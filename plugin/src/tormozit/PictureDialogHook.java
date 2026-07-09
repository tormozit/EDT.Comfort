package tormozit;

import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;

public class PictureDialogHook implements IStartup {

    private static final String PATCHED_KEY = "tormozit.pictureDialogPatched";

    @Override
    public void earlyStartup() {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display) {
        if (display == null || display.isDisposed())
            return;

        Listener listener = new Listener() {
            @Override
            public void handleEvent(Event event) {
                if (!(event.widget instanceof Shell shell))
                    return;
                if (shell.isDisposed())
                    return;
                Object dialog = shell.getData();
                if (dialog == null)
                    return;
                String dialogName = dialog.getClass().getName();
                if (!dialogName.contains("PictureDialog"))
                    return;
                if (shell.getData(PATCHED_KEY) != null)
                    return;

                shell.getDisplay().asyncExec(() -> {
                    if (!shell.isDisposed())
                        tryPatchDialog(shell, dialog);
                });
            }
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static boolean tryPatchDialog(Shell shell, Object dialog) {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return false;
        try {
            if (dialog == null)
                return false;

            Text search = (Text) Global.getField(dialog, "search");
            if (search == null || search.isDisposed()) {
                shell.getDisplay().asyncExec(() -> {
                    if (!shell.isDisposed() && shell.getData(PATCHED_KEY) == null)
                        tryPatchDialog(shell, dialog);
                });
                return false;
            }

            synchronized (shell) {
                if (shell.getData(PATCHED_KEY) != null)
                    return true;
                shell.setData(PATCHED_KEY, Boolean.TRUE);
            }

            TableViewer commonViewer = (TableViewer) Global.getField(dialog, "commonPictureViewer");
            TableViewer standartViewer = (TableViewer) Global.getField(dialog, "standartPictureViewer");

            Object oldFilter = Global.getField(dialog, "filter");
            Object oldFilterStandart = Global.getField(dialog, "filterStandart");

            SmartMatcherPictureFilter customFilter = new SmartMatcherPictureFilter();
            SmartMatcherPictureFilter customFilterStandart = new SmartMatcherPictureFilter();

            if (commonViewer != null && !commonViewer.getControl().isDisposed()) {
                if (oldFilter instanceof ViewerFilter vf)
                    commonViewer.removeFilter(vf);
                commonViewer.addFilter(customFilter);
            }

            if (standartViewer != null && !standartViewer.getControl().isDisposed()) {
                if (oldFilterStandart instanceof ViewerFilter vf)
                    standartViewer.removeFilter(vf);
                standartViewer.addFilter(customFilterStandart);
            }

            for (Listener l : search.getListeners(SWT.Modify))
                search.removeListener(SWT.Modify, l);

            FilterInputBox filterInput = FilterInputBox.replacePatternText(
                    search, FilterInputBox.Scope.PICTURE_DIALOG, null);
            if (filterInput == null) {
                shell.setData(PATCHED_KEY, null);
                return false;
            }

            Control fc = filterInput.inputControl();
            if (fc == null)
                fc = filterInput.widget();
            final Control filterControl = fc;

            final Runnable[] pendingFilterTask = new Runnable[1];

            addFilterModifyListener(filterControl, new ModifyListener() {
                @Override
                public void modifyText(ModifyEvent e) {
                    String pattern = getFilterPattern(filterControl);
                    Display display = filterControl.getDisplay();

                    if (pendingFilterTask[0] != null)
                        display.timerExec(-1, pendingFilterTask[0]);

                    pendingFilterTask[0] = () -> applyFilter(
                            dialog, customFilter, customFilterStandart,
                            commonViewer, standartViewer, pattern);

                    display.timerExec(150, pendingFilterTask[0]);
                }
            });

            filterControl.addDisposeListener(e -> {
                if (pendingFilterTask[0] != null && !filterControl.getDisplay().isDisposed())
                    filterControl.getDisplay().timerExec(-1, pendingFilterTask[0]);
            });

            addHighlightToTable(commonViewer, customFilter);
            addHighlightToTable(standartViewer, customFilterStandart);

            installFilterNavigation(filterControl, dialog,
                    commonViewer, standartViewer);

            hookTabSwitching(dialog, customFilter, customFilterStandart,
                    commonViewer, standartViewer, filterControl);

            filterInput.scheduleFocusWhenReady();

            String initialText = filterInput.getText();
            if (initialText != null && !initialText.isEmpty())
                applyFilter(dialog, customFilter, customFilterStandart,
                        commonViewer, standartViewer, initialText);

            return true;

        } catch (Exception e) {
            shell.setData(PATCHED_KEY, null);
            Global.logError("PictureDialog", "patch", e);
            return false;
        }
    }

    private static void applyFilter(
            Object dialog,
            SmartMatcherPictureFilter customFilter,
            SmartMatcherPictureFilter customFilterStandart,
            TableViewer commonViewer,
            TableViewer standartViewer,
            String pattern) {

        customFilter.setPattern(pattern);
        customFilterStandart.setPattern(pattern);

        if (commonViewer != null && !commonViewer.getControl().isDisposed())
            commonViewer.refresh();
        if (standartViewer != null && !standartViewer.getControl().isDisposed())
            standartViewer.refresh();

        Table activeTable = tableForActiveTab(dialog, commonViewer, standartViewer);
        if (activeTable != null && !activeTable.isDisposed()
                && activeTable.getItemCount() > 0 && activeTable.getSelectionCount() == 0) {
            activeTable.setSelection(0);
            TableItem[] sel = activeTable.getSelection();
            if (sel.length > 0) {
                Event ev = new Event();
                ev.type = SWT.Selection;
                ev.widget = activeTable;
                ev.item = sel[0];
                activeTable.notifyListeners(SWT.Selection, ev);
            }
        }
    }

    private static void hookTabSwitching(
            Object dialog,
            SmartMatcherPictureFilter customFilter,
            SmartMatcherPictureFilter customFilterStandart,
            TableViewer commonViewer,
            TableViewer standartViewer,
            Control filterControl) {

        CTabFolder tabFolder = findTabFolder(dialog);
        if (tabFolder == null || tabFolder.isDisposed())
            return;

        tabFolder.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                String pattern = getFilterPattern(filterControl);
                if (!pattern.isEmpty())
                    applyFilter(dialog, customFilter, customFilterStandart,
                            commonViewer, standartViewer, pattern);
                selectFirstRowIfEmpty(dialog, commonViewer, standartViewer);
            }
        });
    }

    private static CTabFolder findTabFolder(Object dialog) {
        CTabFolder inner = findInnerTabFolder(dialog);
        if (inner != null)
            return inner;
        return findOuterTabFolder(dialog);
    }

    private static CTabFolder findInnerTabFolder(Object dialog) {
        for (String field : new String[]{"tabItemFromConfig", "tabItemStandart"}) {
            Object tabItem = Global.getField(dialog, field);
            if (tabItem instanceof CTabItem item) {
                CTabFolder folder = item.getParent();
                if (folder != null && !folder.isDisposed())
                    return folder;
            }
        }
        return null;
    }

    private static CTabFolder findOuterTabFolder(Object dialog) {
        for (String field : new String[]{"tabItemFromLibrary", "tabItemFromFile"}) {
            Object tabItem = Global.getField(dialog, field);
            if (tabItem instanceof CTabItem item) {
                CTabFolder folder = item.getParent();
                if (folder != null && !folder.isDisposed())
                    return folder;
            }
        }
        return null;
    }

    private static String getFilterPattern(Control filterControl) {
        if (filterControl == null || filterControl.isDisposed())
            return "";
        if (filterControl instanceof Text t)
            return t.getText();
        if (filterControl instanceof StyledText st)
            return st.getText();
        return "";
    }

    private static void addFilterModifyListener(Control filterControl, ModifyListener listener) {
        if (filterControl instanceof Text t)
            t.addModifyListener(listener);
        else if (filterControl instanceof StyledText st)
            st.addModifyListener(listener);
    }

    private static void addHighlightToTable(TableViewer viewer,
            SmartMatcherPictureFilter filter) {
        if (viewer == null)
            return;
        Table table = viewer.getTable();
        if (table == null || table.isDisposed())
            return;

        table.addListener(SWT.PaintItem, e -> {
            SmartMatcher m = filter.getMatcher();
            if (m == null || m.isEmpty)
                return;
            SmartMatchHighlight.paintTableCellMatchOverlay(
                    e, table, (TableItem) e.item, m);
        });
    }

    private static void installFilterNavigation(Control filterControl, Object dialog,
            TableViewer commonViewer, TableViewer standartViewer) {
        Table table = tableForActiveTab(dialog, commonViewer, standartViewer);
        if (table == null)
            return;

        FilterInputBoxListNavigation.installTableNavigation(
                filterControl, table, null, false, () -> {
                    Global.invoke(dialog, "okPressed");
                    return true;
                });

        CTabFolder tabFolder = findTabFolder(dialog);
        if (tabFolder == null || tabFolder.isDisposed())
            return;

        tabFolder.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                updateNavigationTarget(filterControl, dialog,
                        commonViewer, standartViewer);
            }
        });
    }

    private static void updateNavigationTarget(Control filterControl, Object dialog,
            TableViewer commonViewer, TableViewer standartViewer) {
        Table newTable = tableForActiveTab(dialog, commonViewer, standartViewer);
        if (newTable == null)
            return;

        var searchBox = FilterInputBox.resolveSearchBox(filterControl);
        if (searchBox == null || searchBox.isDisposed())
            return;

        Object navCtx = searchBox.getData("tormozit.filterNavContext");
        if (navCtx != null)
            Global.setField(navCtx, "table", newTable);
    }

    private static Table tableForActiveTab(Object dialog,
            TableViewer commonViewer, TableViewer standartViewer) {
        CTabFolder tabFolder = findTabFolder(dialog);
        if (tabFolder == null || tabFolder.isDisposed())
            return null;
        CTabItem selected = tabFolder.getSelection();
        if (selected == null || selected.isDisposed())
            return null;
        Control tabControl = selected.getControl();
        if (tabControl != null && !tabControl.isDisposed()) {
            Table t = tableInsideControl(tabControl, commonViewer, standartViewer);
            if (t != null)
                return t;
        }
        return null;
    }

    private static Table tableInsideControl(Control parent,
            TableViewer commonViewer, TableViewer standartViewer) {
        if (commonViewer != null) {
            Table t = commonViewer.getTable();
            if (t != null && !t.isDisposed() && isDescendantOf(t, parent))
                return t;
        }
        if (standartViewer != null) {
            Table t = standartViewer.getTable();
            if (t != null && !t.isDisposed() && isDescendantOf(t, parent))
                return t;
        }
        return null;
    }

    private static boolean isDescendantOf(Control child, Control ancestor) {
        for (Control p = child.getParent(); p != null && !p.isDisposed(); p = p.getParent())
            if (p == ancestor)
                return true;
        return false;
    }

    private static void selectFirstRowIfEmpty(Object dialog,
            TableViewer commonViewer, TableViewer standartViewer) {
        CTabFolder tabFolder = findTabFolder(dialog);
        if (tabFolder == null || tabFolder.isDisposed())
            return;
        CTabItem selected = tabFolder.getSelection();
        if (selected == null || selected.isDisposed())
            return;
        Control tabControl = selected.getControl();
        if (tabControl == null || tabControl.isDisposed())
            return;
        Table table = tableInsideControl(tabControl, commonViewer, standartViewer);
        if (table == null || table.isDisposed() || table.getItemCount() == 0)
            return;
        if (table.getSelectionCount() > 0)
            return;
        table.setSelection(0);
    }
}
