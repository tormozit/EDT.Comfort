package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com._1c.g5.v8.derived.DerivedDataStatus;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.derived.IDerivedDataStatusListener;
import com._1c.g5.v8.derived.pipeline.PipelineSegmentStatus;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;

/**
 * Свежесть полнотекстового индекса EDT ({@code TextSearchIndex}) на вкладке «Поиск в
 * конфигурациях» — других вкладок окна «Поиск», которые им пользуются, нет. Индекс считается
 * производными данными проекта: сегменты {@code FTS_INDEXING_SEGMENT}/{@code FTS_CLEANER_SEGMENT};
 * их остаток работы — из {@code IDerivedDataManager} (статус и слушатель статуса). Пока индекс
 * обновляется — крутилка как в панели «Проблемы конфигурации».
 */
final class IndexFreshness
{
    private static final Set<String> SEGMENTS = Set.of("FTS_INDEXING_SEGMENT", "FTS_CLEANER_SEGMENT"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final int POLL_MS = 300;
    private static final String TOOLTIP = "Поиск в конфигурации ищет не в самих модулях и свойствах, а в полнотекстовом"
        + " индексе EDT. Индекс обновляется в фоне после каждого изменения. Пока он обновляется, поиск может не найти"
        + " недавно добавленный текст, найти уже изменённый или показать вхождение по старому месту в тексте."
        + " Чтобы результаты были точными, дождитесь окончания обновления и повторите поиск";

    private static final String REFACTORING_TOOLTIP = "Вхождения в комментариях, строковых литералах и текстах в модели"
        + " EDT ищет по полнотекстовому индексу. Пока индекс обновляется (например, после правки модуля), часть"
        + " вхождений может не найтись. Чтобы список был полным, дождитесь окончания обновления и повторите"
        + " рефакторинг";
    /** Данные окна мастера: индекс отставал в какой-то момент с открытия окна. */
    private static final String STALE_SEEN_KEY = "tormozit.indexFreshnessStaleSeen"; //$NON-NLS-1$
    private static final String WATCHED_KEY = "tormozit.indexFreshnessWatched"; //$NON-NLS-1$
    private static final int WATCH_POLL_MS = 200;

    private final Label label;
    /** Окно мастера рефакторинга; {@code null} — вкладка поиска по конфигурации. */
    private final Shell shell;
    private final String tooltip;
    private final Map<IDerivedDataManager, IDerivedDataStatusListener> listeners = new HashMap<>();
    private WaitSpinner spinner;
    private boolean refreshPending;

    private IndexFreshness(Label label, Shell shell, String tooltip)
    {
        this.label = label;
        this.shell = shell;
        this.tooltip = tooltip;
    }

    /**
     * Мастер рефакторинга ищет вхождения в момент открытия, а страница предпросмотра появляется
     * позже: индекс мог отставать только при поиске и успеть обновиться. Поэтому отставание
     * запоминается на окне с первого показа, а подпись на странице читает этот признак.
     */
    static void watch(Shell shell)
    {
        if (shell == null || shell.isDisposed() || shell.getData(WATCHED_KEY) != null)
            return;
        shell.setData(WATCHED_KEY, Boolean.TRUE);
        Display display = shell.getDisplay();
        display.timerExec(0, new Runnable()
        {
            @Override
            public void run()
            {
                if (shell.isDisposed())
                {
                    return;
                }
                List<IDerivedDataManager> all = managers();
                int[] remaining = new int[1];
                boolean busy = busy(all, remaining);
                if (busy)
                    shell.setData(STALE_SEEN_KEY, Boolean.TRUE);
                display.timerExec(WATCH_POLL_MS, this);
            }
        });
    }

    /** Подпись состояния индекса в нижней строке страницы предпросмотра рефакторинга. */
    static void installInRefactoring(Composite root, Shell shell)
    {
        if (root == null || root.isDisposed() || !(root.getLayout() instanceof GridLayout grid))
            return;
        install(root, grid, shell, REFACTORING_TOOLTIP);
    }

    static void install(Object page)
    {
        if (!(Global.invoke(page, "getControl") instanceof Composite root) || root.isDisposed() //$NON-NLS-1$
            || !(root.getLayout() instanceof GridLayout grid))
            return;
        install(root, grid, null, TOOLTIP);
    }

    private static void install(Composite root, GridLayout grid, Shell shell, String tooltip)
    {
        Label label = new Label(root, SWT.NONE);
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, grid.numColumns, 1));
        label.setToolTipText(TooltipText.wrap(label, tooltip + Global.pluginSignForTooltip()));
        IndexFreshness freshness = new IndexFreshness(label, shell, tooltip);
        freshness.listen();
        label.addDisposeListener(e -> freshness.unlisten());
        freshness.startPolling();
        if (shell == null)
            label.getDisplay().timerExec(500, () -> dumpLayout(label));
    }

    // #region agent log
    /** Временная диагностика: где в окне «Поиск» стоят подпись индекса и флажок «Открыть результат…». */
    private static void dumpLayout(Label label)
    {
        if (label.isDisposed())
            return;
        StringBuilder sb = new StringBuilder("label parents:\n");
        for (Composite c = label.getParent(); c != null; c = c.getParent())
            sb.append("  ").append(c.getClass().getName()).append(" layout=").append(describeLayout(c))
                .append(" bounds=").append(c.getBounds()).append('\n');
        Shell sh = label.getShell();
        sb.append("label bounds=").append(label.getBounds()).append(" data=").append(label.getLayoutData())
            .append("\ntree:\n");
        dumpTree(sh, 0, sb);
        Global.tempLog("indexFreshnessLayout", sb.toString());
    }

    private static String describeLayout(Composite c)
    {
        return c.getLayout() instanceof GridLayout g ? "Grid cols=" + g.numColumns + " eq=" + g.makeColumnsEqualWidth
            : String.valueOf(c.getLayout());
    }

    private static void dumpTree(Composite parent, int depth, StringBuilder sb)
    {
        if (depth > 7)
            return;
        for (org.eclipse.swt.widgets.Control child : parent.getChildren())
        {
            sb.append("  ".repeat(depth)).append(child.getClass().getSimpleName()).append(" b=")
                .append(child.getBounds()).append(" data=").append(child.getLayoutData());
            if (child instanceof org.eclipse.swt.widgets.Button b)
                sb.append(" text='").append(b.getText()).append('\'');
            if (child instanceof Label l)
                sb.append(" text='").append(l.getText()).append('\'');
            if (child instanceof Composite c)
                sb.append(" layout=").append(describeLayout(c));
            sb.append('\n');
            if (child instanceof Composite c && !(child instanceof org.eclipse.swt.widgets.Table)
                && !(child instanceof org.eclipse.swt.widgets.Tree) && !(child instanceof org.eclipse.swt.widgets.Group))
                dumpTree(c, depth + 1, sb);
        }
    }
    // #endregion

    /** Начало отставания индекса событием статуса не сообщается — опрос, пока окно открыто. */
    private void startPolling()
    {
        Display display = label.getDisplay();
        display.timerExec(0, new Runnable()
        {
            @Override
            public void run()
            {
                if (label.isDisposed())
                    return;
                refresh();
                display.timerExec(POLL_MS, this);
            }
        });
    }

    private void listen()
    {
        for (IDerivedDataManager manager : managers())
        {
            IDerivedDataStatusListener listener = status ->
            {
                scheduleRefresh();
            };
            manager.addStatusListener(listener);
            listeners.put(manager, listener);
        }
    }

    private void unlisten()
    {
        listeners.forEach(IDerivedDataManager::removeStatusListener);
        listeners.clear();
    }

    /** Слушатель зовётся из потоков конвейера часто — обновляем подпись не чаще раза за проход UI. */
    private void scheduleRefresh()
    {
        synchronized (this)
        {
            if (refreshPending)
                return;
            refreshPending = true;
        }
        Display display = label.isDisposed() ? null : label.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            synchronized (this)
            {
                refreshPending = false;
            }
            refresh();
        });
    }

    private void refresh()
    {
        if (label.isDisposed())
            return;
        int[] remainingBox = new int[1];
        boolean busy = busy(listeners.keySet(), remainingBox);
        int remaining = remainingBox[0];
        boolean refactoring = shell != null;
        if (refactoring && busy && !shell.isDisposed())
            shell.setData(STALE_SEEN_KEY, Boolean.TRUE);
        boolean staleSeen = refactoring && !shell.isDisposed() && shell.getData(STALE_SEEN_KEY) != null;
        String text = busy ? (remaining > 0 ? "Полнотекстовый индекс обновляется, осталось: " + remaining
                : "Полнотекстовый индекс обновляется")
                + (refactoring ? ": вхождения в комментариях и текстах могут быть найдены не все" : "")
            : staleSeen ? "Индекс отставал при поиске вхождений: в комментариях и текстах могли найтись не все."
                + " Закройте окно и повторите рефакторинг"
            : "Полнотекстовый индекс актуален";
        if (!text.equals(label.getText()))
        {
            label.setText(text);
            label.getParent().layout(true, true);
        }
        if (busy && (spinner == null || spinner.isDisposed()))
            spinner = WaitSpinner.attach(label, tooltip);
        if (spinner != null)
            spinner.setActive(busy);
    }

    /**
     * Индекс отстаёт, если сегмент не досчитан или у него есть остаток работы.
     * Отставание после сохранения модуля: сегмент не досчитан, а карта активных сегментов при этом
     * пуста и событий статуса нет (лог 17.10.2026, ~17 с).
     */
    private static boolean busy(Iterable<IDerivedDataManager> managers, int[] remaining)
    {
        boolean busy = false;
        for (IDerivedDataManager manager : managers)
        {
            if (!manager.isComputed(SEGMENTS.toArray(String[]::new)))
                busy = true;
            DerivedDataStatus status = manager.getDerivedDataStatus();
            Map<String, PipelineSegmentStatus> active = status != null ? status.getActivePipelineSegments() : null;
            if (active == null)
                continue;
            for (Map.Entry<String, PipelineSegmentStatus> segment : active.entrySet())
                if (SEGMENTS.contains(segment.getKey()) && segment.getValue() != null
                    && segment.getValue().getRemainingUnitsOfWork() > 0)
                {
                    busy = true;
                    remaining[0] += segment.getValue().getRemainingUnitsOfWork();
                }
        }
        return busy;
    }

    private static List<IDerivedDataManager> managers()
    {
        List<IDerivedDataManager> managers = new ArrayList<>();
        IV8ProjectManager projects = Global.getOsgiService(IV8ProjectManager.class);
        IDerivedDataManagerProvider provider = Global.getOsgiService(IDerivedDataManagerProvider.class);
        if (projects == null || provider == null)
            return managers;
        for (IV8Project project : projects.getProjects())
        {
            IProject workspaceProject = project.getProject();
            IDerivedDataManager manager = workspaceProject != null && workspaceProject.isAccessible()
                ? provider.get(workspaceProject) : null;
            if (manager != null && !managers.contains(manager))
            {
                managers.add(manager);
            }
        }
        return managers;
    }
}
