package tormozit;

import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;

import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.ui.IEditorPart;
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
 * Патч окон инспектора отладки (F9 / hover): «Инспектировать» (hover) и кнопка закрытия.
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
                session.refresh();
        });
    }

    private static void schedulePatchAttempt(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed())
            return;
        if (!mightBeInspectorShell(shell))
            return;
        if (attempt == 0)
        {
            if (!isInspectorCandidateShell(shell))
            {
                schedulePatchAttempt(display, shell, 1);
                return;
            }
            DebugInspectorDebug.step("patch", "try a=0"); //$NON-NLS-1$ //$NON-NLS-2$
            if (tryPatch(shell, 0))
                return;
            schedulePatchAttempt(display, shell, 1);
            return;
        }
        int delay = attempt < 8 ? 50 : 100;
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
        if (!isInspectorCandidateShell(shell))
            return false;

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
        if (root == null || root.isDisposed())
            return null;
        if (root instanceof Tree tree && treeHasInspectorColumns(tree))
            return tree;
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                Tree found = findTreeWithInspectorColumns(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static boolean treeHasInspectorColumns(Tree tree)
    {
        TreeColumn[] columns = tree.getColumns();
        if (columns == null || columns.length < 3)
            return false;
        for (TreeColumn column : columns)
        {
            String text = column.getText();
            if (text != null && (text.contains(COLUMN_MARKER_RU) || text.contains(COLUMN_MARKER_EN)))
                return true;
        }
        return false;
    }

    private record HoverBinding(Object infoControl) {}

    private record InspectorTargets(Object dialog, Object infoControl) {}

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
            applyInspectorModeForTargets();
            maintainHeaderControls(menuBar);
            if (!shell.isDisposed())
            {
                if (!shell.getVisible())
                    shell.setVisible(true);
                shell.layout(true, true);
            }
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
            if (treeEnhancement != null && treeEnhancement.isAttached())
                return;
            treeEnhancement = DebugInspectorTreeEnhancement.install(targets.dialog, shell);
            if (treeEnhancement == null)
                DebugInspectorDebug.step("tree", "install failed dialog=" //$NON-NLS-1$ //$NON-NLS-2$
                    + DebugInspectorDebug.cn(targets.dialog));
            else
                treeEnhancement.schedulePendingPropertyFocus();
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

        void applyInspectorModeForTargets()
        {
            if (isHoverMode() || shell.isDisposed())
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
            if (isElementDialog(targets.dialog))
            {
                Global.setField(targets.dialog, "listenToDeactivate", Boolean.FALSE); //$NON-NLS-1$
                Global.setField(targets.dialog, "listenToParentDeactivate", Boolean.FALSE); //$NON-NLS-1$
                installKeepDeactivateOffListener();
            }
            restoreShellOnTop(true);
            DebugInspectorDebug.step("standalone", "close OFF"); //$NON-NLS-1$ //$NON-NLS-2$
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
                if (isElementDialog(targets.dialog))
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
}
