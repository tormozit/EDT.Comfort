package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

/**
 * Окно EDT «Выбор команды» ({@code CommandChooseDialog}): на вкладке «Глобальные» штатное поле
 * поиска заменяется тем же многословным фильтром, что и на вкладке «Глобальные команды» редактора
 * формы — {@link FormEditorHook.GlobalCommandsFilter}. Деревья и поле поиска здесь те же по устройству
 * ({@code indepViewer}, {@code paramViewer}, {@code txtSearchGlobalCommand}), поэтому вся логика
 * общая, а хук только находит окно и передаёт эти виджеты.
 */
public final class CommandChooseDialogHook implements IStartup
{
    private static final String DIALOG_CLASS =
        "com._1c.g5.v8.dt.form.internal.ui.aef.components.CommandChooseDialog"; //$NON-NLS-1$

    private static final String HOOKED_KEY = "tormozit.commandChooseDialogHooked"; //$NON-NLS-1$

    private static final int MAX_ATTEMPTS = 40;

    private static final int RETRY_MS = 50;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> install(display));
    }

    private static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            if (Boolean.TRUE.equals(shell.getData(HOOKED_KEY)))
                return;
            Object dialog = shell.getData();
            if (dialog == null || !DIALOG_CLASS.equals(dialog.getClass().getName()))
                return;
            shell.setData(HOOKED_KEY, Boolean.TRUE);
            scheduleAttach(display, shell, dialog, 0);
        };
        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
    }

    private static void scheduleAttach(Display display, Shell shell, Object dialog, int attempt)
    {
        display.timerExec(attempt == 0 ? 0 : RETRY_MS, () ->
        {
            if (shell.isDisposed() || !ComfortSettings.isReplaceListFiltersEnabled())
                return;
            boolean done = FormEditorHook.GlobalCommandsFilter.attachViewers(
                Global.getField(dialog, "indepViewer"), //$NON-NLS-1$
                Global.getField(dialog, "paramViewer"), //$NON-NLS-1$
                Global.getField(dialog, "txtSearchGlobalCommand")); //$NON-NLS-1$
            if (!done && attempt < MAX_ATTEMPTS)
                scheduleAttach(display, shell, dialog, attempt + 1);
        });
    }
}
