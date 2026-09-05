package tormozit;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jface.text.AbstractDocument;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.core.runtime.ListenerList;

/**
 * Изоляция штатной карты {@code BslDocumentListener.map} ({@code DataEvent}) от фоновых
 * расчётов автодополнения.
 *
 * <p>Зачем. {@code delegate.computeCompletionProposals} побочно вызывает
 * {@code BslDocumentListener.reset} и чистит карту {@code DataEvent}. По этой карте EDT
 * поднимает LinkedMode — каретку внутри {@code Метод()}. Пока расчёт шёл на UI-потоке,
 * он был строго упорядочен со вставкой предложения; из фонового потока он затирает карту
 * параллельно вставке, и LinkedMode ломается.
 *
 * <p>Как. Поле {@code map} подменяется обёрткой, которая смотрит на текущий поток: вызовы
 * из потока, помеченного {@link #runIsolated}, уходят в теневую карту и выбрасываются,
 * все остальные (то есть UI-поток и сам EDT) работают с настоящей картой без изменений.
 * Фоновый расчёт после этого физически не может ни очистить, ни засорить {@code DataEvent}.
 *
 * <p>Если подменить поле не удалось (другая версия EDT, изменилось имя), {@link #install}
 * возвращает {@code false} — вызывающий код обязан не запускать фоновый расчёт.
 */
final class BslDataEventGuard
{
    private static final String BSL_DOCUMENT_LISTENER_SIMPLE = "BslDocumentListener"; //$NON-NLS-1$

    /** Документы, на которых обёртка уже стоит. */
    private static final Set<IDocument> installed =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Поток, чьи обращения к карте должны уходить в теневую копию. */
    private static final ThreadLocal<Boolean> isolated = new ThreadLocal<>();

    private BslDataEventGuard() {}

    /**
     * Ставит обёртку на карту {@code DataEvent} документа.
     *
     * @return {@code false}, если поле не найдено или подменить не удалось — фоновый расчёт
     *     в этом случае запускать нельзя.
     */
    static synchronized boolean install(IDocument doc)
    {
        if (doc == null)
            return false;
        if (installed.contains(doc))
            return true;
        Object listener = findBslDocumentListener(doc);
        if (listener == null)
            return false;
        try
        {
            Field mapField = listener.getClass().getDeclaredField("map"); //$NON-NLS-1$
            mapField.setAccessible(true);
            Object current = mapField.get(listener);
            if (current instanceof IsolatingMap)
            {
                installed.add(doc);
                return true;
            }
            if (!(current instanceof Map))
                return false;
            @SuppressWarnings("unchecked")
            Map<Object, Object> real = (Map<Object, Object>)current;
            mapField.set(listener, new IsolatingMap(real));
            installed.add(doc);
            Global.tempLog("assist-perf", //$NON-NLS-1$
                "{\"loc\":\"dataEventGuard.installed\"}"); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            Global.tempLog("assist-perf", //$NON-NLS-1$
                "{\"loc\":\"dataEventGuard.failed\",\"err\":\"" //$NON-NLS-1$
                    + String.valueOf(e.getMessage()).replace('"', '\'') + "\"}"); //$NON-NLS-1$
            return false;
        }
    }

    /** Выполняет {@code body} с изоляцией карты {@code DataEvent} для текущего потока. */
    static <T> T runIsolated(java.util.function.Supplier<T> body)
    {
        Boolean prev = isolated.get();
        isolated.set(Boolean.TRUE);
        try
        {
            return body.get();
        }
        finally
        {
            if (prev == null)
                isolated.remove();
            else
                isolated.set(prev);
        }
    }

    private static boolean isIsolatedThread()
    {
        return Boolean.TRUE.equals(isolated.get());
    }

    private static Object findBslDocumentListener(IDocument doc)
    {
        if (!(doc instanceof AbstractDocument))
            return null;
        try
        {
            Field field = AbstractDocument.class.getDeclaredField("fDocumentListeners"); //$NON-NLS-1$
            field.setAccessible(true);
            Object listObj = field.get(doc);
            if (!(listObj instanceof ListenerList<?> listenerList))
                return null;
            for (Object o : listenerList)
            {
                if (o instanceof IDocumentListener
                    && o.getClass().getName().endsWith(BSL_DOCUMENT_LISTENER_SIMPLE))
                    return o;
            }
        }
        catch (Exception ignored)
        {
            // подменять нечего — вызывающий не запустит фоновый расчёт
        }
        return null;
    }

    /**
     * Карта, которая для изолированного потока подставляет теневую копию: всё, что фоновый
     * расчёт пишет и чистит, остаётся в ней и выбрасывается вместе с ней.
     */
    private static final class IsolatingMap implements Map<Object, Object>
    {
        private final Map<Object, Object> real;
        private final ThreadLocal<Map<Object, Object>> shadow =
            ThreadLocal.withInitial(ConcurrentHashMap::new);

        IsolatingMap(Map<Object, Object> real)
        {
            this.real = real;
        }

        private Map<Object, Object> target()
        {
            return isIsolatedThread() ? shadow.get() : real;
        }

        @Override public int size() { return target().size(); }
        @Override public boolean isEmpty() { return target().isEmpty(); }
        @Override public boolean containsKey(Object key) { return target().containsKey(key); }
        @Override public boolean containsValue(Object v) { return target().containsValue(v); }
        @Override public Object get(Object key) { return target().get(key); }
        @Override public Object put(Object key, Object value) { return target().put(key, value); }
        @Override public Object remove(Object key) { return target().remove(key); }
        @Override public void putAll(Map<?, ?> m) { target().putAll(m); }
        @Override public void clear() { target().clear(); }
        @Override public Set<Object> keySet() { return target().keySet(); }
        @Override public Collection<Object> values() { return target().values(); }
        @Override public Set<Entry<Object, Object>> entrySet() { return target().entrySet(); }
    }
}
