package tormozit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.operations.model.IEditingContext;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;

/**
 * Редактор объекта «Стиль»: обход штатной ошибки EDT — признак «изменён» сразу после открытия
 * (issue 530, см. {@link ReadonlyStyleItemsContext}).
 */
public final class StyleEditorHook implements IStartup
{
    /** Редактор объекта метаданных «Стиль» (md.ui); страница «Стиль» в нём — из style.ui. */
    private static final String EDITOR_ID = "com._1c.g5.v8.dt.md.ui.editor.style"; //$NON-NLS-1$

    private final Map<IWorkbenchWindow, Boolean> hookedWindows = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
                hookWindow(window);
            workbench.addWindowListener(new IWindowListener()
            {
                @Override
                public void windowOpened(IWorkbenchWindow window) { hookWindow(window); }

                @Override
                public void windowActivated(IWorkbenchWindow window) { hookWindow(window); }

                @Override
                public void windowDeactivated(IWorkbenchWindow window) {}

                @Override
                public void windowClosed(IWorkbenchWindow window) { hookedWindows.remove(window); }
            });
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        if (window == null || hookedWindows.put(window, Boolean.TRUE) != null)
            return;
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref) { onPart(ref); }

            @Override
            public void partActivated(IWorkbenchPartReference ref) { onPart(ref); }
        });
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
            for (var ref : page.getEditorReferences())
                onPart(ref);
    }

    private static void onPart(IWorkbenchPartReference ref)
    {
        if (ref != null && EDITOR_ID.equals(ref.getId())
            && ref.getPart(false) instanceof DtGranularEditor<?> editor)
            ReadonlyStyleItemsContext.install(editor);
    }

    /**
     * Обёртка контекста редактирования редактора «Стиль»: штатный {@code BmStyleModel.createStyleItems}
     * читает элементы стиля конфигурации задачей {@code BmStyleModel$2} через {@code IModelApi.execute},
     * т.е. записывающей транзакцией. {@code Reactor} заносит её в историю даже пустой — и контекст
     * становится «изменённым» сразу при построении страницы «Стиль» (issue 530). Эту одну задачу
     * обёртка выполняет через {@link IEditingContext#executeReadonlyTask}, остальное передаёт как есть.
     *
     * <p>Ставится до построения страниц ({@code partOpened}): корневая модель страницы берёт
     * {@code modelApi} из {@code DtGranularEditor.getApiEditingContext()} один раз при создании.
     */
    private static final class ReadonlyStyleItemsContext implements InvocationHandler
    {
        private static final String EDITING_CONTEXT_FIELD = "editingContext"; //$NON-NLS-1$
        private static final String READ_STYLE_ITEMS_TASK =
            "com._1c.g5.v8.dt.internal.style.ui.aef.models.BmStyleModel$2"; //$NON-NLS-1$

        private final IEditingContext target;

        private ReadonlyStyleItemsContext(IEditingContext target)
        {
            this.target = target;
        }

        static void install(DtGranularEditor<?> editor)
        {
            if (!(Global.getField(editor, EDITING_CONTEXT_FIELD) instanceof IEditingContext current)
                || (Proxy.isProxyClass(current.getClass())
                    && Proxy.getInvocationHandler(current) instanceof ReadonlyStyleItemsContext))
                return;
            Object proxy = Proxy.newProxyInstance(IEditingContext.class.getClassLoader(),
                new Class<?>[] { IEditingContext.class }, new ReadonlyStyleItemsContext(current));
            Global.setField(editor, EDITING_CONTEXT_FIELD, proxy);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
        {
            if (method.getDeclaringClass() == Object.class)
            {
                switch (method.getName())
                {
                    case "equals": //$NON-NLS-1$
                        return proxy == args[0];
                    case "hashCode": //$NON-NLS-1$
                        return System.identityHashCode(proxy);
                    case "toString": //$NON-NLS-1$
                        return "ReadonlyStyleItemsContext[" + target + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                    default:
                        break;
                }
            }
            try
            {
                if ("execute".equals(method.getName()) && args != null && args.length == 2 //$NON-NLS-1$
                    && args[0] instanceof IBmTask<?> task
                    && READ_STYLE_ITEMS_TASK.equals(task.getClass().getName()))
                    return target.executeReadonlyTask(task, (IProgressMonitor)args[1]);
                return method.invoke(target, args);
            }
            catch (InvocationTargetException e)
            {
                throw e.getCause();
            }
        }
    }
}
