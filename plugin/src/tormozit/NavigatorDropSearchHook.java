package tormozit;

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
import org.eclipse.ui.IViewPart;
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
 * Универсальный DnD «навигатор → список»: если состав списка нельзя изменить перетаскиванием
 * (нет своего обработчика drop на добавление), сброс узла метаданных подставляет
 * иерархическое имя (для реквизита — {@code Реквизит.Бик}) в ближайшее поле
 * {@link SearchBox} и запускает поиск.
 *
 * <p>Дерево сравнения конфигураций сюда не входит: у него нет поля поиска рядом со списком,
 * а поиск строки — специализированный ({@code CompareConfigMenuHook.NavigatorDropSupport}).
 * Навигатор и панель {@link ObjectSetsView} пропускаются: у них свой обработчик drop.
 *
 * <p>Вкладка «Права» и окно «Все роли» — {@code ObjectsSection}: дерево без штатного
 * {@code DropTarget}, запрет рисует родитель (область редактора). Вешаем цель на само дерево.
 */
public final class NavigatorDropSearchHook implements IStartup
{
    private static final String LOG = "navigator-drop-search"; //$NON-NLS-1$
    private static final String INSTALLED_KEY = "tormozit.navigatorDropSearch"; //$NON-NLS-1$
    private static final String OBJECT_SETS_VIEW_ID = "tormozit.ObjectSetsView"; //$NON-NLS-1$
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
        installPair(searchBox, tree);
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

        SearchBox searchBox;
        Control list;
        if (origin instanceof SearchBox box)
        {
            searchBox = box;
            list = findAssociatedList(box);
        }
        else if (origin instanceof Tree || origin instanceof Table)
        {
            list = origin;
            searchBox = findSearchBoxNear(origin);
        }
        else
            return;
        if (searchBox == null || searchBox.isDisposed() || list == null || list.isDisposed())
        {
            log("tryInstall skip origin=" + origin.getClass().getSimpleName() //$NON-NLS-1$
                + " searchBox=" + (searchBox == null) + " list=" + typeName(list)); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (isNavigatorOrObjectSetsList(list))
            return;
        log("tryInstall ok origin=" + origin.getClass().getSimpleName() //$NON-NLS-1$
            + " list=" + list.getClass().getSimpleName()); //$NON-NLS-1$
        installPair(searchBox, list);
    }

    private static void installPair(SearchBox searchBox, Control list)
    {
        installOn(list, searchBox, list);
        installOn(searchBox, searchBox, list);
        Control text = searchTextControl(searchBox);
        if (text != null)
            installOn(text, searchBox, list);
        Control parent = list.getParent();
        if (parent != null && !parent.isDisposed() && parent == searchBox.getParent())
            installOn(parent, searchBox, list);
    }

    private static void installOn(Control control, SearchBox searchBox, Control list)
    {
        if (control == null || control.isDisposed())
            return;
        if (Boolean.TRUE.equals(control.getData(INSTALLED_KEY)))
            return;
        DropTarget target = ensureDropTarget(control);
        if (target == null)
        {
            log("DropTarget fail " + control.getClass().getSimpleName()); //$NON-NLS-1$
            return;
        }
        ensureLocalSelectionTransfer(target);
        target.addDropListener(new SearchDropListener(searchBox, list));
        control.setData(INSTALLED_KEY, Boolean.TRUE);
        log("DropTarget on " + control.getClass().getSimpleName() + "#" + control.hashCode() //$NON-NLS-1$ //$NON-NLS-2$
            + " existingListenersOk transfers=" + transfersOf(target)); //$NON-NLS-1$
    }

    private static final class SearchDropListener extends DropTargetAdapter
    {
        private final SearchBox searchBox;
        private final Control list;
        private boolean acceptSearch;

        SearchDropListener(SearchBox searchBox, Control list)
        {
            this.searchBox = searchBox;
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
            acceptSearch = false;
        }

        @Override
        public void drop(DropTargetEvent event)
        {
            String text = resolveSearchText();
            log("drop acceptSearch=" + acceptSearch + " text=" + text //$NON-NLS-1$ //$NON-NLS-2$
                + " detail=" + event.detail); //$NON-NLS-1$
            if (!acceptSearch || text == null)
                return;
            applySearch(searchBox, list, text);
        }

        private void updateDetail(DropTargetEvent event, String logKind)
        {
            String text = resolveSearchText();
            if (text == null)
            {
                acceptSearch = false;
                if (logKind != null)
                    log("drag " + logKind + " noText sel=" + describeSelection() //$NON-NLS-1$ //$NON-NLS-2$
                        + " detail=" + event.detail); //$NON-NLS-1$
                return;
            }
            preferLocalSelectionDataType(event);
            event.detail = DND.DROP_MOVE;
            event.feedback = DND.FEEDBACK_NONE;
            acceptSearch = true;
            if (logKind != null)
                log("drag " + logKind + " MOVE text=" + text); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static String resolveSearchText()
    {
        Object selObj = LocalSelectionTransfer.getTransfer().getSelection();
        if (!(selObj instanceof IStructuredSelection sel) || sel.isEmpty())
            return null;
        Object element = sel.getFirstElement();
        if (element == null || NavigatorTreeElementLabels.isGroupNode(element))
            return null;
        String fullName = GetRef.fullNameFromNavigatorElement(element);
        if (fullName == null || fullName.isBlank())
            return null;
        String name = hierarchicalSearchName(fullName);
        log("resolveSearchText full=" + fullName + " search=" + name); //$NON-NLS-1$ //$NON-NLS-2$
        return name == null || name.isBlank() ? null : name;
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

    private static void selectFirstVisible(Control list)
    {
        if (list == null || list.isDisposed())
            return;
        if (list instanceof Table table)
        {
            if (table.getItemCount() <= 0)
                return;
            table.setSelection(table.getItem(0));
            table.showSelection();
            return;
        }
        if (list instanceof Tree tree)
        {
            if (tree.getItemCount() <= 0)
                return;
            TreeItem first = tree.getItem(0);
            tree.setSelection(first);
            tree.showItem(first);
        }
    }

    private static SearchBox findSearchBoxNear(Control list)
    {
        SearchBox found = findAssociatedSearchBox(list);
        if (found != null)
            return found;
        for (Control current = list; current != null; current = current.getParent())
        {
            Object value = Global.getField(current, "searchBox"); //$NON-NLS-1$
            if (value instanceof SearchBox box && !box.isDisposed())
                return box;
        }
        return null;
    }

    private static SearchBox findAssociatedSearchBox(Control list)
    {
        Composite parent = list.getParent();
        for (int depth = 0; depth < PARENT_WALK && parent != null && !parent.isDisposed(); depth++)
        {
            SearchBox found = findSearchBoxIn(parent, list, 0);
            if (found != null)
                return found;
            if (parent instanceof CTabFolder || parent instanceof Shell)
                break;
            parent = parent.getParent();
        }
        return null;
    }

    private static Control findAssociatedList(SearchBox searchBox)
    {
        Composite parent = searchBox.getParent();
        for (int depth = 0; depth < PARENT_WALK && parent != null && !parent.isDisposed(); depth++)
        {
            Control found = findListIn(parent, searchBox, 0);
            if (found != null)
                return found;
            if (parent instanceof CTabFolder || parent instanceof Shell)
                break;
            parent = parent.getParent();
        }
        return null;
    }

    private static SearchBox findSearchBoxIn(Composite root, Control skip, int depth)
    {
        if (root == null || root.isDisposed() || depth > CHILD_WALK)
            return null;
        for (Control child : root.getChildren())
        {
            if (child == skip || child.isDisposed())
                continue;
            if (child instanceof SearchBox box)
                return box;
            if (child instanceof Composite composite)
            {
                SearchBox found = findSearchBoxIn(composite, skip, depth + 1);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static Control findListIn(Composite root, Control skip, int depth)
    {
        if (root == null || root.isDisposed() || depth > CHILD_WALK)
            return null;
        for (Control child : root.getChildren())
        {
            if (child == skip || child.isDisposed())
                continue;
            if (child instanceof Tree || child instanceof Table)
                return child;
            if (child instanceof SearchBox)
                continue;
            if (child instanceof Composite composite)
            {
                Control found = findListIn(composite, skip, depth + 1);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static boolean isNavigatorOrObjectSetsList(Control list)
    {
        if (isNavigatorTree(list))
            return true;
        return isUnderView(list, Global.NAVIGATOR_VIEW_ID)
            || isUnderView(list, "org.eclipse.ui.navigator.ProjectExplorer") //$NON-NLS-1$
            || isUnderView(list, OBJECT_SETS_VIEW_ID);
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
                IViewPart view = page.findView(Global.NAVIGATOR_VIEW_ID);
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
                IViewPart view = page.findView(viewId);
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
