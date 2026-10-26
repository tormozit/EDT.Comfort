package tormozit.checks;

import org.eclipse.emf.ecore.EObject;

import com.e1c.g5.v8.dt.check.ICheckParameters;
import com.e1c.g5.v8.dt.check.WrongParameterException;

import tormozit.MassiveChecksExemptionHook;

/** Общий отбор открытых модулей для массовых проверок; ручной запуск сохраняет выбранный объём. */
final class OpenModulesOnly
{
    static final String PARAMETER = "onlyOpenModules"; //$NON-NLS-1$
    static final String TITLE = "Только открытые модули";

    private OpenModulesOnly() {}

    /** {@code true} — флажок включён, а модуль не открыт и ручного запуска нет. */
    static boolean skip(EObject module, ICheckParameters parameters)
    {
        boolean only;
        try
        {
            only = parameters == null || parameters.getBoolean(PARAMETER);
        }
        catch (WrongParameterException e)
        {
            only = true;
        }
        return only && module.eResource() != null
            && !MassiveChecksExemptionHook.isOpenOrManual(module.eResource().getURI());
    }
}
