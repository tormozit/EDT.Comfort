package tormozit;

import org.eclipse.swt.custom.StyledText;

/**
 * Минимальная горизонтальная прокрутка редактора, при которой открытое из результатов поиска
 * вхождение видно целиком. Левая часть строки важнее правой (без контекста слева конец длинной
 * строки малополезен) — если само вхождение шире окна, показываем от его начала. При нулевой
 * ширине клиентской области (виджет ещё не разложен) ничего не меняет — иначе прокрутка
 * встаёт по каретке. Общая для панелей "Результаты поиска" (по конфигурации —
 * {@code ConfigSearchResultsHook}, по файлам — {@code FileSearchResultsHook}) и
 * {@link BslEditorRevealScrollFixHook}.
 */
final class SearchMatchScrollSupport
{
    private static final int MARGIN = 20;

    private SearchMatchScrollSupport() {}

    static void applyLeftmost(StyledText widget, int selStart, int selEnd)
    {
        if (widget == null || widget.isDisposed())
            return;
        int scrollNow = widget.getHorizontalPixel();
        int startX = scrollNow + widget.getLocationAtOffset(selStart).x;
        int endX = scrollNow + widget.getLocationAtOffset(selEnd).x;
        int viewportWidth = widget.getClientArea().width;
        if (viewportWidth <= 0)
            return;
        int target = Math.max(0, endX + MARGIN - viewportWidth);
        if (target > startX - MARGIN)
            target = Math.max(0, startX - MARGIN); // вхождение шире окна — левая часть важнее
        widget.setHorizontalPixel(target);
    }
}
