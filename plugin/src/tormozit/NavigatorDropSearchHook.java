package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.TransferData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.navigator.CommonViewer;

import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;

/**
 * DnD «навигатор → список», если состав списка нельзя менять перетаскиванием
 * (на списке нет своего обработчика drop на добавление).
 *
 * <p>Если список допускает дубли объектов метаданных (вкладка «Права», окно «Все роли») —
 * имя подставляется в поле {@link SearchBox}, связанное с этим списком напрямую,
 * и запускается поиск. 
 *
 * <p>Иначе выделяется строка перетаскиваемого объекта в том списке, куда сбросили.
 *
 * <p>Навигатор, наборы объектов и дерево сравнения сюда не входят: у них свой drop.
 */
public final class NavigatorDropSearchHook implements IStartup
{
    private static final String INSTALLED_KEY = "tormozit.navigatorDropSearch"; //$NON-NLS-1$
    private static final int PARENT_WALK = 8;
    private static final int CHILD_WALK = 6;

    private static final Set<DtGranularEditor<?>> HOOKED_EDITORS =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            log("earlyStartup"); //$NON-NLS-1$
            installDisplayFilter(display);
            IWorkbench workbench = PlatformUI.getWorkbench();
            for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
                hookWindow(window);
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
            for (Shell shell : display.getShells())
                scanControl(shell);
        });
    }

    private static void installDisplayFilter(Display display)
    {
        display.addFilter(SWT.Show, event ->
        {
            if (!(event.widget instanceof Control control) || control.isDisposed())
                return;
            display.asyncExec(() -> consider(control));
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
                scanPart(ref.getPart(false));
            for (IViewReference ref : page.getViewReferences())
                scanPart(ref.getPart(false));
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { scanPart(ref.getPart(false)); }
            @Override public void partActivated(IWorkbenchPartReference ref) { scanPart(ref.getPart(false)); }
            @Override public void partVisible(IWorkbenchPartReference ref) { scanPart(ref.getPart(false)); }
            @Override public void partClosed(IWorkbenchPartReference r) {}
            @Override public void partDeactivated(IWorkbenchPartReference r) {}
            @Override public void partHidden(IWorkbenchPartReference r) {}
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private static void scanPart(IWorkbenchPart part)
    {
        if (part == null || Global.isNavigatorPart(part))
            return;
        if (part instanceof DtGranularEditor<?> editor)
            hookMdEditor(editor);
        else if (RightsEditorFilterHook.isAllRolesEditor(part))
            hookAllRolesEditor(part);
        Object parent = Global.getField(part, "parent"); //$NON-NLS-1$
        if (parent instanceof Control control)
            scanControl(control);
    }

    private static void hookMdEditor(DtGranularEditor<?> editor)
    {
        if (!HOOKED_EDITORS.add(editor))
        {
            installOnMdEditor(editor);
            return;
        }
        editor.addPageChangedListener(event -> installOnMdEditor(editor));
        installOnMdEditor(editor);
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.timerExec(300, () -> installOnMdEditor(editor));
    }

    private static void hookAllRolesEditor(Object editor)
    {
        installOnRightsSection(editor);
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.timerExec(300, () -> installOnRightsSection(editor));
    }

    private static void installOnMdEditor(DtGranularEditor<?> editor)
    {
        if (editor == null)
            return;
        Object page = Global.invoke(editor, "getActivePageInstance"); //$NON-NLS-1$
        if (page == null && editor.getSelectedPage() instanceof IFormPage formPage)
            page = formPage;
        installOnRightsSection(page);
        if (page instanceof IFormPage formPage)
        {
            Control partControl = formPage.getPartControl();
            if (partControl != null)
                scanControl(partControl);
        }
    }

    private static void installOnRightsSection(Object page)
    {
        if (page == null)
            return;
        Object section = Global.getField(page, "objectsSection"); //$NON-NLS-1$
        if (section == null)
            return;
        Object viewerObj = Global.getField(section, "viewer"); //$NON-NLS-1$
        Object searchObj = Global.getField(section, "searchBox"); //$NON-NLS-1$
        if (!(viewerObj instanceof TreeViewer viewer) || !(searchObj instanceof SearchBox searchBox))
        {
            log("rights skip page=" + page.getClass().getName() //$NON-NLS-1$
                + " viewer=" + typeName(viewerObj) + " searchBox=" + typeName(searchObj)); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed() || searchBox.isDisposed())
            return;
        log("rights install tree=" + tree.hashCode() + " searchBox=" + searchBox.hashCode()); //$NON-NLS-1$ //$NON-NLS-2$
        installFilterPair(searchBox, tree);
    }

    private static void scanControl(Control root)
    {
        if (root == null || root.isDisposed())
            return;
        consider(root);
        if (!(root instanceof Composite composite))
            return;
        for (Control child : composite.getChildren())
            scanControl(child);
    }

    private static void consider(Control control)
    {
        if (control == null || control.isDisposed())
            return;
        if (control instanceof SearchBox || control instanceof Tree || control instanceof Table)
            tryInstall(control);
    }

    private static void tryInstall(Control origin)
    {
        if (origin == null || origin.isDisposed())
            return;
        if (Boolean.TRUE.equals(origin.getData(INSTALLED_KEY)))
            return;

        Control list;
        SearchBox searchBox;
        if (origin instanceof SearchBox box)
        {
            searchBox = box;
            list = findDirectList(box);
        }
        else if (isList(origin))
        {
            list = origin;
            searchBox = findDirectSearchBox(origin);
        }
        else
            return;
        if (list == null || list.isDisposed() || isNavigatorList(list) || hasForeignDropTarget(list))
        {
            log("tryInstall skip origin=" + origin.getClass().getSimpleName() //$NON-NLS-1$
                + " list=" + typeName(list)); //$NON-NLS-1$
            return;
        }

        if (allowsDuplicateMdObjects(list) && searchBox != null && !searchBox.isDisposed()
                && findDirectSearchBox(list) == searchBox)
        {
            log("tryInstall filter origin=" + origin.getClass().getSimpleName()); //$NON-NLS-1$
            installFilterPair(searchBox, list);
            return;
        }
        if (origin instanceof SearchBox)
            return;
        log("tryInstall select origin=" + origin.getClass().getSimpleName()); //$NON-NLS-1$
        installOn(list, list);
    }

    private static void installFilterPair(SearchBox searchBox, Control list)
    {
        installOn(list, list);
        installOn(searchBox, list);
        Control text = searchTextControl(searchBox);
        if (text != null)
            installOn(text, list);
    }

    private static void installOn(Control control, Control list)
    {
        if (control == null || control.isDisposed())
            return;
        if (Boolean.TRUE.equals(control.getData(INSTALLED_KEY)))
            return;
        if (control == list && hasForeignDropTarget(list))
            return;
        DropTarget target = ensureDropTarget(control);
        if (target == null)
        {
            log("DropTarget fail " + control.getClass().getSimpleName()); //$NON-NLS-1$
            return;
        }
        ensureLocalSelectionTransfer(target);
        target.addDropListener(new SearchDropListener(list));
        control.setData(INSTALLED_KEY, Boolean.TRUE);
        log("DropTarget on " + control.getClass().getSimpleName() + "#" + control.hashCode() //$NON-NLS-1$ //$NON-NLS-2$
            + " transfers=" + transfersOf(target)); //$NON-NLS-1$
    }

    private static final class SearchDropListener extends DropTargetAdapter
    {
        private final Control list;
        private boolean acceptDrop;

        SearchDropListener(Control list)
        {
            this.list = list;
        }

        @Override
        public void dragEnter(DropTargetEvent event)
        {
            updateDetail(event, "enter"); //$NON-NLS-1$
        }

        @Override
        public void dragOperationChanged(DropTargetEvent event)
        {
            updateDetail(event, "op"); //$NON-NLS-1$
        }

        @Override
        public void dragOver(DropTargetEvent event)
        {
            updateDetail(event, null);
        }

        @Override
        public void dropAccept(DropTargetEvent event)
        {
            updateDetail(event, "accept"); //$NON-NLS-1$
        }

        @Override
        public void dragLeave(DropTargetEvent event)
        {
            acceptDrop = false;
        }

        @Override
        public void drop(DropTargetEvent event)
        {
            String fullName = resolveDraggedFullName();
            log("drop accept=" + acceptDrop + " full=" + fullName //$NON-NLS-1$ //$NON-NLS-2$
                + " detail=" + event.detail); //$NON-NLS-1$
            if (!acceptDrop || fullName == null)
                return;
            SearchBox direct = findDirectSearchBox(list);
            if (direct != null && allowsDuplicateMdObjects(list))
            {
                String search = hierarchicalSearchName(fullName);
                if (search != null && !search.isBlank())
                    applySearch(direct, list, search);
                return;
            }
            selectMatchingRow(list, fullName);
        }

        private void updateDetail(DropTargetEvent event, String logKind)
        {
            String fullName = resolveDraggedFullName();
            if (fullName == null)
            {
                acceptDrop = false;
                if (logKind != null)
                    log("drag " + logKind + " noText sel=" + describeSelection() //$NON-NLS-1$ //$NON-NLS-2$
                        + " detail=" + event.detail); //$NON-NLS-1$
                return;
            }
            preferLocalSelectionDataType(event);
            event.detail = DND.DROP_MOVE;
            event.feedback = DND.FEEDBACK_NONE;
            acceptDrop = true;
            if (logKind != null)
                log("drag " + logKind + " MOVE full=" + fullName); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static String resolveDraggedFullName()
    {
        Object selObj = LocalSelectionTransfer.getTransfer().getSelection();
        if (!(selObj instanceof IStructuredSelection sel) || sel.isEmpty())
            return null;
        Object element = sel.getFirstElement();
        if (element == null || NavigatorTreeElementLabels.isGroupNode(element))
            return null;
        String fullName = GetRef.fullNameFromNavigatorElement(element);
        return fullName == null || fullName.isBlank() ? null : fullName;
    }

    /**
     * Хвост после владельца МД: {@code Справочник.Орг.Реквизит.Бик} → {@code Реквизит.Бик}.
     * Для самого объекта — {@code Справочник.Орг}.
     */
    private static String hierarchicalSearchName(String fullName)
    {
        String owner = MdTypeMapping.toOwnerMdObjectRef(fullName);
        if (owner == null || owner.isBlank())
            return fullName;
        String prefix = owner + "."; //$NON-NLS-1$
        if (fullName.startsWith(prefix))
        {
            String relative = fullName.substring(prefix.length());
            if (!relative.isBlank())
                return relative;
        }
        return owner;
    }

    private static String describeSelection()
    {
        Object selObj = LocalSelectionTransfer.getTransfer().getSelection();
        if (selObj == null)
            return "null"; //$NON-NLS-1$
        if (!(selObj instanceof IStructuredSelection sel) || sel.isEmpty())
            return selObj.getClass().getName();
        Object el = sel.getFirstElement();
        return el == null ? "empty" : el.getClass().getName(); //$NON-NLS-1$
    }

    private static void applySearch(SearchBox searchBox, Control list, String text)
    {
        if (searchBox == null || searchBox.isDisposed())
            return;
        log("applySearch " + text); //$NON-NLS-1$
        RightsEditorFilterHook.applyExactReference(searchBox);
        searchBox.setText(text);
        Global.invoke(searchBox, "performSearch"); //$NON-NLS-1$
        Display display = searchBox.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            if (searchBox.isDisposed())
                return;
            int end = searchBox.getCharCount();
            searchBox.setCaretOffset(end);
            searchBox.setSelection(end, end);
        });
        display.timerExec(120, () -> selectFirstVisible(list));
        if (list != null && !list.isDisposed())
            list.setFocus();
    }

    private static void selectMatchingRow(Control list, String fullName)
    {
        if (list == null || list.isDisposed() || fullName == null || fullName.isBlank())
            return;
        if (list instanceof Table table)
        {
            for (TableItem item : table.getItems())
            {
                if (matchesFullName(item.getData(), fullName))
                {
                    table.setSelection(item);
                    table.showSelection();
                    table.setFocus();
                    return;
                }
            }
            return;
        }
        if (!(list instanceof Tree tree) || tree.getItemCount() <= 0)
            return;
        TreeItem found = findTreeItem(tree.getItems(), fullName);
        if (found == null)
            return;
        tree.setSelection(found);
        tree.showItem(found);
        tree.setFocus();
    }

    private static TreeItem findTreeItem(TreeItem[] items, String fullName)
    {
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            if (matchesFullName(item.getData(), fullName))
                return item;
            TreeItem nested = findTreeItem(item.getItems(), fullName);
            if (nested != null)
                return nested;
        }
        return null;
    }

    private static boolean matchesFullName(Object data, String fullName)
    {
        if (data == null)
            return false;
        String have = GetRef.fullNameFromNavigatorElement(data);
        return fullName.equals(have);
    }

    private static void selectFirstVisible(Control list)
    {
        if (list instanceof Table table)
        {
            if (table.isDisposed() || table.getItemCount() <= 0)
                return;
            table.setSelection(table.getItem(0));
            table.showSelection();
            return;
        }
        if (!(list instanceof Tree tree) || tree.isDisposed() || tree.getItemCount() <= 0)
            return;
        TreeItem first = tree.getItem(0);
        tree.setSelection(first);
        tree.showItem(first);
    }

    /**
     * Поле фильтра, связанное со списком напрямую: пара {@code objectsSection.viewer/searchBox}
     * либо SearchBox в той же секции, без общего предка с другим {@link Tree}/{@link Table}.
     */
    private static SearchBox findDirectSearchBox(Control list)
    {
        if (list == null || list.isDisposed())
            return null;
        SearchBox fromSection = searchBoxOfRightsList(list);
        if (fromSection != null)
            return fromSection;
        Composite parent = list.getParent();
        for (int depth = 0; depth < PARENT_WALK && parent != null && !parent.isDisposed(); depth++)
        {
            if (parent instanceof CTabFolder || parent instanceof Shell)
                break;
            Control scope = countLists(parent) > 1 ? childContaining(parent, list) : parent;
            SearchBox found = findSearchBoxExclusive(scope, list, 0);
            if (found != null)
                return found;
            parent = parent.getParent();
        }
        return null;
    }

    /**
     * Список, для которого {@code searchBox} — прямой фильтр. Если в предке несколько
     * списков и поле не лежит в секции ровно одного из них — {@code null}.
     */
    private static Control findDirectList(SearchBox searchBox)
    {
        if (searchBox == null || searchBox.isDisposed())
            return null;
        Composite parent = searchBox.getParent();
        for (int depth = 0; depth < PARENT_WALK && parent != null && !parent.isDisposed(); depth++)
        {
            if (parent instanceof CTabFolder || parent instanceof Shell)
                break;
            Control[] lists = collectLists(parent);
            if (lists.length == 1)
                return lists[0];
            if (lists.length > 1)
            {
                Control pane = childContaining(parent, searchBox);
                Control[] inPane = pane == null ? new Control[0] : collectLists(pane);
                return inPane.length == 1 ? inPane[0] : null;
            }
            parent = parent.getParent();
        }
        return null;
    }

    private static SearchBox findSearchBoxExclusive(Control root, Control list, int depth)
    {
        if (root == null || root.isDisposed() || depth > CHILD_WALK)
            return null;
        if (root instanceof SearchBox box)
            return box;
        if (!(root instanceof Composite composite))
            return null;
        for (Control child : composite.getChildren())
        {
            if (child == null || child.isDisposed() || child == list)
                continue;
            if (isList(child) || containsOtherList(child, list))
                continue;
            SearchBox found = findSearchBoxExclusive(child, list, depth + 1);
            if (found != null)
                return found;
        }
        return null;
    }

    private static boolean containsOtherList(Control root, Control list)
    {
        if (root == null || root.isDisposed())
            return false;
        if (isList(root))
            return root != list;
        if (!(root instanceof Composite composite))
            return false;
        for (Control child : composite.getChildren())
        {
            if (containsOtherList(child, list))
                return true;
        }
        return false;
    }

    private static Control childContaining(Composite parent, Control descendant)
    {
        for (Control current = descendant; current != null; current = current.getParent())
        {
            if (current.getParent() == parent)
                return current;
        }
        return null;
    }

    private static int countLists(Control root)
    {
        return collectLists(root).length;
    }

    private static Control[] collectLists(Control root)
    {
        ArrayList<Control> lists = new ArrayList<>();
        collectListsInto(root, lists);
        return lists.toArray(new Control[0]);
    }

    private static void collectListsInto(Control root, ArrayList<Control> lists)
    {
        if (root == null || root.isDisposed())
            return;
        if (isList(root))
        {
            lists.add(root);
            return;
        }
        if (!(root instanceof Composite composite))
            return;
        for (Control child : composite.getChildren())
            collectListsInto(child, lists);
    }

    private static boolean isList(Control control)
    {
        return control instanceof Tree || control instanceof Table;
    }

    private static boolean hasForeignDropTarget(Control list)
    {
        if (list == null || list.isDisposed())
            return false;
        if (Boolean.TRUE.equals(list.getData(INSTALLED_KEY)))
            return false;
        return list.getData(DND.DROP_TARGET_KEY) instanceof DropTarget;
    }

    private static boolean allowsDuplicateMdObjects(Control list)
    {
        return objectsSectionForList(list) != null;
    }

    private static SearchBox searchBoxOfRightsList(Control list)
    {
        Object section = objectsSectionForList(list);
        if (section == null)
            return null;
        Object searchObj = Global.getField(section, "searchBox"); //$NON-NLS-1$
        return searchObj instanceof SearchBox box && !box.isDisposed() ? box : null;
    }

    private static Object objectsSectionForList(Control list)
    {
        if (!(list instanceof Tree tree) || tree.isDisposed())
            return null;
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return null;
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorReference ref : page.getEditorReferences())
                {
                    IWorkbenchPart part = ref.getPart(false);
                    if (part == null)
                        continue;
                    Object section = sectionIfTree(part, tree);
                    if (section != null)
                        return section;
                    if (part instanceof DtGranularEditor<?> editor)
                    {
                        section = sectionIfTree(Global.invoke(editor, "getActivePageInstance"), tree); //$NON-NLS-1$
                        if (section != null)
                            return section;
                        section = sectionIfTree(editor.getSelectedPage(), tree);
                        if (section != null)
                            return section;
                    }
                }
            }
        }
        return null;
    }

    private static Object sectionIfTree(Object owner, Tree tree)
    {
        if (owner == null)
            return null;
        Object section = Global.getField(owner, "objectsSection"); //$NON-NLS-1$
        if (section == null)
            return null;
        Object viewerObj = Global.getField(section, "viewer"); //$NON-NLS-1$
        if (!(viewerObj instanceof TreeViewer viewer))
            return null;
        Tree sectionTree = viewer.getTree();
        return sectionTree == tree ? section : null;
    }

    private static boolean isNavigatorList(Control list)
    {
        if (isNavigatorTree(list))
            return true;
        return isUnderView(list, Global.NAVIGATOR_VIEW_ID)
            || isUnderView(list, "org.eclipse.ui.navigator.ProjectExplorer"); //$NON-NLS-1$
    }

    private static boolean isNavigatorTree(Control list)
    {
        if (!(list instanceof Tree tree))
            return false;
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return false;
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                IWorkbenchPart view = page.findView(Global.NAVIGATOR_VIEW_ID);
                if (view == null)
                    continue;
                Object raw = Global.invoke(view, "getCommonViewer"); //$NON-NLS-1$
                if (raw instanceof CommonViewer viewer && viewer.getTree() == tree)
                    return true;
            }
        }
        return false;
    }

    private static boolean isUnderView(Control list, String viewId)
    {
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return false;
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                IWorkbenchPart view = page.findView(viewId);
                if (view == null)
                    continue;
                Object parent = Global.getField(view, "parent"); //$NON-NLS-1$
                if (parent instanceof Control root && isUnder(root, list))
                    return true;
            }
        }
        return false;
    }

    private static boolean isUnder(Control root, Control child)
    {
        for (Control current = child; current != null; current = current.getParent())
        {
            if (current == root)
                return true;
        }
        return false;
    }

    private static Control searchTextControl(SearchBox searchBox)
    {
        if (searchBox == null || searchBox.isDisposed())
            return null;
        for (String field : new String[] { "text", "searchText", "styledText" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Object value = Global.getField(searchBox, field);
            if (value instanceof StyledText || value instanceof Text)
                return (Control) value;
        }
        for (Control child : searchBox.getChildren())
        {
            if (child instanceof StyledText || child instanceof Text)
                return child;
        }
        return null;
    }

    private static DropTarget ensureDropTarget(Control control)
    {
        Object existing = control.getData(DND.DROP_TARGET_KEY);
        if (existing instanceof DropTarget target)
            return target;
        try
        {
            return new DropTarget(control, DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_DEFAULT);
        }
        catch (RuntimeException | SWTError e)
        {
            log("DropTarget init error: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static void ensureLocalSelectionTransfer(DropTarget target)
    {
        Transfer local = LocalSelectionTransfer.getTransfer();
        Transfer[] current = target.getTransfer();
        if (current != null)
        {
            for (Transfer transfer : current)
            {
                if (transfer == local)
                    return;
            }
            Transfer[] expanded = new Transfer[current.length + 1];
            System.arraycopy(current, 0, expanded, 0, current.length);
            expanded[current.length] = local;
            target.setTransfer(expanded);
        }
        else
            target.setTransfer(new Transfer[] { local });
    }

    private static void preferLocalSelectionDataType(DropTargetEvent event)
    {
        Transfer local = LocalSelectionTransfer.getTransfer();
        if (event.currentDataType != null && local.isSupportedType(event.currentDataType))
            return;
        TransferData[] types = event.dataTypes;
        if (types == null)
            return;
        for (TransferData type : types)
        {
            if (local.isSupportedType(type))
            {
                event.currentDataType = type;
                return;
            }
        }
    }

    private static String transfersOf(DropTarget target)
    {
        Transfer[] transfers = target.getTransfer();
        if (transfers == null)
            return "none"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < transfers.length; i++)
        {
            if (i > 0)
                sb.append(',');
            sb.append(transfers[i].getClass().getSimpleName());
        }
        return sb.toString();
    }

    private static String typeName(Object value)
    {
        return value == null ? "null" : value.getClass().getName(); //$NON-NLS-1$
    }

    private static void log(String text)
    {
    }
}
