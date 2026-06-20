package tormozit;

import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/** Одна строка свойства: имя слева + редактор значения справа. */
final class PropertySheetPaletteRow
{
    final Control nameControl;
    final Composite rowComposite;
    final Control[] rowControls;
    final String propertyName;
    /** Имя EMF-признака (CommandBarLocation), не отображаемая подпись. */
    final String modelPropertyName;
    /** AEF view (LabelViewModel view) для LWT origin/hit-test; может быть null. */
    final Object lwtView;
    /** Display Y метки строки при последнем клике (для полосы на section-body). */
    int hitDisplayY = -1;

    PropertySheetPaletteRow(Control nameControl, Composite rowComposite, Control[] rowControls, String propertyName)
    {
        this(nameControl, rowComposite, rowControls, propertyName, null, null);
    }

    PropertySheetPaletteRow(Control nameControl, Composite rowComposite, Control[] rowControls,
            String propertyName, Object lwtView)
    {
        this(nameControl, rowComposite, rowControls, propertyName, lwtView, null);
    }

    PropertySheetPaletteRow(Control nameControl, Composite rowComposite, Control[] rowControls,
            String propertyName, Object lwtView, String modelPropertyName)
    {
        this.nameControl = nameControl;
        this.rowComposite = rowComposite;
        this.rowControls = rowControls;
        this.propertyName = propertyName != null ? propertyName : ""; //$NON-NLS-1$
        this.lwtView = lwtView;
        this.modelPropertyName = modelPropertyName != null && !modelPropertyName.isEmpty()
                ? modelPropertyName : null;
    }

    boolean isAlive()
    {
        return nameControl != null && !nameControl.isDisposed();
    }

    void setHitDisplayY(int displayY)
    {
        hitDisplayY = displayY;
    }

    /** Текст для копирования в буфер: EMF-имя признака, иначе null. */
    String copyPropertyName()
    {
        return modelPropertyName;
    }

    /** Клик попадает в горизонтальную полосу строки (имя или поле значения). */
    boolean hitTest(Point display, Object page)
    {
        if (!isAlive() || display == null)
            return false;
        Control host = PropertySheetRowSelectionFeature.lwtPaintHostAtDisplayY(this, page, display.y);
        if (host == null || host.isDisposed())
            return false;

        if (lwtView != null)
        {
            PropertySheetControlInterop.refreshLwtRowGeometry(host, lwtView, propertyName);
            Rectangle band = PropertySheetControlInterop.selectionBandFromLightDisplay(lwtView, host);
            if (band == null)
                band = PropertySheetControlInterop.liveLwtRowBand(host, lwtView, propertyName);
            if (band != null)
            {
                Point topLeft = host.toDisplay(band.x, band.y);
                int bandTop = topLeft.y - 4;
                int bandBottom = bandTop + Math.max(12, band.height + 8);
                return display.y >= bandTop && display.y < bandBottom;
            }
            return false;
        }

        if (PropertySheetControlInterop.isLwtPaintHost(host))
        {
            Point local = host.toControl(display);
            if (local.x < 0 || local.y < 0 || local.x >= host.getSize().x || local.y >= host.getSize().y)
                return false;
            Rectangle band = rowBandOnHost(host);
            if (band == null)
                return false;
            int bandTop = Math.max(0, band.y - 4);
            int bandBottom = bandTop + Math.max(12, band.height + 8);
            return local.y >= bandTop && local.y < bandBottom;
        }

        Rectangle displayBand = rowBandDisplay();
        return displayBand != null && displayBand.contains(display);
    }

    /** Расстояние от Y клика до центра полосы (для выбора ближайшей строки). */
    int hitTestDistance(Point display, Object page)
    {
        if (display == null)
            return Integer.MAX_VALUE;
        Control host = PropertySheetRowSelectionFeature.lwtPaintHostAtDisplayY(this, page, display.y);
        if (host == null || host.isDisposed())
            return Integer.MAX_VALUE;
        if (lwtView != null)
        {
            PropertySheetControlInterop.refreshLwtRowGeometry(host, lwtView, propertyName);
            Rectangle band = PropertySheetControlInterop.selectionBandFromLightDisplay(lwtView, host);
            if (band == null)
                band = PropertySheetControlInterop.liveLwtRowBand(host, lwtView, propertyName);
            if (band != null)
            {
                Point topLeft = host.toDisplay(band.x, band.y);
                return Math.abs(display.y - (topLeft.y + Math.max(1, band.height / 2)));
            }
            return Integer.MAX_VALUE;
        }
        if (PropertySheetControlInterop.isLwtPaintHost(host))
        {
            Point local = host.toControl(display);
            Rectangle band = rowBandOnHost(host);
            int centerY = Math.max(0, band.y - 4) + Math.max(6, band.height + 8) / 2;
            return Math.abs(local.y - centerY);
        }
        Rectangle displayBand = rowBandDisplay();
        if (displayBand == null)
            return Integer.MAX_VALUE;
        return Math.abs(display.y - (displayBand.y + displayBand.height / 2));
    }

    static boolean touchesControl(PropertySheetPaletteRow row, Control control)
    {
        if (row == null || control == null || control.isDisposed())
            return false;
        if (containsControl(row.nameControl, control))
            return true;
        if (containsControl(row.rowComposite, control))
            return true;
        if (row.rowControls != null)
        {
            for (Control c : row.rowControls)
            {
                if (containsControl(c, control))
                    return true;
            }
        }
        return false;
    }

    private Rectangle rowBandOnHost(Control host)
    {
        Rectangle band = PropertySheetControlInterop.liveLwtRowBand(host, lwtView, propertyName);
        if (band != null)
            return band;
        band = PropertySheetControlInterop.lwtRowBand(host, propertyName);
        if (band != null)
            return band;
        Point origin = PropertySheetControlInterop.lwtHighlightOrigin(host, propertyName);
        int bandH = PropertySheetControlInterop.lwtRowBandHeight(host, propertyName);
        return new Rectangle(0, Math.max(0, origin.y - 2), host.getSize().x, Math.max(4, bandH));
    }

    private Rectangle rowBandDisplay()
    {
        Composite row = rowComposite != null && !rowComposite.isDisposed() ? rowComposite : null;
        if (row == null && nameControl != null && !nameControl.isDisposed()
                && nameControl.getParent() instanceof Composite)
            row = (Composite) nameControl.getParent();
        if (row == null || row.isDisposed())
            return null;
        Rectangle bounds = row.getBounds();
        Point origin = row.toDisplay(bounds.x, bounds.y);
        return new Rectangle(origin.x, origin.y, bounds.width, bounds.height);
    }

    private static boolean containsControl(Control ancestor, Control control)
    {
        if (ancestor == null || ancestor.isDisposed() || control == null || control.isDisposed())
            return false;
        if (ancestor == control)
            return true;
        if (!(ancestor instanceof Composite))
            return false;
        for (Composite parent = control.getParent(); parent != null && !parent.isDisposed();
                parent = parent.getParent())
        {
            if (parent == ancestor)
                return true;
        }
        return false;
    }
}
