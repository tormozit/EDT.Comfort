package tormozit;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Делает штатное окно настроек динамического списка
 * ({@code com._1c.g5.v8.dt.internal.form.ui.dynamiclist.aef.dialogs.DynamicListQueryDialog})
 * немодальным — чтобы при открытом окне можно было работать с навигатором и панелью
 * «Свойства» (issue #428, мотив 1C‑EDT #555). Дополнительно выводит в заголовок окна полный
 * путь к реквизиту, например {@code Справочник.Валюты.Форма.ФормаСписка.Реквизит.ДинамическийСписок}.
 *
 * <p>Модальность в SWT/Win32 полностью эмулируется: {@code Shell.setVisible(true)} добавляет
 * shell в {@code Display.modalShells[]}, {@code Control.isActive()} возвращает {@code false}
 * для прочих shell'ов, {@code Shell.updateModal()} делает {@code EnableWindow(handle, isActive())}.
 * Нативного модального стиля окна нет. Поэтому достаточно убрать бит {@link SWT#APPLICATION_MODAL}
 * из package‑private поля {@code Widget.style} и/или вызвать package‑private
 * {@code Display.clearModal(shell)}, после чего вернуть {@code EnableWindow} прочим shell'ам.
 *
 * <p>Перехват — двумя фильтрами {@link Display#addFilter}:
 * <ul>
 * <li>{@link SWT#Resize} — срабатывает в {@code Window.create()} (до {@code open()}), пока shell
 * ещё невиден и ещё не в {@code modalShells[]}: снимаем бит стиля заранее, модальность не
 * возникает вовсе;</li>
 * <li>{@link SWT#Show} — гарантированный fallback и обработка повторных показов: снимаем бит,
 * {@code clearModal}, повторно включаем sibling‑окна.</li>
 * </ul>
 *
 * <p>Собственное подменю «Комфорт» во встроенных QL‑редакторах этого же окна ставит отдельный
 * хук {@link QueryTextEditDialogHook}; координация — через разные ключи {@code shell.setData}.
 */
public final class DynamicListSettingsDialogHook implements IStartup
{
    private static final String SHELL_HOOKED_KEY = "tormozit.dynListSettingsShellHooked"; //$NON-NLS-1$
    private static final String TITLE_KEY = "tormozit.dynListSettingsTitle"; //$NON-NLS-1$

    private static final String DIALOG_CLASS_SUFFIX = "DynamicListQueryDialog"; //$NON-NLS-1$
    private static final String DIALOG_TITLE = "Динамический список"; //$NON-NLS-1$
    private static final String ATTRIBUTE_TOKEN = "Реквизит"; //$NON-NLS-1$

    /**
     * Окно уже принадлежит shell'у workbench (JFace создаёт его с родителем — активным shell),
     * поэтому за редактор оно не «тонет». Закрепление через Win32 owner нужно лишь как запас,
     * если тестирование покажет обратное — тогда {@code true}.
     */
    private static final boolean PIN_ABOVE_OWNER = false;

    private static final int TITLE_RETRY_LIMIT = 12;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = DynamicListSettingsDialogHook::handleEvent;
        display.addFilter(SWT.Resize, listener);
        display.addFilter(SWT.Show, listener);

        DynamicListSettingsDialogDebug.log("install Resize + Show filters"); //$NON-NLS-1$
    }

    private static void handleEvent(Event event)
    {
        if (!(event.widget instanceof Shell shell) || shell.isDisposed())
            return;

        if (Boolean.TRUE.equals(shell.getData(SHELL_HOOKED_KEY)))
        {
            if (event.type == SWT.Show)
                reassert(shell);
            return;
        }

        if (!isDynamicListSettingsShell(shell))
            return;

        onShellDetected(shell, event.type);
    }

    private static void onShellDetected(Shell shell, int eventType)
    {
        shell.setData(SHELL_HOOKED_KEY, Boolean.TRUE);
        IEditorPart editorAtOpen = activeEditor();

        DynamicListSettingsDialogDebug.log("detected via type=" + eventType //$NON-NLS-1$
            + " visible=" + shell.getVisible()); //$NON-NLS-1$

        demodalize(shell);
        installMaintenance(shell);
        pinAboveOwner(shell);
        scheduleTitle(shell, editorAtOpen, 0);
    }

    /** Повторный показ / восстановление окна: снова снять модальность и починить заголовок. */
    private static void reassert(Shell shell)
    {
        demodalize(shell);
        Object title = shell.getData(TITLE_KEY);
        if (title instanceof String s && !s.equals(shell.getText()))
            shell.setText(s);
        pinAboveOwner(shell);
    }

    // --- демодализация -----------------------------------------------------------------------

    private static void demodalize(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;

        Object styleObj = Global.getField(shell, "style"); //$NON-NLS-1$
        if (styleObj instanceof Integer style && (style & SWT.APPLICATION_MODAL) != 0)
        {
            Global.setField(shell, "style", Integer.valueOf(style & ~SWT.APPLICATION_MODAL)); //$NON-NLS-1$
            DynamicListSettingsDialogDebug.log("cleared APPLICATION_MODAL (style 0x" //$NON-NLS-1$
                + Integer.toHexString(style) + ")"); //$NON-NLS-1$
        }

        Display display = shell.getDisplay();
        // Идемпотентно: убирает shell из modalShells[], если он там есть, иначе no‑op.
        Global.invoke(display, "clearModal", shell); //$NON-NLS-1$
        reenableSiblings(display, shell);
    }

    private static void reenableSiblings(Display display, Shell ourShell)
    {
        // Если поверх открыт ЧУЖОЙ модальный дочерний диалог (пикер поля/типа/выражения) —
        // блокировка workbench правильная, не вмешиваемся.
        Object modal = Global.invoke(display, "getModalShell"); //$NON-NLS-1$
        if (modal != null && modal != ourShell)
            return;

        for (Shell s : display.getShells())
        {
            if (s == ourShell || s.isDisposed())
                continue;

            Object st = Global.getField(s, "style"); //$NON-NLS-1$
            if (st instanceof Integer i
                && (i & (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL)) != 0)
                continue;

            if (!s.getEnabled())
                s.setEnabled(true);
            Global.invoke(s, "updateModal"); //$NON-NLS-1$
            WinWindowActivator.setWindowEnabled(WinWindowActivator.hwndFromShell(s), true);
        }
    }

    // --- заголовок --------------------------------------------------------------------------

    private static void scheduleTitle(Shell shell, IEditorPart editorAtOpen, int attempt)
    {
        if (shell.isDisposed())
            return;

        Display display = shell.getDisplay();
        display.timerExec(attempt == 0 ? 0 : 60, () ->
        {
            if (shell.isDisposed())
                return;

            String path = resolvePath(shell, editorAtOpen);
            if (path != null)
            {
                if (!path.equals(shell.getText()))
                    shell.setText(path);
                shell.setData(TITLE_KEY, path);
                DynamicListSettingsDialogDebug.log("title set: " + path); //$NON-NLS-1$
                return;
            }

            if (attempt < TITLE_RETRY_LIMIT)
                scheduleTitle(shell, editorAtOpen, attempt + 1);
            else
                DynamicListSettingsDialogDebug.problem("полный путь реквизита не определён"); //$NON-NLS-1$
        });
    }

    private static String resolvePath(Shell shell, IEditorPart editorAtOpen)
    {
        Object dialog = resolveDialog(shell);
        if (dialog == null)
            return null;

        Object model = Global.getField(dialog, "model"); //$NON-NLS-1$
        if (model == null)
            return null;

        Object attr = Global.invoke(model, "getAttribute"); //$NON-NLS-1$
        if (!(attr instanceof EObject attrEo))
            return null;

        Object nameObj = Global.invoke(attrEo, "getName"); //$NON-NLS-1$
        if (!(nameObj instanceof String attrName) || attrName.isBlank())
            return null;

        String formPath = resolveFormPath(attrEo, editorAtOpen);
        if (formPath == null)
            return null;

        return formPath + '.' + ATTRIBUTE_TOKEN + '.' + attrName;
    }

    private static String resolveFormPath(EObject attrEo, IEditorPart editorAtOpen)
    {
        for (EObject c = attrEo.eContainer(); c != null; c = c.eContainer())
        {
            if ("Form".equals(c.eClass().getName())) //$NON-NLS-1$
            {
                String p = GetRef.eObjectToFullName(c);
                if (p != null)
                    return p;
                break;
            }
        }

        if (editorAtOpen != null)
        {
            String p = GetRef.getRefFromEditor(editorAtOpen);
            if (p != null)
                return p;
        }

        IEditorPart now = activeEditor();
        return now != null ? GetRef.getRefFromEditor(now) : null;
    }

    // --- закрепление над workbench ---------------------------------------------------------

    private static void pinAboveOwner(Shell shell)
    {
        if (!PIN_ABOVE_OWNER || shell == null || shell.isDisposed())
            return;

        Shell owner = resolveOwnerShell();
        WinWindowActivator.clearShellTopmost(shell);
        WinWindowActivator.setShellAboveOwner(shell, owner, owner != null);
    }

    private static Shell resolveOwnerShell()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
            {
                IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
                if (windows != null && windows.length > 0)
                    window = windows[0];
            }
            if (window != null)
            {
                Shell workbenchShell = window.getShell();
                if (workbenchShell != null && !workbenchShell.isDisposed())
                    return workbenchShell;
            }
        }
        catch (Exception ignored)
        {
            // fallback: без владельца
        }
        return null;
    }

    // --- обслуживание -------------------------------------------------------------------------

    private static void installMaintenance(Shell shell)
    {
        Listener maintenance = e ->
        {
            if (shell.isDisposed())
                return;
            demodalize(shell);
            Object title = shell.getData(TITLE_KEY);
            if (title instanceof String s && !s.equals(shell.getText()))
                shell.setText(s);
            pinAboveOwner(shell);
        };
        shell.addListener(SWT.Show, maintenance);
        shell.addListener(SWT.Activate, maintenance);
        shell.addListener(SWT.Deiconify, maintenance);
    }

    // --- распознавание окна ----------------------------------------------------------------

    private static boolean isDynamicListSettingsShell(Shell shell)
    {
        if (resolveDialog(shell) != null)
            return true;
        return DIALOG_TITLE.equals(shell.getText());
    }

    private static Object resolveDialog(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;

        for (String key : new String[] { null, "org.eclipse.jface.window.Window", //$NON-NLS-1$
            "org.eclipse.jface.dialogs.Dialog.dialog" }) //$NON-NLS-1$
        {
            Object data = key == null ? shell.getData() : shell.getData(key);
            if (data != null && data.getClass().getName().endsWith(DIALOG_CLASS_SUFFIX))
                return data;
        }
        return null;
    }

    private static IEditorPart activeEditor()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            return page == null ? null : page.getActiveEditor();
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /**
     * Отладочный журнал канала «Комфорт». Включение: Параметры → Комфорт → «Общее логирование».
     */
    private static final class DynamicListSettingsDialogDebug
    {
        private static final String TAG = "DynListSettings"; //$NON-NLS-1$

        private DynamicListSettingsDialogDebug()
        {
        }

        static boolean isEnabled()
        {
            return Global.isLogEnabled();
        }

        static void log(String msg)
        {
            if (isEnabled())
                Global.log(TAG, msg);
        }

        static void problem(String msg)
        {
            if (isEnabled())
                Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
        }
    }
}
