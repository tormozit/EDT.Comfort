package tormozit;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.contentassist.ContentAssistEvent;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.contentassist.ICompletionListenerExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.ColumnViewerEditor;
import org.eclipse.jface.viewers.ColumnViewerEditorActivationEvent;
import org.eclipse.jface.viewers.ColumnViewerEditorActivationStrategy;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TableViewerEditor;
import org.eclipse.jface.viewers.TextCellEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.model.BslContextDefMethod;
import com._1c.g5.v8.dt.bsl.model.DeclareStatement;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.ExplicitVariable;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.ImplicitVariable;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.ModuleType;
import com._1c.g5.v8.dt.bsl.model.OperatorStyleCreator;
import com._1c.g5.v8.dt.bsl.model.ReturnStatement;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Variable;
import com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.bsl.typesystem.util.TypeSystemUtil;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.Ctor;
import com._1c.g5.v8.dt.mcore.DuallyNamedElement;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.Parameter;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeContainer;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environment;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Премии слов списка автодополнения без ИР — перенос из ИР ({@code ирКлсПолеТекстаПрограммы}):
 * ожидаемый тип ({@code РассчитатьОжидаемыйТипВыражения}, {@code ТаблицаОжидаемыхТиповПараметраМетода},
 * {@code ПрименитьОжидаемыйТипВТаблицеСлов}, {@code ОбозначитьСловоСОжидаемымТипомВТаблицеСлов}), частота
 * членов в тексте метода и статистика выбора слов ({@code ПоднятьРейтингиСловИзТаблицыВесов}). Премия —
 * второй ключ сортировки после рейтинга фильтра ({@link SmartContentAssistProcessor#compareProposals}), в
 * единицах {@code МножительРейтинга} ИР. Слово с премией, при которой ИР разрешает автооткрытие
 * ({@code мСтрокаЛучшегоСлова}), разрешает автооткрытие при любом триггере ({@link #hasAutoOpenWord}).
 *
 * <p>Снимок премий один на контекст: документ, начало выражения у каретки и текст перед ним. Модель
 * читается один раз ({@link ContentAssistSessionReloader#readOnlyForContentAssist}) и только в
 * фоновом потоке; в UI-потоке снимок лишь читается, а если его нет — считается фоновым заданием,
 * после которого открытое окно списка обновляется.
 *
 * <p>Слова, которых нет у EDT (генераторы кода, конструкции, копии строк выше, {@code Новый Тип},
 * {@code Тип("…")}, {@code Метаданные.СвойстваОбъектов.…}), добавляет {@link AddedWords} — строками
 * {@link BslAddedWordProposal} ({@link #withAddedWords}).
 *
 * <p>Не перенесено: строковые и ссылочные литералы (в том числе имена событий {@code УстановитьДействие}).
 * Тип параметра запроса ({@code УстановитьПараметр}) не считается — только имя параметра.
 */
final class BslExpectedTypePremiums
{
    private static final String LOG = "expected-type"; //$NON-NLS-1$
    private static final String FUZZY_LOG = "expected-type-fuzzy"; //$NON-NLS-1$
    /** {@code КонстантаМаксСловПроверятьДляОжидаемогоТипа}. */
    private static final int MAX_WORDS_FOR_TYPE = 200;
    private static final int MAX_TYPES_TO_CHECK = 3;
    private static final int PRECEDING_TEXT_LENGTH = 200;
    /** {@code МаксДлинаМалых} нечёткого поиска ИР. */
    private static final int FUZZY_SMALL_LENGTH = 8;
    /** Сверок нечёткого поиска с ИР на один контекст. */
    private static final int MAX_FUZZY_RECONCILES = 5;
    /** {@code ИмяПеременнойВозвращаемогоЗначения}: «Возврат» ИР читает как присваивание этой переменной. */
    private static final String RETURN_VARIABLE = "Воз073"; //$NON-NLS-1$
    private static final String GLOBAL_CONTEXT = "Глобальный"; //$NON-NLS-1$
    /** {@code ЯзыкПрограммы} ИР в статистике выбора: модуль и язык запросов. */
    private static final int MODULE_LANGUAGE = 0;
    private static final int QUERY_LANGUAGE = 1;
    private static final String UNDEFINED = "Неопределено"; //$NON-NLS-1$
    private static final String BOOLEAN = "Булево"; //$NON-NLS-1$
    private static final String NUMBER = "Число"; //$NON-NLS-1$
    private static final String STRING = "Строка"; //$NON-NLS-1$
    private static final String STRUCTURE = "Структура"; //$NON-NLS-1$
    private static final String FILL_PROPERTY_VALUES = "ЗаполнитьЗначенияСвойств"; //$NON-NLS-1$
    /** {@code шПростоеВыражениеПрограммы} — без скобок вызова и индекса. */
    private static final String SIMPLE_EXPRESSION = "[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)*"; //$NON-NLS-1$
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*"); //$NON-NLS-1$
    private static final Pattern LOOP_ITERATOR = Pattern.compile(
        "(?iu)Для\\s+Каждого\\s+([\\p{L}_][\\p{L}\\p{N}_]*)\\s+Из\\s+[^\\r\\n]+\\s+Цикл$"); //$NON-NLS-1$
    private static final Pattern TYPE_OF = Pattern.compile(
        "(?iu)(?:^|[^\\p{L}\\p{N}_.])ТипЗнч\\s*\\(\\s*(" + SIMPLE_EXPRESSION + ")\\s*\\)\\s*(?:=|<>)$"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Set<String> UNLIKE_FORBIDDEN_TYPES = Set.of("неопределено", "булево", "строка", "число", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "дата", "null", "массив", "списокзначений", "структура", "таблицазначений", "строкатаблицызначений", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "данныеформыструктура", "данныеформыколлекция", "данныеформыэлементколлекции"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    /** Типы, для которых ИР не ищет менеджер перечисления ({@code ОбозначитьСловоСОжидаемымТипомВТаблицеСлов}). */
    private static final Set<String> ENUM_RETRY_EXCLUDED = Set.of("строка", "число", "булево", "массив", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "структура"); //$NON-NLS-1$
    private static final Set<String> PRIMITIVE_TYPES = Set.of("неопределено", "булево", "строка", "число", "дата", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "null"); //$NON-NLS-1$
    /**
     * Корневые типы с предопределёнными значениями или метассылками ({@code ЛиКорневойТипОбъектаСПредопределеннымЛкс},
     * {@code ЛиТипТаблицыМетассылкиЛкс}) и их менеджеры глобального контекста.
     */
    private static final Map<String, String> ROOT_TYPE_MANAGERS = Map.of(
        "справочник", "Справочники", //$NON-NLS-1$ //$NON-NLS-2$
        "плансчетов", "ПланыСчетов", //$NON-NLS-1$ //$NON-NLS-2$
        "планвидовхарактеристик", "ПланыВидовХарактеристик", //$NON-NLS-1$ //$NON-NLS-2$
        "планвидоврасчета", "ПланыВидовРасчета", //$NON-NLS-1$ //$NON-NLS-2$
        "перечисление", "Перечисления"); //$NON-NLS-1$ //$NON-NLS-2$
    /** Системное свойство «текущего» элемента → коллекция, из которой его выбирают (ИР, стр. 18211). */
    private static final Map<String, String> CURRENT_ITEM_COLLECTIONS = Map.of(
        "текущаяобласть@табличныйдокумент", "Области", //$NON-NLS-1$ //$NON-NLS-2$
        "текущаяколонка@табличноеполе", "Колонки", //$NON-NLS-1$ //$NON-NLS-2$
        "текущаястраница@панель", "Страницы", //$NON-NLS-1$ //$NON-NLS-2$
        "текущаястраница@расширениегруппыформыдлястраниц", "ПодчиненныеЭлементы", //$NON-NLS-1$ //$NON-NLS-2$
        "текущаястраница@группаформы", "ПодчиненныеЭлементы", //$NON-NLS-1$ //$NON-NLS-2$
        "текущийэлемент@таблицаформы", "ПодчиненныеЭлементы", //$NON-NLS-1$ //$NON-NLS-2$
        "текущийэлемент@форма", "ЭлементыФормы", //$NON-NLS-1$ //$NON-NLS-2$
        "текущийэлемент@формаклиентскогоприложения", "Элементы"); //$NON-NLS-1$ //$NON-NLS-2$
    /** Коллекции, у которых индекс — ещё и имя (ИР: тип есть в {@code ТаблицаШаблоновКонтекстов}). */
    private static final Set<String> NAMED_INDEX_TYPES = Set.of("структура", "фиксированнаяструктура", //$NON-NLS-1$ //$NON-NLS-2$
        "строкатаблицызначений", "строкадеревазначений", "данныеформыструктура", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "данныеформыэлементколлекции", "данныеформыэлементдерева"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * Место списка: начало выражения ({@code Объект.} входит), родитель до точки и текст перед выражением.
     *
     * @param caret    каретка при расчёте (на месте не сравнивается)
     * @param selected выделенный текст на начало сессии автодополнения ({@code ВыделенныйТекст} ИР)
     */
    private record Context(IDocument document, int start, int anchor, String parent, String preceding,
        int precedingStart, int caret, String selected)
    {
        boolean sameAs(Context other)
        {
            return other != null && document == other.document && start == other.start
                && parent.equals(other.parent) && preceding.equals(other.preceding) && selected.equals(other.selected);
        }
    }

    /** Выделение в редакторе на начало сессии автодополнения. */
    private record Selection(IDocument document, int offset, String text)
    {
    }

    private static volatile Selection assistSelection;

    /**
     * Результат {@code РассчитатьОжидаемыйТипВыражения}.
     *
     * @param systemEnum     среди ожидаемых типов есть системное перечисление
     * @param systemProperty приёмник — системное свойство: {@code имя@тип владельца} в нижнем регистре
     */
    private record Expectation(List<String> types, String receiver, String token, String callMethod,
        int parameterIndex, boolean systemEnum, String systemProperty, boolean formalParameter)
    {
        /** Чистое «Неопределено» (типично — новая переменная) — ожидаемый тип неизвестен. */
        Expectation
        {
            if (types != null && types.size() == 1 && ("Неопределено".equalsIgnoreCase(types.get(0)) //$NON-NLS-1$
                || "Undefined".equalsIgnoreCase(types.get(0)))) //$NON-NLS-1$
                types = new ArrayList<>();
        }

        /** Ожидание не из формального параметра вызова. */
        Expectation(List<String> types, String receiver, String token, String callMethod, int parameterIndex,
            boolean systemEnum, String systemProperty)
        {
            this(types, receiver, token, callMethod, parameterIndex, systemEnum, systemProperty, false);
        }
    }

    /**
     * Всё, что читается из модели для места списка.
     *
     * @param statisticsContext {@code ТипКонтекстаСтатистики}: {@value #GLOBAL_CONTEXT} или тип родителя
     * @param frequencies       частота членов родителя в тексте метода: {@code m:}/{@code p:} + имя
     */
    /**
     * @param predefinedTypes значения, которые EDT предлагает для ожидаемого типа без типа в строке: имя
     *                        значения системного перечисления или приставка {@code Объект.} предопределённого
     *                        значения (в нижнем регистре) → тип
     */
    private record Scene(Expectation expectation, String statisticsContext, boolean insideExpression,
        Map<String, Integer> frequencies, String receiverLine, String expectedTypeText, Map<String, String> predefinedTypes,
        Facts facts)
    {
        /** Типы приёмника без слишком широких ({@link #BROAD_TYPES}), в нижнем регистре. */
        Set<String> strongReceiverTypes()
        {
            Set<String> result = new HashSet<>();
            if (expectation == null)
                return result;
            List<String> types = expectation.types();
            if (types.isEmpty())
            {
                String byName = typeFromReceiverName(expectation);
                types = byName != null && !byName.isEmpty() ? List.of(byName) : List.of();
            }
            for (String type : types)
            {
                if (!BROAD_TYPES.contains(lower(type)))
                    result.add(lower(type));
            }
            return result;
        }
    }

    /** Типы, совместимые почти со всем: совпадение по ним не считается сильным. */
    private static final Set<String> BROAD_TYPES = Set.of("неопределено", "undefined", "null", "произвольный", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "arbitrary", "?"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * Сильно ли тип строки списка ({@code <А, Б>} или {@code А}) совместим с типом приёмника места: есть общий
     * тип, кроме слишком широких. Без слов ИР — при них список оформляет ИР.
     */
    static boolean isStrongReceiverType(ICompletionProposal proposal, String typeText)
    {
        if (typeText == null || typeText.isBlank())
            return false;
        // Литерал запроса: снимка модуля нет, ожидаемый тип — у самой строки списка.
        List<String> queryTypes = BslQueryLiteralSupport.expectedTypesOf(proposal);
        Set<String> receiver;
        if (queryTypes != null)
        {
            receiver = new HashSet<>();
            for (String type : queryTypes)
                receiver.add(lower(type));
        }
        else
        {
            Snapshot snapshot = active;
            if (snapshot == null)
                return false;
            receiver = snapshot.scene().strongReceiverTypes();
            SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
            if (processor != null && processor.hasIrProposalsForCurrentContext())
                return false;
        }
        if (receiver.isEmpty())
            return false;
        String text = typeText.trim();
        if (text.startsWith("<") && text.endsWith(">")) //$NON-NLS-1$ //$NON-NLS-2$
            text = text.substring(1, text.length() - 1);
        for (String part : text.split(",")) //$NON-NLS-1$
        {
            String type = lower(part.trim());
            if (!BROAD_TYPES.contains(type) && receiver.contains(type))
                return true;
        }
        return false;
    }

    /** Строка таблицы слов: имя, типы (через запятую в ИР), метод ли, переменная ли. */
    private record Word(String key, String name, List<String> types, boolean method, boolean local)
    {
        boolean hasType(String typeName)
        {
            for (String type : types)
            {
                if (type.equalsIgnoreCase(typeName)
                    // Варианты формы ИР: расширения формы совместимы с «Форма…»
                    || lower(type).startsWith("расширениеформы") && lower(typeName).startsWith("форма")) //$NON-NLS-1$ //$NON-NLS-2$
                    return true;
            }
            return false;
        }

        /** Тип менеджера системного перечисления {@code typeName}: {@code Перечисление<Тип>} (и {@code …Менеджер}). */
        boolean hasEnumManagerType(String typeName)
        {
            String manager = lower("Перечисление" + typeName); //$NON-NLS-1$
            for (String type : types)
            {
                String compact = lower(type).replace(" ", ""); //$NON-NLS-1$ //$NON-NLS-2$
                if (compact.equals(manager) || compact.equals(manager + "менеджер") //$NON-NLS-1$
                    || compact.endsWith("." + manager)) //$NON-NLS-1$
                    return true;
            }
            return false;
        }
    }

    /** Премии слов и слова, дающие право на автооткрытие ({@code ВключатьАвтоОткрытие} ИР). */
    record Premiums(Map<String, Integer> values, Set<String> autoOpen)
    {
        static Premiums empty()
        {
            return new Premiums(new HashMap<>(), new HashSet<>());
        }
    }

    /** @param added слова, которых нет у EDT ({@link AddedWords}) */
    private record Snapshot(Context context, Scene scene, IProject project, Map<String, List<String>> variableTypes,
        Premiums premiums, Set<String> evaluated, List<BslAddedWordProposal> added)
    {
    }

    /**
     * Сведения модели для новых слов и первой половины {@code ПрименитьОжидаемыйТипВТаблицеСлов}
     * ({@link AddedWords}); читаются вместе со снимком.
     *
     * @param bodyStartLine        {@code мНомерПервойСтрокиТелаМетода} (с 0); {@code -1} — вне метода
     * @param parentTypes          типы родителя ({@code мСтруктураТипаКонтекста}), первый — главный
     * @param parentCollection     у главного типа родителя есть тип элемента коллекции
     * @param parentIndexed        у главного типа родителя числовой индекс ({@code ЕстьЧисловойИндекс})
     * @param parentSystemEnum     родитель — системное перечисление
     * @param constructorTypes     ожидаемые типы с конструктором {@code Новый} (в нижнем регистре)
     * @param nestedTypes          {@code ИменаВложенныхТипов}: типы {@code Х} у {@code ТипЗнч(Х) =}
     * @param objectPropertyEnums  перечисления {@code Метаданные.СвойстваОбъектов} (в нижнем регистре)
     * @param formClient           модуль формы, метод на клиенте ({@code КлиентУправляемоеПриложение})
     * @param structureConstructor аргумент {@code Новый Структура(…)}
     * @param locals               переменные метода и модуля ({@code АнализаторСПолнойТаблицейСловМетода})
     * @param receiverProperties   свойства объекта слева от {@code =} ({@code ИмяЛевогоОбъекта})
     * @param selectionTypes       типы выделенного выражения
     * @param queryTotals          родитель — {@code РезультатЗапроса}: колонки {@code ИТОГИ ПО} его запроса
     *                             ({@code Построитель.Измерения} ИР); {@code null} — текст запроса не определён
     * @param betweenMethods       место вне метода ({@code ЛиМеждуМетодами})
     * @param moduleType           вид модуля; {@code null} — не определён
     */
    private record Facts(int bodyStartLine, List<String> parentTypes, boolean parentCollection, boolean parentIndexed,
        boolean parentSystemEnum, Set<String> constructorTypes, List<String> nestedTypes,
        Set<String> objectPropertyEnums, boolean formClient, boolean structureConstructor, List<Local> locals,
        List<Local> receiverProperties, List<String> selectionTypes, List<String> queryTotals, boolean betweenMethods,
        ModuleType moduleType)
    {
    }

    /**
     * Переменная или свойство для генераторов.
     *
     * @param visible переменная определена до текущей инструкции / свойство доступно для записи
     */
    private record Local(String name, List<String> types, boolean visible)
    {
    }

    /** Вызов нечёткого поиска — для сверки с ИР. */
    private record FuzzyCall(String search, List<String> candidates, int minPercentSmall, int minPercentBig,
        List<Integer> result)
    {
    }

    /** Последний посчитанный снимок. */
    private static volatile Snapshot current;
    /** Снимок места, для которого список сортируется сейчас; {@code null} — премий нет (ещё считаются). */
    private static volatile Snapshot active;
    /** Место последнего запроса. */
    private static volatile Context requested;
    private static volatile Context pending;

    private BslExpectedTypePremiums()
    {
    }

    /** Премия слова для текущего места; {@code 0} — нет. */
    static int premiumOf(ICompletionProposal proposal)
    {
        Integer queryPremium = BslQueryLiteralSupport.premiumOf(proposal);
        if (queryPremium != null)
            return queryPremium.intValue();
        Snapshot snapshot = active;
        if (snapshot == null || snapshot.premiums().values().isEmpty() || proposal == null)
            return 0;
        Integer premium = snapshot.premiums().values().get(lower(SmartContentAssistProcessor.filterMatchName(proposal)));
        return premium != null ? premium.intValue() : 0;
    }

    /** Есть ли у текущего места строка «Приемник.Тип» для боковой подсказки (без слов ИР). */
    static boolean hasReceiverLine()
    {
        Snapshot snapshot = active;
        if (snapshot == null || snapshot.scene().receiverLine() == null)
            return false;
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        return processor == null || !processor.hasIrProposalsForCurrentContext();
    }

    /** Есть ли для текущего места хоть одна премия. */
    static boolean hasPremiums()
    {
        Snapshot snapshot = active;
        return snapshot != null && !snapshot.premiums().values().isEmpty();
    }

    /**
     * Список {@code raw} со словами, которые расчёт премий места добавил сам ({@link BslAddedWordProposal}), — в
     * конце; слова другого места убираются. При словах ИР слова добавляет ИР.
     */
    static ICompletionProposal[] withAddedWords(ICompletionProposal[] raw)
    {
        if (raw == null)
            return null;
        Snapshot snapshot = active;
        List<BslAddedWordProposal> added = snapshot != null ? snapshot.added() : List.of();
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        if (processor != null && processor.hasIrProposalsForCurrentContext())
            added = List.of();
        boolean changed = false;
        Set<ICompletionProposal> present = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (ICompletionProposal proposal : raw)
        {
            if (proposal instanceof BslAddedWordProposal)
            {
                if (added.contains(proposal))
                    present.add(proposal);
                else
                    changed = true;
            }
        }
        if (!changed && present.size() == added.size())
            return raw;
        List<ICompletionProposal> result = new ArrayList<>(raw.length + added.size());
        for (ICompletionProposal proposal : raw)
        {
            if (!(proposal instanceof BslAddedWordProposal) || present.contains(proposal))
                result.add(proposal);
        }
        for (BslAddedWordProposal proposal : added)
        {
            if (!present.contains(proposal))
                result.add(proposal);
        }
        return result.toArray(new ICompletionProposal[result.size()]);
    }

    /**
     * Есть ли у места {@code caret} слово с премией, при которой ИР открывает список сам. Только
     * чтение готового снимка: без него — {@code false}.
     */
    static boolean hasAutoOpenWord(IDocument document, int caret)
    {
        Boolean queryDecision = BslQueryLiteralSupport.autoOpenAt(document, caret);
        if (queryDecision != null)
            return queryDecision.booleanValue();
        Snapshot snapshot = current;
        if (snapshot == null || document == null || caret < 0)
            return false;
        try
        {
            Context context = contextAt(document, caret);
            return context != null && snapshot.context().sameAs(context) && !snapshot.premiums().autoOpen().isEmpty();
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** Готовый ожидаемый тип места для подписи окна; без чтения модели из UI. */
    static String expectedTypeTextAt(IDocument document, int caret)
    {
        String query = BslQueryLiteralSupport.expectedTypeTextAt(document, caret);
        if (query != null)
            return query;
        Snapshot snapshot = current;
        try
        {
            Context context = document != null && caret >= 0 ? contextAt(document, caret) : null;
            return snapshot != null && snapshot.context().sameAs(context) ? snapshot.scene().expectedTypeText() : ""; //$NON-NLS-1$
        }
        catch (Exception error)
        {
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * Общая перенесённая логика премий ИР для списка QL: ожидаемый тип и статистика выбора языка запросов;
     * вызывается только в фоне.
     */
    static Premiums queryPremiums(IDocument document, int anchor, List<String> expectedTypes,
        String receiver, String parent, String parentTypes, IProject project, ICompletionProposal[] proposals)
    {
        // EDT уже возвращает типы полей и предложений в одном представлении.
        List<String> queryTypes = expectedTypes;
        Context context = new Context(document, anchor, anchor, parent, "", 0, anchor, ""); //$NON-NLS-1$ //$NON-NLS-2$
        Expectation expectation = new Expectation(queryTypes, receiver, "=", "", -1, false, null); //$NON-NLS-1$ //$NON-NLS-2$
        Premiums result = Premiums.empty();
        Map<String, Word> words = words(proposals, Map.of(), Map.of());
        typePremiums(result, context, expectation, words, new ArrayList<>(), null);
        // ЗагрузитьСтатистикуВыбораВТаблицуСлов; «внутри выражения» в запросе не различается
        String statisticsContext = queryStatisticsContext(parent, parentTypes);
        if (!GLOBAL_CONTEXT.equals(statisticsContext))
            weightPremiums(result, words, ChoiceStatistics.weights(project, statisticsContext, false, QUERY_LANGUAGE),
                words.size(), !parent.isEmpty());
        if (parent.isEmpty())
            weightPremiums(result, words, ChoiceStatistics.weights(project, GLOBAL_CONTEXT, false, QUERY_LANGUAGE),
                words.size(), false);
        return new Premiums(Map.copyOf(result.values()), Set.copyOf(result.autoOpen()));
    }

    /**
     * {@code ТипКонтекстаСтатистики} для языка запросов: без родителя — {@value #GLOBAL_CONTEXT}; родитель с
     * известным типом — первый тип; без типа ({@code Справочник.}) — текст родителя.
     */
    static String queryStatisticsContext(String parent, String parentTypes)
    {
        if (parent == null || parent.isEmpty())
            return GLOBAL_CONTEXT;
        if (parentTypes != null && !parentTypes.isBlank())
        {
            String first = parentTypes.split(",")[0].trim(); //$NON-NLS-1$
            if (!first.isEmpty())
                return first;
        }
        return parent;
    }

    /**
     * Готовит снимок премий для списка {@code list} у каретки {@code caret} (модельное смещение). Фоновый
     * поток считает сразу, UI-поток — только запускает фоновый расчёт.
     */
    static void ensure(IDocument document, int caret, ICompletionProposal[] list)
    {
        if (!(document instanceof IXtextDocument) || caret < 0 || list == null || list.length == 0)
            return;
        Context context;
        try
        {
            context = contextAt(document, caret);
        }
        catch (Exception e)
        {
            return;
        }
        if (context == null)
        {
            requested = null;
            active = null;
            return;
        }
        requested = context;
        Snapshot snapshot = current;
        if (snapshot != null && snapshot.context().sameAs(context))
        {
            publish(extend(snapshot, list));
            return;
        }
        active = null;
        IProject project = activeProject();
        if (Display.getCurrent() != null)
        {
            schedule(context, project, list.clone());
            return;
        }
        publish(compute(context, project, list));
    }

    /** Новый снимок становится текущим, а действующим — если место с тех пор не сменилось. */
    private static void publish(Snapshot computed)
    {
        if (computed == null)
            return;
        current = computed;
        if (computed.context().sameAs(requested))
            active = computed;
    }

    private static IProject activeProject()
    {
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        BslXtextEditor editor = reloader != null ? reloader.getBslEditor() : null;
        IFile file = editor != null && editor.getEditorInput() != null
            ? editor.getEditorInput().getAdapter(IFile.class) : null;
        return file != null ? file.getProject() : null;
    }

    private static Context contextAt(IDocument document, int caret) throws Exception
    {
        if (SmartContentAssistProcessor.isStringLiteralAssistContext(document, caret))
            return null;
        // Выделение, на котором открыт список: место — его начало
        String selected = ""; //$NON-NLS-1$
        Selection selection = assistSelection;
        if (selection != null && selection.document() == document && caret >= selection.offset()
            && caret <= selection.offset() + selection.text().length()
            && selection.offset() + selection.text().length() <= document.getLength()
            && document.get(selection.offset(), selection.text().length()).equals(selection.text()))
        {
            selected = selection.text();
            caret = selection.offset();
        }
        int anchor = caret - SmartContentAssistProcessor.computeIdentifierFilter(document, caret).length();
        int start = anchor;
        while (start > 0 && document.getChar(start - 1) == '.')
        {
            int before = start - 1;
            int pos = before;
            if (pos > 0 && (document.getChar(pos - 1) == ')' || document.getChar(pos - 1) == ']'))
                pos = skipBrackets(document, pos - 1);
            while (pos > 0 && SmartContentAssistProcessor.isFilterChar(document.getChar(pos - 1)))
                pos--;
            if (pos >= before)
                break;
            start = pos;
        }
        String parent = start < anchor ? document.get(start, anchor - 1 - start) : ""; //$NON-NLS-1$
        int precedingStart = Math.max(0, start - PRECEDING_TEXT_LENGTH);
        return new Context(document, start, anchor, parent, document.get(precedingStart, start - precedingStart),
            precedingStart, caret, selected);
    }

    /** {@code ВыделенныйТекст} на начало сессии автодополнения (UI-поток). */
    private static void captureAssistSelection()
    {
        SourceViewer viewer = ContentAssistSessionReloader.getActiveViewer();
        IDocument document = viewer != null ? viewer.getDocument() : null;
        Point range = viewer != null ? viewer.getSelectedRange() : null;
        Selection selection = null;
        if (document != null && range != null && range.y > 0)
        {
            try
            {
                selection = new Selection(document, range.x, document.get(range.x, range.y));
            }
            catch (Exception e)
            {
                selection = null;
            }
        }
        assistSelection = selection;
    }

    /** Начало скобочной группы, закрытой в {@code close}; при несбалансированных скобках — {@code close + 1}. */
    private static int skipBrackets(IDocument document, int close) throws Exception
    {
        int depth = 0;
        for (int pos = close; pos >= 0; pos--)
        {
            char c = document.getChar(pos);
            if (c == ')' || c == ']')
                depth++;
            else if ((c == '(' || c == '[') && --depth == 0)
                return pos;
        }
        return close + 1;
    }

    private static void schedule(Context context, IProject project, ICompletionProposal[] list)
    {
        if (context.sameAs(pending))
            return;
        pending = context;
        Job job = new Job("Комфорт: ожидаемый тип") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    Snapshot computed = compute(context, project, list);
                    if (computed == null)
                        return Status.OK_STATUS;
                    publish(computed);
                    if (active == computed)
                    {
                        Display display = Display.getDefault();
                        if (display != null && !display.isDisposed())
                            display.asyncExec(() -> {
                                ContentAssistSessionReloader.refreshPopupIfOpen();
                                ContentAssistSessionReloader.onContextPremiumsReady(context.document());
                            });
                    }
                }
                finally
                {
                    if (pending == context)
                        pending = null;
                }
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.setPriority(Job.INTERACTIVE);
        job.schedule();
    }

    /**
     * Слова, которых в снимке ещё не было (другой срез списка того же места), — без нового чтения модели.
     *
     * @return снимок с этими словами (или прежний, если новых нет)
     */
    private static Snapshot extend(Snapshot snapshot, ICompletionProposal[] list)
    {
        boolean unseen = false;
        for (ICompletionProposal proposal : list)
        {
            String key = lower(SmartContentAssistProcessor.filterMatchName(proposal));
            if (!key.isEmpty() && !snapshot.evaluated().contains(key))
            {
                unseen = true;
                break;
            }
        }
        if (!unseen)
            return snapshot;
        // Новые слова среза не нужны: они уже в снимке (премии считаются и для них)
        Premiums added = premiums(snapshot.context(), snapshot.scene(), snapshot.project(), list,
            snapshot.variableTypes(), new ArrayList<>(), new LinkedHashMap<>());
        Map<String, Integer> merged = new HashMap<>(snapshot.premiums().values());
        added.values().forEach(merged::putIfAbsent);
        Set<String> autoOpen = new HashSet<>(snapshot.premiums().autoOpen());
        autoOpen.addAll(added.autoOpen());
        Set<String> evaluated = new HashSet<>(snapshot.evaluated());
        for (ICompletionProposal proposal : list)
            evaluated.add(lower(SmartContentAssistProcessor.filterMatchName(proposal)));
        Snapshot extended = new Snapshot(snapshot.context(), snapshot.scene(), snapshot.project(),
            snapshot.variableTypes(), new Premiums(merged, autoOpen), evaluated, snapshot.added());
        current = extended;
        return extended;
    }

    private static Snapshot compute(Context context, IProject project, ICompletionProposal[] list)
    {
        long started = System.nanoTime();
        Scene[] scene = new Scene[1];
        Map<String, List<String>> variableTypes = new HashMap<>();
        Boolean read;
        try
        {
            read = ContentAssistSessionReloader.readOnlyForContentAssist((IXtextDocument)context.document(),
                resource ->
                {
                    if (resource == null)
                        return Boolean.FALSE;
                    READ_RESOURCE.set(resource);
                    try
                    {
                        scene[0] = scene(resource, context, list);
                        if (scene[0].expectation() != null)
                            collectVariableTypes(resource, context, list, variableTypes);
                    }
                    finally
                    {
                        READ_RESOURCE.remove();
                    }
                    return Boolean.TRUE;
                });
        }
        catch (RuntimeException e)
        {
            Global.tempLog(LOG, "read error " + e); //$NON-NLS-1$
            return null;
        }
        if (!Boolean.TRUE.equals(read) || scene[0] == null)
            return null;
        List<FuzzyCall> fuzzyCalls = new ArrayList<>();
        Map<String, BslAddedWordProposal> added = new LinkedHashMap<>();
        Premiums premiums = premiums(context, scene[0], project, list, variableTypes, fuzzyCalls, added);
        Set<String> evaluated = new HashSet<>();
        for (ICompletionProposal proposal : list)
            evaluated.add(lower(SmartContentAssistProcessor.filterMatchName(proposal)));
        // #region temp — типы слов, названных как ожидаемый тип (менеджер системного перечисления)
        StringBuilder namedLikeType = new StringBuilder();
        if (scene[0].expectation() != null)
        {
            Map<String, Word> diagnosticWords = words(list, variableTypes, scene[0].predefinedTypes());
            for (String type : scene[0].expectation().types())
            {
                Word named = diagnosticWords.get(lower(type));
                namedLikeType.append(type).append("->").append(named == null ? "нет" : named.types()).append(' '); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        // #endregion
        // #region temp — строки предопределённых значений EDT: почему не распознан тип
        int predefinedShown = 0;
        for (ICompletionProposal proposal : list)
        {
            ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
            String className = raw == null ? "null" : raw.getClass().getName(); //$NON-NLS-1$
            if (!className.contains("Predefined") && !className.contains("Segmentable")) //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            Object info = raw instanceof ConfigurableCompletionProposal configurable
                ? Global.getField(configurable, "additionalProposalInfo") : null; //$NON-NLS-1$
            Global.tempLog(LOG, "  predefined class=" + className.substring(className.lastIndexOf('.') + 1) //$NON-NLS-1$
                + " display=\"" + raw.getDisplayString() + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                + " name=\"" + SmartContentAssistProcessor.parseProposalListName(raw.getDisplayString()) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                + " replacement=\"" + (raw instanceof ConfigurableCompletionProposal c ? c.getReplacementString() : null) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                + " info=" + (info == null ? "null" : info.getClass().getSimpleName()) //$NON-NLS-1$ //$NON-NLS-2$
                + " type=" + SmartCompletionProposal.predefinedValueType(proposal)); //$NON-NLS-1$
            if (++predefinedShown == 10)
                break;
        }
        // #endregion
        Global.tempLog(LOG, "start=" + context.start() + " parent=" + context.parent() //$NON-NLS-1$ //$NON-NLS-2$
            + " namedLikeType=" + namedLikeType //$NON-NLS-1$
            + " words=" + list.length + " expectation=" + scene[0].expectation() //$NON-NLS-1$ //$NON-NLS-2$
            + " statistics=" + scene[0].statisticsContext() + " inExpression=" + scene[0].insideExpression() //$NON-NLS-1$ //$NON-NLS-2$
            + " frequencies=" + scene[0].frequencies().size() //$NON-NLS-1$
            + " predefinedTypes=" + scene[0].predefinedTypes() //$NON-NLS-1$
            + " variables=" + variableTypes.size() + " premiums=" + premiums.values() //$NON-NLS-1$ //$NON-NLS-2$
            + " autoOpen=" + premiums.autoOpen() //$NON-NLS-1$
            + " added=" + added.keySet() //$NON-NLS-1$
            + " facts=" + scene[0].facts() //$NON-NLS-1$
            + " ms=" + (System.nanoTime() - started) / 1_000_000L); //$NON-NLS-1$
        reconcileFuzzyWithIr(fuzzyCalls);
        return new Snapshot(context, scene[0], project, variableTypes, premiums, evaluated,
            List.copyOf(added.values()));
    }

    private static Scene scene(XtextResource resource, Context context, ICompletionProposal[] list)
    {
        ParamHintHtmlModifier.CallSiteInfo site = ParamHintHtmlModifier.findCallSiteAt(resource, context.start());
        if (site != null && !site.contains(context.start()))
            site = null;
        Expectation expectation = expectation(resource, context, site, list);
        List<TypeItem> parentTypes = context.parent().isEmpty() ? List.of()
            : expressionTypeItems(resource, context.start(), context.anchor() - 1, context.parent());
        String statisticsContext = statisticsContext(context, typeNames(parentTypes));
        boolean insideExpression = insideExpression(context, site);
        Map<String, Integer> frequencies = context.parent().isEmpty() ? Collections.emptyMap()
            : memberFrequencies(resource, context);
        return new Scene(expectation, statisticsContext, insideExpression, frequencies,
            receiverTypeLine(expectation, resource), String.join(", ", receiverTypes(expectation)), //$NON-NLS-1$
            predefinedTypes(resource, expectation, list), facts(resource, context, expectation, site, parentTypes));
    }

    /** Приставка ключа {@link Scene#predefinedTypes} для предопределённых значений ссылочного типа. */
    private static final String PREDEFINED_PREFIX_KEY = "prefix:"; //$NON-NLS-1$

    /**
     * Типы значений, которые EDT предлагает для ожидаемого типа строками без типа: значения системного
     * перечисления — свойства его менеджера (свойство глобального контекста с именем типа), предопределённые
     * значения ссылки — строки {@code Объект.Значение}.
     */
    private static Map<String, String> predefinedTypes(XtextResource resource, Expectation expectation,
        ICompletionProposal[] list)
    {
        Map<String, String> result = new HashMap<>();
        if (expectation == null)
            return result;
        for (String type : expectation.types())
        {
            int dot = type.indexOf('.');
            if (dot > 0)
            {
                if (type.substring(0, dot).endsWith("Ссылка")) //$NON-NLS-1$
                    result.put(PREDEFINED_PREFIX_KEY + lower(type.substring(dot + 1)) + ".", type); //$NON-NLS-1$
                continue;
            }
            for (ICompletionProposal proposal : list)
            {
                if (!type.equalsIgnoreCase(SmartContentAssistProcessor.filterMatchName(proposal))
                    || !(proposalInfo(proposal) instanceof Property manager) || manager.eIsProxy())
                    continue;
                for (TypeItem item : manager.getTypes())
                {
                    Type managerType = resolveType(resource, item);
                    ContextDef def = managerType != null ? managerType.getContextDef() : null;
                    if (def == null)
                        continue;
                    for (Property value : def.getProperties())
                    {
                        if (value.getNameRu() != null)
                            result.putIfAbsent(lower(value.getNameRu()), type);
                        if (value.getName() != null)
                            result.putIfAbsent(lower(value.getName()), type);
                    }
                }
                break;
            }
        }
        return result;
    }

    /**
     * Тип строки значения, которую EDT создала для ожидаемого типа без типа в подписи ({@link #predefinedTypes});
     * {@code null} — строка не такая или место другое.
     */
    static String predefinedValueTypeInContext(ICompletionProposal proposal)
    {
        Snapshot snapshot = active;
        return snapshot != null ? predefinedTypeIn(proposal, snapshot.scene().predefinedTypes()) : null;
    }

    private static String predefinedTypeIn(ICompletionProposal proposal, Map<String, String> types)
    {
        ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
        if (types == null || types.isEmpty() || raw == null || proposalInfo(raw) != null)
            return null;
        String className = raw.getClass().getName();
        if (!className.endsWith("$PredefinedValueCompletionProposal") //$NON-NLS-1$
            && !className.endsWith("$SegmentableCompletionProposal")) //$NON-NLS-1$
            return null;
        String name = lower(SmartContentAssistProcessor.parseProposalListName(raw.getDisplayString()));
        String type = types.get(name);
        if (type != null)
            return type;
        for (Map.Entry<String, String> entry : types.entrySet())
        {
            if (entry.getKey().startsWith(PREDEFINED_PREFIX_KEY)
                && name.startsWith(entry.getKey().substring(PREDEFINED_PREFIX_KEY.length())))
                return entry.getValue();
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // РассчитатьОжидаемыйТипВыражения
    // ------------------------------------------------------------------------------------------

    private static Expectation expectation(XtextResource resource, Context context,
        ParamHintHtmlModifier.CallSiteInfo site, ICompletionProposal[] list)
    {
        String preceding = stripEnd(context.preceding());
        // После «Новый» — список типов, его ранжирует EDT.
        if (preceding.isEmpty() || endsWithWord(preceding, "Новый")) //$NON-NLS-1$
            return null;
        // Хвост из цифр перед контекстом ИР отрезает
        int digits = preceding.length();
        while (digits > 0 && Character.isDigit(preceding.charAt(digits - 1)))
            digits--;
        if (digits < preceding.length())
            preceding = stripEnd(preceding.substring(0, digits));
        if (preceding.isEmpty())
            return null;
        List<TypeItem> types = new ArrayList<>();
        String receiver = ""; //$NON-NLS-1$
        String callMethod = ""; //$NON-NLS-1$
        int parameterIndex = -1;
        String systemProperty = null;
        // «Возврат» ИР читает как присваивание переменной результата метода.
        if (endsWithWord(preceding, "Возврат") || endsWithWord(preceding, "Return")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            types.addAll(returnTypes(resource, context.start()));
            return new Expectation(typeNames(types), RETURN_VARIABLE, "=", "", -1, hasSystemEnum(types), null); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String token = preceding.endsWith("<>") ? "<>" : preceding.substring(preceding.length() - 1); //$NON-NLS-1$ //$NON-NLS-2$
        if (site != null)
        {
            parameterIndex = ParamHintHtmlModifier.paramNumberLikeEdt(site, context.start());
            CallExpectation call = site.owner instanceof Invocation invocation
                ? invocationExpectation(resource, invocation, parameterIndex, list, context.start())
                : site.owner instanceof OperatorStyleCreator creator
                    ? constructorExpectation(resource, creator, parameterIndex, context.start()) : null;
            if (call != null)
            {
                types.addAll(call.types());
                receiver = call.receiver();
                callMethod = call.method();
            }
        }
        // Ожидание от параметра вызова, пока его не перебил приёмник слева от оператора
        boolean formalParameter = site != null;
        switch (token)
        {
            case "=": //$NON-NLS-1$
            case "<>": //$NON-NLS-1$
            case ">": //$NON-NLS-1$
            case "<": //$NON-NLS-1$
            case "-": //$NON-NLS-1$
            {
                Matcher typeOf = TYPE_OF.matcher(preceding);
                if (typeOf.find() && !"-".equals(token)) //$NON-NLS-1$
                {
                    // ТипЗнч(Х) = — ожидается Тип; вложенные типы ИР нужны только для новых слов
                    receiver = ""; //$NON-NLS-1$
                    types.clear();
                    String typeName = platformTypeName("Тип"); //$NON-NLS-1$
                    return new Expectation(new ArrayList<>(List.of(typeName != null ? typeName : "Тип")), receiver, //$NON-NLS-1$
                        token, callMethod, parameterIndex, false, null);
                }
                int exprEnd = context.precedingStart()
                    + stripEnd(preceding.substring(0, preceding.length() - token.length())).length();
                Expression leftExpression = findExpressionEndingAt(resource, exprEnd);
                ICompositeNode leftNode = leftExpression != null ? NodeModelUtils.findActualNodeFor(leftExpression) : null;
                int exprStart = leftNode != null ? leftNode.getOffset() : -1;
                if (leftExpression != null)
                    receiver = expressionText(leftExpression);
                else
                {
                    // Неполный AST: сохраняем прежний разбор простых имён и свойств.
                    Matcher matcher = receiverMatcher(token, preceding);
                    if (matcher != null)
                    {
                        receiver = matcher.group(1);
                        exprStart = context.precedingStart() + matcher.start(1);
                        exprEnd = exprStart + receiver.length();
                    }
                }
                if (exprStart >= 0)
                {
                    formalParameter = false;
                    types = expressionTypeItems(resource, exprStart, exprEnd, receiver);
                    systemProperty = systemProperty(resource, exprStart, exprEnd, receiver);
                    if ("-".equals(token)) //$NON-NLS-1$
                    {
                        receiver = ""; //$NON-NLS-1$
                        List<String> names = typeNames(types);
                        if (containsIgnoreCase(names, "Дата")) //$NON-NLS-1$
                        {
                            names.add(NUMBER);
                            return new Expectation(names, receiver, token, callMethod, parameterIndex, false, null);
                        }
                    }
                }
                break;
            }
            case "*": //$NON-NLS-1$
            case "/": //$NON-NLS-1$
            case "%": //$NON-NLS-1$
                return new Expectation(new ArrayList<>(List.of(NUMBER)), receiver, token, callMethod, parameterIndex,
                    false, null);
            case "[": //$NON-NLS-1$
            {
                Matcher matcher = receiverMatcher(token, preceding);
                if (matcher != null)
                {
                    int exprStart = context.precedingStart() + matcher.start(1);
                    List<TypeItem> collection = expressionTypeItems(resource, exprStart,
                        exprStart + matcher.group(1).length(), matcher.group(1));
                    // Сборка частых имен ключевого свойства коллекции — для нечёткого подбора приёмника
                    return new Expectation(typeNames(indexTypes(resource, collection)), matcher.group(1) + ".ИмяКлючИндекс", //$NON-NLS-1$
                        token, callMethod, parameterIndex, false, null);
                }
                break;
            }
            default:
            {
                // При вводе новой первой инструкции цикла даем приоритет итератору цикла
                String lastLine = preceding.substring(preceding.lastIndexOf('\n') + 1).trim();
                Matcher matcher = LOOP_ITERATOR.matcher(lastLine);
                if (matcher.find() && site == null)
                    receiver = matcher.group(1);
                break;
            }
        }
        List<String> names = typeNames(types);
        if (names.isEmpty() && receiver.isEmpty())
            return null;
        return new Expectation(names, receiver, token, callMethod, parameterIndex, hasSystemEnum(types),
            systemProperty, formalParameter);
    }

    /** Тип ключа индекса {@code коллекция[}: у соответствия — тип ключа, у индексируемых — Число, у именованных — ещё Строка. */
    private static List<TypeItem> indexTypes(XtextResource resource, List<TypeItem> collection)
    {
        List<TypeItem> result = new ArrayList<>();
        for (TypeItem item : collection)
        {
            String name = lower(typeName(item));
            if (name.endsWith("соответствие")) //$NON-NLS-1$
            {
                result.addAll(propertyTypes(resource, elementTypes(resource, List.of(item)), "Ключ")); //$NON-NLS-1$
                continue;
            }
            if (hasMethod(resource, item, "Получить")) //$NON-NLS-1$
                addTypeByName(result, NUMBER);
            if (NAMED_INDEX_TYPES.contains(name))
                addTypeByName(result, STRING);
        }
        if (result.isEmpty() && !collection.isEmpty())
            addTypeByName(result, NUMBER);
        return result;
    }

    private static Matcher receiverMatcher(String token, String preceding)
    {
        Matcher matcher = Pattern.compile("(?:^|[\\s(,])(" + SIMPLE_EXPRESSION + ")\\s*" //$NON-NLS-1$ //$NON-NLS-2$
            + Pattern.quote(token) + "$").matcher(preceding); //$NON-NLS-1$
        return matcher.find() ? matcher : null;
    }

    /** Ожидание в аргументе вызова: типы параметра, приёмник (имя параметра или ключа) и имя метода. */
    private record CallExpectation(List<TypeItem> types, String receiver, String method)
    {
    }

    /** {@code ТаблицаОжидаемыхТиповПараметраМетода} для вызова метода. */
    private static CallExpectation invocationExpectation(XtextResource resource, Invocation invocation, int index,
        ICompletionProposal[] list, int offset)
    {
        if (index < 0)
            return null;
        FeatureAccess access = invocation.getMethodAccess();
        String methodName = access != null && access.getName() != null ? access.getName() : ""; //$NON-NLS-1$
        List<Expression> args = invocation.getParams();
        Expression source = access instanceof DynamicFeatureAccess dynamic ? dynamic.getSource() : null;
        List<TypeItem> sourceTypes = source != null ? expressionTypeItems(resource, source, offset) : List.of();
        List<TypeItem> types = new ArrayList<>();
        String receiver = parameterExpectation(resource, invocation, index, types);
        if (FILL_PROPERTY_VALUES.equalsIgnoreCase(methodName) && index == 1 && !args.isEmpty() && args.get(0) != null)
        {
            types.clear();
            List<TypeItem> firstTypes = expressionTypeItems(resource, args.get(0), offset);
            if (!containsIgnoreCase(typeNames(firstTypes), STRUCTURE))
            {
                types.addAll(firstTypes);
                addTypeByName(types, STRUCTURE);
            }
            return new CallExpectation(types, expressionText(args.get(0)), methodName);
        }
        // Запрос.УстановитьПараметр / …Параметры.Вставить: приёмник — имя параметра (тип — из текста запроса, не считается)
        if (index == 1 && !args.isEmpty()
            && ("УстановитьПараметр".equalsIgnoreCase(methodName) && containsIgnoreCase(typeNames(sourceTypes), "Запрос") //$NON-NLS-1$ //$NON-NLS-2$
                || "Вставить".equalsIgnoreCase(methodName) && source != null //$NON-NLS-1$
                    && lower(expressionText(source)).endsWith("параметры"))) //$NON-NLS-1$
        {
            String parameter = literalText(args.get(0));
            if (IDENTIFIER.matcher(parameter).matches())
                return new CallExpectation(new ArrayList<>(), parameter, methodName);
        }
        // ТаблицаЗначений/ТабличнаяЧасть.Найти(Значение, "Колонка")
        if ("Найти".equalsIgnoreCase(methodName) && index == 0 && args.size() > 1 && isTableType(sourceTypes)) //$NON-NLS-1$
        {
            String column = literalText(args.get(1));
            if (IDENTIFIER.matcher(column).matches())
                return new CallExpectation(propertyTypes(resource, elementTypes(resource, sourceTypes), column), column,
                    methodName);
        }
        // Структура.Вставить("Ключ", Значение)
        if ("Вставить".equalsIgnoreCase(methodName) && index >= 1 && !args.isEmpty() //$NON-NLS-1$
            && containsIgnoreCase(typeNames(sourceTypes), STRUCTURE))
        {
            String key = literalText(args.get(0));
            if (IDENTIFIER.matcher(key).matches())
            {
                List<TypeItem> owners = new ArrayList<>(sourceTypes);
                owners.addAll(variableTypesAtMethodEnd(resource, source));
                return new CallExpectation(propertyTypes(resource, owners, key), key, methodName);
            }
        }
        // Массив.Добавить(Значение): тип элемента
        if ("Добавить".equalsIgnoreCase(methodName) && index == 0 && containsIgnoreCase(typeNames(sourceTypes), "Массив")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            List<TypeItem> elements = elementTypes(resource, sourceTypes);
            if (!elements.isEmpty())
                return new CallExpectation(elements, receiver, methodName);
        }
        // БСП: ВыполнитьФункцию/ВыполнитьПроцедуру("Модуль.Метод", П1, …)
        if (("ВыполнитьФункцию".equalsIgnoreCase(methodName) || "ВыполнитьПроцедуру".equalsIgnoreCase(methodName)) //$NON-NLS-1$ //$NON-NLS-2$
            && !isPlatformMethod(access) && args.size() > 1 && index > 1)
        {
            CallExpectation target = executedMethodExpectation(resource, literalText(args.get(1)), index - 2, list,
                offset);
            if (target != null)
                return new CallExpectation(target.types(), target.receiver(), methodName);
        }
        return new CallExpectation(types, receiver, methodName);
    }

    /** {@code ТаблицаОжидаемыхТиповПараметраМетода} для конструктора {@code Новый Тип(…)}. */
    private static CallExpectation constructorExpectation(XtextResource resource, OperatorStyleCreator creator,
        int index, int offset)
    {
        if (index < 0)
            return null;
        Type type = resolveType(resource, creator.getType());
        if (type == null)
            return null;
        String typeName = typeName(type);
        List<Expression> args = creator.getParams();
        if ((STRUCTURE.equalsIgnoreCase(typeName) || "ФиксированнаяСтруктура".equalsIgnoreCase(typeName)) //$NON-NLS-1$
            && index >= 1 && !args.isEmpty())
        {
            String[] keys = literalText(args.get(0)).split(","); //$NON-NLS-1$
            if (index - 1 < keys.length)
            {
                String key = keys[index - 1].trim();
                if (IDENTIFIER.matcher(key).matches())
                    return new CallExpectation(propertyTypes(resource, constructedVariableTypes(resource, creator), key),
                        key, typeName);
            }
        }
        List<List<Parameter>> variants = new ArrayList<>();
        for (Ctor ctor : Global.typeConstructors(type))
            variants.add(ctor.getParams());
        Parameter parameter = chooseParameter(resource, variants, args, index, offset);
        if (parameter == null)
            return new CallExpectation(new ArrayList<>(), "", typeName); //$NON-NLS-1$
        return new CallExpectation(new ArrayList<>(parameter.getType()), parameterName(parameter), typeName);
    }

    /**
     * {@code ИмяПараметраВызоваМетода}: типы параметра {@code index} в {@code types}; результат — имя
     * параметра (приёмник). Вариант синтаксиса подбирается по типам уже введённых аргументов.
     */
    private static String parameterExpectation(XtextResource resource, Invocation invocation, int index,
        List<TypeItem> types)
    {
        for (FeatureEntry entry : featureEntries(invocation.getMethodAccess()))
        {
            EObject feature = entry != null ? entry.getFeature() : null;
            if (feature instanceof com._1c.g5.v8.dt.mcore.Method method && !feature.eIsProxy())
            {
                List<List<Parameter>> variants = new ArrayList<>();
                for (ParamSet set : method.getParamSet())
                    variants.add(set.getParams());
                Parameter parameter = chooseParameter(resource, variants, invocation.getParams(), index, -1);
                if (parameter != null)
                {
                    types.addAll(parameter.getType());
                    return parameterName(parameter);
                }
            }
            else if (feature instanceof com._1c.g5.v8.dt.bsl.model.Method method && !feature.eIsProxy()
                && method.getFormalParams().size() > index)
            {
                FormalParam parameter = method.getFormalParams().get(index);
                ICompositeNode node = NodeModelUtils.findActualNodeFor(parameter);
                types.addAll(variableTypeItemsAt(parameter, node != null ? node.getEndOffset() : 0,
                    method.environments()));
                return nullToEmpty(parameter.getName());
            }
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * {@code ПодобратьВариантСинтаксисаМетодаПоТипамПараметров}: вариант, у которого тип одного из уже
     * введённых аргументов совпал с типом параметра; не подобран — последний вариант с таким параметром
     * (как цикл по вариантам в ИР).
     */
    private static Parameter chooseParameter(XtextResource resource, List<List<Parameter>> variants,
        List<Expression> args, int index, int offset)
    {
        List<List<Parameter>> withIndex = new ArrayList<>();
        for (List<Parameter> variant : variants)
        {
            if (variant.size() > index)
                withIndex.add(variant);
        }
        if (withIndex.isEmpty())
            return null;
        if (withIndex.size() > 1)
        {
            for (int i = 0; i < Math.min(args.size(), index); i++)
            {
                Expression arg = args.get(i);
                if (arg == null)
                    continue;
                List<String> actual = typeNames(offset >= 0 ? expressionTypeItems(resource, arg, offset) : arg.getTypes());
                if (actual.isEmpty())
                    continue;
                for (List<Parameter> variant : withIndex)
                {
                    for (String formal : typeNames(variant.get(i).getType()))
                    {
                        if (containsIgnoreCase(actual, formal))
                            return variant.get(index);
                    }
                }
            }
        }
        return withIndex.get(withIndex.size() - 1).get(index);
    }

    /** БСП: параметр метода, вызываемого по имени {@code Модуль.Метод} (или метода этого модуля). */
    private static CallExpectation executedMethodExpectation(XtextResource resource, String fullName, int index,
        ICompletionProposal[] list, int offset)
    {
        if (fullName.isEmpty() || index < 0)
            return null;
        int dot = fullName.lastIndexOf('.');
        String methodName = dot >= 0 ? fullName.substring(dot + 1) : fullName;
        if (dot < 0)
        {
            Module module = !resource.getContents().isEmpty() && resource.getContents().get(0) instanceof Module m
                ? m : null;
            if (module == null)
                return null;
            for (com._1c.g5.v8.dt.bsl.model.Method method : module.allMethods())
            {
                if (methodName.equalsIgnoreCase(method.getName()) && method.getFormalParams().size() > index)
                {
                    FormalParam parameter = method.getFormalParams().get(index);
                    ICompositeNode node = NodeModelUtils.findActualNodeFor(parameter);
                    return new CallExpectation(variableTypeItemsAt(parameter, node != null ? node.getEndOffset() : 0,
                        method.environments()), nullToEmpty(parameter.getName()), methodName);
                }
            }
            return null;
        }
        String moduleName = fullName.substring(0, dot);
        for (ICompletionProposal proposal : list)
        {
            if (!moduleName.equalsIgnoreCase(SmartContentAssistProcessor.filterMatchName(proposal)))
                continue;
            ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
            if (!(raw instanceof ConfigurableCompletionProposal configurable)
                || !(Global.getField(configurable, "additionalProposalInfo") instanceof Property property)) //$NON-NLS-1$
                continue;
            for (TypeItem item : property.getTypes())
            {
                Type type = resolveType(resource, item);
                ContextDef def = type != null ? type.getContextDef() : null;
                if (def == null)
                    continue;
                for (com._1c.g5.v8.dt.mcore.Method method : def.allMethods())
                {
                    if (!methodName.equalsIgnoreCase(method.getName()) && !methodName.equalsIgnoreCase(method.getNameRu()))
                        continue;
                    List<List<Parameter>> variants = new ArrayList<>();
                    for (ParamSet set : method.getParamSet())
                        variants.add(set.getParams());
                    Parameter parameter = chooseParameter(resource, variants, List.of(), index, offset);
                    if (parameter != null)
                        return new CallExpectation(new ArrayList<>(parameter.getType()), parameterName(parameter),
                            methodName);
                }
            }
        }
        return null;
    }

    private static boolean isPlatformMethod(FeatureAccess access)
    {
        for (FeatureEntry entry : featureEntries(access))
        {
            EObject feature = entry != null ? entry.getFeature() : null;
            if (feature instanceof com._1c.g5.v8.dt.bsl.model.Method || feature instanceof BslContextDefMethod)
                return false;
        }
        return true;
    }

    private static List<FeatureEntry> featureEntries(FeatureAccess access)
    {
        if (access instanceof StaticFeatureAccess staticAccess)
            return staticAccess.getFeatureEntries();
        if (access instanceof DynamicFeatureAccess dynamic)
            return dynamic.getFeatureEntries();
        return List.of();
    }

    private static String parameterName(Parameter parameter)
    {
        String name = parameter.getNameRu();
        return name != null && !name.isEmpty() ? name : nullToEmpty(parameter.getName());
    }

    /** Типы выражения {@code text} в {@code [start, end)}: переменная — по состоянию в точке, иначе — тип узла AST. */
    private static List<TypeItem> expressionTypeItems(XtextResource resource, int start, int end, String text)
    {
        Global.tempLog("expected-receiver-type", "expression text=" + text + " start=" + start + " end=" + end); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (IDENTIFIER.matcher(text).matches())
        {
            Variable variable = findVariable(resource, text, start);
            if (variable != null)
            {
                List<TypeItem> types = variableTypeItemsAt(variable, end, environmentsOf(resource, variable));
                Global.tempLog("expected-receiver-type", "variable name=" + variable.getName() + " types=" + typeNames(types)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                if (!types.isEmpty())
                    return types;
            }
        }
        Expression expression = findExpression(resource, start, end);
        Global.tempLog("expected-receiver-type", "node=" + (expression == null ? "null" : expression.eClass().getName()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " text=" + (expression == null ? "" : expressionText(expression)) //$NON-NLS-1$ //$NON-NLS-2$
            + " types=" + (expression == null ? List.of() : typeNames(expression.getTypes()))); //$NON-NLS-1$
        if (expression == null)
            return new ArrayList<>();
        return expressionTypeItems(resource, expression, end);
    }

    /**
     * Типы обращения, у узла которого EDT типов не проставила ({@code стр.Колонка = |}): типы самого
     * свойства (колонки, реквизита), а у переменной — её состояние в точке.
     */
    private static List<TypeItem> featureTypes(XtextResource resource, Expression expression, int offset)
    {
        List<TypeItem> result = new ArrayList<>();
        Global.tempLog("expected-receiver-type", "featureTypes node=" + expression.eClass().getName() + " offset=" + offset); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!(expression instanceof FeatureAccess access))
            return result;
        List<FeatureEntry> entries = featureEntries(access);
        if (entries.isEmpty() && access instanceof DynamicFeatureAccess)
        {
            var provider = resource.getResourceServiceProvider();
            DynamicFeatureAccessComputer computer = provider != null
                ? provider.get(DynamicFeatureAccessComputer.class) : null;
            if (computer != null)
                entries = DynamicFeatureAccessComputer.resolveDynamicFeatureAccess(access, computer);
            Global.tempLog("expected-receiver-type", "resolveDynamic computer=" + (computer != null) //$NON-NLS-1$ //$NON-NLS-2$
                + " entries=" + entries.size()); //$NON-NLS-1$
        }
        for (FeatureEntry entry : entries)
        {
            EObject feature = entry != null ? entry.getFeature() : null;
            Global.tempLog("expected-receiver-type", "feature=" + (feature == null ? "null" : feature.eClass().getName()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " proxy=" + (feature != null && feature.eIsProxy()) //$NON-NLS-1$
                + " propertyTypes=" + (feature instanceof Property property ? typeNames(property.getTypes()) : List.of())); //$NON-NLS-1$
            if (feature instanceof Property property && !property.eIsProxy())
                result.addAll(property.getTypes());
            else if (feature instanceof Variable variable)
                result.addAll(variableTypeItemsAt(variable, offset, environmentsOf(resource, variable)));
        }
        Global.tempLog("expected-receiver-type", "featureTypes result=" + typeNames(result)); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    /** Типы выражения AST: переменная — по состоянию в точке {@code offset}, иначе — тип узла. */
    private static List<TypeItem> expressionTypeItems(XtextResource resource, Expression expression, int offset)
    {
        if (expression instanceof StaticFeatureAccess access)
        {
            for (FeatureEntry entry : access.getFeatureEntries())
            {
                if (entry != null && entry.getFeature() instanceof Variable variable)
                {
                    List<TypeItem> types = variableTypeItemsAt(variable, offset, environmentsOf(resource, variable));
                    if (!types.isEmpty())
                        return types;
                }
            }
        }
        if (!expression.getTypes().isEmpty())
            return new ArrayList<>(expression.getTypes());
        List<TypeItem> result = featureTypes(resource, expression, offset);
        if (!result.isEmpty())
            return result;
        // У незавершённого сравнения тип узла может ещё отсутствовать. Как в других
        // читателях типов плагина, запрашиваем его у штатного вычислителя EDT.
        var provider = resource.getResourceServiceProvider();
        TypesComputer computer = provider != null ? provider.get(TypesComputer.class) : null;
        com._1c.g5.v8.dt.bsl.model.Method method = EcoreUtil2.getContainerOfType(expression,
            com._1c.g5.v8.dt.bsl.model.Method.class);
        Environments environments = method != null ? method.environments() : moduleEnvironments(resource);
        if (computer != null && environments != null)
        {
            List<TypeItem> computed = computer.computeTypes(expression, environments);
            if (computed != null)
                result.addAll(computed);
        }
        Global.tempLog("expected-receiver-type", "compute node=" + expression.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
            + " text=" + expressionText(expression) + " types=" + typeNames(result)); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    /** Типы переменной выражения {@code expression} в конце её метода (для ключей структуры). */
    private static List<TypeItem> variableTypesAtMethodEnd(XtextResource resource, Expression expression)
    {
        if (!(expression instanceof StaticFeatureAccess access))
            return List.of();
        for (FeatureEntry entry : access.getFeatureEntries())
        {
            if (entry != null && entry.getFeature() instanceof Variable variable)
            {
                com._1c.g5.v8.dt.bsl.model.Method method = EcoreUtil2.getContainerOfType(variable,
                    com._1c.g5.v8.dt.bsl.model.Method.class);
                ICompositeNode node = method != null ? NodeModelUtils.findActualNodeFor(method) : null;
                if (node != null)
                    return variableTypeItemsAt(variable, node.getEndOffset() - 1, method.environments());
            }
        }
        return List.of();
    }

    /** {@code ПеременнаяКонструктора}: типы переменной, которой присваивается {@code Новый Структура(…)}. */
    private static List<TypeItem> constructedVariableTypes(XtextResource resource, OperatorStyleCreator creator)
    {
        List<TypeItem> result = new ArrayList<>(creator.getTypes());
        SimpleStatement statement = EcoreUtil2.getContainerOfType(creator, SimpleStatement.class);
        if (statement != null && statement.getRight() == creator && statement.getLeft() != null)
            result.addAll(variableTypesAtMethodEnd(resource, statement.getLeft()));
        return result;
    }

    /** Типы свойства {@code name} у типов {@code owners}. */
    private static List<TypeItem> propertyTypes(XtextResource resource, List<TypeItem> owners, String name)
    {
        List<TypeItem> result = new ArrayList<>();
        for (TypeItem owner : owners)
        {
            Type type = resolveType(resource, owner);
            ContextDef def = type != null ? type.getContextDef() : null;
            if (def == null)
                continue;
            for (Property property : def.allProperties())
            {
                if (name.equalsIgnoreCase(property.getName()) || name.equalsIgnoreCase(property.getNameRu()))
                    result.addAll(property.getTypes());
            }
        }
        return result;
    }

    /** Типы элементов коллекций {@code owners}. */
    private static List<TypeItem> elementTypes(XtextResource resource, List<TypeItem> owners)
    {
        List<TypeItem> result = new ArrayList<>();
        for (TypeItem owner : owners)
        {
            Type type = resolveType(resource, owner);
            TypeContainer elements = type != null ? type.getCollectionElementTypes() : null;
            if (elements != null)
                result.addAll(elements.allTypes());
        }
        return result;
    }

    private static boolean hasMethod(XtextResource resource, TypeItem owner, String name)
    {
        Type type = resolveType(resource, owner);
        ContextDef def = type != null ? type.getContextDef() : null;
        if (def == null)
            return false;
        for (com._1c.g5.v8.dt.mcore.Method method : def.allMethods())
        {
            if (name.equalsIgnoreCase(method.getNameRu()) || name.equalsIgnoreCase(method.getName()))
                return true;
        }
        return false;
    }

    private static boolean isTableType(List<TypeItem> types)
    {
        for (String name : typeNames(types))
        {
            String lowered = lower(name);
            if (lowered.contains("таблицазначений") || lowered.contains("табличнаячасть")) //$NON-NLS-1$ //$NON-NLS-2$
                return true;
        }
        return false;
    }

    private static boolean hasSystemEnum(List<TypeItem> types)
    {
        for (TypeItem item : types)
        {
            EObject loaded = item != null && item.eIsProxy() && READ_RESOURCE.get() != null
                ? EcoreUtil.resolve(item, READ_RESOURCE.get()) : item;
            if (loaded instanceof Type type && !type.eIsProxy() && type.isSysEnum())
                return true;
        }
        return false;
    }

    private static Type resolveType(XtextResource resource, TypeItem item)
    {
        if (item == null)
            return null;
        EObject resolved = item.eIsProxy() ? EcoreUtil.resolve(item, resource) : item;
        return resolved instanceof Type type && !type.eIsProxy() ? type : null;
    }

    /** Типы результата функции у «Возврат»: типы выражений остальных её «Возврат». */
    private static List<TypeItem> returnTypes(XtextResource resource, int offset)
    {
        List<TypeItem> result = new ArrayList<>();
        com._1c.g5.v8.dt.bsl.model.Method method = methodAt(resource, offset);
        if (!(method instanceof com._1c.g5.v8.dt.bsl.model.Function))
            return result;
        for (ReturnStatement statement : EcoreUtil2.getAllContentsOfType(method, ReturnStatement.class))
        {
            ICompositeNode node = NodeModelUtils.findActualNodeFor(statement);
            if (node != null && node.getOffset() <= offset && offset <= node.getEndOffset())
                continue;
            if (statement.getExpression() != null)
                result.addAll(statement.getExpression().getTypes());
        }
        return result;
    }

    /**
     * Приёмник — системное свойство ({@code ЛиСистемноеСлово}): {@code имя@тип владельца} в нижнем
     * регистре; иначе {@code null}.
     */
    private static String systemProperty(XtextResource resource, int start, int end, String text)
    {
        Expression expression = findExpression(resource, start, end);
        if (!(expression instanceof FeatureAccess access))
            return null;
        String name = lastFragment(text);
        for (FeatureEntry entry : featureEntries(access))
        {
            EObject feature = entry != null ? entry.getFeature() : null;
            if (!(feature instanceof Property property) || feature.eIsProxy())
                continue;
            Type owner = EcoreUtil2.getContainerOfType(property, Type.class);
            if (owner != null)
                return lower(name) + "@" + lower(typeName(owner)); //$NON-NLS-1$
        }
        return null;
    }

    private static Environments environmentsOf(XtextResource resource, Variable variable)
    {
        com._1c.g5.v8.dt.bsl.model.Method method = EcoreUtil2.getContainerOfType(variable,
            com._1c.g5.v8.dt.bsl.model.Method.class);
        return method != null ? method.environments() : moduleEnvironments(resource);
    }

    private static Environments moduleEnvironments(XtextResource resource)
    {
        return !resource.getContents().isEmpty() && resource.getContents().get(0) instanceof Module module
            ? module.environments() : null;
    }

    private static com._1c.g5.v8.dt.bsl.model.Method methodAt(XtextResource resource, int offset)
    {
        if (resource.getParseResult() == null)
            return null;
        INode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset);
        if (leaf == null && offset > 0)
            leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset - 1);
        EObject semantic = leaf != null ? leaf.getSemanticElement() : null;
        return semantic != null ? EcoreUtil2.getContainerOfType(semantic, com._1c.g5.v8.dt.bsl.model.Method.class)
            : null;
    }

    /** Переменная с именем {@code name} метода, в котором стоит {@code offset}, или модуля. */
    private static Variable findVariable(XtextResource resource, String name, int offset)
    {
        EObject container = methodAt(resource, offset);
        if (container == null && !resource.getContents().isEmpty())
            container = resource.getContents().get(0);
        if (container == null)
            return null;
        for (var contents = container.eAllContents(); contents.hasNext();)
        {
            EObject candidate = contents.next();
            if (candidate instanceof Variable variable && name.equalsIgnoreCase(variable.getName()))
                return variable;
        }
        return null;
    }

    /** Полное выражение перед оператором: вызов, литерал, скобки или составное выражение. */
    private static Expression findExpressionEndingAt(XtextResource resource, int end)
    {
        Expression result = null;
        if (resource.getParseResult() == null || end <= 0)
            return null;
        INode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), end - 1);
        int start = end;
        for (INode node = leaf; node != null; node = node.getParent())
        {
            if (node.getSemanticElement() instanceof Expression expression)
            {
                ICompositeNode actual = NodeModelUtils.findActualNodeFor(expression);
                if (actual != null && actual.getEndOffset() == end && actual.getOffset() < start)
                {
                    result = expression;
                    start = actual.getOffset();
                }
            }
        }
        Global.tempLog("expected-receiver-type", "beforeOperator end=" + end //$NON-NLS-1$ //$NON-NLS-2$
            + " node=" + (result == null ? "null" : result.eClass().getName()) //$NON-NLS-1$ //$NON-NLS-2$
            + " text=" + (result == null ? "" : expressionText(result))); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    private static Expression findExpression(XtextResource resource, int start, int end)
    {
        if (resource.getParseResult() == null || end <= start)
            return null;
        INode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), end - 1);
        for (INode node = leaf; node != null; node = node.getParent())
        {
            if (node.getOffset() < start)
                break;
            if (node.getSemanticElement() instanceof Expression expression)
            {
                ICompositeNode actual = NodeModelUtils.findActualNodeFor(expression);
                if (actual != null && actual.getOffset() == start && actual.getEndOffset() == end)
                    return expression;
            }
        }
        return null;
    }

    private static String expressionText(Expression expression)
    {
        ICompositeNode node = NodeModelUtils.findActualNodeFor(expression);
        String text = node != null ? NodeModelUtils.getTokenText(node) : null;
        return text != null ? text.trim() : ""; //$NON-NLS-1$
    }

    /** {@code ТекстИзВстроенногоЯзыкаЛкс}: значение строкового литерала; не литерал — пусто. */
    private static String literalText(Expression expression)
    {
        String text = expression != null ? expressionText(expression) : ""; //$NON-NLS-1$
        if (text.length() < 2 || text.charAt(0) != '"' || text.charAt(text.length() - 1) != '"')
            return ""; //$NON-NLS-1$
        return text.substring(1, text.length() - 1).replace("\"\"", "\"").trim(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Готовый тип переменной в точке — тем же вызовом, что боковая подсказка EDT. */
    private static List<TypeItem> variableTypeItemsAt(Variable variable, int offset, Environments environments)
    {
        if (environments == null)
            return new ArrayList<>();
        try
        {
            return new ArrayList<>(TypeSystemUtil.getVariableTypesAtOffset(variable, offset, environments));
        }
        catch (RuntimeException e)
        {
            return new ArrayList<>();
        }
    }

    /** Типы переменных списка (строки EDT с {@code FantomVariableOccurrence}) в точке списка. */
    private static void collectVariableTypes(XtextResource resource, Context context, ICompletionProposal[] list,
        Map<String, List<String>> result)
    {
        for (ICompletionProposal proposal : list)
        {
            ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
            if (!(raw instanceof ConfigurableCompletionProposal configurable))
                continue;
            FunctionReturnTypes.VariableTarget target = FunctionReturnTypes.variableTargetOf(
                Global.getField(configurable, "additionalProposalInfo")); //$NON-NLS-1$
            if (target == null || target.fragment() == null)
                continue;
            String key = lower(target.name());
            if (result.containsKey(key))
                continue;
            EObject object = resource.getEObject(target.fragment());
            if (!(object instanceof Variable variable))
                continue;
            result.put(key, typeNames(variableTypeItemsAt(variable, context.start(), environmentsOf(resource, variable))));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Сведения модели для новых слов
    // ------------------------------------------------------------------------------------------

    /** {@link Facts} места; {@code null} — после {@code Новый} (список типов ранжирует EDT). */
    private static Facts facts(XtextResource resource, Context context, Expectation expectation,
        ParamHintHtmlModifier.CallSiteInfo site, List<TypeItem> parentItems)
    {
        String preceding = stripEnd(context.preceding());
        if (endsWithWord(preceding, "Новый")) //$NON-NLS-1$
            return null;
        IDocument document = context.document();
        Module module = !resource.getContents().isEmpty() && resource.getContents().get(0) instanceof Module m
            ? m : null;
        com._1c.g5.v8.dt.bsl.model.Method method = methodAt(resource, context.start());
        Type parentType = parentItems.isEmpty() ? null : resolveType(resource, parentItems.get(0));
        String parentName = parentType != null ? lower(typeName(parentType)) : ""; //$NON-NLS-1$
        TypeContainer elements = parentType != null ? parentType.getCollectionElementTypes() : null;
        // Индекс по ключу (соответствие, структура) — не числовой
        boolean parentIndexed = parentType != null && parentType.isIndexAccessible()
            && !parentName.endsWith("соответствие") && !parentName.endsWith("структура"); //$NON-NLS-1$ //$NON-NLS-2$
        boolean parentSystemEnum = parentType != null && (parentType.isSysEnum()
            || parentName.startsWith("перечисление") && parentName.indexOf('.') < 0); //$NON-NLS-1$
        Set<String> constructorTypes = new HashSet<>();
        Set<String> objectPropertyEnums = new HashSet<>();
        if (expectation != null)
        {
            for (String name : receiverTypes(expectation))
            {
                String clean = firstFragment(firstFragment(name, '('), '[');
                // ЕстьКонструктор
                if (!Global.typeConstructors(platformType(resource, clean)).isEmpty())
                    constructorTypes.add(lower(clean));
            }
            if (expectation.systemEnum())
                objectPropertyEnums.addAll(objectPropertyEnums(resource));
        }
        List<String> nestedTypes = new ArrayList<>();
        Matcher typeOf = TYPE_OF.matcher(preceding);
        if (typeOf.find())
        {
            int start = context.precedingStart() + typeOf.start(1);
            nestedTypes.addAll(typeNames(expressionTypeItems(resource, start, start + typeOf.group(1).length(),
                typeOf.group(1))));
        }
        Environments environments = method != null ? method.environments() : null;
        boolean formClient = module != null && module.getModuleType() == ModuleType.FORM_MODULE
            && environments != null && environments.contains(Environment.MNG_CLIENT);
        boolean structureConstructor = site != null && site.owner instanceof OperatorStyleCreator creator
            && STRUCTURE.equalsIgnoreCase(typeName(resolveType(resource, creator.getType())));
        // Типы переменных нужны только <ЗаполнитьСвойства> (строка из одного «Родитель.»)
        boolean fillProperties = false;
        try
        {
            int lineOffset = document.getLineInformationOfOffset(context.anchor()).getOffset();
            fillProperties = !context.parent().isEmpty()
                && document.get(lineOffset, context.anchor() - lineOffset).strip().equals(context.parent() + "."); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            fillProperties = false;
        }
        List<Local> locals = locals(resource, module, method, context.start(), fillProperties);
        List<Local> receiverProperties = new ArrayList<>();
        if (expectation != null && "=".equals(expectation.token()) && expectation.receiver().indexOf('.') > 0 //$NON-NLS-1$
            && !context.parent().isEmpty())
            receiverProperties = receiverProperties(resource, context, expectation);
        List<String> selectionTypes = new ArrayList<>();
        if (!context.selected().isBlank())
        {
            String text = context.selected();
            int lead = text.length() - text.stripLeading().length();
            int start = context.caret() + lead;
            String trimmed = text.strip();
            selectionTypes.addAll(typeNames(expressionTypeItems(resource, start, start + trimmed.length(), trimmed)));
        }
        List<String> queryTotals = "РезультатЗапроса".equals(typeName(parentType)) //$NON-NLS-1$
            ? BslQueryResultTypes.totalColumnsOf(findExpression(resource, context.start(), context.anchor() - 1))
            : null;
        return new Facts(method != null ? bodyStartLine(document, method) : -1, typeNames(parentItems),
            elements != null && !elements.allTypes().isEmpty(), parentIndexed, parentSystemEnum, constructorTypes,
            nestedTypes, objectPropertyEnums, formClient, structureConstructor, locals, receiverProperties,
            selectionTypes, queryTotals, method == null, module != null ? module.getModuleType() : null);
    }

    /** {@code мНомерПервойСтрокиТелаМетода}: строка после закрывающей скобки параметров (с 0); {@code -1} — нет. */
    private static int bodyStartLine(IDocument document, com._1c.g5.v8.dt.bsl.model.Method method)
    {
        ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
        if (node == null)
            return -1;
        try
        {
            int from = node.getOffset();
            List<FormalParam> params = method.getFormalParams();
            ICompositeNode last = params.isEmpty() ? null : NodeModelUtils.findActualNodeFor(params.get(params.size() - 1));
            if (last != null)
                from = last.getEndOffset();
            else
            {
                int open = document.get(node.getOffset(), node.getLength()).indexOf('(');
                if (open >= 0)
                    from = node.getOffset() + open + 1;
            }
            int close = document.get(from, node.getEndOffset() - from).indexOf(')');
            return close >= 0 ? document.getLineOfOffset(from + close) + 1 : -1;
        }
        catch (Exception e)
        {
            return -1;
        }
    }

    /**
     * Переменные метода (параметры, {@code Перем}, неявные) и модуля; {@code visible} — определена до
     * {@code offset}. Типы — в точке {@code offset}, только при {@code withTypes} и для видимых.
     */
    private static List<Local> locals(XtextResource resource, Module module, com._1c.g5.v8.dt.bsl.model.Method method,
        int offset, boolean withTypes)
    {
        List<Local> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Variable> variables = new ArrayList<>();
        Map<Variable, Boolean> visible = new java.util.IdentityHashMap<>();
        if (method != null)
        {
            for (FormalParam param : method.getFormalParams())
                visible.put(param, Boolean.TRUE);
            variables.addAll(method.getFormalParams());
            for (DeclareStatement statement : method.allDeclareStatements())
            {
                for (ExplicitVariable variable : statement.getVariables())
                {
                    variables.add(variable);
                    visible.put(variable, Boolean.TRUE);
                }
            }
            // Неявная переменная видна, если её первое обращение — до места
            Map<Variable, Integer> firstAccess = new java.util.IdentityHashMap<>();
            for (StaticFeatureAccess access : EcoreUtil2.getAllContentsOfType(method, StaticFeatureAccess.class))
            {
                ImplicitVariable variable = access.getImplicitVariable();
                ICompositeNode node = variable != null ? NodeModelUtils.findActualNodeFor(access) : null;
                if (node != null)
                    firstAccess.merge(variable, Integer.valueOf(node.getOffset()), Math::min);
            }
            for (ImplicitVariable variable : method.getImplicitVariables())
            {
                variables.add(variable);
                Integer first = firstAccess.get(variable);
                visible.put(variable, Boolean.valueOf(first != null && first.intValue() < offset));
            }
        }
        if (module != null)
        {
            for (DeclareStatement statement : module.getDeclareStatements())
            {
                for (ExplicitVariable variable : statement.getVariables())
                {
                    variables.add(variable);
                    visible.put(variable, Boolean.TRUE);
                }
            }
        }
        for (Variable variable : variables)
        {
            String name = variable.getName();
            if (name == null || name.isEmpty() || !seen.add(lower(name)))
                continue;
            boolean isVisible = Boolean.TRUE.equals(visible.get(variable));
            List<String> types = withTypes && isVisible
                ? typeNames(variableTypeItemsAt(variable, offset, environmentsOf(resource, variable))) : List.of();
            result.add(new Local(name, types, isVisible));
        }
        return result;
    }

    /** Свойства объекта {@code ИмяЛевогоОбъекта} приёмника {@code Объект.Свойство =}; {@code visible} — запись разрешена. */
    private static List<Local> receiverProperties(XtextResource resource, Context context, Expectation expectation)
    {
        List<Local> result = new ArrayList<>();
        String preceding = stripEnd(context.preceding());
        String token = expectation.token();
        if (!preceding.endsWith(token))
            return result;
        String before = stripEnd(preceding.substring(0, preceding.length() - token.length()));
        String receiver = expectation.receiver();
        if (!before.endsWith(receiver))
            return result;
        String object = firstFragment(receiver, '.');
        int start = context.precedingStart() + before.length() - receiver.length();
        List<TypeItem> types = expressionTypeItems(resource, start, start + object.length(), object);
        Type type = types.isEmpty() ? null : resolveType(resource, types.get(0));
        ContextDef def = type != null ? type.getContextDef() : null;
        if (def == null)
            return result;
        Set<String> seen = new HashSet<>();
        for (Property property : def.allProperties())
        {
            String name = property.getNameRu() != null && !property.getNameRu().isEmpty() ? property.getNameRu()
                : property.getName();
            if (name != null && seen.add(lower(name)))
                result.add(new Local(name, typeNames(property.getTypes()), property.isWritable()));
        }
        return result;
    }

    /** {@code ПеречислимыеСвойстваОбъектовМетаданных}: перечисления {@code Метаданные.СвойстваОбъектов}. */
    private static Set<String> objectPropertyEnums(XtextResource resource)
    {
        Set<String> result = new HashSet<>();
        Type configuration = platformType(resource, "ОбъектМетаданныхКонфигурация"); //$NON-NLS-1$
        if (configuration != null)
        {
            for (TypeItem item : propertyTypes(resource, List.of(configuration), "СвойстваОбъектов")) //$NON-NLS-1$
            {
                Type type = resolveType(resource, item);
                ContextDef def = type != null ? type.getContextDef() : null;
                if (def == null)
                    continue;
                for (Property property : def.allProperties())
                {
                    if (property.getNameRu() != null)
                        result.add(lower(property.getNameRu()));
                    if (property.getName() != null)
                        result.add(lower(property.getName()));
                }
            }
        }
        Global.tempLog(LOG, "objectPropertyEnums configuration=" + (configuration != null) + " count=" + result.size()); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    /** Платформенный тип по имени (версия платформы проекта); {@code null} — нет. */
    private static Type platformType(XtextResource resource, String name)
    {
        if (name == null || name.isEmpty())
            return null;
        try
        {
            IRuntimeVersionSupport versions = resource.getResourceServiceProvider() != null
                ? resource.getResourceServiceProvider().get(IRuntimeVersionSupport.class) : null;
            Version version = versions != null && !resource.getContents().isEmpty()
                ? versions.getRuntimeVersionOrDefault(resource.getContents().get(0), Version.LATEST) : Version.LATEST;
            IEObjectProvider provider = IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
            var description = provider != null ? provider.getEObjectDescription(name) : null;
            EObject object = description != null ? description.getEObjectOrProxy() : null;
            if (object != null && object.eIsProxy())
                object = EcoreUtil.resolve(object, resource);
            return object instanceof Type type && !type.eIsProxy() ? type : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Контекст статистики, «внутри выражения», частота членов
    // ------------------------------------------------------------------------------------------

    /** {@code ТипКонтекстаСтатистики}: без родителя — {@value #GLOBAL_CONTEXT}, иначе тип родителя. */
    private static String statisticsContext(Context context, List<String> names)
    {
        if (context.parent().isEmpty())
            return GLOBAL_CONTEXT;
        if (names.isEmpty())
            return null;
        String name = names.get(0);
        // Конкретный тип с метаданными — полностью, иначе до пробела
        return name.indexOf('.') >= 0 || name.indexOf('[') >= 0 ? name : firstFragment(name, ' ');
    }

    /** {@code ЛиВнутриВыражения} для встроенного языка. */
    private static boolean insideExpression(Context context, ParamHintHtmlModifier.CallSiteInfo site)
    {
        String preceding = context.preceding();
        String line = preceding.substring(preceding.lastIndexOf('\n') + 1);
        int comment = line.indexOf("//"); //$NON-NLS-1$
        if (comment >= 0)
            return false;
        String trimmed = stripEnd(line);
        if (trimmed.trim().startsWith("#")) //$NON-NLS-1$
            return false;
        char previous = trimmed.isEmpty() ? 0 : trimmed.charAt(trimmed.length() - 1);
        return previous == '(' || previous == ',' || previous == '+' || previous == '-' || previous == '='
            || site != null
            || !trimmed.trim().isEmpty()
            || endsWithWord(trimmed, "Если") || endsWithWord(trimmed, "Пока"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Частота {@code Родитель.Член} в тексте метода ({@code ЗаполнитьТаблицуСловИзТекста} с родительским
     * контекстом), без комментариев, строк и вхождения у каретки.
     */
    private static Map<String, Integer> memberFrequencies(XtextResource resource, Context context)
    {
        Map<String, Integer> result = new HashMap<>();
        com._1c.g5.v8.dt.bsl.model.Method method = methodAt(resource, context.start());
        ICompositeNode node = method != null ? NodeModelUtils.findActualNodeFor(method) : null;
        if (node == null)
            return result;
        String text;
        try
        {
            text = context.document().get(node.getOffset(), node.getLength());
        }
        catch (Exception e)
        {
            return result;
        }
        String clean = withoutCommentsAndStrings(text);
        Matcher matcher = Pattern.compile("(?<![\\p{L}\\p{N}_.])" + Pattern.quote(context.parent()) //$NON-NLS-1$
            + "\\.([\\p{L}_][\\p{L}\\p{N}_]*)(\\s*\\()?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(clean); //$NON-NLS-1$
        while (matcher.find())
            result.merge((matcher.group(2) != null ? "m:" : "p:") + lower(matcher.group(1)), 1, Integer::sum); //$NON-NLS-1$ //$NON-NLS-2$
        // Само вводимое слово не считается
        try
        {
            IDocument document = context.document();
            int end = context.anchor();
            while (end < document.getLength() && SmartContentAssistProcessor.isFilterChar(document.getChar(end)))
                end++;
            if (end > context.anchor())
            {
                int next = end;
                while (next < document.getLength() && Character.isWhitespace(document.getChar(next)))
                    next++;
                boolean method1 = next < document.getLength() && document.getChar(next) == '(';
                String key = (method1 ? "m:" : "p:") + lower(document.get(context.anchor(), end - context.anchor())); //$NON-NLS-1$ //$NON-NLS-2$
                result.computeIfPresent(key, (k, count) -> count.intValue() > 1 ? Integer.valueOf(count.intValue() - 1) : null);
            }
        }
        catch (Exception ignored)
        {
            // частота без поправки
        }
        return result;
    }

    /** Комментарии, строковые литералы и {@code Новый Имя} заменяются пробелами (длина сохраняется). */
    private static String withoutCommentsAndStrings(String text)
    {
        StringBuilder result = new StringBuilder(text);
        int i = 0;
        while (i < text.length())
        {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/')
            {
                while (i < text.length() && text.charAt(i) != '\n')
                    result.setCharAt(i++, ' ');
            }
            else if (c == '"')
            {
                result.setCharAt(i++, ' ');
                while (i < text.length())
                {
                    char s = text.charAt(i);
                    result.setCharAt(i, s == '\n' ? '\n' : ' ');
                    i++;
                    if (s == '"')
                    {
                        if (i < text.length() && text.charAt(i) == '"')
                        {
                            result.setCharAt(i++, ' ');
                            continue;
                        }
                        break;
                    }
                }
            }
            else
                i++;
        }
        Matcher creator = Pattern.compile("(?iu)(?:Новый|New)\\s+[\\p{L}_][\\p{L}\\p{N}_]*").matcher(result); //$NON-NLS-1$
        while (creator.find())
        {
            for (int k = creator.start(); k < creator.end(); k++)
                result.setCharAt(k, ' ');
        }
        return result.toString();
    }

    // ------------------------------------------------------------------------------------------
    // ПрименитьОжидаемыйТипВТаблицеСлов, частота, статистика выбора
    // ------------------------------------------------------------------------------------------

    /** @param added новые слова места ({@link AddedWords}) по ключу — заполняется */
    private static Premiums premiums(Context context, Scene scene, IProject project, ICompletionProposal[] list,
        Map<String, List<String>> variableTypes, List<FuzzyCall> fuzzyCalls, Map<String, BslAddedWordProposal> added)
    {
        Premiums result = Premiums.empty();
        Map<String, Word> words = words(list, variableTypes, scene.predefinedTypes());
        AddedWords adder = scene.facts() != null ? new AddedWords(result, words, added, context, scene, list, fuzzyCalls)
            : null;
        if (adder != null)
        {
            adder.surroundingConstructions();
            // Применяется редко, а по типу (возвращает Тип) поднимается высоко — штраф (решение пользователя)
            add(result, words, "изxmlтипа", -1, false); //$NON-NLS-1$
        }
        boolean byType = adder == null || adder.run();
        if (scene.expectation() != null && byType)
            typePremiums(result, context, scene.expectation(), words, fuzzyCalls, adder);
        if (!context.parent().isEmpty())
            weightPremiums(result, words, scene.frequencies(), words.size(), true);
        if (scene.statisticsContext() != null)
        {
            if (!GLOBAL_CONTEXT.equals(scene.statisticsContext()))
                weightPremiums(result, words, ChoiceStatistics.weights(project, scene.statisticsContext(),
                    scene.insideExpression(), MODULE_LANGUAGE), words.size(), !context.parent().isEmpty());
            if (context.parent().isEmpty())
                weightPremiums(result, words, ChoiceStatistics.weights(project, GLOBAL_CONTEXT,
                    scene.insideExpression(), MODULE_LANGUAGE), words.size(), false);
        }
        return result;
    }

    /**
     * {@code ПоднятьРейтингиСловИзТаблицыВесов}: +1 словам, чья доля в сумме весов не меньше
     * {@code max(0.01, min(0.5, 4 / число слов))}. Ключи весов — {@code m:}/{@code p:} + имя.
     */
    private static void weightPremiums(Premiums result, Map<String, Word> words, Map<String, Integer> weights,
        int wordCount, boolean autoOpen)
    {
        if (weights.isEmpty())
            return;
        long total = 0;
        int absent = 0;
        for (Map.Entry<String, Integer> weight : weights.entrySet())
        {
            total += weight.getValue().intValue();
            if (!words.containsKey(weight.getKey().substring(2)))
                absent++;
        }
        if (total <= 0)
            return;
        // Слова, найденные в тексте, ИР добавляет в таблицу — они входят в общее число
        double share = Math.max(0.01, Math.min(0.5, 4.0 / Math.max(1, wordCount + absent)));
        for (Map.Entry<String, Integer> weight : weights.entrySet())
        {
            if (weight.getValue().intValue() / (double)total < share)
                continue;
            Word word = words.get(weight.getKey().substring(2));
            if (word != null && word.method() == weight.getKey().startsWith("m:")) //$NON-NLS-1$
                add(result, words, word.key(), 1, autoOpen);
        }
    }

    /**
     * Тип приёмника по его имени ({@code ИмяТипаИзИмениПеременнойЛкс}), когда тип не известен, а приёмник —
     * простое имя слева от {@code =}: {@code null} — подбор не нужен; пусто — не подобрался.
     */
    private static String typeFromReceiverName(Expectation expectation)
    {
        String lastWord = lastFragment(expectation.receiver());
        String expectedType = String.join(", ", expectation.types()); //$NON-NLS-1$
        if (!(expectedType.isEmpty() || UNDEFINED.equalsIgnoreCase(expectedType)
            || "Произвольный".equalsIgnoreCase(expectedType)) //$NON-NLS-1$
            || !"=".equals(expectation.token()) || !IDENTIFIER.matcher(lastWord).matches() //$NON-NLS-1$
            || expectation.receiver().indexOf('(') >= 0)
            return null;
        String byName = TypeNameFromVariableName.resolve(lastWord, false, true, false, NAME_CATALOG);
        return byName != null ? byName : ""; //$NON-NLS-1$
    }

    /**
     * Боковая подсказка списка: первой строкой — {@code Приемник.Тип: <ИмяТипа>}, если у места есть ожидаемый
     * тип. Как раздел описания слова ИР; при словах ИР подсказку строит ИР.
     */
    static Object withReceiverType(Object info)
    {
        Snapshot snapshot = active;
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        if (snapshot == null || processor != null && processor.hasIrProposalsForCurrentContext())
            return info;
        String line = snapshot.scene().receiverLine();
        if (line == null)
            return info;
        // Меню у ссылок на типы — как у типов EDT в подсказке при наведении
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        BslXtextEditor editor = reloader != null ? reloader.getBslEditor() : null;
        if (editor != null)
            line = MetadataTypeMenu.decorate(line, editor.getInternalSourceViewer(), editor);
        // У строки нет описания EDT — документ боковой панели собирается так же, как для описания ИР
        if (info == null)
            return IrBslHoverHtml.mergeHtml("", line); //$NON-NLS-1$
        boolean browserInput = IrBslHoverHtml.isBslBrowserInput(info);
        String html = browserInput ? IrBslHoverHtml.readDisplayHtml(info) : info instanceof String text ? text : null;
        if (html == null)
            return info;
        if (!browserInput && !IrBslHoverHtml.isFullHtmlDocument(html))
            return html.contains(RECEIVER_TYPE_CLASS) ? info : IrBslHoverHtml.mergeHtml("", line + html); //$NON-NLS-1$
        String result = prependReceiverType(html, line);
        if (result.equals(html))
            return info;
        return browserInput ? IrBslHoverHtml.withHtml(info, result) : result;
    }

    /**
     * Та же строка для подсказки при наведении: место — слово, кончающееся в {@code wordEnd}, как будто его
     * набирают. Читает модель — только вне UI-потока (подсказка считается в фоне); иначе {@code null}.
     */
    static String receiverTypeLineAt(IDocument document, int wordEnd)
    {
        if (!(document instanceof IXtextDocument xtextDocument) || wordEnd < 0 || Display.getCurrent() != null)
            return null;
        try
        {
            Context context = contextAt(document, wordEnd);
            if (context == null)
                return null;
            return ContentAssistSessionReloader.readOnlyForContentAssist(xtextDocument, resource ->
            {
                if (resource == null)
                    return null;
                ParamHintHtmlModifier.CallSiteInfo site = ParamHintHtmlModifier.findCallSiteAt(resource,
                    context.start());
                if (site != null && !site.contains(context.start()))
                    site = null;
                READ_RESOURCE.set(resource);
                try
                {
                    return receiverTypeLine(expectation(resource, context, site, new ICompletionProposal[0]), resource);
                }
                finally
                {
                    READ_RESOURCE.remove();
                }
            });
        }
        catch (Exception e)
        {
            Global.tempLog(LOG, "hover receiver error " + e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Строка {@code Приемник.Тип: <ИмяТипа>} (как раздел описания слова ИР); {@code null} — типа нет. Типы —
     * ссылками EDT, как в её подсказках ({@link #edtTypesHtml}). Только под чтением модели {@code resource}.
     */
    private static String receiverTypeLine(Expectation expectation, XtextResource resource)
    {
        List<String> types = receiverTypes(expectation);
        if (types.isEmpty())
            return null;
        String typesHtml = edtTypesHtml(resource, types);
        return "<div class=\"" + RECEIVER_TYPE_CLASS + "\"><b>Приемник.Тип</b>: " //$NON-NLS-1$ //$NON-NLS-2$
            + (typesHtml != null ? typesHtml : escapeHtml(String.join(", ", types))) + "</div>"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Общий набор ожидаемых типов для HTML и подписи окна. */
    private static List<String> receiverTypes(Expectation expectation)
    {
        if (expectation == null)
            return List.of();
        if (!expectation.types().isEmpty())
            return expectation.types();
        String byName = typeFromReceiverName(expectation);
        return byName != null && !byName.isEmpty() ? List.of(byName) : List.of();
    }

    /**
     * Типы ссылками на их страницы — тем же путём, что EDT строит строку типов своих подсказок:
     * {@code BslDocumentationProvider.getReferenceByTypeName} → {@code PageUtil.generatePrefixTypesString}.
     * {@code null} — EDT ссылок не дала.
     */
    private static String edtTypesHtml(XtextResource resource, List<String> typeNames)
    {
        try
        {
            var services = resource.getResourceServiceProvider();
            Class<?> providerClass = Class.forName(
                "com._1c.g5.v8.dt.internal.bsl.ui.documentation.BslDocumentationProvider"); //$NON-NLS-1$
            Object provider = services != null ? services.get(providerClass) : null;
            if (provider == null)
                return null;
            IRuntimeVersionSupport versions = services.get(IRuntimeVersionSupport.class);
            Version version = versions != null && !resource.getContents().isEmpty()
                ? versions.getRuntimeVersionOrDefault(resource.getContents().get(0), Version.LATEST) : Version.LATEST;
            java.lang.reflect.Method byName = providerClass.getMethod("getReferenceByTypeName", String.class, //$NON-NLS-1$
                org.eclipse.emf.ecore.resource.Resource.class, boolean.class, Version.class, boolean.class);
            List<Object> references = new ArrayList<>();
            for (String name : typeNames)
            {
                // Имена типов — русские, как у EDT при русском языке элементов
                Object reference = byName.invoke(provider, name, resource, Boolean.TRUE, version, Boolean.FALSE);
                if (reference != null)
                    references.add(reference);
            }
            if (references.size() != typeNames.size())
                return null;
            Class<?> pageUtil = Class.forName("com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.PageUtil", true, //$NON-NLS-1$
                providerClass.getClassLoader());
            java.lang.reflect.Method prefix = pageUtil.getDeclaredMethod("generatePrefixTypesString", List.class); //$NON-NLS-1$
            prefix.setAccessible(true);
            return prefix.invoke(null, references) instanceof String html && !html.isBlank() ? html : null;
        }
        catch (Exception | LinkageError e)
        {
            Global.tempLog(LOG, "type links error " + e); //$NON-NLS-1$
            return null;
        }
    }

    /** Строка {@code line} первой в теле подсказки; уже есть или {@code line == null} — как было. */
    static String prependReceiverType(String html, String line)
    {
        if (html == null || line == null || html.contains(RECEIVER_TYPE_CLASS))
            return html;
        int body = html.toLowerCase(Locale.ROOT).indexOf("<body"); //$NON-NLS-1$
        int bodyEnd = body >= 0 ? html.indexOf('>', body) : -1;
        return bodyEnd >= 0 ? html.substring(0, bodyEnd + 1) + line + html.substring(bodyEnd + 1) : line + html;
    }

    private static final String RECEIVER_TYPE_CLASS = "comfort-receiver-type"; //$NON-NLS-1$

    private static String escapeHtml(String text)
    {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }

    /** @param adder новые слова встроенного языка; {@code null} — язык запросов, слова не добавляются */
    private static void typePremiums(Premiums result, Context context, Expectation expectation,
        Map<String, Word> words, List<FuzzyCall> fuzzyCalls, AddedWords adder)
    {
        String parent = context.parent();
        boolean noParent = parent.isEmpty();
        String token = expectation.token();
        String receiver = expectation.receiver();
        String lastWord = lastFragment(receiver);
        String lastLower = lower(lastWord);
        boolean receiverIsMethod = receiver.indexOf('(') >= 0;
        boolean comparison = "=".equals(token) || "<>".equals(token); //$NON-NLS-1$ //$NON-NLS-2$
        boolean currentRowAssignment = comparison && "текущаястрока".equals(lastLower); //$NON-NLS-1$
        String byName = typeFromReceiverName(expectation);
        List<String> allTypes = byName == null ? new ArrayList<>(expectation.types())
            // чтобы нестрогий подбор сработал
            : new ArrayList<>(List.of(byName.isEmpty() ? UNDEFINED : byName));
        allTypes.removeIf(type -> "Произвольный".equalsIgnoreCase(type) || "?".equals(type)); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> trimmed = new ArrayList<>(allTypes);
        trimmed.removeIf(UNDEFINED::equalsIgnoreCase);
        // Нужно для конструктора ИсточникДоступныхНастроекКомпоновкиДанных
        for (String unlikely : new String[] { STRING, "Массив", "СписокЗначений" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            if (trimmed.size() > 1)
                trimmed.removeIf(unlikely::equalsIgnoreCase);
        }
        if (FILL_PROPERTY_VALUES.equalsIgnoreCase(expectation.callMethod()) && trimmed.size() > 1)
            trimmed.removeIf(STRUCTURE::equalsIgnoreCase);
        trimmed = new ArrayList<>(new LinkedHashSet<>(trimmed));
        boolean currentElement = expectation.systemProperty() != null
            && expectation.systemProperty().startsWith("текущийэлемент@"); //$NON-NLS-1$
        // Несколько типов — главного нет (кроме системного «ТекущийЭлемент»)
        String mainType = trimmed.size() == 1 || trimmed.size() > 1 && currentElement
            ? firstFragment(trimmed.get(0), '(') : ""; //$NON-NLS-1$
        String dbTable = mainType.replace("Ссылка.", "."); //$NON-NLS-1$ //$NON-NLS-2$

        if (currentRowAssignment)
            add(result, words, "получитьидентификатор", 1, false); //$NON-NLS-1$
        if (noParent)
        {
            // для Ф=Ф+1
            if ("=".equals(token) && !receiver.isEmpty() && !receiverIsMethod //$NON-NLS-1$
                && (NUMBER.equalsIgnoreCase(mainType) || STRING.equalsIgnoreCase(mainType)))
                add(result, words, lower(receiver), 1, false);
            if (comparison)
            {
                boolean raiseUndefined = !"Тип".equalsIgnoreCase(mainType) && "<>".equals(token) //$NON-NLS-1$ //$NON-NLS-2$
                    || currentRowAssignment;
                if (!raiseUndefined && IDENTIFIER.matcher(receiver).matches())
                {
                    Word known = words.get(lower(receiver));
                    raiseUndefined = known == null || known.method();
                }
                if (raiseUndefined)
                    add(result, words, lower(UNDEFINED), 2, false);
            }
        }
        // мСтрокаЛучшегоСлова сбрасывается — автооткрытие дают только премии ниже
        String collection = expectation.systemProperty() != null
            ? CURRENT_ITEM_COLLECTIONS.get(expectation.systemProperty()) : null;
        if (collection != null && (noParent || startsWithIgnoreCase(receiver, parent)))
        {
            List<String> fragments = new ArrayList<>(List.of(receiver.split("\\."))); //$NON-NLS-1$
            fragments.set(fragments.size() - 1, collection);
            int level = noParent ? 0 : 1 + countOf(parent, '.');
            if (level < fragments.size())
                add(result, words, lower(fragments.get(level)), 3, true);
            return;
        }
        // Перечислимое свойство объектов метаданных: Метаданные.СвойстваОбъектов.<Тип>. Без списка
        // перечислений из модели — по признаку системного перечисления.
        Set<String> enumerations = adder != null ? adder.facts.objectPropertyEnums() : Set.of();
        boolean enumerableProperty = enumerations.isEmpty() ? expectation.systemEnum()
            : enumerations.contains(lower(dbTable));
        if (enumerableProperty && (!noParent || adder != null))
        {
            int level = countOf(parent, '.');
            if (noParent)
                adder.addTyped("Метаданные.СвойстваОбъектов." + dbTable, 3, true, false); //$NON-NLS-1$
            else if (level == 0)
                add(result, words, "свойстваобъектов", 3, true); //$NON-NLS-1$
            else if (level == 1)
                add(result, words, lower(dbTable), 3, true);
        }
        else
        {
            String rootType = lower(firstFragment(dbTable, '.'));
            String manager = ROOT_TYPE_MANAGERS.get(rootType);
            if (manager != null && dbTable.indexOf('.') > 0
                && (noParent || rootType.startsWith(lower(parent)) || lower(manager).startsWith(lower(parent))))
            {
                if (noParent)
                    add(result, words, lower(manager), 2, false);
                else if (parent.indexOf('.') < 0)
                    add(result, words, lower(lastFragment(dbTable)), 3, true);
            }
        }
        if (noParent && containsIgnoreCase(allTypes, UNDEFINED))
            add(result, words, lower(UNDEFINED), allTypes.size() > 1 ? 3 : 1, allTypes.size() > 1);
        if (noParent && containsIgnoreCase(allTypes, BOOLEAN) && !receiverIsMethod)
        {
            boolean falsePreferred = lastLower.equals("видимость") || lastLower.equals("доступность") //$NON-NLS-1$ //$NON-NLS-2$
                || lastLower.equals("стандартнаяобработка"); //$NON-NLS-1$
            add(result, words, "ложь", falsePreferred ? 5 : 3, true); //$NON-NLS-1$
            boolean truePreferred = lastLower.equals("загрузка") || lastLower.equals("использование") //$NON-NLS-1$ //$NON-NLS-2$
                || lastLower.equals("отказ"); //$NON-NLS-1$
            add(result, words, "истина", truePreferred ? 5 : 3, true); //$NON-NLS-1$
        }
        // По совпадению слова и типа
        String receiverParent = withoutLastFragment(receiver);
        boolean receiverIsSource = !IDENTIFIER.matcher(lastWord).matches()
            || FILL_PROPERTY_VALUES.equalsIgnoreCase(expectation.callMethod()) && expectation.parameterIndex() == 1
            || comparison && (receiverParent.equalsIgnoreCase(parent)
                || noParent && ("ЭтотОбъект".equalsIgnoreCase(receiverParent) //$NON-NLS-1$
                    || "ЭтаФорма".equalsIgnoreCase(receiverParent))); //$NON-NLS-1$
        if (!receiverIsSource)
        {
            Word named = words.get(lastLower);
            if (named != null && (mainType.isEmpty() || named.hasType(mainType)))
                add(result, words, named.key(), 4, true);
        }
        // Защита от пары Найти()-СтрНайти()
        if (!(receiverIsMethod && "найти".equals(lastLower) && noParent)) //$NON-NLS-1$
        {
            String except = receiverIsSource ? lastWord : ""; //$NON-NLS-1$
            boolean strict = false;
            int checked = 0;
            for (String type : allTypes)
            {
                String typeName = firstFragment(type, '(');
                List<Word> candidates = new ArrayList<>();
                if (UNDEFINED.equalsIgnoreCase(typeName))
                {
                    if (strict)
                        continue;
                    candidates.addAll(words.values());
                }
                else
                {
                    // Защита от пары Строка-Строка()-XMLСтрока()
                    boolean propertiesOnly = "строка".equals(lastLower) && noParent //$NON-NLS-1$
                        && STRING.equalsIgnoreCase(typeName);
                    for (Word word : words.values())
                    {
                        if (word.hasType(typeName) && !(propertiesOnly && word.method()))
                            candidates.add(word);
                    }
                    // Значение системного перечисления: в списке есть только его менеджер с типом «Перечисление<Тип>»
                    if (candidates.isEmpty() && typeName.indexOf('.') < 0
                        && !ENUM_RETRY_EXCLUDED.contains(lower(typeName)))
                    {
                        for (Word word : words.values())
                        {
                            if (word.hasEnumManagerType(typeName))
                                candidates.add(word);
                        }
                    }
                }
                markBestWord(result, words, candidates, except, lastWord,
                    UNLIKE_FORBIDDEN_TYPES.contains(lower(typeName)), noParent, fuzzyCalls);
                strict = true;
                if (++checked == MAX_TYPES_TO_CHECK)
                    break;
            }
        }
        if (adder != null && !mainType.isEmpty() && !PRIMITIVE_TYPES.contains(lower(mainType)) && noParent
            && !FILL_PROPERTY_VALUES.equalsIgnoreCase(expectation.callMethod()))
            adder.typedValues(allTypes, mainType, dbTable);
        if (!mainType.isEmpty() && (!PRIMITIVE_TYPES.contains(lower(mainType)) || BOOLEAN.equalsIgnoreCase(mainType)))
        {
            List<Word> pool = new ArrayList<>();
            boolean localsOnly = BOOLEAN.equalsIgnoreCase(mainType);
            for (Word word : words.values())
            {
                // Ограничиваем число совпадений, а не весь словарь модуля: иначе методы
                // подходящего типа теряли премию при любом списке длиннее 200 слов.
                if ((!localsOnly || word.local()) && word.hasType(mainType)
                    && (!receiverIsSource || !word.key().equals(lastLower)))
                    pool.add(word);
            }
            Global.tempLog("expected-type-ranking", "expected=" + mainType + " words=" + words.size() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " compatible=" + pool.size() + " localsOnly=" + localsOnly //$NON-NLS-1$ //$NON-NLS-2$
                + " candidates=" + pool.stream().map(Word::name).toList()); //$NON-NLS-1$
            if (pool.size() < MAX_WORDS_FOR_TYPE)
            {
                for (Word word : pool)
                    add(result, words, word.key(), 2, false);
            }
        }
    }

    /**
     * {@code ОбозначитьСловоСОжидаемымТипомВТаблицеСлов} для ключа по типу: лучшее слово среди
     * {@code candidates} получает +3 — первое подходящее либо, при имени приёмника, самое похожее на него.
     */
    private static void markBestWord(Premiums result, Map<String, Word> words, List<Word> candidates, String except,
        String fuzzyWord, boolean forbidUnlike, boolean noParent, List<FuzzyCall> fuzzyCalls)
    {
        // Уже набранная премия (в том числе штраф редкого слова) — до выбора: при равной порядок списка
        List<Word> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingInt(
            (Word word) -> result.values().getOrDefault(word.key(), Integer.valueOf(0)).intValue()).reversed());
        Word best = bestWord(ordered, words.size(), except, fuzzyWord, forbidUnlike, noParent, fuzzyCalls);
        if (best != null)
            add(result, words, best.key(), 3, true);
    }

    /**
     * Лучшее слово {@code ОбозначитьСловоСОжидаемымТипомВТаблицеСлов} среди {@code candidates} из набора
     * {@code setSize} слов; {@code null} — нет.
     */
    private static Word bestWord(List<Word> candidates, int setSize, String except, String fuzzyWord,
        boolean forbidUnlike, boolean noParent, List<FuzzyCall> fuzzyCalls)
    {
        List<Word> found = new ArrayList<>(candidates);
        if (!found.isEmpty())
        {
            Word first = found.get(0);
            if (!except.isEmpty() && first.name().equalsIgnoreCase(except) && !first.method()
                && found.size() != setSize
                || first.method() && noParent && (first.name().equals("ПолучитьОбщуюФорму") //$NON-NLS-1$
                    || first.name().equals("Строка") || first.name().equals("Число") //$NON-NLS-1$ //$NON-NLS-2$
                    || first.name().equals("Дата"))) //$NON-NLS-1$
                found.remove(0);
        }
        if (found.isEmpty())
            return null;
        Word best = forbidUnlike ? null : found.get(0);
        if (!fuzzyWord.isEmpty() && (best == null || found.size() > 1) && found.size() < MAX_WORDS_FOR_TYPE)
        {
            Word fuzzy = fuzzyBest(found, except, fuzzyWord, fuzzyCalls);
            if (fuzzy != null)
                best = fuzzy;
        }
        return best;
    }

    /** Нечёткая часть {@code ОбозначитьСловоСОжидаемымТипомВТаблицеСлов} (уровни непохожести ИР). */
    private static Word fuzzyBest(List<Word> found, String except, String fuzzyWord, List<FuzzyCall> fuzzyCalls)
    {
        String fuzzyLower = lower(fuzzyWord);
        boolean longFuzzy = fuzzyWord.length() >= FUZZY_SMALL_LENGTH;
        List<String> names = new ArrayList<>(found.size());
        for (Word word : found)
        {
            // Если заменить на пустоту, то нечеткий поиск сдвинет индексы в результате
            names.add(!except.isEmpty() && word.name().equalsIgnoreCase(except) ? "цйъффчъэ" : word.key()); //$NON-NLS-1$
        }
        int maxLevel;
        if (found.size() > MAX_WORDS_FOR_TYPE * 0.7)
            maxLevel = 1;
        else if (found.size() > MAX_WORDS_FOR_TYPE * 0.4 || fuzzyWord.length() < 6)
            maxLevel = 2;
        else
            maxLevel = 3;
        for (int level = 1; level <= maxLevel; level++)
        {
            int bigDifference = 20 * (3 - level) + 30;
            int smallDifference = 20 * (3 - level) + 50;
            int minSmall = fuzzyWord.length() < FUZZY_SMALL_LENGTH ? smallDifference : bigDifference;
            int minBig = fuzzyWord.length() < FUZZY_SMALL_LENGTH ? bigDifference : smallDifference;
            List<Integer> indices = FuzzyStringSearch.stringSearch(fuzzyLower, names, FUZZY_SMALL_LENGTH, minSmall,
                minBig);
            fuzzyCalls.add(new FuzzyCall(fuzzyLower, names, minSmall, minBig, indices));
            for (Integer index : indices)
            {
                Word word = found.get(index.intValue());
                if (word.types().isEmpty() && !word.local())
                    continue;
                boolean shortWord = word.name().length() < FUZZY_SMALL_LENGTH;
                if (longFuzzy && !shortWord
                    // Защита от Дата-Ждать
                    || longFuzzy && shortWord && fuzzyWord.contains(left(word.name(), 3))
                    || !longFuzzy && !shortWord && word.name().contains(left(fuzzyWord, 3)))
                    return word;
            }
        }
        if (fuzzyWord.length() > 3)
        {
            // совпадение по началу слова, затем по концу
            for (int i = 0; i < names.size(); i++)
            {
                if (names.get(i).startsWith(fuzzyLower))
                    return usable(found.get(i));
            }
            for (int i = 0; i < names.size(); i++)
            {
                if (names.get(i).endsWith(fuzzyLower))
                    return usable(found.get(i));
            }
        }
        return null;
    }

    private static Word usable(Word word)
    {
        return word.types().isEmpty() && !word.local() ? null : word;
    }

    // ------------------------------------------------------------------------------------------
    // Первая половина ПрименитьОжидаемыйТипВТаблицеСлов и новые слова
    // ------------------------------------------------------------------------------------------

    /**
     * Встроенный язык: первая половина {@code ПрименитьОжидаемыйТипВТаблицеСлов} (до
     * {@code мСтрокаЛучшегоСлова = Неопределено}) — строки выше, конструкции, слова-генераторы — и слова, которых
     * нет у EDT ({@code ДобавитьВТаблицуСлов}, {@code УстановитьРейтингСлову} с добавлением), с текстом вставки
     * ({@code ВставитьВыбранноеСлово}). {@code ПостроительЗапроса.Измерения} ИР заменяют колонки {@code ИТОГИ ПО}
     * схемы запроса ({@link BslQueryResultTypes#totalColumnsOf}). Не перенесено: язык запросов, текстовые литералы,
     * функция-конструктор ({@code см.}).
     */
    private static final class AddedWords
    {
        private static final Pattern PREVIOUS_STATEMENT = Pattern.compile(
            "^(?:(Для\\s+Каждого|Возврат|Если|ИначеЕсли|И|Или|Пока|If|ElseIf|And|Or|While)\\s+)?((" //$NON-NLS-1$
                + SIMPLE_EXPRESSION + ")(?:\\s*(\\(|<>|=|\\.|из)\\s*([^\\n\\r]+))?)\\s*(?://)?", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        private static final Pattern PREVIOUS_CONDITION = Pattern.compile(
            "^(?:Если|ИначеЕсли)\\s+((" + SIMPLE_EXPRESSION + ")\\s*=\\s*([^\\n\\r]+))\\s*(?://)?", //$NON-NLS-1$ //$NON-NLS-2$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        /** Слово {@code ИначеЕсли  Тогда}: двойной пробел — чтобы быть главнее копии. */
        private static final String ELSE_IF_THEN = "ИначеЕсли  Тогда"; //$NON-NLS-1$
        private static final String CONTINUATION_CHARS = "(,-+<>/|"; //$NON-NLS-1$
        private static final List<String> INSIDE_EXPRESSION_KEYWORDS = List.of("возврат", "если", "тогда", "иначе", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "конецесли", "для", "каждого", "цикл", "конеццикла"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        /** Однословные имена типов метаданных ({@code ОписаниеТипаМетаданныхЛкс}) — первое слово имени отбрасывается. */
        private static final Set<String> METADATA_TYPE_WORDS = Set.of("справочник", "документ", "перечисление", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "задача", "константа", "отчет", "обработка", "последовательность", "перерасчет"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        private final Premiums result;
        private final Map<String, Word> words;
        private final Map<String, BslAddedWordProposal> added;
        private final Map<String, ICompletionProposal> proposals = new HashMap<>();
        private final Context context;
        private final Scene scene;
        final Facts facts;
        private final Expectation expectation;
        private final ICompletionProposal[] list;
        private final List<FuzzyCall> fuzzyCalls;
        private final IDocument document;
        private final String parent;
        /** {@code ВыражениеПриемник}. */
        private final String receiver;
        /** {@code ПредшествующийТокен}. */
        private final String token;
        /** {@code ПредшествующийТекст}. */
        private final String precedingText;
        /** В исходном списке есть свойство ({@code ТаблицаСлов.Найти(Свойство, "ТипСлова")}). */
        private final boolean hasProperty;
        private int line = -1;
        private int lineOffset;
        /** {@code мТекущаяСтрокаНачало} без набранного начала слова. */
        private String lineBeforeWord = ""; //$NON-NLS-1$
        /** {@code НепечатноеНачало}. */
        private String indent = ""; //$NON-NLS-1$
        /** {@code мТекущаяСтрокаКонец} за словом. */
        private String lineAfterWord = ""; //$NON-NLS-1$

        AddedWords(Premiums result, Map<String, Word> words, Map<String, BslAddedWordProposal> added, Context context,
            Scene scene, ICompletionProposal[] list, List<FuzzyCall> fuzzyCalls)
        {
            this.result = result;
            this.words = words;
            this.added = added;
            this.context = context;
            this.scene = scene;
            this.facts = scene.facts();
            this.expectation = scene.expectation();
            this.list = list;
            this.fuzzyCalls = fuzzyCalls;
            this.document = context.document();
            this.parent = context.parent();
            this.receiver = expectation != null ? expectation.receiver() : ""; //$NON-NLS-1$
            this.precedingText = stripEnd(context.preceding());
            this.token = expectation != null ? expectation.token() : tokenOf(precedingText);
            boolean property = false;
            for (ICompletionProposal proposal : list)
                proposals.putIfAbsent(lower(SmartContentAssistProcessor.filterMatchName(proposal)), proposal);
            for (Word word : words.values())
                property |= !word.method();
            this.hasProperty = property;
            try
            {
                line = document.getLineOfOffset(context.anchor());
                IRegion info = document.getLineInformation(line);
                lineOffset = info.getOffset();
                lineBeforeWord = document.get(lineOffset, context.anchor() - lineOffset);
                indent = leadingWhitespace(lineBeforeWord);
                int lineEnd = info.getOffset() + info.getLength();
                int end = context.anchor();
                while (end < lineEnd && SmartContentAssistProcessor.isFilterChar(document.getChar(end)))
                    end++;
                lineAfterWord = document.get(end, lineEnd - end);
            }
            catch (Exception e)
            {
                line = -1;
            }
        }

        /** @return считать ли дальше премии по ожидаемому типу (вторая половина) */
        boolean run()
        {
            if (line < 0)
                return true;
            String selected = context.selected();
            boolean insideExpression = scene.insideExpression();
            boolean singleExpression = !insideExpression && lineAfterWord.isBlank();
            List<String> types = expectation != null ? expectation.types() : List.of();
            String lastWord = firstFragment(receiver.substring(receiver.lastIndexOf('.') + 1), '(');
            String lastLower = lower(lastWord);
            boolean comparison = "=".equals(token) || "<>".equals(token); //$NON-NLS-1$ //$NON-NLS-2$
            if (!selected.isBlank() || parent.isEmpty() && expectation != null && expectation.formalParameter()
                && !receiver.isEmpty() && !types.isEmpty()
                && facts.constructorTypes().contains(lower(firstFragment(types.get(0), '('))))
            {
                createVariable(selected, types);
                if (!selected.isBlank())
                    return false;
            }
            boolean firstWordOfLine = lineBeforeWord.isBlank();
            int premiumShort = firstWordOfLine ? 2 : 3;
            int premiumLong = firstWordOfLine ? 3 : 2;
            // ПредСтрока: содержит выражения, может быть уровнем выше, принадлежит текущему блоку
            int bodyStart = facts.bodyStartLine();
            String previous = null;
            String previous2 = null;
            for (int counter = 1; counter <= 10; counter++)
            {
                int number = line - counter;
                if (number < 0 || bodyStart >= 0 && number < bodyStart)
                    break;
                String text = lineText(number);
                if (hasExpressions(text))
                {
                    if (previous == null)
                        previous = text;
                    else
                    {
                        previous2 = text;
                        break;
                    }
                }
            }
            // ПредСтрокаУровня: отступ как у текущей — нужна для конструкций
            String levelLine = null;
            for (int counter = 1; counter <= 100; counter++)
            {
                int number = line - counter;
                if (number < 0)
                    break;
                String text = lineText(number);
                if (text.isBlank())
                    continue;
                String lineIndent = leadingWhitespace(text);
                if (lineIndent.length() < indent.length())
                    break;
                if (lineIndent.equals(indent))
                {
                    levelLine = text;
                    if (bodyStart >= 0 && number >= bodyStart && hasExpressions(text))
                        previous = text;
                    break;
                }
            }
            String previousTrim = stripEnd(firstFragmentText(nullToEmpty(previous), "//")).stripLeading(); //$NON-NLS-1$
            String previousLower = lower(previousTrim);
            String operator = null;
            String previousFirst = null;
            String previousExpression = null;
            boolean previousMethod = false;
            String previousVariable = null;
            int copyShort = premiumShort;
            int copyLong = premiumLong;
            // При вводе новой инструкции даем приоритет переменной, с которой начинается предшествующая инструкция
            Matcher statement = PREVIOUS_STATEMENT.matcher(previousTrim);
            if (statement.find())
            {
                previousFirst = statement.group(1);
                operator = statement.group(4);
                // Строка выше начинается с И/ИЛИ — копия её остатка
                if (operator != null && operator.equals(copyOperator(lower(nullToEmpty(previousFirst)))))
                    copyRest(previousFirst, statement.group(2), operator, copyLong, copyShort);
                previousExpression = statement.group(3);
                previousMethod = "(".equals(operator); //$NON-NLS-1$
                if (previousMethod && previousExpression.indexOf('.') < 0)
                    previousExpression = null;
                else
                    previousVariable = firstFragment(previousExpression, '.');
            }
            String parentType = facts.parentTypes().isEmpty() ? "" : facts.parentTypes().get(0); //$NON-NLS-1$
            String parentLower = lower(parentType);
            if (previousExpression != null && startsWithIgnoreCase(previousTrim, previousExpression + " = ") //$NON-NLS-1$
                && previousLower.contains(".найтистроки(")) //$NON-NLS-1$
                copyRest(null, "Если " + previousExpression + ".Количество() Тогда", "", 3, 3); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            else if ("Массив".equals(parentType) && endsWithIgnoreCase(precedingText, "если") //$NON-NLS-1$ //$NON-NLS-2$
                && (parent.contains(".Найти") //$NON-NLS-1$
                    || startsWithIgnoreCase(previousTrim, parent + " = ") && previousTrim.contains(".Найти"))) //$NON-NLS-1$ //$NON-NLS-2$
                rate("Количество", 2, BslAddedWordProposal.Kind.METHOD, true, false); //$NON-NLS-1$
            if (previousExpression != null && previousLower.startsWith("пока ") //$NON-NLS-1$
                && (previousLower.contains(".количество() цикл") || previousLower.contains(".количество() >"))) //$NON-NLS-1$ //$NON-NLS-2$
                copyRest(null, withoutLastMarker(previousExpression, ".Количество") + ".Удалить(0<?>);", "", 3, 3); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (previousExpression != null && "=".equals(operator) && nullToEmpty(previousFirst).isBlank() //$NON-NLS-1$
                && previousLower.contains(".найти(")) //$NON-NLS-1$
                copyRest(null, "Если " + previousExpression + " = Неопределено Тогда", "", 3, 3); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (previousExpression != null
                && previousLower.equals("если " + lower(previousExpression) + " = неопределено тогда")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                String between = textBetween(nullToEmpty(previous2), previousExpression + " = ", ".Найти(", false); //$NON-NLS-1$ //$NON-NLS-2$
                String collection = between != null ? between.stripLeading() : ""; //$NON-NLS-1$
                // Наличие метода не проверяется ради скорости (как в ИР)
                if (!collection.isEmpty())
                    copyRest(null, previousExpression + " = " + collection + ".Добавить();", "", 3, 3); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            if (previousExpression != null && startsWithIgnoreCase(previousTrim, previousExpression + " = ") //$NON-NLS-1$
                && previousLower.contains(".найтистроки(")) //$NON-NLS-1$
                copyRest(null, "Для Каждого <?> Из " + previousExpression + " Цикл", "", 3, 3); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // «Если Элемент.ТекущиеДанные …» после «Процедура …ПриАктивизацииСтроки(Элемент)» в ИР не срабатывает:
            // первое слово строки выше там не бывает «Процедура»
            if (!parent.isEmpty() && "[".equals(token) && endsWithIgnoreCase(precedingText, parent + "[")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                if (isPlatformMethod("вграница")) //$NON-NLS-1$
                    add(result, words, "вграница", 2, true); //$NON-NLS-1$
                else if (isPlatformMethod("количество")) //$NON-NLS-1$
                    add(result, words, "количество", 2, true); //$NON-NLS-1$
            }
            if (singleExpression && !parent.isEmpty() && lineBeforeWord.strip().equals(parent + ".") && hasProperty //$NON-NLS-1$
                && parent.indexOf('(') < 0 && !parentLower.startsWith("перечисление")) //$NON-NLS-1$
            {
                boolean created = startsWithIgnoreCase(previousLower, lower(parent + " = Новый")) //$NON-NLS-1$
                    || startsWithIgnoreCase(previousLower, lower(parent + " =")) && previousLower.contains(".добавить("); //$NON-NLS-1$ //$NON-NLS-2$
                fillProperties(created ? 0 : -1);
            }
            if (!parent.isEmpty())
            {
                if (receiver.indexOf('.') > 0 && receiver.indexOf('(') < 0 && !receiver.endsWith("]") //$NON-NLS-1$
                    && parent.indexOf('(') < 0 && "=".equals(token) && hasProperty //$NON-NLS-1$
                    && !parentLower.startsWith("общиймодуль") && !facts.parentSystemEnum()) //$NON-NLS-1$
                    matchProperties(1);
                if (singleExpression && facts.parentCollection() && isMethod("количество") && isMethod("удалить")) //$NON-NLS-1$ //$NON-NLS-2$
                    deleteSelected(-1);
                if (singleExpression && (facts.parentCollection() && !"ВыборкаИзРезультатаЗапроса".equals(parentType) //$NON-NLS-1$
                    || "ТабличныйДокумент".equals(parentType) || "ТекстовыйДокумент".equals(parentType) //$NON-NLS-1$ //$NON-NLS-2$
                    || "РезультатЗапроса".equals(parentType) && facts.queryTotals() != null)) //$NON-NLS-1$
                    iterate(parentLower.startsWith("строка") ? -1 : 0, parentType); //$NON-NLS-1$
                if (facts.parentIndexed() && !"ВыборкаИзРезультатаЗапроса".equals(parentType) && parent.indexOf('(') < 0) //$NON-NLS-1$
                    lastElement(hasProjectMember() ? -1 : 0);
                if ("СтрокаТаблицыЗначений".equals(parentType) || "СтрокаДереваЗначений".equals(parentType)) //$NON-NLS-1$ //$NON-NLS-2$
                    removeFromCollection(-1, parentType);
                if (isEnumManager(parentLower, "направлениесортировки") //$NON-NLS-1$
                    || isEnumManager(parentLower, "направлениесортировкикомпоновкиданных")) //$NON-NLS-1$
                    add(result, words, "убыв", 3, true); //$NON-NLS-1$
                if (facts.parentCollection() && isMethod("найтистроки")) //$NON-NLS-1$
                    findOrAddRow(-1);
            }
            if (singleExpression && "Структура".equals(parentType) && hasProperty) //$NON-NLS-1$
                createVariablesFromStructure(0);
            if (parent.isEmpty() && lastLower.contains("разделител")) //$NON-NLS-1$
                add(result, words, "символы", 2, false); //$NON-NLS-1$
            if (levelLine != null)
            {
                String levelFirst = lower(firstFragmentText(levelLine, " ").stripLeading()); //$NON-NLS-1$
                if ("если".equals(levelFirst) || "иначеесли".equals(levelFirst)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    Matcher condition = PREVIOUS_CONDITION.matcher(levelLine.stripLeading());
                    if (condition.find())
                    {
                        previousExpression = condition.group(2);
                        previousVariable = firstFragment(previousExpression, '.');
                        copyShort = 3;
                        copyLong = 3;
                        copyRest("ИначеЕсли", condition.group(1), "=", copyLong, copyShort); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
                if (firstWordOfLine)
                {
                    if (in(levelFirst, "если", "иначеесли", "иначе", "тогда")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        construction("КонецЕсли", 4); //$NON-NLS-1$
                    if (in(levelFirst, "если", "иначеесли", "тогда")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    {
                        construction("ИначеЕсли", premiumShort); //$NON-NLS-1$
                        construction(ELSE_IF_THEN, premiumLong);
                        construction("Иначе", 3); //$NON-NLS-1$
                    }
                    if (in(levelFirst, "#если", "#иначеесли", "#иначе")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        construction("#КонецЕсли", 4); //$NON-NLS-1$
                    if (in(levelFirst, "пока", "для", "цикл")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        construction("КонецЦикла", 3); //$NON-NLS-1$
                    if ("попытка".equals(levelFirst)) //$NON-NLS-1$
                        construction("Исключение", 3); //$NON-NLS-1$
                    if ("исключение".equals(levelFirst)) //$NON-NLS-1$
                        construction("КонецПопытки", 3); //$NON-NLS-1$
                    if ("функция".equals(levelFirst)) //$NON-NLS-1$
                        construction("КонецФункции", 3); //$NON-NLS-1$
                    if ("процедура".equals(levelFirst)) //$NON-NLS-1$
                        construction("КонецПроцедуры", 3); //$NON-NLS-1$
                    if (!result.autoOpen().isEmpty())
                        return false;
                }
            }
            if (insideExpression)
            {
                for (String keyword : INSIDE_EXPRESSION_KEYWORDS)
                    addKeyword(keyword, -1);
            }
            else if (levelLine == null)
                addKeyword("конецесли", -1); //$NON-NLS-1$
            if (previousExpression != null && (!comparison || !receiver.equals(previousVariable)))
            {
                BslAddedWordProposal.Kind kind = BslAddedWordProposal.Kind.PROPERTY;
                boolean premium = false;
                String next = null;
                if (parent.isEmpty())
                {
                    premium = previousVariable != null && !previousVariable.isEmpty();
                    next = previousVariable;
                    if (!previousExpression.equals(previousVariable))
                    {
                        BslAddedWordProposal.Kind expressionKind = previousExpression.indexOf(' ') >= 0
                            ? BslAddedWordProposal.Kind.CONSTRUCTION
                            : previousMethod ? BslAddedWordProposal.Kind.METHOD : BslAddedWordProposal.Kind.PROPERTY;
                        rate(previousExpression, copyLong, expressionKind, false, true);
                    }
                }
                else if (startsWithIgnoreCase(previousExpression, parent + ".") //$NON-NLS-1$
                    && startsWithIgnoreCase(lineBeforeWord.stripLeading(), parent))
                {
                    int index = countOf(parent, '.') + 1;
                    String[] fragments = previousExpression.split("\\.", -1); //$NON-NLS-1$
                    if (index < fragments.length)
                    {
                        next = fragments[index];
                        if (line + 1 > 2)
                            premium = startsWithIgnoreCase(nullToEmpty(previous2).stripLeading(), parent + "." + next); //$NON-NLS-1$
                        if (!premium)
                            premium = previousTrim.indexOf('=') < 0 && previousTrim.indexOf('"') >= 0;
                        if (premium && previousMethod && index == fragments.length - 1)
                            kind = BslAddedWordProposal.Kind.METHOD;
                    }
                }
                if (premium && next != null)
                    rate(next, copyShort, kind, false, true);
            }
            if (types.isEmpty() && receiver.isEmpty() && previousExpression == null)
                return false;
            // мСтрокаЛучшегоСлова = Неопределено: автооткрытие дают только премии по ожидаемому типу
            result.autoOpen().clear();
            return true;
        }

        // ---- Конструкции заполнения таблицы слов ({@code ЗаполнитьТаблицуСлов}) ---------------------------

        /**
         * Конструкции в начале пустой строки и при выделении целых строк (окружающие конструкции):
         * {@code ЗаполнитьТаблицуСлов} ИР, блок «Если <?>  Тогда» … «Процедура КонецПроцедуры».
         */
        void surroundingConstructions()
        {
            if (line < 0 || SmartContentAssistProcessor.isCommentAssistContext(document, context.caret()))
                return;
            String selected = context.selected();
            String afterCaret;
            try
            {
                IRegion info = document.getLineInformation(line);
                int caret = Math.min(Math.max(context.caret(), info.getOffset()), info.getOffset() + info.getLength());
                afterCaret = document.get(caret, info.getOffset() + info.getLength() - caret);
            }
            catch (Exception e)
            {
                return;
            }
            // СокрЛ(мТекущаяСтрокаНачало) = мНачалоКонтекста И ПустаяСтрока(мТекущаяСтрокаКонец)
            boolean emptyLine = selected.isEmpty() && parent.isEmpty() && !scene.insideExpression()
                && lineBeforeWord.isBlank() && afterCaret.isBlank();
            // мНачальнаяСтрока < мКонечнаяСтрока И мНачальнаяКолонка = 1 И мКонечнаяКолонка = 1
            boolean wholeLines = !selected.isEmpty() && context.caret() == lineOffset
                && selected.indexOf('\n') >= 0 && selected.endsWith("\n"); //$NON-NLS-1$
            if (!emptyLine && !wholeLines)
                return;
            boolean between = facts.betweenMethods();
            if (!between)
            {
                addKeyword("если", -1); //$NON-NLS-1$
                addKeyword("для", -1); //$NON-NLS-1$
                // Второй пробел — для вывода на первую позицию при сортировке
                construction("Если <?>  Тогда", wholeLines ? 2 : 1, false); //$NON-NLS-1$
                construction("Если <?> = Неопределено Тогда", 1, false); //$NON-NLS-1$
                construction("Если Не ЗначениеЗаполнено(<?>) Тогда", 1, false); //$NON-NLS-1$
                construction("Если ТипЗнч(<?>) = Тип(\"\") Тогда", 1, false); //$NON-NLS-1$
                construction("Пока Цикл", 1, false); //$NON-NLS-1$
                construction("Попытка Исключение", 1, false); //$NON-NLS-1$
                construction("Для Каждого  Из <?> Цикл", 1, false); //$NON-NLS-1$
                construction("Для По", 1, false); //$NON-NLS-1$
                construction("НачатьТранзакцию Попытка", 1, false); //$NON-NLS-1$
            }
            // СокрЛП(мОригинальныйТекст) = мКонтекст: в модуле нет ничего, кроме вводимого слова
            int multiplier = document.get().strip().equals(currentWord()) ? 2 : 0;
            construction("#Если КонецЕсли", multiplier, false); //$NON-NLS-1$
            construction("#Область КонецОбласти", multiplier, false); //$NON-NLS-1$
            if (!wholeLines && !directivesAvailable())
            {
                construction("Функция КонецФункции", between ? 2 : 0, false); //$NON-NLS-1$
                construction("Процедура КонецПроцедуры", between ? 2 : 0, false); //$NON-NLS-1$
            }
        }

        /** {@code мКонтекст} без родителя: слово у каретки целиком. */
        private String currentWord()
        {
            try
            {
                int end = context.anchor();
                while (end < document.getLength() && SmartContentAssistProcessor.isFilterChar(document.getChar(end)))
                    end++;
                return document.get(context.anchor(), end - context.anchor());
            }
            catch (Exception e)
            {
                return ""; //$NON-NLS-1$
            }
        }

        /** {@code ЛиВМодулеДоступныДирективыКомпиляции}: модули форм и команд. */
        private boolean directivesAvailable()
        {
            return facts.moduleType() == ModuleType.FORM_MODULE || facts.moduleType() == ModuleType.COMMAND_MODULE;
        }

        private void construction(String name, int multiplier, boolean autoOpen)
        {
            rate(name, multiplier, BslAddedWordProposal.Kind.CONSTRUCTION, autoOpen, false);
        }

        // ---- Слова второй половины ({@code мПлатформа.ДобавитьВТаблицуСлов}) -------------------------

        /**
         * {@code Новый <Тип>}, {@code Тип("<Тип>")}, значение перечисления — при ожидаемом сложном типе без
         * родителя. Из корневых типов с предопределёнными ИР здесь берёт только перечисление (условие проверяет
         * {@code ТипСлова}, а не тип таблицы).
         */
        void typedValues(List<String> allTypes, String mainType, String dbTable)
        {
            for (String type : allTypes)
            {
                String name = firstFragment(type, '[');
                if (!facts.constructorTypes().contains(lower(firstFragment(name, '('))) || facts.structureConstructor())
                    continue;
                if ("ОписаниеТипов".equals(name)) //$NON-NLS-1$
                {
                    for (String primitive : new String[] { STRING, NUMBER, BOOLEAN })
                        addTyped("Новый " + name + "(\"" + primitive + "\")", 3, false, false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                String word = "Новый " + name; //$NON-NLS-1$
                if (STRUCTURE.equals(name) && expectation.formalParameter())
                    addTyped(word, 3, false, false, word + "(\"<?>\")", true); //$NON-NLS-1$
                else
                    addTyped(word, 3, false, false);
            }
            List<String> nested = facts.nestedTypes();
            if (!nested.isEmpty() && nested.size() < 10)
            {
                Set<String> seen = new HashSet<>();
                for (String type : nested)
                {
                    if ("Произвольный".equals(type) || UNDEFINED.equals(type) || !seen.add(lower(type))) //$NON-NLS-1$
                        continue;
                    addTyped("Тип(\"" + type + "\")", 3, false, type.indexOf('.') >= 0); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            if ("перечисление".equals(lower(firstFragment(dbTable, '.'))) && dbTable.indexOf('.') > 0) //$NON-NLS-1$
            {
                String word = facts.formClient() ? "ПредопределенноеЗначение(\"" + dbTable + ".<?>\")" //$NON-NLS-1$ //$NON-NLS-2$
                    : "Перечисления." + lastFragment(dbTable) + ".<?>"; //$NON-NLS-1$ //$NON-NLS-2$
                addTyped(word, 2, true, true);
            }
        }

        /** Слово {@code name} с рейтингом {@code rating}; нет в списке — добавляется с текстом вставки {@code name}. */
        void addTyped(String name, int rating, boolean autoOpen, boolean projectWord)
        {
            addTyped(name, rating, autoOpen, projectWord, name, false);
        }

        private void addTyped(String name, int rating, boolean autoOpen, boolean projectWord, String template,
            boolean reopen)
        {
            String key = lower(name);
            if (!words.containsKey(key))
            {
                words.put(key, new Word(key, name, List.of(), false, false));
                added.put(key, new BslAddedWordProposal(name, BslAddedWordProposal.Kind.CONSTRUCTION, template,
                    BslAddedWordProposal.Absorb.WORD, -1, "", null, reopen, false, projectWord)); //$NON-NLS-1$
            }
            add(result, words, key, rating, autoOpen);
        }

        // ---- УстановитьРейтингСлову ------------------------------------------------------------------

        /** {@code УстановитьРейтингСлову} с добавлением: слова нет — добавляется со вставкой {@code ВставитьВыбранноеСлово}. */
        private void rate(String name, int multiplier, BslAddedWordProposal.Kind kind, boolean autoOpen,
            boolean statistical)
        {
            if (name == null || name.isBlank())
                return;
            String key = lower(name);
            if (!words.containsKey(key))
            {
                words.put(key, new Word(key, name, List.of(), kind == BslAddedWordProposal.Kind.METHOD, false));
                added.put(key, newWord(name, kind, statistical));
            }
            add(result, words, key, multiplier, autoOpen);
        }

        private void construction(String name, int multiplier)
        {
            rate(name, multiplier, BslAddedWordProposal.Kind.CONSTRUCTION, true, false);
        }

        /** Премия ключевому слову EDT (без добавления). */
        private void addKeyword(String key, int multiplier)
        {
            ICompletionProposal proposal = proposals.get(key);
            if (proposal != null && SmartContentAssistProcessor.isKeywordProposal(proposal))
                add(result, words, key, multiplier, false);
        }

        /** Текст вставки нового слова — ветки {@code ВставитьВыбранноеСлово} для слов, которые добавляет ИР. */
        private BslAddedWordProposal newWord(String name, BslAddedWordProposal.Kind kind, boolean statistical)
        {
            BslAddedWordProposal special = constructionWord(name);
            if (special != null)
                return special;
            if (startsWithIgnoreCase(name, "Если ")) //$NON-NLS-1$
                return surround(name.replace("  ", " "), "КонецЕсли;", statistical); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (startsWithIgnoreCase(name, "Для Каждого ")) //$NON-NLS-1$
                return surround(name, "КонецЦикла;", statistical); //$NON-NLS-1$
            String template = name;
            if (ELSE_IF_THEN.equals(name))
                template = "ИначеЕсли <?> Тогда"; //$NON-NLS-1$
            else if (kind == BslAddedWordProposal.Kind.METHOD)
                template = name + "(<?>)"; //$NON-NLS-1$
            else if (kind == BslAddedWordProposal.Kind.CONSTRUCTION
                && in(lower(name), "продолжить", "прервать", "конецесли", "конеццикла", "конецпопытки")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                template = name + ";"; //$NON-NLS-1$
            return new BslAddedWordProposal(name, kind, template, BslAddedWordProposal.Absorb.WORD, -1, "", null, //$NON-NLS-1$
                false, statistical, false);
        }

        /**
         * Вставка слов-конструкций со своей веткой {@code ВставитьВыбранноеСлово}; {@code null} — у слова её нет.
         * {@code <%!%>} — тело (выделенный текст или каретка).
         */
        private BslAddedWordProposal constructionWord(String name)
        {
            switch (name)
            {
                case "Пока Цикл": //$NON-NLS-1$
                    return surroundWord(name, "Пока <?> Цикл", "КонецЦикла;"); //$NON-NLS-1$ //$NON-NLS-2$
                case "Для По": //$NON-NLS-1$
                    return surroundWord(name, "Для " + newVariableName("Счетчик") + " = 1 По <?> Цикл", "КонецЦикла;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                case "Попытка Исключение": //$NON-NLS-1$
                    return surroundWord(name, "Попытка \n<%!%>\nИсключение\n\t\nКонецПопытки;", ""); //$NON-NLS-1$ //$NON-NLS-2$
                case "НачатьТранзакцию Попытка": //$NON-NLS-1$
                    return surroundWord(name, "НачатьТранзакцию();\nПопытка\n<%!%>\n\tЗафиксироватьТранзакцию();\n" //$NON-NLS-1$
                        + "Исключение\n\tОтменитьТранзакцию();\n\tЕсли ТранзакцияАктивна() Тогда\n\t\tВызватьИсключение;\n" //$NON-NLS-1$
                        + "\tКонецЕсли; \n\tСообщить(ОписаниеОшибки());\nКонецПопытки;", ""); //$NON-NLS-1$ //$NON-NLS-2$
                case "#Если КонецЕсли": //$NON-NLS-1$
                {
                    String template;
                    boolean blockStart;
                    try
                    {
                        blockStart = document.get(0, lineOffset).isBlank();
                    }
                    catch (Exception e)
                    {
                        blockStart = false;
                    }
                    if (facts.moduleType() == ModuleType.MANAGER_MODULE && blockStart)
                        template = "#Если Сервер Или ТолстыйКлиентОбычноеПриложение Или ВнешнееСоединение Тогда\n\n<%!%>\n\n#КонецЕсли"; //$NON-NLS-1$
                    else if (facts.betweenMethods())
                        template = "#Если <?> Тогда\n\n<%!%>\n\n#КонецЕсли"; //$NON-NLS-1$
                    else
                        template = "#Если <?> Тогда\n<%!%>\n#КонецЕсли"; //$NON-NLS-1$
                    return surroundWord(name, template, ""); //$NON-NLS-1$
                }
                case "#Область КонецОбласти": //$NON-NLS-1$
                    return surroundWord(name, facts.betweenMethods() ? "#Область <?>\n \n<%!%>\n\n#КонецОбласти" //$NON-NLS-1$
                        : "#Область <?>\n<%!%>\t\n#КонецОбласти", ""); //$NON-NLS-1$ //$NON-NLS-2$
                case "Функция КонецФункции": //$NON-NLS-1$
                    return new BslAddedWordProposal(name, BslAddedWordProposal.Kind.CONSTRUCTION,
                        "Функция <?>() Экспорт\n\tВозврат ;\nКонецФункции", BslAddedWordProposal.Absorb.WORD, -1, "", //$NON-NLS-1$ //$NON-NLS-2$
                        null, false, false, false);
                case "Процедура КонецПроцедуры": //$NON-NLS-1$
                    return new BslAddedWordProposal(name, BslAddedWordProposal.Kind.CONSTRUCTION,
                        "Процедура <?>() Экспорт\nКонецПроцедуры", BslAddedWordProposal.Absorb.WORD, -1, "", //$NON-NLS-1$ //$NON-NLS-2$
                        null, false, false, false);
                default:
                    return null;
            }
        }

        /** Слово {@code word} с вставкой {@code ВставитьОкружающуюКонструкцию} по шаблону {@code template}. */
        private BslAddedWordProposal surroundWord(String word, String template, String closing)
        {
            return new BslAddedWordProposal(word, BslAddedWordProposal.Kind.CONSTRUCTION, template,
                BslAddedWordProposal.Absorb.LINE, -1, "", closing, false, false, false); //$NON-NLS-1$
        }

        /** {@code ВставитьОкружающуюКонструкцию}: заголовок {@code header} и закрывающая строка. */
        private BslAddedWordProposal surround(String header, String closing, boolean statistical)
        {
            return new BslAddedWordProposal(header, BslAddedWordProposal.Kind.CONSTRUCTION, header,
                BslAddedWordProposal.Absorb.LINE, -1, "", closing, false, statistical, false); //$NON-NLS-1$
        }

        /** Слово-генератор: всегда добавляется ({@code ДобавитьВТаблицуСлов}) с готовым текстом вставки. */
        private void generator(String name, int multiplier, String template, BslAddedWordProposal.Absorb absorb,
            int absorbOffset, boolean reopen, boolean statistical)
        {
            String key = lower(name);
            String absorbText = ""; //$NON-NLS-1$
            int offset = absorbOffset;
            if (absorb == BslAddedWordProposal.Absorb.CONTEXT)
                offset = context.start();
            if (offset >= 0)
            {
                try
                {
                    absorbText = document.get(offset, context.anchor() - offset);
                }
                catch (Exception e)
                {
                    offset = -1;
                }
            }
            words.putIfAbsent(key, new Word(key, name, List.of(), false, false));
            added.put(key, new BslAddedWordProposal(name, BslAddedWordProposal.Kind.CONSTRUCTION, template, absorb,
                offset, absorbText, null, reopen, statistical, false));
            add(result, words, key, multiplier, false);
        }

        /**
         * {@code ДобавитьОстатокПредстрокиВТаблицуСлов}: остаток предлагаемой строки от начала слова — новое слово
         * (копия), его первый фрагмент — премия. {@code copyOperator} — правая часть сравнения: последний фрагмент
         * текстового литерала заменяется кареткой.
         */
        private void copyRest(String firstWord, String proposed, String copyOperator, int premiumLong, int premiumShort)
        {
            String currentLower = lower(lineBeforeWord);
            String firstLower;
            String pattern;
            String first = nullToEmpty(firstWord);
            if (first.isEmpty())
            {
                firstLower = indent;
                pattern = firstLower;
            }
            else
            {
                first = first + " "; //$NON-NLS-1$
                firstLower = lower(first);
                pattern = indent + firstLower;
            }
            int startLength = Math.max(0, currentLower.length() - pattern.length());
            if (!left(pattern, currentLower.length()).equals(left(currentLower, pattern.length()))
                || !left(textAfterFirst(currentLower, firstLower), startLength).equals(left(lower(proposed), startLength)))
                return;
            String full = indent + first + proposed;
            String rest = lineBeforeWord.length() < full.length() ? full.substring(lineBeforeWord.length()) : ""; //$NON-NLS-1$
            if (!lineAfterWord.isBlank() && endsWithIgnoreCase(rest, lineAfterWord))
                rest = rest.substring(0, rest.length() - lineAfterWord.length());
            if (!copyOperator.isEmpty())
            {
                String quoted = textBetween(textAfterFirst(rest, copyOperator), "\"", "\"", true); //$NON-NLS-1$ //$NON-NLS-2$
                if (quoted != null && !quoted.isEmpty())
                {
                    if (quoted.indexOf('.') >= 0)
                        rest = rest.replace(quoted.substring(quoted.lastIndexOf('.') + 1), "<?>\""); //$NON-NLS-1$
                    else
                        rest = rest.replace(quoted, "\"<?>\""); //$NON-NLS-1$
                }
            }
            if (rest.isBlank())
                return;
            rate(rest, premiumLong, BslAddedWordProposal.Kind.CONSTRUCTION, false, true);
            String firstFragment = rest;
            if (!copyOperator.isEmpty())
                firstFragment = firstFragmentText(firstFragment, copyOperator);
            add(result, words, lower(stripEnd(firstFragment(firstFragment, '.'))), premiumShort, false);
        }

        // ---- Слова-генераторы ------------------------------------------------------------------------

        /** {@code <СоздатьПеременную>}: присваивание новой переменной перед текущей инструкцией ({@code СоздатьПеременнуюИзВыделенногоВыражения}). */
        private void createVariable(String selected, List<String> types)
        {
            String base = lastFragment(receiver);
            if (RETURN_VARIABLE.equals(base))
                base = "Результат"; //$NON-NLS-1$
            boolean fullArgument = expectation != null && expectation.parameterIndex() >= 0
                && (selected.isEmpty() || isWholeArgument());
            if (base.isEmpty() || fullArgument || IDENTIFIER.matcher(receiver).matches() && "=".equals(token)) //$NON-NLS-1$
            {
                List<String> variants = new ArrayList<>();
                if (fullArgument)
                    nameVariantsFromTypes(variants, types);
                if (variants.isEmpty())
                    nameVariantsFromTypes(variants, facts.selectionTypes());
                // ПоследнийФрагментЛкс(…, , Ложь): без точки — пусто
                String lastName = selected.indexOf('.') >= 0 ? selected.substring(selected.lastIndexOf('.') + 1) : ""; //$NON-NLS-1$
                if (!containsAny(selected, "-+*/><=")) //$NON-NLS-1$
                    nameVariantsFromWord(variants, lastName);
                if (!variants.isEmpty() && PRIMITIVE_TYPES.contains(lower(variants.get(0))))
                    variants.remove(0);
                if (!variants.isEmpty())
                    base = variants.get(0);
            }
            else
                base = base.replace("(", "").replace(")", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            String name = newVariableName(base);
            String assignment;
            if (selected.isEmpty() && !types.isEmpty())
            {
                String type = firstFragment(types.get(0), '(');
                assignment = name + " = Новый " + type + ("ОписаниеОповещения".equals(type) ? "(, ЭтотОбъект)" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            }
            else
                assignment = name + " = " + selected; //$NON-NLS-1$
            // Начало инструкции: строки продолжения выше (И/ИЛИ, операторы на стыке строк)
            List<String> lines = new ArrayList<>();
            int number = line;
            String current = lineBeforeWord + name;
            String currentTrim = current.stripLeading();
            String above = number > 0 ? stripEnd(lineText(number - 1)) : ""; //$NON-NLS-1$
            while (startsWithIgnoreCase(currentTrim, "Или ") || startsWithIgnoreCase(currentTrim, "И ") //$NON-NLS-1$ //$NON-NLS-2$
                || !currentTrim.isEmpty() && CONTINUATION_CHARS.indexOf(currentTrim.charAt(0)) >= 0
                || !above.isEmpty() && CONTINUATION_CHARS.indexOf(above.charAt(above.length() - 1)) >= 0)
            {
                number--;
                if (number < 0)
                {
                    number = 0;
                    break;
                }
                lines.add(0, current);
                current = lineText(number);
                currentTrim = current.stripLeading();
                above = number > 0 ? stripEnd(lineText(number - 1)) : ""; //$NON-NLS-1$
            }
            lines.add(0, current);
            String text = leadingWhitespace(current) + assignment + ";\n" + String.join("\n", lines); //$NON-NLS-1$ //$NON-NLS-2$
            int from;
            try
            {
                from = document.getLineOffset(number);
            }
            catch (Exception e)
            {
                return;
            }
            generator("<СоздатьПеременную>", context.caret() > lineOffset ? 2 : 0, text, //$NON-NLS-1$
                BslAddedWordProposal.Absorb.FROM_OFFSET, from, false, true);
        }

        /** Выделение — весь аргумент вызова ({@code ВыделенныйТекст = мФактическиеПараметры[…]}). */
        private boolean isWholeArgument()
        {
            String after = ""; //$NON-NLS-1$
            try
            {
                int end = context.caret() + context.selected().length();
                IRegion info = document.getLineInformationOfOffset(end);
                after = document.get(end, info.getOffset() + info.getLength() - end).stripLeading();
            }
            catch (Exception e)
            {
                return false;
            }
            return (precedingText.endsWith("(") || precedingText.endsWith(",")) //$NON-NLS-1$ //$NON-NLS-2$
                && (after.startsWith(")") || after.startsWith(",")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /**
         * {@code ДобавитьВариантыИмениНовойПеременнойИзТипа}: варианты даёт только единственный тип метаданных
         * (у платформенных типов детальность ниже 3).
         */
        private void nameVariantsFromTypes(List<String> variants, List<String> types)
        {
            if (types.size() != 1 || types.get(0).indexOf('.') < 0)
                return;
            String first = firstFragment(types.get(0), '.');
            if (endsWithIgnoreCase(first, "ТабличнаяЧастьСтрока")) //$NON-NLS-1$
                first = "СтрокаТаблицы"; //$NON-NLS-1$
            else if (endsWithIgnoreCase(first, "Значений")) //$NON-NLS-1$
                first = first.replace("Значений", ""); //$NON-NLS-1$ //$NON-NLS-2$
            nameVariantsFromWord(variants, first);
        }

        /** {@code ДобавитьВариантыИмениНовойПеременнойИзСлова}. */
        private void nameVariantsFromWord(List<String> variants, String expression)
        {
            String clean = firstFragment(expression, '(');
            boolean method = !clean.equals(expression);
            clean = firstFragmentText(firstFragment(clean, '['), "Лкс"); //$NON-NLS-1$
            if (clean.isEmpty())
                return;
            List<String> parts = new ArrayList<>(List.of(Global.representationFromIdentifier(clean).split(" ", -1))); //$NON-NLS-1$
            if (method && TypeNameFromVariableName.isVerb(parts.get(0))
                || parts.size() > 1 && METADATA_TYPE_WORDS.contains(lower(parts.get(0))))
                parts.remove(0);
            Set<String> taken = takenNames();
            String variant = ""; //$NON-NLS-1$
            while (!parts.isEmpty())
            {
                String only = parts.get(0);
                if (parts.size() == 1 && (BOOLEAN.equals(only) || "Произвольный".equals(only) //$NON-NLS-1$
                    || "новый".equalsIgnoreCase(only))) //$NON-NLS-1$
                    break;
                if (parts.size() < 4 && (parts.get(parts.size() - 1).length() > 2 || variant.isEmpty()))
                {
                    variant = Global.identifierFromRepresentation(String.join(" ", parts)); //$NON-NLS-1$
                    if (variant.length() > 2 && !taken.contains(lower(variant)) && !variants.contains(variant))
                        variants.add(variant);
                }
                parts.remove(parts.size() - 1);
            }
        }

        /** {@code НовоеИмяПеременной}: {@code Имя}, {@code Имя1}, … — не занятое переменной. */
        private String newVariableName(String base)
        {
            String name = Global.identifierFromRepresentation(base == null || base.isBlank() ? "Переменная" : base); //$NON-NLS-1$
            Set<String> taken = takenNames();
            for (int index = 0;; index++)
            {
                String candidate = index == 0 ? name : name + index;
                if (!taken.contains(lower(candidate)))
                    return candidate;
            }
        }

        private Set<String> takenNames()
        {
            Set<String> taken = new HashSet<>();
            for (Local local : facts.locals())
                taken.add(lower(local.name()));
            if (parent.isEmpty())
            {
                for (Word word : words.values())
                {
                    if (!word.method())
                        taken.add(word.key());
                }
            }
            return taken;
        }

        /** {@code <ЗаполнитьСвойства>}: {@code Родитель.Свойство = <переменная>;} по свойствам для записи. */
        private void fillProperties(int rating)
        {
            List<Local> lefts = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ICompletionProposal proposal : list)
            {
                if (!(proposalInfo(proposal) instanceof Property property) || property.eIsProxy() || !property.isWritable())
                    continue;
                String name = SmartContentAssistProcessor.filterMatchName(proposal);
                Word word = words.get(lower(name));
                if (!name.isEmpty() && seen.add(lower(name)))
                    lefts.add(new Local(name, word != null ? word.types() : List.of(), true));
            }
            if (lefts.isEmpty())
                return;
            List<Word> rights = new ArrayList<>();
            for (Local local : facts.locals())
            {
                if (local.visible())
                    rights.add(new Word(lower(local.name()), local.name(), local.types(), false, true));
            }
            List<String> lines = new ArrayList<>();
            for (Local left : ordered(lefts))
                lines.add(lineBeforeWord + left.name() + " = " + pickRight(left, rights) + ";"); //$NON-NLS-1$ //$NON-NLS-2$
            generator("<ЗаполнитьСвойства>", rating, String.join("\n", lines), BslAddedWordProposal.Absorb.LINE, -1, //$NON-NLS-1$ //$NON-NLS-2$
                false, false);
        }

        /**
         * {@code <СопоставитьСвойства>}: {@code Объект.Свойство = Родитель.Свойство} по свойствам объекта слева от
         * {@code =} с подобранными справа. В логическом выражении — через {@code И}; начало строки заменяется целиком.
         */
        private void matchProperties(int rating)
        {
            boolean logical = !receiver.isEmpty() && !startsWithIgnoreCase(lineBeforeWord.stripLeading(), receiver);
            String partBefore = firstFragmentText(lineBeforeWord, receiver);
            String templateIndent = indent;
            String lineEnd = ""; //$NON-NLS-1$
            if (logical)
            {
                if (!stripEnd(partBefore).endsWith("И")) //$NON-NLS-1$
                    templateIndent = templateIndent + "\t"; //$NON-NLS-1$
                templateIndent = templateIndent + "И "; //$NON-NLS-1$
            }
            else
                lineEnd = ";"; //$NON-NLS-1$
            List<Local> lefts = new ArrayList<>();
            for (Local property : facts.receiverProperties())
            {
                if (logical || property.visible())
                    lefts.add(property);
            }
            List<Word> rights = new ArrayList<>();
            for (Word word : words.values())
            {
                if (!word.method() && !added.containsKey(word.key()))
                    rights.add(new Word(word.key(), word.name(), word.types(), false, true));
            }
            String object = firstFragment(receiver, '.');
            List<String> lines = new ArrayList<>();
            for (Local left : ordered(lefts))
            {
                String right = pickRight(left, rights);
                if (!right.isEmpty())
                    lines.add(templateIndent + object + "." + left.name() + " = " + parent + "." + right + lineEnd); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            if (lines.isEmpty())
                return;
            generator("<СопоставитьСвойства>", rating, String.join("\n", lines), BslAddedWordProposal.Absorb.LINE, //$NON-NLS-1$ //$NON-NLS-2$
                -1, false, false);
        }

        /** По имени, первым — свойство с именем последнего фрагмента приёмника. */
        private List<Local> ordered(List<Local> locals)
        {
            List<Local> result = new ArrayList<>(locals);
            result.sort(Comparator.comparing(Local::name, String.CASE_INSENSITIVE_ORDER));
            String first = lastFragment(receiver);
            for (int i = 0; i < result.size(); i++)
            {
                if (result.get(i).name().equals(first))
                {
                    result.add(0, result.remove(i));
                    break;
                }
            }
            return result;
        }

        /**
         * Правая часть для свойства {@code left}: {@code ОбозначитьСловоСОжидаемымТипомВТаблицеСлов} с запретом
         * непохожих слов — по типу свойства, самое похожее по имени; без типа — пусто.
         */
        private String pickRight(Local left, List<Word> rights)
        {
            if (left.types().isEmpty())
                return ""; //$NON-NLS-1$
            String type = left.types().get(0);
            List<Word> typed = new ArrayList<>();
            for (Word right : rights)
            {
                if (right.hasType(type))
                    typed.add(right);
            }
            Word best = bestWord(typed, rights.size(), "", left.name(), true, false, fuzzyCalls); //$NON-NLS-1$
            return best != null ? best.name() : ""; //$NON-NLS-1$
        }

        /** {@code <УдалитьВыбранныеЭлементы>}. */
        private void deleteSelected(int rating)
        {
            String element = newVariableName("Элемент"); //$NON-NLS-1$
            String text;
            // «Индекс» не подходит для массива
            if (isMethod("найти")) //$NON-NLS-1$
            {
                String index = newVariableName("Индекс"); //$NON-NLS-1$
                text = "Для " + index + " = 1 - " + parent + ".Количество() По 0 Цикл // Обратный обход\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "\t" + element + " = " + parent + "[-" + index + "];\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    + "\tЕсли <?> Тогда\n" //$NON-NLS-1$
                    + "\t\t" + parent + ".Удалить(-" + index + ");\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "\tКонецЕсли;\n" //$NON-NLS-1$
                    + "КонецЦикла;"; //$NON-NLS-1$
            }
            else
            {
                text = "МассивДляУдаления = Новый Массив; \n" //$NON-NLS-1$
                    + "Для Каждого " + element + " Из " + parent + " Цикл\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "\tЕсли <?> Тогда\n" //$NON-NLS-1$
                    + "\t\tМассивДляУдаления.Удалить(" + element + ");\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "\tКонецЕсли;\n" //$NON-NLS-1$
                    + "КонецЦикла;\n" //$NON-NLS-1$
                    + "Для Каждого " + element + " Из МассивДляУдаления Цикл\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "\t" + parent + ".Удалить(" + element + ");\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "КонецЦикла;"; //$NON-NLS-1$
            }
            generator("<УдалитьВыбранныеЭлементы>", rating, text, BslAddedWordProposal.Absorb.CONTEXT, -1, false, false); //$NON-NLS-1$
        }

        /** {@code <ОбойтиЭлементы>}. */
        private void iterate(int rating, String parentType)
        {
            String text;
            boolean reopen = false;
            if ("РезультатЗапроса".equals(parentType) && facts.queryTotals() != null) //$NON-NLS-1$
                text = queryResultTraversal(facts.queryTotals());
            else if ("ТабличныйДокумент".equals(parentType)) //$NON-NLS-1$
                text = "Для НомерСтроки = 1 По " + parent + ".ВысотаТаблицы Цикл\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "\tДля НомерКолонки = 1 По " + parent + ".ШиринаТаблицы Цикл\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "\t\tЯчейка = " + parent + ".Область(НомерСтроки, НомерКолонки);\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "\t\t<?>\n" //$NON-NLS-1$
                    + "\tКонецЦикла;\n" //$NON-NLS-1$
                    + "КонецЦикла;"; //$NON-NLS-1$
            else if ("ТекстовыйДокумент".equals(parentType)) //$NON-NLS-1$
                text = "Для НомерСтроки = 1 По " + parent + ".КоличествоСтрок() Цикл\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "\tСтрока = " + parent + ".ПолучитьСтроку(НомерСтроки);\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "КонецЦикла;"; //$NON-NLS-1$
            else
            {
                text = "Для Каждого <?> Из " + parent + " Цикл\nКонецЦикла;"; //$NON-NLS-1$ //$NON-NLS-2$
                reopen = true;
            }
            generator("<ОбойтиЭлементы>", rating, text, BslAddedWordProposal.Absorb.CONTEXT, -1, reopen, false); //$NON-NLS-1$
        }

        /**
         * Обход результата запроса по группировкам итогов: уровень на каждую колонку {@code ИТОГИ ПО}, последний —
         * детальные записи. Отступ вложенных уровней — табуляции (отступ строки добавляет вставка, как форматирование ИР).
         */
        private String queryResultTraversal(List<String> totals)
        {
            String result = NESTED_SELECTION;
            String previousName = parent;
            for (int level = 0; level <= totals.size(); level++)
            {
                String grouping;
                String name;
                if (level == totals.size())
                {
                    grouping = ""; //$NON-NLS-1$
                    name = "ВыборкаДетальные"; //$NON-NLS-1$
                }
                else
                {
                    String column = titleCase(totals.get(level));
                    name = "Выборка" + column; //$NON-NLS-1$
                    grouping = "\"" + column + "\""; //$NON-NLS-1$ //$NON-NLS-2$
                }
                String shift = "\t".repeat(level); //$NON-NLS-1$
                String levelText = name + " = " + previousName + ".Выбрать(ОбходРезультатаЗапроса.ПоГруппировкам, " //$NON-NLS-1$ //$NON-NLS-2$
                    + grouping + ");\n" //$NON-NLS-1$
                    + shift + "Пока " + name + ".Следующий() Цикл\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + shift + "\t" + NESTED_SELECTION + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + shift + "КонецЦикла;"; //$NON-NLS-1$
                result = result.replace(NESTED_SELECTION, levelText);
                previousName = name;
            }
            return result.replace(NESTED_SELECTION, "<?>"); //$NON-NLS-1$
        }

        private static final String NESTED_SELECTION = "<ВложеннаяВыборка>"; //$NON-NLS-1$

        /** {@code ТРег}: первая буква каждого слова прописная, остальные строчные. */
        private static String titleCase(String text)
        {
            StringBuilder result = new StringBuilder(text.length());
            boolean wordStart = true;
            for (int i = 0; i < text.length(); i++)
            {
                char c = text.charAt(i);
                result.append(wordStart ? Character.toUpperCase(c) : Character.toLowerCase(c));
                wordStart = !Character.isLetterOrDigit(c);
            }
            return result.toString();
        }

        /** {@code <ПоследнийЭлемент>}. */
        private void lastElement(int rating)
        {
            boolean bound = words.containsKey("вграница"); //$NON-NLS-1$
            String text = parent + "[" + parent + "." + (bound ? "ВГраница()" : "Количество() - 1") + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            generator("<ПоследнийЭлемент>", rating, text, BslAddedWordProposal.Absorb.CONTEXT, -1, false, false); //$NON-NLS-1$
        }

        /** {@code <УдалитьИзКоллекции>}: {@code ТипЗначения} строки слова — тип строки контекста (родителя). */
        private void removeFromCollection(int rating, String parentType)
        {
            String text = "СтрокаДереваЗначений".equals(parentType) //$NON-NLS-1$
                ? parent + ".Родитель.Строки.Удалить(" + parent + ");" //$NON-NLS-1$ //$NON-NLS-2$
                : parent + ".Владелец().Удалить(" + parent + ");"; //$NON-NLS-1$ //$NON-NLS-2$
            generator("<УдалитьИзКоллекции>", rating, text, BslAddedWordProposal.Absorb.CONTEXT, -1, false, false); //$NON-NLS-1$
        }

        /** {@code <НайтиДобавитьСтроку>}. */
        private void findOrAddRow(int rating)
        {
            String filter = newVariableName("Отбор"); //$NON-NLS-1$
            String found = newVariableName("Найденные"); //$NON-NLS-1$
            String row = newVariableName("Строка"); //$NON-NLS-1$
            String text = filter + " = Новый Структура(\"<?>\", );\n" //$NON-NLS-1$
                + found + " = " + parent + ".НайтиСтроки(" + filter + ");\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "Если " + found + ".Количество() > 0 Тогда\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "\t" + row + " = " + found + "[0];\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "Иначе\n" //$NON-NLS-1$
                + "\t" + row + " = " + parent + ".Добавить();\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "\tЗаполнитьЗначенияСвойств(" + row + ", " + filter + ");\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "КонецЕсли;\n"; //$NON-NLS-1$
            generator("<НайтиДобавитьСтроку>", rating, text, BslAddedWordProposal.Absorb.CONTEXT, -1, true, false); //$NON-NLS-1$
        }

        /** {@code <СоздатьПеременные>}: переменная на каждый ключ структуры. */
        private void createVariablesFromStructure(int rating)
        {
            List<String> keys = new ArrayList<>();
            for (Word word : words.values())
            {
                if (!word.method() && !added.containsKey(word.key()))
                    keys.add(word.name());
            }
            keys.sort(String.CASE_INSENSITIVE_ORDER);
            List<String> lines = new ArrayList<>();
            for (String key : keys)
                lines.add(key + " = " + parent + "." + key + ";"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            generator("<СоздатьПеременные>", rating, String.join("\n", lines), BslAddedWordProposal.Absorb.CONTEXT, -1, //$NON-NLS-1$ //$NON-NLS-2$
                false, false);
        }

        // ---- Вспомогательное -------------------------------------------------------------------------

        private boolean isMethod(String key)
        {
            Word word = words.get(key);
            return word != null && word.method() && !added.containsKey(key);
        }

        /** Метод платформы ({@code Определение = Предопределенный}). */
        private boolean isPlatformMethod(String key)
        {
            ICompletionProposal proposal = proposals.get(key);
            return isMethod(key) && proposal != null
                && proposalInfo(proposal) instanceof com._1c.g5.v8.dt.mcore.Method method
                && BslModuleSpellCheckHook.isPlatformElement(method);
        }

        /** В списке есть слово метаданных ({@code Определение = "Метаданные"}). */
        private boolean hasProjectMember()
        {
            for (ICompletionProposal proposal : list)
            {
                if (proposalInfo(proposal) instanceof EObject element && !BslModuleSpellCheckHook.isPlatformElement(element))
                    return true;
            }
            return false;
        }

        private String lineText(int number)
        {
            try
            {
                IRegion info = document.getLineInformation(number);
                return document.get(info.getOffset(), info.getLength());
            }
            catch (Exception e)
            {
                return ""; //$NON-NLS-1$
            }
        }

        /** {@code ЛиСтрокаКодаСВыражениями} встроенного языка. */
        private static boolean hasExpressions(String text)
        {
            String clean = lower(text.stripLeading());
            return !clean.equals("если ложь") && !clean.equals("если истина") && !clean.equals("исключение") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                && !clean.equals("попытка") && !clean.equals("конеццикла;") && !clean.equals("конецесли;") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                && !clean.equals("конецпопытки;") && !left(clean, 2).equals("//") //$NON-NLS-1$ //$NON-NLS-2$
                // Как в ИР: два символа сравниваются с одним — отсекается только строка «#»
                && !left(clean, 2).equals("#"); //$NON-NLS-1$
        }

        /** {@code КопируемыйОператорСравненияДляБулеваОператора}. */
        private static String copyOperator(String firstWordLower)
        {
            if ("или".equals(firstWordLower)) //$NON-NLS-1$
                return "="; //$NON-NLS-1$
            if ("и".equals(firstWordLower)) //$NON-NLS-1$
                return "<>"; //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        }

        /** {@code ПредшествующийТокен} по тексту перед выражением (хвост из цифр отрезается). */
        private static String tokenOf(String preceding)
        {
            int end = preceding.length();
            while (end > 0 && Character.isDigit(preceding.charAt(end - 1)))
                end--;
            String text = stripEnd(preceding.substring(0, end));
            if (text.endsWith("<>") || text.endsWith("//")) //$NON-NLS-1$ //$NON-NLS-2$
                return text.substring(text.length() - 2);
            return text.isEmpty() ? "" : text.substring(text.length() - 1); //$NON-NLS-1$
        }

        private static boolean isEnumManager(String typeLower, String enumLower)
        {
            String compact = typeLower.replace(" ", ""); //$NON-NLS-1$ //$NON-NLS-2$
            return compact.equals(enumLower) || compact.equals("перечисление" + enumLower) //$NON-NLS-1$
                || compact.equals("перечисление" + enumLower + "менеджер"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static boolean in(String value, String... options)
        {
            for (String option : options)
            {
                if (option.equals(value))
                    return true;
            }
            return false;
        }

        private static boolean containsAny(String text, String chars)
        {
            for (int i = 0; i < chars.length(); i++)
            {
                if (text.indexOf(chars.charAt(i)) >= 0)
                    return true;
            }
            return false;
        }

        private static String leadingWhitespace(String text)
        {
            int i = 0;
            while (i < text.length() && Character.isWhitespace(text.charAt(i)))
                i++;
            return text.substring(0, i);
        }

        /** {@code ПервыйФрагментЛкс(Текст, Разделитель)}: до первого вхождения или весь текст. */
        private static String firstFragmentText(String text, String separator)
        {
            int index = separator.isEmpty() ? -1 : text.indexOf(separator);
            return index >= 0 ? text.substring(0, index) : text;
        }

        /** {@code ТекстБезПервогоФрагментаЛкс}: после первого вхождения; нет — пусто. */
        private static String textAfterFirst(String text, String separator)
        {
            if (separator.isEmpty())
                return text;
            int index = text.indexOf(separator);
            return index >= 0 ? text.substring(index + separator.length()) : ""; //$NON-NLS-1$
        }

        /** {@code ТекстБезПоследнегоФрагментаЛкс(Текст, Маркер)}: до последнего вхождения; нет — пусто. */
        private static String withoutLastMarker(String text, String marker)
        {
            int index = text.lastIndexOf(marker);
            return index >= 0 ? text.substring(0, index) : ""; //$NON-NLS-1$
        }

        /** {@code ТекстМеждуМаркерамиЛкс(…, Ложь, ВключатьМаркеры)}: нет любого маркера — {@code null}. */
        private static String textBetween(String text, String start, String end, boolean includeMarkers)
        {
            int startIndex = text.indexOf(start);
            if (startIndex < 0)
                return null;
            String rest = text.substring(startIndex + start.length());
            int endIndex = rest.indexOf(end);
            if (endIndex < 0)
                return null;
            return includeMarkers ? start + rest.substring(0, endIndex + end.length()) : rest.substring(0, endIndex);
        }

        private static boolean endsWithIgnoreCase(String text, String suffix)
        {
            return text.length() >= suffix.length()
                && text.regionMatches(true, text.length() - suffix.length(), suffix, 0, suffix.length());
        }
    }

    /** Слова списка по имени в нижнем регистре; первое вхождение имени побеждает. */
    private static Map<String, Word> words(ICompletionProposal[] list, Map<String, List<String>> variableTypes,
        Map<String, String> predefinedTypes)
    {
        Map<String, Word> words = new LinkedHashMap<>();
        for (ICompletionProposal proposal : list)
        {
            String name = SmartContentAssistProcessor.filterMatchName(proposal);
            String key = lower(name);
            if (key.isEmpty() || words.containsKey(key))
                continue;
            List<String> local = variableTypes.get(key);
            boolean method = IrBslExpressionHtmlSupport.KIND_METHOD.equals(
                BslCompletionSideHintResolver.resolveElementKind(proposal));
            // Значения, которые EDT подставила под ожидаемый тип места, — слова этого места, как локальные
            boolean placeValue = predefinedTypeIn(proposal, predefinedTypes) != null;
            words.put(key, new Word(key, name, local != null ? local : proposalTypes(proposal, predefinedTypes),
                method, local != null || placeValue));
        }
        return words;
    }

    /** Типы строки списка: свойство и метод платформы — из модели, остальное — из подписи строки. */
    private static List<String> proposalTypes(ICompletionProposal proposal, Map<String, String> predefinedTypes)
    {
        String contextType = predefinedTypeIn(proposal, predefinedTypes);
        if (contextType != null)
            return new ArrayList<>(List.of(contextType));
        Object info = proposalInfo(proposal);
        if (info instanceof Property property && !property.eIsProxy())
            return typeNames(property.getTypes());
        if (info instanceof com._1c.g5.v8.dt.mcore.Method method && !method.eIsProxy()
            && !method.getRetValType().isEmpty())
            return typeNames(method.getRetValType());
        String predefinedType = SmartCompletionProposal.predefinedValueType(proposal);
        if (predefinedType != null)
            return new ArrayList<>(List.of(predefinedType));
        return displayTypes(proposal.getDisplayString());
    }

    private static Object proposalInfo(ICompletionProposal proposal)
    {
        ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
        return raw instanceof ConfigurableCompletionProposal configurable
            ? Global.getField(configurable, "additionalProposalInfo") : null; //$NON-NLS-1$
    }

    /** {@code Имя(…) : <А, Б> ~ Родитель} → {@code [А, Б]}; {@code ?} отбрасывается. */
    private static List<String> displayTypes(String display)
    {
        List<String> types = new ArrayList<>();
        if (display == null)
            return types;
        int owner = display.indexOf(" ~ "); //$NON-NLS-1$
        String head = owner >= 0 ? display.substring(0, owner) : display;
        int colon = head.lastIndexOf(" : "); //$NON-NLS-1$
        if (colon < 0 || head.lastIndexOf(')') > colon)
            return types;
        String text = head.substring(colon + 3).trim();
        if (text.startsWith("<") && text.endsWith(">")) //$NON-NLS-1$ //$NON-NLS-2$
            text = text.substring(1, text.length() - 1);
        for (String part : text.split(",")) //$NON-NLS-1$
        {
            String type = part.trim();
            if (!type.isEmpty() && !FunctionReturnTypes.UNKNOWN.equals(type))
                types.add(type);
        }
        return types;
    }

    /**
     * Ресурс модуля на время чтения модели: через него загружаются ссылки на типы, ещё не загруженные
     * (типы параметров платформенных методов ссылаются на платформенные типы другого ресурса).
     */
    private static final ThreadLocal<org.eclipse.emf.ecore.resource.Resource> READ_RESOURCE = new ThreadLocal<>();

    private static String typeName(TypeItem item)
    {
        if (item != null && item.eIsProxy() && READ_RESOURCE.get() != null)
        {
            EObject resolved = EcoreUtil.resolve(item, READ_RESOURCE.get());
            if (resolved instanceof TypeItem loaded)
                item = loaded;
        }
        if (item == null || item.eIsProxy())
            return ""; //$NON-NLS-1$
        String name = item instanceof DuallyNamedElement dually && dually.getNameRu() != null
            && !dually.getNameRu().isEmpty() ? dually.getNameRu() : item.getName();
        return name != null ? SmartContentAssistProcessor.localizeAssistTypeSegment(name) : ""; //$NON-NLS-1$
    }

    private static List<String> typeNames(List<TypeItem> items)
    {
        List<String> names = new ArrayList<>();
        if (items == null)
            return names;
        for (TypeItem item : items)
        {
            String name = typeName(item);
            if (!name.isEmpty() && !containsIgnoreCase(names, name))
                names.add(name);
        }
        return names;
    }

    /** Тип по имени — в список ожидаемых типов (дальше типы сравниваются только по именам). */
    private static void addTypeByName(List<TypeItem> types, String name)
    {
        types.add(namedType(name));
    }

    private static TypeItem namedType(String name)
    {
        Type type = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createType();
        type.setName(name);
        type.setNameRu(name);
        return type;
    }

    /** Правила имён без метаданных: платформенный тип с тем же именем, что у переменной. */
    private static final TypeNameFromVariableName.Catalog NAME_CATALOG = new TypeNameFromVariableName.Catalog(null,
        null, BslExpectedTypePremiums::platformTypeName);

    private static String platformTypeName(String name)
    {
        try
        {
            IEObjectProvider provider = IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM,
                Version.LATEST);
            return provider != null && provider.getEObjectDescription(name) != null ? name : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Статистика выбора слов
    // ------------------------------------------------------------------------------------------

    private static final Map<ContentAssistant, ChoiceListener> CHOICE_LISTENERS = new WeakHashMap<>();

    /** Запись выбора слов окна {@code assistant} в статистику (UI-поток). */
    static void installChoiceListener(ContentAssistant assistant)
    {
        if (assistant == null || CHOICE_LISTENERS.containsKey(assistant))
            return;
        ChoiceListener listener = new ChoiceListener();
        CHOICE_LISTENERS.put(assistant, listener);
        assistant.addCompletionListener(listener);
    }

    static void uninstallChoiceListener(ContentAssistant assistant)
    {
        ChoiceListener listener = assistant != null ? CHOICE_LISTENERS.remove(assistant) : null;
        if (listener != null)
            assistant.removeCompletionListener(listener);
    }

    /** {@code ОбновитьСтатистикуВыбораВСтрокеСлова} при вставке слова из списка. */
    private static final class ChoiceListener implements ICompletionListener, ICompletionListenerExtension2
    {
        @Override
        public void assistSessionStarted(ContentAssistEvent event)
        {
            captureAssistSelection();
        }

        @Override
        public void assistSessionEnded(ContentAssistEvent event)
        {
        }

        @Override
        public void selectionChanged(ICompletionProposal proposal, boolean smartToggle)
        {
        }

        @Override
        public void applied(ICompletionProposal proposal)
        {
            try
            {
                BslQueryLiteralSupport.QueryChoice query = proposal != null ? BslQueryLiteralSupport.choiceOf(proposal) : null;
                if (query != null)
                {
                    recordQueryChoice(proposal, query);
                    return;
                }
                Snapshot snapshot = active;
                if (snapshot == null || snapshot.scene().statisticsContext() == null || proposal == null)
                    return;
                // Новые слова: копии строк и <СоздатьПеременную> в статистику не идут, прочие — конструкции
                // глобального контекста
                if (proposal instanceof BslAddedWordProposal word)
                {
                    if (word.isStatistical() || word.isProjectWord() && snapshot.project() == null)
                        return;
                    ChoiceStatistics.record(word.isProjectWord() ? snapshot.project() : null, GLOBAL_CONTEXT,
                        word.getWord(), word.isMethod(), true, MODULE_LANGUAGE);
                    return;
                }
                Object info = proposalInfo(proposal);
                // Локальные переменные в статистику не идут
                if (FunctionReturnTypes.variableTargetOf(info) != null)
                    return;
                String word = SmartContentAssistProcessor.filterMatchName(proposal);
                if (word.isEmpty())
                    return;
                boolean method = IrBslExpressionHtmlSupport.KIND_METHOD.equals(
                    BslCompletionSideHintResolver.resolveElementKind(proposal));
                List<String> types = proposalTypes(proposal, snapshot.scene().predefinedTypes());
                // Конструкции и литералы — в глобальный контекст
                String context = info == null && types.isEmpty() && !method ? GLOBAL_CONTEXT
                    : snapshot.scene().statisticsContext();
                boolean projectWord = context.indexOf('.') >= 0
                    || !isPlatformWord(proposal, info, snapshot.scene().predefinedTypes());
                // Без проекта слову проекта места нет: в таблицу рабочей области оно не пишется
                if (projectWord && snapshot.project() == null)
                    return;
                ChoiceStatistics.record(projectWord ? snapshot.project() : null, context, word, method, types.isEmpty(),
                    MODULE_LANGUAGE);
            }
            catch (RuntimeException e)
            {
                Global.tempLog(LOG, "choice error " + e); //$NON-NLS-1$
            }
        }

        /** Слово языка запросов: своя таблица языка, деление на платформу и проект — {@link BslQueryLiteralSupport#choiceOf}. */
        private static void recordQueryChoice(ICompletionProposal proposal, BslQueryLiteralSupport.QueryChoice query)
        {
            String word = SmartContentAssistProcessor.filterMatchName(proposal);
            if (word.isEmpty())
                return;
            boolean method = IrBslExpressionHtmlSupport.KIND_METHOD.equals(
                BslCompletionSideHintResolver.resolveElementKind(proposal));
            boolean projectWord = query.context().indexOf('.') >= 0 || !query.platformWord();
            if (projectWord && query.project() == null)
                return;
            // Без значения — ключевые слова: слово платформы, не функция
            ChoiceStatistics.record(projectWord ? query.project() : null, query.context(), word, method,
                query.platformWord() && !method, QUERY_LANGUAGE);
        }
    }

    /**
     * Слово описано в платформе: элемент описания платформы, ключевое слово, шаблон или значение системного
     * перечисления. Остальное (метаданные, методы модулей, колонки, предопределённые значения) — слово проекта.
     */
    private static boolean isPlatformWord(ICompletionProposal proposal, Object info, Map<String, String> predefinedTypes)
    {
        if (info instanceof EObject element)
            return BslModuleSpellCheckHook.isPlatformElement(element);
        if (info != null)
            return false;
        if (SmartContentAssistProcessor.isKeywordProposal(proposal)
            || SmartContentAssistProcessor.isTemplateProposal(proposal))
            return true;
        String valueType = predefinedTypeIn(proposal, predefinedTypes);
        if (valueType == null)
            valueType = SmartCompletionProposal.predefinedValueType(proposal);
        return valueType != null && valueType.indexOf('.') < 0;
    }

    /**
     * Статистика выбора слов: платформенная (на рабочую область, в каталоге состояния плагина) и
     * проектная (слова метаданных проекта, в рабочем каталоге проекта). Ключ строки — контекст, слово,
     * метод ли, без значения ли и язык ({@code ЯзыкПрограммы} ИР); вес — число выборов.
     */
    private static final class ChoiceStatistics
    {
        private static final String FILE_NAME = "assist-choice-statistics.tsv"; //$NON-NLS-1$
        private static final long SAVE_DELAY_MS = 2000L;
        /**
         * Файл → строки {@code контекст\tслово\tметод\tбезЗначения[\tязык] → вес}; язык модуля не пишется
         * (строки до появления языка — модуль).
         */
        private static final Map<File, Map<String, Integer>> TABLES = new HashMap<>();
        private static final Set<File> DIRTY = new HashSet<>();
        private static Job saveJob;

        /** Веса слов контекста языка {@code language} по обеим таблицам: ключ {@code m:}/{@code p:} + имя. */
        static synchronized Map<String, Integer> weights(IProject project, String context, boolean withValueOnly,
            int language)
        {
            Map<String, Integer> result = new HashMap<>();
            for (File file : new File[] { platformFile(), projectFile(project) })
            {
                if (file == null)
                    continue;
                for (Map.Entry<String, Integer> row : table(file).entrySet())
                {
                    String[] parts = row.getKey().split("\t", -1); //$NON-NLS-1$
                    if (parts.length < 4 || !parts[0].equalsIgnoreCase(context) || language(parts) != language)
                        continue;
                    if (withValueOnly && "1".equals(parts[3])) //$NON-NLS-1$
                        continue;
                    result.merge(("1".equals(parts[2]) ? "m:" : "p:") + lower(parts[1]), row.getValue(), Integer::sum); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            }
            return result;
        }

        static synchronized void record(IProject project, String context, String word, boolean method,
            boolean withoutValue, int language)
        {
            File file = project != null ? projectFile(project) : platformFile();
            if (file == null)
                return;
            table(file).merge(key(context, word, method, withoutValue, language), 1, Integer::sum);
            DIRTY.add(file);
            scheduleSave();
        }

        private static String key(String context, String word, boolean method, boolean withoutValue, int language)
        {
            String key = context + '\t' + word + '\t' + (method ? '1' : '0') + '\t' + (withoutValue ? '1' : '0');
            return language == MODULE_LANGUAGE ? key : key + '\t' + language;
        }

        private static int language(String[] parts)
        {
            try
            {
                return parts.length > 4 ? Integer.parseInt(parts[4]) : MODULE_LANGUAGE;
            }
            catch (NumberFormatException e)
            {
                return -1;
            }
        }

        /** Файл таблицы: {@code project == null} — платформенная (рабочая область), иначе проектная. */
        static File file(IProject project)
        {
            return project != null ? projectFile(project) : platformFile();
        }

        /** Строки таблицы {@code file} по убыванию веса. */
        static synchronized List<ChoiceRow> rows(File file)
        {
            List<ChoiceRow> result = new ArrayList<>();
            if (file == null || !file.isFile() && !TABLES.containsKey(file))
                return result;
            for (Map.Entry<String, Integer> row : table(file).entrySet())
            {
                String[] parts = row.getKey().split("\t", -1); //$NON-NLS-1$
                if (parts.length < 4)
                    continue;
                result.add(new ChoiceRow(parts[0], parts[1], "1".equals(parts[2]), "1".equals(parts[3]), //$NON-NLS-1$ //$NON-NLS-2$
                    language(parts), row.getValue().intValue()));
            }
            result.sort(Comparator.comparingInt(ChoiceRow::count).reversed());
            return result;
        }

        /** Число выборов строки; {@code count <= 0} — строка удаляется. */
        static synchronized void setCount(File file, ChoiceRow row, int count)
        {
            if (file == null)
                return;
            String key = key(row.context(), row.word(), row.method(), row.withoutValue(), row.language());
            if (count > 0)
                table(file).put(key, Integer.valueOf(count));
            else
                table(file).remove(key);
            DIRTY.add(file);
            scheduleSave();
        }

        static synchronized void reset(File file)
        {
            if (file == null)
                return;
            table(file).clear();
            DIRTY.add(file);
            scheduleSave();
        }

        private static File platformFile()
        {
            Activator activator = Activator.getDefault();
            IPath location = activator != null ? activator.getStateLocation() : null;
            return location != null ? location.append(FILE_NAME).toFile() : null;
        }

        private static File projectFile(IProject project)
        {
            if (project == null || !project.isAccessible())
                return null;
            IPath location = project.getWorkingLocation(Activator.PLUGIN_ID);
            return location != null ? location.append(FILE_NAME).toFile() : null;
        }

        private static Map<String, Integer> table(File file)
        {
            return TABLES.computeIfAbsent(file, ChoiceStatistics::load);
        }

        private static Map<String, Integer> load(File file)
        {
            Map<String, Integer> rows = new HashMap<>();
            if (!file.isFile())
                return rows;
            try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    int tab = line.lastIndexOf('\t');
                    if (tab <= 0)
                        continue;
                    try
                    {
                        rows.put(line.substring(0, tab), Integer.valueOf(line.substring(tab + 1)));
                    }
                    catch (NumberFormatException ignored)
                    {
                        // испорченная строка пропускается
                    }
                }
            }
            catch (IOException e)
            {
                Global.logError(LOG, "загрузка статистики выбора " + file, e); //$NON-NLS-1$
            }
            return rows;
        }

        private static void scheduleSave()
        {
            if (saveJob == null)
            {
                saveJob = new Job("Комфорт: статистика выбора слов") //$NON-NLS-1$
                {
                    @Override
                    protected IStatus run(IProgressMonitor monitor)
                    {
                        save();
                        return Status.OK_STATUS;
                    }
                };
                saveJob.setSystem(true);
            }
            saveJob.schedule(SAVE_DELAY_MS);
        }

        private static synchronized void save()
        {
            for (File file : DIRTY)
            {
                Map<String, Integer> rows = TABLES.get(file);
                if (rows == null)
                    continue;
                try
                {
                    file.getParentFile().mkdirs();
                    try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))
                    {
                        for (Map.Entry<String, Integer> row : rows.entrySet())
                            writer.write(row.getKey() + '\t' + row.getValue() + '\n');
                    }
                }
                catch (IOException e)
                {
                    Global.logError(LOG, "сохранение статистики выбора " + file, e); //$NON-NLS-1$
                }
            }
            DIRTY.clear();
        }
    }

    /** Строка статистики выбора слов: контекст, слово, признаки, язык, вес. */
    private record ChoiceRow(String context, String word, boolean method, boolean withoutValue, int language,
        int count)
    {
        /** Та же строка таблицы (без учёта веса). */
        boolean sameKey(ChoiceRow other)
        {
            return context.equals(other.context) && word.equals(other.word) && method == other.method
                && withoutValue == other.withoutValue && language == other.language;
        }
    }

    /**
     * Окно «Статистика выбора слов автодополнения»: {@code project == null} — часть рабочей области (ссылка
     * на странице «Редактор кода»), иначе — часть проекта (ссылка в свойствах проекта → «Комфорт»).
     */
    static void openChoiceStatistics(Shell parent, IProject project)
    {
        new ChoiceStatisticsDialog(parent, project).open();
    }

    private static final class ChoiceStatisticsDialog extends Dialog
    {
        private static final String SETTINGS_SECTION = "AssistChoiceStatisticsDialog"; //$NON-NLS-1$
        private static final String KEY_COL_ORDER = "columnOrder"; //$NON-NLS-1$
        private static final String KEY_COL_FILL_MODE = "colFillMode"; //$NON-NLS-1$
        private static final int RESET_ID = IDialogConstants.CLIENT_ID + 1;
        private static final int DELETE_ID = IDialogConstants.CLIENT_ID + 2;
        private static final int COUNT_COLUMN = 5;
        private static final String[] HEADERS = { "Язык", "Контекст", "Слово", "Метод", "Без значения", "Выборов" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        private static final int[] DEFAULT_WIDTHS = { 70, 200, 220, 60, 90, 70 };
        private static final String[] WIDTH_KEYS = { "colLanguage", "colContext", "colWord", "colMethod", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "colWithoutValue", "colCount" }; //$NON-NLS-1$ //$NON-NLS-2$

        private final IProject project;
        private final File file;
        private TableViewer viewer;
        private Table table;
        private TableColumn[] columns;
        private FormTableInteraction interaction;
        private Text fileText;

        ChoiceStatisticsDialog(Shell parentShell, IProject project)
        {
            super(parentShell);
            setShellStyle(getShellStyle() | SWT.RESIZE);
            this.project = project;
            this.file = ChoiceStatistics.file(project);
        }

        @Override
        protected void configureShell(Shell newShell)
        {
            super.configureShell(newShell);
            String title = project != null
                ? "Статистика выбора слов автодополнения проекта «" + project.getName() + "»" //$NON-NLS-1$ //$NON-NLS-2$
                : "Статистика выбора слов автодополнения рабочей области"; //$NON-NLS-1$
            newShell.setText(Global.withPluginWindowTitle(title));
        }

        @Override
        protected Point getInitialSize()
        {
            return dialogSettings().get("DIALOG_WIDTH") == null ? new Point(800, 520) : super.getInitialSize(); //$NON-NLS-1$
        }

        @Override
        protected IDialogSettings getDialogBoundsSettings()
        {
            return dialogSettings();
        }

        @Override
        protected Control createDialogArea(Composite parent)
        {
            Composite area = (Composite)super.createDialogArea(parent);
            area.setLayout(new GridLayout(1, false));

            fileText = new Text(area, SWT.BORDER | SWT.READ_ONLY);
            fileText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            fileText.setToolTipText(TooltipText.wrap(fileText, project != null
                ? "Файл статистики слов метаданных проекта" //$NON-NLS-1$
                : "Файл статистики слов платформы (общий для всех проектов рабочей области)")); //$NON-NLS-1$

            Composite tableHost = new Composite(area, SWT.NONE);
            tableHost.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            TableColumnLayout tableLayout = new TableColumnLayout();
            tableHost.setLayout(tableLayout);
            viewer = new TableViewer(tableHost, SWT.FULL_SELECTION | SWT.MULTI | SWT.BORDER);
            table = viewer.getTable();
            table.setHeaderVisible(true);
            ThemeAwareColors.applyGridLines(table);

            IDialogSettings settings = dialogSettings();
            boolean hasSavedWidths = FormTableColumnState.hasSavedColumnWidths(settings, KEY_COL_FILL_MODE, WIDTH_KEYS);
            columns = new TableColumn[HEADERS.length];
            for (int i = 0; i < HEADERS.length; i++)
            {
                int index = i;
                TableViewerColumn viewerColumn = new TableViewerColumn(viewer, i == HEADERS.length - 1 ? SWT.RIGHT : SWT.NONE);
                columns[i] = viewerColumn.getColumn();
                columns[i].setText(HEADERS[i]);
                viewerColumn.setLabelProvider(new ColumnLabelProvider()
                {
                    @Override
                    public String getText(Object element)
                    {
                        return element instanceof ChoiceRow row ? cellText(row, index) : ""; //$NON-NLS-1$
                    }
                });
                tableLayout.setColumnData(columns[i], new ColumnPixelData(
                    FormTableColumnState.readWidth(settings, WIDTH_KEYS[i], DEFAULT_WIDTHS[i], 40), true, true));
                if (i == COUNT_COLUMN)
                    viewerColumn.setEditingSupport(new CountEditingSupport());
            }
            viewer.setContentProvider(ArrayContentProvider.getInstance());
            // Редактор ячейки — только по команде (двойной щелчок, F2, Enter), не по одиночному щелчку
            TableViewerEditor.create(viewer, new ColumnViewerEditorActivationStrategy(viewer)
            {
                @Override
                protected boolean isEditorActivationEvent(ColumnViewerEditorActivationEvent event)
                {
                    return event.eventType == ColumnViewerEditorActivationEvent.PROGRAMMATIC;
                }
            }, ColumnViewerEditor.DEFAULT);

            interaction = new FormTableInteraction(table, viewer,
                (item, column) -> item.getData() instanceof ChoiceRow row ? cellText(row, column) : ""); //$NON-NLS-1$
            FormTableColumnState.loadOrder(settings, KEY_COL_ORDER, table);
            interaction.install(hasSavedWidths);
            interaction.setHeaderTooltipExtra(columns[COUNT_COLUMN],
                "Изменить — двойной щелчок, F2 или Enter. 0 удаляет строку."); //$NON-NLS-1$
            reload(null);
            interaction.enableHeaderSort(settings);

            table.addListener(SWT.MouseDoubleClick, event ->
            {
                if (interaction.activeColumn() == COUNT_COLUMN)
                    editActiveCount();
            });
            table.addListener(SWT.KeyDown, event ->
            {
                if (event.keyCode == SWT.DEL && event.stateMask == 0)
                {
                    deleteSelected();
                    event.doit = false;
                }
                else if ((event.keyCode == SWT.F2 || event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)
                    && event.stateMask == 0)
                {
                    editActiveCount();
                    event.doit = false;
                }
            });
            table.addListener(SWT.Selection, event -> updateButtons());
            return area;
        }

        /** Перечитать таблицу и выделить строку {@code keep} (по ключу, вес мог измениться). */
        private void reload(ChoiceRow keep)
        {
            List<ChoiceRow> rows = ChoiceStatistics.rows(file);
            viewer.setInput(rows);
            fileText.setText(file == null ? "Файл статистики недоступен" //$NON-NLS-1$
                : rows.isEmpty() ? file.getAbsolutePath() + " — статистика пуста" //$NON-NLS-1$
                : file.getAbsolutePath());
            if (keep != null)
            {
                for (ChoiceRow row : rows)
                {
                    if (row.sameKey(keep))
                    {
                        viewer.setSelection(new StructuredSelection(row), true);
                        break;
                    }
                }
            }
            updateButtons();
        }

        private void updateButtons()
        {
            Button resetButton = getButton(RESET_ID);
            if (resetButton != null)
                resetButton.setEnabled(table.getItemCount() > 0);
            Button deleteButton = getButton(DELETE_ID);
            if (deleteButton != null)
                deleteButton.setEnabled(!viewer.getStructuredSelection().isEmpty());
        }

        private void editActiveCount()
        {
            TableItem item = interaction.selectedItem();
            if (item != null && !item.isDisposed() && item.getData() instanceof ChoiceRow row)
                viewer.editElement(row, COUNT_COLUMN);
        }

        private void deleteSelected()
        {
            List<ChoiceRow> selected = new ArrayList<>();
            for (Object element : viewer.getStructuredSelection().toList())
            {
                if (element instanceof ChoiceRow row)
                    selected.add(row);
            }
            if (selected.isEmpty())
                return;
            // Строка, которая встанет на место удалённых
            int index = table.getSelectionIndex();
            for (ChoiceRow row : selected)
                ChoiceStatistics.setCount(file, row, 0);
            reload(null);
            int count = table.getItemCount();
            if (count > 0)
            {
                Object next = table.getItem(Math.min(Math.max(index, 0), count - 1)).getData();
                viewer.setSelection(new StructuredSelection(next), true);
                updateButtons();
            }
        }

        /** Правка колонки «Выборов»: целое число, 0 — удалить строку. */
        private final class CountEditingSupport extends EditingSupport
        {
            private final TextCellEditor editor;

            CountEditingSupport()
            {
                super(viewer);
                editor = new TextCellEditor(table, SWT.RIGHT);
                editor.setValidator(value -> value instanceof String text && text.trim().matches("\\d{1,9}") //$NON-NLS-1$
                    ? null : "Нужно целое неотрицательное число"); //$NON-NLS-1$
            }

            @Override
            protected CellEditor getCellEditor(Object element)
            {
                return editor;
            }

            @Override
            protected boolean canEdit(Object element)
            {
                return element instanceof ChoiceRow;
            }

            @Override
            protected Object getValue(Object element)
            {
                return element instanceof ChoiceRow row ? Integer.toString(row.count()) : ""; //$NON-NLS-1$
            }

            @Override
            protected void setValue(Object element, Object value)
            {
                if (!(element instanceof ChoiceRow row) || !(value instanceof String text))
                    return;
                int count = Integer.parseInt(text.trim());
                if (count == row.count())
                    return;
                ChoiceStatistics.setCount(file, row, count);
                table.getDisplay().asyncExec(() ->
                {
                    if (!table.isDisposed())
                        reload(count > 0 ? row : null);
                });
            }
        }

        private static String cellText(ChoiceRow row, int column)
        {
            return switch (column)
            {
                case 0 -> row.language() == QUERY_LANGUAGE ? "Запрос" : "Модуль"; //$NON-NLS-1$ //$NON-NLS-2$
                case 1 -> row.context();
                case 2 -> row.word();
                case 3 -> row.method() ? "✓" : ""; //$NON-NLS-1$ //$NON-NLS-2$
                case 4 -> row.withoutValue() ? "✓" : ""; //$NON-NLS-1$ //$NON-NLS-2$
                default -> Integer.toString(row.count());
            };
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent)
        {
            Button resetButton = createButton(parent, RESET_ID, "Сбросить", false); //$NON-NLS-1$
            resetButton.setToolTipText(TooltipText.wrap(resetButton, project != null
                ? "Удалить статистику выбора слов этого проекта" //$NON-NLS-1$
                : "Удалить статистику выбора слов рабочей области. Статистика проектов не меняется.")); //$NON-NLS-1$
            resetButton.setEnabled(table.getItemCount() > 0);
            Button deleteButton = createButton(parent, DELETE_ID, "Удалить", false); //$NON-NLS-1$
            deleteButton.setToolTipText(TooltipText.wrap(deleteButton, "Удалить выделенные строки (Delete)")); //$NON-NLS-1$
            deleteButton.setEnabled(false);
            createButton(parent, IDialogConstants.CLOSE_ID, "Закрыть", false); //$NON-NLS-1$
        }

        @Override
        protected void buttonPressed(int buttonId)
        {
            if (buttonId == IDialogConstants.CLOSE_ID)
                close();
            else if (buttonId == RESET_ID)
            {
                if (MessageDialog.openQuestion(getShell(), Global.withPluginWindowTitle("Сброс статистики"), //$NON-NLS-1$
                    "Удалить всю статистику выбора слов в этой таблице? Порядок слов в списке автодополнения " //$NON-NLS-1$
                        + "перестанет учитывать прошлые выборы.")) //$NON-NLS-1$
                {
                    ChoiceStatistics.reset(file);
                    reload(null);
                }
            }
            else if (buttonId == DELETE_ID)
                deleteSelected();
            else
                super.buttonPressed(buttonId);
        }

        @Override
        public boolean close()
        {
            if (table != null && !table.isDisposed())
                FormTableColumnState.saveOrderAndWidths(dialogSettings(), KEY_COL_ORDER, KEY_COL_FILL_MODE,
                    interaction != null && interaction.isColumnsExactFill(), WIDTH_KEYS, columns, table);
            return super.close();
        }

        private static IDialogSettings dialogSettings()
        {
            IDialogSettings top = Activator.getDefault().getDialogSettings();
            IDialogSettings section = top.getSection(SETTINGS_SECTION);
            return section != null ? section : top.addNewSection(SETTINGS_SECTION);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Сверка нечёткого поиска с ИР
    // ------------------------------------------------------------------------------------------

    /**
     * Те же входы — в {@code мПлатформа.НечеткоеСравнениеСлов().StringSearch} подключённого ИР; оба
     * результата пишутся во временный лог {@value #FUZZY_LOG}. Без ИР — только результат Java.
     */
    private static void reconcileFuzzyWithIr(List<FuzzyCall> calls)
    {
        if (calls.isEmpty())
            return;
        List<FuzzyCall> sample = new ArrayList<>(calls.subList(0, Math.min(calls.size(), MAX_FUZZY_RECONCILES)));
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        BslXtextEditor editor = reloader != null ? reloader.getBslEditor() : null;
        Job job = new Job("Комфорт: сверка нечёткого поиска с ИР") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                IRSession session = null;
                try
                {
                    session = IrBslExpressionHtmlSupport.resolveConnectedSession(editor);
                }
                catch (RuntimeException e)
                {
                    Global.tempLog(FUZZY_LOG, "session error " + e); //$NON-NLS-1$
                }
                for (FuzzyCall call : sample)
                {
                    String irResult = "ИР не подключён"; //$NON-NLS-1$
                    if (session != null)
                        irResult = irStringSearch(session, call);
                    List<Integer> irIndices = parseIndices(irResult);
                    Global.tempLog(FUZZY_LOG, "search=" + call.search() + " minSmall=" + call.minPercentSmall() //$NON-NLS-1$ //$NON-NLS-2$
                        + " minBig=" + call.minPercentBig() + " candidates=" + call.candidates().size() //$NON-NLS-1$ //$NON-NLS-2$
                        + " java=" + names(call, call.result()) + " ir=" //$NON-NLS-1$ //$NON-NLS-2$
                        + (irIndices != null ? names(call, irIndices) : irResult)
                        + " same=" + (irIndices != null && new HashSet<>(irIndices).equals(new HashSet<>(call.result())))); //$NON-NLS-1$
                }
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    private static String irStringSearch(IRSession session, FuzzyCall call)
    {
        try
        {
            return session.executeOnComThread(() ->
            {
                Object irCache = session.getModule("ирКэш"); //$NON-NLS-1$
                Object platform = ComBridge.invoke(irCache, "Получить"); //$NON-NLS-1$
                Object fuzzy = ComBridge.invoke(platform, "НечеткоеСравнениеСлов"); //$NON-NLS-1$
                Object raw = ComBridge.invoke(fuzzy, "StringSearch", call.search(), //$NON-NLS-1$
                    String.join(",", call.candidates()), ",", Integer.valueOf(FUZZY_SMALL_LENGTH), //$NON-NLS-1$ //$NON-NLS-2$
                    Integer.valueOf(call.minPercentSmall()), Integer.valueOf(call.minPercentBig()));
                return ComBridge.toString(raw);
            });
        }
        catch (Exception e)
        {
            return "ошибка " + e; //$NON-NLS-1$
        }
    }

    /** Индексы через запятую; {@code null} — не индексы (ошибка). */
    private static List<Integer> parseIndices(String text)
    {
        List<Integer> indices = new ArrayList<>();
        if (text == null)
            return null;
        if (text.isBlank())
            return indices;
        for (String part : text.split(",")) //$NON-NLS-1$
        {
            try
            {
                indices.add(Integer.valueOf(part.trim()));
            }
            catch (NumberFormatException e)
            {
                return null;
            }
        }
        return indices;
    }

    private static String names(FuzzyCall call, List<Integer> indices)
    {
        List<String> names = new ArrayList<>();
        for (Integer index : indices)
            names.add(index.intValue() >= 0 && index.intValue() < call.candidates().size()
                ? call.candidates().get(index.intValue()) : "#" + index); //$NON-NLS-1$
        return names.toString();
    }

    // ------------------------------------------------------------------------------------------

    /**
     * {@code УстановитьРейтингВСтрокеСлова}: премия прибавляется; слово, которого нет в списке, не
     * учитывается. {@code autoOpen} — {@code ВключатьАвтоОткрытие} ИР.
     */
    private static void add(Premiums result, Map<String, Word> words, String key, int multiplier, boolean autoOpen)
    {
        if (key == null || key.isEmpty() || !words.containsKey(key))
            return;
        result.values().merge(key, Integer.valueOf(multiplier), Integer::sum);
        if (autoOpen && multiplier > 0)
            result.autoOpen().add(key);
    }

    private static boolean containsIgnoreCase(List<String> values, String value)
    {
        for (String item : values)
        {
            if (item.equalsIgnoreCase(value))
                return true;
        }
        return false;
    }

    private static boolean startsWithIgnoreCase(String text, String prefix)
    {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static int countOf(String text, char c)
    {
        int count = 0;
        for (int i = 0; i < text.length(); i++)
        {
            if (text.charAt(i) == c)
                count++;
        }
        return count;
    }

    private static boolean endsWithWord(String text, String word)
    {
        if (text.length() < word.length() || !text.regionMatches(true, text.length() - word.length(), word, 0,
            word.length()))
            return false;
        int before = text.length() - word.length() - 1;
        return before < 0 || !SmartContentAssistProcessor.isFilterChar(text.charAt(before));
    }

    private static String lastFragment(String expression)
    {
        int dot = expression.lastIndexOf('.');
        return firstFragment(dot >= 0 ? expression.substring(dot + 1) : expression, '(');
    }

    private static String withoutLastFragment(String expression)
    {
        int dot = expression.lastIndexOf('.');
        return dot >= 0 ? expression.substring(0, dot) : ""; //$NON-NLS-1$
    }

    private static String firstFragment(String text, char separator)
    {
        int index = text.indexOf(separator);
        return index >= 0 ? text.substring(0, index) : text;
    }

    private static String stripEnd(String text)
    {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1)))
            end--;
        return text.substring(0, end);
    }

    private static String left(String text, int length)
    {
        return text.length() <= length ? text : text.substring(0, length);
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
