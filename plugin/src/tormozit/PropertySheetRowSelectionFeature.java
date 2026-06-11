package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/**
 * Выделение текущей строки по клику — через PaintListener (SWT и LWT).
 * Вместо {@code setBackground}, рисуем полупрозрачный фон поверх стандартной отрисовки.
 */
final class PropertySheetRowSelectionFeature implements PropertySheetUiFeature
{
    private static final String LOG_TAG = "propertySheet"; //$NON-NLS-1$

    private static final String CLICK_HOOK_KEY = "tormozit.ps.rowSelectClick"; //$NON-NLS-1$
    private static final String PAINT_HOOK_KEY = "tormozit.ps.rowSelectPaint"; //$NON-NLS-1$
    /** Имя выделенного свойства для данного paint-хоста (несколько строк на одном LWT-host). */
    private static final String ACTIVE_PROP_KEY = "tormozit.ps.rowSelectProp"; //$NON-NLS-1$
    /** Сам объект PropertySheetPaletteRow — чтобы PaintListener знал строку для LWT-оригин. */
    private static final String ACTIVE_ROW_KEY = "tormozit.ps.rowSelectRow"; //$NON-NLS-1$

    private PropertySheetPaletteRow lastSelected;

    private static void log(String msg)
    {
        Global.log(LOG_TAG, "[rowSelect] " + msg); //$NON-NLS-1$
    }

    @Override
    public void refresh(PropertySheetUiContext ctx)
    {
        if (ctx == null)
            return;

        // Восстанавливаем сессионное выделение из ctx
        PropertySheetPaletteRow selected = ctx.selectedRow();
        if (selected != null && !selected.isAlive())
            selected = null;

        // При обновлении списка (фильтр/скролл) — снимаем прежню подсветку со старого хоста
        if (lastSelected != null && lastSelected != selected && lastSelected.isAlive())
        {
            log("refresh deactivate prev=" + PropertySheetDebug.quote(lastSelected.propertyName)); //$NON-NLS-1$
            deactivate(lastSelected);
        }

        log("refresh start rows=" + ctx.rows.size() //$NON-NLS-1$
                + " selected=" + PropertySheetDebug.quote(selected != null ? selected.propertyName : null)); //$NON-NLS-1$

        // Устанавливаем paint-хуки + клик-хуки на всех строках
        for (PropertySheetPaletteRow row : ctx.rows)
        {
            if (!row.isAlive())
            {
                log("refresh skip dead " + PropertySheetDebug.quote(row.propertyName)); //$NON-NLS-1$
                continue;
            }
            Control host = paintHost(row);
            if (host == null || host.isDisposed())
            {
                log("refresh skip noHost " + PropertySheetDebug.quote(row.propertyName) //$NON-NLS-1$
                        + " target=" + describeInteractionTarget(row)); //$NON-NLS-1$
                continue;
            }
            ensurePaintHook(host);
            boolean isLwtBridge = row.lwtView != null && PropertySheetControlInterop.isLwtPaintHost(host);
            log("refresh hook " + (isLwtBridge ? "bridge" : "direct") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " " + PropertySheetDebug.quote(row.propertyName) //$NON-NLS-1$
                    + " host=" + PropertySheetDebug.controlBrief(host) //$NON-NLS-1$
                    + " clickHook=" + (isLwtBridge ? "mouseBridge" : "direct")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (!isLwtBridge)
                ensureClickHook(host, row, ctx);
        }

        // Теперь активируем нужную строку
        if (selected != null)
            activate(selected);

        lastSelected = selected;
        PropertySheetDebug.feature("rowSelect rows=" + ctx.rows.size() //$NON-NLS-1$
                + " selected=" + PropertySheetDebug.quote(selected != null ? selected.propertyName : null)); //$NON-NLS-1$
        log("refresh done selected=" + PropertySheetDebug.quote(selected != null ? selected.propertyName : null)); //$NON-NLS-1$
    }

    static Control interactionTarget(PropertySheetPaletteRow row)
    {
        if (row == null)
            return null;
        if (row.lwtView != null && row.nameControl != null && !row.nameControl.isDisposed()
                && PropertySheetControlInterop.isLwtPaintHost(row.nameControl))
            return row.nameControl;
        if (row.rowComposite != null && !row.rowComposite.isDisposed())
            return row.rowComposite;
        if (row.nameControl != null && !row.nameControl.isDisposed())
            return row.nameControl;
        return null;
    }

    // -----------------------------------------------------------------------
    // paint host: для LWT-хостов это rowComposite (фиксируем имя);  для SWT — rowComposite или nameControl
    // -----------------------------------------------------------------------

    private static Control paintHost(PropertySheetPaletteRow row)
    {
        return interactionTarget(row);
    }

    void selectRow(PropertySheetUiContext ctx, PropertySheetPaletteRow row)
    {
        if (ctx == null || row == null || !row.isAlive())
        {
            log("selectRow skip ctx=" + (ctx != null) //$NON-NLS-1$
                    + " row=" + PropertySheetDebug.quote(row != null ? row.propertyName : null) //$NON-NLS-1$
                    + " alive=" + (row != null && row.isAlive())); //$NON-NLS-1$
            return;
        }
        log("selectRow " + PropertySheetDebug.quote(row.propertyName) //$NON-NLS-1$
                + " prev=" + PropertySheetDebug.quote(lastSelected != null ? lastSelected.propertyName : null) //$NON-NLS-1$
                + " " + describeInteractionTarget(row)); //$NON-NLS-1$
        if (lastSelected != null && lastSelected != row && lastSelected.isAlive())
            deactivate(lastSelected);
        ctx.setSelectedRow(row);
        PropertySheetUiCoordinator.rememberSelection(ctx.page, row);
        activate(row);
        lastSelected = row;
    }

    private void ensureClickHook(Control host, PropertySheetPaletteRow row, PropertySheetUiContext ctx)
    {
        if (Boolean.TRUE.equals(host.getData(CLICK_HOOK_KEY)))
            return;
        host.setData(CLICK_HOOK_KEY, Boolean.TRUE);
        host.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseDown(MouseEvent e)
            {
                if (e.button != 1 || !row.isAlive())
                {
                    log("click skip btn=" + e.button //$NON-NLS-1$
                            + " alive=" + row.isAlive() //$NON-NLS-1$
                            + " " + PropertySheetDebug.quote(row.propertyName)); //$NON-NLS-1$
                    return;
                }
                log("click " + PropertySheetDebug.quote(row.propertyName) //$NON-NLS-1$
                        + " at=(" + e.x + "," + e.y + ") host=" + PropertySheetDebug.controlBrief(host)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                selectRow(ctx, row);
                PropertySheetDebug.feature("rowSelect click " //$NON-NLS-1$
                        + PropertySheetDebug.quote(row.propertyName));
            }
        });
    }

    private static void ensurePaintHook(Control host)
    {
        if (Boolean.TRUE.equals(host.getData(PAINT_HOOK_KEY)))
            return;
        host.setData(PAINT_HOOK_KEY, Boolean.TRUE);
        host.addPaintListener(new PaintListener()
        {
            @Override
            public void paintControl(PaintEvent e)
            {
                String activeName = (String) host.getData(ACTIVE_PROP_KEY);
                if (activeName == null || activeName.isEmpty())
                    return;
                PropertySheetPaletteRow row = (PropertySheetPaletteRow) host.getData(ACTIVE_ROW_KEY);
                if (row == null || !activeName.equals(row.propertyName))
                {
                    log("paint skip host=" + PropertySheetDebug.controlBrief(host) //$NON-NLS-1$
                            + " active=" + PropertySheetDebug.quote(activeName) //$NON-NLS-1$
                            + " row=" + PropertySheetDebug.quote(row != null ? row.propertyName : null)); //$NON-NLS-1$
                    return;
                }
                drawSelectionBand(e.gc, host, row);
            }
        });
    }

    private static void activate(PropertySheetPaletteRow row)
    {
        Control host = paintHost(row);
        if (host == null || host.isDisposed())
        {
            log("activate skip noHost " + PropertySheetDebug.quote(row.propertyName) //$NON-NLS-1$
                    + " " + describeInteractionTarget(row)); //$NON-NLS-1$
            return;
        }
        log("activate " + PropertySheetDebug.quote(row.propertyName) //$NON-NLS-1$
                + " host=" + PropertySheetDebug.controlBrief(host) //$NON-NLS-1$
                + " " + describeBand(host, row)); //$NON-NLS-1$
        host.setData(ACTIVE_PROP_KEY, row.propertyName);
        host.setData(ACTIVE_ROW_KEY, row);
        host.redraw();
        if (host instanceof Composite)
            redrawChildren((Composite) host);
    }

    private static void deactivate(PropertySheetPaletteRow row)
    {
        Control host = paintHost(row);
        if (host == null || host.isDisposed())
        {
            log("deactivate skip noHost " + PropertySheetDebug.quote(row.propertyName)); //$NON-NLS-1$
            return;
        }
        log("deactivate " + PropertySheetDebug.quote(row.propertyName) //$NON-NLS-1$
                + " host=" + PropertySheetDebug.controlBrief(host)); //$NON-NLS-1$
        host.setData(ACTIVE_PROP_KEY, null);
        host.setData(ACTIVE_ROW_KEY, null);
        host.redraw();
        if (host instanceof Composite)
            redrawChildren((Composite) host);
    }

    private static void redrawChildren(Composite composite)
    {
        if (composite == null || composite.isDisposed())
            return;
        for (Control child : composite.getChildren())
        {
            if (child == null || child.isDisposed())
                continue;
            child.redraw();
            if (child instanceof Composite)
                redrawChildren((Composite) child);
        }
    }

    /**
     * Рисуем полупрозрачный фон выделения поверх стандартной отрисовки контрола.
     * Alpha=80 (~31%) — видно но не глушит текст и редактор значения.
     * Для LWT-хостов рисуем только полосу одной строки (не весь контейнер).
     */
    private static void drawSelectionBand(GC gc, Control host, PropertySheetPaletteRow row)
    {
        int x = 0;
        int y = 0;
        int w = host.getSize().x;
        int h = host.getSize().y;

        if (PropertySheetControlInterop.isLwtPaintHost(host))
        {
            String propName = row != null ? row.propertyName : null;
            Rectangle band = PropertySheetControlInterop.lwtRowBand(host, propName);
            if (band != null)
            {
                // Точная геометрия от LightLabel.getBounds — придерживаемся её ±1px.
                y = Math.max(0, band.y - 1);
                h = Math.min(band.height + 2, host.getSize().y - y);
            }
            else
            {
                org.eclipse.swt.graphics.Point origin =
                        PropertySheetControlInterop.lwtHighlightOrigin(host, propName);
                int bandH = PropertySheetControlInterop.lwtRowBandHeight(host, propName);
                // Не позволяем полосе быть выше одной строки (~33px).
                bandH = Math.min(bandH, 36);
                y = Math.max(0, origin.y - 1);
                h = Math.min(Math.max(4, bandH + 2), host.getSize().y - y);
            }
        }

        if (w <= 0 || h <= 0)
        {
            log("draw skip empty rect prop=" + PropertySheetDebug.quote(row != null ? row.propertyName : null) //$NON-NLS-1$
                    + " host=" + PropertySheetDebug.controlBrief(host) //$NON-NLS-1$
                    + " w=" + w + " h=" + h); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        Color selColor = selectionBg(host.getDisplay());
        int alpha = gc.getAlpha();
        gc.setAlpha(80);
        gc.setBackground(selColor);
        gc.fillRectangle(x, y, w, h);
        gc.setAlpha(alpha);
    }

    private static Color selectionBg(Display display)
    {
        if (display == null || display.isDisposed())
            display = Display.getDefault();
        return display.getSystemColor(SWT.COLOR_LIST_SELECTION);
    }

    private static String describeInteractionTarget(PropertySheetPaletteRow row)
    {
        if (row == null)
            return "target=<null>"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder("target="); //$NON-NLS-1$
        if (row.lwtView != null)
            sb.append("lwtView "); //$NON-NLS-1$
        if (row.nameControl != null && !row.nameControl.isDisposed())
            sb.append("name=").append(PropertySheetDebug.controlBrief(row.nameControl)).append(' '); //$NON-NLS-1$
        else
            sb.append("name=<null> "); //$NON-NLS-1$
        if (row.rowComposite != null && !row.rowComposite.isDisposed())
            sb.append("row=").append(PropertySheetDebug.controlBrief(row.rowComposite)); //$NON-NLS-1$
        else
            sb.append("row=<null>"); //$NON-NLS-1$
        Control chosen = interactionTarget(row);
        sb.append(" → ").append(PropertySheetDebug.controlBrief(chosen)); //$NON-NLS-1$
        return sb.toString();
    }

    private static String describeBand(Control host, PropertySheetPaletteRow row)
    {
        if (host == null || host.isDisposed() || row == null)
            return "band=<null>"; //$NON-NLS-1$
        if (!PropertySheetControlInterop.isLwtPaintHost(host))
            return "band=fullHost size=" + host.getSize().x + "x" + host.getSize().y; //$NON-NLS-1$ //$NON-NLS-2$
        Rectangle band = PropertySheetControlInterop.lwtRowBand(host, row.propertyName);
        if (band != null)
            return "band=lwtBounds y=" + band.y + " h=" + band.height //$NON-NLS-1$ //$NON-NLS-2$
                    + " hostH=" + host.getSize().y; //$NON-NLS-1$
        org.eclipse.swt.graphics.Point origin =
                PropertySheetControlInterop.lwtHighlightOrigin(host, row.propertyName);
        int bandH = PropertySheetControlInterop.lwtRowBandHeight(host, row.propertyName);
        return "band=lwtFallback origin=(" + origin.x + "," + origin.y + ") bandH=" + bandH //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " hostH=" + host.getSize().y; //$NON-NLS-1$
    }
}

