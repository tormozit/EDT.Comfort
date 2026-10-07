package tormozit.checks;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com.e1c.g5.v8.dt.check.qfix.IFixSession;
import com.e1c.g5.v8.dt.check.qfix.components.BasicModelFixContext;
import com.e1c.g5.v8.dt.check.qfix.components.SingleVariantModelBasicFix;
import com.e1c.g5.v8.dt.check.settings.CheckUid;

import tormozit.ComfortCheckIds;
import tormozit.MdCompositionSupport;

/** Удаляет только неразрешённые элементы состава средствами штатного исправления модели EDT. */
public final class BrokenMdCompositionFix extends SingleVariantModelBasicFix<EObject>
{
    @Override
    public CheckUid getCheckId()
    {
        return new CheckUid(ComfortCheckIds.BROKEN_MD_COMPOSITION, "tormozit.comfort.checks");
    }

    @Override
    protected void configureFix(FixConfigurer configurer)
    {
        configurer.description("Удалить битые ссылки из состава");
    }

    @Override
    protected boolean isFixApplicable(BasicModelFixContext context, IFixSession session)
    {
        EObject owner = session.getModelObject(context.getTargetObjectId());
        MdCompositionSupport.Composition composition = MdCompositionSupport.composition(owner);
        return composition != null && context.getTargetFeatureId() == composition.feature().getFeatureID()
            && !MdCompositionSupport.findBrokenReferences(owner).isEmpty();
    }

    @Override
    protected void applyChanges(EObject owner, EStructuralFeature feature, BasicModelFixContext context,
        IFixSession session)
    {
        MdCompositionSupport.Composition composition = MdCompositionSupport.composition(owner);
        if (composition != null && composition.feature() == feature)
            MdCompositionSupport.removeBrokenReferences(owner);
    }
}
