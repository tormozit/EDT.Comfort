package tormozit;

import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.WeakHashMap;

import org.osgi.framework.Bundle;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.AbstractInformationControlManager;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IInformationControlExtension2;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextHoverExtension;
import org.eclipse.jface.text.ITextHoverExtension2;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.CompletionProposal;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.quickassist.IQuickAssistInvocationContext;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
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
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.spelling.SpellingAnnotation;
import org.eclipse.ui.texteditor.spelling.SpellingProblem;
import org.eclipse.ui.texteditor.spelling.SpellingService;
import org.eclipse.xtext.AbstractRule;
import org.eclipse.xtext.CrossReference;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.GrammarUtil;
import org.eclipse.xtext.Keyword;
import org.eclipse.xtext.RuleCall;
import org.eclipse.xtext.diagnostics.Diagnostic;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.IScopeProvider;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;
import org.eclipse.xtext.ui.editor.hover.IEObjectHover;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.editor.validation.XtextAnnotation;
import org.eclipse.xtext.ui.refactoring.ui.IRenameContextFactory;
import org.eclipse.xtext.ui.refactoring.ui.IRenameElementContext;
import org.eclipse.xtext.ui.refactoring.ui.IRenameSupport;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;
import org.eclipse.xtext.validation.Issue;

import com._1c.g5.v8.dt.bm.index.emf.IBmEmfIndexManager;
import com._1c.g5.v8.dt.bm.index.emf.IBmEmfIndexProvider;
import com._1c.g5.v8.dt.bsl.linking.BslLinkingDiagnosticErrorCodes;
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
import com._1c.g5.v8.dt.bsl.ui.hover.BslDispatchingEObjectTextHover;
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
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
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
            installHoverCopyExecutionListener();
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

    private static volatile boolean hoverCopyListenerInstalled;

    /**
     * Ctrl+C над ховером кода (annotation/quick-fix hover, {@code BrowserInformationControl})
     * не долетает как {@code SWT.KeyDown} — тот же архитектурный потолок, что и для
     * {@code KeyBindingToastHook}/Ctrl+Shift+F и {@code PreferenceSearchFilterAugmenter.wireTreeCopy}:
     * нативный Win32-акселератор съедает букву раньше SWT. Перехват — через
     * {@code ICommandService.addExecutionListener} на {@code org.eclipse.ui.edit.copy}.
     */
    private static void installHoverCopyExecutionListener()
    {
        if (hoverCopyListenerInstalled || PlatformUI.getWorkbench() == null)
            return;
        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        commandService.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
                handlePossibleHoverCopy(commandId);
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception)
            {
                handlePossibleHoverCopy(commandId);
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception)
            {
            }
        });
        hoverCopyListenerInstalled = true;
    }

    private static void handlePossibleHoverCopy(String commandId)
    {
        if (!"org.eclipse.ui.edit.copy".equals(commandId)) //$NON-NLS-1$
            return;
        Display display = Display.getCurrent();
        if (display == null)
            return;
        Control focus = display.getFocusControl();
        if (focus == null)
            return;
        Shell hoverShell = findActiveHoverShellContaining(focus);
        if (hoverShell == null)
            return;
        String text = extractHoverCopyText(focus);
        if (text == null || text.isBlank())
            return;
        Clipboard clipboard = new Clipboard(display);
        try
        {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            clipboard.dispose();
        }
    }

    /** Фокус — внутри shell'а сейчас видимого ховера одного из открытых BSL-редакторов. */
    private static Shell findActiveHoverShellContaining(Control focus)
    {
        for (EditorSession session : SESSIONS.values())
        {
            if (session == null || session.viewer == null)
                continue;
            HoverControlRef ref = findVisibleHoverControlRef(session.viewer);
            if (ref == null)
                continue;
            Shell shell = infoControlShell(ref.control);
            if (shell == null || shell.isDisposed())
                continue;
            if (isDescendantOrSelf(focus, shell))
                return shell;
        }
        return null;
    }

    private static boolean isDescendantOrSelf(Control control, Shell shell)
    {
        for (Control c = control; c != null; c = c.getParent())
        {
            if (c == shell)
                return true;
        }
        return false;
    }

    /** Выделение в ховере, иначе весь его текст ({@code Browser} — HTML, {@code StyledText} — как есть). */
    private static String extractHoverCopyText(Control focus)
    {
        Shell shell = focus.getShell();
        Browser browser = findDescendant(shell, Browser.class);
        if (browser != null)
        {
            Object selection = browser.evaluate(
                "return window.getSelection() ? window.getSelection().toString() : '';"); //$NON-NLS-1$
            if (selection instanceof String s && !s.isBlank())
                return s;
            Object full = browser.evaluate(
                "return document.body ? (document.body.innerText || document.body.textContent || '') : '';"); //$NON-NLS-1$
            return full instanceof String s ? s : null;
        }
        StyledText styled = focus instanceof StyledText st ? st : findDescendant(shell, StyledText.class);
        if (styled != null)
        {
            String selection = styled.getSelectionText();
            if (selection != null && !selection.isBlank())
                return selection;
            return styled.getText();
        }
        return null;
    }

    private static <T extends Control> T findDescendant(Control root, Class<T> type)
    {
        if (root == null)
            return null;
        if (type.isInstance(root))
            return type.cast(root);
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                T found = findDescendant(child, type);
                if (found != null)
                    return found;
            }
        }
        return null;
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

    /** Префикс display string у предложений issue #176 - см. {@link #isIssue176Proposal}. */
    private static final String ISSUE176_PROPOSAL_PREFIX = "Заменить на '"; //$NON-NLS-1$

    private static boolean isIssue176Proposal(ICompletionProposal p)
    {
        if (p == null)
            return false;
        String display = p.getDisplayString();
        return display != null && display.startsWith(ISSUE176_PROPOSAL_PREFIX);
    }

    /**
     * issue #176: без этой очистки предложение "Заменить на '...'" дублируется при каждом
     * пересчёте hover (например, при листании страниц/маркеров sticky-тулбара) - filtered
     * читает уже накопленный nonSpellingFallback и снова добавляет своё поверх.
     */
    private static ICompletionProposal[] withoutIssue176Proposals(ICompletionProposal[] proposals)
    {
        if (proposals == null || proposals.length == 0)
            return proposals != null ? proposals : new ICompletionProposal[0];
        List<ICompletionProposal> kept = new ArrayList<>(proposals.length);
        for (ICompletionProposal p : proposals)
        {
            if (p != null && !isIssue176Proposal(p))
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
        ICompletionProposal[] base = withoutIssue176Proposals(
            nonSpellingFallback != null ? nonSpellingFallback : new ICompletionProposal[0]);
        Global.tempLog("issue176", "proposalsForAnnotation: annotationClass=" //$NON-NLS-1$ //$NON-NLS-2$
            + (annotation != null ? annotation.getClass().getName() : "null") + " type=" //$NON-NLS-1$ //$NON-NLS-2$
            + (annotation != null ? annotation.getType() : "null") + " isXtextAnnotation=" //$NON-NLS-1$ //$NON-NLS-2$
            + (annotation instanceof XtextAnnotation));
        if (annotation instanceof XtextAnnotation xa)
        {
            // Свои "Заменить на '...'" визуально должны выглядеть как штатные исправления -
            // берём иконку у любого уже существующего предложения в этом же списке.
            Image fallbackIcon = null;
            for (ICompletionProposal p : base)
            {
                if (p != null && p.getImage() != null)
                {
                    fallbackIcon = p.getImage();
                    break;
                }
            }
            ICompletionProposal extra =
                similarNameProposalForUnresolvedReference(xa, viewer, fallbackIcon);
            if (extra != null)
            {
                ICompletionProposal[] withExtra = Arrays.copyOf(base, base.length + 1);
                withExtra[base.length] = extra;
                return withExtra;
            }
        }
        return base;
    }

    /**
     * issue #176: для штатной ошибки EDT "не найдено" - предложить существующее имя, если оно
     * отличается от введённого ровно на одну правку (вставка/удаление/замена символа). См.
     * {@link #buildSimilarNameProposal} - сначала {@link IScope} несвязавшейся ссылки (когда BSL
     * резолвит имя как cross-reference), затем {@link #candidateNameTable}. {@code undefined-type}
     * покрывается платформенными типами из {@link #platformApiNames()} (каталог
     * {@code McorePackage.Literals.TYPE_ITEM}). {@code undefined-label} пока не поддержан - метки
     * не входят ни в один из трёх источников {@link #candidateNameTable}.
     *
     * <p>{@code SU74} - легаси-код старого чекера (не из {@link BslLinkingDiagnosticErrorCodes}),
     * "Свойство (метод) объекта не обнаружено" - обращение через точку (`Объект.Метод`) к
     * несуществующему свойству/методу. Кандидаты ищутся в той же общей таблице (не только среди
     * членов конкретного типа объекта слева от точки) - при опечатке на расстоянии 1 это обычно
     * не даёт ложных срабатываний.
     */
    private static final Set<String> DECLARED_NAME_ERROR_CODES = Set.of(
        BslLinkingDiagnosticErrorCodes.UNDEFINED_VARIABLE,
        BslLinkingDiagnosticErrorCodes.UNDEFINED_METHOD,
        BslLinkingDiagnosticErrorCodes.UNDEFINED_FUNCTION,
        BslLinkingDiagnosticErrorCodes.UNDEFINED_TYPE,
        "SU74"); //$NON-NLS-1$

    private static ICompletionProposal similarNameProposalForUnresolvedReference(XtextAnnotation xa,
        ISourceViewer viewer, Image icon)
    {
        try
        {
            Issue issue = xa.getIssue();
            String code = issue != null ? issue.getCode() : null;
            Global.tempLog("issue176", "similarNameProposalForUnresolvedReference: code=" + code //$NON-NLS-1$ //$NON-NLS-2$
                + " message=" + (issue != null ? issue.getMessage() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
            boolean isLinking = Diagnostic.LINKING_DIAGNOSTIC.equals(code);
            boolean isDeclaredNameCode = DECLARED_NAME_ERROR_CODES.contains(code);
            if (issue == null || !(isLinking || isDeclaredNameCode))
                return null;
            Integer offset = issue.getOffset();
            Integer length = issue.getLength();
            IXtextDocument document = xa.getDocument();
            if (offset == null || length == null || document == null)
                return null;
            return document.readOnly(
                (IUnitOfWork<ICompletionProposal, XtextResource>) resource -> buildSimilarNameProposal(
                    resource, viewer, offset, length, icon));
        }
        catch (Exception e)
        {
            Global.tempLog("issue176", "similarNameProposalForUnresolvedReference: исключение " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /**
     * issue #176: единая таблица кандидатов - сначала точный {@link IScope} несвязавшейся
     * cross-reference (если BSL резолвит имя в этой позиции именно так - например, общий модуль),
     * затем {@link #candidateNameTable(XtextResource)} (переменные/методы модуля + платформенный
     * API + объекты метаданных), и в последнюю очередь - тот же движок, что у штатного Ctrl+Space
     * ({@link #closestNameFromContentAssist}), для случаев вроде членов конкретного типа объекта
     * после точки, которых нет в плоской общей таблице.
     */
    private static ICompletionProposal buildSimilarNameProposal(XtextResource resource,
        ISourceViewer viewer, int offset, int length, Image icon)
    {
        ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(),
            offset);
        if (leaf == null)
        {
            Global.tempLog("issue176", "buildSimilarNameProposal: leaf==null offset=" + offset); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        String typed = leaf.getText();
        String best = closestNameFromScope(resource, leaf, typed);
        if (best == null)
            best = closestNameFromTable(resource, typed);
        if (best == null)
            best = closestNameFromContentAssist(viewer, offset, typed);
        Global.tempLog("issue176", "buildSimilarNameProposal: typed='" + typed + "' best=" + best); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (best == null || best.equals(typed))
            return null;
        return new CompletionProposal(best, offset, length, best.length(), icon,
            ISSUE176_PROPOSAL_PREFIX + best + "'", null, null); //$NON-NLS-1$
    }

    /**
     * Тот же список, что даёт штатный Ctrl+Space в этой позиции - через delegate
     * {@link IContentAssistProcessor} BSL-редактора. Вызывается на UI-потоке через
     * {@link Display#syncExec} - hover сам по себе считается на фоновом потоке, а не в живом
     * SWT-вызове синхронно из UI, поэтому дедлока тут не возникает (в отличие от прямого вызова
     * без syncExec, который падал {@code SWTException: Invalid thread access} - см. лог issue176
     * от 2026-07-19).
     */
    private static String closestNameFromContentAssist(ISourceViewer viewer, int offset, String typed)
    {
        if (!(viewer instanceof SourceViewer sourceViewer))
        {
            Global.tempLog("issue176", "closestNameFromContentAssist: viewer не SourceViewer"); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return null;
        ICompletionProposal[][] holder = new ICompletionProposal[1][];
        display.syncExec(() ->
        {
            try
            {
                ContentAssistant contentAssist = ContentAssistPatcher.getContentAssistant(sourceViewer);
                if (contentAssist == null)
                {
                    Global.tempLog("issue176", "closestNameFromContentAssist: contentAssist==null"); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                IContentAssistProcessor current =
                    contentAssist.getContentAssistProcessor(IDocument.DEFAULT_CONTENT_TYPE);
                IContentAssistProcessor xtextProcessor = current instanceof SmartContentAssistProcessor scp
                    ? scp.getDelegate() : current;
                if (xtextProcessor == null)
                {
                    Global.tempLog("issue176", "closestNameFromContentAssist: processor==null"); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                // offset - начало ещё не набранного слова, чтобы получить полный список
                // кандидатов в этой позиции, без префиксной фильтрации по опечатке.
                holder[0] = xtextProcessor.computeCompletionProposals(viewer, offset);
            }
            catch (Exception e)
            {
                Global.tempLog("issue176", "closestNameFromContentAssist: исключение " + e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
        ICompletionProposal[] raw = holder[0];
        Global.tempLog("issue176", "closestNameFromContentAssist: typed='" + typed + "' raw.length=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + (raw != null ? raw.length : -1));
        if (raw == null)
            return null;
        StringBuilder names = new StringBuilder();
        String best = null;
        for (ICompletionProposal p : raw)
        {
            String candidate = p instanceof ConfigurableCompletionProposal ccp
                ? ccp.getReplacementString() : p.getDisplayString();
            // Шаблоны методов приходят как "Имя(, , );" - расстояние правки сравниваем
            // по голому имени, до скобки параметров (как normalizeFilterKey в IrBslCompletionSupport).
            String baseName = baseNameOf(candidate);
            if (names.length() > 0)
                names.append(", "); //$NON-NLS-1$
            names.append(baseName);
            if (best == null && baseName != null && isEditDistanceOne(typed, baseName))
                best = baseName;
        }
        Global.tempLog("issue176", "closestNameFromContentAssist: raw names=" + names); //$NON-NLS-1$ //$NON-NLS-2$
        return best;
    }

    /** "Имя(, , );" / "Имя()" → "Имя" - как {@code IrBslCompletionSupport.normalizeFilterKey}. */
    private static String baseNameOf(String text)
    {
        if (text == null)
            return null;
        int paren = text.indexOf('(');
        return (paren >= 0 ? text.substring(0, paren) : text).strip();
    }

    private static String closestNameFromScope(XtextResource resource, ILeafNode leaf, String typed)
    {
        CrossReference crossReference = GrammarUtil.containingCrossReference(leaf.getGrammarElement());
        EReference reference = crossReference != null ? GrammarUtil.getReference(crossReference) : null;
        EObject semanticElement = NodeModelUtils.findActualSemanticObjectFor(leaf);
        EObject context = semanticElement != null ? semanticElement.eContainer() : null;
        if (reference == null || context == null)
            return null;
        IScopeProvider scopeProvider = resource.getResourceServiceProvider().get(IScopeProvider.class);
        IScope scope = scopeProvider.getScope(context, reference);
        for (IEObjectDescription d : scope.getAllElements())
        {
            String name = d.getName().getLastSegment();
            if (name != null && isEditDistanceOne(typed, name))
                return name;
        }
        return null;
    }

    private static String closestNameFromTable(XtextResource resource, String typed)
    {
        for (String name : candidateNameTable(resource))
        {
            if (isEditDistanceOne(typed, name))
                return name;
        }
        return null;
    }

    /**
     * issue #176: единая таблица валидных имён в модуле - без обращения к SWT/UI-потоку и без
     * Guice-override {@code bslUiModuleExtension} (оба пути закрыты, см. javadoc класса и память
     * feedback-bsl-ui-module-extension-verifyerror). Три безопасных источника, уже проверенных
     * в этом же файле/плагине:
     * <ul>
     * <li>объявленные в модуле {@link Variable}/{@link FormalParam}/процедуры и функции
     * ({@code com._1c.g5.v8.dt.bsl.model.Method}) - обход {@code eAllContents()};
     * <li>платформенный API (методы/свойства/типы) - {@link #platformApiNames()}, уже готовый
     * кэш из {@link IEObjectProvider};
     * <li>объекты метаданных проекта (общие модули, справочники, документы и т.п., то есть и
     * "типы", и "общие модули" из требования) - BM-индекс через {@code IBmEmfIndexManager}
     * (тот же паттерн, что в {@code ComfortNavigatorSearchEngine.buildTrie}).
     * </ul>
     */
    private static Set<String> candidateNameTable(XtextResource resource)
    {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (!resource.getContents().isEmpty())
        {
            TreeIterator<EObject> it = resource.getContents().get(0).eAllContents();
            while (it.hasNext())
            {
                EObject o = it.next();
                if (o instanceof Variable v && v.getName() != null)
                    names.add(v.getName());
                else if (o instanceof FormalParam p && p.getName() != null)
                    names.add(p.getName());
                else if (o instanceof com._1c.g5.v8.dt.bsl.model.Method m && m.getName() != null)
                    names.add(m.getName());
            }
        }
        names.addAll(platformApiNames());
        names.addAll(metadataObjectNames(resource));
        Global.tempLog("issue176", "candidateNameTable: total=" + names.size()); //$NON-NLS-1$ //$NON-NLS-2$
        return names;
    }

    /** Имена всех объектов метаданных проекта (общие модули, справочники, документы и т.п.) - BM-индекс. */
    private static Set<String> metadataObjectNames(XtextResource resource)
    {
        try
        {
            IProject project = projectOf(resource);
            if (project == null)
            {
                Global.tempLog("issue176", "metadataObjectNames: project==null uri=" + resource.getURI()); //$NON-NLS-1$ //$NON-NLS-2$
                return Set.of();
            }
            IBmEmfIndexManager manager = Global.getOsgiService(IBmEmfIndexManager.class);
            IBmEmfIndexProvider indexProvider = manager != null ? manager.getEmfIndexProvider(project) : null;
            if (indexProvider == null)
            {
                Global.tempLog("issue176", "metadataObjectNames: indexProvider==null manager=" //$NON-NLS-1$ //$NON-NLS-2$
                    + (manager != null));
                return Set.of();
            }
            Iterable<IEObjectDescription> index =
                indexProvider.getEObjectIndexByType(MdClassPackage.Literals.MD_OBJECT);
            if (index == null)
                return Set.of();
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (IEObjectDescription d : index)
            {
                QualifiedName qn = d != null ? d.getName() : null;
                String last = qn != null && !qn.isEmpty() ? qn.getLastSegment() : null;
                if (last != null && !last.isEmpty())
                    names.add(last);
            }
            return names;
        }
        catch (Exception e)
        {
            Global.tempLog("issue176", "metadataObjectNames: исключение " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return Set.of();
        }
    }

    private static IProject projectOf(XtextResource resource)
    {
        URI uri = resource.getURI();
        if (uri == null || !uri.isPlatformResource())
            return null;
        String platformString = uri.toPlatformString(true);
        if (platformString == null)
            return null;
        IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(platformString));
        return file != null ? file.getProject() : null;
    }

    /** {@code true}, если a и b различаются ровно на одну вставку/удаление/замену символа. */
    private static boolean isEditDistanceOne(String a, String b)
    {
        if (a.equals(b))
            return false;
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > 1)
            return false;
        int i = 0;
        int j = 0;
        boolean editUsed = false;
        while (i < la && j < lb)
        {
            if (a.charAt(i) == b.charAt(j))
            {
                i++;
                j++;
                continue;
            }
            if (editUsed)
                return false;
            editUsed = true;
            if (la == lb)
            {
                i++;
                j++;
            }
            else if (la > lb)
                i++;
            else
                j++;
        }
        return true;
    }

    private static final String BSL_UI_BUNDLE = "com._1c.g5.v8.dt.bsl.ui"; //$NON-NLS-1$
    private static final String SYNTAX_HELP_ICON = "icons/eview/syntax_help.png"; //$NON-NLS-1$
    /** Отдельный presenter описания — без mouse-recompute {@code fTextHoverManager}. */
    private static volatile IdentifierDocHoverPresenter identifierDocPresenter;

    /**
     * Фильтр proposals + кнопки sticky toolbar BSL ({@code IBslHoverContributor}):
     * «Назад/Вперёд» между маркерами и справа кнопка с иконкой синтакс-помощника
     * (открывает штатную подсказку с описанием идентификатора).
     */
    static void installAnnotationNavigationActions(IToolBarManager manager,
        Collection<Annotation> annotations)
    {
        Global.tempLog("issue176", "installAnnotationNavigationActions: annotations=" //$NON-NLS-1$ //$NON-NLS-2$
            + (annotations != null ? annotations.size() : -1));
        if (annotations == null || annotations.isEmpty())
            return;
        List<Annotation> list = new ArrayList<>(annotations);
        ISourceViewer viewer = findViewerForAnnotations(list);
        // BSL getAnnotations иногда отдаёт 1 маркер, хотя на offset есть и spelling
        list = expandAnnotationsAtOffset(viewer, list);
        Object info = findLiveAnnotationInfo(viewer, list);
        applyProposalFilterToInfo(info, viewer, list);
        if (manager == null)
            return;
        int pageIndex = 0;
        boolean hasNav = list.size() >= 2;
        if (hasNav && viewer != null)
        {
            pageIndex = annotationHoverNav != null && annotationHoverNav.viewer == viewer
                ? annotationHoverNav.index : 0;
            annotationHoverNav = new AnnotationHoverNavState(viewer, list,
                annotationHoverNav != null && annotationHoverNav.viewer == viewer
                    ? annotationHoverNav.nonSpellingProposals : new ICompletionProposal[0],
                pageIndex);
        }
        int offset = resolveAnnotationOffset(viewer, list);
        boolean hasSyntax = viewer != null && offset >= 0
            && hasIdentifierDocumentation(viewer, offset);
        if (!hasNav && !hasSyntax)
            return;
        addAnnotationToolbarActions(manager, list, viewer, pageIndex, offset, hasSyntax);
        manager.update(true);
        layoutNavToolbar(manager);
    }

    private static void addAnnotationToolbarActions(IToolBarManager manager, List<Annotation> list,
        ISourceViewer viewer, int index, int docOffset, boolean showSyntaxHelp)
    {
        if (manager == null)
            return;
        removeAnnotationToolbarActions(manager);
        if (list != null && list.size() >= 2)
        {
            int page = index;
            if (page < 0 || page >= list.size())
                page = 0;
            // MODE_FORCE_TEXT: после setInput status-toolbar часто рисует только icon,
            // а shared-image после removeAll становится «пустой» — кнопки пропадают визуально.
            manager.add(forceTextContribution(new AnnotationNavAction(false, list, viewer)));
            manager.add(forceTextContribution(newAnnotationNavPageAction(page, list.size())));
            manager.add(forceTextContribution(new AnnotationNavAction(true, list, viewer)));
        }
        if (showSyntaxHelp && viewer != null && docOffset >= 0)
        {
            // Без spacer: status-bar уже прижимает ToolBar вправо; spacer в ToolBar
            // давал скачущую ширину при layout.
            manager.add(newAnnotationSyntaxHelpAction(viewer, docOffset));
        }
    }

    private static Action newAnnotationNavPageAction(int pageIndex, int pageCount)
    {
        Action page = new Action()
        {
            @Override
            public void run()
            {
                // Только отображение номера страницы.
            }
        };
        page.setId("tormozit.comfort.annNavPage"); //$NON-NLS-1$
        page.setEnabled(false);
        page.setText(" " + (pageIndex + 1) + "/" + pageCount + " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        page.setToolTipText("Маркер " + (pageIndex + 1) + " из " + pageCount); //$NON-NLS-1$ //$NON-NLS-2$
        return page;
    }

    private static Action newAnnotationSyntaxHelpAction(ISourceViewer viewer, int offset)
    {
        Action action = new Action()
        {
            @Override
            public void run()
            {
                openStandardIdentifierDocHover(viewer, offset);
            }
        };
        action.setId("tormozit.comfort.annSyntaxHelp"); //$NON-NLS-1$
        ImageDescriptor icon = syntaxHelpImageDescriptor();
        if (icon != null)
            action.setImageDescriptor(icon);
        else
            action.setText("?"); //$NON-NLS-1$
        action.setToolTipText("Открыть описание" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        return action;
    }

    private static ImageDescriptor syntaxHelpImageDescriptor()
    {
        try
        {
            Bundle bundle = Platform.getBundle(BSL_UI_BUNDLE);
            if (bundle == null)
                return null;
            URL url = bundle.getEntry(SYNTAX_HELP_ICON);
            return url != null ? ImageDescriptor.createFromURL(url) : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static ActionContributionItem forceTextContribution(Action action)
    {
        ActionContributionItem item = new ActionContributionItem(action);
        item.setMode(ActionContributionItem.MODE_FORCE_TEXT);
        return item;
    }

    private static void removeAnnotationToolbarActions(IToolBarManager manager)
    {
        if (manager == null)
            return;
        IContributionItem prev = manager.find("tormozit.comfort.annNavPrev"); //$NON-NLS-1$
        if (prev != null)
            manager.remove(prev);
        IContributionItem page = manager.find("tormozit.comfort.annNavPage"); //$NON-NLS-1$
        if (page != null)
            manager.remove(page);
        IContributionItem next = manager.find("tormozit.comfort.annNavNext"); //$NON-NLS-1$
        if (next != null)
            manager.remove(next);
        IContributionItem syntax = manager.find("tormozit.comfort.annSyntaxHelp"); //$NON-NLS-1$
        if (syntax != null)
            manager.remove(syntax);
    }

    private static int resolveAnnotationOffset(ISourceViewer viewer, List<Annotation> list)
    {
        if (viewer == null || list == null || list.isEmpty())
            return -1;
        IAnnotationModel model = viewer.getAnnotationModel();
        if (model == null)
            return -1;
        for (Annotation a : list)
        {
            Position p = model.getPosition(a);
            if (p != null && !p.isDeleted)
                return p.getOffset();
        }
        return -1;
    }

    /** Есть ли штатное описание идентификатора на offset (для кнопки с иконкой). */
    private static boolean hasIdentifierDocumentation(ISourceViewer viewer, int offset)
    {
        return prepareIdentifierDocHover(viewer, offset) != null;
    }

    private static final class IdentifierDocHoverOpen
    {
        final Object info;
        final IInformationControlCreator creator;
        final IRegion region;

        IdentifierDocHoverOpen(Object info, IInformationControlCreator creator, IRegion region)
        {
            this.info = info;
            this.creator = creator;
            this.region = region;
        }
    }

    private static IdentifierDocHoverOpen prepareIdentifierDocHover(ISourceViewer viewer, int offset)
    {
        if (viewer == null || offset < 0)
            return null;
        try
        {
            if (!(viewer.getDocument() instanceof IXtextDocument xdoc))
                return null;
            URI resourceUri = xdoc.getResourceURI();
            if (resourceUri == null)
                return null;
            IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
                .getResourceServiceProvider(resourceUri);
            if (rsp == null)
                return null;
            IEObjectHover hover = rsp.get(IEObjectHover.class);
            if (!(hover instanceof BslDispatchingEObjectTextHover bslHover))
                return null;
            IRegion region = bslHover.getHoverRegion(viewer, offset);
            if (region == null)
                region = new Region(offset, 1);
            Object info = bslHover.getHoverInfo2(viewer, region);
            if (info == null)
                return null;
            String html = IrBslHoverHtml.readHtml(info);
            if (html == null || html.isBlank())
                return null;
            String fragment = IrBslHoverHtml.extractInsertableFragment(html);
            fragment = IrBslHoverHtml.stripIrEmbeddedChrome(fragment);
            String plain = fragment.replaceAll("(?is)<[^>]+>", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
            if (plain.isBlank())
                return null;
            IInformationControlCreator creator = bslHover.getHoverControlCreator();
            if (creator == null)
                return null;
            return new IdentifierDocHoverOpen(info, creator, region);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Закрыть sticky annotation hover и показать описание по offset исходного popup.
     * Отдельный presenter: constraints копируются с {@code fTextHoverManager}, без
     * mouse-{@code computeInformation} (иначе контент прыгает под указатель).
     */
    private static void openStandardIdentifierDocHover(ISourceViewer viewer, int offset)
    {
        if (viewer == null)
            return;
        int useOffset = resolveDocHoverOffset(viewer, offset);
        if (useOffset < 0)
            return;
        IdentifierDocHoverOpen prepared = prepareIdentifierDocHover(viewer, useOffset);
        if (prepared == null)
        {
            Global.tempLog("ann-doc", "prepare empty offset=" + useOffset); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        closeAnnotationHover(viewer);
        Display display = Display.getCurrent();
        if (display == null)
            display = Display.getDefault();
        if (display == null)
            return;
        final int loggedOffset = useOffset;
        display.asyncExec(() ->
        {
            Global.tempLog("ann-doc", "show offset=" + loggedOffset); //$NON-NLS-1$ //$NON-NLS-2$
            showPreparedIdentifierDocHover(viewer, prepared);
        });
    }

    /** Offset из AnnotationInfo исходного popup, иначе из кнопки toolbar. */
    private static int resolveDocHoverOffset(ISourceViewer viewer, int fallbackOffset)
    {
        try
        {
            HoverControlRef hover = findHoverControlRef(viewer, false);
            if (hover != null)
            {
                Object info = Global.getField(hover.control, "fInput"); //$NON-NLS-1$
                if (info == null && hover.manager != null)
                    info = Global.getField(hover.manager, "fInformation"); //$NON-NLS-1$
                if (isAnnotationInfo(info))
                {
                    Object pos = Global.getField(info, "position"); //$NON-NLS-1$
                    if (pos instanceof Position p && !p.isDeleted)
                        return p.getOffset();
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return fallbackOffset;
    }

    private static void showPreparedIdentifierDocHover(ISourceViewer viewer,
        IdentifierDocHoverOpen prepared)
    {
        if (viewer == null || prepared == null)
            return;
        try
        {
            StyledText widget = viewer.getTextWidget();
            if (widget == null || widget.isDisposed())
                return;
            Rectangle area = subjectAreaForRegion(viewer, prepared.region);
            if (area == null)
            {
                Global.tempLog("ann-doc", "no subject area"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            disposeIdentifierDocPresenter();
            // Иначе штатный mouse-hover открывает второй попап поверх нашего.
            suppressEditorTextHovers(viewer);
            IdentifierDocHoverPresenter presenter =
                new IdentifierDocHoverPresenter(prepared.creator, viewer);
            Object srcMgr = Global.getField(viewer, "fTextHoverManager"); //$NON-NLS-1$
            copyHoverSizeConstraints(srcMgr, presenter);
            presenter.install(widget);
            presenter.show(prepared.info, area);
            identifierDocPresenter = presenter;
            Global.tempLog("ann-doc", "presented via IdentifierDocHoverPresenter"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            restoreEditorTextHovers(viewer);
            Global.tempLog("ann-doc", "show fail: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void suppressEditorTextHovers(ISourceViewer viewer)
    {
        if (viewer == null)
            return;
        for (String field : new String[] { "fTextHoverManager", "fInformationPresenter" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Object mgr = Global.getField(viewer, field);
            if (!(mgr instanceof AbstractInformationControlManager aim))
                continue;
            try
            {
                aim.disposeInformationControl();
                aim.setEnabled(false);
            }
            catch (Exception ignored)
            {
            }
        }
    }

    private static void restoreEditorTextHovers(ISourceViewer viewer)
    {
        if (viewer == null)
            return;
        for (String field : new String[] { "fTextHoverManager", "fInformationPresenter" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Object mgr = Global.getField(viewer, field);
            if (!(mgr instanceof AbstractInformationControlManager aim))
                continue;
            try
            {
                aim.setEnabled(true);
            }
            catch (Exception ignored)
            {
            }
        }
    }

    private static void copyHoverSizeConstraints(Object fromManager,
        AbstractInformationControlManager to)
    {
        if (fromManager == null || to == null)
            return;
        try
        {
            Object w = Global.getField(fromManager, "fWidthConstraint"); //$NON-NLS-1$
            Object h = Global.getField(fromManager, "fHeightConstraint"); //$NON-NLS-1$
            Object min = Global.getField(fromManager, "fEnforceAsMinimalSize"); //$NON-NLS-1$
            Object max = Global.getField(fromManager, "fEnforceAsMaximalSize"); //$NON-NLS-1$
            if (w instanceof Integer width && h instanceof Integer height)
            {
                to.setSizeConstraints(width, height,
                    Boolean.TRUE.equals(min), Boolean.TRUE.equals(max));
            }
            Object mx = Global.getField(fromManager, "fMarginX"); //$NON-NLS-1$
            Object my = Global.getField(fromManager, "fMarginY"); //$NON-NLS-1$
            if (mx instanceof Integer marginX && my instanceof Integer marginY)
                to.setMargins(marginX, marginY);
        }
        catch (Exception ignored)
        {
        }
    }

    private static void disposeIdentifierDocPresenter()
    {
        IdentifierDocHoverPresenter prev = identifierDocPresenter;
        identifierDocPresenter = null;
        if (prev == null)
            return;
        ISourceViewer viewer = prev.viewer;
        try
        {
            prev.disposeInformationControl();
            prev.dispose();
        }
        catch (Exception ignored)
        {
        }
        restoreEditorTextHovers(viewer);
    }

    /**
     * Presenter описания идентификатора: constraints как у editor hover,
     * без пересчёта по текущей мыши. Closer — клик снаружи / Esc
     * ({@link AbstractInformationControlManager} сам closer не ставит).
     */
    private static final class IdentifierDocHoverPresenter extends AbstractInformationControlManager
    {
        private final ISourceViewer viewer;
        private Object pendingInfo;
        private Rectangle pendingArea;

        IdentifierDocHoverPresenter(IInformationControlCreator creator, ISourceViewer viewer)
        {
            super(creator);
            this.viewer = viewer;
            setCloser(new DocHoverCloser());
        }

        void show(Object info, Rectangle subjectArea)
        {
            pendingInfo = info;
            pendingArea = subjectArea;
            setInformation(info, subjectArea);
        }

        @Override
        protected void computeInformation()
        {
            if (pendingInfo != null && pendingArea != null)
                setInformation(pendingInfo, pendingArea);
            else
                setInformation((Object) null, null);
        }

        @Override
        protected void hideInformationControl()
        {
            pendingInfo = null;
            pendingArea = null;
            super.hideInformationControl();
            if (identifierDocPresenter == this)
                identifierDocPresenter = null;
            restoreEditorTextHovers(viewer);
        }

        private final class DocHoverCloser
            implements IInformationControlCloser, Listener
        {
            private Control subjectControl;
            private IInformationControl informationControl;
            private Display display;
            private boolean active;

            @Override
            public void setSubjectControl(Control control)
            {
                subjectControl = control;
            }

            @Override
            public void setInformationControl(IInformationControl control)
            {
                informationControl = control;
            }

            @Override
            public void start(Rectangle subjectArea)
            {
                if (active)
                    return;
                active = true;
                display = subjectControl != null && !subjectControl.isDisposed()
                    ? subjectControl.getDisplay() : Display.getCurrent();
                if (display == null || display.isDisposed())
                    return;
                // Не закрывать тем же MouseDown, которым нажали кнопку toolbar.
                display.asyncExec(() ->
                {
                    if (!active || display.isDisposed())
                        return;
                    display.addFilter(SWT.MouseDown, this);
                    display.addFilter(SWT.KeyDown, this);
                });
            }

            @Override
            public void stop()
            {
                if (!active)
                    return;
                active = false;
                if (display == null || display.isDisposed())
                    return;
                display.removeFilter(SWT.MouseDown, this);
                display.removeFilter(SWT.KeyDown, this);
            }

            @Override
            public void handleEvent(org.eclipse.swt.widgets.Event event)
            {
                if (!active)
                    return;
                if (event.type == SWT.KeyDown && event.keyCode == SWT.ESC)
                {
                    hideInformationControl();
                    return;
                }
                if (event.type != SWT.MouseDown)
                    return;
                Shell shell = infoControlShell(informationControl);
                if (shell != null && !shell.isDisposed()
                    && event.widget instanceof Control clicked
                    && isControlUnderShell(clicked, shell))
                    return;
                hideInformationControl();
            }
        }
    }

    private static boolean isControlUnderShell(Control control, Shell shell)
    {
        for (Control c = control; c != null; c = c.getParent())
        {
            if (c == shell)
                return true;
        }
        return false;
    }

    /** Subject area в координатах text widget — как у {@code TextViewerHoverManager}. */
    private static Rectangle subjectAreaForRegion(ISourceViewer viewer, IRegion region)
    {
        if (viewer == null || region == null)
            return null;
        StyledText widget = viewer.getTextWidget();
        if (widget == null || widget.isDisposed())
            return null;
        int modelStart = region.getOffset();
        int modelEnd = modelStart + Math.max(region.getLength(), 1);
        int widgetStart = modelStart;
        int widgetEnd = modelEnd;
        if (viewer instanceof ITextViewerExtension5 ext5)
        {
            int mappedStart = ext5.modelOffset2WidgetOffset(modelStart);
            int mappedEnd = ext5.modelOffset2WidgetOffset(modelEnd);
            if (mappedStart >= 0)
                widgetStart = mappedStart;
            if (mappedEnd >= 0)
                widgetEnd = mappedEnd;
        }
        int charCount = widget.getCharCount();
        widgetStart = Math.max(0, Math.min(widgetStart, charCount));
        widgetEnd = Math.max(widgetStart + 1, Math.min(widgetEnd, charCount));
        try
        {
            Point p1 = widget.getLocationAtOffset(widgetStart);
            Point p2 = widget.getLocationAtOffset(widgetEnd);
            int lineH = widget.getLineHeight(widgetStart);
            return new Rectangle(p1.x, p1.y, Math.max(p2.x - p1.x, 1), lineH);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static void closeAnnotationHover(ISourceViewer viewer)
    {
        HoverControlRef hover = findHoverControlRef(viewer, false);
        if (hover == null)
            return;
        try
        {
            if (hover.control instanceof IInformationControl ic)
                ic.setVisible(false);
            if (hover.manager instanceof AbstractInformationControlManager mgr)
                mgr.disposeInformationControl();
            else if (hover.manager != null)
                Global.invoke(hover.manager, "disposeInformationControl"); //$NON-NLS-1$
            Object replacer = hover.manager != null
                ? Global.getField(hover.manager, "fInformationControlReplacer") : null; //$NON-NLS-1$
            if (replacer != null)
                Global.invoke(replacer, "disposeInformationControl"); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
        }
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
        Global.tempLog("issue176", "applyProposalFilterToInfo: infoClass=" //$NON-NLS-1$ //$NON-NLS-2$
            + (info != null ? info.getClass().getName() : "null") + " isAnnotationInfo=" //$NON-NLS-1$ //$NON-NLS-2$
            + isAnnotationInfo(info));
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
            Global.tempLog("issue176", "AnnotationHoverProposalFilter.getHoverInfo2: delegateExt2=" //$NON-NLS-1$ //$NON-NLS-2$
                + delegateExt2);
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
            if (list == null || list.isEmpty())
            {
                AnnotationHoverNavState state = annotationHoverNav;
                if (state != null && !state.annotations.isEmpty())
                    list = state.annotations;
            }
            if (list == null)
                list = List.of();
            int idx = index;
            if (list.size() >= 2 && (idx < 0 || idx >= list.size()))
            {
                AnnotationHoverNavState state = annotationHoverNav;
                idx = state != null ? state.index : 0;
            }
            int offset = resolveAnnotationOffset(viewer, list);
            boolean hasSyntax = viewer != null && offset >= 0
                && hasIdentifierDocumentation(viewer, offset);
            if (list.size() < 2 && !hasSyntax)
            {
                removeAnnotationToolbarActions(manager);
                manager.update(true);
                return;
            }
            addAnnotationToolbarActions(manager, list, viewer, idx, offset, hasSyntax);
            manager.update(true);
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
