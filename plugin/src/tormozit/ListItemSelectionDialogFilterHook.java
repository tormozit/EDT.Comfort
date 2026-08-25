package tormozit;

import java.util.List;

import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.ui.dialog.ListItemSelectionDialog;

/**
 * Многословный фильтр ({@link SmartMatcher}, плоский AND) + подсветка совпадений + персистентная
 * история в диалоге {@link ListItemSelectionDialog} EDT («Выбор объекта» — например функциональные
 * опции в панели «Свойства», выбор общей формы и т.п.).
 *
 * <p>Диалог создаётся в AEF-инфраструктуре свойств ({@code AbstractSelectionFromListDialogComponent}).
 * Разведка декомпиляцией байткода ({@code com._1c.g5.v8.dt.ui}, {@code com._1c.g5.v8.dt.common.ui})
 * показала: штатное поле фильтра — приватное поле {@code search} типа
 * {@code com._1c.g5.v8.dt.common.ui.search.SearchTextWithClearButton} (сам является
 * {@link StyledText}, не обёрткой над отдельным {@link Text}), список — приватное поле
 * {@code elementsTableViewer} типа {@link TableViewer} (в режиме множественного выбора — под
 * капотом {@link org.eclipse.jface.viewers.CheckboxTableViewer} со стилем {@code SWT.CHECK}).
 * Штатная фильтрация — {@code TextListViewerFilter} (сплит по «,[]», без AND по словам) —
 * снимается и заменяется собственным {@link ViewerFilter} поверх {@link SmartMatcher}.
 *
 * <p>В этом же {@code TableViewer} независимо патчит label provider
 * {@link FormMainAttributeTypeDecorator} (суффикс типа основного реквизита формы). Оба хука
 * оборачивают провайдер через {@link SelectionAwareStyledCellLabelProvider#unwrapOrAdapt}, поэтому
 * порядок патчей (оба слушают {@code SWT.Show}/{@code SWT.Activate} на одном {@code Shell})
 * не важен — каждый достраивает поверх того, что уже есть.
 *
 * <p>Каждое открытие диалога создаёт новый экземпляр — патчим по факту показа, состояние между
 * вызовами не нужно.
 */
public final class ListItemSelectionDialogFilterHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.listItemSelectionDialogFilterPatched"; //$NON-NLS-1$
    private static final String HIGHLIGHT_PATCHED_DATA = "tormozit.listItemSelectionDialogHighlightPatched"; //$NON-NLS-1$
    private static final String WINDOW_KEY = "org.eclipse.jface.window.Window"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    private static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        Listener listener = event -> {
            if (event.widget instanceof Shell shell && !shell.isDisposed())
                onShellEvent(shell);
        };
        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
    }

    private static void onShellEvent(Shell shell)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        if (Boolean.TRUE.equals(shell.getData(PATCHED_KEY)))
            return;
        if (resolveDialog(shell) == null)
            return;
        schedulePatch(shell, 0);
    }

    private static void schedulePatch(Shell shell, int attempt)
    {
        Display display = shell.getDisplay();
        int delay = attempt == 0 ? 0 : 50;
        display.timerExec(delay, () -> {
            if (shell.isDisposed() || Boolean.TRUE.equals(shell.getData(PATCHED_KEY)))
                return;
            if (!tryPatch(shell) && attempt < 20)
                schedulePatch(shell, attempt + 1);
        });
    }

    private static boolean tryPatch(Shell shell)
    {
        ListItemSelectionDialog dialog = resolveDialog(shell);
        if (dialog == null)
            return true; // диалог уже закрыт

        Object viewerObj = Global.getField(dialog, "elementsTableViewer"); //$NON-NLS-1$
        if (!(viewerObj instanceof TableViewer viewer)
                || viewer.getControl() == null
                || viewer.getControl().isDisposed())
            return false;
        Table table = viewer.getTable();

        Object searchObj = Global.getField(dialog, "search"); //$NON-NLS-1$
        if (!(searchObj instanceof Control searchControl) || searchControl.isDisposed())
            return false;

        shell.setData(PATCHED_KEY, Boolean.TRUE);

        FilterState state = new FilterState();

        for (ViewerFilter filter : viewer.getFilters())
        {
            if (filter != null && filter.getClass().getName().contains("TextListViewerFilter")) //$NON-NLS-1$
                viewer.removeFilter(filter);
        }
        viewer.addFilter(new SmartAndFilter(state));

        installHighlight(viewer, table, state);

        FilterInputBox filterInput =
            FilterInputBox.replacePatternText(searchControl, FilterInputBox.Scope.LIST_ITEM_SELECTION_DIALOG, null);
        if (filterInput == null)
        {
            shell.setData(PATCHED_KEY, null);
            return false;
        }

        Control filterControl = filterInput.inputControl();
        if (filterControl == null)
            filterControl = filterInput.widget();
        final Control fc = filterControl;

        addFilterModifyListener(fc, e -> {
            state.matcher = new SmartMatcher(getFilterPattern(fc));
            if (!viewer.getControl().isDisposed())
                viewer.refresh();
        });

        FilterInputBoxListNavigation.installTableNavigation(fc, table, null);

        alignWithToolbar(dialog, table, filterInput.widget());

        filterInput.scheduleFocusWhenReady();

        String initialText = filterInput.getText();
        if (initialText != null && !initialText.isEmpty())
        {
            state.matcher = new SmartMatcher(initialText);
            viewer.refresh();
        }

        return true;
    }

    /**
     * Ставит поле фильтра и тулбар «отметить все / снять все» ({@code checkAllToolItem} /
     * {@code uncheckAllToolItem}) в одну строку вместо штатных двух ({@code dialogArea} —
     * однoколоночный {@code GridLayout}: поле — своя строка, тулбар — следующая, под ним, с пустым
     * местом слева от иконок). Порядок детей {@code dialogArea} не меняется (поле уже перед
     * тулбаром), поэтому достаточно расширить layout до двух колонок и растянуть список на обе.
     * Только для множественного выбора — при одиночном тулбара нет ({@code checkAllToolItem == null}),
     * поле остаётся на всю ширину само по себе.
     */
    private static void alignWithToolbar(ListItemSelectionDialog dialog, Table table, Control filterWidget)
    {
        if (filterWidget == null || filterWidget.isDisposed())
            return;
        if (!(filterWidget.getParent() instanceof Composite dialogArea)
                || dialogArea.isDisposed()
                || !(dialogArea.getLayout() instanceof GridLayout gridLayout))
            return;

        Object toolItemObj = Global.getField(dialog, "checkAllToolItem"); //$NON-NLS-1$
        if (!(toolItemObj instanceof ToolItem checkAllItem) || checkAllItem.isDisposed())
            return;
        Control toolBar = checkAllItem.getParent();
        if (toolBar == null || toolBar.isDisposed())
            return;

        gridLayout.numColumns = 2;
        if (table.getLayoutData() instanceof GridData tableGd)
            tableGd.horizontalSpan = 2;
        dialogArea.layout(true, true);
    }

    private static void installHighlight(TableViewer viewer, Table table, FilterState state)
    {
        if (Boolean.TRUE.equals(table.getData(HIGHLIGHT_PATCHED_DATA)))
            return;
        IStyledLabelProvider base = SelectionAwareStyledCellLabelProvider.unwrapOrAdapt(viewer.getLabelProvider());
        if (base == null)
            return;
        viewer.setLabelProvider(
            new SelectionAwareStyledCellLabelProvider(new HighlightStyledLabelProvider(base, state, table)));
        table.setData(HIGHLIGHT_PATCHED_DATA, Boolean.TRUE);
    }

    private static String getFilterPattern(Control filterControl)
    {
        if (filterControl == null || filterControl.isDisposed())
            return ""; //$NON-NLS-1$
        if (filterControl instanceof Text t)
            return t.getText();
        if (filterControl instanceof StyledText st)
            return st.getText();
        return ""; //$NON-NLS-1$
    }

    private static void addFilterModifyListener(Control filterControl, ModifyListener listener)
    {
        if (filterControl instanceof Text t)
            t.addModifyListener(listener);
        else if (filterControl instanceof StyledText st)
            st.addModifyListener(listener);
    }

    private static ListItemSelectionDialog resolveDialog(Shell shell)
    {
        Object data = shell.getData();
        if (data instanceof ListItemSelectionDialog dialog)
            return dialog;
        Object window = shell.getData(WINDOW_KEY);
        return window instanceof ListItemSelectionDialog dialog ? dialog : null;
    }

    /** Общее для {@link SmartAndFilter} и {@link HighlightStyledLabelProvider} текущее состояние фильтра. */
    private static final class FilterState
    {
        volatile SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$
    }

    /** Плоский AND по словам вместо штатного {@code TextListViewerFilter} (сплит по «,[]»). */
    private static final class SmartAndFilter extends ViewerFilter
    {
        private final FilterState state;

        SmartAndFilter(FilterState state)
        {
            this.state = state;
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            SmartMatcher matcher = state.matcher;
            if (matcher.isEmpty)
                return true;
            if (!(viewer instanceof TableViewer tableViewer))
                return true;
            return matcher.matches(plainText(tableViewer, element));
        }
    }

    /** Текст элемента, как он реально отображается — независимо от того, кто сейчас обёртывает label provider. */
    private static String plainText(TableViewer viewer, Object element)
    {
        if (viewer == null || element == null)
            return ""; //$NON-NLS-1$
        IBaseLabelProvider provider = viewer.getLabelProvider();
        if (provider instanceof SelectionAwareStyledCellLabelProvider sacp)
            return sacp.textForCopy(element);
        if (provider instanceof ILabelProvider lp)
        {
            String text = lp.getText(element);
            return text != null ? text : ""; //$NON-NLS-1$
        }
        return String.valueOf(element);
    }

    /**
     * Накладывает подсветку совпадений {@link SmartMatchHighlight} на уже стилизованный текст.
     * Вне диапазонов совпадения существующий {@link StyleRange} (например декорация суффикса типа
     * от {@link FormMainAttributeTypeDecorator}) сохраняется — как {@link StyledString#DECORATIONS_STYLER}
     * (единственный стиль, который в этой цепочке провайдеров реально накладывается).
     */
    private static final class HighlightStyledLabelProvider extends LabelProvider implements IStyledLabelProvider
    {
        private final IStyledLabelProvider base;
        private final FilterState state;
        private final Table table;

        HighlightStyledLabelProvider(IStyledLabelProvider base, FilterState state, Table table)
        {
            this.base = base;
            this.state = state;
            this.table = table;
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            StyledString styled = base.getStyledText(element);
            if (styled == null)
                styled = new StyledString();
            return applyHighlight(styled, state.matcher, table);
        }

        @Override
        public Image getImage(Object element)
        {
            return base.getImage(element);
        }

        @Override
        public void dispose()
        {
            base.dispose();
            super.dispose();
        }
    }

    private static StyledString applyHighlight(StyledString base, SmartMatcher matcher, Table table)
    {
        String text = base.getString();
        if (matcher.isEmpty || text.isEmpty())
            return base;
        List<SmartMatcher.HighlightRange> raw = matcher.getHighlightRanges(text);
        if (raw.isEmpty())
            return base;

        boolean[] highlighted = new boolean[text.length()];
        for (SmartMatcher.HighlightRange hr : raw)
        {
            int end = Math.min(hr.offset + hr.length, text.length());
            for (int i = Math.max(hr.offset, 0); i < end; i++)
                highlighted[i] = true;
        }

        StyleRange[] baseRanges = base.getStyleRanges();
        Styler highlightStyler = SmartMatchHighlight.styler(table);

        StyledString result = new StyledString();
        int pos = 0;
        while (pos < text.length())
        {
            boolean hl = highlighted[pos];
            boolean decorated = !hl && isDecorated(baseRanges, pos);
            int end = pos + 1;
            while (end < text.length() && highlighted[end] == hl
                    && (hl || isDecorated(baseRanges, end) == decorated))
                end++;
            String piece = text.substring(pos, end);
            if (hl)
                result.append(piece, highlightStyler);
            else if (decorated)
                result.append(piece, StyledString.DECORATIONS_STYLER);
            else
                result.append(piece);
            pos = end;
        }
        return result;
    }

    private static boolean isDecorated(StyleRange[] ranges, int pos)
    {
        if (ranges == null)
            return false;
        for (StyleRange range : ranges)
        {
            if (pos >= range.start && pos < range.start + range.length)
                return true;
        }
        return false;
    }
}
