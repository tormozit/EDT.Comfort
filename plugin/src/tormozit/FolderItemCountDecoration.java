package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;

import com._1c.g5.v8.dt.metadata.mdclass.BasicTabularSection;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Серое число элементов у папок коллекций навигатора EDT («Реквизиты 12», «Справочники 340»)
 * и у табличных частей. Ветки с фиксированным составом («Общие») числа не получают —
 * см. {@link #isCountableNode}.
 *
 * <p><b>Основной источник — само дерево, без обращения к модели:</b> сколько дочерних
 * {@link TreeItem} уже создано. Пока папку ни разу не раскрывали, у неё либо нет дочерних
 * item'ов вовсе, либо один фиктивный (JFace создаёт его ради «плюсика»,
 * {@code getData() == null}).
 *
 * <p>Исключение — папки, чей состав мал и уже известен без индекса проекта: внутри объекта
 * метаданных (реквизиты, табличные части, команды, формы, макеты…) и {@link CommonModuleGroupNode}.
 * У них число читается сразу, не дожидаясь раскрытия ({@link #eagerCount}), и только для строк,
 * видимых на экране в этот момент. Коллекции верхнего уровня («Справочники», «Документы») так
 * не считаются — см. там же.
 *
 * <p>После первого раскрытия число уже не пропадает: раз посчитанное значение запоминается
 * в {@link #LAST_KNOWN_COUNTS} и показывается даже после того, как JFace выбросил созданные
 * строки (сворачивание Ctrl+кликом, обновление свёрнутого узла). При каждом раскрытии оно
 * перезаписывается фактическим, так что устареть может только пока узел свёрнут.
 *
 * <p>Отсюда же следует, что число — это то, что реально показано в дереве: при активном
 * фильтре навигатора считаются только прошедшие фильтр строки.
 *
 * <p>Два потребителя, по числу веток подсветки навигатора: {@link NavigatorStyledCellLabelWrapper}
 * (путь {@link ViewerCell}, item под рукой) и {@code NavigatorHighlightStyledProvider} в
 * {@link NavigatorFilterHook} (путь {@link StyledString}, item ищется через
 * {@code viewer.testFindItem} — у {@code CommonViewer} включён hash lookup, это O(1)).
 */
final class FolderItemCountDecoration
{
    /**
     * Базовый класс всех папок-коллекций навигатора EDT (см. {@link #isCountableNode}):
     * {@code NavigatorAdapterBase → VirtualNavigatorAdapterBase → CollectionNavigatorAdapterBase →
     * AttachedCollectionNavigatorAdapterBase →} {@code IndexedContainedObject…} (Реквизиты, Формы)
     * и {@code IndexedTopObject…} (Справочники, Документы).
     */
    private static final String COLLECTION_ADAPTER =
        "com._1c.g5.v8.dt.navigator.adapters.CollectionNavigatorAdapterBase"; //$NON-NLS-1$

    /**
     * Общий базовый класс всех папок навигатора (коллекции и виртуальные группы) — узлы объектов
     * МД его не наследуют. Используется, чтобы не считать фиксированные ветки, см.
     * {@link #isFixedBranch}.
     */
    private static final String VIRTUAL_ADAPTER =
        "com._1c.g5.v8.dt.navigator.adapters.VirtualNavigatorAdapterBase"; //$NON-NLS-1$

    /** Слушатель раскрытия уже поставлен на это дерево. */
    private static final String EXPAND_HOOK_KEY = "tormozit.navigatorFolderItemCount.expandHook"; //$NON-NLS-1$

    /** Предел обхода item'ов в {@link #refreshAfterProgrammaticExpand}. */
    private static final int REFRESH_BUDGET = 20000;

    /** Отложенный досчёт видимых строк — см. {@link #scheduleVisibleScan}. */
    private static final String VISIBLE_SCAN_KEY = "tormozit.folderItemCount.visibleScan"; //$NON-NLS-1$

    /** Пауза после прокрутки, по истечении которой досчитываются видимые строки. */
    private static final int VISIBLE_SCAN_DELAY_MS = 150;

    /** Страховка от зацикливания обхода видимых строк: на экране их десятки, не тысячи. */
    private static final int VISIBLE_ROWS_LIMIT = 500;

    /** Сколько последних узлов помнит {@link #LAST_KNOWN_COUNTS}. */
    private static final int COUNT_CACHE_SIZE = 1000;

    /**
     * Последнее фактически посчитанное число по узлу — чтобы оно не пропадало, когда JFace
     * выбрасывает уже созданные строки.
     *
     * <p>Это не оптимизация, а единственный способ пережить обрезку поддерева:
     * {@code AbstractTreeViewer.optionallyPruneChildren()} безусловно делает {@code disassociate}
     * и {@code dispose} всем дочерним item'ам, оставляя один пустой ради «плюсика». Вызывается она
     * из {@code internalCollapseToLevel} (сворачивание Ctrl+кликом через {@link TreeExpander}) и из
     * {@code updateChildren} (обновление свёрнутого узла), так что без кэша число исчезало бы и
     * после Ctrl+клика, и просто после рефреша дерева.
     *
     * <p>Значение перезаписывается фактическим при каждом раскрытии узла, поэтому устареть оно
     * может только пока узел свёрнут. Ключ — сам элемент навигатора: адаптеры коллекций EDT и
     * {@link CommonModuleGroupNode} переопределяют {@code equals}/{@code hashCode}, поэтому их
     * пересоздание при обновлении дерева кэшу не мешает. Только UI-поток, отсюда обычная
     * {@link LinkedHashMap} в режиме LRU.
     */
    private static final Map<Object, Integer> LAST_KNOWN_COUNTS =
        new LinkedHashMap<>(64, 0.75f, true)
        {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<Object, Integer> eldest)
            {
                return size() > COUNT_CACHE_SIZE;
            }
        };

    private FolderItemCountDecoration() {}

    /**
     * Забыть все посчитанные числа — при смене фильтра дерева.
     *
     * <p>Число всегда означает «сколько строк в дереве», то есть считается уже после фильтра.
     * После смены фильтра запомненные значения относятся к прежнему отбору и стали бы враньём:
     * у свёрнутых папок висело бы старое число, пока их не раскроют. Поэтому кэш сбрасывается
     * целиком, а видимые строки пересчитываются заново.
     */
    static void invalidateCounts()
    {
        LAST_KNOWN_COUNTS.clear();
    }

    /**
     * Пересчёт надписи папки после её раскрытия: в момент отрисовки метки дочерних item'ов
     * ещё не было, поэтому суффикс появился бы только при следующем обновлении ячейки.
     *
     * <p>Плюс досчёт видимых строк после прокрутки, изменения размера и сворачивания: досрочный
     * подсчёт ({@link #eagerCount}) разрешён только для строк на экране, а уехавшие в видимую
     * область при прокрутке иначе остались бы без числа — их надписи JFace сам не обновляет.
     */
    static void installExpandRefresh(ColumnViewer viewer, Tree tree)
    {
        if (viewer == null || tree == null || tree.isDisposed())
            return;
        if (tree.getData(EXPAND_HOOK_KEY) != null)
            return;
        tree.setData(EXPAND_HOOK_KEY, Boolean.TRUE);
        tree.addListener(SWT.Expand, new Listener()
        {
            @Override
            public void handleEvent(Event event)
            {
                if (!(event.item instanceof TreeItem item))
                    return;
                Object element = item.getData();
                if (element == null)
                    return;
                Display display = tree.getDisplay();
                if (display == null || display.isDisposed())
                    return;
                // Дочерние item'ы создаются уже после SWT.Expand — считать можно только следующим циклом.
                display.asyncExec(() -> {
                    if (tree.isDisposed() || item.isDisposed() || viewer.getControl() == null
                        || viewer.getControl().isDisposed())
                    {
                        return;
                    }
                    viewer.update(element, null);
                });
                scheduleVisibleScan(viewer, tree);
            }
        });

        // Прокрутка/изменение размера/сворачивание меняют состав видимых строк — досчитываем их.
        Listener rescan = event -> scheduleVisibleScan(viewer, tree);
        tree.addListener(SWT.Collapse, rescan);
        tree.addListener(SWT.Resize, rescan);
        ScrollBar verticalBar = tree.getVerticalBar();
        if (verticalBar != null && !verticalBar.isDisposed())
            verticalBar.addListener(SWT.Selection, rescan);
        scheduleVisibleScan(viewer, tree);
    }

    /**
     * Отложенный досчёт видимых строк: пока идёт прокрутка, состав видимого меняется каждые
     * несколько миллисекунд — считать имеет смысл только когда она остановилась.
     */
    private static void scheduleVisibleScan(ColumnViewer viewer, Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return;
        Display display = tree.getDisplay();
        if (display == null || display.isDisposed())
            return;
        if (tree.getData(VISIBLE_SCAN_KEY) instanceof Runnable pending)
            display.timerExec(-1, pending);
        Runnable scan = () -> {
            if (tree.isDisposed())
                return;
            tree.setData(VISIBLE_SCAN_KEY, null);
            updateVisibleUncounted(viewer, tree);
        };
        tree.setData(VISIBLE_SCAN_KEY, scan);
        display.timerExec(VISIBLE_SCAN_DELAY_MS, scan);
    }

    /**
     * Обновляет надписи видимых строк, у которых числа ещё нет: при обновлении ячейки сработает
     * досрочный подсчёт ({@link #eagerCount}) — он разрешён только для видимых строк.
     *
     * <p>Строки берутся обходом «следующая видимая» от {@code tree.getTopItem()} вниз, пока не
     * кончится клиентская область. По координатам ({@code tree.getItem(Point)}) не выйдет: на
     * Win32 попадание считается по самой строке, а не по её отступу, поэтому фиксированный x
     * не находит вложенные строки — именно из-за этого досчёт папок внутри объекта не срабатывал.
     */
    private static void updateVisibleUncounted(ColumnViewer viewer, Tree tree)
    {
        Control control = viewer.getControl();
        if (control == null || control.isDisposed())
            return;
        Rectangle client = tree.getClientArea();
        Set<Object> targets = new LinkedHashSet<>();
        int guard = 0;
        for (TreeItem item = tree.getTopItem(); item != null && guard++ < VISIBLE_ROWS_LIMIT;
            item = nextVisibleItem(item))
        {
            Rectangle bounds = item.getBounds();
            if (bounds == null || bounds.height <= 0)
                continue;
            if (bounds.y >= client.y + client.height)
                break;
            Object data = item.getData();
            if (data == null || LAST_KNOWN_COUNTS.containsKey(data) || liveCount(item) != null)
                continue;
            targets.add(data);
        }
        if (!targets.isEmpty())
            viewer.update(targets.toArray(), null);
    }

    /** Следующая строка дерева в порядке отображения: первый ребёнок раскрытого узла, иначе сосед. */
    private static TreeItem nextVisibleItem(TreeItem item)
    {
        if (item.isDisposed())
            return null;
        if (item.getExpanded() && item.getItemCount() > 0)
        {
            TreeItem first = item.getItem(0);
            if (first != null && !first.isDisposed() && first.getData() != null)
                return first;
        }
        for (TreeItem current = item; current != null && !current.isDisposed();)
        {
            TreeItem parent = current.getParentItem();
            int index = parent != null ? parent.indexOf(current) : current.getParent().indexOf(current);
            int count = parent != null ? parent.getItemCount() : current.getParent().getItemCount();
            if (index >= 0 && index + 1 < count)
                return parent != null ? parent.getItem(index + 1) : current.getParent().getItem(index + 1);
            current = parent;
        }
        return null;
    }

    /**
     * Пересчёт надписей после <b>программного</b> разворота поддерева (Ctrl+клик по плюсику,
     * {@link TreeExpander}): {@code AbstractTreeViewer.expandToLevel} создаёт детей и ставит
     * {@code TreeItem.setExpanded} напрямую, события {@link SWT#Expand} при этом не бывает —
     * слушатель {@link #installExpandRefresh} его не видит, и папки остались бы без числа.
     *
     * <p>Обновляются только узлы с уже созданными дочерними item'ами — то есть ровно те, у которых
     * число может появиться; листья (их подавляющее большинство) не трогаются. Обход ограничен
     * {@value #REFRESH_BUDGET} item'ами: Ctrl+клик по корню большой конфигурации разворачивает
     * десятки тысяч строк, и полный обход стоил бы дороже самого разворота.
     *
     * <p>Деревья, не пропатченные {@link #installExpandRefresh} (все прочие деревья EDT, куда
     * {@link TreeExpander} тоже подключён), пропускаются — чисел там нет и обновлять нечего.
     */
    static void refreshAfterProgrammaticExpand(ColumnViewer viewer, Tree tree, TreeItem root)
    {
        if (viewer == null || tree == null || tree.isDisposed() || root == null || root.isDisposed())
            return;
        if (tree.getData(EXPAND_HOOK_KEY) == null)
            return;

        List<Object> targets = new ArrayList<>();
        collectNodesWithChildren(root, targets, new int[] { REFRESH_BUDGET });
        if (targets.isEmpty())
            return;

        tree.setRedraw(false);
        try
        {
            viewer.update(targets.toArray(), null);
        }
        finally
        {
            if (!tree.isDisposed())
                tree.setRedraw(true);
        }
    }

    private static void collectNodesWithChildren(TreeItem item, List<Object> out, int[] budget)
    {
        if (item.isDisposed() || budget[0] <= 0)
            return;
        budget[0]--;
        Object data = item.getData();
        if (data != null && item.getItemCount() > 0)
            out.add(data);
        for (TreeItem child : item.getItems())
            collectNodesWithChildren(child, out, budget);
    }

    /** Суффикс для {@link StyledString}-ветки подсветки; {@code styled} не меняется, если считать нечего. */
    static StyledString appendCount(StyledString styled, Object element, ColumnViewer viewer)
    {
        if (styled == null || viewer == null)
            return styled;
        String count = countText(element, viewer.testFindItem(element));
        if (count == null)
            return styled;
        styled.append(" " + count, StyledString.QUALIFIER_STYLER); //$NON-NLS-1$
        return styled;
    }

    /** Суффикс для {@link ViewerCell}-ветки подсветки: item ячейки уже известен. */
    static void appendCount(ViewerCell cell)
    {
        if (cell == null)
            return;
        String count = countText(cell.getElement(), cell.getItem());
        if (count == null)
            return;
        String text = cell.getText();
        if (text == null || text.isEmpty())
            return;

        String suffix = " " + count; //$NON-NLS-1$
        List<StyleRange> ranges = copyStyleRanges(cell.getStyleRanges());
        ranges.addAll(suffixStyleRanges(suffix, text.length()));
        ranges.sort(Comparator.comparingInt(range -> range.start));

        cell.setText(text + suffix);
        if (!ranges.isEmpty())
            cell.setStyleRanges(ranges.toArray(new StyleRange[0]));
    }

    /**
     * Число дочерних строк, либо {@code null} — если у узла числа не бывает
     * ({@link #isCountableNode}) и его ни разу не раскрывали.
     *
     * <p>Сначала фактическое число по дереву; если строк в виджете нет — последнее известное
     * из {@link #LAST_KNOWN_COUNTS} (см. описание кэша).
     */
    private static String countText(Object element, Widget widget)
    {
        if (!(widget instanceof TreeItem item) || item.isDisposed())
            return null;
        Integer live = liveCount(item);
        if (live != null)
        {
            if (!isCountableNode(element))
                return null;
            LAST_KNOWN_COUNTS.put(element, live);
            return live.toString();
        }
        if (element == null)
            return null;
        // Нет уголка разворачивания — нечего и считать: «плюсик» рисуется ровно тогда, когда у
        // item'а есть хоть одна дочерняя строка (у нераскрытого узла — фиктивная, ради «плюсика»).
        if (item.getItemCount() == 0)
            return null;
        Integer known = LAST_KNOWN_COUNTS.get(element);
        if (known != null)
            return known.toString();
        // Досрочный подсчёт — только для строк, реально видимых сейчас на экране: за полосой
        // прокрутки может быть сколько угодно строк, читать модель ради них незачем. Уехавшие
        // в видимую область досчитываются при прокрутке ({@link #scheduleVisibleScan}).
        if (!isOnScreen(item))
            return null;
        return toText(eagerCount(element));
    }

    /** Строка сейчас в видимой части дерева (не за полосой прокрутки и не в свёрнутой ветке). */
    private static boolean isOnScreen(TreeItem item)
    {
        Tree tree = item.getParent();
        if (tree == null || tree.isDisposed())
            return false;
        Rectangle bounds = item.getBounds();
        if (bounds == null || bounds.height <= 0)
            return false;
        Rectangle client = tree.getClientArea();
        return bounds.y + bounds.height > client.y && bounds.y < client.y + client.height;
    }

    /**
     * Число элементов у ещё не раскрытой папки — только там, где состав мал и уже известен
     * без обращения к индексу проекта:
     * <ul>
     * <li>папки внутри объекта метаданных (реквизиты, табличные части, команды, формы, макеты,
     *     измерения, ресурсы, реквизиты табличной части…) — ссылка модели
     *     ({@code getContentFeature()} у {@code CollectionNavigatorAdapterBase});</li>
     * <li>{@link CommonModuleGroupNode} — {@code getMembers().size()}, список уже на узле.</li>
     * </ul>
     *
     * <p>Коллекции верхнего уровня («Справочники», «Документы», «Общие модули» — наследники
     * {@code IndexedTopObjectCollectionNavigatorAdapterBase}, владелец {@code Configuration})
     * сюда не попадают: там тысячи элементов и запрос к индексу проекта, цена несопоставима
     * с пользой. Они по-прежнему считаются только после раскрытия.
     *
     * <p>Результат кладётся в {@link #LAST_KNOWN_COUNTS}, поэтому на каждую папку чтение
     * происходит один раз, а не на каждое обновление надписи.
     *
     * <p>Число здесь — состав модели, без фильтров дерева: при активном фильтре навигатора
     * оно может оказаться больше, чем появится строк при раскрытии.
     */
    private static Integer eagerCount(Object element)
    {
        if (!isCountableNode(element))
            return null;

        if (element instanceof CommonModuleGroupNode group)
            return cached(element, Integer.valueOf(group.getMembers().size()));

        // Табличная часть — не папка коллекции: её дети (реквизиты) висят прямо на узле.
        MdObject tabularSection = NavigatorTreeElementLabels.resolveMdObject(element);
        if (tabularSection instanceof BasicTabularSection)
            return cached(element, sizeOf(Global.invoke(tabularSection, "getAttributes"))); //$NON-NLS-1$

        if (!isInsideMdObject(element))
            return null;
        Object featureObj = Global.invoke(element, "getContentFeature"); //$NON-NLS-1$
        if (!(featureObj instanceof EStructuralFeature feature))
            return null;
        Object model = Global.invoke(element, "getModel", Boolean.TRUE); //$NON-NLS-1$
        return model instanceof EObject eObject ? cached(element, sizeOf(eObject.eGet(feature))) : null;
    }

    /** Удачно прочитанный размер — в кэш, чтобы на папку приходилось одно чтение модели. */
    private static Integer cached(Object element, Integer size)
    {
        if (size == null)
            return null;
        LAST_KNOWN_COUNTS.put(element, size);
        return size;
    }

    private static Integer sizeOf(Object value)
    {
        return value instanceof Collection<?> collection ? Integer.valueOf(collection.size()) : null;
    }

    /**
     * Папка коллекции внутри объекта метаданных, а не коллекция верхнего уровня.
     *
     * <p>Признак — владелец коллекции: у веток конфигурации («Справочники», «Документы»,
     * «Общие модули») это {@code Configuration}, у остальных — сам объект МД. Класс адаптера
     * для этого не годится: у большинства верхних веток он
     * {@code IndexedTopObjectCollectionNavigatorAdapterBase}, но, например, у «Документов» —
     * {@code DocumentNavigatorAdapter$Folder extends AttachedCollectionNavigatorAdapterBase
     * <Configuration>} (подтверждено декомпиляцией; из-за этого «Документы» какое-то время
     * единственными и просачивались в досрочный подсчёт).
     *
     * <p>{@code getModel(false)} — чтение поля адаптера без обращения к BM (флаг включает
     * перечитывание объекта через {@code bmGetEngine().getObjectById()}, здесь оно не нужно).
     */
    private static boolean isInsideMdObject(Object element)
    {
        Object owner = Global.invoke(element, "getModel", Boolean.FALSE); //$NON-NLS-1$
        return owner != null && !(owner instanceof Configuration);
    }

    /**
     * Число уже созданных дочерних строк узла, либо {@code null}, если их нет.
     *
     * <p>Фиксированные ветки не считаются: под «Документами» лежат «Нумераторы» и
     * «Последовательности» — это отдельные коллекции конфигурации, а не документы, и в числе
     * документов им не место. Отличаются они базовым классом адаптера: у папок это
     * {@code VirtualNavigatorAdapterBase} (модели у узла нет), у объектов МД — своя иерархия
     * ({@code DocumentNavigatorAdapter extends MdTopObjectNavigatorAdapterBase<Document>}).
     */
    private static Integer liveCount(TreeItem item)
    {
        int total = item.getItemCount();
        if (total == 0)
            return null;
        int counted = 0;
        boolean anyRealItem = false;
        for (int index = 0; index < total; index++)
        {
            TreeItem child = item.getItem(index);
            Object data = child != null && !child.isDisposed() ? child.getData() : null;
            // item без данных — фиктивный «плюсик» JFace: дети ещё не загружены.
            if (data == null)
                continue;
            anyRealItem = true;
            if (!isFixedBranch(data))
                counted++;
        }
        return anyRealItem ? Integer.valueOf(counted) : null;
    }

    /** Узел-папка навигатора (коллекция, виртуальная группа), а не элемент коллекции. */
    private static boolean isFixedBranch(Object element)
    {
        if (element instanceof CommonModuleGroupNode)
            return true;
        for (Class<?> type = element.getClass(); type != null; type = type.getSuperclass())
        {
            if (VIRTUAL_ADAPTER.equals(type.getName()))
                return true;
        }
        return false;
    }

    private static String toText(Integer count)
    {
        return count != null ? count.toString() : null;
    }

    /**
     * Узлы, у которых показывается число: папки коллекций («Реквизиты», «Формы», «Справочники»,
     * «Табличные части»…), сами табличные части (их дети — реквизиты, отдельной папки нет) и наши
     * группы общих модулей.
     *
     * <p>Ветки с фиксированным составом — «Общие» и подобные виртуальные папки навигатора —
     * сюда намеренно не входят: число у них бессмысленно. Поэтому проверка идёт по
     * {@code CollectionNavigatorAdapterBase} (папка коллекции по одной ссылке модели), а не по
     * более широкому {@code VirtualNavigatorAdapterBase} / {@link NavigatorTreeElementLabels#isGroupNode}.
     *
     * <p>{@code isGroupNode} здесь не годится и по второй причине: для папок внутри объекта он даёт
     * {@code false} — резолвит у них полное имя объекта-владельца («Документ.Расходный…») и считает
     * их узлом объекта. Узел самого объекта МД в иерархию коллекций не входит (у него адаптер
     * {@code *FolderNavigatorAdapter}), поэтому числа дочерних папок у объекта не появляется.
     */
    private static boolean isCountableNode(Object element)
    {
        if (element == null)
            return false;
        if (element instanceof CommonModuleGroupNode)
            return true;
        for (Class<?> type = element.getClass(); type != null; type = type.getSuperclass())
        {
            if (COLLECTION_ADAPTER.equals(type.getName()))
                return true;
        }
        return NavigatorTreeElementLabels.resolveMdObject(element) instanceof BasicTabularSection;
    }

    private static List<StyleRange> copyStyleRanges(StyleRange[] source)
    {
        List<StyleRange> ranges = new ArrayList<>();
        if (source == null)
            return ranges;
        for (StyleRange range : source)
        {
            if (range != null)
                ranges.add((StyleRange)range.clone());
        }
        return ranges;
    }

    private static List<StyleRange> suffixStyleRanges(String suffix, int offset)
    {
        List<StyleRange> result = new ArrayList<>();
        StyledString styled = new StyledString();
        styled.append(suffix, StyledString.QUALIFIER_STYLER);
        StyleRange[] ranges = styled.getStyleRanges();
        if (ranges == null)
            return result;
        for (StyleRange range : ranges)
        {
            if (range == null)
                continue;
            StyleRange copy = (StyleRange)range.clone();
            copy.start += offset;
            result.add(copy);
        }
        return result;
    }
}
