package tormozit;


import java.lang.reflect.Method;
import java.util.Iterator;

/**
 * Рефлексивная обёртка над Jacob (Java COM Bridge).
 *
 * <h3>Classloader в OSGi</h3>
 * {@code ComBridge.class.getClassLoader()} — Equinox BundleClassLoader,
 * видит {@code Bundle-ClassPath: lib/jacob.jar}.
 *
 * <h3>DLL-загрузка</h3>
 * {@code System.load(absolutePath)} через {@code FileLocator} вызывается до
 * первой загрузки класса Jacob — JVM не будет искать DLL повторно в static initializer.
 *
 * <h3>WMI-поддержка</h3>
 * {@link #iterateComCollection} использует Jacob {@code EnumVariant} для итерации
 * COM-коллекций (результаты WMI ExecQuery, списки окон 1С и т.п.).
 */
public final class ComBridge
{
    private ComBridge() {}

    private static volatile boolean initialized;
    private static volatile boolean available;
    private static volatile String  unavailableReason;

    private static Class<?> classActiveXComponent;
    private static Class<?> classDispatch;
    private static Class<?> classVariant;
    private static Class<?> classComThread;
    private static Class<?> classEnumVariant;

    private static Method methodDispatchCall;      // Dispatch.call(Dispatch, String, Object[])
    private static Method methodDispatchPut;       // Dispatch.put(Dispatch, String, Object)
    private static Method methodDispatchGet;       // Dispatch.get(Dispatch, String)
    private static Method methodVariantToBoolean;
    private static Method methodVariantToLong;
    private static Method methodComThreadInitSTA;
    private static Method methodComThreadRelease;
    private static Method methodEnumVariantHasMore; // EnumVariant.hasMoreElements()
    private static Method methodEnumVariantNext;    // EnumVariant.nextElement()
    private static Method methodVariantToJavaObject;
    private static Method methodVariantGetVt;
    private static Method methodVariantToString;
    
    // -----------------------------------------------------------------------
    // Инициализация
    // -----------------------------------------------------------------------

    private static void ensureJacob()
    {
        if (initialized) return;
        synchronized (ComBridge.class)
        {
            if (initialized) return;
            initialized = true;
             try
            {
                ClassLoader cl = ComBridge.class.getClassLoader();

                classActiveXComponent = cl.loadClass("com.jacob.activeX.ActiveXComponent"); //$NON-NLS-1$
                classDispatch         = cl.loadClass("com.jacob.com.Dispatch");              //$NON-NLS-1$
                classVariant          = cl.loadClass("com.jacob.com.Variant");               //$NON-NLS-1$
                classComThread        = cl.loadClass("com.jacob.com.ComThread");             //$NON-NLS-1$
                classEnumVariant      = cl.loadClass("com.jacob.com.EnumVariant");           //$NON-NLS-1$

                methodDispatchCall     = classDispatch.getMethod("call", classDispatch, String.class, Object[].class); //$NON-NLS-1$
                methodDispatchPut      = classDispatch.getMethod("put",  classDispatch, String.class, Object.class);   //$NON-NLS-1$
                methodDispatchGet      = classDispatch.getMethod("get",  classDispatch, String.class);                 //$NON-NLS-1$
                methodVariantToBoolean = classVariant.getMethod("toBoolean"); //$NON-NLS-1$
                methodVariantToLong    = classVariant.getMethod("toInt");    //$NON-NLS-1$
                methodComThreadInitSTA = classComThread.getMethod("InitSTA"); //$NON-NLS-1$
                methodComThreadRelease = classComThread.getMethod("Release"); //$NON-NLS-1$
                methodEnumVariantHasMore = classEnumVariant.getMethod("hasMoreElements"); //$NON-NLS-1$
                methodEnumVariantNext    = classEnumVariant.getMethod("nextElement");     //$NON-NLS-1$
                methodVariantToJavaObject  = classVariant.getMethod("toJavaObject");
                methodVariantToString      = classVariant.getMethod("toString");
                methodVariantToJavaObject = classVariant.getMethod("toJavaObject");
                methodVariantGetVt         = classVariant.getMethod("getvt");
                
                available = true;
                initComThread();
                Global.log("Jacob инициализирован успешно"); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                available = false;
                unavailableReason = e.toString();
                Global.log("Jacob НЕУДАЧА: " + e); //$NON-NLS-1$
            }
        }
    }

    private static void requireJacob()
    {
        ensureJacob();
        if (!available)
            throw new UnsupportedOperationException("Jacob недоступен: " + unavailableReason); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // COM-поток
    // -----------------------------------------------------------------------

    public static void initComThread()
    {
        requireJacob();
        try { methodComThreadInitSTA.invoke(null); }
        catch (Exception e) { throw new RuntimeException("ComThread.InitSTA(): " + e.getMessage(), e); } //$NON-NLS-1$
    }

    public static void releaseComThread()
    {
        if (!available) return;
        try { methodComThreadRelease.invoke(null); }
        catch (Exception ignored) {}
    }

    // -----------------------------------------------------------------------
    // Создание COM-объекта
    // -----------------------------------------------------------------------

    /**
     * Аналог {@code Новый COMОбъект(className)}.
     * Инициализирует COM STA для текущего потока.
     */
    public static Object createComObject(String className)
    {
        requireJacob();
        try
        {
//            initComThread();
            return classActiveXComponent.getConstructor(String.class).newInstance(className);
        }
        catch (Exception e)
        {
            Throwable c = unwrap(e);
            throw new RuntimeException("Ошибка COM '" + className + "': " + c.getMessage(), c); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // -----------------------------------------------------------------------
    // Методы и свойства
    // -----------------------------------------------------------------------

    public static boolean connect(Object dispatch, String connectionString)
    {
        return toBoolean(invoke(dispatch, "Connect", connectionString));
    }
    
    /** * Достает чистый Dispatch из Variant'а, если это необходимо.
     * Именно это спасает от "argument type mismatch".
     */
    private static Object getRealDispatch(Object obj) throws Exception {
        if (obj == null) return null;
        if (classDispatch.isInstance(obj)) return obj;
        if (classVariant.isInstance(obj)) {
            // Если Jacob вернул нам Variant, извлекаем из него Dispatch
            return classVariant.getMethod("toDispatch").invoke(obj);
        }
        return obj;
    }
  
    static Object invoke(Object dispatch, String method, Object... args)
    {
        requireJacob();
        try { 
            // 1. Достаем настоящий Dispatch
            Object realDispatch = getRealDispatch(dispatch);
            // 2. Передаем (Object) args, чтобы массив не "размазался"
            return methodDispatchCall.invoke(null, realDispatch, method, (Object) args); 
        }
        catch (Exception e) 
        { 
            Throwable c = unwrap(e); 
            throw new RuntimeException("COM." + method + "(): " + c.getMessage(), c); 
        }
    }

    public static Object getProperty(Object dispatch, String property)
    {
        requireJacob();
        try { 
            return methodDispatchGet.invoke(null, getRealDispatch(dispatch), property); 
        }
        catch (Exception e) {
            Throwable c = unwrap(e); 
            throw new RuntimeException("COM." + property + ": " + c.getMessage(), c); 
        }
    }

    public static void setProperty(Object dispatch, String property, Object value)
    {
        requireJacob();
        try { 
            methodDispatchPut.invoke(null, getRealDispatch(dispatch), property, value); 
        }
        catch (Exception e) { 
            Throwable c = unwrap(e); 
            throw new RuntimeException("COM." + property + ": " + c.getMessage(), c); 
        }
    }
    
    // -----------------------------------------------------------------------
    // Итерация COM-коллекции (EnumVariant)
    // -----------------------------------------------------------------------

    /**
     * Возвращает {@link Iterable} над COM-коллекцией (SWbemObjectSet, списки и т.п.).
     *
     * <p>Аналог конструкции {@code Для Каждого Элемент Из Коллекция Цикл}.
     * Использует Jacob {@code EnumVariant(Dispatch)} — внутренний итератор COM.
     *
     * <p>Каждый элемент — объект Dispatch, с которым можно работать через
     * {@link #invoke}, {@link #getProperty} и т.д.
     */
    public static Iterable<Object> iterateComCollection(Object dispatch)
    {
        requireJacob();
        try
        {
         // 1. Извлекаем реальный Dispatch, если пришел Variant
            Object realDispatch = dispatch;
            if (classVariant.isInstance(dispatch)) {
                realDispatch = classVariant.getMethod("toDispatch").invoke(dispatch);
            }

            // 2. Теперь передаем realDispatch, тип которого совпадает с classDispatch
            final Object enumVariant = classEnumVariant
                .getConstructor(classDispatch)
                .newInstance(realDispatch);

            return () -> new Iterator<Object>()
            {
                private Boolean hasMore = null;

                @Override
                public boolean hasNext()
                {
                    if (hasMore == null)
                        try { hasMore = (Boolean) methodEnumVariantHasMore.invoke(enumVariant); }
                        catch (Exception e) { hasMore = Boolean.FALSE; }
                    return hasMore;
                }

                @Override
                public Object next()
                {
                    hasMore = null;
                    try { return methodEnumVariantNext.invoke(enumVariant); }
                    catch (Exception e) { throw new RuntimeException("EnumVariant.next(): " + e.getMessage(), e); } //$NON-NLS-1$
                }
            };
        }
        catch (Exception e)
        {
            Throwable c = unwrap(e);
            throw new RuntimeException("iterateComCollection: " + c.getMessage(), c); //$NON-NLS-1$
        }
    }

    // -----------------------------------------------------------------------
    // Конвертация Variant
    // -----------------------------------------------------------------------

    public static boolean toBoolean(Object variant)
    {
        if (variant == null) return false;
        if (variant instanceof Boolean) return (Boolean) variant;
        try { return (Boolean) methodVariantToBoolean.invoke(variant); }
        catch (Exception e) { return false; }
    }
    
    public static String toString(Object variant)
    {
        if (variant == null) return "";
        if (variant instanceof String) return (String) variant;
        try { return (String) methodVariantToString.invoke(variant); }
        catch (Exception e) { return ""; }
    }
    
    public static long toLong(Object variant)
    {
        if (variant == null) return 0L;
        if (variant instanceof Number) return ((Number) variant).longValue();
        
        try {
            // 1. Получаем тип варианта (vt) через рефлексию, чтобы понять, что внутри
            // methodVariantGetVt должен быть инициализирован как Variant.getVariantType()
            short vt = (short) methodVariantGetVt.invoke(variant); 
            
            // 2. Если это строка (WMI иногда отдает uint64 как String), парсим её
            if (vt == 8) { // VT_BSTR
                String val = (String) methodVariantToString.invoke(variant);
                return val == null || val.isEmpty() ? 0L : Long.parseLong(val);
            }

            // 3. Самый надежный путь: вызываем Variant.toJavaObject()
            // Jacob сам конвертирует VT_I4/VT_I2/VT_R8 в подходящий Number
            Object javaObj = methodVariantToJavaObject.invoke(variant);
            if (javaObj instanceof Number) {
                return ((Number) javaObj).longValue();
            }
        }
        catch (Exception e) {
            // Для отладки: выведите e.getCause().getMessage() в консоль или лог
            return 0L; 
        }
        return 0L;
    }

    // -----------------------------------------------------------------------
    // Утилиты
    // -----------------------------------------------------------------------

    private static Throwable unwrap(Exception e)
    {
        return e.getCause() != null ? e.getCause() : e;
    }
}
