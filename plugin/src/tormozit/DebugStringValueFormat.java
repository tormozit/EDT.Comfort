package tormozit;

import java.nio.charset.StandardCharsets;

import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.BslValueType;
import com._1c.g5.v8.dt.debug.core.model.values.IBslPrimitiveValue;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Префикс {@code [N]} перед кавычками для длинных и многострочных строк в колонке «Значение».
 */
final class DebugStringValueFormat
{
    static final int LONG_STRING_THRESHOLD = 150;
    static final int TOOLTIP_PREVIEW_MAX = 1000;

    private DebugStringValueFormat() {}

    /** Штатные деревья «Переменные» / инспектор — только при включённом флажке. */
    static String formatForStockTree(String displayed, IBslVariable variable, IBslValue value)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return displayed != null ? displayed : ""; //$NON-NLS-1$
        return formatCore(displayed, variable, value);
    }

    /**
     * Массовое обновление меток (раскрытие дерева): без resolveFullString / detail для коротких строк.
     */
    static String formatForStockTreeQuick(String displayed, IBslVariable variable, IBslValue value)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return displayed != null ? displayed : ""; //$NON-NLS-1$
        if (value == null || !isStringType(value))
            return displayed != null ? displayed : ""; //$NON-NLS-1$
        if (displayed == null)
            displayed = ""; //$NON-NLS-1$
        if (!looksLikeEdtTruncated(displayed) && !containsLineBreak(displayed)
            && innerQuotedLength(displayed) <= LONG_STRING_THRESHOLD)
            return displayed;
        return formatCore(displayed, variable, value);
    }

    /** Окно «Коллекция» — по явной команде пользователя, без флажка. */
    static String formatForCollectionWindow(String displayed, IBslValue value)
    {
        return formatCore(displayed, null, value);
    }

    private static String formatCore(String displayed, IBslVariable variable, IBslValue value)
    {
        if (displayed == null)
            displayed = ""; //$NON-NLS-1$
        if (value != null && isStringValue(value))
            tryForceStringDetail(variable, value,
                looksLikeEdtTruncated(displayed) || containsLineBreak(displayed));
        boolean stringType = value != null && isStringType(value);
        if (!stringType)
            return displayed;
        int fullLen = resolveFullStringCharLength(value);
        String fullText = resolveFullString(value);
        if (fullText != null && !looksLikeEdtTruncated(fullText))
            fullLen = fullText.length();
        if (fullLen < 0 && looksLikeEdtTruncated(displayed) && value != null)
        {
            tryForceStringDetail(variable, value, true);
            fullLen = resolveFullStringCharLength(value);
            fullText = resolveFullString(value);
            if (fullText != null && !looksLikeEdtTruncated(fullText))
                fullLen = fullText.length();
        }
        if (!shouldPrefix(displayed, fullLen, fullText, value))
            return displayed;
        fullLen = resolvePrefixLength(fullLen, fullText, value);
        if (fullLen < 0)
            return displayed;
        return prefixLength(displayed, fullLen);
    }

    static boolean isStringValue(IBslValue value)
    {
        return value != null && isStringType(value);
    }

    /** EDT показывает длинные строки как {@code "ВЫБРАТЬ..."} до полной оценки. */
    static boolean looksLikeEdtTruncated(String text)
    {
        if (text == null || text.isEmpty())
            return false;
        String inner = text;
        if (inner.length() >= 2 && inner.startsWith("\"") && inner.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
            inner = inner.substring(1, inner.length() - 1);
        return inner.endsWith("...") || inner.contains("..."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static boolean isTruncatedStringValue(IBslValue value, String displayed)
    {
        if (value == null || !isStringValue(value))
            return false;
        if (looksLikeEdtTruncated(displayed))
            return true;
        try
        {
            if (!value.isEvaluated() || value.isPending())
                return true;
        }
        catch (Exception ignored)
        {
            return true;
        }
        return resolveFullStringCharLength(value) < 0;
    }

    /** Первые {@link #TOOLTIP_PREVIEW_MAX} символов полной строки при EDT-усечении; иначе {@code null}. */
    static String tooltipPreviewForTruncated(IBslValue value, String displayed)
    {
        if (value == null || !isStringValue(value) || !looksLikeEdtTruncated(displayed))
            return null;
        tryForceStringDetail(null, value, true);
        String full = resolveFullString(value);
        if (full == null || full.isEmpty())
            return null;
        if (full.length() <= TOOLTIP_PREVIEW_MAX)
            return full;
        return full.substring(0, TOOLTIP_PREVIEW_MAX);
    }

    /** Повторное форматирование, пока полная длина строки ещё неизвестна. */
    static boolean needsStringFormatRetry(IBslValue value, String displayed)
    {
        if (value == null || !isStringValue(value))
            return false;
        String formatted = formatForCollectionWindow(displayed, value);
        if (!displayed.equals(formatted))
            return false;
        if (looksLikeEdtTruncated(displayed) && resolveFullStringCharLength(value) < 0)
            return true;
        try
        {
            return !value.isEvaluated() || value.isPending()
                || resolveFullStringCharLength(value) < 0;
        }
        catch (Exception ignored)
        {
            return resolveFullStringCharLength(value) < 0;
        }
    }

    private static boolean isStringType(IBslValue value)
    {
        try
        {
            BslValueType type = value.getType();
            if (type == BslValueType.STRING)
                return true;
        }
        catch (Exception ignored)
        {
            // fallback по имени типа
        }
        String typeName = value.getValueTypeName();
        if (typeName == null)
            return false;
        return "Строка".equalsIgnoreCase(typeName) //$NON-NLS-1$
            || "String".equalsIgnoreCase(typeName); //$NON-NLS-1$
    }

    private static boolean shouldPrefix(String displayed, int fullLen, String fullText, IBslValue value)
    {
        if (fullLen > LONG_STRING_THRESHOLD)
            return true;
        if (fullText != null && containsLineBreak(fullText))
            return true;
        if (containsLineBreak(displayed))
            return true;
        if (hasNewlineInValueBytes(value))
            return true;
        return hasNewlineInDetail(value);
    }

    private static int resolvePrefixLength(int fullLen, String fullText, IBslValue value)
    {
        if (fullLen >= 0)
            return fullLen;
        if (fullText != null && !looksLikeEdtTruncated(fullText))
            return fullText.length();
        fullLen = resolveFullStringCharLength(value);
        if (fullLen >= 0)
            return fullLen;
        byte[] bytes = readValueStringBytes(value);
        if (bytes != null && bytes.length > 0)
            return new String(bytes, StandardCharsets.UTF_8).length();
        return -1;
    }

    private static boolean containsLineBreak(String text)
    {
        if (text == null || text.isEmpty())
            return false;
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r')
                return true;
        }
        return false;
    }

    private static int innerQuotedLength(String displayed)
    {
        if (displayed == null || displayed.isEmpty())
            return 0;
        if (displayed.length() >= 2 && displayed.startsWith("\"") && displayed.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
            return displayed.length() - 2;
        return displayed.length();
    }

    private static boolean hasNewlineInDetail(IBslValue value)
    {
        if (value == null)
            return false;
        Object detail = Global.getField(value, "detailString"); //$NON-NLS-1$
        if (detail instanceof String text && containsLineBreak(text))
            return true;
        try
        {
            String evaluated = value.getDetailString();
            return evaluated != null && containsLineBreak(evaluated);
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    static void tryForceStringDetail(IBslVariable variable, IBslValue value, boolean forceDetail)
    {
        prepareStringValue(variable, value);
        if (!forceDetail)
            return;
        try
        {
            value.getDetailString();
        }
        catch (Exception ignored)
        {
            // detail ещё не готов
        }
    }

    private static void prepareStringValue(IBslVariable variable, IBslValue value)
    {
        try
        {
            if (!value.isEvaluated() && !value.isPending())
                value.evaluate();
        }
        catch (Exception ignored)
        {
            // evaluate ещё не готов
        }
        if (variable == null)
            return;
        try
        {
            Object evaluated = Global.invoke(variable, "isEvaluated"); //$NON-NLS-1$
            if (evaluated instanceof Boolean ok && !ok.booleanValue())
                Global.invoke(variable, "evaluate"); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            // optional API
        }
    }

    static String resolveFullString(IBslValue value)
    {
        if (value == null)
            return null;
        Object detail = Global.getField(value, "detailString"); //$NON-NLS-1$
        if (detail instanceof String text && !text.isEmpty())
        {
            if (looksLikeEdtTruncated(text))
                return null;
            return text;
        }
        if (value instanceof IBslPrimitiveValue primitive)
        {
            try
            {
                Object prim = primitive.getPrimitiveValue();
                if (prim instanceof String text && !text.isEmpty())
                    return text;
            }
            catch (Exception ignored)
            {
                // fallback по полю
            }
        }
        Object primitive = Global.getField(value, "primitiveValue"); //$NON-NLS-1$
        if (primitive instanceof String text && !text.isEmpty())
            return text;
        Object infoData = Global.getField(value, "value"); //$NON-NLS-1$
        if (infoData != null)
        {
            Object raw = Global.invoke(infoData, "getValueString"); //$NON-NLS-1$
            if (raw instanceof byte[] bytes && bytes.length > 0)
                return new String(bytes, StandardCharsets.UTF_8);
        }
        try
        {
            String presentation = value.getValueString();
            if (presentation != null && !presentation.isEmpty())
            {
                if (looksLikeEdtTruncated(presentation))
                    return null;
                return presentation;
            }
        }
        catch (Exception ignored)
        {
            // valueString ещё не готов
        }
        try
        {
            if (value.isEvaluated() && !value.isPending())
            {
                String evaluated = value.getDetailString();
                if (evaluated != null && !evaluated.isEmpty())
                    return evaluated;
            }
        }
        catch (Exception ignored)
        {
            // detail ещё не готов
        }
        return null;
    }

    /** Длина полной строки; не использует усечённый {@link IBslValue#getValueString()}. */
    static int resolveFullStringCharLength(IBslValue value)
    {
        if (value == null)
            return -1;
        Object detail = Global.getField(value, "detailString"); //$NON-NLS-1$
        if (detail instanceof String text && !text.isEmpty())
        {
            if (looksLikeEdtTruncated(text))
                return -1;
            return text.length();
        }
        if (value instanceof IBslPrimitiveValue primitive)
        {
            try
            {
                Object prim = primitive.getPrimitiveValue();
                if (prim instanceof String text && !text.isEmpty())
                    return text.length();
            }
            catch (Exception ignored)
            {
                // fallback по полю
            }
        }
        Object primitive = Global.getField(value, "primitiveValue"); //$NON-NLS-1$
        if (primitive instanceof String text && !text.isEmpty())
            return text.length();
        byte[] bytes = readValueStringBytes(value);
        if (bytes != null && bytes.length > 0)
        {
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            if (looksLikeEdtTruncated(decoded) || looksLikeEdtTruncated('"' + decoded + '"')) //$NON-NLS-1$
                return -1;
            return decoded.length();
        }
        try
        {
            if (value.isEvaluated() && !value.isPending())
            {
                String evaluated = value.getDetailString();
                if (evaluated != null && !evaluated.isEmpty())
                    return evaluated.length();
            }
        }
        catch (Exception ignored)
        {
            // detail ещё не готов
        }
        return -1;
    }

    private static byte[] readValueStringBytes(IBslValue value)
    {
        Object infoData = Global.getField(value, "value"); //$NON-NLS-1$
        if (infoData == null)
            return null;
        Object raw = Global.invoke(infoData, "getValueString"); //$NON-NLS-1$
        return raw instanceof byte[] bytes && bytes.length > 0 ? bytes : null;
    }

    private static boolean hasNewlineInValueBytes(IBslValue value)
    {
        byte[] bytes = readValueStringBytes(value);
        if (bytes == null)
            return false;
        for (byte b : bytes)
        {
            if (b == 0x0A || b == 0x0D)
                return true;
        }
        return false;
    }

    private static String prefixLength(String displayed, int length)
    {
        if (displayed.isEmpty())
            return "[" + length + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        if (displayed.startsWith("[") && displayed.length() > 1 && Character.isDigit(displayed.charAt(1))) //$NON-NLS-1$
            return displayed;
        int quote = displayed.indexOf('"');
        if (quote >= 0)
            return "[" + length + "] " + displayed.substring(quote); //$NON-NLS-1$ //$NON-NLS-2$
        return "[" + length + "] " + displayed; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
