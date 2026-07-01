package tormozit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Лёгкий сборщик изменённых BSL-модулей на основе Eclipse Resource Delta.
 * <p>
 * Не использует тяжёлый {@code IUpdateInfobaseFlow} и штатный синхронизатор базы.
 * Отслеживает только изменения файловой системы (POST_CHANGE), что даёт
 * O(количество изменённых .bsl) вместо O(вся EMF-модель проекта).
 */
public final class IRModuleChangeCollector
{
    private static final ThreadLocal<MessageDigest> CONTENT_MD5 = ThreadLocal.withInitial(() -> {
        try
        {
            return MessageDigest.getInstance("MD5"); //$NON-NLS-1$
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("MD5", e); //$NON-NLS-1$
        }
    });

    private static final ThreadLocal<CharsetEncoder> UTF8_ENCODER = ThreadLocal.withInitial(() ->
        StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE));

    /** Проект → набор относительных путей к изменённым .bsl файлам. */
    private static final Map<IProject, Set<String>> PENDING_BSL_PATHS = new ConcurrentHashMap<>();

    private static volatile boolean listenerInstalled = false;

    private IRModuleChangeCollector() {}

    static final class ModuleSyncEntry
    {
        final String bslPath;
        final String moduleName;
        final String text;
        final byte[] hash;

        ModuleSyncEntry(String bslPath, String moduleName, String text, byte[] hash)
        {
            this.bslPath = bslPath;
            this.moduleName = moduleName;
            this.text = text;
            this.hash = hash;
        }
    }

    /**
     * Возвращает накопленные изменения BSL-модулей для проекта.
     * <p>
     * Убраны вызовы {@code isProjectDirty}, {@code isFlowActive} и {@code startUpdateInfobaseFlow}.
     * Работа идёт напрямую с индексом файловых изменений.
     */
    static List<ModuleSyncEntry> collectPendingModules(
        IRSession session,
        IProject project,
        InfobaseReference infobase,
        String exceptModuleName)
    {
        List<ModuleSyncEntry> result = new ArrayList<>();
        if (session == null || project == null || infobase == null)
            return result;

        ensureListenerInstalled();

        IDtProject dtProject = Global.getDtProjectFromWorkspaceProject(project);
        if (dtProject == null)
        {
            IRModuleSyncDebug.problem("IDtProject не найден для " + project.getName()); //$NON-NLS-1$
            return result;
        }

        Set<String> pending = PENDING_BSL_PATHS.get(project);
        if (pending == null || pending.isEmpty())
        {
            IRModuleSyncDebug.logCollect(0, false);
            return result;
        }

        // Снимаем копию, чтобы не блокировать listener на время IO
        List<String> paths = new ArrayList<>(pending);

        for (String bslPath : paths)
        {
            IFile file = project.getFile(bslPath);
            if (!file.exists())
                continue;

            String moduleName = GetRef.resolveSetTextModuleName(file);
            if (moduleName == null || moduleName.isEmpty())
                continue;
            if (exceptModuleName != null && !exceptModuleName.isEmpty()
                && exceptModuleName.equals(moduleName))
                continue;

            try
            {
                byte[] bytes;
                try (InputStream in = file.getContents())
                {
                    bytes = in.readAllBytes();
                }
                String text = new String(bytes, StandardCharsets.UTF_8);
                byte[] hash = fingerprintUtf8(bytes);
                if (session.isAlreadyPushed(bslPath, hash))
                    continue;

                result.add(new ModuleSyncEntry(bslPath, moduleName, text, hash));
            }
            catch (CoreException | IOException e)
            {
                IRModuleSyncDebug.problem("read " + bslPath + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        // Удаляем обработанные пути. Если файл не существовал или isAlreadyPushed —
        // он всё равно больше не считается pending.
        pending.removeAll(paths);
        if (pending.isEmpty())
            PENDING_BSL_PATHS.remove(project);

        IRModuleSyncDebug.logCollect(result.size(), true);
        return result;
    }

    /** Сброс накопленных изменений для проекта (например, после ручной синхронизации). */
    static void flush(IProject project)
    {
        if (project != null)
            PENDING_BSL_PATHS.remove(project);
    }

    // -------------------------------------------------------------------------
    // Resource Change Listener
    // -------------------------------------------------------------------------

    public static synchronized void ensureListenerInstalled()
    {
        if (listenerInstalled)
            return;
        listenerInstalled = true;

        ResourcesPlugin.getWorkspace().addResourceChangeListener(
            new IResourceChangeListener()
            {
                @Override
                public void resourceChanged(IResourceChangeEvent event)
                {
                    if (event.getDelta() == null)
                        return;
                    try
                    {
                        event.getDelta().accept(new IResourceDeltaVisitor()
                        {
                            @Override
                            public boolean visit(IResourceDelta delta) throws CoreException
                            {
                                if (!(delta.getResource() instanceof IFile file))
                                    return true; // папки и проекты — обходим дальше

                                if (!file.getName().endsWith(".bsl")) //$NON-NLS-1$
                                    return false; // не BSL — не интересует

                                int kind = delta.getKind();
                                IProject project = file.getProject();
                                String path = file.getProjectRelativePath().toString();

                                if (kind == IResourceDelta.REMOVED)
                                {
                                    Set<String> set = PENDING_BSL_PATHS.get(project);
                                    if (set != null)
                                        set.remove(path);
                                    return false;
                                }

                                if (kind == IResourceDelta.ADDED
                                    || (kind == IResourceDelta.CHANGED
                                        && (delta.getFlags() & IResourceDelta.CONTENT) != 0))
                                {
                                    PENDING_BSL_PATHS
                                        .computeIfAbsent(project, k -> ConcurrentHashMap.newKeySet())
                                        .add(path);
                                }

                                return false; // файл — терминальный узел
                            }
                        });
                    }
                    catch (CoreException e)
                    {
                        IRModuleSyncDebug.problem("ResourceDelta: " + e.getMessage()); //$NON-NLS-1$
                    }
                }
            },
            IResourceChangeEvent.POST_CHANGE);
    }

    // -------------------------------------------------------------------------
    // Fingerprint / Hash
    // -------------------------------------------------------------------------

    static byte[] contentFingerprint(String text)
    {
        MessageDigest md = CONTENT_MD5.get();
        md.reset();
        if (text != null && !text.isEmpty())
            md5UpdateUtf8(md, text);
        return md.digest();
    }

    private static void md5UpdateUtf8(MessageDigest md, String text)
    {
        CharsetEncoder enc = UTF8_ENCODER.get();
        enc.reset();
        CharBuffer in = CharBuffer.wrap(text);
        ByteBuffer out = ByteBuffer.allocate(8192);
        while (true)
        {
            CoderResult cr = enc.encode(in, out, !in.hasRemaining());
            out.flip();
            if (out.hasRemaining())
                md.update(out);
            out.clear();
            if (cr.isUnderflow() && !in.hasRemaining())
                break;
        }
    }

    private static byte[] fingerprintUtf8(byte[] utf8)
    {
        byte[] input = utf8 != null ? utf8 : new byte[0];
        return CONTENT_MD5.get().digest(input);
    }
}