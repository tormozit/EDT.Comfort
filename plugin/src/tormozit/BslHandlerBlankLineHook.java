package tormozit;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.swt.graphics.Point;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Пустая строка-разделитель перед создаваемым обработчиком события, как в конфигураторе.
 *
 * <p>Штатная EDT вставляет процедуру вплотную к предыдущему методу. Окно «Создать обработчик
 * события» открывают команды формы {@code gotoEventHandler} / {@code gotoCommandEventHandler}
 * (переход к обработчику события элемента или команды формы): они вставляют текст обычным
 * {@code IXtextDocument.replace} и затем выделяют в нём заготовку {@code //TODO}.
 * Подмена {@code BslModuleRegionsInfoServiceProvider.wrap} не работает: к моменту регистрации
 * {@code WeavingHook} класс уже загружен. Поэтому после выполнения команды находим новый обработчик
 * по выделенной заготовке и дописываем пустую строку отдельной правкой.
 *
 * <p>Для обработчика из схемы модуля разделитель добавляет
 * {@link #ensureSeparatingBlankLineBeforeHandler} напрямую (см. {@code BslOutlineEventsSupport}).
 *
 * @see <a href="https://github.com/tormozit/EDT.Comfort/issues/394">issue 394</a>
 */
public final class BslHandlerBlankLineHook implements IStartup
{
    @Override
    public void earlyStartup()
    {
        PlatformUI.getWorkbench().getDisplay().asyncExec(() ->
        {
            ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commandService != null)
                commandService.addExecutionListener(new GotoEventHandlerCommandListener());
        });
    }

    /**
     * Пустая строка-разделитель перед новым обработчиком, как в конфигураторе.
     * Идемпотентно: если разделитель уже есть в документе или в {@code content}, ничего не добавляет.
     */
    static String ensureSeparatingBlankLineBeforeHandler(IXtextDocument document, int offset, String content)
    {
        if (content == null || content.isEmpty() || document == null || offset <= 0)
            return content;
        try
        {
            int safeOffset = Math.min(offset, Math.max(0, document.getLength() - 1));
            String ld = document.getLineDelimiter(document.getLineOfOffset(safeOffset));
            if (ld == null || ld.isEmpty())
                ld = "\r\n"; //$NON-NLS-1$

            if (hasBlankLineBefore(document, offset))
                return stripLeadingLineDelimiters(content);

            if (countLeadingLineDelimiters(content) >= 2)
                return content;

            String body = stripLeadingLineDelimiters(content);
            StringBuilder prefix = new StringBuilder();
            char charBefore = document.getChar(offset - 1);
            if (charBefore != '\n' && charBefore != '\r')
                prefix.append(ld);
            prefix.append(ld);
            return prefix.toString() + body;
        }
        catch (BadLocationException e)
        {
            return content;
        }
    }

    private static boolean hasBlankLineBefore(IXtextDocument document, int offset) throws BadLocationException
    {
        if (offset <= 0)
            return true;
        int length = document.getLength();
        if (length <= 0)
            return true;
        int line = document.getLineOfOffset(Math.min(offset, length - 1));
        int lineStart = document.getLineOffset(line);
        if (offset == lineStart && document.getLineLength(line) == 0)
            return true;
        if (offset == lineStart && document.get(lineStart, document.getLineLength(line)).trim().isEmpty())
            return true;
        if (line > 0)
        {
            int prevStart = document.getLineOffset(line - 1);
            if (document.get(prevStart, document.getLineLength(line - 1)).trim().isEmpty())
                return true;
        }
        return false;
    }

    private static String stripLeadingLineDelimiters(String text)
    {
        if (text == null || text.isEmpty())
            return text;
        int i = 0;
        while (i < text.length())
        {
            char c = text.charAt(i);
            if (c == '\r')
            {
                i++;
                if (i < text.length() && text.charAt(i) == '\n')
                    i++;
            }
            else if (c == '\n')
                i++;
            else
                break;
        }
        return text.substring(i);
    }

    private static int countLeadingLineDelimiters(String text)
    {
        if (text == null || text.isEmpty())
            return 0;
        int count = 0;
        int i = 0;
        while (i < text.length())
        {
            char c = text.charAt(i);
            if (c == '\r')
            {
                i++;
                if (i < text.length() && text.charAt(i) == '\n')
                    i++;
                count++;
            }
            else if (c == '\n')
            {
                i++;
                count++;
            }
            else
                break;
        }
        return count;
    }

    private static final class GotoEventHandlerCommandListener implements IExecutionListener
    {
        private static final String COMMAND_EVENT = "com._1c.g5.v8.dt.form.ui.commands.gotoEventHandler"; //$NON-NLS-1$
        private static final String COMMAND_FORM_COMMAND =
            "com._1c.g5.v8.dt.form.ui.commands.gotoCommandEventHandler"; //$NON-NLS-1$

        private static boolean isTarget(String commandId)
        {
            return COMMAND_EVENT.equals(commandId) || COMMAND_FORM_COMMAND.equals(commandId);
        }

        @Override
        public void preExecute(String commandId, ExecutionEvent event)
        {
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue)
        {
            if (!isTarget(commandId))
                return;
            PlatformUI.getWorkbench().getDisplay().asyncExec(() ->
            {
                try
                {
                    fix();
                }
                catch (Throwable t)
                {
                    Global.logError("BslHandlerBlankLine", "fix", t); //$NON-NLS-1$ //$NON-NLS-2$
                }
            });
        }

        @Override
        public void postExecuteFailure(String commandId, ExecutionException exception)
        {
        }

        @Override
        public void notHandled(String commandId, NotHandledException exception)
        {
        }

        private static boolean isMethodHeader(String trimmed)
        {
            return trimmed.startsWith("Процедура") || trimmed.startsWith("Функция") //$NON-NLS-1$ //$NON-NLS-2$
                || trimmed.startsWith("Procedure") || trimmed.startsWith("Function"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** Только что созданный обработчик: EDT выделяет в нём заготовку {@code //TODO…}. */
        private void fix() throws BadLocationException
        {
            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null
                ? PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage() : null;
            IEditorPart part = page != null ? page.getActiveEditor() : null;
            BslXtextEditor editor = part != null ? GetRef.getActiveBslEditor(part) : null;
            IXtextDocument document = editor != null ? editor.getDocument() : null;
            if (document == null)
                return;
            var viewer = editor.getInternalSourceViewer();
            Point sel = viewer.getSelectedRange();
            String selected = sel.y > 0 ? document.get(sel.x, sel.y) : ""; //$NON-NLS-1$
            if (!selected.startsWith("//TODO")) //$NON-NLS-1$
                return;
            int line = document.getLineOfOffset(sel.x);
            while (line > 0 && !isMethodHeader(document.get(document.getLineOffset(line),
                document.getLineLength(line)).trim()))
                line--;
            while (line > 0 && document.get(document.getLineOffset(line - 1),
                document.getLineLength(line - 1)).trim().startsWith("&")) //$NON-NLS-1$
                line--;
            int start = document.getLineOffset(line);
            boolean needBlank = line > 0 && !hasBlankLineBefore(document, start);
            if (!needBlank)
                return;
            String ld = document.getLineDelimiter(line - 1);
            if (ld == null || ld.isEmpty())
                ld = "\r\n"; //$NON-NLS-1$
            document.replace(start, 0, ld);
            viewer.setSelectedRange(sel.x + ld.length(), sel.y);
            viewer.revealRange(sel.x + ld.length(), sel.y);
        }
    }
}
