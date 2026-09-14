// BslDocCommentDescriptionFix.java
package tormozit;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;
import org.eclipse.xtext.resource.IResourceServiceProvider;

/**
 * Корневой фикс EDT: хвост {@code Имя - Тип - описание} из
 * {@code TypeSection.getDescription()} (Section) → {@code FieldDefinition.description}.
 * {@code sourceDescription} хранит имя типа — его не трогаем.
 * <p>
 * Установка: WeavingHook + постоянный Instrumentation transformer.
 * Запасной путь: {@link #recoverParamDescription} (re-parse) для уже собранного ValueContent.
 */
public final class BslDocCommentDescriptionFix
{
    static final String PROP_AFTER_PARSE = "tormozit.bslDocComment.afterParse"; //$NON-NLS-1$
    private static final String TARGET_COMMENT =
        "com._1c.g5.v8.dt.bsl.documentation.comment.BslDocumentationComment"; //$NON-NLS-1$
    private static final String TARGET_COMMENT_INTERNAL =
        "com/_1c/g5/v8/dt/bsl/documentation/comment/BslDocumentationComment"; //$NON-NLS-1$
    private static final String TARGET_UTILS =
        "com._1c.g5.v8.dt.bsl.documentation.comment.BslCommentUtils"; //$NON-NLS-1$
    private static final String TARGET_UTILS_INTERNAL =
        "com/_1c/g5/v8/dt/bsl/documentation/comment/BslCommentUtils"; //$NON-NLS-1$
    private static final String PARSE_VOID_DESC = "(Ljava/util/List;Ljava/util/List;)V"; //$NON-NLS-1$
    private static final String COMMENT_RETURN =
        "Lcom/_1c/g5/v8/dt/bsl/documentation/comment/BslDocumentationComment;"; //$NON-NLS-1$
    private static final String TEXT_PART =
        "com._1c.g5.v8.dt.bsl.documentation.comment.TextPart"; //$NON-NLS-1$
    private static final String LINK_PART =
        "com._1c.g5.v8.dt.bsl.documentation.comment.LinkPart"; //$NON-NLS-1$
    private static final String BSL_COMMENT_BUNDLE = "com._1c.g5.v8.dt.bsl.comment"; //$NON-NLS-1$
    private static final String PROVIDER_CLASS =
        "com._1c.g5.v8.dt.bsl.documentation.comment.BslMultiLineCommentDocumentationProvider"; //$NON-NLS-1$
    private static final String AXIOM_JAVAC =
        "C:\\Program Files\\1C\\1CE\\components\\axiom-jdk-full-17.0.16+12-x86_64\\bin\\javac.exe"; //$NON-NLS-1$

    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final AtomicBoolean weavingHookInstalled = new AtomicBoolean();
    private static volatile Instrumentation instrumentation;
    /**
     * Самоприсоединение агента в EDT запрещено (нет {@code -Djdk.attach.allowAttachSelf=true}),
     * отказ детерминированный и в пределах сессии не изменится. Каждая попытка стоит около
     * 0,35 с (компиляция агента штатным {@code javac} в отдельном процессе плюс attach), а
     * потребителей {@link #registerExtraTransformer} больше десятка — на старте они давали
     * несколько секунд ожидания впустую. Помним первый отказ и дальше сразу отдаём
     * {@code null}: подмена работает через {@code WeavingHook}, агент ей не нужен.
     */
    private static volatile boolean instrumentationUnavailable;
    private static volatile boolean transformerRegistered;

    private BslDocCommentDescriptionFix() {}

    /**
     * Обработчик разбора и {@link WeavingHook} — отдельно от остального и как можно раньше.
     * <p>
     * Хук видит только классы, загруженные после его регистрации, а бандл активируется
     * лениво: {@code BslDocumentationComment} успевал загрузиться до {@code Activator.start},
     * и тогда подмена не применялась вовсе. Зовётся из {@link ComfortEarlyStart} (DS,
     * {@code immediate="true"}) и из {@link #install()}.
     */
    public static void installWeavingHook()
    {
        if (!weavingHookInstalled.compareAndSet(false, true))
        {
            return;
        }

        System.getProperties().put(PROP_AFTER_PARSE,
            (Consumer<Object>) BslDocCommentDescriptionFix::afterParse);

        Bundle bundle = FrameworkUtil.getBundle(BslDocCommentDescriptionFix.class);
        BundleContext context = bundle != null ? bundle.getBundleContext() : null;
        if (context != null)
        {
            context.registerService(WeavingHook.class, new ParseWeavingHook(), null);
        }
    }

    public static void install()
    {
        if (!installed.compareAndSet(false, true))
        {
            return;
        }

        installWeavingHook();

        try
        {
            Instrumentation inst = ensureInstrumentation();
            if (inst == null)
            {
                Global.logError("BslDocComment", "install: Instrumentation is null", null); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            registerPermanentTransformer(inst);

            for (Class<?> c : inst.getAllLoadedClasses())
            {
                String name = c.getName();
                if (!TARGET_COMMENT.equals(name) && !TARGET_UTILS.equals(name))
                    continue;
                retransform(inst, c);
            }
        }
        catch (Throwable t)
        {
            Global.logError("BslDocComment", "install Instrumentation", t); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    public static void afterParse(Object commentObj)
    {
        if (commentObj == null)
            return;
        String cn = commentObj.getClass().getName();
        if (!TARGET_COMMENT.equals(cn))
            return;
        try
        {
            normalize(commentObj);
        }
        catch (Throwable t)
        {
        }
        try
        {
            TypeSectionLinkRepair.repair(commentObj);
        }
        catch (Throwable t)
        {
        }
    }

    /** @return число параметров, у которых перенесли описание */
    static int normalize(Object comment)
    {
        if (comment == null)
            return 0;
        Object section = Global.invoke(comment, "getParametersSection"); //$NON-NLS-1$
        if (section == null)
            return 0;
        Object fieldsObj = Global.invoke(section, "getParameterDefinitions"); //$NON-NLS-1$
        if (!(fieldsObj instanceof List<?> fields) || fields.isEmpty())
            return 0;

        int moved = 0;
        for (Object field : fields)
        {
            if (field == null)
                continue;
            Object fieldDesc = Global.invoke(field, "getDescription"); //$NON-NLS-1$
            if (hasText(fieldDesc))
                continue;
            Object typeSectionsObj = Global.invoke(field, "getTypeSections"); //$NON-NLS-1$
            if (!(typeSectionsObj instanceof List<?> typeSections) || typeSections.isEmpty())
                continue;
            if (fieldDesc == null)
                continue;

            boolean fieldMoved = false;
            for (Object typeSection : typeSections)
            {
                if (typeSection == null)
                    continue;
                // В old-format: имя типа → sourceDescription; хвост после 2-го «-» →
                // TypeSection.getDescription() (Section). sourceDescription трогать нельзя —
                // иначе в описание параметра попадают имена типов (дубль в param-hint).
                Object source = Global.invoke(typeSection, "getDescription"); //$NON-NLS-1$
                if (!hasText(source))
                    continue;
                Object partsObj = Global.invoke(source, "getParts"); //$NON-NLS-1$
                if (!(partsObj instanceof List<?> parts) || parts.isEmpty())
                    continue;
                @SuppressWarnings({ "unchecked", "rawtypes" }) //$NON-NLS-1$ //$NON-NLS-2$
                List rawParts = (List) parts;
                Global.invoke(fieldDesc, "addParts", new ArrayList<>(rawParts)); //$NON-NLS-1$
                rawParts.clear();
                fieldMoved = true;
            }
            if (fieldMoved)
                moved++;
        }
        return moved;
    }

    /**
     * Дефект EDT: ссылка «см. …» внутри секции типов превращается в имя несуществующего типа.
     * <p>
     * Секция типов появляется, как только в строке параметра есть второй «-»
     * ({@code Имя - см. Обработка… - описание}). {@code createTypeSection} разбирает текст
     * секции штатным {@code createTextDescriptionParts}, но, если частей получилось не ровно
     * одна, выбрасывает их и кладёт весь текст одним {@code TextPart}. А
     * {@code TypeSection.computeTypeDefinitions} распознаёт ссылку только в случае
     * «единственная часть, и она {@code LinkPart}». Ссылка плюс пробелы или соседний тип —
     * это всегда больше одной части, поэтому тип получает имя
     * «см. Обработка.Обработка1.Форма.Форма1»: такого типа нет, тип параметра не вычисляется,
     * и подсказки при вводе имени типа после «см.» тоже нет — искать нечего, {@code LinkPart}
     * не создан. Без второго «-» секции типов не возникает вовсе, типы берутся из описания
     * параметра, где {@code LinkPart} сохраняется, — поэтому там всё работает.
     * <p>
     * Чиним после разбора: пересобираем части секции тем же самым
     * {@code createTextDescriptionParts} (результат которого EDT выбросил) и подменяем в уже
     * посчитанном списке типов фиктивные «см. …» на {@code LinkContainsTypeDefinition}.
     * Имена остальных типов считает сам EDT — его логика («Массив из …», расширения в
     * квадратных скобках) не дублируется.
     * <p>
     * Подчинено флажку проекта «Расширенный расчет типов»
     * ({@link BslDocCommentComputedTypes#isExtendedTypesEnabled(org.eclipse.emf.ecore.EObject)}):
     * при выключенном флажке не меняется ничего.
     */
    private static final class TypeSectionLinkRepair
    {
        private static final String PKG =
            "com._1c.g5.v8.dt.bsl.documentation.comment."; //$NON-NLS-1$
        private static final String TYPE_SECTION = PKG + "TypeSection"; //$NON-NLS-1$
        private static final String LINK_CONTAINS_TYPE =
            PKG + "TypeSection$LinkContainsTypeDefinition"; //$NON-NLS-1$
        private static final String DESCRIPTION_PART = PKG + "IDescriptionPart"; //$NON-NLS-1$
        private static final String SEE_RU = "см."; //$NON-NLS-1$
        private static final String SEE_EN = "see"; //$NON-NLS-1$

        static void repair(Object comment)
        {
            // Флажок первым: разбор комментария идёт для каждого метода каждого модуля, и при
            // выключенном флажке здесь не должно тратиться вообще ничего.
            if (!isEnabled(comment))
                return;
            for (Object section : collectTypeSections(comment))
            {
                if (isBrokenLinkSection(section))
                    repairSection(comment, section);
            }
            boundLinks(comment);
        }

        /**
         * Ограничение зоны ссылки «см. …» её собственным текстом.
         * <p>
         * Штатный {@code LinkPart.match} берёт правую границу из
         * {@code initialContent.indexOf(')')}: у ссылки со скобками — её закрывающая скобка, а у
         * «см. …» скобки нет, и тогда {@code match} отвечает «да» на <b>любое</b> смещение правее
         * начала ссылки, то есть до конца строки. Из-за этого после запятой за ссылкой
         * ({@code Форма - см. Обработка…, РасширениеФормы}) под кареткой оказывается
         * {@code LinkPart}, а {@code BslProposalProvider.createProposalsForTypeSectionComment}
         * первой же строкой выходит по {@code part instanceof LinkPart} — имён типов не
         * предлагает никто.
         * <p>
         * Подменять сам {@code match} нельзя: {@code WeavingHook} видит только классы,
         * загружаемые после его регистрации, а {@code LinkPart} к этому моменту уже загружен
         * (бандл {@code com._1c.g5.v8.dt.bsl.comment} активен раньше нашего). Поэтому
         * ограничиваем не код, а данные: дописываем {@code ")"} в конец {@code initialContent},
         * и штатная формула сама даёт границу {@code getOffset() + initialContent.length() + 1} —
         * последний символ ссылки плюс один. Имя типа за «, » оказывается уже вне ссылки.
         */
        private static void boundLinks(Object comment)
        {
            List<Object> queue = new ArrayList<>();
            queue.add(Global.invoke(comment, "getDescription")); //$NON-NLS-1$
            Object parameters = Global.invoke(comment, "getParametersSection"); //$NON-NLS-1$
            if (parameters != null)
            {
                queue.add(Global.invoke(parameters, "getSourceDescription")); //$NON-NLS-1$
                queue.add(Global.invoke(parameters, "getDescription")); //$NON-NLS-1$
                if (Global.invoke(parameters, "getParameterDefinitions") instanceof List<?> fields) //$NON-NLS-1$
                {
                    for (Object field : fields)
                    {
                        if (field == null)
                            continue;
                        queue.add(Global.invoke(field, "getDescription")); //$NON-NLS-1$
                        if (Global.invoke(field, "getTypeSections") instanceof List<?> sections) //$NON-NLS-1$
                            for (Object section : sections)
                                addTypeSection(section, queue);
                    }
                }
            }
            Object returnSection = Global.invoke(comment, "getReturnSection"); //$NON-NLS-1$
            if (returnSection != null)
            {
                queue.add(Global.invoke(returnSection, "getDescription")); //$NON-NLS-1$
                if (Global.invoke(returnSection, "getReturnTypes") instanceof List<?> sections) //$NON-NLS-1$
                    for (Object section : sections)
                        addTypeSection(section, queue);
            }
            // Обход списком, а не рекурсией: секция типов сама лежит частью описания и может
            // содержать вложенные секции, глубину заранее не знаем.
            for (int i = 0; i < queue.size() && i < 512; i++)
            {
                Object description = queue.get(i);
                if (description == null
                    || !(Global.invoke(description, "getParts") instanceof List<?> parts)) //$NON-NLS-1$
                    continue;
                for (Object part : parts)
                {
                    if (part == null)
                        continue;
                    String className = part.getClass().getName();
                    if (LINK_PART.equals(className))
                        boundLink(part);
                    else if (TYPE_SECTION.equals(className))
                        addTypeSection(part, queue);
                }
            }
        }

        private static void addTypeSection(Object section, List<Object> queue)
        {
            if (section == null)
                return;
            queue.add(Global.invoke(section, "getSourceDescription")); //$NON-NLS-1$
            queue.add(Global.invoke(section, "getSourceExtensionDescription")); //$NON-NLS-1$
            queue.add(Global.invoke(section, "getDescription")); //$NON-NLS-1$
        }

        private static void boundLink(Object link)
        {
            try
            {
                String initial = asString(Global.invoke(link, "getInitialContent")); //$NON-NLS-1$
                if (initial == null || initial.isEmpty() || initial.indexOf(')') >= 0)
                    return;
                java.lang.reflect.Field field =
                    link.getClass().getDeclaredField("initialContent"); //$NON-NLS-1$
                field.setAccessible(true);
                field.set(link, initial + ")"); //$NON-NLS-1$
            }
            catch (Throwable t)
            {
                Global.logError("BslDocComment", "boundLink", t); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        /**
         * Флажок проекта. Комментарий, разобранный через
         * {@code BslCommentUtils.parseTemplateComment}, собран конструктором без аргументов:
         * ни модуля, ни метода в нём нет, и проект взять неоткуда. Так комментарий разбирает
         * подсказка ввода — там флажок не проверяем и чиним всегда, иначе ссылка «см. …»
         * осталась бы нераспознанной при любом состоянии флажка.
         */
        private static boolean isEnabled(Object comment)
        {
            Object owner = Global.invoke(comment, "getMethod"); //$NON-NLS-1$
            if (!(owner instanceof EObject))
                owner = Global.invoke(comment, "getModule"); //$NON-NLS-1$
            if (!(owner instanceof EObject eObject))
                return true;
            return BslDocCommentComputedTypes.isExtendedTypesEnabled(eObject);
        }

        private static List<Object> collectTypeSections(Object comment)
        {
            List<Object> result = new ArrayList<>();
            Object parameters = Global.invoke(comment, "getParametersSection"); //$NON-NLS-1$
            if (parameters != null
                && Global.invoke(parameters, "getParameterDefinitions") instanceof List<?> fields) //$NON-NLS-1$
            {
                for (Object field : fields)
                {
                    if (field != null
                        && Global.invoke(field, "getTypeSections") instanceof List<?> sections) //$NON-NLS-1$
                        result.addAll(sections);
                }
            }
            Object returnSection = Global.invoke(comment, "getReturnSection"); //$NON-NLS-1$
            if (returnSection != null
                && Global.invoke(returnSection, "getReturnTypes") instanceof List<?> sections) //$NON-NLS-1$
                result.addAll(sections);
            return result;
        }

        /** Секция из единственного {@code TextPart}, в тексте которого есть «см.» или «see». */
        private static boolean isBrokenLinkSection(Object section)
        {
            Object source = section == null
                ? null : Global.invoke(section, "getSourceDescription"); //$NON-NLS-1$
            if (source == null
                || !(Global.invoke(source, "getParts") instanceof List<?> parts) //$NON-NLS-1$
                || parts.size() != 1)
                return false;
            Object only = parts.get(0);
            if (only == null || !TEXT_PART.equals(only.getClass().getName()))
                return false;
            return hasSeeToken(asString(Global.invoke(only, "getText"))); //$NON-NLS-1$
        }

        private static boolean hasSeeToken(String text)
        {
            if (text == null)
                return false;
            String lower = text.toLowerCase();
            return lower.contains(SEE_RU) || lower.contains(SEE_EN + " "); //$NON-NLS-1$
        }

        private static boolean startsWithSee(String name)
        {
            if (name == null)
                return false;
            String trimmed = name.trim();
            return trimmed.regionMatches(true, 0, SEE_RU, 0, SEE_RU.length())
                || trimmed.regionMatches(true, 0, SEE_EN, 0, SEE_EN.length());
        }

        private static void repairSection(Object comment, Object section)
        {
            try
            {
                Object source = Global.invoke(section, "getSourceDescription"); //$NON-NLS-1$
                if (!(Global.invoke(source, "getParts") instanceof List<?> parts) //$NON-NLS-1$
                    || parts.size() != 1)
                    return;
                Object only = parts.get(0);
                String text = asString(Global.invoke(only, "getText")); //$NON-NLS-1$
                if (text == null)
                    return;

                // Штатный расчёт по сырому тексту — до подмены частей, чтобы имена типов
                // посчитал сам EDT со всеми своими правилами.
                if (!(Global.invoke(section, "getTypeDefinitions") instanceof List<?> stockTypes)) //$NON-NLS-1$
                    return;

                ClassLoader loader = comment.getClass().getClassLoader();
                java.lang.reflect.Method builder = comment.getClass().getDeclaredMethod(
                    "createTextDescriptionParts", //$NON-NLS-1$
                    Class.forName(DESCRIPTION_PART, false, loader), int.class, int.class,
                    String.class);
                builder.setAccessible(true);
                Object built = builder.invoke(comment, source,
                    Integer.valueOf(intValue(Global.invoke(only, "getLineNumber"))), //$NON-NLS-1$
                    Integer.valueOf(intValue(Global.invoke(only, "getOffset"))), text); //$NON-NLS-1$
                if (!(built instanceof Collection<?> newParts))
                    return;

                List<Object> links = new ArrayList<>();
                for (Object part : newParts)
                {
                    if (part != null && LINK_PART.equals(part.getClass().getName()))
                        links.add(part);
                }
                if (links.isEmpty())
                    return;

                Class<?> linkType = Class.forName(LINK_CONTAINS_TYPE, false, loader);
                java.lang.reflect.Constructor<?> ctor = linkType.getConstructor(
                    Class.forName(DESCRIPTION_PART, false, loader),
                    Class.forName(LINK_PART, false, loader));
                List<Object> types = new ArrayList<>();
                int next = 0;
                for (Object type : stockTypes)
                {
                    String name = asString(Global.invoke(type, "getTypeName")); //$NON-NLS-1$
                    if (startsWithSee(name) && next < links.size())
                        types.add(ctor.newInstance(section, links.get(next++)));
                    else
                        types.add(type);
                }

                @SuppressWarnings({ "unchecked", "rawtypes" }) //$NON-NLS-1$ //$NON-NLS-2$
                List rawParts = (List)parts;
                rawParts.clear();
                Global.invoke(source, "addParts", new ArrayList<>(newParts)); //$NON-NLS-1$

                java.lang.reflect.Field cache =
                    Class.forName(TYPE_SECTION, false, loader).getDeclaredField("types"); //$NON-NLS-1$
                cache.setAccessible(true);
                cache.set(section, types);
            }
            catch (Throwable t)
            {
                Global.logError("BslDocComment", "repairSection", t); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /**
     * Запасной путь для UI: re-parse комментария метода и текст описания параметра.
     * Пишет в {@code ValueContent.description}, если он пуст.
     */
    public static String recoverParamDescription(Object method, String paramName, Object valueContent)
    {
        String text = recoverParamDescription(method, paramName);
        if (text != null && valueContent != null)
            applyDescriptionToValueContent(valueContent, text);
        return text;
    }

    public static String recoverParamDescription(Object method, String paramName)
    {
        if (method == null || paramName == null || paramName.isEmpty())
            return null;
        if (!(method instanceof EObject eObject))
        {
            return null;
        }
        try
        {
            Bundle commentBundle = Platform.getBundle(BSL_COMMENT_BUNDLE);
            if (commentBundle == null)
            {
                return null;
            }
            Class<?> utilsClass = commentBundle.loadClass(TARGET_UTILS);
            Class<?> providerClass = commentBundle.loadClass(PROVIDER_CLASS);
            Bundle bslModel = Platform.getBundle("com._1c.g5.v8.dt.bsl.model"); //$NON-NLS-1$
            if (bslModel == null)
            {
                return null;
            }
            Class<?> bslMethodClass = bslModel.loadClass("com._1c.g5.v8.dt.bsl.model.Method"); //$NON-NLS-1$
            if (!bslMethodClass.isInstance(method))
            {
                return null;
            }

            Resource resource = eObject.eResource();
            if (resource == null || resource.getURI() == null)
            {
                return null;
            }
            IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
                .getResourceServiceProvider(resource.getURI());
            if (rsp == null)
            {
                return null;
            }
            Object provider = rsp.get(providerClass);
            if (provider == null)
            {
                return null;
            }
            Object linesObj = Global.invoke(provider, "getCommentLines", eObject); //$NON-NLS-1$
            if (!(linesObj instanceof List<?> lines) || lines.isEmpty())
            {
                return null;
            }

            java.lang.reflect.Method parseMethod = utilsClass.getMethod(
                "parseTemplateComment", List.class, bslMethodClass, boolean.class); //$NON-NLS-1$
            for (boolean oldFormat : new boolean[] { true, false })
            {
                Object comment = parseMethod.invoke(null, lines, method, Boolean.valueOf(oldFormat));
                if (comment == null)
                    continue;
                normalize(comment);
                String text = readFieldDescriptionText(comment, paramName);
                if (text != null && !text.isBlank())
                    return text.trim();
            }
        }
        catch (Throwable t)
        {
        }
        return null;
    }

    public static void applyDescriptionToValueContent(Object valueContent, String description)
    {
        if (valueContent == null || description == null || description.isBlank())
            return;
        String current = asString(Global.invoke(valueContent, "getDescription")); //$NON-NLS-1$
        if (current != null && !current.isBlank())
            return;
        Global.setFieldForce(valueContent, "description", description); //$NON-NLS-1$
    }

    public static void onInstrumentation(Instrumentation inst)
    {
        instrumentation = inst;
    }

    /**
     * Доп. transformer (param-hint doc pages и т.п.).
     * При отсутствии Instrumentation поднимает agent (как {@link #install()}).
     *
     * @return {@code true}, если transformer зарегистрирован
     */
    public static boolean registerExtraTransformer(ClassFileTransformer transformer,
        String... retransformClassNames)
    {
        if (transformer == null)
            return false;
        if (instrumentation == null && instrumentationUnavailable)
            return false;
        if (instrumentation == null)
        {
            try
            {
                ensureInstrumentation();
            }
            catch (Throwable t)
            {
                Global.logError("BslDocComment", "ensureInstrumentation", t); //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
        }
        if (instrumentation == null)
        {
            Global.logError("BslDocComment", "registerExtraTransformer: Instrumentation is null", //$NON-NLS-1$ //$NON-NLS-2$
                null);
            return false;
        }
        try
        {
            instrumentation.addTransformer(transformer, true);
            if (retransformClassNames != null)
            {
                for (Class<?> c : instrumentation.getAllLoadedClasses())
                {
                    String name = c.getName();
                    for (String want : retransformClassNames)
                    {
                        if (want.equals(name))
                        {
                            retransform(instrumentation, c);
                            break;
                        }
                    }
                }
            }
            return true;
        }
        catch (Throwable t)
        {
            Global.logError("BslDocComment", "addTransformer", t); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    private static String readFieldDescriptionText(Object comment, String paramName)
    {
        Object section = Global.invoke(comment, "getParametersSection"); //$NON-NLS-1$
        if (section == null)
            return null;
        Object field = Global.invoke(section, "getParameterByName", paramName); //$NON-NLS-1$
        if (field == null)
            return null;
        return descriptionToString(Global.invoke(field, "getDescription")); //$NON-NLS-1$
    }

    private static String descriptionToString(Object description)
    {
        if (description == null)
            return null;
        Object partsObj = Global.invoke(description, "getParts"); //$NON-NLS-1$
        if (!(partsObj instanceof List<?> parts) || parts.isEmpty())
            return null;
        StringBuilder sb = new StringBuilder();
        Integer prevLine = null;
        for (Object part : parts)
        {
            if (part == null)
                continue;
            Object lineObj = Global.invoke(part, "getLineNumber"); //$NON-NLS-1$
            if (lineObj instanceof Integer line)
            {
                if (prevLine != null && !prevLine.equals(line))
                    sb.append('\n');
                prevLine = line;
            }
            String cn = part.getClass().getName();
            if (TEXT_PART.equals(cn))
            {
                String text = asString(Global.invoke(part, "getText")); //$NON-NLS-1$
                if (text != null)
                    sb.append(text);
            }
            else if (LINK_PART.equals(cn))
            {
                String text = asString(Global.invoke(part, "getInitialContent")); //$NON-NLS-1$
                if (text != null)
                    sb.append(text);
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private static boolean hasText(Object description)
    {
        return descriptionToString(description) != null;
    }

    private static String asString(Object value)
    {
        return value instanceof String s ? s : null;
    }

    private static void registerPermanentTransformer(Instrumentation inst)
    {
        if (transformerRegistered)
            return;
        inst.addTransformer(new ParseClassFileTransformer(), true);
        transformerRegistered = true;
    }

    private static boolean retransform(Instrumentation inst, Class<?> target)
    {
        try
        {
            if (!inst.isModifiableClass(target))
            {
                return false;
            }
            inst.retransformClasses(target);
            return true;
        }
        catch (UnmodifiableClassException e)
        {
            return false;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static Instrumentation ensureInstrumentation() throws Exception
    {
        if (instrumentation != null)
            return instrumentation;
        if (instrumentationUnavailable)
            return null;

        try
        {
            Path agentJar = writeAgentJar();
            String pid = Long.toString(ProcessHandle.current().pid());
            Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine"); //$NON-NLS-1$
            Object vm = vmClass.getMethod("attach", String.class).invoke(null, pid); //$NON-NLS-1$
            try
            {
                vmClass.getMethod("loadAgent", String.class).invoke(vm, //$NON-NLS-1$
                    agentJar.toAbsolutePath().toString());
            }
            finally
            {
                vmClass.getMethod("detach").invoke(vm); //$NON-NLS-1$
            }

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (instrumentation == null && System.nanoTime() < deadline)
                Thread.sleep(20);
        }
        catch (Throwable t)
        {
            instrumentationUnavailable = true;
            if (t instanceof Exception e)
                throw e;
            throw new IllegalStateException(t);
        }
        if (instrumentation == null)
            instrumentationUnavailable = true;
        return instrumentation;
    }

    private static Path writeAgentJar() throws IOException, InterruptedException
    {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "edt-comfort-agents"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.createDirectories(dir);
        Path jar = dir.resolve("bsl-doc-comment-fix-agent.jar"); //$NON-NLS-1$
        Path classes = dir.resolve("agent-classes"); //$NON-NLS-1$
        Files.createDirectories(classes);

        String agentSrc = ""
            + "package tormozit;\n"
            + "import java.lang.instrument.Instrumentation;\n"
            + "public class BslDocCommentFixAgent {\n"
            + "  public static void agentmain(String args, Instrumentation inst) throws Exception {\n"
            + "    for (Class<?> c : inst.getAllLoadedClasses()) {\n"
            + "      if (\"tormozit.BslDocCommentDescriptionFix\".equals(c.getName())) {\n"
            + "        c.getMethod(\"onInstrumentation\", Instrumentation.class).invoke(null, inst);\n"
            + "        return;\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "}\n";
        Path srcFile = dir.resolve("BslDocCommentFixAgent.java"); //$NON-NLS-1$
        Files.writeString(srcFile, agentSrc, StandardCharsets.UTF_8);

        Path javac = resolveJavac();
        Process compile = new ProcessBuilder(
            javac.toString(),
            "-d", classes.toString(), //$NON-NLS-1$
            srcFile.toString())
            .redirectErrorStream(true)
            .start();
        String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (compile.waitFor() != 0)
            throw new IOException("javac agent failed: " + compileOut); //$NON-NLS-1$

        Manifest mf = new Manifest();
        Attributes attrs = mf.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0"); //$NON-NLS-1$
        attrs.putValue("Agent-Class", "tormozit.BslDocCommentFixAgent"); //$NON-NLS-1$ //$NON-NLS-2$
        attrs.putValue("Can-Redefine-Classes", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        attrs.putValue("Can-Retransform-Classes", "true"); //$NON-NLS-1$ //$NON-NLS-2$

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar), mf))
        {
            Path classFile = classes.resolve("tormozit").resolve("BslDocCommentFixAgent.class"); //$NON-NLS-1$ //$NON-NLS-2$
            jos.putNextEntry(new JarEntry("tormozit/BslDocCommentFixAgent.class")); //$NON-NLS-1$
            jos.write(Files.readAllBytes(classFile));
            jos.closeEntry();
        }
        return jar;
    }

    private static Path resolveJavac() throws IOException
    {
        String javaHome = System.getProperty("java.home"); //$NON-NLS-1$
        if (javaHome != null)
        {
            Path home = Path.of(javaHome);
            Path javac = home.resolve("bin").resolve("javac.exe"); //$NON-NLS-1$ //$NON-NLS-2$
            if (Files.isRegularFile(javac))
                return javac;
            javac = home.resolve("bin").resolve("javac"); //$NON-NLS-1$ //$NON-NLS-2$
            if (Files.isRegularFile(javac))
                return javac;
            Path parent = home.getParent();
            if (parent != null)
            {
                javac = parent.resolve("bin").resolve("javac.exe"); //$NON-NLS-1$ //$NON-NLS-2$
                if (Files.isRegularFile(javac))
                    return javac;
            }
        }
        Path axiom = Path.of(AXIOM_JAVAC);
        if (Files.isRegularFile(axiom))
            return axiom;
        throw new IOException("javac not found"); //$NON-NLS-1$
    }

    static byte[] transformClass(String internalName, byte[] classfileBuffer)
    {
        if (TARGET_COMMENT_INTERNAL.equals(internalName))
            return transformCommentClass(classfileBuffer);
        if (TARGET_UTILS_INTERNAL.equals(internalName))
            return transformUtilsClass(classfileBuffer);
        return null;
    }

    private static int intValue(Object value)
    {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static byte[] transformCommentClass(byte[] classfileBuffer)
    {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
        {
            @Override
            protected String getCommonSuperClass(String type1, String type2)
            {
                return "java/lang/Object"; //$NON-NLS-1$
            }
        };
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
                if (("parse".equals(name) || "parseOldFormat".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
                    && PARSE_VOID_DESC.equals(descriptor))
                {
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.RETURN)
                            {
                                emitHookFromThis(this);
                                touched.set(true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);
        return touched.get() ? writer.toByteArray() : null;
    }

    private static byte[] transformUtilsClass(byte[] classfileBuffer)
    {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
        {
            @Override
            protected String getCommonSuperClass(String type1, String type2)
            {
                return "java/lang/Object"; //$NON-NLS-1$
            }
        };
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
                if (!"parseTemplateComment".equals(name) && !"parse".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
                    return mv;
                Type returnType = Type.getReturnType(descriptor);
                if (!COMMENT_RETURN.equals(returnType.getDescriptor()))
                    return mv;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitInsn(int opcode)
                    {
                        if (opcode == Opcodes.ARETURN)
                        {
                            emitHookFromStack(this);
                            touched.set(true);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return touched.get() ? writer.toByteArray() : null;
    }

    private static void emitHookFromThis(MethodVisitor mv)
    {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        emitConsumerAccept(mv);
    }

    private static void emitHookFromStack(MethodVisitor mv)
    {
        mv.visitInsn(Opcodes.DUP);
        emitConsumerAccept(mv);
    }

    /** Стек: …, comment → после вызова comment остаётся (accept съедает копию). */
    private static void emitConsumerAccept(MethodVisitor mv)
    {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
            "()Ljava/util/Properties;", false); //$NON-NLS-1$
        mv.visitLdcInsn(PROP_AFTER_PARSE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
        mv.visitInsn(Opcodes.DUP);
        Label skip = new Label();
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Consumer"); //$NON-NLS-1$
        mv.visitJumpInsn(Opcodes.IFEQ, skip);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer"); //$NON-NLS-1$
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)V", true); //$NON-NLS-1$
        Label end = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, end);
        mv.visitLabel(skip);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(end);
    }

    private static final class ParseWeavingHook implements WeavingHook
    {
        @Override
        public void weave(WovenClass wovenClass)
        {
            String name = wovenClass.getClassName();
            if (!TARGET_COMMENT.equals(name) && !TARGET_UTILS.equals(name))
                return;
            if (wovenClass.getState() != WovenClass.TRANSFORMING)
                return;
            try
            {
                String internal = name.replace('.', '/');
                byte[] transformed = transformClass(internal, wovenClass.getBytes());
                if (transformed != null)
                {
                    wovenClass.setBytes(transformed);
                }
            }
            catch (Throwable t)
            {
            }
        }
    }

    private static final class ParseClassFileTransformer implements ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer)
        {
            if (!TARGET_COMMENT_INTERNAL.equals(className)
                && !TARGET_UTILS_INTERNAL.equals(className))
                return null;
            try
            {
                return transformClass(className, classfileBuffer);
            }
            catch (Throwable t)
            {
                return null;
            }
        }
    }
}
