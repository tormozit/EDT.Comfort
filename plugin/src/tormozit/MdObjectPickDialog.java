package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnWeightData;
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
 * Диалог выбора объекта при переходе к определению
 * (несколько кандидатов из описания типов или списка ИР).
 */
public class MdObjectPickDialog extends Dialog
{
    private static final String SETTINGS_SECTION = "MdObjectPickDialog"; //$NON-NLS-1$
    private static final String KEY_COL_ORDER = "columnOrder"; //$NON-NLS-1$

    private static final int MIN_PRESENTATION_COL_WIDTH = 100;
    private static final int MIN_LINK_COL_WIDTH = 120;

    /** Строка таблицы: колонки «Представление» и «Ссылка». */
    public static final class Entry
    {
        enum Kind { METADATA_NAME, IR_VALUE_PRESENTATION }

        private final Kind kind;
        private final String jumpTarget;
        private final String presentationText;
        private final String linkText;

        private Entry(Kind kind, String jumpTarget, String presentationText)
        {
            this.kind = kind;
            this.jumpTarget = jumpTarget != null ? jumpTarget : ""; //$NON-NLS-1$
            this.presentationText = presentationText != null ? presentationText : ""; //$NON-NLS-1$
            this.linkText = this.jumpTarget;
        }

        /** Локальный список полных имён метаданных ({@code Справочник.Валюты}). */
        public static Entry metadataName(String fullName)
        {
            String name = fullName != null ? fullName : ""; //$NON-NLS-1$
            return new Entry(Kind.METADATA_NAME, name, metadataObjectName(name));
        }

        /** Элемент списка ИР: COM {@code Значение} + {@code Представление}. */
        public static Entry irItem(String link, String presentation)
        {
            String l = link != null ? link : ""; //$NON-NLS-1$
            String p = presentation != null ? presentation.strip() : ""; //$NON-NLS-1$
            return new Entry(Kind.IR_VALUE_PRESENTATION, l, p);
        }

        Kind kind()
        {
            return kind;
        }

        /** Цель перехода ({@link #getSelectedFullName()}). */
        String jumpTarget()
        {
            return jumpTarget;
        }

        String presentationText()
        {
            return presentationText;
        }

        String linkText()
        {
            return linkText;
        }
    }

    private final List<Entry> entries;
    private TableViewer listViewer;
    private TableColumn presentationColumn;
    private TableColumn linkColumn;
    private FormTableInteraction tableInteraction;
    private String selectedFullName;

    public MdObjectPickDialog(Shell parentShell, List<Entry> entries)
    {
        super(parentShell);
        this.entries = new ArrayList<>(entries);
        setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
    }

    /** Локальный список полных имён метаданных (отдельно от {@link List}&lt;{@link Entry}&gt; из‑за erasure). */
    public static MdObjectPickDialog forMetadataNames(Shell parentShell, List<String> fullNames)
    {
        return new MdObjectPickDialog(parentShell, toMetadataEntries(fullNames));
    }

    private static List<Entry> toMetadataEntries(List<String> fullNames)
    {
        List<Entry> rows = new ArrayList<>();
        if (fullNames != null)
        {
            for (String name : fullNames)
                rows.add(Entry.metadataName(name));
        }
        return rows;
    }

    @Override
    protected void configureShell(Shell shell)
    {
        super.configureShell(shell);
        shell.setText(Global.withPluginWindowTitle("Выберите объект")); //$NON-NLS-1$
    }

    @Override
    protected Point getInitialSize()
    {
        return new Point(500, 350);
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
        message.setText("Найдено несколько объектов. Выберите для перехода:"); //$NON-NLS-1$
        message.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

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

        TableViewerColumn colPresentation = new TableViewerColumn(listViewer, SWT.NONE);
        TableColumn presentationColumn = colPresentation.getColumn();
        this.presentationColumn = presentationColumn;
        presentationColumn.setText("Представление"); //$NON-NLS-1$
        colPresentation.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return ((Entry) element).presentationText();
            }
        });

        TableViewerColumn colLink = new TableViewerColumn(listViewer, SWT.NONE);
        TableColumn linkColumn = colLink.getColumn();
        this.linkColumn = linkColumn;
        linkColumn.setText("Ссылка"); //$NON-NLS-1$
        colLink.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return ((Entry) element).linkText();
            }
        });

        columnLayout.setColumnData(presentationColumn,
            new ColumnWeightData(1, MIN_PRESENTATION_COL_WIDTH, true));
        columnLayout.setColumnData(linkColumn,
            new ColumnWeightData(2, MIN_LINK_COL_WIDTH, true));

        FormTableColumnOrder.load(dialogSettings(), KEY_COL_ORDER, table);

        listViewer.setContentProvider(ArrayContentProvider.getInstance());
        listViewer.setInput(entries);

        tableInteraction = new FormTableInteraction(table, this::cellText);
        tableInteraction.setSelectionSync(item ->
            listViewer.setSelection(new StructuredSelection(item.getData())));
        tableInteraction.install();
        selectFirst();

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
        if (!sel.isEmpty() && sel.getFirstElement() instanceof Entry entry)
            selectedFullName = entry.jumpTarget();
        super.okPressed();
    }

    @Override
    public boolean close()
    {
        saveColumnLayout();
        return super.close();
    }

    public String getSelectedFullName()
    {
        return selectedFullName;
    }

    private void selectFirst()
    {
        if (entries.isEmpty())
            return;
        listViewer.setSelection(new StructuredSelection(entries.get(0)));
        Table table = listViewer.getTable();
        if (tableInteraction != null && !table.isDisposed())
        {
            TableItem first = table.getItem(0);
            if (first != null)
                tableInteraction.selectCell(first, 0);
        }
    }

    private String cellText(TableItem item, int column)
    {
        if (!(item.getData() instanceof Entry entry))
            return ""; //$NON-NLS-1$
        Table table = item.getParent();
        if (column < 0 || column >= table.getColumnCount())
            return ""; //$NON-NLS-1$
        TableColumn col = table.getColumn(column);
        if (col == presentationColumn)
            return entry.presentationText();
        if (col == linkColumn)
            return entry.linkText();
        return ""; //$NON-NLS-1$
    }

    private void saveColumnLayout()
    {
        if (listViewer == null)
            return;
        Table table = listViewer.getTable();
        if (table == null || table.isDisposed())
            return;
        FormTableColumnOrder.save(dialogSettings(), KEY_COL_ORDER, table);
    }

    private static IDialogSettings dialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
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

    /** Имя объекта метаданных — часть полного имени после первой точки. */
    private static String metadataObjectName(String fullName)
    {
        if (fullName == null)
            return ""; //$NON-NLS-1$
        int dot = fullName.indexOf('.');
        return dot < 0 ? "" : fullName.substring(dot + 1); //$NON-NLS-1$
    }
}
