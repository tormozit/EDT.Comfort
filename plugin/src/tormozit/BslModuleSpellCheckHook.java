package tormozit;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlExtension2;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.quickassist.IQuickAssistInvocationContext;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.spelling.SpellingAnnotation;
import org.eclipse.ui.texteditor.spelling.SpellingProblem;
import org.eclipse.ui.texteditor.spelling.SpellingService;
import org.eclipse.xtext.AbstractRule;
import org.eclipse.xtext.Keyword;
import org.eclipse.xtext.RuleCall;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Орфография Hunspell в редакторе модуля BSL: видимый диапазон, non-keyword листья
 * node model, camelCase-сегменты, {@link SpellingAnnotation}. Только при
 * {@link SpellCheckHook#isComfortPlatformSpellingActive()}.
 */
public final class BslModuleSpellCheckHook implements IStartup
{
    private static final String JOB_NAME = "Комфорт: орфография модуля"; //$NON-NLS-1$
    private static final String SUGGEST_JOB_NAME = "Комфорт: варианты орфографии"; //$NON-NLS-1$
    private static final int DEBOUNCE_MS = 600;
    private static final int SUGGEST_MAX = 12;
    private static final int SUGGEST_UI_THROTTLE_MS = 300;
    private static final String LOADING_MARKER = "..."; //$NON-NLS-1$
    /** Выше вариантов; Xtext {@code sortQuickfixes} иначе сортирует по алфавиту. */
    private static final int PRIORITY_ADD_TO_DICTIONARY = 1000;
    private static final int PRIORITY_SUGGESTION = 100;
    private static final int PRIORITY_LOADING = 1;

    private static final Map<BslXtextEditor, EditorSession> SESSIONS = new WeakHashMap<>();
    /** Активные сессии подсказок по слову (lowercase). */
    private static final ConcurrentHashMap<String, SuggestSession> SUGGEST_SESSIONS =
        new ConcurrentHashMap<>();

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            installWorkbenchHooks();
            installPreferenceListener();
        });
    }

    private static void installPreferenceListener()
    {
        IPropertyChangeListener listener = event ->
        {
            String prop = event.getProperty();
            if (prop == null)
                return;
            if (SpellingService.PREFERENCE_SPELLING_ENABLED.equals(prop)
                || SpellingService.PREFERENCE_SPELLING_ENGINE.equals(prop)
                || PreferenceConstants.SPELLING_LOCALE.equals(prop)
                || ComfortSettings.PREF_SPELLING_CHECK_IDENTIFIERS_VISIBLE.equals(prop)
                || isSpellingIgnorePreference(prop))
            {
                Display.getDefault().asyncExec(BslModuleSpellCheckHook::onSpellingPrefsChanged);
            }
        };
        EditorsUI.getPreferenceStore().addPropertyChangeListener(listener);
        PreferenceConstants.getPreferenceStore().addPropertyChangeListener(listener);
        ContentAssistSettings cas = ContentAssistSettings.getInstance();
        if (cas != null)
            cas.getPreferenceStore().addPropertyChangeListener(listener);
    }

    private static boolean isSpellingIgnorePreference(String prop)
    {
        return PreferenceConstants.SPELLING_IGNORE_DIGITS.equals(prop)
            || PreferenceConstants.SPELLING_IGNORE_MIXED.equals(prop)
            || PreferenceConstants.SPELLING_IGNORE_SENTENCE.equals(prop)
            || PreferenceConstants.SPELLING_IGNORE_UPPER.equals(prop)
            || PreferenceConstants.SPELLING_IGNORE_URLS.equals(prop)
            || PreferenceConstants.SPELLING_IGNORE_SINGLE_LETTERS.equals(prop)
            || PreferenceConstants.SPELLING_IGNORE_NON_LETTERS.equals(prop)
            || PreferenceConstants.SPELLING_IGNORE_JAVA_STRINGS.equals(prop)
            || PreferenceConstants.SPELLING_IGNORE_AMPERSAND_IN_PROPERTIES.equals(prop);
    }

    private static void onSpellingPrefsChanged()
    {
        if (!SpellCheckHook.isComfortPlatformSpellingActive())
        {
            for (EditorSession session : new ArrayList<>(SESSIONS.values()))
                clearAnnotations(session);
            return;
        }
        for (EditorSession session : new ArrayList<>(SESSIONS.values()))
            session.schedule(0);
    }

    private static void installWorkbenchHooks()
    {
        IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
        for (IWorkbenchWindow window : windows)
            hookWindow(window);
        PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
            @Override public void windowActivated(IWorkbenchWindow w) {}
            @Override public void windowDeactivated(IWorkbenchWindow w) {}
            @Override public void windowClosed(IWorkbenchWindow w) {}
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null)
                continue;
            for (IEditorReference ref : page.getEditorReferences())
                tryAttach(ref.getPart(false));
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)    { tryAttachFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { tryAttachFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref)   { tryAttachFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) { tryAttachFromRef(ref); }
            @Override public void partInputChanged(IWorkbenchPartReference ref) { tryAttachFromRef(ref); }
            @Override public void partClosed(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                detachPart(part);
            }
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}

            private void tryAttachFromRef(IWorkbenchPartReference ref)
            {
                tryAttach(ref != null ? ref.getPart(false) : null);
            }
        });
    }

    private static void tryAttach(IWorkbenchPart part)
    {
        if (part instanceof BslXtextEditor bsl)
        {
            attachEditor(bsl);
            return;
        }
        if (part instanceof DtGranularEditor<?> granular)
        {
            Object page = granular.getActivePageInstance();
            if (page instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
            {
                IEditorPart embedded = xtextPage.getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor bsl)
                    attachEditor(bsl);
            }
        }
    }

    private static void detachPart(IWorkbenchPart part)
    {
        if (part instanceof BslXtextEditor bsl)
            detachEditor(bsl);
    }

    private static void attachEditor(BslXtextEditor editor)
    {
        if (editor == null || SESSIONS.containsKey(editor))
        {
            EditorSession existing = SESSIONS.get(editor);
            if (existing != null && SpellCheckHook.isComfortPlatformSpellingActive())
                existing.schedule(0);
            return;
        }
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null)
            return;
        EditorSession session = new EditorSession(editor, viewer);
        SESSIONS.put(editor, session);
        session.install();
        Global.tempLog("spellCheck", "BslModuleSpell: attach " + editor); //$NON-NLS-1$ //$NON-NLS-2$
        if (SpellCheckHook.isComfortPlatformSpellingActive())
        {
            session.schedule(0);
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
            {
                display.timerExec(1500, () ->
                {
                    EditorSession later = SESSIONS.get(editor);
                    if (later != null)
                        later.schedule(0);
                });
            }
        }
    }

    private static void detachEditor(BslXtextEditor editor)
    {
        EditorSession session = SESSIONS.remove(editor);
        if (session != null)
            session.dispose();
    }

    private static void clearAnnotations(EditorSession session)
    {
        if (session == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(session::removeComfortAnnotations);
    }

    private static final class EditorSession
    {
        final BslXtextEditor editor;
        final ISourceViewer viewer;
        final IDocumentListener documentListener;
        final org.eclipse.jface.text.IViewportListener viewportListener;
        Job job;
        volatile int scheduleGeneration;

        EditorSession(BslXtextEditor editor, ISourceViewer viewer)
        {
            this.editor = editor;
            this.viewer = viewer;
            this.documentListener = new IDocumentListener()
            {
                @Override public void documentAboutToBeChanged(DocumentEvent event) {}
                @Override public void documentChanged(DocumentEvent event) { schedule(DEBOUNCE_MS); }
            };
            this.viewportListener = verticalOffset -> schedule(DEBOUNCE_MS);
        }

        void install()
        {
            IDocument doc = viewer.getDocument();
            if (doc != null)
                doc.addDocumentListener(documentListener);
            viewer.addViewportListener(viewportListener);
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
            try
            {
                viewer.removeViewportListener(viewportListener);
            }
            catch (Exception ignored)
            {
            }
            removeComfortAnnotations();
        }

        void schedule(int delayMs)
        {
            if (!SpellCheckHook.isComfortPlatformSpellingActive())
            {
                cancelJob();
                removeComfortAnnotations();
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
            if (!(document instanceof IXtextDocument xdoc))
                return;

            final int visOffset;
            final int visLength;
            try
            {
                int top = Math.max(0, viewer.getTopIndex());
                int bottom = viewer.getBottomIndex();
                int lines = document.getNumberOfLines();
                if (lines <= 0)
                    return;
                if (bottom < top)
                    bottom = top;
                if (bottom >= lines)
                    bottom = lines - 1;
                visOffset = document.getLineOffset(top);
                int endLineOffset = document.getLineOffset(bottom);
                int endLineLength = document.getLineLength(bottom);
                visLength = Math.max(0, endLineOffset + endLineLength - visOffset);
            }
            catch (Exception e)
            {
                Global.tempLog("spellCheck", "BslModuleSpell: visibleRegion fail " + e); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            if (visLength <= 0)
                return;

            final int visEnd = visOffset + visLength;
            Job checkJob = new Job(JOB_NAME)
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    if (monitor.isCanceled() || gen != scheduleGeneration)
                        return Status.CANCEL_STATUS;
                    List<ModuleProblem> problems;
                    try
                    {
                        boolean checkIdents = ComfortSettings.isSpellingCheckIdentifiersVisible();
                        problems = xdoc.readOnly(
                            new CollectProblems(visOffset, visEnd, checkIdents, monitor));
                    }
                    catch (Exception e)
                    {
                        Global.tempLog("spellCheck", "BslModuleSpell: readOnly " + e); //$NON-NLS-1$ //$NON-NLS-2$
                        return Status.CANCEL_STATUS;
                    }
                    if (monitor.isCanceled() || gen != scheduleGeneration)
                        return Status.CANCEL_STATUS;
                    final List<ModuleProblem> toApply = problems != null ? problems : List.of();
                    Display.getDefault().asyncExec(() ->
                    {
                        if (gen != scheduleGeneration)
                            return;
                        applyAnnotations(toApply);
                        Global.tempLog("spellCheck", "BslModuleSpell: applied=" + toApply.size() //$NON-NLS-1$ //$NON-NLS-2$
                            + " vis=[" + visOffset + "," + visLength + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    });
                    return Status.OK_STATUS;
                }
            };
            checkJob.setSystem(true);
            checkJob.setPriority(Job.DECORATE);
            this.job = checkJob;
            checkJob.schedule();
        }

        void removeComfortAnnotations()
        {
            IAnnotationModel model = viewer.getAnnotationModel();
            if (model == null)
                return;
            List<Annotation> toRemove = new ArrayList<>();
            Iterator<?> it = model.getAnnotationIterator();
            while (it.hasNext())
            {
                Object next = it.next();
                if (next instanceof SpellingAnnotation sa
                    && sa.getSpellingProblem() instanceof ModuleProblem)
                    toRemove.add(sa);
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

        void applyAnnotations(List<ModuleProblem> problems)
        {
            IAnnotationModel model = viewer.getAnnotationModel();
            if (model == null)
                return;
            removeComfortAnnotations();
            if (problems == null || problems.isEmpty())
                return;
            if (model instanceof IAnnotationModelExtension ext)
            {
                Map<Annotation, Position> batch = new java.util.LinkedHashMap<>();
                for (ModuleProblem p : problems)
                {
                    SpellingAnnotation ann = new SpellingAnnotation(p);
                    Position pos = new Position(p.getOffset(), p.getLength());
                    p.attachPresentation(ann, pos, viewer);
                    batch.put(ann, pos);
                }
                ext.replaceAnnotations(new Annotation[0], batch);
            }
            else
            {
                for (ModuleProblem p : problems)
                {
                    SpellingAnnotation ann = new SpellingAnnotation(p);
                    Position pos = new Position(p.getOffset(), p.getLength());
                    p.attachPresentation(ann, pos, viewer);
                    model.addAnnotation(ann, pos);
                }
            }
        }
    }

    private static final class CollectProblems implements IUnitOfWork<List<ModuleProblem>, XtextResource>
    {
        private final int visOffset;
        private final int visEnd;
        private final boolean checkIdentifiers;
        private final IProgressMonitor monitor;

        CollectProblems(int visOffset, int visEnd, boolean checkIdentifiers, IProgressMonitor monitor)
        {
            this.visOffset = visOffset;
            this.visEnd = visEnd;
            this.checkIdentifiers = checkIdentifiers;
            this.monitor = monitor;
        }

        @Override
        public List<ModuleProblem> exec(XtextResource resource) throws Exception
        {
            List<ModuleProblem> result = new ArrayList<>();
            if (resource == null || resource.getParseResult() == null)
                return result;
            ICompositeNode root = resource.getParseResult().getRootNode();
            if (root == null)
                return result;
            for (ILeafNode leaf : root.getLeafNodes())
            {
                if (monitor != null && monitor.isCanceled())
                    return result;
                int leafStart = leaf.getOffset();
                int leafEnd = leaf.getEndOffset();
                if (leafEnd <= visOffset || leafStart >= visEnd)
                    continue;
                if (isKeywordLeaf(leaf))
                    continue;
                LeafKind kind = classifyLeaf(leaf);
                if (!checkIdentifiers)
                {
                    if (kind == LeafKind.IDENTIFIER || kind == LeafKind.STRING)
                        continue;
                }
                String text = leaf.getText();
                if (text == null || text.isEmpty() || !containsLetter(text))
                    continue;
                List<int[]> relative = ComfortSpellingEngine.findMisspelledRanges(text);
                for (int[] r : relative)
                {
                    int abs = leafStart + r[0];
                    int len = r[1];
                    if (abs + len <= visOffset || abs >= visEnd)
                        continue;
                    String word = text.substring(r[0], r[0] + r[1]);
                    if (!checkIdentifiers && kind == LeafKind.COMMENT && looksLikeIdentifier(word))
                        continue;
                    result.add(new ModuleProblem(abs, len, word));
                }
            }
            return result;
        }
    }

    private enum LeafKind
    {
        IDENTIFIER, STRING, COMMENT, OTHER
    }

    private static boolean isKeywordLeaf(ILeafNode leaf)
    {
        EObject grammar = leaf.getGrammarElement();
        return grammar instanceof Keyword;
    }

    private static LeafKind classifyLeaf(ILeafNode leaf)
    {
        String rule = terminalRuleName(leaf);
        if (rule != null)
        {
            String upper = rule.toUpperCase(java.util.Locale.ROOT);
            if (upper.contains("IDENT")) //$NON-NLS-1$
                return LeafKind.IDENTIFIER;
            if (upper.contains("STRING")) //$NON-NLS-1$
                return LeafKind.STRING;
            if (upper.contains("COMMENT")) //$NON-NLS-1$
                return LeafKind.COMMENT;
        }
        if (leaf.isHidden())
        {
            String t = leaf.getText();
            if (t != null && t.contains("//")) //$NON-NLS-1$
                return LeafKind.COMMENT;
        }
        return LeafKind.OTHER;
    }

    private static String terminalRuleName(ILeafNode leaf)
    {
        EObject grammar = leaf.getGrammarElement();
        if (grammar instanceof RuleCall ruleCall && ruleCall.getRule() != null)
            return ruleCall.getRule().getName();
        if (grammar instanceof AbstractRule rule)
            return rule.getName();
        return null;
    }

    /** Заглавная буква не на первой позиции — похоже на CamelCase-идентификатор. */
    private static boolean looksLikeIdentifier(String word)
    {
        if (word == null || word.length() < 2)
            return false;
        for (int i = 1; i < word.length(); i++)
        {
            if (Character.isUpperCase(word.charAt(i)))
                return true;
        }
        return false;
    }

    private static boolean containsLetter(String text)
    {
        for (int i = 0; i < text.length(); i++)
        {
            if (Character.isLetter(text.charAt(i)))
                return true;
        }
        return false;
    }

    /** Маркер наших проблем — чтобы снимать только Comfort-аннотации модуля. */
    private static final class ModuleProblem extends SpellingProblem
    {
        private final int offset;
        private final int length;
        private final String word;
        private volatile SpellingAnnotation annotation;
        private volatile Position position;
        private volatile ISourceViewer viewer;

        ModuleProblem(int offset, int length, String word)
        {
            this.offset = offset;
            this.length = length;
            this.word = word;
        }

        void attachPresentation(SpellingAnnotation annotation, Position position, ISourceViewer viewer)
        {
            this.annotation = annotation;
            this.position = position;
            this.viewer = viewer;
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
            return "Орфографическая ошибка: " + word; //$NON-NLS-1$
        }

        @Override
        public ICompletionProposal[] getProposals()
        {
            return buildProposals(viewer);
        }

        @Override
        public ICompletionProposal[] getProposals(IQuickAssistInvocationContext context)
        {
            ISourceViewer v = context != null ? context.getSourceViewer() : viewer;
            if (v != null)
                this.viewer = v;
            return buildProposals(v);
        }

        /**
         * Сразу: «Добавить в словарь» + «…»; варианты догружает фоновый Job
         * и обновляет открытый hover через {@code setInput}.
         */
        private ICompletionProposal[] buildProposals(ISourceViewer viewer)
        {
            ISourceViewer v = viewer != null ? viewer : this.viewer;
            List<String> cached = ComfortSpellingEngine.peekSuggestCache(word, SUGGEST_MAX);
            if (cached != null)
                return snapshotProposals(v, cached, false);
            SuggestSession session = SUGGEST_SESSIONS.computeIfAbsent(word.toLowerCase(java.util.Locale.ROOT),
                k -> new SuggestSession(word));
            session.ensureStarted(this, v);
            return snapshotProposals(v, session.suggestionsSnapshot(), true);
        }

        private ICompletionProposal[] snapshotProposals(ISourceViewer viewer, List<String> suggestions,
            boolean loading)
        {
            List<ICompletionProposal> result = new ArrayList<>(suggestions.size() + 2);
            result.add(new AddToDictionaryProposal(word, viewer));
            for (String s : suggestions)
            {
                ConfigurableCompletionProposal p = new ConfigurableCompletionProposal(s, offset, length,
                    s.length());
                p.setPriority(PRIORITY_SUGGESTION);
                p.setAutoInsertable(false);
                result.add(p);
            }
            if (loading)
                result.add(LoadingProposal.INSTANCE);
            return result.toArray(new ICompletionProposal[0]);
        }

        void refreshHoverIfOpen(List<String> suggestions, boolean loading)
        {
            SpellingAnnotation ann = annotation;
            Position pos = position;
            ISourceViewer v = viewer;
            if (ann == null || pos == null || v == null)
                return;
            ICompletionProposal[] proposals = snapshotProposals(v, suggestions, loading);
            refreshAnnotationHover(v, ann, pos, proposals);
        }
    }

    /**
     * Фоновый поиск вариантов; по мере нахождения — UI {@code setInput} у открытого hover.
     */
    private static final class SuggestSession
    {
        private final String word;
        private final CopyOnWriteArrayList<String> suggestions = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<ModuleProblem> listeners = new CopyOnWriteArrayList<>();
        private final Object uiLock = new Object();
        private volatile Job job;
        private volatile boolean done;
        private long lastUiFlushMs;
        private boolean uiFlushScheduled;

        SuggestSession(String word)
        {
            this.word = word;
        }

        List<String> suggestionsSnapshot()
        {
            return List.copyOf(suggestions);
        }

        synchronized void ensureStarted(ModuleProblem problem, ISourceViewer viewer)
        {
            if (problem != null)
            {
                if (!listeners.contains(problem))
                    listeners.add(problem);
                if (viewer != null)
                    problem.viewer = viewer;
            }
            if (done || job != null)
            {
                if (done && problem != null)
                    problem.refreshHoverIfOpen(suggestionsSnapshot(), false);
                return;
            }
            Job suggestJob = new Job(SUGGEST_JOB_NAME)
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    ComfortSpellingEngine.suggestStreaming(word, SUGGEST_MAX, suggestion ->
                    {
                        if (monitor.isCanceled())
                            return;
                        if (!suggestions.contains(suggestion))
                            suggestions.add(suggestion);
                        scheduleUiFlush(false);
                    }, monitor);
                    done = true;
                    SUGGEST_SESSIONS.remove(word.toLowerCase(java.util.Locale.ROOT), SuggestSession.this);
                    scheduleUiFlush(true);
                    return Status.OK_STATUS;
                }
            };
            suggestJob.setSystem(true);
            suggestJob.setPriority(Job.DECORATE);
            this.job = suggestJob;
            suggestJob.schedule();
            // Hover может ещё не успеть открыться — догоняющие flush с тем же throttle.
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
            {
                display.timerExec(SUGGEST_UI_THROTTLE_MS, () -> scheduleUiFlush(false));
                display.timerExec(SUGGEST_UI_THROTTLE_MS * 2, () -> scheduleUiFlush(false));
                display.timerExec(SUGGEST_UI_THROTTLE_MS * 3, () -> scheduleUiFlush(done));
            }
        }

        /** Не чаще {@link #SUGGEST_UI_THROTTLE_MS}; {@code force} — финальный снимок без «…». */
        private void scheduleUiFlush(boolean force)
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            // timerExec / обновление hover — только из UI-потока (Job вызывает с Worker).
            if (display.getThread() != Thread.currentThread())
            {
                display.asyncExec(() -> scheduleUiFlush(force));
                return;
            }
            long delay;
            synchronized (uiLock)
            {
                if (force)
                {
                    uiFlushScheduled = false;
                    delay = 0;
                }
                else
                {
                    long now = System.currentTimeMillis();
                    long wait = lastUiFlushMs + SUGGEST_UI_THROTTLE_MS - now;
                    if (wait <= 0)
                    {
                        lastUiFlushMs = now;
                        delay = 0;
                    }
                    else if (uiFlushScheduled)
                        return;
                    else
                    {
                        uiFlushScheduled = true;
                        delay = wait;
                    }
                }
            }
            Runnable flush = () ->
            {
                synchronized (uiLock)
                {
                    uiFlushScheduled = false;
                    lastUiFlushMs = System.currentTimeMillis();
                }
                List<String> snap = suggestionsSnapshot();
                boolean showLoading = !done;
                for (ModuleProblem p : listeners)
                {
                    if (p != null)
                        p.refreshHoverIfOpen(snap, showLoading);
                }
            };
            if (delay <= 0)
                flush.run();
            else
                display.timerExec((int) delay, flush);
        }
    }

    private static final class LoadingProposal extends ConfigurableCompletionProposal
    {
        static final LoadingProposal INSTANCE = new LoadingProposal();

        private LoadingProposal()
        {
            super("", 0, 0, 0); //$NON-NLS-1$
            setDisplayString(LOADING_MARKER);
            setPriority(PRIORITY_LOADING);
            setAutoInsertable(false);
        }

        @Override
        public void apply(IDocument document)
        {
        }

        @Override
        public void apply(IDocument document, char trigger, int offset)
        {
        }

        @Override
        public void apply(ITextViewer viewer, char trigger, int stateMask, int offset)
        {
        }

        @Override
        public String getAdditionalProposalInfo()
        {
            return "Идёт поиск вариантов исправления…"; //$NON-NLS-1$
        }
    }

    private static final class AddToDictionaryProposal extends ConfigurableCompletionProposal
    {
        private final String word;
        private final ISourceViewer viewer;

        AddToDictionaryProposal(String word, ISourceViewer viewer)
        {
            super("", 0, 0, 0); //$NON-NLS-1$
            this.word = word;
            this.viewer = viewer;
            setDisplayString("Добавить в словарь: " + word); //$NON-NLS-1$
            setPriority(PRIORITY_ADD_TO_DICTIONARY);
            setAutoInsertable(false);
        }

        private void addWord()
        {
            ComfortSpellingEngine.addUserWordFromUi(word);
        }

        @Override
        public void apply(IDocument document)
        {
            addWord();
        }

        @Override
        public void apply(IDocument document, char trigger, int offset)
        {
            addWord();
        }

        @Override
        public void apply(ITextViewer viewer, char trigger, int stateMask, int offset)
        {
            addWord();
        }

        @Override
        public String getAdditionalProposalInfo()
        {
            return "Больше не помечать это слово как ошибку в проверке орфографии Comfort."; //$NON-NLS-1$
        }
    }

    /**
     * Обновить открытый Xtext annotation hover новым списком proposals
     * ({@code AnnotationInformationControl#setInput}).
     */
    private static void refreshAnnotationHover(ISourceViewer viewer, Annotation annotation,
        Position position, ICompletionProposal[] proposals)
    {
        if (viewer == null || annotation == null || position == null || proposals == null)
            return;
        try
        {
            Object control = findVisibleHoverControl(viewer);
            if (!(control instanceof IInformationControlExtension2 ext2))
                return;
            Class<?> infoClass = Class.forName(
                "org.eclipse.xtext.ui.editor.hover.AnnotationWithQuickFixesHover$AnnotationInfo"); //$NON-NLS-1$
            Constructor<?> ctor = infoClass.getConstructor(Annotation.class, Position.class,
                ITextViewer.class, ICompletionProposal[].class);
            Object info = ctor.newInstance(annotation, position, viewer, proposals);
            ext2.setInput(info);
            if (control instanceof IInformationControl ic)
            {
                Point hint = ic.computeSizeHint();
                if (hint != null)
                    ic.setSize(hint.x, hint.y);
            }
        }
        catch (Exception e)
        {
            Global.tempLog("spellCheck", "refreshAnnotationHover: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static Object findVisibleHoverControl(ISourceViewer viewer)
    {
        Object[] managers = {
            Global.getField(viewer, "fTextHoverManager"), //$NON-NLS-1$
            Global.getField(viewer, "fInformationPresenter") //$NON-NLS-1$
        };
        for (Object manager : managers)
        {
            if (manager == null)
                continue;
            Object control = Global.getField(manager, "fInformationControl"); //$NON-NLS-1$
            if (isVisibleInfoControl(control))
                return control;
            Object replacer = Global.getField(manager, "fInformationControlReplacer"); //$NON-NLS-1$
            if (replacer != null)
            {
                Object sticky = Global.getField(replacer, "fInformationControl"); //$NON-NLS-1$
                if (isVisibleInfoControl(sticky))
                    return sticky;
            }
        }
        return null;
    }

    private static boolean isVisibleInfoControl(Object control)
    {
        if (!(control instanceof IInformationControl))
            return false;
        try
        {
            Object shell = Global.invoke(control, "getShell"); //$NON-NLS-1$
            return shell instanceof Shell s && !s.isDisposed() && s.isVisible();
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static void rescheduleAllSessions()
    {
        for (EditorSession session : new ArrayList<>(SESSIONS.values()))
        {
            if (session != null)
                session.schedule(0);
        }
    }

    /**
     * После изменения пользовательского словаря: снять/пересчитать аннотации
     * с данным словом во всех открытых модулях.
     */
    static void onUserDictionaryChanged(String word, boolean added)
    {
        if (word == null || word.isEmpty())
            return;
        SUGGEST_SESSIONS.remove(word.toLowerCase(java.util.Locale.ROOT));
        if (added)
        {
            for (EditorSession session : new ArrayList<>(SESSIONS.values()))
            {
                if (session == null || session.viewer == null)
                    continue;
                SpellingProblem.removeAll(session.viewer, word);
            }
        }
        rescheduleAllSessions();
    }
}
