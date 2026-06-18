package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;

import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Единый источник контекстного проекта workbench-страницы и оповещений о его смене.
 * Инфраструктура триггеров — по образцу {@link ApplicationsViewHook}.
 */
public final class ActiveProjectTracker implements IStartup
{
    @FunctionalInterface
    public interface ContextProjectListener
    {
        /** Контекстный проект страницы изменился (в т.ч. null → project). */
        void contextProjectChanged(IWorkbenchPage page, IProject previous, IProject current);
    }

    private static final Set<IWorkbenchWindow> HOOKED_WINDOWS =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private static final Set<IWorkbenchWindow> FOCUS_GUARD_WINDOWS =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private static final int PROJECT_SYNC_MAX_ATTEMPTS = 25;

    private static final int NAV_SELECTION_CORRECTION_MAX = 8;

    private static final int NAV_PROJECT_SYNC_MAX_ATTEMPTS = 12;

    private static final int STARTUP_PROJECT_SYNC_GRACE_MS = 2500;

    private static final int GRACE_PROJECT_CORRECTION_MAX = 50;

    private static final Map<IWorkbenchPage, Long> PROJECT_SYNC_GRACE_UNTIL = new WeakHashMap<>();

    private static final Set<IWorkbenchPage> PAGE_BOOTSTRAPPED =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private static final Map<IWorkbenchPage, IProject> CONTEXT_PROJECT_CACHE = new WeakHashMap<>();

    private static final Map<IWorkbenchPage, List<ContextProjectListener>> PAGE_LISTENERS = new WeakHashMap<>();

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
                return;
            for (IWorkbenchWindow w : wb.getWorkbenchWindows())
                hookWindow(w);
            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      { HOOKED_WINDOWS.remove(w); }
            });
        });
    }

    public static void addListener(IWorkbenchPage page, ContextProjectListener listener)
    {
        if (page == null || listener == null)
            return;
        synchronized (PAGE_LISTENERS)
        {
            List<ContextProjectListener> list = PAGE_LISTENERS.computeIfAbsent(page, k -> new ArrayList<>());
            if (!list.contains(listener))
                list.add(listener);
        }
    }

    public static void removeListener(IWorkbenchPage page, ContextProjectListener listener)
    {
        if (page == null || listener == null)
            return;
        synchronized (PAGE_LISTENERS)
        {
            List<ContextProjectListener> list = PAGE_LISTENERS.get(page);
            if (list != null)
                list.remove(listener);
        }
    }

    /** Текущий контекстный проект страницы (последнее уведомлённое значение). */
    public static IProject peek(IWorkbenchPage page)
    {
        if (page == null)
            return null;
        return CONTEXT_PROJECT_CACHE.get(page);
    }

    /** Каноническое разрешение контекстного проекта страницы. */
    public static IProject resolveContextProject(IWorkbenchPage page)
    {
        if (page == null)
            return null;
        IWorkbenchPart active = page.getActivePart();
        if (!isContextDrivingPart(active))
        {
            IProject cached = CONTEXT_PROJECT_CACHE.get(page);
            if (cached != null)
                return cached;
        }
        if (isStartupProjectSyncGrace(page))
        {
            IProject editorProject = editorProjectForPage(page);
            if (editorProject != null)
                return editorProject;
        }
        if (active instanceof IEditorPart)
            return resolveEditorProject((IEditorPart) active);
        if (Global.isNavigatorPart(active))
        {
            IProject navProject = getNavigatorProject(page);
            if (navProject != null)
                return navProject;
            IEditorPart editor = page.getActiveEditor();
            if (editor != null)
                return resolveEditorProject(editor);
            return null;
        }
        IEditorPart editor = page.getActiveEditor();
        if (editor != null)
            return resolveEditorProject(editor);
        return getNavigatorProject(page);
    }

    /** Первичная инициализация страницы: grace, retry, начальный recompute. */
    public static void bootstrapPage(IWorkbenchPage page)
    {
        if (page == null || !PAGE_BOOTSTRAPPED.add(page))
            return;
        for (IEditorReference ref : page.getEditorReferences())
        {
            IEditorPart editor = ref.getEditor(false);
            if (editor != null)
                scheduleEditorReadyProjectSync(page, editor, 0);
        }
        scheduleProjectSyncForPage(page, 0);
        if (page.getActiveEditor() != null || page.getEditorReferences().length > 0)
            beginStartupProjectSyncGrace(page);
        recompute(page);
    }

    static IProject resolveEditorProject(IEditorPart editor)
    {
        if (editor == null)
            return null;
        IProject project = Global.getActiveProject(editor, false);
        if (project != null)
            return project;
        IDtProject dtProject = Global.getProjectFromEditor(editor);
        if (dtProject != null)
            return dtProject.getWorkspaceProject();
        try
        {
            IEditorInput input = editor.getEditorInput();
            if (input != null)
            {
                IFile file = input.getAdapter(IFile.class);
                if (file != null)
                    return file.getProject();
                IProject adapter = input.getAdapter(IProject.class);
                if (adapter != null)
                    return adapter;
            }
        }
        catch (Exception ignored) {}
        return null;
    }

    static IProject getNavigatorProject(IWorkbenchPage page)
    {
        if (page == null)
            return null;
        try
        {
            IViewPart nav = page.findView(Global.NAVIGATOR_VIEW_ID);
            if (!(nav instanceof CommonNavigator))
                return null;
            TreeSelection sel = (TreeSelection) nav.getSite().getSelectionProvider().getSelection();
            if (sel == null || sel.isEmpty())
                return null;
            Object segment = sel.getPaths()[0].getSegment(0);
            return segment instanceof IProject ? (IProject) segment : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /** Редактор или навигатор задают контекст; прочие view — только потребители. */
    private static boolean isContextDrivingPart(IWorkbenchPart part)
    {
        if (part == null)
            return false;
        return part instanceof IEditorPart || Global.isNavigatorPart(part);
    }

    static boolean shouldPreferEditorProject(IWorkbenchPage page)
    {
        if (page == null)
            return false;
        IWorkbenchPart active = page.getActivePart();
        if (Global.isNavigatorPart(active))
            return false;
        if (isStartupProjectSyncGrace(page) && page.getActiveEditor() != null)
            return true;
        return page.getActiveEditor() != null;
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null || !HOOKED_WINDOWS.add(window))
            return;

        installEditorFocusProjectGuard(window);
        installNavigatorSelectionGuard(window);

        IWorkbenchPage page = window.getActivePage();
        if (page != null)
            bootstrapPage(page);
        else
            schedulePageBootstrap(window, 0);

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                IWorkbenchPage p = ref.getPage();
                if (p == null)
                    return;
                bootstrapPage(p);
                if (ref instanceof IEditorReference)
                {
                    IEditorPart ed = ((IEditorReference) ref).getEditor(false);
                    if (ed != null)
                    {
                        if (p.getActiveEditor() == ed)
                            recomputeForActiveEditor(p);
                        scheduleProjectSyncForPage(p, 0);
                        scheduleEditorReadyProjectSync(p, ed, 0);
                    }
                }
                else
                {
                    IWorkbenchPart opened = ref.getPart(false);
                    if (isContextDrivingPart(opened))
                        recompute(p);
                }
            }

            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref.getPart(false);
                if (part == null)
                    return;
                IWorkbenchPage p = part.getSite().getPage();
                if (p == null)
                    return;
                bootstrapPage(p);
                if (part instanceof IEditorPart)
                {
                    IEditorPart editor = (IEditorPart) part;
                    if (resolveEditorProject(editor) == null)
                        scheduleEditorReadyProjectSync(p, editor, 0);
                }
                else if (Global.isNavigatorPart(part) && getNavigatorProject(p) == null)
                    scheduleNavigatorProjectSync(p, 0);
                if (!isContextDrivingPart(part))
                    return;
                recompute(p);
            }

            @Override
            public void partInputChanged(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference))
                    return;
                IEditorPart editor = ((IEditorReference) ref).getEditor(false);
                if (editor == null)
                    return;
                IWorkbenchPage p = ref.getPage();
                if (p == null || p.getActiveEditor() != editor)
                    return;
                recompute(p);
            }

            @Override
            public void partBroughtToTop(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference))
                    return;
                IEditorPart editor = ((IEditorReference) ref).getEditor(false);
                IWorkbenchPage p = ref.getPage();
                if (p != null && editor != null && p.getActiveEditor() == editor)
                    recomputeForActiveEditor(p);
            }

            @Override public void partClosed(IWorkbenchPartReference ref)       {}
            @Override public void partDeactivated(IWorkbenchPartReference ref)  {}
            @Override public void partHidden(IWorkbenchPartReference ref)        {}
            @Override public void partVisible(IWorkbenchPartReference ref)       {}
        });
    }

    private static void installEditorFocusProjectGuard(IWorkbenchWindow window)
    {
        if (window == null || !FOCUS_GUARD_WINDOWS.add(window))
            return;
        Display display = window.getShell().getDisplay();
        final Runnable[] pending = { null };
        Runnable syncIfEditorActive = () ->
        {
            IWorkbenchPage page = window.getActivePage();
            if (page != null)
                recomputeForActiveEditor(page);
        };
        org.eclipse.swt.widgets.Listener focusListener = event ->
        {
            IWorkbenchPage page = window.getActivePage();
            if (page == null || page.getActiveEditor() == null)
                return;
            if (pending[0] != null)
                display.timerExec(-1, pending[0]);
            pending[0] = () ->
            {
                pending[0] = null;
                syncIfEditorActive.run();
            };
            display.timerExec(50, pending[0]);
        };
        org.eclipse.swt.widgets.Listener mouseListener = event ->
        {
            if (event.type != SWT.MouseDown)
                return;
            IWorkbenchPage page = window.getActivePage();
            if (page == null || page.getActiveEditor() == null)
                return;
            if (Global.isNavigatorPart(page.getActivePart()))
                return;
            syncIfEditorActive.run();
        };
        display.addFilter(SWT.FocusIn, focusListener);
        display.addFilter(SWT.MouseDown, mouseListener);
        window.getShell().addDisposeListener(e ->
        {
            FOCUS_GUARD_WINDOWS.remove(window);
            if (!display.isDisposed())
            {
                if (pending[0] != null)
                    display.timerExec(-1, pending[0]);
                display.removeFilter(SWT.FocusIn, focusListener);
                display.removeFilter(SWT.MouseDown, mouseListener);
            }
        });
    }

    private static void installNavigatorSelectionGuard(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        ISelectionListener guard = (IWorkbenchPart part, ISelection selection) ->
        {
            if (!Global.isNavigatorPart(part))
                return;
            IWorkbenchPage activePage = window.getActivePage();
            if (activePage == null)
                return;
            IWorkbenchPart active = activePage.getActivePart();
            if (shouldPreferEditorProject(activePage))
            {
                recompute(activePage);
                scheduleProjectCorrectionAfterNavigator(activePage, 0);
                return;
            }
            if (Global.isNavigatorPart(active))
            {
                recompute(activePage);
                return;
            }
            scheduleProjectCorrectionAfterNavigator(activePage, 0);
        };
        window.getSelectionService().addPostSelectionListener(guard);
    }

    private static void schedulePageBootstrap(IWorkbenchWindow window, int attempt)
    {
        if (window == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(attempt == 0 ? 0 : 100, () ->
        {
            IWorkbenchPage page = window.getActivePage();
            if (page != null)
            {
                bootstrapPage(page);
                return;
            }
            if (attempt < PROJECT_SYNC_MAX_ATTEMPTS)
                schedulePageBootstrap(window, attempt + 1);
        });
    }

    private static void scheduleEditorReadyProjectSync(IWorkbenchPage page, IEditorPart editor, int attempt)
    {
        if (page == null || editor == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(100, () ->
        {
            if (resolveEditorProject(editor) != null)
            {
                recompute(page);
                return;
            }
            if (attempt < 40)
                scheduleEditorReadyProjectSync(page, editor, attempt + 1);
        });
    }

    private static void recomputeForActiveEditor(IWorkbenchPage page)
    {
        if (page == null || !shouldPreferEditorProject(page))
            return;
        IEditorPart editor = page.getActiveEditor();
        if (editor == null)
            return;
        if (resolveEditorProject(editor) != null)
            recompute(page);
        else
            scheduleEditorReadyProjectSync(page, editor, 0);
    }

    private static void scheduleProjectCorrectionAfterNavigator(IWorkbenchPage page, int attempt)
    {
        if (page == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 1 : 50;
        display.timerExec(delay, () ->
        {
            IWorkbenchWindow window = page.getWorkbenchWindow();
            if (window == null || window.getShell().isDisposed())
                return;
            if (Global.isNavigatorPart(page.getActivePart()))
                return;
            recompute(page);
            if (attempt >= NAV_SELECTION_CORRECTION_MAX)
                return;
            IProject expected = resolveContextProject(page);
            IProject cached = peek(page);
            if (!sameProject(expected, cached))
                scheduleProjectCorrectionAfterNavigator(page, attempt + 1);
        });
    }

    private static void scheduleNavigatorProjectSync(IWorkbenchPage page, int attempt)
    {
        if (page == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(attempt == 0 ? 0 : 50, () ->
        {
            if (isStartupProjectSyncGrace(page))
                return;
            if (!Global.isNavigatorPart(page.getActivePart()))
                return;
            IProject navProject = getNavigatorProject(page);
            if (navProject != null)
            {
                recompute(page);
                return;
            }
            if (attempt < NAV_PROJECT_SYNC_MAX_ATTEMPTS)
                scheduleNavigatorProjectSync(page, attempt + 1);
            else
                recompute(page);
        });
    }

    private static void scheduleProjectSyncForPage(IWorkbenchPage page, int attempt)
    {
        if (page == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 100;
        display.timerExec(delay, () ->
        {
            IWorkbenchWindow window = page.getWorkbenchWindow();
            if (window == null || window.getShell().isDisposed())
                return;
            recompute(page);
            if (attempt >= PROJECT_SYNC_MAX_ATTEMPTS)
                return;
            IProject expected = resolveContextProject(page);
            if (expected == null && page.getActiveEditor() != null)
                scheduleProjectSyncForPage(page, attempt + 1);
        });
    }

    private static boolean isStartupProjectSyncGrace(IWorkbenchPage page)
    {
        if (page == null)
            return false;
        Long until = PROJECT_SYNC_GRACE_UNTIL.get(page);
        return until != null && System.currentTimeMillis() < until;
    }

    private static void beginStartupProjectSyncGrace(IWorkbenchPage page)
    {
        if (page == null)
            return;
        long until = System.currentTimeMillis() + STARTUP_PROJECT_SYNC_GRACE_MS;
        PROJECT_SYNC_GRACE_UNTIL.put(page, until);
        scheduleGraceProjectCorrection(page, 0);
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(STARTUP_PROJECT_SYNC_GRACE_MS, () ->
        {
            PROJECT_SYNC_GRACE_UNTIL.remove(page);
            recompute(page);
        });
    }

    private static void scheduleGraceProjectCorrection(IWorkbenchPage page, int attempt)
    {
        if (page == null || !isStartupProjectSyncGrace(page))
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(50, () ->
        {
            if (!isStartupProjectSyncGrace(page))
                return;
            recompute(page);
            if (attempt < GRACE_PROJECT_CORRECTION_MAX)
                scheduleGraceProjectCorrection(page, attempt + 1);
        });
    }

    private static IProject editorProjectForPage(IWorkbenchPage page)
    {
        if (page == null)
            return null;
        IEditorPart editor = page.getActiveEditor();
        return editor != null ? resolveEditorProject(editor) : null;
    }

    private static void recompute(IWorkbenchPage page)
    {
        if (page == null)
            return;
        IProject resolved = resolveContextProject(page);
        IProject previous = CONTEXT_PROJECT_CACHE.get(page);
        if (sameProject(previous, resolved))
            return;
        CONTEXT_PROJECT_CACHE.put(page, resolved);
        fireListeners(page, previous, resolved);
    }

    private static boolean sameProject(IProject a, IProject b)
    {
        if (a == b)
            return true;
        if (a == null || b == null)
            return false;
        return Objects.equals(a.getName(), b.getName());
    }

    private static void fireListeners(IWorkbenchPage page, IProject previous, IProject current)
    {
        List<ContextProjectListener> copy;
        synchronized (PAGE_LISTENERS)
        {
            List<ContextProjectListener> list = PAGE_LISTENERS.get(page);
            if (list == null || list.isEmpty())
                return;
            copy = new ArrayList<>(list);
        }
        for (ContextProjectListener listener : copy)
        {
            try
            {
                listener.contextProjectChanged(page, previous, current);
            }
            catch (Exception ignored) {}
        }
    }
}
