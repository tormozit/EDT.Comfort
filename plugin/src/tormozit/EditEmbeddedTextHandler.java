package tormozit;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;
/**
 * Обработчик команды «Вложенный текст» в контекстном меню BSL-редактора.
 *
 * <p>Извлекает строковый литерал BSL под курсором и передаёт его в приложение ИР
 * через {@code ирКлиент.ОткрытьТекстЛкс(текст)}, аналогично тому, как
 * {@code DataCompositionSchemaEditorHook} вызывает
 * {@code ирКлиент.РедактироватьСхемуКомпоновкиИзФайлаЛкс()}.
 *
 * <h3>Поддерживаемые форматы BSL-строк</h3>
 * <ul>
 *   <li>Однострочные: {@code "текст"}</li>
 *   <li>Многострочные с продолжением через {@code |}:
 *       <pre>
 *       "первая строка
 *       |вторая строка
 *       |третья строка"
 *       </pre></li>
 * </ul>
 */
public final class EditEmbeddedTextHandler
{
    private EditEmbeddedTextHandler() {}

    // =========================================================================
    // Публичный API
    // =========================================================================

    /**
     * Быстрая проверка применимости команды: курсор находится внутри
     * строкового литерала BSL. Вызывается на UI-потоке при отображении меню.
     */
    public static boolean isApplicable(BslXtextEditor editor)
    {
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null) 
            return false;

        IXtextDocument doc = (IXtextDocument)viewer.getDocument();
        if (doc == null) 
            return false;

        Object sel = viewer.getSelectionProvider().getSelection();
        if (!(sel instanceof ITextSelection))
            return false;

        return extractStringLiteral(doc, ((ITextSelection) sel).getOffset()) != null;
    }

    /**
     * Открывает редактор вложенного текста в приложении ИР.
     *
     * <p>Алгоритм:
     * <ol>
     *   <li>Извлекает строковый литерал BSL под курсором.</li>
     *   <li>Определяет {@link IDtProject} по файлу, открытому в редакторе.</li>
     *   <li>Получает (или инициирует) сессию ИР для этого проекта.</li>
     *   <li>В потоке COM-сессии вызывает {@code ирКлиент.ОткрытьТекстЛкс(текст)}.</li>
     * </ol>
     */
    public static void editEmbeddedText(BslXtextEditor editor)
    {
        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        IRSession irSession = IRApplication.getSession(dtProject);
        if (irSession == null || irSession.executor == null)
        {
            return;
        }
        String ref = GetRef.buildExtendedRef(editor, false);
        irSession.syncCodeEditorToIR(editor);
        irSession.executor.submit(() ->
        {
            try
            {
                String textToOpen = irSession.selectTextLiteral();
                irSession.showWindow();
                irSession.openTextEditor(textToOpen, ref);
                ToastNotification.show("Редактор ИР", "Измененный вложенный текст вернется в EDT, если не будет изменяться там во время редактирования в приложении ИР!");
            }
            catch (Exception e)
            {
                Global.log("EditEmbeddedTextHandler: ошибка вызова ИР: " + e.getMessage()); //$NON-NLS-1$
                ToastNotification.show("Вложенный текст", "Ошибка вызова ИР: " + e.getMessage(), 5_000);
            }
        });
    }

    // =========================================================================
    // Извлечение строкового литерала BSL
    // =========================================================================

    /**
     * Возвращает содержимое строкового литерала BSL, в котором находится
     * позиция {@code offset}, без кавычек и символов продолжения {@code |}.
     *
     * <p>Поддерживает однострочные и многострочные (через {@code |}) литералы.
     * Возвращает {@code null}, если курсор не находится внутри литерала.
     */
    static String extractStringLiteral(IXtextDocument doc, int offset)
    {
        try
        {
//            QueryTextInfo literal = BslQueryWizardUtils.getCurrentStringLiteral(doc.module, doc.getLineOfOffset(offset), 0, 0);
            int cursorLine = doc.getLineOfOffset(offset);

            // ── Шаг 1: найти строку с открывающей кавычкой ─────────────────
            // Курсор может быть на строке продолжения (начинается с |),
            // поэтому идём назад до строки, которая НЕ является продолжением.

            int openingLineNum = cursorLine;
            for (int ln = cursorLine; ln >= 0; ln--)
            {
                IRegion li   = doc.getLineInformation(ln);
                String  text = doc.get(li.getOffset(), li.getLength());

                if (ln == cursorLine)
                {
                    if (!text.stripLeading().startsWith("|")) //$NON-NLS-1$
                    {
                        openingLineNum = ln; // курсор уже на строке с "
                        break;
                    }
                    // иначе — строка продолжения, идём выше
                }
                else
                {
                    // Строки выше курсора
                    if (!text.stripLeading().startsWith("|")) //$NON-NLS-1$
                    {
                        openingLineNum = ln;
                        break;
                    }
                }
            }

            // ── Шаг 2: найти открывающую кавычку на openingLineNum ──────────
            IRegion openLineInfo = doc.getLineInformation(openingLineNum);
            String  openLineText = doc.get(openLineInfo.getOffset(), openLineInfo.getLength());

            // Лимит поиска по X: если курсор на той же строке — до курсора;
            // если курсор на строке продолжения — весь текст строки.
            int scanLimit = (openingLineNum == cursorLine)
                ? offset - openLineInfo.getOffset()
                : openLineText.length();

            // Ищем последнюю незакрытую кавычку (при нечётном счётчике)
            int openQuoteIdx = -1;
            int quoteCount   = 0;
            for (int i = 0; i < scanLimit; i++)
            {
                if (openLineText.charAt(i) == '"')
                {
                    quoteCount++;
                    if (quoteCount % 2 == 1)
                        openQuoteIdx = i; // потенциальная открывающая
                    else
                        openQuoteIdx = -1; // закрылась
                }
            }

            if (openQuoteIdx < 0)
                return null; // курсор вне строки
            String afterOpenQuote = openLineText.substring(openQuoteIdx + 1);
            int closeIdx = afterOpenQuote.indexOf('"');
            if (closeIdx >= 0)
                return afterOpenQuote.substring(0, closeIdx);

            // Многострочный: собираем строки продолжения (начинаются с |)
            StringBuilder sb = new StringBuilder(afterOpenQuote);
            for (int ln = openingLineNum + 1; ln < doc.getNumberOfLines(); ln++)
            {
                IRegion li       = doc.getLineInformation(ln);
                String  lineText = doc.get(li.getOffset(), li.getLength());
                String  trimmed  = lineText.stripLeading();
                if (!trimmed.startsWith("|")) //$NON-NLS-1$
                    break; // не продолжение — литерал не закрыт корректно
                String lineContent = trimmed.substring(1); // убираем |
                int closeQuote = lineContent.indexOf('"');
                if (closeQuote >= 0)
                {
                    // Нашли закрывающую кавычку
                    sb.append('\n').append(lineContent, 0, closeQuote);
                    return sb.toString();
                }
                sb.append('\n').append(lineContent);
            }
            return null; // закрывающая кавычка не найдена
        }
        catch (BadLocationException e)
        {
            return null;
        }
    }
}
