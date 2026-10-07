package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttributeContentItem;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlan;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlanContentItem;
import com._1c.g5.v8.dt.metadata.mdclass.FilterCriterion;
import com._1c.g5.v8.dt.metadata.mdclass.FunctionalOption;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.rights.model.ObjectRights;
import com._1c.g5.v8.dt.rights.model.RoleDescription;
import com._1c.g5.v8.dt.rights.model.RightsPackage;

/** Общая модель состава для проверки и исправления; остальные ссылки объекта не затрагивает. */
public final class MdCompositionSupport
{
    public record Composition(EObject owner, EStructuralFeature feature, List<? extends EObject> entries)
    {
    }

    public record BrokenReference(Composition composition, int index, EObject reference)
    {
    }

    public static Composition composition(EObject owner)
    {
        if (owner instanceof FunctionalOption option)
            return new Composition(owner, MdClassPackage.Literals.FUNCTIONAL_OPTION__CONTENT, option.getContent());
        if (owner instanceof FilterCriterion criterion)
            return new Composition(owner, MdClassPackage.Literals.FILTER_CRITERION__CONTENT, criterion.getContent());
        if (owner instanceof DefinedType type)
            return types(owner, MdClassPackage.Literals.DEFINED_TYPE__TYPE, type.getType());
        if (owner instanceof EventSubscription subscription)
            return types(owner, MdClassPackage.Literals.EVENT_SUBSCRIPTION__SOURCE, subscription.getSource());
        if (owner instanceof ExchangePlan plan)
            return new Composition(owner, MdClassPackage.Literals.EXCHANGE_PLAN__CONTENT, plan.getContent());
        if (owner instanceof CommonAttribute attribute)
            return new Composition(owner, MdClassPackage.Literals.COMMON_ATTRIBUTE__CONTENT, attribute.getContent());
        // Права — отдельный верхний BM-объект, а Role.rights — внешнее свойство.
        if (owner instanceof RoleDescription role)
            return new Composition(owner, RightsPackage.Literals.ROLE_DESCRIPTION__RIGHTS, role.getRights());
        return null;
    }

    private static Composition types(EObject owner, EStructuralFeature feature, TypeDescription types)
    {
        return new Composition(owner, feature, types == null ? List.of() : types.getTypes());
    }

    /** Для вложенной записи проверяет только её; для владельца — весь состав. */
    public static List<BrokenReference> findBrokenReferences(EObject object)
    {
        if (object instanceof ExchangePlanContentItem || object instanceof CommonAttributeContentItem
            || object instanceof ObjectRights)
        {
            EObject reference = referenceOf(object);
            if (reference == null || !reference.eIsProxy())
                return List.of();
            Composition composition = composition(object.eContainer());
            // Маркеру достаточно владельца и свойства. indexOf для каждой битой записи
            // превращал проверку состава с множеством битых ссылок в квадратичный обход.
            return composition == null ? List.of() : List.of(new BrokenReference(composition, -1, reference));
        }
        Composition composition = composition(object);
        if (composition == null && object instanceof TypeDescription)
            composition = composition(object.eContainer());
        if (composition == null)
            return List.of();

        List<BrokenReference> result = null;
        for (int index = 0; index < composition.entries().size(); index++)
        {
            // get() разрешает прямые ссылки списка перед проверкой eIsProxy().
            EObject reference = referenceOf(composition.entries().get(index));
            if (reference != null && reference.eIsProxy())
            {
                if (result == null)
                    result = new ArrayList<>();
                result.add(new BrokenReference(composition, index, reference));
            }
        }
        return result != null ? result : List.of();
    }

    public static EObject referenceOf(EObject entry)
    {
        // Геттеры разрешают ссылки внутри записей состава; null без ссылки не удаляем.
        if (entry instanceof ExchangePlanContentItem item)
            return item.getMdObject();
        if (entry instanceof CommonAttributeContentItem item)
            return item.getMetadata();
        if (entry instanceof ObjectRights rights)
            return rights.getObject();
        return entry;
    }

    /** Вызывается в штатной транзакции исправления EDT, по актуальному состоянию модели. */
    public static void removeBrokenReferences(EObject owner)
    {
        List<BrokenReference> broken = findBrokenReferences(owner);
        // Обратный порядок сохраняет индексы остальных элементов и их настройки.
        for (int index = broken.size() - 1; index >= 0; index--)
        {
            BrokenReference reference = broken.get(index);
            reference.composition().entries().remove(reference.index());
        }
    }

    private MdCompositionSupport() {}
}
