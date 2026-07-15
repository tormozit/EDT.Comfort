package tormozit;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Порт {@code ФорматироватьТекст()} из RDT: форматирование BSL и текста запроса через приложение ИР.
 */
public final class IrFormatTextHandler
{
    static final String MENU_LABEL = "Форматировать текст ИР"; //$NON-NLS-1$

    private IrFormatTextHandler() {}

    public static boolean isApplicableBsl(BslXtextEditor editor)
    {
        if (editor == null)
            return false;
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null)
            return false;
        Object sel = viewer.getSelectionProvider().getSelection();
        return sel instanceof ITextSelection textSel && textSel.getLength() > 0;
    }

    public static boolean isApplicableQuery(ISourceViewer viewer)
    {
        if (viewer == null || viewer.getDocument() == null)
            return false;
        return viewer.getTextWidget() != null && !viewer.getTextWidget().isDisposed();
    }

    public static void formatBslModule(BslXtextEditor editor)
    {
        if (editor == null)
        {
            toast(MENU_LABEL, "Откройте модуль BSL и выделите фрагмент"); //$NON-NLS-1$
            return;
        }
        if (!isApplicableBsl(editor))
        {
            toast(MENU_LABEL, "Необходимо выделить блок текста"); //$NON-NLS-1$
            return;
        }

        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        if (dtProject == null)
            return;

        IRSession irSession = IRApplication.getSession(dtProject, true);
        if (irSession == null || irSession.executor == null)
            return;

        irSession.syncCodeEditorToIR(editor);
        irSession.executor.submit(() -> formatBslOnComThread(irSession, editor));
    }

    /** COM-поток {@link IRSession#executor}: без вложенного {@link IRSession#executeOnComThread}. */
    private static void formatBslOnComThread(IRSession irSession, BslXtextEditor editor)
    {
        try
        {
            ensureCodeEditor(irSession);
            irSession.invokeCodeEditor("РазобратьТекущийКонтекст"); //$NON-NLS-1$
            long language = ComBridge.toLong(
                ComBridge.getProperty(irSession.codeEditor, "мЯзыкПрограммы")); //$NON-NLS-1$
            if (language != 0)
            {
                toast(MENU_LABEL, "Команда доступна только в модуле"); //$NON-NLS-1$
                return;
            }

            boolean formatted = ComBridge.toBoolean(
                irSession.invokeCodeEditor("ФорматироватьТекстВстроенногоЯзыка", false)); //$NON-NLS-1$
            if (!formatted)
                return;

            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> irSession.syncCodeEditorFromIR(editor));
        }
        catch (Exception e)
        {
            Global.log("IrFormatTextHandler: " + e.getMessage()); //$NON-NLS-1$
            toast(MENU_LABEL, "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$
        }
    }

    public static void formatQuery(ISourceViewer viewer, Object queryDialog)
    {
        if (!isApplicableQuery(viewer))
            return;

        IDtProject dtProject = resolveDtProjectForQuery(queryDialog);
        if (dtProject == null)
        {
            toast(MENU_LABEL, "Не удалось определить проект EDT"); //$NON-NLS-1$
            return;
        }

        IRSession irSession = IRApplication.getSession(dtProject, true);
        if (irSession == null || irSession.executor == null)
            return;

        irSession.syncQueryEditorToIR(viewer);
        irSession.executor.submit(() -> formatQueryOnComThread(irSession, viewer));
    }

    private static void formatQueryOnComThread(IRSession irSession, ISourceViewer viewer)
    {
        try
        {
            ensureCodeEditor(irSession);
            boolean formatted = ComBridge.toBoolean(
                irSession.invokeCodeEditor("ФорматироватьТекстЯзыкаЗапросов")); //$NON-NLS-1$
            if (!formatted)
                return;

            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> irSession.syncQueryEditorFromIR(viewer));
        }
        catch (Exception e)
        {
            Global.log("IrFormatTextHandler: " + e.getMessage()); //$NON-NLS-1$
            toast(MENU_LABEL, "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$
        }
    }

    static IDtProject resolveDtProjectForQuery(Object queryDialog)
    {
        if (queryDialog != null)
        {
            Object project = Global.getField(queryDialog, "project"); //$NON-NLS-1$
            if (project instanceof IDtProject dtProject)
                return dtProject;
        }

        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;
            IEditorPart editor = page.getActiveEditor();
            if (editor == null)
                return null;

            BslXtextEditor bsl = GetRef.getActiveBslEditor(editor);
            if (bsl != null)
                return Global.getDtProjectFromBslEditor(bsl);

            IEditorInput input = editor.getEditorInput();
            if (input != null)
            {
                IFile file = input.getAdapter(IFile.class);
                if (file != null)
                    return Global.getDtProjectFromWorkspaceProject(file.getProject());
            }
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    private static void ensureCodeEditor(IRSession irSession)
    {
        if (irSession.codeEditor != null)
            return;
        Object irCache = irSession.getModule("ирКэш"); //$NON-NLS-1$
        irSession.codeEditor = ComBridge.invoke(irCache, "ПолеТекстаПрограммы", 0); //$NON-NLS-1$
    }

    private static void toast(String title, String message)
    {
        toast(title, message, 3_000);
    }

    private static void toast(String title, String message, int durationMs)
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> ToastNotification.show(title, message, durationMs));
    }
}

