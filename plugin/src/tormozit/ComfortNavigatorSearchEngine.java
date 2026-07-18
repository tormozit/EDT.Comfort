package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IEObjectDescription;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.bm.index.emf.IBmEmfIndexManager;
import com._1c.g5.v8.dt.bm.index.emf.IBmEmfIndexProvider;
import com._1c.g5.v8.dt.common.EObjectTrie;
import com._1c.g5.v8.dt.common.IEObjectTrie;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.search.core.IModelObjectTreeSearchEngine;

/**
 * Подмена {@code searchEngine} у штатного {@code NavigatorSearchFilter}: тот же
 * {@link IEObjectTrie}-pipeline и обход BM-индекса (парами), но совпадение через
 * {@link SmartMatcher} и полный текст объекта.
 */
public final class ComfortNavigatorSearchEngine implements IModelObjectTreeSearchEngine
{
    private static final String SCRIPT_USER_DATA = "script"; //$NON-NLS-1$
    private static final String INTNL_SCRIPT = "intnl"; //$NON-NLS-1$
    private static final String SYNONYM_USER_DATA = MdClassPackage.Literals.MD_OBJECT__SYNONYM.getName();
    private static final String COMMENT_USER_DATA = MdClassPackage.Literals.MD_OBJECT__COMMENT.getName();

    private final IModelObjectTreeSearchEngine nativeDelegate;
    private final IV8ProjectManager v8ProjectManager;
    private final IBmModelManager modelManager;

    public ComfortNavigatorSearchEngine(IModelObjectTreeSearchEngine nativeDelegate, IV8ProjectManager v8ProjectManager,
            IBmModelManager modelManager)
    {
        this.nativeDelegate = nativeDelegate;
        this.v8ProjectManager = v8ProjectManager;
        this.modelManager = modelManager;
    }

    @Override
    public IEObjectTrie runSearch(IProject project, String pattern, boolean caseSensitive, boolean lastSegmentOnly,
            boolean fullNameOnly, IProgressMonitor monitor)
    {
        List<EClass> types = new ArrayList<>();
        types.add(MdClassPackage.Literals.MD_OBJECT);
        return buildTrie(project, pattern, types, monitor);
    }

    @Override
    public IEObjectTrie runSearch(IProject project, Pattern pattern, boolean caseSensitive, boolean lastSegmentOnly,
            boolean fullNameOnly, IProgressMonitor monitor)
    {
        String raw = pattern != null ? pattern.pattern() : ""; //$NON-NLS-1$
        return runSearch(project, raw, caseSensitive, lastSegmentOnly, fullNameOnly, monitor);
    }

    @Override
    public IEObjectTrie runSearch(IProject project, String pattern, Collection<EClass> types, boolean caseSensitive,
            boolean lastSegmentOnly, boolean fullNameOnly, IProgressMonitor monitor)
    {
        return buildTrie(project, pattern, types, monitor);
    }

    @Override
    public IEObjectTrie runSearch(IProject project, Pattern pattern, Collection<EClass> types, boolean caseSensitive,
            boolean lastSegmentOnly, boolean fullNameOnly, IProgressMonitor monitor)
    {
        return runSearch(project, pattern != null ? pattern.pattern() : "", types, caseSensitive, lastSegmentOnly, //$NON-NLS-1$
                fullNameOnly, monitor);
    }

    @Override
    public IEObjectTrie runSearch(IProject project, String pattern, Collection<EClass> includeTypes,
            Collection<EClass> excludeTypes, boolean caseSensitive, boolean lastSegmentOnly, boolean fullNameOnly,
            IProgressMonitor monitor)
    {
        return buildTrie(project, pattern, includeTypes, monitor);
    }

    @Override
    public IEObjectTrie runSearch(IProject project, Pattern pattern, Collection<EClass> includeTypes,
            Collection<EClass> excludeTypes, boolean caseSensitive, boolean lastSegmentOnly, boolean fullNameOnly,
            IProgressMonitor monitor)
    {
        return runSearch(project, pattern != null ? pattern.pattern() : "", includeTypes, caseSensitive, //$NON-NLS-1$
                lastSegmentOnly, fullNameOnly, monitor);
    }

    private IEObjectTrie buildTrie(IProject project, String pattern, Collection<EClass> types, IProgressMonitor monitor)
    {
        EObjectTrie trie = new EObjectTrie();
        if (project == null || !project.isOpen() || pattern == null || pattern.isEmpty())
            return trie;

        IBmEmfIndexProvider indexProvider = resolveIndexProvider(project);
        if (indexProvider == null)
        {
            NavigatorFilterDebug.log("comfortSearch indexProvider=null project=" + project.getName() //$NON-NLS-1$
                    + " manager=" + (resolveIndexManager() != null) //$NON-NLS-1$
                    + " fallback=native"); //$NON-NLS-1$
            if (nativeDelegate != null)
                return nativeDelegate.runSearch(project, pattern, false, false, false, monitor);
            return trie;
        }

        List<EClass> scanTypes = normalizeTypes(types);
        SmartMatcher matcher = new SmartMatcher(pattern);
        int matched = 0;

        for (EClass type : scanTypes)
        {
            if (monitor != null && monitor.isCanceled())
                return trie;
            matched += scanTypeIndex(indexProvider, type, matcher, trie, monitor);
        }

        attachResolvedObjects(indexProvider, scanTypes, trie, monitor);

        if (monitor != null && monitor.isCanceled())
            return new EObjectTrie();

        if (NavigatorFilterDebug.isEnabled())
            NavigatorFilterDebug.log("comfortSearch pattern=\"" + pattern + "\" matched=" + matched //$NON-NLS-1$ //$NON-NLS-2$
                    + " project=" + project.getName()); //$NON-NLS-1$
        return trie;
    }

    private static List<EClass> normalizeTypes(Collection<EClass> types)
    {
        List<EClass> scanTypes = new ArrayList<>();
        if (types != null)
        {
            for (EClass type : types)
            {
                if (type != null && !scanTypes.contains(type))
                    scanTypes.add(type);
            }
        }
        if (scanTypes.isEmpty())
            scanTypes.add(MdClassPackage.Literals.MD_OBJECT);
        return scanTypes;
    }

    /** BM-индекс MD_OBJECT — парами записей, как в {@code ModelObjectTreeSearchEngine}. */
    private static int scanTypeIndex(IBmEmfIndexProvider indexProvider, EClass type, SmartMatcher matcher,
            EObjectTrie trie, IProgressMonitor monitor)
    {
        Iterable<IEObjectDescription> index = indexProvider.getEObjectIndexByType(type);
        if (index == null)
            return 0;

        int matched = 0;
        Iterator<IEObjectDescription> iterator = index.iterator();
        while (iterator.hasNext())
        {
            if (monitor != null && monitor.isCanceled())
                break;
            IEObjectDescription primary = iterator.next();
            if (!iterator.hasNext())
                break;
            IEObjectDescription secondary = iterator.next();
            if (tryAddMatch(matcher, primary, secondary, trie))
                matched++;
        }
        return matched;
    }

    private static boolean tryAddMatch(SmartMatcher matcher, IEObjectDescription primary,
            IEObjectDescription secondary, EObjectTrie trie)
    {
        if (primary == null || secondary == null)
            return false;

        if (MdClassPackage.Literals.CONFIGURATION.equals(primary.getEClass()))
            return tryAddConfigurationMatch(matcher, primary, secondary, trie);

        QualifiedName path = primary.getQualifiedName();
        if (path == null)
            return false;

        if (matcher.hasMultipleSections())
        {
            String pathRu = MdTypeMapping.translateDottedToRu(path.toString(".")); //$NON-NLS-1$
            if (!matcher.matchesTree(pathRu))
                return false;
        }
        else
        {
            String searchText = collectPairSearchText(primary, secondary);
            if (!matcher.matches(searchText))
                return false;
        }

        trie.addPath(path);
        EObject eObject = primary.getEObjectOrProxy();
        if (eObject != null)
            trie.setEObject(path, eObject);
        return true;
    }

    private static boolean tryAddConfigurationMatch(SmartMatcher matcher, IEObjectDescription primary,
            IEObjectDescription secondary, EObjectTrie trie)
    {
        QualifiedName path = primary.getQualifiedName();
        if (path == null)
            return false;

        if (matcher.hasMultipleSections())
        {
            if (!matcher.matchesTree(MdTypeMapping.translateDottedToRu(path.toString(".")))) //$NON-NLS-1$
                return false;
        }
        else
        {
            String searchText = joinTokens(
                    segmentOrEmpty(primary.getName()),
                    segmentOrEmpty(secondary.getName()),
                    collectIndexUserText(primary),
                    collectIndexUserText(secondary));
            if (!matcher.matches(searchText))
                return false;
        }
        trie.addPath(path);
        EObject eObject = primary.getEObjectOrProxy();
        if (eObject != null)
            trie.setEObject(path, eObject);
        return true;
    }

    private static String collectPairSearchText(IEObjectDescription primary, IEObjectDescription secondary)
    {
        EObject eObject = primary.getEObjectOrProxy();
        if (eObject instanceof MdObject mdObject)
        {
            String joined = NavigatorFuzzySearch.joinSearchTexts(mdObject);
            if (!joined.isEmpty())
                return joined;
        }

        return joinTokens(
                segmentOrEmpty(primary.getName()),
                segmentOrEmpty(secondary.getName()),
                qualifiedNameText(primary.getQualifiedName()),
                qualifiedNameText(secondary.getQualifiedName()),
                primary.getUserData(SYNONYM_USER_DATA),
                primary.getUserData(COMMENT_USER_DATA),
                secondary.getUserData(SYNONYM_USER_DATA),
                secondary.getUserData(COMMENT_USER_DATA));
    }

    private static String collectIndexUserText(IEObjectDescription description)
    {
        if (description == null)
            return ""; //$NON-NLS-1$
        return joinTokens(
                description.getUserData(SYNONYM_USER_DATA),
                description.getUserData(COMMENT_USER_DATA));
    }

    private static String segmentOrEmpty(QualifiedName name)
    {
        return name != null ? name.getLastSegment() : ""; //$NON-NLS-1$
    }

    private static String qualifiedNameText(QualifiedName name)
    {
        return name != null ? name.toString(" ") : ""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String joinTokens(String... parts)
    {
        StringBuilder sb = new StringBuilder();
        for (String part : parts)
        {
            if (part == null || part.isEmpty())
                continue;
            if (sb.length() > 0)
                sb.append(' ');
            sb.append(part);
        }
        return sb.toString().trim();
    }

    /** Вторая фаза штатного движка: {@code setEObject} для intnl-веток уже отобранного trie. */
    private static void attachResolvedObjects(IBmEmfIndexProvider indexProvider, List<EClass> types, EObjectTrie trie,
            IProgressMonitor monitor)
    {
        for (EClass type : types)
        {
            Iterable<IEObjectDescription> index = indexProvider.getEObjectIndexByType(type);
            if (index == null)
                continue;
            for (IEObjectDescription description : index)
            {
                if (monitor != null && monitor.isCanceled())
                    return;
                if (!INTNL_SCRIPT.equals(description.getUserData(SCRIPT_USER_DATA)))
                    continue;
                QualifiedName qName = description.getQualifiedName();
                if (qName == null || !trie.belongsTo(qName))
                    continue;
                EObject eObject = description.getEObjectOrProxy();
                if (eObject != null)
                    trie.setEObject(qName, eObject);
            }
        }
    }

    private IBmEmfIndexProvider resolveIndexProvider(IProject project)
    {
        IBmEmfIndexManager manager = resolveIndexManager();
        if (manager == null || project == null)
            return null;

        IBmEmfIndexProvider provider = manager.getEmfIndexProvider(project);
        if (provider != null)
            return provider;

        if (modelManager != null)
        {
            IBmModel model = modelManager.getModel(project);
            if (model != null)
            {
                provider = manager.getEmfIndexProvider(model);
                if (provider != null)
                    return provider;
            }
        }

        if (v8ProjectManager != null)
        {
            IV8Project v8Project = v8ProjectManager.getProject(project);
            if (v8Project != null)
            {
                IProject wsProject = v8Project.getProject();
                if (wsProject != null && !wsProject.equals(project))
                {
                    provider = manager.getEmfIndexProvider(wsProject);
                    if (provider != null)
                        return provider;
                }
            }
        }
        return null;
    }

    private IBmEmfIndexManager resolveIndexManager()
    {
        if (nativeDelegate != null)
        {
            Object fromEngine = Global.getField(nativeDelegate, "bmEmfIndexManager"); //$NON-NLS-1$
            if (fromEngine instanceof IBmEmfIndexManager manager)
                return manager;
        }
        return Global.getOsgiService(IBmEmfIndexManager.class);
    }
}
