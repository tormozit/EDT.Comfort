package tormozit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import com._1c.g5.v8.dt.common.FuzzyPattern;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Доступ к штатному {@code FuzzySearchHelper} EDT (синонимы, комментарии, подсказки по EMF-путям).
 */
public final class NavigatorFuzzySearch
{
    private static final String HELPER =
            "com._1c.g5.v8.dt.internal.navigator.ui.filters.FuzzySearchHelper"; //$NON-NLS-1$

    private static volatile Method getSearchPaths;

    private static final String[] TOOL_TIP_METHODS = {
            "getToolTip", "getTooltip" //$NON-NLS-1$ //$NON-NLS-2$
    };

    private NavigatorFuzzySearch() {}

    public static List<String> collectSearchTexts(MdObject mdObject)
    {
        if (mdObject == null)
            return Collections.emptyList();

        List<String> texts = new ArrayList<>();
        addToken(texts, mdObject.getName());
        texts.addAll(hiddenAttributeTexts(mdObject));
        return texts;
    }

    /** Скрытые поля объекта (без имени), без атрибутов предков в EMF-цепочке. */
    public static List<String> hiddenAttributeTexts(MdObject mdObject)
    {
        return new ArrayList<>(collectOwnHiddenTexts(mdObject, true));
    }

    /** Только поля самого объекта — для серого квалификатора справа от имени. */
    private static Set<String> collectOwnHiddenTexts(MdObject mdObject, boolean includeOwnPaths)
    {
        if (mdObject == null)
            return Collections.emptySet();

        Set<String> texts = new LinkedHashSet<>();
        addToken(texts, mdObject.getComment());
        appendTextValue(texts, mdObject.getSynonym());
        addToken(texts, localizedSynonym(mdObject));
        if (includeOwnPaths)
            collectOwnPathTexts(texts, mdObject);
        appendToolTipFeatures(texts, mdObject);
        appendExtensionTexts(texts, mdObject);
        if (mdObject instanceof EObject)
            collectEmfFeatureTexts(texts, (EObject) mdObject);
        appendFeature(texts, mdObject, "getExplanation"); //$NON-NLS-1$
        return texts;
    }

    /** Пути FuzzySearchHelper только для EMF-типа текущего объекта, не предка. */
    private static void collectOwnPathTexts(Set<String> texts, MdObject mdObject)
    {
        if (!(mdObject instanceof EObject))
            return;

        EObject object = (EObject) mdObject;
        for (Object path : getSearchPaths(mdObject))
        {
            if (isOwnSearchPath(object, path))
                appendPathTexts(texts, path);
        }
    }

    private static boolean isOwnSearchPath(EObject object, Object path)
    {
        if (object == null || path == null)
            return false;

        Object feature = Global.getField(path, "feature"); //$NON-NLS-1$
        if (!(feature instanceof EStructuralFeature))
            return false;

        EStructuralFeature structuralFeature = (EStructuralFeature) feature;
        EClass featureClass = structuralFeature.getEContainingClass();
        EClass ownClass = object.eClass();
        if (featureClass == null || ownClass == null)
            return false;

        if (featureClass == ownClass)
            return true;
        for (EClass superType : ownClass.getEAllSuperTypes())
        {
            if (featureClass == superType)
                return true;
        }
        return false;
    }

    private static void appendPathTexts(Set<String> texts, Object path)
    {
        if (path == null)
            return;

        Object values = Global.getField(path, "values"); //$NON-NLS-1$
        if (values instanceof Iterable<?>)
        {
            for (Object value : (Iterable<?>) values)
                appendTextValue(texts, value);
        }
        addToken(texts, (String) Global.getField(path, "synonim")); //$NON-NLS-1$
        addToken(texts, (String) Global.invoke(path, "getSynonym")); //$NON-NLS-1$
        addToken(texts, (String) Global.getField(path, "name")); //$NON-NLS-1$
        addToken(texts, (String) Global.getField(path, "prefix")); //$NON-NLS-1$

        Object prefixes = Global.getField(path, "prefixes"); //$NON-NLS-1$
        if (prefixes instanceof Iterable<?>)
        {
            for (Object prefix : (Iterable<?>) prefixes)
                appendTextValue(texts, prefix);
        }
    }

    /** Подсказка на объекте и в extension (у реквизитов — {@code getExtension().getTooltip()}). */
    private static void appendToolTipFeatures(Set<String> texts, Object target)
    {
        if (target == null)
            return;
        for (String method : TOOL_TIP_METHODS)
            appendFeature(texts, target, method);
    }

    private static void appendExtensionTexts(Set<String> texts, MdObject mdObject)
    {
        Object extension = Global.invoke(mdObject, "getExtension"); //$NON-NLS-1$
        if (extension == null)
            return;
        appendToolTipFeatures(texts, extension);
        appendFeature(texts, extension, "getExplanation"); //$NON-NLS-1$
        appendFeature(texts, extension, "getHelp"); //$NON-NLS-1$
        appendFeature(texts, extension, "getComment"); //$NON-NLS-1$
        if (extension instanceof EObject)
            collectEmfFeatureTexts(texts, (EObject) extension);
    }

    private static void collectEmfFeatureTexts(Set<String> texts, EObject object)
    {
        if (object == null || object.eClass() == null)
            return;

        for (EStructuralFeature feature : object.eClass().getEAllStructuralFeatures())
        {
            String featureName = feature.getName();
            if (!isHiddenSearchFeature(featureName))
                continue;
            appendTextValue(texts, object.eGet(feature, false));
        }
    }

    private static boolean isHiddenSearchFeature(String featureName)
    {
        if (featureName == null || featureName.isEmpty())
            return false;
        if ("name".equals(featureName)) //$NON-NLS-1$
            return false;

        String lower = featureName.toLowerCase();
        return lower.contains("synonym") //$NON-NLS-1$
                || lower.contains("synonim") //$NON-NLS-1$
                || lower.contains("comment") //$NON-NLS-1$
                || lower.contains("tooltip") //$NON-NLS-1$
                || lower.contains("explanation") //$NON-NLS-1$
                || lower.contains("hint") //$NON-NLS-1$
                || lower.contains("help"); //$NON-NLS-1$
    }

    public static String joinSearchTexts(MdObject mdObject)
    {
        List<String> texts = collectSearchTexts(mdObject);
        StringBuilder sb = new StringBuilder();
        for (String text : texts)
            appendToken(sb, text);
        return sb.toString().trim();
    }

    /** Лучший фрагмент для серого квалификатора справа от имени (не само имя). */
    public static QualifierMatch findQualifierMatch(MdObject mdObject, String pattern, String objectName)
    {
        if (mdObject == null || pattern == null || pattern.trim().isEmpty())
            return null;

        String safeName = objectName != null ? objectName.trim() : ""; //$NON-NLS-1$
        QualifierMatch best = null;

        for (String hidden : collectOwnHiddenTexts(mdObject, false))
        {
            QualifierMatch candidate = tryQualifier(hidden, pattern, safeName);
            if (candidate != null && (best == null || candidate.score > best.score))
                best = candidate;
        }
        return best;
    }

    private static QualifierMatch tryQualifier(String source, String pattern, String objectName)
    {
        if (source == null)
            return null;
        String text = source.trim();
        if (text.isEmpty() || text.equalsIgnoreCase(objectName))
            return null;

        SmartMatcher matcher = new SmartMatcher(pattern);
        if (matcher.isEmpty)
            return null;

        if (!matcher.matches(text))
            return null;

        List<String> hiddenFragments = matcher.fragmentsInNotIn(text, objectName);
        if (!hiddenFragments.isEmpty())
            return buildQualifierMulti(text, hiddenFragments);

        if (matcher.getFragments().length == 1)
        {
            FuzzyPattern.Match match = fuzzyMatch(pattern, text);
            if (match != null)
                return buildQualifier(text, match);
        }
        return null;
    }

    private static QualifierMatch buildQualifier(String source, FuzzyPattern.Match match)
    {
        List<int[]> ranges = readMatchRanges(match, source.length());
        if (ranges.isEmpty())
            return null;

        int[] primary = ranges.get(0);
        int start = primary[0];
        int end = primary[0] + primary[1];

        int fragStart = Math.max(0, start - 12);
        int fragEnd = Math.min(source.length(), end + 16);
        while (fragStart > 0 && !Character.isWhitespace(source.charAt(fragStart - 1)))
            fragStart--;
        while (fragEnd < source.length() && !Character.isWhitespace(source.charAt(fragEnd)))
            fragEnd++;

        String fragment = source.substring(fragStart, fragEnd).trim();
        if (fragment.isEmpty())
            return null;

        StringBuilder display = new StringBuilder("... "); //$NON-NLS-1$
        int offsetShift = display.length();
        display.append(fragment);
        boolean trailing = fragEnd < source.length();
        if (trailing)
            display.append(" ..."); //$NON-NLS-1$

        String displayStr = display.toString();
        List<int[]> displayRanges = new ArrayList<>();
        for (int[] range : ranges)
        {
            int relStart = range[0] - fragStart + offsetShift;
            int relLen = range[1];
            if (relStart >= 0 && relStart + relLen <= displayStr.length())
                displayRanges.add(new int[] { relStart, relLen });
        }
        if (displayRanges.isEmpty())
            return null;

        return new QualifierMatch(displayStr, displayRanges, primary[1]);
    }

    /** Окно вокруг вхождений фрагментов, подсветка каждого фрагмента в сером квалификаторе. */
    private static QualifierMatch buildQualifierMulti(String source, List<String> fragments)
    {
        if (source == null || fragments == null || fragments.isEmpty())
            return null;

        int anchorStart = source.length();
        int anchorEnd = 0;
        for (String frag : fragments)
        {
            int[] span = findFragmentSpan(source, frag);
            if (span == null)
                continue;
            anchorStart = Math.min(anchorStart, span[0]);
            anchorEnd = Math.max(anchorEnd, span[0] + span[1]);
        }
        if (anchorStart >= source.length())
            return null;

        int fragStart = Math.max(0, anchorStart - 12);
        int fragEnd = Math.min(source.length(), anchorEnd + 16);
        while (fragStart > 0 && !Character.isWhitespace(source.charAt(fragStart - 1)))
            fragStart--;
        while (fragEnd < source.length() && !Character.isWhitespace(source.charAt(fragEnd)))
            fragEnd++;

        String fragment = source.substring(fragStart, fragEnd).trim();
        if (fragment.isEmpty())
            return null;

        StringBuilder display = new StringBuilder("... "); //$NON-NLS-1$
        int offsetShift = display.length();
        display.append(fragment);
        if (fragEnd < source.length())
            display.append(" ..."); //$NON-NLS-1$

        String displayStr = display.toString();
        SmartMatcher highlight = new SmartMatcher(joinFragments(fragments));
        List<int[]> displayRanges = new ArrayList<>();
        for (SmartMatcher.HighlightRange range : highlight.getHighlightRanges(displayStr))
            displayRanges.add(new int[] { range.offset, range.length });
        if (displayRanges.isEmpty())
            return null;

        int score = 0;
        for (int[] range : displayRanges)
            score += range[1];
        return new QualifierMatch(displayStr, displayRanges, score);
    }

    private static int[] findFragmentSpan(String source, String fragment)
    {
        if (source == null || fragment == null || fragment.isEmpty())
            return null;
        int idx = source.toLowerCase().indexOf(fragment.toLowerCase());
        if (idx >= 0)
            return new int[] { idx, fragment.length() };

        FuzzyPattern.Match match = fuzzyMatch(fragment, source);
        if (match == null)
            return null;
        List<int[]> ranges = readMatchRanges(match, source.length());
        if (ranges.isEmpty())
            return null;
        return ranges.get(0);
    }

    private static String joinFragments(List<String> fragments)
    {
        StringBuilder sb = new StringBuilder();
        for (String frag : fragments)
            appendToken(sb, frag);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> getSearchPaths(MdObject mdObject)
    {
        if (mdObject == null)
            return Collections.emptyList();
        try
        {
            Method method = resolveGetSearchPaths();
            if (method == null)
                return Collections.emptyList();
            Object result = method.invoke(null, mdObject, Boolean.valueOf(isRu()));
            if (result instanceof List<?>)
                return (List<Object>) result;
        }
        catch (Exception ignored) {}
        return Collections.emptyList();
    }

    private static FuzzyPattern.Match fuzzyMatch(String pattern, String sample)
    {
        if (pattern == null || sample == null)
            return null;
        try
        {
            return new FuzzyPattern(pattern).match(sample, isRu());
        }
        catch (Exception ignored) {}
        return null;
    }

    private static List<int[]> readMatchRanges(FuzzyPattern.Match match, int textLength)
    {
        if (match == null || match.getRanges() == null)
            return Collections.emptyList();

        List<int[]> result = new ArrayList<>();
        for (FuzzyPattern.Match.Range range : match.getRanges())
        {
            int off = rangeOffset(range);
            int len = rangeLength(range);
            if (off >= 0 && len > 0 && off + len <= textLength)
                result.add(new int[] { off, len });
        }
        return result;
    }

    private static int rangeOffset(FuzzyPattern.Match.Range range)
    {
        Object off = Global.invoke(range, "getStart"); //$NON-NLS-1$
        if (!(off instanceof Integer))
            off = Global.invoke(range, "getOffset"); //$NON-NLS-1$
        return off instanceof Integer ? (Integer) off : -1;
    }

    private static int rangeLength(FuzzyPattern.Match.Range range)
    {
        Object len = Global.invoke(range, "getLength"); //$NON-NLS-1$
        return len instanceof Integer ? (Integer) len : 0;
    }

    private static Method resolveGetSearchPaths()
    {
        if (getSearchPaths == null)
        {
            try
            {
                Class<?> helper = loadEdtClass(HELPER);
                if (helper != null)
                    getSearchPaths = helper.getMethod("getSearchPaths", MdObject.class, boolean.class); //$NON-NLS-1$
            }
            catch (ClassNotFoundException | NoSuchMethodException ignored) {}
        }
        return getSearchPaths;
    }

    private static Class<?> loadEdtClass(String name) throws ClassNotFoundException
    {
        Activator activator = Activator.getDefault();
        if (activator != null)
        {
            BundleContext context = activator.getBundle().getBundleContext();
            if (context != null)
            {
                for (Bundle bundle : context.getBundles())
                {
                    try
                    {
                        return bundle.loadClass(name);
                    }
                    catch (ClassNotFoundException ignored) {}
                }
            }
        }
        return Class.forName(name);
    }

    private static String localizedSynonym(MdObject mdObject)
    {
        try
        {
            Class<?> mdUtil = Class.forName("com._1c.g5.v8.dt.md.MdUtil"); //$NON-NLS-1$
            Object value = Global.invoke(mdUtil, "getSynonym", mdObject); //$NON-NLS-1$
            return value instanceof String ? (String) value : null;
        }
        catch (ClassNotFoundException ignored)
        {
            return null;
        }
    }

    private static boolean isRu()
    {
        String lang = System.getProperty("user.language", ""); //$NON-NLS-1$ //$NON-NLS-2$
        return lang == null || lang.isEmpty() || lang.toLowerCase().startsWith("ru"); //$NON-NLS-1$
    }

    private static void appendFeature(List<String> texts, Object target, String method)
    {
        Object value = Global.invoke(target, method);
        appendTextValue(texts, value);
    }

    private static void appendTextValue(List<String> texts, Object value)
    {
        appendTextValueImpl(value, v -> addToken(texts, v));
    }

    private static void addToken(List<String> texts, String token)
    {
        if (token == null)
            return;
        String trimmed = token.trim();
        if (!trimmed.isEmpty())
            texts.add(trimmed);
    }

    private static void addToken(Set<String> texts, String token)
    {
        if (token == null)
            return;
        String trimmed = token.trim();
        if (!trimmed.isEmpty())
            texts.add(trimmed);
    }

    private static void appendTextValue(Set<String> texts, Object value)
    {
        appendTextValueImpl(value, v -> addToken(texts, v));
    }

    private interface TextConsumer
    {
        void accept(String text);
    }

    private static void appendTextValueImpl(Object value, TextConsumer consumer)
    {
        if (value == null)
            return;
        if (value instanceof String)
        {
            String trimmed = ((String) value).trim();
            if (!trimmed.isEmpty())
                consumer.accept(trimmed);
            return;
        }
        if (value instanceof Map<?, ?>)
        {
            for (Object entryValue : ((Map<?, ?>) value).values())
                appendTextValueImpl(entryValue, consumer);
            return;
        }
        if (value instanceof Iterable<?> && !(value instanceof String))
        {
            for (Object item : (Iterable<?>) value)
                appendTextValueImpl(item, consumer);
            return;
        }

        Object extracted = Global.invoke(value, "getValue"); //$NON-NLS-1$
        if (extracted != null && extracted != value)
            appendTextValueImpl(extracted, consumer);
    }

    private static void appendFeature(Set<String> texts, Object target, String method)
    {
        appendTextValue(texts, Global.invoke(target, method));
    }

    private static void appendToken(StringBuilder sb, String token)
    {
        if (token == null)
            return;
        String trimmed = token.trim();
        if (trimmed.isEmpty())
            return;
        if (sb.length() > 0)
            sb.append(' ');
        sb.append(trimmed);
    }

    public static final class QualifierMatch
    {
        public final String text;
        public final List<int[]> ranges;
        public final int score;

        QualifierMatch(String text, List<int[]> ranges, int score)
        {
            this.text = text;
            this.ranges = ranges;
            this.score = score;
        }
    }
}
