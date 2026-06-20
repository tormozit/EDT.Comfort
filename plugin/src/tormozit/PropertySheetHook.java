package tormozit;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;

/**
 * Подключение UI-доработок в панели «Свойства»:
 * подсветка горизонтальной полосы по клику, контекстное меню (копирование имени, синтакс-помощник).
 */
public final class PropertySheetHook implements IStartup
{
    @Override
    public void earlyStartup()
    {
        if (true)
        {
            return; // Отключено, т.к. не доделано
        }
        Display.getDefault().asyncExec(() -> {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
                return;
            PropertySheetDebug.log("[ui] debug flags: " + PropertySheetDebug.flags()); //$NON-NLS-1$
            PropertySheetDebug.uiVerbose("earlyStartup: install window listener"); //$NON-NLS-1$
            for (IWorkbenchWindow window : wb.getWorkbenchWindows())
                hookWindow(window);
            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)
                {
                    PropertySheetUiCoordinator.cancelAll();
                }
            });
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null)
                continue;
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (isPropertySheetView(view))
                    schedulePatch(view, 0, false);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)    { tryFromRef(ref, false); }
            @Override public void partVisible(IWorkbenchPartReference ref)   { tryFromRef(ref, false); }
            @Override public void partActivated(IWorkbenchPartReference ref) { tryFromRef(ref, false); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (isPropertySheetView(part))
                    PropertySheetUiCoordinator.cancelForView(part);
            }
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partInputChanged(IWorkbenchPartReference r) { tryFromRef(r, true); }

            private void tryFromRef(IWorkbenchPartReference ref, boolean inputChanged)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (isPropertySheetView(part))
                    schedulePatch((IViewPart) part, 0, inputChanged);
            }
        });
    }

    private static boolean isPropertySheetView(Object part)
    {
        if (!(part instanceof IViewPart))
            return false;
        String id = ((IViewPart) part).getViewSite().getId();
        return Global.PROPERTIES_SHEET_ID.equals(id)
                || "org.eclipse.ui.views.PropertySheet".equals(id); //$NON-NLS-1$
    }

    private static void schedulePatch(IViewPart view, int attempt, boolean inputChanged)
    {
        Display display = Display.getDefault();
        int delay = attempt == 0 ? 0 : 100;
        display.timerExec(delay, () -> {
            if (!tryPatch(view, attempt, inputChanged) && attempt < 25)
                schedulePatch(view, attempt + 1, inputChanged);
            else if (attempt >= 25)
                PropertySheetDebug.problem("tryPatch GIVE UP after 25 attempts"); //$NON-NLS-1$
        });
    }

    private static boolean tryPatch(IViewPart view, int attempt, boolean inputChanged)
    {
        if (PropertySheetDebug.isTrace() && (attempt == 0 || attempt % 5 == 0))
            PropertySheetDebug.uiVerbose("tryPatch #" + attempt); //$NON-NLS-1$

        Object page = resolvePropertySheetPage(view);
        if (page == null)
        {
            if (PropertySheetDebug.isTrace() && (attempt == 0 || attempt % 5 == 0))
                PropertySheetDebug.uiVerbose("tryPatch #" + attempt + " WAIT: page=null"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }

        Object palette = Global.invoke(page, "getPaletteComponent"); //$NON-NLS-1$
        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        if (palette == null || scene == null)
        {
            if (PropertySheetDebug.isTrace() && (attempt == 0 || attempt % 5 == 0))
                PropertySheetDebug.uiVerbose("tryPatch #" + attempt + " WAIT: palette=" //$NON-NLS-1$ //$NON-NLS-2$
                        + PropertySheetDebug.safe(palette) + " scene=" + PropertySheetDebug.safe(scene)); //$NON-NLS-1$
            return false;
        }

        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            PropertySheetMouseBridge.ensureInstalled(display);

        PropertySheetUiCoordinator.scheduleSync(page, new SmartMatcher("")); //$NON-NLS-1$

        PropertySheetDebug.uiVerbose("tryPatch #" + attempt + " OK page=" //$NON-NLS-1$ //$NON-NLS-2$
                + page.getClass().getSimpleName());
        return true;
    }

    private static Object resolvePropertySheetPage(IViewPart view)
    {
        Object page = Global.invoke(view, "getCurrentPage"); //$NON-NLS-1$
        if (isPropertySheetPage(page))
            return page;
        Object pageBook = Global.invoke(view, "getPageBook"); //$NON-NLS-1$
        page = Global.invoke(pageBook, "getCurrentPage"); //$NON-NLS-1$
        if (isPropertySheetPage(page))
            return page;
        return null;
    }

    private static boolean isPropertySheetPage(Object page)
    {
        if (page == null)
            return false;
        return page.getClass().getName().contains("PropertySheetPage"); //$NON-NLS-1$
    }

    /**
     * Глобальный display-фильтр для кликов по LWT-палитре свойств.
     * SwtLightComposite не всегда доставляет MouseDown/MenuDetect в наш listener на host.
     */
    private static final class PropertySheetMouseBridge
    {
        private static volatile boolean installed;

        private PropertySheetMouseBridge() {}

        static void ensureInstalled(Display display)
        {
            if (display == null || display.isDisposed() || installed)
                return;
            display.addFilter(SWT.MouseDown, PropertySheetMouseBridge::handleMouseDown);
            display.addFilter(SWT.MenuDetect, PropertySheetMouseBridge::handleMenuDetect);
            installed = true;
            PropertySheetDebug.uiVerbose("mouseBridge installed"); //$NON-NLS-1$
        }

        private static void handleMouseDown(Event event)
        {
            if (event.button != 1 || !(event.widget instanceof Control))
                return;
            Control widget = (Control) event.widget;
            if (widget.isDisposed() || PropertySheetUiContext.isFilterAreaControl(widget))
                return;

            Object page = PropertySheetUiCoordinator.pageForControl(widget);
            if (page == null)
                return;

            Point display = widget.toDisplay(event.x, event.y);
            PropertySheetPaletteRow row = resolveRowAt(page, widget, display);
            if (row == null)
            {
                PropertySheetDebug.problem("mouseDown MISS at=(" + display.x + "," + display.y + ") " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + "widget=" + PropertySheetDebug.controlBrief(widget) //$NON-NLS-1$
                        + " ctx.rows=" + contextRowCount(page)); //$NON-NLS-1$
                return;
            }

            row.setHitDisplayY(display.y);
            // #region agent log
            agentHitDetail(page, row, display);
            // #endregion

            PropertySheetDebug.feature("mouseDown hit " + PropertySheetDebug.quote(row.propertyName)); //$NON-NLS-1$
            PropertySheetUiCoordinator.handleRowClick(page, row);
        }

        private static void handleMenuDetect(Event event)
        {
            if (!(event.widget instanceof Control))
                return;
            Control widget = (Control) event.widget;
            if (widget.isDisposed() || PropertySheetUiContext.isFilterAreaControl(widget))
                return;

            Object page = PropertySheetUiCoordinator.pageForControl(widget);
            if (page == null)
                return;

            Point display = event.x > 0 || event.y > 0
                    ? new Point(event.x, event.y) : widget.getDisplay().getCursorLocation();
            PropertySheetPaletteRow row = resolveRowAt(page, widget, display);
            if (row == null)
            {
                PropertySheetDebug.feature("menuDetect miss widget=" + PropertySheetDebug.controlBrief(widget)); //$NON-NLS-1$
                return;
            }

            event.doit = false;
            row.setHitDisplayY(display.y);
            PropertySheetDebug.feature("menuDetect hit " + PropertySheetDebug.quote(row.propertyName)); //$NON-NLS-1$
            PropertySheetUiCoordinator.handleRowClick(page, row);
            PropertySheetUiCoordinator.showRowContextMenu(page, row, widget, display);
        }

        private static PropertySheetPaletteRow resolveRowAt(Object page, Control widget, Point display)
        {
            PropertySheetPaletteRow direct = hitTestRendererLightLabel(page, widget, display);
            if (direct != null)
            {
                PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
                Object scene = ctx != null ? ctx.scene : null;
                String modelAtClick = PropertySheetControlInterop.resolveModelPropertyName(page, scene,
                        direct.lwtView, direct.propertyName);
                PropertySheetPaletteRow matched = matchContextRow(page, direct.propertyName, direct.lwtView);
                PropertySheetPaletteRow base = matched != null ? matched : direct;
                if (modelAtClick != null && !modelAtClick.isEmpty())
                {
                    String cached = base.copyPropertyName();
                    if (cached == null || cached.isEmpty() || !modelAtClick.equals(cached))
                        base = rowWithModel(base, modelAtClick);
                }
                return base;
            }
            return hitTestContextRows(page, display);
        }

        private static PropertySheetPaletteRow rowWithModel(PropertySheetPaletteRow row, String model)
        {
            return new PropertySheetPaletteRow(row.nameControl, row.rowComposite, row.rowControls,
                    row.propertyName, row.lwtView, model);
        }

        private static PropertySheetPaletteRow hitTestContextRows(Object page, Point display)
        {
            PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
            if (ctx == null || ctx.rows.isEmpty() || display == null)
                return null;
            PropertySheetPaletteRow best = null;
            int bestDist = Integer.MAX_VALUE;
            Composite root = PropertySheetUiContext.findPaletteRoot(page);
            Composite clickSection = root != null && !root.isDisposed()
                    ? PropertySheetControlInterop.findSectionBodyAtDisplayY(root, display.y) : null;
            for (PropertySheetPaletteRow row : ctx.rows)
            {
                if (!row.isAlive())
                    continue;
                if (clickSection != null && row.lwtView != null)
                {
                    Rectangle light = PropertySheetControlInterop.liveLightDisplayBoundsOnHost(row.lwtView,
                            clickSection);
                    if (light == null)
                        continue;
                    int centerY = light.y + Math.max(1, light.height / 2);
                    if (Math.abs(display.y - centerY) > 14)
                        continue;
                }
                if (!row.hitTest(display, page))
                    continue;
                int dist = row.hitTestDistance(display, page);
                if (dist < bestDist)
                {
                    bestDist = dist;
                    best = row;
                }
            }
            return best;
        }

        private static PropertySheetPaletteRow matchContextRow(Object page, String propertyName, Object lwtView)
        {
            if (propertyName == null || propertyName.isEmpty())
                return null;
            PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
            if (ctx == null)
                return null;
            if (lwtView != null)
            {
                for (PropertySheetPaletteRow row : ctx.rows)
                {
                    if (!row.isAlive())
                        continue;
                    if (lwtView == row.lwtView)
                        return row;
                }
                return null;
            }
            for (PropertySheetPaletteRow row : ctx.rows)
            {
                if (!row.isAlive())
                    continue;
                if (propertyName.equals(row.propertyName))
                    return row;
            }
            return null;
        }

        private static PropertySheetPaletteRow hitTestRendererLightLabel(Object page, Control widget, Point display)
        {
            if (page == null || widget == null || widget.isDisposed() || display == null)
                return null;
            PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
            if (ctx == null || ctx.scene == null)
                return null;
            Object renderer = Global.invoke(ctx.scene, "getRenderer"); //$NON-NLS-1$
            Object mapObj = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$
            if (!(mapObj instanceof Map))
                return null;
            List<Map.Entry<?, ?>> labelEntries = labelEntries((Map<?, ?>) mapObj);
            if (labelEntries.isEmpty())
                return null;

            Composite root = PropertySheetUiContext.findPaletteRoot(page);
            Composite clickSection = root != null && !root.isDisposed()
                    ? PropertySheetControlInterop.findSectionBodyAtDisplayY(root, display.y) : null;

            PropertySheetPaletteRow best = null;
            int bestDist = Integer.MAX_VALUE;
            for (Map.Entry<?, ?> entry : labelEntries)
            {
                Object vm = entry.getKey();
                String name = textOfViewModel(vm);
                if (name.isEmpty())
                    continue;
                Object view = entry.getValue();
                Composite paintHost = clickSection;
                if (paintHost == null || paintHost.isDisposed())
                {
                    Composite leaf = PropertySheetControlInterop.leafFieldRowHostForView(view);
                    if (leaf != null && !leaf.isDisposed() && leaf.getSize().y <= 80)
                        paintHost = leaf;
                    if (paintHost == null || paintHost.isDisposed())
                        paintHost = PropertySheetControlInterop.findPaintHostForView(view, root);
                }
                if (paintHost == null || paintHost.isDisposed())
                    continue;

                Rectangle liveBand = PropertySheetControlInterop.liveRowBandInHost(view, paintHost);
                if (liveBand == null || liveBand.height <= 0)
                    continue;

                Point bandTopLeft = paintHost.toDisplay(liveBand.x, liveBand.y);
                int rowTop = bandTopLeft.y - 2;
                int rowBottom = rowTop + liveBand.height + 4;
                if (display.y < rowTop || display.y >= rowBottom)
                    continue;

                Point hostLocal = paintHost.toControl(display);
                if (hostLocal.x < 0 || hostLocal.y < 0
                        || hostLocal.x >= paintHost.getSize().x || hostLocal.y >= paintHost.getSize().y)
                    continue;

                int dist = Math.abs(display.y - (bandTopLeft.y + Math.max(1, liveBand.height / 2)));
                if (dist < bestDist)
                {
                    PropertySheetControlInterop.refreshLwtRowGeometry(paintHost, view, name);
                    String modelName = PropertySheetControlInterop.resolveModelPropertyName(page, ctx.scene, view, name);
                    best = new PropertySheetPaletteRow(paintHost, paintHost,
                            PropertySheetUiContext.rowControls(paintHost, paintHost), name, view, modelName);
                    bestDist = dist;
                }
            }
            if (best != null)
            {
                // #region agent log
                agentRendererHit(display, best, clickSection, bestDist);
                // #endregion
                PropertySheetDebug.feature("directLightLabel hit " + PropertySheetDebug.quote(best.propertyName)); //$NON-NLS-1$
            }
            else
            {
                // #region agent log
                agentRendererMiss(display, clickSection, labelEntries.size());
                // #endregion
            }
            return best;
        }

        private static List<Map.Entry<?, ?>> labelEntries(Map<?, ?> map)
        {
            List<Map.Entry<?, ?>> out = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet())
            {
                Object vm = entry.getKey();
                if (vm != null && vm.getClass().getName().contains("LabelViewModel")) //$NON-NLS-1$
                    out.add(entry);
            }
            return out;
        }

        private static Object lightFromView(Object view)
        {
            if (view == null)
                return null;
            Object light = Global.getField(view, "lightControl"); //$NON-NLS-1$
            if (light == null)
                light = Global.getField(view, "lightLabel"); //$NON-NLS-1$
            if (light == null)
                light = Global.getField(view, "nativeControl"); //$NON-NLS-1$
            if (light == null)
                light = Global.invoke(view, "getNativeControl"); //$NON-NLS-1$
            return light;
        }

        private static Point lightDisplayOrigin(Object light)
        {
            if (light == null)
                return null;
            Object pt = Global.invoke(light, "toDisplay", Integer.valueOf(0), Integer.valueOf(0)); //$NON-NLS-1$
            if (pt instanceof Point)
                return (Point) pt;
            Object abs = Global.invoke(light, "getAbsoluteBounds"); //$NON-NLS-1$
            if (abs instanceof Rectangle)
            {
                Rectangle r = (Rectangle) abs;
                return new Point(r.x, r.y);
            }
            Object loc = Global.invoke(light, "getLocationInWindow"); //$NON-NLS-1$
            if (loc instanceof Point)
                return (Point) loc;
            return null;
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

        private static int contextRowCount(Object page)
        {
            PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
            return ctx != null ? ctx.rows.size() : 0;
        }

        // #region agent log
        private static void agentHitDetail(Object page, PropertySheetPaletteRow row, Point display)
        {
            try
            {
                Composite root = PropertySheetUiContext.findPaletteRoot(page);
                Control host = PropertySheetRowSelectionFeature.lwtPaintHostAtDisplayY(row, page, display.y);
                int hostTop = host != null && !host.isDisposed() ? host.toDisplay(0, 0).y : -1;
                Composite clickSection = root != null
                        ? PropertySheetControlInterop.findSectionBodyAtDisplayY(root, display.y) : null;
                int clickSectionTop = clickSection != null && !clickSection.isDisposed()
                        ? clickSection.toDisplay(0, 0).y : -1;
                boolean hostHasHook = host != null && Boolean.TRUE.equals(host.getData("tormozit.ps.rowSelectPaint")); //$NON-NLS-1$
                String line = "{\"sessionId\":\"db8c17\",\"hypothesisId\":\"H13\",\"location\":\"PropertySheetHook.mouseDown\"," //$NON-NLS-1$
                        + "\"message\":\"hit\",\"data\":{\"display\":\"" + escJson(row.propertyName) //$NON-NLS-1$
                        + "\",\"model\":\"" + escJson(row.copyPropertyName()) //$NON-NLS-1$
                        + "\",\"clickY\":" + display.y //$NON-NLS-1$
                        + ",\"hostTop\":" + hostTop //$NON-NLS-1$
                        + ",\"clickSectionTop\":" + clickSectionTop //$NON-NLS-1$
                        + ",\"hostHasPaintHook\":" + hostHasHook //$NON-NLS-1$
                        + "},\"timestamp\":" + System.currentTimeMillis() + "}\n"; //$NON-NLS-1$
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of("C:\\VC\\EDT.Comfort\\debug-db8c17.log"), //$NON-NLS-1$
                        line, java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
            catch (Exception ignored)
            {
                // debug session only
            }
        }

        private static void agentRendererHit(Point display, PropertySheetPaletteRow best, Composite clickSection,
                int dist)
        {
            try
            {
                int sectionTop = clickSection != null && !clickSection.isDisposed()
                        ? clickSection.toDisplay(0, 0).y : -1;
                String line = "{\"sessionId\":\"db8c17\",\"hypothesisId\":\"H17\",\"location\":\"PropertySheetHook.rendererHit\"," //$NON-NLS-1$
                        + "\"message\":\"direct\",\"data\":{\"prop\":\"" + escJson(best.propertyName) //$NON-NLS-1$
                        + "\",\"clickY\":" + display.y + ",\"dist\":" + dist //$NON-NLS-1$
                        + ",\"clickSectionTop\":" + sectionTop //$NON-NLS-1$
                        + "},\"timestamp\":" + System.currentTimeMillis() + "}\n"; //$NON-NLS-1$
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of("C:\\VC\\EDT.Comfort\\debug-db8c17.log"), //$NON-NLS-1$
                        line, java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
            catch (Exception ignored)
            {
                // debug session only
            }
        }

        private static void agentRendererMiss(Point display, Composite clickSection, int labelCount)
        {
            try
            {
                int sectionTop = clickSection != null && !clickSection.isDisposed()
                        ? clickSection.toDisplay(0, 0).y : -1;
                String line = "{\"sessionId\":\"db8c17\",\"hypothesisId\":\"H18\",\"location\":\"PropertySheetHook.rendererMiss\"," //$NON-NLS-1$
                        + "\"message\":\"miss\",\"data\":{\"clickY\":" + display.y //$NON-NLS-1$
                        + ",\"labels\":" + labelCount + ",\"clickSectionTop\":" + sectionTop //$NON-NLS-1$
                        + "},\"timestamp\":" + System.currentTimeMillis() + "}\n"; //$NON-NLS-1$
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of("C:\\VC\\EDT.Comfort\\debug-db8c17.log"), //$NON-NLS-1$
                        line, java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
            catch (Exception ignored)
            {
                // debug session only
            }
        }

        private static String escJson(String s)
        {
            if (s == null)
                return ""; //$NON-NLS-1$
            return s.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        // #endregion

    }

}
