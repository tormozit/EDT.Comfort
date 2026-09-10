// BslDocCommentTypeMerge.java
package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
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
import com._1c.g5.v8.dt.bsl.model.ExtendedType;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.ContextDefWithRefItem;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Method;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;

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

    public static void install()
    {
        boolean first = installed.compareAndSet(false, true);
        if (first)
        {
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
            if (context != null)
                context.registerService(WeavingHook.class, new ComputeTypesWeavingHook(), null);
        }

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
            if (contexts.size() < 2)
                return expanded == null ? types : expanded;
            Type merged = mergeContexts(contexts);
            if (merged == null)
                return expanded;
            List<TypeItem> out = new ArrayList<>();
            out.add(merged);
            out.addAll(rest);
            return out;
        }
        catch (Throwable t)
        {
            return types;
        }
    }

    private static int collectInParameters(Object comment)
    {
        Object section = Global.invoke(comment, "getParametersSection"); //$NON-NLS-1$
        if (section == null)
            return 0;
        Object fieldsObj = Global.invoke(section, "getParameterDefinitions"); //$NON-NLS-1$
        if (!(fieldsObj instanceof List<?> fields))
            return 0;
        int added = 0;
        for (Object field : fields)
        {
            if (field == null)
                continue;
            Object sectionsObj = Global.invoke(field, "getTypeSections"); //$NON-NLS-1$
            if (sectionsObj instanceof List<?> sections && sections.isEmpty()
                && fieldHasTypeParts(field))
            {
                Object created = createTypeSection(field);
                if (created != null)
                {
                    @SuppressWarnings("unchecked")
                    List<Object> writable = (List<Object>) sections;
                    writable.add(created);
                    added += collectFromParts(created, descriptionParts(
                        Global.invoke(field, "getDescription"))); //$NON-NLS-1$
                }
            }
            added += collectInTypeSections(sectionsObj);
        }
        return added;
    }

    private static int collectInReturn(Object comment)
    {
        Object section = Global.invoke(comment, "getReturnSection"); //$NON-NLS-1$
        if (section == null)
            return 0;
        return collectInTypeSections(Global.invoke(section, "getReturnTypes")); //$NON-NLS-1$
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
        Object fromDefsObj = Global.invoke(from, "getTypeDefinitions"); //$NON-NLS-1$
        Object toDefsObj = Global.invoke(to, "getTypeDefinitions"); //$NON-NLS-1$
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
        Object defsObj = Global.invoke(typeSection, "getTypeDefinitions"); //$NON-NLS-1$
        if (!(defsObj instanceof List<?>))
            return 0;
        List<Object> parts = typeLineParts(typeSection);
        if (!isCommaSeparatedTypeLine(parts))
            return 0;
        return collectFromParts(typeSection, parts);
    }

    private static int collectFromParts(Object typeSection, List<Object> parts)
    {
        if (typeSection == null || parts == null || parts.isEmpty())
            return 0;
        Object defsObj = Global.invoke(typeSection, "getTypeDefinitions"); //$NON-NLS-1$
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
                String text = str(Global.invoke(part, "getText")); //$NON-NLS-1$
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
        List<Object> parts = descriptionParts(Global.invoke(field, "getDescription")); //$NON-NLS-1$
        return isCommaSeparatedTypeLine(parts);
    }

    private static Object createTypeSection(Object field)
    {
        ClassLoader cl = field.getClass().getClassLoader();
        try
        {
            Class<?> typeSection = Class.forName(
                "com._1c.g5.v8.dt.bsl.documentation.comment.TypeSection", true, cl); //$NON-NLS-1$
            Class<?> part = Class.forName(
                "com._1c.g5.v8.dt.bsl.documentation.comment.IDescriptionPart", true, cl); //$NON-NLS-1$
            java.lang.reflect.Constructor<?> ctor = typeSection.getConstructor(part, int.class);
            return ctor.newInstance(field, Integer.valueOf(0));
        }
        catch (Throwable t)
        {
            return null;
        }
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
            String text = str(Global.invoke(part, "getText")); //$NON-NLS-1$
            if (text != null && text.indexOf(',') >= 0)
                return true;
        }
        return links >= 2;
    }

    private static List<Object> typeLineParts(Object typeSection)
    {
        List<Object> out = new ArrayList<>();
        appendDescriptionParts(out, Global.invoke(typeSection, "getSourceDescription")); //$NON-NLS-1$
        appendDescriptionParts(out, Global.invoke(typeSection, "getCurrentDescription")); //$NON-NLS-1$
        return out;
    }

    private static void appendDescriptionParts(List<Object> out, Object description)
    {
        if (description == null)
            return;
        Object partsObj = Global.invoke(description, "getParts"); //$NON-NLS-1$
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
        Object raw = Global.invoke(part, "getLineNumber"); //$NON-NLS-1$
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
        String name = str(Global.invoke(def, "getTypeName")); //$NON-NLS-1$
        if (name != null && !name.isBlank())
            return name.trim();
        Object link = Global.invoke(def, "getLink"); //$NON-NLS-1$
        return linkText(link);
    }

    private static String linkText(Object linkPart)
    {
        if (linkPart == null)
            return null;
        String text = str(Global.invoke(linkPart, "getInitialContent")); //$NON-NLS-1$
        if (text != null && !text.isBlank())
            return text.trim();
        text = str(Global.invoke(linkPart, "getText")); //$NON-NLS-1$
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
        LinkedHashMap<String, Type> byName = new LinkedHashMap<>();
        for (Object raw : types)
        {
            if (!(raw instanceof TypeItem item))
                continue;
            TypeItem resolved = resolveItem(item);
            if (resolved instanceof Type type && type.getContextDef() != null)
            {
                String key = typeKey(type);
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
        ContextDefWithRefItem ctx = McoreFactory.eINSTANCE.createContextDefWithRefItem();
        LinkedHashSet<Method> methods = new LinkedHashSet<>();
        LinkedHashSet<Property> properties = new LinkedHashSet<>();
        for (Type type : contexts)
        {
            ContextDef def = type.getContextDef();
            if (def == null)
                continue;
            if (def.allMethods() != null)
                methods.addAll(def.allMethods());
            if (def.allProperties() != null)
                properties.addAll(def.allProperties());
        }
        ctx.getRefMethods().addAll(methods);
        ctx.getRefProperties().addAll(properties);
        extended.setContextDef(ctx);
        return extended;
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
                if (!isComputeTypesMethod(name, descriptor))
                    return mv;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitInsn(int opcode)
                    {
                        if (opcode == Opcodes.ARETURN)
                        {
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
                    wovenClass.setBytes(transformed);
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
