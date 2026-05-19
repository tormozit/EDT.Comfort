

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.v8.dt.platform.services.model.Arch;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.model.impl.RuntimeInstallationImpl;
import com._1c.g5.v8.dt.platform.version.Version;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

/**
 * Реестр подключений к приложению ИР (Инструменты Разработчика 1С).
 *
 * <p>Объединяет бывший {@code ComConnectionRegistry} и прежний {@code IRApplicationRegistry}:
 * управляет жизненным циклом COM-сессий и адаптирует их к элементам панели
 * «Приложения» EDT ({@code InfobaseApplication}).
 *
 * <h3>Порт функций из RDT.os</h3>
 * <ul>
 *   <li>{@link #connect} ← {@code ПодключениеИР()}</li>
 *   <li>{@link #disconnect} ← {@code ЗакрытьПриложениеИР()}</li>
 * </ul>
 *
 * <h3>Тост-уведомление</h3>
 * {@link EclipseToastNotification#show} вызывается через {@code syncExec} →
 * всегда возвращает {@link Shell}. {@code finally} закрывает его при любом исходе.
 *
 * <h3>WMI</h3>
 * {@link WmiProcessHelper#connectWmi()} вызывается один раз до создания COM-объекта.
 * PID хранится в сессии и используется для принудительного завершения.
 */
public final class IRApplicationRegistry
{
    // -----------------------------------------------------------------------
    // Синглтон
    // -----------------------------------------------------------------------

    private static final IRApplicationRegistry INSTANCE = new IRApplicationRegistry();

    public static IRApplicationRegistry getInstance() { return INSTANCE; }

    private IRApplicationRegistry() {}

    // -----------------------------------------------------------------------
    // Состояние сессии
    // -----------------------------------------------------------------------

    public enum State { IDLE, CONNECTING, CONNECTED }

    public static final class IrSession
    {
        public final State         state;
        public final LocalDateTime startTime;      // момент успешного Connect()
        public final long          pid;            // PID процесса 1cv8.exe (0 если неизвестен)
        public final String        platformVersion; // версия платформы, например "8.5.1.1343"
        final Object               dispatch;       // Jacob ActiveXComponent (1С COM)
        final Object               processObj;     // Win32_Process COM-объект (для Terminate)
        final String               appTitle;       // заголовок «ИР - Srvr=...»

        IrSession(State state, LocalDateTime startTime, long pid, String platformVersion,
                  Object dispatch, Object processObj, String appTitle)
        {
            this.state           = state;
            this.startTime       = startTime;
            this.pid             = pid;
            this.platformVersion = platformVersion;
            this.dispatch        = dispatch;
            this.processObj      = processObj;
            this.appTitle        = appTitle;
        }

        static IrSession connecting()
        {
            return new IrSession(State.CONNECTING, LocalDateTime.now(), 0, "", null, null, ""); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // UUID инфобазы → сессия
    private final Map<String, IrSession> sessions        = new ConcurrentHashMap<>();
    private final List<Runnable>         changeListeners = new CopyOnWriteArrayList<>();

    // авто-подключение при открытии базы в EDT
    private final Map<String, Boolean> autoConnectMap = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Публичный API — опрос состояния
    // -----------------------------------------------------------------------

    public void addChangeListener(Runnable l)    { if (l != null) changeListeners.add(l); }
    public void removeChangeListener(Runnable l) { changeListeners.remove(l); }

    // Тип Object важен
    public boolean isConnected(Object infobase)
    {
        String key = sessionKey((InfobaseReference)infobase);
        IrSession s = sessions.get(key);
        return s != null && s.state == State.CONNECTED;
    }

    /**
     * Возвращает момент успешного подключения, или {@code null} если нет сессии.
     */
    public LocalDateTime getSessionStart(Object element)
    {
        InfobaseReference infobase = ApplicationsViewHook.getInfobaseFromApplication(element);
        String key = sessionKey(infobase);
        IrSession s = sessions.get(key);
        return (s != null && s.state == State.CONNECTED) ? s.startTime : null;
    }

    /**
     * Возвращает версию платформы, под которой запущено приложение ИР,
     * или {@code null} если нет активной сессии.
     *
     * <p>Пример: {@code "8.5.1.1343"}.
     */
    public String getSessionPlatformVersion(Object element)
    {
        InfobaseReference infobase = ApplicationsViewHook.getInfobaseFromApplication(element);
        String key = sessionKey(infobase);
        IrSession s = sessions.get(key);
        return (s != null && s.state == State.CONNECTED) ? s.platformVersion : null;
    }

    public boolean isAutoConnect(Object element)
    {
        InfobaseReference infobase = ApplicationsViewHook.getInfobaseFromApplication(element);
        return autoConnectMap.getOrDefault(sessionKey(infobase), false);
    }

    public void setAutoConnect(Object element, boolean auto)
    {
        InfobaseReference infobase = ApplicationsViewHook.getInfobaseFromApplication(element);
        autoConnectMap.put(sessionKey(infobase), auto);
    }
    
    // -----------------------------------------------------------------------
    // Подключение (аналог ПодключениеИР)
    // -----------------------------------------------------------------------

    /**
     * Запускает асинхронное подключение к приложению ИР.
     * Немедленно устанавливает состояние CONNECTING — колонка показывает «подключение».
     */
    public void connect(Object element)
    {
        InfobaseReference infobase = ApplicationsViewHook.getInfobaseFromApplication(element);
        String key = sessionKey(infobase);

        IrSession existing = sessions.get(key);
        if (existing != null && (existing.state == State.CONNECTING
                || existing.state == State.CONNECTED)) return;
        IProject activeProject = (IProject) Reflect.getField(element, "project");
        sessions.put(key, IrSession.connecting());
        notifyListeners();
        CompletableFuture.runAsync(() -> doConnect(activeProject, infobase));
    }

    /**
     * Фоновый метод — тост создаётся здесь (syncExec → Shell гарантирован),
     * закрывается в {@code finally} при любом исходе.
     */
    private void doConnect(IProject project, InfobaseReference infobase)
    {
        String appLabel = DesignerSessionPoolAccessor.nameOf(infobase);
        Shell connectingToast = EclipseToastNotification.show("Подключение",
            "Подключается приложение ИР «" + appLabel + "». Закрыть командой «Отключить приложение ИР».", 60_000);
        try
        {
            doConnectInternal(project, infobase);
        }
        catch (Exception e)
        {
            EclipseToastNotification.show("Ошибка подключения ИР", e.getMessage(), 10_000);
            sessions.remove(infobase);
            notifyListeners();
        }
        finally
        {
            EclipseToastNotification.close(connectingToast);
        }
    }

    /**
     * Основная логика подключения — порт вложенного цикла из {@code ПодключениеИР()}.
     * @throws UnsupportedEncodingException 
     */
    private void doConnectInternal(IProject project, InfobaseReference infobase)
    {
        String key = sessionKey(infobase);
        String connectionString = buildConnectionString(infobase);
        RuntimeInstallation RuntimeInstallation = ApplicationsViewHook.getRuntimeInstallation(project, infobase);
        String platformVersion = RuntimeInstallation.getVersion().toString();
        boolean configBitness64 = RuntimeInstallation.getArch() == Arch.X86_64; 
        log("connect() key=" + connectionString + " cs=" + removePassword(connectionString) //$NON-NLS-1$ //$NON-NLS-2$
            + " platform=" + platformVersion); //$NON-NLS-1$

        String className = buildComClassName(platformVersion);
        String connectionStringNoPass = removePassword(connectionString);
        log("COM-класс: " + className + ", БД: " + connectionStringNoPass); //$NON-NLS-1$

        // WMI создаём до запуска 1cv8.exe — нужен для поиска PID нового процесса
        Object wmi = WmiProcessHelper.connectWmi();

        Object  comDispatch        = null;
        Object  processObj         = null;
        long    pid                = 0;
        boolean success            = false;
        String  descriptionOnError = ""; //$NON-NLS-1$
        long    momentStart        = System.currentTimeMillis();

        // До 2 попыток — аналог «Для НомерПопытки = 1 По 2»
        for (int attempt = 1; attempt <= 2; attempt++)
        {
            try
            {
                long startMs = System.currentTimeMillis();
                comDispatch = ComJacobBridge.createComObject(className);

                // ПолучитьПроцессОСЛкс(Неопределено, startMs, 2, "-Embedding")
                processObj = WmiProcessHelper.findProcess(wmi, startMs, 2);
                pid        = WmiProcessHelper.getPid(processObj);

                boolean connected = ComJacobBridge.connect(comDispatch, connectionString);
                if (connected) { success = true; break; }

                descriptionOnError = "Connect() вернул false"; //$NON-NLS-1$
                comDispatch = null; processObj = null; pid = 0;
            }
            catch (Exception e)
            {
                descriptionOnError = e.getMessage() != null ? e.getMessage() : e.toString();
                comDispatch = null; processObj = null; pid = 0;

                String low = descriptionOnError.toLowerCase();
                if (low.contains("пароль") || low.contains("password") //$NON-NLS-1$ //$NON-NLS-2$
                        || low.contains("не идентифицирован")) //$NON-NLS-1$
                {
                    showError("Неверное имя или пароль.\n" + descriptionOnError); //$NON-NLS-1$
                    sessions.remove(key); notifyListeners(); return;
                }
                if (low.contains("0x800706be")) //$NON-NLS-1$
                {
                    showError("Ошибка инициации приложения. Подробности в Error Log."); //$NON-NLS-1$
                    sessions.remove(key); notifyListeners(); return;
                }
                if (attempt == 2)
                {
                    String msg = "Ошибка подключения ИР " + connectionStringNoPass //$NON-NLS-1$
                        + " через " + className + ":\n" + descriptionOnError; //$NON-NLS-1$ //$NON-NLS-2$
                    showError(msg); log(msg);
                    if (!descriptionOnError.contains("Ошибка разделенного доступа")) //$NON-NLS-1$
                        EclipseToastNotification.show("Ошибка COM", //$NON-NLS-1$
                            "Зарегистрируйте " + className + " (/RegServer -CurrentUser).", 8_000); //$NON-NLS-1$ //$NON-NLS-2$
                    sessions.remove(key); notifyListeners(); return;
                }
                log("Попытка " + attempt + " неудача. Повтор..."); //$NON-NLS-1$ //$NON-NLS-2$
                // Еще есть красивый способо com._1c.g5.v8.dt.internal.platform.services.core.infobases.sync.connections.DesignerSessionInfobaseConnection.getBaseDirectory()
                registerComClass(className, RuntimeInstallation.getLocation().getPath(), attempt, configBitness64);
            }
        }

        if (!success || comDispatch == null) { sessions.remove(key); notifyListeners(); return; }

        long   duration = (System.currentTimeMillis() - momentStart) / 1000;
        String title    = "ИР - " + connectionStringNoPass.split(";")[0]; //$NON-NLS-1$ //$NON-NLS-2$

        // УстановитьЗаголовок (8.3.10+) / УстановитьЗаголовокСистемы (8.3.9-)
        try
        {
            Object comApp = ComJacobBridge.getProperty(comDispatch, "КлиентскоеПриложение");
            ComJacobBridge.invoke(comApp, "УстановитьЗаголовок", title);
        }
        catch (Exception e)
        {
            ComJacobBridge.invoke(comDispatch, "УстановитьЗаголовокСистемы", title); //$NON-NLS-1$
        }

        final long   finalPid      = pid;
        final Object finalDispatch = comDispatch;
        final Object finalProcObj  = processObj;
        final String finalVersion  = platformVersion;

        sessions.put(key, new IrSession(
            State.CONNECTED, LocalDateTime.now(), finalPid, finalVersion,
            finalDispatch, finalProcObj, title));
        notifyListeners();

        EclipseToastNotification.show("ИР подключено", //$NON-NLS-1$
            title + " подключено за " + duration + " сек", 3_000); //$NON-NLS-1$ //$NON-NLS-2$
        log("Подключено: " + title + " за " + duration + " с, PID=" + finalPid //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " platform=" + platformVersion); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Отключение (аналог ЗакрытьПриложениеИР)
    // -----------------------------------------------------------------------

    public void disconnect(InfobaseReference infobase)
    {
        String key = sessionKey(infobase);
        IrSession session = sessions.get(key);
        if (session == null || session.state == State.IDLE) return;
        CompletableFuture.runAsync(() -> doDisconnect(key, session));
    }

    private void doDisconnect(String key, IrSession session)
    {
        boolean killed = false;

        if (session.dispatch != null)
        {
            try
            {
                // ЗавершитьРаботуСистемы(false) — штатное завершение
                ComJacobBridge.invoke(session.dispatch, "ЗавершитьРаботуСистемы", false); //$NON-NLS-1$
                log("ЗавершитьРаботуСистемы(false) — OK"); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                log("ЗавершитьРаботуСистемы ошибка → УбитьПроцесс: " + e.getMessage()); //$NON-NLS-1$
                Object wmi  = WmiProcessHelper.connectWmi();
                Object proc = session.processObj != null ? session.processObj
                    : WmiProcessHelper.findProcessByPid(wmi, session.pid);
                WmiProcessHelper.terminate(proc, session.pid);
                killed = true;
            }
        }
        else if (session.pid > 0)
        {
            Object wmi  = WmiProcessHelper.connectWmi();
            Object proc = WmiProcessHelper.findProcessByPid(wmi, session.pid);
            WmiProcessHelper.terminate(proc, session.pid);
            killed = true;
        }

        EclipseToastNotification.show("ИР отключено", //$NON-NLS-1$
            killed ? "Процесс приложения ИР завершён принудительно." //$NON-NLS-1$
                   : "Приложение ИР завершено. Отключение займёт несколько секунд.", //$NON-NLS-1$
            3_000);

        sessions.remove(key);
        notifyListeners();
        log("Сессия ИР удалена, key=" + key); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Строка соединения (аналог СтрокаСоединенияБазыКонфигуратора)
    // -----------------------------------------------------------------------

    static String buildConnectionString(Object infobase)
    {
        Object connectionString = Reflect.getField(infobase, "connectionString"); //$NON-NLS-1$
        String result = (String) Reflect.call(connectionString, "asConnectionString"); //$NON-NLS-1$
        if (result != null && !result.isEmpty()
                && (result.contains("File=") || result.contains("Srvr="))) //$NON-NLS-1$ //$NON-NLS-2$
            return result;
        return ""; //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Ключ сессии = UUID инфобазы
    // -----------------------------------------------------------------------

    private static String sessionKey(InfobaseReference infobase)
    {
        String uuid = extractInfobaseUuid(infobase);
        return uuid.isEmpty() ? String.valueOf(System.identityHashCode(infobase)) : uuid;
    }

    static String extractInfobaseUuid(Object infobase)
    {
        Object uuid = Reflect.call(infobase, "getUuid"); //$NON-NLS-1$
        if (uuid instanceof String && !((String) uuid).isEmpty()) return (String) uuid;

        java.util.regex.Matcher m = DesignerSessionPoolAccessor.UUID_PATTERN
            .matcher(infobase.toString());
        return m.find() ? m.group() : ""; //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Утилиты
    // -----------------------------------------------------------------------

    /** "8.5.1.1343" → "V85.Application" */
    public static String buildComClassName(String platformVersion)
    {
        if (platformVersion == null || platformVersion.isEmpty()) return "V83.Application"; //$NON-NLS-1$
        String[] parts = platformVersion.split("\\."); //$NON-NLS-1$
        return parts.length >= 2 ? "V8" + parts[1] + ".Application" : "V83.Application"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Удаляет Pwd="..." из строки соединения. */
    public static String removePassword(String cs)
    {
        if (cs == null) return ""; //$NON-NLS-1$
        int idx = cs.toLowerCase().indexOf("pwd="); //$NON-NLS-1$
        return idx > 0 ? cs.substring(0, idx) : cs;
    }


    // -----------------------------------------------------------------------
    // Авторегистрация COM-класса в реестре Windows
    // Порт блока «Если ТекущаяВерсияТурбоКонф >= "6.0.8771.35683"»
    // из RDT.os / ПодключениеИР()
    // -----------------------------------------------------------------------

    /**
     * При ошибке создания COM-объекта чистит конфликтующую запись другой
     * разрядности и перерегистрирует класс через {@code 1cv8.exe /RegServer -CurrentUser}.
     *
     * <p>EDT (и JVM) всегда 64-bit, поэтому конфликт возникает когда
     * в {@code HKLM\SOFTWARE\Classes\WOW6432Node} есть старая 32-bit регистрация,
     * из-за которой ОС «отдаёт» не тот DLL при создании COM-объекта.
     */
    private static void registerComClass(String className, String exeFullName, int attempt, boolean configBitness64)
    {
        try
        {
            String subKey        = configBitness64 ? "\\WOW6432Node" : "";  // ветка противоположной разрядности //$NON-NLS-1$ //$NON-NLS-2$
            String removeBitness = configBitness64 ? "32" : "64"; //$NON-NLS-1$ //$NON-NLS-2$
            String useBitness    = configBitness64 ? "64" : "32"; //$NON-NLS-1$ //$NON-NLS-2$

            log("Регистрация COM: JVM=" + useBitness + "b класс=" + className); //$NON-NLS-1$ //$NON-NLS-2$

            // 1. CLSID: HKLM\SOFTWARE\Classes\{className}\CLSID → (Default)
            String clsid = registryReadDefault("HKLM", "SOFTWARE\\Classes\\" + className + "\\CLSID"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (clsid == null || clsid.isEmpty())
            {
                log("CLSID для " + className + " не найден в реестре, регистрация пропущена"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            log("CLSID " + className + " = " + clsid); //$NON-NLS-1$ //$NON-NLS-2$

            // 2. Ветка LocalServer32 для противоположной разрядности
            String server32 = "SOFTWARE\\Classes" + subKey + "\\CLSID\\" + clsid + "\\LocalServer32"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            // 3. HKLM — удаляем конфликтующую запись
            String hklmExe = registryReadDefault("HKLM", server32); //$NON-NLS-1$
            log(removeBitness + "b LocalServer32 HKLM = " + hklmExe); //$NON-NLS-1$
            if (hklmExe != null && !hklmExe.isEmpty())
            {
                if (registryDeleteKey("HKLM", server32)) //$NON-NLS-1$
                    log("Удалён " + removeBitness + "b класс " + className //$NON-NLS-1$ //$NON-NLS-2$
                        + " из HKLM → ОС будет отдавать " + useBitness + "b"); //$NON-NLS-1$ //$NON-NLS-2$
                else
                {
                    if (attempt==2)
                    {
                        EclipseToastNotification.show("Регистрация COM", removeBitness
                            + "b класс из HKLM не удалён (нет прав, запустите EDT от имени администратора)");
                    }
                }
            }

            // 4. HKCU — удаляем конфликтующую запись
            String hkcuExe = registryReadDefault("HKCU", server32); //$NON-NLS-1$
            log(removeBitness + "b LocalServer32 HKCU = " + hkcuExe); //$NON-NLS-1$
            if (hkcuExe != null && !hkcuExe.isEmpty())
            {
                if (registryDeleteKey("HKCU", server32)) //$NON-NLS-1$
                    log("Удалён " + removeBitness + "b класс " + className + " из HKCU"); //$NON-NLS-1$ //$NON-NLS-2$
                else
                    if (attempt==2)
                    {
                        EclipseToastNotification.show("Регистрация COM", removeBitness + "b класс из HKCU не удалён"); //$NON-NLS-1$
                    }
            }

            log("Регистрация: \"" + exeFullName + "\" /RegServer -CurrentUser"); //$NON-NLS-1$ //$NON-NLS-2$
            Process p = new ProcessBuilder(exeFullName, "/RegServer", "-CurrentUser") //$NON-NLS-1$ //$NON-NLS-2$
                .redirectErrorStream(true).start();
            p.waitFor(30, TimeUnit.SECONDS);
            EclipseToastNotification.show(
                "Регистрация COM", //$NON-NLS-1$
                "Зарегистрирован " + className + " для текущего пользователя ОС", 3_000); //$NON-NLS-1$ //$NON-NLS-2$
            log("Регистрация COM завершена"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            log("RegisterComClass ошибка: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Читает значение по умолчанию (Default) ключа реестра через {@code reg.exe query}.
     * Результат парсится по маркеру {@code REG_SZ} — не зависит от локали ОС.
     */
    private static String registryReadDefault(String hive, String keyPath)
    {
        return registryReadValue(hive, keyPath, null);
    }

    /**
     * Читает именованное значение ключа реестра через {@code reg.exe query}.
     * Если {@code valueName} == null — читает значение по умолчанию ({@code /ve}).
     */
    private static String registryReadValue(String hive, String keyPath, String valueName)
    {
        try
        {
            String fullPath = expandHive(hive) + "\\" + keyPath; //$NON-NLS-1$
            ProcessBuilder pb = valueName == null
                ? new ProcessBuilder("reg", "query", fullPath, "/ve") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : new ProcessBuilder("reg", "query", fullPath, "/v", valueName); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Process p = pb.redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(5, TimeUnit.SECONDS);
            // Формат вывода: «    {name}    REG_SZ    {value}»
            // Тип может быть REG_SZ, REG_EXPAND_SZ, REG_DWORD
            for (String line : out.split("\n")) { //$NON-NLS-1$
                int idx = line.indexOf("REG_"); //$NON-NLS-1$
                if (idx >= 0)
                {
                    // после типа идут 4 и более пробелов, затем значение
                    String afterType = line.substring(idx);
                    int valStart = afterType.indexOf("    "); //$NON-NLS-1$
                    if (valStart >= 0) return afterType.substring(valStart).trim();
                }
            }
        }
        catch (Exception ignored) {}
        return null;
    }

    /**
     * Удаляет ключ реестра через {@code reg.exe delete /f}.
     * Возвращает {@code true} при успехе (exit code 0).
     */
    private static boolean registryDeleteKey(String hive, String keyPath)
    {
        try
        {
            String fullPath = expandHive(hive) + "\\" + keyPath; //$NON-NLS-1$
            Process p = new ProcessBuilder("reg", "delete", fullPath, "/f") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .redirectErrorStream(true).start();
            p.waitFor(5, TimeUnit.SECONDS);
            return p.exitValue() == 0;
        }
        catch (Exception ignored) { return false; }
    }

    private static String expandHive(String hive)
    {
        switch (hive.toUpperCase())
        {
            case "HKLM": return "HKEY_LOCAL_MACHINE"; //$NON-NLS-1$ //$NON-NLS-2$
            case "HKCU": return "HKEY_CURRENT_USER";  //$NON-NLS-1$ //$NON-NLS-2$
            default:     return hive;
        }
    }

    private void showError(String msg)
    {
        EclipseToastNotification.show("Ошибка подключения ИР", msg, 6_000); //$NON-NLS-1$
    }

    private void notifyListeners()
    {
        changeListeners.forEach(r -> { try { r.run(); } catch (Exception ignored) {} });
    }

    private static final DateTimeFormatter LOG_TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$

    static void log(String message)
    {
        String ts = LocalTime.now().format(LOG_TIME_FMT);
        System.out.println("[TormozitIR " + ts + "] " + message); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
