package tormozit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.resource.IResourceSetProvider;
import org.eclipse.xtext.ui.resource.XtextLiveScopeResourceSetProvider;

import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.resource.BslResource;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.Environmental;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environments;

/**
 * Контекст вхождения в тексте BSL-модуля: что стоит слева от предшествующей точки («родитель»),
 * какой у этого выражения тип и чем вхождение является синтаксически — комментарием, строковым
 * литералом, обращением к свойству или вызовом метода.
 *
 * <p>Родителем считается не только выражение перед точкой: прямое обращение к реквизиту объекта в
 * его же модуле ({@code ДатаОтгрузки = 123}) точки не имеет, но родителем там является объект
 * модуля — в типе показывается его тип.
 *
 * <p>Разделяемый резолвер по образцу {@link BslModuleMethodResolver}: первый потребитель — колонки
 * табличного режима страницы «Вносимые изменения» мастера рефакторинга
 * ({@link RefactoringPreviewTableHook}), в задаче заложен второй — те же колонки в результатах
 * поиска ссылок.
 *
 * <p>Расчёты разделены по цене:
 * <ul>
 * <li>{@link #parentText} и {@link #syntaxKind} — чисто текстовые, по содержимому модуля и смещению;
 * безопасны в любом потоке, стоят доли микросекунды;</li>
 * <li>{@link #parentType} поднимает {@link XtextResource} модуля и спрашивает у модели типы
 * выражения-приёмника. Это дорого (разбор и связывание модуля, при пустых типах — принудительный
 * {@link TypesComputer}), поэтому вызывать только из фонового потока и только для тех вхождений,
 * которые пользователь видит.</li>
 * </ul>
 *
 * <p>Ресурсы грузятся в свой {@link ResourceSet} на проект (кэш {@link #RESOURCE_SETS}) — иначе
 * каждый модуль тянул бы связывание с нуля. Текст берётся с диска, несохранённые правки открытого
 * редактора в расчёт не идут: смещения вхождений мастер рефакторинга тоже считает по сохранённому
 * содержимому. Освобождать кэши — {@link #clearCaches()} при закрытии окна-потребителя.
 */
public final class BslOccurrenceContextResolver
{
    /** Синтаксический вид вхождения — подпись для колонки. */
    static final String KIND_COMMENT = "Комментарий"; //$NON-NLS-1$
    static final String KIND_LITERAL = "Литерал"; //$NON-NLS-1$
    static final String KIND_METHOD = "Метод"; //$NON-NLS-1$
    static final String KIND_PROPERTY = "Свойство"; //$NON-NLS-1$

    /** Свойство контекста модуля, тип которого и есть тип объекта модуля. */
    private static final String THIS_OBJECT_RU = "ЭтотОбъект"; //$NON-NLS-1$
    private static final String THIS_OBJECT_EN = "ThisObject"; //$NON-NLS-1$

    private static final String TEMP_LOG_TOPIC = "refactoring-preview-table"; //$NON-NLS-1$

    private static final int TYPE_CACHE_LIMIT = 8192;
    /** {@code путь#штамп#смещение} → тип родителя ({@code ""} — вычислить не удалось). */
    private static final Map<String, String> TYPE_CACHE = new ConcurrentHashMap<>();
    /** Проект → набор ресурсов, в котором уже разобраны его модули. */
    private static final Map<IProject, ResourceSet> RESOURCE_SETS = new ConcurrentHashMap<>();

    private BslOccurrenceContextResolver()
    {
    }

    /**
     * Текст выражения слева от точки, непосредственно предшествующей вхождению
     * ({@code Объект.Реквизит} → {@code Объект}).
     *
     * @return {@code ""}, если точки перед вхождением нет
     */
    static String parentText(String content, int offset)
    {
        int dot = dotBefore(content, offset);
        if (dot < 0)
            return ""; //$NON-NLS-1$
        int start = dot;
        while (start > 0 && isReceiverChar(content.charAt(start - 1)))
            start--;
        return start < dot ? content.substring(start, dot).trim() : ""; //$NON-NLS-1$
    }

    /**
     * Чем вхождение {@code [offset, offset + length)} является в тексте модуля: комментарием,
     * строковым литералом, вызовом метода (сразу за именем открывающая скобка) или обращением к
     * свойству.
     *
     * @return {@code ""} для не-BSL содержимого или неверных смещений
     */
    static String syntaxKind(String content, int offset, int length)
    {
        if (content == null || offset < 0 || length <= 0 || offset + length > content.length())
            return ""; //$NON-NLS-1$
        String prefix = linePrefix(content, offset);
        if (BslAssistSourceHeuristics.isInsideLineComment(prefix))
            return KIND_COMMENT;
        if (isInsideLiteral(content, offset))
            return KIND_LITERAL;
        int after = offset + length;
        while (after < content.length() && isInlineSpace(content.charAt(after)))
            after++;
        return after < content.length() && content.charAt(after) == '(' ? KIND_METHOD : KIND_PROPERTY;
    }

    /**
     * Уже вычисленный тип родителя из кэша, без разбора модуля.
     *
     * @return {@code null} — ещё не вычислялось; {@code ""} — вычислить не удалось
     */
    static String cachedParentType(IFile file, int offset)
    {
        if (file == null)
            return null;
        return TYPE_CACHE.get(typeKey(file, file.getModificationStamp(), offset));
    }

    /**
     * Тип выражения-родителя вхождения по модели BSL. Дорогой вызов — только фоновый поток.
     *
     * <p>Точки перед вхождением может и не быть: прямое обращение к реквизиту объекта в его же
     * модуле ({@code ДатаОтгрузки = 123}) — это обращение к свойству контекста, и родителем там
     * является сам объект модуля. В таком случае возвращается тип объекта контекста.
     *
     * @return тип строкой (составной — типы через запятую); {@code ""}, если приёмник не найден или
     *         типы не вычислились — в колонке остаётся «?»
     */
    static String parentType(IFile file, String content, int offset)
    {
        if (!BslModuleMethodResolver.isBslModule(file) || content == null)
            return ""; //$NON-NLS-1$
        long stamp = file.getModificationStamp();
        String key = typeKey(file, stamp, offset);
        String cached = TYPE_CACHE.get(key);
        if (cached != null)
            return cached;

        String resolved = ""; //$NON-NLS-1$
        try
        {
            resolved = resolveTypeFromModel(file, content, dotBefore(content, offset), offset);
        }
        catch (Exception | LinkageError e)
        {
            Global.tempLogException(TEMP_LOG_TOPIC, "parentType " + file.getFullPath() + "@" + offset, e); //$NON-NLS-1$ //$NON-NLS-2$
            resolved = ""; //$NON-NLS-1$
        }
        putBounded(TYPE_CACHE, key, resolved);
        return resolved;
    }

    /** Отпускает разобранные модули и вычисленные типы — вызывать при закрытии окна-потребителя. */
    static void clearCaches()
    {
        RESOURCE_SETS.clear();
        TYPE_CACHE.clear();
    }

    private static String resolveTypeFromModel(IFile file, String content, int dotOffset, int offset)
    {
        String where = file.getName() + "@" + offset + " dot=" + dotOffset //$NON-NLS-1$ //$NON-NLS-2$
            + " [" + snippet(content, offset) + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        IResourceServiceProvider provider =
            IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
        if (provider == null)
        {
            Global.tempLog(TEMP_LOG_TOPIC, "parentType " + where + ": no provider"); //$NON-NLS-1$ //$NON-NLS-2$
            return ""; //$NON-NLS-1$
        }
        ResourceSet resourceSet = resourceSet(provider, file.getProject());
        if (resourceSet == null)
        {
            Global.tempLog(TEMP_LOG_TOPIC, "parentType " + where + ": no resourceSet"); //$NON-NLS-1$ //$NON-NLS-2$
            return ""; //$NON-NLS-1$
        }
        Resource resource = resourceSet.getResource(uri, true);
        if (!(resource instanceof XtextResource xtextResource))
        {
            Global.tempLog(TEMP_LOG_TOPIC, "parentType " + where + ": resource=" //$NON-NLS-1$ //$NON-NLS-2$
                + (resource == null ? "null" : resource.getClass().getName())); //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        }
        // Конвейер редактора/сборщика, без которого TypesComputer даёт пустоту: пакетное
        // связывание и производное состояние ресурса (окружения операторов, контекст модуля
        // его объектом-владельцем, установки обращений) — BslDerivedStateComputer.
        if (resource instanceof BslResource bslResource)
        {
            bslResource.setDeepAnalysis(true);
            if (!bslResource.isLinkedBatch())
                bslResource.linkBatched(null);
            bslResource.installDerivedState(false);
        }
        // Точки перед вхождением нет: родителем может быть сам объект модуля — так выглядит прямое
        // обращение к его реквизиту (ДатаОтгрузки = 123 в модуле объекта).
        if (dotOffset < 0)
        {
            String own = contextTypeOfOwnProperty(xtextResource, offset);
            Global.tempLog(TEMP_LOG_TOPIC, "parentType " + where + ": ownProperty '" + own + "'" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + resourceState(xtextResource));
            return own;
        }

        Expression receiver = SmartContentAssistProcessor.ReceiverTypeLabel.receiverInResource(xtextResource,
            dotOffset, offset);
        if (receiver == null)
        {
            Global.tempLog(TEMP_LOG_TOPIC, "parentType " + where + ": receiver=null" //$NON-NLS-1$ //$NON-NLS-2$
                + resourceState(xtextResource));
            return ""; //$NON-NLS-1$
        }
        String before = SmartContentAssistProcessor.ReceiverTypeLabel.formatTypes(receiver);
        // У загруженного нами ресурса типы в самом выражении пустые (их проставляет конвейер
        // редактора), поэтому берём результат вычислителя, а не состояние модели.
        List<TypeItem> computed = computeTypes(provider, receiver);
        String after = !before.isEmpty() ? before
            : SmartContentAssistProcessor.ReceiverTypeLabel.formatTypeItems(computed);
        Environmental envOwner = EcoreUtil2.getContainerOfType(receiver, Environmental.class);
        Environments environments = envOwner != null ? envOwner.environments() : null;
        Module module = moduleOf(xtextResource);
        int ctxProps = module != null && module.getContextDef() != null
            ? module.getContextDef().allProperties().size() : -1;
        Global.tempLog(TEMP_LOG_TOPIC, "parentType " + where + ": receiver=" //$NON-NLS-1$ //$NON-NLS-2$
            + receiver.eClass().getName() + " before='" + before + "' after='" + after //$NON-NLS-1$ //$NON-NLS-2$
            + "' computed=" + (computed == null ? "null" : computed.size()) //$NON-NLS-1$ //$NON-NLS-2$
            + " env=" + environments + " ctxDef=" + ctxProps + resourceState(xtextResource)); //$NON-NLS-1$ //$NON-NLS-2$
        return after;
    }

    /** Состояние ресурса для диагностики: владелец модуля (прокси?) и тексты ошибок разбора. */
    private static String resourceState(XtextResource resource)
    {
        Module module = moduleOf(resource);
        Object owner = module != null ? Global.invoke(module, "getOwner") : null; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder(" owner="); //$NON-NLS-1$
        if (owner == null)
            sb.append("null"); //$NON-NLS-1$
        else if (((EObject)owner).eIsProxy())
            sb.append("PROXY"); //$NON-NLS-1$
        else
            sb.append(((EObject)owner).eClass().getName());
        sb.append(" errors="); //$NON-NLS-1$
        boolean first = true;
        for (org.eclipse.emf.ecore.resource.Resource.Diagnostic diagnostic : resource.getErrors())
        {
            if (!first)
                sb.append(" | "); //$NON-NLS-1$
            sb.append(diagnostic.getMessage());
            first = false;
            if (sb.length() > 400)
                break;
        }
        return sb.toString();
    }

    /** Фрагмент текста модуля перед вхождением (пробелы и переводы строк схлопнуты). */
    private static String snippet(String content, int offset)
    {
        int start = Math.max(0, offset - 48);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < offset && i < content.length(); i++)
        {
            char c = content.charAt(i);
            sb.append(c == '\n' || c == '\r' || c == '\t' ? ' ' : c);
        }
        return sb.toString();
    }

    /**
     * Прямое обращение к реквизиту объекта в его же модуле ({@code ДатаОтгрузки = 123}): точки перед
     * вхождением нет, а родителем является сам объект модуля. Тип берётся у свойства
     * {@code ЭтотОбъект} контекста модуля — то же, что показала бы подсказка для {@code ЭтотОбъект}.
     *
     * @return {@code ""}, если вхождение не является свойством контекста (локальная переменная,
     *     параметр, имя метода, текст вне кода)
     */
    private static String contextTypeOfOwnProperty(XtextResource resource, int offset)
    {
        Module module = moduleOf(resource);
        ContextDef contextDef = module != null ? module.getContextDef() : null;
        if (contextDef == null)
            return ""; //$NON-NLS-1$
        if (!isContextProperty(resource, contextDef, offset))
            return ""; //$NON-NLS-1$
        return SmartContentAssistProcessor.ReceiverTypeLabel.formatTypeItems(thisObjectTypes(contextDef));
    }

    private static Module moduleOf(XtextResource resource)
    {
        for (EObject root : resource.getContents())
        {
            if (root instanceof Module module)
                return module;
        }
        return null;
    }

    /**
     * Вхождение — свойство контекста модуля. Основной путь — по разрешённым ссылкам обращения
     * ({@link StaticFeatureAccess#getFeatureEntries}); если ресурс ещё не связан и ссылок нет,
     * сверяем имя со списком свойств контекста.
     */
    private static boolean isContextProperty(XtextResource resource, ContextDef contextDef, int offset)
    {
        EObject semantic = semanticAt(resource, offset);
        StaticFeatureAccess access = EcoreUtil2.getContainerOfType(semantic, StaticFeatureAccess.class);
        if (access == null)
            return false;
        for (FeatureEntry entry : access.getFeatureEntries())
        {
            if (entry.getFeature() instanceof Property property && contextDef.allProperties().contains(property))
                return true;
        }
        if (!access.getFeatureEntries().isEmpty())
            return false;
        String name = access.getName();
        if (name == null || name.isEmpty())
            return false;
        for (Property property : contextDef.allProperties())
        {
            if (name.equalsIgnoreCase(property.getName()) || name.equalsIgnoreCase(property.getNameRu()))
                return true;
        }
        return false;
    }

    private static EObject semanticAt(XtextResource resource, int offset)
    {
        if (resource.getParseResult() == null || resource.getParseResult().getRootNode() == null)
            return null;
        ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset);
        return leaf != null ? leaf.getSemanticElement() : null;
    }

    private static List<TypeItem> thisObjectTypes(ContextDef contextDef)
    {
        for (Property property : contextDef.allProperties())
        {
            if (THIS_OBJECT_RU.equalsIgnoreCase(property.getNameRu())
                || THIS_OBJECT_EN.equalsIgnoreCase(property.getName()))
            {
                return property.getTypes();
            }
        }
        return List.of();
    }

    /**
     * Типы выражения от {@link TypesComputer}.
     *
     * <p>Вычислитель <b>возвращает</b> типы, а не записывает их в выражение: {@code getTypes()} у
     * выражения заполняет конвейер редактора (валидация, подсказки), и у ресурса, загруженного нами,
     * он остаётся пустым. Поэтому берётся результат вызова, а не состояние модели.
     *
     * @return {@code null}, если вычислитель или окружения недоступны
     */
    private static List<TypeItem> computeTypes(IResourceServiceProvider provider, Expression expression)
    {
        TypesComputer computer = provider.get(TypesComputer.class);
        if (computer == null)
            return null;
        Environmental owner = EcoreUtil2.getContainerOfType(expression, Environmental.class);
        Environments environments = owner != null ? owner.environments() : null;
        if (environments == null)
            return null;
        return computer.computeTypes(expression, environments);
    }

    private static ResourceSet resourceSet(IResourceServiceProvider provider, IProject project)
    {
        if (project == null)
            return null;
        ResourceSet cached = RESOURCE_SETS.get(project);
        if (cached != null)
            return cached;
        // Набор с живым индексом: ссылки разрешаются по всем ресурсам рабочего пространства
        // по требованию. Простой IResourceSetProvider даёт набор без этой привязки — владелец
        // модуля не разрешается, контекст модуля пуст и типы выражений не вычисляются.
        ResourceSet created = null;
        XtextLiveScopeResourceSetProvider liveProvider =
            provider.get(XtextLiveScopeResourceSetProvider.class);
        if (liveProvider != null)
            created = liveProvider.get(project);
        if (created == null)
        {
            IResourceSetProvider setProvider = provider.get(IResourceSetProvider.class);
            if (setProvider != null)
                created = setProvider.get(project);
        }
        if (created == null)
            return null;
        RESOURCE_SETS.put(project, created);
        return created;
    }

    /**
     * Смещение точки member-access непосредственно перед вхождением. Пробелы между точкой и именем
     * BSL допускает, поэтому пропускаем их.
     */
    private static int dotBefore(String content, int offset)
    {
        if (content == null || offset <= 0 || offset > content.length())
            return -1;
        int pos = offset;
        while (pos > 0 && isInlineSpace(content.charAt(pos - 1)))
            pos--;
        return pos > 0 && content.charAt(pos - 1) == '.' ? pos - 1 : -1;
    }

    /**
     * Вхождение внутри строкового литерала. Учитывает многострочные литералы 1С: строки
     * продолжения начинаются с {@code |}, и нечётная кавычка открывающей строки действует до конца
     * блока — та же логика, что у {@link BslAssistSourceHeuristics#isInsideStringLiteral}, но
     * с подъёмом к началу блока продолжений.
     */
    private static boolean isInsideLiteral(String content, int offset)
    {
        int blockStart = lineStart(content, offset);
        while (blockStart > 0 && startsWithPipe(content, blockStart))
            blockStart = lineStart(content, blockStart - 1);
        String prefix = content.substring(blockStart, offset);
        return BslAssistSourceHeuristics.isInsideStringLiteral(prefix);
    }

    private static boolean startsWithPipe(String content, int lineStart)
    {
        for (int i = lineStart; i < content.length(); i++)
        {
            char c = content.charAt(i);
            if (c == '\n' || c == '\r')
                return false;
            if (!isInlineSpace(c))
                return c == '|';
        }
        return false;
    }

    private static String linePrefix(String content, int offset)
    {
        return content.substring(lineStart(content, offset), offset);
    }

    private static int lineStart(String content, int offset)
    {
        int pos = Math.min(offset, content.length());
        while (pos > 0 && content.charAt(pos - 1) != '\n' && content.charAt(pos - 1) != '\r')
            pos--;
        return pos;
    }

    private static boolean isInlineSpace(char c)
    {
        return c == ' ' || c == '\t';
    }

    /** Символ выражения-приёмника: имя, точка, закрывающая скобка индекса или вызова. */
    private static boolean isReceiverChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == ']' || c == ')';
    }

    private static String typeKey(IFile file, long stamp, int offset)
    {
        return file.getFullPath() + "#" + stamp + "#" + offset; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void putBounded(Map<String, String> cache, String key, String value)
    {
        if (cache.size() >= TYPE_CACHE_LIMIT)
            cache.clear();
        cache.put(key, value);
    }

    /**
     * Разбор вхождения на месте: используется потребителями, которым нужны сразу текстовые колонки
     * («Родитель» и «Синтаксический тип»), а тип родителя добирается фоном.
     */
    static final class Occurrence
    {
        final String parent;
        final String syntaxKind;

        private Occurrence(String parent, String syntaxKind)
        {
            this.parent = parent;
            this.syntaxKind = syntaxKind;
        }

        static Occurrence of(String content, int offset, int length)
        {
            if (content == null || offset < 0 || offset + Math.max(length, 0) > content.length())
                return new Occurrence("", ""); //$NON-NLS-1$ //$NON-NLS-2$
            return new Occurrence(parentText(content, offset), syntaxKind(content, offset, length));
        }
    }
}
