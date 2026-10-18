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

import com._1c.g5.v8.dt.bsl.documentation.comment.BslDocumentationComment;
import com._1c.g5.v8.dt.bsl.documentation.comment.BslDocumentationComment.ReturnSection;
import com._1c.g5.v8.dt.bsl.documentation.comment.TypeSection;
import com._1c.g5.v8.dt.bsl.model.Method;
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
    private static final String COMMENT_INTERNAL =
        "com/_1c/g5/v8/dt/bsl/documentation/comment/BslDocumentationComment"; //$NON-NLS-1$
    private static final String PARSE_METHOD = "parseTemplateComment"; //$NON-NLS-1$
    private static final String BSL_METHOD_DESC = "com._1c.g5.v8.dt.bsl.model.Method"; //$NON-NLS-1$
    private static final String BSL_FUNCTION = "com._1c.g5.v8.dt.bsl.model.Function"; //$NON-NLS-1$
    private static final String TYPES_COMPUTER =
        "com._1c.g5.v8.dt.bsl.resource.TypesComputer"; //$NON-NLS-1$
    /** Знак «не объединять рассчитанный тип с документирующим». */
    private static final String MARK_BAN_TEXT = "%"; //$NON-NLS-1$
    /** Знак «объединить рассчитанный тип с документирующим». */
    private static final String MARK_FORCE_TEXT = "^"; //$NON-NLS-1$

    private static final int MARK_NONE = 0;
    private static final int MARK_BAN = 1;
    private static final int MARK_FORCE = 2;

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
     * Рассчитанные EDT типы возврата функций. Пишет только {@link #rememberModuleReturnTypes} —
     * после штатного полного расчёта модуля; сами мы модули не считаем. Досчёт цели «см.» из
     * разбора ссылки (облегчённый или полный расчёт её модуля) запускал расчёт чужих модулей
     * в чужих потоках — сборка, «Напарник», ForkJoin — и вешал EDT на БСП (18.10.2026).
     * <p>
     * Ключ — {@code имя@URI#метка текста функции} ({@link #versionKey}), не сам {@code EObject}:
     * модуль живёт в разных наборах ресурсов (редактор, сборка, «Напарник»). Одинаковый текст
     * функции делит запись, разный — хранится отдельно: со штампом файла несохранённый текст
     * редактора и сохранённый у сборки перезаписывали друг друга, и редактор читал тип чужой
     * версии (лог 18.10: «Котушок» вместо «Кот»).
     */
    private static final java.util.Map<String, CachedReturn> typeCacheByKey =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Идёт {@link #rememberModuleReturnTypes} в этом потоке. Чтение типа функции разбирает её
     * комментарий, и без этого флага хук подмешивал бы в него прошлую запись кэша: тип
     * включал бы собственное слияние, сигнатура менялась бы на каждом расчёте, и модуль со
     * ссылками на себя считался бы дважды при каждой перевязке (лог 18.10: `changed=1` на
     * каждом расчёте `СтрогийКэш`, в т.ч. из автодополнения и подсказки).
     */
    private static final ThreadLocal<Boolean> remembering = new ThreadLocal<>();

    /**
     * {@code buildHash}/{@code editorHash} — 64-битные отпечатки {@link #typeSignature} у сборки и у
     * редактора, {@code 0} — эта сторона текст ещё не считала. Для одного текста они получают
     * разный тип (сборка — без {@code null{}}), и при общем отпечатке каждая перетирала другую:
     * сборка всякий раз видела «изменились 47 функций» и делала полный второй проход (БСП, 18.10
     * 10:02–10:04, 11 с на сборку). Каждая сторона сравнивает только со своим отпечатком.
     */
    private record CachedReturn(List<?> types, long buildHash, long editorHash)
    {
        long hash(boolean build)
        {
            return build ? buildHash : editorHash;
        }

        static CachedReturn with(CachedReturn previous, List<?> types, boolean build, long hash)
        {
            long other = previous == null ? 0L : previous.hash(!build);
            return build ? new CachedReturn(types, hash, other) : new CachedReturn(types, other, hash);
        }
    }

    /** 64-битный FNV-1a: строку сигнатуры в кэше не держим, она бывает в килобайты. */
    private static long signatureHash(String signature)
    {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < signature.length(); i++)
        {
            hash ^= signature.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash == 0L ? 1L : hash;
    }

    /**
     * Растёт после пересчёта методов, ссылающихся «см.» на функцию с новой версией текста
     * ({@link #bumpTypesGeneration}). По нему кэши автодополнения видят, что слитые типы сменились.
     */
    private static final java.util.concurrent.atomic.AtomicInteger TYPES_GENERATION =
        new java.util.concurrent.atomic.AtomicInteger();

    static int typesGeneration()
    {
        return TYPES_GENERATION.get();
    }

    static void bumpTypesGeneration()
    {
        TYPES_GENERATION.incrementAndGet();
    }

    /** Сколько версий текста одной функции держать (редактор, сборка, производные данные…). */
    private static final int VERSIONS_PER_FUNCTION = 4;

    /** {@code имя@URI} → ключи его версий в {@link #typeCacheByKey}, старые первыми. */
    private static final java.util.Map<String, java.util.Deque<String>> versionsByLabel =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Метка версии — длина и хеш текста функции (узел модели: заголовок и тело). Текст функции,
     * не модуля: модули БСП — до миллиона символов, а метка нужна на каждый разбор ссылки.
     */
    private static String versionKey(String label, EObject method)
    {
        var node = org.eclipse.xtext.nodemodel.util.NodeModelUtils.getNode(method);
        if (node == null)
            return label + "#?"; //$NON-NLS-1$
        // Только сам метод, без прилегающих скрытых узлов (комментарии, пробелы): после
        // частичного переразбора соседа они переходят от узла к узлу, и метка менялась без
        // правки метода — второй проход по 19 методам на чужую правку (лог 18.10 02:28).
        String text = node.getText();
        int start = Math.max(0, Math.min(text.length(), node.getOffset() - node.getTotalOffset()));
        int end = Math.max(start, Math.min(text.length(), start + node.getLength()));
        text = text.substring(start, end);
        return label + "#" + text.length() + ":" + Integer.toHexString(text.hashCode()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void putVersion(String label, String key, CachedReturn value)
    {
        typeCacheByKey.put(key, value);
        java.util.Deque<String> versions = versionsByLabel.computeIfAbsent(label,
            ignored -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        versions.remove(key);
        versions.addLast(key);
        while (versions.size() > VERSIONS_PER_FUNCTION)
        {
            String oldest = versions.pollFirst();
            if (oldest != null)
                typeCacheByKey.remove(oldest);
        }
    }

    /** Рассчитанные типы возврата функции из кэша (для текущего текста) или {@code null}. */
    static List<?> cachedTypesFor(EObject function)
    {
        return function == null ? null : cachedReturnTypes(methodLabel(function), function);
    }

    /** Типы из кэша для текущего текста функции; иначе {@code null}. */
    private static List<?> cachedReturnTypes(String cacheKey, EObject method)
    {
        CachedReturn cached = typeCacheByKey.get(versionKey(cacheKey, method));
        return cached == null ? null : cached.types();
    }

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
        for (java.util.Map.Entry<String, CachedReturn> entry : typeCacheByKey.entrySet())
        {
            String key = entry.getKey();
            List<?> cached = entry.getValue() == null ? null : entry.getValue().types();
            if (key == null || cached == null || cached.isEmpty())
                continue;
            if (!key.regionMatches(true, 0, needle, 0, needle.length()))
                continue;
            List<TypeItem> types = new ArrayList<>();
            for (Object item : cached)
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
            ParamHintHtmlModifier.rememberNativeDocumentationComment(comment, methodObject);
            // Порядок проверок — от дешёвых к дорогим: разбор комментария идёт для каждого
            // метода каждого модуля, и всё, что здесь делается, множится на их число.
            if (!isFunction(methodObject))
                return comment;
            if (returnMark(comment) == MARK_BAN)
                return comment;
            if (!isExtendedTypesEnabled(resolveProject(methodObject)))
                return comment;
            if (Boolean.TRUE.equals(remembering.get()))
                return comment;
            // Только при разрешении ссылки «см.» из другого места. Разбор собственного комментария
            // функции при расчёте её же типа возврата получал бы из кэша её прежний тип: тот
            // попадал в тип функции, оттуда снова в кэш — петля, старый ключ структуры не уходил
            // и у прямого вызова «Функция().» (18.10: «ДобавлятьПанельБыстрыхОтборов» после
            // переименования в «…1»).
            if (!resolvingDocLink())
                return comment;
            // Только готовое: модуль цели ещё не считался — штатный тип без слияния.
            String key = methodLabel(methodObject);
            List<?> computed = cachedReturnTypes(key, methodObject);
            // #region agent log
            if (computed == null || computed.isEmpty())
            {
                Global.tempLog("bsl-cache-miss", "key=" + versionKey(key, methodObject) //$NON-NLS-1$ //$NON-NLS-2$
                    + " versions=" + versionsByLabel.get(key) //$NON-NLS-1$
                    + " thread=" + Thread.currentThread().getName()); //$NON-NLS-1$
            }
            // #endregion
            if (computed != null && !computed.isEmpty())
                fillReturnSection(comment, computed, methodObject);
        }
        catch (Throwable failure)
        {
            Global.tempLogException("bsl-return-fill", "afterParseTemplateComment failed", failure); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return comment;
    }

    /** Разрешается ли сейчас ссылка «см. …» ({@code BslDocumentationComment.computeTypesByLinkPart}). */
    private static boolean resolvingDocLink()
    {
        return StackWalker.getInstance().walk(frames -> frames.limit(16)
            .anyMatch(frame -> "computeTypesByLinkPart".equals(frame.getMethodName()) //$NON-NLS-1$
                && "com._1c.g5.v8.dt.bsl.documentation.comment.BslDocumentationComment" //$NON-NLS-1$
                    .equals(frame.getClassName())));
    }

    private static String methodLabel(EObject method)
    {
        try
        {
            Object name = BslDocCommentDescriptionFix.invokeCommentGetter(method, "getName"); //$NON-NLS-1$
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
            Object uriObj = BslDocCommentDescriptionFix.invokeCommentGetter(contextDefMethod, "getSourceUri"); //$NON-NLS-1$
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
        if (!(comment instanceof BslDocumentationComment documentation))
            return MARK_NONE;
        ReturnSection section = documentation.getReturnSection();
        if (section == null)
            return MARK_NONE;
        for (TypeSection typeSection : section.getReturnTypes())
        {
            for (var definition : typeSection.getTypeDefinitions())
            {
                String name = definition.getTypeName();
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
    // #region agent log
    /** Временная диагностика шторма: глубина стека и вложенность разбора ссылок «см.». */
    private static String nestingDiagnostics()
    {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int linkFrames = 0;
        int parseFrames = 0;
        for (StackTraceElement frame : stack)
        {
            String name = frame.getMethodName();
            if ("computeTypesByLinkPart".equals(name)) //$NON-NLS-1$
                linkFrames++;
            else if ("afterParseTemplateComment".equals(name)) //$NON-NLS-1$
                parseFrames++;
        }
        return "depth=" + stack.length + " linkFrames=" + linkFrames + " hookFrames=" + parseFrames; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
    // #endregion

    /**
     * Все рассчитанные типы и их свойства уже есть в объявленной секции возврата: дописывать
     * нечего. Имя типа сравнивается и английское, и русское — в шапке пишут «Структура».
     */
    private static boolean addsNothingToDeclared(List<TypeItem> items, ReturnSection declared)
    {
        java.util.Map<String, java.util.Set<String>> declaredFields = new java.util.HashMap<>();
        for (TypeSection section : declared.getReturnTypes())
            for (TypeSection.TypeDefinition definition : section.getTypeDefinitions())
            {
                if (definition.getTypeName() == null)
                    continue;
                java.util.Set<String> names = declaredFields.computeIfAbsent(
                    definition.getTypeName().toLowerCase(java.util.Locale.ROOT), key -> new java.util.HashSet<>());
                for (TypeSection.FieldDefinition field : definition.getFieldDefinitionExtension())
                    if (field.getName() != null)
                        names.add(field.getName().toLowerCase(java.util.Locale.ROOT));
            }
        for (TypeItem item : items)
        {
            java.util.Set<String> names = null;
            for (String method : new String[] { "getNameRu", "getName" }) //$NON-NLS-1$ //$NON-NLS-2$
            {
                Object name = Global.invoke(item, method);
                if (names == null && name instanceof String text)
                    names = declaredFields.get(text.toLowerCase(java.util.Locale.ROOT));
            }
            if (names == null)
                return false;
            if (item instanceof com._1c.g5.v8.dt.mcore.Type type && !type.eIsProxy()
                && type.getContextDef() != null && type.getContextDef().allProperties() != null)
                // allProperties — как в TypeSection.fill: с унаследованными полями.
                for (Object property : type.getContextDef().allProperties())
                {
                    Object name = Global.invoke(property, "getName"); //$NON-NLS-1$
                    if (name instanceof String text && !names.contains(text.toLowerCase(java.util.Locale.ROOT)))
                        return false;
                }
        }
        return true;
    }

    /** Предел обхода вложенных типов: штатный fill разворачивает их все. */
    private static final int MAX_NESTED_TYPES = 500;

    /**
     * Тип через свойства ссылается сам на себя (по цепочке вложенности) или вложенных типов
     * больше {@link #MAX_NESTED_TYPES}: штатный {@code TypeSection.fill} на таком уйдёт в
     * бесконечную или огромную рекурсию.
     */
    private static boolean isCyclicOrHuge(List<TypeItem> items)
    {
        int[] visited = { 0 };
        boolean russian = items.stream().anyMatch(item ->
            com._1c.g5.v8.dt.mcore.util.McoreUtil.getTypeNameRu(item) != null);
        return cyclicFrom(items, russian,
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), visited);
    }

    /**
     * Тот же выбор владельца полей, что в TypeSection.fill (байткод бандла bsl.comment).
     * EDT раскрывает BslDerivedPropertySource у любого Type, а не только ExtendedType.
     * Проверка только ExtendedType пропускала рекурсию fill в 3844 кадра (18.10.2026).
     */
    static com._1c.g5.v8.dt.mcore.Type fillPropertyOwner(List<? extends TypeItem> items,
        boolean russian)
    {
        com._1c.g5.v8.dt.mcore.Type owner = null;
        for (TypeItem item : items)
        {
            String name = russian ? com._1c.g5.v8.dt.mcore.util.McoreUtil.getTypeNameRu(item)
                : com._1c.g5.v8.dt.mcore.util.McoreUtil.getTypeName(item);
            if (name == null || item.eIsProxy()
                || !(item instanceof com._1c.g5.v8.dt.mcore.Type type))
                continue;
            if (item instanceof com._1c.g5.v8.dt.bsl.model.ExtendedCollectionType)
            {
                for (TypeItem element : type.getCollectionElementTypes().allTypes())
                    if (element instanceof com._1c.g5.v8.dt.bsl.model.ExtendedType elementType
                        && elementType.getContextDef() != null
                        && elementType.getContextDef().getProperties().stream()
                            .anyMatch(BslDocCommentComputedTypes::isFillProperty))
                        owner = elementType;
                continue;
            }
            String englishName = com._1c.g5.v8.dt.mcore.util.McoreUtil.getTypeName(item);
            if ("ValueTable".equals(englishName)) //$NON-NLS-1$
            {
                List<TypeItem> elements = type.getCollectionElementTypes().allTypes();
                if (!elements.isEmpty() && "ValueTableRow".equals( //$NON-NLS-1$
                    com._1c.g5.v8.dt.mcore.util.McoreUtil.getTypeName(elements.get(0)))
                    && elements.get(0) instanceof com._1c.g5.v8.dt.mcore.Type row)
                    type = row;
            }
            else if ("ValueTree".equals(englishName)) //$NON-NLS-1$
            {
                for (com._1c.g5.v8.dt.mcore.Property property : type.getContextDef().getProperties())
                    if ("Rows".equals(property.getName())) //$NON-NLS-1$
                    {
                        List<TypeItem> rows = property.getTypes();
                        if (!rows.isEmpty() && rows.get(0) instanceof com._1c.g5.v8.dt.mcore.Type rowsType)
                        {
                            List<TypeItem> elements = rowsType.getCollectionElementTypes().allTypes();
                            if (!elements.isEmpty()
                                && elements.get(0) instanceof com._1c.g5.v8.dt.mcore.Type row)
                                type = row;
                        }
                        break;
                    }
            }
            if (type.getContextDef() != null && type.getContextDef().allProperties().stream()
                .anyMatch(BslDocCommentComputedTypes::isFillProperty))
                owner = type;
        }
        return owner;
    }

    private static boolean isFillProperty(com._1c.g5.v8.dt.mcore.Property property)
    {
        return property instanceof com._1c.g5.v8.dt.mcore.DerivedProperty derived
            && derived.getSource() instanceof com._1c.g5.v8.dt.bsl.model.BslDerivedPropertySource;
    }

    /** Считаем развёртывания секций, включая повтор одного поддерева в разных полях. */
    private static boolean cyclicFrom(List<? extends TypeItem> items, boolean russian,
        java.util.Set<Object> path, int[] visited)
    {
        if (++visited[0] > MAX_NESTED_TYPES)
            return true;
        com._1c.g5.v8.dt.mcore.Type owner = fillPropertyOwner(items, russian);
        if (owner == null)
            return false;
        if (!path.add(owner))
            return true;
        try
        {
            for (com._1c.g5.v8.dt.mcore.Property property : owner.getContextDef().allProperties())
                if (isFillProperty(property) && property.getName() != null && !property.getName().isEmpty()
                    && !property.getTypeContainer().allTypes().isEmpty()
                    && cyclicFrom(property.getTypeContainer().allTypes(), russian, path, visited))
                    return true;
            return false;
        }
        finally
        {
            path.remove(owner);
        }
    }

    private static void fillReturnSection(Object comment, List<?> computed, EObject context) throws Exception
    {
        if (!(comment instanceof BslDocumentationComment documentation))
            return;
        long sectionStarted = System.nanoTime();
        List<TypeItem> items = new ArrayList<>();
        for (Object raw : computed)
        {
            if (raw instanceof TypeItem item)
                items.add(item);
        }
        if (items.isEmpty())
            return;

        // Объявленный возврат не замещаем, а дополняем: EDT объединяет все секции типов
        // возврата, поэтому своя секция просто встаёт рядом с написанной руками.
        ReturnSection existing = documentation.getReturnSection();
        // До штатного fill: он разворачивает свойства вложенных типов без защиты от циклов, а
        // слитый тип самоссылающейся структуры (ОписаниеУзла: поля «см. ОписаниеУзла») замкнут
        // сам на себя — сборка висела в рекурсии fill глубже 1000 кадров (дамп 18.10).
        if (existing != null && addsNothingToDeclared(items, existing))
            return;
        Global.tempLog("bsl-return-fill", "guard.begin target=" + methodLabel(context) //$NON-NLS-1$ //$NON-NLS-2$
            + " computedId=" + Integer.toHexString(System.identityHashCode(computed)) //$NON-NLS-1$
            + " classes=" + items.stream().map(item -> item.getClass().getName()).toList()); //$NON-NLS-1$
        boolean unsafe = isCyclicOrHuge(items);
        Global.tempLog("bsl-return-fill", "guard.end target=" + methodLabel(context) //$NON-NLS-1$ //$NON-NLS-2$
            + " unsafe=" + unsafe + " elapsedNs=" + (System.nanoTime() - sectionStarted)); //$NON-NLS-1$ //$NON-NLS-2$
        if (unsafe)
        {
            // #region agent log
            Global.tempLog("bsl-property-fill-cost", "skip cyclic target=" + methodLabel(context)); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return;
        }
        ReturnSection returnSection = existing != null ? existing : new ReturnSection(documentation, 0);
        TypeSection typeSection = new TypeSection(returnSection, 0);

        fillTypeSection(typeSection, items, true, context);
        int count = typeSection.getTypeDefinitions().size();
        if (count == 0)
        {
            fillTypeSection(typeSection, items, false, context);
            count = typeSection.getTypeDefinitions().size();
        }
        if (count == 0)
            return;
        // Место создания полей — и в сборке: из поля его берёт свойство, которое EDT создаёт
        // по секции, а без места вставки подсказка не найдёт описание свойства.
        try
        {
            BslStructureInsertCommentTypes.alignFieldSources(typeSection, documentation, items,
                computed, methodLabel(context));
        }
        catch (Throwable ignored)
        {
        }
        long descriptionsStarted = System.nanoTime();
        // Описания полей — только текст для подсказок. Комментарий, разобранный сборкой, в
        // подсказки не попадает (EDT разбирает его заново на каждое обращение), а сборка
        // держит блокировку проекта, и первое нажатие клавиши после сохранения ждёт её конца.
        boolean inBuild = isBuildThread();
        boolean cached = false;
        try
        {
            if (!inBuild)
                cached = BslStructureInsertCommentTypes.fillPropertyDescriptions(typeSection, items,
                    computed, methodLabel(context), context);
        }
        catch (Throwable t)
        {
            Global.tempLogException("bsl-property-fill-cost", "fill target=" + methodLabel(context), t); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            Global.tempLog("bsl-property-fill-cost", "fill target=" + methodLabel(context) //$NON-NLS-1$ //$NON-NLS-2$
                + " commentId=" + Integer.toHexString(System.identityHashCode(comment)) //$NON-NLS-1$
                + " computedId=" + Integer.toHexString(System.identityHashCode(computed)) //$NON-NLS-1$
                + " types=" + items.size() + " definitions=" + count //$NON-NLS-1$ //$NON-NLS-2$
                + " inBuild=" + inBuild + " cached=" + cached //$NON-NLS-1$ //$NON-NLS-2$
                + " " + nestingDiagnostics() //$NON-NLS-1$
                + " sectionFillNs=" + (descriptionsStarted - sectionStarted) //$NON-NLS-1$
                + " elapsedNs=" + (System.nanoTime() - descriptionsStarted) //$NON-NLS-1$
                + " thread=" + Thread.currentThread().getName()); //$NON-NLS-1$
        }
        // Объединение и отсев — после выравнивания мест и описаний: они адресуют поля по
        // индексам и кэшируют эти пути по форме полной секции.
        mergeSameTypeDefinitions(typeSection);
        if (existing != null && !dropDeclaredFields(typeSection, existing, methodLabel(context)))
            return;
        returnSection.addTypeSection(typeSection);
        if (existing == null)
            documentation.setReturnSection(returnSection);
    }

    /** Безусловная временная диагностика: ошибка fill раньше исчезала в catch разбора. */
    private static void fillTypeSection(TypeSection section, List<TypeItem> items, boolean russian,
        EObject context)
    {
        long started = System.nanoTime();
        boolean completed = false;
        Global.tempLog("bsl-return-fill", "native.begin target=" + methodLabel(context) //$NON-NLS-1$ //$NON-NLS-2$
            + " russian=" + russian); //$NON-NLS-1$
        try
        {
            section.fill(items, "", russian, context); //$NON-NLS-1$
            completed = true;
        }
        finally
        {
            Global.tempLog("bsl-return-fill", "native.end target=" + methodLabel(context) //$NON-NLS-1$ //$NON-NLS-2$
                + " completed=" + completed + " elapsedNs=" + (System.nanoTime() - started)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Тип возврата — часто несколько вариантов одного типа (структура из другой функции, затем
     * {@code Вставить} — ещё вариант). Прямой вызов «Функция().» показывает объединение их
     * полей, а при разборе секции EDT берёт из одноимённых определений первое — через «см.»
     * терялись поля (18.10: «Типрыыыы», «РеквизитыСЧасовымиПоясами»). Поля одноимённых
     * определений сводятся в первое, дубликаты определений удаляются.
     */
    private static void mergeSameTypeDefinitions(TypeSection section)
    {
        try
        {
            java.util.Map<String, TypeSection.TypeDefinition> first = new java.util.HashMap<>();
            for (var iterator = section.getTypeDefinitions().iterator(); iterator.hasNext();)
            {
                TypeSection.TypeDefinition definition = iterator.next();
                String type = definition.getTypeName();
                if (type == null || !definition.getContainTypes().isEmpty())
                    continue;
                TypeSection.TypeDefinition target = first.putIfAbsent(type.toLowerCase(java.util.Locale.ROOT), definition);
                if (target == null)
                    continue;
                java.util.Set<String> names = new java.util.HashSet<>();
                for (TypeSection.FieldDefinition field : target.getFieldDefinitionExtension())
                    if (field.getName() != null)
                        names.add(field.getName().toLowerCase(java.util.Locale.ROOT));
                for (TypeSection.FieldDefinition field : definition.getFieldDefinitionExtension())
                    if (field.getName() != null && names.add(field.getName().toLowerCase(java.util.Locale.ROOT)))
                        target.getFieldDefinitionExtension().add(field);
                iterator.remove();
            }
        }
        catch (UnsupportedOperationException e)
        {
            Global.tempLogException("bsl-declared-dedupe", "merge readonly", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Убирает из рассчитанной секции поля, которые объявленная секция возврата того же типа
     * уже описывает. EDT выводит тип каждого поля со ссылкой «см. …» отдельным обходом, и
     * множество посещённых методов копирует на каждое поле: повтор поля — повтор всего
     * поддерева ссылок. Повтор на каждом уровне удваивает ветвление (самоссылающееся
     * описание в БСП — {@code ОписаниеУзла} — давало десятки минут сборки). Новые типы и
     * новые поля остаются — слияние «по коду + по комментарию» не меняется.
     *
     * @return {@code false}, если в рассчитанной секции не осталось ничего нового
     */
    private static boolean dropDeclaredFields(TypeSection computed, ReturnSection declared, String label)
    {
        java.util.Map<String, java.util.Set<String>> declaredFields = new java.util.HashMap<>();
        for (TypeSection section : declared.getReturnTypes())
        {
            for (TypeSection.TypeDefinition definition : section.getTypeDefinitions())
            {
                String type = definition.getTypeName();
                if (type == null)
                    continue;
                java.util.Set<String> names = declaredFields.computeIfAbsent(
                    type.toLowerCase(java.util.Locale.ROOT), key -> new java.util.HashSet<>());
                for (TypeSection.FieldDefinition field : definition.getFieldDefinitionExtension())
                    if (field.getName() != null)
                        names.add(field.getName().toLowerCase(java.util.Locale.ROOT));
            }
        }
        int droppedFields = 0;
        int droppedTypes = 0;
        int keptFields = 0;
        try
        {
            var definitions = computed.getTypeDefinitions();
            for (var iterator = definitions.iterator(); iterator.hasNext();)
            {
                TypeSection.TypeDefinition definition = iterator.next();
                String type = definition.getTypeName();
                java.util.Set<String> names = type == null ? null
                    : declaredFields.get(type.toLowerCase(java.util.Locale.ROOT));
                if (names == null)
                {
                    keptFields += definition.getFieldDefinitionExtension().size();
                    continue;
                }
                var fields = definition.getFieldDefinitionExtension();
                int before = fields.size();
                fields.removeIf(field -> field.getName() != null
                    && names.contains(field.getName().toLowerCase(java.util.Locale.ROOT)));
                droppedFields += before - fields.size();
                keptFields += fields.size();
                // Тот же тип без новых полей ничего не добавляет к объявленному.
                if (fields.isEmpty() && definition.getContainTypes().isEmpty())
                {
                    iterator.remove();
                    droppedTypes++;
                }
            }
            // #region agent log
            Global.tempLog("bsl-declared-dedupe", "target=" + label //$NON-NLS-1$ //$NON-NLS-2$
                + " declaredTypes=" + declaredFields.keySet() //$NON-NLS-1$
                + " droppedFields=" + droppedFields + " droppedTypes=" + droppedTypes //$NON-NLS-1$ //$NON-NLS-2$
                + " keptTypes=" + definitions.size() + " keptFields=" + keptFields //$NON-NLS-1$ //$NON-NLS-2$
                + " thread=" + Thread.currentThread().getName()); //$NON-NLS-1$
            // #endregion
            return !definitions.isEmpty();
        }
        catch (UnsupportedOperationException e)
        {
            // Список EDT неизменяемый — оставляем секцию как есть.
            Global.tempLogException("bsl-declared-dedupe", "readonly target=" + label, e); //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }
    }

    /**
     * Текущий поток выполняет сборку: задание сборки EDT ({@code Builds.BuildProjectJob})
     * относится к семейству ручной сборки, автосборка Eclipse — к своему.
     */
    static boolean isBuildThread()
    {
        // Параллельную часть сборки штатный LightClusteringBuilderState ведёт в своём
        // ForkJoinPool: задания у этих потоков нет, узнаются они только по имени.
        if (Thread.currentThread().getName().startsWith("LCBuilderState")) //$NON-NLS-1$
            return true;
        Job job = Job.getJobManager().currentJob();
        return job != null && (job.belongsTo(ResourcesPlugin.FAMILY_MANUAL_BUILD)
            || job.belongsTo(ResourcesPlugin.FAMILY_AUTO_BUILD));
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

    /**
     * Запоминает рассчитанные EDT типы возврата всех функций модуля. Зовётся из
     * {@code ComfortTreeTypeSystem.installTypeSystem} сразу после штатного полного расчёта —
     * только чтение готовых состояний, без нового расчёта. Пустой результат удаляет запись:
     * функция могла перестать что-либо возвращать.
     *
     * @return имена (в нижнем регистре) собственных функций модуля, чей тип только что
     *     изменился и на которые модуль ссылается «см.»: эти ссылки EDT разрешил внутри того же
     *     расчёта, до записи, — без слияния, и ссылающиеся методы надо пересчитать. Пусто —
     *     пересчитывать нечего.
     */
    static java.util.Set<String> rememberModuleReturnTypes(com._1c.g5.v8.dt.bsl.model.Module module)
    {
        if (module == null || module.eIsProxy() || !isExtendedTypesEnabled(resolveProject(module)))
            return java.util.Set.of();
        long started = System.nanoTime();
        boolean build = isBuildThread();
        int functions = 0;
        int remembered = 0;
        java.util.Set<String> changed = new java.util.HashSet<>();
        // #region agent log
        List<String> changedKeys = new ArrayList<>();
        // #endregion
        boolean selfReferenced = false;
        try
        {
            Resource resource = module.eResource();
            IResourceServiceProvider rsp = resource == null || resource.getURI() == null ? null
                : IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(resource.getURI());
            Object computer = rsp == null ? null
                : rsp.get(Class.forName(TYPES_COMPUTER, true, bslClassLoader()));
            if (computer == null)
                return java.util.Set.of();
            for (Method method : module.allMethods())
            {
                if (!isFunction(method))
                    continue;
                functions++;
                String label = methodLabel(method);
                String key = versionKey(label, method);
                CachedReturn previous = typeCacheByKey.get(key);
                List<?> types;
                remembering.set(Boolean.TRUE);
                try
                {
                    types = readyReturnTypes(computer, method);
                }
                finally
                {
                    remembering.remove();
                }
                // Пусто — не запоминаем и не считаем изменением: пустота бывает от недосчёта, а
                // запись привязана к тексту функции, так что для другого текста она и так не найдётся.
                if (types == null || types.isEmpty())
                    continue;
                String signature = typeSignature(types);
                long hash = signatureHash(signature);
                // Изменение — новая версия текста или смена самого типа при том же тексте: тип
                // зависит и от вызываемых функций (правка ключа в «Яйцо» меняет тип «Новый123…»).
                // Ложная смена теперь стоит только ленивого пересчёта одного метода при
                // автодополнении, а не второго прохода по всем ссылающимся (было 29 с, 18.10 02:13).
                // Сравнение — только со своей стороной (сборка или редактор), см. CachedReturn.
                if ((previous == null || previous.hash(build) != hash) && method.getName() != null)
                {
                    changed.add(method.getName().toLowerCase(java.util.Locale.ROOT));
                    // #region agent log
                    if (changedKeys.size() < 10)
                        changedKeys.add(key.substring(key.lastIndexOf('#')) + "/versions=" //$NON-NLS-1$
                            + (versionsByLabel.containsKey(label) ? versionsByLabel.get(label).size() : 0)
                            + (changedKeys.size() < 3 ? " type=" + signature : "")); //$NON-NLS-1$ //$NON-NLS-2$
                    // #endregion
                }
                putVersion(label, key, CachedReturn.with(previous, types, build, hash));
                remembered++;
            }
            if (!changed.isEmpty())
            {
                var node = org.eclipse.xtext.nodemodel.util.NodeModelUtils.getNode(module);
                selfReferenced = node != null && referencesAny(node.getText(), changed);
                // Тип функции сменился — запасы членов автодополнения у точек могут держать прежний.
                bumpTypesGeneration();
            }
            // #region agent log
            logTypeStates(module);
            // #endregion
        }
        catch (Throwable t)
        {
            Global.tempLogException("bsl-return-compute-cost", "remember failed module=" //$NON-NLS-1$ //$NON-NLS-2$
                + (module.eResource() == null ? "?" : module.eResource().getURI()), t); //$NON-NLS-1$
        }
        finally
        {
            // #region agent log
            Global.tempLog("bsl-return-compute-cost", "remember module=" //$NON-NLS-1$ //$NON-NLS-2$
                + (module.eResource() == null ? "?" : module.eResource().getURI()) //$NON-NLS-1$
                + " functions=" + functions + " remembered=" + remembered //$NON-NLS-1$ //$NON-NLS-2$
                + " changed=" + changed.size() + " selfReferenced=" + selfReferenced //$NON-NLS-1$ //$NON-NLS-2$
                + " changedNames=" + changed.stream().limit(10).toList() //$NON-NLS-1$
                + " changedKeys=" + changedKeys //$NON-NLS-1$
                + " resourceSet=" + (module.eResource() == null || module.eResource().getResourceSet() == null ? "null" //$NON-NLS-1$ //$NON-NLS-2$
                    : Integer.toHexString(System.identityHashCode(module.eResource().getResourceSet())))
                + " elapsedNs=" + (System.nanoTime() - started) //$NON-NLS-1$
                + " thread=" + Thread.currentThread().getName()); //$NON-NLS-1$
            // #endregion
        }
        return selfReferenced ? changed : java.util.Set.of();
    }

    /**
     * Методы модуля, в тексте которых (документирующий комментарий над методом и тело с
     * боковыми комментариями) есть «см. …» на одну из {@code names}. Именно их тип зависит от
     * слитого типа изменившейся функции, а штатный частичный расчёт их не пересчитывает: он
     * берёт только вызывающих.
     */
    /**
     * Ссылается ли метод «см.» на одну из {@code names}: текст узла метода вместе с прилегающими
     * скрытыми (документирующий комментарий над ним) и телом. Без текста всего модуля — это
     * проверка на каждый вызов автодополнения.
     */
    static boolean methodReferences(Method method, java.util.Set<String> names)
    {
        var node = org.eclipse.xtext.nodemodel.util.NodeModelUtils.getNode(method);
        return node != null && !names.isEmpty() && referencesAny(node.getText(), names);
    }

    static List<Method> methodsReferencing(com._1c.g5.v8.dt.bsl.model.Module module, java.util.Set<String> names)
    {
        List<Method> result = new ArrayList<>();
        var root = org.eclipse.xtext.nodemodel.util.NodeModelUtils.getNode(module);
        if (root == null || names.isEmpty())
            return result;
        String text = root.getText();
        for (Method method : module.allMethods())
        {
            var node = org.eclipse.xtext.nodemodel.util.NodeModelUtils.getNode(method);
            if (node == null)
                continue;
            int end = Math.min(text.length(), node.getTotalEndOffset());
            int start = commentBlockStart(text, Math.min(node.getOffset(), end));
            if (start < end && referencesAny(text.substring(start, end), names))
                result.add(method);
        }
        return result;
    }

    /** Начало строк «//» сразу над позицией {@code offset} (документирующий комментарий метода). */
    private static int commentBlockStart(String text, int offset)
    {
        int lineStart = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int start = lineStart;
        while (start > 0)
        {
            int previousEnd = start - 1;
            int previousStart = text.lastIndexOf('\n', Math.max(0, previousEnd - 1)) + 1;
            if (previousStart >= previousEnd || !text.substring(previousStart, previousEnd).trim().startsWith("//")) //$NON-NLS-1$
                break;
            start = previousStart;
        }
        return start;
    }

    // #region agent log
    /** Временно: сигнатуры возврата функций и NORMAL/LIGHT параметров методов небольшого модуля. */
    static void logTypeStates(com._1c.g5.v8.dt.bsl.model.Module module)
    {
        try
        {
            if (module == null || module.allMethods().size() > 50)
                return;
            Resource resource = module.eResource();
            for (Method method : module.allMethods())
            {
                Object envs = BslDocCommentDescriptionFix.invokeCommentGetter(method, "environments"); //$NON-NLS-1$
                StringBuilder line = new StringBuilder("method=").append(method.getName()) //$NON-NLS-1$
                    .append(" methodId=").append(Integer.toHexString(System.identityHashCode(method))) //$NON-NLS-1$
                    .append(" resourceId=").append(Integer.toHexString(System.identityHashCode(resource))) //$NON-NLS-1$
                    .append(" resourceSetId=").append(resource == null ? "null" //$NON-NLS-1$ //$NON-NLS-2$
                        : Integer.toHexString(System.identityHashCode(resource.getResourceSet())));
                CachedReturn cached = typeCacheByKey.get(versionKey(methodLabel(method), method));
                if (cached != null)
                    line.append(" cached=").append(signatureOrNull(cached.types())) //$NON-NLS-1$
                        .append(" buildHash=").append(Long.toHexString(cached.buildHash())) //$NON-NLS-1$
                        .append(" editorHash=").append(Long.toHexString(cached.editorHash())); //$NON-NLS-1$
                Object returnState = BslDocCommentDescriptionFix.invokeCommentGetter(method, "getFinalReturnState"); //$NON-NLS-1$
                if (returnState != null)
                    line.append(" return{NORMAL=") //$NON-NLS-1$
                        .append(signatureOrNull(typesFromReturnProvider(returnState, "NORMAL", envs))) //$NON-NLS-1$
                        .append(" LIGHT=") //$NON-NLS-1$
                        .append(signatureOrNull(typesFromReturnProvider(returnState, "LIGHT", envs))) //$NON-NLS-1$
                        .append('}');
                for (var param : method.getFormalParams())
                {
                    line.append(" var:").append(param.getName()).append("{NORMAL=") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(signatureOrNull(typesFromReturnProvider(param.getTypeStateProvider(), "NORMAL", envs))) //$NON-NLS-1$
                        .append(" LIGHT=") //$NON-NLS-1$
                        .append(signatureOrNull(typesFromReturnProvider(param.getTypeStateProvider(), "LIGHT", envs))) //$NON-NLS-1$
                        .append('}');
                }
                int index = 0;
                for (Object collector : method.getFinalInParamState())
                {
                    line.append(" param").append(index++).append("{NORMAL=") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(signatureOrNull(typesFromReturnProvider(collector, "NORMAL", envs))) //$NON-NLS-1$
                        .append(" LIGHT=") //$NON-NLS-1$
                        .append(signatureOrNull(typesFromReturnProvider(collector, "LIGHT", envs))) //$NON-NLS-1$
                        .append('}');
                }
                Global.tempLog("bsl-type-states", line.append(" thread=") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(Thread.currentThread().getName()).toString());
            }
        }
        catch (Throwable t)
        {
            Global.tempLogException("bsl-type-states", "log failed", t); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static String signatureOrNull(List<?> types)
    {
        return types == null ? "none" : "[" + typeSignature(types) + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
    // #endregion

    /** Имена типов и их свойств — чтобы сравнивать тип по сути, а не по объектам. */
    /**
     * Без учёта порядка: имя типа и отсортированные поля, варианты — тоже по порядку имён.
     * Порядок вариантов между расчётами разный, и от него сигнатура «плавала».
     */
    private static String typeSignature(List<?> types)
    {
        java.util.TreeSet<String> parts = new java.util.TreeSet<>();
        for (Object item : types)
        {
            if (!(item instanceof TypeItem type))
                continue;
            java.util.TreeSet<String> fields = new java.util.TreeSet<>();
            if (type instanceof com._1c.g5.v8.dt.mcore.Type withContext && !withContext.eIsProxy())
            {
                ContextDef contextDef = withContext.getContextDef();
                // allProperties — как в TypeSection.fill: структура из вызванной функции лежит в
                // родительской части, и её ключи getProperties не видел (правка ключа в «Яйцо» не
                // меняла сигнатуру «Новый123…», 18.10 09:32).
                if (contextDef != null && !contextDef.eIsProxy() && contextDef.allProperties() != null)
                    for (Object property : contextDef.allProperties())
                        fields.add(String.valueOf(Global.invoke(property, "getName"))); //$NON-NLS-1$
            }
            parts.add(Global.invoke(type, "getName") + "{" + String.join(",", fields) + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return String.join("", parts); //$NON-NLS-1$
    }

    /**
     * Есть ли в тексте модуля «см. …» с последним сегментом из {@code names} (в нижнем регистре):
     * {@code см. Функция} или {@code см. ОбщийМодуль.Функция}.
     */
    private static boolean referencesAny(String text, java.util.Set<String> names)
    {
        if (text == null || names.isEmpty())
            return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        int from = 0;
        while ((from = lower.indexOf("см.", from)) >= 0) //$NON-NLS-1$
        {
            int index = from + 3;
            while (index < lower.length() && lower.charAt(index) == ' ')
                index++;
            int start = index;
            while (index < lower.length() && (Character.isLetterOrDigit(lower.charAt(index))
                || lower.charAt(index) == '_' || lower.charAt(index) == '.'))
                index++;
            String chain = lower.substring(start, index);
            if (chain.endsWith(".")) //$NON-NLS-1$
                chain = chain.substring(0, chain.length() - 1);
            if (names.contains(chain.substring(chain.lastIndexOf('.') + 1)))
                return true;
            from = Math.max(index, from + 3);
        }
        return false;
    }

    /**
     * Готовые типы возврата функции: {@code TypesComputer.computeTypes}, затем NORMAL/LIGHT из
     * {@code getFinalReturnState} (TypesComputer может выбрать пустой LIGHT при готовом NORMAL).
     */
    private static List<?> readyReturnTypes(Object computer, EObject function)
    {
        Object envs = BslDocCommentDescriptionFix.invokeCommentGetter(function, "environments"); //$NON-NLS-1$
        if (envs == null)
            return null;
        // Объединение штатного TypesComputer и всех вариантов NORMAL. TypesComputer схлопывает
        // одноимённые варианты «Структура» (дедупликация по имени и URI) и отдаёт один — без
        // структуры из вызванной функции; NORMAL без него терял поля, которые он видит (18.10:
        // «РеквизитыСЧасовымиПоясами»). Поля одноимённых вариантов потом сводит
        // mergeSameTypeDefinitions. Старый тип в LIGHT переразобранной функции снимает
        // ComfortTreeTypeSystem.dropStaleLightReturn.
        java.util.Set<Object> union = new java.util.LinkedHashSet<>();
        List<?> computed = asTypeList(Global.invoke(computer, "computeTypes", function, envs)); //$NON-NLS-1$
        if (computed != null)
            union.addAll(computed);
        try
        {
            Object collector = BslDocCommentDescriptionFix.invokeCommentGetter(function, "getFinalReturnState"); //$NON-NLS-1$
            List<?> normal = collector == null ? null : typesFromReturnProvider(collector, "NORMAL", envs); //$NON-NLS-1$
            if (normal != null)
                union.addAll(normal);
        }
        catch (Exception ignored)
        {
        }
        return union.isEmpty() ? null : new ArrayList<>(union);
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
            Object collector = BslDocCommentDescriptionFix.invokeCommentGetter(target, "getFinalReturnState"); //$NON-NLS-1$
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
            Object all = BslDocCommentDescriptionFix.invokeCommentGetter(provider, "getLastState"); //$NON-NLS-1$
            if (all instanceof List<?> states && !states.isEmpty())
            {
                List<Object> types = new ArrayList<>();
                for (Object state : states)
                {
                    Object iterable = state == null ? null : BslDocCommentDescriptionFix.invokeCommentGetter(state, "getTypes"); //$NON-NLS-1$
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
                Object iterable = state == null ? null : BslDocCommentDescriptionFix.invokeCommentGetter(state, "getTypes"); //$NON-NLS-1$
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
        Object typesObj = BslDocCommentDescriptionFix.invokeCommentGetter(last, "getTypes"); //$NON-NLS-1$
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

    /** Слабые ключи не удерживают модель при отмене расчёта посреди оператора. */
    private static final java.util.Map<Object, java.util.Map<Object, Boolean>> ASSIGNED_COLUMNS =
        new java.util.WeakHashMap<>();

    /** Наблюдаем только присваивания результата добавления колонки, без проверки версии EDT. */
    public static void beforeColumnStatement(Object creator, Object statement)
    {
        if (statement instanceof com._1c.g5.v8.dt.bsl.model.SimpleStatement simple
                && simple.getLeft() instanceof com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess
                && simple.getRight() instanceof com._1c.g5.v8.dt.bsl.model.Invocation invocation
                && invocation.getMethodAccess() instanceof com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess access
                && access.getSource() instanceof com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess collection
                && ("Колонки".equalsIgnoreCase(collection.getName()) || "Columns".equalsIgnoreCase(collection.getName())) //$NON-NLS-1$ //$NON-NLS-2$
                && ("Добавить".equalsIgnoreCase(access.getName()) || "Add".equalsIgnoreCase(access.getName()) //$NON-NLS-1$ //$NON-NLS-2$
                    || "Вставить".equalsIgnoreCase(access.getName()) || "Insert".equalsIgnoreCase(access.getName())) //$NON-NLS-1$ //$NON-NLS-2$
                && isExtendedTypesEnabled(invocation))
        {
            synchronized (ASSIGNED_COLUMNS)
            {
                ASSIGNED_COLUMNS.computeIfAbsent(creator, ignored -> new java.util.WeakHashMap<>())
                    .put(invocation, Boolean.FALSE);
            }
        }
    }

    /** EDT сама выполнила штатный шаг для этого же вызова в текущем операторе. */
    public static void columnExpansionProcessed(Object creator, Object invocation)
    {
        synchronized (ASSIGNED_COLUMNS)
        {
            var calls = ASSIGNED_COLUMNS.get(creator);
            if (calls == null || !calls.containsKey(invocation))
                return;
            calls.put(invocation, Boolean.TRUE);
        }
    }

    /** После всего оператора: восполняем только пропущенный EDT штатный шаг. */
    public static void afterColumnStatement(Object creator, Object statement)
    {
        if (!(statement instanceof com._1c.g5.v8.dt.bsl.model.SimpleStatement simple)
            || !(simple.getRight() instanceof com._1c.g5.v8.dt.bsl.model.Invocation))
            return;
        Boolean nativeProcessed;
        synchronized (ASSIGNED_COLUMNS)
        {
            var calls = ASSIGNED_COLUMNS.get(creator);
            nativeProcessed = calls == null ? null : calls.remove(simple.getRight());
            if (calls != null && calls.isEmpty())
                ASSIGNED_COLUMNS.remove(creator);
        }
        if (nativeProcessed == null)
            return;
        try
        {
            // Имя и параметры подтверждены CreatorTreeState.javap.txt в кэше бандла bsl-full.
            if (!nativeProcessed && !Global.invokeVoid(creator,
                "checkForExpandingStructureTypeContextDef", simple.getRight())) //$NON-NLS-1$
                throw new IllegalStateException("Column expansion method unavailable"); //$NON-NLS-1$
        }
        catch (Throwable failure)
        {
        }
    }

    /**
     * Инструментализация {@code CreatorTreeState}: в хвост
     * {@code isReplaceTypesByDocumentationComment} добавляется наш ответ, который умеет
     * отменить замещение при знаке {@code ^} в боковом комментарии.
     * В обработке SimpleStatement наблюдаем штатное расширение колонок и восполняем пропуск.
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
                if (mv != null && "doInternalProcess".equals(name) //$NON-NLS-1$
                    && descriptor.startsWith("(Lcom/_1c/g5/v8/dt/bsl/model/SimpleStatement;") //$NON-NLS-1$
                    && descriptor.endsWith(")V")) //$NON-NLS-1$
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitVarInsn(Opcodes.ALOAD, 1);
                            visitMethodInsn(Opcodes.INVOKESTATIC, SELF_INTERNAL, "beforeColumnStatement", //$NON-NLS-1$
                                "(Ljava/lang/Object;Ljava/lang/Object;)V", false); //$NON-NLS-1$
                        }

                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.RETURN)
                            {
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitVarInsn(Opcodes.ALOAD, 1);
                                visitMethodInsn(Opcodes.INVOKESTATIC, SELF_INTERNAL, "afterColumnStatement", //$NON-NLS-1$
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false); //$NON-NLS-1$
                                touched.set(true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                if (mv != null && "checkForExpandingStructureTypeContextDef".equals(name) //$NON-NLS-1$
                    && descriptor.equals("(Lcom/_1c/g5/v8/dt/bsl/model/Invocation;)V")) //$NON-NLS-1$
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.RETURN)
                            {
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitVarInsn(Opcodes.ALOAD, 1);
                                visitMethodInsn(Opcodes.INVOKESTATIC, SELF_INTERNAL, "columnExpansionProcessed", //$NON-NLS-1$
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false); //$NON-NLS-1$
                                touched.set(true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
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
