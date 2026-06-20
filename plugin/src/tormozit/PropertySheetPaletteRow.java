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
    /** Display-band области свойства [top, bottom). */
    int selectionBandTopDisplay = -1;
    int selectionBandBottomDisplay = -1;
    /** Canvas-band для отрисовки на PaletteCanvasSpace.content (фаза scan-only). */
    int selectionBandTopCanvas = -1;
    int selectionBandBottomCanvas = -1;
    boolean selectionBandIsCanvas = false;

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

    void setSelectionDisplayBand(int topDisplay, int bottomDisplay)
    {
        selectionBandTopDisplay = topDisplay;
        selectionBandBottomDisplay = bottomDisplay;
        selectionBandIsCanvas = false;
    }

    void setSelectionCanvasBand(int topCanvas, int bottomCanvas)
    {
        selectionBandTopCanvas = topCanvas;
        selectionBandBottomCanvas = bottomCanvas;
        selectionBandIsCanvas = topCanvas >= 0 && bottomCanvas > topCanvas;
        selectionBandTopDisplay = -1;
        selectionBandBottomDisplay = -1;
    }

    /** Текст для копирования в буфер: EMF-имя признака, иначе null. */
    String copyPropertyName()
    {
        return modelPropertyName;
    }

    /** Клик попадает в область свойства (bounds LWT-строки, без canvas-scan). */
    boolean hitTest(Point display, Object page)
    {
        if (!isAlive() || display == null)
            return false;
        if (selectionBandIsCanvas && page != null)
        {
            PropertySheetUiContext.PaletteCanvasSpace space =
                    PropertySheetUiContext.PaletteCanvasSpace.forPage(page);
            if (space != null)
            {
                int canvasY = space.displayToCanvas(display).y;
                return canvasY >= selectionBandTopCanvas && canvasY < selectionBandBottomCanvas;
            }
        }
        if (selectionBandTopDisplay >= 0 && selectionBandBottomDisplay > selectionBandTopDisplay)
            return display.y >= selectionBandTopDisplay && display.y < selectionBandBottomDisplay;
        if (lwtView != null)
        {
            Composite leaf = PropertySheetControlInterop.leafFieldRowHostForView(lwtView);
            if (leaf != null && !leaf.isDisposed())
            {
                int top = leaf.toDisplay(0, 0).y;
                int bottom = top + leaf.getSize().y;
                return display.y >= top && display.y < bottom;
            }
        }
        if (rowComposite != null && !rowComposite.isDisposed() && rowComposite.getSize().y <= 120)
        {
            int top = rowComposite.toDisplay(0, 0).y;
            int bottom = top + rowComposite.getSize().y;
            return display.y >= top && display.y < bottom;
        }
        return false;
    }

    /** Расстояние от Y клика до центра полосы (для выбора ближайшей строки). */
    int hitTestDistance(Point display, Object page)
    {
        if (display == null)
            return Integer.MAX_VALUE;
        if (selectionBandIsCanvas && page != null)
        {
            PropertySheetUiContext.PaletteCanvasSpace space =
                    PropertySheetUiContext.PaletteCanvasSpace.forPage(page);
            if (space != null)
            {
                int canvasY = space.displayToCanvas(display).y;
                int center = selectionBandTopCanvas
                        + (selectionBandBottomCanvas - selectionBandTopCanvas) / 2;
                return Math.abs(canvasY - center);
            }
        }
        if (selectionBandTopDisplay >= 0 && selectionBandBottomDisplay > selectionBandTopDisplay)
        {
            int center = selectionBandTopDisplay
                    + (selectionBandBottomDisplay - selectionBandTopDisplay) / 2;
            return Math.abs(display.y - center);
        }
        Composite root = page != null ? PropertySheetUiContext.findPaletteRoot(page) : null;
        if (root != null && !root.isDisposed() && lwtView != null)
        {
            Composite leaf = PropertySheetControlInterop.leafFieldRowHostForView(lwtView);
            if (leaf != null && !leaf.isDisposed())
            {
                int center = leaf.toDisplay(0, 0).y + leaf.getSize().y / 2;
                return Math.abs(display.y - center);
            }
        }
        return Integer.MAX_VALUE;
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
