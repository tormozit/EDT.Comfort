package tormozit;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.ICheckStateProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.TypedListener;
import org.eclipse.swt.widgets.Widget;
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
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.plugin.AbstractUIPlugin;

import com._1c.g5.v8.dt.bsl.compare.BslModuleComparisonNode;
import com._1c.g5.v8.dt.bsl.compare.BslModuleSectionComparisonNode;
import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;
import com._1c.g5.v8.dt.compare.ui.editor.ISelectionProviderDelegate;
import com._1c.g5.v8.dt.compare.ui.partialmodel.CustomMergeSettingsStatus;
import com._1c.g5.v8.dt.compare.ui.partialmodel.IPartialModel;
import com._1c.g5.v8.dt.compare.ui.partialmodel.PartialModelController;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.ExternalPropertyPartialModelNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.ICollectionPartialNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IDirectPartialModelNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IPartialModelNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.VirtualFolderPartialModelNode;
import org.eclipse.jface.resource.ResourceManager;
import com._1c.g5.v8.dt.compare.ui.ComparisonFilterKind;
import com._1c.g5.v8.dt.compare.ui.FilterUtils;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonViewContext;
import com._1c.g5.v8.dt.compare.ui.editor.LightweightCompareMergeProcessDescriptor;
import com._1c.g5.v8.dt.compare.ui.editor.LightweightComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.ui.partialmodel.INamedViewerFilter;
import com._1c.g5.v8.dt.compare.core.ComparisonUtils;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.datasource.IActiveComparisonDataSource;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSource;
import com._1c.g5.v8.dt.compare.merge.ExternalPropertyUtils;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.ExternalPropertyComparisonNode;
import com._1c.g5.v8.dt.compare.model.FeatureCollectionComparisonNode;
import com._1c.g5.v8.dt.compare.model.FeatureComparisonNode;
import com._1c.g5.v8.dt.compare.model.MergeSettings;
import com._1c.g5.v8.dt.compare.model.SolidResourceComparisonNode;
import com._1c.g5.v8.dt.compare.model.UnsupportedObjectComparisonNode;
import com._1c.g5.v8.dt.compare.ui.editor.ComparisonTreeControl;
import com._1c.g5.v8.dt.compare.ui.util.MergeUiUtils;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.export.IExportOperation;
import com._1c.g5.v8.dt.export.IExportOperationFactory;
import com._1c.g5.v8.dt.export.IExportStrategy;
import com.google.common.net.HttpHeaders.ReferrerPolicyValues;
import com.google.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.AbstractDirectPartialModelNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.ProjectPartialModelNode;

import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.xtext.naming.IQualifiedNameProvider;
import org.eclipse.xtext.naming.QualifiedName;

import com._1c.g5.v8.dt.common.EObjectTrie;
import com._1c.g5.v8.dt.compare.model.SymlinkComparisonNode;

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
            CompareConfigOpenModuleMergeHandler.install(Display.getDefault());
            RightsDialogFilterHook.install(Display.getDefault());
            ThreeSideMergeCurrentLinesHook.install(Display.getDefault());
            GitCompareCurrentLinesHook.install();
            CompareDialogCurrentLinesHook.install(Display.getDefault());

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
        SubsystemFilterFix.install(editor);
        ThreeWayOnlyPresentFiltersFix.install(editor);

        Tree tree = getCompareTree(editor);
        if (tree == null)
        {
            Display.getDefault().asyncExec(() ->
            {
                Tree t = getCompareTree(editor);
                if (t != null) attachTreeListeners(editor, t);
            });
            return;
        }
        attachTreeListeners(editor, tree);
    }

    private void attachTreeListeners(IEditorPart editor, Tree tree)
    {
        attachMenuListener(editor, tree);
        CompareConfigOpenModuleMergeHandler.attachDoubleClickListener(editor, tree);
        CompareModuleStructureColumnHook.install(tree);
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
                    if (v != null)
                    {
                        onCompareTreeViewerReady(listener, v);
                        ThreeWayOnlyPresentFiltersFix.install(editor);
                    }
                });
                return;
            }
            onCompareTreeViewerReady(listener, viewer);
            ThreeWayOnlyPresentFiltersFix.install(editor);
        });
    }

    private static void onCompareTreeViewerReady(CompareConfigSelectionListener listener, AbstractTreeViewer viewer)
    {
        listener.setTreeViewer(viewer);
        // Разворот единственного корня и цепочек единственных потомков — общий механизм,
        // см. TreeAutoExpand.installLoadAutoExpand (раньше был свой tryExpandCompareRoot/
        // scheduleExpandCompareRoot, теперь вынесено и обобщено на все деревья из белого списка).
        TreeAutoExpand.installWhitelisted(
                TreeAutoExpand.Target.COMPARE_CONFIG, viewer);
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

                ISelection selection = CompareConfigCompareInIRHandler.getSelection(editor);
                Object selectedElement = selection instanceof IStructuredSelection
                    ? ((IStructuredSelection) selection).getFirstElement() : null;
                if (CompareConfigCompareInIRHandler.isMxlxNode(editor, selectedElement))
                {
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
                    addedItems.add(item3);
                }
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
     * Возвращает {@code true}, если узел дерева сравнения присутствует только в одной
     * стороне сравнения (добавлен или удалён) — тот же критерий, что использует команда
     * «До изменённых» ({@link CompareConfigExpandMode#toBothElement}), чтобы не разворачивать
     * в него дочерние узлы. Используется {@link TreeAutoExpand} для остановки
     * авторазворачивания цепочки единственных потомков на таких узлах.
     */
    public static boolean isAddedOrDeletedCompareNode(Object element)
    {
        return CompareConfigExpandHandler.isAddedOrDeleted(element);
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

    /**
     * В 3-way комбо «Фильтр» нет пунктов «Показывать только присутствующие в 'X'»
     * ({@link ComparisonFilterKind#ONLY_MAIN}/{@link ComparisonFilterKind#ONLY_OTHER}
     * с {@code isApplicableForThreeWay=false}). Добавляем те же фильтры, что в 2-way,
     * через {@link FilterUtils#twoWayOnlyOnOneSide} — после блока «Показывать отличия …».
     */
    private static class ThreeWayOnlyPresentFiltersFix
    {
        private static final String FLAG = "tormozit.threeWayOnlyPresentFilters"; //$NON-NLS-1$
        private static final String LOG = "ThreeWayOnlyPresentFilters"; //$NON-NLS-1$
        /** Сравнение может идти минуты — ждём появление view/combo дольше. */
        private static final int MAX_ATTEMPTS = 480;
        private static final int RETRY_MS = 250;

        static void install(IEditorPart editor)
        {
            install(editor, 0);
        }

        private static void install(IEditorPart editor, int attempt)
        {
            if (editor == null)
                return;

            Display display = Display.getDefault();
            Runnable body = () ->
            {
                try
                {
                    if (editor.getSite() == null)
                        return;
                    Object viewObj = Global.getField(editor, "comparisonView"); //$NON-NLS-1$
                    if (!(viewObj instanceof DtComparisonView))
                    {
                        retryOrGiveUp(editor, attempt, "no comparisonView"); //$NON-NLS-1$
                        return;
                    }
                    DtComparisonView view = (DtComparisonView) viewObj;
                    if (view.isDisposed())
                    {
                        Global.tempLog(LOG, "skip: view disposed"); //$NON-NLS-1$
                        return;
                    }
                    if (Boolean.TRUE.equals(view.getData(FLAG)))
                        return;

                    Object filtersObj = Global.getField(editor, "filters"); //$NON-NLS-1$
                    if (!(filtersObj instanceof INamedViewerFilter[]))
                    {
                        retryOrGiveUp(editor, attempt, "no filters"); //$NON-NLS-1$
                        return;
                    }
                    INamedViewerFilter[] current = (INamedViewerFilter[]) filtersObj;

                    Object editorInput = Global.getField(editor, "dtComparisonEditorInput"); //$NON-NLS-1$
                    if (editorInput == null)
                    {
                        retryOrGiveUp(editor, attempt, "no editorInput"); //$NON-NLS-1$
                        return;
                    }
                    if (!isThreeWay(editorInput))
                    {
                        Global.tempLog(LOG, "skip: not three-way"); //$NON-NLS-1$
                        view.setData(FLAG, Boolean.TRUE);
                        return;
                    }

                    String mainName = (String) Global.call(editorInput, "getMainComparisonSideName"); //$NON-NLS-1$
                    String otherName = (String) Global.call(editorInput, "getOtherComparisonSideName"); //$NON-NLS-1$
                    if (mainName == null || otherName == null)
                    {
                        Global.tempLog(LOG, "skip: side names null"); //$NON-NLS-1$
                        return;
                    }

                    INamedViewerFilter onlyMain = (INamedViewerFilter) Global.invoke(FilterUtils.class,
                        "twoWayOnlyOnOneSide", //$NON-NLS-1$
                        ComparisonSide.MAIN, ComparisonSide.OTHER, mainName);
                    INamedViewerFilter onlyOther = (INamedViewerFilter) Global.invoke(FilterUtils.class,
                        "twoWayOnlyOnOneSide", //$NON-NLS-1$
                        ComparisonSide.OTHER, ComparisonSide.MAIN, otherName);
                    if (onlyMain == null || onlyOther == null)
                    {
                        Global.tempLog(LOG, "skip: twoWayOnlyOnOneSide returned null"); //$NON-NLS-1$
                        return;
                    }

                    INamedViewerFilter[] patched = insertOnlyPresentFilters(current, mainName, otherName,
                        onlyMain, onlyOther);
                    if (patched == current)
                    {
                        Global.tempLog(LOG, "skip: already present or insert point missing"
                            + " count=" + current.length); //$NON-NLS-1$
                        view.setData(FLAG, Boolean.TRUE);
                        return;
                    }

                    ComboViewer comboViewer = findFilterComboViewer(view);
                    if (comboViewer == null)
                    {
                        if (attempt == 0 || attempt % 20 == 0)
                            Global.tempLog(LOG, "no ComboViewer yet attempt=" + attempt //$NON-NLS-1$
                                + " " + describeFilterControl(view)); //$NON-NLS-1$
                        retryOrGiveUp(editor, attempt, "no ComboViewer"); //$NON-NLS-1$
                        return;
                    }

                    Global.setField(editor, "filters", patched); //$NON-NLS-1$

                    Object contextObj = Global.getField(view, "context"); //$NON-NLS-1$
                    if (contextObj instanceof DtComparisonViewContext)
                    {
                        if (!Global.setFieldForce(contextObj, "filters", patched)) //$NON-NLS-1$
                            Global.tempLog(LOG, "warn: context.filters setFieldForce failed"); //$NON-NLS-1$
                    }
                    else
                        Global.tempLog(LOG, "warn: no DtComparisonViewContext"); //$NON-NLS-1$

                    INamedViewerFilter selected = view.getCurrentNamedViewerFilter();
                    comboViewer.setInput(patched);
                    if (selected != null)
                        comboViewer.setSelection(new StructuredSelection(selected), true);

                    view.setData(FLAG, Boolean.TRUE);
                    Global.tempLog(LOG, "installed only-present filters"
                        + " main=" + mainName //$NON-NLS-1$
                        + " other=" + otherName //$NON-NLS-1$
                        + " count=" + patched.length); //$NON-NLS-1$
                }
                catch (Exception e)
                {
                    Global.tempLog(LOG, "error: " + e); //$NON-NLS-1$
                    if (attempt < MAX_ATTEMPTS)
                        Display.getDefault().timerExec(RETRY_MS, () -> install(editor, attempt + 1));
                }
            };

            if (Thread.currentThread() == display.getThread())
                body.run();
            else
                display.asyncExec(body);
        }

        private static void retryOrGiveUp(IEditorPart editor, int attempt, String reason)
        {
            Display display = Display.getDefault();
            if (attempt < MAX_ATTEMPTS)
                display.timerExec(RETRY_MS, () -> install(editor, attempt + 1));
            else
                Global.tempLog(LOG, "give up after " + attempt + " attempts reason=" + reason); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static boolean isThreeWay(Object editorInput)
        {
            Object descriptorsObj = Global.call(editorInput, "getDescriptors"); //$NON-NLS-1$
            if (!(descriptorsObj instanceof List<?>))
                return false;
            for (Object descriptor : (List<?>) descriptorsObj)
            {
                if (!(descriptor instanceof LightweightCompareMergeProcessDescriptor))
                    continue;
                LightweightComparisonProcessHandle handle =
                    ((LightweightCompareMergeProcessDescriptor) descriptor).getHandle();
                if (handle != null && handle.isThreeWay())
                    return true;
            }
            return false;
        }

        /**
         * Вставляет два фильтра сразу после {@link ComparisonFilterKind#OTHER_TO_ANCESTOR}.
         * Если точка вставки не найдена или фильтры уже есть — возвращает исходный массив.
         */
        private static INamedViewerFilter[] insertOnlyPresentFilters(INamedViewerFilter[] current,
                String mainName, String otherName,
                INamedViewerFilter onlyMain, INamedViewerFilter onlyOther)
        {
            if (current == null || current.length == 0)
                return current;

            String onlyMainName = onlyMain.getName();
            String onlyOtherName = onlyOther.getName();
            for (INamedViewerFilter f : current)
            {
                if (f == null)
                    continue;
                String name = f.getName();
                if (onlyMainName.equals(name) || onlyOtherName.equals(name))
                    return current;
            }

            Map<ComparisonFilterKind, INamedViewerFilter> threeWay =
                FilterUtils.getThreeWayFilters(mainName, otherName);
            INamedViewerFilter otherToAncestor = threeWay.get(ComparisonFilterKind.OTHER_TO_ANCESTOR);
            String markerName = otherToAncestor != null ? otherToAncestor.getName() : null;
            if (markerName == null)
                return current;

            int insertAfter = -1;
            for (int i = 0; i < current.length; i++)
            {
                if (current[i] != null && markerName.equals(current[i].getName()))
                {
                    insertAfter = i;
                    break;
                }
            }
            if (insertAfter < 0)
                return current;

            INamedViewerFilter[] patched = new INamedViewerFilter[current.length + 2];
            System.arraycopy(current, 0, patched, 0, insertAfter + 1);
            patched[insertAfter + 1] = onlyMain;
            patched[insertAfter + 2] = onlyOther;
            System.arraycopy(current, insertAfter + 1, patched, insertAfter + 3,
                current.length - insertAfter - 1);
            return patched;
        }

        private static ComboViewer findFilterComboViewer(DtComparisonView view)
        {
            Object filterControlObj = Global.getField(view, "filterControl"); //$NON-NLS-1$
            if (!(filterControlObj instanceof Composite))
                return null;
            Composite filterControl = (Composite) filterControlObj;
            if (filterControl.isDisposed())
                return null;
            Combo combo = findCombo(filterControl);
            if (combo == null || combo.isDisposed())
                return null;
            return resolveComboViewer(combo);
        }

        private static String describeFilterControl(DtComparisonView view)
        {
            try
            {
                Object filterControlObj = Global.getField(view, "filterControl"); //$NON-NLS-1$
                if (!(filterControlObj instanceof Composite))
                    return "filterControl=" + (filterControlObj == null ? "null" : filterControlObj.getClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$
                Composite filterControl = (Composite) filterControlObj;
                if (filterControl.isDisposed())
                    return "filterControl disposed"; //$NON-NLS-1$
                Combo combo = findCombo(filterControl);
                if (combo == null)
                    return "no Combo children=" + filterControl.getChildren().length; //$NON-NLS-1$
                Listener[] sel = combo.getListeners(SWT.Selection);
                Listener[] disp = combo.getListeners(SWT.Dispose);
                return "combo selListeners=" + sel.length //$NON-NLS-1$
                    + " disposeListeners=" + disp.length //$NON-NLS-1$
                    + " sample=" + sampleListener(sel); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                return "describe error: " + e; //$NON-NLS-1$
            }
        }

        private static String sampleListener(Listener[] listeners)
        {
            if (listeners == null || listeners.length == 0)
                return "none"; //$NON-NLS-1$
            Object eventListener = listeners[0];
            if (listeners[0] instanceof TypedListener)
                eventListener = ((TypedListener) listeners[0]).getEventListener();
            if (eventListener == null)
                return "null-event"; //$NON-NLS-1$
            StringBuilder sb = new StringBuilder(eventListener.getClass().getName());
            Object outer = Global.getField(eventListener, "this$0"); //$NON-NLS-1$
            if (outer != null)
                sb.append(" this$0=").append(outer.getClass().getName()); //$NON-NLS-1$
            Object arg1 = Global.getField(eventListener, "arg$1"); //$NON-NLS-1$
            if (arg1 != null)
                sb.append(" arg$1=").append(arg1.getClass().getName()); //$NON-NLS-1$
            return sb.toString();
        }

        private static Combo findCombo(Composite parent)
        {
            if (parent == null || parent.isDisposed())
                return null;
            for (Control child : parent.getChildren())
            {
                if (child instanceof Combo)
                    return (Combo) child;
                if (child instanceof Composite)
                {
                    Combo nested = findCombo((Composite) child);
                    if (nested != null)
                        return nested;
                }
            }
            return null;
        }

        /**
         * JFace вешает на Combo не сам ComboViewer, а {@code OpenStrategy} /
         * lambda DisposeListener. Достаём viewer из {@code this$0}/{@code arg$1}
         * или из {@code OpenStrategy.selectionEventListeners}.
         */
        private static ComboViewer resolveComboViewer(Combo combo)
        {
            ComboViewer fromSelection = resolveFromListeners(combo.getListeners(SWT.Selection));
            if (fromSelection != null)
                return fromSelection;
            ComboViewer fromDispose = resolveFromListeners(combo.getListeners(SWT.Dispose));
            if (fromDispose != null)
                return fromDispose;
            Object data = combo.getData("org.eclipse.jface.viewers.WIDGET_DATA"); //$NON-NLS-1$
            if (data instanceof ComboViewer)
                return (ComboViewer) data;
            return null;
        }

        private static ComboViewer resolveFromListeners(Listener[] listeners)
        {
            if (listeners == null)
                return null;
            for (Listener listener : listeners)
            {
                Object eventListener = listener;
                if (listener instanceof TypedListener)
                    eventListener = ((TypedListener) listener).getEventListener();
                ComboViewer found = extractComboViewer(eventListener);
                if (found != null)
                    return found;
            }
            return null;
        }

        private static ComboViewer extractComboViewer(Object eventListener)
        {
            if (eventListener == null)
                return null;
            if (eventListener instanceof ComboViewer)
                return (ComboViewer) eventListener;

            for (String field : new String[] { "this$0", "arg$1", "arg$2", "val$viewer" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {
                Object holder = Global.getField(eventListener, field);
                if (holder instanceof ComboViewer)
                    return (ComboViewer) holder;
                if (holder != null && holder.getClass().getName().contains("OpenStrategy")) //$NON-NLS-1$
                {
                    ComboViewer fromOpen = resolveFromOpenStrategy(holder);
                    if (fromOpen != null)
                        return fromOpen;
                }
            }

            if (eventListener.getClass().getName().contains("OpenStrategy")) //$NON-NLS-1$
                return resolveFromOpenStrategy(eventListener);

            return null;
        }

        private static ComboViewer resolveFromOpenStrategy(Object openStrategy)
        {
            Object listObj = Global.getField(openStrategy, "selectionEventListeners"); //$NON-NLS-1$
            ComboViewer fromSel = resolveFromListenerList(listObj);
            if (fromSel != null)
                return fromSel;
            Object postObj = Global.getField(openStrategy, "postSelectionEventListeners"); //$NON-NLS-1$
            return resolveFromListenerList(postObj);
        }

        private static ComboViewer resolveFromListenerList(Object listenerList)
        {
            if (listenerList == null)
                return null;
            Object[] listeners;
            try
            {
                Object got = Global.call(listenerList, "getListeners"); //$NON-NLS-1$
                if (!(got instanceof Object[]))
                    return null;
                listeners = (Object[]) got;
            }
            catch (Exception e)
            {
                return null;
            }
            for (Object sl : listeners)
            {
                if (sl instanceof ComboViewer)
                    return (ComboViewer) sl;
                Object outer = Global.getField(sl, "this$0"); //$NON-NLS-1$
                if (outer instanceof ComboViewer)
                    return (ComboViewer) outer;
                // StructuredViewer$4 → this$0 = StructuredViewer (ComboViewer)
                if (outer != null && outer.getClass().getName().contains("ComboViewer")) //$NON-NLS-1$
                    return (ComboViewer) outer;
                Object arg1 = Global.getField(sl, "arg$1"); //$NON-NLS-1$
                if (arg1 instanceof ComboViewer)
                    return (ComboViewer) arg1;
            }
            return null;
        }
    }

    /**
     * Коррекция EDT-фильтра по подсистемам: односторонние не-{@link SymlinkComparisonNode}
     * узлы EDT пропускает ({@code checkNodeIsSelectedForSide} → true). Добавляем свой
     * {@link ViewerFilter}, используя те же trie через {@code ViewerFilterBySubsystems}.
     *
     * <p>Свойства объектов ({@link FeatureComparisonNode}, в т.ч. внешние модули) не трогаем —
     * их видимость определяется родителем-объектом.
     *
     * <p>Не ждём появления EDT-{@code ViewerFilter} в {@code viewer.getFilters()} —
     * берём {@code editor.filterBySubsystems} (создаётся в {@code createFilterBySubsystems})
     * и ставим коррекцию, когда готов tree viewer ({@code createComparisonViewIfNecessary}).
     */
    private static class SubsystemFilterFix
    {
        private static final String FILTER_FLAG = "tormozit.subsystemFilterFix"; //$NON-NLS-1$
        private static final String LOG = "SubsystemFilterFix"; //$NON-NLS-1$
        private static final int MAX_ATTEMPTS = 60;
        private static final int RETRY_MS = 250;

        static void install(IEditorPart editor)
        {
            install(editor, 0);
        }

        private static void install(IEditorPart editor, int attempt)
        {
            if (editor == null)
                return;
            if (!ComfortSettings.isReplaceListFiltersEnabled())
            {
                Global.tempLog(LOG, "skip: replaceListFilters disabled"); //$NON-NLS-1$
                return;
            }

            Display display = Display.getDefault();
            Runnable body = () ->
            {
                AbstractTreeViewer viewer = resolveTreeViewer(editor);
                Object namedFilter = Global.getField(editor, "filterBySubsystems"); //$NON-NLS-1$
                Global.tempLog(LOG, "attempt=" + attempt //$NON-NLS-1$
                        + " editor=" + editor.getClass().getSimpleName() //$NON-NLS-1$
                        + "@" + System.identityHashCode(editor) //$NON-NLS-1$
                        + " viewer=" + (viewer != null) //$NON-NLS-1$
                        + " namedFilter=" + (namedFilter != null ? namedFilter.getClass().getSimpleName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$

                if (viewer != null && Boolean.TRUE.equals(viewer.getData(FILTER_FLAG)))
                    return;

                if (viewer == null || namedFilter == null)
                {
                    if (attempt < MAX_ATTEMPTS)
                        display.timerExec(RETRY_MS, () -> install(editor, attempt + 1));
                    else
                        Global.tempLog(LOG, "give up after " + attempt + " attempts"); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }

                try
                {
                    Object view = Global.getField(editor, "comparisonView"); //$NON-NLS-1$
                    Object treeControl = view instanceof DtComparisonView
                            ? ((DtComparisonView) view).getTreeControl()
                            : null;
                    CorrectionViewerFilter correction = new CorrectionViewerFilter(namedFilter);
                    viewer.addFilter(correction);
                    if (viewer instanceof CheckboxTreeViewer ctv && treeControl != null)
                        correction.installCheckHooks(ctv, treeControl, editor);
                    viewer.setData(FILTER_FLAG, Boolean.TRUE);
                    Global.tempLog(LOG, "CorrectionViewerFilter installed"); //$NON-NLS-1$
                }
                catch (Exception e)
                {
                    Global.tempLog(LOG, "install error: " + e); //$NON-NLS-1$
                }
            };

            if (Thread.currentThread() == display.getThread())
                body.run();
            else
                display.asyncExec(body);
        }

        private static AbstractTreeViewer resolveTreeViewer(IEditorPart editor)
        {
            Object view = Global.getField(editor, "comparisonView"); //$NON-NLS-1$
            if (!(view instanceof DtComparisonView))
                return null;
            Object treeControl = ((DtComparisonView) view).getTreeControl();
            if (treeControl == null)
                return null;
            Object viewer = Global.call(treeControl, "getTreeViewer"); //$NON-NLS-1$
            return (viewer instanceof AbstractTreeViewer) ? (AbstractTreeViewer) viewer : null;
        }

        /**
         * При отборе по подсистемам скрываем ветки вне состава подсистем:
         * «Манифест», «Настройки проекта» (symlink {@code Settings}).
         * «Конфигурация» уже прячет штатный EDT ({@code Symlink}/isTop).
         */
        private static boolean isNonSubsystemStructuralBranch(Object element)
        {
            if (element == null)
                return false;
            String simple = element.getClass().getSimpleName();
            if ("ManifestPartialModelNode".equals(simple)) //$NON-NLS-1$
                return true;
            if (!"UnsupportedObjectPartialModelNode".equals(simple)) //$NON-NLS-1$
                return false;
            if (!(element instanceof IDirectPartialModelNode node))
                return false;
            ComparisonNode cn;
            try
            {
                cn = node.retrieveComparisonNode();
            }
            catch (RuntimeException e)
            {
                return false;
            }
            if (!(cn instanceof SymlinkComparisonNode symlink))
                return false;
            String main = symlink.getMainSymlink();
            String other = symlink.getOtherSymlink();
            return "Settings".equals(main) || "Settings".equals(other); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static class CorrectionViewerFilter extends ViewerFilter
        {
            private final Object namedFilter;
            private final Object coreFilter;
            private final IQualifiedNameProvider qnProvider;
            private int decideLogsLeft = 80;
            private boolean verifiedAttached;
            private String lastDetail = ""; //$NON-NLS-1$

            CorrectionViewerFilter(Object namedFilter)
            {
                this.namedFilter = namedFilter;
                this.coreFilter = Global.invoke(namedFilter, "getCoreFilter"); //$NON-NLS-1$
                this.qnProvider = coreFilter == null
                        ? null
                        : (IQualifiedNameProvider) Global.getField(coreFilter, "qualifiedNameProvider"); //$NON-NLS-1$
                Global.tempLog(LOG, "ctor coreFilter=" + (coreFilter != null) //$NON-NLS-1$
                        + " qnProvider=" + (qnProvider != null)); //$NON-NLS-1$
            }





            /**
             * С активным отбором (подсистемы и/или штатный combo-фильтр): без EDT-каскада
             * по скрытым. {@code setMustBeMerged} только по видимым.
             * Корень / «Общие» / папки + кнопки «Отметить все»/снять.
             * Без отборов — прозрачный проброс в EDT.
             */
            void installCheckHooks(CheckboxTreeViewer ctv, Object treeControl, IEditorPart editor)
            {
                if (Boolean.TRUE.equals(ctv.getData(CHECK_HOOK_FLAG)))
                    return;

                if (!wrapCheckStateListeners(ctv))
                {
                    Global.tempLog(LOG, "check hooks: CheckStateListeners wrap failed" //$NON-NLS-1$
                            + " treeControl=" + (treeControl == null ? "null" //$NON-NLS-1$ //$NON-NLS-2$
                                    : treeControl.getClass().getSimpleName()));
                    return;
                }
                wrapToolbarSelectAllActions(editor, ctv);
                if (!Boolean.TRUE.equals(ctv.getData(SELECT_ALL_HOOK_FLAG)))
                    scheduleSelectAllWrap(editor, ctv, 0);
                wrapToolbarFilterActions(editor, ctv);
                if (!Boolean.TRUE.equals(ctv.getData(FILTER_ACTION_HOOK_FLAG)))
                    scheduleFilterActionWrap(editor, ctv, 0);
                installNamedFilterComboHook(editor, ctv);
                if (!Boolean.TRUE.equals(ctv.getData(NAMED_FILTER_HOOK_FLAG)))
                    scheduleNamedFilterComboHook(editor, ctv, 0);
                // CheckStateProvider — при любом скрывающем отборе (см. syncCheckHooksToFilterState).
                hookedEditor = editor;
                hookedCtv = ctv;
                syncCheckHooksToFilterState(ctv);
                ctv.setData(CHECK_HOOK_FLAG, Boolean.TRUE);
            }

            private IEditorPart hookedEditor;
            private CheckboxTreeViewer hookedCtv;
            private ICheckStateProvider edtCheckStateProvider;
            private boolean ourCheckStateProviderActive;
            private boolean filterWasActive;

            private void scheduleSelectAllWrap(IEditorPart editor, CheckboxTreeViewer ctv, int attempt)
            {
                if (ctv.getControl() == null || ctv.getControl().isDisposed())
                    return;
                ctv.getControl().getDisplay().timerExec(300, () ->
                {
                    if (ctv.getControl() == null || ctv.getControl().isDisposed())
                        return;
                    wrapToolbarSelectAllActions(editor, ctv);
                    if (!Boolean.TRUE.equals(ctv.getData(SELECT_ALL_HOOK_FLAG)) && attempt < 30)
                        scheduleSelectAllWrap(editor, ctv, attempt + 1);
                });
            }

            private void scheduleFilterActionWrap(IEditorPart editor, CheckboxTreeViewer ctv, int attempt)
            {
                if (ctv.getControl() == null || ctv.getControl().isDisposed())
                    return;
                ctv.getControl().getDisplay().timerExec(300, () ->
                {
                    if (ctv.getControl() == null || ctv.getControl().isDisposed())
                        return;
                    wrapToolbarFilterActions(editor, ctv);
                    if (!Boolean.TRUE.equals(ctv.getData(FILTER_ACTION_HOOK_FLAG)) && attempt < 30)
                        scheduleFilterActionWrap(editor, ctv, attempt + 1);
                });
            }

            private void scheduleNamedFilterComboHook(IEditorPart editor, CheckboxTreeViewer ctv, int attempt)
            {
                if (ctv.getControl() == null || ctv.getControl().isDisposed())
                    return;
                ctv.getControl().getDisplay().timerExec(300, () ->
                {
                    if (ctv.getControl() == null || ctv.getControl().isDisposed())
                        return;
                    installNamedFilterComboHook(editor, ctv);
                    if (!Boolean.TRUE.equals(ctv.getData(NAMED_FILTER_HOOK_FLAG)) && attempt < 30)
                        scheduleNamedFilterComboHook(editor, ctv, attempt + 1);
                });
            }

            private static final String CHECK_HOOK_FLAG = "tormozit.subsystemFilterCheckHook"; //$NON-NLS-1$
            private static final String SELECT_ALL_HOOK_FLAG = "tormozit.subsystemFilterSelectAllHook"; //$NON-NLS-1$
            private static final String FILTER_ACTION_HOOK_FLAG = "tormozit.subsystemFilterActionHook"; //$NON-NLS-1$
            private static final String NAMED_FILTER_HOOK_FLAG = "tormozit.namedFilterCheckRecalcHook"; //$NON-NLS-1$

            private boolean wrapCheckStateListeners(CheckboxTreeViewer ctv)
            {
                Object listObj = Global.getField(ctv, "checkStateListeners"); //$NON-NLS-1$
                if (listObj == null)
                    return false;
                Object raw = Global.invoke(listObj, "getListeners"); //$NON-NLS-1$
                if (!(raw instanceof Object[] existing))
                    return false;
                for (Object o : existing)
                {
                    if (o instanceof FilterAwareCheckStateListener)
                        return true;
                }
                ICheckStateListener[] originals = new ICheckStateListener[existing.length];
                for (int i = 0; i < existing.length; i++)
                {
                    if (!(existing[i] instanceof ICheckStateListener listener))
                        return false;
                    originals[i] = listener;
                }
                Global.invoke(listObj, "clear"); //$NON-NLS-1$
                Global.invoke(listObj, "add", new FilterAwareCheckStateListener(ctv, originals)); //$NON-NLS-1$
                Global.tempLog(LOG, "check hooks: CheckStateListeners wrapped n=" //$NON-NLS-1$
                        + originals.length);
                return true;
            }

            private void wrapToolbarSelectAllActions(IEditorPart editor, CheckboxTreeViewer ctv)
            {
                if (Boolean.TRUE.equals(ctv.getData(SELECT_ALL_HOOK_FLAG)))
                    return;
                Object tbmObj = Global.getField(editor, "toolBarManager"); //$NON-NLS-1$
                if (!(tbmObj instanceof ToolBarManager tbm))
                {
                    Global.tempLog(LOG, "selectAll hooks: toolBarManager missing"); //$NON-NLS-1$
                    return;
                }
                int wrapped = 0;
                for (IContributionItem item : tbm.getItems())
                {
                    if (!(item instanceof ActionContributionItem aci))
                        continue;
                    IAction action = aci.getAction();
                    if (action == null || action instanceof FilterAwareSelectAllAction)
                        continue;
                    String cn = action.getClass().getName();
                    boolean selectAll = cn.contains("SelectAllAction") && !cn.contains("Unselect"); //$NON-NLS-1$ //$NON-NLS-2$
                    boolean unselect = cn.contains("UnselectAllAction"); //$NON-NLS-1$
                    if (!selectAll && !unselect)
                        continue;
                    FilterAwareSelectAllAction wrapper =
                            new FilterAwareSelectAllAction(editor, ctv, action, selectAll);
                    if (!Global.setFieldForce(aci, "action", wrapper)) //$NON-NLS-1$
                    {
                        Global.tempLog(LOG, "selectAll hooks: setField action failed " + cn); //$NON-NLS-1$
                        continue;
                    }
                    wrapped++;
                }
                if (wrapped > 0)
                    tbm.update(true);
                ctv.setData(SELECT_ALL_HOOK_FLAG, Boolean.TRUE);
                Global.tempLog(LOG, "selectAll hooks: wrapped n=" + wrapped); //$NON-NLS-1$
            }

            /**
             * После смены/сброса отбора по подсистемам — сохранить текущую строку
             * или подняться к ближайшему видимому родителю.
             */
            private void wrapToolbarFilterActions(IEditorPart editor, CheckboxTreeViewer ctv)
            {
                if (Boolean.TRUE.equals(ctv.getData(FILTER_ACTION_HOOK_FLAG)))
                    return;
                Object tbmObj = Global.getField(editor, "toolBarManager"); //$NON-NLS-1$
                if (!(tbmObj instanceof ToolBarManager tbm))
                {
                    Global.tempLog(LOG, "filterAction hooks: toolBarManager missing"); //$NON-NLS-1$
                    return;
                }
                int wrapped = 0;
                for (IContributionItem item : tbm.getItems())
                {
                    if (!(item instanceof ActionContributionItem aci))
                        continue;
                    IAction action = aci.getAction();
                    if (action == null || action instanceof FilterAwareSubsystemFilterAction)
                        continue;
                    String cn = action.getClass().getName();
                    boolean apply = cn.contains("FilterBySubsystemsAction") //$NON-NLS-1$
                            && !cn.contains("DropFilter"); //$NON-NLS-1$
                    boolean drop = cn.contains("DropFilterBySubsystemsAction"); //$NON-NLS-1$
                    if (!apply && !drop)
                        continue;
                    FilterAwareSubsystemFilterAction wrapper =
                            new FilterAwareSubsystemFilterAction(editor, ctv, action);
                    if (!Global.setFieldForce(aci, "action", wrapper)) //$NON-NLS-1$
                    {
                        Global.tempLog(LOG, "filterAction hooks: setField action failed " + cn); //$NON-NLS-1$
                        continue;
                    }
                    wrapped++;
                }
                if (wrapped > 0)
                    tbm.update(true);
                ctv.setData(FILTER_ACTION_HOOK_FLAG, Boolean.TRUE);
                Global.tempLog(LOG, "filterAction hooks: wrapped n=" + wrapped); //$NON-NLS-1$
            }

            private final class FilterAwareSubsystemFilterAction extends Action
            {
                private final IEditorPart editor;
                private final CheckboxTreeViewer ctv;
                private final IAction original;

                FilterAwareSubsystemFilterAction(IEditorPart editor, CheckboxTreeViewer ctv,
                        IAction original)
                {
                    super(original.getText() != null ? original.getText() : "", //$NON-NLS-1$
                            original.getStyle());
                    this.editor = editor;
                    this.ctv = ctv;
                    this.original = original;
                    setImageDescriptor(original.getImageDescriptor());
                    setDisabledImageDescriptor(original.getDisabledImageDescriptor());
                    setHoverImageDescriptor(original.getHoverImageDescriptor());
                    setToolTipText(original.getToolTipText());
                    setEnabled(original.isEnabled());
                    super.setChecked(original.isChecked());
                }

                @Override
                public void run()
                {
                    Object saved = firstSelectedElement(ctv);
                    original.run();
                    syncApplyFilterCheckedFromEditor(editor);
                    recalculateCheckStatesAfterFilterChange(ctv);
                    restoreSelectionOrParent(ctv, saved);
                }

                @Override
                public void setChecked(boolean checked)
                {
                    super.setChecked(checked);
                    original.setChecked(checked);
                }

                void setCheckedUiOnly(boolean checked)
                {
                    super.setChecked(checked);
                }
            }

            /** Тулбар держит wrapper, EDT пишет checked в поле editor.filterBySubsystemsAction. */
            private void syncApplyFilterCheckedFromEditor(IEditorPart editor)
            {
                Object applyOrig = Global.getField(editor, "filterBySubsystemsAction"); //$NON-NLS-1$
                if (!(applyOrig instanceof IAction apply))
                    return;
                Object tbmObj = Global.getField(editor, "toolBarManager"); //$NON-NLS-1$
                if (!(tbmObj instanceof ToolBarManager tbm))
                    return;
                for (IContributionItem item : tbm.getItems())
                {
                    if (!(item instanceof ActionContributionItem aci))
                        continue;
                    IAction action = aci.getAction();
                    if (action instanceof FilterAwareSubsystemFilterAction wrapper
                            && wrapper.original == apply)
                    {
                        wrapper.setCheckedUiOnly(apply.isChecked());
                        break;
                    }
                }
                tbm.update(true);
            }

            private static Object firstSelectedElement(CheckboxTreeViewer ctv)
            {
                if (ctv == null || ctv.getControl() == null || ctv.getControl().isDisposed())
                    return null;
                ISelection sel = ctv.getSelection();
                if (!(sel instanceof IStructuredSelection ss) || ss.isEmpty())
                    return null;
                return ss.getFirstElement();
            }

            private void restoreSelectionOrParent(CheckboxTreeViewer ctv, Object element)
            {
                if (element == null || ctv == null
                        || ctv.getControl() == null || ctv.getControl().isDisposed())
                    return;
                Object candidate = element;
                while (candidate != null)
                {
                    ctv.setSelection(new StructuredSelection(candidate), true);
                    ISelection sel = ctv.getSelection();
                    if (sel instanceof IStructuredSelection ss
                            && !ss.isEmpty()
                            && candidate.equals(ss.getFirstElement()))
                    {
                        ctv.reveal(candidate);
                        Global.tempLog(LOG, "filter selection restored el=" //$NON-NLS-1$
                                + candidate.getClass().getSimpleName()
                                + (candidate == element ? " (same)" : " (parent)")); //$NON-NLS-1$ //$NON-NLS-2$
                        return;
                    }
                    candidate = candidate instanceof IPartialModelNode n ? n.getParent() : null;
                }
                Global.tempLog(LOG, "filter selection: no visible ancestor"); //$NON-NLS-1$
            }

            /**
             * Слушатель combo «Фильтр» (отличия / сторона / …): после смены — пересчёт пометок.
             */
            private void installNamedFilterComboHook(IEditorPart editor, CheckboxTreeViewer ctv)
            {
                if (Boolean.TRUE.equals(ctv.getData(NAMED_FILTER_HOOK_FLAG)))
                    return;
                Object view = Global.getField(editor, "comparisonView"); //$NON-NLS-1$
                if (!(view instanceof DtComparisonView dcv) || dcv.isDisposed())
                    return;
                ComboViewer combo = ThreeWayOnlyPresentFiltersFix.findFilterComboViewer(dcv);
                if (combo == null)
                    return;
                combo.addSelectionChangedListener((ISelectionChangedListener) event ->
                {
                    if (ctv.getControl() == null || ctv.getControl().isDisposed())
                        return;
                    // После EDT refresh дерева
                    ctv.getControl().getDisplay().asyncExec(
                            () -> recalculateCheckStatesAfterFilterChange(ctv));
                });
                ctv.setData(NAMED_FILTER_HOOK_FLAG, Boolean.TRUE);
                Global.tempLog(LOG, "namedFilter combo: recalc hook installed"); //$NON-NLS-1$
            }

            /**
             * После смены фильтра: только агрегаты, которым при фильтре ставили
             * «полную пометку» (кэш), плюс их предки — не всё дерево.
             */
            private void recalculateCheckStatesAfterFilterChange(CheckboxTreeViewer ctv)
            {
                if (ctv == null || ctv.getControl() == null || ctv.getControl().isDisposed())
                    return;
                LinkedHashSet<IPartialModelNode> affected =
                        new LinkedHashSet<>(aggregateCheckedUi.keySet());
                if (affected.isEmpty())
                {
                    clearAggregateUiCache();
                    syncCheckHooksToFilterState(ctv);
                    Global.tempLog(LOG, "recalc checks: skip (no cached aggregates)"); //$NON-NLS-1$
                    return;
                }
                // Предки тоже могли показывать «полную» из‑за кэша / UI.
                LinkedHashSet<IPartialModelNode> withAncestors = new LinkedHashSet<>(affected);
                for (IPartialModelNode node : affected)
                {
                    for (IPartialModelNode p = node.getParent(); p != null; p = p.getParent())
                        withAncestors.add(p);
                }
                clearAggregateUiCache();
                // Подтянуть MergeSettings только в поддеревьях затронутых агрегатов.
                for (IPartialModelNode node : affected)
                    syncSubtreeCheckCacheFromMergeSettings(node);
                syncCheckHooksToFilterState(ctv);
                // Снизу вверх по глубине: сначала более глубокие узлы.
                ArrayList<IPartialModelNode> ordered = new ArrayList<>(withAncestors);
                ordered.sort((a, b) -> Integer.compare(depthOf(b), depthOf(a)));
                for (IPartialModelNode node : ordered)
                    paintAggregateFromChildren(ctv, node);
                Global.tempLog(LOG, "recalc checks after filter change n=" + ordered.size() //$NON-NLS-1$
                        + " (cachedAggregates=" + affected.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }

            private static int depthOf(IPartialModelNode node)
            {
                int d = 0;
                for (IPartialModelNode p = node.getParent(); p != null; p = p.getParent())
                    d++;
                return d;
            }

            private void paintAggregateFromChildren(CheckboxTreeViewer ctv, IPartialModelNode node)
            {
                if (!(isAggregateCheckNode(node) || isVisibleOnlyClickTarget(node)))
                    return;
                AggregateCheck agg = computeAggregateFromLoadedChildren(node);
                if (agg == null)
                    return;
                if (!(node instanceof VirtualFolderPartialModelNode))
                {
                    node.setChecked(agg.checked);
                    Global.invoke(node, "setGrayed", Boolean.valueOf(agg.grayed)); //$NON-NLS-1$
                }
                // Не ctv.setGrayed/setChecked — они делают internalExpand и на
                // «Справочники» с фильтром отличий подвешивают UI (тысячи BM getChildren).
                paintTreeItemCheckNoExpand(ctv, node, agg.checked, agg.grayed);
            }

            private static final class AggregateCheck
            {
                final boolean checked;
                final boolean grayed;

                AggregateCheck(boolean checked, boolean grayed)
                {
                    this.checked = checked;
                    this.grayed = grayed;
                }
            }

            private AggregateCheck computeAggregateFromLoadedChildren(IPartialModelNode node)
            {
                Collection<IPartialModelNode> children = node.getChildren();
                if (children == null || children.isEmpty())
                    return null;
                boolean anyChecked = false;
                boolean anyUnchecked = false;
                boolean anyGrayed = false;
                int considered = 0;
                for (IPartialModelNode child : children)
                {
                    boolean consider = child.isCheckable()
                            || isAggregateCheckNode(child)
                            || isVisibleOnlyClickTarget(child);
                    if (!consider)
                        continue;
                    considered++;
                    if (child.isGrayed())
                        anyGrayed = true;
                    if (child.isChecked())
                        anyChecked = true;
                    else
                        anyUnchecked = true;
                }
                if (considered == 0)
                    return null;
                if (anyGrayed || (anyChecked && anyUnchecked))
                    return new AggregateCheck(true, true);
                if (anyChecked)
                    return new AggregateCheck(true, false);
                return new AggregateCheck(false, false);
            }

            /**
             * Без скрывающих отборов — штатный EDT CheckStateProvider.
             * С отбором (подсистемы / combo «Показывать отличия» и т.п.) — кэш агрегатов.
             */
            private void syncCheckHooksToFilterState(CheckboxTreeViewer ctv)
            {
                if (ctv == null || ctv.getControl() == null || ctv.getControl().isDisposed())
                    return;
                if (!shouldRestrictChecksToVisible())
                {
                    if (ourCheckStateProviderActive)
                    {
                        ctv.setCheckStateProvider(edtCheckStateProvider);
                        ourCheckStateProviderActive = false;
                        clearAggregateUiCache();
                        Global.tempLog(LOG, "filters off: restored EDT CheckStateProvider"); //$NON-NLS-1$
                    }
                    return;
                }
                if (!ourCheckStateProviderActive)
                    wrapCheckStateProviderCached(ctv);
            }

            /**
             * Provider читает только кэш, заполненный при visible-only клике/SelectAll.
             * Никакого обхода/resolve/filter в paint. Только при активном отборе.
             */
            private void wrapCheckStateProviderCached(CheckboxTreeViewer ctv)
            {
                Object existingObj = Global.getField(ctv, "checkStateProvider"); //$NON-NLS-1$
                if (existingObj instanceof FilterAwareCheckStateProvider)
                {
                    ourCheckStateProviderActive = true;
                    return;
                }
                edtCheckStateProvider =
                        existingObj instanceof ICheckStateProvider p ? p : null;
                ctv.setCheckStateProvider(new FilterAwareCheckStateProvider(edtCheckStateProvider));
                ourCheckStateProviderActive = true;
                Global.tempLog(LOG, "check hooks: CheckStateProvider wrapped (cache, filter on)"); //$NON-NLS-1$
            }

            /** checked/grayed агрегатов при активном отборе — без обхода в paint. */
            private final Map<IPartialModelNode, Boolean> aggregateCheckedUi =
                    new IdentityHashMap<>();
            private final Map<IPartialModelNode, Boolean> aggregateGrayedUi =
                    new IdentityHashMap<>();

            private void clearAggregateUiCache()
            {
                aggregateCheckedUi.clear();
                aggregateGrayedUi.clear();
            }

            private void putAggregateUi(IPartialModelNode node, boolean checked, boolean grayed)
            {
                aggregateCheckedUi.put(node, Boolean.valueOf(checked));
                aggregateGrayedUi.put(node, Boolean.valueOf(grayed));
            }

            /**
             * После массовой отметки видимых: все затронутые агрегаты = checked, не gray.
             * Только уже загруженные дети (getChildren), без content provider.
             */
            private void markAggregatesUiAfterBulk(IPartialModelNode node, boolean checked)
            {
                Collection<IPartialModelNode> children = node.getChildren();
                if (children != null)
                {
                    for (IPartialModelNode child : children)
                    {
                        if (shouldRecurseOnly(child))
                            markAggregatesUiAfterBulk(child, checked);
                    }
                }
                if (isAggregateCheckNode(node) || isVisibleOnlyClickTarget(node))
                    putAggregateUi(node, checked, false);
            }

            /**
             * Кэш агрегатов + галочки уже существующих TreeItem (без expand).
             * {@link CheckboxTreeViewer#setGrayed}/{@code setChecked} вызывают
             * {@code internalExpand} → createChildren → PartialModelController.getChildren
             * и на большой папке («Справочники» + «Показывать отличия») UI зависает.
             */
            private void applyAggregateTreeCheckUi(CheckboxTreeViewer ctv, IPartialModelNode node,
                    boolean checked)
            {
                markAggregatesUiAfterBulk(node, checked);
                paintAggregateChecks(ctv, node, checked);
            }

            private void paintAggregateChecks(CheckboxTreeViewer ctv, IPartialModelNode node,
                    boolean checked)
            {
                if (isAggregateCheckNode(node) || isVisibleOnlyClickTarget(node))
                    paintTreeItemCheckNoExpand(ctv, node, checked, false);
                Collection<IPartialModelNode> children = node.getChildren();
                if (children == null)
                    return;
                for (IPartialModelNode child : children)
                {
                    if (shouldRecurseOnly(child))
                        paintAggregateChecks(ctv, child, checked);
                }
            }

            /**
             * Ставит checked/grayed только если TreeItem уже создан — без
             * {@code setGrayed}/{@code setChecked} viewer'а (они разворачивают узел).
             */
            private static void paintTreeItemCheckNoExpand(CheckboxTreeViewer ctv, Object element,
                    boolean checked, boolean grayed)
            {
                if (ctv == null || element == null)
                    return;
                Widget w = ctv.testFindItem(element);
                if (!(w instanceof TreeItem item) || item.isDisposed())
                    return;
                item.setChecked(checked);
                item.setGrayed(grayed);
            }

            private void invalidateAggregateUiAncestors(IPartialModelNode from)
            {
                for (IPartialModelNode p = from; p != null; p = p.getParent())
                {
                    aggregateCheckedUi.remove(p);
                    aggregateGrayedUi.remove(p);
                }
            }

            /**
             * Агрегат: проект/папки/коллекции (для клика и UI TreeItem).
             */
            private static boolean isAggregateCheckNode(Object el)
            {
                return el instanceof ProjectPartialModelNode
                        || el instanceof VirtualFolderPartialModelNode
                        || el instanceof ICollectionPartialNode;
            }

            /**
             * Клик visible-only: папки/коллекции/проект/«Конфигурация».
             * Одиночный MD (обработка со «Формы») — штатный EDT: каскад только
             * в его поддерево, скрытых соседних объектов не трогает.
             */
            private static boolean isVisibleOnlyClickTarget(Object el)
            {
                if (isAggregateCheckNode(el))
                    return true;
                if (!(el instanceof IPartialModelNode node))
                    return false;
                if (needsOwnMergeFlag(node))
                    return false;
                if (isConfigurationPartialModelNode(node))
                    return true;
                if (!node.isCheckable() && node.hasChildren())
                    return true;
                return false;
            }

            private static boolean isFolderOrCollection(Object el)
            {
                return el instanceof VirtualFolderPartialModelNode
                        || el instanceof ICollectionPartialNode;
            }

            /**
             * Только обход вниз без setMustBeMerged: проект, папки/коллекции,
             * нечекable-контейнеры, «Конфигурация».
             * Checkable MD с «Формы»/свойствами сюда НЕ входит — иначе каскад
             * resolveChildren по формам каждой (в т.ч. скрытой) обработки ×100.
             */
            private static boolean shouldRecurseOnly(IPartialModelNode node)
            {
                if (node instanceof ProjectPartialModelNode)
                    return true;
                if (isFolderOrCollection(node))
                    return true;
                if (isConfigurationPartialModelNode(node))
                    return true;
                if (!node.isCheckable())
                    return node.hasChildren();
                return false;
            }

            /**
             * Checkable MD-объект с папками-детьми (обработка, справочник…):
             * нужен setMustBeMerged на себе, плюс обход видимых детей.
             * Папки / проект / «Конфигурация» — нет.
             */
            private static boolean needsOwnMergeFlag(IPartialModelNode node)
            {
                if (node == null || !node.isCheckable())
                    return false;
                if (node instanceof ProjectPartialModelNode || isFolderOrCollection(node))
                    return false;
                if (isConfigurationPartialModelNode(node))
                    return false;
                return hasStructuralChild(node);
            }

            private static boolean isConfigurationPartialModelNode(Object element)
            {
                if (element == null)
                    return false;
                String simple = element.getClass().getSimpleName();
                if ("ConfigurationPartialModelNode".equals(simple)) //$NON-NLS-1$
                    return true;
                if (!(element instanceof IDirectPartialModelNode node))
                    return false;
                try
                {
                    ComparisonNode cn = node.retrieveComparisonNode();
                    if (!(cn instanceof SymlinkComparisonNode symlink))
                        return false;
                    String main = symlink.getMainSymlink();
                    String other = symlink.getOtherSymlink();
                    return "Configuration".equals(main) || "Configuration".equals(other); //$NON-NLS-1$ //$NON-NLS-2$
                }
                catch (RuntimeException e)
                {
                    return false;
                }
            }

            private static boolean hasStructuralChild(IPartialModelNode node)
            {
                Collection<IPartialModelNode> children = node.getChildren();
                if (children == null)
                    return false;
                for (IPartialModelNode child : children)
                {
                    if (isFolderOrCollection(child) || child instanceof ProjectPartialModelNode)
                        return true;
                }
                return false;
            }

            private final class FilterAwareCheckStateProvider implements ICheckStateProvider
            {
                private final ICheckStateProvider delegate;

                FilterAwareCheckStateProvider(ICheckStateProvider delegate)
                {
                    this.delegate = delegate;
                }

                @Override
                public boolean isChecked(Object element)
                {
                    // Без скрывающих отборов этот provider не должен быть установлен.
                    if (!shouldRestrictChecksToVisible())
                        return delegate != null ? delegate.isChecked(element)
                                : element instanceof IPartialModelNode n && n.isChecked();
                    if (element instanceof IPartialModelNode node && isAggregateCheckNode(node))
                    {
                        Boolean cached = aggregateCheckedUi.get(node);
                        if (cached != null)
                            return cached.booleanValue();
                    }
                    return delegate != null ? delegate.isChecked(element)
                            : element instanceof IPartialModelNode n && n.isChecked();
                }

                @Override
                public boolean isGrayed(Object element)
                {
                    if (!shouldRestrictChecksToVisible())
                        return delegate != null ? delegate.isGrayed(element)
                                : element instanceof IPartialModelNode n && n.isGrayed();
                    if (element instanceof IPartialModelNode node && isAggregateCheckNode(node))
                    {
                        Boolean cached = aggregateGrayedUi.get(node);
                        if (cached != null)
                            return cached.booleanValue();
                    }
                    return delegate != null ? delegate.isGrayed(element)
                            : element instanceof IPartialModelNode n && n.isGrayed();
                }
            }
            private final class FilterAwareSelectAllAction extends Action
            {
                private final IEditorPart editor;
                private final CheckboxTreeViewer ctv;
                private final IAction original;
                private final boolean check;

                FilterAwareSelectAllAction(IEditorPart editor, CheckboxTreeViewer ctv,
                        IAction original, boolean check)
                {
                    this.editor = editor;
                    this.ctv = ctv;
                    this.original = original;
                    this.check = check;
                    setImageDescriptor(original.getImageDescriptor());
                    setDisabledImageDescriptor(original.getDisabledImageDescriptor());
                    setHoverImageDescriptor(original.getHoverImageDescriptor());
                    setToolTipText(original.getToolTipText());
                    setText(original.getText());
                    setEnabled(original.isEnabled());
                }

                @Override
                public void run()
                {
                    if (!shouldRestrictChecksToVisible())
                    {
                        original.run();
                        return;
                    }
                    int total = applyVisibleOnlyOnComparisonRoots(editor, ctv, check);
                    Global.tempLog(LOG, "selectAll visible-only: check=" + check //$NON-NLS-1$
                            + " applied=" + total); //$NON-NLS-1$
                }
            }

            private int applyVisibleOnlyOnComparisonRoots(IEditorPart editor, CheckboxTreeViewer ctv,
                    boolean checked)
            {
                Object listObj = Global.getField(editor, "comparisonArtifactsList"); //$NON-NLS-1$
                if (!(listObj instanceof List<?> artifacts))
                    return 0;
                applyLogLeft = 30;
                int total = 0;
                for (Object artifact : artifacts)
                {
                    Object pm = Global.getField(artifact, "partialModel"); //$NON-NLS-1$
                    if (pm == null)
                        continue;
                    Object rootObj = Global.invoke(pm, "getRoot"); //$NON-NLS-1$
                    if (!(rootObj instanceof ProjectPartialModelNode root))
                        continue;
                    total += applyCheckVisibleOnly(ctv, root, checked);
                    applyAggregateTreeCheckUi(ctv, root, checked);
                }
                return total;
            }

            private final class FilterAwareCheckStateListener implements ICheckStateListener
            {
                private final CheckboxTreeViewer ctv;
                private final ICheckStateListener[] delegates;

                FilterAwareCheckStateListener(CheckboxTreeViewer ctv,
                        ICheckStateListener[] delegates)
                {
                    this.ctv = ctv;
                    this.delegates = delegates;
                }

                @Override
                public void checkStateChanged(CheckStateChangedEvent event)
                {
                    // Без скрывающих отборов — только штатный EDT.
                    if (!shouldRestrictChecksToVisible())
                    {
                        syncCheckHooksToFilterState(ctv);
                        for (ICheckStateListener delegate : delegates)
                            delegate.checkStateChanged(event);
                        return;
                    }

                    syncCheckHooksToFilterState(ctv);

                    Object el = event.getElement();
                    boolean checked = event.getChecked();
                    boolean bulk = isVisibleOnlyClickTarget(el);
                    Global.tempLog(LOG, "checkState: el=" //$NON-NLS-1$
                            + (el == null ? "null" : el.getClass().getSimpleName()) //$NON-NLS-1$
                            + " checked=" + checked //$NON-NLS-1$
                            + " subsystemFilterEmpty=" + isFilterEmpty() //$NON-NLS-1$
                            + " namedFilterActive=" + isNamedComparisonFilterActive() //$NON-NLS-1$
                            + " bulk=" + bulk); //$NON-NLS-1$

                    // С отбором: фильтр только на top-MD; каскад внутрь объекта — штатный.
                    if (bulk && el instanceof IPartialModelNode node)
                    {
                        applyLogLeft = 30;
                        long t0 = System.nanoTime();
                        int applied = applyCheckVisibleOnly(ctv, node, checked);
                        syncAncestorsCheckCacheFromMergeSettings(node);
                        applyAggregateTreeCheckUi(ctv, node, checked);
                        for (IPartialModelNode p = node.getParent(); p != null; p = p.getParent())
                            ctv.update(p, null);
                        Global.tempLog(LOG, "check visible-only: applied=" + applied //$NON-NLS-1$
                                + " checked=" + checked //$NON-NLS-1$
                                + " ms=" + ((System.nanoTime() - t0) / 1_000_000L)); //$NON-NLS-1$
                        return;
                    }

                    if (el instanceof IPartialModelNode leaf)
                        invalidateAggregateUiAncestors(leaf);

                    for (ICheckStateListener delegate : delegates)
                        delegate.checkStateChanged(event);
                }
            }

            /** Отбор по подсистемам пуст (не путать со штатным combo-фильтром). */
            private boolean isFilterEmpty()
            {
                return coreFilter == null
                        || Boolean.TRUE.equals(Global.invoke(coreFilter, "isFilterEmpty")); //$NON-NLS-1$
            }

            /**
             * Каскад пометок только по видимым: активен отбор по подсистемам
             * и/или штатный фильтр combo (не {@link INamedViewerFilter#NONE}).
             */
            private boolean shouldRestrictChecksToVisible()
            {
                return !isFilterEmpty() || isNamedComparisonFilterActive();
            }

            private boolean isNamedComparisonFilterActive()
            {
                if (hookedEditor == null)
                    return false;
                Object view = Global.getField(hookedEditor, "comparisonView"); //$NON-NLS-1$
                if (!(view instanceof DtComparisonView dcv) || dcv.isDisposed())
                    return false;
                INamedViewerFilter current = dcv.getCurrentNamedViewerFilter();
                return current != null && current != INamedViewerFilter.NONE;
            }

            private boolean isVisibleInViewer(Viewer viewer, Object parent, Object element)
            {
                if (viewer instanceof StructuredViewer structured)
                {
                    for (ViewerFilter f : structured.getFilters())
                    {
                        if (!f.select(viewer, parent, element))
                            return false;
                    }
                    return true;
                }
                return select(viewer, parent, element);
            }

            /**
             * Дети узла с подгрузкой через content provider (иначе у свёрнутых
             * коллекций getChildren() пуст → клик «ничего не делает», applied=0).
             */
            private Collection<IPartialModelNode> resolveChildren(CheckboxTreeViewer ctv,
                    IPartialModelNode parent)
            {
                Object cp = ctv.getContentProvider();
                if (cp instanceof ITreeContentProvider tcp)
                {
                    Object[] loaded = tcp.getChildren(parent);
                    if (loaded != null && loaded.length > 0)
                    {
                        List<IPartialModelNode> out = new ArrayList<>(loaded.length);
                        for (Object o : loaded)
                        {
                            if (o instanceof IPartialModelNode n)
                                out.add(n);
                        }
                        if (!out.isEmpty())
                            return out;
                    }
                }
                Collection<IPartialModelNode> children = parent.getChildren();
                return children != null ? children : List.of();
            }

            /**
             * Скрывающие отборы (подсистемы / combo): только видимые top-MD
             * (Справочник, Обработка, …). Для каждого — штатный каскад EDT на всё
             * его поддерево, без повторной фильтрации внутри объекта.
             */
            private int applyCheckVisibleOnly(CheckboxTreeViewer ctv, IPartialModelNode parent,
                    boolean checked)
            {
                int applied = 0;
                int visible = 0;
                long t0 = System.nanoTime();
                Collection<IPartialModelNode> children = resolveChildren(ctv, parent);
                for (IPartialModelNode child : children)
                {
                    // Контейнеры не гоняем через ViewerFilter.select — у папок select
                    // рекурсивно обходит всех детей (в т.ч. скрытых) → зависание.
                    if (shouldRecurseOnly(child))
                    {
                        applied += applyCheckVisibleOnly(ctv, child, checked);
                        continue;
                    }
                    if (!isVisibleForCheck(ctv, parent, child))
                        continue;
                    visible++;
                    if (!child.isCheckable())
                        continue;
                    if (!applyStockTopObjectCascade(ctv, child, checked))
                        continue;
                    applied++;
                }
                if (parent instanceof ICollectionPartialNode || parent instanceof VirtualFolderPartialModelNode)
                {
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    Global.tempLog(LOG, "visible-only folder=" //$NON-NLS-1$
                            + parent.getClass().getSimpleName()
                            + " children=" + children.size() //$NON-NLS-1$
                            + " visible=" + visible //$NON-NLS-1$
                            + " applied=" + applied //$NON-NLS-1$
                            + " ms=" + ms); //$NON-NLS-1$
                }
                return applied;
            }

            /**
             * Штатный каскад на top-MD и всё поддерево (как {@code PartialModelController.check}):
             * BM {@code setMustBeMergedToTree}, синхрон кэша загруженных узлов, refresh UI.
             * Фильтр сюда не применяется.
             */
            private boolean applyStockTopObjectCascade(CheckboxTreeViewer ctv, IPartialModelNode node,
                    boolean want)
            {
                IComparisonSession session = node.getComparisonSession();
                long id = node.getNodeId();
                boolean ok;
                if (session != null && id != -1L)
                {
                    // Всегда BM-каскад: node.check() no-op, если кэш уже == want,
                    // а «Формы» при этом могут быть рассинхронены.
                    ok = session.setMustBeMerged(id, want, true);
                    node.setChecked(want);
                }
                else
                {
                    node.check(want);
                    ok = node.isChecked() == want;
                }
                if (applyLogLeft > 0)
                {
                    applyLogLeft--;
                    Global.tempLog(LOG, "stock cascade id=" + id + " want=" + want //$NON-NLS-1$ //$NON-NLS-2$
                            + " ok=" + ok + " el=" + node.getClass().getSimpleName()); //$NON-NLS-1$
                }
                // Кэш checked/grayed по уже загруженным потомкам (без resolveChildren).
                syncSubtreeCheckCacheFromMergeSettings(node);
                // Только уже созданные TreeItem — refresh(node,true) = createChildren
                // на каждый справочник при bulk по «Справочники».
                paintExistingSubtreeChecksFromModel(ctv, node);
                return ok;
            }

            /**
             * Обновляет галочки на уже материализованных TreeItem (без expand).
             */
            private static void paintExistingSubtreeChecksFromModel(CheckboxTreeViewer ctv,
                    Object element)
            {
                if (ctv == null || element == null)
                    return;
                Widget w = ctv.testFindItem(element);
                if (!(w instanceof TreeItem item) || item.isDisposed())
                    return;
                if (element instanceof IPartialModelNode node)
                {
                    item.setChecked(node.isChecked());
                    item.setGrayed(node.isGrayed());
                }
                TreeItem[] kids = item.getItems();
                if (kids == null)
                    return;
                for (TreeItem childItem : kids)
                {
                    if (childItem == null || childItem.isDisposed())
                        continue;
                    Object data = childItem.getData();
                    if (data != null)
                        paintExistingSubtreeChecksFromModel(ctv, data);
                }
            }

            /**
             * Видимость для каскада флажков: без {@code ViewerFilter.select} по папкам
             * (дорого) и без oneSide-логов во время bulk.
             */
            private boolean isVisibleForCheck(Viewer viewer, Object parent, Object element)
            {
                boolean prev = suppressDecideLogs;
                suppressDecideLogs = true;
                try
                {
                    return isVisibleInViewer(viewer, parent, element);
                }
                finally
                {
                    suppressDecideLogs = prev;
                }
            }

            private boolean suppressDecideLogs;

            private int applyLogLeft = 30;

            /**
             * Как {@code MdObjectPartialModelController.refreshNode}:
             * checked ← isMustBeMerged, grayed ← isHaveChildrenExcludedFromMerge.
             * VirtualFolder считает сам по детям — setChecked/setGrayed у него no-op.
             */
            private void syncAncestorsCheckCacheFromMergeSettings(IPartialModelNode from)
            {
                int n = 0;
                for (IPartialModelNode p = from; p != null; p = p.getParent())
                {
                    if (p instanceof VirtualFolderPartialModelNode)
                        continue;
                    if (!syncCheckCacheFromMergeSettings(p))
                        continue;
                    n++;
                }
                Global.tempLog(LOG, "ancestor cache synced n=" + n //$NON-NLS-1$
                        + " from=" + from.getClass().getSimpleName()); //$NON-NLS-1$
            }

            private void syncSubtreeCheckCacheFromMergeSettings(IPartialModelNode root)
            {
                if (!(root instanceof VirtualFolderPartialModelNode))
                    syncCheckCacheFromMergeSettings(root);
                // Только уже загруженные дети — без content provider (иначе зависание).
                Collection<IPartialModelNode> children = root.getChildren();
                if (children == null)
                    return;
                for (IPartialModelNode child : children)
                    syncSubtreeCheckCacheFromMergeSettings(child);
            }

            private boolean syncCheckCacheFromMergeSettings(IPartialModelNode node)
            {
                ComparisonNode cn;
                try
                {
                    cn = node.retrieveComparisonNode();
                }
                catch (RuntimeException e)
                {
                    return false;
                }
                if (cn == null)
                    return false;
                MergeSettings ms = cn.getMergeSettings();
                if (ms == null)
                    return false;
                boolean must = ms.isMustBeMerged();
                boolean excl = ms.isHaveChildrenExcludedFromMerge();
                node.setChecked(must);
                // setGrayed есть на AbstractNodeWithLabels*, не на IPartialModelNode
                Global.invoke(node, "setGrayed", Boolean.valueOf(excl)); //$NON-NLS-1$
                return true;
            }

            @Override
            public boolean select(Viewer viewer, Object parentElement, Object element)
            {
                if (coreFilter == null)
                    return true;

                if (isFilterEmpty())
                {
                    verifiedAttached = false;
                    if (filterWasActive)
                    {
                        filterWasActive = false;
                        CheckboxTreeViewer ctv = hookedCtv;
                        if (ctv == null && viewer instanceof CheckboxTreeViewer c)
                            ctv = c;
                        if (ctv != null)
                            recalculateCheckStatesAfterFilterChange(ctv);
                        else
                            clearAggregateUiCache();
                        Global.tempLog(LOG, "subsystem filter off: recalc checkmarks"); //$NON-NLS-1$
                    }
                    return true;
                }

                filterWasActive = true;
                if (hookedCtv != null)
                    syncCheckHooksToFilterState(hookedCtv);
                else if (viewer instanceof CheckboxTreeViewer ctv)
                    syncCheckHooksToFilterState(ctv);

                // Если EDT/Combo сбросил фильтры через setFilters — вернуть коррекцию
                if (viewer instanceof AbstractTreeViewer treeViewer)
                    ensureAttached(treeViewer);

                // Манифест / Настройки проекта — вне состава подсистем
                if (isNonSubsystemStructuralBranch(element))
                    return false;

                // Как ViewerFilterBySubsystems$1: папка видна, только если есть видимый потомок
                if (element instanceof VirtualFolderPartialModelNode
                        || (element instanceof ICollectionPartialNode collection
                                && collection.hasChildren()))
                {
                    IPartialModelNode folder = (IPartialModelNode) element;
                    if (!folder.hasChildren())
                        return false;
                    Collection<IPartialModelNode> children = folder.getChildren();
                    if (children == null)
                        return false;
                    for (IPartialModelNode child : children)
                    {
                        if (select(viewer, element, child))
                            return true;
                    }
                    return false;
                }

                if (element instanceof ICollectionPartialNode collection)
                {
                    FeatureCollectionComparisonNode fcn = collection.retrieveComparisonNode();
                    if (fcn == null)
                        return false;
                    IComparisonSession session = collection.getComparisonSession();
                    if (session == null)
                        return false;
                    for (Object childObj : fcn.getChildren())
                    {
                        if (!(childObj instanceof ComparisonNode childCn))
                            continue;
                        if (isComparisonNodeVisible(childCn, session))
                            return true;
                    }
                    return false;
                }

                if (!(element instanceof IDirectPartialModelNode))
                    return true;

                IDirectPartialModelNode node = (IDirectPartialModelNode) element;
                ComparisonNode cn = node.retrieveComparisonNode();
                IComparisonSession session = node.getComparisonSession();
                if (cn == null || session == null || !cn.isOneSideNode())
                    return true;

                // Свойства (модули, реквизиты-фичи…) — не объекты подсистемы;
                // видимость от родителя. Остальные односторонние объекты — через trie.
                if (cn instanceof FeatureComparisonNode)
                    return true;

                boolean visible = checkOneSideNode(cn, session);
                logDecision(element, cn, visible, lastDetail);
                return visible;
            }

            private boolean isComparisonNodeVisible(ComparisonNode cn, IComparisonSession session)
            {
                if (!cn.isOneSideNode())
                {
                    Object selected = Global.invoke(coreFilter, "checkNodeIsSelected", cn, session); //$NON-NLS-1$
                    return !Boolean.FALSE.equals(selected);
                }
                if (cn instanceof FeatureComparisonNode)
                    return true;
                return checkOneSideNode(cn, session);
            }

            private void ensureAttached(AbstractTreeViewer treeViewer)
            {
                if (verifiedAttached)
                    return;
                verifiedAttached = true;
                boolean found = false;
                for (ViewerFilter f : treeViewer.getFilters())
                {
                    if (f == this)
                    {
                        found = true;
                        break;
                    }
                }
                Global.tempLog(LOG, "ensureAttached found=" + found //$NON-NLS-1$
                        + " filters=" + treeViewer.getFilters().length); //$NON-NLS-1$
                if (!found)
                {
                    try
                    {
                        treeViewer.addFilter(this);
                        treeViewer.setData(FILTER_FLAG, Boolean.TRUE);
                        Global.tempLog(LOG, "re-attached CorrectionViewerFilter"); //$NON-NLS-1$
                    }
                    catch (Exception e)
                    {
                        Global.tempLog(LOG, "re-attach error: " + e); //$NON-NLS-1$
                    }
                }
            }

            private void logDecision(Object element, ComparisonNode cn, boolean visible,
                    String detail)
            {
                if (suppressDecideLogs)
                    return;
                // Раньше скрытые (visible=false) логировались без лимита → зависание UI.
                if (decideLogsLeft <= 0)
                    return;
                decideLogsLeft--;
                Global.tempLog(LOG, "oneSide visible=" + visible //$NON-NLS-1$
                        + " el=" + element.getClass().getSimpleName() //$NON-NLS-1$
                        + " cn=" + cn.getClass().getSimpleName() //$NON-NLS-1$
                        + " symlink=" + (cn instanceof SymlinkComparisonNode) //$NON-NLS-1$
                        + " side=" + cn.getNodeSide() //$NON-NLS-1$
                        + " " + detail); //$NON-NLS-1$
            }

            private boolean checkOneSideNode(ComparisonNode cn, IComparisonSession session)
            {
                ComparisonSide side = cn.getNodeSide();
                if (side == null)
                    return true;

                IComparisonDataSource ds = session.getDataSource(side);
                if (ds == null)
                    return true;

                String symlink = cn instanceof SymlinkComparisonNode
                        ? ((SymlinkComparisonNode) cn).getSymlink(side)
                        : null;
                QualifiedName qn = resolveQualifiedName(cn, side, ds, symlink);
                if (qn == null)
                {
                    Global.tempLog(LOG, "qn=null → hide cn=" + cn.getClass().getSimpleName() //$NON-NLS-1$
                            + " symlink=" + symlink); //$NON-NLS-1$
                    return false;
                }

                try
                {
                    // addProject() → isProjectChecked=true. Тогда getSelectedSubsystems()
                    // возвращает ВСЕ подсистемы проекта (у Main было 232), хотя пользователь
                    // отметил подсистему только на Other. Смотрим явные checkedSubsystemIds.
                    int checkedIds = countCheckedSubsystemIds(ds);
                    boolean projectChecked = isProjectChecked(ds);
                    boolean includeOrphans = isIncludeNotIncluded(ds);
                    if (checkedIds == 0)
                    {
                        lastDetail = "checkedIds=0 projectChecked=" + projectChecked //$NON-NLS-1$
                                + " includeOrphans=" + includeOrphans //$NON-NLS-1$
                                + " qn=" + qn + " → hide (no explicit subsystems on side)"; //$NON-NLS-1$
                        return false;
                    }

                    EObjectTrie included = getTrie("includedInSelectedSubsystemsTrieMap", ds); //$NON-NLS-1$
                    EObjectTrie allTop = getTrie("allTopObjectsToFilterTrieMap", ds); //$NON-NLS-1$
                    boolean isTop = allTop != null && allTop.belongsTo(qn);
                    boolean inIncluded = included != null && included.belongsTo(qn);
                    // Как EDT isIncludedInSelectedSubsystems: не top → не фильтруем;
                    // top → только входящие в выбранные подсистемы.
                    boolean visible;
                    if (included == null && allTop == null)
                        visible = true;
                    else
                        visible = !isTop || inIncluded;
                    lastDetail = "qn=" + qn //$NON-NLS-1$
                            + " symlink=" + symlink //$NON-NLS-1$
                            + " checkedIds=" + checkedIds //$NON-NLS-1$
                            + " projectChecked=" + projectChecked //$NON-NLS-1$
                            + " includeOrphans=" + includeOrphans //$NON-NLS-1$
                            + " isTop=" + isTop //$NON-NLS-1$
                            + " inIncluded=" + inIncluded //$NON-NLS-1$
                            + " hasIncluded=" + (included != null) //$NON-NLS-1$
                            + " hasAllTop=" + (allTop != null); //$NON-NLS-1$
                    return visible;
                }
                catch (Exception e)
                {
                    Global.tempLog(LOG, "checkOneSideNode error: " + e); //$NON-NLS-1$
                    lastDetail = "error"; //$NON-NLS-1$
                    return true;
                }
            }

            private int countCheckedSubsystemIds(IComparisonDataSource ds)
            {
                Object settings = Global.getField(namedFilter, "filterBySubsystemSettings"); //$NON-NLS-1$
                Object project = ds.getDtProject();
                if (settings == null || project == null)
                    return -1;
                Object ids = Global.invoke(settings, "getCheckedSubsystemIds", project); //$NON-NLS-1$
                if (ids instanceof java.util.Collection<?> col)
                    return col.size();
                return -1;
            }

            private boolean isProjectChecked(IComparisonDataSource ds)
            {
                Object settings = Global.getField(namedFilter, "filterBySubsystemSettings"); //$NON-NLS-1$
                Object project = ds.getDtProject();
                if (settings == null || project == null)
                    return false;
                return Boolean.TRUE.equals(Global.invoke(settings, "isProjectChecked", project)); //$NON-NLS-1$
            }

            private boolean isIncludeNotIncluded(IComparisonDataSource ds)
            {
                Object settings = Global.getField(namedFilter, "filterBySubsystemSettings"); //$NON-NLS-1$
                Object project = ds.getDtProject();
                if (settings == null || project == null)
                    return false;
                return Boolean.TRUE.equals(Global.invoke(settings,
                        "isIncludeNotIncludedInSubsystems", project)); //$NON-NLS-1$
            }

            @SuppressWarnings("unchecked")
            private EObjectTrie getTrie(String fieldName, IComparisonDataSource ds)
            {
                Object raw = Global.getField(coreFilter, fieldName);
                if (!(raw instanceof Map<?, ?> map) || map.isEmpty())
                    return null;
                Object direct = map.get(ds);
                if (direct instanceof EObjectTrie trie)
                    return trie;
                // Только тот же DtProject — без fallback на другую сторону сравнения
                Object project = ds.getDtProject();
                if (project == null)
                    return null;
                for (Map.Entry<?, ?> e : map.entrySet())
                {
                    if (!(e.getKey() instanceof IComparisonDataSource keyDs))
                        continue;
                    if (project.equals(keyDs.getDtProject())
                            && e.getValue() instanceof EObjectTrie trie)
                        return trie;
                }
                return null;
            }

            private QualifiedName resolveQualifiedName(ComparisonNode cn, ComparisonSide side,
                    IComparisonDataSource ds, String symlink)
            {
                if (symlink != null && !symlink.isEmpty())
                    return QualifiedName.create(symlink.split("\\.")); //$NON-NLS-1$

                if (!(cn instanceof MatchedObjectsComparisonNode matched))
                    return null;

                Long objectId = matched.getObjectId(side);
                if (objectId == null || objectId == -1L || qnProvider == null)
                    return null;

                EObject obj = ds.getObjectById(objectId);
                if (obj == null)
                    return null;
                return qnProvider.getFullyQualifiedName(obj);
            }
        }
    }

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
            runCompare(editor, shell, element);
        }

        public static void runCompare(IEditorPart editor, Shell shell, Object element)
        {
            runCompare(editor, shell, element, true);
        }

        public static void runCompare(IEditorPart editor, Shell shell, Object element, boolean connectIfAbsent)
        {
            Path pathMain = getPropertySideFile(editor, element, ComparisonSide.MAIN); // mxlx
            if (pathMain == null)
            {
                if (connectIfAbsent)
                    ToastNotification.show("Сравнение метаданных ИР", "Поддерживаются свойства: ТабличныйДокумент.Макет");
                return;
            }
            Path pathOther = getPropertySideFile(editor, element, ComparisonSide.OTHER); // mxlx
            Path pathAncestor = getPropertySideFile(editor, element, ComparisonSide.COMMON_ANCESTOR); // mxlx
            IComparisonSession compSession = CompareConfigSelectionListener.getSession(editor);
            IDtProject dtProject = compSession.getDataSource(ComparisonSide.MAIN).getDtProject();
            CompareTabularDocumentsInIr.runCompare(dtProject, pathMain, pathOther, pathAncestor, connectIfAbsent);
        }

        /**
         * Узел внешнего свойства с файлом {@code .mxlx} (без записи временных файлов).
         */
        public static boolean isMxlxNode(IEditorPart editor, Object element)
        {
            if (element == null)
                return false;
            try
            {
                String name = resolvePropertyFileName(editor, element, ComparisonSide.MAIN);
                if (CompareTabularDocumentsInIr.isMxlxFileName(name))
                    return true;
                name = resolvePropertyFileName(editor, element, ComparisonSide.OTHER);
                return CompareTabularDocumentsInIr.isMxlxFileName(name);
            }
            catch (Exception e)
            {
                return false;
            }
        }

        public static boolean isTabularDocumentTemplate(IEditorPart editor, Object element)
        {
            return isMxlxNode(editor, element);
        }

        /**
         * Имя файла стороны внешнего свойства (без копирования содержимого).
         */
        public static String resolvePropertyFileName(IEditorPart editor, Object element, ComparisonSide side)
        {
            IComparisonSession session = CompareConfigSelectionListener.getSession(editor);
            if (session == null)
                return null;
            MatchedObjectsComparisonNode matchedNode = CompareConfigSelectionListener.resolveMatchedNode(element);
            if (!(matchedNode instanceof ExternalPropertyComparisonNode))
                return null;
            ExternalPropertyComparisonNode properyNode = (ExternalPropertyComparisonNode) matchedNode;
            BundleContext ctx = Global.ourContext();
            ServiceReference<?> ref = ctx.getServiceReference(IComparisonManager.class);
            Object manager = ctx.getService(ref);
            IQualifiedNameFilePathConverter filePathConverter =
                (IQualifiedNameFilePathConverter) Global.getField(manager, "qualifiedNameFilePathConverter");
            String symlink = properyNode.getSymlink(side);
            if (symlink == null)
                return null;
            String qualifyingType = ((SolidResourceComparisonNode) properyNode).getQualifyingType(side);
            Path relativePath = (Path) ComparisonUtils.getFilePathBySymlink(symlink, qualifyingType, filePathConverter);
            if (relativePath == null || relativePath.getFileName() == null)
                return null;
            return relativePath.getFileName().toString();
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
            if (!(matchedNode instanceof ExternalPropertyComparisonNode))
                return null;
            ExternalPropertyComparisonNode properyNode = (ExternalPropertyComparisonNode) matchedNode;
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
            if (!CompareTabularDocumentsInIr.isMxlxFileName(fileName))
            {
                try { stream.close(); } catch (IOException ignored) {}
                return null;
            }
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

    /**
     * Двойной клик в дереве сравнения: то же, что клик по шестерёнке настроек
     * объединения ({@code CustomMergeSettingsStatus} ≠ {@code UNAVAILABLE});
     * секция BSL-модуля — диалог модуля с выбором секции;
     * макет табличного документа — «Сравнить в приложении ИР».
     */
    private static final class CompareConfigOpenModuleMergeHandler
    {
        private static final String TAG = "CompareConfig"; //$NON-NLS-1$
        private static final String DIALOG_CLASS_SNIPPET =
            "CompareBslModuleWithParsingModuleStructureDialog"; //$NON-NLS-1$
        private static final String SHELL_PATCHED_KEY = "tormozit.moduleMergeSectionPatched"; //$NON-NLS-1$
        private static final String DBL_CLICK_HOOKED = "tormozit.compareConf.dblClickHooked"; //$NON-NLS-1$

        private static volatile BslModuleSectionComparisonNode pendingSection;

        static void install(Display display)
        {
            display.addFilter(SWT.Show, event ->
            {
                if (pendingSection == null || !(event.widget instanceof Shell))
                    return;
                Shell shell = (Shell) event.widget;
                if (shell.getData(SHELL_PATCHED_KEY) != null)
                    return;
                Object dialog = shell.getData();
                if (dialog == null)
                    return;
                if (!dialog.getClass().getName().contains(DIALOG_CLASS_SNIPPET))
                    return;

                shell.setData(SHELL_PATCHED_KEY, Boolean.TRUE);
                final BslModuleSectionComparisonNode section = pendingSection;
                scheduleSelectSectionInDialog(shell, dialog, section);
            });
        }

        private static final int SECTION_SELECT_MAX_ATTEMPTS = 40;
        private static final int SECTION_SELECT_DELAY_MS = 50;

        private static void scheduleSelectSectionInDialog(Shell shell, Object dialog,
                BslModuleSectionComparisonNode section)
        {
            scheduleSelectSectionAttempt(shell, dialog, section, 0);
        }

        private static void scheduleSelectSectionAttempt(Shell shell, Object dialog,
                BslModuleSectionComparisonNode section, int attempt)
        {
            if (section == null || dialog == null)
                return;
            if (attempt >= SECTION_SELECT_MAX_ATTEMPTS)
                return;
            shell.getDisplay().timerExec(attempt == 0 ? 100 : SECTION_SELECT_DELAY_MS, () ->
            {
                if (trySelectSectionInDialog(dialog, section))
                    return;
                scheduleSelectSectionAttempt(shell, dialog, section, attempt + 1);
            });
        }

        private static DtComparisonView getDialogComparisonView(Object dialog)
        {
            Object view = Global.invoke(dialog, "getComparisonView"); //$NON-NLS-1$
            if (view instanceof DtComparisonView)
                return (DtComparisonView) view;
            view = Global.getField(dialog, "comparisonView"); //$NON-NLS-1$
            return view instanceof DtComparisonView ? (DtComparisonView) view : null;
        }

        static void attachDoubleClickListener(IEditorPart editor, Tree tree)
        {
            if (Boolean.TRUE.equals(tree.getData(DBL_CLICK_HOOKED)))
                return;
            tree.setData(DBL_CLICK_HOOKED, Boolean.TRUE);
            RightsDialogFilterHook.attachCompareTreeWatch(tree);
            tree.addListener(SWT.MouseDoubleClick, event ->
            {
                if (event.button != 1)
                    return;
                Object fromClick = resolveElementAt(tree, event.x, event.y);
                Object element = fromClick != null ? fromClick : selectionElement(editor);
                onDoubleClick(editor, tree, element);
            });
        }

        static void onDoubleClick(IEditorPart editor, Tree tree, Object element)
        {
            if (element == null)
                return;
            try
            {
                BslModuleSectionComparisonNode sectionNode = resolveSectionComparisonNode(element);
                if (sectionNode != null)
                {
                    ExternalPropertyPartialModelNode moduleNode = findParentModuleNode(element);
                    if (moduleNode == null)
                    {
                        log("doubleClick: родительский узел модуля не найден"); //$NON-NLS-1$
                        return;
                    }
                    openModuleMergeForNode(editor, moduleNode, sectionNode, tree.getShell());
                    return;
                }

                if (ProjectSettingsFilesHandler.tryHandle(editor, element, tree.getShell()))
                    return;

                if (element instanceof IPartialModelNode node && hasMergeSettings(node))
                {
                    openMergeSettings(editor, node, tree.getShell());
                    return;
                }

                if (CompareConfigCompareInIRHandler.isTabularDocumentTemplate(editor, element))
                {
                    CompareConfigCompareInIRHandler.runCompare(editor, tree.getShell(), element, false);
                    return;
                }
            }
            catch (Exception e)
            {
                Global.logError(TAG, "doubleClick", e); //$NON-NLS-1$
            }
        }

        /** Как колонка шестерёнки: иконка есть при статусе ≠ {@code UNAVAILABLE}. */
        private static boolean hasMergeSettings(IPartialModelNode node)
        {
            CustomMergeSettingsStatus status = node.getCustomMergeSettingsStatus();
            return status != null && status != CustomMergeSettingsStatus.UNAVAILABLE;
        }

        private static Object selectionElement(IEditorPart editor)
        {
            ISelection selection = CompareConfigOpenObjectHandler.getSelection(editor);
            if (!(selection instanceof IStructuredSelection))
                return null;
            return ((IStructuredSelection) selection).getFirstElement();
        }

        private static Object resolveElementAt(Tree tree, int x, int y)
        {
            TreeItem item = itemAt(tree, x, y);
            return item != null ? item.getData() : null;
        }

        private static TreeItem itemAt(Tree tree, int x, int y)
        {
            TreeItem item = tree.getItem(new Point(x, y));
            if (item != null)
                return item;
            for (TreeItem root : tree.getItems())
            {
                TreeItem found = findItemAt(root, x, y);
                if (found != null)
                    return found;
            }
            return null;
        }

        private static TreeItem findItemAt(TreeItem item, int x, int y)
        {
            for (int col = 0; col < item.getParent().getColumnCount(); col++)
            {
                if (item.getBounds(col).contains(x, y))
                    return item;
            }
            if (item.getExpanded())
            {
                for (TreeItem child : item.getItems())
                {
                    TreeItem found = findItemAt(child, x, y);
                    if (found != null)
                        return found;
                }
            }
            return null;
        }

        private static void openModuleMergeForNode(IEditorPart editor,
                ExternalPropertyPartialModelNode moduleNode,
                BslModuleSectionComparisonNode section, Shell shell)
        {
            ComparisonNode moduleComparison = moduleNode.retrieveComparisonNode();
            if (!(moduleComparison instanceof BslModuleComparisonNode))
                return;

            BslModuleComparisonNode bslModule = (BslModuleComparisonNode) moduleComparison;
            boolean selectSection = section != null && bslModule.isParseModuleStructure();
            if (section != null && !bslModule.isParseModuleStructure())
                log("doubleClick: модуль без разбора структуры — открытие без выбора секции"); //$NON-NLS-1$

            pendingSection = selectSection ? section : null;
            try
            {
                openMergeSettings(editor, moduleNode, shell);
            }
            finally
            {
                pendingSection = null;
            }
        }

        private static BslModuleSectionComparisonNode resolveSectionComparisonNode(Object element)
        {
            if (!(element instanceof IPartialModelNode))
                return null;
            ComparisonNode cn = ((IPartialModelNode) element).retrieveComparisonNode();
            return cn instanceof BslModuleSectionComparisonNode
                ? (BslModuleSectionComparisonNode) cn : null;
        }

        private static ExternalPropertyPartialModelNode findParentModuleNode(Object element)
        {
            Object current = element;
            while (current instanceof IPartialModelNode)
            {
                IPartialModelNode node = (IPartialModelNode) current;
                if (node instanceof ExternalPropertyPartialModelNode)
                {
                    ComparisonNode cn = node.retrieveComparisonNode();
                    if (cn instanceof BslModuleComparisonNode)
                        return (ExternalPropertyPartialModelNode) node;
                }
                current = node.getParent();
            }
            return null;
        }

        /**
         * То же, что клик по шестерёнке: {@link PartialModelController#editMergeSettings}
         * (как {@code DtComparisonEditor} → {@code mergeSettingsClicked}).
         */
        private static void openMergeSettings(IEditorPart editor, IPartialModelNode node, Shell shell)
        {
            Object artifacts = resolveComparisonArtifacts(editor, node);
            if (artifacts == null)
                return;

            Object partialModel = Global.call(artifacts, "getPartialModel"); //$NON-NLS-1$
            IComparisonSession session = (IComparisonSession) Global.call(artifacts, "getSession"); //$NON-NLS-1$
            PartialModelController pmc =
                (PartialModelController) Global.getField(editor, "partialModelController"); //$NON-NLS-1$
            DtComparisonView view = (DtComparisonView) Global.getField(editor, "comparisonView"); //$NON-NLS-1$

            if (partialModel == null || session == null || pmc == null || view == null)
            {
                log("openMergeSettings: не хватает partialModel/session/controller/view"); //$NON-NLS-1$
                return;
            }

            boolean canMerge = Boolean.TRUE.equals(Global.getField(editor, "canMerge")); //$NON-NLS-1$
            boolean readOnly = view.isReadOnly() || !canMerge;

            Object editorInput = Global.getField(editor, "dtComparisonEditorInput"); //$NON-NLS-1$
            String mainSideName = editorInput != null
                ? (String) Global.call(editorInput, "getMainComparisonSideName") : null; //$NON-NLS-1$
            String otherSideName = editorInput != null
                ? (String) Global.call(editorInput, "getOtherComparisonSideName") : null; //$NON-NLS-1$

            ISelectionProviderDelegate selectionDelegate =
                (ISelectionProviderDelegate) Global.getField(editor, "selectionProviderDelegate"); //$NON-NLS-1$
            ResourceManager resourceManager =
                (ResourceManager) Global.getField(editor, "resourceManager"); //$NON-NLS-1$

            Color colorHasDiffs = view.getColorHasDiffs();
            Color colorOnlyMain = view.getColorOnlyMain();
            Color colorOnlyOther = view.getColorOnlyOther();

            RightsDialogFilterHook.runWhileBlocking(() -> pmc.editMergeSettings(
                (IPartialModel) partialModel,
                node,
                session,
                shell,
                readOnly,
                colorHasDiffs,
                colorOnlyMain,
                colorOnlyOther,
                mainSideName,
                otherSideName,
                view,
                selectionDelegate,
                resourceManager));
        }

        /**
         * Находит {@code ComparisonArtifacts} редактора для узла partial model
         * (как {@code DtComparisonEditor.getComparisonArtifacts}, без двусмысленного {@link Global#invoke}).
         */
        private static Object resolveComparisonArtifacts(IEditorPart editor, IPartialModelNode node)
        {
            IPartialModelNode nodeForSession = normalizeNodeForSessionLookup(node);
            IComparisonSession session = nodeForSession != null
                ? nodeForSession.getComparisonSession() : null;
            int sessionId = session != null ? session.getId() : -1;

            Object listObj = Global.getField(editor, "comparisonArtifactsList"); //$NON-NLS-1$
            if (!(listObj instanceof List))
            {
                logArtifactsNotFound(node, session, 0);
                return null;
            }
            List<?> artifactsList = (List<?>) listObj;
            int artifactsCount = artifactsList.size();

            if (session != null)
            {
                for (Object artifact : artifactsList)
                {
                    Object artSession = Global.call(artifact, "getSession"); //$NON-NLS-1$
                    if (artSession instanceof IComparisonSession
                            && ((IComparisonSession) artSession).getId() == sessionId)
                    {
                        ensurePartialModelForArtifact(editor, artifact);
                        return artifact;
                    }
                }
            }

            long nodeId = node.getNodeId();
            for (Object artifact : artifactsList)
            {
                ensurePartialModelForArtifact(editor, artifact);
                Object partialModel = Global.call(artifact, "getPartialModel"); //$NON-NLS-1$
                if (!(partialModel instanceof IPartialModel))
                    continue;
                if (((IPartialModel) partialModel).getDirectNode(nodeId) != null)
                    return artifact;
            }

            logArtifactsNotFound(node, session, artifactsCount);
            return null;
        }

        private static IPartialModelNode normalizeNodeForSessionLookup(IPartialModelNode node)
        {
            if (node == null)
                return null;
            if (node instanceof VirtualFolderPartialModelNode)
            {
                IDirectPartialModelNode direct =
                    ((VirtualFolderPartialModelNode) node).getClosestDirectParent();
                return direct;
            }
            return node;
        }

        private static void ensurePartialModelForArtifact(IEditorPart editor, Object artifact)
        {
            if (Global.call(artifact, "getPartialModel") != null) //$NON-NLS-1$
                return;
            Global.invokeVoid(editor, "createPartialModelForArtifact", artifact); //$NON-NLS-1$
        }

        private static void logArtifactsNotFound(IPartialModelNode node,
                IComparisonSession session, int artifactsCount)
        {
            if (!Global.isLogEnabled())
                return;
            long nodeId = node != null ? node.getNodeId() : -1L;
            int sessionId = session != null ? session.getId() : -1;
            String nodeClass = node != null ? node.getClass().getSimpleName() : "null"; //$NON-NLS-1$
            Global.log(TAG, "openMergeSettings: артефакт не найден" //$NON-NLS-1$
                + " artifactsCount=" + artifactsCount //$NON-NLS-1$
                + " sessionId=" + sessionId //$NON-NLS-1$
                + " nodeId=" + nodeId //$NON-NLS-1$
                + " nodeClass=" + nodeClass); //$NON-NLS-1$
        }

        private static boolean trySelectSectionInDialog(Object dialog,
                BslModuleSectionComparisonNode section)
        {
            if (section == null || dialog == null)
                return false;

            DtComparisonView view = getDialogComparisonView(dialog);
            if (view == null)
                return false;

            ComparisonTreeControl treeControl = view.getTreeControl();
            if (treeControl == null)
                return false;

            TreeViewer viewer = treeControl.getTreeViewer();
            if (viewer == null)
                return false;

            Object node = findPartialModelNode(viewer, section);
            if (node == null)
                return false;

            viewer.setSelection(new StructuredSelection(node), true);
            viewer.reveal(node);
            Global.invokeVoid(dialog, "nodeSelectionChanged", node); //$NON-NLS-1$
            log("selectSectionInDialog: выбрана секция"); //$NON-NLS-1$
            return true;
        }

        private static Object findPartialModelNode(TreeViewer viewer, BslModuleSectionComparisonNode target)
        {
            ITreeContentProvider cp = (ITreeContentProvider) viewer.getContentProvider();
            if (cp == null)
                return null;
            Object input = viewer.getInput();
            if (input == null)
                return null;
            for (Object root : cp.getElements(input))
            {
                Object found = findInSubtree(cp, root, target);
                if (found != null)
                    return found;
            }
            return null;
        }

        private static boolean sectionNodesMatch(BslModuleSectionComparisonNode a,
                BslModuleSectionComparisonNode b)
        {
            if (a == b)
                return true;
            if (a == null || b == null)
                return false;
            if (a instanceof com._1c.g5.v8.bm.core.IBmObject
                    && b instanceof com._1c.g5.v8.bm.core.IBmObject)
            {
                long idA = ((com._1c.g5.v8.bm.core.IBmObject) a).bmGetId();
                long idB = ((com._1c.g5.v8.bm.core.IBmObject) b).bmGetId();
                if (idA > 0 && idA == idB)
                    return true;
            }
            String na = a.getMainName();
            String nb = b.getMainName();
            return na != null && na.equals(nb);
        }

        private static Object findInSubtree(ITreeContentProvider cp, Object element,
                BslModuleSectionComparisonNode target)
        {
            if (element instanceof IPartialModelNode)
            {
                ComparisonNode cn = ((IPartialModelNode) element).retrieveComparisonNode();
                if (cn instanceof BslModuleSectionComparisonNode
                        && sectionNodesMatch((BslModuleSectionComparisonNode) cn, target))
                    return element;
            }
            if (!cp.hasChildren(element))
                return null;
            for (Object child : cp.getChildren(element))
            {
                Object found = findInSubtree(cp, child, target);
                if (found != null)
                    return found;
            }
            return null;
        }

        private static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
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

            for (Object root : cp.getElements(viewer.getInput()))
            {
                collectElementsToExpand(cp, root, mode, toExpand, viewer);
            }
            TreeAutoExpand.runSuppressed(() ->
            {
                viewer.collapseAll();
                viewer.setExpandedElements(toExpand.toArray());
            });

            if (selection != null && !selection.isEmpty())
            {
                viewer.setSelection(selection, true);
            }
            return null;
        }

        /**
         * Рекурсивно собирает список узлов для раскрытия.
         * В {@code toExpand} попадают только узлы, видимые при текущих фильтрах дерева.
         * Обход модели — по полному дереву content provider; вызовов вьювера внутри нет.
         */
        private static void collectElementsToExpand(ITreeContentProvider cp, Object element, CompareConfigExpandMode mode, Set<Object> toExpand, AbstractTreeViewer viewer)
        {
            if (false
                    || !cp.hasChildren(element)
                    || mode == CompareConfigExpandMode.toBothElement && isAddedOrDeleted(element)
                    || mode == CompareConfigExpandMode.toObject      && isObject(element)
                    || mode == CompareConfigExpandMode.toMarked      && !isMarked(element))
                return;

            if (CompareConfigSearchDialogHook.isNodeMatchFilters(element, viewer))
            {
                toExpand.add(element);
            }
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

    /**
     * Dblclick по «Настройки проекта» (symlink Settings / папка {@code .settings}):
     * список изменившихся файлов → текстовое сравнение сторон MAIN/OTHER.
     */
    private static final class ProjectSettingsFilesHandler
    {
        private static final String TAG = "CompareConfig"; //$NON-NLS-1$
        private static final String SETTINGS_SYMLINK = "Settings"; //$NON-NLS-1$
        private static final Path SETTINGS_FOLDER = Path.of(".settings"); //$NON-NLS-1$
        private static final String TOAST_TITLE = "Настройки проекта"; //$NON-NLS-1$

        private enum ChangeKind
        {
            ADDED, DELETED, CHANGED
        }

        private static final class FileEntry
        {
            final Path path;
            final String relativeName;
            final ChangeKind kind;

            FileEntry(Path path, ChangeKind kind)
            {
                this.path = path;
                this.relativeName = path.toString().replace('\\', '/');
                this.kind = kind;
            }
        }

        static boolean tryHandle(IEditorPart editor, Object element, Shell shell)
        {
            if (!(element instanceof IPartialModelNode node))
                return false;
            IComparisonSession session = CompareConfigSelectionListener.getSession(editor);
            if (session == null)
                return false;
            ComparisonNode comparisonNode = session.getNode(node.getNodeId());
            if (!(comparisonNode instanceof UnsupportedObjectComparisonNode unsupported))
                return false;
            String symlink = unsupported.getMainSymlink();
            if (symlink == null)
                symlink = unsupported.getOtherSymlink();
            if (!SETTINGS_SYMLINK.equals(symlink))
                return false;

            List<FileEntry> changed = collectChangedFiles(session);
            if (changed.isEmpty())
            {
                ToastNotification.show(TOAST_TITLE, "Нет изменившихся файлов настроек", 4000); //$NON-NLS-1$
                return true;
            }

            FileEntry selected = changed.size() == 1
                ? changed.get(0)
                : pickFile(shell, changed);
            if (selected == null)
                return true;

            openTextCompare(editor, session, selected, shell);
            return true;
        }

        private static List<FileEntry> collectChangedFiles(IComparisonSession session)
        {
            IComparisonDataSource mainDs = session.getDataSource(ComparisonSide.MAIN);
            IComparisonDataSource otherDs = session.getDataSource(ComparisonSide.OTHER);
            List<Path> mainFiles = fileList(mainDs);
            List<Path> otherFiles = fileList(otherDs);
            Set<Path> all = new HashSet<>();
            all.addAll(mainFiles);
            all.addAll(otherFiles);

            List<FileEntry> result = new ArrayList<>();
            for (Path path : all)
            {
                boolean inMain = containsPath(mainFiles, path);
                boolean inOther = containsPath(otherFiles, path);
                if (inMain && !inOther)
                    result.add(new FileEntry(path, ChangeKind.DELETED));
                else if (!inMain && inOther)
                    result.add(new FileEntry(path, ChangeKind.ADDED));
                else if (inMain && inOther && !sameContent(mainDs, otherDs, path))
                    result.add(new FileEntry(path, ChangeKind.CHANGED));
            }
            result.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.relativeName, b.relativeName));
            return result;
        }

        private static List<Path> fileList(IComparisonDataSource ds)
        {
            if (ds == null)
                return List.of();
            List<Path> list = ds.getFileListRecursively(SETTINGS_FOLDER);
            return list != null ? list : List.of();
        }

        private static boolean containsPath(List<Path> files, Path path)
        {
            for (Path f : files)
            {
                if (f.equals(path))
                    return true;
            }
            return false;
        }

        private static boolean sameContent(IComparisonDataSource mainDs, IComparisonDataSource otherDs, Path path)
        {
            byte[] main = readBytes(mainDs, path);
            byte[] other = readBytes(otherDs, path);
            if (main == null && other == null)
                return true;
            if (main == null || other == null)
                return false;
            return Arrays.equals(main, other);
        }

        private static byte[] readBytes(IComparisonDataSource ds, Path path)
        {
            if (ds == null || path == null)
                return null;
            try
            {
                if (!ds.fileExists(path))
                    return null;
                try (InputStream in = ds.getFileStream(path))
                {
                    return in != null ? in.readAllBytes() : null;
                }
            }
            catch (IOException e)
            {
                Global.logError(TAG, "settings readBytes " + path, e); //$NON-NLS-1$
                return null;
            }
        }

        private static String readText(IComparisonDataSource ds, Path path)
        {
            byte[] bytes = readBytes(ds, path);
            if (bytes == null)
                return ""; //$NON-NLS-1$
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private static FileEntry pickFile(Shell shell, List<FileEntry> entries)
        {
            StatusImageCache images = new StatusImageCache();
            try
            {
                ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell,
                    new LabelProvider()
                    {
                        @Override
                        public String getText(Object element)
                        {
                            return element instanceof FileEntry e ? e.relativeName : super.getText(element);
                        }

                        @Override
                        public Image getImage(Object element)
                        {
                            return element instanceof FileEntry e ? images.image(e.kind) : null;
                        }
                    });
                dialog.setTitle(Global.withPluginWindowTitle(TOAST_TITLE));
                dialog.setMessage("Выберите файл для сравнения текстов версий:"); //$NON-NLS-1$
                dialog.setElements(entries.toArray());
                dialog.setMultipleSelection(false);
                dialog.setHelpAvailable(false);
                if (dialog.open() != Window.OK)
                    return null;
                Object result = dialog.getFirstResult();
                return result instanceof FileEntry e ? e : null;
            }
            finally
            {
                images.dispose();
            }
        }

        private static void openTextCompare(IEditorPart editor, IComparisonSession session, FileEntry entry,
            Shell shell)
        {
            IComparisonDataSource mainDs = session.getDataSource(ComparisonSide.MAIN);
            IComparisonDataSource otherDs = session.getDataSource(ComparisonSide.OTHER);
            String leftText = readText(mainDs, entry.path);
            String rightText = readText(otherDs, entry.path);
            String leftLabel = sideLabel(editor, true);
            String rightLabel = sideLabel(editor, false);
            String fileName = entry.path.getFileName() != null
                ? entry.path.getFileName().toString()
                : entry.relativeName;
            try
            {
                SettingsFileCompareInput input = new SettingsFileCompareInput(
                    leftText, rightText, leftLabel, rightLabel, fileName);
                CompareUI.openCompareDialog(input);
            }
            catch (Exception e)
            {
                Global.logError(TAG, "settings openTextCompare", e); //$NON-NLS-1$
                ToastNotification.show(TOAST_TITLE,
                    "Не удалось открыть сравнение: " + e.getMessage(), 5000); //$NON-NLS-1$
            }
        }

        private static String sideLabel(IEditorPart editor, boolean main)
        {
            Object editorInput = Global.getField(editor, "dtComparisonEditorInput"); //$NON-NLS-1$
            if (editorInput != null)
            {
                String name = (String) Global.call(editorInput,
                    main ? "getMainComparisonSideName" : "getOtherComparisonSideName"); //$NON-NLS-1$ //$NON-NLS-2$
                if (name != null && !name.isBlank())
                    return name;
            }
            return main ? "Основная" : "Другая"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** Иконки add/del/chg из {@code org.eclipse.compare} (ovr16). */
        private static final class StatusImageCache
        {
            private Image added;
            private Image deleted;
            private Image changed;

            Image image(ChangeKind kind)
            {
                return switch (kind)
                {
                    case ADDED -> added != null ? added : (added = load("icons/full/ovr16/add_ov.png")); //$NON-NLS-1$
                    case DELETED -> deleted != null ? deleted : (deleted = load("icons/full/ovr16/del_ov.png")); //$NON-NLS-1$
                    case CHANGED -> changed != null ? changed : (changed = load("icons/full/ovr16/chg_ov.png")); //$NON-NLS-1$
                };
            }

            private static Image load(String path)
            {
                ImageDescriptor desc = AbstractUIPlugin.imageDescriptorFromPlugin(CompareUI.PLUGIN_ID, path);
                return desc != null ? desc.createImage() : null;
            }

            void dispose()
            {
                if (added != null && !added.isDisposed())
                    added.dispose();
                if (deleted != null && !deleted.isDisposed())
                    deleted.dispose();
                if (changed != null && !changed.isDisposed())
                    changed.dispose();
                added = null;
                deleted = null;
                changed = null;
            }
        }

        private static final class SettingsFileCompareInput extends CompareEditorInput
        {
            private final StringCompareElement leftElement;
            private final StringCompareElement rightElement;

            SettingsFileCompareInput(String leftText, String rightText, String leftLabel, String rightLabel,
                String fileName)
            {
                super(createConfiguration(leftLabel, rightLabel));
                String type = viewerType(fileName);
                leftElement = new StringCompareElement(fileName, leftText, type);
                rightElement = new StringCompareElement(fileName, rightText, type);
                setTitle(fileName);
            }

            private static CompareConfiguration createConfiguration(String leftLabel, String rightLabel)
            {
                CompareConfiguration config = new CompareConfiguration();
                config.setLeftEditable(false);
                config.setRightEditable(false);
                config.setLeftLabel(leftLabel != null ? leftLabel : "Основная"); //$NON-NLS-1$
                config.setRightLabel(rightLabel != null ? rightLabel : "Другая"); //$NON-NLS-1$
                return config;
            }

            private static String viewerType(String fileName)
            {
                if (fileName == null)
                    return "txt"; //$NON-NLS-1$
                int dot = fileName.lastIndexOf('.');
                if (dot < 0 || dot == fileName.length() - 1)
                    return "txt"; //$NON-NLS-1$
                return fileName.substring(dot + 1);
            }

            @Override
            protected Object prepareInput(IProgressMonitor monitor)
            {
                return new DiffNode(null, Differencer.CHANGE, null, leftElement, rightElement);
            }

            @Override
            public String getOKButtonLabel()
            {
                return IDialogConstants.CLOSE_LABEL;
            }

            @Override
            public boolean isSaveNeeded()
            {
                return true;
            }

            private static final class StringCompareElement
                implements ITypedElement, IStreamContentAccessor, IEncodedStreamContentAccessor
            {
                private final String name;
                private final String content;
                private final String type;

                StringCompareElement(String name, String content, String type)
                {
                    this.name = name != null ? name : ""; //$NON-NLS-1$
                    this.content = content != null ? content : ""; //$NON-NLS-1$
                    this.type = type != null ? type : "txt"; //$NON-NLS-1$
                }

                @Override
                public String getName()
                {
                    return name;
                }

                @Override
                public Image getImage()
                {
                    return null;
                }

                @Override
                public String getType()
                {
                    return type;
                }

                @Override
                public InputStream getContents()
                {
                    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
                }

                @Override
                public String getCharset()
                {
                    return StandardCharsets.UTF_8.name();
                }
            }
        }
    }

}
