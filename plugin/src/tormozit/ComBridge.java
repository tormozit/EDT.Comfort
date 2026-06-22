package tormozit;


import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import org.eclipse.swt.widgets.Display;

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
    private static Constructor<?> variantByRefCtor;
    private static Method variantGetStringRef;
    private static volatile boolean byrefAttempted;
    private static volatile boolean byrefAvailable;
    private static volatile boolean jacobFailureNotified;

    /** Результат {@code ПрочитатьУдалитьСообщенияПользователю}. */
    public static final class UserMessageDrainResult
    {
        public final String text;
        public final String moduleRef;

        UserMessageDrainResult(String text, String moduleRef)
        {
            this.text = text != null ? text : ""; //$NON-NLS-1$
            this.moduleRef = moduleRef != null ? moduleRef : ""; //$NON-NLS-1$
        }
    }

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
                notifyJacobFailureOnce(e);
            }
        }
    }

    private static void notifyJacobFailureOnce(Throwable e)
    {
        if (jacobFailureNotified)
            return;
        jacobFailureNotified = true;
        String msg = e != null ? comErrorMessage(unwrap(e instanceof Exception ex ? ex : new Exception(e))) : ""; //$NON-NLS-1$
        notifyComProblemToUser("Jacob/COM недоступен: " + msg); //$NON-NLS-1$
    }

    private static void notifyComProblemToUser(String message)
    {
        String detail = formatErrorForNotification(message);
        if (detail.isEmpty())
            return;
        Global.log("ComBridge", detail); //$NON-NLS-1$
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        final String toastText = detail;
        display.asyncExec(() -> ToastNotification.show(IRApplication.toastTitle(), toastText, 8_000));
    }

    /** Lazy init Variant(Object, boolean) + getStringRef — только для drainUserMessages. */
    private static boolean ensureByrefSupport()
    {
        if (byrefAttempted)
            return byrefAvailable;
        synchronized (ComBridge.class)
        {
            if (byrefAttempted)
                return byrefAvailable;
            byrefAttempted = true;
            try
            {
                variantByRefCtor = classVariant.getConstructor(Object.class, boolean.class);
                variantGetStringRef = classVariant.getMethod("getStringRef"); //$NON-NLS-1$
                byrefAvailable = true;
            }
            catch (Exception e)
            {
                byrefAvailable = false;
                if (Global.isLogEnabled())
                    Global.log("ComBridge", "byref init: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return byrefAvailable;
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
            throw new RuntimeException("Ошибка COM '" + className + "': " + comErrorMessage(c), c); //$NON-NLS-1$ //$NON-NLS-2$
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
            throw new RuntimeException("COM." + method + "(): " + comErrorMessage(c), c); 
        }
    }

    /**
     * Порт {@code ПолеТекстаПрограммы.ПрочитатьУдалитьСообщенияПользователю(СсылкаМодуля)}.
     * Без исключений — ошибки COM возвращают пустой результат.
     */
    public static UserMessageDrainResult drainUserMessages(Object codeEditor)
    {
        if (codeEditor == null)
            return new UserMessageDrainResult("", ""); //$NON-NLS-1$ //$NON-NLS-2$
        requireJacob();
        if (ensureByrefSupport())
        {
            try
            {
                Object moduleRefOut = variantByRefCtor.newInstance("", Boolean.TRUE); //$NON-NLS-1$
                Object raw = invoke(codeEditor, "ПрочитатьУдалитьСообщенияПользователю", moduleRefOut); //$NON-NLS-1$
                String text = fixUtf8Mojibake(toString(raw).strip());
                String moduleRefRaw = (String) variantGetStringRef.invoke(moduleRefOut);
                String moduleRef = moduleRefRaw != null
                    ? fixUtf8Mojibake(moduleRefRaw.strip())
                    : ""; //$NON-NLS-1$
                return new UserMessageDrainResult(text, moduleRef);
            }
            catch (Exception e)
            {
                if (Global.isLogEnabled())
                    Global.log("ComBridge", "drainUserMessages byref: " + comErrorMessage(unwrap(e))); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        try
        {
            Object raw = invoke(codeEditor, "ПрочитатьУдалитьСообщенияПользователю"); //$NON-NLS-1$
            String text = fixUtf8Mojibake(toString(raw).strip());
            return new UserMessageDrainResult(text, ""); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            notifyComProblemToUser("Не удалось прочитать сообщения ИР: " + comErrorMessage(unwrap(e))); //$NON-NLS-1$
            return new UserMessageDrainResult("", ""); //$NON-NLS-1$ //$NON-NLS-2$
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
            throw new RuntimeException("COM." + property + ": " + comErrorMessage(c), c); 
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
            throw new RuntimeException("COM." + property + ": " + comErrorMessage(c), c); 
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
            throw new RuntimeException("iterateComCollection: " + comErrorMessage(c), c); //$NON-NLS-1$
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

    /**
     * Поле 1С:Структура / Соответствие — через {@code Свойство()}, не {@link #getProperty}.
     * Без исключений: при ошибке возвращает {@code null}.
     */
    public static String structureField(Object structure, String key)
    {
        if (structure == null || key == null || key.isEmpty())
            return null;
        if (structure instanceof Boolean)
            return null;

        try
        {
            Object val = invoke(structure, "Свойство", key); //$NON-NLS-1$
            String s = structureFieldValueToString(val);
            if (s != null)
                return s;
        }
        catch (Exception ignored)
        {
            // 1С:Структура — читаем через Свойство(); fallback ниже
        }
        return structureFieldViaProperty(structure, key);
    }

    private static String structureFieldViaProperty(Object structure, String key)
    {
        try
        {
            Object val = methodDispatchGet.invoke(null, getRealDispatch(structure), key);
            return structureFieldValueToString(val);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static String structureFieldValueToString(Object val)
    {
        if (val == null)
            return null;
        if (val instanceof Boolean b && !b)
            return null;
        String s = toString(val);
        return s != null && !s.isEmpty() ? s : null;
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

    /** Jacob на Windows часто отдаёт UTF-8 текст 1С как Latin-1 (ÐÐ¾Ð´…). */
    private static String comErrorMessage(Throwable t)
    {
        if (t == null)
            return ""; //$NON-NLS-1$
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty())
            msg = t.toString();
        return fixUtf8Mojibake(msg);
    }

    static String fixUtf8Mojibake(String message)
    {
        if (message == null || message.isEmpty())
            return ""; //$NON-NLS-1$
        if (!looksLikeUtf8Mojibake(message))
            return message;

        // В одном сообщении COM часть строк (Invoke of:) — Latin-1 mojibake, часть (Description) — нормальная кириллица.
        // Перекодируем только фрагменты из символов U+00FF и ниже с маркерами Ð/Ñ.
        StringBuilder result = new StringBuilder(message.length());
        int index = 0;
        while (index < message.length())
        {
            char ch = message.charAt(index);
            if (ch <= 0xFF)
            {
                int runStart = index;
                while (index < message.length() && message.charAt(index) <= 0xFF)
                    index++;
                String run = message.substring(runStart, index);
                result.append(looksLikeUtf8Mojibake(run) ? fixUtf8MojibakeRun(run) : run);
            }
            else
            {
                int runStart = index;
                while (index < message.length() && message.charAt(index) > 0xFF)
                    index++;
                result.append(message, runStart, index);
            }
        }
        return result.toString();
    }

    private static String fixUtf8MojibakeRun(String run)
    {
        try
        {
            String fixed = new String(run.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            if (fixed.indexOf('\uFFFD') < 0)
                return fixed;
        }
        catch (Exception ignored) {}
        return run;
    }

    /** Текст ошибки COM для тоста: без дубля «Invoke of:» и без строки Source. */
    static String formatErrorForNotification(String message)
    {
        if (message == null || message.isEmpty())
            return ""; //$NON-NLS-1$
        String detail = fixUtf8Mojibake(message);
        if (!detail.startsWith("COM.")) //$NON-NLS-1$
            return detail;
        detail = detail.replaceFirst(
            "(COM\\.[^(:]+\\(\\):)\\s*Invoke of:\\s*[^\\r\\n]+\\s*",
            "$1\n"); //$NON-NLS-1$
        detail = detail.replaceFirst("(?m)^Source: [^\\r\\n]+\\s*\\r?\\n?", ""); //$NON-NLS-1$
        return detail.strip();
    }

    private static boolean looksLikeUtf8Mojibake(String message)
    {
        return message.indexOf('\u00D0') >= 0 || message.indexOf('\u00D1') >= 0;
    }
}
