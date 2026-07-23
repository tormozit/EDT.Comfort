package tormozit;

import org.eclipse.ui.IStartup;

/**
 * IStartup-обёртка без импортов JDT: при отсутствии {@code org.eclipse.jdt.ui}
 * класс {@link SpellCheckHook} не загружается (иначе {@code NoClassDefFoundError}).
 */
public final class SpellCheckStartup implements IStartup
{
    @Override
    public void earlyStartup()
    {
        if (!ComfortJdtAvailability.isJdtUiAvailable())
            return;
        try
        {
            IStartup hook = (IStartup) Class.forName("tormozit.SpellCheckHook") //$NON-NLS-1$
                .getDeclaredConstructor()
                .newInstance();
            hook.earlyStartup();
        }
        catch (Exception | LinkageError e)
        {
            Global.logError("spell", "SpellCheckStartup", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
