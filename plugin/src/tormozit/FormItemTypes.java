package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.resource.IEObjectDescription;

import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Члены неспециализированных элементов управляемой формы. Платформа разбивает {@code ПолеФормы},
 * {@code ТаблицаФормы}, {@code ГруппаФормы} и {@code ДекорацияФормы} на базовый тип и типы-расширения под вид
 * элемента ({@code FormFieldExtensionForATextBox} и т. п.); у специализированного элемента в списке типов есть
 * базовый тип и своё расширение. Если в списке типов, по которому ищутся члены, базовый тип стоит без
 * расширений (вид элемента неизвестен: параметр, переменная, {@code Элементы[Имя]}, созданный программно), к нему
 * добавляются все расширения этого семейства, как EDT уже делает для {@code Элементы.Добавить(…)} по жёсткой
 * таблице ({@code AddFormItemInvocationTypesComputer}). Обратно: если есть только расширение, без базового типа
 * (тип из комментария {@code // РасширениеПоляФормыДляПоляВвода}), базовый тип добавляется всегда.
 *
 * <p>Применяется на входе поиска членов: в раннем бандле это вплетение в {@code DynamicFeatureAccessComputer}
 * (автодополнение, проверка неизвестного члена, подсказки), в {@link BslLiteralValues} — значения литерала со
 * свойствами. Для подписей известный тип-расширение выбирается отдельно, без изменения модели выражения.
 */
final class FormItemTypes
{
    /** Базовый тип → префиксы имён его типов-расширений. */
    private static final Map<String, String[]> FAMILIES = Map.of(
        "FormField", new String[] {"FormFieldExtensionFor", "FormExtensionFor"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "FormTable", new String[] {"FormTableExtensionFor"}, //$NON-NLS-1$ //$NON-NLS-2$
        "FormGroup", new String[] {"FormGroupExtensionFor"}, //$NON-NLS-1$ //$NON-NLS-2$
        "FormDecoration", new String[] {"FormDecorationExtensionFor"}); //$NON-NLS-1$ //$NON-NLS-2$
    private static final String EXTENSION_MARK = "ExtensionFor"; //$NON-NLS-1$
    private static final String MANAGED_FORM = "ManagedForm"; //$NON-NLS-1$
    private static final String FORM_BASE = "ClientApplicationForm"; //$NON-NLS-1$
    /** Расширения по URI базового типа без фрагмента. */
    private static final Map<String, List<TypeItem>> EXTENSIONS = new ConcurrentHashMap<>();

    private FormItemTypes()
    {
    }

    /** Только представление: EDT хранит известное расширение в refContextDefs копии базового типа. */
    static TypeItem displayType(TypeItem item)
    {
        if (!(item instanceof Type type) || type.eIsProxy())
            return item;
        String base = McoreUtil.getTypeName(item);
        if (!isPresentationBase(base))
            return item;
        // Конкретная форма несёт путь к метаданным, которого нет у платформенного расширения.
        if (BslFormTypePresentation.text(item) != null)
            return item;
        Map<String, TypeItem> extensions = new LinkedHashMap<>();
        Set<ContextDef> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectDisplayExtensions(type.getContextDef(), base, extensions, visited);
        // Несколько расширений — объединение вариантов, конкретный вид не установлен.
        return extensions.size() == 1 ? extensions.values().iterator().next() : item;
    }

    /** В явном списке «базовый + единственное расширение» показывается расширение. */
    static List<TypeItem> displayTypes(Iterable<? extends TypeItem> types)
    {
        List<TypeItem> original = new ArrayList<>();
        for (TypeItem item : types)
            if (item != null)
                original.add(item);
        List<TypeItem> result = new ArrayList<>();
        for (TypeItem item : original)
        {
            TypeItem shown = displayType(item);
            String base = McoreUtil.getTypeName(shown);
            if (isPresentationBase(base) && BslFormTypePresentation.text(shown) == null)
            {
                Map<String, TypeItem> extensions = new LinkedHashMap<>();
                for (TypeItem candidate : original)
                {
                    String name = McoreUtil.getTypeName(candidate);
                    if (isPresentationExtensionOf(name, base))
                        extensions.putIfAbsent(name, candidate);
                }
                if (extensions.size() == 1)
                    shown = extensions.values().iterator().next();
            }
            if (!result.contains(shown))
                result.add(shown);
        }
        return result;
    }

    private static void collectDisplayExtensions(ContextDef context, String base, Map<String, TypeItem> extensions,
        Set<ContextDef> visited)
    {
        if (context == null || context.eIsProxy() || !visited.add(context))
            return;
        if (context.eContainer() instanceof Type type)
        {
            String name = McoreUtil.getTypeName(type);
            if (isPresentationExtensionOf(name, base))
                extensions.putIfAbsent(name, type);
        }
        for (ContextDef ref : context.getRefContextDefs())
            collectDisplayExtensions(ref, base, extensions, visited);
    }

    private static boolean isPresentationBase(String name)
    {
        return name != null && (FAMILIES.containsKey(name) || FORM_BASE.equals(name) || MANAGED_FORM.equals(name));
    }

    private static boolean isPresentationExtensionOf(String name, String base)
    {
        return isExtensionOf(name, base) || name != null && (FORM_BASE.equals(base) || MANAGED_FORM.equals(base))
            && (name.startsWith("ManagedFormExtensionFor") || name.startsWith("ClientApplicationFormExtensionFor")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static boolean isPresentationExtension(TypeItem item)
    {
        String name = item != null ? McoreUtil.getTypeName(item) : null;
        if (isPresentationExtensionOf(name, FORM_BASE))
            return true;
        for (String base : FAMILIES.keySet())
            if (isExtensionOf(name, base))
                return true;
        return false;
    }

    /** Функция вплетения: {@code {типы, ресурс}} → те же или дополненные типы. */
    static Object expand(Object[] args)
    {
        return expandCollection(args[0]);
    }

    /** Тот же список, если дополнять нечего. */
    @SuppressWarnings("unchecked")
    static List<TypeItem> expand(List<TypeItem> types)
    {
        Object result = expandCollection(types);
        return result == types ? types : (List<TypeItem>)result;
    }

    private static Object expandCollection(Object raw)
    {
        if (!(raw instanceof Collection<?> types) || types.isEmpty() || types.size() > 32)
            return raw;
        try
        {
            List<TypeItem> extra = null;
            for (Object candidate : types)
            {
                if (!(candidate instanceof TypeItem item))
                    continue;
                String segment = segmentOf(item);
                String[] prefixes = segment != null ? FAMILIES.get(segment) : null;
                if (prefixes == null)
                {
                    // Только расширение без базового типа (тип из комментария «// РасширениеПоляФормыДляПоляВвода»)
                    TypeItem base = baseOf(item, segment);
                    String baseSegment = base != null ? segmentOf(base) : null;
                    if (baseSegment != null && !hasSegment(types, baseSegment) && (extra == null || !hasSegment(extra, baseSegment)))
                    {
                        if (extra == null)
                            extra = new ArrayList<>();
                        extra.add(base);
                    }
                    continue;
                }
                if (hasExtension(types, prefixes))
                    continue;
                for (TypeItem added : extensions(item, segment, prefixes))
                {
                    if (extra == null)
                        extra = new ArrayList<>();
                    if (!extra.contains(added) && !types.contains(added))
                        extra.add(added);
                }
            }
            if (extra == null || extra.isEmpty())
                return raw;
            List<Object> result = new ArrayList<>(types);
            result.addAll(extra);
            return result;
        }
        catch (RuntimeException | LinkageError failure)
        {
            return raw;
        }
    }

    /** Тип-расширение семейства {@code base}: {@code FormFieldExtensionForATextBox} для {@code FormField}. */
    static boolean isExtensionOf(String segment, String base)
    {
        String[] prefixes = segment != null ? FAMILIES.get(base) : null;
        if (prefixes != null)
            for (String prefix : prefixes)
                if (segment.startsWith(prefix))
                    return true;
        return false;
    }

    /** Имя типа платформы по URI: {@code .../types/FormField.type#/} → {@code FormField}. */
    static String segmentOf(TypeItem item)
    {
        URI uri = EcoreUtil.getURI(item);
        String last = uri != null ? uri.lastSegment() : null;
        return last != null && last.endsWith(".type") ? last.substring(0, last.length() - ".type".length()) : null; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean hasSegment(Collection<?> types, String segment)
    {
        for (Object candidate : types)
            if (candidate instanceof TypeItem item && segment != null && segment.equals(segmentOf(item)))
                return true;
        return false;
    }

    /**
     * Базовый тип для любого типа-расширения: часть имени до {@code ExtensionFor}
     * ({@code ClientApplicationFormExtensionFor…} → {@code ClientApplicationForm}); {@code FormExtensionFor…} —
     * расширения {@code FormField}. Расширения формы по объекту ({@code ManagedFormExtensionFor…}) EDT создаёт
     * сам, в провайдере платформы их нет — поэтому имя, а не только URI. {@code null}, если это не расширение.
     */
    private static TypeItem baseOf(TypeItem extension, String segment)
    {
        String name = segment != null ? segment : extension.getName();
        if (name == null)
            return null;
        String baseName = null;
        for (Map.Entry<String, String[]> family : FAMILIES.entrySet())
            for (String prefix : family.getValue())
                if (name.startsWith(prefix))
                    baseName = family.getKey();
        if (baseName == null)
        {
            int at = name.indexOf(EXTENSION_MARK);
            if (at <= 0)
                return null;
            baseName = name.substring(0, at);
        }
        TypeItem base = platformType(baseName);
        // «Управляемая форма» в новых версиях платформы — «Форма клиентского приложения»
        return base == null && MANAGED_FORM.equals(baseName) ? platformType(FORM_BASE) : base;
    }

    static TypeItem platformType(String name)
    {
        for (Version version : Version.getPlatformSupportVersions())
        {
            IEObjectProvider provider = IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
            if (provider != null && provider.getEObjectDescription(name) != null
                && provider.<EObject>getProxy(name) instanceof TypeItem type)
                return type;
        }
        return null;
    }

    private static boolean hasExtension(Collection<?> types, String[] prefixes)
    {
        for (Object candidate : types)
        {
            String segment = candidate instanceof TypeItem item ? segmentOf(item) : null;
            if (segment == null)
                continue;
            for (String prefix : prefixes)
                if (segment.startsWith(prefix))
                    return true;
        }
        return false;
    }

    private static List<TypeItem> extensions(TypeItem base, String baseName, String[] prefixes)
    {
        // Расширения и их члены сохраняют исходные environments: штатный поиск отбирает
        // доступные варианты по фактическим контекстам компиляции, без раннего слияния имён.
        URI baseUri = EcoreUtil.getURI(base).trimFragment();
        String key = String.valueOf(baseUri);
        List<TypeItem> cached = EXTENSIONS.get(key);
        if (cached != null)
            return cached;
        List<TypeItem> found = new ArrayList<>();
        for (Version version : Version.getPlatformSupportVersions())
        {
            IEObjectProvider provider = IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
            IEObjectDescription own = provider != null ? provider.getEObjectDescription(baseName) : null;
            if (own == null || !baseUri.equals(EcoreUtil.getURI(own.getEObjectOrProxy()).trimFragment()))
                continue;
            for (IEObjectDescription description : provider.getEObjectDescriptions(d ->
            {
                String name = d.getName() != null ? d.getName().toString() : ""; //$NON-NLS-1$
                for (String prefix : prefixes)
                    if (name.startsWith(prefix))
                        return true;
                return false;
            }))
            {
                Object proxy = provider.<EObject>getProxy(description.getName().toString());
                if (proxy instanceof TypeItem extension)
                    found.add(extension);
            }
            break;
        }
        // Пустой результат тоже кэшируется: версия платформы не найдена или расширений нет
        EXTENSIONS.put(key, found);
        return found;
    }
}
