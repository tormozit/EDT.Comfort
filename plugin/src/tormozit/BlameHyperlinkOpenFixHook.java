package tormozit;

import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.jgit.diff.DiffEntry.Side;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IStartup;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Обход штатного бага Eclipse/EGit: в списке гиперссылок номера строки
 * всплывающего окна различий (blame-попап {@code BlameInformationControl} →
 * {@code MultipleHyperlinkPresenter$LinkListInformationControl}) клик по
 * команде не долетает — внешний hover-менеджер закрывает popup-в-popup
 * раньше, чем SWT доставит MouseDown/MouseUp (Dispose стартует ~100 мс после
 * остановки мыши, без единого MouseDown/MouseUp по Table).
 * <p>
 * https://github.com/1C-Company/1c-edt-issues/issues/1952
 * https://github.com/tormozit/EDT.Comfort/issues/188
 * <p>
 * Обход: при первом {@code FocusOut} на {@link Table}, чей первый элемент
 * несёт {@link IHyperlink} в {@code getData()} (надёжный маркер именно
 * этого штатного попапа, без привязки к недоступным для компиляции
 * внутренним классам), открываем ссылку, которая была выделена под
 * курсором (см. {@code LinkListInformationControl}: выделение синхронно
 * следует за наведением, {@code MouseUp}/{@code Enter} штатно тоже просто
 * открывают текущее выделение).
 */
public final class BlameHyperlinkOpenFixHook implements IStartup
{
    private static final String TAG = "BlameHyperlinkOpenFixHook"; //$NON-NLS-1$
    /**
     * Пока курсор идёт к нужному пункту, штатный hover-менеджер успевает создать и закрыть
     * НЕСКОЛЬКО {@code LinkListInformationControl} (попапы моргают) — без этой отметки
     * {@code FocusOut} у КАЖДОГО из них (включая те, что
     * реально ни разу не наводились) активировал бы свой пункт по умолчанию (индекс 0), открывая
     * случайный файл вперемешку с нужным сравнением ("рулетка"). Ставим маркер только на попапе,
     * где реально было движение мыши.
     */
    private static final String HOVERED_MARKER = "tormozit.blameHyperlinkTableHovered"; //$NON-NLS-1$
    /** По этой таблице реально прошёл клик — штатный {@code mouseUp} сам всё откроет, см. {@link #handleMouseUp}. */
    private static final String NATIVE_CLICK_MARKER = "tormozit.blameHyperlinkTableNativeClick"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            if (display.isDisposed())
                return;
            display.addFilter(SWT.MouseMove, BlameHyperlinkOpenFixHook::handleMouseMove);
            display.addFilter(SWT.MouseDown, BlameHyperlinkOpenFixHook::handleMouseDown);
            display.addFilter(SWT.FocusOut, BlameHyperlinkOpenFixHook::handleFocusOut);
            display.addFilter(SWT.MouseUp, BlameHyperlinkOpenFixHook::handleMouseUp);
        });
    }

    private static void handleMouseMove(Event event)
    {
        if (!(event.widget instanceof Table table) || table.isDisposed())
            return;
        if (isLinkListTable(table))
            table.setData(HOVERED_MARKER, Boolean.TRUE);
    }

    /**
     * Переход на строку у "Открыть двухпанельное сравнение" нужно захватывать здесь, на
     * {@code MouseDown}, а не позже: если клик по попапу долетает штатно (не наш случай #1952,
     * а нормальная работа — см. {@link #handleMouseUp}), родной {@code mouseUp}-обработчик
     * СНАЧАЛА уничтожает попап ({@code hideInformationControl()}) и только потом открывает
     * ссылку — а наш собственный {@code SWT.MouseUp}-фильтр получает управление уже ПОСЛЕ
     * этого уничтожения (порядок фильтров того же типа события не гарантирован раньше
     * штатного обработчика виджета), таблица к этому моменту {@code disposed}, и
     * {@code resolveCompareLineReveal} не вызывается вовсе (подтверждено логом: сравнение
     * открывалось штатно, но без единой нашей записи в логе). {@code MouseDown}
     * гарантированно происходит РАНЬШЕ этого уничтожения.
     */
    private static void handleMouseDown(Event event)
    {
        if (event.widget instanceof Table table)
        {
            handleTableMouseDown(table, event);
            return;
        }
        if (event.widget instanceof StyledText text)
            handleStyledTextMouseDown(text, event);
    }

    private static void handleTableMouseDown(Table table, Event event)
    {
        if (table.isDisposed() || !isLinkListTable(table))
            return;
        TableItem item = table.getItem(new Point(event.x, event.y));
        if (item == null || !(item.getData() instanceof IHyperlink link))
            return;
        int[] pendingReveal = resolveCompareLineReveal(link, table);
        if (pendingReveal != null)
            CompareEditorCurrentLinesHook.setPendingLineReveal(pendingReveal[0], pendingReveal[1] != 0);
    }

    /**
     * Номер строки в заголовке хунка ("@@ -52,3 +53,3 @@") активируется не через попап
     * {@code LinkListInformationControl} вовсе — промежуточные попапы гаснут сами (~100 мс churn,
     * как и в основном случае #1952), а клик,
     * судя по всему, попадает уже прямо в подчёркнутый текст {@link StyledText} под курсором —
     * штатная одиночная активация гиперссылки JFace, минуя наш перехват через Table совсем.
     * <p>
     * Вместо попытки перехватить сам объект {@code IHyperlink} (нет доступа к списку кандидатов
     * без попапа) — разбираем ТЕКСТ строки под курсором напрямую тем же форматом, что и штатный
     * {@code DiffViewer$HyperlinkDetector.HUNK_LINE_PATTERN}: "@@ -СТАРЫЙ,N +НОВЫЙ,N @@".
     * Определяем, в какую из двух цифровых групп попал клик — так же надёжно узнаём номер
     * строки и сторону, не завися от того, как именно потом активируется ссылка.
     */
    private static final Pattern HUNK_HEADER_PATTERN =
        Pattern.compile("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@"); //$NON-NLS-1$

    private static void handleStyledTextMouseDown(StyledText text, Event event)
    {
        if (text.isDisposed())
            return;
        try
        {
            int offset = text.getOffsetAtPoint(new Point(event.x, event.y));
            if (offset < 0)
                return;
            int line = text.getLineAtOffset(offset);
            String lineText = text.getLine(line);
            Matcher m = HUNK_HEADER_PATTERN.matcher(lineText);
            if (!m.find())
                return;
            int col = offset - text.getOffsetAtLine(line);
            if (col >= m.start(1) && col <= m.end(1))
                CompareEditorCurrentLinesHook.setPendingLineReveal(Integer.parseInt(m.group(1)) - 1, true);
            else if (col >= m.start(2) && col <= m.end(2))
                CompareEditorCurrentLinesHook.setPendingLineReveal(Integer.parseInt(m.group(2)) - 1, false);
        }
        catch (Exception e)
        {
            // best effort — не мешаем штатному клику ни при каких обстоятельствах
        }
    }

    /**
     * Настоящий клик по попапу долетает НЕ ВСЕГДА: в blame-попапе окна модуля (исходный баг
     * https://github.com/1C-Company/1c-edt-issues/issues/1952) MouseDown/MouseUp не приходят
     * вовсе — попап закрывается раньше, и команда не выполняется; но в панели «История» тот же
     * попап клик получает штатно, его собственный {@code mouseUp} вызывает
     * {@code openSelectedLink()} и всё уже работает. Без этой отметки мы в таком случае
     * открывали сравнение ВТОРОЙ раз: повторный {@code CompareEditor.doSetInput} вызывает
     * {@code Job.getJobManager().cancel(this)}, отменяя job первого открытия — состояние
     * становится CANCELED, и редактор закрывал сам себя через ~100 мс (подтверждено логом:
     * MouseUp по Table → наш FocusOut → partInputChanged → partClosed state=3).
     */
    private static void handleMouseUp(Event event)
    {
        if (!(event.widget instanceof Table table) || table.isDisposed())
            return;
        if (isLinkListTable(table))
        {
            table.setData(NATIVE_CLICK_MARKER, Boolean.TRUE);
            /*
             * Если попап всплывает ПРЯМО ПОД уже неподвижным курсором (мышь никуда не двигалась
             * к этой точке — она там уже стояла), MouseMove по таблице вообще не генерируется,
             * HOVERED_MARKER не ставится, и handleFocusOut выходит раньше resolveCompareLineReveal
             * (например, номер строки в заголовке хунка "@@ ... +53,3 @@" — попап там появляется
             * ровно там же, где стоял курсор). MouseDown/MouseUp по таблице — сами по себе
             * достаточное доказательство реального взаимодействия.
             */
            table.setData(HOVERED_MARKER, Boolean.TRUE);
        }
    }

    private static void handleFocusOut(Event event)
    {
        if (!(event.widget instanceof Table table) || table.isDisposed())
            return;
        if (!Boolean.TRUE.equals(table.getData(HOVERED_MARKER)))
            return; // попап мелькнул и закрылся сам, курсор по нему реально не проходил
        /*
         * Одноразовое потребление: JFace, похоже, ПЕРЕИСПОЛЬЗУЕТ тот же виджет Table между
         * разными наведениями (IInformationControlExtension2.setInput() на существующий
         * контрол — обычная оптимизация) — без сброса метки следующее наведение (когда курсор
         * после клика продолжает двигаться дальше по diff'у) со своим собственным FocusOut
         * повторно активировало бы уже ДРУГУЮ, непреднамеренно выделенную ссылку на том же
         * переиспользованном Table (подтверждено логом: открывался случайный
         * CommonModuleEditorInput ~100 мс после нужного сравнения).
         */
        table.setData(HOVERED_MARKER, null);
        boolean nativeClickHandled = Boolean.TRUE.equals(table.getData(NATIVE_CLICK_MARKER));
        table.setData(NATIVE_CLICK_MARKER, null);
        IHyperlink link = hoveredHyperlink(table);
        if (link == null)
            return;
        /*
         * Переход на строку у "Открыть двухпанельное сравнение" — НАША добавка (в апстриме
         * CompareLink.open() её не делает вовсе, см. resolveCompareLineReveal) — нужна ВСЕГДА,
         * независимо от того, кто в итоге открывает сравнение: сами мы или штатный клик
         * (см. ниже). CompareEditorCurrentLinesHook.attach() подхватит pendingLineReveal у
         * СЛЕДУЮЩЕГО открытого им сравнения, кем бы оно ни было открыто.
         */
        int[] pendingReveal = resolveCompareLineReveal(link, table);
        if (pendingReveal != null)
            CompareEditorCurrentLinesHook.setPendingLineReveal(pendingReveal[0], pendingReveal[1] != 0);
        if (nativeClickHandled)
        {
            // Клик долетел штатно (mouseUp сам вызвал openSelectedLink) — открывать САМИМ нельзя,
            // будет второе открытие: повторный CompareEditor.doSetInput отменяет job первого
            // (Job.getJobManager().cancel(this)) — редактор закрывается сам себя (state=3 CANCELED,
            // подтверждено логом). Реветл уже выставлен выше — этого достаточно.
            return;
        }
        /*
         * Штатный LinkListInformationControl.openSelectedLink() (декомпиляция —
         * .tmp/bundles/jface-text-hyperlink/) делает СТРОГО в таком порядке:
         *   fManager.hideInformationControl();  // сначала полностью убрать попапы
         *   fManager.setCaret();                // вернуть каретку/фокус в редактор
         *   link.open();                        // и только потом открывать
         * Одного asyncExec для этого мало: каскад закрытия вложенных попапов к тому моменту
         * ещё идёт, сравнение открывается посреди
         * перетасовки фокуса, и фоновый job подготовки входа отменяется — CompareEditor
         * закрывает сам себя (подтверждено: state=3 CANCELED, compareResult=null,
         * стек CompareEditor.createCompareControl -> closeEditor). Клик по такой же
         * гиперссылке прямо в тексте работает именно потому, что там попапа нет.
         * Поэтому ждём фактического исчезновения попап-шелла, и только затем открываем.
         */
        Display display = table.getDisplay();
        Shell popupShell = table.getShell();
        scheduleOpenAfterPopupsGone(display, popupShell, link, 0);
    }

    private static final int OPEN_MAX_ATTEMPTS = 40;
    private static final int OPEN_RETRY_DELAY_MS = 25;

    private static void scheduleOpenAfterPopupsGone(Display display, Shell popupShell, IHyperlink link,
        int attempt)
    {
        if (display.isDisposed())
            return;
        display.timerExec(OPEN_RETRY_DELAY_MS, () ->
        {
            if (display.isDisposed())
                return;
            boolean popupStillAlive = popupShell != null && !popupShell.isDisposed() && popupShell.isVisible();
            if (popupStillAlive && attempt < OPEN_MAX_ATTEMPTS)
            {
                scheduleOpenAfterPopupsGone(display, popupShell, link, attempt + 1);
                return;
            }
            try
            {
                link.open();
            }
            catch (Exception e)
            {
                Global.log(TAG, "[!] Ошибка открытия ссылки из всплывающего списка: " + e); //$NON-NLS-1$
            }
        });
    }

    /**
     * «Открыть двухпанельное сравнение» ({@code DiffViewer$CompareLink}) не переходит к строке
     * никак (в апстриме этого нет вовсе) — по просьбе пользователя строка ОБЯЗАТЕЛЬНО должна
     * активироваться и там же. У {@code CompareLink} есть только {@code lineNo} (унаследован от
     * {@code RevealLink}), без стороны (старая/новая версия) — сторону берём у соседней штатной
     * ссылки {@code OpenLink} в том же попапе с ТЕМ ЖЕ {@code lineNo} (декомпиляция
     * {@code DiffViewer$HyperlinkDetector.createHunkLinks} подтверждает: обе ссылки для одной
     * строки хунка конструируются рядом с одним и тем же вычисленным номером строки —
     * {@code .tmp/bundles/egit-commit/DiffViewer$HyperlinkDetector.javap-c.txt}).
     *
     * @return {@code null}, если это не {@code CompareLink} или сторону определить не удалось;
     *         иначе {@code [lineNo, oldSide ? 1 : 0]}
     */
    private static int[] resolveCompareLineReveal(IHyperlink link, Table table)
    {
        if (!link.getClass().getName().endsWith("DiffViewer$CompareLink")) //$NON-NLS-1$
            return null;
        if (!(Global.getField(link, "lineNo") instanceof Integer lineNo) || lineNo < 0) //$NON-NLS-1$
            return null;
        for (TableItem item : table.getItems())
        {
            Object sibling = item.getData();
            if (sibling == link || !(sibling instanceof IHyperlink))
                continue;
            if (!(Global.getField(sibling, "side") instanceof Side side)) //$NON-NLS-1$
                continue; // не OpenLink (у CompareLink стороны нет)
            if (!lineNo.equals(Global.getField(sibling, "lineNo"))) //$NON-NLS-1$
                continue;
            return new int[] { lineNo, side == Side.OLD ? 1 : 0 };
        }
        return null;
    }

    /**
     * "Первый элемент несёт {@link IHyperlink}" сам по себе слишком широкий маркер — под него
     * попадает и любая обычная таблица где-то в IDE, где строки для чего-то своего помечены
     * {@code IHyperlink} (например, бэйджи HEAD/master в панели «История» — тогда наш глобальный
     * {@code FocusOut}-фильтр открывал случайный файл при обычном скролле/клике по ЧУЖОЙ
     * таблице — "рулетка"). {@code LinkListInformationControl} — голая всплывающая таблица без
     * заголовка и без явных колонок (см. декомпиляцию {@code deferredCreateContent}:
     * {@code new Table(parent, SWT.SINGLE | SWT.FULL_SELECTION)}, {@code setHeaderVisible(false)}) —
     * обычные табличные панели EDT/EGit всегда с заголовком и колонками, этого достаточно, чтобы
     * их исключить.
     */
    private static boolean isLinkListTable(Table table)
    {
        return !table.getHeaderVisible() && table.getColumnCount() == 0
            && table.getItemCount() > 0 && table.getItem(0).getData() instanceof IHyperlink;
    }

    private static IHyperlink hoveredHyperlink(Table table)
    {
        if (!isLinkListTable(table))
            return null; // не наш попап — обычная таблица где-то в IDE
        int index = table.getSelectionIndex();
        TableItem[] items = table.getItems();
        if (index < 0 || index >= items.length)
            index = 0;
        return items[index].getData() instanceof IHyperlink hyperlink ? hyperlink : null;
    }
}
