package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.commands.MCommand;
import org.eclipse.e4.ui.model.application.ui.menu.MHandledItem;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.emf.common.util.URI;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.menus.CommandContributionItem;
import org.eclipse.xtext.resource.IResourceServiceProvider;

import com._1c.g5.v8.dt.validation.git.IGitMarkerFilterManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerIndex;
import com._1c.g5.v8.dt.validation.marker.v2.IMarkerManagerV2;
import com._1c.g5.wiring.ServiceAccess;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/**
 * Механизм ЕДТ «Скрыть/показать языковые проблемы из базовой ветки git» — с исключением
 * для проблем плагина, которые скрывать нельзя. Штатная подсказка кнопки говорит «ошибки»;
 * плагин ставит «языковые проблемы»: скрываются не только ошибки.
 *
 * <p>Сейчас исключение одно — {@link BslAstTruncationCheck#CHECK_ID}. Обрыв разбора модуля
 * обесценивает результаты проверок всего модуля: ниже места обрыва кода в дереве нет, поэтому
 * «чисто» в панели «Проблемы» означает лишь то, что проверять было нечего. Спрятать такую
 * проблему как «унаследованную от базовой ветки» — оставить пользователя с молчаливым модулем.
 *
 * <p><b>Два пути скрытия.</b> Решение принимает служба {@link IGitMarkerFilterManager}
 * (реализация {@code GitMarkerFilterManager} бандла {@code com.e1c.g5.v8.dt.check}), и зовут её
 * из двух мест:
 * <ul>
 * <li>{@code shouldSkipIssue(uri, line)} — {@code BslNotifyingResourceValidator.validate}
 * выбрасывает Issue <b>до</b> создания маркера (при проверке открытого документа, её ведёт
 * {@code BslXtextDocumentProvider$BslValidationJob}). Для открытого модуля решает именно этот
 * путь: маркера не появляется вовсе;</li>
 * <li>{@code shouldSkipMarker(marker, project)} — {@code BranchChangesIndexProvider} кладёт
 * ответ в индекс маркеров {@code BRANCH_CHANGES}, а {@code BranchChangesFilterProvider} строит
 * по нему глобальный отбор панели «Проблемы».</li>
 * </ul>
 *
 * <p><b>Куда вклиниваемся.</b> Потребители держат службу не напрямую: в модулях Guice она
 * связана как {@code bind(IGitMarkerFilterManager.class).toService()}, то есть в поля внедряется
 * динамический прокси peaberry ({@code IGitMarkerFilterManager$pbryglu}). Такой прокси хранит
 * службу в поле {@code __pbry__} типа {@code org.ops4j.peaberry.Import} и на каждом вызове берёт
 * её через {@code get()} — плагин подменяет этот {@code Import} своим, возвращающим обёртку над
 * настоящей службой. Прокси общий для потребителей одного инжектора, поэтому подмен нужно
 * немного, и ни одного нового объекта в реестре служб не появляется.
 *
 * <p><b>Почему не регистрация своей службы в OSGi.</b> Пробовали: ранг реестр учитывает, но
 * {@code ServiceAccess.get} у ЕДТ при двух регистрациях одного типа бросает
 * {@code ServiceUnavailableException: Multiple services were registered} — то есть вторая
 * регистрация ломает штатный поиск службы. Этот путь закрыт.
 *
 * <p><b>Как узнаётся исключённая проблема.</b> В пути маркеров у маркера {@code checkId} —
 * короткий UID проекта ({@code SU…}) или длинный {@link BslAstTruncationCheck#CHECK_ID};
 * резолв короткого — {@code ICheckRepository.getUidForShortUid}. В пути Issue на входе только
 * файл и строка, поэтому {@link BslAstTruncationCheck} перед публикацией отмечает пару
 * «модуль → строка» ({@link #exemptIssue}), а при целом дереве — снимает отметку
 * ({@link #forgetIssue}). Проверка и отбор идут в одном вызове валидации, так что отметка живёт
 * доли секунды и заводится не больше одной на модуль.
 */
public class GitBaselineFilterHook
    implements IStartup
{
    /** Команда кнопки тулбара EDT {@code toolbar:com._1c.g5.v8.dt.ui.toolbar}. */
    private static final String TOGGLE_COMMAND_ID =
        "com._1c.g5.v8.dt.ui.command.toggleToShowBaselineMarkers"; //$NON-NLS-1$

    /** Штатное имя команды — оно же подсказка кнопки. */
    private static final String TOGGLE_STOCK_TOOLTIP =
        "Скрыть/показать ошибки из базовой ветки git"; //$NON-NLS-1$

    private static final String TOGGLE_TOOLTIP =
        "Скрыть/показать языковые проблемы из базовой ветки git"; //$NON-NLS-1$

    /** Поле динамического прокси peaberry со ссылкой на службу ({@code ImportGlue.PROXY_HANDLE}). */
    private static final String PEABERRY_HANDLE_FIELD = "__pbry__"; //$NON-NLS-1$

    /** Суффикс класса динамического прокси peaberry. */
    private static final String PEABERRY_SUFFIX = "$pbryglu"; //$NON-NLS-1$

    private static final String PEABERRY_IMPORT_CLASS = "org.ops4j.peaberry.Import"; //$NON-NLS-1$

    private static final String MANAGER_FIELD = "gitMarkerFilterManager"; //$NON-NLS-1$

    /** Отметка о разовой чистке маркеров, записанных с несуществующим типом источника. */
    private static final String TRUNCATION_MARKERS_PURGED = "astTruncationMarkersPurged.v2"; //$NON-NLS-1$

    /** Имя индекса маркеров из {@code BranchChangesIndexProvider.NAME}. */
    private static final String BRANCH_CHANGES_INDEX = "BRANCH_CHANGES"; //$NON-NLS-1$

    /** Пауза перед повторной попыткой: службы и индексы поднимаются не мгновенно при старте. */
    private static final int RETRY_DELAY_MS = 15_000;

    private static final int RETRY_ATTEMPTS = 20;

    private static final int TOOLTIP_RETRY_MS = 500;

    /** Модуль → строка проблемы «Обрыв разбора модуля» в нём. Не больше записи на модуль. */
    private static final Map<String, Integer> EXEMPT_ISSUE_LINES = new ConcurrentHashMap<>();

    /** Уже подменённые прокси — по тождеству: один и тот же прокси приходит из разных мест. */
    private static final Set<Object> PATCHED = Collections.newSetFromMap(new IdentityHashMap<>());

    private static final java.util.concurrent.atomic.AtomicBoolean TOGGLE_LISTENER_INSTALLED =
        new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    public void earlyStartup()
    {
        scheduleInstall(RETRY_ATTEMPTS);
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> patchToggleTooltip(RETRY_ATTEMPTS));
    }

    /**
     * Подсказка кнопки при перерисовке берётся из модели e4 ({@code MItem.getLocalizedTooltip}),
     * а не из живого имени команды. {@link Command#define} сам по себе не удерживает текст:
     * после нажатия {@code HandledContributionItem} снова ставит подсказку из модели.
     * Поэтому меняем и имя команды, и {@link MHandledItem#setTooltip}, и сам {@link ToolItem}.
     */
    private static void patchToggleTooltip(int attemptsLeft)
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commands == null)
        {
            retryPatchToggleTooltip(attemptsLeft);
            return;
        }
        Command command = commands.getCommand(TOGGLE_COMMAND_ID);
        if (command == null || !command.isDefined())
        {
            retryPatchToggleTooltip(attemptsLeft);
            return;
        }
        try
        {
            if (!TOGGLE_TOOLTIP.equals(command.getName())
                || !TOGGLE_TOOLTIP.equals(command.getDescription()))
            {
                command.define(TOGGLE_TOOLTIP, TOGGLE_TOOLTIP, command.getCategory(),
                    command.getParameters());
            }
        }
        catch (NotDefinedException e)
        {
            retryPatchToggleTooltip(attemptsLeft);
            return;
        }
        listenToggleTooltip(commands);
        boolean modelPatched = patchToggleModel();
        patchToggleWidgets();
        if (!modelPatched)
            retryPatchToggleTooltip(attemptsLeft);
    }

    /** После клика штатный обработчик перерисовывает кнопку из модели — повторяем подсказку. */
    private static void listenToggleTooltip(ICommandService commands)
    {
        if (!TOGGLE_LISTENER_INSTALLED.compareAndSet(false, true))
            return;
        commands.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void notHandled(String commandId, NotHandledException exception)
            {
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception)
            {
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
                if (!TOGGLE_COMMAND_ID.equals(commandId))
                    return;
                Display display = Display.getCurrent();
                if (display != null && !display.isDisposed())
                    display.asyncExec(() ->
                    {
                        patchToggleModel();
                        patchToggleWidgets();
                    });
            }

            @Override
            public void preExecute(String commandId, org.eclipse.core.commands.ExecutionEvent event)
            {
            }
        });
    }

    /** @return {@code true}, если кнопка уже есть в модели e4 */
    private static boolean patchToggleModel()
    {
        IWorkbench workbench = PlatformUI.getWorkbench();
        MApplication application = workbench.getService(MApplication.class);
        EModelService models = workbench.getService(EModelService.class);
        if (application == null || models == null)
            return false;
        boolean found = false;
        for (MHandledItem item : models.findElements(application, null, MHandledItem.class))
        {
            if (!isBaselineHandled(item))
                continue;
            found = true;
            if (!TOGGLE_TOOLTIP.equals(item.getTooltip()))
                item.setTooltip(TOGGLE_TOOLTIP);
        }
        return found;
    }

    private static boolean isBaselineHandled(MHandledItem item)
    {
        MCommand command = item.getCommand();
        if (command != null && TOGGLE_COMMAND_ID.equals(command.getElementId()))
            return true;
        ParameterizedCommand wb = item.getWbCommand();
        return wb != null && TOGGLE_COMMAND_ID.equals(wb.getId());
    }

    private static void patchToggleWidgets()
    {
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            Shell shell = window.getShell();
            if (shell != null && !shell.isDisposed())
                patchToggleToolItems(shell);
        }
    }

    private static void retryPatchToggleTooltip(int attemptsLeft)
    {
        if (attemptsLeft <= 1)
            return;
        Display display = Display.getCurrent();
        if (display != null && !display.isDisposed())
            display.timerExec(TOOLTIP_RETRY_MS, () -> patchToggleTooltip(attemptsLeft - 1));
    }

    private static void patchToggleToolItems(Control root)
    {
        if (root instanceof ToolBar bar)
        {
            for (ToolItem item : bar.getItems())
            {
                if (item.isDisposed() || !isBaselineToggle(item))
                    continue;
                String wrapped = TooltipText.wrap(bar, TOGGLE_TOOLTIP);
                if (!wrapped.equals(item.getToolTipText()))
                    item.setToolTipText(wrapped);
            }
        }
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
                patchToggleToolItems(child);
        }
    }

    private static boolean isBaselineToggle(ToolItem item)
    {
        Object data = item.getData();
        if (data instanceof MHandledItem handled)
            return isBaselineHandled(handled);
        if (data instanceof CommandContributionItem contribution
            && contribution.getCommand() != null)
        {
            return TOGGLE_COMMAND_ID.equals(contribution.getCommand().getId());
        }
        String tip = item.getToolTipText();
        return tip != null && (tip.startsWith(TOGGLE_STOCK_TOOLTIP) || tip.startsWith(TOGGLE_TOOLTIP));
    }

    /**
     * Отмечает строку проблемы, которую отбор по базовой ветке скрывать не должен.
     *
     * @param resourceUri URI ресурса модуля
     * @param line номер строки проблемы (как её посчитает Xtext по смещению)
     */
    public static void exemptIssue(String resourceUri, int line)
    {
        if (resourceUri != null)
            EXEMPT_ISSUE_LINES.put(resourceUri, Integer.valueOf(line));
    }

    /** Снимает отметку: в этом модуле обрыва больше нет. */
    public static void forgetIssue(String resourceUri)
    {
        if (resourceUri != null)
            EXEMPT_ISSUE_LINES.remove(resourceUri);
    }

    /**
     * Подменяет службу у известных потребителей, если это ещё не сделано. Дёшево при повторных
     * вызовах: подменённые прокси запоминаются по тождеству.
     *
     * @return {@code true}, если исключение действует хотя бы на пути Issue
     */
    public static synchronized boolean ensureInstalled()
    {
        boolean issuePath = patchHolder(bslResourceValidator());
        patchHolder(branchChangesIndexProvider());
        return issuePath;
    }

    /** Валидатор ресурсов BSL — потребитель службы на пути Issue. */
    private static Object bslResourceValidator()
    {
        IResourceServiceProvider provider =
            IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(URI.createURI("comfort.bsl")); //$NON-NLS-1$
        return provider != null ? provider.getResourceValidator() : null;
    }

    /**
     * {@code BranchChangesIndexProvider} — потребитель службы на пути маркеров. Прямой ссылки на
     * него нет: он живёт в лямбде-извлекателе значения индекса {@code BRANCH_CHANGES}, которую
     * менеджер маркеров отдаёт штатным {@link IMarkerManagerV2#getRegisteredIndexes}.
     */
    private static Object branchChangesIndexProvider()
    {
        try
        {
            IMarkerManagerV2 markerManager = ServiceAccess.get(IMarkerManagerV2.class);
            if (markerManager == null)
                return null;
            Set<MarkerIndex> indexes = markerManager.getRegisteredIndexes(MarkerIndex.Activity.FILTERING);
            if (indexes == null)
                return null;
            for (MarkerIndex index : indexes)
            {
                if (!BRANCH_CHANGES_INDEX.equals(index.getName()))
                    continue;
                Field extractorField = MarkerIndex.class.getDeclaredField("valueExtractor"); //$NON-NLS-1$
                extractorField.setAccessible(true);
                Object extractor = extractorField.get(index);
                return extractor instanceof Function ? capturedInstance(extractor) : null;
            }
        }
        catch (Exception | LinkageError e)
        {
        }
        return null;
    }

    /** Объект, захваченный лямбдой-ссылкой на метод ({@code this::skipMarker}). */
    private static Object capturedInstance(Object lambda) throws ReflectiveOperationException
    {
        for (Field field : lambda.getClass().getDeclaredFields())
        {
            if (!field.getName().startsWith("arg$")) //$NON-NLS-1$
                continue;
            field.setAccessible(true);
            return field.get(lambda);
        }
        return null;
    }

    /**
     * Подменяет службу у одного потребителя. Обычно в его поле лежит динамический прокси
     * peaberry — тогда подменяется ссылка внутри прокси, и вклад достаётся всем, кто держит тот
     * же прокси. Если там сама служба, подменяется само поле потребителя.
     *
     * @return {@code true}, если после вызова исключение действует
     */
    private static boolean patchHolder(Object holder)
    {
        if (holder == null)
            return false;
        try
        {
            Field field = findField(holder.getClass(), MANAGER_FIELD);
            if (field == null)
                return false;
            field.setAccessible(true);
            Object manager = field.get(holder);
            if (manager == null)
                return false;
            if (PATCHED.contains(manager))
                return true;

            Field handleField = findField(manager.getClass(), PEABERRY_HANDLE_FIELD);
            if (handleField != null)
            {
                patchPeaberryHandle(manager, handleField);
                PATCHED.add(manager);
                return true;
            }

            if (!(manager instanceof IGitMarkerFilterManager origin))
                return false;
            Object wrapper = exempting(origin);
            field.set(holder, wrapper);
            PATCHED.add(wrapper);
            return true;
        }
        catch (Exception | LinkageError e)
        {
            return false;
        }
    }

    /**
     * Прокси peaberry на каждом вызове берёт службу из своего {@code Import.get()} — подменяем
     * сам {@code Import}: он возвращает обёртку над тем, что вернул штатный.
     */
    private static void patchPeaberryHandle(Object peaberryProxy, Field handleField)
        throws ReflectiveOperationException
    {
        handleField.setAccessible(true);
        Object origin = handleField.get(peaberryProxy);
        if (origin == null)
            return;

        Class<?> importClass = Class.forName(PEABERRY_IMPORT_CLASS, false, peaberryProxy.getClass().getClassLoader());
        Object wrapper = Proxy.newProxyInstance(importClass.getClassLoader(), new Class<?>[] { importClass },
            new ExemptingImport(origin));
        handleField.set(peaberryProxy, wrapper);
    }

    private static Field findField(Class<?> type, String name)
    {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass())
        {
            for (Field field : current.getDeclaredFields())
                if (field.getName().equals(name))
                    return field;
        }
        return null;
    }

    private static IGitMarkerFilterManager exempting(IGitMarkerFilterManager origin)
    {
        return (IGitMarkerFilterManager)Proxy.newProxyInstance(IGitMarkerFilterManager.class.getClassLoader(),
            new Class<?>[] { IGitMarkerFilterManager.class }, new ExemptingHandler(origin));
    }

    /**
     * Убирает из хранилища маркеры «Обрыв разбора модуля», записанные с типом источника, которого
     * в EDT нет.
     *
     * <p>Провайдер маркера подбирается по типу источника, и на маркере с чужим типом панель
     * «Проблемы конфигурации» падает с «Provider not found for marker» при каждой загрузке.
     * Маркер лежит в хранилище и правкой кода не лечится, поэтому удаляем его тем же способом,
     * каким EDT удаляет маркеры выключенной проверки — {@code removeMarkersByCheckId}.
     *
     * <p>Делается один раз на рабочую область: отметка в настройках. Иначе удаление сносило бы и
     * годные маркеры при каждом запуске, а вернулись бы они только после проверки модуля.
     */
    private static void purgeBrokenTruncationMarkers()
    {
        org.eclipse.core.runtime.preferences.IEclipsePreferences preferences =
            org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode("tormozit"); //$NON-NLS-1$
        if (preferences.getBoolean(TRUNCATION_MARKERS_PURGED, false))
            return;
        try
        {
            IMarkerManagerV2 markerManager = ServiceAccess.get(IMarkerManagerV2.class);
            if (markerManager == null)
                return;
            for (org.eclipse.core.resources.IProject project : org.eclipse.core.resources.ResourcesPlugin
                .getWorkspace()
                .getRoot()
                .getProjects())
            {
                if (project.isAccessible())
                    markerManager.removeMarkersByCheckId(project, BslAstTruncationCheck.CHECK_ID);
            }
            preferences.putBoolean(TRUNCATION_MARKERS_PURGED, true);
            preferences.flush();
        }
        catch (Exception | LinkageError e)
        {
        }
    }

    /**
     * Службы и индексы поднимаются по мере готовности бандлов, поэтому при старте попытка
     * повторяется — ограниченное число раз, без бесконечного самоперевзвода.
     */
    private static void scheduleInstall(int attemptsLeft)
    {
        Job job = new Job("Комфорт: отбор проблем по базовой ветке") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                purgeBrokenTruncationMarkers();
                if (!ensureInstalled() && attemptsLeft > 1)
                    scheduleInstall(attemptsLeft - 1);
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule(RETRY_DELAY_MS);
    }

    /** {@code Import} peaberry: отдаёт обёртку вместо самой службы. */
    private static final class ExemptingImport
        implements InvocationHandler
    {
        private final Object origin;

        /** Обёртки кешируются по службе: {@code get()} зовётся на каждый вызов прокси. */
        private final Map<Object, Object> wrappers = new IdentityHashMap<>();

        ExemptingImport(Object origin)
        {
            this.origin = origin;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
        {
            Object result = call(method, args);
            if (!"get".equals(method.getName()) || !(result instanceof IGitMarkerFilterManager manager)) //$NON-NLS-1$
                return result;
            synchronized (wrappers)
            {
                return wrappers.computeIfAbsent(manager, key -> exempting((IGitMarkerFilterManager)key));
            }
        }

        private Object call(Method method, Object[] args) throws Throwable
        {
            try
            {
                Method target = origin.getClass().getMethod(method.getName(), method.getParameterTypes());
                target.setAccessible(true);
                return target.invoke(origin, args);
            }
            catch (InvocationTargetException e)
            {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
    }

    /**
     * В маркере проверки {@link Marker#getCheckId()} — короткий UID проекта ({@code SU…}),
     * а не {@link BslAstTruncationCheck#CHECK_ID}. Хвосты прошлой записи несут длинный идентификатор.
     */
    private static boolean isTruncationCheck(Marker marker)
    {
        String id = marker.getCheckId();
        if (id == null || id.isBlank())
            return false;
        if (BslAstTruncationCheck.CHECK_ID.equals(id))
            return true;
        ICheckRepository repository = Global.getOsgiService(ICheckRepository.class);
        org.eclipse.core.resources.IProject project = marker.getProject();
        if (repository == null || project == null)
            return false;
        CheckUid uid = repository.getUidForShortUid(id, project);
        return uid != null && BslAstTruncationCheck.CHECK_ID.equals(uid.getCheckId());
    }

    /** Делегирует настоящей службе всё, кроме решения о скрытии исключённых проблем. */
    private static final class ExemptingHandler
        implements InvocationHandler
    {
        private static final String SHOULD_SKIP_ISSUE = "shouldSkipIssue"; //$NON-NLS-1$

        private static final String SHOULD_SKIP_MARKER = "shouldSkipMarker"; //$NON-NLS-1$

        private final IGitMarkerFilterManager origin;

        ExemptingHandler(IGitMarkerFilterManager origin)
        {
            this.origin = origin;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
        {
            String name = method.getName();

            if (SHOULD_SKIP_ISSUE.equals(name) && args != null && args.length == 2 && isExemptIssue(args))
                return Boolean.FALSE;
            if (SHOULD_SKIP_MARKER.equals(name) && args != null && args.length == 2
                && args[0] instanceof Marker marker && isTruncationCheck(marker))
                return Boolean.FALSE;

            try
            {
                return method.invoke(origin, args);
            }
            catch (InvocationTargetException e)
            {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }

        /** @param args {@code [URI ресурса с фрагментом, номер строки]} */
        private static boolean isExemptIssue(Object[] args)
        {
            if (!(args[0] instanceof URI uri) || !(args[1] instanceof Integer line))
                return false;
            Integer exempt = EXEMPT_ISSUE_LINES.get(uri.trimFragment().toString());
            return exempt != null && exempt.intValue() == line.intValue();
        }
    }
}
