package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;

import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * ВРЕМЕННАЯ диагностика: в панели «Свойства» иногда сбивается размещение полей (подписи и
 * редакторы съезжают на одну ячейку, строки описания типа дублируются). Сбой редкий и по
 * заказу не воспроизводится, поэтому диагностика рассчитана на долгую работу: каждое событие
 * — дешёвая проверка признаков сбоя ({@link #findAnomaly}) и строка в памяти, а в
 * {@code .tmp/temp-logs/property-sheet-layout.log} пишется только смена состояния: сбой
 * (последние события + полный снимок) и восстановление. Снять после подтверждения фикса.
 *
 * <p>В снимке: порядок записей {@code renderer.viewModelToView}, для каждого LWT-родителя
 * подписей — его раскладка и дети по порядку (класс, границы, видимость, текст, данные
 * раскладки), состояние оверлея поля «Тип». Строка с {@code [!]} — подписи одного родителя
 * стоят в разных колонках, то есть раскладка сбита.
 */
final class PropertySheetLayoutDiag
{
    private static final String LOG = "property-sheet-layout"; //$NON-NLS-1$
    private static final int MAX_TEXT = 30;
    /** Перестройка строк AEF идёт после события-причины — снимок повторяется с задержкой. */
    private static final int[] FOLLOW_UP_DELAYS_MS = { 400, 1500 };
    /** Сколько последних событий хранится в памяти и выводится перед снимком сбоя. */
    private static final int HISTORY_SIZE = 40;
    /** Предел полных снимков за сеанс — дальше сбой отмечается одной строкой. */
    private static final int MAX_FULL_SNAPSHOTS = 20;

    private static final Deque<String> HISTORY = new ArrayDeque<>();
    /** Последнее состояние раскладки панели: {@code null} — исправна, иначе описание сбоя. */
    private static final Map<IViewPart, String> LAST_ANOMALY = new WeakHashMap<>();
    private static int fullSnapshots;
    private static boolean announced;

    private PropertySheetLayoutDiag()
    {
    }

    /** Проверка сейчас и повторно через {@link #FOLLOW_UP_DELAYS_MS}. Из любого потока. */
    static void dump(String reason)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (Display.getCurrent() == null)
        {
            display.asyncExec(() -> dump(reason));
            return;
        }
        dumpNow(reason);
        for (int delay : FOLLOW_UP_DELAYS_MS)
            display.timerExec(delay, () -> dumpNow(reason + " +" + delay + "мс")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Одна проверка без повторов. Только в UI-потоке. */
    static void dumpNow(String reason)
    {
        try
        {
            if (!announced)
            {
                announced = true;
                Global.tempLog(LOG, "диагностика активна: в файл попадают только сбои раскладки (с " //$NON-NLS-1$
                    + HISTORY_SIZE + " предшествующими событиями) и её восстановление"); //$NON-NLS-1$
            }
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            {
                for (IWorkbenchPage page : window.getPages())
                {
                    for (IViewReference ref : page.getViewReferences())
                    {
                        IViewPart view = ref.getView(false);
                        if (PropertyNameIdentifierHook.isPropertySheetView(view))
                            check(view, reason);
                    }
                }
            }
        }
        catch (Throwable t)
        {
            Global.tempLogException(LOG, "[!] проверка не выполнена: " + reason, t); //$NON-NLS-1$
        }
    }

    /**
     * Событие всегда попадает в память ({@link #HISTORY}), в файл — только смена состояния
     * раскладки панели: сбой (история событий + полный снимок), другой сбой, восстановление.
     */
    private static void check(IViewPart view, String reason)
    {
        Map<?, ?> map = viewModelToView(view);
        String anomaly = map != null ? findAnomaly(map) : null;
        if (HISTORY.size() >= HISTORY_SIZE)
            HISTORY.removeFirst();
        HISTORY.addLast(LocalTime.now() + " " + reason + " | записей=" + (map != null ? map.size() : -1) //$NON-NLS-1$ //$NON-NLS-2$
            + " | оверлей «Тип»: " + TypeComboOverlayHook.describePropertyOverlay(view) //$NON-NLS-1$
            + (anomaly != null ? " | [!] " + anomaly : "")); //$NON-NLS-1$ //$NON-NLS-2$

        if (Objects.equals(anomaly, LAST_ANOMALY.get(view)))
            return;
        LAST_ANOMALY.put(view, anomaly);
        if (anomaly == null)
            Global.tempLog(LOG, "раскладка восстановилась: " + reason); //$NON-NLS-1$
        else if (fullSnapshots++ < MAX_FULL_SNAPSHOTS)
            Global.tempLog(LOG, "[!] раскладка сбита: " + anomaly + "\nпредшествующие события:\n  " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join("\n  ", HISTORY) + '\n' + describeView(view, reason)); //$NON-NLS-1$
        else
            Global.tempLog(LOG, "[!] раскладка сбита (полных снимков уже " + MAX_FULL_SNAPSHOTS + "): " //$NON-NLS-1$ //$NON-NLS-2$
                + anomaly + " | " + reason); //$NON-NLS-1$
    }

    private static Map<?, ?> viewModelToView(IViewPart view)
    {
        Object page = PropertyNameIdentifierHook.resolvePropertySheetPage(view);
        Object scene = page != null ? Global.invoke(page, "getScene") : null; //$NON-NLS-1$
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        return renderer != null && Global.getField(renderer, "viewModelToView") instanceof Map<?, ?> map //$NON-NLS-1$
            ? map : null;
    }

    /** LWT-родители подписей свойств в порядке панели (секции). */
    private static Set<Object> labelParents(Map<?, ?> map)
    {
        Set<Object> parents = new LinkedHashSet<>();
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            if (!isLabelViewModel(entry.getKey()))
                continue;
            Object parent = Global.invoke(Global.invoke(entry.getValue(), "getNativeControl"), "getParent"); //$NON-NLS-1$ //$NON-NLS-2$
            if (parent != null)
                parents.add(parent);
        }
        return parents;
    }

    /**
     * Признаки сбитой раскладки секции (двухколоночная: подпись — редактор) или {@code null}.
     * На исправной панели не срабатывают: 215 снимков, 837 подписей — все на чётных местах,
     * в одной колонке, без повторов. Текст без адресов объектов — он же служит для сравнения
     * «тот же сбой или уже другой».
     */
    private static String findAnomaly(Map<?, ?> map)
    {
        StringBuilder out = null;
        int section = 0;
        for (Object parent : labelParents(map))
        {
            section++;
            if (!(Global.invoke(parent, "getChildren") instanceof Iterable<?> children)) //$NON-NLS-1$
                continue;
            Set<Integer> columns = new TreeSet<>();
            Set<String> texts = new HashSet<>();
            List<String> signs = new ArrayList<>();
            int index = -1;
            Object lastLabelText = null;
            for (Object child : children)
            {
                index++;
                boolean label = child != null && child.getClass().getName().contains("LightLabel"); //$NON-NLS-1$
                if (!label)
                    lastLabelText = null;
                if (!label || !Boolean.TRUE.equals(Global.invoke(child, "isVisible"))) //$NON-NLS-1$
                    continue;
                Object text = Global.invoke(child, "getText"); //$NON-NLS-1$
                if (lastLabelText != null)
                    signs.add("подпись без редактора «" + lastLabelText + "»"); //$NON-NLS-1$ //$NON-NLS-2$
                lastLabelText = text != null ? text : ""; //$NON-NLS-1$
                if (Global.invoke(child, "getBounds") instanceof Rectangle bounds) //$NON-NLS-1$
                    columns.add(Integer.valueOf(bounds.x));
                if (index % 2 != 0)
                    signs.add("подпись на месте редактора [" + index + "] «" + text + "»"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                if (text instanceof String value && !value.isEmpty() && !texts.add(value))
                    signs.add("повтор подписи «" + value + "»"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (lastLabelText != null)
                signs.add("подпись без редактора «" + lastLabelText + "»"); //$NON-NLS-1$ //$NON-NLS-2$
            if (columns.size() > 1)
                signs.add("подписи в разных колонках x=" + columns); //$NON-NLS-1$
            if (signs.isEmpty())
                continue;
            if (out == null)
                out = new StringBuilder();
            else
                out.append("; "); //$NON-NLS-1$
            out.append("секция ").append(section).append(": ").append(String.join(", ", signs)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return out != null ? out.toString() : null;
    }

    private static String describeView(IViewPart view, String reason)
    {
        StringBuilder out = new StringBuilder();
        out.append("=== ").append(reason); //$NON-NLS-1$
        Object page = PropertyNameIdentifierHook.resolvePropertySheetPage(view);
        Object scene = page != null ? Global.invoke(page, "getScene") : null; //$NON-NLS-1$
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        out.append(" | страница=").append(simpleName(page)); //$NON-NLS-1$
        if (page != null && Global.invoke(page, "getControl") instanceof Control control && !control.isDisposed()) //$NON-NLS-1$
            out.append(" контрол=").append(control.getBounds()).append(" видим=").append(control.isVisible()); //$NON-NLS-1$ //$NON-NLS-2$
        out.append(" | оверлей «Тип»: ").append(TypeComboOverlayHook.describePropertyOverlay(view)); //$NON-NLS-1$
        if (!(mapObj instanceof Map<?, ?> map))
        {
            out.append(" | карты viewModelToView нет (сцена=").append(simpleName(scene)).append(')'); //$NON-NLS-1$
            return out.toString();
        }

        out.append(" | записей=").append(map.size()).append('\n'); //$NON-NLS-1$
        Set<Object> labelParents = new LinkedHashSet<>();
        int index = 0;
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            Object viewModel = entry.getKey();
            Object nativeControl = Global.invoke(entry.getValue(), "getNativeControl"); //$NON-NLS-1$
            Object parent = Global.invoke(nativeControl, "getParent"); //$NON-NLS-1$
            out.append("  #").append(index++).append(' ').append(simpleName(viewModel)); //$NON-NLS-1$
            if (isLabelViewModel(viewModel))
            {
                Object text = Global.invoke(viewModel, "getText"); //$NON-NLS-1$
                if (text == null)
                    text = Global.getField(viewModel, "text"); //$NON-NLS-1$
                out.append(" «").append(text).append('»'); //$NON-NLS-1$
                if (parent != null)
                    labelParents.add(parent);
            }
            out.append(" → ").append(simpleName(entry.getValue())) //$NON-NLS-1$
                .append(' ').append(describeControl(nativeControl))
                .append(" родитель=").append(identity(parent)).append('\n'); //$NON-NLS-1$
        }

        for (Object parent : labelParents)
            describeParent(out, parent);
        return out.toString();
    }

    /** Родитель подписей и его дети в порядке раскладки; {@code [!]} — подписи в разных колонках. */
    private static void describeParent(StringBuilder out, Object parent)
    {
        Object layout = Global.invoke(parent, "getLayout"); //$NON-NLS-1$
        out.append("  родитель ").append(describeControl(parent)) //$NON-NLS-1$
            .append(" раскладка=").append(describeFields(layout)).append('\n'); //$NON-NLS-1$
        if (!(Global.invoke(parent, "getChildren") instanceof Iterable<?> children)) //$NON-NLS-1$
            return;

        Set<Integer> labelColumns = new TreeSet<>();
        int index = 0;
        for (Object child : children)
        {
            out.append("    [").append(index++).append("] ").append(describeControl(child)) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" данные=").append(describeFields(Global.invoke(child, "getLayoutData"))) //$NON-NLS-1$ //$NON-NLS-2$
                .append('\n');
            if (child != null && child.getClass().getName().contains("LightLabel") //$NON-NLS-1$
                && Boolean.TRUE.equals(Global.invoke(child, "isVisible")) //$NON-NLS-1$
                && Global.invoke(child, "getBounds") instanceof Rectangle bounds) //$NON-NLS-1$
                labelColumns.add(Integer.valueOf(bounds.x));
        }
        if (labelColumns.size() > 1)
            out.append("  [!] подписи родителя ").append(identity(parent)) //$NON-NLS-1$
                .append(" в разных колонках: x=").append(labelColumns).append('\n'); //$NON-NLS-1$
    }

    private static String describeControl(Object control)
    {
        if (control == null)
            return "null"; //$NON-NLS-1$
        StringBuilder out = new StringBuilder();
        out.append(simpleName(control)).append(identity(control))
            .append(' ').append(Global.invoke(control, "getBounds")) //$NON-NLS-1$
            .append(" видим=").append(Global.invoke(control, "isVisible")); //$NON-NLS-1$ //$NON-NLS-2$
        if (Boolean.TRUE.equals(Global.invoke(control, "isDisposed"))) //$NON-NLS-1$
            out.append(" DISPOSED"); //$NON-NLS-1$
        Object text = Global.invoke(control, "getText"); //$NON-NLS-1$
        if (text == null)
            text = Global.invoke(Global.invoke(control, "getContent"), "getText"); //$NON-NLS-1$ //$NON-NLS-2$
        if (text instanceof String value)
            out.append(" «").append(value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) + "…" : value) //$NON-NLS-1$ //$NON-NLS-2$
                .append('»');
        return out.toString();
    }

    /** Имя класса и его простые поля (числа, флаги, перечисления) — колонки, отступы, {@code exclude} и т.п. */
    private static String describeFields(Object object)
    {
        if (object == null)
            return "null"; //$NON-NLS-1$
        List<String> parts = new ArrayList<>();
        for (Class<?> c = object.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        {
            for (Field field : c.getDeclaredFields())
            {
                Class<?> type = field.getType();
                if (Modifier.isStatic(field.getModifiers()) || !(type.isPrimitive() || type.isEnum()))
                    continue;
                try
                {
                    field.setAccessible(true);
                    parts.add(field.getName() + "=" + field.get(object)); //$NON-NLS-1$
                }
                catch (Exception ignored)
                {
                    parts.add(field.getName() + "=?"); //$NON-NLS-1$
                }
            }
        }
        return simpleName(object) + parts;
    }

    private static boolean isLabelViewModel(Object viewModel)
    {
        return viewModel != null && viewModel.getClass().getName().contains("LabelViewModel"); //$NON-NLS-1$
    }

    private static String simpleName(Object object)
    {
        if (object == null)
            return "null"; //$NON-NLS-1$
        String name = object.getClass().getName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String identity(Object object)
    {
        return object != null ? "@" + Integer.toHexString(System.identityHashCode(object)) : "@null"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
