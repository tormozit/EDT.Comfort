package tormozit;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlExtension2;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextHoverExtension;
import org.eclipse.jface.text.ITextHoverExtension2;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.quickassist.IQuickAssistInvocationContext;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
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
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.Keyword;
import org.eclipse.xtext.RuleCall;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.refactoring.ui.IRenameContextFactory;
import org.eclipse.xtext.ui.refactoring.ui.IRenameElementContext;
import org.eclipse.xtext.ui.refactoring.ui.IRenameSupport;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com._1c.g5.v8.dt.bsl.model.BslContextDefMethod;
import com._1c.g5.v8.dt.bsl.model.BslContextDefProperty;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.ImplicitVariable;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Variable;
import com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;
import com._1c.g5.v8.dt.mcore.ContextsItem;
import com._1c.g5.v8.dt.mcore.Environmental;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.Method;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Орфография Hunspell в редакторе модуля BSL: видимый диапазон, non-keyword листья
 * node model, camelCase-сегменты, {@link SpellingAnnotation}. Платформенные методы/
 * свойства/события/типы 1С не проверяем. Исправление в идентификаторе — через
 * Refactor/Rename всего имени (все обращения). Только при
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
    private static final String ANNOTATION_HOVER_WRAP_KEY = "tormozit.comfort.annotationHoverWrapped"; //$NON-NLS-1$
    /** Состояние навигации маркеров в sticky annotation hover. */
    private static volatile AnnotationHoverNavState annotationHoverNav;
    /**
     * Кэш proposals ошибки валидации (без орфографии) по viewer.
     * Иначе после визита на страницу spelling {@code fillToolBar} затирает их пустым массивом.
     */
    private static final WeakHashMap<ISourceViewer, ICompletionProposal[]> NON_SPELL_PROPOSALS =
        new WeakHashMap<>();
    /** Активные сессии подсказок по слову (lowercase). */
    private static final ConcurrentHashMap<String, SuggestSession> SUGGEST_SESSIONS =
        new ConcurrentHashMap<>();
    /** EClass провайдеров IEObjectProvider (см. platform_v8.*.jar plugin.xml). */
    private static final EClass[] PLATFORM_CATALOG_CLASSES = {
        McorePackage.Literals.METHOD,
        McorePackage.Literals.PROPERTY,
        McorePackage.Literals.TYPE_ITEM
    };
    /** Кэш имён платформенного API (en+ru) из IEObjectProvider. */
    private static volatile Set<String> PLATFORM_API_NAMES;

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
        if (editor == null)
            return;
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null)
            return;
        wrapAnnotationHover(viewer);
        if (SESSIONS.containsKey(editor))
        {
            EditorSession existing = SESSIONS.get(editor);
            if (existing != null && SpellCheckHook.isComfortPlatformSpellingActive())
                existing.schedule(0);
            return;
        }
        EditorSession session = new EditorSession(editor, viewer);
        SESSIONS.put(editor, session);
        session.install();
        if (SpellCheckHook.isComfortPlatformSpellingActive())
        {
            session.schedule(0);
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
            {
                display.timerExec(500, () -> wrapAnnotationHover(viewer));
                display.timerExec(1500, () ->
                {
                    wrapAnnotationHover(viewer);
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
                        return Status.CANCEL_STATUS;
                    }
                    if (monitor.isCanceled() || gen != scheduleGeneration)
                        return Status.CANCEL_STATUS;
                    final List<ModuleProblem> toApply = problems != null ? problems : List.of();
                    Display.getDefault().asyncExec(() ->
                    {
                        if (gen != scheduleGeneration)
                            return;
                        // Замена аннотаций закрывает/чистит annotation hover — отложим
                        if (findVisibleHoverControl(viewer) != null)
                        {
                            schedule(Math.max(DEBOUNCE_MS, SUGGEST_UI_THROTTLE_MS * 2));
                            return;
                        }
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
            // replaceAnnotations сносит открытый hover (setInput/dispose) — запомним позицию
            final int hoverOffset = peekVisibleHoverOffset(viewer);
            final String hoverWord = peekVisibleHoverWord(viewer);
            removeComfortAnnotations();
            if (problems == null || problems.isEmpty())
            {
                return;
            }
            ModuleProblem hoverMatch = null;
            if (model instanceof IAnnotationModelExtension ext)
            {
                Map<Annotation, Position> batch = new java.util.LinkedHashMap<>();
                for (ModuleProblem p : problems)
                {
                    SpellingAnnotation ann = new SpellingAnnotation(p);
                    Position pos = new Position(p.getOffset(), p.getLength());
                    p.attachPresentation(ann, pos, viewer, editor);
                    batch.put(ann, pos);
                    if (hoverMatch == null && matchesHover(p, hoverOffset, hoverWord))
                        hoverMatch = p;
                }
                ext.replaceAnnotations(new Annotation[0], batch);
            }
            else
            {
                for (ModuleProblem p : problems)
                {
                    SpellingAnnotation ann = new SpellingAnnotation(p);
                    Position pos = new Position(p.getOffset(), p.getLength());
                    p.attachPresentation(ann, pos, viewer, editor);
                    model.addAnnotation(ann, pos);
                    if (hoverMatch == null && matchesHover(p, hoverOffset, hoverWord))
                        hoverMatch = p;
                }
            }
            if (hoverMatch != null)
                restoreHoverProposals(hoverMatch);
        }
    }

    private static boolean matchesHover(ModuleProblem p, int hoverOffset, String hoverWord)
    {
        if (p == null)
            return false;
        if (hoverWord != null && hoverWord.equals(p.word))
            return true;
        if (hoverOffset < 0)
            return false;
        if (p.getOffset() <= hoverOffset && hoverOffset < p.getOffset() + p.getLength())
            return true;
        return p.leafOffset <= hoverOffset && hoverOffset < p.leafOffset + p.leafLength;
    }

    private static void restoreHoverProposals(ModuleProblem problem)
    {
        if (problem == null)
            return;
        List<String> cached = ComfortSpellingEngine.peekSuggestCache(problem.word, SUGGEST_MAX);
        if (cached == null || cached.isEmpty())
            cached = problem.lastGoodSuggestions;
        if (cached == null || cached.isEmpty())
        {
            return;
        }
        final List<String> snap = List.copyOf(cached);
        problem.rememberSuggestions(snap);
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            problem.refreshHoverIfOpen(snap, false);
        });
    }

    private static int peekVisibleHoverOffset(ISourceViewer viewer)
    {
        try
        {
            Object control = findVisibleHoverControl(viewer);
            if (control == null)
                return -1;
            Object input = Global.getField(control, "fInput"); //$NON-NLS-1$
            if (input == null)
                return -1;
            Object pos = Global.getField(input, "position"); //$NON-NLS-1$
            if (pos instanceof Position p && !p.isDeleted)
                return p.getOffset();
        }
        catch (Exception ignored)
        {
        }
        return -1;
    }

    private static String peekVisibleHoverWord(ISourceViewer viewer)
    {
        try
        {
            Object control = findVisibleHoverControl(viewer);
            if (control == null)
                return null;
            Object input = Global.getField(control, "fInput"); //$NON-NLS-1$
            if (input == null)
                return null;
            Object ann = Global.getField(input, "annotation"); //$NON-NLS-1$
            if (ann instanceof SpellingAnnotation sa
                && sa.getSpellingProblem() instanceof ModuleProblem mp)
                return mp.word;
        }
        catch (Exception ignored)
        {
        }
        return null;
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
            {
                return result;
            }
            ICompositeNode root = resource.getParseResult().getRootNode();
            if (root == null)
            {
                return result;
            }
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
                // ТипЗнч/TypeOf и др. — terminal BUILTIN в Bsl.xtext, не IDENT:
                // иначе classifyLeaf=OTHER и platform-skip не срабатывает.
                if (isBuiltinTerminal(leaf))
                    continue;
                LeafKind kind = classifyLeaf(leaf);
                if (!checkIdentifiers)
                {
                    if (kind == LeafKind.IDENTIFIER || kind == LeafKind.STRING)
                        continue;
                }
                String text = leaf.getText();
                if (kind == LeafKind.IDENTIFIER)
                {
                    String accessName = featureAccessName(leaf);
                    // Платформенные методы/свойства/типы — имена фиксированы платформой
                    if (isResolvedPlatformApiName(leaf)
                        || isPlatformApiName(text)
                        || isPlatformApiName(accessName))
                        continue;
                }
                if (text == null || text.isEmpty() || !containsLetter(text))
                    continue;
                List<int[]> relative = ComfortSpellingEngine.findMisspelledRanges(text);
                if (!relative.isEmpty() && kind == LeafKind.IDENTIFIER)
                {
                    // Повторная проверка: сегмент мог пройти мимо первого platform-skip
                    if (isPlatformApiName(text) || isPlatformApiName(featureAccessName(leaf)))
                        continue;
                }
                for (int[] r : relative)
                {
                    int abs = leafStart + r[0];
                    int len = r[1];
                    if (abs + len <= visOffset || abs >= visEnd)
                        continue;
                    String word = text.substring(r[0], r[0] + r[1]);
                    if (!checkIdentifiers && kind == LeafKind.COMMENT && looksLikeIdentifier(word))
                        continue;
                    int leafLen = leafEnd - leafStart;
                    result.add(new ModuleProblem(abs, len, word, kind, leafStart, leafLen, text));
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

    /** Terminal {@code BUILTIN} в Bsl.xtext (ТипЗнч, СтрДлина, …) — не IDENT. */
    private static boolean isBuiltinTerminal(ILeafNode leaf)
    {
        String rule = terminalRuleName(leaf);
        return rule != null && "BUILTIN".equalsIgnoreCase(rule); //$NON-NLS-1$
    }

    /**
     * Идентификатор резолвится только в платформенный API (метод/свойство/событие/тип).
     * Определения модуля ({@code bsl.model.Method}), {@code BslContextDef*}, переменные —
     * не считаем платформой (их имена проверяем).
     * <p>
     * FeatureEntry часто хранит lazy-proxy ({@code EcoreFactory.createEObject}) до линковки —
     * поэтому при пустых/нетипизированных entries резолвим через
     * {@link DynamicFeatureAccessComputer}, а для статических имён — каталог
     * {@link IEObjectProvider}.
     */
    private static boolean isResolvedPlatformApiName(ILeafNode leaf)
    {
        FeatureAccess access = findFeatureAccessForNameLeaf(leaf);
        if (access == null)
            return false;
        List<FeatureEntry> entries = collectFeatureEntries(access);
        boolean sawPlatform = false;
        boolean sawConfig = false;
        if (entries != null)
        {
            for (FeatureEntry entry : entries)
            {
                if (entry == null)
                    continue;
                EObject feature = resolveFeature(entry.getFeature(), access);
                if (feature == null)
                    continue;
                if (isConfigurationOwnedFeature(feature))
                    sawConfig = true;
                else if (isPlatformApiFeature(feature))
                    sawPlatform = true;
            }
        }
        if (sawConfig)
            return false;
        if (sawPlatform)
            return true;
        return access instanceof StaticFeatureAccess
            && isPlatformApiName(access.getName());
    }

    private static List<FeatureEntry> collectFeatureEntries(FeatureAccess access)
    {
        EList<FeatureEntry> existing = featureEntriesOf(access);
        if (existing != null && !existing.isEmpty() && hasTypedApiFeature(existing, access))
            return existing;
        List<FeatureEntry> resolved = resolveFeatureEntriesViaComputer(access);
        if (resolved != null && !resolved.isEmpty())
            return resolved;
        return existing != null ? existing : Collections.emptyList();
    }

    private static boolean hasTypedApiFeature(EList<FeatureEntry> entries, FeatureAccess access)
    {
        for (FeatureEntry entry : entries)
        {
            if (entry == null)
                continue;
            EObject feature = resolveFeature(entry.getFeature(), access);
            if (feature == null || feature.eIsProxy())
                continue;
            if (isConfigurationOwnedFeature(feature) || isPlatformApiFeature(feature))
                return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<FeatureEntry> resolveFeatureEntriesViaComputer(FeatureAccess access)
    {
        try
        {
            Resource resource = access.eResource();
            if (resource == null || resource.getURI() == null)
                return null;
            IResourceServiceProvider rsp =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(
                    resource.getURI());
            if (rsp == null)
                return null;
            DynamicFeatureAccessComputer computer = rsp.get(DynamicFeatureAccessComputer.class);
            if (computer == null)
                return null;
            Environmental envOwner = EcoreUtil2.getContainerOfType(access, Environmental.class);
            Environments environments = envOwner != null
                ? envOwner.environments()
                : Environments.ALL;
            if (environments == null)
                environments = Environments.ALL;
            Object plain = computer.resolveObject(access, environments);
            if (plain instanceof List<?> list && !list.isEmpty())
                return (List<FeatureEntry>) list;
        }
        catch (Exception ex)
        {
        }
        return null;
    }

    private static EObject resolveFeature(EObject feature, EObject context)
    {
        if (feature == null)
            return null;
        if (!feature.eIsProxy())
            return feature;
        try
        {
            EObject resolved = EcoreUtil.resolve(feature, context);
            return resolved != null ? resolved : feature;
        }
        catch (Exception ignored)
        {
            return feature;
        }
    }

    /** Имя есть в индексе платформенного API (Method/Property/TypeItem, en+ru). */
    private static boolean isPlatformApiName(String name)
    {
        if (name == null || name.isEmpty())
            return false;
        if (platformApiNames().contains(name))
            return true;
        // прямой lookup в descrByName (на случай расхождения с descrList)
        return platformProviderHasName(name);
    }

    private static boolean platformProviderHasName(String name)
    {
        if (name == null || name.isEmpty())
            return false;
        for (EClass eClass : PLATFORM_CATALOG_CLASSES)
        {
            try
            {
                IEObjectProvider provider =
                    IEObjectProvider.Registry.INSTANCE.get(eClass, Version.LATEST);
                if (provider == null)
                    continue;
                if (provider.getClass().getSimpleName().contains("Empty")) //$NON-NLS-1$
                    continue;
                if (provider.getEObjectDescription(name) != null)
                    return true;
            }
            catch (Exception ignored)
            {
            }
        }
        return false;
    }

    private static String featureAccessName(ILeafNode leaf)
    {
        FeatureAccess access = findFeatureAccessForNameLeaf(leaf);
        return access != null ? access.getName() : null;
    }

    private static String toHex(String text)
    {
        if (text == null || text.isEmpty())
            return ""; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder(text.length() * 5);
        for (int i = 0; i < text.length(); i++)
        {
            if (i > 0)
                sb.append(' ');
            sb.append(String.format("%04X", (int) text.charAt(i))); //$NON-NLS-1$
        }
        return sb.toString();
    }

    private static Set<String> platformApiNames()
    {
        Set<String> cached = PLATFORM_API_NAMES;
        if (cached != null)
            return cached;
        synchronized (BslModuleSpellCheckHook.class)
        {
            if (PLATFORM_API_NAMES != null)
                return PLATFORM_API_NAMES;
            Set<String> names = ConcurrentHashMap.newKeySet();
            List<Version> versions = new ArrayList<>();
            versions.add(Version.LATEST);
            try
            {
                List<Version> all = Version.getPlatformSupportVersions();
                if (all != null)
                {
                    for (Version v : all)
                    {
                        if (v != null)
                            versions.add(v);
                    }
                }
            }
            catch (Exception ex)
            {
            }
            int providers = 0;
            for (Version version : versions)
            {
                for (EClass eClass : PLATFORM_CATALOG_CLASSES)
                {
                    try
                    {
                        IEObjectProvider provider =
                            IEObjectProvider.Registry.INSTANCE.get(eClass, version);
                        if (provider == null)
                            continue;
                        String simple = provider.getClass().getSimpleName();
                        if (simple != null && simple.contains("Empty")) //$NON-NLS-1$
                            continue;
                        providers++;
                        Iterable<IEObjectDescription> descs = provider.getEObjectDescriptions(null);
                        if (descs == null)
                            continue;
                        for (IEObjectDescription desc : descs)
                        {
                            if (desc == null)
                                continue;
                            QualifiedName qn = desc.getName();
                            if (qn == null || qn.isEmpty())
                                continue;
                            for (int s = 0; s < qn.getSegmentCount(); s++)
                            {
                                String seg = qn.getSegment(s);
                                if (seg != null && !seg.isEmpty())
                                    names.add(seg);
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                    }
                }
                // одного рабочего runtime достаточно
                if (names.size() > 500)
                    break;
            }
            PLATFORM_API_NAMES = names;
            return names;
        }
    }

    private static FeatureAccess findFeatureAccessForNameLeaf(ILeafNode leaf)
    {
        if (leaf == null)
            return null;
        EObject sem = leaf.getSemanticElement();
        for (EObject cur = sem; cur != null; cur = cur.eContainer())
        {
            if (cur instanceof FeatureAccess access && isNameLeafOfFeatureAccess(access, leaf))
                return access;
            // Не уходим слишком высоко по модулю
            if (cur.eContainer() == null)
                break;
        }
        return null;
    }

    private static boolean isNameLeafOfFeatureAccess(FeatureAccess access, ILeafNode leaf)
    {
        List<INode> nodes = NodeModelUtils.findNodesForFeature(access,
            BslPackage.Literals.FEATURE_ACCESS__NAME);
        if (nodes == null || nodes.isEmpty())
            return false;
        int leafOff = leaf.getOffset();
        int leafEnd = leaf.getEndOffset();
        for (INode node : nodes)
        {
            if (node == null || node.getLength() <= 0)
                continue;
            if (node.getOffset() == leafOff && node.getEndOffset() == leafEnd)
                return true;
            if (leafOff >= node.getOffset() && leafEnd <= node.getEndOffset())
                return true;
        }
        return false;
    }

    private static EList<FeatureEntry> featureEntriesOf(FeatureAccess access)
    {
        if (access instanceof StaticFeatureAccess staticAccess)
            return staticAccess.getFeatureEntries();
        if (access instanceof DynamicFeatureAccess dynamicAccess)
            return dynamicAccess.getFeatureEntries();
        return null;
    }

    private static boolean isConfigurationOwnedFeature(EObject feature)
    {
        if (feature instanceof com._1c.g5.v8.dt.bsl.model.Method)
            return true;
        if (feature instanceof BslContextDefMethod || feature instanceof BslContextDefProperty)
            return true;
        if (feature instanceof Variable || feature instanceof FormalParam
            || feature instanceof ImplicitVariable)
            return true;
        return false;
    }

    private static boolean isPlatformApiFeature(EObject feature)
    {
        if (feature instanceof BslContextDefMethod || feature instanceof BslContextDefProperty)
            return false;
        if (feature instanceof com._1c.g5.v8.dt.bsl.model.Method)
            return false;
        if (feature instanceof Method || feature instanceof Property || feature instanceof Event
            || feature instanceof Type)
        {
            if (feature instanceof ContextsItem item && item.getCode() > 0)
                return true;
            return isPlatformPluginUri(eObjectUri(feature));
        }
        return false;
    }

    private static URI eObjectUri(EObject object)
    {
        if (object == null)
            return null;
        try
        {
            URI uri = EcoreUtil.getURI(object);
            if (uri != null)
                return uri;
        }
        catch (Exception ignored)
        {
            // fallback ниже
        }
        Resource resource = object.eResource();
        return resource != null ? resource.getURI() : null;
    }

    private static boolean isPlatformPluginUri(URI uri)
    {
        if (uri == null)
            return false;
        String text = uri.toString();
        return text != null && text.contains("com._1c.g5.v8.dt.platform"); //$NON-NLS-1$
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
        private final LeafKind kind;
        /** Полный leaf идентификатора (для Rename); для комментария/строки = сегмент. */
        private final int leafOffset;
        private final int leafLength;
        private final String leafText;
        private volatile SpellingAnnotation annotation;
        private volatile Position position;
        private volatile ISourceViewer viewer;
        private volatile BslXtextEditor editor;
        /** Последний непустой список вариантов — не даём hover затереть его пустым снимком. */
        private volatile List<String> lastGoodSuggestions = List.of();

        ModuleProblem(int offset, int length, String word, LeafKind kind, int leafOffset, int leafLength,
            String leafText)
        {
            this.offset = offset;
            this.length = length;
            this.word = word;
            this.kind = kind != null ? kind : LeafKind.OTHER;
            this.leafOffset = leafOffset;
            this.leafLength = leafLength;
            this.leafText = leafText != null ? leafText : word;
        }

        void attachPresentation(SpellingAnnotation annotation, Position position, ISourceViewer viewer,
            BslXtextEditor editor)
        {
            this.annotation = annotation;
            this.position = position;
            this.viewer = viewer;
            this.editor = editor;
        }

        boolean isIdentifierLeaf()
        {
            return kind == LeafKind.IDENTIFIER && leafLength > 0 && leafText != null;
        }

        /** Полное имя идентификатора после замены ошибочного сегмента на {@code suggestion}. */
        String correctedLeafName(String suggestion)
        {
            if (suggestion == null || leafText == null)
                return null;
            int rel = offset - leafOffset;
            if (rel < 0 || rel + length > leafText.length())
                return null;
            return leafText.substring(0, rel) + suggestion + leafText.substring(rel + length);
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
         * {@code loading} только пока сессия реально не завершена — иначе повторный
         * {@code getProposals} после готовности снова вставляет «…» и затирает список.
         */
        private ICompletionProposal[] buildProposals(ISourceViewer viewer)
        {
            ISourceViewer v = viewer != null ? viewer : this.viewer;
            List<String> cached = ComfortSpellingEngine.peekSuggestCache(word, SUGGEST_MAX);
            if (cached != null && !cached.isEmpty())
            {
                rememberSuggestions(cached);
                return snapshotProposals(v, cached, false);
            }
            String key = word.toLowerCase(java.util.Locale.ROOT);
            SuggestSession session = SUGGEST_SESSIONS.computeIfAbsent(key, k -> new SuggestSession(word));
            session.ensureStarted(this, v);
            cached = ComfortSpellingEngine.peekSuggestCache(word, SUGGEST_MAX);
            if (cached != null && !cached.isEmpty())
            {
                rememberSuggestions(cached);
                return snapshotProposals(v, cached, false);
            }
            List<String> snap = session.suggestionsSnapshot();
            if (snap.isEmpty() && !lastGoodSuggestions.isEmpty())
            {
                return snapshotProposals(v, lastGoodSuggestions, false);
            }
            if (!snap.isEmpty())
                rememberSuggestions(snap);
            boolean loading = !session.isDone() && snap.isEmpty();
            return snapshotProposals(v, snap, loading);
        }

        private void rememberSuggestions(List<String> suggestions)
        {
            if (suggestions != null && !suggestions.isEmpty())
                lastGoodSuggestions = List.copyOf(suggestions);
        }

        private ICompletionProposal[] snapshotProposals(ISourceViewer viewer, List<String> suggestions,
            boolean loading)
        {
            List<String> list = suggestions != null ? suggestions : List.of();
            List<ICompletionProposal> result = new ArrayList<>(list.size() + 2);
            result.add(new AddToDictionaryProposal(word));
            for (String s : list)
                result.add(new SpellingReplaceProposal(s, this));
            if (loading && list.isEmpty())
                result.add(LoadingProposal.INSTANCE);
            return result.toArray(new ICompletionProposal[0]);
        }

        void refreshHoverIfOpen(List<String> suggestions, boolean loading)
        {
            SpellingAnnotation ann = annotation;
            Position pos = position;
            ISourceViewer v = viewer;
            if (ann == null || pos == null || v == null)
            {
                return;
            }
            List<String> list = suggestions != null ? suggestions : List.of();
            // setInput всегда пересоздаёт UI: пустой снимок после заполненного — запрещаем
            if (list.isEmpty())
            {
                List<String> cached = ComfortSpellingEngine.peekSuggestCache(word, SUGGEST_MAX);
                if (cached != null && !cached.isEmpty())
                    list = cached;
                else if (!lastGoodSuggestions.isEmpty())
                    list = lastGoodSuggestions;
                if (!list.isEmpty())
                {
                    loading = false;
                }
            }
            if (!list.isEmpty())
                rememberSuggestions(list);
            boolean showLoading = loading && list.isEmpty();
            ICompletionProposal[] proposals = snapshotProposals(v, list, showLoading);
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
        /** Инвалидирует отложенные timerExec flush при завершении Job. */
        private int flushGeneration;
        private long lastUiFlushMs;
        private boolean uiFlushScheduled;
        /** Макс. размер снимка, уже отправленного в hover (монотонность). */
        private int lastFlushedSize;

        SuggestSession(String word)
        {
            this.word = word;
        }

        boolean isDone()
        {
            return done;
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
                // Только async: sync refresh→setInput из getProposals/fillToolBar
                // внутри чужого setInput дублирует контент hover (два блока орфографии).
                if (done && problem != null)
                {
                    ModuleProblem p = problem;
                    List<String> snap = suggestionsSnapshot();
                    Display display = Display.getDefault();
                    if (display != null && !display.isDisposed())
                        display.asyncExec(() -> p.refreshHoverIfOpen(snap, false));
                }
                return;
            }
            final int catchUpGen = flushGeneration;
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
                    // Кэш уже записан внутри suggestStreaming (если не cancel).
                    done = true;
                    // Инвалидируем отложенные catch-up / throttled flush.
                    synchronized (uiLock)
                    {
                        flushGeneration++;
                        uiFlushScheduled = false;
                    }
                    scheduleUiFlush(true);
                    // Сессию НЕ удаляем: повторный getProposals до прогрева кэша иначе
                    // поднимал пустую сессию и затирал список «…».
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
                display.timerExec(SUGGEST_UI_THROTTLE_MS,
                    () -> catchUpFlush(catchUpGen));
                display.timerExec(SUGGEST_UI_THROTTLE_MS * 2,
                    () -> catchUpFlush(catchUpGen));
                display.timerExec(SUGGEST_UI_THROTTLE_MS * 3,
                    () -> catchUpFlush(catchUpGen));
            }
        }

        private void catchUpFlush(int gen)
        {
            if (gen != flushGeneration || done)
                return;
            scheduleUiFlush(false);
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
            final int gen;
            long delay;
            synchronized (uiLock)
            {
                gen = flushGeneration;
                if (force)
                {
                    uiFlushScheduled = false;
                    delay = 0;
                }
                else
                {
                    if (done)
                        return;
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
                    if (gen != flushGeneration && !force)
                        return;
                    uiFlushScheduled = false;
                    lastUiFlushMs = System.currentTimeMillis();
                }
                List<String> snap = suggestionsSnapshot();
                // Устаревший throttled flush после done не должен возвращать «…»
                if (!force && done)
                    return;
                // Не откатываем hover к меньшему списку (гонка throttle vs force)
                if (!force && snap.size() < lastFlushedSize)
                {
                    return;
                }
                if (snap.size() >= lastFlushedSize)
                    lastFlushedSize = snap.size();
                boolean showLoading = !done && snap.isEmpty();
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

    /**
     * Исправление опечатки: для идентификатора — Refactor/Rename всего имени (все ссылки);
     * иначе — замена сегмента в документе.
     * Штатный {@link ConfigurableCompletionProposal#apply(ITextViewer, char, int, int)}
     * пересчитывает length от каретки (length=0 → вставка без замещения) — не используем.
     */
    private static final class SpellingReplaceProposal extends ConfigurableCompletionProposal
    {
        private final String suggestion;
        private final ModuleProblem problem;

        SpellingReplaceProposal(String suggestion, ModuleProblem problem)
        {
            super(suggestion, problem.offset, problem.length, suggestion != null ? suggestion.length() : 0);
            this.suggestion = suggestion;
            this.problem = problem;
            setPriority(PRIORITY_SUGGESTION);
            setAutoInsertable(false);
            setReplaceContextLength(problem.length);
            if (problem.isIdentifierLeaf())
            {
                String full = problem.correctedLeafName(suggestion);
                if (full != null && !full.equals(problem.leafText))
                    setAdditionalProposalInfo(
                        "Переименовать «" + problem.leafText + "» → «" + full + "» (со всеми обращениями)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }

        @Override
        public void apply(IDocument document)
        {
            if (problem != null && problem.isIdentifierLeaf() && tryRenameIdentifier())
                return;
            replaceInDocument(document);
        }

        @Override
        public void apply(ITextViewer viewer, char trigger, int stateMask, int offset)
        {
            if (problem != null && problem.isIdentifierLeaf() && tryRenameIdentifier())
                return;
            IDocument document = viewer != null ? viewer.getDocument() : null;
            replaceInDocument(document);
        }

        @Override
        public void apply(IDocument document, char trigger, int offset)
        {
            apply(document);
        }

        private void replaceInDocument(IDocument document)
        {
            if (document == null || suggestion == null)
                return;
            try
            {
                if (problem != null && problem.isIdentifierLeaf())
                {
                    String full = problem.correctedLeafName(suggestion);
                    if (full != null)
                    {
                        document.replace(problem.leafOffset, problem.leafLength, full);
                        return;
                    }
                }
                document.replace(getReplacementOffset(), getReplacementLength(), suggestion);
            }
            catch (Exception e)
            {
            }
        }

        /**
         * {@code true}, если имя обновлено во всех вхождениях (штатный Rename или
         * запасная замена всех identifier-листьев с тем же текстом).
         */
        private boolean tryRenameIdentifier()
        {
            String oldName = problem.leafText;
            String newName = problem.correctedLeafName(suggestion);
            if (newName == null || newName.isEmpty() || newName.equals(oldName))
                return false;
            BslXtextEditor editor = problem.editor;
            if (editor == null)
            {
                return false;
            }
            IXtextDocument xdoc = editor.getDocument();
            if (xdoc == null)
                return false;
            ITextSelection selection = new TextSelection(problem.leafOffset, problem.leafLength);
            try
            {
                editor.getSelectionProvider().setSelection(selection);
            }
            catch (Exception ignored)
            {
            }
            try
            {
                IRenameElementContext context = xdoc.priorityReadOnly(resource ->
                    createRenameContext(resource, editor, selection, problem.leafOffset));
                if (context == null)
                {
                    return replaceAllIdentifierOccurrences(xdoc, oldName, newName);
                }
                IResourceServiceProvider rsp = resolveRsp(editor);
                if (rsp == null)
                {
                    return replaceAllIdentifierOccurrences(xdoc, oldName, newName);
                }
                IRenameSupport.Factory factory = rsp.get(IRenameSupport.Factory.class);
                if (factory == null)
                {
                    return replaceAllIdentifierOccurrences(xdoc, oldName, newName);
                }
                IRenameSupport support = factory.create(context, newName);
                if (support == null)
                {
                    return replaceAllIdentifierOccurrences(xdoc, oldName, newName);
                }
                support.startDirectRefactoring();
                return true;
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return replaceAllIdentifierOccurrences(xdoc, oldName, newName);
            }
            catch (Exception e)
            {
                return replaceAllIdentifierOccurrences(xdoc, oldName, newName);
            }
        }
    }

    private static IResourceServiceProvider resolveRsp(XtextEditor editor)
    {
        if (editor == null)
            return null;
        try
        {
            IXtextDocument doc = editor.getDocument();
            if (doc == null)
                return null;
            return doc.priorityReadOnly(resource ->
            {
                if (resource == null || resource.getURI() == null)
                    return null;
                return IResourceServiceProvider.Registry.INSTANCE
                    .getResourceServiceProvider(resource.getURI());
            });
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Цель Rename — объявление (переменная/метод), не {@link FeatureAccess} в месте
     * использования: иначе обновляется одно вхождение.
     */
    private static IRenameElementContext createRenameContext(XtextResource resource, XtextEditor editor,
        ITextSelection selection, int offset)
    {
        if (resource == null || editor == null || selection == null)
            return null;
        EObject element = resolveRenameTarget(resource, offset);
        if (element == null)
        {
            return null;
        }
        // FeatureAccess без объявления — Rename обновит одно место; пусть сработает replaceAll
        if (element instanceof FeatureAccess)
        {
            return null;
        }
        IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(resource.getURI());
        if (rsp == null)
            return null;
        IRenameContextFactory contextFactory = rsp.get(IRenameContextFactory.class);
        if (contextFactory == null)
            return null;
        return contextFactory.createRenameElementContext(element, editor, selection, resource);
    }

    private static EObject resolveRenameTarget(XtextResource resource, int offset)
    {
        EObjectAtOffsetHelper helper = new EObjectAtOffsetHelper();
        EObject atOffset = helper.resolveElementAt(resource, offset);
        EObject crossRef = helper.resolveCrossReferencedElementAt(resource, offset);
        if (atOffset instanceof FeatureAccess access)
        {
            EObject decl = declarationFromFeatureAccess(access);
            if (decl != null)
                return decl;
        }
        if (isRenameableDeclaration(crossRef))
            return crossRef;
        if (isRenameableDeclaration(atOffset))
            return atOffset;
        if (atOffset != null)
            return atOffset;
        return crossRef;
    }

    private static EObject declarationFromFeatureAccess(FeatureAccess access)
    {
        List<FeatureEntry> entries = collectFeatureEntries(access);
        if (entries == null)
            return null;
        for (FeatureEntry entry : entries)
        {
            if (entry == null)
                continue;
            EObject feature = resolveFeature(entry.getFeature(), access);
            if (isRenameableDeclaration(feature))
                return feature;
        }
        return null;
    }

    private static boolean isRenameableDeclaration(EObject object)
    {
        if (object == null || object.eIsProxy())
            return false;
        if (object instanceof Variable || object instanceof FormalParam
            || object instanceof ImplicitVariable)
            return true;
        if (object instanceof com._1c.g5.v8.dt.bsl.model.Method)
            return true;
        if (object instanceof BslContextDefMethod || object instanceof BslContextDefProperty)
            return true;
        return false;
    }

    /**
     * Запасной путь: все non-keyword identifier-листья с текстом {@code oldName}
     * в модуле → {@code newName} (от конца к началу).
     */
    private static boolean replaceAllIdentifierOccurrences(IXtextDocument xdoc, String oldName,
        String newName)
    {
        if (xdoc == null || oldName == null || newName == null || oldName.equals(newName))
            return false;
        try
        {
            List<int[]> ranges = xdoc.readOnly(resource ->
            {
                List<int[]> found = new ArrayList<>();
                if (resource == null || resource.getParseResult() == null)
                    return found;
                ICompositeNode root = resource.getParseResult().getRootNode();
                if (root == null)
                    return found;
                for (ILeafNode leaf : root.getLeafNodes())
                {
                    if (isKeywordLeaf(leaf))
                        continue;
                    if (classifyLeaf(leaf) != LeafKind.IDENTIFIER)
                        continue;
                    String text = leaf.getText();
                    if (oldName.equals(text))
                        found.add(new int[] { leaf.getOffset(), leaf.getLength() });
                }
                return found;
            });
            if (ranges == null || ranges.isEmpty())
            {
                return false;
            }
            for (int i = ranges.size() - 1; i >= 0; i--)
            {
                int[] r = ranges.get(i);
                xdoc.replace(r[0], r[1], newName);
            }
            return true;
        }
        catch (Exception e)
        {
            return false;
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
        public Point getSelection(IDocument document)
        {
            // Иначе AnnotationInformationControl.revealRange(0,0) — прокрутка в начало модуля.
            return null;
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

        AddToDictionaryProposal(String word)
        {
            super("", 0, 0, 0); //$NON-NLS-1$
            this.word = word;
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
        public Point getSelection(IDocument document)
        {
            // replacementOffset=0 → иначе hover делает revealRange(0) и уезжает в начало модуля.
            return null;
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
     * <p>
     * Только если сейчас показан наш {@link SpellingAnnotation} — чужой hover ошибки
     * валидации не трогаем.
     */
    private static void refreshAnnotationHover(ISourceViewer viewer, Annotation annotation,
        Position position, ICompletionProposal[] proposals)
    {
        if (viewer == null || annotation == null || position == null || proposals == null)
            return;
        try
        {
            HoverControlRef hover = findVisibleHoverControlRef(viewer);
            if (hover == null || !(hover.control instanceof IInformationControlExtension2 ext2))
            {
                return;
            }
            Object prevInfo = hover.manager != null
                ? Global.getField(hover.manager, "fInformation") : null; //$NON-NLS-1$
            Object controlInfo = Global.getField(hover.control, "fInput"); //$NON-NLS-1$
            if (!isOurSpellingAnnotationInfo(controlInfo, annotation)
                && !isOurSpellingAnnotationInfo(prevInfo, annotation))
            {
                return;
            }
            // Не подменять BslAnnotationInfo новым Xtext AnnotationInfo — иначе fillToolBar
            // не вызывает IBslHoverContributor и setInput стирает кнопки навигации.
            Object live = isOurSpellingAnnotationInfo(controlInfo, annotation) ? controlInfo
                : (isOurSpellingAnnotationInfo(prevInfo, annotation) ? prevInfo : null);
            Object info = live;
            boolean mutated = false;
            if (live != null)
            {
                mutated = Global.setFieldForce(live, "annotation", annotation); //$NON-NLS-1$
                mutated = Global.setFieldForce(live, "position", position) || mutated; //$NON-NLS-1$
                mutated = Global.setFieldForce(live, "proposals", proposals) || mutated; //$NON-NLS-1$
            }
            else
            {
                info = newAnnotationInfo(annotation, position, viewer, proposals);
                if (info == null)
                    return;
            }
            boolean patched = patchManagerInformation(hover.manager, info);
            String sig = System.identityHashCode(hover.control) + ":" + proposalSignature(proposals); //$NON-NLS-1$
            if (sig.equals(lastHoverProposalSig))
            {
                return;
            }
            if (hoverSetInputDepth > 0)
            {
                ISourceViewer v = viewer;
                Annotation ann = annotation;
                Position pos = position;
                ICompletionProposal[] props = proposals;
                Display display = Display.getCurrent();
                if (display != null)
                    display.asyncExec(() -> refreshAnnotationHover(v, ann, pos, props));
                return;
            }
            lastHoverProposalSig = sig;
            hoverSetInputDepth++;
            try
            {
                ext2.setInput(info);
            }
            finally
            {
                hoverSetInputDepth--;
            }
            AnnotationHoverNavState nav = annotationHoverNav;
            if (nav != null && nav.viewer == viewer && nav.annotations.size() >= 2)
                forceAnnotationNavToolbar(hover.control, nav.annotations, viewer, nav.index);
            applyHoverSizeGrowOnly(hover.control);
            if (nav != null && nav.viewer == viewer && nav.annotations.size() >= 2)
            {
                Object controlRef = hover.control;
                List<Annotation> listRef = nav.annotations;
                int idxRef = nav.index;
                Display display = Display.getCurrent();
                if (display != null)
                    display.asyncExec(
                        () -> forceAnnotationNavToolbar(controlRef, listRef, viewer, idxRef));
            }
        }
        catch (Exception e)
        {
        }
    }

    private static final class HoverControlRef
    {
        final Object control;
        final Object manager;

        HoverControlRef(Object control, Object manager)
        {
            this.control = control;
            this.manager = manager;
        }
    }

    /** Подменить {@code fInformation} у менеджера видимого hover — для sticky recreate. */
    private static boolean patchManagerInformation(Object manager, Object info)
    {
        if (manager == null || info == null)
            return false;
        Object prev = Global.getField(manager, "fInformation"); //$NON-NLS-1$
        Global.setField(manager, "fInformation", info); //$NON-NLS-1$
        Object after = Global.getField(manager, "fInformation"); //$NON-NLS-1$
        boolean ok = after == info;
        return ok;
    }

    /** Записать актуальные proposals в существующий AnnotationInfo (поле final). */
    private static boolean isOurSpellingAnnotationInfo(Object info, Annotation expected)
    {
        if (info == null || expected == null || !isAnnotationInfo(info))
            return false;
        Object ann = Global.getField(info, "annotation"); //$NON-NLS-1$
        if (!(ann instanceof SpellingAnnotation sa)
            || !(expected instanceof SpellingAnnotation exp)
            || !(sa.getSpellingProblem() instanceof ModuleProblem a)
            || !(exp.getSpellingProblem() instanceof ModuleProblem b))
            return false;
        return a == b || a.offset == b.offset && a.length == b.length
            && (a.word == null ? b.word == null : a.word.equals(b.word));
    }

    private static boolean isAnnotationInfo(Object info)
    {
        if (info == null)
            return false;
        String name = info.getClass().getName();
        return name.contains("AnnotationWithQuickFixesHover$AnnotationInfo") //$NON-NLS-1$
            || name.contains("BslAnnotationWithQuickFixesHover$BslAnnotationInfo"); //$NON-NLS-1$
    }

    private static Object newAnnotationInfo(Annotation annotation, Position position,
        ITextViewer viewer, ICompletionProposal[] proposals)
    {
        try
        {
            Class<?> infoClass = Class.forName(
                "org.eclipse.xtext.ui.editor.hover.AnnotationWithQuickFixesHover$AnnotationInfo"); //$NON-NLS-1$
            Constructor<?> ctor = infoClass.getConstructor(Annotation.class, Position.class,
                ITextViewer.class, ICompletionProposal[].class);
            return ctor.newInstance(annotation, position, viewer,
                proposals != null ? proposals : new ICompletionProposal[0]);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    static boolean isComfortSpellingProposal(ICompletionProposal proposal)
    {
        if (proposal == null)
            return false;
        if (proposal instanceof AddToDictionaryProposal
            || proposal instanceof SpellingReplaceProposal
            || proposal instanceof LoadingProposal)
            return true;
        String display = proposal.getDisplayString();
        if (display == null)
            return false;
        return display.startsWith("Добавить в словарь:") //$NON-NLS-1$
            || LOADING_MARKER.equals(display);
    }

    static ICompletionProposal[] withoutComfortSpellingProposals(ICompletionProposal[] proposals)
    {
        if (proposals == null || proposals.length == 0)
            return proposals != null ? proposals : new ICompletionProposal[0];
        List<ICompletionProposal> kept = new ArrayList<>(proposals.length);
        for (ICompletionProposal p : proposals)
        {
            if (p != null && !isComfortSpellingProposal(p))
                kept.add(p);
        }
        return kept.toArray(new ICompletionProposal[0]);
    }

    static ICompletionProposal[] proposalsForAnnotation(Annotation annotation, ISourceViewer viewer,
        ICompletionProposal[] nonSpellingFallback)
    {
        if (annotation instanceof SpellingAnnotation sa
            && sa.getSpellingProblem() instanceof ModuleProblem mp)
        {
            ICompletionProposal[] fromProblem = mp.getProposals();
            return fromProblem != null ? fromProblem : new ICompletionProposal[0];
        }
        return nonSpellingFallback != null ? nonSpellingFallback : new ICompletionProposal[0];
    }

    /**
     * Фильтр proposals + кнопки «Назад/Вперёд» в sticky toolbar BSL
     * ({@code IBslHoverContributor}). Вызывается из {@code fillToolBar} до отрисовки
     * списка исправлений — здесь надёжнее, чем обёртка fTextHovers.
     */
    static void installAnnotationNavigationActions(IToolBarManager manager,
        Collection<Annotation> annotations)
    {
        if (annotations == null || annotations.isEmpty())
            return;
        List<Annotation> list = new ArrayList<>(annotations);
        ISourceViewer viewer = findViewerForAnnotations(list);
        // BSL getAnnotations иногда отдаёт 1 маркер, хотя на offset есть и spelling
        list = expandAnnotationsAtOffset(viewer, list);
        Object info = findLiveAnnotationInfo(viewer, list);
        int filtered = applyProposalFilterToInfo(info, viewer, list);
        if (manager == null || list.size() < 2)
            return;
        // Сохраняем полный список для force-toolbar после setInput
        if (viewer != null)
            annotationHoverNav = new AnnotationHoverNavState(viewer, list,
                annotationHoverNav != null && annotationHoverNav.viewer == viewer
                    ? annotationHoverNav.nonSpellingProposals : new ICompletionProposal[0],
                annotationHoverNav != null && annotationHoverNav.viewer == viewer
                    ? annotationHoverNav.index : 0);
        addAnnotationNavActions(manager, list, viewer);
        manager.update(true);
        layoutNavToolbar(manager);
    }

    private static void addAnnotationNavActions(IToolBarManager manager, List<Annotation> list,
        ISourceViewer viewer)
    {
        if (manager == null || list == null || list.size() < 2)
            return;
        removeAnnotationNavActions(manager);
        // MODE_FORCE_TEXT: после setInput status-toolbar часто рисует только icon,
        // а shared-image после removeAll становится «пустой» — кнопки пропадают визуально.
        manager.add(forceTextContribution(new AnnotationNavAction(false, list, viewer)));
        manager.add(forceTextContribution(new AnnotationNavAction(true, list, viewer)));
    }

    private static ActionContributionItem forceTextContribution(Action action)
    {
        ActionContributionItem item = new ActionContributionItem(action);
        item.setMode(ActionContributionItem.MODE_FORCE_TEXT);
        return item;
    }

    private static void removeAnnotationNavActions(IToolBarManager manager)
    {
        if (manager == null)
            return;
        IContributionItem prev = manager.find("tormozit.comfort.annNavPrev"); //$NON-NLS-1$
        if (prev != null)
            manager.remove(prev);
        IContributionItem next = manager.find("tormozit.comfort.annNavNext"); //$NON-NLS-1$
        if (next != null)
            manager.remove(next);
    }

    private static List<Annotation> expandAnnotationsAtOffset(ISourceViewer viewer,
        List<Annotation> seed)
    {
        if (viewer == null || seed == null || seed.isEmpty())
            return seed != null ? seed : List.of();
        IAnnotationModel model = viewer.getAnnotationModel();
        if (model == null)
            return seed;
        int offset = -1;
        int length = 1;
        for (Annotation ann : seed)
        {
            Position pos = model.getPosition(ann);
            if (pos != null && !pos.isDeleted)
            {
                offset = pos.getOffset();
                length = Math.max(pos.getLength(), 1);
                break;
            }
        }
        if (offset < 0)
            return seed;
        // Пересечение диапазонов: spelling на camelCase-сегменте иначе не попадает
        List<Annotation> all = collectAnnotationsOverlapping(viewer, offset, length);
        if (all.size() <= seed.size())
            return seed;
        List<Annotation> merged = new ArrayList<>(seed);
        for (Annotation ann : all)
        {
            if (indexOfAnnotation(merged, ann) < 0)
                merged.add(ann);
        }
        return merged;
    }

    /** Текущий AnnotationInfo sticky/hover: предпочитаем fInformation менеджера и sticky-control. */
    private static Object findLiveAnnotationInfo(ISourceViewer viewer, List<Annotation> annotations)
    {
        List<ISourceViewer> viewers = new ArrayList<>();
        if (viewer != null)
            viewers.add(viewer);
        for (EditorSession session : new ArrayList<>(SESSIONS.values()))
        {
            if (session != null && session.viewer != null && !viewers.contains(session.viewer))
                viewers.add(session.viewer);
        }
        for (ISourceViewer v : viewers)
        {
            Object[] managers = {
                Global.getField(v, "fTextHoverManager"), //$NON-NLS-1$
                Global.getField(v, "fInformationPresenter") //$NON-NLS-1$
            };
            for (Object mgr : managers)
            {
                if (mgr == null)
                    continue;
                Object info = Global.getField(mgr, "fInformation"); //$NON-NLS-1$
                if (annotationInfoMatches(info, annotations))
                    return info;
                HoverControlRef hover = findHoverControlRef(v, false);
                if (hover != null)
                {
                    Object input = Global.getField(hover.control, "fInput"); //$NON-NLS-1$
                    if (annotationInfoMatches(input, annotations))
                        return input;
                }
            }
        }
        return null;
    }

    private static boolean annotationInfoMatches(Object info, List<Annotation> annotations)
    {
        if (!isAnnotationInfo(info) || annotations == null)
            return false;
        Object ann = Global.getField(info, "annotation"); //$NON-NLS-1$
        if (!(ann instanceof Annotation annotation))
            return false;
        if (annotations.contains(annotation))
            return true;
        return indexOfAnnotation(annotations, annotation) >= 0;
    }

    /** @return число proposals после фильтра, или -1 если info не трогали */
    private static int applyProposalFilterToInfo(Object info, ISourceViewer viewer,
        List<Annotation> atOffset)
    {
        if (!isAnnotationInfo(info))
            return -1;
        Object annObj = Global.getField(info, "annotation"); //$NON-NLS-1$
        Object propsObj = Global.getField(info, "proposals"); //$NON-NLS-1$
        if (!(annObj instanceof Annotation annotation))
            return -1;
        ICompletionProposal[] merged = propsObj instanceof ICompletionProposal[] arr
            ? arr : new ICompletionProposal[0];
        ICompletionProposal[] fromMerged = withoutComfortSpellingProposals(merged);
        ICompletionProposal[] cached = viewer != null ? NON_SPELL_PROPOSALS.get(viewer) : null;
        boolean spellingPage = annotation instanceof SpellingAnnotation;
        ICompletionProposal[] nonSpelling;
        ICompletionProposal[] filtered;
        if (spellingPage)
        {
            // Страница орфографии: не затираем кэш non-spelling пустым результатом фильтра
            nonSpelling = cached != null && cached.length > 0 ? cached : fromMerged;
            filtered = proposalsForAnnotation(annotation, viewer, nonSpelling);
        }
        else
        {
            nonSpelling = fromMerged.length > 0 ? fromMerged
                : (cached != null ? cached : fromMerged);
            filtered = proposalsForAnnotation(annotation, viewer, nonSpelling);
            if (filtered.length > 0 && viewer != null)
                NON_SPELL_PROPOSALS.put(viewer, filtered);
            else if (nonSpelling.length > 0)
                filtered = nonSpelling;
        }
        int index = indexOfAnnotation(atOffset, annotation);
        if (index < 0)
            index = 0;
        if (viewer != null)
        {
            ICompletionProposal[] store = nonSpelling.length > 0 ? nonSpelling
                : (cached != null ? cached : nonSpelling);
            annotationHoverNav = new AnnotationHoverNavState(viewer, atOffset, store, index);
        }
        boolean ok = true;
        if (filtered != merged)
            ok = Global.setFieldForce(info, "proposals", filtered); //$NON-NLS-1$
        return filtered.length;
    }

    private static ISourceViewer findViewerForAnnotations(List<Annotation> annotations)
    {
        ISourceViewer active = resolveActiveBslViewer();
        if (active != null && annotationModelContains(active, annotations))
            return active;
        for (EditorSession session : new ArrayList<>(SESSIONS.values()))
        {
            if (session != null && session.viewer != null
                && annotationModelContains(session.viewer, annotations))
                return session.viewer;
        }
        return active;
    }

    private static boolean annotationModelContains(ISourceViewer viewer, List<Annotation> annotations)
    {
        if (viewer == null || annotations == null || annotations.isEmpty())
            return false;
        IAnnotationModel model = viewer.getAnnotationModel();
        if (model == null)
            return false;
        for (Annotation ann : annotations)
        {
            if (ann != null && model.getPosition(ann) != null)
                return true;
        }
        return false;
    }

    /** Сигнатура последнего setInput — не дёргать UI повторно тем же списком. */
    private static volatile String lastHoverProposalSig;
    /** Защита от reentrant setInput (fillToolBar → getProposals → refresh → setInput). */
    private static int hoverSetInputDepth;
    /** Макс. высота sticky-hover за сессию одного control — при смене маркера не сжимаем. */
    private static int annotationHoverMaxHeight;
    private static Object annotationHoverSizeControl;

    /**
     * Подгоняет размер sticky-hover: ширина по hint, высота только растёт
     * (кнопки навигации меньше скачут при переключении маркеров).
     */
    private static void applyHoverSizeGrowOnly(Object control)
    {
        if (!(control instanceof IInformationControl ic))
            return;
        Point hint = ic.computeSizeHint();
        if (hint == null)
            return;
        if (control != annotationHoverSizeControl)
        {
            annotationHoverSizeControl = control;
            annotationHoverMaxHeight = 0;
        }
        int curH = 0;
        Object shell = Global.invoke(control, "getShell"); //$NON-NLS-1$
        if (shell instanceof Shell s && !s.isDisposed())
            curH = s.getSize().y;
        int h = Math.max(annotationHoverMaxHeight, Math.max(curH, hint.y));
        annotationHoverMaxHeight = h;
        ic.setSize(hint.x, h);
    }

    private static String proposalSignature(ICompletionProposal[] proposals)
    {
        if (proposals == null || proposals.length == 0)
            return ""; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder(proposals.length * 16);
        for (ICompletionProposal p : proposals)
        {
            if (sb.length() > 0)
                sb.append('|');
            if (p == null)
                continue;
            String d = p.getDisplayString();
            sb.append(d != null ? d : p.getClass().getSimpleName());
        }
        return sb.toString();
    }

    private static Object findVisibleHoverControl(ISourceViewer viewer)
    {
        HoverControlRef ref = findVisibleHoverControlRef(viewer);
        return ref != null ? ref.control : null;
    }

    private static HoverControlRef findVisibleHoverControlRef(ISourceViewer viewer)
    {
        return findHoverControlRef(viewer, true);
    }

    /**
     * @param requireVisible {@code false} — во время {@code fillToolBar} shell ещё может
     *                       быть не visible; для навигации по клику тоже надёжнее.
     *                       Sticky (replacer) предпочтительнее основного hover-control.
     */
    private static HoverControlRef findHoverControlRef(ISourceViewer viewer, boolean requireVisible)
    {
        if (viewer == null)
            return null;
        Object[] managers = {
            Global.getField(viewer, "fTextHoverManager"), //$NON-NLS-1$
            Global.getField(viewer, "fInformationPresenter") //$NON-NLS-1$
        };
        // 1) sticky
        for (Object manager : managers)
        {
            if (manager == null)
                continue;
            Object replacer = Global.getField(manager, "fInformationControlReplacer"); //$NON-NLS-1$
            if (replacer == null)
                continue;
            Object sticky = Global.getField(replacer, "fInformationControl"); //$NON-NLS-1$
            if (isUsableInfoControl(sticky, requireVisible))
                return new HoverControlRef(sticky, manager);
        }
        // 2) обычный hover
        for (Object manager : managers)
        {
            if (manager == null)
                continue;
            Object control = Global.getField(manager, "fInformationControl"); //$NON-NLS-1$
            if (isUsableInfoControl(control, requireVisible))
                return new HoverControlRef(control, manager);
        }
        return null;
    }

    private static boolean isVisibleInfoControl(Object control)
    {
        return isUsableInfoControl(control, true);
    }

    private static boolean isUsableInfoControl(Object control, boolean requireVisible)
    {
        if (!(control instanceof IInformationControl))
            return false;
        Shell shell = infoControlShell(control);
        if (shell == null || shell.isDisposed())
            return false;
        return !requireVisible || shell.isVisible();
    }

    private static Shell infoControlShell(Object control)
    {
        try
        {
            Object shell = Global.invoke(control, "getShell"); //$NON-NLS-1$
            if (shell instanceof Shell s)
                return s;
        }
        catch (Exception ignored)
        {
        }
        Object field = Global.getField(control, "fShell"); //$NON-NLS-1$
        return field instanceof Shell s ? s : null;
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
        {
            SUGGEST_SESSIONS.clear();
            rescheduleAllSessions();
            return;
        }
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

    private static void wrapAnnotationHover(ISourceViewer viewer)
    {
        if (!(viewer instanceof SourceViewer sourceViewer))
            return;
        @SuppressWarnings("unchecked")
        Map<Object, ITextHover> hovers =
            (Map<Object, ITextHover>) Global.getField(sourceViewer, "fTextHovers"); //$NON-NLS-1$
        if (hovers == null || hovers.isEmpty())
            return;
        boolean wrapped = false;
        int candidates = 0;
        for (Map.Entry<Object, ITextHover> entry : hovers.entrySet())
        {
            ITextHover hover = entry.getValue();
            if (hover instanceof AnnotationHoverProposalFilter)
            {
                wrapped = true;
                continue;
            }
            // Любой hover, который может вернуть AnnotationInfo (в т.ч. composite/dispatch).
            if (hover == null)
                continue;
            candidates++;
            entry.setValue(new AnnotationHoverProposalFilter(hover));
            wrapped = true;
        }
        if (wrapped)
        {
            sourceViewer.setData(ANNOTATION_HOVER_WRAP_KEY, Boolean.TRUE);
            Object manager = Global.getField(sourceViewer, "fTextHoverManager"); //$NON-NLS-1$
            if (manager != null)
                Global.setField(manager, "fTextHover", null); //$NON-NLS-1$
        }
    }

    /**
     * Фильтр proposals в annotation hover: орфография Comfort не попадает в hover
     * ошибки валидации; для spelling-победителя — только её proposals.
     */
    private static final class AnnotationHoverProposalFilter
        implements ITextHover, ITextHoverExtension, ITextHoverExtension2
    {
        private final ITextHover delegate;
        private final ITextHoverExtension delegateExt;
        private final ITextHoverExtension2 delegateExt2;

        AnnotationHoverProposalFilter(ITextHover delegate)
        {
            this.delegate = delegate;
            this.delegateExt = delegate instanceof ITextHoverExtension ext ? ext : null;
            this.delegateExt2 = delegate instanceof ITextHoverExtension2 ext2 ? ext2 : null;
        }

        @Override
        public IRegion getHoverRegion(ITextViewer textViewer, int offset)
        {
            return delegate.getHoverRegion(textViewer, offset);
        }

        @Override
        public String getHoverInfo(ITextViewer textViewer, IRegion hoverRegion)
        {
            Object info = getHoverInfo2(textViewer, hoverRegion);
            return info != null ? String.valueOf(info) : null;
        }

        @Override
        public Object getHoverInfo2(ITextViewer textViewer, IRegion hoverRegion)
        {
            Object info = delegateExt2 != null
                ? delegateExt2.getHoverInfo2(textViewer, hoverRegion)
                : null;
            if (!isAnnotationInfo(info) || !(textViewer instanceof ISourceViewer viewer))
                return info;
            return filterAnnotationInfoProposals(info, viewer, hoverRegion);
        }

        @Override
        public org.eclipse.jface.text.IInformationControlCreator getHoverControlCreator()
        {
            return delegateExt != null ? delegateExt.getHoverControlCreator() : null;
        }
    }

    private static Object filterAnnotationInfoProposals(Object info, ISourceViewer viewer,
        IRegion hoverRegion)
    {
        Object posObj = Global.getField(info, "position"); //$NON-NLS-1$
        int offset = hoverRegion != null ? hoverRegion.getOffset()
            : (posObj instanceof Position p ? p.getOffset() : -1);
        List<Annotation> atOffset = offset >= 0
            ? collectAnnotationsAtOffset(viewer, offset) : List.of();
        applyProposalFilterToInfo(info, viewer, atOffset);
        return info;
    }

    private static List<Annotation> collectAnnotationsAtOffset(ISourceViewer viewer, int offset)
    {
        return collectAnnotationsOverlapping(viewer, offset, 1);
    }

    private static List<Annotation> collectAnnotationsOverlapping(ISourceViewer viewer, int offset,
        int length)
    {
        List<Annotation> result = new ArrayList<>();
        if (viewer == null)
            return result;
        IAnnotationModel model = viewer.getAnnotationModel();
        if (model == null)
            return result;
        int end = offset + Math.max(length, 1);
        Iterator<?> it = model.getAnnotationIterator();
        while (it.hasNext())
        {
            Object next = it.next();
            if (!(next instanceof Annotation ann) || ann.isMarkedDeleted())
                continue;
            Position pos = model.getPosition(ann);
            if (pos == null || pos.isDeleted)
                continue;
            int posEnd = pos.getOffset() + Math.max(pos.getLength(), 1);
            if (posEnd <= offset || pos.getOffset() >= end)
                continue;
            String type = ann.getType();
            if (type == null)
                continue;
            // Как AbstractProblemHover.isHandled: error/warning/info/spelling
            if (type.contains("error") || type.contains("warning") || type.contains("info") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || type.contains("spelling") || ann instanceof SpellingAnnotation) //$NON-NLS-1$
                result.add(ann);
        }
        return result;
    }

    private static int indexOfAnnotation(List<Annotation> list, Annotation annotation)
    {
        if (list == null || annotation == null)
            return -1;
        for (int i = 0; i < list.size(); i++)
        {
            if (list.get(i) == annotation)
                return i;
        }
        return -1;
    }

    private static final class AnnotationHoverNavState
    {
        final ISourceViewer viewer;
        final List<Annotation> annotations;
        final ICompletionProposal[] nonSpellingProposals;
        int index;

        AnnotationHoverNavState(ISourceViewer viewer, List<Annotation> annotations,
            ICompletionProposal[] nonSpellingProposals, int index)
        {
            this.viewer = viewer;
            this.annotations = annotations != null ? List.copyOf(annotations) : List.of();
            this.nonSpellingProposals = nonSpellingProposals != null
                ? nonSpellingProposals : new ICompletionProposal[0];
            this.index = index;
        }
    }

    private static final class AnnotationNavAction extends Action
    {
        private final boolean forward;
        private final List<Annotation> annotations;
        private final ISourceViewer viewer;

        AnnotationNavAction(boolean forward, List<Annotation> annotations, ISourceViewer viewer)
        {
            this.forward = forward;
            this.annotations = annotations;
            this.viewer = viewer;
            setId(forward ? "tormozit.comfort.annNavNext" : "tormozit.comfort.annNavPrev"); //$NON-NLS-1$ //$NON-NLS-2$
            setToolTipText(forward ? "Следующий маркер" : "Предыдущий маркер"); //$NON-NLS-1$ //$NON-NLS-2$
            // Только текст: иконки в status-toolbar sticky hover после setInput пропадают.
            setText(forward ? " ▶ " : " ◀ "); //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public void run()
        {
            navigateAnnotationHover(forward, annotations, viewer);
        }
    }

    private static void navigateAnnotationHover(boolean forward, List<Annotation> annotations,
        ISourceViewer actionViewer)
    {
        if (annotations == null || annotations.size() < 2)
        {
            return;
        }
        AnnotationHoverNavState state = annotationHoverNav;
        ISourceViewer viewer = actionViewer;
        if (viewer == null && state != null)
            viewer = state.viewer;
        if (viewer == null)
            viewer = findViewerForAnnotations(new ArrayList<>(annotations));
        if (viewer == null)
            viewer = resolveActiveBslViewer();
        if (viewer == null)
        {
            return;
        }
        HoverControlRef hover = findHoverControlRef(viewer, false);
        if (hover == null || !(hover.control instanceof IInformationControlExtension2 ext2))
        {
            return;
        }
        Object curInfo = Global.getField(hover.control, "fInput"); //$NON-NLS-1$
        if (curInfo == null && hover.manager != null)
            curInfo = Global.getField(hover.manager, "fInformation"); //$NON-NLS-1$
        Annotation current = null;
        if (isAnnotationInfo(curInfo))
        {
            Object ann = Global.getField(curInfo, "annotation"); //$NON-NLS-1$
            if (ann instanceof Annotation a)
                current = a;
        }
        List<Annotation> list = new ArrayList<>(annotations);
        if (state != null && state.viewer == viewer && !state.annotations.isEmpty())
            list = state.annotations;
        int idx = indexOfAnnotation(list, current);
        if (idx < 0)
            idx = state != null ? state.index : 0;
        int next = forward ? (idx + 1) % list.size() : (idx - 1 + list.size()) % list.size();
        Annotation target = list.get(next);
        IAnnotationModel model = viewer.getAnnotationModel();
        if (model == null)
        {
            return;
        }
        Position position = model.getPosition(target);
        if (position == null || position.isDeleted)
        {
            return;
        }
        ICompletionProposal[] cached = NON_SPELL_PROPOSALS.get(viewer);
        ICompletionProposal[] nonSpell = state != null && state.nonSpellingProposals.length > 0
            ? state.nonSpellingProposals
            : (cached != null && cached.length > 0 ? cached : withoutComfortSpellingProposals(
                curInfo != null && Global.getField(curInfo, "proposals") instanceof ICompletionProposal[] arr //$NON-NLS-1$
                    ? arr : new ICompletionProposal[0]));
        if (!(target instanceof SpellingAnnotation) && nonSpell.length > 0)
            NON_SPELL_PROPOSALS.put(viewer, nonSpell);
        ICompletionProposal[] proposals = proposalsForAnnotation(target, viewer, nonSpell);
        Object info = curInfo;
        boolean mutated = false;
        if (isAnnotationInfo(curInfo))
        {
            mutated = Global.setFieldForce(curInfo, "annotation", target); //$NON-NLS-1$
            mutated = Global.setFieldForce(curInfo, "position", position) || mutated; //$NON-NLS-1$
            mutated = Global.setFieldForce(curInfo, "proposals", proposals) || mutated; //$NON-NLS-1$
            info = curInfo;
        }
        if (!mutated || info == null)
            info = newAnnotationInfo(target, position, viewer, proposals);
        if (info == null)
        {
            return;
        }
        annotationHoverNav = new AnnotationHoverNavState(viewer, list, nonSpell, next);
        patchManagerInformation(hover.manager, info);
        // Сигнатура текущего setInput — async refresh с тем же списком не дублирует UI.
        lastHoverProposalSig = System.identityHashCode(hover.control) + ":" //$NON-NLS-1$
            + proposalSignature(proposals);
        hoverSetInputDepth++;
        try
        {
            ext2.setInput(info);
        }
        finally
        {
            hoverSetInputDepth--;
        }
        // setInput/fillToolBar: removeAll обнуляет ширину ToolBar — кнопки есть, но не видны.
        forceAnnotationNavToolbar(hover.control, list, viewer, next);
        applyHoverSizeGrowOnly(hover.control);
        // После setSize layout status-bar ещё раз (иначе ToolBar остаётся 0×0).
        Object controlRef = hover.control;
        List<Annotation> listRef = list;
        ISourceViewer viewerRef = viewer;
        int idxRef = next;
        Display display = Display.getCurrent();
        if (display != null)
        {
            display.asyncExec(() -> forceAnnotationNavToolbar(controlRef, listRef, viewerRef, idxRef));
        }
    }

    private static void forceAnnotationNavToolbar(Object control, List<Annotation> annotations,
        ISourceViewer viewer, int index)
    {
        if (control == null)
            return;
        try
        {
            Object tbm = Global.invoke(control, "getToolBarManager"); //$NON-NLS-1$
            if (!(tbm instanceof IToolBarManager))
                tbm = Global.getField(control, "fToolBarManager"); //$NON-NLS-1$
            if (!(tbm instanceof IToolBarManager manager))
            {
                return;
            }
            List<Annotation> list = annotations;
            if (list == null || list.size() < 2)
            {
                AnnotationHoverNavState state = annotationHoverNav;
                if (state != null && state.annotations.size() >= 2)
                    list = state.annotations;
            }
            if (list == null || list.size() < 2)
            {
                manager.update(true);
                return;
            }
            addAnnotationNavActions(manager, list, viewer);
            manager.update(true);
            int idx = index;
            if (idx < 0 || idx >= list.size())
            {
                AnnotationHoverNavState state = annotationHoverNav;
                idx = state != null ? state.index : 0;
            }
            // setStatusText() no-op когда shell уже visible — пишем в label напрямую.
            Object statusLabel = Global.getField(control, "fStatusLabel"); //$NON-NLS-1$
            if (statusLabel instanceof Label label && !label.isDisposed())
                label.setText((idx + 1) + "/" + list.size()); //$NON-NLS-1$
            layoutNavToolbar(manager);
        }
        catch (Exception e)
        {
        }
    }

    private static void layoutNavToolbar(IToolBarManager manager)
    {
        ToolBar tb = null;
        if (manager instanceof ToolBarManager tbm)
            tb = tbm.getControl();
        if (tb == null || tb.isDisposed())
            return;
        Composite parent = tb.getParent();
        if (parent != null && !parent.isDisposed())
        {
            parent.layout(true, true);
            Composite status = parent.getParent();
            if (status != null && !status.isDisposed())
                status.layout(true, true);
        }
    }

    private static ISourceViewer resolveActiveBslViewer()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;
            IEditorPart editor = page.getActiveEditor();
            if (editor instanceof BslXtextEditor bsl)
                return bsl.getInternalSourceViewer();
            if (editor instanceof DtGranularEditor<?> granular)
            {
                Object embedded = Global.invoke(granular, "getActiveEditorPage"); //$NON-NLS-1$
                if (embedded instanceof DtGranularEditorXtextEditorPage pageEd)
                {
                    Object inner = Global.invoke(pageEd, "getEditor"); //$NON-NLS-1$
                    if (inner instanceof BslXtextEditor bsl)
                        return bsl.getInternalSourceViewer();
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return null;
    }
}
