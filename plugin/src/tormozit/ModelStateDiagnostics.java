package tormozit;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Widget;

import com._1c.g5.v8.bm.core.IBmObject;

/**
 * Временная диагностика #1045: в штатном пути только короткая история событий в памяти.
 * Каждая ошибка безусловно пишется отдельной строкой, без текста модулей, чтения AST и пересчётов.
 */
final class ModelStateDiagnostics
{
    /** Восемь пар before/after; значения не держат документ или объекты его модели. */
    private static final int HISTORY_LIMIT = 16;
    private static final Map<IDocument, ArrayDeque<String>> CHANGES = new WeakHashMap<>();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static volatile WeakReference<IDocument> lastEdited = new WeakReference<>(null);

    private ModelStateDiagnostics() {}

    /** Штатные ошибки проекции могут не проходить через методы Комфорта. */
    static void install(Display display)
    {
        ILogListener listener = (status, plugin) -> observeStatus(status);
        Platform.addLogListener(listener);
        display.disposeExec(() -> Platform.removeLogListener(listener));
        Global.tempLog("model-state", "installed history=16 stackFrames=6; eclipse document=lastEditedCandidate"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void observeStatus(IStatus status)
    {
        Throwable error = status.getException();
        for (Throwable cause = error; cause != null; cause = cause.getCause())
        {
            boolean matched = false;
            for (StackTraceElement frame : cause.getStackTrace())
            {
                String name = frame.getClassName();
                if (name.startsWith("org.eclipse.jface.text.projection.") || name.contains(".bm.") //$NON-NLS-1$ //$NON-NLS-2$
                    || name.equals("org.eclipse.swt.custom.StyledText") //$NON-NLS-1$
                    || name.startsWith("com.e1c.v8codestyle.bsl.check.")) //$NON-NLS-1$
                {
                    matched = true;
                    break;
                }
            }
            if (matched)
            {
                // Последний изменённый документ — только кандидат, не установленный источник ошибки.
                failure("eclipse.lastEditedCandidate", error, lastEdited.get()); //$NON-NLS-1$
                break;
            }
        }
        for (IStatus child : status.getChildren())
            observeStatus(child);
    }

    static void documentEvent(DocumentEvent event, String phase)
    {
        IDocument document = event.getDocument();
        if (lastEdited.get() != document)
            lastEdited = new WeakReference<>(document);
        String change = phase + " ns=" + System.nanoTime() + " offset=" + event.getOffset() //$NON-NLS-1$ //$NON-NLS-2$
            + " removed=" + event.getLength() + " added=" + (event.getText() == null ? 0 : event.getText().length()) //$NON-NLS-1$ //$NON-NLS-2$
            + " length=" + document.getLength() + " stamp=" + stamp(document); //$NON-NLS-1$ //$NON-NLS-2$
        synchronized (CHANGES)
        {
            ArrayDeque<String> changes = CHANGES.computeIfAbsent(document, key -> new ArrayDeque<>());
            if (changes.size() == HISTORY_LIMIT)
                changes.removeFirst();
            changes.addLast(change);
        }
    }

    static void failure(String phase, Throwable failure, IDocument document, Object... context)
    {
        // Диагностика не подменяет исходное исключение ошибкой сбора контекста.
        try
        {
            StringBuilder text = new StringBuilder(512);
            text.append("event=").append(SEQUENCE.incrementAndGet()).append(" phase=").append(phase) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" errorId=").append(identity(failure)) //$NON-NLS-1$
                .append(" ns=").append(System.nanoTime()).append(" thread=") //$NON-NLS-1$ //$NON-NLS-2$
                .append(Thread.currentThread().getName()).append('#').append(Thread.currentThread().getId());
            if (document != null)
            {
                text.append(" document=").append(identity(document)); //$NON-NLS-1$
                try
                {
                    Object resource = Global.getField(document, "resource"); //$NON-NLS-1$
                    text.append(" resource=").append(describe(resource)); //$NON-NLS-1$
                    text.append(" length=").append(document.getLength()).append(" lines=") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(document.getNumberOfLines()).append(" stamp=").append(stamp(document)); //$NON-NLS-1$
                }
                catch (RuntimeException unavailable)
                {
                    text.append(" documentState=").append(unavailable.getClass().getSimpleName()); //$NON-NLS-1$
                }
                synchronized (CHANGES)
                {
                    ArrayDeque<String> changes = CHANGES.get(document);
                    text.append(" changes=").append(changes); //$NON-NLS-1$
                }
            }
            for (int i = 0; i < context.length; i++)
                text.append(" context").append(i).append('=').append(describe(context[i])); //$NON-NLS-1$
            // Ограничен размер записи, а не частота: каждое исключение попадает в файл.
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 4; depth++)
            {
                text.append(" error=").append(cause.getClass().getName()).append(':') //$NON-NLS-1$
                    .append(singleLine(cause.getMessage()));
                StackTraceElement[] stack = cause.getStackTrace();
                for (int i = 0; i < Math.min(6, stack.length); i++)
                    text.append(" <- ").append(stack[i]); //$NON-NLS-1$
                cause = cause.getCause();
            }
            Global.tempLog("model-state", text.toString()); //$NON-NLS-1$
        }
        catch (RuntimeException diagnosticFailure)
        {
            Global.tempLog("model-state", "phase=" + phase + " diagnosticFailure=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + diagnosticFailure.getClass().getName() + " original=" + failure.getClass().getName()); //$NON-NLS-1$
        }
    }

    private static long stamp(IDocument document)
    {
        return document instanceof IDocumentExtension4 extension ? extension.getModificationStamp() : -1;
    }

    private static String describe(Object object)
    {
        if (object == null || object instanceof Number || object instanceof Boolean)
            return String.valueOf(object);
        StringBuilder text = new StringBuilder(identity(object));
        try
        {
            if (object instanceof IBmObject bm)
                text.append(" bmId=").append(bm.bmGetId()); //$NON-NLS-1$
            if (object instanceof org.eclipse.emf.ecore.resource.Resource resource)
                text.append(" uri=").append(resource.getURI()); //$NON-NLS-1$
            if (object instanceof EObject model && !(object instanceof IBmObject))
            {
                // Не читаем source/container/типы и не разрешаем прокси ради диагностики.
                var resource = model.eResource();
                text.append(" resource=").append(identity(resource)); //$NON-NLS-1$
                if (resource != null)
                    text.append(" uri=").append(resource.getURI()); //$NON-NLS-1$
            }
            if (object instanceof Widget widget)
            {
                text.append(" disposed=").append(widget.isDisposed()); //$NON-NLS-1$
                if (!widget.isDisposed() && widget instanceof StyledText styled)
                    text.append(" chars=").append(styled.getCharCount()).append(" lines=") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(styled.getLineCount()).append(" caret=").append(styled.getCaretOffset()); //$NON-NLS-1$
            }
        }
        catch (RuntimeException unavailable)
        {
            text.append(" stateUnavailable=").append(unavailable.getClass().getSimpleName()); //$NON-NLS-1$
        }
        return text.toString();
    }

    private static String identity(Object object)
    {
        return object == null ? "null" : object.getClass().getName() + '@' //$NON-NLS-1$
            + Integer.toHexString(System.identityHashCode(object));
    }

    private static String singleLine(String text)
    {
        if (text == null)
            return ""; //$NON-NLS-1$
        return text.substring(0, Math.min(text.length(), 240)).replace('\r', ' ').replace('\n', ' ');
    }
}
