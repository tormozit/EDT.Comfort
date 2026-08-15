package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.expressions.EvaluationResult;
import org.eclipse.core.expressions.Expression;
import org.eclipse.core.expressions.ExpressionInfo;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.actions.TextActionHandler;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;

import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;

/**
 * Многословный фильтр в панели «Структура проекта»
 * ({@code org.eclipse.ui.navigator.ProjectExplorer}).
 *
 * <p>Штатного поля поиска у Project Explorer нет — над деревом вставляется
 * {@link FilterInputBox} ({@code SearchBox} с историей). Матчинг — иерархический
 * {@link SmartMatcher#matchesTreeParts}: точка в фильтре делит его на секции, они
 * сравниваются с конца пути (родитель.узел). Имя файла — одна секция, точка
 * расширения иерархию не режет. Текст узла —
 * имя ресурса и русское название папки-группы из {@link MdObjectUsageDecorator}.
 * Обезличенный суффикс {@code <объект>} (и {@code <?>}) в поиск не входит.
 * В дереве остаются совпавшие узлы и их родители.
 *
 * <p>Обход дерева — в фоновом {@link Job} (конфигурация 1С — тысячи файлов),
 * как в {@link GitStagingFilterHook}. Логирование: Параметры → Комфорт →
 * «Общее логирование».
 */
public final class ProjectStructureFilterHook implements IStartup
{
    static final String PROJECT_EXPLORER_ID = "org.eclipse.ui.navigator.ProjectExplorer"; //$NON-NLS-1$

    private static final String PATCHED_KEY = "tormozit.projectStructureFilterPatched"; //$NON-NLS-1$
    private static final String WRAPPER_KEY = "tormozit.projectStructureFilterWrapper"; //$NON-NLS-1$
    private static final String HIGHLIGHT_KEY = "tormozit.projectStructureHighlight"; //$NON-NLS-1$
    private static final String RAW_LABEL_PROVIDER_KEY = "tormozit.projectStructureRawLp"; //$NON-NLS-1$
    private static final String FILTER_ACTIVE_KEY = "tormozit.projectStructureFilterActive"; //$NON-NLS-1$
    private static final String MATCHER_KEY = "tormozit.projectStructureMatcher"; //$NON-NLS-1$
    private static final String CLIPBOARD_KEY = "tormozit.projectStructureFilterClipboard"; //$NON-NLS-1$

    private static final String OBJECT_SUFFIX = " <объект>"; //$NON-NLS-1$
    private static final String ORPHAN_SUFFIX = " <?>"; //$NON-NLS-1$

    private static final int MAX_AUTO_EXPAND = 200;
    private static final int MAX_PATCH_ATTEMPTS = 20;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            IWorkbench wb = PlatformUI.getWorkbench();
            probe("earlyStartup workbench=" + (wb != null)); //$NON-NLS-1$
            if (wb == null)
                return;
            Debug.log("earlyStartup: install window listener"); //$NON-NLS-1$
            for (IWorkbenchWindow window : wb.getWorkbenchWindows())
                hookWindow(window);
            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        StringBuilder refs = new StringBuilder();
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null)
                continue;
            for (IViewReference ref : page.getViewReferences())
            {
                if (refs.length() > 0)
                    refs.append(',');
                refs.append(ref.getId()).append(ref.getView(false) != null ? "+" : "-"); //$NON-NLS-1$ //$NON-NLS-2$
                IViewPart view = ref.getView(false);
                if (isProjectExplorer(view) || PROJECT_EXPLORER_ID.equals(ref.getId()) && view != null)
                    schedulePatch(view, 0);
            }
        }
        probe("hookWindow refs=" + refs); //$NON-NLS-1$
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r) {}
            @Override public void partDeactivated(IWorkbenchPartReference r) {}
            @Override public void partHidden(IWorkbenchPartReference r) {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}

            private void tryFromRef(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (isProjectExplorer(part))
                    schedulePatch((IViewPart) part, 0);
            }
        });
    }

    private static boolean isProjectExplorer(Object part)
    {
        if (!(part instanceof IViewPart view))
            return false;
        return PROJECT_EXPLORER_ID.equals(view.getViewSite().getId())
            || part instanceof CommonNavigator && PROJECT_EXPLORER_ID.equals(view.getSite().getId());
    }

    private static void schedulePatch(IViewPart view, int attempt)
    {
        boolean lists = ComfortSettings.isReplaceListFiltersEnabled();
        if (attempt == 0)
            probe("schedulePatch lists=" + lists //$NON-NLS-1$
                + " view=" + (view != null ? view.getClass().getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                + " id=" + (view != null ? view.getViewSite().getId() : "")); //$NON-NLS-1$ //$NON-NLS-2$
        if (!lists)
            return;
        Display display = Display.getDefault();
        int delay = attempt == 0 ? 0 : 100;
        display.timerExec(delay, () -> {
            if (!tryPatch(view, attempt) && attempt < MAX_PATCH_ATTEMPTS)
                schedulePatch(view, attempt + 1);
            else if (attempt >= MAX_PATCH_ATTEMPTS)
            {
                probe("tryPatch GIVE UP after " + MAX_PATCH_ATTEMPTS + " attempts"); //$NON-NLS-1$ //$NON-NLS-2$
                Debug.log("tryPatch GIVE UP after " + MAX_PATCH_ATTEMPTS + " attempts"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
    }

    private static boolean tryPatch(IViewPart view, int attempt)
    {
        try
        {
            CommonViewer viewer = getCommonViewer(view);
            if (viewer == null)
            {
                probe("tryPatch #" + attempt + " WAIT: viewer=null class=" + view.getClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$
                Debug.log("tryPatch #" + attempt + " WAIT: viewer=null"); //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
            Tree tree = viewer.getTree();
            if (tree == null || tree.isDisposed())
            {
                Debug.log("tryPatch #" + attempt + " WAIT: tree=null/disposed"); //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
            if (tree.getData(PATCHED_KEY) != null)
            {
                Debug.log("tryPatch #" + attempt + " SKIP: already patched"); //$NON-NLS-1$ //$NON-NLS-2$
                return true;
            }

            SearchFilter filter = new SearchFilter();
            filter.captureInitialExpandedElements(viewer);
            SmartLabelHighlight highlight = installHighlight(viewer, tree);
            if (highlight == null)
            {
                probe("tryPatch #" + attempt + " highlight=null lp=" //$NON-NLS-1$ //$NON-NLS-2$
                    + (viewer.getLabelProvider() != null
                        ? viewer.getLabelProvider().getClass().getName() : "null")); //$NON-NLS-1$
            }
            else
                tree.setData(HIGHLIGHT_KEY, highlight);

            FilterSession session = new FilterSession(viewer, filter);
            FilterInputBox box = insertFilterBox(tree, session::onSearch);
            if (box == null)
            {
                probe("tryPatch #" + attempt + " FAIL: filterBox=null"); //$NON-NLS-1$ //$NON-NLS-2$
                Debug.log("tryPatch #" + attempt + " FAIL: filterBox=null"); //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
            session.bindBox(box);
            viewer.addFilter(filter);

            SearchBox searchBox = box.widget();
            // Job SearchBox на каждый символ глотает последний ввод — живой Apply через Modify.
            searchBox.setRunSearchOnTextChange(false);
            Control filterInput = box.inputControl();
            if (filterInput instanceof Text text)
                text.addModifyListener(e -> session.onSearch());
            else if (filterInput instanceof StyledText styled)
                styled.addModifyListener(e -> session.onSearch());
            else
                searchBox.addModifyListener(e -> session.onSearch());
            probe("liveModify input=" + (filterInput != null ? filterInput.getClass().getSimpleName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$

            Control keys = filterInput != null ? filterInput : searchBox;
            if (keys != null && !keys.isDisposed())
                FilterInputBoxListNavigation.installTreeNavigation(keys, tree);

            box.widget().addListener(SWT.Traverse, e -> {
                if (e.detail == SWT.TRAVERSE_ESCAPE)
                {
                    box.setText(""); //$NON-NLS-1$
                    session.onSearch();
                    e.doit = false;
                }
            });
            box.widget().addListener(SWT.FocusOut, e -> {
                String text = box.getText();
                if (text == null)
                    text = ""; //$NON-NLS-1$
                if (text.equals(session.lastApplied))
                    return;
                Debug.log("focusOut forceApply field=[" + text + "] last=[" + session.lastApplied + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                session.onSearch();
            });
            tree.addDisposeListener(e -> session.cancelActiveJob());
            installEmptyPageGuard(tree);
            installDecorationHighlight(tree);
            installFilterClipboardOverride(view, viewer, tree, box);

            tree.setData(PATCHED_KEY, Boolean.TRUE);
            probe("tryPatch #" + attempt + " PATCH OK highlight=" + (highlight != null)); //$NON-NLS-1$ //$NON-NLS-2$
            Debug.log("tryPatch #" + attempt + " PATCH OK"); //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }
        catch (Exception e)
        {
            probe("tryPatch #" + attempt + " EXCEPTION: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            Debug.log("tryPatch #" + attempt + " EXCEPTION: " + e.getClass().getSimpleName() //$NON-NLS-1$ //$NON-NLS-2$
                + " " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    private static CommonViewer getCommonViewer(IViewPart view)
    {
        if (view instanceof CommonNavigator navigator)
            return navigator.getCommonViewer();
        Object v = Global.invoke(view, "getCommonViewer"); //$NON-NLS-1$
        return v instanceof CommonViewer cv ? cv : null;
    }

    /**
     * Родитель дерева в Project Explorer — {@code EmptyWorkspaceHelper.displayArea}
     * со {@link StackLayout} (дерево / заглушка «нет проектов»). SearchBox как сосед
     * дерева на стеке невидим. Вставляем поле над всем стеком, в родителе displayArea.
     */
    private static FilterInputBox insertFilterBox(Tree tree, Runnable onSearch)
    {
        Composite treeParent = tree.getParent();
        if (treeParent == null || treeParent.isDisposed())
        {
            probe("insertFilterBox treeParent=null"); //$NON-NLS-1$
            return null;
        }
        Composite host = filterHostOf(tree);
        Control keepVisible = stackedHostOf(tree);
        if (host == null || host.isDisposed())
        {
            probe("insertFilterBox host=null treeParent=" + layoutName(treeParent)); //$NON-NLS-1$
            return null;
        }
        probe("insertFilterBox host=" + host.getClass().getSimpleName() //$NON-NLS-1$
            + " hostLayout=" + layoutName(host) //$NON-NLS-1$
            + " treeParentLayout=" + layoutName(treeParent) //$NON-NLS-1$
            + " keep=" + (keepVisible != null ? keepVisible.getClass().getSimpleName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$

        if (Boolean.TRUE.equals(host.getData(WRAPPER_KEY)))
        {
            SearchBox existing = findSearchBoxChild(host);
            if (existing != null)
                return FilterInputBox.wrapExisting(existing, FilterInputBox.Scope.PROJECT_STRUCTURE, onSearch);
            return FilterInputBox.forProjectStructure(host, onSearch);
        }

        host.setRedraw(false);
        try
        {
            GridLayout gl = new GridLayout(1, false);
            gl.marginWidth = 0;
            gl.marginHeight = 0;
            gl.verticalSpacing = 2;
            host.setLayout(gl);

            FilterInputBox box = FilterInputBox.forProjectStructure(host, onSearch);
            if (keepVisible != null && !keepVisible.isDisposed())
            {
                Object oldData = keepVisible.getLayoutData();
                if (!(oldData instanceof GridData))
                    keepVisible.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
                box.widget().moveAbove(keepVisible);
            }
            else
            {
                tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
                box.widget().moveAbove(tree);
            }
            host.setData(WRAPPER_KEY, Boolean.TRUE);
            host.layout(true, true);
            return box;
        }
        finally
        {
            host.setRedraw(true);
        }
    }

    /** Композит, в который кладём SearchBox: над StackLayout, иначе родитель дерева. */
    private static Composite filterHostOf(Tree tree)
    {
        Composite stacked = stackedHostOf(tree);
        if (stacked != null)
            return stacked.getParent();
        return tree.getParent();
    }

    /** {@code EmptyWorkspaceHelper.displayArea}, если родитель дерева — стек. */
    private static Composite stackedHostOf(Tree tree)
    {
        Composite parent = tree.getParent();
        if (parent != null && parent.getLayout() instanceof StackLayout)
            return parent;
        return null;
    }

    /**
     * Пустой результат фильтра даёт {@code Tree} без элементов → SWT
     * {@code EmptinessChanged} → {@code EmptyWorkspaceHelper} показывает заглушку
     * «нет проектов». Пока фильтр активен, оставляем дерево поверх стека.
     */
    private static void installEmptyPageGuard(Tree tree)
    {
        tree.addListener(SWT.EmptinessChanged, e -> {
            if (Boolean.TRUE.equals(tree.getData(FILTER_ACTIVE_KEY)))
                showTreeNotEmptyPage(tree);
        });
    }

    private static void setFilterActive(Tree tree, boolean active)
    {
        if (tree == null || tree.isDisposed())
            return;
        tree.setData(FILTER_ACTIVE_KEY, Boolean.valueOf(active));
        if (active)
            showTreeNotEmptyPage(tree);
    }

    private static void showTreeNotEmptyPage(Tree tree)
    {
        Composite stacked = stackedHostOf(tree);
        if (stacked == null || stacked.isDisposed())
            return;
        if (!(stacked.getLayout() instanceof StackLayout layout))
            return;
        if (layout.topControl == tree)
            return;
        layout.topControl = tree;
        stacked.layout(true);
        probe("restored tree over empty-workspace page"); //$NON-NLS-1$
    }

    /**
     * Суффиксы декоратора ({@code <Справочники>}) рисуются после {@code getStyledText}.
     * Совпадения в них красим overlay поверх уже нарисованного текста; имя узла красит inject.
     */
    private static void installDecorationHighlight(Tree tree)
    {
        tree.addListener(SWT.PaintItem, ProjectStructureFilterHook::paintDecorationMatches);
    }

    private static void setHighlightMatcher(Tree tree, String pattern)
    {
        if (tree == null || tree.isDisposed())
            return;
        tree.setData(MATCHER_KEY, new SmartMatcher(pattern != null ? pattern : "")); //$NON-NLS-1$
        tree.redraw();
    }

    private static void paintDecorationMatches(Event e)
    {
        if (!(e.widget instanceof Tree tree) || !(e.item instanceof TreeItem item))
            return;
        Object stored = tree.getData(MATCHER_KEY);
        if (!(stored instanceof SmartMatcher matcher) || matcher.isEmpty)
            return;
        String text = item.getText();
        String searchable = stripDepersonalizedSuffix(text);
        if (searchable == null || searchable.isEmpty())
            return;
        int decoStart = searchable.indexOf(" <"); //$NON-NLS-1$
        if (decoStart < 0)
            return;
        List<SmartMatcher.HighlightRange> all = matcher.getHighlightRanges(searchable);
        if (all.isEmpty())
            return;
        List<SmartMatcher.HighlightRange> suffix = new ArrayList<>();
        for (SmartMatcher.HighlightRange range : all)
        {
            if (range.offset >= decoStart)
                suffix.add(range);
        }
        if (!suffix.isEmpty())
            SmartMatchHighlight.paintTreeItemMatchOverlay(e, tree, item, searchable, suffix);
    }

    /** Видимый текст без {@code <объект>}/{@code <?>} — эти суффиксы в подсветку не входят. */
    static String stripDepersonalizedSuffix(String text)
    {
        if (text == null || text.isEmpty())
            return text;
        int obj = text.indexOf(OBJECT_SUFFIX);
        int orphan = text.indexOf(ORPHAN_SUFFIX);
        int cut = -1;
        if (obj >= 0 && orphan >= 0)
            cut = Math.min(obj, orphan);
        else if (obj >= 0)
            cut = obj;
        else if (orphan >= 0)
            cut = orphan;
        return cut >= 0 ? text.substring(0, cut) : text;
    }

    private static String layoutName(Composite composite)
    {
        if (composite == null)
            return "null"; //$NON-NLS-1$
        Object layout = composite.getLayout();
        return layout != null ? layout.getClass().getSimpleName() : "none"; //$NON-NLS-1$
    }

    private static void probe(String text)
    {
        Global.tempLog("project-structure-filter", text); //$NON-NLS-1$
    }

    /**
     * Ctrl+C / Ctrl+V в {@link SearchBox} (это {@link StyledText}) не регистрируются в штатном
     * {@link TextActionHandler} Project Explorer — он умеет только {@link Text}. Поэтому fallback
     * уходит в вставку/копирование файла. Подменяем fallback {@code setPasteAction}/{@code setCopyAction}
     * и дублируем обработчик команды с выражением по фокусу.
     */
    private static void installFilterClipboardOverride(IViewPart view, CommonViewer viewer, Tree tree,
        FilterInputBox box)
    {
        if (view == null || view.getViewSite() == null || box == null || tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(CLIPBOARD_KEY)))
            return;
        boolean hooked = hookExplorerTextActionHandler(view, box);
        probe("clipboard tahHook=" + hooked); //$NON-NLS-1$
        viewer.addSelectionChangedListener(e -> hookExplorerTextActionHandler(view, box));
        SearchBox searchBox = box.widget();
        if (searchBox != null && !searchBox.isDisposed())
        {
            searchBox.addFocusListener(new FocusAdapter()
            {
                @Override
                public void focusGained(FocusEvent e)
                {
                    hookExplorerTextActionHandler(view, box);
                }
            });
        }
        IHandlerService handlers = view.getViewSite().getService(IHandlerService.class);
        if (handlers != null)
        {
            Expression whenFilter = new FilterFocusExpression(box);
            handlers.activateHandler(ActionFactory.COPY.getId(), new AbstractHandler()
            {
                @Override
                public Object execute(ExecutionEvent event)
                {
                    probeClipboard("handler COPY", box);
                    copyFromFilter(box);
                    return null;
                }
            }, whenFilter, false);
            handlers.activateHandler(ActionFactory.PASTE.getId(), new AbstractHandler()
            {
                @Override
                public Object execute(ExecutionEvent event)
                {
                    probeClipboard("handler PASTE", box);
                    pasteIntoFilter(box);
                    return null;
                }
            }, whenFilter, false);
        }
        ICommandService commands = view.getViewSite().getService(ICommandService.class);
        if (commands != null)
        {
            commands.addExecutionListener(new IExecutionListener()
            {
                @Override
                public void preExecute(String commandId, ExecutionEvent event)
                {
                    if (ActionFactory.COPY.getId().equals(commandId)
                        || ActionFactory.PASTE.getId().equals(commandId))
                        probeClipboard("cmd " + commandId, box);
                }

                @Override
                public void postExecuteSuccess(String commandId, Object returnValue) {}

                @Override
                public void notHandled(String commandId, NotHandledException exception) {}

                @Override
                public void postExecuteFailure(String commandId, ExecutionException exception) {}
            });
        }
        tree.setData(CLIPBOARD_KEY, Boolean.TRUE);
        probe("clipboard override installed"); //$NON-NLS-1$
    }

    private static boolean hookExplorerTextActionHandler(IViewPart view, FilterInputBox box)
    {
        Object editGroup = findEditActionGroup(view);
        if (editGroup == null)
            return false;
        Object handlerObj = Global.getField(editGroup, "textActionHandler"); //$NON-NLS-1$
        if (!(handlerObj instanceof TextActionHandler textHandler))
            return false;
        IAction resourceCopy = (IAction) Global.getField(editGroup, "copyAction"); //$NON-NLS-1$
        IAction resourcePaste = (IAction) Global.getField(editGroup, "pasteAction"); //$NON-NLS-1$
        IAction currentPaste = (IAction) Global.getField(textHandler, "pasteAction"); //$NON-NLS-1$
        IAction currentCopy = (IAction) Global.getField(textHandler, "copyAction"); //$NON-NLS-1$
        if (!(currentCopy instanceof FilterClipboardAction))
            textHandler.setCopyAction(new FilterClipboardAction(box, resourceCopy, false));
        if (!(currentPaste instanceof FilterClipboardAction))
            textHandler.setPasteAction(new FilterClipboardAction(box, resourcePaste, true));
        return true;
    }

    private static Object findEditActionGroup(IViewPart view)
    {
        if (!(view instanceof CommonNavigator navigator))
            return null;
        Object service = navigator.getNavigatorActionService();
        if (service == null)
            return null;
        Object group = findEditActionGroupInService(service);
        if (group != null)
            return group;
        IActionBars bars = view.getViewSite() != null ? view.getViewSite().getActionBars() : null;
        if (bars != null && service instanceof org.eclipse.ui.actions.ActionGroup actionGroup)
            actionGroup.fillActionBars(bars);
        return findEditActionGroupInService(service);
    }

    private static Object findEditActionGroupInService(Object service)
    {
        Object mapObj = Global.getField(service, "actionProviderInstances"); //$NON-NLS-1$
        if (!(mapObj instanceof Map<?, ?> map))
            return null;
        for (Object provider : map.values())
        {
            if (provider == null)
                continue;
            Object group = Global.getField(provider, "editGroup"); //$NON-NLS-1$
            if (group != null && group.getClass().getName().contains("EditActionGroup")) //$NON-NLS-1$
                return group;
        }
        return null;
    }

    private static void probeClipboard(String where, FilterInputBox box)
    {
        Display display = Display.getCurrent();
        Control focus = display != null ? display.getFocusControl() : null;
        probe(where + " inFilter=" + focusInFilter(box) //$NON-NLS-1$
            + " focus=" + (focus != null ? focus.getClass().getName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean focusInFilter(FilterInputBox box)
    {
        if (box == null || box.isDisposed())
            return false;
        if (box.isFocusControl())
            return true;
        Display display = Display.getCurrent();
        Control focus = display != null ? display.getFocusControl() : null;
        SearchBox widget = box.widget();
        if (widget == null || widget.isDisposed())
            return false;
        for (Control current = focus; current != null && !current.isDisposed(); current = current.getParent())
        {
            if (current == widget)
                return true;
        }
        return false;
    }

    private static void copyFromFilter(FilterInputBox box)
    {
        if (box == null || box.isDisposed())
            return;
        SearchBox widget = box.widget();
        if (widget != null && !widget.isDisposed())
            widget.copy();
    }

    private static void pasteIntoFilter(FilterInputBox box)
    {
        if (box == null || box.isDisposed())
            return;
        SearchBox widget = box.widget();
        if (widget != null && !widget.isDisposed())
            widget.paste();
    }

    private static SearchBox findSearchBoxChild(Composite parent)
    {
        for (Control child : parent.getChildren())
        {
            if (child instanceof SearchBox box)
                return box;
        }
        return null;
    }

    /**
     * Как {@link NavigatorFilterHook}: {@code NavigatorDecoratingLabelProvider} нельзя
     * подменять обёрткой на viewer — git-оверлеи и суффиксы рисует его {@code update()}.
     * Инжектируем {@link SmartOutlineLabelProvider} в {@link IStyledLabelProvider}.
     */
    private static SmartLabelHighlight installHighlight(CommonViewer viewer, Tree tree)
    {
        IBaseLabelProvider rawLp = viewer.getLabelProvider();
        if (!(rawLp instanceof DelegatingStyledCellLabelProvider delegating))
        {
            probe("installHighlight skip lp=" //$NON-NLS-1$
                + (rawLp != null ? rawLp.getClass().getName() : "null")); //$NON-NLS-1$
            return null;
        }
        IStyledLabelProvider inner = delegating.getStyledStringProvider();
        if (inner instanceof SmartOutlineLabelProvider existing)
            return existing;
        Object imageSource = unwrapImageSource(inner);
        SmartOutlineLabelProvider smartLp = new SmartOutlineLabelProvider(
            inner, null, null, imageSource, element -> matchText(toResource(element)));
        injectStyledStringProvider(delegating, smartLp);
        SmartMatchHighlight.enableColorsOnSelection(delegating);
        if (tree != null && !tree.isDisposed())
            tree.setData(RAW_LABEL_PROVIDER_KEY, rawLp);
        probe("installHighlight injected inner=" //$NON-NLS-1$
            + (inner != null ? inner.getClass().getName() : "null")); //$NON-NLS-1$
        return smartLp;
    }

    private static void ensureHighlight(CommonViewer viewer, String pattern)
    {
        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        IBaseLabelProvider raw = tree != null && !tree.isDisposed()
            ? (IBaseLabelProvider) tree.getData(RAW_LABEL_PROVIDER_KEY) : null;
        if (raw instanceof DelegatingStyledCellLabelProvider && viewer.getLabelProvider() != raw)
            viewer.setLabelProvider(raw);
        SmartLabelHighlight highlight = installHighlight(viewer, tree);
        if (highlight != null)
            highlight.setHighlightPattern(pattern != null ? pattern : ""); //$NON-NLS-1$
    }

    private static void injectStyledStringProvider(DelegatingStyledCellLabelProvider provider,
        IStyledLabelProvider smartProvider)
    {
        Class<?> cls = provider.getClass();
        while (cls != null)
        {
            for (java.lang.reflect.Field field : cls.getDeclaredFields())
            {
                if (IStyledLabelProvider.class.isAssignableFrom(field.getType()))
                {
                    try
                    {
                        field.setAccessible(true);
                        field.set(provider, smartProvider);
                        return;
                    }
                    catch (Exception ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        probe("injectStyledStringProvider: no IStyledLabelProvider field"); //$NON-NLS-1$
    }

    private static Object unwrapImageSource(Object source)
    {
        if (source == null)
            return null;
        if (source.getClass().getName().contains("StyledLabelProviderAdapter")) //$NON-NLS-1$
        {
            Object provider = Global.getField(source, "provider"); //$NON-NLS-1$
            if (provider != null)
                return provider;
        }
        return source;
    }

    static IResource toResource(Object element)
    {
        if (element instanceof IResource resource)
            return resource;
        return NavigatorResourceResolver.resolve(element);
    }

    /**
     * Текст секции пути: имя узла и RU-подпись папки-группы (как декоратор).
     * Пробел, не точка — чтобы английское и русское имя были одной секцией
     * {@link SmartMatcher#matchesTree}. {@code <объект>} / {@code <?>} не входят.
     */
    static String matchText(IResource resource)
    {
        if (resource == null)
            return ""; //$NON-NLS-1$
        String name = resource.getName();
        if (name == null)
            name = ""; //$NON-NLS-1$
        if (!(resource instanceof IFolder) && !(resource instanceof IProject))
            return name;
        String ru = MdTypeMapping.folderToGroupPlural(name);
        if (ru == null || ru.isEmpty() || ru.equalsIgnoreCase(name))
            return name;
        return name + " " + ru; //$NON-NLS-1$
    }

    /** Имена узлов от корня к ресурсу: каждый файл/папка — одна секция, точка в имени не режет путь. */
    static List<String> pathParts(IResource resource)
    {
        List<String> parts = new ArrayList<>();
        if (resource == null)
            return parts;
        IResource walk = resource;
        while (walk != null && !(walk instanceof IWorkspaceRoot))
        {
            String part = matchText(walk);
            if (!part.isEmpty())
                parts.add(part);
            walk = walk.getParent();
        }
        java.util.Collections.reverse(parts);
        return parts;
    }

    // -----------------------------------------------------------------------
    // Сессия фильтрации
    // -----------------------------------------------------------------------

    private static final class FilterSession
    {
        private final CommonViewer viewer;
        private final SearchFilter filter;
        private FilterInputBox box;
        private volatile int activeGeneration;
        private volatile Job activeJob;
        private String lastApplied = ""; //$NON-NLS-1$

        FilterSession(CommonViewer viewer, SearchFilter filter)
        {
            this.viewer = viewer;
            this.filter = filter;
        }

        void bindBox(FilterInputBox box)
        {
            this.box = box;
        }

        void onSearch()
        {
            Display display = Display.getDefault();
            if (display.getThread() != Thread.currentThread())
            {
                display.asyncExec(this::onSearch);
                return;
            }
            if (box == null || box.isDisposed())
                return;
            String text = box.getText();
            if (text == null)
                text = ""; //$NON-NLS-1$
            probe("onSearch text=[" + text + "] last=[" + lastApplied + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Debug.log("onSearch text=\"" + text + "\""); //$NON-NLS-1$ //$NON-NLS-2$
            if (text.equals(lastApplied))
                return;
            if (text.isEmpty())
            {
                activeGeneration++;
                cancelActiveJob();
                applyEmpty();
                return;
            }
            int generation = ++activeGeneration;
            cancelActiveJob();
            startJob(generation, text);
        }

        void cancelActiveJob()
        {
            Job job = activeJob;
            activeJob = null;
            if (job != null)
                job.cancel();
        }

        private void applyEmpty()
        {
            lastApplied = ""; //$NON-NLS-1$
            filter.setPattern(""); //$NON-NLS-1$
            ensureFilterAttached();
            Tree tree = viewer.getTree();
            if (tree == null || tree.isDisposed())
                return;
            setFilterActive(tree, false);
            setHighlightMatcher(tree, ""); //$NON-NLS-1$
            IStructuredSelection saved = viewer.getSelection() instanceof IStructuredSelection sel
                ? sel : null;
            tree.setRedraw(false);
            try
            {
                viewer.refresh();
                ensureHighlight(viewer, ""); //$NON-NLS-1$
                filter.restoreInitialExpandedElements(viewer);
                if (saved != null && !saved.isEmpty())
                    viewer.setSelection(saved, true);
            }
            finally
            {
                tree.setRedraw(true);
            }
        }

        private void startJob(int generation, String text)
        {
            Object input = viewer.getInput();
            Job job = new Job("Фильтрация структуры проекта...") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    long t0 = System.currentTimeMillis();
                    List<IResource> roots = collectRoots(input);
                    if (roots.isEmpty())
                    {
                        IWorkspace workspace = ResourcesPlugin.getWorkspace();
                        if (workspace != null)
                            roots.add(workspace.getRoot());
                    }
                    probe("job START generation=" + generation //$NON-NLS-1$
                        + " input=" + (input != null ? input.getClass().getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                        + " roots=" + roots.size()); //$NON-NLS-1$
                    Debug.log("job START generation=" + generation); //$NON-NLS-1$
                    try
                    {
                        SmartMatcher matcher = new SmartMatcher(text);
                        Map<IPath, Boolean> results = new HashMap<>();
                        List<IResource> matchedLeaves = new ArrayList<>();
                        int[] visited = { 0 };
                        for (IResource root : roots)
                            computeMatches(root, matcher, List.of(), results, matchedLeaves, visited, generation,
                                () -> activeGeneration, monitor);

                        int trueCount = 0;
                        for (Boolean value : results.values())
                        {
                            if (Boolean.TRUE.equals(value))
                                trueCount++;
                        }
                        long tComputed = System.currentTimeMillis();
                        probe("job COMPUTED generation=" + generation + " visited=" + visited[0] //$NON-NLS-1$ //$NON-NLS-2$
                            + " map=" + results.size() + " true=" + trueCount //$NON-NLS-1$ //$NON-NLS-2$
                            + " leaves=" + matchedLeaves.size() //$NON-NLS-1$
                            + " computeMs=" + (tComputed - t0)); //$NON-NLS-1$
                        Debug.log("job COMPUTED generation=" + generation + " visited=" + visited[0] //$NON-NLS-1$ //$NON-NLS-2$
                            + " leaves=" + matchedLeaves.size() //$NON-NLS-1$
                            + " computeMs=" + (tComputed - t0)); //$NON-NLS-1$

                        Display.getDefault().asyncExec(() -> {
                            if (generation != activeGeneration || box == null || box.isDisposed())
                                return;
                            applyPrecomputed(text, results, matchedLeaves);
                        });
                        return Status.OK_STATUS;
                    }
                    catch (FilterCancelledException cancelled)
                    {
                        Debug.log("job CANCELLED generation=" + generation //$NON-NLS-1$
                            + " afterMs=" + (System.currentTimeMillis() - t0)); //$NON-NLS-1$
                        return Status.CANCEL_STATUS;
                    }
                }
            };
            activeJob = job;
            job.setSystem(true);
            job.schedule();
        }

        private void applyPrecomputed(String text, Map<IPath, Boolean> results, List<IResource> matchedLeaves)
        {
            Tree tree = viewer.getTree();
            if (tree == null || tree.isDisposed())
                return;
            lastApplied = text != null ? text : ""; //$NON-NLS-1$
            filter.installPrecomputed(text, results);
            ensureFilterAttached();
            setFilterActive(tree, true);
            setHighlightMatcher(tree, lastApplied);
            tree.setRedraw(false);
            try
            {
                viewer.refresh();
                ensureHighlight(viewer, lastApplied);
                expandMatches(matchedLeaves);
            }
            finally
            {
                tree.setRedraw(true);
            }
            showTreeNotEmptyPage(tree);
            IBaseLabelProvider lp = viewer.getLabelProvider();
            probe("applyPrecomputed leaves=" + matchedLeaves.size() //$NON-NLS-1$
                + " filters=" + viewer.getFilters().length //$NON-NLS-1$
                + " lp=" + (lp != null ? lp.getClass().getSimpleName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
            Debug.log("applyPrecomputed leaves=" + matchedLeaves.size()); //$NON-NLS-1$
        }

        private void ensureFilterAttached()
        {
            for (ViewerFilter existing : viewer.getFilters())
            {
                if (existing == filter)
                    return;
            }
            viewer.addFilter(filter);
            probe("ensureFilter re-added"); //$NON-NLS-1$
        }

        private void expandMatches(List<IResource> matchedLeaves)
        {
            Set<Object> toExpand = new LinkedHashSet<>();
            int added = 0;
            for (IResource leaf : matchedLeaves)
            {
                if (added >= MAX_AUTO_EXPAND)
                    break;
                IContainer parent = leaf.getParent();
                while (parent != null && !(parent instanceof IWorkspaceRoot))
                {
                    toExpand.add(parent);
                    parent = parent.getParent();
                }
                added++;
            }
            if (!toExpand.isEmpty())
                viewer.setExpandedElements(toExpand.toArray());
        }
    }

    private static List<IResource> collectRoots(Object input)
    {
        List<IResource> roots = new ArrayList<>();
        collectRootsInto(input, roots);
        return roots;
    }

    private static void collectRootsInto(Object input, List<IResource> roots)
    {
        if (input instanceof IResource resource)
            roots.add(resource);
        else if (input instanceof IWorkspace workspace)
            roots.add(workspace.getRoot());
        else if (input instanceof Object[] arr)
        {
            for (Object item : arr)
                collectRootsInto(item, roots);
        }
    }

    private static void computeMatches(IResource resource, SmartMatcher matcher, List<String> parentParts,
        Map<IPath, Boolean> results, List<IResource> matchedLeaves, int[] visited, int generation,
        IntSupplier currentGeneration, IProgressMonitor monitor)
    {
        if ((++visited[0] & 0x1FF) == 0
            && (monitor.isCanceled() || generation != currentGeneration.getAsInt()))
            throw CANCELLED;

        String selfText = matchText(resource);
        List<String> parts = parentParts;
        if (!selfText.isEmpty())
        {
            parts = new ArrayList<>(parentParts.size() + 1);
            parts.addAll(parentParts);
            parts.add(selfText);
        }
        boolean self = matcher.matchesTreeParts(parts);
        boolean any = self;
        if (resource instanceof IContainer container && container.isAccessible())
        {
            IResource[] members;
            try
            {
                members = container.members();
            }
            catch (CoreException ex)
            {
                members = new IResource[0];
            }
            for (IResource child : members)
            {
                computeMatches(child, matcher, parts, results, matchedLeaves, visited, generation,
                    currentGeneration, monitor);
                if (Boolean.TRUE.equals(results.get(child.getFullPath())))
                    any = true;
            }
        }
        results.put(resource.getFullPath(), Boolean.valueOf(any));
        if (self)
            matchedLeaves.add(resource);
    }

    // -----------------------------------------------------------------------
    // ViewerFilter
    // -----------------------------------------------------------------------

    private static final class SearchFilter extends ViewerFilter
    {
        private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$
        private volatile Map<IPath, Boolean> precomputedMatches;
        private Object[] initialExpandedElements = new Object[0];

        void setPattern(String pattern)
        {
            matcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
            precomputedMatches = null;
        }

        void installPrecomputed(String pattern, Map<IPath, Boolean> results)
        {
            matcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
            precomputedMatches = results;
        }

        void captureInitialExpandedElements(TreeViewer viewer)
        {
            Object[] current = viewer.getExpandedElements();
            initialExpandedElements = current != null ? current : new Object[0];
        }

        void restoreInitialExpandedElements(TreeViewer viewer)
        {
            viewer.setExpandedElements(initialExpandedElements);
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            if (matcher.isEmpty)
                return true;
            IResource resource = toResource(element);
            if (resource == null)
                return true;
            Map<IPath, Boolean> precomputed = precomputedMatches;
            if (precomputed != null)
            {
                Boolean cached = precomputed.get(resource.getFullPath());
                if (cached != null)
                    return cached.booleanValue();
            }
            // Узла нет в снимке (появился после обхода) — не прячем, без рекурсии по UI-потоку.
            return matcher.matchesTreeParts(pathParts(resource));
        }
    }

    private static final class FilterFocusExpression extends Expression
    {
        private final FilterInputBox box;

        FilterFocusExpression(FilterInputBox box)
        {
            this.box = box;
        }

        @Override
        public EvaluationResult evaluate(IEvaluationContext context)
        {
            return focusInFilter(box) ? EvaluationResult.TRUE : EvaluationResult.FALSE;
        }

        @Override
        public void collectExpressionInfo(ExpressionInfo info)
        {
            info.addVariableNameAccess(ISources.ACTIVE_FOCUS_CONTROL_NAME);
        }
    }

    private static final class FilterClipboardAction extends Action
    {
        private final FilterInputBox box;
        private final IAction original;
        private final boolean paste;

        FilterClipboardAction(FilterInputBox box, IAction original, boolean paste)
        {
            this.box = box;
            this.original = original;
            this.paste = paste;
        }

        @Override
        public void run()
        {
            probeClipboard(paste ? "tah PASTE" : "tah COPY", box); //$NON-NLS-1$ //$NON-NLS-2$
            if (focusInFilter(box))
            {
                if (paste)
                    pasteIntoFilter(box);
                else
                    copyFromFilter(box);
                return;
            }
            if (original != null)
                original.run();
        }

        @Override
        public void runWithEvent(org.eclipse.swt.widgets.Event event)
        {
            probeClipboard((paste ? "tah PASTE" : "tah COPY") + " event", box); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (focusInFilter(box))
            {
                if (paste)
                    pasteIntoFilter(box);
                else
                    copyFromFilter(box);
                return;
            }
            if (original != null)
                original.runWithEvent(event);
        }
    }

    private static final FilterCancelledException CANCELLED = new FilterCancelledException();

    private static final class FilterCancelledException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        FilterCancelledException()
        {
            super(null, null, false, false);
        }
    }

    private static final class Debug
    {
        private static final String TAG = "ProjectStructureFilter"; //$NON-NLS-1$

        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
