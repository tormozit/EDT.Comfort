package tormozit;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;

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
        return result instanceof MarkerFilter filter ? filter : null;
    }
}
