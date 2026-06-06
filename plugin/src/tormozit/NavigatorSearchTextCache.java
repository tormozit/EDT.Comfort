package tormozit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jface.viewers.IBaseLabelProvider;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/** Кэш текста поиска и квалификатора навигатора — не пересчитывать EMF/рефлексию на каждую ячейку. */
public final class NavigatorSearchTextCache
{
    private final Map<Object, String> searchTextByElement = new HashMap<>();
    private final Map<Integer, NavigatorFuzzySearch.QualifierMatch> qualifierByMdObject = new HashMap<>();
    private final Set<Integer> qualifierMiss = new HashSet<>();
    private String qualifierPattern = ""; //$NON-NLS-1$

    public String searchText(Object element, IBaseLabelProvider labelBase)
    {
        if (element == null)
            return ""; //$NON-NLS-1$
        return searchTextByElement.computeIfAbsent(element,
                e -> NavigatorTreeElementLabels.resolveSearchText(e, labelBase));
    }

    public NavigatorFuzzySearch.QualifierMatch qualifier(MdObject mdObject, String pattern, String objectName)
    {
        if (mdObject == null || pattern == null || pattern.trim().isEmpty())
            return null;

        String safePattern = pattern.trim();
        if (!Objects.equals(qualifierPattern, safePattern))
        {
            qualifierPattern = safePattern;
            qualifierByMdObject.clear();
            qualifierMiss.clear();
        }

        int key = System.identityHashCode(mdObject);
        if (qualifierMiss.contains(key))
            return null;

        NavigatorFuzzySearch.QualifierMatch cached = qualifierByMdObject.get(key);
        if (cached != null)
            return cached;

        NavigatorFuzzySearch.QualifierMatch computed =
                NavigatorFuzzySearch.findQualifierMatch(mdObject, safePattern, objectName);
        if (computed == null)
            qualifierMiss.add(key);
        else
            qualifierByMdObject.put(key, computed);
        return computed;
    }

    public void onPatternChanged(String pattern)
    {
        String safePattern = pattern != null ? pattern.trim() : ""; //$NON-NLS-1$
        if (!Objects.equals(qualifierPattern, safePattern))
        {
            qualifierPattern = safePattern;
            qualifierByMdObject.clear();
            qualifierMiss.clear();
        }
    }
}
