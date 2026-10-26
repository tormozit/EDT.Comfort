package tormozit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.platform.doc.PlatformDocPage;
import com._1c.g5.v8.dt.platform.doc.PlatformDocPageKind;
import com._1c.g5.v8.dt.platform.doc.PlatformDocProvider;
import com._1c.g5.v8.dt.platform.doc.PlatformDocTreeNodeShortId;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Общий признак ЕстьЧисловойИндекс для автопроверки и автодополнения (#892).
 * Реестр 8.3.27 — выгрузка ИР; для типов с 8.5 — точная формула по русскому HTML.
 * Описание получает штатный PlatformDocProvider по коду типа и версии платформы.
 */
public final class BslNumericIndexTypes
{
    private static final Pattern SINCE = Pattern.compile("версии\\s+(\\d+)\\.(\\d+)");

    private BslNumericIndexTypes() {}

    /** true — разрешён; false — запрещён; null — данных для решения недостаточно. */
    public static Boolean allows(Type type, Resource context)
    {
        if (type == null || type.eIsProxy() || type.getNameRu() == null || type.getName() == null)
            return null;
        String category = McoreUtil.getTypeCategoryRu(type);
        if (Registry.INSTANCE.numericTypes.contains(category))
            return true;
        if (!(context instanceof XtextResource resource) || resource.getContents().isEmpty())
            return null;
        IRuntimeVersionSupport versions = resource.getResourceServiceProvider().get(IRuntimeVersionSupport.class);
        Version version = versions.getRuntimeVersionOrDefault(resource.getContents().get(0), Version.LATEST);
        int code = type.getCode();
        if (code <= 0 || code >= 100_000)
        {
            // Штатный резолвер возвращает платформенный объект с кодом, если у копии из AST кода нет.
            Object provider = BslSyntaxAssist.documentationProvider();
            Object canonical = Global.invoke(provider, "getTypeItemByName", McoreUtil.getTypeCategory(type), resource); //$NON-NLS-1$
            if (canonical instanceof Type platformType)
                code = platformType.getCode();
        }
        // PlatformDocTreeNodeShortId допускает только пятизначный код платформенного типа.
        if (code <= 0 || code >= 100_000)
            return null;
        Object injected = BslSyntaxAssist.injected(PlatformDocProvider.class.getName());
        if (!(injected instanceof PlatformDocProvider provider))
            return null;
        DocumentationKey key = new DocumentationKey(version, code);
        return Registry.INSTANCE.computed.computeIfAbsent(key,
            ignored -> Optional.ofNullable(fromDocumentation(provider, key))).orElse(null);
    }

    /** Штатный идентификатор типа исключает совпадения с разделами, свойствами и методами. */
    private static Boolean fromDocumentation(PlatformDocProvider provider, DocumentationKey key)
    {
        String path = provider.getPathByNodeId(key.version(), PlatformDocTreeNodeShortId.fromType(key.code()));
        if (path == null || path.isBlank())
            return null;
        try
        {
            PlatformDocPage page = provider.loadPage(path, key.version(), "ru"); //$NON-NLS-1$
            if (page.getKind() != PlatformDocPageKind.TYPE)
                return null;
            String sinceText = page.getValue(PlatformDocPage.AVAILABLE_SINCE);
            Matcher since = SINCE.matcher(sinceText == null ? "" : sinceText); //$NON-NLS-1$
            String html = page.getValue("content"); //$NON-NLS-1$
            if (!since.find() || html == null)
                return null;
            int major = Integer.parseInt(since.group(1));
            int minor = Integer.parseInt(since.group(2));
            boolean recent = major > 8 || major == 8 && minor >= 5;
            boolean allowed = recent && descriptionAllowsNumericIndex(html);
            return allowed;
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /** Формула из #892 применяется к HTML, который возвращает штатная страница описания типа. */
    private static boolean descriptionAllowsNumericIndex(String html)
    {
        return html.contains("оператора [...]. В качестве аргумента передается индекс")
            || html.contains("оператора [...]. В качестве аргумента передается позиция")
            || html.contains("оператора [...]. В качестве аргумента передается имя, индекс");
    }

    private static record DocumentationKey(Version version, int code) {}

    private static final class Registry
    {
        static final Registry INSTANCE = new Registry();
        final Set<String> numericTypes = new HashSet<>();
        final Map<DocumentationKey, Optional<Boolean>> computed = new ConcurrentHashMap<>();

        Registry()
        {
            try (InputStream stream = BslNumericIndexTypes.class.getResourceAsStream("numeric-index-types-8.3.27.txt")) //$NON-NLS-1$
            {
                if (stream == null)
                    throw new IOException("IR numeric index registry is missing"); //$NON-NLS-1$
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)))
                {
                    reader.lines().map(Registry::category).filter(name -> !name.isEmpty()).forEach(numericTypes::add);
                }
            }
            catch (IOException e)
            {
                // При недоступном реестре решение принимается только по доступной документации.
            }
        }

        private static String category(String name)
        {
            int dot = name.indexOf('.');
            return (dot < 0 ? name : name.substring(0, dot)).trim();
        }
    }
}
