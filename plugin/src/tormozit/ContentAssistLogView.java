package tormozit;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.part.ViewPart;

/**
 * Общий отладочный журнал плагина (content assist, установщик и др.).
 */
public final class ContentAssistLogView extends ViewPart
{
    private StyledText logText;
    private final java.util.function.Consumer<String> logListener = this::onLogLine;

    @Override
    public void createPartControl(Composite parent)
    {
        logText = new StyledText(parent,
            SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        logText.setEditable(false);
        logText.setText(ComfortSettings.isDebugLogEnabled()
            ? ContentAssistLog.getFullText()
            : ""); //$NON-NLS-1$
        if (logText.getLineCount() > 0)
            logText.setTopIndex(logText.getLineCount() - 1);

        ContentAssistLog.addListener(logListener);

        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        toolbar.add(new Action("Очистить") { //$NON-NLS-1$
            @Override
            public void run()
            {
                ContentAssistLog.clear();
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
        ContentAssistLog.removeListener(logListener);
        super.dispose();
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
        if (!ComfortSettings.isDebugLogEnabled())
            return;

        String existing = logText.getText();
        String next = existing.isEmpty() ? line : existing + "\n" + line; //$NON-NLS-1$
        logText.setText(next);
        applyHighlight(line, existing.length() + (existing.isEmpty() ? 0 : 1));
        logText.setTopIndex(logText.getLineCount() - 1);
    }

    private void applyHighlight(String line, int lineOffset)
    {
        Display display = logText.getDisplay();
        Color keyColor = display.getSystemColor(SWT.COLOR_DARK_BLUE);
        highlightToken(line, lineOffset, "filterTrace", keyColor); //$NON-NLS-1$
        highlightToken(line, lineOffset, "filter=\"", keyColor); //$NON-NLS-1$
        highlightToken(line, lineOffset, "docFilter=\"", keyColor); //$NON-NLS-1$
        highlightToken(line, lineOffset, "trackerFilter=\"", keyColor); //$NON-NLS-1$
        highlightToken(line, lineOffset, "MISMATCH", //$NON-NLS-1$
            display.getSystemColor(SWT.COLOR_DARK_RED));
        highlightToken(line, lineOffset, "ERROR", //$NON-NLS-1$
            display.getSystemColor(SWT.COLOR_DARK_RED));
        highlightToken(line, lineOffset, "[install]", //$NON-NLS-1$
            display.getSystemColor(SWT.COLOR_DARK_BLUE));
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
