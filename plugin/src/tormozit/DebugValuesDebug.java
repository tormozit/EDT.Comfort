package tormozit;

import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;

/**
 * Диагностика панели «Значения» и команды «Инспектировать».
 *
 * <p>Включение: параметры → Комфорт → «Общее логирование».
 */
public final class DebugValuesDebug
{
    private static final String TAG = "DebugValues"; //$NON-NLS-1$

    static final int SLOW_UI_MS = 16;
    static final int SLOW_CMD_MS = 50;

    private DebugValuesDebug() {}

    public static boolean isEnabled()
    {
        return Global.isLogEnabled();
    }

    public static void step(String phase, String detail)
    {
        if (!isEnabled())
            return;
        if (detail == null || detail.isEmpty())
            Global.log(TAG, phase);
        else
            Global.log(TAG, phase + " " + detail); //$NON-NLS-1$
    }

    public static void perf(String phase, long startNano, String detail)
    {
        if (!isEnabled())
            return;
        Global.log(TAG, "perf " + phase + "=" + elapsedMs(startNano) + "ms " + detail); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    public static void perfSlow(String phase, long startNano, String detail)
    {
        if (!isEnabled())
            return;
        int ms = elapsedMs(startNano);
        int threshold = phase != null && phase.startsWith("inspect") ? SLOW_CMD_MS : SLOW_UI_MS; //$NON-NLS-1$
        if (ms >= threshold)
            Global.log(TAG, "perfSlow " + phase + "=" + ms + "ms " + detail); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    public static long begin()
    {
        return System.nanoTime();
    }

    public static int elapsedMs(long startNano)
    {
        return (int) ((System.nanoTime() - startNano) / 1_000_000L);
    }

    /**
     * Краткое описание таблицы для лога. Без {@code getItem(i)} по всем строкам —
     * на {@code SWT.VIRTUAL} это вызывает SetData на весь itemCount и блокирует UI
     * (ПКМ по «Коллекция» с тысячами строк).
     */
    public static String tableBrief(Table table)
    {
        if (table == null || table.isDisposed())
            return "table=disposed"; //$NON-NLS-1$
        int cols = table.getColumnCount();
        int items = table.getItemCount();
        int itemH = table.getItemHeight();
        int clientH = table.getClientArea().height;
        int visibleEst = itemH > 0 ? Math.max(0, clientH / itemH + 1) : -1;
        int top = table.getTopIndex();
        return "cols=" + cols + " rows=" + items //$NON-NLS-1$ //$NON-NLS-2$
            + " top=" + top + " visibleEst=" + visibleEst //$NON-NLS-1$ //$NON-NLS-2$
            + " headerW=" + sumColumnWidths(table); //$NON-NLS-1$
    }

    static int sumColumnWidths(Table table)
    {
        if (table == null || table.isDisposed())
            return 0;
        int sum = 0;
        for (TableColumn column : table.getColumns())
        {
            if (column != null && !column.isDisposed())
                sum += column.getWidth();
        }
        return sum;
    }

    static String quote(String text)
    {
        if (text == null)
            return "null"; //$NON-NLS-1$
        return "\"" + text + "\""; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
