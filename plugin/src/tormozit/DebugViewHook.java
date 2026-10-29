package tormozit;

import java.io.IOException;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdapterFactory;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.debug.internal.ui.model.elements.DebugElementLabelProvider;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementLabelProvider;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.graphics.TextLayout;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.debug.core.model.IDebugSession;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationEvent;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

/**
 * Дополнения панели «Отладка»: состояние метаданных приложения у сеанса (issue #883)
 * и удаление выбранного сеанса через RAC/RAS (issue #881).
 * Состояние базы берётся из IApplicationManager.getUpdateState(). Поколения
 * предметов сеанса (DebugTargetId.getConfigVersion()) сравниваются с generation-id
 * существующего агента конфигуратора. Предупреждение о сеансе имеет приоритет.
 *
 * <p>Адаптер конкретного класса сеанса имеет приоритет перед общим адаптером
 * IDebugElement. Штатный DebugElementLabelProvider Eclipse debug.ui
 * 3.18.200.v20231106-1112 сохраняет текст, изображения, цвета и шрифт EDT.
 */
@SuppressWarnings("restriction")
public final class DebugViewHook implements IStartup, IAdapterFactory
{
    private static final IElementLabelProvider LABELS = new SessionLabels();
    private static volatile IApplicationManager applications;
    private static final String BASE_STALE = " (метаданные базы устарели)"; //$NON-NLS-1$
    private static final String SESSION_STALE = " (метаданные сеанса устарели)"; //$NON-NLS-1$
    private static final Map<IDebugSession, SessionStatus> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<IBslStackFrame, FrameLine> FRAME_LINES =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Class<?>> REGISTERED_SESSION_TYPES = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean DEBUG_DIAGNOSTICS_INSTALLED = new AtomicBoolean();
    private static final Set<UUID> DELETING_SESSIONS = ConcurrentHashMap.newKeySet();

    @Override
    public void earlyStartup()
    {
        installDebugDiagnostics();
        installApplicationListener();
    }

    @Override
    public <T> T getAdapter(Object adaptableObject, Class<T> adapterType)
    {
        if (adapterType != IElementLabelProvider.class
            || (!(adaptableObject instanceof IDebugSession) && !(adaptableObject instanceof IBslStackFrame)))
            return null;
        installApplicationListener();
        return adapterType.cast(LABELS);
    }

    private static void installDebugDiagnostics()
    {
        DebugPlugin debug = DebugPlugin.getDefault();
        if (debug == null || !DEBUG_DIAGNOSTICS_INSTALLED.compareAndSet(false, true))
            return;
        debug.addDebugEventListener(events ->
        {
            for (DebugEvent event : events)
            {
                Object source = event.getSource();
                if (event.getKind() == DebugEvent.RESUME || event.getKind() == DebugEvent.TERMINATE)
                    synchronized (FRAME_LINES)
                    {
                        FRAME_LINES.keySet().removeIf(frame -> frame.getThread() == source
                            || frame.getDebugTarget() == source);
                    }
                if (source instanceof IDebugElement element
                    && element.getDebugTarget() instanceof IRuntimeDebugClientTarget target
                    && !target.isTerminated())
                {
                    if (source instanceof IRuntimeDebugTargetThread thread
                        && thread.getThreadGroup() instanceof IDebugSession session)
                        diagnoseGenerations(session, target);
                    else
                        diagnoseTarget(target);
                }
            }
        });
        for (var launch : debug.getLaunchManager().getLaunches())
            for (var target : launch.getDebugTargets())
                if (target instanceof IRuntimeDebugClientTarget runtime && !runtime.isTerminated())
                    diagnoseTarget(runtime);
    }

    private static void diagnoseTarget(IRuntimeDebugClientTarget target)
    {
        try
        {
            var groups = target.getThreadGroups();
            for (var group : groups)
                if (group instanceof IDebugSession session)
                    diagnoseGenerations(session, target);
        }
        catch (Exception e)
        {
        }
    }

    @Override
    public Class<?>[] getAdapterList()
    {
        return new Class<?>[] { IElementLabelProvider.class };
    }

    private static synchronized void installApplicationListener()
    {
        if (applications != null)
            return;
        IApplicationManager manager = Global.getOsgiService(IApplicationManager.class);
        if (manager == null)
            return;
        manager.addAppllicationListener(DebugViewHook::applicationChanged);
        applications = manager;
    }

    private static void applicationChanged(IApplicationEvent event)
    {
        if (event.getEventType() != IApplicationEvent.ApplicationEventType.UPDATE_STATE_CHANGED
            && event.getEventType() != IApplicationEvent.ApplicationEventType.LIFECYCLE_STATE_CHANGED)
            return;
        DebugPlugin debug = DebugPlugin.getDefault();
        if (debug == null)
            return;
        IApplication changed = event.getApplication();
        List<DebugEvent> updates = new ArrayList<>();
        for (var launch : debug.getLaunchManager().getLaunches())
        {
            for (var debugTarget : launch.getDebugTargets())
            {
                if (!(debugTarget instanceof IRuntimeDebugClientTarget target) || target.isTerminated())
                    continue;
                try
                {
                    IApplication application = target.getApplication().orElse(null);
                    if (application != null && changed != null
                        && application.getProject().equals(changed.getProject())
                        && application.getId().equals(changed.getId()))
                    {
                        for (var group : target.getThreadGroups())
                        {
                            SessionStatus status = STATES.get(group);
                            if (status != null)
                                synchronized (status)
                                {
                                    if (status.stale)
                                        continue;
                                    status.revision++;
                                    status.signature = null;
                                    status.suffix = ""; //$NON-NLS-1$
                                }
                        }
                        diagnoseTarget(target);
                        // DebugTargetEventHandler переводит CHANGE/CONTENT в дельту CONTENT:
                        // штатный viewer заново запрашивает подписи дочерних сеансов.
                        updates.add(new DebugEvent(target, DebugEvent.CHANGE, DebugEvent.CONTENT));
                    }
                }
                catch (CoreException | RuntimeException e)
                {
                    // Закрытие проекта/подключения не должно прерывать уведомление приложения.
                }
            }
        }
        if (!updates.isEmpty())
            debug.fireDebugEventSet(updates.toArray(DebugEvent[]::new));
    }

    /** Запрос в фоне; отрисовка и повторные события используют уже полученный результат. */
    private static void diagnoseGenerations(IDebugSession session, IRuntimeDebugClientTarget target)
    {
        if (REGISTERED_SESSION_TYPES.add(session.getClass()))
        {
            Platform.getAdapterManager().registerAdapters(new DebugViewHook(), session.getClass());
        }
        SessionStatus status;
        synchronized (STATES)
        {
            status = STATES.computeIfAbsent(session, ignored -> new SessionStatus());
        }
        synchronized (status)
        {
            if (status.stale)
                return;
        }
        List<String> versions = new ArrayList<>();
        try
        {
            for (var thread : session.getThreads())
            {
                if (thread.isTerminated() || thread.isDisconnected())
                    continue;
                String version = thread.getRuntimeDebugTarget().getConfigVersion();
                if (version != null && !version.isBlank())
                    versions.add(version);
            }
        }
        catch (CoreException | RuntimeException e)
        {
            return;
        }
        String signature = versions.toString();
        long revision;
        synchronized (status)
        {
            if (status.stale || status.pending || signature.equals(status.signature))
            {
                return;
            }
            status.pending = true;
            revision = status.revision;
        }
        Job job = Job.create("Комфорт: проверка поколений метаданных", monitor -> //$NON-NLS-1$
        {
            String suffix = ""; //$NON-NLS-1$
            try
            {
                IApplication application = target.getApplication().orElse(null);
                IApplicationManager manager = applications;
                ApplicationUpdateState state = manager != null && application != null
                    ? manager.getUpdateState(application) : ApplicationUpdateState.UNKNOWN;
                boolean baseStale = state == ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED
                    || state == ApplicationUpdateState.FULL_UPDATE_REQUIRED;
                if (baseStale)
                    suffix = BASE_STALE;
                if (!(application instanceof IInfobaseApplication infobaseApplication))
                {
                    return Status.OK_STATUS;
                }
                var connections = DesignerSessionPoolAccessor.getInstance()
                    .findExistingConnections(infobaseApplication.getInfobase());
                List<Object> active = new ArrayList<>();
                for (Object connection : connections)
                {
                    if (!Boolean.TRUE.equals(connection.getClass().getMethod("isAlive").invoke(connection))) //$NON-NLS-1$
                    {
                        continue;
                    }
                    active.add(connection);
                }
                // Несколько агентов могут держать разные поколения: произвольный не выбираем.
                if (active.size() == 1)
                {
                    Object generation = readAgentGeneration(active.get(0));
                    if (generation instanceof String actual && !actual.isBlank()
                        && versions.stream().anyMatch(version -> !version.equals(actual)))
                        suffix = SESSION_STALE;
                }
            }
            catch (Exception e)
            {
            }
            finally
            {
                boolean current;
                boolean stale;
                synchronized (status)
                {
                    status.pending = false;
                    current = revision == status.revision;
                    stale = status.stale |= SESSION_STALE.equals(suffix);
                    if (stale)
                        status.suffix = SESSION_STALE;
                    if (current)
                    {
                        status.signature = signature;
                        if (!stale)
                            status.suffix = suffix;
                    }
                }
                Global.tempLog("debug-source", "session generation result stale=" + stale);
                if (!current && !stale)
                    diagnoseGenerations(session, target);
                else if (!target.isTerminated())
                    DebugPlugin.getDefault().fireDebugEventSet(new DebugEvent[] {
                        new DebugEvent(target, DebugEvent.CHANGE, DebugEvent.CONTENT) });
            }
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    /** Тот же результат проверки поколения, что используется для подписи сеанса. Без запроса к агенту. */
    static boolean isSessionMetadataStale(IBslStackFrame frame)
    {
        if (!(frame.getThread().getThreadGroup() instanceof IDebugSession session))
            return false;
        SessionStatus status = STATES.get(session);
        if (status == null)
            return false;
        synchronized (status)
        {
            return status.stale;
        }
    }

    /** Факт устаревания необратим в пределах сеанса: дальнейшие запросы поколения не нужны. */
    static void markSessionMetadataStale(IBslStackFrame frame)
    {
        if (!(frame.getThread().getThreadGroup() instanceof IDebugSession session))
            return;
        SessionStatus status = STATES.computeIfAbsent(session, ignored -> new SessionStatus());
        boolean changed;
        synchronized (status)
        {
            changed = !status.stale;
            status.stale = true;
            status.suffix = SESSION_STALE;
        }
        Global.tempLog("debug-source", "session stale latched changed=" + changed);
        if (changed)
            DebugPlugin.getDefault().fireDebugEventSet(new DebugEvent[] {
                new DebugEvent(frame.getDebugTarget(), DebugEvent.CHANGE, DebugEvent.CONTENT) });
    }

    private static final class SessionStatus
    {
        private long revision;
        private boolean pending;
        private boolean stale;
        private String signature;
        private volatile String suffix = ""; //$NON-NLS-1$
    }

    /** Команда относится только к узлу «Сеанс», а не к его предметам или серверному подключению. */
    static void installSessionMenu(Tree tree)
    {
        String key = "tormozit.debug.rasSessionMenu"; //$NON-NLS-1$
        if (Boolean.TRUE.equals(tree.getData(key)))
            return;
        tree.setData(key, Boolean.TRUE);
        tree.addListener(SWT.MenuDetect, event ->
        {
            Menu menu = tree.getMenu();
            if (menu == null || menu.isDisposed() || Boolean.TRUE.equals(menu.getData(key)))
                return;
            menu.setData(key, Boolean.TRUE);
            menu.addMenuListener(new MenuAdapter()
            {
                private MenuItem item;

                @Override
                public void menuShown(MenuEvent event)
                {
                    if (item != null && !item.isDisposed())
                        item.dispose();
                    TreeItem[] selection = tree.getSelection();
                    if (selection.length != 1 || !(selection[0].getData() instanceof IDebugSession session))
                        return;
                    item = new MenuItem(menu, SWT.PUSH);
                    item.setText("Удалить сеанс");
                    item.setImage(PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_TOOL_DELETE));
                    ComfortSubmenuHelper.setMenuItemTooltip(item,
                        "Принудительно завершить выбранный сеанс через RAC и сервис администрирования RAS. "
                            + "Параметры задаются на вкладке «RAS» конфигурации отладки.");
                    UUID uuid = session.getSessionUuid();
                    item.setEnabled(uuid != null && !DELETING_SESSIONS.contains(uuid));
                    item.addListener(SWT.Selection, selected -> deleteSession(tree.getShell(), session));
                }

                @Override
                public void menuHidden(MenuEvent event)
                {
                    MenuItem previous = item;
                    item = null;
                    menu.getDisplay().asyncExec(() ->
                    {
                        if (previous != null && !previous.isDisposed())
                            previous.dispose();
                    });
                }
            });
        });
    }

    private static void deleteSession(Shell shell, IDebugSession session)
    {
        try
        {
            ILaunchConfiguration configuration = session.getLaunch().getLaunchConfiguration();
            if (configuration == null)
                throw new IOException("У сеанса нет конфигурации подключения к серверу отладки.");
            // Серверный хук использует рабочую копию запуска. После «Применить» нужны актуальные
            // параметры сохранённой конфигурации, а не её снимок на момент подключения отладчика.
            configuration = DebugSessionRasTab.savedConfiguration(configuration);
            String configurationProblem = DebugSessionRasTab.validationProblem(configuration);
            Global.tempLog("debug-session-rac", "command configuration=" + configuration.getName()
                + " validation=" + (configurationProblem != null ? configurationProblem : "OK")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            if (configurationProblem != null)
            {
                DebugSessionRasTab.requestFocus(configuration);
                try
                {
                    DebugUITools.openLaunchConfigurationDialogOnGroup(shell,
                        new StructuredSelection(configuration), IDebugUIConstants.ID_DEBUG_LAUNCH_GROUP,
                        new Status(org.eclipse.core.runtime.IStatus.WARNING, "tormozit.comfort", //$NON-NLS-1$
                            "Удаление сеанса не выполнено: " + configurationProblem));
                }
                finally
                {
                    DebugSessionRasTab.clearFocusRequest();
                }
                return;
            }
            UUID uuid = session.getSessionUuid();
            BigDecimal number = session.getSessionId();
            if (uuid == null || number == null)
                throw new IOException("EDT не передала UUID и номер выбранного сеанса.");
            IApplication application = session.getDebugTarget() instanceof IRuntimeDebugClientTarget target
                ? target.getApplication().orElse(null) : null;
            if (!(application instanceof IInfobaseApplication infobaseApplication))
                throw new IOException("Не удалось определить приложение выбранного сеанса.");
            RacSessionClient connection = new RacSessionClient(
                application.getProject(), infobaseApplication.getInfobase(),
                DebugSessionRasTab.normalizedAddress(configuration.getAttribute(DebugSessionRasTab.ADDRESS, "")), //$NON-NLS-1$
                configuration.getAttribute(DebugSessionRasTab.USER, "").trim(), //$NON-NLS-1$
                configuration.getAttribute(DebugSessionRasTab.PASSWORD, "")); //$NON-NLS-1$
            if (!DELETING_SESSIONS.add(uuid))
                return;
            var display = shell.getDisplay();
            Job job = Job.create("Удаление сеанса " + number.toPlainString(), monitor ->
            {
                try
                {
                    connection.delete(uuid, number, monitor);
                    if (!display.isDisposed())
                        display.asyncExec(() ->
                        {
                            if (!shell.isDisposed())
                                ToastNotification.show("Удаление сеанса",
                                    "Сеанс " + number.toPlainString() + " отсутствует в RAS. Удаление подтверждено.",
                                    6000, null, null, shell);
                        });
                }
                catch (OperationCanceledException e)
                {
                    return Status.CANCEL_STATUS;
                }
                catch (Exception e)
                {
                    String message = connection.redact(e.getLocalizedMessage());
                    Global.tempLog("debug-session-rac", "delete failed: " + message); //$NON-NLS-1$ //$NON-NLS-2$
                    if (!display.isDisposed())
                        display.asyncExec(() ->
                        {
                            if (!shell.isDisposed())
                                MessageDialog.openError(shell, Global.withPluginWindowTitle("Удаление сеанса"), message);
                        });
                }
                finally
                {
                    DELETING_SESSIONS.remove(uuid);
                }
                return Status.OK_STATUS;
            });
            job.setUser(true);
            job.schedule();
        }
        catch (Exception e)
        {
            Global.tempLog("debug-session-rac", "prepare failed: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            MessageDialog.openError(shell, Global.withPluginWindowTitle("Удаление сеанса"), e.getLocalizedMessage());
        }
    }

    /** Красным рисуется только суффикс об устаревании сеанса. */
    static void installSuffixPainting(Tree tree)
    {
        String key = "tormozit.debug.metadataSuffixPainting"; //$NON-NLS-1$
        if (Boolean.TRUE.equals(tree.getData(key)))
            return;
        tree.setData(key, Boolean.TRUE);
        tree.addListener(SWT.EraseItem, event ->
        {
            if (event.item instanceof TreeItem item && item.getData() instanceof IDebugSession
                && suffixStart(item.getText(event.index)) >= 0)
                event.detail &= ~SWT.FOREGROUND;
        });
        tree.addListener(SWT.PaintItem, event ->
        {
            if (!(event.item instanceof TreeItem item) || !(item.getData() instanceof IDebugSession))
                return;
            String text = item.getText(event.index);
            int start = suffixStart(text);
            if (start < 0)
                return;
            var oldForeground = event.gc.getForeground();
            TextLayout layout = new TextLayout(tree.getDisplay());
            try
            {
                // SWT.FOREGROUND управляет и текстом, и изображением строки.
                var image = item.getImage(event.index);
                if (image != null && !image.isDisposed())
                {
                    var imageBounds = item.getImageBounds(event.index);
                    event.gc.drawImage(image, imageBounds.x, imageBounds.y);
                }
                // SWT уже выбрал цвет текста с учётом темы, выделения и фокуса.
                // COLOR_LIST_SELECTION_TEXT не совпадает с ним в теме Explorer Windows.
                layout.setFont(item.getFont(event.index));
                layout.setText(text);
                layout.setStyle(new TextStyle(null, tree.getDisplay().getSystemColor(SWT.COLOR_RED), null),
                    start, text.length() - 1);
                var bounds = item.getTextBounds(event.index);
                layout.draw(event.gc, bounds.x, bounds.y);
            }
            finally
            {
                layout.dispose();
                event.gc.setForeground(oldForeground);
            }
        });
    }

    private static int suffixStart(String text)
    {
        if (text.endsWith(SESSION_STALE))
            return text.length() - SESSION_STALE.length();
        return -1;
    }

    /** Тот же захват DesignerAgentConnection.use, что у выгрузки в ProfilingViewHook. */
    private static Object readAgentGeneration(Object connection) throws Exception
    {
        Class<?> actionType = connection.getClass().getClassLoader().loadClass(
            "com._1c.g5.v8.dt.internal.platform.services.core.runtimes.execution." //$NON-NLS-1$
                + "DesignerAgentConnection$DesignerAgentConnectionAction"); //$NON-NLS-1$
        Method use = connection.getClass().getMethod("use", actionType); //$NON-NLS-1$
        Object action = Proxy.newProxyInstance(actionType.getClassLoader(), new Class<?>[] { actionType },
            (proxy, method, args) ->
            {
                if (method.getDeclaringClass() == Object.class)
                    return switch (method.getName())
                    {
                        case "toString" -> "Debug metadata generation query"; //$NON-NLS-1$ //$NON-NLS-2$
                        case "hashCode" -> System.identityHashCode(proxy); //$NON-NLS-1$
                        case "equals" -> proxy == args[0]; //$NON-NLS-1$
                        default -> null;
                    };
                Object designer = connection.getClass().getMethod("getSession").invoke(connection); //$NON-NLS-1$
                Object configure = designer.getClass().getMethod("configure").invoke(designer); //$NON-NLS-1$
                Object query = configure.getClass().getMethod("retrieveGenerationId").invoke(configure); //$NON-NLS-1$
                return query.getClass().getMethod("exec", Duration.class).invoke(query, Duration.ofSeconds(15)); //$NON-NLS-1$
            });
        return use.invoke(connection, action);
    }

    /** Номера — 1-based, как в подписи EDT; меняется только подпись, не сам кадр. */
    static void setCorrectedFrameLine(IBslStackFrame frame, int original, int corrected, boolean moduleChanged)
    {
        FrameLine replacement = moduleChanged ? new FrameLine(original, corrected) : null;
        FrameLine previous;
        synchronized (FRAME_LINES)
        {
            previous = replacement != null ? FRAME_LINES.put(frame, replacement) : FRAME_LINES.remove(frame);
        }
        if (java.util.Objects.equals(previous, replacement))
            return;
        Global.tempLog("debug-source", "stack line original=" + original + " corrected=" + corrected
            + " moduleChanged=" + moduleChanged);
        DebugPlugin.getDefault().fireDebugEventSet(new DebugEvent[] {
            new DebugEvent(frame, DebugEvent.CHANGE, DebugEvent.STATE) });
    }

    static void clearCorrectedFrameLine(IBslStackFrame frame)
    {
        setCorrectedFrameLine(frame, 0, 0, false);
    }

    private static String correctedFrameLineSuffix(IBslStackFrame frame) throws CoreException
    {
        FrameLine line = FRAME_LINES.get(frame);
        if (line == null || frame.isTerminated() || !frame.getThread().isSuspended()
            || isSessionMetadataStale(frame) || frame.getLineNumber() != line.original)
            return "";
        return " (" + line.corrected + ")";
    }

    private record FrameLine(int original, int corrected) {}

    private static final class SessionLabels extends DebugElementLabelProvider
    {
        @Override
        protected String getLabel(TreePath path, IPresentationContext context, String columnId)
            throws CoreException
        {
            String label = super.getLabel(path, context, columnId);
            if (label != null && path.getLastSegment() instanceof IBslStackFrame frame)
                return label + correctedFrameLineSuffix(frame);
            if (label == null || !(path.getLastSegment() instanceof IDebugSession session)
                || !(session.getDebugTarget() instanceof IRuntimeDebugClientTarget target))
                return label;
            diagnoseGenerations(session, target);
            SessionStatus status = STATES.get(session);
            return status != null ? label + status.suffix : label;
        }
    }
}
