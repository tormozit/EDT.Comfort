package tormozit;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Разбор ключей записей «Последние места» для навигации в дереве МД.
 */
final class RecentPlacesKeys
{
    private static final Set<String> MODULE_SUFFIXES = new HashSet<>(Arrays.asList(
        "МодульОбъекта", "МодульМенеджера", "МодульНабораЗаписей", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "Модуль", "Форма")); //$NON-NLS-1$ //$NON-NLS-2$

    private RecentPlacesKeys() {}

    /**
     * Полное имя объекта метаданных для показа в навигаторе.
     * Метод → владелец объекта; модуль → объект без суффикса типа модуля.
     */
    static String mdObjectRef(RecentPlaces.Entry entry)
    {
        if (entry == null)
            return null;
        return mdObjectRefFromKey(entry.key);
    }

    static String mdObjectRefFromKey(String key)
    {
        if (key == null || key.isBlank())
            return null;
        String trimmed = key.trim();
        int methodSep = trimmed.indexOf(": "); //$NON-NLS-1$
        if (methodSep >= 0)
            return stripModuleTypeSuffix(trimmed.substring(0, methodSep).trim());
        return stripModuleTypeSuffix(trimmed);
    }

    private static String stripModuleTypeSuffix(String modulePath)
    {
        if (modulePath == null)
            return ""; //$NON-NLS-1$
        int dot = modulePath.lastIndexOf('.');
        if (dot < 0)
            return modulePath;
        String suffix = modulePath.substring(dot + 1);
        return MODULE_SUFFIXES.contains(suffix)
            ? modulePath.substring(0, dot)
            : modulePath;
    }
}
