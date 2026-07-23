package tormozit;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

/**
 * Наличие {@code org.eclipse.jdt.ui} в runtime (орфография Comfort строится на JDT spelling).
 * Без JDT плагин работает, орфография отключена ([#189](https://github.com/tormozit/EDT.Comfort/issues/189)).
 */
public final class ComfortJdtAvailability
{
    public static final String JDT_UI_BUNDLE_ID = "org.eclipse.jdt.ui"; //$NON-NLS-1$

    private ComfortJdtAvailability()
    {
    }

    /** {@code true}, если бандл JDT UI установлен и разрезолвлен/активен. */
    public static boolean isJdtUiAvailable()
    {
        Bundle bundle = Platform.getBundle(JDT_UI_BUNDLE_ID);
        if (bundle == null)
            return false;
        int state = bundle.getState();
        return state == Bundle.RESOLVED
            || state == Bundle.STARTING
            || state == Bundle.ACTIVE;
    }
}
