package tormozit;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.resources.IResource;
import org.eclipse.emf.edit.ui.dnd.LocalTransfer;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.part.ResourceTransfer;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Довешивает формат "Текст" к результату штатного копирования объектов навигатора.
 *
 * <p>{@code CopyMdObjectHandler} (декомпилировано, aef2-standard-swt) всегда кладёт в буфер
 * {@link LocalTransfer} с массивом {@link MdObject}, а {@link TextTransfer} — только когда
 * выделен один объект (второй элемент — его имя). При выделении нескольких объектов
 * текстового формата нет вовсе, поэтому вставка имён в сторонние приложения не работает.
 *
 * <p>Не подменяем сам хэндлер, а довешиваем формат через {@code postExecuteSuccess} команды
 * {@code org.eclipse.ui.edit.copy}: читаем то, что штатный хэндлер только что положил в
 * {@link LocalTransfer} (это ссылка на Java-объект, а не сериализованные байты, поэтому доступна
 * из другого {@link Clipboard} в том же JVM), и перезаписываем буфер той же ссылкой на объекты
 * плюс их именами, склеенными через перевод строки.
 *
 * <p>То же самое для дерева «Структура проекта» ({@code org.eclipse.ui.navigator.ProjectExplorer})
 * — там штатное копирование кладёт {@link IResource}{@code []} через {@link ResourceTransfer}
 * (сериализуется, но читается обратно из буфера сразу же), без текстового формата вовсе;
 * довешиваем имена файлов/папок тем же способом.
 */
public final class NavigatorCopyTextAugmentHook implements IStartup
{
    private static final String COPY_COMMAND_ID = "org.eclipse.ui.edit.copy"; //$NON-NLS-1$

    private static boolean executionListenerInstalled;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null)
            return;
        display.asyncExec(NavigatorCopyTextAugmentHook::installExecutionListener);
    }

    private static void installExecutionListener()
    {
        if (executionListenerInstalled || PlatformUI.getWorkbench() == null)
            return;
        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        commandService.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
                handlePossibleNavigatorCopy(commandId);
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
        executionListenerInstalled = true;
    }

    private static void handlePossibleNavigatorCopy(String commandId)
    {
        if (!COPY_COMMAND_ID.equals(commandId))
            return;
        Display display = Display.getCurrent();
        if (display == null)
            return;
        Clipboard clipboard = new Clipboard(display);
        try
        {
            Object mdObjectData = clipboard.getContents(LocalTransfer.getInstance());
            if (mdObjectData instanceof MdObject[] mdObjects && mdObjects.length > 0)
            {
                StringBuilder text = new StringBuilder();
                for (MdObject mdObject : mdObjects)
                {
                    if (text.length() > 0)
                        text.append('\n');
                    text.append(mdObject.getName());
                }
                clipboard.setContents(new Object[] {mdObjects, text.toString()},
                    new Transfer[] {LocalTransfer.getInstance(), TextTransfer.getInstance()});
                return;
            }

            Object resourceData = clipboard.getContents(ResourceTransfer.getInstance());
            if (resourceData instanceof IResource[] resources && resources.length > 0)
            {
                StringBuilder text = new StringBuilder();
                for (IResource resource : resources)
                {
                    if (text.length() > 0)
                        text.append('\n');
                    text.append(resource.getName());
                }
                clipboard.setContents(new Object[] {resources, text.toString()},
                    new Transfer[] {ResourceTransfer.getInstance(), TextTransfer.getInstance()});
            }
        }
        finally
        {
            clipboard.dispose();
        }
    }
}
