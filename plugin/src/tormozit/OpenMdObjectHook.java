package tormozit;

import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelDecorator;
import org.eclipse.jface.viewers.ILabelProvider;

import java.lang.reflect.Field;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog;

public class OpenMdObjectHook implements IStartup {

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            install(Display.getDefault());
        });
    }

    private static final String PATCHED_KEY = "tormozit.mdObjectPatched";

    public static void install(Display display) {
        if (display == null || display.isDisposed()) return;

        Listener listener = new Listener() {
            @Override
            public void handleEvent(Event event) {
                if (!(event.widget instanceof Shell)) return;
                Shell shell = (Shell) event.widget;
                Object dialog = shell.getData();
                String dialogName = dialog != null ? dialog.getClass().getName() : "";
                String shellName = shell.getClass().getName();

                boolean isMdDialog = dialogName.contains("OpenMdObjectSelectionDialog")
                                  || shellName.contains("OpenMdObjectSelectionDialog");
                if (!isMdDialog) return;

                if (shell.getData(PATCHED_KEY) != null) return;
                shell.setData(PATCHED_KEY, Boolean.TRUE); // ставим сразу, до asyncExec

                display.asyncExec(() -> {
                    if (!shell.isDisposed()) tryPatchDialog(shell, dialog);
                });
            }
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static void tryPatchDialog(Shell shell, Object dialog) {
        try {
            if (dialog == null) return;

            Object patternControlObj = Global.invoke(dialog, "getPatternControl");
            if (!(patternControlObj instanceof Text)) {
                OpenMdObjectDebug.log("patch FAIL getPatternControl=" + patternControlObj);
                return;
            }
            Text filterText = (Text) patternControlObj;

            Object currentBaseLpObj = Global.getField(dialog, "mdObjectLabelProvider");
            if (currentBaseLpObj == null) {
                currentBaseLpObj = Global.invoke(dialog, "getListLabelProvider");
            }
            if (!(currentBaseLpObj instanceof IBaseLabelProvider)) {
                OpenMdObjectDebug.log("patch FAIL labelProvider=" + currentBaseLpObj);
                return;
            }
            IBaseLabelProvider currentBaseLp = (IBaseLabelProvider) currentBaseLpObj;

            ILabelProvider currentLp = (ILabelProvider) currentBaseLp;
            IStyledLabelProvider currentStyled = (currentBaseLp instanceof IStyledLabelProvider)
                    ? (IStyledLabelProvider) currentBaseLp : null;
            ILabelDecorator currentDecorator = (currentBaseLp instanceof ILabelDecorator)
                    ? (ILabelDecorator) currentBaseLp : null;

            OpenMdObjectLabelProvider smartLp = new OpenMdObjectLabelProvider(
                    currentLp, currentStyled, currentDecorator);

            Global.invoke(dialog, "setListLabelProvider", smartLp);
            Global.invoke(dialog, "setListSelectionLabelDecorator", smartLp);

            OpenMdObjectComparator comparator = new OpenMdObjectComparator(smartLp);
            setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "itemsComparator", comparator);
            setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "fItemsComparator", comparator);

            org.eclipse.ui.dialogs.OpenMdObjectItemsFilter smartFilter =
                    new org.eclipse.ui.dialogs.OpenMdObjectItemsFilter(
                            (FilteredItemsSelectionDialog) dialog, smartLp, filterText.getText());

            for (Listener l : filterText.getListeners(SWT.Modify)) {
                filterText.removeListener(SWT.Modify, l);
            }
            for (Listener l : filterText.getListeners(SWT.KeyDown)) {
                filterText.removeListener(SWT.KeyDown, l);
            }

            Table listTable = getDialogTable(dialog, shell);
            if (listTable != null) {
                FilterFieldListNavigation.installTableNavigation(filterText, listTable);
                OpenMdObjectDebug.log("tableNav OK items=" + listTable.getItemCount());
            } else {
                OpenMdObjectDebug.log("tableNav SKIP table not found");
            }

            final Runnable[] pendingTask = new Runnable[1];
            final Display display = filterText.getDisplay();

            filterText.addModifyListener(e -> {
                String pattern = filterText.getText();
                OpenMdObjectDebug.log("modify text=\"" + pattern + "\" len=" + pattern.length());
                if (pendingTask[0] != null) display.timerExec(-1, pendingTask[0]);

                pendingTask[0] = () -> {
                    if (filterText.isDisposed()) return;
                    OpenMdObjectDebug.log("debounce fire text=\"" + filterText.getText() + "\"");
                    applySmartFilter(dialog, smartFilter, smartLp, comparator, filterText.getText());
                };
                display.timerExec(150, pendingTask[0]);
            });

            filterText.addDisposeListener(e -> {
                if (pendingTask[0] != null && !display.isDisposed()) {
                    display.timerExec(-1, pendingTask[0]);
                }
            });

            applySmartFilter(dialog, smartFilter, smartLp, comparator, filterText.getText());

            shell.setData(PATCHED_KEY, Boolean.TRUE);
            OpenMdObjectDebug.log("patch OK initial text=\"" + filterText.getText() + "\"");

            Label target = Global.findLabelByText(shell, "Выберите элемент");
            if (target != null && !target.isDisposed()) {
                target.setText("Фильтр разбивается на слова пробелами и ищется вхождение всех слов одновременно");
                target.getParent().layout();
            }

        } catch (Exception e) {
            OpenMdObjectDebug.log("patch ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Запуск фильтрации без {@code applyFilter}/{@code createFilter} — один экземпляр
     * {@link org.eclipse.ui.dialogs.OpenMdObjectItemsFilter} на весь цикл EDT jobs.
     */
    private static void applySmartFilter(Object dialog,
            org.eclipse.ui.dialogs.OpenMdObjectItemsFilter smartFilter,
            OpenMdObjectLabelProvider smartLp,
            OpenMdObjectComparator comparator,
            String pattern) {
        Object currentFilter = Global.getField(dialog, "filter");
        String patBefore = smartFilter.getPattern();
        boolean skip = smartFilter.shouldSkipSchedule(pattern, currentFilter);

        OpenMdObjectDebug.log("applySmartFilter text=\"" + pattern + "\" smartPatBefore=\"" + patBefore //$NON-NLS-1$
                + "\" current=" + OpenMdObjectDebug.filterDesc(currentFilter) + " skip=" + skip); //$NON-NLS-1$

        smartFilter.setPattern(pattern);
        smartLp.setPattern(pattern);
        comparator.setMatcher(new SmartMatcher(pattern));

        if (skip) {
            OpenMdObjectDebug.log("applySmartFilter SKIP schedule (pattern unchanged for EDT)");
            return;
        }

        Job filterHistoryJob = getJobField(dialog, "filterHistoryJob", "fFilterHistoryJob");
        Job filterJob = getJobField(dialog, "filterJob", "fFilterJob");
        OpenMdObjectDebug.log("applySmartFilter cancel jobs fh=" + OpenMdObjectDebug.jobState(filterHistoryJob) //$NON-NLS-1$
                + " fj=" + OpenMdObjectDebug.jobState(filterJob)); //$NON-NLS-1$
        if (filterHistoryJob != null) filterHistoryJob.cancel();
        if (filterJob != null) filterJob.cancel();

        Object contentProvider = Global.getField(dialog, "contentProvider");
        if (contentProvider != null) {
            Global.invoke(contentProvider, "stopReloadingCache");
        }

        Global.setField(dialog, "filter", smartFilter);
        setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "filter", smartFilter);

        if (filterHistoryJob != null) {
            filterHistoryJob.schedule();
            OpenMdObjectDebug.log("applySmartFilter SCHEDULED filterHistoryJob smartPat=\"" //$NON-NLS-1$
                    + smartFilter.getPattern() + "\""); //$NON-NLS-1$
        } else {
            OpenMdObjectDebug.log("applySmartFilter WARN filterHistoryJob not found");
        }
    }

    private static Table getDialogTable(Object dialog, Shell shell)
    {
        for (String field : new String[] { "tableViewer", "fTableViewer", "list" }) //$NON-NLS-1$
        {
            Object viewerObj = Global.getField(dialog, field);
            if (viewerObj instanceof TableViewer)
                return ((TableViewer) viewerObj).getTable();
        }
        if (shell != null && !shell.isDisposed())
        {
            Table fromShell = Global.findControl((Composite) shell, Table.class, t -> true);
            if (fromShell != null)
                return fromShell;
        }
        return null;
    }

    private static Job getJobField(Object dialog, String... fieldNames) {
        for (String name : fieldNames) {
            Object job = Global.getField(dialog, name);
            if (job instanceof Job) {
                return (Job) job;
            }
        }
        return null;
    }

    private static void setFieldExactClass(Object obj, Class<?> exactClass, String fieldName, Object value) {
        try {
            Field f = exactClass.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (NoSuchFieldException ignored) {
        } catch (Exception e) {
            OpenMdObjectDebug.log("setField FAIL " + exactClass.getName() + "." + fieldName + ": " + e.getMessage());
        }
    }
}
