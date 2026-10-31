package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.ui.editor.hover.html.XtextBrowserInformationControlInput;

import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Шаблонные (виртуальные) типы платформы: «ДокументОбъектИмяДокумента» — анонимный тип, только свои
 * члены. Его короткий брат «ДокументОбъект» — набор типов вида метаданных ({@code v8:/TypeSets/Md}),
 * объединение всех дочерних типов. Связь берётся из модели EDT: шаблон — базовый тип своего
 * набора. Имя шаблона не начало имени реального типа.
 */
final class PlatformTemplateTypes
{
    private static final String NOTE_CLASS = "comfort-template-type"; //$NON-NLS-1$
    private static final String TYPE_SETS_SCHEME = "v8"; //$NON-NLS-1$
    private static volatile Model model;

    private PlatformTemplateTypes() {}

    /**
     * Набор вида метаданных без конкретных типов в проекте («ПланСчетовОбъект» без планов счетов)
     * раскрылся бы в пустой список членов — вместо него берётся его шаблон, анонимный тип с общими
     * членами. Типы из комментария приходят ссылками {@code v8:/TypeSets/Md/…}; {@code context} — объект
     * модуля, по проекту которого считаются конкретные типы.
     */
    static Collection<?> emptySetsToTemplates(Collection<?> types, EObject context)
    {
        List<Object> result = null;
        int index = 0;
        for (Object raw : types)
        {
            List<Type> templates = raw instanceof TypeSet set && isMdTypeSet(set) ? templatesOfEmpty(set, context) : null;
            if (templates != null && result == null)
                result = new ArrayList<>(new ArrayList<>(types).subList(0, index));
            if (result != null)
            {
                if (templates != null)
                    result.addAll(templates);
                else
                    result.add(raw);
            }
            index++;
        }
        return result != null ? result : types;
    }

    private static boolean isMdTypeSet(TypeSet set)
    {
        URI uri = EcoreUtil.getURI(set);
        return uri != null && TYPE_SETS_SCHEME.equals(uri.scheme()) && uri.segmentCount() >= 2
            && "TypeSets".equals(uri.segment(0)) && "Md".equals(uri.segment(1)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Шаблоны набора без конкретных типов; {@code null} — типы есть или набор не разрешился. */
    private static List<Type> templatesOfEmpty(TypeSet proxy, EObject context)
    {
        if (!(EcoreUtil.resolve(proxy, context) instanceof TypeSet set) || set.eIsProxy()
            || !set.types(context).isEmpty())
            return null;
        List<Type> templates = new ArrayList<>();
        for (Type base : set.getBaseTypes())
            if (EcoreUtil.resolve(base, context) instanceof Type template && !template.eIsProxy())
                templates.add(template);
        return templates.isEmpty() ? null : templates;
    }

    /** Имя шаблонного типа, английское или русское, без учёта регистра. */
    static boolean isTemplateName(String name)
    {
        return name != null && model().templates.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Текст замечания по имени типа (английскому или русскому); {@code null} — тип не шаблонный и не набор
     * вида метаданных.
     */
    static String noteText(String typeName)
    {
        if (typeName == null)
            return null;
        String name = typeName.toLowerCase(Locale.ROOT);
        Model current = model();
        return current.templates.contains(name) ? "Анонимный (базовый) тип. Содержит только свои члены." //$NON-NLS-1$
            : current.sets.containsKey(name) ? "Объединение всех дочерних типов." : null; //$NON-NLS-1$
    }

    /**
     * Замечание под заголовком подсказки шаблонного типа — одна вставка для подсказки при наведении
     * и боковой подсказки. {@code element} — объект строки списка или наведения; без него берётся
     * объект самой подсказки EDT, затем её страница описания типа.
     */
    static Object withNote(Object info, Object element)
    {
        return withNote(info, element, null);
    }

    /**
     * {@code rowName} — имя строки списка, у которой нет объекта: штатный список типов в комментарии
     * хранит только имя.
     */
    static Object withNote(Object info, Object element, String rowName)
    {
        EObject object = element instanceof EObject eObject ? eObject
            : info instanceof XtextBrowserInformationControlInput input ? input.getElement() : null;
        // Строка списка держит тип прокси; McoreUtil читает имя и у прокси.
        String typeName = object instanceof Type type ? McoreUtil.getTypeName(type)
            : object == null ? pageTypeName(info, rowName) : null;
        String text = noteText(typeName);
        if (text == null)
            return info;
        String note = "<div class=\"" + NOTE_CLASS + "\">" //$NON-NLS-1$ //$NON-NLS-2$
            + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</div>"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        // Строка списка без описания EDT: подсказка — само замечание, документом как у описания ИР.
        String html = info == null ? null : IrBslHoverHtml.readDisplayHtml(info);
        if (html == null || html.isEmpty())
            return IrBslHoverHtml.mergeHtml("", note); //$NON-NLS-1$
        if (html.contains(NOTE_CLASS))
            return info;
        // Сразу под заголовком, как блок обработчиков формы: низ подсказки обрезается высотой окна.
        int insertAt = html.indexOf("<div class=\"hover-content\">"); //$NON-NLS-1$
        if (insertAt < 0)
        {
            int body = html.toLowerCase(Locale.ROOT).indexOf("<body"); //$NON-NLS-1$
            insertAt = body >= 0 ? html.indexOf('>', body) + 1 : 0;
        }
        String result = html.substring(0, insertAt) + note + html.substring(insertAt);
        return IrBslHoverHtml.isBslBrowserInput(info) ? IrBslHoverHtml.withHtml(info, result) : result;
    }

    /**
     * Подсказка при наведении на тип в комментарии приходит без объекта: имя типа есть только у её
     * страницы описания ({@code TypeBslDocumentationPage}, поля {@code firstName}/{@code secondName}).
     */
    private static String pageTypeName(Object info, String rowName)
    {
        Object page = info == null || !IrBslHoverHtml.isBslBrowserInput(info) ? null
            : Global.invoke(info, "getHoverPage"); //$NON-NLS-1$
        if (page != null && "TypeBslDocumentationPage".equals(page.getClass().getSimpleName())) //$NON-NLS-1$
        {
            for (String field : new String[] { "firstName", "secondName" }) //$NON-NLS-1$ //$NON-NLS-2$
                if (Global.getField(page, field) instanceof String name && noteText(name) != null)
                    return name;
            return null;
        }
        return rowName;
    }

    /** Строится один раз по провайдеру типов. */
    private static Model model()
    {
        Model result = model;
        if (result != null)
            return result;
        Set<String> templates = new HashSet<>();
        Map<String, String> sets = new HashMap<>();
        try
        {
            IEObjectProvider provider = IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM,
                Version.LATEST);
            ResourceSet resources = new ResourceSetImpl();
            for (IEObjectDescription description : provider.getEObjectDescriptions(any -> true))
            {
                if (!TYPE_SETS_SCHEME.equals(description.getEObjectURI().scheme())
                    || !(EcoreUtil.resolve(description.getEObjectOrProxy(), resources) instanceof TypeSet set)
                    || set.eIsProxy() || set.getNameRu() == null)
                    continue;
                for (Type base : set.getBaseTypes())
                    if (EcoreUtil.resolve(base, resources) instanceof Type template && !template.eIsProxy()
                        && template.getName() != null)
                    {
                        templates.add(template.getName().toLowerCase(Locale.ROOT));
                        if (template.getNameRu() != null)
                            templates.add(template.getNameRu().toLowerCase(Locale.ROOT));
                        sets.put(set.getNameRu().toLowerCase(Locale.ROOT), template.getName());
                        if (set.getName() != null)
                            sets.put(set.getName().toLowerCase(Locale.ROOT), template.getName());
                    }
            }
        }
        catch (RuntimeException | LinkageError failure)
        {
            Global.logError("PlatformTemplateTypes", "model", failure); //$NON-NLS-1$ //$NON-NLS-2$
        }
        result = new Model(templates, sets);
        model = result;
        return result;
    }

    /** {@code sets} — имя набора вида метаданных → английское имя его шаблона. */
    private record Model(Set<String> templates, Map<String, String> sets) {}
}
