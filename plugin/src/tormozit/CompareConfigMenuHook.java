package tormozit;


import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
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
import org.eclipse.jface.viewers.ITreeViewerListener;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeExpansionEvent;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.TypedListener;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.swt.layout.GridData;
import org.eclipse.ui.IActionBars;
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
import org.eclipse.ui.actions.ActionFactory;
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
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.dt.compare.core.ComparisonUtils;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.core.IComparisonTreeFilter;
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
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.CollapseAllHandler;
import org.eclipse.ui.handlers.ExpandAllHandler;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.handlers.IHandlerService;
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
    private static final String ITEM_TEXT_setMarks     = "Установить пометки";
    private static final String ITEM_TEXT_clearMarks   = "Снять пометки";
    private static final String ITEM_TEXT_findLowestCheckable = "Найти нижние настраиваемые";
    /** Маркер: на дереве уже активированы Collapse All / Expand All. */
    private static final String COLLAPSE_EXPAND_HANDLERS_KEY =
            "tormozit.compareConf.collapseExpandHandlers"; //$NON-NLS-1$
    /** Id contribution item кнопки «Свернуть всё» в тулбаре редактора сравнения. */
    private static final String TOOLBAR_COLLAPSE_ID =
            "tormozit.compareConf.toolbar.collapseAll"; //$NON-NLS-1$
    private static final String NAVIGATOR_BUNDLE_ID = "org.eclipse.ui.navigator"; //$NON-NLS-1$
    private static final String COLLAPSE_ALL_ICON_PATH =
            "icons/full/elcl16/collapseall.png"; //$NON-NLS-1$

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
        CompareConfigMultiMarkSupport.install(editor, tree);
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
                        onCompareTreeViewerReady(editor, listener, v);
                        ThreeWayOnlyPresentFiltersFix.install(editor);
                    }
                });
                return;
            }
            onCompareTreeViewerReady(editor, listener, viewer);
            ThreeWayOnlyPresentFiltersFix.install(editor);
        });
    }

    private static void onCompareTreeViewerReady(
            IEditorPart editor, CompareConfigSelectionListener listener, AbstractTreeViewer viewer)
    {
        listener.setTreeViewer(viewer);
        // Разворот единственного корня и цепочек единственных потомков — общий механизм,
        // см. TreeAutoExpand.installLoadAutoExpand (раньше был свой tryExpandCompareRoot/
        // scheduleExpandCompareRoot, теперь вынесено и обобщено на все деревья из белого списка).
        TreeAutoExpand.installWhitelisted(
                TreeAutoExpand.Target.COMPARE_CONFIG, viewer);
        installCollapseExpandHandlers(editor, viewer);
    }

    /**
     * Штатные {@code org.eclipse.ui.navigate.collapseAll}/{@code expandAll} не имеют
     * defaultHandler — их вешают view/editor через {@link IHandlerService}. EDT-редактор
     * сравнения этого не делает, поэтому Ctrl+Shift+/ (numpad) только вызывал команду
     * (тост), но дерево не трогал. Активируем на site редактора, пока он в фокусе.
     */
    private static void installCollapseExpandHandlers(IEditorPart editor, AbstractTreeViewer viewer)
    {
        if (editor == null || viewer == null)
            return;
        Control control = viewer.getControl();
        if (!(control instanceof Tree tree) || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(COLLAPSE_EXPAND_HANDLERS_KEY)))
            return;
        IHandlerService handlerService = editor.getSite().getService(IHandlerService.class);
        if (handlerService == null)
            return;

        // Свернуть всё, затем снова применить правила авторазворота (единственный корень /
        // цепочки sole-child) — как resetExpansionAfterReveal в результатах поиска.
        handlerService.activateHandler(CollapseAllHandler.COMMAND_ID, new AbstractHandler()
        {
            @Override
            public Object execute(ExecutionEvent event)
            {
                collapseCompareTree(editor);
                return null;
            }
        });
        handlerService.activateHandler(ExpandAllHandler.COMMAND_ID, new ExpandAllHandler(viewer));
        tree.setData(COLLAPSE_EXPAND_HANDLERS_KEY, Boolean.TRUE);
    }

    /**
     * Свернуть дерево сравнения и заново применить {@link TreeAutoExpand}
     * (единственный корень / sole-child). Общее действие для команды Collapse All и кнопки тулбара.
     */
    private static void collapseCompareTree(IEditorPart editor)
    {
        AbstractTreeViewer viewer = getTreeViewerFromEditor(editor);
        if (viewer == null)
            return;
        TreeAutoExpand.resetExpansionAfterReveal(viewer, true);
    }

    private static AbstractTreeViewer getTreeViewerFromEditor(IEditorPart editor)
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
        if (toolbar.find(TOOLBAR_COLLAPSE_ID) == null)
        {
            ImageDescriptor collapseIcon = AbstractUIPlugin.imageDescriptorFromPlugin(
                    NAVIGATOR_BUNDLE_ID, COLLAPSE_ALL_ICON_PATH);
            Action collapseAction = new Action(null, collapseIcon)
            {
                @Override
                public void run()
                {
                    collapseCompareTree(editor);
                }
            };
            collapseAction.setId(TOOLBAR_COLLAPSE_ID);
            collapseAction.setToolTipText(
                    "Свернуть всё" + Global.pluginSignForTooltip()); //$NON-NLS-1$
            collapseAction.setActionDefinitionId(CollapseAllHandler.COMMAND_ID);
            toolbar.add(new Separator());
            toolbar.add(collapseAction);
        }
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
                CompareConfigMultiMarkSupport multiMark = CompareConfigMultiMarkSupport.get(tree);
                boolean hasMultiMark = multiMark != null && !multiMark.getSelected().isEmpty();
                boolean hasNative = getSelectedMatchedNode(editor) != null;
                Object nativeElement = getSelectedTreeElement(editor);

                if (hasMultiMark || hasNative)
                {
                    addedItems.add(new MenuItem(menu, SWT.SEPARATOR));

                    MenuItem findLowest = new MenuItem(menu, SWT.PUSH);
                    findLowest.setText(ITEM_TEXT_findLowestCheckable);
                    findLowest.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent e)
                        {
                            CompareConfigLowestCheckableFinder.run(editor, tree.getShell(),
                                CompareConfigLowestCheckableFinder.resolveRoots(
                                    hasMultiMark ? multiMark.getSelected() : null, nativeElement));
                        }
                    });
                    addedItems.add(findLowest);
                }

                // Пометки — до early-return по hasNative: папки разделов («Регистры» и т.п.)
                // часто без MatchedObjectsComparisonNode, но Ctrl/Shift-набор уже есть.
                if (multiMark != null && multiMark.getSelected().size() > 1)
                {
                    addedItems.add(new MenuItem(menu, SWT.SEPARATOR));

                    MenuItem itemMark = new MenuItem(menu, SWT.PUSH);
                    itemMark.setText(ITEM_TEXT_setMarks);
                    itemMark.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent e)
                        {
                            AbstractTreeViewer viewer = getTreeViewerFromEditor(editor);
                            if (viewer instanceof CheckboxTreeViewer ctv)
                                multiMark.applyMark(ctv, true);
                        }
                    });
                    addedItems.add(itemMark);

                    MenuItem itemUnmark = new MenuItem(menu, SWT.PUSH);
                    itemUnmark.setText(ITEM_TEXT_clearMarks);
                    itemUnmark.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent e)
                        {
                            AbstractTreeViewer viewer = getTreeViewerFromEditor(editor);
                            if (viewer instanceof CheckboxTreeViewer ctv)
                                multiMark.applyMark(ctv, false);
                        }
                    });
                    addedItems.add(itemUnmark);
                }

                if (!hasNative) return;

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

    /** Первый элемент штатного выделения дерева (узел под курсором), если это {@code IPartialModelNode}. */
    private Object getSelectedTreeElement(IEditorPart editor)
    {
        AbstractTreeViewer viewer = getTreeViewerFromEditor(editor);
        if (viewer == null) return null;
        Object sel = viewer.getSelection();
        if (!(sel instanceof IStructuredSelection ss)) return null;
        Object element = ss.getFirstElement();
        return element instanceof IPartialModelNode ? element : null;
    }

    /**
     * Псевдо-мультивыделение веток в дереве сравнения (штатный {@code Tree} создан
     * EDT со стилем {@code SWT.SINGLE} — сменить на {@code MULTI} после создания
     * контрола нельзя). Ctrl/Shift+клик копят набор выбранных узлов независимо от
     * штатного {@code tree.getSelection()}; подсветка — через {@code SWT.EraseItem}.
     *
     * <p>Команды «Установить/Снять пометки» ({@link #applyMark(boolean)}) поглощают
     * потомков, чей предок уже есть в наборе, и для каждого оставшегося верхнего узла
     * симулируют штатный клик по чекбоксу — прогоняют {@link CheckStateChangedEvent}
     * через уже зарегистрированные {@link ICheckStateListener} (то же поле
     * {@code checkStateListeners}, что и в {@link SubsystemFilterFix}), не дублируя
     * логику каскада {@code setMustBeMerged}.
     */
    private static final class CompareConfigMultiMarkSupport
    {
        private static final String DATA_KEY = "tormozit.compareConfigMultiMark"; //$NON-NLS-1$

        private final Tree tree;
        private final Set<IPartialModelNode> selected =
            Collections.newSetFromMap(new IdentityHashMap<>());
        private IPartialModelNode anchor;
        private Color ownedBg;

        private CompareConfigMultiMarkSupport(Tree tree)
        {
            this.tree = tree;
        }

        static void install(IEditorPart editor, Tree tree)
        {
            if (tree == null || tree.isDisposed()) return;
            if (tree.getData(DATA_KEY) != null) return;

            CompareConfigMultiMarkSupport support = new CompareConfigMultiMarkSupport(tree);
            tree.setData(DATA_KEY, support);
            tree.addListener(SWT.MouseDown, support::onMouseDown);
            tree.addListener(SWT.EraseItem, support::onEraseItem);
            tree.addListener(SWT.PaintItem, support::onPaintItem);
            tree.addDisposeListener(e ->
            {
                if (support.ownedBg != null && !support.ownedBg.isDisposed())
                    support.ownedBg.dispose();
            });
            installCopyActionOverride(editor, support);
        }

        static CompareConfigMultiMarkSupport get(Tree tree)
        {
            if (tree == null || tree.isDisposed()) return null;
            Object data = tree.getData(DATA_KEY);
            return data instanceof CompareConfigMultiMarkSupport support ? support : null;
        }

        // ---- Ctrl+C — копирование текстов выделенных строк ----

        /**
         * Ctrl+C на дереве сравнения не долетает как {@code SWT.KeyDown} (нативный
         * Win32-акселератор Edit → Copy съедает букву раньше), а перехват через
         * {@code ICommandService.addExecutionListener} не годится: команда
         * {@code org.eclipse.ui.edit.copy} в этом редакторе ДЕЙСТВИТЕЛЬНО обрабатывается
         * штатным обработчиком EDT, который выполняется сразу после нашего {@code preExecute}
         * и перезаписывает буфер обратно на одиночное native-выделение (проверено логом).
         * Нужна подмена самого обработчика —
         * {@code IActionBars.setGlobalActionHandler(ActionFactory.COPY.getId(), ...)}
         * (эталон концепции — {@code DebugInspectorTreeEnhancement.hookGlobalCopyAction()},
         * но там модальный диалог и reflection на {@code globalActions}, здесь — обычный
         * редактор со штатным {@code IActionBars}).
         */
        private static void installCopyActionOverride(IEditorPart editor, CompareConfigMultiMarkSupport support)
        {
            if (editor == null || editor.getEditorSite() == null) return;
            IActionBars bars = editor.getEditorSite().getActionBars();
            if (bars == null) return;

            IAction original = bars.getGlobalActionHandler(ActionFactory.COPY.getId());
            IAction wrapper = new Action()
            {
                @Override
                public void run()
                {
                    Control focus = support.tree.getDisplay().getFocusControl();
                    if (focus == support.tree && !support.selected.isEmpty())
                    {
                        support.copySelectedText();
                        return;
                    }
                    if (original != null)
                        original.run();
                }
            };
            bars.setGlobalActionHandler(ActionFactory.COPY.getId(), wrapper);
            bars.updateActionBars();
        }

        /** Тексты выделенных строк в видимом порядке, через разделитель строки. */
        private void copySelectedText()
        {
            if (selected.isEmpty()) return;

            List<TreeItem> visible = new ArrayList<>();
            collectVisibleItems(tree.getItems(), visible);

            List<String> lines = new ArrayList<>();
            for (TreeItem item : visible)
            {
                if (item.getData() instanceof IPartialModelNode n && selected.contains(n))
                {
                    String text = item.getText();
                    if (text != null && !text.isBlank())
                        lines.add(text);
                }
            }
            if (lines.isEmpty()) return;

            String joined = String.join(System.lineSeparator(), lines);
            Clipboard clipboard = new Clipboard(tree.getDisplay());
            try
            {
                clipboard.setContents(new Object[] { joined }, new Transfer[] { TextTransfer.getInstance() });
            }
            finally
            {
                clipboard.dispose();
            }
        }

        Set<IPartialModelNode> getSelected()
        {
            return selected;
        }

        // ---- Ctrl/Shift+клик — накопление набора ----

        private void onMouseDown(Event e)
        {
            if (e.button != 1 && e.button != 3) return;
            TreeItem item = tree.getItem(new Point(e.x, e.y));
            if (item == null || item.isDisposed()) return;
            if (!(item.getData() instanceof IPartialModelNode node)) return;

            if (e.button == 3)
            {
                onRightMouseDown(item, node);
                return;
            }

            boolean ctrl = (e.stateMask & SWT.MOD1) != 0;
            boolean shift = (e.stateMask & SWT.SHIFT) != 0;

            if (shift && anchor != null)
            {
                selectRange(anchor, node);
            }
            else if (ctrl)
            {
                if (!selected.remove(node))
                    selected.add(node);
                anchor = node;
            }
            else
            {
                selected.clear();
                selected.add(node);
                anchor = node;
            }
            tree.redraw();
        }

        /**
         * ПКМ: сначала сделать кликнутую строку текущей (штатный {@code Tree} на Win32
         * при правом клике только рисует рамку, но выделение не меняет — команды меню
         * работали бы над прежней строкой), и лишь затем показывается контекстное меню.
         *
         * <p>Если строка уже входит в псевдо-мультивыделение, набор сохраняется —
         * иначе ПКМ по одной из выделенных веток обнулял бы групповые команды.
         */
        private void onRightMouseDown(TreeItem item, IPartialModelNode node)
        {
            if (!selected.contains(node))
            {
                selected.clear();
                selected.add(node);
                anchor = node;
            }
            tree.setSelection(item);
            Event selectionEvent = new Event();
            selectionEvent.item = item;
            tree.notifyListeners(SWT.Selection, selectionEvent);
            tree.redraw();
        }

        /** Диапазон по видимому порядку строк дерева (развёрнутые узлы). */
        private void selectRange(IPartialModelNode from, IPartialModelNode to)
        {
            List<TreeItem> visible = new ArrayList<>();
            collectVisibleItems(tree.getItems(), visible);

            int i1 = indexOfNode(visible, from);
            int i2 = indexOfNode(visible, to);
            if (i1 < 0 || i2 < 0) return;

            int lo = Math.min(i1, i2);
            int hi = Math.max(i1, i2);
            selected.clear();
            for (int i = lo; i <= hi; i++)
            {
                if (visible.get(i).getData() instanceof IPartialModelNode n)
                    selected.add(n);
            }
        }

        private static void collectVisibleItems(TreeItem[] items, List<TreeItem> out)
        {
            for (TreeItem item : items)
            {
                if (item == null || item.isDisposed()) continue;
                out.add(item);
                if (item.getExpanded())
                    collectVisibleItems(item.getItems(), out);
            }
        }

        private static int indexOfNode(List<TreeItem> items, IPartialModelNode node)
        {
            for (int i = 0; i < items.size(); i++)
                if (items.get(i).getData() == node)
                    return i;
            return -1;
        }

        // ---- Подсветка ----

        private void onEraseItem(Event e)
        {
            if (!(e.item instanceof TreeItem item) || item.isDisposed()) return;
            if (!(item.getData() instanceof IPartialModelNode node) || !selected.contains(node)) return;
            // Узел под штатным native-выделением уже подсвечен системой — не мешаем.
            if (isNativelySelected(item)) return;

            Color bg = highlightBackground();
            if (bg == null) return;
            e.gc.setBackground(bg);
            e.gc.fillRectangle(e.x, e.y, e.width, e.height);
            e.detail &= ~SWT.BACKGROUND;
        }

        private boolean isNativelySelected(TreeItem item)
        {
            for (TreeItem s : tree.getSelection())
                if (s == item) return true;
            return false;
        }

        /** Тёмная тема — акцент от {@link ListSelectionThemeColors}; светлая — свой slightlyDarker. */
        private Color highlightBackground()
        {
            if (ownedBg != null && !ownedBg.isDisposed())
                return ownedBg;
            if (ListSelectionThemeColors.isDarkList(tree))
            {
                ownedBg = ListSelectionThemeColors.listSelectionBackground(tree, false);
                return ownedBg;
            }
            Color base = tree.getBackground();
            if (base == null || base.isDisposed())
                base = tree.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
            ownedBg = slightlyDarker(base, 0.16);
            return ownedBg;
        }

        /**
         * Рамка поверх строки (система, {@code COLOR_LIST_SELECTION}) — в отличие от
         * фонового оттенка не тонет на цветных строках добавленных/удалённых объектов
         * (там текст/фон часто уже подкрашен label-провайдером сравнения).
         */
        private void onPaintItem(Event e)
        {
            if (!(e.item instanceof TreeItem item) || item.isDisposed()) return;
            if (!(item.getData() instanceof IPartialModelNode node) || !selected.contains(node)) return;
            if (isNativelySelected(item)) return;

            // getBounds() без индекса — только колонка 0; растягиваем на всю ширину
            // клиентской области (в дереве сравнения несколько колонок,
            // см. CompareModuleStructureColumnHook), а не только на первую.
            Rectangle bounds = item.getBounds(0);
            if (bounds == null || bounds.isEmpty()) return;

            Color border = tree.getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION);
            e.gc.setForeground(border);
            e.gc.drawRectangle(0, bounds.y,
                Math.max(0, tree.getClientArea().width - 1), Math.max(0, bounds.height - 1));
        }

        private static Color slightlyDarker(Color base, double factor)
        {
            RGB rgb = base.getRGB();
            int r = clampChannel((int) (rgb.red * (1.0 - factor)));
            int g = clampChannel((int) (rgb.green * (1.0 - factor)));
            int b = clampChannel((int) (rgb.blue * (1.0 - factor)));
            return new Color(base.getDevice(), r, g, b);
        }

        private static int clampChannel(int value)
        {
            return Math.max(0, Math.min(255, value));
        }

        // ---- Команды «Установить/Снять пометки» ----

        /** Отбрасывает узлы, чей предок уже присутствует в наборе (поглощение). */
        private List<IPartialModelNode> absorbDescendants()
        {
            List<IPartialModelNode> top = new ArrayList<>();
            for (IPartialModelNode node : selected)
            {
                boolean hasAncestorInSet = false;
                for (IPartialModelNode p = node.getParent(); p != null; p = p.getParent())
                {
                    if (selected.contains(p))
                    {
                        hasAncestorInSet = true;
                        break;
                    }
                }
                if (!hasAncestorInSet)
                    top.add(node);
            }
            return top;
        }

        void applyMark(CheckboxTreeViewer ctv, boolean checked)
        {
            if (ctv == null) return;

            for (IPartialModelNode node : absorbDescendants())
            {
                if (!node.isCheckable()) continue;
                ctv.setChecked(node, checked);
                fireCheckStateChanged(ctv, node, checked);
            }
        }

        /**
         * Прогоняет синтетическое событие через уже зарегистрированные
         * {@link ICheckStateListener} — тот же путь, что и обычный клик по чекбоксу
         * (включая штатный EDT-каскад {@code setMustBeMerged}).
         */
        private static void fireCheckStateChanged(CheckboxTreeViewer ctv, Object element, boolean checked)
        {
            Object listObj = Global.getField(ctv, "checkStateListeners"); //$NON-NLS-1$
            if (listObj == null) return;
            Object raw = Global.invoke(listObj, "getListeners"); //$NON-NLS-1$
            if (!(raw instanceof Object[] listeners)) return;

            CheckStateChangedEvent event = new CheckStateChangedEvent(ctv, element, checked);
            for (Object l : listeners)
                if (l instanceof ICheckStateListener listener)
                    listener.checkStateChanged(event);
        }
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
                        retryOrGiveUp(editor, attempt);
                        return;
                    }
                    DtComparisonView view = (DtComparisonView) viewObj;
                    if (view.isDisposed())
                    {
                        return;
                    }
                    if (Boolean.TRUE.equals(view.getData(FLAG)))
                        return;

                    Object filtersObj = Global.getField(editor, "filters"); //$NON-NLS-1$
                    if (!(filtersObj instanceof INamedViewerFilter[]))
                    {
                        retryOrGiveUp(editor, attempt);
                        return;
                    }
                    INamedViewerFilter[] current = (INamedViewerFilter[]) filtersObj;

                    Object editorInput = Global.getField(editor, "dtComparisonEditorInput"); //$NON-NLS-1$
                    if (editorInput == null)
                    {
                        retryOrGiveUp(editor, attempt);
                        return;
                    }
                    if (!isThreeWay(editorInput))
                    {
                        view.setData(FLAG, Boolean.TRUE);
                        return;
                    }

                    String mainName = (String) Global.call(editorInput, "getMainComparisonSideName"); //$NON-NLS-1$
                    String otherName = (String) Global.call(editorInput, "getOtherComparisonSideName"); //$NON-NLS-1$
                    if (mainName == null || otherName == null)
                    {
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
                        return;
                    }

                    INamedViewerFilter[] patched = insertOnlyPresentFilters(current, mainName, otherName,
                        new ViewOnlyNamedFilter(onlyMain), new ViewOnlyNamedFilter(onlyOther));
                    if (patched == current)
                    {
                        view.setData(FLAG, Boolean.TRUE);
                        return;
                    }

                    ComboViewer comboViewer = findFilterComboViewer(view);
                    if (comboViewer == null)
                    {
                        retryOrGiveUp(editor, attempt);
                        return;
                    }

                    Global.setField(editor, "filters", patched); //$NON-NLS-1$

                    Object contextObj = Global.getField(view, "context"); //$NON-NLS-1$
                    if (contextObj instanceof DtComparisonViewContext)
                        Global.setFieldForce(contextObj, "filters", patched); //$NON-NLS-1$

                    INamedViewerFilter selected = view.getCurrentNamedViewerFilter();
                    comboViewer.setInput(patched);
                    if (selected != null)
                        comboViewer.setSelection(new StructuredSelection(selected), true);

                    view.setData(FLAG, Boolean.TRUE);
                }
                catch (Exception e)
                {
                    if (attempt < MAX_ATTEMPTS)
                        Display.getDefault().timerExec(RETRY_MS, () -> install(editor, attempt + 1));
                }
            };

            if (Thread.currentThread() == display.getThread())
                body.run();
            else
                display.asyncExec(body);
        }

        private static void retryOrGiveUp(IEditorPart editor, int attempt)
        {
            Display display = Display.getDefault();
            if (attempt < MAX_ATTEMPTS)
                display.timerExec(RETRY_MS, () -> install(editor, attempt + 1));
        }

        /**
         * Выбор пункта комбо «Фильтр» не только отбирает дерево: {@code PartialModelController
         * .changeComparisonViewerFilter} отдаёт {@code convertToComparisonTreeFilter()} в сессию
         * через {@code addComparisonTreeFilter} — это сужает область сравнения, из которой
         * работает объединение. Двусторонний фильтр, добавленный нами в трёхстороннее сравнение,
         * в таком качестве неприменим: пометки остаются в модели ({@code mustBeMerged=true} в BM),
         * но «Объединить» по ним ничего не делает.
         *
         * <p>Обёртка оставляет отбор дерева двусторонним (как и просили), а в сессию отдаёт
         * {@link IComparisonTreeFilter#ALL} — сравнение не сужается, объединение работает.
         */
        private static final class ViewOnlyNamedFilter implements INamedViewerFilter
        {
            private final INamedViewerFilter delegate;

            ViewOnlyNamedFilter(INamedViewerFilter delegate)
            {
                this.delegate = delegate;
            }

            @Override
            public String getName()
            {
                return delegate.getName();
            }

            @Override
            public ViewerFilter getViewerFilter()
            {
                return delegate.getViewerFilter();
            }

            @Override
            public IComparisonTreeFilter convertToComparisonTreeFilter()
            {
                return IComparisonTreeFilter.ALL;
            }
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
        private static final String CORRECTION_DATA_KEY = "tormozit.correctionViewerFilter"; //$NON-NLS-1$
        private static final String FILTER_AWARE_CHECKBOX_FLAG = "tormozit.filterAwareChecksCheckbox"; //$NON-NLS-1$
        private static final int MAX_ATTEMPTS = 60;
        /** Без viewer и namedFilter — editor не поднимается; не крутить полные 60. */
        private static final int MAX_STUCK_ATTEMPTS = 12;
        private static final int RETRY_MS = 250;

        static void install(IEditorPart editor)
        {
            install(editor, 0);
        }

        private static boolean isEditorGone(IEditorPart editor)
        {
            if (editor == null)
                return true;
            try
            {
                return editor.getSite() == null;
            }
            catch (Exception ex)
            {
                return true;
            }
        }

        private static void install(IEditorPart editor, int attempt)
        {
            if (isEditorGone(editor))
                return;

            Display display = Display.getDefault();
            Runnable body = () ->
            {
                if (isEditorGone(editor))
                    return;

                // Флажок у combo «Фильтр» — независимо от «Улучшать списки».
                installFilterAwareChecksCheckbox(editor, attempt);

                if (!ComfortSettings.isReplaceListFiltersEnabled())
                {
                    return;
                }

                AbstractTreeViewer viewer = resolveTreeViewer(editor);
                Object namedFilter = Global.getField(editor, "filterBySubsystems"); //$NON-NLS-1$

                if (viewer != null && Boolean.TRUE.equals(viewer.getData(FILTER_FLAG)))
                    return;

                if (viewer == null || namedFilter == null)
                {
                    boolean stuck = viewer == null && namedFilter == null;
                    int limit = stuck ? MAX_STUCK_ATTEMPTS : MAX_ATTEMPTS;
                    if (attempt < limit && !isEditorGone(editor))
                        display.timerExec(RETRY_MS, () -> install(editor, attempt + 1));
                    else
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
                    viewer.setData(CORRECTION_DATA_KEY, correction);
                    if (viewer instanceof CheckboxTreeViewer ctv && treeControl != null
                            && ComfortSettings.isCompareFilterAwareChecksEnabled())
                        correction.installCheckHooks(ctv, treeControl, editor);
                    viewer.setData(FILTER_FLAG, Boolean.TRUE);
                }
                catch (Exception e)
                {
                }
            };

            if (Thread.currentThread() == display.getThread())
                body.run();
            else
                display.asyncExec(body);
        }

        private static final String FILTER_AWARE_CHECKBOX_LABEL =
            "Каскадный пересчет пометок с учетом фильтра (экспериментально)"; //$NON-NLS-1$
        private static final String FILTER_AWARE_CHECKBOX_TOOLTIP = 
            "При активном отборе по подсистемам или combo «Показывать…»\n"
                + "пометки и серые предки — только по видимым узлам;\n"
                + "«Отметить все»/клик по папке не трогают скрытые.\n"
                + "Выключено — штатное поведение EDT."; //$NON-NLS-1$

        /**
         * Checkbox справа от combo «Фильтр» в {@code DtComparisonView.filterControl}.
         * Состояние — глобальный PreferenceStore ({@link ComfortSettings}).
         */
        private static void installFilterAwareChecksCheckbox(IEditorPart editor, int attempt)
        {
            if (isEditorGone(editor))
                return;
            Object viewObj = Global.getField(editor, "comparisonView"); //$NON-NLS-1$
            if (!(viewObj instanceof DtComparisonView dcv) || dcv.isDisposed())
            {
                if (attempt < MAX_STUCK_ATTEMPTS && !isEditorGone(editor))
                    Display.getDefault().timerExec(RETRY_MS,
                            () -> installFilterAwareChecksCheckbox(editor, attempt + 1));
                return;
            }
            Object filterControlObj = Global.getField(dcv, "filterControl"); //$NON-NLS-1$
            if (!(filterControlObj instanceof Composite filterControl) || filterControl.isDisposed())
            {
                if (attempt < MAX_ATTEMPTS && !isEditorGone(editor))
                    Display.getDefault().timerExec(RETRY_MS,
                            () -> installFilterAwareChecksCheckbox(editor, attempt + 1));
                return;
            }
            if (Boolean.TRUE.equals(filterControl.getData(FILTER_AWARE_CHECKBOX_FLAG)))
                return;

            Combo combo = ThreeWayOnlyPresentFiltersFix.findCombo(filterControl);
            if (combo == null || combo.isDisposed())
            {
                if (attempt < MAX_ATTEMPTS && !isEditorGone(editor))
                    Display.getDefault().timerExec(RETRY_MS,
                            () -> installFilterAwareChecksCheckbox(editor, attempt + 1));
                return;
            }

            Composite host = combo.getParent();
            if (host == null || host.isDisposed())
                return;
            if (Boolean.TRUE.equals(host.getData(FILTER_AWARE_CHECKBOX_FLAG)))
                return;

            Button check = new Button(host, SWT.CHECK);
            check.setText(FILTER_AWARE_CHECKBOX_LABEL);
            check.setToolTipText(FILTER_AWARE_CHECKBOX_TOOLTIP + Global.pluginSignForTooltip());
            check.setSelection(ComfortSettings.isCompareFilterAwareChecksEnabled());
            Object layoutData = combo.getLayoutData();
            if (layoutData instanceof GridData)
            {
                GridData gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
                gd.horizontalIndent = 8;
                check.setLayoutData(gd);
            }
            check.moveBelow(combo);
            host.layout(true, true);
            Composite outer = host.getParent();
            if (outer != null && !outer.isDisposed())
                outer.layout(true, true);

            check.addListener(SWT.Selection, e ->
            {
                boolean on = check.getSelection();
                ComfortSettings.setCompareFilterAwareChecksEnabled(on);
                applyFilterAwareChecksToggle(editor, on);
            });
            host.setData(FILTER_AWARE_CHECKBOX_FLAG, Boolean.TRUE);
            filterControl.setData(FILTER_AWARE_CHECKBOX_FLAG, Boolean.TRUE);
        }

        private static void applyFilterAwareChecksToggle(IEditorPart editor, boolean enabled)
        {
            AbstractTreeViewer viewer = resolveTreeViewer(editor);
            if (!(viewer instanceof CheckboxTreeViewer ctv))
                return;
            Object view = Global.getField(editor, "comparisonView"); //$NON-NLS-1$
            Object treeControl = view instanceof DtComparisonView
                    ? ((DtComparisonView) view).getTreeControl()
                    : null;
            Object corrObj = viewer.getData(CORRECTION_DATA_KEY);
            if (enabled)
            {
                if (corrObj instanceof CorrectionViewerFilter correction && treeControl != null)
                {
                    if (Boolean.TRUE.equals(ctv.getData("tormozit.subsystemFilterCheckHook"))) //$NON-NLS-1$
                    {
                        correction.syncCheckHooksAfterPrefOn(ctv);
                    }
                    else
                    {
                        correction.installCheckHooks(ctv, treeControl, editor);
                    }
                }
            }
            else if (corrObj instanceof CorrectionViewerFilter correction)
            {
                correction.deactivateCheckHooks(ctv);
            }
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
            /** Ключ режима «чёрный список» — {@code ViewerFilterBySubsystemsSettings}, тот же
             *  объект, которым владеет диалог отбора ({@link FilterBySubsystemsDialogHook}). */
            private final Object blacklistSettingsKey;
            private boolean verifiedAttached;
            /** Штатный EDT-фильтр подсистем отсоединён от дерева ради режима «чёрный список». */
            private boolean nativeSubsystemFilterDetached;

            CorrectionViewerFilter(Object namedFilter)
            {
                this.namedFilter = namedFilter;
                this.coreFilter = Global.invoke(namedFilter, "getCoreFilter"); //$NON-NLS-1$
                this.qnProvider = coreFilter == null
                        ? null
                        : (IQualifiedNameProvider) Global.getField(coreFilter, "qualifiedNameProvider"); //$NON-NLS-1$
                this.blacklistSettingsKey = Global.getField(namedFilter, "filterBySubsystemSettings"); //$NON-NLS-1$
            }

            private boolean isBlacklistMode()
            {
                return FilterBySubsystemsDialogHook.isBlacklistMode(blacklistSettingsKey);
            }

            /**
             * В режиме «чёрный список» штатный {@code ViewerFilterBySubsystems} должен быть
             * отсоединён от дерева: {@link ViewerFilter}'ы комбинируются через AND, поэтому
             * добавочный фильтр может только сильнее скрывать, но не «показать» то, что штатный
             * (белый список) уже спрятал. При выключении режима — вернуть штатный фильтр обратно.
             */
            private void syncNativeFilterAttachment(AbstractTreeViewer treeViewer, boolean blacklist)
            {
                if (blacklist == nativeSubsystemFilterDetached)
                    return;
                Object nativeFilterObj = Global.invoke(namedFilter, "getViewerFilter"); //$NON-NLS-1$
                if (!(nativeFilterObj instanceof ViewerFilter nativeViewerFilter))
                    return;
                // Флаг — до addFilter/removeFilter: они синхронно вызывают refresh() → select()
                // реентерабельно, и при устаревшем флаге это уходит в бесконечную рекурсию
                // (переполнение стека).
                nativeSubsystemFilterDetached = blacklist;
                if (blacklist)
                    treeViewer.removeFilter(nativeViewerFilter);
                else
                    treeViewer.addFilter(nativeViewerFilter);
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
                if (!ComfortSettings.isCompareFilterAwareChecksEnabled())
                {
                    return;
                }

                if (!wrapCheckStateListeners(ctv))
                {
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
                // Штатный EDT на старте массово расставляет mustBeMerged — не мешаем.
                beginStartupSettle();
                syncCheckHooksToFilterState(ctv);
                ctv.setData(CHECK_HOOK_FLAG, Boolean.TRUE);
                installRootRepaintOnTreeExpand(ctv);
                scheduleStartupAggregateRepaint(ctv, 0);
            }

            /** Флажок снят: вернуть штатный CheckStateProvider, сбросить кэш агрегатов. */
            void deactivateCheckHooks(CheckboxTreeViewer ctv)
            {
                if (ctv == null || ctv.getControl() == null || ctv.getControl().isDisposed())
                    return;
                ourCheckStateProviderActive = false;
                if (edtCheckStateProvider != null)
                    ctv.setCheckStateProvider(edtCheckStateProvider);
                clearAggregateUiCache();
            }

            /**
             * Флажок снова включён на уже установленном хуке.
             * Без startupSettle/startupRepaint(+2.6с): дерево уже построено, а отложенный
             * wipe кэша красил родителей в full при kids=1 (Clip_854591).
             */
            void syncCheckHooksAfterPrefOn(CheckboxTreeViewer ctv)
            {
                syncCheckHooksToFilterState(ctv);
                clearAggregateUiCache();
                paintVisibleAggregateTreeItems(ctv);
            }

            private static final String ROOT_EXPAND_HOOK_FLAG = "tormozit.compareRootExpandRepaint"; //$NON-NLS-1$

            /**
             * Когда под корнем появляются/раскрываются дети — пересчитать корень
             * (иначе kids=1 empty залипает в кэше).
             */
            private void installRootRepaintOnTreeExpand(CheckboxTreeViewer ctv)
            {
                if (Boolean.TRUE.equals(ctv.getData(ROOT_EXPAND_HOOK_FLAG)))
                    return;
                ctv.addTreeListener(new ITreeViewerListener()
                {
                    @Override
                    public void treeExpanded(TreeExpansionEvent event)
                    {
                        Object el = event.getElement();
                        IPartialModelNode from = el instanceof IPartialModelNode n ? n : null;
                        scheduleAggregateRepaintSoon(ctv, from);
                    }

                    @Override
                    public void treeCollapsed(TreeExpansionEvent event)
                    {
                        // no-op
                    }
                });
                Tree tree = ctv.getTree();
                if (tree != null && !tree.isDisposed())
                {
                    // Догрузка детей без Expand (lazy) — SetData у VIRTUAL нет,
                    // слушаем Resize/Paint редко; основной догон — startup retries.
                    tree.addListener(SWT.Show, e -> scheduleAggregateRepaintSoon(ctv, null));
                }
                ctv.setData(ROOT_EXPAND_HOOK_FLAG, Boolean.TRUE);
            }

            /**
             * После Expand/Show: пересчитать агрегат раскрытого узла и предков
             * по TreeItem (не только корень — иначе «Справочники» остаётся empty
             * после раннего paint loaded→empty, Clip_854577).
             */
            private void scheduleAggregateRepaintSoon(CheckboxTreeViewer ctv, IPartialModelNode from)
            {
                if (ctv == null || ctv.getControl() == null || ctv.getControl().isDisposed())
                    return;
                if (!shouldRestrictChecksToVisible() || isStartupSettleActive())
                    return;
                // Задержка: после Expand дети/provider ещё не готовы (Clip_854585).
                ctv.getControl().getDisplay().timerExec(80, () ->
                {
                    if (ctv.getControl() == null || ctv.getControl().isDisposed())
                        return;
                    if (!shouldRestrictChecksToVisible())
                        return;
                    if (from != null)
                    {
                        for (IPartialModelNode p = from; p != null; p = p.getParent())
                        {
                            if (!isFilterAwareAggregateUi(p))
                                continue;
                            aggregateCheckedUi.remove(p);
                            aggregateGrayedUi.remove(p);
                            paintAggregateFromChildren(ctv, p);
                        }
                        return;
                    }
                    for (TreeItem rootItem : ctv.getTree().getItems())
                    {
                        if (rootItem == null || rootItem.isDisposed())
                            continue;
                        Object data = rootItem.getData();
                        if (data instanceof ProjectPartialModelNode root)
                        {
                            aggregateCheckedUi.remove(root);
                            aggregateGrayedUi.remove(root);
                            paintAggregateFromChildren(ctv, root);
                        }
                    }
                });
            }


            private IEditorPart hookedEditor;
            private CheckboxTreeViewer hookedCtv;
            private ICheckStateProvider edtCheckStateProvider;
            private boolean ourCheckStateProviderActive;
            private boolean filterWasActive;
            /** До этого момента — только EDT delegates/provider, без нашего агрегата. */
            private long startupSettleUntilMs;

            private void beginStartupSettle()
            {
                startupSettleUntilMs = System.currentTimeMillis() + 2500L;
            }

            private boolean isStartupSettleActive()
            {
                return System.currentTimeMillis() < startupSettleUntilMs;
            }

            /**
             * После штатной расстановки — пересчёт видимых агрегатов (как смена
             * combo-фильтра). Много попыток: на ранних тиках у корня kids=1
             * (дерево ещё грузится) — пустой кэш залипал до клика.
             */
            private void scheduleStartupAggregateRepaint(CheckboxTreeViewer ctv, int attempt)
            {
                if (ctv == null || ctv.getControl() == null || ctv.getControl().isDisposed())
                    return;
                int delay = attempt == 0 ? 2600 : 500;
                ctv.getControl().getDisplay().timerExec(delay, () ->
                {
                    if (ctv.getControl() == null || ctv.getControl().isDisposed())
                        return;
                    if (!shouldRestrictChecksToVisible())
                    {
                        return;
                    }
                    clearAggregateUiCache();
                    ensureProjectRootExpanded(ctv);
                    syncRootChildTreeItemChecksFromModel(ctv);
                    boolean rootReady = paintVisibleAggregateTreeItems(ctv);
                    // Пустой корень / мало детей — EDT ещё не доставил пометки. Не
                    // останавливаемся на kids>1 + empty (см. Clip_854571).
                    if (!rootReady && attempt < 40)
                        scheduleStartupAggregateRepaint(ctv, attempt + 1);
                    else if (rootReady && attempt < 6)
                        scheduleStartupAggregateRepaint(ctv, attempt + 1);
                });
            }

            /**
             * Уже созданные TreeItem — только куда красить. Значение агрегата из
             * отфильтрованной модели ({@link #computeAggregateFromFilteredChildren}),
             * не из развёрнутости.
             */
            private boolean paintVisibleAggregateTreeItems(CheckboxTreeViewer ctv)
            {
                if (ctv == null || ctv.getControl() == null || ctv.getControl().isDisposed())
                    return false;
                Tree tree = ctv.getTree();
                if (tree == null || tree.isDisposed())
                    return false;
                ArrayList<IPartialModelNode> nodes = new ArrayList<>();
                collectAggregateNodesFromTreeItems(tree.getItems(), nodes);
                nodes.sort((a, b) -> Integer.compare(depthOf(b), depthOf(a)));
                for (IPartialModelNode node : nodes)
                    paintAggregateFromChildren(ctv, node);
                // Warning/merge overlays (UID и т.п.) — из LabelProvider, не из TreeItem.
                refreshNodeImages(ctv, nodes);
                return isProjectRootAggregateReady(ctv);
            }

            private boolean isProjectRootAggregateReady(CheckboxTreeViewer ctv)
            {
                Tree tree = ctv.getTree();
                if (tree == null || tree.isDisposed())
                    return false;
                for (TreeItem rootItem : tree.getItems())
                {
                    if (rootItem == null || rootItem.isDisposed())
                        continue;
                    Object data = rootItem.getData();
                    if (!(data instanceof ProjectPartialModelNode))
                        continue;
                    TreeItem[] kids = rootItem.getItems();
                    int kidCount = kids != null ? kids.length : 0;
                    boolean hasGray = rootItem.getGrayed()
                            || Boolean.TRUE.equals(aggregateGrayedUi.get(data));
                    boolean hasFull = (rootItem.getChecked() && !rootItem.getGrayed())
                            || (Boolean.TRUE.equals(aggregateCheckedUi.get(data))
                                    && !Boolean.TRUE.equals(aggregateGrayedUi.get(data)));
                    boolean anyChildMark = hasAnyChildMark(kids);
                    // Не ready при kids=2 (Clip_854585): дерево ещё не догрузило
                    // Справочники/Перечисления — иначе stop и папки без пометок.
                    boolean ready = hasGray && kidCount >= 8;
                    return ready;
                }
                return false;
            }

            private static boolean hasAnyChildMark(TreeItem[] kids)
            {
                if (kids == null)
                    return false;
                for (TreeItem kid : kids)
                {
                    if (kid == null || kid.isDisposed())
                        continue;
                    if (kid.getGrayed() || kid.getChecked())
                        return true;
                }
                return false;
            }


            /**
             * Подтянуть checked/grayed прямых детей корня из модели — TreeItem часто
             * ещё пустой, пока EDT не дорисовал, а isChecked уже есть.
             */
            private void syncRootChildTreeItemChecksFromModel(CheckboxTreeViewer ctv)
            {
                Tree tree = ctv.getTree();
                if (tree == null || tree.isDisposed())
                    return;
                for (TreeItem rootItem : tree.getItems())
                {
                    if (rootItem == null || rootItem.isDisposed())
                        continue;
                    if (!(rootItem.getData() instanceof ProjectPartialModelNode))
                        continue;
                    TreeItem[] kids = rootItem.getItems();
                    if (kids == null)
                        return;
                    for (TreeItem kid : kids)
                    {
                        if (kid == null || kid.isDisposed())
                            continue;
                        Object data = kid.getData();
                        if (!(data instanceof IPartialModelNode node))
                            continue;
                        boolean checked = node.isChecked();
                        boolean grayed = node.isGrayed();
                        if (!checked && !grayed)
                            continue;
                        kid.setChecked(checked);
                        kid.setGrayed(grayed);
                    }
                    return;
                }
            }

            /** Раскрыть только корень на 1 уровень. Детей второго уровня не трогаем. */
            private void ensureProjectRootExpanded(CheckboxTreeViewer ctv)
            {
                if (ctv == null || ctv.getControl() == null || ctv.getControl().isDisposed())
                    return;
                Tree tree = ctv.getTree();
                if (tree == null || tree.isDisposed())
                    return;
                for (TreeItem rootItem : tree.getItems())
                {
                    if (rootItem == null || rootItem.isDisposed())
                        continue;
                    Object data = rootItem.getData();
                    if (!(data instanceof ProjectPartialModelNode))
                        continue;
                    TreeItem[] kids = rootItem.getItems();
                    int kidCount = kids != null ? kids.length : 0;
                    try
                    {
                        if (kidCount <= 1 || !rootItem.getExpanded())
                            ctv.expandToLevel(data, 1);
                    }
                    catch (RuntimeException ignored)
                    {
                        // expand may fail while tree is still loading
                    }
                    return;
                }
            }

            private static void collectAggregateNodesFromTreeItems(TreeItem[] items,
                    ArrayList<IPartialModelNode> out)
            {
                if (items == null)
                    return;
                for (TreeItem item : items)
                {
                    if (item == null || item.isDisposed())
                        continue;
                    Object data = item.getData();
                    if (data instanceof IPartialModelNode node
                            && isFilterAwareAggregateUi(node))
                        out.add(node);
                    collectAggregateNodesFromTreeItems(item.getItems(), out);
                }
            }

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
                return true;
            }

            private void wrapToolbarSelectAllActions(IEditorPart editor, CheckboxTreeViewer ctv)
            {
                if (Boolean.TRUE.equals(ctv.getData(SELECT_ALL_HOOK_FLAG)))
                    return;
                Object tbmObj = Global.getField(editor, "toolBarManager"); //$NON-NLS-1$
                if (!(tbmObj instanceof ToolBarManager tbm))
                {
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
                        continue;
                    }
                    wrapped++;
                }
                if (wrapped > 0)
                    tbm.update(true);
                ctv.setData(SELECT_ALL_HOOK_FLAG, Boolean.TRUE);
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
                            new FilterAwareSubsystemFilterAction(editor, ctv, action, drop);
                    if (!Global.setFieldForce(aci, "action", wrapper)) //$NON-NLS-1$
                    {
                        continue;
                    }
                    wrapped++;
                }
                if (wrapped > 0)
                    tbm.update(true);
                ctv.setData(FILTER_ACTION_HOOK_FLAG, Boolean.TRUE);
            }

            private final class FilterAwareSubsystemFilterAction extends Action
            {
                private final IEditorPart editor;
                private final CheckboxTreeViewer ctv;
                private final IAction original;
                private final boolean dropAction;

                FilterAwareSubsystemFilterAction(IEditorPart editor, CheckboxTreeViewer ctv,
                        IAction original, boolean dropAction)
                {
                    super(original.getText() != null ? original.getText() : "", //$NON-NLS-1$
                            original.getStyle());
                    this.editor = editor;
                    this.ctv = ctv;
                    this.original = original;
                    this.dropAction = dropAction;
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
                    if (dropAction)
                    {
                        // drop() чистит только EDT-пометки; наш «чёрный список» — отдельно.
                        FilterBySubsystemsDialogHook.clearBlacklistMode(blacklistSettingsKey);
                        syncNativeFilterAttachment(ctv, false);
                    }
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
                        return;
                    }
                    candidate = candidate instanceof IPartialModelNode n ? n.getParent() : null;
                }
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
                    // Как после смены combo: пересчитать уже видимые папки.
                    paintVisibleAggregateTreeItems(ctv);
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
            }

            private static int depthOf(IPartialModelNode node)
            {
                int d = 0;
                for (IPartialModelNode p = node.getParent(); p != null; p = p.getParent())
                    d++;
                return d;
            }

            /**
             * Красит уже существующий TreeItem. Значение агрегата — только из
             * отфильтрованных узлов модели ({@link #computeAggregateFromFilteredChildren}),
             * развёрнутость дерева не влияет.
             */
            private void paintAggregateFromChildren(CheckboxTreeViewer ctv, IPartialModelNode node)
            {
                if (!isFilterAwareAggregateUi(node))
                    return;
                AggregateCheck agg = computeAggregateFromFilteredChildren(ctv, node);
                if (agg == null)
                    return;
                if (!agg.checked && !agg.grayed)
                {
                    // Кэш «пусто» оставляем — иначе provider пересчитает и снова
                    // подхватит own mustBeMerged контейнера.
                    putAggregateUi(node, false, false);
                    paintTreeItemCheckNoExpand(ctv, node, false, false);
                    return;
                }
                putAggregateUi(node, agg.checked, agg.grayed);
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

            /**
             * Кэши одного каскада пометок (клик / SelectAll): дети через
             * {@link #resolveChildren} и значения агрегатов. Живёт от listener до
             * завершения async-записи BM. Дети — без обхода content provider повторно;
             * агрегаты не переживают BM-запись — {@link #commitFilteredMarksBm}
             * очищает их после записи.
             */
            private static final class CheckCascadeContext
            {
                final Map<IPartialModelNode, Collection<IPartialModelNode>> children =
                        new IdentityHashMap<>();
                final Map<IPartialModelNode, AggregateCheck> aggregates =
                        new IdentityHashMap<>();
                final Map<IPartialModelNode, Boolean> visible =
                        new IdentityHashMap<>();

                boolean hasAggregate(IPartialModelNode node)
                {
                    return aggregates.containsKey(node);
                }

                AggregateCheck aggregateOf(IPartialModelNode node)
                {
                    return aggregates.get(node);
                }

                void putAggregate(IPartialModelNode node, AggregateCheck agg)
                {
                    aggregates.put(node, agg);
                }

                void clearAggregates()
                {
                    aggregates.clear();
                }
            }

            private static final int AGGREGATE_MAX_DEPTH = 64;

            /**
             * Агрегат только по детям, видимым при активном отборе (фильтры viewer).
             * Дети — через {@link #resolveChildren} (content provider), не через TreeItem:
             * свёрнутый узел даёт тот же результат, что развёрнутый.
             */
            private AggregateCheck computeAggregateFromFilteredChildren(
                    CheckboxTreeViewer ctv, IPartialModelNode node)
            {
                return computeAggregateFromFilteredChildren(ctv, node, 0,
                        Collections.newSetFromMap(new IdentityHashMap<>()));
            }


            private AggregateCheck computeAggregateFromFilteredChildren(
                    CheckboxTreeViewer ctv, IPartialModelNode node, int depth,
                    Set<IPartialModelNode> visiting)
            {
                CheckCascadeContext ctx = activeCascadeContext();
                if (ctx != null)
                {
                    if (ctx.hasAggregate(node))
                        return ctx.aggregateOf(node);
                }
                if (node == null || depth > AGGREGATE_MAX_DEPTH || !visiting.add(node))
                    return null;
                try
                {
                    Collection<IPartialModelNode> children = ctv != null
                            ? resolveChildren(ctv, node)
                            : node.getChildren();
                    if (children == null || children.isEmpty())
                    {
                        // Нет детей в модели: checkable + видим → своя галка;
                        // иначе null (не участвует в родителе).
                        AggregateCheck noKids = aggregateWhenNoConsideredChildren(ctv, node,
                                shouldRestrictChecksToVisible());
                        if (ctx != null)
                            ctx.putAggregate(node, noKids);
                        return noKids;
                    }
                    boolean restrictVisible = shouldRestrictChecksToVisible();
                    boolean anyChecked = false;
                    boolean anyUnchecked = false;
                    boolean anyGrayed = false;
                    int considered = 0;
                    for (IPartialModelNode child : children)
                    {
                        // Non-checkable ⇒ поддерево без пометок — не участвует в агрегате.
                        if (!child.isCheckable())
                            continue;
                        if (restrictVisible && ctv != null
                                && !isVisibleForCheck(ctv, node, child))
                            continue;
                        boolean recurseLikeFolder = isFilterAwareAggregateUi(child)
                                || (restrictVisible && hasContainmentChildren(child));
                        if (recurseLikeFolder)
                        {
                            AggregateCheck nested = computeAggregateFromFilteredChildren(
                                    ctv, child, depth + 1, visiting);
                            // null = нет содержимого под фильтром — не mixed у родителя.
                            // (false,false) = реально снят — участвует.
                            if (nested == null)
                                continue;
                            considered++;
                            if (!nested.checked && !nested.grayed)
                            {
                                anyUnchecked = true;
                                continue;
                            }
                            if (nested.grayed)
                                anyGrayed = true;
                            if (nested.checked)
                                anyChecked = true;
                            else
                                anyUnchecked = true;
                            continue;
                        }
                        considered++;
                        if (isNodeMustBeMergedFlag(child))
                            anyChecked = true;
                        else
                            anyUnchecked = true;
                    }
                    AggregateCheck result;
                    if (considered == 0)
                    {
                        result = aggregateWhenNoConsideredChildren(ctv, node, restrictVisible);
                    }
                    else if (anyGrayed || (anyChecked && anyUnchecked))
                        result = new AggregateCheck(true, true);
                    else if (anyChecked)
                        result = new AggregateCheck(true, false);
                    else
                        result = new AggregateCheck(false, false);
                    if (ctx != null)
                        ctx.putAggregate(node, result);
                    return result;
                }
                finally
                {
                    visiting.remove(node);
                }
            }

            /**
             * Нет учтённых детей: {@code null} — не участвует в родителе (скрытая
             * пустая папка без TreeItem); иначе галка самого checkable-узла.
             * «Справочники»☑ при «Справочник1» без checkbox (kids=0) должны
             * участвовать — иначе корень□.
             * Не читать сырой TreeItem раньше кэша/модели: при каскадном снятии
             * с родителя TreeItem коллекции ещё ☑, и агрегат «воскрешал» пометку.
             */
            private AggregateCheck aggregateWhenNoConsideredChildren(CheckboxTreeViewer ctv,
                    IPartialModelNode node, boolean restrictVisible)
            {
                if (node == null || !node.isCheckable())
                    return null;
                if (restrictVisible && ctv != null)
                {
                    IPartialModelNode parent = node.getParent();
                    Object visParent = parent != null ? parent : ctv.getInput();
                    if (!isVisibleForCheck(ctv, visParent, node))
                        return null;
                }
                // «Свойства» / Configuration — галка узла в UI даже без учтённых детей.
                if (isPropertiesVirtualFolderNode(node)
                        || isConfigurationPartialModelNode(node))
                    return new AggregateCheck(isNodeMustBeMergedFlag(node), false);
                // Коллекция/папка без помечаемых детей — своя галка (collectionOwn).
                // Кэш после seedCollectionOwnMarksUi важнее сырого TreeItem:
                // иначе каскадное снятие с родителя видит ещё ☑ на коллекции.
                if (isAggregateCheckNode(node) || shouldRecurseOnly(node))
                {
                    Boolean cached = aggregateCheckedUi.get(node);
                    if (cached != null)
                    {
                        boolean g = Boolean.TRUE.equals(aggregateGrayedUi.get(node));
                        return new AggregateCheck(cached.booleanValue(), g);
                    }
                    if (ctv != null)
                    {
                        Widget w = ctv.testFindItem(node);
                        if (w instanceof TreeItem item && !item.isDisposed())
                            return new AggregateCheck(item.getChecked(), item.getGrayed());
                    }
                    if (isNodeMustBeMergedFlag(node))
                        return new AggregateCheck(true, false);
                    return null;
                }
                // MD с Формы/Реквизиты: дети могли не учться в агрегате (пустые
                // папки / non-checkable). Раньше всегда □ — из‑за этого «Документ1»
                // с Свойства+Реквизиты оставался □ при уже выставленном mustBeMerged
                // (один ребёнок «Свойства» шёл в ветку own BM и был ОК).
                Boolean cachedMd = aggregateCheckedUi.get(node);
                if (cachedMd != null)
                {
                    boolean g = Boolean.TRUE.equals(aggregateGrayedUi.get(node));
                    return new AggregateCheck(cachedMd.booleanValue(), g);
                }
                if (isNodeMustBeMergedFlag(node))
                    return new AggregateCheck(true, false);
                if (hasContainmentChildren(node) && hasNonPropertiesCheckableChild(ctv, node))
                    return new AggregateCheck(false, false);
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

            /** Активные каскады (вложенность на UI-потоке: sync + async-фазы). */
            private final Deque<CheckCascadeContext> cascadeContexts = new ArrayDeque<>();

            private void beginCascadeContext(CheckCascadeContext ctx)
            {
                if (ctx == null)
                    return;
                cascadeContexts.push(ctx);
            }

            private void endCascadeContext()
            {
                if (cascadeContexts.isEmpty())
                    return;
                cascadeContexts.pop();
            }

            private CheckCascadeContext activeCascadeContext()
            {
                return cascadeContexts.isEmpty() ? null : cascadeContexts.peek();
            }

            private void putAggregateUi(IPartialModelNode node, boolean checked, boolean grayed)
            {
                aggregateCheckedUi.put(node, Boolean.valueOf(checked));
                aggregateGrayedUi.put(node, Boolean.valueOf(grayed));
            }

            /** Сброс кэша только у узла и предков — соседей не трогаем (иначе
             * CheckStateProvider отдаёт серый из модели со скрытыми mustBeMerged). */
            private void invalidateAggregateUiAncestors(IPartialModelNode from)
            {
                for (IPartialModelNode p = from; p != null; p = p.getParent())
                {
                    aggregateCheckedUi.remove(p);
                    aggregateGrayedUi.remove(p);
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

            /**
             * UI-агрегат при filter-aware: папки/коллекции/«Конфигурация» плюс
             * MD с детьми (Справочник…) — их галка = видимые потомки, не сырой
             * {@code isChecked()}. Клик bulk по-прежнему только
             * {@link #isVisibleOnlyClickTarget} (без needsOwnMergeFlag).
             */
            private static boolean isFilterAwareAggregateUi(Object el)
            {
                if (isAggregateCheckNode(el) || isVisibleOnlyClickTarget(el))
                    return true;
                if (!(el instanceof IPartialModelNode node))
                    return false;
                return node.isCheckable() && hasContainmentChildren(node);
            }

            /**
             * Агрегат: проект/папки/коллекции (для клика и UI TreeItem).
             */
            private static boolean isAggregateCheckNode(Object el)
            {
                return el instanceof ProjectPartialModelNode
                        || el instanceof VirtualFolderPartialModelNode
                        || el instanceof ICollectionPartialNode
                        || isPropertiesVirtualFolderNode(el);
            }

            /**
             * «Свойства» — отдельный класс EDT, не {@link VirtualFolderPartialModelNode}.
             * hasChildren() у модели часто false при уже созданных TreeItem-детях →
             * Роль1 kidsRaw=1 checkable=0 → skip, полная галка при серых Свойствах.
             */
            private static boolean isPropertiesVirtualFolderNode(Object el)
            {
                return el != null
                        && "PropertiesVirtualFolderNode".equals(el.getClass().getSimpleName()); //$NON-NLS-1$
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
                if (isPropertiesVirtualFolderNode(node))
                    return true;
                if (needsOwnMergeFlag(node))
                    return false;
                if (isConfigurationPartialModelNode(node))
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
             * «Конфигурация». Non-checkable не входят — их поддерево без галок.
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

                /**
                 * При отборе агрегат без кэша — visible-only пересчёт, не
                 * {@code VirtualFolder.isGrayed()} модели (скрытые mustBeMerged).
                 * {@code null} — ещё рано (гонка со штатной расстановкой): без кэша,
                 * отдаём delegate.
                 */
                private AggregateCheck aggregateUiForProvider(IPartialModelNode node)
                {
                    if (isStartupSettleActive())
                        return null;
                    Boolean cachedChecked = aggregateCheckedUi.get(node);
                    Boolean cachedGrayed = aggregateGrayedUi.get(node);
                    if (cachedChecked != null && cachedGrayed != null)
                        return new AggregateCheck(cachedChecked.booleanValue(),
                                cachedGrayed.booleanValue());
                    AggregateCheck agg = computeAggregateFromFilteredChildren(hookedCtv, node);
                    if (agg == null)
                        return null;
                    putAggregateUi(node, agg.checked, agg.grayed);
                    return agg;
                }

                @Override
                public boolean isChecked(Object element)
                {
                    // Без скрывающих отборов этот provider не должен быть установлен.
                    if (!shouldRestrictChecksToVisible())
                        return delegate != null ? delegate.isChecked(element)
                                : element instanceof IPartialModelNode n && n.isChecked();
                    if (element instanceof IPartialModelNode node
                            && isFilterAwareAggregateUi(node))
                    {
                        AggregateCheck agg = aggregateUiForProvider(node);
                        if (agg != null)
                            return agg.checked;
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
                    if (element instanceof IPartialModelNode node
                            && isFilterAwareAggregateUi(node))
                    {
                        AggregateCheck agg = aggregateUiForProvider(node);
                        if (agg != null)
                            return agg.grayed;
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
                    applyVisibleOnlyOnComparisonRoots(editor, ctv, check);
                }
            }

            private int applyVisibleOnlyOnComparisonRoots(IEditorPart editor, CheckboxTreeViewer ctv,
                    boolean checked)
            {
                Object listObj = Global.getField(editor, "comparisonArtifactsList"); //$NON-NLS-1$
                if (!(listObj instanceof List<?> artifacts))
                    return 0;
                int total = 0;
                for (Object artifact : artifacts)
                {
                    Object pm = Global.getField(artifact, "partialModel"); //$NON-NLS-1$
                    if (pm == null)
                        continue;
                    Object rootObj = Global.invoke(pm, "getRoot"); //$NON-NLS-1$
                    if (!(rootObj instanceof ProjectPartialModelNode root))
                        continue;
                    total += applyFilterAwareCheckCascade(ctv, root, checked);
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
                    Object el = event.getElement();
                    boolean checked = event.getChecked();

                    // Старт сравнения: штатный расстановщик — не пересчитываем агрегаты.
                    if (isStartupSettleActive())
                    {
                        for (ICheckStateListener delegate : delegates)
                            delegate.checkStateChanged(event);
                        return;
                    }

                    // Без скрывающих отборов: EDT ставит mustBeMerged; серые предков
                    // всё равно рисуем мы (видимы все → как EDT, единый путь).
                    if (!shouldRestrictChecksToVisible())
                    {
                        syncCheckHooksToFilterState(ctv);
                        for (ICheckStateListener delegate : delegates)
                            delegate.checkStateChanged(event);
                        if (el instanceof IPartialModelNode node)
                            repaintAncestorAggregates(ctv, node);
                        return;
                    }

                    syncCheckHooksToFilterState(ctv);

                    // Filter-aware: (1) та же пометка на всех отфильтрованных
                    // потомках; (2) 3-состояние родителей снизу вверх только по
                    // отфильтрованным потомкам.
                    if (el instanceof IPartialModelNode node)
                    {
                        applyFilterAwareCheckCascade(ctv, node, checked);
                        return;
                    }

                    for (ICheckStateListener delegate : delegates)
                        delegate.checkStateChanged(event);
                }
            }

            /**
             * Каскад при активном отборе (постановка пользователя):
             * <ol>
             * <li>та же пометка только на кликнутом и отфильтрованных потомках;</li>
             * <li>пометки узлов вне фильтра — не трогать (ни ставить, ни снимать);</li>
             * <li>у предков — 3-состояние только по отфильтрованным детям.</li>
             * </ol>
             * BM-запись — в {@code asyncExec}: в listener уже может быть транзакция
             * («Cannot open more than one transaction»). ToTree на контейнерах запрещён.
             */
            private int applyFilterAwareCheckCascade(CheckboxTreeViewer ctv,
                    IPartialModelNode clicked, boolean want)
            {
                if (ctv == null || clicked == null)
                    return 0;
                CheckCascadeContext cascadeCtx = new CheckCascadeContext();
                beginCascadeContext(cascadeCtx);
                try
                {
                    ArrayList<IPartialModelNode> targets = new ArrayList<>();
                    collectFilteredCheckable(ctv, clicked, targets);
                    for (IPartialModelNode t : targets)
                    {
                        boolean ownUi = isCollectionOwnMarkTarget(ctv, t)
                                || !isFilterAwareAggregateUi(t);
                        t.setChecked(ownUi && want);
                        Global.invoke(t, "setGrayed", Boolean.FALSE); //$NON-NLS-1$
                    }
                    clearAggregateUiUnder(clicked);
                    // «Справочники» без помечаемых детей: зафиксировать свою галку в
                    // TreeItem+кэше до пересчёта агрегатов (иначе stale TreeItem☑).
                    seedCollectionOwnMarksUi(ctv, targets, want);
                    // MD с несколькими папками (Свойства+Реквизиты): ownUi=false, BM
                    // ещё не записан — кэш want, иначе агрегат даёт □ до async.
                    seedMdAggregateTargetsUi(ctv, targets, want);
                    paintFilteredSubtreeLeafChecks(ctv, clicked);
                    paintAggregatesBottomUpUnder(ctv, clicked);
                    if (isFilterAwareAggregateUi(clicked))
                        paintAggregateFromChildren(ctv, clicked);
                    else
                        paintPlainCheckUi(ctv, clicked, want && !targets.isEmpty());
                    recalculateAncestorAggregatesUpward(ctv, clicked);

                    IComparisonSession session = null;
                    for (IPartialModelNode t : targets)
                    {
                        session = t.getComparisonSession();
                        if (session != null)
                            break;
                    }
                    if (session == null || targets.isEmpty())
                        return targets.size();
                    IComparisonSession sessionFinal = session;
                    ArrayList<IPartialModelNode> targetsFinal = new ArrayList<>(targets);
                    boolean wantFinal = want;
                    IPartialModelNode clickedFinal = clicked;
                    Display display = ctv.getControl().getDisplay();
                    display.asyncExec(() ->
                    {
                        if (ctv.getControl() == null || ctv.getControl().isDisposed())
                            return;
                        beginCascadeContext(cascadeCtx);
                        try
                        {
                            commitFilteredMarksBm(ctv, clickedFinal, wantFinal, targetsFinal,
                                    sessionFinal);
                        }
                        finally
                        {
                            endCascadeContext();
                        }
                    });
                    return targets.size();
                }
                finally
                {
                    endCascadeContext();
                }
            }

            /**
             * Запись mustBeMerged только на отфильтрованных узлах (после listener).
             */
            private void commitFilteredMarksBm(CheckboxTreeViewer ctv,
                    IPartialModelNode clicked, boolean want,
                    List<IPartialModelNode> targets, IComparisonSession session)
            {
                ArrayList<IPartialModelNode> sessionRefused = new ArrayList<>();
                for (IPartialModelNode t : targets)
                {
                    if (!setMustBeMergedViaSession(session, t, want))
                        sessionRefused.add(t);
                }
                ArrayList<IPartialModelNode> leafFallback = new ArrayList<>();
                if (!sessionRefused.isEmpty())
                {
                    runComparisonTreeWrite(session, () ->
                    {
                        for (IPartialModelNode t : sessionRefused)
                        {
                            if (!setMergeSettingsOnNode(t, want)
                                    && !hasContainmentChildren(t))
                                leafFallback.add(t);
                        }
                    });
                }
                for (IPartialModelNode leaf : leafFallback)
                    setMergeFlagOnLeaf(leaf, want);
                // BM записан — sync красил по старому mustBeMerged; пересчёт UI обязателен.
                CheckCascadeContext ctx = activeCascadeContext();
                if (ctx != null)
                    ctx.clearAggregates();
                clearAggregateUiUnder(clicked);
                seedCollectionOwnMarksUi(ctv, targets, want);
                seedMdAggregateTargetsUi(ctv, targets, want);
                paintFilteredSubtreeLeafChecks(ctv, clicked);
                paintAggregatesBottomUpUnder(ctv, clicked);
                if (isFilterAwareAggregateUi(clicked))
                    paintAggregateFromChildren(ctv, clicked);
                recalculateAncestorAggregatesUpward(ctv, clicked);
            }

            /**
             * Коллекция/папка без видимых checkable-детей — своя галка (не ToTree).
             * Каскад с родителя должен ставить/снимать её явно.
             */
            private boolean isCollectionOwnMarkTarget(CheckboxTreeViewer ctv,
                    IPartialModelNode node)
            {
                return node != null && isFilterAwareAggregateUi(node)
                        && !hasFilteredCheckableDescendants(ctv, node);
            }

            /**
             * MD/«Конфигурация» (не VirtualFolder/коллекция): в targets с ownUi=false
             * каскад пишет mustBeMerged на узле, но UI-агрегат от детей при
             * Свойства+Реквизиты часто даёт □. Кэш want до пересчёта.
             */
            private void seedMdAggregateTargetsUi(CheckboxTreeViewer ctv,
                    List<IPartialModelNode> targets, boolean want)
            {
                if (ctv == null || targets == null)
                    return;
                for (IPartialModelNode t : targets)
                {
                    if (!isFilterAwareAggregateUi(t) || isAggregateCheckNode(t))
                        continue;
                    if (isCollectionOwnMarkTarget(ctv, t))
                        continue;
                    putAggregateUi(t, want, false);
                    paintTreeItemCheckNoExpand(ctv, t, want, false);
                }
            }

            /**
             * После clearAggregateUi: TreeItem + кэш для collectionOwn, чтобы
             * {@link #aggregateWhenNoConsideredChildren} не поднял старый ☑.
             */
            private void seedCollectionOwnMarksUi(CheckboxTreeViewer ctv,
                    List<IPartialModelNode> targets, boolean want)
            {
                if (ctv == null || targets == null)
                    return;
                for (IPartialModelNode t : targets)
                {
                    if (!isCollectionOwnMarkTarget(ctv, t))
                        continue;
                    putAggregateUi(t, want, false);
                    paintTreeItemCheckNoExpand(ctv, t, want, false);
                    t.setChecked(want);
                    Global.invoke(t, "setGrayed", Boolean.FALSE); //$NON-NLS-1$
                }
            }


            /** Галочки листьев из модели (агрегаты красит {@link #paintAggregatesBottomUpUnder}). */
            private void paintFilteredSubtreeLeafChecks(CheckboxTreeViewer ctv,
                    IPartialModelNode node)
            {
                if (ctv == null || node == null)
                    return;
                if (node.isCheckable() && !isFilterAwareAggregateUi(node))
                {
                    Widget w = ctv.testFindItem(node);
                    if (w instanceof TreeItem item && !item.isDisposed())
                    {
                        item.setChecked(node.isChecked());
                        item.setGrayed(false);
                    }
                }
                for (IPartialModelNode child : resolveChildren(ctv, node))
                {
                    if (!shouldDescendCheckCascade(ctv, node, child))
                        continue;
                    paintFilteredSubtreeLeafChecks(ctv, child);
                }
            }


            /**
             * Спуск каскада пометок: только checkable. Non-checkable ⇒ всё поддерево
             * без галок — не materialize'им и не обходим.
             */
            private boolean shouldDescendCheckCascade(CheckboxTreeViewer ctv,
                    IPartialModelNode parent, IPartialModelNode child)
            {
                if (child == null || !child.isCheckable())
                    return false;
                if (ctv == null || !shouldRestrictChecksToVisible())
                    return true;
                return isVisibleForCheck(ctv, parent, child);
            }

            private void collectFilteredCheckable(CheckboxTreeViewer ctv,
                    IPartialModelNode node, List<IPartialModelNode> out)
            {
                if (node == null)
                    return;
                if (node.isCheckable())
                    out.add(node);
                for (IPartialModelNode child : resolveChildren(ctv, node))
                {
                    if (!shouldDescendCheckCascade(ctv, node, child))
                        continue;
                    collectFilteredCheckable(ctv, child, out);
                }
            }


            /**
             * Запись MergeSettings в BM-транзакции сравнения (как EDT /
             * {@code ComparisonManager.runComparisonTreeBmModelTask}).
             */
            private static boolean runComparisonTreeWrite(IComparisonSession session,
                    Runnable body)
            {
                if (session == null || body == null)
                    return false;
                Object comparisonManager = Global.getField(session, "comparisonManager"); //$NON-NLS-1$
                if (comparisonManager == null)
                {
                    return false;
                }
                try
                {
                    AbstractBmTask<Object> task = new AbstractBmTask<>(
                            "Comfort: filter-aware checkmarks") //$NON-NLS-1$
                    {
                        @Override
                        public Object execute(IBmTransaction transaction,
                                IProgressMonitor progressMonitor)
                        {
                            body.run();
                            return null;
                        }
                    };
                    Global.invoke(comparisonManager, "runComparisonTreeBmModelTask", //$NON-NLS-1$
                            session, task);
                    return true;
                }
                catch (RuntimeException ex)
                {
                    return false;
                }
            }


            /**
             * Есть ли под узлом видимые checkable-потомки (для UI-агрегата).
             * Non-checkable дети не обходятся — их поддерево без пометок.
             */
            private boolean hasFilteredCheckableDescendants(CheckboxTreeViewer ctv,
                    IPartialModelNode node)
            {
                if (ctv == null || node == null)
                    return hasContainmentChildren(node);
                for (IPartialModelNode child : resolveChildren(ctv, node))
                {
                    if (!shouldDescendCheckCascade(ctv, node, child))
                        continue;
                    return true;
                }
                return false;
            }

            /**
             * Среди детей есть checkable/агрегат кроме «Свойства» (Формы, Реквизиты…).
             */
            private boolean hasNonPropertiesCheckableChild(CheckboxTreeViewer ctv,
                    IPartialModelNode node)
            {
                if (node == null)
                    return false;
                Collection<IPartialModelNode> children = ctv != null
                        ? resolveChildren(ctv, node)
                        : node.getChildren();
                if (children == null)
                    return false;
                for (IPartialModelNode child : children)
                {
                    if (isPropertiesVirtualFolderNode(child))
                        continue;
                    if (child.isCheckable() || isFilterAwareAggregateUi(child))
                        return true;
                }
                return false;
            }

            /**
             * Пометка через {@code session.setMustBeMerged(id, want, false)} — тот же вызов, что
             * делает штатный EDT ({@code AbstractPartialModelNode.check}). Только он регистрирует
             * узел в {@code session.getNodesWithChangedMergeSettings()} и выставляет штатное
             * {@code MergeRule}; именно этот набор перебирает объединение.
             *
             * <p>Прямая запись {@code MergeSettings.setMustBeMerged} (см.
             * {@link #setMergeSettingsOnNode}) меняет только EObject: {@code mustBeMerged=true}
             * читается и из BM, галка в дереве стоит, но набор изменённых настроек остаётся пустым,
             * {@code MergeRule} — {@code DoNotMerge}, и «Объединить» такие пометки не переносит.
             * Поэтому прямая запись осталась только как резерв, если сессия отказала.
             *
             * <p>Вызывать вне {@link #runComparisonTreeWrite}: сессия открывает свою
             * BM-транзакцию, вложенная даёт «Cannot open more than one transaction».
             */
            private static boolean setMustBeMergedViaSession(IComparisonSession session,
                    IPartialModelNode node, boolean want)
            {
                if (session == null || node == null)
                    return false;
                long id = node.getNodeId();
                if (id == -1L)
                    return false;
                try
                {
                    if (!session.setMustBeMerged(id, want, false))
                        return false;
                    node.setChecked(want);
                    if (!want)
                        Global.invoke(node, "setGrayed", Boolean.FALSE); //$NON-NLS-1$
                    return true;
                }
                catch (RuntimeException ex)
                {
                    return false;
                }
            }

            /**
             * Пишет mustBeMerged в MergeSettings без setMustBeMergedToTree.
             * cn — через retrieveComparisonNode или {@code session.getNode(id)}.
             * one-side: при необходимости {@code setCanBeMerged(true)}.
             */
            private static boolean setMergeSettingsOnNode(IPartialModelNode node, boolean want)
            {
                if (node == null)
                    return false;
                try
                {
                    ComparisonNode cn = resolveComparisonNode(node);
                    long id = node.getNodeId();
                    if (cn == null)
                    {
                        return false;
                    }
                    MergeSettings ms = cn.getMergeSettings();
                    if (ms == null)
                    {
                        return false;
                    }
                    if (want && !ms.isCanBeMerged())
                        ms.setCanBeMerged(true);
                    ms.setMustBeMerged(want);
                    if (!want)
                        ms.setHaveChildrenExcludedFromMerge(false);
                    node.setChecked(want);
                    if (!want)
                        Global.invoke(node, "setGrayed", Boolean.FALSE); //$NON-NLS-1$
                    return true;
                }
                catch (RuntimeException ex)
                {
                    return false;
                }
            }

            /** ComparisonNode: retrieveComparisonNode, иначе session.getNode(id). */
            private static ComparisonNode resolveComparisonNode(IPartialModelNode node)
            {
                if (node == null)
                    return null;
                try
                {
                    ComparisonNode cn = node.retrieveComparisonNode();
                    if (cn != null)
                        return cn;
                }
                catch (RuntimeException ignored)
                {
                    // fall through
                }
                IComparisonSession session = node.getComparisonSession();
                long id = node.getNodeId();
                if (session != null && id != -1L)
                    return session.getNode(id);
                return null;
            }

            /**
             * Реальный mustBeMerged (не артефакт клика TreeViewer на isChecked).
             */
            private static boolean isNodeMustBeMergedFlag(IPartialModelNode node)
            {
                if (node == null)
                    return false;
                try
                {
                    ComparisonNode cn = resolveComparisonNode(node);
                    if (cn != null)
                    {
                        MergeSettings ms = cn.getMergeSettings();
                        if (ms != null)
                            return ms.isMustBeMerged();
                    }
                }
                catch (RuntimeException ignored)
                {
                    // fall through
                }
                return node.isChecked();
            }


            private static void paintPlainCheckUi(CheckboxTreeViewer ctv, IPartialModelNode node,
                    boolean checked)
            {
                Widget w = ctv.testFindItem(node);
                if (w instanceof TreeItem item && !item.isDisposed())
                {
                    item.setChecked(checked);
                    item.setGrayed(false);
                }
                node.setChecked(checked);
                Global.invoke(node, "setGrayed", Boolean.FALSE); //$NON-NLS-1$
            }

            /** Агрегаты поддерева снизу вверх (только checkable-ветви). */
            private void paintAggregatesBottomUpUnder(CheckboxTreeViewer ctv,
                    IPartialModelNode node)
            {
                if (ctv == null || node == null)
                    return;
                ArrayList<IPartialModelNode> painted = new ArrayList<>();
                for (IPartialModelNode child : resolveChildren(ctv, node))
                {
                    if (!shouldDescendCheckCascade(ctv, node, child))
                        continue;
                    paintAggregatesBottomUpUnder(ctv, child);
                    if (isFilterAwareAggregateUi(child))
                    {
                        paintAggregateFromChildren(ctv, child);
                        painted.add(child);
                    }
                }
                if (!painted.isEmpty())
                    refreshNodeImages(ctv, painted);
            }


            /**
             * 3-состояние предков: непосредственный родитель, затем его родитель…
             * только по отфильтрованным потомкам.
             */
            private void recalculateAncestorAggregatesUpward(CheckboxTreeViewer ctv,
                    IPartialModelNode from)
            {
                if (ctv == null || from == null)
                    return;
                paintAncestorChainUpward(ctv, from);
                Control control = ctv.getControl();
                if (control == null || control.isDisposed())
                    return;
                CheckCascadeContext ctx = activeCascadeContext();
                control.getDisplay().asyncExec(() -> {
                    if (ctv.getControl() == null || ctv.getControl().isDisposed())
                        return;
                    beginCascadeContext(ctx);
                    try
                    {
                        paintAncestorChainUpward(ctv, from);
                    }
                    finally
                    {
                        endCascadeContext();
                    }
                });
            }

            private void paintAncestorChainUpward(CheckboxTreeViewer ctv, IPartialModelNode from)
            {
                ArrayList<IPartialModelNode> toRefresh = new ArrayList<>();
                for (IPartialModelNode p = from.getParent(); p != null; p = p.getParent())
                {
                    if (isFilterAwareAggregateUi(p))
                        paintAggregateFromChildren(ctv, p);
                    toRefresh.add(p);
                }
                refreshNodeImages(ctv, toRefresh);
            }

            /**
             * Пересчёт динамических пометок всех предков снизу вверх
             * (только отфильтрованные / видимые дети).
             * Повтор в asyncExec — EDT после listener может перерисовать
             * TreeItem из модели (скрытые mustBeMerged).
             */
            private void repaintAncestorAggregates(CheckboxTreeViewer ctv, IPartialModelNode from)
            {
                recalculateAncestorAggregatesUpward(ctv, from);
            }

            private void paintAncestorChain(CheckboxTreeViewer ctv, IPartialModelNode from)
            {
                paintAncestorChainUpward(ctv, from);
            }

            /**
             * {@code ObjectColumnLabelProvider}: warning (hasPotentialMergeProblems /
             * смена UID) и merge-action зависят от модели; {@link #paintTreeItemCheckNoExpand}
             * галки TreeItem не трогает Image — нужен {@link CheckboxTreeViewer#update}.
             */
            private void refreshNodeImages(CheckboxTreeViewer ctv,
                    Collection<? extends Object> elements)
            {
                if (ctv == null || elements == null || elements.isEmpty())
                    return;
                Control control = ctv.getControl();
                if (control == null || control.isDisposed())
                    return;
                ArrayList<Object> present = new ArrayList<>(elements.size());
                for (Object el : elements)
                {
                    if (el == null)
                        continue;
                    Widget w = ctv.testFindItem(el);
                    if (w instanceof TreeItem item && !item.isDisposed())
                        present.add(el);
                }
                if (present.isEmpty())
                    return;
                ctv.update(present.toArray(), null);
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
                if (!ComfortSettings.isCompareFilterAwareChecksEnabled())
                    return false;
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

            private Collection<IPartialModelNode> resolveChildren(CheckboxTreeViewer ctv,
                    IPartialModelNode parent)
            {
                CheckCascadeContext ctx = activeCascadeContext();
                if (ctx != null)
                {
                    Collection<IPartialModelNode> cached = ctx.children.get(parent);
                    if (cached != null)
                    {
                        return cached;
                    }
                    Collection<IPartialModelNode> loaded = resolveChildrenUncached(ctv,
                            parent);
                    ctx.children.put(parent, loaded);
                    return loaded;
                }
                return resolveChildrenUncached(ctv, parent);
            }

            /**
             * Дети узла с подгрузкой через content provider (иначе у свёрнутых
             * коллекций getChildren() пуст → клик «ничего не делает», applied=0).
             */
            private Collection<IPartialModelNode> resolveChildrenUncached(
                    CheckboxTreeViewer ctv, IPartialModelNode parent)
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


            private void clearAggregateUiUnder(IPartialModelNode node)
            {
                if (node == null)
                    return;
                aggregateCheckedUi.remove(node);
                aggregateGrayedUi.remove(node);
                Collection<IPartialModelNode> children = node.getChildren();
                if (children == null)
                    return;
                for (IPartialModelNode child : children)
                    clearAggregateUiUnder(child);
            }


            private static boolean hasContainmentChildren(IPartialModelNode node)
            {
                try
                {
                    ComparisonNode cn = node.retrieveComparisonNode();
                    if (cn != null)
                        return cn.hasChildren();
                }
                catch (RuntimeException ignored)
                {
                    // fall through
                }
                Collection<IPartialModelNode> ch = node.getChildren();
                return ch != null && !ch.isEmpty();
            }

            /**
             * {@code setMustBeMerged} только для BM-листа без детей.
             * Иначе ToTree меняет пометки скрытых дочек — запрещено постановкой.
             */
            private static boolean setMergeFlagOnLeaf(IPartialModelNode node, boolean want)
            {
                if (node == null || hasContainmentChildren(node))
                    return false;
                IComparisonSession session = node.getComparisonSession();
                long id = node.getNodeId();
                if (session != null && id != -1L)
                {
                    session.setMustBeMerged(id, want, true);
                    node.setChecked(want);
                    if (!want)
                        Global.invoke(node, "setGrayed", Boolean.FALSE); //$NON-NLS-1$
                    return true;
                }
                node.check(want);
                return node.isChecked() == want;
            }


            /**
             * Видимость для каскада флажков: без oneSide-логов во время bulk.
             * Уже созданный {@link TreeItem} = узел на экране (не доверяем одному
             * {@code ViewerFilter.select} по папкам: у коллекций {@code getChildren()}
             * часто пуст, а дерево уже показало узел через content provider →
             * select=false → корень□ при «Справочники»☑).
             */
            private boolean isVisibleForCheck(Viewer viewer, Object parent, Object element)
            {
                CheckCascadeContext ctx = activeCascadeContext();
                if (ctx != null && element instanceof IPartialModelNode node)
                {
                    Boolean cached = ctx.visible.get(node);
                    if (cached != null)
                    {
                        return cached.booleanValue();
                    }
                    boolean r = isVisibleForCheckUncached(viewer, parent, element);
                    ctx.visible.put(node, Boolean.valueOf(r));
                    return r;
                }
                return isVisibleForCheckUncached(viewer, parent, element);
            }

            private boolean isVisibleForCheckUncached(Viewer viewer, Object parent, Object element)
            {
                if (viewer instanceof CheckboxTreeViewer ctv && element != null)
                {
                    Widget w = ctv.testFindItem(element);
                    if (w instanceof TreeItem item && !item.isDisposed())
                        return true;
                }
                return isVisibleInViewer(viewer, parent, element);
            }


            /**
             * Как {@code MdObjectPartialModelController.refreshNode}:
             * checked ← isMustBeMerged, grayed ← isHaveChildrenExcludedFromMerge.
             * VirtualFolder считает сам по детям — setChecked/setGrayed у него no-op.
             */
            private void syncAncestorsCheckCacheFromMergeSettings(IPartialModelNode from)
            {
                for (IPartialModelNode p = from; p != null; p = p.getParent())
                {
                    if (p instanceof VirtualFolderPartialModelNode)
                        continue;
                    syncCheckCacheFromMergeSettings(p);
                }
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
                ComparisonNode cn = resolveComparisonNode(node);
                if (cn == null)
                    return false;
                MergeSettings ms = cn.getMergeSettings();
                if (ms == null)
                    return false;
                boolean must = ms.isMustBeMerged();
                // При активном отборе isHaveChildrenExcludedFromMerge=true из‑за
                // скрытых (мы их сняли) — UI должен быть full/empty по видимым,
                // серость считает только наш агрегат.
                boolean excl = !shouldRestrictChecksToVisible()
                        && ms.isHaveChildrenExcludedFromMerge();
                node.setChecked(must);
                Global.invoke(node, "setGrayed", Boolean.valueOf(excl)); //$NON-NLS-1$
                return true;
            }

            /**
             * Дети папки для {@link #select}: сначала модель, иначе content
             * provider (как {@link #resolveChildren}) — иначе пустой
             * {@code getChildren()} → select=false при уже видимом TreeItem.
             */
            private Collection<IPartialModelNode> folderChildrenForSelect(Viewer viewer,
                    IPartialModelNode folder)
            {
                if (folder == null)
                    return List.of();
                Collection<IPartialModelNode> children = folder.getChildren();
                if (children != null && !children.isEmpty())
                    return children;
                if (viewer instanceof CheckboxTreeViewer ctv)
                    return resolveChildren(ctv, folder);
                return children != null ? children : List.of();
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
                    }
                    return true;
                }

                filterWasActive = true;
                if (hookedCtv != null)
                    syncCheckHooksToFilterState(hookedCtv);
                else if (viewer instanceof CheckboxTreeViewer ctv)
                    syncCheckHooksToFilterState(ctv);

                boolean blacklist = isBlacklistMode();

                // Если EDT/Combo сбросил фильтры через setFilters — вернуть коррекцию;
                // в режиме «чёрный список» штатный фильтр подсистем должен быть отсоединён
                // (ViewerFilter'ы комбинируются через AND — добавочный фильтр не может «показать»
                // то, что штатный белый список уже спрятал).
                if (viewer instanceof AbstractTreeViewer treeViewer)
                {
                    ensureAttached(treeViewer);
                    syncNativeFilterAttachment(treeViewer, blacklist);
                }

                // Манифест / Настройки проекта — вне состава подсистем; в чёрном списке не трогаем —
                // он только про объекты выбранных подсистем.
                if (!blacklist && isNonSubsystemStructuralBranch(element))
                    return false;

                // Как ViewerFilterBySubsystems$1: папка видна, только если есть видимый потомок
                if (element instanceof VirtualFolderPartialModelNode
                        || (element instanceof ICollectionPartialNode collection
                                && collection.hasChildren()))
                {
                    IPartialModelNode folder = (IPartialModelNode) element;
                    Collection<IPartialModelNode> children = folderChildrenForSelect(viewer, folder);
                    if (children.isEmpty())
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
                        if (isComparisonNodeVisible(childCn, session, blacklist))
                            return true;
                    }
                    return false;
                }

                if (!(element instanceof IDirectPartialModelNode))
                    return true;

                IDirectPartialModelNode node = (IDirectPartialModelNode) element;
                ComparisonNode cn = node.retrieveComparisonNode();
                IComparisonSession session = node.getComparisonSession();
                if (cn == null || session == null)
                    return true;

                // Свойства (модули, реквизиты-фичи…) — не объекты подсистемы;
                // видимость от родителя.
                if (cn instanceof FeatureComparisonNode)
                    return true;

                boolean visible;
                if (cn.isOneSideNode())
                {
                    // Односторонние объекты EDT пропускает (checkNodeIsSelectedForSide → true
                    // для отсутствующей стороны) — коррекция через trie, как и раньше.
                    visible = checkOneSideNode(cn, session, blacklist);
                }
                else if (!blacklist)
                {
                    // Двусторонние объекты в режиме белого списка — целиком штатный фильтр.
                    return true;
                }
                else
                {
                    // Штатный фильтр отсоединён (см. syncNativeFilterAttachment) — считаем сами.
                    visible = checkTwoSideNode(cn, session, true);
                }
                return visible;
            }

            private boolean isComparisonNodeVisible(ComparisonNode cn, IComparisonSession session,
                    boolean blacklist)
            {
                if (cn instanceof FeatureComparisonNode)
                    return true;
                if (!cn.isOneSideNode())
                    return checkTwoSideNode(cn, session, blacklist);
                return checkOneSideNode(cn, session, blacklist);
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
                if (!found)
                {
                    try
                    {
                        treeViewer.addFilter(this);
                        treeViewer.setData(FILTER_FLAG, Boolean.TRUE);
                    }
                    catch (Exception e)
                    {
                    }
                }
            }


            private boolean checkOneSideNode(ComparisonNode cn, IComparisonSession session, boolean blacklist)
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
                    return blacklist;
                }

                try
                {
                    // addProject() → isProjectChecked=true. Тогда getSelectedSubsystems()
                    // возвращает ВСЕ подсистемы проекта (у Main было 232), хотя пользователь
                    // отметил подсистему только на Other. Смотрим явные checkedSubsystemIds.
                    int checkedIds = countCheckedSubsystemIds(ds);
                    if (checkedIds == 0)
                        return blacklist;

                    EObjectTrie included = getTrie("includedInSelectedSubsystemsTrieMap", ds); //$NON-NLS-1$
                    EObjectTrie allTop = getTrie("allTopObjectsToFilterTrieMap", ds); //$NON-NLS-1$
                    boolean isTop = allTop != null && allTop.belongsTo(qn);
                    boolean inIncluded = included != null && included.belongsTo(qn);
                    // Как EDT isIncludedInSelectedSubsystems: не top → не фильтруем;
                    // top → только входящие в выбранные подсистемы (в чёрном списке — наоборот).
                    boolean visible;
                    if (included == null && allTop == null)
                        visible = true;
                    else
                        visible = !isTop || (blacklist ? !inIncluded : inIncluded);
                    return visible;
                }
                catch (Exception e)
                {
                    return true;
                }
            }

            /**
             * Двусторонние объекты: воспроизводит штатную {@code ComparisonFilterBySubsystems
             * .checkNodeIsSelected} (AND по обеим сторонам через trie), но с инверсией для чёрного
             * списка. Используется только когда штатный фильтр отсоединён от дерева
             * ({@link #syncNativeFilterAttachment}) — иначе штатный уже посчитал то же самое.
             */
            private boolean checkTwoSideNode(ComparisonNode cn, IComparisonSession session, boolean blacklist)
            {
                return checkTwoSideNodeForSide(cn, session, ComparisonSide.MAIN, blacklist)
                        && checkTwoSideNodeForSide(cn, session, ComparisonSide.OTHER, blacklist);
            }

            private boolean checkTwoSideNodeForSide(ComparisonNode cn, IComparisonSession session,
                    ComparisonSide side, boolean blacklist)
            {
                String symlink = cn instanceof SymlinkComparisonNode
                        ? ((SymlinkComparisonNode) cn).getSymlink(side)
                        : null;
                if (symlink == null || symlink.isEmpty())
                    return true;

                IComparisonDataSource ds = session.getDataSource(side);
                if (ds == null)
                    return true;

                // Чёрный список: сторона без явных checkedSubsystemIds не ограничивает.
                // Иначе projectChecked без ID (частый артефакт git-сравнения) заполняет trie
                // всеми подсистемами → AND-инверсия прячет почти все двусторонние узлы,
                // и в дереве остаётся в основном односторонняя «часть без приёмника».
                if (blacklist && countCheckedSubsystemIds(ds) == 0)
                    return true;

                QualifiedName qn = QualifiedName.create(symlink.split("\\.")); //$NON-NLS-1$
                EObjectTrie allTop = getTrie("allTopObjectsToFilterTrieMap", ds); //$NON-NLS-1$
                if (allTop == null || !allTop.belongsTo(qn))
                    return true;

                EObjectTrie included = getTrie("includedInSelectedSubsystemsTrieMap", ds); //$NON-NLS-1$
                boolean inIncluded = included != null && included.belongsTo(qn);
                return blacklist ? !inIncluded : inIncluded;
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
                return null;
            List<?> artifactsList = (List<?>) listObj;

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

    /**
     * «Найти нижние настраиваемые»: листовые checkable в поддеревьях выделения
     * (нет видимого checkable-потомка). Non-checkable и их поддеревья не обходятся.
     * Область — Ctrl/Shift-выделение или узел под курсором; результат — панель поиска.
     */
    private static final class CompareConfigLowestCheckableFinder
    {
        private static final String LOG = "CompareLowestCheckable"; //$NON-NLS-1$
        private static final String QUERY_TEXT = "нижние настраиваемые"; //$NON-NLS-1$

        /**
         * Корни обхода: при непустом наборе Ctrl/Shift-выделения — его «верхние» узлы
         * (без потомков, чей предок уже в наборе); иначе — узел под курсором (нативный single).
         */
        static List<Object> resolveRoots(Set<IPartialModelNode> multiSelected, Object nativeElement)
        {
            if (multiSelected != null && !multiSelected.isEmpty())
            {
                List<Object> roots = new ArrayList<>();
                for (IPartialModelNode node : multiSelected)
                {
                    boolean hasAncestorInSet = false;
                    for (IPartialModelNode p = node.getParent(); p != null; p = p.getParent())
                    {
                        if (multiSelected.contains(p))
                        {
                            hasAncestorInSet = true;
                            break;
                        }
                    }
                    if (!hasAncestorInSet)
                        roots.add(node);
                }
                return roots;
            }
            if (nativeElement != null)
            {
                List<Object> roots = new ArrayList<>(1);
                roots.add(nativeElement);
                return roots;
            }
            return new ArrayList<>(0);
        }

        static void run(IEditorPart editor, Shell shell, List<Object> roots)
        {
            if (editor == null || roots == null || roots.isEmpty()) return;
            AbstractTreeViewer viewer = getTreeViewerFromEditor(editor);
            if (viewer == null) return;
            Object cp = viewer.getContentProvider();
            if (!(cp instanceof ITreeContentProvider provider)) return;
            String objectColumn = CompareConfigSearchDialogHook.getObjectColumnHeader(editor);

            Job job = new Job("Поиск нижних настраиваемых...") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    List<Object> leaves = new ArrayList<>();
                    try
                    {
                        for (Object root : roots)
                        {
                            if (CompareConfigSearchDialogHook.isNodeMatchFilters(root, viewer))
                                collectLeaves(root, provider, viewer, leaves);
                        }
                    }
                    catch (Throwable t)
                    {
                        Global.logError(LOG, "collection failed", t); //$NON-NLS-1$
                    }

                    List<CompareSearchMatch> matches = buildMatches(leaves, objectColumn);

                    Display.getDefault().asyncExec(() ->
                    {
                        if (matches.isEmpty())
                        {
                            if (shell != null && !shell.isDisposed())
                                MessageDialog.openInformation(shell,
                                    Global.withPluginWindowTitle("Найти нижние настраиваемые"), //$NON-NLS-1$
                                    "Нижние настраиваемые узлы не найдены"); //$NON-NLS-1$
                        }
                        else
                        {
                            CompareConfigSearchDialogHook.showFindAllResults(editor, matches, QUERY_TEXT);
                        }
                    });
                    return Status.OK_STATUS;
                }
            };
            job.setSystem(true);
            job.schedule();
        }

        /**
         * Post-order по видимой checkable-части дерева. Non-checkable узел и всё
         * его поддерево без галок — не обходим ({@code getChildren} не вызываем).
         * Узел — лист, если сам {@code isCheckable()} и ни у одного видимого
         * checkable-потомка чекбокса нет. Возвращает {@code true}, если в поддереве
         * есть чекбокс (включая сам узел).
         */
        private static boolean collectLeaves(
            Object node,
            ITreeContentProvider provider,
            AbstractTreeViewer viewer,
            List<Object> leaves)
        {
            if (!(node instanceof IPartialModelNode partial) || !partial.isCheckable())
                return false;
            boolean descendantCheckable = false;
            Object[] children = CompareConfigSearchDialogHook.getChildrenSafe(provider, node);
            if (children != null)
            {
                for (Object child : children)
                {
                    if (!(child instanceof IPartialModelNode childNode) || !childNode.isCheckable())
                        continue;
                    if (!CompareConfigSearchDialogHook.isNodeMatchFilters(child, viewer))
                        continue;
                    if (collectLeaves(child, provider, viewer, leaves))
                        descendantCheckable = true;
                }
            }
            if (!descendantCheckable)
                leaves.add(node);
            return true;
        }

        private static List<CompareSearchMatch> buildMatches(List<Object> leaves, String objectColumn)
        {
            List<CompareSearchMatch> matches = new ArrayList<>(leaves.size());
            for (Object leaf : leaves)
            {
                try
                {
                    String label = CompareConfigSearchDialogHook.extractNodeLabel(leaf);
                    Object parent = Global.invoke(leaf, "getParent"); //$NON-NLS-1$
                    String path = parent != null
                        ? CompareConfigSearchDialogHook.buildPathForNode(parent) : ""; //$NON-NLS-1$
                    CompareConfigSearchDialogHook.ComparisonStatusInfo status =
                        CompareConfigSearchDialogHook.computeComparisonStatusSafe(leaf);
                    matches.add(new CompareSearchMatch(leaf, path, label, objectColumn, label,
                        status.status, status.rowColorKind, status.checkable));
                }
                catch (Throwable t)
                {
                    Global.logError(LOG, "match build failed", t); //$NON-NLS-1$
                }
            }
            return matches;
        }
    }
}
