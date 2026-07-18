package tormozit;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

/**
 * Диалоги подтверждения закрытия модального окна сравнения/слияния перед
 * переходом в реальный редактор модуля ({@link ShowInModuleHandler}) —
 * модальность блокирует переключение на другой редактор, пока окно открыто.
 */
public final class ModalSaveCloseHelper
{
    private static final String TITLE = "Комфорт"; //$NON-NLS-1$

    private static final String[] PROCEED_LABELS = { "Да", "Отмена" }; //$NON-NLS-1$ //$NON-NLS-2$
    private static final String[] SAVE_LABELS = { "Сохранить", "Не сохранять", "Отмена" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    public enum Choice
    {
        PROCEED, CANCEL
    }

    public enum SaveChoice
    {
        SAVE, DISCARD, CANCEL
    }

    private ModalSaveCloseHelper()
    {
    }

    /** Диалог «Да/Отмена» — для окон без осмысленного варианта «без сохранения» (см. {@link SaveChoice}). */
    public static Choice confirmClose(Shell shell, String message)
    {
        int index = new MessageDialog(shell, TITLE, null, message,
            MessageDialog.QUESTION, 1, PROCEED_LABELS).open();
        return index == 0 ? Choice.PROCEED : Choice.CANCEL;
    }

    /** Диалог «Сохранить/Не сохранять/Отмена» — для окон, где оба варианта закрытия осмысленны. */
    public static SaveChoice confirmSaveClose(Shell shell, String message)
    {
        int index = new MessageDialog(shell, TITLE, null, message,
            MessageDialog.QUESTION, 2, SAVE_LABELS).open();
        return switch (index)
        {
            case 0 -> SaveChoice.SAVE;
            case 1 -> SaveChoice.DISCARD;
            default -> SaveChoice.CANCEL;
        };
    }
}
