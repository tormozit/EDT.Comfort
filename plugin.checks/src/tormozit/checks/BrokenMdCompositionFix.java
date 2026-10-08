package tormozit.checks;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com.e1c.g5.v8.dt.check.qfix.IFixSession;
import com.e1c.g5.v8.dt.check.qfix.components.BasicModelFixContext;
import com.e1c.g5.v8.dt.check.qfix.components.SingleVariantModelBasicFix;
import com.e1c.g5.v8.dt.check.settings.CheckUid;

import tormozit.ComfortCheckIds;
import tormozit.MdReferenceSupport;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.core.runtime.NullProgressMonitor;

/** Очищает битые ссылки свойства средствами штатного исправления модели EDT. */
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
        configurer.description("Удалить битые ссылки");
    }

    @Override
    protected boolean isFixApplicable(BasicModelFixContext context, IFixSession session)
    {
        EObject owner = session.getModelObject(context.getTargetObjectId());
        EStructuralFeature feature = owner != null
            ? owner.eClass().getEStructuralFeature(context.getTargetFeatureId()) : null;
        boolean applicable = feature instanceof EReference reference
            && !MdReferenceSupport.findBrokenReferences(owner, reference, new NullProgressMonitor()).isEmpty();
        tormozit.Global.tempLog("broken-links-fix", "applicable owner=" + context.getTargetObjectId()
            + " feature=" + context.getTargetFeatureId() + " result=" + applicable);
        return applicable;
    }

    @Override
    protected void applyChanges(EObject owner, EStructuralFeature feature, BasicModelFixContext context,
        IFixSession session)
    {
        if (feature instanceof EReference reference)
            MdReferenceSupport.removeBrokenReferences(owner, reference);
    }
}
