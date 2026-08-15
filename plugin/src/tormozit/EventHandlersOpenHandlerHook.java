package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.IURIEditorOpener;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.ui.util.OpenHelper;

/**
 * Команда «Открыть обработчик» в дереве подписок панели «Все подписки на события»
 * ({@code EventHandlersEditor}) — первым пунктом контекстного меню и как действие по
 * умолчанию (двойной клик, выделено жирным через {@link Menu#setDefaultItem}).
 *
 * <p>Штатное «Открыть» ({@code OpenObjectHandler}) для строки дерева открывает не
 * обработчик, а объект: для {@code MethodContainer} — редактор объекта-владельца
 * ({@code getOwnerTypeItem()}), для подписки — её редактор метаданных. Процедуру-обработчик
 * приходилось искать вручную. Наша команда открывает сам метод BSL:
 * <ul>
 * <li>{@code MethodContainer} (переопределение в модуле объекта) — {@code getMethod()};</li>
 * <li>{@code EventSubscription} — процедура из свойства «Обработчик»
 * ({@code CommonModule.&lt;Модуль&gt;.&lt;Процедура&gt;}): общий модуль ищется в BM-модели
 * редактора, метод — по имени среди {@code Module.allMethods()} (так же, как штатный
 * {@code OpenObjectHandler} ищет метод для узлов панели «Обработчики»).</li>
 * </ul>
 * Открытие — тем же способом, что и штатный обработчик: {@link IURIEditorOpener} языка
 * BSL по {@code EcoreUtil.getURI(метод)}.
 *
 * <p>Двойной клик: штатный {@code SubSection$ViewerDoubleClickListener} снимается с
 * дерева подписок (перечисление слушателей — рефлексией по {@code StructuredViewer.
 * doubleClickListeners}, снятие — публичным {@code removeDoubleClickListener}); если снять
 * не удалось, свой слушатель НЕ ставится — иначе двойной клик открывал бы сразу и объект,
 * и обработчик. Разворачивание/сворачивание узла по двойному клику делает сам
 * {@code AbstractTreeViewer}, слушателями оно не затрагивается.
 *
 * <p>Пакет {@code com._1c.g5.v8.dt.eventhandlers.model} бандлом не экспортируется, поэтому
 * {@code MethodContainer} читается рефлексией (как и в {@link MdEventHandlersPageHook}).
 */
public final class EventHandlersOpenHandlerHook implements IStartup
{
    private static final String TAG = "EventHandlersOpenHandler"; //$NON-NLS-1$

    private static final String EDITOR_CLASS =
        "com._1c.g5.v8.dt.eventhandlers.ui.editor.EventHandlersEditor"; //$NON-NLS-1$

    private static final String METHOD_CONTAINER_CLASS =
        "com._1c.g5.v8.dt.eventhandlers.model.MethodContainer"; //$NON-NLS-1$

    private static final String STOCK_DOUBLE_CLICK_CLASS =
        "com._1c.g5.v8.dt.eventhandlers.ui.sections.SubSection$ViewerDoubleClickListener"; //$NON-NLS-1$

    private static final String PATCHED_KEY = "tormozit.eventHandlersOpenHandlerPatched"; //$NON-NLS-1$

    private static final String ITEM_TEXT = "Открыть обработчик"; //$NON-NLS-1$

    private static final String ITEM_TOOLTIP =
        "Открыть процедуру-обработчик выбранной строки в модуле" //$NON-NLS-1$
        + Global.pluginSignForTooltip();

    private static final String ITEM_TEXT_LINK = "Открыть связь"; //$NON-NLS-1$

    private static final String ITEM_TOOLTIP_LINK =
        "Открыть редактор подписки, в нём — диалог «Редактирование типа данных» поля «Источник»" //$NON-NLS-1$
        + " с активной строкой текущего объекта" //$NON-NLS-1$
        + Global.pluginSignForTooltip();

    /** Тема временного лога сценария «Открыть связь» (шаги фиксируются безусловно). */
    private static final String LINK_LOG_TOPIC = "eventhandlers-open-link"; //$NON-NLS-1$

    /** Тема временного лога «Открыть обработчик» / двойного клика. */
    private static final String HANDLER_LOG_TOPIC = "eventhandlers-open-handler"; //$NON-NLS-1$

    /** Префикс FQN общего модуля в свойстве «Обработчик» подписки. */
    private static final String COMMON_MODULE_PREFIX = "CommonModule."; //$NON-NLS-1$

    private static final int MAX_ATTEMPTS = 40;

    private static final int RETRY_MS = 100;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)      { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });
            for (IWorkbenchWindow w : workbench.getWorkbenchWindows())
                hookWindow(w);
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;

        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
                patchIfEventHandlersEditor(ref.getEditor(false));
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)    { patchFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { patchFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}

            private void patchFromRef(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference))
                    return;
                IWorkbenchPart part = ((IEditorReference)ref).getPart(false);
                if (part instanceof IEditorPart editorPart)
                    patchIfEventHandlersEditor(editorPart);
            }
        });
    }

    private static void patchIfEventHandlersEditor(Object editor)
    {
        if (editor != null && EDITOR_CLASS.equals(editor.getClass().getName()))
            patchEditor(editor);
    }

    /**
     * Granular-редакторы, в страницу которых встроен {@code EventHandlersEditor}
     * ({@link MdEventHandlersPageHook}). Нужны, чтобы обработчик своего же объекта
     * открывался прямо в этом редакторе, а не поверх него ещё раз.
     */
    private static final Map<Object, Object> hostEditors = new WeakHashMap<>();

    /**
     * Подключает команду к уже созданному {@code EventHandlersEditor} — в том числе к
     * встроенному в страницу редактора объекта, которого нет среди частей workbench
     * (см. {@link MdEventHandlersPageHook}).
     *
     * @param hostGranularEditor редактор объекта, в страницу которого встроена панель
     * ({@code null} для отдельной панели «Все подписки на события»).
     */
    static void patchEditor(Object editor, Object hostGranularEditor)
    {
        if (editor != null && hostGranularEditor != null)
            hostEditors.put(editor, hostGranularEditor);
        scheduleTryPatch(editor, 0);
    }

    static void patchEditor(Object editor)
    {
        patchEditor(editor, null);
    }

    private static void scheduleTryPatch(Object editor, int attempt)
    {
        if (editor == null || attempt >= MAX_ATTEMPTS)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;

        Runnable task = () ->
        {
            if (tryPatch(editor))
                return;
            scheduleTryPatch(editor, attempt + 1);
        };
        if (attempt == 0)
            display.asyncExec(task);
        else
            display.timerExec(RETRY_MS, task);
    }

    /** @return {@code true}, если делать больше нечего (подключено или недоступно). */
    private static boolean tryPatch(Object editor)
    {
        try
        {
            Object mainSection = Global.invoke(editor, "getMainSection"); //$NON-NLS-1$
            if (mainSection == null)
                return false;
            Object viewerObj = Global.invoke(mainSection, "getEventHandlersTreeViewer"); //$NON-NLS-1$
            if (!(viewerObj instanceof TreeViewer viewer))
                return false;

            Tree tree = viewer.getTree();
            if (tree == null || tree.isDisposed())
                return false;
            if (Boolean.TRUE.equals(tree.getData(PATCHED_KEY)))
                return true;

            Menu menu = tree.getMenu();
            if (menu == null || menu.isDisposed())
                return false;

            tree.setData(PATCHED_KEY, Boolean.TRUE);
            installMenuItem(menu, editor, viewer);
            installDoubleClick(editor, viewer);
            return true;
        }
        catch (Exception e)
        {
            Global.logError(TAG, "tryPatch", e); //$NON-NLS-1$
            return true;
        }
    }

    /** Дерево подписок редактора — чтобы не навешивать на него второе действие двойного клика. */
    static Tree resolveEventHandlersTree(Object editor)
    {
        Object mainSection = Global.invoke(editor, "getMainSection"); //$NON-NLS-1$
        Object viewerObj = mainSection != null
            ? Global.invoke(mainSection, "getEventHandlersTreeViewer") : null; //$NON-NLS-1$
        return viewerObj instanceof TreeViewer viewer ? viewer.getTree() : null;
    }

    // =========================================================================
    // Контекстное меню
    // =========================================================================

    private static void installMenuItem(Menu menu, Object editor, TreeViewer viewer)
    {
        // MenuManager на SWT.Show пересобирает меню и удаляет чужие пункты (его слушатель
        // добавлен раньше нашего) — поэтому пункты создаются заново при каждом показе.
        menu.addMenuListener(new MenuAdapter()
        {
            private MenuItem openHandlerItem;

            private MenuItem openLinkItem;

            @Override
            public void menuShown(MenuEvent e)
            {
                openHandlerItem = disposeItem(openHandlerItem);
                openLinkItem = disposeItem(openLinkItem);
                if (menu.isDisposed())
                    return;

                Object element = selectedElement(viewer);
                int index = 0;
                if (isHandlerElement(element))
                {
                    openHandlerItem = createItem(menu, index++, ITEM_TEXT, ITEM_TOOLTIP,
                        () -> openSelectedHandler(editor, viewer));
                    menu.setDefaultItem(openHandlerItem);
                }
                if (element instanceof EventSubscription)
                {
                    openLinkItem = createItem(menu, index, ITEM_TEXT_LINK, ITEM_TOOLTIP_LINK,
                        () -> OpenSourceLinkFlow.run(editor, viewer));
                }
            }
        });
    }

    private static MenuItem disposeItem(MenuItem item)
    {
        if (item != null && !item.isDisposed())
            item.dispose();
        return null;
    }

    private static MenuItem createItem(Menu menu, int index, String text, String tooltip, Runnable action)
    {
        MenuItem item = new MenuItem(menu, SWT.PUSH, index);
        item.setText(text);
        item.setToolTipText(tooltip);
        item.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent event)
            {
                action.run();
            }
        });
        return item;
    }

    // =========================================================================
    // Двойной клик
    // =========================================================================

    private static void installDoubleClick(Object editor, TreeViewer viewer)
    {
        if (!removeStockDoubleClickListeners(viewer))
        {
            Global.tempLog(HANDLER_LOG_TOPIC,
                "installDoubleClick: штатный слушатель не снят — свой не ставим"); //$NON-NLS-1$
            return;
        }
        Global.tempLog(HANDLER_LOG_TOPIC, "installDoubleClick: свой слушатель установлен, хозяин=" //$NON-NLS-1$
            + (hostEditors.get(editor) == null ? "нет" : "есть")); //$NON-NLS-1$ //$NON-NLS-2$
        viewer.addDoubleClickListener(new IDoubleClickListener()
        {
            @Override
            public void doubleClick(DoubleClickEvent event)
            {
                openSelectedHandler(editor, viewer);
            }
        });
    }

    /**
     * @return {@code true}, если список слушателей прочитан (штатные сняты либо их не было);
     * {@code false} — прочитать не удалось, ставить свой слушатель нельзя.
     */
    private static boolean removeStockDoubleClickListeners(TreeViewer viewer)
    {
        Object listeners = Global.getField(viewer, "doubleClickListeners"); //$NON-NLS-1$
        if (!(listeners instanceof Iterable<?> iterable))
        {
            Object[] array = listeners != null
                ? (Object[])Global.invoke(listeners, "getListeners") : null; //$NON-NLS-1$
            if (array == null)
                return false;
            for (Object listener : array)
                removeIfStock(viewer, listener);
            return true;
        }
        for (Object listener : iterable)
            removeIfStock(viewer, listener);
        return true;
    }

    private static void removeIfStock(TreeViewer viewer, Object listener)
    {
        if (listener instanceof IDoubleClickListener doubleClick
            && STOCK_DOUBLE_CLICK_CLASS.equals(listener.getClass().getName()))
            viewer.removeDoubleClickListener(doubleClick);
    }

    // =========================================================================
    // Открытие обработчика
    // =========================================================================

    private static Object selectedElement(TreeViewer viewer)
    {
        if (viewer == null || viewer.getControl() == null || viewer.getControl().isDisposed())
            return null;
        IStructuredSelection selection = viewer.getStructuredSelection();
        return selection != null ? selection.getFirstElement() : null;
    }

    /** Узел, у которого вообще есть обработчик: подписка или метод-переопределение. */
    private static boolean isHandlerElement(Object element)
    {
        return element instanceof EventSubscription
            || (element != null && METHOD_CONTAINER_CLASS.equals(element.getClass().getName()));
    }

    private static void openSelectedHandler(Object editor, TreeViewer viewer)
    {
        try
        {
            Object element = selectedElement(viewer);
            // Лог до разбора строки: если разбор упадёт, по этой записи будет видно,
            // что действие вообще вызывалось (в прошлый раз лога не было вовсе).
            Global.tempLog(HANDLER_LOG_TOPIC, "openSelectedHandler: строка=" //$NON-NLS-1$
                + (element == null ? "null" : element.getClass().getName())); //$NON-NLS-1$
            Method method = resolveHandlerMethod(editor, element);
            Global.tempLog(HANDLER_LOG_TOPIC, "openSelectedHandler: метод=" //$NON-NLS-1$
                + (method == null ? "не найден" : method.getName())); //$NON-NLS-1$
            if (method == null)
                return;
            if (revealInHostEditor(editor, method))
                return;
            openMethod(method);
            Global.tempLog(HANDLER_LOG_TOPIC, "openSelectedHandler: открыт редактор метода"); //$NON-NLS-1$
        }
        catch (Exception | LinkageError e)
        {
            Global.tempLogException(HANDLER_LOG_TOPIC, "openSelectedHandler", e); //$NON-NLS-1$
            Global.logError(TAG, "openSelectedHandler", e); //$NON-NLS-1$
        }
    }

    private static Method resolveHandlerMethod(Object editor, Object element)
    {
        if (element == null)
            return null;
        if (METHOD_CONTAINER_CLASS.equals(element.getClass().getName()))
        {
            Object method = Global.invoke(element, "getMethod"); //$NON-NLS-1$
            return method instanceof Method bslMethod ? bslMethod : null;
        }
        if (element instanceof EventSubscription subscription)
            return resolveSubscriptionHandler(editor, subscription);
        return null;
    }

    /**
     * Свойство «Обработчик» подписки — строка вида {@code CommonModule.<Модуль>.<Процедура>}
     * (тип модуля в ней всегда английский, как в XML конфигурации).
     */
    private static Method resolveSubscriptionHandler(Object editor, EventSubscription subscription)
    {
        String handler = subscription.getHandler();
        if (handler == null || handler.isBlank())
            return null;

        int lastDot = handler.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == handler.length() - 1)
            return null;
        String moduleFqn = handler.substring(0, lastDot);
        String methodName = handler.substring(lastDot + 1);
        if (!moduleFqn.startsWith(COMMON_MODULE_PREFIX))
            return null;

        Object bmModelObj = Global.invoke(editor, "getBmModel"); //$NON-NLS-1$
        if (!(bmModelObj instanceof IBmModel bmModel))
            return null;
        IBmEngine engine = bmModel.getEngine();
        if (engine == null)
            return null;

        IBmObject topObject = engine.getTopObjectByFqn(moduleFqn);
        if (!(topObject instanceof CommonModule commonModule))
            return null;
        Module module = commonModule.getModule();
        if (module == null)
            return null;

        for (Method method : module.allMethods())
        {
            if (method != null && methodName.equalsIgnoreCase(method.getName()))
                return method;
        }
        return null;
    }

    /**
     * Обработчик принадлежит модулю того же объекта, в редактор которого встроена панель:
     * открывать этот редактор заново не нужно — переходим к процедуре прямо в нём
     * ({@code DtGranularEditor.selectReveal} → {@code gotoSelection}: активирует страницу
     * того свойства объекта, в котором лежит модуль метода, и показывает выделение).
     *
     * @return {@code true}, если переход выполнен в уже открытом редакторе.
     */
    private static boolean revealInHostEditor(Object editor, Method method)
    {
        Object host = hostEditors.get(editor);
        if (host == null)
        {
            Global.tempLog(HANDLER_LOG_TOPIC, "revealInHostEditor: редактор-хозяин не зарегистрирован" //$NON-NLS-1$
                + " (отдельная панель или patchEditor без него)"); //$NON-NLS-1$
            return false;
        }

        Module module = containingModule(method);
        Object owner = module != null ? module.getOwner() : null;
        Object model = Global.invoke(host, "getModel"); //$NON-NLS-1$
        Global.tempLog(HANDLER_LOG_TOPIC, "revealInHostEditor: владелец модуля=" + uriOf(owner) //$NON-NLS-1$
            + ", модель редактора=" + uriOf(model)); //$NON-NLS-1$
        if (owner == null || model == null || !isSameObject(owner, model))
            return false;

        Object result = Global.invoke(host, "selectReveal", new StructuredSelection(method)); //$NON-NLS-1$
        Global.tempLog(HANDLER_LOG_TOPIC, "revealInHostEditor: selectReveal выполнен, результат=" + result); //$NON-NLS-1$
        return true;
    }

    private static String uriOf(Object object)
    {
        if (!(object instanceof EObject eObject))
            return String.valueOf(object);
        URI uri = EcoreUtil.getURI(eObject);
        return uri != null ? uri.toString() : eObject.getClass().getName();
    }

    private static Module containingModule(Method method)
    {
        for (EObject current = method; current != null; current = current.eContainer())
            if (current instanceof Module module)
                return module;
        return null;
    }

    /** Экземпляры могут быть из разных сессий модели — сверяем ещё и по URI. */
    private static boolean isSameObject(Object first, Object second)
    {
        if (first == second)
            return true;
        if (!(first instanceof EObject a) || !(second instanceof EObject b))
            return false;
        URI uriA = EcoreUtil.getURI(a);
        return uriA != null && uriA.equals(EcoreUtil.getURI(b));
    }

    /** Как штатный {@code OpenObjectHandler.openMethodEditor}: opener языка BSL по URI метода. */
    private static void openMethod(Method method)
    {
        IResourceServiceProvider provider = IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(URI.createURI("*.bsl")); //$NON-NLS-1$
        if (provider == null)
            return;
        IURIEditorOpener opener = provider.get(IURIEditorOpener.class);
        if (opener == null)
            return;
        opener.open(EcoreUtil.getURI(method), true);
    }

    /**
     * Сценарий «Открыть связь»: редактор подписки → диалог «Редактирование типа данных»
     * поля «Источник» → активная строка текущего объекта.
     *
     * <p>Путь повторяет штатный (согласовано с пользователем): диалог открывает сам
     * {@code TypeDescriptionComponent} по клику своей кнопки «…» — мы лишь отправляем этой
     * кнопке тот же {@code ClickEvent}, что шлёт SWT-рендерер, поэтому дальше работает
     * штатная цепочка (создание модели диалога, {@code IEngine.showDialog} и возврат
     * выбранного типа в редактор). Пакеты AEF плагином не импортируются — работа рефлексией.
     *
     * <p>«Текущий объект» — сперва источники активного отбора панели
     * ({@code EventHandlersFilter.getSources()}: на встроенной странице это производные типы
     * её объекта), иначе первый тип из свойства «Источник» самой подписки.
     *
     * <p>Каждый шаг пишется в {@code .tmp/temp-logs/eventhandlers-open-link.log} безусловно:
     * шагов много и все они зависят от вёрстки чужого редактора/диалога.
     */
    private static final class OpenSourceLinkFlow
    {
        private static final String TYPE_DESCRIPTION_COMPONENT_CLASS =
            "com._1c.g5.v8.dt.md.ui.aef.components.type.TypeDescriptionComponent"; //$NON-NLS-1$

        private static final String AEF_STANDARD_BUNDLE = "com._1c.g5.aef2.standard"; //$NON-NLS-1$

        private static final String CLICK_EVENT_CLASS =
            "com._1c.g5.aef2.standard.events.ClickEvent"; //$NON-NLS-1$

        private static final int STEP_MS = 150;

        private static final int STEP_ATTEMPTS = 40;

        private static final int MAX_TREE_DEPTH = 5;

        /** Попытка, на которой делается разовый дамп дерева компонентов (страница уже отрисована). */
        private static final int DUMP_ATTEMPT = 10;

        private static final int DUMP_LINE_LIMIT = 300;

        /** Моменты проверки, что фокус остался в поле «Источник», мс от активации. */
        private static final int[] FOCUS_RECHECK_DELAYS = { 200, 500, 1000, 1800 };

        private OpenSourceLinkFlow() {}

        static void run(Object editor, TreeViewer viewer)
        {
            try
            {
                if (!(selectedElement(viewer) instanceof EventSubscription subscription))
                    return;

                List<String> targets = resolveTargetTypeNames(editor, subscription);
                Global.tempLog(LINK_LOG_TOPIC, "run: подписка=" + subscription.getName() //$NON-NLS-1$
                    + ", целевые типы=" + targets); //$NON-NLS-1$

                IEditorPart part = new OpenHelper().openEditor(subscription);
                if (part == null)
                {
                    Global.tempLog(LINK_LOG_TOPIC, "run: редактор подписки не открылся"); //$NON-NLS-1$
                    return;
                }
                scheduleOpenTypeDialog(part, subscription, targets, 0);
            }
            catch (Exception | LinkageError e)
            {
                Global.tempLogException(LINK_LOG_TOPIC, "run", e); //$NON-NLS-1$
                Global.logError(TAG, "openSourceLink", e); //$NON-NLS-1$
            }
        }

        // -----------------------------------------------------------------
        // Шаг 1. Кнопка «…» поля «Источник» в редакторе подписки
        // -----------------------------------------------------------------

        private static void scheduleOpenTypeDialog(IEditorPart part, EventSubscription subscription,
            List<String> targets, int attempt)
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed() || attempt >= STEP_ATTEMPTS)
            {
                if (attempt >= STEP_ATTEMPTS)
                    Global.tempLog(LINK_LOG_TOPIC, "поле «Источник» не найдено за " //$NON-NLS-1$
                        + STEP_ATTEMPTS + " попыток"); //$NON-NLS-1$
                return;
            }

            display.timerExec(attempt == 0 ? 0 : STEP_MS, () ->
            {
                // Кнопку «…» создаёт не сам TypeDescriptionComponent, а его внутренний
                // combo-компонент в createButtons() — то есть только при отрисовке панели
                // действий поля. До этого поле selectButton пустое (подтверждено логом
                // первой версии: компонент найден на попытке 0, кнопки ещё нет), поэтому
                // ждём именно кнопку с каналом событий, а не сам компонент.
                Object[] buttonAndChannel = findSelectButton(part, attempt);
                if (buttonAndChannel == null)
                {
                    // Разовый снимок дерева компонентов и их view model: по логу видно, что
                    // TypeDescriptionComponent найден, но кнопка у него не появляется — значит
                    // «…» создаёт другой компонент/другая ветка createComponents. Дамп показывает,
                    // какая именно, без догадок.
                    if (attempt == DUMP_ATTEMPT)
                        dumpComponentTree(part);
                    scheduleOpenTypeDialog(part, subscription, targets, attempt + 1);
                    return;
                }

                Object sourceComponent = buttonAndChannel[3];
                selectSourceField(buttonAndChannel[2], sourceComponent, subscription);

                List<Shell> before = List.of(display.getShells());
                if (!fireSelectButtonClick(buttonAndChannel[0], buttonAndChannel[1]))
                    return;
                scheduleSelectTypeRow(targets, before, buttonAndChannel[2], sourceComponent,
                    subscription, 0);
            });
        }

        /**
         * Кнопка «…» поля «Источник» и канал, в который надо слать по ней клик.
         *
         * <p>По дампу компонентов: у самой {@code ButtonItemViewModel} кнопки
         * {@code eventChannel} равен {@code null} (тултип «Выбрать», contextId
         * {@code …commands.dt.select.select}) — ждать непустой канал у view model было
         * ошибкой. Слушателя кнопок ({@code buttonListener}) {@code TypeDescriptionComponent}
         * вешает на свой ДОЧЕРНИЙ компонент панели действий ({@code TypeDescriptionComponent$6}
         * — {@code AbstractDtListActionBarComponent} для составного типа,
         * {@code $3} — combo для одиночного), а компонент сам является {@code IEventChannel}.
         *
         * @return {@code [кнопка, канал]} или {@code null}, если кнопка ещё не создана.
         */
        private static Object[] findSelectButton(IEditorPart part, int attempt)
        {
            List<Object> pages = new ArrayList<>();
            Object active = Global.invoke(part, "getActivePageInstance"); //$NON-NLS-1$
            if (active != null)
                pages.add(active);
            Object all = Global.getField(part, "pages"); //$NON-NLS-1$
            if (all instanceof Iterable<?> iterable)
                for (Object page : iterable)
                    if (page != null && !pages.contains(page))
                        pages.add(page);

            int componentsFound = 0;
            for (Object page : pages)
            {
                List<Object> components = new ArrayList<>();
                collectComponents(Global.getField(page, "pageComponent"), components, 0); //$NON-NLS-1$
                componentsFound += components.size();
                for (Object component : components)
                {
                    Object button = Global.getField(component, "selectButton"); //$NON-NLS-1$
                    if (button == null)
                        continue;
                    Object channel = findButtonChannel(component);
                    Global.tempLog(LINK_LOG_TOPIC, "кнопка «…» поля «Источник» готова, попытка=" //$NON-NLS-1$
                        + attempt + ", канал=" + channel.getClass().getName()); //$NON-NLS-1$
                    return new Object[] { button, channel, page, component };
                }
            }
            Global.tempLog(LINK_LOG_TOPIC, "ожидание кнопки «…»: попытка=" + attempt //$NON-NLS-1$
                + ", страниц=" + pages.size() + ", компонентов типа=" + componentsFound); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        /**
         * Разовый дамп: страницы редактора, дерево их AEF-компонентов и view model каждого
         * компонента (класс + текст/подсказка, если есть). Нужен, чтобы найти реальный
         * компонент и view model кнопки «…» поля «Источник».
         */
        private static void dumpComponentTree(IEditorPart part)
        {
            try
            {
                Global.tempLog(LINK_LOG_TOPIC, "=== дамп компонентов редактора подписки ==="); //$NON-NLS-1$
                List<Object> pages = new ArrayList<>();
                Object active = Global.invoke(part, "getActivePageInstance"); //$NON-NLS-1$
                if (active != null)
                    pages.add(active);
                Object all = Global.getField(part, "pages"); //$NON-NLS-1$
                if (all instanceof Iterable<?> iterable)
                    for (Object page : iterable)
                        if (page != null && !pages.contains(page))
                            pages.add(page);

                for (Object page : pages)
                {
                    Object root = Global.getField(page, "pageComponent"); //$NON-NLS-1$
                    Global.tempLog(LINK_LOG_TOPIC, "страница " + page.getClass().getName() //$NON-NLS-1$
                        + ", pageComponent=" + (root == null ? "null" : root.getClass().getName())); //$NON-NLS-1$ //$NON-NLS-2$
                    dumpComponent(root, 0, new int[] { 0 });
                }
                Global.tempLog(LINK_LOG_TOPIC, "=== конец дампа ==="); //$NON-NLS-1$
            }
            catch (Exception | LinkageError e)
            {
                Global.tempLogException(LINK_LOG_TOPIC, "dumpComponentTree", e); //$NON-NLS-1$
            }
        }

        private static void dumpComponent(Object component, int depth, int[] budget)
        {
            if (component == null || depth > 20 || budget[0] > DUMP_LINE_LIMIT)
                return;
            budget[0]++;

            StringBuilder line = new StringBuilder();
            line.append("  ".repeat(depth)).append(component.getClass().getName()); //$NON-NLS-1$
            if (TYPE_DESCRIPTION_COMPONENT_CLASS.equals(component.getClass().getName()))
            {
                line.append(" [selectButton=").append(Global.getField(component, "selectButton")) //$NON-NLS-1$ //$NON-NLS-2$
                    .append(", openButton=").append(Global.getField(component, "openButton")) //$NON-NLS-1$ //$NON-NLS-2$
                    .append(", createWithField=").append(Global.getField(component, "createWithField")) //$NON-NLS-1$ //$NON-NLS-2$
                    .append(']');
            }
            Object viewModels = Global.invoke(component, "getViewModels"); //$NON-NLS-1$
            if (viewModels instanceof Iterable<?> iterable)
            {
                for (Object viewModel : iterable)
                {
                    if (viewModel == null)
                        continue;
                    line.append("\n").append("  ".repeat(depth + 1)).append("vm: ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        .append(viewModel.getClass().getName());
                    for (String getter : new String[] { "getText", "getTooltip", "getContextId" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    {
                        Object value = Global.invoke(viewModel, getter);
                        if (value instanceof String text && !text.isBlank())
                            line.append(' ').append(getter).append('=').append(text);
                    }
                }
            }
            Global.tempLog(LINK_LOG_TOPIC, line.toString());

            Object children = Global.invoke(component, "getComponents"); //$NON-NLS-1$
            if (children instanceof Iterable<?> iterable)
                for (Object child : iterable)
                    dumpComponent(child, depth + 1, budget);
        }

        private static void collectComponents(Object component, List<Object> result, int depth)
        {
            if (component == null || depth > 20)
                return;
            if (TYPE_DESCRIPTION_COMPONENT_CLASS.equals(component.getClass().getName()))
                result.add(component);
            Object children = Global.invoke(component, "getComponents"); //$NON-NLS-1$
            if (children instanceof Iterable<?> iterable)
                for (Object child : iterable)
                    collectComponents(child, result, depth + 1);
        }

        /**
         * Активирует поле «Источник» на странице редактора подписки.
         *
         * <p>{@code setSelection(feature, value)} страницы только выделяет значение внутри
         * контрола ({@code ClientSetSelectionEvent}) — по логу он отрабатывал («ок»), а поле
         * оставалось неактивным. Само переключение ввода на нужное свойство штатная страница
         * делает {@link AefFieldFocus} — тот же механизм, что активирует поле в панели
         * «Свойства» ({@code ConfigSearchResultsHook.PropertyFieldFocus}). Попытки слать
         * {@code ClientFocusEvent} (сцене с ключом-свойством или напрямую компоненту) ничего
         * не меняли — см. javadoc {@link AefFieldFocus}.
         */
        private static void selectSourceField(Object page, Object sourceComponent,
            EventSubscription subscription)
        {
            if (sourceComponent == null)
                return;
            try
            {
                if (page != null)
                    Global.invokeVoid(page, "setSelection", //$NON-NLS-1$
                        MdClassPackage.Literals.EVENT_SUBSCRIPTION__SOURCE, subscription.getSource());

                Object scene = Global.invoke(sourceComponent, "getScene"); //$NON-NLS-1$
                if (scene == null && page != null)
                    scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
                Object rootComponent = page != null ? Global.getField(page, "pageComponent") : null; //$NON-NLS-1$
                boolean focused = AefFieldFocus.focusComponent(scene, sourceComponent);
                Global.tempLog(LINK_LOG_TOPIC, "активация поля «Источник»: компонент=" //$NON-NLS-1$
                    + sourceComponent.getClass().getName() + ", фокус=" + focused); //$NON-NLS-1$
                // EDT при открытии редактора выделяет текст в «Имя» — без снятия выделения
                // подсвеченными выглядят сразу два поля. Первый вызов с логом: какие контролы
                // нашлись (SWT или LWT) и удалось ли снять выделение.
                AefFieldFocus.clearSelectionInUnfocusedFields(scene, rootComponent, LINK_LOG_TOPIC);
                // Собственная инициализация страницы редактора отрабатывает позже и уводит ввод
                // обратно на первое поле («Имя»). Проверяем и возвращаем фокус, пока он сбит —
                // ограниченным числом проверок, а не постоянным перехватом.
                scheduleFocusRecheck(scene, sourceComponent, rootComponent, 0);
            }
            catch (Exception | LinkageError e)
            {
                Global.tempLogException(LINK_LOG_TOPIC, "selectSourceField", e); //$NON-NLS-1$
            }
        }

        /**
         * Проверки фокуса после активации поля: страница редактора доводит свою инициализацию
         * асинхронно и переводит ввод на первое поле уже после нашего вызова. Каждая проверка
         * возвращает фокус только если он действительно ушёл; проверок ограниченное число.
         */
        private static void scheduleFocusRecheck(Object scene, Object sourceComponent,
            Object rootComponent, int attempt)
        {
            if (attempt >= FOCUS_RECHECK_DELAYS.length)
                return;
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;

            display.timerExec(FOCUS_RECHECK_DELAYS[attempt], () ->
            {
                if (!AefFieldFocus.isComponentFocused(scene, sourceComponent))
                {
                    boolean focused = AefFieldFocus.focusComponent(scene, sourceComponent);
                    Global.tempLog(LINK_LOG_TOPIC, "возврат фокуса на «Источник»: проверка=" //$NON-NLS-1$
                        + attempt + ", фокус=" + focused); //$NON-NLS-1$
                }
                AefFieldFocus.clearSelectionInUnfocusedFields(scene, rootComponent);
                scheduleFocusRecheck(scene, sourceComponent, rootComponent, attempt + 1);
            });
        }

        /**
         * Компонент панели действий, которому {@code TypeDescriptionComponent} отдал
         * {@code buttonListener} — это его дочерний анонимный компонент
         * ({@code TypeDescriptionComponent$N}). Если такого нет, шлём событие самому
         * {@code TypeDescriptionComponent} (тоже {@code IEventChannel}).
         */
        private static Object findButtonChannel(Object typeDescriptionComponent)
        {
            Object children = Global.invoke(typeDescriptionComponent, "getComponents"); //$NON-NLS-1$
            if (children instanceof Iterable<?> iterable)
            {
                for (Object child : iterable)
                {
                    if (child != null
                        && child.getClass().getName().startsWith(TYPE_DESCRIPTION_COMPONENT_CLASS + '$'))
                        return child;
                }
            }
            return typeDescriptionComponent;
        }

        /** Тот же {@code ClickEvent} по кнопке «…», что отправляет SWT-рендерер AEF. */
        private static boolean fireSelectButtonClick(Object selectButton, Object channel)
        {
            try
            {
                Bundle bundle = Platform.getBundle(AEF_STANDARD_BUNDLE);
                if (bundle == null)
                {
                    Global.tempLog(LINK_LOG_TOPIC, "бандл " + AEF_STANDARD_BUNDLE + " не найден"); //$NON-NLS-1$ //$NON-NLS-2$
                    return false;
                }
                Class<?> clickEventClass = bundle.loadClass(CLICK_EVENT_CLASS);
                Object event = clickEventClass.getConstructors()[0].newInstance(selectButton);
                Global.invoke(channel, "queueEvent", event); //$NON-NLS-1$
                Global.tempLog(LINK_LOG_TOPIC, "клик по кнопке «…» отправлен"); //$NON-NLS-1$
                return true;
            }
            catch (Exception | LinkageError e)
            {
                Global.tempLogException(LINK_LOG_TOPIC, "fireSelectButtonClick", e); //$NON-NLS-1$
                return false;
            }
        }

        // -----------------------------------------------------------------
        // Шаг 2. Строка текущего объекта в открывшемся диалоге
        // -----------------------------------------------------------------

        private static void scheduleSelectTypeRow(List<String> targets, List<Shell> before, Object page,
            Object sourceComponent, EventSubscription subscription, int attempt)
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed() || attempt >= STEP_ATTEMPTS)
            {
                if (attempt >= STEP_ATTEMPTS)
                    Global.tempLog(LINK_LOG_TOPIC, "строка типа не активирована за " //$NON-NLS-1$
                        + STEP_ATTEMPTS + " попыток"); //$NON-NLS-1$
                return;
            }

            display.timerExec(STEP_MS, () ->
            {
                Shell dialog = findNewShell(display, before);
                if (dialog == null || !selectTypeRow(dialog, targets))
                {
                    scheduleSelectTypeRow(targets, before, page, sourceComponent, subscription,
                        attempt + 1);
                    return;
                }
                Global.tempLog(LINK_LOG_TOPIC, "строка типа активирована, попытка=" + attempt); //$NON-NLS-1$
                // Модальный диалог открывается сразу после нашего запроса фокуса и забирает
                // ввод себе — повторяем активацию поля, когда диалог закрыт.
                dialog.addDisposeListener(e -> display.asyncExec(
                    () -> selectSourceField(page, sourceComponent, subscription)));
            });
        }

        private static Shell findNewShell(Display display, List<Shell> before)
        {
            for (Shell shell : display.getShells())
            {
                if (shell == null || shell.isDisposed() || !shell.isVisible() || before.contains(shell))
                    continue;
                return shell;
            }
            return null;
        }

        private static boolean selectTypeRow(Shell dialog, List<String> targets)
        {
            if (targets.isEmpty())
                return true; // диалог открыт, выделять нечего
            Tree tree = findTree(dialog);
            if (tree == null || tree.isDisposed())
                return false;

            TreeViewer viewer = findTreeViewer(tree, dialog);
            if (viewer == null || !(viewer.getContentProvider() instanceof ITreeContentProvider tcp))
            {
                Global.tempLog(LINK_LOG_TOPIC, "TreeViewer диалога не найден"); //$NON-NLS-1$
                return false;
            }

            Object[] roots = tcp.getElements(viewer.getInput());
            if (roots == null || roots.length == 0)
                return false;
            Object element = findElement(tcp, roots, targets, 0);
            if (element == null)
                return false;

            viewer.setSelection(new StructuredSelection(element), true);
            tree.setFocus();
            // Фильтр «Только помеченные» диалога применяется асинхронно и может прокрутить
            // дерево уже после нашего выбора — возвращаем строку в видимую область следом
            // (сам SmartOutlineHook делает то же со своей стороны).
            for (int delay : new int[] { 150, 400, 800 })
                tree.getDisplay().timerExec(delay, () ->
                {
                    if (!tree.isDisposed() && tree.getSelectionCount() > 0)
                        tree.showSelection();
                });
            return true;
        }

        private static Object findElement(ITreeContentProvider tcp, Object[] elements, List<String> targets,
            int depth)
        {
            if (elements == null || depth > MAX_TREE_DEPTH)
                return null;
            for (Object element : elements)
            {
                if (element == null)
                    continue;
                if (matchesTarget(element, targets))
                    return element;
                Object found = findElement(tcp, tcp.getChildren(element), targets, depth + 1);
                if (found != null)
                    return found;
            }
            return null;
        }

        private static boolean matchesTarget(Object element, List<String> targets)
        {
            for (String candidate : candidateNames(element))
            {
                for (String target : targets)
                {
                    if (candidate.equalsIgnoreCase(target)
                        || candidate.equalsIgnoreCase(lastSegment(target)))
                        return true;
                }
            }
            return false;
        }

        /** Имена узла дерева типов: сам узел и вложенный в него {@code TypeItem}, если есть. */
        private static List<String> candidateNames(Object element)
        {
            List<String> result = new ArrayList<>(4);
            addNames(element, result);
            for (String getter : new String[] { "getTypeItem", "getType", "getValue" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                addNames(Global.invoke(element, getter), result);
            return result;
        }

        private static void addNames(Object object, List<String> result)
        {
            if (object == null)
                return;
            for (String getter : new String[] { "getName", "getNameRu", "getPresentation", "getText" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {
                Object value = Global.invoke(object, getter);
                if (value instanceof String text && !text.isBlank() && !result.contains(text))
                    result.add(text);
            }
        }

        private static Tree findTree(Control control)
        {
            if (control instanceof Tree tree)
                return tree;
            if (control instanceof Composite composite)
            {
                for (Control child : composite.getChildren())
                {
                    Tree found = findTree(child);
                    if (found != null)
                        return found;
                }
            }
            return null;
        }

        /** Как {@code SmartOutlineHook.findTreeViewer}: сам виджет, предки, затем слушатели дерева. */
        private static TreeViewer findTreeViewer(Tree tree, Shell shell)
        {
            for (Composite parent = tree.getParent(); parent != null; parent = parent.getParent())
            {
                for (String getter : new String[] { "getTreeViewer", "getViewer" }) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    Object viewer = Global.invoke(parent, getter);
                    if (viewer instanceof TreeViewer treeViewer)
                        return treeViewer;
                }
                for (String field : new String[] { "treeViewer", "viewer" }) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    Object viewer = Global.getField(parent, field);
                    if (viewer instanceof TreeViewer treeViewer)
                        return treeViewer;
                }
                if (parent == shell)
                    break;
            }
            for (int eventType : new int[] { SWT.Selection, SWT.Expand, SWT.Collapse })
            {
                for (Listener listener : tree.getListeners(eventType))
                {
                    for (String field : new String[] { "this$0", "viewer", "treeViewer" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    {
                        Object viewer = Global.getField(listener, field);
                        if (viewer instanceof TreeViewer treeViewer)
                            return treeViewer;
                    }
                }
            }
            return null;
        }

        // -----------------------------------------------------------------
        // «Текущий объект»
        // -----------------------------------------------------------------

        private static List<String> resolveTargetTypeNames(Object editor, EventSubscription subscription)
        {
            List<String> names = new ArrayList<>();

            Object mainSection = Global.invoke(editor, "getMainSection"); //$NON-NLS-1$
            Object filter = mainSection != null
                ? Global.invoke(mainSection, "getEventHandlersFilter") : null; //$NON-NLS-1$
            Object sources = filter != null ? Global.invoke(filter, "getSources") : null; //$NON-NLS-1$
            if (sources instanceof Collection<?> collection)
                for (Object source : collection)
                    addTypeNames(source, names);

            if (names.isEmpty())
            {
                TypeDescription source = subscription.getSource();
                if (source != null)
                    for (TypeItem type : source.getTypes())
                        addTypeNames(type, names);
            }
            return names;
        }

        private static void addTypeNames(Object type, List<String> names)
        {
            if (!(type instanceof TypeItem typeItem))
                return;
            for (String name : new String[] { typeItem.getName(), typeItem.getNameRu() })
                if (name != null && !name.isBlank() && !names.contains(name))
                    names.add(name);
        }

        private static String lastSegment(String name)
        {
            int dot = name.lastIndexOf('.');
            return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : name;
        }
    }

}
