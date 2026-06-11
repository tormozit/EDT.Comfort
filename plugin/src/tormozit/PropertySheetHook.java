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
}
