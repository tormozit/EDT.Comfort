package tormozit.checks;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com.e1c.g5.v8.dt.check.CheckComplexity;
import com.e1c.g5.v8.dt.check.ICheckParameters;
import com.e1c.g5.v8.dt.check.components.BasicCheck;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

import tormozit.ComfortCheckIds;
import tormozit.MdReachability;

/**
 * Объект метаданных лежит в проекте, но в составе конфигурации ({@code Configuration.mdo}) его нет
 * (issue #754). Признак — тот же, что у команды «Найти недостижимые файлы метаданных».
 */
public final class UnreachableMdObjectCheck extends BasicCheck<Void>
{
    @Override
    public String getCheckId()
    {
        return ComfortCheckIds.UNREACHABLE_MD_OBJECT;
    }

    @Override
    protected void configureCheck(CheckConfigurer configurer)
    {
        configurer.title("Достижимость объекта метаданных")
            .description("Файлы объекта метаданных есть в проекте, но объекта нет в составе конфигурации"
                + " (Configuration.mdo). Такого объекта нет в навигаторе, а сравнение конфигураций"
                + " учитывает его как существующий.")
            .severity(IssueSeverity.MAJOR)
            .issueType(IssueType.ERROR)
            .complexity(CheckComplexity.NORMAL);
        configurer.enableProtectedObjectCheck();
        // CheckDefinition хранит EClass по точному ключу: перечисляются конкретные виды объектов
        // состава конфигурации — типы её списков (catalogs, documents…). Общий список content
        // имеет тип MdObject и вид объекта не задаёт.
        java.util.Set<EClass> types = new java.util.LinkedHashSet<>();
        for (EReference content : MdClassPackage.Literals.CONFIGURATION.getEAllReferences())
        {
            EClass type = content.getEReferenceType();
            if (content.isMany() && !content.isContainment() && !type.isAbstract() && !type.isInterface()
                && type != MdClassPackage.Literals.MD_OBJECT && MdClassPackage.Literals.MD_OBJECT.isSuperTypeOf(type))
                types.add(type);
        }
        for (EClass type : types)
            configurer.topObject(type).checkTop().features(MdClassPackage.Literals.MD_OBJECT__NAME);
    }

    @Override
    protected void check(Object object, ResultAcceptor resultAcceptor, ICheckParameters parameters,
        IProgressMonitor monitor)
    {
        if (object instanceof EObject target && MdReachability.isUnreachable(MdReachability.descriptorOf(target)))
            resultAcceptor.addIssue("Объекта метаданных нет в составе конфигурации (Configuration.mdo)",
                target, MdClassPackage.Literals.MD_OBJECT__NAME);
    }
}
