package tormozit;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.evaluation.EvaluationJob;
import com._1c.g5.v8.dt.debug.core.model.evaluation.EvaluationRequest;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationRequest;
import com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationResult;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;
import com._1c.g5.v8.dt.debug.model.calculations.BaseValueInfoData;
import com._1c.g5.v8.dt.debug.model.calculations.CalculationResultBaseData;
import com._1c.g5.v8.dt.debug.model.calculations.ViewInterface;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * EDT 2026 отдаёт усечённый текст в нижнюю панель деталей (штатный {@link IBslValue#getDetailString()}
 * всегда шлёт отладчику {@code EvaluationRequest} с {@code setMaxTestSize(0)}, и повторный вызов не
 * помогает — сервер каждый раз отдаёт тот же обрезанный кусок). Строим свой {@code EvaluationRequest}
 * с большим {@code setMaxTestSize} и подставляем результат прямо в поля {@code detailString}/
 * {@code presentationState} самого значения — тогда штатный {@code getDetailString()} видит
 * {@code presentationState != NOT_EVALUATED} и отдаёт уже наш полный текст без нового урезанного
 * запроса при любой активации строки, не только при первой (см. issue #258).
 * <p>
 * Общий код для окна «Инспектор» ({@link DebugInspectorTreeEnhancement}) и панелей «Переменные» /
 * «Выражения» / «Выражения встроенного языка» ({@link DebugDetailPaneFullTextHook}), а также для
 * {@code TextPropertyEditMenuHook} ({@link DebugInspectorTreeEnhancement}) — команды «Редактировать
 * текст ИР» / «Редактировать текст запроса» должны получать полный, а не усечённый текст свойства.
 */
final class DebugDetailPaneFullTextSupport
{
    private static final int FULL_DETAIL_MAX_TEXT_SIZE = 1_000_000;
    private static final String EVALUATION_STATE_CLASS =
        "com._1c.g5.v8.dt.internal.debug.core.model.values.EvaluationState"; //$NON-NLS-1$

    /** Значения, для которых полный текст уже получен и записан в {@code detailString}. */
    private static final Map<IBslValue, Boolean> RESOLVED = new WeakHashMap<>();
    /** Значения, для которых запрос к отладчику уже отправлен и ответ ещё не пришёл. */
    private static final Set<IBslValue> PENDING = Collections.newSetFromMap(new WeakHashMap<>());

    private DebugDetailPaneFullTextSupport() {}

    /**
     * Вызывать при смене выделения в дереве.
     *
     * @param tree дерево, из которого берётся текущее выделение ({@code TreeItem.getData()} — {@link IBslVariable})
     * @param viewer JFace viewer этого дерева — нужен его {@code getSelection()} для повторного {@code display()}
     * @param detailPaneHost объект, чьё поле {@code detailPaneFieldName} хранит {@code DetailPaneProxy}
     * @param detailPaneFieldName имя поля ({@code "detailPane"} у {@code DebugElementDialog},
     *        {@code "fDetailPane"} у {@code VariablesView}/{@code ExpressionView}/{@code BslExpressionsView})
     */
    static void onTreeSelectionChanged(Tree tree, Object viewer, Object detailPaneHost, String detailPaneFieldName)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return;
        IBslValue bsl = selectedStringValue(tree);
        if (bsl == null)
            return;
        if (RESOLVED.containsKey(bsl) || PENDING.contains(bsl))
            return;
        requestFullDetailText(tree, viewer, detailPaneHost, detailPaneFieldName, bsl);
    }

    private static IBslValue selectedStringValue(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return null;
        TreeItem[] selection = tree.getSelection();
        if (selection.length == 0)
            return null;
        Object data = selection[0].getData();
        if (!(data instanceof IBslVariable variable))
            return null;
        IValue value = variable.getValue();
        if (!(value instanceof IBslValue bsl) || !DebugStringValueFormat.isStringValue(bsl))
            return null;
        return bsl;
    }

    /**
     * Полный (неусечённый) текст строкового свойства — асинхронно, {@code callback} вызывается
     * на UI-потоке. Тот же запрос к отладчику ({@code setMaxTestSize} без EDT-ограничения), что
     * используется для панели деталей (см. класс-javadoc); используется, когда нужен именно текст
     * (не отображение), например для «Редактировать текст ИР» / «Редактировать текст запроса».
     *
     * @param callback получает {@code null}, если свойство не строковое или текст получить не удалось
     */
    static void fetchFullText(IBslVariable variable, Consumer<String> callback)
    {
        IBslValue bsl;
        try
        {
            IValue value = variable.getValue();
            if (!(value instanceof IBslValue b) || !DebugStringValueFormat.isStringValue(b))
            {
                callback.accept(null);
                return;
            }
            bsl = b;
        }
        catch (Exception e)
        {
            callback.accept(null);
            return;
        }

        // Всегда свежий запрос с большим setMaxTestSize: кэш ({@code detailString}/{@code primitiveValue})
        // может быть как усечён EDT, так и (после {@link #patchValueDetailString}) обёрнут в кавычки —
        // для текста, который пойдёт в редактор как есть, нужен именно "сырой" ответ отладчика.
        BslValuePath path = bsl.getPath();
        IBslStackFrame stackFrame = bsl.getStackFrame();
        if (path == null || stackFrame == null)
        {
            callback.accept(null);
            return;
        }

        IEvaluationRequest request = EvaluationRequest.builder(path)
            .setStackFrame(stackFrame)
            .setExpressionUuid(bsl.getParentUuid())
            .setInterface(ViewInterface.NONE)
            .setMaxTestSize(FULL_DETAIL_MAX_TEXT_SIZE)
            .setMultiLine(true)
            .setEvaluationListener(result -> onFullTextResolved(bsl, result, callback))
            .build();
        new EvaluationJob(request).schedule();
    }

    private static void onFullTextResolved(IBslValue bsl, IEvaluationResult result, Consumer<String> callback)
    {
        String text = extractText(result);
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            if (text != null)
            {
                RESOLVED.put(bsl, Boolean.TRUE);
                patchValueDetailString(bsl, text);
            }
            callback.accept(text);
        });
    }

    private static void requestFullDetailText(Tree tree, Object viewer, Object detailPaneHost,
        String detailPaneFieldName, IBslValue bsl)
    {
        BslValuePath path = bsl.getPath();
        IBslStackFrame stackFrame = bsl.getStackFrame();
        if (path == null || stackFrame == null)
            return;
        PENDING.add(bsl);
        IEvaluationRequest request = EvaluationRequest.builder(path)
            .setStackFrame(stackFrame)
            .setExpressionUuid(bsl.getParentUuid())
            .setInterface(ViewInterface.NONE)
            .setMaxTestSize(FULL_DETAIL_MAX_TEXT_SIZE)
            .setMultiLine(true)
            .setEvaluationListener(result ->
                onEvaluationComplete(tree, viewer, detailPaneHost, detailPaneFieldName, bsl, result))
            .build();
        new EvaluationJob(request).schedule();
    }

    private static void onEvaluationComplete(Tree tree, Object viewer, Object detailPaneHost,
        String detailPaneFieldName, IBslValue bsl, IEvaluationResult result)
    {
        String text = extractText(result);
        if (tree.isDisposed())
            return;
        tree.getDisplay().asyncExec(() ->
        {
            PENDING.remove(bsl);
            if (tree.isDisposed() || text == null)
                return;
            RESOLVED.put(bsl, Boolean.TRUE);
            patchValueDetailString(bsl, text);
            if (bsl == selectedStringValue(tree))
                refreshDisplay(viewer, detailPaneHost, detailPaneFieldName);
        });
    }

    private static String extractText(IEvaluationResult result)
    {
        try
        {
            if (result == null || !result.isSuccess())
                return null;
            CalculationResultBaseData data = result.getResult();
            BaseValueInfoData info = data != null ? data.getResultValueInfo() : null;
            byte[] bytes = info != null ? info.getValueString() : null;
            if (bytes == null || bytes.length == 0)
                return null;
            return new String(bytes, StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Подменяет {@code detailString}/{@code presentationState} прямо в объекте {@link IBslValue} —
     * источнике, из которого штатный {@code getDetailString()} берёт текст для панели деталей.
     */
    private static void patchValueDetailString(IBslValue bsl, String fullText)
    {
        Object evaluatedState = resolveEvaluatedStateConstant(bsl);
        if (evaluatedState == null)
            return;
        // Наш текст берётся из "сырого" getValueString() (без кавычек), а не из getPres(), которое
        // штатно уже приходит с кавычками для строкового типа — оборачиваем сами, как в дереве.
        String quoted = "\"" + fullText + "\""; //$NON-NLS-1$ //$NON-NLS-2$
        Global.setFieldForce(bsl, "detailString", quoted); //$NON-NLS-1$
        Global.setFieldForce(bsl, "presentationState", evaluatedState); //$NON-NLS-1$
    }

    /**
     * {@code EvaluationState.EVALUATED} через classloader самого значения — не зависит от того,
     * найдёт ли {@link Global#getField} текущее (возможно ещё не инициализированное) значение поля
     * {@code presentationState}.
     */
    private static Object resolveEvaluatedStateConstant(IBslValue bsl)
    {
        try
        {
            Class<?> stateClass = Class.forName(EVALUATION_STATE_CLASS, true, bsl.getClass().getClassLoader());
            for (Object constant : stateClass.getEnumConstants())
            {
                if (constant instanceof Enum<?> e && "EVALUATED".equals(e.name())) //$NON-NLS-1$
                    return constant;
            }
        }
        catch (Exception ignored)
        {
            // класс недоступен в этой версии EDT
        }
        return null;
    }

    /** {@code DetailPaneProxy.display(selection)} — безопасно вызывать: значение уже пропатчено. */
    private static void refreshDisplay(Object viewer, Object detailPaneHost, String detailPaneFieldName)
    {
        if (detailPaneHost == null || viewer == null)
            return;
        Object detailPaneProxy = Global.getField(detailPaneHost, detailPaneFieldName);
        if (detailPaneProxy == null)
            return;
        Object selection = Global.invoke(viewer, "getSelection"); //$NON-NLS-1$
        if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty())
            return;
        Global.invokeVoid(detailPaneProxy, "display", structured); //$NON-NLS-1$
    }
}
