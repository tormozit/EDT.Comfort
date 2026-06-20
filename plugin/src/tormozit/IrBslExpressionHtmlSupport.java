package tormozit;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.swt.widgets.Display;

/**
 * COM-цепочка ИР для {@code ОписаниеХТМЛВыражения} в боковой подсказке и outline.
 */
public final class IrBslExpressionHtmlSupport
{
    public static final String KIND_METHOD = "Метод"; //$NON-NLS-1$
    public static final String KIND_PROPERTY = "Свойство"; //$NON-NLS-1$

    static final String CANCEL_FILE_PREFIX = "tormozit-ir-cancel-"; //$NON-NLS-1$
    static final String CANCEL_FILE_SUFFIX = ".tmp"; //$NON-NLS-1$

    static final ConcurrentHashMap<IRSession, AtomicReference<Path>> activeCancelFiles =
        new ConcurrentHashMap<>();

    private IrBslExpressionHtmlSupport() {}

    public static IRSession resolveConnectedSession(BslXtextEditor editor)
    {
        if (editor == null)
            return null;
        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        if (dtProject == null || !IRApplication.hasConnectedSessionForKeys(dtProject))
            return null;
        IRSession session = IRApplication.getSession(dtProject);
        if (session == null || session.executor == null || session.executor.isShutdown())
            return null;
        return session;
    }

    public static void ensureCodeEditor(IRSession session)
    {
        if (session == null || session.codeEditor != null)
            return;
        Object irCache = session.getModule("ирКэш"); //$NON-NLS-1$
        session.codeEditor = ComBridge.invoke(irCache, "ПолеТекстаПрограммы", 0); //$NON-NLS-1$
    }

    /**
     * COM на {@code session.executor}: apply подготовленного sync, контекст, {@code ОписаниеХТМЛВыражения}.
     */
    public static String fetchDescriptionHtmlAfterPreparedSync(
        IRSession session, IRSession.CodeEditorSyncPayload payload, String name, String kind)
    {
        if (session == null || name == null || name.isEmpty() || kind == null || kind.isEmpty())
            return null;
        try
        {
            if (payload != null)
                session.applyPreparedCodeEditorSync(payload);
            ensureCodeEditor(session);
            ComBridge.invoke(session.codeEditor, "РазобратьТекущийКонтекст"); //$NON-NLS-1$
            ComBridge.invoke(session.codeEditor, "ЗаполнитьТаблицуСлов"); //$NON-NLS-1$
            return invokeDescriptionHtmlWithCancellation(session, name, kind);
        }
        catch (Exception e)
        {
            BslSideHintDebug.problem("ir fetch name=" + name + " kind=" + kind + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }
    }

    /**
     * Doc-hover: sync → {@code РазобратьТекущийКонтекст} → {@code ОписаниеХТМЛВыражения()}.
     */
    public static String fetchDescriptionHtmlForHover(
        IRSession session, IRSession.CodeEditorSyncPayload payload)
    {
        if (session == null || payload == null)
            return null;
        try
        {
            session.applyPreparedCodeEditorSync(payload);
            ensureCodeEditor(session);
            ComBridge.invoke(session.codeEditor, "РазобратьТекущийКонтекст"); //$NON-NLS-1$
            String html = invokeDescriptionHtmlWithCancellation(session);
            if (html == null || html.isBlank())
            {
                IrBslHoverDebug.step("fetch", "пустой ответ offset=" + payload.offset); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }
            IrBslHoverDebug.log("fetch offset=" + payload.offset + " len=" + html.length()); //$NON-NLS-1$ //$NON-NLS-2$
            return html;
        }
        catch (Exception e)
        {
            IrBslHoverDebug.problem("fetch offset=" + payload.offset + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /**
     * @return HTML-фрагмент ИР или {@code null}
     */
    public static String fetchDescriptionHtml(IRSession session, String name, String kind)
    {
        if (session == null || name == null || name.isEmpty() || kind == null || kind.isEmpty())
            return null;
        try
        {
            ensureCodeEditor(session);
            String html = invokeDescriptionHtmlWithCancellation(session, name, kind);
            if (html == null || html.isBlank())
            {
                BslSideHintDebug.step("ir fetch", "пустой ответ name=" + name + " kind=" + kind); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return null;
            }
            return html;
        }
        catch (Exception e)
        {
            BslSideHintDebug.problem("ir fetch name=" + name + " kind=" + kind + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }
    }

    private static String invokeDescriptionHtmlWithCancellation(IRSession session, Object... args)
    {
        Object codeEditor = session.codeEditor;
        Path cancelFile = null;
        try
        {
            cancelFile = IRSession.setEvaluationCancellationFile(session, codeEditor);
            Object raw = ComBridge.invoke(codeEditor, "ОписаниеХТМЛВыражения", args); //$NON-NLS-1$
            return ComBridge.toString(raw);
        }
        catch (IOException e)
        {
            Global.log(e.getMessage());
            return "";
        }
        finally
        {
            IRSession.clearEvaluationCancellationFile(session, codeEditor, cancelFile);
        }
    }

    /**
     * Sync на вызывающем потоке; COM — на {@code session.executor}.
     *
     * @param onReady UI-thread callback после {@code ЗаполнитьТаблицуСлов}
     */
    public static void prepareWordsTableAsync(
        IRSession session, BslXtextEditor editor, int caretOffset, Runnable onReady)
    {
        if (session == null || editor == null || session.executor == null || session.executor.isShutdown())
            return;
        try
        {
            session.syncCodeEditorToIR(editor, caretOffset);
        }
        catch (Exception e)
        {
            BslSideHintDebug.problem("wordsTable sync: " + e.getMessage()); //$NON-NLS-1$
            return;
        }
        session.executor.submit(() -> {
            try
            {
                ensureCodeEditor(session);
                ComBridge.invoke(session.codeEditor, "РазобратьТекущийКонтекст"); //$NON-NLS-1$
                ComBridge.invoke(session.codeEditor, "ЗаполнитьТаблицуСлов"); //$NON-NLS-1$
                Display display = Display.getDefault();
                if (display != null && !display.isDisposed())
                    display.asyncExec(onReady);
            }
            catch (Exception e)
            {
                BslSideHintDebug.problem("wordsTable: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }
}
