package tormozit;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareViewerPane;
import org.eclipse.compare.CompareViewerSwitchingPane;
import org.eclipse.compare.contentmergeviewer.TextMergeViewer;
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

/**
 * Панель «Текущая строка» ({@link CompareCurrentLinesPanel}) в предпросмотре изменений мастера
 * рефакторинга LTK — окна «Переименовать элемент» и т.п. (страница «Вносимые изменения» +
 * панель сравнения «Начальный исходный текст» / «Исходный текст после рефакторинга»).
 *
 * <p>Панель сравнения там — {@code TextEditChangePreviewViewer$ComparePreviewer}, наследник
 * {@link CompareViewerSwitchingPane} со своим {@code CompareConfiguration} (создаётся прямо в
 * нём, {@link org.eclipse.compare.CompareEditorInput} нет вовсе). Поэтому pane ищем обходом
 * дерева виджетов окна, а не через {@code fContentInputPane} входа сравнения, как
 * {@link CompareDialogCurrentLinesHook} / {@link GitCompareCurrentLinesHook}. Внутри — обычный
 * {@link TextMergeViewer} (для BSL — вариант EDT), значит синхронизация та же:
 * {@link TwoSideCurrentLinesSync}.
 *
 * <p>Стороны подписаны {@link #LABEL_BEFORE} / {@link #LABEL_AFTER} вместо штатных названий.
 * «Поменять местами» ({@code MIRRORED}) у этого {@code CompareConfiguration} берётся из общей
 * настройки Eclipse Compare и может быть уже включено при открытии (именно поэтому в окне сверху
 * нередко оказывается текст после рефакторинга) — визуальный порядок подписей ведёт
 * {@code TwoSideCurrentLinesSync} по семантическим сторонам (левая = до рефакторинга).
 */
public final class RefactoringPreviewCurrentLinesHook
{
    /**
     * Свои подписи сторон вместо штатных «Начальный исходный текст» / «Исходный текст после
     * рефакторинга» — короче и однозначно читаются в шапке панелей и в панели «Текущая строка».
     */
    private static final String LABEL_BEFORE = "Текст ДО рефакторинга"; //$NON-NLS-1$
    private static final String LABEL_AFTER = "Текст ПОСЛЕ рефакторинга"; //$NON-NLS-1$

    private static final String SHELL_HANDLED_KEY = "tormozit.refactoringPreviewCurrentLinesShell"; //$NON-NLS-1$
    private static final String PANEL_ATTACHED_KEY = "tormozit.refactoringPreviewCurrentLinesAttached"; //$NON-NLS-1$

    /**
     * Диалог мастера рефакторинга: LTK открывает {@code RefactoringWizardDialog2} (реже
     * {@code RefactoringWizardDialog}). Проверяем обе части имени по отдельности — чтобы не
     * зависеть от точного класса, но и не сканировать дерево виджетов каждого показанного окна.
     */
    private static final String DIALOG_NAME_PART_REFACTORING = "Refactoring"; //$NON-NLS-1$
    private static final String DIALOG_NAME_PART_DIALOG = "Dialog"; //$NON-NLS-1$

    private static final int MAX_FAST_ATTEMPTS = 40;
    private static final int FAST_RETRY_DELAY_MS = 50;
    /** Предпросмотр может быть не первой страницей мастера — ждём дольше, но не бесконечно. */
    private static final int MAX_SLOW_ATTEMPTS = 1200;
    private static final int SLOW_RETRY_DELAY_MS = 500;

    private static final int ATTACH_WAIT = 0;
    private static final int ATTACH_DONE = 1;

    private RefactoringPreviewCurrentLinesHook()
    {
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, RefactoringPreviewCurrentLinesHook::handleShow);
    }

    private static void handleShow(Event event)
    {
        if (!(event.widget instanceof Shell shell) || shell.isDisposed())
            return;
        if (Boolean.TRUE.equals(shell.getData(SHELL_HANDLED_KEY)))
            return;
        if (!isRefactoringWizardDialog(shell))
            return;
        shell.setData(SHELL_HANDLED_KEY, Boolean.TRUE);
        scheduleAttach(shell, 0, false);
    }

    private static boolean isRefactoringWizardDialog(Shell shell)
    {
        Object data = shell.getData();
        if (data == null)
            return false;
        String name = data.getClass().getName();
        return name.contains(DIALOG_NAME_PART_REFACTORING) && name.contains(DIALOG_NAME_PART_DIALOG);
    }

    private static void scheduleAttach(Shell shell, int attempt, boolean slow)
    {
        Display display = shell.getDisplay();
        if (display == null || display.isDisposed())
            return;
        int max = slow ? MAX_SLOW_ATTEMPTS : MAX_FAST_ATTEMPTS;
        int delay = slow ? SLOW_RETRY_DELAY_MS : (attempt == 0 ? 100 : FAST_RETRY_DELAY_MS);
        if (attempt >= max)
        {
            if (!slow)
                scheduleAttach(shell, 0, true);
            return;
        }
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed())
                return;
            if (tryAttach(shell) == ATTACH_WAIT)
                scheduleAttach(shell, attempt + 1, slow);
        });
    }

    private static int tryAttach(Shell shell)
    {
        CompareViewerSwitchingPane pane = findComparePane(shell);
        if (pane == null || pane.isDisposed())
            return ATTACH_WAIT;
        if (Boolean.TRUE.equals(pane.getData(PANEL_ATTACHED_KEY)))
            return ATTACH_DONE;

        Viewer viewer = pane.getViewer();
        if (!(viewer instanceof TextMergeViewer mergeViewer))
            return ATTACH_WAIT;

        Control viewerControl = viewer.getControl();
        if (viewerControl == null || viewerControl.isDisposed())
            return ATTACH_WAIT;
        if (viewerControl.getParent() != pane)
            return ATTACH_WAIT;

        StyledText leftText = MergeViewerReflection.extractStyledText(mergeViewer, "fLeft"); //$NON-NLS-1$
        StyledText rightText = MergeViewerReflection.extractStyledText(mergeViewer, "fRight"); //$NON-NLS-1$
        if (leftText == null || leftText.isDisposed() || rightText == null || rightText.isDisposed())
            return ATTACH_WAIT;

        pane.setData(PANEL_ATTACHED_KEY, Boolean.TRUE);
        attach(pane, viewerControl, mergeViewer, leftText, rightText, shell);
        return ATTACH_DONE;
    }

    /** В окне мастера рефакторинга панель предпросмотра одна — берём первую найденную. */
    private static CompareViewerSwitchingPane findComparePane(Composite composite)
    {
        if (composite == null || composite.isDisposed())
            return null;
        for (Control child : composite.getChildren())
        {
            if (child instanceof CompareViewerSwitchingPane pane && !pane.isDisposed())
                return pane;
            if (child instanceof Composite nested)
            {
                CompareViewerSwitchingPane found = findComparePane(nested);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static void attach(CompareViewerSwitchingPane pane, Control viewerControl, TextMergeViewer viewer,
        StyledText leftText, StyledText rightText, Shell shell)
    {
        /*
         * Содержимое pane (ViewForm) — control вьюера напрямую. Оборачиваем: control вьюера и
         * панель «Текущая строка» в свой composite, его же подставляем как содержимое pane.
         */
        Composite wrapper = new Composite(pane, SWT.NONE);
        GridLayout wrapperLayout = new GridLayout(1, false);
        wrapperLayout.marginWidth = 0;
        wrapperLayout.marginHeight = 0;
        wrapper.setLayout(wrapperLayout);

        viewerControl.setParent(wrapper);
        viewerControl.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        CompareConfiguration config = resolveConfig(pane, viewer);
        String semanticLeft = LABEL_BEFORE;
        String semanticRight = LABEL_AFTER;
        applyHeaderLabels(config, viewer, semanticLeft, semanticRight);

        CompareCurrentLinesPanel panel = CompareCurrentLinesPanel.create(wrapper, semanticLeft, semanticRight);
        panel.getControl().setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));

        pane.setContent(wrapper);
        pane.layout(true, true);
        applyHeaderLabels(config, viewer, semanticLeft, semanticRight);
        scheduleHeaderLabelRefresh(config, viewer, semanticLeft, semanticRight);

        addToolbarActions(pane, panel, shell);

        TwoSideCurrentLinesSync.hook(panel, leftText, rightText, viewer, config, semanticLeft, semanticRight);

        /*
         * Смена выбранного изменения в дереве «Вносимые изменения» переиспользует тот же вьюер
         * (см. CompareViewerSwitchingPane.setViewer — при том же вьюере содержимое не трогается),
         * но при смене типа предпросмотра вьюер пересоздаётся, и наш wrapper уничтожается
         * вместе с панелью. Тогда присоединяемся заново.
         */
        wrapper.addDisposeListener(e ->
        {
            if (!pane.isDisposed())
                pane.setData(PANEL_ATTACHED_KEY, null);
            if (!shell.isDisposed())
                scheduleAttach(shell, 0, false);
        });
    }

    /**
     * {@code CompareConfiguration} предпросмотра: своё поле {@code ComparePreviewer}, иначе —
     * у самого вьюера ({@code ContentMergeViewer.getCompareConfiguration()} защищённый).
     */
    private static CompareConfiguration resolveConfig(CompareViewerSwitchingPane pane, TextMergeViewer viewer)
    {
        Object fromPane = Global.getField(pane, "fCompareConfiguration"); //$NON-NLS-1$
        if (fromPane instanceof CompareConfiguration config)
            return config;
        Object fromViewer = Global.invoke(viewer, "getCompareConfiguration"); //$NON-NLS-1$
        return fromViewer instanceof CompareConfiguration config ? config : null;
    }

    /**
     * Пишет подписи сторон в {@link CompareConfiguration} и прямо в
     * {@code ContentMergeViewer.fLeftLabel}/{@code fRightLabel} — тот же приём, что в
     * {@link GitCompareCurrentLinesHook}: только config недостаточно (шапка уже нарисована),
     * только CLabel недостаточно (штатный {@code updateHeader} при смене выбранного изменения
     * перечитывает подписи из config и откатывает наши).
     */
    private static void applyHeaderLabels(CompareConfiguration config, TextMergeViewer viewer,
        String leftLabel, String rightLabel)
    {
        if (viewer == null || viewer.getControl() == null || viewer.getControl().isDisposed())
            return;
        if (config != null)
        {
            config.setLeftLabel(leftLabel);
            config.setRightLabel(rightLabel);
        }
        /*
         * Config хранит семантические стороны; при MIRRORED ContentMergeViewer показывает их
         * зеркально — прямая запись в CLabel должна это учитывать.
         */
        boolean mirrored = config != null && config.isMirrored();
        MergeViewerReflection.setLabelText(viewer, "fLeftLabel", //$NON-NLS-1$
            TwoSideCurrentLinesSync.visualSideLabel(leftLabel, rightLabel, mirrored, true));
        MergeViewerReflection.setLabelText(viewer, "fRightLabel", //$NON-NLS-1$
            TwoSideCurrentLinesSync.visualSideLabel(leftLabel, rightLabel, mirrored, false));
    }

    /** Повторная установка шапки после отложенного {@code updateHeader} (layout, смена входа). */
    private static void scheduleHeaderLabelRefresh(CompareConfiguration config, TextMergeViewer viewer,
        String leftLabel, String rightLabel)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> applyHeaderLabels(config, viewer, leftLabel, rightLabel));
        display.timerExec(100, () -> applyHeaderLabels(config, viewer, leftLabel, rightLabel));
        display.timerExec(500, () -> applyHeaderLabels(config, viewer, leftLabel, rightLabel));
    }

    /**
     * Переключатели «Текущие строки» и маркеров вхождений — в командную панель самого
     * просмотрщика сравнения. Тулбар принадлежит {@code ToolBarManager}, его {@code update(true)}
     * чужие {@code ToolItem} не сохраняет — поэтому шелл помечаем обслуженным для универсального
     * скана {@link OccurrencesToggleHook}, а уже добавленное им убираем.
     */
    private static void addToolbarActions(CompareViewerPane pane, CompareCurrentLinesPanel panel, Shell shell)
    {
        IToolBarManager toolBarManager = CompareViewerPane.getToolBarManager(pane);
        if (toolBarManager == null)
            return;

        IContributionItem[] existingItems = toolBarManager.getItems();
        toolBarManager.removeAll();

        toolBarManager.add(panel.createVisibilityToggleAction());
        toolBarManager.add(OccurrencesToggleHook.createToggleAction());
        toolBarManager.add(new Separator());
        for (IContributionItem item : existingItems)
        {
            // Кнопка вхождений от прошлого присоединения — иначе в панели два переключателя
            if (!OccurrencesToggleHook.isStaleToggleItem(item))
                toolBarManager.add(item);
        }

        toolBarManager.update(true);

        OccurrencesToggleHook.markShellHandled(shell);
        OccurrencesToggleHook.removeDialogItems(shell);
    }
}
