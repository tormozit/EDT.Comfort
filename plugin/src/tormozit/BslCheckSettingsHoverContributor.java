package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.ContributionManager;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IMenuCreator;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.bsl.ui.hover.IBslHoverContributor;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import org.eclipse.xtext.ui.editor.validation.XtextAnnotation;

/**
 * Кнопка «Открыть настройку проверки» в подсказке предупреждения в редакторе
 * модуля — в тулбаре подсказки, рядом со штатной кнопкой EDT «Открыть
 * проверку...» ({@code CheckDescriptionHoverContributor} бандла
 * {@code com._1c.g5.v8.dt.ui.validation}). Открывает страницу «Проверки»
 * параметров проекта с выделенной строкой проверки — как двойной щелчок в
 * колонке «Код проверки» панели (см. {@link ProblemViewHook}).
 *
 * <p>Регистрируется extension point'ом EDT
 * {@code com._1c.g5.v8.dt.bsl.ui.bslHoverContributor}. Предупреждения проверок
 * приходят в подсказку как {@link XtextAnnotation} с кодом {@code SU...}
 * (короткий код проверки в пределах проекта).
 *
 * <p>Отдельный файл, а не вложенный класс: точка входа из {@code plugin.xml}.
 */
public final class BslCheckSettingsHoverContributor implements IBslHoverContributor
{
    /** Префикс кодов проблем проверок конфигурации в модуле (короткий UID вида {@code SU47}). */
    private static final String CHECK_ISSUE_PREFIX = "SU"; //$NON-NLS-1$

    private static final String ACTION_TEXT = "Открыть настройку проверки"; //$NON-NLS-1$

    private static final String ICON_BUNDLE = "com._1c.g5.v8.dt.ui"; //$NON-NLS-1$
    private static final String ICON_PATH = "icons/etool16/show_properties.png"; //$NON-NLS-1$

    private static volatile ImageDescriptor iconDescriptor;

    @Override
    public void fillToolBar(IToolBarManager manager, Collection<Annotation> annotations)
    {
        if (manager == null || annotations == null || annotations.isEmpty())
            return;
        Set<String> codes = collectCheckCodes(annotations);
        if (codes.isEmpty())
            return;
        IProject project = resolveProject(annotations);
        if (project == null)
            return;
        List<OpenCheckSettingsAction> actions = new ArrayList<>();
        for (String code : codes)
            actions.add(new OpenCheckSettingsAction(code, project,
                codes.size() == 1 ? ACTION_TEXT : ACTION_TEXT + " (" + code + ")")); //$NON-NLS-1$ //$NON-NLS-2$
        addToToolBar(manager, actions.size() == 1 ? actions.get(0) : new CheckSettingsDropdownAction(actions));
        Debug.log("fillToolBar: " + codes); //$NON-NLS-1$
    }

    /**
     * Вставляет кнопку непосредственно перед штатным дропдауном EDT «Открыть
     * проверку...» (единственный {@link IMenuCreator} в этом тулбаре), а без него —
     * в начало: кнопки настроек и описания проверки должны стоять рядом, а не по
     * разным концам тулбара после кнопок навигации и синтакс-помощника.
     */
    private static void addToToolBar(IToolBarManager manager, IAction action)
    {
        IContributionItem contribution = new ActionContributionItem(action);
        if (manager instanceof ContributionManager contributionManager)
        {
            IContributionItem[] items = contributionManager.getItems();
            for (int i = 0; i < items.length; i++)
            {
                if (items[i] instanceof ActionContributionItem item
                    && item.getAction() instanceof IMenuCreator)
                {
                    contributionManager.insert(i, contribution);
                    return;
                }
            }
            contributionManager.insert(0, contribution);
            return;
        }
        manager.add(contribution);
    }

    /** Уникальные короткие коды проверок ({@code SU...}) из аннотаций подсказки, в порядке появления. */
    private static Set<String> collectCheckCodes(Collection<Annotation> annotations)
    {
        Set<String> codes = new LinkedHashSet<>();
        for (Annotation annotation : annotations)
        {
            if (!(annotation instanceof XtextAnnotation xtext) || xtext.getUriToProblem() == null)
                continue;
            String code = xtext.getIssueCode();
            if (code != null && !code.isBlank() && code.startsWith(CHECK_ISSUE_PREFIX))
                codes.add(code);
        }
        return codes;
    }

    private static IProject resolveProject(Collection<Annotation> annotations)
    {
        IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
        if (lookup == null)
            return null;
        for (Annotation annotation : annotations)
        {
            if (!(annotation instanceof XtextAnnotation xtext))
                continue;
            URI uri = xtext.getUriToProblem();
            if (uri == null)
                continue;
            IProject project = lookup.getProject(uri);
            if (project != null)
                return project;
        }
        return null;
    }

    private static ImageDescriptor iconDescriptor()
    {
        ImageDescriptor descriptor = iconDescriptor;
        if (descriptor == null)
        {
            try
            {
                Bundle bundle = Platform.getBundle(ICON_BUNDLE);
                if (bundle != null)
                {
                    descriptor = ImageDescriptor.createFromURL(bundle.getEntry(ICON_PATH));
                    iconDescriptor = descriptor;
                }
            }
            catch (RuntimeException e)
            {
                descriptor = null;
            }
        }
        return descriptor;
    }

    /**
     * Как {@code ValidationPreferencesAction.Mode.OPEN_PREFERENCES} EDT: страница
     * «Проверки» параметров проекта с выделенной строкой проверки.
     */
    private static final class OpenCheckSettingsAction extends Action
    {
        private final IProject project;
        private final String shortUid;

        OpenCheckSettingsAction(String shortUid, IProject project, String text)
        {
            super(text, AS_PUSH_BUTTON);
            this.shortUid = shortUid;
            this.project = project;
            ImageDescriptor descriptor = iconDescriptor();
            if (descriptor != null)
                setImageDescriptor(descriptor);
            setToolTipText(TooltipText.wrap(Display.getCurrent(), null,
                "Открыть настройку этой проверки на странице «Проверки» параметров проекта" //$NON-NLS-1$
                    + Global.pluginSignForTooltip()));
        }

        @Override
        public void run()
        {
            Shell shell = Display.getCurrent() != null ? Display.getCurrent().getActiveShell() : null;
            ProblemViewHook.openCheckSettings(shell, project, shortUid);
        }
    }

    /**
     * Несколько разных проверок в одной точке наведения — дропдаун, по пункту на
     * проверку (как {@code CheckDescriptionHoverContributor$ToolbarDropdownAction} EDT).
     */
    private static final class CheckSettingsDropdownAction extends Action implements IMenuCreator
    {
        private final List<OpenCheckSettingsAction> actions;
        private Menu menu;

        CheckSettingsDropdownAction(List<OpenCheckSettingsAction> actions)
        {
            super(ACTION_TEXT, AS_DROP_DOWN_MENU);
            this.actions = actions;
            ImageDescriptor descriptor = actions.get(0).getImageDescriptor();
            if (descriptor != null)
                setImageDescriptor(descriptor);
            setToolTipText(TooltipText.wrap(Display.getCurrent(), null,
                "Открыть настройку одной из этих проверок на странице «Проверки» параметров проекта" //$NON-NLS-1$
                    + Global.pluginSignForTooltip()));
            setMenuCreator(this);
        }

        @Override
        public void run()
        {
            actions.get(0).run();
        }

        @Override
        public Menu getMenu(Control parent)
        {
            disposeMenu();
            menu = new Menu(parent);
            fillMenu();
            return menu;
        }

        @Override
        public Menu getMenu(Menu parent)
        {
            disposeMenu();
            menu = new Menu(parent);
            fillMenu();
            return menu;
        }

        @Override
        public void dispose()
        {
            disposeMenu();
        }

        private void fillMenu()
        {
            for (OpenCheckSettingsAction action : actions)
                new ActionContributionItem(action).fill(menu, -1);
        }

        private void disposeMenu()
        {
            if (menu != null && !menu.isDisposed())
                menu.dispose();
            menu = null;
        }
    }

    private static final class Debug
    {
        private static final String TAG = "ProblemView"; //$NON-NLS-1$

        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
