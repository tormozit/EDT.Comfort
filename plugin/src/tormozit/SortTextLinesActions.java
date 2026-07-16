package tormozit;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;

/**
 * Логика команды «Сортировать строки текста»: сортировка выделенных строк по алфавиту.
 */
public final class SortTextLinesActions
{
    public static final String MENU_LABEL = "Сортировать строки текста"; //$NON-NLS-1$

    private SortTextLinesActions()
    {
    }

    public static void run(Shell shell, IWorkbenchPart part, IEditorPart editorPart)
    {
        run(shell, TextEditor.resolveContext(part, editorPart));
    }

    public static void run(Shell shell, TextEditor.Context ctx)
    {
        if (ctx == null)
        {
            ToastNotification.show(MENU_LABEL,
                "Откройте текстовый редактор с фокусом в поле текста.", 4000);
            return;
        }

        if (!ctx.editable)
        {
            ToastNotification.show(MENU_LABEL,
                "Редактор доступен только для чтения.", 4000);
            return;
        }

        String delimiter = detectDelimiter(ctx.selectedText);
        if (delimiter == null)
        {
            ToastNotification.show(MENU_LABEL,
                "Необходимо выделить несколько строк текста.", 4000);
            return;
        }

        boolean trailingDelimiter = ctx.selectedText.endsWith(delimiter);
        String body = trailingDelimiter
            ? ctx.selectedText.substring(0, ctx.selectedText.length() - delimiter.length())
            : ctx.selectedText;

        List<String> lines = new ArrayList<>(List.of(body.split(Pattern.quote(delimiter), -1)));
        if (lines.size() < 2)
        {
            ToastNotification.show(MENU_LABEL,
                "Необходимо выделить несколько строк текста.", 4000);
            return;
        }

        Collator collator = Collator.getInstance(new Locale("ru")); //$NON-NLS-1$
        lines.sort(collator::compare);

        String result = String.join(delimiter, lines) + (trailingDelimiter ? delimiter : ""); //$NON-NLS-1$
        TextEditor.replaceSelectionAndSelect(ctx, result);
    }

    public static boolean isAvailable(Shell shell, IWorkbenchPart part, IEditorPart editorPart)
    {
        return isAvailable(shell, TextEditor.resolveContext(part, editorPart));
    }

    public static boolean isAvailable(Shell shell, TextEditor.Context ctx)
    {
        return ctx != null
            && ctx.editable
            && detectDelimiter(ctx.selectedText) != null;
    }

    private static String detectDelimiter(String text)
    {
        if (text == null || text.isEmpty())
            return null;
        if (text.contains("\r\n")) //$NON-NLS-1$
            return "\r\n"; //$NON-NLS-1$
        if (text.contains("\n")) //$NON-NLS-1$
            return "\n"; //$NON-NLS-1$
        if (text.contains("\r")) //$NON-NLS-1$
            return "\r"; //$NON-NLS-1$
        return null;
    }
}
