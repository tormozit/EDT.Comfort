package tormozit;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Колонка «Изменений» в таблице приложений мастера EDT «Обновление конфигурации в приложениях».
 *
 * <p>Показывает число объектов, ожидающих синхронизации с базой строки — тем же расчётом, что и
 * набор «&lt;Измененные <i>ИмяБазы</i>&gt;» ({@link InfobaseChangedObjects}). Клик по числу
 * открывает этот набор, как команда «Показать изменения» в панели «Приложения».
 *
 * <p>Число выводится <b>только для строк, помеченных «Обновить»</b>: расчёт стоит около половины
 * секунды на базу, и делать его для строк, которые обновляться не будут, незачем.
 *
 * <p>Доступ к штатной таблице: у диалога JFace сам диалог лежит в {@code shell.getData()}
 * ({@code Window#createShell}), оттуда — текущая страница мастера и её поле
 * {@code applicationsViewer}. У этого вьюера есть публичные {@code toApplication(Object)} и
 * {@code isApplicationSelectedForUpdate(IApplication)} — по ним и определяется строка и её пометка.
 * Колонка добавляется в сам {@link TableViewer}, а не текстом в {@code TableItem}: иначе штатное
 * обновление подписей при переключении пометок затирало бы её.
 *
 * @see DeployConfigurationFixHook хук того же диалога (флажок исправления формата 8.5)
 */
public final class DeployChangesColumnHook implements IStartup
{
    private static final String DIALOG_TITLE = "Обновление конфигурации в приложениях"; //$NON-NLS-1$

    private static final String PATCHED_KEY = "tormozit.deployChangesColumn"; //$NON-NLS-1$

    private static final String COLUMN_TITLE = "Изменений"; //$NON-NLS-1$

    private static final String COLUMN_TOOLTIP =
        "Число объектов, ожидающих синхронизации с базой. Клик — открыть их набор в панели " //$NON-NLS-1$
            + "«Наборы объектов». Считается только для приложений, помеченных «Обновить»"; //$NON-NLS-1$

    private static final String CONFIRMATION_COLUMN_TITLE = "Запрашивать подтверждение"; //$NON-NLS-1$

    private static final int COLUMN_WIDTH = 90;

    private static final int MAX_ATTEMPTS = 20;

    private static final String VIEWER_FIELD = "applicationsViewer"; //$NON-NLS-1$

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
            if (!(event.widget instanceof Shell shell) || shell.getData(PATCHED_KEY) != null)
                return;
            String title = shell.getText();
            if (title == null || !title.contains(DIALOG_TITLE))
                return;
            schedule(display, shell, 0);
        };
        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static void schedule(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;
        display.timerExec(attempt == 0 ? 0 : 100, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (!addColumn(shell) && attempt < MAX_ATTEMPTS)
                schedule(display, shell, attempt + 1);
        });
    }

    private static boolean addColumn(Shell shell)
    {
        Object viewer = findApplicationsViewer(shell);
        if (!(viewer instanceof TableViewer tableViewer))
            return false;
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed())
            return false;

        shell.setData(PATCHED_KEY, Boolean.TRUE);

        TableViewerColumn column = new TableViewerColumn(tableViewer, SWT.RIGHT, insertIndex(table));
        TableColumn tableColumn = column.getColumn();
        tableColumn.setText(COLUMN_TITLE);
        tableColumn.setToolTipText(COLUMN_TOOLTIP + Global.pluginSignForTooltip());
        tableColumn.setResizable(true);
        column.setLabelProvider(new ChangesLabelProvider(tableViewer));
        applyColumnWidth(table, tableColumn);

        addClickHandlers(tableViewer, table, tableColumn);
        tableViewer.refresh();
        return true;
    }

    /**
     * Позиция новой колонки — перед «Запрашивать подтверждение».
     *
     * @return индекс вставки; если штатной колонки не нашли — перед последней
     */
    private static int insertIndex(Table table)
    {
        for (int i = 0; i < table.getColumnCount(); i++)
        {
            String text = table.getColumn(i).getText();
            if (text != null && text.contains(CONFIRMATION_COLUMN_TITLE))
                return i;
        }
        return Math.max(0, table.getColumnCount() - 1);
    }

    /**
     * Задать ширину так, как этого требует layout таблицы.
     *
     * <p>Родитель таблицы в этом мастере управляется {@link TableColumnLayout}, а он считает
     * ширины сам по зарегистрированным {@code ColumnLayoutData} и игнорирует
     * {@code TableColumn#setWidth}. Колонка без регистрации ломает весь расчёт: ширины уезжают,
     * появляется горизонтальная прокрутка, а соседние контролы диалога перестают растягиваться.
     * Штатные колонки заданы весами, наша — фиксированной шириной в пикселях.
     */
    private static void applyColumnWidth(Table table, TableColumn column)
    {
        Composite parent = table.getParent();
        Layout layout = parent != null ? parent.getLayout() : null;
        if (layout instanceof TableColumnLayout columnLayout)
        {
            columnLayout.setColumnData(column, new ColumnPixelData(COLUMN_WIDTH, true, false));
            parent.layout(true);
            return;
        }
        column.setWidth(COLUMN_WIDTH);
    }

    /** Штатный вьюер таблицы приложений текущей страницы мастера. */
    private static Object findApplicationsViewer(Shell shell)
    {
        Object window = shell.getData();
        if (!(window instanceof WizardDialog dialog))
            return null;
        IWizardPage page = dialog.getCurrentPage();
        return page != null ? Global.getField(page, VIEWER_FIELD) : null;
    }

    // =======================================================================
    // Отрисовка
    // =======================================================================

    private static final class ChangesLabelProvider extends CellLabelProvider
    {
        private final TableViewer viewer;

        ChangesLabelProvider(TableViewer viewer)
        {
            this.viewer = viewer;
        }

        @Override
        public void update(ViewerCell cell)
        {
            Object element = cell.getElement();
            Display display = cell.getControl().getDisplay();
            if (!isSelectedForUpdate(viewer, element))
            {
                // Строка не помечена «Обновить» — не считаем.
                cell.setText(""); //$NON-NLS-1$
                cell.setForeground(null);
                return;
            }
            int count = changedCount(element);
            cell.setText(count > 0 ? Integer.toString(count) : "—"); //$NON-NLS-1$
            cell.setForeground(ThemeAwareColors.effectiveSystemColor(display,
                count > 0 ? SWT.COLOR_DARK_BLUE : SWT.COLOR_DARK_GRAY));
        }

        @Override
        public String getToolTipText(Object element)
        {
            if (!isSelectedForUpdate(viewer, element))
                return "Пометьте «Обновить», чтобы посчитать изменения"; //$NON-NLS-1$
            return changedCount(element) > 0
                ? "Нажмите, чтобы открыть набор изменённых объектов" //$NON-NLS-1$
                : "Нет объектов, ожидающих синхронизации с базой"; //$NON-NLS-1$
        }
    }

    // =======================================================================
    // Клик
    // =======================================================================

    private static void addClickHandlers(TableViewer viewer, Table table, TableColumn ourColumn)
    {
        table.addMouseMoveListener(new MouseMoveListener()
        {
            @Override
            public void mouseMove(MouseEvent e)
            {
                boolean hand = isOurCell(table, ourColumn, e.x, e.y)
                    && isClickable(viewer, elementAt(table, e.x, e.y));
                table.setCursor(hand ? table.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
            }
        });
        table.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseDown(MouseEvent e)
            {
                if (e.button != 1 || !isOurCell(table, ourColumn, e.x, e.y))
                    return;
                Object element = elementAt(table, e.x, e.y);
                if (isClickable(viewer, element))
                    InfobaseChangedObjects.showChangedSet(projectOf(element), infobaseOf(element));
            }
        });
    }

    private static boolean isClickable(TableViewer viewer, Object element)
    {
        return element != null && isSelectedForUpdate(viewer, element) && changedCount(element) > 0;
    }

    private static boolean isOurCell(Table table, TableColumn ourColumn, int x, int y)
    {
        TableItem item = table.getItem(new Point(x, y));
        if (item == null)
            return false;
        for (int i = 0; i < table.getColumnCount(); i++)
        {
            if (item.getBounds(i).contains(x, y))
                return table.getColumn(i) == ourColumn;
        }
        return false;
    }

    private static Object elementAt(Table table, int x, int y)
    {
        TableItem item = table.getItem(new Point(x, y));
        return item != null ? item.getData() : null;
    }

    // =======================================================================
    // Модель строки
    // =======================================================================

    /** Строка помечена «Обновить». Источник истины — сам штатный вьюер. */
    private static boolean isSelectedForUpdate(TableViewer viewer, Object element)
    {
        Object application = applicationOf(viewer, element);
        if (application == null)
            return false;
        Object selected = Global.invoke(viewer, "isApplicationSelectedForUpdate", application); //$NON-NLS-1$
        return Boolean.TRUE.equals(selected);
    }

    private static Object applicationOf(TableViewer viewer, Object element)
    {
        if (element == null)
            return null;
        Object application = Global.invoke(viewer, "toApplication", element); //$NON-NLS-1$
        return application != null ? application : Global.call(element, "getApplication"); //$NON-NLS-1$
    }

    private static int changedCount(Object element)
    {
        try
        {
            return InfobaseChangedObjects.changedCount(projectOf(element), infobaseOf(element));
        }
        catch (Throwable e)
        {
            // Вызывается из отрисовки ячейки и из MouseMove — падать здесь нельзя.
            return 0;
        }
    }

    private static IProject projectOf(Object element)
    {
        Object application = Global.call(element, "getApplication"); //$NON-NLS-1$
        Object project = Global.call(application != null ? application : element, "getProject"); //$NON-NLS-1$
        return project instanceof IProject result ? result : null;
    }

    private static InfobaseReference infobaseOf(Object element)
    {
        Object application = Global.call(element, "getApplication"); //$NON-NLS-1$
        return ApplicationsViewHook.getInfobase(application != null ? application : element);
    }
}
