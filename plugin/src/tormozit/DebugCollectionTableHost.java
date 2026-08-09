package tormozit;

import java.util.List;
import java.util.Map;

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

    /**
     * Сброс постороннего substring-поиска (поле фильтра над таблицей) — вызывается из «Отключить все
     * отборы» перед снятием column-value. Реализация по умолчанию — noop.
     */
    default void clearSubstringFilter()
    {
    }

    /**
     * Элемент модели активной строки {@code table} для внешнего отбора ({@code Integer} logical row),
     * либо {@code null}. Реализация по умолчанию — отбор не поддерживается (напр. скелет).
     */
    default Object activeLogicalElement(Table table)
    {
        return null;
    }

    /**
     * Применить отбор по значениям колонок (AND; {@code visibleCol → value}, пустая → снят).
     * Реализация по умолчанию — noop (скелет не поддерживает column-value отбор).
     */
    default void applyColumnValueFilters(Map<Integer, String> filters)
    {
    }

    /**
     * Различные значения колонки + число строк — для «Различные значения колонки». Реализация по
     * умолчанию — пустой список (скелет).
     */
    default List<ColumnValuesDialog.ValueRow> distinctColumnValues(
        int visibleCol, boolean honorOtherFilters)
    {
        return List.of();
    }
}
