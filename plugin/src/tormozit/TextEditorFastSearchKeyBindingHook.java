package tormozit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.Scheme;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.bindings.keys.KeyBinding;
import org.eclipse.jface.bindings.keys.ParseException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.keys.IBindingService;

/**
 * По умолчанию (Ctrl+F3 / Shift+F3) на {@code M1+F3}/{@code M2+F3} в контексте
 * {@code org.eclipse.xtext.ui.XtextEditorScope} уже претендует штатная команда EDT
 * ({@code ...ForwardBslFastSearchHandler}/{@code Back...}), из-за чего одновременный
 * системный (plugin.xml) биндинг {@link TextEditorFastSearchHandler} на то же сочетание приводит
 * к диалогу выбора команды вместо тихого выполнения.
 * <p>
 * Решение: один раз при старте программно создаём {@code USER}-привязку (тот же механизм,
 * которым страница настроек «Клавиши» сохраняет выбор пользователя) на наши команды
 * ({@code tormozit.TextEditorFastSearchForward}/{@code Back}) для тех же сочетаний
 * в контексте {@code XtextEditorScope} (редакторы BSL). USER-привязка однозначно побеждает
 * SYSTEM-привязку EDT без диалога, при этом остаётся обычной записью в «Клавиши» —
 * пользователь может там же переназначить или отключить её.
 * <p>
 * Для модального «Редактора запроса» (куда Eclipse Key Binding Service не доставляет события)
 * установлен глобальный {@link Display#addFilter SWT.KeyDown-фильтр}.
 */
public final class TextEditorFastSearchKeyBindingHook implements IStartup
{
    private static final String TAG = "FastSearch"; //$NON-NLS-1$
    private static final String CONTEXT_ID = "org.eclipse.xtext.ui.XtextEditorScope"; //$NON-NLS-1$
    private static final String SEQ_FORWARD = "M1+F3"; //$NON-NLS-1$
    private static final String SEQ_BACK = "M1+M2+F3"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(TextEditorFastSearchKeyBindingHook::ensureBindings);
    }

    private static void ensureBindings()
    {
        try
        {
            IBindingService bindingService = PlatformUI.getWorkbench().getAdapter(IBindingService.class);
            ICommandService commandService = PlatformUI.getWorkbench().getAdapter(ICommandService.class);
            if (bindingService == null || commandService == null)
                return;

            Scheme activeScheme = bindingService.getActiveScheme();
            if (activeScheme == null)
                return;
            String schemeId = activeScheme.getId();

            Binding[] existing = bindingService.getBindings();
            if (existing == null)
                return;

            KeySequence forwardSeq = KeySequence.getInstance(SEQ_FORWARD);
            KeySequence backSeq = KeySequence.getInstance(SEQ_BACK);

            Command forwardCmd = commandService.getCommand("tormozit.TextEditorFastSearchForward"); //$NON-NLS-1$
            Command backCmd = commandService.getCommand("tormozit.TextEditorFastSearchBack"); //$NON-NLS-1$

            List<Binding> newBindings = new ArrayList<>(Arrays.asList(existing));
            ensureContextBindings(newBindings, forwardSeq, backSeq,
                forwardCmd, backCmd, schemeId, existing);
            bindingService.savePreferences(activeScheme, newBindings.toArray(new Binding[0]));
        }
        catch (ParseException e)
        {
            Global.log("TextEditorFastSearchKeyBindingHook: " + e); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            Global.log("TextEditorFastSearchKeyBindingHook: " + e); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            Global.log("TextEditorFastSearchKeyBindingHook: " + e); //$NON-NLS-1$
        }
        installDisplayFilter();
    }

    private static void ensureContextBindings(List<Binding> newBindings,
        KeySequence forwardSeq, KeySequence backSeq,
        Command forwardCmd, Command backCmd, String schemeId, Binding[] existing)
    {
        boolean forwardCustomized = hasUserBinding(existing, forwardSeq, schemeId);
        boolean backCustomized = hasUserBinding(existing, backSeq, schemeId);

        if (!forwardCustomized)
        {
            newBindings.add(new KeyBinding(
                forwardSeq, new ParameterizedCommand(forwardCmd, null),
                schemeId, CONTEXT_ID, null, null, null, Binding.USER));
        }
        if (!backCustomized)
        {
            newBindings.add(new KeyBinding(
                backSeq, new ParameterizedCommand(backCmd, null),
                schemeId, CONTEXT_ID, null, null, null, Binding.USER));
        }
    }

    /** Display-фильтр для модальных диалогов, куда не доходит Key Binding Service. */
    private static void installDisplayFilter()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.KeyDown, TextEditorFastSearchKeyBindingHook::handleDisplayKeyDown);
        Global.log(TAG, "installDisplayFilter");
    }

    private static void handleDisplayKeyDown(Event event)
    {
        if (event.keyCode != SWT.F3)
            return;
        if ((event.stateMask & SWT.MOD1) == 0)
            return;

        boolean forward = (event.stateMask & SWT.MOD2) == 0;
        if (!(event.widget instanceof StyledText textWidget) || textWidget.isDisposed())
            return;

        TextEditorFastSearchHandler.executeSearch(textWidget, forward);
        event.doit = false;
    }

    /** Есть ли уже пользовательская (в т.ч. «не привязано») привязка на это сочетание в этом контексте. */
    private static boolean hasUserBinding(Binding[] bindings, KeySequence sequence, String schemeId)
    {
        for (Binding b : bindings)
        {
            if (b.getType() != Binding.USER)
                continue;
            if (!schemeId.equals(b.getSchemeId()))
                continue;
            if (!CONTEXT_ID.equals(b.getContextId()))
                continue;
            if (sequence.equals(b.getTriggerSequence()))
                return true;
        }
        return false;
    }
}
