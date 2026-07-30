package tormozit;

import java.util.stream.Stream;

import org.eclipse.ui.IWorkbenchPart;

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

    private ProblemViewMarkers() {}

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
