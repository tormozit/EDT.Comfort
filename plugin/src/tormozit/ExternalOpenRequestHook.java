package tormozit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.ui.util.OpenHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Приём запросов «открыть в EDT» от внешних программ (например, из кода 1С).
 *
 * <p>Внешняя программа записывает файл {@code %APPDATA%\1C\EDT.Comfort\open-request.json} вида
 * {@code {"Configuration":"ИмяПроекта","FullName":"Справочник.Товары.Форма.ФормаЭлемента.Элемент.Код"}}.
 * Недописанный файл приёмник не принимает: при ошибке разбора перечитывает его, а позже получает
 * следующее событие изменения.
 *
 * <p>Файл только читается, не меняется и не удаляется. Реакция — только на файл, изменённый не
 * более {@link #FRESH_MS} назад. Каталог просматривает каждый запущенный процесс EDT, и форму
 * открывает каждый, в чьей рабочей области есть открытый проект с такой конфигурацией. Поле
 * {@code Configuration} — имя конфигурации в метаданных, а не имя проекта в EDT.
 *
 * <p>FullName: {@code <Тип>.<Имя>.Форма.<ИмяФормы>[.Элемент.<ИмяЭлемента>]} (русские и английские
 * имена типов), либо {@code ОбщаяФорма.<Имя>[.Элемент.<ИмяЭлемента>]}. Элемент ищется по имени в
 * любом месте дерева элементов формы.
 */
public final class ExternalOpenRequestHook implements IStartup
{
    private static final String TAG = "ExternalOpenRequest"; //$NON-NLS-1$
    private static final String REQUEST_FILE = "open-request.json"; //$NON-NLS-1$
    /** Реагируем только на файл, изменённый не раньше этого срока назад. */
    private static final long FRESH_MS = TimeUnit.SECONDS.toMillis(5);
    private static final int READ_ATTEMPTS = 5;

    private static volatile boolean started;
    /** Время изменения файла, для которого форма уже открыта. Доступ только из потока вотчера. */
    private static long lastHandledModified = -1;

    @Override
    public void earlyStartup()
    {
        if (started)
            return;
        started = true;
        Path dir = requestDir();
        if (dir == null)
            return;
        Thread thread = new Thread(() -> watch(dir), "Comfort: external open request watcher"); //$NON-NLS-1$
        thread.setDaemon(true);
        thread.start();
    }

    private static Path requestDir()
    {
        String base = System.getenv("APPDATA"); //$NON-NLS-1$
        if (base == null || base.isBlank())
            return null;
        return Paths.get(base, "1C", "EDT.Comfort"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void watch(Path dir)
    {
        try (WatchService service = dir.getFileSystem().newWatchService())
        {
            Files.createDirectories(dir);
            dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            while (true)
            {
                WatchKey key = service.take();
                for (WatchEvent<?> event : key.pollEvents())
                {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW)
                    {
                        handle(dir.resolve(REQUEST_FILE));
                        continue;
                    }
                    Path name = (Path) event.context();
                    if (name != null)
                        handle(dir.resolve(name));
                }
                if (!key.reset())
                    return;
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch (IOException | RuntimeException e)
        {
            Global.log(TAG, "сбой слежения: " + e); //$NON-NLS-1$
        }
    }

    /** Время изменения файла или {@code -1}, если файла нет. */
    private static long modifiedMillis(Path file)
    {
        try
        {
            FileTime modified = Files.getLastModifiedTime(file);
            return modified.toMillis();
        }
        catch (IOException e)
        {
            return -1;
        }
    }

    /** Файл запроса только читается — не переименовывается и не удаляется. */
    private static void handle(Path file)
    {
        String fileName = file.getFileName().toString();
        if (!REQUEST_FILE.equalsIgnoreCase(fileName))
            return;
        long modified = modifiedMillis(file);
        long age = System.currentTimeMillis() - modified;
        if (modified < 0 || age > FRESH_MS)
            return;
        // Одну запись файла система сообщает несколькими событиями — повторно не открываем.
        if (modified == lastHandledModified)
            return;
        JsonNode request = readRequest(file);
        if (request == null)
            return;
        lastHandledModified = modified;
        String projectName = text(request, "Configuration"); //$NON-NLS-1$
        String fqn = text(request, "FullName"); //$NON-NLS-1$
        if (projectName == null || fqn == null)
            return;
        List<IProject> projects = findProjects(projectName);
        // пусто — конфигурации нет в этом процессе EDT
        if (projects.isEmpty())
            return;
        for (IProject project : projects)
        {
            try
            {
                open(project, fqn);
            }
            catch (RuntimeException e)
            {
                Global.log(TAG, "сбой открытия " + fqn + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /**
     * Открытые проекты, у которых имя конфигурации в метаданных равно {@code configurationName}.
     * Приложение 1С знает имя конфигурации, но не имя проекта в EDT, которое может быть любым.
     */
    private static List<IProject> findProjects(String configurationName)
    {
        List<IProject> result = new ArrayList<>();
        if (!(Global.getServiceByClass(IV8ProjectManager.class) instanceof IV8ProjectManager manager))
            return result;
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (!project.isOpen())
                continue;
            IV8Project v8Project = manager.getProject(project);
            if (v8Project instanceof IConfigurationProject configurationProject
                && configurationProject.getConfiguration() != null
                && configurationName.equalsIgnoreCase(configurationProject.getConfiguration().getName()))
                result.add(project);
        }
        return result;
    }

    private static JsonNode readRequest(Path file)
    {
        for (int attempt = 0; attempt < READ_ATTEMPTS; attempt++)
        {
            try
            {
                return new ObjectMapper().readTree(Files.readAllBytes(file));
            }
            catch (java.nio.file.NoSuchFileException gone)
            {
                return null;
            }
            catch (IOException e)
            {
                try
                {
                    Thread.sleep(100);
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        String text = value == null || !value.isTextual() ? null : value.asText().trim();
        return text == null || text.isEmpty() ? null : text;
    }

    // =========================================================================
    // Разбор FQN и открытие
    // =========================================================================

    private static void open(IProject project, String fqn)
    {
        String[] parts = fqn.split("\\.", -1); //$NON-NLS-1$
        int formEnd = formNameEnd(parts);
        if (formEnd < 0)
            return;
        String formFullName = String.join(".", java.util.Arrays.copyOf(parts, formEnd)); //$NON-NLS-1$
        String itemName = formEnd < parts.length ? parts[parts.length - 1] : null;
        if (!(Global.getServiceByClass(IV8ProjectManager.class) instanceof IV8ProjectManager manager))
            return;
        IV8Project v8Project = manager.getProject(project);
        if (v8Project == null)
            return;
        EObject resolved = GoToDefinition.resolveEObjectByQualifiedName(formFullName, v8Project);
        if (!(resolved instanceof BasicForm basicForm))
            return;
        FormItem item = itemName == null ? null : findItem(basicForm.getForm(), itemName);
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.asyncExec(() -> openOnUi(basicForm, item));
    }

    /** Индекс сразу за именем формы в массиве сегментов FQN или {@code -1}. */
    private static int formNameEnd(String[] parts)
    {
        if (parts.length >= 2 && isOneOf(parts[0], "CommonForm", "ОбщаяФорма")) //$NON-NLS-1$ //$NON-NLS-2$
            return 2;
        for (int i = 2; i + 1 < parts.length; i += 2)
            if (isOneOf(parts[i], "Form", "Форма")) //$NON-NLS-1$ //$NON-NLS-2$
                return i + 2;
        return -1;
    }

    private static boolean isOneOf(String value, String... variants)
    {
        for (String variant : variants)
            if (variant.equalsIgnoreCase(value))
                return true;
        return false;
    }

    private static FormItem findItem(EObject form, String name)
    {
        if (form == null)
            return null;
        List<EObject> queue = new ArrayList<>(form.eContents());
        for (int i = 0; i < queue.size(); i++)
        {
            EObject object = queue.get(i);
            if (object instanceof FormItem formItem && name.equalsIgnoreCase(formItem.getName()))
                return formItem;
            queue.addAll(object.eContents());
        }
        return null;
    }

    private static void openOnUi(BasicForm basicForm, FormItem item)
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        OpenHelper helper = page == null ? new OpenHelper() : new OpenHelper(page);
        try
        {
            if (item != null)
                helper.openEditor(basicForm, null, new StructuredSelection(item));
            else
                helper.openEditor(basicForm);
        }
        catch (RuntimeException e)
        {
            helper.openEditor(basicForm);
        }
        try
        {
            // На переднем плане окно приложения 1С, приславшего запрос: присоединять к нему ввод
            // (activateWorkbench) нельзя — приложение зависает, issue 600.
            WinWindowActivator.activateWorkbenchWithoutInputAttach();
        }
        catch (RuntimeException | LinkageError e)
        {
            // Окно не вынесено вперёд — форма уже открыта.
        }
    }
}
