package tormozit;

import java.util.Locale;

/**
 * Порт 1С-функции {@code ИдентификаторИзПредставленияЛкс}: строит валидный идентификатор
 * из произвольного текстового представления (например, наименования реквизита).
 *
 * <p>Пример (со {@code specialCharReplacement = ""}, см. {@link #convert(String, String, String, String)}):
 * {@code "3-я Дебиторка По контрагентам с интервалами СНГ (для Руководства)"} →
 * {@code "_3яДебиторкаПоКонтрагентамСИнтерваламиСНГдляРуководства"} — недопустимые символы
 * отбрасываются, а следующий за ними допустимый символ переводится в верхний регистр
 * (склейка camelCase). Со {@code specialCharReplacement = "_"} те же символы заменяются на
 * {@code "_"} без изменения регистра соседних букв.
 */
public final class IdentifierFromRepresentation
{
    private static final String RUSSIAN_LOWER = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"; //$NON-NLS-1$
    private static final String LATIN_LOWER = "abcdefghijklmnopqrstuvwxyz"; //$NON-NLS-1$
    private static final String DIGITS = "0123456789"; //$NON-NLS-1$

    private IdentifierFromRepresentation() {}

    /** Как {@link #convert(String, String, String, String)} со значениями по умолчанию из BSL-оригинала. */
    public static String convert(String representation)
    {
        return convert(representation, "_", "", "_"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * @param representation         исходное представление (может быть {@code null})
     * @param emptyStringReplacement чем заменить пустое/пробельное представление (BSL-умолчание — {@code "_"})
     * @param extraAllowedChars      дополнительные разрешённые символы идентификатора
     * @param specialCharReplacement чем заменять недопустимый символ; если {@code ""} — символ
     *                               отбрасывается, а следующий допустимый символ переводится
     *                               в верхний регистр (camelCase-склейка); BSL-умолчание — {@code "_"}
     */
    public static String convert(String representation, String emptyStringReplacement,
        String extraAllowedChars, String specialCharReplacement)
    {
        String value = representation;
        if (isBlank(value))
            value = emptyStringReplacement;
        if (value == null)
            value = ""; //$NON-NLS-1$
        if (specialCharReplacement == null)
            specialCharReplacement = ""; //$NON-NLS-1$

        // Быстрый путь: представление уже является валидным одиночным идентификатором —
        // порт проверки «Новый Структура(Представление)» из оригинала.
        if (value.equals(value.trim()) && value.indexOf(',') < 0 && isBareIdentifier(value))
            return value;

        String working = value;
        if (!working.isEmpty() && isAsciiDigit(working.charAt(0)))
            working = "_" + working; //$NON-NLS-1$

        String allowedChars = identifierCharSet(extraAllowedChars);
        StringBuilder result = new StringBuilder(working.length());
        // "Предыдущий символ" изначально считается пробелом — как и в оригинале на BSL
        // (ПустаяСтрока(" ") = Истина), из-за чего самый первый обработанный символ
        // переводится в верхний регистр.
        String previousChar = " "; //$NON-NLS-1$
        for (int i = 0; i < working.length(); i++)
        {
            String currentChar = String.valueOf(working.charAt(i));
            if (isBlank(previousChar))
                currentChar = currentChar.toUpperCase(Locale.ROOT);

            char lower = Character.toLowerCase(currentChar.charAt(0));
            if (allowedChars.indexOf(lower) >= 0)
            {
                result.append(currentChar);
            }
            else
            {
                result.append(specialCharReplacement);
                if (specialCharReplacement.isEmpty())
                    currentChar = " "; //$NON-NLS-1$
            }
            previousChar = currentChar;
        }
        return result.toString();
    }

    private static String identifierCharSet(String extraAllowedChars)
    {
        String extra = extraAllowedChars != null ? extraAllowedChars.toLowerCase(Locale.ROOT) : ""; //$NON-NLS-1$
        return "_" + DIGITS + RUSSIAN_LOWER + LATIN_LOWER + extra; //$NON-NLS-1$
    }

    private static boolean isBareIdentifier(String s)
    {
        if (s.isEmpty())
            return false;
        char first = s.charAt(0);
        if (isAsciiDigit(first) || !isIdentifierChar(first))
            return false;
        for (int i = 1; i < s.length(); i++)
        {
            if (!isIdentifierChar(s.charAt(i)))
                return false;
        }
        return true;
    }

    private static boolean isIdentifierChar(char c)
    {
        char lower = Character.toLowerCase(c);
        return lower == '_' || isAsciiDigit(lower)
            || LATIN_LOWER.indexOf(lower) >= 0 || RUSSIAN_LOWER.indexOf(lower) >= 0;
    }

    private static boolean isAsciiDigit(char c)
    {
        return c >= '0' && c <= '9';
    }

    private static boolean isBlank(String s)
    {
        return s == null || s.trim().isEmpty();
    }
}
