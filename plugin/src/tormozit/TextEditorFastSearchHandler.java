package tormozit;

import java.util.function.Predicate;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.IDialogSettingsProvider;
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

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        String commandId = event.getCommand().getId();
        boolean forward = CMD_FORWARD.equals(commandId);

        IEditorPart editorPart = HandlerUtil.getActiveEditor(event);
        ITextEditor textEditor = editorPart != null
            ? TextEditor.resolveTextEditor(editorPart) : null;

        StyledText textWidget = null;
        if (textEditor != null)
        {
            var viewer = TextEditor.getSourceViewer(textEditor);
            if (viewer != null && viewer.getTextWidget() instanceof StyledText st)
                textWidget = st;
        }
        if (textWidget == null)
            textWidget = resolveStyledTextFromFocus();
        if (textWidget == null || textWidget.isDisposed())
            return null;

        return executeSearch(textWidget, forward);
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

    /** Общий вход для {@link #execute} и для Display-фильтра (модальный «Редактор запроса»). */
    public static Object executeSearch(StyledText textWidget, boolean forward)
    {
        if (textWidget == null || textWidget.isDisposed())
            return null;

        Point selRange = textWidget.getSelectionRange();
        int offset = selRange.x;
        String selectionText = textWidget.getSelectionText();
        String fullText = textWidget.getText();

        String searchString = getSearchString(offset, selectionText, textWidget);
        if (searchString == null || searchString.isEmpty())
        {
            if (Global.isLogEnabled())
                Global.log(TAG, "executeSearch: searchString=null offset=" + offset);
            return null;
        }

        if (selectionText != null && !selectionText.isEmpty())
            offset = forward ? selRange.x + selRange.y : selRange.x;

        boolean caseSensitive = isCaseSensitiveSearch();
        boolean wholeWord = isWholeWordSearch();

        if (Global.isLogEnabled())
            Global.log(TAG, "executeSearch: search='" + searchString
                + "' from=" + offset + " forward=" + forward
                + " caseSensitive=" + caseSensitive + " wholeWord=" + wholeWord);

        int found = forward
            ? indexOf(fullText, searchString, offset + 1, caseSensitive, wholeWord)
            : lastIndexOf(fullText, searchString, offset - 1, caseSensitive, wholeWord);
        if (found == -1)
            found = forward
                ? indexOf(fullText, searchString, 0, caseSensitive, wholeWord)
                : lastIndexOf(fullText, searchString, fullText.length() - 1, caseSensitive, wholeWord);

        if (found >= 0)
        {
            textWidget.setSelectionRange(found, searchString.length());
            textWidget.showSelection();
        }

        if (Global.isLogEnabled())
            Global.log(TAG, "executeSearch: result=" + found);
        return null;
    }

    private static boolean isCaseSensitiveSearch()
    {
        return readFindReplaceFlag(KEY_CASE_SENSITIVE);
    }

    private static boolean isWholeWordSearch()
    {
        return readFindReplaceFlag(KEY_WHOLE_WORD);
    }

    private static boolean readFindReplaceFlag(String key)
    {
        try
        {
            Bundle bundle = Platform.getBundle(FIND_REPLACE_BUNDLE);
            if (bundle == null)
                return false;
            IDialogSettingsProvider provider = PlatformUI.getDialogSettingsProvider(bundle);
            if (provider == null)
                return false;
            IDialogSettings settings = provider.getDialogSettings();
            if (settings == null)
                return false;
            IDialogSettings section = settings.getSection(FIND_REPLACE_SECTION);
            return section != null && section.getBoolean(key);
        }
        catch (RuntimeException e)
        {
            return false;
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

    // ========= Поиск напрямую через StyledText =========

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