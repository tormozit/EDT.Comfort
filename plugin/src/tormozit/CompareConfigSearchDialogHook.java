package tormozit;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.viewers.IContentProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.search.ui.NewSearchUI;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartService;
import org.eclipse.ui.IWorkbenchPart;

import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.AbstractDirectPartialModelNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.AbstractNodeWithLabels;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IPartialModelNode;

/**
 * Патч диалога поиска EDT (CTRL+F в дереве сравнения конфигураций).
 */
public class CompareConfigSearchDialogHook
{
    private static final String PATCHED_KEY = "tormozit.searchPatched"; //$NON-NLS-1$
    private static final String SESSION_KEY = "tormozit.compareSearchSession"; //$NON-NLS-1$
    private static final String DIALOG_CLASS = "ComparisonTreeSearchDialog"; //$NON-NLS-1$
    private static final int PROGRESS_INTERVAL_MS = 1000;
    private static final int COLLECT_CANCEL_CHECK_INTERVAL = 256;

    // Ключи настроек для хранения в dialog_settings.xml
    private static final String SETTINGS_SECTION = "TormozitCompareConfigSearchSettings"; //$NON-NLS-1$
    private static final String KEY_SEARCH_All_rows = "searchAllRows"; //$NON-NLS-1$
    private static final String KEY_SEARCH_All_columns = "searchAllColumns"; //$NON-NLS-1$
    private static final String KEY_WHOLE_WORD = "wholeWord"; //$NON-NLS-1$

    /** scopeId для {@link FilterHistoryStore} — история запросов этого диалога отдельна от других полей фильтра. */
    private static final String COMPARE_SEARCH_HISTORY_SCOPE = "compareConfigSearch"; //$NON-NLS-1$

    // Кэш узлов между открытиями диалога, пока жив редактор сравнения
    private static final Map<IEditorPart, SearchCache> searchCacheByEditor = new WeakHashMap<>();

    private static final class SearchCache
    {
        final List<Object> items;
        final Object input;
        final int filterHash;

        SearchCache(List<Object> items, Object input, int filterHash)
        {
            this.items = items;
            this.input = input;
            this.filterHash = filterHash;
        }
    }

    public static void install(Display display)
    {
        display.addFilter(SWT.Show, event ->
        {
            if (!(event.widget instanceof Shell))
                return;
            Shell shell = (Shell)event.widget;
            if (shell.getData(PATCHED_KEY) != null)
                return;

            Object dialog = shell.getData();
            if (dialog == null)
                return;
            if (!dialog.getClass().getName().contains(DIALOG_CLASS))
                return;

            shell.setData(PATCHED_KEY, Boolean.TRUE);
            patchDialog(shell, dialog);
        });
    }

    private static void patchDialog(Shell shell, Object dialog)
    {
        Button btnCase = (Button)getField(dialog, "buttonCaseSensitive"); //$NON-NLS-1$
        Button btnNext = (Button)getField(dialog, "buttonSearch"); //$NON-NLS-1$
        Button btnPrev = (Button)getField(dialog, "buttonSearchBack"); //$NON-NLS-1$

        if (btnNext == null || btnPrev == null)
            return;

        Composite parent = btnCase != null ? btnCase.getParent() : btnNext.getParent();
        Composite buttonBar = btnNext.getParent();

        IDialogSettings settings = getDialogSettings();

        // Все флажки в одном вертикальном блоке (ячейка бывшего «С учётом регистра»),
        // иначе moveBelow разносит их по колонкам GridLayout и даёт пустоты.
        Composite checkGroup = new Composite(parent, SWT.NONE);
        GridLayout checkLayout = new GridLayout(1, false);
        checkLayout.marginWidth = 0;
        checkLayout.marginHeight = 0;
        checkLayout.verticalSpacing = 2;
        checkGroup.setLayout(checkLayout);

        if (btnCase != null)
        {
            GridData caseGd = (GridData) btnCase.getLayoutData();
            GridData groupGd;
            if (caseGd != null)
            {
                groupGd = new GridData(caseGd.horizontalAlignment, caseGd.verticalAlignment,
                        caseGd.grabExcessHorizontalSpace, caseGd.grabExcessVerticalSpace);
                groupGd.horizontalIndent = caseGd.horizontalIndent;
                groupGd.horizontalSpan = caseGd.horizontalSpan;
                groupGd.verticalSpan = caseGd.verticalSpan;
                groupGd.widthHint = caseGd.widthHint;
                groupGd.heightHint = SWT.DEFAULT;
                groupGd.exclude = caseGd.exclude;
            }
            else
            {
                groupGd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
            }
            checkGroup.setLayoutData(groupGd);
            checkGroup.moveBelow(btnCase);
            btnCase.setParent(checkGroup);
            btnCase.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        }
        else
        {
            checkGroup.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        }

        Button cbWholeWord = new Button(checkGroup, SWT.CHECK);
        cbWholeWord.setText("Слово целиком");
        cbWholeWord.setToolTipText("Искать только целые слова, а не подстроки"
                + Global.pluginSignForTooltip());
        cbWholeWord.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        cbWholeWord.setSelection(settings.getBoolean(KEY_WHOLE_WORD));
        cbWholeWord.addListener(SWT.Selection, event ->
                settings.put(KEY_WHOLE_WORD, cbWholeWord.getSelection()));

        Button cbSearchAllRows = new Button(checkGroup, SWT.CHECK);
        cbSearchAllRows.setText("По всем строкам");
        cbSearchAllRows.setToolTipText(
                "Стандартный поиск EDT ищет только по строкам имен объектов. Этот флажок включает просмотр всех строк"
                        + Global.pluginSignForTooltip());
        cbSearchAllRows.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        boolean loadDetailed = settings.get(KEY_SEARCH_All_rows) == null ? true
                : settings.getBoolean(KEY_SEARCH_All_rows);
        cbSearchAllRows.setSelection(loadDetailed);
        cbSearchAllRows.addListener(SWT.Selection,
                event -> settings.put(KEY_SEARCH_All_rows, cbSearchAllRows.getSelection()));

        Button cbSearchAllColumns = new Button(checkGroup, SWT.CHECK);
        cbSearchAllColumns.setText("По всем колонкам");
        cbSearchAllColumns.setToolTipText(
                "Дополнительно к поиску в колонках значений еще искать в колонке \"Объект\""
                        + Global.pluginSignForTooltip());
        cbSearchAllColumns.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        boolean loadObjectCol = settings.get(KEY_SEARCH_All_columns) == null ? true
                : settings.getBoolean(KEY_SEARCH_All_columns);
        cbSearchAllColumns.setSelection(loadObjectCol);
        cbSearchAllColumns.addListener(SWT.Selection,
                event -> settings.put(KEY_SEARCH_All_columns, cbSearchAllColumns.getSelection()));

        Button btnFindAll = new Button(buttonBar, SWT.PUSH);
        btnFindAll.setText("Найти все");
        btnFindAll.setToolTipText("Показать все найденные узлы в панели результатов поиска"
                + Global.pluginSignForTooltip());
        btnFindAll.setLayoutData(new org.eclipse.swt.layout.RowData());

        btnFindAll.moveAbove(btnPrev);

        Text textFilter = (Text)getField(dialog, "textFilter"); //$NON-NLS-1$

        installSearchHistory(textFilter);

        CompareConfigSearchSession session = new CompareConfigSearchSession(shell, dialog, btnNext, btnPrev, btnFindAll, textFilter, getEditorFromDialog(dialog));

        disableTreeDeactivationClearing(dialog);

        btnFindAll.addListener(SWT.Selection, event ->
        {
            if (session.isRunning())
            {
                session.cancel();
                return;
            }
            session.findAndShowAll(cbSearchAllColumns.getSelection(), cbWholeWord.getSelection());
            session.focusComparisonTree();
        });
        shell.setData(SESSION_KEY, session);
        shell.addListener(SWT.Close, event -> session.onShellClose());
        session.ensurePrefetch();

        interceptButton(btnNext, dialog, cbSearchAllRows, session, false, cbSearchAllColumns, cbWholeWord);
        interceptButton(btnPrev, dialog, cbSearchAllRows, session, true, cbSearchAllColumns, cbWholeWord);

        installEnterTriggersNext(textFilter, btnNext);

        installSearchDialogButtonKeepAlive(shell, dialog, session, textFilter, cbSearchAllRows,
                cbSearchAllColumns, cbWholeWord, btnCase);

        buttonBar.layout(true, true);
        parent.layout(true, true);
        // Ширина +100 под оверлей истории; высоту — по содержимому, без лишней «дыры» снизу.
        Point pref = shell.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
        shell.setSize(pref.x + 100, pref.y);
    }

    private static IDialogSettings getDialogSettings()
    {
        IDialogSettings topSettings = Activator.getDefault().getDialogSettings();
        IDialogSettings section = topSettings.getSection(SETTINGS_SECTION);
        if (section == null)
        {
            section = topSettings.addNewSection(SETTINGS_SECTION);
        }
        return section;
    }

    private static void interceptButton(Button button, Object dialog, Button cbSearchAllRows,
            CompareConfigSearchSession session, boolean backward, Button cbSearchAllColumns,
            Button cbWholeWord)
    {
        Listener[] original = button.getListeners(SWT.Selection);
        for (Listener l : original)
            button.removeListener(SWT.Selection, l);

        button.addListener(SWT.Selection, event ->
        {
            if (session.isRunning())
            {
                session.cancel();
                return;
            }
            if (!cbSearchAllRows.getSelection() && !cbWholeWord.getSelection())
            {
                reattachSearchEngineMediator(dialog);
                for (Listener l : original)
                    l.handleEvent(event);
            }
            else
            {
                session.startSearch(backward, cbSearchAllColumns.getSelection(),
                        cbWholeWord.getSelection());
            }
            session.focusComparisonTree();
            session.refreshSearchButtons();
        });
    }

    /**
     * История запросов поля «Найти:» — тот же общий UI, что и у поля фильтра
     * ({@link FilterHistoryUi}, {@link FilterHistoryStore}): кнопка ▾ справа от поля
     * и Ctrl+↓ прямо в поле. Само сохранение в историю — при запуске поиска
     * (см. {@code startSearch}/{@code findAndShowAll}), а не по потере фокуса,
     * т.к. Enter в этом диалоге не уводит фокус с поля (см. {@link #installEnterTriggersNext}).
     */
    private static void installSearchHistory(Text textFilter)
    {
        if (textFilter == null || textFilter.isDisposed())
            return;
        Composite rowParent = textFilter.getParent();
        if (rowParent == null || rowParent.isDisposed())
            return;

        // GridLayout родителя общий на ВСЕ строки диалога (чекбоксы, кнопки поиска) —
        // обычная колонка (numColumns++, как делает FilterHistoryUi.createButtonsRow
        // в изолированной строке фильтра Preferences) здесь ломает раскладку остальных
        // строк. Поэтому кнопка — отдельный Composite с GridData.exclude=true (родитель
        // не учитывает его при расчёте своих колонок), позиционируем вручную поверх
        // правого края поля «Найти:» и держим синхронно с ним при изменении размера.
        Composite overlay = new Composite(rowParent, SWT.NONE);
        GridLayout overlayLayout = new GridLayout(0, false);
        overlayLayout.marginWidth = 0;
        overlayLayout.marginHeight = 0;
        overlay.setLayout(overlayLayout);
        GridData excludeData = new GridData();
        excludeData.exclude = true;
        overlay.setLayoutData(excludeData);

        Composite buttonsRow = FilterHistoryUi.createButtonsRow(overlay);
        FilterHistoryUi.wireKeyboard(textFilter, COMPARE_SEARCH_HISTORY_SCOPE);
        FilterHistoryUi.addHistoryButton(buttonsRow, textFilter, COMPARE_SEARCH_HISTORY_SCOPE);

        Runnable reposition = () ->
        {
            if (textFilter.isDisposed() || overlay.isDisposed())
                return;
            Rectangle tb = textFilter.getBounds();
            Point size = overlay.computeSize(SWT.DEFAULT, SWT.DEFAULT);
            int x = tb.x + tb.width - size.x - 2;
            int y = tb.y + (tb.height - size.y) / 2;
            overlay.setBounds(x, y, size.x, size.y);
            overlay.moveAbove(textFilter);
        };

        textFilter.addListener(SWT.Resize, e -> reposition.run());
        textFilter.addListener(SWT.Move, e -> reposition.run());
        rowParent.addListener(SWT.Resize, e -> rowParent.getDisplay().asyncExec(reposition));
        rowParent.getDisplay().asyncExec(reposition);
    }

    /**
     * Enter в поле «Найти:» — как «Далее». Штатный {@code AbstractSearchDialog} без mediator
     * (снят при фокусе в поле) даёт «не найдено»; перехватываем RETURN до EDT.
     */
    private static void installEnterTriggersNext(Text textFilter, Button btnNext)
    {
        if (textFilter == null || btnNext == null)
            return;
        textFilter.addListener(SWT.Traverse, event ->
        {
            if (event.detail == SWT.TRAVERSE_RETURN)
            {
                event.doit = false;
                if (!btnNext.isDisposed())
                    btnNext.notifyListeners(SWT.Selection, new Event());
            }
        });
    }

    /**
     * EDT снимает searchEngineMediator при деактивации дерева (фокус в поле ввода диалога).
     * Отключаем DeactivationListener — PartListener по-прежнему обрабатывает смену редактора.
     */
    private static void disableTreeDeactivationClearing(Object dialog)
    {
        Object controller = getField(dialog, "controller"); //$NON-NLS-1$
        if (controller == null)
            return;

        try
        {
            Field listenersField = controller.getClass().getDeclaredField("comparisonTreeSearchUpdateListeners"); //$NON-NLS-1$
            listenersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> registered = (List<Object>)listenersField.get(controller);
            if (registered == null || registered.isEmpty())
                return;

            List<Object> toRemove = new ArrayList<>();
            List<Object> toKeep = new ArrayList<>();
            for (Object listener : registered)
            {
                if (listener != null && "DeactivationListener".equals(listener.getClass().getSimpleName())) //$NON-NLS-1$
                    toRemove.add(listener);
                else
                    toKeep.add(listener);
            }
            if (toRemove.isEmpty())
                return;

            listenersField.set(controller, toKeep);

            Object mediator = getField(controller, "searchEngineMediator"); //$NON-NLS-1$
            if (mediator != null)
            {
                Class<?> listenerType = Class.forName("com._1c.g5.v8.dt.md.compare.search.IComparisonTreeSearchUpdateListener"); //$NON-NLS-1$
                Method remove = mediator.getClass().getMethod("removeComparisonTreeSearchUpdateListener", listenerType); //$NON-NLS-1$
                for (Object listener : toRemove)
                    remove.invoke(mediator, listener);
            }
        }
        catch (Exception ignored) {}
    }

    private static void reattachSearchEngineMediator(Object dialog)
    {
        Object controller = getField(dialog, "controller"); //$NON-NLS-1$
        if (controller == null)
            return;

        if (getField(controller, "searchEngineMediator") != null) //$NON-NLS-1$
            return;

        IEditorPart editor = getEditorFromDialog(dialog);
        Object mediator = captureSearchEngineMediator(editor);
        if (mediator == null)
            return;

        try
        {
            Class<?> mediatorType = Class.forName("com._1c.g5.v8.dt.internal.compare.ui.search.ComparisonTreeSearchEngineMediator"); //$NON-NLS-1$
            Method setMediator = controller.getClass().getDeclaredMethod("setSearchEngineMediator", mediatorType, boolean.class); //$NON-NLS-1$
            setMediator.setAccessible(true);
            setMediator.invoke(controller, mediator, Boolean.FALSE);
        }
        catch (Exception ignored) {}
    }

    private static Object captureSearchEngineMediator(IEditorPart editor)
    {
        if (editor == null)
            return null;
        try
        {
            Class<?> mediatorType = Class.forName("com._1c.g5.v8.dt.internal.compare.ui.search.ComparisonTreeSearchEngineMediator"); //$NON-NLS-1$
            return editor.getAdapter(mediatorType);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static void installSearchDialogButtonKeepAlive(Shell shell, Object dialog, CompareConfigSearchSession session,
        Text textFilter, Button cbSearchAllRows, Button cbSearchAllColumns, Button cbWholeWord, Button btnCase)
    {
        Runnable keepAlive = () ->
        {
            if (shell.isDisposed())
                return;
            reattachSearchEngineMediator(dialog);
            session.refreshSearchButtons();
        };

        shell.addListener(SWT.Activate, event -> shell.getDisplay().asyncExec(keepAlive));

        FocusAdapter focusIn = new FocusAdapter()
        {
            @Override
            public void focusGained(FocusEvent e)
            {
                keepAlive.run();
            }
        };

        if (textFilter != null)
        {
            textFilter.addFocusListener(focusIn);
            textFilter.addModifyListener((ModifyEvent e) -> session.refreshSearchButtons());
        }
        if (cbSearchAllRows != null)
            cbSearchAllRows.addFocusListener(focusIn);
        if (cbSearchAllColumns != null)
            cbSearchAllColumns.addFocusListener(focusIn);
        if (cbWholeWord != null)
            cbWholeWord.addFocusListener(focusIn);
        if (btnCase != null)
            btnCase.addFocusListener(focusIn);

        shell.getDisplay().asyncExec(keepAlive);
    }

    private static int computeTreeCacheKey(AbstractTreeViewer viewer, Object input)
    {
        return Objects.hash(Arrays.hashCode(viewer.getFilters()), System.identityHashCode(input));
    }

    public static boolean isNodeMatchFilters(
        Object element,
        AbstractTreeViewer viewer)
    {
        for (ViewerFilter filter : viewer.getFilters())
        {
            try
            {
                if (!filter.select(viewer, null, element))
                    return false;
            }
            catch (Throwable t)
            {
                Global.logError("CompareSearch", "filter.select failed, node excluded", t); //$NON-NLS-1$
                return false;
            }
        }

        return true;
    }

    /**
     * EDT может бросить на getChildren (напр. табличные части с разным числом строк на сторонах) —
     * такой узел считаем листом, обход остального дерева продолжается.
     */
    private static Object[] getChildrenSafe(ITreeContentProvider provider, Object parent)
    {
        try
        {
            return provider.getChildren(parent);
        }
        catch (Throwable t)
        {
            Global.logError("CompareSearch", "getChildren failed, treated as leaf", t); //$NON-NLS-1$
            return null;
        }
    }

    private static boolean collectModelItems(
        Object parent,
        ITreeContentProvider provider,
        List<Object> result,
        AbstractTreeViewer viewer,
        CollectProgress progress)
    {
        return collectModelItemsFromChildren(getChildrenSafe(provider, parent), provider, result, viewer, progress);
    }

    /**
     * Тело обхода {@link #collectModelItems}, вынесенное отдельно для читаемости.
     */
    private static boolean collectModelItemsFromChildren(
        Object[] children,
        ITreeContentProvider provider,
        List<Object> result,
        AbstractTreeViewer viewer,
        CollectProgress progress)
    {
        if (children != null)
        {
            for (Object child : children)
            {
                if (progress != null && !progress.tick())
                    return false;
                if (isNodeMatchFilters(child, viewer))
                {
                    result.add(child);
                    if (progress != null)
                        progress.nodeAdded();
                }
                if (!collectModelItems(child, provider, result, viewer, progress))
                    return false;
            }
        }
        return true;
    }

    private static final class CollectProgress
    {
        private final int generation;
        private final IntSupplier activeGeneration;
        private final IProgressMonitor monitor;
        private final Runnable onNodeAdded;
        private final Runnable onNodeProcessed;
        private int nodesSinceCheck;

        CollectProgress(int generation, IntSupplier activeGeneration, IProgressMonitor monitor, Runnable onNodeAdded, Runnable onNodeProcessed)
        {
            this.generation = generation;
            this.activeGeneration = activeGeneration;
            this.monitor = monitor;
            this.onNodeAdded = onNodeAdded;
            this.onNodeProcessed = onNodeProcessed;
        }

        boolean tick()
        {
            if (onNodeProcessed != null)
                onNodeProcessed.run();
            if (++nodesSinceCheck < COLLECT_CANCEL_CHECK_INTERVAL)
                return true;
            nodesSinceCheck = 0;
            return !monitor.isCanceled() && generation == activeGeneration.getAsInt();
        }

        void nodeAdded()
        {
            if (onNodeAdded != null)
                onNodeAdded.run();
        }
    }

    // Параллельный сбор узлов: общая очередь обхода, N воркеров (не привязка к числу корней — корень всегда один).
    private static final int COLLECT_PARALLEL_THREADS = 4;
    private static final int COLLECT_QUEUE_IDLE_YIELDS = 64;

    private static final class CollectTreeResult
    {
        final List<Object> items;
        final boolean cancelled;

        CollectTreeResult(List<Object> items, boolean cancelled)
        {
            this.items = items;
            this.cancelled = cancelled;
        }
    }

    /**
     * Параллельный сбор всех узлов дерева сравнения через общую очередь BFS-обхода.
     * Корень один — воркеры делят между собой узлы из очереди, а не «по корням».
     *
     * @param progressPercent грубая оценка % по формуле processed/(processed+queue+inFlight); может быть {@code null}
     */
    private static CollectTreeResult collectTreeItems(
        Object[] roots,
        ITreeContentProvider provider,
        AbstractTreeViewer viewer,
        int generation,
        IntSupplier activeGeneration,
        IProgressMonitor monitor,
        AtomicInteger collectedCounter,
        AtomicInteger processedCounter,
        AtomicInteger activeCollectThreads,
        AtomicInteger progressPercent)
    {
        if (roots == null || roots.length == 0)
        {
            if (progressPercent != null)
                progressPercent.set(100);
            if (monitor != null)
            {
                monitor.beginTask("Подготовка индекса поиска по сравнению", 100); //$NON-NLS-1$
                monitor.worked(100);
                monitor.done();
            }
            return new CollectTreeResult(new ArrayList<>(), false);
        }

        if (progressPercent != null)
            progressPercent.set(0);

        // worked() только с потока Job (монитор не thread-safe); воркеры пишут только AtomicInteger.
        AtomicInteger monitorReported = new AtomicInteger(0);
        if (monitor != null)
            monitor.beginTask("Подготовка индекса поиска по сравнению", 100); //$NON-NLS-1$

        List<Object> items = Collections.synchronizedList(new ArrayList<>());
        ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();
        AtomicInteger queueSize = new AtomicInteger(0);
        for (Object root : roots)
        {
            items.add(root);
            collectedCounter.incrementAndGet();
            queue.offer(root);
            queueSize.incrementAndGet();
        }

        int parallelism = Math.max(1, Math.min(COLLECT_PARALLEL_THREADS,
            Runtime.getRuntime().availableProcessors()));
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicInteger inFlight = new AtomicInteger(0);

        ForkJoinPool pool = new ForkJoinPool(parallelism);
        try
        {
            List<java.util.concurrent.ForkJoinTask<?>> workers = new ArrayList<>(parallelism);
            for (int i = 0; i < parallelism; i++)
            {
                workers.add(pool.submit(() ->
                {
                    activeCollectThreads.incrementAndGet();
                    try
                    {
                        collectTreeWorker(queue, queueSize, items, provider, viewer, generation,
                            activeGeneration, monitor, collectedCounter, processedCounter, cancelled,
                            inFlight, progressPercent);
                    }
                    finally
                    {
                        activeCollectThreads.decrementAndGet();
                    }
                }));
            }
            while (true)
            {
                boolean pending = false;
                for (java.util.concurrent.ForkJoinTask<?> worker : workers)
                {
                    if (!worker.isDone())
                    {
                        pending = true;
                        break;
                    }
                }
                advanceCollectMonitor(monitor, progressPercent, monitorReported);
                if (!pending)
                    break;
                try
                {
                    Thread.sleep(100);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    cancelled.set(true);
                    break;
                }
            }
            for (java.util.concurrent.ForkJoinTask<?> worker : workers)
                worker.join();
        }
        finally
        {
            pool.shutdown();
        }

        if (progressPercent != null && !cancelled.get())
            progressPercent.set(100);
        if (monitor != null)
        {
            if (!cancelled.get())
            {
                int left = 100 - monitorReported.get();
                if (left > 0)
                    monitor.worked(left);
            }
            monitor.done();
        }

        return new CollectTreeResult(new ArrayList<>(items), cancelled.get());
    }

    /** Грубая оценка прогресса BFS: processed / (processed + очередь + inFlight). */
    private static void publishCollectPercent(
        AtomicInteger progressPercent,
        AtomicInteger processedCounter,
        AtomicInteger queueSize,
        AtomicInteger inFlight)
    {
        if (progressPercent == null)
            return;
        int done = processedCounter.get();
        int rem = queueSize.get() + inFlight.get();
        if (done <= 0 && rem <= 0)
        {
            progressPercent.set(0);
            return;
        }
        if (rem <= 0)
        {
            progressPercent.set(100);
            return;
        }
        progressPercent.set((int)Math.min(99L, done * 100L / (done + rem)));
    }

    /** Перенос % в IProgressMonitor с потока Job (не из воркеров). */
    private static void advanceCollectMonitor(
        IProgressMonitor monitor,
        AtomicInteger progressPercent,
        AtomicInteger monitorReported)
    {
        if (monitor == null || progressPercent == null || monitorReported == null)
            return;
        int pct = Math.max(0, Math.min(100, progressPercent.get()));
        int last = monitorReported.get();
        if (pct > last)
        {
            monitor.worked(pct - last);
            monitorReported.set(pct);
        }
    }

    private static void collectTreeWorker(
        ConcurrentLinkedQueue<Object> queue,
        AtomicInteger queueSize,
        List<Object> items,
        ITreeContentProvider provider,
        AbstractTreeViewer viewer,
        int generation,
        IntSupplier activeGeneration,
        IProgressMonitor monitor,
        AtomicInteger collectedCounter,
        AtomicInteger processedCounter,
        AtomicBoolean cancelled,
        AtomicInteger inFlight,
        AtomicInteger progressPercent)
    {
        CollectProgress progress = new CollectProgress(generation, activeGeneration, monitor,
            collectedCounter::incrementAndGet, processedCounter::incrementAndGet);
        int idleYields = 0;

        while (!cancelled.get())
        {
            Object parent = queue.poll();
            if (parent == null)
            {
                if (inFlight.get() == 0)
                    break;
                if (++idleYields >= COLLECT_QUEUE_IDLE_YIELDS)
                {
                    idleYields = 0;
                    publishCollectPercent(progressPercent, processedCounter, queueSize, inFlight);
                    if (generation != activeGeneration.getAsInt()
                        || (monitor != null && monitor.isCanceled()))
                    {
                        cancelled.set(true);
                        break;
                    }
                }
                Thread.yield();
                continue;
            }

            queueSize.decrementAndGet();
            idleYields = 0;
            inFlight.incrementAndGet();
            try
            {
                Object[] children = getChildrenSafe(provider, parent);
                if (children == null)
                    continue;

                for (Object child : children)
                {
                    if (!progress.tick())
                    {
                        cancelled.set(true);
                        return;
                    }
                    if (isNodeMatchFilters(child, viewer))
                    {
                        items.add(child);
                        progress.nodeAdded();
                    }
                    queue.offer(child);
                    queueSize.incrementAndGet();
                    if ((processedCounter.get() & 63) == 0)
                        publishCollectPercent(progressPercent, processedCounter, queueSize, inFlight);
                }
            }
            finally
            {
                inFlight.decrementAndGet();
                publishCollectPercent(progressPercent, processedCounter, queueSize, inFlight);
            }
        }
    }

    private static final class StreamSearchResult
    {
        final Object match;
        final boolean cancelled;
        final int scanned;

        StreamSearchResult(Object match, boolean cancelled, int scanned)
        {
            this.match = match;
            this.cancelled = cancelled;
            this.scanned = scanned;
        }
    }

    @FunctionalInterface
    private interface StreamNodeVisitor
    {
        /** @return {@code false} — остановить обход */
        boolean visit(Object node, boolean isCandidate);
    }

    /**
     * DFS pre-order: корни всегда кандидаты (как в {@link #collectTreeItems});
     * потомки — только при {@link #isNodeMatchFilters}, но вглубь идём всегда.
     */
    private static boolean dfsStreamNodes(
        Object[] roots,
        ITreeContentProvider provider,
        AbstractTreeViewer viewer,
        StreamNodeVisitor visitor)
    {
        if (roots == null)
            return true;
        for (Object root : roots)
        {
            if (!visitor.visit(root, true))
                return false;
            if (!dfsStreamChildren(root, provider, viewer, visitor))
                return false;
        }
        return true;
    }

    private static boolean dfsStreamChildren(
        Object parent,
        ITreeContentProvider provider,
        AbstractTreeViewer viewer,
        StreamNodeVisitor visitor)
    {
        Object[] children = getChildrenSafe(provider, parent);
        if (children == null)
            return true;
        for (Object child : children)
        {
            boolean candidate = isNodeMatchFilters(child, viewer);
            if (!visitor.visit(child, candidate))
                return false;
            if (!dfsStreamChildren(child, provider, viewer, visitor))
                return false;
        }
        return true;
    }

    /**
     * Пошаговый поиск без полного индекса: isMatched на лету, стоп на первом hit.
     * Порядок — DFS pre-order; от selection вперёд/назад с wrap (как скан списка).
     */
    private static StreamSearchResult streamSearchFirstMatch(
        Object[] roots,
        ITreeContentProvider provider,
        AbstractTreeViewer viewer,
        Object selection,
        boolean backward,
        String query,
        boolean caseSensitive,
        IBaseLabelProvider labelProvider,
        boolean searchAllColumns,
        boolean wholeWord,
        int generation,
        IntSupplier activeGeneration,
        IProgressMonitor monitor,
        IntConsumer onScanned)
    {
        if (backward)
        {
            return streamSearchBackward(roots, provider, viewer, selection, query, caseSensitive,
                labelProvider, searchAllColumns, wholeWord, generation, activeGeneration, monitor,
                onScanned);
        }
        return streamSearchForward(roots, provider, viewer, selection, query, caseSensitive,
            labelProvider, searchAllColumns, wholeWord, generation, activeGeneration, monitor,
            onScanned);
    }

    private static boolean streamCancelled(
        int generation,
        IntSupplier activeGeneration,
        IProgressMonitor monitor)
    {
        return generation != activeGeneration.getAsInt()
            || (monitor != null && monitor.isCanceled());
    }

    private static StreamSearchResult streamSearchForward(
        Object[] roots,
        ITreeContentProvider provider,
        AbstractTreeViewer viewer,
        Object selection,
        String query,
        boolean caseSensitive,
        IBaseLabelProvider labelProvider,
        boolean searchAllColumns,
        boolean wholeWord,
        int generation,
        IntSupplier activeGeneration,
        IProgressMonitor monitor,
        IntConsumer onScanned)
    {
        final Object[] found = { null };
        final boolean[] cancelled = { false };
        final int[] scanned = { 0 };
        final boolean[] pastSelection = { selection == null };
        final boolean[] sawSelection = { selection == null };

        StreamNodeVisitor afterSelection = (node, candidate) ->
        {
            if (streamCancelled(generation, activeGeneration, monitor))
            {
                cancelled[0] = true;
                return false;
            }
            if (!pastSelection[0])
            {
                if (Objects.equals(node, selection))
                {
                    pastSelection[0] = true;
                    sawSelection[0] = true;
                }
                return true;
            }
            if (!candidate)
                return true;
            scanned[0]++;
            if (onScanned != null)
                onScanned.accept(scanned[0]);
            if (isMatched(node, query, caseSensitive, labelProvider, searchAllColumns, wholeWord))
            {
                found[0] = node;
                return false;
            }
            return true;
        };

        if (!dfsStreamNodes(roots, provider, viewer, afterSelection))
        {
            if (cancelled[0])
                return new StreamSearchResult(null, true, scanned[0]);
            if (found[0] != null)
                return new StreamSearchResult(found[0], false, scanned[0]);
        }

        if (cancelled[0])
            return new StreamSearchResult(null, true, scanned[0]);

        // selection не в дереве — ищем с начала (как startIdx == -1)
        if (!sawSelection[0])
        {
            StreamNodeVisitor fromStart = (node, candidate) ->
            {
                if (streamCancelled(generation, activeGeneration, monitor))
                {
                    cancelled[0] = true;
                    return false;
                }
                if (!candidate)
                    return true;
                scanned[0]++;
                if (onScanned != null)
                    onScanned.accept(scanned[0]);
                if (isMatched(node, query, caseSensitive, labelProvider, searchAllColumns, wholeWord))
                {
                    found[0] = node;
                    return false;
                }
                return true;
            };
            dfsStreamNodes(roots, provider, viewer, fromStart);
            return new StreamSearchResult(found[0], cancelled[0], scanned[0]);
        }

        // wrap: с начала до selection (не включая)
        if (selection != null && found[0] == null)
        {
            StreamNodeVisitor wrap = (node, candidate) ->
            {
                if (streamCancelled(generation, activeGeneration, monitor))
                {
                    cancelled[0] = true;
                    return false;
                }
                if (Objects.equals(node, selection))
                    return false;
                if (!candidate)
                    return true;
                scanned[0]++;
                if (onScanned != null)
                    onScanned.accept(scanned[0]);
                if (isMatched(node, query, caseSensitive, labelProvider, searchAllColumns, wholeWord))
                {
                    found[0] = node;
                    return false;
                }
                return true;
            };
            dfsStreamNodes(roots, provider, viewer, wrap);
        }

        if (cancelled[0])
            return new StreamSearchResult(null, true, scanned[0]);

        // последний шаг модульного скана — сама selection
        if (found[0] == null && selection != null
            && isMatched(selection, query, caseSensitive, labelProvider, searchAllColumns, wholeWord))
        {
            boolean selectionCandidate = true;
            boolean isRoot = false;
            if (roots != null)
            {
                for (Object root : roots)
                {
                    if (Objects.equals(root, selection))
                    {
                        isRoot = true;
                        break;
                    }
                }
            }
            if (!isRoot)
                selectionCandidate = isNodeMatchFilters(selection, viewer);
            if (selectionCandidate)
                found[0] = selection;
        }

        return new StreamSearchResult(found[0], false, scanned[0]);
    }

    private static StreamSearchResult streamSearchBackward(
        Object[] roots,
        ITreeContentProvider provider,
        AbstractTreeViewer viewer,
        Object selection,
        String query,
        boolean caseSensitive,
        IBaseLabelProvider labelProvider,
        boolean searchAllColumns,
        boolean wholeWord,
        int generation,
        IntSupplier activeGeneration,
        IProgressMonitor monitor,
        IntConsumer onScanned)
    {
        final Object[] lastBefore = { null };
        final Object[] lastAfter = { null };
        final boolean[] cancelled = { false };
        final int[] scanned = { 0 };
        final boolean[] pastSelection = { selection == null };
        final boolean[] sawSelection = { selection == null };

        StreamNodeVisitor visitor = (node, candidate) ->
        {
            if (streamCancelled(generation, activeGeneration, monitor))
            {
                cancelled[0] = true;
                return false;
            }
            if (!pastSelection[0])
            {
                if (Objects.equals(node, selection))
                {
                    pastSelection[0] = true;
                    sawSelection[0] = true;
                    return true;
                }
                if (candidate)
                {
                    scanned[0]++;
                    if (onScanned != null)
                        onScanned.accept(scanned[0]);
                    if (isMatched(node, query, caseSensitive, labelProvider, searchAllColumns, wholeWord))
                        lastBefore[0] = node;
                }
                return true;
            }
            if (candidate)
            {
                scanned[0]++;
                if (onScanned != null)
                    onScanned.accept(scanned[0]);
                if (isMatched(node, query, caseSensitive, labelProvider, searchAllColumns, wholeWord))
                    lastAfter[0] = node;
            }
            return true;
        };

        dfsStreamNodes(roots, provider, viewer, visitor);

        if (cancelled[0])
            return new StreamSearchResult(null, true, scanned[0]);

        Object match;
        if (!sawSelection[0] || selection == null)
        {
            // нет selection / не в дереве: последний hit в дереве
            match = lastBefore[0] != null ? lastBefore[0] : lastAfter[0];
        }
        else if (lastBefore[0] != null)
            match = lastBefore[0];
        else if (lastAfter[0] != null)
            match = lastAfter[0];
        else if (isMatched(selection, query, caseSensitive, labelProvider, searchAllColumns, wholeWord))
        {
            boolean isRoot = false;
            if (roots != null)
            {
                for (Object root : roots)
                {
                    if (Objects.equals(root, selection))
                    {
                        isRoot = true;
                        break;
                    }
                }
            }
            match = (isRoot || isNodeMatchFilters(selection, viewer)) ? selection : null;
        }
        else
            match = null;

        return new StreamSearchResult(match, false, scanned[0]);
    }

    private static boolean isMatched(Object element, String query, boolean caseSensitive,
            IBaseLabelProvider baseLabelProvider, boolean cbSearchAllColumns, boolean wholeWord)
    {
        if (baseLabelProvider instanceof ILabelProvider) {
            String text;
            if (element instanceof AbstractNodeWithLabels) {
                AbstractNodeWithLabels node = (AbstractNodeWithLabels) element;
                text = getSideLabelSafe(node, ComparisonSide.MAIN);
                if (textMatches(text, query, caseSensitive, wholeWord))
                    return true;
                text = getSideLabelSafe(node, ComparisonSide.OTHER);
                if (textMatches(text, query, caseSensitive, wholeWord))
                    return true;
                text = getSideLabelSafe(node, ComparisonSide.COMMON_ANCESTOR);
                if (textMatches(text, query, caseSensitive, wholeWord))
                    return true;
            }
            if (cbSearchAllColumns)
            {
                text = extractNodeLabel(element);
                if (textMatches(text, query, caseSensitive, wholeWord))
                    return true;
            }
        }
        return false;
    }

    private static String extractNodeLabel(Object node)
    {
        try
        {
            Object styled = Global.invoke(node, "getStyledText"); //$NON-NLS-1$
            if (styled instanceof StyledString ss)
            {
                String text = ss.getString();
                if (text != null && !text.isEmpty())
                    return text;
            }
        }
        catch (Exception ignored) {}
        try
        {
            Object label = Global.invoke(node, "getLabel"); //$NON-NLS-1$
            if (label instanceof String s && !s.isEmpty())
                return s;
        }
        catch (Exception ignored) {}
        return ""; //$NON-NLS-1$
    }

    private static String buildPathForNode(Object element)
    {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (Object cur = element; cur != null; )
        {
            String label = extractNodeLabel(cur);
            if (label != null && !label.isEmpty())
                parts.add(0, label);
            try
            {
                cur = Global.invoke(cur, "getParent"); //$NON-NLS-1$
            }
            catch (Throwable t)
            {
                Global.logError("CompareSearch", "buildPath: getParent failed", t); //$NON-NLS-1$
                break;
            }
        }
        return String.join(".", parts); //$NON-NLS-1$
    }

    private static String getCachedObjectPath(Object parent, Map<Object, String> cache)
    {
        if (parent == null)
            return ""; //$NON-NLS-1$
        return cache.computeIfAbsent(parent, CompareConfigSearchDialogHook::buildPathForNode);
    }

    private static boolean textMatches(String text, String effectiveQuery, boolean caseSensitive,
            boolean wholeWord)
    {
        if (text == null || effectiveQuery == null || effectiveQuery.isEmpty())
            return false;
        if (!wholeWord)
            return caseSensitive ? text.contains(effectiveQuery)
                    : text.toLowerCase().contains(effectiveQuery);
        return containsWholeWord(text, effectiveQuery, caseSensitive);
    }

    /** Как в {@link ConfigSearchDialogHook}: границы слова = буква/цифра/`_`. */
    private static boolean containsWholeWord(String text, String query, boolean caseSensitive)
    {
        String src = caseSensitive ? text : text.toLowerCase();
        // effectiveQuery уже в lower при !caseSensitive
        String q = query;
        int from = 0;
        while (from <= src.length() - q.length())
        {
            int idx = src.indexOf(q, from);
            if (idx < 0)
                return false;
            boolean startOk = idx == 0 || !isWordChar(src.charAt(idx - 1));
            int end = idx + q.length();
            boolean endOk = end >= src.length() || !isWordChar(src.charAt(end));
            if (startOk && endOk)
                return true;
            from = idx + 1;
        }
        return false;
    }

    private static boolean isWordChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static final class ComparisonStatusInfo
    {
        final String status;
        final CompareSearchMatch.RowColorKind rowColorKind;
        final boolean checkable;

        ComparisonStatusInfo(String status, CompareSearchMatch.RowColorKind rowColorKind, boolean checkable)
        {
            this.status = status;
            this.rowColorKind = rowColorKind;
            this.checkable = checkable;
        }
    }

    /**
     * Статус и цвет строки — одно вычисление.
     * Сначала {@code getSide()} (как окраска дерева EDT): MAIN/OTHER = только на стороне.
     * {@code hasOnlyOnOneSide(MAIN,OTHER)} при {@code getSide()==null} в трёхстороннем сравнении
     * даёт ложные «Удалено» — не использовать для статуса.
     */
    private static ComparisonStatusInfo computeComparisonStatus(Object element)
    {
        boolean checkable = true;
        if (element instanceof AbstractNodeWithLabels labeled)
            checkable = labeled.isCheckable();

        ComparisonSide side = null;
        if (element instanceof IPartialModelNode partial)
            side = partial.getSide();

        if (side == ComparisonSide.MAIN)
            return new ComparisonStatusInfo("Удалено", CompareSearchMatch.RowColorKind.ONLY_MAIN, checkable);
        if (side == ComparisonSide.OTHER)
            return new ComparisonStatusInfo("Добавлено", CompareSearchMatch.RowColorKind.ONLY_OTHER, checkable);

        boolean changed = false;
        if (element instanceof AbstractNodeWithLabels node)
            changed = node.hasChanged(ComparisonSide.MAIN, ComparisonSide.OTHER);
        else if (element instanceof AbstractDirectPartialModelNode node)
            changed = node.hasDifferences(ComparisonSide.MAIN, ComparisonSide.OTHER);
        else if (element instanceof IPartialModelNode node)
            changed = node.hasDifferences(ComparisonSide.MAIN, ComparisonSide.OTHER);

        if (changed)
            return new ComparisonStatusInfo("Изменено", CompareSearchMatch.RowColorKind.HAS_DIFFS, checkable);
        return new ComparisonStatusInfo("", CompareSearchMatch.RowColorKind.NONE, checkable);
    }

    /**
     * EDT (hasChanged/hasOnlyOnOneSide) может бросить исключение на отдельных узлах
     * (напр. "Different count of objects to build equal nodes for..." для табличных частей
     * с разным числом строк на сторонах) — в findAll такой узел не должен обрывать весь поиск.
     */
    private static ComparisonStatusInfo computeComparisonStatusSafe(Object element)
    {
        try
        {
            return computeComparisonStatus(element);
        }
        catch (Throwable t)
        {
            Global.logError("CompareSearch", "findAll: status computation failed", t); //$NON-NLS-1$
            return new ComparisonStatusInfo("", CompareSearchMatch.RowColorKind.NONE, true);
        }
    }

    private static final class ColumnHit
    {
        final String columnSide;
        final String matchText;

        ColumnHit(String columnSide, String matchText)
        {
            this.columnSide = columnSide;
            this.matchText = matchText;
        }
    }

    private static String getSideLabelSafe(AbstractNodeWithLabels node, ComparisonSide side)
    {
        try
        {
            return node.getSideLabel(side);
        }
        catch (Throwable t)
        {
            Global.logError("CompareSearch", "getSideLabel failed", t); //$NON-NLS-1$
            return null;
        }
    }

    private static void collectColumnHits(Object element, String effectiveQuery, boolean caseSensitive,
        boolean searchAllColumns, boolean wholeWord, String headerMain, String headerOther,
        String headerAncestor, String headerObject, List<ColumnHit> hits)
    {
        if (element instanceof AbstractNodeWithLabels node)
        {
            String text = getSideLabelSafe(node, ComparisonSide.MAIN);
            if (textMatches(text, effectiveQuery, caseSensitive, wholeWord))
                hits.add(new ColumnHit(headerMain != null ? headerMain : "MAIN", text)); //$NON-NLS-1$

            text = getSideLabelSafe(node, ComparisonSide.OTHER);
            if (textMatches(text, effectiveQuery, caseSensitive, wholeWord))
                hits.add(new ColumnHit(headerOther != null ? headerOther : "OTHER", text)); //$NON-NLS-1$

            text = getSideLabelSafe(node, ComparisonSide.COMMON_ANCESTOR);
            if (textMatches(text, effectiveQuery, caseSensitive, wholeWord))
                hits.add(new ColumnHit(headerAncestor != null ? headerAncestor : "ОбщийПредок", text)); //$NON-NLS-1$
        }
        if (searchAllColumns)
        {
            String text = extractNodeLabel(element);
            if (textMatches(text, effectiveQuery, caseSensitive, wholeWord))
                hits.add(new ColumnHit(headerObject, text));
        }
    }

    private static void setStatus(Object dialog, String message)
    {
        try
        {
            dialog.getClass().getMethod("setMessage", String.class).invoke(dialog, message); //$NON-NLS-1$
        }
        catch (Exception ignored) {}
    }

    private static void clearStatus(Object dialog)
    {
        setStatus(dialog, ""); //$NON-NLS-1$
    }

    private static void updateDialog(Object dialog)
    {
        try
        {
            dialog.getClass().getMethod("update").invoke(dialog); //$NON-NLS-1$
        }
        catch (Exception ignored) {}
    }

    /** Компактная строка прогресса сбора — грубый % по фронту BFS. */
    private static String formatCollectProgressStatus(int percent)
    {
        return "Подготовка индекса " + Math.max(0, Math.min(100, percent)) + "%"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Компактная строка прогресса поиска/фильтрации. */
    private static String formatSearchProgressStatus(int scanned, int total)
    {
        if (total <= 0)
            return "Поиск…"; //$NON-NLS-1$
        int percent = (int)Math.min(100L, Math.max(0L, scanned * 100L / total));
        return "Поиск… ~" + percent + "%"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static IEditorPart getEditorFromDialog(Object dialog)
    {
        Object controller = getField(dialog, "controller"); //$NON-NLS-1$
        if (controller == null) return null;

        IPartService ps = (IPartService)getField(controller, "partService"); //$NON-NLS-1$
        if (ps == null) return null;

        IWorkbenchPart part = ps.getActivePart();
        return (part instanceof IEditorPart) ? (IEditorPart)part : null;
    }

    private static AbstractTreeViewer getTreeViewerFromDialog(Object dialog)
    {
        IEditorPart editor = getEditorFromDialog(dialog);
        return editor != null ? getTreeViewerFromEditor(editor) : null;
    }

    private static AbstractTreeViewer getTreeViewerFromEditor(IEditorPart editor)
    {
        Object view = getField(editor, "comparisonView"); //$NON-NLS-1$
        if (!(view instanceof DtComparisonView)) return null;

        Object treeControl = ((DtComparisonView)view).getTreeControl();
        if (treeControl == null) return null;

        Object viewer = invokeNoArg(treeControl, "getTreeViewer"); //$NON-NLS-1$
        return (viewer instanceof AbstractTreeViewer) ? (AbstractTreeViewer)viewer : null;
    }

    private static void focusComparisonTree(IEditorPart editor)
    {
        AbstractTreeViewer viewer = getTreeViewerFromEditor(editor);
        if (viewer == null)
            return;
        Control control = viewer.getControl();
        if (control == null || control.isDisposed())
            return;
        Display display = control.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            if (control.isDisposed())
                return;
            try
            {
                editor.setFocus();
            }
            catch (Exception ignored) {}
            if (!control.isDisposed())
                control.setFocus();
        });
    }

    private static String getColumnHeader(IEditorPart editor, ComparisonSide side)
    {
        AbstractTreeViewer viewer = getTreeViewerFromEditor(editor);
        if (viewer == null) return null;
        Tree tree = (Tree) viewer.getControl();
        if (tree == null || tree.isDisposed()) return null;
        TreeColumn[] columns = tree.getColumns();
        int idx;
        switch (side)
        {
            case MAIN:
                idx = columns.length >= 2 ? 1 : -1;
                break;
            case OTHER:
                idx = columns.length >= 3 ? 2 : -1;
                break;
            case COMMON_ANCESTOR:
                idx = columns.length >= 4 ? 3 : -1;
                break;
            default:
                return null;
        }
        if (idx >= 0 && idx < columns.length)
        {
            String text = columns[idx].getText();
            if (text != null && !text.isEmpty()) return text;
        }
        return null;
    }

    private static String getObjectColumnHeader(IEditorPart editor)
    {
        AbstractTreeViewer viewer = getTreeViewerFromEditor(editor);
        if (viewer == null) return "\u041E\u0431\u044A\u0435\u043A\u0442";
        Tree tree = (Tree) viewer.getControl();
        if (tree == null || tree.isDisposed()) return "\u041E\u0431\u044A\u0435\u043A\u0442";
        TreeColumn[] columns = tree.getColumns();
        if (columns.length > 0)
        {
            String text = columns[0].getText();
            if (text != null && !text.isEmpty()) return text;
        }
        return "\u041E\u0431\u044A\u0435\u043A\u0442";
    }

    private static Object getField(Object obj, String name)
    {
        Class<?> cls = obj.getClass();
        while (cls != null)
        {
            try
            {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            }
            catch (NoSuchFieldException ignored) { cls = cls.getSuperclass(); }
            catch (Exception ignored) { return null; }
        }
        return null;
    }

    private static Object invokeNoArg(Object o, String name)
    {
        if (o == null) return null;
        try { return o.getClass().getMethod(name).invoke(o); }
        catch (Exception ignored) { return null; }
    }

    private static List<CompareSearchMatch> buildFindAllMatchesRange(
        List<Object> items,
        int from,
        int to,
        String effectiveQuery,
        boolean caseSensitive,
        boolean searchAllColumns,
        boolean wholeWord,
        String headerMain,
        String headerOther,
        String headerAncestor,
        String headerObject,
        int generation,
        IntSupplier activeGeneration,
        IProgressMonitor monitor,
        java.util.function.IntConsumer onScanned)
    {
        List<CompareSearchMatch> matches = new ArrayList<>();
        Map<Object, String> pathCache = new IdentityHashMap<>();
        List<ColumnHit> columnHits = new ArrayList<>(4);
        for (int i = from; i < to; i++)
        {
            if ((monitor != null && monitor.isCanceled()) || generation != activeGeneration.getAsInt())
                return matches;

            if (onScanned != null)
                onScanned.accept(i + 1);

            Object element = items.get(i);
            try
            {
                columnHits.clear();
                collectColumnHits(element, effectiveQuery, caseSensitive, searchAllColumns, wholeWord,
                    headerMain, headerOther, headerAncestor, headerObject, columnHits);

                if (!columnHits.isEmpty())
                {
                    String propertyName = extractNodeLabel(element);
                    Object parent = Global.invoke(element, "getParent"); //$NON-NLS-1$
                    String objectPath = getCachedObjectPath(parent, pathCache);
                    ComparisonStatusInfo statusInfo = computeComparisonStatusSafe(element);

                    for (ColumnHit hit : columnHits)
                    {
                        matches.add(new CompareSearchMatch(element, objectPath, propertyName,
                            hit.columnSide, hit.matchText, statusInfo.status, statusInfo.rowColorKind,
                            statusInfo.checkable));
                    }
                }
            }
            catch (Throwable t)
            {
                Global.logError("CompareSearch", "findAll: skipped node due to error", t); //$NON-NLS-1$
            }

            if (monitor != null)
                monitor.worked(1);
        }
        return matches;
    }

    private static void sortCompareSearchMatches(List<CompareSearchMatch> matches)
    {
        matches.sort((a, b) ->
        {
            String pathA = a.getObjectPath() != null ? a.getObjectPath() : ""; //$NON-NLS-1$
            String pathB = b.getObjectPath() != null ? b.getObjectPath() : ""; //$NON-NLS-1$
            int cmp = pathA.compareToIgnoreCase(pathB);
            if (cmp != 0)
                return cmp;
            String propA = a.getPropertyName() != null ? a.getPropertyName() : ""; //$NON-NLS-1$
            String propB = b.getPropertyName() != null ? b.getPropertyName() : ""; //$NON-NLS-1$
            return propA.compareToIgnoreCase(propB);
        });
    }

    /**
     * Фоновый прерываемый поиск по всему дереву сравнения (флажок «По всем строкам»).
     */
    private static final class CompareConfigSearchSession
    {
        private final Shell shell;
        private final Object dialog;
        private final Button btnNext;
        private final Button btnPrev;
        private final Button btnFindAll;
        private final Text textFilter;

        private volatile Job activeJob;
        private volatile Job findAllJob;
        private volatile Job prefetchJob;
        private volatile int scanned;
        private volatile int total;
        private final AtomicInteger collected = new AtomicInteger();
        private final AtomicInteger totalProcessed = new AtomicInteger();
        private final AtomicInteger activeSearchThreads = new AtomicInteger();
        private volatile boolean collecting;
        private volatile boolean running;
        private volatile boolean searchError;
        private int activeGeneration;
        private volatile int prefetchGeneration;
        private volatile Object prefetchInput;
        private volatile int prefetchFilterHash;
        private final AtomicInteger prefetchCollected = new AtomicInteger();
        private final AtomicInteger prefetchProcessed = new AtomicInteger();
        private final AtomicInteger prefetchThreads = new AtomicInteger();
        private final AtomicInteger prefetchProgressPercent = new AtomicInteger();
        private final AtomicInteger collectProgressPercent = new AtomicInteger();
        /** UI прогресса: ждём prefetch Job, а не свой collect. */
        private volatile boolean reportPrefetchProgress;
        private Runnable progressTick;

        private IEditorPart editorPart;

        private final String btnNextOriginalText;
        private final String btnPrevOriginalText;

        private String btnNextOriginal2;
        private String btnPrevOriginal2;
        private String btnFindAllOriginal2;

        private List<Object> cachedItems;
        private Object cachedInput;
        private int cachedFilterHash;

        CompareConfigSearchSession(Shell shell, Object dialog, Button btnNext, Button btnPrev, Button btnFindAll, Text textFilter, IEditorPart editor)
        {
            this.shell = shell;
            this.dialog = dialog;
            this.btnNext = btnNext;
            this.btnPrev = btnPrev;
            this.btnFindAll = btnFindAll;
            this.textFilter = textFilter;
            this.editorPart = editor;
            this.btnNextOriginalText = btnNext.getText();
            this.btnPrevOriginalText = btnPrev.getText();
        }

        void refreshSearchButtons()
        {
            if (running)
                return;
            boolean canSearch = editorPart != null && getTreeViewerFromEditor(editorPart) != null;
            boolean hasQuery = textFilter != null && !textFilter.isDisposed() && !textFilter.getText().isEmpty();

            if (btnNext != null && !btnNext.isDisposed())
                btnNext.setEnabled(canSearch);
            if (btnPrev != null && !btnPrev.isDisposed())
                btnPrev.setEnabled(canSearch);
            if (btnFindAll != null && !btnFindAll.isDisposed())
                btnFindAll.setEnabled(canSearch && hasQuery);
        }

        void focusComparisonTree()
        {
            CompareConfigSearchDialogHook.focusComparisonTree(editorPart);
        }

        boolean isRunning()
        {
            return running;
        }

        void onShellClose()
        {
            cancel();
            cancelPrefetch();
        }

        void cancel()
        {
            stopProgressTimer();
            Job job = activeJob;
            activeJob = null;
            if (job != null)
                job.cancel();
            Job jfa = findAllJob;
            findAllJob = null;
            if (jfa != null)
                jfa.cancel();
            if (running)
            {
                running = false;
                activeGeneration++;
            }
            reportPrefetchProgress = false;
            restoreSearchButtons();
        }

        private void cancelPrefetch()
        {
            prefetchGeneration++;
            Job job = prefetchJob;
            prefetchJob = null;
            prefetchInput = null;
            prefetchFilterHash = 0;
            if (job != null)
                job.cancel();
        }

        private void invalidateCache()
        {
            cachedItems = null;
            cachedInput = null;
            cachedFilterHash = 0;
        }

        private boolean isCacheValid(Object input, int filterHash)
        {
            return cachedItems != null && cachedInput == input && cachedFilterHash == filterHash;
        }

        private boolean tryAdoptEditorCache(Object input, int filterHash)
        {
            if (isCacheValid(input, filterHash))
                return true;
            if (editorPart == null)
                return false;
            SearchCache cached = searchCacheByEditor.get(editorPart);
            if (cached != null && cached.input == input && cached.filterHash == filterHash)
            {
                cachedInput = cached.input;
                cachedFilterHash = cached.filterHash;
                cachedItems = cached.items;
                return true;
            }
            return false;
        }

        private void saveCache(Object input, int filterHash, List<Object> items)
        {
            cachedInput = input;
            cachedFilterHash = filterHash;
            cachedItems = items;
            if (editorPart != null)
                searchCacheByEditor.put(editorPart, new SearchCache(items, input, filterHash));
        }

        /**
         * Фоновая сборка кэша узлов при открытии «Найти», чтобы Next/Prev не ждали полный collect.
         */
        void ensurePrefetch()
        {
            if (editorPart == null)
                return;
            AbstractTreeViewer viewer = getTreeViewerFromEditor(editorPart);
            if (viewer == null)
                return;
            IContentProvider cp = viewer.getContentProvider();
            if (!(cp instanceof ITreeContentProvider))
                return;
            ITreeContentProvider provider = (ITreeContentProvider)cp;
            Object input = viewer.getInput();
            if (input == null)
                return;
            int filterHash = computeTreeCacheKey(viewer, input);

            if (tryAdoptEditorCache(input, filterHash))
                return;

            Job existing = prefetchJob;
            if (existing != null && existing.getState() != Job.NONE
                && prefetchInput == input && prefetchFilterHash == filterHash)
            {
                return;
            }
            if (existing != null)
                cancelPrefetch();

            prefetchInput = input;
            prefetchFilterHash = filterHash;
            prefetchGeneration++;
            final int generation = prefetchGeneration;
            final AbstractTreeViewer treeViewer = viewer;
            final ITreeContentProvider treeProvider = provider;

            prefetchCollected.set(0);
            prefetchProcessed.set(0);
            prefetchThreads.set(0);
            prefetchProgressPercent.set(0);

            Job job = new Job("Подготовка индекса поиска по сравнению") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    try
                    {
                        if (generation != prefetchGeneration)
                            return Status.CANCEL_STATUS;

                        Object[] roots = treeProvider.getElements(input);
                        CollectTreeResult collectResult = collectTreeItems(roots, treeProvider, treeViewer,
                            generation, () -> prefetchGeneration, monitor, prefetchCollected,
                            prefetchProcessed, prefetchThreads, prefetchProgressPercent);
                        if (collectResult.cancelled || generation != prefetchGeneration
                            || (monitor != null && monitor.isCanceled()))
                            return Status.CANCEL_STATUS;

                        List<Object> items = collectResult.items;
                        Display display = Display.getDefault();
                        if (display == null || display.isDisposed())
                            return Status.CANCEL_STATUS;

                        boolean[] saved = { false };
                        display.syncExec(() ->
                        {
                            if (generation != prefetchGeneration || shell.isDisposed())
                                return;
                            AbstractTreeViewer current = getTreeViewerFromEditor(editorPart);
                            if (current == null)
                                return;
                            Object currentInput = current.getInput();
                            int currentHash = computeTreeCacheKey(current, currentInput);
                            if (currentInput != input || currentHash != filterHash)
                                return;
                            saveCache(input, filterHash, items);
                            saved[0] = true;
                        });

                        if (saved[0])
                            Global.log("CompareSearch", "prefetch cached " + items.size() + " items"); //$NON-NLS-1$ //$NON-NLS-2$
                        return saved[0] ? Status.OK_STATUS : Status.CANCEL_STATUS;
                    }
                    catch (Throwable t)
                    {
                        Global.logError("CompareSearch", "prefetch error", t); //$NON-NLS-1$
                        return Status.CANCEL_STATUS;
                    }
                    finally
                    {
                        if (prefetchJob == this)
                            prefetchJob = null;
                    }
                }
            };
            prefetchJob = job;
            job.setUser(true);
            job.setSystem(false);
            job.setPriority(Job.LONG);
            job.schedule();
            Global.log("CompareSearch", "prefetch started"); //$NON-NLS-1$
        }

        /**
         * Дождаться prefetch (если идёт) и подтянуть кэш. {@code false} — кэша нет, нужен свой collect.
         */
        private boolean awaitPrefetchAndAdopt(Object input, int filterHash, int generation,
            IProgressMonitor monitor)
        {
            if (tryAdoptEditorCache(input, filterHash))
                return true;

            Job pf = prefetchJob;
            if (pf == null)
                return tryAdoptEditorCache(input, filterHash);

            if (prefetchInput != input || prefetchFilterHash != filterHash)
                return tryAdoptEditorCache(input, filterHash);

            reportPrefetchProgress = true;
            try
            {
                while (pf.getState() != Job.NONE)
                {
                    if (generation != activeGeneration
                        || (monitor != null && monitor.isCanceled()))
                        return false;
                    pf.join(100, null);
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return false;
            }
            finally
            {
                reportPrefetchProgress = false;
            }

            return tryAdoptEditorCache(input, filterHash);
        }

        void startSearch(boolean backward, boolean searchAllColumns, boolean wholeWord)
        {
            cancel();
            activeGeneration++;

            Text textFilter = (Text)getField(dialog, "textFilter"); //$NON-NLS-1$
            if (textFilter == null || textFilter.isDisposed())
                return;

            String query = textFilter.getText();
            if (query.isEmpty())
                return;
            FilterHistoryStore.remember(COMPARE_SEARCH_HISTORY_SCOPE, query);

            Button cbCase = (Button)getField(dialog, "buttonCaseSensitive"); //$NON-NLS-1$
            boolean caseSensitive = cbCase != null && cbCase.getSelection();
            String effectiveQuery = caseSensitive ? query : query.toLowerCase();

            AbstractTreeViewer viewer = getTreeViewerFromEditor(editorPart);
            if (viewer == null)
                return;

            IContentProvider cp = viewer.getContentProvider();
            if (!(cp instanceof ITreeContentProvider))
                return;
            ITreeContentProvider provider = (ITreeContentProvider) cp;

            IBaseLabelProvider labelProvider = viewer.getLabelProvider();
            ISelection sel = viewer.getSelection();
            Object input = viewer.getInput();
            int filterHash = computeTreeCacheKey(viewer, input);
            boolean cacheHit = tryAdoptEditorCache(input, filterHash);

            final boolean hasCachedItems = cacheHit;

            final int generation = activeGeneration;
            running = true;
            collecting = false;
            searchError = false;
            scanned = 0;
            total = hasCachedItems ? cachedItems.size() : 0;
            collected.set(0);
            totalProcessed.set(0);
            activeSearchThreads.set(0);

            setSearchButtonsToCancelMode();
            startProgressTimer(generation);

            Global.log("CompareSearch", "start query=\"" + query + "\" backward=" + backward //$NON-NLS-1$ //$NON-NLS-2$
                    + " allColumns=" + searchAllColumns + " wholeWord=" + wholeWord //$NON-NLS-1$ //$NON-NLS-2$
                    + " cacheHit=" + cacheHit); //$NON-NLS-1$

            Object selectionElement = (sel instanceof IStructuredSelection && !sel.isEmpty())
                ? ((IStructuredSelection)sel).getFirstElement()
                : null;

            Job job = new Job("Поиск по дереву сравнения...") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    try
                    {
                        if (generation != activeGeneration)
                            return Status.CANCEL_STATUS;

                        if (hasCachedItems)
                        {
                            List<Object> items = cachedItems;
                            int n = items.size();
                            total = n;
                            scanned = 0;

                            if (n <= 0)
                            {
                                finishOnUi(generation, false, false);
                                return Status.OK_STATUS;
                            }

                            int startIdx = selectionElement != null
                                ? items.indexOf(selectionElement)
                                : -1;

                            monitor.beginTask("Поиск по дереву сравнения...", n); //$NON-NLS-1$

                            for (int i = 1; i <= n; i++)
                            {
                                if (monitor.isCanceled() || generation != activeGeneration)
                                    return finishCancelled(generation);

                                int idx = backward
                                    ? Math.floorMod(startIdx - i, n)
                                    : (startIdx + i) % n;
                                Object candidate = items.get(idx);

                                if (isMatched(candidate, effectiveQuery, caseSensitive, labelProvider,
                                        searchAllColumns, wholeWord))
                                {
                                    Object found = candidate;
                                    Display.getDefault().asyncExec(() ->
                                    {
                                        if (generation != activeGeneration || textFilter.isDisposed())
                                            return;
                                        viewer.setSelection(new StructuredSelection(found), true);
                                        finishOnUi(generation, false, true);
                                    });

                                    Global.log("CompareSearch", "found at i=" + i + " idx=" + idx); //$NON-NLS-1$ //$NON-NLS-2$

                                    monitor.done();
                                    return Status.OK_STATUS;
                                }

                                scanned = i;

                                if (i % 500 == 0)
                                    Global.log("CompareSearch", "progress " + i + "/" + n); //$NON-NLS-1$ //$NON-NLS-2$

                                monitor.worked(1);
                            }

                            Global.log("CompareSearch", "no match after " + n + " items"); //$NON-NLS-1$

                            finishOnUi(generation, false, false);
                            monitor.done();
                            return Status.OK_STATUS;
                        }

                        // Cache miss: streaming, без второго collect (prefetch достроит кэш сам)
                        Object[] roots = provider.getElements(input);
                        Global.log("CompareSearch", "stream search (cache miss)"); //$NON-NLS-1$

                        StreamSearchResult streamResult = streamSearchFirstMatch(
                            roots, provider, viewer, selectionElement, backward,
                            effectiveQuery, caseSensitive, labelProvider, searchAllColumns, wholeWord,
                            generation, () -> activeGeneration, monitor, v -> scanned = v);

                        scanned = streamResult.scanned;
                        if (streamResult.cancelled || generation != activeGeneration)
                            return finishCancelled(generation);

                        if (streamResult.match != null)
                        {
                            Object found = streamResult.match;
                            Display.getDefault().asyncExec(() ->
                            {
                                if (generation != activeGeneration || textFilter.isDisposed())
                                    return;
                                viewer.setSelection(new StructuredSelection(found), true);
                                finishOnUi(generation, false, true);
                            });
                            Global.log("CompareSearch", "stream found after " + streamResult.scanned); //$NON-NLS-1$
                            return Status.OK_STATUS;
                        }

                        Global.log("CompareSearch", "stream no match after " + streamResult.scanned); //$NON-NLS-1$
                        finishOnUi(generation, false, false);
                        return Status.OK_STATUS;
                    }
                    catch (Throwable t)
                    {
                        searchError = true;
                        Global.logError("CompareSearch", "error", t); //$NON-NLS-1$
                        return finishCancelled(generation);
                    }
                }
            };
            activeJob = job;
            job.setSystem(true);
            job.schedule();
        }

        void findAndShowAll(boolean searchAllColumns, boolean wholeWord)
        {
            cancel();

            Text textFilter = (Text)getField(dialog, "textFilter"); //$NON-NLS-1$
            if (textFilter == null || textFilter.isDisposed())
                return;
            String query = textFilter.getText();
            if (query.isEmpty())
                return;
            FilterHistoryStore.remember(COMPARE_SEARCH_HISTORY_SCOPE, query);

            Button cbCase = (Button)getField(dialog, "buttonCaseSensitive"); //$NON-NLS-1$
            boolean caseSensitive = cbCase != null && cbCase.getSelection();

            if (editorPart == null)
                return;

            AbstractTreeViewer viewer = getTreeViewerFromEditor(editorPart);
            if (viewer == null)
                return;

            IContentProvider cp = viewer.getContentProvider();
            if (!(cp instanceof ITreeContentProvider))
                return;
            ITreeContentProvider provider = (ITreeContentProvider)cp;

            Object input = viewer.getInput();
            int filterHash = computeTreeCacheKey(viewer, input);

            String headerMain = getColumnHeader(editorPart, ComparisonSide.MAIN);
            String headerOther = getColumnHeader(editorPart, ComparisonSide.OTHER);
            String headerAncestor = getColumnHeader(editorPart, ComparisonSide.COMMON_ANCESTOR);
            String headerObject = getObjectColumnHeader(editorPart);

            boolean cacheHit = tryAdoptEditorCache(input, filterHash);

            final boolean hasCachedItems = cacheHit;
            final String effectiveQuery = caseSensitive ? query : query.toLowerCase();

            activeGeneration++;
            final int generation = activeGeneration;
            running = true;
            collecting = !hasCachedItems;
            searchError = false;
            scanned = 0;
            total = hasCachedItems ? cachedItems.size() : 0;
            collected.set(0);
            totalProcessed.set(0);
            activeSearchThreads.set(0);
            collectProgressPercent.set(0);
            Job pfEarly = prefetchJob;
            reportPrefetchProgress = !hasCachedItems
                && pfEarly != null && pfEarly.getState() != Job.NONE
                && prefetchInput == input && prefetchFilterHash == filterHash;
            setSearchButtonsToCancelMode();
            startProgressTimer(generation);

            Job job = new Job("Поиск по дереву сравнения...") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    try
                    {
                        if (generation != activeGeneration)
                            return Status.CANCEL_STATUS;

                        List<Object> items;
                        if (hasCachedItems)
                        {
                            items = cachedItems;
                        }
                        else if (awaitPrefetchAndAdopt(input, filterHash, generation, monitor))
                        {
                            collecting = false;
                            items = cachedItems;
                        }
                        else
                        {
                            if (generation != activeGeneration
                                || (monitor != null && monitor.isCanceled()))
                                return Status.CANCEL_STATUS;

                            collecting = true;
                            collected.set(0);
                            totalProcessed.set(0);
                            collectProgressPercent.set(0);

                            Object[] roots = provider.getElements(input);
                            CollectTreeResult collectResult = collectTreeItems(roots, provider, viewer,
                                generation, () -> activeGeneration, monitor, collected, totalProcessed,
                                activeSearchThreads, collectProgressPercent);
                            items = collectResult.items;
                            if (collectResult.cancelled)
                                return Status.CANCEL_STATUS;

                            saveCache(input, filterHash, items);
                            collecting = false;
                        }

                        int n = items.size();
                        total = n;
                        scanned = 0;
                        monitor.beginTask("Фильтрация...", n); //$NON-NLS-1$

                        List<CompareSearchMatch> matches = buildFindAllMatches(items, effectiveQuery,
                            caseSensitive, searchAllColumns, wholeWord, headerMain, headerOther,
                            headerAncestor, headerObject, generation, monitor);

                        if (generation != activeGeneration)
                            return Status.CANCEL_STATUS;

                        Display.getDefault().asyncExec(() ->
                        {
                            if (generation != activeGeneration)
                                return;
                            showFindAllResultsUI(matches, query);
                        });

                        monitor.done();
                        return Status.OK_STATUS;
                    }
                    catch (Throwable t)
                    {
                        Global.logError("CompareSearch", "findAll error", t); //$NON-NLS-1$
                        searchError = true;
                        Display.getDefault().asyncExec(() ->
                        {
                            if (generation != activeGeneration)
                                return;
                            stopProgressTimer();
                            findAllJob = null;
                            running = false;
                            restoreSearchButtons();
                            showFindAllResultsUI(Collections.emptyList(), query);
                        });
                        return Status.CANCEL_STATUS;
                    }
                }
            };
            findAllJob = job;
            job.setSystem(true);
            job.schedule();
        }

        private List<CompareSearchMatch> buildFindAllMatches(List<Object> items, String effectiveQuery,
            boolean caseSensitive, boolean searchAllColumns, boolean wholeWord,
            String headerMain, String headerOther, String headerAncestor, String headerObject,
            int generation, IProgressMonitor monitor)
        {
            int n = items.size();
            if (n <= 0)
                return new ArrayList<>();
            return buildFindAllMatchesRange(items, 0, n, effectiveQuery, caseSensitive, searchAllColumns,
                wholeWord, headerMain, headerOther, headerAncestor, headerObject, generation,
                () -> activeGeneration, monitor, idx -> scanned = idx);
        }

        private void showFindAllResultsUI(List<CompareSearchMatch> matches, String query)
        {
            stopProgressTimer();
            findAllJob = null;
            running = false;
            restoreSearchButtons();
            if (!matches.isEmpty())
            {
                setStatus(dialog, "Найдено: " + matches.size()); //$NON-NLS-1$
                doShowFindAllResults(matches, query);
            }
            else if (searchError)
                setStatus(dialog, "Поиск прерван"); //$NON-NLS-1$
            else
                setStatus(dialog, "Не найдено"); //$NON-NLS-1$
        }

        private void doShowFindAllResults(List<CompareSearchMatch> matches, String query)
        {
            sortCompareSearchMatches(matches);
            CompareSearchResult result = new CompareSearchResult(matches, editorPart);
            result.setQueryText(query);
            CompareSearchQuery searchQuery = new CompareSearchQuery();
            result.setQuery(searchQuery);
            searchQuery.setSearchResult(result);
            NewSearchUI.runQueryInBackground(searchQuery);
        }

        private List<Object> collectAllItems(ITreeContentProvider provider, Object input, AbstractTreeViewer viewer)
        {
            List<Object> items = new ArrayList<>();
            Object[] roots = provider.getElements(input);
            if (roots != null)
            {
                for (Object root : roots)
                {
                    items.add(root);
                    collectModelItems(root, provider, items, viewer, null);
                }
            }
            return items;
        }

        private IStatus finishCancelled(int generation)
        {
            Global.log("CompareSearch", "cancelled"); //$NON-NLS-1$
            finishOnUi(generation, true, false);
            return Status.CANCEL_STATUS;
        }

        private void finishOnUi(int generation, boolean cancelled, boolean found)
        {
            Display display = shell != null && !shell.isDisposed() ? shell.getDisplay() : null;
            if (display == null || display.isDisposed())
            {
                running = false;
                activeJob = null;
                stopProgressTimer();
                return;
            }
            display.asyncExec(() ->
            {
                if (generation != activeGeneration)
                    return;
                stopProgressTimer();
                activeJob = null;
                running = false;
                if (shell.isDisposed())
                    return;
                if (cancelled)
                {
                    if (searchError)
                        setStatus(dialog, "Ошибка (журнал Комфорт)"); //$NON-NLS-1$
                    else
                        clearStatus(dialog);
                }
                else if (found)
                    setStatus(dialog, "Найдено"); //$NON-NLS-1$
                else
                    setStatus(dialog, "Не найдено"); //$NON-NLS-1$
                restoreSearchButtons();
                updateDialog(dialog);
                refreshSearchButtons();
            });
        }

        private void setSearchButtonsToCancelMode()
        {
            if (btnNext != null && !btnNext.isDisposed())
            {
                btnNextOriginal2 = btnNext.getText();
                btnNext.setText("Отмена");
                btnNext.setEnabled(true);
            }
            if (btnPrev != null && !btnPrev.isDisposed())
            {
                btnPrevOriginal2 = btnPrev.getText();
                btnPrev.setText("Отмена");
                btnPrev.setEnabled(true);
            }
            if (btnFindAll != null && !btnFindAll.isDisposed())
            {
                btnFindAllOriginal2 = btnFindAll.getText();
                btnFindAll.setText("Отмена");
                btnFindAll.setEnabled(true);
            }
        }

        private void restoreSearchButtons()
        {
            if (btnNext != null && !btnNext.isDisposed())
                btnNext.setText(btnNextOriginal2 != null ? btnNextOriginal2 : btnNextOriginalText);
            if (btnPrev != null && !btnPrev.isDisposed())
                btnPrev.setText(btnPrevOriginal2 != null ? btnPrevOriginal2 : btnPrevOriginalText);
            if (btnFindAll != null && !btnFindAll.isDisposed())
                btnFindAll.setText(btnFindAllOriginal2 != null ? btnFindAllOriginal2 : "Найти все"); //$NON-NLS-1$
            refreshSearchButtons();
        }

        private void startProgressTimer(int generation)
        {
            stopProgressTimer();
            Display display = shell.getDisplay();
            progressTick = () ->
            {
                if (generation != activeGeneration || !running || shell.isDisposed())
                    return;
                if (collecting)
                {
                    if (reportPrefetchProgress)
                        setStatus(dialog, formatCollectProgressStatus(prefetchProgressPercent.get()));
                    else
                        setStatus(dialog, formatCollectProgressStatus(collectProgressPercent.get()));
                }
                else
                    setStatus(dialog, formatSearchProgressStatus(scanned, total));
                if (running && generation == activeGeneration && !shell.isDisposed())
                    display.timerExec(PROGRESS_INTERVAL_MS, progressTick);
            };
            display.timerExec(PROGRESS_INTERVAL_MS, progressTick);
        }

        private void stopProgressTimer()
        {
            if (progressTick == null)
                return;
            Display display = shell != null && !shell.isDisposed() ? shell.getDisplay() : null;
            if (display != null && !display.isDisposed())
                display.timerExec(-1, progressTick);
            progressTick = null;
        }
    }
}
