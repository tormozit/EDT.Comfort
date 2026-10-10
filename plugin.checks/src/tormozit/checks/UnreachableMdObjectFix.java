package tormozit.checks;

import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.emf.ecore.EObject;

import com.e1c.g5.v8.dt.check.qfix.FixDescriptor;
import com.e1c.g5.v8.dt.check.qfix.FixVariantDescriptor;
import com.e1c.g5.v8.dt.check.qfix.IFixChange;
import com.e1c.g5.v8.dt.check.qfix.IFixChangeProcessor;
import com.e1c.g5.v8.dt.check.qfix.IFixSession;
import com.e1c.g5.v8.dt.check.qfix.components.BasicModelFixContext;
import com.e1c.g5.v8.dt.check.qfix.components.SingleVariantBasicFix;
import com.e1c.g5.v8.dt.check.settings.CheckUid;

import tormozit.ComfortCheckIds;
import tormozit.MdReachability;

/**
 * Прописывает объект в составе конфигурации — то же, что команда «Подключить к конфигурации»
 * режима «Недостижимые файлы» панели «Поиск». Контекст редактирования модели не создаётся:
 * правится файл {@code Configuration.mdo}.
 */
public final class UnreachableMdObjectFix extends SingleVariantBasicFix<BasicModelFixContext>
    implements IFixChangeProcessor<IFixChange, BasicModelFixContext>
{
    private record AttachChange(IFile descriptor) implements IFixChange {}

    @Override
    public CheckUid getCheckId()
    {
        return new CheckUid(ComfortCheckIds.UNREACHABLE_MD_OBJECT, "tormozit.comfort.checks");
    }

    @Override
    public Class<BasicModelFixContext> getRequiredContextType() { return BasicModelFixContext.class; }

    @Override
    public void onRegistration(FixDescriptor descriptor)
    {
        descriptor.setContextFactory(new ModelFixContextFactory());
        descriptor.setChangeProcessor(this);
    }

    @Override
    protected boolean isFixApplicable(BasicModelFixContext context, IFixSession session)
    {
        EObject object = session.getModelObject(context.getTargetObjectId());
        return MdReachability.isUnreachable(MdReachability.descriptorOf(object));
    }

    @Override
    public FixVariantDescriptor describeChanges(BasicModelFixContext context, IFixSession session)
    {
        return new FixVariantDescriptor("Подключить к конфигурации",
            "Прописать объект в составе конфигурации (Configuration.mdo)");
    }

    @Override
    public Collection<IFixChange> prepareChanges(BasicModelFixContext context, IFixSession session)
    {
        IFile descriptor = MdReachability.descriptorOf(session.getModelObject(context.getTargetObjectId()));
        return descriptor == null ? List.of() : List.of(new AttachChange(descriptor));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<IFixChange> getProcessedFixType()
    {
        return (Class<IFixChange>)(Class<?>)AttachChange.class;
    }

    @Override
    public void applyFix(IFixChange change, BasicModelFixContext context, IFixSession session)
    {
        if (change instanceof AttachChange attach)
            MdReachability.attachObject(attach.descriptor());
    }
}
