package tormozit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
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
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
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
 * Доработки панели «Синтакс-помощник» ({@code SyntaxAssistView} бандла
 * {@code com._1c.g5.v8.dt.bsl.ui}).
 *
 * <p><b>1. Фильтр «Содержание».</b> Многословный фильтр ({@link SmartMatcher}, плоское И по
 * словам) с раскраской совпадений.
 *
 * <p>Штатное поведение (декомпиляция {@code SyntaxAssistContentsPanel}): поле поиска — уже
 * {@link SearchBox} ({@code ContextSearchBox}), но матчинг делает
 * {@code ContentsViewerFilter} через регэксп с подстановками {@code *}/{@code ?}; история —
 * голый {@code InMemorySearchHistory} (теряется при закрытии EDT); поиск стартует от
 * 2 символов. Штатный фильтр ищет подстроку ещё и в тексте справки узла — наш фильтр,
 * как во всех остальных местах плагина, матчит по видимой подписи узла.
 *
 * <p>Что делает эта часть. Штатный {@code ContentsViewerFilter} НЕ трогается — он всегда висит на
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
 *
 * <p><b>2. Стиль ссылок страницы «Поиск» ({@link LinkStyle}).</b> Подчёркивание
 * ссылок-результатов — только при наведении, как у заголовков страниц самого EDT
 * ({@code a.title-link} в {@code css/view.css}).
 *
 * <p><b>3. История поиска страницы «Поиск» ({@link SearchHistory}).</b> Поле
 * полнотекстового поиска получает персистентную историю вместо штатной
 * {@code InMemorySearchHistory} — переживает закрытие панели и перезапуск EDT.
 *
 * <p><b>4. Состояние закрытой панели ({@link NavigationState}).</b> В пределах
 * текущего сеанса EDT восстанавливаются статья, ветвящаяся история переходов,
 * вкладка навигации, результаты поиска и позиция дерева содержания.
 */
public final class SyntaxAssistHook implements IStartup
{
    private static final String TAG = "SyntaxAssistHook"; //$NON-NLS-1$

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
                @Override public void windowClosed(IWorkbenchWindow w)       { NavigationState.SAVED.remove(w); }
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
        // Синхронно: команда открытия статьи после showView должна иметь приоритет
        // над восстановлением предыдущего состояния панели.
        NavigationState.install(viewPart);
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
            {
                // Панель описания/поиска и их браузер/поле создаются в том же
                // createPartControl, но не зависят от дерева содержания — свои retry-циклы.
                LinkStyle.scheduleInstall(view, 0);
                SearchHistory.scheduleInstall(view, 0);
                return;
            }
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
                FilterInputBox.FLAT_FILTER_TOOLTIP + "\nCtrl+↓ — история запросов.")); //$NON-NLS-1$
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

    /**
     * Подчёркивание ссылок панели «Синтакс-помощник» — только при наведении, единообразно
     * во всех браузерах панели (описание страниц и результаты поиска).
     *
     * <p><b>Почему.</b> Страницы — HTML во встроенном браузере (JavaFX WebView): ссылки
     * строятся как обычные {@code <a href>} без класса, а в {@code view.css} для {@code a}
     * заданы только цвета — подчёркивание остаётся браузерным умолчанием. В настройках EDT
     * управляется только цвет ссылок, не {@code text-decoration}. Подчёркивания подряд идущих
     * ссылок в тексте (особенно в результатах поиска) сливаются в сплошную линию.
     *
     * <p><b>Механика.</b> У панели «Синтакс-помощник» ДВА независимых экземпляра
     * {@code SyntaxAssistBrowser} (декомпиляция bsl.ui): {@code SyntaxAssistDescriptionPanel
     * .getBrowser()} — страницы описаний, и {@code SyntaxAssistSearchPanel
     * .getSearchResultsBrowser()} — только {@code SearchResultsPageDescriptor} (своя фабрика,
     * свой список слушателей). Хук ставится на оба, независимо. После успешной загрузки
     * страницы (SUCCEEDED штатного load worker'а, FX-поток) браузер обновляет
     * {@code showedHistoryNode} и оповещает слушателей {@code addShowedPageChangedListener}.
     * Наш слушатель (Proxy по внутреннему интерфейсу {@code ISyntaxAssistBrowserListener} —
     * пакеты не экспортируются, поэтому только так) на каждый показ страницы, независимо от
     * типа дескриптора, вставляет в документ {@code <style>} c
     * {@code a:not(.title-link) { text-decoration: none }} (и подчёркиванием при
     * {@code :hover}/{@code :active}) — единый стиль что для описаний, что для результатов
     * поиска. Вставка идемпотентна (проверка по id элемента), повторные загрузки страницы
     * (обновление, история) безопасны. Скрипт исполняется package-private
     * {@code executeScript} — через {@link Global#invoke}.
     *
     * <p><b>Границы.</b> Заголовки страниц ({@code .title-link}) не трогаются — их правило
     * подчёркивания-при-наведении уже задано штатным {@code view.css}.
     */
    private static final class LinkStyle
    {
        private static final String LISTENER_CLASS =
            "com._1c.g5.v8.dt.internal.bsl.ui.syntaxassist.browser.ISyntaxAssistBrowserListener"; //$NON-NLS-1$

        /** id вставляемого элемента {@code <style>} — идемпотентность повторных загрузок. */
        private static final String STYLE_ID = "comfort-syntax-assist-links"; //$NON-NLS-1$

        /**
         * Подчёркивание ссылок результатов поиска — только при наведении/нажатии; состояние
         * покоя — без подчёркивания. Симметрично правилам {@code a.title-link} из EDT.
         */
        private static final String STYLE_SCRIPT = "(function(){" //$NON-NLS-1$
            + "var d=document;"
            + "if(!d.head)return 'no-head';"
            + "if(d.getElementById('" + STYLE_ID + "'))return 'already';"
            + "var s=d.createElement('style');"
            + "s.id='" + STYLE_ID + "';"
            + "s.appendChild(d.createTextNode("
            + "'a:not(.title-link):link,a:not(.title-link):visited{text-decoration:none}'"
            + "+'a:not(.title-link):hover,a:not(.title-link):active{text-decoration:underline}'));"
            + "d.head.appendChild(s);"
            + "return 'ok';})()"; //$NON-NLS-1$

        private static final Set<Object> HOOKED_BROWSERS = Collections.newSetFromMap(new IdentityHashMap<>());

        private LinkStyle() {}

        /**
         * Своё подключение с retry — оба браузера панели создаются в том же
         * {@code createPartControl}, но не зависят от дерева содержания.
         */
        static void scheduleInstall(IViewPart view, int attempt)
        {
            if (view == null || attempt >= MAX_ATTEMPTS)
            {
                if (view != null && attempt >= MAX_ATTEMPTS)
                    Global.tempLog(TAG, "link-style: не удалось подключить после " //$NON-NLS-1$
                        + MAX_ATTEMPTS + " попыток"); //$NON-NLS-1$
                return;
            }
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            Runnable task = () ->
            {
                if (!tryInstall(view))
                    scheduleInstall(view, attempt + 1);
            };
            if (attempt == 0)
                display.asyncExec(task);
            else
                display.timerExec(RETRY_MS, task);
        }

        /** @return {@code true}, если делать больше нечего (оба браузера подключены/недоступны). */
        private static boolean tryInstall(IViewPart view)
        {
            Class<?> listenerClass = BslSyntaxAssist.bslUiClass(LISTENER_CLASS);
            if (listenerClass == null)
                return true;

            Object descriptionPanel = Global.invoke(view, "getDescriptionPanel"); //$NON-NLS-1$
            Object descriptionBrowser = descriptionPanel != null
                ? Global.invoke(descriptionPanel, "getBrowser") : null; //$NON-NLS-1$

            Object navigationPanel = Global.invoke(view, "getNavigationPanel"); //$NON-NLS-1$
            Object searchPanel = navigationPanel != null
                ? Global.invoke(navigationPanel, "getSearchPanel") : null; //$NON-NLS-1$
            Object searchBrowser = searchPanel != null
                ? Global.invoke(searchPanel, "getSearchResultsBrowser") : null; //$NON-NLS-1$

            boolean descriptionReady = descriptionBrowser == null || hookBrowser(descriptionBrowser, listenerClass);
            boolean searchReady = searchBrowser == null || hookBrowser(searchBrowser, listenerClass);
            // Оба браузера появляются в одном createPartControl — если хотя бы один ещё
            // не создан, повторяем позже, чтобы не подключить только один и забыть про второй.
            return descriptionBrowser != null && searchBrowser != null && descriptionReady && searchReady;
        }

        /** @return {@code true}, если браузер подключён (сейчас или ранее) либо подключение невозможно. */
        private static boolean hookBrowser(Object browser, Class<?> listenerClass)
        {
            if (!HOOKED_BROWSERS.add(browser))
                return true;
            try
            {
                Object listener = listenerProxy(browser, listenerClass);
                if (listener == null)
                {
                    HOOKED_BROWSERS.remove(browser);
                    return true;
                }
                Global.invokeVoid(browser, "addShowedPageChangedListener", listener); //$NON-NLS-1$

                Control control = (Control)Global.invoke(browser, "getControl"); //$NON-NLS-1$
                if (control != null)
                {
                    control.addDisposeListener(e ->
                    {
                        HOOKED_BROWSERS.remove(browser);
                        Global.invokeVoid(browser, "removeShowedPageChangedListener", listener); //$NON-NLS-1$
                    });
                }
                return true;
            }
            catch (RuntimeException e)
            {
                HOOKED_BROWSERS.remove(browser);
                Global.logError(TAG, "link-style: установка", e); //$NON-NLS-1$
                return true;
            }
        }

        /** Proxy внутреннего интерфейса {@code ISyntaxAssistBrowserListener} (один метод). */
        private static Object listenerProxy(Object browser, Class<?> listenerClass)
        {
            ClassLoader loader = listenerClass.getClassLoader();
            if (loader == null)
                return null;
            // Цвет читаем в SWT-потоке установки; notifyListener вызывается в FX-потоке.
            String availabilityColor = PlatformWordAvailability.recentColor();
            return Proxy.newProxyInstance(loader, new Class<?>[] { listenerClass },
                (proxy, method, args) ->
                {
                    try
                    {
                        String name = method.getName();
                        if ("notifyListener".equals(name)) //$NON-NLS-1$
                        {
                            onShowedPage(browser, availabilityColor);
                            return null;
                        }
                        if ("hashCode".equals(name) && (args == null || args.length == 0)) //$NON-NLS-1$
                            return Integer.valueOf(System.identityHashCode(proxy));
                        if ("equals".equals(name) && args != null && args.length == 1) //$NON-NLS-1$
                            return Boolean.valueOf(proxy == args[0]);
                        if ("toString".equals(name) && (args == null || args.length == 0)) //$NON-NLS-1$
                            return "SyntaxAssistHook.LinkStyle listener"; //$NON-NLS-1$
                        return null;
                    }
                    catch (RuntimeException e)
                    {
                        Global.logError(TAG, "link-style: слушатель", e); //$NON-NLS-1$
                        return null;
                    }
                });
        }

        /**
         * Вызов в FX-потоке после показа ЛЮБОЙ страницы этого браузера: {@code showedHistoryNode}
         * уже обновлён штатным слушателем (он добавлен раньше нашего), {@code executeScript}
         * из FX-потока легален.
         */
        private static void onShowedPage(Object browser, String availabilityColor)
        {
            try
            {
                Global.invoke(browser, "executeScript", STYLE_SCRIPT); //$NON-NLS-1$
                PlatformWordAvailability.styleSyntaxPage(browser, availabilityColor);
            }
            catch (RuntimeException e)
            {
                // Ошибки JS (JSException) и пр. — не ломаем страницу, просто фиксируем.
                Global.logError(TAG, "link-style: скрипт", e); //$NON-NLS-1$
            }
        }
    }

    /**
     * Персистентная история поля полнотекстового поиска страницы «Поиск»
     * ({@code SyntaxAssistSearchPanel.fullTextSearchBox}).
     *
     * <p>Штатно поле получает {@code new InMemorySearchHistory()} (декомпиляция
     * конструктора {@code SyntaxAssistSearchPanel}) — история живёт только пока жив
     * экземпляр панели (закрытие/переоткрытие view, перезапуск EDT — история пустая).
     * Поле создаётся штатно с {@code GridDataFactory.fillDefaults().grab(true, false)}
     * (на всю ширину строки с кнопкой «Открыть поиск») — используется
     * {@link FilterInputBox#attachHistoryKeepLayout}, компакт-ширина не навязывается
     * (как для страницы «Валидация», см. {@link FilterInputBox.Scope#VALIDATION_CHECKS}).
     * Слушатель поиска штатный, не подменяется — меняется только источник истории.
     */
    private static final class SearchHistory
    {
        private static final Set<Object> HOOKED_BOXES = Collections.newSetFromMap(new IdentityHashMap<>());

        private SearchHistory() {}

        static void scheduleInstall(IViewPart view, int attempt)
        {
            if (view == null || attempt >= MAX_ATTEMPTS)
            {
                if (view != null && attempt >= MAX_ATTEMPTS)
                    Global.tempLog(TAG, "search-history: не удалось подключить после " //$NON-NLS-1$
                        + MAX_ATTEMPTS + " попыток"); //$NON-NLS-1$
                return;
            }
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            Runnable task = () ->
            {
                if (!tryInstall(view))
                    scheduleInstall(view, attempt + 1);
            };
            if (attempt == 0)
                display.asyncExec(task);
            else
                display.timerExec(RETRY_MS, task);
        }

        /** @return {@code true}, если делать больше нечего (подключено или среда не подходит). */
        private static boolean tryInstall(IViewPart view)
        {
            try
            {
                Object navigationPanel = Global.invoke(view, "getNavigationPanel"); //$NON-NLS-1$
                Object searchPanel = navigationPanel != null
                    ? Global.invoke(navigationPanel, "getSearchPanel") : null; //$NON-NLS-1$
                Object searchBoxObj = searchPanel != null
                    ? Global.getField(searchPanel, "fullTextSearchBox") : null; //$NON-NLS-1$
                if (!(searchBoxObj instanceof SearchBox searchBox) || searchBox.isDisposed())
                    return false;
                if (!HOOKED_BOXES.add(searchBox))
                    return true;

                FilterInputBox.attachHistoryKeepLayout(searchBox, FilterInputBox.Scope.SYNTAX_SEARCH);
                searchBox.addDisposeListener(e -> HOOKED_BOXES.remove(searchBox));
                return true;
            }
            catch (RuntimeException e)
            {
                Global.logError(TAG, "search-history: установка", e); //$NON-NLS-1$
                return true;
            }
        }
    }

    /**
     * Состояние закрытой панели в пределах окна и текущего сеанса EDT (issue 737).
     * API и поля проверены по SyntaxAssistBrowser/HistoryNode/ContentsStore в бандле bsl.ui.
     * Снимок не удерживает SWT/FX-контролы: дескрипторы результатов поиска сохраняются
     * как аргументы конструктора, а при восстановлении получают новый targetBrowser
     * и highlightersStore. История сохраняет все ветви и lastVisitedNext.
     */
    private static final class NavigationState
    {
        private static final String KEY = "tormozit.syntaxAssistNavigationState"; //$NON-NLS-1$
        private static final String TOPIC = "syntax-assist-state"; //$NON-NLS-1$
        private static final Map<IWorkbenchWindow, NavigationState> SAVED = new IdentityHashMap<>();

        private Object version;
        private int tab;
        private NodePosition selection;
        private NodePosition top;
        private final List<NodePosition> expanded = new ArrayList<>();
        private String query;
        private BrowserHistory descriptionHistory;
        private BrowserHistory searchHistory;

        static void install(IViewPart view)
        {
            try
            {
                Object navigation = Global.invoke(view, "getNavigationPanel"); //$NON-NLS-1$
                Object contents = Global.invoke(navigation, "getContentsPanel"); //$NON-NLS-1$
                Object search = Global.invoke(navigation, "getSearchPanel"); //$NON-NLS-1$
                Object description = Global.invoke(view, "getDescriptionPanel"); //$NON-NLS-1$
                Object browser = Global.invoke(description, "getBrowser"); //$NON-NLS-1$
                Object searchBrowser = Global.invoke(search, "getSearchResultsBrowser"); //$NON-NLS-1$
                Object rootObject = Global.getField(view, "sashForm"); //$NON-NLS-1$
                Object viewerObject = Global.getField(contents, "viewer"); //$NON-NLS-1$
                if (!(rootObject instanceof Control root) || root.isDisposed()
                    || !(viewerObject instanceof TreeViewer viewer) || browser == null || searchBrowser == null
                    || Boolean.TRUE.equals(root.getData(KEY)))
                    return;

                IWorkbenchWindow window = view.getViewSite().getWorkbenchWindow();
                root.setData(KEY, Boolean.TRUE);
                // Dispose родителя приходит до уничтожения дочерних дерева и браузеров.
                root.addDisposeListener(e ->
                {
                    try
                    {
                        NavigationState state = capture(navigation, contents, search, browser, searchBrowser, viewer);
                        SAVED.put(window, state);
                        Global.tempLog(TOPIC, "сохранено: вкладка=" + state.tab //$NON-NLS-1$
                            + ", ветвей=" + state.expanded.size() //$NON-NLS-1$
                            + ", статей=" + state.descriptionHistory.pages.size() //$NON-NLS-1$
                            + ", страниц поиска=" + state.searchHistory.pages.size()); //$NON-NLS-1$
                    }
                    catch (Exception ex)
                    {
                        Global.tempLogException(TOPIC, "сохранение", ex); //$NON-NLS-1$
                    }
                });
                NavigationState state = SAVED.get(window);
                if (state != null)
                    state.restore(navigation, contents, search, browser, searchBrowser, viewer);
                Global.tempLog(TOPIC, "подключено: восстановление=" + (state != null)); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                Global.tempLogException(TOPIC, "подключение/восстановление", e); //$NON-NLS-1$
            }
        }

        private static NavigationState capture(Object navigation, Object contents, Object search,
            Object browser, Object searchBrowser, TreeViewer viewer)
        {
            NavigationState state = new NavigationState();
            Object store = Global.invoke(contents, "getContentsStore"); //$NON-NLS-1$
            state.version = Global.invoke(store, "getVersion"); //$NON-NLS-1$
            state.tab = (Integer)Global.invoke(navigation, "getSelectedTab"); //$NON-NLS-1$
            state.selection = NodePosition.capture(viewer.getStructuredSelection().getFirstElement());
            TreeItem topItem = viewer.getTree().getTopItem();
            state.top = NodePosition.capture(topItem != null ? topItem.getData() : null);
            for (Object node : viewer.getExpandedElements())
                state.expanded.add(NodePosition.capture(node));
            SearchBox box = (SearchBox)Global.getField(search, "fullTextSearchBox"); //$NON-NLS-1$
            state.query = box.getText();
            state.descriptionHistory = BrowserHistory.capture(browser);
            state.searchHistory = BrowserHistory.capture(searchBrowser);
            return state;
        }

        private void restore(Object navigation, Object contents, Object search, Object browser,
            Object searchBrowser, TreeViewer viewer) throws ReflectiveOperationException
        {
            Object store = Global.invoke(contents, "getContentsStore"); //$NON-NLS-1$
            if (version != null && !version.equals(Global.invoke(store, "getVersion"))) //$NON-NLS-1$
                Global.invokeVoid(store, "setVersion", version); //$NON-NLS-1$
            Object docTree = Global.invoke(store, "getDocumentationTree"); //$NON-NLS-1$
            List<Object> nodes = new ArrayList<>();
            for (NodePosition position : expanded)
            {
                Object node = position.resolve(docTree, viewer.getInput());
                if (node != null)
                    nodes.add(node);
            }
            viewer.setExpandedElements(nodes.toArray());
            Object selected = selection.resolve(docTree, viewer.getInput());
            if (selected != null)
                Global.invokeVoid(contents, "setSelection", selected); //$NON-NLS-1$
            Object topNode = top.resolve(docTree, viewer.getInput());
            Deque<TreeItem> items = new ArrayDeque<>();
            Collections.addAll(items, viewer.getTree().getItems());
            while (topNode != null && !items.isEmpty())
            {
                TreeItem item = items.removeFirst();
                if (item.getData() == topNode)
                {
                    viewer.getTree().setTopItem(item);
                    break;
                }
                Collections.addAll(items, item.getItems());
            }
            SearchBox box = (SearchBox)Global.getField(search, "fullTextSearchBox"); //$NON-NLS-1$
            boolean runOnChange = Boolean.TRUE.equals(Global.getField(box, "runSearchOnTextChange")); //$NON-NLS-1$
            box.setRunSearchOnTextChange(false);
            // SearchBox.setText запускает поиск даже при runSearchOnTextChange=false
            // (флаг settingText). Возвращаем текст через API StyledText без этого флага.
            try
            {
                if (!query.isEmpty())
                {
                    Global.invokeVoid(box, "hideMessage"); //$NON-NLS-1$
                    box.replaceTextRange(0, box.getCharCount(), query);
                }
            }
            finally { box.setRunSearchOnTextChange(runOnChange); }
            Object highlighters = Global.getField(search, "highlightersStore"); //$NON-NLS-1$
            descriptionHistory.restore(browser, browser, highlighters);
            searchHistory.restore(searchBrowser, browser, highlighters);
            Global.invokeVoid(navigation, "setSelectedTab", tab); //$NON-NLS-1$
            // setSelectedTab сам не вызывает Selection: штатный слушатель включает
            // подсветку поиска и обновляет previousTab только по этому событию.
            CTabFolder folder = (CTabFolder)Global.getField(navigation, "tabFolder"); //$NON-NLS-1$
            Event event = new Event();
            event.item = folder.getSelection();
            folder.notifyListeners(SWT.Selection, event);
        }

        /** Идентификатор для статей; маршрут от корня — для групп без идентификатора. */
        private record NodePosition(Object id, int[] route)
        {
            static NodePosition capture(Object node)
            {
                Object id = Global.invoke(Global.invoke(node, "getId"), "toShortId"); //$NON-NLS-1$ //$NON-NLS-2$
                List<Integer> reverse = new ArrayList<>();
                Object current = node;
                while (current != null)
                {
                    Object parent = Global.invoke(current, "getParent"); //$NON-NLS-1$
                    if (parent == null)
                        break;
                    Object children = Global.invoke(parent, "getChildren"); //$NON-NLS-1$
                    if (!(children instanceof Collection<?> collection))
                        return new NodePosition(id, null);
                    int index = new ArrayList<>(collection).indexOf(current);
                    if (index < 0)
                        return new NodePosition(id, null);
                    reverse.add(index);
                    current = parent;
                }
                int[] route = node != null ? new int[reverse.size()] : null;
                if (route != null)
                    for (int i = 0; i < route.length; i++)
                        route[i] = reverse.get(route.length - 1 - i);
                return new NodePosition(id, route);
            }

            Object resolve(Object tree, Object root)
            {
                if (id != null)
                    return Global.invoke(tree, "getNode", id); //$NON-NLS-1$
                if (route == null)
                    return null;
                Object node = root;
                for (int index : route)
                {
                    Object children = Global.invoke(node, "getChildren"); //$NON-NLS-1$
                    if (!(children instanceof Collection<?> collection) || index >= collection.size())
                        return null;
                    node = new ArrayList<>(collection).get(index);
                }
                return node;
            }
        }

        private record BrowserHistory(Class<?> nodeClass, List<SavedPage> pages,
            int[] parents, int[] next, int current)
        {
            static BrowserHistory capture(Object browser)
            {
                Object current = Global.invoke(browser, "getHistoryNode"); //$NON-NLS-1$
                if (current == null)
                    return new BrowserHistory(null, List.of(), new int[0], new int[0], -1);
                Object root = current;
                Object previous;
                while ((previous = Global.invoke(root, "getPrevious")) != null) //$NON-NLS-1$
                    root = previous;
                List<Object> nodes = new ArrayList<>();
                Map<Object, Integer> indexes = new IdentityHashMap<>();
                nodes.add(root);
                indexes.put(root, 0);
                for (int i = 0; i < nodes.size(); i++)
                {
                    Object children = Global.invoke(nodes.get(i), "getNexts"); //$NON-NLS-1$
                    if (children instanceof List<?> list)
                        for (Object child : list)
                            if (!indexes.containsKey(child))
                            {
                                indexes.put(child, nodes.size());
                                nodes.add(child);
                            }
                }
                // Новый узел попадает в nexts родителя только после успешной загрузки.
                // Закрытие во время загрузки сохраняет и текущую запрошенную страницу.
                if (!indexes.containsKey(current))
                {
                    indexes.put(current, nodes.size());
                    nodes.add(current);
                }
                List<SavedPage> pages = new ArrayList<>();
                int[] parents = new int[nodes.size()];
                int[] next = new int[nodes.size()];
                for (int i = 0; i < nodes.size(); i++)
                {
                    Object node = nodes.get(i);
                    pages.add(SavedPage.capture(Global.invoke(node, "getPageDescriptor"))); //$NON-NLS-1$
                    parents[i] = indexes.getOrDefault(Global.invoke(node, "getPrevious"), -1); //$NON-NLS-1$
                    next[i] = indexes.getOrDefault(Global.invoke(node, "getLastVisitedNext"), -1); //$NON-NLS-1$
                }
                return new BrowserHistory(current.getClass(), pages, parents, next, indexes.get(current));
            }

            void restore(Object browser, Object targetBrowser, Object highlighters) throws ReflectiveOperationException
            {
                if (current < 0)
                    return;
                Class<?> descriptorClass = BslSyntaxAssist.bslUiClass(
                    "com._1c.g5.v8.dt.internal.bsl.ui.syntaxassist.browser.ISyntaxAssistBrowserPageDescriptor"); //$NON-NLS-1$
                Constructor<?> constructor = nodeClass.getConstructor(descriptorClass, nodeClass);
                List<Object> nodes = new ArrayList<>();
                for (int i = 0; i < pages.size(); i++)
                {
                    Object parent = parents[i] >= 0 ? nodes.get(parents[i]) : null;
                    Object node = constructor.newInstance(pages.get(i).restore(targetBrowser, highlighters), parent);
                    nodes.add(node);
                    if (parent != null)
                        Global.invokeVoid(parent, "add", node); //$NON-NLS-1$
                }
                for (int i = 0; i < nodes.size(); i++)
                    if (next[i] >= 0 && !Global.setFieldForce(nodes.get(i), "lastVisitedNext", nodes.get(next[i]))) //$NON-NLS-1$
                        throw new IllegalStateException("Не удалось восстановить ветвь истории");
                Global.invokeVoid(browser, "openHistoryNode", nodes.get(current)); //$NON-NLS-1$
            }
        }

        private record SavedPage(Object descriptor, Class<?> searchClass, Object[] arguments)
        {
            static SavedPage capture(Object descriptor)
            {
                String name = descriptor.getClass().getSimpleName();
                if ("SearchResultsPageDescriptor".equals(name)) //$NON-NLS-1$
                    return new SavedPage(null, descriptor.getClass(), new Object[] {
                        Global.getField(descriptor, "query"), Global.getField(descriptor, "pageNumber"), //$NON-NLS-1$ //$NON-NLS-2$
                        Global.getField(descriptor, "pageSize"), null, Global.getField(descriptor, "docProvider"), //$NON-NLS-1$ //$NON-NLS-2$
                        null, Global.getField(descriptor, "platformDocProvider"), //$NON-NLS-1$
                        Global.getField(descriptor, "queryLanguage"), Global.getField(descriptor, "version") }); //$NON-NLS-1$ //$NON-NLS-2$
                // Эти три штатных дескриптора содержат только данные и провайдер документации.
                if (!"DocumentationPageDescriptor".equals(name) && !"DocumentationPageGroupDescriptor".equals(name) //$NON-NLS-1$ //$NON-NLS-2$
                    && !"SimplePageDescriptor".equals(name)) //$NON-NLS-1$
                    throw new IllegalStateException("Неизвестный дескриптор: " + descriptor.getClass().getName());
                return new SavedPage(descriptor, null, null);
            }

            Object restore(Object targetBrowser, Object highlighters) throws ReflectiveOperationException
            {
                if (searchClass == null)
                    return descriptor;
                Object[] args = arguments.clone();
                args[3] = targetBrowser;
                args[5] = highlighters;
                // Единственный публичный конструктор SearchResultsPageDescriptor проверен в JAR.
                for (Constructor<?> constructor : searchClass.getConstructors())
                    if (constructor.getParameterCount() == args.length)
                        return constructor.newInstance(args);
                throw new NoSuchMethodException(searchClass.getName());
            }
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
