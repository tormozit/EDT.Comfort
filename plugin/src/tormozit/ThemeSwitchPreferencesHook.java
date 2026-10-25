package tormozit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.e4.ui.css.swt.helpers.EclipsePreferencesHelper;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/**
 * Свои значения настроек отдельно для каждой темы оформления («Параметры → Общие → Внешний вид → Тема»).
 *
 * <p>Штатно при смене темы {@code EclipsePreferencesHandler.overrideProperty} записывает поверх
 * настроек все значения из CSS новой темы, в том числе заданные пользователем, а
 * {@code PartRenderingEngine.StylingPreferencesHandler.resetOverriddenPreferences} при следующей
 * смене темы или закрытии EDT удаляет их — заданное пользователем значение теряется безвозвратно.
 *
 * <p>Комфорт перед сменой темы запоминает заданные пользователем значения ключей, которыми управляют
 * темы, для уходящей темы, а после смены возвращает значения, запомненные для новой темы. Ключи,
 * заданные пользователем только в уходящей теме, в новой теме, если для неё уже есть запись,
 * сбрасываются к значению темы/по умолчанию.
 *
 * <p>Момент «перед сменой»: {@code ThemeEngine.setTheme(theme, restore=true)} синхронно записывает
 * {@code themeid} в узел {@value #THEME_NODE} и только затем рассылает событие смены темы, по которому
 * переписываются настройки. «После смены» — {@code asyncExec}: событие и {@code reapply} к этому
 * моменту уже отработали в том же вызове. Узлы настроек, которыми управляют темы, — те же, что берёт
 * штатный {@code StylingPreferencesHandler.getThemeRelatedPreferences}.
 */
public final class ThemeSwitchPreferencesHook implements IStartup
{
    private static final String THEME_NODE = "org.eclipse.e4.ui.css.swt.theme"; //$NON-NLS-1$
    private static final String THEME_ID_KEY = "themeid"; //$NON-NLS-1$

    /** Узел Комфорта: {@code themeUserPrefs/<тема>/<узел настроек>} — значения пользователя. */
    private static final String USER_PREFS_NODE = "themeUserPrefs"; //$NON-NLS-1$
    /** {@code themeKeys/<тема>/<узел настроек>} — ключи, которые тема записывает при переходе на неё. */
    private static final String THEME_KEYS_NODE = "themeKeys"; //$NON-NLS-1$
    /** Отметка в {@code themeUserPrefs/<тема>}: значения для темы уже записаны. */
    private static final String RECORDED_KEY = "recorded"; //$NON-NLS-1$

    private static Set<String> themeNodeNames;

    @Override
    public void earlyStartup()
    {
        InstanceScope.INSTANCE.getNode(THEME_NODE).addPreferenceChangeListener(event ->
        {
            if (THEME_ID_KEY.equals(event.getKey()))
                onThemeChanging(asString(event.getOldValue()), asString(event.getNewValue()));
        });
    }

    private static String asString(Object value)
    {
        return value instanceof String text && !text.isEmpty() ? text : null;
    }

    /** Вызывается до того, как тема перепишет настройки. */
    private static void onThemeChanging(String oldTheme, String newTheme)
    {
        if (newTheme == null)
            return;
        Map<String, Map<String, String>> before = new HashMap<>();
        Map<String, Set<String>> overriddenBefore = new HashMap<>();
        for (String nodeName : themeNodeNames())
        {
            IEclipsePreferences node = InstanceScope.INSTANCE.getNode(nodeName);
            before.put(nodeName, values(node));
            overriddenBefore.put(nodeName, new HashSet<>(EclipsePreferencesHelper.getOverriddenPropertyNames(node)));
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> onThemeChanged(oldTheme, newTheme, before, overriddenBefore));
    }

    /** Вызывается после того, как новая тема переписала настройки. */
    private static void onThemeChanged(String oldTheme, String newTheme, Map<String, Map<String, String>> before,
        Map<String, Set<String>> overriddenBefore)
    {
        Preferences userRoot = comfortNode().node(USER_PREFS_NODE);
        Preferences keysRoot = comfortNode().node(THEME_KEYS_NODE);
        Preferences oldUser = oldTheme == null ? null : userRoot.node(oldTheme);
        Preferences newUser = userRoot.node(newTheme);
        boolean newRecorded = newUser.getBoolean(RECORDED_KEY, false);
        Set<IEclipsePreferences> changed = new HashSet<>();

        for (String nodeName : themeNodeNames())
        {
            IEclipsePreferences node = InstanceScope.INSTANCE.getNode(nodeName);
            Set<String> overriddenAfter = new HashSet<>(EclipsePreferencesHelper.getOverriddenPropertyNames(node));
            Set<String> wasOverridden = overriddenBefore.getOrDefault(nodeName, Set.of());
            Map<String, String> oldValues = before.getOrDefault(nodeName, Map.of());
            Preferences newKeys = keysRoot.node(newTheme).node(nodeName);
            for (String key : overriddenAfter)
                newKeys.put(key, ""); //$NON-NLS-1$

            // Ключи, которыми управляют темы: записанные уходящей и новой темой и уже запомненные.
            Set<String> themed = new LinkedHashSet<>(wasOverridden);
            themed.addAll(overriddenAfter);
            Preferences oldUserNode = oldTheme == null ? null : oldUser.node(nodeName);
            if (oldTheme != null)
            {
                themed.addAll(keys(keysRoot.node(oldTheme).node(nodeName)));
                themed.addAll(keys(oldUserNode));
            }
            Preferences newUserNode = newUser.node(nodeName);
            Map<String, String> newValues = new HashMap<>();
            for (String key : keys(newUserNode))
                newValues.put(key, newUserNode.get(key, null));
            themed.addAll(newValues.keySet());

            // Уходящая тема: значения, заданные пользователем, а не темой.
            if (oldUserNode != null)
            {
                for (String key : themed)
                {
                    String value = oldValues.get(key);
                    if (value != null && !wasOverridden.contains(key))
                        oldUserNode.put(key, value);
                    else
                        oldUserNode.remove(key);
                }
            }

            // Новая тема: вернуть её значения пользователя, убрать перешедшие из уходящей темы.
            for (Map.Entry<String, String> entry : newValues.entrySet())
            {
                if (entry.getValue() == null || entry.getValue().equals(node.get(entry.getKey(), null)))
                    continue;
                node.put(entry.getKey(), entry.getValue());
                // Значение пользователя, а не темы: иначе его удалит сброс при закрытии EDT.
                if (EclipsePreferencesHelper.getOverriddenPropertyNames(node).contains(entry.getKey()))
                    EclipsePreferencesHelper.removeOverriddenByCssProperty(node, entry.getKey());
                changed.add(node);
            }
            if (newRecorded)
            {
                for (String key : themed)
                {
                    if (newValues.containsKey(key) || overriddenAfter.contains(key) || node.get(key, null) == null
                        || wasOverridden.contains(key) || !oldValues.containsKey(key))
                        continue;
                    node.remove(key);
                    changed.add(node);
                }
            }
        }
        if (oldUser != null)
            oldUser.putBoolean(RECORDED_KEY, true);
        for (IEclipsePreferences node : changed)
            flush(node);
        flush(comfortNode());
    }

    /** Узлы настроек, которыми управляют темы: как {@code StylingPreferencesHandler.getThemeRelatedPreferences}. */
    private static synchronized Set<String> themeNodeNames()
    {
        if (themeNodeNames != null)
            return themeNodeNames;
        Set<String> names = new LinkedHashSet<>();
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        for (String point : new String[] { "org.eclipse.e4.ui.css.swt.theme", "org.eclipse.ui.themes" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            for (IConfigurationElement element : registry.getConfigurationElementsFor(point))
            {
                String namespace = element.getNamespaceIdentifier();
                if (namespace != null)
                    names.add(namespace);
            }
        }
        themeNodeNames = names;
        return names;
    }

    private static Map<String, String> values(IEclipsePreferences node)
    {
        Map<String, String> result = new HashMap<>();
        for (String key : keys(node))
            result.put(key, node.get(key, null));
        return result;
    }

    private static List<String> keys(Preferences node)
    {
        if (node == null)
            return List.of();
        try
        {
            return List.of(node.keys());
        }
        catch (BackingStoreException e)
        {
            return List.of();
        }
    }

    private static IEclipsePreferences comfortNode()
    {
        return InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID);
    }

    private static void flush(Preferences node)
    {
        try
        {
            node.flush();
        }
        catch (BackingStoreException e)
        {
        }
    }
}
