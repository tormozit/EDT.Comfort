package tormozit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.model.FunctionStyleCreator;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.OperatorStyleCreator;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;

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
        String queryKeyword = BslQueryLiteralSupport.queryKeyword(editor, viewer, range.x);
        Future<?> previous = running;
        if (previous != null)
        {
            previous.cancel(false);
            running = null;
        }
        running = CompletableFuture.runAsync(() -> resolveAndShow(document, range.x, selected, queryKeyword, display));
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
            return null;
        }
    }

    /** Вне потока UI: определяем роль слова и запрос для поиска. */
    private void resolveAndShow(IXtextDocument document, int offset, String selected, String queryKeyword,
        Display display)
    {
        EObject element = BslSyntaxAssist.elementAt(document, offset);
        if (queryKeyword != null)
        {
            List<?> queryPages = BslSyntaxAssist.queryKeywordPages(element, queryKeyword);
            if (!display.isDisposed())
                display.asyncExec(() -> show(queryPages, queryKeyword));
            return;
        }
        // Гарантированное имя типа: EDT описывает такой литерал статьёй «Строковый литерал», а не
        // типом. Статью типа ищем по имени — тем же путём, что ссылки типов в подсказках EDT.
        String typeName = typeNameAt(element);
        List<?> typePages = BslSyntaxAssist.typeNamePages(typeName, element);
        if (hasErrorPage(typePages))
            typePages = List.of();
        Object group = element != null ? BslSyntaxAssist.viewDocumentationPages(element) : null;
        List<?> rawPages = BslSyntaxAssist.pagesOf(group);
        List<?> filteredPages = distinctPages(rawPages.stream().filter(Objects::nonNull).toList());
        // Страница-ошибка вместо статьи (составной тип без кодов документации): берём запасной
        // источник — тот же, что у подсказки при наведении.
        List<?> recoveredPages = filteredPages.isEmpty() || hasErrorPage(filteredPages)
            ? element != null ? distinctPages(ParamHintHtmlModifier.collectRecoveredPages(element, element.eResource()))
                : List.of()
            : List.of();
        List<?> pages = !typePages.isEmpty() ? typePages
            : recoveredPages.isEmpty() ? filteredPages : recoveredPages;
        String query = firstMeaningful(typeName, selected, BslSyntaxAssist.searchQueryOf(group),
            wordAt(document, offset));
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
    static void show(List<?> pages, String query)
    {
        if (BslSyntaxAssist.showView() == null)
            return;
        BslSyntaxAssist.showSearch(query);
        if (pages.size() == 1)
            BslSyntaxAssist.openViewPage(pages.get(0));
        else
            BslSyntaxAssist.openDescriptor(BslSyntaxAssist.pageGroupDescriptor(pages, query));
    }

    /** Среди страниц есть штатная страница-ошибка EDT ({@code ErrorBslDocumentationPage}). */
    private static boolean hasErrorPage(List<?> pages)
    {
        for (Object page : pages)
        {
            if (page == null)
                continue;
            Object view = Global.invoke(page, "getViewPage"); //$NON-NLS-1$
            if (page.getClass().getName().endsWith(".ErrorBslDocumentationPage") //$NON-NLS-1$
                || view != null && view.getClass().getName().endsWith(".ErrorBslDocumentationPage")) //$NON-NLS-1$
                return true;
        }
        return false;
    }

    /**
     * Одна статья на член. Объединение типов приёмника даёт один и тот же член дважды
     * (одинаковые {@code FeatureEntry}: владелец, имя и окружения совпадают), и EDT строит по статье
     * на каждую запись — отсюда «Неоднозначный элемент» с двумя одинаковыми вариантами.
     * Разные статьи (разные владельцы или разные поля) остаются — неоднозначность сохраняется.
     */
    private static List<?> distinctPages(List<?> pages)
    {
        if (pages == null || pages.size() < 2)
            return pages;
        List<Object> result = new ArrayList<>(pages.size());
        Set<String> seen = new HashSet<>();
        for (Object page : pages)
        {
            if (page == null)
                continue;
            Object platformId = Global.invoke(page, "getPlatformId"); //$NON-NLS-1$
            Object link = Global.invoke(page, "getLink"); //$NON-NLS-1$
            Object title = Global.invoke(page, "getExternalTitle"); //$NON-NLS-1$
            String key = platformId != null ? "id:" + platformId //$NON-NLS-1$
                : link != null || title != null ? page.getClass().getName() + "|" + link + "|" + title //$NON-NLS-1$ //$NON-NLS-2$
                    : page.getClass().getName() + "|" + System.identityHashCode(page); //$NON-NLS-1$
            if (seen.add(key))
                result.add(page);
        }
        return result;
    }

    /**
     * Имя типа, если слово под кареткой — гарантированно имя типа: единственный аргумент
     * {@code Тип("…")} / {@code Type("…")}, имя в {@code Новый("…")} или {@code Новый <Тип>}.
     * Иначе {@code null}.
     *
     * <p>Строковый литерал EDT описывает статьёй «Строковый литерал»: в ней нет описания самого
     * типа, поэтому статью типа ищем по имени ({@link BslSyntaxAssist#typeNamePages}).
     */
    private static String typeNameAt(EObject element)
    {
        if (element instanceof StringLiteral literal)
        {
            EObject container = literal.eContainer();
            boolean typeName = container instanceof Invocation call && call.getParams().size() == 1
                && call.getParams().get(0) == literal
                && call.getMethodAccess() instanceof StaticFeatureAccess access
                && ("Тип".equalsIgnoreCase(access.getName()) || "Type".equalsIgnoreCase(access.getName())) //$NON-NLS-1$ //$NON-NLS-2$
                || container instanceof FunctionStyleCreator creator && creator.getTypeNameExpression() == literal;
            return typeName ? literalTypeName(literal) : null;
        }
        Type type = element instanceof OperatorStyleCreator creator ? creator.getType()
            : element instanceof Type found ? found : null;
        if (type == null)
            return null;
        String name = McoreUtil.getTypeNameRu(type);
        return name != null && !name.isBlank() ? name : McoreUtil.getTypeName(type);
    }

    /** Значение однострочного строкового литерала; многострочный — не имя типа. */
    private static String literalTypeName(StringLiteral literal)
    {
        ICompositeNode node = NodeModelUtils.findActualNodeFor(literal);
        String text = node != null ? NodeModelUtils.getTokenText(node) : null;
        if (text == null)
            return null;
        text = text.trim();
        if (text.length() < 2 || text.charAt(0) != '"' || text.charAt(text.length() - 1) != '"')
            return null;
        String name = text.substring(1, text.length() - 1).replace("\"\"", "\"").trim(); //$NON-NLS-1$ //$NON-NLS-2$
        return name.isEmpty() || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0 ? null : name;
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
