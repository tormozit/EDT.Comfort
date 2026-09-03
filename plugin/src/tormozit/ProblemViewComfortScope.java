package tormozit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jface.dialogs.IDialogSettings;

/**
 * Дополнительные области отбора «Отобранное в навигаторе» и «Активные наборы» для
 * панели «Проблемы конфигурации» (issue 462).
 *
 * <p>Радио добавляет в окно «Настройки отбора» {@link ProblemFiltersDialogHook};
 * применяет режим {@link ProblemViewHook}: подменяет поле {@code scopeSelection}
 * штатной панели синтетическим набором объектов и переводит штатную «Область
 * возникновения» в {@code CURRENT_ELEMENT} — по ней панель уже умеет отбирать
 * маркеры по конкретным объектам и их потомкам.
 *
 * <p>Прямой аналог группы «Область поиска» окна поиска по конфигурации
 * ({@link ConfigSearchDialogHook}): оба варианта не привязаны к штатному enum
 * {@code ProblemFilters.Scope}, принадлежность объекта определяется по владеющей
 * ссылке ({@link ObjectSetsItems#isUnderAnyOwnerRef}).
 *
 * <p>Отдельный файл, а не вложенный класс: два независимых потребителя — окно
 * настроек отбора и хук самой панели.
 */
final class ProblemViewComfortScope
{
    enum Mode
    {
        NONE, NAVIGATOR, ACTIVE_SETS
    }

    static final String NAVIGATOR_LABEL = "Отобранное в проекте навигатора"; //$NON-NLS-1$
    static final String ACTIVE_SETS_LABEL = "Активный набор проекта"; //$NON-NLS-1$

    private static final String SECTION = "ProblemViewComfortScope"; //$NON-NLS-1$
    private static final String KEY_MODE = "mode"; //$NON-NLS-1$

    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static volatile Mode mode;

    private ProblemViewComfortScope()
    {
    }

    static Mode mode()
    {
        Mode current = mode;
        if (current != null)
            return current;
        String saved = settings().get(KEY_MODE);
        Mode restored = Mode.NONE;
        if (saved != null)
        {
            try
            {
                restored = Mode.valueOf(saved);
            }
            catch (IllegalArgumentException ignored)
            {
                restored = Mode.NONE;
            }
        }
        mode = restored;
        return restored;
    }

    /** Сменить режим (персист + оповещение потребителей). */
    static void setMode(Mode next)
    {
        Mode target = next != null ? next : Mode.NONE;
        if (target == mode())
            return;
        mode = target;
        settings().put(KEY_MODE, target.name());
        for (Runnable listener : listeners)
        {
            try
            {
                listener.run();
            }
            catch (RuntimeException ignored)
            {
                // потребитель сам логирует через свой Debug при включённом логировании
            }
        }
    }

    static void addListener(Runnable listener)
    {
        if (listener != null && !listeners.contains(listener))
            listeners.add(listener);
    }

    /**
     * Владеющие ссылки объектов текущей области, по проектам (ключ — имя проекта).
     * Пустая карта — область сузить нечем (в навигаторе нет активного отбора либо
     * нет активных наборов).
     */
    static Map<String, List<String>> collectRefs()
    {
        Mode current = mode();
        if (current == Mode.NAVIGATOR)
        {
            Map<String, List<String>> refs = ObjectSetSubsystemsFilterBridge.visibleOwnerRefsByProject();
            return refs != null ? refs : Map.of();
        }
        if (current == Mode.ACTIVE_SETS)
        {
            Map<String, List<String>> refs = new HashMap<>();
            for (String project : ObjectSetsAddTargetState.getInstance().projectsWithAddTarget())
            {
                List<String> list = ObjectSetsItems.addTargetOwnerRefs(project);
                if (list != null && !list.isEmpty())
                    refs.put(project, list);
            }
            return refs;
        }
        return Map.of();
    }

    private static IDialogSettings settings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SECTION);
        if (section == null)
            section = top.addNewSection(SECTION);
        return section;
    }
}
