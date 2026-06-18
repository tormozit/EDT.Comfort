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
        if (entry == null || entry.key == null || entry.key.isBlank())
            return null;
        String key = entry.key.trim();
        int methodSep = key.indexOf(": "); //$NON-NLS-1$
        if (methodSep >= 0)
            return stripModuleTypeSuffix(key.substring(0, methodSep).trim());
        return stripModuleTypeSuffix(key);
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
