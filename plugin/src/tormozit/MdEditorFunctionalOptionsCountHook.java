package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.notify.Adapter;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.dialogs.PageChangedEvent;
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
 * на вкладке «Функц. опции». Считается в фоне; ноль тоже показывается.
 */
public final class MdEditorFunctionalOptionsCountHook implements IStartup
{
    private static final String TAG = "MdEditorFoCount"; //$NON-NLS-1$

    private static final String FO_PAGE_ID = "editors.pages.functionalOptions"; //$NON-NLS-1$

    private static final String FO_CONTENT_COMPONENT_CLASS =
        "com._1c.g5.v8.dt.internal.md.ui.editors.pages.functionaloptions.DtGranularEditorFunctionalOptionsMdObjectContentComponent"; //$NON-NLS-1$

    private static final String DT_TREE_VIEWER_KEY =
        "com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView.treeViewer"; //$NON-NLS-1$

    private static final String HOOK_MARKER = "tormozit.foContentCountHooked"; //$NON-NLS-1$

    private static final String INDEX_KEY = "tormozit.foContentCountIndex"; //$NON-NLS-1$

    private static final String MAPPER_KEY = "tormozit.foContentCountMapper"; //$NON-NLS-1$

    private static final String ADAPTER_KEY = "tormozit.foContentCountAdapter"; //$NON-NLS-1$

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
        TreeViewer viewer = component != null ? findContentTreeViewer(component, page) : null;
        Tree tree = viewer != null ? viewer.getTree() : null;
        if (component == null || viewer == null || tree == null || tree.isDisposed())
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
        attachModelListener(tree, configuration, index, viewer);
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
        return index.countOf(modelOf(tree.getData(MAPPER_KEY), element));
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

    private static void attachModelListener(Tree tree, Configuration configuration, CountIndex index,
        TreeViewer viewer)
    {
        if (tree == null || tree.isDisposed() || configuration == null)
            return;
        Object existing = tree.getData(ADAPTER_KEY);
        if (existing instanceof Adapter adapter && configuration.eAdapters().contains(adapter))
            return;
        Adapter adapter = new EContentAdapter()
        {
            @Override
            public void notifyChanged(Notification notification)
            {
                super.notifyChanged(notification);
                if (notification == null || notification.isTouch())
                    return;
                Object feature = notification.getFeature();
                if (feature != MdClassPackage.Literals.FUNCTIONAL_OPTION__CONTENT
                    && feature != MdClassPackage.Literals.CONFIGURATION__FUNCTIONAL_OPTIONS)
                    return;
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
        };
        configuration.eAdapters().add(adapter);
        tree.setData(ADAPTER_KEY, adapter);
        tree.addDisposeListener(ev ->
        {
            if (configuration.eAdapters().contains(adapter))
                configuration.eAdapters().remove(adapter);
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
                    viewer.refresh();
                    scheduleColumnLayout(viewer.getTree());
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
            if (count != null && !tree.isDisposed())
                cell.setForeground(tree.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
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
