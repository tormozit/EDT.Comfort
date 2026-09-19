package tormozit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/**
 * Переносит файловую часть категории «Проверки» ({@code <проект>/.settings/*.cset}) —
 * то, что штатный механизм {@code org.eclipse.ui.preferenceTransfer} (только
 * {@code IEclipsePreferences}) скопировать не может; сами файлы — JSON-профили проверок,
 * см. декомпилированный {@code JSONCheckSettingsProfileStore}. Вызывается из
 * {@link ComfortPreferencesExportPage}/{@link ComfortPreferencesImportPage} рядом с обычным
 * preferences-переносом ({@link ComfortPreferenceTransferFilter#CHECKS_PREFERENCE_QUALIFIER}).
 * <p>
 * Формат в {@code .par}: {@code tormozit.comfort.checkProfile.<n>.name=<имя файла>} и
 * {@code tormozit.comfort.checkProfile.<n>.content=<Base64>}, {@code n} с нуля.
 */
final class ComfortCheckProfileTransfer
{
    private static final String TAG = "ComfortCheckProfileTransfer"; //$NON-NLS-1$

    private static final String SETTINGS_FOLDER = ".settings"; //$NON-NLS-1$

    private static final String PROFILE_EXTENSION = ".cset"; //$NON-NLS-1$

    private static final String KEY_PREFIX = "tormozit.comfort.checkProfile."; //$NON-NLS-1$

    private static final String KEY_NAME_SUFFIX = ".name"; //$NON-NLS-1$

    private static final String KEY_CONTENT_SUFFIX = ".content"; //$NON-NLS-1$

    private ComfortCheckProfileTransfer()
    {
    }

    /** Добавляет в {@code props} содержимое {@code .cset}-файлов проекта {@code projectName}. */
    static void embed(Properties props, String projectName)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen())
        {
            Global.tempLog(TAG, "embed: проект недоступен: " + projectName); //$NON-NLS-1$
            return;
        }
        IFolder settings = project.getFolder(SETTINGS_FOLDER);
        if (!settings.exists())
        {
            Global.tempLog(TAG, "embed: нет папки .settings в " + projectName); //$NON-NLS-1$
            return;
        }
        int n = 0;
        try
        {
            for (IResource member : settings.members())
            {
                if (!(member instanceof IFile file) || !file.getName().endsWith(PROFILE_EXTENSION))
                    continue;
                byte[] bytes = readAllBytes(file);
                props.setProperty(KEY_PREFIX + n + KEY_NAME_SUFFIX, file.getName());
                props.setProperty(KEY_PREFIX + n + KEY_CONTENT_SUFFIX,
                        Base64.getEncoder().encodeToString(bytes));
                n++;
            }
        }
        catch (CoreException | IOException e)
        {
            Global.tempLogException(TAG, "embed: " + projectName, e); //$NON-NLS-1$
        }
        Global.tempLog(TAG, "embed: проект=" + projectName + " файлов=" + n); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Восстанавливает {@code .cset}-файлы из {@code props} в проект {@code projectName}. */
    static void extract(Properties props, String projectName)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen())
        {
            Global.tempLog(TAG, "extract: проект недоступен: " + projectName); //$NON-NLS-1$
            return;
        }
        List<Integer> indices = indices(props);
        if (indices.isEmpty())
            return;
        try
        {
            IFolder settings = project.getFolder(SETTINGS_FOLDER);
            if (!settings.exists())
                settings.create(true, true, new NullProgressMonitor());
            for (int n : indices)
            {
                String name = props.getProperty(KEY_PREFIX + n + KEY_NAME_SUFFIX);
                String content = props.getProperty(KEY_PREFIX + n + KEY_CONTENT_SUFFIX);
                if (name == null || content == null)
                    continue;
                byte[] bytes = Base64.getDecoder().decode(content);
                IFile file = settings.getFile(name);
                try (InputStream in = new ByteArrayInputStream(bytes))
                {
                    if (file.exists())
                        file.setContents(in, true, true, new NullProgressMonitor());
                    else
                        file.create(in, true, new NullProgressMonitor());
                }
            }
            settings.refreshLocal(IResource.DEPTH_ONE, new NullProgressMonitor());
        }
        catch (CoreException | IOException e)
        {
            Global.tempLogException(TAG, "extract: " + projectName, e); //$NON-NLS-1$
        }
        Global.tempLog(TAG, "extract: проект=" + projectName + " файлов=" + indices.size()); //$NON-NLS-1$ //$NON-NLS-2$
        forceReload(project);
    }

    /**
     * По декомпилированному {@code CheckRepository$CheckSettingsProfileExternalChangeListener}
     * (bundle {@code com.e1c.g5.v8.dt.check}): при внешнем изменении файла профиля
     * {@code scheduleProfileUpdates} реально перечитывает настройки (вызывает
     * {@code ProjectCheckSettings.reloadFromResources()}) только если
     * {@code otherProfilesChanged || activeProfileRemoved} — ветка {@code activeProfileChanged}
     * (файл ИЗМЕНИЛСЯ, но остался тем же именем/активным профилем — ровно наш случай, когда и
     * источник, и приёмник используют профиль "Default") туда не попадает вообще. Это пробел
     * самой EDT, не нашего кода — после {@link #extract} перечитываем настройки принудительно.
     * <p>
     * {@code getProjectSettings}/{@code reloadFromResources} — internal API, недоступны через
     * {@link ICheckRepository} напрямую; {@code getOsgiService} может вернуть peaberry-прокси
     * без внутренностей службы — сначала {@link Global#unwrapServiceProxy}, затем
     * {@link Global#invoke} (обходит private/protected по всей иерархии классов).
     */
    private static void forceReload(IProject project)
    {
        Object repository = Global.unwrapServiceProxy(Global.getOsgiService(ICheckRepository.class));
        if (repository == null)
        {
            Global.tempLog(TAG, "forceReload: служба ICheckRepository недоступна"); //$NON-NLS-1$
            return;
        }
        Object projectSettings = Global.invoke(repository, "getProjectSettings", project); //$NON-NLS-1$
        if (projectSettings == null)
        {
            Global.tempLog(TAG, "forceReload: getProjectSettings вернул null для " + project.getName()); //$NON-NLS-1$
            return;
        }
        Global.invoke(projectSettings, "refreshAvailableProfiles"); //$NON-NLS-1$
        Object reloaded = Global.invoke(projectSettings, "reloadFromResources"); //$NON-NLS-1$
        Global.tempLog(TAG, "forceReload: проект=" + project.getName() //$NON-NLS-1$
                + " reloadFromResources выполнен=" + (reloaded != null)); //$NON-NLS-1$
    }

    private static List<Integer> indices(Properties props)
    {
        List<Integer> result = new ArrayList<>();
        for (String key : props.stringPropertyNames())
        {
            if (!key.startsWith(KEY_PREFIX) || !key.endsWith(KEY_NAME_SUFFIX))
                continue;
            String middle = key.substring(KEY_PREFIX.length(), key.length() - KEY_NAME_SUFFIX.length());
            try
            {
                result.add(Integer.valueOf(middle));
            }
            catch (NumberFormatException ignored)
            {
            }
        }
        return result;
    }

    private static byte[] readAllBytes(IFile file) throws CoreException, IOException
    {
        try (InputStream in = file.getContents())
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
