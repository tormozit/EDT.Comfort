package tormozit.checks;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.rights.model.RightsPackage;
import com.e1c.g5.v8.dt.check.CheckComplexity;
import com.e1c.g5.v8.dt.check.ICheckParameters;
import com.e1c.g5.v8.dt.check.components.BasicCheck;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

import tormozit.ComfortCheckIds;
import tormozit.MdCompositionSupport;
import tormozit.MdTypeMapping;

/** Битые ссылки в составе общих объектов метаданных (issue #732). */
public final class BrokenMdCompositionCheck extends BasicCheck<Void>
{
    @Override
    public String getCheckId()
    {
        return ComfortCheckIds.BROKEN_MD_COMPOSITION;
    }

    @Override
    protected void configureCheck(CheckConfigurer configurer)
    {
        configurer.title("Битые ссылки в составе общих объектов метаданных")
            .description("Состав функциональных опций, определяемых типов, планов обмена, подписок на события,"
                + " общих реквизитов, критериев отбора и ролей содержит ссылки на несуществующие объекты.")
            .severity(IssueSeverity.MAJOR)
            .issueType(IssueType.ERROR)
            .complexity(CheckComplexity.NORMAL);
        configurer.topObject(MdClassPackage.Literals.FUNCTIONAL_OPTION)
            .features(MdClassPackage.Literals.FUNCTIONAL_OPTION__CONTENT);
        configurer.topObject(MdClassPackage.Literals.FILTER_CRITERION)
            .features(MdClassPackage.Literals.FILTER_CRITERION__CONTENT);
        configurer.topObject(MdClassPackage.Literals.DEFINED_TYPE)
            .containment(McorePackage.Literals.TYPE_DESCRIPTION)
            .features(McorePackage.Literals.TYPE_DESCRIPTION__TYPES);
        configurer.topObject(MdClassPackage.Literals.EVENT_SUBSCRIPTION)
            .containment(McorePackage.Literals.TYPE_DESCRIPTION)
            .features(McorePackage.Literals.TYPE_DESCRIPTION__TYPES);
        configurer.topObject(MdClassPackage.Literals.EXCHANGE_PLAN)
            .containment(MdClassPackage.Literals.EXCHANGE_PLAN_CONTENT_ITEM)
            .features(MdClassPackage.Literals.EXCHANGE_PLAN_CONTENT_ITEM__MD_OBJECT);
        configurer.topObject(MdClassPackage.Literals.COMMON_ATTRIBUTE)
            .containment(MdClassPackage.Literals.COMMON_ATTRIBUTE_CONTENT_ITEM)
            .features(MdClassPackage.Literals.COMMON_ATTRIBUTE_CONTENT_ITEM__METADATA);
        configurer.topObject(RightsPackage.Literals.ROLE_DESCRIPTION)
            .containment(RightsPackage.Literals.OBJECT_RIGHTS)
            .features(RightsPackage.Literals.OBJECT_RIGHTS__OBJECT);
    }

    @Override
    protected void check(Object object, ResultAcceptor resultAcceptor, ICheckParameters parameters,
        IProgressMonitor monitor)
    {
        for (MdCompositionSupport.BrokenReference broken
            : MdCompositionSupport.findBrokenReferences((EObject)object))
        {
            if (monitor.isCanceled())
                return;
            MdCompositionSupport.Composition composition = broken.composition();
            String message = "Битая ссылка в составе: " + formatReference(broken.reference());
            // Маркер на владельце состава: навигация и быстрое исправление получают сам объект,
            // а не безымянную запись состава или TypeDescription.
            if (composition.feature().isMany() && broken.index() >= 0)
                resultAcceptor.addIssue(message, composition.owner(), composition.feature(), broken.index());
            else
                resultAcceptor.addIssue(message, composition.owner(), composition.feature());
        }
    }

    private static String formatReference(EObject reference)
    {
        String uri = String.valueOf(((InternalEObject)reference).eProxyURI());
        int hash = uri.indexOf('#');
        String fragment = hash >= 0 ? uri.substring(hash) : "";
        String path = hash >= 0 ? uri.substring(0, hash) : uri;
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < path.length())
            path = path.substring(slash + 1);
        String localized = MdTypeMapping.bmFqnToRuFullName(path);
        // У типов фрагмент может обозначать конкретный производный тип объекта.
        return (localized != null ? localized : path) + ("#/".equals(fragment) ? "" : fragment);
    }
}
