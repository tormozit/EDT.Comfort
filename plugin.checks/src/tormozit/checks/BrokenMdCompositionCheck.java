package tormozit.checks;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EClass;

import com.e1c.g5.v8.dt.check.CheckComplexity;
import com.e1c.g5.v8.dt.check.ICheckParameters;
import com.e1c.g5.v8.dt.check.components.BasicCheck;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

import tormozit.ComfortCheckIds;
import tormozit.MdReferenceSupport;

/** Битые ссылки метаданных во всех сохранённых свойствах модели (issue #732). */
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
        configurer.title("Битые ссылки метаданных")
            .description("Свойства объектов конфигурации содержат ссылки на несуществующие объекты метаданных,"
                + " включая подчинённые объекты и типы значений.")
            .severity(IssueSeverity.MAJOR)
            .issueType(IssueType.ERROR)
            .complexity(CheckComplexity.NORMAL);
        // Без этого EDT молча не вызывает проверку для объектов, которые нельзя редактировать
        // (CheckExecutor.validateModel: canEdit == false) — битая ссылка в них остаётся невидимой.
        configurer.enableProtectedObjectCheck();
        for (var scope : MdReferenceSupport.scopes().entrySet())
        {
            var objects = configurer.topObject(scope.getKey());
            if (!MdReferenceSupport.features(scope.getKey()).isEmpty())
                objects.checkTop().features(MdReferenceSupport.features(scope.getKey()).toArray(
                    org.eclipse.emf.ecore.EStructuralFeature[]::new));
            for (EClass type : scope.getValue())
                objects.containment(type).features(MdReferenceSupport.features(type).toArray(
                    org.eclipse.emf.ecore.EStructuralFeature[]::new));
        }
    }

    @Override
    protected void check(Object object, ResultAcceptor resultAcceptor, ICheckParameters parameters,
        IProgressMonitor monitor)
    {
        var brokenReferences = MdReferenceSupport.findBrokenReferences((EObject)object, monitor);
        for (int first = 0; first < brokenReferences.size();)
        {
            if (monitor.isCanceled())
                return;
            MdReferenceSupport.BrokenReference broken = brokenReferences.get(first);
            // Анализатор обходит свойства по очереди: ссылки одного свойства идут подряд.
            int end = first + 1;
            while (end < brokenReferences.size() && brokenReferences.get(end).feature() == broken.feature())
                end++;
            int count = end - first;
            String message = count == 1
                ? "Битая ссылка метаданных: " + MdReferenceSupport.localized(
                    org.eclipse.emf.ecore.util.EcoreUtil.getURI(broken.target()))
                : "Битые ссылки метаданных: " + count;
            resultAcceptor.addIssue(message, broken.owner(), broken.feature());
            first = end;
        }
    }

}
