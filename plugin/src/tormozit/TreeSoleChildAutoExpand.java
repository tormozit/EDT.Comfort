package tormozit;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.events.TreeAdapter;
import org.eclipse.swt.events.TreeEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.form.ui.editor.FormEditor;
import com._1c.g5.v8.dt.form.ui.editor.FormEditorPage;

/**
 * Авторазворачивание цепочки единственных дочерних узлов при ручном expand.
 * <p>
 * Единая точка: белый список {@link Target}. Поведение включается только для
 * перечисленных деревьев и только при «Улучшать списки».
 */
public final class TreeSoleChildAutoExpand implements IStartup
{
    private static final String MARKER_KEY = "tormozit.treeSoleChildAutoExpand"; //$NON-NLS-1$

    private static final String COMPARE_EDITOR_ID = "com._1c.g5.v8.dt.compare.ui.editor"; //$NON-NLS-1$
    private static final String FORM_EDITOR_ID = "com._1c.g5.v8.dt.form.ui.formEditor"; //$NON-NLS-1$
    private static final String ORDINARY_FORM_EDITOR_ID =
            "com._1c.g5.v8.dt.form.ui.ordinaryFormEditor"; //$NON-NLS-1$
    /** Панель «Ошибки конфигурации». */
    private static final String PROBLEM_VIEW_ID = "com._1c.g5.v8.dt.ui.problemView"; //$NON-NLS-1$
    private static final String SEARCH_VIEW_ID = "org.eclipse.search.ui.views.SearchView"; //$NON-NLS-1$

    /** Белый список деревьев, для которых разрешено авторазворачивание. */
    private static final Set<Target> WHITELIST = EnumSet.of(
            Target.COMPARE_CONFIG,
            Target.SEARCH_CONFIG,
            Target.SEARCH_FILES,
            Target.FORM_ITEMS,
            Target.CONFIG_ERRORS);

    private static final ThreadLocal<Boolean> SUPPRESSED = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> IN_AUTO_EXPAND = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Дерево из белого списка. */
    public enum Target
    {
        /** Редактор сравнения конфигураций. */
        COMPARE_CONFIG,
        /** Глобальный поиск по метаданным — левое дерево. */
        SEARCH_CONFIG,
        /** Поиск по файлам — левое дерево. */
        SEARCH_FILES,
        /** Редактор формы — дерево элементов (не реквизиты). */
        FORM_ITEMS,
        /** Панель «Ошибки конфигурации». */
        CONFIG_ERRORS
    }

    @FunctionalInterface
    public interface VisibleChildFilter
    {
        boolean isVisible(AbstractTreeViewer viewer, Object parent, Object child);
    }

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(TreeSoleChildAutoExpand::bootstrap);
    }

    private static void bootstrap()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;

        IWorkbench workbench = PlatformUI.getWorkbench();
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            hookWindow(window);

        workbench.addWindowListener(new IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow window) { hookWindow(window); }
            @Override public void windowActivated(IWorkbenchWindow window) {}
            @Override public void windowDeactivated(IWorkbenchWindow window) {}
            @Override public void windowClosed(IWorkbenchWindow window) {}
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
                tryInstallFromPart(ref.getView(false));
            for (IEditorReference ref : page.getEditorReferences())
                tryInstallFromPart(ref.getEditor(false));
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                tryInstallFromPart(ref != null ? ref.getPart(false) : null);
            }

            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                tryInstallFromPart(ref != null ? ref.getPart(false) : null);
            }

            @Override
            public void partVisible(IWorkbenchPartReference ref)
            {
                tryInstallFromPart(ref != null ? ref.getPart(false) : null);
            }

            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) {}
        });
    }

    private static void tryInstallFromPart(IWorkbenchPart part)
    {
        if (part == null || part.getSite() == null)
            return;

        String id = part.getSite().getId();
        if (PROBLEM_VIEW_ID.equals(id) && part instanceof IViewPart view)
            scheduleResolve(Target.CONFIG_ERRORS, () -> resolveProblemViewTree(view));
        else if (COMPARE_EDITOR_ID.equals(id) && part instanceof IEditorPart editor)
            scheduleResolve(Target.COMPARE_CONFIG, () -> resolveCompareTree(editor));
        else if ((FORM_EDITOR_ID.equals(id) || ORDINARY_FORM_EDITOR_ID.equals(id))
                && part instanceof IEditorPart)
            scheduleResolve(Target.FORM_ITEMS, TreeSoleChildAutoExpand::resolveFormItemsTree);
        else if (SEARCH_VIEW_ID.equals(id) && part instanceof IViewPart view)
        {
            scheduleResolve(Target.SEARCH_CONFIG, () -> resolveSearchConfigTree(view));
            scheduleResolve(Target.SEARCH_FILES, () -> resolveSearchFilesTree(view));
        }
    }

    private static void scheduleResolve(Target target, ViewerSupplier supplier)
    {
        Display.getDefault().asyncExec(() -> scheduleResolve(target, supplier, 0));
    }

    private static void scheduleResolve(Target target, ViewerSupplier supplier, int attempt)
    {
        if (!WHITELIST.contains(target) || !ComfortSettings.isReplaceListFiltersEnabled())
            return;

        AbstractTreeViewer viewer = supplier != null ? supplier.get() : null;
        if (viewer != null)
        {
            installWhitelisted(target, viewer);
            return;
        }
        if (attempt < 20)
            Display.getDefault().timerExec(150, () -> scheduleResolve(target, supplier, attempt + 1));
    }

    @FunctionalInterface
    private interface ViewerSupplier
    {
        AbstractTreeViewer get();
    }

    /**
     * Устанавливает авторазворачивание, только если {@code target} в белом списке.
     * Вызывается из доменных хуков, когда viewer уже найден (поиск и т.п.).
     */
    public static void installWhitelisted(Target target, AbstractTreeViewer viewer)
    {
        if (target == null || !WHITELIST.contains(target) || viewer == null)
            return;
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        install(viewer, TreeSoleChildAutoExpand::defaultVisible, ComfortSettings::isReplaceListFiltersEnabled);
    }

    static void runSuppressed(Runnable action)
    {
        if (action == null)
            return;

        Boolean previous = SUPPRESSED.get();
        SUPPRESSED.set(Boolean.TRUE);
        try
        {
            action.run();
        }
        finally
        {
            SUPPRESSED.set(previous);
        }
    }

    /**
     * Программный sole-child разворот от {@code root} (после фильтра и т.п.).
     * Для lazy-деревьев догружает детей через {@code loadAndGetChildren}.
     */
    public static void expandSoleChildChainFrom(AbstractTreeViewer viewer, Object root)
    {
        expandSoleChildChainFrom(viewer, root, TreeSoleChildAutoExpand::defaultVisible);
    }

    public static void expandSoleChildChainFrom(AbstractTreeViewer viewer, Object root, VisibleChildFilter filter)
    {
        if (viewer == null || root == null || filter == null)
            return;
        Tree tree = resolveTree(viewer);
        if (tree == null || tree.isDisposed())
            return;
        IN_AUTO_EXPAND.set(Boolean.TRUE);
        try
        {
            expandSoleChildChain(viewer, root, filter);
        }
        finally
        {
            IN_AUTO_EXPAND.set(Boolean.FALSE);
        }
    }

    // ---- Резолверы деревьев из белого списка ----

    private static AbstractTreeViewer resolveProblemViewTree(IViewPart view)
    {
        if (view == null)
            return null;
        Object adapted = view.getAdapter(TreeViewer.class);
        return adapted instanceof AbstractTreeViewer treeViewer ? treeViewer : null;
    }

    private static AbstractTreeViewer resolveCompareTree(IEditorPart editor)
    {
        Object view = Global.getField(editor, "comparisonView"); //$NON-NLS-1$
        if (!(view instanceof DtComparisonView))
            return null;
        Object treeControl = ((DtComparisonView) view).getTreeControl();
        if (treeControl == null)
            return null;
        Object viewer = Global.call(treeControl, "getTreeViewer"); //$NON-NLS-1$
        return viewer instanceof AbstractTreeViewer treeViewer ? treeViewer : null;
    }

    private static AbstractTreeViewer resolveFormItemsTree()
    {
        FormEditorPage page = FormEditor.getActiveFormEditorPage();
        if (page == null)
            return null;

        Object itemsViewer = Global.getField(page, "itemsViewer"); //$NON-NLS-1$
        Object attributesViewer = Global.getField(page, "attributesViewer"); //$NON-NLS-1$
        // Дерево реквизитов содержит циклы — никогда не подключать.
        if (itemsViewer == null || itemsViewer == attributesViewer)
            return null;
        if (!(itemsViewer instanceof AbstractTreeViewer treeViewer))
            return null;

        Tree itemsTree = resolveTree(treeViewer);
        if (itemsTree == null || itemsTree.isDisposed())
            return null;
        if (attributesViewer instanceof AbstractTreeViewer attrViewer)
        {
            Tree attrTree = resolveTree(attrViewer);
            if (itemsTree == attrTree)
                return null;
        }
        return treeViewer;
    }

    private static AbstractTreeViewer resolveSearchConfigTree(IViewPart view)
    {
        Object page = activeSearchPage(view);
        if (page == null || !page.getClass().getName().contains("ConfigurationSearchViewPage")) //$NON-NLS-1$
            return null;
        Object treeLayout = Global.getField(page, "treeLayout"); //$NON-NLS-1$
        if (treeLayout == null)
            return null;
        Object viewer = Global.invoke(treeLayout, "getViewer"); //$NON-NLS-1$
        return viewer instanceof AbstractTreeViewer treeViewer ? treeViewer : null;
    }

    private static AbstractTreeViewer resolveSearchFilesTree(IViewPart view)
    {
        Object page = activeSearchPage(view);
        if (page == null || !page.getClass().getName().contains("FileSearchPage")) //$NON-NLS-1$
            return null;
        Object viewer = Global.getField(page, "fViewer"); //$NON-NLS-1$
        return viewer instanceof AbstractTreeViewer treeViewer ? treeViewer : null;
    }

    private static Object activeSearchPage(IViewPart view)
    {
        if (!(view instanceof org.eclipse.search.ui.ISearchResultViewPart searchView))
            return null;
        return searchView.getActivePage();
    }

    // ---- Установка listener ----

    private static void install(AbstractTreeViewer viewer, VisibleChildFilter filter,
            java.util.function.BooleanSupplier enabled)
    {
        if (viewer == null || filter == null)
            return;

        Tree tree = resolveTree(viewer);
        if (tree == null || tree.isDisposed() || Boolean.TRUE.equals(tree.getData(MARKER_KEY)))
            return;

        tree.setData(MARKER_KEY, Boolean.TRUE);
        tree.addTreeListener(new TreeAdapter()
        {
            @Override
            public void treeExpanded(TreeEvent event)
            {
                if (enabled == null || !enabled.getAsBoolean()
                        || Boolean.TRUE.equals(SUPPRESSED.get())
                        || Boolean.TRUE.equals(IN_AUTO_EXPAND.get()))
                    return;

                Object element = event.item != null ? event.item.getData() : null;
                if (element == null)
                    return;

                Display display = tree.getDisplay();
                display.timerExec(150, () -> {
                    if (tree.isDisposed() || Boolean.TRUE.equals(SUPPRESSED.get())
                            || Boolean.TRUE.equals(IN_AUTO_EXPAND.get()))
                        return;
                    IN_AUTO_EXPAND.set(Boolean.TRUE);
                    try
                    {
                        expandSoleChildChain(viewer, element, filter);
                    }
                    finally
                    {
                        IN_AUTO_EXPAND.set(Boolean.FALSE);
                    }
                });
            }
        });
    }

    private static Tree resolveTree(AbstractTreeViewer viewer)
    {
        if (viewer instanceof TreeViewer treeViewer)
            return treeViewer.getTree();

        Object widget = Global.call(viewer, "getTree"); //$NON-NLS-1$
        return widget instanceof Tree ? (Tree) widget : null;
    }

    private static boolean defaultVisible(AbstractTreeViewer viewer, Object parent, Object child)
    {
        for (ViewerFilter vf : viewer.getFilters())
        {
            if (!vf.select(viewer, parent, child))
                return false;
        }
        return true;
    }

    private static void expandSoleChildChain(AbstractTreeViewer viewer, Object element, VisibleChildFilter filter)
    {
        if (viewer == null || element == null)
            return;

        Object cpObj = viewer.getContentProvider();
        ITreeContentProvider cp = cpObj instanceof ITreeContentProvider tcp ? tcp : null;

        Set<String> labelsInChain = new HashSet<>();
        rememberLabel(labelsInChain, nodeLabel(viewer, element));

        Tree tree = resolveTree(viewer);
        Object current = element;
        int safety = 0;
        while (safety++ < 64)
        {
            Object[] raw = getVisibleChildren(viewer, cp, tree, current, filter);
            if (raw == null || raw.length == 0)
                break;

            Object onlyChild = null;
            int visibleCount = 0;
            for (Object child : raw)
            {
                if (child == null)
                    continue;
                visibleCount++;
                onlyChild = child;
                if (visibleCount > 1)
                    break;
            }
            if (visibleCount != 1 || onlyChild == null)
                break;

            if (isLabelCycle(viewer, current, onlyChild, labelsInChain))
                break;

            boolean hasKids = nodeHasChildren(viewer, cp, tree, onlyChild);
            if (!hasKids)
                break;

            if (!viewer.getExpandedState(onlyChild))
                viewer.setExpandedState(onlyChild, true);
            rememberLabel(labelsInChain, nodeLabel(viewer, onlyChild));
            current = onlyChild;
        }
    }

    private static boolean nodeHasChildren(AbstractTreeViewer viewer, ITreeContentProvider cp, Tree tree, Object node)
    {
        if (cp != null)
            return cp.hasChildren(node);
        Object hasChildren = Global.invoke(node, "hasChildren"); //$NON-NLS-1$
        if (hasChildren instanceof Boolean b)
            return b.booleanValue();
        return hasTreeItems(tree, node);
    }

    private static Object[] getVisibleChildren(AbstractTreeViewer viewer, ITreeContentProvider cp,
            Tree tree, Object parent, VisibleChildFilter filter)
    {
        List<Object> visible = new ArrayList<>();
        if (cp != null)
        {
            Object[] raw = cp.getChildren(parent);
            if (raw != null)
            {
                for (Object child : raw)
                {
                    if (child != null && filter.isVisible(viewer, parent, child))
                        visible.add(child);
                }
                return visible.toArray();
            }
        }

        // LazyTreeNode: сначала уже загруженные, иначе синхронная догрузка (без getChildren у ILazy*).
        List<?> loaded = resolveLazyChildren(parent);
        if (loaded != null)
        {
            for (Object child : loaded)
            {
                if (child != null && filter.isVisible(viewer, parent, child))
                    visible.add(child);
            }
            if (!visible.isEmpty() || loaded.isEmpty())
                return visible.toArray();
        }

        TreeItem parentItem = findTreeItem(tree, parent);
        if (parentItem == null)
            return new Object[0];
        for (TreeItem ti : parentItem.getItems())
        {
            Object data = ti.getData();
            if (data != null && filter.isVisible(viewer, parent, data))
                visible.add(data);
        }
        return visible.toArray();
    }

    private static List<?> resolveLazyChildren(Object parent)
    {
        if (parent == null)
            return null;
        Object without = Global.invoke(parent, "getChildrenWithoutLoading"); //$NON-NLS-1$
        if (without instanceof List<?> list && !list.isEmpty())
            return list;
        Object hasChildren = Global.invoke(parent, "hasChildren"); //$NON-NLS-1$
        if (!(hasChildren instanceof Boolean b) || !b.booleanValue())
            return without instanceof List<?> empty ? empty : null;
        Object loaded = Global.invoke(parent, "loadAndGetChildren"); //$NON-NLS-1$
        return loaded instanceof List<?> list ? list : null;
    }

    private static TreeItem findTreeItem(Tree tree, Object element)
    {
        if (tree == null || element == null)
            return null;
        for (TreeItem item : tree.getItems())
        {
            TreeItem found = findTreeItemRecursive(item, element);
            if (found != null)
                return found;
        }
        return null;
    }

    private static TreeItem findTreeItemRecursive(TreeItem item, Object element)
    {
        if (item == null)
            return null;
        if (element.equals(item.getData()))
            return item;
        for (TreeItem child : item.getItems())
        {
            TreeItem found = findTreeItemRecursive(child, element);
            if (found != null)
                return found;
        }
        return null;
    }

    private static boolean hasTreeItems(Tree tree, Object element)
    {
        TreeItem item = findTreeItem(tree, element);
        return item != null && item.getItemCount() > 0;
    }

    /**
     * Цикл в модели часто выглядит как одинаковая подпись у родителя и единственного потомка
     * или повтор подписи в уже пройденной цепочке авторазворачивания.
     */
    private static boolean isLabelCycle(AbstractTreeViewer viewer, Object parent, Object child, Set<String> labelsInChain)
    {
        String parentLabel = nodeLabel(viewer, parent);
        String childLabel = nodeLabel(viewer, child);
        if (parentLabel.isEmpty() || childLabel.isEmpty())
            return false;
        if (parentLabel.equals(childLabel))
            return true;
        return labelsInChain.contains(childLabel);
    }

    private static void rememberLabel(Set<String> labelsInChain, String label)
    {
        if (label != null && !label.isEmpty())
            labelsInChain.add(label);
    }

    private static String nodeLabel(AbstractTreeViewer viewer, Object element)
    {
        if (element == null)
            return ""; //$NON-NLS-1$

        IBaseLabelProvider lp = viewer.getLabelProvider();
        if (lp == null)
            return ""; //$NON-NLS-1$

        if (lp instanceof DelegatingStyledCellLabelProvider dscp)
        {
            IStyledLabelProvider inner = dscp.getStyledStringProvider();
            if (inner != null)
                return normalizeLabel(styledStringText(inner.getStyledText(element)));
        }
        else if (lp instanceof IStyledLabelProvider styledProvider)
        {
            return normalizeLabel(styledStringText(styledProvider.getStyledText(element)));
        }

        if (lp instanceof ILabelProvider labelProvider)
            return normalizeLabel(labelProvider.getText(element));

        return ""; //$NON-NLS-1$
    }

    private static String styledStringText(StyledString styled)
    {
        return styled != null ? styled.getString() : ""; //$NON-NLS-1$
    }

    private static String normalizeLabel(String text)
    {
        if (text == null)
            return ""; //$NON-NLS-1$
        return text.trim();
    }
}
