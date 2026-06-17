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
 * подсветка горизонтальной полосы по клику, контекстное меню копирования имени свойства.
 */
public final class PropertySheetHook implements IStartup
{
    @Override
    public void earlyStartup()
    {
        if (true)
        {
            // Отключено, т.к. не доделано. 
            return;
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
            PropertySheetPaletteRow row = hitTestRendererLightLabel(page, widget, display);
            if (row == null)
                row = hitTestRow(page, display);
            if (row == null)
            {
                PropertySheetDebug.problem("mouseDown MISS at=(" + display.x + "," + display.y + ") " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + "widget=" + PropertySheetDebug.controlBrief(widget) //$NON-NLS-1$
                        + " ctx.rows=" + contextRowCount(page)); //$NON-NLS-1$
                return;
            }

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
            PropertySheetPaletteRow row = hitTestRendererLightLabel(page, widget, display);
            if (row == null)
                row = hitTestRow(page, display);
            if (row == null)
            {
                PropertySheetDebug.feature("menuDetect miss widget=" + PropertySheetDebug.controlBrief(widget)); //$NON-NLS-1$
                return;
            }

            event.doit = false;
            PropertySheetDebug.feature("menuDetect hit " + PropertySheetDebug.quote(row.propertyName)); //$NON-NLS-1$
            PropertySheetUiCoordinator.showRowContextMenu(page, row, widget, display);
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

            Point local = widget.toControl(display);
            if (local.x < 0 || local.y < 0 || local.x >= widget.getSize().x || local.y >= widget.getSize().y)
                return null;
            // Левая половина — зона имени; правая половина — зона значения.
            // В правой половине может быть LightLabel имени (это определяет следующий цикл до границы / 2).
            if (local.x > widget.getSize().x / 2)
                return null;

            PropertySheetPaletteRow best = null;
            int bestDist = Integer.MAX_VALUE;
            // Проходим все LabelViewModel — без slice-индексирования.
            // Принадлежность к widget определяется по реальным координатам LightLabel.
            for (Map.Entry<?, ?> entry : labelEntries)
            {
                Object vm = entry.getKey();
                String name = textOfViewModel(vm);
                if (name.isEmpty())
                    continue;
                Object view = entry.getValue();
                Object light = lightFromView(view);
                Object boundsObj = light != null ? Global.invoke(light, "getBounds") : null; //$NON-NLS-1$
                if (!(boundsObj instanceof Rectangle))
                    continue;
                Rectangle bounds = (Rectangle) boundsObj;
                Point lightDisplay = lightDisplayOrigin(light);
                Point lightLocal = lightDisplay != null ? widget.toControl(lightDisplay) : null;
                if (lightLocal == null)
                    lightLocal = new Point(bounds.x, bounds.y); // fallback: LWT local coords
                if (lightLocal == null)
                    continue;
                if (lightLocal.x < -8 || lightLocal.x > widget.getSize().x / 2)
                    continue;
                Rectangle band = new Rectangle(0, Math.max(0, lightLocal.y - 4),
                        Math.max(1, widget.getSize().x), Math.max(24, bounds.height + 8));
                if (local.y < band.y || local.y >= band.y + band.height)
                    continue;
                int dist = Math.abs(local.y - (lightLocal.y + Math.max(1, bounds.height / 2)));
                if (dist < bestDist)
                {
                    Composite host = widget instanceof Composite ? (Composite) widget : widget.getParent();
                    Point origin = PropertySheetControlInterop.lwtLabelDrawOrigin(light, host);
                    if (origin == null)
                    {
                        int textHeight = PropertySheetControlInterop.lwtTextHeight(light, host);
                        origin = new Point(Math.max(0, lightLocal.x + 3),
                                Math.max(0, lightLocal.y + Math.max(0, (bounds.height - textHeight) / 2)));
                    }
                    PropertySheetControlInterop.storeLwtRowGeometry(widget, view, name, origin, band);
                    Composite rowComposite = widget instanceof Composite ? (Composite) widget : widget.getParent();
                    best = new PropertySheetPaletteRow(widget, rowComposite,
                            PropertySheetUiContext.rowControls(rowComposite, widget), name, view);
                    bestDist = dist;
                }
            }
            if (best != null)
                PropertySheetDebug.feature("directLightLabel hit " + PropertySheetDebug.quote(best.propertyName)); //$NON-NLS-1$
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

        private static boolean sameOrRelated(Control a, Control b)
        {
            if (a == null || b == null || a.isDisposed() || b.isDisposed())
                return false;
            if (a == b)
                return true;
            if (b instanceof Composite && isDescendant(a, (Composite) b))
                return true;
            if (a instanceof Composite && isDescendant(b, (Composite) a))
                return true;
            return false;
        }

        private static boolean isDescendant(Control control, Composite ancestor)
        {
            if (control == null || ancestor == null || control.isDisposed() || ancestor.isDisposed())
                return false;
            for (Composite p = control.getParent(); p != null && !p.isDisposed(); p = p.getParent())
            {
                if (p == ancestor)
                    return true;
            }
            return false;
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

        private static PropertySheetPaletteRow hitTestRow(Object page, Point display)
        {
            PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
            if (ctx == null || ctx.rows.isEmpty())
                return null;

            PropertySheetPaletteRow best = null;
            int bestDist = Integer.MAX_VALUE;

            for (PropertySheetPaletteRow row : ctx.rows)
            {
                if (!row.isAlive())
                    continue;
                Control host = PropertySheetRowSelectionFeature.interactionTarget(row);
                if (host == null || host.isDisposed())
                    continue;

                Point local = host.toControl(display);
                if (local.x < 0 || local.y < 0 || local.x >= host.getSize().x || local.y >= host.getSize().y)
                    continue;

                if (PropertySheetControlInterop.isLwtPaintHost(host))
                {
                    Rectangle band = PropertySheetControlInterop.lwtRowBand(host, row.propertyName);
                    if (band == null)
                    {
                        Point origin = PropertySheetControlInterop.lwtHighlightOrigin(host, row.propertyName);
                        int bandH = PropertySheetControlInterop.lwtRowBandHeight(host, row.propertyName);
                        band = new Rectangle(0, Math.max(0, origin.y - 2), host.getSize().x, Math.max(4, bandH));
                    }
                    int bandTop = Math.max(0, band.y - 8);
                    int bandBottom = bandTop + Math.max(12, band.height + 16);
                    if (local.y < bandTop || local.y >= bandBottom)
                        continue;
                    int dist = Math.abs(local.y - (bandTop + Math.max(4, band.height) / 2));
                    if (dist < bestDist)
                    {
                        bestDist = dist;
                        best = row;
                    }
                    continue;
                }
                else if (local.x >= host.getSize().x / 2)
                {
                    continue;
                }

                int dist = Math.abs(local.y - host.getSize().y / 2);
                if (dist < bestDist)
                {
                    bestDist = dist;
                    best = row;
                }
            }
            return best;
        }

        private static int contextRowCount(Object page)
        {
            PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
            return ctx != null ? ctx.rows.size() : 0;
        }

    }

}
