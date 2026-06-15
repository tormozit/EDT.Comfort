package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

/**
 * Диалог выбора строки стека при переходе по ссылке из буфера
 * (несколько кадров {@code {Модуль(строка)}} в тексте).
 *
 * <p>Размер, положение окна и ширины колонок — {@link #getDialogBoundsSettings()}.
 */
public class ModuleLinePickDialog extends Dialog
{
    private static final String SETTINGS_SECTION = "ModuleLinePickDialog"; //$NON-NLS-1$
    private static final String KEY_COL_MODULE_WIDTH = "colModuleWidth"; //$NON-NLS-1$
    private static final String KEY_COL_METHOD_WIDTH = "colMethodWidth"; //$NON-NLS-1$
    private static final String KEY_COL_LINE_WIDTH = "colLineWidth"; //$NON-NLS-1$

    private static final String KEY_COL_CODE_WIDTH = "colCodeWidth"; //$NON-NLS-1$
    private static final String KEY_COL_ORDER = "columnOrder"; //$NON-NLS-1$

    private static final int DEFAULT_MODULE_COL_WIDTH = 200;
    private static final int DEFAULT_METHOD_COL_WIDTH = 140;
    private static final int DEFAULT_LINE_COL_WIDTH = 90;
    private static final int DEFAULT_CODE_COL_WIDTH = 300;
    private static final int MIN_MODULE_COL_WIDTH = 120;
    private static final int MIN_METHOD_COL_WIDTH = 100;
    private static final int MIN_LINE_COL_WIDTH = 70;
    private static final int MIN_CODE_COL_WIDTH = 120;

    private final List<Row> rows;
    private TableViewer listViewer;
    private TableColumn moduleColumn;
    private TableColumn methodColumn;
    private TableColumn lineColumn;
    private TableColumn codeColumn;
    private FormTableInteraction tableInteraction;
    private int selectedIndex = -1;
    private boolean confirmed;

    public static final class Row
    {
        public final String module;
        public final String method;
        public final int lineNumber;
        public final String code;

        public Row(String module, String method, int lineNumber, String code)
        {
            this.module = module != null ? module : ""; //$NON-NLS-1$
            this.method = method != null ? method : ""; //$NON-NLS-1$
            this.lineNumber = lineNumber;
            this.code = code != null ? code : ""; //$NON-NLS-1$
        }
    }

    public ModuleLinePickDialog(Shell parentShell, List<Row> rows)
    {
        super(parentShell);
        this.rows = new ArrayList<>(rows);
        setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
    }

    @Override
    protected void configureShell(Shell shell)
    {
        super.configureShell(shell);
        shell.setText(Global.withPluginWindowTitle("Выберите строку стека")); //$NON-NLS-1$
    }

    @Override
    protected Point getInitialSize()
    {
        IDialogSettings settings = dialogSettings();
        if (settings.get("DIALOG_WIDTH") == null) //$NON-NLS-1$
            return new Point(650, 350);
        return super.getInitialSize();
    }

    @Override
    protected IDialogSettings getDialogBoundsSettings()
    {
        return dialogSettings();
    }

    @Override
    protected int getDialogBoundsStrategy()
    {
        return DIALOG_PERSISTSIZE | DIALOG_PERSISTLOCATION;
    }

    @Override
    protected boolean isResizable()
    {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new GridLayout(1, false));

        Label message = new Label(area, SWT.WRAP);
        message.setText("Найдено несколько ссылок. Выберите строку для перехода:"); //$NON-NLS-1$
        message.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        IDialogSettings settings = dialogSettings();
        int moduleWidth = readColWidth(settings, KEY_COL_MODULE_WIDTH,
            DEFAULT_MODULE_COL_WIDTH, MIN_MODULE_COL_WIDTH);
        int methodWidth = readColWidth(settings, KEY_COL_METHOD_WIDTH,
            DEFAULT_METHOD_COL_WIDTH, MIN_METHOD_COL_WIDTH);
        int lineWidth = readColWidth(settings, KEY_COL_LINE_WIDTH,
            DEFAULT_LINE_COL_WIDTH, MIN_LINE_COL_WIDTH);
        int codeWidth = readColWidth(settings, KEY_COL_CODE_WIDTH,
            DEFAULT_CODE_COL_WIDTH, MIN_CODE_COL_WIDTH);

        Composite tableStack = new Composite(area, SWT.NONE);
        tableStack.setLayout(null);
        tableStack.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite columnHost = new Composite(tableStack, SWT.NONE);
        TableColumnLayout columnLayout = new TableColumnLayout(true);
        columnHost.setLayout(columnLayout);

        listViewer = new TableViewer(columnHost,
            SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table table = listViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        TableViewerColumn colModule = new TableViewerColumn(listViewer, SWT.NONE);
        moduleColumn = colModule.getColumn();
        moduleColumn.setText("Модуль"); //$NON-NLS-1$
        colModule.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return ((Row) element).module;
            }
        });

        TableViewerColumn colMethod = new TableViewerColumn(listViewer, SWT.NONE);
        methodColumn = colMethod.getColumn();
        methodColumn.setText("Метод"); //$NON-NLS-1$
        colMethod.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return ((Row) element).method;
            }
        });

        TableViewerColumn colLine = new TableViewerColumn(listViewer, SWT.NONE);
        lineColumn = colLine.getColumn();
        lineColumn.setText("НомерСтроки"); //$NON-NLS-1$
        colLine.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return String.valueOf(((Row) element).lineNumber);
            }
        });

        TableViewerColumn colCode = new TableViewerColumn(listViewer, SWT.NONE);
        codeColumn = colCode.getColumn();
        codeColumn.setText("Код"); //$NON-NLS-1$
        colCode.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return ((Row) element).code;
            }
        });

        columnLayout.setColumnData(moduleColumn,
            new ColumnPixelData(moduleWidth, true, true));
        columnLayout.setColumnData(methodColumn,
            new ColumnPixelData(methodWidth, true, true));
        columnLayout.setColumnData(lineColumn,
            new ColumnPixelData(lineWidth, true, true));
        columnLayout.setColumnData(codeColumn,
            new ColumnPixelData(codeWidth, true, true));

        FormTableColumnOrder.load(settings, KEY_COL_ORDER, table);

        listViewer.setContentProvider(ArrayContentProvider.getInstance());
        listViewer.setInput(rows);

        tableInteraction = new FormTableInteraction(table, this::cellText);
        tableInteraction.setSelectionSync(item ->
            listViewer.setSelection(new StructuredSelection(item.getData())));
        tableInteraction.install();
        if (!rows.isEmpty())
        {
            TableItem first = table.getItem(0);
            if (first != null)
                tableInteraction.selectCell(first, 0);
        }

        installDoubleClick(table);
        installKeyListener(table);

        table.setFocus();
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, "Перейти", true); //$NON-NLS-1$
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void okPressed()
    {
        IStructuredSelection sel = listViewer.getStructuredSelection();
        if (sel.isEmpty())
            return;
        int idx = rows.indexOf(sel.getFirstElement());
        if (idx < 0)
            return;
        selectedIndex = idx;
        confirmed = true;
        super.okPressed();
    }

    @Override
    protected void cancelPressed()
    {
        confirmed = false;
        selectedIndex = -1;
        super.cancelPressed();
    }

    @Override
    public boolean close()
    {
        saveColumnWidths();
        return super.close();
    }

    public int getSelectedIndex()
    {
        return selectedIndex;
    }

    boolean wasConfirmed()
    {
        return confirmed;
    }

    private static IDialogSettings dialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }

    private static int readColWidth(IDialogSettings settings, String key,
        int defaultWidth, int minWidth)
    {
        String raw = settings.get(key);
        if (raw == null || raw.isEmpty())
            return defaultWidth;
        try
        {
            int w = Integer.parseInt(raw);
            return w >= minWidth ? w : defaultWidth;
        }
        catch (NumberFormatException ex)
        {
            return defaultWidth;
        }
    }

    private void saveColumnWidths()
    {
        if (moduleColumn == null || methodColumn == null || lineColumn == null || codeColumn == null
                || moduleColumn.isDisposed() || methodColumn.isDisposed()
                || lineColumn.isDisposed() || codeColumn.isDisposed())
            return;
        IDialogSettings settings = dialogSettings();
        settings.put(KEY_COL_MODULE_WIDTH, Integer.toString(moduleColumn.getWidth()));
        settings.put(KEY_COL_METHOD_WIDTH, Integer.toString(methodColumn.getWidth()));
        settings.put(KEY_COL_LINE_WIDTH, Integer.toString(lineColumn.getWidth()));
        settings.put(KEY_COL_CODE_WIDTH, Integer.toString(codeColumn.getWidth()));
        FormTableColumnOrder.save(settings, KEY_COL_ORDER, listViewer.getTable());
    }

    private String cellText(TableItem item, int column)
    {
        if (!(item.getData() instanceof Row row))
            return ""; //$NON-NLS-1$
        Table table = item.getParent();
        if (column < 0 || column >= table.getColumnCount())
            return ""; //$NON-NLS-1$
        TableColumn col = table.getColumn(column);
        if (col == moduleColumn)
            return row.module;
        if (col == methodColumn)
            return row.method;
        if (col == lineColumn)
            return String.valueOf(row.lineNumber);
        if (col == codeColumn)
            return row.code;
        return ""; //$NON-NLS-1$
    }

    private void installDoubleClick(Table table)
    {
        table.addListener(SWT.MouseDoubleClick, event -> okPressed());
    }

    private void installKeyListener(Table table)
    {
        table.addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR)
                    okPressed();
            }
        });
    }
}
