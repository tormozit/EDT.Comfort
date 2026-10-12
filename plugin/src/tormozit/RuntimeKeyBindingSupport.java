package tormozit;

import java.util.ArrayList;
import java.util.Objects;

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

    /**
     * Действует ли привязка в модели E4 (есть запись без тега deleted). Диспетчер клавиш
     * работает по модели, а {@code BindingService.getBindings()} — по старому менеджеру,
     * который {@code removeBinding} не затрагивает; пользовательские привязки к тому же
     * возвращаются в модель при её перечитывании из настроек.
     */
    static boolean isLiveInModel(Binding binding)
    {
        MApplication application = PlatformUI.getWorkbench().getService(MApplication.class);
        if (application == null || binding == null || binding.getContextId() == null)
            return false;
        for (MBindingTable table : new ArrayList<>(application.getBindingTables()))
        {
            if (table.getBindingContext() == null
                    || !binding.getContextId().equals(table.getBindingContext().getElementId()))
                continue;
            for (MKeyBinding modelBinding : new ArrayList<>(table.getBindings()))
            {
                if (binding.equals(modelBinding.getTransientData().get("binding")) //$NON-NLS-1$
                        && !modelBinding.getTags().contains("deleted")) //$NON-NLS-1$
                    return true;
            }
        }
        return false;
    }

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
                    // Запись той же привязки с другим типом (в модели сохранён тег type:user,
                    // а восстанавливаем SYSTEM) — та же запись. Иначе addBinding ниже найдёт её
                    // через isSameBinding и подменит transientData объектом, которого нет в рабочей
                    // таблице: при следующем событии тегов в таблицу попадёт вторая привязка той
                    // же команды. Конфликт команды с самой собой KeyAssistDialog.isShowingBindings
                    // не узнаёт (сравнивает по имени команды), и окно конфликта переоткрывается
                    // на каждом автоповторе клавиши.
                    if (binding.equals(cached)
                            || cached instanceof Binding other && differsOnlyByType(binding, other))
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

    private static boolean differsOnlyByType(Binding a, Binding b)
    {
        return a.getType() != b.getType()
                && Objects.equals(a.getParameterizedCommand(), b.getParameterizedCommand())
                && Objects.equals(a.getTriggerSequence(), b.getTriggerSequence())
                && Objects.equals(a.getContextId(), b.getContextId())
                && Objects.equals(a.getSchemeId(), b.getSchemeId())
                && Objects.equals(a.getLocale(), b.getLocale())
                && Objects.equals(a.getPlatform(), b.getPlatform());
    }
}
