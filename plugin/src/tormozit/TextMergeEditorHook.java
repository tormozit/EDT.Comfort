package tormozit;

import java.nio.file.Path;
import java.util.List;

import com._1c.g5.v8.dt.bsl.compare.BslModuleComparisonNode;
import com._1c.g5.v8.dt.bsl.compare.BslModuleSectionComparisonNode;
import com._1c.g5.v8.dt.bsl.compare.ComparisonTextRegion;
import com._1c.g5.v8.dt.bsl.compare.TextRegion;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSource;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.ui.editor.ComparisonTreeControl;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.compare.ui.mergeviewer.IThreeSideTextMergeInput;
import com._1c.g5.v8.dt.compare.ui.mergeviewer.IThreeSideTextMergeViewerProvider;
import com._1c.g5.v8.dt.compare.ui.mergeviewer.ThreeSideTextMergeViewer;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.ExternalPropertyPartialModelNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IPartialModelNode;
import com._1c.g5.v8.dt.core.platform.IDtProject;

import org.eclipse.compare.ITypedElement;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.ViewForm;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.handlers.IHandlerActivation;
import org.eclipse.ui.handlers.IHandlerService;

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
public final class TextMergeEditorHook
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
    /** Имя стороны предка в попарном сравнении — как {@code CompareBslModuleWithParsingModuleStructureDialog_Parent_typed_element_name}. */
    private static final String ANCESTOR_SIDE_LABEL = "Общий родитель"; //$NON-NLS-1$
    private static final String RESULT_SIDE_LABEL = "Результат объединения модулей"; //$NON-NLS-1$

    private TextMergeEditorHook()
    {
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, TextMergeEditorHook::handleShow);
        MergeResultEditorKeys.installFocusFilter(display);
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
         * только 2-way EGit (см. CompareEditorCurrentLinesHook).
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
        /*
         * «Структура» — только у диалога «Объединение» (plain, без встроенного дерева секций).
         * У «Настройка объединения модулей» (structured) уже есть штатная панель структуры —
         * дублировать её не нужно. Тот же признак structured/plain, что и в showInModule
         * (поле {@code node} — IPartialModelNode — есть только у structured-диалога).
         */
        boolean structured = Global.getField(provider, "node") != null; //$NON-NLS-1$
        if (!structured && leftText != null && rightText != null)
        {
            String[] sideLabels = extractSideLabels(provider, viewer);
            createStructureController(viewFormControl, viewer, leftText, rightText, resultText, sideLabels[0],
                sideLabels[1]);
        }
        addCompareInIrToolbarAction(provider, viewer, panel, activePair);

        hookStyledText(leftText, panel, provider, viewer, leftText, rightText, resultText, activePair);
        hookStyledText(rightText, panel, provider, viewer, leftText, rightText, resultText, activePair);
        hookStyledText(resultText, panel, provider, viewer, leftText, rightText, resultText, activePair);

        MergeResultEditorKeys.install(MergeViewerReflection.extractSourceViewer(viewer, "resultViewer")); //$NON-NLS-1$

        if (structured)
            MethodLineRestore.install(provider, viewer, leftText, rightText, resultText);

        StyledText initialSource = leftText != null ? leftText : rightText != null ? rightText : resultText;
        if (initialSource != null)
            syncThreeWayCurrentLines(initialSource, panel, provider, viewer, leftText, rightText, resultText,
                activePair, false);

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
     * Панель структуры нужна МЕЖДУ штатным тулбаром {@code ViewForm} («Объединение встроенного
     * языка» — topLeft/topCenter самого {@code viewFormControl}) и текстом, а не НАД всем
     * {@code ViewForm} целиком (что было бы, просто добавь панель соседом в
     * {@code mergeViewerComposite}, как «Текущая строка») — оборачиваем текущее содержимое
     * {@code ViewForm} (сам вьюер, {@code viewer.getControl()}) в свой composite и подставляем
     * его через {@code ViewForm.setContent(...)}, как это уже делает
     * {@link CompareDialogCurrentLinesHook}/{@link CompareEditorCurrentLinesHook} с
     * {@code CompareViewerSwitchingPane}.
     */
    private static void createStructureController(Control viewFormControl,
        ThreeSideTextMergeViewer viewer, StyledText leftText, StyledText rightText, StyledText resultText,
        String leftLabel, String rightLabel)
    {
        if (!(viewFormControl instanceof ViewForm viewForm))
            return;
        Control viewerControl = viewer.getControl();
        if (viewerControl == null || viewerControl.isDisposed())
            return;

        Composite contentWrapper = new Composite(viewForm, SWT.NONE);
        GridLayout wrapperLayout = new GridLayout(1, false);
        wrapperLayout.marginWidth = 0;
        wrapperLayout.marginHeight = 0;
        contentWrapper.setLayout(wrapperLayout);

        viewerControl.setParent(contentWrapper);
        viewerControl.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        viewForm.setContent(contentWrapper);
        contentWrapper.getParent().layout(true, true);

        StructureToggleController controller = new StructureToggleController(contentWrapper, viewerControl,
            leftText, rightText, leftLabel, rightLabel, "3way"); //$NON-NLS-1$
        controller.setResultText(resultText);
        controller.setSourceViewers(MergeViewerReflection.extractSourceViewer(viewer, "leftViewer"), //$NON-NLS-1$
            MergeViewerReflection.extractSourceViewer(viewer, "rightViewer")); //$NON-NLS-1$
        /*
         * ПРОВЕРЕНО и ОШИБОЧНО: ThreeSideTextMergeViewer.leftToolBarManager/rightToolBarManager
         * (декомпилировано в .tmp/bundles/compare-ui/ThreeSideTextMergeViewer.javap-c.txt,
         * buildControls()) — это ДВЕ половины НИЖНЕЙ панели значков (та же строка, что и
         * «Сравнить ИР» из addCompareInIrToolbarAction ниже), а НЕ тулбар дропдауна
         * «Объединение встроенного языка» — это подтвердилось регрессией при реальном тесте
         * (кнопка уехала в нижнюю строку вместо места рядом с дропдауном). Дропдаун — это
         * ViewForm.topLeft самого viewFormControl, отдельно от ThreeSideTextMergeViewer.
         * Используем общий приём (см. StructureToggleController.placeToggleButtonAtViewFormTopLeft) —
         * оборачиваем topLeft вместе со своим ToolBar в общий composite.
         */
        StructureToggleController.placeToggleButtonAtViewFormTopLeft(viewForm, controller);
        CompareMethodHeader.install(contentWrapper, viewer, leftText, "leftLabel", //$NON-NLS-1$
            rightText, "rightLabel", resultText, "resultLabel"); //$NON-NLS-1$ //$NON-NLS-2$
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

        if (isMxlxViewerInput(viewer))
        {
            Action tabularAction = new Action(CompareTabularDocumentsInIr.MENU_LABEL)
            {
                @Override
                public void run()
                {
                    runTabularDocumentsCompare(provider, viewer);
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
        toolBarManager.add(OccurrencesToggleHook.createToggleAction());
        toolBarManager.add(new Separator());
        for (IContributionItem item : existingItems)
        {
            // Кнопка вхождений от прошлой сборки — иначе в панели два переключателя
            if (!OccurrencesToggleHook.isStaleToggleItem(item))
                toolBarManager.add(item);
        }

        toolBarManager.update(true);

        // Шелл обслужен: универсальный скан OccurrencesToggleHook не добавит свою
        // кнопку — она уже лежит рядом с «Текущие строки» (и уберём добавленную ранее)
        Control viewerControl = viewer.getControl();
        if (viewerControl != null && !viewerControl.isDisposed())
        {
            Shell shell = viewerControl.getShell();
            OccurrencesToggleHook.markShellHandled(shell);
            OccurrencesToggleHook.removeDialogItems(shell);

            /*
             * Кнопка «Показать в модуле» добавлена — с этого момента отслеживаем окно
             * для «Последних мест» (см. CompareRecentPlacesTracker). Метод — по позиции
             * пользователя: фокусная панель (итоговая/левая/правая), у секции дерева —
             * имя секции; см. resolveMergeRecentPlace.
             */
            CompareRecentPlacesTracker.track(shell,
                () -> resolveMergeRecentPlace(provider, viewer, activePair));
        }
    }

    /**
     * Место для «Последних мест» из 3-way окна объединения:
     * <ul>
     * <li>Секция дерева структурированного диалога — имя метода из узла секции
     *     (каретка панелей при выборе дерева не двигается и стоит на 0); имя
     *     подтверждается объявлением в файле модуля (отсекает не-методы).</li>
     * <li>Иначе — панель с фокусом: левая/правая → её файл и её каретка;
     *     итоговая → метод из её текста, файл стороны модуля (как у кнопки
     *     «Показать в модуле»; каретка панели-партнёра всегда на 0).</li>
     * <li>Без фокуса — пара activePair (как у кнопки «Сравнить ИР»).</li>
     * </ul>
     */
    private static CompareRecentPlacesTracker.Place resolveMergeRecentPlace(
        IThreeSideTextMergeViewerProvider provider, ThreeSideTextMergeViewer viewer, ActivePair activePair)
    {
        StyledText left = MergeViewerReflection.extractStyledText(viewer, "leftViewer"); //$NON-NLS-1$
        StyledText right = MergeViewerReflection.extractStyledText(viewer, "rightViewer"); //$NON-NLS-1$
        StyledText result = MergeViewerReflection.extractStyledText(viewer, "resultViewer"); //$NON-NLS-1$

        ComparisonSide moduleSide = activePair.indexA == RIGHT ? ComparisonSide.OTHER : ComparisonSide.MAIN;

        if (Global.getField(provider, "currentSelectedNode") instanceof IPartialModelNode sectionNode //$NON-NLS-1$
            && sectionNode.retrieveComparisonNode() instanceof BslModuleSectionComparisonNode section)
        {
            IFile sectionFile = resolveModuleFile(provider, viewer, moduleSide);
            if (sectionFile != null)
            {
                CompareRecentPlacesTracker.Place byName = CompareRecentPlacesTracker.forMethodName(sectionFile,
                    MethodLineRestore.sectionName(section, moduleSide), left, right, result);
                if (byName != null)
                    return byName;
                return new CompareRecentPlacesTracker.Place(sectionFile, null, null, left, right, result);
            }
        }

        StyledText primary = null;
        if (left != null && !left.isDisposed() && left.isFocusControl())
            primary = left;
        else if (right != null && !right.isDisposed() && right.isFocusControl())
            primary = right;
        else if (result != null && !result.isDisposed() && result.isFocusControl())
            primary = result;
        if (primary == null)
            primary = activePair.widgetA;
        if (primary == null || primary.isDisposed())
            return null;

        if (primary == result)
        {
            IFile resultFile = resolveModuleFile(provider, viewer, moduleSide);
            if (resultFile == null)
                return null;
            int line1 = CompareLineRangeMatcher.lineAtCaret(result) + 1;
            String method = GetRef.findEnclosingMethodName(new Document(result.getText()), line1);
            return new CompareRecentPlacesTracker.Place(resultFile, null, method, left, right, result);
        }

        ComparisonSide side = primary == right ? ComparisonSide.OTHER : ComparisonSide.MAIN;
        IFile sideFile = resolveModuleFile(provider, viewer, side);
        if (sideFile == null)
            return null;
        return new CompareRecentPlacesTracker.Place(sideFile, primary, null, left, right, result);
    }

    private static boolean isMxlxViewerInput(ThreeSideTextMergeViewer viewer)
    {
        if (!(viewer.getInput() instanceof IThreeSideTextMergeInput mergeInput))
            return false;
        return CompareTabularDocumentsInIr.isMxlxTypedElement(mergeInput.getLeft())
            || CompareTabularDocumentsInIr.isMxlxTypedElement(mergeInput.getRight())
            || CompareTabularDocumentsInIr.isMxlxTypedElement(mergeInput.getAncestor());
    }

    private static void runTabularDocumentsCompare(IThreeSideTextMergeViewerProvider provider,
        ThreeSideTextMergeViewer viewer)
    {
        if (!(viewer.getInput() instanceof IThreeSideTextMergeInput mergeInput))
            return;
        Path pathMain = CompareTabularDocumentsInIr.resolveSideFile(mergeInput.getLeft(), "main"); //$NON-NLS-1$
        Path pathOther = CompareTabularDocumentsInIr.resolveSideFile(mergeInput.getRight(), "other"); //$NON-NLS-1$
        Path pathAncestor = CompareTabularDocumentsInIr.resolveSideFile(mergeInput.getAncestor(), "ancestor"); //$NON-NLS-1$
        IDtProject dtProject = resolveDtProjectForMerge(provider, viewer);
        CompareTabularDocumentsInIr.runCompare(dtProject, pathMain, pathOther, pathAncestor, true);
    }

    private static IDtProject resolveDtProjectForMerge(IThreeSideTextMergeViewerProvider provider,
        ThreeSideTextMergeViewer viewer)
    {
        IFile file = resolveModuleFile(provider, viewer, ComparisonSide.MAIN);
        if (file == null)
            file = resolveModuleFile(provider, viewer, ComparisonSide.OTHER);
        if (file != null)
        {
            IDtProject fromFile = Global.getDtProjectFromWorkspaceProject(file.getProject());
            if (fromFile != null)
                return fromFile;
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        if (page == null)
            return null;
        IProject project = Global.getActiveProject(page, true);
        return project != null ? Global.getDtProjectFromWorkspaceProject(project) : null;
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
        String[] sideLabels = extractSideLabels(dialog, viewer);
        String resultLabel = CompareCurrentLinesPanel.sideLabelForCurrentLines(
            MergeViewerReflection.extractLabelText(viewer, "resultLabel")); //$NON-NLS-1$

        panel.setLabelText(LEFT, labelOrDefault(withColon(sideLabels[0]), DEFAULT_LEFT_LABEL));
        panel.setLabelText(RIGHT, labelOrDefault(withColon(sideLabels[1]), DEFAULT_RIGHT_LABEL));
        panel.setLabelText(RESULT, labelOrDefault(withColon(resultLabel), DEFAULT_RESULT_LABEL));
    }

    /**
     * Названия сторон 3-way диалога для попарного «Сравнение текста» (команды
     * «Сравнить текст слева и справа» и аналогичные). Штатный EDT в шапку 2-way кладёт
     * имя секции модуля с обеих сторон — здесь подставляются те же подписи, что в
     * колонках дерева / панели «Текущая строка» исходного окна.
     *
     * @return {@code {leftLabel, rightLabel}} или {@code null}, если родитель не 3-way
     *         либо стороны сопоставить не удалось
     */
    static String[] pairwiseLabelsFromParent(Shell twoWayShell, ITypedElement left, ITypedElement right)
    {
        if (twoWayShell == null || twoWayShell.isDisposed())
            return null;
        Shell threeWay = findThreeWayShell(twoWayShell, left, right);
        if (threeWay == null || threeWay.isDisposed())
            return null;
        if (!(threeWay.getData() instanceof IThreeSideTextMergeViewerProvider provider))
            return null;
        ThreeSideTextMergeViewer viewer = provider.getMergeViewer();
        if (viewer == null)
            return null;
        if (!(viewer.getInput() instanceof IThreeSideTextMergeInput mergeInput))
            return null;

        String[] sides = extractSideLabels(threeWay.getData(), viewer);
        String resultLabel = resolveResultSideLabel(viewer, mergeInput);
        String mappedLeft = mapPairwiseElement(left, mergeInput, sides[0], sides[1], resultLabel);
        String mappedRight = mapPairwiseElement(right, mergeInput, sides[0], sides[1], resultLabel);
        if (mappedLeft == null && mappedRight == null)
            return null;
        /*
         * Итоговая сторона в 2-way — {@code StringBasedTypedElement}, не тот же экземпляр,
         * что {@code mergeInput.getMergeResult()}. Несопоставленная сторона — она.
         */
        if (mappedLeft == null)
            mappedLeft = resultLabel;
        if (mappedRight == null)
            mappedRight = resultLabel;
        return new String[] { mappedLeft, mappedRight };
    }

    private static Shell findThreeWayShell(Shell twoWayShell, ITypedElement left, ITypedElement right)
    {
        for (Control c = twoWayShell.getParent(); c != null && !c.isDisposed(); c = c.getParent())
        {
            if (c instanceof Shell s && s.getData() instanceof IThreeSideTextMergeViewerProvider)
                return s;
        }
        Display display = twoWayShell.getDisplay();
        if (display == null || display.isDisposed())
            return null;
        Shell fallback = null;
        for (Shell s : display.getShells())
        {
            if (s == null || s.isDisposed() || s == twoWayShell)
                continue;
            if (!(s.getData() instanceof IThreeSideTextMergeViewerProvider provider))
                continue;
            ThreeSideTextMergeViewer viewer = provider.getMergeViewer();
            if (viewer == null)
                continue;
            if (!(viewer.getInput() instanceof IThreeSideTextMergeInput mergeInput))
                continue;
            if (containsSide(mergeInput, left) || containsSide(mergeInput, right))
                return s;
            if (fallback == null)
                fallback = s;
        }
        return fallback;
    }

    private static boolean containsSide(IThreeSideTextMergeInput mergeInput, ITypedElement element)
    {
        if (element == null)
            return false;
        return element == mergeInput.getLeft()
            || element == mergeInput.getRight()
            || element == mergeInput.getAncestor()
            || element == mergeInput.getMergeResult();
    }

    private static String mapPairwiseElement(ITypedElement element, IThreeSideTextMergeInput mergeInput,
        String leftLabel, String rightLabel, String resultLabel)
    {
        if (element == null)
            return null;
        if (element == mergeInput.getLeft())
            return leftLabel;
        if (element == mergeInput.getRight())
            return rightLabel;
        if (element == mergeInput.getAncestor())
            return ANCESTOR_SIDE_LABEL;
        if (element == mergeInput.getMergeResult())
            return resultLabel;
        return null;
    }

    private static String resolveResultSideLabel(ThreeSideTextMergeViewer viewer, IThreeSideTextMergeInput mergeInput)
    {
        String fromViewer = CompareCurrentLinesPanel.sideLabelForCurrentLines(
            MergeViewerReflection.extractLabelText(viewer, "resultLabel")); //$NON-NLS-1$
        if (isDistinctResultLabel(fromViewer, mergeInput))
            return fromViewer;
        ITypedElement result = mergeInput.getMergeResult();
        String fromElement = result != null ? result.getName() : null;
        if (isDistinctResultLabel(fromElement, mergeInput))
            return fromElement;
        return RESULT_SIDE_LABEL;
    }

    /** Итоговая подпись не должна совпадать с именем секции слева/справа (иначе снова «где какая сторона»). */
    private static boolean isDistinctResultLabel(String label, IThreeSideTextMergeInput mergeInput)
    {
        if (label == null || label.isBlank())
            return false;
        ITypedElement left = mergeInput.getLeft();
        ITypedElement right = mergeInput.getRight();
        String leftName = left != null ? left.getName() : null;
        String rightName = right != null ? right.getName() : null;
        return !label.equals(leftName) && !label.equals(rightName);
    }

    /**
     * Реальные названия сторон («Конфигурация»/«Конфигурация1» и т.п.), те же, что в заголовках
     * колонок дерева объектов / панели «Текущая строка» — см. {@link #refreshLabels}. Общий
     * для неё и для {@link #createStructureController} (комбо «Показывать только …» в
     * {@link CompareDialogStructurePanel} должно называть стороны так же, как везде в этом окне,
     * а не своими generic-подписями наподобие {@link #DEFAULT_LEFT_LABEL}).
     *
     * @return {@code {leftLabel, rightLabel}}, элементы могут быть {@code null}
     */
    private static String[] extractSideLabels(Object dialog, ThreeSideTextMergeViewer viewer)
    {
        String leftFromDialog = asNonBlankString(Global.getField(dialog, "mainComparisonSideName")); //$NON-NLS-1$
        String rightFromDialog = asNonBlankString(Global.getField(dialog, "otherComparisonSideName")); //$NON-NLS-1$

        String leftLabel = leftFromDialog != null ? leftFromDialog
            : CompareCurrentLinesPanel.sideLabelForCurrentLines(
                MergeViewerReflection.extractLabelText(viewer, "leftLabel")); //$NON-NLS-1$
        String rightLabel = rightFromDialog != null ? rightFromDialog
            : CompareCurrentLinesPanel.sideLabelForCurrentLines(
                MergeViewerReflection.extractLabelText(viewer, "rightLabel")); //$NON-NLS-1$
        return new String[] { leftLabel, rightLabel };
    }

    private static String asNonBlankString(Object value)
    {
        return value instanceof String s && !s.isBlank() ? s : null;
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
            syncThreeWayCurrentLines(styledText, panel, dialog, viewer, leftText, rightText, resultText, activePair,
                false));
        styledText.addListener(SWT.Modify, e ->
            syncThreeWayCurrentLines(styledText, panel, dialog, viewer, leftText, rightText, resultText, activePair,
                false));
        styledText.addListener(SWT.MouseUp, e ->
        {
            if (e.button == 1)
                syncThreeWayCurrentLines(styledText, panel, dialog, viewer, leftText, rightText, resultText,
                    activePair, true);
        });
    }

    /**
     * Раскрашивает пару «сторона под кареткой ↔ итоговая» (или «левая ↔ итоговая»,
     * если каретка в итоговой панели); третья сторона — обычным текстом сопоставленной
     * строки, либо пусто, если строк не сопоставлена. Заодно запоминает эту пару в
     * {@code activePair} — источник для кнопки «Сравнить ИР» (полные тексты).
     * При {@code revealOthers} (клик) ставит сопоставленные строки текущими в остальных
     * полях текста — тем же сопоставлением, что и панель.
     */
    private static void syncThreeWayCurrentLines(StyledText source, CompareCurrentLinesPanel panel, Object dialog,
        ThreeSideTextMergeViewer viewer, StyledText leftText, StyledText rightText, StyledText resultText,
        ActivePair activePair, boolean revealOthers)
    {
        if (CompareLineRangeMatcher.isActivating())
            return;
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
            revealMatchedInOthers(revealOthers, source, colorPartnerWidget, colorPartnerLine, plainWidget, plainLine);
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
        revealMatchedInOthers(revealOthers, source, colorPartnerWidget, colorPartnerLine, plainWidget, plainLine);
    }

    private static void revealMatchedInOthers(boolean reveal, StyledText source, StyledText first, int firstLine,
        StyledText second, int secondLine)
    {
        if (!reveal)
            return;
        if (firstLine >= 0)
            CompareLineRangeMatcher.revealMatchedLine(source, first, firstLine);
        if (secondLine >= 0)
            CompareLineRangeMatcher.revealMatchedLine(source, second, secondLine);
    }

    /**
     * В диалоге «Настройка объединения модулей»: переход с секции (метод/область) на корень
     * «Модуль» подгружает полный текст — восстанавливаем каретку на ту же строку, что была
     * активна в поле текста секции.
     *
     * <p>Снимок позиции берём в {@code Display}-фильтре {@link SWT#Selection} (до штатного
     * {@code nodeSelectionChanged} / {@code fireInputChange}); применение — в {@code asyncExec}
     * после замены документов. Слушатель {@code TreeViewer} регистрируется слишком поздно
     * и видел бы уже новый текст.
     */
    private static final class MethodLineRestore
    {
        private static final String TREE_KEY = "tormozit.moduleMergeMethodLineRestore"; //$NON-NLS-1$
        private static final String TRACK_KEY = "tormozit.moduleMergeMethodLineRestoreTrack"; //$NON-NLS-1$
        private static final int APPLY_MAX_ATTEMPTS = 20;
        private static final int APPLY_RETRY_MS = 50;

        private final IThreeSideTextMergeViewerProvider provider;
        private final Tree tree;
        private ThreeSideTextMergeViewer viewer;
        private StyledText leftText;
        private StyledText rightText;
        private StyledText resultText;
        private StyledText lastActive;
        private Listener selectionFilter;
        private Pending pending;

        private MethodLineRestore(IThreeSideTextMergeViewerProvider provider, Tree tree)
        {
            this.provider = provider;
            this.tree = tree;
        }

        static void install(IThreeSideTextMergeViewerProvider provider, ThreeSideTextMergeViewer viewer,
            StyledText leftText, StyledText rightText, StyledText resultText)
        {
            Tree tree = findStructureTree(provider);
            if (tree == null || tree.isDisposed())
                return;
            Object existing = tree.getData(TREE_KEY);
            MethodLineRestore restore;
            if (existing instanceof MethodLineRestore current)
                restore = current;
            else
            {
                restore = new MethodLineRestore(provider, tree);
                tree.setData(TREE_KEY, restore);
                restore.hookTree();
            }
            restore.bindViewers(viewer, leftText, rightText, resultText);
        }

        private static Tree findStructureTree(IThreeSideTextMergeViewerProvider provider)
        {
            Object viewObj = Global.getField(provider, "comparisonView"); //$NON-NLS-1$
            if (!(viewObj instanceof DtComparisonView comparisonView))
                return null;
            ComparisonTreeControl treeControl = comparisonView.getTreeControl();
            if (treeControl == null)
                return null;
            TreeViewer treeViewer = treeControl.getTreeViewer();
            return treeViewer != null ? treeViewer.getTree() : null;
        }

        private void bindViewers(ThreeSideTextMergeViewer viewer, StyledText leftText, StyledText rightText,
            StyledText resultText)
        {
            this.viewer = viewer;
            this.leftText = leftText;
            this.rightText = rightText;
            this.resultText = resultText;
            trackActive(leftText);
            trackActive(rightText);
            trackActive(resultText);
            if (lastActive == null || lastActive.isDisposed())
                lastActive = leftText != null ? leftText : rightText != null ? rightText : resultText;
        }

        private void trackActive(StyledText text)
        {
            if (text == null || text.isDisposed() || Boolean.TRUE.equals(text.getData(TRACK_KEY)))
                return;
            text.setData(TRACK_KEY, Boolean.TRUE);
            text.addCaretListener(e -> lastActive = text);
            text.addListener(SWT.FocusIn, e -> lastActive = text);
        }

        private void hookTree()
        {
            Display display = tree.getDisplay();
            if (display == null || display.isDisposed())
                return;
            selectionFilter = this::handleTreeSelection;
            display.addFilter(SWT.Selection, selectionFilter);
            tree.addDisposeListener(e ->
            {
                if (display != null && !display.isDisposed() && selectionFilter != null)
                    display.removeFilter(SWT.Selection, selectionFilter);
            });
        }

        private void handleTreeSelection(Event event)
        {
            if (event.widget != tree || event.detail == SWT.CHECK)
                return;
            Pending snapshot = captureIfSwitchingToRoot();
            if (snapshot == null)
                return;
            pending = snapshot;
            Display display = tree.getDisplay();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> applyPending(0));
        }

        private Pending captureIfSwitchingToRoot()
        {
            if (!(Global.getField(provider, "currentSelectedNode") instanceof IPartialModelNode oldNode)) //$NON-NLS-1$
                return null;
            ComparisonNode oldComparison = oldNode.retrieveComparisonNode();
            if (!(oldComparison instanceof BslModuleSectionComparisonNode section))
                return null;
            TreeItem[] selection = tree.getSelection();
            if (selection == null || selection.length == 0 || selection[0] == null || selection[0].isDisposed())
                return null;
            if (!(selection[0].getData() instanceof ExternalPropertyPartialModelNode))
                return null;

            StyledText source = resolveActiveText();
            if (source == null || source.isDisposed())
                return null;
            int pane = paneOf(source);
            if (pane < 0)
                return null;
            SourceViewer sourceViewer = sourceViewerOf(pane);
            String oldText;
            int caret;
            if (sourceViewer != null && sourceViewer.getDocument() != null)
            {
                oldText = sourceViewer.getDocument().get();
                caret = sourceViewer.getSelectedRange().x;
            }
            else
            {
                oldText = source.getText();
                caret = source.getCaretOffset();
            }
            if (oldText == null)
                oldText = ""; //$NON-NLS-1$
            caret = Math.max(0, Math.min(caret, oldText.length()));
            int relativeLine = 0;
            int column = 0;
            try
            {
                relativeLine = source.getLineAtOffset(Math.min(caret, source.getCharCount()));
                column = caret - source.getOffsetAtLine(relativeLine);
            }
            catch (RuntimeException ignored)
            {
            }
            return new Pending(pane, oldText, caret, relativeLine, Math.max(0, column), section);
        }

        private void applyPending(int attempt)
        {
            Pending snapshot = pending;
            if (snapshot == null || tree.isDisposed())
                return;
            Object currentObj = Global.getField(provider, "currentSelectedNode"); //$NON-NLS-1$
            ComparisonNode currentComparison = currentObj instanceof IPartialModelNode current
                ? current.retrieveComparisonNode() : null;
            if (currentComparison instanceof BslModuleSectionComparisonNode)
            {
                if (currentComparison == snapshot.section && attempt < APPLY_MAX_ATTEMPTS)
                {
                    Display display = tree.getDisplay();
                    if (display != null && !display.isDisposed())
                        display.timerExec(APPLY_RETRY_MS, () -> applyPending(attempt + 1));
                    return;
                }
                pending = null;
                return;
            }
            if (!(currentComparison instanceof BslModuleComparisonNode))
            {
                if (attempt < APPLY_MAX_ATTEMPTS)
                {
                    Display display = tree.getDisplay();
                    if (display != null && !display.isDisposed())
                        display.timerExec(APPLY_RETRY_MS, () -> applyPending(attempt + 1));
                    return;
                }
                pending = null;
                return;
            }

            StyledText target = textOf(snapshot.pane);
            SourceViewer sourceViewer = sourceViewerOf(snapshot.pane);
            if (target == null || target.isDisposed())
                return;
            String newText = sourceViewer != null && sourceViewer.getDocument() != null
                ? sourceViewer.getDocument().get() : target.getText();
            if (newText == null)
                newText = ""; //$NON-NLS-1$

            int offset = mapOffset(snapshot, newText);
            if (offset < 0)
            {
                if (attempt < APPLY_MAX_ATTEMPTS && newText.length() <= snapshot.oldText.length())
                {
                    Display display = tree.getDisplay();
                    if (display != null && !display.isDisposed())
                        display.timerExec(APPLY_RETRY_MS, () -> applyPending(attempt + 1));
                    return;
                }
                log("MethodLineRestore: не удалось сопоставить строку секции в полном модуле"); //$NON-NLS-1$
                pending = null;
                return;
            }
            pending = null;
            activateOffset(sourceViewer, target, offset);
        }

        private int mapOffset(Pending snapshot, String newText)
        {
            try
            {
                ComparisonSide side = sideOf(snapshot.pane);
                if (side != null)
                {
                    int viaRegions = offsetViaRegions(snapshot.section, side, snapshot.caretOffset);
                    if (viaRegions >= 0 && viaRegions <= newText.length())
                        return viaRegions;
                }
                int viaSubstring = offsetViaUniqueSubstring(snapshot.oldText, snapshot.caretOffset, newText);
                if (viaSubstring >= 0)
                    return viaSubstring;
                return offsetViaSectionName(snapshot, newText);
            }
            catch (RuntimeException e)
            {
                return -1;
            }
        }

        private static int offsetViaRegions(BslModuleSectionComparisonNode section, ComparisonSide side,
            int caretInSection)
        {
            List<ComparisonTextRegion> regions = section.getComparisonRegions();
            if (regions == null || regions.isEmpty())
                return -1;
            int remaining = Math.max(0, caretInSection);
            for (ComparisonTextRegion comparisonRegion : regions)
            {
                if (comparisonRegion == null)
                    continue;
                TextRegion region = comparisonRegion.getRegion(side);
                if (region == null)
                    continue;
                int length = Math.max(0, region.getLength());
                if (remaining <= length)
                    return region.getOffset() + remaining;
                remaining -= length;
            }
            return -1;
        }

        private static int offsetViaUniqueSubstring(String oldText, int caretOffset, String newText)
        {
            if (oldText == null || oldText.isEmpty() || newText == null)
                return -1;
            int first = newText.indexOf(oldText);
            if (first < 0)
                return -1;
            if (newText.indexOf(oldText, first + 1) >= 0)
                return -1;
            int offset = first + Math.max(0, Math.min(caretOffset, oldText.length()));
            return Math.min(offset, newText.length());
        }

        private int offsetViaSectionName(Pending snapshot, String newText)
        {
            String name = sectionName(snapshot.section, sideOf(snapshot.pane));
            if (name == null || name.isBlank())
                return -1;
            BslModuleStructureParser.ParseOutcome outcome = BslModuleStructureParser.parse(newText);
            if (outcome == null || outcome.root == null)
                return -1;
            BslModuleStructureParser.SectionNode found = findSectionByName(outcome.root, name);
            if (found == null)
                return -1;
            StyledText target = textOf(snapshot.pane);
            if (target == null || target.isDisposed())
                return found.offset;
            try
            {
                int startLine = target.getLineAtOffset(Math.min(found.offset, target.getCharCount()));
                int line = Math.min(startLine + snapshot.relativeLine, Math.max(0, target.getLineCount() - 1));
                int lineStart = target.getOffsetAtLine(line);
                int lineEnd = line + 1 < target.getLineCount() ? target.getOffsetAtLine(line + 1)
                    : target.getCharCount();
                return lineStart + Math.min(snapshot.column, Math.max(0, lineEnd - lineStart));
            }
            catch (RuntimeException e)
            {
                return found.offset;
            }
        }

        private static String sectionName(BslModuleSectionComparisonNode section, ComparisonSide side)
        {
            if (side != null)
            {
                String named = section.getName(side);
                if (named != null && !named.isBlank())
                    return named;
            }
            String main = section.getMainName();
            if (main != null && !main.isBlank())
                return main;
            String other = section.getOtherName();
            return other != null && !other.isBlank() ? other : section.getAncestorName();
        }

        private static BslModuleStructureParser.SectionNode findSectionByName(
            BslModuleStructureParser.SectionNode node, String name)
        {
            if (node == null || name == null)
                return null;
            if (name.equals(node.label))
                return node;
            for (BslModuleStructureParser.SectionNode child : node.children)
            {
                BslModuleStructureParser.SectionNode found = findSectionByName(child, name);
                if (found != null)
                    return found;
            }
            return null;
        }

        private static void activateOffset(SourceViewer sourceViewer, StyledText text, int offset)
        {
            if (text == null || text.isDisposed())
                return;
            int safe = Math.max(0, Math.min(offset, text.getCharCount()));
            if (sourceViewer != null)
                sourceViewer.setSelectedRange(safe, 0);
            else
                text.setSelectionRange(safe, 0);
            try
            {
                int line = text.getLineAtOffset(safe);
                int visibleLines = Math.max(1, text.getClientArea().height / Math.max(1, text.getLineHeight()));
                text.setTopIndex(Math.max(0, line - visibleLines / 3));
            }
            catch (RuntimeException ignored)
            {
            }
        }

        private StyledText resolveActiveText()
        {
            if (lastActive != null && !lastActive.isDisposed() && paneOf(lastActive) >= 0)
                return lastActive;
            return leftText != null && !leftText.isDisposed() ? leftText
                : rightText != null && !rightText.isDisposed() ? rightText
                : resultText != null && !resultText.isDisposed() ? resultText : null;
        }

        private int paneOf(StyledText text)
        {
            if (text == null)
                return -1;
            if (text == leftText)
                return LEFT;
            if (text == rightText)
                return RIGHT;
            if (text == resultText)
                return RESULT;
            return -1;
        }

        private StyledText textOf(int pane)
        {
            return switch (pane)
            {
                case LEFT -> leftText;
                case RIGHT -> rightText;
                case RESULT -> resultText;
                default -> null;
            };
        }

        private ComparisonSide sideOf(int pane)
        {
            return switch (pane)
            {
                case LEFT -> ComparisonSide.MAIN;
                case RIGHT -> ComparisonSide.OTHER;
                default -> null;
            };
        }

        private SourceViewer sourceViewerOf(int pane)
        {
            if (viewer == null)
                return null;
            String field = switch (pane)
            {
                case LEFT -> "leftViewer"; //$NON-NLS-1$
                case RIGHT -> "rightViewer"; //$NON-NLS-1$
                case RESULT -> "resultViewer"; //$NON-NLS-1$
                default -> null;
            };
            return field != null ? MergeViewerReflection.extractSourceViewer(viewer, field) : null;
        }

        private record Pending(int pane, String oldText, int caretOffset, int relativeLine, int column,
            BslModuleSectionComparisonNode section)
        {
        }
    }

    /**
     * Tab / Shift+Tab и «Переключить комментарий» в поле «результат объединения» —
     * как в редакторе модуля.
     *
     * <p>Поле объединения — это {@code SourceViewer}, сконфигурированный тем же
     * {@code XtextSourceViewerConfiguration}, что и редактор модуля (см. декомпиляцию
     * {@code XtextThreeSideTextMergeViewer.configureSourceViewer} в
     * {@code .tmp/bundles/xtext-compare-ui/}), но это не {@code ITextEditor} — штатных
     * действий редактора («Сдвиг вправо», {@code ToggleSLCommentAction}) и их привязок
     * клавиш здесь нет.
     *
     * <p>И сдвиг строк, и переключение комментария делаем своим кодом поверх
     * {@link StyledText}: операции вьюера ({@code SHIFT_RIGHT}/{@code SHIFT_LEFT},
     * {@code PREFIX}) доступны только при выделении нескольких строк, а поле результата
     * попарного сравнения — вообще виджет без доступного нам вьюера. Единица отступа и
     * префикс комментария берутся из настроек вьюера, когда он есть, — тогда они совпадают
     * с редактором модуля.
     */
    private static final class MergeResultEditorKeys
    {
        private static final String INSTALLED_KEY = "tormozit.threeSideMergeResultEditorKeys"; //$NON-NLS-1$
        private static final java.util.regex.Pattern LINE_DELIMITER =
            java.util.regex.Pattern.compile("\r\n|\r|\n"); //$NON-NLS-1$
        private static final String TOGGLE_COMMENT_COMMAND_ID = "org.eclipse.xtext.ui.ToggleCommentAction"; //$NON-NLS-1$
        private static final String XTEXT_EDITOR_SCOPE_ID = "org.eclipse.xtext.ui.XtextEditorScope"; //$NON-NLS-1$

        private MergeResultEditorKeys()
        {
        }

        /**
         * Ставится на КАЖДОЕ редактируемое текстовое поле окна объединения, а не только на
         * {@code resultViewer} трёхстороннего вьюера: поле «Результат объединения модулей»
         * попарного сравнения в диалоге «Настройка объединения модулей» — отдельный виджет,
         * до которого установка по одному полю не дотягивалась.
         */
        static void installFocusFilter(Display display)
        {
            display.addFilter(SWT.FocusIn, MergeResultEditorKeys::handleFocusIn);
        }

        private static void handleFocusIn(Event event)
        {
            if (!(event.widget instanceof StyledText text) || text.isDisposed())
                return;
            Shell shell = text.getShell();
            if (shell == null || !(shell.getData() instanceof IThreeSideTextMergeViewerProvider))
                return;
            if (text.getEditable())
                install(text, null);
        }

        static void install(SourceViewer viewer)
        {
            if (viewer != null)
                install(viewer.getTextWidget(), viewer);
        }

        /** {@code viewer} может быть {@code null} — тогда работаем только через сам виджет. */
        static void install(StyledText text, SourceViewer viewer)
        {
            if (text == null || text.isDisposed())
                return;
            if (Boolean.TRUE.equals(text.getData(INSTALLED_KEY)))
                return;
            text.setData(INSTALLED_KEY, Boolean.TRUE);

            /*
             * Без отмены обхода по Tab клавиша уводит фокус на соседний контрол и до
             * KeyDown не доходит (StyledText сам вставляет табуляцию только если обход
             * не состоялся).
             */
            text.addListener(SWT.Traverse, e ->
            {
                if ((e.detail == SWT.TRAVERSE_TAB_NEXT || e.detail == SWT.TRAVERSE_TAB_PREVIOUS)
                    && text.getEditable())
                    e.doit = false;
            });
            /*
             * ИМЕННО addVerifyKeyListener, а не слушатель SWT.KeyDown: свою обработку клавиш
             * StyledText делает во ВНУТРЕННЕМ слушателе SWT.KeyDown, зарегистрированном при
             * создании виджета, то есть РАНЬШЕ нашего. Подтверждено логом: на Tab сначала
             * verify range=216..390 new=\t (выделение уже заменено табуляцией), и только потом
             * наш KeyDown с пустым выделением. ST.VerifyKey вызывается до этой обработки, и
             * doit=false её отменяет.
             */
            text.addVerifyKeyListener(e -> handleVerifyKey(text, viewer, e));
            hookToggleCommentCommand(text, viewer);
        }

        /**
         * «Переключить комментарий» — не перехватом клавиши, а активацией контекста
         * {@link #XTEXT_EDITOR_SCOPE_ID} и обработчика команды на время, пока фокус в поле
         * результата.
         *
         * <p>Привязки клавиш разбирает фильтр воркбенча на уровне {@code Display} — он
         * срабатывает раньше слушателей виджета, поэтому перехват клавиши у себя не мешал
         * воркбенчу выполнить СВОЮ команду для того же сочетания (в диалоге, без контекста
         * редактора Xtext, это оказывалась «Свертывание» — она и всплывала в индикаторе
         * выполненных команд). С активным контекстом редактора воркбенч сам выбирает более
         * частную привязку — переключение комментария — и вызывает обработчик ниже.
         */
        private static void hookToggleCommentCommand(StyledText text, SourceViewer viewer)
        {
            IContextService contextService =
                PlatformUI.getWorkbench().getService(IContextService.class);
            IHandlerService handlerService =
                PlatformUI.getWorkbench().getService(IHandlerService.class);
            if (contextService == null || handlerService == null)
                return;

            IContextActivation[] context = new IContextActivation[1];
            IHandlerActivation[] handler = new IHandlerActivation[1];
            Listener deactivate = e ->
            {
                if (context[0] != null)
                {
                    contextService.deactivateContext(context[0]);
                    context[0] = null;
                }
                if (handler[0] != null)
                {
                    handlerService.deactivateHandler(handler[0]);
                    handler[0] = null;
                }
            };
            text.addListener(SWT.FocusIn, e ->
            {
                if (context[0] == null)
                    context[0] = contextService.activateContext(XTEXT_EDITOR_SCOPE_ID);
                if (handler[0] == null)
                    handler[0] = handlerService.activateHandler(TOGGLE_COMMENT_COMMAND_ID,
                        new ToggleCommentHandler(text, viewer));
            });
            text.addListener(SWT.FocusOut, deactivate);
            text.addListener(SWT.Dispose, deactivate);
        }

        private static final class ToggleCommentHandler extends AbstractHandler
        {
            private final StyledText text;
            private final SourceViewer viewer;

            ToggleCommentHandler(StyledText text, SourceViewer viewer)
            {
                this.text = text;
                this.viewer = viewer;
            }

            @Override
            public Object execute(ExecutionEvent event)
            {
                if (!text.isDisposed())
                    toggleComment(text, viewer);
                return null;
            }
        }

        private static void handleVerifyKey(StyledText text, SourceViewer viewer, VerifyEvent event)
        {
            if (event.keyCode != SWT.TAB && event.character != '\t')
                return;
            if (!event.doit || text.isDisposed() || !text.getEditable())
                return;
            if ((event.stateMask & (SWT.CTRL | SWT.ALT)) != 0)
                return;
            boolean back = (event.stateMask & SWT.SHIFT) != 0;
            /*
             * Tab без выделения (и с выделением внутри одной строки) — обычная вставка
             * табуляции, как в редакторе; сдвигаем блок только при выделении нескольких строк.
             * Shift+Tab снимает отступ всегда, в том числе по одной строке под кареткой.
             */
            if (!back && !isMultiLineSelection(text))
                return;
            if (shiftSelectedLines(text, viewer, back))
                event.doit = false;
        }

        /** Сдвиг строк выделения (или строки каретки) на один уровень отступа. */
        private static boolean shiftSelectedLines(StyledText text, SourceViewer viewer, boolean back)
        {
            String indent = resolveIndentUnit(viewer);
            int tabWidth = text.getTabs();
            return replaceLineBlock(text, line -> back
                ? line.substring(leadingIndentLength(line, tabWidth))
                : line.isBlank() ? line : indent + line);
        }

        /**
         * Применяет преобразование к каждой строке выделения и записывает результат ОДНОЙ
         * заменой — иначе правка распадается на несколько шагов отмены.
         *
         * <p>Работаем через сам {@link StyledText}, а не через {@code IDocument} вьюера:
         * поле результата попарного сравнения — виджет без доступного нам вьюера, а операции
         * {@code SHIFT_RIGHT}/{@code SHIFT_LEFT}/{@code PREFIX} вьюер и вовсе разрешает
         * только при выделении нескольких строк.
         */
        /**
         * Границы выделения. Не {@code getSelectionRange()}: в режиме блочного выделения он
         * возвращает только каретку (нулевую длину), а сами выделенные куски лежат в
         * {@code getSelectionRanges()}.
         */
        private static Point selectionSpan(StyledText text)
        {
            int[] ranges = text.getSelectionRanges();
            if (ranges != null && ranges.length >= 2)
            {
                int start = ranges[0];
                int end = ranges[ranges.length - 2] + ranges[ranges.length - 1];
                if (end > start)
                    return new Point(start, end - start);
            }
            return text.getSelectionRange();
        }

        private static boolean replaceLineBlock(StyledText text, java.util.function.UnaryOperator<String> lineOp)
        {
            Point selection = selectionSpan(text);
            int firstLine = text.getLineAtOffset(selection.x);
            int end = selection.x + Math.max(selection.y, 0);
            int lastLine = text.getLineAtOffset(end);
            if (selection.y > 0 && lastLine > firstLine && end == text.getOffsetAtLine(lastLine))
                lastLine--;

            int blockStart = text.getOffsetAtLine(firstLine);
            int blockEnd = text.getOffsetAtLine(lastLine) + text.getLine(lastLine).length();
            String block = text.getTextRange(blockStart, blockEnd - blockStart);

            StringBuilder result = new StringBuilder(block.length() + 16);
            boolean changed = false;
            int position = 0;
            java.util.regex.Matcher matcher = LINE_DELIMITER.matcher(block);
            while (matcher.find())
            {
                String line = block.substring(position, matcher.start());
                String shifted = lineOp.apply(line);
                changed |= !shifted.equals(line);
                result.append(shifted).append(matcher.group());
                position = matcher.end();
            }
            String lastLineText = block.substring(position);
            String shiftedLast = lineOp.apply(lastLineText);
            changed |= !shiftedLast.equals(lastLineText);
            result.append(shiftedLast);

            if (!changed)
                return false;
            text.replaceTextRange(blockStart, block.length(), result.toString());
            if (selection.y > 0)
                text.setSelectionRange(blockStart, result.length());
            else
                text.setCaretOffset(Math.max(blockStart,
                    Math.min(selection.x + result.length() - block.length(), blockStart + result.length())));
            return true;
        }

        /**
         * Единица отступа — из настроек вьюера ({@code TextViewer.fIndentChars}, заполняется из
         * {@code SourceViewerConfiguration.getIndentPrefixes}), как у штатного сдвига; без
         * вьюера — табуляция.
         */
        private static String resolveIndentUnit(SourceViewer viewer)
        {
            String indent = viewer != null
                ? resolvePrefix(Global.getField(viewer, "fIndentChars")) : null; //$NON-NLS-1$
            return indent != null ? indent : "\t"; //$NON-NLS-1$
        }

        private static int leadingIndentLength(String lineText, int tabWidth)
        {
            if (lineText.startsWith("\t")) //$NON-NLS-1$
                return 1;
            int spaces = 0;
            while (spaces < lineText.length() && spaces < Math.max(tabWidth, 1)
                && lineText.charAt(spaces) == ' ')
                spaces++;
            return spaces;
        }

        private static boolean isMultiLineSelection(StyledText text)
        {
            Point selection = selectionSpan(text);
            return selection.y > 0
                && text.getLineAtOffset(selection.x) != text.getLineAtOffset(selection.x + selection.y);
        }

        private static boolean toggleComment(StyledText text, SourceViewer viewer)
        {
            String prefix = resolveCommentPrefix(viewer);
            boolean commented = isSelectionCommented(text, prefix);
            return replaceLineBlock(text, line -> commented ? removePrefix(line, prefix)
                : line.isBlank() ? line : prefix + line);
        }

        private static String removePrefix(String line, String prefix)
        {
            int at = line.indexOf(prefix);
            return at < 0 || !line.substring(0, at).isBlank()
                ? line : line.substring(0, at) + line.substring(at + prefix.length());
        }

        /**
         * Префикс комментария — из самого вьюера ({@code TextViewer.fDefaultPrefixChars},
         * заполняется в {@code configure()} из {@code SourceViewerConfiguration.getDefaultPrefixes}),
         * чтобы совпадал с языком, на который вьюер настроен; без вьюера — «//» (окна
         * объединения модулей всегда про встроенный язык).
         */
        private static String resolveCommentPrefix(SourceViewer viewer)
        {
            String prefix = viewer != null
                ? resolvePrefix(Global.getField(viewer, "fDefaultPrefixChars")) : null; //$NON-NLS-1$
            return prefix != null ? prefix : "//"; //$NON-NLS-1$
        }

        /** Первый непустой префикс из карты «тип содержимого → префиксы» {@code TextViewer}. */
        private static String resolvePrefix(Object prefixes)
        {
            if (!(prefixes instanceof java.util.Map<?, ?> map) || map.isEmpty())
                return null;
            String prefix = firstNonEmpty(map.get(IDocument.DEFAULT_CONTENT_TYPE));
            if (prefix != null)
                return prefix;
            for (Object value : map.values())
            {
                prefix = firstNonEmpty(value);
                if (prefix != null)
                    return prefix;
            }
            return null;
        }

        private static String firstNonEmpty(Object prefixes)
        {
            if (!(prefixes instanceof String[] array))
                return null;
            for (String prefix : array)
            {
                if (prefix != null && !prefix.isEmpty())
                    return prefix;
            }
            return null;
        }

        /**
         * Как {@code ToggleSLCommentAction.isSelectionCommented}: снимаем комментарий,
         * только если закомментированы все непустые строки выделения (иначе — комментируем).
         */
        private static boolean isSelectionCommented(StyledText text, String prefix)
        {
            Point selection = selectionSpan(text);
            int firstLine = text.getLineAtOffset(selection.x);
            int end = selection.x + Math.max(selection.y, 0);
            int lastLine = text.getLineAtOffset(end);
            if (selection.y > 0 && lastLine > firstLine && end == text.getOffsetAtLine(lastLine))
                lastLine--;

            boolean hasContentLine = false;
            for (int line = firstLine; line <= lastLine; line++)
            {
                String lineText = text.getLine(line).trim();
                if (lineText.isEmpty())
                    continue;
                hasContentLine = true;
                if (!lineText.startsWith(prefix))
                    return false;
            }
            return hasContentLine;
        }
    }

    private static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, msg);
    }
}
