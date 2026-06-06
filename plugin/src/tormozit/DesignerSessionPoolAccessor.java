package tormozit;


import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Рефлексивная обёртка над {@code DesignerSessionPool} EDT.
 *
 * <h3>Как работает</h3>
 * <ul>
 *   <li>Пул находится в полях сервиса через рекурсивное сканирование OSGi-сервисов EDT
 *       (стратегия 3, бандл {@code com._1c.g5.v8.dt.platform.services.core}).</li>
 *   <li>Активные соединения хранятся в поле {@code cachedConnections}
 *       (тип {@code Map}) с ключами вида {@code "uuid:version"}.</li>
 *   <li>Сопоставление элемента дерева ({@code InfobaseApplication}) с ключом пула
 *       выполняется через {@code infobase.uuid} → ключ начинается с этого UUID.</li>
 * </ul>
 */
public final class DesignerSessionPoolAccessor
{
    // -----------------------------------------------------------------------
    // Константы
    // -----------------------------------------------------------------------

    static final String POOL_CLASS_NAME =
        "com._1c.g5.v8.dt.internal.platform.services.core.runtimes.execution.DesignerSessionPool"; //$NON-NLS-1$

    private static final String EDT_PKG_PREFIX   = "com._1c.g5"; //$NON-NLS-1$
    private static final String PLUGIN_ID         = "tormozit"; //$NON-NLS-1$
    private static final int    POLL_INTERVAL_SEC = 2;
    private static final int    SCAN_DEPTH        = 4;

    /** UUID-паттерн для разбора строки-ключа пула вида "uuid:version". */
    static final Pattern UUID_PATTERN = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"); //$NON-NLS-1$

    // -----------------------------------------------------------------------
    // Singleton
    // -----------------------------------------------------------------------

    private static volatile DesignerSessionPoolAccessor INSTANCE;

    public static DesignerSessionPoolAccessor getInstance()
    {
        if (INSTANCE == null)
            synchronized (DesignerSessionPoolAccessor.class)
            {
                if (INSTANCE == null)
                    INSTANCE = new DesignerSessionPoolAccessor();
            }
        return INSTANCE;
    }

    private DesignerSessionPoolAccessor() {}
    volatile Object    pool; // com._1c.g5.v8.dt.internal.platform.services.core.runtimes.execution.DesignerSessionPool - не смог напрямую тип указать
    final List<Field>  mapFields      = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Сессии
    // -----------------------------------------------------------------------

    /**
     * Ключ пула (строка "uuid:version") → время первого обнаружения.
     */
    private final ConcurrentHashMap<Object, LocalDateTime> firstSeenTimes =
        new ConcurrentHashMap<>();

    private volatile Set<Object> prevKeys = Collections.emptySet();

    // -----------------------------------------------------------------------
    // Слушатели / планировщик
    // -----------------------------------------------------------------------

    private final List<Runnable>              changeListeners = new CopyOnWriteArrayList<>();
    private volatile ScheduledExecutorService scheduler;

    // =======================================================================
    // Публичный API
    // =======================================================================

    public synchronized void startPolling()
    {
        if (scheduler != null && !scheduler.isShutdown()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread t = new Thread(r, "DesignerSessionPool-Poller"); //$NON-NLS-1$
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::poll, 1, POLL_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    public synchronized void stopPolling()
    {
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
    }

    public void addChangeListener(Runnable l)    { if (l != null) changeListeners.add(l); }
    public void removeChangeListener(Runnable l) { changeListeners.remove(l); }

    public LocalDateTime getSessionStart(Object poolKey)
    {
        return poolKey != null ? firstSeenTimes.get(poolKey) : null;
    }

    public Set<Object> getActiveKeys()
    {
        return Collections.unmodifiableSet(firstSeenTimes.keySet());
    }

    // тип Object важен
    public boolean isConnected(Object infobase) {
        return findPoolKey((InfobaseReference)infobase) != null;
    }

    public Object findPoolKey(InfobaseReference infobase)
    {
        UUID uuid = extractInfobaseUuid(infobase);
        for (Object k : getActiveKeys())
            if (Global.getField(k, "infobaseUuid") == uuid) 
                return k;
        return null;
    }

    /**
     * Извлекает UUID инфобазы из элемента дерева.
     * Цепочка: el → getInfobase()/поле "infobase" → getUuid() / regex из toString().
     */
    private static UUID extractInfobaseUuid(InfobaseReference infobase)
    {
        UUID uuid = (UUID) Global.call(infobase, "getUuid"); //$NON-NLS-1$
        if (uuid instanceof UUID)
            return uuid;
        return null;
    }

    static String getPlatformVersionFromActiveSession(InfobaseReference infobase)
    {
        Set<Object> keys = DesignerSessionPoolAccessor.getInstance().getActiveKeys();
        for (Object k : keys)
        {
            if (k == infobase.getUuid())
            {
                String version = (String)Global.getField(k, "installationVersionWithBuild");
                return version;
            }
        }
        return "8.3.0.0"; //$NON-NLS-1$
    }
    
    /**
     * Преобразует результат {@code RuntimeInstallation.getLocation()} в путь к директории.
     *
     * <p>{@code getLocation()} может возвращать {@code String}, {@code java.net.URI}
     * или EMF-URI вида {@code "file:/C:/Program Files/1cv8/8.3.25.1234"}.
     */
    private static String locationToDir(Object loc)
    {
        if (loc == null) return null;
        String s = loc.toString().trim();
        if (s.startsWith("file:/")) //$NON-NLS-1$
        {
            s = s.substring(6).replace('/', File.separatorChar);
            // Windows: убираем ведущий \ перед "C:\"
            if (s.startsWith("\\") && s.length() > 2 && s.charAt(2) == ':') //$NON-NLS-1$
                s = s.substring(1);
        }
        // Убираем trailing separator
        while (s.endsWith(File.separator) || s.endsWith("/")) //$NON-NLS-1$
            s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? null : s;
    }

    /**
     * Получает путь к исполняемому файлу процесса через {@code ProcessHandle}.
     * Работает на Java 9+ (EDT использует Java 17).
     */
    private static String exeFromProcess(Process process)
    {
        try
        {
            return ProcessHandle.of(process.pid())
                .flatMap(h -> h.info().command())
                .orElse(null);
        }
        catch (Exception ignored) { return null; }
    }
    
    // -----------------------------------------------------------------------
    // release()
    // -----------------------------------------------------------------------

    /**
     * Вызывает com._1c.g5.v8.dt.internal.platform.services.core.runtimes.execution.DesignerAgentConnection.release()
     * TODO переделать на com._1c.g5.v8.dt.internal.platform.services.core.infobases.sync.connections.DesignerSessionInfobaseConnection
     */
    public void release(Object poolKey)
    {
        ensurePool();
        if (pool == null || poolKey == null) return;
        Object connVal = mapValue(poolKey); // DesignerAgentConnection или null
        Object[] cands = connVal != null
            ? new Object[]{ connVal, poolKey }
            : new Object[]{ poolKey };
        for (Object arg : cands)
            try { 
                Method m = connVal.getClass().getMethod("release", boolean.class, boolean.class);
                m.invoke(connVal, true, true);
                Map cachedConnections = (Map) Global.getField(pool, "cachedConnections");
                cachedConnections.remove(poolKey);
                return; 
            }
            catch (Exception ignored) {
                Global.log("DesignerAgentConnection.release() → fail");
            }
    }

    // =======================================================================
    // Опрос
    // =======================================================================

    private void poll()
    {
        ensurePool();
        if (pool == null) return;

        Set<Object> cur     = collectPoolKeys();
        Set<Object> added   = new HashSet<>(cur); added.removeAll(prevKeys);
        Set<Object> removed = new HashSet<>(prevKeys); removed.removeAll(cur);

        if (!added.isEmpty() || !removed.isEmpty())
        {
            LocalDateTime now = LocalDateTime.now();
            added.forEach(k -> firstSeenTimes.put(k, now));
            removed.forEach(firstSeenTimes::remove);
            prevKeys = cur;
            Global.log("Сессии: +" + added.size() + " -" + removed.size() //$NON-NLS-1$ //$NON-NLS-2$
                + " итого=" + firstSeenTimes.size() + " keys=" + cur); //$NON-NLS-1$ //$NON-NLS-2$
            notifyListeners();
        }
    }

    @SuppressWarnings("unchecked")
    private Set<Object> collectPoolKeys()
    {
        Set<Object> result = new HashSet<>();
        for (Field f : mapFields)
            try
            {
                Object v = f.get(pool);
                if (v instanceof Map)
                    result.addAll(((Map<Object, Object>) v).keySet());
            }
            catch (Exception ignored) {}
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object mapValue(Object key)
    {
        for (Field f : mapFields)
            try
            {
                Object v = f.get(pool);
                if (v instanceof Map)
                {
                    Object r = ((Map<Object, Object>) v).get(key);
                    if (r != null) return r;
                }
            }
            catch (Exception ignored) {}
        return null;
    }

    private synchronized void ensurePool()
    {
        if (pool != null) return;
        BundleContext ctx = Global.ourContext();
        if (ctx == null) return;
        ServiceReference<Object>[] refs = null;
        try
        {
            refs = (ServiceReference<Object>[]) ctx.getAllServiceReferences(null, null);
        }
        catch (InvalidSyntaxException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        if (refs == null) return;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ServiceReference<Object> ref : refs)
        {
            Object svc = null;
            try
            {
                svc = ctx.getService(ref);
                if (svc == null || !svc.getClass().getName().startsWith(EDT_PKG_PREFIX))
                    continue;
                Object found = scanFields(svc, 0, visited);
                if (found != null) 
                    {
                        pool = found;
                        break;
                    }
            }
            catch (Exception ignored) {}
            finally
            {
                if (svc != null)
                    try { ctx.ungetService(ref); } catch (Exception ignored) {}
            }
        }
        if (pool != null) 
        { 
            prepareReflection(); 
        }
    }

    // -----------------------------------------------------------------------
    // Рекурсивный обход полей
    // -----------------------------------------------------------------------

    private Object scanFields(Object obj, int depth, Set<Object> visited)
    {
        if (obj == null || depth > SCAN_DEPTH || !visited.add(obj)) return null;
        if (classNameInHierarchy(obj.getClass())) 
            return obj;
        // Заходим только в EDT-объекты
        if (!obj.getClass().getName().startsWith(EDT_PKG_PREFIX) && depth > 0) return null;

        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields())
            {
                if (f.getType().isPrimitive() || f.getType() == String.class) continue;
                try
                {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val instanceof Map)
                    {
                        for (Object v : ((Map<?, ?>) val).values())
                        {
                            Object r = scanFields(v, depth + 1, visited);
                            if (r != null) return r;
                        }
                    }
                    else if (val instanceof Collection)
                    {
                        for (Object v : (Collection<?>) val)
                        {
                            Object r = scanFields(v, depth + 1, visited);
                            if (r != null) return r;
                        }
                    }
                    else
                    {
                        Object r = scanFields(val, depth + 1, visited);
                        if (r != null) return r;
                    }
                }
                catch (Exception ignored) {}
            }
        return null;
    }

    // -----------------------------------------------------------------------
    // Рефлексия методов и полей пула
    // -----------------------------------------------------------------------

    private void prepareReflection()
    {
        if (pool == null) return;
        mapFields.clear();

        // Приоритетные Map-поля (по имени) — cachedConnections — в начало
        List<Field> prio = new ArrayList<>(), rest = new ArrayList<>();
        for (Class<?> c = pool.getClass(); c != null; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields())
            {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                (false
                    || n.contains("session")
                    || n.contains("client") 
                    || n.contains("connect") 
                    || n.contains("cache") 
                    || n.contains("active") 
                    || n.contains("pool")
                        ? prio : rest).add(f);
                Global.log("  Map field: " + f.getName()); //$NON-NLS-1$
            }
        mapFields.addAll(prio);
        mapFields.addAll(rest);

        poll(); // немедленный первый опрос
    }

    // =======================================================================
    // Вспомогательные утилиты
    // =======================================================================

    static boolean classNameInHierarchy(Class<?> cls)
    {
        for (Class<?> c = cls; c != null; c = c.getSuperclass())
            if (POOL_CLASS_NAME.equals(c.getName())) return true;
        return false;
    }

    static String nameOf(Object obj)
    {
        if (obj == null) return ""; //$NON-NLS-1$
        for (String m : new String[]{ "getName", "getTitle", "getLocation" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Object r = Global.call(obj, m);
            if (r instanceof String && !((String) r).isEmpty()) return (String) r;
        }
        return obj.toString();
    }

    private void notifyListeners()
    {
        changeListeners.forEach(r -> { try { r.run(); } catch (Exception ignored) {} });
    }

}
