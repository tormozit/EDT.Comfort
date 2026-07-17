package tormozit;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.Scheme;
import org.eclipse.jface.bindings.TriggerSequence;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.internal.keys.BindingService;
import org.eclipse.ui.keys.IBindingService;

/**
 * ВРЕМЕННАЯ диагностика: кто перехватывает Ctrl+Shift+F в редакторе модуля на разных РМ.
 * Лог — .tmp/temp-logs/ctrlShiftF.log (Global.tempLog, безусловно, без флажка «Общее логирование»).
 * Снять после разбора расхождения между C:\1C\EDT и C:\VC\EDT-plugin-WS (issue: Ctrl+Shift+F).
 */
public final class CtrlShiftFDiagHook implements IStartup
{
    private static final String TOPIC = "ctrlShiftF"; //$NON-NLS-1$

    private static final String TARGET_SEQUENCE_FORMAL = "M1+M2+F"; //$NON-NLS-1$

    private static volatile boolean armed;
    private static volatile long armedAt;
    private static boolean installed;
    private static boolean executionListenerInstalled;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null)
            return;
        display.asyncExec(CtrlShiftFDiagHook::install);
    }

    private static void install()
    {
        installDisplayFilter();
        installExecutionListener();
        Global.tempLog(TOPIC, "diag installed, filterInstalled=" + installed //$NON-NLS-1$
            + " executionListenerInstalled=" + executionListenerInstalled //$NON-NLS-1$
            + " pid=" + ProcessHandle.current().pid()); //$NON-NLS-1$

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        scheduleRepeatedScan(display, 0);
    }

    private static final int SCAN_INTERVAL_MS = 5000;
    private static final int SCAN_MAX_ATTEMPTS = 18; // ~90 сек — время открыть редактор/меню руками

    /**
     * Повторный скан раз в {@link #SCAN_INTERVAL_MS} на протяжении ~90 сек: пункт меню
     * с legacy-акселератором (Action.setAccelerator) может создаваться лениво — только
     * когда реально открыт редактор модуля/соответствующее меню.
     */
    private static void scheduleRepeatedScan(Display display, int attempt)
    {
        if (display == null || display.isDisposed() || attempt >= SCAN_MAX_ATTEMPTS)
            return;
        display.timerExec(SCAN_INTERVAL_MS, () ->
        {
            dumpMenuAccelerators();
            scheduleRepeatedScan(display, attempt + 1);
        });
    }

    /**
     * Ctrl+Shift+F может быть акселератором пункта меню, который на Windows перехватывается
     * ОС (WM_COMMAND) до попадания события в очередь Display — тогда SWT.KeyDown-фильтр
     * его вообще не видит. Обходим все открытые Shell и их деревья меню в поисках пункта
     * с этим акселератором — текст пункта прямо называет команду-победителя.
     */
    private static void dumpMenuAccelerators()
    {
        try
        {
            int target = SWT.CTRL | SWT.SHIFT | 'F';
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            Shell[] shells = display.getShells();
            int found = 0;
            for (Shell shell : shells)
            {
                if (shell == null || shell.isDisposed())
                    continue;
                String shellLabel = "menuBar[" + safeText(shell.getText()) + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                found += scanMenu(shell.getMenuBar(), target, shellLabel);
                found += scanControlContextMenus(shell, target,
                    "ctxMenu[" + safeText(shell.getText()) + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            // Компактная строка на каждый скан (без спама «не найдено») — прогресс видно
            // по числу shells; совпадения логируются отдельно, громко, в scanMenu.
            Global.tempLog(TOPIC, "menu scan: shells=" + shells.length //$NON-NLS-1$
                + " target=0x" + Integer.toHexString(target) //$NON-NLS-1$
                + " found=" + found); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.tempLog(TOPIC, "menu scan error=" + e); //$NON-NLS-1$
        }
    }

    /** Контекстные (popup) меню контролов не входят в menuBar — обходим дерево Composite отдельно. */
    private static int scanControlContextMenus(Control control, int targetAccelerator, String path)
    {
        if (control == null || control.isDisposed())
            return 0;
        int found = 0;
        Menu contextMenu = control.getMenu();
        if (contextMenu != null)
            found += scanMenu(contextMenu, targetAccelerator, path + " > [ctx:" + widgetBrief(control) + "]"); //$NON-NLS-1$ //$NON-NLS-2$
        if (control instanceof org.eclipse.swt.widgets.Composite composite)
        {
            for (Control child : composite.getChildren())
                found += scanControlContextMenus(child, targetAccelerator, path);
        }
        return found;
    }

    private static int scanMenu(Menu menu, int targetAccelerator, String path)
    {
        if (menu == null || menu.isDisposed())
            return 0;
        int found = 0;
        for (MenuItem item : menu.getItems())
        {
            if (item == null || item.isDisposed())
                continue;
            String itemPath = path + " > " + safeText(item.getText()); //$NON-NLS-1$
            if (item.getAccelerator() == targetAccelerator)
            {
                Global.tempLog(TOPIC, "  menu match text=\"" + safeText(item.getText()) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                    + " path=" + itemPath //$NON-NLS-1$
                    + " enabled=" + item.getEnabled() //$NON-NLS-1$
                    + " data=" + describeItemData(item)); //$NON-NLS-1$
                found++;
            }
            Menu sub = item.getMenu();
            if (sub != null)
                found += scanMenu(sub, targetAccelerator, itemPath);
        }
        return found;
    }

    private static String describeItemData(MenuItem item)
    {
        try
        {
            Object data = item.getData();
            if (data == null)
                return "null"; //$NON-NLS-1$
            String s = data.getClass().getName();
            Object action = Global.invoke(data, "getAction"); //$NON-NLS-1$
            if (action != null)
            {
                Object defId = Global.invoke(action, "getActionDefinitionId"); //$NON-NLS-1$
                s += " action=" + action.getClass().getName() + " actionDefinitionId=" + defId; //$NON-NLS-1$ //$NON-NLS-2$
            }
            Object command = Global.invoke(data, "getCommand"); //$NON-NLS-1$
            if (command != null)
            {
                Object elementId = Global.invoke(command, "getElementId"); //$NON-NLS-1$
                s += " command=" + command.getClass().getName() + " elementId=" + elementId; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return s;
        }
        catch (Exception e)
        {
            return "[error: " + e.getMessage() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static String safeText(String text)
    {
        if (text == null)
            return ""; //$NON-NLS-1$
        return text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static void installDisplayFilter()
    {
        if (installed)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        try
        {
            display.addFilter(SWT.KeyDown, CtrlShiftFDiagHook::onKeyDown);
            installed = true;
        }
        catch (Exception e)
        {
            Global.tempLog(TOPIC, "installDisplayFilter error=" + e); //$NON-NLS-1$
        }
    }

    private static void installExecutionListener()
    {
        if (executionListenerInstalled || PlatformUI.getWorkbench() == null)
            return;
        ICommandService commandService =
            PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        commandService.addExecutionListener(executionListener);
        executionListenerInstalled = true;
    }

    private static volatile long lastAnyProbeAt;
    private static volatile int anyProbeCount;

    private static void onKeyDown(Event event)
    {
        if (event.type != SWT.KeyDown)
            return;

        // Проб: фильтр вообще получает КАКИЕ-ЛИБО нажатия (не только Ctrl+Shift+F)?
        // Первые 20 нажатий после установки хука логируются безусловно (любая клавиша),
        // дальше — не чаще раза в 200мс, чтобы не залить лог обычным набором текста.
        long now = System.currentTimeMillis();
        if (anyProbeCount < 20 || now - lastAnyProbeAt >= 200)
        {
            anyProbeCount++;
            lastAnyProbeAt = now;
            Global.tempLog(TOPIC, "probe ANY keyDown #" + anyProbeCount //$NON-NLS-1$
                + " keyCode=" + event.keyCode //$NON-NLS-1$
                + " char=" + (int) event.character //$NON-NLS-1$
                + " stateMask=0x" + Integer.toHexString(event.stateMask) //$NON-NLS-1$
                + " widget=" + widgetBrief(event.widget)); //$NON-NLS-1$
        }

        if (!isCtrlShiftF(event))
            return;

        armed = true;
        armedAt = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        sb.append("KeyDown Ctrl+Shift+F keyCode=").append(event.keyCode) //$NON-NLS-1$
            .append(" stateMask=0x").append(Integer.toHexString(event.stateMask)) //$NON-NLS-1$
            .append(" widget=").append(widgetBrief(event.widget)) //$NON-NLS-1$
            .append(" focus=").append(formatFocus()) //$NON-NLS-1$
            .append(" activePart=").append(formatActivePart()); //$NON-NLS-1$

        IBindingService bindingService = PlatformUI.getWorkbench() != null
            ? PlatformUI.getWorkbench().getService(IBindingService.class) : null;
        Scheme scheme = bindingService != null ? bindingService.getActiveScheme() : null;
        sb.append(" activeScheme=").append(scheme != null ? scheme.getId() : "null"); //$NON-NLS-1$ //$NON-NLS-2$

        Set<String> activeContexts = collectActiveContextIds();
        sb.append(" activeContexts=").append(activeContexts); //$NON-NLS-1$

        Global.tempLog(TOPIC, sb.toString());

        if (bindingService instanceof BindingService internal)
            logMatchingBindings(internal, activeContexts);
        else
            Global.tempLog(TOPIC, "  bindingService is not internal BindingService: " //$NON-NLS-1$
                + (bindingService == null ? "null" : bindingService.getClass().getName())); //$NON-NLS-1$
    }

    private static void logMatchingBindings(BindingService bindingService, Set<String> activeContexts)
    {
        try
        {
            KeySequence target = KeySequence.getInstance(TARGET_SEQUENCE_FORMAL);
            int count = 0;
            for (Binding binding : bindingService.getBindings())
            {
                if (binding == null)
                    continue;
                TriggerSequence trigger = binding.getTriggerSequence();
                if (!target.equals(trigger))
                    continue;
                ParameterizedCommand pc = binding.getParameterizedCommand();
                String commandId = pc != null ? pc.getId() : "(unbind)"; //$NON-NLS-1$
                String contextId = binding.getContextId();
                boolean ctxActive = contextId != null && activeContexts.contains(contextId);
                Global.tempLog(TOPIC, "  candidate cmd=" + commandId //$NON-NLS-1$
                    + " ctx=" + contextId + " ctxActive=" + ctxActive //$NON-NLS-1$ //$NON-NLS-2$
                    + " type=" + (binding.getType() == Binding.SYSTEM ? "SYSTEM" : "USER") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " scheme=" + binding.getSchemeId() //$NON-NLS-1$
                    + " platform=" + binding.getPlatform() //$NON-NLS-1$
                    + " locale=" + binding.getLocale()); //$NON-NLS-1$
                count++;
            }
            if (count == 0)
                Global.tempLog(TOPIC, "  candidate (none registered for Ctrl+Shift+F)"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.tempLog(TOPIC, "  candidate scan error=" + e); //$NON-NLS-1$
        }
    }

    private static final IExecutionListener executionListener = new IExecutionListener()
    {
        @Override
        public void preExecute(String commandId, ExecutionEvent event)
        {
            if (consumeArmed())
                Global.tempLog(TOPIC, "EXECUTED preExecute cmd=" + commandId); //$NON-NLS-1$
        }

        @Override
        public void notHandled(String commandId, NotHandledException exception)
        {
            if (consumeArmed())
                Global.tempLog(TOPIC, "NOT_HANDLED cmd=" + commandId); //$NON-NLS-1$
        }

        @Override
        public void postExecuteFailure(String commandId, ExecutionException exception)
        {
            Global.tempLog(TOPIC, "postExecuteFailure cmd=" + commandId + " err=" + exception); //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue)
        {
            // preExecute уже отметил факт выполнения; здесь не дублируем
        }
    };

    private static boolean consumeArmed()
    {
        if (!armed)
            return false;
        boolean fresh = System.currentTimeMillis() - armedAt <= 2000;
        armed = false;
        return fresh;
    }

    private static boolean isCtrlShiftF(Event event)
    {
        int sm = event.stateMask;
        boolean ctrl = (sm & SWT.CTRL) != 0 || (sm & SWT.MOD1) != 0;
        boolean shift = (sm & SWT.SHIFT) != 0 || (sm & SWT.MOD2) != 0;
        if (!ctrl || !shift)
            return false;
        int kc = event.keyCode;
        char ch = event.character;
        return kc == 102 || kc == 'f' || kc == 'F' || ch == 'f' || ch == 'F' || ch == 6;
    }

    private static String widgetBrief(Widget w)
    {
        return w == null ? "null" : w.getClass().getSimpleName(); //$NON-NLS-1$
    }

    private static String formatFocus()
    {
        try
        {
            Display display = Display.getCurrent();
            if (display == null)
                display = Display.getDefault();
            if (display == null)
                return "display=null"; //$NON-NLS-1$
            Control focus = display.getFocusControl();
            if (focus == null || focus.isDisposed())
                return "focus=null"; //$NON-NLS-1$
            String shellTitle = ""; //$NON-NLS-1$
            Shell shell = focus.getShell();
            if (shell != null && !shell.isDisposed())
                shellTitle = shell.getText();
            String kind = focus instanceof StyledText ? "StyledText" : focus.getClass().getSimpleName(); //$NON-NLS-1$
            return "class=" + kind + " shell=\"" + shellTitle + "\""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        catch (Exception e)
        {
            return "[error: " + e.getMessage() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static String formatActivePart()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window != null ? window.getActivePage() : null;
            IWorkbenchPart part = page != null ? page.getActivePart() : null;
            if (part == null)
                return "null"; //$NON-NLS-1$
            String s = part.getClass().getName();
            if (part instanceof IEditorPart editor && editor.getSite() != null)
                s += " id=" + editor.getSite().getId(); //$NON-NLS-1$
            return s;
        }
        catch (Exception e)
        {
            return "[error: " + e.getMessage() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static Set<String> collectActiveContextIds()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return Set.of();
            IContextService contextService = window.getService(IContextService.class);
            if (contextService == null)
                return Set.of();
            Set<String> result = new HashSet<>();
            for (Object id : contextService.getActiveContextIds())
                if (id != null)
                    result.add(id.toString());
            return result;
        }
        catch (Exception e)
        {
            return Set.of("[error: " + e.getMessage() + "]"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
