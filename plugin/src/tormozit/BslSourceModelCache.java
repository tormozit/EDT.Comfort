package tormozit;

import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.bsl.model.BslPackage;

/** Кэш планов исходного BSL: привязка ссылок и расчёт типов не являются правкой исходника. */
final class BslSourceModelCache
{
    private record Key(BslSourceModelCache cache, Object key) {}
    private final boolean bindings;

    BslSourceModelCache()
    {
        this(false);
    }

    /** Индексу по объектам Variable нужны также изменения привязок featureEntries. */
    BslSourceModelCache(boolean bindings)
    {
        this.bindings = bindings;
    }

    <T> T get(Object key, Resource resource, Supplier<T> compute)
    {
        if (resource == null)
            return compute.get();
        SourceVersion version = version(resource);
        State state = bindings ? version.boundState : version.state;
        Key namespaced = new Key(this, key);
        @SuppressWarnings("unchecked")
        T known = (T)state.values.get(namespaced);
        if (known != null)
            return known;
        T result = compute.get();
        if (result != null)
            state.values.put(namespaced, result);
        return result;
    }

    static Object stamp(Resource resource)
    {
        return version(resource).state;
    }

    private static SourceVersion version(Resource resource)
    {
        SourceVersion version = (SourceVersion)EcoreUtil.getAdapter(resource.eAdapters(), SourceVersion.class);
        if (version == null)
        {
            version = new SourceVersion();
            resource.eAdapters().add(version);
        }
        version.checkRoots(resource);
        return version;
    }

    /** У этих служебных полей EDT derived=false/transient=false; это подтверждено API и логом прогона. */
    private static boolean computed(EStructuralFeature feature)
    {
        return feature.isDerived() || feature.isTransient()
            || binding(feature)
            || feature == BslPackage.Literals.VARIABLE__TYPE_STATE_PROVIDER
            || feature == BslPackage.Literals.METHOD__EVENT
            || feature == BslPackage.Literals.METHOD__USED
            || feature == BslPackage.Literals.INVOCATION__IS_SERVER_CALL;
    }

    private static boolean binding(EStructuralFeature feature)
    {
        return feature == BslPackage.Literals.STATIC_FEATURE_ACCESS__FEATURE_ENTRIES
            || feature == BslPackage.Literals.DYNAMIC_FEATURE_ACCESS__FEATURE_ENTRIES;
    }

    private static boolean bindingObject(Object notifier)
    {
        if (notifier instanceof com._1c.g5.v8.dt.bsl.model.FeatureEntry)
            return true;
        if (notifier instanceof EObject object)
            for (EObject current = object; current != null; current = current.eContainer())
                if (binding(current.eContainingFeature()))
                    return true;
        return false;
    }

    /** Учитываем также изменения внутри служебных объектов, например элементов featureEntries. */
    private static boolean computedObject(Object notifier)
    {
        if (!(notifier instanceof EObject object))
            return false;
        for (EObject current = object; current != null; current = current.eContainer())
            if (current.eContainingFeature() != null && computed(current.eContainingFeature()))
                return true;
        return false;
    }

    /** Расчёт, начатый до правки, может заполнить только старое состояние. */
    private static final class State
    {
        final Map<Object, Object> values = new ConcurrentHashMap<>();
    }

    private static final class SourceVersion extends EContentAdapter
    {
        volatile State state = new State();
        volatile State boundState = new State();
        private List<EObject> roots = List.of();
        private Object parseResult;

        /** ResourceImpl не обязан сообщать об изменении списка корней: перепарсинг проверяем и при чтении. */
        void checkRoots(Resource resource)
        {
            Object parsed = resource instanceof XtextResource xtext ? xtext.getParseResult() : null;
            if (roots.equals(resource.getContents()) && parsed == parseResult)
                return;
            parseResult = parsed;
            for (EObject previous : roots)
                if (!resource.getContents().contains(previous))
                    previous.eAdapters().remove(this);
            roots = List.copyOf(resource.getContents());
            for (EObject root : roots)
                if (!root.eAdapters().contains(this))
                    root.eAdapters().add(this);
            state = new State();
            boundState = new State();
        }

        @Override
        public boolean isAdapterForType(Object type)
        {
            return type == SourceVersion.class;
        }

        @Override
        public void notifyChanged(Notification notification)
        {
            super.notifyChanged(notification);
            if (notification.isTouch() || notification.getEventType() == Notification.RESOLVE
                || notification.getEventType() == Notification.REMOVING_ADAPTER)
                return;
            boolean ignored = computedObject(notification.getNotifier())
                || notification.getFeature() instanceof EStructuralFeature feature && computed(feature);
            if (notification.getNotifier() instanceof Resource)
            {
                int feature = notification.getFeatureID(Resource.class);
                ignored |= feature == Resource.RESOURCE__IS_MODIFIED || feature == Resource.RESOURCE__ERRORS
                    || feature == Resource.RESOURCE__WARNINGS || feature == Resource.RESOURCE__TIME_STAMP
                    || feature == Resource.RESOURCE__IS_TRACKING_MODIFICATION;
            }
            if (!ignored)
            {
                state = new State();
            }
            boolean bound = !ignored || bindingObject(notification.getNotifier())
                || notification.getFeature() instanceof EStructuralFeature feature && binding(feature);
            if (bound)
                boundState = new State();
        }
    }
}
