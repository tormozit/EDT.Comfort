package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/** Контекст одного прохода UI-доработок палитры свойств. */
final class PropertySheetUiContext
{
    final Object page;
    final Object scene;
    final Object paletteComponent;
    final SmartMatcher matcher;
    final List<PropertySheetPaletteRow> rows;

    private PropertySheetPaletteRow selectedRow;

    PropertySheetUiContext(Object page, Object scene, Object paletteComponent,
            SmartMatcher matcher, List<PropertySheetPaletteRow> rows)
    {
        this.page = page;
        this.scene = scene;
        this.paletteComponent = paletteComponent;
        this.matcher = matcher;
        this.rows = rows != null ? rows : Collections.emptyList();
    }

    PropertySheetPaletteRow selectedRow()
    {
        return selectedRow;
    }

    void setSelectedRow(PropertySheetPaletteRow row)
    {
        selectedRow = row;
    }

    static PropertySheetUiContext build(Object page, SmartMatcher matcher)
    {
        if (page == null)
            return null;
        Object palette = Global.invoke(page, "getPaletteComponent"); //$NON-NLS-1$
        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        if (palette == null || scene == null)
            return null;
        List<PropertySheetPaletteRow> rows = PropertySheetPaletteScanner.scan(scene, page);
        return new PropertySheetUiContext(page, scene, palette, matcher, rows);
    }

    static void copyToClipboard(Control control, String text)
    {
        if (control == null || control.isDisposed() || text == null)
            return;
        Clipboard cb = new Clipboard(control.getDisplay());
        cb.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        cb.dispose();
    }

    static Composite findPaletteRoot(Object page)
    {
        if (page == null)
            return null;
        for (String method : new String[] {
                "getNewPaletteContent", //$NON-NLS-1$
                "getNewPaletteScrolledComposite", //$NON-NLS-1$
                "getRootComposite", //$NON-NLS-1$
                "getControl" //$NON-NLS-1$
        })
        {
            Composite fromMethod = compositeFrom(Global.invoke(page, method));
            if (fromMethod != null && isPlausiblePaletteRoot(fromMethod))
            {
                PropertySheetDebug.scanVerbose("paletteRoot via page." + method + " → " //$NON-NLS-1$ //$NON-NLS-2$
                        + PropertySheetDebug.safe(fromMethod));
                return fromMethod;
            }
        }
        Object palette = Global.invoke(page, "getPaletteComponent"); //$NON-NLS-1$
        Object scene = palette != null ? Global.invoke(palette, "getScene") : null; //$NON-NLS-1$
        if (scene != null)
        {
            Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
            Object composite = renderer != null ? Global.invoke(renderer, "getComposite") : null; //$NON-NLS-1$
            Composite fromRenderer = compositeFrom(composite);
            if (fromRenderer != null && isPlausiblePaletteRoot(fromRenderer))
            {
                PropertySheetDebug.scanVerbose("paletteRoot via renderer.getComposite → " //$NON-NLS-1$
                        + PropertySheetDebug.safe(fromRenderer));
                return fromRenderer;
            }
        }
        PropertySheetDebug.scanProblem("paletteRoot NOT FOUND page=" + PropertySheetDebug.safe(page)); //$NON-NLS-1$
        return null;
    }

    private static Composite compositeFrom(Object value)
    {
        if (value instanceof ScrolledComposite)
        {
            Control content = ((ScrolledComposite) value).getContent();
            if (content instanceof Composite)
                return (Composite) content;
        }
        return value instanceof Composite ? (Composite) value : null;
    }

    /** Прокручиваемый content палитры (полная высота canvas). */
    static Composite findPaletteContent(Object page)
    {
        if (page == null)
            return null;
        for (String method : new String[] {
                "getNewPaletteScrolledComposite", //$NON-NLS-1$
                "getNewPaletteContent" //$NON-NLS-1$
        })
        {
            Object raw = Global.invoke(page, method);
            if (raw instanceof ScrolledComposite)
            {
                Control content = ((ScrolledComposite) raw).getContent();
                if (content instanceof Composite)
                    return (Composite) content;
            }
            if (raw instanceof Composite)
                return (Composite) raw;
        }
        return findPaletteRoot(page);
    }

    static ScrolledComposite findPaletteScrolledComposite(Object page)
    {
        if (page == null)
            return null;
        Object raw = Global.invoke(page, "getNewPaletteScrolledComposite"); //$NON-NLS-1$
        return raw instanceof ScrolledComposite ? (ScrolledComposite) raw : null;
    }

    /** Координаты клика в прокручиваемом canvas content. */
    static final class PaletteCanvasSpace
    {
        final Composite content;

        PaletteCanvasSpace(Composite content)
        {
            this.content = content;
        }

        static PaletteCanvasSpace forPage(Object page)
        {
            Composite content = findPaletteContent(page);
            if (content == null || content.isDisposed())
                return null;
            return new PaletteCanvasSpace(content);
        }

        Point displayToCanvas(Point display)
        {
            return content.toControl(display);
        }

        Point canvasToDisplay(int canvasX, int canvasY)
        {
            return content.toDisplay(canvasX, canvasY);
        }

        int canvasHeight()
        {
            return content.getSize().y;
        }
    }

    static boolean isPropertyNameControl(Control control, String propertyName)
    {
        if (control == null || control.isDisposed())
            return false;
        String cn = control.getClass().getName();
        if (cn.contains("SearchBox") || cn.contains("ExpandableComposite")) //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        String text = propertyName != null && !propertyName.isEmpty()
                ? propertyName : PropertySheetControlInterop.controlText(control);
        if (text.isEmpty() || text.length() > 300)
            return false;
        Composite parent = control.getParent();
        if (parent == null)
            return false;
        if (hasValueEditor(parent))
            return true;
        Composite grand = parent.getParent();
        return grand != null && hasValueEditor(grand);
    }

    /** @deprecated use {@link #isPropertyNameControl(Control, String)} */
    static boolean isPropertyNameControl(Control control)
    {
        return isPropertyNameControl(control, PropertySheetControlInterop.controlText(control));
    }

    static boolean hasValueEditor(Composite composite)
    {
        if (composite == null || composite.isDisposed())
            return false;
        for (Control child : composite.getChildren())
        {
            if (child == null || child.isDisposed())
                continue;
            if (isValueEditor(child))
                return true;
            if (child instanceof Composite && hasValueEditor((Composite) child))
                return true;
        }
        return false;
    }

    private static boolean isValueEditor(Control control)
    {
        if (control instanceof org.eclipse.swt.widgets.Text)
            return true;
        String cn = control.getClass().getName();
        return cn.contains("Combo") || cn.contains("Spinner") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("CCombo") || cn.contains("Hyperlink") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("LightText") || cn.contains("LightCombo") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("LightSpinner") || cn.contains("LightCheckBox"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static boolean isPlausiblePaletteRoot(Composite composite)
    {
        if (composite == null || composite.isDisposed())
            return false;
        String cn = composite.getClass().getSimpleName();
        return !cn.contains("ToolBar") && !cn.contains("Toolbar") && !cn.contains("Menu"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    static String controlText(Control control)
    {
        return PropertySheetControlInterop.controlText(control);
    }

    /** Контролы одной строки поля: имя + соседи в том же {@code Composite}, без рекурсии в секции. */
    static Control[] rowControls(Composite rowComposite, Control nameControl)
    {
        List<Control> list = new ArrayList<>();
        if (nameControl != null && !nameControl.isDisposed())
            list.add(nameControl);
        Composite fieldRow = fieldRowOf(nameControl);
        if (fieldRow != null && !fieldRow.isDisposed())
        {
            for (Control sibling : fieldRow.getChildren())
            {
                if (sibling == null || sibling.isDisposed() || sibling == nameControl)
                    continue;
                if (isFilterAreaControl(sibling))
                    continue;
                list.add(sibling);
            }
        }
        return list.toArray(new Control[0]);
    }

    /** Ближайший {@code Composite} одной строки поля (~33px), не контейнер секции. */
    static Composite fieldRowOf(Control nameControl)
    {
        if (nameControl == null || nameControl.isDisposed())
            return null;
        if (nameControl instanceof Composite
                && PropertySheetControlInterop.isLwtFieldRowComposite((Composite) nameControl))
            return (Composite) nameControl;

        for (Composite current = nameControl.getParent(); current != null; current = current.getParent())
        {
            if (isFilterAreaControl(current))
                break;
            if (PropertySheetControlInterop.isLwtFieldRowComposite(current))
                return current;
        }

        Composite current = nameControl.getParent();
        for (int depth = 0; depth < 5 && current != null; depth++)
        {
            if (isFilterAreaControl(current))
                return nameControl.getParent();
            if (hasValueEditor(current))
            {
                Point size = current.getSize();
                if (size.y <= 80)
                    return current;
            }
            current = current.getParent();
        }
        return nameControl.getParent();
    }

    static boolean isFilterAreaControl(Control control)
    {
        if (control == null || control.isDisposed())
            return false;
        for (Control c = control; c != null; c = c.getParent())
        {
            String cn = c.getClass().getName();
            if (cn.contains("SearchBox") || cn.contains("FilterText") //$NON-NLS-1$ //$NON-NLS-2$
                    || cn.contains("SearchControl") || cn.contains("SearchWidget")) //$NON-NLS-1$ //$NON-NLS-2$
                return true;
        }
        return false;
    }
}
