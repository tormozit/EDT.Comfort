package tormozit;

import java.nio.file.Path;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareViewerPane;
import org.eclipse.compare.CompareViewerSwitchingPane;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.contentmergeviewer.TextMergeViewer;
import org.eclipse.compare.structuremergeviewer.ICompareInput;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Панель «Текущая строка» и кнопки ИР в попарном {@code CompareDialog} EDT
 * ({@code AbstractCompareHandler$NonModalDialog} — «Сравнение текста» из 3-way merge
 * с {@code DtCompareEditorInput}). Не трогает «Вставить со сравнением» и прочие
 * {@code CompareDialog} без этого input. Класс EDT детектируем по имени — без
 * compile-зависимости на {@code SaveableCompareEditorInput} ({@code org.eclipse.team.ui}).
 */
public final class CompareDialogCurrentLinesHook
{
    private static final String SHELL_HANDLED_KEY = "tormozit.compareDialogCurrentLinesShellHandled"; //$NON-NLS-1$
    private static final String PANEL_ATTACHED_KEY = "tormozit.compareDialogCurrentLinesAttached"; //$NON-NLS-1$
    /** FQCN-суффикс {@code com._1c.g5.v8.dt.compare.ui.DtCompareEditorInput}. */
    private static final String DT_COMPARE_INPUT_SUFFIX = ".DtCompareEditorInput"; //$NON-NLS-1$

    private static final int MAX_FAST_ATTEMPTS = 40;
    private static final int FAST_RETRY_DELAY_MS = 50;
    private static final int MAX_SLOW_ATTEMPTS = 120;
    private static final int SLOW_RETRY_DELAY_MS = 500;

    private static final int ATTACH_WAIT = 0;
    private static final int ATTACH_DONE = 1;

    private CompareDialogCurrentLinesHook()
    {
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, CompareDialogCurrentLinesHook::handleShow);
    }

    private static void handleShow(Event event)
    {
        if (!(event.widget instanceof Shell shell) || shell.isDisposed())
            return;
        if (Boolean.TRUE.equals(shell.getData(SHELL_HANDLED_KEY)))
            return;
        CompareEditorInput editorInput = extractCompareEditorInput(shell);
        if (!isDtCompareEditorInput(editorInput))
            return;
        shell.setData(SHELL_HANDLED_KEY, Boolean.TRUE);
        scheduleAttach(editorInput, shell, 0, false);
    }

    private static boolean isDtCompareEditorInput(Object input)
    {
        return input != null && input.getClass().getName().endsWith(DT_COMPARE_INPUT_SUFFIX);
    }

    /**
     * {@code org.eclipse.compare.internal.CompareDialog} и EDT {@code NonModalDialog} —
     * по имени класса; input — поле {@code fCompareEditorInput}.
     */
    private static CompareEditorInput extractCompareEditorInput(Shell shell)
    {
        Object data = shell.getData();
        if (data == null)
            return null;
        String name = data.getClass().getName();
        if (!name.contains("CompareDialog") && !name.contains("NonModalDialog")) //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        Object input = Global.getField(data, "fCompareEditorInput"); //$NON-NLS-1$
        return input instanceof CompareEditorInput editorInput ? editorInput : null;
    }

    private static void scheduleAttach(CompareEditorInput editorInput, Shell shell, int attempt, boolean slow)
    {
        Display display = shell.getDisplay();
        if (display == null || display.isDisposed())
            return;
        int max = slow ? MAX_SLOW_ATTEMPTS : MAX_FAST_ATTEMPTS;
        int delay = slow ? SLOW_RETRY_DELAY_MS : (attempt == 0 ? 100 : FAST_RETRY_DELAY_MS);
        if (attempt >= max)
        {
            if (!slow)
                scheduleAttach(editorInput, shell, 0, true);
            return;
        }
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed())
                return;
            int result = tryAttach(editorInput, shell);
            if (result == ATTACH_WAIT)
                scheduleAttach(editorInput, shell, attempt + 1, slow);
        });
    }

    private static int tryAttach(CompareEditorInput editorInput, Shell shell)
    {
        Object paneObj = Global.getField(editorInput, "fContentInputPane"); //$NON-NLS-1$
        if (!(paneObj instanceof CompareViewerSwitchingPane pane) || pane.isDisposed())
            return ATTACH_WAIT;

        if (Boolean.TRUE.equals(pane.getData(PANEL_ATTACHED_KEY)))
            return ATTACH_DONE;

        Viewer viewer = pane.getViewer();
        if (viewer == null)
            return ATTACH_WAIT;
        if (!(viewer instanceof TextMergeViewer mergeViewer))
            return ATTACH_WAIT;

        Control viewerControl = viewer.getControl();
        if (viewerControl == null || viewerControl.isDisposed())
            return ATTACH_WAIT;
        if (viewerControl.getParent() != pane)
            return ATTACH_WAIT;

        attach(pane, viewerControl, editorInput, mergeViewer, shell);
        return ATTACH_DONE;
    }

    private static void attach(CompareViewerSwitchingPane pane, Control viewerControl,
        CompareEditorInput editorInput, TextMergeViewer viewer, Shell shell)
    {
        if (pane.isDisposed())
            return;
        if (Boolean.TRUE.equals(pane.getData(PANEL_ATTACHED_KEY)))
            return;
        pane.setData(PANEL_ATTACHED_KEY, Boolean.TRUE);

        Composite wrapper = new Composite(pane, SWT.NONE);
        GridLayout wrapperLayout = new GridLayout(1, false);
        wrapperLayout.marginWidth = 0;
        wrapperLayout.marginHeight = 0;
        wrapper.setLayout(wrapperLayout);

        viewerControl.setParent(wrapper);
        viewerControl.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        CompareConfiguration config = editorInput.getCompareConfiguration();
        /*
         * Семантические подписи (левый/правый input). CLabel при attach часто ещё
         * до применения запомненного MIRRORED — не считать их визуальными.
         */
        String semanticLeft = resolveSemanticSideLabel(viewer, config, true);
        String semanticRight = resolveSemanticSideLabel(viewer, config, false);

        CompareCurrentLinesPanel panel = CompareCurrentLinesPanel.create(wrapper,
            labelOrDefault(semanticLeft, "Слева:"), labelOrDefault(semanticRight, "Справа:")); //$NON-NLS-1$ //$NON-NLS-2$
        panel.getControl().setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));

        pane.setContent(wrapper);
        pane.layout(true, true);

        StyledText leftText = MergeViewerReflection.extractStyledText(viewer, "fLeft"); //$NON-NLS-1$
        StyledText rightText = MergeViewerReflection.extractStyledText(viewer, "fRight"); //$NON-NLS-1$

        String irSyntaxVariant = IrCompareValuesHandler.syntaxVariantFor(resolveCompareType(editorInput));
        final String semLeft = semanticLeft;
        final String semRight = semanticRight;
        panel.setCompareInIrSupplier(() ->
        {
            boolean mirrored = config != null && config.isMirrored();
            String liveLeft = TwoSideCurrentLinesSync.visualSideLabel(semLeft, semRight, mirrored, true);
            String liveRight = TwoSideCurrentLinesSync.visualSideLabel(semLeft, semRight, mirrored, false);
            String title = labelOrDefault(liveLeft, "Слева") + " / " //$NON-NLS-1$ //$NON-NLS-2$
                + labelOrDefault(liveRight, "Справа"); //$NON-NLS-1$
            return supplyFullTextsForIr(leftText, rightText, title, liveLeft, liveRight, irSyntaxVariant);
        });

        addToolbarActions(pane, panel, editorInput, viewer, semanticLeft, semanticRight);

        TwoSideCurrentLinesSync.hook(panel, leftText, rightText, viewer, config, semanticLeft, semanticRight);

        wrapper.addDisposeListener(e ->
        {
            if (!pane.isDisposed())
                pane.setData(PANEL_ATTACHED_KEY, null);
            if (!shell.isDisposed())
                scheduleAttach(editorInput, shell, 0, false);
        });
    }

    /**
     * Семантическая подпись стороны (левый/правый input), без учёта визуального
     * {@code MIRRORED}. Сначала {@link CompareConfiguration}, иначе CLabel
     * (на раннем attach шапка ещё не зеркалена).
     */
    private static String resolveSemanticSideLabel(TextMergeViewer viewer, CompareConfiguration config,
        boolean left)
    {
        if (config != null)
        {
            String fromConfig = left ? config.getLeftLabel(null) : config.getRightLabel(null);
            if (fromConfig != null && !fromConfig.isBlank())
                return fromConfig;
        }
        return MergeViewerReflection.extractLabelText(viewer,
            left ? "fLeftLabel" : "fRightLabel"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void addToolbarActions(CompareViewerPane pane, CompareCurrentLinesPanel panel,
        CompareEditorInput editorInput, TextMergeViewer viewer, String semanticLeft, String semanticRight)
    {
        IToolBarManager toolBarManager = CompareViewerPane.getToolBarManager(pane);
        if (toolBarManager == null)
            return;

        IContributionItem[] existingItems = toolBarManager.getItems();
        toolBarManager.removeAll();

        ITypedElement left = resolveLeft(editorInput);
        ITypedElement right = resolveRight(editorInput);
        boolean mxlx = CompareTabularDocumentsInIr.isMxlxTypedElement(left)
            || CompareTabularDocumentsInIr.isMxlxTypedElement(right);

        if (mxlx)
        {
            CompareConfiguration config = editorInput.getCompareConfiguration();
            // Без зеркала: MAIN слева = Текущий (не новее), OTHER справа = Новый.
            final boolean semanticLeftIsNewer = false;
            final String semLeft = semanticLeft;
            final String semRight = semanticRight;
            Action tabularAction = new Action(CompareTabularDocumentsInIr.MENU_LABEL)
            {
                @Override
                public void run()
                {
                    boolean mirrored = config != null && config.isMirrored();
                    ITypedElement uiLeft = mirrored ? right : left;
                    ITypedElement uiRight = mirrored ? left : right;
                    String liveLeft = TwoSideCurrentLinesSync.visualSideLabel(semLeft, semRight, mirrored, true);
                    String liveRight = TwoSideCurrentLinesSync.visualSideLabel(semLeft, semRight, mirrored, false);
                    boolean uiLeftIsNewer = mirrored ? !semanticLeftIsNewer : semanticLeftIsNewer;
                    runTabularCompare(uiLeft, uiRight, liveLeft, liveRight, uiLeftIsNewer);
                }
            };
            tabularAction.setToolTipText(
                CompareTabularDocumentsInIr.TOOLTIP + Global.pluginSignForTooltip());
            toolBarManager.add(tabularAction);
        }

        Action compareInIrAction = new Action(IrCompareValuesHandler.MENU_LABEL)
        {
            @Override
            public void run()
            {
                panel.triggerCompareInIr();
            }
        };
        compareInIrAction.setToolTipText(IrCompareValuesHandler.TOOLTIP + Global.pluginSignForTooltip());
        toolBarManager.add(compareInIrAction);

        toolBarManager.add(panel.createVisibilityToggleAction());
        toolBarManager.add(new Separator());
        for (IContributionItem item : existingItems)
            toolBarManager.add(item);

        toolBarManager.update(true);
    }

    private static void runTabularCompare(ITypedElement left, ITypedElement right,
        String leftLabel, String rightLabel, boolean uiLeftIsNewer)
    {
        Path pathLeft = CompareTabularDocumentsInIr.resolveSideFile(left, "left"); //$NON-NLS-1$
        Path pathRight = CompareTabularDocumentsInIr.resolveSideFile(right, "right"); //$NON-NLS-1$
        IDtProject dtProject = resolveActiveDtProject();
        CompareTabularDocumentsInIr.runCompareTwoSide(dtProject, pathLeft, pathRight,
            leftLabel, rightLabel, uiLeftIsNewer, true);
    }

    private static IDtProject resolveActiveDtProject()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        if (page == null)
            return null;
        org.eclipse.core.resources.IProject project = Global.getActiveProject(page, true);
        return project != null ? Global.getDtProjectFromWorkspaceProject(project) : null;
    }

    private static ITypedElement resolveLeft(CompareEditorInput editorInput)
    {
        Object left = Global.call(editorInput, "getLeft"); //$NON-NLS-1$
        if (left instanceof ITypedElement typed)
            return typed;
        Object result = editorInput.getCompareResult();
        if (result instanceof ICompareInput compareInput)
            return compareInput.getLeft();
        return null;
    }

    private static ITypedElement resolveRight(CompareEditorInput editorInput)
    {
        Object right = Global.call(editorInput, "getRight"); //$NON-NLS-1$
        if (right instanceof ITypedElement typed)
            return typed;
        Object result = editorInput.getCompareResult();
        if (result instanceof ICompareInput compareInput)
            return compareInput.getRight();
        return null;
    }

    private static CompareCurrentLinesPanel.FullTextPair supplyFullTextsForIr(
        StyledText leftText, StyledText rightText, String title, String leftLabel, String rightLabel,
        String syntaxVariant)
    {
        if (leftText == null || leftText.isDisposed() || rightText == null || rightText.isDisposed())
            return null;
        return new CompareCurrentLinesPanel.FullTextPair(leftText.getText(), rightText.getText(), title,
            leftLabel, rightLabel, syntaxVariant,
            CompareLineRangeMatcher.lineAtCaret(leftText), CompareLineRangeMatcher.lineAtCaret(rightText));
    }

    private static String resolveCompareType(CompareEditorInput editorInput)
    {
        ITypedElement left = resolveLeft(editorInput);
        if (left != null && left.getName() != null)
        {
            String name = left.getName();
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1)
                return name.substring(dot + 1);
        }
        if (left != null && left.getType() != null)
            return left.getType();
        ITypedElement right = resolveRight(editorInput);
        return right != null ? right.getType() : null;
    }

    private static String labelOrDefault(String text, String fallback)
    {
        return text != null && !text.isBlank() ? text : fallback;
    }
}
