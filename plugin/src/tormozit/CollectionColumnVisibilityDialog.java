package tormozit;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * Диалог видимости колонок коллекции (⚙): список с чекбоксами, «Включить» / «Выключить».
 */
final class CollectionColumnVisibilityDialog extends Dialog
{
    private final CollectionColumnModel columns;
    private final boolean[] visibility;
    private int[] order;
    private int[] preselectedModelIndices;
    private int focusModelIndex = -1;

    private Table columnTable;
    private String findText = ""; //$NON-NLS-1$
    private int lastFindListIndex = -1;
    private int findGeneration;

    CollectionColumnVisibilityDialog(
        Shell parent,
        CollectionColumnModel columns,
        boolean[] visibility,
        int[] order)
    {
        super(parent);
        this.columns = columns;
        this.visibility = visibility.clone();
        this.order = order.clone();
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    boolean[] resultVisibility()
    {
        return visibility.clone();
    }

    int[] resultOrder()
    {
        return order.clone();
    }

    /** Выделить строки model-колонок; чекбоксы уже должны быть включены в {@code visibility}. */
    void preselectModelIndices(int[] modelIndices)
    {
        preselectedModelIndices = modelIndices != null ? modelIndices.clone() : null;
    }

    /** Только выделить и прокрутить к колонке (без изменения чекбоксов). */
    void focusModelIndex(int modelIndex)
    {
        focusModelIndex = modelIndex;
    }

    @Override
    protected void configureShell(Shell shell)
    {
        super.configureShell(shell);
        shell.setText(Global.withPluginWindowTitle("Колонки")); //$NON-NLS-1$
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new GridLayout(2, false));

        Label hint = new Label(area, SWT.WRAP);
        hint.setText(
            "Отметьте видимые колонки. «По алфавиту» — сортировка списка; «Вверх» / «Вниз» — порядок выделенной строки; Ctrl+F — поиск."); //$NON-NLS-1$
        hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Composite actionBar = new Composite(area, SWT.NONE);
        actionBar.setLayout(new GridLayout(5, false));
        actionBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Button enable = new Button(actionBar, SWT.PUSH);
        enable.setText("Включить"); //$NON-NLS-1$
        enable.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        enable.addListener(SWT.Selection, e -> setCheckedOnSelection(true));

        Button disable = new Button(actionBar, SWT.PUSH);
        disable.setText("Выключить"); //$NON-NLS-1$
        disable.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        disable.addListener(SWT.Selection, e -> setCheckedOnSelection(false));

        Button sortAlpha = new Button(actionBar, SWT.PUSH);
        sortAlpha.setText("По алфавиту"); //$NON-NLS-1$
        sortAlpha.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sortAlpha.addListener(SWT.Selection, e -> {
            // #region agent log
            CollectionLoadDebug.log("H-sort", "CollectionColumnVisibilityDialog.sortAlpha", //$NON-NLS-1$ //$NON-NLS-2$
                "sort clicked", "{\"items\":" + columnTable.getItemCount() + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // #endregion
            sortOrderAlphabetically();
        });

        Button up = new Button(actionBar, SWT.PUSH);
        up.setText("Вверх"); //$NON-NLS-1$
        up.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        up.addListener(SWT.Selection, e -> moveSelected(-1));

        Button down = new Button(actionBar, SWT.PUSH);
        down.setText("Вниз"); //$NON-NLS-1$
        down.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        down.addListener(SWT.Selection, e -> moveSelected(1));

        // #region agent log
        CollectionLoadDebug.log("H-sort", "CollectionColumnVisibilityDialog.createDialogArea", //$NON-NLS-1$ //$NON-NLS-2$
            "action bar with sort button", "{\"sortButton\":true}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion

        columnTable = new Table(area, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.MULTI);
        GridData listGd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        listGd.heightHint = 220;
        listGd.widthHint = 280;
        columnTable.setLayoutData(listGd);
        columnTable.setHeaderVisible(false);
        columnTable.setLinesVisible(true);
        refreshTableItems();
        applyPreselection();
        applyFocusSelection();
        installFindKeys();

        return area;
    }

    private void installFindKeys()
    {
        Shell shell = getShell();
        if (shell == null)
            return;
        Listener filter = this::onFindKey;
        shell.addListener(SWT.KeyDown, filter);
        if (columnTable != null)
            columnTable.addListener(SWT.KeyDown, filter);
    }

    private void onFindKey(Event event)
    {
        if (event.keyCode == 'f' && (event.stateMask & SWT.MOD1) != 0)
        {
            openFindDialog();
            event.doit = false;
        }
        else if (event.keyCode == SWT.F3)
        {
            findNext((event.stateMask & SWT.SHIFT) == 0);
            event.doit = false;
        }
    }

    private void openFindDialog()
    {
        InputDialog dialog = new InputDialog(
            getShell(),
            Global.withPluginWindowTitle("Поиск"), //$NON-NLS-1$
            "Текст для поиска в списке колонок:", //$NON-NLS-1$
            findText,
            null);
        if (dialog.open() != org.eclipse.jface.window.Window.OK)
            return;
        findText = dialog.getValue() != null ? dialog.getValue().trim() : ""; //$NON-NLS-1$
        findGeneration++;
        lastFindListIndex = -1;
        findNext(true);
    }

    private void findNext(boolean forward)
    {
        if (findText.isEmpty() || columnTable == null || columnTable.isDisposed())
            return;
        int count = columnTable.getItemCount();
        if (count <= 0)
            return;

        String needle = findText.toLowerCase();
        int start = lastFindListIndex < 0 ? (forward ? -1 : count) : lastFindListIndex;
        int gen = findGeneration;

        for (int step = 1; step <= count; step++)
        {
            int listIdx = forward ? (start + step) % count : (start - step + count) % count;
            TableItem item = columnTable.getItem(listIdx);
            String text = item != null ? item.getText() : null;
            if (text != null && text.toLowerCase().contains(needle))
            {
                if (gen != findGeneration)
                    return;
                lastFindListIndex = listIdx;
                columnTable.setSelection(listIdx);
                columnTable.setFocus();
                columnTable.showItem(item);
                return;
            }
        }
    }

    @Override
    protected void okPressed()
    {
        applyChecksToVisibility();
        super.okPressed();
    }

    private void applyPreselection()
    {
        if (preselectedModelIndices == null || preselectedModelIndices.length == 0
            || columnTable == null || columnTable.isDisposed())
            return;

        java.util.Set<Integer> wanted = new java.util.HashSet<>();
        for (int idx : preselectedModelIndices)
            wanted.add(idx);

        java.util.List<Integer> listIndices = new java.util.ArrayList<>();
        for (int listIdx = 0; listIdx < columnTable.getItemCount(); listIdx++)
        {
            TableItem item = columnTable.getItem(listIdx);
            Object data = item.getData();
            if (!(data instanceof Integer modelIdx))
                continue;
            if (!wanted.contains(modelIdx))
                continue;
            item.setChecked(true);
            listIndices.add(listIdx);
        }

        if (listIndices.isEmpty())
            return;

        selectListRows(listIndices, true);
    }

    private void applyFocusSelection()
    {
        if (focusModelIndex < 0 || columnTable == null || columnTable.isDisposed())
            return;
        if (preselectedModelIndices != null && preselectedModelIndices.length > 0)
            return;

        int listIdx = listIndexForModel(focusModelIndex);
        if (listIdx < 0)
            return;
        selectListRows(java.util.List.of(listIdx), true);
    }

    private int listIndexForModel(int modelIndex)
    {
        for (int listIdx = 0; listIdx < columnTable.getItemCount(); listIdx++)
        {
            TableItem item = columnTable.getItem(listIdx);
            Object data = item.getData();
            if (data instanceof Integer idx && idx.intValue() == modelIndex)
                return listIdx;
        }
        return -1;
    }

    private void selectListRows(java.util.List<Integer> listIndices, boolean focusTable)
    {
        if (listIndices.isEmpty())
            return;
        int firstListIdx = listIndices.get(0);
        int[] sel = new int[listIndices.size()];
        for (int i = 0; i < listIndices.size(); i++)
            sel[i] = listIndices.get(i);
        columnTable.setSelection(sel);
        if (focusTable)
            columnTable.setFocus();
        if (firstListIdx >= 0)
            columnTable.showItem(columnTable.getItem(firstListIdx));
    }

    private java.util.List<Integer> selectedModelIndices()
    {
        java.util.List<Integer> models = new java.util.ArrayList<>();
        for (int listIdx : columnTable.getSelectionIndices())
        {
            if (listIdx < 0 || listIdx >= columnTable.getItemCount())
                continue;
            Object data = columnTable.getItem(listIdx).getData();
            if (data instanceof Integer modelIdx)
                models.add(modelIdx);
        }
        return models;
    }

    private void sortOrderAlphabetically()
    {
        java.util.List<Integer> selectedModels = selectedModelIndices();
        java.util.List<Integer> sorted = new java.util.ArrayList<>(order.length);
        for (int modelIdx : order)
            sorted.add(modelIdx);
        sorted.sort((a, b) -> columnHeader(a).compareToIgnoreCase(columnHeader(b)));
        for (int i = 0; i < order.length; i++)
            order[i] = sorted.get(i);
        refreshTableItems();
        if (!selectedModels.isEmpty())
        {
            java.util.List<Integer> listIndices = new java.util.ArrayList<>();
            for (int modelIdx : selectedModels)
            {
                int listIdx = listIndexForModel(modelIdx);
                if (listIdx >= 0)
                    listIndices.add(listIdx);
            }
            if (!listIndices.isEmpty())
                selectListRows(listIndices, false);
        }
        else if (focusModelIndex >= 0)
        {
            int listIdx = listIndexForModel(focusModelIndex);
            if (listIdx >= 0)
                selectListRows(java.util.List.of(listIdx), false);
        }
    }

    private String columnHeader(int modelIndex)
    {
        if (modelIndex < 0 || modelIndex >= columns.allColumns().size())
            return ""; //$NON-NLS-1$
        String header = columns.allColumns().get(modelIndex).header;
        return header != null ? header : ""; //$NON-NLS-1$
    }

    private void refreshTableItems()
    {
        columnTable.removeAll();
        for (int modelIdx : order)
        {
            if (modelIdx < 0 || modelIdx >= columns.allColumns().size())
                continue;
            TableItem item = new TableItem(columnTable, SWT.NONE);
            item.setText(columns.allColumns().get(modelIdx).header);
            item.setChecked(modelIdx < visibility.length && visibility[modelIdx]);
            item.setData(Integer.valueOf(modelIdx));
        }
    }

    private void setCheckedOnSelection(boolean checked)
    {
        for (int listIdx : columnTable.getSelectionIndices())
        {
            if (listIdx >= 0 && listIdx < columnTable.getItemCount())
                columnTable.getItem(listIdx).setChecked(checked);
        }
    }

    private void applyChecksToVisibility()
    {
        for (int i = 0; i < visibility.length; i++)
            visibility[i] = false;
        for (TableItem item : columnTable.getItems())
        {
            Object data = item.getData();
            if (!(data instanceof Integer modelIdx))
                continue;
            if (modelIdx >= 0 && modelIdx < visibility.length && item.getChecked())
                visibility[modelIdx] = true;
        }
        if (noneVisible())
            visibility[0] = true;
    }

    private boolean noneVisible()
    {
        for (boolean v : visibility)
        {
            if (v)
                return false;
        }
        return true;
    }

    private void moveSelected(int delta)
    {
        int[] sel = columnTable.getSelectionIndices();
        if (sel == null || sel.length != 1)
            return;
        int idx = sel[0];
        int target = idx + delta;
        if (target < 0 || target >= order.length)
            return;
        int tmp = order[idx];
        order[idx] = order[target];
        order[target] = tmp;
        refreshTableItems();
        columnTable.select(target);
    }
}
