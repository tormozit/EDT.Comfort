package tormozit;


import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.model.AppArch;
import com._1c.g5.v8.dt.platform.services.model.Arch;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.wiring.ServiceSupplier;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.google.common.base.Strings;

public final class IRApplication
{
    // -----------------------------------------------------------------------
    // Синглтон
    // -----------------------------------------------------------------------
    static String MINIMUM_IR_VERSION = "8.19";

    public static String toastTitle()
    {
        return "Приложение ИР";
    }

    public static String irSubsystemTitle()
    {
        return "ИР";
    }

    private static final String WAIT_FOR_IR_CONNECTION_MSG = "Дождитесь подключения для вызова команд ИР"; //$NON-NLS-1$

    public static void notifyWaitForIrConnection()
    {
        ToastNotification.show(toastTitle(), WAIT_FOR_IR_CONNECTION_MSG, 5_000);
    }
    
    private static final IRApplication INSTANCE = new IRApplication();
    private static ServiceSupplier<IInfobaseAccessManager> infobaseAccessManagerSupplier = 
        ServiceAccess.supplier(IInfobaseAccessManager.class, Global.ourContext()); 
    public static IRApplication getInstance() { return INSTANCE; }
    /** Аналог ПапкаПередачиФайлов — временный транспортный каталог для передачи файлов из приложения ИР в EDT */
    static public String transportFolder = ""; // оперативный единый для всех приложений EDT и ИР буферный файл, не учитываем пересечения //$NON-NLS-1$
    /** Аналог выхИспользуемоеИмяФайлаПортативногоИР — путь к .epf или .1cd портативного ИР */
    static public String usedPortableFileName = ""; //$NON-NLS-1$

    private IRApplication() {}
    public enum State { IDLE, CONNECTING, CONNECTED }
    IRSession newSession(ExecutorService executor)
    {
        return new IRSession(State.CONNECTING, LocalDateTime.now(), 0, "", null, null, "", null, executor, null);
    }
    
    static private final Map<String, IRSession>  sessions           = new ConcurrentHashMap<>();
    static private final List<Runnable>          changeListeners    = new CopyOnWriteArrayList<>();
    /** Аналог ПапкаПортативногоИР — каталог, содержащий ирПортативный.epf и модули.
     *  Пусто = ещё не определён или не найден. */
    static private volatile String               portableIrFolder   = ""; //$NON-NLS-1$
    private final Map<String, Boolean> autoConnectMap = new ConcurrentHashMap<>();
    private final Map<String, Boolean> dynamicAutoUpdateMap = new ConcurrentHashMap<>();
    public void addChangeListener(Runnable l)    { if (l != null) changeListeners.add(l); }
    public void removeChangeListener(Runnable l) { changeListeners.remove(l); }
    public boolean isConnected(Object infobase)
    {
        String key = sessionKey((InfobaseReference)infobase);
        IRSession s = sessions.get(key);
        return s != null && s.state == State.CONNECTED;
    }

    /**
     * Возвращает момент успешного подключения, или {@code null} если нет сессии.
     */
    public LocalDateTime getSessionStart(Object element)
    {
        InfobaseReference infobase = ApplicationsViewHook.getInfobaseFromApplication(element);
        String key = sessionKey(infobase);
        IRSession s = sessions.get(key);
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
        IRSession s = sessions.get(key);
        return (s != null && s.state == State.CONNECTED) ? s.platformVersion : null;
    }

    public Object getAnyActiveDispatch()
    {
        for (IRSession s : sessions.values())
            if (s.state == State.CONNECTED && s.root != null)
                return s.root;
        return null;
    }

    public boolean isAutoConnect(Object element)
    {
        return isAutoConnect(ApplicationsViewHook.getInfobaseFromApplication(element));
    }

    public boolean isAutoConnect(InfobaseReference infobase)
    {
        return getInfobaseBooleanSetting(infobase, autoConnectMap,
            ComfortSettings.DEFAULT_IR_AUTO_CONNECT, ComfortSettings::isAutoConnect);
    }

    public void setAutoConnect(Object element, boolean auto)
    {
        setAutoConnect(ApplicationsViewHook.getInfobaseFromApplication(element), auto);
    }

    public void setAutoConnect(InfobaseReference infobase, boolean auto)
    {
        setInfobaseBooleanSetting(infobase, autoConnectMap, auto, ComfortSettings::setAutoConnect);
    }

    public boolean isDynamicAutoUpdate(Object element)
    {
        return isDynamicAutoUpdate(ApplicationsViewHook.getInfobaseFromApplication(element));
    }

    public boolean isDynamicAutoUpdate(InfobaseReference infobase)
    {
        return getInfobaseBooleanSetting(infobase, dynamicAutoUpdateMap,
            ComfortSettings.DEFAULT_IR_DYNAMIC_AUTO_UPDATE, ComfortSettings::isDynamicAutoUpdate);
    }

    public void setDynamicAutoUpdate(Object element, boolean enabled)
    {
        setDynamicAutoUpdate(ApplicationsViewHook.getInfobaseFromApplication(element), enabled);
    }

    public void setDynamicAutoUpdate(InfobaseReference infobase, boolean enabled)
    {
        setInfobaseBooleanSetting(infobase, dynamicAutoUpdateMap, enabled,
            ComfortSettings::setDynamicAutoUpdate);
    }

    private static boolean getInfobaseBooleanSetting(
        InfobaseReference infobase,
        Map<String, Boolean> cache,
        boolean defaultValue,
        java.util.function.Function<String, Boolean> readByUuid)
    {
        if (infobase == null)
            return defaultValue;
        String key = sessionKey(infobase);
        if (cache.containsKey(key))
            return cache.get(key);
        String uuid = extractInfobaseUuid(infobase);
        if (!uuid.isEmpty())
            return readByUuid.apply(uuid);
        return defaultValue;
    }

    private static void setInfobaseBooleanSetting(
        InfobaseReference infobase,
        Map<String, Boolean> cache,
        boolean value,
        java.util.function.BiConsumer<String, Boolean> writeByUuid)
    {
        if (infobase == null)
            return;
        String key = sessionKey(infobase);
        cache.put(key, value);
        String uuid = extractInfobaseUuid(infobase);
        if (!uuid.isEmpty())
            writeByUuid.accept(uuid, value);
        notifyListeners();
    }

    /** Инфобазы с активной IR-сессией в состоянии CONNECTED. */
    public List<InfobaseReference> getConnectedInfobases()
    {
        List<InfobaseReference> result = new ArrayList<>();
        for (IRSession s : sessions.values())
            if (s.state == State.CONNECTED && s.infobase != null)
                result.add(s.infobase);
        return result;
    }

    /**
     * Переподключает приложение ИР к указанной инфобазе: отключает старую сессию
     * и запускает фоновое подключение новой.
     */
    public void reconnectInfobase(InfobaseReference infobase)
    {
        if (infobase == null)
        {
            ToastNotification.show(toastTitle(), "Не указана инфобаза для переподключения ИР", 8_000); //$NON-NLS-1$
            return;
        }
        IInfobaseApplication application = findApplicationForInfobase(infobase);
        if (application == null)
        {
            ToastNotification.show(toastTitle(),
                "Не найдено приложение EDT для переподключения ИР", 8_000); //$NON-NLS-1$
            return;
        }
        connectInfobaseApplication(application);
    }

    private IInfobaseApplication findApplicationForInfobase(InfobaseReference infobase)
    {
        String targetKey = sessionKey(infobase);
        BundleContext ctx = Global.ourContext();
        if (ctx == null)
            return null;
        ServiceReference<IApplicationManager> ref = ctx.getServiceReference(IApplicationManager.class);
        if (ref == null)
            return null;
        IApplicationManager mgr = ctx.getService(ref);
        try
        {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            {
                if (!project.isOpen())
                    continue;
                for (IApplication app : mgr.getApplications(project))
                {
                    if (!(app instanceof IInfobaseApplication ibApp))
                        continue;
                    InfobaseReference ib = ApplicationsViewHook.getInfobaseFromApplication(ibApp);
                    if (ib != null && targetKey.equals(sessionKey(ib)))
                        return ibApp;
                }
            }
        }
        catch (Exception e)
        {
            Global.log("findApplicationForInfobase: " + e.getMessage()); //$NON-NLS-1$
        }
        finally
        {
            ctx.ungetService(ref);
        }
        return null;
    }

    public void connectInfobaseApplication(IInfobaseApplication element)
    {
        IRConnectDebug.logConnectStart(element);
        InfobaseReference infobase = ApplicationsViewHook.getInfobaseFromApplication(element);
        String key = sessionKey(infobase);

        IRSession existing = sessions.get(key);
        if (existing != null && existing.state == State.CONNECTED) {
            doDisconnect(key, existing, false);
        }
        else if (existing != null && existing.state == State.CONNECTING)
            return;
        IProject activeProject = (IProject) Global.getField(element, "project");
        if (activeProject == null)
        {
            // Диагностика issue #88: на некоторых версиях EDT поле "project" не резолвится,
            // но публичный метод getProject() работает (см. IRApplication.getSession(IDtProject)).
            Object viaMethod = Global.invoke(element, "getProject");
            IRConnectDebug.problem("Global.getField(element, \"project\") == null; "
                + "Global.invoke(element, \"getProject\") = " + viaMethod);
            if (viaMethod instanceof IProject)
                activeProject = (IProject) viaMethod;
        }
        final IProject resolvedProject = activeProject;
        IRConnectDebug.logResolvedInfobaseAndProject(element, infobase, resolvedProject);

        // Создаем поток-одиночку для этой сессии. Поток будет жить, пока сессия активна.
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "IR-COMThread-" + extractInfobaseUuid(infobase));
            t.setDaemon(true);
            return t;
        });

        IRSession connectingSession = newSession(executor);
        sessions.put(key, connectingSession);
        connectingSession.application = element;
        notifyListeners();

        // Запускаем подключение строго в контексте этого потока
        executor.submit(() -> {
            ComBridge.initComThread(); // Инициализируем STA для потока ОДИН раз при старте
            try
            {
                doConnect(resolvedProject, infobase);
            }
            catch (Exception e)
            {
                Global.log("Исключение в потоке подключения COM: " + e.getMessage());
            }
        });
    }
    
    /**
     * Фоновый метод — тост создаётся здесь (syncExec → Shell гарантирован),
     * закрывается в {@code finally} при любом исходе.
     */
    private void doConnect(IProject project, InfobaseReference infobase)
    {
        String appLabel = DesignerSessionPoolAccessor.nameOf(infobase);
        Shell connectingToast = ToastNotification.show(toastTitle(),
            "Подключается \"" + appLabel + "\". Отключить можно в списке приложений", 60_000);
        try
        {
            doConnectInternal(project, infobase);
        }
        catch (Exception e)
        {
            ToastNotification.show(toastTitle(), "Ошибка подключения: " + e.getMessage(), 10_000);
            String key = sessionKey(infobase);
            IRSession s = sessions.remove(key);
            if (s != null && s.executor != null) {
                s.executor.submit(() -> ComBridge.releaseComThread());
                s.executor.shutdown();
            }
            notifyListeners();
        }
        finally
        {
            ToastNotification.close(connectingToast);
        }
    }

    /**
     * Основная логика подключения — порт вложенного цикла из {@code ПодключениеИР()}.
     * @throws UnsupportedEncodingException 
     */
    private void doConnectInternal(IProject project, InfobaseReference infobase)
    {
        if (transportFolder == "")
        {
            try
            {
                transportFolder = Files.createTempDirectory("tormozit").toString();
            }
            catch (IOException e)
            {
                Global.logError("IRApplication", "createTempDirectory", e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }       
        String key = sessionKey(infobase);
        String connectionString = buildConnectionString(infobase, true);
        RuntimeInstallation runtimeInstallation = ApplicationsViewHook.getRuntimeInstallation(project, infobase);
        if (runtimeInstallation == null)
        {
            IRConnectDebug.logRuntimeInstallationNull(project, infobase);
            // Fallback: тот же путь, что EDT для «Запустить конфигуратор»
            IRSession currentSession = sessions.get(key);
            IInfobaseApplication application = currentSession != null ? currentSession.application : null;
            runtimeInstallation = resolveRuntimeViaApplication(project, application);
        }
        String platformVersion;
        boolean configBitness64;
        if (runtimeInstallation != null)
        {
            platformVersion = runtimeInstallation.getVersion().toString();
            configBitness64 = runtimeInstallation.getArch() == Arch.X86_64;
        }
        else
        {
            IRConnectDebug.log("RuntimeInstallation не определён ни одним путём — дефолтные значения"); //$NON-NLS-1$
            platformVersion = "8.3"; //$NON-NLS-1$
            configBitness64 = true;
        }
        String className = buildComClassName(platformVersion);
        String connectionStringNoPass = removePassword(connectionString);
        Object comDispatch = null;
        Object processObj = null;
        long pid = 0;
        boolean success = false;
        String descriptionOnError = ""; //$NON-NLS-1$
        long momentStart = System.currentTimeMillis();
        ForegroundWindowWatcher fgWatcher = new ForegroundWindowWatcher();
        fgWatcher.start();
        // До 2 попыток — аналог «Для НомерПопытки = 1 По 2»
        for (int attempt = 1; attempt <= 2; attempt++)
        {
            try
            {
                // ПолучитьПроцессОСЛкс(Неопределено, startMs, 2, "-Embedding")
                long timeMillis = System.currentTimeMillis();
                comDispatch = ComBridge.createComObject(className);
                processObj = WmiProcessHelper.findProcess(timeMillis, 2, "-Embedding");
                pid = WmiProcessHelper.getPid(processObj);
                boolean connected = ComBridge.connect(comDispatch, connectionString);
                if (connected) { 
                    success = true; 
                    break; 
                }
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
                    showError("Ошибка инициации приложения. Подробности в журнале регистрации."); //$NON-NLS-1$
                    sessions.remove(key); notifyListeners(); return;
                }
                if (attempt == 2)
                {
                    String msg = "Ошибка подключения ИР " + connectionStringNoPass //$NON-NLS-1$
                        + " через " + className + ":\n" + descriptionOnError; //$NON-NLS-1$ //$NON-NLS-2$
                    showError(msg);
                    if (!descriptionOnError.contains("Ошибка разделенного доступа")) //$NON-NLS-1$
                        ToastNotification.show(toastTitle(), "Зарегистрируйте " + className + " (/RegServer -CurrentUser).", 8_000); //$NON-NLS-1$ //$NON-NLS-2$
                    sessions.remove(key); notifyListeners(); return;
                }
                Global.log("Попытка " + attempt + " неудача. Повтор..."); //$NON-NLS-1$ //$NON-NLS-2$
                if (runtimeInstallation != null)
                {
                    registerComClass(className, runtimeInstallation.getLocation().getPath() + "1cv8.exe", attempt, configBitness64);
                }
            }
        }

        if (!success || comDispatch == null) 
        { 
            fgWatcher.stop();
            sessions.remove(key);
            notifyListeners(); 
            return; 
        }

        long   duration = (System.currentTimeMillis() - momentStart) / 1000;
        String title    = "ИР - " + connectionStringNoPass.split(";")[0]; //$NON-NLS-1$ //$NON-NLS-2$
        ComBridge.setProperty(comDispatch, "Visible", false);
        // УстановитьЗаголовок (8.3.10+) / УстановитьЗаголовокСистемы (8.3.9-)
        try
        {
            Object comApp = ComBridge.getProperty(comDispatch, "КлиентскоеПриложение");
            ComBridge.invoke(comApp, "УстановитьЗаголовок", title);
        }
        catch (Exception e)
        {
            ComBridge.invoke(comDispatch, "УстановитьЗаголовокСистемы", title); //$NON-NLS-1$
        }

        // Находим временную сессию, чтобы забрать созданный executor и application
        IRSession connectingSession = sessions.get(key);
        ExecutorService currentExecutor = connectingSession != null ? connectingSession.executor : null;
        IInfobaseApplication savedApplication = connectingSession != null ? connectingSession.application : null;

        IRSession irSession = new IRSession(
            State.CONNECTING, LocalDateTime.now(), pid, platformVersion,
            comDispatch, processObj, title, project, currentExecutor, infobase);
        irSession.application = savedApplication;
        sessions.put(key, irSession);
        notifyListeners();

        if (!resolveIrModules(comDispatch, irSession))
        {
            // МодулиИР = Неопределено → выхПодключеноНоНетПодсистемы = Истина
            sessions.remove(key);
            notifyListeners();
            return;
        }
        Object irCache  = irSession.getModule("ирКэш");  //$NON-NLS-1$
        Object irClient = irSession.getModule("ирКлиент"); //$NON-NLS-1$
        long irSubsystemVersion = 0;
        try
        {
            irSubsystemVersion = ComBridge.toLong(ComBridge.invoke(irCache, "НомерВерсииПодсистемыИРЛкс")); //$NON-NLS-1$
        }
        catch (Exception ignored) { irSubsystemVersion = 0; }

        if (irSubsystemVersion < Integer.parseInt(MINIMUM_IR_VERSION.replace(".", "")))
        {
            String versionMsg = String.format(
                "Обнаружена несовместимая версия подсистемы " + irSubsystemTitle() + ". Необходима версия %s и выше", MINIMUM_IR_VERSION);
            ToastNotification.show(toastTitle(), versionMsg, 5_000); //$NON-NLS-1$
            irSession.showWindow();
            try { ComBridge.invoke(irClient, "ОткрытьСправкуПоПодсистемеЛкс"); } //$NON-NLS-1$
            catch (Exception ignored) {}
        }
        long edtPid = ProcessHandle.current().pid(); // Конфигуратор.PID
        try
        {
            ComBridge.invoke(irClient, "ПодключитьСвязанныйКонфигураторЛкс", edtPid, transportFolder); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            String noRightsMsg = "Нет прав на подсистему " + irSubsystemTitle() + ": " + e.getMessage(); //$NON-NLS-1$
            // Если у БД нет ни одного пользователя — платформа вернёт специфичную ошибку
            String cs = buildConnectionString(infobase, false);
            if (!cs.contains("Usr=")) //$NON-NLS-1$
                noRightsMsg += ". Необходимо создать пользователя."; //$NON-NLS-1$
            ToastNotification.show(toastTitle(), noRightsMsg, 5_000); //$NON-NLS-1$
        }

        // МодулиИР.ирКлиент.ЗакрытьВсеЧужиеФормыЛкс()
        try { ComBridge.invoke(irClient, "ЗакрытьВсеЧужиеФормыЛкс"); } //$NON-NLS-1$
        catch (Exception ignored) {}
        try
        {
            Object bsp = ComBridge.getProperty(comDispatch, "СтандартныеПодсистемыКлиент"); //$NON-NLS-1$
            ComBridge.invoke(bsp, "ПропуститьПредупреждениеПередЗавершениемРаботыСистемы"); //$NON-NLS-1$
        }
        catch (Exception ignored) {}

        ComBridge.invoke(irClient, "УстановитьГитРепозиторийЛкс", resolveGitRepositoryPath(project)); //$NON-NLS-1$
        performInitialIrModuleSync(irCache, irSession);

        long ping = 0;
        String pingText = ""; //$NON-NLS-1$
        try
        {
            ping = ComBridge.toLong(ComBridge.invoke(irClient, "ЗамеритьПингДоСервераЛкс", pingText)); //$NON-NLS-1$
        }
        catch (Exception ignored) { ping = 0; } // ИР 8.06-

        IRSession connectedSession = new IRSession(
            State.CONNECTED, LocalDateTime.now(), irSession.pid, irSession.platformVersion,
            irSession.root, irSession.processObj, irSession.appTitle, irSession.project,
            irSession.executor, irSession.infobase);
        connectedSession.application = irSession.application;
        connectedSession.moduleRoot = irSession.moduleRoot;
        connectedSession.codeEditor = irSession.codeEditor;
        sessions.put(key, connectedSession);
        notifyListeners();

        String connectedMsg = title + " подключено за " + duration + " сек"; //$NON-NLS-1$ //$NON-NLS-2$
        if (duration > 30 || ping > 5)
            connectedMsg += ". Задержка канала до сервера — " + ping + " сек"; //$NON-NLS-1$ //$NON-NLS-2$

        // ═══════════════════════════════════════════════════════════════════════
        // Предупреждения для файловой базы ИР
        // ═══════════════════════════════════════════════════════════════════════
        boolean isFileBased = buildConnectionString(infobase, false).contains("File="); //$NON-NLS-1$
        if (isFileBased)
        {
//            // МодулиИР.ПолучитьВремяОжиданияБлокировкиДанных()
//            try
//            {
//                long lockWait = ComBridge.toLong(ComBridge.invoke(comDispatch, "ПолучитьВремяОжиданияБлокировкиДанных")); //$NON-NLS-1$
//                if (lockWait > 0)
//                    ToastNotification.show(toastTitle(), //$NON-NLS-1$
//                        "В файловой базе ИР включено ожидание блокировки данных. Рекомендую отключить.", 10_000); //$NON-NLS-1$
//            }
//            catch (Exception ignored) {}
            // МодулиИР.ирКлиент.ЛиВФайловойБазеЕстьВключенныеРегламентныеЗаданияЛкс()
            
            Boolean hasJobs = ComBridge.toBoolean(ComBridge.invoke(irClient, "ЛиВФайловойБазеЕстьВключенныеРегламентныеЗаданияЛкс")); //$NON-NLS-1$
            if (hasJobs)
                ToastNotification.show(toastTitle(), //$NON-NLS-1$
                    "В файловой базе ИР включены регламентные задания. Рекомендую отключить.\n" //$NON-NLS-1$
                    + "Если в коде есть их включение, подавите его проверкой файловой базы.", 5_000, //$NON-NLS-1$
                    () -> connectedSession.openJobConsole()); //$NON-NLS-1$
        }
        String[] forbiddenHandlers = {
            "ОбработчикОжиданияПроверкиДинамическогоИзмененияИБ", // БСП 2.0 //$NON-NLS-1$
            "ОбработчикДействийРезервногоКопирования",             // БСП 2.0 //$NON-NLS-1$
            "ОбработчикОжиданияСтандартныхПериодическихПроверок",  // БСП 2.0 //$NON-NLS-1$
        };
        for (String handlerName : forbiddenHandlers)
        {
            try { ComBridge.invoke(comDispatch, "ОтключитьОбработчикОжидания", handlerName); } //$NON-NLS-1$
            catch (Exception ignored) {}
        }
        ToastNotification.show(toastTitle(), connectedMsg, 3_000); //$NON-NLS-1$
        fgWatcher.restorePreviousWindow(pid); // Фокус могли украсть прикладные всплывающие уведомления приложения ИР 
        fgWatcher.stop();
    }

    /** Корень EDT-проекта на диске (каталог, содержащий {@code src/}), для {@code УстановитьГитРепозиторийЛкс}. */
    private static String resolveGitRepositoryPath(IProject project)
    {
        if (project == null || !project.isOpen())
            return ""; //$NON-NLS-1$
        org.eclipse.core.runtime.IPath location = project.getLocation();
        if (location == null)
            return ""; //$NON-NLS-1$
        if (!project.getFolder("src").exists()) //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        return location.toOSString();
    }

    /** Начальная синхронизация кэша модулей ИР из git (порт RDT ПодключениеИР). */
    private static void performInitialIrModuleSync(Object irCache, IRSession session)
    {
        ComBridge.invoke(irCache, "СостояниеПодготовкиКэшМДСеансаЛкс"); //$NON-NLS-1$
        Object codeEditor = ComBridge.invoke(irCache, "ПолеТекстаПрограммы", 0); //$NON-NLS-1$
        session.codeEditor = codeEditor;
        session.invokeCodeEditorQuiet("РазобратьТекущийКонтекст"); //$NON-NLS-1$
        session.invokeCodeEditorQuiet("ПодготовитьГлобальныйКонтекст"); //$NON-NLS-1$

        long changedCount = 0;
        try
        {
            // Функция ПодготовитьОбновлениеИзПапкиГита(Знач ОтсекатьДатой = Истина) Экспорт
            Object changedModules = session.invokeCodeEditorQuiet("ПодготовитьОбновлениеИзПапкиГита", false); //$NON-NLS-1$
            if (changedModules != null)
                changedCount = ComBridge.toLong(ComBridge.invoke(changedModules, "Количество")); //$NON-NLS-1$
            if (changedCount > 0)
            {
                session.invokeCodeEditorQuiet("ОбновитьКэшМодулейИзПапкиГита", changedModules); //$NON-NLS-1$
                Object platform = ComBridge.getProperty(codeEditor, "мПлатформа"); //$NON-NLS-1$
                ComBridge.invoke(platform, "ОчиститьКэшАнализатораЯзыка"); //$NON-NLS-1$
                session.invokeCodeEditorQuiet("ПодготовитьГлобальныйКонтекст"); //$NON-NLS-1$
            }
        }
        catch (RuntimeException e)
        {
            String detail = e.getMessage() != null ? e.getMessage() : e.toString(); //$NON-NLS-1$
            ToastNotification.show(
                toastTitle(),
                "Синхронизация модулей из Git в кэш ИР не выполнена.\n" //$NON-NLS-1$
                    + "Подключение сохранено, но кэш модулей ИР может быть неактуален.\n" //$NON-NLS-1$
                    + detail,
                12_000);
        }
        IRModuleSyncDebug.logGitSync(changedCount);
        session.resetPushedSignatures();
        session.pumpUserMessagesToUi();
    }

    // -----------------------------------------------------------------------
    // resolveIrModules — порт функции МодулиИР(ПодключениеИР, ЭтоПервоеПодключение, выхИмяФайла)
    // -----------------------------------------------------------------------

    /**
     * Определяет корень модулей ИР: расширение (встроено в конфигурацию) или портативный epf.
     * <ul>
     *   <li>Расширение: {@code comDispatch.ирПортативный} доступен → {@code root} содержит модули.</li>
     *   <li>Портативный: {@code comDispatch.ирПортативный} бросает исключение →
     *       загружаем {@code ирПортативный.epf} из {@link #portableIrFolder}.</li>
     * </ul>
     * Побочные эффекты:
     * <ul>
     *   <li>Записывает {@code session.portableRoot} (только для портативного варианта).</li>
     *   <li>Записывает {@code session.usedPortableFileName}.</li>
     * </ul>
     *
     * @return {@code true} — модули найдены и готовы к использованию через {@code session.getModule()};
     *         {@code false} — подсистема ИР не обнаружена (аналог {@code МодулиИР = Неопределено}).
     * @throws RuntimeException если версия ИР обновилась и требуется переподключение
     *         (аналог {@code ВызватьИсключение "Требуется переподключение ИР"}).
     */
    private boolean resolveIrModules(Object comDispatch, IRSession session)
    {
        String updateFlag = ""; //$NON-NLS-1$
        Object result     = null;
        try
        {
            // Проверяем доступность модуля ирПортативный.
            // Если не бросает — ИР встроен как расширение, модули живут прямо в comDispatch.
            // Комментарий из 1С: //ФлагОбновления = ПодключениеИР.ирПлатформа; — вызывает 200мс задержку
            ComBridge.getProperty(comDispatch, "ирПортативный"); //$NON-NLS-1$
            // ирПерехватКлавиатуры — проверка корректности встройки в модуль приложения
            try
            {
                ComBridge.getProperty(comDispatch, "ирПерехватКлавиатуры"); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                ToastNotification.show("ИР: предупреждение", //$NON-NLS-1$
                    "Подсистема ИР некорректно встроена в конфигурацию " //$NON-NLS-1$
                    + "(модуль приложения). Часть функций будет недоступна.", 5_000); //$NON-NLS-1$
            }
            result = comDispatch; // Результат = ПодключениеИР
        }
        catch (Exception embeddedEx)
        {
            // модуль ирПортативный не найден → пробуем портативный вариант (ирПортативный.epf)
            String folder = initPortableIrFolder();
            if (!folder.isEmpty())
            {
                try
                {
                    // Результат = ПодключениеИР.ВнешниеОбработки.ПолучитьФорму(folder + "\ирПортативный.epf")
                    Object extProcessors = ComBridge.getProperty(comDispatch, "ВнешниеОбработки"); //$NON-NLS-1$
                    Object portableForm  = ComBridge.invoke(extProcessors, "ПолучитьФорму", folder + "\\ирПортативный.epf"); //$NON-NLS-1$ //$NON-NLS-2$
                     // Результат.ЗапретитьАвтозапуск = Истина  (ИР 7.45+)
                    ComBridge.setProperty(portableForm, "ЗапретитьАвтозапуск", true); //$NON-NLS-1$
                    result = portableForm;
                }
                catch (Exception e)
                {
                    // Платформа 1С не позволяет ловить некоторые исключения — аналогично молчим
                    Global.log("Ошибка загрузки ирПортативный.epf: " + e.getMessage()); //$NON-NLS-1$
                }
            }
            if (result != null)
            {
                // выхИспользуемоеИмяФайлаПортативногоИР = Результат.ИспользуемоеИмяФайла
                try
                {
                    Object fn = ComBridge.getProperty(result, "ИспользуемоеИмяФайла"); //$NON-NLS-1$
                    usedPortableFileName = fn instanceof String ? (String) fn : ""; //$NON-NLS-1$
                }
                catch (Exception ignored) {}
                // Если не открыта — открываем; если после открытия всё равно закрыта → ИР обновился
                try
                {
                    boolean isOpen = ComBridge.toBoolean(ComBridge.invoke(result, "Открыта")); //$NON-NLS-1$
                    if (!isOpen)
                    {
                        ComBridge.invoke(result, "Открыть"); //$NON-NLS-1$
                        isOpen = ComBridge.toBoolean(ComBridge.invoke(result, "Открыта")); //$NON-NLS-1$
                        if (!isOpen)
                            updateFlag = "ВерсияИРОбновлена"; //$NON-NLS-1$
                    }
                }
                catch (Exception e)
                {
                    Global.log("Ошибка открытия портативного ирПортативный.epf: " + e.getMessage()); //$NON-NLS-1$
                }
                session.moduleRoot = result; // getModule() будет обращаться к форме, а не к comDispatch
            }
            else
            {
                // Ни расширение, ни портативный — аналог нижней ветки «Иначе» в 1С:
                // ОсвежитьПодключениеИР + ЗапуститьСистему "/Execute installer.epf"
                String installerEpf  = "ирУстановщикРасширения.epf"; //$NON-NLS-1$
                String installerPath = folder.isEmpty() ? "" //$NON-NLS-1$
                    : folder + "\\Модули\\" + installerEpf; //$NON-NLS-1$

                String reason;
                if (WmiProcessHelper.findProcess(System.currentTimeMillis(), 60, installerEpf) != null)
                {
                    reason = " Активируйте окно установщика расширения."; //$NON-NLS-1$
                }
                else
                {
                    reason = " Поэтому запускаем установщик расширения."; //$NON-NLS-1$
                    if (!installerPath.isEmpty())
                    {
                        ComBridge.invoke(comDispatch, "ЗапуститьСистему", //$NON-NLS-1$
                        "/Execute\"" + installerPath + "\""); 
                    }
                }
                ToastNotification.show(toastTitle(), 
                    "В базе нет подсистемы " + irSubsystemTitle() + " и портативный вариант не найден." //$NON-NLS-1$
                    + reason, 10_000);
                return false;
            }
        }

        // Если портативный обновился — перезапускаем подключение
        if ("ВерсияИРОбновлена".equals(updateFlag)) //$NON-NLS-1$
        {
            ToastNotification.show(toastTitle(), "Запущено подключение новой версии ИР.", 3_000); //$NON-NLS-1$
            // TODO: ЗакрытьПриложениеИР + ЗапуститьПодключениеИР → disconnect + scheduleConnect
            throw new RuntimeException("Требуется переподключение ИР — версия обновилась"); //$NON-NLS-1$
        }

        return result != null;
    }
    
    /**
     * Аналог инициализации ПапкаПортативногоИР в МодулиИР().
     * Возвращает путь к папке с ирПортативный.epf, или пустую строку если папка не готова.
     * <p>Если ИР.zip не найден — пытается скачать с devtool1c.ucoz.ru (аналог автозагрузки в 1С).
     */
    private static String initPortableIrFolder()
    {
        if (!portableIrFolder.isEmpty()) return portableIrFolder;
        // ПапкаПортативногоИР = ТекущийКаталог() + "\" + ТурбоКонф.ПолучитьКаталогСкрипта() + "\ИР"
        // В Java: директория состояния плагина + "\ИР"
        String stateDir = Platform.getStateLocation(
            FrameworkUtil.getBundle(IRApplication.class)).toOSString();
        String candidate = stateDir + "\\ИР"; //$NON-NLS-1$
        File dir = new File(candidate);
        if (!dir.exists()) dir.mkdirs();
        File zipFile = new File(candidate + "\\ИР.zip"); //$NON-NLS-1$
        if (!zipFile.exists())
        {
           java.net.HttpURLConnection conn = null;
           try
            {
                // Аналог: СоединениеHTTP + ЗагрузитьФайл("https://devtool1c.ucoz.ru/load/0-0-0-6-20")
               java.net.URL url = new java.net.URL("https://devtool1c.ucoz.ru/load/0-0-0-6-20"); //$NON-NLS-1$
               conn = (java.net.HttpURLConnection) url.openConnection();
               conn.setConnectTimeout(5_000);
               conn.setReadTimeout(30_000);
               try (java.io.InputStream in = conn.getInputStream();
                    java.io.FileOutputStream out = new java.io.FileOutputStream(zipFile))
               {
                   in.transferTo(out);
               }
               extractZip(zipFile, dir);
            }
           catch (Exception e)
           {
               ToastNotification.show(toastTitle(), "Ошибка скачивания портативного варианта ИР: " + e.getMessage(), 6_000); //$NON-NLS-1$
               ToastNotification.show(toastTitle(),  
                   "В базе нет подсистемы " + irSubsystemTitle() + " и в " + candidate + " нет ее портативного варианта.\n"
                   + " Прямые ссылки на ИР:\n"
                   + "Расширение: https://devtool1c.ucoz.ru/load/osnovnye/ustanovshhik_varianta_rasshirenie/1-1-0-21\n" //$NON-NLS-1$
                   + "Портативный: https://devtool1c.ucoz.ru/load/osnovnye/portativnye_instrumenty_razrabotchika_dlja_1s_8_2/1-1-0-6", //$NON-NLS-1$
                   10_000);
           }
           finally { if (conn!= null) conn.disconnect(); }
           return ""; //$NON-NLS-1$
        }
        portableIrFolder = candidate;
        return portableIrFolder;
    }

    /** Распаковывает zip-архив в целевую папку. */
    private static void extractZip(File zipFile, File targetDir) throws Exception
    {
        try (java.util.zip.ZipInputStream zis =
                new java.util.zip.ZipInputStream(new java.io.FileInputStream(zipFile), Charset.forName("cp866")))
        {
            java.util.zip.ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null)
            {
                File out = new File(targetDir, entry.getName());
                if (entry.isDirectory()) { out.mkdirs(); }
                else
                {
                    out.getParentFile().mkdirs();
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out))
                    {
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Отключение (аналог ЗакрытьПриложениеИР)
    // -----------------------------------------------------------------------

    static public void disconnect(InfobaseReference infobase)
    {
        String key = sessionKey(infobase);
        IRSession session = sessions.get(key);
        if (session == null || session.state == State.IDLE) return;
        
        // Закрываем строго в том же потоке, где выполнялась работа
        session.executor.submit(() -> doDisconnect(key, session, false));
    }

    /** Завершает все подключённые приложения ИР (при выходе из EDT). */
    public static void disconnectAll()
    {
        for (String key : List.copyOf(sessions.keySet()))
        {
            IRSession session = sessions.remove(key);
            if (session == null || session.state == State.IDLE)
                continue;
            shutdownSession(key, session);
        }
        notifyListeners();
    }

    private static void shutdownSession(String key, IRSession session)
    {
        ExecutorService ex = session.executor;
        if (ex == null)
        {
            killSessionProcess(session);
            return;
        }
        if (ex.isShutdown())
            return;
        if (session.state == State.CONNECTING)
        {
            ex.shutdownNow();
            awaitExecutor(ex, 3);
            return;
        }
        try
        {
            Future<?> f = ex.submit(() -> {
                try
                {
                    doDisconnect(key, session, true);
                }
                finally
                {
                    ComBridge.releaseComThread();
                }
            });
            f.get(15, TimeUnit.SECONDS);
        }
        catch (Exception e)
        {
            Global.log("IRApplication.disconnectAll: " //$NON-NLS-1$
                + (session.appTitle != null ? session.appTitle : key)
                + " — " + e.getMessage()); //$NON-NLS-1$
            killSessionProcess(session);
        }
        finally
        {
            ex.shutdown();
            awaitExecutor(ex, 5);
        }
    }

    private static void awaitExecutor(ExecutorService ex, int timeoutSec)
    {
        try
        {
            ex.awaitTermination(timeoutSec, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static void killSessionProcess(IRSession session)
    {
        if (session.root != null)
        {
            try
            {
                ComBridge.invoke(session.root, "ЗавершитьРаботуСистемы", false); //$NON-NLS-1$
                return;
            }
            catch (Exception ignored) {}
        }
        if (session.pid > 0)
        {
            Object proc = session.processObj != null ? session.processObj
                : WmiProcessHelper.findProcessByPid(session.pid);
            WmiProcessHelper.terminate(proc, session.pid);
        }
    }

    static private void doDisconnect(String key, IRSession session, boolean quiet)
    {
        boolean killed = false;
        if (session.root != null)
        {
            try
            {
                ComBridge.invoke(session.root, "ЗавершитьРаботуСистемы", false);
            }
            catch (Exception e)
            {
                Object proc = session.processObj != null ? session.processObj
                    : WmiProcessHelper.findProcessByPid(session.pid);
                WmiProcessHelper.terminate(proc, session.pid);
                killed = true;
            }
        }
        else if (session.pid > 0)
        {
            Object proc = WmiProcessHelper.findProcessByPid(session.pid);
            WmiProcessHelper.terminate(proc, session.pid);
            killed = true;
        }
        if (!quiet)
        {
            ToastNotification.show(toastTitle(), 
                killed ? "Процесс завершен принудительно." //$NON-NLS-1$
                       : "Отключение займет несколько секунд.", //$NON-NLS-1$
                3_000);
        }
        session.resetPushedSignatures();
        sessions.remove(key);
        if (!quiet)
            notifyListeners();
    }

    // =======================================================================
    // Резолв RuntimeInstallation через LaunchRuntimeInstallationProvider
    // (тот же путь, что EDT использует для «Запустить конфигуратор»)
    // =======================================================================

    private static RuntimeInstallation resolveRuntimeViaApplication(IProject project, IInfobaseApplication application)
    {
        if (project == null || application == null)
            return null;
        try
        {
            Object provider = Class.forName(
                "com._1c.g5.v8.dt.debug.core.LaunchRuntimeInstallationProvider") //$NON-NLS-1$
                .getDeclaredConstructor().newInstance();
            @SuppressWarnings("unchecked")
            Optional<IResolvableRuntimeInstallation> opt =
                (Optional<IResolvableRuntimeInstallation>) Global.invoke(
                    provider, "getInstallation", project, application); //$NON-NLS-1$
            if (opt == null || !opt.isPresent())
            {
                IRConnectDebug.log("LaunchRuntimeInstallationProvider.getInstallation вернул empty"); //$NON-NLS-1$
                return null;
            }
            IResolvableRuntimeInstallation resolvable = opt.get();
            IRConnectDebug.log("resolvable из LaunchRuntimeInstallationProvider: " + resolvable.getClass().getName()); //$NON-NLS-1$
            try
            {
                RuntimeInstallation result = resolvable.resolve(
                    List.of("com._1c.g5.v8.dt.platform.services.core.componentTypes.ThickClient"), //$NON-NLS-1$
                    AppArch.X86_64);
                if (result != null)
                    return result;
            }
            catch (Exception ignored) {}
            try
            {
                return resolvable.resolve(
                    List.of("com._1c.g5.v8.dt.platform.services.core.componentTypes.ThickClient"), //$NON-NLS-1$
                    AppArch.X86);
            }
            catch (Exception ignored) {}
            return null;
        }
        catch (Exception e)
        {
            IRConnectDebug.log("resolveRuntimeViaApplication: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    public static String buildConnectionString(InfobaseReference infobase, boolean withUser)
    {
        IConnectionString connectionString = infobase.getConnectionString();
        String result = (String) Global.call(connectionString, "asConnectionString"); //$NON-NLS-1$
        if (result != null && !result.isEmpty()
                && (result.contains("File=") || result.contains("Srvr="))) //$NON-NLS-1$ //$NON-NLS-2$
        {
            if (withUser)
            {
                IInfobaseAccessSettings access;
                try
                {
                    access = infobaseAccessManagerSupplier.get().resolveSettings(infobase);
                }
                catch (CoreException e)
                {
                    // TODO Auto-generated catch block
                    throw new RuntimeException("IInfobaseAccessSettings.: " + e.getMessage(), e);
                }
                if (!Strings.isNullOrEmpty(access.userName()))
                {
                    result += "Usr=\"" + access.userName() + "\";";
                }
                if (!Strings.isNullOrEmpty(access.password()))
                {
                    result += "Pwd=\"" + access.password() + "\";";
                }
            }
            return result;
        }
        return ""; //$NON-NLS-1$
    }

    private static String sessionKey(InfobaseReference infobase)
    {
        String uuid = extractInfobaseUuid(infobase);
        return uuid.isEmpty() ? String.valueOf(System.identityHashCode(infobase)) : uuid;
    }

    static String extractInfobaseUuid(Object infobase)
    {
        if (infobase == null)
            return ""; //$NON-NLS-1$
        Object uuid = Global.call(infobase, "getUuid"); //$NON-NLS-1$
        if (uuid instanceof String s && !s.isEmpty())
            return s;
        if (uuid instanceof java.util.UUID u)
            return u.toString();

        java.util.regex.Matcher m = DesignerSessionPoolAccessor.UUID_PATTERN
            .matcher(infobase.toString());
        return m.find() ? m.group() : ""; //$NON-NLS-1$
    }

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
        String subKey        = configBitness64 ? "\\WOW6432Node" : "";  // ветка противоположной разрядности //$NON-NLS-1$ //$NON-NLS-2$
        String removeBitness = configBitness64 ? "32" : "64"; //$NON-NLS-1$ //$NON-NLS-2$
        String useBitness    = configBitness64 ? "64" : "32"; //$NON-NLS-1$ //$NON-NLS-2$

        Global.log("Регистрация COM: JVM=" + useBitness + "b класс=" + className); //$NON-NLS-1$ //$NON-NLS-2$

        // 1. CLSID: HKLM\SOFTWARE\Classes\{className}\CLSID → (Default)
        String clsid = registryReadDefault("HKLM", "SOFTWARE\\Classes\\" + className + "\\CLSID"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (clsid == null || clsid.isEmpty())
        {
            Global.log("CLSID для " + className + " не найден в реестре, регистрация пропущена"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        Global.log("CLSID " + className + " = " + clsid); //$NON-NLS-1$ //$NON-NLS-2$
        String server32 = "SOFTWARE\\Classes" + subKey + "\\CLSID\\" + clsid + "\\LocalServer32"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String hklmExe = registryReadDefault("HKLM", server32); //$NON-NLS-1$
        Global.log(removeBitness + "b LocalServer32 HKLM = " + hklmExe); //$NON-NLS-1$
        if (hklmExe != null && !hklmExe.isEmpty())
        {
            if (registryDeleteKey("HKLM", server32)) //$NON-NLS-1$
                Global.log("Удалён " + removeBitness + "b класс " + className //$NON-NLS-1$ //$NON-NLS-2$
                    + " из HKLM → ОС будет отдавать " + useBitness + "b"); //$NON-NLS-1$ //$NON-NLS-2$
            else
            {
                if (attempt==2)
                {
                    ToastNotification.show(toastTitle(), "Запустите EDT от имени администратора, чтобы он удалил " + removeBitness + "b класс " + className + " из HKLM");
                }
            }
        }
        String hkcuExe = registryReadDefault("HKCU", server32); //$NON-NLS-1$
        Global.log(removeBitness + "b LocalServer32 HKCU = " + hkcuExe); //$NON-NLS-1$
        if (hkcuExe != null && !hkcuExe.isEmpty())
        {
            if (registryDeleteKey("HKCU", server32)) //$NON-NLS-1$
                Global.log("Удалён " + removeBitness + "b класс " + className + " из HKCU"); //$NON-NLS-1$ //$NON-NLS-2$
            else
                if (attempt==2)
                {
                    ToastNotification.show(toastTitle(), "Ошибка удаления " + removeBitness + "b класса из HKCU"); //$NON-NLS-1$
                }
        }
        try
        {
            Process p = new ProcessBuilder(exeFullName, "/RegServer", "-CurrentUser").redirectErrorStream(true).start();
            p.waitFor(30, TimeUnit.SECONDS);
            ToastNotification.show(toastTitle(),
                "Из-за ошибки подключения регистрируем класс " + className + " для текущей версии платформы 1С и текущего пользователя ОС", 3_000);
        }
        catch (Exception e)
        {
            ToastNotification.show(toastTitle(),
                "Ошибка регистрации " + className + " для текущего пользователя ОС: " + e.getMessage(), 3_000); //$NON-NLS-1$ //$NON-NLS-2$
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
        ToastNotification.show(toastTitle(), "Ошибка подключения: " + msg, 6_000); //$NON-NLS-1$
    }

    static private void notifyListeners()
    {
        Runnable notify = () -> changeListeners.forEach(r -> {
            try
            {
                r.run();
            }
            catch (Exception ignored)
            {
            }
        });
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            notify.run();
            return;
        }
        if (display.getThread() == Thread.currentThread())
            notify.run();
        else
            display.asyncExec(notify);
    }

    private static final DateTimeFormatter LOG_TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$

    /**
     * Есть ли подключённая и живая IR-сессия для проекта (без автоподключения).
     * Состояние {@link State#CONNECTING} считается «не подключено».
     */
    public static boolean hasConnectedSession(IDtProject dtProject)
    {
        if (dtProject == null)
            return false;
        IProject wsProject = dtProject.getWorkspaceProject();
        if (wsProject == null)
            return false;
        for (IRSession session : sessions.values())
        {
            if (session.project != wsProject)
                continue;
            if (session.state == State.CONNECTING)
                return false;
            if (session.state == State.CONNECTED && checkAlive(session))
                return true;
        }
        return false;
    }

    /**
     * Проверка подключения для включения горячих клавиш ИР: без COM-probe {@link #checkAlive},
     * чтобы не блокировать UI и не ложно отключать привязки сразу после connect.
     */
    public static boolean hasConnectedSessionForKeys(IDtProject dtProject)
    {
        if (dtProject == null)
            return false;
        IProject wsProject = dtProject.getWorkspaceProject();
        if (wsProject == null)
            return false;
        for (IRSession session : sessions.values())
        {
            if (session.project != wsProject)
                continue;
            if (session.state == State.CONNECTED && session.isProcessAlive())
                return true;
        }
        return false;
    }

    /** Есть ли хотя бы одна подключённая IR-сессия (для restore, когда фокус не в BSL-редакторе). */
    public static boolean hasAnyConnectedSessionForKeys()
    {
        for (IRSession session : sessions.values())
        {
            if (session.state == State.CONNECTED && session.isProcessAlive())
                return true;
        }
        return false;
    }

    /**
     * Возвращает уже подключённую сессию ИР для проекта, не инициируя подключение.
     */
    public static IRSession getConnectedSession(IDtProject dtProject)
    {
        if (dtProject == null)
            return null;
        IProject wsProject = dtProject.getWorkspaceProject();
        for (IRSession session : sessions.values())
        {
            if (session.project != wsProject)
                continue;
            if (session.state == State.CONNECTED && checkAlive(session))
                return session;
        }
        return null;
    }

    /**
     * Берем любую активную сессию IRApplication.IRSession от main проекта. Если ее нет то подключаем от основного приложения. Если его нет то подключаем от первого приложения.
    */
    public static IRSession getSession(IDtProject dtProject)
    {
        if (dtProject == null)
            return null;
        IProject wsProject = dtProject.getWorkspaceProject();
        // Сначала ищем любую подключенную сессию этого проекта, отдавая предпочтение основному приложению
        for (Map.Entry<String, IRSession> entry : sessions.entrySet())
        {
            IRSession session = entry.getValue();
            if (session.project != wsProject)
                continue;
            if (session.state == State.CONNECTING)
            {
                notifyWaitForIrConnection();
                return null;
            }
            if (session.state == State.CONNECTED && checkAlive(session))
                return session;
        }
        // Ищем любое приложение этого проекта, отдавая предпочтение основному приложению
        BundleContext ctx = Global.ourContext();
        ServiceReference<Object>[] refs = null;
        try
        {
            refs = (ServiceReference<Object>[]) ctx.getAllServiceReferences(null, null);
        }
        catch (Exception e)
        { return null; }
        IApplicationManager appManager = ctx.getService(ctx.getServiceReference(IApplicationManager.class));
        IInfobaseApplication application = null;
        Optional<IApplication> defaultAppOptional = appManager.getDefaultApplication(dtProject.getWorkspaceProject());
        if (defaultAppOptional.isPresent() && defaultAppOptional.get() instanceof IInfobaseApplication)
        {
            application = (IInfobaseApplication) defaultAppOptional.get();
        }
        if (application==null)
        {
            List<IApplication> apps = appManager.getApplications(dtProject.getWorkspaceProject());
            for (IApplication elem : apps)
            {
                if (elem instanceof IInfobaseApplication)
                    if (Global.invoke(elem, "getProject") == dtProject.getWorkspaceProject())
                    {
                        application = (IInfobaseApplication)elem;
                        break;
                    }
            } 
        }
        if (application == null)
            return null;            
        getInstance().connectInfobaseApplication(application);
        notifyWaitForIrConnection();
        return null;
    }
    
    private static boolean checkAlive(IRSession session)
    {
        if (session.state != State.CONNECTED)
            return false;
        if (!session.isProcessAlive())
            return false;

        Future<Boolean> future = session.executor.submit(() -> {
           try {
                ComBridge.getProperty(session.root, "Visible"); 
            }
            catch (Exception e) {
                return false;
            }
            return true;
        });

        try {
            // Ждем завершения максимум 1 секунд (так как база может отвечать до 20 сек)
            return future.get(1, TimeUnit.SECONDS); 
        }
        catch (TimeoutException e) {
            // Если вышло время ожидания — принудительно прерываем задачу в экзекьюторе
            future.cancel(true); 
            return true;
        }
        catch (Exception e) {
            // Для всех остальных ошибок (InterruptedException, ExecutionException)
            return false;
        }
    }

    public static IRSession getSession(long pid)
    {
        for (Map.Entry<String, IRSession> entry : sessions.entrySet())
        {
            String key = entry.getKey();
            IRSession session = entry.getValue();
            if (session.pid == pid && session.state == State.CONNECTED && checkAlive(session))
                return session;
        }
        return null;
    }
}
