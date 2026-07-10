package tormozit;

import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Порт {@code НайтиВМодулях()} из RDT: поиск ссылок на слово или объект метаданных через приложение ИР.
 */
public final class IrFindReferencesHandler
{
    public static final String COMMAND_ID = "tormozit.IrFindReferences"; //$NON-NLS-1$
    static final String MENU_LABEL = "Найти ссылки ИР"; //$NON-NLS-1$
    private IrFindReferencesHandler() {}
    public static void findInModules(BslXtextEditor editor)
    {
        if (editor == null)
        {
            ToastNotification.show(MENU_LABEL, "Откройте модуль BSL и повторите команду", 3000); //$NON-NLS-1$
            return;
        }

        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        if (dtProject == null)
            return;

        IRSession irSession = IRApplication.getSession(dtProject);
        if (irSession == null || irSession.executor == null)
            return;
        irSession.syncCodeEditorToIR(editor);
        irSession.executor.submit(() ->
            findInModulesOnComThread(irSession));
    }

    /** COM-поток {@link IRSession#executor}; sync уже выполнен через {@link IRSession#syncCodeEditorToIR}. */
    private static void findInModulesOnComThread(
        IRSession irSession)
    {
        try
        {
            irSession.invokeCodeEditor("РазобратьТекущийКонтекст"); //$NON-NLS-1$
            irSession.showWindow();
//            Функция ОткрытьПоискВМодулях(Знач ЧтоИскать = Неопределено, Знач Автозапуск = Истина) Экспорт
            irSession.invokeCodeEditor("ОткрытьПоискВМодулях", null, false);
        }
        catch (Exception e)
        {
            Global.log("IrFindReferencesHandler: " + e.getMessage()); //$NON-NLS-1$
            ToastNotification.show(MENU_LABEL, "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$
        }
    }

}
