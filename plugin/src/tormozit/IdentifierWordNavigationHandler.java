package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Перехват команд word navigation (Ctrl+←/→ и Ctrl+Shift+←/→): граница идентификатора.
 *
 * <p>Цель — виджет с фокусом, а не обязательно {@link ITextEditor}. В поле результата
 * объединения модулей Комфорт активирует {@code XtextEditorScope} (чтобы работало
 * «Переключить комментарий»), и штатные привязки редактора забирают Ctrl+←/→ раньше
 * виджета. Активный редактор при этом — не это поле: без фокуса каретка там не двигалась.
 */
public final class IdentifierWordNavigationHandler extends AbstractHandler
{
    static final String CMD_SELECT_WORD_PREVIOUS =
        "org.eclipse.ui.edit.text.select.wordPrevious"; //$NON-NLS-1$
    static final String CMD_SELECT_WORD_NEXT =
        "org.eclipse.ui.edit.text.select.wordNext"; //$NON-NLS-1$
    static final String CMD_GOTO_WORD_PREVIOUS =
        "org.eclipse.ui.edit.text.goto.wordPrevious"; //$NON-NLS-1$
    static final String CMD_GOTO_WORD_NEXT =
        "org.eclipse.ui.edit.text.goto.wordNext"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        String commandId = event.getCommand().getId();
        StyledText text = resolveFocusedStyledText();
        if (text == null)
        {
            IEditorPart editorPart = HandlerUtil.getActiveEditor(event);
            IWorkbenchPart part = HandlerUtil.getActivePart(event);
            ITextEditor textEditor = TextEditor.resolveTextEditor(editorPart);
            if (textEditor == null && part instanceof IEditorPart ep)
                textEditor = TextEditor.resolveTextEditor(ep);
            text = resolveStyledText(textEditor);
        }
        if (text == null)
            return null;

        boolean toLeft = CMD_SELECT_WORD_PREVIOUS.equals(commandId)
            || CMD_GOTO_WORD_PREVIOUS.equals(commandId);
        boolean select = CMD_SELECT_WORD_PREVIOUS.equals(commandId)
            || CMD_SELECT_WORD_NEXT.equals(commandId);
        if (select)
            IdentifierSelectionSupport.extendSelection(text, toLeft);
        else
            IdentifierSelectionSupport.moveCaret(text, toLeft);
        return null;
    }

    private static StyledText resolveFocusedStyledText()
    {
        Display display = Display.getCurrent();
        if (display == null)
            display = Display.getDefault();
        if (display == null || display.isDisposed())
            return null;
        Control focus = display.getFocusControl();
        if (!(focus instanceof StyledText text) || text.isDisposed() || text.getBlockSelection())
            return null;
        return text;
    }

    private static StyledText resolveStyledText(ITextEditor textEditor)
    {
        if (textEditor == null)
            return null;
        ISourceViewer viewer = TextEditor.getSourceViewer(textEditor);
        if (viewer == null)
            return null;
        Control widget = viewer.getTextWidget();
        return widget instanceof StyledText styledText ? styledText : null;
    }
}
