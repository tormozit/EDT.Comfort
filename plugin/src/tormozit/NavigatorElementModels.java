package tormozit;

import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Извлечение модели метаданных ({@link MdObject} / {@link EObject}) из элемента дерева навигатора EDT.
 */
public final class NavigatorElementModels
{
    private static final String[] MODEL_METHODS = {
            "getModelObject", "getModel", "getMdObject", "getEObject", "getObject", "getTarget", "getElement" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
    };

    private NavigatorElementModels() {}

    /** MdObject или EObject, либо {@code null}. */
    public static Object resolveModel(Object element)
    {
        if (element == null)
            return null;
        if (element instanceof MdObject || element instanceof EObject)
            return element;

        EObject adapted = adapt(element, EObject.class);
        if (adapted != null)
            return adapted;

        for (String method : MODEL_METHODS)
        {
            Object value = Global.invoke(element, method);
            if (value instanceof MdObject || value instanceof EObject)
                return value;
        }

        Object nested = Global.invoke(element, "getElement"); //$NON-NLS-1$
        if (nested != null && nested != element)
            return resolveModel(nested);

        return null;
    }

    /** EObject (в т.ч. MdObject), либо {@code null}. */
    public static EObject resolveEObject(Object element)
    {
        Object model = resolveModel(element);
        return model instanceof EObject ? (EObject) model : null;
    }

    static <T> T adapt(Object object, Class<T> type)
    {
        if (type.isInstance(object))
            return type.cast(object);
        if (object instanceof IAdaptable)
        {
            Object adapted = ((IAdaptable) object).getAdapter(type);
            if (type.isInstance(adapted))
                return type.cast(adapted);
        }
        return Adapters.adapt(object, type);
    }
}
