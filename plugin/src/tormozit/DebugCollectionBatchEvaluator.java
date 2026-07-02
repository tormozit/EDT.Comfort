package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.jobs.Job;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.evaluation.EvaluationJob;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationChain;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Батч-реэвалюация ещё не вычисленных {@link IBslValue} ОДНИМ запросом к отладчику вместо
 * последовательных {@code value.evaluate()} по каждому значению.
 *
 * <p>Механизм — публичный API 1C EDT (не наш): {@link IBslValue#reevaluate()} только строит
 * заявку ({@link IEvaluationChain}) без похода к отладчику; заявки склеиваются через
 * {@link IEvaluationChain#combine(IEvaluationChain...)} и уходят одним {@link EvaluationJob}.
 * Слушатель результата привязан к конкретному value-объекту ещё на этапе {@code reevaluate()}
 * (см. декомпил {@code BslValue.reevaluate()}), поэтому после завершения job'а те же самые
 * {@link IBslValue}-объекты, что были переданы на вход, уже обновлены in-place — обменивать
 * ссылки на новые объекты не требуется.
 *
 * <p>Ровно тот же паттерн, которым сам EDT (internal {@code BslValueReevaluationService})
 * разом резолвит пачки pending-переменных в штатном Variables/Watch view.
 */
final class DebugCollectionBatchEvaluator
{
    private DebugCollectionBatchEvaluator() {}

    /**
     * Реэвалюирует все ещё не вычисленные значения из {@code values} одним комбинированным
     * запросом и блокируется (в вызывающем — фоновом — потоке) до его завершения.
     *
     * <p>Значения, которые уже {@code isEvaluated()} или уже {@code isPending()} (в процессе
     * из более раннего запроса), пропускаются — {@link IBslValue#reevaluate()} и так не строит
     * заявку для evaluated, а pending трогать незачем: у него уже есть свой в процессе идущий
     * запрос.
     */
    static void evaluateBatch(IBslStackFrame frame, List<IBslValue> values)
    {
        if (frame == null || values == null || values.isEmpty())
            return;

        List<IEvaluationChain> chains = new ArrayList<>(values.size());
        for (IBslValue value : values)
        {
            if (value == null || value.isEvaluated() || value.isPending())
                continue;
            IEvaluationChain chain = value.reevaluate();
            if (chain != null)
                chains.add(chain);
        }
        if (chains.isEmpty())
            return;

        IEvaluationChain combined = chains.size() == 1
            ? chains.get(0)
            : chains.get(0).combine(chains.toArray(new IEvaluationChain[0]));

        DebugCollectionDebug.step("batchEval", "requests=" + chains.size()); //$NON-NLS-1$

        Job job = new EvaluationJob(frame, combined);
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
    }
}
