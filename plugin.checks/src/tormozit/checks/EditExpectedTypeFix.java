package tormozit.checks;

import java.util.Collection;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.bsl.ui.quickfix.AbstractExternalQuickfixProvider;
import com.e1c.g5.v8.dt.check.qfix.FixDescriptor;
import com.e1c.g5.v8.dt.check.qfix.FixVariantDescriptor;
import com.e1c.g5.v8.dt.check.qfix.IFix;
import com.e1c.g5.v8.dt.check.qfix.IFixChange;
import com.e1c.g5.v8.dt.check.qfix.IFixChangeProcessor;
import com.e1c.g5.v8.dt.check.qfix.IFixContext;
import com.e1c.g5.v8.dt.check.qfix.IFixSession;
import com.e1c.g5.v8.dt.check.qfix.IFixVariant;
import com.e1c.g5.v8.dt.check.settings.CheckUid;

import tormozit.BslExpectedTypeQuickFix;
import tormozit.BslExpectedTypeQuickFix.Target;
import tormozit.ComfortCheckIds;
import tormozit.Global;

/**
 * Быстрое исправление проверки «Несовместимость типа значения»: перейти к явному ожидаемому типу
 * в модуле и выделить его для редактирования. Сами тексты и поиск места живут в основном бандле.
 */
public final class EditExpectedTypeFix extends AbstractExternalQuickfixProvider
    implements IFix<IFixContext>, IFixVariant<IFixContext>,
        IFixChangeProcessor<IFixChange, IFixContext>
{
    private static final String BUNDLE = "com.e1c.g5.v8.dt.bsl.check"; //$NON-NLS-1$
    private static final String API = BUNDLE + ".qfix."; //$NON-NLS-1$
    private static final CheckUid CHECK_UID =
        new CheckUid(ComfortCheckIds.BSL_VALUE_TYPE, "tormozit.comfort.checks"); //$NON-NLS-1$

    @Override
    public CheckUid getCheckId()
    {
        return CHECK_UID;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<IFixContext> getRequiredContextType()
    {
        try
        {
            return (Class<IFixContext>)loadApi("SingleVariantXtextBslModuleFixContext"); //$NON-NLS-1$
        }
        catch (ReflectiveOperationException e)
        {
            return IFixContext.class;
        }
    }

    @Override
    public void onRegistration(FixDescriptor descriptor)
    {
        descriptor.setChangeProcessor(this);
    }

    @Override
    public Collection<IFixVariant<IFixContext>> getVariants(IFixContext context, IFixSession session)
    {
        return locate(context, session) == null ? List.of() : List.of(this);
    }

    @Override
    public FixVariantDescriptor describeChanges(IFixContext context, IFixSession session)
    {
        return new FixVariantDescriptor(BslExpectedTypeQuickFix.TITLE,
            "Открыть модуль, где явно задан ожидаемый тип, и выделить его текст для редактирования."
                + Global.pluginSignForTooltip());
    }

    @Override
    public Collection<IFixChange> prepareChanges(IFixContext context, IFixSession session)
    {
        Target target = locate(context, session);
        return target == null ? List.of() : List.of(new Change(target));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<IFixChange> getProcessedFixType()
    {
        return (Class<IFixChange>)(Class<?>)Change.class;
    }

    @Override
    public void applyFix(IFixChange fixChange, IFixContext context, IFixSession session)
    {
        if (fixChange instanceof Change change)
            BslExpectedTypeQuickFix.reveal(change.target);
    }

    private static Target locate(IFixContext context, IFixSession session)
    {
        return BslExpectedTypeQuickFix.locateFromFixModel(getModel(context, session, false));
    }

    private static Class<?> loadApi(String name) throws ClassNotFoundException
    {
        Bundle bundle = Platform.getBundle(BUNDLE);
        if (bundle == null)
            throw new ClassNotFoundException(BUNDLE);
        return bundle.loadClass(API + name);
    }

    private static Object getModel(IFixContext context, IFixSession session, boolean interactive)
    {
        try
        {
            Class<?> contextApi = loadApi("SingleVariantXtextBslModuleFixContext"); //$NON-NLS-1$
            if (!contextApi.isInstance(context))
                return null;
            return contextApi.getMethod("getModel", IFixSession.class, boolean.class) //$NON-NLS-1$
                .invoke(context, session, interactive);
        }
        catch (ReflectiveOperationException e)
        {
            return null;
        }
    }

    private static final class Change implements IFixChange
    {
        final Target target;

        Change(Target target)
        {
            this.target = target;
        }
    }
}
