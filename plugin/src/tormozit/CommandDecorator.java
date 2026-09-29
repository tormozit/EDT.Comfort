package tormozit;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILightweightLabelDecorator;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IDecoratorManager;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.BasicCommand;

/**
 * Суффикс «(Парам)» у параметризуемой команды (общей и команды объекта метаданных), то есть
 * такой, у которой задан тип параметра команды. Показывается везде, где EDT применяет декораторы
 * к {@link BasicCommand} (навигатор, дерево команд редактора объекта и т.п.).
 */
public final class CommandDecorator extends LabelProvider implements ILightweightLabelDecorator, IStartup
{
    private static final String DECORATOR_ID = "tormozit.commandParameterDecorator"; //$NON-NLS-1$
    private static final String PREF_AUTO_ENABLED = "tormozit.commandParameterDecorator.autoEnabled"; //$NON-NLS-1$

    /** См. {@link TemplateTypeSuffixDecorator#earlyStartup()}: одноразовое принудительное включение. */
    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display != null)
            display.asyncExec(CommandDecorator::ensureEnabledOnce);
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
            Global.log("CommandDecorator ensureEnabledOnce error: " + ex); //$NON-NLS-1$
        }
        finally
        {
            store.setValue(PREF_AUTO_ENABLED, true);
        }
    }

    @Override
    public void decorate(Object element, IDecoration decoration)
    {
        Object model = element instanceof BasicCommand ? element : NavigatorElementModels.resolveModel(element);
        if (!(model instanceof BasicCommand command))
            return;
        TypeDescription description = command.getCommandParameterType();
        if (description != null && !description.getTypes().isEmpty())
            decoration.addSuffix(" (Парам)");
    }
}
