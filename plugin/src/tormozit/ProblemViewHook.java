package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
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
import org.eclipse.ui.dialogs.PreferencesUtil;

import com._1c.g5.v8.dt.validation.marker.Marker;
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
        installScopeLabel(view);
        installOpenOverride(view);
    }

    /**
     * Подпись «Область: …» в строке над деревом, сразу за итогами по видам
     * проблем (issue 401). Для режимов «Текущий проект», «Текущий объект» и
     * «Текущий элемент» дописывается ещё и сам источник отбора (имя проекта,
     * полное имя объекта или элемента) — иначе непонятно, чей это список.
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
        if (!wanted.equals(text))
            status.setText(wanted);
    }

    private static String scopeSuffix(IViewPart view, Object filters, ClassLoader loader)
    {
        Object scope = Global.invoke(filters, "getScope"); //$NON-NLS-1$
        if (!(scope instanceof Enum<?> constant))
            return null;
        String value = message(loader, scopeField(constant.name()));
        if (value == null)
        {
            Debug.log("scopeSuffix: no message for " + scope); //$NON-NLS-1$
            return null;
        }
        String detail = scopeDetail(view, constant.name());
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
