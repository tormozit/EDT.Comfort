package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import org.eclipse.swt.widgets.Display;
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
 * колонке «Код проверки» панели (см. {@link ProblemViewHook}). Штатный дропдаун
 * «Открыть проверку...» заменяется кнопкой без меню: обе кнопки работают с
 * проверкой текущей страницы подсказки.
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
    private static final String DESCRIPTION_TEXT = "Открыть описание проверки"; //$NON-NLS-1$
    private static final String EDT_DESCRIPTION_DROPDOWN_CLASS = "CheckDescriptionHoverContributor$ToolbarDropdownAction"; //$NON-NLS-1$

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
        addToToolBar(manager, new OpenCheckSettingsAction(new ArrayList<>(codes), annotations, project));
        replaceDescriptionDropdown(manager, annotations);
        Debug.log("fillToolBar: " + codes); //$NON-NLS-1$
    }

    /**
     * Штатный дропдаун EDT «Открыть проверку...» ({@code CheckDescriptionHoverContributor$ToolbarDropdownAction},
     * пункты — {@code ValidationPreferencesAction} с полем {@code shortCheckUid}) заменяется
     * кнопкой без меню, открывающей описание проверки текущей страницы подсказки.
     */
    private static void replaceDescriptionDropdown(IToolBarManager manager, Collection<Annotation> annotations)
    {
        if (!(manager instanceof ContributionManager contributionManager))
            return;
        IContributionItem[] items = contributionManager.getItems();
        for (int i = 0; i < items.length; i++)
        {
            if (!(items[i] instanceof ActionContributionItem item)
                || !(item.getAction() instanceof IMenuCreator)
                || !item.getAction().getClass().getName().endsWith(EDT_DESCRIPTION_DROPDOWN_CLASS))
                continue;
            Map<String, IAction> byCode = new LinkedHashMap<>();
            if (Global.getField(item.getAction(), "items") instanceof List<?> inner) //$NON-NLS-1$
            {
                for (Object o : inner)
                {
                    IAction action = o instanceof ActionContributionItem aci ? aci.getAction() : null;
                    if (action != null && Global.getField(action, "shortCheckUid") instanceof String code) //$NON-NLS-1$
                        byCode.putIfAbsent(code, action);
                }
            }
            if (byCode.isEmpty())
            {
                Debug.log("replaceDescriptionDropdown: нет пунктов"); //$NON-NLS-1$
                return;
            }
            contributionManager.remove(item);
            contributionManager.insert(i, new ActionContributionItem(
                new OpenCheckDescriptionAction(byCode, annotations, item.getAction().getImageDescriptor())));
            return;
        }
    }

    /**
     * Код проверки текущей страницы подсказки. {@code pageKnown=false} — страницу
     * определить не удалось (тогда кнопки берут первую проверку).
     */
    private record CurrentPage(boolean pageKnown, String code)
    {
        static CurrentPage resolve(Collection<Annotation> annotations)
        {
            Annotation current = BslModuleSpellCheckHook.currentAnnotationHoverAnnotation(annotations);
            return new CurrentPage(current != null, checkCode(current));
        }

        boolean isCheck()
        {
            return !pageKnown || code != null;
        }
    }

    /** Открыть описание проверки текущей страницы — вместо штатного дропдауна EDT. */
    private static final class OpenCheckDescriptionAction extends Action
    {
        private final Map<String, IAction> byCode;
        private final Collection<Annotation> annotations;

        OpenCheckDescriptionAction(Map<String, IAction> byCode, Collection<Annotation> annotations,
            ImageDescriptor image)
        {
            super(DESCRIPTION_TEXT, AS_PUSH_BUTTON);
            this.byCode = byCode;
            this.annotations = new ArrayList<>(annotations);
            if (image != null)
                setImageDescriptor(image);
            CurrentPage page = CurrentPage.resolve(annotations);
            IAction target = page.code() != null ? byCode.get(page.code()) : null;
            boolean enabled = page.isCheck() && (!page.pageKnown() || target != null);
            setEnabled(enabled);
            String text = target != null ? target.getText() : DESCRIPTION_TEXT;
            setToolTipText(TooltipText.wrap(Display.getCurrent(), null, (enabled
                ? text
                : DESCRIPTION_TEXT + ".\nНедоступно: на текущей странице подсказки не проверка конфигурации") //$NON-NLS-1$
                + Global.pluginSignForTooltip()));
        }

        @Override
        public void run()
        {
            CurrentPage page = CurrentPage.resolve(annotations);
            Debug.log("description run: current=" + page.code() + " codes=" + byCode.keySet()); //$NON-NLS-1$ //$NON-NLS-2$
            IAction target = page.pageKnown()
                ? (page.code() != null ? byCode.get(page.code()) : null)
                : byCode.values().iterator().next();
            if (target != null)
                target.run();
        }
    }

    /** Код проверки аннотации ({@code SU...}) или {@code null}. */
    private static String checkCode(Annotation annotation)
    {
        if (!(annotation instanceof XtextAnnotation xtext) || xtext.getUriToProblem() == null)
            return null;
        String code = xtext.getIssueCode();
        return code != null && !code.isBlank() && code.startsWith(CHECK_ISSUE_PREFIX) ? code : null;
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
            String code = checkCode(annotation);
            if (code != null)
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
     *
     * <p>Если в точке наведения несколько проверок, открывается та, чью страницу
     * («◀ n/m ▶») подсказка показывает в момент нажатия — без списка выбора. На
     * странице без проверки (например, орфография) кнопка недоступна.
     */
    private static final class OpenCheckSettingsAction extends Action
    {
        private final IProject project;
        private final List<String> codes;
        private final Collection<Annotation> annotations;

        OpenCheckSettingsAction(List<String> codes, Collection<Annotation> annotations, IProject project)
        {
            super(ACTION_TEXT, AS_PUSH_BUTTON);
            this.codes = codes;
            this.annotations = new ArrayList<>(annotations);
            this.project = project;
            ImageDescriptor descriptor = iconDescriptor();
            if (descriptor != null)
                setImageDescriptor(descriptor);
            // Тулбар перезаполняется при каждом листании страниц — доступность по текущей странице.
            boolean enabled = CurrentPage.resolve(annotations).isCheck();
            setEnabled(enabled);
            setToolTipText(TooltipText.wrap(Display.getCurrent(), null, (enabled
                ? "Открыть настройку этой проверки на странице «Проверки» параметров проекта" //$NON-NLS-1$
                : "Открыть настройку проверки.\nНедоступно: на текущей странице подсказки не проверка конфигурации") //$NON-NLS-1$
                + Global.pluginSignForTooltip()));
        }

        @Override
        public void run()
        {
            CurrentPage page = CurrentPage.resolve(annotations);
            Debug.log("run: current=" + page.code() + " codes=" + codes); //$NON-NLS-1$ //$NON-NLS-2$
            // Страница определена, но это не проверка — открывать нечего.
            if (!page.isCheck())
                return;
            String code = page.code() != null ? page.code() : codes.get(0);
            Shell shell = Display.getCurrent() != null ? Display.getCurrent().getActiveShell() : null;
            ProblemViewHook.openCheckSettings(shell, project, code);
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
