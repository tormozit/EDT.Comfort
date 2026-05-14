package tormozit.edt.menu;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextService;

import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;

import tormozit.edt.assist.ContentAssistAutoOpenManager;
import tormozit.edt.handlers.ExpandExceptAddedDeletedHandler;
import tormozit.edt.handlers.ExpandMode;
import tormozit.edt.handlers.OpenObjectHandler;
import tormozit.edt.search.CompareSearchDialogHook;
import tormozit.edt.selection.CompareEditorSelectionProvider;

/**
 * Добавляет пункт «Открыть объект» в контекстное меню дерева сравнения EDT.
 *
 * EDT строит это меню напрямую через SWT, минуя getSite().registerContextMenu(),
 * поэтому декларативные механизмы Eclipse (popup:any, CompoundContributionItem)
 * его не видят. Единственный рабочий способ — подключиться к SWT Menu через
 * MenuListener.
 *
 * Порядок срабатывания:
 *   1. EDT регистрирует свой SWT MenuListener при создании дерева (в createPartControl).
 *   2. Мы регистрируем свой в partOpened — т.е. позже, значит в очереди ПОСЛЕ EDT.
 *   3. При каждом открытии меню: сначала EDT наполняет его своими пунктами,
 *      затем наш menuShown добавляет «Открыть объект» в конец — только для
 *      MatchedObjectsComparisonNode (объектов конфигурации, не свойств).
 *   4. В menuHidden мы сами удаляем добавленные пункты — EDT о них не знает.
 */
public class CompareEditorMenuHook implements IStartup {

    private static final String COMPARE_EDITOR_ID = "com._1c.g5.v8.dt.compare.ui.editor";
    private static final String CONTEXT_ID        = "tormozit.edt.compareConf.context";
//    private static final String COMMAND_ID_OpenObject = "tormozit.edt.openObject";
    private static final String ITEM_TEXT_OpenObject = "Открыть объект \tF2";
//    private static final String COMMAND_ID_showInNavigator = "tormozit.edt.showInNavigator";
    private static final String ITEM_TEXT_showInNavigator = "Показать в навигаторе \tCTRL+T";

    // ---- IStartup ----

    @Override
    public void earlyStartup() {
        Display.getDefault().asyncExec(() -> {
            // Патч диалога поиска CTRL+F: добавляет флажок "Только имена объектов"
            CompareSearchDialogHook.install(Display.getDefault());

            IWorkbench wb = PlatformUI.getWorkbench();
            for (IWorkbenchWindow w : wb.getWorkbenchWindows()) {
                hookWindow(w);
            }
            wb.addWindowListener(new IWindowListener() {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });
            ContentAssistAutoOpenManager mgr = ContentAssistAutoOpenManager.getInstance();
            if (mgr != null)
                mgr.start();
        });
    }

    // ---- Подключение к окну / редактору ----

    private void hookWindow(IWorkbenchWindow window) {
        // Редакторы, открытые до нашей загрузки (восстановление сессии)
        IWorkbenchPage page = window.getActivePage();
        if (page != null) {
            for (IEditorReference ref : page.getEditorReferences()) {
                IEditorPart ed = ref.getEditor(false);
                if (ed != null && isCompareEditor(ed)) hookEditor(ed);
            }
        }
        // Все будущие редакторы
        window.getPartService().addPartListener(new IPartListener2() {
            @Override
            public void partOpened(IWorkbenchPartReference ref) {
                if (!(ref instanceof IEditorReference)) return;
                IEditorPart ed = ((IEditorReference) ref).getEditor(false);
                if (ed != null && isCompareEditor(ed)) hookEditor(ed);
            }
            @Override public void partActivated(IWorkbenchPartReference r)    {}
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private boolean isCompareEditor(IEditorPart editor) {
//        String message = "editor=" + editor.getClass().getSimpleName() + "-" + editor.getSite().getId();
//        IStatus status = new Status(IStatus.INFO, "tormozit.edt", message);
//        ILog log = Platform.getLog(FrameworkUtil.getBundle(CompareEditorMenuHook.class));
//        log.log(status);
        return COMPARE_EDITOR_ID.equals(editor.getSite().getId());
    }

    private void hookEditor(IEditorPart editor) {
        IContextService cs = editor.getSite().getService(IContextService.class);
        if (cs != null) cs.activateContext(CONTEXT_ID);

        // Добавляем кнопку в тулбар EDT с повторными попытками
        addToolbarButtonWithRetry(editor);

        // Глобальное оповещение об активации объекта:
        // ВАЖНО: setSelectionProvider вызывается СИНХРОННО здесь (в partOpened),
        // чтобы workbench успел подключить свой ISelectionChangedListener
        // в partActivated (который идёт следом). Если вызвать через asyncExec —
        // workbench уже подключится к старому провайдеру и listeners останется пустым.
        CompareEditorSelectionProvider selProvider = new CompareEditorSelectionProvider(editor);
        editor.getSite().setSelectionProvider(selProvider);

        
        // Tree viewer ещё не создан в partOpened — подключаем асинхронно
        wireTreeViewerToProvider(editor, selProvider);

        Tree tree = getCompareTree(editor);
        if (tree == null) {
            Display.getDefault().asyncExec(() -> {
                Tree t = getCompareTree(editor);
                if (t != null) attachMenuListener(editor, t);
            });
            return;
        }
        attachMenuListener(editor, tree);
    }

    /**
     * Подключает tree viewer к уже установленному провайдеру асинхронно.
     * К этому моменту workbench уже подключился к провайдеру через
     * {@code addSelectionChangedListener}, поэтому события дерева
     * будут правильно доставляться через {@code ISelectionService}.
     */
    private void wireTreeViewerToProvider(IEditorPart editor, CompareEditorSelectionProvider provider)
    {
        Display.getDefault().asyncExec(() ->
        {
            AbstractTreeViewer viewer = getTreeViewerFromEditor(editor);
            if (viewer == null)
            {
                Display.getDefault().asyncExec(() ->
                {
                    AbstractTreeViewer v = getTreeViewerFromEditor(editor);
                    if (v != null)
                        provider.setTreeViewer(v);
                });
                return;
            }
            provider.setTreeViewer(viewer);
        });
    }

    /**
     * Возвращает tree viewer из comparisonView редактора (через рефлексию),
     * либо {@code null}, если view ещё не создан.
     */
    private AbstractTreeViewer getTreeViewerFromEditor(IEditorPart editor)
    {
        Object view = getField(editor, "comparisonView"); //$NON-NLS-1$
        if (!(view instanceof DtComparisonView))
            return null;
        Object treeControl = ((DtComparisonView) view).getTreeControl();
        if (treeControl == null)
            return null;
        Object viewer = invokeNoArg(treeControl, "getTreeViewer"); //$NON-NLS-1$
        return (viewer instanceof AbstractTreeViewer)
                ? (AbstractTreeViewer) viewer : null;
    }

    /**
     * Добавляет кнопки с повторными попытками, если тулбар еще не инициализирован
     */
    private void addToolbarButtonWithRetry(IEditorPart editor)
    {
        Display.getDefault().asyncExec(() -> {
            Object tbm2 = getField(editor, "toolBarManager");
            if (tbm2 instanceof IToolBarManager)
            {
                fillToolbar((IToolBarManager)tbm2, editor);
            }
        });
    }

    private void fillToolbar(IToolBarManager toolbar, IEditorPart editor) {
        IAction action1 = new Action("До измененных", IAction.AS_PUSH_BUTTON)
        {
            @Override
            public void run()
            {
                ExpandExceptAddedDeletedHandler.expand(editor, ExpandMode.toBothElement);
            }
        };
        action1.setToolTipText("Развернуть до измененных (все кроме добавленных/удалённых)");
        toolbar.add(new Separator());
        toolbar.add(action1);
        toolbar.update(true);

        IAction action2 = new Action("До объектов", IAction.AS_PUSH_BUTTON)
        {
            @Override
            public void run() {
                ExpandExceptAddedDeletedHandler.expand(editor, ExpandMode.toObject);
            }
        };
        action2.setToolTipText("Развернуть до верхних объектов");
        toolbar.add(new Separator());
        toolbar.add(action2);
        toolbar.update(true);
    }

    // ---- SWT MenuListener ----

    private void attachMenuListener(IEditorPart editor, Tree tree) {
        Menu menu = tree.getMenu();
        if (menu == null || menu.isDisposed()) return;

        MenuAdapter listener = new MenuAdapter() {
            /** Пункты, добавленные нами в текущем показе меню. */
            private final List<MenuItem> addedItems = new ArrayList<>(2);

            @Override
            public void menuShown(MenuEvent e) {
                // EDT уже заполнил меню к этому моменту (его listener в очереди раньше нашего).
                // Добавляем разделитель и пункт только для MatchedObjectsComparisonNode.
                if (getSelectedMatchedNode(editor) == null) return;

                addedItems.add(new MenuItem(menu, SWT.SEPARATOR));

                MenuItem item1 = new MenuItem(menu, SWT.PUSH);
                item1.setText(ITEM_TEXT_OpenObject);
                item1.addSelectionListener(new SelectionAdapter() {
                    @Override
                    public void widgetSelected(SelectionEvent e) {
                        // Вызываем напрямую с захваченным editor и shell дерева —
                        // без прохода через команду/хендлер, чтобы не зависеть
                        // от activeWhen/activeContexts в момент клика.
                        OpenObjectHandler.openObject(editor, tree.getShell());
                    }
                });
                addedItems.add(item1);
                
                MenuItem item2 = new MenuItem(menu, SWT.PUSH);
                item2.setText(ITEM_TEXT_showInNavigator);
                item2.addSelectionListener(new SelectionAdapter() {
                    @Override
                    public void widgetSelected(SelectionEvent e) {
                        // Вызываем напрямую с захваченным editor и shell дерева —
                        // без прохода через команду/хендлер, чтобы не зависеть
                        // от activeWhen/activeContexts в момент клика.
                        OpenObjectHandler.showInNavigator(editor, tree.getShell());
                    }
                });
                addedItems.add(item2);           
            }

            @Override
            public void menuHidden(MenuEvent e) {
                // ВАЖНО: откладываем dispose() через asyncExec.
                // Порядок SWT-событий при клике на пункт меню:
                //   1. menuHidden  (синхронно)
                //   2. widgetSelected (синхронно)
                //   3. asyncExec-очередь
                // Если вызвать dispose() прямо здесь, MenuItem уничтожается
                // до того, как widgetSelected доставит SelectionEvent —
                // и клик молча проглатывается. asyncExec гарантирует обратный порядок.
                Display display = ((Menu) e.widget).getDisplay();
                List<MenuItem> toDispose = new ArrayList<>(addedItems);
                addedItems.clear();
                display.asyncExec(() -> {
                    for (MenuItem item : toDispose) {
                        if (!item.isDisposed()) item.dispose();
                    }
                });
            }
        };

        menu.addMenuListener(listener);

        // Снимаем listener при уничтожении дерева, чтобы не было утечки
        tree.addDisposeListener(e -> {
            if (!menu.isDisposed()) menu.removeMenuListener(listener);
        });
    }

    // ---- Получение Tree из редактора (через рефлексию) ----

    private Tree getCompareTree(IEditorPart editor) {
        Object view = getField(editor, "comparisonView");
        if (!(view instanceof DtComparisonView)) return null;

        Object treeControl = ((DtComparisonView) view).getTreeControl();
        if (treeControl == null) return null;

        Object viewer = invokeNoArg(treeControl, "getTreeViewer");
        if (viewer == null) return null;

        Object widget = invokeNoArg(viewer, "getTree");
        return (widget instanceof Tree) ? (Tree) widget : null;
    }

    // ---- Получение выбранного узла (аналогично OpenObjectHandler) ----

    private MatchedObjectsComparisonNode getSelectedMatchedNode(IEditorPart editor) {
        Object view = getField(editor, "comparisonView");
        if (!(view instanceof DtComparisonView)) return null;

        Object treeControl = ((DtComparisonView) view).getTreeControl();
        if (treeControl == null) return null;

        Object viewer = invokeNoArg(treeControl, "getTreeViewer");
        if (viewer == null) return null;

        Object sel = invokeNoArg(viewer, "getSelection");
        if (!(sel instanceof IStructuredSelection)) return null;

        Object element = ((IStructuredSelection) sel).getFirstElement();
        if (element == null) return null;

        try {
            Object node = invokeNoArg(element, "retrieveComparisonNode");
            if (node instanceof MatchedObjectsComparisonNode)
                return (MatchedObjectsComparisonNode) node;
        } catch (Exception ignored) {}

        if (element instanceof MatchedObjectsComparisonNode)
            return (MatchedObjectsComparisonNode) element;

        return null;
    }

    // ---- Утилиты рефлексии ----

    public static Object getField(Object obj, String name) {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Object invokeNoArg(Object o, String name) {
        if (o == null) return null;
        try {
            return o.getClass().getMethod(name).invoke(o);
        } catch (Exception ignored) {
            return null;
        }
    }
}
