package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.eclipse.core.resources.IProject;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Диагностика подключения приложения ИР (журнал «Комфорт»).
 *
 * <p>Заведён под <a href="https://github.com/tormozit/EDT.Comfort/issues/88">issue #88</a>:
 * {@code IResolvableRuntimeInstallation resolvable = ... Global.invoke(syncMgr, "getInstallation", ...)}
 * либо {@code project}/{@code infobase} оказываются {@code null} на машине пользователя,
 * но не воспроизводится в отладке разработчика. Логи включаются через
 * Параметры → Комфорт → «Общее логирование».
 */
final class IRConnectDebug
{
    private static final String TAG = "IRConnect"; //$NON-NLS-1$

    private IRConnectDebug() {}

    static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, msg);
    }

    static void problem(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // connectInfobaseApplication()
    // -----------------------------------------------------------------------

    static void logConnectStart(Object element)
    {
        log("connectInfobaseApplication: element=" + describe(element)); //$NON-NLS-1$
    }

    static void logResolvedInfobaseAndProject(Object element, InfobaseReference infobase, IProject project)
    {
        log("resolved: infobase=" + infobaseLabel(infobase) //$NON-NLS-1$
            + " project=" + (project != null ? project.getName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$

        if (infobase == null)
            problem("infobase == null для element=" + describe(element) //$NON-NLS-1$
                + " — getInfobase()/поле infobase не найдены"); //$NON-NLS-1$
        if (project == null)
            problem("project == null для element=" + describe(element) //$NON-NLS-1$
                + " — поле \"project\" не найдено или пусто"); //$NON-NLS-1$

        // Дамп рефлексии — только если что-то из пары не резолвится, чтобы не шуметь в обычном режиме
        if (element != null && (infobase == null || project == null))
            dumpReflection(element);
    }

    /** Дамп полей/методов класса element, содержащих project/infobase, — чтобы найти реальные имена в чужой сборке EDT. */
    private static void dumpReflection(Object element)
    {
        Class<?> cls = element.getClass();
        log("dumpReflection: класс " + cls.getName()); //$NON-NLS-1$

        for (Class<?> c = cls; c != null; c = c.getSuperclass())
        {
            for (Field f : c.getDeclaredFields())
            {
                String n = f.getName().toLowerCase();
                if (n.contains("project") || n.contains("infobase")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    Object value = tryReadField(element, f);
                    log("  field " + c.getSimpleName() + "." + f.getName() //$NON-NLS-1$ //$NON-NLS-2$
                        + " : " + f.getType().getSimpleName() //$NON-NLS-1$
                        + " = " + describe(value)); //$NON-NLS-1$
                }
            }
            for (Method m : c.getDeclaredMethods())
            {
                String n = m.getName().toLowerCase();
                if (Modifier.isPublic(m.getModifiers()) && m.getParameterCount() == 0
                    && (n.contains("project") || n.contains("infobase"))) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    Object value = tryInvokeNoArg(element, m);
                    log("  method " + c.getSimpleName() + "." + m.getName() + "()" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + " : " + m.getReturnType().getSimpleName() //$NON-NLS-1$
                        + " = " + describe(value)); //$NON-NLS-1$
                }
            }
        }
    }

    private static Object tryReadField(Object obj, Field f)
    {
        try
        {
            f.setAccessible(true);
            return f.get(obj);
        }
        catch (Exception e)
        {
            return "<err " + e.getClass().getSimpleName() + ">"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static Object tryInvokeNoArg(Object obj, Method m)
    {
        try
        {
            m.setAccessible(true);
            return m.invoke(obj);
        }
        catch (Exception e)
        {
            return "<err " + e.getClass().getSimpleName() + ">"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // -----------------------------------------------------------------------
    // ApplicationsViewHook.getRuntimeInstallation()
    // -----------------------------------------------------------------------

    static void logRuntimeInstallationLookupStart(IProject project, InfobaseReference infobase)
    {
        log("getRuntimeInstallation: project=" + (project != null ? project.getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
            + " infobase=" + infobaseLabel(infobase)); //$NON-NLS-1$
    }

    static void logServiceReference(ServiceReference<?> ref)
    {
        if (ref == null)
            problem("getServiceReference(IInfobaseSynchronizationManager) вернул null — сервис не зарегистрирован"); //$NON-NLS-1$
        else
            log("serviceReference найден: " + ref); //$NON-NLS-1$
    }

    static void logSyncMgr(Object syncMgr)
    {
        if (syncMgr == null)
            problem("ctx.getService(ref) вернул null для IInfobaseSynchronizationManager"); //$NON-NLS-1$
        else
            log("syncMgr класс=" + syncMgr.getClass().getName()); //$NON-NLS-1$
    }

    static void logResolvable(Object syncMgr, Object resolvable)
    {
        if (resolvable == null)
        {
            problem("Global.invoke(syncMgr, \"getInstallation\", project, infobase) вернул null"); //$NON-NLS-1$
            if (syncMgr != null)
                dumpGetInstallationMethods(syncMgr);
        }
        else
        {
            log("resolvable класс=" + resolvable.getClass().getName()); //$NON-NLS-1$
        }
    }

    /** Если Global.invoke не нашёл/не вызвал метод — покажем, что реально есть на классе syncMgr. */
    private static void dumpGetInstallationMethods(Object syncMgr)
    {
        Class<?> cls = syncMgr.getClass();
        for (Class<?> c = cls; c != null; c = c.getSuperclass())
        {
            for (Method m : c.getDeclaredMethods())
            {
                if (m.getName().equals("getInstallation")) //$NON-NLS-1$
                {
                    StringBuilder params = new StringBuilder();
                    for (Class<?> p : m.getParameterTypes())
                        params.append(p.getSimpleName()).append(", "); //$NON-NLS-1$
                    log("  найден getInstallation(" + params + ") на " + c.getName()); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
    }

    static void logResolveResult(Object result)
    {
        log("resolve() -> " + describe(result)); //$NON-NLS-1$
    }

    static void logResolveException(Exception e, IProject project, InfobaseReference infobase)
    {
        problem("resolve() бросил исключение: project=" + (project != null ? project.getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
            + " infobase=" + infobaseLabel(infobase) + " -> " + e); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -----------------------------------------------------------------------
    // IRApplication.doConnectInternal()
    // -----------------------------------------------------------------------

    static void logRuntimeInstallationNull(IProject project, InfobaseReference infobase)
    {
        problem("doConnectInternal: getRuntimeInstallation() вернул null — project=" //$NON-NLS-1$
            + (project != null ? project.getName() : "null") //$NON-NLS-1$
            + " infobase=" + infobaseLabel(infobase)); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Вспомогательные
    // -----------------------------------------------------------------------

    private static String infobaseLabel(InfobaseReference infobase)
    {
        if (infobase == null)
            return "null"; //$NON-NLS-1$
        String uuid = IRApplication.extractInfobaseUuid(infobase);
        return uuid.isEmpty() ? infobase.toString() : uuid;
    }

    private static String describe(Object obj)
    {
        if (obj == null)
            return "null"; //$NON-NLS-1$
        try
        {
            return obj.getClass().getSimpleName() + "[" + obj + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            return obj.getClass().getSimpleName() + "[<toString error>]"; //$NON-NLS-1$
        }
    }
}
