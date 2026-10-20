package tormozit;

import java.net.URL;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.ISources;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.IURIEditorOpener;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.HTTPService;
import com._1c.g5.v8.dt.metadata.mdclass.Method;
import com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob;
import com._1c.g5.v8.dt.metadata.mdclass.URLTemplate;

/**
 * «Открыть обработчик» объекта метаданных: переход к процедуре из его свойства-обработчика.
 * <ul>
 *   <li>метод HTTP-сервиса — «Обработчик», функция модуля сервиса;</li>
 *   <li>регламентное задание — «Имя метода» вида {@code CommonModule.<Модуль>.<Процедура>}.</li>
 * </ul>
 * Команда — в списке «Шаблоны URL» редактора HTTP-сервиса (plugin.xml); навигатор зовёт
 * {@link #open(EObject)} из своего пункта меню.
 */
public final class MdHandlerOpenHandler extends AbstractHandler
{
    static final String ITEM_TEXT = "Открыть обработчик"; //$NON-NLS-1$

    private static final String COMMON_MODULE_PREFIX = "CommonModule."; //$NON-NLS-1$

    /** Значок штатных «Открыть модуль …» в списках редактора объекта. */
    private static final String ICON_BUNDLE = "com._1c.g5.v8.dt.md.ui.shared"; //$NON-NLS-1$
    private static final String ICON_PATH = "icons/etool16/open_module_co.png"; //$NON-NLS-1$

    private static Image icon;

    @Override
    public void setEnabled(Object evaluationContext)
    {
        Object selection = evaluationContext instanceof IEvaluationContext context
            ? context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME) : null;
        setBaseEnabled(hasHandler(selectedObject(selection)));
    }

    @Override
    public Object execute(ExecutionEvent event)
    {
        EObject object = selectedObject(HandlerUtil.getCurrentSelection(event));
        if (object != null)
            open(object);
        return null;
    }

    private static EObject selectedObject(Object selection)
    {
        return selection instanceof IStructuredSelection structured && structured.size() == 1
            && structured.getFirstElement() instanceof EObject object && isSupported(object) ? object : null;
    }

    /** Объект, у которого есть свойство-обработчик. */
    static boolean isSupported(EObject object)
    {
        return (object instanceof Method || object instanceof ScheduledJob) && !object.eIsProxy();
    }

    static boolean hasHandler(EObject object)
    {
        String handler = handlerText(object);
        return handler != null && !handler.isBlank();
    }

    private static String handlerText(EObject object)
    {
        if (object instanceof Method method)
            return method.getHandler();
        if (object instanceof ScheduledJob job)
            return job.getMethodName();
        return null;
    }

    /** Значок пункта меню; {@code null}, если бандл или файл значка не нашёлся. */
    static Image icon()
    {
        if (icon == null || icon.isDisposed())
        {
            Bundle bundle = Platform.getBundle(ICON_BUNDLE);
            URL url = bundle != null ? bundle.getEntry(ICON_PATH) : null;
            icon = url != null ? ImageDescriptor.createFromURL(url).createImage(false) : null;
        }
        return icon;
    }

    static void open(EObject object)
    {
        String handler = handlerText(object);
        Global.tempLog("md-handler-open", "open handler: object=" + EcoreUtil.getURI(object) //$NON-NLS-1$ //$NON-NLS-2$
            + " handler=" + handler); //$NON-NLS-1$
        try
        {
            com._1c.g5.v8.dt.bsl.model.Method target = targetMethod(object);
            if (target != null)
            {
                // Как EventHandlersOpenHandlerHook: штатный BSL opener по URI метода.
                IResourceServiceProvider provider = IResourceServiceProvider.Registry.INSTANCE
                    .getResourceServiceProvider(URI.createURI("*.bsl")); //$NON-NLS-1$
                IURIEditorOpener opener = provider != null ? provider.get(IURIEditorOpener.class) : null;
                if (opener != null && opener.open(EcoreUtil.getURI(target), true) != null)
                    return;
            }
            ToastNotification.show(ITEM_TEXT, "Не удалось найти обработчик «" + handler + "»."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (RuntimeException e)
        {
            Global.logError("MdHandlerOpenHandler", "open handler", e); //$NON-NLS-1$ //$NON-NLS-2$
            Global.tempLog("md-handler-open", "open handler failed: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            ToastNotification.show(ITEM_TEXT, "Не удалось открыть обработчик «" + handler + "»."); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static com._1c.g5.v8.dt.bsl.model.Method targetMethod(EObject object)
    {
        String handler = handlerText(object);
        if (handler == null || handler.isBlank())
            return null;
        if (object instanceof Method method)
        {
            if (method.eContainer() instanceof URLTemplate template
                && template.eContainer() instanceof HTTPService service && service.getModule() != null)
                return methodNamed((Module) EcoreUtil.resolve(service.getModule(), service), handler);
            return null;
        }
        // Как у обработчика подписки (EventHandlersOpenHandlerHook.resolveSubscriptionHandler):
        // тип модуля в строке всегда английский, как в XML конфигурации.
        int lastDot = handler.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == handler.length() - 1)
            return null;
        String moduleFqn = handler.substring(0, lastDot);
        if (!moduleFqn.startsWith(COMMON_MODULE_PREFIX) || !(object instanceof IBmObject bmObject))
            return null;
        IBmEngine engine = bmObject.bmGetEngine();
        if (engine == null || !(engine.getTopObjectByFqn(moduleFqn) instanceof CommonModule commonModule))
            return null;
        return methodNamed(commonModule.getModule(), handler.substring(lastDot + 1));
    }

    private static com._1c.g5.v8.dt.bsl.model.Method methodNamed(Module module, String name)
    {
        if (module == null)
            return null;
        for (com._1c.g5.v8.dt.bsl.model.Method method : module.allMethods())
            if (method != null && name.equalsIgnoreCase(method.getName()))
                return method;
        return null;
    }
}
