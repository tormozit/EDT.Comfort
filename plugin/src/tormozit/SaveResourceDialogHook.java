package tormozit;

import java.lang.reflect.Field;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

/**
 * Диалог Eclipse «Сохранить ресурс» при закрытии редактора, который ещё открыт
 * в другом месте с теми же изменениями. Русский фрагмент {@code nl_ru} не содержит
 * ключей {@code EditorManager_saveChangesOptionallyQuestion} и
 * {@code EditorManager_saveChangesQuestion} — заголовок, флажок и кнопки уже
 * русские, а текст сообщения остаётся английским. Подставляем русские строки
 * в {@code WorkbenchMessages} при старте; на случай, если поле недоступно,
 * тот же текст правится в показанном {@code Shell}.
 */
public final class SaveResourceDialogHook implements IStartup
{
    private static final String TAG = "SaveResourceDialog"; //$NON-NLS-1$
    private static final String PATCHED_KEY = "tormozit.saveResourceDialogPatched"; //$NON-NLS-1$
    private static final String MESSAGES_CLASS = "org.eclipse.ui.internal.WorkbenchMessages"; //$NON-NLS-1$

    private static final String FIELD_OPTIONAL = "EditorManager_saveChangesOptionallyQuestion"; //$NON-NLS-1$
    private static final String FIELD_QUESTION = "EditorManager_saveChangesQuestion"; //$NON-NLS-1$

    private static final String EN_OPTIONAL =
        "''{0}'' has been modified, but is still open elsewhere with identical changes. Closing this will not lose those changes. Save anyway?"; //$NON-NLS-1$
    private static final String RU_OPTIONAL_TAIL =
        " изменён, но всё ещё открыт в другом месте с теми же изменениями. Закрытие этого окна не приведёт к их потере. Сохранить?"; //$NON-NLS-1$
    private static final String RU_OPTIONAL = "''{0}''" + RU_OPTIONAL_TAIL; //$NON-NLS-1$

    private static final String EN_QUESTION = "Save ''{0}''?"; //$NON-NLS-1$
    private static final String RU_QUESTION = "Сохранить ''{0}''?"; //$NON-NLS-1$

    private static final String TITLE_RU = "Сохранить ресурс"; //$NON-NLS-1$
    private static final String TITLE_EN = "Save Resource"; //$NON-NLS-1$
    private static final String OPTIONAL_SNIPPET =
        "has been modified, but is still open elsewhere with identical changes"; //$NON-NLS-1$
    private static final String OPTIONAL_AFTER_NAME = "' has been modified"; //$NON-NLS-1$
    private static final String SAVE_PREFIX = "Save '"; //$NON-NLS-1$
    private static final String SAVE_SUFFIX = "'?"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        patchWorkbenchMessages();
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    private static void patchWorkbenchMessages()
    {
        try
        {
            Class<?> messages = IStartup.class.getClassLoader().loadClass(MESSAGES_CLASS);
            patchField(messages, FIELD_OPTIONAL, EN_OPTIONAL, RU_OPTIONAL);
            patchField(messages, FIELD_QUESTION, EN_QUESTION, RU_QUESTION);
        }
        catch (Exception e)
        {
            Global.logError(TAG, "WorkbenchMessages", e); //$NON-NLS-1$
        }
    }

    private static void patchField(Class<?> messages, String fieldName, String english, String russian)
    {
        try
        {
            Field field = messages.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(null);
            if (!(value instanceof String current) || !english.equals(current.trim()))
                return;
            field.set(null, russian);
            Global.log(TAG, "NLS " + fieldName); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.logError(TAG, fieldName, e);
        }
    }

    private static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        Listener listener = event ->
        {
            if (event.widget instanceof Shell shell && !shell.isDisposed())
                onShellEvent(display, shell);
        };
        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
    }

    private static void onShellEvent(Display display, Shell shell)
    {
        if (shell.getData(PATCHED_KEY) != null)
            return;
        String title = shell.getText();
        if (!TITLE_RU.equals(title) && !TITLE_EN.equals(title))
            return;
        schedulePatch(display, shell, 0);
    }

    private static void schedulePatch(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;
        display.timerExec(attempt == 0 ? 0 : 50, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (tryPatchShell(shell))
            {
                shell.setData(PATCHED_KEY, Boolean.TRUE);
                return;
            }
            if (attempt < 8)
                schedulePatch(display, shell, attempt + 1);
        });
    }

    /** @return {@code true}, если сообщение уже русское или только что переведено */
    private static boolean tryPatchShell(Shell shell)
    {
        if (TITLE_EN.equals(shell.getText()))
            shell.setText(TITLE_RU);
        return patchControls(shell);
    }

    private static boolean patchControls(Control control)
    {
        boolean done = false;
        if (control instanceof Label label)
        {
            String text = label.getText();
            String translated = translateShownMessage(text);
            if (translated != null)
            {
                if (!translated.equals(text))
                    label.setText(translated);
                done = true;
            }
            else if (isAlreadyRussianMessage(text))
                done = true;
        }
        if (control instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
                done = patchControls(child) || done;
        }
        return done;
    }

    /** @return русский текст или {@code null}, если это не штатное английское сообщение */
    private static String translateShownMessage(String text)
    {
        if (text == null || text.isBlank())
            return null;
        int optionalAt = text.indexOf(OPTIONAL_AFTER_NAME);
        if (text.startsWith("'") && optionalAt > 1 && text.contains(OPTIONAL_SNIPPET)) //$NON-NLS-1$
        {
            String name = text.substring(1, optionalAt);
            return "'" + name + "'" + RU_OPTIONAL_TAIL; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (text.startsWith(SAVE_PREFIX) && text.endsWith(SAVE_SUFFIX)
            && text.length() > SAVE_PREFIX.length() + SAVE_SUFFIX.length())
        {
            String name = text.substring(SAVE_PREFIX.length(), text.length() - SAVE_SUFFIX.length());
            return "Сохранить '" + name + "'?"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    private static boolean isAlreadyRussianMessage(String text)
    {
        if (text == null || text.isBlank())
            return false;
        return text.contains("изменён, но всё ещё открыт в другом месте") //$NON-NLS-1$
            || (text.startsWith("Сохранить '") && text.endsWith("'?")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
