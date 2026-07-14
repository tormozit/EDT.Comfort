package tormozit;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.xtext.ide.server.ProjectManager;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.wiring.IManagedService;

/**
 * Утилиты Java-рефлексии и общего назначения — единый источник правды для всего плагина.
 */
public final class Global
{
    /** ID навигатора EDT. */
    static final String NAVIGATOR_VIEW_ID  = "com._1c.g5.v8.dt.ui2.navigator";  //$NON-NLS-1$

    /** ID редактора сравнения конфигураций EDT. */
    static final String COMPARE_EDITOR_ID  = "com._1c.g5.v8.dt.compare.ui.editor"; //$NON-NLS-1$
   
    /** ID универсального редактора свойств объекта EDT. */
    static final String PROPERTIES_SHEET_ID  = "org.eclipse.ui.views.properties.PropertySheet"; //$NON-NLS-1$

    /** ID панели истории коммитов EGit. */
    static final String TEAM_HISTORY_VIEW_ID = "org.eclipse.team.ui.GenericHistoryView"; //$NON-NLS-1$

    private Global() {}

    /**
     * Читает значение поля {@code fieldName} из объекта {@code obj},
     * поднимаясь по иерархии суперклассов.
     *
     * @return значение поля, или {@code null} при любой ошибке / отсутствии поля
     */
    public static Object getField(Object obj, String fieldName)
    {
        if (obj == null || fieldName == null) return null;
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass())
        {
            try
            {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            }
            catch (NoSuchFieldException ignored) {}
            catch (Exception ignored)            { return null; }
        }
        return null;
    }
    
    /**
     * Записывает значение {@code value} в поле {@code fieldName} объекта {@code obj},
     * поднимаясь по иерархии суперклассов.
     */
    public static void setField(Object obj, String fieldName, Object value)
    {
        setFieldForce(obj, fieldName, value);
    }

    /**
     * Запись в поле с обходом проверки типа (для internal-полей EDT с узким declared type).
     *
     * @return {@code true}, если после записи {@link #getField} возвращает {@code value}
     */
    public static boolean setFieldForce(Object obj, String fieldName, Object value)
    {
        if (obj == null || fieldName == null)
            return false;
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass())
        {
            try
            {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                try
                {
                    f.set(obj, value);
                }
                catch (IllegalArgumentException typeMismatch)
                {
                    if (!setFieldViaUnsafe(obj, f, value))
                        return false;
                }
                return f.get(obj) == value;
            }
            catch (NoSuchFieldException ignored) {}
            catch (Exception ignored)
            {
                return false;
            }
        }
        return false;
    }

    private static boolean setFieldViaUnsafe(Object obj, Field f, Object value)
    {
        try
        {
            Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); //$NON-NLS-1$ //$NON-NLS-2$
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            java.lang.reflect.Method offsetMethod =
                unsafe.getClass().getMethod("objectFieldOffset", Field.class); //$NON-NLS-1$
            java.lang.reflect.Method putMethod =
                unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class); //$NON-NLS-1$
            long offset = ((Long) offsetMethod.invoke(unsafe, f)).longValue();
            putMethod.invoke(unsafe, obj, Long.valueOf(offset), value);
            return true;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }
    
    /**
     * Вызывает метод {@code methodName} с переданными аргументами на объекте {@code obj}.
     * Метод ищется по имени и количеству аргументов по всей иерархии классов,
     * включая private и protected.
     *
     * @return результат вызова, или {@code null} при любой ошибке
     */
    public static Object invoke(Object obj, String methodName, Object... args)
    {
        if (obj == null || methodName == null) return null;
        int argc = args == null ? 0 : args.length;

        boolean isStatic = obj instanceof Class<?>;
        Class<?> startClass = isStatic ? (Class<?>) obj : obj.getClass();
        Object targetInstance = isStatic ? null : obj;

        for (Class<?> c = startClass; c != null; c = c.getSuperclass())
        {
            for (java.lang.reflect.Method m : c.getDeclaredMethods())
            {
                if (m.getName().equals(methodName) && m.getParameterCount() == argc)
                {
                    try
                    {
                        m.setAccessible(true);
                        return m.invoke(targetInstance, args);
                    }
                    catch (java.lang.reflect.InvocationTargetException e)
                    {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                        throw new RuntimeException(cause);
                    }
                    catch (Exception ignored) { return null; }
                }
            }
        }
        return null;
    }

    /** Как {@link #invoke}, но {@code true} если метод найден и вызван (в т.ч. {@code void}). */
    public static boolean invokeVoid(Object obj, String methodName, Object... args)
    {
        if (obj == null || methodName == null) return false;
        int argc = args == null ? 0 : args.length;
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass())
        {
            for (java.lang.reflect.Method m : c.getDeclaredMethods())
            {
                if (m.getName().equals(methodName) && m.getParameterCount() == argc)
                {
                    try
                    {
                        m.setAccessible(true);
                        m.invoke(obj, args);
                        return true;
                    }
                    catch (java.lang.reflect.InvocationTargetException e)
                    {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                        throw new RuntimeException(cause);
                    }
                    catch (Exception ignored) { return false; }
                }
            }
        }
        return false;
    }

    /**
     * Вызывает публичный метод {@code methodName} без аргументов на объекте {@code obj}.
     *
     * @return результат вызова, или {@code null} при любой ошибке / отсутствии метода
     */
    public static Object call(Object obj, String methodName)
    {
        if (obj == null || methodName == null) return null;
        try { return obj.getClass().getMethod(methodName).invoke(obj); }
        catch (Exception ignored) { return null; }
    }

    private static final String LIGHT_CONTROL_LISTENER_CLASS = "com._1c.g5.lwt.ILightControlListener"; //$NON-NLS-1$

    /**
     * Вешает на {@code lightControl} (произвольный {@code com._1c.g5.lwt} light-контрол — без
     * compile-зависимости от этого бандла, тем же принципом, что и весь остальной плагин, см.
     * {@link #getField}/{@link #invoke}) слушатель {@code ILightControlListener} через
     * {@link Proxy} и вызывает {@code onFocusLost} при {@code event.type == SWT.FocusOut}.
     *
     * <p>Proxy пересылает handler'у и служебные {@code Object}-методы ({@code hashCode}/
     * {@code equals}/{@code toString}), которые может вызвать инфраструктура EDT при добавлении/
     * поиске слушателя в коллекции; для примитивных возвращаемых типов {@code null} недопустим
     * (NPE на автоунбоксинге), поэтому они обрабатываются явно.
     *
     * @return {@code true}, если слушатель успешно добавлен через {@code addControlListener}
     */
    public static boolean installLightControlFocusOutListener(Object lightControl, Runnable onFocusLost)
    {
        return installLightControlListener(lightControl, onFocusLost, null);
    }

    /**
     * Как {@link #installLightControlFocusOutListener}, но дополнительно вызывает {@code onEnter}
     * при нажатии Enter ({@code event.type == SWT.KeyDown && event.character == SWT.CR}) — фокус
     * при этом не теряется, обработчик просто запускается по факту подтверждения ввода.
     * Используется точечно (сейчас — только в панели «Свойства»,
     * см. {@link PropertyNameIdentifierHook}), не в мастерах «Новый ...».
     *
     * @param onFocusLost вызывается на {@code SWT.FocusOut}; если {@code null} — этот случай игнорируется
     * @param onEnter     вызывается на Enter; если {@code null} — этот случай игнорируется
     * @return {@code true}, если слушатель успешно добавлен через {@code addControlListener}
     */
    public static boolean installLightControlListener(Object lightControl, Runnable onFocusLost, Runnable onEnter)
    {
        if (lightControl == null || (onFocusLost == null && onEnter == null))
            return false;
        try
        {
            ClassLoader lwtLoader = lightControl.getClass().getClassLoader();
            Class<?> listenerClass = Class.forName(LIGHT_CONTROL_LISTENER_CLASS, true, lwtLoader);

            InvocationHandler handler = (proxy, method, args) ->
            {
                String name = method.getName();
                if ("eventReceived".equals(name) && args != null && args.length == 2 //$NON-NLS-1$
                    && args[1] instanceof Event event)
                {
                    if (event.type == SWT.FocusOut && onFocusLost != null)
                        onFocusLost.run();
                    else if (event.type == SWT.KeyDown && event.character == SWT.CR && onEnter != null)
                        onEnter.run();
                    return null;
                }
                if ("hashCode".equals(name) && (args == null || args.length == 0)) //$NON-NLS-1$
                    return System.identityHashCode(proxy);
                if ("equals".equals(name) && args != null && args.length == 1) //$NON-NLS-1$
                    return proxy == args[0];
                if ("toString".equals(name) && (args == null || args.length == 0)) //$NON-NLS-1$
                    return proxy.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(proxy)); //$NON-NLS-1$
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class)
                    return Boolean.FALSE;
                if (returnType == int.class || returnType == long.class || returnType == short.class
                    || returnType == byte.class)
                    return 0;
                if (returnType == char.class)
                    return '\0';
                if (returnType == float.class || returnType == double.class)
                    return 0.0;
                return null;
            };

            Object listenerProxy = Proxy.newProxyInstance(lwtLoader, new Class<?>[] { listenerClass }, handler);
            return invokeVoid(lightControl, "addControlListener", listenerProxy); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            logError("Global", "installLightControlListener", e); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    /**
     * Создаёт объект класса {@code targetClass} конструктором, подобранным по количеству
     * аргументов (как {@link #invoke} — без сверки типов параметров), включая private.
     *
     * @return новый объект, или {@code null} при любой ошибке / если подходящий конструктор не найден
     */
    public static Object newInstance(Class<?> targetClass, Object... args)
    {
        if (targetClass == null) return null;
        int argc = args == null ? 0 : args.length;
        for (java.lang.reflect.Constructor<?> ctor : targetClass.getDeclaredConstructors())
        {
            if (ctor.getParameterCount() == argc)
            {
                try
                {
                    ctor.setAccessible(true);
                    return ctor.newInstance(args);
                }
                catch (Exception ignored) { /* пробуем следующий конструктор с тем же argc */ }
            }
        }
        return null;
    }

    /** Как {@link #newInstance(Class, Object...)}, но класс ищется по имени через {@code loader}. */
    public static Object newInstance(String className, ClassLoader loader, Object... args)
    {
        try
        {
            return newInstance(Class.forName(className, true, loader), args);
        }
        catch (Exception e)
        {
            logError("Global", "newInstance:" + className, e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /**
     * Как {@link #installLightControlListener(Object, Runnable, Runnable)}, но пересылает
     * {@code onEvent} все события {@code ILightControlListener.eventReceived} без фильтрации
     * по типу (используется, когда заранее не известно, какой именно тип события нужен —
     * например, для перехвата ввода в {@code LightCombo}, где фильтрация списка происходит
     * прямо на {@code SWT.KeyDown}, а не на {@code SWT.Modify}).
     *
     * @return {@code true}, если слушатель успешно добавлен через {@code addControlListener}
     */
    public static boolean installLightControlListener(Object lightControl, Consumer<Event> onEvent)
    {
        if (lightControl == null || onEvent == null)
            return false;
        try
        {
            ClassLoader lwtLoader = lightControl.getClass().getClassLoader();
            Class<?> listenerClass = Class.forName(LIGHT_CONTROL_LISTENER_CLASS, true, lwtLoader);

            InvocationHandler handler = (proxy, method, args) ->
            {
                String name = method.getName();
                if ("eventReceived".equals(name) && args != null && args.length == 2 //$NON-NLS-1$
                    && args[1] instanceof Event event)
                {
                    onEvent.accept(event);
                    return null;
                }
                if ("hashCode".equals(name) && (args == null || args.length == 0)) //$NON-NLS-1$
                    return System.identityHashCode(proxy);
                if ("equals".equals(name) && args != null && args.length == 1) //$NON-NLS-1$
                    return proxy == args[0];
                if ("toString".equals(name) && (args == null || args.length == 0)) //$NON-NLS-1$
                    return proxy.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(proxy)); //$NON-NLS-1$
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class)
                    return Boolean.FALSE;
                if (returnType == int.class || returnType == long.class || returnType == short.class
                    || returnType == byte.class)
                    return 0;
                if (returnType == char.class)
                    return '\0';
                if (returnType == float.class || returnType == double.class)
                    return 0.0;
                return null;
            };

            Object listenerProxy = Proxy.newProxyInstance(lwtLoader, new Class<?>[] { listenerClass }, handler);
            invokeVoid(lightControl, "addControlListener", listenerProxy); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            logError("Global", "installLightControlListener", e); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    // =========================================================================
    // OSGi / сервисы
    // =========================================================================

    public static BundleContext ourContext()
    {
        Bundle b = FrameworkUtil.getBundle(Global.class);
        return b != null ? b.getBundleContext() : null;
    }

    public static IManagedService getServiceByClass(Class<?> clazz)
    {
        BundleContext ctx = Global.ourContext();
        return (IManagedService) ctx.getService(ctx.getServiceReference(clazz));
    }

    @SuppressWarnings("unchecked")
    public static <T> T getOsgiService(Class<T> clazz)
    {
        BundleContext ctx = ourContext();
        if (ctx == null || clazz == null)
            return null;
        org.osgi.framework.ServiceReference<T> ref = ctx.getServiceReference(clazz);
        return ref != null ? ctx.getService(ref) : null;
    }

    // =========================================================================
    // Проекты
    // =========================================================================

    /** Извлекает {@link IProject} из панели истории коммитов EGit (GenericHistoryView). */
    private static IProject getProjectFromHistoryView(IWorkbenchPart part)
    {
        try
        {
            Object page = call(part, "getHistoryPage");
            if (page == null)
                return null;
            Object repo = getField(page, "currentRepo");
            if (!(repo instanceof org.eclipse.jgit.lib.Repository))
                return null;
            File workTree = ((org.eclipse.jgit.lib.Repository) repo).getWorkTree();
            if (workTree != null)
            {
                String repoAbs = workTree.getAbsolutePath().replace('\\', '/');
                for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects())
                {
                    if (!p.isOpen())
                        continue;
                    String projAbs = p.getLocation().toFile().getAbsolutePath().replace('\\', '/');
                    if (repoAbs.equals(projAbs) || repoAbs.startsWith(projAbs + "/"))
                        return p;
                }
            }
        }
        catch (Exception e)
        {
            log("getProjectFromHistoryView error: " + e);
        }
        return null;
    }

    /**
     * Возвращает активный {@link IProject} из нескольких источников (в порядке приоритета):
     * <ol>
     *   <li>Файл, открытый в активном редакторе ({@code IEditorInput → IFile → IProject}).</li>
     *   <li>Редактор сравнения конфигураций EDT — через сессию сравнения
     *       ({@link CompareConfigOpenObjectHandler#getProjectFromEditor}).</li>

     *   <li>Выделенный элемент в навигаторе EDT.</li>
     * </ol>
     *
     * @param page рабочая страница; если {@code null} — берётся активная страница IDE
     * @return проект или {@code null}
     */
    public static IProject getActiveProject(IWorkbenchPage page, boolean showMessage)
    {
        if (page == null) 
            page = getActivePage();
        if (page == null) 
            return null;
        IEditorPart editor = page.getActiveEditor();
        if (editor != null)
        {
            IFile file = editorToFile(editor);
            if (file != null)
            {
                return file.getProject();
            }
        }

        // 2. Из редактора сравнения конфигураций
        if (editor != null && COMPARE_EDITOR_ID.equals(editor.getSite().getId()))
        {
            IProject p = CompareConfigOpenObjectHandler.getProjectFromEditor(editor);
            if (p != null) 
                return p;
        }

        // 3. Из навигатора EDT
        IProject navProject = getProjectFromNavigator();
        if (navProject != null) 
            return navProject;

        if (navProject == null && showMessage)
            ToastNotification.show("Проект", "Отсутствует активный проект");
        return null;
    }

    /**
     * Активный проект с учётом {@linkplain org.eclipse.ui.handlers.HandlerUtil#getActivePart
     * активной части}: при фокусе в навигаторе — из его выделения, при фокусе в редакторе —
     * из редактора; иначе — {@link #getActiveProject(IWorkbenchPage, boolean)}.
     */
    public static IProject getActiveProject(IWorkbenchPart part, boolean showMessage)
    {
        IWorkbenchPage page = part != null ? part.getSite().getPage() : getActivePage();
        if (part != null && page != null)
        {
            IViewPart navigator = page.findView(NAVIGATOR_VIEW_ID);
            if (part == navigator && navigator instanceof CommonNavigator)
            {
                IProject navProject = getProjectFromNavigator((CommonNavigator) navigator);
                if (navProject != null)
                    return navProject;
                if (showMessage)
                    ToastNotification.show("Проект", "Отсутствует активный проект"); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }
            if (part instanceof IEditorPart)
            {
                IEditorPart editor = (IEditorPart) part;
                IFile file = editorToFile(editor);
                if (file != null)
                    return file.getProject();
                if (COMPARE_EDITOR_ID.equals(part.getSite().getId()))
                {
                    IProject p = CompareConfigOpenObjectHandler.getProjectFromEditor(editor);
                    if (p != null)
                        return p;
                }
            }
            if (TEAM_HISTORY_VIEW_ID.equals(part.getSite().getId()))
            {
                IProject p = getProjectFromHistoryView(part);
                if (p != null)
                    return p;
            }
        }
        return getActiveProject(page, showMessage);
    }

    /** Навигатор EDT или Project Explorer. */
    public static boolean isNavigatorPart(IWorkbenchPart part)
    {
        if (!(part instanceof IViewPart))
            return false;
        IViewPart view = (IViewPart) part;
        if (view.getViewSite() == null)
            return false;
        String id = view.getViewSite().getId();
        return NAVIGATOR_VIEW_ID.equals(id)
            || "org.eclipse.ui.navigator.ProjectExplorer".equals(id); //$NON-NLS-1$
    }

    /** Возвращает {@link IDtProject} из поля {@code project} произвольного редактора. */
    public static IDtProject getProjectFromEditor(Object editorPart)
    {
        Object project = Global.getField(editorPart, "project"); //$NON-NLS-1$
        if (project instanceof IDtProject) return (IDtProject) project;
        Object context = Global.getField(editorPart, "context"); //$NON-NLS-1$
        if (context != null)
        {
            Object p = Global.getField(context, "project"); //$NON-NLS-1$
            if (p instanceof IDtProject) return (IDtProject) p;
        }
        return null;
    }

    /**
     * {@link IDtProject} для BSL-редактора: поле редактора / context, затем IFile → workspace.
     */
    public static IDtProject getDtProjectFromBslEditor(BslXtextEditor editor)
    {
        if (editor == null)
            return null;

        IDtProject fromField = getProjectFromEditor(editor);
        if (fromField != null)
            return fromField;

        try
        {
            IEditorInput input = editor.getEditorInput();
            if (input == null)
                return null;

            IFile file = input.getAdapter(IFile.class);
            if (file == null)
                return null;

            return getDtProjectFromWorkspaceProject(file.getProject());
        }
        catch (Exception e)
        {
            log("Global.getDtProjectFromBslEditor: " + e); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * {@link IDtProject} для диалога редактора запросов.
     * Ищет поле {@code project} ({@link IDtProject}) в объекте dialog рефлексией.
     */
    public static IDtProject getDtProjectFromQueryDialog(Object queryDialog)
    {
        if (queryDialog == null)
            return null;
        IDtProject fromField = getProjectFromEditor(queryDialog);
        if (fromField != null)
            return fromField;
        try
        {
            IWorkbenchWindow window =
                PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;
            IEditorPart editor = page.getActiveEditor();
            if (editor == null)
                return null;
            IEditorInput input = editor.getEditorInput();
            if (input != null)
            {
                IFile file = input.getAdapter(IFile.class);
                if (file != null)
                    return getDtProjectFromWorkspaceProject(file.getProject());
            }
        }
        catch (Exception e)
        {
            log("Global.getDtProjectFromQueryDialog: " + e); //$NON-NLS-1$
        }
        return null;
    }

    /** {@link IDtProject} по {@link IProject} воркспейса. */
    public static IDtProject getDtProjectFromWorkspaceProject(IProject iProject)
    {
        if (iProject == null)
            return null;
        try
        {
            IV8ProjectManager projectManager =
                (IV8ProjectManager) getServiceByClass(IV8ProjectManager.class);
            if (projectManager == null)
                return null;

            Object result = invoke(projectManager, "getDtProject", iProject); //$NON-NLS-1$
            if (result instanceof IDtProject)
                return (IDtProject) result;

            Object allProjects = call(projectManager, "getProjects"); //$NON-NLS-1$
            if (allProjects instanceof Iterable<?>)
            {
                for (Object proj : (Iterable<?>) allProjects)
                {
                    IDtProject candidate = toDtProject(proj);
                    if (candidate != null && iProject.equals(candidate.getWorkspaceProject()))
                        return candidate;
                }
            }
        }
        catch (Exception e)
        {
            log("Global.getDtProjectFromWorkspaceProject: " + e); //$NON-NLS-1$
        }
        return null;
    }

    private static IDtProject toDtProject(Object proj)
    {
        if (proj instanceof IDtProject)
            return (IDtProject) proj;
        if (proj instanceof IV8Project)
        {
            Object dt = call(proj, "getDtProject"); //$NON-NLS-1$
            if (dt instanceof IDtProject)
                return (IDtProject) dt;
        }
        return null;
    }

    // =========================================================================
    // ИР — фоновые вызовы БСЛ-функций подключённого приложения
    // =========================================================================

    /**
     * Если для {@code project} есть подключённое приложение ИР (дешёвая проверка без COM-пробы,
     * см. {@link IRApplication#hasConnectedSessionForKeys}) — в фоновом {@link Job} вызывает
     * {@code module.function(args)} через {@link ComBridge} на COM-потоке сессии
     * ({@link IRSession#executeOnComThread}) и, если к моменту готовности результата
     * {@code stillApplicable} возвращает {@code true}, передаёт результат в {@code onResult} —
     * уже на UI-потоке ({@link Display#asyncExec}).
     *
     * <p>Ничего не делает (тихо), если ИР не подключён — не пытается подключиться и не
     * показывает пользователю никаких сообщений об этом (в отличие от {@link IRApplication#getSession}).
     *
     * @param project        проект инфобазы; если {@code null} — вызов не выполняется
     * @param moduleName     имя общего модуля ИР, например {@code "ирОбщий"}
     * @param functionName   имя экспортной функции модуля
     * @param args           аргументы вызова (как в {@link ComBridge#invoke})
     * @param stillApplicable проверяется на UI-потоке непосредственно перед применением результата —
     *                       обычно сравнение снимка состояния UI на момент вызова с текущим
     * @param onResult       вызывается на UI-потоке с результатом ({@code ComBridge.toString(...)}),
     *                       только если {@code stillApplicable.get() == true}
     */
    public static void callIrFunctionInBackground(IDtProject project, String moduleName, String functionName,
        Object[] args, Supplier<Boolean> stillApplicable, Consumer<String> onResult)
    {
        if (project == null || !IRApplication.hasConnectedSessionForKeys(project))
            return;

        new Job("ИР: " + functionName) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    IRSession session = IRApplication.getConnectedSession(project);
                    if (session == null)
                        return Status.OK_STATUS;

                    String result = session.executeOnComThread(() ->
                    {
                        Object module = session.getModule(moduleName);
                        Object raw = ComBridge.invoke(module, functionName, args);
                        return ComBridge.toString(raw);
                    });

                    Display display = Display.getDefault();
                    if (display != null && !display.isDisposed())
                    {
                        display.asyncExec(() ->
                        {
                            if (Boolean.TRUE.equals(stillApplicable.get()))
                                onResult.accept(result);
                        });
                    }
                }
                catch (Exception e)
                {
                    logError("Global", "callIrFunctionInBackground(" + moduleName + "." + functionName + ")", e); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    // =========================================================================
    // Временное логирование (отдельные файлы на отладочном РМ, см. .cursor/rules/comfort-logging.mdc)
    // =========================================================================

    /** Корень репозитория с исходниками плагина (личное РМ автора, не для дистрибуции). */
    private static final String SOURCE_ROOT = "C:\\VC\\EDT.Comfort"; //$NON-NLS-1$

    /** Папка временных логов — очищается целиком при каждом старте плагина, см. {@link #clearTempLogs()}. */
    private static final String TEMP_LOG_DIR = SOURCE_ROOT + "\\.tmp\\temp-logs"; //$NON-NLS-1$

    /**
     * Временный лог отладочного РМ: одна строка с меткой времени в файл {@code .tmp/temp-logs/<topic>.log}.
     * Каждый {@code topic} — свой файл; все такие файлы удаляются при следующем старте плагина
     * ({@link #clearTempLogs()}, вызывается из {@code Activator.start}). Не связан с флажком
     * «Общее логирование» и с журналом «Комфорт» — оба режима работают независимо (см.
     * {@code .cursor/rules/comfort-logging.mdc}).
     *
     * @param topic имя темы/фичи — определяет имя файла; небезопасные для имени файла символы заменяются на {@code _}
     * @param text  текст строки (без времени — оно добавляется автоматически)
     */
    public static synchronized void tempLog(String topic, String text)
    {
        try
        {
            String safeTopic = (topic == null || topic.isBlank()) ? "general" : topic.trim(); //$NON-NLS-1$
            safeTopic = safeTopic.replaceAll("[\\\\/:*?\"<>|\\s]+", "_"); //$NON-NLS-1$ //$NON-NLS-2$
            File dir = new File(TEMP_LOG_DIR);
            dir.mkdirs();
            File file = new File(dir, safeTopic + ".log"); //$NON-NLS-1$
            String line = java.time.LocalDateTime.now() + " " + text + System.lineSeparator(); //$NON-NLS-1$
            Files.writeString(file.toPath(), line, java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
        catch (Exception ignored) {}
    }

    /** Удаляет все файлы временных логов из {@link #TEMP_LOG_DIR}. Вызывать при старте плагина. */
    public static void clearTempLogs()
    {
        try
        {
            File dir = new File(TEMP_LOG_DIR);
            File[] files = dir.listFiles();
            if (files == null)
                return;
            for (File f : files)
                f.delete();
        }
        catch (Exception ignored) {}
    }

    // =========================================================================
    // Логирование (журнал «Журнал Комфорт», {@link GlobalLogView})
    // =========================================================================

    /** Включение: Параметры → Комфорт → «Общее логирование». */
    public static boolean isLogEnabled()
    {
        return ComfortSettings.isDebugLogEnabled();
    }

    public static void log(String message)
    {
        log(null, message);
    }

    public static void log(String tag, String message)
    {
        if (!isLogEnabled() || message == null)
            return;
        GlobalLog.append(formatLogTag(tag) + message);
    }

    public static void logError(String tag, String message, Throwable error)
    {
        if (!isLogEnabled())
            return;
        String text = message != null ? message : ""; //$NON-NLS-1$
        Throwable root = unwrapLogCause(error);
        if (root != null)
        {
            String detail = root.getClass().getSimpleName();
            if (root.getMessage() != null && !root.getMessage().isBlank())
                detail += ": " + root.getMessage(); //$NON-NLS-1$
            text = text.isBlank() ? detail : text + ": " + detail; //$NON-NLS-1$
        }
        GlobalLog.append(formatLogTag(tag) + "ERROR " + text); //$NON-NLS-1$
        if (root != null)
            appendLogStackTrace(root);
    }

    private static void appendLogStackTrace(Throwable error)
    {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        for (String line : sw.toString().split("\n")) //$NON-NLS-1$
        {
            if (line.isBlank())
                continue;
            GlobalLog.append("    " + line); //$NON-NLS-1$
        }
    }

    private static String formatLogTag(String tag)
    {
        if (tag == null || tag.isBlank())
            return ""; //$NON-NLS-1$
        return "[" + tag + "] "; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Throwable unwrapLogCause(Throwable error)
    {
        if (error == null)
            return null;
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root)
            root = root.getCause();
        return root;
    }

    // =========================================================================
    // Идентификаторы / представления (порты 1С-функций ...Лкс)
    // =========================================================================

    private static final String RUSSIAN_LOWER = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"; //$NON-NLS-1$
    private static final String LATIN_LOWER = "abcdefghijklmnopqrstuvwxyz"; //$NON-NLS-1$
    private static final String DIGITS = "0123456789"; //$NON-NLS-1$

    /**
     * Порт 1С-функции {@code ИдентификаторИзПредставленияЛкс}: строит валидный идентификатор
     * из произвольного текстового представления (например, наименования реквизита).
     *
     * <p>Пример (со {@code specialCharReplacement = ""}, см.
     * {@link #identifierFromRepresentation(String, String, String, String)}):
     * {@code "3-я Дебиторка По контрагентам с интервалами СНГ (для Руководства)"} →
     * {@code "_3яДебиторкаПоКонтрагентамСИнтерваламиСНГдляРуководства"} — недопустимые символы
     * отбрасываются, а следующий за ними допустимый символ переводится в верхний регистр
     * (склейка camelCase). Со {@code specialCharReplacement = "_"} те же символы заменяются на
     * {@code "_"} без изменения регистра соседних букв.
     *
     * <p>Как {@link #identifierFromRepresentation(String, String, String, String)} со значениями
     * по умолчанию из BSL-оригинала.
     */
    public static String identifierFromRepresentation(String representation)
    {
        return identifierFromRepresentation(representation, "_", "", "_"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * @param representation         исходное представление (может быть {@code null})
     * @param emptyStringReplacement чем заменить пустое/пробельное представление (BSL-умолчание — {@code "_"})
     * @param extraAllowedChars      дополнительные разрешённые символы идентификатора
     * @param specialCharReplacement чем заменять недопустимый символ; если {@code ""} — символ
     *                               отбрасывается, а следующий допустимый символ переводится
     *                               в верхний регистр (camelCase-склейка); BSL-умолчание — {@code "_"}
     */
    public static String identifierFromRepresentation(String representation, String emptyStringReplacement,
        String extraAllowedChars, String specialCharReplacement)
    {
        String value = representation;
        if (isBlank(value))
            value = emptyStringReplacement;
        if (value == null)
            value = ""; //$NON-NLS-1$
        if (specialCharReplacement == null)
            specialCharReplacement = ""; //$NON-NLS-1$

        // Быстрый путь: представление уже является валидным одиночным идентификатором —
        // порт проверки «Новый Структура(Представление)» из оригинала.
        if (value.equals(value.trim()) && value.indexOf(',') < 0 && isBareIdentifier(value))
            return value;

        String working = value;
        if (!working.isEmpty() && isAsciiDigit(working.charAt(0)))
            working = "_" + working; //$NON-NLS-1$

        String allowedChars = identifierCharSet(extraAllowedChars);
        StringBuilder result = new StringBuilder(working.length());
        // "Предыдущий символ" изначально считается пробелом — как и в оригинале на BSL
        // (ПустаяСтрока(" ") = Истина), из-за чего самый первый обработанный символ
        // переводится в верхний регистр.
        String previousChar = " "; //$NON-NLS-1$
        for (int i = 0; i < working.length(); i++)
        {
            String currentChar = String.valueOf(working.charAt(i));
            if (isBlank(previousChar))
                currentChar = currentChar.toUpperCase(Locale.ROOT);

            char lower = Character.toLowerCase(currentChar.charAt(0));
            if (allowedChars.indexOf(lower) >= 0)
            {
                result.append(currentChar);
            }
            else
            {
                result.append(specialCharReplacement);
                if (specialCharReplacement.isEmpty())
                    currentChar = " "; //$NON-NLS-1$
            }
            previousChar = currentChar;
        }
        return result.toString();
    }

    private static String identifierCharSet(String extraAllowedChars)
    {
        String extra = extraAllowedChars != null ? extraAllowedChars.toLowerCase(Locale.ROOT) : ""; //$NON-NLS-1$
        return "_" + DIGITS + RUSSIAN_LOWER + LATIN_LOWER + extra; //$NON-NLS-1$
    }

    private static boolean isBareIdentifier(String s)
    {
        if (s.isEmpty())
            return false;
        char first = s.charAt(0);
        if (isAsciiDigit(first) || !isIdentifierChar(first))
            return false;
        for (int i = 1; i < s.length(); i++)
        {
            if (!isIdentifierChar(s.charAt(i)))
                return false;
        }
        return true;
    }

    private static boolean isIdentifierChar(char c)
    {
        char lower = Character.toLowerCase(c);
        return lower == '_' || isAsciiDigit(lower)
            || LATIN_LOWER.indexOf(lower) >= 0 || RUSSIAN_LOWER.indexOf(lower) >= 0;
    }

    private static boolean isAsciiDigit(char c)
    {
        return c >= '0' && c <= '9';
    }

    private static boolean isBlank(String s)
    {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Порт 1С-функции {@code ПредставлениеИзИдентификатораЛкс}: восстанавливает читаемое
     * представление из идентификатора, обратная операция к
     * {@link #identifierFromRepresentation(String)}.
     *
     * <p>Примеры: {@code "ОкноПрибытияС"} → {@code "Окно прибытия с"};
     * {@code "Дебиторка_По_контрагентамСИнтерваламиСНГДля__Руководства"} →
     * {@code "Дебиторка По контрагентам с интервалами СНГ для  Руководства"}. После символа
     * {@code "_"} регистр не меняется, а сам символ заменяется на {@code " "}.
     *
     * @param identifier исходный идентификатор
     * @return представление, или {@code null}/{@code ""} если на входе то же самое
     */
    public static String representationFromIdentifier(String identifier)
    {
        if (identifier == null || identifier.isEmpty())
            return identifier;

        int length = identifier.length();
        String currentChar = identifier.substring(0, 1);
        StringBuilder result = new StringBuilder(currentChar);

        for (int i = 2; i <= length; i++)
        {
            String previousChar = currentChar;
            currentChar = identifier.substring(i - 1, i);

            if (currentChar.equals("_")) //$NON-NLS-1$
            {
                result.append(' ');
                continue;
            }
            else if (isUpperInvariant(currentChar))
            {
                String nextChar = charAt1(identifier, i + 1);
                boolean spaceBeforeAbbrevOrDigits =
                    !currentChar.equals(" ") && !isUpperInvariant(previousChar); //$NON-NLS-1$
                boolean spaceBeforeNewCapitalizedWord =
                    !previousChar.equals("_") && isUpperInvariant(previousChar) && !isUpperInvariant(nextChar); //$NON-NLS-1$
                if (spaceBeforeAbbrevOrDigits || spaceBeforeNewCapitalizedWord)
                {
                    result.append(' ');
                    String afterNextChar = charAt1(identifier, i + 2);
                    if (nextChar.isEmpty() || !isUpperInvariant(nextChar) || !isUpperInvariant(afterNextChar))
                        currentChar = currentChar.toLowerCase(Locale.ROOT);
                }
            }
            result.append(currentChar);
        }
        return result.toString();
    }

    /** {@code ВРЕГ(s) = s} из оригинала — верно и для верхнего регистра/цифр/спецсимволов, и для {@code ""}. */
    private static boolean isUpperInvariant(String s)
    {
        return s.isEmpty() || s.toUpperCase(Locale.ROOT).equals(s);
    }

    /** {@code Сред(s, pos, 1)}: символ по 1-based позиции, {@code ""} при выходе за границы строки. */
    private static String charAt1(String s, int pos1Based)
    {
        int index = pos1Based - 1;
        return index >= 0 && index < s.length() ? s.substring(index, index + 1) : ""; //$NON-NLS-1$
    }

    // =========================================================================
    // Вспомогательные
    // =========================================================================

    public static String readTextFromFile(File file) throws IOException
    {
        String text = normalizeLineSeparators(Files.readString(file.toPath()));
        return text.startsWith("\uFEFF") ? text.substring(1) : text; // strip BOM
    }

    // =========================================================================
    // Приватные утилиты
    // =========================================================================

    private static IWorkbenchPage getActivePage()
    {
        IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        return w != null ? w.getActivePage() : null;
    }

    private static IFile editorToFile(IEditorPart editor)
    {
        if (editor == null) return null;
        IEditorInput input = editor.getEditorInput();
        return input != null ? input.getAdapter(IFile.class) : null;
    }

    private static IProject getProjectFromNavigator()
    {
        return getProjectFromNavigator((CommonNavigator) getViewById(NAVIGATOR_VIEW_ID));
    }

    private static IProject getProjectFromNavigator(CommonNavigator nav)
    {
        try
        {
            if (nav == null)
                return null;
            TreeSelection sel = (TreeSelection) nav.getSite().getSelectionProvider().getSelection();
            if (sel == null || sel.isEmpty())
                return null;
            return (IProject) sel.getPaths()[0].getSegment(0);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }
    
    public static IViewPart getViewById(String id) {
        IWorkbench workbench = PlatformUI.getWorkbench();
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                IViewReference ref = page.findViewReference(id);
                if (ref != null) {
                    IViewPart view = ref.getView(false); // не создавать, если не открыт
                    if (view != null)
                        return view;
                }
            }
        }
        return null;
    }
    public static String normalizeLineSeparators(String text) {
        if (text == null) return null;
        // Сначала заменяем \r\n на \n (Windows)
        // Затем заменяем оставшиеся \r на \n (старые Mac)
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /** Текст в LF и границы выделения в координатах этой строки (для COM EDT→ИР). */
    public static record LfTextSlice(String text, int start, int end) {}

    /**
     * Смещение в строке с любыми EOL → смещение в LF-нормализованной версии той же строки.
     * {@code offset} — индекс символа в {@code text} (0…length).
     */
    public static int remapOffsetToLf(String text, int offset)
    {
        if (text == null || offset <= 0)
            return Math.max(0, offset);
        int lfOffset = 0;
        int i = 0;
        int capped = Math.min(offset, text.length());
        while (i < capped)
        {
            char c = text.charAt(i);
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n')
                i += 2;
            else
                i++;
            lfOffset++;
        }
        return lfOffset;
    }

    /**
     * LF-offset → offset в исходной строке {@code rawText} (inverse к {@link #remapOffsetToLf}).
     * Для {@code doc.replace} после {@link #toLfWithSelection} при sync ИР→EDT.
     */
    public static int remapOffsetFromLf(String rawText, int lfOffset)
    {
        if (rawText == null || lfOffset <= 0)
            return Math.max(0, lfOffset);
        int lfPos = 0;
        int rawPos = 0;
        while (lfPos < lfOffset && rawPos < rawText.length())
        {
            char c = rawText.charAt(rawPos);
            if (c == '\r' && rawPos + 1 < rawText.length() && rawText.charAt(rawPos + 1) == '\n')
                rawPos += 2;
            else
                rawPos++;
            lfPos++;
        }
        return rawPos;
    }

    /** Нормализует текст к LF и пересчитывает границы выделения относительно исходной строки. */
    public static LfTextSlice toLfWithSelection(String raw, int start, int end)
    {
        String source = raw != null ? raw : ""; //$NON-NLS-1$
        int rawStart = Math.max(0, start);
        int rawEnd = Math.max(rawStart, end);
        String lfText = normalizeLineSeparators(source);
        return new LfTextSlice(
            lfText,
            remapOffsetToLf(source, rawStart),
            remapOffsetToLf(source, rawEnd));
    }

    /** Число пар {@code \\r\\n} в строке (для диагностики синхронизации с ИР). */
    public static int countCrlf(String text)
    {
        if (text == null || text.length() < 2)
            return 0;
        int count = 0;
        for (int i = 0; i < text.length() - 1; i++)
        {
            if (text.charAt(i) == '\r' && text.charAt(i + 1) == '\n')
                count++;
        }
        return count;
    }

    /** Найти первый Control по предикату в поддереве */
    public static <T extends Control> T findControl(Composite root, Class<T> type, java.util.function.Predicate<T> predicate) {
        for (Control c : root.getChildren()) {
            if (type.isInstance(c) && predicate.test(type.cast(c))) {
                return type.cast(c);
            }
            if (c instanceof Composite) {
                T found = findControl((Composite) c, type, predicate);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Удобный поиск Label по подстроке текста */
    public static Label findLabelByText(Composite root, String text) {
        return findControl(root, Label.class, lbl -> {
            String t = lbl.getText();
            return t != null && t.contains(text);
        });
    }

    public static String pluginSignForTooltip()
    {
        return " (Комфорт)";
    }

    /** Заголовок кастомного окна/диалога плагина (не дублирует суффикс). */
    public static String withPluginWindowTitle(String base)
    {
        return withPluginWindowTitle(base, null);
    }

    /**
     * Заголовок с суффиксом плагина у {@code basePrefix}, далее — {@code titleSuffix}.
     * Пример: {@code ("Коллекция", "Массив")} → «Коллекция (Комфорт) Массив».
     */
    public static String withPluginWindowTitle(String basePrefix, String titleSuffix)
    {
        if (basePrefix == null || basePrefix.isBlank())
            return basePrefix;
        String sign = pluginSignForTooltip();
        String prefix = basePrefix.trim();
        if (prefix.endsWith(sign))
        {
            if (titleSuffix == null || titleSuffix.isBlank())
                return prefix;
            return prefix + ' ' + titleSuffix.trim();
        }
        StringBuilder sb = new StringBuilder(prefix);
        sb.append(sign);
        if (titleSuffix != null && !titleSuffix.isBlank())
            sb.append(' ').append(titleSuffix.trim());
        return sb.toString();
    }
}
