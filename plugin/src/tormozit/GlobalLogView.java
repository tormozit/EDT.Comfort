package tormozit;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.part.ViewPart;

/**
 * Общий отладочный журнал плагина (content assist, установщик и др.).
 */
public final class GlobalLogView extends ViewPart
{
    private StyledText logText;
    private final java.util.function.Consumer<String> logListener = this::onLogLine;
    private String findText = ""; //$NON-NLS-1$
    private int findPos = -1;
    private int findGeneration;

    @Override
    public void createPartControl(Composite parent)
    {
        logText = new StyledText(parent,
            SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        logText.setEditable(false);
        logText.setText(ComfortSettings.isDebugLogEnabled()
            ? GlobalLog.getFullText()
            : ""); //$NON-NLS-1$
        if (logText.getLineCount() > 0 && ComfortSettings.isLogAutoscroll())
            scrollToBottom();

        GlobalLog.addListener(logListener);
        attachFindKeyListener();
        installContextMenu();

        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        Action autoScrollAction = new Action("Автопрокрутка", Action.AS_CHECK_BOX) { //$NON-NLS-1$
            @Override
            public void run()
            {
                ComfortSettings.setLogAutoscroll(isChecked());
                if (isChecked())
                    scrollToBottom();
            }
        };
        autoScrollAction.setToolTipText(
            "Автоматически прокручивать журнал к последней строке при появлении новых записей" //$NON-NLS-1$
                + Global.pluginSignForTooltip());
        autoScrollAction.setChecked(ComfortSettings.isLogAutoscroll());
        toolbar.add(autoScrollAction);
        toolbar.add(new Action("Найти") { //$NON-NLS-1$
            @Override
            public void run()
            {
                promptFind();
            }
        });
        toolbar.add(new Action("Очистить") { //$NON-NLS-1$
            @Override
            public void run()
            {
                GlobalLog.clear();
                if (logText != null && !logText.isDisposed())
                {
                    logText.setText(""); //$NON-NLS-1$
                }
            }
        });
        toolbar.update(true);
    }

    @Override
    public void setFocus()
    {
        if (logText != null && !logText.isDisposed())
            logText.setFocus();
    }

    @Override
    public void dispose()
    {
        GlobalLog.removeListener(logListener);
        super.dispose();
    }

    private void attachFindKeyListener()
    {
        Listener keyListener = this::onLogKeyDown;
        logText.addListener(SWT.KeyDown, keyListener);
        logText.addDisposeListener(e -> logText.removeListener(SWT.KeyDown, keyListener));
    }

    private void installContextMenu()
    {
        Menu menu = new Menu(logText);
        logText.setMenu(menu);
        MenuItem copyItem = new MenuItem(menu, SWT.PUSH);
        copyItem.setText("Копировать в буфер обмена\tCtrl+C"); //$NON-NLS-1$
        copyItem.addListener(SWT.Selection, e -> copySelectionToClipboard());
        menu.addListener(SWT.Show, e -> {
            Point sel = logText.getSelection();
            copyItem.setEnabled(sel.y > sel.x);
        });
    }

    private void onLogKeyDown(Event event)
    {
        if ((event.stateMask & SWT.CTRL) != 0 && (event.keyCode == 'c' || event.keyCode == 'C'))
        {
            event.doit = false;
            copySelectionToClipboard();
            return;
        }
        if ((event.stateMask & SWT.CTRL) != 0 && (event.keyCode == 'f' || event.keyCode == 'F'))
        {
            event.doit = false;
            promptFind();
            return;
        }
        if (event.keyCode == SWT.F3)
        {
            event.doit = false;
            findNext((event.stateMask & SWT.SHIFT) == 0);
        }
    }

    private void copySelectionToClipboard()
    {
        if (logText == null || logText.isDisposed())
            return;
        Point sel = logText.getSelection();
        if (sel.y <= sel.x)
            return;

        String text = logText.getSelectionText();
        if (text == null || text.isEmpty())
            text = logText.getText(sel.x, sel.y - sel.x);
        if (text == null || text.isEmpty())
            return;

        Clipboard cb = new Clipboard(logText.getDisplay());
        try
        {
            cb.setContents(
                new Object[] { text },
                new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            cb.dispose();
        }
    }

    private void promptFind()
    {
        if (logText == null || logText.isDisposed())
            return;
        InputDialog dialog = new InputDialog(logText.getShell(),
            "Поиск в журнале", //$NON-NLS-1$
            "Текст для поиска", //$NON-NLS-1$
            findText,
            null);
        if (dialog.open() != InputDialog.OK)
            return;
        String value = dialog.getValue();
        if (value == null || value.isBlank())
            return;
        findText = value.trim();
        findGeneration++;
        findPos = logText.getCaretOffset() - 1;
        findNext(true);
    }

    private void findNext(boolean forward)
    {
        if (logText == null || logText.isDisposed())
            return;
        if (findText == null || findText.isBlank())
        {
            promptFind();
            return;
        }

        String haystack = logText.getText();
        if (haystack.isEmpty())
            return;

        String needle = findText.toLowerCase();
        String hayLower = haystack.toLowerCase();
        int generation = findGeneration;
        int start = forward ? findPos + 1 : findPos - 1;
        int idx = forward
            ? hayLower.indexOf(needle, Math.max(0, start))
            : hayLower.lastIndexOf(needle, start < 0 ? hayLower.length() : start);

        if (idx < 0)
            idx = forward ? hayLower.indexOf(needle, 0) : hayLower.lastIndexOf(needle);

        if (idx < 0 || generation != findGeneration)
            return;

        findPos = idx;
        logText.setSelection(idx, idx + findText.length());
        logText.showSelection();
        highlightFindMatch(idx, findText.length());
    }

    private void highlightFindMatch(int start, int length)
    {
        Display display = logText.getDisplay();
        StyleRange range = new StyleRange();
        range.start = start;
        range.length = length;
        range.background = display.getSystemColor(SWT.COLOR_INFO_BACKGROUND);
        range.foreground = display.getSystemColor(SWT.COLOR_INFO_FOREGROUND);
        logText.setStyleRange(range);
    }

    private void onLogLine(String line)
    {
        if (logText == null || logText.isDisposed())
            return;
        if (line == null)
        {
            logText.setText(""); //$NON-NLS-1$
            return;
        }
        if (GlobalLog.RESYNC.equals(line))
        {
            resyncFromBuffer();
            return;
        }
        if (!ComfortSettings.isDebugLogEnabled())
            return;

        appendLogLine(line);
    }

    private void appendLogLine(String line)
    {
        int oldCharCount = logText.getCharCount();
        int topIndex = logText.getTopIndex();

        String suffix = oldCharCount == 0 ? line : "\n" + line; //$NON-NLS-1$
        runWithoutStealingFocus(() -> {
            logText.replaceTextRange(oldCharCount, 0, suffix);
            applyHighlight(line, oldCharCount + (oldCharCount == 0 ? 0 : 1));
        });

        if (ComfortSettings.isLogAutoscroll())
            scrollToBottom();
        else if (logText.getLineCount() > 0)
            logText.setTopIndex(Math.min(topIndex, logText.getLineCount() - 1));
    }

    private void resyncFromBuffer()
    {
        if (!ComfortSettings.isDebugLogEnabled())
            return;

        String newText = GlobalLog.getFullText();
        String oldText = logText.getText();
        if (oldText.equals(newText))
            return;

        int topIndex = logText.getTopIndex();

        runWithoutStealingFocus(() -> {
            logText.setText(newText);
            reapplyAllHighlights(newText);
        });

        if (ComfortSettings.isLogAutoscroll())
            scrollToBottom();
        else if (logText.getLineCount() > 0)
            logText.setTopIndex(Math.min(topIndex, logText.getLineCount() - 1));
    }

    /** Обновление текста журнала без активации представления и потери фокуса редактора. */
    private void runWithoutStealingFocus(Runnable update)
    {
        Control focusControl = logText.getDisplay().getFocusControl();
        boolean logHadFocus = focusControl == logText;
        update.run();
        if (!logHadFocus && focusControl != null && !focusControl.isDisposed() && focusControl != logText)
            focusControl.setFocus();
    }

    private void reapplyAllHighlights(String text)
    {
        int lineStart = 0;
        for (int i = 0; i <= text.length(); i++)
        {
            if (i == text.length() || text.charAt(i) == '\n')
            {
                applyHighlight(text.substring(lineStart, i), lineStart);
                lineStart = i + 1;
            }
        }
    }

    private void scrollToBottom()
    {
        if (logText == null || logText.isDisposed() || logText.getLineCount() <= 0)
            return;
        logText.setTopIndex(logText.getLineCount() - 1);
    }

    private void applyHighlight(String line, int lineOffset)
    {
        Display display = logText.getDisplay();
        Color keyColor = ThemeAwareColors.effectiveSystemColor(display, SWT.COLOR_DARK_BLUE);
        Color errorColor = ThemeAwareColors.effectiveSystemColor(display, SWT.COLOR_DARK_RED);
        highlightToken(line, lineOffset, "filterTrace", keyColor); //$NON-NLS-1$
        highlightToken(line, lineOffset, "filter=\"", keyColor); //$NON-NLS-1$
        highlightToken(line, lineOffset, "docFilter=\"", keyColor); //$NON-NLS-1$
        highlightToken(line, lineOffset, "trackerFilter=\"", keyColor); //$NON-NLS-1$
        highlightToken(line, lineOffset, "MISMATCH", errorColor); //$NON-NLS-1$
        highlightToken(line, lineOffset, "ERROR", errorColor); //$NON-NLS-1$
        highlightToken(line, lineOffset, "[install]", keyColor); //$NON-NLS-1$
    }

    private void highlightToken(String line, int lineOffset, String token, Color color)
    {
        int idx = line.indexOf(token);
        if (idx < 0)
            return;
        StyleRange range = new StyleRange();
        range.start = lineOffset + idx;
        range.length = token.length();
        range.foreground = color;
        range.fontStyle = SWT.BOLD;
        logText.setStyleRange(range);
    }
}
