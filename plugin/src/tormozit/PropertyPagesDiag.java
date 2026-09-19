package tormozit;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.IStartup;
import org.osgi.framework.Bundle;

/**
 * Временная диагностика (issue #520): найти класс/бандл страницы «Настройки
 * запуска/отладки» в свойствах проекта — статический поиск по декомпилированным
 * бандлам результата не дал (ни в одном plugin.xml/*.properties из ~400 просмотренных
 * не нашлось "запуска"+"отладк" рядом). Логирует все {@code org.eclipse.ui.propertyPages},
 * чей id/class/имя (сырое, с "%") содержит launch/debug/запуск/отладк — независимо
 * от перевода. Снять после того, как страница найдена.
 */
public final class PropertyPagesDiag implements IStartup
{
    private static final String TAG = "PropertyPagesDiag"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        try
        {
            dump();
        }
        catch (Throwable t)
        {
            Global.tempLogException(TAG, "earlyStartup", t); //$NON-NLS-1$
        }
    }

    private static void dump()
    {
        IConfigurationElement[] pages =
                Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.propertyPages"); //$NON-NLS-1$
        Global.tempLog(TAG, "всего propertyPages=" + pages.length); //$NON-NLS-1$
        int matched = 0;
        for (IConfigurationElement page : pages)
        {
            String id = page.getAttribute("id"); //$NON-NLS-1$
            String clazz = page.getAttribute("class"); //$NON-NLS-1$
            String rawName = page.getAttribute("name"); //$NON-NLS-1$
            String contributor = page.getContributor() == null ? null : page.getContributor().getName();
            String resolvedName = resolveName(contributor, rawName);
            String haystack = lower(id) + ' ' + lower(clazz) + ' ' + lower(rawName) + ' ' + lower(resolvedName);
            if (haystack.contains("launch") || haystack.contains("debug") //$NON-NLS-1$ //$NON-NLS-2$
                    || haystack.contains("запуск") || haystack.contains("отладк")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                matched++;
                Global.tempLog(TAG, "MATCH id=" + id + " class=" + clazz //$NON-NLS-1$ //$NON-NLS-2$
                        + " name=" + resolvedName + " contributor=" + contributor); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        Global.tempLog(TAG, "совпадений launch/debug/запуск/отладк=" + matched); //$NON-NLS-1$
    }

    private static String resolveName(String contributorName, String rawName)
    {
        if (rawName == null || contributorName == null)
            return rawName;
        Bundle bundle = Platform.getBundle(contributorName);
        if (bundle == null)
            return rawName;
        return Platform.getResourceString(bundle, rawName);
    }

    private static String lower(String s)
    {
        return s == null ? "" : s.toLowerCase(); //$NON-NLS-1$
    }
}
