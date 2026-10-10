package tormozit.checks;

import com._1c.g5.v8.dt.validation.marker.BmObjectMarker;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com.e1c.g5.v8.dt.check.qfix.IFixContextFactory;
import com.e1c.g5.v8.dt.check.qfix.IFixSession;
import com.e1c.g5.v8.dt.check.qfix.components.BasicModelFixContext;

/** Контекст исправления по маркеру объекта модели — для исправлений, которые модель не редактируют. */
final class ModelFixContextFactory implements IFixContextFactory<BasicModelFixContext>
{
    @Override
    public BasicModelFixContext createContext(Marker marker, IFixSession session)
    {
        return marker instanceof BmObjectMarker modelMarker
            ? new BasicModelFixContext(modelMarker.getObjectId(), modelMarker.getFeatureId(), session.getDtProject())
            : null;
    }

    @Override
    public Class<BasicModelFixContext> getProvidedContextType() { return BasicModelFixContext.class; }
}
