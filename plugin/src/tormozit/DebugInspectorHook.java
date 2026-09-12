package tormozit;

import org.eclipse.jface.internal.text.InformationControlReplacer;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextInputListener;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ContentAssistEvent;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.projection.IProjectionListener;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CaretEvent;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;

import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.jface.text.link.ILinkedModeListener;
import org.eclipse.jface.text.link.LinkedModeModel;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.ISaveablesSource;
import org.eclipse.ui.Saveable;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.embedded.EmbeddedEditor;
import org.eclipse.xtext.ui.editor.embedded.EmbeddedEditorFactory;
import org.eclipse.xtext.ui.editor.embedded.EmbeddedEditorModelAccess;
import org.eclipse.xtext.ui.editor.embedded.IEditedResourceProvider;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.debug.util.DebugNodeModelUtils;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IDebugMonitoringManager;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Патч окон инспектора отладки (F9 / hover): «Инспектировать» (hover), кнопка закрытия
 * и языковые подсказки в поле выражения отдельного окна.
 * Независимое окно (F9) закреплено без авто-закрытия по деактивации; hover — lifecycle у EDT.
 */
public final class DebugInspectorHook implements IStartup
{
    private static volatile boolean filtersInstalled;

    private static final String PATCHED_KEY = "tormozit.debugInspectorPatched"; //$NON-NLS-1$
    private static final String SESSION_KEY = "tormozit.debugInspectorSession"; //$NON-NLS-1$
    private static final String COMFORT_HEADER_KEY = "tormozit.inspectorComfortHeader"; //$NON-NLS-1$
    private static final String COMFORT_MENU_LAYOUT_KEY = "tormozit.inspectorMenuBarOriginalLayout"; //$NON-NLS-1$
    private static final String DETECT_LOG_KEY = "tormozit.debugInspectorDetectLog"; //$NON-NLS-1$
    static final String INSPECT_EXPRESSION_EDITOR_KEY = "tormozit.inspectExpressionEditor"; //$NON-NLS-1$
    static final String INSPECT_EXPRESSION_WRAP_PREFIX = "Строка("; //$NON-NLS-1$
    private static boolean inspectExpressionProposalApplied;

    static void markInspectExpressionProposalApplied(String replacement, Object proposal)
    {
        inspectExpressionProposalApplied = true;
        // Признак «вставлен вызов со скобками» нужен общему полю кода: по нему решается,
        // глотать ли Enter, чтобы не выпустить LinkedMode. Шаблоны туда не попадают.
        BslExpressionField.markProposalApplied(replacement, proposal);
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> inspectExpressionProposalApplied = false);
    }

    static boolean consumeInspectExpressionProposalApplied()
    {
        boolean applied = inspectExpressionProposalApplied;
        inspectExpressionProposalApplied = false;
        return applied;
    }

    /** Только UI-поток: читает {@code StyledText.getData}. */
    static boolean isInspectExpressionViewer(ITextViewer viewer)
    {
        if (viewer == null || Display.getCurrent() == null)
            return false;
        StyledText text = viewer.getTextWidget() instanceof StyledText st ? st : null;
        return text != null && !text.isDisposed()
            && Boolean.TRUE.equals(text.getData(INSPECT_EXPRESSION_EDITOR_KEY));
    }

    private static final String WINDOW_DATA_KEY = "org.eclipse.jface.window.Window"; //$NON-NLS-1$

    private static final String CLASS_INSPECT_POPUP =
        "com._1c.g5.v8.dt.internal.debug.ui.dialogs.PendingAwareInspectPopupDialog"; //$NON-NLS-1$
    private static final String CLASS_HOVER_DIALOG =
        "com._1c.g5.v8.dt.internal.debug.ui.hover.DebugElementInformationControlCreator$ExpressionInformationControl$DebugExpressionInformationControl"; //$NON-NLS-1$
    private static final String CLASS_DEBUG_ELEMENT_DIALOG =
        "com._1c.g5.v8.dt.internal.debug.ui.hover.DebugElementDialog"; //$NON-NLS-1$
    private static final String COLUMN_MARKER_RU = "Фактический тип"; //$NON-NLS-1$
    private static final String COLUMN_MARKER_EN = "Actual type"; //$NON-NLS-1$
    /** Подъём блока кнопок в hover-шапке. */
    private static final int HEADER_LIFT_PX = -6;
    /** Дополнительный подъём «Инспектировать» и × в hover. */
    private static final int HOVER_HEADER_LIFT_EXTRA_PX = -3;
    /** Опускание иконки «Инспектировать» относительно шапки hover. */
    private static final int INSPECT_BUTTON_DROP_PX = 4;

    @Override
    public void earlyStartup()
    {
        ensureInstalled();
    }

    public static void ensureInstalled()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (display.getThread() == Thread.currentThread())
            install(display);
        else
            display.asyncExec(() -> install(display));
    }

    /** Снять pin inspector shell на время modal find; вернуть restore для {@code try/finally}. */
    static Runnable suspendInspectorShellPin(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return () -> { };
        Object sessionObj = shell.getData(SESSION_KEY);
        if (sessionObj instanceof InspectorPatchSession session)
            return session.suspendShellPinForModal();
        return () -> { };
    }

    private static synchronized void install(Display display)
    {
        if (display == null || display.isDisposed() || filtersInstalled)
            return;
        filtersInstalled = true;

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell))
                return;
            if (shell.isDisposed())
                return;
            if (isWorkbenchShell(shell))
                return;
            if (!isInspectorCandidateShell(shell))
                return;
            logInspectorDetectOnce(shell, event.type);
            Object sessionObj = shell.getData(SESSION_KEY);
            if (sessionObj instanceof InspectorPatchSession session)
            {
                if (Boolean.TRUE.equals(shell.getData(PATCHED_KEY)))
                    scheduleSessionRefresh(display, shell, session);
                else
                    schedulePatchAttempt(display, shell, 0);
                return;
            }
            schedulePatchAttempt(display, shell, 0);
        };

        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
        DebugInspectorDebug.log("install Show/Activate filters"); //$NON-NLS-1$
    }

    private static void scheduleSessionRefresh(Display display, Shell shell, InspectorPatchSession session)
    {
        display.timerExec(0, () ->
        {
            if (!shell.isDisposed())
                session.refreshOrMaintain();
        });
    }

    private static void schedulePatchAttempt(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed())
            return;
        if (!mightBeInspectorShell(shell))
            return;
        // Show/Activate приходит из nested gtk_main_iteration_do внутри Shell.setVisible —
        // sync tryPatch/setVisible на GTK подвешивает open инспектора (Linux).
        // attempt 0: 1 ms, чтобы выйти из nested loop; дальше — как раньше.
        int delay = attempt == 0 ? 1 : (attempt < 8 ? 50 : 100);
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed())
                return;
            if (!mightBeInspectorShell(shell))
                return;
            if (!isInspectorCandidateShell(shell))
            {
                if (attempt < 24)
                    schedulePatchAttempt(display, shell, attempt + 1);
                return;
            }
            DebugInspectorDebug.step("patch", "try a=" + attempt); //$NON-NLS-1$ //$NON-NLS-2$
            if (tryPatch(shell, attempt))
                return;
            if (attempt < 24)
                schedulePatchAttempt(display, shell, attempt + 1);
            else
            {
                InspectorTargets failedTargets = resolveTargets(shell);
                traceResolveDiagnostics(shell, attempt, failedTargets, null, null);
                DebugInspectorDebug.problem("patch failed after retries shell=\"" //$NON-NLS-1$
                    + shell.getText() + "\" tree=" + hasInspectorTableMarker(shell)); //$NON-NLS-1$
            }
        });
    }

    private static boolean tryPatch(Shell shell, int attempt)
    {
        synchronized (shell)
        {
            return tryPatchLocked(shell, attempt);
        }
    }

    private static boolean tryPatchLocked(Shell shell, int attempt)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return true;
        if (!isInspectorCandidateShell(shell))
            return false;
        try
        {
            return tryPatchLockedImpl(shell, attempt);
        }
        catch (Exception e)
        {
            DebugInspectorDebug.problem("patch exception a=" + attempt + " " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : "")); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    private static boolean tryPatchLockedImpl(Shell shell, int attempt)
    {

        InspectorTargets targets = resolveTargets(shell);

        InspectorPatchSession existing = (InspectorPatchSession) shell.getData(SESSION_KEY);
        final InspectorPatchSession session;
        if (existing == null)
        {
            session = new InspectorPatchSession(shell, targets);
            shell.addDisposeListener(e -> session.dispose());
            shell.setData(SESSION_KEY, session);
            DebugInspectorDebug.step("session", "new a=" + attempt); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            session = existing;
            session.updateTargets(targets);
        }

        session.installTreeEnhancements();
        session.ensureHoverReplaceSuppressed();

        targets = resolveTargets(shell);
        session.updateTargets(targets);

        ToolBar menuBar = resolveToolBar(targets.dialog, shell);
        if (menuBar == null || menuBar.isDisposed())
        {
            traceResolveDiagnostics(shell, attempt, targets, menuBar, "miss=menuBar"); //$NON-NLS-1$
            return false;
        }

        if (!isPatchTarget(targets.dialog))
        {
            traceResolveDiagnostics(shell, attempt, targets, menuBar, "miss=dialog"); //$NON-NLS-1$
            return false;
        }

        traceResolveDiagnostics(shell, attempt, targets, menuBar, "pre-header"); //$NON-NLS-1$

        boolean headerOk = session.installHeaderControls(menuBar);
        session.scheduleHeaderMaintenance(menuBar);

        if (headerOk && session.tryFinalizePatch(menuBar, attempt))
            return true;

        if (headerOk)
        {
            DebugInspectorDebug.step("header", "defer finalize a=" + attempt); //$NON-NLS-1$ //$NON-NLS-2$
            session.scheduleFinalizePatch(menuBar, attempt);
            return false;
        }

        DebugInspectorDebug.step("header", //$NON-NLS-1$
            "miss a=" + attempt //$NON-NLS-1$
                + " headerOk=" + headerOk //$NON-NLS-1$
                + " installed=" + session.isHeaderInstalled() //$NON-NLS-1$
                + " menu=" + describeToolBar(menuBar)); //$NON-NLS-1$
        return false;
    }

    private static boolean mightBeInspectorShell(Shell shell)
    {
        if (isWorkbenchShell(shell))
            return false;
        return isInspectorCandidateShell(shell);
    }

    /** Shell popup/hover-инспектора EDT (для маршрутизации F2). */
    static boolean isInspectorShell(Shell shell)
    {
        return isInspectorCandidateShell(shell);
    }

    /** F9 / debug hover — не штатный doc-hover редактора (описание метода и т.п.). */
    private static boolean isInspectorCandidateShell(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return false;
        if (Boolean.TRUE.equals(shell.getData(PATCHED_KEY)))
            return true;
        if (isElementDialog(resolveElementDialog(shell, null)))
            return true;
        return hasInspectorTableMarker(shell);
    }

    private static boolean isWorkbenchShell(Shell shell)
    {
        Object layout = shell.getLayout();
        if (layout != null && layout.getClass().getName().contains("TrimmedPartLayout")) //$NON-NLS-1$
            return true;
        String title = shell.getText();
        return title != null && title.contains("Eclipse SDK"); //$NON-NLS-1$
    }

    private static InspectorTargets resolveTargets(Shell shell)
    {
        HoverBinding binding = findHoverBindingForShell(shell);
        Object infoControl = binding != null ? binding.infoControl() : null;
        Object dialog = resolveElementDialog(shell, infoControl);
        if (!isElementDialog(dialog))
            dialog = findElementDialogByShellMatch(shell);
        if (!isPatchTarget(dialog))
            dialog = findElementDialogByTreeShell(shell);
        if (!isPatchTarget(dialog))
            dialog = resolveHoverInspectProxy(shell, infoControl);
        if (!isPatchTarget(dialog) && isHoverInspectControl(infoControl) && hasInspectorTableMarker(shell))
            dialog = infoControl;
        return new InspectorTargets(dialog, infoControl);
    }

    /** Диалог с деревом и toolBar (DebugElementDialog / PendingAwareInspectPopupDialog). */
    private static Object resolveElementDialog(Shell shell, Object infoControl)
    {
        Object dialog = unwrapToElementDialog(shell.getData());
        if (isElementDialog(dialog))
            return dialog;
        dialog = unwrapToElementDialog(shell.getData(WINDOW_DATA_KEY));
        if (isElementDialog(dialog))
            return dialog;
        dialog = unwrapToElementDialog(infoControl);
        if (isElementDialog(dialog))
            return dialog;

        for (Shell walk = shell; walk != null && !walk.isDisposed(); walk = parentShellOf(walk))
        {
            dialog = unwrapToElementDialog(walk.getData(WINDOW_DATA_KEY));
            if (isElementDialog(dialog))
                return dialog;
            dialog = unwrapToElementDialog(walk.getData());
            if (isElementDialog(dialog))
                return dialog;
        }

        Tree tree = findTreeWithInspectorColumns(shell);
        if (tree != null && PlatformUI.isWorkbenchRunning())
        {
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            {
                for (IWorkbenchPage page : window.getPages())
                {
                    for (IEditorPart editor : page.getEditors())
                    {
                        dialog = dialogFromEditorTree(editor, tree);
                        if (isElementDialog(dialog))
                            return dialog;
                    }
                }
            }
        }
        Object hoverProxy = resolveHoverInspectProxy(shell, infoControl);
        if (hoverProxy != null)
            return hoverProxy;
        return null;
    }

    /** Hover-инспектор без {@code debugElementDialog}: UI в {@code ExpressionInformationControl}. */
    private static Object resolveHoverInspectProxy(Shell shell, Object infoControl)
    {
        if (!isHoverInspectControl(infoControl) || shell == null || shell.isDisposed())
            return null;
        if (!infoControlShellEquals(infoControl, shell))
            return null;
        if (!hasInspectorTableMarker(shell))
            return null;
        return infoControl;
    }

    private static boolean isHoverInspectControl(Object data)
    {
        if (data == null)
            return false;
        return data.getClass().getName().contains("ExpressionInformationControl"); //$NON-NLS-1$
    }

    private static boolean isPatchTarget(Object data)
    {
        return isElementDialog(data) || isHoverInspectControl(data);
    }

    private static Object unwrapToElementDialog(Object data)
    {
        if (data == null)
            return null;
        if (isElementDialog(data))
            return data;
        String name = data.getClass().getName();
        if (!name.contains("ExpressionInformationControl")) //$NON-NLS-1$
            return null;
        Object elementDialog = Global.getField(data, "debugElementDialog"); //$NON-NLS-1$
        if (isElementDialog(elementDialog))
            return elementDialog;
        elementDialog = Global.invoke(data, "getDebugElementDialog"); //$NON-NLS-1$
        return isElementDialog(elementDialog) ? elementDialog : null;
    }

    private static boolean isElementDialog(Object data)
    {
        if (data == null)
            return false;
        String name = data.getClass().getName();
        if (name.contains("ExpressionInformationControl")) //$NON-NLS-1$
            return false;
        return CLASS_INSPECT_POPUP.equals(name) || CLASS_DEBUG_ELEMENT_DIALOG.equals(name);
    }

    private static boolean isPopupInspectDialog(Object data)
    {
        return data != null && CLASS_INSPECT_POPUP.equals(data.getClass().getName());
    }

    /** {@link DebugElementDialog} внутри {@code ExpressionInformationControl}. */
    private static Object resolveElementDialogHost(Object data)
    {
        if (data == null)
            return null;
        if (isElementDialog(data))
            return data;
        if (!data.getClass().getName().contains("ExpressionInformationControl")) //$NON-NLS-1$
            return null;
        Object inner = Global.getField(data, "debugElementDialog"); //$NON-NLS-1$
        if (isElementDialog(inner))
            return inner;
        inner = Global.invoke(data, "getDebugElementDialog"); //$NON-NLS-1$
        return isElementDialog(inner) ? inner : null;
    }

    private static Object dialogFromEditorTree(IEditorPart editor, Tree tree)
    {
        ISourceViewer viewer = sourceViewer(editor);
        if (viewer == null)
            return null;
        Object hoverManager = Global.getField(viewer, "fTextHoverManager"); //$NON-NLS-1$
        if (hoverManager == null)
            return null;
        Object replacer = Global.getField(hoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
        Object ric = replacer != null ? Global.getField(replacer, "fInformationControl") : null; //$NON-NLS-1$
        Object ic = Global.getField(hoverManager, "fInformationControl"); //$NON-NLS-1$
        for (Object candidate : new Object[] { ic, ric })
        {
            Object dialog = unwrapToElementDialog(candidate);
            if (dialog != null && treeFromDialog(dialog) == tree)
                return dialog;
        }
        return null;
    }

    private static Tree treeFromDialog(Object dialog)
    {
        Object tree = Global.invoke(dialog, "getTree"); //$NON-NLS-1$
        if (tree instanceof Tree t && !t.isDisposed())
            return t;
        tree = Global.getField(dialog, "tree"); //$NON-NLS-1$
        return tree instanceof Tree t && !t.isDisposed() ? t : null;
    }

    private static ISourceViewer sourceViewer(IEditorPart editor)
    {
        if (editor instanceof BslXtextEditor bsl)
            return bsl.getInternalSourceViewer();
        if (editor instanceof DtGranularEditor<?> granular)
        {
            IFormPage page = granular.getActivePageInstance();
            if (page instanceof DtGranularEditorXtextEditorPage<?> xtext)
            {
                IEditorPart embedded = xtext.getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor bsl)
                    return bsl.getInternalSourceViewer();
            }
        }
        return null;
    }

    private static boolean isInspectorShellData(Object data)
    {
        if (data == null)
            return false;
        if (isElementDialog(data))
            return true;
        String name = data.getClass().getName();
        return CLASS_INSPECT_POPUP.equals(name) || CLASS_HOVER_DIALOG.equals(name)
            || CLASS_DEBUG_ELEMENT_DIALOG.equals(name);
    }

    private static ToolBar resolveToolBar(Object dialog, Shell shell)
    {
        if (isElementDialog(dialog))
        {
            ToolBar menuBar = (ToolBar) Global.getField(dialog, "toolBar"); //$NON-NLS-1$
            if (menuBar != null && !menuBar.isDisposed() && !isComfortHeader(menuBar))
                return menuBar;

            Object titleObj = Global.getField(dialog, "titleAreaComposite"); //$NON-NLS-1$
            if (titleObj instanceof Composite title && !title.isDisposed())
            {
                ToolBar inTitle = findToolBarInControls(title);
                if (inTitle != null)
                    return inTitle;
            }
        }

        if (shell != null && !shell.isDisposed())
        {
            Composite titleFromTree = resolveTitleAreaFromTree(shell);
            if (titleFromTree != null)
            {
                ToolBar inTitle = findToolBarInControls(titleFromTree);
                if (inTitle != null)
                    return inTitle;
            }
        }
        return null;
    }

    private static Object findElementDialogByShellMatch(Shell shell)
    {
        if (!PlatformUI.isWorkbenchRunning() || shell == null || shell.isDisposed())
            return null;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorPart editor : page.getEditors())
                {
                    Object dialog = findElementDialogInEditorShellMatch(editor, shell);
                    if (isElementDialog(dialog))
                        return dialog;
                }
            }
        }
        return null;
    }

    private static Object findElementDialogByTreeShell(Shell shell)
    {
        Tree tree = findTreeWithInspectorColumns(shell);
        if (tree == null || !PlatformUI.isWorkbenchRunning())
            return null;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorPart editor : page.getEditors())
                {
                    Object dialog = dialogFromEditorTree(editor, tree);
                    if (isElementDialog(dialog))
                        return dialog;
                }
            }
        }
        return null;
    }

    private static Object findElementDialogInEditorShellMatch(IEditorPart editor, Shell shell)
    {
        if (editor instanceof BslXtextEditor bsl)
            return findElementDialogInSourceViewerShellMatch(bsl.getInternalSourceViewer(), shell);
        if (editor instanceof DtGranularEditor<?> granular)
        {
            IFormPage activePage = granular.getActivePageInstance();
            if (activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
            {
                IEditorPart embedded = xtextPage.getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor bsl)
                    return findElementDialogInSourceViewerShellMatch(bsl.getInternalSourceViewer(), shell);
            }
        }
        return null;
    }

    private static Object findElementDialogInSourceViewerShellMatch(ISourceViewer viewer, Shell shell)
    {
        if (!(viewer instanceof SourceViewer sourceViewer))
            return null;
        Object textHoverManager = Global.getField(sourceViewer, "fTextHoverManager"); //$NON-NLS-1$
        if (textHoverManager == null)
            return null;

        Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
        if (replacer != null)
        {
            Object replacerControl = Global.getField(replacer, "fInformationControl"); //$NON-NLS-1$
            Object dialog = unwrapToElementDialog(replacerControl);
            if (dialogShellEquals(dialog, shell))
                return dialog;
        }

        Object infoControl = Global.getField(textHoverManager, "fInformationControl"); //$NON-NLS-1$
        Object dialog = unwrapToElementDialog(infoControl);
        if (dialogShellEquals(dialog, shell))
            return dialog;
        return null;
    }

    private static boolean dialogShellEquals(Object dialog, Shell shell)
    {
        if (!isElementDialog(dialog) || shell == null || shell.isDisposed())
            return false;
        Object dialogShell = Global.invoke(dialog, "getShell"); //$NON-NLS-1$
        return dialogShell == shell;
    }

    private static Shell parentShellOf(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;
        Composite parent = shell.getParent();
        while (parent != null && !(parent instanceof Shell))
            parent = parent.getParent();
        return parent instanceof Shell s ? s : null;
    }

    private static Composite resolveTitleAreaFromTree(Shell shell)
    {
        Tree tree = findTreeWithInspectorColumns(shell);
        if (tree == null)
            return null;
        Composite dialogArea = tree.getParent();
        if (dialogArea == null)
            return null;
        Composite main = dialogArea.getParent();
        if (main != null && !main.isDisposed())
        {
            for (Control child : main.getChildren())
            {
                if (child instanceof Composite composite && findToolBarInControls(composite) != null)
                    return composite;
            }
            ToolBar bar = findToolBarInControls(main);
            if (bar != null)
                return InspectorPatchSession.findTitleArea(bar);
        }
        for (Composite parent = dialogArea; parent != null && parent != (Composite) shell; parent = parent.getParent())
        {
            if (parent.getLayout() instanceof GridLayout)
            {
                ToolBar bar = findToolBarInControls(parent);
                if (bar != null)
                    return InspectorPatchSession.findTitleArea(bar);
            }
        }
        return null;
    }

    private static ToolBar findToolBarInControls(Control root)
    {
        if (root == null || root.isDisposed())
            return null;
        if (root instanceof ToolBar toolBar)
        {
            if (isComfortHeader(toolBar))
                return null;
            return toolBar;
        }
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                ToolBar found = findToolBarInControls(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static HoverBinding findHoverBindingForShell(Shell shell)
    {
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorPart editor : page.getEditors())
                {
                    HoverBinding binding = findHoverBindingInEditor(editor, shell);
                    if (binding != null)
                        return binding;
                }
            }
        }
        return null;
    }

    private static HoverBinding findHoverBindingInEditor(IEditorPart editor, Shell shell)
    {
        if (editor instanceof BslXtextEditor bsl)
            return findHoverBindingInSourceViewer(bsl.getInternalSourceViewer(), shell);
        if (editor instanceof DtGranularEditor<?> granular)
        {
            IFormPage activePage = granular.getActivePageInstance();
            if (activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
            {
                IEditorPart embedded = xtextPage.getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor bsl)
                    return findHoverBindingInSourceViewer(bsl.getInternalSourceViewer(), shell);
            }
        }
        return null;
    }

    private static HoverBinding findHoverBindingInSourceViewer(ISourceViewer viewer, Shell shell)
    {
        if (!(viewer instanceof SourceViewer sourceViewer))
            return null;
        Object textHoverManager = Global.getField(sourceViewer, "fTextHoverManager"); //$NON-NLS-1$
        if (textHoverManager == null)
            return null;

        Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
        Object dialogOnShell = resolveElementDialog(shell, null);

        if (replacer != null)
        {
            Object replacerControl = Global.getField(replacer, "fInformationControl"); //$NON-NLS-1$
            if (infoControlShellEquals(replacerControl, shell))
                return new HoverBinding(replacerControl);
        }

        Object infoControl = Global.getField(textHoverManager, "fInformationControl"); //$NON-NLS-1$
        if (infoControl == null)
            return null;

        boolean shellMatch = infoControlShellEquals(infoControl, shell);
        if (!shellMatch && dialogOnShell != null)
        {
            Object debugDialog = Global.getField(infoControl, "debugElementDialog"); //$NON-NLS-1$
            shellMatch = debugDialog == dialogOnShell;
        }
        if (!shellMatch)
            return null;

        if (isHoverInspectControl(infoControl) && !hasInspectorTableMarker(shell)
            && !Boolean.TRUE.equals(shell.getData(PATCHED_KEY)))
            return null;

        Object activeControl = infoControl;
        if (replacer != null)
        {
            Object replacerControl = Global.getField(replacer, "fInformationControl"); //$NON-NLS-1$
            if (replacerControl != null)
                activeControl = replacerControl;
        }

        return new HoverBinding(activeControl);
    }

    /** {@link org.eclipse.jface.text.AbstractInformationControlManager} hover/replacer для popup IC. */
    static Object resolveHoverInformationControlManager(Shell shell, Object infoControl)
    {
        if (shell == null || shell.isDisposed() || infoControl == null || !PlatformUI.isWorkbenchRunning())
            return null;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorPart editor : page.getEditors())
                {
                    ISourceViewer viewer = sourceViewer(editor);
                    if (viewer == null)
                        continue;
                    Object manager = resolveHoverManagerInSourceViewer(viewer, shell, infoControl);
                    if (manager != null)
                        return manager;
                }
            }
        }
        return null;
    }

    /** {@code fTextHoverManager} редактора, владеющий {@code fInformationControlReplacer}. */
    private static Object resolveDebugHoverTextManager(Shell shell, Object infoControl)
    {
        if (shell == null || shell.isDisposed() || infoControl == null || !PlatformUI.isWorkbenchRunning())
            return null;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorPart editor : page.getEditors())
                {
                    ISourceViewer viewer = sourceViewer(editor);
                    if (viewer == null)
                        continue;
                    Object manager = resolveTextHoverManagerInSourceViewer(viewer, shell, infoControl);
                    if (manager != null)
                        return manager;
                }
            }
        }
        return null;
    }

    private static Object resolveHoverManagerInSourceViewer(
        ISourceViewer viewer, Shell shell, Object infoControl)
    {
        if (!(viewer instanceof SourceViewer))
            return null;
        Object textHoverManager = Global.getField(viewer, "fTextHoverManager"); //$NON-NLS-1$
        if (textHoverManager == null)
            return null;
        Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
        if (hoverManagerHosts(replacer, shell, infoControl))
            return replacer;
        if (hoverManagerHosts(textHoverManager, shell, infoControl))
            return textHoverManager;
        return null;
    }

    private static Object resolveTextHoverManagerInSourceViewer(
        ISourceViewer viewer, Shell shell, Object infoControl)
    {
        if (!(viewer instanceof SourceViewer))
            return null;
        Object textHoverManager = Global.getField(viewer, "fTextHoverManager"); //$NON-NLS-1$
        if (textHoverManager == null)
            return null;
        Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
        if (hoverManagerHosts(replacer, shell, infoControl))
            return textHoverManager;
        if (hoverManagerHosts(textHoverManager, shell, infoControl))
            return textHoverManager;
        return null;
    }

    private static boolean hoverManagerHosts(Object manager, Shell shell, Object infoControl)
    {
        if (manager == null)
            return false;
        Object ic = Global.getField(manager, "fInformationControl"); //$NON-NLS-1$
        if (ic == null)
            return false;
        if (ic == infoControl)
            return true;
        return infoControlShellEquals(ic, shell);
    }

    private static IEditorPart findEditorForHoverShell(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorPart editor : page.getEditors())
                {
                    if (findHoverBindingInEditor(editor, shell) != null)
                        return resolveBslEditorFromPart(editor);
                }
            }
        }
        return null;
    }

    private static IEditorPart resolveBslEditorFromPart(IEditorPart editor)
    {
        if (editor instanceof BslXtextEditor bsl)
            return bsl;
        if (editor instanceof DtGranularEditor<?> granular)
        {
            IFormPage activePage = granular.getActivePageInstance();
            if (activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
            {
                IEditorPart embedded = xtextPage.getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor bsl)
                    return bsl;
            }
        }
        return editor;
    }

    private static Object resolveHoverMonitoringManager(Object infoControl)
    {
        if (infoControl != null)
        {
            Object mm = Global.getField(infoControl, "monitoringManager"); //$NON-NLS-1$
            if (mm != null)
                return mm;
        }
        return Global.getOsgiService(IDebugMonitoringManager.class);
    }

    private static IWatchExpression resolveHoverRootWatchExpression(Shell shell, Object infoControl)
    {
        Object element = resolveHoverDebugElement(infoControl);
        IWatchExpression watch = BslInspectSupport.toWatchExpression(element);
        if (watch != null)
            return watch;

        Tree tree = findTreeWithInspectorColumns(shell);
        if (tree == null)
            return null;
        TreeItem[] roots = tree.getItems();
        if (roots.length == 0)
            return null;
        return BslInspectSupport.toWatchExpression(roots[0].getData());
    }

    private static Object resolveHoverDebugElement(Object infoControl)
    {
        if (infoControl == null)
            return null;
        Object dialog = Global.getField(infoControl, "debugElementDialog"); //$NON-NLS-1$
        if (dialog == null)
            return null;
        try
        {
            Object element = Global.invoke(dialog, "getElement"); //$NON-NLS-1$
            if (element != null)
                return element;
        }
        catch (RuntimeException ignored)
        {
            // DebugElementDialog.checkContent — Show/setVisible до createContent
        }
        return Global.getField(dialog, "element"); //$NON-NLS-1$
    }

    private static String resolveHoverRootExpressionText(Shell shell, Object infoControl)
    {
        IWatchExpression watch = resolveHoverRootWatchExpression(shell, infoControl);
        String text = BslInspectSupport.watchExpressionText(watch);
        if (text != null && !text.isBlank())
            return text;

        Tree tree = findTreeWithInspectorColumns(shell);
        if (tree == null)
            return null;
        TreeItem[] roots = tree.getItems();
        if (roots.length == 0)
            return null;
        TreeItem root = roots[0];
        text = root.getText(0);
        if (text != null && !text.isBlank())
            return text.trim();
        return null;
    }

    private static void openStandaloneInspectFromHover(Shell hoverShell, IEditorPart editor, Object infoControl)
    {
        IWatchExpression watch = resolveHoverRootWatchExpression(hoverShell, infoControl);
        String exprText = BslInspectSupport.watchExpressionText(watch);
        if (exprText == null || exprText.isBlank())
            exprText = resolveHoverRootExpressionText(hoverShell, infoControl);
        if (watch == null && (exprText == null || exprText.isBlank()))
        {
            DebugInspectorDebug.problem("inspect: root expression not found"); //$NON-NLS-1$
            return;
        }
        if (watch == null)
            watch = BslInspectSupport.newWatchExpression(exprText);

        IBslStackFrame frame = BslInspectSupport.resolveInspectStackFrame(editor);
        if (frame == null)
        {
            DebugInspectorDebug.problem("inspect: no suspended frame"); //$NON-NLS-1$
            return;
        }

        Object monitoringManagerObj = resolveHoverMonitoringManager(infoControl);
        IDebugMonitoringManager monitoringManager = monitoringManagerObj instanceof IDebugMonitoringManager mm
            ? mm
            : Global.getOsgiService(IDebugMonitoringManager.class);
        Shell parent = hoverShell;
        if (editor != null && editor.getSite() != null)
            parent = editor.getSite().getShell();
        else if (parent == null || parent.isDisposed())
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            parent = window != null ? window.getShell() : null;
        }
        if (parent == null || parent.isDisposed())
        {
            DebugInspectorDebug.problem("inspect: no parent shell"); //$NON-NLS-1$
            return;
        }

        Point anchor = resolveInspectPopupAnchor(hoverShell, editor);
        BslInspectSupport.openInspectPopup(parent, anchor, watch, frame, monitoringManager);
    }

    private static Point resolveInspectPopupAnchor(Shell hoverShell, IEditorPart editor)
    {
        if (hoverShell != null && !hoverShell.isDisposed())
        {
            Rectangle bounds = hoverShell.getBounds();
            return new Point(bounds.x + bounds.width / 2, bounds.y + 20);
        }
        StyledText styledText = styledTextFromEditor(editor);
        if (styledText != null && !styledText.isDisposed())
        {
            Point range = styledText.getSelectionRange();
            int mid = range.x + range.y / 2;
            Point loc = styledText.getLocationAtOffset(mid);
            return styledText.toDisplay(loc);
        }
        return new Point(100, 100);
    }

    private static StyledText styledTextFromEditor(IEditorPart editor)
    {
        if (editor == null)
            return null;
        ITextViewer viewer = editor.getAdapter(ITextViewer.class);
        if (viewer != null)
            return viewer.getTextWidget();
        if (editor instanceof BslXtextEditor bsl)
            return bsl.getInternalSourceViewer().getTextWidget();
        return null;
    }

    private static boolean infoControlShellEquals(Object infoControl, Shell shell)
    {
        if (infoControl == null)
            return false;
        Object controlShell = Global.invoke(infoControl, "getShell"); //$NON-NLS-1$
        return controlShell == shell;
    }

    private static boolean hasInspectorTableMarker(Shell shell)
    {
        return findTreeWithInspectorColumns(shell) != null;
    }

    private static Tree findTreeWithInspectorColumns(Control root)
    {
        return DebugInspectorTreeEnhancement.findInspectorDebugTree(root);
    }

    private record HoverBinding(Object infoControl) {}

    private record InspectorTargets(Object dialog, Object infoControl) {}

    /** No-op replace: sticky-переход мыши сохраняется, shell/меню не пересоздаются. */
    private static final class DebugHoverInformationControlReplacer extends InformationControlReplacer
    {
        private static final String IC_FIELD = "fInformationControl"; //$NON-NLS-1$

        private final Object hoverManager;

        DebugHoverInformationControlReplacer(IInformationControlCreator creator, Object hoverManager)
        {
            super(creator);
            this.hoverManager = hoverManager;
        }

        @Override
        public void replaceInformationControl(
            IInformationControlCreator creator,
            Rectangle area,
            Object information,
            Rectangle bounds,
            boolean takesFocusWhenVisible)
        {
            Global.setField(this, "fIsReplacing", Boolean.TRUE); //$NON-NLS-1$
            Global.setField(this, "fReplaceableArea", bounds); //$NON-NLS-1$
            Global.setField(this, "fContentBounds", area); //$NON-NLS-1$
            Global.setField(this, "fReplacableInformation", information); //$NON-NLS-1$

            Object ic = hoverManager != null ? Global.getField(hoverManager, IC_FIELD) : null;
            if (hoverManager != null)
                Global.setField(hoverManager, IC_FIELD, null);

            DebugInspectorDebug.step("hover", "replace suppressed keepalive"); //$NON-NLS-1$ //$NON-NLS-2$

            if (ic == null || hoverManager == null)
                return;
            Display display = Display.getCurrent();
            if (display == null || display.isDisposed())
                return;
            final Object icFinal = ic;
            display.asyncExec(() ->
            {
                Object current = Global.getField(hoverManager, IC_FIELD);
                if (current == null)
                    Global.setField(hoverManager, IC_FIELD, icFinal);
                Global.setField(this, "fIsReplacing", Boolean.FALSE); //$NON-NLS-1$
            });
        }
    }

    /**
     * Подменяет {@code fInformationControlReplacer} на no-op-версию на время debug hover IC.
     * Обнуление replacer ломает переход мыши; штатный replace (~200 ms) пересоздаёт shell и закрывает меню.
     */
    private static final class HoverReplacerSuppressGuard
    {
        private static final String REPLACER_FIELD = "fInformationControlReplacer"; //$NON-NLS-1$
        private static final String CREATOR_FIELD = "fInformationControlCreator"; //$NON-NLS-1$

        private final Object hoverManager;
        private final Object savedReplacer;
        private final DebugHoverInformationControlReplacer suppressingReplacer;
        private boolean restored;

        static HoverReplacerSuppressGuard install(Shell shell, Object infoControl)
        {
            if (shell == null || shell.isDisposed() || !isHoverInspectControl(infoControl))
                return null;
            Object manager = resolveDebugHoverTextManager(shell, infoControl);
            if (manager == null)
                return null;
            Object saved = Global.getField(manager, REPLACER_FIELD);
            if (!(saved instanceof InformationControlReplacer))
                return null;
            Object creatorObj = Global.getField(saved, CREATOR_FIELD);
            if (!(creatorObj instanceof IInformationControlCreator creator))
                return null;
            DebugHoverInformationControlReplacer suppressing =
                new DebugHoverInformationControlReplacer(creator, manager);
            Global.setField(manager, REPLACER_FIELD, suppressing);
            cancelReplacingDelay(manager);
            DebugInspectorDebug.step("hover", "replacer suppress ON"); //$NON-NLS-1$ //$NON-NLS-2$
            return new HoverReplacerSuppressGuard(manager, saved, suppressing);
        }

        private HoverReplacerSuppressGuard(
            Object hoverManager, Object savedReplacer, DebugHoverInformationControlReplacer suppressingReplacer)
        {
            this.hoverManager = hoverManager;
            this.savedReplacer = savedReplacer;
            this.suppressingReplacer = suppressingReplacer;
        }

        void restore()
        {
            if (restored || hoverManager == null)
                return;
            restored = true;
            Object current = Global.getField(hoverManager, REPLACER_FIELD);
            if (current == suppressingReplacer && savedReplacer != null)
            {
                Global.setField(hoverManager, REPLACER_FIELD, savedReplacer);
                DebugInspectorDebug.step("hover", "replacer suppress OFF"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private static void cancelReplacingDelay(Object manager)
        {
            try
            {
                Global.invoke(manager, "cancelReplacingDelay"); //$NON-NLS-1$
            }
            catch (RuntimeException ignored)
            {
                // не AbstractHoverInformationControlManager
            }
        }
    }

    private static final class InspectorPatchSession
    {
        private final Shell shell;
        private InspectorTargets targets;

        private ToolBar leftInspectToolBar;
        private ToolBar closeToolBar;
        private ToolBar menuBarRef;
        private Composite titleAreaRef;
        private Listener keepDeactivateOffListener;
        private Listener headerMaintainListener;
        private Listener shellPinListener;
        private boolean hoverPinDisposeAllowed;
        private boolean shellPinnedOnTop;
        private boolean headerGuardInstalled;
        private DebugInspectorTreeEnhancement treeEnhancement;
        private HoverReplacerSuppressGuard hoverReplacerSuppressGuard;

        InspectorPatchSession(Shell shell, InspectorTargets targets)
        {
            this.shell = shell;
            this.targets = targets;
        }

        void updateTargets(InspectorTargets fresh)
        {
            if (fresh == null)
                return;
            Object dialog = fresh.dialog;
            if (!isPatchTarget(dialog))
                dialog = resolveElementDialog(shell, fresh.infoControl);
            if (isPatchTarget(dialog))
                targets = new InspectorTargets(dialog, fresh.infoControl);
        }

        void refreshOrMaintain()
        {
            if (shell.isDisposed())
                return;
            if (hoverPinDisposeAllowed)
                return;
            if (!Boolean.TRUE.equals(shell.getData(PATCHED_KEY)))
            {
                DebugInspectorDebug.step("hover", "patch aborted zombie shell=" + shell); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            {
                disposeTreeEnhancements();
                return;
            }
            updateTargets(resolveTargets(shell));
            if (shouldLightRefreshHover())
            {
                ToolBar menuBar = menuBarRef != null && !menuBarRef.isDisposed()
                    ? menuBarRef
                    : resolveToolBar(targets.dialog, shell);
                if (menuBar != null && !menuBar.isDisposed())
                    maintainHeaderControls(menuBar);
                return;
            }
            refresh();
        }

        void refresh()
        {
            if (shell.isDisposed())
                return;
            if (hoverPinDisposeAllowed)
                return;
            if (!Boolean.TRUE.equals(shell.getData(PATCHED_KEY)))
            {
                DebugInspectorDebug.step("hover", "patch aborted zombie shell=" + shell); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            {
                disposeTreeEnhancements();
                return;
            }
            updateTargets(resolveTargets(shell));
            ToolBar menuBar = resolveToolBar(targets.dialog, shell);
            if (!isPatchTarget(targets.dialog))
            {
                DebugInspectorDebug.step("refresh", "dialog=null → retry patch"); //$NON-NLS-1$
                schedulePatchAttempt(shell.getDisplay(), shell, 0);
                return;
            }
            if (menuBar == null || menuBar.isDisposed())
            {
                DebugInspectorDebug.step("refresh", "menuBar=null → invalidate"); //$NON-NLS-1$ //$NON-NLS-2$
                traceResolveDiagnostics(shell, -1, targets, menuBar, "refresh"); //$NON-NLS-1$
                invalidateSession();
                schedulePatchAttempt(shell.getDisplay(), shell, 0);
                return;
            }
            if (!installHeaderControls(menuBar))
            {
                DebugInspectorDebug.problem("refresh: header install failed"); //$NON-NLS-1$
                invalidateSession();
                schedulePatchAttempt(shell.getDisplay(), shell, 0);
                return;
            }
            if (!isHeaderInstalled())
            {
                DebugInspectorDebug.step("hover", "patch aborted zombie shell=" + shell); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            installTreeEnhancements();
            scheduleHeaderMaintenance(menuBar);
            applyInspectorModeForTargets();
            DebugInspectorDebug.step("refresh", "OK dialog=" + targets.dialog.getClass().getSimpleName() //$NON-NLS-1$ //$NON-NLS-2$
                + " menu=" + describeToolBar(menuBar));
        }

        void requestClose()
        {
            if (shell.isDisposed())
                return;
            hoverPinDisposeAllowed = true;
            restoreHoverReplacerSuppressGuard();
            removeKeepDeactivateOffListener();
            removeShellPinMaintenance();

            if (isHoverMode())
            {
                Object ic = targets.infoControl;
                if (isHoverInspectControl(ic))
                    Global.invoke(ic, "dispose"); //$NON-NLS-1$
                else if (!shell.isDisposed())
                    shell.dispose();
                return;
            }

            if (isElementDialog(targets.dialog))
                Global.invoke(targets.dialog, "close"); //$NON-NLS-1$
            else if (!shell.isDisposed())
                shell.dispose();
        }

        private void invalidateSession()
        {
            DebugInspectorDebug.step("session", "invalidate"); //$NON-NLS-1$ //$NON-NLS-2$
            clearPatchState();
            if (!shell.isDisposed())
                shell.setData(SESSION_KEY, null);
        }

        private void clearPatchState()
        {
            shell.setData(PATCHED_KEY, null);
            treeEnhancement = null;
        }

        void scheduleHeaderMaintenance(ToolBar menuBar)
        {
            if (shell.isDisposed() || menuBar.isDisposed())
                return;
            Display display = shell.getDisplay();
            for (int delay : new int[] { 50, 150, 400, 800 })
            {
                display.timerExec(delay, () ->
                {
                    if (!shell.isDisposed() && !menuBar.isDisposed())
                        maintainHeaderControls(menuBar);
                    if (!shell.isDisposed())
                        installTreeEnhancements();
                });
            }
        }

        boolean maintainHeaderControls(ToolBar menuBar)
        {
            if (menuBar == null || menuBar.isDisposed())
                return false;
            if (!isHeaderInstalled())
            {
                if (!installHeaderControls(menuBar))
                    return false;
                applyInspectorModeForTargets();
                return true;
            }

            Composite titleArea = findTitleArea(menuBar);
            if (titleArea == null || titleArea.isDisposed())
                return false;

            if (leftInspectToolBar != null && !leftInspectToolBar.isDisposed())
            {
                leftInspectToolBar.setVisible(true);
                if (leftInspectToolBar.getItemCount() > 0)
                    configureInspectToolItem(leftInspectToolBar.getItem(0));
            }
            if (closeToolBar != null && !closeToolBar.isDisposed())
                closeToolBar.setVisible(true);
            if (isHoverMode())
                layoutHoverHeader(titleArea, menuBar, menuBar.getData(COMFORT_MENU_LAYOUT_KEY));
            else
            {
                syncHeaderBackground(titleArea);
                titleArea.layout(true, true);
            }
            return true;
        }

        private void layoutHoverHeader(Composite titleArea, ToolBar menuBar, Object menuBarLayout)
        {
            if (!isHoverMode() || titleArea == null || titleArea.isDisposed()
                || menuBar == null || menuBar.isDisposed())
                return;

            if (leftInspectToolBar != null && !leftInspectToolBar.isDisposed()
                && menuBar.getParent() == titleArea)
                leftInspectToolBar.moveAbove(menuBar);

            applyMenuBarLeftGridData(menuBar, menuBarLayout);

            if (closeToolBar != null && !closeToolBar.isDisposed()
                && menuBar.getParent() == titleArea)
            {
                closeToolBar.moveBelow(menuBar);
                applyCloseButtonGridData(closeToolBar, menuBar, true);
            }

            applyTitleAreaLeftGridData(titleArea);

            if (titleArea.getLayout() instanceof GridLayout titleGrid)
                titleGrid.numColumns = Math.max(titleGrid.numColumns, 4);

            syncHeaderBackground(titleArea);
            titleArea.layout(true, true);
            Composite headerRow = titleArea.getParent();
            if (headerRow instanceof Composite row && !row.isDisposed()
                && row != titleArea && row.getLayout() instanceof GridLayout)
                row.layout(true, true);
        }

        boolean tryFinalizePatch(ToolBar menuBar, int attempt)
        {
            if (shell.isDisposed() || menuBar == null || menuBar.isDisposed())
                return false;
            if (!isHeaderInstalled())
                return false;
            if (Boolean.TRUE.equals(shell.getData(PATCHED_KEY)))
                return true;
            shell.setData(PATCHED_KEY, Boolean.TRUE);
            ensureHoverReplaceSuppressed();
            applyInspectorModeForTargets();
            InspectExpressionAssist.install(targets.dialog);
            maintainHeaderControls(menuBar);
            // Не вызывать shell.setVisible: на GTK nested setVisible из Show/finalize
            // блокирует DebugPopup.open (gtk_main_iteration_do). Видимость — у EDT open.
            if (!shell.isDisposed())
                shell.layout(true, true);
            DebugInspectorDebug.step("PATCH OK", //$NON-NLS-1$
                "a=" + attempt //$NON-NLS-1$
                    + " dialog=" + targets.dialog.getClass().getSimpleName() //$NON-NLS-1$
                    + " hover=" + (targets.infoControl != null) //$NON-NLS-1$
                    + " menu=" + describeToolBar(menuBar)); //$NON-NLS-1$
            return true;
        }

        void scheduleFinalizePatch(ToolBar menuBar, int attempt)
        {
            if (shell.isDisposed())
                return;
            Display display = shell.getDisplay();
            if (display == null || display.isDisposed())
                return;
            display.timerExec(0, () ->
            {
                if (shell.isDisposed() || Boolean.TRUE.equals(shell.getData(PATCHED_KEY)))
                    return;
                synchronized (shell)
                {
                    InspectorTargets latest = resolveTargets(shell);
                    updateTargets(latest);
                    ToolBar bar = resolveToolBar(targets.dialog, shell);
                    if (bar != null && !bar.isDisposed() && tryFinalizePatch(bar, attempt))
                        return;
                    schedulePatchAttempt(display, shell, attempt + 1);
                }
            });
        }

        private boolean isHeaderInstalled()
        {
            if (closeToolBar == null || closeToolBar.isDisposed())
                return false;
            if (!isHoverMode())
                return true;
            return leftInspectToolBar != null && !leftInspectToolBar.isDisposed()
                && leftInspectToolBar.getItemCount() > 0;
        }

        boolean installHeaderControls(ToolBar menuBar)
        {
            if (isHeaderInstalled() && menuBarRef == menuBar)
                return maintainHeaderControls(menuBar);

            Composite titleArea = findTitleArea(menuBar);
            if (titleArea == null || titleArea.isDisposed())
            {
                DebugInspectorDebug.problem("installHeader: titleArea not found"); //$NON-NLS-1$
                return false;
            }

            ToolBar oldMenuBarRef = menuBarRef;
            Composite oldTitleAreaRef = titleAreaRef;

            DebugInspectorDebug.step("header", "install start menu=" + describeToolBar(menuBar)); //$NON-NLS-1$ //$NON-NLS-2$
            removeOrphanComfortControls(titleArea, menuBar);

            Color titleBg = titleArea.getBackground();
            Object menuBarLayout = menuBar.getLayoutData();

            try
            {
                boolean ok = installHeaderControlsInTitleArea(titleArea, menuBar, titleBg, menuBarLayout);
                if (ok && oldMenuBarRef != null && oldMenuBarRef != menuBar
                    && oldTitleAreaRef != null && oldTitleAreaRef != titleArea
                    && !oldTitleAreaRef.isDisposed())
                {
                    DebugInspectorDebug.step("header", //$NON-NLS-1$
                        "menuBar changed old=" + describeToolBar(oldMenuBarRef) //$NON-NLS-1$
                            + " new=" + describeToolBar(menuBar)); //$NON-NLS-1$
                    removeOrphanComfortControls(oldTitleAreaRef, oldMenuBarRef);
                }
                return ok;
            }
            catch (RuntimeException e)
            {
                DebugInspectorDebug.problem("installHeader: " + e.getMessage()); //$NON-NLS-1$
                return false;
            }
        }

        private boolean installHeaderControlsInTitleArea(
            Composite titleArea, ToolBar menuBar, Color titleBg, Object menuBarLayout)
        {
            if (isHoverMode())
            {
                ensureMenuBarInParent(titleArea, menuBar, menuBarLayout);

                leftInspectToolBar = new ToolBar(titleArea, SWT.FLAT | SWT.LEFT);
                markComfortHeader(leftInspectToolBar);
                leftInspectToolBar.setBackground(titleBg);
                leftInspectToolBar.setLayoutData(leftInspectToolBarGridData());
                ToolItem inspectItem = new ToolItem(leftInspectToolBar, SWT.PUSH);
                Image inspectImage = BslInspectSupport.loadInspectCommandImage();
                if (inspectImage != null)
                {
                    inspectItem.setImage(inspectImage);
                    leftInspectToolBar.addDisposeListener(e -> inspectImage.dispose());
                }
                else
                    inspectItem.setText("Инспектировать"); //$NON-NLS-1$
                configureInspectToolItem(inspectItem);
                inspectItem.addListener(SWT.Selection, e -> runInspectFromHover());

                closeToolBar = new ToolBar(titleArea, SWT.FLAT | SWT.RIGHT);
                markComfortHeader(closeToolBar);
                closeToolBar.setBackground(titleBg);
                closeToolBar.setLayoutData(closeButtonGridData(true));
                ToolItem closeItem = new ToolItem(closeToolBar, SWT.PUSH);
                closeItem.setText("✕"); //$NON-NLS-1$
                closeItem.setToolTipText(
                    "Закрыть окно инспектора" //$NON-NLS-1$
                        + Global.pluginSignForTooltip());
                closeItem.addListener(SWT.Selection, e -> requestClose());

                layoutHoverHeader(titleArea, menuBar, menuBarLayout);
            }
            else
            {
                ensureMenuBarInParent(titleArea, menuBar, menuBarLayout);

                closeToolBar = new ToolBar(titleArea, SWT.FLAT | SWT.RIGHT);
                markComfortHeader(closeToolBar);
                closeToolBar.setBackground(titleBg);
                closeToolBar.setLayoutData(closeButtonGridData(false));
                ToolItem closeItem = new ToolItem(closeToolBar, SWT.PUSH);
                closeItem.setText("✕"); //$NON-NLS-1$
                closeItem.setToolTipText(
                    "Закрыть окно инспектора" //$NON-NLS-1$
                        + Global.pluginSignForTooltip());
                closeItem.addListener(SWT.Selection, e -> requestClose());

                if (menuBar.getParent() == titleArea)
                    closeToolBar.moveBelow(menuBar);

                if (titleArea.getLayout() instanceof GridLayout gridLayout)
                    gridLayout.numColumns = Math.max(gridLayout.numColumns, 4);
            }

            menuBarRef = menuBar;
            if (leftInspectToolBar != null && !leftInspectToolBar.isDisposed())
                leftInspectToolBar.setVisible(true);
            if (closeToolBar != null && !closeToolBar.isDisposed())
                closeToolBar.setVisible(true);
            titleAreaRef = titleArea;
            installHeaderGuard(titleArea);
            titleArea.layout(true, true);
            installTreeEnhancements();
            DebugInspectorDebug.step("header", //$NON-NLS-1$
                "installed left=" + describeToolBar(leftInspectToolBar) //$NON-NLS-1$
                    + " menu=" + describeToolBar(menuBar)); //$NON-NLS-1$
            return true;
        }

        void installTreeEnhancements()
        {
            if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            {
                disposeTreeEnhancements();
                return;
            }
            InspectExpressionAssist.install(targets.dialog);
            if (treeEnhancement != null && treeEnhancement.isAttached())
                return;
            treeEnhancement = DebugInspectorTreeEnhancement.install(targets.dialog, shell);
            DebugInspectorTreeEnhancement.hookInspectorPrefixTree(shell, targets.dialog);
            if (treeEnhancement == null)
                DebugInspectorDebug.step("tree", "install failed dialog=" //$NON-NLS-1$ //$NON-NLS-2$
                    + DebugInspectorDebug.cn(targets.dialog));
            else
                treeEnhancement.schedulePendingPropertyFocus();
        }

        private void disposeTreeEnhancements()
        {
            if (treeEnhancement != null)
            {
                treeEnhancement.dispose();
                treeEnhancement = null;
            }
        }

        private static void ensureMenuBarInParent(
            Composite parent, ToolBar menuBar, Object menuBarLayout)
        {
            if (menuBar == null || menuBar.isDisposed() || parent == null || parent.isDisposed())
                return;
            if (menuBar.getParent() == parent)
                return;
            Control oldParent = menuBar.getParent();
            if (oldParent instanceof Composite composite && isComfortHeader(composite))
            {
                menuBar.setParent(parent);
                if (menuBarLayout instanceof GridData gd)
                    menuBar.setLayoutData(gd);
            }
        }

        private static Composite findTitleArea(ToolBar menuBar)
        {
            for (Composite parent = menuBar.getParent(); parent != null; parent = parent.getParent())
            {
                if (parent.getLayout() instanceof GridLayout)
                    return parent;
            }
            return menuBar.getParent();
        }

        private void removeOrphanComfortControls(Composite titleArea, ToolBar menuBar)
        {
            leftInspectToolBar = null;
            closeToolBar = null;
            menuBarRef = null;
            int removed = removeComfortOrphansIn(titleArea, menuBar);
            if (removed > 0)
                DebugInspectorDebug.step("header", "removed orphans=" + removed); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private int removeComfortOrphansIn(Composite area, ToolBar menuBar)
        {
            if (area == null || area.isDisposed())
                return 0;
            int removed = 0;
            for (Control child : area.getChildren())
            {
                if (!isComfortHeader(child))
                    continue;
                if (child instanceof ToolBar bar && menuBar != null && bar == menuBar)
                    continue;
                removed++;
                if (child instanceof Composite composite && menuBar != null && !menuBar.isDisposed())
                {
                    for (Control grand : composite.getChildren())
                    {
                        if (grand == menuBar)
                        {
                            Object layoutData = menuBar.getLayoutData();
                            menuBar.setParent(area);
                            if (layoutData instanceof GridData gd)
                                menuBar.setLayoutData(gd);
                            break;
                        }
                    }
                }
                child.dispose();
            }
            return removed;
        }

        private void syncHeaderBackground(Composite titleArea)
        {
            if (titleArea == null || titleArea.isDisposed())
                return;
            Color titleBg = titleArea.getBackground();
            if (leftInspectToolBar != null && !leftInspectToolBar.isDisposed())
                leftInspectToolBar.setBackground(titleBg);
            if (closeToolBar != null && !closeToolBar.isDisposed())
                closeToolBar.setBackground(titleBg);
        }

        private void installHeaderGuard(Composite titleArea)
        {
            if (headerGuardInstalled)
                return;
            headerGuardInstalled = true;
            headerMaintainListener = e ->
            {
                if (shell.isDisposed())
                    return;
                ToolBar menuBar = resolveToolBar(targets.dialog, shell);
                if (menuBar != null && !menuBar.isDisposed())
                    maintainHeaderControls(menuBar);
            };
            titleArea.addListener(SWT.Resize, headerMaintainListener);
            shell.addListener(SWT.Resize, headerMaintainListener);
            shell.addListener(SWT.Move, headerMaintainListener);
            shell.addListener(SWT.MouseUp, headerMaintainListener);
        }

        private void removeHeaderGuard()
        {
            if (!headerGuardInstalled)
                return;
            if (titleAreaRef != null && !titleAreaRef.isDisposed() && headerMaintainListener != null)
                titleAreaRef.removeListener(SWT.Resize, headerMaintainListener);
            if (!shell.isDisposed() && headerMaintainListener != null)
            {
                shell.removeListener(SWT.Resize, headerMaintainListener);
                shell.removeListener(SWT.Move, headerMaintainListener);
                shell.removeListener(SWT.MouseUp, headerMaintainListener);
            }
            headerGuardInstalled = false;
            headerMaintainListener = null;
        }

        private boolean isDebugHoverInspectContext()
        {
            if (!isHoverMode() || isPopupInspectDialog(targets != null ? targets.dialog : null))
                return false;
            Object infoControl = targets != null ? targets.infoControl : null;
            if (isHoverInspectControl(infoControl))
                return true;
            return isHoverInspectControl(targets != null ? targets.dialog : null);
        }

        void ensureHoverReplaceSuppressed()
        {
            if (hoverReplacerSuppressGuard != null || !ComfortSettings.isImproveDebuggerWindowsEnabled())
                return;
            if (!isDebugHoverInspectContext())
                return;
            Object infoControl = targets != null ? targets.infoControl : null;
            if (!isHoverInspectControl(infoControl) && targets != null)
                infoControl = isHoverInspectControl(targets.dialog) ? targets.dialog : infoControl;
            if (!isHoverInspectControl(infoControl))
            {
                InspectorTargets latest = resolveTargets(shell);
                infoControl = latest.infoControl;
                if (!isHoverInspectControl(infoControl) && isHoverInspectControl(latest.dialog))
                    infoControl = latest.dialog;
            }
            if (!isHoverInspectControl(infoControl))
                return;
            hoverReplacerSuppressGuard = HoverReplacerSuppressGuard.install(shell, infoControl);
        }

        private void restoreHoverReplacerSuppressGuard()
        {
            if (hoverReplacerSuppressGuard == null)
                return;
            hoverReplacerSuppressGuard.restore();
            hoverReplacerSuppressGuard = null;
        }

        private boolean shouldLightRefreshHover()
        {
            if (!isHoverMode() || !isHeaderInstalled())
                return false;
            if (isPopupInspectDialog(targets != null ? targets.dialog : null))
                return false;
            Object infoControl = targets != null ? targets.infoControl : null;
            if (isHoverInspectControl(infoControl))
                return true;
            return isHoverInspectControl(targets != null ? targets.dialog : null);
        }

        void applyInspectorModeForTargets()
        {
            if (shell.isDisposed())
                return;
            ensureHoverReplaceSuppressed();
            boolean popupInspect = isPopupInspectDialog(targets != null ? targets.dialog : null);
            Object deactivateHost = resolveElementDialogHost(targets != null ? targets.dialog : null);
            if (isHoverMode() && !popupInspect && deactivateHost == null)
                return;
            if (!isPatchTarget(targets.dialog))
            {
                Object dialog = resolveElementDialog(shell, targets.infoControl);
                if (isPatchTarget(dialog))
                    targets = new InspectorTargets(dialog, targets.infoControl);
            }
            if (!isPatchTarget(targets.dialog))
            {
                DebugInspectorDebug.step("inspector", "skip dialog=null"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            deactivateHost = resolveElementDialogHost(targets.dialog);
            if (deactivateHost != null)
            {
                Global.setField(deactivateHost, "listenToDeactivate", Boolean.FALSE); //$NON-NLS-1$
                Global.setField(deactivateHost, "listenToParentDeactivate", Boolean.FALSE); //$NON-NLS-1$
                installKeepDeactivateOffListener();
            }
            else if (isElementDialog(targets.dialog))
            {
                Global.setField(targets.dialog, "listenToDeactivate", Boolean.FALSE); //$NON-NLS-1$
                Global.setField(targets.dialog, "listenToParentDeactivate", Boolean.FALSE); //$NON-NLS-1$
                installKeepDeactivateOffListener();
            }
            if (!isHoverMode())
            {
                restoreShellOnTop(true);
                DebugInspectorDebug.step("standalone", "close OFF"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if (popupInspect || deactivateHost != null)
                DebugInspectorDebug.step("popup", "close OFF"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** Временно снять pin shell на время modal find (z-order InputDialog). */
        Runnable suspendShellPinForModal()
        {
            boolean wasPinned = shellPinnedOnTop;
            if (wasPinned)
                restoreShellOnTop(false);
            Shell pinnedShell = shell;
            return () ->
            {
                if (wasPinned && pinnedShell != null && !pinnedShell.isDisposed())
                    restoreShellOnTop(true);
            };
        }

        private void configureInspectToolItem(ToolItem inspectItem)
        {
            if (inspectItem == null || inspectItem.isDisposed() || !isHoverMode())
                return;
            if (inspectItem.getImage() == null)
            {
                Image img = BslInspectSupport.loadInspectCommandImage();
                if (img != null)
                {
                    inspectItem.setImage(img);
                    if (leftInspectToolBar != null && !leftInspectToolBar.isDisposed())
                        leftInspectToolBar.addDisposeListener(e -> img.dispose());
                }
                else
                    inspectItem.setText("Инспектировать"); //$NON-NLS-1$
            }
            inspectItem.setToolTipText(
                "Открыть инспектор с выражением из hover-окна" //$NON-NLS-1$
                    + Global.pluginSignForTooltip());
            inspectItem.setEnabled(true);
        }

        private void runInspectFromHover()
        {
            IEditorPart editor = findEditorForHoverShell(shell);
            if (editor == null)
            {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window != null && window.getActivePage() != null)
                    editor = resolveBslEditorFromPart(window.getActivePage().getActiveEditor());
            }
            Object infoControl = targets != null ? targets.infoControl : null;
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            IEditorPart editorFinal = editor;
            display.asyncExec(() -> openStandaloneInspectFromHover(shell, editorFinal, infoControl));
        }

        private boolean isHoverMode()
        {
            return isHoverInspectControl(targets.dialog) || targets.infoControl != null;
        }

        private void installKeepDeactivateOffListener()
        {
            if (keepDeactivateOffListener != null)
                return;
            keepDeactivateOffListener = e ->
            {
                if (e.widget != shell || shell.isDisposed())
                    return;
                Object host = resolveElementDialogHost(targets.dialog);
                if (host != null)
                {
                    Global.setField(host, "listenToDeactivate", Boolean.FALSE); //$NON-NLS-1$
                    Global.setField(host, "listenToParentDeactivate", Boolean.FALSE); //$NON-NLS-1$
                }
                else if (isElementDialog(targets.dialog))
                {
                    Global.setField(targets.dialog, "listenToDeactivate", Boolean.FALSE); //$NON-NLS-1$
                    Global.setField(targets.dialog, "listenToParentDeactivate", Boolean.FALSE); //$NON-NLS-1$
                }
            };
            shell.addListener(SWT.Activate, keepDeactivateOffListener);
            shell.addListener(SWT.Deactivate, keepDeactivateOffListener);
        }

        private void removeKeepDeactivateOffListener()
        {
            if (keepDeactivateOffListener == null || shell.isDisposed())
                return;
            shell.removeListener(SWT.Activate, keepDeactivateOffListener);
            shell.removeListener(SWT.Deactivate, keepDeactivateOffListener);
            keepDeactivateOffListener = null;
        }

        private void restoreShellOnTop(boolean pinOnTop)
        {
            shellPinnedOnTop = pinOnTop;
            if (pinOnTop)
            {
                boolean firstPinMaintenance = shellPinListener == null;
                applyShellPinNow();
                installShellPinMaintenance();
                if (firstPinMaintenance)
                    scheduleShellPinRetries();
            }
            else
            {
                removeShellPinMaintenance();
                WinWindowActivator.setShellAboveOwner(shell, null, false);
            }
        }

        private void applyShellPinNow()
        {
            if (shell.isDisposed() || !shellPinnedOnTop)
                return;
            WinWindowActivator.clearShellTopmost(shell);
            WinWindowActivator.setShellAboveOwner(shell, resolveOwnerShell(), true);
        }

        private void installShellPinMaintenance()
        {
            if (shellPinListener != null)
                return;
            shellPinListener = e ->
            {
                if (!shell.isDisposed() && shellPinnedOnTop)
                    applyShellPinNow();
            };
            shell.addListener(SWT.Show, shellPinListener);
            shell.addListener(SWT.Activate, shellPinListener);
        }

        private void scheduleShellPinRetries()
        {
            Display display = shell.getDisplay();
            for (int delay : new int[] { 0, 50, 150, 400, 800 })
            {
                display.timerExec(delay, () ->
                {
                    if (!shell.isDisposed() && shellPinnedOnTop)
                        applyShellPinNow();
                });
            }
        }

        private void removeShellPinMaintenance()
        {
            if (shellPinListener == null || shell.isDisposed())
                return;
            shell.removeListener(SWT.Show, shellPinListener);
            shell.removeListener(SWT.Activate, shellPinListener);
            shellPinListener = null;
        }

        private Shell resolveOwnerShell()
        {
            try
            {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window == null)
                {
                    IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
                    if (windows != null && windows.length > 0)
                        window = windows[0];
                }
                if (window != null)
                {
                    Shell workbenchShell = window.getShell();
                    if (workbenchShell != null && !workbenchShell.isDisposed())
                        return workbenchShell;
                }
            }
            catch (RuntimeException ignored)
            {
                // workbench ещё не поднят
            }
            Shell active = shell.getDisplay().getActiveShell();
            if (active != null && !active.isDisposed() && active != shell)
                return active;
            return null;
        }

        void dispose()
        {
            restoreHoverReplacerSuppressGuard();
            removeHeaderGuard();
            removeKeepDeactivateOffListener();
            removeShellPinMaintenance();
            if (treeEnhancement != null)
            {
                treeEnhancement.dispose();
                treeEnhancement = null;
            }
            if (!shell.isDisposed())
            {
                shell.setData(PATCHED_KEY, null);
                shell.setData(SESSION_KEY, null);
            }
        }
    }

    private static void markComfortHeader(Control control)
    {
        control.setData(COMFORT_HEADER_KEY, Boolean.TRUE);
    }

    private static boolean isComfortHeader(Control control)
    {
        return control != null && !control.isDisposed()
            && Boolean.TRUE.equals(control.getData(COMFORT_HEADER_KEY));
    }

    private static GridData copyGridData(GridData src)
    {
        GridData gd = new GridData(
            src.horizontalAlignment, src.verticalAlignment,
            src.grabExcessHorizontalSpace, src.grabExcessVerticalSpace);
        gd.horizontalSpan = src.horizontalSpan;
        gd.verticalSpan = src.verticalSpan;
        gd.horizontalIndent = src.horizontalIndent;
        gd.verticalIndent = src.verticalIndent;
        gd.widthHint = src.widthHint;
        gd.heightHint = src.heightHint;
        gd.minimumWidth = src.minimumWidth;
        gd.minimumHeight = src.minimumHeight;
        return gd;
    }

    private static int resolveLeftEdgeIndent(Composite from)
    {
        if (from == null || from.isDisposed())
            return 0;
        int pull = 0;
        for (Composite walk = from; walk != null && !walk.isDisposed() && !(walk instanceof Shell);
            walk = walk.getParent())
        {
            if (walk.getLayout() instanceof GridLayout grid)
                pull -= grid.marginWidth;
        }
        return pull;
    }

    private static int computeToolBarContentWidth(ToolBar menuBar)
    {
        if (menuBar == null || menuBar.isDisposed())
            return SWT.DEFAULT;
        menuBar.pack();
        Point size = menuBar.getSize();
        if (size.x > 0)
            return size.x;
        int width = 0;
        for (ToolItem item : menuBar.getItems())
        {
            if (item.isDisposed())
                continue;
            Rectangle bounds = item.getBounds();
            if (bounds.width > 0)
                width += bounds.width;
        }
        return width > 0 ? width : SWT.DEFAULT;
    }

    private static void saveOriginalMenuBarLayout(ToolBar menuBar, Object menuBarLayout)
    {
        if (menuBar == null || menuBar.isDisposed() || menuBarLayout == null)
            return;
        if (menuBar.getData(COMFORT_MENU_LAYOUT_KEY) == null)
            menuBar.setData(COMFORT_MENU_LAYOUT_KEY, menuBarLayout);
    }

    private static void applyMenuBarLeftGridData(ToolBar menuBar, Object originalLayout)
    {
        if (menuBar == null || menuBar.isDisposed())
            return;
        Object layoutSource = originalLayout;
        if (layoutSource == null)
            layoutSource = menuBar.getData(COMFORT_MENU_LAYOUT_KEY);
        saveOriginalMenuBarLayout(menuBar, layoutSource);
        GridData gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        if (layoutSource instanceof GridData src)
            gd.verticalIndent = src.verticalIndent;
        else if (menuBar.getLayoutData() instanceof GridData current)
            gd.verticalIndent = current.verticalIndent;
        gd.widthHint = computeToolBarContentWidth(menuBar);
        menuBar.setLayoutData(gd);
    }

    private static void applyTitleAreaLeftGridData(Composite titleArea)
    {
        if (titleArea == null || titleArea.isDisposed())
            return;
        Composite parent = titleArea.getParent();
        if (!(parent instanceof Composite) || !(parent.getLayout() instanceof GridLayout))
            return;
        Object layoutData = titleArea.getLayoutData();
        GridData gd;
        if (layoutData instanceof GridData src)
            gd = copyGridData(src);
        else
            gd = new GridData(SWT.BEGINNING, SWT.CENTER, true, false);
        gd.horizontalAlignment = SWT.BEGINNING;
        gd.grabExcessHorizontalSpace = true;
        gd.horizontalIndent = resolveLeftEdgeIndent(titleArea);
        titleArea.setLayoutData(gd);
    }

    private static GridData leftInspectToolBarGridData()
    {
        GridData gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        gd.verticalIndent = HEADER_LIFT_PX + HOVER_HEADER_LIFT_EXTRA_PX + INSPECT_BUTTON_DROP_PX;
        return gd;
    }

    private static GridData closeButtonGridData(boolean hover)
    {
        return closeButtonGridData(null, hover);
    }

    private static GridData closeButtonGridData(ToolBar menuBar, boolean hover)
    {
        GridData gd = new GridData(SWT.END, SWT.CENTER, false, false);
        if (hover && menuBar != null && !menuBar.isDisposed()
            && menuBar.getLayoutData() instanceof GridData menuGd)
            gd.verticalIndent = menuGd.verticalIndent;
        else
            gd.verticalIndent = HEADER_LIFT_PX + (hover ? HOVER_HEADER_LIFT_EXTRA_PX : 0);
        return gd;
    }

    private static void applyCloseButtonGridData(ToolBar closeToolBar, ToolBar menuBar, boolean hover)
    {
        if (closeToolBar == null || closeToolBar.isDisposed())
            return;
        closeToolBar.setLayoutData(closeButtonGridData(menuBar, hover));
    }

    private static String describeToolBar(ToolBar bar)
    {
        if (bar == null)
            return "null"; //$NON-NLS-1$
        if (bar.isDisposed())
            return "disposed"; //$NON-NLS-1$
        String style = (bar.getStyle() & SWT.RIGHT) != 0 ? "R" : "L"; //$NON-NLS-1$ //$NON-NLS-2$
        String grid = ""; //$NON-NLS-1$
        if (bar.getLayoutData() instanceof GridData gd)
        {
            grid = " hAlign=" + gd.horizontalAlignment //$NON-NLS-1$
                + " widthHint=" + gd.widthHint //$NON-NLS-1$
                + " grabH=" + gd.grabExcessHorizontalSpace; //$NON-NLS-1$
        }
        return "items=" + bar.getItemCount() //$NON-NLS-1$
            + " @" + Integer.toHexString(System.identityHashCode(bar)) //$NON-NLS-1$
            + " style=" + style + grid //$NON-NLS-1$
            + " path=" + controlPath(bar); //$NON-NLS-1$
    }

    private static String describeTitleAreaGrid(Composite titleArea)
    {
        if (titleArea == null || titleArea.isDisposed())
            return "disposed"; //$NON-NLS-1$
        if (!(titleArea.getLayoutData() instanceof GridData gd))
            return "noGrid"; //$NON-NLS-1$
        return "hAlign=" + gd.horizontalAlignment //$NON-NLS-1$
            + " indent=" + gd.horizontalIndent //$NON-NLS-1$
            + " grabH=" + gd.grabExcessHorizontalSpace //$NON-NLS-1$
            + " path=" + controlPath(titleArea); //$NON-NLS-1$
    }

    private static String controlPath(Control control)
    {
        if (control == null || control.isDisposed())
            return "disposed"; //$NON-NLS-1$
        StringBuilder path = new StringBuilder(control.getClass().getSimpleName());
        for (Composite parent = control.getParent(); parent != null; parent = parent.getParent())
        {
            path.insert(0, parent.getClass().getSimpleName() + '/');
            if (parent instanceof Shell)
                break;
        }
        return path.toString();
    }

    private static String describeDialogStep(String label, Object data)
    {
        if (data == null)
            return label + "=null"; //$NON-NLS-1$
        if (isElementDialog(data))
            return label + '=' + data.getClass().getSimpleName();
        if (isHoverInspectControl(data))
            return label + "=hover:" + data.getClass().getSimpleName(); //$NON-NLS-1$
        String name = data.getClass().getName();
        if (name.contains("ExpressionInformationControl")) //$NON-NLS-1$
        {
            Object inner = Global.getField(data, "debugElementDialog"); //$NON-NLS-1$
            if (!isElementDialog(inner))
                inner = Global.invoke(data, "getDebugElementDialog"); //$NON-NLS-1$
            if (isElementDialog(inner))
                return label + "→" + inner.getClass().getSimpleName(); //$NON-NLS-1$
            return label + "=wrap:" + data.getClass().getSimpleName() //$NON-NLS-1$
                + "(inner=" + DebugInspectorDebug.cn(inner) + ')'; //$NON-NLS-1$
        }
        return label + "=reject:" + data.getClass().getSimpleName(); //$NON-NLS-1$
    }

    private static void logInspectorDetectOnce(Shell shell, int eventType)
    {
        if (shell.getData(DETECT_LOG_KEY) != null)
            return;
        shell.setData(DETECT_LOG_KEY, Boolean.TRUE);
        String evt = eventType == SWT.Show ? "Show" //$NON-NLS-1$
            : eventType == SWT.Activate ? "Activate" : String.valueOf(eventType); //$NON-NLS-1$
        DebugInspectorDebug.step("detect", //$NON-NLS-1$
            "evt=" + evt + " shell=\"" + shell.getText() //$NON-NLS-1$ //$NON-NLS-2$
                + "\" reason=" + detectInspectorShellReason(shell)); //$NON-NLS-1$
    }

    private static String detectInspectorShellReason(Shell shell)
    {
        if (resolveElementDialog(shell, null) != null)
            return "elementDialog"; //$NON-NLS-1$
        if (isInspectorShellData(shell.getData()))
            return "shellData"; //$NON-NLS-1$
        if (isInspectorShellData(shell.getData(WINDOW_DATA_KEY)))
            return "windowData"; //$NON-NLS-1$
        if (findHoverBindingForShell(shell) != null)
            return "hoverBinding"; //$NON-NLS-1$
        if (hasInspectorTableMarker(shell))
            return "inspectorTree"; //$NON-NLS-1$
        return "?"; //$NON-NLS-1$
    }

    private static void traceResolveDiagnostics(
        Shell shell, int attempt, InspectorTargets targets, ToolBar menuBar, String headerNote)
    {
        StringBuilder msg = new StringBuilder();
        msg.append("a=").append(attempt);
        msg.append(" shell=\"").append(shell.getText()).append('"');
        msg.append(" tree=").append(hasInspectorTableMarker(shell));
        msg.append(' ').append(describeDialogStep("shellData", shell.getData())); //$NON-NLS-1$
        msg.append(' ').append(describeDialogStep("windowData", shell.getData(WINDOW_DATA_KEY))); //$NON-NLS-1$
        if (targets.infoControl != null)
            msg.append(' ').append(describeDialogStep("infoCtrl", targets.infoControl)); //$NON-NLS-1$
        msg.append(" hoverBind=").append(findHoverBindingForShell(shell) != null); //$NON-NLS-1$
        msg.append(' ').append(describeDialogStep("resolve", resolveElementDialog(shell, targets.infoControl))); //$NON-NLS-1$
        msg.append(' ').append(describeDialogStep("shellMatch", findElementDialogByShellMatch(shell))); //$NON-NLS-1$
        msg.append(' ').append(describeDialogStep("treeMatch", findElementDialogByTreeShell(shell))); //$NON-NLS-1$
        msg.append(' ').append(describeDialogStep("targets", targets.dialog)); //$NON-NLS-1$
        if (isElementDialog(targets.dialog))
        {
            ToolBar fromField = (ToolBar) Global.getField(targets.dialog, "toolBar"); //$NON-NLS-1$
            msg.append(" dialog.toolBar=").append(fromField != null && !fromField.isDisposed() ? "ok" : "null"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Object titleObj = Global.getField(targets.dialog, "titleAreaComposite"); //$NON-NLS-1$
            msg.append(" titleArea=").append(DebugInspectorDebug.cn(titleObj)); //$NON-NLS-1$
            if (titleObj instanceof Composite title && !title.isDisposed())
            {
                ToolBar inTitle = findToolBarInControls(title);
                msg.append(" title.toolBar=").append(inTitle != null ? "ok" : "null"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        else if (hasInspectorTableMarker(shell))
        {
            Composite titleFromTree = resolveTitleAreaFromTree(shell);
            msg.append(" titleFromTree=").append(titleFromTree != null ? "ok" : "null"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        msg.append(" menu=").append(describeToolBar(menuBar)); //$NON-NLS-1$
        if (headerNote != null && !headerNote.isEmpty())
            msg.append(' ').append(headerNote);
        DebugInspectorDebug.step("resolve", msg.toString()); //$NON-NLS-1$
    }

    /**
     * Встроенный BSL-редактор вместо Combo «Выражение»: языковые подсказки (content assist,
     * hover) в контексте текущего кадра стека. История — кнопка справа, штатный Combo
     * остаётся скрытым источником пунктов и обработчика Enter/выбора.
     */
    private static final class InspectExpressionAssist
    {
        private static final String LOG = "inspect-expr"; //$NON-NLS-1$
        private static final String INSTALLED_KEY = "tormozit.inspectExpressionAssist"; //$NON-NLS-1$
        private static final String FOCUS_RESTORE_KEY = "tormozit.inspectExpressionAssistFocus"; //$NON-NLS-1$
        private static final String EDITOR_PREFIX = INSPECT_EXPRESSION_WRAP_PREFIX;
        private static final String EDITOR_SUFFIX = ");"; //$NON-NLS-1$
        private static final String BSL_FILE_URI = "*.bsl"; //$NON-NLS-1$
        private static final String HISTORY_TOOLTIP = "История выражений"; //$NON-NLS-1$

        private final Combo combo;
        private final EmbeddedEditorModelAccess modelAccess;
        private final SourceViewer sourceViewer;
        private final Button historyButton;
        private final String prefix;
        private final String suffix;
        private int lastWidgetLines = 1;

        private InspectExpressionAssist(
            Combo combo,
            EmbeddedEditorModelAccess modelAccess,
            SourceViewer sourceViewer,
            Button historyButton,
            String prefix,
            String suffix)
        {
            this.combo = combo;
            this.modelAccess = modelAccess;
            this.sourceViewer = sourceViewer;
            this.historyButton = historyButton;
            this.prefix = prefix;
            this.suffix = suffix;
        }

        static void install(Object dialog)
        {
            if (!isPopupInspectDialog(dialog))
                return;
            Object comboObj = Global.getField(dialog, "searchCombo"); //$NON-NLS-1$
            if (!(comboObj instanceof Combo combo) || combo.isDisposed())
                return;
            if (combo.getData(INSTALLED_KEY) != null)
                return;
            if (combo.getListeners(SWT.Selection).length == 0)
                return;

            IWatchExpression watch = resolveWatch(dialog);
            IResourceServiceProvider rsp = bslServiceProvider(sourceUri(watch));
            if (rsp == null)
            {
                combo.setData(INSTALLED_KEY, Boolean.FALSE);
                DebugInspectorDebug.problem("expression assist: BSL resource provider missing"); //$NON-NLS-1$
                return;
            }
            IEditedResourceProvider resourceProvider = rsp.get(IEditedResourceProvider.class);
            EmbeddedEditorFactory factory = rsp.get(EmbeddedEditorFactory.class);
            if (resourceProvider == null || factory == null)
            {
                combo.setData(INSTALLED_KEY, Boolean.FALSE);
                DebugInspectorDebug.problem("expression assist: embedded editor factory missing"); //$NON-NLS-1$
                return;
            }

            Composite parent = combo.getParent();
            if (parent == null || parent.isDisposed())
                return;
            Object layoutData = combo.getLayoutData();
            configureResource(resourceProvider, watch);

            Composite host = new Composite(parent, SWT.NONE);
            // #region agent log
            // Пробник свёрток — до создания встроенного редактора: иначе состояние
            // проекции живого редактора уже измерено после возможной поломки.
            wireLiveFoldingProbe(host, sourceUri(watch));
            // #endregion
            if (layoutData instanceof GridData gd)
                host.setLayoutData(copyGridData(gd));
            else
                host.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            GridLayout hostLayout = new GridLayout(2, false);
            hostLayout.marginWidth = 0;
            hostLayout.marginHeight = 0;
            hostLayout.horizontalSpacing = 0;
            host.setLayout(hostLayout);

            Composite editorParent = new Composite(host, SWT.NONE);
            editorParent.setLayout(new FillLayout());
            GridData editorGd = new GridData(SWT.FILL, SWT.FILL, true, true);
            int comboHeight = combo.computeSize(SWT.DEFAULT, SWT.DEFAULT).y;
            editorGd.heightHint = Math.max(comboHeight, 21);
            editorParent.setLayoutData(editorGd);

            EmbeddedEditor editor;
            try
            {
                editor = factory.newEditor(resourceProvider)
                    .withStyle(SWT.BORDER)
                    .withParent(editorParent);
            }
            catch (RuntimeException e)
            {
                host.dispose();
                combo.setData(INSTALLED_KEY, Boolean.FALSE);
                DebugInspectorDebug.problem("expression assist: create editor " + e.getMessage()); //$NON-NLS-1$
                return;
            }
            if (!(editor.getViewer() instanceof SourceViewer sourceViewer))
            {
                host.dispose();
                combo.setData(INSTALLED_KEY, Boolean.FALSE);
                return;
            }

            EmbeddedEditorModelAccess modelAccess;
            String[] wrap;
            try
            {
                wrap = wrapExpression(watch, combo.getText());
                // #region agent log
                logInspectVsLive("before-create", null, sourceUri(watch)); //$NON-NLS-1$
                // #endregion
                modelAccess = editor.createPartialEditor(wrap[0], wrap[1], wrap[2], true);
            }
            catch (RuntimeException e)
            {
                host.dispose();
                combo.setData(INSTALLED_KEY, Boolean.FALSE);
                DebugInspectorDebug.problem("expression assist: model " + e.getMessage()); //$NON-NLS-1$
                return;
            }

            StyledText text = sourceViewer.getTextWidget();
            if (text == null || text.isDisposed())
            {
                host.dispose();
                combo.setData(INSTALLED_KEY, Boolean.FALSE);
                return;
            }
            text.setData(INSPECT_EXPRESSION_EDITOR_KEY, Boolean.TRUE);
            text.setWordWrap(false);
            // Окно видимости держит сам EDT — {@code CustomEmbeddedEditorModelAccess}
            // после {@code createPartialEditor}/{@code updateModel}, как в штатной панели
            // выражения точки останова (BslBreakpointTextAndHistoryEditorPane). Свою
            // блокировку на каждое событие каретки/документа не ставим: именно она давала
            // инциденты с проекцией и координатами виджет↔модель.

            int editorHeight = editorRowHeight(text, comboHeight);
            editorGd.heightHint = editorHeight;
            if (host.getLayoutData() instanceof GridData hostGd)
            {
                hostGd.heightHint = editorHeight;
                hostGd.verticalAlignment = SWT.CENTER;
                hostGd.grabExcessVerticalSpace = false;
            }

            Button history = new Button(host, SWT.ARROW | SWT.DOWN);
            history.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, true));
            history.setToolTipText(TooltipText.wrap(history,
                HISTORY_TOOLTIP + Global.pluginSignForTooltip()));

            GridData hide = new GridData();
            hide.exclude = true;
            combo.setLayoutData(hide);
            combo.setVisible(false);

            InspectExpressionAssist assist = new InspectExpressionAssist(
                combo, modelAccess, sourceViewer, history, wrap[0], wrap[2]);
            combo.setData(INSTALLED_KEY, assist);
            history.addListener(SWT.Selection, e -> assist.openHistory());
            combo.addListener(SWT.FocusIn, e ->
            {
                if (!text.isDisposed())
                    text.setFocus();
            });
            wireEnter(text, sourceViewer, assist);
            wireRegionDiag(text, sourceViewer, assist);
            // Общее поведение поля кода BSL (Enter из списка, Ctrl+Shift+Space, жизнь
            // LinkedMode при всплывающих окнах, возврат фокуса) — в BslExpressionField:
            // оно же подключается к полям окна точки останова.
            BslExpressionField.attach(sourceViewer, LOG);
            parent.layout(true, true);
            if (!text.isDisposed())
                text.setFocus();
            assist.placeCaretAtExpressionEnd("install"); //$NON-NLS-1$
            Display display = text.getDisplay();
            if (display != null && !display.isDisposed())
            {
                display.timerExec(0, () ->
                {
                    if (!text.isDisposed())
                        assist.placeCaretAtExpressionEnd("install-0"); //$NON-NLS-1$
                });
            }
            scheduleComfortAssistPatch(sourceViewer, assist, 0);
            // #region agent log
            URI installUri = sourceUri(watch);
            logInspectVsLive("after-create", sourceViewer, installUri); //$NON-NLS-1$
            wireInspectDirtyProbe(sourceViewer, installUri);
            wireHintDiag(text, sourceViewer);
            logLiveFolding("after-create", installUri); //$NON-NLS-1$
            host.addDisposeListener(e -> logInspectVsLive("host-dispose", sourceViewer, installUri)); //$NON-NLS-1$
            // #endregion
            log("installed prefixLen=" + wrap[0].length() //$NON-NLS-1$
                + " suffixLen=" + wrap[2].length() //$NON-NLS-1$
                + " expr=[" + snippet(wrap[1], 80) + "] " //$NON-NLS-1$ //$NON-NLS-2$
                + dumpViewer(sourceViewer, wrap[0]));
            DebugInspectorDebug.step("expressionAssist", "installed h=" + editorHeight); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static int editorRowHeight(StyledText text, int comboHeight)
        {
            int line = Math.max(text.getLineHeight(), 14);
            Rectangle trim = text.computeTrim(0, 0, 0, line);
            return Math.max(comboHeight, Math.max(trim.height, line + 8));
        }

        private static void log(String message)
        {
            Global.tempLog(LOG, message);
        }

        private static String logStack()
        {
            StringBuilder sb = new StringBuilder();
            StackTraceElement[] frames = Thread.currentThread().getStackTrace();
            int n = 0;
            for (int i = 2; i < frames.length && n < 28; i++)
            {
                String cn = frames[i].getClassName();
                if (cn.startsWith("java.") //$NON-NLS-1$
                    || cn.startsWith("javax.") //$NON-NLS-1$
                    || cn.startsWith("jdk.") //$NON-NLS-1$
                    || cn.startsWith("sun.") //$NON-NLS-1$
                    || cn.startsWith("org.eclipse.swt.") //$NON-NLS-1$
                    || cn.startsWith("org.eclipse.core.runtime.") //$NON-NLS-1$
                    || cn.startsWith("org.eclipse.equinox.")) //$NON-NLS-1$
                    continue;
                sb.append(" | ").append(cn).append('.').append(frames[i].getMethodName()) //$NON-NLS-1$
                    .append(':').append(frames[i].getLineNumber());
                n++;
            }
            return sb.toString();
        }

        private static String snippet(String text, int max)
        {
            if (text == null)
                return "null"; //$NON-NLS-1$
            String one = text.replace('\r', '¬').replace('\n', '¶');
            if (one.length() <= max)
                return one;
            return one.substring(0, max) + "..."; //$NON-NLS-1$
        }

        private static String dumpViewer(SourceViewer viewer, String prefix)
        {
            IDocument document = viewer.getDocument();
            IRegion vis = viewer.getVisibleRegion();
            StyledText widget = viewer.getTextWidget();
            boolean prefixOk = document != null && documentStartsWithPrefix(document, prefix);
            boolean proj = viewer instanceof ProjectionViewer projection && projection.isProjectionMode();
            int lines = widget == null || widget.isDisposed() ? -1 : widget.getLineCount();
            int chars = widget == null || widget.isDisposed() ? -1 : widget.getCharCount();
            int clientW = widget == null || widget.isDisposed() ? -1 : widget.getClientArea().width;
            int hPixel = widget == null || widget.isDisposed() ? -1 : widget.getHorizontalPixel();
            String widgetText = widget == null || widget.isDisposed() ? "disposed" //$NON-NLS-1$
                : snippet(widget.getText(), 100);
            Point sel = viewer.getSelectedRange();
            boolean unlocked = document != null
                && vis.getOffset() == 0
                && vis.getLength() == document.getLength()
                && document.getLength() > (prefix == null ? 0 : prefix.length()) + 40;
            boolean widgetOpen = lines > 2 || chars > vis.getLength() + 40;
            return "vis=" + vis.getOffset() + "," + vis.getLength() //$NON-NLS-1$ //$NON-NLS-2$
                + " doc=" + (document == null ? -1 : document.getLength()) //$NON-NLS-1$
                + " prefixMatch=" + prefixOk //$NON-NLS-1$
                + " proj=" + proj //$NON-NLS-1$
                + " caret=" + sel.x + "," + sel.y //$NON-NLS-1$ //$NON-NLS-2$
                + " widgetLines=" + lines //$NON-NLS-1$
                + " widgetChars=" + chars //$NON-NLS-1$
                + " clientW=" + clientW //$NON-NLS-1$
                + " hPixel=" + hPixel //$NON-NLS-1$
                + (unlocked ? " UNLOCKED" : "") //$NON-NLS-1$ //$NON-NLS-2$
                + (widgetOpen ? " widgetOpen" : "") //$NON-NLS-1$ //$NON-NLS-2$
                + " widget=[" + widgetText + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static String documentDelimiter(IDocument document)
        {
            if (document instanceof IDocumentExtension4 ext)
                return ext.getDefaultLineDelimiter();
            String[] legal = document.getLegalLineDelimiters();
            if (legal != null && legal.length > 0)
                return legal[0];
            return "\n"; //$NON-NLS-1$
        }

        private static boolean hasBreakAfterPrefix(IDocument document, String prefix, String delimiter)
        {
            if (delimiter == null || delimiter.isEmpty())
                return false;
            int at = prefix.length();
            if (document.getLength() < at + delimiter.length())
                return false;
            try
            {
                return delimiter.equals(document.get(at, delimiter.length()));
            }
            catch (BadLocationException e)
            {
                return false;
            }
        }

        private void placeCaretAtExpressionEnd(String reason)
        {
            if (sourceViewer.getTextWidget() == null || sourceViewer.getTextWidget().isDisposed())
                return;
            IRegion vis = sourceViewer.getVisibleRegion();
            if (vis == null)
                return;
            int end = vis.getOffset() + vis.getLength();
            sourceViewer.setSelectedRange(end, 0);
            StyledText widget = sourceViewer.getTextWidget();
            if (widget != null && !widget.isDisposed())
                widget.setCaretOffset(widget.getCharCount());
            log("caret-end reason=" + reason //$NON-NLS-1$
                + " pos=" + end //$NON-NLS-1$
                + " " + dumpViewer(sourceViewer, prefix)); //$NON-NLS-1$
        }

        private static boolean documentStartsWithPrefix(IDocument document, String prefix)
        {
            if (prefix == null || prefix.isEmpty())
                return true;
            if (document.getLength() < prefix.length())
                return false;
            try
            {
                return prefix.equals(document.get(0, prefix.length()));
            }
            catch (BadLocationException e)
            {
                return false;
            }
        }

        private void logWidgetIfOpened(String reason)
        {
            StyledText widget = sourceViewer.getTextWidget();
            if (widget == null || widget.isDisposed())
                return;
            int lines = widget.getLineCount();
            int prev = lastWidgetLines;
            if (lines == prev)
                return;
            lastWidgetLines = lines;
            log("widget-change reason=" + reason //$NON-NLS-1$
                + " prevLines=" + prev //$NON-NLS-1$
                + " " + dumpViewer(sourceViewer, prefix) //$NON-NLS-1$
                + logStack());
        }

        /**
         * Наблюдение за полем: срыв окна видимости (число строк виджета), правки документа
         * и подмена входного документа. Окно видимости держит EDT
         * ({@code CustomEmbeddedEditorModelAccess}) — своих {@code setVisibleRegion} здесь
         * больше нет.
         */
        private static void wireRegionDiag(StyledText text, SourceViewer sourceViewer,
            InspectExpressionAssist assist)
        {
            text.addCaretListener(new CaretListener()
            {
                @Override
                public void caretMoved(CaretEvent event)
                {
                    assist.logWidgetIfOpened("caret"); //$NON-NLS-1$
                    // #region agent log
                    attachLinkedModeProbe(sourceViewer);
                    // #endregion
                }
            });
            text.addListener(SWT.Paint, e -> assist.logWidgetIfOpened("paint")); //$NON-NLS-1$
            IDocumentListener docListener = new IDocumentListener()
            {
                @Override
                public void documentAboutToBeChanged(DocumentEvent event)
                {
                }

                @Override
                public void documentChanged(DocumentEvent event)
                {
                    String newText = event.getText();
                    log("docChanged offset=" + event.getOffset() //$NON-NLS-1$
                        + " oldLen=" + event.getLength() //$NON-NLS-1$
                        + " newLen=" + (newText == null ? 0 : newText.length()) //$NON-NLS-1$
                        + " new=[" + snippet(newText, 80) + "] " //$NON-NLS-1$ //$NON-NLS-2$
                        + dumpViewer(sourceViewer, assist.prefix)
                        + logStack());
                    // #region agent log
                    attachLinkedModeProbe(sourceViewer);
                    // #endregion
                }
            };
            IDocument document = sourceViewer.getDocument();
            if (document != null)
                document.addDocumentListener(docListener);
            sourceViewer.addTextInputListener(new ITextInputListener()
            {
                @Override
                public void inputDocumentAboutToBeChanged(IDocument oldInput, IDocument newInput)
                {
                    log("inputAboutToChange oldLen=" //$NON-NLS-1$
                        + (oldInput == null ? -1 : oldInput.getLength())
                        + " newLen=" + (newInput == null ? -1 : newInput.getLength()) //$NON-NLS-1$
                        + " " + dumpViewer(sourceViewer, assist.prefix) //$NON-NLS-1$
                        + logStack());
                    if (oldInput != null)
                        oldInput.removeDocumentListener(docListener);
                }

                @Override
                public void inputDocumentChanged(IDocument oldInput, IDocument newInput)
                {
                    log("inputChanged oldLen=" //$NON-NLS-1$
                        + (oldInput == null ? -1 : oldInput.getLength())
                        + " newLen=" + (newInput == null ? -1 : newInput.getLength()) //$NON-NLS-1$
                        + " " + dumpViewer(sourceViewer, assist.prefix) //$NON-NLS-1$
                        + logStack());
                    if (newInput != null)
                        newInput.addDocumentListener(docListener);
                }
            });
            text.addDisposeListener(e ->
            {
                IDocument current = sourceViewer.getDocument();
                if (current != null)
                    current.removeDocumentListener(docListener);
            });
        }

        private static IWatchExpression resolveWatch(Object dialog)
        {
            Object watchObj = Global.getField(dialog, "expression"); //$NON-NLS-1$
            return watchObj instanceof IWatchExpression watch ? watch : null;
        }

        private static URI sourceUri(IWatchExpression watch)
        {
            IBslStackFrame frame = stackFrame(watch);
            if (frame == null)
                return URI.createURI(BSL_FILE_URI);
            URI source = frame.getSource();
            if (source == null)
                return URI.createURI(BSL_FILE_URI);
            return source.fragment() == null ? source.appendFragment("/0") : source; //$NON-NLS-1$
        }

        // #region agent log
        /**
         * Временная диагностика: показ/скрытие всплывающих окон (список автодополнения,
         * подсказка параметров) и переходы фокуса, пока живёт поле «Выражение».
         * Пишет безусловно в {@code .tmp/temp-logs/inspect-hint.log} — нужна, чтобы
         * увидеть, что именно гасит подсказку параметров сразу после вставки метода.
         */
        private static void wireHintDiag(StyledText text, SourceViewer viewer)
        {
            if (text == null || text.isDisposed())
                return;
            Display display = text.getDisplay();
            if (display == null || display.isDisposed())
                return;
            Listener shells = event ->
            {
                if (!(event.widget instanceof Shell shell))
                    return;
                String phase = event.type == SWT.Show ? "show" //$NON-NLS-1$
                    : event.type == SWT.Hide ? "hide" : "dispose"; //$NON-NLS-1$ //$NON-NLS-2$
                // Стек только на гашении: нужен виновник закрытия подсказки параметров.
                hintLog(phase, shell, text, viewer, display, event.type != SWT.Show);
            };
            display.addFilter(SWT.Show, shells);
            display.addFilter(SWT.Hide, shells);
            display.addFilter(SWT.Dispose, shells);
            Listener focus = event ->
            {
                String phase = event.type == SWT.FocusIn ? "focusIn" : "focusOut"; //$NON-NLS-1$ //$NON-NLS-2$
                hintLog(phase, null, text, viewer, display, event.type == SWT.FocusOut);
                // Кто получил фокус, видно только следующим тактом.
                display.asyncExec(() ->
                {
                    if (!text.isDisposed())
                        hintLog(phase + "-after", null, text, viewer, display, false); //$NON-NLS-1$
                });
            };
            text.addListener(SWT.FocusIn, focus);
            text.addListener(SWT.FocusOut, focus);
            text.addDisposeListener(e ->
            {
                if (display.isDisposed())
                    return;
                display.removeFilter(SWT.Show, shells);
                display.removeFilter(SWT.Hide, shells);
                display.removeFilter(SWT.Dispose, shells);
            });
        }

        private static void hintLog(String phase, Shell shell, StyledText text,
            SourceViewer viewer, Display display, boolean withStack)
        {
            try
            {
                StringBuilder sb = new StringBuilder(phase);
                if (shell != null)
                    sb.append(" shell=").append(describeShell(shell)); //$NON-NLS-1$
                sb.append(" focus=").append(describeFocus(display.getFocusControl())); //$NON-NLS-1$
                IDocument document = viewer.getDocument();
                sb.append(" linked=") //$NON-NLS-1$
                    .append(document != null && LinkedModeModel.hasInstalledModel(document));
                Point sel = viewer.getSelectedRange();
                IRegion vis = viewer.getVisibleRegion();
                sb.append(" sel=").append(sel == null ? -1 : sel.x) //$NON-NLS-1$
                    .append(',').append(sel == null ? -1 : sel.y);
                sb.append(" vis=").append(vis == null ? -1 : vis.getOffset()) //$NON-NLS-1$
                    .append(',').append(vis == null ? -1 : vis.getLength());
                sb.append(" widgetLines=").append(text.isDisposed() ? -1 : text.getLineCount()); //$NON-NLS-1$
                if (withStack)
                    sb.append(logStack());
                Global.tempLog("inspect-hint", sb.toString()); //$NON-NLS-1$
            }
            catch (Exception ignored)
            {
            }
        }

        private static String describeShell(Shell shell)
        {
            if (shell.isDisposed())
                return "disposed"; //$NON-NLS-1$
            StringBuilder sb = new StringBuilder(shell.getClass().getSimpleName());
            sb.append('@').append(Integer.toHexString(System.identityHashCode(shell)));
            String title = shell.getText();
            if (title != null && !title.isEmpty())
                sb.append(" text=[").append(snippet(title, 40)).append(']'); //$NON-NLS-1$
            Rectangle bounds = shell.getBounds();
            sb.append(" at=").append(bounds.x).append(',').append(bounds.y) //$NON-NLS-1$
                .append(' ').append(bounds.width).append('x').append(bounds.height);
            sb.append(" content=[").append(describeShellContent(shell, 0)).append(']'); //$NON-NLS-1$
            return sb.toString();
        }

        /** Классы содержимого окна: Browser у подсказки, Table у списка автодополнения. */
        private static String describeShellContent(Composite parent, int depth)
        {
            StringBuilder sb = new StringBuilder();
            for (Control child : parent.getChildren())
            {
                if (child.isDisposed())
                    continue;
                if (sb.length() > 120)
                {
                    sb.append(",…"); //$NON-NLS-1$
                    break;
                }
                if (sb.length() > 0)
                    sb.append(',');
                sb.append(child.getClass().getSimpleName());
                if (child instanceof Composite composite && depth < 3)
                {
                    String inner = describeShellContent(composite, depth + 1);
                    if (!inner.isEmpty())
                        sb.append('(').append(inner).append(')');
                }
            }
            return sb.toString();
        }

        /** Модели LinkedMode, к которым уже подключён пробник выхода. */
        private static final java.util.Set<Object> linkedProbeAttached =
            java.util.Collections.synchronizedSet(
                java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

        /**
         * Временная диагностика: кто и с какими флагами уводит LinkedMode из поля
         * «Выражение». Подсказку параметров гасит {@code BslSelectionChangedListener}
         * ровно потому, что модели LinkedMode к этому моменту уже нет.
         */
        private static void attachLinkedModeProbe(SourceViewer viewer)
        {
            try
            {
                IDocument document = viewer.getDocument();
                if (document == null || !LinkedModeModel.hasInstalledModel(document))
                    return;
                Point sel = viewer.getSelectedRange();
                int caret = sel == null ? 0 : sel.x;
                LinkedModeModel model = null;
                for (int at = caret; at >= caret - 2 && model == null; at--)
                {
                    if (at >= 0)
                        model = LinkedModeModel.getModel(document, at);
                }
                if (model == null || !linkedProbeAttached.add(model))
                    return;
                Global.tempLog("inspect-linked", "attach model=" //$NON-NLS-1$ //$NON-NLS-2$
                    + Integer.toHexString(System.identityHashCode(model)) + " caret=" + caret); //$NON-NLS-1$
                model.addLinkingListener(new ILinkedModeListener()
                {
                    @Override
                    public void left(LinkedModeModel owner, int flags)
                    {
                        Global.tempLog("inspect-linked", "left flags=" + flags //$NON-NLS-1$ //$NON-NLS-2$
                            + logStack());
                    }

                    @Override
                    public void suspend(LinkedModeModel owner)
                    {
                        Global.tempLog("inspect-linked", "suspend" + logStack()); //$NON-NLS-1$ //$NON-NLS-2$
                    }

                    @Override
                    public void resume(LinkedModeModel owner, int flags)
                    {
                        Global.tempLog("inspect-linked", "resume flags=" + flags); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                });
            }
            catch (Exception ignored)
            {
            }
        }

        /**
         * Временная диагностика: гаснет ли projection (свёртки) у живого редактора модуля
         * при открытии инспектора и кто её гасит. Пишет безусловно в
         * {@code .tmp/temp-logs/inspect-fold.log}.
         */
        /** Состояние свёрток живого редактора модуля в произвольный момент. */
        private static void logLiveFolding(String phase, URI sourceUri)
        {
            if (liveModuleViewer(sourceUri) instanceof ProjectionViewer projection)
                foldingLog(phase, projection, false);
        }

        private static void wireLiveFoldingProbe(Control host, URI sourceUri)
        {
            if (host == null || host.isDisposed())
                return;
            if (!(liveModuleViewer(sourceUri) instanceof ProjectionViewer projection))
                return;
            foldingLog("attach", projection, false); //$NON-NLS-1$
            IProjectionListener listener = new IProjectionListener()
            {
                @Override
                public void projectionEnabled()
                {
                    foldingLog("enabled", projection, true); //$NON-NLS-1$
                }

                @Override
                public void projectionDisabled()
                {
                    foldingLog("disabled", projection, true); //$NON-NLS-1$
                }
            };
            projection.addProjectionListener(listener);
            host.addDisposeListener(e ->
            {
                foldingLog("host-dispose", projection, false); //$NON-NLS-1$
                projection.removeProjectionListener(listener);
            });
        }

        private static void foldingLog(String phase, ProjectionViewer projection, boolean withStack)
        {
            try
            {
                StringBuilder sb = new StringBuilder(phase);
                sb.append(" mode=").append(projection.isProjectionMode()); //$NON-NLS-1$
                ProjectionAnnotationModel model = projection.getProjectionAnnotationModel();
                int n = -1;
                if (model != null)
                {
                    n = 0;
                    for (java.util.Iterator<?> it = model.getAnnotationIterator(); it.hasNext(); it.next())
                        n++;
                }
                sb.append(" annotations=").append(n); //$NON-NLS-1$
                StyledText widget = projection.getTextWidget();
                sb.append(" widgetLines=") //$NON-NLS-1$
                    .append(widget == null || widget.isDisposed() ? -1 : widget.getLineCount());
                IDocument document = projection.getDocument();
                sb.append(" docLen=").append(document == null ? -1 : document.getLength()); //$NON-NLS-1$
                // Подмена модели аннотаций отцепляет projection без события projectionDisabled.
                sb.append(" annModel=") //$NON-NLS-1$
                    .append(Integer.toHexString(System.identityHashCode(projection.getAnnotationModel())));
                sb.append(" viewer=") //$NON-NLS-1$
                    .append(Integer.toHexString(System.identityHashCode(projection)));
                if (withStack)
                    sb.append(logStack());
                Global.tempLog("inspect-fold", sb.toString()); //$NON-NLS-1$
            }
            catch (Exception ignored)
            {
            }
        }

        /** Штатный редактор модуля кадра стека — для диагностики свёрток. */
        private static SourceViewer liveModuleViewer(URI sourceUri)
        {
            try
            {
                URI fileUri = sourceUri == null ? null : sourceUri.trimFragment();
                if (fileUri == null || !fileUri.isPlatformResource())
                    return null;
                String platform = fileUri.toPlatformString(true);
                if (platform == null || platform.isBlank())
                    return null;
                IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(platform));
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                IWorkbenchPage page = window != null ? window.getActivePage() : null;
                if (page == null)
                    return null;
                for (IEditorReference ref : page.getEditorReferences())
                {
                    IEditorPart part = ref.getEditor(false);
                    if (part == null)
                        continue;
                    BslXtextEditor bsl = findOpenBslEditor(part);
                    if (bsl == null)
                        continue;
                    IEditorInput input = bsl.getEditorInput();
                    if (!(input instanceof IFileEditorInput fileInput) || !file.equals(fileInput.getFile()))
                        continue;
                    if (bsl.getInternalSourceViewer() instanceof SourceViewer viewer)
                        return viewer;
                }
            }
            catch (Exception ignored)
            {
            }
            return null;
        }

        private static void wireInspectDirtyProbe(SourceViewer inspectViewer, URI sourceUri)
        {
            IDocument document = inspectViewer == null ? null : inspectViewer.getDocument();
            if (document == null)
                return;
            int[] n = { 0 };
            document.addDocumentListener(new IDocumentListener()
            {
                @Override
                public void documentAboutToBeChanged(DocumentEvent event)
                {
                }

                @Override
                public void documentChanged(DocumentEvent event)
                {
                    if (n[0] >= 12)
                        return;
                    n[0]++;
                    logInspectVsLive("doc-change-" + n[0], inspectViewer, sourceUri); //$NON-NLS-1$
                }
            });
        }

        private static String dumpXtextState(IDocument document)
        {
            if (!(document instanceof IXtextDocument xdoc))
                return "{\"xtext\":false}"; //$NON-NLS-1$
            try
            {
                String resUri = String.valueOf(xdoc.getResourceURI());
                String inner = xdoc.readOnly((XtextResource res) ->
                {
                    if (res == null)
                        return "{\"res\":null}"; //$NON-NLS-1$
                    ResourceSet rs = res.getResourceSet();
                    int sameUri = 0;
                    if (rs != null && res.getURI() != null)
                    {
                        for (Resource other : rs.getResources())
                        {
                            if (res.getURI().equals(other.getURI()))
                                sameUri++;
                        }
                    }
                    return "{\"res\":" + System.identityHashCode(res) //$NON-NLS-1$
                        + ",\"mod\":" + res.isModified() //$NON-NLS-1$
                        + ",\"rs\":" + (rs == null ? 0 : System.identityHashCode(rs)) //$NON-NLS-1$
                        + ",\"rsSize\":" + (rs == null ? 0 : rs.getResources().size()) //$NON-NLS-1$
                        + ",\"sameUriCount\":" + sameUri + "}"; //$NON-NLS-1$
                });
                return "{\"resUri\":\"" + ContentAssistDebug.jsonEscapeForLog(resUri) + "\"," //$NON-NLS-1$ //$NON-NLS-2$
                    + inner.substring(1);
            }
            catch (Exception e)
            {
                return "{\"err\":true}"; //$NON-NLS-1$
            }
        }

        private static String dumpFileBuffer(IFile file, IDocument inspectDoc)
        {
            if (file == null)
                return "null"; //$NON-NLS-1$
            try
            {
                Class<?> fb = Class.forName("org.eclipse.core.filebuffers.FileBuffers"); //$NON-NLS-1$
                Object mgr = fb.getMethod("getTextFileBufferManager").invoke(null); //$NON-NLS-1$
                Class<?> locKind = Class.forName("org.eclipse.core.filebuffers.LocationKind"); //$NON-NLS-1$
                @SuppressWarnings({ "unchecked", "rawtypes" })
                Object ifile = Enum.valueOf((Class) locKind, "IFILE"); //$NON-NLS-1$
                Object buf = mgr.getClass()
                    .getMethod("getTextFileBuffer", org.eclipse.core.runtime.IPath.class, locKind) //$NON-NLS-1$
                    .invoke(mgr, file.getFullPath(), ifile);
                if (buf == null)
                    return "null"; //$NON-NLS-1$
                boolean dirty = Boolean.TRUE.equals(buf.getClass().getMethod("isDirty").invoke(buf)); //$NON-NLS-1$
                Object doc = buf.getClass().getMethod("getDocument").invoke(buf); //$NON-NLS-1$
                return "{\"dirty\":" + dirty //$NON-NLS-1$
                    + ",\"doc\":" + System.identityHashCode(doc) //$NON-NLS-1$
                    + ",\"sameInspect\":" + (doc == inspectDoc) + "}"; //$NON-NLS-1$
            }
            catch (Exception e)
            {
                return "\"err\""; //$NON-NLS-1$
            }
        }

        private static void logInspectVsLive(String phase, SourceViewer inspectViewer, URI sourceUri)
        {
            IDocument inspectDoc = inspectViewer == null ? null : inspectViewer.getDocument();
            IRegion inspectVis = inspectViewer == null ? null : inspectViewer.getVisibleRegion();
            StringBuilder live = new StringBuilder("["); //$NON-NLS-1$
            StringBuilder dirtyParts = new StringBuilder("["); //$NON-NLS-1$
            int n = 0;
            int dirtyN = 0;
            IFile file = null;
            try
            {
                URI fileUri = sourceUri == null ? null : sourceUri.trimFragment();
                if (fileUri != null && fileUri.isPlatformResource())
                {
                    String platform = fileUri.toPlatformString(true);
                    if (platform != null && !platform.isBlank())
                        file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(platform));
                }
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                IWorkbenchPage page = window != null ? window.getActivePage() : null;
                if (page != null)
                {
                    for (IEditorReference ref : page.getEditorReferences())
                    {
                        IEditorPart part = ref.getEditor(false);
                        if (part == null)
                            continue;
                        if (part.isDirty())
                        {
                            if (dirtyN > 0)
                                dirtyParts.append(',');
                            dirtyParts.append("{\"part\":\"").append(part.getClass().getSimpleName()) //$NON-NLS-1$
                                .append("\",\"title\":\"") //$NON-NLS-1$
                                .append(ContentAssistDebug.jsonEscapeForLog(String.valueOf(part.getTitle())))
                                .append("\"}"); //$NON-NLS-1$
                            dirtyN++;
                        }
                        if (file == null || !file.exists())
                            continue;
                        BslXtextEditor bsl = findOpenBslEditor(part);
                        if (bsl == null)
                            continue;
                        IEditorInput input = bsl.getEditorInput();
                        if (!(input instanceof IFileEditorInput fi) || !file.equals(fi.getFile()))
                            continue;
                        ISourceViewer sv = bsl.getInternalSourceViewer();
                        IDocument liveDoc = sv != null ? sv.getDocument() : bsl.getDocument();
                        // Никогда не звать getVisibleRegion() у ЧУЖОГО viewer:
                        // ProjectionViewer.getVisibleRegion() первым делом делает
                        // disableProjection(), и в живом редакторе модуля гаснут свёртки —
                        // слушателя модели он снимает раньше, чем removeAllAnnotations,
                        // поэтому текст остаётся свёрнутым, а стрелки исчезают
                        // (подтверждено стеком inspect-fold 12.09.2026 20:14:48).
                        IRegion vis = null;
                        IDocumentProvider dp = bsl.getDocumentProvider();
                        boolean canSave = false;
                        int pdoc = 0;
                        boolean pdocInspect = false;
                        boolean pdocLive = false;
                        if (dp != null)
                        {
                            try
                            {
                                canSave = dp.canSaveDocument(input);
                            }
                            catch (Exception ignored)
                            {
                            }
                            IDocument pd = dp.getDocument(input);
                            pdoc = System.identityHashCode(pd);
                            pdocInspect = pd == inspectDoc;
                            pdocLive = pd == liveDoc;
                        }
                        if (n > 0)
                            live.append(',');
                        live.append("{\"sameDoc\":").append(liveDoc == inspectDoc); //$NON-NLS-1$
                        live.append(",\"sameViewer\":").append(sv == inspectViewer); //$NON-NLS-1$
                        live.append(",\"partDirty\":").append(part.isDirty()); //$NON-NLS-1$
                        live.append(",\"bslDirty\":").append(bsl.isDirty()); //$NON-NLS-1$
                        live.append(",\"part\":\"").append(part.getClass().getSimpleName()).append('"'); //$NON-NLS-1$
                        live.append(",\"canSave\":").append(canSave); //$NON-NLS-1$
                        live.append(",\"pdoc\":").append(pdoc); //$NON-NLS-1$
                        live.append(",\"pdocInspect\":").append(pdocInspect); //$NON-NLS-1$
                        live.append(",\"pdocLive\":").append(pdocLive); //$NON-NLS-1$
                        live.append(",\"doc\":").append(System.identityHashCode(liveDoc)); //$NON-NLS-1$
                        live.append(",\"len\":").append(liveDoc == null ? -1 : liveDoc.getLength()); //$NON-NLS-1$
                        live.append(",\"visOff\":").append(vis == null ? -1 : vis.getOffset()); //$NON-NLS-1$
                        live.append(",\"visLen\":").append(vis == null ? -1 : vis.getLength()); //$NON-NLS-1$
                        live.append(",\"viewer\":").append(System.identityHashCode(sv)); //$NON-NLS-1$
                        live.append(",\"xtext\":").append(dumpXtextState(liveDoc)); //$NON-NLS-1$
                        if (part instanceof ISaveablesSource src)
                        {
                            live.append(",\"saveables\":["); //$NON-NLS-1$
                            Saveable[] all = src.getSaveables();
                            for (int i = 0; i < all.length; i++)
                            {
                                if (i > 0)
                                    live.append(',');
                                live.append("{\"n\":\"") //$NON-NLS-1$
                                    .append(ContentAssistDebug.jsonEscapeForLog(all[i].getName()))
                                    .append("\",\"dirty\":").append(all[i].isDirty()).append('}'); //$NON-NLS-1$
                            }
                            live.append(']');
                        }
                        live.append('}');
                        n++;
                    }
                }
            }
            catch (Exception ignored)
            {
            }
            live.append(']');
            dirtyParts.append(']');
            String payload = "{\"inspectDoc\":" + System.identityHashCode(inspectDoc) //$NON-NLS-1$
                + ",\"inspectLen\":" + (inspectDoc == null ? -1 : inspectDoc.getLength()) //$NON-NLS-1$
                + ",\"inspectVisOff\":" + (inspectVis == null ? -1 : inspectVis.getOffset()) //$NON-NLS-1$
                + ",\"inspectVisLen\":" + (inspectVis == null ? -1 : inspectVis.getLength()) //$NON-NLS-1$
                + ",\"inspectViewer\":" + System.identityHashCode(inspectViewer) //$NON-NLS-1$
                + ",\"inspectXtext\":" + dumpXtextState(inspectDoc) //$NON-NLS-1$
                + ",\"fileBuffer\":" + dumpFileBuffer(file, inspectDoc) //$NON-NLS-1$
                + ",\"dirtyParts\":" + dirtyParts //$NON-NLS-1$
                + ",\"uri\":\"" + ContentAssistDebug.jsonEscapeForLog(String.valueOf(sourceUri)) //$NON-NLS-1$
                + "\",\"live\":" + live + "}"; //$NON-NLS-1$
            ContentAssistDebug.debugSessionLog("F", "inspect.dirty", phase, payload); //$NON-NLS-1$ //$NON-NLS-2$
            Global.tempLog("inspect-dirty", phase + " " + payload); //$NON-NLS-1$
        }

        private static BslXtextEditor findOpenBslEditor(IEditorPart part)
        {
            if (part instanceof BslXtextEditor bsl)
                return bsl;
            if (part instanceof DtGranularEditor<?> granular)
            {
                IFormPage activePage = granular.getActivePageInstance();
                if (activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
                {
                    IEditorPart embedded = xtextPage.getEmbeddedEditor();
                    if (embedded instanceof BslXtextEditor bsl)
                        return bsl;
                }
                Object countObj = Global.invoke(granular, "getPageCount"); //$NON-NLS-1$
                if (countObj instanceof Integer count)
                {
                    for (int i = 0; i < count.intValue(); i++)
                    {
                        Object editor = Global.invoke(granular, "getEditor", Integer.valueOf(i)); //$NON-NLS-1$
                        if (editor instanceof BslXtextEditor bsl)
                            return bsl;
                    }
                }
            }
            return null;
        }
        // #endregion

        private static IResourceServiceProvider bslServiceProvider(URI sourceUri)
        {
            IResourceServiceProvider.Registry registry = IResourceServiceProvider.Registry.INSTANCE;
            IResourceServiceProvider rsp = registry.getResourceServiceProvider(sourceUri);
            if (rsp != null)
                return rsp;
            return registry.getResourceServiceProvider(URI.createURI(BSL_FILE_URI));
        }

        private static void configureResource(IEditedResourceProvider resourceProvider, IWatchExpression watch)
        {
            URI uri = sourceUri(watch);
            Global.invoke(resourceProvider, "setPlatformUri", uri); //$NON-NLS-1$
            URI fileUri = uri.trimFragment();
            if (!fileUri.isPlatformResource())
                return;
            String platform = fileUri.toPlatformString(true);
            if (platform == null || platform.isBlank())
                return;
            IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(platform));
            IProject project = file.getProject();
            if (project != null && project.exists())
                Global.invoke(resourceProvider, "setProject", project); //$NON-NLS-1$
        }

        private static IBslStackFrame stackFrame(IWatchExpression watch)
        {
            if (watch == null)
                return DebugSessionHelper.findSuspendedStackFrame(null);
            Object context = Global.getField(watch, "fCurrentContext"); //$NON-NLS-1$
            if (context instanceof IBslStackFrame frame)
                return frame;
            return DebugSessionHelper.findSuspendedStackFrame(null);
        }

        private static String[] wrapExpression(IWatchExpression watch, String comboText)
        {
            String editable = comboText != null ? comboText : ""; //$NON-NLS-1$
            if (editable.isBlank() && watch != null && watch.getExpressionText() != null)
                editable = watch.getExpressionText();
            IBslStackFrame frame = stackFrame(watch);
            if (frame == null)
                return new String[] { "", editable, "" }; //$NON-NLS-1$ //$NON-NLS-2$
            try
            {
                Module module = frame.getModule();
                if (module == null)
                    return new String[] { "", editable, "" }; //$NON-NLS-1$ //$NON-NLS-2$
                int line = frame.getLineNumber();
                int offset = DebugNodeModelUtils.getValidModuleResourceOffset(module, line);
                String content = DebugNodeModelUtils.getModuleResourceContent(module);
                if (content == null || content.isEmpty())
                    return new String[] { "", editable, "" }; //$NON-NLS-1$ //$NON-NLS-2$
                int safe = Math.max(0, Math.min(offset, content.length()));
                String[] wrap = wrapAtStackOffset(content, safe, editable);
                // #region agent log
                int aroundAt = Math.max(0, Math.min(safe, content.length()));
                int from = Math.max(0, aroundAt - 24);
                int to = Math.min(content.length(), aroundAt + 24);
                String around = content.substring(from, to).replace('\r', ' ').replace('\n', ' ');
                char before = aroundAt > 0 ? content.charAt(aroundAt - 1) : 0;
                boolean header = isMethodHeaderLine(content, lineStartOffset(content, safe));
                String prefixTail = wrap[0].length() <= 48
                    ? wrap[0]
                    : wrap[0].substring(wrap[0].length() - 48);
                ContentAssistDebug.debugSessionLog("D", "inspect.wrap", "splice", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"line\":" + line //$NON-NLS-1$
                        + ",\"safe\":" + safe //$NON-NLS-1$
                        + ",\"header\":" + header //$NON-NLS-1$
                        + ",\"before\":\"" + ContentAssistDebug.jsonEscapeForLog(String.valueOf(before)) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"around\":\"" + ContentAssistDebug.jsonEscapeForLog(around) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"prefixTail\":\"" + ContentAssistDebug.jsonEscapeForLog(prefixTail.replace('\r', ' ').replace('\n', '|')) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                        + ",\"hypothesisId\":\"D\"}"); //$NON-NLS-1$
                log("wrap line=" + line //$NON-NLS-1$
                    + " safe=" + safe //$NON-NLS-1$
                    + " header=" + header //$NON-NLS-1$
                    + " around=[" + around + "]" //$NON-NLS-1$ //$NON-NLS-2$
                    + " prefixTail=[" + prefixTail.replace('\r', ' ').replace('\n', '|') + "]"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                return wrap;
            }
            catch (DebugException e)
            {
                DebugInspectorDebug.problem("expression assist wrap: " + e.getMessage()); //$NON-NLS-1$
                return new String[] { "", editable, "" }; //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (RuntimeException e)
            {
                DebugInspectorDebug.problem("expression assist wrap: " + e.getMessage()); //$NON-NLS-1$
                return new String[] { "", editable, "" }; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        /**
         * {@code Строка();} — отдельный оператор в теле метода. В заголовке
         * {@code Процедура/Функция} после {@code )}, иначе в начало строки кадра.
         */
        private static String[] wrapAtStackOffset(String content, int safe, String editable)
        {
            int lineStart = lineStartOffset(content, safe);
            String nl = lineDelimiterOf(content);
            if (isMethodHeaderLine(content, lineStart))
            {
                int splice = methodBodyInsertOffset(content, lineStart, safe);
                String indent = followingLineIndent(content, splice);
                return new String[] {
                    content.substring(0, splice) + nl + indent + EDITOR_PREFIX,
                    editable,
                    EDITOR_SUFFIX + nl + content.substring(splice)
                };
            }
            String indent = lineIndent(content, lineStart, safe);
            return new String[] {
                content.substring(0, lineStart) + indent + EDITOR_PREFIX,
                editable,
                EDITOR_SUFFIX + nl + content.substring(lineStart)
            };
        }

        private static int lineStartOffset(String content, int offset)
        {
            int at = Math.max(0, Math.min(offset, content.length()));
            while (at > 0)
            {
                char c = content.charAt(at - 1);
                if (c == '\n')
                    break;
                at--;
            }
            return at;
        }

        private static int lineEndOffset(String content, int lineStart)
        {
            int n = content.indexOf('\n', lineStart);
            if (n < 0)
                return content.length();
            if (n > lineStart && content.charAt(n - 1) == '\r')
                return n - 1;
            return n;
        }

        private static boolean isMethodHeaderLine(String content, int lineStart)
        {
            int end = lineEndOffset(content, lineStart);
            String line = content.substring(lineStart, end).trim();
            return line.startsWith("Процедура ") //$NON-NLS-1$
                || line.startsWith("Функция ") //$NON-NLS-1$
                || line.startsWith("Procedure ") //$NON-NLS-1$
                || line.startsWith("Function "); //$NON-NLS-1$
        }

        private static int methodBodyInsertOffset(String content, int lineStart, int safe)
        {
            if (safe > lineStart && safe <= content.length() && content.charAt(safe - 1) == ')')
                return skipHeaderTrailer(content, safe);
            int end = lineEndOffset(content, lineStart);
            int close = content.lastIndexOf(')', end - 1);
            if (close >= lineStart)
                return skipHeaderTrailer(content, close + 1);
            return safe;
        }

        private static int skipHeaderTrailer(String content, int at)
        {
            int i = at;
            while (i < content.length())
            {
                char c = content.charAt(i);
                if (c != ' ' && c != '\t')
                    break;
                i++;
            }
            if (startsAt(content, i, "Экспорт") || startsAt(content, i, "Export")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                i += startsAt(content, i, "Экспорт") ? "Экспорт".length() : "Export".length(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                while (i < content.length())
                {
                    char c = content.charAt(i);
                    if (c != ' ' && c != '\t')
                        break;
                    i++;
                }
            }
            return i;
        }

        private static boolean startsAt(String content, int at, String token)
        {
            return at >= 0 && at + token.length() <= content.length()
                && content.regionMatches(true, at, token, 0, token.length());
        }

        private static String lineIndent(String content, int lineStart, int limit)
        {
            int end = Math.max(lineStart, Math.min(limit, content.length()));
            int i = lineStart;
            while (i < end)
            {
                char c = content.charAt(i);
                if (c != ' ' && c != '\t')
                    break;
                i++;
            }
            return content.substring(lineStart, i);
        }

        private static String followingLineIndent(String content, int from)
        {
            int i = from;
            if (i < content.length() && content.charAt(i) == '\r')
                i++;
            if (i < content.length() && content.charAt(i) == '\n')
                i++;
            int j = i;
            while (j < content.length())
            {
                char c = content.charAt(j);
                if (c != ' ' && c != '\t')
                    break;
                j++;
            }
            return j > i ? content.substring(i, j) : "\t"; //$NON-NLS-1$
        }

        private static String lineDelimiterOf(String content)
        {
            int n = content.indexOf('\n');
            if (n > 0 && content.charAt(n - 1) == '\r')
                return "\r\n"; //$NON-NLS-1$
            return n >= 0 ? "\n" : "\r\n"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static void wireEnter(StyledText text, SourceViewer sourceViewer, InspectExpressionAssist assist)
        {
            if (text == null || text.isDisposed())
                return;
            text.addVerifyKeyListener(new VerifyKeyListener()
            {
                @Override
                public void verifyKey(VerifyEvent event)
                {
                    if (event.keyCode == SWT.ARROW_DOWN
                        && (event.stateMask & SWT.MODIFIER_MASK) == 0
                        && !isAssistShowing(sourceViewer))
                    {
                        event.doit = false;
                        assist.openHistory();
                        return;
                    }
                    if (!isAssistShowing(sourceViewer)
                        && (event.keyCode == SWT.ARROW_UP
                            || event.keyCode == SWT.PAGE_UP
                            || event.keyCode == SWT.PAGE_DOWN
                            || event.keyCode == SWT.ARROW_LEFT && text.getCaretOffset() == 0
                                && (event.stateMask & SWT.MODIFIER_MASK) == 0
                            || (event.stateMask & SWT.MOD1) != 0
                                && (event.keyCode == SWT.HOME || event.keyCode == SWT.END)))
                    {
                        event.doit = false;
                        return;
                    }
                    if (event.character == SWT.ESC)
                    {
                        restoreExpressionFocus(text, sourceViewer);
                        return;
                    }
                    if (event.character != SWT.CR && event.keyCode != SWT.KEYPAD_CR)
                        return;
                    boolean origDoit = event.doit;
                    event.doit = false;
                    boolean assistVisible = isAssistShowing(sourceViewer);
                    boolean justApplied = consumeInspectExpressionProposalApplied();
                    log("enter origDoit=" + origDoit //$NON-NLS-1$
                        + " assistVisible=" + assistVisible //$NON-NLS-1$
                        + " justApplied=" + justApplied //$NON-NLS-1$
                        + " ch=" + (int) event.character //$NON-NLS-1$
                        + " key=" + event.keyCode); //$NON-NLS-1$
                    if (assistVisible || justApplied)
                        return;
                    assist.evaluateTyped();
                }
            });
            text.addListener(SWT.Traverse, e ->
            {
                if (e.detail == SWT.TRAVERSE_RETURN)
                    e.doit = false;
                if (e.detail == SWT.TRAVERSE_ESCAPE)
                {
                    e.doit = false;
                    restoreExpressionFocus(text, sourceViewer);
                }
            });
        }

        private static String describeFocus(Control focus)
        {
            return BslExpressionField.describeFocus(focus);
        }

        private static boolean isAssistShowing(SourceViewer viewer)
        {
            return ContentAssistPopupSync.isPopupVisible(
                ContentAssistPatcher.getContentAssistant(viewer));
        }

        private static void scheduleComfortAssistPatch(SourceViewer viewer,
            InspectExpressionAssist assist, int attempt)
        {
            ContentAssistManager mgr = ContentAssistManager.getInstance();
            if (mgr != null)
                mgr.applyPatchToEmbeddedBslViewer(viewer);
            if (wireAssistFocusRestore(viewer, assist))
                return;
            if (attempt >= 12)
                return;
            Display display = Display.getCurrent();
            if (display == null || display.isDisposed())
                return;
            display.timerExec(80, () ->
            {
                if (viewer.getTextWidget() == null || viewer.getTextWidget().isDisposed())
                    return;
                scheduleComfortAssistPatch(viewer, assist, attempt + 1);
            });
        }

        private static boolean wireAssistFocusRestore(SourceViewer viewer, InspectExpressionAssist assist)
        {
            StyledText text = viewer.getTextWidget();
            if (text == null || text.isDisposed())
                return true;
            if (Boolean.TRUE.equals(text.getData(FOCUS_RESTORE_KEY)))
                return true;
            ContentAssistant assistant = ContentAssistPatcher.getContentAssistant(viewer);
            if (assistant == null)
                return false;
            text.setData(FOCUS_RESTORE_KEY, Boolean.TRUE);
            assistant.addCompletionListener(new ICompletionListener()
            {
                @Override
                public void assistSessionStarted(ContentAssistEvent event)
                {
                }

                @Override
                public void assistSessionEnded(ContentAssistEvent event)
                {
                    IDocument endedDoc = viewer.getDocument();
                    boolean linked = endedDoc != null && LinkedModeModel.hasInstalledModel(endedDoc);
                    boolean apply = SmartCompletionProposal.isAnyApplyInProgress();
                    // #region agent log
                    ContentAssistDebug.debugSessionLog("D", "inspect.assist-end", "beforeLock", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "{\"linked\":" + linked //$NON-NLS-1$
                            + ",\"apply\":" + apply //$NON-NLS-1$
                            + ",\"doc\":" + BslDataEventGuard.debugDocJson(endedDoc) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
                    // #endregion
                    restoreExpressionFocus(text, viewer);
                    log("assist-end " + dumpViewer(viewer, assist.prefix)); //$NON-NLS-1$
                    // apply() у JFace идёт после assistSessionEnded, поэтому фокус
                    // возвращаем отложенно и только когда вставка уже закончилась.
                    Display display = text.getDisplay();
                    if (display != null && !display.isDisposed())
                    {
                        display.timerExec(0, () ->
                        {
                            if (text.isDisposed())
                                return;
                            IDocument laterDoc = viewer.getDocument();
                            boolean linkedLater = laterDoc != null
                                && LinkedModeModel.hasInstalledModel(laterDoc);
                            boolean applyLater = SmartCompletionProposal.isAnyApplyInProgress();
                            // #region agent log
                            ContentAssistDebug.debugSessionLog("D", "inspect.assist-end", "deferred", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                "{\"linked\":" + linkedLater //$NON-NLS-1$
                                    + ",\"apply\":" + applyLater //$NON-NLS-1$
                                    + ",\"hypothesisId\":\"A\"}"); //$NON-NLS-1$
                            // #endregion
                            if (!applyLater && !text.isDisposed() && !text.isFocusControl())
                                text.setFocus();
                        });
                    }
                }

                @Override
                public void selectionChanged(ICompletionProposal proposal, boolean smartToggle)
                {
                }
            });
            return true;
        }

        private static void restoreExpressionFocus(StyledText text, SourceViewer viewer)
        {
            BslExpressionField.restoreFocus(text, viewer, LOG);
        }

        private void evaluateTyped()
        {
            if (combo.isDisposed())
                return;
            String text = editableText();
            log("evaluate before expr=[" + snippet(text, 120) + "] " //$NON-NLS-1$ //$NON-NLS-2$
                + dumpViewer(sourceViewer, prefix));
            combo.setText(text != null ? text : ""); //$NON-NLS-1$
            Event key = new Event();
            key.type = SWT.KeyUp;
            key.widget = combo;
            key.keyCode = SWT.CR;
            key.character = SWT.CR;
            combo.notifyListeners(SWT.KeyUp, key);
            log("evaluate after " + dumpViewer(sourceViewer, prefix)); //$NON-NLS-1$
            Display display = combo.getDisplay();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() ->
            {
                if (!combo.isDisposed())
                    log("evaluate-async " + dumpViewer(sourceViewer, prefix)); //$NON-NLS-1$
            });
            display.timerExec(80, () ->
            {
                if (!combo.isDisposed())
                    log("evaluate-80 " + dumpViewer(sourceViewer, prefix)); //$NON-NLS-1$
            });
            display.timerExec(250, () ->
            {
                if (!combo.isDisposed())
                    log("evaluate-250 " + dumpViewer(sourceViewer, prefix)); //$NON-NLS-1$
            });
        }

        private void openHistory()
        {
            Control history = historyButton;
            if (combo.isDisposed() || history == null || history.isDisposed())
                return;
            String[] items = combo.getItems();
            if (items == null || items.length == 0)
                return;
            Shell shell = history.getShell();
            if (shell == null || shell.isDisposed())
                return;
            Menu menu = new Menu(shell, SWT.POP_UP);
            for (String item : items)
            {
                if (item == null || item.isBlank())
                    continue;
                MenuItem menuItem = new MenuItem(menu, SWT.PUSH);
                menuItem.setText(item.replace("&", "&&")); //$NON-NLS-1$ //$NON-NLS-2$
                String chosen = item;
                menuItem.addListener(SWT.Selection, e -> applyHistory(chosen));
            }
            if (menu.getItemCount() == 0)
            {
                menu.dispose();
                return;
            }
            Point loc = history.toDisplay(0, history.getBounds().height);
            menu.setLocation(loc);
            menu.addListener(SWT.Hide, e ->
            {
                Display display = e.display;
                if (display == null || display.isDisposed())
                    return;
                display.asyncExec(() ->
                {
                    if (!menu.isDisposed())
                        menu.dispose();
                });
            });
            menu.setVisible(true);
        }

        private void applyHistory(String item)
        {
            if (combo.isDisposed() || item == null)
                return;
            boolean viaCustom = false;
            try
            {
                viaCustom = Global.invokeVoid(modelAccess, "updateEditablePart", item); //$NON-NLS-1$
                if (!viaCustom)
                    modelAccess.updateModel(prefix, item, suffix);
            }
            catch (RuntimeException e)
            {
                modelAccess.updateModel(prefix, item, suffix);
            }
            String now = editableText();
            // Если правка не легла — повторяем через модель EDT, а не своим
            // document.replace + setVisibleRegion: регион держит модель.
            if (!item.trim().equals(now))
                modelAccess.updateModel(prefix, item, suffix);
            combo.setText(item);
            Event selection = new Event();
            selection.type = SWT.Selection;
            selection.widget = combo;
            combo.notifyListeners(SWT.Selection, selection);
            StyledText text = sourceViewer.getTextWidget();
            if (text != null && !text.isDisposed())
                text.setFocus();
            placeCaretAtExpressionEnd("history"); //$NON-NLS-1$
            log("history item=[" + snippet(item, 80) + "] viaCustom=" + viaCustom //$NON-NLS-1$ //$NON-NLS-2$
                + " now=[" + snippet(editableText(), 80) + "] " //$NON-NLS-1$ //$NON-NLS-2$
                + dumpViewer(sourceViewer, prefix));
        }

        private String editableText()
        {
            IDocument document = sourceViewer.getDocument();
            if (document == null)
                return ""; //$NON-NLS-1$
            if (!documentStartsWithPrefix(document, prefix))
            {
                String comboText = combo.isDisposed() ? "" : combo.getText(); //$NON-NLS-1$
                return comboText != null ? comboText.trim() : ""; //$NON-NLS-1$
            }
            String delimiter = documentDelimiter(document);
            int start = prefix.length();
            int tail = suffix.length();
            if (hasBreakAfterPrefix(document, prefix, delimiter))
            {
                start += delimiter.length();
                tail += delimiter.length();
            }
            int len = Math.max(0, document.getLength() - start - tail);
            try
            {
                return document.get(start, len).trim();
            }
            catch (BadLocationException e)
            {
                String text = modelAccess.getEditablePart();
                return text != null ? text.trim() : ""; //$NON-NLS-1$
            }
        }
    }
}
