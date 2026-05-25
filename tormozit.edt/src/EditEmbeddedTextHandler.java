
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.ISelection;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Обработчик команды «Редактировать вложенный текст».
 *
 * <p>Вызывается из {@link BSLEditorMenuHook} при выборе одноимённого пункта
 * контекстного меню BSL-редактора.
 *
 * <h3>Контракт</h3>
 * <ul>
 *   <li>{@link #isApplicable(BslXtextEditor)} — быстрая проверка,
 *       нужно ли вообще показывать пункт меню (курсор находится внутри
 *       строкового литерала или в ином «вложенном» контексте).</li>
 *   <li>{@link #editEmbeddedText(BslXtextEditor)} — собственно открытие
 *       вложенного текста на редактирование.</li>
 * </ul>
 *
 * <p><b>TODO:</b> Реализовать логику определения вложенного текста
 * (строковый литерал / шаблон запроса / HTML-шаблон) и открытие
 * соответствующего диалога / дополнительного редактора.
 */
public final class EditEmbeddedTextHandler
{
    private EditEmbeddedTextHandler() {}

    // =========================================================================
    // Публичный API
    // =========================================================================

    /**
     * Проверяет, применима ли команда в текущем состоянии редактора.
     *
     * <p>Возвращает {@code true}, если курсор находится в позиции, для которой
     * имеет смысл редактировать вложенный текст (например, внутри строкового
     * литерала). {@code false} приводит к тому, что пункт меню не отображается.
     *
     * @param editor активный BSL-редактор; не {@code null}
     * @return {@code true} — команда применима
     */
    public static boolean isApplicable(BslXtextEditor editor)
    {
        // TODO: реализовать точную проверку контекста (лексический анализ позиции курсора)
        // Пример заглушки — команда всегда видима, пока логика не реализована:
        return true;
    }

    /**
     * Выполняет «Редактировать вложенный текст».
     *
     * <p>Определяет строковый литерал / шаблон, на котором стоит курсор,
     * и открывает его содержимое для редактирования в отдельном виджете.
     *
     * @param editor активный BSL-редактор; не {@code null}
     */
    public static void editEmbeddedText(BslXtextEditor editor)
    {
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null)
            return;

        IDocument doc = viewer.getDocument();
        if (doc == null)
            return;

        ISelection selection = viewer.getSelectionProvider().getSelection();
        if (!(selection instanceof ITextSelection))
            return;

        int offset = ((ITextSelection) selection).getOffset();

        String embeddedText = extractEmbeddedText(doc, offset);
        if (embeddedText == null)
        {
            Global.log("EditEmbeddedTextHandler: курсор не находится во вложенном тексте"); //$NON-NLS-1$
            return;
        }

        // TODO: открыть диалог / дополнительный редактор с embeddedText
        // Пример: EmbeddedTextDialog.open(editor.getSite().getShell(), embeddedText, doc, offset);
        Global.log("EditEmbeddedTextHandler: вложенный текст = " + embeddedText); //$NON-NLS-1$
    }

    // =========================================================================
    // Внутренняя логика
    // =========================================================================

    /**
     * Извлекает текст строкового литерала, в котором находится позиция {@code offset}.
     *
     * <p>В BSL строковые литералы могут быть:
     * <ul>
     *   <li>однострочными: {@code "текст"}</li>
     *   <li>многострочными (с продолжением через {@code |}).</li>
     * </ul>
     *
     * @param doc    документ редактора
     * @param offset смещение курсора
     * @return содержимое строкового литерала без кавычек, или {@code null}
     *         если курсор вне строкового литерала
     */
    private static String extractEmbeddedText(IDocument doc, int offset)
    {
        try
        {
            IRegion lineInfo = doc.getLineInformationOfOffset(offset);
            String line = doc.get(lineInfo.getOffset(), lineInfo.getLength());
            int posInLine = offset - lineInfo.getOffset();

            // Ищем ближайшую открывающую кавычку слева от курсора
            int openQuote = -1;
            for (int i = posInLine - 1; i >= 0; i--)
            {
                if (line.charAt(i) == '"')
                {
                    openQuote = i;
                    break;
                }
            }
            if (openQuote < 0)
                return null;

            // Ищем закрывающую кавычку справа от курсора
            int closeQuote = -1;
            for (int i = posInLine; i < line.length(); i++)
            {
                if (line.charAt(i) == '"')
                {
                    closeQuote = i;
                    break;
                }
            }
            if (closeQuote < 0)
                return null;

            return line.substring(openQuote + 1, closeQuote);
        }
        catch (BadLocationException e)
        {
            return null;
        }
    }
}
