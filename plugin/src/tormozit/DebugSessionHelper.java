package tormozit;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IDebugTarget;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTargetManager;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread;
import com._1c.g5.v8.dt.debug.core.model.evaluation.EvaluationRequest;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationListener;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationRequest;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationResult;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.model.calculations.BaseValueInfoData;
import com._1c.g5.v8.dt.debug.model.calculations.CalculationResultBaseData;
import com._1c.g5.v8.dt.debug.model.calculations.ViewInterface;
import com.e1c.g5.dt.applications.IApplication;

/**
 * Утилиты для работы с API отладки EDT (замена TurboConf в команде «Отладить ИР»).
 */
public final class DebugSessionHelper
{
    private static final String THICK_CLIENT_MARKER = "Толстый клиент"; //$NON-NLS-1$

    /**
     * Предел длины текста значения в ответе отладчика. По умолчанию
     * {@code EvaluationRequest.EvaluationRequestBuilder.maxTestSize} равен 100 символам — этого мало
     * даже для служебной строки {@code ирОбщий.От}, которую ИР возвращает для команды «Открыть объект
     * для отладки». Тот же предел используется в {@code DebugDetailPaneFullTextSupport}.
     */
    private static final int MAX_TEXT_SIZE = 2_000_000;

    private DebugSessionHelper() {}

    public static final class SuspendedContext
    {
        public final IRuntimeDebugClientTarget target;
        public final IRuntimeDebugTargetThread thread;
        public final IBslStackFrame frame;

        SuspendedContext(IRuntimeDebugClientTarget target, IRuntimeDebugTargetThread thread, IBslStackFrame frame)
        {
            this.target = target;
            this.thread = thread;
            this.frame = frame;
        }
    }

    public static final class EvalResult
    {
        public final String valueText;
        public final String typeText;
        public final String error;

        public EvalResult(String valueText, String typeText, String error)
        {
            this.valueText = valueText;
            this.typeText = typeText;
            this.error = error;
        }

        public boolean hasValue()
        {
            return valueText != null && !valueText.isBlank();
        }
    }

    public static boolean isDebugSuspended(IProject project)
    {
        return findSuspendedContext(project) != null;
    }

    public static boolean isThickClientDebug(IProject project)
    {
        SuspendedContext ctx = findSuspendedContext(project);
        if (ctx == null)
            return false;
        return isThickClientDebug(ctx);
    }

    public static IBslStackFrame findSuspendedStackFrame(IProject project)
    {
        SuspendedContext ctx = findSuspendedContext(project);
        return ctx != null ? ctx.frame : null;
    }

    public static EvalResult evaluateExpression(IBslStackFrame frame, String expression, long timeoutMs)
    {
        if (frame == null || expression == null || expression.isBlank())
            return new EvalResult(null, null, "empty expression"); //$NON-NLS-1$

        IDebugTarget debugTarget;
        debugTarget = frame.getDebugTarget();
        if (!(debugTarget instanceof IRuntimeDebugClientTarget target))
            return new EvalResult(null, null, "no runtime debug target"); //$NON-NLS-1$

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<EvalResult> holder = new AtomicReference<>(new EvalResult(null, null, "timeout")); //$NON-NLS-1$

        IEvaluationListener listener = result ->
        {
            try
            {
                holder.set(extractResult(result));
            }
            catch (DebugException e)
            {
                holder.set(new EvalResult(null, null, e.getMessage()));
            }
            finally
            {
                latch.countDown();
            }
        };

        try
        {
            BslValuePath path = new BslValuePath(expression);
            IEvaluationRequest request = EvaluationRequest.builder(path)
                .setStackFrame(frame)
                .setExpressionUuid(UUID.randomUUID())
                .setInterface(ViewInterface.NONE)
                .setMaxTestSize(MAX_TEXT_SIZE)
                .setMultiLine(true)
                .setEvaluationListener(listener)
                .build();
            target.getEvaluationEngine().evaluateExpression(request);
        }
        catch (DebugException e)
        {
            return new EvalResult(null, null, e.getMessage());
        }
        catch (RuntimeException e)
        {
            return new EvalResult(null, null, e.getMessage());
        }

        try
        {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS))
                return new EvalResult(null, null, "timeout"); //$NON-NLS-1$
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return new EvalResult(null, null, e.getMessage());
        }
        return holder.get();
    }

    static SuspendedContext findSuspendedContext(IProject project)
    {
        IRuntimeDebugClientTargetManager manager =
            Global.getOsgiService(IRuntimeDebugClientTargetManager.class);
        if (manager == null)
            return null;

        Collection<IRuntimeDebugClientTarget> targets = manager.listDebugTargets();
        if (targets == null || targets.isEmpty())
            return null;

        SuspendedContext matched = null;
        SuspendedContext fallback = null;

        for (IRuntimeDebugClientTarget target : targets)
        {
            SuspendedContext ctx = suspendedContextForTarget(target);
            if (ctx == null)
                continue;

            if (fallback == null)
                fallback = ctx;

            if (project != null && targetMatchesProject(target, project))
            {
                matched = ctx;
                break;
            }
        }
        return matched != null ? matched : fallback;
    }

    private static boolean targetMatchesProject(IRuntimeDebugClientTarget target, IProject project)
    {
        Optional<IApplication> app = target.getApplication();
        if (!app.isPresent())
            return false;
        Object appProject = Global.call(app.get(), "getProject"); //$NON-NLS-1$
        return project.equals(appProject);
    }

    private static SuspendedContext suspendedContextForTarget(IRuntimeDebugClientTarget target)
    {
        try
        {
            for (IRuntimeDebugTargetThread thread : target.getThreads())
            {
                if (thread == null || !thread.isSuspended())
                    continue;
                IBslStackFrame frame = thread.getTopStackFrame();
                if (frame != null)
                    return new SuspendedContext(target, thread, frame);
            }
        }
        catch (DebugException ignored) {}
        return null;
    }

    private static boolean isThickClientDebug(SuspendedContext ctx)
    {
        try
        {
            IBslStackFrame[] frames = ctx.thread.getStackFrames();
            if (frames != null)
            {
                for (IBslStackFrame frame : frames)
                {
                    if (frame == null)
                        continue;
                    String signature = frame.getSignature();
                    if (containsThickClientMarker(signature))
                        return true;
                }
            }
            String threadName = ctx.thread.getName();
            return containsThickClientMarker(threadName);
        }
        catch (DebugException ignored)
        {
            return false;
        }
    }

    private static boolean containsThickClientMarker(String text)
    {
        return text != null
            && text.toLowerCase(Locale.ROOT).contains(THICK_CLIENT_MARKER.toLowerCase(Locale.ROOT));
    }

    private static EvalResult extractResult(IEvaluationResult evalResult) throws DebugException
    {
        if (evalResult == null)
            return new EvalResult(null, null, "empty evaluation result"); //$NON-NLS-1$

        CalculationResultBaseData data = evalResult.getResult();
        if (data == null)
            return new EvalResult(null, null, "empty calculation result"); //$NON-NLS-1$

        if (data.getErrorOccurred())
            return new EvalResult(null, null, bytesToPresentation(data.getExceptionStr()));

        BaseValueInfoData info = data.getResultValueInfo();
        if (info == null)
            return new EvalResult(null, null, "no value info"); //$NON-NLS-1$

        String value = bytesToPresentation(info.getPres());
        if (value == null || value.isBlank())
            value = bytesToPresentation(info.getValueString());
        String type = info.getTypeName();
        return new EvalResult(value, type, null);
    }

    /** Как {@code RuntimePresentationConverter.presentation} во внутреннем API EDT. */
    private static String bytesToPresentation(byte[] bytes)
    {
        if (bytes == null || bytes.length == 0)
            return null;
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
