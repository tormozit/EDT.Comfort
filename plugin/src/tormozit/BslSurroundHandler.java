package tormozit;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

final class BslSurroundHandler
{
    enum SurroundKind
    {
        IF("Если", "Если ", " Тогда", "КонецЕсли;"),
        FOR_EACH("Для каждого", "Для каждого ", " Из  Цикл", "КонецЦикла;"),
        FOR_TO("Для по", "Для ", " =  По  Цикл", "КонецЦикла;"),
        WHILE("Пока", "Пока ", " Цикл", "КонецЦикла;"),
        TRY("Попытка", null, null, "КонецПопытки;"),
        REGION("#Область", "#Область ", " ", "#КонецОбласти"),
        PREPROC_IF("#Если", "#Если ", " Тогда", "#КонецЕсли");

        final String label;
        final String before;
        final String after;
        final String closing;

        SurroundKind(String label, String before, String after, String closing)
        {
            this.label = label;
            this.before = before;
            this.after = after;
            this.closing = closing;
        }
    }

    private BslSurroundHandler() {}

    static void surroundWith(BslXtextEditor editor, SurroundKind kind)
    {
        if (editor == null || kind == null)
            return;

        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null)
            return;

        Object sel = viewer.getSelectionProvider().getSelection();
        if (!(sel instanceof ITextSelection textSel))
            return;

        IDocument doc = viewer.getDocument();
        if (doc == null)
            return;

        try
        {
            int offset = textSel.getOffset();
            int length = textSel.getLength();
            String selected;

            if (length > 0)
            {
                selected = doc.get(offset, length);
                while (selected.endsWith("\n"))
                    selected = selected.substring(0, selected.length() - 1);
            }
            else
            {
                int line = doc.getLineOfOffset(offset);
                org.eclipse.jface.text.IRegion lineInfo = doc.getLineInformation(line);
                offset = lineInfo.getOffset();
                length = lineInfo.getLength();
                selected = doc.get(offset, length);
            }

            int firstLine = doc.getLineOfOffset(offset);
            String lineText = doc.get(
                doc.getLineInformation(firstLine).getOffset(),
                doc.getLineInformation(firstLine).getLength());
            String indent = extractIndent(lineText);

            String body = indentBody(selected);

            String replacement;
            int cursorPos;

            if (kind == SurroundKind.TRY)
            {
                String tryOpen = indent + "Попытка\n";
                String tryExc = "\n" + indent + "Исключение\n";
                String tryClose = indent + kind.closing;
                replacement = tryOpen + body + tryExc + indent + "\n" + tryClose;
                cursorPos = offset + tryOpen.length() + body.length()
                    + tryExc.length() + indent.length();
            }
            else
            {
                String opening = indent + kind.before + kind.after;
                replacement = opening + "\n" + body + "\n" + indent + kind.closing;
                cursorPos = offset + indent.length() + kind.before.length();
            }

            if (doc instanceof IXtextDocument xtextDoc)
            {
                int off = offset;
                int len = length;
                String rep = replacement;
                xtextDoc.modify(resource ->
                {
                    xtextDoc.replace(off, len, rep);
                    return null;
                });
            }
            else
            {
                doc.replace(offset, length, replacement);
            }

            viewer.setSelectedRange(cursorPos, 0);
            viewer.getSelectionProvider().setSelection(
                new org.eclipse.jface.text.TextSelection(cursorPos, 0));
        }
        catch (BadLocationException e)
        {
            Global.logError("BslSurroundHandler", "surroundWith(" + kind.label + ")", e);
        }
    }

    private static String extractIndent(String text)
    {
        if (text == null || text.isEmpty())
            return "";
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t')
                return text.substring(0, i);
        }
        return text;
    }

    private static String indentBody(String text)
    {
        if (text == null || text.isEmpty())
            return "";
        String[] lines = text.split("\\n", -1);
        StringBuilder sb = new StringBuilder(text.length() + lines.length);
        for (int i = 0; i < lines.length; i++)
        {
            if (i > 0)
                sb.append('\n');
            sb.append('\t').append(lines[i]);
        }
        return sb.toString();
    }
}
