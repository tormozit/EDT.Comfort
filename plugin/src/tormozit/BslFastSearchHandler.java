package tormozit;

import java.util.function.Predicate;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.IDialogSettingsProvider;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IFindReplaceTarget;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;
import org.osgi.framework.Bundle;

/**
 * Команды-дублёры «Быстрый поиск вперёд/назад» ({@code tormozit.BslFastSearchForward}/{@code Back}),
 * перехватывающие сочетания клавиш штатных команд EDT
 * ({@code com._1c.g5.v8.dt.bsl.ui.menu.ForwardBslFastSearchHandler}/{@code Back...}; по умолчанию
 * Ctrl+F3 / Shift+F3) через привязку к тем же {@code M1+F3}/{@code M2+F3} в том же контексте
 * {@code org.eclipse.xtext.ui.XtextEditorScope} — это надёжнее, чем переопределение handler'а той же
 * команды (конфликт handler-расширений в Eclipse разрешается не так предсказуемо, как конфликт
 * биндингов клавиш).
 * <p>
 * Штатная реализация ({@code AbstractBslFastSearchHandler.getSearchString}/{@code getWholeWord})
 * захватывает идентификатор под кареткой через {@code Character.isLetterOrDigit}, из-за чего
 * {@code _} обрывает идентификатор. Здесь применяется та же граница, что и для word navigation
 * ({@link IdentifierSelectionSupport#isIdentifierChar(char)}), остальная логика (включая порядок
 * поиска и перенос на начало/конец документа, а также флажки «С учётом регистра»/«Слово целиком»
 * из диалога «Найти/Заменить») воспроизводит штатную. Еще штатная команда иногда скачет глючит - скачает непоследовально в 2025.2.5
 */
public final class BslFastSearchHandler extends AbstractHandler
{
    static final String CMD_FORWARD =
        "tormozit.BslFastSearchForward"; //$NON-NLS-1$
    static final String CMD_BACK =
        "tormozit.BslFastSearchBack"; //$NON-NLS-1$

    /** Бандл, хранящий настройки диалога «Найти/Заменить» (Ctrl+F). */
    private static final String FIND_REPLACE_BUNDLE = "org.eclipse.ui.workbench.texteditor"; //$NON-NLS-1$
    /** Имя секции — полное имя класса {@code org.eclipse.ui.texteditor.FindReplaceDialog}. */
    private static final String FIND_REPLACE_SECTION = "org.eclipse.ui.texteditor.FindReplaceDialog"; //$NON-NLS-1$
    private static final String KEY_CASE_SENSITIVE = "casesensitive"; //$NON-NLS-1$
    private static final String KEY_WHOLE_WORD = "wholeword"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        String commandId = event.getCommand().getId();
        boolean forward = CMD_FORWARD.equals(commandId);

        IEditorPart editorPart = HandlerUtil.getActiveEditor(event);
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        ITextEditor textEditor = TextEditorSupport.resolveTextEditor(editorPart);
        if (textEditor == null && part instanceof IEditorPart ep)
            textEditor = TextEditorSupport.resolveTextEditor(ep);
        if (textEditor == null)
            return null;

        ISourceViewer viewer = TextEditorSupport.getSourceViewer(textEditor);
        if (viewer == null)
            return null;

        IFindReplaceTarget target = textEditor.getAdapter(IFindReplaceTarget.class);
        if (target == null)
            return null;

        Point selectedRange = viewer.getSelectedRange();
        int offset = selectedRange.x;

        String searchString = getSearchString(offset, target, viewer.getDocument());
        if (searchString == null || searchString.isEmpty())
            return null;

        String selectionText = target.getSelectionText();
        if (selectionText != null && !selectionText.isEmpty())
        {
            offset = forward
                ? selectedRange.x + selectedRange.y
                : selectedRange.x;
        }

        boolean caseSensitive = isCaseSensitiveSearch();
        boolean wholeWord = isWholeWordSearch();

        int searchFrom = forward ? offset + 1 : offset - 1;
        int result = target.findAndSelect(searchFrom, searchString, forward, caseSensitive, wholeWord);
        if (result == -1)
            target.findAndSelect(-1, searchString, forward, caseSensitive, wholeWord);
        return null;
    }

    /** Флажок «С учётом регистра» из диалога «Найти/Заменить» (Ctrl+F). */
    private static boolean isCaseSensitiveSearch()
    {
        return readFindReplaceFlag(KEY_CASE_SENSITIVE);
    }

    /** Флажок «Слово целиком» из диалога «Найти/Заменить» (Ctrl+F). */
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

    /** Аналог {@code AbstractBslFastSearchHandler.getSearchString}, граница — идентификатор с {@code _}. */
    private static String getSearchString(int offset, IFindReplaceTarget target, IDocument document)
    {
        String selection = target.getSelectionText();
        if (selection != null && !selection.isEmpty())
            return selection;

        if (document == null)
            return null;

        try
        {
            IRegion line = document.getLineInformationOfOffset(offset);
            String lineText = document.get(line.getOffset(), line.getLength());
            if (lineText.isEmpty())
                return null;

            int posInLine = offset - line.getOffset();
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
        catch (BadLocationException e)
        {
            return null;
        }
    }

    /** Аналог {@code AbstractBslFastSearchHandler.getWholeWord}: расширение от {@code pos} в обе стороны. */
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
}
