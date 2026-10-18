package tormozit;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.INavigationHistory;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

import com._1c.g5.v8.dt.ui.editor.input.DtEditorProxyInput;
import com._1c.g5.v8.dt.ui.editor.input.IDtEditorInput;

/**
 * «Назад/Вперёд по истории» после перезапуска не открывает дубли редактора,
 * <a href="https://github.com/tormozit/EDT.Comfort/issues/829">issue 829</a>.
 *
 * <p>После перезапуска записи истории хранят вход-заглушку {@link DtEditorProxyInput}, как и
 * лениво восстановленные вкладки. Когда вкладка достраивается до настоящего входа
 * ({@code CommonModuleEditorInput} и т.п.), запись по заглушке перестаёт совпадать с ней:
 * {@code DtEditorInput.equals} требует одинакового класса. Тогда
 * {@code NavigationHistoryEntry.restoreLocation} открывает второй редактор, а
 * {@code DtEditorInputNavigationLocation.restoreLocation} не находит свой ({@code findEditor})
 * и падает с NPE.
 *
 * <p>Лечение — перед командой истории заменить в записях заглушку на вход уже открытого
 * редактора того же объекта (то же поле, которое Eclipse читает при восстановлении).
 */
public final class NavigationHistoryHook implements IStartup
{
    private static final Set<String> HISTORY_COMMAND_IDS = Set.of(
        "org.eclipse.ui.navigate.backwardHistory", //$NON-NLS-1$
        "org.eclipse.ui.navigate.forwardHistory"); //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(this::install);
    }

    private void install()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;
        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        commandService.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
                if (HISTORY_COMMAND_IDS.contains(commandId))
                    replaceProxyInputs();
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception)
            {
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception)
            {
            }
        });
    }

    private static void replaceProxyInputs()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            INavigationHistory history = page == null ? null : page.getNavigationHistory();
            if (history == null
                || !"org.eclipse.ui.internal.NavigationHistory".equals(history.getClass().getName())) //$NON-NLS-1$
                return;
            Field editorsField = history.getClass().getDeclaredField("editors"); //$NON-NLS-1$
            editorsField.setAccessible(true);
            List<?> infos = (List<?>)editorsField.get(history);
            Field idField = null;
            Field inputField = null;
            for (Object info : infos)
            {
                if (idField == null)
                {
                    idField = info.getClass().getDeclaredField("editorID"); //$NON-NLS-1$
                    inputField = info.getClass().getDeclaredField("editorInput"); //$NON-NLS-1$
                    idField.setAccessible(true);
                    inputField.setAccessible(true);
                }
                if (!(inputField.get(info) instanceof DtEditorProxyInput proxy))
                    continue;
                IEditorInput real = findOpenInput(page, (String)idField.get(info), proxy);
                if (real != null)
                    inputField.set(info, real);
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            // При несовместимости платформы остаётся штатная история.
        }
    }

    /** Вход уже созданного (не ленивого) редактора с тем же id и тем же объектом метаданных. */
    private static IEditorInput findOpenInput(IWorkbenchPage page, String editorId, DtEditorProxyInput proxy)
    {
        String uri = modelUri(proxy);
        if (uri == null)
            return null;
        for (IEditorReference ref : page.getEditorReferences())
        {
            if (!ref.getId().equals(editorId) || ref.getPart(false) == null)
                continue;
            try
            {
                IEditorInput input = ref.getEditorInput();
                if (input instanceof IDtEditorInput<?> dt && !(input instanceof DtEditorProxyInput)
                    && uri.equals(modelUri(dt)))
                    return input;
            }
            catch (org.eclipse.ui.PartInitException e)
            {
                // Пропускаем редактор, вход которого не восстановился.
            }
        }
        return null;
    }

    private static String modelUri(IDtEditorInput<?> input)
    {
        EObject model = input.getModel();
        return model == null ? null : EcoreUtil.getURI(model).toString();
    }
}
