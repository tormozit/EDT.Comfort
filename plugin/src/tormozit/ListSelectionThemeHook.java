package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import java.util.Set;
import java.util.Collections;
import java.util.WeakHashMap;

/**
 * Глобально: заметное выделение строки Table в тёмной теме EDT.
 * Tree (навигатор) — штатная подсветка только на ширину надписи, без полной строки.
 */
public final class ListSelectionThemeHook implements IStartup
{
    private static volatile boolean filtersInstalled;
    private static Listener showFilterListener;
    private static final Set<IWorkbenchPage> HOOKED_PAGES =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (display.getThread() == Thread.currentThread())
            install(display);
        else
            display.asyncExec(() -> install(display));
    }

    private static synchronized void install(Display display)
    {
        if (display == null || display.isDisposed() || filtersInstalled)
            return;
        filtersInstalled = true;
        showFilterListener = event -> {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            Enhancement.scanShell(shell);
        };
        display.addFilter(SWT.Show, showFilterListener);
        hookWorkbenchWindows();
        for (Shell shell : display.getShells())
        {
            if (shell != null && !shell.isDisposed())
                Enhancement.scanShell(shell);
        }
        display.timerExec(2000, () -> scanWorkbenchShells());
        display.timerExec(8000, () -> scanWorkbenchShells());
    }

    private static void hookWorkbenchWindows()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
            hookWindow(window);
        wb.addWindowListener(new org.eclipse.ui.IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override public void windowActivated(IWorkbenchWindow window) {}
            @Override public void windowDeactivated(IWorkbenchWindow window) {}
            @Override public void windowClosed(IWorkbenchWindow window) {}
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        Shell shell = window.getShell();
        if (shell != null && !shell.isDisposed())
            Enhancement.scanShell(shell);
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
            hookPage(page);
        window.addPageListener(new org.eclipse.ui.IPageListener()
        {
            @Override
            public void pageActivated(IWorkbenchPage activePage)
            {
                hookPage(activePage);
            }

            @Override public void pageClosed(IWorkbenchPage closedPage) {}
            @Override public void pageOpened(IWorkbenchPage openedPage)
            {
                hookPage(openedPage);
            }
        });
    }

    private static void hookPage(IWorkbenchPage page)
    {
        if (page == null || !HOOKED_PAGES.add(page))
            return;
        page.addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference partRef)
            {
                scanWorkbenchShells();
            }

            @Override public void partActivated(IWorkbenchPartReference partRef) {}
            @Override public void partBroughtToTop(IWorkbenchPartReference partRef) {}
            @Override public void partClosed(IWorkbenchPartReference partRef) {}
            @Override public void partDeactivated(IWorkbenchPartReference partRef) {}
            @Override public void partHidden(IWorkbenchPartReference partRef) {}
            @Override public void partVisible(IWorkbenchPartReference partRef)
            {
                scanWorkbenchShells();
            }

            @Override public void partInputChanged(IWorkbenchPartReference partRef) {}
        });
        scanWorkbenchShells();
    }

    /** Снять глобальный SWT.Show filter (preShutdown). */
    public static void uninstall()
    {
        if (!filtersInstalled)
            return;
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed() && showFilterListener != null)
            display.removeFilter(SWT.Show, showFilterListener);
        showFilterListener = null;
        filtersInstalled = false;
        HOOKED_PAGES.clear();
    }

    private static void scanWorkbenchShells()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
        {
            Shell shell = window.getShell();
            if (shell != null && !shell.isDisposed())
                Enhancement.scanShell(shell);
        }
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
        {
            for (Shell shell : display.getShells())
            {
                if (shell != null && !shell.isDisposed())
                    Enhancement.scanShell(shell);
            }
        }
    }

    /** Явная установка на таблицу (popup автодополнения создаётся после Show shell). */
    public static void ensureControl(Control list)
    {
        if (list == null || list.isDisposed())
            return;
        Enhancement.installIfNeeded(list);
    }

        private static final class Enhancement
        {
            private static final String INSTALLED_KEY = "tormozit.listSelectionThemeInstalled"; //$NON-NLS-1$
            private static final String LISTENER_KEY = "tormozit.listSelectionThemeListeners"; //$NON-NLS-1$

        private Enhancement() {}

        static void scanShell(Shell shell)
        {
            if (shell == null || shell.isDisposed())
                return;
            walkControl(shell);
        }

        private static void walkControl(Control control)
        {
            if (control == null || control.isDisposed())
                return;
            if (control instanceof Table table)
                installIfNeeded(table);
            else if (control instanceof Tree tree)
                uninstallTreeThemeIfInstalled(tree);
            if (control instanceof Composite composite)
            {
                for (Control child : composite.getChildren())
                    walkControl(child);
            }
        }

        static void uninstallTreeThemeIfInstalled(Tree tree)
        {
            if (tree == null || tree.isDisposed())
                return;
            if (!Boolean.TRUE.equals(tree.getData(INSTALLED_KEY)))
                return;
            uninstall(tree);
        }

        static void installIfNeeded(Control list)
        {
            if (!(list instanceof Table table))
                return;
            if (list == null || list.isDisposed() || ListSelectionThemeColors.isOptOut(list))
                return;
            if (Boolean.TRUE.equals(list.getData(INSTALLED_KEY)))
                return;

            Listener erase = Enhancement::onEraseItem;
            Listener focus = e -> syncTableSelection(table);
            Listener selection = e -> syncTableSelection(table);

            list.addListener(SWT.EraseItem, erase);
            list.addListener(SWT.FocusIn, focus);
            list.addListener(SWT.FocusOut, focus);
            list.addListener(SWT.Selection, selection);
            list.addDisposeListener(e -> uninstall(list));

            Listener[] bundle = new Listener[] { erase, focus, selection };
            list.setData(LISTENER_KEY, bundle);
            list.setData(INSTALLED_KEY, Boolean.TRUE);
            syncTableSelection(table);
            ListSelectionThemeColors.installSelectionPrePaintFilter(table, (t, item, col) ->
            {
                if (!isSelected(t, item))
                    return null;
                return ListSelectionThemeColors.listSelectionBackground(t, t.isFocusControl());
            }, "table"); //$NON-NLS-1$
        }

        static void uninstall(Control list)
        {
            if (list == null || list.isDisposed())
                return;
            Object stored = list.getData(LISTENER_KEY);
            if (stored instanceof Listener[] bundle && bundle.length >= 3)
            {
                list.removeListener(SWT.EraseItem, bundle[0]);
                list.removeListener(SWT.FocusIn, bundle[1]);
                list.removeListener(SWT.FocusOut, bundle[1]);
                list.removeListener(SWT.Selection, bundle[2]);
            }
            list.setData(LISTENER_KEY, null);
            list.setData(INSTALLED_KEY, null);
        }

        private static void onEraseItem(Event e)
        {
            if (e.gc == null)
                return;
            if (e.item instanceof TableItem tableItem)
                paintTableErase(e, tableItem);
        }

        private static void paintTableErase(Event e, TableItem item)
        {
            if (!(e.widget instanceof Table table) || table.isDisposed() || item.isDisposed())
                return;
            if (!ListSelectionThemeColors.isDarkList(table))
                return;
            if (!isSelected(table, item))
                return;
            Color bg = ListSelectionThemeColors.listSelectionBackground(table, table.isFocusControl());
            if (bg == null)
                return;
            ListSelectionThemeColors.fillSelectionBackground(e, table, item, bg);
            e.detail &= ~SWT.BACKGROUND;
            if (!bg.isDisposed())
                bg.dispose();
        }

        private static boolean isSelected(Table table, TableItem item)
        {
            for (TableItem selected : table.getSelection())
            {
                if (selected == item)
                    return true;
            }
            return false;
        }

        private static void syncTableSelection(Table table)
        {
            if (table == null || table.isDisposed())
                return;
            for (TableItem item : table.getSelection())
                redrawTableItem(table, item);
        }

        private static void redrawTableItem(Table table, TableItem item)
        {
            if (item == null || item.isDisposed())
                return;
            int cols = Math.max(1, table.getColumnCount());
            for (int c = 0; c < cols; c++)
            {
                Rectangle bounds = item.getBounds(c);
                if (bounds != null && !bounds.isEmpty())
                    table.redraw(bounds.x, bounds.y, bounds.width, bounds.height, false);
            }
        }
    }
}
