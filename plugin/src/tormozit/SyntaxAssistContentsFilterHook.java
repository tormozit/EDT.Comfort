package tormozit;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;

/**
 * Многословный фильтр ({@link SmartMatcher}, плоское И по словам) с раскраской совпадений
 * на странице «Содержание» панели «Синтакс-помощник» ({@code SyntaxAssistView} бандла
 * {@code com._1c.g5.v8.dt.bsl.ui}).
 *
 * <p>Штатное поведение (декомпиляция {@code SyntaxAssistContentsPanel}): поле поиска — уже
 * {@link SearchBox} ({@code ContextSearchBox}), но матчинг делает
 * {@code ContentsViewerFilter} через регэксп с подстановками {@code *}/{@code ?}; история —
 * голый {@code InMemorySearchHistory} (теряется при закрытии EDT); поиск стартует от
 * 2 символов. Штатный фильтр ищет подстроку ещё и в тексте справки узла — наш фильтр,
 * как во всех остальных местах плагина, матчит по видимой подписи узла.
 *
 * <p>Что делает хук. Штатный {@code ContentsViewerFilter} НЕ трогается — он всегда висит на
 * дереве, но без {@code setSearchString} остаётся в состоянии «any» и всё пропускает; вместо
 * него добавляется свой {@link ViewerFilter}. Тяжёлый обход дерева (дерево справки большое)
 * выполняется ВНЕ UI-потока: фоновый {@link Job} идёт от корня по
 * {@code PlatformDocTreeNode.getChildren()} (чистые данные, как у штатного content provider'а)
 * с подписями через штатный label provider ({@code getText()} = {@code node.getName(lang)},
 * без SWT), строит множество видимых узлов (совпавшие + все их предки до корня); по готовности
 * множество атомарно подменяется в фильтре, и только тогда UI-поток делает refresh и раскрытие.
 * {@code select()} — только lookup в готовом множестве, O(1). Пока новый запрос считается,
 * дерево остаётся на предыдущем результате; повторный ввод отменяет текущий job (дебаунс 150 мс).
 * Смена версии/языка справки (change listener {@code ContentsStore}) переперезапускает расчёт
 * для активного текста; результат устаревшего job отбрасывается по номеру поколения и тождеству
 * input'а. Раскраска вхождений — {@link SmartOutlineLabelProvider} поверх штатного плоского
 * label provider'а в обёртке {@link SelectionAwareStyledCellLabelProvider}
 * ({@code COLORS_ON_SELECTION}, иначе цвет пропадает на выделенной строке). Раскрытие дерева —
 * как в штатном {@code search(String)}: {@code refresh()}, отмена и перезапуск штатного же
 * {@code expandManager} ({@code asyncExpandAll}/{@code asyncCollapseAll}); приватный флаг
 * {@code notChangeExpandManagerSelection} вокруг refresh не воспроизводится (влияет только на
 * запоминание выделения между асинхронными раскрытиями). {@code SearchBox} получает
 * персистентную историю ({@link FilterInputBox#attachHistory} /
 * {@link FilterInputBox.Scope#SYNTAX_CONTENTS}) и порог длины запроса 0; клавиши навигации из
 * поля пробрасываются в дерево ({@link FilterInputBoxListNavigation#installTreeNavigation}:
 * стрелки/PageUp/PageDown двигают выделение дерева, Enter открывает страницу как двойной
 * щелчок, Ctrl+↓ — история).
 */
public final class SyntaxAssistContentsFilterHook implements IStartup
{
    private static final String TAG = "SyntaxAssistContentsFilter"; //$NON-NLS-1$

    private static final String VIEW_ID = "com._1c.g5.v8.dt.bsl.ui.view.BslInfoView"; //$NON-NLS-1$

    private static final String PATCHED_KEY = "tormozit.syntaxAssistContentsFilterPatched"; //$NON-NLS-1$

    private static final int MAX_ATTEMPTS = 40;

    private static final int RETRY_MS = 100;

    /** Дебаунс набора текста перед запуском фонового обхода дерева. */
    private static final int SEARCH_DEBOUNCE_MS = 150;

    /** Как в штатном search(String): шаг порции асинхронного раскрытия/сворачивания. */
    private static final int EXPAND_STEP = 100;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)       { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)    {}
                @Override public void windowDeactivated(IWorkbenchWindow w)  {}
                @Override public void windowClosed(IWorkbenchWindow w)       {}
            });
            for (IWorkbenchWindow w : workbench.getWorkbenchWindows())
                hookWindow(w);
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;

        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null)
                continue;
            for (IViewReference ref : page.getViewReferences())
                patchFromRef(ref);
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)     { patchFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref)    { patchFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref)  { patchFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private static void patchFromRef(IWorkbenchPartReference ref)
    {
        IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
        if (!(part instanceof IViewPart viewPart) || !VIEW_ID.equals(viewPart.getViewSite().getId()))
            return;
        scheduleTryPatch(viewPart, 0);
    }

    private static void scheduleTryPatch(IViewPart view, int attempt)
    {
        if (view == null || attempt >= MAX_ATTEMPTS)
        {
            if (view != null && attempt >= MAX_ATTEMPTS)
                Global.tempLog(TAG, view.getViewSite().getId() + ": не удалось подключить после " //$NON-NLS-1$
                    + MAX_ATTEMPTS + " попыток"); //$NON-NLS-1$
            return;
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;

        Runnable task = () ->
        {
            if (tryPatch(view))
                return;
            scheduleTryPatch(view, attempt + 1);
        };
        if (attempt == 0)
            display.asyncExec(task);
        else
            display.timerExec(RETRY_MS, task);
    }

    /** @return {@code true}, если делать больше нечего (пропатчено, отключено или недоступно). */
    private static boolean tryPatch(IViewPart view)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return true;
        try
        {
            Object navigationPanel = Global.invoke(view, "getNavigationPanel"); //$NON-NLS-1$
            Object panel = navigationPanel != null
                ? Global.invoke(navigationPanel, "getContentsPanel") : null; //$NON-NLS-1$
            if (panel == null)
                return false;

            Object viewerObj = Global.getField(panel, "viewer"); //$NON-NLS-1$
            Object searchBoxObj = Global.getField(panel, "searchBox"); //$NON-NLS-1$
            Object expandManager = Global.getField(panel, "expandManager"); //$NON-NLS-1$
            if (!(viewerObj instanceof TreeViewer viewer) || !(searchBoxObj instanceof SearchBox searchBox)
                || searchBox.isDisposed() || expandManager == null)
                return false;

            Tree tree = viewer.getTree();
            if (tree == null || tree.isDisposed())
                return false;
            if (Boolean.TRUE.equals(tree.getData(PATCHED_KEY)))
                return true;

            IBaseLabelProvider rawLp = viewer.getLabelProvider();
            if (rawLp instanceof SelectionAwareStyledCellLabelProvider)
            {
                // Обёртка уже стоит (патч из предыдущего захода без пометки) — не трогаем.
                tree.setData(PATCHED_KEY, Boolean.TRUE);
                return true;
            }
            if (!(rawLp instanceof ILabelProvider plainLabels))
            {
                Global.tempLog(TAG, "label provider неожиданного типа: " //$NON-NLS-1$
                    + (rawLp != null ? rawLp.getClass().getName() : "null")); //$NON-NLS-1$
                return true;
            }

            SmartOutlineLabelProvider smartLp = new SmartOutlineLabelProvider(null, plainLabels);
            viewer.setLabelProvider(new SelectionAwareStyledCellLabelProvider(smartLp));

            ContentsFilter filter = new ContentsFilter();
            filter.setMatchLabels(plainLabels);
            viewer.addFilter(filter);

            // Состояние поиска одного пропатченного дерева: поколение гасит гонки
            // «набор текста → фоновый расчёт», holder хранит текущий job для отмены.
            final int[] applyGeneration = { 0 };
            AtomicReference<Job> runningJob = new AtomicReference<>();

            // Смена версии/языка справки пересобирает дерево — пересчитать фильтр
            // по активному тексту, иначе старое множество не содержит новые узлы.
            Object store = Global.getField(panel, "contentsStore"); //$NON-NLS-1$
            Runnable storeReapply = () ->
            {
                if (searchBox.isDisposed())
                    return;
                String text = searchBox.getText();
                if (text != null && !text.trim().isEmpty())
                    scheduleApply(searchBox, viewer, filter, smartLp, expandManager,
                        applyGeneration, runningJob);
            };
            Global.invokeVoid(store, "addChangeListener", storeReapply); //$NON-NLS-1$

            tree.addDisposeListener(e ->
            {
                Job current = runningJob.getAndSet(null);
                if (current != null)
                    current.cancel();
                Global.invokeVoid(store, "removeChangeListener", storeReapply); //$NON-NLS-1$
            });

            searchBox.setToolTipText(TooltipText.wrap(searchBox,
                FilterInputBox.SYNTAX_CONTENTS_FILTER_TOOLTIP + "\nCtrl+↓ — история запросов.")); //$NON-NLS-1$
            searchBox.setMinimumSearchTextLength(0);
            searchBox.setJobScheduleDelay(0);
            FilterInputBox.attachHistory(searchBox, FilterInputBox.Scope.SYNTAX_CONTENTS);
            searchBox.setSearchListener(new SearchBox.ISearchListener()
            {
                @Override
                public void performSearch(String text, IProgressMonitor monitor)
                {
                    // История / Enter. Живой ввод — SWT.Modify: Job SearchBox при
                    // restart=true глотает последний символ (см. ModuleMergeStructureFilterHook).
                    scheduleApply(searchBox, viewer, filter, smartLp, expandManager,
                        applyGeneration, runningJob);
                }
            });
            searchBox.setRunSearchOnTextChange(false);
            searchBox.addModifyListener(e ->
            {
                if (searchBox.isDisposed())
                    return;
                scheduleApply(searchBox, viewer, filter, smartLp, expandManager,
                    applyGeneration, runningJob);
            });
            FilterInputBoxListNavigation.installTreeNavigation(searchBox, tree);

            String initial = searchBox.getText();
            if (initial != null && !initial.isEmpty())
                scheduleApply(searchBox, viewer, filter, smartLp, expandManager,
                    applyGeneration, runningJob);

            tree.setData(PATCHED_KEY, Boolean.TRUE);
            Debug.log("tryPatch PATCH OK"); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            Global.logError(TAG, "tryPatch", e); //$NON-NLS-1$
            return true;
        }
    }

    /**
     * Дебаунс набора: через паузу запускается пересчёт, промежуточные вызовы гасятся
     * номером поколения.
     */
    private static void scheduleApply(SearchBox searchBox, TreeViewer viewer, ContentsFilter filter,
        SmartOutlineLabelProvider smartLp, Object expandManager, int[] applyGeneration,
        AtomicReference<Job> runningJob)
    {
        if (searchBox == null || searchBox.isDisposed())
            return;
        Display display = searchBox.getDisplay();
        if (display == null || display.isDisposed())
            return;

        // Спусковой призрак SearchBox (фокус/очистка) иногда перезапускает пустой
        // запрос, когда фильтр уже снят: повторный asyncCollapseAll сворачивал узлы,
        // развернутые пользователем вручную. Такой вызов — заведомый no-op.
        String requestText = searchBox.getText();
        boolean emptyRequest = requestText == null || requestText.trim().isEmpty();
        if (emptyRequest && !filter.isActive())
            return;

        int generation = ++applyGeneration[0];
        display.timerExec(SEARCH_DEBOUNCE_MS, () ->
        {
            if (searchBox.isDisposed() || generation != applyGeneration[0])
                return;
            startCompute(searchBox, viewer, filter, smartLp, expandManager,
                applyGeneration, generation, runningJob);
        });
    }

    /**
     * Пустой запрос снимает фильтр сразу в UI (быстрый путь); непустой — запускает фоновый
     * {@link Job}: обход дерева вне UI-потока, построение множества видимых узлов, затем
     * атомарная подмена множества и обновление UI.
     */
    private static void startCompute(SearchBox searchBox, TreeViewer viewer, ContentsFilter filter,
        SmartOutlineLabelProvider smartLp, Object expandManager, int[] applyGeneration,
        int generation, AtomicReference<Job> runningJob)
    {
        String pattern = searchBox.getText().trim();

        Job previous = runningJob.getAndSet(null);
        if (previous != null)
            previous.cancel();

        if (pattern.isEmpty())
        {
            finalizeUi(viewer, filter, smartLp, expandManager, pattern, null, applyGeneration, generation);
            return;
        }

        Object input = viewer.getInput();
        ITreeContentProvider tcp = viewer.getContentProvider() instanceof ITreeContentProvider provider
            ? provider : null;
        ILabelProvider labels = filter.matchLabels();
        SmartMatcher matcher = new SmartMatcher(pattern);

        if (input == null || tcp == null || labels == null)
        {
            finalizeUi(viewer, filter, smartLp, expandManager, pattern, null, applyGeneration, generation);
            return;
        }

        final Job[] jobHolder = new Job[1];
        jobHolder[0] = Job.create("Комфорт: фильтр содержания синтакс-помощника", monitor -> //$NON-NLS-1$
        {
            Set<Object> visible = computeVisible(input, tcp, labels, matcher, monitor);
            if (monitor.isCanceled() || visible == null)
                return Status.CANCEL_STATUS;

            Display display = viewer.getControl() != null && !viewer.getControl().isDisposed()
                ? viewer.getControl().getDisplay() : null;
            if (display == null || display.isDisposed())
                return Status.CANCEL_STATUS;
            display.asyncExec(() ->
            {
                if (viewer.getControl().isDisposed() || generation != applyGeneration[0])
                    return;
                runningJob.compareAndSet(jobHolder[0], null);
                if (viewer.getInput() != input)
                {
                    // Дерево пересобрано (смена версии/языка справки): множество из старых
                    // узлов бесполезно — пересчитать по актуальному input'у.
                    scheduleApply(searchBox, viewer, filter, smartLp, expandManager,
                        applyGeneration, runningJob);
                    return;
                }
                finalizeUi(viewer, filter, smartLp, expandManager, pattern, visible,
                    applyGeneration, generation);
            });
            return Status.OK_STATUS;
        });
        jobHolder[0].setSystem(true);
        jobHolder[0].setPriority(Job.DECORATE);
        runningJob.set(jobHolder[0]);
        jobHolder[0].schedule();
    }

    /**
     * Обход дерева вне UI-потока. Возвращает множество видимых узлов: совпавшие по подписи
     * плюс все их предки до корня. Отмена проверяется порциями; циклы страхуются реестром
     * посещённых узлов.
     */
    private static Set<Object> computeVisible(Object root, ITreeContentProvider tcp,
        ILabelProvider labels, SmartMatcher matcher, IProgressMonitor monitor)
    {
        Set<Object> visible = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Object, Boolean> visited = new IdentityHashMap<>();
        Deque<Object> stack = new ArrayDeque<>();
        stack.push(root);
        int sinceCancelCheck = 0;

        while (!stack.isEmpty())
        {
            if (++sinceCancelCheck >= 256)
            {
                sinceCancelCheck = 0;
                if (monitor.isCanceled())
                    return null;
            }

            Object element = stack.pop();
            Boolean seen = visited.put(element, Boolean.TRUE);
            if (seen != null)
                continue;

            String text = labels.getText(element);
            if (text != null && !text.isEmpty() && matcher.matches(text))
                addWithAncestors(visible, tcp, element);

            Object[] children = tcp.getChildren(element);
            if (children != null)
                for (Object child : children)
                    if (child != null && !visited.containsKey(child))
                        stack.push(child);
        }
        return visible;
    }

    /** Узел и все его предки до корня становятся видимыми; предки уже во множестве — стоп. */
    private static void addWithAncestors(Set<Object> visible, ITreeContentProvider tcp, Object element)
    {
        Object current = element;
        while (current != null && visible.add(current))
            current = tcp.getParent(current);
    }

    /**
     * Применение готового результата в UI: подмена множества, паттерн подсветки, refresh,
     * перезапуск асинхронного раскрытия/сворачивания — порядок как в штатном search(String).
     */
    private static void finalizeUi(TreeViewer viewer, ContentsFilter filter,
        SmartOutlineLabelProvider smartLp, Object expandManager, String pattern,
        Set<Object> visible, int[] applyGeneration, int generation)
    {
        try
        {
            if (viewer.getControl() == null || viewer.getControl().isDisposed()
                || generation != applyGeneration[0])
                return;

            boolean filtering = visible != null;
            filter.setVisible(filtering ? visible : null);
            smartLp.setHighlightPattern(pattern);

            viewer.refresh();

            Global.invokeVoid(expandManager, "cancel"); //$NON-NLS-1$
            Global.invokeVoid(expandManager, filtering ? "asyncExpandAll" : "asyncCollapseAll", //$NON-NLS-1$ //$NON-NLS-2$
                EXPAND_STEP);
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "finalizeUi", e); //$NON-NLS-1$
        }
    }

    /**
     * Узел виден, если он есть в готовом множестве (считается в фоне, см.
     * {@link #computeVisible}); пустой фильтр пропускает всё. Никакого обхода поддеревьев
     * в UI-потоке.
     */
    private static final class ContentsFilter extends ViewerFilter
    {
        /** Подписи для матчинга — штатный label provider (getText() без SWT). */
        private volatile ILabelProvider matchLabels;

        /** Видимые узлы последнего завершённого расчёта; {@code null} — фильтр неактивен. */
        private volatile Set<Object> visible;

        void setVisible(Set<Object> newVisible)
        {
            visible = newVisible;
        }

        boolean isActive()
        {
            return visible != null;
        }

        ILabelProvider matchLabels()
        {
            return matchLabels;
        }

        void setMatchLabels(ILabelProvider labels)
        {
            matchLabels = labels;
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            Set<Object> currentVisible = visible;
            if (currentVisible == null)
                return true;
            return currentVisible.contains(element);
        }
    }

    private static final class Debug
    {
        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
