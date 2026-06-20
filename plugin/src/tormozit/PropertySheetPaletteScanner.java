package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;

/** Поиск строк свойств: LabelViewModel в renderer + FieldComponent + SWT/LWT. */
final class PropertySheetPaletteScanner
{
    private static final String LABEL_VIEW_MODEL = "LabelViewModel"; //$NON-NLS-1$
    private static final String FIELD_COMPONENT = "FieldComponent"; //$NON-NLS-1$
    private static final int MAX_NAME_HEIGHT = 48;

    private PropertySheetPaletteScanner() {}

    /** Число LabelViewModel в renderer (ожидаемое кол-во строк при успешном скане). */
    static int expectedLabelRows(Object scene)
    {
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        if (renderer == null)
            return 0;
        Object mapObj = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$
        if (!(mapObj instanceof Map))
            return 0;
        int count = 0;
        for (Object key : ((Map<?, ?>) mapObj).keySet())
        {
            if (key != null && key.getClass().getName().contains(LABEL_VIEW_MODEL))
                count++;
        }
        return count;
    }

    static List<PropertySheetPaletteRow> scan(Object scene, Object page)
    {
        Set<PropertySheetPaletteRow> rows = new LinkedHashSet<>();
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        boolean lwt = renderer != null && renderer.getClass().getName().contains("Lwt"); //$NON-NLS-1$
        int expected = expectedLabelRows(scene);
        Composite root = PropertySheetUiContext.findPaletteRoot(page);
        if (root == null || root.isDisposed() || !PropertySheetUiContext.isPlausiblePaletteRoot(root))
            return Collections.emptyList();

        PropertySheetDebug.scan("START lwt=" + lwt + " expected=" + expected //$NON-NLS-1$ //$NON-NLS-2$
                + " renderer=" + PropertySheetDebug.safe(renderer) //$NON-NLS-1$
                + " root=" + PropertySheetDebug.safe(root)); //$NON-NLS-1$

        if (lwt)
        {
            int before = rows.size();
            scanViaFieldLayout(scene, page, rows);
            logPhase("fieldLayout", before, rows.size()); //$NON-NLS-1$

            before = rows.size();
            scanViaLwtFieldRows(scene, page, rows);
            logPhase("lwtFieldRow", before, rows.size()); //$NON-NLS-1$

            before = rows.size();
            scanViaLabelVmText(scene, page, rows);
            logPhase("labelVmText", before, rows.size()); //$NON-NLS-1$

            before = rows.size();
            scanViaRenderer(scene, page, rows);
            logPhase("renderer", before, rows.size()); //$NON-NLS-1$

            before = rows.size();
            supplementViaRenderer(scene, page, rows);
            logPhase("renderer+", before, rows.size()); //$NON-NLS-1$
        }
        else
        {
            int before = rows.size();
            scanViaRenderer(scene, page, rows);
            logPhase("renderer", before, rows.size()); //$NON-NLS-1$
        }

        if (rows.isEmpty())
        {
            int before = rows.size();
            scanViaFieldComponents(scene, rows);
            logPhase("fieldComponents", before, rows.size()); //$NON-NLS-1$
        }
        if (rows.isEmpty())
        {
            int before = rows.size();
            scanViaSwt(root, rows);
            logPhase("swtWalk", before, rows.size()); //$NON-NLS-1$
        }

        List<PropertySheetPaletteRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingInt(PropertySheetPaletteScanner::rowTop));
        logScanResult(scene, page, sorted, lwt, expected);
        return sorted;
    }

    private static void logPhase(String phase, int before, int after)
    {
        int added = after - before;
        if (added > 0)
            PropertySheetDebug.scan("phase " + phase + " +" + added + " total=" + after); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static void scanViaRenderer(Object scene, Object page, Set<PropertySheetPaletteRow> rows)
    {
        addRendererRows(scene, page, rows, false);
    }

    private static void supplementViaRenderer(Object scene, Object page, Set<PropertySheetPaletteRow> rows)
    {
        addRendererRows(scene, page, rows, true);
    }

    private static void addRendererRows(Object scene, Object page, Set<PropertySheetPaletteRow> rows,
            boolean skipExistingNames)
    {
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        if (renderer == null)
            return;
        Object mapObj = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$
        if (!(mapObj instanceof Map))
            return;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) mapObj).entrySet())
        {
            Object vm = entry.getKey();
            if (vm == null || !vm.getClass().getName().contains(LABEL_VIEW_MODEL))
                continue;
            String name = textOfViewModel(vm);
            Object view = entry.getValue();
            if (name.isEmpty() || (skipExistingNames && hasRowForView(rows, view)))
                continue;
            Control nameControl = PropertySheetControlInterop.resolveNameControl(scene, vm, view, name);
            nameControl = PropertySheetControlInterop.narrowNameControl(nameControl, name);
            if (!isPlausibleNameControl(nameControl, name))
            {
                PropertySheetDebug.scanVerbose("skip renderer " + PropertySheetDebug.quote(name) //$NON-NLS-1$
                        + " reason=" + PropertySheetDebug.nameControlRejectReason(nameControl, name)); //$NON-NLS-1$
                continue;
            }
            if (hasRowUsingControl(rows, nameControl, name))
            {
                PropertySheetDebug.scanVerbose("skip renderer " + PropertySheetDebug.quote(name) //$NON-NLS-1$
                        + " reason=controlAlreadyUsed " + PropertySheetDebug.controlBrief(nameControl)); //$NON-NLS-1$
                continue;
            }
            addRow(rows, nameControl, name, "renderer", view, page, scene); //$NON-NLS-1$
        }
    }

    private static void scanViaLabelVmText(Object scene, Object page, Set<PropertySheetPaletteRow> rows)
    {
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        if (renderer == null || !renderer.getClass().getName().contains("Lwt")) //$NON-NLS-1$
            return;
        Object mapObj = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$
        if (!(mapObj instanceof Map))
            return;
        Composite root = PropertySheetUiContext.findPaletteRoot(page);
        if (root == null || root.isDisposed())
        {
            PropertySheetDebug.scanProblem("labelVmText ABORT root=" + PropertySheetDebug.safe(root)); //$NON-NLS-1$
            return;
        }

        for (Map.Entry<?, ?> entry : ((Map<?, ?>) mapObj).entrySet())
        {
            Object vm = entry.getKey();
            if (vm == null || !vm.getClass().getName().contains(LABEL_VIEW_MODEL))
                continue;
            String name = textOfViewModel(vm);
            Object view = entry.getValue();
            if (name.isEmpty() || hasRowForView(rows, view))
                continue;
            Control nameControl = findControlByText(root, name);
            if (isSectionTitleLabel(nameControl))
            {
                PropertySheetDebug.scanVerbose("skip labelVmText " + PropertySheetDebug.quote(name) //$NON-NLS-1$
                        + " reason=sectionTitle " + PropertySheetDebug.controlBrief(nameControl)); //$NON-NLS-1$
                continue;
            }
            if (!isPlausibleNameControl(nameControl, name))
            {
                PropertySheetDebug.scanVerbose("skip labelVmText " + PropertySheetDebug.quote(name) //$NON-NLS-1$
                        + " found=" + PropertySheetDebug.controlBrief(nameControl) //$NON-NLS-1$
                        + " reason=" + PropertySheetDebug.nameControlRejectReason(nameControl, name)); //$NON-NLS-1$
                continue;
            }
            addRow(rows, nameControl, name, "labelVmText", view, page, scene); //$NON-NLS-1$
        }
    }

    /** LWT: DtLayoutComposite внутри Section — хост строки (текст только в LightLabel). */
    private static void scanViaLwtFieldRows(Object scene, Object page, Set<PropertySheetPaletteRow> rows)
    {
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        if (renderer == null || !renderer.getClass().getName().contains("Lwt")) //$NON-NLS-1$
            return;
        Composite root = PropertySheetUiContext.findPaletteRoot(page);
        if (root == null || root.isDisposed())
            return;

        List<LabelEntry> pending = collectMissingLabelEntries(renderer, rows);
        if (pending.isEmpty())
            return;

        List<Composite> paintHosts = PropertySheetControlInterop.collectLwtPaintHosts(root);
        PropertySheetDebug.scan("lwtFieldRow names=" + pending.size() + " hosts=" + paintHosts.size()); //$NON-NLS-1$ //$NON-NLS-2$
        if (PropertySheetDebug.isVerbose())
        {
            for (int i = 0; i < paintHosts.size(); i++)
                PropertySheetDebug.scanVerbose("  lwtHost[" + i + "] " //$NON-NLS-1$ //$NON-NLS-2$
                        + PropertySheetDebug.controlBrief(paintHosts.get(i)));
        }

        for (LabelEntry entry : pending)
        {
            if (hasRowForView(rows, entry.view))
                continue;

            Composite rowComposite = PropertySheetControlInterop.leafFieldRowHostForView(entry.view);
            if (rowComposite == null || rowComposite.isDisposed())
                rowComposite = PropertySheetControlInterop.findPaintHostForView(entry.view, root);
            if (rowComposite == null || rowComposite.isDisposed())
            {
                PropertySheetDebug.scanVerbose("skip lwtFieldRow " + PropertySheetDebug.quote(entry.name) //$NON-NLS-1$
                        + " reason=noHost"); //$NON-NLS-1$
                continue;
            }

            Control nameControl = rowComposite;
            PropertySheetControlInterop.refreshLwtRowGeometry(nameControl, entry.view, entry.name);
            addRow(rows, nameControl, entry.name, "lwtFieldRow", entry.view, page, scene); //$NON-NLS-1$
        }
    }

    private static final class LabelEntry
    {
        final String name;
        final Object view;

        LabelEntry(String name, Object view)
        {
            this.name = name;
            this.view = view;
        }
    }

    private static List<LabelEntry> collectMissingLabelEntries(Object renderer, Set<PropertySheetPaletteRow> rows)
    {
        List<LabelEntry> entries = new ArrayList<>();
        Object mapObj = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$
        if (!(mapObj instanceof Map))
            return entries;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) mapObj).entrySet())
        {
            Object key = e.getKey();
            if (key == null || !key.getClass().getName().contains(LABEL_VIEW_MODEL))
                continue;
            String name = textOfViewModel(key);
            Object view = e.getValue();
            if (!name.isEmpty() && !hasRowForView(rows, view))
                entries.add(new LabelEntry(name, view));
        }
        return entries;
    }

    /** @deprecated use {@link #collectMissingLabelEntries} */
    private static List<String> collectMissingLabelNames(Object renderer, Set<PropertySheetPaletteRow> rows)
    {
        List<String> names = new ArrayList<>();
        for (LabelEntry entry : collectMissingLabelEntries(renderer, rows))
            names.add(entry.name);
        return names;
    }

    private static List<Composite> collectLwtFieldRowComposites(Composite root)
    {
        List<Composite> out = new ArrayList<>();
        collectLwtFieldRowComposites(root, out);
        out.sort(Comparator.comparingInt(c -> c.toDisplay(0, 0).y));
        return out;
    }

    private static void collectLwtFieldRowComposites(Composite composite, List<Composite> out)
    {
        if (composite == null || composite.isDisposed())
            return;
        if (PropertySheetUiContext.isFilterAreaControl(composite))
            return;

        if (isLwtFieldRowComposite(composite))
        {
            out.add(composite);
            return;
        }

        for (Control child : composite.getChildren())
        {
            if (child instanceof Composite)
                collectLwtFieldRowComposites((Composite) child, out);
        }
    }

    /** @see PropertySheetControlInterop#isLwtFieldRowComposite(Composite) */
    private static boolean isLwtFieldRowComposite(Composite composite)
    {
        return PropertySheetControlInterop.isLwtFieldRowComposite(composite);
    }

    /** LWT: геометрия строк полей + сопоставление LabelViewModel (в т.ч. при активном фильтре). */
    private static void scanViaFieldLayout(Object scene, Object page, Set<PropertySheetPaletteRow> rows)
    {
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        if (renderer == null || !renderer.getClass().getName().contains("Lwt")) //$NON-NLS-1$
            return;
        Object mapObj = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$
        if (!(mapObj instanceof Map))
            return;

        List<LabelEntry> pending = collectMissingLabelEntries(renderer, rows);
        if (pending.isEmpty())
            return;

        Composite root = PropertySheetUiContext.findPaletteRoot(page);
        if (root == null || root.isDisposed())
        {
            PropertySheetDebug.scanProblem("fieldLayout ABORT root=" + PropertySheetDebug.safe(root)); //$NON-NLS-1$
            return;
        }

        List<Control> slots = collectFieldNameSlots(root);
        PropertySheetDebug.scan("fieldLayout names=" + pending.size() + " slots=" + slots.size() //$NON-NLS-1$ //$NON-NLS-2$
                + " root=" + PropertySheetDebug.safe(root)); //$NON-NLS-1$
        if (slots.isEmpty())
            return;

        List<Control> free = new ArrayList<>(slots);
        for (PropertySheetPaletteRow row : rows)
            free.remove(row.nameControl);

        for (LabelEntry entry : new ArrayList<>(pending))
        {
            if (hasRowForView(rows, entry.view))
                continue;
            String name = entry.name;
            Control byText = findControlByText(root, name);
            if (isSectionTitleLabel(byText))
            {
                PropertySheetDebug.scanVerbose("skip fieldLayout " + PropertySheetDebug.quote(name) //$NON-NLS-1$
                        + " reason=sectionTitle " + PropertySheetDebug.controlBrief(byText)); //$NON-NLS-1$
                continue;
            }
            if (isPlausibleNameControl(byText, name))
            {
                free.remove(byText);
                addRow(rows, byText, name, "fieldLayout+text", entry.view, page, scene); //$NON-NLS-1$
            }
            else
            {
                PropertySheetDebug.scanVerbose("fieldLayout text miss " + PropertySheetDebug.quote(name) //$NON-NLS-1$
                        + " found=" + PropertySheetDebug.controlBrief(byText)); //$NON-NLS-1$
            }
        }

        pending = collectMissingLabelEntries(renderer, rows);
        if (pending.isEmpty())
            return;

        free.sort(Comparator.comparingInt(c -> c.toDisplay(0, 0).y));
        int n = Math.min(pending.size(), free.size());
        PropertySheetDebug.scan("fieldLayout Y-zip missing=" + pending.size() + " freeSlots=" + free.size() + " pair=" + n); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (n > 0)
        {
            for (int i = 0; i < n; i++)
            {
                Control slot = free.get(i);
                LabelEntry entry = pending.get(i);
                String name = entry.name;
                if (!isPlausibleNameControl(slot, name))
                {
                    PropertySheetDebug.scanVerbose("fieldLayout Y-zip skip " + PropertySheetDebug.quote(name) //$NON-NLS-1$
                            + " slot=" + PropertySheetDebug.controlBrief(slot) //$NON-NLS-1$
                            + " reason=" + PropertySheetDebug.nameControlRejectReason(slot, name)); //$NON-NLS-1$
                    continue;
                }
                addRow(rows, slot, name, "fieldLayout+Y", entry.view, page, scene); //$NON-NLS-1$
            }
        }
    }

    private static void addRow(Set<PropertySheetPaletteRow> rows, Control nameControl, String name, String source)
    {
        addRow(rows, nameControl, name, source, null, null, null);
    }

    private static void addRow(Set<PropertySheetPaletteRow> rows, Control nameControl, String name,
            String source, Object lwtView, Object page)
    {
        addRow(rows, nameControl, name, source, lwtView, page, null);
    }

    private static void addRow(Set<PropertySheetPaletteRow> rows, Control nameControl, String name,
            String source, Object lwtView, Object page, Object scene)
    {
        if (nameControl instanceof Composite)
        {
            Composite composite = (Composite) nameControl;
            String cn = composite.getClass().getName();
            if (lwtView == null && cn.contains("LayoutComposite") && composite.getSize().y > 250) //$NON-NLS-1$
            {
                PropertySheetDebug.scanVerbose("skip ADD [" + source + "] " + PropertySheetDebug.quote(name) //$NON-NLS-1$ //$NON-NLS-2$
                        + " reason=sectionContainer " + PropertySheetDebug.controlBrief(nameControl)); //$NON-NLS-1$
                return;
            }
        }

        Control paintControl = nameControl;
        Composite rowComposite;
        if (lwtView != null)
        {
            Composite leaf = PropertySheetControlInterop.leafFieldRowHostForView(lwtView);
            if (leaf != null && !leaf.isDisposed())
            {
                paintControl = leaf;
                rowComposite = leaf;
            }
            else if (nameControl instanceof Composite)
            {
                rowComposite = (Composite) nameControl;
                paintControl = nameControl;
            }
            else
            {
                rowComposite = findRowComposite(nameControl);
                paintControl = nameControl;
            }
            PropertySheetControlInterop.refreshLwtRowGeometry(rowComposite, lwtView, name);
        }
        else
            rowComposite = findRowComposite(nameControl);
        String modelName = PropertySheetControlInterop.resolveModelPropertyName(page, scene, lwtView, name);
        rows.add(new PropertySheetPaletteRow(paintControl, rowComposite,
                PropertySheetUiContext.rowControls(rowComposite, paintControl), name, lwtView, modelName));
        PropertySheetDebug.scanVerbose("ADD [" + source + "] " + PropertySheetDebug.quote(name) //$NON-NLS-1$ //$NON-NLS-2$
                + " model=" + PropertySheetDebug.quote(modelName) //$NON-NLS-1$
                + " ctrl=" + PropertySheetDebug.controlBrief(nameControl) //$NON-NLS-1$
                + " row=" + PropertySheetDebug.controlBrief(rowComposite)); //$NON-NLS-1$
    }

    private static void addRow(Set<PropertySheetPaletteRow> rows, Control nameControl, String name)
    {
        addRow(rows, nameControl, name, "?"); //$NON-NLS-1$
    }

    private static boolean hasStoredLwtOrigin(Control control, String propertyName)
    {
        if (control == null || propertyName == null || propertyName.isEmpty())
            return false;
        Object perRow = control.getData(PropertySheetControlInterop.LWT_ORIGIN_KEY + '.' + propertyName);
        return perRow instanceof Point;
    }

    private static boolean hasRowForView(Set<PropertySheetPaletteRow> rows, Object view)
    {
        if (view == null)
            return false;
        for (PropertySheetPaletteRow row : rows)
        {
            if (row.lwtView == view)
                return true;
        }
        return false;
    }

    /** Одна строка на имя; один composite может обслуживать несколько LWT-строк. */
    private static boolean hasRowUsingControl(Set<PropertySheetPaletteRow> rows, Control control, String name)
    {
        if (control == null)
            return false;
        for (PropertySheetPaletteRow row : rows)
        {
            if (row.nameControl == control && name.equals(row.propertyName))
                return true;
        }
        return false;
    }

    private static boolean isSectionTitleLabel(Control control)
    {
        if (!(control instanceof Label) || control.isDisposed())
            return false;
        for (Composite parent = control.getParent(); parent != null && !parent.isDisposed(); parent = parent.getParent())
        {
            if (PropertySheetControlInterop.isLwtFieldRowComposite(parent))
                return false;
            String cn = parent.getClass().getName();
            if (cn.contains("Section") || cn.contains("ExpandableComposite")) //$NON-NLS-1$ //$NON-NLS-2$
                return true;
        }
        return false;
    }

    private static boolean isPlausibleNameControl(Control control, String name)
    {
        control = PropertySheetControlInterop.narrowNameControl(control, name);
        if (control == null || control.isDisposed())
            return false;
        if (PropertySheetControlInterop.isTwistieOrDecor(control))
            return false;
        String cn = control.getClass().getName();
        if (cn.contains("Hyperlink") || cn.contains("Link")) //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        if (PropertySheetUiContext.isFilterAreaControl(control))
            return false;
        Point size = control.getSize();
        if (size.x > 0 && size.x < 20)
            return false;
        int maxHeight = control instanceof Label ? 48 : 36;
        if (size.y > maxHeight)
            return false;
        String visible = PropertySheetControlInterop.controlText(control);
        if (!visible.isEmpty() && !name.equals(visible))
            return false;
        return true;
    }

    /** Наименьший по площади контрол с точным текстом (не секция/группа). */
    private static Control findControlByText(Composite composite, String text)
    {
        Control[] best = new Control[] { null };
        int[] bestArea = new int[] { Integer.MAX_VALUE };
        findControlByTextDeep(composite, text, best, bestArea);
        return best[0];
    }

    private static void findControlByTextDeep(Composite composite, String text,
            Control[] best, int[] bestArea)
    {
        if (composite == null || composite.isDisposed())
            return;
        for (Control child : composite.getChildren())
        {
            if (child == null || child.isDisposed() || PropertySheetUiContext.isFilterAreaControl(child))
                continue;
            String childText = PropertySheetControlInterop.controlText(child);
            if (text.equals(childText))
            {
                Point size = child.getSize();
                int area = Math.max(1, size.x) * Math.max(1, size.y);
                if (area < bestArea[0] && size.y <= MAX_NAME_HEIGHT)
                {
                    bestArea[0] = area;
                    best[0] = child;
                }
            }
            if (child instanceof Composite)
                findControlByTextDeep((Composite) child, text, best, bestArea);
        }
    }

    private static List<Control> collectFieldNameSlots(Composite root)
    {
        List<Control> slots = new ArrayList<>();
        collectFieldNameSlots(root, slots);
        slots.sort(Comparator.comparingInt(c -> c.toDisplay(0, 0).y));
        return slots;
    }

    private static void collectFieldNameSlots(Composite composite, List<Control> out)
    {
        if (composite == null || composite.isDisposed())
            return;
        if (PropertySheetUiContext.isFilterAreaControl(composite))
            return;

        if (tryAddFieldRowSlot(composite, out))
            return;

        if (isLwtFieldRowComposite(composite))
        {
            out.add(composite);
            return;
        }

        for (Control child : composite.getChildren())
        {
            if (child instanceof Composite)
                collectFieldNameSlots((Composite) child, out);
        }
    }

    /** Строка поля: слева имя, справа (вложенный) редактор значения. */
    private static boolean tryAddFieldRowSlot(Composite composite, List<Control> out)
    {
        Control[] children = composite.getChildren();
        if (children.length < 2)
            return false;

        Control labelCandidate = null;
        int minX = Integer.MAX_VALUE;
        boolean hasEditor = false;

        for (Control child : children)
        {
            if (child == null || child.isDisposed() || PropertySheetUiContext.isFilterAreaControl(child))
                continue;
            if (looksLikeValueEditor(child) || containsValueEditor(child))
            {
                hasEditor = true;
                continue;
            }
            int x = child.toDisplay(0, 0).x;
            if (x < minX)
            {
                minX = x;
                labelCandidate = child;
            }
        }

        if (!hasEditor || labelCandidate == null)
            return false;
        Point size = labelCandidate.getSize();
        if (size.y > MAX_NAME_HEIGHT)
            return false;
        out.add(labelCandidate);
        return true;
    }

    private static boolean containsValueEditor(Control control)
    {
        if (control == null || control.isDisposed())
            return false;
        if (looksLikeValueEditor(control))
            return true;
        if (control instanceof Composite)
        {
            for (Control child : ((Composite) control).getChildren())
            {
                if (containsValueEditor(child))
                    return true;
            }
        }
        return false;
    }

    private static boolean looksLikeValueEditor(Control child)
    {
        if (child instanceof org.eclipse.swt.widgets.Text)
            return true;
        String cn = child.getClass().getName();
        return cn.contains("Combo") || cn.contains("Spinner") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("CCombo") || cn.contains("Hyperlink") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("LightText") || cn.contains("LightCombo") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("LightSpinner") || cn.contains("LightCheckBox"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void scanViaFieldComponents(Object scene, Set<PropertySheetPaletteRow> rows)
    {
        if (scene == null)
            return;
        Object root = Global.invoke(scene, "getComponent"); //$NON-NLS-1$
        if (root != null)
            walkComponent(scene, root, rows);
    }

    private static void walkComponent(Object scene, Object component, Set<PropertySheetPaletteRow> rows)
    {
        if (component == null)
            return;
        if (component.getClass().getName().contains(FIELD_COMPONENT))
            addFieldRow(scene, component, rows);
        Iterator<?> it = childrenOf(component);
        if (it == null)
            return;
        while (it.hasNext())
        {
            Object child = it.next();
            if (child != null)
                walkComponent(scene, child, rows);
        }
    }

    private static void addFieldRow(Object scene, Object fieldComponent, Set<PropertySheetPaletteRow> rows)
    {
        Object labelComp = Global.getField(fieldComponent, "label"); //$NON-NLS-1$
        if (labelComp == null)
            return;

        Object labelVm = Global.invoke(labelComp, "getLabel"); //$NON-NLS-1$
        if (labelVm == null)
            labelVm = Global.getField(labelComp, "viewModel"); //$NON-NLS-1$

        String name = textOfViewModel(labelVm);
        if (name.isEmpty())
        {
            Object labelText = Global.getField(fieldComponent, "labelText"); //$NON-NLS-1$
            if (labelText != null)
                name = labelText.toString();
        }
        if (name.isEmpty())
            return;

        Control nameControl = PropertySheetControlInterop.resolveNameControl(scene, labelVm, labelComp, name);
        if (!isPlausibleNameControl(nameControl, name)
                && !PropertySheetUiContext.isPropertyNameControl(nameControl, name))
            return;
        if (hasRowUsingControl(rows, nameControl, name))
            return;
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        Object lwtView = labelVm != null ? PropertySheetControlInterop.viewForViewModel(renderer, labelVm) : null;
        addRow(rows, nameControl, name, "fieldComponent", lwtView, null, scene); //$NON-NLS-1$
    }

    private static void scanViaSwt(Composite root, Set<PropertySheetPaletteRow> rows)
    {
        if (root == null || root.isDisposed())
            return;
        walkComposite(root, rows);
    }

    private static void walkComposite(Composite composite, Set<PropertySheetPaletteRow> rows)
    {
        for (Control child : composite.getChildren())
        {
            if (child == null || child.isDisposed())
                continue;
            String name = PropertySheetControlInterop.controlText(child);
            if (!name.isEmpty() && PropertySheetUiContext.isPropertyNameControl(child, name)
                    && isPlausibleNameControl(child, name) && !hasRowUsingControl(rows, child, name))
            {
                addRow(rows, child, name);
            }
            if (child instanceof Composite)
                walkComposite((Composite) child, rows);
        }
    }

    private static Composite findRowComposite(Control nameControl)
    {
        return PropertySheetUiContext.fieldRowOf(nameControl);
    }

    private static String textOfViewModel(Object viewModel)
    {
        if (viewModel == null)
            return ""; //$NON-NLS-1$
        Object text = Global.invoke(viewModel, "getText"); //$NON-NLS-1$
        if (text instanceof String)
            return (String) text;
        return SmartTreeElementLabels.resolve(viewModel, null);
    }

    private static Iterator<?> childrenOf(Object result)
    {
        if (result instanceof Iterable)
            return ((Iterable<?>) result).iterator();
        if (result instanceof Object[])
        {
            Object[] arr = (Object[]) result;
            List<Object> list = new ArrayList<>(arr.length);
            for (Object o : arr)
                list.add(o);
            return list.iterator();
        }
        if (result != null)
        {
            Object children = Global.invoke(result, "getComponents"); //$NON-NLS-1$
            if (children != null && children != result)
                return childrenOf(children);
        }
        return null;
    }

    private static int rowTop(PropertySheetPaletteRow row)
    {
        if (row == null || !row.isAlive())
            return Integer.MAX_VALUE;
        if (row.lwtView != null && PropertySheetControlInterop.isLwtPaintHost(row.nameControl))
        {
            Point origin = PropertySheetControlInterop.lwtHighlightOrigin(row.nameControl, row.propertyName);
            return row.nameControl.toDisplay(0, Math.max(0, origin.y)).y;
        }
        return row.nameControl.toDisplay(0, 0).y;
    }

    private static void logScanResult(Object scene, Object page, List<PropertySheetPaletteRow> rows,
            boolean lwt, int expected)
    {
        int rowCount = rows.size();
        // #region agent log
        int lwtRowCount = 0;
        for (PropertySheetPaletteRow row : rows)
        {
            if (row.lwtView != null)
                lwtRowCount++;
        }
        try
        {
            String line = "{\"sessionId\":\"db8c17\",\"hypothesisId\":\"H11\",\"location\":\"PropertySheetPaletteScanner.scan\"," //$NON-NLS-1$
                    + "\"message\":\"rows\",\"data\":{\"total\":" + rowCount + ",\"lwt\":" + lwtRowCount //$NON-NLS-1$
                    + ",\"expected\":" + expected + "},\"timestamp\":" + System.currentTimeMillis() + "}\n"; //$NON-NLS-1$
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("C:\\VC\\EDT.Comfort\\debug-db8c17.log"), //$NON-NLS-1$
                    line, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
        catch (Exception ignored)
        {
            // debug session only
        }
        // #endregion
        if (rowCount > 0)
        {
            boolean incomplete = expected > 0 && rowCount < expected;
            if (incomplete)
            {
                PropertySheetDebug.scanProblem("rows=" + rowCount + (lwt ? " lwt" : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + " expected=" + expected + " INCOMPLETE"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                PropertySheetDebug.scan("END rows=" + rowCount + (lwt ? " lwt" : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + " expected=" + expected + " OK"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (PropertySheetDebug.isTrace() && rowCount <= 20)
            {
                for (PropertySheetPaletteRow row : rows)
                {
                    PropertySheetDebug.scanVerbose("  row " + PropertySheetDebug.quote(row.propertyName) //$NON-NLS-1$
                            + " → " + PropertySheetDebug.controlBrief(row.nameControl)); //$NON-NLS-1$
                }
            }
            else if (PropertySheetDebug.isTrace())
            {
                PropertySheetDebug.scanVerbose("  … " + rowCount + " rows (list truncated)"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return;
        }

        if (expected <= 0)
            return;

        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        int mapSize = -1;
        int labelVm = 0;
        if (renderer != null)
        {
            Object mapObj = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$
            if (mapObj instanceof Map)
            {
                mapSize = ((Map<?, ?>) mapObj).size();
                for (Object key : ((Map<?, ?>) mapObj).keySet())
                {
                    if (key != null && key.getClass().getName().contains(LABEL_VIEW_MODEL))
                        labelVm++;
                }
            }
        }
        Composite root = PropertySheetUiContext.findPaletteRoot(page);
        List<Control> slots = root != null ? collectFieldNameSlots(root) : Collections.emptyList();
        List<Composite> lwtFieldComposites = root != null ? collectLwtFieldRowComposites(root) : Collections.emptyList();
        PropertySheetDebug.scanProblem("rows=0 FAIL expected=" + expected //$NON-NLS-1$
                + " renderer=" + PropertySheetDebug.safe(renderer) //$NON-NLS-1$
                + " mapSize=" + mapSize + " labelVm=" + labelVm + " slots=" + slots.size() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " lwtFieldRows=" + lwtFieldComposites.size() //$NON-NLS-1$
                + " root=" + PropertySheetDebug.safe(root)); //$NON-NLS-1$
        if (PropertySheetDebug.isVerbose() && root != null && slots.isEmpty())
            PropertySheetDebug.scan("root tree: " + PropertySheetDebug.compositeTreeBrief(root, 2)); //$NON-NLS-1$
        if (PropertySheetDebug.isVerbose() && labelVm > 0 && labelVm <= 30)
        {
            Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
            if (mapObj instanceof Map)
            {
                for (Object key : ((Map<?, ?>) mapObj).keySet())
                {
                    if (key == null || !key.getClass().getName().contains(LABEL_VIEW_MODEL))
                        continue;
                    String name = textOfViewModel(key);
                    PropertySheetDebug.scanVerbose("  vm " + PropertySheetDebug.quote(name)); //$NON-NLS-1$
                }
            }
        }
        if (PropertySheetDebug.isVerbose() && !slots.isEmpty() && slots.size() <= 30)
        {
            for (int i = 0; i < slots.size(); i++)
                PropertySheetDebug.scanVerbose("  slot[" + i + "] " + PropertySheetDebug.controlBrief(slots.get(i))); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
