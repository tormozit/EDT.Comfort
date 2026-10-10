package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.eclipse.core.resources.IFolder;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.graphics.Image;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.search.ui.ISearchResult;
import org.eclipse.search.ui.ISearchResultPage;
import org.eclipse.search.ui.ISearchResultViewPart;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.part.IPageSite;

/**
 * Режим «Недостижимые файлы» панели «Поиск»: таблица папок объектов метаданных, не связанных
 * с конфигурацией ({@link UnreachableFilesSearchResult}).
 */
public class UnreachableFilesResultPage implements ISearchResultPage
{
    private static final String SETTINGS_SECTION = "UnreachableFilesResults"; //$NON-NLS-1$
    private static final String KEY_COL_ORDER = "columnOrder"; //$NON-NLS-1$
    private static final String KEY_COL_FILL_MODE = "colFillMode"; //$NON-NLS-1$
    private static final String[] KEY_COL_WIDTHS = { "colPathWidth", "colFolderWidth", "colTypeWidth" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    private static final int[] DEFAULT_WIDTHS = { 360, 360, 160 };

    private String id;
    private ISearchResultViewPart viewPart;
    private IPageSite pageSite;
    private Composite tableStack;
    private Table table;
    private TableViewer tableViewer;
    private FormTableInteraction tableInteraction;
    private UnreachableFilesSearchResult searchResult;

    @Override
    public void init(IPageSite site) { this.pageSite = site; }

    @Override
    public IPageSite getSite() { return pageSite; }

    @Override
    public void createControl(Composite parent)
    {
        // FormTableInteraction (accent заголовка) требует родителя Table без layout —
        // эталон tableStack в CompareSearchResultPage.
        tableStack = new Composite(parent, SWT.NONE);
        tableStack.setLayout(null);
        table = new Table(tableStack, SWT.MULTI | SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        table.setHeaderVisible(true);
        ThemeAwareColors.applyGridLines(table);
        tableStack.addControlListener(new ControlAdapter()
        {
            @Override
            public void controlResized(ControlEvent e)
            {
                if (!table.isDisposed())
                    table.setBounds(tableStack.getClientArea());
            }
        });
        tableViewer = new TableViewer(table);
        tableViewer.setContentProvider(ArrayContentProvider.getInstance());

        IDialogSettings settings = dialogSettings();
        addColumn("Путь", 0, settings, MdReachability.Item::fullName);
        addColumn("Папка", 1, settings, MdReachability.Item::folder);
        addColumn("Тип", 2, settings, MdReachability.Item::type);
        FormTableColumnState.loadOrder(settings, KEY_COL_ORDER, table);

        tableViewer.addDoubleClickListener(event -> showInProjectStructure());
        installContextMenu();

        tableInteraction = new FormTableInteraction(table, tableViewer);
        tableInteraction.install(FormTableColumnState.hasSavedColumnWidths(settings, KEY_COL_FILL_MODE, KEY_COL_WIDTHS));
        // Панель не закрывается при повторном поиске: ширины сохраняются и в setInput.
        table.addDisposeListener(event -> saveColumnLayout());
    }

    private void addColumn(String title, int index, IDialogSettings settings, Function<MdReachability.Item, String> text)
    {
        TableViewerColumn column = new TableViewerColumn(tableViewer, SWT.LEFT);
        column.getColumn().setText(title);
        column.getColumn().setResizable(true);
        column.getColumn().setWidth(FormTableColumnState.readWidth(settings, KEY_COL_WIDTHS[index], DEFAULT_WIDTHS[index], 1));
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return element instanceof MdReachability.Item item ? text.apply(item) : ""; //$NON-NLS-1$
            }

            @Override
            public Image getImage(Object element)
            {
                return index == 0 && element instanceof MdReachability.Item item ? typeImage(item) : null;
            }
        });
    }

    /**
     * Значок вида метаданных по папке строки — штатный значок EDT для класса модели. У формы и
     * команды класс свой у каждого вида владельца ({@code CatalogForm}), у макета — общий.
     */
    private static Image typeImage(MdReachability.Item item)
    {
        String[] path = item.folder().split("/"); //$NON-NLS-1$
        if (path.length < 3)
            return null;
        String owner = MdTypeMapping.folderToEnSing(path[1]);
        String child = path.length == 5 ? MdTypeMapping.folderToEnSing(path[3]) : null;
        for (String name : child == null ? new String[] { owner } : new String[] { owner + child, child })
            if (name != null && MdClassPackage.eINSTANCE.getEClassifier(name) instanceof EClass type)
                return Global.mdClassImage(type);
        return null;
    }

    private List<MdReachability.Item> selection()
    {
        List<MdReachability.Item> items = new ArrayList<>();
        for (Object element : tableViewer.getStructuredSelection().toList())
            if (element instanceof MdReachability.Item item)
                items.add(item);
        return items;
    }

    private void installContextMenu()
    {
        MenuManager menuManager = new MenuManager();
        menuManager.setRemoveAllWhenShown(true);
        menuManager.addMenuListener(manager ->
        {
            List<MdReachability.Item> items = selection();
            UnreachableFilesSearchResult result = searchResult;
            if (items.isEmpty() || result == null)
                return;
            if (items.size() == 1)
            {
                Action show = new Action("Показать в структуре проекта")
                {
                    @Override
                    public void run() { showInProjectStructure(); }
                };
                show.setToolTipText(TooltipText.wrap(table,
                    "Показать папку в панели «Структура проекта»" + Global.pluginSignForTooltip()));
                manager.add(show);
                Action open = new Action("Открыть объект")
                {
                    @Override
                    public void run() { openObject(items.get(0)); }
                };
                open.setToolTipText(TooltipText.wrap(table,
                    "Открыть редактор объекта метаданных строки" + Global.pluginSignForTooltip()));
                manager.add(open);
            }
            Action attach = new Action("Подключить к родителю")
            {
                @Override
                public void run()
                {
                    MdReachability.attach(items, result.isIndexed(), attached -> removeAttached(result, attached));
                }
            };
            boolean attachable = items.stream().anyMatch(MdReachability::attachable);
            attach.setEnabled(attachable);
            attach.setToolTipText(TooltipText.wrap(table, (attachable
                ? "Прописать объекты выделенных строк у родителя: объект с описателем (файлом mdo) —"
                    + " в составе конфигурации (Configuration.mdo), форму и макет — в описателе владельца."
                    + " Остальные строки пропускаются"
                : "Среди выделенных строк нет ни объекта с описателем (файлом mdo), ни формы, ни макета:"
                    + " подключать нечего")
                + Global.pluginSignForTooltip()));
            manager.add(attach);
        });
        table.setMenu(menuManager.createContextMenu(table));
        table.addDisposeListener(event -> menuManager.dispose());
    }

    private void showInProjectStructure()
    {
        List<MdReachability.Item> items = selection();
        if (items.isEmpty())
            return;
        IFolder folder = MdReachability.folder(items.get(0));
        if (folder.exists())
            NavigatorShowInProjectStructureHandler.showInProjectStructure(new StructuredSelection(folder));
        else
            ToastNotification.show("Недостижимые файлы метаданных",
                "Папки нет в рабочем каталоге: " + items.get(0).folder(), 5_000);
    }

    /**
     * Недостижимого объекта в модели проекта может не быть вовсе — тогда открывать нечего,
     * и причина сообщается уведомлением.
     */
    private void openObject(MdReachability.Item item)
    {
        EObject object = item.mdo() != null
            ? GitChangedFileMenuHook.resolveEObject(item.project().getFile(item.mdo()))
            : GitChangedFileMenuHook.resolveEObjectForResource(MdReachability.folder(item));
        if (object != null)
            GitChangedFileMenuHook.openInEditor(object, null, table.getShell());
        else
            ToastNotification.show("Открыть объект", "Объекта " + item.fullName()
                + " нет в модели проекта: он не связан с конфигурацией."
                + (MdReachability.attachable(item) ? " Сначала выполните «Подключить к родителю»." : ""), 10_000);
    }

    private void removeAttached(UnreachableFilesSearchResult result, List<MdReachability.Item> attached)
    {
        result.getItems().removeAll(attached);
        if (result != searchResult || table == null || table.isDisposed())
            return;
        tableViewer.refresh();
        tableInteraction.resyncSelectionTheme();
        if (viewPart != null)
            viewPart.updateLabel();
    }

    @Override
    public void setInput(ISearchResult search, Object uiState)
    {
        saveColumnLayout();
        if (search instanceof UnreachableFilesSearchResult result)
        {
            searchResult = result;
            if (table != null && !table.isDisposed())
            {
                tableViewer.setInput(result.getItems());
                if (uiState instanceof List<?> selected)
                    tableViewer.setSelection(new StructuredSelection(selected), true);
                tableInteraction.resyncSelectionTheme();
                tableStack.setVisible(true);
            }
        }
        else if (search == null)
        {
            searchResult = null;
            if (table != null && !table.isDisposed())
            {
                tableViewer.setInput(List.of());
                // PageBook панели «Поиск» не всегда скрывает страницу при переключении на другой вид
                // результатов (issue #165) — тот же обход, что в CompareSearchResultPage.
                tableStack.setVisible(false);
                for (int delay : new int[] { 50, 200, 500, 1000, 2000 })
                    scheduleForceHide(tableStack, delay);
            }
        }
    }

    private void scheduleForceHide(Control control, int delayMs)
    {
        Display display = control.getDisplay();
        display.timerExec(delayMs, () ->
        {
            if (!control.isDisposed() && searchResult == null && control.getVisible())
                control.setVisible(false);
        });
    }

    @Override
    public void setViewPart(ISearchResultViewPart part) { this.viewPart = part; }

    @Override
    public Object getUIState()
    {
        return tableViewer == null || table == null || table.isDisposed() ? null
            : new ArrayList<>(tableViewer.getStructuredSelection().toList());
    }

    @Override
    public void setActionBars(IActionBars actionBars)
    {
    }

    @Override
    public void dispose()
    {
        saveColumnLayout();
        tableInteraction = null;
        tableStack = null;
        table = null;
        tableViewer = null;
        searchResult = null;
    }

    @Override
    public Control getControl() { return tableStack; }

    @Override
    public void setFocus()
    {
        if (table != null && !table.isDisposed())
            table.setFocus();
    }

    @Override
    public void setID(String id) { this.id = id; }

    @Override
    public String getID() { return id; }

    @Override
    public String getLabel()
    {
        return searchResult != null ? searchResult.getLabel() : "Недостижимые файлы метаданных";
    }

    @Override
    public void restoreState(IMemento memento)
    {
    }

    @Override
    public void saveState(IMemento memento) { saveColumnLayout(); }

    private void saveColumnLayout()
    {
        if (table == null || table.isDisposed() || table.getColumnCount() < KEY_COL_WIDTHS.length)
            return;
        FormTableColumnState.saveOrderAndWidths(dialogSettings(), KEY_COL_ORDER, KEY_COL_FILL_MODE,
            tableInteraction != null && tableInteraction.isColumnsExactFill(), KEY_COL_WIDTHS, table.getColumns(), table);
    }

    private static IDialogSettings dialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        return section != null ? section : top.addNewSection(SETTINGS_SECTION);
    }
}
