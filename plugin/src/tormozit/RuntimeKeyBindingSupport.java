package tormozit;

import java.util.ArrayList;

import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.commands.MBindingTable;
import org.eclipse.e4.ui.model.application.commands.MKeyBinding;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.internal.keys.BindingService;

/** Восстановление временно снятых привязок в живой модели Eclipse. */
final class RuntimeKeyBindingSupport
{
    private RuntimeKeyBindingSupport() {}

    static void restore(BindingService service, Binding binding)
    {
        // BindingService.removeBinding(SYSTEM) добавляет тег deleted, а
        // createOrUpdateMKeyBinding при addBinding оставляет его на существующей записи.
        // Существующий объект Binding сохраняем: BindingTable.removeBinding сравнивает
        // активную запись по ссылке. addBinding перезаписал бы transientData равным,
        // но другим объектом, рассинхронизировав модель и рабочую таблицу.
        MApplication application = PlatformUI.getWorkbench().getService(MApplication.class);
        int matched = 0;
        if (application != null)
        {
            for (MBindingTable table : new ArrayList<>(application.getBindingTables()))
            {
                if (table.getBindingContext() == null
                        || !binding.getContextId().equals(table.getBindingContext().getElementId()))
                    continue;
                for (MKeyBinding modelBinding : new ArrayList<>(table.getBindings()))
                {
                    Object cached = modelBinding.getTransientData().get("binding"); //$NON-NLS-1$
                    if (binding.equals(cached))
                    {
                        matched++;
                        // Удаляем все повторения: несколько removeBinding могут добавить тег повторно.
                        modelBinding.getTags().removeIf("deleted"::equals); //$NON-NLS-1$
                    }
                }
            }
        }
        if (matched == 0)
            service.addBinding(binding);
    }
}
