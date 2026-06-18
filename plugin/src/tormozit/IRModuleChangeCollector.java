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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.ecore.EObject;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.common.Pair;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationListener;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseEqualityState;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IUpdateInfobaseFlow;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.e1c.g5.dt.core.api.naming.INamingService;

/**
 * Сбор изменённых BSL-модулей через штатный механизм EDT (dirty-state per инфобаза).
 */
public final class IRModuleChangeCollector
{
    private static final String STATE_MANAGER_CLASS =
        "com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager"; //$NON-NLS-1$

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

    static List<ModuleSyncEntry> collectPendingModules(
        IRSession session,
        IProject project,
        InfobaseReference infobase,
        String exceptModuleName)
    {
        List<ModuleSyncEntry> result = new ArrayList<>();
        if (session == null || project == null || infobase == null)
            return result;

        IInfobaseSynchronizationStateManager stateMgr = getStateManager();
        INamingService naming = Global.getOsgiService(INamingService.class);
        if (stateMgr == null || naming == null)
        {
            IRModuleSyncDebug.problem("сервисы синхронизации EDT недоступны"); //$NON-NLS-1$
            return result;
        }

        IDtProject dtProject = Global.getDtProjectFromWorkspaceProject(project);
        if (dtProject == null)
        {
            IRModuleSyncDebug.problem("IDtProject не найден для " + project.getName()); //$NON-NLS-1$
            return result;
        }

        boolean dirty;
        try
        {
            dirty = stateMgr.isProjectDirty(project, infobase);
        }
        catch (Exception e)
        {
            IRModuleSyncDebug.problem("isProjectDirty: " + e.getMessage()); //$NON-NLS-1$
            return result;
        }

        if (!dirty)
        {
            IRModuleSyncDebug.logCollect(0, false);
            return result;
        }

        if (stateMgr.isFlowActive(infobase))
        {
            IRModuleSyncDebug.problem("flow уже активен для инфобазы — пропуск collect"); //$NON-NLS-1$
            return result;
        }

        IUpdateInfobaseFlow flow = null;
        try
        {
            flow = stateMgr.startUpdateInfobaseFlow(dtProject, infobase);
            flow.start();
            @SuppressWarnings("unchecked")
            Pair<Set<EObject>, Set<EObject>> changes = flow.collectAddedAndModifiedInEdtObjects();
            Set<EObject> all = new HashSet<>();
            if (changes.getFirst() != null)
                all.addAll(changes.getFirst());
            if (changes.getSecond() != null)
                all.addAll(changes.getSecond());

            for (EObject obj : all)
            {
                ModuleSyncEntry entry = toModuleEntry(obj, naming, project, session);
                if (entry == null)
                    continue;
                if (exceptModuleName != null
                        && !exceptModuleName.isEmpty()
                        && exceptModuleName.equals(entry.moduleName))
                    continue;
                result.add(entry);
            }
            IRModuleSyncDebug.logCollect(result.size(), true);
        }
        catch (Exception e)
        {
            IRModuleSyncDebug.problem("collect: " + e.getMessage()); //$NON-NLS-1$
        }
        finally
        {
            if (flow != null)
            {
                try
                {
                    flow.cancel();
                }
                catch (Exception ignored) {}
            }
        }
        return result;
    }

    /** Fingerprint содержимого (MD5, 16 байт) для дедупликации push в ИР. Без полного {@code getBytes}. */
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

    private static ModuleSyncEntry toModuleEntry(
        EObject obj,
        INamingService naming,
        IProject project,
        IRSession session)
    {
        if (obj == null)
            return null;
        String path = naming.getFilePath(obj);
        if (path == null || !path.endsWith(".bsl")) //$NON-NLS-1$
            return null;

        String bslPath = path.replace('\\', '/');
        IFile file = project.getFile(bslPath);
        if (!file.exists())
            return null;

        String moduleName = GetRef.resolveSetTextModuleName(file);
        if (moduleName == null || moduleName.isEmpty())
            return null;

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
                return null;
            return new ModuleSyncEntry(bslPath, moduleName, text, hash);
        }
        catch (CoreException | IOException e)
        {
            IRModuleSyncDebug.problem("read " + bslPath + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /** MD5 по уже прочитанным UTF-8 байтам (без повторного getBytes). */
    private static byte[] fingerprintUtf8(byte[] utf8)
    {
        byte[] input = utf8 != null ? utf8 : new byte[0];
        return CONTENT_MD5.get().digest(input);
    }

    private static IInfobaseSynchronizationStateManager getStateManager()
    {
        BundleContext ctx = Global.ourContext();
        if (ctx == null)
            return null;
        ServiceReference<?> ref = ctx.getServiceReference(STATE_MANAGER_CLASS);
        if (ref == null)
            return null;
        Object svc = ctx.getService(ref);
        return svc instanceof IInfobaseSynchronizationStateManager mgr ? mgr : null;
    }

    private static final String SYNC_MANAGER_CLASS =
        "com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager"; //$NON-NLS-1$

    /** Подписка на dirty-state EDT (диагностика; список собирается on-demand). */
    static void installDirtyListener()
    {
        BundleContext ctx = Global.ourContext();
        if (ctx == null)
            return;
        ServiceReference<?> ref = ctx.getServiceReference(SYNC_MANAGER_CLASS);
        if (ref == null)
            return;
        Object svc = ctx.getService(ref);
        if (!(svc instanceof IInfobaseSynchronizationManager syncMgr))
            return;
        syncMgr.addInfobaseSynchronizationListener(new IInfobaseSynchronizationListener()
        {
            @Override
            public void synchronizationStateChanged(InfobaseReference infobase, boolean dirty)
            {
                if (dirty && IRApplication.getInstance().isConnected(infobase))
                    IRModuleSyncDebug.logDirty(infobase);
            }

            @Override
            public void equalityStateChanged(InfobaseReference infobase, InfobaseEqualityState state)
            {
            }
        });
    }
}
