package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
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

/**
 * Диалог выбора объекта метаданных при переходе к определению
 * (несколько кандидатов из описания типов).
 */
public class MdObjectPickDialog extends Dialog
{
    private static final int MIN_TYPE_COL_WIDTH = 100;
    private static final int MIN_NAME_COL_WIDTH = 120;

    private final List<String> fullNames;
    private TableViewer listViewer;
    private String selectedFullName;

    public MdObjectPickDialog(Shell parentShell, List<String> fullNames)
    {
        super(parentShell);
        this.fullNames = new ArrayList<>(fullNames);
        setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
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

        Composite tableHost = new Composite(area, SWT.NONE);
        TableColumnLayout columnLayout = new TableColumnLayout(true);
        tableHost.setLayout(columnLayout);
        tableHost.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        listViewer = new TableViewer(tableHost,
            SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table table = listViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        TableViewerColumn colType = new TableViewerColumn(listViewer, SWT.NONE);
        TableColumn typeColumn = colType.getColumn();
        typeColumn.setText("Тип"); //$NON-NLS-1$
        colType.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return typeOf((String) element);
            }
        });

        TableViewerColumn colName = new TableViewerColumn(listViewer, SWT.NONE);
        TableColumn nameColumn = colName.getColumn();
        nameColumn.setText("Имя"); //$NON-NLS-1$
        colName.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return nameOf((String) element);
            }
        });

        columnLayout.setColumnData(typeColumn,
            new ColumnWeightData(1, MIN_TYPE_COL_WIDTH, true));
        columnLayout.setColumnData(nameColumn,
            new ColumnWeightData(2, MIN_NAME_COL_WIDTH, true));

        listViewer.setContentProvider(ArrayContentProvider.getInstance());
        listViewer.setInput(fullNames);
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
        if (!sel.isEmpty())
            selectedFullName = (String) sel.getFirstElement();
        super.okPressed();
    }

    public String getSelectedFullName()
    {
        return selectedFullName;
    }

    private void selectFirst()
    {
        if (!fullNames.isEmpty())
            listViewer.setSelection(new StructuredSelection(fullNames.get(0)));
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

    private static String typeOf(String fullName)
    {
        if (fullName == null)
            return ""; //$NON-NLS-1$
        int dot = fullName.indexOf('.');
        return dot < 0 ? fullName : fullName.substring(0, dot);
    }

    private static String nameOf(String fullName)
    {
        if (fullName == null)
            return ""; //$NON-NLS-1$
        int dot = fullName.indexOf('.');
        return dot < 0 ? "" : fullName.substring(dot + 1); //$NON-NLS-1$
    }
}
