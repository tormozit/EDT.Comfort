package tormozit;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.osgi.framework.Bundle;

/**
 * Команда «Вывести список» контекстного меню списков, которые дорабатывает плагин.
 *
 * <p>Выгружает выделенные строки (если выделено больше одной) либо все строки списка в
 * табличный документ ODS и открывает его в приложении по умолчанию. Формирование файла и
 * открытие идут в фоновом {@link Job} — поток ввода не блокируется. issue #23.
 *
 * <p>Работает с любым {@link Table}/{@link Tree}/{@link org.eclipse.swt.widgets.List}, который
 * зарегистрирован через {@link CopyCommandSupport#wireCopyOverride(Control)} — оттуда вызывается
 * {@link #attach(Control)}. В контекстном меню <b>штатного</b> списка EDT пункт идёт в подменю
 * «Комфорт», в меню <b>собственного окна плагина</b> — в корень (см. {@link #attach(Control)}).
 * Окна, которые сами пересобирают меню при каждом показе ({@code FormTableInteraction} внешнего
 * режима, {@code DebugCollectionWindow}), зовут {@link #appendMenuItem(Menu, Control)}.
 *
 * <p>Дерево выводится в том виде, в каком оно на экране: только развёрнутые узлы, вложенность
 * показана ведущими пробелами в первой колонке. Ячейки, целиком разбирающиеся как число,
 * пишутся числовым типом.
 */
public final class OutputListCommand
{
    private static final String ITEM_KEY = "tormozit.outputListItem"; //$NON-NLS-1$

    private static final String LABEL = "Вывести список"; //$NON-NLS-1$

    private static final String INDENT = "    "; //$NON-NLS-1$

    private static final String SHOW_HOOK_KEY = "tormozit.outputListShowHook"; //$NON-NLS-1$

    private static final String ICON_PATH = "icons/etool16/output_list.png"; //$NON-NLS-1$

    private static final Map<Display, Image> ICON_CACHE = new ConcurrentHashMap<>();

    private OutputListCommand()
    {
    }

    /**
     * Универсальное подключение к {@code Table}/{@code Tree}/{@code List}, зарегистрированному
     * в {@link CopyCommandSupport}. Список считается <b>штатным</b>, если у контрола на момент
     * вызова уже есть контекстное меню (его создала EDT) — тогда пункт идёт в подменю
     * «Комфорт». Если меню ещё нет, список считается собственным окном плагина — пункт идёт
     * в корень меню.
     *
     * <p>Пункт нельзя добавить только по {@code SWT.MenuDetect}: у штатных списков меню —
     * {@code MenuManager} с {@code removeAllWhenShown}, он пересобирает содержимое в своём
     * {@code SWT.Show} (после {@code MenuDetect}), затирая наш пункт. Поэтому ставится
     * собственный слушатель {@code SWT.Show}, который на каждом показе кладёт пункт заново.
     */
    public static void attach(Control control)
    {
        if (control == null || control.isDisposed())
            return;
        if (!(control instanceof Table) && !(control instanceof Tree)
            && !(control instanceof org.eclipse.swt.widgets.List))
            return;
        boolean nativeList = control.getMenu() != null;
        control.addListener(SWT.MenuDetect, e ->
        {
            if (control.isDisposed())
                return;
            Menu menu = control.getMenu();
            if (menu == null)
            {
                menu = new Menu(control);
                control.setMenu(menu);
            }
            installShowHook(menu, control, nativeList);
        });
    }

    private static void installShowHook(Menu menu, Control control, boolean nativeList)
    {
        if (Boolean.TRUE.equals(menu.getData(SHOW_HOOK_KEY)))
            return;
        menu.setData(SHOW_HOOK_KEY, Boolean.TRUE);
        Menu tracked = menu;
        tracked.addListener(SWT.Show, ev -> placeItem(tracked, control, nativeList));
    }

    /**
     * Добавить пункт «Вывести список» в корень контекстного меню собственного окна плагина.
     * Вызывается из сборки меню окон, которые полностью пересобирают своё меню при каждом показе.
     */
    public static void appendMenuItem(Menu menu, Control control)
    {
        placeItem(menu, control, false);
    }

    /**
     * Кладёт пункт «Вывести список» в меню списка: для штатного списка EDT — в подменю
     * «Комфорт» (find-or-create), с сохранением алфавитного порядка; для собственного окна
     * плагина — в корень меню, отдельной группой (как команды отбора по значению ячейки).
     * Прежний экземпляр пункта и его ведущий разделитель удаляются.
     */
    private static void placeItem(Menu rootMenu, Control control, boolean nativeList)
    {
        if (rootMenu == null || rootMenu.isDisposed() || control == null || control.isDisposed())
            return;
        disposeExistingItem(rootMenu);

        MenuItem item;
        if (nativeList)
        {
            Menu comfortSub = ComfortSubmenuHelper.findOrCreateComfortSubmenu(rootMenu, rootMenu.getShell());
            if (comfortSub == null || comfortSub.isDisposed())
                return;
            disposeExistingItem(comfortSub);
            item = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH, LABEL);
        }
        else
        {
            if (hasContent(rootMenu) && !endsWithSeparator(rootMenu))
            {
                MenuItem separator = new MenuItem(rootMenu, SWT.SEPARATOR);
                separator.setData(ITEM_KEY, Boolean.TRUE);
            }
            item = new MenuItem(rootMenu, SWT.PUSH);
            item.setText(LABEL);
        }
        item.setData(ITEM_KEY, Boolean.TRUE);
        ComfortSubmenuHelper.setMenuItemTooltip(item, LABEL + " в табличный документ ODS."); //$NON-NLS-1$
        Image image = icon(item.getDisplay());
        if (image != null)
            item.setImage(image);
        item.addListener(SWT.Selection, e -> run(control));
    }

    private static void disposeExistingItem(Menu menu)
    {
        for (MenuItem mi : menu.getItems())
        {
            if (!mi.isDisposed() && Boolean.TRUE.equals(mi.getData(ITEM_KEY)))
                mi.dispose();
        }
    }

    private static boolean hasContent(Menu menu)
    {
        for (MenuItem mi : menu.getItems())
        {
            if (!mi.isDisposed())
                return true;
        }
        return false;
    }

    private static boolean endsWithSeparator(Menu menu)
    {
        MenuItem[] items = menu.getItems();
        for (int i = items.length - 1; i >= 0; i--)
        {
            if (items[i].isDisposed())
                continue;
            return (items[i].getStyle() & SWT.SEPARATOR) != 0;
        }
        return false;
    }

    private static Image icon(Display display)
    {
        if (display == null || display.isDisposed())
            return null;
        Image cached = ICON_CACHE.get(display);
        if (cached != null && !cached.isDisposed())
            return cached;
        Activator activator = Activator.getDefault();
        Bundle bundle = activator != null ? activator.getBundle() : null;
        URL url = bundle != null ? bundle.getEntry(ICON_PATH) : null;
        if (url == null)
            return null;
        Image image = ImageDescriptor.createFromURL(url).createImage(false, display);
        if (image == null)
            return null;
        ICON_CACHE.put(display, image);
        display.disposeExec(() ->
        {
            ICON_CACHE.remove(display);
            if (!image.isDisposed())
                image.dispose();
        });
        return image;
    }

    private static void run(Control control)
    {
        Model model = extract(control);
        if (model == null || model.rows.isEmpty())
        {
            if (control != null && !control.isDisposed())
                control.getDisplay().beep();
            return;
        }
        Display display = control.getDisplay();
        Job job = new Job("Вывод списка в табличный документ") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    File file = writeOds(model);
                    if (display != null && !display.isDisposed())
                        display.asyncExec(() -> Program.launch(file.getAbsolutePath()));
                    return Status.OK_STATUS;
                }
                catch (Exception ex)
                {
                    return new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                        "Не удалось сформировать табличный документ", ex); //$NON-NLS-1$
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    // --- извлечение данных (поток UI) ---

    private static Model extract(Control control)
    {
        if (control instanceof Table table && !table.isDisposed())
            return fromTable(table);
        if (control instanceof Tree tree && !tree.isDisposed())
            return fromTree(tree);
        if (control instanceof org.eclipse.swt.widgets.List list && !list.isDisposed())
            return fromList(list);
        return null;
    }

    private static Model fromTable(Table table)
    {
        Model model = new Model();
        int[] cols = visibleTableColumns(table);
        if (table.getColumnCount() > 0)
        {
            for (int c : cols)
                model.headers.add(text(table.getColumn(c).getText()));
        }
        TableItem[] selection = table.getSelection();
        TableItem[] items = selection.length > 1 ? selection : table.getItems();
        for (TableItem item : items)
        {
            String[] row = new String[cols.length];
            for (int i = 0; i < cols.length; i++)
                row[i] = text(item.getText(cols[i]));
            model.rows.add(row);
        }
        return model;
    }

    private static int[] visibleTableColumns(Table table)
    {
        int count = table.getColumnCount();
        if (count == 0)
            return new int[] {0};
        int[] order = table.getColumnOrder();
        ArrayList<Integer> visible = new ArrayList<>();
        for (int c : order)
        {
            TableColumn column = table.getColumn(c);
            if (column.getWidth() > 0)
                visible.add(c);
        }
        if (visible.isEmpty())
        {
            for (int c = 0; c < count; c++)
                visible.add(c);
        }
        int[] result = new int[visible.size()];
        for (int i = 0; i < result.length; i++)
            result[i] = visible.get(i);
        return result;
    }

    private static Model fromTree(Tree tree)
    {
        Model model = new Model();
        int[] cols = visibleTreeColumns(tree);
        if (tree.getColumnCount() > 0)
        {
            for (int c : cols)
                model.headers.add(text(tree.getColumn(c).getText()));
        }
        TreeItem[] selection = tree.getSelection();
        if (selection.length > 1)
        {
            for (TreeItem item : selection)
                model.rows.add(treeRow(item, cols, depthOf(item)));
        }
        else
        {
            for (TreeItem root : tree.getItems())
                appendTreeItem(model, root, cols, 0);
        }
        return model;
    }

    private static void appendTreeItem(Model model, TreeItem item, int[] cols, int depth)
    {
        if (item.isDisposed())
            return;
        model.rows.add(treeRow(item, cols, depth));
        if (item.getExpanded())
        {
            for (TreeItem child : item.getItems())
                appendTreeItem(model, child, cols, depth + 1);
        }
    }

    private static String[] treeRow(TreeItem item, int[] cols, int depth)
    {
        String[] row = new String[cols.length];
        for (int i = 0; i < cols.length; i++)
        {
            String value = text(item.getText(cols[i]));
            if (i == 0 && depth > 0)
                value = INDENT.repeat(depth) + value;
            row[i] = value;
        }
        return row;
    }

    private static int depthOf(TreeItem item)
    {
        int depth = 0;
        for (TreeItem parent = item.getParentItem(); parent != null; parent = parent.getParentItem())
            depth++;
        return depth;
    }

    private static int[] visibleTreeColumns(Tree tree)
    {
        int count = tree.getColumnCount();
        if (count == 0)
            return new int[] {0};
        int[] order = tree.getColumnOrder();
        ArrayList<Integer> visible = new ArrayList<>();
        for (int c : order)
        {
            TreeColumn column = tree.getColumn(c);
            if (column.getWidth() > 0)
                visible.add(c);
        }
        if (visible.isEmpty())
        {
            for (int c = 0; c < count; c++)
                visible.add(c);
        }
        int[] result = new int[visible.size()];
        for (int i = 0; i < result.length; i++)
            result[i] = visible.get(i);
        return result;
    }

    private static Model fromList(org.eclipse.swt.widgets.List list)
    {
        Model model = new Model();
        String[] selection = list.getSelection();
        String[] items = selection.length > 1 ? selection : list.getItems();
        for (String value : items)
            model.rows.add(new String[] {text(value)});
        return model;
    }

    private static String text(String value)
    {
        return value == null ? "" : value; //$NON-NLS-1$
    }

    // --- запись ODS (фон) ---

    private static File writeOds(Model model) throws Exception
    {
        LocalDateTime now = LocalDateTime.now();
        String stamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")); //$NON-NLS-1$
        File file = new File(System.getProperty("java.io.tmpdir"), //$NON-NLS-1$
            "Вывести список " + stamp + ".ods"); //$NON-NLS-1$ //$NON-NLS-2$

        Zip zip = new Zip();
        // mimetype — первым и без сжатия (требование пакета ODF).
        zip.add("mimetype", //$NON-NLS-1$
            "application/vnd.oasis.opendocument.spreadsheet".getBytes(StandardCharsets.US_ASCII)); //$NON-NLS-1$
        zip.add("META-INF/manifest.xml", MANIFEST.getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        zip.add("styles.xml", STYLES.getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        zip.add("content.xml", content(model).getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        zip.add("meta.xml", meta(now).getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        zip.add("settings.xml", SETTINGS.getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        Files.write(file.toPath(), zip.build());
        return file;
    }

    /**
     * Минимальный ZIP-упаковщик, все записи — {@code STORED}. Своя реализация нужна из-за
     * {@code java.util.zip.ZipOutputStream}: он безусловно ставит в общий флаг бит 11
     * (UTF-8/EFS) и для deflate-записей — бит 3 (data descriptor). Разборщик ODS в Excel
     * обе особенности не принимает и предлагает «восстановить» книгу. Здесь общий флаг = 0,
     * имена файлов только ASCII.
     */
    private static final class Zip
    {
        private final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();

        private final java.io.ByteArrayOutputStream central = new java.io.ByteArrayOutputStream();

        private int count;

        void add(String name, byte[] data) throws Exception
        {
            byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(data);
            long checksum = crc.getValue();
            int offset = body.size();

            writeLocalHeader(body, nameBytes, checksum, data.length);
            body.write(data);

            u32(central, 0x02014b50L); // central directory header
            u16(central, 0x0314); // version made by
            u16(central, 20); // version needed
            u16(central, 0); // flags
            u16(central, 0); // method: stored
            u16(central, 0); // mod time
            u16(central, 0x21); // mod date: 1980-01-01
            u32(central, checksum);
            u32(central, data.length); // compressed
            u32(central, data.length); // uncompressed
            u16(central, nameBytes.length);
            u16(central, 0); // extra len
            u16(central, 0); // comment len
            u16(central, 0); // disk number start
            u16(central, 0); // internal attrs
            u32(central, 0); // external attrs
            u32(central, offset); // local header offset
            central.write(nameBytes);
            count++;
        }

        byte[] build() throws Exception
        {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(body.toByteArray());
            int centralOffset = out.size();
            byte[] centralBytes = central.toByteArray();
            out.write(centralBytes);

            u32(out, 0x06054b50L); // end of central directory
            u16(out, 0); // disk number
            u16(out, 0); // disk with central directory
            u16(out, count);
            u16(out, count);
            u32(out, centralBytes.length);
            u32(out, centralOffset);
            u16(out, 0); // comment len
            return out.toByteArray();
        }

        private static void writeLocalHeader(java.io.ByteArrayOutputStream o, byte[] nameBytes,
            long crc, int size) throws Exception
        {
            u32(o, 0x04034b50L);
            u16(o, 20); // version needed
            u16(o, 0); // flags — важно: 0, без бита 11
            u16(o, 0); // method: stored
            u16(o, 0); // mod time
            u16(o, 0x21); // mod date
            u32(o, crc);
            u32(o, size); // compressed
            u32(o, size); // uncompressed
            u16(o, nameBytes.length);
            u16(o, 0); // extra len
            o.write(nameBytes);
        }

        private static void u16(java.io.ByteArrayOutputStream o, int v)
        {
            o.write(v & 0xFF);
            o.write((v >>> 8) & 0xFF);
        }

        private static void u32(java.io.ByteArrayOutputStream o, long v)
        {
            o.write((int) (v & 0xFF));
            o.write((int) ((v >>> 8) & 0xFF));
            o.write((int) ((v >>> 16) & 0xFF));
            o.write((int) ((v >>> 24) & 0xFF));
        }
    }

    private static final String XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"; //$NON-NLS-1$

    // Формат ODF 1.1, а не 1.2: Excel 2007 (Office 12) открывает только ODF 1.1, файл с
    // office:version="1.2" встречает диалогом «не удалось прочитать / восстановить».
    // Поэтому нигде нет office:version, а manifest — в стиле OpenOffice.org 1.0.
    private static final String MANIFEST = XML_DECL
        + "<!DOCTYPE manifest:manifest PUBLIC \"-//OpenOffice.org//DTD Manifest 1.0//EN\" \"Manifest.dtd\">\n" //$NON-NLS-1$
        + "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\">\n" //$NON-NLS-1$
        + "<manifest:file-entry manifest:media-type=\"application/vnd.oasis.opendocument.spreadsheet\"" //$NON-NLS-1$
        + " manifest:full-path=\"/\"/>\n" //$NON-NLS-1$
        + "<manifest:file-entry manifest:media-type=\"text/xml\" manifest:full-path=\"content.xml\"/>\n" //$NON-NLS-1$
        + "<manifest:file-entry manifest:media-type=\"text/xml\" manifest:full-path=\"styles.xml\"/>\n" //$NON-NLS-1$
        + "<manifest:file-entry manifest:media-type=\"text/xml\" manifest:full-path=\"meta.xml\"/>\n" //$NON-NLS-1$
        + "<manifest:file-entry manifest:media-type=\"text/xml\" manifest:full-path=\"settings.xml\"/>\n" //$NON-NLS-1$
        + "</manifest:manifest>\n"; //$NON-NLS-1$

    private static final String STYLES = XML_DECL
        + "<office:document-styles" //$NON-NLS-1$
        + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"" //$NON-NLS-1$
        + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\"" //$NON-NLS-1$
        + " xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\"" //$NON-NLS-1$
        + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\">\n" //$NON-NLS-1$
        + "<office:styles>\n" //$NON-NLS-1$
        + "<style:default-style style:family=\"table-cell\"/>\n" //$NON-NLS-1$
        + "<style:style style:name=\"Default\" style:family=\"table-cell\"/>\n" //$NON-NLS-1$
        + "</office:styles>\n" //$NON-NLS-1$
        + "<office:automatic-styles>\n" //$NON-NLS-1$
        + "<style:page-layout style:name=\"pm1\">" //$NON-NLS-1$
        + "<style:page-layout-properties fo:margin=\"0.7874in\"/></style:page-layout>\n" //$NON-NLS-1$
        + "</office:automatic-styles>\n" //$NON-NLS-1$
        + "<office:master-styles>\n" //$NON-NLS-1$
        + "<style:master-page style:name=\"Default\" style:page-layout-name=\"pm1\"/>\n" //$NON-NLS-1$
        + "</office:master-styles>\n" //$NON-NLS-1$
        + "</office:document-styles>\n"; //$NON-NLS-1$

    private static final String SETTINGS = XML_DECL
        + "<office:document-settings" //$NON-NLS-1$
        + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"" //$NON-NLS-1$
        + " xmlns:config=\"urn:oasis:names:tc:opendocument:xmlns:config:1.0\">" //$NON-NLS-1$
        + "<office:settings/></office:document-settings>\n"; //$NON-NLS-1$

    private static String meta(LocalDateTime now)
    {
        String iso = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")); //$NON-NLS-1$
        return XML_DECL
            + "<office:document-meta" //$NON-NLS-1$
            + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"" //$NON-NLS-1$
            + " xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\"" //$NON-NLS-1$
            + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n" //$NON-NLS-1$
            + "<office:meta><meta:generator>EDT.Comfort</meta:generator>" //$NON-NLS-1$
            + "<meta:creation-date>" + iso + "</meta:creation-date>" //$NON-NLS-1$ //$NON-NLS-2$
            + "<dc:date>" + iso + "</dc:date></office:meta>\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "</office:document-meta>\n"; //$NON-NLS-1$
    }

    private static String content(Model model)
    {
        int columns = columnCount(model);
        StringBuilder b = new StringBuilder(8192);
        b.append(XML_DECL);
        b.append("<office:document-content" //$NON-NLS-1$
            + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"" //$NON-NLS-1$
            + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\"" //$NON-NLS-1$
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\"" //$NON-NLS-1$
            + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\"" //$NON-NLS-1$
            + " xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\">\n"); //$NON-NLS-1$
        b.append("<office:scripts/>\n"); //$NON-NLS-1$
        b.append("<office:font-face-decls/>\n"); //$NON-NLS-1$
        b.append("<office:automatic-styles>\n"); //$NON-NLS-1$
        b.append("<style:style style:name=\"co1\" style:family=\"table-column\">" //$NON-NLS-1$
            + "<style:table-column-properties fo:break-before=\"auto\" style:column-width=\"2in\"/></style:style>\n"); //$NON-NLS-1$
        // Стиль строки не задаём: Excel 2007 при импорте ODF трактует table-row без
        // явной style:row-height как высоту 0 — все строки схлопываются, лист выглядит пустым.
        b.append("<style:style style:name=\"ta1\" style:family=\"table\" style:master-page-name=\"Default\">" //$NON-NLS-1$
            + "<style:table-properties table:display=\"true\" style:writing-mode=\"lr-tb\"/></style:style>\n"); //$NON-NLS-1$
        b.append("<style:style style:name=\"ceh\" style:family=\"table-cell\" style:parent-style-name=\"Default\">" //$NON-NLS-1$
            + "<style:text-properties fo:font-weight=\"bold\"/></style:style>\n"); //$NON-NLS-1$
        b.append("</office:automatic-styles>\n"); //$NON-NLS-1$
        b.append("<office:body><office:spreadsheet>\n"); //$NON-NLS-1$
        b.append("<table:table table:name=\"Список\" table:style-name=\"ta1\">\n"); //$NON-NLS-1$
        b.append("<table:table-column table:style-name=\"co1\" table:default-cell-style-name=\"Default\""); //$NON-NLS-1$
        if (columns > 1)
            b.append(" table:number-columns-repeated=\"").append(columns).append('"'); //$NON-NLS-1$
        b.append("/>\n"); //$NON-NLS-1$
        if (!model.headers.isEmpty())
        {
            b.append("<table:table-row>"); //$NON-NLS-1$
            for (int c = 0; c < columns; c++)
                b.append(headerCell(c < model.headers.size() ? model.headers.get(c) : "")); //$NON-NLS-1$
            b.append("</table:table-row>\n"); //$NON-NLS-1$
        }
        for (String[] row : model.rows)
        {
            b.append("<table:table-row>"); //$NON-NLS-1$
            for (int c = 0; c < columns; c++)
                b.append(dataCell(c < row.length ? row[c] : "")); //$NON-NLS-1$
            b.append("</table:table-row>\n"); //$NON-NLS-1$
        }
        b.append("</table:table>\n"); //$NON-NLS-1$
        b.append("</office:spreadsheet></office:body>\n"); //$NON-NLS-1$
        b.append("</office:document-content>\n"); //$NON-NLS-1$
        return b.toString();
    }

    private static int columnCount(Model model)
    {
        int columns = model.headers.size();
        for (String[] row : model.rows)
            columns = Math.max(columns, row.length);
        return Math.max(1, columns);
    }

    private static String headerCell(String value)
    {
        return "<table:table-cell table:style-name=\"ceh\" office:value-type=\"string\"><text:p>" //$NON-NLS-1$
            + escape(value) + "</text:p></table:table-cell>"; //$NON-NLS-1$
    }

    private static String dataCell(String value)
    {
        Double number = asNumber(value);
        if (number != null)
        {
            return "<table:table-cell office:value-type=\"float\" office:value=\"" //$NON-NLS-1$
                + toPlainString(number) + "\"><text:p>" + escape(value) + "</text:p></table:table-cell>"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (value.isEmpty())
            return "<table:table-cell/>"; //$NON-NLS-1$
        return "<table:table-cell office:value-type=\"string\"><text:p>" //$NON-NLS-1$
            + escape(value) + "</text:p></table:table-cell>"; //$NON-NLS-1$
    }

    /**
     * @return значение ячейки как число, если она целиком разбирается как число (в текущей
     *         локали или в инвариантной), иначе {@code null}. Строки с ведущим нулём и без
     *         десятичного разделителя (артикулы, коды, индексы) числом не считаются.
     */
    private static Double asNumber(String value)
    {
        String t = value.trim();
        if (t.isEmpty() || t.length() > 30)
            return null;
        boolean hasDigit = false;
        for (int i = 0; i < t.length(); i++)
        {
            if (Character.isDigit(t.charAt(i)))
            {
                hasDigit = true;
                break;
            }
        }
        if (!hasDigit)
            return null;
        String digits = t.startsWith("-") || t.startsWith("+") ? t.substring(1) : t; //$NON-NLS-1$ //$NON-NLS-2$
        if (digits.length() > 1 && digits.charAt(0) == '0'
            && digits.indexOf(',') < 0 && digits.indexOf('.') < 0)
            return null;
        Double parsed = parseWith(t, NumberFormat.getInstance(Locale.getDefault()));
        if (parsed == null)
            parsed = parseWith(t, NumberFormat.getInstance(Locale.ROOT));
        return parsed;
    }

    private static Double parseWith(String value, NumberFormat format)
    {
        ParsePosition position = new ParsePosition(0);
        Number number = format.parse(value, position);
        if (number == null || position.getIndex() != value.length())
            return null;
        return number.doubleValue();
    }

    private static String toPlainString(double value)
    {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15)
            return Long.toString((long) value);
        return java.math.BigDecimal.valueOf(value).toPlainString();
    }

    private static String escape(String value)
    {
        StringBuilder b = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++)
        {
            char ch = value.charAt(i);
            switch (ch)
            {
                case '&' -> b.append("&amp;"); //$NON-NLS-1$
                case '<' -> b.append("&lt;"); //$NON-NLS-1$
                case '>' -> b.append("&gt;"); //$NON-NLS-1$
                case '"' -> b.append("&quot;"); //$NON-NLS-1$
                case '\t' -> b.append("<text:tab/>"); //$NON-NLS-1$
                case '\n' -> b.append("<text:line-break/>"); //$NON-NLS-1$
                default ->
                {
                    if (ch >= 0x20 || ch == '\r')
                        b.append(ch);
                }
            }
        }
        return b.toString();
    }

    private static final class Model
    {
        final ArrayList<String> headers = new ArrayList<>();

        final ArrayList<String[]> rows = new ArrayList<>();
    }
}
