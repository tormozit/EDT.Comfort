package tormozit;

import org.eclipse.swt.widgets.Table;

/**
 * Контракт таблицы «Коллекция» / скелета для interaction и подсветки фильтра.
 */
interface DebugCollectionTableHost
{

    Table collectionTable();
    int displayIndexToLogical(int displayIndex);
    String getCellDisplayText(int logicalRow, int visibleCol);
    SmartMatcher activeFilterMatcher();

    /** Смещение видимой колонки модели для таблицы split-раскладки. */
    default int firstVisibleColumnIndex(Table table)
    {
        return 0;
    }

    /** Превью полной строки для тултипа; {@code null} — не показывать. */
    default String getCellHoverToolTip(int logicalRow, int visibleCol)
    {
        return null;
    }
}
