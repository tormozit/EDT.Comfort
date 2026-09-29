package tormozit;

import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILightweightLabelDecorator;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IDecoratorManager;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;

/**
 * Суффикс вида первого типа в составе определяемого типа: «Объект» или «Менеджер»
 * (по окончанию имени типа, например «СправочникОбъект.Номенклатура» → «Объект»).
 * Для «Ссылка» суффикс не показывается: это самый частый случай и он не несёт информации.
 * Показывается серым шрифтом везде, где EDT применяет декораторы к {@link DefinedType}
 * (навигатор, дерево категории «Определяемые типы» редактора объекта и т.п.).
 */
public final class DefinedTypeDecorator extends LabelProvider implements ILightweightLabelDecorator, IStartup
{
    private static final String DECORATOR_ID = "tormozit.definedTypeDecorator"; //$NON-NLS-1$
    private static final String PREF_AUTO_ENABLED = "tormozit.definedTypeDecorator.autoEnabled"; //$NON-NLS-1$
    private static final String NONE = ""; //$NON-NLS-1$
    private static final String[] KINDS = { "Объект", "Менеджер" }; //$NON-NLS-1$ //$NON-NLS-2$

    private static final ConcurrentHashMap<String, String> KIND_CACHE = new ConcurrentHashMap<>();

    /** См. {@link TemplateTypeSuffixDecorator#earlyStartup()}: одноразовое принудительное включение. */
    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display != null)
            display.asyncExec(DefinedTypeDecorator::ensureEnabledOnce);
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
            Global.log("DefinedTypeDecorator ensureEnabledOnce error: " + ex); //$NON-NLS-1$
        }
        finally
        {
            store.setValue(PREF_AUTO_ENABLED, true);
        }
    }

    @Override
    public void decorate(Object element, IDecoration decoration)
    {
        Object model = element instanceof DefinedType ? element : NavigatorElementModels.resolveModel(element);
        if (!(model instanceof DefinedType definedType))
            return;
        String kind = kindFor(definedType);
        if (kind != null)
            decoration.addSuffix(" (" + kind + ")");
    }

    private static String kindFor(DefinedType definedType)
    {
        String key = cacheKey(definedType);
        if (key != null)
        {
            String cached = KIND_CACHE.get(key);
            if (cached != null)
                return cached.isEmpty() ? null : cached;
        }
        String kind = computeKind(definedType);
        if (key != null)
            KIND_CACHE.put(key, kind == null ? NONE : kind);
        return kind;
    }

    private static String cacheKey(EObject object)
    {
        try
        {
            URI uri = EcoreUtil.getURI(object);
            return uri != null ? uri.toString() : null;
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    private static String computeKind(DefinedType definedType)
    {
        try
        {
            TypeDescription description = definedType.getTypeDescription();
            if (description == null)
                return null;
            for (TypeItem item : description.getTypes())
            {
                if (item == null)
                    continue;
                TypeItem resolved = item.eIsProxy() ? (TypeItem) EcoreUtil.resolve(item, definedType) : item;
                String name = McoreUtil.getTypeNameRu(resolved);
                if (name == null || name.isBlank())
                    name = McoreUtil.getTypeName(resolved);
                if (name == null || name.isBlank())
                    continue;
                int dot = name.indexOf('.');
                String fragment = dot > 0 ? name.substring(0, dot) : name;
                // Только первый тип: если он не «Объект/Ссылка/Менеджер», суффикса нет.
                for (String kind : KINDS)
                    if (fragment.endsWith(kind))
                        return kind;
                return null;
            }
        }
        catch (RuntimeException ex)
        {
            return null;
        }
        return null;
    }
}
