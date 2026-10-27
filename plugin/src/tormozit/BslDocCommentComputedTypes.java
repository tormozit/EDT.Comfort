// BslDocCommentComputedTypes.java
package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
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
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.naming.QualifiedName;
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
import com._1c.g5.v8.dt.bsl.model.Block;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.OperatorStyleCreator;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com._1c.g5.v8.dt.bsl.model.resource.owner.IBslOwnerComputerService;
import com._1c.g5.v8.dt.bsl.model.typesytem.TypeSystemMode;
import com._1c.g5.v8.dt.bsl.model.typesytem.VariableTreeTypeState;
import com._1c.g5.v8.dt.bsl.model.typesytem.VariableTreeTypeStateWithSubStates;
import com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer;
import com._1c.g5.v8.dt.bsl.resource.BslResource;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.bsl.typesystem.InvocationTypesComputerExtensionRegistry;
import com._1c.g5.v8.dt.bsl.typesystem.util.CreatorTreeState;
import com._1c.g5.v8.dt.bsl.typesystem.util.TypeSystemUtil;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environment;
import com._1c.g5.v8.dt.mcore.util.Environments;

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
 * <b>Слияние с документирующим комментарием зависит от флажка проекта.</b>
 * {@link #afterParseTemplateComment} первым делом проверяет флажок и при выключенном
 * возвращает разобранный комментарий как есть. Исправление начального состояния
 * неявных переменных от флажка не зависит.
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
        FunctionReturnTypes.computedTypesChanged();
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
            if (seedCalleeReturnFromPartialParse(comment, methodObject))
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
            if (computed != null && !computed.isEmpty())
                fillReturnSection(comment, computed, methodObject);
        }
        catch (Throwable ignored)
        {
        }
        return comment;
    }

    /**
     * Обход дефекта EDT (bsl 28.0.1, сборка 2026-07; без плагина тоже): частичный расчёт NORMAL
     * заново заводит состояние возврата не только у переразобранного метода и его вызывающих,
     * но и у вызываемых им функций ({@code PartialParseResult.getCalleesMethod}), а тела вызываемых
     * не пересчитывает. Начальный тип возврата в NORMAL берётся только из комментария
     * ({@code computeReturnTypes}) — у функции без секции возврата он пуст, и после
     * «Функция(…).» список членов пуст до полного расчёта модуля.
     * <p>
     * Запомненные EDT «прежние типы вызываемых» ({@code getCalleeMethodReturnType}) брать
     * нельзя: {@code BslPartialParsingHelper.addTypesInformation} считает их не по вызываемой
     * функции, а по переразобранному методу ({@code computeTypes(aload_2, …)}) — у вызываемой
     * всплывал тип возврата вызывающего (20.10: «ОбсуждениеСистемыВзаимодействия» у функции,
     * возвращающей пользователя). Собственный NORMAL-тип вызываемой снимается перед штатным
     * расчётом ({@link #captureCalleeReturns}) и дописывается секцией возврата, когда
     * {@code initFinalStateForMethod} разбирает её комментарий. Своя секция возврата у
     * функции — не трогаем.
     *
     * @return {@code true}, если секция дописана
     */
    private static boolean seedCalleeReturnFromPartialParse(Object comment, EObject method)
    {
        java.util.Map<EObject, List<?>> captured = CALLEE_RETURNS.get();
        if (captured == null || !captured.containsKey(method))
            return false;
        if (!(comment instanceof BslDocumentationComment documentation) || documentation.getReturnSection() != null)
            return false;
        Resource resource = method.eResource();
        Object parse = resource == null ? null : Global.invoke(resource, "getParseResult"); //$NON-NLS-1$
        if (parse == null || !parse.getClass().getName().endsWith(".PartialParseResult")) //$NON-NLS-1$
            return false;
        if (Global.invoke(parse, "getReparseMethod") == method) //$NON-NLS-1$
            return false;
        if (!(Global.invoke(parse, "getCalleesMethod") instanceof java.util.Collection<?> callees) //$NON-NLS-1$
            || !callees.contains(method))
            return false;
        if (!initializingFinalState())
            return false;
        try
        {
            fillReturnSection(comment, new ArrayList<>(captured.get(method)), method);
            return documentation.getReturnSection() != null;
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    /** Собственные NORMAL-типы возврата вызываемых функций, снятые до штатного частичного расчёта. */
    private static final ThreadLocal<java.util.Map<EObject, List<?>>> CALLEE_RETURNS = new ThreadLocal<>();

    /**
     * Перед штатным {@code installTypeSystem}: при частичном разборе снять NORMAL-тип возврата
     * каждой вызываемой функции ({@code PartialParseResult.getCalleesMethod}), пока EDT его не
     * сбросила. Пара — {@link #releaseCalleeReturns}.
     *
     * @return прежнее значение для восстановления (вложенные расчёты)
     */
    static java.util.Map<EObject, List<?>> captureCalleeReturns(EObject module)
    {
        java.util.Map<EObject, List<?>> previous = CALLEE_RETURNS.get();
        java.util.Map<EObject, List<?>> captured = new java.util.IdentityHashMap<>();
        try
        {
            Resource resource = module == null ? null : module.eResource();
            Object parse = resource == null ? null : Global.invoke(resource, "getParseResult"); //$NON-NLS-1$
            if (parse != null && parse.getClass().getName().endsWith(".PartialParseResult") //$NON-NLS-1$
                && Global.invoke(parse, "getCalleesMethod") instanceof java.util.Collection<?> callees) //$NON-NLS-1$
            {
                for (Object callee : new ArrayList<>(callees))
                {
                    if (!(callee instanceof EObject function) || !isFunction(function))
                        continue;
                    Object envs = BslDocCommentDescriptionFix.invokeCommentGetter(function, "environments"); //$NON-NLS-1$
                    Object collector = BslDocCommentDescriptionFix.invokeCommentGetter(function, "getFinalReturnState"); //$NON-NLS-1$
                    List<?> types = collector == null ? null : typesFromReturnProvider(collector, "NORMAL", envs); //$NON-NLS-1$
                    if (types != null && !types.isEmpty())
                        captured.put(function, new ArrayList<>(types));
                }
            }
        }
        catch (Throwable ignored)
        {
        }
        CALLEE_RETURNS.set(captured);
        return previous;
    }

    /** Пара к {@link #captureCalleeReturns}. */
    static void releaseCalleeReturns(java.util.Map<EObject, List<?>> previous)
    {
        if (previous == null)
            CALLEE_RETURNS.remove();
        else
            CALLEE_RETURNS.set(previous);
    }

    /**
     * Разбор комментария идёт из {@code BslTreeTypeSystem.initFinalStateForMethod} полного
     * ({@code installTypeSystem}), а не облегчённого расчёта: в LIGHT EDT сама начинает
     * с прежних типов вызываемой.
     */
    private static boolean initializingFinalState()
    {
        String owner = "com._1c.g5.v8.dt.bsl.typesystem.BslTreeTypeSystem"; //$NON-NLS-1$
        boolean[] seen = new boolean[2]; // [0] initFinalStateForMethod, [1] lightInstallingTypeSystem
        StackWalker.getInstance().forEach(frame ->
        {
            if (!owner.equals(frame.getClassName()))
                return;
            if ("initFinalStateForMethod".equals(frame.getMethodName())) //$NON-NLS-1$
                seen[0] = true;
            else if ("lightInstallingTypeSystem".equals(frame.getMethodName())) //$NON-NLS-1$
                seen[1] = true;
        });
        return seen[0] && !seen[1];
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
    private static void fillReturnSection(Object comment, List<?> computed, EObject context) throws Exception
    {
        if (!(comment instanceof BslDocumentationComment documentation))
            return;
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
        if (isCyclicOrHuge(items))
            return;
        ReturnSection returnSection = existing != null ? existing : new ReturnSection(documentation, 0);
        TypeSection typeSection = new TypeSection(returnSection, 0);

        typeSection.fill(items, "", true, context); //$NON-NLS-1$
        int count = typeSection.getTypeDefinitions().size();
        if (count == 0)
        {
            typeSection.fill(items, "", false, context); //$NON-NLS-1$
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
        // Описания полей — только текст для подсказок. Комментарий, разобранный сборкой, в
        // подсказки не попадает (EDT разбирает его заново на каждое обращение), а сборка
        // держит блокировку проекта, и первое нажатие клавиши после сохранения ждёт её конца.
        boolean inBuild = isBuildThread();
        try
        {
            if (!inBuild)
                BslStructureInsertCommentTypes.fillPropertyDescriptions(typeSection, items,
                    computed, methodLabel(context), context);
        }
        catch (Throwable ignored)
        {
        }
        // Объединение и отсев — после выравнивания мест и описаний: они адресуют поля по
        // индексам и кэшируют эти пути по форме полной секции.
        mergeSameTypeDefinitions(typeSection);
        if (existing != null && !dropDeclaredFields(typeSection, existing))
            return;
        returnSection.addTypeSection(typeSection);
        if (existing == null)
            documentation.setReturnSection(returnSection);
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
            // Список EDT неизменяемый — оставляем секцию как есть.
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
    private static boolean dropDeclaredFields(TypeSection computed, ReturnSection declared)
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
                    continue;
                var fields = definition.getFieldDefinitionExtension();
                fields.removeIf(field -> field.getName() != null
                    && names.contains(field.getName().toLowerCase(java.util.Locale.ROOT)));
                // Тот же тип без новых полей ничего не добавляет к объявленному.
                if (fields.isEmpty() && definition.getContainTypes().isEmpty())
                    iterator.remove();
            }
            return !definitions.isEmpty();
        }
        catch (UnsupportedOperationException e)
        {
            // Список EDT неизменяемый — оставляем секцию как есть.
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
        boolean build = isBuildThread();
        java.util.Set<String> changed = new java.util.HashSet<>();
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
                {
                    continue;
                }
                long hash = signatureHash(typeSignature(types));
                // Изменение — новая версия текста или смена самого типа при том же тексте: тип
                // зависит и от вызываемых функций (правка ключа в «Яйцо» меняет тип «Новый123…»).
                // Ложная смена теперь стоит только ленивого пересчёта одного метода при
                // автодополнении, а не второго прохода по всем ссылающимся (было 29 с, 18.10 02:13).
                // Сравнение — только со своей стороной (сборка или редактор), см. CachedReturn.
                if ((previous == null || previous.hash(build) != hash) && method.getName() != null)
                    changed.add(method.getName().toLowerCase(java.util.Locale.ROOT));
                putVersion(label, key, CachedReturn.with(previous, types, build, hash));
            }
            if (!changed.isEmpty())
            {
                var node = org.eclipse.xtext.nodemodel.util.NodeModelUtils.getNode(module);
                selfReferenced = node != null && referencesAny(node.getText(), changed);
                // Тип функции сменился — запасы членов автодополнения у точек могут держать прежний.
                bumpTypesGeneration();
            }
        }
        catch (Throwable ignored)
        {
            // Запомнить не удалось — слияние для этого расчёта останется без новых типов.
        }
        return selfReferenced ? changed : java.util.Set.of();
    }


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

    /**
     * Методы модуля, в тексте которых (документирующий комментарий над методом и тело с
     * боковыми комментариями) есть «см. …» на одну из {@code names}. Именно их тип зависит от
     * слитого типа изменившейся функции, а штатный частичный расчёт их не пересчитывает: он
     * берёт только вызывающих.
     */
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

    /**
     * Имена типов и их свойств — чтобы сравнивать тип по сути, а не по объектам.
     * <p>
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

    /**
     * Только уже рассчитанные EDT типы возврата функции — последнее состояние NORMAL
     * {@code getFinalReturnState}, без {@code TypesComputer} и любого досчёта; {@code null} —
     * готового состояния нет. Для чужой модели под её блокировкой ({@link FunctionReturnTypes}).
     */
    static List<?> readyNormalReturnTypes(EObject function)
    {
        try
        {
            Object envs = BslDocCommentDescriptionFix.invokeCommentGetter(function, "environments"); //$NON-NLS-1$
            Object collector = BslDocCommentDescriptionFix.invokeCommentGetter(function, "getFinalReturnState"); //$NON-NLS-1$
            return envs == null || collector == null ? null : typesFromReturnProvider(collector, "NORMAL", envs); //$NON-NLS-1$
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

    // #region agent log
    /**
     * Диагностика: полный снимок NORMAL возврата функции — каждое последнее состояние,
     * словарь его подсостояний по средам ({@code subStates}), у каждого подсостояния
     * объект, число хранимых типов ({@code typesState}), число типов после фильтра
     * ({@code getTypes}) и фильтры {@code skipTypes}/{@code allowedTypes}.
     */
    static String diagReturnStateDeep(EObject function)
    {
        StringBuilder sb = new StringBuilder("{");
        try
        {
            Object envs = BslDocCommentDescriptionFix.invokeCommentGetter(function, "environments"); //$NON-NLS-1$
            sb.append("envs=").append(envs); //$NON-NLS-1$
            if (function instanceof Method method)
            {
                sb.append(" callers=["); //$NON-NLS-1$
                for (Object caller : new ArrayList<>(method.getCallers()))
                {
                    sb.append(caller instanceof Method m ? m.getName() : String.valueOf(caller));
                    if (caller instanceof EObject eo && eo.eResource() != null && eo.eResource() != function.eResource())
                        sb.append("@OTHER:").append(eo.eResource().getURI()); //$NON-NLS-1$
                    sb.append(',');
                }
                sb.append(']');
            }
            Object collector = BslDocCommentDescriptionFix.invokeCommentGetter(function, "getFinalReturnState"); //$NON-NLS-1$
            if (collector == null)
                return sb.append(" collector=null}").toString(); //$NON-NLS-1$
            Class<?> modeClass = Class.forName(
                "com._1c.g5.v8.dt.bsl.model.typesytem.TypeSystemMode", true, bslClassLoader()); //$NON-NLS-1$
            @SuppressWarnings({ "unchecked", "rawtypes" })
            Object normal = Enum.valueOf((Class<Enum>)modeClass.asSubclass(Enum.class), "NORMAL"); //$NON-NLS-1$
            Object provider = Global.invoke(collector, "get", normal); //$NON-NLS-1$
            if (provider == null)
                return sb.append(" NORMAL=null}").toString(); //$NON-NLS-1$
            Object all = BslDocCommentDescriptionFix.invokeCommentGetter(provider, "getLastState"); //$NON-NLS-1$
            sb.append(" provider@").append(diagId(provider)); //$NON-NLS-1$
            if (!(all instanceof List<?> states))
                return sb.append(" lastState=").append(all).append('}').toString(); //$NON-NLS-1$
            int i = 0;
            for (Object state : states)
            {
                sb.append(" last[").append(i++).append("]=").append(diagStateBrief(state)); //$NON-NLS-1$ //$NON-NLS-2$
                Object map = diagField(state, "subStates"); //$NON-NLS-1$
                if (map instanceof java.util.Map<?, ?> subStates)
                {
                    sb.append(" subStates#").append(subStates.size()).append('{'); //$NON-NLS-1$
                    for (java.util.Map.Entry<?, ?> entry : new ArrayList<>(subStates.entrySet()))
                        sb.append(entry.getKey()).append("->").append(diagStateBrief(entry.getValue())).append("; "); //$NON-NLS-1$ //$NON-NLS-2$
                    sb.append('}');
                }
                Object picked = Global.invoke(state, "getSubStates", envs); //$NON-NLS-1$
                if (picked instanceof List<?> list)
                {
                    sb.append(" getSubStates(envs)=["); //$NON-NLS-1$
                    for (Object s : list)
                        sb.append('@').append(diagId(s)).append(' ');
                    sb.append(']');
                }
            }
        }
        catch (Throwable e)
        {
            sb.append(" ERR:").append(e); //$NON-NLS-1$
        }
        return sb.append('}').toString();
    }

    private static String diagStateBrief(Object state)
    {
        if (state == null)
            return "null"; //$NON-NLS-1$
        Object stored = diagField(state, "typesState"); //$NON-NLS-1$
        Object skip = diagField(state, "skipTypes"); //$NON-NLS-1$
        Object allowed = diagField(state, "allowedTypes"); //$NON-NLS-1$
        int visible = -1;
        try
        {
            Object types = Global.invoke(state, "getTypes"); //$NON-NLS-1$
            if (types instanceof Iterable<?> it)
            {
                visible = 0;
                for (Object ignored : it)
                    visible++;
            }
        }
        catch (Throwable ignored)
        {
        }
        StringBuilder names = new StringBuilder();
        if (stored instanceof java.util.Collection<?> c)
            for (Object t : new ArrayList<>(c))
                names.append(t instanceof TypeItem ti ? com._1c.g5.v8.dt.mcore.util.McoreUtil.getTypeName(ti) : String.valueOf(t))
                    .append(',');
        return state.getClass().getSimpleName() + "@" + diagId(state) //$NON-NLS-1$
            + "(stored=" + (stored instanceof java.util.Collection<?> c ? c.size() : -1) //$NON-NLS-1$
            + "[" + names + "] visible=" + visible //$NON-NLS-1$ //$NON-NLS-2$
            + " skip=" + skip + " allowed=" + allowed + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static Object diagField(Object target, String name)
    {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass())
        {
            try
            {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            }
            catch (NoSuchFieldException e)
            {
                // выше по иерархии
            }
            catch (Throwable e)
            {
                return "ERR:" + e; //$NON-NLS-1$
            }
        }
        return "noField"; //$NON-NLS-1$
    }

    private static String diagId(Object o)
    {
        return Integer.toHexString(System.identityHashCode(o));
    }

    /** Диагностика: имена функций модуля, у которых в NORMAL возврата есть типы. */
    static java.util.Set<String> diagTypedReturnFunctions(com._1c.g5.v8.dt.bsl.model.Module module)
    {
        java.util.Set<String> result = new java.util.HashSet<>();
        if (module == null)
            return result;
        for (Method method : new ArrayList<>(module.allMethods()))
        {
            if (!(method instanceof com._1c.g5.v8.dt.bsl.model.Function) || method.getName() == null)
                continue;
            try
            {
                Object envs = BslDocCommentDescriptionFix.invokeCommentGetter(method, "environments"); //$NON-NLS-1$
                Object collector = BslDocCommentDescriptionFix.invokeCommentGetter(method, "getFinalReturnState"); //$NON-NLS-1$
                List<?> types = collector == null ? null : typesFromReturnProvider(collector, "NORMAL", envs); //$NON-NLS-1$
                if (types != null && !types.isEmpty())
                    result.add(method.getName());
            }
            catch (Throwable ignored)
            {
                // Не читается — считаем «с типом», чтобы не дать ложный returnLost.
                result.add(method.getName());
            }
        }
        return result;
    }

    /** Диагностика: состояния возврата метода по режимам — {@code NORMAL=[…] LIGHT=[…]}. */
    static String describeFinalReturnState(EObject method)
    {
        if (method == null)
            return "method=null"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        try
        {
            Object envs = BslDocCommentDescriptionFix.invokeCommentGetter(method, "environments"); //$NON-NLS-1$
            Object collector = BslDocCommentDescriptionFix.invokeCommentGetter(method, "getFinalReturnState"); //$NON-NLS-1$
            sb.append("envs=").append(envs).append(" collector=") //$NON-NLS-1$ //$NON-NLS-2$
                .append(collector == null ? "null" : collector.getClass().getSimpleName()); //$NON-NLS-1$
            if (collector == null)
                return sb.toString();
            for (String mode : new String[] { "NORMAL", "LIGHT" }) //$NON-NLS-1$ //$NON-NLS-2$
            {
                sb.append(' ').append(mode).append('=');
                try
                {
                    Class<?> modeClass = Class.forName(
                        "com._1c.g5.v8.dt.bsl.model.typesytem.TypeSystemMode", true, bslClassLoader()); //$NON-NLS-1$
                    @SuppressWarnings({ "unchecked", "rawtypes" })
                    Object modeValue = Enum.valueOf((Class<Enum>)modeClass.asSubclass(Enum.class), mode);
                    Object provider = Global.invoke(collector, "get", modeValue); //$NON-NLS-1$
                    Object last = provider == null ? null : Global.invoke(provider, "getLastState", envs); //$NON-NLS-1$
                    Object allLast = provider == null ? null
                        : BslDocCommentDescriptionFix.invokeCommentGetter(provider, "getLastState"); //$NON-NLS-1$
                    Object sub = last == null ? null : Global.invoke(last, "getSubStates", envs); //$NON-NLS-1$
                    sb.append("{provider=").append(provider == null ? "null" : provider.getClass().getSimpleName()) //$NON-NLS-1$ //$NON-NLS-2$
                        .append(" last=").append(last == null ? "null" : last.getClass().getSimpleName()) //$NON-NLS-1$ //$NON-NLS-2$
                        .append(" lastAll=").append(allLast instanceof List<?> l ? "#" + l.size() : String.valueOf(allLast)) //$NON-NLS-1$ //$NON-NLS-2$
                        .append(" sub=").append(sub instanceof List<?> l2 ? "#" + l2.size() : String.valueOf(sub)) //$NON-NLS-1$ //$NON-NLS-2$
                        .append("} "); //$NON-NLS-1$
                }
                catch (Throwable e)
                {
                    sb.append("{ERR:").append(e).append("} "); //$NON-NLS-1$ //$NON-NLS-2$
                }
                try
                {
                    List<?> types = typesFromReturnProvider(collector, mode, envs);
                    sb.append(types == null ? "null" : SmartContentAssistProcessor.ReceiverTypeLabel //$NON-NLS-1$
                        .formatTypeItems(types.stream().filter(TypeItem.class::isInstance)
                            .map(TypeItem.class::cast).toList()) + "#" + types.size()); //$NON-NLS-1$
                }
                catch (Throwable e)
                {
                    sb.append("ERR:").append(e); //$NON-NLS-1$
                }
            }
        }
        catch (Throwable e)
        {
            sb.append(" ERR:").append(e); //$NON-NLS-1$
        }
        return sb.toString();
    }
    // #endregion

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
     * EDT bsl 28.0.1.v202605050943: setInitialStateForExplicitMethodVariable задаёт
     * Undefined только переменным из Перем. У неявной переменной ветка без присваивания
     * поэтому не имеет исходного состояния. Добавляем такое же состояние в начале метода;
     * присваивания и объединение веток дальше выполняет штатный CreatorTreeState.
     */
    public static void afterInitialVariableStates(Block block, IScope scope,
        IBslOwnerComputerService owner, TypeSystemMode mode, INode node,
        Deque<VariableTreeTypeState> states)
    {
        if (!(block instanceof Method) || block.getImplicitVariables().isEmpty())
            return;
        try
        {
            var undefined = scope.getSingleElement(QualifiedName.create("Undefined")); //$NON-NLS-1$
            if (undefined == null || !(undefined.getEObjectOrProxy() instanceof TypeItem type))
                return;
            for (var variable : block.getImplicitVariables())
            {
                var environments = TypeSystemUtil.getActualEnvironments(variable, owner);
                var provider = TypeSystemUtil.getOrInitVariableProvider(variable, mode);
                var state = provider.createTypeState(variable, environments, node.getOffset(), node);
                state.addTypes(List.of(type));
                provider.addState(state);
                states.add(state);
                state.setHasNewTypes(true);
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    /** Вход из графа EDT: зависимости параметров обработчиков ОписаниеОповещения. */
    public static void afterNotificationTreeStates(Object creator)
    {
        try
        {
            NotificationParameters.link(creator);
        }
        catch (org.eclipse.core.runtime.OperationCanceledException canceled)
        {
            throw canceled;
        }
        catch (RuntimeException ignored)
        {
        }
    }

    /**
     * ИР: ОбработкаОбъект.ирКлсПолеТекстаПрограммы.ВычислитьТипПараметраМетодаМодуля,
     * ветка Оповещения: третий аргумент — последний параметр клиентского обработчика.
     * Зависимость добавляется после построения состояний вызывающего блока, до расчёта графа.
     * Штатный processDependState (bsl 28.0.1.v202605050943, CreatorTreeState) сохраняет
     * выражение, связи с переменными/возвратами и очередь расчёта, как для прямого вызова.
     */
    private static final class NotificationParameters
    {
        static void link(Object creator)
        {
            Object rawBlock = Global.getField(creator, "block"); //$NON-NLS-1$
            if (!(rawBlock instanceof Block block) || !isExtendedTypesEnabled(block)
                || Global.getField(creator, "mode") != TypeSystemMode.NORMAL) //$NON-NLS-1$
                return;
            var cancel = Global.getField(creator, "cancelIndicator"); //$NON-NLS-1$
            if (cancel instanceof org.eclipse.xtext.util.CancelIndicator indicator && indicator.isCanceled())
                return;
            Module module = org.eclipse.xtext.EcoreUtil2.getContainerOfType(block, Module.class);
            if (module == null)
                return;
            if (block instanceof Method handler && module.eResource() instanceof BslResource resource
                && resource.isOnlyMethodReparse() && resource.getReparseMethod() == handler
                && handler.isExport() && !handler.getFormalParams().isEmpty() && isClient(handler.environments()))
                restoreIncoming(creator, module, handler);
            for (OperatorStyleCreator notification : org.eclipse.xtext.EcoreUtil2.getAllContentsOfType(
                block, OperatorStyleCreator.class))
            {
                // При проходе тела модуля не дублируем вызовы из отдельных проходов методов.
                Method caller = org.eclipse.xtext.EcoreUtil2.getContainerOfType(notification, Method.class);
                if (caller != (block instanceof Method method ? method : null))
                    continue;
                String name = handlerName(notification);
                if (name == null)
                    continue;
                List<Expression> args = notification.getParams();
                for (Method handler : module.allMethods())
                {
                    if (!name.equalsIgnoreCase(handler.getName()) || !handler.isExport()
                        || handler.getFormalParams().isEmpty() || !isClient(handler.environments()))
                        continue;
                    linkArgument(creator, notification, args.get(2), handler, false);
                }
            }
        }

        private static String handlerName(OperatorStyleCreator notification)
        {
            var type = notification.getType();
            if (type == null || !isNotificationType(type.getName(), type.getNameRu()))
                return null;
            List<Expression> args = notification.getParams();
            if (args.size() < 3 || !(args.get(0) instanceof StringLiteral literal)
                || literal.getLines().size() != 1 || !(args.get(1) instanceof StaticFeatureAccess receiver)
                || !isSelf(receiver.getName()))
                return null;
            String line = literal.getLines().get(0);
            return line == null || line.length() < 2 ? null
                : line.substring(1, line.length() - 1).replace("\"\"", "\""); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /**
         * После правки обработчика EDT создаёт его входные состояния заново, не обходя вызывающие
         * методы. Их состояния NORMAL остались рассчитанными: восстанавливаем связь с новым
         * входом и переносим текущие типы, чтобы запустить распространение по новому графу.
         */
        private static void restoreIncoming(Object creator, Module module, Method handler)
        {
            for (OperatorStyleCreator notification : org.eclipse.xtext.EcoreUtil2.getAllContentsOfType(
                module, OperatorStyleCreator.class))
            {
                if (!handler.getName().equalsIgnoreCase(handlerName(notification))
                    || org.eclipse.xtext.EcoreUtil2.getContainerOfType(notification, Method.class) == handler)
                    continue; // Собственные вызовы ниже обрабатываются обычным исходящим проходом.
                linkArgument(creator, notification, notification.getParams().get(2), handler, true);
            }
        }

        /** Имена из platform-v8: NotifyDescription — старое, CallbackDescription — с 8.3.22. */
        private static boolean isNotificationType(String name, String nameRu)
        {
            return "NotifyDescription".equalsIgnoreCase(name) || "CallbackDescription".equalsIgnoreCase(name) //$NON-NLS-1$ //$NON-NLS-2$
                || "ОписаниеОповещения".equalsIgnoreCase(nameRu); //$NON-NLS-1$
        }

        private static boolean isSelf(String name)
        {
            return "ЭтотОбъект".equalsIgnoreCase(name) || "ЭтаФорма".equalsIgnoreCase(name) //$NON-NLS-1$ //$NON-NLS-2$
                || "ThisObject".equalsIgnoreCase(name) || "ThisForm".equalsIgnoreCase(name); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static boolean isClient(Environments environments)
        {
            return environments != null && (environments.containsAny(Environments.MNG_CLIENTS)
                || environments.contains(Environment.CLIENT));
        }

        private static void linkArgument(Object creator, OperatorStyleCreator notification,
            Expression argument, Method handler, boolean restore)
        {
            try
            {
                var owner = (IBslOwnerComputerService)Global.getField(creator, "ownerComputerService"); //$NON-NLS-1$
                var environments = TypeSystemUtil.getActualEnvironments(notification, owner);
                if (!isClient(environments))
                    return;
                int index = handler.getFormalParams().size() - 1;
                if (index >= handler.getFinalInParamState().size())
                    return;
                var provider = handler.getFinalInParamState().get(index).get(TypeSystemMode.NORMAL);
                var last = provider == null ? null : provider.getLastState(environments);
                if (!(last instanceof VariableTreeTypeStateWithSubStates states))
                    return;
                VariableTreeTypeState target = states.getConcreteState(environments);
                var dynamic = (DynamicFeatureAccessComputer)Global.getField(creator, "dynamicComputer"); //$NON-NLS-1$
                var extensions = (InvocationTypesComputerExtensionRegistry)Global.getField(creator,
                    "invTypesComputerExtension"); //$NON-NLS-1$
                List<VariableTreeTypeState> dependencies = CreatorTreeState.getAllDependState(argument,
                    dynamic, owner, extensions, TypeSystemMode.NORMAL, environments);
                boolean search = Boolean.TRUE.equals(Global.invoke(creator,
                    "needSearchFeatureDependencies", argument)); //$NON-NLS-1$
                if (!Global.invokeVoid(creator, "processDependState", target, argument, dependencies, //$NON-NLS-1$
                    search, environments))
                    throw new IllegalStateException("processDependState unavailable"); //$NON-NLS-1$
                if (restore)
                {
                    var computer = (TypesComputer)Global.getField(creator, "computerTypes"); //$NON-NLS-1$
                    List<TypeItem> types = computer.computeTypes(argument, environments);
                    target.addTypes(types);
                    // Старый источник уже обработан и не стоит в очереди. Распространяем новый вход
                    // сами: одного добавления рёбер недостаточно, hasNewTypes источника уже false.
                    target.setHasNewTypes(true);
                    @SuppressWarnings("unchecked")
                    Deque<VariableTreeTypeState> queue =
                        (Deque<VariableTreeTypeState>)Global.getField(creator, "allComputedState"); //$NON-NLS-1$
                    queue.add(target);
                }
            }
            catch (org.eclipse.core.runtime.OperationCanceledException canceled)
            {
                throw canceled;
            }
            catch (RuntimeException ignored)
            {
            }
        }
    }

    /**
     * Инструментализация {@code CreatorTreeState}: начальные состояния неявных переменных; в хвост
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
                if (mv != null && "doProcess".equals(name) //$NON-NLS-1$
                    && "(Lcom/e1c/g5/dt/core/api/platform/BmOperationContext;)V".equals(descriptor)) //$NON-NLS-1$
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.RETURN)
                            {
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitMethodInsn(Opcodes.INVOKESTATIC, SELF_INTERNAL, "afterNotificationTreeStates", //$NON-NLS-1$
                                    "(Ljava/lang/Object;)V", false); //$NON-NLS-1$
                                touched.set(true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                if (mv != null && "setInitialStateForExplicitMethodVariable".equals(name) //$NON-NLS-1$
                    && "()V".equals(descriptor)) //$NON-NLS-1$
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.RETURN)
                            {
                                String creator = TARGET_CREATOR.replace('.', '/');
                                String[] fields = { "block", "typeScope", "ownerComputerService", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                    "mode", "blockNode", "allComputedState" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                String[] types = { "Lcom/_1c/g5/v8/dt/bsl/model/Block;", //$NON-NLS-1$
                                    "Lorg/eclipse/xtext/scoping/IScope;", //$NON-NLS-1$
                                    "Lcom/_1c/g5/v8/dt/bsl/model/resource/owner/IBslOwnerComputerService;", //$NON-NLS-1$
                                    "Lcom/_1c/g5/v8/dt/bsl/model/typesytem/TypeSystemMode;", //$NON-NLS-1$
                                    "Lorg/eclipse/xtext/nodemodel/INode;", "Ljava/util/Deque;" }; //$NON-NLS-1$ //$NON-NLS-2$
                                for (int i = 0; i < fields.length; i++)
                                {
                                    visitVarInsn(Opcodes.ALOAD, 0);
                                    visitFieldInsn(Opcodes.GETFIELD, creator, fields[i], types[i]);
                                }
                                visitMethodInsn(Opcodes.INVOKESTATIC, SELF_INTERNAL, "afterInitialVariableStates", //$NON-NLS-1$
                                    "(" + String.join("", types) + ")V", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                touched.set(true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
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
