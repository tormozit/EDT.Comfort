package tormozit;

import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.ICommonContentExtensionSite;
import org.eclipse.ui.navigator.ICommonLabelProvider;

/**
 * Подпись и иконка узла-группы общих модулей ({@link CommonModuleGroupNode}).
 * Для обычных элементов возвращает {@code null} — EDT сама отрисует их штатно.
 */
public final class CommonModuleGroupLabelProvider extends LabelProvider implements ICommonLabelProvider
{
    @Override
    public void init(ICommonContentExtensionSite aConfig)
    {
    }

    @Override
    public String getText(Object element)
    {
        return element instanceof CommonModuleGroupNode group ? group.getBaseName() : null;
    }

    @Override
    public String getDescription(Object element)
    {
        return element instanceof CommonModuleGroupNode group
                ? "Группа общих модулей: " + group.getBaseName() //$NON-NLS-1$
                : null;
    }

    @Override
    public Image getImage(Object element)
    {
        if (!(element instanceof CommonModuleGroupNode))
            return null;
        // Общий образ платформы — владеет им сама PlatformUI, диспоузить не нужно.
        return PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_OBJ_FOLDER);
    }

    @Override
    public void restoreState(IMemento aMemento)
    {
    }

    @Override
    public void saveState(IMemento aMemento)
    {
    }
}
