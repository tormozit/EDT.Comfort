package tormozit;

import org.eclipse.jface.action.ContributionManager;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.SubContributionManager;

/** Добавление кнопок плагина в левый край тулбара с сохранением владения страницы EDT. */
final class ToolBarContributionSupport
{
    private ToolBarContributionSupport() {}

    static void prepend(IToolBarManager manager, IContributionItem... items)
    {
        IContributionManager parent = manager;
        while (parent instanceof SubContributionManager subManager)
            parent = subManager.getParent();
        ContributionManager root = (ContributionManager) parent;
        for (int i = 0; i < items.length; i++)
        {
            // Сначала регистрируем у страницы: SubContributionManager создаёт свою обёртку
            // и сохраняет её для переключения видимости и удаления при закрытии страницы.
            manager.add(items[i]);
            IContributionItem contribution = root.remove(items[i].getId());
            root.insert(i, contribution);
        }
    }
}
