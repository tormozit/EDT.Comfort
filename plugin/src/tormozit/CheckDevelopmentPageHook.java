package tormozit;

import java.util.WeakHashMap;

import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

/**
 * Страница свойств проекта «Настройки для разработчиков проверок»
 * ({@code com._1c.g5.v8.dt.internal.ui.validation.development.CheckDevelopmentPropertyPage},
 * пакет internal — доступ только рефлексией).
 *
 * <p><b>Подсказка флажку «Отключить режим массовых проверок».</b> Штатно у флажка нет ни
 * подсказки, ни пояснения, а последствия у него неочевидные и дорогие: пока он установлен,
 * проверки конфигурации не пересчитываются по массовым событиям — в том числе по синхронизации
 * файлов рабочей области, то есть после сохранения модуля. Проблемы, которые находят такие
 * проверки, перестают появляться в панели «Проблемы конфигурации», хотя в редакторе они
 * по-прежнему подчёркиваются и дают значок на иконке объекта — расхождение, которое без
 * пояснения выглядит дефектом панели.
 *
 * <p>Механика штатного флажка: значение живёт в {@code .settings/com.e1c.g5.v8.dt.check.prefs}
 * проекта (ключ {@code disableMassiveChecks}), читает его
 * {@code ICheckRepository.isMassiveCheckProcessDisabled}, а применяет
 * {@code CheckDerivedDataContributor$CheckDeactivationController.requiresDeactivation}: расчёт
 * проверок глушится для событий служб {@code SYNCHRONIZATION_MANAGER}, {@code CHECK_SCHEDULER},
 * {@code COMPARISON_MANAGER} и {@code REFACTORING_SERVICE}. Ошибки самого языка (синтаксис,
 * неопределённые методы) пишет другой путь — валидатор BSL, — и от флажка не зависят.
 */
public final class CheckDevelopmentPageHook
    implements IStartup
{
    private static final String PAGE_CLASS_NAME =
        "com._1c.g5.v8.dt.internal.ui.validation.development.CheckDevelopmentPropertyPage"; //$NON-NLS-1$

    /** Поле страницы с флажком «Отключить режим массовых проверок». */
    private static final String DISABLE_MASSIVE_CHECKS_FIELD = "disableMassiveChecks"; //$NON-NLS-1$

    private static final String PATCHED_KEY = "tormozit.checkDevelopmentPagePatched"; //$NON-NLS-1$

    private static final String TOOLTIP_DISABLE_MASSIVE_CHECKS =
        "Отключает пересчёт проверок конфигурации по массовым событиям: синхронизации файлов"
            + " рабочей области (в том числе после сохранения модуля), рефакторингу, сравнению"
            + " и объединению, а также по команде «Запустить проверку»."
            + " Пока флажок установлен, найденные проверками проблемы не попадают в панель"
            + " «Проблемы конфигурации» — в редакторе они подчёркиваются и дают значок на иконке"
            + " объекта, а в панели видны только ошибки самого языка: синтаксис, неопределённые"
            + " методы и т. п. Результаты прошлых проверок остаются в панели как есть.";

    private static final int MAX_ATTEMPTS = 30;

    private static final int RETRY_MS = 100;

    private static final WeakHashMap<Shell, Boolean> pendingWiring = new WeakHashMap<>();

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
            PreferenceDialog dialog = findPreferenceDialog(shell);
            if (dialog == null)
                return;
            scheduleWireOnce(display, shell, dialog);
        };

        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
        Debug.log("install: installed"); //$NON-NLS-1$
    }

    /** Окно свойств проекта — тоже {@link PreferenceDialog}, лежит в {@code getData()} оболочки. */
    private static PreferenceDialog findPreferenceDialog(Shell shell)
    {
        Shell current = shell;
        while (current != null && !current.isDisposed())
        {
            if (current.getData() instanceof PreferenceDialog dialog)
                return dialog;
            current = current.getParent() instanceof Shell parent ? parent : null;
        }
        return null;
    }

    private static void scheduleWireOnce(Display display, Shell shell, PreferenceDialog dialog)
    {
        synchronized (pendingWiring)
        {
            if (Boolean.TRUE.equals(pendingWiring.get(shell)))
                return;
            pendingWiring.put(shell, Boolean.TRUE);
        }

        dialog.addPageChangedListener(event -> tryPatchSelected(dialog.getSelectedPage()));
        scheduleRetry(display, shell, dialog, 0);
    }

    /**
     * Страница создаётся не мгновенно: при открытии окна свойств выбранная страница может ещё не
     * иметь виджетов, поэтому попытка повторяется, пока флажок не найден.
     */
    private static void scheduleRetry(Display display, Shell shell, PreferenceDialog dialog, int attempt)
    {
        if (shell.isDisposed())
            return;
        if (tryPatchSelected(dialog.getSelectedPage()) || attempt >= MAX_ATTEMPTS)
            return;
        display.timerExec(RETRY_MS, () -> scheduleRetry(display, shell, dialog, attempt + 1));
    }

    /** @return {@code true}, если страница не наша, уже пропатчена или пропатчена сейчас. */
    private static boolean tryPatchSelected(Object selected)
    {
        if (!(selected instanceof IPreferencePage page) || !PAGE_CLASS_NAME.equals(page.getClass().getName()))
            return true;

        Object field = Global.getField(page, DISABLE_MASSIVE_CHECKS_FIELD);
        if (!(field instanceof Button button) || button.isDisposed())
        {
            Debug.log("tryPatch WAIT: " + DISABLE_MASSIVE_CHECKS_FIELD + " not ready"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        if (Boolean.TRUE.equals(button.getData(PATCHED_KEY)))
            return true;

        button.setToolTipText(TooltipText.wrap(button, TOOLTIP_DISABLE_MASSIVE_CHECKS));
        button.setData(PATCHED_KEY, Boolean.TRUE);
        Debug.log("tryPatch PATCH OK"); //$NON-NLS-1$
        return true;
    }

    private static final class Debug
    {
        private static final String TAG = "CheckDevelopmentPageHook"; //$NON-NLS-1$

        private Debug()
        {
        }

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
