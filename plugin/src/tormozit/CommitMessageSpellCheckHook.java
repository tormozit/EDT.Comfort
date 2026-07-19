package tormozit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
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
import org.eclipse.ui.texteditor.spelling.SpellingAnnotation;
import org.eclipse.ui.texteditor.spelling.SpellingProblem;

/**
 * Орфография в поле сообщения коммита EGit ({@code SpellcheckableMessageArea} /
 * {@code StagingView$…}): те же правила токенизации, что BSL/свойства —
 * {@link ComfortSpellingEngine#findMisspelledRanges}; hover —
 * {@link SpellCheckHook#installSpellingQuickFixHover}.
 * Штатный SpellingReconcileStrategy отключается (иначе whole-token без CamelCase).
 */
public final class CommitMessageSpellCheckHook implements IStartup
{
    private static final String AREA_CLASS =
        "org.eclipse.egit.ui.internal.dialogs.SpellcheckableMessageArea"; //$NON-NLS-1$
    private static final String STAGING_VIEW_ID = "org.eclipse.egit.ui.StagingView"; //$NON-NLS-1$
    private static final String STAGING_VIEW_CLASS =
        "org.eclipse.egit.ui.internal.staging.StagingView"; //$NON-NLS-1$
    private static final String HOOKED_KEY = "tormozit.commitSpell.hover"; //$NON-NLS-1$
    private static final String JOB_NAME = "Comfort: орфография сообщения коммита"; //$NON-NLS-1$
    private static final int SCAN_RETRY_MS = 400;
    private static final int SCAN_MAX_ATTEMPTS = 80;
    private static final int DEBOUNCE_MS = 400;

    private static final WeakHashMap<Control, Boolean> hookedAreas = new WeakHashMap<>();
    private static final WeakHashMap<SourceViewer, CommitSpellSession> sessions = new WeakHashMap<>();

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
            }
            if (hooked > 0)
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
                    }
                    Object area = Global.getField(view, "commitMessageText"); //$NON-NLS-1$
                    if (!(area instanceof Control control))
                    {
                        if (loggedStagingClass == null || !loggedStagingClass.endsWith("#noText")) //$NON-NLS-1$
                        {
                            loggedStagingClass = stagingClass + "#noText"; //$NON-NLS-1$
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
            return false;
        try
        {
            Object configObj = Global.getField(area, "configuration"); //$NON-NLS-1$
            SourceViewerConfiguration config =
                configObj instanceof SourceViewerConfiguration c ? c : null;
            // Переустанавливаем hover: configure()/recreate может вернуть DefaultTextHover.
            if (!SpellCheckHook.installSpellingQuickFixHover(viewer, config))
            {
                return false;
            }
            disableDefaultSpellReconciler(viewer);
            CommitSpellSession session = sessions.get(viewer);
            if (session == null)
            {
                session = new CommitSpellSession(viewer);
                sessions.put(viewer, session);
                session.attach();
            }
            session.schedule(0);
            if (!already)
            {
                area.setData(HOOKED_KEY, Boolean.TRUE);
                hookedAreas.put(area, Boolean.TRUE);
                area.addDisposeListener(e ->
                {
                    hookedAreas.remove(area);
                    CommitSpellSession s = sessions.remove(viewer);
                    if (s != null)
                        s.dispose();
                });
                return true;
            }
            return false;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * EGit ставит {@code SpellingReconcileStrategy} (DefaultSpellChecker, whole-token).
     * Отключаем через {@code fReconciler} (публичного get/setReconciler у SourceViewer нет) —
     * проверку ведёт {@link CommitSpellSession} через findMisspelledRanges.
     */
    private static void disableDefaultSpellReconciler(SourceViewer viewer)
    {
        if (viewer == null)
            return;
        try
        {
            Object reconciler = Global.getField(viewer, "fReconciler"); //$NON-NLS-1$
            if (reconciler == null)
                return;
            Global.invoke(reconciler, "uninstall"); //$NON-NLS-1$
            Global.setField(viewer, "fReconciler", null); //$NON-NLS-1$
        }
        catch (Exception e)
        {
        }
    }

    /**
     * После изменения пользовательского словаря — перескан Comfort-сессий.
     */
    static void onUserDictionaryChanged(String word, boolean added)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Runnable run = () ->
        {
            for (CommitSpellSession session : new ArrayList<>(sessions.values()))
            {
                if (session != null)
                    session.schedule(0);
            }
        };
        if (display.getThread() == Thread.currentThread())
            run.run();
        else
            display.asyncExec(run);
    }

    /** Debounced Job: {@link ComfortSpellingEngine#findMisspelledRanges} → SpellingAnnotation. */
    private static final class CommitSpellSession
    {
        private final SourceViewer viewer;
        private final IDocumentListener documentListener;
        private volatile Job job;
        private int scheduleGeneration;

        CommitSpellSession(SourceViewer viewer)
        {
            this.viewer = viewer;
            this.documentListener = new IDocumentListener()
            {
                @Override
                public void documentAboutToBeChanged(DocumentEvent event)
                {
                }

                @Override
                public void documentChanged(DocumentEvent event)
                {
                    schedule(DEBOUNCE_MS);
                }
            };
        }

        void attach()
        {
            IDocument doc = viewer.getDocument();
            if (doc != null)
                doc.addDocumentListener(documentListener);
        }

        void dispose()
        {
            cancelJob();
            try
            {
                IDocument doc = viewer.getDocument();
                if (doc != null)
                    doc.removeDocumentListener(documentListener);
            }
            catch (Exception ignored)
            {
            }
            removeAllSpellingAnnotations();
        }

        void schedule(int delayMs)
        {
            if (!SpellCheckHook.isComfortPlatformSpellingActive())
            {
                cancelJob();
                removeAllSpellingAnnotations();
                return;
            }
            final int gen = ++scheduleGeneration;
            cancelJob();
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.timerExec(Math.max(0, delayMs), () ->
            {
                if (gen != scheduleGeneration)
                    return;
                startJob(gen);
            });
        }

        private void cancelJob()
        {
            if (job != null)
                job.cancel();
            job = null;
        }

        private void startJob(int gen)
        {
            if (!SpellCheckHook.isComfortPlatformSpellingActive())
                return;
            IDocument document = viewer.getDocument();
            if (document == null)
                return;
            final String text;
            try
            {
                text = document.get();
            }
            catch (Exception e)
            {
                return;
            }
            Job checkJob = new Job(JOB_NAME)
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    if (monitor.isCanceled() || gen != scheduleGeneration)
                        return Status.CANCEL_STATUS;
                    List<int[]> ranges = ComfortSpellingEngine.findMisspelledRanges(text);
                    if (monitor.isCanceled() || gen != scheduleGeneration)
                        return Status.CANCEL_STATUS;
                    List<CommitProblem> problems = new ArrayList<>();
                    if (ranges != null)
                    {
                        for (int[] r : ranges)
                        {
                            if (r == null || r.length < 2 || r[1] <= 0)
                                continue;
                            int off = r[0];
                            int len = r[1];
                            if (off < 0 || off + len > text.length())
                                continue;
                            String word = text.substring(off, off + len);
                            problems.add(new CommitProblem(off, len, word));
                        }
                    }
                    final List<CommitProblem> toApply = problems;
                    Display.getDefault().asyncExec(() ->
                    {
                        if (gen != scheduleGeneration)
                            return;
                        applyAnnotations(toApply);
                    });
                    return Status.OK_STATUS;
                }
            };
            checkJob.setSystem(true);
            checkJob.setPriority(Job.DECORATE);
            this.job = checkJob;
            checkJob.schedule();
        }

        void removeAllSpellingAnnotations()
        {
            IAnnotationModel model = viewer.getAnnotationModel();
            if (model == null)
                return;
            List<Annotation> toRemove = new ArrayList<>();
            Iterator<?> it = model.getAnnotationIterator();
            while (it.hasNext())
            {
                Object next = it.next();
                if (next instanceof SpellingAnnotation)
                    toRemove.add((Annotation) next);
            }
            if (toRemove.isEmpty())
                return;
            if (model instanceof IAnnotationModelExtension ext)
            {
                Annotation[] arr = toRemove.toArray(new Annotation[0]);
                ext.replaceAnnotations(arr, Map.of());
            }
            else
            {
                for (Annotation a : toRemove)
                    model.removeAnnotation(a);
            }
        }

        void applyAnnotations(List<CommitProblem> problems)
        {
            IAnnotationModel model = viewer.getAnnotationModel();
            if (model == null)
                return;
            // Полный replace всех spelling — штатный reconciler мог успеть что-то положить
            List<Annotation> toRemove = new ArrayList<>();
            Iterator<?> it = model.getAnnotationIterator();
            while (it.hasNext())
            {
                Object next = it.next();
                if (next instanceof SpellingAnnotation)
                    toRemove.add((Annotation) next);
            }
            Map<Annotation, Position> batch = new LinkedHashMap<>();
            if (problems != null)
            {
                for (CommitProblem p : problems)
                {
                    SpellingAnnotation ann = new SpellingAnnotation(p);
                    batch.put(ann, new Position(p.getOffset(), p.getLength()));
                }
            }
            if (model instanceof IAnnotationModelExtension ext)
            {
                Annotation[] removeArr = toRemove.toArray(new Annotation[0]);
                ext.replaceAnnotations(removeArr, batch);
            }
            else
            {
                for (Annotation a : toRemove)
                    model.removeAnnotation(a);
                for (Map.Entry<Annotation, Position> e : batch.entrySet())
                    model.addAnnotation(e.getKey(), e.getValue());
            }
        }
    }

    /** Маркер Comfort-проблемы в сообщении коммита (сегмент после findMisspelledRanges). */
    private static final class CommitProblem extends SpellingProblem
    {
        private final int offset;
        private final int length;
        private final String word;

        CommitProblem(int offset, int length, String word)
        {
            this.offset = offset;
            this.length = length;
            this.word = word;
        }

        @Override
        public int getOffset()
        {
            return offset;
        }

        @Override
        public int getLength()
        {
            return length;
        }

        @Override
        public String getMessage()
        {
            return SpellCheckHook.spellingErrorMessage(word);
        }

        @Override
        public ICompletionProposal[] getProposals()
        {
            // Hover собирает proposals сам (SpellCheckHook.ComfortSpellingQuickFixHover)
            return new ICompletionProposal[0];
        }
    }
}
