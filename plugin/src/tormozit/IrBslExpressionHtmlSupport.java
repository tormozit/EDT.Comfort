package tormozit;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.text.source.SourceViewer;
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

    /** Сессия ИР для произвольного {@link TextEditorFacade} (BSL или query). */
    public static IRSession resolveConnectedSession(TextEditorFacade facade)
    {
        if (facade == null)
            return null;
        if (!facade.isQueryMode() && facade instanceof BslTextEditorFacade bf)
            return resolveConnectedSession(bf.getBslEditor());
        IDtProject dtProject = facade.getDtProject();
        if (dtProject == null || !IRApplication.hasConnectedSessionForKeys(dtProject))
            return null;
        IRSession session = IRApplication.getSession(dtProject);
        if (session == null || session.executor == null || session.executor.isShutdown())
            return null;
        return session;
    }

    /** Сессия ИР для assist: штатный путь + {@link IRApplication#getConnectedSession}. */
    public static IRSession resolveIrSessionForAssist(BslXtextEditor editor, SourceViewer viewer)
    {
        IRSession session = resolveConnectedSession(editor);
        if (session != null)
            return session;
        IDtProject dtProject = editor != null ? Global.getDtProjectFromBslEditor(editor) : null;
        if (dtProject != null)
        {
            session = IRApplication.getConnectedSession(dtProject);
            if (session != null && session.executor != null && !session.executor.isShutdown())
                return session;
        }
        return null;
    }

    /** Сессия ИР для assist через {@link TextEditorFacade}. */
    public static IRSession resolveIrSessionForAssist(TextEditorFacade facade, SourceViewer viewer)
    {
        if (facade == null)
            return null;
        IRSession session = resolveConnectedSession(facade);
        if (session != null)
            return session;
        IDtProject dtProject = facade.getDtProject();
        if (dtProject != null)
        {
            session = IRApplication.getConnectedSession(dtProject);
            if (session != null && session.executor != null && !session.executor.isShutdown())
                return session;
        }
        return null;
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
            session.invokeCodeEditor("РазобратьТекущийКонтекст"); //$NON-NLS-1$
            session.invokeCodeEditor("ЗаполнитьТаблицуСлов"); //$NON-NLS-1$
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
            session.invokeCodeEditor("РазобратьТекущийКонтекст"); //$NON-NLS-1$
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
            Object raw = session.invokeCodeEditor("ОписаниеХТМЛВыражения", args); //$NON-NLS-1$
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
     * @param onReady UI-thread callback после цепочки RDT (таблица слов + наборы)
     */
    public static void prepareWordsTableAsync(
        IRSession session, BslXtextEditor editor, int caretOffset, Runnable onReady)
    {
        IrBslCompletionSupport.prepareAssistContextAsync(session, editor, caretOffset, false,
            snapshot -> {
                if (onReady != null)
                    onReady.run();
            });
    }
}
