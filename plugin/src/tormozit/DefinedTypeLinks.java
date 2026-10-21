package tormozit;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.naming.IQualifiedNameConverter;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.scoping.IScopeProvider;

import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;

/**
 * Ссылки на определяемые типы в подсказках: штатная документация EDT выводит
 * {@code ОпределяемыйТип.Имя} простым текстом. Клик — {@code comfortOpenMethodDefinition}
 * с URI объекта метаданных, меню «Объект» добавляет {@link MetadataTypeMenu}.
 */
final class DefinedTypeLinks
{
    /** Ищем имена только в тексте HTML, пропуская существующие ссылки, теги и скрипты. */
    private static final Pattern DEFINED_TYPE_TEXT = Pattern.compile(
        "(<a\\b[^>]*>[\\s\\S]*?</a\\s*>|<script\\b[^>]*>[\\s\\S]*?</script\\s*>" //$NON-NLS-1$
            + "|<style\\b[^>]*>[\\s\\S]*?</style\\s*>|<[^>]*>)" //$NON-NLS-1$
            + "|((?<![\\p{L}\\p{N}_])(?:ОпределяемыйТип|DefinedType)\\.[\\p{L}_][\\p{L}\\p{N}_]*)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private DefinedTypeLinks()
    {
    }

    /** Имена определяемых типов в тексте HTML вне ссылок. */
    static Set<String> names(String html)
    {
        Set<String> names = new LinkedHashSet<>();
        if (html == null)
            return names;
        Matcher text = DEFINED_TYPE_TEXT.matcher(html);
        while (text.find())
            if (text.group(2) != null)
                names.add(text.group(2));
        return names;
    }

    /** Имя → закодированный URI определяемого типа; только под чтением модели {@code resource}. */
    static Map<String, String> resolve(Set<String> names, XtextResource resource)
    {
        if (names == null || names.isEmpty() || resource == null || resource.getContents().isEmpty())
            return Map.of();
        var provider = resource.getResourceServiceProvider();
        var scopes = provider.get(IScopeProvider.class);
        var converter = provider.get(IQualifiedNameConverter.class);
        if (scopes == null || converter == null)
            return Map.of();
        var scope = scopes.getScope(resource.getContents().get(0),
            McorePackage.Literals.TYPE_DESCRIPTION__TYPES);
        Map<String, String> links = new LinkedHashMap<>();
        for (String name : names)
        {
            var description = scope.getSingleElement(converter.toQualifiedName(name));
            EObject object = description == null ? null : description.getEObjectOrProxy();
            if (object != null && object.eIsProxy())
                object = EcoreUtil.resolve(object, resource);
            if (!(object instanceof TypeSet type))
                continue;
            var producer = BslEditorHyperlinkHook.typeProducer(type);
            if (!(producer instanceof DefinedType))
                continue;
            var uri = resource.getResourceSet().getURIConverter().normalize(EcoreUtil.getURI(producer));
            links.put(name, URLEncoder.encode(uri.toString(), StandardCharsets.UTF_8));
        }
        return Map.copyOf(links);
    }

    /** Подставляет ссылки по готовой карте; уже существующие ссылки не трогает. */
    static String inject(String html, Map<String, String> links)
    {
        if (html == null || links == null || links.isEmpty())
            return html;
        Matcher text = DEFINED_TYPE_TEXT.matcher(html);
        StringBuilder result = new StringBuilder();
        while (text.find())
        {
            String name = text.group(2);
            String uri = name == null ? null : links.get(name);
            String replacement = uri == null ? text.group()
                : "<a class=\"comfort-defined-type\" href=\"#\" onclick=\"" //$NON-NLS-1$
                    + "comfortOpenMethodDefinition('" + uri + "');return false;\">" + name + "</a>"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            text.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        text.appendTail(result);
        return result.toString();
    }
}
