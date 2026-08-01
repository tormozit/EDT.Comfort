package tormozit;

import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * Подсветка smart-фильтра в таблицах "Коллекция" — тем же стилем, что и во всех остальных фильтрах
 * плагина ({@link SmartMatchHighlight}: текст цвета фильтра из настроек, оверлей в
 * {@code SWT.PaintItem} поверх уже отрисованного). Раньше здесь была своя заливка жёлтым фоном в
 * {@code SWT.EraseItem}, которую приходилось пропускать для выделенной строки (иначе стирала фон
 * выделения, нарисованный {@code FormTableInteraction} раньше нас) — overlay-подход этого
 * ограничения не имеет: он ничего не заливает, просто дорисовывает текст поверх. {@code bold=false}
 * — жирный шрифт шире оригинала, а overlay не может сдвинуть текст правее совпадения, который
 * платформа уже отрисовала (см. фикс аналогичного бага в {@code PictureDialogHook}).
 */
final class DebugCollectionFilterHighlightSupport
{
    private DebugCollectionFilterHighlightSupport() {}

    static void handlePaintItem(Table table, TableItem item, Event e, SmartMatcher matcher,
            DebugCollectionTableHost host)
    {
        handlePaintItem(table, item, e, matcher, host, 0);
    }

    static void handlePaintItem(Table table, TableItem item, Event e, SmartMatcher matcher,
            DebugCollectionTableHost host, int visibleColumnOffset)
    {
        if (matcher == null || matcher.isEmpty || host == null)
            return;

        int logical = DebugCollectionTableItemKeys.logicalRow(item);
        if (logical < 0)
        {
            int displayIndex = DebugCollectionTableItemKeys.displayIndex(item, table);
            logical = host.displayIndexToLogical(displayIndex);
        }
        if (logical < 0 || e.index < 0)
            return;

        int visibleCol = visibleColumnOffset + e.index;
        String text = item.getText(e.index);
        if (text == null || text.isEmpty())
            text = host.getCellDisplayText(logical, visibleCol);
        // bold=false: overlay рисуется поверх уже отрисованной платформой ячейки и не может
        // сдвинуть текст правее совпадения — жирный шрифт шире и наезжал на соседние буквы (тот
        // же баг, что был в PictureDialogHook, см. историю фикса).
        SmartMatchHighlight.paintTableCellMatchOverlayFlat(e, table, item, matcher, text, false);
    }
}
