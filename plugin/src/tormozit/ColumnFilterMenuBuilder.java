package tormozit;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.osgi.framework.Bundle;

/**
 * Общая группа пунктов меню «Отобрать/Снять отбор по значению ячейки», «Различные значения
 * колонки», «Отключить все отборы», «Отобрано элементов: N» (issue #266, п.2–5) — единая для
 * {@link FormTableInteraction} (Table) и {@link TreeColumnValueFilterSupport} (Tree).
 *
 * <p>Группа всегда идёт в КОРЕНЬ переданного {@code menu} (не в подменю «Комфорт»), обрамлённая
 * разделителями с обеих сторон — не сливается с соседними пунктами штатного меню, даже когда та же
 * группа не последняя (меню общее с другими хуками/штатным EGit).
 */
final class ColumnFilterMenuBuilder
{
    /** Команда отбора по значению ячейки (см. {@code plugin.xml}); сочетание — Alt+W по умолчанию. */
    static final String FILTER_COMMAND_ID = "tormozit.formTable.filterByCellValue"; //$NON-NLS-1$
    /** Команда «Различные значения колонки» (см. {@code plugin.xml}); сочетание — Alt+F по умолчанию. */
    static final String COLUMN_VALUES_COMMAND_ID = "tormozit.formTable.columnValues"; //$NON-NLS-1$

    /**
     * Контекст, в котором обе команды привязаны в {@code plugin.xml}. В модальном
     * диалоге он не активен, и {@code getActiveBindingsFor} пуст — без явного
     * контекста пункты меню остались бы без сочетания клавиш, хотя сами клавиши
     * там работают (см. {@code FormTableInteraction.handleFilterCommandKeys}).
     */
    static final String WINDOW_CONTEXT_ID = "org.eclipse.ui.contexts.window"; //$NON-NLS-1$

    private static final int GLYPH_SIZE = 12;
    /** По одной картинке на {@code Display} — MenuItem.setImage не копирует, живёт, пока жив Display. */
    private static final Map<Display, Image> GLYPH_CACHE = new WeakHashMap<>();
    private static final String FILTER_BY_VALUE_ICON_PATH = "icons/ирОтборПоЗначению.gif"; //$NON-NLS-1$
    private static final Map<Display, Image> FILTER_BY_VALUE_ICON_CACHE = new WeakHashMap<>();

    private ColumnFilterMenuBuilder() {}

    /**
     * Иконка «×» для «Отключить все отборы» ({@link Owner#clearAllIcon()}) — тот же стиль, что у
     * {@code FormTableInteraction.filterGlyph()} (antialias, lineWidth=2, COLOR_WIDGET_FOREGROUND).
     * Кэш на {@code Display}, освобождается через {@code disposeExec} при закрытии Display — не
     * нужно вручную диспозить в каждом владельце.
     */
    static Image filterGlyph(Display display)
    {
        Image cached = GLYPH_CACHE.get(display);
        if (cached != null && !cached.isDisposed())
            return cached;
        Image image = new Image(display, GLYPH_SIZE, GLYPH_SIZE);
        GC gc = new GC(image);
        try
        {
            gc.setAdvanced(true);
            gc.setAntialias(SWT.ON);
            gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
            gc.fillRectangle(0, 0, GLYPH_SIZE, GLYPH_SIZE);
            gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
            gc.setLineWidth(2);
            int pad = 3;
            gc.drawLine(pad, pad, GLYPH_SIZE - pad, GLYPH_SIZE - pad);
            gc.drawLine(GLYPH_SIZE - pad, pad, pad, GLYPH_SIZE - pad);
        }
        finally
        {
            gc.dispose();
        }
        GLYPH_CACHE.put(display, image);
        display.disposeExec(() ->
        {
            GLYPH_CACHE.remove(display);
            if (!image.isDisposed())
                image.dispose();
        });
        return image;
    }

    /**
     * Иконка «Отобрать по значению ячейки» из {@code plugin/icons/ирОтборПоЗначению.gif}.
     * {@code AbstractUIPlugin.imageDescriptorFromPlugin(Activator.PLUGIN_ID, ...)} тут не подходит:
     * {@code PLUGIN_ID = "tormozit"} — внутренний ключ preference store, а не Bundle-SymbolicName
     * ({@code tormozit.comfort} в MANIFEST.MF) — OSGi не находит бандл по неверному имени и молча
     * возвращает пустой дескриптор. Берём бандл напрямую через {@link Activator#getDefault()} — тот
     * же приём, что в {@code CompareCurrentLinesPanel.toggleIconDescriptor}/{@code
     * BslInspectSupport.loadInspectCommandImage}. {@code null}, если бандл/файл не нашлись —
     * иконка опциональна, пункт меню остаётся без неё.
     */
    static Image filterByValueIcon(Display display)
    {
        Image cached = FILTER_BY_VALUE_ICON_CACHE.get(display);
        if (cached != null && !cached.isDisposed())
            return cached;
        Activator activator = Activator.getDefault();
        Bundle bundle = activator != null ? activator.getBundle() : null;
        URL url = bundle != null ? bundle.getEntry(FILTER_BY_VALUE_ICON_PATH) : null;
        if (url == null)
            return null;
        Image image = ImageDescriptor.createFromURL(url).createImage(false, display);
        if (image == null)
            return null;
        FILTER_BY_VALUE_ICON_CACHE.put(display, image);
        display.disposeExec(() ->
        {
            FILTER_BY_VALUE_ICON_CACHE.remove(display);
            if (!image.isDisposed())
                image.dispose();
        });
        return image;
    }

    /** Источник данных группы — реализуют {@link FormTableInteraction} и {@link TreeColumnValueFilterSupport}. */
    interface Owner
    {
        boolean canFilterByActiveCell();

        boolean isActiveCellFiltered();

        void toggleActiveCellFilter();

        boolean canBrowseColumnValues();

        void openColumnValuesDialog();

        boolean hasActiveFilters();

        void clearAllFilters();

        /** Текущий отбор целиком — для подсказки у «Отключить все отборы»/счётчика. */
        String activeFiltersDescription();

        /** Число видимых (после отбора) строк-листьев — для «Отобрано элементов: N». */
        int filteredElementCount();

        /** Иконка «×» у «Отключить все отборы»; {@code null} — без иконки. */
        default Image clearAllIcon()
        {
            return null;
        }
    }

    /**
     * Пересобирает группу в {@code menu}: разделитель, «Отобрать/Снять отбор», «Различные значения
     * колонки», [если есть активные отборы] «Отключить все отборы» + «Отобрано элементов: N»,
     * разделитель. Старые пункты из {@code trackedItems} вызывающий обязан удалить ДО вызова (и
     * очистить список) — метод только добавляет новые пункты и складывает их в {@code trackedItems}.
     */
    static void rebuildFilterMenuItems(Menu menu, List<MenuItem> trackedItems, Owner owner)
    {
        if (menu == null || menu.isDisposed())
            return;

        trackedItems.add(new MenuItem(menu, SWT.SEPARATOR));

        boolean activeCellFiltered = owner.isActiveCellFiltered();
        boolean canFilter = owner.canFilterByActiveCell();
        MenuItem filterItem = new MenuItem(menu, SWT.PUSH);
        filterItem.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
            activeCellFiltered ? "Снять отбор по значению ячейки" : "Отобрать по значению ячейки", //$NON-NLS-1$ //$NON-NLS-2$
            FILTER_COMMAND_ID, WINDOW_CONTEXT_ID));
        filterItem.setEnabled(activeCellFiltered || canFilter);
        // Снять отбор — та же иконка «×», что у «Отключить все отборы» (антикоманда).
        Image filterIcon = activeCellFiltered ? owner.clearAllIcon() : filterByValueIcon(menu.getDisplay());
        if (filterIcon != null)
            filterItem.setImage(filterIcon);
        filterItem.addListener(SWT.Selection, ev -> owner.toggleActiveCellFilter());
        trackedItems.add(filterItem);

        boolean canBrowse = owner.canBrowseColumnValues();
        MenuItem valuesItem = new MenuItem(menu, SWT.PUSH);
        valuesItem.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
            "Различные значения колонки", COLUMN_VALUES_COMMAND_ID, WINDOW_CONTEXT_ID)); //$NON-NLS-1$
        valuesItem.setEnabled(canBrowse);
        valuesItem.addListener(SWT.Selection, ev -> owner.openColumnValuesDialog());
        trackedItems.add(valuesItem);

        if (owner.hasActiveFilters())
        {
            MenuItem clearAllItem = new MenuItem(menu, SWT.PUSH);
            clearAllItem.setText("Отключить все отборы"); //$NON-NLS-1$
            clearAllItem.setToolTipText(owner.activeFiltersDescription());
            Image icon = owner.clearAllIcon();
            if (icon != null)
                clearAllItem.setImage(icon);
            clearAllItem.addListener(SWT.Selection, ev -> owner.clearAllFilters());
            trackedItems.add(clearAllItem);

            MenuItem countItem = new MenuItem(menu, SWT.PUSH);
            countItem.setText("Отобрано элементов:  " + owner.filteredElementCount()); //$NON-NLS-1$
            countItem.setToolTipText(owner.activeFiltersDescription());
            countItem.setEnabled(false);
            trackedItems.add(countItem);
        }

        trackedItems.add(new MenuItem(menu, SWT.SEPARATOR));
    }
}
