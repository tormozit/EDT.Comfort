package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiFunction;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.xtext.naming.QualifiedName;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com.google.inject.Injector;

/**
 * Порядок отображения составного типа как в диалоге «Редактирование типа данных» (#768).
 * Сортируется копия для представления; TypeDescription и список модели не изменяются.
 *
 * <p>TypeDescriptionComponent$6 наследует createControlViewModel от общего компонента,
 * который может загрузиться до старта Комфорта. В класс самого поля добавляется override:
 * после штатного построения значение представления формируется из отсортированной копии
 * выбранных типов. Используются штатные TypeInfoSorter модели и ILabelProvider поля.
 * Колонки используют тот же порядок TypeInfo: priority, затем QualifiedName языка проекта.
 */
final class TypeDescriptionPresentationHook
{
    private static final String TARGET =
        "com._1c.g5.v8.dt.md.ui.aef.components.type.TypeDescriptionComponent$6"; //$NON-NLS-1$
    private static final String TYPE_FIELD =
        "com._1c.g5.v8.dt.md.ui.aef.components.type.TypeDescriptionComponent$6"; //$NON-NLS-1$
    private static final String CALLBACK = "tormozit.typeDescription.displayOrder"; //$NON-NLS-1$
    private static final String ITEMS_CALLBACK = "tormozit.typeDescription.sortedItems"; //$NON-NLS-1$
    private static final String BASE =
        "com._1c.g5.v8.dt.ui.aef.component.AbstractDtListActionBarComponent"; //$NON-NLS-1$
    private static final String DCS_TARGET = "com._1c.g5.v8.dt.dcs.ui.valueeditors.ValueEditorBase"; //$NON-NLS-1$
    private static final String DCS_CALLBACK = "tormozit.typeDescription.dcsText"; //$NON-NLS-1$
    private static volatile boolean dcsWoven;
    private static final Map<TypeDescription, CachedText> TEXTS = new WeakHashMap<>();

    private TypeDescriptionPresentationHook() {}

    static void install(BundleContext context)
    {
        System.getProperties().put(CALLBACK,
            (BiFunction<Object, Object, Object>) TypeDescriptionPresentationHook::formatField);
        System.getProperties().put(ITEMS_CALLBACK,
            (BiFunction<Object, Object, Object>) TypeDescriptionPresentationHook::displayItems);
        System.getProperties().put(DCS_CALLBACK,
            (BiFunction<Object, Object, Object>) TypeDescriptionPresentationHook::formatDcsText);
        context.registerService(WeavingHook.class, new PresentationWeavingHook(), null);
        // Проверяем установку, а не считаем сам факт регистрации успешным перехватом.
        // Для уже загруженного компонента схема класса неизменна: инструментируем метод
        // родителя через принятый в репозитории registerExtraTransformer.
        try
        {
            Bundle bundle = Platform.getBundle("com._1c.g5.v8.dt.md.ui"); //$NON-NLS-1$
            if (bundle == null)
                throw new IllegalStateException("Бандл md.ui не найден"); //$NON-NLS-1$
            Class<?> fieldClass = bundle.loadClass(TARGET);
            try
            {
                fieldClass.getDeclaredMethod("createControlViewModel"); //$NON-NLS-1$
            }
            catch (NoSuchMethodException alreadyLoaded)
            {
                BslDocCommentDescriptionFix.registerExtraTransformer(
                    new LoadedPresentationTransformer(BASE), BASE);
            }
        }
        catch (ClassNotFoundException | RuntimeException | LinkageError e)
        {
        }
        try
        {
            Bundle dcs = Platform.getBundle("com._1c.g5.v8.dt.dcs.ui"); //$NON-NLS-1$
            if (dcs != null)
            {
                dcs.loadClass(DCS_TARGET);
                if (!dcsWoven)
                    BslDocCommentDescriptionFix.registerExtraTransformer(
                        new LoadedPresentationTransformer(DCS_TARGET), DCS_TARGET);
            }
        }
        catch (ClassNotFoundException | RuntimeException | LinkageError e)
        {
        }
    }

    private static Object formatDcsText(Object text, Object arguments)
    {
        if (!(arguments instanceof Object[] args) || !(args[1] instanceof TypeDescription type))
            return text;
        try
        {
            if (type.getTypes().size() < 2)
            {
                return text;
            }
            Object data = Global.getField(args[0], "data"); //$NON-NLS-1$
            Object owner = Global.getField(data, "object"); //$NON-NLS-1$
            String result = sortText(type, owner instanceof EObject object ? object : null,
                text instanceof String string ? string : null,
                args[2] instanceof IV8Project project ? project : null);
            return result;
        }
        catch (RuntimeException | LinkageError e)
        {
            return text;
        }
    }

    private static Object formatField(Object viewModel, Object component)
    {
        try
        {
            Object items = Global.invoke(component, "getModel"); //$NON-NLS-1$
            if (!(items instanceof List<?> selected))
            {
                return viewModel;
            }
            if (selected.size() < 2)
            {
                return viewModel;
            }
            Object ordered = displayItems(items, component);
            Object labels = Global.getField(component, "val$labelProvider"); //$NON-NLS-1$
            if (!(ordered instanceof List<?> sorted) || !(labels instanceof ILabelProvider provider))
                throw new IllegalStateException("Нет провайдера представления поля типа"); //$NON-NLS-1$
            List<String> names = new ArrayList<>();
            for (Object item : sorted)
                names.add(provider.getText(item));
            String text = String.join(", ", names); //$NON-NLS-1$
            Global.invokeVoid(viewModel, "setValue", text); //$NON-NLS-1$
        }
        catch (RuntimeException | LinkageError e)
        {
        }
        return viewModel;
    }

    private static Object displayItems(Object items, Object component)
    {
        try
        {
            if (component == null || !TYPE_FIELD.equals(component.getClass().getName())
                || !(items instanceof List<?> selected))
            {
                return items;
            }
            if (selected.size() < 2)
            {
                return items;
            }
            Object owner = Global.getField(component, "this$0"); //$NON-NLS-1$
            Object model = Global.invoke(owner, "getModel"); //$NON-NLS-1$
            Object sorter = Global.getField(model, "sorter"); //$NON-NLS-1$
            if (!(sorter instanceof Comparator<?>))
                throw new IllegalStateException("Нет штатного компаратора типов"); //$NON-NLS-1$
            @SuppressWarnings("unchecked")
            Comparator<Object> comparator = (Comparator<Object>) sorter;
            Map<Object, Object> infos = new HashMap<>();
            for (Object value : selected)
                infos.put(value, Global.invoke(model, "getTypeInfo", value)); //$NON-NLS-1$
            List<Object> copy = new ArrayList<>(selected);
            copy.sort((left, right) -> comparator.compare(infos.get(left), infos.get(right)));
            return copy;
        }
        catch (RuntimeException | LinkageError e)
        {
            return items;
        }
    }

    static String sortText(TypeDescription type, EObject context, String text)
    {
        return sortText(type, context, text, null);
    }

    private static String sortText(TypeDescription type, EObject context, String text,
        IV8Project suppliedProject)
    {
        try
        {
            if (type == null || type.getTypes().size() < 2)
            {
                return text;
            }
            if (text == null || text.isEmpty())
            {
                return text;
            }
            // Штатные колонки соединяют отдельные названия через запятую. Другие подсказки
            // (квалификаторы, пояснения) не являются списком названий и остаются как есть.
            String[] parts = text.split(",\\s*", -1); //$NON-NLS-1$
            if (parts.length != type.getTypes().size())
            {
                return text;
            }
            EObject owner = context != null ? context : type.eContainer();
            if (owner == null && suppliedProject == null)
            {
                return text;
            }
            IV8ProjectManager manager = suppliedProject == null
                ? (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class) : null;
            IV8Project v8project = suppliedProject != null ? suppliedProject
                : manager != null ? manager.getProject(owner) : null;
            if (v8project == null)
            {
                return text;
            }
            boolean english = v8project.getScriptVariant() == ScriptVariant.ENGLISH;
            synchronized (TEXTS)
            {
                CachedText cached = TEXTS.get(type);
                if (cached != null && cached.english == english && cached.source.equals(text))
                {
                    return cached.result;
                }
            }
            SelectedClassifiers classifiers = SelectedClassifiers.get();
            Map<String, TypeOrder> orders = new HashMap<>();
            for (TypeItem item : type.getTypes())
            {
                TypeOrder order = typeOrder(item, english, classifiers, false);
                if (order == null)
                    continue;
                orders.put(McoreUtil.getTypeName(item), order);
                String nameRu = McoreUtil.getTypeNameRu(item);
                if (nameRu != null && !nameRu.isBlank())
                    orders.put(nameRu, order);
            }
            // Если хоть одно название не распознано, это не простой список типов.
            if (Arrays.stream(parts).anyMatch(part -> !orders.containsKey(part)))
            {
                return text;
            }
            Arrays.sort(parts, (left, right) -> orders.get(left).compareTo(orders.get(right)));
            String result = String.join(", ", parts); //$NON-NLS-1$
            synchronized (TEXTS)
            {
                TEXTS.put(type, new CachedText(text, english, result));
            }
            return result;
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            return text;
        }
    }

    /**
     * Порядок показа типов составного типа в подсказках — тот же, что в ячейках и полях ввода.
     *
     * @return индексы {@code types} в порядке показа или {@code null}, если порядок не определить
     */
    static int[] displayOrder(List<TypeItem> types, EObject context)
    {
        try
        {
            IV8ProjectManager manager = (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
            IV8Project v8project = manager != null && context != null ? manager.getProject(context) : null;
            if (v8project == null)
                return null;
            boolean english = v8project.getScriptVariant() == ScriptVariant.ENGLISH;
            SelectedClassifiers classifiers = SelectedClassifiers.get();
            TypeOrder[] orders = new TypeOrder[types.size()];
            for (int i = 0; i < orders.length; i++)
            {
                orders[i] = typeOrder(types.get(i), english, classifiers, true);
                if (orders[i] == null)
                    return null;
            }
            return java.util.stream.IntStream.range(0, orders.length).boxed()
                .sorted((left, right) -> orders[left].compareTo(orders[right]))
                .mapToInt(Integer::intValue).toArray();
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            return null;
        }
    }

    /**
     * Ключ TypeInfoSorter EDT для типа; {@code null}, если у типа нет имени.
     *
     * @param unclassifiedLast тип без классификатора у себя и предков (объекты метаданных —
     *            в поле «Тип» их не бывает) ставится после всех классифицированных; иначе — исключение
     */
    private static TypeOrder typeOrder(TypeItem item, boolean english, SelectedClassifiers classifiers,
        boolean unclassifiedLast) throws ReflectiveOperationException
    {
        String name = McoreUtil.getTypeName(item);
        if (name == null || name.isBlank())
            return null;
        // PropertyCategoryTypeInfoProvider.createTypeInfo использует первый
        // сегмент имени и признак TypeSet для того же классификатора EDT.
        // Наследники без своего классификатора (ОбъектМетаданныхДокумент → … → ОбъектМетаданных)
        // берут приоритет ближайшего предка.
        Integer priority = null;
        TypeItem current = item;
        for (int depth = 0; current != null && priority == null && depth < 32; depth++)
        {
            String currentName = McoreUtil.getTypeName(current);
            if (currentName != null && !currentName.isBlank())
            {
                int dot = currentName.indexOf('.');
                String category = dot < 0 ? currentName : currentName.substring(0, dot);
                priority = classifiers.findPriority(category, current instanceof TypeSet);
            }
            current = current instanceof Type type ? type.getParentType() : null;
        }
        if (priority == null && unclassifiedLast)
            priority = Integer.MAX_VALUE;
        if (priority == null)
            throw new IllegalArgumentException("Нет классификатора типа " + name); //$NON-NLS-1$
        String sortName = english ? name : McoreUtil.getTypeNameRu(item);
        QualifiedName sortCode = sortName != null && !sortName.isBlank()
            ? QualifiedName.create(sortName.split("\\.")) : null; //$NON-NLS-1$
        return new TypeOrder(priority, sortCode);
    }

    /** Штатный классификатор EDT; приоритеты категорий не зависят от проекта и списка типов. */
    private static final class SelectedClassifiers
    {
        private static volatile SelectedClassifiers instance;
        private final Object service;
        private final Method getClassifier;
        private final Field priority;
        private final Map<ClassifierKey, Integer> priorities = new HashMap<>();

        private SelectedClassifiers(Object service, Method getClassifier, Field priority)
        {
            this.service = service;
            this.getClassifier = getClassifier;
            this.priority = priority;
        }

        private static SelectedClassifiers get() throws ReflectiveOperationException
        {
            SelectedClassifiers result = instance;
            if (result != null)
                return result;
            synchronized (SelectedClassifiers.class)
            {
                if (instance == null)
                {
                    Bundle mdUi = Platform.getBundle("com._1c.g5.v8.dt.md.ui"); //$NON-NLS-1$
                    Bundle core = Platform.getBundle("com._1c.g5.v8.dt.platform.core"); //$NON-NLS-1$
                    if (mdUi == null || core == null)
                        throw new IllegalStateException("Не найдены бандлы классификатора EDT"); //$NON-NLS-1$
                    Object factory = mdUi.loadClass("com._1c.g5.v8.dt.md.ui.MdExecutableExtensionFactory") //$NON-NLS-1$
                        .getConstructor().newInstance();
                    Object nativeInjector = Global.invoke(factory, "getInjector"); //$NON-NLS-1$
                    if (!(nativeInjector instanceof Injector injector))
                        throw new IllegalStateException("Не найден штатный инжектор метаданных"); //$NON-NLS-1$
                    Class<?> api = core.loadClass(
                        "com._1c.g5.v8.dt.platform.core.typeinfo.category.IClassifiersTypeInfoServiceProvider"); //$NON-NLS-1$
                    Object service = injector.getInstance(api);
                    Method getter = api.getMethod("getClassifier", String.class, boolean.class); //$NON-NLS-1$
                    Field priority = core.loadClass("com._1c.g5.v8.dt.platform.core.typeinfo.TypeInfo$TypeClassifiers") //$NON-NLS-1$
                        .getField("priority"); //$NON-NLS-1$
                    instance = new SelectedClassifiers(service, getter, priority);
                }
                return instance;
            }
        }

        /** Приоритет категории или {@code null}, если у неё нет классификатора. */
        private synchronized Integer findPriority(String category, boolean typeSet)
            throws ReflectiveOperationException
        {
            ClassifierKey key = new ClassifierKey(category, typeSet);
            if (priorities.containsKey(key))
            {
                return priorities.get(key);
            }
            Object classifier = getClassifier.invoke(service, category, typeSet);
            Integer result = classifier == null ? null : priority.getInt(classifier);
            priorities.put(key, result);
            return result;
        }

        private static record ClassifierKey(String category, boolean typeSet) {}
    }

    private static record CachedText(String source, boolean english, String result) {}

    /** Сравнение TypeInfoSorter EDT: приоритет, затем код на языке конфигурации. */
    private static record TypeOrder(int priority, QualifiedName name) implements Comparable<TypeOrder>
    {
        @Override
        public int compareTo(TypeOrder other)
        {
            int result = Integer.compare(priority, other.priority);
            return result != 0 ? result : name != null && other.name != null ? name.compareTo(other.name) : 0;
        }
    }

    private static final class PresentationWeavingHook implements WeavingHook
    {
        @Override
        public void weave(WovenClass wovenClass)
        {
            boolean dcs = DCS_TARGET.equals(wovenClass.getClassName());
            if (wovenClass.getState() != WovenClass.TRANSFORMING || !TARGET.equals(wovenClass.getClassName()) && !dcs)
                return;
            try
            {
                byte[] bytes = dcs ? transformDcs(wovenClass.getBytes()) : transform(wovenClass.getBytes());
                if (bytes != null)
                {
                    wovenClass.setBytes(bytes);
                    if (dcs)
                        dcsWoven = true;
                }
            }
            catch (RuntimeException | LinkageError e)
            {
            }
        }
    }

    private static final class LoadedPresentationTransformer implements ClassFileTransformer
    {
        private final String target;

        private LoadedPresentationTransformer(String target)
        {
            this.target = target;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] bytes)
        {
            if (!target.replace('.', '/').equals(className))
                return null;
            boolean dcs = DCS_TARGET.equals(target);
            try
            {
                byte[] result = dcs ? transformDcs(bytes) : transformBase(bytes);
                return result;
            }
            catch (RuntimeException | LinkageError e)
            {
                return null;
            }
        }
    }

    private static byte[] transformDcs(byte[] bytes)
    {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] changed = { false };
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"getText".equals(name) || !descriptor.equals( //$NON-NLS-1$
                    "(Lcom/_1c/g5/v8/dt/core/platform/IV8Project;Ljava/lang/Object;)Ljava/lang/String;")) //$NON-NLS-1$
                    return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate)
                {
                    @Override
                    public void visitInsn(int opcode)
                    {
                        if (opcode == Opcodes.ARETURN)
                        {
                            emitCallbackHead(delegate, DCS_CALLBACK);
                            delegate.visitInsn(Opcodes.ICONST_3);
                            delegate.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                            // [editor, value, v8project] — исходные аргументы getText.
                            int[] locals = { 0, 2, 1 };
                            for (int i = 0; i < locals.length; i++)
                            {
                                delegate.visitInsn(Opcodes.DUP);
                                delegate.visitInsn(Opcodes.ICONST_0 + i);
                                delegate.visitVarInsn(Opcodes.ALOAD, locals[i]);
                                delegate.visitInsn(Opcodes.AASTORE);
                            }
                            emitCallbackTail(delegate, "java/lang/String"); //$NON-NLS-1$
                            changed[0] = true;
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : null;
    }

    private static byte[] transformBase(byte[] bytes)
    {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] changed = { false };
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"createControlViewModel".equals(name) || !descriptor.equals( //$NON-NLS-1$
                    "()Lcom/_1c/g5/v8/dt/ui/aef/viewModels/ActionBarViewModel;")) //$NON-NLS-1$
                    return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate)
                {
                    @Override
                    public void visitTypeInsn(int opcode, String type)
                    {
                        super.visitTypeInsn(opcode, type);
                        if (opcode == Opcodes.CHECKCAST && "java/util/List".equals(type)) //$NON-NLS-1$
                        {
                            emitCallback(delegate, ITEMS_CALLBACK, "java/util/List"); //$NON-NLS-1$
                            changed[0] = true;
                        }
                    }
                };
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : null;
    }

    static byte[] transform(byte[] bytes)
    {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] exists = { false };
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                if ("createControlViewModel".equals(name) && descriptor.equals( //$NON-NLS-1$
                    "()Lcom/_1c/g5/v8/dt/ui/aef/viewModels/ActionBarViewModel;")) //$NON-NLS-1$
                    exists[0] = true;
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            @Override
            public void visitEnd()
            {
                if (!exists[0])
                {
                    MethodVisitor mv = super.visitMethod(Opcodes.ACC_PROTECTED, "createControlViewModel", //$NON-NLS-1$
                        "()Lcom/_1c/g5/v8/dt/ui/aef/viewModels/ActionBarViewModel;", null, null); //$NON-NLS-1$
                    mv.visitCode();
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, reader.getSuperName(), "createControlViewModel", //$NON-NLS-1$
                        "()Lcom/_1c/g5/v8/dt/ui/aef/viewModels/ActionBarViewModel;", false); //$NON-NLS-1$
                    emitFormatField(mv);
                    mv.visitInsn(Opcodes.ARETURN);
                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                }
                super.visitEnd();
            }
        }, 0);
        return exists[0] ? null : writer.toByteArray();
    }

    /** Стек: ActionBarViewModel -> BiFunction.apply(viewModel, component) -> ActionBarViewModel. */
    private static void emitFormatField(MethodVisitor mv)
    {
        emitCallback(mv, CALLBACK, "com/_1c/g5/v8/dt/ui/aef/viewModels/ActionBarViewModel"); //$NON-NLS-1$
    }

    private static void emitCallback(MethodVisitor mv, String property, String resultType)
    {
        emitCallbackHead(mv, property);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        emitCallbackTail(mv, resultType);
    }

    private static void emitCallbackHead(MethodVisitor mv, String property)
    {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
            "()Ljava/util/Properties;", false); //$NON-NLS-1$
        mv.visitLdcInsn(property);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction"); //$NON-NLS-1$
        mv.visitInsn(Opcodes.SWAP);
    }

    private static void emitCallbackTail(MethodVisitor mv, String resultType)
    {
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
        mv.visitTypeInsn(Opcodes.CHECKCAST, resultType);
    }
}

