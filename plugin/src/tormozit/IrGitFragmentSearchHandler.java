package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Команда «Найти фрагмент в Гите ИР»: выделенный в модуле текст ищется в истории Git через приложение ИР.
 * Без выделения {@code ЧтоИскать} пуст — передаются только модуль и строка каретки.
 *
 * <p>Сигнатура метода 1С:
 * <pre>
 * ирКлиент.ОткрытьИсториюГитаЛкс(Знач ЧтоИскать = "", Знач ИмяМодуля = "", Знач Коммит = "", Знач НомерСтроки = 0)
 * </pre>
 */
public final class IrGitFragmentSearchHandler extends AbstractHandler
{
    public static final String COMMAND_ID = "tormozit.IrGitFragmentSearch"; //$NON-NLS-1$
    public static final String BINDING_CONTEXT_ID = "org.eclipse.xtext.ui.XtextEditorScope"; //$NON-NLS-1$
    static final String MENU_LABEL = "Найти фрагмент в Гите ИР"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        BslXtextEditor editor = resolveBslEditor(HandlerUtil.getActivePart(event));
        if (editor == null)
            editor = resolveBslEditor(HandlerUtil.getActiveEditor(event));
        findFragment(editor);
        return null;
    }

    private static BslXtextEditor resolveBslEditor(IWorkbenchPart part)
    {
        if (part instanceof IEditorPart editorPart)
            return GetRef.getActiveBslEditor(editorPart);
        return GetRef.getActiveBslEditor(part);
    }

    /** Подключён ли проект модуля к Git — без репозитория команда в меню не добавляется. */
    static boolean isGitProject(BslXtextEditor editor)
    {
        IDtProject dtProject = editor != null ? Global.getDtProjectFromBslEditor(editor) : null;
        IProject project = dtProject != null ? dtProject.getWorkspaceProject() : null;
        if (project == null)
            return false;
        RepositoryMapping mapping = RepositoryMapping.getMapping(project);
        return mapping != null && mapping.getRepository() != null;
    }

    public static void findFragment(BslXtextEditor editor)
    {
        if (editor == null)
        {
            ToastNotification.show(MENU_LABEL, "Откройте модуль BSL и повторите команду", 3000); //$NON-NLS-1$
            return;
        }

        if (!isGitProject(editor))
        {
            ToastNotification.show(MENU_LABEL, "Проект не подключён к Git", 3000); //$NON-NLS-1$
            return;
        }

        // Пустое выделение допустимо: ИР получает пустой ЧтоИскать и строку каретки.
        ITextSelection selection = textSelection(editor);
        String text = selection != null && selection.getText() != null ? selection.getText() : ""; //$NON-NLS-1$

        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        if (dtProject == null)
            return;

        IRSession irSession = IRApplication.getSession(dtProject, true);
        if (irSession == null || irSession.executor == null)
            return;

        // Текст в ИР — с переводами строк LF, как и весь модуль после синхронизации.
        String fragment = text.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        String moduleName = GetRef.resolveSetTextModuleName(editor);
        String finalModuleName = moduleName != null ? moduleName : ""; //$NON-NLS-1$
        // ITextSelection редактора — координаты документа (не виджета), строка с нуля.
        int lineNumber = selection != null ? selection.getStartLine() + 1 : 0;

        irSession.executor.submit(() ->
        {
            try
            {
                Object irClient = irSession.getModule("ирКлиент"); //$NON-NLS-1$
                irSession.showWindow();
                ComBridge.invoke(irClient, "ОткрытьИсториюГитаЛкс", //$NON-NLS-1$
                    fragment, finalModuleName, "", lineNumber); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                Global.log("IrGitFragmentSearchHandler: " + e.getMessage()); //$NON-NLS-1$
                ToastNotification.show(MENU_LABEL, "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$
            }
        });
    }

    private static ITextSelection textSelection(BslXtextEditor editor)
    {
        if (editor == null)
            return null;
        ISelectionProvider provider = editor.getSelectionProvider();
        if (provider == null)
            return null;
        ISelection selection = provider.getSelection();
        return selection instanceof ITextSelection ts ? ts : null;
    }
}
