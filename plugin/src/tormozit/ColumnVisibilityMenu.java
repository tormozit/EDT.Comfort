package tormozit;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.layout.TreeColumnLayout;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Item;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;

/** Меню дополнительных колонок: пометки и ширины сохраняются между сеансами EDT. */
final class ColumnVisibilityMenu
{
    private static final String KEY = "tormozit.columnVisibility"; //$NON-NLS-1$
    private static final List<ColumnVisibilityMenu> OPEN = new ArrayList<>();

    private final Control control;
    private final String scope;
    private final IDialogSettings settings;
    private final List<Entry> columns = new ArrayList<>();
    private final BiConsumer<Entry, Integer> setWidth;

    private ColumnVisibilityMenu(Control control, String scope, BiConsumer<Entry, Integer> setWidth)
    {
        this.control = control;
        this.scope = scope;
        this.setWidth = setWidth;
        IDialogSettings root = Activator.getDefault().getDialogSettings();
        String section = "ColumnVisibility." + scope; //$NON-NLS-1$
        IDialogSettings existing = root.getSection(section);
        settings = existing != null ? existing : root.addNewSection(section);
        control.setData(KEY, this);
        OPEN.add(this);
        control.addListener(SWT.MenuDetect, event -> hookMenu());
        control.addListener(SWT.Dispose, event -> OPEN.remove(this));
        hookMenu();
    }

    static ColumnVisibilityMenu forTree(Tree tree, String scope)
    {
        return forTree(tree, scope, (column, width) -> {
            if (tree.getParent().getLayout() instanceof TreeColumnLayout layout)
                layout.setColumnData(column, new ColumnPixelData(width, column.getResizable(), false));
            ColumnAutoFit.setColumnWidth(column, width);
        });
    }

    static ColumnVisibilityMenu forTree(Tree tree, String scope, BiConsumer<TreeColumn, Integer> setWidth)
    {
        if (tree.getData(KEY) instanceof ColumnVisibilityMenu existing)
            return existing;
        return new ColumnVisibilityMenu(tree, scope,
            (entry, width) -> setWidth.accept((TreeColumn)entry.column, width));
    }

    static ColumnVisibilityMenu forTable(Control table, String scope, FormTableInteraction interaction)
    {
        if (table.getData(KEY) instanceof ColumnVisibilityMenu existing)
            return existing;
        return new ColumnVisibilityMenu(table, scope,
            (entry, width) -> interaction.setColumnHidden((TableColumn)entry.column, !entry.visible, width));
    }

    /** Одинаковый group объединяет несколько динамических колонок в одну пометку. */
    void add(Item column, String id, String group, String label)
    {
        if (column.getData(KEY) instanceof Entry)
            return;
        Entry entry = new Entry(this, column, id, group, label);
        columns.removeIf(value -> value.column.isDisposed());
        columns.add(entry);
        column.setData(KEY, entry);
        column.addListener(SWT.Resize, event -> {
            if (entry.visible && entry.width() > 0)
                entry.rememberWidth();
        });
        entry.visible = settings.get(group) == null || settings.getBoolean(group);
        if (!entry.visible)
        {
            String saved = settings.get("width." + id); //$NON-NLS-1$
            if (saved != null)
            {
                try
                {
                    entry.lastWidth = Math.max(1, Integer.parseInt(saved));
                }
                catch (NumberFormatException ignored)
                {
                    // Повреждённая настройка ширины: остаётся ширина, заданная хозяином колонки.
                }
            }
            entry.apply();
        }
    }

    void add(Item column, String id, String label)
    {
        add(column, id, id, label);
    }

    /** Хозяин сменил раскладку: повторно скрыть колонки, не потеряв сохранённую ширину. */
    static void reapply(Control control)
    {
        if (control.getData(KEY) instanceof ColumnVisibilityMenu menu)
            for (Entry entry : menu.columns)
                if (!entry.column.isDisposed() && !entry.visible)
                    entry.apply();
    }

    /** Ширина для сохранения хозяином: у скрытой колонки это последнее видимое значение. */
    static int savedWidth(Item column)
    {
        if (column.getData(KEY) instanceof Entry entry && !entry.visible)
            return entry.lastWidth;
        return column instanceof TreeColumn treeColumn ? treeColumn.getWidth() : ((TableColumn)column).getWidth();
    }

    static boolean isHidden(Item column)
    {
        return column.getData(KEY) instanceof Entry entry && !entry.visible;
    }

    private void hookMenu()
    {
        Menu menu = control.getMenu();
        if (menu == null)
        {
            menu = new Menu(control);
            control.setMenu(menu);
        }
        if (menu.getData(KEY) == this)
            return;
        menu.setData(KEY, this);
        Menu target = menu;
        menu.addListener(SWT.Show, event -> populate(target));
    }

    private void populate(Menu menu)
    {
        if (control.isDisposed())
            return;
        columns.removeIf(entry -> entry.column.isDisposed());
        if (columns.isEmpty())
            return;
        Menu comfort = ComfortSubmenuHelper.findOrCreateComfortSubmenu(menu, control.getShell());
        for (MenuItem item : menu.getItems())
            if (item.getData(KEY) == this)
            {
                Menu submenu = item.getMenu();
                item.dispose();
                if (submenu != null && !submenu.isDisposed())
                    submenu.dispose();
            }
        MenuItem comfortItem = comfort.getParentItem();
        int index = comfortItem != null && comfortItem.getParent() == menu
            ? menu.indexOf(comfortItem) + 1 : menu.getItemCount();
        MenuItem cascade = new MenuItem(menu, SWT.CASCADE, index);
        cascade.setText("Колонки"); //$NON-NLS-1$
        cascade.setData(KEY, this);
        cascade.setImage(columnsIcon(control.getDisplay()));
        ComfortSubmenuHelper.setMenuItemTooltip(cascade, "Видимость дополнительных колонок"); //$NON-NLS-1$
        Menu submenu = new Menu(menu);
        cascade.setMenu(submenu);
        Map<String, Entry> groups = new LinkedHashMap<>();
        for (Entry entry : columns)
            groups.putIfAbsent(entry.group, entry);
        for (Entry entry : groups.values())
        {
            MenuItem item = ComfortSubmenuHelper.createSortedMenuItem(submenu, SWT.CHECK, entry.label);
            item.setSelection(entry.visible);
            ComfortSubmenuHelper.setMenuItemTooltip(item, groupTooltip(entry));
            item.addListener(SWT.Selection, event -> setVisible(entry.group, item.getSelection()));
        }
    }

    /** Иконка из бандла плагина; ресурс общий для меню текущего Display. */
    private static Image columnsIcon(Display display)
    {
        String key = KEY + ".icon"; //$NON-NLS-1$
        if (display.getData(key) instanceof Image cached && !cached.isDisposed())
            return cached;
        Activator activator = Activator.getDefault();
        URL url = activator != null
            ? activator.getBundle().getEntry("icons/ирКолонкаТабличногоПоля.gif") : null; //$NON-NLS-1$
        if (url == null)
            return null;
        Image image = ImageDescriptor.createFromURL(url).createImage(false, display);
        if (image != null)
        {
            display.setData(key, image);
            display.disposeExec(() -> {
                if (!image.isDisposed())
                    image.dispose();
            });
        }
        return image;
    }

    /** Описания берём из текущих шапок, в том числе для единой пометки динамических колонок. */
    private String groupTooltip(Entry groupEntry)
    {
        StringBuilder text = new StringBuilder("Показывать или скрывать колонки: ") //$NON-NLS-1$
            .append(groupEntry.label).append('.');
        Set<String> descriptions = new LinkedHashSet<>();
        for (Entry entry : columns)
        {
            if (entry.column.isDisposed() || !groupEntry.group.equals(entry.group))
                continue;
            String description = entry.column instanceof TreeColumn column
                ? column.getToolTipText() : ((TableColumn)entry.column).getToolTipText();
            if (description == null || description.isBlank())
                continue;
            // Авторство для пункта меню определяет хелпер; подпись шапки могла попасть на новую строку.
            description = description.replace(Global.pluginSignForTooltip(), "") //$NON-NLS-1$
                .replace(Global.pluginSignForTooltip().trim(), "").trim(); //$NON-NLS-1$
            if (!description.isEmpty())
                descriptions.add(description);
        }
        for (String description : descriptions)
            text.append("\n\n").append(description); //$NON-NLS-1$
        return text.toString();
    }

    private void setVisible(String group, boolean visible)
    {
        settings.put(group, visible);
        // Например, оба списка «Индексирования Git» и несколько открытых редакторов объекта.
        for (ColumnVisibilityMenu menu : List.copyOf(OPEN))
        {
            if (!scope.equals(menu.scope) || menu.control.isDisposed())
                continue;
            menu.control.setRedraw(false);
            try
            {
                for (Entry entry : menu.columns)
                {
                    if (entry.column.isDisposed() || !group.equals(entry.group) || entry.visible == visible)
                        continue;
                    if (!visible)
                        entry.rememberWidth();
                    entry.visible = visible;
                    entry.apply();
                }
                menu.control.getParent().layout(true, true);
            }
            finally
            {
                menu.control.setRedraw(true);
                menu.control.redraw();
            }
        }
    }

    private static final class Entry
    {
        private final ColumnVisibilityMenu owner;
        private final Item column;
        private final String id;
        private final String group;
        private final String label;
        private final boolean resizable;
        private int lastWidth;
        private boolean visible = true;

        Entry(ColumnVisibilityMenu owner, Item column, String id, String group, String label)
        {
            this.owner = owner;
            this.column = column;
            this.id = id;
            this.group = group;
            this.label = label;
            resizable = column instanceof TreeColumn treeColumn
                ? treeColumn.getResizable() : ((TableColumn)column).getResizable();
            lastWidth = Math.max(1, width());
        }

        int width()
        {
            return column instanceof TreeColumn treeColumn ? treeColumn.getWidth() : ((TableColumn)column).getWidth();
        }

        void rememberWidth()
        {
            if (width() > 0)
                lastWidth = width();
            owner.settings.put("width." + id, lastWidth); //$NON-NLS-1$
        }

        void apply()
        {
            if (column instanceof TreeColumn treeColumn)
                treeColumn.setResizable(visible && resizable);
            else
                ((TableColumn)column).setResizable(visible && resizable);
            owner.setWidth.accept(this, visible ? lastWidth : 0);
        }
    }
}
