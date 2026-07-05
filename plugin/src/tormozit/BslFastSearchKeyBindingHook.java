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
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.keys.IBindingService;

/**
 * По умолчанию (Ctrl+F3 / Shift+F3) на {@code M1+F3}/{@code M2+F3} в контексте
 * {@code org.eclipse.xtext.ui.XtextEditorScope} уже претендует штатная команда EDT
 * ({@code ...ForwardBslFastSearchHandler}/{@code Back...}), из-за чего одновременный
 * системный (plugin.xml) биндинг {@link BslFastSearchHandler} на то же сочетание приводит
 * к диалогу выбора команды вместо тихого выполнения.
 * <p>
 * Решение: один раз при старте программно создаём {@code USER}-привязку (тот же механизм,
 * которым страница настроек «Клавиши» сохраняет выбор пользователя) на наши команды
 * ({@code tormozit.BslFastSearchForward}/{@code Back}) для тех же сочетаний. USER-привязка
 * однозначно побеждает SYSTEM-привязку EDT без диалога, при этом остаётся обычной записью
 * в «Клавиши» — пользователь может там же переназначить или отключить её.
 * <p>
 * Если пользователь уже сам настраивал эти сочетания в этом контексте (то есть там уже есть
 * USER-привязка — неважно, на какую команду, в т.ч. «не привязано»), хук ничего не трогает.
 */
public final class BslFastSearchKeyBindingHook implements IStartup
{
    private static final String CONTEXT_ID = "org.eclipse.xtext.ui.XtextEditorScope"; //$NON-NLS-1$
    private static final String SEQ_FORWARD = "M1+F3"; //$NON-NLS-1$
    private static final String SEQ_BACK = "M1+M2+F3"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(BslFastSearchKeyBindingHook::ensureBindings);
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

            boolean forwardCustomized = hasUserBinding(existing, forwardSeq, schemeId);
            boolean backCustomized = hasUserBinding(existing, backSeq, schemeId);
            if (forwardCustomized && backCustomized)
                return;

            Command forwardCmd = commandService.getCommand("tormozit.BslFastSearchForward"); //$NON-NLS-1$
            Command backCmd = commandService.getCommand("tormozit.BslFastSearchBack"); //$NON-NLS-1$

            List<Binding> newBindings = new ArrayList<>(Arrays.asList(existing));
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

            bindingService.savePreferences(activeScheme, newBindings.toArray(new Binding[0]));
        }
        catch (ParseException e)
        {
            Global.log("BslFastSearchKeyBindingHook: " + e); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            Global.log("BslFastSearchKeyBindingHook: " + e); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            Global.log("BslFastSearchKeyBindingHook: " + e); //$NON-NLS-1$
        }
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
