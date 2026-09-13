package tormozit;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISaveablesLifecycleListener;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.Saveable;

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
        logWhoAsks(shell);
        schedulePatch(display, shell, 0);
    }

    /**
     * Безусловная запись: кто и почему показывает этот диалог. «Двойная
     * модифицированность» после работы в диалоге точки останова воспроизводится не всегда,
     * а по стеку показа видно и инициатора (закрытие редактора, сохранение перед запуском,
     * страница свойств), и имя ресурса.
     */
    private static void logWhoAsks(Shell shell)
    {
        StringBuilder sb = new StringBuilder("show"); //$NON-NLS-1$
        try
        {
            sb.append(" title=").append(shell.getText()); //$NON-NLS-1$
            String message = findMessageText(shell);
            if (message != null)
                sb.append(" message=").append(message.replace('\n', ' ')); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
        }
        appendSaveablesState(sb);
        sb.append("\nstack:"); //$NON-NLS-1$
        for (StackTraceElement frame : new Throwable().getStackTrace())
        {
            String className = frame.getClassName();
            if (className.startsWith("java.") || className.startsWith("jdk.") //$NON-NLS-1$ //$NON-NLS-2$
                || className.startsWith("sun.") //$NON-NLS-1$
                || className.startsWith("tormozit.SaveResourceDialogHook")) //$NON-NLS-1$
                continue;
            sb.append("\n  ").append(className).append('.').append(frame.getMethodName()) //$NON-NLS-1$
                .append(':').append(frame.getLineNumber());
        }
        Global.tempLog("save-resource", sb.toString()); //$NON-NLS-1$
    }

    /**
     * Кто держит изменённые {@code Saveable} в момент показа диалога.
     * <p>
     * Текст «изменён, но всё ещё открыт в другом месте» штатный
     * {@code SaveablesList.promptForSavingIfNecessary} показывает ровно для тех
     * изменённых моделей, чей счётчик ссылок не обнуляется закрываемыми частями. Значит,
     * держатель есть ещё один — вторая часть в {@code modelMap} или регистрация в
     * {@code nonPartSources}. По самому диалогу этого не видно, поэтому печатаем карту
     * целиком: имя модели, счётчик и все источники (класс части, заголовок, класс входа).
     */
    private static void appendSaveablesState(StringBuilder sb)
    {
        try
        {
            Object list = PlatformUI.getWorkbench().getService(ISaveablesLifecycleListener.class);
            if (list == null)
            {
                sb.append("\nsaveables: сервис недоступен"); //$NON-NLS-1$
                return;
            }
            sb.append("\nsaveables: ").append(list.getClass().getName()); //$NON-NLS-1$
            appendRefCounts(sb, Global.getField(list, "modelRefCounts")); //$NON-NLS-1$
            appendModelMap(sb, Global.getField(list, "modelMap")); //$NON-NLS-1$
            appendNonPartSources(sb, Global.getField(list, "nonPartSources")); //$NON-NLS-1$
        }
        catch (Exception | LinkageError ex)
        {
            sb.append("\nsaveables: ошибка дампа ").append(ex); //$NON-NLS-1$
        }
    }

    private static void appendRefCounts(StringBuilder sb, Object refCounts)
    {
        if (!(refCounts instanceof Map<?, ?> map))
        {
            sb.append("\n  modelRefCounts: недоступно"); //$NON-NLS-1$
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            sb.append("\n  refCount=").append(entry.getValue()) //$NON-NLS-1$
                .append(' ').append(describeSaveable(entry.getKey()));
        }
    }

    private static void appendModelMap(StringBuilder sb, Object modelMap)
    {
        if (!(modelMap instanceof Map<?, ?> map))
        {
            sb.append("\n  modelMap: недоступно"); //$NON-NLS-1$
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            sb.append("\n  источник ").append(describeSource(entry.getKey())); //$NON-NLS-1$
            if (entry.getValue() instanceof Collection<?> models)
            {
                for (Object model : models)
                    sb.append("\n    держит ").append(describeSaveable(model)); //$NON-NLS-1$
            }
        }
    }

    private static void appendNonPartSources(StringBuilder sb, Object sources)
    {
        if (!(sources instanceof Collection<?> collection))
        {
            sb.append("\n  nonPartSources: недоступно"); //$NON-NLS-1$
            return;
        }
        if (collection.isEmpty())
        {
            sb.append("\n  nonPartSources: нет"); //$NON-NLS-1$
            return;
        }
        for (Object source : collection)
            sb.append("\n  nonPartSource ").append(describeSource(source)); //$NON-NLS-1$
    }

    private static String describeSaveable(Object model)
    {
        if (!(model instanceof Saveable saveable))
            return String.valueOf(model);
        StringBuilder sb = new StringBuilder();
        sb.append('\'').append(saveable.getName()).append('\'');
        try
        {
            sb.append(" dirty=").append(saveable.isDirty()); //$NON-NLS-1$
        }
        catch (Exception | LinkageError ex)
        {
            sb.append(" dirty=?"); //$NON-NLS-1$
        }
        sb.append(" [").append(saveable.getClass().getName()) //$NON-NLS-1$
            .append('@').append(Integer.toHexString(System.identityHashCode(saveable))).append(']');
        return sb.toString();
    }

    private static String describeSource(Object source)
    {
        if (source == null)
            return "null"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder(source.getClass().getName());
        sb.append('@').append(Integer.toHexString(System.identityHashCode(source)));
        if (source instanceof IWorkbenchPart part)
        {
            sb.append(" title='").append(part.getTitle()).append('\''); //$NON-NLS-1$
            if (part instanceof IEditorPart editor)
            {
                Object input = editor.getEditorInput();
                sb.append(" input=").append(input == null ? "null" : input.getClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$
                if (input != null)
                    sb.append(" inputName='").append(editor.getEditorInput().getName()).append('\''); //$NON-NLS-1$
            }
        }
        return sb.toString();
    }

    /** Первый непустой текст {@link Label} в диалоге — его сообщение. */
    private static String findMessageText(Composite parent)
    {
        for (Control child : parent.getChildren())
        {
            if (child.isDisposed())
                continue;
            if (child instanceof Label label)
            {
                String text = label.getText();
                if (text != null && !text.isBlank())
                    return text;
            }
            if (child instanceof Composite composite)
            {
                String nested = findMessageText(composite);
                if (nested != null)
                    return nested;
            }
        }
        return null;
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
