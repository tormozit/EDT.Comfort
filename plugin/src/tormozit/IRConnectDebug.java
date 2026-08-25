package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.v8.dt.platform.version.Version;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationType;

/**
 * Диагностика подключения приложения ИР (журнал «Комфорт»).
 *
 * <p>Заведён под <a href="https://github.com/tormozit/EDT.Comfort/issues/88">issue #88</a>
 * (резолв {@code RuntimeInstallation}/{@code project}/{@code infobase}).
 * Расширен под <a href="https://github.com/tormozit/EDT.Comfort/issues/396">issue #396</a>:
 * какой COM-класс ({@code V83.Application} vs {@code V85.Application}) выбран
 * и на какой {@code 1cv8.exe} он указывает в реестре.
 *
 * <p>Логи включаются через Параметры → Комфорт → «Вести журнал».
 * Вызовы из отрисовки колонки «Платформа» должны передавать {@code debug=false},
 * иначе журнал забивается на каждый refresh (урок #88).
 */
final class IRConnectDebug
{
    private static final String TAG = "IRConnect"; //$NON-NLS-1$
    private static final String COM_V83 = "V83.Application"; //$NON-NLS-1$
    private static final String COM_V85 = "V85.Application"; //$NON-NLS-1$

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
        if (element instanceof IApplication app)
        {
            IApplicationType type = app.getType();
            log("applicationType id=" + (type != null ? type.getId() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                + " name=" + (type != null ? type.getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                + " requiredVersion=" + app.getRequiredVersion()); //$NON-NLS-1$
        }
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
        if (infobase != null)
        {
            log("infobase.version=" + nz(infobase.getVersion()) //$NON-NLS-1$
                + " defaultVersion=" + nz(infobase.getDefaultVersion()) //$NON-NLS-1$
                + " type=" + infobase.getInfobaseType() //$NON-NLS-1$
                + " appArch=" + infobase.getAppArch()); //$NON-NLS-1$
        }
        if (project == null || infobase == null)
            problem("основной резолв пропущен: project или infobase == null"); //$NON-NLS-1$
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
        if (syncMgr != null)
        {
            String name = syncMgr.getClass().getName();
            if (name.contains("ContextLinks")) //$NON-NLS-1$
                problem("syncMgr — прокси ContextLinks (#88): " + name); //$NON-NLS-1$
        }
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
        if (result == null)
            problem("основной resolve(ThickClient) вернул null"); //$NON-NLS-1$
    }

    static void logResolveArch(Object appArch)
    {
        log("основной resolve ThickClient appArch=" + appArch); //$NON-NLS-1$
    }

    static void logResolveException(Exception e, IProject project, InfobaseReference infobase)
    {
        problem("resolve() бросил исключение: project=" + (project != null ? project.getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
            + " infobase=" + infobaseLabel(infobase) + " -> " + e); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -----------------------------------------------------------------------
    // IRApplication.doConnectInternal() — выбор COM-класса (issue #396)
    // -----------------------------------------------------------------------

    static void logRuntimeInstallationNull(IProject project, InfobaseReference infobase)
    {
        problem("основной резолв вернул null — project=" //$NON-NLS-1$
            + (project != null ? project.getName() : "null") //$NON-NLS-1$
            + " infobase=" + infobaseLabel(infobase) //$NON-NLS-1$
            + " → ищем CLIENT_LAUNCH / StandaloneServer / маску версии"); //$NON-NLS-1$
    }

    static void logRuntimeSource(String source, RuntimeInstallation inst)
    {
        if (inst == null)
            problem("установка не найдена, источник=" + nz(source)); //$NON-NLS-1$
        else
            log("источник установки=" + nz(source) //$NON-NLS-1$
                + " versionWithBuild=" + nz(inst.getVersionWithBuild()) //$NON-NLS-1$
                + " location=" + (inst.getLocation() != null ? inst.getLocation().toString() : "—")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static void logFallbackInputs(IProject project, Object application)
    {
        log("фолбек: project=" + (project != null ? project.getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
            + " application=" + describe(application)); //$NON-NLS-1$
        if (project == null || application == null)
            problem("фолбек пропущен: project или application == null — дальше версия ИБ или хардкод 8.3"); //$NON-NLS-1$
    }

    static void logFallbackFailed(String phase, Exception e)
    {
        problem("фолбек " + phase + " → " + e.getClass().getName() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    static void logFallbackGetInstallationEmpty()
    {
        problem("фолбек getInstallation(project, application) вернул empty"); //$NON-NLS-1$
    }

    static void logFallbackResolve(String arch, RuntimeInstallation result, Exception error)
    {
        if (error != null)
            problem("фолбек resolve(" + arch + ") → " + error.getClass().getSimpleName() //$NON-NLS-1$ //$NON-NLS-2$
                + ": " + error.getMessage()); //$NON-NLS-1$
        else if (result == null)
            problem("фолбек resolve(" + arch + ") вернул null"); //$NON-NLS-1$ //$NON-NLS-2$
        else
            log("фолбек resolve(" + arch + ") → versionWithBuild=" //$NON-NLS-1$ //$NON-NLS-2$
                + nz(result.getVersionWithBuild()) + " location=" //$NON-NLS-1$
                + (result.getLocation() != null ? result.getLocation().toString() : "—")); //$NON-NLS-1$
    }

    static void logInfobaseVersionFallback(String field, String version)
    {
        log("установка не найдена — COM-версию берём из infobase." + field + "=" + version); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static void logDefaultPlatformVersion()
    {
        problem("ни один резолв не дал установку, infobase.version пуст — хардкод platformVersion=8.3 → V83.Application"); //$NON-NLS-1$
    }

    static void logComSelection(InfobaseReference infobase, RuntimeInstallation inst,
            String runtimeSource, String platformVersion, boolean configBitness64, String className)
    {
        log("источник платформы=" + nz(runtimeSource) //$NON-NLS-1$
            + " platformVersion=" + nz(platformVersion) //$NON-NLS-1$
            + " arch64=" + configBitness64 //$NON-NLS-1$
            + " COM=" + className); //$NON-NLS-1$
        logComClassDerivation(platformVersion, className);

        if (infobase != null)
        {
            String ibVer = infobase.getVersion();
            String ibDefault = infobase.getDefaultVersion();
            log("infobase.version=" + nz(ibVer) //$NON-NLS-1$
                + " defaultVersion=" + nz(ibDefault) //$NON-NLS-1$
                + " appArch=" + infobase.getAppArch()); //$NON-NLS-1$
        }

        if (inst == null)
            problem("RuntimeInstallation отсутствует — COM-класс выведен из platformVersion=" //$NON-NLS-1$
                + nz(platformVersion));
        else
        {
            Version version = inst.getVersion();
            String versionWithBuild = inst.getVersionWithBuild();
            String location = inst.getLocation() != null ? inst.getLocation().toString() : null;
            StringBuilder versionParts = new StringBuilder();
            if (version != null)
            {
                versionParts.append(" major=").append(version.getMajor()) //$NON-NLS-1$
                    .append(" minor=").append(version.getMinor()) //$NON-NLS-1$
                    .append(" micro=").append(version.getMicro()); //$NON-NLS-1$
            }
            log("установка name=" + nz(inst.getName()) //$NON-NLS-1$
                + " typeId=" + nz(inst.getTypeId()) //$NON-NLS-1$
                + " version=" + version //$NON-NLS-1$
                + versionParts
                + " build=" + inst.getBuild() //$NON-NLS-1$
                + " versionWithBuild=" + nz(versionWithBuild) //$NON-NLS-1$
                + " arch=" + inst.getArch() //$NON-NLS-1$
                + " location=" + nz(location)); //$NON-NLS-1$
        }

        logVersionSources(infobase, inst, platformVersion, className);
    }

    /** Как {@link IRApplication#buildComClassName} получил класс из строки версии. */
    static void logComClassDerivation(String platformVersion, String className)
    {
        if (platformVersion == null || platformVersion.isEmpty())
        {
            problem("buildComClassName: версия пустая → " + className); //$NON-NLS-1$
            return;
        }
        String[] parts = platformVersion.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
            problem("buildComClassName: version=" + platformVersion //$NON-NLS-1$
                + " parts=" + parts.length + " (<2) → " + className); //$NON-NLS-1$ //$NON-NLS-2$
        else
            log("buildComClassName: version=" + platformVersion //$NON-NLS-1$
                + " parts=" + String.join(",", parts) //$NON-NLS-1$ //$NON-NLS-2$
                + " minor=" + parts[1] + " → " + className); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Все гипотезы источников версии → какой COM-класс получился бы. */
    static void logVersionSources(InfobaseReference infobase, RuntimeInstallation inst,
            String usedVersion, String chosenClass)
    {
        logComSource("использован platformVersion", usedVersion, chosenClass); //$NON-NLS-1$
        if (inst != null)
        {
            Version version = inst.getVersion();
            logComSource("inst.getVersion()", version != null ? version.toString() : null, chosenClass); //$NON-NLS-1$
            logComSource("inst.getVersionWithBuild()", inst.getVersionWithBuild(), chosenClass); //$NON-NLS-1$
        }
        if (infobase != null)
        {
            logComSource("infobase.getVersion()", infobase.getVersion(), chosenClass, true); //$NON-NLS-1$
            logComSource("infobase.getDefaultVersion()", infobase.getDefaultVersion(), chosenClass, true); //$NON-NLS-1$
            try
            {
                String designerVer = DesignerSessionPoolAccessor.getPlatformVersionFromActiveSession(infobase);
                if ("8.3.0.0".equals(designerVer)) //$NON-NLS-1$
                    log("сеанс конфигуратора: ключ не найден, метод вернул дефолт 8.3.0.0"); //$NON-NLS-1$
                else
                    logComSource("сеанс конфигуратора", designerVer, chosenClass, true); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                problem("сеанс конфигуратора: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        logComSource("хардкод 8.3", "8.3", chosenClass, false); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void logComSource(String source, String version, String chosenClass)
    {
        logComSource(source, version, chosenClass, true);
    }

    private static void logComSource(String source, String version, String chosenClass, boolean alertOnMismatch)
    {
        if (version == null || version.isEmpty())
        {
            log("источник COM: " + source + "=—"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        String other = IRApplication.buildComClassName(version);
        String line = "источник COM: " + source + "=" + version + " → " + other; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (alertOnMismatch && chosenClass != null && !other.equals(chosenClass))
            problem(line + ", но выбран " + chosenClass); //$NON-NLS-1$
        else
            log(line);
    }

    static void logReregister(String className, String exeFullName, int attempt)
    {
        log("перерегистрация COM попытка=" + attempt + " класс=" + className + " exe=" + exeFullName); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    static void logComRegistry(String chosenClass)
    {
        Set<String> classes = new LinkedHashSet<>();
        classes.add(chosenClass);
        classes.add(COM_V83);
        classes.add(COM_V85);
        for (String name : classes)
            dumpComClass(name);
    }

    static void logLaunchedProcess(Object processObj)
    {
        if (processObj == null)
        {
            problem("процесс 1cv8.exe после создания COM не найден"); //$NON-NLS-1$
            return;
        }
        try
        {
            long pid = WmiProcessHelper.getPid(processObj);
            String exe = ComBridge.toString(ComBridge.getProperty(processObj, "ExecutablePath")); //$NON-NLS-1$
            String cmd = ComBridge.toString(ComBridge.getProperty(processObj, "CommandLine")); //$NON-NLS-1$
            log("процесс PID=" + pid + " exe=" + exe + " cmdline=" + cmd); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        catch (Exception e)
        {
            problem("не удалось прочитать свойства процесса: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void dumpComClass(String className)
    {
        String clsidHklm = IRApplication.registryReadDefault("HKLM", //$NON-NLS-1$
            "SOFTWARE\\Classes\\" + className + "\\CLSID"); //$NON-NLS-1$ //$NON-NLS-2$
        String clsidHkcu = IRApplication.registryReadDefault("HKCU", //$NON-NLS-1$
            "SOFTWARE\\Classes\\" + className + "\\CLSID"); //$NON-NLS-1$ //$NON-NLS-2$
        log("COM " + className + " CLSID HKLM=" + nz(clsidHklm) + " HKCU=" + nz(clsidHkcu)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        dumpLocalServer(className, clsidHklm, "HKLM"); //$NON-NLS-1$
        dumpLocalServer(className, clsidHkcu != null && !clsidHkcu.isEmpty() ? clsidHkcu : clsidHklm, "HKCU"); //$NON-NLS-1$
    }

    private static void dumpLocalServer(String className, String clsid, String hive)
    {
        if (clsid == null || clsid.isEmpty())
            return;
        String s64 = IRApplication.registryReadDefault(hive,
            "SOFTWARE\\Classes\\CLSID\\" + clsid + "\\LocalServer32"); //$NON-NLS-1$ //$NON-NLS-2$
        String s32 = IRApplication.registryReadDefault(hive,
            "SOFTWARE\\Classes\\WOW6432Node\\CLSID\\" + clsid + "\\LocalServer32"); //$NON-NLS-1$ //$NON-NLS-2$
        log("COM " + className + " LocalServer32 " + hive //$NON-NLS-1$ //$NON-NLS-2$
            + " x64=" + nz(s64) + " wow32=" + nz(s32)); //$NON-NLS-1$ //$NON-NLS-2$
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

    private static String nz(String value)
    {
        return value == null || value.isEmpty() ? "—" : value; //$NON-NLS-1$
    }
}
