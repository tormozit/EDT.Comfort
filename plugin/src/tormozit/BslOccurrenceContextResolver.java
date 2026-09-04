package tormozit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.resource.IResourceSetProvider;
import org.eclipse.xtext.ui.resource.XtextLiveScopeResourceSetProvider;

import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Variable;
import com._1c.g5.v8.dt.bsl.resource.BslResource;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;

import com._1c.g5.v8.dt.mcore.Environmental;
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
 * ({@link RefactoringPreviewTableHook}), второй — те же колонки «Родитель», «Тип родителя» и
 * «Категория» в результатах команды «Найти ссылки» ({@code ConfigSearchResultsHook}). У
 * вхождений-ссылок BSL ({@code BslReferenceMatch}) смещения в элементе таблицы нет — оно берётся из
 * URI источника ссылки через {@link #referenceNodeRegion}, дальше работает та же offset-логика.
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

    /** Единые заголовки колонок контекста вхождения для обоих потребителей. */
    static final String COL_PARENT = "Родитель"; //$NON-NLS-1$
    static final String COL_PARENT_TYPE = "Тип родителя"; //$NON-NLS-1$
    static final String COL_SYNTAX_KIND = "Категория"; //$NON-NLS-1$

    private static final String TIP_PARENT = "Выражение слева от точки перед вхождением"; //$NON-NLS-1$
    private static final String TIP_PARENT_TYPE = "Тип выражения-родителя"; //$NON-NLS-1$
    private static final String TIP_SYNTAX_KIND = "Свойство, метод, литерал или комментарий"; //$NON-NLS-1$

    /** Диагностика разбора типа родителя — по умолчанию выкл. (в горячем пути, лог рос до ~1 МБ). */
    private static final boolean LOG_TYPE = false;

    private static void logType(String text)
    {
        if (LOG_TYPE)
            Global.tempLog("parent-type", text); //$NON-NLS-1$
    }

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
            // Разбор модуля моделью BSL — не гарантированная операция: в колонке остаётся «?».
            resolved = ""; //$NON-NLS-1$
        }
        putBounded(TYPE_CACHE, key, resolved);
        return resolved;
    }

    /**
     * Смещение и длина узла вхождения-ссылки BSL по URI источника ссылки
     * ({@code BslReferenceMatch.getSourceURI()} — платформенный URI модуля с фрагментом на исходный
     * EObject вхождения). В самом элементе таблицы результатов поиска смещения нет
     * ({@code BslResourceMatchTreeTableItem.calculate()} проставляет только номер строки), поэтому
     * позиция восстанавливается из модели — так же, как это делает штатный {@code calculate()}.
     *
     * <p>Дорогой вызов: поднимает {@link XtextResource} модуля (кэш {@link #RESOURCE_SETS}) — только
     * фоновый поток.
     *
     * <p>Позиция берётся у узла самой ссылающейся ссылки ({@code reference}) — это последний сегмент
     * обращения ({@code Справочники.Валюты} → {@code Валюты}), а не всё выражение; так {@code offset}
     * попадает сразу за точку, и {@link #parentText}/{@link #parentType} видят родителя.
     *
     * @param reference ссылка вхождения ({@code BslReferenceMatch.getReference()}); {@code null}
     *            допустим — тогда берётся узел всего исходного EObject
     * @param indexInList индекс в многозначной ссылке ({@code BslReferenceMatch.getIndexInList()});
     *            {@code < 0} — брать последний узел
     * @return {@code [offset, length]} модельные координаты в тексте модуля; {@code null}, если
     *         файл не BSL-модуль, URI без фрагмента, либо ресурс/EObject/узел не разрешились
     */
    static int[] referenceNodeRegion(IFile file, URI sourceUri, EReference reference, int indexInList)
    {
        return referenceNodeRegion(file, sourceUri, reference, indexInList, false);
    }

    /**
     * @param referenceFirst {@code true} — узел брать по {@code reference} в первую очередь (Xtext-поиск
     *     ссылок: {@code getEReference()} непустой и указывает прямо на токен вхождения — у объявления
     *     параметра «Форма» это «Форма» в «(Форма)», а не имя процедуры); {@code false} — сначала
     *     токен имени обращения ({@code BslReferenceMatch}, где {@code reference} часто null).
     */
    static int[] referenceNodeRegion(IFile file, URI sourceUri, EReference reference, int indexInList,
        boolean referenceFirst)
    {
        return referenceNodeRegion(file, sourceUri, reference, indexInList, referenceFirst, null);
    }

    /**
     * @param targetName простое имя искомого элемента (напр. «Форма»). Xtext на многие вхождения даёт
     *     грубый источник (метод целиком, {@code getEReference()==null}); тогда позиция ищется как
     *     первый неспрятанный лист с этим текстом в поддереве источника (или совпадающий сегмент
     *     строкового литерала).
     */
    static int[] referenceNodeRegion(IFile file, URI sourceUri, EReference reference, int indexInList,
        boolean referenceFirst, String targetName)
    {
        if (!BslModuleMethodResolver.isBslModule(file) || sourceUri == null)
            return null;
        String fragment = sourceUri.fragment();
        if (fragment == null || fragment.isEmpty())
            return null;
        try
        {
            URI moduleUri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
            IResourceServiceProvider provider =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(moduleUri);
            if (provider == null)
                return null;
            ResourceSet resourceSet = resourceSet(provider, file.getProject());
            if (resourceSet == null)
                return null;
            Resource resource = resourceSet.getResource(moduleUri, true);
            if (!(resource instanceof BslResource bslResource))
                return null;
            // Xtext-поиск ссылок: getSourceEObjectUri() — обычный EMF-фрагмент, resource.getEObject
            // возвращает ТОЧНОЕ обращение (StaticFeatureAccess «Форма»). BslResource.getSourceEObject
            // (для BslReferenceMatch) отображает фрагмент на «исходную» модель и на xtext-фрагменте
            // может отдать грубый узел (метод целиком) — тогда подсвечивалось имя процедуры.
            EObject source = null;
            if (referenceFirst)
            {
                try
                {
                    source = resource.getEObject(fragment);
                }
                catch (RuntimeException ignored)
                {
                    source = null;
                }
            }
            if (source == null)
                source = bslResource.getSourceEObject(fragment);
            Global.tempLog("ref-node", file.getName() + " frag=" + fragment //$NON-NLS-1$ //$NON-NLS-2$
                + " source=" + (source != null ? source.eClass().getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                + " ref=" + (reference != null ? reference.getName() : "null") + " idx=" + indexInList); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (source == null)
                return null;
            INode node = referenceNode(source, reference, indexInList, referenceFirst);
            INode scope = NodeModelUtils.findActualNodeFor(source);
            String nodeText = node != null ? node.getText() : null;

            // 1. Строковый литерал: сегмент, совпадающий с искомым именем (иначе последний).
            if (nodeText != null && nodeText.trim().startsWith("\"")) //$NON-NLS-1$
            {
                int[] r = literalSegmentRegion(nodeText, node.getOffset(), node.getLength(), targetName);
                if (r != null)
                    return logRegion("literal", r); //$NON-NLS-1$
            }
            // 2. Грубый источник (метод и т.п.): первый неспрятанный лист с текстом = искомое имя.
            if (targetName != null && !targetName.isBlank() && scope != null)
            {
                for (org.eclipse.xtext.nodemodel.ILeafNode leaf : scope.getLeafNodes())
                {
                    if (!leaf.isHidden() && targetName.equalsIgnoreCase(leaf.getText()))
                        return logRegion("byName", new int[] {leaf.getOffset(), leaf.getLength()}); //$NON-NLS-1$
                }
            }
            // 3. Первый значимый лист узла (обрезаем ведущие комментарии/пробелы).
            INode meaningful = firstMeaningfulLeaf(node != null ? node : scope);
            if (meaningful == null)
                return null;
            return logRegion("leaf", new int[] {meaningful.getOffset(), meaningful.getLength()}); //$NON-NLS-1$
        }
        catch (Exception | LinkageError e)
        {
            logType("  referenceNodeRegion EX: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static int[] logRegion(String how, int[] region)
    {
        Global.tempLog("ref-node", "  → " + how + " [" + region[0] + "," + region[1] + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        return region;
    }

    /**
     * Внутри строкового литерала ({@code "Обработка.X.Форма.Y"}) — сегмент, совпадающий с искомым
     * именем ({@code targetName}); если имя не задано или не найдено — последний сегмент. Смещение
     * попадает внутрь кавычек → {@link #syntaxKind}=«Литерал», {@link #parentText} собирает родителя.
     */
    private static int[] literalSegmentRegion(String tokenText, int offset, int length, String targetName)
    {
        int contentEnd = tokenText.length();
        while (contentEnd > 0 && (tokenText.charAt(contentEnd - 1) == '"'
            || Character.isWhitespace(tokenText.charAt(contentEnd - 1))))
            contentEnd--;
        int i = 0;
        int lastStart = -1;
        int lastLen = 0;
        while (i < contentEnd)
        {
            if (isIdentifierChar(tokenText.charAt(i)))
            {
                int segStart = i;
                while (i < contentEnd && isIdentifierChar(tokenText.charAt(i)))
                    i++;
                String seg = tokenText.substring(segStart, i);
                if (targetName != null && targetName.equalsIgnoreCase(seg))
                    return new int[] {offset + segStart, seg.length()};
                lastStart = segStart;
                lastLen = seg.length();
            }
            else
            {
                i++;
            }
        }
        if (lastStart >= 0)
            return new int[] {offset + lastStart, lastLen};
        return new int[] {offset, length};
    }

    private static INode firstMeaningfulLeaf(INode node)
    {
        if (node == null)
            return null;
        for (org.eclipse.xtext.nodemodel.ILeafNode leaf : node.getLeafNodes())
        {
            if (!leaf.isHidden())
            {
                String text = leaf.getText();
                if (text != null && !text.isBlank())
                    return leaf;
            }
        }
        return node;
    }

    private static boolean isIdentifierChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static INode referenceNode(EObject source, EReference reference, int indexInList,
        boolean referenceFirst)
    {
        if (referenceFirst)
        {
            INode byRef = nodeByReference(source, reference, indexInList);
            if (byRef != null)
                return byRef;
        }
        // Токен имени обращения (FeatureAccess.name): у «Справочники.Валюты» это «Валюты», у внешнего
        // «ОсновнаяВалюта.ОсновнаяВалюта» — второй сегмент (сразу за точкой). Так offset попадает за
        // точку, и parentText/parentType видят родителя.
        EStructuralFeature nameFeature = source.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
        if (nameFeature != null)
        {
            List<INode> nodes = NodeModelUtils.findNodesForFeature(source, nameFeature);
            if (!nodes.isEmpty())
                return nodes.get(nodes.size() - 1);
        }
        if (!referenceFirst)
        {
            INode byRef = nodeByReference(source, reference, indexInList);
            if (byRef != null)
                return byRef;
        }
        return NodeModelUtils.findActualNodeFor(source);
    }

    private static INode nodeByReference(EObject source, EReference reference, int indexInList)
    {
        if (reference == null)
            return null;
        List<INode> nodes = NodeModelUtils.findNodesForFeature(source, reference);
        if (nodes.isEmpty())
            return null;
        if (indexInList >= 0 && indexInList < nodes.size())
            return nodes.get(indexInList);
        return nodes.get(nodes.size() - 1);
    }

    /**
     * Ставит единые подсказки заголовков колонок «Родитель»/«Тип родителя»/«Категория»
     * (через {@link FormTableInteraction#setHeaderTooltipExtra} — при обрезанном заголовке первой
     * строкой его полный текст). Любая из колонок может быть {@code null}.
     */
    static void applyColumnHeaderTooltips(FormTableInteraction interaction, TableColumn parent,
        TableColumn parentType, TableColumn syntaxKind)
    {
        if (interaction == null)
            return;
        String sign = Global.pluginSignForTooltip();
        if (parent != null && !parent.isDisposed())
            interaction.setHeaderTooltipExtra(parent, TIP_PARENT + sign);
        if (parentType != null && !parentType.isDisposed())
            interaction.setHeaderTooltipExtra(parentType, TIP_PARENT_TYPE + sign);
        if (syntaxKind != null && !syntaxKind.isDisposed())
            interaction.setHeaderTooltipExtra(syntaxKind, TIP_SYNTAX_KIND + sign);
    }

    /** Отпускает разобранные модули и вычисленные типы — вызывать при закрытии окна-потребителя. */
    static void clearCaches()
    {
        RESOURCE_SETS.clear();
        TYPE_CACHE.clear();
    }

    private static String resolveTypeFromModel(IFile file, String content, int dotOffset, int offset)
    {
        URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        IResourceServiceProvider provider =
            IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
        if (provider == null)
            return ""; //$NON-NLS-1$
        ResourceSet resourceSet = resourceSet(provider, file.getProject());
        if (resourceSet == null)
            return ""; //$NON-NLS-1$
        Resource resource = resourceSet.getResource(uri, true);
        if (!(resource instanceof XtextResource xtextResource))
            return ""; //$NON-NLS-1$
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
            return contextTypeOfOwnProperty(xtextResource, offset, file);

        Expression receiver = SmartContentAssistProcessor.ReceiverTypeLabel.receiverInResource(xtextResource,
            dotOffset, offset);
        logType("  receiver path: dotOffset=" + dotOffset //$NON-NLS-1$
            + " receiver=" + (receiver != null ? receiver.eClass().getName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
        if (receiver == null)
            return ""; //$NON-NLS-1$
        String types = SmartContentAssistProcessor.ReceiverTypeLabel.formatTypes(receiver);
        if (!types.isEmpty())
            return types;
        // У загруженного нами ресурса типы в самом выражении пустые (их проставляет конвейер
        // редактора), поэтому берём результат вычислителя, а не состояние модели.
        String computed = SmartContentAssistProcessor.ReceiverTypeLabel.formatTypeItems(computeTypes(provider, receiver));
        logType("  receiver computed types=«" + computed + "»"); //$NON-NLS-1$ //$NON-NLS-2$
        return computed;
    }

    /**
     * Прямое обращение к реквизиту объекта в его же модуле ({@code ОсновнаяВалюта = …} в модуле
     * объекта справочника): точки перед вхождением нет, родителем является сам объект модуля.
     *
     * <p>Тип объекта модуля берётся у его владельца ({@link Module#getOwner()} + тип модуля):
     * {@code Справочник.Валюты + МодульОбъекта} → {@code СправочникОбъект.Валюты}
     * ({@link MdTypeMapping#directModuleName}). Контекст модуля ({@code ContextDef.allProperties()})
     * у поднятого нами ресурса часто не содержит ни реквизитов объекта, ни свойства {@code ЭтотОбъект}
     * — на него не опираемся.
     *
     * @return {@code ""}, если вхождение — объявленная локальная переменная/параметр, либо владелец
     *     модуля / тип модуля не определяются
     */
    private static String contextTypeOfOwnProperty(XtextResource resource, int offset, IFile file)
    {
        Module module = moduleOf(resource);
        if (module == null)
            return ""; //$NON-NLS-1$
        EObject semantic = semanticAt(resource, offset);
        StaticFeatureAccess access = EcoreUtil2.getContainerOfType(semantic, StaticFeatureAccess.class);
        if (access == null)
            return ""; //$NON-NLS-1$
        boolean localVar = false;
        for (FeatureEntry entry : access.getFeatureEntries())
        {
            if (entry.getFeature() instanceof Variable)
                localVar = true;
        }
        String type = localVar ? "" : moduleObjectTypeName(module, file); //$NON-NLS-1$
        EObject owner = module.getOwner();
        logType("  contextTypeOfOwnProperty: access=«" + access.getName() //$NON-NLS-1$
            + "» localVar=" + localVar //$NON-NLS-1$
            + " owner=" + (owner != null ? owner.eClass().getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
            + " ownerName=" + (owner != null ? Global.invoke(owner, "getName") : null) //$NON-NLS-1$ //$NON-NLS-2$
            + " → type=«" + type + "»"); //$NON-NLS-1$ //$NON-NLS-2$
        return type;
    }

    /**
     * Тип объекта модуля ({@code СправочникОбъект.Валюты}, {@code ДокументОбъект.Заказ},
     * {@code РегистрСведенийНаборЗаписей.Курсы} …) — из владельца модуля и типа модуля по файлу.
     *
     * @return {@code ""}, если владелец/тип не в справочнике {@link MdTypeMapping}
     */
    private static String moduleObjectTypeName(Module module, IFile file)
    {
        EObject owner = module.getOwner();
        if (owner == null || file == null)
            return ""; //$NON-NLS-1$
        String typeRu = MdTypeMapping.anyToRu(owner.eClass().getName());
        String moduleRu = MdTypeMapping.bslFilenameToModuleRu(file.getName());
        Object nameObj = Global.invoke(owner, "getName"); //$NON-NLS-1$
        if (typeRu == null || moduleRu == null || !(nameObj instanceof String name) || name.isEmpty())
            return ""; //$NON-NLS-1$
        String direct = MdTypeMapping.directModuleName(typeRu + "." + name + "." + moduleRu); //$NON-NLS-1$ //$NON-NLS-2$
        return direct != null ? direct : ""; //$NON-NLS-1$
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

    private static EObject semanticAt(XtextResource resource, int offset)
    {
        if (resource.getParseResult() == null || resource.getParseResult().getRootNode() == null)
            return null;
        ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset);
        return leaf != null ? leaf.getSemanticElement() : null;
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
     * («Родитель» и «Категория»), а тип родителя добирается фоном.
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
