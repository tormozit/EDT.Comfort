package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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
        if (row != null && row.hitDisplayY > 0)
            return lwtPaintHostAtDisplayY(row, page, row.hitDisplayY);
        return lwtPaintHostAtDisplayY(row, page, 0);
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
                drawSelectionBand(e.gc, host, row);
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
            return;
        }
        if (row.lwtView != null)
            PropertySheetControlInterop.refreshLwtRowGeometry(host, row.lwtView, row.propertyName);
        Rectangle bandPreview = resolveRowBand(host, row);
        // #region agent log
        agentActivateLog(row, host, bandPreview);
        // #endregion
        ensurePaintHook(host);
        log("activate " + PropertySheetDebug.quote(row.propertyName) //$NON-NLS-1$
                + " host=" + PropertySheetDebug.controlBrief(host) //$NON-NLS-1$
                + " " + describeBand(host, row)); //$NON-NLS-1$
        if (lastActivePaintHost != null && lastActivePaintHost != host && !lastActivePaintHost.isDisposed())
            clearActiveOnHost(lastActivePaintHost);
        host.setData(ACTIVE_PROP_KEY, row.propertyName);
        host.setData(ACTIVE_ROW_KEY, row);
        lastActivePaintHost = host;
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
        if (host == lastActivePaintHost)
            lastActivePaintHost = null;
    }

    /**
     * Рисуем полупрозрачный фон выделения поверх стандартной отрисовки контрола.
     * Alpha=60 (~24%) — видно, но не глушит текст и редактор значения.
     * Для LWT-хостов рисуем только полосу одной строки (не весь контейнер).
     */
    private static void drawSelectionBand(GC gc, Control host, PropertySheetPaletteRow row)
    {
        if (row != null && row.lwtView != null)
            PropertySheetControlInterop.refreshLwtRowGeometry(host, row.lwtView, row.propertyName);
        Rectangle band = resolveRowBand(host, row);
        if (band == null)
            return;
        int x = band.x;
        int y = band.y;
        int w = band.width;
        int h = band.height;

        if (w <= 0 || h <= 0)
            return;
        Color selColor = selectionBg(host.getDisplay());
        int alpha = gc.getAlpha();
        gc.setAlpha(60);
        gc.setBackground(selColor);
        gc.fillRectangle(x, y, w, h);
        gc.setAlpha(alpha);
    }

    private static Rectangle resolveRowBand(Control host, PropertySheetPaletteRow row)
    {
        if (host == null || host.isDisposed() || row == null)
            return null;
        int hostH = host.getSize().y;
        if (hostH <= 0)
            return null;

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

    // #region agent log
    private static void agentActivateLog(PropertySheetPaletteRow row, Control host, Rectangle band)
    {
        try
        {
            int hostTop = host != null && !host.isDisposed() ? host.toDisplay(0, 0).y : -1;
            int hostH = host != null && !host.isDisposed() ? host.getSize().y : -1;
            int bandY = band != null ? band.y : -1;
            int bandH = band != null ? band.height : -1;
            int bandDisplayY = band != null && host != null && !host.isDisposed()
                    ? host.toDisplay(band.x, band.y).y : -1;
            int clickGap = row.hitDisplayY > 0 && bandDisplayY >= 0
                    ? Math.abs(row.hitDisplayY - (bandDisplayY + (band != null ? band.height / 2 : 0))) : -1;
            boolean hadHook = host != null && Boolean.TRUE.equals(host.getData(PAINT_HOOK_KEY));
            boolean fullHostBand = band != null && hostH > 0 && band.y <= 1 && band.height >= hostH - 2;
            String line = "{\"sessionId\":\"db8c17\",\"hypothesisId\":\"H20\",\"location\":\"PropertySheetRowSelectionFeature.activate\"," //$NON-NLS-1$
                    + "\"message\":\"band\",\"data\":{\"prop\":\"" + escAgent(row.propertyName) //$NON-NLS-1$
                    + "\",\"clickY\":" + row.hitDisplayY //$NON-NLS-1$
                    + ",\"hostTop\":" + hostTop //$NON-NLS-1$
                    + ",\"hostH\":" + hostH //$NON-NLS-1$
                    + ",\"bandY\":" + bandY //$NON-NLS-1$
                    + ",\"bandH\":" + bandH //$NON-NLS-1$
                    + ",\"bandDisplayY\":" + bandDisplayY //$NON-NLS-1$
                    + ",\"clickGap\":" + clickGap //$NON-NLS-1$
                    + ",\"hadPaintHook\":" + hadHook //$NON-NLS-1$
                    + ",\"fullHostBand\":" + fullHostBand //$NON-NLS-1$
                    + "},\"timestamp\":" + System.currentTimeMillis() + "}\n"; //$NON-NLS-1$
            Files.writeString(Path.of("C:\\VC\\EDT.Comfort\\debug-db8c17.log"), line, //$NON-NLS-1$
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (Exception ignored)
        {
            // debug session only
        }
    }

    private static String escAgent(String s)
    {
        if (s == null)
            return ""; //$NON-NLS-1$
        return s.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
    // #endregion

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

