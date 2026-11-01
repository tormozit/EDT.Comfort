package tormozit;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.navigator.CommonNavigator;

import com._1c.g5.v8.dt.ui.util.OpenHelper;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;

/**
 * Доступ к внутренностям панели «Ошибки конфигурации» EDT
 * ({@code com._1c.g5.v8.dt.internal.ui.validation.lazytree.LazyProblemView}).
 * <p>
 * Методы {@code selectedMarkers()} и {@code getMarkerFilter()} у неё public, но сам класс лежит
 * во внутреннем пакете и бандлом не экспортируется — поэтому вызов через {@link Global#invoke},
 * а вот возвращаемые типы ({@link Marker}, {@link MarkerFilter}) из экспортируемого
 * {@code com._1c.g5.v8.dt.validation.marker} и типизируются нормально.
 */
public final class ProblemViewMarkers
{
    /** ID панели «Ошибки конфигурации» (см. plugin.xml бандла {@code com._1c.g5.v8.dt.ui.validation}). */
    public static final String PROBLEM_VIEW_ID = "com._1c.g5.v8.dt.ui.problemView"; //$NON-NLS-1$

    /** Команда панели для переключения области отбора (radio). */
    private static final String SCOPE_COMMAND_ID = "com._1c.g5.v8.dt.ui.command.filtersScopeRadio"; //$NON-NLS-1$
    private static final String SCOPE_PARAMETER_ID = "org.eclipse.ui.commands.radioStateParameter"; //$NON-NLS-1$
    private static final String SCOPE_CURRENT_OBJECT = "CURRENT_OBJECT"; //$NON-NLS-1$

    private ProblemViewMarkers() {}

    /**
     * Переключает область отбора на «Текущий объект» (штатная команда {@value #SCOPE_COMMAND_ID})
     * и выводит панель на передний план.
     * <p>
     * Область нужно переключить, пока активна часть с объектом (редактор): панель определяет
     * «текущий объект» по активной части, а {@link IWorkbenchPage#showView} сразу уводит фокус
     * на панель.
     */
    public static void showForCurrentObject()
    {
        switchScopeToCurrentObject();
        show();
    }

    /** Открывает панель «Ошибки конфигурации» без смены области отбора. */
    public static void show()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        if (page == null)
            return;
        try
        {
            page.showView(PROBLEM_VIEW_ID);
        }
        catch (PartInitException ignored)
        {
        }
    }

    /**
     * Открывает панель с результатом пересчёта проверок. Для одного объекта — активирует его
     * редактор и включает область «Текущий объект». Иначе область не меняет, а если её источник
     * (активный редактор или выделение навигатора) не пересекается с проверенным набором —
     * подставляет подходящий: для объектов открывает редактор первого из них, для проекта
     * активирует навигатор и, если в нём выделен другой проект, ставит строку на нужный.
     *
     * @param objects проверенные объекты; пусто — проверялся проект целиком
     * @param topIds их top-объекты с подчинёнными; для проекта не используются
     */
    public static void showChecked(IProject project, Collection<? extends EObject> objects, Set<Object> topIds)
    {
        IWorkbenchPage page = Global.getActivePage();
        if (page == null)
            return;
        if (objects != null && objects.size() == 1)
        {
            openEditor(page, objects.iterator().next());
            showForCurrentObject();
            return;
        }
        // Закрытая панель берёт область из навигатора при создании — сверять уже её
        IViewPart view = page.findView(PROBLEM_VIEW_ID);
        if (view == null)
        {
            show();
            view = page.findView(PROBLEM_VIEW_ID);
        }
        boolean wholeProject = objects == null || objects.isEmpty();
        if (view == null || ProblemViewHook.scopeIntersectsChecked(view, project, wholeProject ? null : topIds))
        {
            show();
            return;
        }
        if (wholeProject)
            selectProjectInNavigator(page, project);
        else
            openEditor(page, objects.iterator().next());
        // Панель берёт область из выделения активированной части — выводить её после
        Display.getDefault().asyncExec(ProblemViewMarkers::show);
    }

    private static void openEditor(IWorkbenchPage page, EObject object)
    {
        try
        {
            new OpenHelper(page).openEditor(object);
        }
        catch (RuntimeException ignored)
        {
        }
    }

    private static void selectProjectInNavigator(IWorkbenchPage page, IProject project)
    {
        IViewPart navigator;
        try
        {
            navigator = page.showView(Global.NAVIGATOR_VIEW_ID);
        }
        catch (PartInitException e)
        {
            return;
        }
        if (navigator instanceof CommonNavigator common && !project.equals(ActiveProjectTracker.getNavigatorProject(page)))
            common.selectReveal(new StructuredSelection(project));
    }

    private static void switchScopeToCurrentObject()
    {
        try
        {
            ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
            IHandlerService handlerService = PlatformUI.getWorkbench().getService(IHandlerService.class);
            if (commandService == null || handlerService == null)
                return;
            Command command = commandService.getCommand(SCOPE_COMMAND_ID);
            Map<String, Object> parameters = new HashMap<>();
            parameters.put(SCOPE_PARAMETER_ID, SCOPE_CURRENT_OBJECT);
            ParameterizedCommand parameterizedCommand = ParameterizedCommand.generateCommand(command, parameters);
            handlerService.executeCommand(parameterizedCommand, null);
        }
        catch (Exception ignored)
        {
        }
    }

    /** Первый выделенный в панели маркер или {@code null}. */
    public static Marker firstSelectedMarker(IWorkbenchPart part)
    {
        Stream<?> markers = selectedMarkers(part);
        if (markers == null)
            return null;
        return markers.filter(Marker.class::isInstance).map(Marker.class::cast).findFirst().orElse(null);
    }

    /** Выделенные в панели маркеры или {@code null}, если это не панель «Ошибки конфигурации». */
    public static Stream<?> selectedMarkers(IWorkbenchPart part)
    {
        Object result = Global.invoke(part, "selectedMarkers"); //$NON-NLS-1$
        return result instanceof Stream<?> stream ? stream : null;
    }

    /**
     * Текущий отбор панели — ровно тот, по которому построено видимое дерево ошибок
     * (severity, тип проблемы, область, подсистемы, строка поиска).
     */
    public static MarkerFilter currentFilter(IWorkbenchPart part)
    {
        Object result = Global.invoke(part, "getMarkerFilter"); //$NON-NLS-1$
        return result instanceof MarkerFilter filter ? ProblemViewHook.withCheckCode(part, filter) : null;
    }
}
