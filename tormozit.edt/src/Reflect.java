

import java.lang.reflect.Field;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

/**
 * Утилиты Java-рефлексии — единый источник правды для всего плагина.
 *
 * <p>Заменяет дублирующиеся реализации, которые были рассыпаны по четырём классам:
 * <ul>
 *   <li>{@code CompareConfigMenuHook.getField} / {@code invokeNoArg}</li>
 *   <li>{@code CompareConfigOpenObjectHandler.getField} / {@code invokeNoArg}</li>
 *   <li>{@code IRApplicationRegistry.readField} / {@code tryCall}</li>
 *   <li>{@code DesignerSessionPoolAccessor.readField} / {@code tryCall}</li>
 * </ul>
 */
public final class Reflect
{
    private Reflect() {}

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
     * Вызывает метод {@code methodName} с переданными аргументами на объекте {@code obj}.
     * Метод ищется по имени и количеству аргументов по всей иерархии классов,
     * включая private и protected.
     *
     * @return результат вызова, или {@code null} при любой ошибке
     * @throws RuntimeException если метод бросает проверяемое исключение (причина из {@code InvocationTargetException})
     */
    public static Object invoke(Object obj, String methodName, Object... args)
    {
        if (obj == null || methodName == null) return null;
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
                        return m.invoke(obj, args);
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

    public static BundleContext ourContext()
    {
        Bundle b = FrameworkUtil.getBundle(DesignerSessionPoolAccessor.class);
        return b != null ? b.getBundleContext() : null;
    }

    public static void log(String msg)
    {
       String timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
       System.out.println("[Tormozit " + timestamp + "] " + msg);
    }
}
