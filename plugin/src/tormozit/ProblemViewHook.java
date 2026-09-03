package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.descriptor.basic.MPartDescriptor;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IOpenListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.dialogs.PreferencesUtil;

import com._1c.g5.v8.dt.validation.marker.IMarkerInfo;
import com._1c.g5.v8.dt.validation.marker.IMarkerUpdateListener;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.MarkersChangedEvent;
import com._1c.g5.v8.dt.validation.marker.v2.IMarkerManagerV2;
import com._1c.g5.v8.dt.validation.marker.v2.IMarkerReader;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/**
 * Панель проблем конфигурации ({@code com._1c.g5.v8.dt.ui.problemView}), issue 401.
 *
 * <ul>
 * <li><b>Заголовок.</b> «Ошибки конфигурации» → «Проблемы конфигурации»: панель
 * показывает не только ошибки, но и предупреждения, а «ошибка конфигурации» —
 * это отдельный вид проблемы (он же отдельный флажок в «Настройках отбора», см.
 * {@link ProblemFiltersDialogHook}).</li>
 * <li><b>Двойной щелчок в колонке «Код проверки»</b> открывает настройку этой
 * проверки на странице «Проверки» параметров проекта — вместо перехода к самой
 * проблеме, который остаётся на всех остальных колонках. Штатное открытие
 * редактора для этой колонки подавляется, иначе поверх настроек открывался бы
 * ещё и редактор объекта.</li>
 * </ul>
 *
 * <p>То же открытие настройки ({@link #openCheckSettings}) переиспользуют команда
 * «Открыть настройку проверки» в тулбаре и контекстном меню панели
 * ({@code ProblemViewOpenCheckSettingsHandler}) и кнопка в подсказке предупреждения
 * в редакторе модуля ({@code BslCheckSettingsHoverContributor}).</p>
 *
 * <p>Отдельный файл, а не вложенный класс: точка входа из {@code plugin.xml}.
 */
public final class ProblemViewHook implements IStartup
{
    /** Страница «Проверки» в свойствах проекта ({@code ValidationPreferencePage.PROPERTIES_PAGE_ID}). */
    private static final String CHECKS_PAGE_ID = "com.e1c.g5.v8.dt.checks.properties"; //$NON-NLS-1$
    /** Ключ {@code ValidationPreferencePage.DATA_PROPERTY_CHECK_ID}: короткий код проверки. */
    private static final String CHECK_ID_DATA_KEY = "ValidationPreferencePage.checkId"; //$NON-NLS-1$
    private static final String CODE_COLUMN_TITLE = "Код проверки"; //$NON-NLS-1$
    private static final String VIEW_TITLE = "Проблемы конфигурации"; //$NON-NLS-1$

    private static final String SCOPE_LABEL_KEY = "tormozit.problemViewScopeLabel"; //$NON-NLS-1$
    private static final String OPEN_OVERRIDE_KEY = "tormozit.problemViewOpenOverride"; //$NON-NLS-1$
    /** Отделяет дописанный отбор от штатных итогов — он же признак «уже дописано». */
    private static final String SCOPE_SEPARATOR = "   │   "; //$NON-NLS-1$
    private static final int SCOPE_REFRESH_MS = 300;

    /** Заслонки обновления по панелям — см. {@link #installResultChangeGate(IViewPart)}. */
    private static final Map<IViewPart, ResultChangeGate> gates = new WeakHashMap<>();
    private static final String MESSAGES_CLASS = "com._1c.g5.v8.dt.internal.ui.validation.Messages"; //$NON-NLS-1$
    private static final String PLUGIN_CLASS =
        "com._1c.g5.v8.dt.internal.ui.validation.V8UiValidationPlugin"; //$NON-NLS-1$
    private static final String CHANGE_LISTENER_CLASS =
        "com._1c.g5.v8.dt.internal.ui.validation.AbstractSetting$ChangeListener"; //$NON-NLS-1$
    /**
     * Своё короткое название вместо штатного {@code Messages.Scope} («Область
     * возникновения»): к значению режима дописывается ещё и полное имя объекта,
     * и длинный штатный заголовок в одной строке с итогами уже мешает.
     */
    private static final String SCOPE_TITLE = "Область"; //$NON-NLS-1$
    /** Режимы {@code ProblemFilters.Scope}, у которых есть конкретный источник отбора. */
    private static final String SCOPE_CURRENT_OBJECT = "CURRENT_OBJECT"; //$NON-NLS-1$
    private static final String SCOPE_CURRENT_ELEMENT = "CURRENT_ELEMENT"; //$NON-NLS-1$
    private static final String SCOPE_CURRENT_PROJECT = "CURRENT_PROJECT"; //$NON-NLS-1$
    /** Режим без отбора по области — им же подписывается включённое «Показывать все». */
    private static final String SCOPE_ALL = "ALL"; //$NON-NLS-1$

    private static final String SCOPE_SELECTION_CLASS =
        "com._1c.g5.v8.dt.internal.ui.validation.ScopeSelection"; //$NON-NLS-1$
    private static final String SCOPE_ENUM_CLASS =
        "com._1c.g5.v8.dt.internal.ui.validation.ProblemFilters$Scope"; //$NON-NLS-1$
    /** Штатная команда радио «Области возникновения» (тулбар-пулдаун / меню панели). */
    private static final String NATIVE_SCOPE_COMMAND_ID =
        "com._1c.g5.v8.dt.ui.command.filtersScopeRadio"; //$NON-NLS-1$
    /** Команда включения/выключения фильтра по подсистемам в навигаторе. */
    private static final String NAVIGATOR_SUBSYSTEMS_FILTER_COMMAND_ID =
        "com._1c.g5.v8.dt.navigator.ui.filterBySubsystems"; //$NON-NLS-1$
    /** Период «дешёвой» перепроверки, что панель не сбросила нашу синтетическую область. */
    private static final int COMFORT_REASSERT_MS = 3000;

    /** Установлен ли уже {@link #installComfortScope} для панели. */
    private static final Map<IViewPart, Boolean> comfortInstalled = new WeakHashMap<>();
    /** Синтетическая {@code ScopeSelection}, наложенная нами на панель (для сверки). */
    private static final Map<IViewPart, Object> comfortSyntheticSelection = new WeakHashMap<>();
    /** Имя штатной области ({@code CURRENT_ELEMENT}/{@code CURRENT_PROJECT}/{@code ALL}), которую мы выставили. */
    private static final Map<IViewPart, String> comfortAppliedScope = new WeakHashMap<>();
    /** Проект, под который наложена область (для сверки со сменой активного проекта). */
    private static final Map<IViewPart, IProject> comfortAppliedProject = new WeakHashMap<>();

    private static volatile boolean installed;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(ProblemViewHook::install);
    }

    private static void install()
    {
        if (installed)
            return;
        installed = true;

        IWorkbench workbench = PlatformUI.getWorkbench();
        applyDescriptorTitle(workbench);
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            hookWindow(window);
        workbench.addWindowListener(new IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override
            public void windowActivated(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override
            public void windowDeactivated(IWorkbenchWindow window)
            {
            }

            @Override
            public void windowClosed(IWorkbenchWindow window)
            {
            }
        });

        Debug.log("install: installed"); //$NON-NLS-1$
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        for (IWorkbenchPage page : window.getPages())
        {
            for (IViewReference ref : page.getViewReferences())
                applyTitle(ref.getView(false));
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                applyTitle(partOf(ref));
            }

            @Override
            public void partVisible(IWorkbenchPartReference ref)
            {
                applyTitle(partOf(ref));
            }
        });
    }

    private static IWorkbenchPart partOf(IWorkbenchPartReference ref)
    {
        return ref != null ? ref.getPart(false) : null;
    }

    /**
     * То же имя — в списке окна «Показать панель» (и в быстром доступе).
     *
     * <p>Вкладка открытой панели берёт имя из {@code MPart} (см. {@link #applyTitle}), а список
     * представлений строится не по открытым панелям, а по дескрипторам модели e4
     * ({@code MApplication.getDescriptors()}, {@code ShowViewDialog}). Пока переименован только
     * {@code MPart}, в списке остаётся штатное «Ошибки конфигурации» — одна и та же панель
     * называется по-разному в двух местах интерфейса.
     *
     * <p>Дескрипторы собираются из реестра расширений при каждом запуске, поэтому подпись
     * ставится заново на старте и никуда не сохраняется.
     */
    private static void applyDescriptorTitle(IWorkbench workbench)
    {
        MApplication application = workbench.getService(MApplication.class);
        if (application == null)
            return;
        for (MPartDescriptor descriptor : application.getDescriptors())
        {
            if (ProblemViewMarkers.PROBLEM_VIEW_ID.equals(descriptor.getElementId())
                && !VIEW_TITLE.equals(descriptor.getLabel()))
            {
                descriptor.setLabel(VIEW_TITLE);
                Debug.log("applyDescriptorTitle: renamed"); //$NON-NLS-1$
            }
        }
    }

    /**
     * Имя панели живёт в модели e4, а не в самом {@code IViewPart}: штатный
     * {@code setPartName} у чужой панели недоступен, зато {@code MPart.setLabel}
     * меняет и заголовок вкладки, и подпись в списке представлений.
     */
    private static void applyTitle(IWorkbenchPart part)
    {
        if (!(part instanceof IViewPart view) || !isProblemView(view))
            return;
        Object mpart = view.getSite().getService(MPart.class);
        if (mpart instanceof MPart model && !VIEW_TITLE.equals(model.getLabel()))
        {
            model.setLabel(VIEW_TITLE);
            Debug.log("applyTitle: renamed"); //$NON-NLS-1$
        }
        installResultChangeGate(view);
        installScopeLabel(view);
        installOpenOverride(view);
        installComfortScope(view);
        refreshComfortScope(view);
    }

    /**
     * Панель обновляется, только когда изменился её собственный результат.
     *
     * <p>Штатно панель слушает изменения маркеров <b>всего проекта</b>
     * ({@code LazyProblemView.updateListener} у {@code IMarkerManagerV2}) и на каждое такое
     * событие перестраивает дерево и переписывает надпись с итогами. Пока по конфигурации идёт
     * проверка, коммиттер маркеров рассылает событие примерно раз в полторы секунды, и панель
     * моргает целиком, даже если под текущим отбором ничего не поменялось: дерево перерисовывается,
     * а надпись на миг теряет дописанное «Область: …».
     *
     * <p>Штатный слушатель подменяется обёрткой: на каждое событие считается отпечаток результата
     * под текущим отбором панели (количество и сумма хэшей маркеров) и событие пропускается дальше,
     * только если отпечаток изменился. Отбор берётся из {@code getMarkerFilter()} — публичного
     * метода панели, но требующего UI-потока, поэтому он снимается заранее и кэшируется.
     * Сомнение всегда трактуется в пользу обновления: нет кэша отбора, слишком много маркеров,
     * любая ошибка — событие проходит, то есть остаётся штатное поведение.
     */
    private static void installResultChangeGate(IViewPart view)
    {
        try
        {
            if (gates.containsKey(view))
                return;
            Object manager = Global.getField(view, "markerManager"); //$NON-NLS-1$
            Object listener = Global.getField(view, "updateListener"); //$NON-NLS-1$
            if (!(manager instanceof IMarkerManagerV2 markerManager)
                || !(listener instanceof IMarkerUpdateListener stock))
            {
                Debug.log("installResultChangeGate: fields not found"); //$NON-NLS-1$
                return;
            }
            ResultChangeGate gate = new ResultChangeGate(view, markerManager, stock);
            gates.put(view, gate);
            markerManager.removeListener(stock);
            markerManager.addListener(gate);
            gate.refreshFilterSnapshot();
            Debug.log("installResultChangeGate: installed"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            Debug.log("installResultChangeGate: " + e); //$NON-NLS-1$
        }
    }

    /** См. {@link #installResultChangeGate(IViewPart)}. */
    private static final class ResultChangeGate implements IMarkerUpdateListener
    {
        /** Столько маркеров под отбором ещё считаем: выше — обновляем панель без сверки. */
        private static final int DIGEST_LIMIT = 5000;

        /** Пауза перед сверкой: события коммиттера идут пачками, считать на каждое незачем. */
        private static final int EVALUATE_DELAY_MS = 100;

        private final IViewPart view;

        private final IMarkerManagerV2 markerManager;

        private final IMarkerUpdateListener stock;

        /** Отбор панели, снятый в UI-потоке: в потоке события его строить нельзя. */
        private volatile MarkerFilter filterSnapshot;

        private volatile String lastDigest;

        /** Последнее событие, ожидающее сверки. */
        private volatile MarkersChangedEvent pending;

        /** Сверка идёт в своей задаче: событие приходит в потоке коммиттера маркеров. */
        private final Job evaluateJob;

        ResultChangeGate(IViewPart view, IMarkerManagerV2 markerManager, IMarkerUpdateListener stock)
        {
            this.view = view;
            this.markerManager = markerManager;
            this.stock = stock;
            // Приведение обязательно: Job.create перегружен под ICoreRunnable и IJobFunction,
            // а лямбда без результата подходит обеим
            this.evaluateJob = Job.create("Комфорт: сверка результата панели проблем", //$NON-NLS-1$
                (ICoreRunnable)monitor -> evaluate());
            this.evaluateJob.setSystem(true);
        }

        @Override
        public void handleMarkersChanged(MarkersChangedEvent event)
        {
            // Считать отпечаток прямо здесь нельзя: это поток коммиттера маркеров, он в этот
            // момент держит хранилище — читать его отсюда и задерживать коммит одинаково плохо
            pending = event;
            evaluateJob.cancel();
            evaluateJob.schedule(EVALUATE_DELAY_MS);
        }

        private void evaluate()
        {
            MarkersChangedEvent event = pending;
            if (event == null)
                return;
            String digest = digest(event);
            if (digest != null && digest.equals(lastDigest))
                return;
            lastDigest = digest;
            stock.handleMarkersChanged(event);
            // Отбор мог измениться вместе с результатом (например, сменился текущий объект)
            refreshFilterSnapshot();
        }

        /**
         * Отпечаток результата под текущим отбором: количество маркеров и сумма их хэшей.
         * Сумма не зависит от порядка выдачи, а {@code Marker} переопределяет {@code hashCode}.
         *
         * @return {@code null}, если отпечаток посчитать нельзя — тогда событие проходит дальше
         */
        private String digest(MarkersChangedEvent event)
        {
            MarkerFilter filter = filterSnapshot;
            if (filter == null || event == null)
                return null;
            try
            {
                IMarkerReader reader = markerManager.createReader(projects(event));
                IMarkerInfo info = reader.getMarkerInfo(filter);
                int total = info == null ? -1 : info.getTotalCount();
                if (total < 0 || total > DIGEST_LIMIT)
                    return null;
                long sum = reader.markers(filter).mapToLong(Marker::hashCode).sum();
                return total + ":" + sum; //$NON-NLS-1$
            }
            catch (RuntimeException e)
            {
                Debug.log("resultChangeGate: отпечаток не посчитан: " + e); //$NON-NLS-1$
                return null;
            }
        }

        private Collection<IProject> projects(MarkersChangedEvent event)
        {
            Collection<IProject> changed = event.getChangedProjects();
            return changed == null ? Set.of() : changed;
        }

        /** Снимает текущий отбор панели в UI-потоке — там его строить безопасно. */
        void refreshFilterSnapshot()
        {
            Display.getDefault().asyncExec(() ->
            {
                try
                {
                    if (view.getSite() == null)
                        return;
                    Object filter = Global.invoke(view, "getMarkerFilter"); //$NON-NLS-1$
                    if (filter instanceof MarkerFilter markerFilter)
                        filterSnapshot = markerFilter;
                }
                catch (RuntimeException e)
                {
                    Debug.log("resultChangeGate: отбор не снят: " + e); //$NON-NLS-1$
                    filterSnapshot = null;
                }
            });
        }
    }

    /**
     * Подпись «Область: …» в строке над деревом, сразу за итогами по видам
     * проблем (issue 401). Для режимов «Текущий проект», «Текущий объект» и
     * «Текущий элемент» дописывается ещё и сам источник отбора (имя проекта,
     * полное имя объекта или элемента) — иначе непонятно, чей это список.
     *
     * <p>При включённом «Показывать все» отбор по области не применяется вовсе,
     * поэтому подпись показывает «Все проекты», а не выбранный в настройках режим.
     *
     * <p>Область возникновения — самый «дорогой» отбор панели: он один способен
     * убрать из списка почти всё. Штатно он виден только внутри окна «Настройки
     * отбора», поэтому пустой список легко принять за отсутствие проблем.
     *
     * <p>Текст дописывается в саму штатную надпись с итогами. Панель переписывает
     * её при каждом обновлении маркеров и о своих записях никак не сообщает
     * ({@code Label} события смены текста не шлёт), поэтому дополнение
     * восстанавливается по таймеру: сравнивается только строка, и лишь при
     * расхождении вызывается {@code setText}.
     */
    private static void installScopeLabel(IViewPart view)
    {
        Object statusObj = Global.getField(view, "statusLabel"); //$NON-NLS-1$
        if (!(statusObj instanceof Label status) || status.isDisposed())
        {
            Debug.log("installScopeLabel: statusLabel not found"); //$NON-NLS-1$
            return;
        }
        if (Boolean.TRUE.equals(status.getData(SCOPE_LABEL_KEY)))
            return;
        status.setData(SCOPE_LABEL_KEY, Boolean.TRUE);

        ClassLoader loader = view.getClass().getClassLoader();
        Object filters = problemFilters(loader);
        appendScope(view, status, filters, loader);
        listenScopeChanges(view, status, filters, loader);
        keepScopeAppended(view, status, filters, loader);
        Debug.log("installScopeLabel: installed"); //$NON-NLS-1$
    }

    /**
     * Двойной щелчок в колонке «Код проверки» открывает настройку проверки, а
     * не редактор объекта проблемы (issue 401).
     *
     * <p>Штатное открытие делает {@code OpenAndLinkWithEditorHelper$InternalListener},
     * зарегистрированный у {@code TreeViewer} панели как {@link IOpenListener}
     * (срабатывает по {@code SWT.DefaultSelection}, не по {@code MouseDoubleClick} —
     * поэтому фильтром {@code Display} его не перехватить). Снимаем этот listener
     * штатным {@code removeOpenListener} и ставим свой: для колонки «Код проверки» —
     * настройка проверки, для остальных колонок — делегирование снятому listener'у,
     * то есть прежнее поведение. «Связь с редактором» живёт в отдельном
     * {@code selectionChanged} того же объекта и не затрагивается.
     */
    private static void installOpenOverride(IViewPart view)
    {
        if (!(view.getAdapter(TreeViewer.class) instanceof TreeViewer viewer))
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed() || Boolean.TRUE.equals(tree.getData(OPEN_OVERRIDE_KEY)))
            return;

        IOpenListener stock = findStockOpenListener(viewer);
        if (stock == null)
        {
            Debug.log("installOpenOverride: stock open listener not found"); //$NON-NLS-1$
            return;
        }
        tree.setData(OPEN_OVERRIDE_KEY, Boolean.TRUE);

        int[] lastColumn = { -1 };
        tree.addListener(SWT.MouseDown, ev -> lastColumn[0] = columnIndexAt(tree, ev.x, ev.y));
        // Навигация клавишами уводит от «колонки последнего клика» — тогда Enter
        // должен открывать объект штатно.
        tree.addListener(SWT.KeyDown, ev -> lastColumn[0] = -1);

        viewer.removeOpenListener(stock);
        viewer.addOpenListener(event ->
        {
            int index = lastColumn[0];
            Marker marker = firstMarker(event.getSelection());
            if (marker != null && index >= 0 && index < tree.getColumnCount()
                && CODE_COLUMN_TITLE.equals(columnTitle(tree, index)))
            {
                openCheckSettings(tree.getShell(), marker);
                return;
            }
            stock.open(event);
        });
        tree.addDisposeListener(e -> tree.setData(OPEN_OVERRIDE_KEY, null));
        Debug.log("installOpenOverride: installed"); //$NON-NLS-1$
    }

    private static IOpenListener findStockOpenListener(TreeViewer viewer)
    {
        Object listenerList = Global.getField(viewer, "openListeners"); //$NON-NLS-1$
        Object raw = listenerList != null ? Global.invoke(listenerList, "getListeners") : null; //$NON-NLS-1$
        if (!(raw instanceof Object[] listeners))
            return null;
        for (Object listener : listeners)
        {
            if (listener instanceof IOpenListener open
                && "org.eclipse.ui.OpenAndLinkWithEditorHelper$InternalListener".equals(listener.getClass().getName())) //$NON-NLS-1$
                return open;
        }
        return null;
    }

    private static int columnIndexAt(Tree tree, int x, int y)
    {
        TreeItem item = tree.getItem(new Point(x, y));
        if (item == null)
            return -1;
        for (int i = 0; i < tree.getColumnCount(); i++)
        {
            if (item.getBounds(i).contains(x, y))
                return i;
        }
        return -1;
    }

    private static String columnTitle(Tree tree, int index)
    {
        TreeColumn column = tree.getColumn(index);
        return column != null ? column.getText() : null;
    }

    private static Marker firstMarker(ISelection selection)
    {
        if (!(selection instanceof IStructuredSelection structured))
            return null;
        return markerOf(structured.getFirstElement());
    }

    /** Дописывает отбор к штатным итогам, заменяя ранее дописанное. */
    private static void appendScope(IViewPart view, Label status, Object filters, ClassLoader loader)
    {
        if (status.isDisposed())
            return;
        String suffix = scopeSuffix(view, filters, loader);
        if (suffix == null)
            return;
        String text = status.getText();
        int appended = text.indexOf(SCOPE_SEPARATOR);
        String wanted = (appended >= 0 ? text.substring(0, appended) : text) + suffix;
        if (wanted.equals(text))
            return;
        status.setText(wanted);
        // Смена подписи означает и смену отбора (например, выбрали другой объект):
        // отпечаток результата надо считать уже по новому отбору
        ResultChangeGate gate = gates.get(view);
        if (gate != null)
            gate.refreshFilterSnapshot();
    }

    private static String scopeSuffix(IViewPart view, Object filters, ClassLoader loader)
    {
        // Наши варианты области не относятся к штатному enum: под ними панель
        // работает в режиме «Текущий элемент»/«Текущий проект», но показывать надо
        // выбранный вариант — с именем проекта (и набора).
        String comfortLabel = ComfortSettings.isReplaceListFiltersEnabled() ? comfortScopeLabel(view) : null;
        if (comfortLabel != null && !Boolean.TRUE.equals(Global.invoke(filters, "isShowAll"))) //$NON-NLS-1$
            return SCOPE_SEPARATOR + SCOPE_TITLE + ": " + comfortLabel; //$NON-NLS-1$

        // При «Показывать все» панель полностью пропускает отбор по области
        // (LazyProblemView: isShowAll() -> область не применяется), поэтому
        // выбранный в настройках режим показывать нельзя — он ничего не отбирает.
        boolean showAll = Boolean.TRUE.equals(Global.invoke(filters, "isShowAll")); //$NON-NLS-1$
        String scopeName = showAll ? SCOPE_ALL : scopeName(Global.invoke(filters, "getScope")); //$NON-NLS-1$
        if (scopeName == null)
            return null;
        String value = message(loader, scopeField(scopeName));
        if (value == null)
        {
            Debug.log("scopeSuffix: no message for " + scopeName); //$NON-NLS-1$
            return null;
        }
        String detail = scopeDetail(view, scopeName);
        return SCOPE_SEPARATOR + SCOPE_TITLE + ": " + value //$NON-NLS-1$
            + (detail != null ? ": " + detail : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Источник отбора для режимов «Текущий проект» / «Текущий объект» /
     * «Текущий элемент»: панель строит его из своих {@code scopeSelection} +
     * {@code scopeSelectionManager} ({@code LazyProblemView.getCurrentSelectedObjectIds}),
     * оттуда же берём и подпись.
     *
     * <ul>
     * <li>«Текущий проект» — имена выделенных проектов;</li>
     * <li>«Текущий объект» — выделенный элемент поднят до объекта МД верхнего
     * уровня (как в самом отборе);</li>
     * <li>«Текущий элемент» — сам выделенный элемент, без подъёма.</li>
     * </ul>
     *
     * @return имена через запятую или {@code null}, если источник не определён
     */
    private static String scopeDetail(IViewPart view, String scopeName)
    {
        Object selection = Global.getField(view, "scopeSelection"); //$NON-NLS-1$
        if (selection == null)
            return null;
        if (SCOPE_CURRENT_PROJECT.equals(scopeName))
            return join(projectNames(selection));
        if (!SCOPE_CURRENT_OBJECT.equals(scopeName) && !SCOPE_CURRENT_ELEMENT.equals(scopeName))
            return null;

        Object manager = Global.getField(view, "scopeSelectionManager"); //$NON-NLS-1$
        boolean liftToTopObject = SCOPE_CURRENT_OBJECT.equals(scopeName);
        Object objects = Global.invoke(selection, "getSelectedObjects"); //$NON-NLS-1$
        if (!(objects instanceof Map<?, ?> byProject))
            return null;

        Set<String> names = new LinkedHashSet<>();
        for (Object perProject : byProject.values())
        {
            if (!(perProject instanceof Collection<?> items))
                continue;
            for (Object item : items)
                add(names, fullNameOfScopeObject(manager, item, liftToTopObject));
        }
        return join(names);
    }

    /**
     * Проекты отбора: как в штатном {@code ScopeSelectionManager.getCurrentProject} —
     * и явно выделенные проекты, и проекты выделенных объектов (при выделении
     * объекта список проектов пуст, а отбор всё равно работает).
     */
    private static Set<String> projectNames(Object selection)
    {
        Set<String> names = new LinkedHashSet<>();
        addProjectNames(names, Global.invoke(selection, "getSelectedProjects")); //$NON-NLS-1$
        if (Global.invoke(selection, "getSelectedObjects") instanceof Map<?, ?> byProject) //$NON-NLS-1$
            addProjectNames(names, byProject.keySet());
        return names;
    }

    private static void addProjectNames(Set<String> names, Object projects)
    {
        if (!(projects instanceof Collection<?> items))
            return;
        for (Object project : items)
        {
            if (project instanceof IProject resource)
                add(names, resource.getName());
        }
    }

    private static String fullNameOfScopeObject(Object manager, Object item, boolean liftToTopObject)
    {
        if (!(item instanceof EObject object))
            return null;
        // Подпись обновляется по таймеру: исключение из чужого резолва оборвало бы цепочку тиков.
        try
        {
            Object top = liftToTopObject && manager != null
                ? Global.invoke(manager, "getTopMdObject", object) //$NON-NLS-1$
                : null;
            return GetRef.eObjectToFullName(top instanceof EObject topObject ? topObject : object);
        }
        catch (Exception e)
        {
            Debug.log("fullNameOfScopeObject: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static void add(Set<String> names, String name)
    {
        if (name != null && !name.isBlank())
            names.add(name);
    }

    private static String join(Set<String> names)
    {
        return names.isEmpty() ? null : String.join(", ", names); //$NON-NLS-1$
    }

    private static void keepScopeAppended(IViewPart view, Label status, Object filters, ClassLoader loader)
    {
        Display display = status.getDisplay();
        Runnable[] tick = new Runnable[1];
        tick[0] = () ->
        {
            if (status.isDisposed())
                return;
            appendScope(view, status, filters, loader);
            display.timerExec(SCOPE_REFRESH_MS, tick[0]);
        };
        display.timerExec(SCOPE_REFRESH_MS, tick[0]);
    }

    private static String scopeName(Object scope)
    {
        return scope instanceof Enum<?> constant ? constant.name() : null;
    }

    /**
     * Подпись нашей области над деревом: «Отобранное в проекте &lt;Проект&gt; навигатора»
     * либо «Набор «&lt;Набор&gt;» проекта &lt;Проект&gt;» (+ « пуст», если набор без
     * объектов). Проект — из фактически наложенной области ({@link #comfortAppliedProject}),
     * а если её нет (набор пуст) — активный проект страницы.
     */
    private static String comfortScopeLabel(IViewPart view)
    {
        ProblemViewComfortScope.Mode mode = ProblemViewComfortScope.mode();
        IProject project = comfortAppliedProject.get(view);
        String projectName = project != null ? project.getName() : null;
        if (mode == ProblemViewComfortScope.Mode.NAVIGATOR)
        {
            return projectName != null
                ? "Отобранное в проекте " + projectName + " навигатора" //$NON-NLS-1$ //$NON-NLS-2$
                : ProblemViewComfortScope.NAVIGATOR_LABEL;
        }
        if (mode == ProblemViewComfortScope.Mode.ACTIVE_SETS)
        {
            boolean empty = project == null; // область по объектам не наложена → набор пуст
            if (projectName == null && view.getSite() != null)
            {
                IProject active = ActiveProjectTracker.resolveContextProject(view.getSite().getPage());
                projectName = active != null ? active.getName() : null;
            }
            if (projectName == null)
                return empty ? "Активный набор проекта пуст" : ProblemViewComfortScope.ACTIVE_SETS_LABEL; //$NON-NLS-1$
            ObjectSets.SetDef set = activeSet(projectName);
            String setPart = set != null ? "Набор " + ObjectSets.quotedName(set) : "Активный набор"; //$NON-NLS-1$ //$NON-NLS-2$
            boolean suffixEmpty = empty && (set == null || !set.isDefaultSet());
            return setPart + " проекта " + projectName + (suffixEmpty ? " пуст" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return null;
    }

    private static ObjectSets.SetDef activeSet(String projectName)
    {
        try
        {
            return ObjectSetsAddTargetState.getInstance().getAddTargetSet(projectName);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** Имя поля {@code Messages} с названием режима: {@code CURRENT_PROJECT} → {@code Scope_current_project}. */
    private static String scopeField(String constantName)
    {
        if ("ALL".equals(constantName)) //$NON-NLS-1$
            return "Scope_All"; //$NON-NLS-1$
        if ("SUBSYSTEM_FILTER".equals(constantName)) //$NON-NLS-1$
            return "Scope_subsystems_filter"; //$NON-NLS-1$
        return "Scope_" + constantName.toLowerCase(); //$NON-NLS-1$
    }

    /**
     * Отбор живёт в {@code ProblemFilters} — том же объекте, на который подписана
     * сама панель, поэтому подпись меняется вместе со списком. Пакет
     * {@code internal} наружу не экспортирован, так что слушателя подставляем
     * динамическим прокси (см. правило про неэкспортированные супертипы).
     */
    private static void listenScopeChanges(IViewPart view, Label status, Object filters, ClassLoader loader)
    {
        if (filters == null)
            return;
        try
        {
            Class<?> listenerType = loader.loadClass(CHANGE_LISTENER_CLASS);
            Display display = status.getDisplay();
            InvocationHandler handler = (proxy, method, args) ->
            {
                if ("accept".equals(method.getName())) //$NON-NLS-1$
                    display.asyncExec(() -> appendScope(view, status, filters, loader));
                return defaultProxyResult(method, args, proxy);
            };
            Object listener = Proxy.newProxyInstance(loader, new Class<?>[] { listenerType }, handler);
            Global.invoke(filters, "addChangeListener", listener); //$NON-NLS-1$
            status.addDisposeListener(event -> Global.invoke(filters, "removeChangeListener", listener)); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Debug.log("listenScopeChanges: " + e); //$NON-NLS-1$
        }
    }

    private static Object defaultProxyResult(java.lang.reflect.Method method, Object[] args, Object proxy)
    {
        return switch (method.getName())
        {
            case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null); //$NON-NLS-1$
            case "hashCode" -> System.identityHashCode(proxy); //$NON-NLS-1$
            case "toString" -> "ProblemViewHook.scopeListener"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> null;
        };
    }

    private static Object problemFilters(ClassLoader loader)
    {
        try
        {
            return loader.loadClass(PLUGIN_CLASS).getMethod("getProblemFilters").invoke(null); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Debug.log("problemFilters: " + e); //$NON-NLS-1$
            return null;
        }
    }

    /** Локализованная строка EDT из неэкспортированного {@code Messages}. */
    private static String message(ClassLoader loader, String fieldName)
    {
        try
        {
            Field field = loader.loadClass(MESSAGES_CLASS).getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof String text && !text.isBlank() ? text.trim() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static boolean isProblemView(IViewPart view)
    {
        return view != null && ProblemViewMarkers.PROBLEM_VIEW_ID.equals(view.getViewSite().getId());
    }

    // === Области отбора «Отобранное в проекте навигатора» / «Активный набор проекта» (issue 462) ===
    //
    // Радио добавляет ProblemFiltersDialogHook, режим хранит ProblemViewComfortScope. Здесь:
    // синтетическая ScopeSelection из объектов области + перевод штатной «Области возникновения»
    // в CURRENT_ELEMENT (по ней панель отбирает маркеры по конкретным объектам и их потомкам).

    private static void installComfortScope(IViewPart view)
    {
        synchronized (comfortInstalled)
        {
            if (comfortInstalled.containsKey(view))
                return;
            comfortInstalled.put(view, Boolean.TRUE);
        }
        ClassLoader loader = view.getClass().getClassLoader();
        Object filters = problemFilters(loader);
        if (filters == null)
        {
            Debug.log("installComfortScope: no ProblemFilters"); //$NON-NLS-1$
            return;
        }

        installComfortGlobalListeners();

        // Смена активного проекта страницы — пересобрать нашу область (её проект =
        // активный проект). Это не «каждый клик»: слушатель срабатывает только на
        // реальную смену контекстного проекта, потому потока обновлений нет.
        IWorkbenchPage page = view.getSite().getPage();
        ActiveProjectTracker.bootstrapPage(page);
        ActiveProjectTracker.ContextProjectListener projectListener = (p, previous, current) ->
        {
            if (!java.util.Objects.equals(previous, current) && view.getSite() != null
                && ProblemViewComfortScope.mode() != ProblemViewComfortScope.Mode.NONE)
            {
                Display.getDefault().asyncExec(() -> applyComfortScope(view, filters, loader));
            }
        };
        ActiveProjectTracker.addListener(page, projectListener);

        // Метку установки снять при закрытии панели (иначе следующий её экземпляр
        // не переустановит хук).
        if (view.getAdapter(TreeViewer.class) instanceof TreeViewer viewer
            && viewer.getTree() != null && !viewer.getTree().isDisposed())
        {
            viewer.getTree().addDisposeListener(e ->
            {
                ActiveProjectTracker.removeListener(page, projectListener);
                synchronized (comfortInstalled)
                {
                    comfortInstalled.remove(view);
                }
                comfortSyntheticSelection.remove(view);
                comfortAppliedScope.remove(view);
                comfortAppliedProject.remove(view);
            });
        }

        startComfortReassertTicker(view, filters, loader);
    }

    private static volatile boolean comfortGlobalListeners;

    /**
     * Слушатели наборов — один раз на процесс: панель проблем — синглтон, набор
     * объектов у режимов общий, поэтому на изменение наборов пересобираем область
     * во всех открытых панелях.
     */
    private static void installComfortGlobalListeners()
    {
        if (comfortGlobalListeners)
            return;
        comfortGlobalListeners = true;
        Runnable rebuildAll = () -> Display.getDefault().asyncExec(ProblemViewHook::rebuildComfortScopeEverywhere);
        try
        {
            ObjectSets.getInstance().addChangeListener(rebuildAll);
            ObjectSetsAddTargetState.getInstance().addListener(rebuildAll);
        }
        catch (RuntimeException e)
        {
            Debug.log("installComfortGlobalListeners: " + e); //$NON-NLS-1$
        }
        ProblemViewComfortScope.addListener(
            () -> Display.getDefault().asyncExec(ProblemViewHook::rebuildComfortScopeEverywhere));

        // Выбор штатного радио «Области возникновения» (в диалоге его перехватывает
        // ProblemFiltersDialogHook, а вот пулдаун/меню панели идут прямо через команду)
        // — сбрасывает наш режим.
        ICommandService commandService = PlatformUI.isWorkbenchRunning()
            ? PlatformUI.getWorkbench().getService(ICommandService.class) : null;
        if (commandService != null)
        {
            commandService.addExecutionListener(new IExecutionListener()
            {
                @Override public void postExecuteSuccess(String commandId, Object returnValue)
                {
                    if (NATIVE_SCOPE_COMMAND_ID.equals(commandId))
                    {
                        ProblemViewComfortScope.setMode(ProblemViewComfortScope.Mode.NONE);
                    }
                    else if (NAVIGATOR_SUBSYSTEMS_FILTER_COMMAND_ID.equals(commandId)
                        && ProblemViewComfortScope.mode() == ProblemViewComfortScope.Mode.NAVIGATOR)
                    {
                        // Фильтр по подсистемам в навигаторе применяется асинхронно.
                        Display.getDefault().timerExec(400, ProblemViewHook::rebuildComfortScopeEverywhere);
                    }
                }

                @Override public void preExecute(String commandId, org.eclipse.core.commands.ExecutionEvent event) {}
                @Override public void postExecuteFailure(String commandId, ExecutionException exception) {}
                @Override public void notHandled(String commandId, NotHandledException exception) {}
            });
        }
    }

    private static void rebuildComfortScopeEverywhere()
    {
        java.util.List<IViewPart> views;
        synchronized (comfortInstalled)
        {
            views = new java.util.ArrayList<>(comfortInstalled.keySet());
        }
        for (IViewPart view : views)
        {
            if (view.getSite() == null)
                continue;
            ClassLoader loader = view.getClass().getClassLoader();
            Object filters = problemFilters(loader);
            if (filters != null)
                applyComfortScope(view, filters, loader);
        }
    }

    /**
     * Вызывается на каждый показ панели: если режим активен — восстановить нашу
     * область, если панель её сбросила. Дешёвая сверка, без пересборки на каждый
     * показ (полная пересборка — только на смену режима/наборов).
     */
    private static void refreshComfortScope(IViewPart view)
    {
        if (view.getSite() == null || ProblemViewComfortScope.mode() == ProblemViewComfortScope.Mode.NONE)
            return;
        ClassLoader loader = view.getClass().getClassLoader();
        Object filters = problemFilters(loader);
        if (filters != null)
            reassertComfortScope(view, filters, loader);
    }

    /**
     * Полное наложение: разрешить объекты области в фоне (открывает BM-транзакции),
     * затем в UI-потоке подменить {@code scopeSelection} и обновить панель.
     */
    private static void applyComfortScope(IViewPart view, Object filters, ClassLoader loader)
    {
        if (view.getSite() == null)
            return;
        ProblemViewComfortScope.Mode mode = ProblemViewComfortScope.mode();
        if (mode == ProblemViewComfortScope.Mode.NONE || !ComfortSettings.isReplaceListFiltersEnabled())
        {
            clearComfortScope(view, filters, loader);
            return;
        }
        IWorkbenchPage page = view.getSite().getPage();
        // Панель отбирает по одному проекту (getCurrentProject), поэтому область
        // ограничивается активным проектом.
        IProject active = ActiveProjectTracker.resolveContextProject(page);
        Map<String, List<String>> refs = ProblemViewComfortScope.collectRefs();

        // Нечем сузить: «Отобранное в проекте навигатора» без активного отбора —
        // это весь проект навигатора; «Активный набор проекта» без набора — без
        // ограничения. Так режим никогда не «залипает» на текущем выделении.
        IProject fallbackProject =
            mode == ProblemViewComfortScope.Mode.NAVIGATOR ? active : null;

        Job job = Job.create("Комфорт: область панели проблем", (ICoreRunnable)monitor -> //$NON-NLS-1$
        {
            Map<IProject, Set<EObject>> data =
                refs.isEmpty() ? Map.of() : resolveComfortScopeObjects(page, refs, active);
            Display.getDefault().asyncExec(
                () -> injectComfortScope(view, filters, loader, data, fallbackProject));
        });
        job.setSystem(true);
        job.schedule();
    }

    /** Разрешение владеющих ссылок в {@link EObject} метаданных активного проекта. */
    private static Map<IProject, Set<EObject>> resolveComfortScopeObjects(
        IWorkbenchPage page, Map<String, List<String>> refs, IProject preferred)
    {
        Map<IProject, Set<EObject>> result = new LinkedHashMap<>();
        // Ровно один проект: активный, если он есть среди отобранных, иначе первый.
        String chosen = chooseComfortScopeProject(refs, preferred);
        for (Map.Entry<String, List<String>> entry : refs.entrySet())
        {
            String projectName = entry.getKey();
            if (projectName == null || !projectName.equals(chosen))
                continue;
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.isOpen())
                continue;
            Set<EObject> objects = new LinkedHashSet<>();
            for (String ref : entry.getValue())
            {
                EObject eObject = null;
                try
                {
                    eObject = GoToDefinition.resolveEObjectForFullName(ref, page, project);
                }
                catch (RuntimeException e)
                {
                    Debug.log("resolveComfortScopeObjects: " + ref + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (eObject != null)
                    objects.add(eObject);
            }
            if (!objects.isEmpty())
                result.put(project, objects);
        }
        return result;
    }

    /**
     * Проект, по объектам которого сужаем: только активный, и только если отбор
     * навигатора его затрагивает. Иначе {@code null} — {@link #injectComfortScope}
     * возьмёт весь активный проект ({@code CURRENT_PROJECT}).
     */
    private static String chooseComfortScopeProject(Map<String, List<String>> refs, IProject preferred)
    {
        String preferredName = preferred != null ? preferred.getName() : null;
        return preferredName != null && refs.containsKey(preferredName) ? preferredName : null;
    }

    private static void injectComfortScope(IViewPart view, Object filters, ClassLoader loader,
        Map<IProject, Set<EObject>> data, IProject fallbackProject)
    {
        if (view.getSite() == null || ProblemViewComfortScope.mode() == ProblemViewComfortScope.Mode.NONE
            || !ComfortSettings.isReplaceListFiltersEnabled())
            return;

        Object selection;
        String scopeName;
        IProject scopeProject;
        if (!data.isEmpty())
        {
            selection = newObjectsScopeSelection(loader, data);
            scopeName = SCOPE_CURRENT_ELEMENT;
            scopeProject = data.keySet().iterator().next();
        }
        else if (fallbackProject != null)
        {
            selection = newProjectScopeSelection(loader, fallbackProject);
            scopeName = SCOPE_CURRENT_PROJECT;
            scopeProject = fallbackProject;
        }
        else
        {
            // «Активный набор проекта» без набора — без ограничения по области.
            selection = newProjectScopeSelection(loader, null);
            scopeName = SCOPE_ALL;
            scopeProject = null;
        }
        if (selection == null)
            return;

        comfortSyntheticSelection.put(view, selection);
        comfortAppliedScope.put(view, scopeName);
        if (scopeProject != null)
            comfortAppliedProject.put(view, scopeProject);
        else
            comfortAppliedProject.remove(view);
        Global.setField(view, "scopeSelection", selection); //$NON-NLS-1$
        forceComfortScope(filters, loader, scopeName);
        // Пока наша область активна, панель не должна перестраивать scopeSelection на
        // каждое выделение в навигаторе/редакторе — снимаем её слушатель выделения.
        setPanelSelectionTracking(view, false);
        Global.invoke(filters, "update"); //$NON-NLS-1$
    }

    /**
     * Тихая сверка (тикер): если поле {@code scopeSelection} или область кто-то
     * подменил — вернуть наши <b>без</b> {@code update()}: содержимое не менялось.
     */
    private static void reassertComfortScope(IViewPart view, Object filters, ClassLoader loader)
    {
        if (view.getSite() == null || ProblemViewComfortScope.mode() == ProblemViewComfortScope.Mode.NONE
            || !ComfortSettings.isReplaceListFiltersEnabled())
        {
            clearComfortScope(view, filters, loader);
            return;
        }
        Object synthetic = comfortSyntheticSelection.get(view);
        String scopeName = comfortAppliedScope.get(view);
        if (synthetic == null || scopeName == null)
        {
            applyComfortScope(view, filters, loader);
            return;
        }
        // Активный проект сменился — область относится к другому проекту, пересобрать.
        IProject appliedProject = comfortAppliedProject.get(view);
        if (appliedProject != null && view.getSite().getPage() != null
            && !appliedProject.equals(ActiveProjectTracker.resolveContextProject(view.getSite().getPage())))
        {
            applyComfortScope(view, filters, loader);
            return;
        }
        setPanelSelectionTracking(view, false);
        if (Global.getField(view, "scopeSelection") != synthetic) //$NON-NLS-1$
            Global.setField(view, "scopeSelection", synthetic); //$NON-NLS-1$
        if (!scopeName.equals(scopeName(Global.invoke(filters, "getScope")))) //$NON-NLS-1$
            forceComfortScope(filters, loader, scopeName);
    }

    /**
     * Слушатель выделения самой панели ({@code LazyProblemView implements ISelectionListener},
     * регистрируется через {@code ISelectionService.addPostSelectionListener}).
     */
    private static void setPanelSelectionTracking(IViewPart view, boolean enabled)
    {
        if (!(view instanceof ISelectionListener listener) || view.getSite() == null)
            return;
        IWorkbenchWindow window = view.getSite().getWorkbenchWindow();
        ISelectionService service = window != null ? window.getSelectionService() : null;
        if (service == null)
            return;
        try
        {
            if (enabled)
                service.addPostSelectionListener(listener);
            else
                service.removePostSelectionListener(listener);
        }
        catch (RuntimeException e)
        {
            Debug.log("setPanelSelectionTracking: " + e); //$NON-NLS-1$
        }
    }

    private static void clearComfortScope(IViewPart view, Object filters, ClassLoader loader)
    {
        setPanelSelectionTracking(view, true);
        String applied = comfortAppliedScope.remove(view);
        comfortAppliedProject.remove(view);
        if (comfortSyntheticSelection.remove(view) == null)
            return;
        // Если область всё ещё та, что выставили мы (режим выключили нашим флажком) —
        // вернуть в «Показывать всё», иначе список остался бы сужен нашим
        // CURRENT_ELEMENT/CURRENT_PROJECT. Если область уже другая (пользователь выбрал
        // штатное радио) — не трогать его выбор.
        boolean stillOurs = applied != null
            && applied.equals(scopeName(Global.invoke(filters, "getScope"))); //$NON-NLS-1$
        Object all = stillOurs ? scopeConstant(loader, SCOPE_ALL) : null;
        if (all != null)
        {
            Global.invokeVoid(filters, "setScope", all); //$NON-NLS-1$
            Global.invokeVoid(filters, "setShowAll", Boolean.TRUE); //$NON-NLS-1$
        }
        Object manager = Global.getField(view, "scopeSelectionManager"); //$NON-NLS-1$
        Object fresh = manager != null
            ? Global.invoke(manager, "getScopeFromNavigatorSelection", view.getSite()) : null; //$NON-NLS-1$
        if (fresh == null)
            fresh = newProjectScopeSelection(loader, null);
        if (fresh != null)
            Global.setField(view, "scopeSelection", fresh); //$NON-NLS-1$
        Global.invoke(filters, "update"); //$NON-NLS-1$
    }

    /** Синтетическая область с объектами по проектам ({@code CURRENT_ELEMENT}). */
    private static Object newObjectsScopeSelection(ClassLoader loader, Map<IProject, Set<EObject>> data)
    {
        Object selection = newEmptyScopeSelection(loader);
        if (selection != null && data != null && !data.isEmpty()
            && Global.invoke(selection, "getSelectedObjects") instanceof Map<?, ?> mapObj) //$NON-NLS-1$
        {
            @SuppressWarnings("unchecked")
            Map<IProject, Set<EObject>> map = (Map<IProject, Set<EObject>>)mapObj;
            map.putAll(data);
        }
        return selection;
    }

    /** Синтетическая область с одним проектом ({@code CURRENT_PROJECT}); {@code null} — пустая. */
    private static Object newProjectScopeSelection(ClassLoader loader, IProject project)
    {
        Object selection = newEmptyScopeSelection(loader);
        if (selection != null && project != null
            && Global.invoke(selection, "getSelectedProjects") instanceof java.util.Set<?> setObj) //$NON-NLS-1$
        {
            @SuppressWarnings("unchecked")
            java.util.Set<IProject> set = (java.util.Set<IProject>)setObj;
            set.add(project);
        }
        return selection;
    }

    private static Object newEmptyScopeSelection(ClassLoader loader)
    {
        try
        {
            return loader.loadClass(SCOPE_SELECTION_CLASS).getDeclaredConstructor().newInstance();
        }
        catch (ReflectiveOperationException e)
        {
            Debug.log("newEmptyScopeSelection: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static void forceComfortScope(Object filters, ClassLoader loader, String scopeName)
    {
        Object scope = scopeConstant(loader, scopeName);
        if (scope == null)
            return;
        // Всегда showAll=false: наши режимы оставляют отбор по критичности/типу, а
        // сама область при SCOPE_ALL просто ничего не сужает.
        if (Boolean.TRUE.equals(Global.invoke(filters, "isShowAll"))) //$NON-NLS-1$
            Global.invokeVoid(filters, "setShowAll", Boolean.FALSE); //$NON-NLS-1$
        if (Global.invoke(filters, "getScope") != scope) //$NON-NLS-1$
            Global.invokeVoid(filters, "setScope", scope); //$NON-NLS-1$
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object scopeConstant(ClassLoader loader, String name)
    {
        try
        {
            return Enum.valueOf((Class)loader.loadClass(SCOPE_ENUM_CLASS), name);
        }
        catch (ReflectiveOperationException | IllegalArgumentException e)
        {
            Debug.log("scopeConstant: " + name + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    private static void startComfortReassertTicker(IViewPart view, Object filters, ClassLoader loader)
    {
        Display display = Display.getDefault();
        Runnable[] tick = new Runnable[1];
        tick[0] = () ->
        {
            if (view.getSite() == null)
                return;
            if (ProblemViewComfortScope.mode() != ProblemViewComfortScope.Mode.NONE
                && view.getSite().getPage().isPartVisible(view))
            {
                reassertComfortScope(view, filters, loader);
            }
            display.timerExec(COMFORT_REASSERT_MS, tick[0]);
        };
        display.timerExec(COMFORT_REASSERT_MS, tick[0]);
    }

    /**
     * {@code Marker.getCheckId()} — короткий код проверки в пределах проекта
     * ({@code SU47}), и страница проверок ждёт в {@code applyData} именно его:
     * она сама превращает его в полный {@link CheckUid} через
     * {@link ICheckRepository#getUidForShortUid}.
     */
    static void openCheckSettings(Shell shell, Marker marker)
    {
        if (marker == null)
            return;
        openCheckSettings(shell, marker.getProject(), marker.getCheckId());
    }

    /** То же по проекту и короткому коду проверки — без маркера (подсказка редактора модуля). */
    static void openCheckSettings(Shell shell, IProject project, String shortUid)
    {
        if (shortUid == null || shortUid.isBlank() || project == null)
        {
            Debug.log("openCheckSettings: no check id or project"); //$NON-NLS-1$
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put(CHECK_ID_DATA_KEY, shortUid);
        Debug.log("openCheckSettings: " + shortUid); //$NON-NLS-1$
        Shell target = shell != null ? shell : Display.getDefault().getActiveShell();
        PreferenceDialog dialog =
            PreferencesUtil.createPropertyDialogOn(target, project, CHECKS_PAGE_ID, null, data);
        if (dialog == null)
            return;
        CheckDescriptionRefresh.install(dialog);
        dialog.open();
    }

    /**
     * Описание проверки (HTML) на странице «Проверки» параметров проекта рисуется
     * через {@code Browser.execute("document.body.innerHTML = …")}. При открытии
     * страницы с уже выбранной проверкой ({@code applyData}) описание пустое: EDT
     * вызывает {@code execute} сразу после {@code setText} начального документа, а
     * до окончания его загрузки {@code execute} молча ничего не делает. Повторяем
     * отрисовку по событию загрузки документа — состояние {@code CheckViewer}
     * (проект и настройка проверки) к этому моменту уже проставлено.
     *
     * <p>Диалог сюда приходит уже созданным: {@code PropertyDialog.createDialogOn}
     * внутри {@link PreferencesUtil#createPropertyDialogOn} сам вызывает
     * {@code create()}. Повторный {@code create()} создаёт второй shell и теряет
     * выбранную страницу вместе с отбором по проекту — вызывать его нельзя.
     * Поэтому, кроме слушателя, планируем и разовую перерисовку в очереди UI:
     * документ мог загрузиться ещё до установки слушателя.
     */
    private static final class CheckDescriptionRefresh
    {
        private CheckDescriptionRefresh() {}

        static void install(PreferenceDialog dialog)
        {
            Object page = dialog.getSelectedPage();
            if (!(page instanceof IPreferencePage preferencePage))
                return;
            Browser browser = findBrowser(preferencePage.getControl());
            if (browser == null)
            {
                Debug.log("CheckDescriptionRefresh: no browser on checks page"); //$NON-NLS-1$
                return;
            }
            Composite checkViewer = browser.getParent();
            Runnable refresh = () -> {
                if (!browser.isDisposed())
                    Global.invokeVoid(checkViewer, "updateHtmlContent"); //$NON-NLS-1$
            };
            browser.addProgressListener(ProgressListener.completedAdapter(e -> refresh.run()));
            browser.getDisplay().asyncExec(refresh);
        }

        private static Browser findBrowser(Control control)
        {
            if (control instanceof Browser browser)
                return browser;
            if (control instanceof Composite composite)
            {
                for (Control child : composite.getChildren())
                {
                    Browser found = findBrowser(child);
                    if (found != null)
                        return found;
                }
            }
            return null;
        }
    }

    private static Marker markerOf(Object element)
    {
        if (element instanceof Marker marker)
            return marker;
        Object marker = Global.invoke(element, "getMarker"); //$NON-NLS-1$
        return marker instanceof Marker m ? m : null;
    }

    private static final class Debug
    {
        private static final String TAG = "ProblemView"; //$NON-NLS-1$

        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
