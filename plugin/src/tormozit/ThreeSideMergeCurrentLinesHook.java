package tormozit;

import java.nio.file.Path;

import com._1c.g5.v8.dt.bsl.compare.BslModuleComparisonNode;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSource;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.compare.ui.mergeviewer.IThreeSideTextMergeInput;
import com._1c.g5.v8.dt.compare.ui.mergeviewer.IThreeSideTextMergeViewerProvider;
import com._1c.g5.v8.dt.compare.ui.mergeviewer.ThreeSideTextMergeViewer;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IPartialModelNode;
import com._1c.g5.v8.dt.core.platform.IDtProject;

import org.eclipse.compare.ITypedElement;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.ViewForm;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Панель «Текущая строка» (см. {@link CompareCurrentLinesPanel}) в любом окне
 * 1C:EDT на основе трёхстороннего merge-вьюера — «Настройка объединения модулей»
 * (со structure-разбором, {@code CompareBslModuleWithParsingModuleStructureDialog})
 * и «Объединение» (без разбора структуры, {@code BslModuleThreeSideMergeDialog} /
 * {@code ThreeSideTextMergeDialog}). Оба реализуют публичный
 * {@code IThreeSideTextMergeViewerProvider.getMergeViewer()} — детектируем по
 * этому интерфейсу, а не по имени конкретного класса диалога.
 *
 * <p>Это не наши диалоги — встраиваем панель в уже существующее дерево виджетов
 * после открытия окна, а не через переопределение {@code createContents} (как в
 * {@link PasteWithCompareActions}).
 *
 * <p>Три стороны сравнения (левая/правая/итоговая со слиянием) вместо двух —
 * раскрашивается пара «сторона под кареткой ↔ итоговая» (или «левая ↔ итоговая»,
 * если каретка в итоговой панели); третья, не участвующая сторона показывается
 * обычным текстом сопоставленной строки (или пусто, если сопоставленной строки нет).
 */
public final class ThreeSideMergeCurrentLinesHook
{
    private static final String TAG = "ThreeSideMergeCurrentLines"; //$NON-NLS-1$

    private static final String SHELL_HANDLED_KEY = "tormozit.threeSideMergeCurrentLinesShellHandled"; //$NON-NLS-1$
    private static final String PANEL_ATTACHED_KEY = "tormozit.threeSideMergeCurrentLinesAttached"; //$NON-NLS-1$
    private static final String FILTER_ROW_RELAID_KEY = "tormozit.threeSideMergeFilterRowRelaid"; //$NON-NLS-1$
    private static final String VIEWFORM_SPACING_ADJUSTED_KEY = "tormozit.threeSideMergeViewFormSpacingAdjusted"; //$NON-NLS-1$
    private static final String HOOK_MARKER_KEY = "tormozit.threeSideMergeCurrentLinesHooked"; //$NON-NLS-1$

    private static final int MAX_ATTEMPTS = 40;
    private static final int RETRY_DELAY_MS = 50;

    private static final int LEFT = 0;
    private static final int RIGHT = 1;
    private static final int RESULT = 2;

    /** Используются, только если не удалось прочитать заголовок вьюера (см. {@link #refreshLabels}). */
    private static final String DEFAULT_LEFT_LABEL = "Ваша версия:"; //$NON-NLS-1$
    private static final String DEFAULT_RIGHT_LABEL = "Входящая версия:"; //$NON-NLS-1$
    private static final String DEFAULT_RESULT_LABEL = "Итоговый текст:"; //$NON-NLS-1$

    private ThreeSideMergeCurrentLinesHook()
    {
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, ThreeSideMergeCurrentLinesHook::handleShow);
    }

    private static void handleShow(Event event)
    {
        if (!(event.widget instanceof Shell shell) || shell.isDisposed())
            return;
        if (Boolean.TRUE.equals(shell.getData(SHELL_HANDLED_KEY)))
            return;

        if (!(shell.getData() instanceof IThreeSideTextMergeViewerProvider provider))
            return;

        shell.setData(SHELL_HANDLED_KEY, Boolean.TRUE);
        scheduleAttach(shell, provider, 0);
    }

    private static void scheduleAttach(Shell shell, IThreeSideTextMergeViewerProvider provider, int attempt)
    {
        if (shell.isDisposed())
            return;
        if (attempt >= MAX_ATTEMPTS)
        {
            log("attach: не удалось найти merge-вьюер после " + MAX_ATTEMPTS + " попыток"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        shell.getDisplay().timerExec(attempt == 0 ? 100 : RETRY_DELAY_MS, () ->
        {
            if (!tryAttach(provider))
                scheduleAttach(shell, provider, attempt + 1);
        });
    }

    private static boolean tryAttach(IThreeSideTextMergeViewerProvider provider)
    {
        ThreeSideTextMergeViewer viewer = provider.getMergeViewer();
        if (viewer == null)
            return false;
        Control viewerControl = viewer.getControl();
        if (viewerControl == null || viewerControl.isDisposed())
            return false;

        Control viewFormControl = findViewFormAncestor(viewerControl);
        if (viewFormControl == null)
            return false;
        Composite mergeViewerComposite = viewFormControl.getParent();
        if (mergeViewerComposite == null)
            return false;

        attach(mergeViewerComposite, viewFormControl, provider, viewer);
        return true;
    }

    /**
     * И «Настройка объединения модулей», и «Объединение» кладут вьюер сравнения внутрь
     * {@code ThreeSideTextMergeViewerPanel} ({@code extends ViewForm}) — поднимаемся
     * от контрола вьюера до этого {@link ViewForm} и берём его родителя. Не зависит от
     * того, что именно вокруг ({@link org.eclipse.swt.custom.SashForm} с деревом секций
     * у структурированного диалога, либо обычный {@code Composite} у простого).
     */
    private static Control findViewFormAncestor(Control control)
    {
        for (Control c = control; c != null; c = c.getParent())
        {
            if (c instanceof ViewForm)
                return c;
        }
        return null;
    }

    /**
     * {@code mergeViewerComposite} — composite самого 1C (не наш), его {@code GridLayout}
     * может резервировать отступ сверху/снизу (marginHeight) и между строками
     * (verticalSpacing) — до вставки нашей панели там был один ребёнок ({@code ViewForm}),
     * и этот отступ не был заметен. После вставки второй строки (нашей панели) тот же
     * отступ появляется дважды — над и под панелью — и выглядит избыточным. Уменьшаем его
     * явно, не полагаясь на то, что 1C сам использует нулевые значения. Не связано с зазором
     * между кнопкой выбора вида объединения и её выпадающим меню (тот зазор — внутри чужого
     * {@code ThreeSideTextMergeViewerPanel.createTopLeft()}, оставлен как есть).
     */
    private static void shrinkVerticalGaps(Composite mergeViewerComposite)
    {
        if (mergeViewerComposite.getLayout() instanceof GridLayout gridLayout)
        {
            gridLayout.marginHeight = 2;
            gridLayout.verticalSpacing = 2;
        }
    }

    /**
     * Только у «Настройка объединения модулей» (structured-диалог с деревом секций) —
     * {@code mergeViewerComposite} лежит внутри {@link SashForm} рядом с composite,
     * содержащим {@link DtComparisonView} (дерево объектов + группа «Статусы по
     * соответствиям объектов» + строка «Фильтр:», друг под другом в одну колонку).
     * У «Объединение» (без разбора структуры) дерева нет — метод тихо ничего не делает.
     *
     * <p>Переносим строку фильтра в ту же строку, что и группа статусов (вместо отдельной
     * строки под ней) — меняем {@code DtComparisonView.GridLayout} на 2 колонки: дерево
     * растягивается на обе (как и раньше), фильтр занимает место группы статусов — колонка 1
     * (слева), группа статусов сдвигается в колонку 2 (справа). {@code colorsLegend}/
     * {@code filterControl} — приватные поля {@code DtComparisonView} без геттеров, только
     * через рефлексию.
     */
    private static void relocateFilterRow(Composite mergeViewerComposite)
    {
        if (!(mergeViewerComposite.getParent() instanceof SashForm sashForm) || sashForm.isDisposed())
            return;
        if (Boolean.TRUE.equals(sashForm.getData(FILTER_ROW_RELAID_KEY)))
            return;

        DtComparisonView comparisonView = null;
        for (Control sashChild : sashForm.getChildren())
        {
            if (sashChild == mergeViewerComposite || !(sashChild instanceof Composite comparisonViewComposite))
                continue;
            for (Control c : comparisonViewComposite.getChildren())
                if (c instanceof DtComparisonView view)
                {
                    comparisonView = view;
                    break;
                }
        }
        if (comparisonView == null)
        {
            log("relocateFilterRow: DtComparisonView не найден"); //$NON-NLS-1$
            return;
        }

        Object legendObj = Global.getField(comparisonView, "colorsLegend"); //$NON-NLS-1$
        Object filterObj = Global.getField(comparisonView, "filterControl"); //$NON-NLS-1$
        if (!(legendObj instanceof Group legend) || !(filterObj instanceof Composite filter)
            || !(comparisonView.getLayout() instanceof GridLayout viewLayout))
        {
            log("relocateFilterRow: не удалось извлечь colorsLegend/filterControl/GridLayout"); //$NON-NLS-1$
            return;
        }

        sashForm.setData(FILTER_ROW_RELAID_KEY, Boolean.TRUE);
        viewLayout.numColumns = 2;

        GridData treeData = new GridData(SWT.FILL, SWT.FILL, true, true);
        treeData.horizontalSpan = 2;
        comparisonView.getTreeControl().setLayoutData(treeData);

        /*
         * GridLayout раскладывает детей в порядке их следования в composite (не по
         * GridData) — colorsLegend создан раньше filterControl (см. конструктор
         * DtComparisonView), поэтому без явной перестановки он и остался бы в колонке 1
         * (слева). Меняем местами: filterControl — на место колонки 1 (там же, где раньше
         * была группа статусов), colorsLegend — в колонку 2, справа.
         */
        filter.moveAbove(legend);

        /*
         * grab=true у любого из двух растягивает саму КОЛОНКУ на всё свободное место — и тогда
         * компактный (не растянутый через align) сосед всё равно оказывается прижат к дальнему
         * краю этой растянутой колонки, то есть к правому краю родителя. Никакого grab: обе
         * колонки — natural size, legend просто стоит вплотную справа от filter, без зазора
         * и без растяжения чего-либо.
         */
        filter.setLayoutData(new GridData(SWT.LEFT, SWT.BOTTOM, false, false));
        legend.setLayoutData(new GridData(SWT.LEFT, SWT.BOTTOM, false, false));

        comparisonView.layout(true, true);
    }

    private static void attach(Composite mergeViewerComposite, Control viewFormControl,
        IThreeSideTextMergeViewerProvider provider, ThreeSideTextMergeViewer viewer)
    {
        if (mergeViewerComposite.isDisposed())
            return;
        if (Boolean.TRUE.equals(mergeViewerComposite.getData(PANEL_ATTACHED_KEY)))
            return;
        mergeViewerComposite.setData(PANEL_ATTACHED_KEY, Boolean.TRUE);

        /*
         * Зазор между верхней панелью инструментов (topLeft — выбор вида объединения) и
         * содержимым (панели сравнения) ViewForm — публичное поле самого SWT
         * (org.eclipse.swt.custom.ViewForm.verticalSpacing, по умолчанию 1px), а не что-то
         * приватное 1C — увеличиваем на пару пикселей штатным способом. viewFormControl —
         * тот же {@code ThreeSideTextMergeViewerPanel}, что переживает переключение варианта
         * объединения (пересоздаётся только внутренний вьюер) — без метки-маркера значение
         * накапливалось бы на +2 при каждом переприсоединении.
         */
        if (viewFormControl instanceof ViewForm viewForm
            && !Boolean.TRUE.equals(viewForm.getData(VIEWFORM_SPACING_ADJUSTED_KEY)))
        {
            viewForm.verticalSpacing += 2;
            viewForm.setData(VIEWFORM_SPACING_ADJUSTED_KEY, Boolean.TRUE);
        }

        shrinkVerticalGaps(mergeViewerComposite);
        relocateFilterRow(mergeViewerComposite);

        CompareCurrentLinesPanel panel = CompareCurrentLinesPanel.create(mergeViewerComposite,
            DEFAULT_LEFT_LABEL, DEFAULT_RIGHT_LABEL, DEFAULT_RESULT_LABEL);
        /*
         * Итоговая сторона в паре всегда «новая» (INSERT) — добавления должны быть зелёными,
         * удаления из источника — красными. Режим «слева зелёное / справа красное» нужен
         * только 2-way EGit (см. GitCompareCurrentLinesHook).
         */
        panel.setSideAlignedDiffColors(false);
        refreshLabels(panel, provider, viewer);
        Composite panelControl = panel.getControl();
        panelControl.moveBelow(viewFormControl);

        viewFormControl.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        panelControl.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));
        mergeViewerComposite.layout(true, true);

        StyledText leftText = MergeViewerReflection.extractStyledText(viewer, "leftViewer"); //$NON-NLS-1$
        StyledText rightText = MergeViewerReflection.extractStyledText(viewer, "rightViewer"); //$NON-NLS-1$
        StyledText resultText = MergeViewerReflection.extractStyledText(viewer, "resultViewer"); //$NON-NLS-1$

        if (leftText == null || rightText == null || resultText == null)
            log("attach: не удалось извлечь один из StyledText (left=" + (leftText != null) //$NON-NLS-1$
                + " right=" + (rightText != null) + " result=" + (resultText != null) + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        ActivePair activePair = new ActivePair();
        panel.setCompareInIrSupplier(() -> supplyFullTextsForIr(panel, activePair));
        addCompareInIrToolbarAction(provider, viewer, panel, activePair);

        hookStyledText(leftText, panel, provider, viewer, leftText, rightText, resultText, activePair);
        hookStyledText(rightText, panel, provider, viewer, leftText, rightText, resultText, activePair);
        hookStyledText(resultText, panel, provider, viewer, leftText, rightText, resultText, activePair);

        StyledText initialSource = leftText != null ? leftText : rightText != null ? rightText : resultText;
        if (initialSource != null)
            syncThreeWayCurrentLines(initialSource, panel, provider, viewer, leftText, rightText, resultText, activePair);

        /*
         * Переключатель варианта объединения («Объединение встроенного языка»/«с учётом
         * семантики»/«текста», топ-левое меню ViewForm) пересоздаёт вьюер — decompiled
         * {@code ThreeSideTextMergeViewerPanel.updateViewer()}: уничтожает control СТАРОГО
         * вьюера и создаёт НОВЫЙ {@code ThreeSideTextMergeViewer} с собственным
         * rightToolBarManager — наши кнопки (добавленные в toolbar СТАРОГО вьюера) и ссылки
         * на leftText/rightText/resultText (виджеты старого вьюера) становятся недействительны.
         * Наша панель (panelControl) при этом не уничтожается (она не внутри control'а вьюера,
         * а рядом, в mergeViewerComposite) — но её содержимое протухает без переприсоединения.
         * На уничтожение control'а старого вьюера — переприсоединяемся с нуля.
         */
        Control oldViewerControl = viewer.getControl();
        if (oldViewerControl != null && !oldViewerControl.isDisposed())
        {
            Shell shell = mergeViewerComposite.getShell();
            oldViewerControl.addDisposeListener(e ->
            {
                if (!mergeViewerComposite.isDisposed())
                    mergeViewerComposite.setData(PANEL_ATTACHED_KEY, null);
                if (!panelControl.isDisposed())
                    panelControl.dispose();
                if (shell != null && !shell.isDisposed())
                    scheduleAttach(shell, provider, 0);
            });
        }
    }

    /**
     * Добавляет «Сравнить ИР» в левый край панели инструментов правой стороны
     * ({@code ThreeSideTextMergeViewer.rightToolBarManager} — приватное поле, тот же
     * {@link IToolBarManager}, что уже содержит штатные кнопки — навигацию по различиям,
     * копирование и т.п.). Не {@code ViewForm.setTopRight()}: это отдельная, куда более
     * заметная область над всей панелью — не «вместе с обычными кнопками», как просили.
     *
     * <p>{@code IToolBarManager} не даёт прямого «добавить в начало» без ID существующего
     * элемента — пересобираем список: наш пункт первым, затем то, что уже было.
     */
    private static void addCompareInIrToolbarAction(IThreeSideTextMergeViewerProvider provider,
        ThreeSideTextMergeViewer viewer, CompareCurrentLinesPanel panel, ActivePair activePair)
    {
        Object managerObj = Global.getField(viewer, "rightToolBarManager"); //$NON-NLS-1$
        if (!(managerObj instanceof IToolBarManager toolBarManager))
            return;

        IContributionItem[] existingItems = toolBarManager.getItems();
        toolBarManager.removeAll();
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

        Action showInModuleAction = new Action(ShowInModuleHandler.MENU_LABEL)
        {
            @Override
            public void run()
            {
                showInModule(provider, viewer, activePair);
            }
        };
        ImageDescriptor showInModuleIcon = ShowInModuleHandler.iconDescriptor();
        if (showInModuleIcon != null)
        {
            showInModuleAction.setImageDescriptor(showInModuleIcon);
            showInModuleAction.setText(""); //$NON-NLS-1$
        }
        showInModuleAction.setToolTipText(
            ShowInModuleHandler.MENU_LABEL + " — " + ShowInModuleHandler.TOOLTIP + Global.pluginSignForTooltip()); //$NON-NLS-1$
        toolBarManager.add(showInModuleAction);

        toolBarManager.add(panel.createVisibilityToggleAction());
        toolBarManager.add(new Separator());
        for (IContributionItem item : existingItems)
            toolBarManager.add(item);

        toolBarManager.update(true);
    }

    /**
     * «Показать в модуле» — целевая сторона та же, что и у «Сравнить ИР»
     * ({@code activePair.indexA}, всегда LEFT или RIGHT — никогда RESULT, см.
     * {@link #syncThreeWayCurrentLines}: пара для ИР всегда «левая/правая ↔ итоговая»).
     * Итоговая (Result) сторона своего модуля не имеет (временный буфер слияния) —
     * поэтому, когда каретка в ней, {@code activePair.indexA} уже указывает на LEFT
     * («вашу» сторону) — вести некуда, кроме как в MAIN, что и требуется.
     *
     * <p>Диалог «Настройка объединения модулей» — только кнопка OK, close() всегда
     * сохраняет настройки; спрашиваем подтверждение «Да/Отмена». Диалог «Объединение» —
     * OK (сохранить+применить) и Cancel (отменить) — спрашиваем «Сохранить/Не
     * сохранять/Отмена» и симулируем нажатие соответствующей кнопки.
     */
    private static void showInModule(IThreeSideTextMergeViewerProvider provider, ThreeSideTextMergeViewer viewer,
        ActivePair activePair)
    {
        StyledText activeWidget = activePair.widgetA;
        if (activeWidget == null || activeWidget.isDisposed())
            return;
        try
        {
            ComparisonSide side = activePair.indexA == RIGHT ? ComparisonSide.OTHER : ComparisonSide.MAIN;
            IFile file = resolveModuleFile(provider, viewer, side);
            if (file == null)
            {
                log("showInModule: не удалось определить реальный файл модуля"); //$NON-NLS-1$
                return;
            }
            int line1Based = CompareLineRangeMatcher.lineAtCaret(activeWidget) + 1;

            if (!(provider instanceof Window dialogWindow) || dialogWindow.getShell() == null)
                return;
            Shell dialogShell = dialogWindow.getShell();

            boolean structured = Global.getField(provider, "node") != null; //$NON-NLS-1$
            if (structured)
            {
                ModalSaveCloseHelper.Choice choice = ModalSaveCloseHelper.confirmClose(dialogShell,
                    "Закрыть окно и сохранить настройки объединения?"); //$NON-NLS-1$
                if (choice != ModalSaveCloseHelper.Choice.PROCEED)
                    return;
                dialogWindow.close();
            }
            else
            {
                ModalSaveCloseHelper.SaveChoice choice = ModalSaveCloseHelper.confirmSaveClose(dialogShell,
                    "Сохранить изменения объединения перед переходом в модуль?"); //$NON-NLS-1$
                if (choice == ModalSaveCloseHelper.SaveChoice.CANCEL)
                    return;
                // buttonPressed(0)/(1) — те же id, что у штатных OK/Cancel, тот же путь сохранения/отмены.
                Global.invokeVoid(provider, "buttonPressed", //$NON-NLS-1$
                    choice == ModalSaveCloseHelper.SaveChoice.SAVE ? 0 : 1);
            }

            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window != null ? window.getActivePage() : null;
            if (page == null)
                return;
            ShowInModuleHandler.openBslModule(file, line1Based, page, window.getShell());
        }
        catch (Exception e)
        {
            Global.tempLogException("showInModule", "ThreeSideMergeCurrentLinesHook", e); //$NON-NLS-1$ //$NON-NLS-2$
            ToastNotification.show(ShowInModuleHandler.MENU_LABEL,
                "Ошибка перехода в модуль: " + e, 6000); //$NON-NLS-1$
        }
    }

    /**
     * У «Настройка объединения модулей» (structured) — путь через дерево секций:
     * приватное поле {@code node} диалога ({@link IPartialModelNode}, публичный тип) →
     * {@code retrieveComparisonNode()} → {@link BslModuleComparisonNode} → символическая
     * ссылка стороны ({@code getSymlink(ComparisonSide)}, наследуется от
     * {@code SymlinkComparisonNode} через цепочку {@code ExternalPropertyComparisonNode
     * → TopModelObjectsComparisonNode → TopComparisonNode}) → путь файла через
     * {@code IComparisonDataSource.getPath(symlink, BslPackage.Literals.MODULE)}.
     *
     * <p>У «Объединение» (plain, дерева нет — поля {@code node} не существует) —
     * запасной путь через {@code viewer.getInput()} (штатный {@code Viewer.getInput()}) →
     * {@link IThreeSideTextMergeInput#getLeft()}/{@code getRight()} → {@code ITypedElement},
     * чей конкретный класс (внутренний {@code BslModuleTypedElement}) хранит приватные
     * {@code path}/{@code dataSource} без геттеров — только через рефлексию.
     */
    private static IFile resolveModuleFile(IThreeSideTextMergeViewerProvider provider,
        ThreeSideTextMergeViewer viewer, ComparisonSide side)
    {
        IFile file = resolveViaPartialModelNode(provider, side);
        if (file != null)
            return file;
        return resolveViaTypedElement(viewer, side);
    }

    private static IFile resolveViaPartialModelNode(IThreeSideTextMergeViewerProvider provider, ComparisonSide side)
    {
        if (!(Global.getField(provider, "node") instanceof IPartialModelNode partialModelNode)) //$NON-NLS-1$
            return null;
        try
        {
            ComparisonNode comparisonNode = partialModelNode.retrieveComparisonNode();
            if (!(comparisonNode instanceof BslModuleComparisonNode moduleNode))
                return null;
            String symlink = moduleNode.getSymlink(side);
            if (symlink == null)
                return null;
            IComparisonSession session = partialModelNode.getComparisonSession();
            IComparisonDataSource dataSource = session != null ? session.getDataSource(side) : null;
            return dataSource != null ? fileFromDataSource(dataSource, symlink) : null;
        }
        catch (RuntimeException e)
        {
            log("resolveViaPartialModelNode: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static IFile resolveViaTypedElement(ThreeSideTextMergeViewer viewer, ComparisonSide side)
    {
        if (!(viewer.getInput() instanceof IThreeSideTextMergeInput mergeInput))
            return null;
        ITypedElement element = side == ComparisonSide.OTHER ? mergeInput.getRight() : mergeInput.getLeft();
        if (element == null)
            return null;
        try
        {
            Object pathObj = Global.getField(element, "path"); //$NON-NLS-1$
            Object dataSourceObj = Global.getField(element, "dataSource"); //$NON-NLS-1$
            if (!(pathObj instanceof Path path)
                || !(dataSourceObj instanceof IComparisonDataSource dataSource))
                return null;
            return fileFromProject(dataSource, path.toString());
        }
        catch (RuntimeException e)
        {
            log("resolveViaTypedElement: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static IFile fileFromDataSource(IComparisonDataSource dataSource, String symlink)
    {
        String relPath = dataSource.getPath(symlink, BslPackage.Literals.MODULE);
        return relPath != null ? fileFromProject(dataSource, relPath) : null;
    }

    private static IFile fileFromProject(IComparisonDataSource dataSource, String relPath)
    {
        IDtProject dtProject = dataSource.getDtProject();
        IProject project = dtProject != null ? dtProject.getWorkspaceProject() : null;
        if (project == null)
            return null;
        IFile file = project.getFile(relPath.replace('\\', '/'));
        return file.exists() ? file : null;
    }

    /** Пара сторон, чья раскраска сейчас активна — источник для кнопки «Сравнить ИР» (полные тексты). */
    private static final class ActivePair
    {
        volatile StyledText widgetA;
        volatile StyledText widgetB;
        volatile int indexA = -1;
        volatile int indexB = -1;
    }

    /** Полные тексты текущей раскрашенной пары (не текущей строки) — для кнопки «Сравнить ИР». */
    private static CompareCurrentLinesPanel.FullTextPair supplyFullTextsForIr(
        CompareCurrentLinesPanel panel, ActivePair activePair)
    {
        StyledText a = activePair.widgetA;
        StyledText b = activePair.widgetB;
        if (a == null || a.isDisposed() || b == null || b.isDisposed())
            return null;
        String labelA = panel.getLabelText(activePair.indexA);
        String labelB = panel.getLabelText(activePair.indexB);
        String title = labelA + " / " + labelB; //$NON-NLS-1$
        // Оба диалога — исключительно сравнение/слияние модулей BSL.
        return new CompareCurrentLinesPanel.FullTextPair(a.getText(), b.getText(), title, labelA, labelB,
            IrCompareValuesHandler.syntaxVariantFor("bsl"), //$NON-NLS-1$
            CompareLineRangeMatcher.lineAtCaret(a), CompareLineRangeMatcher.lineAtCaret(b));
    }

    /**
     * Заголовки сторон, обновляются при каждой синхронизации (могут меняться при
     * переключении узла дерева секций):
     * <ul>
     *   <li>Левая/правая — из полей {@code mainComparisonSideName}/{@code otherComparisonSideName}
     *   диалога (protected-поля {@code AbstractCompareBslModuleWithParsingModuleStructureDialog}) —
     *   это заголовки колонок дерева объектов («Конфигурация»/«Конфигурация1»), а не имя
     *   выбранной строки дерева. Если таких полей нет (диалог «Объединение» без разбора
     *   структуры — в нём дерева объектов нет) — берём текст в скобках из
     *   {@code ThreeSideTextMergeViewer.leftLabel}/{@code rightLabel} (например,
     *   «Модуль.Модуль1 (Конфигурация)» → «Конфигурация»).</li>
     *   <li>Итоговая — всегда текст в скобках из {@code ThreeSideTextMergeViewer.resultLabel}
     *   (скобок там обычно нет — тогда используется текст целиком).</li>
     * </ul>
     */
    private static void refreshLabels(CompareCurrentLinesPanel panel, Object dialog, ThreeSideTextMergeViewer viewer)
    {
        String leftFromDialog = asNonBlankString(Global.getField(dialog, "mainComparisonSideName")); //$NON-NLS-1$
        String rightFromDialog = asNonBlankString(Global.getField(dialog, "otherComparisonSideName")); //$NON-NLS-1$

        String leftLabel = leftFromDialog != null ? leftFromDialog
            : extractInsideParentheses(MergeViewerReflection.extractLabelText(viewer, "leftLabel")); //$NON-NLS-1$
        String rightLabel = rightFromDialog != null ? rightFromDialog
            : extractInsideParentheses(MergeViewerReflection.extractLabelText(viewer, "rightLabel")); //$NON-NLS-1$
        String resultLabel = extractInsideParentheses(MergeViewerReflection.extractLabelText(viewer, "resultLabel")); //$NON-NLS-1$

        panel.setLabelText(LEFT, labelOrDefault(withColon(leftLabel), DEFAULT_LEFT_LABEL));
        panel.setLabelText(RIGHT, labelOrDefault(withColon(rightLabel), DEFAULT_RIGHT_LABEL));
        panel.setLabelText(RESULT, labelOrDefault(withColon(resultLabel), DEFAULT_RESULT_LABEL));
    }

    private static String asNonBlankString(Object value)
    {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /** {@code "Модуль.Модуль1 (Конфигурация)"} → {@code "Конфигурация"}; без скобок — текст как есть. */
    private static String extractInsideParentheses(String text)
    {
        if (text == null)
            return null;
        int open = text.indexOf('(');
        int close = open >= 0 ? text.indexOf(')', open + 1) : -1;
        return close > open ? text.substring(open + 1, close).trim() : text;
    }

    private static String withColon(String text)
    {
        if (text == null)
            return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty())
            return null;
        return trimmed.endsWith(":") ? trimmed : trimmed + ":"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String labelOrDefault(String text, String fallback)
    {
        return text != null && !text.isBlank() ? text : fallback;
    }

    private static void hookStyledText(StyledText styledText, CompareCurrentLinesPanel panel, Object dialog,
        ThreeSideTextMergeViewer viewer, StyledText leftText, StyledText rightText, StyledText resultText,
        ActivePair activePair)
    {
        if (styledText == null || styledText.isDisposed())
            return;
        if (Boolean.TRUE.equals(styledText.getData(HOOK_MARKER_KEY)))
            return;
        styledText.setData(HOOK_MARKER_KEY, Boolean.TRUE);

        styledText.addCaretListener(e ->
            syncThreeWayCurrentLines(styledText, panel, dialog, viewer, leftText, rightText, resultText, activePair));
        styledText.addListener(SWT.Modify, e ->
            syncThreeWayCurrentLines(styledText, panel, dialog, viewer, leftText, rightText, resultText, activePair));
    }

    /**
     * Раскрашивает пару «сторона под кареткой ↔ итоговая» (или «левая ↔ итоговая»,
     * если каретка в итоговой панели); третья сторона — обычным текстом сопоставленной
     * строки, либо пусто, если строк не сопоставлена. Заодно запоминает эту пару в
     * {@code activePair} — источник для кнопки «Сравнить ИР» (полные тексты).
     */
    private static void syncThreeWayCurrentLines(StyledText source, CompareCurrentLinesPanel panel, Object dialog,
        ThreeSideTextMergeViewer viewer, StyledText leftText, StyledText rightText, StyledText resultText,
        ActivePair activePair)
    {
        if (leftText == null || leftText.isDisposed()
            || rightText == null || rightText.isDisposed()
            || resultText == null || resultText.isDisposed())
            return;

        refreshLabels(panel, dialog, viewer);

        int primaryIdx;
        int colorPartnerIdx;
        int plainIdx;
        StyledText primaryWidget;
        StyledText colorPartnerWidget;
        StyledText plainWidget;

        if (source == leftText)
        {
            primaryIdx = LEFT;
            primaryWidget = leftText;
            colorPartnerIdx = RESULT;
            colorPartnerWidget = resultText;
            plainIdx = RIGHT;
            plainWidget = rightText;
        }
        else if (source == rightText)
        {
            primaryIdx = RIGHT;
            primaryWidget = rightText;
            colorPartnerIdx = RESULT;
            colorPartnerWidget = resultText;
            plainIdx = LEFT;
            plainWidget = leftText;
        }
        else if (source == resultText)
        {
            primaryIdx = RESULT;
            primaryWidget = resultText;
            colorPartnerIdx = LEFT;
            colorPartnerWidget = leftText;
            plainIdx = RIGHT;
            plainWidget = rightText;
        }
        else
            return;

        /*
         * Для кнопки «Сравнить ИР» пара всегда «левая/правая ↔ итоговая» (не «итоговая ↔
         * левая/правая») — даже если каретка сейчас в итоговой панели (тогда primary=RESULT,
         * colorPartner=LEFT) и раскраска строится в обратном порядке ради цвета (см. ниже).
         */
        if (primaryIdx == RESULT)
        {
            activePair.widgetA = colorPartnerWidget;
            activePair.widgetB = primaryWidget;
            activePair.indexA = colorPartnerIdx;
            activePair.indexB = primaryIdx;
        }
        else
        {
            activePair.widgetA = primaryWidget;
            activePair.widgetB = colorPartnerWidget;
            activePair.indexA = primaryIdx;
            activePair.indexB = colorPartnerIdx;
        }

        int primaryLine = CompareLineRangeMatcher.lineAtCaret(primaryWidget);
        String primaryLineText = CompareLineRangeMatcher.lineOrEmpty(primaryWidget, primaryLine);

        int colorPartnerLine = CompareLineRangeMatcher.findMatchedLine(primaryWidget, primaryLine, colorPartnerWidget);
        String colorPartnerLineText = colorPartnerLine >= 0
            ? CompareLineRangeMatcher.lineOrEmpty(colorPartnerWidget, colorPartnerLine)
            : null;

        int plainLine = CompareLineRangeMatcher.findMatchedLine(primaryWidget, primaryLine, plainWidget);
        String plainLineText = plainLine >= 0 ? CompareLineRangeMatcher.lineOrEmpty(plainWidget, plainLine) : ""; //$NON-NLS-1$

        if (colorPartnerLineText == null)
        {
            panel.renderPlain(primaryIdx, primaryLineText);
            panel.renderPlain(colorPartnerIdx, null);
            panel.renderPlain(plainIdx, plainLineText);
            panel.resetScroll();
            return;
        }

        /*
         * Итоговая сторона (результат слияния) — всегда «новая» относительно левой/правой,
         * независимо от того, какая панель сейчас под кареткой: иначе при активной итоговой
         * панели фрагмент, добавленный в неё, красился бы как «удаление» (красным) вместо
         * «вставка» (зелёным) — align(text1, text2) считает различия относительно порядка
         * аргументов, а не по смыслу «что было добавлено».
         */
        int oldIdx;
        int newIdx;
        String oldText;
        String newText;
        if (primaryIdx == RESULT)
        {
            oldIdx = colorPartnerIdx;
            oldText = colorPartnerLineText;
            newIdx = primaryIdx;
            newText = primaryLineText;
        }
        else
        {
            oldIdx = primaryIdx;
            oldText = primaryLineText;
            newIdx = colorPartnerIdx;
            newText = colorPartnerLineText;
        }

        CompareCurrentLineDiff.AlignedResult aligned = panel.renderPair(oldIdx, newIdx, oldText, newText);
        panel.renderPlain(plainIdx, plainLineText);

        // newIdx — это всегда RESULT (либо colorPartnerIdx=RESULT, либо primaryIdx=RESULT).
        panel.scrollToFirstDifference(panel.getRow(RESULT), aligned.rightTypes);
    }

    private static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, msg);
    }
}
