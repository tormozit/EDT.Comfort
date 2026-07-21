package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslLineBreakpoint;

/**
 * Гиперссылка «Выводить ИР» в диалоге «Свойства для …» (точка останова):
 * генерация выражения — порт RDT {@code КнопкаВставитьВыражениеИзМодуля};
 * вставка — в поле «Значение выражения».
 */
public final class BreakpointPropertiesHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.breakpointPropertiesPatched"; //$NON-NLS-1$
    private static final String LINK_ROW_KEY = "tormozit.breakpointIrLinkRow"; //$NON-NLS-1$
    private static final String RESIZE_KEY = "tormozit.breakpointPaneResize"; //$NON-NLS-1$
    private static final String RESIZE_PANES_KEY = "tormozit.breakpointResizePanes"; //$NON-NLS-1$
    private static final String RESIZE_LISTENER_KEY = "tormozit.breakpointResizeListener"; //$NON-NLS-1$
    private static final int EDITOR_PANE_MIN_HEIGHT = 48;
    private static final int EDITOR_PANE_HEIGHT_HINT = 64;
    private static final int COMBO_FIELD_MAX_WIDTH = 200;
    private static final String DIALOG_TITLE_PREFIX = "Свойства для"; //$NON-NLS-1$
    private static final String BUTTON_TEXT = "Выводить ИР"; //$NON-NLS-1$
    private static final String BUTTON_TOOLTIP =
            "Вставить вывод текущего времени, метода и значений выражений из выделенного фрагмента или текущей строки модуля."; //$NON-NLS-1$
    private static final String BSL_BREAKPOINT_PAGE =
            "com._1c.g5.v8.dt.internal.debug.ui.breakpoints.BslBreakpointPage"; //$NON-NLS-1$
    private static final String ACTIONS_EDITOR =
            "com._1c.g5.v8.dt.internal.debug.ui.breakpoints.BslBreakpointActionsEditor"; //$NON-NLS-1$
    private static final String CONDITION_EDITOR =
            "com._1c.g5.v8.dt.internal.debug.ui.breakpoints.BslBreakpointConditionEditor"; //$NON-NLS-1$
    private static final String EXPRESSION_PANE =
            "com._1c.g5.v8.dt.internal.debug.ui.breakpoints.BslBreakpointTextAndHistoryEditorPane"; //$NON-NLS-1$
    private static final String JOIN_SEPARATOR = ", " + "\n"; //$NON-NLS-1$ //$NON-NLS-2$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell))
                return;
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (!isBreakpointPropertiesShell(shell))
                return;
            schedulePatchAttempt(display, shell, 0);
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static boolean isBreakpointPropertiesShell(Shell shell)
    {
        String title = shell.getText();
        if (title == null || !title.startsWith(DIALOG_TITLE_PREFIX))
            return false;
        PreferenceDialog dialog = findPreferenceDialog(shell);
        if (dialog == null)
            return false;
        Object page = dialog.getSelectedPage();
        return page != null && BSL_BREAKPOINT_PAGE.equals(page.getClass().getName());
    }

    private static void schedulePatchAttempt(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;
        int delay = attempt == 0 ? 0 : 80;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (tryPatch(shell))
                return;
            if (attempt < 12)
                schedulePatchAttempt(display, shell, attempt + 1);
        });
    }

    private static boolean tryPatch(Shell shell)
    {
        Object actionsEditor = resolveActionsEditor(shell);
        if (actionsEditor == null)
            return false;

        Button evaluateButton = (Button) Global.getField(actionsEditor, "evaluateExpressionButton"); //$NON-NLS-1$
        if (evaluateButton == null || evaluateButton.isDisposed())
            return false;

        if (Boolean.TRUE.equals(evaluateButton.getData(LINK_ROW_KEY)))
        {
            installEditorPanesVerticalStretch(shell, actionsEditor);
            shell.setData(PATCHED_KEY, Boolean.TRUE);
            return true;
        }

        installEditorPanesVerticalStretch(shell, actionsEditor);
        if (!installIrOutputLink(actionsEditor, evaluateButton))
            return false;

        shell.setData(PATCHED_KEY, Boolean.TRUE);
        return true;
    }

    /** Гиперссылка «Выводить ИР» справа от флажка «Значение выражения» (вторая колонка строки EDT). */
    private static boolean installIrOutputLink(Object actionsEditor, Button evaluateButton)
    {
        Composite parent = evaluateButton.getParent();
        if (parent == null || parent.isDisposed())
            return false;

        Control placeholder = findEvaluateExpressionRowPlaceholder(evaluateButton);
        Object layoutData = placeholder != null && !placeholder.isDisposed()
                ? placeholder.getLayoutData()
                : null;
        Control belowAnchor = findControlBelowPlaceholder(parent, evaluateButton, placeholder);

        if (placeholder != null && !placeholder.isDisposed())
            placeholder.dispose();

        Link link = new Link(parent, SWT.NONE);
        link.setText("<a>" + BUTTON_TEXT + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$
        link.setToolTipText(BUTTON_TOOLTIP);
        GridData linkData;
        if (layoutData instanceof GridData existing)
            linkData = existing;
        else
            linkData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        linkData.horizontalAlignment = SWT.END;
        linkData.grabExcessHorizontalSpace = true;
        link.setLayoutData(linkData);
        link.addListener(SWT.Selection, e ->
        {
            if (BUTTON_TEXT.equals(e.text))
                onInsertFromIr(actionsEditor);
        });

        if (belowAnchor != null && !belowAnchor.isDisposed())
            link.moveAbove(belowAnchor);
        else
            link.moveBelow(evaluateButton);

        evaluateButton.setData(LINK_ROW_KEY, Boolean.TRUE);
        parent.layout(true, true);
        return true;
    }

    /** Пустой {@link Label} во 2-й колонке строки «Значение выражения». */
    private static Control findEvaluateExpressionRowPlaceholder(Button evaluateButton)
    {
        Composite parent = evaluateButton.getParent();
        if (parent == null || parent.isDisposed())
            return null;
        Control[] children = parent.getChildren();
        for (int i = 0; i < children.length - 1; i++)
        {
            if (children[i] == evaluateButton && children[i + 1] instanceof Label label)
                return label;
        }
        return null;
    }

    private static Control findControlBelowPlaceholder(Composite parent, Button evaluateButton, Control placeholder)
    {
        Control[] children = parent.getChildren();
        boolean afterPlaceholder = false;
        for (Control child : children)
        {
            if (child == placeholder)
            {
                afterPlaceholder = true;
                continue;
            }
            if (afterPlaceholder && child != evaluateButton)
                return child;
        }
        return null;
    }

    /** Поля «Условный» / «Значение выражения» — растягивание по высоте. */
    private static void installEditorPanesVerticalStretch(Shell shell, Object actionsEditor)
    {
        Object conditionEditor = resolveBreakpointSubEditor(shell, CONDITION_EDITOR);
        if (conditionEditor != null)
        {
            Control conditionPane = (Control) Global.getField(conditionEditor, "conditionPane"); //$NON-NLS-1$
            installPaneVerticalStretch(shell, conditionPane);
        }

        Control expressionPane = (Control) Global.getField(actionsEditor, "expressionPane"); //$NON-NLS-1$
        installPaneVerticalStretch(shell, expressionPane);
    }

    private static void installPaneVerticalStretch(Shell shell, Control pane)
    {
        if (pane == null || pane.isDisposed())
            return;
        if (Boolean.TRUE.equals(pane.getData(RESIZE_KEY)))
            return;
        pane.setData(RESIZE_KEY, Boolean.TRUE);

        applyVerticalStretchGridData(pane, EDITOR_PANE_MIN_HEIGHT, EDITOR_PANE_HEIGHT_HINT);
        stretchTextAndHistoryPaneInternals(pane);
        enableVerticalGrabOnAncestors(pane);
        registerResizePane(shell, pane);
        relayoutPaneHierarchy(pane);
    }

    @SuppressWarnings("unchecked")
    private static void registerResizePane(Shell shell, Control pane)
    {
        List<Control> panes = (List<Control>) shell.getData(RESIZE_PANES_KEY);
        if (panes == null)
        {
            panes = new ArrayList<>();
            shell.setData(RESIZE_PANES_KEY, panes);
        }
        if (!panes.contains(pane))
            panes.add(pane);

        if (shell.getData(RESIZE_LISTENER_KEY) != null)
            return;

        Listener resizeListener = e ->
        {
            List<Control> registered = (List<Control>) shell.getData(RESIZE_PANES_KEY);
            if (registered == null)
                return;
            for (Control registeredPane : registered)
                relayoutPaneHierarchy(registeredPane);
        };
        shell.addListener(SWT.Resize, resizeListener);
        shell.addDisposeListener(e ->
        {
            if (!shell.isDisposed())
                shell.removeListener(SWT.Resize, resizeListener);
        });
        shell.setData(RESIZE_LISTENER_KEY, Boolean.TRUE);
    }

    private static void applyVerticalStretchGridData(Control control, int minHeight, int heightHint)
    {
        applyStretchGridData(control, minHeight, heightHint, true);
    }

    private static void applyStretchGridData(Control control, int minHeight, int heightHint, boolean grabVertical)
    {
        if (control == null || control.isDisposed())
            return;
        Object layoutData = control.getLayoutData();
        GridData gd;
        if (layoutData instanceof GridData existing)
            gd = existing;
        else
        {
            gd = new GridData(SWT.FILL, grabVertical ? SWT.FILL : SWT.CENTER, true, grabVertical);
            control.setLayoutData(gd);
        }
        gd.grabExcessHorizontalSpace = true;
        gd.grabExcessVerticalSpace = grabVertical;
        gd.horizontalAlignment = SWT.FILL;
        gd.verticalAlignment = grabVertical ? SWT.FILL : SWT.CENTER;
        if (minHeight > 0)
            gd.minimumHeight = Math.max(gd.minimumHeight, minHeight);
        if (heightHint > 0)
            gd.heightHint = Math.max(gd.heightHint, heightHint);
    }

    private static void ensureHistoryComboDoesNotGrabVertical(Control combo)
    {
        if (combo == null || combo.isDisposed())
            return;
        Object layoutData = combo.getLayoutData();
        GridData gd;
        if (layoutData instanceof GridData existing)
            gd = existing;
        else
        {
            gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
            combo.setLayoutData(gd);
        }
        gd.grabExcessHorizontalSpace = true;
        gd.grabExcessVerticalSpace = false;
        gd.horizontalAlignment = SWT.FILL;
        gd.verticalAlignment = SWT.CENTER;
    }

    private static void replaceWithNarrowComboLayout(Composite composite)
    {
        if (!(composite.getLayout() instanceof GridLayout gl))
            return;
        int marginLeft = gl.marginLeft;
        int marginTop = gl.marginTop;
        int marginRight = gl.marginRight;
        int marginBottom = gl.marginBottom;
        int spacing = gl.verticalSpacing;
        composite.setLayout(new Layout()
        {
            @Override
            protected Point computeSize(Composite parent, int wHint, int hHint, boolean flushCache)
            {
                Control[] children = parent.getChildren();
                int maxWidth = 0;
                int totalHeight = 0;
                boolean first = true;
                for (Control child : children)
                {
                    if (child.isDisposed() || !child.getVisible())
                        continue;
                    Point cs = child.computeSize(SWT.DEFAULT, SWT.DEFAULT, flushCache);
                    int cw = cs.x;
                    if (child instanceof Combo)
                        cw = Math.min(cw, COMBO_FIELD_MAX_WIDTH);
                    maxWidth = Math.max(maxWidth, cw);
                    if (!first)
                        totalHeight += spacing;
                    totalHeight += cs.y;
                    first = false;
                }
                int width = wHint != SWT.DEFAULT ? wHint : marginLeft + maxWidth + marginRight;
                int height = hHint != SWT.DEFAULT ? hHint : marginTop + totalHeight + marginBottom;
                return new Point(width, height);
            }

            @Override
            protected void layout(Composite parent, boolean flushCache)
            {
                Control[] children = parent.getChildren();
                Rectangle area = parent.getClientArea();
                int innerWidth = area.width - marginLeft - marginRight;
                int totalNatural = 0;
                int childCount = 0;
                for (Control child : children)
                {
                    if (child.isDisposed() || !child.getVisible())
                        continue;
                    Point cs = child.computeSize(SWT.DEFAULT, SWT.DEFAULT, flushCache);
                    totalNatural += cs.y;
                    childCount++;
                    if (childCount > 1)
                        totalNatural += spacing;
                }
                int extra = Math.max(0, area.height - marginTop - marginBottom - totalNatural);
                int y = area.y + marginTop;
                for (Control child : children)
                {
                    if (child.isDisposed() || !child.getVisible())
                        continue;
                    Point cs = child.computeSize(SWT.DEFAULT, SWT.DEFAULT, flushCache);
                    int cw;
                    int ch = cs.y;
                    if (child instanceof Combo)
                        cw = Math.min(cs.x, COMBO_FIELD_MAX_WIDTH);
                    else
                    {
                        cw = innerWidth;
                        if (extra > 0)
                        {
                            ch += extra;
                            extra = 0;
                        }
                    }
                    child.setBounds(area.x + marginLeft, y, Math.max(cw, 1), ch);
                    y += ch + spacing;
                }
            }
        });
    }

    private static void stretchTextAndHistoryPaneInternals(Control pane)
    {
        if (!(pane instanceof Composite composite))
            return;
        replaceWithNarrowComboLayout(composite);
        for (Control child : composite.getChildren())
        {
            if (child instanceof Combo)
            {
                ensureHistoryComboDoesNotGrabVertical(child);
                continue;
            }
            applyVerticalStretchGridData(child, EDITOR_PANE_MIN_HEIGHT, EDITOR_PANE_HEIGHT_HINT);
            if (child instanceof Composite inner)
            {
                for (Control nested : inner.getChildren())
                    applyVerticalStretchGridData(nested, EDITOR_PANE_MIN_HEIGHT, 0);
            }
        }
    }

    private static void enableVerticalGrabOnAncestors(Control start)
    {
        for (Control parent = start.getParent(); parent != null && !(parent instanceof Shell);
                parent = parent.getParent())
        {
            if (parent instanceof ScrolledComposite scrolled)
            {
                relayoutScrolledComposite(scrolled);
                continue;
            }
            applyVerticalStretchGridData(parent, 0, 0);
        }
    }

    private static void relayoutPaneHierarchy(Control pane)
    {
        if (pane == null || pane.isDisposed())
            return;
        for (Control parent = pane.getParent(); parent != null && !(parent instanceof Shell);
                parent = parent.getParent())
        {
            if (parent instanceof ScrolledComposite scrolled)
                relayoutScrolledComposite(scrolled);
        }
        Control immediateParent = pane.getParent();
        if (immediateParent instanceof Composite parentComposite && !parentComposite.isDisposed())
            parentComposite.layout(true, true);
    }

    private static void relayoutScrolledComposite(ScrolledComposite scrolled)
    {
        if (scrolled == null || scrolled.isDisposed())
            return;
        Control content = scrolled.getContent();
        if (content == null || content.isDisposed())
            return;
        if (content instanceof Composite contentComposite)
        {
            replacePageLayoutIfInflated(contentComposite);
            contentComposite.layout(true, true);
        }
        Point size = content.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        scrolled.setMinSize(size.x, size.y);
        scrolled.layout(true, true);
    }

    private static void replacePageLayoutIfInflated(Composite contentComposite)
    {
        if (contentComposite.isDisposed())
            return;
        Object layout = contentComposite.getLayout();
        if (layout == null)
            return;
        String name = layout.getClass().getSimpleName();
        if (!"PageLayout".equals(name)) //$NON-NLS-1$
            return;
        Control[] kids = contentComposite.getChildren();
        if (kids.length != 1)
            return;
        Point kidCs = kids[0].computeSize(SWT.DEFAULT, SWT.DEFAULT);
        Point myCs = contentComposite.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        if (myCs.x <= kidCs.x + 20)
            return;
        GridLayout replacement = new GridLayout();
        replacement.marginWidth = 0;
        replacement.marginHeight = 0;
        contentComposite.setLayout(replacement);
    }

    private static Object resolveBreakpointSubEditor(Shell shell, String editorClassName)
    {
        PreferenceDialog dialog = findPreferenceDialog(shell);
        if (dialog == null)
            return null;
        Object page = dialog.getSelectedPage();
        if (page == null || !BSL_BREAKPOINT_PAGE.equals(page.getClass().getName()))
            return null;

        Object compositeEditor = Global.getField(page, "editor"); //$NON-NLS-1$
        if (compositeEditor == null)
            return null;
        Object editorsObj = Global.getField(compositeEditor, "editors"); //$NON-NLS-1$
        if (!(editorsObj instanceof List<?> editors))
            return null;
        for (Object editor : editors)
        {
            if (editor != null && editorClassName.equals(editor.getClass().getName()))
                return editor;
        }
        return null;
    }

    private static Object resolveActionsEditor(Shell shell)
    {
        return resolveBreakpointSubEditor(shell, ACTIONS_EDITOR);
    }

    private static PreferenceDialog findPreferenceDialog(Shell shell)
    {
        Shell current = shell;
        while (current != null && !current.isDisposed())
        {
            Object data = current.getData();
            if (data instanceof PreferenceDialog dialog)
                return dialog;
            current = current.getParent() instanceof Shell parent ? parent : null;
        }
        return null;
    }

    private static void onInsertFromIr(Object actionsEditor)
    {
        Object breakpointObj = Global.getField(actionsEditor, "breakpoint"); //$NON-NLS-1$
        if (!(breakpointObj instanceof IBslLineBreakpoint breakpoint))
        {
            BreakpointPropertiesDebug.problem("breakpoint missing"); //$NON-NLS-1$
            return;
        }

        Object pane = Global.getField(actionsEditor, "expressionPane"); //$NON-NLS-1$
        if (pane == null || !EXPRESSION_PANE.equals(pane.getClass().getName()))
        {
            BreakpointPropertiesDebug.problem("expressionPane missing"); //$NON-NLS-1$
            return;
        }

        BslXtextEditor editor = resolveModuleEditor(breakpoint);
        if (editor == null)
        {
            ToastNotification.show(BUTTON_TEXT, "Не удалось открыть модуль точки останова", 4_000); //$NON-NLS-1$
            return;
        }

        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        if (dtProject == null)
            return;

        IRSession irSession = IRApplication.getSession(dtProject, true);
        if (irSession == null || irSession.executor == null)
            return;

        String oldExpressionText = readCurrentExpressionText(actionsEditor, pane, breakpoint);
        SelectionRange syncRange = resolveSyncRange(editor, breakpoint);

        irSession.syncCodeEditorToIR(editor, syncRange.offset, syncRange.endOffset);
        irSession.executor.submit(() ->
        {
            try
            {
                ensureCodeEditor(irSession);
                irSession.invokeCodeEditor("РазобратьТекущийКонтекст"); //$NON-NLS-1$
                String sourceText = resolveSourceText(irSession, editor, syncRange);
                List<String> objectExpressions = findObjectExpressions(irSession, sourceText);
                String fragment = ExpressionFragmentBuilder.build(irSession, oldExpressionText, objectExpressions);
                if (fragment == null || fragment.isEmpty()
                        || (!oldExpressionText.isEmpty() && oldExpressionText.contains(fragment)))
                {
                    BreakpointPropertiesDebug.log("skip insert fragmentEmpty=" + fragment.isEmpty()); //$NON-NLS-1$
                    return;
                }
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return;
                display.asyncExec(() -> insertExpressionFragment(actionsEditor, pane, breakpoint,
                        oldExpressionText, fragment));
            }
            catch (Exception e)
            {
                BreakpointPropertiesDebug.problem("insert: " + e.getMessage()); //$NON-NLS-1$
                Display display = Display.getDefault();
                if (display != null && !display.isDisposed())
                    display.asyncExec(() -> ToastNotification.show(BUTTON_TEXT,
                            "Ошибка вызова ИР: " + e.getMessage(), 5_000)); //$NON-NLS-1$
            }
        });
    }

    private static String readCurrentExpressionText(Object actionsEditor, Object pane, IBslLineBreakpoint breakpoint)
    {
        Button evaluateButton = (Button) Global.getField(actionsEditor, "evaluateExpressionButton"); //$NON-NLS-1$
        if (evaluateButton != null && evaluateButton.getSelection())
        {
            Object text = Global.invoke(pane, "getText"); //$NON-NLS-1$
            if (text instanceof String s)
                return s.trim();
        }
        try
        {
            String stored = breakpoint.getExpressionForEvaluation();
            return stored != null ? stored.trim() : ""; //$NON-NLS-1$
        }
        catch (CoreException e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    private static void insertExpressionFragment(Object actionsEditor, Object pane, IBslLineBreakpoint breakpoint,
            String oldExpressionText, String fragment)
    {
        setEvaluateExpressionEnabled(actionsEditor, pane, breakpoint, true);

        String current = ""; //$NON-NLS-1$
        Object textObj = Global.invoke(pane, "getText"); //$NON-NLS-1$
        if (textObj instanceof String s)
            current = s;

        if (!current.isEmpty() && current.contains(fragment))
            return;

        String merged = current.isEmpty() ? fragment : current + fragment;
        Object modelAccess = Global.invoke(pane, "getModelAccess"); //$NON-NLS-1$
        if (modelAccess == null)
        {
            BreakpointPropertiesDebug.problem("modelAccess missing"); //$NON-NLS-1$
            return;
        }
        Global.invoke(modelAccess, "updateEditablePart", merged); //$NON-NLS-1$
        Global.invoke(pane, "setFocusOnEditor"); //$NON-NLS-1$
        BreakpointPropertiesDebug.log("inserted len=" + merged.length() + " exprCount fragment"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void setEvaluateExpressionEnabled(Object actionsEditor, Object pane,
            IBslLineBreakpoint breakpoint, boolean enabled)
    {
        Button evaluateButton = (Button) Global.getField(actionsEditor, "evaluateExpressionButton"); //$NON-NLS-1$
        if (evaluateButton == null || evaluateButton.isDisposed())
            return;
        evaluateButton.setSelection(enabled);
        Global.invoke(pane, "setEnabled", enabled); //$NON-NLS-1$
        if (!enabled)
            return;

        String existing = ""; //$NON-NLS-1$
        try
        {
            String stored = breakpoint.getExpressionForEvaluation();
            if (stored != null)
                existing = stored;
        }
        catch (CoreException ignored) {}

        Global.invoke(pane, "ensureInitialized", breakpoint, existing); //$NON-NLS-1$
        if (pane instanceof Control control)
            relayoutPaneHierarchy(control);
    }

    private static BslXtextEditor resolveModuleEditor(IBslLineBreakpoint breakpoint)
    {
        try
        {
            IMarker marker = breakpoint.getMarker();
            if (marker == null)
                return null;
            IResource resource = marker.getResource();
            if (!(resource instanceof IFile file))
                return null;

            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;

            IEditorPart active = page.getActiveEditor();
            BslXtextEditor activeBsl = GetRef.getActiveBslEditor(active);
            if (activeBsl != null)
            {
                IFile activeFile = activeBsl.getEditorInput().getAdapter(IFile.class);
                if (file.equals(activeFile))
                {
                    revealLine(activeBsl, breakpoint.getLineNumber());
                    return activeBsl;
                }
            }

            IEditorPart part = page.findEditor(new FileEditorInput(file));
            if (part == null)
                part = IDE.openEditor(page, file, true);
            if (part == null)
                return null;

            if (part instanceof BslXtextEditor bsl)
            {
                revealLine(bsl, breakpoint.getLineNumber());
                return bsl;
            }

            BslXtextEditor embedded = GetRef.getActiveBslEditor(part);
            if (embedded != null)
            {
                revealLine(embedded, breakpoint.getLineNumber());
                return embedded;
            }
        }
        catch (Exception e)
        {
            BreakpointPropertiesDebug.problem("resolveModuleEditor: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    private static void revealLine(BslXtextEditor editor, int lineNumber)
    {
        if (lineNumber <= 0)
            return;
        try
        {
            ISourceViewer viewer = editor.getInternalSourceViewer();
            if (viewer == null)
                return;
            IDocument doc = viewer.getDocument();
            if (doc == null)
                return;
            int lineIndex = Math.max(0, lineNumber - 1);
            if (lineIndex >= doc.getNumberOfLines())
                return;
            int offset = doc.getLineOffset(lineIndex);
            editor.selectAndReveal(offset, 0);
        }
        catch (Exception e)
        {
            BreakpointPropertiesDebug.problem("revealLine: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static SelectionRange resolveSyncRange(BslXtextEditor editor, IBslLineBreakpoint breakpoint)
    {
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer != null)
        {
            Object sel = viewer.getSelectionProvider().getSelection();
            if (sel instanceof ITextSelection textSelection && textSelection.getLength() > 0)
            {
                return new SelectionRange(textSelection.getOffset(),
                        textSelection.getOffset() + textSelection.getLength());
            }
        }
        try
        {
            IDocument doc = viewer != null ? viewer.getDocument() : null;
            if (doc != null)
            {
                int lineIndex = Math.max(0, breakpoint.getLineNumber() - 1);
                if (lineIndex < doc.getNumberOfLines())
                {
                    int offset = doc.getLineOffset(lineIndex);
                    return new SelectionRange(offset, offset);
                }
            }
        }
        catch (Exception ignored) {}
        return new SelectionRange(0, 0);
    }

    private static String resolveSourceText(IRSession irSession, BslXtextEditor editor, SelectionRange syncRange)
    {
        if (syncRange.endOffset > syncRange.offset)
        {
            try
            {
                ISourceViewer viewer = editor.getInternalSourceViewer();
                IDocument doc = viewer != null ? viewer.getDocument() : null;
                if (doc != null)
                    return doc.get(syncRange.offset, syncRange.endOffset - syncRange.offset);
            }
            catch (Exception ignored) {}
        }

        Object fieldText = ComBridge.getProperty(irSession.codeEditor, "ПолеТекста"); //$NON-NLS-1$
        Object startLine = ComBridge.getProperty(irSession.codeEditor, "мНачальнаяСтрока"); //$NON-NLS-1$
        if (!ComBridge.isVariantUndefined(fieldText) && !ComBridge.isVariantUndefined(startLine))
            return ComBridge.toString(ComBridge.invoke(fieldText, "ПолучитьСтроку", startLine)); //$NON-NLS-1$
        return ""; //$NON-NLS-1$
    }

    private static List<String> findObjectExpressions(IRSession irSession, String sourceText)
    {
        List<String> result = new ArrayList<>();
        if (sourceText == null || sourceText.isBlank())
            return result;
        Object raw = ComBridge.invoke(irSession.codeEditor, "НайтиОбъектныеВыражения", sourceText); //$NON-NLS-1$
        if (raw == null)
            return result;
        for (Object item : ComBridge.iterateComCollection(raw))
        {
            String text = ComBridge.toString(item);
            if (text != null && !text.isBlank())
                result.add(text.trim());
        }
        return result;
    }

    private static void ensureCodeEditor(IRSession irSession)
    {
        if (irSession.codeEditor != null)
            return;
        Object irCache = irSession.getModule("ирКэш"); //$NON-NLS-1$
        irSession.codeEditor = ComBridge.invoke(irCache, "ПолеТекстаПрограммы", 0); //$NON-NLS-1$
    }

    private static final class SelectionRange
    {
        final int offset;
        final int endOffset;

        SelectionRange(int offset, int endOffset)
        {
            this.offset = offset;
            this.endOffset = endOffset;
        }
    }

    /** Порт сборки фрагмента из RDT {@code КнопкаВставитьВыражениеИзМодуля}. */
    private static final class ExpressionFragmentBuilder
    {
        private ExpressionFragmentBuilder() {}

        static String build(IRSession session, String oldExpressionText, List<String> objectExpressions)
        {
            if (objectExpressions.isEmpty())
                return ""; //$NON-NLS-1$

            Object irCommon = session.getModule("ирОбщий"); //$NON-NLS-1$
            List<String> parts = new ArrayList<>();

            if (oldExpressionText == null || oldExpressionText.isBlank())
            {
                Object method = ComBridge.getProperty(session.codeEditor, "мМетодМодуля"); //$NON-NLS-1$
                String methodName = ComBridge.toString(ComBridge.getProperty(method, "Имя")); //$NON-NLS-1$
                String truncatedMethod = truncate(irCommon, methodName, 50);
                parts.add("ирОбщий.ТекущееВремяЛкс(1) + \" \" + \"" + truncatedMethod + ":\""); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                parts.add(""); //$NON-NLS-1$
            }

            for (String expression : objectExpressions)
            {
                String label = truncate(irCommon, expression, 50);
                String prepared = prepareExpressionForOutput(session, irCommon, expression);
                parts.add("\"" + label + " = \"+" + prepared); //$NON-NLS-1$ //$NON-NLS-2$
            }

            return String.join(JOIN_SEPARATOR, parts);
        }

        private static String prepareExpressionForOutput(IRSession session, Object irCommon, String expression)
        {
            if (ComBridge.toBoolean(ComBridge.invoke(session.codeEditor,
                    "ЛиТекущееВыражениеИмеетСлово", "Количество", "Метод", ""))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                return expression + ".Количество()"; //$NON-NLS-1$
            return "ирОбщий.ПредставлениеСОбрезкойДлиныЛкс(" + expression + ", 50)"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static String truncate(Object irCommon, String value, int maxLen)
        {
            if (value == null)
                value = ""; //$NON-NLS-1$
            return ComBridge.toString(ComBridge.invoke(irCommon, "ПредставлениеСОбрезкойДлиныЛкс", value, maxLen)); //$NON-NLS-1$
        }
    }
}
