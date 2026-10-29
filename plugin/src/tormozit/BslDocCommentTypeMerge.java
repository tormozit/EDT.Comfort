// BslDocCommentTypeMerge.java
package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.util.Tuples;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.documentation.comment.TypeSection;
import com._1c.g5.v8.dt.bsl.model.ExtendedType;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.ContextDefWithRefItem;
import com._1c.g5.v8.dt.mcore.DerivedProperty;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Method;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeContainerRef;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.platform.core.uconverter.IUObjectConverterServiceProvider;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Несколько типов через запятую на одной строке комментария:
 * {@code ИмяПараметр - Тип1, Тип2 - комментарий} и
 * {@code Возвращаемое значение: Тип1, Тип2 - комментарий}.
 * EDT пропускает лишние {@code LinkPart} в {@code sourceDescription} и не сливает
 * контексты. Собираем имена с этой строки и объединяем {@code ContextDef}
 * в один {@link ExtendedType}. Соседние строки и поля не трогаем.
 */
public final class BslDocCommentTypeMerge
{
    private static final String TARGET_COMMENT =
        "com._1c.g5.v8.dt.bsl.documentation.comment.BslDocumentationComment"; //$NON-NLS-1$
    private static final String TARGET_COMMENT_INTERNAL =
        "com/_1c/g5/v8/dt/bsl/documentation/comment/BslDocumentationComment"; //$NON-NLS-1$
    private static final String TYPE_DEF =
        "com._1c.g5.v8.dt.bsl.documentation.comment.TypeSection$TypeDefinition"; //$NON-NLS-1$
    private static final String LINK_TYPE_DEF =
        "com._1c.g5.v8.dt.bsl.documentation.comment.TypeSection$LinkContainsTypeDefinition"; //$NON-NLS-1$
    private static final String LINK_PART =
        "com._1c.g5.v8.dt.bsl.documentation.comment.LinkPart"; //$NON-NLS-1$
    private static final String TEXT_PART =
        "com._1c.g5.v8.dt.bsl.documentation.comment.TextPart"; //$NON-NLS-1$
    private static final String AFTER_COMPUTE_DESC =
        "(Ljava/util/Collection;)Ljava/util/Collection;"; //$NON-NLS-1$
    private static final String SELF_INTERNAL = "tormozit/BslDocCommentTypeMerge"; //$NON-NLS-1$

    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final AtomicBoolean transformerOk = new AtomicBoolean();

    private BslDocCommentTypeMerge() {}

    /**
     * Обработчик разбора и {@link WeavingHook} — отдельно и как можно раньше, по той же
     * причине, что и {@link BslDocCommentDescriptionFix#installWeavingHook()}: иначе
     * {@code BslDocumentationComment} успевает загрузиться до старта бандла.
     * <p>
     * Зовётся из {@link ComfortEarlyStart} строго после {@code BslDocCommentDescriptionFix}:
     * обработчик разбора навешивается поверх прежнего, порядок цепочки важен.
     */
    public static void installWeavingHook()
    {
        BslFormDataTypes.installWeavingHook();
        if (!installed.compareAndSet(false, true))
            return;

        System.getProperties().put("tormozit.formData.comment", //$NON-NLS-1$
            (java.util.function.Function<Object[], Object>) args -> afterComputeTypes(
                afterComputeFormAttributeTypes((Collection<?>)args[0], args[1], (EObject)args[2])));
        System.getProperties().put("tormozit.formData.docDependency", //$NON-NLS-1$
            (java.util.function.Function<Object[], Object>) args -> {
                BslDocCommentComputedTypes.rememberDocReturnDependency(args[0], args[1]);
                return args[0];
            });

        Object prev = System.getProperties().get(BslDocCommentDescriptionFix.PROP_AFTER_PARSE);
        @SuppressWarnings("unchecked")
        Consumer<Object> previous = prev instanceof Consumer<?>
            ? (Consumer<Object>) prev
            : null;
        System.getProperties().put(BslDocCommentDescriptionFix.PROP_AFTER_PARSE,
            (Consumer<Object>) comment -> {
                if (previous != null)
                    previous.accept(comment);
                afterParse(comment);
            });

        Bundle bundle = FrameworkUtil.getBundle(BslDocCommentTypeMerge.class);
        BundleContext context = bundle != null ? bundle.getBundleContext() : null;
        if (context != null && !BslFormDataTypes.earlyWeavingActive())
            context.registerService(WeavingHook.class, new ComputeTypesWeavingHook(), null);
    }

    public static void install()
    {
        installWeavingHook();
        BslFormDataTypes.install();

        if (BslFormDataTypes.earlyWeavingActive())
            return;

        if (transformerOk.get())
            return;
        try
        {
            if (BslDocCommentDescriptionFix.registerExtraTransformer(
                new ComputeTypesTransformer(), TARGET_COMMENT))
                transformerOk.set(true);
        }
        catch (Throwable ignored)
        {
        }
    }

    static void afterParse(Object comment)
    {
        if (comment == null)
            return;
        try
        {
            String cn = comment.getClass().getName();
            if (!TARGET_COMMENT.equals(cn))
                return;
            collectInParameters(comment);
            collectInReturn(comment);
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * Выход EDT {@code computeTypes(TypeSection, …)} — одна строка типов.
     * При двух и более контекстных типах — один {@link ExtendedType}.
     */
    public static Collection<?> afterComputeTypes(Collection<?> types)
    {
        try
        {
            Collection<?> expanded = expandTypeSets(types);
            List<Type> contexts = new ArrayList<>();
            List<TypeItem> rest = new ArrayList<>();
            collectContextAndRest(expanded, contexts, rest);
            Collection<?> result;
            if (contexts.size() < 2)
                result = expanded == null ? types : expanded;
            else
            {
                Type merged = mergeContexts(contexts);
                if (merged == null)
                    result = expanded;
                else
                {
                    List<TypeItem> out = new ArrayList<>();
                    out.add(merged);
                    out.addAll(rest);
                    result = out;
                }
            }
            BslFormTypeContextEnrichment.enrichTypes(result);
            return result;
        }
        catch (Throwable t)
        {
            return types;
        }
    }

    /** Типы реквизита виртуальной формы — до объединения контекстов одной строки. */
    public static Collection<?> afterComputeFormAttributeTypes(Collection<?> types, Object section,
        EObject context)
    {
        if (types == null || types.isEmpty() || context == null || context.eResource() == null)
            return types;
        try
        {
            // Поле без своей TypeSection EDT считает через getTypesFromLinkParts,
            // минуя computeTypes. Поэтому обрабатываем и готовые свойства формы.
            FormAttributeTypes.convertFormProperties(types, context);
            if (!(section instanceof TypeSection typeSection)
                || !(typeSection.getParent() instanceof TypeSection.FieldDefinition field)
                || !(field.getParent() instanceof TypeSection.TypeDefinition owner)
                || !FormAttributeTypes.isForm(owner.getTypeName()))
                return types;
            return FormAttributeTypes.convert(types, context);
        }
        catch (Throwable t)
        {
            return types;
        }
    }

    private static final class FormAttributeTypes
    {
        static void convertFormProperties(Collection<?> types, EObject context)
        {
            for (Object raw : types)
            {
                if (!(raw instanceof ExtendedType form) || form.getContextDef() == null
                    || !(isForm(McoreUtil.getTypeName(form)) || isForm(McoreUtil.getTypeNameRu(form))))
                    continue;
                for (Property property : form.getContextDef().getProperties())
                {
                    List<TypeItem> original = new ArrayList<>(property.getTypes());
                    Collection<?> converted = convert(original, context);
                    if (original.equals(new ArrayList<>(converted)))
                        continue;
                    TypeContainerRef container = McoreFactory.eINSTANCE.createTypeContainerRef();
                    for (Object item : converted)
                        container.getTypes().add((TypeItem)item);
                    property.setTypeContainer(container);
                }
            }
        }

        static boolean isForm(String name)
        {
            if (name == null)
                return false;
            // Формы и добавляемые FormImplicitRefInferrer расширения могут быть объединены.
            for (String part : name.split(",")) //$NON-NLS-1$
            {
                String type = part.trim();
                if ("ClientApplicationForm".equalsIgnoreCase(type) //$NON-NLS-1$
                    || "ManagedForm".equalsIgnoreCase(type) //$NON-NLS-1$
                    || "ФормаКлиентскогоПриложения".equalsIgnoreCase(type) //$NON-NLS-1$
                    || "УправляемаяФорма".equalsIgnoreCase(type) //$NON-NLS-1$
                    || type.startsWith("ManagedFormExtensionFor") //$NON-NLS-1$
                    || type.startsWith("РасширениеУправляемойФормыДля")) //$NON-NLS-1$
                    return true;
            }
            return false;
        }

        static Collection<?> convert(Collection<?> types, EObject context)
        {
            List<TypeItem> converted = new ArrayList<>();
            IResourceServiceProvider services = null;
            IUObjectConverterServiceProvider converter = null;
            Version version = null;
            for (Object raw : types)
            {
                if (!(raw instanceof TypeItem item))
                    return types;
                TypeItem resolved = (TypeItem)EcoreUtil.resolve(item, context);
                if (resolved instanceof TypeSet typeSet && !typeSet.eIsProxy())
                {
                    // types(context) раскрывает и определяемые типы, и общие категории
                    // через индекс метаданных проекта. getTypes() у категории пуст,
                    // а baseTypes содержит только платформенный шаблон без реквизитов метаданных.
                    List<Type> base = new ArrayList<>(typeSet.types(context));
                    if (base.isEmpty())
                        base.addAll(typeSet.getBaseTypes());
                    Collection<?> convertedBase = convert(base, context);
                    // Определяемый тип сохраняем, если ни одна альтернатива не преобразована.
                    if (!base.equals(new ArrayList<>(convertedBase)))
                    {
                        for (Object value : convertedBase)
                            converted.add((TypeItem)value);
                        continue;
                    }
                }
                // Поддержку типа определяет конвертер EDT, включая типы без квалификатора.
                if (resolved instanceof Type)
                {
                    if (services == null)
                    {
                        services = IResourceServiceProvider.Registry.INSTANCE
                            .getResourceServiceProvider(context.eResource().getURI());
                        if (services == null)
                            return types;
                        // PlatformCore регистрирует конвертер в OSGi; у BSL ResourceServiceProvider его нет.
                        converter = ServiceAccess.get(IUObjectConverterServiceProvider.class);
                        IRuntimeVersionSupport versions = services.get(IRuntimeVersionSupport.class);
                        if (converter == null || versions == null)
                            return types;
                        version = versions.getRuntimeVersionOrDefault(context, Version.LATEST);
                    }
                    // Тот же конвертер, что FormImplicitRefInferrer применяет к реквизитам
                    // настоящей формы: свойства, табличные части и методы данных формы.
                    boolean available = converter.hasConverter(resolved, version);
                    if (available)
                    {
                        TypeItem data = converter.convert(resolved, version, context.eResource());
                        if (data != null && !data.eIsProxy())
                            item = BslFormDataTypes.afterConvert(data, resolved);
                    }
                }
                converted.add(item);
            }
            return converted;
        }
    }

    private static int collectInParameters(Object comment)
    {
        Object section = BslDocCommentDescriptionFix.invokeCommentGetter(comment, "getParametersSection"); //$NON-NLS-1$
        if (section == null)
            return 0;
        Object fieldsObj = BslDocCommentDescriptionFix.invokeCommentGetter(section, "getParameterDefinitions"); //$NON-NLS-1$
        if (!(fieldsObj instanceof List<?> fields))
            return 0;
        int added = 0;
        for (Object field : fields)
        {
            if (field == null)
                continue;
            Object sectionsObj = BslDocCommentDescriptionFix.invokeCommentGetter(field, "getTypeSections"); //$NON-NLS-1$
            if (sectionsObj instanceof List<?> sections && sections.isEmpty()
                && fieldHasTypeParts(field))
            {
                Object created = createNativeFieldTypeSection(comment, field);
                if (created != null)
                {
                    @SuppressWarnings("unchecked")
                    List<Object> writable = (List<Object>) sections;
                    writable.add(created);
                    added += 1;
                }
            }
            added += collectInTypeSections(sectionsObj);
        }
        return added;
    }

    private static int collectInReturn(Object comment)
    {
        Object section = BslDocCommentDescriptionFix.invokeCommentGetter(comment, "getReturnSection"); //$NON-NLS-1$
        if (section == null)
            return 0;
        return collectInTypeSections(BslDocCommentDescriptionFix.invokeCommentGetter(section, "getReturnTypes")); //$NON-NLS-1$
    }

    private static int collectInTypeSections(Object sectionsObj)
    {
        if (!(sectionsObj instanceof List<?>))
            return 0;
        @SuppressWarnings("unchecked")
        List<Object> sections = (List<Object>) sectionsObj;
        int added = consolidateSameLineSections(sections);
        for (Object typeSection : new ArrayList<>(sections))
            added += collectCommaTypesOnLine(typeSection);
        return added;
    }

    /**
     * EDT режет {@code Тип1, Тип2} на несколько {@code TypeSection} с одним именем
     * в каждом — запятой внутри секции уже нет. Секции одной строки собираем
     * в первую, чтобы {@code computeTypes} слил контексты. Соседние строки
     * (другое {@code lineNumber}) не трогаем.
     */
    private static int consolidateSameLineSections(List<Object> sections)
    {
        if (sections == null || sections.size() < 2)
            return 0;
        LinkedHashMap<Integer, List<Object>> byLine = new LinkedHashMap<>();
        for (Object section : sections)
        {
            Integer line = lineNumber(section);
            if (line == null)
                continue;
            byLine.computeIfAbsent(line, key -> new ArrayList<>()).add(section);
        }
        int moved = 0;
        List<Object> extras = new ArrayList<>();
        for (List<Object> group : byLine.values())
        {
            if (group.size() < 2)
                continue;
            Object first = group.get(0);
            int groupMoved = 0;
            for (int i = 1; i < group.size(); i++)
            {
                Object extra = group.get(i);
                groupMoved += moveTypeDefinitions(extra, first);
                extras.add(extra);
            }
            moved += groupMoved;
        }
        if (!extras.isEmpty())
        {
            try
            {
                sections.removeAll(extras);
            }
            catch (UnsupportedOperationException ignored)
            {
            }
        }
        return moved;
    }

    private static int moveTypeDefinitions(Object from, Object to)
    {
        Object fromDefsObj = BslDocCommentDescriptionFix.invokeCommentGetter(from, "getTypeDefinitions"); //$NON-NLS-1$
        Object toDefsObj = BslDocCommentDescriptionFix.invokeCommentGetter(to, "getTypeDefinitions"); //$NON-NLS-1$
        if (!(fromDefsObj instanceof List<?> fromDefs) || !(toDefsObj instanceof List<?>))
            return 0;
        @SuppressWarnings("unchecked")
        List<Object> toDefs = (List<Object>) toDefsObj;
        Set<String> names = new LinkedHashSet<>();
        for (Object def : toDefs)
        {
            String name = typeDefName(def);
            if (name != null && !name.isBlank())
                names.add(name.toLowerCase(Locale.ROOT));
        }
        int added = 0;
        for (Object def : new ArrayList<>(fromDefs))
        {
            String name = typeDefName(def);
            if (name == null || name.isBlank())
                continue;
            String key = name.toLowerCase(Locale.ROOT);
            if (names.contains(key))
                continue;
            toDefs.add(def);
            names.add(key);
            added++;
        }
        try
        {
            fromDefs.clear();
        }
        catch (UnsupportedOperationException ignored)
        {
        }
        return added;
    }

    /**
     * Имена типов этой строки: {@code sourceDescription} / {@code currentDescription}.
     * Комментарий после второго {@code -} ({@code getDescription}) и поля следующих
     * строк не читаем.
     */
    private static int collectCommaTypesOnLine(Object typeSection)
    {
        if (typeSection == null)
            return 0;
        Object defsObj = BslDocCommentDescriptionFix.invokeCommentGetter(typeSection, "getTypeDefinitions"); //$NON-NLS-1$
        if (!(defsObj instanceof List<?> defs))
            return 0;
        List<Object> parts = typeLineParts(typeSection);
        if (!isCommaSeparatedTypeLine(parts))
            return 0;
        // EDT уже разобрала «Массив из Тип1, Тип2» в один тип с getContainTypes().
        // Повторный split по запятой поднял бы Тип2 на уровень самой переменной.
        // Сохраняем штатную структуру, в том числе для английского «Array of …».
        for (Object def : defs)
        {
            Object contained = BslDocCommentDescriptionFix.invokeCommentGetter(def, "getContainTypes"); //$NON-NLS-1$
            if (contained instanceof List<?> elements && !elements.isEmpty())
                return 0;
        }
        return collectFromParts(typeSection, parts);
    }

    private static int collectFromParts(Object typeSection, List<Object> parts)
    {
        if (typeSection == null || parts == null || parts.isEmpty())
            return 0;
        Object defsObj = BslDocCommentDescriptionFix.invokeCommentGetter(typeSection, "getTypeDefinitions"); //$NON-NLS-1$
        if (!(defsObj instanceof List<?>))
            return 0;
        @SuppressWarnings("unchecked")
        List<Object> defs = (List<Object>) defsObj;
        Set<String> names = new LinkedHashSet<>();
        for (Object def : defs)
        {
            String name = typeDefName(def);
            if (name != null && !name.isBlank())
                names.add(name.toLowerCase(Locale.ROOT));
        }
        int added = 0;
        for (Object part : parts)
        {
            if (part == null)
                continue;
            String cn = part.getClass().getName();
            if (LINK_PART.equals(cn))
            {
                added += addTypeIfMissing(typeSection, defs, names, part, linkText(part));
            }
            else if (TEXT_PART.equals(cn))
            {
                String text = str(BslDocCommentDescriptionFix.invokeCommentGetter(part, "getText")); //$NON-NLS-1$
                if (text == null || text.isBlank())
                    continue;
                for (String piece : text.split(",")) //$NON-NLS-1$
                    added += addTypeIfMissing(typeSection, defs, names, null, piece);
            }
        }
        return added;
    }

    private static List<Object> descriptionParts(Object description)
    {
        List<Object> out = new ArrayList<>();
        appendDescriptionParts(out, description);
        return out;
    }

    private static boolean fieldHasTypeParts(Object field)
    {
        List<Object> parts = descriptionParts(BslDocCommentDescriptionFix.invokeCommentGetter(field, "getDescription")); //$NON-NLS-1$
        return !parts.isEmpty() && isCommaSeparatedTypeLine(parts.subList(0, 1));
    }

    /** Передаём типы штатному разбору EDT, сохраняя наличие разделителя и координаты текста. */
    private static Object createNativeFieldTypeSection(Object comment, Object field)
    {
        List<Object> parts = descriptionParts(BslDocCommentDescriptionFix.invokeCommentGetter(field, "getDescription")); //$NON-NLS-1$
        if (parts.isEmpty())
            return null;
        Object first = parts.get(0);
        if (!TEXT_PART.equals(first.getClass().getName()))
            return null;
        String raw = str(BslDocCommentDescriptionFix.invokeCommentGetter(first, "getText")); //$NON-NLS-1$
        if (raw == null)
            return null;
        String line = raw.trim();
        if (line.indexOf(',') < 0)
            return null;
        // В описании параметра EDT может уже убрать разделитель после его имени.
        // Последний аргумент createTypeSection означает «снять ведущий -», а не формат
        // комментария: без разделителя передаём false, чтобы не потерять первый символ типа.
        boolean hasLeadingDash = line.startsWith("-"); //$NON-NLS-1$
        int lineNumber = intValue(BslDocCommentDescriptionFix.invokeCommentGetter(first, "getLineNumber")); //$NON-NLS-1$
        int offset = intValue(BslDocCommentDescriptionFix.invokeCommentGetter(first, "getOffset")) + raw.indexOf(line); //$NON-NLS-1$
        String normalized = line.endsWith(":") //$NON-NLS-1$
            ? line.substring(0, line.length() - 1).trim() + " - :" //$NON-NLS-1$
            : line + " - "; //$NON-NLS-1$
        try
        {
            Object section = Global.invoke(comment, "createTypeSection", field, //$NON-NLS-1$
                Tuples.create(normalized, Integer.valueOf(offset), Integer.valueOf(0)),
                Integer.valueOf(normalized.lastIndexOf('-')), Integer.valueOf(lineNumber),
                Boolean.valueOf(hasLeadingDash));
            if (section == null)
                return null;
            Deque<Object> stack = new ArrayDeque<>();
            stack.addLast(section);
            for (int i = 1; i < parts.size(); i++)
            {
                Object part = parts.get(i);
                if (!TEXT_PART.equals(part.getClass().getName()))
                    continue;
                String value = str(BslDocCommentDescriptionFix.invokeCommentGetter(part, "getText")); //$NON-NLS-1$
                if (value == null || !value.trim().startsWith("*")) //$NON-NLS-1$
                    continue;
                String content = value.trim();
                int partOffset = intValue(BslDocCommentDescriptionFix.invokeCommentGetter(part, "getOffset")) //$NON-NLS-1$
                    + value.indexOf(content);
                int partLine = intValue(BslDocCommentDescriptionFix.invokeCommentGetter(part, "getLineNumber")); //$NON-NLS-1$
                Object contentTriple = Tuples.create(content, Integer.valueOf(partOffset),
                    Integer.valueOf(0));
                while (!stack.isEmpty())
                {
                    Object current = stack.peekLast();
                    Object consumed = Global.invoke(comment, "readTypeSectionContent", current, //$NON-NLS-1$
                        Integer.valueOf(partLine), contentTriple, stack);
                    if (Boolean.TRUE.equals(consumed))
                        break;
                    stack.pollLast();
                }
            }
            return section;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private static int intValue(Object value)
    {
        return value instanceof Integer number ? number.intValue() : 0;
    }

    private static boolean isCommaSeparatedTypeLine(List<Object> parts)
    {
        int links = 0;
        for (Object part : parts)
        {
            if (part == null)
                continue;
            String cn = part.getClass().getName();
            if (LINK_PART.equals(cn))
            {
                links++;
                continue;
            }
            if (!TEXT_PART.equals(cn))
                continue;
            String text = str(BslDocCommentDescriptionFix.invokeCommentGetter(part, "getText")); //$NON-NLS-1$
            if (text != null && text.indexOf(',') >= 0)
                return true;
        }
        return links >= 2;
    }

    private static List<Object> typeLineParts(Object typeSection)
    {
        List<Object> out = new ArrayList<>();
        appendDescriptionParts(out, BslDocCommentDescriptionFix.invokeCommentGetter(typeSection, "getSourceDescription")); //$NON-NLS-1$
        // У секции с «:» currentDescription содержит вложенные поля, а не имена типов.
        if (!Boolean.TRUE.equals(BslDocCommentDescriptionFix.invokeCommentGetter(typeSection, "isExtensionPart"))) //$NON-NLS-1$
            appendDescriptionParts(out, BslDocCommentDescriptionFix.invokeCommentGetter(typeSection, "getCurrentDescription")); //$NON-NLS-1$
        return out;
    }

    private static void appendDescriptionParts(List<Object> out, Object description)
    {
        if (description == null)
            return;
        Object partsObj = BslDocCommentDescriptionFix.invokeCommentGetter(description, "getParts"); //$NON-NLS-1$
        if (!(partsObj instanceof List<?> parts) || parts.isEmpty())
            return;
        for (Object part : parts)
        {
            if (part != null)
                out.add(part);
        }
    }

    private static Integer lineNumber(Object part)
    {
        Object raw = BslDocCommentDescriptionFix.invokeCommentGetter(part, "getLineNumber"); //$NON-NLS-1$
        if (raw instanceof Integer line && line.intValue() > 0)
            return line;
        return null;
    }

    private static int addTypeIfMissing(Object typeSection, List<Object> defs, Set<String> names,
        Object linkPart, String rawName)
    {
        String text = rawName == null ? null : rawName.trim();
        if (linkPart != null)
        {
            if (text == null || text.isBlank() || "-".equals(text) || ".".equals(text)) //$NON-NLS-1$ //$NON-NLS-2$
                return 0;
        }
        else if (!isTypeName(text))
            return 0;
        String key = text.toLowerCase(Locale.ROOT);
        if (names.contains(key))
            return 0;
        Object created = createTypeDefinition(typeSection, linkPart, text);
        if (created == null)
            return 0;
        defs.add(created);
        names.add(key);
        return 1;
    }

    private static boolean isTypeName(String text)
    {
        if (text == null || text.isBlank())
            return false;
        if ("-".equals(text) || ".".equals(text) || ",".equals(text)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("см.") || lower.startsWith("see ")) //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        for (int i = 0; i < text.length(); i++)
        {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '.')
                continue;
            return false;
        }
        return true;
    }

    private static Object createTypeDefinition(Object typeSection, Object linkPart, String typeName)
    {
        ClassLoader cl = typeSection.getClass().getClassLoader();
        try
        {
            Class<?> linkDef = Class.forName(LINK_TYPE_DEF, true, cl);
            for (java.lang.reflect.Constructor<?> ctor : linkDef.getDeclaredConstructors())
            {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length != 2)
                    continue;
                if (!params[1].isInstance(linkPart))
                    continue;
                ctor.setAccessible(true);
                return ctor.newInstance(typeSection, linkPart);
            }
        }
        catch (Throwable t)
        {
        }
        try
        {
            Class<?> typeDef = Class.forName(TYPE_DEF, true, cl);
            java.lang.reflect.Constructor<?> ctor = typeDef.getConstructor(
                Class.forName("com._1c.g5.v8.dt.bsl.documentation.comment.IDescriptionPart", //$NON-NLS-1$
                    true, cl),
                String.class);
            return ctor.newInstance(typeSection, typeName);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private static String typeDefName(Object def)
    {
        if (def == null)
            return null;
        String name = str(BslDocCommentDescriptionFix.invokeCommentGetter(def, "getTypeName")); //$NON-NLS-1$
        if (name != null && !name.isBlank())
            return name.trim();
        Object link = BslDocCommentDescriptionFix.invokeCommentGetter(def, "getLink"); //$NON-NLS-1$
        return linkText(link);
    }

    private static String linkText(Object linkPart)
    {
        if (linkPart == null)
            return null;
        String text = str(BslDocCommentDescriptionFix.invokeCommentGetter(linkPart, "getInitialContent")); //$NON-NLS-1$
        if (text != null && !text.isBlank())
            return text.trim();
        text = str(BslDocCommentDescriptionFix.invokeCommentGetter(linkPart, "getText")); //$NON-NLS-1$
        return text == null ? null : text.trim();
    }

    private static Collection<?> expandTypeSets(Collection<?> types)
    {
        if (types == null || types.isEmpty())
            return types;
        List<TypeItem> out = new ArrayList<>();
        LinkedHashSet<TypeItem> seen = new LinkedHashSet<>();
        for (Object raw : types)
        {
            if (!(raw instanceof TypeItem item))
                continue;
            addExpanded(item, out, seen);
        }
        return out;
    }

    private static void addExpanded(TypeItem item, List<TypeItem> out, Set<TypeItem> seen)
    {
        if (item == null || !seen.add(item))
            return;
        if (item.eIsProxy())
        {
            out.add(item);
            return;
        }
        if (item instanceof TypeSet typeSet)
        {
            List<?> base = typeSet.getBaseTypes();
            if (base != null && !base.isEmpty())
            {
                for (Object nested : base)
                {
                    if (nested instanceof TypeItem nestedItem)
                        addExpanded(nestedItem, out, seen);
                }
                return;
            }
        }
        out.add(item);
    }

    private static void collectContextAndRest(Collection<?> types, List<Type> contexts,
        List<TypeItem> rest)
    {
        if (types == null)
            return;
        LinkedHashMap<Object, Type> byName = new LinkedHashMap<>();
        for (Object raw : types)
        {
            if (!(raw instanceof TypeItem item))
                continue;
            TypeItem resolved = resolveItem(item);
            if (resolved instanceof Type type && type.getContextDef() != null)
            {
                // У данных формы одно имя, но разные реквизиты порождающих объектов.
                // Объединяем все такие контексты, сохраняя свойства каждой альтернативы.
                Object key = "FormDataStructure".equals(McoreUtil.getTypeName(type)) //$NON-NLS-1$
                    ? type : typeKey(type);
                if (!byName.containsKey(key))
                    byName.put(key, type);
            }
            else
            {
                rest.add(item);
            }
        }
        contexts.addAll(byName.values());
    }

    private static TypeItem resolveItem(TypeItem item)
    {
        if (item == null || !item.eIsProxy())
            return item;
        EObject resolved = EcoreUtil.resolve(item, item);
        return resolved instanceof TypeItem typeItem ? typeItem : item;
    }

    /** Исходные варианты объединённого контекста: для сравнения типов, а не только их подписей. */
    public static List<TypeItem> alternatives(TypeItem type)
    {
        for (var adapter : type.eAdapters())
            if (adapter instanceof MergedAlternatives merged)
                return merged.types;
        return List.of(type);
    }

    private static final class MergedAlternatives extends AdapterImpl
    {
        final List<TypeItem> types;

        MergedAlternatives(List<? extends TypeItem> sources)
        {
            List<TypeItem> flattened = new ArrayList<>();
            for (TypeItem source : sources)
                flattened.addAll(alternatives(source));
            types = List.copyOf(flattened);
        }
    }

    private static Type mergeContexts(List<Type> contexts)
    {
        Type primary = pickPrimary(contexts);
        ExtendedType extended = BslFactory.eINSTANCE.createExtendedType();
        String en = joinNames(contexts, false);
        String ru = joinNames(contexts, true);
        extended.setName(en);
        extended.setNameRu(ru == null || ru.isBlank() ? en : ru);
        Environments envs = primary.getEnvironments();
        if (envs != null)
            extended.setEnvironments(new Environments(envs.toArray()));
        extended.setCreatedByNewOperator(primary.isCreatedByNewOperator());
        // Штатная проверка EDT запрещает обмен, если хотя бы один вариант непередаваем.
        extended.setExchangeWithServer(contexts.stream().allMatch(Type::isExchangeWithServer));
        ContextDefWithRefItem ctx = McoreFactory.eINSTANCE.createContextDefWithRefItem();
        LinkedHashSet<Method> methods = new LinkedHashSet<>();
        LinkedHashSet<Property> properties = new LinkedHashSet<>();
        for (Type type : contexts)
            collectRefContext(type, methods, properties, false);
        ctx.getRefMethods().addAll(methods);
        ctx.getRefProperties().addAll(properties);
        extended.setContextDef(ctx);
        extended.eAdapters().add(new MergedAlternatives(contexts));
        BslFormDataTypes.inheritOrigins(extended, contexts);
        return extended;
    }

    /**
     * Переносимый тип с {@link ContextDefWithRefItem}: методы в {@code refMethods},
     * свойства — ссылки или собственные {@link DerivedProperty} (экспорт чужого модуля).
     * Без плейсхолдера {@code ИмяКлюча}/{@code KeyName}.
     */
    static Type copyWithRefContext(Type source, Environments environments, boolean ownProperties)
    {
        if (source == null || source.eIsProxy())
            return source;
        ExtendedType copy = BslFactory.eINSTANCE.createExtendedType();
        String en = McoreUtil.getTypeName(source);
        String ru = McoreUtil.getTypeNameRu(source);
        copy.setName(en != null ? en : "Structure"); //$NON-NLS-1$
        copy.setNameRu(ru != null && !ru.isBlank() ? ru : copy.getName());
        if (environments != null)
            copy.setEnvironments(environments);
        else if (source.getEnvironments() != null)
            copy.setEnvironments(new Environments(source.getEnvironments().toArray()));
        copy.setCreatedByNewOperator(source.isCreatedByNewOperator());
        copy.setExchangeWithServer(source.isExchangeWithServer());
        // Без признака копия таблицы/массива для проверки «Для каждого» — не коллекция
        copy.setIterable(source.isIterable());
        ContextDefWithRefItem ctx = McoreFactory.eINSTANCE.createContextDefWithRefItem();
        LinkedHashSet<Method> methods = new LinkedHashSet<>();
        LinkedHashSet<Property> properties = new LinkedHashSet<>();
        collectRefContext(source, methods, properties, true);
        ctx.getRefMethods().addAll(methods);
        if (ownProperties)
        {
            for (Property property : properties)
                ctx.getProperties().add(copyDerivedProperty(property));
        }
        else
            ctx.getRefProperties().addAll(properties);
        copy.setContextDef(ctx);
        List<TypeItem> alternatives = alternatives(source);
        if (alternatives.size() != 1 || alternatives.get(0) != source)
            copy.eAdapters().add(new MergedAlternatives(alternatives));
        BslFormDataTypes.inheritOrigins(copy, List.of(source));
        return copy;
    }

    private static void collectRefContext(Type type, Set<Method> methods, Set<Property> properties,
        boolean skipStructureKeyPlaceholder)
    {
        ContextDef def = type.getContextDef();
        if (def == null)
            return;
        if (def.allMethods() != null)
            methods.addAll(def.allMethods());
        org.eclipse.emf.common.util.EList<Property> props = def.allProperties() != null
            ? def.allProperties()
            : def.getProperties();
        if (props == null)
            return;
        for (Property property : props)
        {
            if (property == null)
                continue;
            if (skipStructureKeyPlaceholder && isStructureKeyPlaceholder(property))
                continue;
            properties.add(property);
        }
    }

    /** Плейсхолдер ключа структуры в EDT — не член значения. */
    static boolean isStructureKeyPlaceholder(Property property)
    {
        String name = property.getName();
        if (name == null || name.isEmpty())
            return true;
        return "KeyName".equalsIgnoreCase(name) //$NON-NLS-1$
            || "ИмяКлюча".equalsIgnoreCase(name); //$NON-NLS-1$
    }

    private static DerivedProperty copyDerivedProperty(Property sourceProp)
    {
        DerivedProperty derived = McoreFactory.eINSTANCE.createDerivedProperty();
        derived.setName(sourceProp.getName());
        String nameRu = sourceProp.getNameRu();
        derived.setNameRu(nameRu != null && !nameRu.isBlank() ? nameRu : sourceProp.getName());
        derived.setReadable(true);
        derived.setWritable(true);
        derived.setEnvironments(Environments.ALL);
        TypeContainerRef propTypes = McoreFactory.eINSTANCE.createTypeContainerRef();
        Collection<TypeItem> sourceTypes = sourceProp.getTypeContainer() instanceof TypeContainerRef ref
            ? ref.getTypes()
            : sourceProp.getTypes();
        if (sourceTypes != null)
        {
            for (TypeItem propType : sourceTypes)
            {
                if (propType instanceof EObject eObject)
                {
                    TypeItem copy = (TypeItem)EcoreUtil.copy(eObject);
                    BslFormDataTypes.inheritOrigins(copy, List.of(propType));
                    propTypes.getTypes().add(copy);
                }
            }
        }
        derived.setTypeContainer(propTypes);
        return derived;
    }

    private static Type pickPrimary(List<Type> contexts)
    {
        for (Type type : contexts)
        {
            String name = McoreUtil.getTypeName(type);
            if ("ClientApplicationForm".equals(name) || "ManagedForm".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
                return type;
            String ru = McoreUtil.getTypeNameRu(type);
            if ("ФормаКлиентскогоПриложения".equals(ru) || "УправляемаяФорма".equals(ru)) //$NON-NLS-1$ //$NON-NLS-2$
                return type;
        }
        return contexts.get(0);
    }

    private static String joinNames(List<Type> types, boolean russian)
    {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Type type : types)
        {
            String name = russian ? McoreUtil.getTypeNameRu(type) : McoreUtil.getTypeName(type);
            if (name == null || name.isBlank())
                name = russian ? McoreUtil.getTypeName(type) : McoreUtil.getTypeNameRu(type);
            if (name != null && !name.isBlank())
                names.add(name);
        }
        return String.join(", ", names); //$NON-NLS-1$
    }

    private static String typeKey(Type type)
    {
        String name = McoreUtil.getTypeName(type);
        if (name != null && !name.isBlank())
            return name;
        String ru = McoreUtil.getTypeNameRu(type);
        return ru == null ? Integer.toHexString(System.identityHashCode(type)) : ru;
    }

    private static String str(Object value)
    {
        return value instanceof String s ? s : null;
    }

    static byte[] transformClass(byte[] classfileBuffer)
    {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final AtomicBoolean touched = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null)
                    return null;
                if ("computeTypesByLinkPart".equals(name)) //$NON-NLS-1$
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String called,
                            String desc, boolean isInterface)
                        {
                            super.visitMethodInsn(opcode, owner, called, desc, isInterface);
                            if (opcode == Opcodes.INVOKESTATIC
                                && "com/_1c/g5/v8/dt/bsl/documentation/comment/BslCommentUtils".equals(owner) //$NON-NLS-1$
                                && "getObjectByLinkPart".equals(called)) //$NON-NLS-1$
                            {
                                visitInsn(Opcodes.DUP);
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitMethodInsn(Opcodes.INVOKESTATIC, "tormozit/BslDocCommentComputedTypes", //$NON-NLS-1$
                                    "rememberDocReturnDependency", "(Ljava/lang/Object;Ljava/lang/Object;)V", false); //$NON-NLS-1$ //$NON-NLS-2$
                                touched.set(true);
                            }
                        }
                    };
                if (!isComputeTypesMethod(name, descriptor))
                    return mv;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitInsn(int opcode)
                    {
                        if (opcode == Opcodes.ARETURN)
                        {
                            if ("computeTypes".equals(name)) //$NON-NLS-1$
                            {
                                visitVarInsn(Opcodes.ALOAD, 1); // TypeSection
                                visitVarInsn(Opcodes.ALOAD, 8); // EObject context (EDT bsl.comment)
                                visitMethodInsn(Opcodes.INVOKESTATIC, SELF_INTERNAL,
                                    "afterComputeFormAttributeTypes", //$NON-NLS-1$
                                    "(Ljava/util/Collection;Ljava/lang/Object;Lorg/eclipse/emf/ecore/EObject;)Ljava/util/Collection;", //$NON-NLS-1$
                                    false);
                            }
                            visitMethodInsn(Opcodes.INVOKESTATIC, SELF_INTERNAL,
                                "afterComputeTypes", AFTER_COMPUTE_DESC, false); //$NON-NLS-1$
                            touched.set(true);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return touched.get() ? writer.toByteArray() : null;
    }

    private static boolean isComputeTypesMethod(String name, String descriptor)
    {
        if (name == null || descriptor == null || !descriptor.endsWith(")Ljava/util/Collection;")) //$NON-NLS-1$
            return false;
        // computeTypes — одна TypeSection. computeParameterTypes — все секции
        // одного параметра (EDT режет «Тип1, Тип2» на две секции без запятой).
        // computeReturnTypes не трогаем: он склеивает соседние строки возврата.
        return "computeTypes".equals(name) //$NON-NLS-1$
            || "computeParameterTypes".equals(name); //$NON-NLS-1$
    }

    private static final class ComputeTypesWeavingHook implements WeavingHook
    {
        @Override
        public void weave(WovenClass wovenClass)
        {
            if (!TARGET_COMMENT.equals(wovenClass.getClassName()))
                return;
            if (wovenClass.getState() != WovenClass.TRANSFORMING)
                return;
            try
            {
                byte[] transformed = transformClass(wovenClass.getBytes());
                if (transformed != null)
                {
                    wovenClass.getDynamicImports().add("tormozit"); //$NON-NLS-1$
                    wovenClass.setBytes(transformed);
                }
            }
            catch (Throwable t)
            {
            }
        }
    }

    private static final class ComputeTypesTransformer implements ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer)
        {
            if (!TARGET_COMMENT_INTERNAL.equals(className))
                return null;
            try
            {
                return transformClass(classfileBuffer);
            }
            catch (Throwable t)
            {
                return null;
            }
        }
    }
}
