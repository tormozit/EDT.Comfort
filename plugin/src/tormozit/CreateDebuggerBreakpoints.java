package tormozit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslBreakpointFactory;
import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslLineBreakpoint;

/**
 * Команда "Создать остановки отладчика" тулбара панели "Результаты поиска" — и заодно точка
 * координации между двумя независимыми хуками этой панели, {@link FileSearchResultsHook}
 * (текстовый поиск) и {@link ConfigSearchResultsHook} (поиск по конфигурации), там, где обоим
 * нужен единственный владелец общего ресурса панели:
 * <ul>
 * <li>{@link #installToolbarAction} — общая кнопка тулбара (идемпотентно, сработает только первый
 * вызов); оба хука предоставляют {@code currentBreakpointTargets(IViewPart)}, возвращающий
 * {@code null}, если их страница результатов сейчас не активна.</li>
 * <li>{@link #installGlobalCopyHandler} — общий {@code IHandlerService}-перехват
 * "org.eclipse.ui.edit.copy": если оба хука по отдельности активируют обработчик этой же команды
 * на этом же view, второй молча перебивает первый (даже когда фокус не на его таблице) — здесь
 * единственная активация на view, диспетчеризующая по тому, у чьей таблицы сейчас фокус.</li>
 * </ul>
 */
final class CreateDebuggerBreakpoints
{
    private static final String TOOLBAR_ACTION_ID = "tormozit.createDebuggerBreakpoints";
    /** Как {@code com._1c.g5.v8.dt.internal.debug.core.model.breakpoints.BslLineBreakpoint.getModelIdentifier()}. */
    private static final String BSL_DEBUG_MODEL_ID = "com._1c.g5.v8.dt.debug";

    private static final java.util.Set<IViewPart> COPY_HANDLER_INSTALLED =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private CreateDebuggerBreakpoints() {}

    /**
     * Единственная активация "org.eclipse.ui.edit.copy" на этом view — идемпотентно (повторные
     * вызовы для того же view игнорируются, независимо от того, кто из двух хуков вызвал первым).
     */
    static void installGlobalCopyHandler(IViewPart view)
    {
        if (view == null || view.getSite() == null)
            return;
        if (!COPY_HANDLER_INSTALLED.add(view))
            return;

        org.eclipse.ui.handlers.IHandlerService handlerService =
            view.getSite().getService(org.eclipse.ui.handlers.IHandlerService.class);
        if (handlerService == null)
        {
            try
            {
                handlerService = PlatformUI.getWorkbench().getService(org.eclipse.ui.handlers.IHandlerService.class);
            }
            catch (Exception e) { /* ignore */ }
        }
        if (handlerService == null)
        {
            COPY_HANDLER_INSTALLED.remove(view);
            return;
        }
        handlerService.activateHandler("org.eclipse.ui.edit.copy", //$NON-NLS-1$
            new org.eclipse.core.commands.AbstractHandler()
        {
            @Override
            public Object execute(org.eclipse.core.commands.ExecutionEvent event)
            {
                if (!ComfortSettings.isReplaceListFiltersEnabled())
                    return null;
                if (!FileSearchResultsHook.copyActiveCellIfFocused())
                    ConfigSearchResultsHook.copyActiveCellIfFocused();
                return null;
            }

            @Override
            public boolean isHandled()
            {
                return ComfortSettings.isReplaceListFiltersEnabled();
            }
        });
    }

    record Target(IFile file, int lineNumber) {}

    static void installToolbarAction(IViewPart view)
    {
        if (view == null || view.getViewSite() == null)
            return;
        IActionBars bars = view.getViewSite().getActionBars();
        if (bars == null)
            return;
        IToolBarManager toolBar = bars.getToolBarManager();
        if (toolBar.find(TOOLBAR_ACTION_ID) != null)
            return;

        IAction action = new Action("Создать остановки отладчика")
        {
            @Override
            public void run()
            {
                if (!ComfortSettings.isReplaceListFiltersEnabled())
                    return;
                List<Target> fileSearchTargets = FileSearchResultsHook.currentBreakpointTargets(view);
                List<Target> configSearchTargets = fileSearchTargets == null
                    ? ConfigSearchResultsHook.currentBreakpointTargets(view) : null;
                List<Target> targets = fileSearchTargets != null ? fileSearchTargets : configSearchTargets;
                if (targets == null || targets.isEmpty())
                {
                    ToastNotification.show("Точки останова", //$NON-NLS-1$
                        "Среди выделенного нет строк модулей (с номером строки) — нечего добавлять."); //$NON-NLS-1$
                    return;
                }
                Shell shell = view.getSite().getShell();
                createForTargets(targets, shell);
            }
        };
        action.setId(TOOLBAR_ACTION_ID);
        action.setImageDescriptor(DebugUITools.getImageDescriptor(IDebugUIConstants.IMG_OBJS_BREAKPOINT));
        action.setToolTipText("Создает одинаковые точки останова для всех выделенных строк модулей"
            + Global.pluginSignForTooltip());
        toolBar.add(new Separator());
        toolBar.add(action);
        bars.updateActionBars();
    }

    static void createForTargets(List<Target> targets, Shell shell)
    {
        List<Target> valid = dedupe(targets);
        if (valid.isEmpty())
        {
            ToastNotification.show("Точки останова", //$NON-NLS-1$
                "Среди выделенного нет строк BSL-модулей — нечего добавлять " //$NON-NLS-1$
                + "(макеты/шаблоны и прочие не-BSL файлы пропускаются)."); //$NON-NLS-1$
            return;
        }

        IBslBreakpointFactory factory = resolveFactory();
        if (factory == null)
            return;

        Target sampleTarget = valid.get(0);
        IBslLineBreakpoint sample;
        boolean sampleIsNew;
        try
        {
            List<IBslLineBreakpoint> sampleCreated = new ArrayList<>(1);
            sample = createOrReuse(factory, sampleTarget, sampleCreated);
            sampleIsNew = !sampleCreated.isEmpty();
            registerNewlyCreated(sampleCreated);
        }
        catch (Exception e)
        {
            log("createForTargets: sample create failed: " + e);
            return;
        }

        // Точка регистрируется ДО открытия диалога (без этого не работает страница свойств) —
        // на "Отмена" (или если страницу вообще не удалось открыть) её нужно убрать, если она была
        // только что создана нами (переиспользованную существующую точку — не трогать).
        PreferenceDialog dialog = PreferencesUtil.createPropertyDialogOn(shell, sample, null, null, null);
        if (dialog == null)
        {
            deleteIfNew(sample, sampleIsNew);
            return;
        }
        int result = dialog.open();
        if (result != Window.OK)
        {
            deleteIfNew(sample, sampleIsNew);
            return;
        }

        // Точки создаются по одной (нужен CoreException на каждую отдельно), но регистрируются
        // в IBreakpointManager ОДНИМ пакетным addBreakpoints() — по одной (addBreakpoint в цикле)
        // панель "Точки останова" не пересортировывает список полностью на каждой вставке.
        List<IBslLineBreakpoint> newlyCreated = new ArrayList<>();
        List<IBslLineBreakpoint> created = new ArrayList<>();
        created.add(sample);
        for (int i = 1; i < valid.size(); i++)
        {
            Target target = valid.get(i);
            try
            {
                IBslLineBreakpoint bp = createOrReuse(factory, target, newlyCreated);
                if (bp != sample)
                    copySettings(sample, bp);
                created.add(bp);
            }
            catch (CoreException e)
            {
                log("createForTargets: apply failed for " + target.file() + ":" + target.lineNumber() + " " + e);
            }
        }
        registerNewlyCreated(newlyCreated);

        revealInBreakpointsView(created);
    }

    private static void registerNewlyCreated(List<IBslLineBreakpoint> newlyCreated)
    {
        if (newlyCreated.isEmpty())
            return;
        try
        {
            DebugPlugin.getDefault().getBreakpointManager()
                .addBreakpoints(newlyCreated.toArray(new IBreakpoint[0]));
        }
        catch (CoreException e)
        {
            log("registerNewlyCreated: " + e);
        }
    }

    /** Удаляет только что созданный (не переиспользованный) сэмпл при отмене/неудаче диалога свойств. */
    private static void deleteIfNew(IBslLineBreakpoint breakpoint, boolean isNew)
    {
        if (!isNew)
            return;
        try
        {
            breakpoint.delete();
        }
        catch (CoreException e)
        {
            log("deleteIfNew: " + e);
        }
    }

    /** Открывает панель "Точки останова", очищает там выделение и выделяет все созданные/применённые точки. */
    private static void revealInBreakpointsView(List<IBslLineBreakpoint> breakpoints)
    {
        try
        {
            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            if (page == null)
                return;
            IViewPart breakpointsView = page.showView(IDebugUIConstants.ID_BREAKPOINT_VIEW);
            ISelectionProvider selectionProvider = breakpointsView.getSite().getSelectionProvider();
            if (selectionProvider == null)
                return;
            selectionProvider.setSelection(StructuredSelection.EMPTY);
            // Панель могла ещё не отразить только что созданные точки (обновление по маркерам —
            // асинхронное), поэтому сразу после showView() выделение может не применится — повторяем.
            trySelectInBreakpointsView(selectionProvider, breakpoints, 0);
        }
        catch (PartInitException e)
        {
            log("revealInBreakpointsView: " + e);
        }
    }

    private static void trySelectInBreakpointsView(ISelectionProvider selectionProvider,
        List<IBslLineBreakpoint> breakpoints, int attempt)
    {
        StructuredSelection selection = new StructuredSelection(breakpoints);
        selectionProvider.setSelection(selection);
        if (selection.equals(selectionProvider.getSelection()) || attempt >= 20)
            return;
        org.eclipse.swt.widgets.Display display = org.eclipse.swt.widgets.Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(50, () -> trySelectInBreakpointsView(selectionProvider, breakpoints, attempt + 1));
    }

    private static List<Target> dedupe(List<Target> targets)
    {
        List<Target> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Target t : targets)
        {
            if (t == null || t.file() == null || t.lineNumber() <= 0)
                continue;
            // Точка останова BSL имеет смысл только для .bsl-модуля — для остальных файлов
            // (макеты .mxlx, шаблоны и т.п., где номер строки — это строка внутри их собственного
            // XML/иного формата, а не BSL-кода) BslBreakpointFactory её создать не может: 1С внутри
            // ищет BSL-модуль для файла и падает ("Compute breakpoint method signature") —
            // подтверждено логом (Target с .mxlx первым в списке).
            if (!"bsl".equalsIgnoreCase(t.file().getFileExtension())) //$NON-NLS-1$
                continue;
            String key = t.file().getFullPath() + "#" + t.lineNumber();
            if (seen.add(key))
                result.add(t);
        }
        return result;
    }

    static IBslBreakpointFactory resolveFactory()
    {
        try
        {
            Bundle bundle = Platform.getBundle("com._1c.g5.v8.dt.debug.core");
            if (bundle == null)
                return null;
            Class<?> pluginCls = bundle.loadClass("com._1c.g5.v8.dt.internal.debug.core.DebugCorePlugin");
            Object plugin = Global.invoke(pluginCls, "getDefault");
            Object injectorObj = Global.invoke(plugin, "getInjector");
            if (!(injectorObj instanceof com.google.inject.Injector injector))
                return null;
            // Injector.getInstance() имеет 2 одноаргументных перегрузки (Class<T> и Key<T>) —
            // Global.invoke(name, argc) не различает их и может молча (без исключения наружу)
            // попасть на getInstance(Key), поэтому здесь — типизированный вызов, а не Global.invoke.
            return injector.getInstance(IBslBreakpointFactory.class);
        }
        catch (Exception e)
        {
            log("resolveFactory: " + e);
            return null;
        }
    }

    /**
     * Создаёт точку останова, но НЕ регистрирует её в {@code IBreakpointManager} — только собирает
     * в {@code newlyCreatedOut} (для пакетного {@link #registerNewlyCreated}). Существующую (уже
     * зарегистрированную) точку в {@code newlyCreatedOut} не добавляет — повторная регистрация не
     * нужна. {@code BslBreakpointFactory.createLineBreakpoint()} создаёт маркер, но НЕ регистрирует
     * его в {@code IBreakpointManager} сама (это отдельный шаг у штатного {@code BslLineBreakpointAdapter}) —
     * без явного {@code addBreakpoint(s)} точка видна в редакторе (маркер есть), но не в панели
     * "Точки останова".
     */
    private static IBslLineBreakpoint createOrReuse(IBslBreakpointFactory factory, Target target,
        List<IBslLineBreakpoint> newlyCreatedOut) throws CoreException
    {
        IBslLineBreakpoint existing = findExisting(target.file(), target.lineNumber());
        if (existing != null)
            return existing;
        IBslLineBreakpoint created = factory.createLineBreakpoint(target.file(), target.lineNumber());
        newlyCreatedOut.add(created);
        return created;
    }

    private static IBslLineBreakpoint findExisting(IFile file, int lineNumber)
    {
        IBreakpointManager manager = DebugPlugin.getDefault().getBreakpointManager();
        for (IBreakpoint bp : manager.getBreakpoints(BSL_DEBUG_MODEL_ID))
        {
            if (!(bp instanceof IBslLineBreakpoint lineBp))
                continue;
            try
            {
                if (!file.equals(bp.getMarker().getResource()))
                    continue;
                if (lineBp.getLineNumber() == lineNumber)
                    return lineBp;
            }
            catch (CoreException ignored) {}
        }
        return null;
    }

    private static void copySettings(IBslLineBreakpoint from, IBslLineBreakpoint to) throws CoreException
    {
        to.setEnabled(from.isEnabled());
        to.setHitCount(from.getHitCount());
        to.setHitCondition(from.getHitCondition());
        if (from.isConditionalBreakpoint())
            to.setCondition(from.getCondition());
        to.setExpressionForEvaluation(from.getExpressionForEvaluation());
        to.setPutStackTrace(from.isPutStackTrace());
        to.setPutHitCount(from.isPutHitCount());
        to.setContinueExecution(from.getContinueExecution());
        to.setDescription(from.getDescription());
        to.setParentMethodToBreakOn(from.getParentMethodToBreakOn());
    }

    private static void log(String message)
    {
        Global.log("CreateDebuggerBreakpoints", message);
    }
}
