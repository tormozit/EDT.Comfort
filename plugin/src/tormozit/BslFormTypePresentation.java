package tormozit;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.internal.text.html.HTMLPrinter;

import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/** Представление конкретной формы без переименования платформенного типа в модели (#981). */
final class BslFormTypePresentation
{
    // Только строки: копии контекстов не удерживают модель проекта.
    private static final Map<TypeItem, List<FormLink>> COPIES = Collections.synchronizedMap(new WeakHashMap<>());

    private BslFormTypePresentation() {}

    private record FormLink(String label, String uri) {}

    static void inherit(TypeItem target, List<? extends TypeItem> sources)
    {
        Map<String, FormLink> forms = new LinkedHashMap<>();
        for (TypeItem source : sources)
            for (FormLink form : forms(source))
                forms.putIfAbsent(form.uri(), form);
        if (!forms.isEmpty())
            COPIES.put(target, List.copyOf(forms.values()));
    }

    static String text(TypeItem type)
    {
        List<FormLink> forms = forms(type);
        return forms.isEmpty() ? null : "Форма: " //$NON-NLS-1$
            + String.join(", ", forms.stream().map(FormLink::label).toList()); //$NON-NLS-1$
    }

    static String html(TypeItem type)
    {
        List<FormLink> forms = forms(type);
        if (forms.isEmpty())
            return null;
        List<String> links = new ArrayList<>();
        for (FormLink form : forms)
            links.add("<span data-comfort-form=\"" + form.uri() + "\">" //$NON-NLS-1$ //$NON-NLS-2$
                + HTMLPrinter.convertToHTMLContent(form.label()) + "</span>"); //$NON-NLS-1$
        return "Форма: " + String.join(", ", links); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static List<FormLink> forms(TypeItem item)
    {
        if (item == null || item.eIsProxy())
            return List.of();
        String name = McoreUtil.getTypeName(item);
        if (!"ClientApplicationForm".equals(name) && !"ManagedForm".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
            return List.of();
        List<FormLink> stored = COPIES.get(item);
        if (stored != null)
            return stored;
        Map<String, FormLink> forms = new LinkedHashMap<>();
        Set<ContextDef> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TypeItem alternative : BslDocCommentTypeMerge.alternatives(item))
            if (alternative instanceof Type type && !type.eIsProxy())
                collect(type.getContextDef(), forms, visited);
        return List.copyOf(forms.values());
    }

    private static void collect(ContextDef context, Map<String, FormLink> forms, Set<ContextDef> visited)
    {
        if (context == null || context.eIsProxy() || !visited.add(context))
            return;
        if (context.eContainer() instanceof AbstractForm form)
        {
            MdObject md = form.getMdForm();
            if (md != null && !md.eIsProxy())
            {
                List<String> path = new ArrayList<>();
                for (EObject owner = md; owner != null && !(owner instanceof Configuration); owner = owner.eContainer())
                    if (owner instanceof MdObject object)
                    {
                        // EDT использует CatalogForm, DocumentForm и другие подклассы BasicForm.
                        String kind = object instanceof CommonForm ? "ОбщаяФорма" //$NON-NLS-1$
                            : object instanceof BasicForm ? "Форма" //$NON-NLS-1$
                            : MdTypeMapping.anyToRu(object.eClass().getName());
                        if (kind == null || object.getName() == null || object.getName().isBlank())
                            return;
                        path.add(0, kind + "." + object.getName()); //$NON-NLS-1$
                    }
                var uri = EcoreUtil.getURI(md);
                if (md.eResource() != null && md.eResource().getResourceSet() != null)
                    uri = md.eResource().getResourceSet().getURIConverter().normalize(uri);
                String encoded = URLEncoder.encode(uri.toString(), StandardCharsets.UTF_8);
                forms.putIfAbsent(encoded, new FormLink(String.join(".", path), encoded)); //$NON-NLS-1$
            }
        }
        for (ContextDef ref : context.getRefContextDefs())
            collect(ref, forms, visited);
    }

}



