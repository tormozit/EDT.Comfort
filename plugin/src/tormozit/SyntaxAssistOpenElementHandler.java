package tormozit;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Команда «Открыть синтакс-помощник» (Ctrl+F1) редактора модуля: подмена штатного обработчика EDT
 * {@code OpenBslEditorElementInSyntaxAssistViewCommandHandler}
 * ([#383](https://github.com/tormozit/EDT.Comfort/issues/383)).
 *
 * <p><b>Что делала EDT.</b> Если в редакторе есть выделение, штатный обработчик роль слова вообще
 * не вычислял — просто искал выделенный текст. Без выделения роль вычислялась, но статья
 * открывалась, только если страница ровно одна; при нескольких ролях или ни одной в нижнюю часть
 * панели попадала страница-заглушка «Неоднозначность» / «Ничего не найдено».
 *
 * <p><b>Что делаем мы.</b> Роль слова вычисляется всегда — по смещению каретки (или начала
 * выделения), независимо от наличия выделения. Результат раскладывается по обеим частям панели:
 * <ul>
 * <li><b>верхняя</b> — поиск по слову (как и раньше, вкладка «Поиск» панели навигации);</li>
 * <li><b>нижняя</b> — сразу статья рассчитанной роли, если роль однозначна. При неоднозначности
 * (несколько ролей) и когда роль не определилась, туда по-прежнему идёт штатная страница EDT
 * «Неоднозначный элемент» / «Ничего не найдено» — эти случаи должны быть отличимы от
 * определившейся роли.</li>
 * </ul>
 *
 * <p>Смещение берётся из {@link ISourceViewer#getSelectedRange()} — это <b>модельные</b>
 * координаты; виджетное {@code StyledText.getCaretOffset()} при свёрнутых участках указывает не
 * туда. Разбор документа делается вне потока UI (как и в EDT): он читает документацию платформы и
 * заметно блокирует.
 */
public final class SyntaxAssistOpenElementHandler extends AbstractHandler
{
    private static final String TEMP_TOPIC = "syntax-assist"; //$NON-NLS-1$

    /** Предыдущий незавершённый разбор: команду часто жмут подряд по разным словам. */
    private volatile Future<?> running;

    @Override
    public Object execute(ExecutionEvent event)
    {
        BslXtextEditor editor = resolveEditor(event);
        if (editor == null)
            return null;
        IXtextDocument document = editor.getDocument();
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (document == null || viewer == null)
            return null;
        Point range = viewer.getSelectedRange();
        Shell shell = editor.getSite() != null ? editor.getSite().getShell() : null;
        Display display = shell != null ? shell.getDisplay() : Display.getCurrent();
        if (display == null)
            return null;

        String selected = selectedText(document, range);
        Future<?> previous = running;
        if (previous != null)
        {
            previous.cancel(false);
            running = null;
        }
        running = CompletableFuture.runAsync(() -> resolveAndShow(document, range.x, selected, display));
        return null;
    }

    /** Редактор модуля: сам по себе или встроенный в редактор объекта метаданных. */
    private static BslXtextEditor resolveEditor(ExecutionEvent event)
    {
        IEditorPart editorPart = HandlerUtil.getActiveEditor(event);
        BslXtextEditor editor = GetRef.getActiveBslEditor(editorPart);
        if (editor != null)
            return editor;
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        return part != null ? GetRef.getActiveBslEditor(part) : null;
    }

    /** Выделенный текст в одну строку (как в штатном обработчике); {@code null} — нет выделения. */
    private static String selectedText(IXtextDocument document, Point range)
    {
        if (range == null || range.y <= 0)
            return null;
        try
        {
            return document.get(range.x, range.y).replaceAll("\\R", ""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (BadLocationException e)
        {
            Global.tempLogException(TEMP_TOPIC, "выделение " + range, e); //$NON-NLS-1$
            return null;
        }
    }

    /** Вне потока UI: определяем роль слова и запрос для поиска. */
    private void resolveAndShow(IXtextDocument document, int offset, String selected, Display display)
    {
        EObject element = BslSyntaxAssist.elementAt(document, offset);
        Object group = element != null ? BslSyntaxAssist.viewDocumentationPages(element) : null;
        List<?> pages = BslSyntaxAssist.pagesOf(group);
        String query = firstMeaningful(selected, BslSyntaxAssist.searchQueryOf(group),
            wordAt(document, offset));
        Global.tempLog(TEMP_TOPIC, "смещение " + offset + ", элемент=" //$NON-NLS-1$ //$NON-NLS-2$
            + (element == null ? "null" : element.eClass().getName()) //$NON-NLS-1$
            + ", страниц=" + pages.size() + ", запрос=" + query); //$NON-NLS-1$ //$NON-NLS-2$
        if (display.isDisposed())
            return;
        display.asyncExec(() -> show(pages, query));
    }

    /**
     * В потоке UI: поиск — в верхнюю часть панели, статья рассчитанной роли — в нижнюю.
     *
     * <p>Однозначная роль (ровно одна страница) — сразу её статья. Ноль ролей или несколько —
     * штатная страница EDT «Неоднозначный элемент» / «Ничего не найдено» со списком вариантов:
     * неоднозначность должна оставаться отличимой от определившейся роли.
     */
    private static void show(List<?> pages, String query)
    {
        if (BslSyntaxAssist.showView() == null)
            return;
        BslSyntaxAssist.showSearch(query);
        if (pages.size() == 1)
            BslSyntaxAssist.openViewPage(pages.get(0));
        else
            BslSyntaxAssist.openDescriptor(BslSyntaxAssist.pageGroupDescriptor(pages, query));
    }

    private static String firstMeaningful(String... values)
    {
        for (String value : values)
        {
            if (value != null && !value.isBlank())
                return value;
        }
        return null;
    }

    /** Слово под кареткой — запасной запрос для поиска, когда своего у группы нет. */
    private static String wordAt(IXtextDocument document, int offset)
    {
        try
        {
            IRegion line = document.getLineInformationOfOffset(offset);
            String text = document.get(line.getOffset(), line.getLength());
            int position = Math.max(0, Math.min(offset - line.getOffset(), text.length()));
            int start = position;
            int end = position;
            while (start > 0 && isWordChar(text.charAt(start - 1)))
                start--;
            while (end < text.length() && isWordChar(text.charAt(end)))
                end++;
            return start < end ? text.substring(start, end) : null;
        }
        catch (BadLocationException e)
        {
            return null;
        }
    }

    private static boolean isWordChar(char symbol)
    {
        return Character.isLetterOrDigit(symbol) || symbol == '_';
    }
}
