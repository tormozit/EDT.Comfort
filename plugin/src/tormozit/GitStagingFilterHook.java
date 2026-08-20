package tormozit;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.DropTargetListener;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.TransferData;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Многословный фильтр ({@link SmartMatcher}, AND по словам) в панели «Индексирование Git»
 * ({@code com._1c.g5.v8.dt.internal.team.ui.views.DtStagingView}, наследник EGit
 * {@code org.eclipse.egit.ui.internal.staging.StagingView}).
 *
 * <p>Разведка декомпиляцией + рефлексией показала: списки «Индексированные»/«Неиндексированные
 * изменения» — {@code TreeViewer} (поля {@code stagedViewer}/{@code unstagedViewer}), общее поле
 * поиска {@code filterText} ({@code Text}, обычный wildcard EGit) на весь вид. У {@code DtStagingView}
 * своего {@code SearchBox}/{@code ViewerFilter} нет.
 *
 * <p>Штатный {@code StagingViewContentProvider} матчит только {@code entry.getPath()} — для
 * фильтрации по «ПолноеИмя + ИмяФайла» (полное имя объекта метаданных не хранится в пути) этого
 * недостаточно, поэтому вместо подмены штатного {@code Pattern} используем свой
 * {@link GitStagingSearchFilter} поверх дерева (с текстом матчинга — {@link #matchText}), а штатное
 * поле {@code filterPattern} оставляем всегда {@code null} (штатный контент-провайдер отдаёт все
 * элементы без своей фильтрации — реальную фильтрацию делает уже наш {@code ViewerFilter}).
 *
 * <p><b>Не {@link SmartOutlineFilter}:</b> тот у листьев матчит через {@code matcher.matchesTree()} —
 * при однословном (без точек) запросе это фактически {@code matches()} только по ПОСЛЕДНЕМУ
 * dot-сегменту текста узла (рассчитан на «имя типа»/«имя узла дерева» без внутренних точек). Наш
 * {@link #matchText} сам содержит точки («ОбщийМодуль.малыйМодуль.Модуль Module.bsl») — с
 * {@code SmartOutlineFilter} матчилось бы только «bsl» после последней точки, теряя всё остальное
 * (обнаружено на «малы мо»: не находило «малыйМодуль», хотя оба фрагмента в тексте есть). Поэтому
 * {@link GitStagingSearchFilter} матчит листья простым {@code matcher.matches(text)} (плоский AND
 * по фрагментам, без dot-иерархии), а «показать папку, если внутри есть совпадение» — своей
 * рекурсией по {@link ITreeContentProvider}.
 *
 * <p><b>Производительность (тысячи изменённых файлов):</b> на каждый элемент дерева матчинг
 * дёргает рефлексию ({@code element.getPath()}) и {@link GetRef#resolveFullNameOrNull} — при
 * наивном пересчёте на каждое нажатие клавиши синхронно на UI-потоке это ощутимо тормозит и
 * блокирует UI на тысячах файлов. Поэтому набор текста дебаунсится (см. {@link #DEBOUNCE_MS},
 * {@link FilterSession}), а сам обход дерева и матчинг переносятся в фоновый {@link Job}
 * (по образцу {@code CompareConfigSearchDialogHook} — счётчик поколений {@code activeGeneration},
 * отмена устаревшего job'а, применение результата через {@code asyncExec}). Результат обхода —
 * {@code Map<Object,Boolean>} по идентичности узла — кладётся в {@link GitStagingSearchFilter}
 * как {@code precomputedMatches}; {@code select()} на UI-потоке становится O(1)-поиском по этой
 * карте. Если элемента в карте нет (узел появился уже после фонового прохода — например, штатный
 * refresh вида из-за внешнего изменения git-статуса во время набора текста), {@code select()}
 * откатывается на старый инлайн-подсчёт (тот же код, что был до этой правки) — только для
 * непокрытых узлов, не для всего дерева.
 *
 * <p>Дерево получает колонки «Имя»/«Тип»/«Путь»/«Время изменения»/«Статус»
 * ({@link #GitStagingLabelProvider}, {@link #installColumns}) — «Путь» через
 * {@link GetRef#resolveFullNameOrNull}, как в панели «Результаты поиска» ({@link FileSearchResultsHook}),
 * но отдельной колонкой, а не дописыванием в текст строки. Порядок и ширины колонок
 * синхронизируются между {@code stagedViewer}/{@code unstagedViewer}
 * ({@link #saveColumnState}, {@link #syncWidthToPeer}/{@link #syncOrderToPeer}).
 *
 * <p>Выбор ячейки, подсветка активной ячейки/строки и копирование текста ячейки по Ctrl+C —
 * {@link #GitStagingTreeInteraction} + {@link CopyCommandSupport#wireCopyOverride} (control остаётся
 * штатным {@code Tree}/{@code TreeViewer} — мультивыделение Ctrl/Shift, drag&drop stage/unstage,
 * открытие сравнения по двойному клику и автоподстановка commit message остаются штатными EGit,
 * не переопределяются). Сброс объекта метаданных из навигатора, последних мест
 * или наборов объектов на дерево — отдельно: снимает выделение и выделяет файлы
 * этого объекта без вложенных (форм, макетов, команд…); штатный drop EGit для
 * такого жеста перехватывается, иначе он мог бы индексировать всю папку объекта.
 * Штатное контекстное меню только дополняется группой пунктов отбора по значению ячейки (issue
 * #266, п.2–5: «Отобрать/Снять отбор», «Различные значения колонки», «Отключить все отборы»,
 * «Отобрано элементов: N») — {@link TreeColumnValueFilterSupport}, общий код с {@link
 * FormTableInteraction} через {@link ColumnFilterMenuBuilder}. Группа идёт в КОРЕНЬ меню (не в
 * подменю «Комфорт»), обрамлённая разделителями. Текст ячейки для отбора — тот же {@link
 * #sortKey}, что и для сортировки колонок.
 *
 * <p>Колонки и фильтр файлов: Параметры → Комфорт → «Улучшать списки»
 * ({@link ComfortSettings#PREF_REPLACE_LIST_FILTERS}). Проверяется при установке патча
 * (открытие панели), а не только на старте EDT — как {@link GitHistoryHook}.
 *
 * <p>Логирование: Параметры → Комфорт → «Общее логирование».
 */
public final class GitStagingFilterHook implements IStartup
{
    private static final String VIEW_ID = "com._1c.g5.v8.dt.internal.team.ui.views.DtStagingView"; //$NON-NLS-1$
    private static final String PATCHED_KEY = "tormozit.gitStagingFilterPatched"; //$NON-NLS-1$
    private static final String HISTORY_SCOPE_ID = "gitStagingFilter"; //$NON-NLS-1$

    /** Пауза после последнего нажатия клавиши, прежде чем реально пересчитать фильтр. */
    private static final int DEBOUNCE_MS = 200;

    /** Бюджет ожидания готовности вида: 30×100 мс + 90×500 мс ≈ 48 с (холодный старт EDT). */
    private static final int MAX_PATCH_ATTEMPTS = 120;

    /** Виды с активной цепочкой ретраев (UI-поток) — одна цепочка на вид. */
    private static final Set<IViewPart> pendingRetries =
        Collections.newSetFromMap(new IdentityHashMap<>());

    // -----------------------------------------------------------------------
    // Колонки дерева (Имя/Тип/Путь/Время изменения/Статус)
    // -----------------------------------------------------------------------

    private static final int COL_NAME = 0;
    private static final int COL_TYPE = 1;
    private static final int COL_PATH = 2;
    private static final int COL_TIME = 3;
    private static final int COL_STATUS = 4;
    private static final int COLUMN_COUNT = 5;

    private static final String[] COLUMN_KEYS =
        { "Name", "Type", "Path", "Time", "Status" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    private static final String[] COLUMN_HEADERS =
        { "Имя", "Тип", "Путь", "Время изменения", "Статус" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    private static final String[] COLUMN_TOOLTIPS =
    {
        "Имя файла", //$NON-NLS-1$
        "Расширение файла", //$NON-NLS-1$
        "Полное имя объекта метаданных", //$NON-NLS-1$
        "Время изменения файла на диске", //$NON-NLS-1$
        "Статус изменения" //$NON-NLS-1$
    };
    private static final int[] COLUMN_DEFAULT_WIDTHS = { 220, 55, 220, 95, 100 };

    private static final String COLUMN_LOGICAL_KEY = "tormozit.gitStagingColumnLogical"; //$NON-NLS-1$
    private static final String INTERACTION_KEY = "tormozit.gitStagingTreeInteraction"; //$NON-NLS-1$
    private static final String COLUMN_SETTINGS_SECTION = "GitStagingColumns"; //$NON-NLS-1$
    private static final String NAVIGATOR_DROP_KEY = "tormozit.gitStagingNavigatorDrop"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
                return;
            for (IWorkbenchWindow window : wb.getWorkbenchWindows())
                hookWindow(window);
            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
            Debug.log("earlyStartup: installed"); //$NON-NLS-1$
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        for (IWorkbenchPage page : window.getPages())
        {
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (isGitStagingView(view))
                    schedulePatch(view, 0);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r) {}
            @Override public void partDeactivated(IWorkbenchPartReference r) {}
            @Override public void partHidden(IWorkbenchPartReference r) {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}

            private void tryFromRef(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (isGitStagingView(part))
                    schedulePatch((IViewPart) part, 0);
            }
        });
    }

    private static boolean isGitStagingView(Object part)
    {
        if (!(part instanceof IViewPart view))
            return false;
        return VIEW_ID.equals(view.getSite().getId());
    }

    // -----------------------------------------------------------------------
    // Настройки колонок (порядок/ширина) — общие для stagedViewer/unstagedViewer.
    // -----------------------------------------------------------------------

    private static IDialogSettings columnSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(COLUMN_SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(COLUMN_SETTINGS_SECTION);
        return section;
    }

    /** Общий ключ (не раздельно staged/unstaged) — колонки в обеих панелях синхронны по порядку/ширине. */
    private static String orderKey()
    {
        return "columnOrder"; //$NON-NLS-1$
    }

    private static String widthKey(int logical)
    {
        return "colWidth" + COLUMN_KEYS[logical]; //$NON-NLS-1$
    }

    private static int[] defaultOrder()
    {
        int[] result = new int[COLUMN_COUNT];
        for (int i = 0; i < COLUMN_COUNT; i++)
            result[i] = i;
        return result;
    }

    /** Сохранённый порядок — только если это перестановка всех {@link #COLUMN_COUNT} колонок. */
    private static int[] loadOrder(IDialogSettings settings)
    {
        int[] def = defaultOrder();
        String raw = settings.get(orderKey());
        if (raw == null || raw.isBlank())
            return def;
        String[] parts = raw.split(","); //$NON-NLS-1$
        if (parts.length != COLUMN_COUNT)
            return def;
        boolean[] seen = new boolean[COLUMN_COUNT];
        int[] order = new int[COLUMN_COUNT];
        for (int i = 0; i < parts.length; i++)
        {
            int logical;
            try
            {
                logical = Integer.parseInt(parts[i].trim());
            }
            catch (NumberFormatException ex)
            {
                return def;
            }
            if (logical < 0 || logical >= COLUMN_COUNT || seen[logical])
                return def;
            seen[logical] = true;
            order[i] = logical;
        }
        return order;
    }

    /** Пересоздаёт TreeColumn по сохранённым порядку/ширинам (идемпотентно). */
    private static void installColumns(Tree tree, IDialogSettings settings, IViewPart view)
    {
        if (tree == null || tree.isDisposed())
            return;
        for (TreeColumn c : tree.getColumns())
            c.dispose();
        int[] order = loadOrder(settings);
        for (int logical : order)
        {
            TreeColumn col = new TreeColumn(tree, SWT.LEFT);
            col.setText(COLUMN_HEADERS[logical]);
            col.setToolTipText(COLUMN_TOOLTIPS[logical] + Global.pluginSignForTooltip());
            col.setResizable(true);
            col.setMoveable(true);
            col.setWidth(FormTableColumnState.readWidth(settings, widthKey(logical),
                COLUMN_DEFAULT_WIDTHS[logical], 20));
            col.setData(COLUMN_LOGICAL_KEY, Integer.valueOf(logical));
            col.addListener(SWT.Selection, e ->
            {
                Object data = tree.getData(INTERACTION_KEY);
                if (data instanceof GitStagingTreeInteraction interaction)
                    interaction.sortBy(logical, col);
            });
            col.addControlListener(new ControlAdapter()
            {
                @Override
                public void controlResized(ControlEvent e)
                {
                    syncWidthToPeer(view, tree, logical, col.getWidth());
                }

                @Override
                public void controlMoved(ControlEvent e)
                {
                    syncOrderToPeer(view, tree);
                }
            });
        }
        tree.setHeaderVisible(true);
        tree.setLinesVisible(true);
    }

    /** Порядок + ширины — одним проходом (ключи общие, не per-viewer). */
    private static void saveColumnState(Tree tree, IDialogSettings settings)
    {
        if (tree == null || tree.isDisposed() || tree.getColumnCount() <= 0)
            return;
        int[] visualOrder = tree.getColumnOrder();
        StringBuilder orderStr = new StringBuilder();
        for (int i = 0; i < visualOrder.length; i++)
        {
            TreeColumn col = tree.getColumn(visualOrder[i]);
            Object logicalObj = col.getData(COLUMN_LOGICAL_KEY);
            int logical = logicalObj instanceof Integer li ? li : COL_NAME;
            if (i > 0)
                orderStr.append(','); //$NON-NLS-1$
            orderStr.append(logical);
            settings.put(widthKey(logical), Integer.toString(col.getWidth()));
        }
        settings.put(orderKey(), orderStr.toString());
    }

    private static int logicalOfColumn(Tree tree, int physicalCol)
    {
        if (tree == null || tree.isDisposed() || physicalCol < 0 || physicalCol >= tree.getColumnCount())
            return COL_NAME;
        Object data = tree.getColumn(physicalCol).getData(COLUMN_LOGICAL_KEY);
        return data instanceof Integer li ? li : COL_NAME;
    }

    // -----------------------------------------------------------------------
    // Живая синхронизация ширины/порядка колонок между stagedViewer/unstagedViewer
    // -----------------------------------------------------------------------

    private static final String SYNC_SUPPRESS_KEY = "tormozit.gitStagingColumnSyncSuppress"; //$NON-NLS-1$

    private static Tree otherTree(IViewPart view, Tree tree)
    {
        TreeViewer staged = treeViewerOf(view, "stagedViewer"); //$NON-NLS-1$
        TreeViewer unstaged = treeViewerOf(view, "unstagedViewer"); //$NON-NLS-1$
        Tree stagedTree = staged != null ? staged.getTree() : null;
        Tree unstagedTree = unstaged != null ? unstaged.getTree() : null;
        if (tree == stagedTree)
            return unstagedTree;
        if (tree == unstagedTree)
            return stagedTree;
        return null;
    }

    private static TreeColumn columnByLogical(Tree tree, int logical)
    {
        for (TreeColumn c : tree.getColumns())
        {
            Object data = c.getData(COLUMN_LOGICAL_KEY);
            if (data instanceof Integer li && li == logical)
                return c;
        }
        return null;
    }

    private static int indexOf(Tree tree, TreeColumn col)
    {
        TreeColumn[] cols = tree.getColumns();
        for (int i = 0; i < cols.length; i++)
            if (cols[i] == col)
                return i;
        return 0;
    }

    private static int[] currentLogicalOrder(Tree tree)
    {
        int[] visual = tree.getColumnOrder();
        int[] order = new int[visual.length];
        for (int i = 0; i < visual.length; i++)
        {
            Object data = tree.getColumn(visual[i]).getData(COLUMN_LOGICAL_KEY);
            order[i] = data instanceof Integer li ? li : COL_NAME;
        }
        return order;
    }

    /** {@code false} — набор логических колонок на дереве не совпал с {@code logicalOrder}. */
    private static boolean applyLogicalOrder(Tree tree, int[] logicalOrder)
    {
        if (tree.getColumnCount() != logicalOrder.length)
            return false;
        int[] physicalOrder = new int[logicalOrder.length];
        for (int i = 0; i < logicalOrder.length; i++)
        {
            TreeColumn col = columnByLogical(tree, logicalOrder[i]);
            if (col == null)
                return false;
            physicalOrder[i] = indexOf(tree, col);
        }
        tree.setColumnOrder(physicalOrder);
        return true;
    }

    /** Ширина колонки изменена drag'ом (или программно) — переносим на ту же логическую колонку соседнего дерева. */
    private static void syncWidthToPeer(IViewPart view, Tree sourceTree, int logical, int width)
    {
        if (Boolean.TRUE.equals(sourceTree.getData(SYNC_SUPPRESS_KEY)))
            return;
        Tree peer = otherTree(view, sourceTree);
        if (peer == null || peer.isDisposed())
            return;
        TreeColumn peerCol = columnByLogical(peer, logical);
        if (peerCol == null || peerCol.getWidth() == width)
            return;
        peer.setData(SYNC_SUPPRESS_KEY, Boolean.TRUE);
        try
        {
            peerCol.setWidth(width);
        }
        finally
        {
            peer.setData(SYNC_SUPPRESS_KEY, null);
        }
    }

    /** Колонка перетащена на новое место — переносим тот же порядок на соседнее дерево. */
    private static void syncOrderToPeer(IViewPart view, Tree sourceTree)
    {
        if (Boolean.TRUE.equals(sourceTree.getData(SYNC_SUPPRESS_KEY)))
            return;
        Tree peer = otherTree(view, sourceTree);
        if (peer == null || peer.isDisposed())
            return;
        int[] logicalOrder = currentLogicalOrder(sourceTree);
        peer.setData(SYNC_SUPPRESS_KEY, Boolean.TRUE);
        try
        {
            applyLogicalOrder(peer, logicalOrder);
        }
        finally
        {
            peer.setData(SYNC_SUPPRESS_KEY, null);
        }
    }

    /**
     * Ретраи установки патча. При восстановлении вида на старте EDT {@code filterText} и оба
     * {@code TreeViewer}'а готовы далеко не сразу (панель ждёт загрузки репозитория), а прежний
     * бюджет 20×100 мс = 2 с на холодном старте истекал раньше — патч «иногда не применялся».
     * Поэтому ждём десятки секунд с замедлением и прекращаем только когда вид закрыт.
     *
     * <p>Цепочка ретраев на вид ровно одна: {@code partVisible}/{@code partActivated} приходят
     * пачками, и без этого каждый event плодил бы свою цепочку.
     */
    private static void schedulePatch(IViewPart view, int attempt)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
        {
            pendingRetries.remove(view);
            return;
        }
        if (attempt == 0 && !pendingRetries.add(view))
            return;
        Display display = Display.getDefault();
        int delay = attempt == 0 ? 0 : attempt < 30 ? 100 : 500;
        display.timerExec(delay, () ->
        {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
            {
                pendingRetries.remove(view);
                return;
            }
            if (isViewGone(view))
            {
                pendingRetries.remove(view);
                Debug.log("tryPatch STOP: вид закрыт"); //$NON-NLS-1$
                return;
            }
            if (tryPatch(view))
            {
                pendingRetries.remove(view);
                return;
            }
            if (attempt >= MAX_PATCH_ATTEMPTS)
            {
                pendingRetries.remove(view);
                Debug.log("tryPatch GIVE UP after " + MAX_PATCH_ATTEMPTS + " attempts"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            schedulePatch(view, attempt + 1);
        });
    }

    /** Вид закрыт (или уже не принадлежит своей странице) — ретраи больше не нужны. */
    private static boolean isViewGone(IViewPart view)
    {
        IWorkbenchPartSite site = view.getSite();
        if (site == null || site.getPage() == null)
            return true;
        for (IViewReference ref : site.getPage().getViewReferences())
            if (ref.getPart(false) == view)
                return false;
        return true;
    }

    private static boolean tryPatch(IViewPart view)
    {
        try
        {
            boolean dropReady = installNavigatorDropOnView(view);
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return dropReady;

            Object filterTextObj = Global.getField(view, "filterText"); //$NON-NLS-1$
            if (!(filterTextObj instanceof Text filterText) || filterText.isDisposed())
            {
                Debug.log("tryPatch WAIT: filterText=" //$NON-NLS-1$
                    + (filterTextObj == null ? "null" : filterTextObj.getClass().getName())); //$NON-NLS-1$
                return false;
            }
            if (Boolean.TRUE.equals(filterText.getData(PATCHED_KEY)))
                return dropReady;

            GitStagingSearchFilter stagedFilter = installViewer(view, "stagedViewer"); //$NON-NLS-1$
            GitStagingSearchFilter unstagedFilter = installViewer(view, "unstagedViewer"); //$NON-NLS-1$
            // Нужны ОБА дерева: FilterSession держит оба фильтра (при null второго — NPE в
            // clearViewer), а PATCHED_KEY закрыл бы повтор, и «опоздавшее» дерево осталось бы
            // без колонок и фильтра навсегда. installViewer идемпотентен — уже пропатченное
            // дерево на следующей попытке вернёт свой существующий фильтр.
            if (stagedFilter == null || unstagedFilter == null)
            {
                Debug.log("tryPatch WAIT: viewers staged=" + (stagedFilter != null) //$NON-NLS-1$
                    + " unstaged=" + (unstagedFilter != null)); //$NON-NLS-1$
                return false;
            }

            // Штатный Pattern больше не используется нашим фильтром — держим поле пустым,
            // чтобы StagingViewContentProvider отдавал все элементы без своей фильтрации.
            Global.setField(view, "filterPattern", null); //$NON-NLS-1$

            stripModifyListeners(filterText);
            filterText.setData(PATCHED_KEY, Boolean.TRUE);
            FilterSession session = new FilterSession(view, filterText, stagedFilter, unstagedFilter);
            filterText.addListener(SWT.Modify, e -> session.onModify());
            filterText.setToolTipText(
                FilterInputBox.FLAT_FILTER_TOOLTIP + "\nCtrl+↓ — история запросов."); //$NON-NLS-1$
            FilterHistoryUi.wireKeyboard(filterText, HISTORY_SCOPE_ID);
            addHistoryButton(filterText);

            TreeViewer unstaged = treeViewerOf(view, "unstagedViewer"); //$NON-NLS-1$
            Tree unstagedTree = unstaged != null ? unstaged.getTree() : null;
            if (unstagedTree != null && !unstagedTree.isDisposed())
                FilterInputBoxListNavigation.installTreeNavigation(filterText, unstagedTree);

            session.onModify();

            Debug.log("tryPatch PATCH OK"); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            Debug.log("tryPatch EXCEPTION: " + e); //$NON-NLS-1$
            return false;
        }
    }

    private static void stripModifyListeners(Text filterText)
    {
        for (Listener l : filterText.getListeners(SWT.Modify))
            filterText.removeListener(SWT.Modify, l);
    }

    /**
     * Видимая кнопка-стрелка (▾) справа от {@code filterText}. Родитель поля в {@code DtStagingView} —
     * {@code GridLayout} с {@code filterText} как единственным дочерним элементом; сам
     * {@code filterText} держал широкий (нет {@code GridData}/натуральный) размер, поэтому после
     * добавления второй колонки ему нужен явный {@code grab+fill}, иначе колонка с кнопкой
     * вылезает за пределы родителя (подтверждено диагностикой, снята после фикса).
     */
    private static void addHistoryButton(Text filterText)
    {
        try
        {
            Composite parent = filterText.getParent();
            if (parent == null || parent.isDisposed())
                return;
            Composite row = FilterHistoryUi.createButtonsRow(parent);
            FilterHistoryUi.addHistoryButton(row, filterText, HISTORY_SCOPE_ID);
            filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            parent.layout(true, true);
        }
        catch (Exception e)
        {
            Debug.log("addHistoryButton EXCEPTION: " + e); //$NON-NLS-1$
        }
    }

    /** Оборачивает label provider и вешает {@link GitStagingSearchFilter}; {@code null}, если вьювер не готов. */
    private static GitStagingSearchFilter installViewer(IViewPart view, String viewerField)
    {
        Object viewerObj = Global.getField(view, viewerField);
        if (!(viewerObj instanceof TreeViewer viewer))
            return null;

        IBaseLabelProvider current = viewer.getLabelProvider();
        if (current instanceof GitStagingLabelProvider)
        {
            GitStagingSearchFilter filter = findExistingFilter(viewer);
            return filter != null ? filter : attachFilter(viewer);
        }
        if (!(current instanceof CellLabelProvider cellLp))
        {
            Debug.log("installViewer " + viewerField + ": lp=" //$NON-NLS-1$ //$NON-NLS-2$
                + (current != null ? current.getClass().getName() : "null")); //$NON-NLS-1$
            return null;
        }

        Tree tree = viewer.getTree();
        viewer.setLabelProvider(new GitStagingLabelProvider(cellLp, tree));
        installColumnsAndInteraction(view, viewer, tree, viewerField);
        return attachFilter(viewer);
    }

    /** Первичная установка колонок + Tree-взаимодействия (выбор ячейки/подсветка/копирование/сортировка) — один раз на дерево. */
    private static void installColumnsAndInteraction(IViewPart view, TreeViewer viewer, Tree tree, String viewerField)
    {
        IDialogSettings settings = columnSettings();
        installColumns(tree, settings, view);

        GitStagingTreeInteraction interaction = new GitStagingTreeInteraction(tree, viewer);
        interaction.install();
        // Колонки растягиваются с панелью (issue #273). Ставится после installColumns: подгонка живёт на
        // дереве, поэтому переживает их пересоздание, а первичный проход идёт по уже готовым колонкам.
        ColumnAutoFit.install(tree, t -> stagingWidthBudget(view, t));
        tree.addDisposeListener(e -> saveColumnState(tree, columnSettings()));

        TreeColumnValueFilterSupport.CellTextResolver textResolver = (element, physicalColumn) ->
            sortKey(element, logicalOfColumn(tree, physicalColumn));
        new TreeColumnValueFilterSupport(viewer, tree, textResolver,
            interaction::activeElement, interaction::activeColumnIndex).install();

        Debug.log("installColumnsAndInteraction " + viewerField + ": columns=" //$NON-NLS-1$ //$NON-NLS-2$
            + tree.getColumnCount());
    }

    /**
     * Штатный DropTarget EGit уже есть (stage/unstage между списками). Общий
     * {@link NavigatorDropSearchHook} такие списки пропускает. Вешаем свой слушатель
     * поверх: для объекта из навигатора перехватываем drop, чтобы EGit не пробовал
     * индексировать папку объекта целиком, и выделяем файлы объекта без вложенных.
     */
    private static boolean installNavigatorDropOnView(IViewPart view)
    {
        TreeViewer staged = treeViewerOf(view, "stagedViewer"); //$NON-NLS-1$
        TreeViewer unstaged = treeViewerOf(view, "unstagedViewer"); //$NON-NLS-1$
        if (staged == null || unstaged == null)
            return false;
        return installNavigatorDrop(staged) && installNavigatorDrop(unstaged);
    }

    private static boolean installNavigatorDrop(TreeViewer viewer)
    {
        if (viewer == null)
            return false;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return false;
        if (Boolean.TRUE.equals(tree.getData(NAVIGATOR_DROP_KEY)))
            return true;
        Object existing = tree.getData(DND.DROP_TARGET_KEY);
        if (!(existing instanceof DropTarget target) || target.isDisposed())
            return false;
        ensureLocalSelectionTransfer(target);
        DropTargetListener[] originals = target.getDropListeners();
        if (originals == null)
            originals = new DropTargetListener[0];
        for (DropTargetListener listener : originals)
            target.removeDropListener(listener);
        target.addDropListener(new NavigatorSelectDropListener(viewer, originals));
        tree.setData(NAVIGATOR_DROP_KEY, Boolean.TRUE);
        Debug.log("installNavigatorDrop tree=" + tree.hashCode() //$NON-NLS-1$
            + " wrapped=" + originals.length); //$NON-NLS-1$
        return true;
    }

    private static void ensureLocalSelectionTransfer(DropTarget target)
    {
        Transfer local = LocalSelectionTransfer.getTransfer();
        Transfer[] current = target.getTransfer();
        if (current != null)
        {
            for (Transfer transfer : current)
            {
                if (transfer == local)
                    return;
            }
            Transfer[] expanded = new Transfer[current.length + 1];
            System.arraycopy(current, 0, expanded, 0, current.length);
            expanded[current.length] = local;
            target.setTransfer(expanded);
        }
        else
            target.setTransfer(new Transfer[] { local });
    }

    private static void preferLocalSelectionDataType(DropTargetEvent event)
    {
        Transfer local = LocalSelectionTransfer.getTransfer();
        if (event.currentDataType != null && local.isSupportedType(event.currentDataType))
            return;
        TransferData[] types = event.dataTypes;
        if (types == null)
            return;
        for (TransferData type : types)
        {
            if (local.isSupportedType(type))
            {
                event.currentDataType = type;
                return;
            }
        }
    }

    private static List<String> draggedObjectFullNames()
    {
        Object selObj = LocalSelectionTransfer.getTransfer().getSelection();
        if (!(selObj instanceof IStructuredSelection sel) || sel.isEmpty())
            return List.of();
        if (isEgitStagingDrag(sel))
            return List.of();
        List<String> names = new ArrayList<>();
        for (Object element : sel.toArray())
        {
            if (element == null)
                continue;
            if (isStagingModelElement(element))
                return List.of();
            String fullName = fullNameFromDraggedElement(element);
            if (fullName != null && !fullName.isBlank())
                names.add(fullName);
        }
        return names;
    }

    private static String fullNameFromDraggedElement(Object element)
    {
        if (element instanceof RecentPlaces.Entry entry)
            return RecentPlacesKeys.mdObjectRef(entry);
        if (element instanceof ObjectSets.Item item)
            return RecentPlacesKeys.mdObjectRefFromKey(item.key);
        if (NavigatorTreeElementLabels.isGroupNode(element))
            return null;
        return GetRef.fullNameFromNavigatorElement(element);
    }

    private static boolean isEgitStagingDrag(IStructuredSelection sel)
    {
        return sel.getClass().getName().contains("StagingDragSelection"); //$NON-NLS-1$
    }

    private static boolean isStagingModelElement(Object element)
    {
        String className = element.getClass().getName();
        return className.contains("StagingEntry") //$NON-NLS-1$
            || className.contains("StagingFolderEntry"); //$NON-NLS-1$
    }

    private static void selectOwnFiles(TreeViewer viewer, List<String> droppedNames)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        GitStagingTreeInteraction interaction = interactionOf(tree);
        if (interaction != null)
            interaction.clearSelection();
        else
        {
            tree.deselectAll();
            viewer.setSelection(StructuredSelection.EMPTY, false);
        }

        List<Object> matches = new ArrayList<>();
        if (droppedNames != null && !droppedNames.isEmpty())
        {
            Object cpObj = viewer.getContentProvider();
            if (cpObj instanceof ITreeContentProvider cp)
            {
                ViewerFilter[] filters = viewer.getFilters();
                Object input = viewer.getInput();
                Object[] roots = cp.getElements(input);
                if (roots != null)
                {
                    for (Object root : roots)
                        collectOwnFiles(viewer, cp, filters, input, root, droppedNames, matches);
                }
            }
        }
        Object[] toSelect = matches.toArray();
        applyOwnFileSelection(viewer, tree, interaction, toSelect);
        tree.setFocus();
        Display display = tree.getDisplay();
        Runnable later = () -> applyOwnFileSelection(viewer, tree, interaction, toSelect);
        display.asyncExec(later);
        display.timerExec(1, later);
        Debug.log("selectOwnFiles names=" + droppedNames + " matches=" + matches.size()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static GitStagingTreeInteraction interactionOf(Tree tree)
    {
        Object data = tree.getData(INTERACTION_KEY);
        return data instanceof GitStagingTreeInteraction gi ? gi : null;
    }

    private static void applyOwnFileSelection(TreeViewer viewer, Tree tree,
        GitStagingTreeInteraction interaction, Object[] elements)
    {
        if (tree == null || tree.isDisposed())
            return;
        if (interaction != null)
            interaction.replaceSelection(elements);
        else
        {
            tree.deselectAll();
            viewer.setSelection(elements == null || elements.length == 0
                ? StructuredSelection.EMPTY : new StructuredSelection(elements), false);
        }
        FormTableInteraction.revealSelection(tree);
    }

    private static void collectOwnFiles(TreeViewer viewer, ITreeContentProvider cp,
        ViewerFilter[] filters, Object parent, Object node, List<String> droppedNames,
        List<Object> out)
    {
        if (node == null)
            return;
        if (filters != null)
        {
            for (ViewerFilter filter : filters)
            {
                if (!filter.select(viewer, parent, node))
                    return;
            }
        }
        String path = pathOf(node);
        if (!path.isEmpty())
        {
            if (belongsToDroppedWithoutNested(fullNameOf(node), droppedNames))
                out.add(node);
            return;
        }
        Object[] children = cp.getChildren(node);
        if (children == null)
            return;
        for (Object child : children)
            collectOwnFiles(viewer, cp, filters, node, child, droppedNames, out);
    }

    /**
     * Файл принадлежит сброшенному объекту, но не вложенному (форма, макет, команда…).
     * {@code Справочник.Орг} матчит {@code Справочник.Орг} и {@code Справочник.Орг.МодульОбъекта},
     * но не {@code Справочник.Орг.Форма.ФормаСписка}.
     */
    private static boolean belongsToDroppedWithoutNested(String fileFqn, List<String> droppedNames)
    {
        if (fileFqn == null || fileFqn.isEmpty())
            return false;
        for (String dropped : droppedNames)
        {
            if (dropped == null || dropped.isEmpty())
                continue;
            if (fileFqn.equals(dropped))
                return true;
            String prefix = dropped + "."; //$NON-NLS-1$
            if (!fileFqn.startsWith(prefix))
                continue;
            String rest = fileFqn.substring(prefix.length());
            int dot = rest.indexOf('.');
            String first = dot < 0 ? rest : rest.substring(0, dot);
            if (!isNestedChildType(first))
                return true;
        }
        return false;
    }

    private static boolean isNestedChildType(String segment)
    {
        if (segment == null || segment.isEmpty())
            return false;
        if (MdTypeMapping.subObjectTypeToEmfFeature(segment) != null)
            return true;
        String ru = MdTypeMapping.anyToRu(segment);
        if (ru == null)
            ru = segment;
        if (MdTypeMapping.isKnownMdRootType(ru))
            return false;
        return MdTypeMapping.ruSingularToGroupPlural(ru) != null;
    }

    private static final class NavigatorSelectDropListener extends DropTargetAdapter
    {
        private final TreeViewer viewer;
        private final DropTargetListener[] originals;
        private boolean acceptNavigator;

        NavigatorSelectDropListener(TreeViewer viewer, DropTargetListener[] originals)
        {
            this.viewer = viewer;
            this.originals = originals;
        }

        @Override
        public void dragEnter(DropTargetEvent event)
        {
            if (updateNavigator(event))
                return;
            for (DropTargetListener listener : originals)
                listener.dragEnter(event);
        }

        @Override
        public void dragOperationChanged(DropTargetEvent event)
        {
            if (updateNavigator(event))
                return;
            for (DropTargetListener listener : originals)
                listener.dragOperationChanged(event);
        }

        @Override
        public void dragOver(DropTargetEvent event)
        {
            if (updateNavigator(event))
                return;
            for (DropTargetListener listener : originals)
                listener.dragOver(event);
        }

        @Override
        public void dropAccept(DropTargetEvent event)
        {
            if (updateNavigator(event))
                return;
            for (DropTargetListener listener : originals)
                listener.dropAccept(event);
        }

        @Override
        public void dragLeave(DropTargetEvent event)
        {
            boolean wasNavigator = acceptNavigator;
            acceptNavigator = false;
            if (wasNavigator)
                return;
            for (DropTargetListener listener : originals)
                listener.dragLeave(event);
        }

        @Override
        public void drop(DropTargetEvent event)
        {
            if (acceptNavigator)
            {
                List<String> names = draggedObjectFullNames();
                selectOwnFiles(viewer, names);
                acceptNavigator = false;
                return;
            }
            for (DropTargetListener listener : originals)
                listener.drop(event);
        }

        private boolean updateNavigator(DropTargetEvent event)
        {
            List<String> names = draggedObjectFullNames();
            if (names.isEmpty())
            {
                acceptNavigator = false;
                return false;
            }
            preferLocalSelectionDataType(event);
            if ((event.operations & DND.DROP_MOVE) != 0)
                event.detail = DND.DROP_MOVE;
            else if ((event.operations & DND.DROP_COPY) != 0)
                event.detail = DND.DROP_COPY;
            else
                event.detail = DND.DROP_NONE;
            event.feedback = DND.FEEDBACK_NONE;
            acceptNavigator = event.detail != DND.DROP_NONE;
            return true;
        }
    }

    /**
     * Ширина, под которую авто-подгонка ({@link ColumnAutoFit}) растягивает колонки в обоих списках —
     * МИНИМУМ клиентских областей staged и unstaged, то есть с запасом на вертикальную полосу прокрутки,
     * если она есть хотя бы в одном из них.
     *
     * <p>Ширины колонок здесь общие: любая {@code setWidth} переносится на соседний список
     * ({@link #syncWidthToPeer}). Клиентские области при этом разные — в списке с вертикальной полосой она
     * уже на её ширину. Если подгонять каждый список под свою ширину, синхронизация тут же приносит соседу
     * ширины, в его клиентскую область не влезающие, и в нём навсегда остаётся горизонтальная полоса
     * (issue #273). Общий бюджет по минимуму снимает это: одинаковые ширины умещаются в обоих.
     */
    private static int stagingWidthBudget(IViewPart view, Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return 0;
        int budget = tree.getClientArea().width;
        Tree peer = otherTree(view, tree);
        if (peer != null && !peer.isDisposed())
        {
            int peerWidth = peer.getClientArea().width;
            // Ноль — сосед ещё не разложен (или скрыт): по нему не ограничиваем.
            if (peerWidth > 0 && peerWidth < budget)
                budget = peerWidth;
        }
        return budget;
    }

    private static GitStagingSearchFilter findExistingFilter(TreeViewer viewer)
    {
        for (ViewerFilter f : viewer.getFilters())
            if (f instanceof GitStagingSearchFilter gsf)
                return gsf;
        return null;
    }

    private static GitStagingSearchFilter attachFilter(TreeViewer viewer)
    {
        GitStagingSearchFilter filter = new GitStagingSearchFilter();
        filter.captureInitialExpandedElements(viewer);
        viewer.addFilter(filter);
        return filter;
    }

    // -----------------------------------------------------------------------
    // Фильтрация: дебаунс ввода + фоновый Job на обход дерева
    // -----------------------------------------------------------------------

    /**
     * Состояние фильтрации одного экземпляра {@code DtStagingView}: счётчик поколений
     * ({@code activeGeneration}) и ссылка на текущий фоновый {@link Job}, по образцу
     * {@code CompareConfigSearchDialogHook} (поиск по дереву сравнения — тоже тысячи узлов).
     *
     * <p>Пустой текст обрабатывается сразу и синхронно (дёшево — {@code select()} всегда
     * {@code true}). Непустой текст — через {@link #DEBOUNCE_MS} дебаунс ({@code Display.timerExec}
     * с одним и тем же {@link Runnable}, что и переиспользует штатное поведение SWT — повторный
     * вызов до срабатывания таймера переносит его), затем обход дерева и матчинг — в {@link Job},
     * применение результата — через {@code asyncExec} с проверкой поколения.
     */
    private static final class FilterSession
    {
        private final IViewPart view;
        private final Text filterText;
        private final GitStagingSearchFilter stagedFilter;
        private final GitStagingSearchFilter unstagedFilter;
        private final Runnable debounceRunnable = this::fireDebounced;

        private volatile int activeGeneration;
        private volatile Job activeJob;

        FilterSession(IViewPart view, Text filterText, GitStagingSearchFilter stagedFilter,
            GitStagingSearchFilter unstagedFilter)
        {
            this.view = view;
            this.filterText = filterText;
            this.stagedFilter = stagedFilter;
            this.unstagedFilter = unstagedFilter;
        }

        void onModify()
        {
            if (filterText.isDisposed())
                return;
            String text = filterText.getText();
            Debug.log("onModify text=\"" + text + "\""); //$NON-NLS-1$ //$NON-NLS-2$
            if (text.isEmpty())
            {
                activeGeneration++;
                cancelActiveJob();
                applyEmptyFilter();
                return;
            }
            filterText.getDisplay().timerExec(DEBOUNCE_MS, debounceRunnable);
        }

        private void fireDebounced()
        {
            if (filterText.isDisposed())
                return;
            String text = filterText.getText();
            if (text.isEmpty())
                return;

            int generation = ++activeGeneration;
            Debug.log("fireDebounced generation=" + generation + " text=\"" + text + "\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            cancelActiveJob();
            startBackgroundJob(generation, text);
        }

        private void cancelActiveJob()
        {
            Job job = activeJob;
            activeJob = null;
            if (job != null)
                job.cancel();
        }

        private void applyEmptyFilter()
        {
            clearViewer(stagedFilter, "stagedViewer"); //$NON-NLS-1$
            clearViewer(unstagedFilter, "unstagedViewer"); //$NON-NLS-1$
            Debug.log("applyEmptyFilter"); //$NON-NLS-1$
        }

        private void clearViewer(GitStagingSearchFilter filter, String viewerField)
        {
            TreeViewer viewer = getViewer(viewerField);
            if (viewer == null)
                return;
            filter.setPattern(""); //$NON-NLS-1$
            IBaseLabelProvider lp = viewer.getLabelProvider();
            if (lp instanceof GitStagingLabelProvider provider)
                provider.setHighlightPattern(""); //$NON-NLS-1$
            viewer.getTree().setRedraw(false);
            try
            {
                viewer.refresh();
                filter.restoreInitialExpandedElements(viewer);
            }
            finally
            {
                viewer.getTree().setRedraw(true);
            }
        }

        private void startBackgroundJob(int generation, String text)
        {
            TreeViewer stagedViewer = getViewer("stagedViewer"); //$NON-NLS-1$
            TreeViewer unstagedViewer = getViewer("unstagedViewer"); //$NON-NLS-1$
            ITreeContentProvider stagedCp = contentProviderOf(stagedViewer);
            Object stagedInput = stagedViewer != null ? stagedViewer.getInput() : null;
            ITreeContentProvider unstagedCp = contentProviderOf(unstagedViewer);
            Object unstagedInput = unstagedViewer != null ? unstagedViewer.getInput() : null;

            Job job = new Job("Фильтрация списка изменений...") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    long tStart = System.currentTimeMillis();
                    Debug.log("job START generation=" + generation); //$NON-NLS-1$
                    try
                    {
                        SmartMatcher matcher = new SmartMatcher(text);
                        int[] visited = {0};

                        Map<Object, Boolean> stagedResults = new IdentityHashMap<>();
                        List<Object> stagedLeaves = new ArrayList<>();
                        if (stagedCp != null)
                            for (Object root : stagedCp.getElements(stagedInput))
                                computeMatches(root, stagedCp, matcher, stagedResults, stagedLeaves,
                                    visited, generation, () -> activeGeneration, monitor);

                        Map<Object, Boolean> unstagedResults = new IdentityHashMap<>();
                        List<Object> unstagedLeaves = new ArrayList<>();
                        if (unstagedCp != null)
                            for (Object root : unstagedCp.getElements(unstagedInput))
                                computeMatches(root, unstagedCp, matcher, unstagedResults, unstagedLeaves,
                                    visited, generation, () -> activeGeneration, monitor);

                        long tComputed = System.currentTimeMillis();
                        Debug.log("job COMPUTED generation=" + generation + " visited=" + visited[0] //$NON-NLS-1$ //$NON-NLS-2$
                            + " stagedLeaves=" + stagedLeaves.size() + " unstagedLeaves=" + unstagedLeaves.size() //$NON-NLS-1$ //$NON-NLS-2$
                            + " computeMs=" + (tComputed - tStart)); //$NON-NLS-1$

                        Display.getDefault().asyncExec(() ->
                        {
                            if (generation != activeGeneration || filterText.isDisposed())
                            {
                                Debug.log("asyncExec SKIP (stale) generation=" + generation //$NON-NLS-1$
                                    + " activeGeneration=" + activeGeneration); //$NON-NLS-1$
                                return;
                            }
                            long tUiStart = System.currentTimeMillis();
                            Debug.log("asyncExec START generation=" + generation //$NON-NLS-1$
                                + " uiWaitMs=" + (tUiStart - tComputed)); //$NON-NLS-1$
                            applyPrecomputed(stagedViewer, stagedFilter, "stagedViewer", text, stagedResults, //$NON-NLS-1$
                                stagedLeaves);
                            applyPrecomputed(unstagedViewer, unstagedFilter, "unstagedViewer", text, //$NON-NLS-1$
                                unstagedResults, unstagedLeaves);
                            Debug.log("asyncExec DONE generation=" + generation //$NON-NLS-1$
                                + " totalUiMs=" + (System.currentTimeMillis() - tUiStart)); //$NON-NLS-1$
                        });

                        return Status.OK_STATUS;
                    }
                    catch (FilterCancelledException cancelled)
                    {
                        Debug.log("job CANCELLED generation=" + generation //$NON-NLS-1$
                            + " afterMs=" + (System.currentTimeMillis() - tStart)); //$NON-NLS-1$
                        return Status.CANCEL_STATUS;
                    }
                }
            };
            activeJob = job;
            job.setSystem(true);
            job.schedule();
        }

        private void applyPrecomputed(TreeViewer viewer, GitStagingSearchFilter filter, String viewerField,
            String text, Map<Object, Boolean> results, List<Object> matchedLeaves)
        {
            if (viewer == null || viewer.getControl().isDisposed())
            {
                Debug.log("applyPrecomputed " + viewerField + ": viewer unavailable"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            filter.installPrecomputed(text, results);
            IBaseLabelProvider lp = viewer.getLabelProvider();
            if (lp instanceof GitStagingLabelProvider provider)
                provider.setHighlightPattern(text);
            viewer.getTree().setRedraw(false);
            try
            {
                long t0 = System.currentTimeMillis();
                viewer.refresh();
                long tRefresh = System.currentTimeMillis();
                viewer.setExpandedElements(matchedLeaves.toArray());
                long tExpand = System.currentTimeMillis();
                Debug.log("applyPrecomputed " + viewerField + ": items=" + results.size() //$NON-NLS-1$ //$NON-NLS-2$
                    + " leaves=" + matchedLeaves.size() + " refreshMs=" + (tRefresh - t0) //$NON-NLS-1$ //$NON-NLS-2$
                    + " expandMs=" + (tExpand - tRefresh)); //$NON-NLS-1$
            }
            finally
            {
                viewer.getTree().setRedraw(true);
            }
            selectFirstMatchIfSelectionLost(viewer, matchedLeaves);
        }

        /** Если прежняя строка скрыта фильтром — выделяем первое совпадение (иначе первую видимую). */
        private static void selectFirstMatchIfSelectionLost(TreeViewer viewer, List<Object> matchedLeaves)
        {
            Tree tree = viewer.getTree();
            if (tree == null || tree.isDisposed())
                return;
            if (tree.getSelectionCount() > 0)
                return;
            if (matchedLeaves != null)
            {
                for (Object leaf : matchedLeaves)
                {
                    if (leaf == null)
                        continue;
                    viewer.setSelection(new StructuredSelection(leaf), true);
                    if (tree.getSelectionCount() > 0)
                        return;
                }
            }
            FilterInputBoxListNavigation.selectFirstRowIfSelectionLost(tree);
        }

        private TreeViewer getViewer(String fieldName)
        {
            Object obj = Global.getField(view, fieldName);
            return (obj instanceof TreeViewer v && !v.getControl().isDisposed()) ? v : null;
        }

        private static ITreeContentProvider contentProviderOf(TreeViewer viewer)
        {
            if (viewer == null)
                return null;
            Object cp = viewer.getContentProvider();
            return cp instanceof ITreeContentProvider tcp ? tcp : null;
        }
    }

    /**
     * Обход дерева на фоновом потоке: {@code element.getPath()} + {@link GetRef#resolveFullNameOrNull}
     * не трогают SWT-виджеты (обычные Java-объекты модели EGit), поэтому это безопасно вне UI-потока.
     * Каждые ~512 узлов проверяется отмена — устаревшее поколение (пользователь напечатал ещё)
     * прерывает обход немедленно через {@link FilterCancelledException}, не тратя время впустую.
     */
    private static void computeMatches(Object node, ITreeContentProvider cp, SmartMatcher matcher,
        Map<Object, Boolean> results, List<Object> matchedLeaves, int[] visited, int generation,
        IntSupplier currentGeneration, IProgressMonitor monitor)
    {
        if ((++visited[0] & 0x1FF) == 0 && (monitor.isCanceled() || generation != currentGeneration.getAsInt()))
            throw CANCELLED;

        String text = matchText(node);
        if (!text.isEmpty())
        {
            boolean matches = matcher.matches(text);
            results.put(node, matches);
            if (matches)
                matchedLeaves.add(node);
            return;
        }

        boolean any = false;
        for (Object child : cp.getChildren(node))
        {
            computeMatches(child, cp, matcher, results, matchedLeaves, visited, generation, currentGeneration,
                monitor);
            Boolean childValue = results.get(child);
            if (childValue != null && childValue)
                any = true;
        }
        results.put(node, any);
    }

    /** Без сообщения/стека — бросается часто и только для быстрого выхода из обхода, не как ошибка. */
    private static final class FilterCancelledException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        FilterCancelledException()
        {
            super(null, null, false, false);
        }
    }

    private static final FilterCancelledException CANCELLED = new FilterCancelledException();

    /** «ПолноеИмя ИмяФайла» для листа ({@code getPath()} есть); "" для остальных узлов (папки и т.п.). */
    private static String matchText(Object element)
    {
        Object pathObj = Global.invoke(element, "getPath"); //$NON-NLS-1$
        if (!(pathObj instanceof String path) || path.isEmpty())
            return ""; //$NON-NLS-1$
        String fileName = fileNameOf(path);
        String fullName = GetRef.resolveFullNameOrNull(path);
        return fullName != null && !fullName.isEmpty() ? fullName + " " + fileName : fileName; //$NON-NLS-1$
    }

    private static String fileNameOf(String path)
    {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    // -----------------------------------------------------------------------
    // Данные для колонок «Тип»/«Путь»/«Время изменения»/«Статус»
    // -----------------------------------------------------------------------

    private static String pathOf(Object element)
    {
        Object pathObj = Global.invoke(element, "getPath"); //$NON-NLS-1$
        return pathObj instanceof String s ? s : ""; //$NON-NLS-1$
    }

    private static String fullNameOf(Object element)
    {
        String path = pathOf(element);
        if (path.isEmpty())
            return ""; //$NON-NLS-1$
        String fullName = GetRef.resolveFullNameOrNull(path);
        return fullName != null ? fullName : ""; //$NON-NLS-1$
    }

    /** Текст колонки «Имя» для сортировки: {@code StagingFolderEntry.getLabel()} / {@code StagingEntry.getName()}. */
    private static String nameText(Object element)
    {
        Object label = Global.invoke(element, "getLabel"); //$NON-NLS-1$
        if (label instanceof String s)
            return s;
        Object name = Global.invoke(element, "getName"); //$NON-NLS-1$
        return name instanceof String s2 ? s2 : ""; //$NON-NLS-1$
    }

    private static String sortKey(Object element, int logical)
    {
        return switch (logical)
        {
            case COL_TYPE -> extensionOf(pathOf(element));
            case COL_PATH -> fullNameOf(element);
            case COL_TIME -> timeText(element);
            case COL_STATUS -> statusText(element);
            default -> nameText(element);
        };
    }

    /** Расширение файла (без точки); пусто, если точки нет или путь пуст (папка). */
    private static String extensionOf(String path)
    {
        if (path == null || path.isEmpty())
            return ""; //$NON-NLS-1$
        String name = fileNameOf(path);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : ""; //$NON-NLS-1$
    }

    /** Формат колонки «Время изменения» — как в панели «Приложения» ({@code ApplicationsViewHook.DATE_FMT}). */
    private static final DateTimeFormatter TIME_COLUMN_FMT = DateTimeFormatter.ofPattern("dd'д'HH:mm:ss"); //$NON-NLS-1$

    /**
     * Timestamp изменения рабочего файла на диске (мс, epoch); {@code 0} — файл недоступен
     * (папка, отсутствующий/удалённый файл, субмодуль). Для сортировки по дате — {@link #timeText}
     * форматирует ТОЛЬКО для отображения и для сортировки не годится (без года/месяца).
     */
    private static long timeMillis(Object element)
    {
        Object fileObj = Global.invoke(element, "getFile"); //$NON-NLS-1$
        long ts = fileObj instanceof IFile file ? file.getLocalTimeStamp() : 0L;
        if (ts <= 0)
        {
            Object locObj = Global.invoke(element, "getLocation"); //$NON-NLS-1$
            if (locObj instanceof IPath p)
                ts = new File(p.toOSString()).lastModified();
        }
        return Math.max(ts, 0L);
    }

    /** Время изменения рабочего файла на диске ({@code ддДчч:мм:сс}); пусто — файл недоступен. */
    private static String timeText(Object element)
    {
        long ts = timeMillis(element);
        if (ts <= 0)
            return ""; //$NON-NLS-1$
        return TIME_COLUMN_FMT.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()));
    }

    /** Текст статуса по {@code StagingEntry.State} (получен рефлексией — без compile-зависимости от egit-ui). */
    private static String statusText(Object element)
    {
        Object stateObj = Global.invoke(element, "getState"); //$NON-NLS-1$
        if (stateObj == null)
            return ""; //$NON-NLS-1$
        String name = stateObj.toString();
        return switch (name)
        {
            case "ADDED", "UNTRACKED" -> "Добавлен"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "MODIFIED", "CHANGED", "MODIFIED_AND_CHANGED" -> "Изменён"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "REMOVED", "MISSING", "MISSING_AND_CHANGED" -> "Удалён"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "MODIFIED_AND_ADDED" -> "Добавлен, изменён"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CONFLICTING" -> "Конфликт"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> ""; //$NON-NLS-1$
        };
    }

    /**
     * Плоский AND по фрагментам {@link SmartMatcher} для листьев ({@link #matchText}, без
     * dot-иерархии — см. javadoc класса, почему не {@link SmartOutlineFilter}); для узлов без
     * своего пути (папки) — рекурсивная проверка «есть ли совпадение среди потомков».
     *
     * <p>{@link #precomputedMatches} — результат фонового обхода {@link FilterSession}: карта
     * «узел (по идентичности) → матчится ли он (лист) / есть ли совпадение в поддереве (папка)».
     * {@code select()} сначала смотрит туда (O(1)); если узла там нет (появился уже после
     * фонового прохода), считает как раньше — инлайн, с локальным memo {@link #subtreeMemo} на
     * время одного {@code refresh()}.
     */
    private static final class GitStagingSearchFilter extends ViewerFilter
    {
        private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$
        private final Map<Object, Boolean> subtreeMemo = new IdentityHashMap<>();
        private Object[] initialExpandedElements = new Object[0];
        private volatile Map<Object, Boolean> precomputedMatches;

        void setPattern(String pattern)
        {
            matcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
            subtreeMemo.clear();
            precomputedMatches = null;
        }

        void installPrecomputed(String pattern, Map<Object, Boolean> results)
        {
            matcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
            subtreeMemo.clear();
            precomputedMatches = results;
        }

        boolean isFiltering()
        {
            return !matcher.isEmpty;
        }

        void captureInitialExpandedElements(TreeViewer viewer)
        {
            Object[] current = viewer.getExpandedElements();
            initialExpandedElements = current != null ? current : new Object[0];
        }

        void restoreInitialExpandedElements(TreeViewer viewer)
        {
            viewer.setExpandedElements(initialExpandedElements);
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            if (matcher.isEmpty)
                return true;

            Map<Object, Boolean> precomputed = precomputedMatches;
            if (precomputed != null)
            {
                Boolean cached = precomputed.get(element);
                if (cached != null)
                    return cached.booleanValue();
            }

            String text = matchText(element);
            if (!text.isEmpty())
                return matcher.matches(text);

            // Узел без своего пути (папка и т.п.) — виден, только если внутри есть совпадение.
            if (!(viewer instanceof TreeViewer treeViewer))
                return true;
            Object cp = treeViewer.getContentProvider();
            if (!(cp instanceof ITreeContentProvider tcp))
                return true;
            return hasMatchInSubtree(tcp, element);
        }

        private boolean hasMatchInSubtree(ITreeContentProvider tcp, Object element)
        {
            Boolean memo = subtreeMemo.get(element);
            if (memo != null)
                return memo.booleanValue();

            boolean result = false;
            for (Object child : tcp.getChildren(element))
            {
                String childText = matchText(child);
                boolean childMatches = !childText.isEmpty()
                    ? matcher.matches(childText) : hasMatchInSubtree(tcp, child);
                if (childMatches)
                {
                    result = true;
                    break;
                }
            }
            subtreeMemo.put(element, result);
            return result;
        }
    }

    // -----------------------------------------------------------------------
    // Подсветка + дописывание полного имени в текст строки
    // -----------------------------------------------------------------------

    /**
     * Multi-column label provider дерева «Индексирование Git»: колонка «Имя» — оригинальный текст/иконка
     * штатного {@code StagingViewLabelProvider} ({@code base}), «Тип»/«Путь»/«Время изменения»/«Статус» —
     * вычисляются здесь (см. {@link #extensionOf}/{@link #fullNameOf}/{@link #timeText}/{@link #statusText}).
     * Логическая колонка ячейки резолвится через {@link #logicalOfColumn} — не по физическому индексу
     * (тот меняется при drag-реордере/скрытии колонок).
     */
    private static final class GitStagingLabelProvider extends StyledCellLabelProvider
        implements ILabelProvider
    {
        private final CellLabelProvider base;
        private final Tree tree;
        private SmartMatcher highlightMatcher = new SmartMatcher(""); //$NON-NLS-1$

        GitStagingLabelProvider(CellLabelProvider base, Tree tree)
        {
            this.base = base;
            this.tree = tree;
        }

        void setHighlightPattern(String pattern)
        {
            highlightMatcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
        }

        @Override
        public void update(ViewerCell cell)
        {
            if (cell == null)
                return;
            Object element = cell.getElement();
            int logical = logicalOfColumn(tree, cell.getColumnIndex());
            String text;
            switch (logical)
            {
                case COL_TYPE -> text = extensionOf(pathOf(element));
                case COL_PATH -> text = fullNameOf(element);
                case COL_TIME -> text = timeText(element);
                case COL_STATUS -> text = statusText(element);
                default -> text = getText(element);
            }
            cell.setText(text != null ? text : ""); //$NON-NLS-1$
            cell.setImage(logical == COL_NAME ? getImage(element) : null);
            copyRowStyle(cell, element);

            // Вызываем всегда, а не только при непустом фильтре — иначе при очистке поля старые
            // StyleRange (SWT переиспользует TreeItem между refresh-ами) остаются висеть.
            // appendMatchRanges сам корректно очищает ячейку при пустом списке диапазонов.
            // В «Время изменения» / «Статус» подсветку не делаем: там дата и фиксированные метки.
            boolean highlightColumn = logical != COL_TIME && logical != COL_STATUS;
            List<SmartMatcher.HighlightRange> ranges = highlightColumn
                && !highlightMatcher.isEmpty && text != null && !text.isEmpty()
                && highlightMatcher.matches(matchText(element))
                    ? highlightMatcher.getHighlightRanges(text) : List.of();
            SmartMatchHighlight.appendMatchRanges(cell, ranges);
        }

        /** Копирует foreground/background/font штатного провайдера (dim/конфликт и т.п.) на всю строку. */
        private void copyRowStyle(ViewerCell cell, Object element)
        {
            if (base instanceof ColumnLabelProvider clp)
            {
                cell.setForeground(clp.getForeground(element));
                cell.setBackground(clp.getBackground(element));
                cell.setFont(clp.getFont(element));
                return;
            }
            Object fg = Global.invoke(base, "getForeground", element); //$NON-NLS-1$
            cell.setForeground(fg instanceof Color c ? c : null);
            Object bg = Global.invoke(base, "getBackground", element); //$NON-NLS-1$
            cell.setBackground(bg instanceof Color c ? c : null);
        }

        @Override
        public String getText(Object element)
        {
            String baseText;
            if (base instanceof ILabelProvider bl)
                baseText = bl.getText(element);
            else
            {
                Object text = Global.invoke(base, "getText", element); //$NON-NLS-1$
                baseText = text instanceof String s ? s : ""; //$NON-NLS-1$
            }
            return baseText != null ? baseText : ""; //$NON-NLS-1$
        }

        @Override
        public Image getImage(Object element)
        {
            if (base instanceof ILabelProvider bl)
                return bl.getImage(element);
            Object img = Global.invoke(base, "getImage", element); //$NON-NLS-1$
            return img instanceof Image ? (Image) img : null;
        }

        @Override
        public void addListener(ILabelProviderListener listener)
        {
            base.addListener(listener);
        }

        @Override
        public void removeListener(ILabelProviderListener listener)
        {
            base.removeListener(listener);
        }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            return base.isLabelProperty(element, property);
        }

        @Override
        public void dispose()
        {
            base.dispose();
        }
    }

    // -----------------------------------------------------------------------
    // Tree-взаимодействие: выбор ячейки, подсветка активной ячейки/строки,
    // копирование Ctrl+C через CopyCommandSupport (см. javadoc класса).
    // -----------------------------------------------------------------------

    private static final class GitStagingTreeInteraction
    {
        private final Tree tree;
        private final TreeViewer viewer;
        private TreeItem selectedItem;
        private int activeColumn;
        private Color ownedRowBg;
        private Color ownedInactiveRowBg;
        private Color ownedActiveCellBg;
        private int sortLogical = -1;
        private boolean sortAscending = true;

        GitStagingTreeInteraction(Tree tree, TreeViewer viewer)
        {
            this.tree = tree;
            this.viewer = viewer;
        }

        void install()
        {
            tree.setData(INTERACTION_KEY, this);
            ListSelectionThemeColors.markOptOut(tree);

            tree.addListener(SWT.MouseDown, this::onMouseDown);

            tree.addListener(SWT.EraseItem, this::onEraseItem);
            tree.addListener(SWT.PaintItem, this::onPaintItem);
            tree.addListener(SWT.FocusIn, e -> { invalidateColors(); tree.redraw(); });
            tree.addListener(SWT.FocusOut, e -> { invalidateColors(); tree.redraw(); });
            tree.addListener(SWT.Selection, e -> { syncFromSelection(); invalidateColors(); tree.redraw(); });

            tree.addDisposeListener(e -> invalidateColors());
            // Win32: Ctrl+C не доходит до KeyDown (акселератор Edit→Copy) — только через команду.
            CopyCommandSupport.wireCopyOverride(tree, this::copyActiveCellToClipboard);
        }

        // ---- сортировка по клику на заголовке колонки ----

        /** Клик по заголовку: переключить/сменить сортировку, сохранив текущее выделение. */
        void sortBy(int logical, TreeColumn column)
        {
            sortAscending = sortLogical == logical ? !sortAscending : true;
            sortLogical = logical;

            Object[] selection = captureSelection();
            int sortLogicalFinal = logical;
            boolean ascendingFinal = sortAscending;
            viewer.setComparator(new ViewerComparator()
            {
                @Override
                public int compare(Viewer v, Object e1, Object e2)
                {
                    // «Время» — по timestamp (с учётом даты), не по отформатированной строке (без года/месяца).
                    int cmp = sortLogicalFinal == COL_TIME
                        ? Long.compare(timeMillis(e1), timeMillis(e2))
                        : String.CASE_INSENSITIVE_ORDER.compare(
                            sortKey(e1, sortLogicalFinal), sortKey(e2, sortLogicalFinal));
                    return ascendingFinal ? cmp : -cmp;
                }
            });
            tree.setSortColumn(column);
            tree.setSortDirection(sortAscending ? SWT.UP : SWT.DOWN);
            restoreSelection(selection);
        }

        private Object[] captureSelection()
        {
            ISelection sel = viewer.getSelection();
            return sel instanceof IStructuredSelection ss ? ss.toArray() : new Object[0];
        }

        /** Снять нативное выделение и активную строку подсветки. */
        void clearSelection()
        {
            selectedItem = null;
            tree.deselectAll();
            viewer.setSelection(StructuredSelection.EMPTY, false);
            invalidateColors();
            tree.redraw();
        }

        /**
         * Сначала очищает выделение, затем ставит указанные элементы.
         * {@code tree.select} не используем — он добавляет к текущему MULTI.
         */
        void replaceSelection(Object[] elements)
        {
            selectedItem = null;
            tree.deselectAll();
            viewer.setSelection(elements == null || elements.length == 0
                ? StructuredSelection.EMPTY : new StructuredSelection(elements), false);
            TreeItem[] sel = tree.getSelection();
            if (sel.length > 0)
                selectedItem = sel[0];
            if (activeColumn < 0 || activeColumn >= tree.getColumnCount())
                activeColumn = 0;
            invalidateColors();
            tree.redraw();
        }

        private void restoreSelection(Object[] elements)
        {
            if (elements.length == 0)
                return;
            viewer.setSelection(new StructuredSelection(elements), true);
        }

        private void onMouseDown(Event e)
        {
            if (e.button != 1)
                return;
            TreeItem item = itemAt(tree, e.x, e.y);
            if (item == null)
                return;
            int column = columnAt(tree, e.x, e.y, item);
            if (column < 0)
                column = 0;
            // Только запоминаем активную ячейку. tree.setSelection(item) сбрасывает
            // штатное SWT.MULTI (Ctrl/Shift и клик по уже выделенной строке для drag).
            selectedItem = item;
            activeColumn = column;
            invalidateColors();
            tree.redraw();
        }

        private void syncFromSelection()
        {
            if (selectedItem != null && !selectedItem.isDisposed() && isRowSelected(selectedItem))
            {
                if (activeColumn < 0 || activeColumn >= tree.getColumnCount())
                    activeColumn = 0;
                return;
            }
            TreeItem[] sel = tree.getSelection();
            if (sel.length > 0)
                selectedItem = sel[0];
            if (activeColumn < 0 || activeColumn >= tree.getColumnCount())
                activeColumn = 0;
        }

        private boolean isRowSelected(TreeItem item)
        {
            if (item == null || item.isDisposed())
                return false;
            for (TreeItem s : tree.getSelection())
            {
                if (s == item)
                    return true;
            }
            return false;
        }

        private TreeItem currentSelectedRow()
        {
            if (selectedItem != null && !selectedItem.isDisposed() && isRowSelected(selectedItem))
                return selectedItem;
            TreeItem[] sel = tree.getSelection();
            if (sel.length > 0)
                return sel[0];
            return selectedItem;
        }

        /** Элемент модели активной строки — для {@link TreeColumnValueFilterSupport}. */
        Object activeElement()
        {
            TreeItem row = currentSelectedRow();
            return row != null && !row.isDisposed() ? row.getData() : null;
        }

        /** Индекс активной колонки (0, если ещё не выбрана) — для {@link TreeColumnValueFilterSupport}. */
        int activeColumnIndex()
        {
            int column = activeColumn;
            if (column < 0 || column >= tree.getColumnCount())
                column = 0;
            return column;
        }

        void copyActiveCellToClipboard()
        {
            int column = activeColumnIndex();
            TreeItem[] sel = tree.getSelection();
            if (sel.length == 0)
            {
                if (selectedItem == null || selectedItem.isDisposed())
                    return;
                sel = new TreeItem[] { selectedItem };
            }
            List<TreeItem> rows = sel.length == 1
                ? List.of(sel[0])
                : selectedItemsInDisplayOrder(sel);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rows.size(); i++)
            {
                if (i > 0)
                    sb.append('\n');
                sb.append(cellText(rows.get(i), column));
            }
            String text = sb.toString();
            Clipboard clipboard = new Clipboard(tree.getDisplay());
            try
            {
                clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
            }
            finally
            {
                clipboard.dispose();
            }
        }

        private String cellText(TreeItem item, int column)
        {
            String text = item.getText(column);
            if (text == null || text.isEmpty())
            {
                Object element = item.getData();
                text = element != null ? sortKey(element, logicalOfColumn(tree, column)) : ""; //$NON-NLS-1$
            }
            return text != null ? text : ""; //$NON-NLS-1$
        }

        private List<TreeItem> selectedItemsInDisplayOrder(TreeItem[] sel)
        {
            Set<TreeItem> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
            for (TreeItem item : sel)
                wanted.add(item);
            List<TreeItem> ordered = new ArrayList<>(sel.length);
            collectSelectedInOrder(tree.getItems(), wanted, ordered);
            return ordered.isEmpty() ? List.of(sel) : ordered;
        }

        private static void collectSelectedInOrder(TreeItem[] items, Set<TreeItem> wanted,
            List<TreeItem> out)
        {
            for (TreeItem item : items)
            {
                if (wanted.contains(item))
                    out.add(item);
                if (item.getExpanded() && item.getItemCount() > 0)
                    collectSelectedInOrder(item.getItems(), wanted, out);
            }
        }

        // ---- подсветка активной ячейки/строки (см. DebugInspectorTreeEnhancement) ----

        private void onEraseItem(Event e)
        {
            if (!(e.item instanceof TreeItem item))
                return;
            if (!isRowSelected(item) && item != selectedItem)
                return;
            boolean activeRow = item == selectedItem;
            Color rowBg = activeRow ? rowSelectionBackground() : inactiveRowSelectionBackground();
            Color bg = activeRow && e.index == activeColumnIndex()
                ? activeCellBackground(rowBg) : rowBg;
            e.gc.setBackground(bg);
            e.gc.fillRectangle(e.x, e.y, e.width, e.height);
            e.detail &= ~SWT.BACKGROUND;
            if (ListSelectionThemeColors.isDarkList(tree))
            {
                e.detail &= ~SWT.SELECTED;
                e.detail &= ~SWT.HOT;
            }
        }

        private void onPaintItem(Event e)
        {
            if (!(e.item instanceof TreeItem item) || item != selectedItem
                || e.index != activeColumnIndex())
                return;
            Rectangle bounds = item.getBounds(e.index);
            if (bounds == null || bounds.isEmpty())
                return;
            Color rowBg = rowSelectionBackground();
            Color base = activeCellBackground(rowBg);
            Color frame = slightlyDarker(base, 0.12);
            try
            {
                e.gc.setForeground(frame);
                e.gc.drawRectangle(bounds.x, bounds.y, Math.max(0, bounds.width - 1),
                    Math.max(0, bounds.height - 1));
            }
            finally
            {
                if (!frame.isDisposed())
                    frame.dispose();
            }
        }

        private Color rowSelectionBackground()
        {
            if (ownedRowBg != null && !ownedRowBg.isDisposed())
                return ownedRowBg;
            if (ListSelectionThemeColors.isDarkList(tree))
            {
                ownedRowBg = ListSelectionThemeColors.listSelectionBackground(tree, tree.isFocusControl());
                return ownedRowBg;
            }
            Display display = tree.getDisplay();
            Color base = tree.getBackground();
            if (base == null || base.isDisposed())
                base = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND);
            double factor = tree.isFocusControl() ? 0.12 : 0.08;
            ownedRowBg = slightlyDarker(base, factor);
            return ownedRowBg;
        }

        /** Фон прочих выбранных строк при мультивыделении (слабее текущей). */
        private Color inactiveRowSelectionBackground()
        {
            if (ownedInactiveRowBg != null && !ownedInactiveRowBg.isDisposed())
                return ownedInactiveRowBg;
            if (ListSelectionThemeColors.isDarkList(tree))
            {
                ownedInactiveRowBg = ListSelectionThemeColors.inactiveRowSelectionBackground(
                    tree, tree.isFocusControl());
                return ownedInactiveRowBg;
            }
            Display display = tree.getDisplay();
            Color base = tree.getBackground();
            if (base == null || base.isDisposed())
                base = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND);
            double factor = tree.isFocusControl() ? 0.08 : 0.05;
            ownedInactiveRowBg = slightlyDarker(base, factor);
            return ownedInactiveRowBg;
        }

        private Color activeCellBackground(Color rowBg)
        {
            if (ownedActiveCellBg != null && !ownedActiveCellBg.isDisposed())
                return ownedActiveCellBg;
            if (ListSelectionThemeColors.isDarkList(tree))
            {
                ownedActiveCellBg = ListSelectionThemeColors.activeCellBackground(tree, rowBg);
                return ownedActiveCellBg;
            }
            ownedActiveCellBg = slightlyDarker(rowBg, tree.isFocusControl() ? 0.08 : 0.06);
            return ownedActiveCellBg;
        }

        private static Color slightlyDarker(Color base, double factor)
        {
            Device device = base.getDevice();
            RGB rgb = base.getRGB();
            int r = clampChannel((int) (rgb.red * (1.0 - factor)));
            int g = clampChannel((int) (rgb.green * (1.0 - factor)));
            int b = clampChannel((int) (rgb.blue * (1.0 - factor)));
            return new Color(device, r, g, b);
        }

        private static int clampChannel(int value)
        {
            return Math.max(0, Math.min(255, value));
        }

        private void invalidateColors()
        {
            if (ownedRowBg != null && !ownedRowBg.isDisposed())
                ownedRowBg.dispose();
            if (ownedInactiveRowBg != null && !ownedInactiveRowBg.isDisposed())
                ownedInactiveRowBg.dispose();
            if (ownedActiveCellBg != null && !ownedActiveCellBg.isDisposed())
                ownedActiveCellBg.dispose();
            ownedRowBg = null;
            ownedInactiveRowBg = null;
            ownedActiveCellBg = null;
        }

        // ---- hit-test (см. DebugInspectorTreeEnhancement.itemAt/columnAt) ----

        private static TreeItem itemAt(Tree tree, int x, int y)
        {
            TreeItem item = tree.getItem(new Point(x, y));
            if (item != null)
                return item;
            for (TreeItem root : tree.getItems())
            {
                TreeItem found = findInItem(root, x, y);
                if (found != null)
                    return found;
            }
            return null;
        }

        private static TreeItem findInItem(TreeItem item, int x, int y)
        {
            for (int i = 0; i < item.getParent().getColumnCount(); i++)
            {
                Rectangle bounds = item.getBounds(i);
                if (bounds != null && bounds.contains(x, y))
                    return item;
            }
            if (item.getExpanded())
                for (TreeItem child : item.getItems())
                {
                    TreeItem found = findInItem(child, x, y);
                    if (found != null)
                        return found;
                }
            return null;
        }

        private static int columnAt(Tree tree, int x, int y, TreeItem item)
        {
            for (int i = 0; i < tree.getColumnCount(); i++)
            {
                Rectangle bounds = item.getBounds(i);
                if (bounds != null && bounds.contains(x, y))
                    return i;
            }
            return 0;
        }
    }

    private static TreeViewer treeViewerOf(IViewPart view, String field)
    {
        Object obj = Global.getField(view, field);
        return obj instanceof TreeViewer tv && !tv.getControl().isDisposed() ? tv : null;
    }

    // -----------------------------------------------------------------------
    // Логи
    // -----------------------------------------------------------------------

    private static final class Debug
    {
        private static final String TAG = "GitStagingFilter"; //$NON-NLS-1$

        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
