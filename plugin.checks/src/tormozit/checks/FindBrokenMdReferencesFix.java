package tormozit.checks;

import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com.e1c.g5.v8.dt.check.qfix.FixDescriptor;
import com.e1c.g5.v8.dt.check.qfix.FixVariantDescriptor;
import com.e1c.g5.v8.dt.check.qfix.IFixChange;
import com.e1c.g5.v8.dt.check.qfix.IFixChangeProcessor;
import com.e1c.g5.v8.dt.check.qfix.IFixSession;
import com.e1c.g5.v8.dt.check.qfix.components.BasicModelFixContext;
import com.e1c.g5.v8.dt.check.qfix.components.SingleVariantBasicFix;
import com.e1c.g5.v8.dt.check.settings.CheckUid;

import tormozit.ComfortCheckIds;
import tormozit.MdReferenceSupport;

/** Показывает ссылки свойства в Поиске, не создавая контекст редактирования модели. */
public final class FindBrokenMdReferencesFix extends SingleVariantBasicFix<BasicModelFixContext>
    implements IFixChangeProcessor<IFixChange, BasicModelFixContext>
{
    private record FindChange(IProject project, MdReferenceSupport.Location location) implements IFixChange {}

    @Override
    public CheckUid getCheckId()
    {
        return new CheckUid(ComfortCheckIds.BROKEN_MD_REFERENCE, "tormozit.comfort.checks");
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
        EObject owner = session.getModelObject(context.getTargetObjectId());
        return owner != null
            && owner.eClass().getEStructuralFeature(context.getTargetFeatureId()) instanceof EReference reference
            && !MdReferenceSupport.findBrokenReferences(owner, reference, new NullProgressMonitor()).isEmpty();
    }

    @Override
    public FixVariantDescriptor describeChanges(BasicModelFixContext context, IFixSession session)
    {
        return new FixVariantDescriptor("Найти битые ссылки",
            "Показать битые ссылки этого свойства в панели Поиск");
    }

    @Override
    public Collection<IFixChange> prepareChanges(BasicModelFixContext context, IFixSession session)
    {
        EObject owner = session.getModelObject(context.getTargetObjectId());
        if (owner == null || session.getDtProject() == null
            || !(owner.eClass().getEStructuralFeature(context.getTargetFeatureId()) instanceof EReference feature))
            return List.of();
        return List.of(new FindChange(session.getDtProject().getWorkspaceProject(),
            new MdReferenceSupport.Location(EcoreUtil.getURI(owner), owner.eClass(), feature.getName())));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<IFixChange> getProcessedFixType()
    {
        return (Class<IFixChange>)(Class<?>)FindChange.class;
    }

    @Override
    public void applyFix(IFixChange change, BasicModelFixContext context, IFixSession session)
    {
        if (change instanceof FindChange find)
            MdReferenceSupport.findInProject(find.project(), find.location());
    }
}
