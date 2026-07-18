package tormozit;

import java.util.ArrayList;
import java.util.WeakHashMap;

import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.spelling.SpellingProblem;

/**
 * Орфография в поле сообщения коммита EGit ({@code SpellcheckableMessageArea} /
 * {@code StagingView$…}): тот же интерактивный hover с proposals, что в модуле BSL
 * ({@link SpellCheckHook#installSpellingQuickFixHover}).
 */
public final class CommitMessageSpellCheckHook implements IStartup
{
    private static final String AREA_CLASS =
        "org.eclipse.egit.ui.internal.dialogs.SpellcheckableMessageArea"; //$NON-NLS-1$
    private static final String STAGING_VIEW_ID = "org.eclipse.egit.ui.StagingView"; //$NON-NLS-1$
    private static final String STAGING_VIEW_CLASS =
        "org.eclipse.egit.ui.internal.staging.StagingView"; //$NON-NLS-1$
    private static final String HOOKED_KEY = "tormozit.commitSpell.hover"; //$NON-NLS-1$
    private static final int SCAN_RETRY_MS = 400;
    private static final int SCAN_MAX_ATTEMPTS = 80;

    private static final WeakHashMap<Control, Boolean> hookedAreas = new WeakHashMap<>();

    private static volatile Class<?> spellAreaClass;
    private static volatile String loggedStagingClass;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            Global.tempLog("spellCheck", "commitSpell: earlyStartup"); //$NON-NLS-1$ //$NON-NLS-2$
            installWorkbenchListeners();
            scheduleScanAll(0);
        });
    }

    private static void installWorkbenchListeners()
    {
        try
        {
            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override
                public void windowOpened(IWorkbenchWindow window)
                {
                    attachWindow(window);
                    scheduleScanAll(0);
                }

                @Override
                public void windowActivated(IWorkbenchWindow window)
                {
                    scheduleScanAll(0);
                }

                @Override
                public void windowClosed(IWorkbenchWindow window)
                {
                }

                @Override
                public void windowDeactivated(IWorkbenchWindow window)
                {
                }
            });
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                attachWindow(window);
        }
        catch (Exception e)
        {
            Global.tempLog("spellCheck", "commitSpell: windowListener: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void attachWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return;
        page.addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference partRef)
            {
                scheduleScanAll(0);
            }

            @Override
            public void partActivated(IWorkbenchPartReference partRef)
            {
                scheduleScanAll(0);
            }

            @Override
            public void partVisible(IWorkbenchPartReference partRef)
            {
                scheduleScanAll(0);
            }

            @Override
            public void partBroughtToTop(IWorkbenchPartReference partRef)
            {
            }

            @Override
            public void partClosed(IWorkbenchPartReference partRef)
            {
            }

            @Override
            public void partDeactivated(IWorkbenchPartReference partRef)
            {
            }

            @Override
            public void partHidden(IWorkbenchPartReference partRef)
            {
            }

            @Override
            public void partInputChanged(IWorkbenchPartReference partRef)
            {
            }
        });
    }

    private static void scheduleScanAll(int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(attempt == 0 ? 0 : SCAN_RETRY_MS, () ->
        {
            if (!SpellCheckHook.isComfortPlatformSpellingActive())
            {
                if (attempt == 0)
                    Global.tempLog("spellCheck", "commitSpell: Comfort spelling inactive, skip"); //$NON-NLS-1$ //$NON-NLS-2$
                if (attempt < SCAN_MAX_ATTEMPTS)
                    scheduleScanAll(attempt + 1);
                return;
            }
            int found = 0;
            int hooked = 0;
            try
            {
                int[] fromViews = hookStagingViews();
                found += fromViews[0];
                hooked += fromViews[1];
                for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                {
                    if (window == null)
                        continue;
                    Shell shell = window.getShell();
                    if (shell != null && !shell.isDisposed())
                    {
                        int[] counts = scanControl(shell);
                        found += counts[0];
                        hooked += counts[1];
                    }
                    for (Shell extra : display.getShells())
                    {
                        if (extra == null || extra.isDisposed() || extra == shell)
                            continue;
                        int[] more = scanControl(extra);
                        found += more[0];
                        hooked += more[1];
                    }
                }
            }
            catch (Exception e)
            {
                Global.tempLog("spellCheck", "commitSpell: scan: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            }
            // Не логируем периодические проходы — только первую установку hover.
            if (hooked > 0)
                Global.tempLog("spellCheck", "commitSpell: scan attempt=" + attempt //$NON-NLS-1$ //$NON-NLS-2$
                    + " areas=" + found + " newlyHooked=" + hooked); //$NON-NLS-1$ //$NON-NLS-2$
            if (attempt < SCAN_MAX_ATTEMPTS)
                scheduleScanAll(attempt + 1);
        });
    }

    /** Прямой доступ к {@code StagingView.commitMessageText}. */
    private static int[] hookStagingViews()
    {
        int found = 0;
        int hooked = 0;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            if (window == null)
                continue;
            for (IWorkbenchPage page : window.getPages())
            {
                if (page == null)
                    continue;
                IViewReference[] refs = page.getViewReferences();
                if (refs == null)
                    continue;
                for (IViewReference ref : refs)
                {
                    if (ref == null)
                        continue;
                    if (!STAGING_VIEW_ID.equals(ref.getId()))
                        continue;
                    IViewPart view = ref.getView(false);
                    if (view == null)
                        continue;
                    String stagingClass = view.getClass().getName();
                    if (!STAGING_VIEW_CLASS.equals(stagingClass)
                        && !stagingClass.equals(loggedStagingClass))
                    {
                        loggedStagingClass = stagingClass;
                        Global.tempLog("spellCheck", "commitSpell: staging class=" //$NON-NLS-1$ //$NON-NLS-2$
                            + stagingClass);
                    }
                    Object area = Global.getField(view, "commitMessageText"); //$NON-NLS-1$
                    if (!(area instanceof Control control))
                    {
                        // Один раз на отсутствие поля — иначе спам при каждом scan.
                        if (loggedStagingClass == null || !loggedStagingClass.endsWith("#noText")) //$NON-NLS-1$
                        {
                            loggedStagingClass = stagingClass + "#noText"; //$NON-NLS-1$
                            Global.tempLog("spellCheck", "commitSpell: StagingView.commitMessageText=" //$NON-NLS-1$ //$NON-NLS-2$
                                + (area == null ? "null" : area.getClass().getName())); //$NON-NLS-1$
                        }
                        continue;
                    }
                    found++;
                    if (hookArea(control))
                        hooked++;
                }
            }
        }
        return new int[] { found, hooked };
    }

    /** @return [foundAreas, newlyHooked] */
    private static int[] scanControl(Control root)
    {
        int found = 0;
        int hooked = 0;
        if (root == null || root.isDisposed())
            return new int[] { 0, 0 };
        if (isSpellMessageArea(root))
        {
            found++;
            if (hookArea(root))
                hooked++;
        }
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                int[] sub = scanControl(child);
                found += sub[0];
                hooked += sub[1];
            }
        }
        return new int[] { found, hooked };
    }

    /** Учитывает {@code StagingView$19} и прочие подклассы/анонимы. */
    private static boolean isSpellMessageArea(Control control)
    {
        if (control == null || control.isDisposed())
            return false;
        Class<?> areaCl = resolveSpellAreaClass(control);
        if (areaCl != null && areaCl.isInstance(control))
            return true;
        for (Class<?> c = control.getClass(); c != null; c = c.getSuperclass())
        {
            if (AREA_CLASS.equals(c.getName()))
                return true;
        }
        return false;
    }

    private static Class<?> resolveSpellAreaClass(Control control)
    {
        Class<?> cached = spellAreaClass;
        if (cached != null)
            return cached;
        try
        {
            ClassLoader cl = control.getClass().getClassLoader();
            cached = Class.forName(AREA_CLASS, false, cl);
            spellAreaClass = cached;
            return cached;
        }
        catch (ClassNotFoundException e)
        {
            return null;
        }
    }

    private static boolean hookArea(Control area)
    {
        if (area == null || area.isDisposed())
            return false;
        boolean already = Boolean.TRUE.equals(area.getData(HOOKED_KEY)) || hookedAreas.containsKey(area);
        Object viewerObj = Global.getField(area, "sourceViewer"); //$NON-NLS-1$
        if (!(viewerObj instanceof SourceViewer viewer))
        {
            if (!already)
                Global.tempLog("spellCheck", "commitSpell: area без SourceViewer: class=" //$NON-NLS-1$ //$NON-NLS-2$
                    + area.getClass().getName() + " field=" //$NON-NLS-1$
                    + (viewerObj == null ? "null" : viewerObj.getClass().getName())); //$NON-NLS-1$
            return false;
        }
        try
        {
            Object configObj = Global.getField(area, "configuration"); //$NON-NLS-1$
            SourceViewerConfiguration config =
                configObj instanceof SourceViewerConfiguration c ? c : null;
            // Переустанавливаем hover: configure()/recreate может вернуть DefaultTextHover.
            if (!SpellCheckHook.installSpellingQuickFixHover(viewer, config))
            {
                Global.tempLog("spellCheck", "commitSpell: installSpellingQuickFixHover failed on " //$NON-NLS-1$ //$NON-NLS-2$
                    + area.getClass().getName());
                return false;
            }
            if (!already)
            {
                area.setData(HOOKED_KEY, Boolean.TRUE);
                hookedAreas.put(area, Boolean.TRUE);
                area.addDisposeListener(e -> hookedAreas.remove(area));
                Global.tempLog("spellCheck", "commitSpell: hover installed on " //$NON-NLS-1$ //$NON-NLS-2$
                    + area.getClass().getName());
                return true;
            }
            return false;
        }
        catch (Exception e)
        {
            Global.tempLog("spellCheck", "commitSpell: installHover: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    /**
     * После изменения пользовательского словаря: снять ошибки с этим словом
     * или форсировать reconcile при отмене.
     */
    static void onUserDictionaryChanged(String word, boolean added)
    {
        if (word == null || word.isEmpty())
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Runnable run = () ->
        {
            for (Control area : new ArrayList<>(hookedAreas.keySet()))
            {
                if (area == null || area.isDisposed())
                    continue;
                Object viewerObj = Global.getField(area, "sourceViewer"); //$NON-NLS-1$
                if (!(viewerObj instanceof ISourceViewer viewer))
                    continue;
                if (added)
                    SpellingProblem.removeAll(viewer, word);
                else
                    forceSpellReconcile(viewer);
            }
        };
        if (display.getThread() == Thread.currentThread())
            run.run();
        else
            display.asyncExec(run);
    }

    private static void forceSpellReconcile(ISourceViewer viewer)
    {
        if (viewer == null)
            return;
        try
        {
            Object reconciler = Global.getField(viewer, "fReconciler"); //$NON-NLS-1$
            if (reconciler != null)
                Global.invoke(reconciler, "forceReconciling"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.tempLog("spellCheck", "commitSpell: forceReconciling: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
