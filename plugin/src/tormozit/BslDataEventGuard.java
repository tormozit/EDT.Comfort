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
 * <p>Но выбросить теневую карту целиком тоже нельзя: именно в неё попали {@code DataEvent}
 * для посчитанных предложений, и без них вставка не поднимает LinkedMode (каретка не встаёт
 * между скобок {@code Метод()}). Поэтому фон забирает записи через {@link #drainIsolated},
 * а публикация на UI-потоке добавляет их в настоящую карту через {@link #mergeIntoReal} —
 * в момент, когда перенос строго упорядочен со вставкой.
 *
 * <p>Если подменить поле не удалось (другая версия EDT, изменилось имя), {@link #install}
 * возвращает {@code false} — вызывающий код обязан не запускать фоновый расчёт.
 */
final class BslDataEventGuard
{
    private static final String BSL_DOCUMENT_LISTENER_SIMPLE = "BslDocumentListener"; //$NON-NLS-1$

    /** Документы, на которых обёртка уже стоит, и сама обёртка. */
    private static final Map<IDocument, IsolatingMap> installed = new java.util.WeakHashMap<>();

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
        if (installed.containsKey(doc))
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
                installed.put(doc, (IsolatingMap)current);
                return true;
            }
            if (!(current instanceof Map))
                return false;
            @SuppressWarnings("unchecked")
            Map<Object, Object> real = (Map<Object, Object>)current;
            IsolatingMap guard = new IsolatingMap(real);
            mapField.set(listener, guard);
            installed.put(doc, guard);
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

    /**
     * Забирает то, что изолированный расчёт записал в теневую карту, и очищает её.
     *
     * <p>Вызывать <b>на том же потоке</b>, где выполнялся {@link #runIsolated}: теневая карта
     * привязана к потоку. Возвращённые записи — это {@code DataEvent} для посчитанных
     * предложений; без них LinkedMode при вставке не поднимется (карта пуста — EDT не найдёт
     * ключ по вставленному тексту).
     */
    static synchronized Map<Object, Object> drainIsolated(IDocument doc)
    {
        IsolatingMap guard = doc == null ? null : installed.get(doc);
        return guard == null ? java.util.Collections.emptyMap() : guard.drainShadow();
    }

    /**
     * Переносит записи {@code DataEvent}, добытые фоновым расчётом, в настоящую карту.
     *
     * <p>Только с UI-потока и только в момент публикации результата: тогда перенос строго
     * упорядочен со вставкой предложения, и гонки, ради которой введена изоляция, нет.
     * Настоящая карта при этом не очищается — записи только добавляются.
     */
    static synchronized void mergeIntoReal(IDocument doc, Map<Object, Object> entries)
    {
        if (entries == null || entries.isEmpty())
            return;
        IsolatingMap guard = doc == null ? null : installed.get(doc);
        if (guard != null)
            guard.mergeToReal(entries);
    }

    /**
     * Диагностика на момент вставки: есть ли в НАСТОЯЩЕЙ карте запись под вставленный текст.
     *
     * <p>EDT ищет её по {@code DocumentEvent.getText()}; не найдя — молча не поднимает
     * LinkedMode, без исключения и без записи в журнал. Отличить «записи нет» от «запись есть,
     * но с чужими координатами» иначе нечем.
     */
    static String describeRealMap(IDocument doc, String insertedText)
    {
        IsolatingMap guard = doc == null ? null : installed.get(doc);
        if (guard == null)
            return "{\"guard\":false}"; //$NON-NLS-1$
        Map<Object, Object> real = guard.real;
        Object hit = insertedText == null ? null : real.get(insertedText);
        StringBuilder sb = new StringBuilder("{\"guard\":true,\"size\":"); //$NON-NLS-1$
        sb.append(real.size()).append(",\"hit\":").append(hit != null); //$NON-NLS-1$
        if (hit != null)
            sb.append(",\"event\":\"").append(describeEvent(hit)).append('"'); //$NON-NLS-1$
        return sb.append('}').toString();
    }

    /** Координаты записи {@code DataEvent}: по ним видно, для той ли позиции она посчитана. */
    private static String describeEvent(Object dataEvent)
    {
        try
        {
            Class<?> c = dataEvent.getClass();
            return "posStart=" + readInt(c, dataEvent, "posStart") //$NON-NLS-1$ //$NON-NLS-2$
                + " length=" + readInt(c, dataEvent, "length") //$NON-NLS-1$ //$NON-NLS-2$
                + " stopPos=" + readInt(c, dataEvent, "stopPos"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            return "?"; //$NON-NLS-1$
        }
    }

    private static int readInt(Class<?> c, Object target, String name) throws Exception
    {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(target);
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

        /** Снимок теневой карты текущего потока с её очисткой. */
        Map<Object, Object> drainShadow()
        {
            Map<Object, Object> s = shadow.get();
            if (s.isEmpty())
                return java.util.Collections.emptyMap();
            Map<Object, Object> copy = new java.util.HashMap<>(s);
            s.clear();
            return copy;
        }

        void mergeToReal(Map<Object, Object> entries)
        {
            real.putAll(entries);
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
