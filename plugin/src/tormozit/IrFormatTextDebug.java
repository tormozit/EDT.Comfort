package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.Scheme;
import org.eclipse.jface.bindings.TriggerSequence;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.bindings.keys.KeyStroke;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.internal.keys.BindingService;
import org.eclipse.ui.keys.IBindingService;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Рантайм-диагностика Alt+Shift+F / конфликтов привязок для «Форматировать текст ИР».
 * По умолчанию выключена ({@link #KEY_DIAGNOSTIC_ENABLED}). Для включения: флаг true
 * + Параметры → Комфорт → «Общее логирование». Код сохранён для повторной отладки
 * и будущего пользовательского инструмента «кто кого вытесняет».
 */
public final class IrFormatTextDebug
{
    private static final String TAG = "IrFormatText"; //$NON-NLS-1$

    /** Переключатель разработчика: true + «Общее логирование» → полный report. */
    private static final boolean KEY_DIAGNOSTIC_ENABLED = false;

    /**
     * Диагностика клавиш/привязок IrFormatText (probe, report, execution listener).
     * Не влияет на функциональные хуки (context boost, binding mirror).
     */
    public static boolean isKeyDiagnosticEnabled()
    {
        return KEY_DIAGNOSTIC_ENABLED && Global.isLogEnabled();
    }

    private static final String DEFAULT_SCHEME_ID =
            "org.eclipse.ui.defaultAcceleratorConfiguration"; //$NON-NLS-1$

    private static final String DESIGNER_SCHEME_ID =
            "com._1c.g5.v8.designer.scheme"; //$NON-NLS-1$

    private static final String TARGET_SEQUENCE_FORMAL = "M3+M2+F"; //$NON-NLS-1$

    private static final int MAX_BINDING_LINES = 40;

    private static final int MAX_FILTER_LINES = 25;

    /** Известные перехваты KeyDown в плагине Комфорт (для сверки с filter dump). */
    private static final String[] COMFORT_KEYDOWN_HOOKS = {
        "ContentAssistSessionReloader.CtrlSpaceFilter (Display)", //$NON-NLS-1$
        "DebugInspectorTreeEnhancement (Display)", //$NON-NLS-1$
        "BSLEditorMenuHook (StyledText per editor)", //$NON-NLS-1$
        "EmbeddedBslXtextContextHook (StyledText per editor)", //$NON-NLS-1$
    };

    private IrFormatTextDebug() {}

    public static void log(String msg)
    {
        if (isKeyDiagnosticEnabled())
            Global.log(TAG, msg);
    }

    public static void step(String phase, String detail)
    {
        if (isKeyDiagnosticEnabled())
            Global.log(TAG, phase + ": " + detail); //$NON-NLS-1$
    }

    public static void problem(String msg)
    {
        if (isKeyDiagnosticEnabled())
            Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
    }

    public static void logComfortKeyDownHooksRegistered()
    {
        if (!isKeyDiagnosticEnabled())
            return;
        log("registered Comfort KeyDown hooks: " + COMFORT_KEYDOWN_HOOKS.length); //$NON-NLS-1$
        for (String hook : COMFORT_KEYDOWN_HOOKS)
            log("  comfort hook: " + hook); //$NON-NLS-1$
    }

    public static String formatKeyEvent(Event event)
    {
        if (event == null)
            return "event=null"; //$NON-NLS-1$
        return "type=" + eventTypeName(event.type) //$NON-NLS-1$
            + " keyCode=" + event.keyCode //$NON-NLS-1$
            + " char=" + charBrief(event.character) //$NON-NLS-1$
            + " stateMask=0x" + Integer.toHexString(event.stateMask) //$NON-NLS-1$
            + " doit=" + event.doit; //$NON-NLS-1$
    }

    public static boolean hasAltShiftModifiers(Event event)
    {
        if (event == null || event.type != SWT.KeyDown)
            return false;
        int sm = event.stateMask;
        boolean alt = (sm & SWT.ALT) != 0 || (sm & SWT.MOD1) != 0;
        boolean shift = (sm & SWT.SHIFT) != 0 || (sm & SWT.MOD2) != 0;
        return alt && shift;
    }

    /** Клавиша F (keyCode 102) — для probe-диагностики. */
    public static boolean isFKey(Event event)
    {
        if (event == null)
            return false;
        int kc = event.keyCode;
        char ch = event.character;
        if (kc == 102 || kc == 'f' || kc == 'F') //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        return ch == 'f' || ch == 'F';
    }

    public static boolean looksLikeAltShiftF(Event event)
    {
        if (event == null || event.type != SWT.KeyDown)
            return false;
        if (!isFKey(event))
            return false;
        int sm = event.stateMask;
        boolean alt = (sm & SWT.ALT) != 0 || (sm & SWT.MOD1) != 0;
        boolean shift = (sm & SWT.SHIFT) != 0 || (sm & SWT.MOD2) != 0;
        if (alt && shift)
            return true;
        // Windows: Alt+Shift+F иногда приходит как Alt+'F' без SHIFT в stateMask
        return alt && event.character == 'F';
    }

    /** Полный снимок рантайма при Alt+Shift+F (без изменения поведения). */
    public static void logFullRuntimeReport(String source, Event event, BslXtextEditor editor)
    {
        if (!isKeyDiagnosticEnabled())
            return;

        log("report BEGIN source=" + source); //$NON-NLS-1$
        if (event != null)
        {
            log("report event " + formatKeyEvent(event)); //$NON-NLS-1$
            log("report eventKeySequence=" + formatEventKeySequence(event)); //$NON-NLS-1$
        }
        appendRuntimeBindingReport(editor);
    }

    /** Снимок bindings/contexts при срабатывании команды (SWT KeyDown мог не дойти). */
    public static void logBindingSnapshot(String source, String commandId, BslXtextEditor editor)
    {
        if (!isKeyDiagnosticEnabled())
            return;

        log("report BEGIN source=" + source + " cmd=" + commandId); //$NON-NLS-1$ //$NON-NLS-2$
        log("report event (none, execution path)"); //$NON-NLS-1$
        appendRuntimeBindingReport(editor);
    }

    private static void appendRuntimeBindingReport(BslXtextEditor editor)
    {
        log("report focus " + formatFocus()); //$NON-NLS-1$
        log("report bslEditor=" + formatEditorBrief(editor)); //$NON-NLS-1$
        logEmbeddedBslSection(editor);

        BindingService bindingService = resolveBindingService();
        String activeScheme = formatActiveScheme(bindingService);
        log("report scheme active=" + activeScheme); //$NON-NLS-1$

        Set<String> activeContexts = collectActiveContextIds();
        log("report contexts active=" + activeContexts); //$NON-NLS-1$
        log("report context XtextEditorScope=" //$NON-NLS-1$
            + activeContexts.contains(IrFormatTextCommandHandler.BINDING_CONTEXT_ID));
        log("report context BslEditorScope=" //$NON-NLS-1$
            + activeContexts.contains("com._1c.g5.v8.dt.bsl.ui.editor.BslEditorScope")); //$NON-NLS-1$

        log("report ourCommand bindings active=" //$NON-NLS-1$
            + formatActiveBindingsFor(IrFormatTextCommandHandler.COMMAND_ID));
        log("report ourCommand bindings configured=" //$NON-NLS-1$
            + formatBindings(IrFormatTextCommandHandler.COMMAND_ID, null));
        log("report ourCommand bindings configured XtextEditorScope=" //$NON-NLS-1$
            + formatBindings(IrFormatTextCommandHandler.COMMAND_ID,
                IrFormatTextCommandHandler.BINDING_CONTEXT_ID));
        log("report ourCommand state=" + formatHandlerState(editor)); //$NON-NLS-1$

        KeySequence target = resolveTargetSequence();
        if (target == null)
        {
            problem("report: cannot parse target sequence " + TARGET_SEQUENCE_FORMAL); //$NON-NLS-1$
            log("report END"); //$NON-NLS-1$
            return;
        }
        log("report allBindings sequence=" + TARGET_SEQUENCE_FORMAL //$NON-NLS-1$
            + " formal=" + target.format());
        dumpMatchingBindings(bindingService, target, activeScheme, activeContexts);

        log("report conflicts=" + formatConflicts(bindingService, target)); //$NON-NLS-1$
        log("report effectiveCandidates=" + formatEffectiveCandidates(bindingService, target, activeContexts)); //$NON-NLS-1$

        dumpDisplayKeyDownFilters();
        log("report END"); //$NON-NLS-1$
    }

    public static void logKeyDiagnostics(BslXtextEditor editor, Event event)
    {
        logFullRuntimeReport("widget", event, editor); //$NON-NLS-1$
    }

    private static void logEmbeddedBslSection(BslXtextEditor editor)
    {
        for (String line : EmbeddedBslXtextContextHook.formatDiagnosticLines(editor))
            log("report embeddedBsl " + line); //$NON-NLS-1$
    }

    static String formatBindings(String commandId, String contextId)
    {
        TriggerSequence[] sequences =
            ComfortSubmenuHelper.resolveKeySequences(commandId, contextId);
        if (sequences == null || sequences.length == 0)
            return "[]"; //$NON-NLS-1$
        List<String> parts = new ArrayList<>(sequences.length);
        for (TriggerSequence sequence : sequences)
        {
            if (sequence == null)
                continue;
            String formatted = sequence.format();
            parts.add(formatted != null ? formatted : sequence.toString());
        }
        return parts.toString();
    }

    private static void dumpMatchingBindings(
            BindingService bindingService,
            KeySequence target,
            String activeScheme,
            Set<String> activeContexts)
    {
        if (bindingService == null)
        {
            log("report allBindings [bindingService=null]"); //$NON-NLS-1$
            return;
        }

        int count = 0;
        for (Binding binding : bindingService.getBindings())
        {
            if (binding == null || !matchesTargetSequence(binding, target))
                continue;
            if (!schemeMatches(binding.getSchemeId(), activeScheme))
                continue;
            if (count >= MAX_BINDING_LINES)
            {
                log("report allBindings …truncated"); //$NON-NLS-1$
                break;
            }
            log("report allBindings " + formatBindingLine(binding, activeContexts)); //$NON-NLS-1$
            count++;
        }
        if (count == 0)
            log("report allBindings (none in active+default scheme)"); //$NON-NLS-1$
    }

    private static String formatBindingLine(Binding binding, Set<String> activeContexts)
    {
        String commandId = ""; //$NON-NLS-1$
        ParameterizedCommand pc = binding.getParameterizedCommand();
        if (pc != null)
            commandId = pc.getId();
        String contextId = binding.getContextId();
        boolean contextActive = contextId != null && activeContexts.contains(contextId);
        TriggerSequence trigger = binding.getTriggerSequence();
        String formatted = trigger != null ? trigger.format() : "?"; //$NON-NLS-1$
        return "cmd=" + commandId //$NON-NLS-1$
            + " ctx=" + contextId //$NON-NLS-1$
            + " ctxActive=" + contextActive //$NON-NLS-1$
            + " type=" + bindingTypeName(binding.getType()) //$NON-NLS-1$
            + " scheme=" + binding.getSchemeId() //$NON-NLS-1$
            + " seq=" + formatted; //$NON-NLS-1$
    }

    private static String formatEffectiveCandidates(
            BindingService bindingService,
            KeySequence target,
            Set<String> activeContexts)
    {
        if (bindingService == null)
            return "[]"; //$NON-NLS-1$

        List<String> candidates = new ArrayList<>();
        String activeScheme = formatActiveScheme(bindingService);
        for (Binding binding : bindingService.getBindings())
        {
            if (binding == null || !matchesTargetSequence(binding, target))
                continue;
            if (!schemeMatches(binding.getSchemeId(), activeScheme))
                continue;
            String contextId = binding.getContextId();
            if (contextId == null || !activeContexts.contains(contextId))
                continue;
            candidates.add(formatBindingLine(binding, activeContexts));
        }
        if (candidates.isEmpty())
            return "[]"; //$NON-NLS-1$
        candidates.sort((a, b) -> {
            int scoreA = effectiveScore(a);
            int scoreB = effectiveScore(b);
            return Integer.compare(scoreB, scoreA);
        });
        return candidates.toString();
    }

    private static int effectiveScore(String line)
    {
        int score = 0;
        if (line.contains("type=USER")) //$NON-NLS-1$
            score += 100;
        if (line.contains(IrFormatTextCommandHandler.BINDING_CONTEXT_ID))
            score += 50;
        if (line.contains("com._1c.g5.v8.dt.bsl.ui.editor.BslEditorScope")) //$NON-NLS-1$
            score += 40;
        if (line.contains("tormozit.IrFormatText")) //$NON-NLS-1$
            score += 10;
        return score;
    }

    private static String formatConflicts(BindingService bindingService, KeySequence target)
    {
        if (bindingService == null)
            return "[]"; //$NON-NLS-1$
        try
        {
            Object manager = Global.invoke(bindingService, "getBindingManager"); //$NON-NLS-1$
            if (manager == null)
                return "[no BindingManager]"; //$NON-NLS-1$
            Object conflicts = Global.invoke(manager, "getConflictsFor", target); //$NON-NLS-1$
            if (conflicts == null)
                return "[]"; //$NON-NLS-1$
            return conflicts.toString();
        }
        catch (Exception e)
        {
            return "[error: " + e.getMessage() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static KeySequence resolveTargetSequence()
    {
        try
        {
            return KeySequence.getInstance(TARGET_SEQUENCE_FORMAL);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String formatEventKeySequence(Event event)
    {
        if (event == null)
            return "null"; //$NON-NLS-1$
        try
        {
            int modifiers = event.stateMask
                & (SWT.MODIFIER_MASK | SWT.MOD1 | SWT.MOD2 | SWT.MOD3 | SWT.MOD4);
            KeyStroke stroke = KeyStroke.getInstance(event.keyCode, modifiers);
            if (stroke == null)
                return "stroke=null keyCode=" + event.keyCode //$NON-NLS-1$
                    + " stateMask=0x" + Integer.toHexString(event.stateMask); //$NON-NLS-1$
            KeySequence sequence = KeySequence.getInstance(stroke);
            return sequence.format() + " formal=" + sequence.toString(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return "[error: " + e.getMessage() + "] keyCode=" + event.keyCode; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static String formatActiveBindingsFor(String commandId)
    {
        try
        {
            IBindingService bindingService =
                PlatformUI.getWorkbench().getService(IBindingService.class);
            if (bindingService == null)
                return "[]"; //$NON-NLS-1$
            TriggerSequence[] active = bindingService.getActiveBindingsFor(commandId);
            if (active == null || active.length == 0)
                return "[]"; //$NON-NLS-1$
            List<String> parts = new ArrayList<>(active.length);
            for (TriggerSequence sequence : active)
            {
                if (sequence == null)
                    continue;
                parts.add(sequence.format());
            }
            return parts.toString();
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
            Collection<?> active = contextService.getActiveContextIds();
            if (active == null || active.isEmpty())
                return Set.of();
            Set<String> result = new HashSet<>();
            for (Object id : active)
            {
                if (id != null)
                    result.add(id.toString());
            }
            return result;
        }
        catch (Exception e)
        {
            return Set.of("[error: " + e.getMessage() + "]"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    static String formatActiveContexts()
    {
        return collectActiveContextIds().toString();
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
            return "class=" + kind //$NON-NLS-1$
                + " shell=\"" + truncate(shellTitle, 60) + "\""; //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            return "[error: " + e.getMessage() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void dumpDisplayKeyDownFilters()
    {
        try
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
            {
                log("report displayFilters [display=null]"); //$NON-NLS-1$
                return;
            }

            int count = 0;
            count += dumpFilterArrays(display, count);
            if (count == 0)
                count += dumpFilterTable(display, count);
            if (count == 0)
                log("report displayFilters (introspect: none found)"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            log("report displayFilters [error: " + e.getMessage() + "]"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static int dumpFilterArrays(Display display, int startCount)
    {
        Object filtersObj = Global.getField(display, "filters"); //$NON-NLS-1$
        Object eventsObj = Global.getField(display, "filterEvents"); //$NON-NLS-1$
        if (!(filtersObj instanceof Listener[] filters) || !(eventsObj instanceof int[] events))
            return 0;

        int count = startCount;
        for (int i = 0; i < events.length && i < filters.length; i++)
        {
            if (events[i] != SWT.KeyDown || filters[i] == null)
                continue;
            if (count >= MAX_FILTER_LINES)
            {
                log("report displayFilters …truncated"); //$NON-NLS-1$
                return count;
            }
            log("report displayFilters #" + count //$NON-NLS-1$
                + " " + listenerBrief(filters[i]));
            count++;
        }
        return count - startCount;
    }

    @SuppressWarnings("unchecked")
    private static int dumpFilterTable(Display display, int startCount)
    {
        Object tableObj = Global.getField(display, "filterTable"); //$NON-NLS-1$
        if (!(tableObj instanceof Map<?, ?> table))
            return 0;

        int count = startCount;
        for (Map.Entry<?, ?> entry : table.entrySet())
        {
            if (count >= MAX_FILTER_LINES)
            {
                log("report displayFilters …truncated"); //$NON-NLS-1$
                break;
            }
            Object key = entry.getKey();
            if (key instanceof Integer type && type != SWT.KeyDown)
                continue;
            Object value = entry.getValue();
            if (value instanceof Listener listener)
            {
                log("report displayFilters #" + count + " " + listenerBrief(listener)); //$NON-NLS-1$ //$NON-NLS-2$
                count++;
            }
            else if (value instanceof Listener[] listeners)
            {
                for (Listener listener : listeners)
                {
                    if (listener == null)
                        continue;
                    if (count >= MAX_FILTER_LINES)
                        break;
                    log("report displayFilters #" + count + " " + listenerBrief(listener)); //$NON-NLS-1$ //$NON-NLS-2$
                    count++;
                }
            }
        }
        return count - startCount;
    }

    private static String listenerBrief(Listener listener)
    {
        if (listener == null)
            return "null"; //$NON-NLS-1$
        String name = listener.getClass().getName();
        int dollar = name.indexOf('$');
        if (dollar > 0)
            name = name.substring(name.lastIndexOf('.') + 1);
        return name + "@0x" + Integer.toHexString(System.identityHashCode(listener)); //$NON-NLS-1$
    }

    private static BindingService resolveBindingService()
    {
        try
        {
            if (PlatformUI.getWorkbench() == null)
                return null;
            IBindingService bindingService =
                PlatformUI.getWorkbench().getService(IBindingService.class);
            if (bindingService instanceof BindingService internal)
                return internal;
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    private static String formatActiveScheme(BindingService bindingService)
    {
        if (bindingService == null)
            return "?"; //$NON-NLS-1$
        Scheme scheme = bindingService.getActiveScheme();
        if (scheme == null || scheme.getId() == null)
            return "?"; //$NON-NLS-1$
        return scheme.getId();
    }

    private static boolean schemeMatches(String schemeId, String activeScheme)
    {
        if (schemeId == null)
            return false;
        return activeScheme.equals(schemeId)
            || DEFAULT_SCHEME_ID.equals(schemeId)
            || DESIGNER_SCHEME_ID.equals(schemeId);
    }

    private static boolean matchesTargetSequence(Binding binding, KeySequence target)
    {
        TriggerSequence trigger = binding.getTriggerSequence();
        if (!(trigger instanceof KeySequence keySequence))
            return false;
        if (target.equals(keySequence))
            return true;
        String formatted = trigger.format();
        return formatted != null
            && (formatted.equalsIgnoreCase("Alt+Shift+F") //$NON-NLS-1$
                || formatted.equalsIgnoreCase("M3+M2+F")); //$NON-NLS-1$
    }

    private static String bindingTypeName(int type)
    {
        if (type == Binding.SYSTEM)
            return "SYSTEM"; //$NON-NLS-1$
        if (type == Binding.USER)
            return "USER"; //$NON-NLS-1$
        return String.valueOf(type);
    }

    private static String formatHandlerState(BslXtextEditor editor)
    {
        try
        {
            ICommandService commandService = null;
            if (editor != null && editor.getSite() != null)
                commandService = editor.getSite().getService(ICommandService.class);
            if (commandService == null && PlatformUI.getWorkbench() != null)
                commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commandService == null)
                return "commandService=null"; //$NON-NLS-1$
            Command command = commandService.getCommand(IrFormatTextCommandHandler.COMMAND_ID);
            if (command == null)
                return "command=null"; //$NON-NLS-1$
            return "defined=" + command.isDefined() //$NON-NLS-1$
                + " handled=" + command.isHandled() //$NON-NLS-1$
                + " enabled=" + command.isEnabled(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return "error: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    private static String eventTypeName(int type)
    {
        if (type == SWT.KeyDown)
            return "KeyDown"; //$NON-NLS-1$
        if (type == SWT.KeyUp)
            return "KeyUp"; //$NON-NLS-1$
        return String.valueOf(type);
    }

    private static String charBrief(char ch)
    {
        if (ch == 0)
            return "'\\0'"; //$NON-NLS-1$
        if (ch < 32 || ch > 126)
            return "'\\u" + String.format("%04x", (int) ch) + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return "'" + ch + "'"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String truncate(String text, int max)
    {
        if (text == null)
            return ""; //$NON-NLS-1$
        if (text.length() <= max)
            return text;
        return text.substring(0, max) + "…"; //$NON-NLS-1$
    }

    static String formatPartBrief(IWorkbenchPart part)
    {
        if (part == null)
            return "null"; //$NON-NLS-1$
        return part.getClass().getSimpleName();
    }

    static String formatEditorBrief(BslXtextEditor editor)
    {
        if (editor == null)
            return "null"; //$NON-NLS-1$
        try
        {
            if (editor.getEditorInput() != null && editor.getEditorInput().getName() != null)
                return editor.getEditorInput().getName();
        }
        catch (Exception ignored)
        {
        }
        return editor.getClass().getSimpleName();
    }
}
