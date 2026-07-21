package tormozit;

import java.util.regex.Pattern;

import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Порт {@code КонструкторЗапроса()} из RDT: конструктор запроса приложения ИР
 * для текста запроса во вложенном строковом литерале BSL.
 *
 * <p>Доступность — как у «Вложенный текст ИР»
 * ({@link EditEmbeddedTextHandler#isApplicable(BslXtextEditor)}): курсор должен
 * находиться внутри строкового литерала BSL.
 */
public final class IrQueryConstructorHandler
{
    public static final String COMMAND_ID = "tormozit.IrQueryConstructor"; //$NON-NLS-1$
    static final String MENU_LABEL = "Конструктор запроса ИР"; //$NON-NLS-1$

    private static final Pattern QUERY_CONSTRUCTOR_TITLE =
        Pattern.compile("^Конструктор запрос.*"); //$NON-NLS-1$
    private static final long DIALOG_WAIT_MS = 2_000;

    private IrQueryConstructorHandler() {}

    public static boolean isApplicable(BslXtextEditor editor)
    {
        return editor != null && EditEmbeddedTextHandler.isApplicable(editor);
    }

    public static void openQueryConstructor(BslXtextEditor editor)
    {
        if (editor == null)
        {
            toast(MENU_LABEL, "Откройте модуль BSL и повторите команду"); //$NON-NLS-1$
            return;
        }

        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        if (dtProject == null)
            return;

        IRSession irSession = IRApplication.getSession(dtProject, true);
        if (irSession == null || irSession.executor == null)
            return;

        irSession.syncCodeEditorToIR(editor);
        irSession.executor.submit(() -> openOnComThread(irSession, editor));
    }

    /** COM-поток {@link IRSession#executor}. */
    private static void openOnComThread(IRSession irSession, BslXtextEditor editor)
    {
        try
        {
            ensureCodeEditor(irSession);
            irSession.invokeCodeEditor("КончитьОбработкуКоманды"); //$NON-NLS-1$
            irSession.invokeCodeEditor("РазобратьТекущийКонтекст"); //$NON-NLS-1$

            Object result = irSession.runIrModalDialog(QUERY_CONSTRUCTOR_TITLE, DIALOG_WAIT_MS,
                () -> irSession.invokeCodeEditor("ОткрытьКонструкторЗапроса")); //$NON-NLS-1$

            if (IRApplication.isCancelled(result))
                return;

            boolean changed = ComBridge.toBoolean(result);
            if (!changed)
                return;

            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> irSession.syncCodeEditorFromIR(editor));
        }
        catch (Exception e)
        {
            Global.log("IrQueryConstructorHandler: " + e.getMessage()); //$NON-NLS-1$
            toast(MENU_LABEL, "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$
        }
    }

    // =========================================================================
    // Режим языка запросов: модальный «Редактор запроса» / «Динамический список»
    // =========================================================================

    public static boolean isApplicableQuery(ISourceViewer viewer)
    {
        return IrFormatTextHandler.isApplicableQuery(viewer);
    }

    public static void openQueryConstructor(ISourceViewer viewer, Object queryDialog)
    {
        if (!isApplicableQuery(viewer))
            return;

        IDtProject dtProject = IrFormatTextHandler.resolveDtProjectForQuery(queryDialog);
        if (dtProject == null)
        {
            toast(MENU_LABEL, "Не удалось определить проект EDT"); //$NON-NLS-1$
            return;
        }

        IRSession irSession = IRApplication.getSession(dtProject, true);
        if (irSession == null || irSession.executor == null)
            return;

        irSession.syncQueryEditorToIR(viewer);
        irSession.executor.submit(() -> openQueryOnComThread(irSession, viewer));
    }

    /** COM-поток {@link IRSession#executor}. Текст запроса уже в режиме «ЯзыкПрограммы=1». */
    private static void openQueryOnComThread(IRSession irSession, ISourceViewer viewer)
    {
        try
        {
            ensureCodeEditor(irSession);

            Object result = irSession.runIrModalDialog(QUERY_CONSTRUCTOR_TITLE, DIALOG_WAIT_MS,
                () -> irSession.invokeCodeEditor("ОткрытьКонструкторЗапроса")); //$NON-NLS-1$

            if (IRApplication.isCancelled(result))
                return;

            boolean changed = ComBridge.toBoolean(result);
            if (!changed)
                return;

            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> irSession.syncQueryEditorFromIR(viewer));
        }
        catch (Exception e)
        {
            Global.log("IrQueryConstructorHandler: " + e.getMessage()); //$NON-NLS-1$
            toast(MENU_LABEL, "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$
        }
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
