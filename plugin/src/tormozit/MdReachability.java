package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatch;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatchStatus;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.datasource.V8ProjectComparisonDataSourceDescriptor;

/**
 * Недостижимые файлы метаданных: папка объекта лежит в проекте, но объекта нет в составе
 * конфигурации ({@code Configuration.mdo}) либо, для формы, макета и команды, в описателе владельца.
 * EDT считает такой объект существующим лишь наполовину: в навигаторе его нет, а сравнение
 * конфигураций его файлы видит.
 * <p>
 * Признак тот же, что у суффикса {@code <?>} в {@link MdObjectUsageDecorator}, но считается по путям
 * и тексту файлов, а не по ресурсам рабочей области: так проверяется и состав Git, которого на диске
 * ещё или уже нет. Случаи, где связь определить нельзя, недостижимыми не считаются.
 */
public final class MdReachability
{
    static final String TITLE = "Найти недостижимые файлы метаданных";
    private static final String TAG = "MdReachability"; //$NON-NLS-1$
    private static final String CONFIGURATION = "src/Configuration/Configuration.mdo"; //$NON-NLS-1$
    /** Элемент состава в {@code Configuration.mdo}: {@code <catalogs>Catalog.Имя</catalogs>}. */
    private static final Pattern CONTENT_ITEM = Pattern.compile("<(\\w+)>(\\w+\\.[^<.]+)</\\1>"); //$NON-NLS-1$

    private MdReachability()
    {
    }

    /** Состав файлов проекта; пути — от корня проекта. */
    interface Files
    {
        boolean exists(String file);

        /** Текст файла либо {@code null}, если файла нет или он не читается. */
        String content(String file);
    }

    /**
     * Недостижимая папка.
     *
     * @param fullName полное имя объекта метаданных папки
     * @param type вид объекта
     * @param mdo описатель объекта, если он есть в папке, иначе {@code null}
     */
    record Item(IProject project, String folder, String fullName, String type, String mdo)
    {
    }

    /**
     * Состав до и после операции Git.
     *
     * @param files все файлы после операции
     * @param filesBefore все файлы до операции; {@code null} — уже недостижимые папки не отсеиваются
     * @param added файлы, которые операция создаёт
     */
    record Change(Collection<String> files, Collection<String> filesBefore, Collection<String> added,
        Files before, Files after)
    {
    }

    /** Файлы проекта в рабочей области. Текст каждого файла читается один раз. */
    static Files workspace(IProject project)
    {
        return cached(new Files()
        {
            @Override
            public boolean exists(String file) { return project.getFile(file).exists(); }

            @Override
            public String content(String file) { return GitChangedFileMenuHook.readWorkingCopyContent(project.getFile(file)); }
        });
    }

    static Files cached(Files source)
    {
        Map<String, String> contents = new HashMap<>();
        return new Files()
        {
            @Override
            public boolean exists(String file) { return source.exists(file); }

            @Override
            public synchronized String content(String file)
            {
                if (!contents.containsKey(file))
                    contents.put(file, source.content(file));
                return contents.get(file);
            }
        };
    }

    /**
     * Самые верхние недостижимые папки. Вложенные в них папки отдельно не показываются.
     *
     * @param files все файлы состава
     * @param scope файлы, папки которых проверяются (со всеми вышестоящими); {@code null} — все
     */
    static List<Item> find(IProject project, Collection<String> files, Collection<String> scope, Files source,
        IProgressMonitor monitor)
    {
        // Порядок строк ставит папку раньше вложенных в неё.
        Set<String> folders = new TreeSet<>();
        for (String file : scope != null ? scope : files)
            for (int slash = file.lastIndexOf('/'); slash > 0; slash = file.lastIndexOf('/', slash - 1))
                if (!folders.add(file.substring(0, slash)))
                    break;
        Set<String> found = new HashSet<>();
        List<Item> result = new ArrayList<>();
        for (String folder : folders)
        {
            if (monitor != null && monitor.isCanceled())
                throw new OperationCanceledException();
            if (inside(folder, found))
                continue;
            Item item = classify(project, folder, source);
            if (item != null)
            {
                found.add(folder);
                result.add(item);
            }
        }
        return result;
    }

    /**
     * Недостижимые папки, которые образует операция Git: папки создаваемых ею файлов, а если из
     * состава конфигурации убираются объекты — все папки итогового состава.
     */
    static List<Item> findNew(IProject project, Change change, IProgressMonitor monitor)
    {
        boolean all = losesObjects(change.before().content(CONFIGURATION), change.after().content(CONFIGURATION));
        if (!all && change.added().isEmpty())
            return List.of();
        List<Item> found = find(project, change.files(), all ? null : change.added(), change.after(), monitor);
        if (change.filesBefore() != null && !found.isEmpty())
        {
            // Операция отвечает только за папки, которые делает недостижимыми она сама.
            Set<String> before = new HashSet<>();
            for (Item item : find(project, change.filesBefore(), null, change.before(), monitor))
                before.add(item.folder());
            found.removeIf(item -> before.contains(item.folder()));
        }
        return found;
    }

    private static boolean losesObjects(String before, String after)
    {
        if (before == null || after == null)
            return false;
        Matcher items = CONTENT_ITEM.matcher(before);
        while (items.find())
            if (!after.contains(">" + items.group(2) + "<")) //$NON-NLS-1$ //$NON-NLS-2$
                return true;
        return false;
    }

    private static boolean inside(String folder, Set<String> parents)
    {
        if (parents.isEmpty())
            return false;
        for (int slash = folder.lastIndexOf('/'); slash > 0; slash = folder.lastIndexOf('/', slash - 1))
            if (parents.contains(folder.substring(0, slash)))
                return true;
        return false;
    }

    /** {@code null} — папка достижима, не является папкой объекта или её связь определить нельзя. */
    private static Item classify(IProject project, String folder, Files source)
    {
        String[] path = folder.split("/"); //$NON-NLS-1$
        if (path.length < 3 || !"src".equals(path[0]) || "Configuration".equals(path[1])) //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        String type = MdTypeMapping.folderToEnSing(path[1]);
        if (type == null || MdTypeMapping.subObjectTypeToEmfFeature(type) != null)
            return null;
        if (path.length == 3)
        {
            // Объект верхнего уровня: в составе конфигурации записан плоским тегом >Тип.Имя<.
            String configuration = source.content(CONFIGURATION);
            if (configuration == null || configuration.contains(">" + type + "." + path[2] + "<")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return null;
            String mdo = folder + "/" + path[2] + ".mdo"; //$NON-NLS-1$ //$NON-NLS-2$
            return new Item(project, folder, fullName(folder), typeName(path[1]), source.exists(mdo) ? mdo : null);
        }
        // Форма, макет или команда: своего описателя нет, объявлена в описателе владельца.
        // Реквизиты и команды общей формы лежат в Form.form — по описателю о них судить нельзя.
        if (path.length != 5 || "CommonForms".equals(path[1])) //$NON-NLS-1$
            return null;
        String child = MdTypeMapping.folderToEnSing(path[3]);
        String tag = child != null ? MdTypeMapping.subObjectTypeToEmfFeature(child) : null;
        if (!"forms".equals(tag) && !"templates".equals(tag) && !"commands".equals(tag)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        String owner = source.content("src/" + path[1] + "/" + path[2] + "/" + path[2] + ".mdo"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (owner == null || GitChangedFileMenuHook.isChildObjectDeclared(owner, tag, path[4]))
            return null;
        return new Item(project, folder, fullName(folder), typeName(path[3]), null);
    }

    private static String fullName(String folder)
    {
        String name = GetRef.pathToFullName(folder);
        return name != null ? name : folder;
    }

    private static String typeName(String typeFolder)
    {
        String name = MdTypeMapping.folderToRu(typeFolder);
        return name != null ? name : typeFolder;
    }

    /**
     * Команда меню: ищет недостижимые папки внутри выбранных и показывает их в панели «Поиск».
     * Проверяются и вышестоящие папки: папка внутри недостижимого объекта даёт сам объект.
     */
    static void findIn(Collection<? extends IContainer> roots)
    {
        Map<IProject, List<IContainer>> projects = new LinkedHashMap<>();
        for (IContainer root : roots)
            if (root != null && root.getProject() != null && root.getProject().isOpen())
                projects.computeIfAbsent(root.getProject(), project -> new ArrayList<>()).add(root);
        if (projects.isEmpty())
        {
            ToastNotification.show(TITLE, "Не удалось определить папки выбранных узлов.", 5_000);
            return;
        }
        new Job("Поиск недостижимых файлов метаданных")
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    List<UnreachableFilesSearchResult> results = new ArrayList<>();
                    for (Map.Entry<IProject, List<IContainer>> entry : projects.entrySet())
                    {
                        IProject project = entry.getKey();
                        List<String> labels = new ArrayList<>();
                        boolean whole = false;
                        for (IContainer root : entry.getValue())
                        {
                            whole |= root instanceof IProject;
                            if (!labels.contains(root.getName()))
                                labels.add(root.getName());
                        }
                        List<Item> items = scan(project, entry.getValue(), monitor);
                        if (!items.isEmpty())
                            results.add(new UnreachableFilesSearchResult(project, items,
                                whole ? null : MdReferenceSupport.scopeLabel(labels)));
                    }
                    Display.getDefault().asyncExec(() ->
                    {
                        if (results.isEmpty())
                            ToastNotification.show(TITLE, "Недостижимые файлы метаданных не найдены.", 5_000);
                        for (UnreachableFilesSearchResult result : results)
                            result.show(false);
                    });
                    return Status.OK_STATUS;
                }
                catch (OperationCanceledException canceled) { return Status.CANCEL_STATUS; }
                catch (CoreException error)
                {
                    Global.logError(TAG, "command failed", error); //$NON-NLS-1$
                    return new Status(IStatus.ERROR, Activator.PLUGIN_ID, error.getMessage(), error);
                }
            }
        }.schedule();
    }

    /** Недостижимые папки рабочей области внутри {@code roots} одного проекта, по полному имени. */
    private static List<Item> scan(IProject project, Collection<? extends IContainer> roots, IProgressMonitor monitor)
        throws CoreException
    {
        Set<String> files = new HashSet<>();
        for (IContainer root : roots)
        {
            // Метаданные лежат только в src: остальные папки проекта не обходятся.
            IContainer start = root instanceof IProject ? project.getFolder("src") : root; //$NON-NLS-1$
            if (!start.exists())
                continue;
            start.accept(proxy ->
            {
                if (monitor.isCanceled())
                    throw new OperationCanceledException();
                if (proxy.getType() == IResource.FILE)
                    files.add(proxy.requestFullPath().removeFirstSegments(1).toString());
                return true;
            }, IResource.NONE);
        }
        List<Item> items = find(project, files, null, workspace(project), monitor);
        items.sort(Comparator.comparing(Item::fullName, String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    /** Проекты, проверка которых перед сравнением ещё идёт: повторный запуск для них не нужен. */
    private static final Set<IProject> COMPARED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static boolean comparisonsWatched;

    /**
     * С началом любого сравнения конфигураций параллельно проверяет участвующие в нём проекты
     * рабочей области: недостижимые папки сравнение учитывает как существующие объекты. Сравнение
     * не задерживается; найденное показывается в панели «Поиск» с уведомлением.
     * <p>
     * Слушатель состояния — публичный {@link IComparisonManager#addStatusListener}: его события
     * приходят при любом способе запуска (мастер, Git, объединение). Вызывать в потоке интерфейса.
     */
    static void watchComparisons()
    {
        watchComparisons(0);
    }

    private static void watchComparisons(int attempt)
    {
        if (comparisonsWatched)
            return;
        IComparisonManager manager;
        try { manager = Global.getOsgiService(IComparisonManager.class); }
        catch (Throwable error)
        {
            // Вызывается из общего запуска хуков сравнения: сбой не должен прервать остальные.
            Global.logError(TAG, "comparison watch failed", error); //$NON-NLS-1$
            return;
        }
        if (manager == null)
        {
            // Служба сравнения регистрируется позже раннего запуска плагина.
            if (attempt < 60)
                Display.getDefault().timerExec(2_000, () -> watchComparisons(attempt + 1));
            return;
        }
        comparisonsWatched = true;
        manager.addStatusListener(new IComparisonManager.ICompareMergeStatusListener()
        {
            @Override
            public void statusChanged(ComparisonProcessHandle handle, ComparisonProcessStatus status)
            {
                try
                {
                    if (status != ComparisonProcessStatus.COMPARISON_PROCESS_INITIALIZATION_STARTED
                        || !ComfortSettings.isControlMdReferencesEnabled())
                        return;
                    checkCompared(handle.getMainDescriptor());
                    checkCompared(handle.getOtherDescriptor());
                    if (handle.isThreeWay())
                        checkCompared(handle.getCommonAncestorDescriptor());
                }
                catch (Throwable error) { Global.logError(TAG, "comparison status failed", error); } //$NON-NLS-1$
            }

            @Override
            public void statusChanged(CompareMergeProcessBatch batch, CompareMergeProcessBatchStatus status)
            {
            }
        });
    }

    /** Сторона сравнения — проект рабочей области: только у него есть рабочий каталог. */
    private static void checkCompared(IComparisonDataSourceDescriptor descriptor)
    {
        if (!(descriptor instanceof V8ProjectComparisonDataSourceDescriptor) || descriptor.getProjectName() == null)
            return;
        IProject project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot()
            .getProject(descriptor.getProjectName());
        if (!project.isOpen() || !COMPARED.add(project))
            return;
        Job job = new Job("Поиск недостижимых файлов метаданных")
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    List<Item> items = scan(project, List.of(project), monitor);
                    if (!items.isEmpty())
                        Display.getDefault().asyncExec(() ->
                        {
                            new UnreachableFilesSearchResult(project, items, null).show(false);
                            ToastNotification.show("Сравнение конфигураций", "В проекте " + project.getName()
                                + " есть недостижимые файлы метаданных (папок: " + items.size()
                                + "). Такие объекты не смогут корректно сравниться. Список показан в панели Поиск.",
                                15_000);
                        });
                    return Status.OK_STATUS;
                }
                catch (OperationCanceledException canceled) { return Status.CANCEL_STATUS; }
                catch (Throwable error)
                {
                    // Сбой проверки сравнению не мешает и пользователю не показывается.
                    Global.logError(TAG, "comparison check failed", error); //$NON-NLS-1$
                    return Status.OK_STATUS;
                }
                finally { COMPARED.remove(project); }
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    /** Папка, внутри которой ищет команда меню, для ресурса выбранного узла. */
    static IContainer folderOf(IResource resource)
    {
        if (resource == null)
            return null;
        // Корневой описатель представляет всю конфигурацию, а не свою папку.
        IContainer folder = resource instanceof IContainer container ? container : resource.getParent();
        return CONFIGURATION.startsWith(folder.getProjectRelativePath() + "/Configuration.mdo") //$NON-NLS-1$
            ? folder.getProject() : folder;
    }

    /**
     * Описатель владельца, в который можно прописать строку, — для формы и макета
     * ({@code src/<вид>/<имя>/Forms|Templates/<имя>}); иначе {@code null}. Команда требует свойств,
     * которых по одной папке не восстановить (группа команды).
     */
    static String ownerMdo(Item item)
    {
        String[] path = item.folder().split("/"); //$NON-NLS-1$
        return item.mdo() == null && path.length == 5
            && ("Forms".equals(path[3]) || "Templates".equals(path[3])) //$NON-NLS-1$ //$NON-NLS-2$
            ? "src/" + path[1] + "/" + path[2] + "/" + path[2] + ".mdo" : null; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** Строку можно подключить к родителю: объект — к конфигурации, форму и макет — к владельцу. */
    static boolean attachable(Item item)
    {
        return item.mdo() != null || ownerMdo(item) != null;
    }

    /** Блоки подчинённых объектов в описателе владельца в порядке штатной выгрузки. */
    private static final List<String> CHILD_TAGS = List.of("forms", "templates", "commands"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * Вид макета по расширению его файла {@code Template.*}; пустая строка — табличный документ,
     * вид по умолчанию, в описатель не пишется. Соответствие снято с описателей и папок макетов
     * реального проекта (БСП).
     */
    private static final Map<String, String> TEMPLATE_TYPES = Map.of(
        "mxlx", "", //$NON-NLS-1$ //$NON-NLS-2$
        "txt", "TextDocument", //$NON-NLS-1$ //$NON-NLS-2$
        "bin", "BinaryData", //$NON-NLS-1$ //$NON-NLS-2$
        "dcs", "DataCompositionSchema", //$NON-NLS-1$ //$NON-NLS-2$
        "dcsat", "DataCompositionAppearanceTemplate", //$NON-NLS-1$ //$NON-NLS-2$
        "htmldoc", "HTMLDocument", //$NON-NLS-1$ //$NON-NLS-2$
        "addin", "AddIn"); //$NON-NLS-1$ //$NON-NLS-2$

    /** Объявление подчинённого объекта: блок {@code <tag uuid>} с именем и строками свойств. */
    private record Child(String tag, String name, List<String> properties)
    {
    }

    /**
     * Объявление формы или макета по содержимому папки. Без файла самого объекта объявление дало
     * бы владельцу пустую форму или макет, поэтому такая папка не подключается.
     *
     * @throws IllegalStateException причина, по которой папку подключить нельзя
     */
    private static Child child(Item item) throws CoreException
    {
        IFolder folder = folder(item);
        if ("Forms".equals(folder.getParent().getName())) //$NON-NLS-1$
        {
            if (!folder.getFile("Form.form").exists()) //$NON-NLS-1$
                throw new IllegalStateException("нет файла Form.form"); //$NON-NLS-1$
            return new Child("forms", folder.getName(), List.of( //$NON-NLS-1$
                "    <usePurposes>PersonalComputer</usePurposes>", //$NON-NLS-1$
                "    <usePurposes>MobileDevice</usePurposes>")); //$NON-NLS-1$
        }
        for (IResource member : folder.members())
        {
            String extension = member instanceof IFile ? member.getFileExtension() : null;
            String type = extension != null && member.getName().startsWith("Template.") //$NON-NLS-1$
                ? TEMPLATE_TYPES.get(extension.toLowerCase(java.util.Locale.ROOT)) : null;
            if (type != null)
                return new Child("templates", folder.getName(), type.isEmpty() ? List.of() //$NON-NLS-1$
                    : List.of("    <templateType>" + type + "</templateType>")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        throw new IllegalStateException("нет файла макета известного вида (Template.*)"); //$NON-NLS-1$
    }

    /** Строка, перед которой ставится новый блок {@code tag}; {@code -1} — место не найдено. */
    private static int insertionLine(List<String> lines, String tag)
    {
        // После последнего блока того же вида, иначе после последнего блока предшествующих видов.
        for (int order = CHILD_TAGS.indexOf(tag); order >= 0; order--)
            for (int i = lines.size() - 1; i >= 0; i--)
                if (lines.get(i).equals("  </" + CHILD_TAGS.get(order) + ">")) //$NON-NLS-1$ //$NON-NLS-2$
                    return i + 1;
        // Иначе перед первым блоком следующих видов, иначе в конец описателя.
        for (int i = 0; i < lines.size(); i++)
            for (int order = CHILD_TAGS.indexOf(tag) + 1; order < CHILD_TAGS.size(); order++)
                if (lines.get(i).startsWith("  <" + CHILD_TAGS.get(order) + " ")) //$NON-NLS-1$ //$NON-NLS-2$
                    return i;
        for (int i = lines.size() - 1; i >= 0; i--)
            if (lines.get(i).startsWith("</mdclass:")) //$NON-NLS-1$
                return i;
        return -1;
    }

    /**
     * Прописывает формы и макеты в описателе владельца текстовой вставкой блоков — как
     * {@link GitChangedFileMenuHook#attachToConfiguration} для {@code Configuration.mdo}. Файлы
     * самих форм и макетов не трогаются.
     */
    private static void attachChildren(IFile ownerMdo, List<Child> children, MultiStatus errors)
    {
        try
        {
            ownerMdo.refreshLocal(IResource.DEPTH_ZERO, null);
            String content = GitChangedFileMenuHook.readWorkingCopyContent(ownerMdo);
            if (content == null)
                throw new IllegalStateException("не удалось прочитать файл"); //$NON-NLS-1$
            String eol = content.contains("\r\n") ? "\r\n" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            List<String> lines = new ArrayList<>(java.util.Arrays.asList(content.split("\r\n|\n", -1))); //$NON-NLS-1$
            boolean changed = false;
            for (Child child : children)
            {
                if (GitChangedFileMenuHook.isChildObjectDeclared(String.join("\n", lines), child.tag(), child.name())) //$NON-NLS-1$
                    continue;
                int at = insertionLine(lines, child.tag());
                if (at < 0)
                    throw new IllegalStateException("не найдено место вставки " + child.name()); //$NON-NLS-1$
                List<String> block = new ArrayList<>();
                block.add("  <" + child.tag() + " uuid=\"" + java.util.UUID.randomUUID() + "\">"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                block.add("    <name>" + child.name() + "</name>"); //$NON-NLS-1$ //$NON-NLS-2$
                block.addAll(child.properties());
                block.add("  </" + child.tag() + ">"); //$NON-NLS-1$ //$NON-NLS-2$
                lines.addAll(at, block);
                changed = true;
            }
            if (!changed)
                return;
            String result = String.join(eol, lines);
            if (!GitChangedFileMenuHook.isWellFormedXml(result))
                throw new IllegalStateException("результат вставки не является корректным XML — файл не изменён"); //$NON-NLS-1$
            try (java.io.ByteArrayInputStream in =
                new java.io.ByteArrayInputStream(result.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            {
                ownerMdo.setContents(in, IResource.FORCE, null);
            }
        }
        catch (Exception error)
        {
            errors.add(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                "Не удалось прописать формы и макеты в " + ownerMdo.getFullPath() + ": " + error.getMessage(), error)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Добавляет в индекс Git файлы родителей строк: {@code Configuration.mdo} для объекта с
     * описателем, описатель владельца для формы и макета. Файл индексируется целиком, со всеми
     * его изменениями рабочего каталога.
     */
    private static void stageParents(List<Item> items, MultiStatus errors)
    {
        Set<IFile> parents = new java.util.LinkedHashSet<>();
        for (Item item : items)
            parents.add(item.project().getFile(item.mdo() != null ? CONFIGURATION : ownerMdo(item)));
        for (IFile parent : parents)
            try
            {
                org.eclipse.egit.core.project.RepositoryMapping mapping =
                    org.eclipse.egit.core.project.RepositoryMapping.getMapping(parent);
                String path = mapping != null ? mapping.getRepoRelativePath(parent) : null;
                if (path == null)
                    throw new IllegalStateException("файл не принадлежит репозиторию Git"); //$NON-NLS-1$
                try (org.eclipse.jgit.api.Git git = new org.eclipse.jgit.api.Git(mapping.getRepository()))
                {
                    git.add().addFilepattern(path).call();
                }
            }
            catch (Exception error)
            {
                errors.add(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Не удалось добавить в индекс Git " //$NON-NLS-1$
                    + parent.getFullPath() + ": " + error.getMessage(), error)); //$NON-NLS-1$
            }
    }

    /**
     * Прописывает объекты строк у родителя: объект с описателем — в {@code Configuration.mdo},
     * форму и макет — в описателе владельца. Остальные строки пропускаются.
     *
     * @param indexed строки найдены в индексе Git (проверка перед фиксацией): файл родителя
     *        добавляется в индекс, иначе указатель в коммит не попадёт. Если указатель в рабочем
     *        каталоге уже есть, родитель не правится — только индексируется
     * @param done получает в потоке интерфейса строки, ставшие достижимыми
     */
    static void attach(List<Item> items, boolean indexed, Consumer<List<Item>> done)
    {
        List<Item> attachable = new ArrayList<>();
        for (Item item : items)
            if (attachable(item))
                attachable.add(item);
        if (attachable.isEmpty())
            return;
        WorkspaceJob job = new WorkspaceJob("Подключение объектов к родителю")
        {
            @Override
            public IStatus runInWorkspace(IProgressMonitor monitor)
            {
                MultiStatus errors = new MultiStatus(Activator.PLUGIN_ID, IStatus.ERROR,
                    "Не удалось подключить все объекты к родителю", null); //$NON-NLS-1$
                List<IFile> objects = new ArrayList<>();
                Map<IFile, List<Child>> children = new LinkedHashMap<>();
                for (Item item : attachable)
                    if (item.mdo() != null)
                        objects.add(item.project().getFile(item.mdo()));
                    else
                        try
                        {
                            children.computeIfAbsent(item.project().getFile(ownerMdo(item)), owner -> new ArrayList<>())
                                .add(child(item));
                        }
                        catch (CoreException | IllegalStateException error)
                        {
                            errors.add(new Status(IStatus.WARNING, Activator.PLUGIN_ID,
                                item.fullName() + " не подключён: " + error.getMessage())); //$NON-NLS-1$
                        }
                GitChangedFileMenuHook.attachToConfiguration(objects, errors);
                for (Map.Entry<IFile, List<Child>> owner : children.entrySet())
                    attachChildren(owner.getKey(), owner.getValue(), errors);
                Map<IProject, Files> sources = new HashMap<>();
                List<Item> attached = new ArrayList<>();
                for (Item item : attachable)
                    if (classify(item.project(), item.folder(),
                        sources.computeIfAbsent(item.project(), MdReachability::workspace)) == null)
                        attached.add(item);
                if (indexed)
                    stageParents(attached, errors);
                Display.getDefault().asyncExec(() -> done.accept(attached));
                if (!attached.isEmpty())
                    openAttached(attached.get(0));
                return errors.isOK() ? Status.OK_STATUS : errors;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    /**
     * Описатель объекта верхнего уровня ({@code src/<вид>/<имя>/<имя>.mdo}) для автопроверки
     * «Достижимость объекта метаданных»; {@code null} для прочих объектов.
     */
    public static IFile descriptorOf(org.eclipse.emf.ecore.EObject object)
    {
        com._1c.g5.v8.dt.core.platform.IResourceLookup lookup =
            Global.getOsgiService(com._1c.g5.v8.dt.core.platform.IResourceLookup.class);
        IFile file = lookup != null && object != null ? lookup.getPlatformResource(object) : null;
        if (file == null || !"mdo".equalsIgnoreCase(file.getFileExtension())) //$NON-NLS-1$
            return null;
        org.eclipse.core.runtime.IPath path = file.getProjectRelativePath();
        return path.segmentCount() == 4 && "src".equals(path.segment(0)) //$NON-NLS-1$
            && !"Configuration".equals(path.segment(1)) ? file : null; //$NON-NLS-1$
    }

    /**
     * Объекта описателя нет в составе конфигурации — тот же признак, что у команды поиска
     * (текст {@code Configuration.mdo} рабочего каталога кэшируется по отметке изменения файла).
     */
    public static boolean isUnreachable(IFile descriptor)
    {
        return descriptor != null && !GitChangedFileMenuHook.isAttachedToConfiguration(descriptor);
    }

    /** Быстрое исправление автопроверки: то же, что «Подключить к родителю» для одной строки. */
    public static void attachObject(IFile descriptor)
    {
        if (descriptor == null)
            return;
        IProject project = descriptor.getProject();
        Item item = classify(project, descriptor.getParent().getProjectRelativePath().toString(), workspace(project));
        if (item != null)
            attach(List.of(item), false, attached -> {});
    }

    /** Сколько ждать появления подключённого объекта в модели проекта. */
    private static final long ATTACHED_WAIT_MS = 60_000;

    /**
     * Открывает редактор подключённого объекта и показывает его в навигаторе. Объект появляется
     * в модели не сразу: EDT читает изменённый {@code Configuration.mdo} после завершения записи,
     * поэтому объект опрашивается отдельным заданием. Из нескольких подключённых открывается первый.
     */
    private static void openAttached(Item item)
    {
        // У формы своего описателя нет: объект строится по пути её папки.
        IFile mdo = item.mdo() != null ? item.project().getFile(item.mdo()) : null;
        Job job = new Job("Открытие подключённого объекта")
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                long started = System.currentTimeMillis();
                org.eclipse.emf.ecore.EObject object = null;
                try
                {
                    while (!monitor.isCanceled() && System.currentTimeMillis() - started < ATTACHED_WAIT_MS)
                    {
                        object = mdo != null ? GitChangedFileMenuHook.resolveEObject(mdo)
                            : GitChangedFileMenuHook.resolveEObjectForResource(folder(item));
                        if (object != null)
                            break;
                        Thread.sleep(500);
                    }
                }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (Throwable error) { Global.logError(TAG, "attached resolve failed", error); } //$NON-NLS-1$
                org.eclipse.emf.ecore.EObject resolved = object;
                if (!monitor.isCanceled())
                    Display.getDefault().asyncExec(() ->
                    {
                        if (resolved == null)
                        {
                            ToastNotification.show("Подключить к родителю", "Объект " + item.fullName()
                                + " прописан у родителя, но в модели проекта пока не появился:"
                                + " редактор не открыт.", 10_000);
                            return;
                        }
                        org.eclipse.ui.IWorkbenchWindow window =
                            org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                        GitChangedFileMenuHook.openInEditor(resolved, null, window != null ? window.getShell() : null);
                        NavigatorReveal.revealAndActivateIfHidden(resolved);
                        // Автопроверка достижимости сама не пересчитывается: объект не менялся.
                        ComfortCheckRecompute.recomputeObjects(item.project(), List.of(resolved));
                    });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    /** Папка строки в рабочей области. */
    static IFolder folder(Item item)
    {
        return item.project().getFolder(item.folder());
    }
}
