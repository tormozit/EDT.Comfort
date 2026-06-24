package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.TypedListener;

import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;

import java.util.List;

/**
 * Клавиатура полей фильтра {@link com._1c.g5.v8.dt.common.ui.controls.search.SearchBox} (Комфорт):
 * <ul>
 * <li>↓/↑/PgUp/PgDn — навигация по связанному списку/дереву, фокус в фильтре</li>
 * <li>Enter — primary action по текущей строке ({@link EnterListener}), не перевод фокуса</li>
 * <li>Ctrl+↓ — история запросов с выделением первого пункта</li>
 * <li>Enter при открытом popup — применить выделенный пункт истории</li>
 * </ul>
 */
public final class FilterInputBoxListNavigation
{
    private static final String INSTALLED_KEY = "tormozit.filterFieldNavInstalled"; //$NON-NLS-1$
    private static final String SEARCH_BOX_KEY_GUARD_KEY = "tormozit.searchBoxKeyGuard"; //$NON-NLS-1$
    private static final String SEARCH_BOX_KEY_DOWN_GUARD_KEY = "tormozit.searchBoxKeyDownGuard"; //$NON-NLS-1$
    private static final String SEARCH_BOX_STOCK_KEY_STRIPPED_KEY = "tormozit.searchBoxStockKeyStripped"; //$NON-NLS-1$
    private static final String SEARCH_BOX_TRAVERSE_GUARD_KEY = "tormozit.searchBoxTraverseGuard"; //$NON-NLS-1$
    private static final String SUPPRESS_PRIMARY_ENTER_KEY = "tormozit.suppressFilterPrimaryEnter"; //$NON-NLS-1$
    private static final String NAV_CONTEXT_KEY = "tormozit.filterNavContext"; //$NON-NLS-1$

    private static volatile boolean displayTraverseFilterInstalled;

    private static final class NavContext
    {
        Table table;
        TableIndexListener tableListener;
        Tree tree;
        EnterListener enterListener;
        boolean focusListOnEnter = false;
    }

    @FunctionalInterface
    public interface TableIndexListener
    {
        void onIndexChanged(int newIdx);
    }

    @FunctionalInterface
    public interface EnterListener
    {
        /** @return {@code true}, если Enter обработан и не нужно штатное действие */
        boolean onEnter();
    }

    private FilterInputBoxListNavigation() {}

    public static boolean isNavigationKey(int keyCode)
    {
        return keyCode == SWT.ARROW_DOWN || keyCode == SWT.ARROW_UP
                || keyCode == SWT.PAGE_DOWN || keyCode == SWT.PAGE_UP;
    }

    public static void installTableNavigation(Text filterText, Table table)
    {
        installTableNavigation(filterText, table, null);
    }

    public static void installTableNavigation(Text filterText, Table table, TableIndexListener onIndexChanged)
    {
        installTableNavigation((Control) filterText, table, onIndexChanged);
    }

    public static void installTableNavigation(Control filterControl, Table table, TableIndexListener onIndexChanged)
    {
        installTableNavigation(filterControl, table, onIndexChanged, false);
    }

    public static void installTableNavigation(Control filterControl, Table table,
            TableIndexListener onIndexChanged, boolean focusListOnEnter)
    {
        installTableNavigation(filterControl, table, onIndexChanged, focusListOnEnter, null);
    }

    /** Enter из фильтра — {@code primaryAction} (как двойной клик по строке). */
    public static void installTableOpenOnEnter(Control filterControl, Table table,
            TableIndexListener onIndexChanged, Runnable primaryAction)
    {
        installTableNavigation(filterControl, table, onIndexChanged, false, () -> {
            if (primaryAction != null)
                primaryAction.run();
            return true;
        });
    }

    public static void installTableNavigation(Control filterControl, Table table,
            TableIndexListener onIndexChanged, boolean focusListOnEnter, EnterListener onEnter)
    {
        if (filterControl == null || table == null)
            return;
        SearchBox searchBox = resolveSearchBox(filterControl);
        if (searchBox != null)
        {
            NavContext ctx = new NavContext();
            ctx.table = table;
            ctx.tableListener = onIndexChanged;
            ctx.focusListOnEnter = focusListOnEnter;
            ctx.enterListener = onEnter;
            searchBox.setData(NAV_CONTEXT_KEY, ctx);
            installSearchBoxKeyGuard(searchBox);
            searchBox.setData(INSTALLED_KEY, Boolean.TRUE);
            return;
        }
        if (Boolean.TRUE.equals(filterControl.getData(INSTALLED_KEY)))
            return;
        filterControl.setData(INSTALLED_KEY, Boolean.TRUE);

        filterControl.addListener(SWT.KeyDown, event -> {
            if (handleCtrlDownHistory(event, filterControl))
                return;
            SearchBox searchBoxPopup = resolveSearchBox(filterControl);
            if (searchBoxPopup != null && Boolean.TRUE.equals(Global.getField(searchBoxPopup, "displayingPopup"))) //$NON-NLS-1$
            {
                if (isEnterKey(event.keyCode) && applyPopupHistoryItem(searchBoxPopup))
                {
                    event.doit = false;
                    return;
                }
            }
            else if (isSearchBoxPopupActive(searchBoxPopup))
                return;
            if (isEnterKey(event.keyCode))
            {
                if (onEnter != null && onEnter.onEnter())
                {
                    event.doit = false;
                    return;
                }
                if (focusListOnEnter && focusTableFromFilter(table))
                {
                    event.doit = false;
                    return;
                }
            }
            if (!isNavigationKey(event.keyCode))
                return;
            int newIdx = computeTableNavIndex(table, event.keyCode);
            if (newIdx >= 0)
            {
                if (onIndexChanged != null)
                    onIndexChanged.onIndexChanged(newIdx);
                else
                    navigateTable(table, event.keyCode);
            }
            event.doit = false;
            keepFilterFocus(filterControl);
        });
        filterControl.addListener(SWT.Traverse, event -> {
            if (handleCtrlDownHistoryTraverse(event, filterControl))
                return;
            if (isSearchBoxPopupActive(resolveSearchBox(filterControl)))
                return;
            if (event.detail == SWT.TRAVERSE_RETURN)
            {
                if (onEnter != null && onEnter.onEnter())
                {
                    event.doit = false;
                    event.detail = SWT.TRAVERSE_NONE;
                    return;
                }
                if (focusListOnEnter && focusTableFromFilter(table))
                {
                    event.doit = false;
                    event.detail = SWT.TRAVERSE_NONE;
                    return;
                }
            }
            if (!isNavigationKey(event.keyCode))
                return;
            event.doit = false;
            event.detail = SWT.TRAVERSE_NONE;
        });
    }

    private static boolean isEnterKey(int keyCode)
    {
        return keyCode == SWT.CR || keyCode == SWT.KEYPAD_CR;
    }

    /** Enter из поля фильтра — фокус в таблицу, при необходимости выбрать первую строку. */
    public static boolean focusTableFromFilter(Table table)
    {
        if (table == null || table.isDisposed() || table.getItemCount() == 0)
            return false;
        if (table.getSelectionIndex() < 0)
        {
            table.setSelection(0);
            fireTableSelection(table);
        }
        table.setFocus();
        table.showSelection();
        return true;
    }

    /**
     * @return новый индекс выделения или {@code -1}, если навигация не выполнена
     */
    public static int navigateTable(Table table, int keyCode)
    {
        int newIdx = computeTableNavIndex(table, keyCode);
        if (newIdx < 0)
            return -1;
        table.setSelection(newIdx);
        table.showSelection();
        fireTableSelection(table);
        return newIdx;
    }

    /** Синхронизация {@link org.eclipse.jface.viewers.TableViewer} и строки статуса после программного выбора. */
    private static void fireTableSelection(Table table)
    {
        if (table == null || table.isDisposed())
            return;
        TableItem[] selected = table.getSelection();
        if (selected.length == 0)
            return;
        Event event = new Event();
        event.type = SWT.Selection;
        event.widget = table;
        event.item = selected[0];
        table.notifyListeners(SWT.Selection, event);
    }

    private static int computeTableNavIndex(Table table, int keyCode)
    {
        if (table == null || table.isDisposed() || table.getItemCount() == 0)
            return -1;

        int idx = table.getSelectionIndex();
        int count = table.getItemCount();
        int itemH = table.getItemHeight();
        int page = itemH > 0 ? Math.max(1, table.getClientArea().height / itemH) : 10;

        return switch (keyCode)
        {
            case SWT.ARROW_DOWN -> Math.min(idx + 1, count - 1);
            case SWT.ARROW_UP -> Math.max(idx - 1, 0);
            case SWT.PAGE_DOWN -> Math.min(idx + page, count - 1);
            case SWT.PAGE_UP -> Math.max(idx - page, 0);
            default -> -1;
        };
    }

    public static void installTreeNavigation(Text filterText, Tree tree)
    {
        installTreeNavigation((Control) filterText, tree);
    }

    public static void installTreeNavigation(StyledText filterText, Tree tree)
    {
        installTreeNavigation((Control) filterText, tree);
    }

    public static void installTreeNavigation(Control filterControl, Tree tree)
    {
        installTreeNavigation(filterControl, tree, null);
    }

    public static void installTreeNavigation(Control filterControl, Tree tree, EnterListener onEnter)
    {
        if (filterControl == null || tree == null)
            return;
        SearchBox searchBox = resolveSearchBox(filterControl);
        if (searchBox != null)
        {
            NavContext ctx = new NavContext();
            ctx.tree = tree;
            ctx.enterListener = onEnter;
            searchBox.setData(NAV_CONTEXT_KEY, ctx);
            installSearchBoxKeyGuard(searchBox);
            searchBox.setData(INSTALLED_KEY, Boolean.TRUE);
            return;
        }
        if (Boolean.TRUE.equals(filterControl.getData(INSTALLED_KEY)))
            return;
        filterControl.setData(INSTALLED_KEY, Boolean.TRUE);

        filterControl.addListener(SWT.KeyDown, event -> {
            if (handleCtrlDownHistory(event, filterControl))
                return;
            if (isSearchBoxPopupActive(resolveSearchBox(filterControl)))
                return;
            if (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)
            {
                if (onEnter != null && onEnter.onEnter())
                {
                    event.doit = false;
                    return;
                }
            }
            if (!isNavigationKey(event.keyCode))
                return;
            navigateTree(tree, event.keyCode);
            event.doit = false;
            keepFilterFocus(filterControl);
        });
        filterControl.addListener(SWT.Traverse, event -> {
            if (handleCtrlDownHistoryTraverse(event, filterControl))
                return;
            if (isSearchBoxPopupActive(resolveSearchBox(filterControl)))
                return;
            if (event.detail == SWT.TRAVERSE_RETURN && onEnter != null && onEnter.onEnter())
            {
                event.doit = false;
                event.detail = SWT.TRAVERSE_NONE;
                return;
            }
            if (!isNavigationKey(event.keyCode))
                return;
            event.doit = false;
            event.detail = SWT.TRAVERSE_NONE;
        });
    }

    /** Ctrl+↓ — выпадающий список истории {@link SearchBox}, первый пункт выделен. */
    public static boolean openSearchHistory(Control filterControl)
    {
        SearchBox box = resolveSearchBox(filterControl);
        if (box == null || box.isDisposed())
            return false;
        Global.invoke(box, "displayPopup"); //$NON-NLS-1$
        if (!Boolean.TRUE.equals(Global.getField(box, "displayingPopup"))) //$NON-NLS-1$
            return false;
        Object itemsObj = Global.invoke(box, "getPopupItems"); //$NON-NLS-1$
        if (!(itemsObj instanceof List<?> items) || items.isEmpty())
            return true;
        Global.setField(box, "selectedPopupItem", 0); //$NON-NLS-1$
        Object popup = Global.getField(box, "popup"); //$NON-NLS-1$
        if (popup instanceof Shell shell && !shell.isDisposed())
            shell.redraw();
        return true;
    }

    /**
     * {@link SearchBox} extends {@link StyledText}: каретку блокирует только
     * {@link org.eclipse.swt.custom.VerifyKeyListener}, а stock {@code KeyListener} на ↓ открывает историю.
     */
    static void installSearchBoxKeyGuard(SearchBox box)
    {
        if (box == null || box.isDisposed())
            return;
        ensureDisplayTraverseFilter();
        stripSearchBoxStockKeyListener(box);
        if (!Boolean.TRUE.equals(box.getData(SEARCH_BOX_KEY_GUARD_KEY)))
        {
            box.setData(SEARCH_BOX_KEY_GUARD_KEY, Boolean.TRUE);
            box.addVerifyKeyListener(e -> handleSearchBoxVerifyKey(box, e));
        }
        if (!Boolean.TRUE.equals(box.getData(SEARCH_BOX_KEY_DOWN_GUARD_KEY)))
        {
            box.setData(SEARCH_BOX_KEY_DOWN_GUARD_KEY, Boolean.TRUE);
            box.addListener(SWT.KeyDown, event -> dismissSearchBoxPopupAfterStockNav(box, event));
        }
        if (!Boolean.TRUE.equals(box.getData(SEARCH_BOX_TRAVERSE_GUARD_KEY)))
        {
            box.setData(SEARCH_BOX_TRAVERSE_GUARD_KEY, Boolean.TRUE);
            box.addListener(SWT.Traverse, event -> handleSearchBoxTraverse(box, event));
        }
    }

    /** Снять stock {@code KeyListener} сразу после {@link SearchBox} — до {@link #installTableNavigation}. */
    static void ensureSearchBoxStockKeyStripped(SearchBox box)
    {
        stripSearchBoxStockKeyListener(box);
    }

    private static void handleSearchBoxVerifyKey(SearchBox box, VerifyEvent event)
    {
        if (Boolean.TRUE.equals(Global.getField(box, "displayingPopup"))) //$NON-NLS-1$
        {
            if (isEnterKey(event.keyCode))
            {
                if (applyPopupHistoryItem(box))
                {
                    event.doit = false;
                    return;
                }
            }
            if (handleSearchBoxPopupNavigation(box, event))
                return;
        }

        NavContext ctx = box.getData(NAV_CONTEXT_KEY) instanceof NavContext nav ? nav : null;
        if (ctx == null)
            return;

        if (event.keyCode == SWT.ARROW_DOWN && (event.stateMask & SWT.CTRL) != 0)
        {
            openSearchHistory(box);
            event.doit = false;
            return;
        }
        if (isEnterKey(event.keyCode))
        {
            if (isPrimaryEnterSuppressed(box))
            {
                event.doit = false;
                return;
            }
            if (ctx.enterListener != null && ctx.enterListener.onEnter())
            {
                event.doit = false;
                return;
            }
            if (ctx.focusListOnEnter && ctx.table != null && focusTableFromFilter(ctx.table))
            {
                event.doit = false;
                return;
            }
        }
        if (!isNavigationKey(event.keyCode))
            return;
        if (ctx.table != null)
        {
            int newIdx = computeTableNavIndex(ctx.table, event.keyCode);
            if (newIdx >= 0)
            {
                if (ctx.tableListener != null)
                    ctx.tableListener.onIndexChanged(newIdx);
                else
                    navigateTable(ctx.table, event.keyCode);
            }
        }
        else if (ctx.tree != null)
        {
            navigateTree(ctx.tree, event.keyCode);
        }
        keepFilterFocus(box);
        event.doit = false;
    }

    /** @return {@code true}, если клавиша обработана навигацией по открытому popup истории */
    private static boolean handleSearchBoxPopupNavigation(SearchBox box, VerifyEvent event)
    {
        if (!isNavigationKey(event.keyCode))
            return false;
        if (!Boolean.TRUE.equals(Global.getField(box, "displayingPopup"))) //$NON-NLS-1$
            return false;
        Object itemsObj = Global.invoke(box, "getPopupItems"); //$NON-NLS-1$
        if (!(itemsObj instanceof List<?> items) || items.isEmpty())
            return false;
        Object selObj = Global.getField(box, "selectedPopupItem"); //$NON-NLS-1$
        int sel = selObj instanceof Integer i ? i : 0;
        int newSel = sel;
        if (event.keyCode == SWT.ARROW_DOWN)
            newSel = Math.min(sel + 1, items.size() - 1);
        else if (event.keyCode == SWT.ARROW_UP)
            newSel = Math.max(sel - 1, 0);
        else
            return false;
        if (newSel != sel)
            Global.setField(box, "selectedPopupItem", newSel); //$NON-NLS-1$
        Object popup = Global.getField(box, "popup"); //$NON-NLS-1$
        if (popup instanceof Shell shell && !shell.isDisposed())
            shell.redraw();
        event.doit = false;
        return true;
    }

    /** Enter в открытом popup — подставить выделенный пункт истории и закрыть список. */
    private static boolean applyPopupHistoryItem(SearchBox box)
    {
        if (box == null || box.isDisposed())
            return false;
        if (!Boolean.TRUE.equals(Global.getField(box, "displayingPopup"))) //$NON-NLS-1$
            return false;
        Object itemsObj = Global.invoke(box, "getPopupItems"); //$NON-NLS-1$
        if (!(itemsObj instanceof List<?> items) || items.isEmpty())
            return false;
        Object selObj = Global.getField(box, "selectedPopupItem"); //$NON-NLS-1$
        int sel = selObj instanceof Integer i ? i : -1;
        if (sel < 0)
            sel = 0;
        if (sel >= items.size())
            sel = 0;
        Object entry = items.get(sel);
        if (entry instanceof String text)
            setSearchBoxFilterText(box, text);
        Global.invoke(box, "hidePopup"); //$NON-NLS-1$
        Global.invoke(box, "performSearch"); //$NON-NLS-1$
        if (entry instanceof String text)
            moveSearchBoxCaretToEnd(box, text);
        suppressPrimaryEnterOnce(box);
        return true;
    }

    private static void setSearchBoxFilterText(SearchBox box, String text)
    {
        String value = text != null ? text : ""; //$NON-NLS-1$
        box.setText(value);
    }

    private static void moveSearchBoxCaretToEnd(SearchBox box, String text)
    {
        if (box == null || box.isDisposed())
            return;
        int len = text != null ? text.length() : box.getText().length();
        box.setSelection(len, len);
    }

    private static void ensureDisplayTraverseFilter()
    {
        if (displayTraverseFilterInstalled)
            return;
        Display display = Display.getCurrent();
        if (display == null)
            display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        displayTraverseFilterInstalled = true;
        display.addFilter(SWT.Traverse, FilterInputBoxListNavigation::displayTraverseFilter);
    }

    /**
     * До stock {@link SearchBox} {@code TraverseListener}: Enter при открытом popup истории
     * не должен уходить в default button диалога.
     */
    private static void displayTraverseFilter(Event event)
    {
        if (!(event.widget instanceof SearchBox box) || box.isDisposed())
            return;
        if (!(box.getData(NAV_CONTEXT_KEY) instanceof NavContext))
            return;
        if (event.detail != SWT.TRAVERSE_RETURN)
            return;
        if (!Boolean.TRUE.equals(Global.getField(box, "displayingPopup"))) //$NON-NLS-1$
            return;
        if (applyPopupHistoryItem(box))
        {
            event.doit = false;
            event.detail = SWT.TRAVERSE_NONE;
        }
    }

    private static void suppressPrimaryEnterOnce(Control control)
    {
        if (control == null || control.isDisposed())
            return;
        control.setData(SUPPRESS_PRIMARY_ENTER_KEY, Boolean.TRUE);
        Display display = control.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            if (!control.isDisposed())
                control.setData(SUPPRESS_PRIMARY_ENTER_KEY, null);
        });
    }

    private static boolean isPrimaryEnterSuppressed(Control control)
    {
        return control != null && Boolean.TRUE.equals(control.getData(SUPPRESS_PRIMARY_ENTER_KEY));
    }

    private static void handleSearchBoxTraverse(SearchBox box, Event event)
    {
        if (event.detail != SWT.TRAVERSE_RETURN)
            return;
        if (Boolean.TRUE.equals(Global.getField(box, "displayingPopup"))) //$NON-NLS-1$
        {
            if (applyPopupHistoryItem(box))
            {
                event.doit = false;
                event.detail = SWT.TRAVERSE_NONE;
            }
            return;
        }
        if (isPrimaryEnterSuppressed(box))
        {
            event.doit = false;
            event.detail = SWT.TRAVERSE_NONE;
            return;
        }
        NavContext ctx = box.getData(NAV_CONTEXT_KEY) instanceof NavContext nav ? nav : null;
        if (ctx != null && ctx.enterListener != null && ctx.enterListener.onEnter())
        {
            event.doit = false;
            event.detail = SWT.TRAVERSE_NONE;
        }
    }

    /** После stock {@code KeyDown}: закрыть popup/quick browse, если ↓ успел открыть историю. */
    private static void dismissSearchBoxPopupAfterStockNav(SearchBox box, Event event)
    {
        NavContext ctx = box.getData(NAV_CONTEXT_KEY) instanceof NavContext nav ? nav : null;
        if (ctx == null)
            return;
        if (Boolean.TRUE.equals(Global.getField(box, "displayingPopup"))) //$NON-NLS-1$
        {
            if (isEnterKey(event.keyCode))
            {
                if (applyPopupHistoryItem(box))
                    event.doit = false;
                return;
            }
            if (isNavigationKey(event.keyCode))
                return;
        }
        if (event.keyCode == SWT.ARROW_DOWN && (event.stateMask & SWT.CTRL) != 0)
            return;
        if (!isNavigationKey(event.keyCode))
            return;
        dismissSearchBoxHistoryUi(box);
    }

    private static void dismissSearchBoxHistoryUi(SearchBox box)
    {
        if (box == null || box.isDisposed())
            return;
        if (Boolean.TRUE.equals(Global.getField(box, "displayingPopup"))) //$NON-NLS-1$
            Global.invoke(box, "hidePopup"); //$NON-NLS-1$
        if (Boolean.TRUE.equals(Global.getField(box, "displayingQuickBrowse"))) //$NON-NLS-1$
            Global.invoke(box, "hideQuickBrowse", Boolean.FALSE); //$NON-NLS-1$
    }

    private static boolean stripSearchBoxStockKeyListener(SearchBox box)
    {
        if (box == null || box.isDisposed())
            return false;
        if (Boolean.TRUE.equals(box.getData(SEARCH_BOX_STOCK_KEY_STRIPPED_KEY)))
            return true;
        boolean removed = false;
        for (int type : new int[] { SWT.KeyDown, SWT.KeyUp })
        {
            for (Listener listener : box.getListeners(type))
            {
                if (!isSearchBoxStockKeyListener(listener))
                    continue;
                box.removeListener(type, listener);
                removed = true;
            }
        }
        if (removed)
            box.setData(SEARCH_BOX_STOCK_KEY_STRIPPED_KEY, Boolean.TRUE);
        return removed;
    }

    private static boolean isSearchBoxStockKeyListener(Listener listener)
    {
        if (!(listener instanceof TypedListener typed))
            return false;
        Object inner = typed.getEventListener();
        if (inner == null)
            return false;
        return inner.getClass().getName().contains("SearchBox$"); //$NON-NLS-1$
    }

    private static boolean handleCtrlDownHistory(Event event, Control filterControl)
    {
        if (event.keyCode != SWT.ARROW_DOWN)
            return false;
        if ((event.stateMask & SWT.CTRL) == 0)
            return false;
        if (!openSearchHistory(filterControl))
            return false;
        event.doit = false;
        return true;
    }

    private static boolean handleCtrlDownHistoryTraverse(Event event, Control filterControl)
    {
        if (event.keyCode != SWT.ARROW_DOWN)
            return false;
        if ((event.stateMask & SWT.CTRL) == 0)
            return false;
        if (!openSearchHistory(filterControl))
            return false;
        event.doit = false;
        event.detail = SWT.TRAVERSE_NONE;
        return true;
    }

    private static SearchBox resolveSearchBox(Control control)
    {
        Control current = control;
        while (current != null && !current.isDisposed())
        {
            if (current instanceof SearchBox)
                return (SearchBox) current;
            current = current.getParent();
        }
        return null;
    }

    private static boolean isSearchBoxPopupActive(SearchBox box)
    {
        if (box == null || box.isDisposed())
            return false;
        if (Boolean.TRUE.equals(Global.getField(box, "displayingPopup"))) //$NON-NLS-1$
            return true;
        return Boolean.TRUE.equals(Global.getField(box, "displayingQuickBrowse")); //$NON-NLS-1$
    }

    /** После programmatic selection Tree/Table забирают фокус — возвращаем в поле фильтра. */
    private static void keepFilterFocus(Control filterControl)
    {
        if (filterControl == null || filterControl.isDisposed())
            return;
        Display display = filterControl.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            if (!filterControl.isDisposed())
                filterControl.forceFocus();
        });
    }

    public static void navigateTree(Tree tree, int keyCode)
    {
        if (tree == null || tree.isDisposed() || tree.getItemCount() == 0)
            return;

        TreeItem[] selection = tree.getSelection();
        TreeItem targetItem = null;

        int itemHeight = tree.getItemHeight();
        int pageSteps = itemHeight > 0 ? tree.getClientArea().height / itemHeight : 10;
        if (pageSteps <= 0)
            pageSteps = 10;

        if (selection.length == 0)
        {
            if (keyCode == SWT.ARROW_DOWN || keyCode == SWT.PAGE_DOWN)
                targetItem = tree.getItem(0);
            else
                targetItem = getLastVisibleItem(tree);
        }
        else
        {
            TreeItem current = selection[0];

            if (keyCode == SWT.ARROW_DOWN)
                targetItem = getNextVisibleItem(tree, current);
            else if (keyCode == SWT.ARROW_UP)
                targetItem = getPreviousVisibleItem(tree, current);
            else if (keyCode == SWT.PAGE_DOWN)
            {
                targetItem = current;
                for (int i = 0; i < pageSteps; i++)
                {
                    TreeItem next = getNextVisibleItem(tree, targetItem);
                    if (next == null)
                        break;
                    targetItem = next;
                }
            }
            else if (keyCode == SWT.PAGE_UP)
            {
                targetItem = current;
                for (int i = 0; i < pageSteps; i++)
                {
                    TreeItem prev = getPreviousVisibleItem(tree, targetItem);
                    if (prev == null)
                        break;
                    targetItem = prev;
                }
            }
        }

        if (targetItem != null && !targetItem.isDisposed())
        {
            tree.setSelection(targetItem);
            tree.showItem(targetItem);

            Event selectionEvent = new Event();
            selectionEvent.widget = tree;
            selectionEvent.item = targetItem;
            tree.notifyListeners(SWT.Selection, selectionEvent);
        }
    }

    private static TreeItem getNextVisibleItem(Tree tree, TreeItem item)
    {
        if (item.getExpanded() && item.getItemCount() > 0)
            return item.getItem(0);
        return getNextSiblingOrParentSibling(tree, item);
    }

    private static TreeItem getNextSiblingOrParentSibling(Tree tree, TreeItem item)
    {
        TreeItem parent = item.getParentItem();
        TreeItem[] siblings = parent == null ? tree.getItems() : parent.getItems();

        int index = indexOfItem(siblings, item);
        if (index >= 0 && index < siblings.length - 1)
            return siblings[index + 1];

        if (parent != null)
            return getNextSiblingOrParentSibling(tree, parent);
        return null;
    }

    private static TreeItem getPreviousVisibleItem(Tree tree, TreeItem item)
    {
        TreeItem parent = item.getParentItem();
        TreeItem[] siblings = parent == null ? tree.getItems() : parent.getItems();

        int index = indexOfItem(siblings, item);
        if (index > 0)
        {
            TreeItem prevSibling = siblings[index - 1];
            return getLastVisibleDescendant(prevSibling);
        }
        return parent;
    }

    private static TreeItem getLastVisibleDescendant(TreeItem item)
    {
        if (item.getExpanded() && item.getItemCount() > 0)
            return getLastVisibleDescendant(item.getItem(item.getItemCount() - 1));
        return item;
    }

    private static TreeItem getLastVisibleItem(Tree tree)
    {
        if (tree.getItemCount() == 0)
            return null;
        TreeItem lastRoot = tree.getItem(tree.getItemCount() - 1);
        return getLastVisibleDescendant(lastRoot);
    }

    private static int indexOfItem(TreeItem[] items, TreeItem item)
    {
        for (int i = 0; i < items.length; i++)
        {
            if (items[i] == item)
                return i;
        }
        return -1;
    }
}
