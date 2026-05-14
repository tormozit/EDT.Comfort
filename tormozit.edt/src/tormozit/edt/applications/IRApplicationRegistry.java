package tormozit.edt.applications;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реестр подключений приложения ИР (Информационная Разработка) через COM Automation.
 * Логика адаптирована из RDT.os (ПодключениеИР / ЗакрытьПриложениеИР).
 */
public final class IRApplicationRegistry
{
    private static final IRApplicationRegistry INSTANCE = new IRApplicationRegistry();

    // Хранилище активных COM-объектов (соединений с ИР)
    private final Map<String, Object> connections = new ConcurrentHashMap<>();
    // Хранилище времени начала сессии
    private final Map<String, LocalDateTime> sessions = new ConcurrentHashMap<>();

    public static IRApplicationRegistry getInstance() { return INSTANCE; }

    private IRApplicationRegistry() {}

    public boolean isConnected(Object element) {
        return connections.containsKey(getKey(element));
    }

    public LocalDateTime getSessionStart(Object element) {
        return sessions.get(getKey(element));
    }

    /**
     * Подключить приложение ИР (аналог ПодключениеИР из RDT.os)
     */
    public void connect(Object element) {
        String key = getKey(element);
        if (connections.containsKey(key)) return;

        try {
            // 1. Получаем строку соединения через существующий аксессор
            // В реальном EDT это требует получения объекта Infobase из element
            String connectionString = extractConnectionString(element);
            if (connectionString == null || connectionString.isEmpty()) {
                DesignerSessionPoolAccessor.log("Не удалось получить строку соединения для " + key);
                return;
            }

            // 2. Создаем V83.COMConnector (через Jacob рефлексивно)
            Object connector = createComObject("V83.COMConnector");
            
            // 3. Вызываем Connect(connectionString)
            Object connection = invokeMethod(connector, "Connect", connectionString);
            
            // 4. Инициализация ИР (как в RDT.os)
            // Здесь можно добавить вызов специфичных для ИР методов, например:
            // invokeMethod(connection, "ИнициализироватьПриложениеИР", ...);
            
            connections.put(key, connection);
            sessions.put(key, LocalDateTime.now());
            
            DesignerSessionPoolAccessor.log("Приложение ИР успешно подключено: " + key);
            
        } catch (Exception e) {
            DesignerSessionPoolAccessor.log("Ошибка подключения ИР: " + e.getMessage());
        }
    }

    /**
     * Отключить приложение ИР (аналог ЗакрытьПриложениеИР из RDT.os)
     */
    public void disconnect(Object element) {
        String key = getKey(element);
        Object connection = connections.remove(key);
        sessions.remove(key);

        if (connection != null) {
            releaseComObject(connection);
            DesignerSessionPoolAccessor.log("Приложение ИР отключено: " + key);
        }
    }

    // ---- Вспомогательные методы ----

    private String getKey(Object element) {
        return DesignerSessionPoolAccessor.nameOf(element);
    }

    private String extractConnectionString(Object element) {
        // Пытаемся достать строку соединения из объекта Infobase внутри element
        Object infobase = DesignerSessionPoolAccessor.tryCall(element, "getInfobase");
        if (infobase == null) infobase = element;
        
        Object connStr = DesignerSessionPoolAccessor.tryCall(infobase, "getConnectString");
        return connStr != null ? connStr.toString() : null;
    }

    /** Создает объект Dispatch(progId) через Jacob рефлексивно */
    private Object createComObject(String progId) throws Exception {
        Class<?> dispatchClass = Class.forName("com.jacob.com.Dispatch");
        Class<?> activeXClass = Class.forName("com.jacob.activeX.ActiveXComponent");
        Constructor<?> constr = activeXClass.getConstructor(String.class);
        Object activeX = constr.newInstance(progId);
        return activeXClass.getMethod("getObject").invoke(activeX);
    }

    /** Вызывает метод COM-объекта через Jacob Dispatch.call */
    private Object invokeMethod(Object dispatchObj, String methodName, Object... args) throws Exception {
        Class<?> dispatchClass = Class.forName("com.jacob.com.Dispatch");
        Method callMethod = dispatchClass.getMethod("call", dispatchClass, String.class, Object[].class);
        return callMethod.invoke(null, dispatchObj, methodName, args);
    }

    private void releaseComObject(Object comObj) {
        try {
            comObj.getClass().getMethod("release").invoke(comObj);
        } catch (Exception ignored) {}
    }
}