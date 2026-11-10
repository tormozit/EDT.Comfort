package tormozit;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.dt.bsl.common.IBslPreferences;
import com._1c.g5.v8.dt.bsl.documentation.comment.BslCommentUtils;
import com._1c.g5.v8.dt.bsl.documentation.comment.BslMultiLineCommentDocumentationProvider;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com.e1c.g5.dt.core.api.naming.INamingService;
import com.e1c.g5.dt.core.api.platform.BmOperationContext;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.xtext.naming.IQualifiedNameConverter;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.scoping.IScopeProvider;

/** Типы параметров из актуального комментария для подсказок и проверок вызова. */
public final class BslMethodParameterTypes
{
    private BslMethodParameterTypes() {}

    /** Возвращает null, если описание параметра или контекст его вычисления отсутствуют. */
    public static List<TypeItem> fromComment(Method method, FormalParam parameter)
    {
        List<TypeItem> documented = null;
        try
        {
            if (method.eResource() instanceof XtextResource resource)
            {
                var services = resource.getResourceServiceProvider();
                var comments = services.get(BslMultiLineCommentDocumentationProvider.class);
                var projects = services.get(IV8ProjectManager.class);
                var project = projects.getProject(resource.getURI());
                boolean oldFormat = project == null || project.getProject() == null
                    || services.get(IBslPreferences.class)
                        .getDocumentCommentProperties(project.getProject()).oldCommentFormat();
                // Именно эта перегрузка используется в штатной подсказке параметров.
                var comment = BslCommentUtils.parseTemplateComment(comments.getCommentLines(method), method,
                    oldFormat);
                var field = comment.getParametersSection() != null
                    ? comment.getParametersSection().getParameterByName(parameter.getName()) : null;
                if (field != null)
                {
                    var models = services.get(IBmModelManager.class);
                    var naming = services.get(INamingService.class);
                    var scopeProvider = services.get(IScopeProvider.class);
                    var scope = scopeProvider.getScope(method, McorePackage.Literals.TYPE_DESCRIPTION__TYPES);
                    var converter = services.get(IQualifiedNameConverter.class);
                    var model = models.getModel(resource.getURI());
                    if (model != null)
                    {
                        var task = new AbstractBmTask<List<TypeItem>>("comfort.expectedParameterTypes") //$NON-NLS-1$
                        {
                            @Override
                            public List<TypeItem> execute(IBmTransaction transaction, IProgressMonitor monitor)
                            {
                                var context = new BmOperationContext(naming, models, transaction);
                                List<TypeItem> result = new ArrayList<>();
                                for (var section : field.getTypeSections())
                                    result.addAll(comment.computeTypeSectionTypes(section, scope, scopeProvider,
                                        converter, comments, projects, oldFormat, method, context));
                                return result;
                            }
                        };
                        if (Global.invoke(model.getEngine(), "getCurrentTransaction") //$NON-NLS-1$
                            instanceof IBmTransaction currentTransaction)
                            documented = task.execute(currentTransaction, null);
                        else
                            documented = model.executeReadonlyTask(task, true);
                    }
                }
            }
        }
        catch (RuntimeException error)
        {
            // Контекст недоступен: вызывающий код использует прежний путь расчёта типов.
        }
        return documented;
    }

    /** Из {@link BslDocCommentTypeMerge#installWeavingHook()}: после колбэков раннего бандла. */
    public static void installReturnTypesMemo()
    {
        ReturnTypesMemo.install();
    }

    /** Ключ, прочитанный из множества посещённых без добавления ({@code skipVisitedLinkTarget}). */
    public static void recordConsulted(String key)
    {
        ReturnTypesMemo.consulted(key);
    }

    /**
     * Типы возврата цели «см. Функция» на время одной проверки {@code BslValueTypeCheck} модуля.
     * Штатный {@code BslDocumentationComment.computeReturnTypes} считает их заново для каждого поля,
     * ссылающегося на функцию: на большом модуле это почти всё время {@link #fromComment}.
     *
     * <p>Результат зависит от множества посещённых только через его ключи: штатный код их добавляет
     * ({@code Set.add}), а {@code skipVisitedLinkTarget} проверяет. Поэтому функция считается один раз
     * с пустым множеством, и запоминаются типы, добавленные ключи и все затронутые ключи. Запомненное
     * отдаётся, только если ни один затронутый ключ не входит в текущее множество: тогда штатный обход
     * прошёл бы тот же путь и добавил те же ключи. Иначе — штатный расчёт. Свободные объекты типов
     * (вне ресурса) отдаются копиями, как штатный расчёт создаёт их заново.
     *
     * <p>Область — вызов {@code ICheck.check} этой проверки (колбэки {@code tormozit.checkDuration.begin/end}
     * раннего бандла): модуль и модель в ней неизменны.
     */
    private static final class ReturnTypesMemo
    {
        private static final String CALLBACK = "tormozit.formData.returnMemo"; //$NON-NLS-1$
        private static final String CHECK_DURATION = "tormozit.checkDuration"; //$NON-NLS-1$
        private static final String CHECK = "tormozit.checks.BslValueTypeCheck"; //$NON-NLS-1$
        private static final java.util.concurrent.atomic.AtomicBoolean INSTALLED =
            new java.util.concurrent.atomic.AtomicBoolean();
        private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();
        private static volatile java.lang.reflect.Method compute;

        private static final class Scope
        {
            int depth;
            final java.util.Map<String, Entry> memo = new java.util.HashMap<>();
            /** Множество, с которым идёт корневой вызов свежего расчёта: его пропускаем к штатному. */
            java.util.Set<?> bypass;
            /** Затронутые ключи идущих свежих расчётов, от внешнего к внутреннему. */
            final java.util.ArrayDeque<java.util.Set<String>> touched = new java.util.ArrayDeque<>();
        }

        private record Item(Object value, boolean free) {}

        private record Entry(List<Item> types, java.util.Set<String> added, java.util.Set<String> touched) {}

        static void install()
        {
            if (!INSTALLED.compareAndSet(false, true))
                return;
            System.getProperties().put(CALLBACK,
                (java.util.function.Function<Object[], Object>) ReturnTypesMemo::apply);
            chain(".begin", true); //$NON-NLS-1$
            chain(".end", false); //$NON-NLS-1$
        }

        @SuppressWarnings("unchecked")
        private static boolean chain(String suffix, boolean begin)
        {
            if (!(System.getProperties().get(CHECK_DURATION + suffix)
                instanceof java.util.function.Consumer<?> previous))
                return false;
            java.util.function.Consumer<Object> stock = (java.util.function.Consumer<Object>) previous;
            System.getProperties().put(CHECK_DURATION + suffix, (java.util.function.Consumer<Object>) check -> {
                if (begin)
                    enter(check);
                try
                {
                    stock.accept(check);
                }
                finally
                {
                    if (!begin)
                        exit(check);
                }
            });
            return true;
        }

        private static boolean isScopeCheck(Object check)
        {
            return check != null && CHECK.equals(check.getClass().getName());
        }

        private static void enter(Object check)
        {
            if (!isScopeCheck(check))
                return;
            Scope scope = SCOPE.get();
            if (scope == null)
                SCOPE.set(scope = new Scope());
            scope.depth++;
        }

        private static void exit(Object check)
        {
            Scope scope = isScopeCheck(check) ? SCOPE.get() : null;
            if (scope != null && --scope.depth <= 0)
                SCOPE.remove();
        }

        static void consulted(String key)
        {
            Scope scope = SCOPE.get();
            if (scope != null && key != null)
                for (java.util.Set<String> touched : scope.touched)
                    touched.add(key);
        }

        /** [comment, scope, scopeProvider, converter, docs, projects, oldFormat, context, visited, ctx]. */
        @SuppressWarnings("unchecked")
        private static Object apply(Object[] args)
        {
            Scope scope = SCOPE.get();
            if (scope == null || args == null || args.length != 10
                || !(args[8] instanceof java.util.Set<?> visited))
                return null;
            if (visited == scope.bypass)
            {
                scope.bypass = null;
                return null;
            }
            Object uri = Global.getField(args[0], "methodURI"); //$NON-NLS-1$
            // Функция уже посещена: штатный расчёт сразу вернёт пустой список.
            if (uri == null || visited.contains(uri.toString()))
                return null;
            String key = uri + (Boolean.TRUE.equals(args[6]) ? "|old" : "|new"); //$NON-NLS-1$ //$NON-NLS-2$
            Entry entry = scope.memo.get(key);
            java.util.Collection<?> computed = null;
            if (entry == null)
            {
                java.lang.reflect.Method method = computeMethod(args[0]);
                if (method == null)
                    return null;
                java.util.Set<String> fresh = new java.util.HashSet<>();
                java.util.Set<String> touched = new java.util.HashSet<>();
                Object[] call = java.util.Arrays.copyOfRange(args, 1, args.length);
                call[7] = fresh;
                scope.touched.push(touched);
                scope.bypass = fresh;
                try
                {
                    computed = (java.util.Collection<?>) method.invoke(args[0], call);
                }
                catch (java.lang.reflect.InvocationTargetException error)
                {
                    // Отмена и ошибки расчёта — как у штатного вызова.
                    if (error.getCause() instanceof RuntimeException runtime)
                        throw runtime;
                    if (error.getCause() instanceof Error fatal)
                        throw fatal;
                    return null;
                }
                catch (ReflectiveOperationException | RuntimeException error)
                {
                    return null;
                }
                finally
                {
                    scope.bypass = null;
                    scope.touched.pop();
                }
                touched.addAll(fresh);
                entry = new Entry(snapshot(computed), java.util.Set.copyOf(fresh), java.util.Set.copyOf(touched));
                scope.memo.put(key, entry);
            }
            for (String reached : entry.touched)
                if (visited.contains(reached))
                    return null;
            for (java.util.Set<String> outer : scope.touched)
                outer.addAll(entry.touched);
            ((java.util.Set<Object>) visited).addAll(entry.added);
            if (computed != null)
                return computed;
            List<Object> result = new ArrayList<>(entry.types.size());
            for (Item item : entry.types)
                result.add(item.free ? org.eclipse.emf.ecore.util.EcoreUtil.copy((org.eclipse.emf.ecore.EObject) item.value)
                    : item.value);
            return result;
        }

        private static List<Item> snapshot(java.util.Collection<?> types)
        {
            List<Item> items = new ArrayList<>();
            if (types != null)
                for (Object type : types)
                {
                    boolean free = type instanceof org.eclipse.emf.ecore.EObject object
                        && org.eclipse.emf.ecore.util.EcoreUtil.getRootContainer(object).eResource() == null;
                    // Копия сразу: первый получатель может вложить исходный объект в своё свойство.
                    items.add(new Item(free ? org.eclipse.emf.ecore.util.EcoreUtil.copy(
                        (org.eclipse.emf.ecore.EObject) type) : type, free));
                }
            return items;
        }

        private static java.lang.reflect.Method computeMethod(Object comment)
        {
            java.lang.reflect.Method method = compute;
            if (method != null)
                return method;
            try
            {
                method = comment.getClass().getDeclaredMethod("computeReturnTypes", //$NON-NLS-1$
                    org.eclipse.xtext.scoping.IScope.class, IScopeProvider.class, IQualifiedNameConverter.class,
                    BslMultiLineCommentDocumentationProvider.class, IV8ProjectManager.class, boolean.class,
                    org.eclipse.emf.ecore.EObject.class, java.util.Set.class, BmOperationContext.class);
                method.setAccessible(true);
                compute = method;
                return method;
            }
            catch (ReflectiveOperationException | RuntimeException error)
            {
                return null;
            }
        }
    }
}
