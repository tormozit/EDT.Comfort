package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.internal.text.html.HTMLPrinter;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.hover.IEObjectHoverProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.mcore.DerivedProperty;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.md.resource.MdTypeUtil;

/** Основа типов данных настоящих форм EDT и виртуальных реквизитов из комментариев. */
public final class BslFormDataTypes
{
    private static final String CONVERTER =
        "com._1c.g5.v8.dt.internal.platform.core.uconverter.UObjectConverterServiceProvider"; //$NON-NLS-1$
    private static final String DOCUMENTATION =
        "com._1c.g5.v8.dt.internal.bsl.ui.documentation.BslDocumentationProvider"; //$NON-NLS-1$
    private static final String PAGE_UTIL =
        "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.PageUtil"; //$NON-NLS-1$
    private static final String SELF = "tormozit/BslFormDataTypes"; //$NON-NLS-1$
    private static final String ITEM = "Lcom/_1c/g5/v8/dt/mcore/TypeItem;"; //$NON-NLS-1$
    private static final String BASE_ATTRIBUTE = "data-comfort-form-base"; //$NON-NLS-1$
    private static final Pattern BASE_TYPE = Pattern.compile(BASE_ATTRIBUTE + "=\"([^\"]+)\""); //$NON-NLS-1$
    private static final AtomicBoolean weavingInstalled = new AtomicBoolean();
    private static final AtomicBoolean transformerInstalled = new AtomicBoolean();
    // Значения содержат только строки, не удерживают модель проекта через слабый ключ.
    private static final Map<TypeItem, List<Origin>> ORIGINS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, List<Origin>> REFERENCES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, String> FORM_REFERENCES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, CommonModule> COMMON_MODULE_REFERENCES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Property, Property> PROPERTY_DOCUMENTATION = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<TypeItem, TypeItem> CONTENT_TYPES = Collections.synchronizedMap(new WeakHashMap<>());

    private record Origin(String typeName, String label) {}

    private BslFormDataTypes() {}

    static void installWeavingHook()
    {
        if (!weavingInstalled.compareAndSet(false, true))
            return;
        System.getProperties().put("tormozit.formData.convert", //$NON-NLS-1$
            (java.util.function.Function<Object[], Object>) args -> afterConvert((TypeItem)args[0], (TypeItem)args[1]));
        System.getProperties().put("tormozit.formData.reference", //$NON-NLS-1$
            (java.util.function.Function<Object[], Object>) args -> afterReference(args[0], (TypeItem)args[1], (Resource)args[2]));
        System.getProperties().put("tormozit.formData.html", //$NON-NLS-1$
            (java.util.function.Function<Object[], Object>) args -> afterReferenceHtml((String)args[0], args[1]));
        System.getProperties().put("tormozit.formData.property", //$NON-NLS-1$
            (java.util.function.Function<Object[], Object>) args -> documentationProperty((Property)args[0],
                args.length > 1 ? (EObject)args[1] : null));
        if (earlyWeavingActive())
            return;
        var bundle = FrameworkUtil.getBundle(BslFormDataTypes.class);
        var context = bundle == null ? null : bundle.getBundleContext();
        if (context != null)
            context.registerService(WeavingHook.class, new PresentationWeavingHook(), null);
    }

    static void install()
    {
        if (earlyWeavingActive())
            return;
        if (transformerInstalled.get())
            return;
        if (BslDocCommentDescriptionFix.registerExtraTransformer(new PresentationTransformer(),
            CONVERTER, DOCUMENTATION, PAGE_UTIL))
            transformerInstalled.set(true);
    }

    static boolean earlyWeavingActive()
    {
        return Boolean.TRUE.equals(System.getProperties().get("tormozit.formData.early")); //$NON-NLS-1$
    }

    /** Строка списка использует типы Property напрямую, без HTML-провайдера EDT. */
    static String decorateProposal(String display, Object info)
    {
        if (info instanceof com._1c.g5.v8.dt.bsl.model.FeatureEntry entry)
            return decorateProposal(display, entry.getFeature());
        if (info instanceof com._1c.g5.v8.dt.bsl.model.ProposalElement element)
        {
            String result = display;
            for (EObject member : element.getList())
                result = decorateProposal(result, member);
            return result;
        }
        if (display == null || !(info instanceof com._1c.g5.v8.dt.mcore.Property property))
            return display;
        try
        {
            String result = display;
            Map<String, String> contents = contentLabels(property, null);
            for (TypeItem type : property.getTypes())
            {
                if (type == null || type.eIsProxy())
                    continue;
                String content = contents.get(McoreUtil.getTypeName(type));
                if (content != null)
                    for (String base : new String[] {McoreUtil.getTypeName(type), McoreUtil.getTypeNameRu(type)})
                        if (base != null && !base.isBlank())
                            result = result.replace(base + ">", base + ": " + content + ">") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                .replace(base + ",", base + ": " + content + ","); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                TypeItem shown = FormItemTypes.displayType(type);
                if (shown != type)
                {
                    String base = McoreUtil.getTypeNameRu(type);
                    String extension = McoreUtil.getTypeNameRu(shown);
                    if (base != null && extension != null)
                        result = result.replace(base + ">", extension + ">") //$NON-NLS-1$ //$NON-NLS-2$
                            .replace(base + ",", extension + ",") //$NON-NLS-1$ //$NON-NLS-2$
                            .replace(base + " |", extension + " |"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                String form = BslFormTypePresentation.text(type);
                if (form != null)
                {
                    String name = McoreUtil.getTypeNameRu(type);
                    if (name != null && !name.isBlank())
                        result = result.replace(name + ">", form + ">") //$NON-NLS-1$ //$NON-NLS-2$
                            .replace(name + ",", form + ","); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (!isFormData(type))
                    continue;
                List<Origin> origins = origins(type);
                if (origins.isEmpty())
                    continue;
                String name = McoreUtil.getTypeNameRu(type);
                if (name == null || name.isBlank())
                    continue;
                String suffix = String.join(", ", origins.stream().map(Origin::label).toList()); //$NON-NLS-1$
                result = result.replace(name + ">", name + ": " + suffix + ">") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .replace(name + ",", name + ": " + suffix + ","); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            return result;
        }
        catch (RuntimeException | LinkageError failure)
        {
            return display;
        }
    }

    /** Результат штатного конвертера: связь фиксируется и для натуральных форм, включая вложенные типы. */
    public static TypeItem afterConvert(TypeItem result, TypeItem source)
    {
        try
        {
            if (!isFormData(result) || source == null)
                return result;
            rememberPropertyDocumentation(result, List.of(source));
            Origin origin = origin(source);
            if (origin != null)
                ORIGINS.put(result, List.of(origin));
        }
        catch (RuntimeException | LinkageError failure)
        {
        }
        return result;
    }

    private static boolean isFormData(TypeItem type)
    {
        String name = type == null ? null : McoreUtil.getTypeName(type);
        return name != null && name.startsWith("FormData"); //$NON-NLS-1$
    }

    private static Origin origin(TypeItem source)
    {
        if (source == null || source.eIsProxy())
            return null;
        MdObject producer = MdTypeUtil.getTypeProducer(source);
        String typeName = McoreUtil.getTypeName(source);
        if (producer == null || typeName == null)
            return null;
        List<String> path = new ArrayList<>();
        for (EObject object = producer; object != null; object = object.eContainer())
        {
            if (object instanceof Configuration)
                break;
            if (!(object instanceof MdObject md))
                continue;
            String kind = MdTypeMapping.anyToRu(md.eClass().getName());
            if (kind != null && md.getName() != null && !md.getName().isBlank())
                path.add(0, kind + "." + md.getName()); //$NON-NLS-1$
        }
        return path.isEmpty() ? null : new Origin(typeName, String.join(".", path)); //$NON-NLS-1$
    }

    /** Слияние и перенос контекстов не должны терять порождающие объекты. */
    static void inheritOrigins(TypeItem target, List<? extends TypeItem> sources)
    {
        BslFormTypePresentation.inherit(target, sources);
        if (!isFormData(target))
            return;
        rememberPropertyDocumentation(target, sources);
        LinkedHashSet<Origin> origins = new LinkedHashSet<>();
        for (TypeItem source : sources)
            origins.addAll(origins(source));
        if (!origins.isEmpty())
            ORIGINS.put(target, List.copyOf(origins));
    }

    /** Клону свойства нужна документация исходного владельца, а не FormDataStructure с code=-1. */
    private static void rememberPropertyDocumentation(TypeItem target, List<? extends TypeItem> sources)
    {
        if (!(target instanceof com._1c.g5.v8.dt.mcore.Type converted) || converted.getContextDef() == null)
            return;
        Map<String, Property> originals = new java.util.LinkedHashMap<>();
        for (TypeItem source : sources)
            if (source instanceof com._1c.g5.v8.dt.mcore.Type type && type.getContextDef() != null)
                for (Property property : type.getContextDef().allProperties())
                    originals.putIfAbsent(property.getName(), PROPERTY_DOCUMENTATION.getOrDefault(property, property));
        for (Property property : converted.getContextDef().getProperties())
        {
            Property original = originals.get(property.getName());
            if (!(property instanceof DerivedProperty) && original != null && original != property)
            {
                PROPERTY_DOCUMENTATION.put(property, original);
            }
        }
    }

    /** Обе подсказки и синтакс-помощник строят платформенную страницу через этот Property. */
    public static Property documentationProperty(Property property)
    {
        return documentationProperty(property, null);
    }

    /** Копии только для документации: рассчитанный тип и исходная модель не меняются. */
    public static Property documentationProperty(Property property, EObject context)
    {
        Property original = PROPERTY_DOCUMENTATION.getOrDefault(property, property);
        Map<String, TypeItem> contents = contentTypes(original, context);
        if (contents.isEmpty())
            return original;
        Property copy = org.eclipse.xtext.EcoreUtil2.cloneWithProxies(original);
        var container = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createTypeContainerRef();
        for (TypeItem item : original.getTypes())
        {
            TypeItem content = contents.get(McoreUtil.getTypeName(item));
            if (content == null)
                container.getTypes().add(item);
            else
            {
                TypeItem shown = org.eclipse.xtext.EcoreUtil2.cloneWithProxies(item);
                CONTENT_TYPES.put(shown, content);
                container.getTypes().add(shown);
            }
        }
        copy.setTypeContainer(container);
        return copy;
    }

    private static Map<String, String> contentLabels(Property property, EObject context)
    {
        Map<String, String> labels = new java.util.HashMap<>();
        contentTypes(property, context).forEach((name, content) -> {
            String label = McoreUtil.getTypeNameRu(content);
            if (label == null || label.isBlank())
                label = McoreUtil.getTypeName(content);
            if (label != null && !label.isBlank())
                labels.put(name, label);
        });
        return labels;
    }

    private static Map<String, TypeItem> contentTypes(Property property, EObject context)
    {
        if (!(property instanceof DerivedProperty derived) || derived.getSource() == null)
            return Map.of();
        try
        {
            EObject source = derived.getSource();
            if (source.eIsProxy())
                source = EcoreUtil.resolve(source, context != null ? context : property);
            if (!(source instanceof com._1c.g5.v8.dt.metadata.mdclass.BasicFeature)
                || !BslDocCommentComputedTypes.isExtendedTypesEnabled(source))
                return Map.of();
            Map<String, TypeItem> result = new java.util.HashMap<>();
            for (String name : List.of("ValueStorage", "String")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                var content = BslInvocationTypeRules.metadataContentType(source, name, context);
                if (content != null)
                    result.put(name, content);
            }
            return result;
        }
        catch (java.util.concurrent.CancellationException | org.eclipse.core.runtime.OperationCanceledException canceled)
        {
            throw canceled;
        }
        catch (RuntimeException | LinkageError failure)
        {
            return Map.of();
        }
    }

    private static List<Origin> origins(TypeItem type)
    {
        List<Origin> stored = ORIGINS.get(type);
        if (stored != null)
            return stored;
        // Штатная модель формы, рассчитанная до установки хука: тип принадлежит
        // DerivedProperty, source которого FormImplicitRefInferrer заполняет FormAttribute.
        EObject container = type.eContainer();
        if (container != null && container.eContainer() instanceof DerivedProperty property
            && property.getSource() instanceof FormAttribute attribute && attribute.getValueType() != null
            && attribute.getValueType().getTypes().size() == 1)
        {
            TypeItem source = attribute.getValueType().getTypes().get(0);
            source = (TypeItem)EcoreUtil.resolve(source, attribute);
            Origin origin = origin(source);
            if (origin != null)
                return List.of(origin);
        }
        return List.of();
    }

    /** Общий провайдер обеих подсказок: исходный IPageReference остаётся штатным. */
    public static Object afterReference(Object reference, TypeItem type, Resource resource)
    {
        try
        {
            if (reference != null && type != null)
            {
                TypeItem content = CONTENT_TYPES.get(type);
                if (content != null)
                    return contentReference(reference, type, content, resource);
                TypeItem resolved = (TypeItem)EcoreUtil.resolve(type, resource);
                // EDT здесь подменяет тип DerivedProperty общей платформенной ссылкой CommonModule.
                // Сохраняем source до потери конкретного модуля; путь подтверждён в EDT BslDocumentationProvider.
                EObject container = resolved.eContainer();
                if (container != null && container.eContainer() instanceof DerivedProperty property
                    && property.getSource() instanceof CommonModule module)
                {
                    COMMON_MODULE_REFERENCES.put(reference, module);
                    return reference;
                }
                String form = BslFormTypePresentation.html(resolved);
                if (form != null)
                {
                    FORM_REFERENCES.put(reference, form);
                    return reference;
                }
                TypeItem shown = FormItemTypes.displayType(resolved);
                if (shown != resolved)
                {
                    // Тот же провайдер даёт и имя, и действующую ссылку на страницу расширения.
                    var services = resource != null ? IResourceServiceProvider.Registry.INSTANCE
                        .getResourceServiceProvider(resource.getURI()) : null;
                    var hover = services != null ? services.get(IEObjectHoverProvider.class) : null;
                    Object provider = Global.getField(hover, "documentationProvider"); //$NON-NLS-1$
                    Object version = Global.invoke(provider, "getVersion", resolved); //$NON-NLS-1$
                    Object specialized = Global.invoke(provider, "convertTypeItemToReference", //$NON-NLS-1$
                        shown, resource, true, version, false);
                    if (specialized != null)
                        return specialized;
                    String name = McoreUtil.getTypeNameRu(shown);
                    if (name != null)
                        FORM_REFERENCES.put(reference, HTMLPrinter.convertToHTMLContent(name));
                }
                if (isFormData(resolved))
                {
                    List<Origin> origins = origins(resolved);
                    if (!origins.isEmpty())
                        REFERENCES.put(reference, origins);
                }
            }
        }
        catch (RuntimeException | LinkageError failure)
        {
        }
        return reference;
    }

    /** Как у массива с элементами: две штатные ссылки в PlatformReference без внешней ссылки. */
    private static Object contentReference(Object reference, TypeItem base, TypeItem content, Resource resource)
    {
        try
        {
            // Конструктор и поля подтверждены в bsl-ui/PlatformReference.content-type.javap.txt.
            Class<?> referenceClass = reference.getClass();
            if (!referenceClass.getName().equals(
                "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.PlatformReference")) //$NON-NLS-1$
            {
                return reference;
            }
            String first = (String)Global.getField(reference, "firstName"); //$NON-NLS-1$
            Object provider = BslSyntaxAssist.documentationProvider();
            EObject context = resource != null && !resource.getContents().isEmpty()
                ? resource.getContents().get(0) : content;
            Object version = Global.invoke(provider, "getVersion", context); //$NON-NLS-1$
            boolean russian = !java.util.Objects.equals(first, McoreUtil.getTypeName(base));
            Object nested = Global.invoke(provider, "convertTypeItemToReference", //$NON-NLS-1$
                content, resource, Boolean.valueOf(russian), version, Boolean.FALSE);
            if (nested == null)
            {
                return reference;
            }
            String shown = BslStructureInsertCommentTypes.typeReferenceHtml(reference) + ": " //$NON-NLS-1$
                + BslStructureInsertCommentTypes.typeReferenceHtml(nested);
            Object result = referenceClass.getConstructor(String.class, String.class, String.class)
                .newInstance(null, shown, null);
            return result;
        }
        catch (java.util.concurrent.CancellationException | org.eclipse.core.runtime.OperationCanceledException canceled)
        {
            throw canceled;
        }
        catch (Exception failure)
        {
            return reference;
        }
    }

    /** После штатного anchor, чтобы имя метаданного получило отдельную ссылку. */
    public static String afterReferenceHtml(String html, Object reference)
    {
        CommonModule module = COMMON_MODULE_REFERENCES.get(reference);
        if (html != null && module != null)
            return MetadataTypeMenu.commonModuleReference(module);
        String form = FORM_REFERENCES.get(reference);
        if (html != null && form != null)
            return form;
        List<Origin> origins = REFERENCES.get(reference);
        if (html == null || origins == null || origins.isEmpty())
            return html;
        StringBuilder result = new StringBuilder(html).append(": "); //$NON-NLS-1$
        for (int index = 0; index < origins.size(); index++)
        {
            if (index > 0)
                result.append(", "); //$NON-NLS-1$
            Origin origin = origins.get(index);
            result.append("<a href=\"#\" ").append(BASE_ATTRIBUTE).append("=\"") //$NON-NLS-1$ //$NON-NLS-2$
                .append(HTMLPrinter.convertToHTMLContent(origin.typeName())).append("\">") //$NON-NLS-1$
                .append(HTMLPrinter.convertToHTMLContent(origin.label())).append("</a>"); //$NON-NLS-1$
        }
        return result.toString();
    }

    /** Имя исходного типа для штатного перехода к метаданному в MetadataTypeMenu. */
    static String baseTypeName(String anchor)
    {
        var matcher = BASE_TYPE.matcher(anchor);
        return matcher.find() ? matcher.group(1) : null;
    }

    static byte[] transform(String className, byte[] bytes)
    {
        if (!CONVERTER.equals(className) && !DOCUMENTATION.equals(className) && !PAGE_UTIL.equals(className))
            return null;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        AtomicBoolean touched = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                Type[] args = Type.getArgumentTypes(descriptor);
                boolean convert = CONVERTER.equals(className) && "convert".equals(name) //$NON-NLS-1$
                    && args.length >= 3 && ITEM.equals(args[0].getDescriptor())
                    && ITEM.equals(Type.getReturnType(descriptor).getDescriptor());
                boolean reference = DOCUMENTATION.equals(className) && "convertTypeItemToReference".equals(name) //$NON-NLS-1$
                    && args.length == 5 && ITEM.equals(args[0].getDescriptor());
                boolean html = PAGE_UTIL.equals(className) && ("generateReferenceFullHtml".equals(name) //$NON-NLS-1$
                    || "generateReferenceShortHtml".equals(name)) && args.length == 2; //$NON-NLS-1$
                boolean property = DOCUMENTATION.equals(className) && "getDocByProperty".equals(name) //$NON-NLS-1$
                    && descriptor.startsWith("(Lcom/_1c/g5/v8/dt/mcore/Property;"); //$NON-NLS-1$
                if (visitor == null || !(convert || reference || html || property))
                    return visitor;
                return new MethodVisitor(Opcodes.ASM9, visitor)
                {
                    @Override
                    public void visitCode()
                    {
                        super.visitCode();
                        if (property)
                        {
                            visitVarInsn(Opcodes.ALOAD, 1);
                            visitVarInsn(Opcodes.ALOAD, 3);
                            visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "documentationProperty", //$NON-NLS-1$
                                "(Lcom/_1c/g5/v8/dt/mcore/Property;Lorg/eclipse/emf/ecore/EObject;)Lcom/_1c/g5/v8/dt/mcore/Property;", false); //$NON-NLS-1$
                            visitVarInsn(Opcodes.ASTORE, 1);
                            touched.set(true);
                        }
                    }

                    @Override
                    public void visitInsn(int opcode)
                    {
                        if (opcode == Opcodes.ARETURN && !property)
                        {
                            if (convert)
                            {
                                visitVarInsn(Opcodes.ALOAD, 1);
                                visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "afterConvert", //$NON-NLS-1$
                                    "(" + ITEM + ITEM + ")" + ITEM, false); //$NON-NLS-1$ //$NON-NLS-2$
                            }
                            else if (reference)
                            {
                                visitVarInsn(Opcodes.ALOAD, 1);
                                visitVarInsn(Opcodes.ALOAD, 2);
                                visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "afterReference", //$NON-NLS-1$
                                    "(Ljava/lang/Object;" + ITEM + "Lorg/eclipse/emf/ecore/resource/Resource;)Ljava/lang/Object;", false); //$NON-NLS-1$ //$NON-NLS-2$
                                visitTypeInsn(Opcodes.CHECKCAST, Type.getReturnType(descriptor).getInternalName());
                            }
                            else
                            {
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "afterReferenceHtml", //$NON-NLS-1$
                                    "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;", false); //$NON-NLS-1$
                            }
                            touched.set(true);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return touched.get() ? writer.toByteArray() : null;
    }

    private static final class PresentationWeavingHook implements WeavingHook
    {
        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING)
                return;
            byte[] bytes = transform(woven.getClassName(), woven.getBytes());
            if (bytes != null)
            {
                woven.getDynamicImports().add("tormozit"); //$NON-NLS-1$
                woven.setBytes(bytes);
            }
        }
    }

    private static final class PresentationTransformer implements ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
            ProtectionDomain domain, byte[] bytes)
        {
            return name == null ? null : BslFormDataTypes.transform(name.replace('/', '.'), bytes);
        }
    }
}
