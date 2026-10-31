package tormozit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension4;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension5;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension6;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.util.InternalEList;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.jface.text.source.SourceViewer;

import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.ProposalElement;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Обёртка proposal: кастомны только {@link #validate} и {@link #getStyledDisplayString()};
 * {@link #apply} — pass-through к delegate; если каретка перед {@code (}, вставка без {@code ()}.
 * Каретка сразу после {@code =} с пробелом перед знаком — перед вставляемым словом тоже пробел.
 */
public class SmartCompletionProposal implements
    ICompletionProposal,
    ICompletionProposalExtension,
    ICompletionProposalExtension2,
    ICompletionProposalExtension3,
    ICompletionProposalExtension4,
    ICompletionProposalExtension5,
    ICompletionProposalExtension6
{
    private final ICompletionProposal delegate;
    /** Индекс в штатном списке delegate; {@code -1} — неизвестен. */
    private final int delegateOrder;
    /** Как {@code ConfigurableCompletionProposal.apply(ITextViewer,…)} — флаг COMPLETE. */
    private static final int COMPLETE_STATE_MASK = 262144;

    /** Флаг: мы внутри {@link #apply} — document-listener не должен запускать авто-открытие. */
    static final ThreadLocal<Boolean> PROPOSAL_APPLY_IN_PROGRESS = new ThreadLocal<>();

    /**
     * Apply идёт для {@link IrCompletionProposal}: не цеплять EDT {@code DataEvent}/LinkedMode
     * на текст вставки (иначе trim’нутый {@code Структура()} EDT пересекается с ИР).
     */
    static final ThreadLocal<Boolean> IR_PROPOSAL_APPLY_IN_PROGRESS = new ThreadLocal<>();

    /**
     * Вставка слова ИР с заменой родителя ({@code слово} содержит разделитель варианта).
     * Нужна {@link ContentAssistSessionReloader} для подсказки параметров после {@code ()}.
     */
    static final ThreadLocal<Boolean> IR_REPLACE_PARENT_APPLY = new ThreadLocal<>();

    /**
     * ИР-слово overlap для EDT-строки (issue 562), запомненное в {@link #selected}
     * до {@code assistSessionEnded}: тот зовётся ДО {@code apply()} и чистит
     * {@code ACTIVE_PROCESSOR}/{@code openSessionReloader}, поэтому повторный
     * {@code findIrOverlapForEdt(delegate)} в момент apply уже не находит ничего
     * ({@code getActiveProcessor()} вернёт {@code null}) — использовать только этот кэш.
     */
    private IrCompletionProposal overlapIr;

    /** Каретка после overlap-вставки ИР; {@code -1} — не задана. */
    private int overlapIrCaret = -1;

    /** Каретка внутри сохранённых скобок после вставки только имени метода. */
    private int wordOnlyCaret = -1;

    /** Каретка внутри встроенного шаблона вставки. */
    private Point templateSelection;

    /** Функция конфигурации строки ({@link FunctionReturnTypes}); {@code null} — не функция. */
    private FunctionReturnTypes.Target functionTarget;
    private boolean functionTargetResolved;
    /** {@link #moduleParams()}: посчитано один раз на строку. */
    private int[] moduleParams;
    private boolean moduleParamsResolved;
    /** Переменная строки ({@link FunctionReturnTypes#variableTargetOf}); {@code null} — не переменная. */
    private FunctionReturnTypes.VariableTarget variableTarget;
    private boolean variableTargetResolved;
    /** Временно #877: строка уже записана в лог. */
    private boolean rowLogged;
    private boolean rowResultLogged;

    public SmartCompletionProposal(ICompletionProposal delegate)
    {
        this(delegate, -1);
    }

    public SmartCompletionProposal(ICompletionProposal delegate, int delegateOrder)
    {
        // Вложенные обёртки: selected/getStyledDisplayString зовут delegate как Extension6
        // и получают StackOverflowError (лог 10:40 / 11:05).
        this.delegate = unwrapRaw(delegate);
        this.delegateOrder = delegateOrder;
    }

    private static ICompletionProposal unwrapRaw(ICompletionProposal proposal)
    {
        ICompletionProposal start = proposal;
        int depth = 0;
        while (proposal instanceof SmartCompletionProposal)
        {
            ICompletionProposal next = ((SmartCompletionProposal) proposal).delegate;
            if (next == null || next == proposal || next == start || ++depth > 8)
                return next instanceof SmartCompletionProposal ? start : (next == null ? start : next);
            proposal = next;
        }
        return proposal;
    }

    public ICompletionProposal getDelegate()
    {
        return delegate;
    }

    public int getDelegateOrder()
    {
        return delegateOrder;
    }

    // ---- ICompletionProposal ------------------------------------------------

    @Override
    public StyledString getStyledDisplayString()
    {
        String overlapDisplay = resolveIrOverlapDisplayOverride();
        ICompletionProposal raw = delegate instanceof SmartCompletionProposal
            ? null : delegate;
        String sourceDisplay = overlapDisplay != null
            ? overlapDisplay
            : (raw == null ? "" : raw.getDisplayString()); //$NON-NLS-1$
        String localized = localizeDisplayTypes(sourceDisplay);
        if (delegate instanceof ConfigurableCompletionProposal configurable)
            localized = BslFormDataTypes.decorateProposal(localized,
                Global.getField(configurable, "additionalProposalInfo")); //$NON-NLS-1$
        // #region temp #877 — почему строка без типа: первый показ каждой строки
        if (!rowLogged)
        {
            rowLogged = true;
            Object info = delegate instanceof ConfigurableCompletionProposal c
                ? Global.getField(c, "additionalProposalInfo") : null; //$NON-NLS-1$
            Global.tempLog("assist-function-return-type", "row display=" + sourceDisplay //$NON-NLS-1$ //$NON-NLS-2$
                + " delegate=" + (delegate == null ? null : delegate.getClass().getSimpleName()) //$NON-NLS-1$
                + " overlap=" + (overlapDisplay != null) //$NON-NLS-1$
                + " info=" + (info == null ? null : info.getClass().getSimpleName()) //$NON-NLS-1$
                + " var=" + (variableTarget() != null) + " fn=" + (functionTarget() != null)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // #endregion
        // Подпись совпадения с ИР несёт тип ИР — его досчитывает активация ИР.
        if (overlapDisplay == null)
            FunctionReturnTypes.rowShown(this);
        String collapsed = collapseParams(overlapDisplay != null
            ? localized : decoratePredefinedValueType(decoratePropertyType(decorateVariableType(
                decorateFunctionType(localized, functionTarget()), variableTarget()))),
            moduleParams(), hasSeveralSignatures(sourceDisplay) ? signatureFirstParam(sourceDisplay) : null);
        // #region temp — переменная без «<?>» при первом открытии: итоговая подпись первого показа
        if (!rowResultLogged && variableTarget() != null)
        {
            rowResultLogged = true;
            Global.tempLog("assist-function-return-type", "row result=" + collapsed //$NON-NLS-1$ //$NON-NLS-2$
                + " source=" + sourceDisplay); //$NON-NLS-1$
        }
        // #endregion
        StyledString result;
        if (collapsed != localized)
        {
            localized = collapsed;
            result = buildIrStyledDisplayString(collapsed);
        }
        else if (overlapDisplay != null || delegate instanceof IrCompletionProposal)
            result = buildIrStyledDisplayString(localized);
        else if (localized != null && localized.equals(sourceDisplay)
            && !(delegate instanceof SmartCompletionProposal)
            && delegate instanceof ICompletionProposalExtension6 ext6)
            result = ext6.getStyledDisplayString();
        else
            result = buildIrStyledDisplayString(localized != null ? localized : ""); //$NON-NLS-1$
        applyTypeStyle(result, delegate);
        SmartCodeMatcher matcher = resolveHighlightMatcher();
        if (!matcher.isEmpty)
        {
            String display = localized != null ? localized : ""; //$NON-NLS-1$
            String nameOnly = SmartContentAssistProcessor.parseProposalListName(display);
            if (!nameOnly.isEmpty())
                SmartMatchHighlight.applyRanges(result, matcher.getHighlightRanges(nameOnly));
        }
        // #region temp #877: тип есть в кэше, но отсутствует на экране
        Global.tempLog("assist-type-display", "render proposal=" + System.identityHashCode(this) //$NON-NLS-1$ //$NON-NLS-2$
            + " source=" + sourceDisplay + " decorated=" + collapsed + " result=" + result.getString() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " variable=" + variableTarget()); //$NON-NLS-1$
        // #endregion
        return result;


    }

    /**
     * {@code Имя(А, Б, В) : <Тип> ~ Родитель} → {@code Имя(3) : <Тип> ~ Родитель} при флажке
     * «Сворачивать параметры в списке автодополнения» (issue #877). Только для показа: разбор
     * сигнатуры идёт по исходной подписи delegate. Без изменений — тот же экземпляр строки.
     *
     * <p>Число — номер последнего обязательного параметра. У платформенного метода это строка
     * с минимумом (перегрузки схлопываются до неё) — число по подписи. У метода модуля строка
     * одна со всеми параметрами — номер берётся из {@code moduleParams}: {@code [номер, всего]},
     * применяется, только если {@code всего} совпало с подписью. Номер {@code 0} выводится,
     * если есть необязательные параметры; пустые скобки — у метода без параметров.
     *
     * <p>{@code severalSignatures} не {@code null} — у имени несколько сигнатур: перед числом
     * имя первого параметра, {@code Структура(Ключи, 0)}; число выводится и при {@code 0}.
     * В пустой подписи (строка-минимум без обязательных) имени нет — оно в
     * {@code severalSignatures} (из сигнатуры); пустая строка — оставить {@code Имя()}.
     */
    static String collapseParams(String display, int[] moduleParams, String severalSignatures)
    {
        if (display == null || !ComfortSettings.isCollapseAssistParamsEnabled())
            return display;
        int open = signatureOpen(display);
        int close = signatureClose(display, open);
        if (close < 0)
            return display;
        String inside = display.substring(open + 1, close).trim();
        if (inside.isEmpty() && severalSignatures != null && !severalSignatures.isEmpty())
            return display.substring(0, open + 1) + severalSignatures + ", 0" //$NON-NLS-1$
                + display.substring(close);
        if (inside.isEmpty() && moduleParams != null && moduleParams[0] == 0 && moduleParams[1] > 0)
            return display.substring(0, open + 1) + "0" + display.substring(close); //$NON-NLS-1$
        if (inside.isEmpty() || inside.chars().allMatch(Character::isDigit))
            return display;
        int depth = 0;
        int count = 1;
        int firstEnd = close;
        for (int i = open + 1; i < close; i++)
        {
            char c = display.charAt(i);
            if (c == '(' || c == '<' || c == '[')
                depth++;
            else if (c == ')' || c == '>' || c == ']')
                depth--;
            else if (c == ',' && depth == 0)
            {
                if (count == 1)
                    firstEnd = i;
                count++;
            }
        }
        if (moduleParams != null && moduleParams[1] == count)
            count = moduleParams[0];
        String first = severalSignatures != null
            ? firstParamName(display.substring(open + 1, firstEnd)) : ""; //$NON-NLS-1$
        String text = !first.isEmpty() ? first + ", " + count //$NON-NLS-1$
            : String.valueOf(count);
        return display.substring(0, open + 1) + text + display.substring(close);
    }

    /** {@code Ключи: Строка = ""} → {@code Ключи}. */
    private static String firstParamName(String segment)
    {
        String name = segment.trim();
        int colon = name.indexOf(':');
        if (colon >= 0)
            name = name.substring(0, colon).trim();
        int eq = name.indexOf('=');
        if (eq >= 0)
            name = name.substring(0, eq).trim();
        return name;
    }

    /**
     * Первый параметр сигнатуры платформенного метода ({@code ParamSet}) на языке подписи:
     * у строки-минимума без обязательных в подписи его нет. {@code ""} — не известен.
     */
    private String signatureFirstParam(String sourceDisplay)
    {
        com._1c.g5.v8.dt.mcore.ParamSet set = SmartContentAssistProcessor.paramSetSignatureOf(delegate);
        if (set == null || set.getParams().isEmpty())
            return ""; //$NON-NLS-1$
        com._1c.g5.v8.dt.mcore.Parameter param = set.getParams().get(0);
        String listName = SmartContentAssistProcessor.parseProposalListName(sourceDisplay);
        boolean ru = !(set.eContainer() instanceof com._1c.g5.v8.dt.mcore.DuallyNamedElement owner)
            || listName.equalsIgnoreCase(owner.getNameRu());
        String name = ru ? param.getNameRu() : param.getName();
        return name != null ? name : ""; //$NON-NLS-1$
    }

    /** Список окна, по которому посчитан {@link #severalSignatureKeys}, и его размер. */
    private static java.lang.ref.WeakReference<java.util.List<ICompletionProposal>> severalSignatureList =
        new java.lang.ref.WeakReference<>(null);
    private static int severalSignatureListSize = -1;
    /** Ключи имени и родителя, у которых в окне больше одной строки со скобками. */
    private static java.util.Set<String> severalSignatureKeys = java.util.Collections.emptySet();

    /**
     * У имени несколько сигнатур: перегрузки одной сигнатуры к показу уже схлопнуты
     * (см. {@code SmartContentAssistProcessor.collapseOptionalParamOverlaps}), значит,
     * оставшиеся в окне строки со скобками, тем же именем и родителем — разные сигнатуры. Модель не
     * читаем: сигнатуры конструктора без {@code readOnly} не получить, а это отрисовка.
     */
    private boolean hasSeveralSignatures(String sourceDisplay)
    {
        if (!ComfortSettings.isCollapseAssistParamsEnabled() || delegate instanceof IrCompletionProposal
            || SmartContentAssistProcessor.parseProposalSignature(sourceDisplay).indexOf('(') < 0)
            return false;
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        java.util.List<ICompletionProposal> rows = assistant != null
            ? ContentAssistPopupSync.visibleProposals(assistant) : null;
        if (rows == null)
            return false;
        if (severalSignatureList.get() != rows || severalSignatureListSize != rows.size())
        {
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            for (ICompletionProposal row : rows)
            {
                ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(row);
                if (raw == null || raw instanceof IrCompletionProposal
                    || SmartContentAssistProcessor.parseProposalSignature(raw.getDisplayString())
                        .indexOf('(') < 0)
                    continue;
                counts.merge(rowKey(row), 1, Integer::sum);
            }
            java.util.Set<String> keys = new java.util.HashSet<>();
            counts.forEach((key, n) -> {
                if (n > 1)
                    keys.add(key);
            });
            severalSignatureKeys = keys;
            severalSignatureList = new java.lang.ref.WeakReference<>(rows);
            severalSignatureListSize = rows.size();
        }
        return severalSignatureKeys.contains(rowKey(this));
    }

    private static String rowKey(ICompletionProposal proposal)
    {
        return SmartContentAssistProcessor.signatureGroupKey(proposal);
    }

    /**
     * Строка со всеми параметрами, у которой обязательность из подписи не видна:
     * {@code [номер последнего обязательного параметра, всего]}. Метод модуля — параметры без
     * значения по умолчанию; конструктор ({@code FakeCtor}) — {@code Ctor.getMinParams()} из
     * фонового кэша типов; платформенный метод — {@code ParamSet.getMinParams()} (строки-минимума
     * сигнатуры в списке может не быть: у двух {@code ParamSet} с минимумом 0 EDT даёт одну
     * {@code Имя()}). {@code null} — не такая строка или ещё не известно.
     */
    private int[] moduleParams()
    {
        if (moduleParamsResolved)
            return moduleParams;
        com._1c.g5.v8.dt.mcore.ParamSet set = SmartContentAssistProcessor.paramSetSignatureOf(delegate);
        if (set != null)
        {
            moduleParamsResolved = true;
            int total = set.getMaxParams() >= 0 ? set.getMaxParams() : set.getParams().size();
            moduleParams = new int[] { Math.max(0, set.getMinParams()), total };
            return moduleParams;
        }
        Object info = delegate instanceof ConfigurableCompletionProposal configurable
            ? Global.getField(configurable, "additionalProposalInfo") : null; //$NON-NLS-1$
        if (info instanceof com._1c.g5.v8.dt.mcore.FakeCtor fake)
        {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (com._1c.g5.v8.dt.mcore.FakeParameter param : fake.getParams())
                names.add(param.getName() != null ? param.getName() : ""); //$NON-NLS-1$
            int lastRequired = SmartContentAssistProcessor.ctorLastRequiredParam(fake.getTypeName(), names);
            // Не известно — спросить снова при следующей отрисовке (кэш типов наполняется в фоне).
            if (lastRequired < 0)
                return null;
            moduleParamsResolved = true;
            moduleParams = new int[] { lastRequired, names.size() };
            return moduleParams;
        }
        moduleParamsResolved = true;
        if (!(info instanceof com._1c.g5.v8.dt.bsl.model.Method method) || method.eIsProxy())
            return null;
        java.util.List<FormalParam> params = method.getFormalParams();
        int lastRequired = 0;
        for (int i = 0; i < params.size(); i++)
        {
            if (params.get(i).getDefaultValue() == null)
                lastRequired = i + 1;
        }
        moduleParams = new int[] { lastRequired, params.size() };
        return moduleParams;
    }

    /** {@code (} списка параметров в подписи {@code Имя(…) : <Тип> ~ Родитель}; {@code -1} — нет. */
    private static int signatureOpen(String display)
    {
        if (display == null)
            return -1;
        int sep = display.indexOf(" ~ "); //$NON-NLS-1$
        int headEnd = sep >= 0 ? sep : display.length();
        int open = display.indexOf('(');
        if (open <= 0 || open >= headEnd)
            return -1;
        // «Имя : <Строка(10)>» — скобка в типе свойства, а не параметры.
        int colon = display.indexOf(':');
        if (colon >= 0 && colon < open)
            return -1;
        // «Имя - описание (…)» — скобка в пояснении шаблона.
        int dash = display.indexOf(" - "); //$NON-NLS-1$
        if (dash >= 0 && dash < open)
            return -1;
        return open;
    }

    /** Парная {@code )} к {@link #signatureOpen}; {@code -1} — нет. */
    private static int signatureClose(String display, int open)
    {
        if (open < 0)
            return -1;
        int sep = display.indexOf(" ~ "); //$NON-NLS-1$
        int headEnd = sep >= 0 ? sep : display.length();
        int depth = 0;
        for (int i = open + 1; i < headEnd; i++)
        {
            char c = display.charAt(i);
            if (c == '(' || c == '<' || c == '[')
                depth++;
            else if (c == ')' && depth == 0)
                return i;
            else if (c == ')' || c == '>' || c == ']')
                depth--;
        }
        return -1;
    }

    private FunctionReturnTypes.Target functionTarget()
    {
        if (!functionTargetResolved)
        {
            functionTargetResolved = true;
            functionTarget = delegate instanceof ConfigurableCompletionProposal configurable
                ? FunctionReturnTypes.targetOf(Global.getField(configurable, "additionalProposalInfo"), 0) //$NON-NLS-1$
                : null;
        }
        return functionTarget;
    }

    private FunctionReturnTypes.VariableTarget variableTarget()
    {
        if (!variableTargetResolved)
        {
            variableTargetResolved = true;
            variableTarget = delegate instanceof ConfigurableCompletionProposal configurable
                ? FunctionReturnTypes.variableTargetOf(Global.getField(configurable, "additionalProposalInfo")) //$NON-NLS-1$
                : null;
        }
        return variableTarget;
    }

    /**
     * Подпись строки переменной с её типом в точке, где открыт список: {@code имя : <Тип>};
     * до расчёта — {@code : <?>}, по прежнему состоянию модуля — {@code : <?, Тип>}. Тип, который
     * не удалось определить, не пишется.
     */
    private static String decorateVariableType(String display, FunctionReturnTypes.VariableTarget target)
    {
        if (target == null || display == null || display.isEmpty() || hasNameType(display))
            return display;
        return decorateNameType(display, FunctionReturnTypes.variableState(target));
    }

    /**
     * Подпись строки свойства (общие модули глобального контекста и т.п.) с его типом, если EDT его
     * не написала: {@code имя : <ОбщийМодуль>}; пока ссылки на типы загружаются — {@code : <?>}.
     */
    private String decoratePropertyType(String display)
    {
        if (display == null || display.isEmpty() || hasNameType(display)
            || !(delegate instanceof ConfigurableCompletionProposal configurable))
            return display;
        return decorateNameType(display,
            FunctionReturnTypes.propertyState(Global.getField(configurable, "additionalProposalInfo"))); //$NON-NLS-1$
    }

    /**
     * Подпись значения системного перечисления или предопределённого значения с его типом:
     * {@code Обычное : <СтатусСообщения>}. Такие строки EDT создаёт простыми предложениями — без
     * описания-объекта, с текстом вставки {@code СтатусСообщения.Обычное} или
     * {@code ПредопределенноеЗначение("…")}; тип берётся из него ({@link #predefinedValueType}).
     */
    private String decoratePredefinedValueType(String display)
    {
        if (display == null || display.isEmpty() || hasNameType(display))
            return display;
        // EDT кладёт в такие строки лишь имя значения — тип берётся из места ввода
        String type = BslExpectedTypePremiums.predefinedValueTypeInContext(delegate);
        if (type == null)
            type = predefinedValueType(delegate);
        return type == null ? display
            : decorateNameType(display, new FunctionReturnTypes.State("<" + type + ">", true)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static final java.util.regex.Pattern PREDEFINED_VALUE_CALL = java.util.regex.Pattern.compile(
        "(?iu)^(?:ПредопределенноеЗначение|PredefinedValue)\\s*\\(\\s*\"(.*)\"\\s*\\)$"); //$NON-NLS-1$

    /** Ссылочный тип по корню предопределённого значения (менеджер или вид в {@code ПредопределенноеЗначение}). */
    private static final java.util.Map<String, String> PREDEFINED_REF_TYPES = java.util.Map.of(
        "перечисления", "ПеречислениеСсылка", "перечисление", "ПеречислениеСсылка", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "справочники", "СправочникСсылка", "справочник", "СправочникСсылка", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "планысчетов", "ПланСчетовСсылка", "плансчетов", "ПланСчетовСсылка", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "планывидовхарактеристик", "ПланВидовХарактеристикСсылка", //$NON-NLS-1$ //$NON-NLS-2$
        "планвидовхарактеристик", "ПланВидовХарактеристикСсылка", //$NON-NLS-1$ //$NON-NLS-2$
        "планывидоврасчета", "ПланВидовРасчетаСсылка", "планвидоврасчета", "ПланВидовРасчетаСсылка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * Тип значения перечисления или предопределённого значения строки EDT без описания-объекта, по тексту
     * вставки: {@code СтатусСообщения.Обычное} → {@code СтатусСообщения}, {@code Перечисления.Пол.Мужской} →
     * {@code ПеречислениеСсылка.Пол}; последний фрагмент должен совпадать с именем строки. Иначе {@code null}.
     */
    static String predefinedValueType(ICompletionProposal proposal)
    {
        ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
        if (!(raw instanceof ConfigurableCompletionProposal configurable)
            || Global.getField(configurable, "additionalProposalInfo") != null) //$NON-NLS-1$
            return null;
        String text = configurable.getReplacementString();
        if (text == null)
            return null;
        text = text.trim();
        if (text.endsWith(";")) //$NON-NLS-1$
            text = text.substring(0, text.length() - 1).trim();
        java.util.regex.Matcher call = PREDEFINED_VALUE_CALL.matcher(text);
        if (call.matches())
            text = call.group(1).trim();
        String[] parts = text.split("\\."); //$NON-NLS-1$
        String name = SmartContentAssistProcessor.parseProposalListName(raw.getDisplayString());
        if (parts.length < 2 || name == null || !parts[parts.length - 1].equalsIgnoreCase(name))
            return null;
        for (String part : parts)
        {
            if (part.isEmpty() || !Character.isLetter(part.charAt(0)) && part.charAt(0) != '_')
                return null;
        }
        if (parts.length == 2)
            return parts[0];
        String refType = parts.length == 3 ? PREDEFINED_REF_TYPES.get(parts[0].toLowerCase(java.util.Locale.ROOT)) : null;
        return refType != null ? refType + "." + parts[1] : null; //$NON-NLS-1$
    }

    /** У имени строки уже есть тип ({@code имя : <Тип> ~ Родитель}). */
    private static boolean hasNameType(String display)
    {
        int sep = display.indexOf(" ~ "); //$NON-NLS-1$
        return display.substring(0, sep >= 0 ? sep : display.length()).contains(" : "); //$NON-NLS-1$
    }

    /** Тип после имени строки: итоговый, или с {@code ?}, пока пересчёт идёт; {@code null} — как есть. */
    private static String decorateNameType(String display, FunctionReturnTypes.State state)
    {
        int sep = display.indexOf(" ~ "); //$NON-NLS-1$
        int nameEnd = sep >= 0 ? sep : display.length();
        if (state == null)
            return display;
        String type;
        if (state.fresh())
        {
            if (state.type().isEmpty())
                return display;
            type = state.type();
        }
        else
        {
            String preliminary = state.type();
            if (preliminary.length() >= 2 && preliminary.charAt(0) == '<'
                && preliminary.charAt(preliminary.length() - 1) == '>')
                preliminary = preliminary.substring(1, preliminary.length() - 1).trim();
            type = preliminary.isEmpty()
                ? "<" + FunctionReturnTypes.UNKNOWN + ">" //$NON-NLS-1$ //$NON-NLS-2$
                : "<" + FunctionReturnTypes.UNKNOWN + " | " + preliminary + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return display.substring(0, nameEnd) + " : " + type + display.substring(nameEnd); //$NON-NLS-1$
    }

    /**
     * Подпись строки с типом функции ({@link FunctionReturnTypes}). Пересчёта нет и итога нет —
     * подпись EDT как есть. Итог — рассчитанный по текущему состоянию модуля тип; пустой — тип EDT
     * без {@code ?}. Пока пересчёт ждёт или идёт — последний известный тип с {@code ?} первым
     * элементом: прежний итог, а без него — тип EDT из «Возвращаемое значение»
     * ({@code : <?, Строка>}); нет ни того, ни другого — {@code : <?>}.
     */
    private static String decorateFunctionType(String display, FunctionReturnTypes.Target target)
    {
        if (target == null || display == null)
            return display;
        int close = signatureClose(display, signatureOpen(display));
        if (close < 0)
            return display;
        int sep = display.indexOf(" ~ ", close); //$NON-NLS-1$
        int typeEnd = sep >= 0 ? sep : display.length();
        String rest = display.substring(close + 1, typeEnd).trim();
        String edtType = rest.startsWith(":") ? rest.substring(1).trim() : ""; //$NON-NLS-1$ //$NON-NLS-2$
        String head = display.substring(0, close + 1);
        String tail = display.substring(typeEnd);
        FunctionReturnTypes.State state = FunctionReturnTypes.state(target);
        if (state == null)
            return display;
        if (state.fresh())
            return state.type().isEmpty() ? display : head + " : " + state.type() + tail; //$NON-NLS-1$
        String preliminary = !state.type().isEmpty() ? state.type() : edtType;
        if (preliminary.length() >= 2 && preliminary.charAt(0) == '<'
            && preliminary.charAt(preliminary.length() - 1) == '>')
            preliminary = preliminary.substring(1, preliminary.length() - 1).trim();
        String type = preliminary.isEmpty()
            ? "<" + FunctionReturnTypes.UNKNOWN + ">" //$NON-NLS-1$ //$NON-NLS-2$
            : "<" + FunctionReturnTypes.UNKNOWN + " | " + preliminary + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return head + " : " + type + tail; //$NON-NLS-1$
    }

    /** Цвет типа в светлой теме: чуть синее обычного шрифта; в тёмной — {@link ThemeAwareColors#toEffectiveRgb}. */
    private static final RGB TYPE_LIGHT_RGB = new RGB(30, 60, 140);

    private static final Styler TYPE_STYLER = new Styler()
    {
        @Override
        public void applyStyles(TextStyle textStyle)
        {
            String key = "tormozit.assist.type." + (ThemeAwareColors.isDarkTheme() ? "dark" : "light"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ColorRegistry registry = JFaceResources.getColorRegistry();
            if (!registry.hasValueFor(key))
                registry.put(key, ThemeAwareColors.toEffectiveRgb(TYPE_LIGHT_RGB));
            textStyle.foreground = registry.get(key);
        }
    };

    /**
     * Тип, сильно совместимый с типом приёмника места: в светлой теме — чуть зеленее обычного шрифта (лёгкий
     * зелёный оттенок, той же силы, что синий {@link #TYPE_LIGHT_RGB}); в тёмной — {@link ThemeAwareColors#toEffectiveRgb}.
     */
    private static final RGB TYPE_MATCH_LIGHT_RGB = new RGB(30, 110, 60);

    private static final Styler TYPE_MATCH_STYLER = new Styler()
    {
        @Override
        public void applyStyles(TextStyle textStyle)
        {
            String key = "tormozit.assist.type.match." + (ThemeAwareColors.isDarkTheme() ? "dark" : "light"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ColorRegistry registry = JFaceResources.getColorRegistry();
            if (!registry.hasValueFor(key))
                registry.put(key, ThemeAwareColors.toEffectiveRgb(TYPE_MATCH_LIGHT_RGB));
            textStyle.foreground = registry.get(key);
        }
    };

    /**
     * Тип {@code Имя(…) : Тип ~ Родитель} — между {@code " : "} после сигнатуры и {@code " ~ "}. Тип, сильно
     * совместимый с типом приёмника ({@link BslExpectedTypePremiums#isStrongReceiverType}), — зелёным.
     */
    private static void applyTypeStyle(StyledString styled, ICompletionProposal proposal)
    {
        String display = styled.getString();
        int sep = display.indexOf(" ~ "); //$NON-NLS-1$
        int headEnd = sep >= 0 ? sep : display.length();
        int close = signatureClose(display, signatureOpen(display));
        int colon = display.indexOf(" : ", close >= 0 ? close + 1 : 0); //$NON-NLS-1$
        if (colon < 0 || colon + 3 >= headEnd)
            return;
        boolean match = BslExpectedTypePremiums.isStrongReceiverType(proposal, display.substring(colon + 3, headEnd));
        styled.setStyle(colon + 3, headEnd - colon - 3, match ? TYPE_MATCH_STYLER : TYPE_STYLER);
    }

    /** Владелец после {@code ~} — как у штатного assist EDT ({@link StyledString#QUALIFIER_STYLER}). */
    private static StyledString buildIrStyledDisplayString(String display)
    {
        if (display == null || display.isEmpty())
            return new StyledString(""); //$NON-NLS-1$
        int sep = display.indexOf(" ~ "); //$NON-NLS-1$
        if (sep < 0)
            return new StyledString(display);
        StyledString styled = new StyledString(display.substring(0, sep));
        styled.append(display.substring(sep), StyledString.QUALIFIER_STYLER);
        return styled;
    }

    @Override
    public String getDisplayString()
    {
        String overlapDisplay = resolveIrOverlapDisplayOverride();
        if (overlapDisplay != null)
            return SmartContentAssistProcessor.localizeAssistTypeNames(overlapDisplay);
        if (delegate instanceof SmartCompletionProposal)
            return ""; //$NON-NLS-1$
        String raw = delegate.getDisplayString();
        return localizeDisplayTypes(raw);

    }

    private String localizeDisplayTypes(String display)
    {
        String localized = SmartContentAssistProcessor.localizeAssistTypeNames(display);
        return FunctionReturnTypes.sortProposalTypeDisplay(localized,
            delegate instanceof ConfigurableCompletionProposal configurable
                ? Global.getField(configurable, "additionalProposalInfo") : null); //$NON-NLS-1$
    }

    /**
     * Подпись overlap EDT+ИР из кэша процессора — не сбрасывается при пересборке popup.
     */
    private String resolveIrOverlapDisplayOverride()
    {
        if (delegate instanceof IrCompletionProposal)
            return null;
        if (delegate instanceof SmartCompletionProposal)
            return null;
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return null;
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        if (processor == null)
            return null;
        String key = SmartContentAssistProcessor.dedupKeyForMerge(delegate);
        if (key.isEmpty())
            return null;
        return processor.resolveIrOverlapDisplay(delegate.getDisplayString(), key);

    }

    @Override
    public Image getImage()
    {
        return delegate.getImage();

    }

    @Override
    public Point getSelection(IDocument document)
    {
        Point selection;
        if (templateSelection != null)
        {
            selection = templateSelection;
            templateSelection = null;
        }
        else if (wordOnlyCaret >= 0)
        {
            int caret = wordOnlyCaret;
            wordOnlyCaret = -1;
            selection = new Point(caret, 0);
        }
        else if (overlapIrCaret >= 0)
        {
            int caret = overlapIrCaret;
            overlapIrCaret = -1;
            selection = new Point(caret, 0);
        }
        else
            selection = delegate.getSelection(document);
        return selection;
    }

    @Override
    public IContextInformation getContextInformation()
    {
        return delegate.getContextInformation();
    }

    @Override
    public void apply(IDocument document)
    {
        int caret = resolveApplyCaret(document, -1);
        EqualsSpacePad pad = EqualsSpacePad.install(document, caret, delegate);
        boolean irApply = delegate instanceof IrCompletionProposal;
        boolean replaceParentOverlap = isEdtOverlapReplaceParent();
        beginProposalApply(document, irApply, replaceParentOverlap);
        try
        {
            rebaseInspectExpressionReplacement(document, null, caret);
            rebaseEdtTemplateReplacement(document, null, caret);
            if (replaceParentOverlap && tryApplyEdtOverlapReplaceParent(document, null, -1))
                return;
            if (tryApplyWordOnly(document, null, -1, null))
                return;
            if (tryApplyTemplate(document, null, caret, null))
                return;
            if (irApply)
            {
                applyIrBareCtorOrDelegate(document, null, (char) 0, 0, caret);
                return;
            }
            delegate.apply(document);
        }
        finally
        {
            endProposalApply();
            pad.scheduleRestore();
        }
    }

    // ---- ICompletionProposalExtension ---------------------------------------

    @Override
    public void apply(IDocument document, char trigger, int offset)
    {
        int caret = resolveApplyCaret(document, offset);
        EqualsSpacePad pad = EqualsSpacePad.install(document, caret, delegate);
        boolean irApply = delegate instanceof IrCompletionProposal;
        boolean replaceParentOverlap = isEdtOverlapReplaceParent();
        beginProposalApply(document, irApply, replaceParentOverlap);
        try
        {
            rebaseInspectExpressionReplacement(document, null, caret);
            rebaseEdtTemplateReplacement(document, null, caret);
            if (replaceParentOverlap && tryApplyEdtOverlapReplaceParent(document, null, offset))
                return;
            if (tryApplyWordOnly(document, null, offset, null))
                return;
            if (tryApplyTemplate(document, null, caret, null))
                return;
            if (irApply)
            {
                applyIrBareCtorOrDelegate(document, null, trigger, 0, caret);
                return;
            }
            if (delegate instanceof ICompletionProposalExtension)
                ((ICompletionProposalExtension) delegate).apply(document, trigger, offset);
            else
                delegate.apply(document);
        }
        finally
        {
            endProposalApply();
            pad.scheduleRestore();
        }
    }

    @Override
    public boolean isValidFor(IDocument document, int offset)
    {
        return matchesFilter(document, offset, null);
    }

    @Override
    public char[] getTriggerCharacters()
    {
        if (delegate instanceof ICompletionProposalExtension)
            return ((ICompletionProposalExtension) delegate).getTriggerCharacters();
        return null;
    }

    @Override
    public int getContextInformationPosition()
    {
        if (delegate instanceof ICompletionProposalExtension)
            return ((ICompletionProposalExtension) delegate).getContextInformationPosition();
        return -1;
    }

    // ---- ICompletionProposalExtension2 --------------------------------------

    @Override
    public void apply(ITextViewer viewer, char trigger, int stateMask, int offset)
    {
        discardPreviousStockCaretRestore(viewer);
        IDocument document = viewer != null ? viewer.getDocument() : null;
        int caret = resolveApplyCaret(document, offset);
        EqualsSpacePad pad = EqualsSpacePad.install(document, caret, delegate);
        boolean irApply = delegate instanceof IrCompletionProposal;
        boolean replaceParentOverlap = isEdtOverlapReplaceParent();
        beginProposalApply(document, irApply, replaceParentOverlap);
        try
        {
            rebaseInspectExpressionReplacement(document, viewer, caret);
            rebaseEdtTemplateReplacement(document, viewer, caret);
            if (replaceParentOverlap && tryApplyEdtOverlapReplaceParent(document, viewer, offset))
                return;
            if (viewer != null && tryApplyWordOnly(document, viewer, offset, stateMask))
                return;
            if (irApply
                && tryApplyWithIrAdapter((IrCompletionProposal) delegate, viewer, offset))
                return;
            if (!irApply && viewer != null && tryApplyGeneralIrOverlapAdapter(viewer, offset))
                return;
            if (tryApplyTemplate(document, viewer, caret, stateMask))
                return;
            if (irApply)
            {
                applyIrBareCtorOrDelegate(document, viewer, trigger, stateMask, caret);
                return;
            }
            if (delegate instanceof ICompletionProposalExtension2)
                ((ICompletionProposalExtension2) delegate).apply(viewer, trigger, stateMask, offset);
            else if (document != null)
                apply(document, trigger, offset);
        }
        finally
        {
            endProposalApply();
            pad.scheduleRestore();
        }
    }

    /**
     * EDT DataEvent.doIt после LinkedModeUI.enter ставит одноразовый слушатель
     * BslProposalProvider$2 с координатами первого поля. Если событие выделения
     * не пришло, слушатель переживает вставку и при следующем автодополнении
     * возвращает каретку в старое поле.
     * Перед новой вставкой снимаем только оставшиеся слушатели прошлого DataEvent.
     * Новые, созданные текущей вставкой, сохраняются для штатного LinkedMode.
     * Класс и поля подтверждены в .tmp/bundles/bsl-ui-full-22 и исходниках JFace.
     */
    private void discardPreviousStockCaretRestore(ITextViewer viewer)
    {
        ISelectionProvider provider = viewer != null ? viewer.getSelectionProvider() : null;
        if (!(Global.getField(provider, "selectionChangedListeners") instanceof ListenerList<?> listeners)) //$NON-NLS-1$
            return;
        for (Object listener : listeners.getListeners())
        {
            if (!(listener instanceof ISelectionChangedListener selectionListener)
                || !listener.getClass().getName().equals(
                    "com._1c.g5.v8.dt.bsl.ui.contentassist.BslProposalProvider$2") //$NON-NLS-1$
                || Global.getField(listener, "val$viewer") != viewer) //$NON-NLS-1$
                continue;
            provider.removeSelectionChangedListener(selectionListener);
        }
    }

    /**
     * Issue 562: EDT-строка, заместившая при merge слово ИР с тем же ключом (напр. одна из
     * перегрузок {@code НСтр(...)} вместо общей строки ИР), обязана так же вызвать
     * {@code Адаптер_ПриВыбореСтрокиАвтодополнения} — иначе внешние инструменты ИР не видят
     * событие выбора для отображённых по факту слов. Текст вставляет EDT {@code delegate.apply}
     * как обычно (сигнатура/LinkedMode перегрузки — ценность, ради которой merge оставил именно
     * эту строку); адаптер переопределяет вставку сам, только если это решил он
     * ({@link #tryApplyWithIrAdapter}).
     */
    private boolean tryApplyGeneralIrOverlapAdapter(ITextViewer viewer, int offset)
    {
        // issue 562: findIrOverlapForEdt(delegate) здесь напрямую не годится — apply()
        // выполняется уже после assistSessionEnded (см. javadoc у #overlapIr), поэтому
        // используем то, что resolveOverlapIr взял из кэша #selected.
        IrCompletionProposal ir = resolveOverlapIr();
        if (ir == null)
            return false;
        IR_PROPOSAL_APPLY_IN_PROGRESS.set(Boolean.TRUE);
        boolean handled = tryApplyWithIrAdapter(ir, viewer, offset);
        if (handled)
            captureOverlapIrCaret(ir);
        else
            IR_PROPOSAL_APPLY_IN_PROGRESS.remove();
        return handled;
    }

    /** Запрос готовых типов этой строки: текущая сразу, прочие — после паузы ввода. */
    void requestRowTypes(boolean current)
    {
        FunctionReturnTypes.requestFunction(functionTarget(), current);
        FunctionReturnTypes.requestVariable(variableTarget(), current);
        if (delegate instanceof ConfigurableCompletionProposal configurable)
            FunctionReturnTypes.requestProperty(Global.getField(configurable, "additionalProposalInfo")); //$NON-NLS-1$
    }

    /** Активация строки assist (Eclipse {@code selected}), не подтверждение {@code apply}. */
    @Override
    public void selected(ITextViewer viewer, boolean smartToggle)
    {
        logProposalAtSelection(viewer);
        stashOverlapReplaceParentIr();
        if (delegate instanceof IrCompletionProposal ir)
            scheduleIrWordActivation(ir);
        else
        {
            ensureLiteralOverlapBrowserIfNeeded(viewer);
            scheduleEdtRowActivation(null);
            requestRowTypes(true);
        }
        if (!(delegate instanceof SmartCompletionProposal)
            && delegate instanceof ICompletionProposalExtension2 ext2)
        {
            // При smart-фильтрации Ctrl+Space переключает «Фильтр». Не передаём
            // Xtext smart toggle: selected(true) выделяет контекст замены и запускает
            // перефильтрацию JFace ещё до команды, сдвигая её filterOffset.
            boolean delegateSmartToggle = smartToggle
                && !(ComfortSettings.isReplaceListFiltersEnabled()
                    && delegate instanceof ConfigurableCompletionProposal);
            ext2.selected(viewer, delegateSmartToggle);

        }

    }

    @Override
    public void unselected(ITextViewer viewer)
    {
        if (delegate instanceof ICompletionProposalExtension2
            && !(delegate instanceof SmartCompletionProposal))
            ((ICompletionProposalExtension2) delegate).unselected(viewer);

    }

    @Override
    public boolean validate(IDocument document, int offset, DocumentEvent event)
    {
        return matchesFilter(document, offset, event);
    }

    // ---- ICompletionProposalExtension4 --------------------------------------

    private void beginProposalApply(IDocument document, boolean irApply, boolean replaceParentOverlap)
    {
        PROPOSAL_APPLY_IN_PROGRESS.set(Boolean.TRUE);
        if (irApply || replaceParentOverlap)
            IR_PROPOSAL_APPLY_IN_PROGRESS.set(Boolean.TRUE);
        boolean replaceParent = replaceParentOverlap
            || irApply && ((IrCompletionProposal) delegate).isReplaceParentOnInsert();
        if (replaceParent)
            IR_REPLACE_PARENT_APPLY.set(Boolean.TRUE);
        SmartContentAssistProcessor.bindCtorFakeCtorBeforeApply(delegate, document);
        // Пустая строка-минимум EDT не создаёт поле LinkedMode для необязательного параметра.
        // getSelection() штатного proposal берёт позицию из cursorPosition.
        ConfigurableCompletionProposal cp = asConfigurable(delegate);
        if (!irApply && !replaceParentOverlap && cp != null)
        {
            int[] params = moduleParams();
            String replacement = cp.getReplacementString();
            int open = replacement != null ? replacement.indexOf('(') : -1;
            if (params != null && params[0] == 0 && params[1] > 0 && open >= 0
                && replacement.indexOf(')', open + 1) == open + 1)
                cp.setCursorPosition(open + 1);
        }
    }

    private static void endProposalApply()
    {
        SmartContentAssistProcessor.clearCtorPendingApplyState();
        IR_REPLACE_PARENT_APPLY.remove();
        IR_PROPOSAL_APPLY_IN_PROGRESS.remove();
        PROPOSAL_APPLY_IN_PROGRESS.remove();
    }

    /** Сейчас выполняется {@link #apply}. */
    static boolean isAnyApplyInProgress()
    {
        return Boolean.TRUE.equals(PROPOSAL_APPLY_IN_PROGRESS.get())
            || Boolean.TRUE.equals(IR_PROPOSAL_APPLY_IN_PROGRESS.get());
    }

    /** Overlap EDT+ИР: слово ИР содержит разделитель варианта — вставка с заменой родителя. */
    private boolean isEdtOverlapReplaceParent()
    {
        return resolveOverlapReplaceParentIr() != null;
    }

    private IrCompletionProposal resolveOverlapReplaceParentIr()
    {
        if (delegate instanceof IrCompletionProposal)
            return null;
        IrCompletionProposal ir = resolveOverlapIr();
        return ir != null && ir.isReplaceParentOnInsert() ? ir : null;
    }

    /** ИР-слово overlap для текущей EDT-строки: сперва кэш из {@link #selected}, иначе live-поиск. */
    private IrCompletionProposal resolveOverlapIr()
    {
        if (delegate instanceof IrCompletionProposal)
            return null;
        if (overlapIr != null)
            return overlapIr;
        return findIrOverlapForEdt(delegate);
    }

    private void stashOverlapReplaceParentIr()
    {
        if (delegate instanceof IrCompletionProposal)
            return;
        overlapIr = findIrOverlapForEdt(delegate);

    }

    private boolean tryApplyEdtOverlapReplaceParent(IDocument document, ITextViewer viewer,
        int offset)
    {
        IrCompletionProposal ir = resolveOverlapReplaceParentIr();
        if (ir == null)
            return false;
        IDocument doc = document != null ? document
            : viewer != null ? viewer.getDocument() : null;
        if (viewer != null && tryApplyWithIrAdapter(ir, viewer, offset))
        {
            captureOverlapIrCaret(ir);
            return true;
        }
        int caret = offset >= 0 ? offset : resolveApplyCaretOffset(doc);
        applyIrInsertion(ir, doc, null, caret, false, false, "", false, null); //$NON-NLS-1$
        captureOverlapIrCaret(ir);
        return true;
    }

    private void captureOverlapIrCaret(IrCompletionProposal ir)
    {
        if (ir == null)
            return;
        Point sel = ir.getSelection(null);
        if (sel != null)
            overlapIrCaret = sel.x;
    }

    /**
     * Порт RDT {@code ПриВыбореЗначенияТ9}: перехват вставки ИР-предложения через
     * {@code Адаптер_ПриВыбореСтрокиАвтодополнения}.
     *
     * @return {@code true} если вставка выполнена/подавлена адаптером
     */
    private boolean tryApplyWithIrAdapter(IrCompletionProposal ir, ITextViewer viewer, int offset)
    {
        // Bare-ctor ИР (Структура/Массив): не через адаптер — только имя, без ()/("").
        if (isBareCtorIrWord(ir))
        {
            return false;
        }

        BslXtextEditor activeBslEditor = GetRef.getActiveBslEditor(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActivePart());
        IRSession session = IrBslExpressionHtmlSupport.resolveConnectedSession(activeBslEditor);
        if (session == null)
            return false;

        IDocument document = viewer != null ? viewer.getDocument() : null;
        if (document == null)
            return false;

        long started = System.currentTimeMillis();
        IRSession.CompletionAdapterResult result;
        try
        {
            final String tmpl = ir.getTemplateText();
            result = session.executeOnComThread(() ->
                session.invokeCompletionAdapter(
                    ir.getWordValue(), ir.isMethod(), ir.getDictionaryKey(), tmpl));
        }
        catch (Exception e)
        {
            IrCompletionDebug.problem("адаптер apply: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
        IrCompletionDebug.timing("Адаптер_ПриВыбореСтрокиАвтодополнения", started); //$NON-NLS-1$

        if (result == null)
            return false;

        // Отступ строки вставки — вычисляем ДО любых изменений документа
        String lineIndent = result.formatText ? computeLineIndent(document, offset) : ""; //$NON-NLS-1$

        // Вычисляем диапазон удаления если генератор с поглощением
        int deleteFrom = -1;
        int deleteTo   = -1;
        if (result.newTemplate != null || result.isGeneratorWithLineStart) {
           int[] range = resolveDeleteRange(result, document, session, offset, viewer, ir);
            deleteFrom = range[0];
            deleteTo   = range[1];
        }
        // Удаляем диапазон перед вставкой
        boolean didDeleteRange = false;
        int insertOffset = offset;
        if (deleteFrom >= 0 && deleteTo > deleteFrom)
        {
            try
            {
                document.replace(deleteFrom, deleteTo - deleteFrom, ""); //$NON-NLS-1$
                insertOffset = deleteFrom;
                didDeleteRange = true;
            }
            catch (BadLocationException e)
            {
                IrCompletionDebug.problem("адаптер deleteRange: " + e.getMessage()); //$NON-NLS-1$
            }
        }

        // НовыйШаблон <> Неопределено
        if (result.newTemplate != null)
        {
            if (result.newTemplate.isEmpty())
            {
                // Отказ — генератор вернул пустой текст
                Display.getDefault().asyncExec(() ->
                    ToastNotification.show(
                        IRApplication.toastTitle(),
                        "Генератор вернул пустой текст для вставки", 3_000)); //$NON-NLS-1$
                return true;
            }
            String template = normalizeBareCtorIrTemplate(ir, result.newTemplate);
            applyIrInsertion(ir, document, template, insertOffset, didDeleteRange, result.formatText, lineIndent, result.isGeneratorWithLineStart, activeBslEditor);
            return true;
        }

        // НовыйШаблон = Неопределено, без поглощения — стандартная обработка
        if (!didDeleteRange)
            return false;

        // НовыйШаблон = Неопределено, диапазон удалён — вставляем стандартный план
        applyIrInsertion(ir, document, null, insertOffset, true, result.formatText, lineIndent, result.isGeneratorWithLineStart, activeBslEditor);
        return true;
    }

    /**
     * ИР-строка bare-ctor ({@code Структура}/{@code Массив}): вставляем имя без {@code ("")}
     * и без принудительных {@code ()} от {@code ЛиМетод}, иначе пересечение с EDT DataEvent.
     */
    private void applyIrBareCtorOrDelegate(IDocument document, ITextViewer viewer, char trigger,
        int stateMask, int offset)
    {
        IrCompletionProposal ir = (IrCompletionProposal) delegate;
        if (document == null && viewer != null)
            document = viewer.getDocument();
        if (isBareCtorIrWord(ir))
        {
            int caret = offset >= 0 ? offset : resolveApplyCaretOffset(document);
            String bare = ir.getFilterName() != null && !ir.getFilterName().isEmpty()
                ? ir.getFilterName()
                : ir.getWordValue();
            applyIrInsertion(ir, document, bare, caret, false, false, "", false, null); //$NON-NLS-1$
            return;
        }
        if (viewer != null && delegate instanceof ICompletionProposalExtension2)
            ((ICompletionProposalExtension2) delegate).apply(viewer, trigger, stateMask, offset);
        else if (document != null && delegate instanceof ICompletionProposalExtension && offset >= 0)
            ((ICompletionProposalExtension) delegate).apply(document, trigger, offset);
        else if (document != null)
            delegate.apply(document);
    }

    private static boolean isBareCtorIrWord(IrCompletionProposal ir)
    {
        if (ir == null)
            return false;
        String name = ir.getFilterName();
        if (name == null || name.isEmpty())
            name = ir.getWordValue();
        if (name == null || name.isEmpty())
            return false;
        String tmpl = ir.getTemplateText();
        if (tmpl != null && tmpl.indexOf('(') >= 0)
            return false;
        return SmartContentAssistProcessor.isBareConstructorTypeName(name);
    }

    /**
     * Если адаптер/шаблон всё же дал {@code Имя("")} для bare-ctor — свести к голому имени.
     */
    private static String normalizeBareCtorIrTemplate(IrCompletionProposal ir, String template)
    {
        if (template == null || ir == null || !isBareCtorIrWord(ir))
            return template;
        String name = ir.getFilterName();
        if (name == null || name.isEmpty())
            name = ir.getWordValue();
        if (name == null)
            return template;
        String trimmed = template.trim();
        String emptyCall = name + "(\"\")"; //$NON-NLS-1$
        String emptyMarker = name + "(\"<?>\")"; //$NON-NLS-1$
        String emptyBang = name + "(\"<!>\")"; //$NON-NLS-1$
        String emptyParens = name + "()"; //$NON-NLS-1$
        if (trimmed.equalsIgnoreCase(emptyCall)
            || trimmed.equalsIgnoreCase(emptyMarker)
            || trimmed.equalsIgnoreCase(emptyBang)
            || trimmed.equalsIgnoreCase(emptyParens))
            return name;
        return template;
    }

    /**
     * Вычисляет диапазон [from, to) для удаления перед вставкой генератора.
     * <p>Порт блока {@code ЛиГенераторСПоглощениемНачалаСтроки} из {@code ПриВыбореЗначенияТΟ}.
     *
     * @return массив [deleteFrom, deleteTo]; оба -1 если не вычислено
     */
    private static int[] resolveDeleteRange(IRSession.CompletionAdapterResult result,
        IDocument document, IRSession session, int completionOffset, ITextViewer viewer,
        IrCompletionProposal ir)
    {
        // Приоритет — мЗаменяемыйДиапазон, уже прочитаный на COM-потоке
        if (result.deleteFromLf >= 0 && result.deleteToLf > result.deleteFromLf)
        {
            String raw = session.lastSyncedRawText;
            int from = Global.remapOffsetFromLf(raw, result.deleteFromLf);
            int to   = Global.remapOffsetFromLf(raw, result.deleteToLf);
            return new int[] { from, to };
        }
        // Порт: УдалитьТекстС = Позиция - СтрДлина(СокрЛ(СтрокаСлева))
        //        УдалитьТекстПо = Позиция + СтрДлина(ВыделенныйТекст)
        try
        {
            int prefixStart = ir != null && ir.isReplaceParentOnInsert()
                ? IrCompletionProposal.computeMaskPrefixStart(document, completionOffset)
                : SmartContentAssistProcessor.computeIdentifierWordStart(
                    document, completionOffset);
            String effectivePrefix = document.get(prefixStart, completionOffset - prefixStart);
            int deleteFrom = completionOffset - effectivePrefix.length();
            if (result.isGeneratorWithLineStart)
            {
                deleteFrom = document.getLineOffset(
                    document.getLineOfOffset(completionOffset));
            }
            int selLen = viewer instanceof SourceViewer sv
                ? Math.max(0, sv.getSelectedRange().y) : 0;
            int deleteTo = completionOffset + selLen;
            return new int[] { deleteFrom, deleteTo };
        }
        catch (BadLocationException e)
        {
            IrCompletionDebug.problem("адаптер resolveRange fallback: " + e.getMessage()); //$NON-NLS-1$
            return new int[] { -1, -1 };
        }
    }

    /**
     * Вставляет текст в документ по итогам адаптера.
     * <p>Если {@code template != null} — использует его (адаптерный шаблон от ИР);
     * иначе — стандартный план proposal ({@link IrCompletionProposal#buildInsertPlan()}).
     * <p>Если {@code rangeAlreadyDeleted=true}, перед вставкой ничего не удаляется;
     * иначе заменяет идентификаторный префикс перед {@code insertOffset}.
     *
     * @return длина вставленного текста; {@code 0} при ошибке
     */
    private static void applyIrInsertion(IrCompletionProposal ir, IDocument document,
        String template, int insertOffset, boolean rangeAlreadyDeleted, boolean formatText,
        String lineIndent, boolean indentFirstLine, BslXtextEditor editor)
    {
        if (document == null || ir == null)
            return;
        IrCompletionProposal.InsertPlan plan = template != null
            ? IrCompletionProposal.planFromTemplate(template)
            : ir.buildInsertPlan();
        if (isBareCtorIrWord(ir))
        {
            String source = plan.text;
            String normalized = normalizeBareCtorIrTemplate(ir, source);
            if (normalized != null && !normalized.equals(source))
                plan = new IrCompletionProposal.InsertPlan(normalized, normalized.length());
        }
        int replaceFrom;
        int replaceLen;
        // Для явного шаблона (в т.ч. bare-имя) — заменить идентификаторный префикс.
        // template==null после deleteRange адаптера — только вставка в caret.
        if (rangeAlreadyDeleted)
        {
            replaceFrom = insertOffset;
            replaceLen = 0;
        }
        else
        {
            if (ir.isReplaceParentOnInsert())
                replaceFrom = IrCompletionProposal.computeMaskPrefixStart(document, insertOffset);
            else
                replaceFrom = SmartContentAssistProcessor.computeIdentifierWordStart(
                    document, insertOffset);
            replaceLen = Math.max(0, insertOffset - replaceFrom);
            padIrPlanAfterEquals(document, insertOffset, plan);
        }
        // Отступ вычисляем ДО вставки, по полной строке (не обрезая по каретке)
        try
        {
            document.replace(replaceFrom, replaceLen, plan.text);
            int insertedLength = plan.text.length();
            if (formatText && !lineIndent.isEmpty() && insertedLength > 0)
            {
                formatInsertedRegion(replaceFrom, insertedLength, lineIndent, indentFirstLine, editor);
                if (indentFirstLine)
                {
                    plan.caretOffset += lineIndent.length();
                }
            }
            ir.setPendingCaretAfterApply(replaceFrom + plan.caretOffset);
        }
        catch (BadLocationException e)
        {
            IrCompletionDebug.problem("адаптер applyInsertion: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Форматирует вставленный фрагмент штатным форматтером EDT/BSL.
     * <p>Выделяет регион {@code [offset, offset+length)}, вызывает
     * {@code viewer.doOperation(FORMAT)} и откладывает через {@code asyncExec},
     * чтобы не конфликтовать с завершением сессии автодополнения.
     */
    /**
     * Выравнивает вставленный многострочный фрагмент по отступу строки вставки.
     * <p>Берёт лидирующие пробельные символы строки, в которую попал {@code offset},
     * и добавляет их в начало каждой непервой строки вставленного текста.
     * Работает независимо от AST и применимо к любому тексту.
     */
    /**
     * Выравнивает вставленный многострочный фрагмент по заранее вычисленному отступу.
     * Добавляет {@code lineIndent} в начало каждой непервой строки вставки.
     *
     * @param lineIndent лидирующие пробельные символы строки вставки,
     *                   захваченные до выполнения вставки
     */
    private static void formatInsertedRegion(int offset, int length, String lineIndent,
        boolean indentFirstLine, BslXtextEditor activeBslEditor)
    {
        if (activeBslEditor == null || length <= 0 || lineIndent.isEmpty())
            return;
        org.eclipse.jface.text.source.ISourceViewer viewer =
            activeBslEditor.getInternalSourceViewer();
        if (viewer == null)
            return;
        IDocument document = viewer.getDocument();
        if (document == null)
            return;
        try
        {
            String inserted = document.get(offset, length);
            // Непервые строки — всегда добавляем отступ
            String formatted = inserted.replace("\n", "\n" + lineIndent); //$NON-NLS-1$ //$NON-NLS-2$
            // Первая строка — только если генератор начинается с начала строки
            if (indentFirstLine)
                formatted = lineIndent + formatted;
            if (!formatted.equals(inserted))
                document.replace(offset, length, formatted);
        }
        catch (BadLocationException e)
        {
            IrCompletionDebug.problem("formatInsertedRegion: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Возвращает лидирующие пробельные символы полной строки, содержащей {@code offset}.
     * Использует всю строку целиком — не обрезает по позиции каретки.
     */
    private static String computeLineIndent(IDocument document, int offset)
    {
        try
        {
            int line           = document.getLineOfOffset(offset);
            org.eclipse.jface.text.IRegion lineInfo = document.getLineInformation(line);
            return leadingWhitespace(document.get(lineInfo.getOffset(), lineInfo.getLength()));
        }
        catch (BadLocationException e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    /** Возвращает лидирующие пробельные символы строки (до первого непробельного). */
    private static String leadingWhitespace(String line)
    {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i)))
            i++;
        return line.substring(0, i);
    }

    // ---- ICompletionProposalExtension4 --------------------------------------

    @Override
    public boolean isAutoInsertable()
    {
        if (delegate instanceof ICompletionProposalExtension4)
            return ((ICompletionProposalExtension4) delegate).isAutoInsertable();
        return true;
    }

    // ---- ICompletionProposalExtension3 / 5 / 6 ------------------------------

    @Override
    public String getAdditionalProposalInfo()
    {
        if (delegate instanceof IrCompletionProposal ir)
            return resolveIrAdditionalProposalInfo(ir);
        Object baseInfo = readDelegateEdtAdditionalInfo(new NullProgressMonitor());
        return objectToSideHintString(withComputedFunctionType(
            BslExpectedTypePremiums.withReceiverType(resolveEdtAssistSideHint(baseInfo))));

    }

    private static String objectToSideHintString(Object info)
    {
        if (IrBslHoverHtml.isBslBrowserInput(info))
            return IrBslHoverHtml.readDisplayHtml(info);
        if (info instanceof String)
            return (String) info;
        if (info instanceof StyledString)
            return ((StyledString) info).getString();
        return info != null ? info.toString() : null;
    }

    @Override
    public IInformationControlCreator getInformationControlCreator()
    {
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        if (delegate instanceof IrCompletionProposal)
            return IrBslHoverHtml.wrapCreator(BslCompletionSideHintResolver.resolveAssistBrowserCreatorForProposal(
                assistant, delegate));
        if (isEdtIrOverlapRow())
            return IrBslHoverHtml.wrapCreator(BslCompletionSideHintResolver.resolveAssistBrowserCreatorForProposal(
                assistant, delegate));
        // Строка без описания EDT получает от нас HTML («Приемник.Тип», замечание о шаблонном типе) — панель
        // браузерная, как у слов ИР; штатная текстовая показала бы разметку текстом.
        if (delegate instanceof ConfigurableCompletionProposal configurable
            && Global.getField(configurable, "additionalProposalInfo") == null //$NON-NLS-1$
            && (BslExpectedTypePremiums.hasReceiverLine()
                || PlatformTemplateTypes.noteText(SmartContentAssistProcessor.filterMatchName(delegate)) != null))
            return IrBslHoverHtml.wrapCreator(BslCompletionSideHintResolver.resolveAssistBrowserCreatorForProposal(
                assistant, delegate));
        if (delegate instanceof ICompletionProposalExtension3 ext3)
            return IrBslHoverHtml.wrapCreator(ext3.getInformationControlCreator());
        return null;
    }

    /** EDT-строка с тем же ключом, что и слово ИР (overlap в литеральном assist). */
    private boolean isEdtIrOverlapRow()
    {
        if (delegate instanceof IrCompletionProposal)
            return false;
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return false;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader == null)
            return false;
        if (IrBslExpressionHtmlSupport.resolveConnectedSession(reloader.getBslEditor()) == null)
            return false;
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        if (processor == null || !processor.hasIrProposalsForCurrentContext())
            return false;
        if (processor.findIrProposalForMergeMatch(delegate) != null)
            return true;
        String cacheKey = BslCompletionSideHintResolver.resolveIrCacheKey(delegate);
        if (cacheKey != null && !cacheKey.isEmpty())
        {
            String merged = reloader.getIrMergedHtml(cacheKey);
            if (merged != null && !merged.isEmpty())
                return true;
        }
        return false;
    }

    /** Боковая подсказка assist: browser для ИР и overlap EDT+ИР. */
    public boolean usesIrAssistBrowserForSideHint()
    {
        return delegate instanceof IrCompletionProposal || isEdtIrOverlapRow();
    }

    /** H41: идентичность proposal при {@code selected()} в literal assist. */
    private void logProposalAtSelection(ITextViewer viewer)
    {
        if (!(viewer instanceof SourceViewer sourceViewer))
            return;
        int caret = SmartContentAssistProcessor.resolveWidgetCaret(sourceViewer);
        if (caret < 0 || !SmartContentAssistProcessor.isStringLiteralAssistContext(
            sourceViewer, caret))
            return;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader == null)
            return;
        ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(this);
        String displayKey = delegate.getDisplayString() != null ? delegate.getDisplayString() : ""; //$NON-NLS-1$
        String delegateClass = raw != null ? raw.getClass().getSimpleName()
            : delegate.getClass().getSimpleName();
        boolean hasIrMergedHtml = hasIrMergedHtmlForSelection(reloader, raw);
        boolean setupPhase = reloader.isLiteralOpenSetupPhase();
        boolean literalFinishPinPending = reloader.isLiteralFinishPinPending();
        int gen = reloader.getLiteralOpenGen();
        long ms = reloader.msSinceLiteralOpen();

    }

    private static boolean hasIrMergedHtmlForSelection(ContentAssistSessionReloader reloader,
        ICompletionProposal raw)
    {
        if (reloader == null || raw == null)
            return false;
        String cacheKey = BslCompletionSideHintResolver.resolveIrCacheKey(raw);
        if (cacheKey != null && !cacheKey.isEmpty())
        {
            String merged = reloader.getIrMergedHtml(cacheKey);
            if (merged != null && !merged.isEmpty())
                return true;
        }
        String display = raw.getDisplayString();
        if (display != null && !display.isEmpty())
        {
            String merged = reloader.getIrMergedHtml(display);
            return merged != null && !merged.isEmpty();
        }
        return false;
    }

    private void ensureLiteralOverlapBrowserIfNeeded(ITextViewer viewer)
    {
        if (!isEdtIrOverlapRow())
            return;
        if (!(viewer instanceof SourceViewer sourceViewer))
            return;
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        if (assistant == null)
            return;
        int caret = SmartContentAssistProcessor.resolveWidgetCaret(sourceViewer);
        if (caret < 0 || !SmartContentAssistProcessor.isStringLiteralAssistContext(
            sourceViewer, caret))
            return;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader != null && reloader.isLiteralOpenSetupPhase())
            return;
        if (!ContentAssistPopupSync.hasAssistBrowserSidePanel(assistant))
            ContentAssistPopupSync.ensureAssistBrowserCreatorOnController(assistant, sourceViewer);

    }

    @Override
    public Object getAdditionalProposalInfo(IProgressMonitor monitor)
    {
        if (delegate instanceof IrCompletionProposal ir)
            return resolveIrAdditionalProposalInfo(ir);
        Object baseInfo = readDelegateEdtAdditionalInfo(monitor);
        // Строка типа штатного списка без обработчика подсказки: сам тип — в additionalProposalInfo.
        Object element = SmartContentAssistProcessor.unwrapProposal(delegate) instanceof ConfigurableCompletionProposal configurable
            ? Global.getField(configurable, "additionalProposalInfo") : null; //$NON-NLS-1$
        // Замечание о типе — до «Приемник.Тип»: та строка всегда первая.
        Object described = PlatformTemplateTypes.withNote(withParentLine(resolveEdtAssistSideHint(baseInfo)), element,
            SmartContentAssistProcessor.filterMatchName(delegate));
        return withComputedFunctionType(BslExpectedTypePremiums.withReceiverType(described));

    }

    private Object withComputedFunctionType(Object info)
    {
        return FunctionReturnTypes.withFunctionReturnType(info, functionTarget(),
            ContentAssistSessionReloader.getActiveViewer());
    }

    /** Штатное описание переменной после пересчёта её метода; чтение только в фоне. */
    void refreshNativeVariableSideHint(ContentAssistant assistant)
    {
        if (variableTarget() == null)
            return;
        var job = new org.eclipse.core.runtime.jobs.Job("Комфорт: обновление типа переменной")
        {
            @Override
            protected org.eclipse.core.runtime.IStatus run(IProgressMonitor monitor)
            {
                Object info = getAdditionalProposalInfo(monitor);
                if (info != null)
                    Display.getDefault().asyncExec(() ->
                    {
                        if (assistant != ContentAssistSessionReloader.getActiveAssistant()
                            || !ContentAssistPopupSync.isPopupVisible(assistant))
                            return;
                        var rows = ContentAssistPopupSync.visibleProposals(assistant);
                        var table = ContentAssistPopupSync.getProposalTable(ContentAssistPopupSync.getPopupObject(assistant));
                        int selected = table != null && !table.isDisposed() ? table.getSelectionIndex() : -1;
                        if (rows != null && selected >= 0 && selected < rows.size()
                            && rows.get(selected) == SmartCompletionProposal.this)
                        {
                            ContentAssistPopupSync.pinMergedAdditionalInfo(assistant, null, info);
                        }
                    });
                return org.eclipse.core.runtime.Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.setRule(ForeignModuleModel.SERIAL);
        job.schedule();
    }

    private static final String PARENT_LINE_CLASS = "comfort-row-parent"; //$NON-NLS-1$

    /**
     * Боковая подсказка строки, когда у строк списка больше одного родителя (например, после {@code База_.} группы
     * общих модулей): сверху {@code Родитель: <родитель строки>} — часть подписи после {@code ~}. Строку
     * {@code Приемник.Тип} {@link BslExpectedTypePremiums#withReceiverType} ставит выше неё.
     */
    private Object withParentLine(Object info)
    {
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        if (processor == null || !processor.hasSeveralParentTypes())
            return info;
        ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(delegate);
        String parent = raw != null ? SmartContentAssistProcessor.extractParentType(raw.getDisplayString()) : null;
        if (parent == null || parent.isEmpty())
            return info;
        String line = "<div class=\"" + PARENT_LINE_CLASS + "\"><b>Родитель</b>: " //$NON-NLS-1$ //$NON-NLS-2$
            + parent.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</div>"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        if (info == null)
            return IrBslHoverHtml.mergeHtml("", line); //$NON-NLS-1$
        boolean browserInput = IrBslHoverHtml.isBslBrowserInput(info);
        String html = browserInput ? IrBslHoverHtml.readDisplayHtml(info) : info instanceof String text ? text : null;
        if (html == null || html.contains(PARENT_LINE_CLASS))
            return info;
        if (!browserInput && !IrBslHoverHtml.isFullHtmlDocument(html))
            return IrBslHoverHtml.mergeHtml("", line + html); //$NON-NLS-1$
        int body = html.toLowerCase(java.util.Locale.ROOT).indexOf("<body"); //$NON-NLS-1$
        int bodyEnd = body >= 0 ? html.indexOf('>', body) : -1;
        String result = bodyEnd >= 0 ? html.substring(0, bodyEnd + 1) + line + html.substring(bodyEnd + 1)
            : line + html;
        return browserInput ? IrBslHoverHtml.withHtml(info, result) : result;
    }

    @Override
    public CharSequence getPrefixCompletionText(IDocument document, int completionOffset)
    {
        if (delegate instanceof ICompletionProposalExtension3)
            return ((ICompletionProposalExtension3) delegate)
                .getPrefixCompletionText(document, completionOffset);
        return null;

    }

    @Override
    public int getPrefixCompletionStart(IDocument document, int completionOffset)
    {
        if (delegate instanceof ICompletionProposalExtension3)
            return ((ICompletionProposalExtension3) delegate)
                .getPrefixCompletionStart(document, completionOffset);
        return completionOffset;

    }

    /**
     * Каретка непосредственно перед {@code (} — вставка без хвоста вызова {@code (...)}.
     *
     * @return {@code true}, если выполнен fallback вместо штатного delegate.apply
     */
    private boolean tryApplyWordOnly(IDocument document, ITextViewer viewer,
        int completionOffset, Integer stateMask)
    {
        ConfigurableCompletionProposal cp = asConfigurable(delegate);
        int caret = completionOffset >= 0
            ? completionOffset
            : resolveApplyCaretOffset(document);
        boolean wordOnly = cp != null && document != null && caret >= 0
            && needsWordOnlyInsert(cp, document, caret);
        if (!wordOnly)
            return false;
        int effectiveOffset = completionOffset >= 0 ? completionOffset : caret;
        // Полный replacement не вставляем, поэтому его DataEvent/LinkedMode неприменим:
        // позиции рассчитаны для новых скобок, а мы сохраняем уже существующие.
        SmartContentAssistProcessor.clearCtorPendingApplyState();
        int desiredCaret = applyWordOnly(cp, document, viewer, effectiveOffset, stateMask);
        // CompletionProposalPopup после любого apply всегда вызывает getSelection()
        // и выставляет полученный диапазон, поэтому позицию нужно вернуть именно там.
        wordOnlyCaret = desiredCaret;
        ContentAssistSessionReloader reloader = viewer instanceof SourceViewer sourceViewer
            ? ContentAssistSessionReloader.forViewer(sourceViewer)
            : ContentAssistSessionReloader.getActiveReloader();
        if (reloader != null)
            reloader.scheduleParamHintForExistingCall(desiredCaret);
        return true;
    }

    /**
     * Штатный шаблон EDT обновляет replaceLength только в validate(DocumentEvent).
     * Умный фильтр обходит этот вызов, а кэш может перенести предложение на другое слово.
     * Перед штатной вставкой пересчитываем диапазон, как для кэшированных предложений Xtext.
     * Поля подтверждены в .tmp/bundles/lcore-ui-templates/TemplateProposal.javap.txt.
     */
    private void rebaseEdtTemplateReplacement(IDocument document, ITextViewer viewer, int caret)
    {
        ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(delegate);
        if (raw == null || !raw.getClass().getName().equals(
            "com.e1c.g5.v8.dt.lcore.ui.templates.contentassist.TemplateProposal")) //$NON-NLS-1$
            return;
        Object oldOffset = Global.getField(raw, "replaceOffset"); //$NON-NLS-1$
        Object oldLength = Global.getField(raw, "replaceLength"); //$NON-NLS-1$
        if (document == null || caret < 0 || caret > document.getLength()
            || !(oldOffset instanceof Integer) || !(oldLength instanceof Integer))
            return;
        String prefix = SmartContentAssistProcessor.computeIdentifierFilter(document, caret);
        int start = caret - prefix.length();
        Point selection = viewer != null ? viewer.getSelectedRange() : null;
        int selectedLength = selection != null && selection.x == caret ? selection.y : 0;
        int length = prefix.length() + selectedLength;
        // setFieldForce проверяет результат через ==: для упакованного int вне кэша
        // Integer возвращает false даже после успешной записи. Не связываем записи через &&.
        Global.setField(raw, "replaceOffset", start); //$NON-NLS-1$
        Global.setField(raw, "replaceLength", length); //$NON-NLS-1$
    }

    private boolean tryApplyTemplate(IDocument document, ITextViewer viewer, int caret, Integer stateMask)
    {
        templateSelection = BslCompletionTemplates.apply(delegate, document, viewer, caret, stateMask);
        if (templateSelection == null)
            return false;
        ContentAssistSessionReloader reloader = viewer instanceof SourceViewer sourceViewer
            ? ContentAssistSessionReloader.forViewer(sourceViewer)
            : ContentAssistSessionReloader.getActiveReloader();
        if (reloader != null)
            reloader.scheduleParamHintForExistingCall(templateSelection.x);
        return true;
    }

    private static ConfigurableCompletionProposal asConfigurable(ICompletionProposal proposal)
    {
        ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
        return raw instanceof ConfigurableCompletionProposal cp ? cp : null;
    }

    /**
     * Каретка сразу после {@code =}, перед знаком пробел — перед вставляемым текстом
     * тоже пробел ({@code А =|} → {@code А = Слово}).
     */
    static void padIrPlanAfterEquals(IDocument document, int caret,
        IrCompletionProposal.InsertPlan plan)
    {
        if (plan == null || !needsSpaceAfterEquals(document, caret, plan.text))
            return;
        plan.text = " " + plan.text; //$NON-NLS-1$
        plan.caretOffset++;
    }

    static boolean needsSpaceAfterEquals(IDocument document, int caret, String insertText)
    {
        if (document == null || caret < 2 || insertText == null || insertText.isEmpty())
            return false;
        char first = insertText.charAt(0);
        if (first == ' ' || first == '\t' || first == '\n' || first == '\r')
            return false;
        try
        {
            return document.getChar(caret - 1) == '=' && document.getChar(caret - 2) == ' ';
        }
        catch (BadLocationException e)
        {
            return false;
        }
    }

    /**
     * Временно добавляет ведущий пробел в {@code replacementString} EDT-предложения.
     * Восстанавливает поля после {@code apply}, чтобы объект из кэша списка не остался
     * с изменённой строкой.
     */
    static final class EqualsSpacePad
    {
        private final ConfigurableCompletionProposal proposal;
        private final String original;
        private final boolean padded;

        static EqualsSpacePad install(IDocument document, int caret, ICompletionProposal delegate)
        {
            ConfigurableCompletionProposal cp = asConfigurable(delegate);
            if (cp == null)
                return new EqualsSpacePad(null, null, false);
            String repl = readInsertText(cp);
            int start = cp.getReplacementOffset();
            int insertAt = start >= 0 ? start : caret;
            if (!needsSpaceAfterEquals(document, insertAt, repl))
                return new EqualsSpacePad(null, null, false);
            SmartContentAssistProcessor.writeReplacementContent(cp, " " + repl); //$NON-NLS-1$
            cp.setCursorPosition(cp.getCursorPosition() + 1);
            return new EqualsSpacePad(cp, repl, true);
        }

        private EqualsSpacePad(ConfigurableCompletionProposal proposal, String original,
            boolean padded)
        {
            this.proposal = proposal;
            this.original = original;
            this.padded = padded;
        }

        void restore()
        {
            if (!padded || proposal == null || original == null)
                return;
            proposal.setCursorPosition(Math.max(0, proposal.getCursorPosition() - 1));
            SmartContentAssistProcessor.writeReplacementContent(proposal, original);
        }

        void scheduleRestore()
        {
            if (!padded)
                return;
            Display display = Display.getCurrent();
            if (display == null || display.isDisposed())
            {
                restore();
                return;
            }
            display.asyncExec(this::restore);
        }

        static String readInsertText(ConfigurableCompletionProposal cp)
        {
            Object initial = Global.getField(cp, "initialReplacementContent"); //$NON-NLS-1$
            if (initial instanceof String s && !s.isEmpty())
                return s;
            return cp.getReplacementString();
        }
    }

    /** Модельная каретка для чтения документа; {@code fallbackOffset} — аргумент JFace {@code apply}. */
    private static int resolveApplyCaret(IDocument document, int fallbackOffset)
    {
        int model = resolveApplyCaretOffset(document);
        if (model >= 0)
            return model;
        return fallbackOffset;
    }

    private static int resolveApplyCaretOffset(IDocument document)
    {
        SourceViewer viewer = ContentAssistSessionReloader.getActiveViewer();
        if (viewer == null || viewer.getDocument() != document)
            return -1;
        return SmartContentAssistProcessor.resolveWidgetCaret(viewer);
    }

    /**
     * В поле выражения инспектора proposal иногда приходит со стартом замены
     * от прошлого вызова (середина уже вставленного имени). Сужаем к текущему
     * идентификатору, кроме {@code родитель.член}.
     * Viewer берём из {@code apply(ITextViewer)}, не из {@code getActiveViewer()} —
     * активным часто остаётся модульный редактор, и правка молча не срабатывала.
     */
    private void rebaseInspectExpressionReplacement(IDocument document, ITextViewer applyViewer,
        int caret)
    {
        StyledText widget = applyViewer != null ? applyViewer.getTextWidget() : null;
        if (widget == null || widget.isDisposed())
        {
            SourceViewer active = ContentAssistSessionReloader.getActiveViewer();
            widget = active != null ? active.getTextWidget() : null;
        }
        if (widget == null || widget.isDisposed()
            || !Boolean.TRUE.equals(widget.getData(DebugInspectorHook.INSPECT_EXPRESSION_EDITOR_KEY)))
            return;
        ConfigurableCompletionProposal cp = asConfigurable(delegate);
        DebugInspectorHook.markInspectExpressionProposalApplied(
            cp == null ? null : cp.getReplacementString(), delegate);
        if (document == null || caret < 0 || cp == null)
            return;
        int start = cp.getReplacementOffset();
        int len = cp.getReplacementLength();
        String filter = SmartContentAssistProcessor.computeIdentifierFilter(document, caret);
        int identStart = filter.isEmpty() ? caret : caret - filter.length();
        if (start >= identStart)
            return;
        try
        {
            if (identStart > 0 && document.getChar(identStart - 1) == '.')
                return;
        }
        catch (BadLocationException e)
        {
            return;
        }
        int newLen = Math.max(0, caret - identStart);
        cp.setReplacementOffset(identStart);
        cp.setReplacementLength(newLen);
    }

    private static boolean needsWordOnlyInsert(ConfigurableCompletionProposal cp,
        IDocument document, int caretOffset)
    {
        String repl = cp.getReplacementString();
        if (callSuffixStart(repl) < 0)
            return false;
        try
        {
            if (caretOffset >= document.getLength())
                return false;
            return document.getChar(caretOffset) == '(';
        }
        catch (BadLocationException e)
        {
            return false;
        }
    }

    /** Начало хвоста вызова {@code (...)} с допустимыми пробелами/точкой с запятой после него. */
    private static int callSuffixStart(String replacement)
    {
        if (replacement == null)
            return -1;
        int open = replacement.indexOf('(');
        int close = replacement.lastIndexOf(')');
        if (open <= 0 || close <= open)
            return -1;
        for (int i = close + 1; i < replacement.length(); i++)
        {
            char ch = replacement.charAt(i);
            if (ch != ';' && !Character.isWhitespace(ch))
                return -1;
        }
        return open;
    }

    private static String stripTrailingCall(String replacement)
    {
        int open = callSuffixStart(replacement);
        if (open >= 0)
            return replacement.substring(0, open);
        return replacement;
    }

    private static int applyWordOnly(ConfigurableCompletionProposal cp, IDocument document,
        ITextViewer viewer, int completionOffset, Integer stateMask)
    {
        String savedRepl = cp.getReplacementString();
        int savedLen = cp.getReplacementLength();
        String wordOnly = stripTrailingCall(savedRepl);
        int desiredCaret = cp.getReplacementOffset() + wordOnly.length() + 1;
        try
        {
            cp.setReplacementString(wordOnly);
            int newLen = completionOffset - cp.getReplacementOffset();
            Point sel = viewer != null ? viewer.getSelectedRange() : null;
            if (sel != null)
                newLen += sel.y;
            cp.setReplacementLength(newLen);
            if (stateMask != null && (stateMask & COMPLETE_STATE_MASK) != 0)
                cp.setReplacementLength(cp.getReplaceContextLength());
            cp.apply(document);
        }
        finally
        {
            cp.setReplacementString(savedRepl);
            cp.setReplacementLength(savedLen);
        }
        return desiredCaret;
    }

    private SmartCodeMatcher resolveHighlightMatcher()
    {
        String filter = SmartFilterTracker.getCurrentFilter();
        return new SmartCodeMatcher(filter != null ? filter : ""); //$NON-NLS-1$
    }

    private boolean matchesFilter(IDocument document, int offset, DocumentEvent event)
    {
        return SmartContentAssistProcessor.proposalMatchesFilter(delegate, document, offset, event);
    }

    private String resolveIrAdditionalProposalInfo(IrCompletionProposal ir)
    {
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader != null && ComfortSettings.isReplaceListFiltersEnabled())
        {
            String cacheKey = ir.getStableCacheKey();
            String merged = reloader.getIrMergedHtml(cacheKey);
            if (merged == null || merged.isEmpty())
                merged = reloader.getIrMergedHtml(ir.getDisplayString());
            if (merged != null && !merged.isEmpty())
            {
                return merged;
            }
            if (reloader.isIrActivationPending(cacheKey))
            {
                return null;
            }
            String html = resolveIrAssistSideHintHtml(reloader, ir);
            if (html != null && !html.isEmpty())
            {
                return html;
            }
            maybeScheduleIrWordActivation(reloader, ir);
        }
        String fallback = ir.getAdditionalProposalInfo();
        return fallback;
    }

    /**
     * Боковая подсказка assist для ИР-строки: только кэш активации
     * ({@code ОписаниеТекущегоСловаАвтодополнения}), без {@code ОписаниеХТМЛВыражения}.
     */
    private static String resolveIrAssistSideHintHtml(
        ContentAssistSessionReloader reloader, IrCompletionProposal ir)
    {
        String cacheKey = ir.getStableCacheKey();
        String cached = reloader.getIrMergedHtml(cacheKey);
        if (cached != null && !cached.isEmpty())
            return cached;
        cached = reloader.getIrMergedHtml(ir.getDisplayString());
        if (cached != null && !cached.isEmpty())
            return cached;
        String onProposal = ir.getAdditionalProposalInfo();
        if (onProposal != null && !onProposal.isEmpty())
            return onProposal;
        IrBslCompletionSupport.ActivationDescription act = reloader.getIrActivation(cacheKey);
        if (act != null)
            return IrBslCompletionSupport.formatActivationHtml(act.description, act.rawHtml);
        return null;
    }

    private void maybeScheduleIrWordActivation(
        ContentAssistSessionReloader reloader, IrCompletionProposal ir)
    {
        String cacheKey = ir.getStableCacheKey();
        if (reloader.getIrActivation(cacheKey) != null)
            return;
        if (reloader.isIrActivationPending(cacheKey))
            return;
        scheduleIrWordActivation(ir);
    }

    /**
     * Кэш предложений переживает переразбор документа. В EDT описание BSL-метода
     * вызывает installDerivedState у модуля без проверки его ресурса; eContainer()
     * на старом AST также может рекурсивно запустить разрешение BSL-ссылок.
     * Проверяем исходный EObject до штатного resolve и ещё раз на входе в hover.
     */
    private Object readDelegateEdtAdditionalInfo(IProgressMonitor monitor)
    {
        return readDelegateEdtAdditionalInfo(delegate, monitor);
    }

    static Object readDelegateEdtAdditionalInfo(ICompletionProposal delegate,
        IProgressMonitor monitor)
    {
        if (monitor != null && monitor.isCanceled())
            return null;
        if (delegate instanceof ConfigurableCompletionProposal configurable)
        {
            Object additional = Global.getField(configurable, "additionalProposalInfo"); //$NON-NLS-1$
            if (additional instanceof EObject object && !isEdtDocumentationObjectReady(object))
                return null;
            if (additional instanceof ProposalElement proposal)
                replaceStaleModelContext(proposal);
        }
        try
        {
            // Подключаем общую цепочку здесь: штатные, добавленные и поздно созданные строки проходят этот путь.
            BslEditorHoverHook.prepareSideHint(delegate, ContentAssistSessionReloader.getActiveViewer());
            Object info = delegate instanceof ICompletionProposalExtension5 ext5
                ? ext5.getAdditionalProposalInfo(monitor != null ? monitor : new NullProgressMonitor())
                : delegate.getAdditionalProposalInfo();
            if (delegate instanceof ConfigurableCompletionProposal configurable
                && Global.getField(configurable, "additionalProposalInfo") instanceof EObject source) //$NON-NLS-1$
            {
                if (!ParamHintHtmlModifier.needsMemberDocumentationRecovery(info))
                    return info;
                SourceViewer viewer = ContentAssistSessionReloader.getActiveViewer();
                if (viewer != null && viewer.getDocument() instanceof IXtextDocument document)
                    return ContentAssistSessionReloader.readOnlyPeekAst(document, resource ->
                        ParamHintHtmlModifier.recoverMemberDocumentation(info, source, resource));
                return ParamHintHtmlModifier.recoverMemberDocumentation(info, source, null);
            }
            return info;
        }
        catch (RuntimeException | StackOverflowError e)
        {
            return null;
        }

    }

    /** Проверка не резолвит proxy и не вызывает eContainer()/eResource(). */
    static boolean isEdtDocumentationObjectReady(EObject object)
    {
        String problem = edtDocumentationObjectProblem(object);
        return problem == null;
    }

    private static String edtDocumentationObjectProblem(EObject object)
    {
        if (object == null)
            return null;
        java.util.Deque<EObject> pending = new java.util.ArrayDeque<>();
        java.util.Set<EObject> checked = java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<>());
        pending.add(object);
        while (!pending.isEmpty())
        {
            EObject next = pending.removeFirst();
            if (!checked.add(next))
                continue;
            String problem = edtDocumentationContainerProblem(next);
            if (problem != null)
                return problem;
            if (next instanceof ProposalElement && next instanceof InternalEObject proposal)
            {
                // getModel()/обычный итератор getList() разрешают ссылки. eGet(false)
                // и basicIterator подтверждены ProposalElementImpl.eGet/getList.
                // model — лишь контекст для getViewDocBySemanticEObject, и EDT берёт его через
                // getModel() с разрешением прокси. Устаревший контекст — норма для списка после
                // «.»: каждая набранная буква переразбирает метод, и узел выражения сам или его
                // метод становится прокси. Цепочку контейнеров model поэтому не проверяем —
                // запрет гасил боковую подсказку автооткрытого списка (см. replaceStaleModelContext).
                Object value = proposal.eGet(BslPackage.PROPOSAL_ELEMENT__LIST, false, false);
                if (!(value instanceof InternalEList<?> list))
                    return "proposalListNotBasic";
                var entries = list.basicIterator();
                while (entries.hasNext())
                    if (entries.next() instanceof EObject entry)
                        pending.addLast(entry);
            }
        }
        return null;
    }

    /**
     * Контекст {@code ProposalElement.model} устарел — узел выражения после частичного
     * переразбора висит под методом-прокси с пустым адресом. EDT строит по контексту имя и
     * ссылку типа ({@code getViewDocBySemanticEObject(…, model)}), без ресурса получается
     * {@code <null>}. Подставляем корень модуля из текущего ресурса редактора: у него тот же
     * ресурс, версия платформы и язык. Только для ссылки: подмена в поле-включении вынула бы
     * модуль из ресурса.
     */
    private static void replaceStaleModelContext(ProposalElement proposal)
    {
        try
        {
            Object model = ((InternalEObject)proposal).eGet(BslPackage.PROPOSAL_ELEMENT__MODEL, false, false);
            if (!(model instanceof InternalEObject context) || !isStaleContext(context))
                return;
            org.eclipse.emf.ecore.EStructuralFeature feature =
                proposal.eClass().getEStructuralFeature(BslPackage.PROPOSAL_ELEMENT__MODEL);
            if (!(feature instanceof org.eclipse.emf.ecore.EReference reference) || reference.isContainment())
                return;
            SourceViewer viewer = ContentAssistSessionReloader.getActiveViewer();
            if (viewer == null || !(viewer.getDocument() instanceof IXtextDocument document))
                return;
            EObject root = ContentAssistSessionReloader.readOnlyPeekAst(document, resource ->
                resource == null || resource.getContents().isEmpty() ? null : resource.getContents().get(0));
            if (root instanceof com._1c.g5.v8.dt.bsl.model.Module)
                proposal.eSet(reference, root);
        }
        catch (RuntimeException ignored)
        {
        }
    }

    /**
     * Предложение EDT посчитано по модели, которую частичный переразбор уже заменил: контекст
     * его {@code ProposalElement} устарел. Не {@link ConfigurableCompletionProposal} или без
     * {@code ProposalElement} — {@code false}.
     */
    static boolean isProposalContextStale(ICompletionProposal proposal)
    {
        if (!(proposal instanceof ConfigurableCompletionProposal configurable)
            || !(Global.getField(configurable, "additionalProposalInfo") instanceof ProposalElement element)) //$NON-NLS-1$
            return false;
        Object model = ((InternalEObject)element).eGet(BslPackage.PROPOSAL_ELEMENT__MODEL, false, false);
        return model instanceof InternalEObject context && isStaleContext(context);
    }

    /** В цепочке контейнеров прокси, или до ресурса не дойти. Прокси не разрешаются. */
    private static boolean isStaleContext(InternalEObject context)
    {
        java.util.Set<EObject> path = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (InternalEObject current = context; current != null && path.add(current);
            current = current.eInternalContainer())
        {
            if (current.eIsProxy())
                return true;
            if (current.eDirectResource() != null)
                return false;
        }
        return true;
    }

    private static String edtDocumentationContainerProblem(EObject object)
    {
        try
        {
            return availableEdtDocumentationContainerProblem(object);
        }
        catch (IllegalStateException error)
        {
            if (!"Object is removed".equals(error.getMessage())) //$NON-NLS-1$
                throw error;
            Global.tempLogException("assist-removed-object", "documentation infoClass=" //$NON-NLS-1$ //$NON-NLS-2$
                + object.getClass().getName(), error);
            return "removedBmObject"; //$NON-NLS-1$
        }
    }

    private static String availableEdtDocumentationContainerProblem(EObject object)
    {
        java.util.Set<EObject> path = java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<>());
        boolean bsl = false;
        boolean module = false;
        InternalEObject current = object instanceof InternalEObject internal ? internal : null;
        while (current != null)
        {
            if (!path.add(current))
                return "containerCycle";
            bsl |= BslPackage.eNS_URI.equals(current.eClass().getEPackage().getNsURI());
            module |= current instanceof com._1c.g5.v8.dt.bsl.model.Module;
            if (current.eIsProxy())
                return bsl ? "bslProxy" : null;
            org.eclipse.emf.ecore.resource.Resource direct = current.eDirectResource();
            if (direct != null)
            {
                if (bsl && direct instanceof XtextResource resource)
                {
                    var parse = resource.getParseResult();
                    if (parse == null || !path.contains(parse.getRootASTElement()))
                        return "oldParseRoot";
                }
                return null;
            }
            current = current.eInternalContainer();
        }
        // Безресурсные объекты сами по себе допустимы. Ошибка EDT подтверждена
        // для метода внутри отсоединённого Module: getEventsFromModelMethod
        // вызывает module.eResource().installDerivedState без проверки на null.
        return module ? "detachedBslModule" : null;
    }

    /**
     * EDT-строка: штатная боковая подсказка + HTML из кэша активации
     * ({@code ОписаниеТекущегоСловаАвтодополнения}), без {@code ОписаниеХТМЛВыражения}.
     */
    private Object resolveEdtAssistSideHint(Object baseInfo)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return baseInfo;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader == null)
            return baseInfo;
        String cacheKey = BslCompletionSideHintResolver.resolveIrCacheKey(delegate);
        if (cacheKey == null || cacheKey.isEmpty())
            return baseInfo;
        String merged = reloader.getIrMergedHtml(cacheKey);
        if (merged != null && !merged.isEmpty())
            return IrBslHoverHtml.withHtml(baseInfo, merged);
        String irHtml = resolveActivationHtmlFromCache(reloader, cacheKey);
        if (irHtml != null && !irHtml.isEmpty())
        {
            // Merge в боковую панель только если EDT дал BslBrowserInput; тип в списке
            // уже обновлён через applyEdtListTypeFromIrActivation.
            if (IrBslHoverHtml.isBslBrowserInput(baseInfo))
            {
                merged = IrBslHoverHtml.mergeHtml(IrBslHoverHtml.readDisplayHtml(baseInfo), irHtml);
                reloader.putIrMergedHtml(cacheKey, merged);
                return IrBslHoverHtml.withHtml(baseInfo, merged);
            }
            return baseInfo;
        }
        // Активация (ОписаниеТекущегоСловаАвтодополнения) нужна и без BslBrowserInput —
        // иначе overlap EDT+ИР не получает calculated type в подписи строки.
        maybeScheduleEdtRowActivation(reloader, baseInfo);
        return baseInfo;
    }

    private static String resolveActivationHtmlFromCache(
        ContentAssistSessionReloader reloader, String cacheKey)
    {
        IrBslCompletionSupport.ActivationDescription act = reloader.getIrActivation(cacheKey);
        if (act == null)
            return null;
        return IrBslCompletionSupport.formatActivationHtml(act.description, act.rawHtml);
    }

    private void maybeScheduleEdtRowActivation(ContentAssistSessionReloader reloader,
        Object baseInfo)
    {
        String cacheKey = BslCompletionSideHintResolver.resolveIrCacheKey(delegate);
        if (cacheKey == null || cacheKey.isEmpty())
            return;
        if (reloader.getIrActivation(cacheKey) != null)
            return;
        if (reloader.isIrActivationPending(cacheKey))
            return;
        scheduleEdtRowActivation(baseInfo);
    }

    private void scheduleEdtRowActivation(Object baseInfo)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader == null)
            return;
        String cacheKey = BslCompletionSideHintResolver.resolveIrCacheKey(delegate);
        String name = BslCompletionSideHintResolver.resolveElementName(delegate);
        String kind = BslCompletionSideHintResolver.resolveElementKind(delegate);
        if (cacheKey == null || cacheKey.isEmpty() || name == null || name.isEmpty()
            || kind == null)
            return;
        IrBslCompletionSupport.ActivationDescription cachedRaw = reloader.getIrActivation(cacheKey);
        // Пустой тип при overlap — обычно fetch без КлючНабораСлов до прихода ИР; не держим кэш.
        if (cachedRaw != null && (cachedRaw.type == null || cachedRaw.type.isEmpty())
            && resolveEdtOverlapDictionaryKey(delegate) != null)
        {
            reloader.removeIrActivation(cacheKey);
            cachedRaw = null;
        }
        IRSession session = IrBslExpressionHtmlSupport.resolveConnectedSession(reloader.getBslEditor());
        if (session == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        // И кэш, и подключение проверены до запроса штатного описания. Чтение
        // описания выполняется в существующем фоновом задании активации.
        if (!reloader.tryBeginIrActivation(cacheKey, true))
            return;
        final boolean isMethod =
            IrBslExpressionHtmlSupport.KIND_METHOD.equals(kind);
        final int gen = reloader.beginIrFetchForDisplay(cacheKey);
        final String displayKey = delegate.getDisplayString();
        final BslXtextEditor editor = reloader.getBslEditor();
        display.asyncExec(() -> submitEdtActivationOnUi(
            reloader, session, editor, gen, cacheKey, displayKey, name, isMethod, baseInfo, delegate));

    }

    /** RDT {@code ПриАктивизацииСтрокиТ9}: динамический тип и описание ИР-слова. */
    private void scheduleIrWordActivation(IrCompletionProposal ir)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader == null)
            return;
        String cacheKey = ir.getStableCacheKey();
        IrBslCompletionSupport.ActivationDescription cached = reloader.getIrActivation(cacheKey);
        if (cached != null)
        {
            applyCachedIrActivation(reloader, ir, cached);
            return;
        }
        if (!reloader.tryBeginIrActivation(cacheKey))
        {
            return;
        }
        IRSession session = IrBslExpressionHtmlSupport.resolveConnectedSession(reloader.getBslEditor());
        if (session == null)
        {
            reloader.endIrActivation(cacheKey);
            return;
        }
        final int gen = reloader.beginIrFetchForDisplay(cacheKey);
        final BslXtextEditor editor = reloader.getBslEditor();
        submitIrActivationOnUi(reloader, session, editor, gen, ir);
    }

    private static void applyCachedIrActivation(
        ContentAssistSessionReloader reloader, IrCompletionProposal ir,
        IrBslCompletionSupport.ActivationDescription desc)
    {
        String cacheKey = ir.getStableCacheKey();
        org.eclipse.jface.text.contentassist.ContentAssistant assistant =
            ContentAssistSessionReloader.getActiveAssistant();
        if (assistant == null || !ContentAssistPopupSync.isPopupVisible(assistant))
            return;
        int gen = reloader.getIrFetchGeneration();
        applyIrListTypeFromActivation(reloader, assistant, ir, desc, gen);
        String html = reloader.getIrMergedHtml(cacheKey);
        if (html == null || html.isEmpty())
            html = ir.getAdditionalProposalInfo();
        publishIrSideHintAfterActivation(reloader, assistant, ir, cacheKey, html, true);
    }

    private static void submitIrActivationOnUi(
        ContentAssistSessionReloader reloader, IRSession session, BslXtextEditor editor,
        int gen, IrCompletionProposal ir)
    {
        String cacheKey = ir != null ? ir.getStableCacheKey() : null;
        if (editor == null || session == null || reloader == null || ir == null)
        {
            if (reloader != null)
                reloader.endIrActivation(cacheKey);
            return;
        }
        Runnable fetch = () -> runIrActivationFetch(reloader, session, gen, ir);
        if (!reloader.isWordsTableReady())
        {
            reloader.runWhenWordsTableReady(() -> session.executor.submit(fetch));
            return;
        }
        session.executor.submit(fetch);
    }

    private static void runIrActivationFetch(
        ContentAssistSessionReloader reloader, IRSession session, int gen, IrCompletionProposal ir)
    {
        String cacheKey = ir.getStableCacheKey();
        try
        {
            IrBslCompletionSupport.ActivationDescription cached =
                reloader.getIrActivation(cacheKey);
            if (cached != null)
            {
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return;
                display.asyncExec(() -> applyIrActivationResult(
                    reloader, session, gen, ir, cached));
                return;
            }
            IrBslCompletionSupport.ActivationDescription desc =
                IrBslCompletionSupport.fetchWordActivationDescription(
                    session, ir.getWordValue(), ir.isMethod(), ir.getDictionaryKey());
            if (desc == null)
                return;
            reloader.putIrActivation(cacheKey, desc);
            session.pumpUserMessagesToUi();
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> applyIrActivationResult(reloader, session, gen, ir, desc));
        }
        finally
        {
            reloader.endIrActivation(cacheKey);
        }
    }

    private static void applyIrActivationResult(
        ContentAssistSessionReloader reloader, IRSession session, int gen,
        IrCompletionProposal ir, IrBslCompletionSupport.ActivationDescription desc)
    {
        reloader.putIrActivation(ir.getStableCacheKey(), desc);
        boolean staleGen = gen != reloader.getIrFetchGeneration();
        org.eclipse.jface.text.contentassist.ContentAssistant assistant =
            ContentAssistSessionReloader.getActiveAssistant();
        if (assistant == null || !ContentAssistPopupSync.isPopupVisible(assistant))
            return;
        applyIrListTypeFromActivation(reloader, assistant, ir, desc, gen);
        String cacheKey = ir.getStableCacheKey();
        String html = ir.getAdditionalProposalInfo();
        if (!staleGen)
            publishIrSideHintAfterActivation(reloader, assistant, ir, cacheKey, html, false);
    }

    /** Только подпись строки ИР в списке (тип из COM), без боковой подсказки. */
    private static void applyIrListTypeFromActivation(
        ContentAssistSessionReloader reloader,
        org.eclipse.jface.text.contentassist.ContentAssistant assistant,
        IrCompletionProposal ir, IrBslCompletionSupport.ActivationDescription desc, int gen)
    {
        String cacheKey = ir.getStableCacheKey();
        String oldDisplay = ir.getDisplayString();
        if (desc.type != null)
            ir.applyActivation(desc.type, desc.description, desc.rawHtml);
        else if (desc.description != null)
            ir.applyActivation("", desc.description, desc.rawHtml); //$NON-NLS-1$
        String newDisplay = ir.getDisplayString();
        String html = ir.getAdditionalProposalInfo();
        if (html != null && !html.isEmpty())
        {
            reloader.putIrMergedHtml(cacheKey, html);
            reloader.putIrMergedHtml(newDisplay, html);
        }
        if (!oldDisplay.equals(newDisplay))
        {
            ContentAssistPopupSync.refreshProposalTable(assistant);
        }
    }

    /**
     * Боковая подсказка ИР — отдельно от обновления типа в списке.
     *
     * @param fromSelectedActivation {@code true} в {@code selected()} — строка активируется сейчас
     */
    private static boolean publishIrSideHintAfterActivation(
        ContentAssistSessionReloader reloader,
        org.eclipse.jface.text.contentassist.ContentAssistant assistant,
        IrCompletionProposal ir, String cacheKey, String html,
        boolean fromSelectedActivation)
    {
        if (html == null || html.isEmpty())
            return false;
        if (!fromSelectedActivation
            && !ContentAssistPopupSync.isSelectedIrProposal(assistant, ir))
            return false;
        SourceViewer viewer = ContentAssistSessionReloader.getActiveViewer();
        int caret = SmartContentAssistProcessor.resolveSessionCaret(assistant, viewer);
        boolean inLiteral = viewer != null && caret >= 0
            && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret);
        if (inLiteral && reloader.isLiteralOpenSetupPhase())
        {
            ContentAssistPopupSync.logLiteralSideHintSuppressed(fromSelectedActivation,
                "setupPhase"); //$NON-NLS-1$
            return false;
        }
        if (fromSelectedActivation && inLiteral && reloader.isLiteralFinishPinPending())
            return false;
        boolean cachedCreatorBefore = reloader.getAssistBrowserCreator() != null;
        reloader.noteActiveIrDisplayKey(cacheKey);
        boolean pinned = inLiteral
            ? ContentAssistPopupSync.pinIrSideHint(assistant, ir, html, false)
            : ContentAssistPopupSync.pinIrSideHint(assistant, ir, html);
        if (pinned)
            reloader.markIrSideHintPublished(cacheKey);
        else if (!reloader.isIrSideHintPublishedForKey(cacheKey))
            ContentAssistPopupSync.publishIrActivationSideHint(assistant, html, cacheKey);
return pinned;
    }

    private static void submitEdtActivationOnUi(
        ContentAssistSessionReloader reloader, IRSession session, BslXtextEditor editor,
        int gen, String cacheKey, String displayKey, String wordValue, boolean isMethod,
        Object baseInput, ICompletionProposal edtDelegate)
    {
        if (editor == null || session == null || reloader == null)
        {
            if (reloader != null)
                reloader.endIrActivation(cacheKey);
            return;
        }
        Runnable fetch = () -> runEdtActivationFetch(
            reloader, session, gen, cacheKey, displayKey, wordValue, isMethod, baseInput,
            edtDelegate);
        // Для готового ответа ИР таблица слов уже не нужна.
        if (!reloader.isWordsTableReady() && reloader.getIrActivation(cacheKey) == null)
        {
            reloader.runWhenWordsTableReady(() -> session.executor.submit(fetch));
            return;
        }
        session.executor.submit(fetch);
    }

    private static void runEdtActivationFetch(
        ContentAssistSessionReloader reloader, IRSession session, int gen,
        String cacheKey, String displayKey, String wordValue, boolean isMethod,
        Object baseInput, ICompletionProposal edtDelegate)
    {
        try
        {
            if (gen != reloader.getIrFetchGeneration())
                return;
            IrBslCompletionSupport.ActivationDescription cached =
                reloader.getIrActivation(cacheKey);
            if (cached != null)
            {
                publishEdtActivationFromBackground(reloader, session, gen, cacheKey,
                    displayKey, cached, baseInput, edtDelegate);
                return;
            }
            IrOverlapActivationTarget target = resolveEdtOverlapActivationTarget(
                edtDelegate, wordValue, isMethod);
            IrBslCompletionSupport.ActivationDescription desc =
                IrBslCompletionSupport.fetchWordActivationDescription(
                    session, target.wordValue, target.isMethod, target.dictionaryKey);
            if (desc == null)
                return;
            // Без КлючНабораСлов ИР часто отдаёт пустой Тип — не кэшируем, иначе overlap залипает.
            boolean hasType = desc.type != null && !desc.type.isEmpty();
            boolean hasDict = target.dictionaryKey != null && !target.dictionaryKey.isEmpty();
            if (hasType || hasDict)
                reloader.putIrActivation(cacheKey, desc);
            session.pumpUserMessagesToUi();
            publishEdtActivationFromBackground(reloader, session, gen, cacheKey,
                displayKey, desc, baseInput, edtDelegate);
        }
        finally
        {
            reloader.endIrActivation(cacheKey);
        }
    }

    /** Тяжёлое штатное описание — в том же фоне, что и активация ИР, до публикации на UI. */
    private static void publishEdtActivationFromBackground(
        ContentAssistSessionReloader reloader, IRSession session, int gen,
        String cacheKey, String displayKey, IrBslCompletionSupport.ActivationDescription desc,
        Object baseInput, ICompletionProposal edtDelegate)
    {
        if (gen != reloader.getIrFetchGeneration())
            return;
        Object readyBaseInput = baseInput != null ? baseInput
            : readDelegateEdtAdditionalInfo(edtDelegate, new NullProgressMonitor());
        if (gen != reloader.getIrFetchGeneration())
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> applyEdtActivationResult(
            reloader, session, gen, cacheKey, displayKey, desc, readyBaseInput, edtDelegate));
    }

    private static void applyEdtActivationResult(
        ContentAssistSessionReloader reloader, IRSession session, int gen,
        String cacheKey, String displayKey, IrBslCompletionSupport.ActivationDescription desc,
        Object baseInput, ICompletionProposal edtDelegate)
    {
        reloader.putIrActivation(cacheKey, desc);
        if (gen != reloader.getIrFetchGeneration())
            return;
        org.eclipse.jface.text.contentassist.ContentAssistant assistant =
            ContentAssistSessionReloader.getActiveAssistant();
        if (assistant == null || !ContentAssistPopupSync.isPopupVisible(assistant))
            return;
        applyEdtListTypeFromIrActivation(assistant, edtDelegate, desc);
        applyEdtActivationToSidePanel(
            reloader, assistant, cacheKey, displayKey, desc, baseInput);
    }

    /** Overlap EDT+ИР: тип в списке из ИР после активации строки. */
    private static void applyEdtListTypeFromIrActivation(
        org.eclipse.jface.text.contentassist.ContentAssistant assistant,
        ICompletionProposal edtDelegate, IrBslCompletionSupport.ActivationDescription desc)
    {
        if (desc == null || desc.type == null || desc.type.isEmpty())
            return;
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        if (processor == null)
            return;
        String key = SmartContentAssistProcessor.dedupKeyForMerge(edtDelegate);
        IrCompletionProposal ir = processor.findIrProposalForMergeMatch(edtDelegate);
        if (ir == null)
            return;
        String edtDisplay = edtDelegate.getDisplayString();
        String old = processor.resolveIrOverlapDisplay(edtDisplay, key);
        processor.putIrOverlapTypeFromIr(key, ir, desc.type);
        String display = SmartContentAssistProcessor.injectIrTypeIntoEdtDisplay(
            edtDisplay, ir.getListTypeLabel(), desc.type, ir.getParentContextType());
        SmartContentAssistProcessor.applyIrDisplayToEdt(edtDelegate, display);
        if (display.equals(old))
            return;
        ContentAssistPopupSync.refreshProposalTable(assistant);
    }

    private static void applyEdtActivationToSidePanel(
        ContentAssistSessionReloader reloader,
        org.eclipse.jface.text.contentassist.ContentAssistant assistant,
        String cacheKey, String displayKey,
        IrBslCompletionSupport.ActivationDescription desc, Object baseInput)
    {
        String irHtml = IrBslCompletionSupport.formatActivationHtml(desc.description, desc.rawHtml);
        if (irHtml == null || irHtml.isEmpty())
            return;
        String merged = IrBslHoverHtml.isBslBrowserInput(baseInput)
            ? IrBslHoverHtml.mergeHtml(IrBslHoverHtml.readHtml(baseInput), irHtml)
            : irHtml;
        reloader.putIrMergedHtml(cacheKey, merged);
        if (ContentAssistPopupSync.isSelectedDisplay(assistant, displayKey))
        {
            SourceViewer viewer = ContentAssistSessionReloader.getActiveViewer();
            int caret = SmartContentAssistProcessor.resolveWidgetCaret(viewer);
            boolean literalOverlap = viewer != null && caret >= 0
                && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret)
                && IrBslExpressionHtmlSupport.resolveConnectedSession(reloader.getBslEditor()) != null;
            ContentAssistPopupSync.pinMergedAdditionalInfo(assistant, displayKey, merged,
                literalOverlap);
        }
    }

    /** Параметры COM {@code ОписаниеТекущегоСловаАвтодополнения} для overlap EDT+ИР. */
    private static final class IrOverlapActivationTarget
    {
        final String wordValue;
        final boolean isMethod;
        final String dictionaryKey;

        IrOverlapActivationTarget(String wordValue, boolean isMethod, String dictionaryKey)
        {
            this.wordValue = wordValue;
            this.isMethod = isMethod;
            this.dictionaryKey = dictionaryKey != null ? dictionaryKey : ""; //$NON-NLS-1$
        }
    }

    private static IrOverlapActivationTarget resolveEdtOverlapActivationTarget(
        ICompletionProposal edtDelegate, String wordValue, boolean isMethod)
    {
        IrCompletionProposal ir = findIrOverlapForEdt(edtDelegate);
        if (ir == null)
            return new IrOverlapActivationTarget(wordValue, isMethod, ""); //$NON-NLS-1$
        return new IrOverlapActivationTarget(
            ir.getWordValue() != null && !ir.getWordValue().isEmpty()
                ? ir.getWordValue() : wordValue,
            ir.isMethod(),
            ir.getDictionaryKey());
    }

    private static String resolveEdtOverlapDictionaryKey(ICompletionProposal edtDelegate)
    {
        IrCompletionProposal ir = findIrOverlapForEdt(edtDelegate);
        if (ir == null)
            return null;
        String dict = ir.getDictionaryKey();
        return dict != null && !dict.isEmpty() ? dict : null;
    }

    private static IrCompletionProposal findIrOverlapForEdt(ICompletionProposal edtDelegate)
    {
        if (edtDelegate == null)
            return null;
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        if (processor == null)
            return null;
        return processor.findIrProposalForMergeMatch(edtDelegate);
    }
}
