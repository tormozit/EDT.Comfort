package tormozit.edt;

import java.lang.reflect.Field;

/**
 * Утилиты Java-рефлексии — единый источник правды для всего плагина.
 *
 * <p>Заменяет дублирующиеся реализации, которые были рассыпаны по четырём классам:
 * <ul>
 *   <li>{@code CompareEditorMenuHook.getField} / {@code invokeNoArg}</li>
 *   <li>{@code OpenObjectHandler.getField} / {@code invokeNoArg}</li>
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
}
