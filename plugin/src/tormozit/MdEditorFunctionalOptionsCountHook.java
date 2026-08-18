package tormozit;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.FunctionalOption;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Колонка с числом функциональных опций у строк дерева «Состав объекта»
 * на вкладке «Функц. опции». Считается в фоне; у папок числа нет, у элементов
 * ноль показывается. Сумма — только в заголовке вкладки, не по узлам дерева.
 * Пересчёт — при добавлении и удалении опций в правом списке этой вкладки.
 */
public final class MdEditorFunctionalOptionsCountHook implements IStartup
{
    private static final String TAG = "MdEditorFoCount"; //$NON-NLS-1$

    private static final String FO_PAGE_ID = "editors.pages.functionalOptions"; //$NON-NLS-1$

    private static final String FO_CONTENT_COMPONENT_CLASS =
        "com._1c.g5.v8.dt.internal.md.ui.editors.pages.functionaloptions.DtGranularEditorFunctionalOptionsMdObjectContentComponent"; //$NON-NLS-1$

    private static final String FO_LIST_COMPONENT_CLASS =
        "com._1c.g5.v8.dt.internal.md.ui.editors.pages.functionaloptions.DtGranularEditorFunctionalOptionsPageComponent"; //$NON-NLS-1$

    private static final String BM_SERVICE_EVENT_CLASS =
        "com._1c.g5.v8.dt.aef2.bm.events.BmServiceEvent"; //$NON-NLS-1$

    private static final String DT_TREE_VIEWER_KEY =
        "com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView.treeViewer"; //$NON-NLS-1$

    private static final String HOOK_MARKER = "tormozit.foContentCountHooked"; //$NON-NLS-1$

    private static final String INDEX_KEY = "tormozit.foContentCountIndex"; //$NON-NLS-1$

    private static final String MAPPER_KEY = "tormozit.foContentCountMapper"; //$NON-NLS-1$

    private static final String LIST_LISTENER_KEY = "tormozit.foContentCountListListener"; //$NON-NLS-1$

    private static final String FO_LIST_WATCH_KEY = "tormozit.foContentCountListWatched"; //$NON-NLS-1$

    private static final String SUM_KEY = "tormozit.foContentCountSum"; //$NON-NLS-1$

    private static final String EDITOR_KEY = "tormozit.foContentCountEditor"; //$NON-NLS-1$

    private static final String VIEWER_KEY = "tormozit.foContentCountViewer"; //$NON-NLS-1$

    private static final String LAYOUT_PENDING_KEY = "tormozit.foContentCountLayoutPending"; //$NON-NLS-1$

    private static final int COUNT_COL_MIN_WIDTH = 32;

    private static final int NAME_COL_PAD = 12;

    private static final int MAX_ATTEMPTS = 40;

    private static final int RETRY_MS = 100;

    private final Set<DtGranularEditor<?>> hookedEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
            for (IWorkbenchWindow w : workbench.getWorkbenchWindows())
                hookWindow(w);
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                if (ref.getEditor(false) instanceof DtGranularEditor<?> granular)
                    hookEditor(granular);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { hookFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { hookFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) { hookFromRef(ref); }
        });
    }

    private void hookFromRef(IWorkbenchPartReference ref)
    {
        if (ref != null && ref.getPart(false) instanceof DtGranularEditor<?> granular)
            hookEditor(granular);
    }

    private void hookEditor(DtGranularEditor<?> editor)
    {
        if (editor == null)
            return;
        boolean first = hookedEditors.add(editor);
        if (first)
        {
            editor.addPageChangedListener((PageChangedEvent event) ->
            {
                IFormPage hinted = event.getSelectedPage() instanceof IFormPage form ? form : null;
                scheduleInstall(editor, 0, hinted);
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return;
                display.timerExec(200, () -> scheduleInstall(editor, 0, null));
                display.timerExec(600, () -> scheduleInstall(editor, 0, null));
            });
        }
        scheduleInstall(editor, 0, null);
    }

    private static void scheduleInstall(DtGranularEditor<?> editor, int attempt, IFormPage hintedPage)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || attempt >= MAX_ATTEMPTS)
            return;
        display.timerExec(attempt == 0 ? 0 : RETRY_MS, () ->
        {
            try
            {
                if (install(editor, hintedPage))
                    return;
            }
            catch (RuntimeException e)
            {
                Global.logError(TAG, "install", e); //$NON-NLS-1$
            }
            scheduleInstall(editor, attempt + 1, hintedPage);
        });
    }

    private static boolean install(DtGranularEditor<?> editor, IFormPage hintedPage)
    {
        IFormPage active = editor.getActivePageInstance();
        IFormPage page = isFunctionalOptionsPage(hintedPage) ? hintedPage : active;
        if (!isFunctionalOptionsPage(page))
            return true;
        Object root = Global.getField(page, "pageComponent"); //$NON-NLS-1$
        Object component = findComponentByClass(root, FO_CONTENT_COMPONENT_CLASS, 0);
        Object listComponent = findComponentByClass(root, FO_LIST_COMPONENT_CLASS, 0);
        TreeViewer viewer = component != null ? findContentTreeViewer(component, page) : null;
        Tree tree = viewer != null ? viewer.getTree() : null;
        if (component == null || listComponent == null || viewer == null || tree == null || tree.isDisposed())
            return false;
        boolean hooked = Boolean.TRUE.equals(tree.getData(HOOK_MARKER));
        Object mapper = Global.invoke(component, "getMapper"); //$NON-NLS-1$
        tree.setData(MAPPER_KEY, mapper);
        CountIndex index;
        if (tree.getData(INDEX_KEY) instanceof CountIndex existing)
        {
            index = existing;
        }
        else
        {
            index = new CountIndex();
            tree.setData(INDEX_KEY, index);
        }
        if (!hooked)
        {
            if (tree.getColumnCount() == 0)
                return false;
            installCountColumn(viewer, tree);
            tree.setData(HOOK_MARKER, Boolean.TRUE);
        }
        Configuration configuration = configurationOf(editor);
        tree.setData(EDITOR_KEY, editor);
        tree.setData(VIEWER_KEY, viewer);
        attachFoListListener(tree, listComponent, configuration, index, viewer);
        TreeViewer foListViewer = findOtherTreeViewer(page, viewer);
        watchFoListChanges(foListViewer, viewer, index, configuration);
        if (tree.getData(LIST_LISTENER_KEY) == null
            && (foListViewer == null || foListViewer.getTree().isDisposed()
                || foListViewer.getTree().getData(FO_LIST_WATCH_KEY) == null))
            return false;
        scheduleRebuild(index, configuration, viewer);
        return true;
    }

    /**
     * Узкая колонка сразу после имён, без горизонтального скролла: имя пакуется по
     * содержимому, сумма колонок не шире клиента. Штатный stretch первой колонки
     * после нашего {@code Resize} снова раздувает её — поэтому ширины ставим ещё
     * раз через {@code timerExec(0)}.
     */
    private static void installCountColumn(TreeViewer viewer, Tree tree)
    {
        if (tree.getColumnCount() == 0)
            return;
        tree.setHeaderVisible(true);
        TreeViewerColumn column = new TreeViewerColumn(viewer, SWT.RIGHT);
        TreeColumn swtColumn = column.getColumn();
        swtColumn.setText("ФО"); //$NON-NLS-1$
        swtColumn.setToolTipText("Число функциональных опций" + Global.pluginSignForTooltip());
        swtColumn.setWidth(COUNT_COL_MIN_WIDTH);
        swtColumn.setResizable(false);
        swtColumn.setMoveable(false);
        column.setLabelProvider(new CountColumnProvider(tree));
        tree.setData(VIEWER_KEY, viewer);
        tree.addListener(SWT.Resize, event -> scheduleColumnLayout(tree));
        tree.addListener(SWT.Expand, event -> scheduleColumnLayout(tree));
        tree.addListener(SWT.Collapse, event -> scheduleColumnLayout(tree));
        Composite parent = tree.getParent();
        if (parent != null && !parent.isDisposed())
            parent.layout(true, true);
        layoutCountColumns(tree);
        scheduleColumnLayout(tree);
    }

    private static void scheduleColumnLayout(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(LAYOUT_PENDING_KEY)))
            return;
        tree.setData(LAYOUT_PENDING_KEY, Boolean.TRUE);
        Display display = tree.getDisplay();
        display.timerExec(0, () ->
        {
            if (tree.isDisposed())
                return;
            tree.setData(LAYOUT_PENDING_KEY, Boolean.FALSE);
            layoutCountColumns(tree);
        });
    }

    private static void layoutCountColumns(Tree tree)
    {
        if (tree == null || tree.isDisposed() || tree.getColumnCount() < 2)
            return;
        int client = tree.getClientArea().width;
        if (client <= 0)
            return;
        TreeColumn nameCol = tree.getColumn(0);
        TreeColumn countCol = tree.getColumn(tree.getColumnCount() - 1);
        countCol.pack();
        int countW = Math.max(countCol.getWidth(), COUNT_COL_MIN_WIDTH);
        nameCol.pack();
        int packed = nameCol.getWidth() + NAME_COL_PAD;
        int maxName = Math.max(80, client - countW);
        int nameW = Math.min(packed, maxName);
        int oldName = nameCol.getWidth();
        int oldCount = countCol.getWidth();
        if (oldName != nameW)
            nameCol.setWidth(nameW);
        if (oldCount != countW)
            countCol.setWidth(countW);
        ScrollBar hBar = tree.getHorizontalBar();
        if (hBar != null && hBar.getVisible())
            hBar.setVisible(false);
    }

    private static Integer countForElement(Tree tree, Object element)
    {
        if (tree == null || !(tree.getData(INDEX_KEY) instanceof CountIndex index))
            return null;
        if (isGroupingFolder(tree, element))
            return null;
        Integer count = index.countOf(modelOf(tree.getData(MAPPER_KEY), element));
        return count;
    }

    private static boolean isGroupingFolder(Tree tree, Object element)
    {
        if (element == null)
            return false;
        Object current = element;
        for (int i = 0; i < 8 && current != null; i++)
        {
            if (classLooksLikeNavigatorFolder(current))
                return true;
            Object next = Global.invoke(current, "getElement"); //$NON-NLS-1$
            if (next == null || next == current)
                next = Global.invoke(current, "getModel"); //$NON-NLS-1$
            if (next == null || next == current)
                break;
            current = next;
        }
        TreeViewer viewer = tree != null && tree.getData(VIEWER_KEY) instanceof TreeViewer stored
            ? stored
            : tree != null && tree.getData(DT_TREE_VIEWER_KEY) instanceof TreeViewer nativeViewer
                ? nativeViewer
                : null;
        if (viewer == null || !(viewer.getContentProvider() instanceof ITreeContentProvider content))
            return false;
        Object rootElement = firstRoot(content, viewer.getInput());
        if (rootElement != null && (element == rootElement || element.equals(rootElement)))
            return false;
        Object mapper = tree.getData(MAPPER_KEY);
        EObject self = modelOf(mapper, element);
        EObject rootModel = modelOf(mapper, rootElement);
        if (self instanceof MdObject && sameMd(self, rootModel))
            return true;
        Object parent;
        try
        {
            parent = content.getParent(element);
        }
        catch (RuntimeException e)
        {
            return false;
        }
        if (parent == null)
            return false;
        EObject owner = modelOf(mapper, parent);
        return self instanceof MdObject && sameMd(self, owner);
    }

    private static Object firstRoot(ITreeContentProvider content, Object input)
    {
        try
        {
            Object[] roots = content.getElements(input);
            return roots != null && roots.length > 0 ? roots[0] : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static boolean sameMd(EObject first, EObject second)
    {
        if (first == null || second == null)
            return false;
        if (first == second)
            return true;
        Long firstId = bmId(first);
        Long secondId = bmId(second);
        return firstId != null && firstId.equals(secondId);
    }

    private static boolean classLooksLikeNavigatorFolder(Object object)
    {
        String name = object.getClass().getName();
        return name.contains("$Folder") //$NON-NLS-1$
            || name.contains("CollectionNavigatorAdapter") //$NON-NLS-1$
            || name.contains("VirtualNavigatorAdapter"); //$NON-NLS-1$
    }

    /**
     * Сумма чисел колонки ФО в дереве «Состав объекта». {@code null}, пока индекс
     * ещё не посчитан.
     */
    static Integer columnSum(IFormPage page)
    {
        if (page == null)
            return null;
        Object root = Global.getField(page, "pageComponent"); //$NON-NLS-1$
        Object component = findComponentByClass(root, FO_CONTENT_COMPONENT_CLASS, 0);
        TreeViewer viewer = component != null ? findContentTreeViewer(component, page) : null;
        Tree tree = viewer != null ? viewer.getTree() : null;
        if (tree == null || tree.isDisposed())
            return null;
        return tree.getData(SUM_KEY) instanceof Integer sum ? sum : null;
    }

    private static EObject modelOf(Object mapper, Object element)
    {
        EObject direct = NavigatorElementModels.resolveEObject(element);
        if (direct instanceof MdObject)
            return direct;
        if (mapper == null || element == null)
            return direct;
        try
        {
            Object model = Global.invoke(mapper, "mapViewToModel", element); //$NON-NLS-1$
            EObject mapped = NavigatorElementModels.resolveEObject(model);
            return mapped != null ? mapped : direct;
        }
        catch (RuntimeException e)
        {
            return direct;
        }
    }

    private static Integer sumDisplayedColumn(TreeViewer viewer)
    {
        if (viewer == null)
            return null;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return null;
        if (!(tree.getData(INDEX_KEY) instanceof CountIndex index) || !index.ready)
            return null;
        if (!(viewer.getContentProvider() instanceof ITreeContentProvider content))
            return null;
        Object[] roots;
        try
        {
            roots = content.getElements(viewer.getInput());
        }
        catch (RuntimeException e)
        {
            return null;
        }
        int[] sum = { 0 };
        walkDisplayedColumn(content, roots, tree, sum, 0);
        return Integer.valueOf(sum[0]);
    }

    private static void walkDisplayedColumn(ITreeContentProvider content, Object[] elements, Tree tree,
        int[] sum, int depth)
    {
        if (elements == null || depth > 24)
            return;
        for (Object element : elements)
        {
            Integer count = countForElement(tree, element);
            if (count != null)
                sum[0] += count.intValue();
            Object[] children;
            try
            {
                children = content.getChildren(element);
            }
            catch (RuntimeException e)
            {
                continue;
            }
            walkDisplayedColumn(content, children, tree, sum, depth + 1);
        }
    }

    /**
     * Правый список вкладки сам подписан на {@code BmServiceEvent} по
     * {@code FUNCTIONAL_OPTION__CONTENT} — то же событие, что после добавления
     * и удаления опций. Слушаем его, а не EMF-адаптер конфигурации: состав опций
     * меняется на самих ФО, не в списке конфигурации.
     */
    private static void attachFoListListener(Tree tree, Object listComponent, Configuration configuration,
        CountIndex index, TreeViewer viewer)
    {
        if (tree == null || tree.isDisposed() || listComponent == null || configuration == null)
            return;
        if (tree.getData(LIST_LISTENER_KEY) != null)
            return;
        Object listener = foListEventListener(listComponent, tree, configuration, index, viewer);
        if (listener == null)
            return;
        if (!Global.invokeVoid(listComponent, "addListener", listener)) //$NON-NLS-1$
            return;
        tree.setData(LIST_LISTENER_KEY, listener);
        tree.addDisposeListener(ev -> Global.invoke(listComponent, "removeListener", listener)); //$NON-NLS-1$
    }

    /**
     * Правый список после добавления/удаления опций перерисовывается, а выбор
     * слева тот же. После смены строки слева список тоже меняется — это отбор,
     * колонку не трогаем.
     */
    private static void watchFoListChanges(TreeViewer foListViewer, TreeViewer contentViewer,
        CountIndex index, Configuration configuration)
    {
        Tree foListTree = foListViewer != null ? foListViewer.getTree() : null;
        if (foListTree == null || foListTree.isDisposed() || contentViewer == null)
            return;
        if (Boolean.TRUE.equals(foListTree.getData(FO_LIST_WATCH_KEY)))
            return;
        foListTree.setData(FO_LIST_WATCH_KEY, Boolean.TRUE);
        index.lastLeftKey = leftSelectionKey(contentViewer);
        index.lastFoListSig = foListSignature(foListTree);
        foListTree.addListener(SWT.Paint, event ->
        {
            if (Boolean.TRUE.equals(index.watchPending))
                return;
            index.watchPending = Boolean.TRUE;
            foListTree.getDisplay().timerExec(200, () ->
            {
                index.watchPending = Boolean.FALSE;
                if (foListTree.isDisposed() || contentViewer.getTree().isDisposed())
                    return;
                Object leftKey = leftSelectionKey(contentViewer);
                String previousSig = index.lastFoListSig;
                String sig = foListSignature(foListTree);
                boolean sameLeft = Objects.equals(leftKey, index.lastLeftKey);
                boolean listChanged = !Objects.equals(sig, previousSig);
                index.lastLeftKey = leftKey;
                index.lastFoListSig = sig;
                if (!sameLeft || !listChanged)
                    return;
                Global.tempLog("fo-count", "list-watch rebuild left=" + leftKey + " sig=" + sig); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                selectFirstAdded(foListViewer, previousSig);
                requestRebuild(contentViewer.getTree(), configuration, index, contentViewer);
            });
        });
    }

    private static void selectFirstAdded(TreeViewer foListViewer, String previousSig)
    {
        if (foListViewer == null)
            return;
        Tree tree = foListViewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        Set<String> oldLabels = new HashSet<>();
        if (previousSig != null && !previousSig.isEmpty())
        {
            for (String line : previousSig.split("\n", -1)) //$NON-NLS-1$
                oldLabels.add(line);
        }
        Object first = firstNewElement(tree.getItems(), oldLabels);
        if (first == null)
            return;
        foListViewer.setSelection(new StructuredSelection(first), true);
        if (!tree.isDisposed())
            tree.setFocus();
    }

    private static Object firstNewElement(TreeItem[] items, Set<String> oldLabels)
    {
        if (items == null)
            return null;
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            String label = item.getText(0);
            if (!oldLabels.contains(label == null ? "" : label)) //$NON-NLS-1$
                return item.getData();
            Object nested = firstNewElement(item.getItems(), oldLabels);
            if (nested != null)
                return nested;
        }
        return null;
    }

    private static Object leftSelectionKey(TreeViewer viewer)
    {
        if (viewer == null)
            return null;
        var selection = viewer.getStructuredSelection();
        if (selection == null || selection.isEmpty())
            return null;
        EObject model = modelOf(viewer.getTree().getData(MAPPER_KEY), selection.getFirstElement());
        Long id = bmId(model);
        return id != null ? id : uriKey(model);
    }

    private static String foListSignature(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return ""; //$NON-NLS-1$
        StringBuilder text = new StringBuilder();
        appendTreeSignature(tree.getItems(), text);
        return text.toString();
    }

    private static void appendTreeSignature(TreeItem[] items, StringBuilder text)
    {
        if (items == null)
            return;
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            String label = item.getText(0);
            text.append(label == null ? "" : label).append('\n'); //$NON-NLS-1$
            appendTreeSignature(item.getItems(), text);
        }
    }

    private static Object foListEventListener(Object listComponent, Tree tree, Configuration configuration,
        CountIndex index, TreeViewer viewer)
    {
        Class<?> listenerClass = eventListenerClass(listComponent);
        if (listenerClass == null)
            return null;
        return Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[] { listenerClass },
            (proxy, method, args) ->
            {
                String name = method.getName();
                if ("eventReceived".equals(name) && args != null && args.length == 1) //$NON-NLS-1$
                {
                    Object event = args[0];
                    Global.tempLog("fo-count", "aef " //$NON-NLS-1$ //$NON-NLS-2$
                        + (event == null ? "null" : event.getClass().getName())); //$NON-NLS-1$
                    if (isFoContentChangeEvent(event)
                        || isFoListTreeRefresh(event, index, viewer))
                        requestRebuild(tree, configuration, index, viewer);
                }
                if ("hashCode".equals(name) && (args == null || args.length == 0)) //$NON-NLS-1$
                    return Integer.valueOf(System.identityHashCode(proxy));
                if ("equals".equals(name) && args != null && args.length == 1) //$NON-NLS-1$
                    return Boolean.valueOf(proxy == args[0]);
                if ("toString".equals(name) && (args == null || args.length == 0)) //$NON-NLS-1$
                    return LIST_LISTENER_KEY;
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class)
                    return Boolean.FALSE;
                if (returnType == int.class || returnType == long.class || returnType == short.class
                    || returnType == byte.class)
                    return Integer.valueOf(0);
                return null;
            });
    }

    private static Class<?> eventListenerClass(Object listComponent)
    {
        if (listComponent == null)
            return null;
        for (Class<?> current = listComponent.getClass(); current != null; current = current.getSuperclass())
        {
            for (java.lang.reflect.Method method : current.getDeclaredMethods())
            {
                if ("addListener".equals(method.getName()) && method.getParameterCount() == 1) //$NON-NLS-1$
                    return method.getParameterTypes()[0];
            }
        }
        if (Global.isLogEnabled())
            Global.log(TAG, "addListener не найден"); //$NON-NLS-1$
        return null;
    }

    private static boolean isFoListTreeRefresh(Object event, CountIndex index, TreeViewer contentViewer)
    {
        if (event == null)
            return false;
        String name = event.getClass().getName();
        if (name.contains("FunctionalOptionsSetFilterEvent")) //$NON-NLS-1$
        {
            index.lastLeftKey = leftSelectionKey(contentViewer);
            return false;
        }
        if (!name.endsWith(".TreeRefreshEvent")) //$NON-NLS-1$
            return false;
        Object leftKey = leftSelectionKey(contentViewer);
        boolean sameLeft = Objects.equals(leftKey, index.lastLeftKey);
        index.lastLeftKey = leftKey;
        return sameLeft && leftKey != null;
    }

    private static boolean isFoContentChangeEvent(Object event)
    {
        if (event == null || !BM_SERVICE_EVENT_CLASS.equals(event.getClass().getName()))
            return false;
        Object notifications = Global.invoke(event, "getNotifications"); //$NON-NLS-1$
        if (!(notifications instanceof Map<?, ?> map))
            return false;
        Object list = map.get(MdClassPackage.Literals.FUNCTIONAL_OPTION__CONTENT);
        return list instanceof List<?> items && !items.isEmpty();
    }

    private static void requestRebuild(Tree tree, Configuration configuration, CountIndex index,
        TreeViewer viewer)
    {
        int gen = index.debounceGen.incrementAndGet();
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(200, () ->
        {
            if (!tree.isDisposed() && gen == index.debounceGen.get())
                scheduleRebuild(index, configuration, viewer);
        });
    }

    private static void scheduleRebuild(CountIndex index, Configuration configuration, TreeViewer viewer)
    {
        Job previous = index.job;
        if (previous != null)
            previous.cancel();
        Job job = new Job("Комфорт: число функциональных опций") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                if (monitor.isCanceled())
                    return Status.CANCEL_STATUS;
                CountSnapshot snapshot = tryCompute(configuration);
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return Status.CANCEL_STATUS;
                display.asyncExec(() ->
                {
                    if (monitor.isCanceled() || viewer.getTree().isDisposed())
                        return;
                    CountSnapshot toApply = snapshot != null ? snapshot : tryCompute(configuration);
                    if (toApply == null)
                        return;
                    index.apply(toApply);
                    Tree tree = viewer.getTree();
                    Integer sum = sumDisplayedColumn(viewer);
                    if (sum != null)
                        tree.setData(SUM_KEY, sum);
                    viewer.refresh();
                    scheduleColumnLayout(tree);
                    if (tree.getData(EDITOR_KEY) instanceof DtGranularEditor<?> editor)
                        MdEditorListTabCountHook.refreshFunctionalOptionsTitle(editor);
                });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.setPriority(Job.DECORATE);
        index.job = job;
        job.schedule();
    }

    private static boolean isFunctionalOptionsPage(IFormPage page)
    {
        if (page == null)
            return false;
        String id = page.getId();
        if (FO_PAGE_ID.equals(id))
            return true;
        return page.getClass().getName().contains("FunctionalOptionsPage"); //$NON-NLS-1$
    }

    private static Configuration configurationOf(DtGranularEditor<?> editor)
    {
        EObject model = editor != null ? editor.getModel() : null;
        for (EObject current = model; current != null; current = current.eContainer())
        {
            if (current instanceof Configuration configuration)
                return configuration;
        }
        if (!(model instanceof MdObject mdObject))
            return null;
        IV8ProjectManager projectManager =
            (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
        if (projectManager == null)
            return null;
        IV8Project project = projectManager.getProject(mdObject);
        if (project instanceof IConfigurationProject configurationProject)
            return configurationProject.getConfiguration();
        Object configuration = Global.invoke(project, "getConfiguration"); //$NON-NLS-1$
        return configuration instanceof Configuration conf ? conf : null;
    }

    private static TreeViewer findContentTreeViewer(Object component, IFormPage page)
    {
        TreeViewer fromNative = findViewerViaNativeControls(component);
        if (fromNative != null)
            return fromNative;
        return findViewerViaPartControl(page, component);
    }

    private static TreeViewer findOtherTreeViewer(IFormPage page, TreeViewer contentViewer)
    {
        Control root = page != null ? page.getPartControl() : null;
        if (!(root instanceof Composite composite) || composite.isDisposed())
            return null;
        List<TreeViewer> viewers = new ArrayList<>();
        collectTreeViewers(composite, viewers, 0);
        for (TreeViewer candidate : viewers)
        {
            if (candidate != contentViewer)
                return candidate;
        }
        return null;
    }

    private static TreeViewer findViewerViaNativeControls(Object component)
    {
        Object scene = component != null ? Global.invoke(component, "getScene") : null; //$NON-NLS-1$
        for (Object nativeControl : AefFieldFocus.editorNativeControls(scene, component))
        {
            TreeViewer viewer = treeViewerOfNative(nativeControl);
            if (viewer != null)
                return viewer;
        }
        return null;
    }

    /**
     * Как {@code MdEditorSubsystemsExpandHook}: {@code DtTreeView} кладёт viewer в данные
     * контрола. На вкладке ФО два дерева — берём то, чей {@code getInput()} совпадает
     * с view-model компонента «Состав объекта».
     */
    private static TreeViewer findViewerViaPartControl(IFormPage page, Object component)
    {
        Control root = page != null ? page.getPartControl() : null;
        if (!(root instanceof Composite composite) || composite.isDisposed())
            return null;
        List<TreeViewer> viewers = new ArrayList<>();
        collectTreeViewers(composite, viewers, 0);
        Object wantedInput = treeViewModelInput(component);
        if (wantedInput != null)
        {
            for (TreeViewer viewer : viewers)
            {
                if (wantedInput == viewer.getInput() || wantedInput.equals(viewer.getInput()))
                    return viewer;
            }
        }
        return viewers.isEmpty() ? null : viewers.get(0);
    }

    private static Object treeViewModelInput(Object component)
    {
        Object viewModels = Global.invoke(component, "getViewModels"); //$NON-NLS-1$
        if (!(viewModels instanceof Iterable<?> iterable))
            return null;
        for (Object viewModel : iterable)
        {
            if (viewModel == null)
                continue;
            Object input = Global.invoke(viewModel, "getInput"); //$NON-NLS-1$
            if (input != null)
                return input;
        }
        return null;
    }

    private static void collectTreeViewers(Control control, List<TreeViewer> out, int depth)
    {
        if (control == null || control.isDisposed() || depth > 24)
            return;
        if (control.getData(DT_TREE_VIEWER_KEY) instanceof TreeViewer viewer && !out.contains(viewer))
            out.add(viewer);
        if (control instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
                collectTreeViewers(child, out, depth + 1);
        }
    }

    private static TreeViewer treeViewerOfNative(Object nativeControl)
    {
        Control control = nativeControl instanceof Control swt ? swt : null;
        if (control == null)
            control = Global.invoke(nativeControl, "getNativeControl") instanceof Control swt ? swt : null; //$NON-NLS-1$
        if (control == null || control.isDisposed())
            return null;
        for (Control current = control; current != null; current = current.getParent())
        {
            if (current.getData(DT_TREE_VIEWER_KEY) instanceof TreeViewer viewer)
                return viewer;
        }
        return control instanceof Composite composite ? findTreeViewerInData(composite, 0) : null;
    }

    private static TreeViewer findTreeViewerInData(Composite composite, int depth)
    {
        if (composite == null || composite.isDisposed() || depth > 20)
            return null;
        if (composite.getData(DT_TREE_VIEWER_KEY) instanceof TreeViewer viewer)
            return viewer;
        for (Control child : composite.getChildren())
        {
            if (child instanceof Composite childComposite)
            {
                TreeViewer found = findTreeViewerInData(childComposite, depth + 1);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static Object findComponentByClass(Object component, String className, int depth)
    {
        if (component == null || depth > 20)
            return null;
        if (className.equals(component.getClass().getName()))
            return component;
        for (Object child : AefFieldFocus.childComponents(component))
        {
            Object found = findComponentByClass(child, className, depth + 1);
            if (found != null)
                return found;
        }
        return null;
    }

    private static Long bmId(EObject object)
    {
        return object instanceof IBmObject bm ? Long.valueOf(bm.bmGetId()) : null;
    }

    private static String uriKey(EObject object)
    {
        try
        {
            return EcoreUtil.getURI(object).toString();
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "uriKey", e); //$NON-NLS-1$
            return null;
        }
    }

    private static final class CountColumnProvider extends StyledCellLabelProvider
    {
        private final Tree tree;

        private CountColumnProvider(Tree tree)
        {
            this.tree = tree;
        }

        @Override
        public void update(ViewerCell cell)
        {
            if (cell == null)
                return;
            Object element = cell.getElement();
            Integer count = countForElement(tree, element);
            cell.setText(count == null ? "" : Integer.toString(count)); //$NON-NLS-1$
            if (tree.isDisposed())
                return;
            if (count != null && count.intValue() == 0)
                cell.setForeground(tree.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
            else
                cell.setForeground(null);
        }
    }

    private static CountSnapshot tryCompute(Configuration configuration)
    {
        try
        {
            return CountSnapshot.compute(configuration);
        }
        catch (RuntimeException e)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, "счёт ФО: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static final class CountSnapshot
    {
        private final Map<Long, Integer> byBmId;

        private final Map<String, Integer> byUri;

        private CountSnapshot(Map<Long, Integer> byBmId, Map<String, Integer> byUri)
        {
            this.byBmId = byBmId;
            this.byUri = byUri;
        }

        static CountSnapshot compute(Configuration configuration)
        {
            Map<Long, Integer> byBmId = new HashMap<>();
            Map<String, Integer> byUri = new HashMap<>();
            if (configuration != null)
            {
                for (FunctionalOption option : configuration.getFunctionalOptions())
                {
                    if (option == null)
                        continue;
                    for (MdObject item : option.getContent())
                        bump(byBmId, byUri, item);
                }
            }
            return new CountSnapshot(Map.copyOf(byBmId), Map.copyOf(byUri));
        }

        private static void bump(Map<Long, Integer> byBmId, Map<String, Integer> byUri, MdObject item)
        {
            if (item == null)
                return;
            Long id = bmId(item);
            if (id != null)
                byBmId.merge(id, Integer.valueOf(1), Integer::sum);
            String uri = uriKey(item);
            if (uri != null)
                byUri.merge(uri, Integer.valueOf(1), Integer::sum);
        }
    }

    private static final class CountIndex
    {
        private volatile boolean ready;

        private Job job;

        private final AtomicInteger debounceGen = new AtomicInteger();

        private volatile Map<Long, Integer> byBmId = Map.of();

        private volatile Map<String, Integer> byUri = Map.of();

        private volatile Object lastLeftKey;

        private volatile String lastFoListSig;

        private volatile Boolean watchPending;

        void apply(CountSnapshot snapshot)
        {
            byBmId = snapshot.byBmId;
            byUri = snapshot.byUri;
            ready = true;
        }

        Integer countOf(EObject object)
        {
            if (!ready || !(object instanceof MdObject))
                return null;
            Long id = bmId(object);
            if (id != null)
            {
                Integer count = byBmId.get(id);
                if (count != null)
                    return count;
            }
            String uri = uriKey(object);
            if (uri != null)
            {
                Integer count = byUri.get(uri);
                if (count != null)
                    return count;
            }
            return Integer.valueOf(0);
        }
    }
}
