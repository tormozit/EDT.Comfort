package tormozit;

import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelDecorator;
import org.eclipse.jface.viewers.ILabelProvider;

import java.lang.reflect.Field;
import java.util.List;

import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
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
                if (shell.getData(PATCHED_KEY) != null) return;

                Object dialog = shell.getData();
                String dialogName = dialog != null ? dialog.getClass().getName() : "";
                String shellName = shell.getClass().getName();

                boolean isMdDialog = dialogName.contains("OpenMdObjectSelectionDialog")
                                  || shellName.contains("OpenMdObjectSelectionDialog");
                if (!isMdDialog) return;

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

            // --- 1. Text фильтра ---
            Object patternControlObj = Global.invoke(dialog, "getPatternControl");
            if (!(patternControlObj instanceof Text)) {
                Global.log("OpenMdObjectHook: getPatternControl returned " + patternControlObj);
                return;
            }
            Text filterText = (Text) patternControlObj;

            // --- 2. Текущий LabelProvider ---
            Object currentBaseLpObj = Global.getField(dialog, "mdObjectLabelProvider");
            if (currentBaseLpObj == null) {
                currentBaseLpObj = Global.invoke(dialog, "getListLabelProvider");
            }
            if (!(currentBaseLpObj instanceof IBaseLabelProvider)) {
                Global.log("OpenMdObjectHook: labelProvider not found: " + currentBaseLpObj);
                return;
            }
            IBaseLabelProvider currentBaseLp = (IBaseLabelProvider) currentBaseLpObj;

            ILabelProvider currentLp = (ILabelProvider) currentBaseLp;
            IStyledLabelProvider currentStyled = (currentBaseLp instanceof IStyledLabelProvider)
                    ? (IStyledLabelProvider) currentBaseLp : null;
            ILabelDecorator currentDecorator = (currentBaseLp instanceof ILabelDecorator)
                    ? (ILabelDecorator) currentBaseLp : null;

            // --- 3. Умный LabelProvider ---
            OpenMdObjectLabelProvider smartLp = new OpenMdObjectLabelProvider(
                    currentLp, currentStyled, currentDecorator);
            smartLp.setPattern(filterText.getText());

            Global.invoke(dialog, "setListLabelProvider", smartLp);
            Global.invoke(dialog, "setListSelectionLabelDecorator", smartLp);

            // --- 4. Компаратор ---
            OpenMdObjectComparator comparator = new OpenMdObjectComparator(smartLp);
            comparator.setMatcher(new SmartMatcher(filterText.getText()));
            setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "itemsComparator", comparator);
            setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "fItemsComparator", comparator);

            // --- 5. Фильтр ---
            org.eclipse.ui.dialogs.OpenMdObjectItemsFilter smartFilter =
                    new org.eclipse.ui.dialogs.OpenMdObjectItemsFilter(
                            (FilteredItemsSelectionDialog) dialog, smartLp, filterText.getText());

            // --- 6. Удаляем стандартный modify listener EDT ---
            for (Listener l : filterText.getListeners(SWT.Modify)) {
                filterText.removeListener(SWT.Modify, l);
            }

            // --- 7. Debounce ---
            final Runnable[] pendingTask = new Runnable[1];
            final Display display = filterText.getDisplay();
            final boolean[] isSettingFakeText = new boolean[1];

            filterText.addModifyListener(e -> {
                if (isSettingFakeText[0]) return;

                String pattern = filterText.getText();
                if (pendingTask[0] != null) display.timerExec(-1, pendingTask[0]);

                pendingTask[0] = () -> {
                    if (filterText.isDisposed()) return;
                    smartFilter.setPattern(pattern);
                    smartLp.setPattern(pattern);
                    comparator.setMatcher(new SmartMatcher(pattern));

                    if (pattern.trim().isEmpty() && !" ".equals(filterText.getText())) {
                        // Пустой фильтр: устанавливаем фейковый пробел, чтобы RefreshJob
                        // прошел через fillContentProvider → matchItem()
                        isSettingFakeText[0] = true;
                        try {
                            filterText.setText(" ");
                            Global.invoke(dialog, "applyFilter");
                            // applyFilter создаст MdObjectItemsFilter, подменяем на наш
                            Global.setField(dialog, "filter", smartFilter);
                            setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "filter", smartFilter);
                        } finally {
                            isSettingFakeText[0] = false;
                        }

                        // После завершения job восстанавливаем пустой текст
                        restoreTextAfterJob(dialog, filterText, display, isSettingFakeText);
                    } else {
                        Global.invoke(dialog, "applyFilter");
                        Global.setField(dialog, "filter", smartFilter);
                        setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "filter", smartFilter);
                    }
                };
                display.timerExec(150, pendingTask[0]);
            });

            filterText.addDisposeListener(e -> {
                if (pendingTask[0] != null && !display.isDisposed()) {
                    display.timerExec(-1, pendingTask[0]);
                }
            });

            // При открытии
            if (filterText.getText().trim().isEmpty() && !" ".equals(filterText.getText())) {
                isSettingFakeText[0] = true;
                try {
                    filterText.setText(" ");
                    Global.invoke(dialog, "applyFilter");
                    Global.setField(dialog, "filter", smartFilter);
                    setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "filter", smartFilter);
                } finally {
                    isSettingFakeText[0] = false;
                }
                restoreTextAfterJob(dialog, filterText, display, isSettingFakeText);
            } else {
                Global.invoke(dialog, "applyFilter");
                Global.setField(dialog, "filter", smartFilter);
                setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "filter", smartFilter);
            }

            shell.setData(PATCHED_KEY, Boolean.TRUE);
            Global.log("OpenMdObjectHook: patched successfully");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void restoreTextAfterJob(Object dialog, Text filterText,
                                             Display display, boolean[] isSettingFakeText) {
        Object refreshJob = Global.getField(dialog, "refreshJob");
        if (refreshJob == null) refreshJob = Global.getField(dialog, "fRefreshJob");

        Runnable restore = () -> {
            if (!filterText.isDisposed() && " ".equals(filterText.getText())) {
                isSettingFakeText[0] = true;
                try {
                    filterText.setText("");
                } finally {
                    isSettingFakeText[0] = false;
                }
            }
        };

        if (refreshJob instanceof Job) {
            Job job = (Job) refreshJob;
            int state = job.getState();
            if (state == Job.RUNNING || state == Job.WAITING) {
                job.addJobChangeListener(new JobChangeAdapter() {
                    @Override
                    public void done(IJobChangeEvent event) {
                        display.asyncExec(restore);
                    }
                });
                return;
            }
        }
        // Job уже завершился или не найден — восстанавливаем сразу
        display.asyncExec(restore);
    }

    private static void setFieldExactClass(Object obj, Class<?> exactClass, String fieldName, Object value) {
        try {
            Field f = exactClass.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (NoSuchFieldException ignored) {
        } catch (Exception e) {
            Global.log("OpenMdObjectHook: failed to set " + exactClass.getName() + "." + fieldName + ": " + e);
        }
    }
}