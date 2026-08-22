package tormozit;

import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/** Поиск SWT-корня палитры «Свойства» и буфер обмена для имени свойства. */
final class PropertySheetUiContext
{
    private PropertySheetUiContext() {}

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
                return fromMethod;
        }
        Object palette = Global.invoke(page, "getPaletteComponent"); //$NON-NLS-1$
        Object scene = palette != null ? Global.invoke(palette, "getScene") : null; //$NON-NLS-1$
        if (scene != null)
        {
            Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
            Object composite = renderer != null ? Global.invoke(renderer, "getComposite") : null; //$NON-NLS-1$
            Composite fromRenderer = compositeFrom(composite);
            if (fromRenderer != null && isPlausiblePaletteRoot(fromRenderer))
                return fromRenderer;
        }
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

    static boolean isPlausiblePaletteRoot(Composite composite)
    {
        if (composite == null || composite.isDisposed())
            return false;
        String cn = composite.getClass().getSimpleName();
        return !cn.contains("ToolBar") && !cn.contains("Toolbar") && !cn.contains("Menu"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
