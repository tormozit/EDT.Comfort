package tormozit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.validation.marker.IMarkerChildrenObjectProvider;

/**
 * Подчинённые верхние объекты для отбора «Текущий объект» панели «Проблемы конфигурации»: тела форм
 * и макетов в поддереве объекта (вложенность + внешние BM-объекты {@code BasicForm.getForm()} /
 * {@code BasicTemplate.getTemplate()}, как {@code UserTextSpellCheck}). Штатный
 * {@code BmMarkerChildrenObjectProvider} содержимого макетов не отдаёт. Реестр EDT объединяет ответы
 * всех провайдеров.
 */
public final class SubordinateTopObjectsMarkerProvider
    implements IMarkerChildrenObjectProvider
{
    @Override
    public Collection<Object> getSubordinateTopObjectIds(IProject project, Object objectId)
    {
        if (project == null || !(objectId instanceof Long id))
            return List.of();
        try
        {
            IBmModelManager manager = Global.getOsgiService(IBmModelManager.class);
            IBmModel model = manager == null ? null : manager.getModel(project);
            if (model == null || model.isDisposed())
                return List.of();
            return model.executeReadonlyTask(new AbstractBmTask<Collection<Object>>("comfort.subordinateTopObjects") //$NON-NLS-1$
            {
                @Override
                public Collection<Object> execute(IBmTransaction transaction, IProgressMonitor monitor)
                {
                    return collect(transaction.getObjectById(id.longValue()));
                }
            }, true);
        }
        catch (RuntimeException e)
        {
            // Модель закрывается или объект удалён — подчинённых нет.
            return List.of();
        }
    }

    private static Collection<Object> collect(IBmObject object)
    {
        // Конфигурация — не владелец форм и макетов объектов, обходить нечего.
        if (object == null || object.eIsProxy() || object instanceof Configuration)
            return List.of();
        List<Object> result = new ArrayList<>();
        ArrayDeque<EObject> pending = new ArrayDeque<>();
        pending.add(object);
        while (!pending.isEmpty())
        {
            EObject node = pending.remove();
            // Тело формы и макета — внешние самостоятельные BM-объекты, а не containment владельца.
            if (node instanceof BasicForm form)
                addBody(form.getForm(), result);
            if (node instanceof BasicTemplate template)
                addBody(template.getTemplate(), result);
            for (EReference containment : node.eClass().getEAllContainments())
            {
                if (containment.isDerived() || containment.isTransient() || containment.isVolatile())
                    continue;
                Object value = node.eGet(containment, true);
                if (value instanceof EObject child)
                    pending.add(child);
                else if (value instanceof List<?> children)
                    for (Object child : children)
                        if (child instanceof EObject nested)
                            pending.add(nested);
            }
        }
        return result;
    }

    private static void addBody(EObject body, List<Object> result)
    {
        // BM_NULL_ID (-1) — тело без своего объекта (форма без файла); -1 в отборе панели — «всегда ложь».
        if (body instanceof IBmObject bm && !bm.eIsProxy() && bm.bmIsTop() && bm.bmGetId() != IBmObject.BM_NULL_ID)
            result.add(Long.valueOf(bm.bmGetId()));
    }
}
