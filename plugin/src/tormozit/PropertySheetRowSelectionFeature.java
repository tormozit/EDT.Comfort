package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Выделение текущей строки по клику — через PaintListener (SWT и LWT).
 * Рамка поверх стандартной отрисовки (без заливки фона).
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
    private static final String SCAN_OVERLAY_KEY = "tormozit.ps.scanOverlay"; //$NON-NLS-1$
    private static final String SCAN_OVERLAY_PAGE_KEY = "tormozit.ps.scanOverlayPage"; //$NON-NLS-1$

    private PropertySheetPaletteRow lastSelected;
    /** Последний paint-host с ACTIVE_* — снимаем при смене секции. */
    private static Control lastActivePaintHost;

    private static void clearActiveOnHost(Control host)
    {
        if (host == null || host.isDisposed())
            return;
        if (host.getData(ACTIVE_PROP_KEY) == null)
            return;
        host.setData(ACTIVE_PROP_KEY, null);
        host.setData(ACTIVE_ROW_KEY, null);
        host.redraw();
    }

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
            deactivate(lastSelected, ctx.page);
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
            Control host = paintHost(row, ctx.page);
            if (host == null || host.isDisposed())
            {
                log("refresh skip noHost " + PropertySheetDebug.quote(row.propertyName) //$NON-NLS-1$
                        + " target=" + describeInteractionTarget(row)); //$NON-NLS-1$
                continue;
            }
            ensurePaintHook(host);
            log("refresh hook bridge " + PropertySheetDebug.quote(row.propertyName) //$NON-NLS-1$
                    + " host=" + PropertySheetDebug.controlBrief(host) //$NON-NLS-1$
                    + " clickHook=mouseBridge"); //$NON-NLS-1$
        }

        // Теперь активируем нужную строку
        if (selected != null)
            activate(selected, ctx);

        lastSelected = selected;
        PropertySheetDebug.feature("rowSelect rows=" + ctx.rows.size() //$NON-NLS-1$
                + " selected=" + PropertySheetDebug.quote(selected != null ? selected.propertyName : null)); //$NON-NLS-1$
        log("refresh done selected=" + PropertySheetDebug.quote(selected != null ? selected.propertyName : null)); //$NON-NLS-1$
    }

    static Control interactionTarget(PropertySheetPaletteRow row)
    {
        return interactionTarget(row, PropertySheetUiCoordinator.pageForRow(row));
    }

    static Control interactionTarget(PropertySheetPaletteRow row, Object page)
    {
        return lwtPaintHostAtDisplayY(row, page, 0);
    }

    /** Paint-host LWT: секция под displayY клика, не host по метке view. */
    static Control lwtPaintHostAtDisplayY(PropertySheetPaletteRow row, Object page, int displayY)
    {
        if (row == null)
            return null;
        if (row.lwtView != null)
        {
            if (page != null)
            {
                Composite root = PropertySheetUiContext.findPaletteRoot(page);
                int probeY = displayY > 0 ? displayY : row.hitDisplayY;
                if (probeY > 0 && root != null && !root.isDisposed())
                {
                    Composite atClick = PropertySheetControlInterop.findSectionBodyAtDisplayY(root, probeY);
                    if (atClick != null && !atClick.isDisposed())
                    {
                        Composite leafInSection = PropertySheetControlInterop.leafFieldRowAtDisplayY(atClick,
                                probeY);
                        if (leafInSection != null && !leafInSection.isDisposed())
                            return leafInSection;
                        return atClick;
                    }
                }
            }
            Composite leaf = PropertySheetControlInterop.leafFieldRowHostForView(row.lwtView);
            int probeY = displayY > 0 ? displayY : row.hitDisplayY;
            if (probeY <= 0 && page != null)
            {
                Composite root = PropertySheetUiContext.findPaletteRoot(page);
                Composite byView = root != null && !root.isDisposed()
                        ? PropertySheetControlInterop.findSectionBodyForView(row.lwtView, root) : null;
                if (byView != null && !byView.isDisposed())
                    return byView;
            }
            if (leaf != null && !leaf.isDisposed() && leaf.getSize().y <= 80)
            {
                if (probeY <= 0)
                    return leaf;
                int top = leaf.toDisplay(0, 0).y;
                int bottom = top + leaf.getSize().y;
                if (probeY >= top && probeY < bottom)
                    return leaf;
            }
            if (page != null)
            {
                Composite root = PropertySheetUiContext.findPaletteRoot(page);
                Composite host = PropertySheetControlInterop.findPaintHostForView(row.lwtView, root);
                if (host != null && !host.isDisposed())
                    return host;
            }
        }
        if (row.rowComposite != null && !row.rowComposite.isDisposed())
        {
            Composite rc = row.rowComposite;
            if (PropertySheetControlInterop.isLwtFieldRowComposite(rc))
                return rc;
            if (rc.getSize().y <= 80)
                return rc;
        }
        if (row.nameControl instanceof Composite)
        {
            Composite nc = (Composite) row.nameControl;
            if (nc.getSize().y >= 20 && nc.getSize().y <= 80)
                return nc;
        }
        return null;
    }

    private static Control paintHost(PropertySheetPaletteRow row, Object page)
    {
        if (row != null && row.selectionBandIsCanvas)
        {
            Canvas overlay = ensureScanOverlay(page);
            if (overlay != null && !overlay.isDisposed())
                return overlay;
        }
        if (row != null && row.lwtView != null)
        {
            Composite leaf = PropertySheetControlInterop.leafFieldRowHostForView(row.lwtView);
            if (leaf != null && !leaf.isDisposed())
                return leaf;
        }
        if (row != null && row.selectionBandTopDisplay >= 0
                && row.selectionBandBottomDisplay > row.selectionBandTopDisplay
                && row.rowComposite != null && !row.rowComposite.isDisposed()
                && row.rowComposite.getSize().y <= 120)
            return row.rowComposite;
        if (row != null && row.hitDisplayY > 0)
            return lwtPaintHostAtDisplayY(row, page, row.hitDisplayY);
        return lwtPaintHostAtDisplayY(row, page, 0);
    }

    /** Прозрачный overlay поверх viewport ScrolledComposite — LWT content не шлёт Paint. */
    private static Canvas ensureScanOverlay(Object page)
    {
        ScrolledComposite scroll = PropertySheetUiContext.findPaletteScrolledComposite(page);
        if (scroll == null || scroll.isDisposed())
        {
            // #region agent log
            PropertySheetControlInterop.agentHitLog("H25", "PropertySheetRowSelectionFeature.ensureScanOverlay", //$NON-NLS-1$ //$NON-NLS-2$
                    "noScroll", java.util.Map.of("pageNull", page == null)); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return null;
        }
        Canvas overlay = (Canvas) scroll.getData(SCAN_OVERLAY_KEY);
        if (overlay == null || overlay.isDisposed())
        {
            overlay = new Canvas(scroll, SWT.NO_MERGE_PAINTS | SWT.TRANSPARENT);
            overlay.setEnabled(false);
            scroll.setData(SCAN_OVERLAY_KEY, overlay);
            final Canvas overlayRef = overlay;
            overlay.addPaintListener(new PaintListener()
            {
                @Override
                public void paintControl(PaintEvent e)
                {
                    String activeName = (String) overlayRef.getData(ACTIVE_PROP_KEY);
                    if (activeName == null || activeName.isEmpty())
                        return;
                    PropertySheetPaletteRow row = (PropertySheetPaletteRow) overlayRef.getData(ACTIVE_ROW_KEY);
                    if (row == null || !activeName.equals(row.propertyName))
                        return;
                    Object overlayPage = overlayRef.getData(SCAN_OVERLAY_PAGE_KEY);
                    drawSelectionBand(e.gc, overlayRef, row, overlayPage);
                }
            });
            ControlAdapter layoutOnResize = new ControlAdapter()
            {
                @Override
                public void controlResized(ControlEvent e)
                {
                    layoutScanOverlay(scroll, overlayRef);
                    if (!overlayRef.isDisposed())
                        overlayRef.redraw();
                }
            };
            scroll.addControlListener(layoutOnResize);
            Listener overlayOnScroll = e -> {
                if (!overlayRef.isDisposed())
                {
                    layoutScanOverlay(scroll, overlayRef);
                    overlayRef.redraw();
                }
            };
            ScrollBar vertical = scroll.getVerticalBar();
            if (vertical != null)
                vertical.addListener(SWT.Selection, overlayOnScroll);
            ScrollBar horizontal = scroll.getHorizontalBar();
            if (horizontal != null)
                horizontal.addListener(SWT.Selection, overlayOnScroll);
        }
        overlay.setData(SCAN_OVERLAY_PAGE_KEY, page);
        return overlay;
    }

    private static void layoutScanOverlayForRow(ScrolledComposite scroll, Canvas overlay, Object page,
            PropertySheetPaletteRow row)
    {
        if (scroll == null || scroll.isDisposed() || overlay == null || overlay.isDisposed())
            return;
        Rectangle band = viewportCanvasBand(page, row);
        if (band == null)
        {
            overlay.setVisible(false);
            return;
        }
        overlay.setBounds(band.x, band.y, band.width, band.height);
        overlay.moveAbove(null);
        overlay.setVisible(true);
    }

    private static void layoutScanOverlay(ScrolledComposite scroll, Canvas overlay)
    {
        if (scroll == null || scroll.isDisposed() || overlay == null || overlay.isDisposed())
            return;
        PropertySheetPaletteRow row = (PropertySheetPaletteRow) overlay.getData(ACTIVE_ROW_KEY);
        Object page = overlay.getData(SCAN_OVERLAY_PAGE_KEY);
        if (row != null && row.selectionBandIsCanvas)
            layoutScanOverlayForRow(scroll, overlay, page, row);
        else
            overlay.setVisible(false);
    }

    /** Canvas-band → координаты viewport ScrolledComposite. */
    private static Rectangle viewportCanvasBand(Object page, PropertySheetPaletteRow row)
    {
        if (page == null || row == null || !row.selectionBandIsCanvas
                || row.selectionBandBottomCanvas <= row.selectionBandTopCanvas)
            return null;
        PropertySheetUiContext.PaletteCanvasSpace space =
                PropertySheetUiContext.PaletteCanvasSpace.forPage(page);
        ScrolledComposite scroll = PropertySheetUiContext.findPaletteScrolledComposite(page);
        if (space == null || scroll == null || scroll.isDisposed()
                || space.content == null || space.content.isDisposed())
            return null;
        Point topD = space.content.toDisplay(0, row.selectionBandTopCanvas);
        Point botD = space.content.toDisplay(0, row.selectionBandBottomCanvas);
        Point localTop = scroll.toControl(topD);
        Point localBot = scroll.toControl(botD);
        int y = localTop.y;
        int h = localBot.y - localTop.y;
        int w = scroll.getClientArea().width;
        if (h <= 0 || w <= 0)
            return null;
        Rectangle client = scroll.getClientArea();
        if (y + h < 0 || y > client.height)
            return null;
        if (y < 0)
        {
            h += y;
            y = 0;
        }
        if (y + h > client.height)
            h = client.height - y;
        if (h <= 0)
            return null;
        return new Rectangle(0, y, w, h);
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
            deactivate(lastSelected, ctx.page);
        ctx.setSelectedRow(row);
        PropertySheetUiCoordinator.rememberSelection(ctx.page, row);
        activate(row, ctx);
        lastSelected = row;
    }

    private void installSwtClickHooks(PropertySheetPaletteRow row, PropertySheetUiContext ctx)
    {
        Control host = interactionTarget(row, ctx.page);
        if (host != null && !host.isDisposed())
            ensureClickHook(host, row, ctx);
        if (row.rowComposite != null && !row.rowComposite.isDisposed() && row.rowComposite != host)
            ensureClickHook(row.rowComposite, row, ctx);
        if (row.rowControls != null)
        {
            for (Control extra : row.rowControls)
            {
                if (extra == null || extra.isDisposed() || extra == host || extra == row.rowComposite)
                    continue;
                ensureClickHook(extra, row, ctx);
            }
        }
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
                drawSelectionBand(e.gc, host, row, null);
            }
        });
    }

    private static void activate(PropertySheetPaletteRow row, PropertySheetUiContext ctx)
    {
        Object page = ctx != null ? ctx.page : null;
        Control host = paintHost(row, page);
        if (host == null || host.isDisposed())
        {
            log("activate skip noHost " + PropertySheetDebug.quote(row.propertyName) //$NON-NLS-1$
                    + " " + describeInteractionTarget(row, page)); //$NON-NLS-1$
            // #region agent log
            PropertySheetControlInterop.agentHitLog("H9", "PropertySheetRowSelectionFeature.activate", "noHost", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    java.util.Map.of("prop", row.propertyName)); //$NON-NLS-1$
            // #endregion
            return;
        }
        if (row.lwtView != null && !row.selectionBandIsCanvas)
            PropertySheetControlInterop.refreshLwtRowGeometry(host, row.lwtView, row.propertyName);
        if (!row.selectionBandIsCanvas)
            ensurePaintHook(host);
        log("activate " + PropertySheetDebug.quote(row.propertyName) //$NON-NLS-1$
                + " host=" + PropertySheetDebug.controlBrief(host) //$NON-NLS-1$
                + " " + describeBand(host, row, page)); //$NON-NLS-1$
        if (lastActivePaintHost != null && lastActivePaintHost != host && !lastActivePaintHost.isDisposed())
            clearActiveOnHost(lastActivePaintHost);
        host.setData(ACTIVE_PROP_KEY, row.propertyName);
        host.setData(ACTIVE_ROW_KEY, row);
        if (host instanceof Canvas)
            host.setData(SCAN_OVERLAY_PAGE_KEY, page);
        lastActivePaintHost = host;
        if (row.selectionBandIsCanvas && host instanceof Canvas)
        {
            ScrolledComposite scroll = PropertySheetUiContext.findPaletteScrolledComposite(page);
            layoutScanOverlayForRow(scroll, (Canvas) host, page, row);
        }
        Rectangle band = resolveRowBand(host, row, page);
        // #region agent log
        java.util.Map<String, Object> h9 = new java.util.LinkedHashMap<>();
        h9.put("prop", row.propertyName); //$NON-NLS-1$
        h9.put("host", host.getClass().getSimpleName()); //$NON-NLS-1$
        h9.put("rectW", band != null ? band.width : -1); //$NON-NLS-1$
        h9.put("rectH", band != null ? band.height : -1); //$NON-NLS-1$
        if (row.selectionBandIsCanvas)
        {
            h9.put("topCanvas", row.selectionBandTopCanvas); //$NON-NLS-1$
            h9.put("bottomCanvas", row.selectionBandBottomCanvas); //$NON-NLS-1$
            h9.put("overlay", host instanceof Canvas); //$NON-NLS-1$
        }
        else
        {
            h9.put("bandTop", row.selectionBandTopDisplay); //$NON-NLS-1$
            h9.put("bandBottom", row.selectionBandBottomDisplay); //$NON-NLS-1$
        }
        PropertySheetControlInterop.agentHitLog("H9", "PropertySheetRowSelectionFeature.activate", "draw", h9); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // #endregion
        host.redraw();
    }

    private static void deactivate(PropertySheetPaletteRow row, Object page)
    {
        Control host = paintHost(row, page);
        if (host == null || host.isDisposed())
        {
            log("deactivate skip noHost " + PropertySheetDebug.quote(row.propertyName)); //$NON-NLS-1$
            if (lastActivePaintHost != null && !lastActivePaintHost.isDisposed())
                clearActiveOnHost(lastActivePaintHost);
            lastActivePaintHost = null;
            return;
        }
        log("deactivate " + PropertySheetDebug.quote(row.propertyName) //$NON-NLS-1$
                + " host=" + PropertySheetDebug.controlBrief(host)); //$NON-NLS-1$
        clearActiveOnHost(host);
        if (host instanceof Canvas)
            host.setVisible(false);
        if (host == lastActivePaintHost)
            lastActivePaintHost = null;
    }

    /**
     * Рамка выделения поверх стандартной отрисовки контрола (без заливки фона).
     */
    private static void drawSelectionBand(GC gc, Control host, PropertySheetPaletteRow row, Object page)
    {
        if (row != null && !row.selectionBandIsCanvas && row.lwtView != null)
            PropertySheetControlInterop.refreshLwtRowGeometry(host, row.lwtView, row.propertyName);
        if (page == null && host != null)
            page = host.getData(SCAN_OVERLAY_PAGE_KEY);
        Rectangle band = resolveRowBand(host, row, page);
        if (band == null)
            return;
        int x = band.x;
        int y = band.y;
        int w = band.width;
        int h = band.height;

        if (w <= 0 || h <= 0)
            return;
        Color selColor = selectionBg(host.getDisplay());
        gc.setForeground(selColor);
        gc.setLineWidth(1);
        gc.drawRectangle(x, y, Math.max(0, w - 1), Math.max(0, h - 1));
        // #region agent log
        java.util.Map<String, Object> h24 = new java.util.LinkedHashMap<>();
        h24.put("prop", row.propertyName); //$NON-NLS-1$
        h24.put("host", host.getClass().getSimpleName()); //$NON-NLS-1$
        h24.put("x", x); //$NON-NLS-1$
        h24.put("y", y); //$NON-NLS-1$
        h24.put("w", w); //$NON-NLS-1$
        h24.put("h", h); //$NON-NLS-1$
        h24.put("hostH", host.getSize().y); //$NON-NLS-1$
        if (row.selectionBandIsCanvas)
        {
            h24.put("topCanvas", row.selectionBandTopCanvas); //$NON-NLS-1$
            h24.put("bottomCanvas", row.selectionBandBottomCanvas); //$NON-NLS-1$
        }
        PropertySheetControlInterop.agentHitLog("H24", "PropertySheetRowSelectionFeature.drawSelectionBand", //$NON-NLS-1$ //$NON-NLS-2$
                "scanFrame", h24); //$NON-NLS-1$
        PropertySheetControlInterop.agentHitLog("H9d", "PropertySheetRowSelectionFeature.drawSelectionBand", //$NON-NLS-1$ //$NON-NLS-2$
                "paint", java.util.Map.of("prop", row.propertyName, "host", host.getClass().getSimpleName(), //$NON-NLS-1$ //$NON-NLS-2$
                        "x", x, "y", y, "w", w, "h", h)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // #endregion
    }

    private static Rectangle resolveRowBand(Control host, PropertySheetPaletteRow row, Object page)
    {
        if (host == null || host.isDisposed() || row == null)
            return null;
        if (row.selectionBandIsCanvas)
        {
            Rectangle viewport = viewportCanvasBand(page, row);
            if (viewport != null && host instanceof Canvas)
                return new Rectangle(0, 0, host.getSize().x, host.getSize().y);
            if (viewport != null)
                return viewport;
        }
        int hostH = host.getSize().y;
        if (hostH <= 0)
            return null;

        if (row.selectionBandTopDisplay >= 0
                && row.selectionBandBottomDisplay > row.selectionBandTopDisplay)
        {
            if (host instanceof Composite
                    && (PropertySheetControlInterop.isLwtFieldRowComposite((Composite) host)
                            || host.getSize().y <= 120))
            {
                return new Rectangle(0, 0, host.getSize().x, hostH);
            }
            int displayX = host.toDisplay(0, 0).x;
            Point localTop = host.toControl(displayX, row.selectionBandTopDisplay);
            int h = row.selectionBandBottomDisplay - row.selectionBandTopDisplay;
            int rowY = Math.max(0, localTop.y);
            int rowH = Math.min(hostH - rowY, h);
            if (rowH > 0)
                return new Rectangle(0, rowY, host.getSize().x, rowH);
        }

        if (row.lwtView != null)
        {
            Rectangle fromLight = PropertySheetControlInterop.selectionBandFromLightDisplay(row.lwtView, host);
            if (fromLight != null)
                return fromLight;
        }

        if (host instanceof Composite && hostH <= 44 && row.lwtView != null
                && PropertySheetControlInterop.isLwtFieldRowComposite((Composite) host))
            return new Rectangle(0, 0, host.getSize().x, hostH);

        if (!PropertySheetControlInterop.isLwtPaintHost(host))
        {
            Rectangle swtBand = bandFromRowControls(host, row);
            if (swtBand != null)
                return swtBand;
        }

        Rectangle live = PropertySheetControlInterop.liveLwtRowBand(host, row.lwtView, row.propertyName);
        if (live == null)
        {
            Rectangle stored = PropertySheetControlInterop.lwtRowBand(host, row.propertyName);
            if (stored != null)
                live = stored;
        }
        int rowY;
        int rowH;
        if (live != null)
        {
            rowY = Math.max(0, live.y - 2);
            rowH = Math.min(36, Math.max(22, live.height + 4));
        }
        else if (PropertySheetControlInterop.isLwtPaintHost(host) && row.lwtView != null)
        {
            org.eclipse.swt.graphics.Point origin =
                    PropertySheetControlInterop.lwtHighlightOrigin(host, row.propertyName);
            rowY = Math.max(0, origin.y - 2);
            rowH = Math.min(36, Math.max(22, PropertySheetControlInterop.lwtRowBandHeight(host, row.propertyName) + 2));
        }
        else if (row.nameControl != null && !row.nameControl.isDisposed()
                && row.nameControl != host && row.nameControl.getParent() == host)
        {
            Rectangle nameB = row.nameControl.getBounds();
            return new Rectangle(Math.max(0, nameB.x), Math.max(0, nameB.y - 1),
                    Math.max(1, nameB.width), Math.min(hostH, Math.max(4, nameB.height + 2)));
        }
        else
            return null;

        if (rowY + rowH > hostH)
            rowH = Math.max(4, hostH - rowY);
        if (rowH >= hostH - 2 && rowY <= 1)
            return null;

        int bandW = nameColumnWidth(host, row, live);
        return new Rectangle(0, rowY, bandW, rowH);
    }

    /** Полоса выделения по display-координатам контролов строки (устойчиво при скролле секции). */
    private static Rectangle bandFromRowControls(Control host, PropertySheetPaletteRow row)
    {
        if (host == null || host.isDisposed() || row == null)
            return null;
        int hostH = host.getSize().y;
        if (row.lwtView != null)
        {
            Rectangle fromLight = PropertySheetControlInterop.selectionBandFromLightDisplay(row.lwtView, host);
            if (fromLight != null)
                return fromLight;
            Rectangle live = PropertySheetControlInterop.liveLwtRowBand(host, row.lwtView, row.propertyName);
            if (live != null && live.height > 0)
            {
                int rowY = Math.max(0, live.y - 2);
                int rowH = Math.min(36, Math.max(22, live.height + 4));
                if (rowY + rowH > hostH)
                    rowH = Math.max(4, hostH - rowY);
                if (rowH < hostH - 2 || rowY > 1)
                {
                    int bandW = nameColumnWidth(host, row, live);
                    return new Rectangle(0, rowY, bandW, rowH);
                }
            }
        }
        int[] bounds = displayBandForRow(row);
        if (bounds == null)
            return null;
        int top = bounds[0];
        int bottom = bounds[1];
        org.eclipse.swt.graphics.Point hostOrigin = host.toDisplay(0, 0);
        int rowY = top - hostOrigin.y;
        int rowH = bottom - top;
        if (rowH <= 0)
            return null;
        rowY = Math.max(0, rowY - 1);
        rowH = Math.min(hostH - rowY, Math.max(4, rowH + 2));
        if (rowH >= hostH - 2 && rowY <= 1)
            return null;
        int bandW = nameColumnWidth(host, row, null);
        return new Rectangle(0, rowY, bandW, rowH);
    }

    private static int[] displayBandForRow(PropertySheetPaletteRow row)
    {
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        if (row.nameControl != null && !row.nameControl.isDisposed())
        {
            org.eclipse.swt.graphics.Point d = row.nameControl.toDisplay(0, 0);
            top = Math.min(top, d.y);
            bottom = Math.max(bottom, d.y + row.nameControl.getSize().y);
        }
        if (row.rowControls != null)
        {
            for (Control value : row.rowControls)
            {
                if (value == null || value.isDisposed() || value == row.nameControl)
                    continue;
                org.eclipse.swt.graphics.Point d = value.toDisplay(0, 0);
                top = Math.min(top, d.y);
                bottom = Math.max(bottom, d.y + value.getSize().y);
            }
        }
        if (top == Integer.MAX_VALUE || bottom <= top)
            return null;
        return new int[] { top, bottom };
    }

    private static int nameColumnWidth(Control host, PropertySheetPaletteRow row, Rectangle live)
    {
        int fallback = Math.max(140, host.getSize().x / 2);
        int minValueX = Integer.MAX_VALUE;
        if (row.rowControls != null)
        {
            for (Control value : row.rowControls)
            {
                if (value == null || value.isDisposed() || value == host)
                    continue;
                Rectangle vb = valueBoundsInHost(host, value);
                if (vb != null && vb.x > 8)
                    minValueX = Math.min(minValueX, vb.x);
            }
        }
        if (minValueX != Integer.MAX_VALUE)
            return Math.max(80, minValueX - 4);
        if (live != null)
            return Math.min(fallback, Math.max(live.x + live.width + 12, fallback));
        return fallback;
    }

    private static Rectangle valueBoundsInHost(Control host, Control value)
    {
        if (host == null || host.isDisposed() || value == null || value.isDisposed())
            return null;
        Rectangle b = value.getBounds();
        Control p = value.getParent();
        if (p == host)
            return b;
        Point display = value.toDisplay(b.x, b.y);
        Point local = host.toControl(display);
        return new Rectangle(local.x, local.y, b.width, b.height);
    }

    private static Color selectionBg(Display display)
    {
        if (display == null || display.isDisposed())
            display = Display.getDefault();
        return display.getSystemColor(SWT.COLOR_LIST_SELECTION);
    }

    private static String describeInteractionTarget(PropertySheetPaletteRow row)
    {
        return describeInteractionTarget(row, PropertySheetUiCoordinator.pageForRow(row));
    }

    private static String describeInteractionTarget(PropertySheetPaletteRow row, Object page)
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
        Control chosen = interactionTarget(row, page);
        sb.append(" → ").append(PropertySheetDebug.controlBrief(chosen)); //$NON-NLS-1$
        return sb.toString();
    }

    private static String describeBand(Control host, PropertySheetPaletteRow row, Object page)
    {
        if (host == null || host.isDisposed() || row == null)
            return "band=<null>"; //$NON-NLS-1$
        if (row.selectionBandIsCanvas)
        {
            Rectangle band = resolveRowBand(host, row, page);
            return "band=scanViewport y=" + (band != null ? band.y : -1) //$NON-NLS-1$
                    + " h=" + (band != null ? band.height : -1) //$NON-NLS-1$
                    + " canvas=" + row.selectionBandTopCanvas + ".." + row.selectionBandBottomCanvas; //$NON-NLS-1$ //$NON-NLS-2$
        }
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

