package tormozit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.debug.core.model.IDebugTargetsConfigure;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTargetManager;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugElement;
import com._1c.g5.v8.dt.debug.model.area.DebugAreaInfo;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;

/**
 * Штатное подменю «Настройка предметов отладки» (EDT {@code DebugTargetsConfigureAction},
 * команда {@code ConfigureDebugAreas}) по умолчанию доступно только при подключённом
 * отладчике — действие включает себя из активного debug-контекста, которого нет без сессии.
 *
 * <p>Хук делает это подменю доступным и без подключённого отладчика, если есть активный
 * проект: в делегат EDT подменяется {@link OfflineConfigure} (через рефлексию по private-полю
 * {@code configure}), а флаг действия принудительно включается. Данные предметов отладки
 * хранятся локально (project properties, {@code DebugAreasPersistenceHelper}), поэтому
 * офлайн-редактирование списка областей и типов автоподключения полностью функционально;
 * сервер нужен лишь для активации области, что офлайн — no-op с сохранением выбора.
 *
 * <p>Проект для диалога поставляется через proxy-target ({@link OfflineProxyHandler}):
 * штатный {@code run()} сам резолвит {@code IV8Project} и общие признаки по
 * {@code launch.getLaunchConfiguration().getAttribute(ATTR_PROJECT_NAME)}, поэтому диалог
 * открывается без деградации. При подключённом отладчике хук не вмешивается.
 *
 * <p>Охватывает оба вклада {@code DebugTargetsConfigureAction}: тулбарную pulldown-кнопку
 * (actionSet {@code debugActionSet1}, {@code WWinPluginAction}) и push-пункт в меню Debug-вьюхи
 * ({@code ViewPluginAction}, подменю «Платформа 1С:Предприятие»).
 */
public final class DebugAreasConfigureHook implements IStartup
{
    private static final String DEBUG_VIEW_ID = "org.eclipse.debug.ui.DebugView"; //$NON-NLS-1$
    private static final String BSL_SUBMENU_ID = "com._1c.g5.v8.dt.debug.ui.DebugView.bslSubmenu"; //$NON-NLS-1$
    private static final String ACTION_ID = "com._1c.g5.v8.dt.debug.ui.actions.ConfigureDebugAreas"; //$NON-NLS-1$
    private static final String TOOLBAR_ACTION_ID =
        "com._1c.g5.v8.dt.debug.ui.actions.toolbar.ConfigureDebugAreas"; //$NON-NLS-1$
    private static final String TOOLBAR_COMMAND_ID =
        "com._1c.g5.v8.dt.debug.ui.commands.ConfigureDebugAreas"; //$NON-NLS-1$
    private static final String PERSISTENCE_HELPER_CLASS =
        "com._1c.g5.v8.dt.internal.debug.core.model.DebugAreasPersistenceHelper"; //$NON-NLS-1$
    private static final String DEBUG_MODEL_ID = "com._1c.g5.v8.dt.debug"; //$NON-NLS-1$

    private static final Set<IViewPart> HOOKED_VIEWS = ConcurrentHashMap.newKeySet();
    private static final Set<IWorkbenchPage> PAGES_LISTENED = ConcurrentHashMap.newKeySet();
    private static final Set<IAction> WIRED_ACTIONS = ConcurrentHashMap.newKeySet();

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(DebugAreasConfigureHook::installOnWindows);
    }

    private static void installOnWindows()
    {
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            hookWindowToolbar(window);
            IWorkbenchPage page = window.getActivePage();
            if (page != null)
                hookPage(page);
        }
        PlatformUI.getWorkbench().addWindowListener(new org.eclipse.ui.IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                hookWindowToolbar(window);
                IWorkbenchPage page = window.getActivePage();
                if (page != null)
                    hookPage(page);
            }

            @Override
            public void windowActivated(IWorkbenchWindow window)
            {
                // actionSet/coolbar могут доформироваться позже — перепроверяем тулбар
                hookWindowToolbar(window);
            }

            @Override
            public void windowDeactivated(IWorkbenchWindow window)
            {
                // no-op
            }

            @Override
            public void windowClosed(IWorkbenchWindow window)
            {
                // no-op
            }
        });
        scheduleRetry(1500);
        scheduleRetry(8000);
    }

    private static void scheduleRetry(long delayMs)
    {
        Display.getDefault().timerExec((int) delayMs, DebugAreasConfigureHook::recheckAllPages);
    }

    private static void recheckAllPages()
    {
        try
        {
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            {
                hookWindowToolbar(window);
                IWorkbenchPage page = window.getActivePage();
                if (page != null)
                    hookPage(page);
            }
        }
        catch (Exception e)
        {
            // no-op
        }
    }

    /**
     * Тулбарная кнопка-pulldown «Настройка предметов отладки» — отдельный вклад
     * {@code DebugTargetsConfigureAction} (actionSet {@code debugActionSet1}), обёрнутый
     * в {@code WWinPluginAction} (тоже extends {@code PluginAction}). Находим её и вайрим
     * так же, как view-menu push.
     */
    private static void hookWindowToolbar(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        try
        {
            IAction action = findToolbarAction(window);
            if (action == null || !WIRED_ACTIONS.add(action))
                return;
            Object delegate = extractDelegate(action);
            if (delegate == null)
                return;
            ActionHandle handle = new ActionHandle(window.getActivePage(), null, action, delegate);
            wire(handle);
        }
        catch (Exception e)
        {
            // no-op
        }
    }

    private static IAction findToolbarAction(IWorkbenchWindow window)
    {
        // Основной путь: 3.x-compat actionSets живы и в e4. Цепочка
        // WorkbenchWindow.getActionPresentation() -> ActionPresentation.getActionSets() ->
        // PluginActionSet[].getPluginActions() -> WWinPluginAction[].
        IAction viaSets = findToolbarActionViaActionSets(window);
        if (viaSets != null)
            return viaSets;
        // Фолбэк: command-service -> ActionHandler (в e4 завёрнут в прокси, обычно null).
        return findToolbarActionByCommand();
    }

    private static IAction findToolbarActionViaActionSets(IWorkbenchWindow window)
    {
        try
        {
            Method getAP = lookup(window.getClass(), "getActionPresentation"); //$NON-NLS-1$
            if (getAP == null)
                return null;
            getAP.setAccessible(true);
            Object ap = getAP.invoke(window);
            if (ap == null)
                return null;
            Method getSets = lookup(ap.getClass(), "getActionSets"); //$NON-NLS-1$
            if (getSets == null)
                return null;
            getSets.setAccessible(true);
            Object setsObj = getSets.invoke(ap);
            if (!(setsObj instanceof Object[] sets))
                return null;
            for (Object set : sets)
            {
                if (set == null)
                    continue;
                Method getActions = lookup(set.getClass(), "getPluginActions"); //$NON-NLS-1$
                if (getActions == null)
                    continue;
                getActions.setAccessible(true);
                Object actionsObj = getActions.invoke(set);
                if (actionsObj instanceof IAction[] arr)
                {
                    for (IAction a : arr)
                    {
                        if (a != null && TOOLBAR_ACTION_ID.equals(a.getId()))
                            return a;
                    }
                }
            }
            return null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static IAction findToolbarActionByCommand()
    {
        try
        {
            Object cs = PlatformUI.getWorkbench().getService(org.eclipse.ui.commands.ICommandService.class);
            if (!(cs instanceof org.eclipse.ui.commands.ICommandService cmds))
                return null;
            org.eclipse.core.commands.Command cmd = cmds.getCommand(TOOLBAR_COMMAND_ID);
            if (cmd == null || !cmd.isDefined())
                return null;
            return actionFromHandler(cmd.getHandler());
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * {@code Command.getHandler()} для actionSet-команды отдаёт e4-прокси
     * {@code WorkbenchHandlerServiceHandler} (extends {@code HandlerServiceHandler}), а не сам
     * {@code ActionHandler}. {@code HandlerServiceHandler.lookUpHandlerWithState()} резолвит
     * текущий активный handler из e4-контекста — для активного actionSet это и есть
     * {@code ActionHandler}, обёртка над искомым {@code WWinPluginAction}.
     */
    private static IAction actionFromHandler(Object handler)
    {
        if (handler instanceof org.eclipse.jface.commands.ActionHandler ah)
            return ah.getAction();
        if (handler == null)
            return null;
        Method m = lookup(handler.getClass(), "lookUpHandlerWithState"); //$NON-NLS-1$
        if (m != null)
        {
            try
            {
                m.setAccessible(true);
                Object real = m.invoke(handler);
                if (real instanceof org.eclipse.jface.commands.ActionHandler ah2)
                    return ah2.getAction();
            }
            catch (Exception e)
            {
                // no-op
            }
        }
        return null;
    }

    private static Method lookup(Class<?> cls, String methodName)
    {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass())
        {
            try
            {
                return c.getDeclaredMethod(methodName);
            }
            catch (NoSuchMethodException ignored)
            {
                // continue up the hierarchy
            }
        }
        return null;
    }

    private static void hookPage(IWorkbenchPage page)
    {
        if (page == null)
            return;
        try
        {
            IViewPart view = page.findView(DEBUG_VIEW_ID);
            if (view != null && HOOKED_VIEWS.add(view))
                attachToView(view);
            if (PAGES_LISTENED.add(page))
            {
                page.addPartListener(new IPartListener2()
                {
                    @Override
                    public void partOpened(IWorkbenchPartReference ref)
                    {
                        IViewPart v = resolveView(ref);
                        if (v != null && HOOKED_VIEWS.add(v))
                            attachToView(v);
                    }
                });
            }
        }
        catch (Exception e)
        {
            // no-op
        }
    }

    private static IViewPart resolveView(IWorkbenchPartReference ref)
    {
        try
        {
            if (ref == null)
                return null;
            String id = ref.getId();
            if (!DEBUG_VIEW_ID.equals(id))
                return null;
            if (ref.getPart(false) instanceof IViewPart v)
                return v;
        }
        catch (Exception e)
        {
            // no-op
        }
        return null;
    }

    private static void attachToView(IViewPart view)
    {
        try
        {
            IMenuManager top = view.getViewSite().getActionBars().getMenuManager();
            if (top == null)
                return;
            ActionHandle handle = findActionHandle(top, view);
            if (handle == null)
            {
                top.addMenuListener(new IMenuListener()
                {
                    private boolean resolved;
                    @Override
                    public void menuAboutToShow(IMenuManager manager)
                    {
                        if (resolved)
                            return;
                        ActionHandle h = findActionHandle(manager, view);
                        if (h != null)
                        {
                            resolved = true;
                            wire(h);
                        }
                    }
                });
                return;
            }
            wire(handle);
        }
        catch (Exception e)
        {
            // no-op
        }
    }

    private static void wire(ActionHandle handle)
    {
        try
        {
            // EDT-делегат в selectionChanged/debugContextChanged без отладчика сбрасывает
            // configure=null и вызывает action.setEnabled(false). Реактивно возвращаем:
            // на каждое ENABLED->false форсим обратно (apply сам проверяет connected/project).
            handle.action.addPropertyChangeListener(event -> {
                if (IAction.ENABLED.equals(event.getProperty()) && !handle.action.isEnabled())
                    apply(handle);
            });
            if (handle.view != null)
            {
                handle.view.getViewSite().getActionBars().getMenuManager().addMenuListener(manager -> apply(handle));
                try
                {
                    DebugUITools.addPartDebugContextListener(handle.view.getSite(), event -> apply(handle));
                }
                catch (Exception e)
                {
                    // no-op
                }
            }
            // для тулбарной кнопки реактивное восстановление — по property-listener:
            // EDT-делегат сам зовёт setEnabled из selectionChanged/debugContextChanged,
            // мы это перехватываем и возвращаем configure+enabled.
            apply(handle);
        }
        catch (Exception e)
        {
            // no-op
        }
    }

    private static void apply(ActionHandle handle)
    {
        try
        {
            if (isConnected())
                return;
            IProject project = Global.getActiveProject(handle.page, false);
            if (project == null)
                return;
            OfflineConfigure configure = new OfflineConfigure(project);
            handle.action.setEnabled(true);
            Global.setFieldForce(handle.delegate, "configure", configure); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            // no-op
        }
    }

    private static boolean isConnected()
    {
        try
        {
            IRuntimeDebugClientTargetManager manager = Global.getOsgiService(IRuntimeDebugClientTargetManager.class);
            if (manager == null)
                return false;
            Collection<IRuntimeDebugClientTarget> targets = manager.listDebugTargets();
            return targets != null && !targets.isEmpty();
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static ActionHandle findActionHandle(IMenuManager top, IViewPart view)
    {
        IContributionItem sub = findItem(top, BSL_SUBMENU_ID);
        if (!(sub instanceof IMenuManager submenu))
            return null;
        IContributionItem item = findItem(submenu, ACTION_ID);
        if (!(item instanceof ActionContributionItem aci) || aci.getAction() == null)
            return null;
        IAction action = aci.getAction();
        Object delegate = extractDelegate(action);
        if (delegate == null)
            return null;
        return new ActionHandle(view.getSite().getPage(), view, action, delegate);
    }

    private static IContributionItem findItem(IMenuManager manager, String id)
    {
        if (manager == null || id == null)
            return null;
        for (IContributionItem item : manager.getItems())
        {
            if (id.equals(item.getId()))
                return item;
            if (item instanceof IMenuManager sub && sub.getId() != null && !id.equals(sub.getId()))
            {
                IContributionItem deeper = findItem(sub, id);
                if (deeper != null)
                    return deeper;
            }
        }
        return null;
    }

    /**
     * Делегат {@code DebugTargetsConfigureAction} спрятан внутри Eclipse-обёртки
     * {@code ViewPluginAction} (иерархия ViewPluginAction &rarr; PartPluginAction &rarr;
     * {@code PluginAction}). {@code PluginAction.getDelegate()} и {@code createDelegate()} —
     * protected, поэтому {@code Class.getMethod} (только public) их не находит; берём
     * {@code getDeclaredMethod} по суперклассу {@code PluginAction} с {@code setAccessible}.
     * Если делегат ещё не создан (lazy), принудительно вызываем {@code createDelegate()}.
     */
    private static Object extractDelegate(IAction action)
    {
        if (action == null)
            return null;
        try
        {
            Class<?> pluginActionClass = pluginActionClassOf(action);
            if (pluginActionClass == null)
                return null;
            Method getDelegate = pluginActionClass.getDeclaredMethod("getDelegate"); //$NON-NLS-1$
            getDelegate.setAccessible(true);
            Object delegate = getDelegate.invoke(action);
            if (delegate == null)
            {
                Method createDelegate = pluginActionClass.getDeclaredMethod("createDelegate"); //$NON-NLS-1$
                createDelegate.setAccessible(true);
                createDelegate.invoke(action);
                delegate = getDelegate.invoke(action);
            }
            return delegate;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static Class<?> pluginActionClassOf(Object action)
    {
        Class<?> c = action.getClass();
        while (c != null && c != Object.class)
        {
            if (c.getName().equals("org.eclipse.ui.internal.PluginAction")) //$NON-NLS-1$
                return c;
            c = c.getSuperclass();
        }
        return null;
    }

    private record ActionHandle(IWorkbenchPage page, IViewPart view, IAction action, Object delegate) {}

    /**
     * Offline-реализация {@link IDebugTargetsConfigure} + {@link IRuntimeDebugElement}:
     * данные предметов отладки читаются/пишутся в локальное project-хранилище через
     * {@code DebugAreasPersistenceHelper}, активация области — no-op (сохраняется только выбор),
     * {@code getDebugTarget()} возвращает proxy-target, через который штатный {@code run()}
     * резолвит проект и общие признаки.
     */
    private static final class OfflineConfigure implements IDebugTargetsConfigure, IRuntimeDebugElement
    {
        private static volatile Object sharedHelper;
        private static volatile boolean helperInitialized;

        private final IProject project;
        private final Object helper;
        private final IRuntimeDebugClientTarget fakeTarget;
        private final ILaunch fakeLaunch;

        OfflineConfigure(IProject project)
        {
            this.project = project;
            this.helper = persistenceHelper();
            OfflineProxyHandler handler = new OfflineProxyHandler(project.getName());
            this.fakeTarget = handler.targetProxy();
            this.fakeLaunch = handler.launchProxy();
        }

        private static Object persistenceHelper()
        {
            if (helperInitialized)
                return sharedHelper;
            synchronized (OfflineConfigure.class)
            {
                if (!helperInitialized)
                {
                    sharedHelper = newPersistenceHelper();
                    helperInitialized = true;
                }
                return sharedHelper;
            }
        }

        @Override
        public boolean canConfigure()
        {
            return true;
        }

        @Override
        public String getDebugAreaName()
        {
            try
            {
                Object res = Global.invoke(helper, "loadCurrentDebugArea", project); //$NON-NLS-1$
                return res instanceof String s ? s : null;
            }
            catch (Exception e)
            {
                return null;
            }
        }

        @Override
        public void configureDebugArea(DebugAreaInfo info) throws org.eclipse.debug.core.DebugException
        {
            if (info == null)
                return;
            try
            {
                Global.invokeVoid(helper, "saveCurrentDebugArea", project, info.getName()); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                // no-op
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<DebugAreaInfo> getDebugAreas() throws org.eclipse.debug.core.DebugException
        {
            try
            {
                Object res = Global.invoke(helper, "loadDebugAreas", project); //$NON-NLS-1$
                return res instanceof List list ? (List<DebugAreaInfo>) list : Collections.emptyList();
            }
            catch (Exception e)
            {
                return Collections.emptyList();
            }
        }

        @Override
        public void setDebugAreas(List<DebugAreaInfo> areas) throws org.eclipse.debug.core.DebugException
        {
            try
            {
                Global.invokeVoid(helper, "saveDebugAreas", project, areas != null ? areas : Collections.emptyList()); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                // no-op
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<DebugTargetType> getAutoconnectDebugTargets() throws org.eclipse.debug.core.DebugException
        {
            try
            {
                Object res = Global.invoke(helper, "loadAutoattachTargets", project); //$NON-NLS-1$
                return res instanceof List list ? (List<DebugTargetType>) list : Collections.emptyList();
            }
            catch (Exception e)
            {
                return Collections.emptyList();
            }
        }

        @Override
        public void setAutoconnectDebugTargets(List<DebugTargetType> targets) throws org.eclipse.debug.core.DebugException
        {
            try
            {
                Global.invokeVoid(helper, "saveAutoattachTargets", project, targets != null ? targets : Collections.emptyList()); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                // no-op
            }
        }

        @Override
        public IRuntimeDebugClientTarget getDebugTarget()
        {
            return fakeTarget;
        }

        @Override
        public ILaunch getLaunch()
        {
            return fakeLaunch;
        }

        @Override
        public String getModelIdentifier()
        {
            return DEBUG_MODEL_ID;
        }

        @Override
        public <T> T getAdapter(Class<T> adapter)
        {
            return null;
        }

        private static Object newPersistenceHelper()
        {
            try
            {
                ClassLoader cl = IRuntimeDebugClientTarget.class.getClassLoader();
                Class<?> clazz = Class.forName(PERSISTENCE_HELPER_CLASS, true, cl);
                Object helper = clazz.getDeclaredConstructor().newInstance();
                // no-arg ctor не внедряет IDebugMonitoringManager (его ставит Guice у EDT).
                // Без него saveAutoattachTargets падает NPE на autoattachDebugTargetsAdded
                // ДО записи в файл. Внедряем no-op proxy — офлайн нотификации живой сессии не нужны.
                injectNoOpMonitoringManager(helper, cl);
                return helper;
            }
            catch (Exception e)
            {
                return null;
            }
        }

        private static void injectNoOpMonitoringManager(Object helper, ClassLoader cl)
        {
            try
            {
                Class<?> iface =
                    Class.forName("com._1c.g5.v8.dt.debug.core.model.IDebugMonitoringManager", true, cl); //$NON-NLS-1$
                Object noop = Proxy.newProxyInstance(cl, new Class<?>[]{ iface }, (proxy, method, args) ->
                    OfflineProxyHandler.defaultReturn(method.getReturnType()));
                Global.setFieldForce(helper, "monitoringManager", noop); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                // no-op
            }
        }
    }

    /**
     * Один {@link InvocationHandler} на три связанных proxy: target ({@link IRuntimeDebugClientTarget}),
     * launch ({@link ILaunch}), launch-конфигурацию ({@link ILaunchConfiguration}). Штатный
     * {@code getV8Project()} делает {@code target.getLaunch().getLaunchConfiguration().getAttribute(...)},
     * поэтому {@code getAttribute} отдаёт имя активного проекта — и больше ничего из огромной
     * поверхности {@code IDebugTarget} реально не используется.
     */
    private static final class OfflineProxyHandler implements InvocationHandler
    {
        private final String projectName;
        private final IRuntimeDebugClientTarget targetProxy;
        private final ILaunch launchProxy;
        private final ILaunchConfiguration launchConfigProxy;

        OfflineProxyHandler(String projectName)
        {
            this.projectName = projectName;
            ClassLoader cl = IRuntimeDebugClientTarget.class.getClassLoader();
            this.launchConfigProxy =
                (ILaunchConfiguration) Proxy.newProxyInstance(cl, new Class<?>[]{ ILaunchConfiguration.class }, this);
            this.launchProxy = (ILaunch) Proxy.newProxyInstance(cl, new Class<?>[]{ ILaunch.class }, this);
            this.targetProxy =
                (IRuntimeDebugClientTarget) Proxy.newProxyInstance(cl, new Class<?>[]{ IRuntimeDebugClientTarget.class }, this);
        }

        IRuntimeDebugClientTarget targetProxy()
        {
            return targetProxy;
        }

        ILaunch launchProxy()
        {
            return launchProxy;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
        {
            String name = method.getName();
            int argc = args == null ? 0 : args.length;
            switch (name)
            {
                case "getLaunch": //$NON-NLS-1$
                    return launchProxy;
                case "getLaunchConfiguration": //$NON-NLS-1$
                    return launchConfigProxy;
                case "getAttribute": //$NON-NLS-1$
                    // ILaunchConfiguration.getAttribute(String, default) — у перегрузок return-тип
                    // совпадает с типом default-аргумента; нас интересует только String-перегрузка
                    // (ATTR_PROJECT_NAME), для остальных возвращаем переданный default как есть.
                    if (argc == 2 && args[0] instanceof String
                        && method.getReturnType() == String.class)
                        return projectName;
                    break;
                case "getDebugTarget": //$NON-NLS-1$
                    return targetProxy;
                case "getModelIdentifier": //$NON-NLS-1$
                    return DEBUG_MODEL_ID;
                case "getName": //$NON-NLS-1$
                    return "Offline[" + projectName + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                case "hashCode": //$NON-NLS-1$
                    return Integer.valueOf(System.identityHashCode(proxy));
                case "equals": //$NON-NLS-1$
                    return Boolean.valueOf(proxy == (args == null ? null : args[0]));
                case "toString": //$NON-NLS-1$
                    return "OfflineDebugTarget[" + projectName + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                default:
                    break;
            }
            return defaultReturn(method.getReturnType());
        }

        private static Object defaultReturn(Class<?> type)
        {
            if (type == boolean.class)
                return Boolean.FALSE;
            if (type == int.class)
                return Integer.valueOf(0);
            if (type == long.class)
                return Long.valueOf(0);
            if (type == short.class)
                return Short.valueOf((short) 0);
            if (type == byte.class)
                return Byte.valueOf((byte) 0);
            if (type == char.class)
                return Character.valueOf((char) 0);
            if (type == float.class)
                return Float.valueOf(0f);
            if (type == double.class)
                return Double.valueOf(0d);
            return null;
        }
    }
}
