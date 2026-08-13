package tormozit;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.IDialogSettingsProvider;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;
import org.osgi.framework.Bundle;

public final class TextEditorFastSearchHandler extends AbstractHandler
{
    private static final String TAG = "FastSearch"; //$NON-NLS-1$

    static final String CMD_FORWARD =
        "tormozit.TextEditorFastSearchForward"; //$NON-NLS-1$
    static final String CMD_BACK =
        "tormozit.TextEditorFastSearchBack"; //$NON-NLS-1$

    private static final String FIND_REPLACE_BUNDLE = "org.eclipse.ui.workbench.texteditor"; //$NON-NLS-1$
    private static final String FIND_REPLACE_SECTION = "org.eclipse.ui.texteditor.FindReplaceDialog"; //$NON-NLS-1$
    private static final String KEY_CASE_SENSITIVE = "casesensitive"; //$NON-NLS-1$
    private static final String KEY_WHOLE_WORD = "wholeword"; //$NON-NLS-1$
    private static final String KEY_REGEX = "isRegEx"; //$NON-NLS-1$
    private static final String KEY_WRAP = "wrap"; //$NON-NLS-1$
    private static final String KEY_SELECTION = "selection"; //$NON-NLS-1$
    private static final String KEY_FIND_HISTORY = "findhistory"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        String commandId = event.getCommand().getId();
        boolean forward = CMD_FORWARD.equals(commandId);

        IEditorPart editorPart = HandlerUtil.getActiveEditor(event);
        ITextEditor textEditor = editorPart != null
            ? TextEditor.resolveTextEditor(editorPart) : null;

        ISourceViewer viewer = null;
        StyledText textWidget = null;
        if (textEditor != null)
        {
            viewer = TextEditor.getSourceViewer(textEditor);
            if (viewer != null && viewer.getTextWidget() instanceof StyledText st)
                textWidget = st;
            else
                viewer = null;
        }
        if (textWidget == null)
        {
            textWidget = resolveStyledTextFromFocus();
            if (textWidget != null)
                viewer = TextEditor.resolveViewerFromFocus(textWidget);
        }
        if (textWidget == null || textWidget.isDisposed())
            return null;

        return executeSearch(viewer, textWidget, forward);
    }

    private static StyledText resolveStyledTextFromFocus()
    {
        Display display = Display.getCurrent();
        if (display == null) return null;
        Control focus = display.getFocusControl();
        if (focus instanceof StyledText st && !st.isDisposed())
            return st;
        return null;
    }

    /** Общий вход для Display-фильтра (модальный «Редактор запроса») — сам ищет вьювер по фокусу. */
    public static Object executeSearch(StyledText textWidget, boolean forward)
    {
        if (textWidget == null || textWidget.isDisposed())
            return null;
        return executeSearch(TextEditor.resolveViewerFromFocus(textWidget), textWidget, forward);
    }

    /**
     * Общий вход для {@link #execute}. При наличии {@code viewer} ищет по полному тексту документа
     * (модельные офсеты) — иначе, как виджет {@link StyledText#getText()}, видны только развёрнутые
     * (не свёрнутые) строки, т.к. под капотом лежит проекционный документ.
     */
    public static Object executeSearch(ITextViewer viewer, StyledText textWidget, boolean forward)
    {
        if (textWidget == null || textWidget.isDisposed())
            return null;

        Point widgetSelRange = textWidget.getSelectionRange();
        String selectionText = textWidget.getSelectionText();

        String searchString = getSearchString(widgetSelRange.x, selectionText, textWidget);
        if (searchString == null || searchString.isEmpty())
        {
            if (Global.isLogEnabled())
                Global.log(TAG, "executeSearch: searchString=null offset=" + widgetSelRange.x);
            return null;
        }
        return executeSearchWithString(viewer, textWidget, searchString, forward, false, true);
    }

    /**
     * Следующее/предыдущее вхождение строки из буфера диалога «Найти/Заменить»
     * (не идентификатор под кареткой — это {@link #executeSearch}).
     */
    public static Object executeFindNextFromBuffer(StyledText textWidget, boolean forward)
    {
        if (textWidget == null || textWidget.isDisposed())
            return null;
        String searchString = readFindBufferNeedle();
        if (searchString == null || searchString.isEmpty())
        {
            if (Global.isLogEnabled())
                Global.log(TAG, "executeFindNextFromBuffer: empty find buffer"); //$NON-NLS-1$
            return null;
        }
        return executeSearchWithString(
            TextEditor.resolveViewerFromFocus(textWidget), textWidget, searchString, forward,
            true, isWrapSearch());
    }

    private static Object executeSearchWithString(ITextViewer viewer, StyledText textWidget,
        String searchString, boolean forward, boolean fromFindBuffer, boolean wrap)
    {
        if (textWidget == null || textWidget.isDisposed()
            || searchString == null || searchString.isEmpty())
            return null;

        Point widgetSelRange = textWidget.getSelectionRange();
        String selectionText = textWidget.getSelectionText();

        IDocument document = viewer != null ? viewer.getDocument() : null;
        String fullText;
        int offset;
        if (document != null)
        {
            fullText = document.get();
            Point modelSelRange = viewer.getSelectedRange();
            offset = selectionText != null && !selectionText.isEmpty()
                ? (forward ? modelSelRange.x + modelSelRange.y : modelSelRange.x)
                : modelSelRange.x;
        }
        else
        {
            fullText = textWidget.getText();
            offset = selectionText != null && !selectionText.isEmpty()
                ? (forward ? widgetSelRange.x + widgetSelRange.y : widgetSelRange.x)
                : widgetSelRange.x;
        }

        boolean caseSensitive = isCaseSensitiveSearch();
        boolean wholeWord = isWholeWordSearch();
        boolean regex = fromFindBuffer && isRegExSearch();
        int searchFrom = forward ? (fromFindBuffer ? offset : offset + 1) : offset - 1;

        if (Global.isLogEnabled())
            Global.log(TAG, "executeSearchWithString: search='" + searchString //$NON-NLS-1$
                + "' from=" + searchFrom + " forward=" + forward //$NON-NLS-1$ //$NON-NLS-2$
                + " caseSensitive=" + caseSensitive + " wholeWord=" + wholeWord //$NON-NLS-1$ //$NON-NLS-2$
                + " regex=" + regex + " wrap=" + wrap //$NON-NLS-1$ //$NON-NLS-2$
                + " viaDocument=" + (document != null)); //$NON-NLS-1$

        int found;
        int matchLength = searchString.length();
        if (regex)
        {
            int[] match = findRegEx(fullText, searchString, searchFrom, forward, caseSensitive, wrap);
            found = match[0];
            matchLength = match[1];
        }
        else
        {
            found = forward
                ? indexOf(fullText, searchString, searchFrom, caseSensitive, wholeWord)
                : lastIndexOf(fullText, searchString, searchFrom, caseSensitive, wholeWord);
            if (found < 0 && wrap)
                found = forward
                    ? indexOf(fullText, searchString, 0, caseSensitive, wholeWord)
                    : lastIndexOf(fullText, searchString, fullText.length() - 1, caseSensitive, wholeWord);
        }

        if (found >= 0)
            selectFound(viewer, textWidget, found, matchLength);

        if (Global.isLogEnabled())
            Global.log(TAG, "executeSearchWithString: result=" + found); //$NON-NLS-1$
        return null;
    }

    /** Выделяет найденный фрагмент; для сворачиваемого вьювера предварительно разворачивает область. */
    private static void selectFound(ITextViewer viewer, StyledText textWidget, int offset, int length)
    {
        if (viewer != null && viewer.getDocument() != null)
        {
            if (viewer instanceof ITextViewerExtension5 ext5)
                ext5.exposeModelRange(new Region(offset, length));
            viewer.setSelectedRange(offset, length);
            viewer.revealRange(offset, length);
            return;
        }
        textWidget.setSelectionRange(offset, length);
        textWidget.showSelection();
    }

    private static boolean isCaseSensitiveSearch()
    {
        return readFindReplaceFlag(KEY_CASE_SENSITIVE);
    }

    private static boolean isWholeWordSearch()
    {
        return readFindReplaceFlag(KEY_WHOLE_WORD);
    }

    private static boolean isRegExSearch()
    {
        return readFindReplaceFlag(KEY_REGEX);
    }

    /** Как {@code FindNextAction}: wrap по умолчанию включён, пока в настройках явно не false. */
    private static boolean isWrapSearch()
    {
        IDialogSettings section = findReplaceSection();
        if (section == null || section.get(KEY_WRAP) == null)
            return true;
        return section.getBoolean(KEY_WRAP);
    }

    private static String readFindBufferNeedle()
    {
        IDialogSettings section = findReplaceSection();
        if (section == null)
            return null;
        String selection = section.get(KEY_SELECTION);
        if (selection != null && !selection.isEmpty())
            return selection;
        String[] history = section.getArray(KEY_FIND_HISTORY);
        if (history != null)
        {
            for (String entry : history)
            {
                if (entry != null && !entry.isEmpty())
                    return entry;
            }
        }
        return null;
    }

    private static boolean readFindReplaceFlag(String key)
    {
        IDialogSettings section = findReplaceSection();
        return section != null && section.getBoolean(key);
    }

    private static IDialogSettings findReplaceSection()
    {
        try
        {
            Bundle bundle = Platform.getBundle(FIND_REPLACE_BUNDLE);
            if (bundle == null)
                return null;
            IDialogSettingsProvider provider = PlatformUI.getDialogSettingsProvider(bundle);
            if (provider == null)
                return null;
            IDialogSettings settings = provider.getDialogSettings();
            if (settings == null)
                return null;
            return settings.getSection(FIND_REPLACE_SECTION);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static String getSearchString(int offset, String selectionText, StyledText textWidget)
    {
        if (selectionText != null && !selectionText.isEmpty())
            return selectionText;
        if (textWidget == null || textWidget.isDisposed())
            return null;

        String fullText = textWidget.getText();
        if (fullText.isEmpty())
            return null;

        int lineIndex = textWidget.getLineAtOffset(offset);
        int lineOffset = textWidget.getOffsetAtLine(lineIndex);
        String lineText = textWidget.getLine(lineIndex);
        if (lineText.isEmpty())
            return null;

        int posInLine = offset - lineOffset;
        if (posInLine < 0 || posInLine >= lineText.length())
            return null;

        char[] chars = lineText.toCharArray();
        char c = lineText.charAt(posInLine);

        if (IdentifierSelectionSupport.isIdentifierChar(c))
            return getWholeWord(chars, posInLine, IdentifierSelectionSupport::isIdentifierChar);
        if (Character.isWhitespace(c))
            return getWholeWord(chars, posInLine, Character::isWhitespace);
        return getWholeWord(chars, posInLine,
            ch -> !Character.isWhitespace(ch) && !IdentifierSelectionSupport.isIdentifierChar(ch));
    }

    private static String getWholeWord(char[] chars, int pos, Predicate<Character> predicate)
    {
        StringBuilder forwardPart = new StringBuilder();
        for (int i = pos; i < chars.length && predicate.test(chars[i]); i++)
            forwardPart.append(chars[i]);

        StringBuilder backwardPart = new StringBuilder();
        for (int i = pos - 1; i >= 0 && predicate.test(chars[i]); i--)
            backwardPart.append(chars[i]);

        return backwardPart.reverse().append(forwardPart).toString();
    }

    // ========= Поиск по строке (тексту документа/виджета) =========

    /** @return {@code {start, length}} или {@code {-1, 0}} */
    private static int[] findRegEx(String text, String regex, int from, boolean forward,
        boolean caseSensitive, boolean wrap)
    {
        Pattern pattern;
        try
        {
            pattern = Pattern.compile(regex, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
        }
        catch (PatternSyntaxException e)
        {
            return new int[] { -1, 0 };
        }
        Matcher matcher = pattern.matcher(text);
        if (forward)
        {
            int start = Math.max(from, 0);
            if (start <= text.length() && matcher.find(start))
                return new int[] { matcher.start(), matcher.end() - matcher.start() };
            if (wrap && start > 0 && matcher.find(0))
                return new int[] { matcher.start(), matcher.end() - matcher.start() };
            return new int[] { -1, 0 };
        }
        int lastStart = -1;
        int lastLength = 0;
        int limit = Math.min(Math.max(from, 0), text.length());
        int pos = 0;
        while (pos <= limit && matcher.find(pos))
        {
            if (matcher.start() >= limit)
                break;
            lastStart = matcher.start();
            lastLength = matcher.end() - matcher.start();
            pos = matcher.end() > matcher.start() ? matcher.end() : matcher.end() + 1;
        }
        if (lastStart >= 0)
            return new int[] { lastStart, lastLength };
        if (wrap && limit < text.length())
        {
            pos = limit;
            lastStart = -1;
            while (pos <= text.length() && matcher.find(pos))
            {
                lastStart = matcher.start();
                lastLength = matcher.end() - matcher.start();
                pos = matcher.end() > matcher.start() ? matcher.end() : matcher.end() + 1;
            }
            if (lastStart >= 0)
                return new int[] { lastStart, lastLength };
        }
        return new int[] { -1, 0 };
    }

    private static int indexOf(String text, String search, int from,
        boolean caseSensitive, boolean wholeWord)
    {
        if (from >= text.length() || search.isEmpty())
            return -1;
        int end = text.length() - search.length();
        for (int i = Math.max(from, 0); i <= end; i++)
        {
            if (text.regionMatches(!caseSensitive, i, search, 0, search.length())
                && (!wholeWord || isWordBoundary(text, i, i + search.length())))
                return i;
        }
        return -1;
    }

    private static int lastIndexOf(String text, String search, int from,
        boolean caseSensitive, boolean wholeWord)
    {
        if (from < 0 || search.isEmpty())
            return -1;
        int start = Math.min(from, text.length() - search.length());
        for (int i = start; i >= 0; i--)
        {
            if (text.regionMatches(!caseSensitive, i, search, 0, search.length())
                && (!wholeWord || isWordBoundary(text, i, i + search.length())))
                return i;
        }
        return -1;
    }

    private static boolean isWordBoundary(String text, int matchStart, int matchEnd)
    {
        if (matchStart > 0)
        {
            char prev = text.charAt(matchStart - 1);
            if (IdentifierSelectionSupport.isIdentifierChar(prev))
                return false;
        }
        if (matchEnd < text.length())
        {
            char next = text.charAt(matchEnd);
            if (IdentifierSelectionSupport.isIdentifierChar(next))
                return false;
        }
        return true;
    }
}