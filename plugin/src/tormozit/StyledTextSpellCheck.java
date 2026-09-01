package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.LineStyleEvent;
import org.eclipse.swt.custom.LineStyleListener;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * Орфография Comfort на произвольном {@link StyledText}: красные волны под ошибочными
 * сегментами, меню ПКМ с вариантами замены и «Добавить в словарь», Ctrl+1, Ctrl+C/X.
 *
 * <p>Общая часть двух мест, где проверка идёт по StyledText, а не по документу редактора:
 * панель «Свойства» ({@link PropertySheetSpellCheckHook} — StyledText там оверлей LWT) и окно
 * «Строки на разных языках» ({@link LocalStringDialogSpellCheckHook} — StyledText создаёт сам
 * плагин вместо штатного поля). Здесь только работа с виджетом; поиск нужного StyledText и его
 * жизненный цикл — на стороне потребителя.
 *
 * <p>Волны рисуются дважды: {@link LineStyleListener} с {@link SWT#UNDERLINE_ERROR} и запасной
 * {@link SWT#Paint} поверх. LWT в однострочном режиме {@code UNDERLINE_ERROR} из LineStyleListener
 * рисует не всегда, а запасной путь работает на любой платформе — обе отрисовки совпадают,
 * поэтому дублирование не видно.
 */
final class StyledTextSpellCheck
{
    private static final int SUGGEST_MAX = 12;
    private static final int SUGGEST_UI_THROTTLE_MS = 300;

    private static final Set<StyledText> WIRED =
        Collections.newSetFromMap(new WeakHashMap<>());

    private static boolean keyFilterInstalled;

    private StyledTextSpellCheck()
    {
    }

    static boolean isWired(StyledText styled)
    {
        return styled != null && WIRED.contains(styled);
    }

    /**
     * Подключить орфографию к виджету. Повторный вызов на уже подключённом только перерисовывает,
     * поэтому вызывать можно из любого места, где виджет мог обновиться.
     */
    static void install(StyledText styled)
    {
        if (styled == null || styled.isDisposed())
            return;
        if (!SpellCheckHook.isComfortPlatformSpellingActive())
            return;
        if (WIRED.contains(styled))
        {
            styled.redraw();
            return;
        }

        ensureKeyFilter(styled.getDisplay());
        styled.addLineStyleListener(StyledTextSpellCheck::provideLineStyles);
        styled.addListener(SWT.Paint, StyledTextSpellCheck::paintErrorUnderlines);
        styled.addModifyListener(e ->
        {
            if (!styled.isDisposed())
                styled.redraw();
        });
        installAssistHandlers(styled);
        styled.addDisposeListener(e -> WIRED.remove(styled));
        WIRED.add(styled);
        styled.redraw();
    }

    /** После изменения пользовательского словаря — перерисовать подчёркивания. */
    static void redrawAll()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Runnable redraw = () ->
        {
            for (StyledText styled : new ArrayList<>(WIRED))
            {
                if (styled != null && !styled.isDisposed())
                    styled.redraw();
            }
        };
        if (display.getThread() == Thread.currentThread())
            redraw.run();
        else
            display.asyncExec(redraw);
    }

    /**
     * Горячие клавиши на подключённом виджете через Display filter (Workbench/LWT иначе
     * перехватывают). Ctrl+1 — подсказки; Ctrl+C/X — копирование/вырезание выделенного текста.
     */
    private static void ensureKeyFilter(Display display)
    {
        if (keyFilterInstalled || display == null || display.isDisposed())
            return;
        keyFilterInstalled = true;
        display.addFilter(SWT.KeyDown, event ->
        {
            if (!SpellCheckHook.isComfortPlatformSpellingActive())
                return;
            if (!(event.widget instanceof StyledText styled) || styled.isDisposed())
                return;
            if (!WIRED.contains(styled))
                return;
            if ((event.stateMask & SWT.MOD1) == 0)
                return;
            if (event.keyCode == 'c' || event.keyCode == 'C')
            {
                if (copySelection(styled))
                {
                    event.doit = false;
                    event.type = SWT.None;
                }
                return;
            }
            if (event.keyCode == 'x' || event.keyCode == 'X')
            {
                if (cutSelection(styled))
                {
                    event.doit = false;
                    event.type = SWT.None;
                }
                return;
            }
            boolean ctrl1 = event.keyCode == '1' || event.keyCode == SWT.KEYPAD_1;
            if (!ctrl1)
                return;
            WordSpan span = wordSpanAt(styled, styled.getCaretOffset());
            if (span == null || !span.misspelled)
                return;
            event.doit = false;
            event.type = SWT.None;
            Point loc;
            try
            {
                loc = styled.toDisplay(styled.getLocationAtOffset(span.start));
            }
            catch (IllegalArgumentException ex)
            {
                loc = styled.toDisplay(0, styled.getLineHeight());
            }
            showAssistMenu(styled, loc.x, loc.y + styled.getLineHeight(), span, false);
        });
    }

    private static void installAssistHandlers(StyledText styled)
    {
        styled.addListener(SWT.MenuDetect, event ->
        {
            if (!SpellCheckHook.isComfortPlatformSpellingActive())
                return;
            if (!(event.widget instanceof StyledText st) || st.isDisposed())
                return;
            int offset = offsetFromDisplay(st, event.x, event.y);
            WordSpan span = wordSpanAt(st, offset);
            String selection = st.getSelectionText();
            boolean hasSelection = selection != null && !selection.isEmpty();
            if ((span == null || !span.misspelled) && !hasSelection)
                return;
            event.doit = false;
            showAssistMenu(st, event.x, event.y, span, hasSelection);
        });
    }

    private static void provideLineStyles(LineStyleEvent event)
    {
        if (event == null || event.lineText == null)
            return;
        if (!SpellCheckHook.isComfortPlatformSpellingActive())
        {
            event.styles = new StyleRange[0];
            return;
        }
        List<int[]> bad = ComfortSpellingEngine.findMisspelledRanges(event.lineText);
        if (bad.isEmpty())
        {
            event.styles = new StyleRange[0];
            return;
        }
        Color underline = event.widget instanceof StyledText st
            ? spellingUnderlineColor(st.getDisplay())
            : Display.getDefault().getSystemColor(SWT.COLOR_RED);
        StyleRange[] ranges = new StyleRange[bad.size()];
        for (int i = 0; i < bad.size(); i++)
        {
            int[] r = bad.get(i);
            StyleRange sr = new StyleRange();
            sr.start = event.lineOffset + r[0];
            sr.length = r[1];
            sr.underline = true;
            sr.underlineStyle = SWT.UNDERLINE_ERROR;
            sr.underlineColor = underline;
            ranges[i] = sr;
        }
        event.styles = ranges;
    }

    /** Красная волна под ошибочными сегментами (поверх текста). */
    private static void paintErrorUnderlines(Event event)
    {
        if (!(event.widget instanceof StyledText styled) || styled.isDisposed())
            return;
        if (!SpellCheckHook.isComfortPlatformSpellingActive())
            return;
        String text = styled.getText();
        if (text == null || text.isEmpty())
            return;
        List<int[]> bad = ComfortSpellingEngine.findMisspelledRanges(text);
        if (bad.isEmpty())
            return;
        GC gc = event.gc;
        if (gc == null)
            return;
        Color red = spellingUnderlineColor(styled.getDisplay());
        Color oldFg = gc.getForeground();
        gc.setForeground(red);
        int lineHeight = styled.getLineHeight();
        for (int[] r : bad)
        {
            int from = r[0];
            int to = r[0] + r[1];
            try
            {
                Point p0 = styled.getLocationAtOffset(from);
                Point p1 = styled.getLocationAtOffset(to);
                int y = p0.y + lineHeight - 2;
                drawWavyLine(gc, p0.x, p1.x, y);
            }
            catch (IllegalArgumentException ignored)
            {
                // offset вне видимой области
            }
        }
        gc.setForeground(oldFg);
    }

    private static void drawWavyLine(GC gc, int x1, int x2, int y)
    {
        if (x2 <= x1)
            return;
        int amp = 1;
        int step = 2;
        int prevX = x1;
        int prevY = y;
        boolean up = true;
        for (int x = x1 + step; x <= x2; x += step)
        {
            int nextY = up ? y - amp : y + amp;
            gc.drawLine(prevX, prevY, x, nextY);
            prevX = x;
            prevY = nextY;
            up = !up;
        }
        if (prevX < x2)
            gc.drawLine(prevX, prevY, x2, y);
    }

    private static Color spellingUnderlineColor(Display display)
    {
        return display.getSystemColor(SWT.COLOR_RED);
    }

    static boolean copySelection(StyledText styled)
    {
        if (styled == null || styled.isDisposed())
            return false;
        String sel = styled.getSelectionText();
        if (sel == null || sel.isEmpty())
            return false;
        Clipboard cb = new Clipboard(styled.getDisplay());
        try
        {
            cb.setContents(new Object[] { sel }, new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            cb.dispose();
        }
        return true;
    }

    static boolean cutSelection(StyledText styled)
    {
        if (styled == null || styled.isDisposed() || !styled.getEditable())
            return false;
        Point range = styled.getSelection();
        if (range == null || range.x == range.y)
            return false;
        if (!copySelection(styled))
            return false;
        styled.replaceTextRange(range.x, range.y - range.x, ""); //$NON-NLS-1$
        return true;
    }

    private static int offsetFromDisplay(StyledText styled, int displayX, int displayY)
    {
        try
        {
            Point p = styled.toControl(displayX, displayY);
            return styled.getOffsetAtPoint(p);
        }
        catch (IllegalArgumentException ex)
        {
            return styled.getCaretOffset();
        }
    }

    /** Сегмент слова под смещением: границы по {@link ComfortSpellingEngine#splitIdentifierSegments}. */
    static WordSpan wordSpanAt(StyledText styled, int offset)
    {
        if (styled == null || styled.isDisposed())
            return null;
        String text = styled.getText();
        if (text == null || text.isEmpty())
            return null;
        int caret = Math.max(0, Math.min(offset, text.length()));
        if (caret > 0 && caret == text.length() && !isWordChar(text.charAt(caret - 1)))
            caret--;
        if (caret < text.length() && !isWordChar(text.charAt(caret)) && caret > 0
            && isWordChar(text.charAt(caret - 1)))
            caret--;
        if (caret >= text.length() || !isWordChar(text.charAt(caret)))
            return null;
        int runStart = caret;
        while (runStart > 0 && isWordChar(text.charAt(runStart - 1)))
            runStart--;
        int runEnd = caret;
        while (runEnd < text.length() && isWordChar(text.charAt(runEnd)))
            runEnd++;
        if (runStart >= runEnd)
            return null;
        List<int[]> segments = ComfortSpellingEngine.splitIdentifierSegments(text, runStart, runEnd);
        int[] chosen = segments.isEmpty() ? new int[] { runStart, runEnd } : segments.get(0);
        for (int[] seg : segments)
        {
            if (caret >= seg[0] && caret < seg[1])
            {
                chosen = seg;
                break;
            }
        }
        if (caret == runEnd && !segments.isEmpty())
            chosen = segments.get(segments.size() - 1);
        int segStart = chosen[0];
        int segEnd = chosen[1];
        String word = text.substring(segStart, segEnd);
        if (!hasLetter(word))
            return null;
        boolean misspelled = word.length() >= 2
            && ComfortSpellingEngine.isMisspelledAt(text, segStart, segEnd - segStart);
        return new WordSpan(segStart, segEnd - segStart, word, misspelled);
    }

    private static void showAssistMenu(StyledText styled, int displayX, int displayY,
        WordSpan span, boolean offerCopy)
    {
        if (styled == null || styled.isDisposed())
            return;
        Menu menu = new Menu(styled);
        // «Добавить в словарь» всегда первый пункт меню.
        if (span != null && span.misspelled)
        {
            MenuItem addDict = new MenuItem(menu, SWT.PUSH);
            addDict.setText("Добавить в словарь: " + span.word); //$NON-NLS-1$
            addDict.addListener(SWT.Selection, e -> styled.getDisplay().asyncExec(() ->
                ComfortSpellingEngine.addUserWordFromUi(span.word)));
            fillSuggestionsAsync(menu, styled, span);
            if (offerCopy)
                new MenuItem(menu, SWT.SEPARATOR);
        }
        if (offerCopy)
        {
            MenuItem copyItem = new MenuItem(menu, SWT.PUSH);
            copyItem.setText("Копировать\tCtrl+C"); //$NON-NLS-1$
            copyItem.addListener(SWT.Selection,
                e -> styled.getDisplay().asyncExec(() -> copySelection(styled)));
            if (styled.getEditable())
            {
                MenuItem cutItem = new MenuItem(menu, SWT.PUSH);
                cutItem.setText("Вырезать\tCtrl+X"); //$NON-NLS-1$
                cutItem.addListener(SWT.Selection,
                    e -> styled.getDisplay().asyncExec(() -> cutSelection(styled)));
            }
        }
        if (menu.getItemCount() == 0)
        {
            menu.dispose();
            return;
        }
        menu.addListener(SWT.Hide, e -> styled.getDisplay().asyncExec(() ->
        {
            if (!menu.isDisposed())
                menu.dispose();
        }));
        menu.setLocation(displayX, displayY);
        menu.setVisible(true);
    }

    /**
     * Варианты в меню: из кэша сразу; иначе «…» и фоновый
     * {@link ComfortSpellingEngine#suggestStreaming} с дописыванием перед «…»
     * (не чаще раза в {@link #SUGGEST_UI_THROTTLE_MS} мс). «Добавить в словарь» остаётся первым.
     */
    private static void fillSuggestionsAsync(Menu menu, StyledText styled, WordSpan span)
    {
        List<String> cached = ComfortSpellingEngine.peekSuggestCache(span.word, SUGGEST_MAX);
        if (cached != null)
        {
            if (cached.isEmpty())
            {
                MenuItem empty = new MenuItem(menu, SWT.PUSH);
                empty.setText("Нет вариантов исправления"); //$NON-NLS-1$
                empty.setEnabled(false);
            }
            else
            {
                int at = 1; // сразу после «Добавить в словарь»
                for (String suggestion : cached)
                {
                    addSuggestionMenuItem(menu, styled, span, suggestion, at);
                    at++;
                }
            }
            return;
        }

        MenuItem loading = new MenuItem(menu, SWT.PUSH);
        loading.setText("..."); //$NON-NLS-1$
        loading.setEnabled(false);

        final String word = span.word;
        final java.util.ArrayList<String> pending = new java.util.ArrayList<>();
        final Object pendingLock = new Object();
        final boolean[] flushScheduled = { false };
        final long[] lastFlushMs = { 0L };

        Runnable flushPending = () ->
        {
            if (menu.isDisposed() || loading.isDisposed())
                return;
            java.util.ArrayList<String> batch;
            synchronized (pendingLock)
            {
                flushScheduled[0] = false;
                lastFlushMs[0] = System.currentTimeMillis();
                if (pending.isEmpty())
                    return;
                batch = new java.util.ArrayList<>(pending);
                pending.clear();
            }
            int idx = menu.indexOf(loading);
            for (String suggestion : batch)
            {
                if (menu.isDisposed() || loading.isDisposed())
                    return;
                idx = menu.indexOf(loading);
                addSuggestionMenuItem(menu, styled, span, suggestion, idx >= 0 ? idx : 1);
            }
        };

        Job job = new Job("Комфорт: варианты орфографии") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                ComfortSpellingEngine.suggestStreaming(word, SUGGEST_MAX, suggestion ->
                {
                    if (monitor.isCanceled())
                        return;
                    Display display = styled.getDisplay();
                    if (display == null || display.isDisposed())
                        return;
                    synchronized (pendingLock)
                    {
                        pending.add(suggestion);
                        long now = System.currentTimeMillis();
                        long wait = lastFlushMs[0] + SUGGEST_UI_THROTTLE_MS - now;
                        if (wait <= 0)
                        {
                            flushScheduled[0] = false;
                            display.asyncExec(flushPending);
                        }
                        else if (!flushScheduled[0])
                        {
                            flushScheduled[0] = true;
                            int delay = (int) wait;
                            // timerExec только из UI-потока
                            display.asyncExec(() ->
                            {
                                if (!display.isDisposed())
                                    display.timerExec(delay, flushPending);
                            });
                        }
                    }
                }, monitor);
                Display display = styled.getDisplay();
                if (display == null || display.isDisposed())
                    return Status.OK_STATUS;
                display.asyncExec(() ->
                {
                    flushPending.run();
                    if (menu.isDisposed())
                        return;
                    if (!loading.isDisposed())
                        loading.dispose();
                    if (!menuHasSpellSuggestion(menu))
                    {
                        // сразу после «Добавить в словарь» (индекс 1)
                        MenuItem empty = new MenuItem(menu, SWT.PUSH, Math.min(1, menu.getItemCount()));
                        empty.setText("Нет вариантов исправления"); //$NON-NLS-1$
                        empty.setEnabled(false);
                    }
                });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.setPriority(Job.DECORATE);
        job.schedule();
        menu.addListener(SWT.Dispose, e -> job.cancel());
    }

    private static boolean menuHasSpellSuggestion(Menu menu)
    {
        for (MenuItem item : menu.getItems())
        {
            if (item.isDisposed())
                continue;
            String t = item.getText();
            if (t == null || t.isEmpty())
                continue;
            if (t.startsWith("Добавить в словарь") //$NON-NLS-1$
                || t.startsWith("Копировать") || t.startsWith("Вырезать") //$NON-NLS-1$ //$NON-NLS-2$
                || "...".equals(t) //$NON-NLS-1$
                || "Нет вариантов исправления".equals(t)) //$NON-NLS-1$
                continue;
            return true;
        }
        return false;
    }

    private static void addSuggestionMenuItem(Menu menu, StyledText styled, WordSpan span,
        String suggestion, int index)
    {
        // Не вставлять перед «Добавить в словарь» (индекс 0).
        int safeIndex = index <= 0 ? 1 : index;
        safeIndex = Math.min(safeIndex, menu.getItemCount());
        MenuItem item = new MenuItem(menu, SWT.PUSH, safeIndex);
        item.setText(suggestion);
        item.addListener(SWT.Selection, e ->
        {
            String replacement = suggestion;
            styled.getDisplay().asyncExec(() -> applySuggestion(styled, span, replacement));
        });
    }

    private static void applySuggestion(StyledText styled, WordSpan span, String replacement)
    {
        if (styled == null || styled.isDisposed() || span == null || replacement == null)
            return;
        String text = styled.getText();
        if (text == null || span.start < 0 || span.start + span.length > text.length())
            return;
        styled.replaceTextRange(span.start, span.length, replacement);
        styled.setCaretOffset(span.start + replacement.length());
        styled.redraw();
    }

    private static boolean isWordChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '-' || c == '\'';
    }

    private static boolean hasLetter(String word)
    {
        for (int i = 0; i < word.length(); i++)
        {
            if (Character.isLetter(word.charAt(i)))
                return true;
        }
        return false;
    }

    /** Сегмент слова в тексте виджета и признак ошибки в нём. */
    static final class WordSpan
    {
        final int start;
        final int length;
        final String word;
        final boolean misspelled;

        WordSpan(int start, int length, String word, boolean misspelled)
        {
            this.start = start;
            this.length = length;
            this.word = word;
            this.misspelled = misspelled;
        }
    }
}
