package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;

/**
 * Определяет видимые в данный момент подсказки и рисует их полупрозрачным
 * текстом в конце строки, где заканчивается соответствующая конструкция.
 * Цвет подсказки не настраивается отдельно — берётся из штатной подсветки
 * синтаксиса самого закрывающего ключевого слова ({@link StyleRange#foreground}
 * его последнего символа), чтобы подсказка визуально соответствовала токену,
 * к которому относится.
 *
 * <p>Номера строк в {@link BracketContentHintIndex.Entry} — это номера строк
 * ДОКУМЕНТА (модели). Если в редакторе свёрнут (folding) хотя бы один блок
 * выше по тексту, номера строк {@link StyledText} (виджета) расходятся с
 * номерами строк документа — поэтому все обращения к API виджета по номеру
 * строки/офсету идут только после перевода через {@link ITextViewerExtension5},
 * который знает о текущем состоянии сворачивания.
 */
final class BracketContentHintPresenter
{
    /** Высокая прозрачность (0 — невидимо, 255 — непрозрачно). */
    private static final int ALPHA = 90;

    private BracketContentHintPresenter()
    {
    }

    /** Подсказка, уже привязанная к строке и офсету ВИДЖЕТА (с учётом свёрнутых блоков). */
    static final class VisibleHint
    {
        final int widgetLine;
        /** Офсет виджета последнего символа закрывающего ключевого слова — источник цвета. -1, если перевод не удался. */
        final int widgetColorOffset;
        final String text;

        VisibleHint(int widgetLine, int widgetColorOffset, String text)
        {
            this.widgetLine = widgetLine;
            this.widgetColorOffset = widgetColorOffset;
            this.text = text;
        }
    }

    static List<VisibleHint> computeVisibleHints(StyledText widget, ITextViewerExtension5 lineMapper,
        List<BracketContentHintIndex.Entry> index, int minLines)
    {
        List<VisibleHint> result = new ArrayList<>();
        if (widget == null || widget.isDisposed() || lineMapper == null || index == null || index.isEmpty())
            return result;

        int widgetLineCount = widget.getLineCount();
        int widgetTopLine = widget.getLineIndex(0);
        int widgetBottomLine = widget.getLineIndex(Math.max(0, widget.getClientArea().height - 1));
        int caretWidgetLine = widget.getLineAtOffset(widget.getCaretOffset());

        for (BracketContentHintIndex.Entry entry : index)
        {
            int endWidgetLine = resolveVisibleWidgetLine(lineMapper, entry.endLine);
            if (endWidgetLine < 0 || endWidgetLine >= widgetLineCount)
                continue; // строка конца конструкции скрыта внутри свёрнутого блока
            if (endWidgetLine < widgetTopLine || endWidgetLine > widgetBottomLine)
                continue;
            if (endWidgetLine == caretWidgetLine)
                continue; // каретка стоит на строке подсказки — не мешаем редактированию

            int startWidgetLine = resolveVisibleWidgetLine(lineMapper, entry.startLine);
            boolean openingHidden = startWidgetLine < 0
                || startWidgetLine < widgetTopLine || startWidgetLine > widgetBottomLine;
            // Порог — по ВИДИМЫМ строкам (номера строк виджета), а не по строкам
            // документа: если между началом и концом свёрнут блок, видимых строк
            // там меньше, чем в документе, и порог должен учитывать именно это.
            if (!openingHidden && (endWidgetLine - startWidgetLine) < minLines)
                continue;

            int widgetColorOffset = lineMapper.modelOffset2WidgetOffset(entry.endOffset - 1);
            result.add(new VisibleHint(endWidgetLine, widgetColorOffset, entry.hintText));
        }
        return result;
    }

    /**
     * Переводит номер строки документа в номер строки виджета, но только
     * если эта строка ДЕЙСТВИТЕЛЬНО видна как отдельная строка. Для строк
     * внутри свёрнутого блока {@code modelLine2WidgetLine} не всегда
     * возвращает -1 — часто возвращает номер строки, где показан маркер
     * складывания ({@code ...}), ОБЩИЙ для всех скрытых строк этого блока.
     * Без проверки обратным переводом это приводило к тому, что подсказки
     * нескольких разных конструкций накладывались друг на друга на одной
     * строке ("склеивались").
     */
    private static int resolveVisibleWidgetLine(ITextViewerExtension5 lineMapper, int modelLine)
    {
        int widgetLine = lineMapper.modelLine2WidgetLine(modelLine);
        if (widgetLine < 0)
            return -1;
        if (lineMapper.widgetLine2ModelLine(widgetLine) != modelLine)
            return -1; // строка скрыта — виджет вернул общую "видимую" линию сворачивания
        return widgetLine;
    }

    static void paint(PaintEvent e, StyledText widget, List<VisibleHint> visibleHints)
    {
        if (visibleHints.isEmpty())
            return;

        GC gc = e.gc;
        for (VisibleHint hint : visibleHints)
        {
            try
            {
                int lineEndOffset = widget.getOffsetAtLine(hint.widgetLine)
                    + widget.getLine(hint.widgetLine).length();
                Point location = widget.getLocationAtOffset(lineEndOffset);

                gc.setForeground(resolveHintColor(widget, hint.widgetColorOffset));
                gc.setAlpha(ALPHA);
                gc.drawString(" " + hint.text, location.x, location.y, true); //$NON-NLS-1$
            }
            catch (IllegalArgumentException ignored)
            {
                // строка/офсет устарели между computeVisibleHints и paint (редкая гонка при правке)
            }
            finally
            {
                gc.setAlpha(255);
            }
        }
    }

    /**
     * Цвет закрывающего ключевого слова из штатной подсветки синтаксиса
     * ({@link StyleRange#foreground} последнего его символа). Цветовые
     * объекты в {@link StyleRange} принадлежат самому виджету/палитре
     * подсветки — их нельзя {@code dispose()}. Если стиль/цвет не задан
     * (обычный текст без подсветки), используется цвет текста по умолчанию.
     */
    private static Color resolveHintColor(StyledText widget, int widgetColorOffset)
    {
        if (widgetColorOffset >= 0 && widgetColorOffset < widget.getCharCount())
        {
            StyleRange style = widget.getStyleRangeAtOffset(widgetColorOffset);
            if (style != null && style.foreground != null)
                return style.foreground;
        }
        return widget.getForeground();
    }
}
