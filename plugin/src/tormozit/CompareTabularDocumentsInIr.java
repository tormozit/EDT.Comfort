package tormozit;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.eclipse.compare.IResourceProvider;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IPath;

import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Сравнение макетов табличного документа (файлы {@code .mxlx}) в приложении ИР —
 * COM {@code СравнитьТабличныеДокументыИмпортЛкс}. Общая точка для дерева сравнения
 * конфигураций и окон сравнения текстов.
 */
public final class CompareTabularDocumentsInIr
{
    /** Подпись в окнах сравнения текстов / 3-way диалоге макета. */
    public static final String MENU_LABEL = "Сравнить таблично ИР"; //$NON-NLS-1$

    public static final String TOOLTIP =
        "Открыть сравнение табличных документов (.mxlx) в приложении ИР"; //$NON-NLS-1$

    private CompareTabularDocumentsInIr()
    {
    }

    public static boolean isMxlxFileName(String name)
    {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".mxlx"); //$NON-NLS-1$
    }

    /**
     * Расширение берём из {@link ITypedElement#getName()}, {@link ITypedElement#getType()},
     * поля {@code path} ({@code TextDocumentTemplateTypedElement}) или workspace-{@link IFile}
     * через {@link IResourceProvider}.
     */
    public static boolean isMxlxTypedElement(ITypedElement element)
    {
        if (element == null)
            return false;
        if (isMxlxFileName(element.getName()) || isMxlxFileName(dotExtension(element.getType())))
            return true;
        Object pathObj = Global.getField(element, "path"); //$NON-NLS-1$
        if (pathObj instanceof Path path)
        {
            Path fileName = path.getFileName();
            if (fileName != null && isMxlxFileName(fileName.toString()))
                return true;
        }
        IFile file = resolveWorkspaceFile(element);
        return file != null && isMxlxFileName(file.getName());
    }

    private static String dotExtension(String typeOrExt)
    {
        if (typeOrExt == null || typeOrExt.isBlank())
            return null;
        return typeOrExt.startsWith(".") ? typeOrExt : "." + typeOrExt; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Workspace-{@link IFile} стороны сравнения:
     * <ul>
     *   <li>{@link IResourceProvider} (рабочая копия / индекс EGit);</li>
     *   <li>{@link IAdaptable#getAdapter(Class)} → {@link IFile};</li>
     *   <li>EGit {@code FileRevisionTypedElement}: {@code getFileRevision}/{@code getRevision}
     *       → {@code getGitPath} + work tree (список файлов коммита — без
     *       {@code IResourceProvider}; {@code getPath()} там = {@code URI.getPath}, ненадёжен
     *       при рассинхроне git-path и project-relative);</li>
     *   <li>{@code getPath()} как {@link String} / {@link Path};</li>
     *   <li>поле {@code path} ({@link Path}) через {@code findFilesForLocationURI}.</li>
     * </ul>
     */
    public static IFile resolveWorkspaceFile(ITypedElement element)
    {
        if (element == null)
            return null;
        if (element instanceof IResourceProvider resourceProvider)
        {
            IResource resource = resourceProvider.getResource();
            if (resource instanceof IFile file)
                return file;
        }
        if (element instanceof IAdaptable adaptable)
        {
            IFile adapted = adaptable.getAdapter(IFile.class);
            if (adapted != null)
                return adapted;
        }
        IFile fromGitRevision = resolveWorkspaceFileFromGitRevision(element);
        if (fromGitRevision != null)
            return fromGitRevision;
        Object getPath = Global.call(element, "getPath"); //$NON-NLS-1$
        if (getPath instanceof String repoRelative && !repoRelative.isBlank())
        {
            IFile byRepoPath = findWorkspaceFileByRepoRelativePath(repoRelative);
            if (byRepoPath != null)
                return byRepoPath;
        }
        if (getPath instanceof Path pathFromGetter)
        {
            IFile byAbs = findWorkspaceFileByAbsolutePath(pathFromGetter);
            if (byAbs != null)
                return byAbs;
        }
        if (getPath instanceof IPath eclipsePath)
        {
            IFile byEclipsePath = findWorkspaceFileByEclipsePath(eclipsePath);
            if (byEclipsePath != null)
                return byEclipsePath;
        }
        Object pathObj = Global.getField(element, "path"); //$NON-NLS-1$
        if (pathObj instanceof Path path)
        {
            IFile byAbs = findWorkspaceFileByAbsolutePath(path);
            if (byAbs != null)
                return byAbs;
        }
        return null;
    }

    /**
     * EGit {@code FileRevisionTypedElement}: ревизия с {@code getGitPath}/{@code getRepository}.
     * Абсолютный путь workTree+gitPath надёжнее, чем project.getFile(gitPath) при вложенных
     * проектах (корень git ≠ корень Eclipse-проекта).
     */
    private static IFile resolveWorkspaceFileFromGitRevision(ITypedElement element)
    {
        Object revision = Global.call(element, "getFileRevision"); //$NON-NLS-1$
        if (revision == null)
            revision = Global.call(element, "getRevision"); //$NON-NLS-1$
        if (revision == null)
            return null;
        String gitPath = null;
        Object gitPathObj = Global.call(revision, "getGitPath"); //$NON-NLS-1$
        if (gitPathObj instanceof String s && !s.isBlank())
            gitPath = s;
        Object repository = Global.call(revision, "getRepository"); //$NON-NLS-1$
        Object workTreeObj = repository != null ? Global.call(repository, "getWorkTree") : null; //$NON-NLS-1$
        if (workTreeObj instanceof File workTree && gitPath != null)
        {
            Path absolute = workTree.toPath().resolve(gitPath.replace('/', File.separatorChar));
            IFile byAbs = findWorkspaceFileByAbsolutePath(absolute);
            if (byAbs != null)
                return byAbs;
            IFile byPrefix = findWorkspaceFileUnderProjectLocation(absolute);
            if (byPrefix != null)
                return byPrefix;
        }
        if (gitPath != null)
        {
            IFile byRepoPath = findWorkspaceFileByRepoRelativePath(gitPath);
            if (byRepoPath != null)
                return byRepoPath;
        }
        Object uriObj = Global.call(revision, "getURI"); //$NON-NLS-1$
        if (uriObj instanceof URI uri && uri.getPath() != null && !uri.getPath().isBlank())
        {
            if (uri.isAbsolute() && "file".equalsIgnoreCase(uri.getScheme())) //$NON-NLS-1$
            {
                IFile byUri = findWorkspaceFileByAbsolutePath(Path.of(uri));
                if (byUri != null)
                    return byUri;
            }
        }
        return null;
    }

    /**
     * Путь относительно корня git-репозитория → {@link IFile} в доступном проекте workspace
     * (типичный случай EDT: корень репозитория = корень проекта или путь внутри него).
     * Если файла нет в WT (rename/delete), возвращаем handle только когда ровно один проект
     * даёт наилучший существующий префикс пути — иначе {@code null} (проект ИР возьмётся
     * с другой стороны сравнения).
     */
    public static IFile findWorkspaceFileByRepoRelativePath(String repoRelativePath)
    {
        if (repoRelativePath == null || repoRelativePath.isBlank())
            return null;
        String normalized = repoRelativePath.replace('\\', '/');
        while (normalized.startsWith("/")) //$NON-NLS-1$
            normalized = normalized.substring(1);
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IResource atRoot = root.findMember(normalized);
        if (atRoot instanceof IFile file)
            return file;
        IFile bestMissing = null;
        int bestScore = 0;
        boolean bestTied = false;
        for (IProject project : root.getProjects())
        {
            if (project == null || !project.isAccessible())
                continue;
            IFile file = project.getFile(normalized);
            if (file.exists())
                return file;
            int score = existingPathPrefixScore(project, normalized);
            if (score <= 0)
                continue;
            if (score > bestScore)
            {
                bestScore = score;
                bestMissing = file;
                bestTied = false;
            }
            else if (score == bestScore)
            {
                bestTied = true;
            }
        }
        if (bestMissing != null && !bestTied)
            return bestMissing;
        // git path = <имяПапкиПроекта>/... при проекте, лежащем внутри work tree
        int slash = normalized.indexOf('/');
        if (slash > 0)
        {
            String first = normalized.substring(0, slash);
            String rest = normalized.substring(slash + 1);
            IProject named = root.getProject(first);
            if (named != null && named.isAccessible())
            {
                IFile file = named.getFile(rest);
                if (file.exists())
                    return file;
            }
        }
        return null;
    }

    /** Сколько сегментов пути (без имени файла) существуют под корнем проекта. */
    private static int existingPathPrefixScore(IProject project, String normalizedPath)
    {
        if (project == null || normalizedPath == null || normalizedPath.isBlank())
            return 0;
        String[] parts = normalizedPath.split("/"); //$NON-NLS-1$
        if (parts.length == 0)
            return 0;
        IContainer current = project;
        int score = 0;
        int folderSegments = Math.max(0, parts.length - 1);
        for (int i = 0; i < folderSegments; i++)
        {
            IResource child = current.findMember(parts[i]);
            if (!(child instanceof IContainer container) || !container.exists())
                break;
            score++;
            current = container;
        }
        return score;
    }

    /**
     * Абсолютный путь на диске → {@link IFile}: сначала {@code findFilesForLocationURI},
     * иначе handle по префиксу {@link IProject#getLocation()} (файл может отсутствовать в WT).
     */
    public static IFile findWorkspaceFileByAbsolutePath(Path path)
    {
        if (path == null)
            return null;
        try
        {
            IFile[] files = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(path.toUri());
            if (files != null && files.length > 0)
                return files[0];
        }
        catch (Exception e)
        {
            Global.log("CompareTabularDocumentsInIr.findWorkspaceFileByAbsolutePath: " + e.getMessage()); //$NON-NLS-1$
        }
        return findWorkspaceFileUnderProjectLocation(path);
    }

    private static IFile findWorkspaceFileByEclipsePath(IPath eclipsePath)
    {
        if (eclipsePath == null)
            return null;
        if (eclipsePath.isAbsolute())
        {
            java.io.File asFile = eclipsePath.toFile();
            if (asFile != null)
                return findWorkspaceFileByAbsolutePath(asFile.toPath());
        }
        return findWorkspaceFileByRepoRelativePath(eclipsePath.toString());
    }

    /** {@code absPath} лежит под location проекта → project-relative {@link IFile} (даже без exists). */
    private static IFile findWorkspaceFileUnderProjectLocation(Path absPath)
    {
        if (absPath == null)
            return null;
        Path normalizedAbs = absPath.toAbsolutePath().normalize();
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        for (IProject project : root.getProjects())
        {
            if (project == null || !project.isAccessible())
                continue;
            IPath projectLoc = project.getLocation();
            if (projectLoc == null)
                continue;
            Path projectPath = projectLoc.toFile().toPath().toAbsolutePath().normalize();
            if (!normalizedAbs.startsWith(projectPath))
                continue;
            Path relative = projectPath.relativize(normalizedAbs);
            if (relative.getNameCount() == 0)
                continue;
            return project.getFile(relative.toString().replace('\\', '/'));
        }
        return null;
    }

    /**
     * Существующий файл на диске из typed element, иначе содержимое во временный {@code .mxlx}.
     */
    public static Path resolveSideFile(ITypedElement element, String sideTag)
    {
        if (element == null)
            return null;
        Object pathObj = Global.getField(element, "path"); //$NON-NLS-1$
        if (pathObj instanceof Path path && Files.isRegularFile(path))
            return path;
        IFile workspaceFile = resolveWorkspaceFile(element);
        if (workspaceFile != null && workspaceFile.getLocation() != null)
        {
            Path location = workspaceFile.getLocation().toFile().toPath();
            if (Files.isRegularFile(location))
                return location;
        }
        if (!(element instanceof IStreamContentAccessor accessor))
            return null;
        String fileName = element.getName();
        if (!isMxlxFileName(fileName))
            fileName = "content.mxlx"; //$NON-NLS-1$
        String prefix = "tormozit_" + sideTag + "_"; //$NON-NLS-1$ //$NON-NLS-2$
        String suffix = "_" + fileName; //$NON-NLS-1$
        try (InputStream stream = accessor.getContents())
        {
            if (stream == null)
                return null;
            Path tempFile = Files.createTempFile(prefix, suffix);
            Files.copy(stream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        }
        catch (CoreException | java.io.IOException e)
        {
            Global.log("CompareTabularDocumentsInIr: не удалось получить файл стороны: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Двухстороннее сравнение: в ИР первым идёт «Текущий», вторым — более новый «Новый».
     * Если в UI более новый слева (порядок отличается от ИР) — стороны меняются местами
     * и показывается уведомление с обозначениями.
     *
     * @param uiLeftIsNewer {@code true}, если левая сторона UI — более новый файл
     */
    public static void runCompareTwoSide(IDtProject dtProject, Path uiLeft, Path uiRight,
        String uiLeftLabel, String uiRightLabel, boolean uiLeftIsNewer, boolean connectIfAbsent)
    {
        Path current;
        Path newer;
        String currentLabel;
        String newerLabel;
        boolean swapped;
        if (uiLeftIsNewer)
        {
            current = uiRight;
            newer = uiLeft;
            currentLabel = uiRightLabel;
            newerLabel = uiLeftLabel;
            swapped = true;
        }
        else
        {
            current = uiLeft;
            newer = uiRight;
            currentLabel = uiLeftLabel;
            newerLabel = uiRightLabel;
            swapped = false;
        }
        runCompare(dtProject, current, newer, null, connectIfAbsent);
        if (swapped)
        {
            String currentDisplay = CompareCurrentLinesPanel.sideLabelForCurrentLines(currentLabel);
            String newerDisplay = CompareCurrentLinesPanel.sideLabelForCurrentLines(newerLabel);
            ToastNotification.show("Обозначения сторон в ИР:", //$NON-NLS-1$
                "Текущий - " + currentDisplay + ", Новый - " + newerDisplay); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    public static void runCompare(IDtProject dtProject, Path pathMain, Path pathOther, Path pathAncestor,
        boolean connectIfAbsent)
    {
        if (pathMain == null || pathOther == null)
        {
            if (connectIfAbsent)
                ToastNotification.show(MENU_LABEL, "Не удалось получить файлы сторон сравнения"); //$NON-NLS-1$
            return;
        }
        if (dtProject == null)
        {
            if (connectIfAbsent)
                ToastNotification.show(MENU_LABEL, "Не удалось определить активный проект EDT"); //$NON-NLS-1$
            return;
        }
        IRSession irSession = IRApplication.getSession(dtProject, connectIfAbsent);
        if (irSession == null || irSession.executor == null)
            return;
        String ancestor = pathAncestor != null ? pathAncestor.toString() : null;
        irSession.executor.submit(() ->
        {
            try
            {
                Object irClient = irSession.getModule("ирКлиент"); //$NON-NLS-1$
                irSession.showWindow();
                ComBridge.invoke(irClient, "СравнитьТабличныеДокументыИмпортЛкс", //$NON-NLS-1$
                    pathMain.toString(), pathOther.toString(), ancestor);
            }
            catch (Exception e)
            {
                Global.log("CompareTabularDocumentsInIr: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }
}
