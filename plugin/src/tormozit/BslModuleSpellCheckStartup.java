package tormozit;

import org.eclipse.ui.IStartup;

/**
 * IStartup-обёртка без импортов JDT: при отсутствии {@code org.eclipse.jdt.ui}
 * класс {@link BslModuleSpellCheckHook} не загружается.
 */
public final class BslModuleSpellCheckStartup implements IStartup
{
    @Override
    public void earlyStartup()
    {
        if (!ComfortJdtAvailability.isJdtUiAvailable())
            return;
        try
        {
            IStartup hook = (IStartup) Class.forName("tormozit.BslModuleSpellCheckHook") //$NON-NLS-1$
                .getDeclaredConstructor()
                .newInstance();
            hook.earlyStartup();
        }
        catch (Exception | LinkageError e)
        {
            Global.logError("spell", "BslModuleSpellCheckStartup", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
