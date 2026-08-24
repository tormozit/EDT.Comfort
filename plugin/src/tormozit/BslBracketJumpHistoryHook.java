package tormozit;

import java.util.Set;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.xtext.ui.editor.XtextEditor;

import com._1c.g5.v8.dt.bsl.ui.menu.BslHandlerUtil;

/**
 * Переходы по операторным скобкам попадают в историю навигации EDT
 * («Назад по истории» / «Вперёд по истории»),
 * <a href="https://github.com/tormozit/EDT.Comfort/issues/369">issue 369</a>.
 *
 * <p>Штатные обработчики EDT ({@code ForwardBslBracketSearchHandler} и др.) двигают каретку
 * напрямую через {@code ITextViewer.setSelectedRange}, минуя
 * {@code AbstractTextEditor.selectAndReveal} — а отметку в истории платформа ставит именно
 * там. В результате после перехода от «Для» к «КонецЦикла» команда «Назад по истории»
 * уходила не к «Для», а к объекту, открытому до текущего.
 *
 * <p>Лечение — то же, что делает {@code selectAndReveal}: отметка ДО перехода (запоминает
 * исходную строку) и отметка ПОСЛЕ (новая позиция становится текущей записью истории).
 * Обе обязательны: перед переходом «назад» платформа обновляет текущую запись по фактической
 * каретке ({@code NavigationHistory.shiftEntry}), поэтому одной отметки «до» недостаточно —
 * она будет перезаписана позицией уже после прыжка.
 */
public final class BslBracketJumpHistoryHook implements IStartup
{
    /** Команды перехода по операторным скобкам (вперёд/назад, с выделением и без). */
    private static final Set<String> JUMP_COMMAND_IDS = Set.of(
        "com._1c.g5.v8.dt.bsl.ui.menu.ForwardBslBracketSearchHandler", //$NON-NLS-1$
        "com._1c.g5.v8.dt.bsl.ui.menu.BackBslBracketSearchHandler", //$NON-NLS-1$
        "com._1c.g5.v8.dt.bsl.ui.menu.ForwardBslBracketSearchWithSelectionHandler", //$NON-NLS-1$
        "com._1c.g5.v8.dt.bsl.ui.menu.BackBslBracketSearchWithSelectionHandler"); //$NON-NLS-1$

    /** Редактор, отмеченный в {@code preExecute}; в нём же ставится отметка после перехода. */
    private IEditorPart pendingEditor;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(this::install);
    }

    private void install()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;
        ICommandService commandService =
            PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        commandService.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
                if (!JUMP_COMMAND_IDS.contains(commandId))
                    return;
                pendingEditor = resolveTextEditor(event);
                Global.markNavigationLocation(pendingEditor);
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
                if (!JUMP_COMMAND_IDS.contains(commandId))
                    return;
                IEditorPart editor = pendingEditor;
                pendingEditor = null;
                /*
                 * Каретку обработчик переставил синхронно, поэтому отметку берём сразу:
                 * asyncExec поставил бы её после чужих отметок и перепутал порядок записей.
                 * Если перехода не было (парная скобка не найдена), новая запись совпадёт с
                 * текущей и платформа отбросит её сама (NavigationHistoryEntry.mergeInto).
                 */
                Global.markNavigationLocation(editor);
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception)
            {
                if (JUMP_COMMAND_IDS.contains(commandId))
                    pendingEditor = null;
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception)
            {
                if (JUMP_COMMAND_IDS.contains(commandId))
                    pendingEditor = null;
            }
        });
    }

    /**
     * Текстовый редактор модуля, в котором выполняется команда: отдельная вкладка модуля или
     * редактор, встроенный в страницу редактора объекта метаданных. Отмечать надо именно его —
     * только он умеет отдавать позицию каретки ({@code createNavigationLocation}); у редактора
     * объекта метаданных запись истории с точностью до открытого файла.
     */
    private static IEditorPart resolveTextEditor(ExecutionEvent event)
    {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        if (part == null)
            return null;
        XtextEditor xtextEditor = BslHandlerUtil.extractXtextEditor(part);
        if (xtextEditor != null)
            return xtextEditor;
        return part instanceof IEditorPart editor ? editor : null;
    }
}
