package tormozit;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.IColorProvider;
import org.eclipse.jface.viewers.IFontProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IToolTipProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;

/**
 * Многословный фильтр ({@link SmartMatcher}, плоское И по словам) в диалогах EDT
 * «Установить фильтр по объектам и ролям» ({@code SetFilterDialog}) и
 * «Установить фильтр по правам» ({@code SetFilterByRightsDialog}).
 *
 * <p>Панели — штатный {@code AbstractViewerPanel} с {@link SearchBox} и
 * {@code searchFilterWithHistory} ({@code InMemorySearchFilter} / для объектов —
 * {@code NavigatorSearchFilterWithHistory}). Порог длины штатного поиска — 3 символа,
 * матчинг — fuzzy-подстрока. Хук оставляет {@link SearchBox}, подменяет слушатель и
 * {@link ViewerFilter}, добавляет раскраску вхождений. История —
 * {@link FilterInputBox#attachHistory} / {@link FilterInputBox.Scope#SET_FILTER_DIALOG}.
 *
 * <p>Смена фильтра идёт через {@code addViewerFilter}/{@code removeViewerFilter}
 * панели, чтобы {@code CheckboxTreeViewer} не терял пометки (как в
 * {@link FilterBySubsystemsDialogHook}).
 */
public final class SetFilterDialogFilterHook implements IStartup
{
    private static final String TAG = "SetFilterDialogFilter"; //$NON-NLS-1$

    private static final String HOOKED_KEY = "tormozit.setFilterDialogFilterHooked"; //$NON-NLS-1$

    private static final String PATCHED_KEY = "tormozit.setFilterDialogFilterPatched"; //$NON-NLS-1$

    private static final String LAST_PATTERN_KEY = "tormozit.setFilterDialogLastPattern"; //$NON-NLS-1$

    private static final int MAX_ATTEMPTS = 20;

    private static final int RETRY_MS = 50;

    private static final String[] PANEL_FIELDS = {
        "objectsPanel", //$NON-NLS-1$
        "rolesPanel", //$NON-NLS-1$
        "rightsPanel" //$NON-NLS-1$
    };

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> install(display));
    }

    private static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            if (Boolean.TRUE.equals(shell.getData(HOOKED_KEY)))
                return;
            if (!isTargetShell(shell))
                return;
            shell.setData(HOOKED_KEY, Boolean.TRUE);
            scheduleTryPatch(display, shell, 0);
        };
        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
        Debug.log("install: Show/Activate"); //$NON-NLS-1$
    }

    private static void scheduleTryPatch(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : RETRY_MS;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed())
                return;
            if (tryPatchShell(shell))
                return;
            if (attempt < MAX_ATTEMPTS)
                scheduleTryPatch(display, shell, attempt + 1);
        });
    }

    private static boolean tryPatchShell(Shell shell)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return true;
        Object dialog = resolveDialog(shell);
        if (dialog == null)
            return false;

        boolean foundPanel = false;
        boolean allOk = true;
        for (String field : PANEL_FIELDS)
        {
            Object panel = Global.getField(dialog, field);
            if (panel == null)
                continue;
            foundPanel = true;
            if (!tryPatchPanel(panel))
                allOk = false;
        }
        return foundPanel && allOk;
    }

    private static boolean tryPatchPanel(Object panel)
    {
        Object searchBoxObj = Global.getField(panel, "searchBox"); //$NON-NLS-1$
        Object viewerObj = Global.getField(panel, "viewer"); //$NON-NLS-1$
        if (!(searchBoxObj instanceof SearchBox searchBox) || searchBox.isDisposed())
            return false;
        if (!(viewerObj instanceof CheckboxTreeViewer viewer))
            return false;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return false;
        if (Boolean.TRUE.equals(tree.getData(PATCHED_KEY)))
            return true;

        LabelHighlight highlight = installHighlight(viewer);
        if (highlight == null)
            return false;

        SmartSearchFilter filter = new SmartSearchFilter(highlight);
        tree.setData(PATCHED_KEY, Boolean.TRUE);
        tree.setData(LAST_PATTERN_KEY, ""); //$NON-NLS-1$
        int[] applyGeneration = { 0 };

        searchBox.setToolTipText(
            FilterInputBox.SET_FILTER_DIALOG_TOOLTIP + "\nCtrl+↓ — история запросов."); //$NON-NLS-1$
        searchBox.setMinimumSearchTextLength(0);
        searchBox.setJobScheduleDelay(0);
        FilterInputBox.attachHistory(searchBox, FilterInputBox.Scope.SET_FILTER_DIALOG);
        FilterInputBoxListNavigation.installTreeNavigation(searchBox, tree);
        searchBox.setRunSearchOnTextChange(false);
        searchBox.setSearchListener(new SearchBox.ISearchListener()
        {
            @Override
            public void performSearch(String text, IProgressMonitor monitor)
            {
                scheduleApply(searchBox, panel, viewer, filter, highlight, applyGeneration);
            }
        });
        searchBox.addModifyListener(e ->
        {
            if (searchBox.isDisposed())
                return;
            scheduleApply(searchBox, panel, viewer, filter, highlight, applyGeneration);
        });

        String initial = searchBox.getText();
        if (initial != null && !initial.isEmpty())
            apply(searchBox, panel, viewer, filter, highlight);

        Debug.log("tryPatchPanel PATCH OK " + panel.getClass().getSimpleName()); //$NON-NLS-1$
        return true;
    }

    private static void scheduleApply(SearchBox searchBox, Object panel, CheckboxTreeViewer viewer,
        SmartSearchFilter filter, LabelHighlight highlight, int[] applyGeneration)
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
            apply(searchBox, panel, viewer, filter, highlight);
        });
    }

    private static void apply(SearchBox searchBox, Object panel, CheckboxTreeViewer viewer,
        SmartSearchFilter filter, LabelHighlight highlight)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        String pattern = searchBox != null && !searchBox.isDisposed()
            ? searchBox.getText().trim() : ""; //$NON-NLS-1$
        String last = tree.getData(LAST_PATTERN_KEY) instanceof String lastStr ? lastStr : ""; //$NON-NLS-1$
        if (pattern.equals(last))
            return;

        highlight.setHighlightPattern(pattern);
        filter.setPattern(pattern);

        Object nativeFilterObj = Global.getField(panel, "searchFilterWithHistory"); //$NON-NLS-1$
        if (nativeFilterObj instanceof ViewerFilter nativeFilter
            && Arrays.asList(viewer.getFilters()).contains(nativeFilter))
            Global.invokeVoid(panel, "removeViewerFilter", nativeFilter); //$NON-NLS-1$

        boolean filtering = !pattern.isEmpty();
        List<ViewerFilter> current = Arrays.asList(viewer.getFilters());
        if (filtering)
        {
            Global.invokeVoid(panel, "addViewerFilter", filter); //$NON-NLS-1$
            viewer.expandAll();
        }
        else if (current.contains(filter))
        {
            ISelection selection = viewer.getSelection();
            Global.invokeVoid(panel, "removeViewerFilter", filter); //$NON-NLS-1$
            if (selection != null && !selection.isEmpty() && !tree.isDisposed())
                viewer.setSelection(selection, true);
        }

        tree.setData(LAST_PATTERN_KEY, pattern);
    }

    private static LabelHighlight installHighlight(TreeViewer viewer)
    {
        IBaseLabelProvider raw = viewer.getLabelProvider();
        if (!(raw instanceof DelegatingStyledCellLabelProvider delegating))
            return null;
        IStyledLabelProvider inner = delegating.getStyledStringProvider();
        if (inner instanceof LabelHighlight existing)
        {
            SmartMatchHighlight.enableColorsOnSelection(delegating);
            return existing;
        }
        if (inner == null)
            return null;
        LabelHighlight highlight = new LabelHighlight(inner);
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

    private static boolean isTargetShell(Shell shell)
    {
        return resolveDialog(shell) != null;
    }

    private static Object resolveDialog(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;
        Object fromShell = resolveDialogOnWidget(shell);
        if (fromShell != null)
            return fromShell;
        return resolveDialogInComposite(shell);
    }

    private static Object resolveDialogInComposite(Composite root)
    {
        if (root == null || root.isDisposed())
            return null;
        Object onRoot = resolveDialogOnWidget(root);
        if (onRoot != null)
            return onRoot;
        for (Control child : root.getChildren())
        {
            if (child.isDisposed())
                continue;
            Object onChild = resolveDialogOnWidget(child);
            if (onChild != null)
                return onChild;
            if (child instanceof Composite composite)
            {
                Object nested = resolveDialogInComposite(composite);
                if (nested != null)
                    return nested;
            }
        }
        return null;
    }

    private static Object resolveDialogOnWidget(org.eclipse.swt.widgets.Widget widget)
    {
        if (widget == null || widget.isDisposed())
            return null;
        for (String key : new String[] { null, "org.eclipse.jface.window.Window", //$NON-NLS-1$
            "org.eclipse.jface.dialogs.Dialog.dialog" }) //$NON-NLS-1$
        {
            Object data = key == null ? widget.getData() : widget.getData(key);
            if (isTargetDialog(data))
                return data;
        }
        return null;
    }

    private static boolean isTargetDialog(Object data)
    {
        if (data == null)
            return false;
        String name = data.getClass().getName();
        return name.endsWith(".SetFilterDialog") //$NON-NLS-1$
            || name.endsWith(".SetFilterByRightsDialog"); //$NON-NLS-1$
    }

    /**
     * Узел виден, если {@link SmartMatcher} совпал с его видимой подписью или с подписью
     * любого потомка — как штатный {@code InMemorySearchFilter}.
     */
    private static final class SmartSearchFilter extends ViewerFilter
    {
        private final LabelHighlight labels;

        private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$

        private final Map<Object, Boolean> subtreeMemo = new IdentityHashMap<>();

        SmartSearchFilter(LabelHighlight labels)
        {
            this.labels = labels;
        }

        void setPattern(String pattern)
        {
            matcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
            subtreeMemo.clear();
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            if (matcher.isEmpty)
                return true;
            return matchesOwnOrDescendant(viewer, element);
        }

        private boolean matchesOwnOrDescendant(Viewer viewer, Object element)
        {
            Boolean memo = subtreeMemo.get(element);
            if (memo != null)
                return memo.booleanValue();

            String text = labels.plainText(element);
            boolean result = text != null && !text.isEmpty() && matcher.matches(text);
            if (!result && viewer instanceof TreeViewer treeViewer
                && treeViewer.getContentProvider() instanceof ITreeContentProvider tcp)
            {
                Object[] children = tcp.getChildren(element);
                if (children != null)
                {
                    for (Object child : children)
                    {
                        if (matchesOwnOrDescendant(viewer, child))
                        {
                            result = true;
                            break;
                        }
                    }
                }
            }
            subtreeMemo.put(element, result);
            return result;
        }
    }

    /**
     * Раскраска вхождений поверх штатного {@code IStyledLabelProvider}. Цвет/шрифт/
     * подсказку делегирует ему же.
     */
    private static final class LabelHighlight implements IStyledLabelProvider, SmartLabelHighlight,
        IColorProvider, IFontProvider, IToolTipProvider
    {
        private final IStyledLabelProvider delegate;

        private SmartMatcher highlightMatcher = new SmartMatcher(""); //$NON-NLS-1$

        LabelHighlight(IStyledLabelProvider delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public void setHighlightPattern(String pattern)
        {
            highlightMatcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
        }

        String plainText(Object element)
        {
            StyledString styled = delegate.getStyledText(element);
            return styled != null ? styled.getString() : null;
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            StyledString styled = delegate.getStyledText(element);
            if (styled == null)
                styled = new StyledString();
            if (highlightMatcher.isEmpty)
                return styled;
            String text = styled.getString();
            if (text == null || text.isEmpty() || !highlightMatcher.matches(text))
                return styled;
            SmartMatchHighlight.applyRanges(styled, highlightMatcher.getHighlightRanges(text));
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

    private static final class Debug
    {
        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
