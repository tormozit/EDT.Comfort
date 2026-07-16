package tormozit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceNode;
import org.eclipse.jface.preference.PreferenceManager;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Персистентный кэш индекса текстов контролов страниц диалога «Параметры» —
 * между сессиями EDT, чтобы офскрин-индексация не выполнялась заново при
 * каждом запуске. Формат — по образцу {@code RecentPlaces}/{@code ObjectSets}
 * (единственный принятый в проекте способ персистентности): один ключ
 * {@link ScopedPreferenceStore}, ручная построчная сериализация.
 */
final class PreferenceSearchIndexCache
{
    private static final String PREF_KEY = "comfort.preferenceSearch.index.cache"; //$NON-NLS-1$

    private static final String NODE_MARKER = "@NODE"; //$NON-NLS-1$

    private static ScopedPreferenceStore prefs;

    private PreferenceSearchIndexCache()
    {
    }

    record CacheData(String signature, Map<String, Set<String>> textsByNodeId)
    {
    }

    /**
     * Сигнатура — хэш списка id всех preference-узлов (мгновенно, без
     * создания виджетов) И списка всех установленных бандлов с версиями.
     * Максимально чувствительная нарочно: любое изменение состава/версий
     * плагинов должно инвалидировать кэш (но не перестраивать его
     * автоматически — см. {@link PreferenceSearchIndex}).
     */
    @SuppressWarnings("unchecked")
    static String computeSignature(PreferenceManager manager)
    {
        List<String> nodeIds = new ArrayList<>();
        if (manager != null)
        {
            for (Object element : manager.getElements(PreferenceManager.POST_ORDER))
                nodeIds.add(((IPreferenceNode)element).getId());
        }
        Collections.sort(nodeIds);

        List<String> bundleParts = new ArrayList<>();
        try
        {
            Bundle self = FrameworkUtil.getBundle(PreferenceSearchIndexCache.class);
            if (self != null && self.getBundleContext() != null)
            {
                for (Bundle bundle : self.getBundleContext().getBundles())
                    bundleParts.add(bundle.getSymbolicName() + "_" + bundle.getVersion()); //$NON-NLS-1$
            }
        }
        catch (Exception ignored)
        {
        }
        Collections.sort(bundleParts);

        StringBuilder sb = new StringBuilder();
        for (String id : nodeIds)
            sb.append(id).append('\n');
        for (String part : bundleParts)
            sb.append(part).append('\n');

        return sha256(sb.toString());
    }

    private static String sha256(String text)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash)
                hex.append(String.format("%02x", b)); //$NON-NLS-1$
            return hex.toString();
        }
        catch (Exception e)
        {
            return String.valueOf(text.hashCode());
        }
    }

    static CacheData tryLoad()
    {
        ScopedPreferenceStore store = prefs();
        if (store == null)
            return null;
        String raw = store.getString(PREF_KEY);
        if (raw == null || raw.isBlank())
            return null;

        String[] lines = raw.split("\n", -1); //$NON-NLS-1$
        if (lines.length == 0)
            return null;
        String signature = lines[0];

        Map<String, Set<String>> texts = new LinkedHashMap<>();
        Set<String> currentTexts = null;
        for (int i = 1; i < lines.length; i++)
        {
            String line = lines[i];
            if (line.startsWith(NODE_MARKER + "\t")) //$NON-NLS-1$
            {
                String nodeId = unescape(line.substring(NODE_MARKER.length() + 1));
                currentTexts = new LinkedHashSet<>();
                texts.put(nodeId, currentTexts);
            }
            else if (currentTexts != null && !line.isEmpty())
            {
                currentTexts.add(unescape(line));
            }
        }
        return new CacheData(signature, texts);
    }

    static void save(Map<String, Set<String>> textsByNodeId, String signature)
    {
        ScopedPreferenceStore store = prefs();
        if (store == null)
            return;

        StringBuilder sb = new StringBuilder();
        sb.append(signature).append('\n');
        for (Map.Entry<String, Set<String>> entry : textsByNodeId.entrySet())
        {
            sb.append(NODE_MARKER).append('\t').append(escape(entry.getKey())).append('\n');
            for (String text : entry.getValue())
                sb.append(escape(text)).append('\n');
        }

        store.setValue(PREF_KEY, sb.toString());
        try
        {
            store.save();
        }
        catch (Exception ex)
        {
            Global.log("PreferenceSearchIndexCache.save error: " + ex); //$NON-NLS-1$
        }
    }

    private static ScopedPreferenceStore prefs()
    {
        if (prefs != null)
            return prefs;
        try
        {
            String pluginId = FrameworkUtil.getBundle(PreferenceSearchIndexCache.class).getSymbolicName();
            prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
        }
        catch (Exception ignored)
        {
            return null;
        }
        return prefs;
    }

    private static String escape(String s)
    {
        if (s == null)
            return ""; //$NON-NLS-1$
        return s.replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\t", "\\t") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\n", "\\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String unescape(String s)
    {
        if (s == null)
            return ""; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length())
            {
                char next = s.charAt(i + 1);
                if (next == 'n')
                {
                    sb.append('\n');
                    i++;
                    continue;
                }
                if (next == 't')
                {
                    sb.append('\t');
                    i++;
                    continue;
                }
                if (next == '\\')
                {
                    sb.append('\\');
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
