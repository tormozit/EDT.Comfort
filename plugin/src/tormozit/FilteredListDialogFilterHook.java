package tormozit;

import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.dialogs.FilteredList;

/**
 * Многословный фильтр ({@link SmartMatcher}, AND по словам) + подсветка + история в ЛЮБОМ диалоге
 * EDT/Eclipse, построенном на штатном {@link FilteredList} (детектируется структурно — по
 * наличию этого виджета в показанном {@code Shell}, без привязки к конкретному пакету/диалогу).
 *
 * <p>Мотивирующий случай — диалог «Найти» (Ctrl+F) отладочных панелей на flexible-viewer-модели:
 * «Точки останова», «Переменные», «Выражения», «Регистры» и т.п. используют один и тот же
 * {@code org.eclipse.debug.internal.ui.viewers.model.VirtualFindAction}, который открывает
 * {@code org.eclipse.debug.internal.ui.viewers.FindElementDialog extends
 * org.eclipse.ui.dialogs.ElementListSelectionDialog}. Но охват хука — любой {@link FilteredList},
 * а не только этот диалог (согласовано в чате: имя класса отражает реальный охват).
 *
 * <p>Разведка декомпиляцией байткода ({@code org.eclipse.ui.workbench_*.jar}) показала:
 * {@link FilteredList} — публичный SWT-виджет с публичным SPI {@link FilteredList.FilterMatcher}
 * ({@code setFilterMatcher}), поэтому AND-матчинг подключается штатным API, БЕЗ рефлексии в
 * приватные поля диалога. Поле фильтра ({@code fFilterText}) и сам {@link FilteredList} —
 * прямые дочерние одного {@code dialogArea} в этом порядке (см.
 * {@code ElementListSelectionDialog.createDialogArea}: {@code createMessageArea → createFilterText
 * → createFilteredList} на один и тот же parent) — оба находятся обычным обходом
 * {@code Composite.getChildren()}.
 *
 * <p>Штатное поле {@code fFilterText} заменяется на общий {@link FilterInputBox} (штатный
 * {@code SearchBox}, как в {@code PictureDialogHook}/{@code OpenMdObjectHook}) —
 * кнопки истории (▾) и очистки (×) у {@code SearchBox} уже встроены в само поле
 * ({@code SearchBox$Area.DROP_DOWN_BUTTON}/{@code CLEAR_BUTTON}), отдельный ряд Label-кнопок
 * не нужен. Фильтрация — своим {@code ModifyListener} на внутреннем текстовом контроле
 * ({@link FilterInputBox#inputControl()}), напрямую вызывающим {@code filteredList.setFilter(...)}
 * (штатный Modify-listener исходного {@code Text} уходит вместе с ним при замене).
 *
 * <p>Каждое открытие такого диалога создаёт НОВЫЙ его экземпляр (не персистентная панель) —
 * патчим по факту показа ({@code SWT.Show}/{@code SWT.Activate}), состояние между вызовами не нужно.
 *
 * <p>Матчинг — плоский {@link SmartMatcher#matches} (без dot-иерархии, как в
 * {@code GitStagingFilterHook}): элементы дерева отладки (точки останова, переменные) обычно сами
 * содержат точки в тексте (полное имя объекта, путь к модулю) — {@code matchesTree} откатывался бы
 * к последнему dot-сегменту и терял остальной текст.
 */
public final class FilteredListDialogFilterHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.filteredListDialogFilterPatched"; //$NON-NLS-1$

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
        FilteredList filteredList = findFilteredList(shell);
        if (filteredList != null)
            schedulePatch(shell, filteredList, 0);
    }

    private static void schedulePatch(Shell shell, FilteredList filteredList, int attempt)
    {
        Display display = shell.getDisplay();
        int delay = attempt == 0 ? 0 : 60;
        display.timerExec(delay, () -> {
            if (shell.isDisposed() || Boolean.TRUE.equals(shell.getData(PATCHED_KEY)))
                return;
            if (!tryPatch(shell, filteredList) && attempt < 20)
                schedulePatch(shell, filteredList, attempt + 1);
        });
    }

    private static boolean tryPatch(Shell shell, FilteredList filteredList)
    {
        if (filteredList.isDisposed())
            return true; // диалог уже закрыт — патчить нечего, но и повторять незачем

        Composite dialogArea = filteredList.getParent();
        Text filterText = dialogArea != null ? findDirectText(dialogArea) : null;
        Table table = findTable(filteredList);
        if (dialogArea == null || dialogArea.isDisposed() || filterText == null || table == null)
            return false;

        shell.setData(PATCHED_KEY, Boolean.TRUE);
        hideOutdatedHint(dialogArea);

        SmartFilterMatcher matcher = new SmartFilterMatcher(filteredList.getLabelProvider());
        filteredList.setFilterMatcher(matcher);

        FilterInputBox filterInput =
            FilterInputBox.replacePatternText(filterText, FilterInputBox.Scope.FILTERED_LIST_DIALOG, null);
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
            filteredList.setFilter(getFilterPattern(fc));
            // FilteredList трогает TableItem.setText только у РЕАЛЬНО изменившихся строк — если
            // состав видимых элементов не поменялся (сузили/расширили паттерн, но набор тот же),
            // Table сама не перерисуется и PaintItem-оверлей останется со старыми диапазонами.
            if (!table.isDisposed())
                table.redraw();
        });

        FilterInputBoxListNavigation.installTableNavigation(fc, table, null);

        table.addListener(SWT.PaintItem, e -> {
            SmartMatcher current = matcher.current();
            if (current != null && !current.isEmpty)
                SmartMatchHighlight.paintTableCellMatchOverlayFlat(e, table, (TableItem) e.item, current);
        });

        filterInput.scheduleFocusWhenReady();

        String initialText = filterInput.getText();
        if (initialText != null && !initialText.isEmpty())
        {
            filteredList.setFilter(initialText);
            if (!table.isDisposed())
                table.redraw();
        }

        return true;
    }

    private static String itemText(org.eclipse.swt.widgets.Event e, Table table)
    {
        if (!(e.item instanceof TableItem item) || item.isDisposed())
            return "?"; //$NON-NLS-1$
        String text = item.getText(e.index);
        return text != null ? text : ""; //$NON-NLS-1$
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

    /**
     * Штатная подсказка («Укажите элемент (? = любой символ, * = любая строка):», см.
     * {@code AbstractElementListSelectionDialog.createMessageArea}) описывает wildcard-синтаксис
     * оригинального {@code DefaultFilterMatcher} — с нашим {@link SmartMatcher} она вводит в
     * заблуждение, поэтому скрываем (не {@code dispose}: JFace держит на неё поле {@code fMessage},
     * дальнейшие обращения к нему не должны падать).
     */
    private static void hideOutdatedHint(Composite dialogArea)
    {
        for (Control child : dialogArea.getChildren())
        {
            if (child instanceof Label label)
            {
                label.setVisible(false);
                if (label.getLayoutData() instanceof GridData gd)
                    gd.exclude = true;
                dialogArea.layout(true, true);
                return;
            }
        }
    }

    private static Text findDirectText(Composite dialogArea)
    {
        for (Control child : dialogArea.getChildren())
        {
            if (child instanceof Text text)
                return text;
        }
        return null;
    }

    private static Table findTable(Control root)
    {
        if (root == null || root.isDisposed())
            return null;
        if (root instanceof Table table)
            return table;
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                Table found = findTable(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static FilteredList findFilteredList(Control root)
    {
        if (root == null || root.isDisposed())
            return null;
        if (root instanceof FilteredList filteredList)
            return filteredList;
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                FilteredList found = findFilteredList(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /**
     * Плоский AND по словам через штатный SPI {@link FilteredList.FilterMatcher} — без подмены
     * приватных полей {@code FilteredList}/диалога.
     */
    private static final class SmartFilterMatcher implements FilteredList.FilterMatcher
    {
        private final ILabelProvider labelProvider;
        private volatile SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$

        SmartFilterMatcher(ILabelProvider labelProvider)
        {
            this.labelProvider = labelProvider;
        }

        @Override
        public void setFilter(String pattern, boolean ignoreCase, boolean matchEmptyString)
        {
            matcher = new SmartMatcher(pattern);
         }

        @Override
        public boolean match(Object element)
        {
            if (matcher.isEmpty)
                return true;
            String text = labelProvider != null ? labelProvider.getText(element) : String.valueOf(element);
            boolean result = matcher.matches(text != null ? text : ""); //$NON-NLS-1$
             return result;
        }

        SmartMatcher current()
        {
            return matcher;
        }
    }
}
