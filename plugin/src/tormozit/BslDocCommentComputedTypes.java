// BslDocCommentComputedTypes.java
package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.Variable;
import com._1c.g5.v8.dt.bsl.model.typesytem.VariableTypeStateProviderCollector;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.TypeItem;

/**
 * Влитие рассчитанного по коду типа в документирующий комментарий (issue 509).
 * <p>
 * <b>Почему подменяется {@code BslCommentUtils}, а не {@code BslDocumentationComment}.</b>
 * Замеры 13.09.2026: {@code BslDocumentationComment} загружается в первые секунды работы
 * каркаса, до активации нашего бандла, и {@link WeavingHook} его не застаёт — из 12637
 * классов, прошедших через хук, этого класса не было ни разу. Присоединение агента в EDT
 * запрещено ({@code Can not attach to current VM}), поэтому {@code retransform} недоступен.
 * А {@code BslCommentUtils} грузится поздно и подменяется надёжно — на нём и держатся давние
 * доработки комментариев ({@code BslDocCommentDescriptionFix}, {@code BslDocCommentTypeMerge}):
 * они обогащают <b>разобранный комментарий</b>, а не пост-обрабатывают вычисленные типы.
 * <p>
 * <b>Как это решает задачу.</b> Когда EDT разрешает ссылку {@code см. Конструктор}, он парсит
 * документирующий комментарий самого {@code Конструктор} через
 * {@code BslCommentUtils.parseTemplateComment(Method, …)}. Если у целевого метода комментария
 * нет, секция возвращаемого значения пуста — отсюда и пустой список автодополнения после
 * {@code Предок.}, хотя после {@code Конструктор().} он открывается. Здесь в разобранный
 * комментарий дописывается секция возвращаемого значения по типу, рассчитанному системой
 * типов, а свойства структуры кладутся полями ({@code FieldDefinition}) — в тех же терминах,
 * в которых их описал бы человек. Дальше штатный расчёт работает сам, без единой правки
 * в недоступном для подмены классе.
 * <p>
 * <b>Главный инвариант: при выключенном флажке проекта не меняется ничего.</b>
 * {@link #afterParseTemplateComment} первым делом проверяет флажок и при выключенном
 * возвращает разобранный комментарий как есть.
 */
public final class BslDocCommentComputedTypes
{
    /** Флажок проекта: «Расширенный расчет типов». */
    public static final String PREF_EXTENDED_TYPE_COMPUTATION =
        "comfort.bsl.extendedTypeComputation"; //$NON-NLS-1$

    /** Узел проектных параметров EDT со штатным флажком замещения типов. */
    private static final String EDT_BSL_NODE = "com._1c.g5.v8.dt.bsl"; //$NON-NLS-1$
    /** Штатный флажок «Перезаписывать типы документирующим комментарием». */
    private static final String EDT_REPLACE_KEY = "replaceTypesByDocumentationComment"; //$NON-NLS-1$

    private static final String TAG = "BslDocComputedTypes"; //$NON-NLS-1$

    private static final String TARGET_UTILS =
        "com._1c.g5.v8.dt.bsl.documentation.comment.BslCommentUtils"; //$NON-NLS-1$
    private static final String COMMENT_CLASS =
        "com._1c.g5.v8.dt.bsl.documentation.comment.BslDocumentationComment"; //$NON-NLS-1$
    private static final String COMMENT_INTERNAL =
        "com/_1c/g5/v8/dt/bsl/documentation/comment/BslDocumentationComment"; //$NON-NLS-1$
    private static final String PARSE_METHOD = "parseTemplateComment"; //$NON-NLS-1$
    private static final String BSL_METHOD_DESC = "com._1c.g5.v8.dt.bsl.model.Method"; //$NON-NLS-1$
    private static final String BSL_FUNCTION = "com._1c.g5.v8.dt.bsl.model.Function"; //$NON-NLS-1$
    private static final String TYPES_COMPUTER =
        "com._1c.g5.v8.dt.bsl.resource.TypesComputer"; //$NON-NLS-1$
    private static final String TYPE_SYSTEM_PROVIDER =
        "com._1c.g5.v8.dt.bsl.typesystem.BslTypeSystemProvider"; //$NON-NLS-1$
    /** Знак «не объединять рассчитанный тип с документирующим». */
    private static final String MARK_BAN_TEXT = "%"; //$NON-NLS-1$
    /** Знак «объединить рассчитанный тип с документирующим». */
    private static final String MARK_FORCE_TEXT = "^"; //$NON-NLS-1$

    private static final int MARK_NONE = 0;
    private static final int MARK_BAN = 1;
    private static final int MARK_FORCE = 2;

    /** Метод EDT, разрешающий ссылку «см. Метод» документирующего комментария. */
    private static final String LINK_RESOLVE_METHOD = "computeTypesByLinkPart"; //$NON-NLS-1$
    private static final String SELF_INTERNAL = "tormozit/BslDocCommentComputedTypes"; //$NON-NLS-1$
    private static final String AFTER_PARSE_DESC =
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"; //$NON-NLS-1$

    /** Класс EDT, решающий, замещать ли рассчитанный тип типом бокового комментария. */
    private static final String TARGET_CREATOR =
        "com._1c.g5.v8.dt.bsl.typesystem.util.CreatorTreeState"; //$NON-NLS-1$
    private static final String REPLACE_METHOD = "isReplaceTypesByDocumentationComment"; //$NON-NLS-1$
    private static final String AFTER_REPLACE_DESC = "(ZLjava/lang/Object;)Z"; //$NON-NLS-1$
    /** Разбор бокового комментария: {@code parseTemplateComment(List, boolean)}. */
    private static final String SIDE_PARSE_DESC =
        "(Ljava/util/List;Z)L" + COMMENT_INTERNAL + ";"; //$NON-NLS-1$ //$NON-NLS-2$
    private static final String AFTER_SIDE_DESC =
        "(Ljava/lang/Object;)Ljava/lang/Object;"; //$NON-NLS-1$
    /** {@code parseTemplateComment(BslContextDefMethod, boolean)} — путь {@code см.} через экспорт. */
    private static final String CTX_METHOD_DESC =
        "com._1c.g5.v8.dt.bsl.model.BslContextDefMethod"; //$NON-NLS-1$
    private static final String CTX_PARSE_DESC =
        "(L" + CTX_METHOD_DESC.replace('.', '/') + ";Z)L" + COMMENT_INTERNAL + ";"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    private static final String AFTER_CTX_DESC =
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"; //$NON-NLS-1$

    private static final AtomicBoolean installed = new AtomicBoolean();

    /**
     * Методы, для которых расчёт типов уже идёт в этом потоке. Досчёт целевой функции
     * запускается изнутри разбора, поэтому без такого списка взаимные ссылки
     * ({@code Кодол} → {@code Конструктор} → {@code Кодол}) дают бесконечную рекурсию.
     * Повторное обращение к методу из списка отсекается грубо — просто выходим.
     */
    private static final ThreadLocal<java.util.Set<Object>> computing =
        ThreadLocal.withInitial(java.util.LinkedHashSet::new);

    /**
     * Идёт ли в этом потоке досчёт типов. Досчёт разбирает комментарии всех методов модуля,
     * и каждый такой разбор попадает сюда снова — уже для другого метода, поэтому защита по
     * объекту метода его не останавливает. Без этого флага один досчёт порождает столько же
     * досчётов, сколько в модуле методов, и сборка проекта упирается в процессор.
     */
    private static final ThreadLocal<Boolean> lightInstalling = new ThreadLocal<>();

    /** Досчёт целевого метода {@code см. …} вне стека {@code computeTypesByLinkPart}. */
    private static final ThreadLocal<Boolean> forceLightInstall = new ThreadLocal<>();

    /**
     * Флажок проекта по имени проекта. Чтение проектных параметров идёт через службу
     * параметров Eclipse с блокировками, а спрашивать флажок приходится на каждый разбор
     * комментария каждого метода — это заметная доля нагрузки при сборке. Пишет сюда только
     * {@link #setExtendedTypesEnabled}, других источников изменения нет.
     */
    private static final java.util.Map<String, Boolean> extendedTypesCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Знак управления слиянием из последнего разобранного бокового комментария этого потока.
     * <p>
     * Боковой комментарий EDT разбирает тем же {@code parseTemplateComment} (перегрузка по
     * списку строк) и сразу следом спрашивает {@code isReplaceTypesByDocumentationComment} —
     * оба вызова идут подряд в одном потоке, поэтому знак доносится сюда. Устаревшее значение
     * ни на что не влияет: без бокового комментария список типов пуст и EDT отвечает «не
     * замещать» ещё до нашей вставки.
     */
    private static final ThreadLocal<Integer> sideCommentMark = new ThreadLocal<>();

    /**
     * Результат расчёта на метод. Ключ — {@code имя@URI}, не сам {@code EObject}:
     * EDT при повторном разборе модуля подставляет новые объекты метода, и кэш по
     * ссылке промахивался (лог: {@code afterLight Конструктор size=1}, затем
     * {@code stillEmpty} на другом инстансе → {@code commentTypes=0} у {@code КэшАФВ}).
     * Пустой результат не кэшируем.
     */
    private static final java.util.Map<String, List<?>> typeCacheByKey =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Живые типы возврата метода из кэша (после {@code afterLight}/{@code afterParse}).
     * Нужны для {@code Перем … Экспорт; // см. …}: штатный {@code computeCommentTypes}
     * гоняет типы через документирующий комментарий и теряет свойства структуры.
     */
    public static List<TypeItem> peekCachedReturnTypes(String methodName)
    {
        if (methodName == null || methodName.isEmpty())
            return List.of();
        String needle = methodName + "@"; //$NON-NLS-1$
        for (java.util.Map.Entry<String, List<?>> entry : typeCacheByKey.entrySet())
        {
            String key = entry.getKey();
            if (key == null || entry.getValue() == null || entry.getValue().isEmpty())
                continue;
            if (!key.regionMatches(true, 0, needle, 0, needle.length()))
                continue;
            List<TypeItem> types = new ArrayList<>();
            for (Object item : entry.getValue())
            {
                if (item instanceof TypeItem typeItem)
                    types.add(typeItem);
            }
            if (!types.isEmpty())
                return types;
        }
        return List.of();
    }

    /** Сколько свойств в {@code ContextDef} у типов (для сравнения «живых» и из комментария). */
    public static int contextPropertyCount(Collection<?> types)
    {
        if (types == null || types.isEmpty())
            return 0;
        int count = 0;
        for (Object item : types)
        {
            if (!(item instanceof com._1c.g5.v8.dt.mcore.Type type) || type.eIsProxy())
                continue;
            ContextDef contextDef = type.getContextDef();
            if (contextDef == null || contextDef.eIsProxy())
                continue;
            if (contextDef.getProperties() != null)
                count += contextDef.getProperties().size();
        }
        return count;
    }

    /** Защита от рекурсии полного {@code installTypeSystem} целевого модуля из {@code см.}. */
    private static final java.util.Set<EObject> INSTALLING_TARGET_MODULE = java.util.Collections
        .synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

    private BslDocCommentComputedTypes() {}

    /** Регистрация {@link WeavingHook}; зовётся первой строкой {@code Activator.start}. */
    public static void installWeavingHook()
    {
        if (!installed.compareAndSet(false, true))
            return;
        Bundle bundle = FrameworkUtil.getBundle(BslDocCommentComputedTypes.class);
        BundleContext context = bundle != null ? bundle.getBundleContext() : null;
        if (context != null)
            context.registerService(WeavingHook.class, new ParseWeavingHook(), null);
    }

    // === Настройка проекта ===

    /** Флажок «Расширенный расчет типов» для проекта. */
    public static boolean isExtendedTypesEnabled(IProject project)
    {
        if (project == null || !project.isAccessible())
            return false;
        Boolean cached = extendedTypesCache.get(project.getName());
        if (cached != null)
            return cached.booleanValue();
        boolean value = readMergeEnabled(project);
        extendedTypesCache.put(project.getName(), Boolean.valueOf(value));
        return value;
    }

    /**
     * Тот же флажок, но по объекту модели (метод, модуль) — проект берётся из URI ресурса.
     * Нужен потребителям, у которых на руках только разобранный комментарий.
     */
    public static boolean isExtendedTypesEnabled(EObject object)
    {
        return object != null && isExtendedTypesEnabled(resolveProject(object));
    }

    private static boolean readMergeEnabled(IProject project)
    {
        try
        {
            return new ProjectScope(project).getNode(Activator.PLUGIN_ID)
                .getBoolean(PREF_EXTENDED_TYPE_COMPUTATION, false);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * Записывает флажок проекта и форсирует штатный флажок EDT «Перезаписывать типы
     * документирующим комментарием» во включённое состояние: он читается только для бокового
     * комментария у присваивания, и включённый даёт там «заменять», как требует постановка.
     * При выключении нашего флажка штатный не трогаем — его значение принадлежит пользователю.
     */
    public static boolean setExtendedTypesEnabled(IProject project, boolean enabled)
    {
        if (project == null || !project.isAccessible())
            return false;
        try
        {
            IEclipsePreferences own = new ProjectScope(project).getNode(Activator.PLUGIN_ID);
            own.putBoolean(PREF_EXTENDED_TYPE_COMPUTATION, enabled);
            own.flush();
            extendedTypesCache.put(project.getName(), Boolean.valueOf(enabled));
            if (enabled)
            {
                IEclipsePreferences edt = new ProjectScope(project).getNode(EDT_BSL_NODE);
                edt.putBoolean(EDT_REPLACE_KEY, true);
                edt.flush();
            }
            return true;
        }
        catch (Throwable t)
        {
            Global.logError(TAG, "setExtendedTypesEnabled", t); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Предложение пересобрать проект после смены флажка: типы методов лежат в индексе проекта,
     * и уже посчитанные модули новый режим сами не подхватят.
     */
    public static void promptRebuild(Shell shell, IProject project)
    {
        if (project == null || !project.isAccessible())
            return;
        boolean yes = MessageDialog.openQuestion(shell,
            Global.withPluginWindowTitle("Пересчёт типов"), //$NON-NLS-1$
            "Режим объединения типов изменён. Уже рассчитанные модули подхватят его только "
            + "после пересборки проекта.\n\nПересобрать проект «" + project.getName() + "» сейчас?"); //$NON-NLS-1$
        if (!yes)
            return;
        Job job = new Job("Пересборка проекта") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
                }
                catch (CoreException e)
                {
                    Global.logError(TAG, "promptRebuild", e); //$NON-NLS-1$
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    // === Точка, вызываемая из подменённого BslCommentUtils.parseTemplateComment ===

    /**
     * Хвост {@code parseTemplateComment}. Аргументы приходят как {@code Object}, чтобы плагин
     * не зависел от внутренних классов бандла {@code bsl.comment} на этапе компиляции.
     *
     * @param comment разобранный документирующий комментарий метода
     * @param method метод {@code com._1c.g5.v8.dt.bsl.model.Method}, чей комментарий разобран
     * @return тот же комментарий — исходный либо дополненный секцией возвращаемого значения
     */
    public static Object afterParseTemplateComment(Object comment, Object method)
    {
        try
        {
            if (comment == null || !(method instanceof EObject methodObject))
                return comment;
            // Порядок проверок — от дешёвых к дорогим: разбор комментария идёт для каждого
            // метода каждого модуля, и всё, что здесь делается, множится на их число.
            if (!isFunction(methodObject))
                return comment;
            if (returnMark(comment) == MARK_BAN)
                return comment;
            if (!isExtendedTypesEnabled(resolveProject(methodObject)))
                return comment;
            String cacheKey = methodLabel(methodObject);
            List<?> computed = typeCacheByKey.get(cacheKey);
            if (computed != null && !computed.isEmpty())
            {
                fillReturnSection(comment, computed);
                return comment;
            }
            java.util.Set<Object> guard = computing.get();
            if (!guard.add(methodObject))
                return comment;
            try
            {
                List<?> result = computeTypes(methodObject);
                if (result == null || result.isEmpty())
                {
                    // Повторный промах: вдруг другой инстанс уже посчитал тот же метод.
                    computed = typeCacheByKey.get(cacheKey);
                    if (computed != null && !computed.isEmpty())
                    {
                        fillReturnSection(comment, computed);
                        return comment;
                    }
                    return comment;
                }
                computed = result;
                typeCacheByKey.put(cacheKey, computed);
            }
            finally
            {
                guard.remove(methodObject);
            }
            if (computed == null || computed.isEmpty())
                return comment;
            fillReturnSection(comment, computed);
        }
        catch (Throwable ignored)
        {
        }
        return comment;
    }

    private static String methodLabel(EObject method)
    {
        try
        {
            Object name = Global.invoke(method, "getName"); //$NON-NLS-1$
            Resource resource = method.eResource();
            return String.valueOf(name) + "@" //$NON-NLS-1$
                + (resource != null ? resource.getURI() : method.eClass().getName());
        }
        catch (Throwable ignored)
        {
            return method.eClass().getName();
        }
    }

    /**
     * Хвост {@code parseTemplateComment(BslContextDefMethod, …)}.
     * {@code см. ОбщийМодуль.Функция} часто резолвится в экспортный {@code BslContextDefMethod},
     * а не в {@code bsl.model.Method}: без досчёта по исходному методу секция возврата пуста
     * и {@code computeTypesByLinkPart} отдаёт 0 типов (цепочка {@code Форма.КэшАФВ.}).
     */
    public static Object afterParseContextDefMethodComment(Object comment, Object contextDefMethod)
    {
        try
        {
            if (comment == null || !(contextDefMethod instanceof EObject contextMethod))
                return comment;
            EObject source = resolveContextDefSourceMethod(contextMethod);
            if (source == null)
                return comment;
            return afterParseTemplateComment(comment, source);
        }
        catch (Throwable ignored)
        {
            return comment;
        }
    }

    /** Как EDT в {@code computeTypesByLinkPart}: proxy по {@code getSourceUri} → resolve. */
    private static EObject resolveContextDefSourceMethod(EObject contextDefMethod)
    {
        try
        {
            Object uriObj = Global.invoke(contextDefMethod, "getSourceUri"); //$NON-NLS-1$
            if (!(uriObj instanceof URI uri) || uri.toString().isEmpty())
                return null;
            EObject proxy = org.eclipse.emf.ecore.EcoreFactory.eINSTANCE.createEObject();
            ((org.eclipse.emf.ecore.InternalEObject)proxy).eSetProxyURI(uri);
            EObject resolved = org.eclipse.emf.ecore.util.EcoreUtil.resolve(proxy, contextDefMethod);
            if (resolved == null || resolved.eIsProxy())
                return null;
            return resolved;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    /** Объявленный возврат имеет приоритет: если он есть, ничего не трогаем. */
    /**
     * Хвост {@code parseTemplateComment(List, boolean)} — разбора бокового комментария
     * (в строке присваивания: {@code Ф = Кукма(); // ^, Массив}). Типы такого комментария EDT
     * держит в его секции возвращаемого значения, поэтому знак читается тем же способом.
     * Запоминаем знак для следующего за этим вопроса «замещать ли рассчитанный тип».
     *
     * @param comment разобранный комментарий
     * @return тот же комментарий, без изменений
     */
    public static Object afterParseSideComment(Object comment)
    {
        try
        {
            sideCommentMark.set(Integer.valueOf(comment == null ? MARK_NONE : returnMark(comment)));
        }
        catch (Throwable ignored)
        {
        }
        return comment;
    }

    /**
     * Хвост {@code CreatorTreeState.isReplaceTypesByDocumentationComment}. Штатно при включённом
     * флажке EDT «Перезаписывать типы документирующим комментарием» тип бокового комментария
     * <b>замещает</b> рассчитанный — это умолчание сохраняется. Знак {@code ^} в боковом
     * комментарии требует объединения: отвечаем «не замещать», и рассчитанный тип остаётся
     * рядом с написанным.
     *
     * @param replace ответ EDT
     * @param context объект, к которому относится боковой комментарий
     * @return ответ EDT либо {@code false}, если затребовано объединение
     */
    public static boolean afterIsReplaceTypes(boolean replace, Object context)
    {
        try
        {
            if (!replace || !(context instanceof EObject eObject))
                return replace;
            Integer mark = sideCommentMark.get();
            if (mark == null || mark.intValue() != MARK_FORCE)
                return replace;
            if (!isExtendedTypesEnabled(resolveProject(eObject)))
                return replace;
            return false;
        }
        catch (Throwable ignored)
        {
        }
        return replace;
    }

    /**
     * Знак управления слиянием в секции возвращаемого значения.
     * <p>
     * По умолчанию (при включённом флажке проекта) рассчитанный тип добавляется к
     * объявленному. Знак {@code %} запрещает это для конкретной секции — остаётся только
     * написанное руками; знак {@code ^} требует слияния явно и от умолчания не отличается,
     * но оставляет намерение в тексте. Оба знака для EDT нейтральны: в имя типа они не
     * разрешаются и расчёт не ломают.
     *
     * @return {@link #MARK_NONE}, {@link #MARK_BAN} или {@link #MARK_FORCE}
     */
    private static int returnMark(Object comment)
    {
        Object section = Global.invoke(comment, "getReturnSection"); //$NON-NLS-1$
        if (section == null)
            return MARK_NONE;
        Object types = Global.invoke(section, "getReturnTypes"); //$NON-NLS-1$
        if (!(types instanceof List<?> sections))
            return MARK_NONE;
        for (Object typeSection : sections)
        {
            Object defs = Global.invoke(typeSection, "getTypeDefinitions"); //$NON-NLS-1$
            if (!(defs instanceof List<?> definitions))
                continue;
            for (Object definition : definitions)
            {
                Object name = Global.invoke(definition, "getTypeName"); //$NON-NLS-1$
                if (MARK_BAN_TEXT.equals(name))
                    return MARK_BAN;
                if (MARK_FORCE_TEXT.equals(name))
                    return MARK_FORCE;
            }
        }
        return MARK_NONE;
    }

    /**
     * Собирает секцию «Возвращаемое значение» из рассчитанных типов.
     * <p>
     * Сборку делает сам EDT — {@code TypeSection.fill(типы, описание, русскиеИмена, контекст)}.
     * Это его штатный перевод рассчитанных {@code TypeItem} в определения типов: он же
     * расставляет номера строк, раскрывает типы элементов коллекций и переносит свойства
     * контекста полями. Своя сборка этих же структур давала определения, которые дальше не
     * разбирались.
     * <p>
     * Признак русских имён подбирается попыткой: при неверном значении {@code fill} молча
     * выбрасывает типы, у которых нет имени на нужном языке, и секция остаётся пустой.
     */
    private static void fillReturnSection(Object comment, List<?> computed) throws Exception
    {
        ClassLoader cl = comment.getClass().getClassLoader();
        Class<?> partClass = Class.forName(
            "com._1c.g5.v8.dt.bsl.documentation.comment.IDescriptionPart", true, cl); //$NON-NLS-1$
        Class<?> returnSectionClass = Class.forName(
            COMMENT_CLASS + "$ReturnSection", true, cl); //$NON-NLS-1$
        Class<?> typeSectionClass = Class.forName(
            "com._1c.g5.v8.dt.bsl.documentation.comment.TypeSection", true, cl); //$NON-NLS-1$

        List<Object> items = new ArrayList<>();
        for (Object raw : computed)
        {
            if (raw instanceof TypeItem)
                items.add(raw);
        }
        if (items.isEmpty())
            return;

        // Объявленный возврат не замещаем, а дополняем: EDT объединяет все секции типов
        // возврата, поэтому своя секция просто встаёт рядом с написанной руками.
        Object existing = Global.invoke(comment, "getReturnSection"); //$NON-NLS-1$
        Object returnSection = existing != null ? existing
            : returnSectionClass.getConstructor(partClass, int.class)
                .newInstance(comment, Integer.valueOf(0));
        Object typeSection = typeSectionClass
            .getConstructor(partClass, int.class)
            .newInstance(returnSection, Integer.valueOf(0));

        java.lang.reflect.Method fill = typeSectionClass.getMethod("fill", //$NON-NLS-1$
            List.class, String.class, boolean.class, EObject.class);
        Object owner = Global.invoke(comment, "getMethod"); //$NON-NLS-1$
        EObject context = owner instanceof EObject eObject ? eObject : null;

        fill.invoke(typeSection, items, "", Boolean.TRUE, context); //$NON-NLS-1$
        int count = definitionCount(typeSection);
        if (count == 0)
        {
            fill.invoke(typeSection, items, "", Boolean.FALSE, context); //$NON-NLS-1$
            count = definitionCount(typeSection);
        }
        if (count == 0)
            return;
        Global.invoke(returnSection, "addTypeSection", typeSection); //$NON-NLS-1$
        if (existing == null)
            Global.invoke(comment, "setReturnSection", returnSection); //$NON-NLS-1$
    }

    /** Сколько определений типа собралось в секции. */
    private static int definitionCount(Object typeSection)
    {
        Object defs = Global.invoke(typeSection, "getTypeDefinitions"); //$NON-NLS-1$
        return defs instanceof List<?> list ? list.size() : 0;
    }

    /**
     * Загрузчик классов бандла {@code com._1c.g5.v8.dt.bsl}.
     * <p>
     * Загрузчик самой модели ({@code target.getClass().getClassLoader()}) видит только
     * {@code com._1c.g5.v8.dt.bsl.model}: пакеты {@code ...bsl.resource} и
     * {@code ...bsl.typesystem} лежат в другом бандле, и {@code Class.forName} по ним даёт
     * {@code ClassNotFoundException}. Наш бандл требует {@code com._1c.g5.v8.dt.bsl},
     * поэтому берём свой загрузчик.
     */
    private static ClassLoader bslClassLoader()
    {
        return BslDocCommentComputedTypes.class.getClassLoader();
    }

    /** {@code TypesComputer.computeTypes(EObject, Environments)} для целевой функции. */
    private static List<?> computeTypes(EObject target)
    {
        Resource resource = target.eResource();
        if (resource == null || resource.getURI() == null)
            return null;
        IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(resource.getURI());
        if (rsp == null)
            return null;
        try
        {
            Class<?> computerClass = Class.forName(TYPES_COMPUTER, true, bslClassLoader());
            Object computer = rsp.get(computerClass);
            if (computer == null)
                return null;
            Object envs = Global.invoke(target, "environments"); //$NON-NLS-1$
            if (envs == null)
                return null;
            List<?> result = asTypeList(Global.invoke(computer, "computeTypes", target, envs)); //$NON-NLS-1$
            if (result != null && !result.isEmpty())
                return result;
            // Пусто — FinalReturnState ещё нет. lightInstall пишет в LIGHT; TypesComputer
            // при !isLinkedBatch предпочитает LIGHT даже пустой — NORMAL после полного
            // installTypeSystem тогда не виден. Поэтому после досчёта читаем NORMAL сами.
            boolean lit = lightInstallTypeSystem(rsp, target);
            if (!lit)
                return finalReturnTypes(target, envs);
            result = finalReturnTypes(target, envs);
            if (result != null && !result.isEmpty())
                return result;
            if (installTargetModuleTypeSystem(target))
            {
                result = finalReturnTypes(target, envs);
                if (result != null && !result.isEmpty())
                    return result;
                result = asTypeList(Global.invoke(computer, "computeTypes", target, envs)); //$NON-NLS-1$
                if (result != null && !result.isEmpty())
                    return result;
            }
            return result;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static List<?> asTypeList(Object result)
    {
        return result instanceof List<?> list ? list : null;
    }

    /**
     * Типы возврата функции из {@code getFinalReturnState}: сначала NORMAL (полный
     * {@code installTypeSystem}), затем LIGHT. Обход бага TypesComputer: пустой LIGHT
     * перекрывает непустой NORMAL при {@code !isLinkedBatch}.
     */
    private static List<?> finalReturnTypes(EObject target, Object envs)
    {
        try
        {
            Object collector = Global.invoke(target, "getFinalReturnState"); //$NON-NLS-1$
            if (collector == null)
                return null;
            List<?> normal = typesFromReturnProvider(collector, "NORMAL", envs); //$NON-NLS-1$
            if (normal != null && !normal.isEmpty())
                return normal;
            return typesFromReturnProvider(collector, "LIGHT", envs); //$NON-NLS-1$
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static List<?> typesFromReturnProvider(Object collector, String modeName, Object envs)
        throws Exception
    {
        Class<?> modeClass = Class.forName(
            "com._1c.g5.v8.dt.bsl.model.typesytem.TypeSystemMode", true, bslClassLoader()); //$NON-NLS-1$
        @SuppressWarnings({ "unchecked", "rawtypes" })
        Object mode = Enum.valueOf((Class<Enum>)modeClass.asSubclass(Enum.class), modeName);
        Object provider = Global.invoke(collector, "get", mode); //$NON-NLS-1$
        if (provider == null)
            return null;
        Object last = Global.invoke(provider, "getLastState", envs); //$NON-NLS-1$
        if (last == null)
        {
            // Перегрузка без Environments (как в syncExportPropertyTypes).
            Object all = Global.invoke(provider, "getLastState"); //$NON-NLS-1$
            if (all instanceof List<?> states && !states.isEmpty())
            {
                List<Object> types = new ArrayList<>();
                for (Object state : states)
                {
                    Object iterable = state == null ? null : Global.invoke(state, "getTypes"); //$NON-NLS-1$
                    if (iterable instanceof Iterable<?> it)
                    {
                        for (Object item : it)
                        {
                            if (item instanceof TypeItem)
                                types.add(item);
                        }
                    }
                }
                return types.isEmpty() ? null : types;
            }
            return null;
        }
        // VariableTreeTypeStateWithSubStates.getSubStates(envs) — как TypesComputer._compute
        Object subStates = null;
        try
        {
            subStates = Global.invoke(last, "getSubStates", envs); //$NON-NLS-1$
        }
        catch (Throwable ignored)
        {
        }
        if (subStates instanceof List<?> list && !list.isEmpty())
        {
            List<Object> types = new ArrayList<>();
            for (Object state : list)
            {
                Object iterable = state == null ? null : Global.invoke(state, "getTypes"); //$NON-NLS-1$
                if (iterable instanceof Iterable<?> it)
                {
                    for (Object item : it)
                    {
                        if (item instanceof TypeItem)
                            types.add(item);
                    }
                }
            }
            if (!types.isEmpty())
                return types;
        }
        Object typesObj = Global.invoke(last, "getTypes"); //$NON-NLS-1$
        if (typesObj instanceof Iterable<?> it)
        {
            List<Object> types = new ArrayList<>();
            for (Object item : it)
            {
                if (item instanceof TypeItem)
                    types.add(item);
            }
            return types;
        }
        return null;
    }

    /**
     * Полный {@code installTypeSystem} модуля цели {@code см. …}, если light-проход
     * не дал типов возврата (типично: ссылка из модуля формы на общий модуль).
     */
    private static boolean installTargetModuleTypeSystem(EObject target)
    {
        if (!resolvingDocLink() && !Boolean.TRUE.equals(forceLightInstall.get()))
            return false;
        Object moduleObj = containerOfType(target, "com._1c.g5.v8.dt.bsl.model.Module"); //$NON-NLS-1$
        if (!(moduleObj instanceof EObject module) || module.eIsProxy())
            return false;
        if (!INSTALLING_TARGET_MODULE.add(module))
            return false;
        try
        {
            com._1c.g5.v8.dt.bsl.typesystem.BslTreeTypeSystem tree =
                BslStructureInsertCommentTypes.peekTreeTypeSystem();
            if (tree == null)
                return false;
            BslStructureInsertCommentTypes.installTypeSystemNonInterruptable(tree,
                (com._1c.g5.v8.dt.bsl.model.Module)module);
            return true;
        }
        catch (Throwable ignored)
        {
            return false;
        }
        finally
        {
            INSTALLING_TARGET_MODULE.remove(module);
        }
    }

    /**
     * Досчёт типов одного метода штатным механизмом EDT
     * {@code BslTreeTypeSystem.lightInstallingTypeSystem(Module, Method, Variable, Statement,
     * int, BmOperationContext)} — тем же, которым пользуется автодополнение, когда тип нужен
     * здесь и сейчас, а полный разбор модуля не закончен. Ограничения по экспортности у него
     * нет: считается любой метод модуля.
     * <p>
     * <b>Зачем подставная переменная.</b> По байт-коду аргумент {@code variable} используется
     * ровно в одном месте (BslTreeTypeSystem:337) — как условие
     * {@code variable.getTypeStateProvider() != null} перед завершающим {@code computeAllState},
     * который и заполняет состояние возврата. С {@code null} там NPE, и досчёт обрывается, не
     * дойдя до расчёта. Поэтому передаём собственную несвязанную переменную с пустым
     * накопителем состояний: модель EDT она не трогает, а ворота открывает.
     * <p>
     * Метода нет в интерфейсе {@code ITypeSystem}, поэтому зовётся рефлексией по конкретному
     * классу: пропадёт в новой версии EDT — просто вернёмся к поведению без слияния.
     * {@code BmOperationContext} передаём {@code null}; если EDT его потребует, отказ ловится
     * и слияния не происходит.
     *
     * @return {@code true}, если досчёт выполнен
     */
    static void enableForceLightInstall()
    {
        forceLightInstall.set(Boolean.TRUE);
    }

    static void disableForceLightInstall()
    {
        forceLightInstall.remove();
    }

    private static boolean lightInstallTypeSystem(IResourceServiceProvider rsp, EObject target)
    {
        if (!resolvingDocLink() && !Boolean.TRUE.equals(forceLightInstall.get()))
        {
            // Рассчитанный тип нужен только там, где EDT разрешает ссылку «см. Метод»: именно
            // там документирующий комментарий целевого метода пуст. Разбор комментария самого
            // метода идёт для каждого метода каждого модуля, и досчёт в этих местах — обход
            // всего модуля впустую, умноженный на число методов.
            return false;
        }
        if (Boolean.TRUE.equals(lightInstalling.get()))
        {
            // Досчёт сам разбирает комментарии всех методов модуля, и каждый такой разбор
            // просился бы досчитать себя: вложенность множится на число методов. Защита по
            // одному методу (computing) этого не ловит — методы каждый раз разные.
            return false;
        }
        lightInstalling.set(Boolean.TRUE);
        try
        {
            // ITypeSystem напрямую в инжекторе не связан, а getTypeSystem() без вида отдаёт
            // SIMPLE, у которого в BslTypeSystemProvider жёстко null. Дерево — под видом TREE.
            Class<?> providerClass = Class.forName(TYPE_SYSTEM_PROVIDER, true, bslClassLoader());
            Object provider = rsp.get(providerClass);
            Object typeSystem = provider == null ? null
                : Global.invoke(provider, "getTypeSystem", treeTypeSystemKind()); //$NON-NLS-1$
            if (typeSystem == null)
                return false;
            Object module = containerOfType(target, "com._1c.g5.v8.dt.bsl.model.Module"); //$NON-NLS-1$
            if (module == null)
                return false;
            Variable gate = BslFactory.eINSTANCE.createImplicitVariable();
            gate.setTypeStateProvider(new VariableTypeStateProviderCollector());
            for (java.lang.reflect.Method m : typeSystem.getClass().getMethods())
            {
                if (!"lightInstallingTypeSystem".equals(m.getName()) //$NON-NLS-1$
                    || m.getParameterCount() != 6)
                    continue;
                m.invoke(typeSystem, module, target, gate, null, Integer.valueOf(0), null);
                return true;
            }
        }
        catch (Throwable ignored)
        {
        }
        finally
        {
            lightInstalling.remove();
        }
        return false;
    }

    /**
     * Разрешается ли сейчас ссылка {@code см. Метод} документирующего комментария
     * ({@code BslDocumentationComment.computeTypesByLinkPart}).
     */
    private static boolean resolvingDocLink()
    {
        return StackWalker.getInstance().walk(frames -> frames.limit(40)
            .anyMatch(frame -> LINK_RESOLVE_METHOD.equals(frame.getMethodName())
                && frame.getClassName().equals(COMMENT_CLASS)));
    }

    /** Значение {@code BslTypeSystemProvider.BslTypeSystemKind.TREE}. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object treeTypeSystemKind() throws ClassNotFoundException
    {
        Class<?> kindClass =
            Class.forName(TYPE_SYSTEM_PROVIDER + "$BslTypeSystemKind", true, bslClassLoader()); //$NON-NLS-1$
        return Enum.valueOf((Class<Enum>)kindClass.asSubclass(Enum.class), "TREE"); //$NON-NLS-1$
    }

    private static Object containerOfType(EObject object, String className)
    {
        try
        {
            Class<?> wanted = Class.forName(className, false, object.getClass().getClassLoader());
            for (EObject current = object.eContainer(); current != null;
                current = current.eContainer())
            {
                if (wanted.isInstance(current))
                    return current;
            }
        }
        catch (Throwable t)
        {
        }
        return null;
    }

    private static boolean isFunction(EObject object)
    {
        try
        {
            return Class.forName(BSL_FUNCTION, false, object.getClass().getClassLoader())
                .isInstance(object);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * Проект по URI ресурса: {@code platform:/resource/<проект>/…} или
     * {@code bm://<проект>/…} (форма/МД в BM — authority = имя проекта).
     */
    private static IProject resolveProject(EObject object)
    {
        if (object == null)
            return null;
        Resource resource = object.eResource();
        URI uri = resource != null ? resource.getURI() : null;
        if (uri == null)
            return null;
        try
        {
            String projectName = null;
            if (uri.isPlatformResource())
            {
                if (uri.segmentCount() >= 2)
                    projectName = uri.segment(1);
            }
            else if ("bm".equals(uri.scheme())) //$NON-NLS-1$
            {
                // bm://Конфигурация/CommonForm.… — проект в authority
                projectName = uri.authority();
                if (projectName == null || projectName.isEmpty())
                {
                    // bm:/Конфигурация/… без authority
                    if (uri.segmentCount() >= 1)
                        projectName = uri.segment(0);
                }
            }
            if (projectName == null || projectName.isEmpty())
                return null;
            return ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    // === Инструментализация BslCommentUtils ===

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
                if (mv == null || !PARSE_METHOD.equals(name)
                    || !descriptor.endsWith(")L" + COMMENT_INTERNAL + ";")) //$NON-NLS-1$ //$NON-NLS-2$
                    return mv;
                if (SIDE_PARSE_DESC.equals(descriptor))
                {
                    // Боковой комментарий: метода тут нет, считать по телу нечего — только
                    // запоминаем знак управления слиянием для следующего вопроса EDT.
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.ARETURN)
                            {
                                visitMethodInsn(Opcodes.INVOKESTATIC, SELF_INTERNAL,
                                    "afterParseSideComment", AFTER_SIDE_DESC, false); //$NON-NLS-1$
                                visitTypeInsn(Opcodes.CHECKCAST, COMMENT_INTERNAL);
                                touched.set(true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                if (CTX_PARSE_DESC.equals(descriptor))
                {
                    // см. ОбщийМодуль.Метод → BslContextDefMethod: досчёт по исходному Method.
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.ARETURN)
                            {
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitMethodInsn(Opcodes.INVOKESTATIC, SELF_INTERNAL,
                                    "afterParseContextDefMethodComment", AFTER_CTX_DESC, false); //$NON-NLS-1$
                                visitTypeInsn(Opcodes.CHECKCAST, COMMENT_INTERNAL);
                                touched.set(true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                final int methodSlot = bslMethodSlot(descriptor, (access & Opcodes.ACC_STATIC) != 0);
                if (methodSlot < 0)
                    return mv;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitInsn(int opcode)
                    {
                        if (opcode == Opcodes.ARETURN)
                        {
                            visitVarInsn(Opcodes.ALOAD, methodSlot);
                            visitMethodInsn(Opcodes.INVOKESTATIC, SELF_INTERNAL,
                                "afterParseTemplateComment", AFTER_PARSE_DESC, false); //$NON-NLS-1$
                            visitTypeInsn(Opcodes.CHECKCAST, COMMENT_INTERNAL);
                            touched.set(true);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return touched.get() ? writer.toByteArray() : null;
    }

    /**
     * Инструментализация {@code CreatorTreeState}: в хвост
     * {@code isReplaceTypesByDocumentationComment} добавляется наш ответ, который умеет
     * отменить замещение при знаке {@code ^} в боковом комментарии.
     *
     * @return изменённый класс либо {@code null}, если вставки не было
     */
    static byte[] transformCreatorClass(byte[] classfileBuffer)
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
                if (mv == null || !REPLACE_METHOD.equals(name) || !descriptor.endsWith(")Z")) //$NON-NLS-1$
                    return mv;
                final int contextSlot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitInsn(int opcode)
                    {
                        if (opcode == Opcodes.IRETURN)
                        {
                            visitVarInsn(Opcodes.ALOAD, contextSlot);
                            visitMethodInsn(Opcodes.INVOKESTATIC, SELF_INTERNAL,
                                "afterIsReplaceTypes", AFTER_REPLACE_DESC, false); //$NON-NLS-1$
                            touched.set(true);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return touched.get() ? writer.toByteArray() : null;
    }

    /**
     * Номер локальной переменной с {@code com._1c.g5.v8.dt.bsl.model.Method}.
     * Перегрузка по списку строк без метода пропускается; {@code BslContextDefMethod}
     * обрабатывается отдельно ({@link #afterParseContextDefMethodComment}).
     *
     * @return номер слота либо -1
     */
    private static int bslMethodSlot(String descriptor, boolean isStatic)
    {
        Type[] args = Type.getArgumentTypes(descriptor);
        int slot = isStatic ? 0 : 1;
        for (Type arg : args)
        {
            if (BSL_METHOD_DESC.equals(arg.getClassName()))
                return slot;
            slot += arg.getSize();
        }
        return -1;
    }

    private static final class ParseWeavingHook implements WeavingHook
    {
        @Override
        public void weave(WovenClass wovenClass)
        {
            if (wovenClass.getState() != WovenClass.TRANSFORMING)
                return;
            if (TARGET_CREATOR.equals(wovenClass.getClassName()))
            {
                weaveCreator(wovenClass);
                return;
            }
            if (!TARGET_UTILS.equals(wovenClass.getClassName()))
                return;
            try
            {
                byte[] transformed = transformClass(wovenClass.getBytes());
                if (transformed != null)
                {
                    // Вплетённый код зовёт наш класс напрямую, а бандл EDT пакет tormozit
                    // не видит — без динамического импорта получаем NoClassDefFoundError
                    // tormozit/BslDocCommentComputedTypes при первом же разборе комментария.
                    // Пакет экспортируется в MANIFEST.MF ровно для этого.
                    wovenClass.getDynamicImports().add("tormozit"); //$NON-NLS-1$
                    wovenClass.setBytes(transformed);
                }
            }
            catch (Throwable t)
            {
            }
        }

        private void weaveCreator(WovenClass wovenClass)
        {
            try
            {
                byte[] transformed = transformCreatorClass(wovenClass.getBytes());
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
}
