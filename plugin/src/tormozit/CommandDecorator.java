package tormozit;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILightweightLabelDecorator;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IDecoratorManager;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.mcore.CommandGroupCategory;
import com._1c.g5.v8.dt.mcore.StandardCommandGroup;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.BasicCommand;
import com._1c.g5.v8.dt.metadata.mdclass.CommandGroup;

/**
 * Суффикс «(Парам)» у параметризуемой команды (общей и команды объекта метаданных), то есть
 * такой, у которой задан тип параметра команды. Показывается везде, где EDT применяет декораторы
 * к {@link BasicCommand} (навигатор, дерево команд редактора объекта и т.п.).
 *
 * <p>Тот же суффикс — у группы команд, в которой может стоять параметризуемая команда, в окне
 * «Выбор группы команд» ({@link #groupSuffix}, подключение — {@link FormMainAttributeTypeDecorator}).
 */
public final class CommandDecorator extends LabelProvider implements ILightweightLabelDecorator, IStartup
{
    private static final String DECORATOR_ID = "tormozit.commandParameterDecorator"; //$NON-NLS-1$
    private static final String PREF_AUTO_ENABLED = "tormozit.commandParameterDecorator.autoEnabled"; //$NON-NLS-1$
    private static final String PARAM = "Парам"; //$NON-NLS-1$

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
            decoration.addSuffix(" (" + PARAM + ")");
    }

    /**
     * Суффикс (без скобок) группы команд, допускающей параметризуемые команды; {@code null} — у прочих
     * элементов или при выключенном декораторе.
     */
    static String groupSuffix(Object element)
    {
        if (!allowsParameter(element))
            return null;
        try
        {
            return PlatformUI.getWorkbench().getDecoratorManager().getEnabled(DECORATOR_ID) ? PARAM : null;
        }
        catch (RuntimeException ex)
        {
            return PARAM;
        }
    }

    /** Группа панели навигации формы или командной панели формы (стандартная либо своя). */
    static boolean allowsParameter(Object group)
    {
        CommandGroupCategory category = null;
        if (group instanceof StandardCommandGroup standard)
            category = standard.getCategory();
        else if (group instanceof CommandGroup custom)
            category = custom.getCategory();
        return category == CommandGroupCategory.FORM_NAVIGATION_PANEL
            || category == CommandGroupCategory.FORM_COMMAND_BAR;
    }
}
