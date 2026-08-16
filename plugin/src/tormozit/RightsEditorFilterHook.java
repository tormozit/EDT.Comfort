package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.IColorProvider;
import org.eclipse.jface.viewers.IFontProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.IToolTipProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.OwnerDrawLabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.forms.IManagedForm;
import org.eclipse.ui.forms.editor.IFormPage;

import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;

/**
 * Иерархический фильтр ({@link SmartMatcher#matchesTree}) с раскраской вхождений
 * в поле поиска страницы «Права» ({@code RightsEditorRightsPage} /
 * {@code ObjectsSection}) и окна «Все роли» ({@code AllRolesEditor} с тем же
 * {@code ObjectsSection}).
 *
 * <p>Штатный {@code SearchBox} остаётся; меняются слушатель, {@link ViewerFilter}
 * и подсветка колонок «Объект»/«Роль». История — {@link FilterInputBox#attachHistory}
 * / {@link FilterInputBox.Scope#RIGHTS_EDITOR} (режим «Нижний» —
 * {@link FilterInputBox.Scope#RIGHTS_EDITOR_LEAVES}). Порог длины запроса 0.
 *
 * <p>Без флажка «Нижний» — как штатный {@code SearchFilterWithHistory} через
 * {@code ItemFilter.filter}: матч только для <b>верхних</b> строк дерева. Дочерние
 * узлы всегда видимы, если виден родитель. Папка верхнего уровня видна, если
 * совпала сама или совпал прямой потомок.
 *
 * <p>С флажком «Нижний» фильтр отбирает <b>листовые</b> объекты (не папки-коллекции);
 * родители совпадений остаются видимыми. Пометки у нелистовых узлов в этом режиме
 * недоступны. Во вкладке «Права» редактора объекта метаданных флажок включён и
 * недоступен; в редакторе роли и в окне «Все роли» его можно снять. Штатная
 * команда «Фильтровать по выбранному значению» доступна и для дочерних строк:
 * включает «Нижний» и подставляет имя в поиск; для верхних строк флажок
 * «Нижний» снимается. Флажок «Полный» — полное совпадение сегментов;
 * включается при подстановке точной ссылки на реквизит. Ctrl+C копирует текст
 * активной ячейки дерева.
 */
public final class RightsEditorFilterHook implements IStartup
{
    private static final String TAG = "RightsEditorFilter"; //$NON-NLS-1$

    private static final String PAGE_CLASS_SUFFIX = "RightsEditorRightsPage"; //$NON-NLS-1$

    private static final String PAGE_ID_SUFFIX = ".editors.page.rights"; //$NON-NLS-1$

    private static final String ALL_ROLES_EDITOR_SUFFIX = "AllRolesEditor"; //$NON-NLS-1$

    private static final String PATCHED_KEY = "tormozit.rightsEditorFilterPatched"; //$NON-NLS-1$

    private static final String HIGHLIGHT_KEY = "tormozit.rightsEditorHighlights"; //$NON-NLS-1$

    private static final String FILTER_KEY = "tormozit.rightsEditorFilter"; //$NON-NLS-1$

    private static final String APPLY_GEN_KEY = "tormozit.rightsEditorApplyGen"; //$NON-NLS-1$

    private static final String LEAVES_CHECK_KEY = "tormozit.rightsEditorLeavesCheck"; //$NON-NLS-1$

    private static final String EXACT_CHECK_KEY = "tormozit.rightsEditorExactCheck"; //$NON-NLS-1$

    private static final String VIEWER_KEY = "tormozit.rightsEditorViewer"; //$NON-NLS-1$

    private static final String FILTER_ROW_KEY = "tormozit.rightsEditorFilterRow"; //$NON-NLS-1$

    private static final String PREF_LEAVES_MODE = "comfort.rightsEditor.filter.leaves"; //$NON-NLS-1$

    private static final String PREF_EXACT_MODE = "comfort.rightsEditor.filter.exact"; //$NON-NLS-1$

    private static final String EDIT_WRAP_HOOK_KEY = "tormozit.rightsEditorEditWrap"; //$NON-NLS-1$

    private static final String COPY_HOOKED_KEY = "tormozit.rightsEditorCopyHooked"; //$NON-NLS-1$

    private static final String COPY_ACTIVE_COLUMN_KEY = "tormozit.rightsEditorCopyColumn"; //$NON-NLS-1$

    private static final String FILTER_BY_VALUE_HOOK_KEY = "tormozit.rightsEditorFilterByValue"; //$NON-NLS-1$

    private static final String FILTER_BY_VALUE_MENU_KEY = "tormozit.rightsEditorFilterByValueMenu"; //$NON-NLS-1$

    private static final String COLLAPSE_OTHERS_MENU_KEY = "tormozit.rightsEditorCollapseOthersMenu"; //$NON-NLS-1$

    private static final String FILTER_BY_VALUE_ACTION_KEY = "tormozit.rightsEditorFilterByValueAction"; //$NON-NLS-1$

    private static final String FILTER_BY_VALUE_COMMAND_ID =
        "com._1c.g5.v8.dt.rights.ui.filterByCurrentValue"; //$NON-NLS-1$

    private static final String COLUMN_VIEWER_KEY = "org.eclipse.jface.columnViewer"; //$NON-NLS-1$

    private static boolean filterByValueListenerInstalled;

    private static final String LEAVES_CHECK_LABEL = "Нижний"; //$NON-NLS-1$

    private static final String LEAVES_CHECK_TOOLTIP =
        "Отбирать нижние узлы. Недоступно редактирование родителей"; //$NON-NLS-1$

    private static final String EXACT_CHECK_LABEL = "Полный"; //$NON-NLS-1$

    private static final String EXACT_CHECK_TOOLTIP =
        "Полное совпадение каждого сегмента. «Реквизит.Банк» не отберёт БанкПолучателя"; //$NON-NLS-1$

    private static final int MAX_ATTEMPTS = 40;

    private static final int RETRY_MS = 100;

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
            IWorkbench workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)       { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)    {}
                @Override public void windowDeactivated(IWorkbenchWindow w)  {}
                @Override public void windowClosed(IWorkbenchWindow w)       {}
            });
            for (IWorkbenchWindow w : workbench.getWorkbenchWindows())
                hookWindow(w);
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
                hookEditor(ref.getEditor(false));
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)     { hookFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref)  { hookFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) { hookFromRef(r); }
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      { hookFromRef(r); }
            @Override public void partInputChanged(IWorkbenchPartReference r) {}

            private void hookFromRef(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference editorRef))
                    return;
                IWorkbenchPart part = editorRef.getPart(false);
                if (part instanceof IEditorPart editorPart)
                    hookEditor(editorPart);
            }
        });
    }

    private static void hookEditor(Object editor)
    {
        if (editor instanceof DtGranularEditor<?> granular)
        {
            if (HOOKED_EDITORS.add(granular))
                granular.addPageChangedListener(event -> schedulePatch(granular, 0));
            schedulePatch(granular, 0);
            return;
        }
        if (isAllRolesEditor(editor))
            schedulePatchAllRoles(editor, 0);
    }

    static boolean isAllRolesEditor(Object editor)
    {
        return editor != null && editor.getClass().getName().endsWith(ALL_ROLES_EDITOR_SUFFIX);
    }

    private static void schedulePatch(DtGranularEditor<?> editor, int attempt)
    {
        if (editor == null || attempt >= MAX_ATTEMPTS)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Runnable task = () ->
        {
            if (tryPatchEditor(editor))
                return;
            schedulePatch(editor, attempt + 1);
        };
        if (attempt == 0)
            display.asyncExec(task);
        else
            display.timerExec(RETRY_MS, task);
    }

    /** @return {@code true}, если делать больше нечего (пропатчено, отключено или страницы прав нет). */
    private static boolean tryPatchEditor(DtGranularEditor<?> editor)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return true;
        try
        {
            IFormPage active = editor.getActivePageInstance();
            if (isRightsPage(active) && tryPatchPage(active))
                return true;

            Object pagesObj = Global.getField(editor, "pages"); //$NON-NLS-1$
            if (!(pagesObj instanceof List<?> pages) || pages.isEmpty())
                return false;
            boolean sawRights = false;
            boolean allReady = true;
            for (Object pageObj : pages)
            {
                if (!(pageObj instanceof IFormPage page) || !isRightsPage(page))
                    continue;
                sawRights = true;
                if (!tryPatchPage(page))
                    allReady = false;
            }
            return sawRights && allReady;
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "tryPatchEditor", e); //$NON-NLS-1$
            return true;
        }
    }

    private static void schedulePatchAllRoles(Object editor, int attempt)
    {
        if (editor == null || attempt >= MAX_ATTEMPTS)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Runnable task = () ->
        {
            if (tryPatchAllRoles(editor))
                return;
            schedulePatchAllRoles(editor, attempt + 1);
        };
        if (attempt == 0)
            display.asyncExec(task);
        else
            display.timerExec(RETRY_MS, task);
    }

    /** @return {@code true}, если делать больше нечего (пропатчено или секции ещё нет). */
    private static boolean tryPatchAllRoles(Object editor)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return true;
        try
        {
            return tryPatchSection(Global.getField(editor, "objectsSection")); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "tryPatchAllRoles", e); //$NON-NLS-1$
            return true;
        }
    }

    private static boolean isRightsPage(IFormPage page)
    {
        if (page == null)
            return false;
        String id = page.getId();
        if (id != null && id.endsWith(PAGE_ID_SUFFIX))
            return true;
        return page.getClass().getName().endsWith(PAGE_CLASS_SUFFIX);
    }

    /**
     * Подставляет текст в поле поиска страницы «Права» или окна «Все роли»
     * и запускает фильтр. {@code owner} — {@link IFormPage} или {@code AllRolesEditor}.
     *
     * @return {@code true}, если поле найдено и текст записан
     */
    static boolean applyFilterText(Object owner, String text)
    {
        if (owner == null || text == null)
            return false;
        Object section = Global.getField(owner, "objectsSection"); //$NON-NLS-1$
        if (!tryPatchSection(section))
            return false;
        Object searchObj = section != null ? Global.getField(section, "searchBox") : null; //$NON-NLS-1$
        if (!(searchObj instanceof SearchBox searchBox) || searchBox.isDisposed())
            return false;
        if (searchBox.getData(FILTER_KEY) instanceof RightsSmartFilter filter
            && searchBox.getData(VIEWER_KEY) instanceof TreeViewer viewer)
            applyLeafMode(searchBox, viewer, filter, true, isMdObjectRightsEditor(section));
        applyExactReference(searchBox);
        searchBox.setText(text);
        rememberAppliedFilter(section, text);
        Global.invoke(searchBox, "performSearch"); //$NON-NLS-1$
        Display display = searchBox.getDisplay();
        if (display != null && !display.isDisposed())
        {
            display.asyncExec(() ->
            {
                if (searchBox.isDisposed())
                    return;
                int end = searchBox.getCharCount();
                searchBox.setCaretOffset(end);
                searchBox.setSelection(end, end);
                searchBox.setFocus();
            });
        }
        return true;
    }

    private static void rememberAppliedFilter(Object section, String text)
    {
        Object viewerObj = section != null ? Global.getField(section, "viewer") : null; //$NON-NLS-1$
        Tree tree = viewerObj instanceof TreeViewer viewer ? viewer.getTree() : null;
        Object filterObj = tree != null && !tree.isDisposed() ? tree.getData(FILTER_KEY) : null;
        boolean leaves = filterObj instanceof RightsSmartFilter filter
            ? filter.isLeafMode()
            : isMdObjectRightsEditor(section);
        FilterInputBox.remember(historyScope(leaves), text);
    }

    /** @return {@code true}, если страница пропатчена или её ещё нет смысла ждать. */
    private static boolean tryPatchPage(IFormPage page)
    {
        if (page == null)
            return false;
        return tryPatchSection(Global.getField(page, "objectsSection")); //$NON-NLS-1$
    }

    /** @return {@code true}, если секция пропатчена; {@code false} — контролы ещё не готовы. */
    private static boolean tryPatchSection(Object section)
    {
        if (section == null)
            return false;
        Object searchBoxObj = Global.getField(section, "searchBox"); //$NON-NLS-1$
        Object viewerObj = Global.getField(section, "viewer"); //$NON-NLS-1$
        if (!(searchBoxObj instanceof SearchBox searchBox) || searchBox.isDisposed())
            return false;
        if (!(viewerObj instanceof TreeViewer viewer))
            return false;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return false;

        installTreeCopy(tree);
        TreeExpander.installWhitelisted(TreeExpander.Target.RIGHTS_EDITOR, viewer);

        if (!Boolean.TRUE.equals(tree.getData(PATCHED_KEY)))
        {
            List<ColumnHighlight> highlights = installHighlights(viewer);
            if (highlights.isEmpty())
                return false;
            tree.setData(HIGHLIGHT_KEY, highlights);

            RightsSmartFilter filter = new RightsSmartFilter();
            boolean lockedLeaves = isMdObjectRightsEditor(section);
            boolean leaves = lockedLeaves || isLeavesModePref();
            filter.setLeafMode(leaves);
            filter.setExactSegments(isExactModePref());
            tree.setData(FILTER_KEY, filter);
            searchBox.setData(FILTER_KEY, filter);
            searchBox.setData(VIEWER_KEY, viewer);
            searchBox.setData(HIGHLIGHT_KEY, highlights);

            searchBox.setToolTipText(FilterInputBox.HIERARCHICAL_FILTER_TOOLTIP
                    + "\nCtrl+↓ — история запросов."); //$NON-NLS-1$
            searchBox.setMinimumSearchTextLength(0);
            searchBox.setJobScheduleDelay(0);
            FilterInputBox.attachHistory(searchBox, historyScope(leaves));
            searchBox.setRunSearchOnTextChange(false);
            final int[] applyGeneration = { 0 };
            searchBox.setSearchListener(new SearchBox.ISearchListener()
            {
                @Override
                public void performSearch(String text, IProgressMonitor monitor)
                {
                    scheduleApply(searchBox, viewer, filter, highlights, applyGeneration);
                }
            });
            searchBox.addModifyListener(e ->
            {
                if (searchBox.isDisposed())
                    return;
                scheduleApply(searchBox, viewer, filter, highlights, applyGeneration);
            });
            FilterInputBoxListNavigation.installTreeNavigation(searchBox, tree);
            installLeavesCheckbox(section, searchBox, viewer, filter, highlights, lockedLeaves);
            installExactCheckbox(section, searchBox, viewer, filter, highlights);
            wrapEditingSupports(viewer, filter);
            tree.setData(PATCHED_KEY, Boolean.TRUE);
            tree.setData(APPLY_GEN_KEY, applyGeneration);

            String initial = searchBox.getText();
            if (initial != null && !initial.isEmpty())
                apply(searchBox, viewer, filter, highlights);

            Display display = tree.getDisplay();
            if (display != null && !display.isDisposed())
            {
                for (int delay : new int[] { 200, 800, 2000 })
                {
                    display.timerExec(delay, () ->
                    {
                        if (!searchBox.isDisposed() && !tree.isDisposed())
                            ensureOurSearchListener(searchBox, viewer, tree);
                    });
                }
            }
        }
        else
            ensureOurSearchListener(searchBox, viewer, tree);

        if (tree.getData(FILTER_KEY) instanceof RightsSmartFilter filterReady)
        {
            searchBox.setData(FILTER_KEY, filterReady);
            searchBox.setData(VIEWER_KEY, viewer);
            Object highlightsObj = tree.getData(HIGHLIGHT_KEY);
            if (highlightsObj != null)
                searchBox.setData(HIGHLIGHT_KEY, highlightsObj);
            installFilterByCurrentValueSupport(section, viewer);
            installCollapseOthersMenu(section, viewer);
        }

        return true;
    }

    /**
     * {@code ObjectsSection.afterViewerInizialized} вешает штатный слушатель после
     * создания поля — возвращаем наш, если его успели перезаписать.
     */
    private static void ensureOurSearchListener(SearchBox searchBox, TreeViewer viewer, Tree tree)
    {
        Object listener = Global.getField(searchBox, "searchListener"); //$NON-NLS-1$
        if (listener != null && listener.getClass().getDeclaringClass() == RightsEditorFilterHook.class)
            return;
        Object filterObj = tree.getData(FILTER_KEY);
        Object highlightsObj = tree.getData(HIGHLIGHT_KEY);
        Object genObj = tree.getData(APPLY_GEN_KEY);
        if (!(filterObj instanceof RightsSmartFilter filter)
            || !(highlightsObj instanceof List<?> highlightsRaw)
            || !(genObj instanceof int[] applyGeneration))
            return;
        @SuppressWarnings("unchecked")
        List<ColumnHighlight> highlights = (List<ColumnHighlight>) highlightsRaw;
        searchBox.setSearchListener((text, monitor) ->
            scheduleApply(searchBox, viewer, filter, highlights, applyGeneration));
        wrapEditingSupports(viewer, filter);
    }

    private static void scheduleApply(SearchBox searchBox, TreeViewer viewer,
        RightsSmartFilter filter, List<ColumnHighlight> highlights, int[] applyGeneration)
    {
        if (searchBox == null || searchBox.isDisposed())
            return;
        Display display = searchBox.getDisplay();
        if (display == null || display.isDisposed())
            return;
        int generation = ++applyGeneration[0];
        display.asyncExec(() ->
        {
            if (searchBox.isDisposed() || generation != applyGeneration[0])
                return;
            apply(searchBox, viewer, filter, highlights);
        });
    }

    private static void apply(SearchBox searchBox, TreeViewer viewer,
        RightsSmartFilter filter, List<ColumnHighlight> highlights)
    {
        try
        {
            if (viewer.getControl() == null || viewer.getControl().isDisposed())
                return;
            Tree tree = viewer.getTree();
            if (searchBox != null && !searchBox.isDisposed()
                && tree != null && !tree.isDisposed())
                ensureOurSearchListener(searchBox, viewer, tree);
            wrapEditingSupports(viewer, filter);
            String pattern = searchBox != null && !searchBox.isDisposed()
                ? searchBox.getText().trim() : ""; //$NON-NLS-1$
            boolean filtering = !pattern.isEmpty();
            filter.setPattern(pattern);
            for (ColumnHighlight highlight : highlights)
            {
                highlight.setHighlightPattern(pattern);
                highlight.setExactSegments(filter.isExactSegments());
            }

            boolean attached = isFilterAttached(viewer, filter);
            ISelection selection = viewer.getSelection();
            if (filtering && !attached)
                viewer.addFilter(filter);
            else if (!filtering && attached)
                viewer.removeFilter(filter);
            else
                viewer.refresh();
            if (!filtering)
                collapseOthersKeepingSelection(viewer, selection);
            else if (filter.isLeafMode())
                TreeExpander.runSuppressed(() -> viewer.expandAll());
            else
                TreeExpander.notifyContentLoaded(viewer);
            enableFilterByCurrentValueAction(viewer);
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "apply", e); //$NON-NLS-1$
        }
    }

    private static void collapseOthersKeepingSelection(TreeViewer viewer, ISelection selection)
    {
        TreeExpander.runSuppressed(() ->
        {
            if (selection != null && !selection.isEmpty())
            {
                viewer.setSelection(selection, true);
                TreeCollapseOthers.collapseOthers(viewer);
            }
            else
                viewer.collapseAll();
        });
    }

    /**
     * Штатный {@code ObjectsSection$7.getFilters} при пустом списке возвращает
     * {@code null}, а не пустой массив — обход через for-each даёт NPE и фильтр
     * так и не вешается.
     */
    private static boolean isFilterAttached(TreeViewer viewer, ViewerFilter filter)
    {
        ViewerFilter[] filters = viewer.getFilters();
        if (filters == null)
            return false;
        for (ViewerFilter existing : filters)
        {
            if (existing == filter)
                return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<ColumnHighlight> installHighlights(TreeViewer viewer)
    {
        List<ColumnHighlight> result = new ArrayList<>();
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return result;

        Object existing = tree.getData(HIGHLIGHT_KEY);
        if (existing instanceof List<?> list && !list.isEmpty()
            && list.get(0) instanceof ColumnHighlight)
            return (List<ColumnHighlight>) list;

        int count = tree.getColumnCount();
        for (int i = 0; i < count; i++)
        {
            TreeViewerColumn column = resolveViewerColumn(viewer, tree, i);
            if (column == null)
                continue;
            Object lpObj = Global.invoke(column, "getLabelProvider"); //$NON-NLS-1$
            if (!(lpObj instanceof DelegatingStyledCellLabelProvider delegating))
                continue;
            ColumnHighlight highlight = installColumnHighlight(delegating);
            if (highlight != null)
                result.add(highlight);
        }
        return result;
    }

    private static ColumnHighlight installColumnHighlight(DelegatingStyledCellLabelProvider delegating)
    {
        IStyledLabelProvider inner = delegating.getStyledStringProvider();
        if (inner instanceof ColumnHighlight existing)
        {
            SmartMatchHighlight.enableColorsOnSelection(delegating);
            return existing;
        }
        if (inner == null)
            return null;
        ColumnHighlight highlight = new ColumnHighlight(inner);
        if (!injectStyledStringProvider(delegating, highlight))
            return null;
        SmartMatchHighlight.enableColorsOnSelection(delegating);
        return highlight;
    }

    private static boolean injectStyledStringProvider(DelegatingStyledCellLabelProvider provider,
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
                        return true;
                    }
                    catch (Exception ignored)
                    {
                        // следующее поле / суперкласс
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        return false;
    }

    private static TreeViewerColumn resolveViewerColumn(TreeViewer viewer, Tree tree, int index)
    {
        Object vc = Global.invoke(viewer, "getViewerColumn", Integer.valueOf(index)); //$NON-NLS-1$
        if (vc instanceof TreeViewerColumn tvc)
            return tvc;
        if (index >= 0 && index < tree.getColumnCount())
        {
            TreeColumn column = tree.getColumn(index);
            if (column != null)
            {
                Object colData = column.getData(COLUMN_VIEWER_KEY);
                if (colData instanceof TreeViewerColumn tvcFromData)
                    return tvcFromData;
            }
        }
        return null;
    }

    private static FilterInputBox.Scope historyScope(boolean leaves)
    {
        return leaves ? FilterInputBox.Scope.RIGHTS_EDITOR_LEAVES : FilterInputBox.Scope.RIGHTS_EDITOR;
    }

    private static boolean isMdObjectRightsEditor(Object section)
    {
        Object controller = Global.getField(section, "controller"); //$NON-NLS-1$
        Object type = Global.invoke(controller, "getEditorType"); //$NON-NLS-1$
        return type instanceof Enum<?> editorType
            && "MD_OBJECT_EDITOR".equals(editorType.name()); //$NON-NLS-1$
    }

    private static boolean isLeavesModePref()
    {
        ComfortSettings settings = ComfortSettings.getInstance();
        if (settings == null)
            return false;
        return settings.getPreferenceStore().getBoolean(PREF_LEAVES_MODE);
    }

    private static void saveLeavesModePref(boolean enabled)
    {
        ComfortSettings settings = ComfortSettings.getInstance();
        if (settings == null)
            return;
        settings.getPreferenceStore().setValue(PREF_LEAVES_MODE, enabled);
        try
        {
            settings.getPreferenceStore().save();
        }
        catch (Exception ex)
        {
            Global.log(TAG, "save leaves mode: " + ex); //$NON-NLS-1$
        }
    }

    private static void installLeavesCheckbox(Object section, SearchBox searchBox, TreeViewer viewer,
        RightsSmartFilter filter, List<ColumnHighlight> highlights, boolean lockedLeaves)
    {
        if (searchBox.getData(LEAVES_CHECK_KEY) instanceof Button)
            return;
        Composite parent = searchBox.getParent();
        if (parent == null || parent.isDisposed())
            return;

        Composite row = ensureFilterRow(section, searchBox, parent, viewer);
        if (row == null || row.isDisposed())
            return;
        FilterInputBox.applyCompactLayout(searchBox);

        Button check;
        Object formObj = Global.getField(section, "managedForm"); //$NON-NLS-1$
        if (formObj instanceof IManagedForm managedForm)
            check = managedForm.getToolkit().createButton(row, LEAVES_CHECK_LABEL, SWT.CHECK);
        else
        {
            check = new Button(row, SWT.CHECK);
            check.setText(LEAVES_CHECK_LABEL);
        }
        check.setToolTipText(LEAVES_CHECK_TOOLTIP + Global.pluginSignForTooltip());
        check.setSelection(filter.isLeafMode());
        if (lockedLeaves)
        {
            check.setSelection(true);
            check.setEnabled(false);
        }
        check.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));

        if (!lockedLeaves)
        {
            check.addListener(SWT.Selection, e ->
            {
                applyLeafMode(searchBox, viewer, filter, check.getSelection(), false);
                apply(searchBox, viewer, filter, highlights);
            });
        }
        searchBox.setData(LEAVES_CHECK_KEY, check);
        parent.layout(true, true);
    }

    private static void installExactCheckbox(Object section, SearchBox searchBox, TreeViewer viewer,
        RightsSmartFilter filter, List<ColumnHighlight> highlights)
    {
        if (searchBox.getData(EXACT_CHECK_KEY) instanceof Button)
            return;
        Composite parent = searchBox.getParent();
        if (parent == null || parent.isDisposed())
            return;
        Composite row = ensureFilterRow(section, searchBox, parent, viewer);
        if (row == null || row.isDisposed())
            return;

        Button check;
        Object formObj = Global.getField(section, "managedForm"); //$NON-NLS-1$
        if (formObj instanceof IManagedForm managedForm)
            check = managedForm.getToolkit().createButton(row, EXACT_CHECK_LABEL, SWT.CHECK);
        else
        {
            check = new Button(row, SWT.CHECK);
            check.setText(EXACT_CHECK_LABEL);
        }
        check.setToolTipText(EXACT_CHECK_TOOLTIP + Global.pluginSignForTooltip());
        check.setSelection(filter.isExactSegments());
        check.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        check.addListener(SWT.Selection, e ->
        {
            applyExactMode(searchBox, viewer, filter, check.getSelection());
            apply(searchBox, viewer, filter, highlights);
        });
        searchBox.setData(EXACT_CHECK_KEY, check);
        parent.layout(true, true);
    }

    private static boolean isExactModePref()
    {
        ComfortSettings settings = ComfortSettings.getInstance();
        if (settings == null)
            return false;
        return settings.getPreferenceStore().getBoolean(PREF_EXACT_MODE);
    }

    private static void saveExactModePref(boolean enabled)
    {
        ComfortSettings settings = ComfortSettings.getInstance();
        if (settings == null)
            return;
        settings.getPreferenceStore().setValue(PREF_EXACT_MODE, enabled);
        try
        {
            settings.getPreferenceStore().save();
        }
        catch (Exception ex)
        {
            Global.log(TAG, "save exact mode: " + ex); //$NON-NLS-1$
        }
    }

    /**
     * Включает «Полный» при подстановке точной ссылки МД (команда «Права»,
     * «Все роли», «Фильтровать по выбранному значению», перетаскивание из навигатора).
     */
    static void applyExactReference(SearchBox searchBox)
    {
        if (searchBox == null || searchBox.isDisposed())
            return;
        if (!(searchBox.getData(FILTER_KEY) instanceof RightsSmartFilter filter))
            return;
        if (!(searchBox.getData(VIEWER_KEY) instanceof TreeViewer viewer))
            return;
        applyExactMode(searchBox, viewer, filter, true);
    }

    private static void applyExactMode(SearchBox searchBox, TreeViewer viewer,
        RightsSmartFilter filter, boolean exact)
    {
        if (filter.isExactSegments() == exact)
        {
            Object checkObj = searchBox.getData(EXACT_CHECK_KEY);
            if (checkObj instanceof Button check && !check.isDisposed() && check.getSelection() != exact)
                check.setSelection(exact);
            return;
        }
        saveExactModePref(exact);
        Object checkObj = searchBox.getData(EXACT_CHECK_KEY);
        if (checkObj instanceof Button check && !check.isDisposed())
            check.setSelection(exact);
        filter.setExactSegments(exact);
    }

    /**
     * Поле фильтра и флажок в одной компактной строке слева, без растягивания
     * SearchBox на всю ширину секции.
     */
    private static Composite ensureFilterRow(Object section, SearchBox searchBox, Composite parent,
        TreeViewer viewer)
    {
        if (Boolean.TRUE.equals(parent.getData(FILTER_ROW_KEY)))
        {
            if (parent.getLayout() instanceof GridLayout existing && existing.numColumns < 3)
                existing.numColumns = 3;
            return parent;
        }
        Composite row;
        Object formObj = Global.getField(section, "managedForm"); //$NON-NLS-1$
        if (formObj instanceof IManagedForm managedForm)
            row = managedForm.getToolkit().createComposite(parent);
        else
            row = new Composite(parent, SWT.NONE);
        row.setData(FILTER_ROW_KEY, Boolean.TRUE);
        GridLayout rowLayout = new GridLayout(3, false);
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 0;
        rowLayout.horizontalSpacing = 6;
        row.setLayout(rowLayout);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        searchBox.setParent(row);
        FilterInputBox.applyCompactLayout(searchBox);
        Tree tree = viewer.getTree();
        if (tree != null && !tree.isDisposed() && tree.getParent() == parent)
            row.moveAbove(tree);
        return row;
    }

    /**
     * Штатная команда доступна только для {@code isTop} строк. Для дочерних
     * включаем её и переводим фильтр в режим «Нижний» с именем строки в поиске.
     */
    private static void installFilterByCurrentValueSupport(Object section, TreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        Object actionObj = Global.getField(section, "filterByCurrentValueAction"); //$NON-NLS-1$
        if (actionObj instanceof IAction action)
            tree.setData(FILTER_BY_VALUE_ACTION_KEY, action);
        enableFilterByCurrentValueAction(viewer);
        if (!Boolean.TRUE.equals(tree.getData(FILTER_BY_VALUE_HOOK_KEY)))
        {
            tree.setData(FILTER_BY_VALUE_HOOK_KEY, Boolean.TRUE);
            viewer.addSelectionChangedListener(event ->
            {
                Display display = tree.getDisplay();
                if (display == null || display.isDisposed())
                    return;
                display.asyncExec(() ->
                {
                    if (!tree.isDisposed())
                        enableFilterByCurrentValueAction(viewer);
                });
            });
        }
        Object mm = Global.getField(section, "contextMenuManager"); //$NON-NLS-1$
        if (mm instanceof MenuManager manager
            && !Boolean.TRUE.equals(tree.getData(FILTER_BY_VALUE_MENU_KEY)))
        {
            tree.setData(FILTER_BY_VALUE_MENU_KEY, Boolean.TRUE);
            manager.addMenuListener(m -> enableFilterByCurrentValueAction(viewer));
        }
        installFilterByCurrentValueCommandListener();
    }

    private static void installCollapseOthersMenu(Object section, TreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(COLLAPSE_OTHERS_MENU_KEY)))
            return;
        Object mm = Global.getField(section, "contextMenuManager"); //$NON-NLS-1$
        if (!(mm instanceof MenuManager manager))
            return;
        Action action = new Action(TreeCollapseOthers.ITEM_TEXT)
        {
            @Override
            public void run()
            {
                TreeCollapseOthers.collapseOthers(viewer);
            }
        };
        action.setId("tormozit.rightsEditor.collapseOthers"); //$NON-NLS-1$
        action.setToolTipText(TreeCollapseOthers.ITEM_TOOLTIP);
        try
        {
            action.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
                .getImageDescriptor(ISharedImages.IMG_ELCL_COLLAPSEALL));
        }
        catch (RuntimeException ignored)
        {
        }
        boolean inserted = false;
        Object collapseAll = Global.getField(section, "collapseAllAction"); //$NON-NLS-1$
        if (collapseAll instanceof IAction stock)
        {
            String id = stock.getId();
            if (id != null && !id.isEmpty())
            {
                try
                {
                    manager.insertAfter(id, action);
                    inserted = true;
                }
                catch (IllegalArgumentException ignored)
                {
                }
            }
        }
        if (!inserted)
            manager.add(action);
        tree.setData(COLLAPSE_OTHERS_MENU_KEY, Boolean.TRUE);
        manager.addMenuListener(m -> action.setEnabled(TreeCollapseOthers.isApplicable(tree)));
    }

    private static void enableFilterByCurrentValueAction(TreeViewer viewer)
    {
        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        Object actionObj = tree.getData(FILTER_BY_VALUE_ACTION_KEY);
        if (!(actionObj instanceof IAction action))
            return;
        boolean has = viewer.getSelection() instanceof IStructuredSelection ss && !ss.isEmpty();
        action.setEnabled(has);
    }

    private static void installFilterByCurrentValueCommandListener()
    {
        if (filterByValueListenerInstalled || PlatformUI.getWorkbench() == null)
            return;
        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        commandService.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
                if (FILTER_BY_VALUE_COMMAND_ID.equals(commandId))
                    handleFilterByCurrentValue();
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception)
            {
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception)
            {
            }
        });
        filterByValueListenerInstalled = true;
    }

    private static void handleFilterByCurrentValue()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return;
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return;
        IEditorPart editor = page.getActiveEditor();
        Object section = resolveObjectsSection(editor);
        if (section == null)
            return;
        Object searchObj = section != null ? Global.getField(section, "searchBox") : null; //$NON-NLS-1$
        Object viewerObj = section != null ? Global.getField(section, "viewer") : null; //$NON-NLS-1$
        if (!(searchObj instanceof SearchBox searchBox) || searchBox.isDisposed())
            return;
        if (!(viewerObj instanceof TreeViewer viewer))
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        Object filterObj = tree.getData(FILTER_KEY);
        Object highlightsObj = tree.getData(HIGHLIGHT_KEY);
        if (!(filterObj instanceof RightsSmartFilter filter)
            || !(highlightsObj instanceof List<?> highlightsRaw))
            return;
        @SuppressWarnings("unchecked")
        List<ColumnHighlight> highlights = (List<ColumnHighlight>) highlightsRaw;
        if (!(viewer.getSelection() instanceof IStructuredSelection selection) || selection.isEmpty())
            return;
        Object first = selection.getFirstElement();
        if (first == null)
            return;
        boolean leaf = !RightsSmartFilter.isTop(first);
        boolean locked = isMdObjectRightsEditor(section);
        applyLeafMode(searchBox, viewer, filter, leaf, locked);
        if (leaf)
        {
            String name = filterNameOf(first);
            if (!name.isEmpty())
            {
                applyExactMode(searchBox, viewer, filter, true);
                searchBox.setText(name);
            }
        }
        apply(searchBox, viewer, filter, highlights);
    }

    private static Object resolveObjectsSection(IEditorPart editor)
    {
        if (editor instanceof DtGranularEditor<?> granular)
        {
            IFormPage formPage = granular.getActivePageInstance();
            if (!isRightsPage(formPage))
                return null;
            return Global.getField(formPage, "objectsSection"); //$NON-NLS-1$
        }
        if (isAllRolesEditor(editor))
            return Global.getField(editor, "objectsSection"); //$NON-NLS-1$
        return null;
    }

    private static void applyLeafMode(SearchBox searchBox, TreeViewer viewer,
        RightsSmartFilter filter, boolean leaves, boolean locked)
    {
        if (locked)
            leaves = true;
        if (filter.isLeafMode() == leaves)
            return;
        if (!locked)
        {
            FilterInputBox.remember(historyScope(!leaves), searchBox.getText());
            FilterInputBox.attachHistory(searchBox, historyScope(leaves));
            saveLeavesModePref(leaves);
            Object checkObj = searchBox.getData(LEAVES_CHECK_KEY);
            if (checkObj instanceof Button check && !check.isDisposed())
                check.setSelection(leaves);
        }
        filter.setLeafMode(leaves);
        wrapEditingSupports(viewer, filter);
    }

    private static String filterNameOf(Object element)
    {
        Object eObj = Global.invoke(element, "getEObject"); //$NON-NLS-1$
        if (eObj instanceof EObject md)
        {
            String relative = relativeMdName(md);
            if (relative != null && !relative.isEmpty())
                return relative;
        }
        String name = stringOf(Global.invoke(element, "getEObjectName")); //$NON-NLS-1$
        if (!name.isEmpty())
            return name;
        return stringOf(Global.invoke(element, "getRoleName")); //$NON-NLS-1$
    }

    /** Хвост после владельца МД: {@code Справочник.Орг.Реквизит.Бик} → {@code Реквизит.Бик}. */
    private static String relativeMdName(EObject object)
    {
        String full = GetRef.eObjectToFullName(object);
        if (full == null || full.isBlank())
            return null;
        String owner = MdTypeMapping.toOwnerMdObjectRef(full);
        if (owner == null || owner.isBlank())
            return null;
        String prefix = owner + "."; //$NON-NLS-1$
        if (!full.startsWith(prefix))
            return null;
        String relative = full.substring(prefix.length());
        return relative.isBlank() ? null : relative;
    }

    /**
     * Ctrl+C при фокусе на дереве копирует текст активной ячейки. В редакторе
     * EDT глобальный Copy перехватывает сочетание раньше {@code SWT.KeyDown} —
     * см. {@link CopyCommandSupport}.
     */
    private static void installTreeCopy(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(COPY_HOOKED_KEY)))
            return;
        tree.setData(COPY_HOOKED_KEY, Boolean.TRUE);
        tree.addListener(SWT.MouseDown, e ->
        {
            if (e.button != 1 || tree.isDisposed())
                return;
            TreeItem item = tree.getItem(new Point(e.x, e.y));
            if (item == null)
                return;
            tree.setData(COPY_ACTIVE_COLUMN_KEY, Integer.valueOf(columnAt(tree, e.x, e.y, item)));
        });
        CopyCommandSupport.wireCopyOverride(tree, () -> copyActiveCellText(tree));
    }

    private static int columnAt(Tree tree, int x, int y, TreeItem item)
    {
        int count = tree.getColumnCount();
        for (int i = 0; i < count; i++)
        {
            Rectangle bounds = item.getBounds(i);
            if (bounds != null && !bounds.isEmpty() && bounds.contains(x, y))
                return i;
        }
        return 0;
    }

    private static void copyActiveCellText(Tree tree)
    {
        if (tree.isDisposed())
            return;
        TreeItem[] selection = tree.getSelection();
        if (selection.length == 0)
            return;
        int column = 0;
        Object stored = tree.getData(COPY_ACTIVE_COLUMN_KEY);
        if (stored instanceof Integer col && col >= 0 && col < tree.getColumnCount())
            column = col.intValue();
        String text = cellText(selection[0], column);
        if (text.isEmpty() && column != 0)
            text = cellText(selection[0], 0);
        if (text.isEmpty())
            text = itemFallbackName(selection[0]);
        if (text.isEmpty())
            return;
        Clipboard clipboard = new Clipboard(tree.getDisplay());
        try
        {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            clipboard.dispose();
        }
    }

    private static String cellText(TreeItem item, int column)
    {
        String text = item.getText(column);
        return text == null ? "" : text.trim(); //$NON-NLS-1$
    }

    private static String itemFallbackName(TreeItem item)
    {
        Object data = item.getData();
        if (data == null)
            return ""; //$NON-NLS-1$
        String name = stringOf(Global.invoke(data, "getEObjectName")); //$NON-NLS-1$
        if (!name.isEmpty())
            return name;
        return stringOf(Global.invoke(data, "getRoleName")); //$NON-NLS-1$
    }

    private static String stringOf(Object value)
    {
        return value instanceof String s ? s : ""; //$NON-NLS-1$
    }

    private static void wrapEditingSupports(TreeViewer viewer, RightsSmartFilter filter)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        if (!Boolean.TRUE.equals(tree.getData(EDIT_WRAP_HOOK_KEY)))
        {
            tree.addListener(SWT.MouseDown, e -> wrapEditingSupports(viewer, filter));
            tree.setData(EDIT_WRAP_HOOK_KEY, Boolean.TRUE);
        }
        int count = tree.getColumnCount();
        for (int i = 0; i < count; i++)
        {
            TreeViewerColumn column = resolveViewerColumn(viewer, tree, i);
            if (column == null)
                continue;
            Object es = Global.invoke(column, "getEditingSupport"); //$NON-NLS-1$
            if (!(es instanceof EditingSupport existing) || existing instanceof LeafEditGuard)
                continue;
            column.setEditingSupport(new LeafEditGuard(viewer, existing, filter));
        }
        wrapCheckLabelProviders(viewer, filter);
    }

    private static void wrapCheckLabelProviders(TreeViewer viewer, RightsSmartFilter filter)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        int count = tree.getColumnCount();
        for (int i = 0; i < count; i++)
        {
            TreeViewerColumn column = resolveViewerColumn(viewer, tree, i);
            if (column == null)
                continue;
            Object lpObj = Global.invoke(column, "getLabelProvider"); //$NON-NLS-1$
            if (!(lpObj instanceof CellLabelProvider existing) || existing instanceof LeafCheckHide)
                continue;
            if (!isRightCheckBoxProvider(existing))
                continue;
            column.setLabelProvider(new LeafCheckHide(existing, filter));
        }
    }

    private static boolean isRightCheckBoxProvider(Object provider)
    {
        return provider.getClass().getName().endsWith("RightCheckBoxLabelProvider"); //$NON-NLS-1$
    }

    /**
     * Как штатный {@code ItemFilter.filter} по {@code getActualItems}: матч только
     * верхних узлов. Остальные всегда проходят. В режиме «Нижний» — листовые
     * объекты и их родители.
     */
    private static final class RightsSmartFilter extends ViewerFilter
    {
        private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$

        private boolean leafMode;

        private boolean exactSegments;

        private final Map<Object, Boolean> subtreeMemo = new IdentityHashMap<>();

        void setPattern(String pattern)
        {
            matcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
            subtreeMemo.clear();
        }

        void setLeafMode(boolean leafMode)
        {
            this.leafMode = leafMode;
            subtreeMemo.clear();
        }

        boolean isLeafMode()
        {
            return leafMode;
        }

        void setExactSegments(boolean exactSegments)
        {
            this.exactSegments = exactSegments;
            subtreeMemo.clear();
        }

        boolean isExactSegments()
        {
            return exactSegments;
        }

        boolean allowsEdit(Object element)
        {
            if (!leafMode)
                return true;
            if (isCollectionFolder(element))
                return false;
            if (matcher.isEmpty)
                return true;
            return matchesLeafTarget(element);
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            if (matcher.isEmpty)
                return true;
            if (!leafMode)
            {
                if (!isTop(element))
                    return true;
                if (matchesOwn(element))
                    return true;
                return isCollectionFolder(element) && hasMatchingDirectChild(viewer, element);
            }
            return matchesLeafOrAncestor(viewer, element);
        }

        private boolean matchesLeafOrAncestor(Viewer viewer, Object element)
        {
            Boolean memo = subtreeMemo.get(element);
            if (memo != null)
                return memo.booleanValue();
            subtreeMemo.put(element, Boolean.FALSE);
            boolean result = matchesLeafTarget(element) || hasMatchingDescendant(viewer, element);
            subtreeMemo.put(element, result);
            return result;
        }

        private boolean hasMatchingDescendant(Viewer viewer, Object element)
        {
            List<?> loaded = asList(Global.invoke(element, "getChildItems")); //$NON-NLS-1$
            if (loaded != null && !loaded.isEmpty())
            {
                for (Object child : loaded)
                {
                    if (matchesLeafOrAncestor(viewer, child))
                        return true;
                }
                return false;
            }
            if (!(viewer instanceof TreeViewer treeViewer)
                || !(treeViewer.getContentProvider() instanceof ITreeContentProvider tcp))
                return false;
            Object[] children = tcp.getChildren(element);
            if (children == null)
                return false;
            for (Object child : children)
            {
                if (matchesLeafOrAncestor(viewer, child))
                    return true;
            }
            return false;
        }

        private boolean matchesLeafTarget(Object element)
        {
            if (isCollectionFolder(element))
                return false;
            if (matchesMdName(element))
                return true;
            if (matchesOwn(element))
                return true;
            String path = dottedPath(element);
            return !path.isEmpty() && matchText(path);
        }

        private boolean matchesMdName(Object element)
        {
            Object eObj = Global.invoke(element, "getEObject"); //$NON-NLS-1$
            if (!(eObj instanceof EObject md))
                return false;
            String full = GetRef.eObjectToFullName(md);
            if (full != null && !full.isBlank() && matchText(full))
                return true;
            String relative = relativeMdName(md);
            return relative != null && !relative.isEmpty() && matchText(relative);
        }

        private boolean matchText(String text)
        {
            return exactSegments ? matcher.matchesTreeExact(text) : matcher.matchesTree(text);
        }

        private boolean hasMatchingDirectChild(Viewer viewer, Object folder)
        {
            List<?> loaded = asList(Global.invoke(folder, "getChildItems")); //$NON-NLS-1$
            if (loaded != null)
            {
                for (Object child : loaded)
                {
                    if (matchesOwn(child) || matchesParentChild(folder, child))
                        return true;
                }
                return false;
            }
            if (!(viewer instanceof TreeViewer treeViewer)
                || !(treeViewer.getContentProvider() instanceof ITreeContentProvider tcp))
                return false;
            Object[] children = tcp.getChildren(folder);
            if (children == null)
                return false;
            for (Object child : children)
            {
                if (matchesOwn(child) || matchesParentChild(folder, child))
                    return true;
            }
            return false;
        }

        private boolean matchesParentChild(Object parent, Object child)
        {
            String parentName = objectName(parent);
            String childName = objectName(child);
            if (parentName.isEmpty() || childName.isEmpty())
                return false;
            return matchText(parentName + "." + childName); //$NON-NLS-1$
        }

        private boolean matchesOwn(Object element)
        {
            String name = objectName(element);
            if (!name.isEmpty() && matchText(name))
                return true;
            String role = stringOf(Global.invoke(element, "getRoleName")); //$NON-NLS-1$
            return !role.isEmpty() && matchText(role);
        }

        private String dottedPath(Object element)
        {
            List<String> parts = new ArrayList<>();
            Object current = element;
            while (current != null)
            {
                String name = objectName(current);
                if (!name.isEmpty())
                    parts.add(name);
                current = Global.invoke(current, "getParent"); //$NON-NLS-1$
            }
            if (parts.isEmpty())
                return ""; //$NON-NLS-1$
            Collections.reverse(parts);
            return String.join(".", parts); //$NON-NLS-1$
        }

        private static boolean isTop(Object element)
        {
            Object top = Global.invoke(element, "isTop"); //$NON-NLS-1$
            if (top instanceof Boolean)
                return ((Boolean) top).booleanValue();
            return Global.invoke(element, "getParent") == null; //$NON-NLS-1$
        }

        private static boolean isCollectionFolder(Object element)
        {
            Object flag = Global.invoke(element, "hasCollectionAdapter"); //$NON-NLS-1$
            return Boolean.TRUE.equals(flag);
        }

        private static String objectName(Object element)
        {
            return stringOf(Global.invoke(element, "getEObjectName")); //$NON-NLS-1$
        }

        private static List<?> asList(Object value)
        {
            return value instanceof List<?> list ? list : null;
        }
    }

    /**
     * В режиме «Нижний» запрещает менять пометки у папок и у узлов, которые
     * видны только как родители совпадений.
     */
    private static final class LeafEditGuard extends EditingSupport
    {
        private final EditingSupport delegate;

        private final RightsSmartFilter filter;

        LeafEditGuard(TreeViewer viewer, EditingSupport delegate, RightsSmartFilter filter)
        {
            super(viewer);
            this.delegate = delegate;
            this.filter = filter;
        }

        @Override
        protected boolean canEdit(Object element)
        {
            if (!filter.allowsEdit(element))
                return false;
            return Boolean.TRUE.equals(Global.invoke(delegate, "canEdit", element)); //$NON-NLS-1$
        }

        @Override
        protected CellEditor getCellEditor(Object element)
        {
            Object editor = Global.invoke(delegate, "getCellEditor", element); //$NON-NLS-1$
            return editor instanceof CellEditor cellEditor ? cellEditor : null;
        }

        @Override
        protected Object getValue(Object element)
        {
            return Global.invoke(delegate, "getValue", element); //$NON-NLS-1$
        }

        @Override
        protected void setValue(Object element, Object value)
        {
            if (!filter.allowsEdit(element))
                return;
            Global.invoke(delegate, "setValue", element, value); //$NON-NLS-1$
        }
    }

    /**
     * В режиме «Нижний» не рисует пометки у узлов, которые нельзя редактировать
     * (папки и родители совпадений).
     */
    private static final class LeafCheckHide extends OwnerDrawLabelProvider implements IToolTipProvider
    {
        private final CellLabelProvider delegate;

        private final RightsSmartFilter filter;

        LeafCheckHide(CellLabelProvider delegate, RightsSmartFilter filter)
        {
            this.delegate = delegate;
            this.filter = filter;
        }

        @Override
        protected void measure(Event event, Object element)
        {
            Global.invoke(delegate, "measure", event, element); //$NON-NLS-1$
        }

        @Override
        protected void paint(Event event, Object element)
        {
            if (!filter.allowsEdit(element))
                return;
            Global.invoke(delegate, "paint", event, element); //$NON-NLS-1$
        }

        @Override
        protected void erase(Event event, Object element)
        {
            Global.invoke(delegate, "erase", event, element); //$NON-NLS-1$
        }

        @Override
        public String getToolTipText(Object element)
        {
            if (!filter.allowsEdit(element))
                return null;
            if (delegate instanceof IToolTipProvider tips)
                return tips.getToolTipText(element);
            Object tip = Global.invoke(delegate, "getToolTipText", element); //$NON-NLS-1$
            return tip instanceof String s ? s : null;
        }
    }

    /**
     * Раскраска вхождений поверх штатного {@code ObjectColumnLabelProvider}/
     * {@code RoleColumnLabelProvider}. Цвет/шрифт/подсказку делегирует ему же.
     */
    private static final class ColumnHighlight implements IStyledLabelProvider, SmartLabelHighlight,
        IColorProvider, IFontProvider, IToolTipProvider
    {
        private final IStyledLabelProvider delegate;

        private SmartMatcher highlightMatcher = new SmartMatcher(""); //$NON-NLS-1$

        private boolean exactSegments;

        ColumnHighlight(IStyledLabelProvider delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public void setHighlightPattern(String pattern)
        {
            highlightMatcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
        }

        void setExactSegments(boolean exactSegments)
        {
            this.exactSegments = exactSegments;
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            StyledString styled = delegate.getStyledText(element);
            if (styled == null)
                styled = new StyledString();
            if (highlightMatcher.isEmpty || exactSegments)
                return styled;
            String text = styled.getString();
            if (text == null || text.isEmpty())
                return styled;
            List<SmartMatcher.HighlightRange> ranges = highlightMatcher.getTreeHighlightRanges(text);
            if (ranges.isEmpty())
                ranges = highlightMatcher.getLastSectionHighlightRanges(text);
            SmartMatchHighlight.applyRanges(styled, ranges);
            return styled;
        }

        @Override
        public Image getImage(Object element)
        {
            return delegate.getImage(element);
        }

        @Override
        public Color getForeground(Object element)
        {
            if (delegate instanceof IColorProvider colors)
                return colors.getForeground(element);
            Object color = Global.invoke(delegate, "getForeground", element); //$NON-NLS-1$
            return color instanceof Color c ? c : null;
        }

        @Override
        public Color getBackground(Object element)
        {
            if (delegate instanceof IColorProvider colors)
                return colors.getBackground(element);
            Object color = Global.invoke(delegate, "getBackground", element); //$NON-NLS-1$
            return color instanceof Color c ? c : null;
        }

        @Override
        public Font getFont(Object element)
        {
            if (delegate instanceof IFontProvider fonts)
                return fonts.getFont(element);
            Object font = Global.invoke(delegate, "getFont", element); //$NON-NLS-1$
            return font instanceof Font f ? f : null;
        }

        @Override
        public String getToolTipText(Object element)
        {
            if (delegate instanceof IToolTipProvider tips)
                return tips.getToolTipText(element);
            Object tip = Global.invoke(delegate, "getToolTipText", element); //$NON-NLS-1$
            return tip instanceof String s ? s : null;
        }

        @Override
        public void addListener(ILabelProviderListener listener)
        {
            delegate.addListener(listener);
        }

        @Override
        public void removeListener(ILabelProviderListener listener)
        {
            delegate.removeListener(listener);
        }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            return delegate.isLabelProperty(element, property);
        }

        @Override
        public void dispose()
        {
            delegate.dispose();
        }
    }
}
