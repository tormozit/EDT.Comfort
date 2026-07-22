package tormozit;

import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.FilteredTree;

/**
 * Общий UI истории фильтра: сохранение по потере фокуса, попап по Ctrl+↓ и/
 * или по клику на кнопку — переиспользуется и для поиска по «Параметрам»
 * ({@code PreferenceSearchFilterAugmenter}), и для фильтра страницы «Клавиши»
 * ({@code ComfortKeysPreferences}). Хранилище — {@link FilterHistoryStore},
 * с отдельным {@code scopeId} на каждое место использования.
 */
final class FilterHistoryUi
{
    private FilterHistoryUi()
    {
    }

    /** Сохранение по потере фокуса + Ctrl+↓ в самом поле. Вызывать один раз на контрол. */
    static void wireKeyboard(Text filterControl, String scopeId)
    {
        // Сохранение — лениво по потере фокуса (как installDeferredHistorySave
        // в FilterInputBox), а не на каждое нажатие клавиши.
        filterControl.addListener(SWT.FocusOut, e -> FilterHistoryStore.remember(scopeId, filterControl.getText()));

        // Ctrl+↓ — тот же жест, что уже принят в проекте (FilterInputBoxListNavigation),
        // но обычный пользователь его не угадает — поэтому есть и видимая кнопка.
        filterControl.addListener(SWT.KeyDown, e ->
        {
            if (e.keyCode != SWT.ARROW_DOWN || (e.stateMask & SWT.CTRL) == 0)
                return;
            e.doit = false;
            showPopup(filterControl, filterControl, scopeId);
        });
    }

    /**
     * Общий узкий контейнер под кнопки-глифы справа от поля фильтра — ОДНА
     * колонка в {@code GridLayout} родителя вместо нескольких (по одной на
     * каждую кнопку), с нулевыми внутренними отступами. Раньше каждая кнопка
     * добавляла свою колонку с полными отступами родительского layout'а —
     * суммарно это оказалось заметно шире, чем нужно.
     */
    static Composite createButtonsRow(Composite parent)
    {
        if (parent == null || parent.isDisposed())
            return null;
        Composite row = new Composite(parent, SWT.NONE);
        GridLayout rowLayout = new GridLayout(0, false);
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 0;
        rowLayout.horizontalSpacing = 2;
        row.setLayout(rowLayout);
        if (parent.getLayout() instanceof GridLayout parentLayout)
        {
            parentLayout.numColumns = parentLayout.numColumns + 1;
            row.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        }
        return row;
    }

    /**
     * Видимая кнопка-стрелка в уже существующем ряду (см.
     * {@link #createButtonsRow(Composite)}).
     */
    static Label addHistoryButton(Composite row, Text filterControl, String scopeId)
    {
        if (row == null || row.isDisposed())
            return null;
        Label label = createGlyphButton(row, "▾", "История фильтров (или Ctrl+↓ в поле) (Комфорт)"); //$NON-NLS-1$ //$NON-NLS-2$
        label.addListener(SWT.MouseUp, e -> showPopup(label, filterControl, scopeId));
        Composite parent = row.getParent();
        if (parent != null && !parent.isDisposed())
            parent.layout(true, true);
        return label;
    }

    /**
     * Компактная кликабельная кнопка-глиф (текстовый символ, без иконок) с
     * чуть увеличенным шрифтом — обычный {@code Label}, а не нативный
     * {@code ToolItem} (тот оказался слишком широким и портил соседние
     * штатные элементы строки фильтра). Добавляется в уже существующий ряд
     * (см. {@link #createButtonsRow(Composite)}), а не в исходный parent —
     * так суммарная ширина двух кнопок минимальна.
     */
    static Label createGlyphButton(Composite row, String text, String tooltip)
    {
        Label label = new Label(row, SWT.CENTER);
        label.setText(text);
        label.setToolTipText(tooltip);
        label.setCursor(row.getDisplay().getSystemCursor(SWT.CURSOR_HAND));

        Font baseFont = label.getFont();
        FontData[] fontData = baseFont.getFontData();
        for (FontData fd : fontData)
            fd.setHeight(fd.getHeight() + 2);
        Font biggerFont = new Font(row.getDisplay(), fontData);
        label.setFont(biggerFont);
        label.addDisposeListener(e ->
        {
            if (!biggerFont.isDisposed())
                biggerFont.dispose();
        });

        if (row.getLayout() instanceof GridLayout rowLayout)
        {
            rowLayout.numColumns = rowLayout.numColumns + 1;
            GridData gd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
            gd.widthHint = 18;
            gd.heightHint = 18;
            label.setLayoutData(gd);
        }
        return label;
    }

    /**
     * @param anchor откуда позиционировать попап (кнопка при клике мышью,
     * само поле при Ctrl+↓) — раньше попап всегда цеплялся к левому краю
     * {@code filterControl}, из-за чего на широком поле список открывался
     * далеко от места клика по кнопке справа.
     */
    private static void showPopup(Control anchor, Text filterControl, String scopeId)
    {
        List<String> items = FilterHistoryStore.load(scopeId);
        if (items.isEmpty())
            return;
        // Menu.setVisible(true) открывает вложенный цикл обработки событий —
        // вызванный синхронно прямо из обработчика клика/клавиши, это может
        // конфликтовать с ещё не завершённой обработкой исходного события
        // (меню либо не появляется, либо сразу закрывается). Откладываем
        // показ на следующий такт UI-потока.
        anchor.getDisplay().asyncExec(() ->
        {
            if (!anchor.isDisposed() && !filterControl.isDisposed())
                showMenu(anchor, filterControl, items);
        });
    }

    private static void showMenu(Control anchor, Text filterControl, List<String> items)
    {
        Menu menu = new Menu(anchor);
        for (String item : items)
        {
            MenuItem menuItem = new MenuItem(menu, SWT.PUSH);
            menuItem.setText(item);
            menuItem.addListener(SWT.Selection, e ->
            {
                // Не ставим текст здесь: Selection приходит внутри nested loop
                // Menu.setVisible (на Windows Hide часто раньше Selection), и
                // Modify/textChanged/refreshJob FilteredTree плавают до Activate
                // окна. Применяем на следующем такте UI-потока.
                if (!filterControl.isDisposed())
                    filterControl.getDisplay().asyncExec(() -> applyHistoryValue(filterControl, item));
            });
        }
        menu.addListener(SWT.Hide, e ->
        {
            menu.getDisplay().asyncExec(() ->
            {
                if (!menu.isDisposed())
                    menu.dispose();
            });
        });
        menu.setLocation(anchor.toDisplay(0, anchor.getSize().y));
        menu.setVisible(true);
    }

    /**
     * Ставит значение из истории в поле и форсирует перефильтровку
     * {@link FilteredTree} (тот же приём, что {@code ComfortKeysPreferences.setFilterText}).
     */
    private static void applyHistoryValue(Text filterControl, String item)
    {
        if (filterControl == null || filterControl.isDisposed())
        {
            return;
        }
        filterControl.setText(item);
        filterControl.setSelection(item.length());
        filterControl.setFocus();
        FilteredTree tree = findFilteredTree(filterControl);
        if (tree != null)
            forceTextChanged(tree);
        else
        {
            filterControl.notifyListeners(SWT.Modify, new Event());
        }
    }

    private static FilteredTree findFilteredTree(Control start)
    {
        for (Control c = start; c != null; c = c.getParent())
        {
            if (c instanceof FilteredTree filteredTree)
                return filteredTree;
        }
        return null;
    }

    private static void forceTextChanged(FilteredTree tree)
    {
        try
        {
            Method textChanged = FilteredTree.class.getDeclaredMethod("textChanged"); //$NON-NLS-1$
            textChanged.setAccessible(true);
            textChanged.invoke(tree);
        }
        catch (Exception ex)
        {
            Text filterControl = tree.getFilterControl();
            if (filterControl != null && !filterControl.isDisposed())
                filterControl.notifyListeners(SWT.Modify, new Event());
        }
    }
}
