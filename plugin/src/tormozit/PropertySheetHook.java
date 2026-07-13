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
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;

/**
 * Подключение UI-доработок в панели «Свойства»:
 * подсветка горизонтальной полосы по клику, контекстное меню (копирование имени,
 * синтакс-помощник, «Перейти к определению» для значений с именем типа через точку).
 */
public final class PropertySheetHook implements IStartup
{
    @Override
    public void earlyStartup()
    {
        if (true)
        {
            return; // не осилил
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

        PropertySheetControlInterop.installPaletteCanvasListeners(page);

        // #region agent log
        Composite paletteContent = PropertySheetUiContext.findPaletteContent(page);
        PropertySheetControlInterop.agentHitLog("H0", "PropertySheetHook.tryPatch", "patched", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                java.util.Map.of("attempt", attempt, //$NON-NLS-1$
                        "contentClass", paletteContent != null ? paletteContent.getClass().getSimpleName() : "null", //$NON-NLS-1$ //$NON-NLS-2$
                        "contentH", paletteContent != null ? paletteContent.getSize().y : -1, //$NON-NLS-1$
                        "sessionCount", PropertySheetUiCoordinator.sessionCountForDebug())); //$NON-NLS-1$
        // #endregion

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
            // #region agent log
            PropertySheetControlInterop.agentHitLog("H0", "PropertySheetHook.ensureInstalled", "bridgeOk", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    java.util.Collections.emptyMap());
            // #endregion
        }

        private static void handleMouseDown(Event event)
        {
            if (event.button != 1 || !(event.widget instanceof Control))
                return;
            Control widget = (Control) event.widget;
            if (widget.isDisposed())
                return;
            if (PropertySheetUiContext.isFilterAreaControl(widget))
            {
                // #region agent log
                PropertySheetControlInterop.agentHitLog("H4", "PropertySheetHook.mouseDown", "filterArea", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        java.util.Map.of("widget", widget.getClass().getSimpleName())); //$NON-NLS-1$
                // #endregion
                return;
            }

            Point display = widget.toDisplay(event.x, event.y);
            Object page = PropertySheetUiCoordinator.pageForControl(widget);
            if (page == null)
            {
                // #region agent log
                PropertySheetControlInterop.agentHitLog("H4", "PropertySheetHook.mouseDown", "noPage", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        java.util.Map.of("widget", widget.getClass().getSimpleName(), //$NON-NLS-1$
                                "clickY", display.y, //$NON-NLS-1$
                                "diag", PropertySheetUiCoordinator.pageLookupDiag(widget))); //$NON-NLS-1$
                // #endregion
                return;
            }

            // #region agent log
            PropertySheetControlInterop.agentHitLog("H4", "PropertySheetHook.mouseDown", "entry", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    java.util.Map.of("clickX", display.x, "clickY", display.y, //$NON-NLS-1$ //$NON-NLS-2$
                            "widget", widget.getClass().getSimpleName())); //$NON-NLS-1$
            // #endregion
            PropertySheetPaletteRow row = resolveRowAt(page, widget, display);
            if (row == null)
            {
                // #region agent log
                PropertySheetControlInterop.agentHitLog("H5", "PropertySheetHook.mouseDown", "miss", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        java.util.Map.of("clickY", display.y, "ctxRows", contextRowCount(page))); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                PropertySheetDebug.problem("mouseDown MISS at=(" + display.x + "," + display.y + ") " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + "widget=" + PropertySheetDebug.controlBrief(widget) //$NON-NLS-1$
                        + " ctx.rows=" + contextRowCount(page)); //$NON-NLS-1$
                return;
            }

            row.setHitDisplayY(display.y);
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
            // #region agent log
            PropertySheetControlInterop.agentHitLog("H4", "PropertySheetHook.menuDetect", "entry", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    java.util.Map.of("clickY", display.y)); //$NON-NLS-1$
            // #endregion
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
            PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
            Object scene = ctx != null ? ctx.scene : null;
            Composite root = PropertySheetUiContext.findPaletteRoot(page);
            java.util.List<java.util.Map.Entry<?, ?>> labelEntries =
                    PropertySheetControlInterop.labelEntriesFromScene(scene);
            PropertySheetControlInterop.PropertyClickHit hit = root == null
                    ? null
                    : PropertySheetControlInterop.resolvePropertyAtClick(display, labelEntries,
                            root, page, scene);
            if (hit != null)
            {
                PropertySheetPaletteRow row = buildRowFromHit(page, scene, root, hit, display);
                if (row != null)
                    return row;
                // #region agent log
                PropertySheetControlInterop.agentHitLog("H7", "PropertySheetHook.resolveRowAt", "buildRowNull", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        java.util.Map.of("prop", hit.displayName, "via", hit.via)); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
            }
            return null;
        }

        /** Строка scanner ctx по имени из canvas-hit — без повторного area-scan. */
        private static PropertySheetPaletteRow scannerRowForHit(Object page, Object scene,
                PropertySheetControlInterop.PropertyClickHit hit, Point display)
        {
            PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
            if (ctx == null || hit == null || hit.displayName.isEmpty())
                return null;
            PropertySheetControlInterop.LwtPropertySection section = null;
            PropertySheetUiContext.PaletteCanvasSpace space =
                    PropertySheetUiContext.PaletteCanvasSpace.forPage(page);
            if (space != null)
            {
                java.util.List<PropertySheetControlInterop.LwtPropertySection> sections =
                        PropertySheetControlInterop.collectPropertySections(space.content);
                PropertySheetControlInterop.refreshSectionCanvasBounds(sections, space.content);
                section = PropertySheetControlInterop.findActiveSectionAtCanvasY(sections,
                        space.displayToCanvas(display).y);
            }
            Composite sectionBody = section != null ? section.body : null;
            for (PropertySheetPaletteRow paletteRow : ctx.rows)
            {
                if (!paletteRow.isAlive() || !hit.displayName.equals(paletteRow.propertyName))
                    continue;
                Composite host = PropertySheetControlInterop.paintHostForPaletteRow(paletteRow,
                        sectionBody, display.y);
                if (host == null)
                    continue;
                Object view = paletteRow.lwtView != null ? paletteRow.lwtView : hit.lwtView;
                String modelName = PropertySheetControlInterop.resolveModelPropertyName(page, scene,
                        view, hit.displayName);
                PropertySheetPaletteRow row = new PropertySheetPaletteRow(host, host,
                        PropertySheetUiContext.rowControls(host, host), hit.displayName, view,
                        modelName);
                row.setHitDisplayY(display.y);
                row.setSelectionDisplayBand(hit.areaTopDisplay, hit.areaBottomDisplay);
                // #region agent log
                PropertySheetControlInterop.agentHitLog("H21", "PropertySheetHook.scannerRowForHit", //$NON-NLS-1$ //$NON-NLS-2$
                        "ctxByName", java.util.Map.of("prop", hit.displayName, //$NON-NLS-1$ //$NON-NLS-2$
                                "hostH", host.getSize().y)); //$NON-NLS-1$
                // #endregion
                return row;
            }
            return null;
        }

        private static java.util.List<java.util.Map.Entry<?, ?>> labelEntriesFromScene(Object scene)
        {
            return PropertySheetControlInterop.labelEntriesFromScene(scene);
        }

        private static PropertySheetPaletteRow buildRowFromHit(Object page, Object scene,
                Composite root, PropertySheetControlInterop.PropertyClickHit hit, Point display)
        {
            if (hit == null || hit.displayName.isEmpty() || display == null)
            {
                // #region agent log
                PropertySheetControlInterop.agentHitLog("H7", "PropertySheetHook.buildRowFromHit", "earlyNull", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        java.util.Map.of("hitNull", hit == null, //$NON-NLS-1$
                                "nameEmpty", hit == null || hit.displayName.isEmpty())); //$NON-NLS-1$
                // #endregion
                return null;
            }
            if (!"scan".equals(hit.via)) //$NON-NLS-1$
                return null;
            PropertySheetUiContext.PaletteCanvasSpace space =
                    PropertySheetUiContext.PaletteCanvasSpace.forPage(page);
            Composite content = space != null ? space.content : root;
            if (content == null || content.isDisposed())
            {
                // #region agent log
                PropertySheetControlInterop.agentHitLog("H7", "PropertySheetHook.buildRowFromHit", "noContent", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        java.util.Map.of("prop", hit.displayName)); //$NON-NLS-1$
                // #endregion
                return null;
            }
            if (hit.areaBottomCanvas <= hit.areaTopCanvas)
                return null;
            PropertySheetPaletteRow row = new PropertySheetPaletteRow(content, content,
                    PropertySheetUiContext.rowControls(content, content), hit.displayName,
                    hit.lwtView, null);
            row.setHitDisplayY(display.y);
            row.setSelectionCanvasBand(hit.areaTopCanvas, hit.areaBottomCanvas);
            // #region agent log
            PropertySheetControlInterop.agentHitLog("H20", "PropertySheetHook.buildRowFromHit", "paintHost", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    java.util.Map.of("prop", hit.displayName, //$NON-NLS-1$
                            "host", content.getClass().getSimpleName(), //$NON-NLS-1$
                            "hostH", content.getSize().y, //$NON-NLS-1$
                            "topCanvas", hit.areaTopCanvas, //$NON-NLS-1$
                            "bottomCanvas", hit.areaBottomCanvas, //$NON-NLS-1$
                            "propertyIndex", hit.propertyIndex, //$NON-NLS-1$
                            "leaf", false)); //$NON-NLS-1$
            // #endregion
            return row;
        }

        private static PropertySheetPaletteRow hitTestContextRows(Object page, Point display)
        {
            PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
            if (ctx == null || ctx.rows.isEmpty() || display == null)
                return null;
            Object scene = ctx.scene;
            PropertySheetPaletteRow best = null;
            int bestDist = Integer.MAX_VALUE;
            for (PropertySheetPaletteRow row : ctx.rows)
            {
                if (!row.isAlive())
                    continue;
                if (!row.hitTest(display, page))
                    continue;
                int dist = row.hitTestDistance(display, page);
                if (dist < bestDist)
                {
                    bestDist = dist;
                    best = row;
                }
            }
            if (best != null)
            {
                if (best.lwtView != null)
                {
                    String model = PropertySheetControlInterop.resolveModelPropertyName(page, scene,
                            best.lwtView, best.propertyName);
                    if (model != null && !model.isEmpty()
                            && (best.copyPropertyName() == null || !model.equals(best.copyPropertyName())))
                    {
                        best = new PropertySheetPaletteRow(best.nameControl, best.rowComposite,
                                best.rowControls, best.propertyName, best.lwtView, model);
                    }
                }
                best.setHitDisplayY(display.y);
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

        private static int contextRowCount(Object page)
        {
            PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
            return ctx != null ? ctx.rows.size() : 0;
        }

    }

}
