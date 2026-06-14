package tormozit;

import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * Ctrl+F / F3 / Shift+F3 по таблице коллекции.
 */
final class CollectionFindSession
{
    private final ComfortCollectionWindow window;
    private String findText = ""; //$NON-NLS-1$
    private int findGeneration;
    private int lastDisplayIndex = -1;

    CollectionFindSession(ComfortCollectionWindow window)
    {
        this.window = window;
    }

    void openFindDialog(Shell shell)
    {
        if (shell != null && shell.isDisposed())
            shell = null;
        InputDialog dialog = new InputDialog(
            shell,
            Global.withPluginWindowTitle("Поиск"), //$NON-NLS-1$
            "Текст для поиска:", //$NON-NLS-1$
            findText,
            null);
        if (dialog.open() != org.eclipse.jface.window.Window.OK)
            return;
        findText = dialog.getValue() != null ? dialog.getValue().trim() : ""; //$NON-NLS-1$
        findGeneration++;
        lastDisplayIndex = -1;
        findNext(true);
    }

    void findNext(boolean forward)
    {
        if (findText.isEmpty())
            return;
        Table table = window.getTable();
        if (table == null || table.isDisposed())
            return;

        int count = table.getItemCount();
        if (count <= 0)
            return;

        String needle = findText.toLowerCase();
        int start = lastDisplayIndex < 0 ? (forward ? -1 : count) : lastDisplayIndex;
        int gen = findGeneration;

        for (int step = 1; step <= count; step++)
        {
            int idx = forward ? (start + step) % count : (start - step + count) % count;
            int logical = window.displayIndexToLogical(idx);
            if (logical < 0)
                continue;
            int hitCol = findHitColumn(logical, needle);
            if (hitCol >= 0)
            {
                if (gen != findGeneration)
                    return;
                lastDisplayIndex = idx;
                window.selectDisplayRow(idx, hitCol);
                TableItem item = table.getItem(idx);
                if (item != null)
                    table.showItem(item);
                window.requestRowLoad(logical);
                return;
            }
        }
    }

    private int findHitColumn(int logicalRow, String needle)
    {
        int cols = window.getModel().columns.columnCount();
        for (int c = 0; c < cols; c++)
        {
            String text = window.getModel().getCellDisplayText(logicalRow, c);
            if (text != null && text.toLowerCase().contains(needle))
                return c;
        }
        return -1;
    }
}
