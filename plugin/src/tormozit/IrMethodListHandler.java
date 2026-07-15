package tormozit;

import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Порт {@code ОткрытьСписокМетодов} из RDT: вызов списка методов ИР из Quick Outline.
 */
public final class IrMethodListHandler
{
    private IrMethodListHandler() {}

    public static void openCommonMethods(BslXtextEditor editor, String searchText)
    {
        openMethodList(editor, searchText, false);
    }

    public static void openModuleMethods(BslXtextEditor editor, String searchText)
    {
        openMethodList(editor, searchText, true);
    }

    public static BslXtextEditor resolveBslEditor(Object outlineDialog)
    {
        if (outlineDialog != null)
        {
            Object host = Global.getField(outlineDialog, "host"); //$NON-NLS-1$
            if (host != null)
            {
                Object editorPart = Global.invoke(host, "getEditorPart"); //$NON-NLS-1$
                if (editorPart == null)
                    editorPart = Global.getField(host, "editor"); //$NON-NLS-1$
                if (editorPart instanceof IEditorPart part)
                {
                    BslXtextEditor bsl = GetRef.getActiveBslEditor(part);
                    if (bsl != null)
                        return bsl;
                }
            }
        }
        return activeBslEditor();
    }

    private static void openMethodList(BslXtextEditor editor, String searchText, boolean onlyThisModule)
    {
        if (editor == null)
        {
            toast("Список методов", "Откройте модуль BSL и повторите команду"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        if (dtProject == null)
            return;

        IRSession irSession = IRApplication.getSession(dtProject, true);
        if (irSession == null || irSession.executor == null)
            return;

        String pattern = searchText != null ? searchText : ""; //$NON-NLS-1$
        if (pattern.isEmpty())
            pattern = selectedText(editor);

        final String query = pattern;
        IRSession.CodeEditorSyncPayload payload = irSession.prepareCodeEditorSyncFromEditor(editor);
        if (payload == null)
            return;

        IRSession.cancelActiveEvaluation(irSession);
        irSession.executor.submit(() -> openMethodListOnComThread(irSession, query, onlyThisModule, payload));
    }

    /** COM-поток {@link IRSession#executor}: без {@link IRSession#executeOnComThread} с UI. */
    private static void openMethodListOnComThread(
        IRSession irSession, String query, boolean onlyThisModule, IRSession.CodeEditorSyncPayload payload)
    {
        try
        {
            irSession.applyPreparedCodeEditorSync(payload);
            ensureCodeEditor(irSession);
            irSession.invokeCodeEditor("РазобратьТекущийКонтекст"); //$NON-NLS-1$
            long language = ComBridge.toLong(ComBridge.getProperty(irSession.codeEditor, "мЯзыкПрограммы")); //$NON-NLS-1$
            if (language != 0)
            {
                toast("Список методов", "Команда доступна только в модуле"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            irSession.invokeCodeEditor("ОткрытьСписокМетодов", query, onlyThisModule); //$NON-NLS-1$
            irSession.showWindow();
        }
        catch (Exception e)
        {
            Global.log("IrMethodListHandler: " + e.getMessage()); //$NON-NLS-1$
            toast("Список методов", "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static BslXtextEditor activeBslEditor()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;
            return GetRef.getActiveBslEditor(page.getActiveEditor());
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static String selectedText(BslXtextEditor editor)
    {
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null)
            return ""; //$NON-NLS-1$
        Object sel = viewer.getSelectionProvider().getSelection();
        if (!(sel instanceof ITextSelection textSel) || textSel.getLength() == 0)
            return ""; //$NON-NLS-1$
        return textSel.getText();
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
