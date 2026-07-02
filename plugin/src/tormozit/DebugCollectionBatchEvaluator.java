package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.jobs.Job;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.evaluation.EvaluationChain;
import com._1c.g5.v8.dt.debug.core.model.evaluation.EvaluationJob;
import com._1c.g5.v8.dt.debug.core.model.evaluation.EvaluationRequest;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationChain;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationRequest;
import com._1c.g5.v8.dt.debug.model.calculations.ViewInterface;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * \u0411\u0430\u0442\u0447-\u0437\u0430\u0433\u0440\u0443\u0437\u043a\u0430 \u0435\u0449\u0451 \u043d\u0435 \u0432\u044b\u0447\u0438\u0441\u043b\u0435\u043d\u043d\u044b\u0445 {@link IBslValue} \u041e\u0414\u041d\u0418\u041c \u0437\u0430\u043f\u0440\u043e\u0441\u043e\u043c \u043a \u043e\u0442\u043b\u0430\u0434\u0447\u0438\u043a\u0443.
 *
 * <p><b>\u0412\u0430\u0436\u043d\u043e:</b> {@link IBslValue#reevaluate()} \u0417\u0414\u0415\u0421\u042c \u041d\u0415 \u0418\u0421\u041f\u041e\u041b\u042c\u0417\u0423\u0415\u0422\u0421\u042f. \u041f\u043e \u0434\u0435\u043a\u043e\u043c\u043f\u0438\u043b\u0443
 * {@code BslValue.reevaluate()} \u043e\u043d \u0441\u0442\u0440\u043e\u0438\u0442 \u0437\u0430\u044f\u0432\u043a\u0443 \u0422\u041e\u041b\u042c\u041a\u041e \u0435\u0441\u043b\u0438 {@code isEvaluated()==true} (\u044d\u0442\u043e \u043c\u0435\u0442\u043e\u0434
 * "\u043f\u0435\u0440\u0435\u043e\u0446\u0435\u043d\u0438\u0442\u044c \u0437\u0430\u043d\u043e\u0432\u043e" \u0443\u0436\u0435 \u043f\u043e\u043a\u0430\u0437\u0430\u043d\u043d\u043e\u0433\u043e \u0437\u043d\u0430\u0447\u0435\u043d\u0438\u044f \u043f\u043e\u0441\u043b\u0435 \u0448\u0430\u0433\u0430 \u0432 \u043e\u0442\u043b\u0430\u0434\u0447\u0438\u043a\u0435), \u0434\u043b\u044f \u0441\u0432\u0435\u0436\u0438\u0445
 * (NOT_EVALUATED) \u0437\u043d\u0430\u0447\u0435\u043d\u0438\u0439 \u043e\u043d \u0432\u0441\u0435\u0433\u0434\u0430 \u0432\u043e\u0437\u0432\u0440\u0430\u0449\u0430\u0435\u0442 null. \u0418\u043c\u0435\u043d\u043d\u043e \u043f\u043e\u044d\u0442\u043e\u043c\u0443 \u0432\u043c\u0435\u0441\u0442\u043e reevaluate()
 * \u043c\u044b \u0441\u0442\u0440\u043e\u0438\u043c {@link IEvaluationRequest} \u0441\u0430\u043c\u0438, \u0447\u0435\u0440\u0435\u0437 \u043f\u0443\u0431\u043b\u0438\u0447\u043d\u044b\u0439 {@link EvaluationRequest#builder(com._1c.g5.v8.dt.debug.core.model.values.BslValuePath)}
 * \u2014 \u0442\u043e\u0447\u043d\u043e \u0442\u0430\u043a \u0436\u0435, \u043a\u0430\u043a \u044d\u0442\u043e \u0434\u0435\u043b\u0430\u0435\u0442 \u0441\u0430\u043c EDT \u0432\u043d\u0443\u0442\u0440\u0438 {@code BslIndexedValue} \u0434\u043b\u044f \u0421\u0412\u041e\u0415\u0419 \u043f\u0435\u0440\u0432\u0438\u0447\u043d\u043e\u0439
 * \u044d\u0432\u0430\u043b\u044e\u0430\u0446\u0438\u0438 \u2014 \u043f\u0440\u043e\u0441\u0442\u043e \u0442\u0430\u043c \u044d\u0442\u043e \u0434\u0435\u043b\u0430\u0435\u0442\u0441\u044f \u043d\u0430 \u043e\u0434\u043d\u043e \u0437\u043d\u0430\u0447\u0435\u043d\u0438\u0435, \u0430 \u0437\u0434\u0435\u0441\u044c \u043c\u044b \u0441\u043e\u0431\u0438\u0440\u0430\u0435\u043c N \u0442\u0430\u043a\u0438\u0445 \u0437\u0430\u044f\u0432\u043e\u043a
 * \u0432 \u041e\u0414\u0418\u041d {@link EvaluationChain} \u0438 \u0448\u043b\u0451\u043c \u043e\u0434\u043d\u0438\u043c {@link EvaluationJob} (\u043f\u0443\u0431\u043b\u0438\u0447\u043d\u044b\u0439 \u043a\u043e\u043d\u0441\u0442\u0440\u0443\u043a\u0442\u043e\u0440
 * {@code EvaluationChain(IBslStackFrame, IEvaluationRequest...)}).
 *
 * <p>\u041e\u0442\u043a\u0440\u044b\u0442\u044b\u0439 \u0432\u043e\u043f\u0440\u043e\u0441: \u043e\u0431\u043d\u043e\u0432\u043b\u044f\u0435\u0442\u0441\u044f \u043b\u0438 \u0438\u0441\u0445\u043e\u0434\u043d\u044b\u0439 {@code value}-\u043e\u0431\u044a\u0435\u043a\u0442 in-place \u043f\u043e\u0441\u043b\u0435
 * \u0437\u0430\u0432\u0435\u0440\u0448\u0435\u043d\u0438\u044f job'\u0430 (\u043a\u0430\u043a \u044d\u0442\u043e \u0434\u0435\u043b\u0430\u0435\u0442 \u043f\u0440\u0438\u0432\u0430\u0442\u043d\u044b\u0439 {@code this::evaluationComplete} \u0432\u043d\u0443\u0442\u0440\u0438 EDT), \u0438\u043b\u0438
 * \u043d\u0443\u0436\u043d\u043e \u0431\u0443\u0434\u0435\u0442 \u0441\u0442\u0440\u043e\u0438\u0442\u044c \u043d\u043e\u0432\u044b\u0439 {@link IBslValue} \u0447\u0435\u0440\u0435\u0437 \u0444\u0430\u0431\u0440\u0438\u043a\u0443 \u0438\u0437 {@code IEvaluationResult}, \u043a\u043e\u0442\u043e\u0440\u044b\u0439 \u043f\u0440\u0438\u0434\u0451\u0442
 * \u0432 \u043d\u0430\u0448 \u0441\u043e\u0431\u0441\u0442\u0432\u0435\u043d\u043d\u044b\u0439 {@link com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationListener}. \u041f\u0440\u043e\u0432\u0435\u0440\u044f\u0435\u043c \u044d\u043c\u043f\u0438\u0440\u0438\u0447\u0435\u0441\u043a\u0438 \u043f\u043e \u043b\u043e\u0433\u0443
 * (\u0441\u0442\u0440\u043e\u043a\u0430 "after evaluated=N/M" \u043d\u0438\u0436\u0435).
 */
final class DebugCollectionBatchEvaluator
{
    private DebugCollectionBatchEvaluator() {}

    static void evaluateBatch(IBslStackFrame frame, List<IBslValue> values)
    {
        if (frame == null || values == null || values.isEmpty())
            return;

        List<IEvaluationRequest> requests = new ArrayList<>(values.size());
        for (IBslValue value : values)
        {
            if (value == null || value.isEvaluated())
                continue;
            IEvaluationRequest request = EvaluationRequest.builder(value.getPath())
                .setStackFrame(frame)
                .setExpressionUuid(value.getParentUuid())
                .setInterface(ViewInterface.NONE)
                .setMaxTestSize(1000)
                .setMultiLine(false)
                .build();
            requests.add(request);
        }
        if (requests.isEmpty())
            return;

        IEvaluationChain chain = new EvaluationChain(frame, requests.toArray(new IEvaluationRequest[0]));

        DebugCollectionDebug.step("batchEval", "requests=" + requests.size()); //$NON-NLS-1$

        Job job = new EvaluationJob(frame, chain);
        job.setSystem(true);
        job.schedule();
        try
        {
            job.join();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

        // \u042d\u043c\u043f\u0438\u0440\u0438\u0447\u0435\u0441\u043a\u0430\u044f \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0430: \u043e\u0431\u043d\u043e\u0432\u0438\u043b\u0438\u0441\u044c \u043b\u0438 \u0418\u0421\u0425\u041e\u0414\u041d\u042b\u0415 value-\u043e\u0431\u044a\u0435\u043a\u0442\u044b in-place.
        int evaluatedNow = 0;
        for (IBslValue value : values)
            if (value != null && value.isEvaluated())
                evaluatedNow++;
        DebugCollectionDebug.step("batchEval", //$NON-NLS-1$
            "after evaluated=" + evaluatedNow + "/" + values.size()); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
