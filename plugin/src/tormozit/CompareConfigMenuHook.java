package tormozit;


import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
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
import com._1c.g5.v8.dt.compare.core.ComparisonUtils;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.datasource.IActiveComparisonDataSource;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSource;
import com._1c.g5.v8.dt.compare.merge.ExternalPropertyUtils;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.ExternalPropertyComparisonNode;
import com._1c.g5.v8.dt.compare.model.SolidResourceComparisonNode;
import com._1c.g5.v8.dt.compare.ui.editor.ComparisonTreeControl;
import com._1c.g5.v8.dt.compare.ui.util.MergeUiUtils;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.export.IExportOperation;
import com._1c.g5.v8.dt.export.IExportOperationFactory;
import com._1c.g5.v8.dt.export.IExportStrategy;
import com.google.common.net.HttpHeaders.ReferrerPolicyValues;
import com.google.inject.Inject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import javafx.scene.control.TreeView;
import org.eclipse.compare.internal.CompareEditorSelectionProvider;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.AbstractDirectPartialModelNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.ProjectPartialModelNode;
import java.util.HashSet;
import org.eclipse.jface.viewers.ITreeContentProvider;

/**
 * Добавляет пункты в контекстное меню и командную панель редактора сравнения EDT.
 *
 * <p>В тулбаре добавляется кнопка-dropdown «Развернуть» (чистое подменю —
 * при любом клике открывается меню с тремя пунктами):
 * <ul>
 *   <li>До измененных — развернуть всё кроме добавленных/удалённых.</li>
 *   <li>До объектов   — развернуть до верхних объектов конфигурации.</li>
 *   <li>До помеченных — развернуть до узлов с установленным чекбоксом.</li>
 * </ul>
 */
public class CompareConfigMenuHook implements IStartup
{
    private static final String COMPARE_EDITOR_ID         = "com._1c.g5.v8.dt.compare.ui.editor";
    private static final String CONTEXT_ID                = "tormozit.compareConf.context";
    private static final String ITEM_TEXT_OpenObject      = "Открыть объект \tF2";
    private static final String ITEM_TEXT_showInNavigator = "Показать в навигаторе \tCTRL+T";
    private static final String ITEM_TEXT_compareInIR = "Сравнить в приложении ИР";

    // ---- IStartup ----

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
//          Activator.getDefault().getInjector().injectMembers(this); // Слишком рано?
            CompareConfigSearchDialogHook.install(Display.getDefault());

            IWorkbench wb = PlatformUI.getWorkbench();
            for (IWorkbenchWindow w : wb.getWorkbenchWindows())
                hookWindow(w);

            wb.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });

            ContentAssistManager mgr = ContentAssistManager.getInstance();
            if (mgr != null) mgr.start();

            DebugInspectorHook.ensureInstalled();
        });
    }

    // ---- Подключение к окну / редактору ----

    private void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart ed = ref.getEditor(false);
                if (ed != null && isCompareEditor(ed)) hookEditor(ed);
            }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
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

    private boolean isCompareEditor(IEditorPart editor)
    {
        return COMPARE_EDITOR_ID.equals(editor.getSite().getId());
    }

    private void hookEditor(IEditorPart editor)
    {
        IContextService cs = editor.getSite().getService(IContextService.class);
        if (cs != null) cs.activateContext(CONTEXT_ID);

        addToolbarButtonWithRetry(editor);

        // ВМЕСТО УСТАНОВКИ SELECTION PROVIDER — ПРОСТО ВЕШАЕМ СЛУШАТЕЛЬ НА ДЕРЕВО
        CompareConfigSelectionListener syncListener = new CompareConfigSelectionListener(editor);
        wireTreeViewerToListener(editor, syncListener);

        Tree tree = getCompareTree(editor);
        if (tree == null)
        {
            Display.getDefault().asyncExec(() ->
            {
                Tree t = getCompareTree(editor);
                if (t != null) attachMenuListener(editor, t);
            });
            return;
        }
        attachMenuListener(editor, tree);
    }

    private void wireTreeViewerToListener(IEditorPart editor, CompareConfigSelectionListener listener)
    {
        Display.getDefault().asyncExec(() ->
        {
            AbstractTreeViewer viewer = getTreeViewerFromEditor(editor);
            if (viewer == null)
            {
                Display.getDefault().asyncExec(() ->
                {
                    AbstractTreeViewer v = getTreeViewerFromEditor(editor);
                    if (v != null) listener.setTreeViewer(v);
                });
                return;
            }
            listener.setTreeViewer(viewer);
        });
    }

    private AbstractTreeViewer getTreeViewerFromEditor(IEditorPart editor)
    {
        Object view = Global.getField(editor, "comparisonView"); //$NON-NLS-1$
        if (!(view instanceof DtComparisonView)) return null;
        Object treeControl = ((DtComparisonView) view).getTreeControl();
        if (treeControl == null) return null;
        Object viewer = Global.call(treeControl, "getTreeViewer"); //$NON-NLS-1$
        return (viewer instanceof AbstractTreeViewer) ? (AbstractTreeViewer) viewer : null;
    }

    private void addToolbarButtonWithRetry(IEditorPart editor)
    {
        Display.getDefault().asyncExec(() ->
        {
            Object tbm = Global.getField(editor, "toolBarManager"); //$NON-NLS-1$
            if (tbm instanceof IToolBarManager)
                fillToolbar((IToolBarManager) tbm, editor);
        });
    }

    // ---- Тулбар: чистое подменю «Развернуть» ----

    /**
     * Добавляет в тулбар кнопку «Развернуть» с типом {@code SWT.DROP_DOWN}.
     *
     * <p>В отличие от {@code Action + IMenuCreator}, здесь используется
     * {@link ContributionItem}, перекрывающий {@link ContributionItem#fill(ToolBar, int)}.
     * Слушатель {@code widgetSelected} вызывается при нажатии на <b>любую</b>
     * часть кнопки — и на текст, и на стрелку — и всегда открывает подменю под кнопкой.
     */
    private void fillToolbar(IToolBarManager toolbar, IEditorPart editor)
    {
        toolbar.add(new Separator());
        toolbar.add(new ContributionItem()
        {
            @Override
            public void fill(ToolBar bar, int index)
            {
                ToolItem item = index >= 0
                    ? new ToolItem(bar, SWT.DROP_DOWN, index)
                    : new ToolItem(bar, SWT.DROP_DOWN);
                item.setText("Развернуть");
                item.setToolTipText("Развернуть дерево сравнения до нужного уровня" + Global.pluginSignForTooltip());

                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent e)
                    {
                        // Открываем меню при ЛЮБОМ клике — и по тексту (e.detail == 0),
                        // и по стрелке (e.detail == SWT.ARROW)
                        showExpandMenu(item, bar, editor);
                    }
                });
            }
        });
        toolbar.update(true);
    }

    /**
     * Создаёт временное SWT pop-up меню и отображает его прямо под кнопкой.
     * Меню уничтожается автоматически после закрытия.
     */
    private static void showExpandMenu(ToolItem item, ToolBar bar, IEditorPart editor)
    {
        Menu menu = new Menu(bar.getShell(), SWT.POP_UP);

        addExpandMenuItem(menu, "До измененных",
            "Развернуть всё, кроме добавленных/удалённых",
            editor, CompareConfigExpandMode.toBothElement);

        addExpandMenuItem(menu, "До объектов",
            "Развернуть до верхних объектов конфигурации",
            editor, CompareConfigExpandMode.toObject);

        addExpandMenuItem(menu, "До помеченных",
            "Развернуть до узлов с установленным чекбоксом",
            editor, CompareConfigExpandMode.toMarked);

        // Позиционируем меню под кнопкой тулбара
        Rectangle bounds = item.getBounds();
        Point loc = bar.toDisplay(bounds.x, bounds.y + bounds.height);
        menu.setLocation(loc);
        menu.setVisible(true);

        // Удаляем временное меню сразу после закрытия
        menu.addMenuListener(new MenuAdapter()
        {
            @Override
            public void menuHidden(MenuEvent e)
            {
                bar.getDisplay().asyncExec(menu::dispose);
            }
        });
    }

    private static void addExpandMenuItem(Menu menu, String text, String tooltip,
                                          IEditorPart editor, CompareConfigExpandMode mode)
    {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(text);
        item.setToolTipText(tooltip);
        item.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                CompareConfigExpandHandler.expand(editor, mode);
            }
        });
    }

    // ---- SWT MenuListener (контекстное меню дерева) ----

    private void attachMenuListener(IEditorPart editor, Tree tree)
    {
        Menu menu = tree.getMenu();
        if (menu == null || menu.isDisposed()) return;

        MenuAdapter listener = new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(2);

            @Override
            public void menuShown(MenuEvent e)
            {
                if (getSelectedMatchedNode(editor) == null) return;

                addedItems.add(new MenuItem(menu, SWT.SEPARATOR));

                MenuItem item1 = new MenuItem(menu, SWT.PUSH);
                item1.setText(ITEM_TEXT_OpenObject);
                item1.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent e)
                    {
                        CompareConfigOpenObjectHandler.openObject(editor, tree.getShell());
                    }
                });
                addedItems.add(item1);

                MenuItem item2 = new MenuItem(menu, SWT.PUSH);
                item2.setText(ITEM_TEXT_showInNavigator);
                item2.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent e)
                    {
                        CompareConfigOpenObjectHandler.showInNavigator(editor, tree.getShell());
                    }
                });
                addedItems.add(item2);
                
                MenuItem item3 = new MenuItem(menu, SWT.PUSH);
                item3.setText(ITEM_TEXT_compareInIR);
                item3.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent e)
                    {
                        CompareConfigCompareInIRHandler.runCompare(editor, tree.getShell());
                    }
                });
                addedItems.add(item2);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                Display display = ((Menu) e.widget).getDisplay();
                List<MenuItem> toDispose = new ArrayList<>(addedItems);
                addedItems.clear();
                display.asyncExec(() ->
                {
                    for (MenuItem mi : toDispose)
                        if (!mi.isDisposed()) mi.dispose();
                });
            }
        };

        menu.addMenuListener(listener);
        tree.addDisposeListener(e ->
        {
            if (!menu.isDisposed()) menu.removeMenuListener(listener);
        });
    }

    // ---- Получение Tree из редактора ----

    private Tree getCompareTree(IEditorPart editor)
    {
        Object view = Global.getField(editor, "comparisonView"); //$NON-NLS-1$
        if (!(view instanceof DtComparisonView)) return null;
        Object treeControl = ((DtComparisonView) view).getTreeControl();
        if (treeControl == null) return null;
        Object viewer = Global.call(treeControl, "getTreeViewer"); //$NON-NLS-1$
        if (viewer == null) return null;
        Object widget = Global.call(viewer, "getTree"); //$NON-NLS-1$
        return (widget instanceof Tree) ? (Tree) widget : null;
    }

    // ---- Получение выбранного узла ----

    private MatchedObjectsComparisonNode getSelectedMatchedNode(IEditorPart editor)
    {
        Object view = Global.getField(editor, "comparisonView"); //$NON-NLS-1$
        if (!(view instanceof DtComparisonView)) return null;
        Object treeControl = ((DtComparisonView) view).getTreeControl();
        if (treeControl == null) return null;
        Object viewer = Global.call(treeControl, "getTreeViewer"); //$NON-NLS-1$
        if (viewer == null) return null;
        Object sel = Global.call(viewer, "getSelection"); //$NON-NLS-1$
        if (!(sel instanceof IStructuredSelection)) return null;
        Object element = ((IStructuredSelection) sel).getFirstElement();
        if (element == null) return null;
        try
        {
            Object node = Global.call(element, "retrieveComparisonNode"); //$NON-NLS-1$
            if (node instanceof MatchedObjectsComparisonNode)
                return (MatchedObjectsComparisonNode) node;
        }
        catch (Exception ignored) {}
        if (element instanceof MatchedObjectsComparisonNode)
            return (MatchedObjectsComparisonNode) element;
        return null;
    }

    /**
     * Открывает объект конфигурации выбранный в дереве сравнения EDT.
     *
     * Алгоритм:
     * 1. Получаем IComparisonSession из поля comparisonArtifactsList редактора
     * 2. Получаем MatchedObjectsComparisonNode из comparisonView
     * 3. Берём mainObjectId (bmId) из узла
     * 4. Получаем EObject через IActiveComparisonDataSource.getObjectById()
     * 5. Открываем через OpenHelper
     */
    private static class CompareConfigCompareInIRHandler extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) throws ExecutionException {
            runCompare(HandlerUtil.getActiveEditor(event), HandlerUtil.getActiveShell(event));
            return null;
        }

        public static void runCompare(IEditorPart editor, Shell shell) {
            ISelection selection = getSelection(editor);
            Object element = ((IStructuredSelection) selection).getFirstElement();
            if (element == null)
                return;
            Path pathMain = getPropertySideFile(editor, element, ComparisonSide.MAIN); // mxlx
            if (pathMain == null)
            {
                ToastNotification.show("Сравнение метаданных ИР", "Поддерживаются свойства: ТабличныйДокумент.Макет");
                return;
            }
            Path pathOther = getPropertySideFile(editor, element, ComparisonSide.OTHER); // mxlx
            Path pathAncestor = getPropertySideFile(editor, element, ComparisonSide.COMMON_ANCESTOR); // mxlx
            IComparisonSession compSession = CompareConfigSelectionListener.getSession(editor);
            IRSession irSession = IRApplication.getSession(compSession.getDataSource(ComparisonSide.MAIN).getDtProject());
            if (irSession == null || irSession.executor == null) {
                return;
            }
            String ancestor = pathAncestor != null ?pathAncestor.toString() : null;
            irSession.executor.submit(() -> {
                try 
                {
                    // Здесь мы находимся в родном потоке для этого COM-объекта. 
                    Object irClient = irSession.getModule("ирКлиент");
                    irSession.showWindow();
                    ComBridge.invoke(irClient, "СравнитьТабличныеДокументыИмпортЛкс", pathMain.toString(), pathOther.toString(), ancestor);
                } 
                catch (Exception e) 
                {
                    Global.log("Ошибка вызова ИР: " + e.getMessage());
                }
            });
        }

        /**
         * Читает содержимое xmxl-файла через {@link ExternalPropertyUtils#getContentStream},
         * сохраняет его во временный файл и возвращает путь к нему.
         * <p>Имя временного файла строится по шаблону {@code tormozit_<side>_<имяФайла>.xmxl},
         * где имя файла берётся из относительного пути, полученного от
         * {@link ComparisonUtils#getFilePathBySymlink}. Это упрощает отладку.
         * @return абсолютный путь к временному файлу, или {@code null} если поток недоступен
         */
        public static Path getPropertySideFile(IEditorPart editor, Object element, ComparisonSide side)
        {
            IComparisonSession session = CompareConfigSelectionListener.getSession(editor);
            MatchedObjectsComparisonNode matchedNode = CompareConfigSelectionListener.resolveMatchedNode(element);
            ExternalPropertyComparisonNode properyNode;
            try
            {
                properyNode = (ExternalPropertyComparisonNode) matchedNode;
            }
            catch (Exception e)
            {
                return null;
            }
            BundleContext ctx = Global.ourContext();
            ServiceReference<?> ref = ctx.getServiceReference(IComparisonManager.class);
            Object manager = ctx.getService(ref);
            IQualifiedNameFilePathConverter filePathConverter = (IQualifiedNameFilePathConverter) Global.getField(manager, "qualifiedNameFilePathConverter");
            InputStream stream = ExternalPropertyUtils.getContentStream(properyNode, session, side, filePathConverter);
            if (stream == null)
                return null;
            String symlink = properyNode.getSymlink(side);
            String qualifyingType = ((SolidResourceComparisonNode) properyNode).getQualifyingType(side);
            Path relativePath = (Path) ComparisonUtils.getFilePathBySymlink(symlink, qualifyingType, filePathConverter);
            String fileName = relativePath != null ? relativePath.getFileName().toString() : "content.xmxl"; //$NON-NLS-1$
            String prefix = "tormozit_" + side.name().toLowerCase() + "_"; //$NON-NLS-1$ //$NON-NLS-2$
            String suffix = "_" + fileName; //$NON-NLS-1$
            try {
                Path tempFile = Files.createTempFile(prefix, suffix);
                Files.copy(stream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return tempFile;
            } catch (IOException e) {
                Global.log("getSideFile: не удалось записать временный файл: " + e.getMessage()); //$NON-NLS-1$
                return null;
            } finally {
                try { stream.close(); } catch (IOException ignored) {}
            }
        }

        public static ISelection getSelection(IEditorPart editor) {
            ISelection sel = null;
            DtComparisonView view = (DtComparisonView) Global.getField(editor, "comparisonView");
            if (view != null) {
                ComparisonTreeControl treeControl = view.getTreeControl();
                if (treeControl != null) {
                    TreeViewer viewer = treeControl.getTreeViewer();
                    if (viewer != null)
                    {
                        sel = viewer.getSelection();
                    }
                }
            }
            return sel;
        }
    }


    private static class CompareConfigExpandHandler extends AbstractHandler
    {
        private static Method retrieveMethodCache = null;

        @Override
        public Object execute(ExecutionEvent event) throws ExecutionException
        {
            return null;
        }

        public static Object expand(IEditorPart editor, CompareConfigExpandMode mode)
        {
            AbstractTreeViewer viewer = getTreeViewer(editor);
            if (viewer == null) return null;

            ITreeContentProvider cp = (ITreeContentProvider) viewer.getContentProvider();
            if (cp == null) return null;

            ISelection selection = viewer.getSelection();

            Set<Object> toExpand = new HashSet<>();

            viewer.collapseAll();
            for (Object root : cp.getElements(viewer.getInput()))
            {
                collectElementsToExpand(cp, root, mode, toExpand, viewer);
            }
            viewer.setExpandedElements(toExpand.toArray());

            if (selection != null && !selection.isEmpty())
            {
                viewer.setSelection(selection, true);
            }
            return null;
        }

        /**
         * Рекурсивно собирает список узлов для раскрытия.
         * Никаких вызовов вьювера внутри!
         */
        private static void collectElementsToExpand(ITreeContentProvider cp, Object element, CompareConfigExpandMode mode, Set<Object> toExpand, AbstractTreeViewer viewer)
        {
            if (false
                    || !cp.hasChildren(element)
                    || mode == CompareConfigExpandMode.toBothElement && isAddedOrDeleted(element)
                    || mode == CompareConfigExpandMode.toObject      && isObject(element)
                    || mode == CompareConfigExpandMode.toMarked      && !isMarked(element))
                return;

            toExpand.add(element);
            Object[] children;
            try
            {
                children = cp.getChildren(element);
            }
            catch (Exception e)
            {
                // https://github.com/tormozit/EDT-Tormozit/issues/8
                Global.logError("CompareConfig", "getChildren", e); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            for (Object child : children)
            {
                if (CompareConfigSearchDialogHook.isNodeMatchFilters(child, viewer));
                    collectElementsToExpand(cp, child, mode, toExpand, viewer);
            }
        }

        /**
         * Возвращает {@code true} если у узла установлен чекбокс в дереве сравнения.
         */
        private static boolean isMarked(Object element)
        {
            boolean isChecked = false;
            try
            {
                Method methodDesc = element.getClass().getMethod("isChecked"); //$NON-NLS-1$
                isChecked = (Boolean) methodDesc.invoke(element);
    //            return isChecked;
            }
            catch (Exception e)
            {
                Global.logError("CompareConfig", "isChecked", e); //$NON-NLS-1$ //$NON-NLS-2$
            }
            boolean is = false;
            if (isChecked)
            {
                MatchedObjectsComparisonNode node = extractMatchedNode(element);
                is = !(node == null || node.getNodeSide() == null)
                    || (node != null && !node.getComparisonFlags().hasDiffsMainOther());
                is = !is;
            }
            return is;
        }

        /**
         * Возвращает {@code true} если элемент является узлом объекта конфигурации.
         */
        private static boolean isObject(Object element)
        {
            MatchedObjectsComparisonNode node = extractMatchedNode(element);
            if (node == null)
                return false;

            Long mainId  = node.getMainObjectId();
            Long otherId = node.getOtherObjectId();
            return (mainId  != null && mainId  != -1L)
                || (otherId != null && otherId != -1L);
        }

        /**
         * Возвращает {@code true} если объект присутствует только в одной стороне
         * сравнения (добавлен или удалён).
         */
        private static boolean isAddedOrDeleted(Object element)
        {
            boolean isCheckable = true;
            try
            {
                Method methodDesc = element.getClass().getMethod("isCheckable"); //$NON-NLS-1$
                isCheckable = (Boolean) methodDesc.invoke(element);
            }
            catch (Exception e)
            {
                Global.logError("CompareConfig", "isCheckable", e); //$NON-NLS-1$ //$NON-NLS-2$
            }
            boolean is = true;
            if (isCheckable)
            {
                MatchedObjectsComparisonNode node = extractMatchedNode(element);
                is = !(node == null || node.getNodeSide() == null)
                    || (node != null && !node.getComparisonFlags().hasDiffsMainOther());
            }
            return is;
        }

        /**
         * Извлекает {@link MatchedObjectsComparisonNode} из обёртки элемента дерева.
         */
        private static MatchedObjectsComparisonNode extractMatchedNode(Object element)
        {
            if (retrieveMethodCache == null)
            {
                try
                {
                    retrieveMethodCache = element.getClass().getMethod("retrieveComparisonNode"); //$NON-NLS-1$
                }
                catch (NoSuchMethodException e)
                {
                    return null;
                }
            }
            Object raw;
            try
            {
                raw = retrieveMethodCache.invoke(element);
            }
            catch (Exception e)
            {
                return null;
            }
            if (raw instanceof MatchedObjectsComparisonNode)
                return (MatchedObjectsComparisonNode) raw;
            if (element instanceof MatchedObjectsComparisonNode)
                return (MatchedObjectsComparisonNode) element;
            return null;
        }

        // ---- Утилиты рефлексии ----

        private static AbstractTreeViewer getTreeViewer(IEditorPart editor)
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

        static Object getField(Object obj, String name)
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
                catch (Exception ignored)            { return null; }
            }
            return null;
        }

        static Object invokeNoArg(Object o, String name)
        {
            if (o == null) return null;
            try
            {
                return o.getClass().getMethod(name).invoke(o);
            }
            catch (Exception ignored)
            {
                return null;
            }
        }
    }

}
