package tormozit;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.IContentProvider;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.ui.editor.XtextEditor;

import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.OperatorStyleCreator;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.ui.menu.BslHandlerUtil;

/**
 * Доработки штатной панели «Иерархия вызовов»:
 * <ul>
 *   <li>в правой таблице мест вызова — колонки формальных параметров вызываемого
 *       метода, в ячейках текст фактических аргументов;</li>
 *   <li>в вызывающей иерархии таблица по умолчанию не сужается до мест выбранного
 *       метода: видны все места текущего уровня; переключатель «Подчинение дереву»
 *       (по умолчанию выключен, состояние запоминается) возвращает штатный отбор
 *       по узлу дерева; без отбора таблица сортируется по модулю, методу и строке;</li>
 *   <li>двойной щелчок по ячейке колонки параметра выделяет этот фактический
 *       аргумент в модуле;</li>
 *   <li>подсказка заголовка колонки параметра — обычный текст (направление,
 *       типы, значение по умолчанию, описание); если имя обрезано — сначала
 *       полное имя.</li>
 * </ul>
 *
 * <p>Типы {@code com._1c.g5.v8.dt.bsl.ui.editor.callhierarchy} EDT не экспортирует —
 * к панели обращаемся по id view и через {@link Global#invoke}/{@link Global#getField}.
 */
public final class CallHierarchyViewHook implements IStartup
{
    private static final Set<IWorkbenchWindow> HOOKED_WINDOWS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final String VIEW_ID = "com._1c.g5.v8.dt.bsl.ui.editor.callhierarchy.view"; //$NON-NLS-1$
    private static final String INSTALLED_MARKER = "tormozit.callHierarchyViewHook"; //$NON-NLS-1$
    private static final String SESSION_KEY = "tormozit.callHierarchyViewHook.session"; //$NON-NLS-1$
    private static final String PARAM_COLUMN_KEY = "tormozit.callHierarchyParamColumn"; //$NON-NLS-1$
    private static final String PARAM_INDEX_KEY = "tormozit.callHierarchyParamIndex"; //$NON-NLS-1$
    private static final String CALLER_COLUMN_KEY = "tormozit.callHierarchyCallerColumn"; //$NON-NLS-1$
    private static final String CALLER_KIND_MODULE = "module"; //$NON-NLS-1$
    private static final String CALLER_KIND_METHOD = "method"; //$NON-NLS-1$
    private static final String FOLLOW_TREE_ACTION_ID = "tormozit.callHierarchyFollowTree"; //$NON-NLS-1$
    private static final String SETTINGS_SECTION = "CallHierarchyView"; //$NON-NLS-1$
    private static final String KEY_COL_ORDER = "col.order"; //$NON-NLS-1$
    private static final String KEY_COL_FILL_MODE = "col.fillMode"; //$NON-NLS-1$
    private static final String KEY_COL_LINE_WIDTH = "col.lineWidth"; //$NON-NLS-1$
    private static final String KEY_COL_INFO_WIDTH = "col.infoWidth"; //$NON-NLS-1$
    private static final String KEY_COL_MODULE_WIDTH = "col.moduleWidth"; //$NON-NLS-1$
    private static final String KEY_COL_METHOD_WIDTH = "col.methodWidth"; //$NON-NLS-1$
    private static final String KEY_FOLLOW_TREE = "followTree"; //$NON-NLS-1$
    private static final String KEY_SASH_LEFT = "sashLeft"; //$NON-NLS-1$
    private static final String KEY_SASH_RIGHT = "sashRight"; //$NON-NLS-1$
    private static final int SASH_HIT_WIDTH = 7;
    private static final int SASH_LINE_WIDTH = 1;
    private static final String LISTENER_CLASS =
        "com._1c.g5.v8.dt.bsl.ui.editor.callhierarchy.ICallHierarchyResultListener"; //$NON-NLS-1$
    private static final int BASE_COLUMN_COUNT = 3;
    private static final int DEFAULT_LINE_WIDTH = 60;
    private static final int DEFAULT_INFO_WIDTH = 300;
    private static final int DEFAULT_PARAM_WIDTH = 80;
    private static final int DEFAULT_CALLER_WIDTH = 70;
    private static final int CALL_MODE_CALLERS = 0;
    private static final String EXTRA_PARAM_TOOLTIP = "Формальный параметр отсутствует"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
                return;
            for (IWorkbenchWindow window : wb.getWorkbenchWindows())
                hookWindow(window);
            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)
                {
                    if (w != null)
                        HOOKED_WINDOWS.remove(w);
                }
            });
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null || !HOOKED_WINDOWS.add(window))
            return;
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null)
                continue;
            for (IViewReference ref : page.getViewReferences())
            {
                if (!VIEW_ID.equals(ref.getId()))
                    continue;
                IViewPart view = ref.getView(false);
                if (view != null)
                    install(view);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { tryInstall(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref) { tryInstall(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { tryInstall(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r) {}
            @Override public void partDeactivated(IWorkbenchPartReference r) {}
            @Override public void partHidden(IWorkbenchPartReference r) {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}

            private void tryInstall(IWorkbenchPartReference ref)
            {
                if (ref == null || !VIEW_ID.equals(ref.getId()))
                    return;
                IWorkbenchPart part = ref.getPart(false);
                if (part instanceof IViewPart view)
                    install(view);
            }
        });
    }

    private static void install(IViewPart view)
    {
        if (view == null)
            return;
        TableViewer locationViewer = locationViewerOf(view);
        Table table = locationViewer != null ? locationViewer.getTable() : null;
        if (table == null || table.isDisposed())
            return;
        if (Boolean.TRUE.equals(table.getData(INSTALLED_MARKER)))
            return;

        Object treeRaw = Global.invoke(view, "getTreeViewer"); //$NON-NLS-1$
        if (!(treeRaw instanceof TreeViewer treeViewer))
            return;

        try
        {
            Session session = new Session(view, treeViewer, locationViewer);
            IBaseLabelProvider current = locationViewer.getLabelProvider();
            if (current instanceof ITableLabelProvider tableLabels)
                locationViewer.setLabelProvider(new LocationLabelProvider(tableLabels, session));
            session.interaction = installTableInteraction(locationViewer);
            session.installKeepInput();
            session.installFollowTreeAction();
            session.installTreeSort();
            session.installOpenActualArg();

            // Штатный слушатель EDT на каждый клик в дереве пересобирает правую таблицу
            // (сужает до мест выбранного метода). Мы показываем весь уровень и только
            // активируем строку — иначе клик = полная перестройка таблицы.
            // createPartControl может добавить EDT-слушатель после нашего install —
            // снимаем его здесь и ещё раз после возврата в цикл UI.
            session.detachEdtTreeListener();
            treeViewer.addSelectionChangedListener(event -> session.applyFromTree(true));
            treeViewer.addPostSelectionChangedListener(event -> session.applyFromTree(true));
            treeViewer.addTreeListener(new org.eclipse.jface.viewers.ITreeViewerListener()
            {
                @Override
                public void treeExpanded(org.eclipse.jface.viewers.TreeExpansionEvent event)
                {
                    session.applyFromTree(true);
                }

                @Override
                public void treeCollapsed(org.eclipse.jface.viewers.TreeExpansionEvent event)
                {
                }
            });
            table.addDisposeListener(e ->
            {
                session.saveFollowTree();
                session.stopWatch();
                session.detachResult();
            });
            if (treeViewer.getControl() != null)
                treeViewer.getControl().addDisposeListener(e -> session.detachResult());
            session.attachResult(Global.invoke(view, "getCurrentCallHierarchyResult")); //$NON-NLS-1$
            session.applyFromTree();
            session.watchUntilTableFilled();
            Display display = table.getDisplay();
            display.timerExec(0, session::detachEdtTreeListener);
            display.timerExec(200, session::detachEdtTreeListener);

            table.setData(INSTALLED_MARKER, Boolean.TRUE);
            table.setData(SESSION_KEY, session);
            Debug.log("installed"); //$NON-NLS-1$
        }
        catch (RuntimeException ignored)
        {
        }
    }

    private static TableViewer locationViewerOf(IViewPart view)
    {
        Object raw = Global.getField(view, "locationViewer"); //$NON-NLS-1$
        return raw instanceof TableViewer viewer ? viewer : null;
    }

    private static FormTableInteraction installTableInteraction(TableViewer locationViewer)
    {
        Table table = locationViewer.getTable();
        if (table == null || table.isDisposed())
            return null;
        if (!(table.getParent() instanceof SashForm sash))
            return null;

        Composite tableStack = new Composite(sash, SWT.NONE);
        tableStack.setLayout(null);
        Composite columnHost = new Composite(tableStack, SWT.NONE);
        TableColumnLayout columnLayout = new TableColumnLayout(true);
        columnHost.setLayout(columnLayout);
        if (!table.setParent(columnHost))
        {
            tableStack.dispose();
            return null;
        }
        table.setLayout(null);
        table.setLayoutData(null);

        TableColumn[] columns = table.getColumns();
        IDialogSettings settings = dialogSettings();
        if (columns.length >= BASE_COLUMN_COUNT)
        {
            FormTableInteraction.applyIconColumn(columns[0], columnLayout);
            int lineWidth = FormTableColumnState.readWidth(settings, KEY_COL_LINE_WIDTH, DEFAULT_LINE_WIDTH, 1);
            int infoWidth = FormTableColumnState.readWidth(settings, KEY_COL_INFO_WIDTH, DEFAULT_INFO_WIDTH, 1);
            columnLayout.setColumnData(columns[1], new ColumnPixelData(lineWidth, true, true));
            columnLayout.setColumnData(columns[2], new ColumnPixelData(infoWidth, true, true));
            for (int i = BASE_COLUMN_COUNT; i < columns.length; i++)
                columnLayout.setColumnData(columns[i], new ColumnPixelData(DEFAULT_PARAM_WIDTH, true, true));
        }
        restoreSashWeights(sash, settings);
        sash.layout(true, true);

        FormTableInteraction interaction = new FormTableInteraction(table, locationViewer,
            (item, col) -> columnText(locationViewer, item != null ? item.getData() : null, col, item));
        interaction.setFilterTextResolver(
            (element, col) -> columnText(locationViewer, element, col, null));
        interaction.setExternalMenuPopulation(true);
        interaction.setColumnReorderEnabled(true);
        FormTableColumnState.loadOrder(settings, KEY_COL_ORDER, table);
        // Fill-mode всей таблицы с динамическими колонками параметров не отменяет
        // сохранённые ширины штатных «Строка»/«Вызов».
        boolean hasSaved = settings.get(KEY_COL_LINE_WIDTH) != null
            || settings.get(KEY_COL_INFO_WIDTH) != null;
        interaction.install(hasSaved);
        interaction.enableHeaderSort();
        enhanceSplitter(sash, tableStack);

        Menu menu = table.getMenu();
        if (menu != null && !menu.isDisposed())
        {
            menu.addMenuListener(new MenuAdapter()
            {
                @Override
                public void menuShown(MenuEvent e)
                {
                    if (!menu.isDisposed())
                        interaction.populateFilterMenuItems(menu);
                }
            });
        }
        TableColumn lineColumn = columns.length > 1 ? columns[1] : null;
        TableColumn infoColumn = columns.length > 2 ? columns[2] : null;
        // Как в результатах поиска: IDialogSettings при закрытии/пересоздании панели, не на ресайз.
        sash.addDisposeListener(e -> saveSashWeights(sash));
        table.addDisposeListener(e -> saveColumnState(table, interaction, lineColumn, infoColumn));
        return interaction;
    }

    private static String columnText(TableViewer viewer, Object element, int column, TableItem item)
    {
        if (element != null)
        {
            IBaseLabelProvider provider = viewer.getLabelProvider();
            if (provider instanceof ITableLabelProvider tableLabels)
            {
                String text = tableLabels.getColumnText(element, column);
                return text != null ? text : ""; //$NON-NLS-1$
            }
        }
        if (item != null && !item.isDisposed())
        {
            String text = item.getText(column);
            return text != null ? text : ""; //$NON-NLS-1$
        }
        return ""; //$NON-NLS-1$
    }

    private static TableColumnLayout columnLayoutOf(Table table)
    {
        Composite parent = table != null ? table.getParent() : null;
        if (parent != null && parent.getLayout() instanceof TableColumnLayout layout)
            return layout;
        return null;
    }

    private static IDialogSettings dialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }

    private static void saveColumnState(Table table, FormTableInteraction interaction,
        TableColumn lineColumn, TableColumn infoColumn)
    {
        if (table == null || table.isDisposed())
            return;
        if (lineColumn == null || lineColumn.isDisposed() || infoColumn == null || infoColumn.isDisposed())
            return;
        boolean fillMode = interaction != null && interaction.isColumnsExactFill();
        IDialogSettings settings = dialogSettings();
        FormTableColumnState.saveOrderAndWidths(settings, KEY_COL_ORDER, KEY_COL_FILL_MODE, fillMode,
            new String[] { KEY_COL_LINE_WIDTH, KEY_COL_INFO_WIDTH },
            new TableColumn[] { lineColumn, infoColumn }, table);
        for (TableColumn column : table.getColumns())
        {
            if (column == null || column.isDisposed())
                continue;
            Object kind = column.getData(CALLER_COLUMN_KEY);
            if (CALLER_KIND_MODULE.equals(kind))
                settings.put(KEY_COL_MODULE_WIDTH, Integer.toString(column.getWidth()));
            else if (CALLER_KIND_METHOD.equals(kind))
                settings.put(KEY_COL_METHOD_WIDTH, Integer.toString(column.getWidth()));
        }
    }

    private static void enhanceSplitter(SashForm sash, Composite tableStack)
    {
        if (sash == null || sash.isDisposed())
            return;
        sash.setSashWidth(SASH_HIT_WIDTH);
        if (tableStack == null || tableStack.isDisposed())
            return;
        Canvas line = new Canvas(tableStack, SWT.NO_MERGE_PAINTS | SWT.DOUBLE_BUFFERED);
        line.setEnabled(false);
        line.addPaintListener((PaintListener) e ->
        {
            Color color = e.display.getSystemColor(
                ThemeAwareColors.isDarkTheme() ? SWT.COLOR_GRAY : SWT.COLOR_WIDGET_DARK_SHADOW);
            e.gc.setBackground(color);
            e.gc.fillRectangle(0, 0, e.width, e.height);
        });
        Runnable place = () ->
        {
            if (tableStack.isDisposed() || line.isDisposed())
                return;
            int height = tableStack.getClientArea().height;
            line.setBounds(0, 0, SASH_LINE_WIDTH, Math.max(0, height));
            line.moveAbove(null);
        };
        tableStack.addListener(SWT.Resize, e -> place.run());
        place.run();
    }

    private static void restoreSashWeights(SashForm sash, IDialogSettings settings)
    {
        if (sash == null || sash.isDisposed() || settings == null || settings.get(KEY_SASH_LEFT) == null)
            return;
        int left = FormTableColumnState.readWidth(settings, KEY_SASH_LEFT, 50, 1);
        int right = FormTableColumnState.readWidth(settings, KEY_SASH_RIGHT, 50, 1);
        sash.setWeights(new int[] { left, right });
    }

    private static void saveSashWeights(SashForm sash)
    {
        if (sash == null)
            return;
        int[] weights = sash.getWeights();
        if (weights.length < 2 || weights[0] <= 0 || weights[1] <= 0)
            return;
        IDialogSettings settings = dialogSettings();
        settings.put(KEY_SASH_LEFT, weights[0]);
        settings.put(KEY_SASH_RIGHT, weights[1]);
    }

    private static void applyPixelWidth(TableColumn column, TableColumnLayout layout, int width)
    {
        if (column == null || column.isDisposed() || width <= 0)
            return;
        if (layout != null)
            layout.setColumnData(column, new ColumnPixelData(width, true, true));
        column.setWidth(width);
    }

    private static void scheduleRestoreStaticWidths(Table table, int lineWidth, int infoWidth)
    {
        if (table == null || table.isDisposed())
            return;
        Display display = table.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(1, () ->
        {
            if (table.isDisposed())
                return;
            TableColumn[] columns = table.getColumns();
            if (columns.length < BASE_COLUMN_COUNT)
                return;
            TableColumnLayout layout = columnLayoutOf(table);
            applyPixelWidth(columns[1], layout, lineWidth);
            applyPixelWidth(columns[2], layout, infoWidth);
            IDialogSettings settings = dialogSettings();
            for (TableColumn column : columns)
            {
                Object kind = column.getData(CALLER_COLUMN_KEY);
                if (CALLER_KIND_MODULE.equals(kind))
                    applyPixelWidth(column, layout,
                        FormTableColumnState.readWidth(settings, KEY_COL_MODULE_WIDTH, column.getWidth(), 1));
                else if (CALLER_KIND_METHOD.equals(kind))
                    applyPixelWidth(column, layout,
                        FormTableColumnState.readWidth(settings, KEY_COL_METHOD_WIDTH, column.getWidth(), 1));
            }
        });
    }

    private static boolean isTreeNode(Object element)
    {
        if (element == null || isPendingNode(element))
            return false;
        for (Class<?> type = element.getClass(); type != null; type = type.getSuperclass())
        {
            if ("CallHierarchyViewTreeNode".equals(type.getSimpleName())) //$NON-NLS-1$
                return true;
        }
        return false;
    }

    private static boolean isPendingNode(Object element)
    {
        return element != null && "PendingCallHierarchyViewTreeNode".equals(element.getClass().getSimpleName()); //$NON-NLS-1$
    }

    private static boolean isCallersMode(Object view)
    {
        Object mode = Global.invoke(view, "getCallMode"); //$NON-NLS-1$
        return Integer.valueOf(CALL_MODE_CALLERS).equals(mode);
    }

    private static final class Session
    {
        private final IViewPart view;
        private final TreeViewer treeViewer;
        private final TableViewer locationViewer;
        private final Map<URI, String[]> argsBySource = new LinkedHashMap<>();
        private FormTableInteraction interaction;
        private Object hookedResult;
        private Object listenerProxy;
        private boolean applying;
        private boolean fillQueued;
        private URI lastParamMethodUri;
        private int tipsGeneration;
        private Job tipsJob;
        private int watchGeneration;
        private boolean watchStarted;
        private List<IReferenceDescription> keptInput;
        private boolean forceEmpty;
        private boolean searchFinished = true;
        private boolean followTree;
        private Action followTreeAction;
        private boolean lastFillAllowEmpty;
        private int resetGeneration;
        private final Map<URI, String[]> callerBySource = new LinkedHashMap<>();
        private final Map<Object, String[]> treeSortByNode = new IdentityHashMap<>();
        private Boolean treeSortCallersMode;

        Session(IViewPart view, TreeViewer treeViewer, TableViewer locationViewer)
        {
            this.view = view;
            this.treeViewer = treeViewer;
            this.locationViewer = locationViewer;
            this.followTree = readFollowTree();
        }

        void onResultChanged(Object event)
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            Display display = workbench != null ? workbench.getDisplay() : null;
            if (display == null || display.isDisposed())
                return;
            if (isResultEvent(event, "Reset")) //$NON-NLS-1$
            {
                searchFinished = false;
                display.asyncExec(() ->
                {
                    if (!display.isDisposed())
                        clearTableNow();
                });
                return;
            }
            if (isResultEvent(event, "Finish")) //$NON-NLS-1$
                searchFinished = true;
            queueFill(display);
        }

        void queueFill(Display display)
        {
            if (display == null || display.isDisposed() || fillQueued)
                return;
            fillQueued = true;
            display.asyncExec(() ->
            {
                fillQueued = false;
                if (!display.isDisposed())
                    applyFromTree(false);
            });
        }

        void clearTableNow()
        {
            Table table = locationViewer.getTable();
            if (table == null || table.isDisposed())
                return;
            applying = true;
            try
            {
                resetGeneration++;
                forceEmpty = true;
                keptInput = Collections.emptyList();
                lastFillAllowEmpty = false;
                locationViewer.setInput(Collections.emptyList());
                locationViewer.setSelection(StructuredSelection.EMPTY, false);
            }
            finally
            {
                applying = false;
            }
        }

        static boolean isResultEvent(Object event, String simpleName)
        {
            if (event == null || simpleName == null || simpleName.isEmpty())
                return false;
            return simpleName.equals(event.getClass().getSimpleName());
        }

        void attachResult(Object result)
        {
            if (hookedResult == result)
                return;
            boolean hadResult = hookedResult != null;
            detachResult();
            hookedResult = result;
            argsBySource.clear();
            lastParamMethodUri = null;
            treeSortByNode.clear();
            cancelParamHeaderTips();
            if (hookedResult == null)
                return;
            listenerProxy = resultListenerProxy(hookedResult);
            if (listenerProxy != null)
                Global.invoke(hookedResult, "addListener", listenerProxy); //$NON-NLS-1$
            if (hadResult)
            {
                searchFinished = false;
                clearTableNow();
            }
        }

        void detachResult()
        {
            if (hookedResult == null)
                return;
            try
            {
                if (listenerProxy != null)
                    Global.invoke(hookedResult, "removeListener", listenerProxy); //$NON-NLS-1$
            }
            catch (RuntimeException ignored)
            {
            }
            hookedResult = null;
            listenerProxy = null;
            keptInput = null;
            cancelParamHeaderTips();
        }

        void stopWatch()
        {
            watchStarted = false;
            watchGeneration++;
        }

        Object resultListenerProxy(Object result)
        {
            ClassLoader loader = result.getClass().getClassLoader();
            if (loader == null)
                return null;
            Class<?> listenerClass;
            try
            {
                listenerClass = loader.loadClass(LISTENER_CLASS);
            }
            catch (ClassNotFoundException ignored)
            {
                return null;
            }
            return Proxy.newProxyInstance(loader, new Class<?>[] { listenerClass },
                (proxy, method, args) ->
                {
                    try
                    {
                        String name = method.getName();
                        if ("callHierarchyResultChanged".equals(name)) //$NON-NLS-1$
                        {
                            onResultChanged(args != null && args.length > 0 ? args[0] : null);
                            return null;
                        }
                        if ("hashCode".equals(name) && (args == null || args.length == 0)) //$NON-NLS-1$
                            return Integer.valueOf(System.identityHashCode(proxy));
                        if ("equals".equals(name) && args != null && args.length == 1) //$NON-NLS-1$
                            return Boolean.valueOf(proxy == args[0]);
                        if ("toString".equals(name) && (args == null || args.length == 0)) //$NON-NLS-1$
                            return "CallHierarchyViewHook.Session"; //$NON-NLS-1$
                        return null;
                    }
                    catch (RuntimeException ignored)
                    {
                        return null;
                    }
                });
        }

        void installKeepInput()
        {
            IContentProvider current = locationViewer.getContentProvider();
            if (!(current instanceof IStructuredContentProvider delegate))
                return;
            locationViewer.setContentProvider(new IStructuredContentProvider()
            {
                @Override
                public Object[] getElements(Object inputElement)
                {
                    if (forceEmpty)
                        return new Object[0];
                    Object[] elements = delegate.getElements(inputElement);
                    if (elements != null && elements.length > 0)
                        return elements;
                    if (keptInput == null || keptInput.isEmpty())
                        return elements != null ? elements : new Object[0];
                    return keptInput.toArray();
                }

                @Override
                public void inputChanged(Viewer viewer, Object oldInput, Object newInput)
                {
                    delegate.inputChanged(viewer, oldInput, newInput);
                }

                @Override
                public void dispose()
                {
                    delegate.dispose();
                }
            });
        }

        void installFollowTreeAction()
        {
            if (view.getViewSite() == null)
                return;
            IActionBars bars = view.getViewSite().getActionBars();
            if (bars == null)
                return;
            IToolBarManager toolBar = bars.getToolBarManager();
            if (toolBar.find(FOLLOW_TREE_ACTION_ID) != null)
                toolBar.remove(FOLLOW_TREE_ACTION_ID);
            followTreeAction = new Action("", IAction.AS_CHECK_BOX) //$NON-NLS-1$
            {
                @Override
                public void run()
                {
                    followTree = isChecked();
                    saveFollowTree();
                    applyFromTree(true);
                }
            };
            followTreeAction.setId(FOLLOW_TREE_ACTION_ID);
            followTreeAction.setChecked(followTree);
            followTreeAction.setToolTipText(
                "Подчинение дереву — отбор таблицы мест вызова по выбранному узлу дерева" //$NON-NLS-1$
                    + Global.pluginSignForTooltip());
            ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
            followTreeAction.setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_ELCL_SYNCED));
            followTreeAction.setDisabledImageDescriptor(
                images.getImageDescriptor(ISharedImages.IMG_ELCL_SYNCED_DISABLED));
            followTreeAction.setEnabled(isCallersMode(view));
            toolBar.add(new Separator());
            toolBar.add(followTreeAction);
            toolBar.update(true);
            bars.updateActionBars();
        }

        void installTreeSort()
        {
            treeViewer.setComparator(new ViewerComparator()
            {
                @Override
                public int compare(Viewer viewer, Object e1, Object e2)
                {
                    if (isPendingNode(e1) != isPendingNode(e2))
                        return isPendingNode(e1) ? 1 : -1;
                    boolean leftCurrent = sameModuleAsParent(e1);
                    boolean rightCurrent = sameModuleAsParent(e2);
                    if (leftCurrent != rightCurrent)
                        return leftCurrent ? -1 : 1;
                    String[] left = treeSortKeys(e1);
                    String[] right = treeSortKeys(e2);
                    int cmp = compareCallerText(left[0], right[0]);
                    if (cmp != 0)
                        return cmp;
                    return compareCallerText(left[1], right[1]);
                }
            });
        }

        String[] treeSortKeys(Object node)
        {
            boolean callers = isCallersMode(view);
            if (treeSortCallersMode == null || treeSortCallersMode.booleanValue() != callers)
            {
                treeSortCallersMode = Boolean.valueOf(callers);
                treeSortByNode.clear();
            }
            String[] cached = treeSortByNode.get(node);
            if (cached != null)
                return cached;
            String[] keys = new String[] { "", "" }; //$NON-NLS-1$ //$NON-NLS-2$
            if (isTreeNode(node))
            {
                IReferenceDescription first = firstDescription(descriptionsOf(node));
                if (first != null)
                {
                    if (isCallersMode(view))
                        keys = callerFields(first);
                    else
                        keys = extractModuleMethod(resolveEObject(first.getTargetEObjectUri()));
                }
            }
            treeSortByNode.put(node, keys);
            return keys;
        }

        boolean sameModuleAsParent(Object node)
        {
            if (!isTreeNode(node))
                return false;
            Object parent = Global.invoke(node, "getParent"); //$NON-NLS-1$
            if (!isTreeNode(parent))
                return false;
            String childModule = treeSortKeys(node)[0];
            String parentModule = treeSortKeys(parent)[0];
            return !childModule.isEmpty() && compareCallerText(childModule, parentModule) == 0;
        }

        void installOpenActualArg()
        {
            locationViewer.addOpenListener(event ->
            {
                Display display = locationViewer.getTable() != null
                    ? locationViewer.getTable().getDisplay()
                    : null;
                if (display == null || display.isDisposed())
                    return;
                display.asyncExec(this::openActualArgFromActiveCell);
            });
        }

        void openActualArgFromActiveCell()
        {
            Table table = locationViewer.getTable();
            if (table == null || table.isDisposed() || interaction == null)
                return;
            int column = interaction.activeColumn();
            if (column < 0 || column >= table.getColumnCount())
                return;
            TableColumn widget = table.getColumn(column);
            if (widget == null || widget.isDisposed())
                return;
            Object rawIndex = widget.getData(PARAM_INDEX_KEY);
            if (!(rawIndex instanceof Integer paramIndex))
                return;
            IStructuredSelection selection = locationViewer.getStructuredSelection();
            IReferenceDescription description = asDescription(
                selection != null ? selection.getFirstElement() : null);
            Expression arg = actualArg(description, paramIndex.intValue());
            if (arg == null)
                return;
            try
            {
                IEditorPart editor = openCallSite(description);
                XtextEditor xtext = BslHandlerUtil.extractXtextEditor(editor);
                ICompositeNode node = NodeModelUtils.findActualNodeFor(arg);
                if (node == null)
                    node = NodeModelUtils.getNode(arg);
                if (xtext != null && node != null && node.getLength() > 0)
                    xtext.selectAndReveal(node.getOffset(), node.getLength());
            }
            catch (RuntimeException ignored)
            {
            }
        }

        IEditorPart openCallSite(IReferenceDescription description)
        {
            Object opener = Global.getField(view, "uriEditorOpener"); //$NON-NLS-1$
            Object opened = Global.invoke(opener, "open", description.getSourceEObjectUri(), //$NON-NLS-1$
                description.getEReference(), Integer.valueOf(description.getIndexInList()), Boolean.TRUE);
            if (opened instanceof IEditorPart part)
                return part;
            if (view.getSite() != null && view.getSite().getPage() != null)
                return view.getSite().getPage().getActiveEditor();
            return null;
        }

        Expression actualArg(IReferenceDescription description, int index)
        {
            if (description == null || index < 0)
                return null;
            EObject source = resolveEObject(sourceUri(description));
            EList<Expression> params = paramsOf(source);
            if (params == null || index >= params.size())
                return null;
            return params.get(index);
        }

        static boolean readFollowTree()
        {
            IDialogSettings settings = dialogSettings();
            if (settings.get(KEY_FOLLOW_TREE) == null)
                return false;
            return settings.getBoolean(KEY_FOLLOW_TREE);
        }

        void saveFollowTree()
        {
            dialogSettings().put(KEY_FOLLOW_TREE, followTree);
        }

        boolean followTreeEffective()
        {
            return followTree && isCallersMode(view);
        }

        boolean callerColumnsVisible()
        {
            return !followTree && isCallersMode(view);
        }

        int paramColumnStart()
        {
            return BASE_COLUMN_COUNT + (hasCallerColumns() ? 2 : 0);
        }

        boolean hasCallerColumns()
        {
            Table table = locationViewer.getTable();
            if (table == null || table.isDisposed())
                return false;
            for (TableColumn column : table.getColumns())
            {
                if (CALLER_KIND_MODULE.equals(column.getData(CALLER_COLUMN_KEY))
                    || CALLER_KIND_METHOD.equals(column.getData(CALLER_COLUMN_KEY)))
                    return true;
            }
            return false;
        }

        void selectTableRows(List<IReferenceDescription> shown, List<IReferenceDescription> mine)
        {
            Table table = locationViewer.getTable();
            if (isCallersMode(view) && !followTreeEffective())
            {
                List<IReferenceDescription> matches = matchingInShown(shown, mine);
                locationViewer.setSelection(
                    matches.isEmpty() ? StructuredSelection.EMPTY : new StructuredSelection(matches), true);
                FormTableInteraction.revealSelection(table);
                return;
            }
            IReferenceDescription match = findBySource(shown, sourceUri(firstDescription(mine)));
            if (match == null)
                match = firstDescription(shown);
            if (match != null)
            {
                locationViewer.setSelection(new StructuredSelection(match), true);
                FormTableInteraction.revealSelection(table);
            }
            else
                locationViewer.setSelection(StructuredSelection.EMPTY, false);
        }

        static List<IReferenceDescription> matchingInShown(List<IReferenceDescription> shown,
            List<IReferenceDescription> mine)
        {
            if (shown == null || shown.isEmpty() || mine == null || mine.isEmpty())
                return Collections.emptyList();
            Set<URI> sources = new java.util.HashSet<>();
            for (IReferenceDescription item : mine)
            {
                URI uri = sourceUri(item);
                if (uri != null)
                    sources.add(uri);
            }
            if (sources.isEmpty())
                return Collections.emptyList();
            List<IReferenceDescription> matches = new ArrayList<>();
            for (IReferenceDescription item : shown)
            {
                URI uri = sourceUri(item);
                if (uri != null && sources.contains(uri))
                    matches.add(item);
            }
            return matches;
        }

        boolean syncCallerColumns()
        {
            Table table = locationViewer.getTable();
            if (table == null || table.isDisposed())
                return false;
            boolean want = callerColumnsVisible();
            boolean have = hasCallerColumns();
            if (want == have)
                return false;
            TableColumnLayout layout = columnLayoutOf(table);
            int lineWidth = table.getColumnCount() > 1 ? table.getColumns()[1].getWidth() : 0;
            int infoWidth = table.getColumnCount() > 2 ? table.getColumns()[2].getWidth() : 0;
            boolean added = false;
            if (want)
            {
                IDialogSettings settings = dialogSettings();
                int moduleWidth = FormTableColumnState.readWidth(settings, KEY_COL_MODULE_WIDTH, DEFAULT_CALLER_WIDTH, 1);
                int methodWidth = FormTableColumnState.readWidth(settings, KEY_COL_METHOD_WIDTH, DEFAULT_CALLER_WIDTH, 1);
                TableColumn moduleColumn = new TableColumn(table, SWT.LEFT, BASE_COLUMN_COUNT);
                moduleColumn.setData(CALLER_COLUMN_KEY, CALLER_KIND_MODULE);
                moduleColumn.setText("Модуль"); //$NON-NLS-1$
                moduleColumn.setResizable(true);
                bindCallerColumn(moduleColumn, layout, moduleWidth);
                TableColumn methodColumn = new TableColumn(table, SWT.LEFT, BASE_COLUMN_COUNT + 1);
                methodColumn.setData(CALLER_COLUMN_KEY, CALLER_KIND_METHOD);
                methodColumn.setText("Метод"); //$NON-NLS-1$
                methodColumn.setResizable(true);
                bindCallerColumn(methodColumn, layout, methodWidth);
                added = true;
            }
            else
            {
                clearTableSort();
                TableColumn[] columns = table.getColumns();
                for (int i = columns.length - 1; i >= 0; i--)
                {
                    TableColumn column = columns[i];
                    Object kind = column.getData(CALLER_COLUMN_KEY);
                    if (CALLER_KIND_MODULE.equals(kind) || CALLER_KIND_METHOD.equals(kind))
                        column.dispose();
                }
            }
            lastParamMethodUri = null;
            if (interaction != null)
                interaction.notifyColumnsChanged();
            Composite host = table.getParent();
            if (host != null && !host.isDisposed())
                host.layout(true, true);
            scheduleRestoreStaticWidths(table, lineWidth, infoWidth);
            return added;
        }

        TableColumn findCallerColumn(String kind)
        {
            Table table = locationViewer.getTable();
            if (table == null || table.isDisposed() || kind == null)
                return null;
            for (TableColumn column : table.getColumns())
            {
                if (kind.equals(column.getData(CALLER_COLUMN_KEY)))
                    return column;
            }
            return null;
        }

        void clearTableSort()
        {
            locationViewer.setComparator(null);
            Table table = locationViewer.getTable();
            if (table == null || table.isDisposed())
                return;
            table.setSortColumn(null);
            table.setSortDirection(SWT.NONE);
        }

        void applyDefaultCallerSort()
        {
            Table table = locationViewer.getTable();
            TableColumn moduleColumn = findCallerColumn(CALLER_KIND_MODULE);
            if (table == null || table.isDisposed() || moduleColumn == null || moduleColumn.isDisposed())
                return;
            locationViewer.setComparator(new ViewerComparator()
            {
                @Override
                public int compare(Viewer viewer, Object e1, Object e2)
                {
                    int cmp = compareCallerText(callerModuleOf(e1), callerModuleOf(e2));
                    if (cmp != 0)
                        return cmp;
                    cmp = compareCallerText(callerMethodOf(e1), callerMethodOf(e2));
                    if (cmp != 0)
                        return cmp;
                    return compareCallerLine(lineTextOf(e1), lineTextOf(e2));
                }
            });
            table.setSortColumn(moduleColumn);
            table.setSortDirection(SWT.UP);
        }

        void ensureDefaultCallerSort(boolean force)
        {
            if (!callerColumnsVisible())
                return;
            Table table = locationViewer.getTable();
            if (table == null || table.isDisposed())
                return;
            if (!force && table.getSortColumn() != null)
                return;
            applyDefaultCallerSort();
        }

        static int compareCallerText(String a, String b)
        {
            if (a == null)
                a = ""; //$NON-NLS-1$
            if (b == null)
                b = ""; //$NON-NLS-1$
            return String.CASE_INSENSITIVE_ORDER.compare(a, b);
        }

        String lineTextOf(Object element)
        {
            return columnText(locationViewer, element, 1, null);
        }

        static int compareCallerLine(String a, String b)
        {
            Long na = parseWholeLong(a);
            Long nb = parseWholeLong(b);
            if (na != null && nb != null)
                return Long.compare(na.longValue(), nb.longValue());
            return compareCallerText(a, b);
        }

        static Long parseWholeLong(String s)
        {
            if (s == null || s.isEmpty())
                return null;
            int i = 0;
            if (s.charAt(0) == '-')
            {
                if (s.length() == 1)
                    return null;
                i = 1;
            }
            for (; i < s.length(); i++)
            {
                char c = s.charAt(i);
                if (c < '0' || c > '9')
                    return null;
            }
            try
            {
                return Long.valueOf(s);
            }
            catch (NumberFormatException ex)
            {
                return null;
            }
        }

        static void bindCallerColumn(TableColumn column, TableColumnLayout layout, int width)
        {
            if (column == null || column.isDisposed())
                return;
            int pixel = width > 0 ? width : DEFAULT_CALLER_WIDTH;
            if (layout != null)
                layout.setColumnData(column, new ColumnPixelData(pixel, true, true));
            column.setWidth(pixel);
        }

        void applyFromTree()
        {
            applyFromTree(false);
        }

        void applyFromTree(boolean fromUser)
        {
            Table table = locationViewer.getTable();
            if (table == null || table.isDisposed() || applying)
                return;
            detachEdtTreeListener();
            Object result = Global.invoke(view, "getCurrentCallHierarchyResult"); //$NON-NLS-1$
            attachResult(result);
            if (followTreeAction != null)
                followTreeAction.setEnabled(isCallersMode(view));

            boolean treeCurrent = result != null && result == treeViewer.getInput();
            IStructuredSelection sel = treeViewer.getStructuredSelection();
            Object first = sel != null ? sel.getFirstElement() : null;
            if (!isTreeNode(first) && treeCurrent)
            {
                Object inferred = firstRootNode();
                if (isTreeNode(inferred))
                    first = inferred;
            }
            if (!isTreeNode(first) || !treeCurrent)
            {
                List<IReferenceDescription> matching = matchingReferences();
                if (!withoutMethodDefinition(matching, Collections.emptyList()).isEmpty())
                    fillTable(matching, Collections.emptyList(), true, fromUser);
                return;
            }

            List<IReferenceDescription> mine = descriptionsOf(first);
            List<IReferenceDescription> all;
            boolean root = Global.invoke(first, "getParent") == null; //$NON-NLS-1$
            boolean subordinate = followTreeEffective();
            if (isCallersMode(view) && !subordinate)
            {
                all = collectLevelDescriptions(first);
                if (all.isEmpty())
                    all = matchingReferences();
                if (all.isEmpty() && !root)
                    all = new ArrayList<>(mine);
            }
            else
            {
                all = new ArrayList<>(mine);
                if (all.isEmpty())
                    all = matchingReferences();
            }
            fillTable(all, mine, root, fromUser);
        }

        void fillTable(List<IReferenceDescription> all, List<IReferenceDescription> mine, boolean root,
            boolean fromUser)
        {
            if (all == null)
                all = Collections.emptyList();
            if (root)
                all = withoutMethodDefinition(all, mine);
            lastFillAllowEmpty = searchFinished && all.isEmpty();
            if (all.isEmpty())
                return;
            Table table = locationViewer.getTable();
            if (!fromUser && !forceEmpty && table.getItemCount() > 0
                && sameDescriptions(locationViewer.getInput(), all))
            {
                keptInput = all;
                return;
            }
            applying = true;
            try
            {
                List<IReferenceDescription> shown = all;
                boolean tableEmpty = table.getItemCount() == 0 || locationViewer.getInput() == null
                    || forceEmpty;
                boolean callerColsAdded = syncCallerColumns();
                if (tableEmpty || !sameDescriptions(locationViewer.getInput(), all))
                {
                    syncParamColumns(all);
                    forceEmpty = false;
                    locationViewer.setInput(all);
                }
                else
                    shown = descriptionsFromInput(locationViewer.getInput());
                keptInput = shown;
                ensureDefaultCallerSort(callerColsAdded);
                selectTableRows(shown, mine);
                if (interaction != null)
                    interaction.resyncSelectionTheme();
                scheduleRefillIfCleared(shown);
            }
            catch (RuntimeException ignored)
            {
            }
            finally
            {
                applying = false;
            }
        }

        void detachEdtTreeListener()
        {
            if (view instanceof ISelectionChangedListener edtListener)
                treeViewer.removeSelectionChangedListener(edtListener);
        }

        void scheduleRefillIfCleared(List<IReferenceDescription> shown)
        {
            if (shown == null || shown.isEmpty())
                return;
            Table table = locationViewer.getTable();
            Display display = table != null ? table.getDisplay() : null;
            if (display == null || display.isDisposed())
                return;
            int[] delays = { 0, 50, 200, 500, 1000, 2000, 4000 };
            int gen = resetGeneration;
            for (int delay : delays)
            {
                int captured = delay;
                display.timerExec(captured, () ->
                {
                    if (table.isDisposed() || gen != resetGeneration || forceEmpty)
                        return;
                    if (table.getItemCount() > 0)
                        return;
                    keptInput = shown;
                    locationViewer.setInput(shown);
                });
            }
        }

        void watchUntilTableFilled()
        {
            if (watchStarted)
                return;
            Table table = locationViewer.getTable();
            if (table == null || table.isDisposed())
                return;
            Display display = table.getDisplay();
            if (display == null || display.isDisposed())
                return;
            watchStarted = true;
            int gen = ++watchGeneration;
            Runnable tick = new Runnable()
            {
                @Override
                public void run()
                {
                    if (gen != watchGeneration || table.isDisposed())
                        return;
                    Object result = Global.invoke(view, "getCurrentCallHierarchyResult"); //$NON-NLS-1$
                    if (result != hookedResult)
                        attachResult(result);
                    boolean empty = table.getItemCount() == 0;
                    if (empty && keptInput != null && !keptInput.isEmpty() && !forceEmpty)
                        locationViewer.setInput(keptInput);
                    else if (empty || !searchFinished)
                        applyFromTree(false);
                    int delay = empty || !searchFinished ? 200 : 500;
                    display.timerExec(delay, this);
                }
            };
            display.timerExec(200, tick);
        }

        List<IReferenceDescription> collectLevelDescriptions(Object node)
        {
            Object parent = Global.invoke(node, "getParent"); //$NON-NLS-1$
            if (parent == null)
            {
                List<IReferenceDescription> matching = matchingReferences();
                if (!withoutMethodDefinition(matching, descriptionsOf(node)).isEmpty())
                    return matching;
                List<IReferenceDescription> fromChildren = collectFromNodes(populatedChildren(node));
                if (!fromChildren.isEmpty())
                    return fromChildren;
                return collectFromNodes(rootNodes());
            }
            return collectFromNodes(populatedChildren(parent));
        }

        static Collection<?> populatedChildren(Object node)
        {
            Object raw = Global.getField(node, "children"); //$NON-NLS-1$
            if (raw instanceof Collection<?> collection && !collection.isEmpty())
                return collection;
            return Collections.emptyList();
        }

        List<Object> rootNodes()
        {
            if (!(treeViewer.getContentProvider() instanceof IStructuredContentProvider content))
                return Collections.emptyList();
            Object[] elements = content.getElements(treeViewer.getInput());
            if (elements == null || elements.length == 0)
                return Collections.emptyList();
            List<Object> nodes = new ArrayList<>(elements.length);
            for (Object element : elements)
            {
                if (isTreeNode(element))
                    nodes.add(element);
            }
            return nodes;
        }

        Object firstRootNode()
        {
            List<Object> roots = rootNodes();
            if (!roots.isEmpty())
                return roots.get(0);
            Tree tree = treeViewer.getTree();
            if (tree == null || tree.isDisposed() || tree.getItemCount() <= 0)
                return null;
            return tree.getItem(0).getData();
        }

        List<IReferenceDescription> matchingReferences()
        {
            Object result = Global.invoke(view, "getCurrentCallHierarchyResult"); //$NON-NLS-1$
            List<IReferenceDescription> matching =
                copyDescriptions(Global.invoke(result, "getMatchingReferences")); //$NON-NLS-1$
            if (matching.isEmpty())
                matching = copyDescriptions(Global.getField(result, "matchingReferences")); //$NON-NLS-1$
            return matching;
        }

        static List<IReferenceDescription> withoutMethodDefinition(List<IReferenceDescription> list,
            List<IReferenceDescription> definition)
        {
            if (list == null || list.isEmpty())
                return Collections.emptyList();
            URI methodUri = null;
            IReferenceDescription defined = firstDescription(definition);
            if (defined != null)
            {
                methodUri = defined.getTargetEObjectUri();
                if (methodUri == null)
                    methodUri = sourceUri(defined);
            }
            List<IReferenceDescription> filtered = new ArrayList<>(list.size());
            for (IReferenceDescription item : list)
            {
                URI source = sourceUri(item);
                URI target = item.getTargetEObjectUri();
                if (source != null && source.equals(target))
                    continue;
                if (methodUri != null && methodUri.equals(source))
                    continue;
                filtered.add(item);
            }
            return filtered;
        }

        static List<IReferenceDescription> collectFromNodes(Collection<?> nodes)
        {
            if (nodes == null || nodes.isEmpty())
                return new ArrayList<>();
            List<IReferenceDescription> all = new ArrayList<>();
            for (Object node : nodes)
            {
                if (isPendingNode(node))
                    continue;
                all.addAll(descriptionsOf(node));
            }
            return all;
        }

        static List<IReferenceDescription> descriptionsOf(Object node)
        {
            return copyDescriptions(Global.invoke(node, "getDescriptions")); //$NON-NLS-1$
        }

        static List<IReferenceDescription> copyDescriptions(Object raw)
        {
            if (!(raw instanceof List<?> list) || list.isEmpty())
                return Collections.emptyList();
            List<IReferenceDescription> copied = new ArrayList<>(list.size());
            for (Object item : list)
            {
                if (item instanceof IReferenceDescription description)
                    copied.add(description);
            }
            return copied;
        }

        static List<IReferenceDescription> descriptionsFromInput(Object input)
        {
            List<IReferenceDescription> copied = copyDescriptions(input);
            return copied.isEmpty() ? new ArrayList<>() : copied;
        }

        static boolean sameDescriptions(Object input, List<IReferenceDescription> next)
        {
            if (!(input instanceof List<?> current) || next == null || current.size() != next.size())
                return false;
            for (int i = 0; i < next.size(); i++)
            {
                Object item = current.get(i);
                URI currentUri = item instanceof IReferenceDescription description
                    ? sourceUri(description)
                    : null;
                URI nextUri = sourceUri(next.get(i));
                if (currentUri == null || !currentUri.equals(nextUri))
                    return false;
            }
            return true;
        }

        static Collection<?> asNodeCollection(Object raw)
        {
            return raw instanceof Collection<?> collection ? collection : Collections.emptyList();
        }

        static IReferenceDescription firstDescription(List<IReferenceDescription> list)
        {
            if (list == null || list.isEmpty())
                return null;
            return list.get(0);
        }

        static IReferenceDescription findBySource(List<IReferenceDescription> list, URI uri)
        {
            if (list == null || uri == null)
                return null;
            for (IReferenceDescription item : list)
            {
                if (uri.equals(sourceUri(item)))
                    return item;
            }
            return null;
        }

        void syncParamColumns(List<IReferenceDescription> descriptions)
        {
            Table table = locationViewer.getTable();
            if (table == null || table.isDisposed())
                return;
            Method method = resolveCalledMethod(firstDescription(descriptions));
            List<String> formals = formalParamNames(method);
            List<String> headers = paramColumnHeaders(formals, maxActualArgCount(descriptions));
            URI methodUri = method != null ? EcoreUtil.getURI(method) : null;
            TableColumn[] columns = table.getColumns();
            int start = paramColumnStart();
            int extra = Math.max(0, columns.length - start);
            boolean sameHeaders = extra == headers.size();
            if (sameHeaders)
            {
                for (int i = 0; i < headers.size(); i++)
                {
                    if (!headers.get(i).equals(columns[start + i].getText()))
                    {
                        sameHeaders = false;
                        break;
                    }
                }
            }
            if (sameHeaders && Objects.equals(methodUri, lastParamMethodUri))
            {
                applyExtraParamTooltips(columns, start, formals.size(), headers.size());
                return;
            }
            lastParamMethodUri = methodUri;
            if (!sameHeaders)
            {
                int lineWidth = columns.length > 1 ? columns[1].getWidth() : 0;
                int infoWidth = columns.length > 2 ? columns[2].getWidth() : 0;
                while (extra > headers.size())
                {
                    TableColumn last = table.getColumns()[table.getColumnCount() - 1];
                    if (last.getData(PARAM_COLUMN_KEY) == null)
                        break;
                    last.dispose();
                    extra--;
                }
                TableColumnLayout layout = columnLayoutOf(table);
                while (extra < headers.size())
                {
                    TableColumn column = new TableColumn(table, SWT.LEFT);
                    column.setData(PARAM_COLUMN_KEY, Boolean.TRUE);
                    bindParamColumn(column, layout);
                    extra++;
                }
                columns = table.getColumns();
                start = paramColumnStart();
                for (int i = 0; i < headers.size(); i++)
                {
                    TableColumn column = columns[start + i];
                    column.setText(headers.get(i));
                    column.setData(PARAM_COLUMN_KEY, Boolean.TRUE);
                    column.setData(PARAM_INDEX_KEY, Integer.valueOf(i));
                    column.setToolTipText(null);
                    column.setResizable(true);
                    if (interaction != null && i < formals.size())
                        interaction.setHeaderTooltipExtra(column, null);
                }
                applyExtraParamTooltips(columns, start, formals.size(), headers.size());
                if (interaction != null)
                    interaction.notifyColumnsChanged();
                Composite host = table.getParent();
                if (host != null && !host.isDisposed())
                    host.layout(true, true);
                scheduleRestoreStaticWidths(table, lineWidth, infoWidth);
            }
            else
                applyExtraParamTooltips(columns, start, formals.size(), headers.size());
            scheduleParamHeaderTooltips(formals, method);
        }

        int maxActualArgCount(List<IReferenceDescription> descriptions)
        {
            int max = 0;
            if (descriptions == null)
                return 0;
            for (IReferenceDescription item : descriptions)
            {
                String[] args = argsOf(item);
                if (args != null && args.length > max)
                    max = args.length;
            }
            return max;
        }

        static List<String> paramColumnHeaders(List<String> formals, int maxActual)
        {
            int formalCount = formals != null ? formals.size() : 0;
            int count = Math.max(formalCount, maxActual);
            List<String> headers = new ArrayList<>(count);
            if (formals != null)
                headers.addAll(formals);
            for (int i = formalCount; i < count; i++)
                headers.add("<" + (i + 1) + ">"); //$NON-NLS-1$ //$NON-NLS-2$
            return headers;
        }

        void applyExtraParamTooltips(TableColumn[] columns, int start, int formalCount, int headerCount)
        {
            if (columns == null || start < 0)
                return;
            for (int i = formalCount; i < headerCount; i++)
            {
                if (start + i >= columns.length)
                    break;
                TableColumn column = columns[start + i];
                if (column == null || column.isDisposed())
                    continue;
                if (interaction != null)
                    interaction.setHeaderTooltipExtra(column, EXTRA_PARAM_TOOLTIP);
                else
                    column.setToolTipText(EXTRA_PARAM_TOOLTIP);
            }
        }

        void bindParamColumn(TableColumn column, TableColumnLayout layout)
        {
            if (column == null || column.isDisposed())
                return;
            if (layout != null)
                layout.setColumnData(column, new ColumnPixelData(DEFAULT_PARAM_WIDTH, true, true));
            else if (column.getWidth() < 40)
                column.setWidth(DEFAULT_PARAM_WIDTH);
        }

        static List<String> formalParamNames(Method method)
        {
            if (method == null)
                return Collections.emptyList();
            EList<FormalParam> params = method.getFormalParams();
            if (params == null || params.isEmpty())
                return Collections.emptyList();
            List<String> names = new ArrayList<>(params.size());
            for (FormalParam param : params)
            {
                String name = param != null ? param.getName() : null;
                names.add(name != null ? name : ""); //$NON-NLS-1$
            }
            return names;
        }

        void cancelParamHeaderTips()
        {
            tipsGeneration++;
            if (tipsJob != null)
            {
                tipsJob.cancel();
                tipsJob = null;
            }
        }

        void scheduleParamHeaderTooltips(List<String> names, Method method)
        {
            Table table = locationViewer.getTable();
            if (table == null || table.isDisposed())
                return;
            cancelParamHeaderTips();
            if (names == null || names.isEmpty())
                return;
            int gen = tipsGeneration;
            Job job = Job.create("Комфорт: подсказки колонок иерархии вызовов", monitor -> //$NON-NLS-1$
            {
                List<String> docs;
                try
                {
                    docs = ParamHintHtmlModifier.plainParamDescriptions(method, names);
                }
                catch (RuntimeException ignored)
                {
                    docs = Collections.emptyList();
                }
                if (monitor.isCanceled() || gen != tipsGeneration)
                    return org.eclipse.core.runtime.Status.CANCEL_STATUS;
                List<String> computed = docs;
                Display display = PlatformUI.getWorkbench().getDisplay();
                if (display == null || display.isDisposed())
                    return org.eclipse.core.runtime.Status.OK_STATUS;
                display.asyncExec(() ->
                {
                    if (gen != tipsGeneration || table.isDisposed())
                        return;
                    applyParamHeaderTooltips(table.getColumns(), names, computed);
                });
                return org.eclipse.core.runtime.Status.OK_STATUS;
            });
            job.setSystem(true);
            tipsJob = job;
            job.schedule();
        }

        void applyParamHeaderTooltips(TableColumn[] columns, List<String> names, List<String> docs)
        {
            if (columns == null || names == null || names.isEmpty())
                return;
            int start = paramColumnStart();
            int size = docs != null ? docs.size() : 0;
            for (int i = 0; i < names.size(); i++)
            {
                if (start + i >= columns.length)
                    break;
                TableColumn column = columns[start + i];
                String extra = i < size ? docs.get(i) : null;
                if (interaction != null)
                    interaction.setHeaderTooltipExtra(column, extra);
                else if (extra != null && !extra.isBlank())
                    column.setToolTipText(extra);
            }
        }

        String[] argsOf(Object element)
        {
            IReferenceDescription description = asDescription(element);
            if (description == null)
                return null;
            URI uri = sourceUri(description);
            if (uri == null)
                return null;
            String[] cached = argsBySource.get(uri);
            if (cached != null)
                return cached;
            String[] args = extractArgs(description);
            argsBySource.put(uri, args);
            return args;
        }

        String callerModuleOf(Object element)
        {
            return callerFields(element)[0];
        }

        String callerMethodOf(Object element)
        {
            return callerFields(element)[1];
        }

        String[] callerFields(Object element)
        {
            IReferenceDescription description = asDescription(element);
            URI uri = sourceUri(description);
            if (uri == null)
                return new String[] { "", "" }; //$NON-NLS-1$ //$NON-NLS-2$
            String[] cached = callerBySource.get(uri);
            if (cached != null)
                return cached;
            String[] fields = extractCallerFields(description);
            callerBySource.put(uri, fields);
            return fields;
        }

        String[] extractCallerFields(IReferenceDescription description)
        {
            return extractModuleMethod(resolveEObject(sourceUri(description)));
        }

        String[] extractModuleMethod(EObject object)
        {
            if (object == null)
                return new String[] { "", "" }; //$NON-NLS-1$ //$NON-NLS-2$
            Method method = object instanceof Method found ? found
                : EcoreUtil2.getContainerOfType(object, Method.class);
            String methodName = method != null && method.getName() != null ? method.getName() : ""; //$NON-NLS-1$
            Module module = EcoreUtil2.getContainerOfType(method != null ? method : object, Module.class);
            String moduleName = ""; //$NON-NLS-1$
            if (module != null)
            {
                EObject owner = module.getOwner();
                String full = GetRef.eObjectToFullName(owner != null ? owner : module);
                if (full == null || full.isBlank())
                    full = GetRef.eObjectToFullName(module);
                if (full != null && !full.isBlank())
                {
                    String stripped = GetRef.stripLowValueModuleSuffix(full);
                    moduleName = stripped != null && !stripped.isBlank() ? stripped : full;
                }
            }
            return new String[] { moduleName, methodName };
        }

        String[] extractArgs(IReferenceDescription description)
        {
            EObject source = resolveEObject(sourceUri(description));
            if (source == null)
                return new String[0];
            EList<Expression> params = paramsOf(source);
            if (params == null || params.isEmpty())
                return new String[0];
            String[] args = new String[params.size()];
            for (int i = 0; i < params.size(); i++)
                args[i] = expressionText(params.get(i));
            return args;
        }

        Method resolveCalledMethod(IReferenceDescription description)
        {
            if (description == null)
                return null;
            EObject target = resolveEObject(description.getTargetEObjectUri());
            if (target instanceof Method method)
                return method;
            if (target instanceof FormalParam)
            {
                Method enclosing = EcoreUtil2.getContainerOfType(target, Method.class);
                if (enclosing != null)
                    return enclosing;
            }
            EObject source = resolveEObject(sourceUri(description));
            Invocation invocation = source != null
                ? EcoreUtil2.getContainerOfType(source, Invocation.class)
                : null;
            if (invocation == null)
                return null;
            return resolveMethod(invocation.getMethodAccess());
        }

        static Method resolveMethod(FeatureAccess access)
        {
            if (access == null)
                return null;
            EList<FeatureEntry> entries = null;
            if (access instanceof DynamicFeatureAccess dynamic)
                entries = dynamic.getFeatureEntries();
            else if (access instanceof StaticFeatureAccess staticAccess)
                entries = staticAccess.getFeatureEntries();
            if (entries == null)
                return null;
            for (FeatureEntry entry : entries)
            {
                if (entry == null)
                    continue;
                EObject feature = entry.getFeature();
                if (feature instanceof Method method)
                    return method;
            }
            return null;
        }

        EObject resolveEObject(URI uri)
        {
            if (uri == null)
                return null;
            Object labelProvider = Global.getField(view, "labelProvider"); //$NON-NLS-1$
            Object resolved = Global.invoke(labelProvider, "getEObject", uri, Integer.valueOf(0)); //$NON-NLS-1$
            return resolved instanceof EObject object ? object : null;
        }

        static EList<Expression> paramsOf(EObject source)
        {
            Invocation invocation = EcoreUtil2.getContainerOfType(source, Invocation.class);
            if (invocation != null)
                return invocation.getParams();
            OperatorStyleCreator ctor = EcoreUtil2.getContainerOfType(source, OperatorStyleCreator.class);
            return ctor != null ? ctor.getParams() : null;
        }

        static String expressionText(Expression expression)
        {
            if (expression == null)
                return ""; //$NON-NLS-1$
            ICompositeNode node = NodeModelUtils.findActualNodeFor(expression);
            if (node == null)
                return ""; //$NON-NLS-1$
            String text = NodeModelUtils.getTokenText(node);
            return text != null ? text.trim() : ""; //$NON-NLS-1$
        }

        static IReferenceDescription asDescription(Object element)
        {
            return element instanceof IReferenceDescription description ? description : null;
        }

        static URI sourceUri(IReferenceDescription description)
        {
            return description != null ? description.getSourceEObjectUri() : null;
        }
    }

    private static final class LocationLabelProvider extends LabelProvider implements ITableLabelProvider
    {
        private final ITableLabelProvider delegate;
        private final Session session;

        LocationLabelProvider(ITableLabelProvider delegate, Session session)
        {
            this.delegate = delegate;
            this.session = session;
        }

        @Override
        public Image getColumnImage(Object element, int columnIndex)
        {
            if (columnIndex < BASE_COLUMN_COUNT)
                return delegate.getColumnImage(element, columnIndex);
            return null;
        }

        @Override
        public String getColumnText(Object element, int columnIndex)
        {
            if (columnIndex < BASE_COLUMN_COUNT)
                return delegate.getColumnText(element, columnIndex);
            if (session.hasCallerColumns())
            {
                if (columnIndex == BASE_COLUMN_COUNT)
                    return session.callerModuleOf(element);
                if (columnIndex == BASE_COLUMN_COUNT + 1)
                    return session.callerMethodOf(element);
            }
            String[] args = session.argsOf(element);
            int index = columnIndex - session.paramColumnStart();
            if (args == null || index < 0 || index >= args.length)
                return ""; //$NON-NLS-1$
            return args[index];
        }
    }

    private static final class Debug
    {
        private static final String TAG = "CallHierarchyView"; //$NON-NLS-1$

        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
