package tormozit;

import java.net.URL;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.osgi.framework.Bundle;

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

    /** Штатная иконка модуля BSL — тот же бандл, что и у самого редактора модулей. */
    private static final String BUNDLE_BSL_UI = "com._1c.g5.v8.dt.bsl.ui"; //$NON-NLS-1$
    private static final String ICON_MODULE = "icons/obj16/module.png"; //$NON-NLS-1$

    private ShowInModuleHandler()
    {
    }

    /**
     * Иконка кнопки — {@code null}, если бандл/файл иконки не нашёлся (иконка опциональна,
     * вызывающая сторона в этом случае оставляет текстовую подпись как запасной вариант).
     */
    public static ImageDescriptor iconDescriptor()
    {
        try
        {
            Bundle bundle = Platform.getBundle(BUNDLE_BSL_UI);
            if (bundle == null)
                return null;
            URL url = bundle.getEntry(ICON_MODULE);
            return url != null ? ImageDescriptor.createFromURL(url) : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
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

    /**
     * Как {@link #open}, но через штатный EDT-механизм открытия модуля ({@code OpenHelper} —
     * тот же, что и «Перейти к определению») — используется для диалогов слияния модулей,
     * где обычный {@code IDE.openEditor} приводил к задвоению видимых строк в редакторе.
     */
    public static void openBslModule(IFile file, int line1Based, IWorkbenchPage page, Shell shell)
    {
        if (file == null || !file.exists())
        {
            log("openBslModule: файл не найден"); //$NON-NLS-1$
            return;
        }
        if (!GoToDefinition.openBslModuleAtLine(file, line1Based, page, shell))
            log("openBslModule: не удалось открыть " + file.getFullPath()); //$NON-NLS-1$
    }

    private static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log("ShowInModuleHandler", msg); //$NON-NLS-1$
    }
}
