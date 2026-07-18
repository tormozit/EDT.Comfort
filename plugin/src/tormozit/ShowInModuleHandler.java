package tormozit;

import org.eclipse.core.resources.IFile;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;

/**
 * Команда «Показать в модуле» — открывает реальный редактор BSL-модуля
 * (не виртуальную панель сравнения) на строке, соответствующей активной
 * позиции в поле сравнения ({@link CompareCurrentLinesPanel}).
 *
 * <p>Тонкая обёртка над {@link GoToDefinition#openFileAtLine} — там уже есть
 * вся нужная логика открытия {@link IFile} и перевода каретки на строку.
 */
public final class ShowInModuleHandler
{
    public static final String MENU_LABEL = "Показать в модуле"; //$NON-NLS-1$
    public static final String TOOLTIP = "Открыть строку сравнения в реальном редакторе модуля"; //$NON-NLS-1$

    private ShowInModuleHandler()
    {
    }

    /** @param line1Based строка, нумерация с 1 (как в {@link GoToDefinition}); {@code <= 0} — без перехода на строку. */
    public static void open(IFile file, int line1Based, IWorkbenchPage page, Shell shell)
    {
        if (file == null || !file.exists())
        {
            log("open: файл не найден"); //$NON-NLS-1$
            return;
        }
        if (!GoToDefinition.openFileAtLine(file, line1Based, page, shell))
            log("open: не удалось открыть " + file.getFullPath()); //$NON-NLS-1$
    }

    private static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log("ShowInModuleHandler", msg); //$NON-NLS-1$
    }
}
