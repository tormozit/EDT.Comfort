package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.IScopeProvider;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;
import org.eclipse.xtext.ui.editor.hover.IEObjectHover;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.Triple;
import org.eclipse.xtext.util.Tuples;

import com._1c.g5.modeling.xtext.scoping.IIndexSlicePredicateService;
import com._1c.g5.v8.dt.bsl.common.DefaultStringLiteralTextProvider;
import com._1c.g5.v8.dt.bsl.model.BinaryExpression;
import com._1c.g5.v8.dt.bsl.model.BinaryOperation;
import com._1c.g5.v8.dt.bsl.model.Conditional;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.ForEachStatement;
import com._1c.g5.v8.dt.bsl.model.ForStatement;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.FunctionStyleCreator;
import com._1c.g5.v8.dt.bsl.model.IfStatement;
import com._1c.g5.v8.dt.bsl.model.IndexAccess;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.OperatorStyleCreator;
import com._1c.g5.v8.dt.bsl.model.Pragma;
import com._1c.g5.v8.dt.bsl.model.PropertyType;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com._1c.g5.v8.dt.bsl.model.Variable;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.bsl.stringliteral.contenttypes.TypeUtil;
import com._1c.g5.v8.dt.bsl.typesystem.ValueTreeDynamicContextDefProvider;
import com._1c.g5.v8.dt.bsl.typesystem.util.TypeSystemUtil;
import com._1c.g5.v8.dt.bsl.ui.contentassist.stringliteral.AbstractStringLiteralProposalProvider;
import com._1c.g5.v8.dt.bsl.ui.contentassist.stringliteral.IStringLiteralProposalProvider.IBslStringLiteralProposalImageProvider;
import com._1c.g5.v8.dt.core.platform.IConfigurationAware;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.form.model.AbstractFormAttribute;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.form.model.FormParameter;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.Ctor;
import com._1c.g5.v8.dt.mcore.DuallyNamedElement;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.DerivedField;
import com._1c.g5.v8.dt.mcore.Field;
import com._1c.g5.v8.dt.mcore.FieldSource;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.NamedElement;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.Parameter;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeContainer;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicDbObject;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttributeDataSeparation;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.RegisterDimension;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;

/**
 * Значения строкового литерала (issue 888) — перенос из ИР ({@code ирКлсПолеТекстаПрограммы}):
 * {@code ЗаполнитьТаблицуВозможныхЗначенийЛитерала}, {@code ВозможныеТекстовыеЗначенияПараметраИзДругогоМетода},
 * {@code ВозможныеТекстовыеЗначенияВыраженияИзТекущегоМетода} и {@code ВозможныеКлючиСтруктуры}.
 *
 * <p>Литерал-аргумент вызова и индекс {@code [""]} — штатная точка расширения строковых подсказок: её строки
 * идут тем же путём, что строки провайдеров EDT. Провайдер «подходит» только внутри расчёта автодополнения
 * ({@link #beginAssist}): тот же реестр опрашивает проверка значений литералов, где согласие любого провайдера
 * ({@code isCorrectValue}) глушит штатные замечания. Прочие литералы ({@code Вид = "…"}) точка расширения не
 * видит — их значения добавляет {@link #merge}, он же убирает дубли со штатными строками.
 *
 * <p>Объекты ИР времени выполнения (колонки таблицы, ключи структуры, метаданные) здесь — типы EDT, модели
 * формы, макета и метаданных. Низковероятные значения ИР ({@code ДобавлятьНизкоВероятные}: литералы сравнений
 * и присваиваний) — только по Ctrl+Space.
 */
public final class BslLiteralValues extends AbstractStringLiteralProposalProvider
{
    /** {@code МаксВызываемыхМетодов}. */
    private static final int MAX_CALLS = 4;
    private static final int MAX_LITERAL_LENGTH = 200;
    private static final String GLOBAL_CONTEXT = "Глобальный"; //$NON-NLS-1$
    /** {@code ИмяПеременнойВозвращаемогоЗначения}. */
    private static final String RETURN_VARIABLE = "Воз073"; //$NON-NLS-1$
    /** Вызовы, через которые значение переменной не передаётся (ИР, Мультиметка241014_185112). */
    private static final Set<String> SKIPPED_CALLS = Set.of("типзнч", "нрег", "врег", "лев", "прав", "сред", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "сокрл", "сокрп", "сокрлп", "найти", "стрнайти", "стрдлина", "стрразделить", "значениезаполнено"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
    /** Методы, отбирающие по структуре ({@code ВозможныеКлючиСтруктуры}, «ОтборПоКолонкам»). */
    private static final Set<String> STRUCTURE_FILTER_METHODS = Set.of("найтистроки", "выгрузить", "скопировать", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "найтиследующий", "выбратьверсии", "получить", "findrows", "unload", "copy", "findnext", "get"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
    private static final Set<String> FORM_OPEN_METHODS = Set.of("открытьформу", "получитьформу", //$NON-NLS-1$ //$NON-NLS-2$
        "открытьформумодально", "openform", "getform", "openformmodal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    private static final Set<String> HANDLER_PARAMETERS = Set.of("имяметода", "имяпроцедуры", "имяфункции", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "имяобработчика", "действие"); //$NON-NLS-1$ //$NON-NLS-2$
    /** Параметры {@code ОформлениеКомпоновкиДанных.УстановитьЗначениеПараметра}. */
    private static final List<String> APPEARANCE_PARAMETERS = List.of("ВертикальноеПоложение", "Видимость", //$NON-NLS-1$ //$NON-NLS-2$
        "ВыделятьОтрицательные", "ГоризонтальноеПоложение", "Доступность", "МаксимальнаяШирина", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "ОтметкаНезаполненного", "Отображать", "Размещение", "Текст", "ТолькоПросмотр", "Формат", "ЦветФона", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "ЦветТекста", "Шрифт"); //$NON-NLS-1$ //$NON-NLS-2$
    /** Стандартные имена форм → свойство объекта метаданных с основной формой. */
    private static final Map<String, String> STANDARD_FORMS = Map.of("формаобъекта", "defaultObjectForm", //$NON-NLS-1$ //$NON-NLS-2$
        "формасписка", "defaultListForm", "формавыбора", "defaultChoiceForm", "формазаписи", "defaultRecordForm", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "формагруппы", "defaultFolderForm", "формавыборагруппы", "defaultFolderChoiceForm", "форма", "defaultForm"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    private static final Pattern TEMP_TABLE = Pattern.compile(
        "(?iu)(?:^|[^\\p{L}\\p{N}_])(?:ПОМЕСТИТЬ|INTO)\\s+([\\p{L}_][\\p{L}\\p{N}_]*)"); //$NON-NLS-1$
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*"); //$NON-NLS-1$
    private static final Pattern DYNAMIC_CALL_PREFIX = Pattern.compile("([\\p{L}_][\\p{L}\\p{N}_]*)\\."); //$NON-NLS-1$

    private static final ThreadLocal<Request> REQUEST = new ThreadLocal<>();

    /**
     * ПроверитьВыраженияУровня из ирКлсПолеТекстаПрограммы: только непустой полный список,
     * элементы через запятую, у сортировки — имя до первого пробела.
     */
    public static List<String> unknownLiteralValues(StringLiteral literal, BooleanSupplier canceled)
    {
        return unknownLiteralValues(literal, canceled, new Pass(), null);
    }

    /**
     * То же в общем проходе {@code pass}: одна проверка модуля — один проход, общий для всех его литералов.
     * Проход хранит ссылки на узлы AST — не переживает проверку модуля.
     */
    public static List<String> unknownLiteralValues(StringLiteral literal, BooleanSupplier canceled, Pass pass)
    {
        return unknownLiteralValues(literal, canceled, pass, null);
    }

    /** Те же полные списки для общего исправления опечаток; значение — варианты всего элемента списка. */
    static Map<String, List<String>> literalCorrectionNames(StringLiteral literal)
    {
        if (!TypeUtil.isStringLiteralContentTypesEnabled())
            return Map.of();
        Map<String, List<String>> corrections = new LinkedHashMap<>();
        unknownLiteralValues(literal, () -> !TypeUtil.isStringLiteralContentTypesEnabled(), new Pass(), corrections);
        return corrections;
    }

    private static List<String> unknownLiteralValues(StringLiteral literal, BooleanSupplier canceled, Pass pass,
        Map<String, List<String>> corrections)
    {
        boolean queryScopeOwner = BslQueryResultTypes.beginOpenModuleCalculation(literal);
        try
        {
            return unknownLiteralValuesInScope(literal, canceled, pass, corrections);
        }
        finally
        {
            BslQueryResultTypes.endOpenModuleCalculation(queryScopeOwner);
        }
    }

    private static List<String> unknownLiteralValuesInScope(StringLiteral literal, BooleanSupplier canceled, Pass pass,
        Map<String, List<String>> corrections)
    {
        if (!(literal.eResource() instanceof XtextResource resource))
            return List.of();
        Analysis analysis = analyzeLiteral(resource, literal, "", canceled, pass); //$NON-NLS-1$
        // Анализ родителя пути только этого литерала: «Объект.Код, Объект.Наименование» — «Объект.» один раз.
        Map<String, Analysis> byParent = new HashMap<>();
        byParent.put("", analysis); //$NON-NLS-1$
        List<String> unknown = new ArrayList<>();
        if (!analysis.full || analysis.values.isEmpty())
            return unknown;
        String value = literalText(literal);
        String[] parts = value.split(",", -1); //$NON-NLS-1$
        for (String part : parts)
        {
            String candidate = part.trim();
            if (analysis.sortDirection)
                candidate = candidate.split("\\s+", 2)[0]; //$NON-NLS-1$
            if (candidate.isEmpty())
                continue;
            String[] path = analysis.pathAware ? candidate.split("\\.", -1) : new String[] {candidate}; //$NON-NLS-1$
            String parent = ""; //$NON-NLS-1$
            int segmentOffset = 0;
            for (String segment : path)
            {
                analysis.checkCanceled();
                Analysis current = byParent.get(parent);
                if (current == null)
                {
                    current = analyzeLiteral(resource, literal, parent, canceled, pass);
                    byParent.put(parent, current);
                }
                if (!current.full || current.values.isEmpty())
                    break;
                String name = segment.trim();
                if (!current.values.containsKey(lower(name)))
                {
                    unknown.add(part.trim());
                    if (corrections != null)
                    {
                        String original = part.trim();
                        int from = segmentOffset + segment.indexOf(name);
                        List<String> legal = new ArrayList<>();
                        for (String allowed : current.values.values())
                            legal.add(original.substring(0, from) + allowed
                                + original.substring(from + name.length()));
                        corrections.put(original, legal);
                    }
                    break;
                }
                parent += name + "."; //$NON-NLS-1$
                segmentOffset += segment.length() + 1;
            }
        }
        return unknown;
    }

    private static Analysis analyzeLiteral(XtextResource resource, StringLiteral literal, String prefix,
        BooleanSupplier canceled, Pass pass)
    {
        Analysis analysis = new Analysis(resource, false, pass.russian(resource), prefix, offsetOf(literal), pass);
        analysis.validation = true;
        analysis.canceled = canceled;
        analysis.checkCanceled();
        EObject container = literal.eContainer();
        if (container instanceof IndexAccess access && access.getIndex() == literal)
            analysis.indexAccess(access);
        else
        {
            List<Expression> args = argumentsOf(container);
            int index = args.indexOf(literal);
            if (index >= 0)
                analysis.call(container, args, index, ""); //$NON-NLS-1$
            else
                analysis.outside(literal);
        }
        analysis.finish();
        return analysis;
    }

    /**
     * Общий проход анализа литералов: узлы области в порядке {@code eAllContents}, исходные методы экспортных
     * методов, язык и конфигурация проекта. Только для неизменной модели — на одну проверку модуля или один
     * расчёт; глобально не хранить. Текст узлов не запоминается: строки всех выражений модуля, удержанные до
     * конца его проверки в параллельных потоках, заметно поднимали расход памяти и нагрузку на GC.
     */
    public static final class Pass
    {
        private final Map<EObject, List<EObject>> contents = new IdentityHashMap<>();
        /** Исходный метод по набору ресурсов, в котором разрешался, и полному URI. */
        private final Map<Object, Map<URI, EObject>> sourceMethods = new IdentityHashMap<>();
        private final Map<XtextResource, Boolean> russian = new IdentityHashMap<>();
        private final Map<XtextResource, Configuration> configurations = new IdentityHashMap<>();

        public Pass()
        {
        }

        boolean russian(XtextResource resource)
        {
            return russian.computeIfAbsent(resource, BslLiteralValues::isRussian);
        }

        Configuration configuration(XtextResource resource)
        {
            if (!configurations.containsKey(resource))
            {
                IV8Project project = v8Project(resource);
                configurations.put(resource, project instanceof IConfigurationAware aware ? aware.getConfiguration() : null);
            }
            return configurations.get(resource);
        }

        /** Узлы области в порядке {@code eAllContents}; индекс запоминается только построенным целиком. */
        List<EObject> contents(EObject scope, Runnable checkCanceled)
        {
            List<EObject> result = contents.get(scope);
            if (result != null)
                return result;
            result = new ArrayList<>();
            var all = scope.eAllContents();
            while (all.hasNext())
            {
                checkCanceled.run();
                result.add(all.next());
            }
            contents.put(scope, result);
            return result;
        }
    }

    /** Один расчёт автодополнения: ручной ли вызов и строки, добавленные этим классом. */
    private static final class Request
    {
        final boolean manual;
        final Set<String> produced = new HashSet<>();
        /** Значения полного набора из Analysis.full, приведённые к ключу dedupeKey. */
        final Set<String> fullValues = new HashSet<>();
        /** Описание строки провайдера по тексту вставки: свойство с типом значения. */
        final Map<String, Object> infos = new HashMap<>();
        final Set<ICompletionProposal> added = Collections.newSetFromMap(new IdentityHashMap<>());

        Request(boolean manual)
        {
            this.manual = manual;
        }

        void rememberFull(Analysis analysis, String prefix)
        {
            if (analysis.full && !analysis.values.isEmpty())
                for (String name : analysis.values.values())
                    fullValues.add(lower((prefix + name).trim()));
        }
    }

    /** Начало штатного расчёта автодополнения в этом потоке; {@code manual} — Ctrl+Space. */
    static void beginAssist(boolean manual)
    {
        REQUEST.set(new Request(manual));
    }

    static void endAssist()
    {
        REQUEST.remove();
    }

    /** Общее описание значения литерала для строки списка и обеих подсказок. */
    static Property valueDescription(String name, Collection<? extends TypeItem> types)
    {
        var property = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createDerivedProperty();
        property.setName(name);
        property.setNameRu(name);
        property.setReadable(true);
        property.setEnvironments(Environments.ALL);
        var container = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createTypeContainerRef();
        container.getTypes().addAll(types);
        property.setTypeContainer(container);
        return property;
    }

    /** Поздно созданные предложения используют тот же штатный hover, что строки провайдера. */
    static void describeProposal(ConfigurableCompletionProposal proposal, Property info, ITextViewer viewer)
    {
        if (!(viewer.getDocument() instanceof IXtextDocument document))
            return;
        XtextResource resource = document.readOnly(r -> r);
        var services = resource.getResourceServiceProvider();
        describeProposal(proposal, info, viewer, resource, services.get(IEObjectHover.class));
    }

    private static void describeProposal(ConfigurableCompletionProposal proposal, Object info,
        ITextViewer viewer, XtextResource resource, IEObjectHover hover)
    {
        if (Global.getField(proposal, "additionalProposalInfo") == null) //$NON-NLS-1$
            proposal.setAdditionalProposalInfo(info);
        if (Global.getField(proposal, "hover") == null && hover != null) //$NON-NLS-1$
        {
            proposal.setHover(hover);
            proposal.setProposalContextResource(resource);
            Global.setField(proposal, "viewer", viewer); //$NON-NLS-1$
        }
    }

    /** Только границы слова: получение региона не запускает расчёт типов. */
    static org.eclipse.jface.text.IRegion valueHoverRegion(ITextViewer viewer, int offset)
    {
        IDocument document = viewer.getDocument();
        try
        {
            var partition = document.getPartition(offset);
            if (!org.eclipse.xtext.ui.editor.model.TerminalsTokenTypeToPartitionMapper.STRING_LITERAL_PARTITION
                .equals(partition.getType()))
                return null;
            int start = offset;
            int end = offset;
            while (start > partition.getOffset() && Character.isJavaIdentifierPart(document.getChar(start - 1)))
                start--;
            while (end < partition.getOffset() + partition.getLength()
                && Character.isJavaIdentifierPart(document.getChar(end)))
                end++;
            return end > start ? new org.eclipse.jface.text.Region(start, end - start) : null;
        }
        catch (org.eclipse.jface.text.BadLocationException failure)
        {
            return null;
        }
    }

    /** Наведение берёт то же описание значения, которое используется в списке литерала. */
    static BslEditorHoverHook.ContentHover valueHover(ITextViewer viewer, int offset)
    {
        var region = valueHoverRegion(viewer, offset);
        if (region == null || !(viewer.getDocument() instanceof IXtextDocument document))
            return null;
        try
        {
            return ContentAssistSessionReloader.readOnlyPeekAst(document, resource ->
            {
                EObject object = new org.eclipse.xtext.resource.EObjectAtOffsetHelper()
                    .resolveContainedElementAt(resource, offset);
                StringLiteral literal = EcoreUtil2.getContainerOfType(object, StringLiteral.class);
                if (literal == null)
                    return null;
                String name = literalText(literal);
                Property property;
                EObject target = literal.eContainer();
                List<Expression> args = argumentsOf(target);
                int index = args.indexOf(literal);
                if (target instanceof Invocation invocation && index == 0
                    && invocation.getMethodAccess() != null
                    && Set.of("установитьпараметр", "setparameter").contains(lower(invocation.getMethodAccess().getName()))) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    var parameters = BslQueryResultTypes.queryParametersAt(resource, offsetOf(literal) + 1);
                    property = parameters == null ? null : parameters.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue)
                        .findFirst().orElse(null);
                }
                else
                {
                    // Регион — целое слово под указателем; хвост списка после него не участвует.
                    String prefix = BslQueryLiteralSupport.literalTextBefore(document,
                        region.getOffset() + region.getLength());
                    if (prefix == null)
                        return null;
                    Segment segment = Segment.of(prefix);
                    Analysis analysis = new Analysis(resource, true, isRussian(resource),
                        prefix.substring(0, segment.start), offsetOf(literal));
                    if (target instanceof IndexAccess access && access.getIndex() == literal)
                        analysis.indexAccess(access);
                    else if (index >= 0)
                        analysis.call(target, args, index, ""); //$NON-NLS-1$
                    else
                        analysis.outside(literal);
                    analysis.finish();
                    property = analysis.info(prefix.substring(segment.start));
                }
                if (property == null)
                    return null;
                IEObjectHover hover = resource.getResourceServiceProvider().get(IEObjectHover.class);
                if (hover == null)
                    return null;
                Object info = hover.getHoverInfo(property, viewer, region);
                BslStructureInsertCommentTypes.completePropertyHover(info, property, viewer);
                info = BslEditorHoverHook.decorateSideHint(info, property, viewer);
                var creator = hover instanceof org.eclipse.jface.text.ITextHoverExtension extension
                    ? extension.getHoverControlCreator() : null;
                return info == null ? null : new BslEditorHoverHook.ContentHover(info, creator);
            });
        }
        catch (RuntimeException | LinkageError failure)
        {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Точка расширения: литерал-аргумент вызова и индекс
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean isAppropriate(Triple<EObject, List<Expression>, Integer> context)
    {
        EObject target = context != null ? context.getFirst() : null;
        return REQUEST.get() != null && (target instanceof Invocation
            || target instanceof OperatorStyleCreator || target instanceof IndexAccess);
    }

    @Override
    public List<Triple<String, String, IBslStringLiteralProposalImageProvider>> computeProposals(
        Triple<EObject, List<Expression>, Integer> context, String prefix, IScopeProvider scope,
        IIndexSlicePredicateService slices, boolean russian)
    {
        Request request = REQUEST.get();
        EObject target = context != null ? context.getFirst() : null;
        if (request == null || target == null || !(target.eResource() instanceof XtextResource resource))
            return List.of();
        String typed = prefix != null ? prefix : ""; //$NON-NLS-1$
        List<Expression> args = context.getSecond() != null ? context.getSecond() : List.of();
        int index = context.getThird() != null ? context.getThird() : -1;
        Expression literal = index >= 0 && index < args.size() ? args.get(index) : null;
        Analysis analysis = new Analysis(resource, request.manual, russian, typed,
            literal != null ? offsetOf(literal) : offsetOf(target));
        try
        {
            analysis.call(target, args, index, ""); //$NON-NLS-1$
            analysis.finish();
        }
        catch (RuntimeException | StackOverflowError e)
        {
            return List.of();
        }
        Segment segment = Segment.of(typed);
        request.rememberFull(analysis, typed.substring(0, segment.start));
        String before = "\"" + typed.substring(0, segment.start); //$NON-NLS-1$
        List<Triple<String, String, IBslStringLiteralProposalImageProvider>> proposals = new ArrayList<>();
        for (String name : analysis.values.values())
        {
            String replacement = before + name;
            request.produced.add(replacement);
            Property info = analysis.info(name);
            if (info != null)
                request.infos.put(replacement, info);
            proposals.add(Tuples.create(replacement, name, IBslStringLiteralProposalImageProvider.NULL_IMAGE_PROVIDER));
        }
        return proposals;
    }

    @Override
    public Collection<QualifiedName> getExportedName(Triple<EObject, List<Expression>, Integer> context,
        IScopeProvider scope)
    {
        return List.of();
    }

    @Override
    public List<IReferenceDescription> getReferenceDescriptions(Triple<EObject, List<Expression>, Integer> context,
        IScopeProvider scope)
    {
        return List.of();
    }

    @Override
    public boolean isCorrectValue(String value, Triple<EObject, List<Expression>, Integer> context,
        IScopeProvider scope, IIndexSlicePredicateService slices)
    {
        // Значение литерала этот провайдер не проверяет.
        return false;
    }

    @Override
    public boolean hasHyperlinks(Triple<EObject, List<Expression>, Integer> context)
    {
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Литерал вне вызова и удаление дублей
    // ------------------------------------------------------------------------------------------

    /** @param infos описания строк (свойство с типом или {@code null}) в порядке {@code names} */
    private record Added(List<String> names, List<Property> infos, int segmentOffset) {}

    /**
     * Ответ штатного расчёта в строковом литерале: значения литерала вне вызова ({@code Вид = "…"}) и без
     * дублей. Из двух одинаковых строк остаётся строка EDT (у неё значок и описание).
     */
    static ICompletionProposal[] merge(ITextViewer viewer, int offset, ICompletionProposal[] raw)
    {
        Request request = REQUEST.get();
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        boolean literal = doc != null && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, offset);
        boolean query = doc != null && BslQueryLiteralSupport.segmentKeyAt(doc, offset) >= 0;
        if (request == null || raw == null || !(doc instanceof IXtextDocument xdoc) || !literal || query)
            return raw;
        List<ICompletionProposal> result = new ArrayList<>(Arrays.asList(raw));
        try
        {
            Added added = xdoc.readOnly(resource -> outsideCall(resource, doc, offset, request));
            if (added != null && !added.names.isEmpty())
            {
                for (int i = 0; i < added.names.size(); i++)
                {
                    String name = added.names.get(i);
                    ConfigurableCompletionProposal proposal = new ConfigurableCompletionProposal(name,
                        added.segmentOffset, Math.max(0, offset - added.segmentOffset), name.length(), null,
                        new StyledString(name), null, null);
                    if (added.infos.get(i) != null)
                        proposal.setAdditionalProposalInfo(added.infos.get(i));
                    request.added.add(proposal);
                    result.add(proposal);
                }
            }
        }
        catch (Exception | StackOverflowError e)
        {
            // Без значений литерала вне вызова — список EDT как есть.
        }
        // Строкам провайдера EDT описание не передаёт: тип значения — по тексту вставки. Боковую подсказку из
        // описания строит штатный hover, как у строк свойств (см. CommonModuleAssistGroups.memberProposal).
        XtextResource resource = null;
        try
        {
            resource = xdoc.readOnly(r -> r);
        }
        catch (Exception e)
        {
            // Без ресурса — строки без боковой подсказки.
        }
        IEObjectHover hover = null;
        if (resource != null && resource.getURI() != null)
        {
            var services = org.eclipse.xtext.resource.IResourceServiceProvider.Registry.INSTANCE
                .getResourceServiceProvider(resource.getURI());
            hover = services != null ? services.get(IEObjectHover.class) : null;
        }
        for (ICompletionProposal proposal : result)
        {
            if (!(SmartContentAssistProcessor.unwrapProposal(proposal) instanceof ConfigurableCompletionProposal configurable))
                continue;
            Object info = request.infos.get(configurable.getReplacementString());
            if (info == null && request.added.contains(configurable))
                info = Global.getField(configurable, "additionalProposalInfo"); //$NON-NLS-1$
            if (info == null)
                continue;
            describeProposal(configurable, info, viewer, resource, hover);
        }
        int quote = literalQuote(doc, offset);
        if (quote >= 0 && !request.fullValues.isEmpty())
            result.removeIf(proposal -> !request.fullValues.contains(dedupeKey(proposal, doc, quote)));
        if (quote < 0)
            return result.toArray(ICompletionProposal[]::new);
        result = new ArrayList<>(Arrays.asList(dedupe(result, request, doc, quote)));
        addAllProperties(result, doc, quote, offset);
        return result.toArray(ICompletionProposal[]::new);
    }

    /**
     * Тип ключа {@code key} по значениям литерала ключей (первый параметр {@code call}): ожидаемый тип значения
     * в {@code Новый Структура("Ключ", |)} и {@code Структура.Вставить("Ключ", |)}.
     */
    static List<TypeItem> structureKeyTypes(XtextResource resource, EObject call, List<Expression> args, String key)
    {
        if (key == null || key.isBlank())
            return List.of();
        List<TypeItem> types = structureKeysTypes(resource, call, args).get(lower(key.trim()));
        return types != null ? types : List.of();
    }

    /** Сужение обобщённого типа параметра до типа поля; остальные типы сигнатуры сохраняются. */
    public static List<TypeItem> dataLockFieldTypes(XtextResource resource, List<TypeItem> sourceTypes, String field,
        List<TypeItem> parameterTypes)
    {
        Analysis analysis = new Analysis(resource, false, isRussian(resource), "", -1); //$NON-NLS-1$
        analysis.dataLockFields(sourceTypes);
        List<TypeItem> fieldTypes = analysis.valueTypes.getOrDefault(lower(field), List.of());
        List<TypeItem> expected = new ArrayList<>();
        for (TypeItem parameterType : parameterTypes)
        {
            Type resolved = analysis.resolve(parameterType);
            if (resolved != null && "Arbitrary".equals(resolved.getName()) && !fieldTypes.isEmpty()) //$NON-NLS-1$
            {
                for (TypeItem fieldType : fieldTypes)
                    if (!expected.contains(fieldType))
                        expected.add(fieldType);
            }
            else if (!expected.contains(parameterType))
                expected.add(parameterType);
        }
        return expected;
    }

    /** Типы всех ключей литерала ключей (первый параметр {@code call}) по имени в нижнем регистре. */
    static Map<String, List<TypeItem>> structureKeysTypes(XtextResource resource, EObject call, List<Expression> args)
    {
        if (resource == null || call == null || args == null || args.isEmpty())
            return Map.of();
        try
        {
            Analysis analysis = new Analysis(resource, false, isRussian(resource), "", offsetOf(args.get(0))); //$NON-NLS-1$
            analysis.call(call, args, 0, ""); //$NON-NLS-1$
            return analysis.valueTypes;
        }
        catch (RuntimeException | StackOverflowError e)
        {
            return Map.of();
        }
    }

    /** Открывающая кавычка всего литерала, включая строки продолжения. */
    private static int literalQuote(IDocument doc, int offset)
    {
        try
        {
            return BslQueryLiteralSupport.literalOpenQuoteBefore(doc, offset);
        }
        catch (RuntimeException e)
        {
            return -1;
        }
    }

    private static Added outsideCall(XtextResource resource, IDocument doc, int offset, Request request)
    {
        if (resource == null || resource.getParseResult() == null || offset <= 0)
            return null;
        INode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset - 1);
        EObject semantic = leaf != null ? NodeModelUtils.findActualSemanticObjectFor(leaf) : null;
        if (!(semantic instanceof StringLiteral literal))
            return null;
        EObject container = literal.eContainer();
        // Однострочный аргумент и индекс обслуживает провайдер точки расширения.
        boolean call = container instanceof Invocation || container instanceof OperatorStyleCreator
            || container instanceof FunctionStyleCreator
            || container instanceof IndexAccess access && access.getIndex() == literal;
        if (call && text(literal).indexOf('\n') < 0 && text(literal).indexOf('\r') < 0)
            return null;
        int start = offsetOf(literal);
        if (start < 0 || start >= offset)
            return null;
        String prefix;
        try
        {
            prefix = doc.get(start + 1, offset - start - 1);
        }
        catch (org.eclipse.jface.text.BadLocationException e)
        {
            return null;
        }
        Analysis analysis = new Analysis(resource, request.manual, isRussian(resource), prefix, start);
        if (container instanceof IndexAccess access && access.getIndex() == literal)
            analysis.indexAccess(access);
        else if (call)
        {
            List<Expression> args = argumentsOf(container);
            analysis.call(container, args, args.indexOf(literal), ""); //$NON-NLS-1$
        }
        else
            analysis.outside(literal);
        analysis.finish();
        request.rememberFull(analysis, prefix.substring(0, Segment.of(prefix).start));
        List<String> names = new ArrayList<>(analysis.values.values());
        List<Property> infos = new ArrayList<>();
        for (String name : names)
            infos.add(analysis.info(name));
        return new Added(names, infos, start + 1 + Segment.of(prefix).start);
    }

    private static ICompletionProposal[] dedupe(List<ICompletionProposal> list, Request request, IDocument doc,
        int quote)
    {
        Map<String, Integer> first = new HashMap<>();
        List<ICompletionProposal> result = new ArrayList<>(list.size());
        for (ICompletionProposal proposal : list)
        {
            String key = dedupeKey(proposal, doc, quote);
            Integer at = key != null ? first.get(key) : null;
            if (key == null || at == null)
            {
                if (key != null)
                    first.put(key, result.size());
                result.add(proposal);
                continue;
            }
            if (isOurs(result.get(at), request) && !isOurs(proposal, request))
                result.set(at, proposal);
        }
        return result.toArray(ICompletionProposal[]::new);
    }

    /**
     * Текст литерала после вставки строки: от кавычки до места замены плюс замена. Строки EDT заменяют с
     * кавычки («"Справочник.Валюты»), наши — только текущий сегмент; одинаковый итог — дубль.
     */
    private static String dedupeKey(ICompletionProposal proposal, IDocument doc, int quote)
    {
        ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
        if (!(raw instanceof ConfigurableCompletionProposal configurable))
            return null;
        String replacement = configurable.getReplacementString();
        int replacementOffset = configurable.getReplacementOffset();
        if (replacement == null || replacementOffset < quote)
            return null;
        String value;
        try
        {
            value = doc.get(quote, replacementOffset - quote) + replacement;
        }
        catch (org.eclipse.jface.text.BadLocationException e)
        {
            return null;
        }
        value = value.startsWith("\"") ? value.substring(1).trim() : value.trim(); //$NON-NLS-1$
        return value.isEmpty() ? null : lower(value);
    }

    /** ИР: список свойств в литерале без запятой; заменяется весь контекст внутри кавычек. */
    private static void addAllProperties(List<ICompletionProposal> result, IDocument doc, int quote, int offset)
    {
        try
        {
            String typed = doc.get(quote + 1, offset - quote - 1);
            List<String> names = new ArrayList<>();
            if (typed.indexOf(',') < 0 && result.size() > 1 && result.size() < 100)
            {
                for (ICompletionProposal proposal : result)
                {
                    if (!(SmartContentAssistProcessor.unwrapProposal(proposal)
                        instanceof ConfigurableCompletionProposal configurable))
                        continue;
                    int from = configurable.getReplacementOffset();
                    String replacement = configurable.getReplacementString();
                    if (from < quote || from > offset || replacement == null)
                        continue;
                    String name = doc.get(quote, from - quote) + replacement;
                    if (name.startsWith("\"")) //$NON-NLS-1$
                        name = name.substring(1);
                    name = name.trim();
                    String leaf = name.substring(name.lastIndexOf('.') + 1);
                    if (!IDENTIFIER.matcher(leaf).matches() || in(leaf, "ЭтотОбъект", "ThisObject")) //$NON-NLS-1$ //$NON-NLS-2$
                        continue;
                    if (!names.contains(leaf))
                        names.add(leaf);
                }
                names.sort(String.CASE_INSENSITIVE_ORDER);
                if (!names.isEmpty())
                {
                    int wordStart = SmartContentAssistProcessor.computeIdentifierWordStart(doc, offset);
                    result.add(BslAddedWordProposal.literalProperties(String.join(", ", names), quote + 1, //$NON-NLS-1$
                        doc.get(quote + 1, wordStart - quote - 1)));
                }
            }
        }
        catch (org.eclipse.jface.text.BadLocationException e)
        {
            // Документ изменился во время расчёта — генератор не добавляется.
        }
    }

    private static boolean isOurs(ICompletionProposal proposal, Request request)
    {
        ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
        return request.added.contains(raw) || raw instanceof ConfigurableCompletionProposal configurable
            && request.produced.contains(configurable.getReplacementString());
    }

    // ------------------------------------------------------------------------------------------
    // Набранное в литерале
    // ------------------------------------------------------------------------------------------

    /**
     * Текущий сегмент набранного: начало слова (после запятой или точки) и {@code мРодительскийКонтекст} —
     * путь до последней точки внутри элемента списка через запятую.
     */
    private record Segment(int start, String parent)
    {
        static Segment of(String typed)
        {
            // Пробелы вместо синтаксиса продолжения сохраняют смещения вставки в документе.
            typed = Pattern.compile("(?m)^[\\t ]*(?:\\||//[^\\r\\n]*)") //$NON-NLS-1$
                .matcher(typed).replaceAll(match -> " ".repeat(match.group().length())); //$NON-NLS-1$
            int comma = typed.lastIndexOf(',');
            int dot = typed.lastIndexOf('.');
            int start = Math.max(comma, dot) + 1;
            while (start < typed.length() && Character.isWhitespace(typed.charAt(start)))
                start++;
            String parent = dot > comma ? typed.substring(comma + 1, dot).trim() : ""; //$NON-NLS-1$
            return new Segment(start, parent);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Расчёт
    // ------------------------------------------------------------------------------------------

    /** Место параметра вызова: метод, имя параметра ({@code ИмяПараметраВызоваМетода}) и тип контекста. */
    private record Call(EObject target, List<Expression> args, int index, String method, String parameter,
        String context, Expression source, List<TypeItem> sourceTypes, Method moduleMethod, boolean constructor)
    {
        @Override
        public String toString()
        {
            return method + "(" + index + ":" + parameter + ")@" + context //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + (moduleMethod != null ? " module" : "") + (constructor ? " ctor" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
    }

    private static final class Analysis
    {
        final XtextResource resource;
        final boolean manual;
        final boolean russian;
        final String parent;
        /** Литерал в документе: состояние переменных берётся здесь ({@code мПозицияВБлоке}). */
        final int position;
        final Module module;
        final Map<String, String> values = new LinkedHashMap<>();
        /** Типы значений по имени в нижнем регистре; запоминаются и для имён, ещё не попавших в список. */
        final Map<String, List<TypeItem>> valueTypes = new HashMap<>();
        final Map<String, Property> valueProperties = new HashMap<>();
        final List<String> branches = new ArrayList<>();
        /** {@code ЛиНаборЗначенийПолный}. */
        boolean full;
        /** Ограничивать полноту по веткам только при поиске роли в вызываемом методе. */
        ParameterSearch parameterSearch;
        boolean sortDirection;
        /** ИР мРежимПроверкиМодуля: поиск существования и добавление ключа не ограничены текущими именами. */
        boolean validation;
        BooleanSupplier canceled;
        /** Ветка учла {@code мРодительскийКонтекст}; иначе после точки значений нет. */
        boolean pathAware;
        /** {@code СчетчикиВызовов}. */
        final Map<String, Integer> calls = new HashMap<>();
        /** {@code КлючевыеПараметры}: типы фактических параметров для параметров вызываемого метода. */
        final Map<FormalParam, List<TypeItem>> bindings = new IdentityHashMap<>();
        final Set<String> visitedParameters = new HashSet<>();
        final Pass pass;

        Analysis(XtextResource resource, boolean manual, boolean russian, String typed, int position)
        {
            this(resource, manual, russian, typed, position, new Pass());
        }

        Analysis(XtextResource resource, boolean manual, boolean russian, String typed, int position, Pass pass)
        {
            this.pass = pass;
            this.resource = resource;
            this.manual = manual;
            this.russian = russian;
            this.parent = Segment.of(typed).parent;
            this.position = position;
            this.module = !resource.getContents().isEmpty() && resource.getContents().get(0) instanceof Module m
                ? m : null;
        }

        void add(String name)
        {
            if (name != null && !name.isBlank())
                values.putIfAbsent(lower(name.trim()), name.trim());
        }

        /** Тип значения: для свойства, колонки, реквизита — его тип ({@code ТаблицаТипов} слова ИР). */
        void rememberTypes(String name, Collection<? extends TypeItem> types)
        {
            if (name == null || name.isBlank() || types == null || types.isEmpty())
                return;
            List<TypeItem> known = valueTypes.computeIfAbsent(lower(name.trim()), key -> new ArrayList<>());
            for (TypeItem type : types)
            {
                // Разрешённый тип: подсказка EDT разрешает ссылку относительно ресурса контекста, а у описания
                // строки его нет — неразрешённая ссылка давала «Свойство <null> Имя».
                Type resolved = resolve(type);
                TypeItem item = resolved != null ? resolved : type;
                if (item != null && !known.contains(item))
                    known.add(item);
            }
        }

        void add(String name, Collection<? extends TypeItem> types)
        {
            rememberTypes(name, types);
            add(name);
        }

        void add(String name, com._1c.g5.v8.dt.mcore.TypeDescription description)
        {
            add(name, description != null ? description.getTypes() : null);
        }

        /** Значение — само свойство типа (колонка, ключ структуры, свойство объекта). */
        void rememberProperty(Property property)
        {
            String name = dualName(property);
            if (name.isBlank())
                return;
            valueProperties.putIfAbsent(lower(name), property);
            rememberTypes(name, property.getTypes());
        }

        /**
         * Описание строки списка: свойство с типом значения, как у строк свойств EDT. По нему список пишет
         * тип, а боковая подсказка — тип со ссылкой. Значению не из свойства (реквизит формы или метаданных,
         * параметр формы) — свойство с его типами.
         */
        Property info(String name)
        {
            Property property = valueProperties.get(lower(name));
            if (property != null && property.getTypes().stream().noneMatch(type -> type == null || type.eIsProxy()))
                return property;
            List<TypeItem> types = valueTypes.get(lower(name));
            if (types == null || types.isEmpty())
                return property;
            // Копия с разрешёнными типами (ссылками, без переноса из модели).
            Property derived = valueDescription(name, types);
            derived.setName(property != null && property.getName() != null ? property.getName() : name);
            derived.setNameRu(property != null && property.getNameRu() != null ? property.getNameRu() : name);
            return derived;
        }

        void addAll(Collection<String> names)
        {
            for (String name : names)
                add(name);
        }

        void branch(String name)
        {
            branches.add(name);
        }

        void finish()
        {
            // Единственный шаблонный член не задаёт полный список допустимых имён.
            if (values.size() == 1)
            {
                Property property = valueProperties.get(values.keySet().iterator().next());
                if (property != null && BslDocCommentTypeMerge.isStructureKeyPlaceholder(property))
                    full = false;
            }
            if (!parent.isEmpty() && !pathAware)
            {
                values.clear();
                full = false;
            }
        }

        // ---- ЗаполнитьТаблицуВозможныхЗначенийЛитерала: литерал-параметр вызова ----

        void call(EObject target, List<Expression> args, int index, String structureType)
        {
            boolean queryScopeOwner = BslQueryResultTypes.beginOpenModuleCalculation(module);
            try
            {
                callInScope(target, args, index, structureType);
            }
            finally
            {
                BslQueryResultTypes.endOpenModuleCalculation(queryScopeOwner);
            }
        }

        private void callInScope(EObject target, List<Expression> args, int index, String structureType)
        {
            checkCanceled();
            if (structureType.isEmpty() && index == 0 && target instanceof Invocation invocation
                && invocation.getMethodAccess() instanceof StaticFeatureAccess access
                && in(access.getName(), "Тип", "Type")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                typeNameLiteral(invocation);
                return;
            }
            if (target instanceof IndexAccess access)
            {
                if (structureType.isEmpty())
                    indexAccess(access);
                return;
            }
            Call call = describe(target, args, index);
            if (call == null)
                return;
            branch(call.toString());
            if (call.moduleMethod != null)
                moduleMethodCall(call, structureType);
            else if (structureType.isEmpty())
                platformCall(call);
        }

        /** ИР: ЗаполнитьТаблицуСлов, ветка Тип("…") с полным набором имён из внешнего вызова. */
        void typeNameLiteral(Invocation invocation)
        {
            branch("typeNameLiteral"); //$NON-NLS-1$
            EObject outer = enclosingCall(invocation);
            if (!(outer instanceof Invocation outerInvocation) || outerInvocation.getMethodAccess() == null)
                return;
            FeatureAccess access = outerInvocation.getMethodAccess();
            String method = access.getName();
            List<TypeItem> resultTypes = new ArrayList<>();
            for (FeatureEntry entry : featureEntries(access))
                if (entry != null && entry.getFeature() instanceof com._1c.g5.v8.dt.mcore.Method platformMethod
                    && !platformMethod.eIsProxy())
                    resultTypes.addAll(platformMethod.getRetValType());
            List<String> names = typeNames(resultTypes);
            // ЛиИмяМетодаВозвращающегоЗначениеТипаИзПараметра — без ограничения номера параметра.
            if (in(method, "Добавить", "Add", "Вставить", "Insert", "НайтиОбъект", "FindObject", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "ПолучитьОбъект", "GetObject")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                if (names.size() > 1)
                    addAll(names);
            }
            else if (in(method, "УстановитьЭлементУправления", "SetControl")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                add(russian ? "ПолеВвода" : "TextBox"); //$NON-NLS-1$ //$NON-NLS-2$
                add(russian ? "ПолеВыбора" : "ComboBox"); //$NON-NLS-1$ //$NON-NLS-2$
                add(russian ? "Флажок" : "CheckBox"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if (in(method, "Выполнить", "Execute") && outerInvocation.getParams().indexOf(invocation) == 4 //$NON-NLS-1$ //$NON-NLS-2$
                && access instanceof DynamicFeatureAccess dynamic
                && hasType(typesOf(dynamic.getSource()), "DataCompositionTemplateComposer")) //$NON-NLS-1$
            {
                add(russian ? "ГенераторМакетаКомпоновкиДанных" : "DataCompositionTemplateGenerator"); //$NON-NLS-1$ //$NON-NLS-2$
                add(russian ? "ГенераторМакетаКомпоновкиДанныхДляКоллекцииЗначений" //$NON-NLS-1$
                    : "DataCompositionValueCollectionTemplateGenerator"); //$NON-NLS-1$
            }
            full = !values.isEmpty();
        }

        /** {@code [""]} у структуры: её ключи ({@code ЗначенияТекстовогоПеречисленияИзСтруктуры}). */
        void indexAccess(IndexAccess access)
        {
            List<TypeItem> types = typesOf(access.getSource());
            if (hasType(types, "Structure", "FixedStructure", "Map", "FixedMap")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {
                branch("index"); //$NON-NLS-1$
                addAll(structureKeyNames(types));
                full = !values.isEmpty();
            }
            // Таблицы менеджера временных таблиц: М.Таблицы["…"] (ИР стр. 7770, issue 889)
            else if (!validation && access.getIndex() instanceof StringLiteral literal && hasType(types, "QueryTempTables")) //$NON-NLS-1$
            {
                branch("tempTableIndex"); //$NON-NLS-1$
                List<String> names = BslTempTableSessions.literalNames(literal, org.eclipse.xtext.util.CancelIndicator.NullImpl);
                addAll(names != null && !names.isEmpty() ? names : tempTableNames());
            }
        }

        Call describe(EObject target, List<Expression> args, int index)
        {
            checkCanceled();
            if (index < 0)
                return null;
            if (target instanceof OperatorStyleCreator creator)
            {
                Type type = resolve(creator.getType());
                if (type == null)
                    return null;
                String name = typeName(type);
                List<List<Parameter>> variants = new ArrayList<>();
                for (Ctor ctor : Global.typeConstructors(type))
                    variants.add(ctor.getParams());
                Parameter parameter = chooseParameter(variants, args, index);
                return new Call(target, args, index, name, parameter != null ? dualName(parameter) : "", name, //$NON-NLS-1$
                    null, List.of(), null, true);
            }
            if (!(target instanceof Invocation invocation) || invocation.getMethodAccess() == null)
                return null;
            FeatureAccess access = invocation.getMethodAccess();
            String method = access.getName() != null ? access.getName() : ""; //$NON-NLS-1$
            Expression source = access instanceof DynamicFeatureAccess dynamic ? dynamic.getSource() : null;
            List<TypeItem> sourceTypes = source != null ? typesOf(source) : List.of();
            for (FeatureEntry entry : featureEntries(access))
            {
                EObject feature = entry != null ? entry.getFeature() : null;
                if (feature != null && feature.eIsProxy())
                    feature = EcoreUtil.resolve(feature, resource);
                // Экспортный метод общего модуля приходит методом его контекста — нужен исходный метод модуля
                // (как ParamHintHtmlModifier.sourceMethod).
                if (feature instanceof com._1c.g5.v8.dt.bsl.model.BslContextDefMethod exported
                    && exported.getSourceUri() != null)
                {
                    EObject sourceMethod = sourceMethod(exported);
                    if (sourceMethod instanceof Method && !sourceMethod.eIsProxy())
                        feature = sourceMethod;
                }
                if (feature instanceof Method moduleMethod && !feature.eIsProxy())
                {
                    String parameter = index < moduleMethod.getFormalParams().size()
                        ? nullToEmpty(moduleMethod.getFormalParams().get(index).getName()) : ""; //$NON-NLS-1$
                    return new Call(target, args, index, nullToEmpty(moduleMethod.getName()), parameter, "", source, //$NON-NLS-1$
                        sourceTypes, moduleMethod, false);
                }
                if (feature instanceof com._1c.g5.v8.dt.mcore.Method platformMethod && !feature.eIsProxy())
                {
                    List<List<Parameter>> variants = new ArrayList<>();
                    for (ParamSet set : platformMethod.getParamSet())
                        variants.add(set.getParams());
                    Parameter parameter = chooseParameter(variants, args, index);
                    Type owner = EcoreUtil2.getContainerOfType(platformMethod, Type.class);
                    String context = owner != null ? typeName(owner)
                        : source == null ? GLOBAL_CONTEXT : firstTypeName(sourceTypes);
                    return new Call(target, args, index, method, parameter != null ? dualName(parameter) : "", //$NON-NLS-1$
                        context, source, sourceTypes, null, false);
                }
            }
            return null;
        }

        /**
         * Исходный метод модуля экспортного метода: сначала из общей модели общего модуля
         * ({@link #sharedSourceMethod}), иначе {@link EcoreUtil#resolve(EObject, EObject)} в наборе контекста.
         * Запоминается в проходе по набору ресурсов контекста и полному URI.
         */
        EObject sourceMethod(com._1c.g5.v8.dt.bsl.model.BslContextDefMethod exported)
        {
            EObject context = exported instanceof com._1c.g5.v8.dt.bsl.model.BslContextDefMockMethod mock
                && mock.getContext() != null ? mock.getContext() : exported;
            var sourceUri = exported.getSourceUri();
            var contextResource = context.eResource();
            if (sourceUri.isRelative() && contextResource != null)
                sourceUri = sourceUri.resolve(contextResource.getURI());
            var sourceResourceUri = sourceUri.trimFragment();
            Object resolutionScope = contextResource == null ? null
                : contextResource.getResourceSet() != null ? contextResource.getResourceSet() : contextResource;
            Map<URI, EObject> known = resolutionScope != null
                ? pass.sourceMethods.computeIfAbsent(resolutionScope, key -> new HashMap<>()) : null;
            EObject cached = known != null ? known.get(sourceUri) : null;
            if (cached != null)
            {
                return cached;
            }
            Method shared = sharedSourceMethod(sourceResourceUri, exported.getName());
            if (shared != null)
            {
                if (known != null)
                    known.put(sourceUri, shared);
                return shared;
            }
            EObject proxy = org.eclipse.emf.ecore.EcoreFactory.eINSTANCE.createEObject();
            ((org.eclipse.emf.ecore.InternalEObject)proxy).eSetProxyURI(exported.getSourceUri());
            EObject sourceMethod = EcoreUtil.resolve(proxy, context);
            if (known != null)
                known.put(sourceUri, sourceMethod);
            return sourceMethod;
        }

        /**
         * Метод общего модуля из его модели, которую отдаёт {@code CommonModule.getModule()}: EDT держит её общей
         * для всех проверок. Разрешение {@code getSourceUri} в наборе ресурсов проверяемого модуля заново грузило
         * и разбирало общий модуль для каждого вызывающего модуля. {@code null} — не общий модуль или метод не
         * найден: тогда прежний путь.
         */
        Method sharedSourceMethod(URI sourceResourceUri, String methodName)
        {
            List<String> segments = sourceResourceUri.segmentsList();
            int src = segments.indexOf("src"); //$NON-NLS-1$
            if (methodName == null || src < 0 || src + 2 >= segments.size()
                || !"CommonModules".equals(segments.get(src + 1)) || configuration() == null) //$NON-NLS-1$
                return null;
            String name = URI.decode(segments.get(src + 2));
            CommonModule common = null;
            for (CommonModule candidate : configuration().getCommonModules())
                if (name.equalsIgnoreCase(candidate.getName()))
                    common = candidate;
            Module model = common != null ? common.getModule() : null;
            Method found = null;
            if (model != null && !model.eIsProxy())
                for (Method method : model.allMethods())
                    if (methodName.equalsIgnoreCase(method.getName()))
                    {
                        found = method;
                        break;
                    }
            return found;
        }

        /** {@code ПодобратьВариантСинтаксисаМетодаПоТипамПараметров}, как в {@link BslExpectedTypePremiums}. */
        Parameter chooseParameter(List<List<Parameter>> variants, List<Expression> args, int index)
        {
            List<List<Parameter>> withIndex = new ArrayList<>();
            for (List<Parameter> variant : variants)
                if (variant.size() > index)
                    withIndex.add(variant);
            if (withIndex.isEmpty())
                return null;
            if (withIndex.size() > 1)
                for (int i = 0; i < Math.min(args.size(), index); i++)
                {
                    List<String> actual = typeNames(typesOf(args.get(i)));
                    if (actual.isEmpty())
                        continue;
                    for (List<Parameter> variant : withIndex)
                        for (String formal : typeNames(variant.get(i).getType()))
                            if (containsIgnoreCase(actual, formal))
                                return variant.get(index);
                }
            return withIndex.get(withIndex.size() - 1).get(index);
        }

        /** Общее правило для прямого вызова и анализа параметра через вызываемый метод. */
        boolean isStructurePropertyProbe(String method, List<TypeItem> ownerTypes)
        {
            return hasType(ownerTypes, "Structure", "FixedStructure", "FormDataStructure") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                && in(method, "Свойство", "Property"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** Ветка «системный метод или конструктор» {@code ЗаполнитьТаблицуВозможныхЗначенийЛитерала}. */
        void platformCall(Call call)
        {
            full = true;
            String parameter = call.parameter;
            String parameterLower = lower(parameter);
            sortDirection |= in(call.method, "Сортировать", "Sort"); //$NON-NLS-1$ //$NON-NLS-2$
            String context = call.context;
            String method = call.method;
            boolean allowed = true;
            String structureVariable = ""; //$NON-NLS-1$
            String leftOfAssignment = ""; //$NON-NLS-1$
            Module handlerModule = null;
            List<TypeItem> objectTypes = call.sourceTypes;
            if (call.index == 0 && in(method, "УстановитьЗначение", "SetValue") //$NON-NLS-1$ //$NON-NLS-2$
                && hasType(objectTypes, "DataLockItem")) //$NON-NLS-1$
            {
                branch("dataLockFields"); //$NON-NLS-1$
                dataLockFields(objectTypes);
                return;
            }
            if (call.index == 0 && call.constructor
                && (in(method, "Структура", "ФиксированнаяСтруктура", "ПолеКомпоновкиДанных") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    || "Действие".equalsIgnoreCase(method) && "ИмяПроцедуры".equalsIgnoreCase(parameter))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                EObject creator = call.target;
                if (creator.eContainer() instanceof SimpleStatement statement && statement.getRight() == creator
                    && statement.getLeft() != null)
                {
                    leftOfAssignment = text(statement.getLeft());
                    if (in(method, "Структура", "ФиксированнаяСтруктура")) //$NON-NLS-1$ //$NON-NLS-2$
                    {
                        structureVariable = endsWithIgnoreCase(leftOfAssignment, ".ОтборСтрок") ? "" : leftOfAssignment; //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    else if (statement.getLeft() instanceof DynamicFeatureAccess left)
                    {
                        if ("ИмяПроцедуры".equalsIgnoreCase(parameter)) //$NON-NLS-1$
                            handlerModule = module;
                        else
                            objectTypes = typesOf(left.getSource());
                    }
                }
                else if (enclosingCall(creator) != null)
                {
                    EObject enclosing = enclosingCall(creator);
                    List<Expression> enclosingArgs = argumentsOf(enclosing);
                    int enclosingIndex = enclosingArgs.indexOf(creator);
                    if ("ИмяПроцедуры".equalsIgnoreCase(parameter)) //$NON-NLS-1$
                        handlerModule = module;
                    else
                    {
                        Call outer = describe(enclosing, enclosingArgs, enclosingIndex);
                        if (outer != null && outer.moduleMethod != null && "Ключи".equalsIgnoreCase(parameter)) //$NON-NLS-1$
                        {
                            branch("structureDown"); //$NON-NLS-1$
                            textValuesOfParameter(outer.moduleMethod, enclosingIndex, enclosingArgs, method);
                        }
                        else if (outer != null && in(method, "Структура", "ФиксированнаяСтруктура")) //$NON-NLS-1$ //$NON-NLS-2$
                        {
                            List<TypeItem> elements = outer.sourceTypes.isEmpty() ? List.of()
                                : elementTypes(returnTypes(enclosing));
                            if (!elements.isEmpty() && !hasType(elements, "ValueListItem")) //$NON-NLS-1$
                                objectTypes = elements;
                        }
                    }
                }
            }
            else if (STRUCTURE.equalsIgnoreCase(context) && "Вставить".equalsIgnoreCase(method) //$NON-NLS-1$
                && "Ключ".equalsIgnoreCase(parameter) && isSimpleName(call.source)) //$NON-NLS-1$
                structureVariable = text(call.source);
            else if ("Массив".equalsIgnoreCase(context) && "Добавить".equalsIgnoreCase(method) //$NON-NLS-1$ //$NON-NLS-2$
                && isSimpleName(call.source))
                structureVariable = text(call.source);
            if ("ПолеКомпоновкиДанных".equalsIgnoreCase(context) && moduleForm() != null //$NON-NLS-1$
                && hasFormType(objectTypes))
            {
                branch("compositionField"); //$NON-NLS-1$
                if (endsWithIgnoreCase(leftOfAssignment, ".Поле")) //$NON-NLS-1$
                {
                    addFormItems(moduleForm());
                    allowed = false;
                }
                else
                    addFormAttributes(moduleForm());
            }
            else if ("РеквизитФормы".equalsIgnoreCase(context) && "Путь".equalsIgnoreCase(parameter) //$NON-NLS-1$ //$NON-NLS-2$
                && moduleForm() != null)
            {
                branch("formAttributePath"); //$NON-NLS-1$
                addFormAttributes(moduleForm());
            }
            else if ("ФормаКлиентскогоПриложения".equalsIgnoreCase(context) && "ПолучитьРеквизиты".equalsIgnoreCase(method) //$NON-NLS-1$ //$NON-NLS-2$
                && "Путь".equalsIgnoreCase(parameter) && moduleForm() != null) //$NON-NLS-1$
            {
                branch("getAttributes"); //$NON-NLS-1$
                addFormAttributes(moduleForm());
            }
            if (values.isEmpty())
            {
                String structureType = in(context, STRUCTURE, "ФиксированнаяСтруктура", "Массив") ? context //$NON-NLS-1$ //$NON-NLS-2$
                    : call.constructor && in(method, STRUCTURE, "ФиксированнаяСтруктура") ? method : ""; //$NON-NLS-1$ //$NON-NLS-2$
                if (!structureVariable.isEmpty())
                    textValuesOfExpression(methodOf(call.target), structureVariable, structureType, false,
                        endOf(call.target));
                else if (!structureType.isEmpty() && call.constructor && call.index == 0)
                    structureKeys(scopeOf(call.target), null, (Expression)call.target, "", structureType, //$NON-NLS-1$
                        Integer.MAX_VALUE);
                if (!values.isEmpty())
                    allowed = false;
            }
            if (!allowed || STRUCTURE.equalsIgnoreCase(context) && "Ключ".equalsIgnoreCase(parameter) //$NON-NLS-1$
                && isTableType(objectTypes))
            {
                full = full && !values.isEmpty();
                return;
            }
            // Ограничения мРежимПроверкиМодуля в ЗаполнитьТаблицуВозможныхЗначенийЛитерала ИР.
            // Список из места передачи структуры (выше) остаётся допустимым ограничением для Вставить.
            if (validation && (isStructurePropertyProbe(method, objectTypes)
                || hasType(objectTypes, "Structure", "FixedStructure") && in(method, "Вставить", "Insert") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                || context.contains("КоллекцияКолонок") && in(method, "Найти", "Find"))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                full = false;
                return;
            }
            if (containsAny(parameterLower, "ключ", "индекс", "колонк", "сортировк", "группировк") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                && isTableType(objectTypes))
            {
                branch("columns"); //$NON-NLS-1$
                addAll(columns(objectTypes));
                // Колонки результата запроса знает только план запроса (у типа РезультатЗапроса их нет).
                if (hasType(objectTypes, "QueryResult") && call.source != null) //$NON-NLS-1$
                {
                    Map<String, List<TypeItem>> resultColumns = BslQueryResultTypes.resultColumnsOf(call.source);
                    if (resultColumns != null)
                        resultColumns.forEach(this::add);
                }
            }
            else if (containsAny(parameterLower, "ключ", "индекс", "колонк", "сортировк") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                && (hasType(objectTypes, "Structure", "FixedStructure") && !context.contains("Соответствие") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    || hasTabularSection(objectTypes) && (context.contains("ТабличнаяЧасть") || STRUCTURE.equalsIgnoreCase(context)))) //$NON-NLS-1$
            {
                branch("keys"); //$NON-NLS-1$
                addAll(hasTabularSection(objectTypes) ? elementProperties(objectTypes) : structureKeyNames(objectTypes));
            }
            else if ("URIПространстваИмен".equalsIgnoreCase(parameter)) //$NON-NLS-1$
            {
                branch("xdtoNamespaces"); //$NON-NLS-1$
                full = in(context, "ТипДанныхXML", "ФабрикаXDTO", "СериализаторXDTO"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                if (configuration() != null)
                    for (var xdto : configuration().getXDTOPackages())
                        add(xdto.getNamespace());
            }
            else if (call.constructor && "УникальныйИдентификатор".equalsIgnoreCase(method)) //$NON-NLS-1$
            {
                branch("uuid"); //$NON-NLS-1$
                full = false;
                add("00000000-0000-0000-0000-000000000000"); //$NON-NLS-1$
            }
            else if ("НаименованиеТаблицы".equalsIgnoreCase(parameter) //$NON-NLS-1$
                && (hasType(objectTypes, "QueryTempTable", "QueryTempTables", "TempTablesManager") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    || context.contains("ВременнаяТаблицаЗапроса") || context.contains("ВременныеТаблицыЗапроса"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                branch("tempTables"); //$NON-NLS-1$
                // Таблицы того же менеджера (ИР: пакеты МенеджерВременныхТаблиц, issue 889), иначе — все ПОМЕСТИТЬ модуля
                List<String> names = tempTableLiteralNames(call);
                addAll(names != null ? names : tempTableNames());
            }
            else if ("НаименованиеКолонки".equalsIgnoreCase(parameter) //$NON-NLS-1$
                && hasType(objectTypes, "QueryTempTableColumns")) //$NON-NLS-1$
            {
                branch("tempTableColumns"); //$NON-NLS-1$
                List<String> names = tempTableLiteralNames(call);
                full = names != null;
                if (names != null)
                    addAll(names);
            }
            else if ("ИмяРеквизита".equalsIgnoreCase(parameter) && "НайтиПоРеквизиту".equalsIgnoreCase(method)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                branch("findByAttribute"); //$NON-NLS-1$
                MdObject md = mdOfTypes(objectTypes);
                if (md != null)
                    addAll(mdChildren(md, "attributes")); //$NON-NLS-1$
            }
            else if (hasType(objectTypes, "FormDataCollection")) //$NON-NLS-1$
            {
                branch("formDataCollection"); //$NON-NLS-1$
                addAll(elementProperties(objectTypes));
            }
            else if ("ИмяРеквизита".equalsIgnoreCase(parameter) //$NON-NLS-1$
                && in(method, "ЗначениеВРеквизитФормы", "РеквизитФормыВЗначение")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                branch("formAttribute"); //$NON-NLS-1$
                if (moduleForm() != null)
                    for (FormAttribute attribute : moduleForm().getAttributes())
                        add(nameOf(attribute));
            }
            else if ("ИмяОбласти".equalsIgnoreCase(parameter) && hasType(objectTypes, "SpreadsheetDocument")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                branch("templateAreas"); //$NON-NLS-1$
                addTemplateAreas(call.source);
                full = false;
            }
            else if ("ПолучитьФорму".equalsIgnoreCase(method) && "ИмяФормы".equalsIgnoreCase(parameter)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                // Имена форм даёт EDT.
            }
            else if (mdOfTypes(objectTypes) != null)
            {
                MdObject md = mdOfTypes(objectTypes);
                branch("mdObject"); //$NON-NLS-1$
                if ("Макет".equalsIgnoreCase(parameter)) //$NON-NLS-1$
                    addAll(mdChildren(md, "templates")); //$NON-NLS-1$
                else if ("Форма".equalsIgnoreCase(parameter)) //$NON-NLS-1$
                    addAll(mdChildren(md, "forms")); //$NON-NLS-1$
                else if (!parameter.isEmpty() && !hasType(objectTypes, "ConstantManager") //$NON-NLS-1$
                    && !isObjectType(objectTypes))
                    addAll(mdFields(md));
            }
            else if ("ИмяПользователяИБ".equalsIgnoreCase(parameter)) //$NON-NLS-1$
            {
                // Пользователей информационной базы в модели проекта нет.
                branch("infobaseUsers"); //$NON-NLS-1$
            }
            else if ("Роль".equalsIgnoreCase(parameter)) //$NON-NLS-1$
            {
                branch("roles"); //$NON-NLS-1$
                if (configuration() != null)
                    addAll(names(configuration().getRoles()));
            }
            else if ("ОбщийМакет".equalsIgnoreCase(parameter)) //$NON-NLS-1$
            {
                branch("commonTemplates"); //$NON-NLS-1$
                if (configuration() != null)
                    addAll(names(configuration().getCommonTemplates()));
            }
            else if ("ПолучитьОбщуюФорму".equalsIgnoreCase(method) && "Форма".equalsIgnoreCase(parameter)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                branch("commonForms"); //$NON-NLS-1$
                if (configuration() != null)
                    addAll(names(configuration().getCommonForms()));
            }
            else if ("НайтиПредопределенное".equalsIgnoreCase(method) //$NON-NLS-1$
                && "МенеджерРегламентныхЗаданий".equalsIgnoreCase(context) && "Метаданные".equalsIgnoreCase(parameter)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                branch("scheduledJobs"); //$NON-NLS-1$
                if (configuration() != null)
                    addAll(names(configuration().getScheduledJobs()));
            }
            else if (FILL_PROPERTY_VALUES.equalsIgnoreCase(method)
                && in(parameter, "СписокСвойств", "ИсключаяСвойства") && !call.args.isEmpty()) //$NON-NLS-1$ //$NON-NLS-2$
            {
                branch("fillPropertyValues"); //$NON-NLS-1$
                fillPropertyValuesNames(call.args);
            }
            else if ("ОформлениеКомпоновкиДанных".equalsIgnoreCase(context) //$NON-NLS-1$
                && "УстановитьЗначениеПараметра".equalsIgnoreCase(method) && "Параметр".equalsIgnoreCase(parameter)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                branch("appearance"); //$NON-NLS-1$
                addAll(APPEARANCE_PARAMETERS);
            }
            else if (in(method, "ПолучитьФункциональнуюОпцию", "ПолучитьФункциональнуюОпциюИнтерфейса", //$NON-NLS-1$ //$NON-NLS-2$
                "ПолучитьФункциональнуюОпциюФормы") && "Имя".equalsIgnoreCase(parameter)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                branch("functionalOptions"); //$NON-NLS-1$
                if (configuration() != null)
                    addAll(names(configuration().getFunctionalOptions()));
            }
            else if (HANDLER_PARAMETERS.contains(parameterLower))
                handlerNames(call, handlerModule);
            else if ("ИмяСобытия".equalsIgnoreCase(parameter) && !GLOBAL_CONTEXT.equals(context)) //$NON-NLS-1$
            {
                branch("events"); //$NON-NLS-1$
                for (TypeItem item : objectTypes)
                {
                    Type type = resolve(item);
                    if (type != null)
                        for (Event event : type.allEvents())
                            add(dualName(event));
                }
            }
            else if ("Найти".equalsIgnoreCase(method) && hasType(objectTypes, "MetadataObjectCollection")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                branch("metadataCollection"); //$NON-NLS-1$
                addAll(metadataCollection(text(call.source)));
            }
            else if ("Найти".equalsIgnoreCase(method) && moduleForm() != null //$NON-NLS-1$
                && hasType(objectTypes, "FormAllItems")) //$NON-NLS-1$
            {
                branch("formItems"); //$NON-NLS-1$
                addFormItems(moduleForm());
            }
            else
                full = false;
        }

        /** Имена методов для параметра-обработчика ({@code ИмяПроцедуры}, {@code ИмяМетода}, …). */
        void handlerNames(Call call, Module handlerModule)
        {
            branch("handlers"); //$NON-NLS-1$
            boolean exportOnly = "ОписаниеОповещения".equalsIgnoreCase(call.context); //$NON-NLS-1$
            boolean idle = endsWithIgnoreCase(call.method, "Ожидания"); //$NON-NLS-1$
            List<Module> targets = new ArrayList<>();
            if (handlerModule != null)
                targets.add(handlerModule);
            else if (GLOBAL_CONTEXT.equals(call.context))
            {
                if (configuration() != null)
                    for (CommonModule common : configuration().getCommonModules())
                        if (common.isGlobal() && common.getModule() != null)
                            targets.add(common.getModule());
                exportOnly = true;
            }
            else if ("ОписаниеОповещения".equalsIgnoreCase(call.context)) //$NON-NLS-1$
                targets.add(call.args.size() > 1 ? moduleOf(call.args.get(1)) : module);
            else if (endsWithIgnoreCase(call.context, "Формы")) //$NON-NLS-1$
                targets.add(moduleForm() != null ? module : null);
            else
                targets.add(moduleOf(call.source));
            boolean found = false;
            for (Module target : targets)
            {
                if (target == null)
                    continue;
                found = true;
                for (Method method : target.allMethods())
                {
                    if (method.getName() == null || exportOnly && !method.isExport())
                        continue;
                    if (idle && (!method.getFormalParams().isEmpty() || isServerOnly(method)))
                        continue;
                    add(method.getName());
                }
            }
            if (!found)
                full = false;
        }

        // ---- Метод модуля ----

        void moduleMethodCall(Call call, String structureType)
        {
            Method callee = call.moduleMethod;
            String parameter = call.parameter;
            String name = nullToEmpty(callee.getName());
            if (parameter.isEmpty())
            {
                full = false;
                return;
            }
            full = true;
            if (parameter.contains("Реквизит") && name.contains("Значен") && name.contains("Реквизит") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                && !call.args.isEmpty())
            {
                // БСП ЗначениеРеквизитаОбъекта: поля таблицы ссылки, в том числе через точку.
                branch("objectAttributeValue"); //$NON-NLS-1$
                List<TypeItem> types = typesOf(call.args.get(0));
                if (hasType(types, "Array")) //$NON-NLS-1$
                    types = elementTypes(types);
                pathAware = true;
                addAll(propertiesAtPath(types, parent));
            }
            else if (structureType.isEmpty() && "Поле".equalsIgnoreCase(parameter) //$NON-NLS-1$
                && "СообщитьПользователю".equalsIgnoreCase(name)) //$NON-NLS-1$
            {
                branch("messageField"); //$NON-NLS-1$
                Form form = null;
                Expression key = call.args.size() > 1 ? call.args.get(1) : null;
                if (isEmptyArgument(key))
                    form = moduleForm();
                else if (mdOfTypes(typesOf(key)) != null)
                    form = defaultForm(mdOfTypes(typesOf(key)), "defaultObjectForm"); //$NON-NLS-1$
                if (form != null)
                    addFormAttributes(form);
            }
            else if (structureType.isEmpty() && "ПутьКСвойству".equalsIgnoreCase(parameter) //$NON-NLS-1$
                && in(name, "ВыгрузитьСвойствоЛкс", "ОтобратьКоллекциюЛкс") && !call.args.isEmpty()) //$NON-NLS-1$ //$NON-NLS-2$
            {
                branch("propertyPath"); //$NON-NLS-1$
                addAll(properties(elementTypes(typesOf(call.args.get(0)))));
            }
            else if (structureType.isEmpty() && ("ПолноеИмяМД".equalsIgnoreCase(parameter) //$NON-NLS-1$
                || "ОтобратьКоллекциюЛкс".equalsIgnoreCase(name) && "Условие".equalsIgnoreCase(parameter))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                // В ИР здесь TODO.
            }
            else
            {
                full = false;
                textValuesOfParameter(callee, call.index, call.args, structureType);
            }
        }

        // ---- Литерал вне вызова ----

        void outside(StringLiteral literal)
        {
            EObject container = literal.eContainer();
            Expression left = null;
            if (container instanceof SimpleStatement statement && statement.getRight() == literal)
                left = statement.getLeft();
            else if (container instanceof BinaryExpression binary
                && (binary.getOperation() == BinaryOperation.EQ || binary.getOperation() == BinaryOperation.NE))
                left = binary.getLeft() == literal ? binary.getRight() : binary.getLeft();
            if (left == null)
                return;
            String leftText = text(left);
            String last = lastFragment(leftText);
            if ((startsWithIgnoreCase(last, "ПутьКДанным") || startsWithIgnoreCase(last, "Поле")) //$NON-NLS-1$ //$NON-NLS-2$
                && left instanceof DynamicFeatureAccess access)
            {
                List<TypeItem> owners = typesOf(access.getSource());
                if (startsWithIgnoreCase(last, "ПутьКДанным") //$NON-NLS-1$
                    && hasType(owners, "FormGroup", "FormField", "FormTable") && moduleForm() != null) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                {
                    branch("dataPath"); //$NON-NLS-1$
                    addFormAttributes(moduleForm());
                    full = true;
                }
                else if (startsWithIgnoreCase(last, "Поле") && hasType(owners, "UserMessage")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    branch("userMessageField"); //$NON-NLS-1$
                    Form form = moduleForm();
                    if (form == null && mdOfModule() != null)
                        form = defaultForm(mdOfModule(), "defaultObjectForm"); //$NON-NLS-1$
                    if (form != null)
                        addFormAttributes(form);
                    full = true;
                }
            }
            if (!full && parent.isEmpty())
            {
                branch("expression"); //$NON-NLS-1$
                textValuesOfExpression(methodOf(literal), leftText, "", true, offsetOf(literal)); //$NON-NLS-1$
            }
        }

        // ---- ВозможныеТекстовыеЗначенияПараметраИзДругогоМетода ----

        void textValuesOfParameter(Method callee, int index, List<Expression> actualArgs, String structureType)
        {
            checkCanceled();
            if (callee == null || index < 0 || index >= callee.getFormalParams().size())
                return;
            String key = System.identityHashCode(callee) + "/" + index + "/" + structureType; //$NON-NLS-1$ //$NON-NLS-2$
            if (!visitedParameters.add(key))
                return;
            branch("callee:" + callee.getName()); //$NON-NLS-1$
            Map<FormalParam, List<TypeItem>> saved = new IdentityHashMap<>(bindings);
            for (int i = 0; i < Math.min(actualArgs.size(), callee.getFormalParams().size()); i++)
            {
                Expression actual = actualArgs.get(i);
                if (i == index || isEmptyArgument(actual))
                    continue;
                List<TypeItem> types = typesOf(actual);
                if (!types.isEmpty())
                    bindings.put(callee.getFormalParams().get(i), types);
            }
            boolean wasFull = full;
            full = false;
            ParameterSearch savedSearch = parameterSearch;
            parameterSearch = new ParameterSearch(callee, callee.getFormalParams().get(index).getName());
            try
            {
                textValuesOfExpression(callee, callee.getFormalParams().get(index).getName(), structureType, false,
                    offsetOf(callee));
                if (!full && !parameterSearch.branches.isEmpty())
                    full = !hasUnknownParameterRead(parameterSearch);
            }
            finally
            {
                parameterSearch = savedSearch;
                bindings.clear();
                bindings.putAll(saved);
                full = full || wasFull;
            }
        }

        // ---- ВозможныеТекстовыеЗначенияВыраженияИзТекущегоМетода ----

        void textValuesOfExpression(Method method, String name, String structureType, boolean collectAssignments,
            int from)
        {
            checkCanceled();
            EObject scope = method != null ? method : module;
            if (scope == null || name == null)
                return;
            // Позиция0ВМетодеДо: у структуры ключи — до следующего присваивания переменной.
            int to = structureType.isEmpty() ? Integer.MAX_VALUE : reassignmentAfter(scope, name, from);
            if (structureType.isEmpty())
            {
                if (!full)
                    objectPropertyNames(scope, name);
                if (!full)
                    fillPropertyValuesNames(scope, name);
                if (!full)
                    dynamicCallNames(scope, name);
                if (!full && manual)
                    comparedLiterals(scope, name, collectAssignments);
                if (full)
                    return;
            }
            else if (in(structureType, STRUCTURE, "ФиксированнаяСтруктура")) //$NON-NLS-1$
            {
                // Ключи из копирования свойств и типа параметра — подсказки, а не закрытый список.
                // Полноту должно подтвердить место использования (structureKeys), не platformCall.
                full = false;
                String formVariable = name;
                String fromForm = ".Параметры"; //$NON-NLS-1$
                for (EObject object : contents(scope))
                {
                    if (object instanceof Invocation invocation && in(invocation.getMethodAccess() != null
                        ? invocation.getMethodAccess().getName() : null, "Вставить", "Insert") //$NON-NLS-1$ //$NON-NLS-2$
                        && invocation.getParams().size() == 2 && "Отбор".equalsIgnoreCase(literalText(invocation.getParams().get(0))) //$NON-NLS-1$
                        && matches(invocation.getParams().get(1), name)
                        && invocation.getMethodAccess() instanceof DynamicFeatureAccess access)
                    {
                        formVariable = text(access.getSource());
                        fromForm = ""; //$NON-NLS-1$
                        break;
                    }
                    if (object instanceof OperatorStyleCreator creator && creator.getParams().size() == 2
                        && "Отбор".equalsIgnoreCase(literalText(creator.getParams().get(0))) //$NON-NLS-1$
                        && matches(creator.getParams().get(1), name)
                        && creator.eContainer() instanceof SimpleStatement statement && statement.getRight() == creator)
                    {
                        formVariable = text(statement.getLeft());
                        fromForm = ""; //$NON-NLS-1$
                        break;
                    }
                }
                if (structureKeys(scope, name, null, fromForm, structureType, from, to, formVariable))
                    return;
                for (EObject object : contents(scope))
                {
                    if (!(object instanceof Invocation invocation) || !isFillPropertyValues(invocation)
                        || invocation.getParams().size() < 2)
                        continue;
                    Expression first = invocation.getParams().get(0);
                    Expression second = invocation.getParams().get(1);
                    Expression source = matches(first, name) ? second : matches(second, name) ? first : null;
                    if (source == null)
                        continue;
                    List<String> names = properties(typesOf(source));
                    if (names.size() > 1)
                    {
                        branch("structureFromFill"); //$NON-NLS-1$
                        addAll(names);
                        return;
                    }
                }
                if (method != null)
                    for (FormalParam parameter : method.getFormalParams())
                    {
                        if (!name.equalsIgnoreCase(parameter.getName()))
                            continue;
                        List<TypeItem> types = typesAtParameter(parameter);
                        List<String> names = structureKeyNames(withType(types, "Structure")); //$NON-NLS-1$
                        if (names.size() > 1)
                        {
                            branch("structureParameter"); //$NON-NLS-1$
                            addAll(names);
                            return;
                        }
                    }
            }
            else if ("Массив".equalsIgnoreCase(structureType)) //$NON-NLS-1$
            {
                if (in(name, "Поля", "ПроверяемыеРеквизиты", "ИсключитьДанные") && method != null //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    && method.getFormalParams().stream().anyMatch(p -> name.equalsIgnoreCase(p.getName())))
                {
                    if (moduleForm() != null)
                    {
                        branch("arrayFormAttributes"); //$NON-NLS-1$
                        addFormAttributes(moduleForm());
                        return;
                    }
                    if (mdOfModule() != null)
                    {
                        branch("arrayFields"); //$NON-NLS-1$
                        addAll(mdFields(mdOfModule()));
                        return;
                    }
                }
                for (EObject object : contents(scope))
                {
                    if (!(object instanceof ForEachStatement loop) || offsetOf(loop) < from
                        || !matches(loop.getCollection(), name) || loop.getVariableAccess() == null)
                        continue;
                    int before = values.size();
                    textValuesOfExpression(method, text(loop.getVariableAccess()), "", false, offsetOf(loop)); //$NON-NLS-1$
                    if (values.size() > before)
                        return;
                }
            }
            // Значение передаётся параметром в другие вызовы: значения для того параметра.
            calls.put(lower(method != null ? method.getName() : ""), 1); //$NON-NLS-1$
            if (name.isEmpty() || calls.size() > MAX_CALLS)
                return;
            String current = name;
            int start = from;
            while (true)
            {
                String substitute = null;
                int substituteAt = 0;
                for (EObject object : contents(scope))
                {
                    int at = offsetOf(object);
                    if (object instanceof SimpleStatement statement && substitute == null && at > start
                        && matches(statement.getRight(), current) && isSimpleName(statement.getLeft()))
                    {
                        substitute = text(statement.getLeft());
                        substituteAt = endOf(statement);
                        continue;
                    }
                    List<Expression> args = object instanceof Invocation || object instanceof OperatorStyleCreator
                        ? argumentsOf(object) : List.of();
                    if (args.isEmpty() || at < start || at > to)
                        continue;
                    int argument = -1;
                    for (int i = 0; i < args.size(); i++)
                        if (matches(args.get(i), current))
                        {
                            argument = i;
                            break;
                        }
                    if (argument < 0)
                        continue;
                    String callee = object instanceof Invocation invocation && invocation.getMethodAccess() != null
                        ? text(invocation.getMethodAccess()) : text(object);
                    String calleeName = object instanceof Invocation invocation && invocation.getMethodAccess() != null
                        ? lower(invocation.getMethodAccess().getName()) : ""; //$NON-NLS-1$
                    if (SKIPPED_CALLS.contains(calleeName) || calls.containsKey(lower(callee)))
                        continue;
                    calls.put(lower(callee), 1);
                    call(object, args, argument, structureType);
                    if (full)
                        full = isFullParameterRole(scope, object);
                    if (full || calls.size() > MAX_CALLS)
                        break;
                }
                if (full || substitute == null || RETURN_VARIABLE.equalsIgnoreCase(substitute)
                    || calls.size() > MAX_CALLS)
                    break;
                current = substitute;
                start = substituteAt;
            }
        }

        private static record ConditionalBranch(EObject owner, EStructuralFeature feature)
        {
            boolean contains(EObject object)
            {
                while (object != null && object.eContainer() != owner)
                    object = object.eContainer();
                return object != null && object.eContainingFeature() == feature;
            }
        }

        private static final class ParameterSearch
        {
            final Method method;
            final String name;
            final Set<ConditionalBranch> branches = new HashSet<>();

            ParameterSearch(Method method, String name)
            {
                this.method = method;
                this.name = name;
            }
        }

        /** Условные роли собираются вместе; полноту подтверждаем после обхода всех мест использования. */
        boolean isFullParameterRole(EObject scope, EObject role)
        {
            if (parameterSearch == null || parameterSearch.method != scope)
                return true;
            for (EObject child = role; child != null && child != scope; child = child.eContainer())
            {
                EObject owner = child.eContainer();
                boolean branch = owner instanceof Conditional conditional
                    && conditional.getStatements().contains(child)
                    || owner instanceof IfStatement statement && statement.getElseStatements().contains(child);
                if (branch)
                {
                    parameterSearch.branches.add(new ConditionalBranch(owner, child.eContainingFeature()));
                    return false;
                }
            }
            return true;
        }

        boolean hasUnknownParameterRead(ParameterSearch search)
        {
            for (EObject object : contents(search.method))
            {
                if (!(object instanceof StaticFeatureAccess access) || !matches(access, search.name)
                    || access.eContainer() instanceof SimpleStatement assignment && assignment.getLeft() == access
                    || access.eContainer() instanceof ForEachStatement loop && loop.getVariableAccess() == access
                    || access.eContainer() instanceof ForStatement forLoop && forLoop.getVariableAccess() == access
                    || access.eContainer() instanceof Invocation invocation && invocation.getMethodAccess() == access)
                    continue;
                if (search.branches.stream().noneMatch(branch -> branch.contains(access)))
                    return true;
            }
            return false;
        }

        /** {@code Объект[Имя]}, {@code Объект.Свойство(Имя)}: свойства объекта. */
        void objectPropertyNames(EObject scope, String name)
        {
            for (EObject object : contents(scope))
            {
                Expression owner = null;
                if (object instanceof IndexAccess access && matches(access.getIndex(), name))
                    owner = access.getSource();
                else if (object instanceof Invocation invocation && !invocation.getParams().isEmpty()
                    && matches(invocation.getParams().get(0), name)
                    && invocation.getMethodAccess() instanceof DynamicFeatureAccess access
                    && in(access.getName(), "Свойство", "Property")) //$NON-NLS-1$ //$NON-NLS-2$
                    owner = access.getSource();
                if (owner == null)
                    continue;
                List<TypeItem> ownerTypes = typesOf(owner);
                // Проверка существования допускает отсутствующий ключ, в том числе через обёртку.
                // Для автодополнения сохраняем известные свойства; индексное обращение проверяем.
                if (validation && object instanceof Invocation invocation
                    && isStructurePropertyProbe(invocation.getMethodAccess().getName(), ownerTypes))
                    continue;
                List<String> names = properties(ownerTypes);
                if (!names.isEmpty())
                {
                    branch("objectProperties"); //$NON-NLS-1$
                    addAll(names);
                    full = isFullParameterRole(scope, object);
                    if (full)
                        return;
                }
            }
        }

        /** {@code ЗаполнитьЗначенияСвойств(А, Б, Имя)}: свойства приёмника или источника. */
        void fillPropertyValuesNames(EObject scope, String name)
        {
            for (EObject object : contents(scope))
            {
                if (!(object instanceof Invocation invocation) || !isFillPropertyValues(invocation)
                    || invocation.getParams().size() < 3 || !matches(invocation.getParams().get(2), name))
                    continue;
                fillPropertyValuesNames(invocation.getParams());
                if (full)
                    full = isFullParameterRole(scope, object);
                if (full || parameterSearch == null && !values.isEmpty())
                    return;
            }
        }

        /** Полнота выборки подтверждается моделью запроса, а не числом вычисленных свойств. */
        void fillPropertyValuesNames(List<Expression> args)
        {
            for (int i = 0; i < Math.min(2, args.size()); i++)
            {
                Expression argument = args.get(i);
                List<TypeItem> types = typesOf(argument);
                if (!hasType(types, "QueryResultSelection")) //$NON-NLS-1$
                    continue;
                Map<String, List<TypeItem>> columns = BslQueryResultTypes.selectionColumnsOf(argument);
                branch("fillPropertySelection"); //$NON-NLS-1$
                full = columns != null;
                if (columns != null)
                    columns.forEach(this::add);
                else
                    addAll(properties(types));
                return;
            }
            for (int i = 0; i < Math.min(2, args.size()); i++)
            {
                List<String> names = properties(typesOf(args.get(i)));
                if (names.size() > 1)
                {
                    branch("fillPropertyList"); //$NON-NLS-1$
                    addAll(names);
                    full = true;
                    return;
                }
            }
        }

        /** {@code Выполнить("Объект." + Имя + "(…")}: методы объекта. */
        void dynamicCallNames(EObject scope, String name)
        {
            for (EObject object : contents(scope))
            {
                if (!(object instanceof BinaryExpression binary) || binary.getOperation() != BinaryOperation.PLUS
                    || !matches(binary.getRight(), name) || !(binary.getLeft() instanceof StringLiteral prefix)
                    || !(binary.eContainer() instanceof BinaryExpression outer)
                    || outer.getOperation() != BinaryOperation.PLUS || !literalText(outer.getRight()).startsWith("(")) //$NON-NLS-1$
                    continue;
                Matcher matcher = DYNAMIC_CALL_PREFIX.matcher(literalText(prefix));
                if (!matcher.matches())
                    continue;
                Module target = commonModule(matcher.group(1));
                full = isFullParameterRole(scope, object);
                if (target == null)
                    continue;
                branch("dynamicCall"); //$NON-NLS-1$
                for (Method method : target.allMethods())
                    if (method.isExport())
                        add(method.getName());
                if (full)
                    return;
            }
        }

        /** Литералы, с которыми выражение сравнивают (и которые ему присваивают). Низковероятные. */
        void comparedLiterals(EObject scope, String name, boolean collectAssignments)
        {
            for (EObject object : contents(scope))
            {
                Expression literal = null;
                if (object instanceof BinaryExpression binary && binary.getOperation() == BinaryOperation.EQ)
                    literal = matches(binary.getLeft(), name) ? binary.getRight()
                        : matches(binary.getRight(), name) ? binary.getLeft() : null;
                else if (collectAssignments && object instanceof SimpleStatement statement
                    && matches(statement.getLeft(), name))
                    literal = statement.getRight();
                if (!(literal instanceof StringLiteral string))
                    continue;
                int at = offsetOf(string);
                if (at <= position && position <= endOf(string))
                    continue;
                String value = literalText(string);
                if (value.length() > MAX_LITERAL_LENGTH)
                    continue;
                add(value);
            }
        }

        // ---- ВозможныеКлючиСтруктуры ----

        /**
         * Ключи структуры по месту её передачи: параметры открываемой формы и отборы по колонкам/измерениям.
         *
         * @param variable    переменная структуры или {@code null}, если структура — сам {@code creator}
         * @param fromForm    {@code ПутьОтФормы}: {@code ".Параметры"} — параметры формы, пусто — отбор списка
         * @param formVariable {@code ИмяПеременнойПараметровФормы}
         */
        boolean structureKeys(EObject scope, String variable, Expression creator, String fromForm,
            String structureType, int to)
        {
            return structureKeys(scope, variable, creator, fromForm, structureType, -1, to, variable);
        }

        /**
         * @param from место структуры: передачи раньше него — другой экземпляр ({@code ЛиВхождениеВЗапрещенномДиапазоне})
         * @return {@code true} — ключи найдены; иначе поиск продолжается, как при {@code Неопределено} в ИР
         */
        boolean structureKeys(EObject scope, String variable, Expression creator, String fromForm,
            String structureType, int from, int to, String formVariable)
        {
            if (scope == null)
                return false;
            for (EObject object : contents(scope))
            {
                if (!(object instanceof Invocation invocation) || invocation.getMethodAccess() == null
                    || !FORM_OPEN_METHODS.contains(lower(invocation.getMethodAccess().getName()))
                    || invocation.getParams().size() < 2 || offsetOf(invocation) > to
                    || creator == null && offsetOf(invocation) < from)
                    continue;
                Expression passed = invocation.getParams().get(1);
                if (creator != null ? passed != creator : !matches(passed, formVariable))
                    continue;
                Form form = formByName(literalText(invocation.getParams().get(0)));
                branch("formParameters"); //$NON-NLS-1$
                if (form != null)
                {
                    if (creator != null || !fromForm.isEmpty())
                        addFormParameters(form);
                    else if (mdOfForm(form) != null)
                        addAll(mdFields(mdOfForm(form)));
                }
                full = form != null;
                return true;
            }
            if (!in(structureType, STRUCTURE, "ФиксированнаяСтруктура")) //$NON-NLS-1$
                return false;
            for (EObject object : contents(scope))
            {
                Expression owner = null;
                if (object instanceof Invocation invocation && !invocation.getParams().isEmpty()
                    && invocation.getMethodAccess() instanceof DynamicFeatureAccess access
                    && STRUCTURE_FILTER_METHODS.contains(lower(access.getName()))
                    && (creator != null ? invocation.getParams().get(0) == creator
                        : matches(invocation.getParams().get(0), variable)))
                    owner = access.getSource();
                else if (object instanceof SimpleStatement statement
                    && statement.getLeft() instanceof DynamicFeatureAccess left
                    && in(left.getName(), "ОтборСтрок", "RowFilter") //$NON-NLS-1$ //$NON-NLS-2$
                    && (creator != null ? statement.getRight() == creator : matches(statement.getRight(), variable)))
                    owner = left.getSource();
                if (owner == null || offsetOf(object) > to || creator == null && offsetOf(object) < from)
                    continue;
                int before = values.size();
                branch("structureFilter"); //$NON-NLS-1$
                List<TypeItem> ownerTypes = typesOf(owner);
                for (TypeItem item : ownerTypes)
                {
                    Type type = resolve(item);
                    String en = type != null ? nullToEmpty(type.getName()) : ""; //$NON-NLS-1$
                    if (en.isEmpty() || "Array".equals(en)) //$NON-NLS-1$
                        continue;
                    List<TypeItem> one = List.of(item);
                    // У табличной части нужны свойства строки; mdOf(type) возвращает её владельца.
                    if (hasTabularSection(one))
                    {
                        List<String> names = elementProperties(one);
                        addAll(names);
                        Global.tempLog("unknown-literal-tabular", "structureFilter type=" + en //$NON-NLS-1$ //$NON-NLS-2$
                            + " validation=" + validation + " values=" + names); //$NON-NLS-1$ //$NON-NLS-2$
                        continue;
                    }
                    // ИР: метаданные типа — Структура (у структуры, выборки запроса и т. п.) → её ключи. В EDT —
                    // свойства, добавленные сверх платформенного типа, у типа с любым именем.
                    List<String> added = structureKeyNames(one);
                    if (!added.isEmpty())
                        addAll(added);
                    else if (isTableType(one))
                        addAll(columns(one));
                    else if (hasType(one, "FormDataCollection", "FormDataStructureAndCollection")) //$NON-NLS-1$ //$NON-NLS-2$
                        addAll(elementProperties(one));
                    else if (hasType(one, "FormTable")) //$NON-NLS-1$
                        addAll(properties(propertyTypes(one, "ТекущиеДанные"))); //$NON-NLS-1$
                    else if (mdOf(type) != null)
                        addAll(mdChildren(mdOf(type), en.contains("Manager") ? "dimensions" : "attributes")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                // В ИР первое место передачи решает: пусто — Неопределено, и поиск идёт дальше.
                full = values.size() > before;
                return values.size() > before;
            }
            return false;
        }

        /** Ключи структуры: её свойства без шаблонных свойств платформенного типа («ИмяКлюча»). */
        List<String> structureKeyNames(List<TypeItem> types)
        {
            List<String> names = new ArrayList<>();
            for (TypeItem item : types)
            {
                Type type = resolve(item);
                if (type == null || type.getContextDef() == null)
                    continue;
                Set<String> platform = platformPropertyNames(type);
                for (Property property : type.getContextDef().allProperties())
                    if (!platform.contains(lower(property.getName())))
                    {
                        addUnique(names, dualName(property));
                        rememberProperty(property);
                    }
            }
            return names;
        }

        // ---- Типы ----

        List<TypeItem> typesOf(Expression expression)
        {
            checkCanceled();
            if (expression == null)
                return List.of();
            if (expression instanceof StaticFeatureAccess access)
            {
                for (FeatureEntry entry : access.getFeatureEntries())
                    if (entry != null && entry.getFeature() instanceof Variable variable
                        && variable instanceof FormalParam parameter && bindings.containsKey(parameter))
                        return bindings.get(parameter);
                // Общая исходная модель метода может ещё не иметь рассчитанных FeatureEntries.
                // Неквалифицированный параметр разрешается в его собственном методе, а не в модуле вызова.
                Method owner = methodOf(access);
                if (owner != null && access.getName() != null)
                    for (FormalParam parameter : owner.getFormalParams())
                        if (access.getName().equalsIgnoreCase(parameter.getName()) && bindings.containsKey(parameter))
                            return bindings.get(parameter);
            }
            // Штатный вычислитель — как у колонок #876: только он отдаёт таблицу с колонками из запроса.
            TypesComputer computer = resource.getResourceServiceProvider() != null
                ? resource.getResourceServiceProvider().get(TypesComputer.class) : null;
            Method method = EcoreUtil2.getContainerOfType(expression, Method.class);
            Environments environments = method != null ? method.environments()
                : module != null ? module.environments() : null;
            if (computer != null && environments != null)
            {
                List<TypeItem> computed = computer.computeTypes(expression, environments);
                if (computed != null && !computed.isEmpty())
                    return new ArrayList<>(computed);
            }
            if (expression instanceof StaticFeatureAccess access)
                for (FeatureEntry entry : access.getFeatureEntries())
                    if (entry != null && entry.getFeature() instanceof Variable variable)
                    {
                        List<TypeItem> types = variableTypesAt(variable, endOf(expression));
                        if (!types.isEmpty())
                            return types;
                    }
            if (!expression.getTypes().isEmpty())
                return new ArrayList<>(expression.getTypes());
            return List.of();
        }

        List<TypeItem> variableTypesAt(Variable variable, int offset)
        {
            Method method = EcoreUtil2.getContainerOfType(variable, Method.class);
            Environments environments = method != null ? method.environments()
                : module != null ? module.environments() : null;
            if (environments == null || offset < 0)
                return List.of();
            try
            {
                return new ArrayList<>(TypeSystemUtil.getVariableTypesAtOffset(variable, offset, environments));
            }
            catch (RuntimeException e)
            {
                return List.of();
            }
        }

        List<TypeItem> typesAtParameter(FormalParam parameter)
        {
            if (bindings.containsKey(parameter))
                return bindings.get(parameter);
            return variableTypesAt(parameter, endOf(parameter));
        }

        /** Типы результата вызова (у вызова EDT кладёт их в сам узел). */
        List<TypeItem> returnTypes(EObject call)
        {
            return call instanceof Expression expression ? typesOf(expression) : List.of();
        }

        Type resolve(TypeItem item)
        {
            if (item == null)
                return null;
            EObject resolved = item.eIsProxy() ? EcoreUtil.resolve(item, resource) : item;
            return resolved instanceof Type type && !type.eIsProxy() ? type : null;
        }

        boolean hasType(List<TypeItem> types, String... englishNames)
        {
            for (TypeItem item : types)
            {
                Type type = resolve(item);
                if (type != null && type.getName() != null)
                    for (String name : englishNames)
                        if (type.getName().equals(name) || type.getName().startsWith(name + ".")) //$NON-NLS-1$
                            return true;
            }
            return false;
        }

        List<TypeItem> withType(List<TypeItem> types, String englishName)
        {
            List<TypeItem> result = new ArrayList<>();
            for (TypeItem item : types)
                if (hasType(List.of(item), englishName))
                    result.add(item);
            return result;
        }

        boolean isTableType(List<TypeItem> types)
        {
            // Строки — как их таблица: в ИР у строки в метаданных лежит сама таблица.
            return hasType(types, "ValueTable", "ValueTree", "ValueTreeRowCollection", "QueryResult", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "ValueTableRow", "ValueTreeRow"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        boolean hasTabularSection(List<TypeItem> types)
        {
            for (TypeItem item : types)
            {
                Type type = resolve(item);
                if (type != null && type.getName() != null && type.getName().contains("TabularSection")) //$NON-NLS-1$
                    return true;
            }
            return false;
        }

        boolean hasFormType(List<TypeItem> types)
        {
            return hasType(types, "ClientApplicationForm", "ManagedForm", "Form"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        /** {@code Найти(ТипКонтекста, "Объект.") > 0}: объект, а не менеджер или ссылка. */
        boolean isObjectType(List<TypeItem> types)
        {
            for (TypeItem item : types)
            {
                Type type = resolve(item);
                String name = type != null ? nullToEmpty(type.getName()) : ""; //$NON-NLS-1$
                int dot = name.indexOf('.');
                if (dot > 0 && name.substring(0, dot).endsWith("Object")) //$NON-NLS-1$
                    return true;
            }
            return false;
        }

        String typeName(TypeItem item)
        {
            if (item == null)
                return ""; //$NON-NLS-1$
            String name = item instanceof DuallyNamedElement dually && dually.getNameRu() != null
                && !dually.getNameRu().isEmpty() ? dually.getNameRu() : item.getName();
            return name != null ? SmartContentAssistProcessor.localizeAssistTypeSegment(name) : ""; //$NON-NLS-1$
        }

        String firstTypeName(List<TypeItem> types)
        {
            for (TypeItem item : types)
            {
                Type type = resolve(item);
                if (type != null)
                    return typeName(type);
            }
            return ""; //$NON-NLS-1$
        }

        List<String> typeNames(List<TypeItem> types)
        {
            List<String> names = new ArrayList<>();
            if (types != null)
                for (TypeItem item : types)
                {
                    Type type = resolve(item);
                    if (type != null)
                        names.add(typeName(type));
                }
            return names;
        }

        /** Свойства контекста типов ({@code ТаблицаСловИзСтруктурыТипа} со словами-свойствами). */
        List<String> properties(List<TypeItem> types)
        {
            List<String> names = new ArrayList<>();
            for (TypeItem item : types)
            {
                Type type = resolve(item);
                ContextDef def = type != null ? type.getContextDef() : null;
                if (def != null)
                    for (Property property : def.allProperties())
                    {
                        addUnique(names, dualName(property));
                        rememberProperty(property);
                    }
            }
            return names;
        }

        List<TypeItem> propertyTypes(List<TypeItem> owners, String name)
        {
            List<TypeItem> result = new ArrayList<>();
            for (TypeItem owner : owners)
            {
                Type type = resolve(owner);
                ContextDef def = type != null ? type.getContextDef() : null;
                if (def != null)
                    for (Property property : def.allProperties())
                        if (name.equalsIgnoreCase(property.getName()) || name.equalsIgnoreCase(property.getNameRu()))
                            result.addAll(property.getTypes());
            }
            return result;
        }

        /** Свойства типа по пути {@code мРодительскийКонтекст} («Контрагент.» — свойства контрагента). */
        List<String> propertiesAtPath(List<TypeItem> types, String path)
        {
            List<TypeItem> current = types;
            if (!path.isEmpty())
                for (String segment : path.split("\\.")) //$NON-NLS-1$
                    current = propertyTypes(current, segment.trim());
            return properties(current);
        }

        List<TypeItem> elementTypes(List<TypeItem> owners)
        {
            List<TypeItem> result = new ArrayList<>();
            for (TypeItem owner : owners)
            {
                Type type = resolve(owner);
                TypeContainer elements = type != null ? type.getCollectionElementTypes() : null;
                if (elements != null)
                    result.addAll(elements.allTypes());
            }
            return result;
        }

        List<String> elementProperties(List<TypeItem> owners)
        {
            return properties(elementTypes(owners));
        }

        /** Колонки таблицы/дерева значений: свойства строки, кроме общих для всех строк. */
        List<String> columns(List<TypeItem> tables)
        {
            List<String> names = new ArrayList<>();
            for (TypeItem item : tables)
            {
                Type table = resolve(item);
                if (table == null)
                    continue;
                List<TypeItem> rowTypes;
                if (hasType(List.of(table), "ValueTableRow", "ValueTreeRow")) //$NON-NLS-1$ //$NON-NLS-2$
                    rowTypes = List.of(table);
                else
                {
                    TypeContainer rows = "ValueTree".equals(table.getName()) //$NON-NLS-1$
                        ? ValueTreeDynamicContextDefProvider.getValueTreeCollectionElementType(table)
                        : table.getCollectionElementTypes();
                    rowTypes = rows != null ? rows.allTypes() : List.of();
                }
                for (TypeItem rowItem : rowTypes)
                {
                    Type row = resolve(rowItem);
                    // Колонки — свойства сверх свойств самой строки платформы («Родитель», «Строки» и т. п.).
                    Set<String> platform = platformPropertyNames(row);
                    if (row != null && row.getContextDef() != null)
                        for (Property property : row.getContextDef().allProperties())
                            if (!property.isDependsOnMetadata() && !platform.contains(lower(property.getName())))
                            {
                                addUnique(names, dualName(property));
                                rememberProperty(property);
                            }
                }
            }
            return names;
        }

        /** Имена свойств платформенного типа с тем же именем, что у {@code type} (без колонок и ключей). */
        Set<String> platformPropertyNames(Type type)
        {
            Set<String> names = new HashSet<>();
            if (type == null || type.getName() == null)
                return names;
            try
            {
                var provider = com._1c.g5.v8.dt.platform.IEObjectProvider.Registry.INSTANCE.get(
                    McorePackage.Literals.TYPE_ITEM, com._1c.g5.v8.dt.platform.version.Version.LATEST);
                EObject proxy = provider != null ? provider.getProxy(type.getName()) : null;
                EObject resolved = proxy != null && proxy.eIsProxy() ? EcoreUtil.resolve(proxy, resource) : proxy;
                // Тот же объект — это сам платформенный тип: добавленных свойств (колонок, ключей) у него
                // нет, все его свойства платформенные (у «Структура» — шаблонное «ИмяКлюча»).
                if (resolved instanceof Type platform && !platform.eIsProxy() && platform.getContextDef() != null)
                    for (Property property : platform.getContextDef().allProperties())
                        names.add(lower(property.getName()));
            }
            catch (RuntimeException e)
            {
            }
            return names;
        }

        // ---- Форма ----

        Form moduleForm()
        {
            return module != null && module.getOwner() instanceof Form form ? form : null;
        }

        /**
         * Реквизиты формы по пути {@code мРодительскийКонтекст} ({@code СписокРеквизитовУправляемойФормы}):
         * в корне — реквизиты формы, ниже — колонки реквизита-таблицы или свойства типа реквизита.
         */
        void addFormAttributes(Form form)
        {
            pathAware = true;
            if (form == null)
                return;
            String path = parent.replaceAll("\\[[^\\]]*\\]", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (path.isEmpty())
            {
                for (FormAttribute attribute : form.getAttributes())
                    add(nameOf(attribute), attribute.getValueType());
                return;
            }
            String[] segments = path.split("\\."); //$NON-NLS-1$
            FormAttribute attribute = null;
            for (FormAttribute candidate : form.getAttributes())
                if (segments[0].trim().equalsIgnoreCase(nameOf(candidate)))
                    attribute = candidate;
            if (attribute == null)
                return;
            List<TypeItem> types = attribute.getValueType() != null
                ? new ArrayList<>(attribute.getValueType().getTypes()) : new ArrayList<>();
            List<? extends AbstractFormAttribute> columns = attribute.getColumns();
            for (int i = 1; i < segments.length; i++)
            {
                String segment = segments[i].trim();
                AbstractFormAttribute column = null;
                for (AbstractFormAttribute candidate : columns)
                    if (segment.equalsIgnoreCase(nameOf(candidate)))
                        column = candidate;
                columns = List.of();
                if (column != null)
                    types = column.getValueType() != null ? new ArrayList<>(column.getValueType().getTypes())
                        : new ArrayList<>();
                else
                    types = propertyTypes(types, segment);
            }
            if (!columns.isEmpty())
                for (AbstractFormAttribute column : columns)
                    add(nameOf(column), column.getValueType());
            addAll(properties(types));
            addAll(elementProperties(types));
        }

        void addFormItems(Form form)
        {
            if (form != null)
                collectItems(form, values);
        }

        private void collectItems(FormItemContainer container, Map<String, String> into)
        {
            for (FormItem item : container.getItems())
            {
                add(item.getName());
                if (item instanceof FormItemContainer nested)
                    collectItems(nested, into);
            }
        }

        /** Форма по имени из {@code ОткрытьФорму}: «Справочник.Х.ФормаОбъекта», «…Форма.Имя», «ОбщаяФорма.Имя». */
        Form formByName(String fullName)
        {
            String[] parts = fullName.split("\\."); //$NON-NLS-1$
            if (parts.length < 2 || configuration() == null)
                return null;
            if (in(parts[0], "ОбщаяФорма", "CommonForm")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                for (var common : configuration().getCommonForms())
                    if (parts[1].equalsIgnoreCase(common.getName()))
                        return formOf(common);
                return null;
            }
            MdObject md = mdByKind(parts[0], parts[1]);
            if (md == null || parts.length < 3)
                return null;
            if (parts.length > 3 && in(parts[2], "Форма", "Form")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                for (Object child : list(md, "forms")) //$NON-NLS-1$
                    if (child instanceof BasicForm basic && parts[3].equalsIgnoreCase(basic.getName()))
                        return formOf(basic);
                return null;
            }
            String feature = STANDARD_FORMS.get(lower(parts[2]));
            return feature != null ? defaultForm(md, feature) : null;
        }

        /**
         * {@code СхемаКоллекцииПараметровФормы} из {@code ирПлатформа}: системные параметры формы, затем ожидаемые
         * в её коде. Системные — свойства {@code Параметры} контекста формы: туда EDT кладёт параметры формы вместе с
         * параметрами расширения ({@code Отбор}, {@code ТекущаяСтрока} у формы списка и т. п.).
         */
        void addFormParameters(Form form)
        {
            boolean fromContext = false;
            ContextDef context = form.getFormContext();
            if (context != null)
                for (Property property : context.getProperties())
                {
                    if (!"Parameters".equals(property.getName())) //$NON-NLS-1$
                        continue;
                    for (TypeItem item : property.getTypes())
                    {
                        Type type = resolve(item);
                        if (type != null && type.getContextDef() != null)
                            for (Property parameter : type.getContextDef().getProperties())
                            {
                                fromContext = true;
                                // Только типы, без самого свойства: это не свойство платформы, и подсказка
                                // по нему искала бы несуществующую страницу документации.
                                add(dualName(parameter), parameter.getTypes());
                            }
                    }
                }
            // Контекст формы ещё не выведен — собственные параметры из модели формы.
            if (!fromContext)
                for (FormParameter parameter : form.getParameters())
                    add(nameOf(parameter), parameter.getValueType());
            // Ожидаемые параметры читаются в ПриСозданииНаСервере и вызываемых им методах формы.
            Module formModule = form.getModule();
            if (formModule == null || formModule.eIsProxy())
                return;
            Set<Method> localMethods = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Method> pending = new ArrayList<>();
            for (Method method : formModule.allMethods())
            {
                localMethods.add(method);
                if (in(method.getName(), "ПриСозданииНаСервере", "OnCreateAtServer")) //$NON-NLS-1$ //$NON-NLS-2$
                    pending.add(method);
            }
            Set<Method> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int i = 0; i < pending.size(); i++)
            {
                checkCanceled();
                Method method = pending.get(i);
                if (!visited.add(method))
                    continue;
                for (EObject object : contents(method))
                {
                    if (object instanceof Invocation invocation && invocation.getMethodAccess() != null)
                    {
                        // У загруженного модуля другой формы FeatureEntries могут быть ещё не рассчитаны.
                        // Неквалифицированный вызов локального метода разрешаем по имени в том же модуле.
                        if (invocation.getMethodAccess() instanceof StaticFeatureAccess access
                            && access.getFeatureEntries().isEmpty() && access.getName() != null)
                            for (Method called : localMethods)
                                if (access.getName().equalsIgnoreCase(called.getName()) && !visited.contains(called))
                                    pending.add(called);
                        for (FeatureEntry entry : featureEntries(invocation.getMethodAccess()))
                        {
                            EObject feature = entry != null ? entry.getFeature() : null;
                            if (feature != null && feature.eIsProxy())
                                feature = EcoreUtil.resolve(feature, formModule.eResource());
                            if (feature instanceof Method called && localMethods.contains(called)
                                && !visited.contains(called))
                                pending.add(called);
                        }
                    }
                    if (object instanceof Invocation invocation
                        && invocation.getMethodAccess() instanceof DynamicFeatureAccess access
                        && in(access.getName(), "Свойство", "Property") && isFormParameters(access.getSource()) //$NON-NLS-1$ //$NON-NLS-2$
                        && !invocation.getParams().isEmpty())
                        add(literalText(invocation.getParams().get(0)));
                    else if (object instanceof DynamicFeatureAccess access && isFormParameters(access.getSource())
                        && !(access.eContainer() instanceof Invocation invocation && invocation.getMethodAccess() == access))
                        add(access.getName());
                }
            }
        }

        boolean isFormParameters(Expression expression)
        {
            return expression instanceof StaticFeatureAccess && in(text(expression), "Параметры", "Parameters"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        Form defaultForm(MdObject md, String feature)
        {
            return eGet(md, feature) instanceof BasicForm basic ? formOf(basic) : null;
        }

        Form formOf(BasicForm basic)
        {
            EObject form = basic.getForm();
            if (form != null && form.eIsProxy())
                form = EcoreUtil.resolve(form, resource);
            return form instanceof Form loaded && !loaded.eIsProxy() ? loaded : null;
        }

        MdObject mdOfForm(Form form)
        {
            return form.eResource() != null ? mdOfUri(form.eResource().getURI()) : null;
        }

        // ---- Метаданные ----

        /**
         * EDT DataLockAddInvocationTypesComputer хранит пространство блокировки в PropertyType.propertyName.
         * Поля и ограничения — как у StringLiteralProposalProviderDataLockItemSetValue EDT.
         */
        void dataLockFields(List<TypeItem> types)
        {
            for (TypeItem item : types)
            {
                Type type = resolve(item);
                if (!(type instanceof PropertyType lock) || !hasType(List.of(type), "DataLockItem") //$NON-NLS-1$
                    || lock.getPropertyName().isEmpty())
                    continue;
                String[] path = lock.getPropertyName().get(0).split("\\."); //$NON-NLS-1$
                if (path.length < 2)
                    continue;
                MdObject md = mdByKind(path[0], path[1]);
                boolean recordSet = path.length == 3 && in(path[2], "НаборЗаписей", "RecordSet"); //$NON-NLS-1$ //$NON-NLS-2$
                if (md instanceof BasicDbObject object)
                {
                    Type ref = typeByName(md.eClass().getName() + "Ref." + md.getName()); //$NON-NLS-1$
                    if (ref != null)
                        add(russian ? "Ссылка" : "Ref", List.of(ref)); //$NON-NLS-1$ //$NON-NLS-2$
                    for (Field field : object.getDataLockFields())
                        add(dualName(field), field.getType());
                }
                else if (md instanceof FieldSource fields)
                {
                    for (Field field : fields.getFields())
                    {
                        String name = field.getName();
                        EObject origin = field instanceof DerivedField derived ? derived.getSource() : null;
                        boolean allowed = recordSet ? "Recorder".equals(name) //$NON-NLS-1$
                            : "Period".equals(name) || origin instanceof RegisterDimension //$NON-NLS-1$
                                || "AccountingRegister".equals(md.eClass().getName()) //$NON-NLS-1$
                                    && ("Account".equals(name) || name != null && name.startsWith("ExtDimension")) //$NON-NLS-1$ //$NON-NLS-2$
                                || "CalculationRegister".equals(md.eClass().getName()) //$NON-NLS-1$
                                    && in(name, "ActionPeriod", "RegistrationPeriod") //$NON-NLS-1$ //$NON-NLS-2$
                                || origin instanceof CommonAttribute common
                                    && common.getDataSeparation() == CommonAttributeDataSeparation.SEPARATE;
                        if (allowed)
                            add(dualName(field), field.getType());
                    }
                }
            }
        }

        Configuration configuration()
        {
            return pass.configuration(resource);
        }

        /** Объект метаданных вида {@code kind} (любое написание) с именем {@code name}. */
        MdObject mdByKind(String kind, String name)
        {
            String folder = MdTypeMapping.anyToFolder(kind);
            if (folder == null || configuration() == null)
                return null;
            for (Object item : list(configuration(), Character.toLowerCase(folder.charAt(0)) + folder.substring(1)))
                if (item instanceof MdObject md && name.equalsIgnoreCase(md.getName()))
                    return md;
            return null;
        }

        /** Объект метаданных типа ({@code CatalogRef.Валюты} → справочник «Валюты»). */
        MdObject mdOf(Type type)
        {
            String name = type != null ? type.getName() : null;
            int dot = name != null ? name.indexOf('.') : -1;
            if (dot <= 0)
                return null;
            String prefix = name.substring(0, dot);
            String object = name.substring(dot + 1);
            int next = object.indexOf('.');
            if (next > 0)
                object = object.substring(0, next);
            String kind = null;
            for (String candidate : MdTypeMapping.EN_SING_TO_FOLDER.keySet())
                if (prefix.startsWith(candidate) && (kind == null || candidate.length() > kind.length()))
                    kind = candidate;
            return kind != null ? mdByKind(kind, object) : null;
        }

        MdObject mdOfTypes(List<TypeItem> types)
        {
            for (TypeItem item : types)
            {
                MdObject md = mdOf(resolve(item));
                if (md != null)
                    return md;
            }
            return null;
        }

        /** Объект метаданных модуля: владелец или папка ресурса ({@code src/Catalogs/Валюты/…}). */
        MdObject mdOfModule()
        {
            if (module == null)
                return null;
            if (module.getOwner() instanceof MdObject md && !(md instanceof Configuration))
                return md;
            return mdOfUri(resource.getURI());
        }

        MdObject mdOfUri(URI uri)
        {
            if (uri == null)
                return null;
            List<String> segments = uri.segmentsList();
            int src = segments.indexOf("src"); //$NON-NLS-1$
            if (src < 0 || src + 2 >= segments.size())
                return null;
            return mdByKind(URI.decode(segments.get(src + 1)), URI.decode(segments.get(src + 2)));
        }

        List<String> mdChildren(MdObject md, String feature)
        {
            List<String> names = new ArrayList<>();
            for (Object child : list(md, feature))
                if (child instanceof MdObject named)
                {
                    addUnique(names, named.getName());
                    // Реквизиты, измерения, ресурсы: тип из описания типов объекта метаданных.
                    if (eGet(named, "type") instanceof com._1c.g5.v8.dt.mcore.TypeDescription description) //$NON-NLS-1$
                        rememberTypes(named.getName(), description.getTypes());
                }
            return names;
        }

        /**
         * Поля таблицы объекта ({@code ПоляТаблицыБДЛкс}): свойства ссылки или записи — с локализованными
         * стандартными реквизитами; без такого типа — реквизиты, измерения, ресурсы и табличные части.
         */
        List<String> mdFields(MdObject md)
        {
            String kind = md.eClass().getName();
            for (String suffix : List.of("Ref", "RecordKey", "Object")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                Type type = typeByName(kind + suffix + "." + md.getName()); //$NON-NLS-1$
                List<String> names = type != null ? properties(List.of(type)) : List.of();
                if (!names.isEmpty())
                    return names;
            }
            List<String> names = new ArrayList<>();
            for (String feature : List.of("dimensions", "resources", "attributes", "tabularSections")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                for (String name : mdChildren(md, feature))
                    addUnique(names, name);
            return names;
        }

        Type typeByName(String name)
        {
            IScopeProvider scopes = resource.getResourceServiceProvider() != null
                ? resource.getResourceServiceProvider().get(IScopeProvider.class) : null;
            if (scopes == null || module == null)
                return null;
            IScope scope = scopes.getScope(module, McorePackage.Literals.TYPE_DESCRIPTION__TYPES);
            IEObjectDescription description = scope.getSingleElement(QualifiedName.create(name.split("\\."))); //$NON-NLS-1$
            if (description == null)
                description = scope.getSingleElement(QualifiedName.create(name));
            EObject object = description != null ? EcoreUtil.resolve(description.getEObjectOrProxy(), resource) : null;
            return object instanceof Type type && !type.eIsProxy() ? type : null;
        }

        /** {@code Метаданные.Справочники.Найти("…")}: имена объектов этой коллекции. */
        List<String> metadataCollection(String sourceText)
        {
            String collection = lastFragment(sourceText);
            String singular = MdTypeMapping.ruPluralToRu(collection);
            String folder = MdTypeMapping.anyToFolder(singular != null ? singular : collection);
            List<String> names = new ArrayList<>();
            if (folder != null && configuration() != null)
                for (Object item : list(configuration(), Character.toLowerCase(folder.charAt(0)) + folder.substring(1)))
                    if (item instanceof MdObject md)
                        addUnique(names, md.getName());
            return names;
        }

        Module commonModule(String name)
        {
            if (configuration() == null)
                return null;
            for (CommonModule common : configuration().getCommonModules())
                if (name.equalsIgnoreCase(common.getName()))
                    return common.getModule();
            return null;
        }

        /** Модуль, методы которого назначают обработчиком: {@code ЭтотОбъект}, общий модуль, форма. */
        Module moduleOf(Expression expression)
        {
            if (expression == null)
                return module;
            String text = text(expression);
            if (in(text, "ЭтотОбъект", "ЭтаФорма", "ThisObject", "ThisForm")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                return module;
            if (isSimpleName(expression) && commonModule(text) != null)
                return commonModule(text);
            List<TypeItem> types = typesOf(expression);
            for (TypeItem item : types)
            {
                Type type = resolve(item);
                String en = type != null ? nullToEmpty(type.getName()) : ""; //$NON-NLS-1$
                if (en.startsWith("CommonModule.")) //$NON-NLS-1$
                    return commonModule(en.substring("CommonModule.".length())); //$NON-NLS-1$
            }
            return hasFormType(types) && moduleForm() != null ? module : null;
        }

        /** Таблицы или колонки менеджера временных таблиц для литерала вызова; {@code null} — менеджер не определён. */
        List<String> tempTableLiteralNames(Call call)
        {
            if (validation || !(call.args.get(call.index) instanceof StringLiteral literal))
                return null;
            List<String> names = BslTempTableSessions.literalNames(literal, org.eclipse.xtext.util.CancelIndicator.NullImpl);
            return names == null || names.isEmpty() ? null : names;
        }

        /** Временные таблицы ({@code ПОМЕСТИТЬ}) запросов модуля. */
        List<String> tempTableNames()
        {
            List<String> names = new ArrayList<>();
            if (module == null)
                return names;
            for (EObject object : contents(module))
            {
                if (!(object instanceof StringLiteral literal))
                    continue;
                Matcher matcher = TEMP_TABLE.matcher(text(literal));
                while (matcher.find())
                    addUnique(names, matcher.group(1));
            }
            return names;
        }

        /** Области макета табличного документа, полученного {@code ПолучитьМакет("…")}. */
        void addTemplateAreas(Expression receiver)
        {
            Invocation origin = templateOrigin(receiver);
            if (origin == null || origin.getParams().isEmpty())
                return;
            String name = literalText(origin.getParams().get(0));
            String method = lower(origin.getMethodAccess().getName());
            BasicTemplate template = null;
            if (in(method, "получитьобщиймакет", "getcommontemplate")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                String shortName = name.contains(".") ? lastFragment(name) : name; //$NON-NLS-1$
                if (configuration() != null)
                    for (var common : configuration().getCommonTemplates())
                        if (shortName.equalsIgnoreCase(common.getName()))
                            template = common;
            }
            else
            {
                MdObject owner = origin.getMethodAccess() instanceof DynamicFeatureAccess access
                    ? mdOfTypes(typesOf(access.getSource())) : mdOfModule();
                if (owner != null)
                    for (Object child : list(owner, "templates")) //$NON-NLS-1$
                        if (child instanceof BasicTemplate candidate && name.equalsIgnoreCase(candidate.getName()))
                            template = candidate;
            }
            EObject content = template != null ? template.getTemplate() : null;
            if (content != null && content.eIsProxy())
                content = EcoreUtil.resolve(content, resource);
            if (content instanceof SpreadsheetDocument document)
                addAll(document.getNamedItems().keySet());
        }

        Invocation templateOrigin(Expression receiver)
        {
            if (receiver instanceof Invocation invocation && isTemplateCall(invocation))
                return invocation;
            if (!isSimpleName(receiver))
                return null;
            String name = text(receiver);
            Invocation found = null;
            EObject scope = methodOf(receiver) != null ? methodOf(receiver) : module;
            for (EObject object : contents(scope))
                if (object instanceof SimpleStatement statement && offsetOf(statement) < offsetOf(receiver)
                    && matches(statement.getLeft(), name) && statement.getRight() instanceof Invocation invocation
                    && isTemplateCall(invocation))
                    found = invocation;
            return found;
        }

        boolean isTemplateCall(Invocation invocation)
        {
            return invocation.getMethodAccess() != null && in(invocation.getMethodAccess().getName(),
                "ПолучитьМакет", "GetTemplate", "ПолучитьОбщийМакет", "GetCommonTemplate"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }

        // ---- AST ----

        void checkCanceled()
        {
            if (canceled != null && canceled.getAsBoolean())
                throw new org.eclipse.core.runtime.OperationCanceledException();
        }

        /** Узлы области из индекса прохода; обход, как и построение индекса, прерывается отменой. */
        Iterable<EObject> contents(EObject scope)
        {
            checkCanceled();
            List<EObject> result = scope != null ? pass.contents(scope, this::checkCanceled) : List.of();
            return () -> new java.util.Iterator<EObject>()
            {
                private final java.util.Iterator<EObject> delegate = result.iterator();

                @Override
                public boolean hasNext()
                {
                    checkCanceled();
                    return delegate.hasNext();
                }

                @Override
                public EObject next()
                {
                    checkCanceled();
                    return delegate.next();
                }
            };
        }

        Method methodOf(EObject object)
        {
            return EcoreUtil2.getContainerOfType(object, Method.class);
        }

        EObject scopeOf(EObject object)
        {
            Method method = methodOf(object);
            return method != null ? method : module;
        }

        /** Позиция следующего присваивания выражению после {@code from} ({@code Позиция0ВМетодеДо}). */
        int reassignmentAfter(EObject scope, String name, int from)
        {
            for (EObject object : contents(scope))
                if (object instanceof SimpleStatement statement && offsetOf(statement) > from
                    && matches(statement.getLeft(), name))
                    return offsetOf(statement);
            return Integer.MAX_VALUE;
        }

        boolean isServerOnly(Method method)
        {
            for (Pragma pragma : method.getPragmas())
            {
                String symbol = lower(pragma.getSymbol());
                if (symbol.contains("насервере") || symbol.contains("atserver")) //$NON-NLS-1$ //$NON-NLS-2$
                    return true;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Общие помощники
    // ------------------------------------------------------------------------------------------

    private static final String STRUCTURE = "Структура"; //$NON-NLS-1$
    private static final String FILL_PROPERTY_VALUES = "ЗаполнитьЗначенияСвойств"; //$NON-NLS-1$

    private static boolean isFillPropertyValues(Invocation invocation)
    {
        return invocation.getMethodAccess() != null
            && in(invocation.getMethodAccess().getName(), FILL_PROPERTY_VALUES, "FillPropertyValues"); //$NON-NLS-1$
    }

    private static List<Expression> argumentsOf(EObject call)
    {
        if (call instanceof Invocation invocation)
            return invocation.getParams();
        if (call instanceof OperatorStyleCreator creator)
            return creator.getParams();
        return List.of();
    }

    /** Вызов, параметром которого стоит {@code expression}. */
    private static EObject enclosingCall(EObject expression)
    {
        EObject container = expression.eContainer();
        return (container instanceof Invocation || container instanceof OperatorStyleCreator)
            && argumentsOf(container).contains(expression) ? container : null;
    }

    private static List<FeatureEntry> featureEntries(FeatureAccess access)
    {
        if (access instanceof StaticFeatureAccess staticAccess)
            return staticAccess.getFeatureEntries();
        if (access instanceof DynamicFeatureAccess dynamic)
            return dynamic.getFeatureEntries();
        return List.of();
    }

    private static boolean isSimpleName(Expression expression)
    {
        return expression instanceof StaticFeatureAccess && IDENTIFIER.matcher(text(expression)).matches();
    }

    private static boolean isEmptyArgument(Expression expression)
    {
        return expression == null || text(expression).isEmpty();
    }

    /** Выражение записано так же, как {@code name} (без учёта регистра и пробелов). */
    private static boolean matches(Expression expression, String name)
    {
        return expression != null && name != null && !name.isEmpty()
            && withoutSpaces(text(expression)).equalsIgnoreCase(withoutSpaces(name));
    }

    private static String withoutSpaces(String text)
    {
        return text.replaceAll("\\s+", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String text(EObject object)
    {
        ICompositeNode node = object != null ? NodeModelUtils.findActualNodeFor(object) : null;
        String text = node != null ? NodeModelUtils.getTokenText(node) : null;
        return text != null ? text.trim() : ""; //$NON-NLS-1$
    }

    /** {@code ТекстИзВстроенногоЯзыкаЛкс}: значение строкового литерала; не литерал — пусто. */
    private static String literalText(Expression expression)
    {
        if (!(expression instanceof StringLiteral literal))
            return ""; //$NON-NLS-1$
        String text = text(expression);
        if (text.length() < 2 || text.charAt(0) != '"' || text.charAt(text.length() - 1) != '"')
            return ""; //$NON-NLS-1$
        return new DefaultStringLiteralTextProvider(literal).getText().trim();
    }

    private static int offsetOf(EObject object)
    {
        ICompositeNode node = object != null ? NodeModelUtils.findActualNodeFor(object) : null;
        return node != null ? node.getOffset() : -1;
    }

    private static int endOf(EObject object)
    {
        ICompositeNode node = object != null ? NodeModelUtils.findActualNodeFor(object) : null;
        return node != null ? node.getEndOffset() : -1;
    }

    private static String dualName(EObject element)
    {
        if (element instanceof DuallyNamedElement dually)
        {
            String ru = dually.getNameRu();
            return ru != null && !ru.isEmpty() ? ru : nullToEmpty(dually.getName());
        }
        return nameOf(element);
    }

    private static String nameOf(EObject element)
    {
        if (element instanceof NamedElement named)
            return nullToEmpty(named.getName());
        if (element instanceof MdObject md)
            return nullToEmpty(md.getName());
        return eGet(element, "name") instanceof String name ? name : ""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static List<String> names(List<? extends MdObject> objects)
    {
        List<String> names = new ArrayList<>();
        for (MdObject object : objects)
            addUnique(names, object.getName());
        return names;
    }

    private static Object eGet(EObject object, String feature)
    {
        EStructuralFeature structural = object != null ? object.eClass().getEStructuralFeature(feature) : null;
        return structural != null ? object.eGet(structural) : null;
    }

    private static List<?> list(EObject object, String feature)
    {
        return eGet(object, feature) instanceof List<?> list ? list : List.of();
    }

    private static IV8Project v8Project(XtextResource resource)
    {
        var projects = (IV8ProjectManager)Global.getServiceByClass(IV8ProjectManager.class);
        return projects != null && resource != null ? projects.getProject(resource.getURI()) : null;
    }

    private static boolean isRussian(XtextResource resource)
    {
        IV8Project project = v8Project(resource);
        return project == null || project.getScriptVariant() != ScriptVariant.ENGLISH;
    }

    private static void addUnique(List<String> names, String name)
    {
        if (name != null && !name.isBlank() && !containsIgnoreCase(names, name))
            names.add(name);
    }

    private static boolean containsIgnoreCase(List<String> values, String value)
    {
        for (String candidate : values)
            if (candidate.equalsIgnoreCase(value))
                return true;
        return false;
    }

    private static boolean containsAny(String text, String... parts)
    {
        for (String part : parts)
            if (text.contains(part))
                return true;
        return false;
    }

    private static boolean in(String value, String... options)
    {
        if (value == null)
            return false;
        for (String option : options)
            if (option.equalsIgnoreCase(value))
                return true;
        return false;
    }

    private static boolean startsWithIgnoreCase(String text, String prefix)
    {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean endsWithIgnoreCase(String text, String suffix)
    {
        return text.length() >= suffix.length()
            && text.regionMatches(true, text.length() - suffix.length(), suffix, 0, suffix.length());
    }

    private static String lastFragment(String expression)
    {
        int dot = expression.lastIndexOf('.');
        return dot >= 0 ? expression.substring(dot + 1) : expression;
    }

    private static String nullToEmpty(String text)
    {
        return text != null ? text : ""; //$NON-NLS-1$
    }

    private static String lower(String text)
    {
        return text != null ? text.toLowerCase(Locale.ROOT) : ""; //$NON-NLS-1$
    }
}
