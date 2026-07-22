package tormozit;


import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
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

import com._1c.g5.v8.dt.bsl.compare.BslModuleComparisonNode;
import com._1c.g5.v8.dt.bsl.compare.BslModuleSectionComparisonNode;
import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;
import com._1c.g5.v8.dt.compare.ui.editor.ISelectionProviderDelegate;
import com._1c.g5.v8.dt.compare.ui.partialmodel.CustomMergeSettingsStatus;
import com._1c.g5.v8.dt.compare.ui.partialmodel.IPartialModel;
import com._1c.g5.v8.dt.compare.ui.partialmodel.PartialModelController;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.ExternalPropertyPartialModelNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IDirectPartialModelNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IPartialModelNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.VirtualFolderPartialModelNode;
import org.eclipse.jface.resource.ResourceManager;
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
import com._1c.g5.v8.dt.core.platform.IDtProject;
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
            CompareConfigOpenModuleMergeHandler.install(Display.getDefault());
            RightsDialogFilterHook.install(Display.getDefault());
            ThreeSideMergeCurrentLinesHook.install(Display.getDefault());
            GitCompareCurrentLinesHook.install();

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
                        listener.setTreeViewer(v);
                        TreeSoleChildAutoExpand.installWhitelisted(
                                TreeSoleChildAutoExpand.Target.COMPARE_CONFIG, v);
                    }
                });
                return;
            }
            listener.setTreeViewer(viewer);
            TreeSoleChildAutoExpand.installWhitelisted(
                    TreeSoleChildAutoExpand.Target.COMPARE_CONFIG, viewer);
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
            IRSession irSession = IRApplication.getSession(dtProject, connectIfAbsent);
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

        public static boolean isTabularDocumentTemplate(IEditorPart editor, Object element)
        {
            if (element == null)
                return false;
            try
            {
                return getPropertySideFile(editor, element, ComparisonSide.MAIN) != null
                    || getPropertySideFile(editor, element, ComparisonSide.OTHER) != null;
            }
            catch (Exception e)
            {
                return false;
            }
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
            TreeSoleChildAutoExpand.runSuppressed(() ->
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

}
