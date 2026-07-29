package tormozit;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILightweightLabelDecorator;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IDecoratorManager;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;

/**
 * Краткий суффикс типа макета в представлении {@link BasicTemplate} (обычный и общий макет)
 * в навигаторе EDT и в дереве категории «Макеты» редактора объекта метаданных.
 */
public final class TemplateTypeSuffixDecorator extends LabelProvider implements ILightweightLabelDecorator, IStartup
{
    private static final String DECORATOR_ID = "tormozit.templateTypeSuffixDecorator"; //$NON-NLS-1$
    private static final String PREF_AUTO_ENABLED = "tormozit.templateTypeSuffixDecorator.autoEnabled"; //$NON-NLS-1$

    /**
     * Декоратор {@code state="true"} в plugin.xml задаёт значение по умолчанию только для
     * никогда не виденного id; если первая попытка загрузки класса когда-либо провалилась
     * (напр. гонка при первом старте в PDE-разработке), Eclipse сам гасит декоратор и сохраняет
     * "false" в настройках рабочей области — навсегда, до ручного включения. Один раз за всё
     * время жизни плагина в этой рабочей области принудительно включаем декоратор, если он ещё
     * ни разу не был подтверждён включённым нами.
     */
    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display != null)
            display.asyncExec(TemplateTypeSuffixDecorator::ensureEnabledOnce);
    }

    private static void ensureEnabledOnce()
    {
        Activator activator = Activator.getDefault();
        IPreferenceStore store = activator != null ? activator.getPreferenceStore() : null;
        if (store == null || store.getBoolean(PREF_AUTO_ENABLED))
            return;
        try
        {
            IDecoratorManager manager = PlatformUI.getWorkbench().getDecoratorManager();
            if (!manager.getEnabled(DECORATOR_ID))
                manager.setEnabled(DECORATOR_ID, true);
        }
        catch (Exception ex)
        {
            Global.log("TemplateTypeSuffixDecorator ensureEnabledOnce error: " + ex); //$NON-NLS-1$
        }
        finally
        {
            store.setValue(PREF_AUTO_ENABLED, true);
        }
    }

    @Override
    public void decorate(Object element, IDecoration decoration)
    {
        Object model = element instanceof BasicTemplate ? element : NavigatorElementModels.resolveModel(element);
        if (!(model instanceof BasicTemplate basicTemplate))
            return;

        String suffix = shortLabel(basicTemplate.getTemplateType());
        if (suffix != null)
            decoration.addSuffix(" (" + suffix + ")");
    }

    private static String shortLabel(TemplateType type)
    {
        if (type == null)
            return null;
        switch (type)
        {
            case SPREADSHEET_DOCUMENT: return "Табл";
            case TEXT_DOCUMENT: return "Текст";
            case BINARY_DATA: return "Двоич";
            case HTML_DOCUMENT: return "HTML";
            case GEOGRAPHICAL_SCHEMA: return "Геог";
            case DATA_COMPOSITION_SCHEMA: return "Комп";
            case DATA_COMPOSITION_APPEARANCE_TEMPLATE: return "Оформ";
            case ADD_IN: return "Внеш";
            default: return null;
        }
    }
}
