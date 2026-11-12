package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

/**
 * Доработки окон установки и обновления ПО: обновление сайтов перед выбором новой версии.
 * Механизм загрузки — как в RepositoryManipulationPage.refreshRepository из
 * org.eclipse.equinox.p2.ui 2.8.200.v20231105-1139: refresh обоих менеджеров.
 * Ошибки необязательной доработки изолированы от штатных обработчиков и отключают её до перезапуска.
 *
 * https://github.com/tormozit/EDT.Comfort/issues/1049
 */
public final class AvailableSoftwareDialogHook implements IStartup
{
    private static final String PAGE_CLASS = "org.eclipse.equinox.internal.p2.ui.dialogs.AvailableIUsPage"; //$NON-NLS-1$
    private static final String SESSION_KEY = "tormozit.availableSoftware.session"; //$NON-NLS-1$
    private static final String LOG = "available-software"; //$NON-NLS-1$
    private static volatile boolean disabled;

    /** Граница чужого вызова: в том числе Error от PDE при неполной компиляции метода. */
    private static void runSafely(String phase, Runnable action)
    {
        if (disabled)
            return;
        try { action.run(); }
        // Штатный код сам прочитает отмену из monitor; отмена не является сбоем доработки.
        catch (OperationCanceledException canceled) { }
        catch (Throwable failure) { disable(phase, failure); }
    }

    private static void disable(String phase, Throwable failure)
    {
        disabled = true;
        // Даже отказ диагностического канала не должен выходить в штатный обработчик EDT.
        try { Global.tempLogException(LOG, phase + "; extension disabled until restart", failure); } //$NON-NLS-1$
        catch (Throwable ignored) { }
    }

    /** До загрузки классов p2; вызывается из Activator. */
    public static void installUpdateRefreshHook()
    {
        runSafely("install update refresh", UpdateSitesRefresh::install); //$NON-NLS-1$
    }

    @Override
    public void earlyStartup()
    {
        runSafely("earlyStartup", () -> //$NON-NLS-1$
        {
            installUpdateRefreshHook();
            if (!UpdateSitesRefresh.woven && !disabled)
                BslDocCommentDescriptionFix.registerExtraTransformer(new ClassFileTransformer()
                {
                    @Override
                    public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                        ProtectionDomain domain, byte[] bytes)
                    {
                        if (disabled)
                            return null;
                        try { return new UpdateSitesRefresh().transform(loader, name, redefined, domain, bytes); }
                        catch (Throwable failure)
                        {
                            disable("fallback transformer", failure); //$NON-NLS-1$
                            return null;
                        }
                    }
                }, UpdateSitesRefresh.TARGET);
            Display.getDefault().asyncExec(() -> runSafely("install Show filter", () -> //$NON-NLS-1$
            {
                Display.getDefault().addFilter(SWT.Show,
                    event -> runSafely("Show", () -> onShow(event))); //$NON-NLS-1$
                Global.tempLog(LOG, "Show filter installed"); //$NON-NLS-1$
            }));
        });
    }

    private static void onShow(Event event)
    {
        if (!(event.widget instanceof Shell shell) || shell.isDisposed()
            || !(shell.getData() instanceof WizardDialog dialog))
            return;
        IWizardPage currentPage = dialog.getCurrentPage();
        Global.tempLog(LOG, "wizard shown: dialog=" + dialog.getClass().getName() //$NON-NLS-1$
            + ", currentPage=" + (currentPage == null ? null : currentPage.getClass().getName())); //$NON-NLS-1$
        if (currentPage == null || currentPage.getWizard() == null)
            return;
        // WizardDialog.getWizard() защищён; публичный доступ к мастеру предоставляет страница.
        for (IWizardPage page : currentPage.getWizard().getPages())
        {
            if (!PAGE_CLASS.equals(page.getClass().getName()))
                continue;
            Object selector = Global.getField(page, "repoSelector"); //$NON-NLS-1$
            Object control = Global.call(selector, "getDefaultFocusControl"); //$NON-NLS-1$
            if (!(control instanceof Combo combo) || combo.isDisposed())
            {
                Global.tempLog(LOG, "attach failed: selector=" + selector + ", control=" + control); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            if (combo.getData(SESSION_KEY) != null)
                continue;
            Session session = new Session(shell, page, selector, combo);
            combo.setData(SESSION_KEY, session);
            combo.addListener(SWT.Selection, e -> runSafely("Selection", session::selected)); //$NON-NLS-1$
            combo.addListener(SWT.DefaultSelection, e -> runSafely("DefaultSelection", session::selected)); //$NON-NLS-1$
            Global.tempLog(LOG, "attached: page=" + page.getClass().getName()); //$NON-NLS-1$
        }
    }

    private static final class Session
    {
        private final Shell shell;
        private final IWizardPage page;
        private final Object selector;
        private final Combo combo;
        private int generation;

        Session(Shell shell, IWizardPage page, Object selector, Combo combo)
        {
            this.shell = shell;
            this.page = page;
            this.selector = selector;
            this.combo = combo;
        }

        void selected()
        {
            int request = ++generation;
            Global.tempLog(LOG, "selection: request=" + request + ", text=" + combo.getText()); //$NON-NLS-1$ //$NON-NLS-2$
            // После штатных слушателей: они применяют фильтр сайта. Объединяем Selection/DefaultSelection.
            combo.getDisplay().asyncExec(() -> runSafely("refresh selected site", () -> //$NON-NLS-1$
            {
                if (shell.isDisposed() || combo.isDisposed() || request != generation)
                    return;
                // Пункты «Все сайты»/«Локальные сайты» не выбирают единственное расположение.
                Object locations = Global.getField(selector, "comboRepos"); //$NON-NLS-1$
                int index = combo.getSelectionIndex();
                if (!(locations instanceof URI[] sites) || index < 0 || index >= sites.length
                    || sites[index] == null)
                {
                    Global.tempLog(LOG, "skip: index=" + index + ", locations=" + locations); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                Global.tempLog(LOG, "selected location: index=" + index + ", uri=" + sites[index]); //$NON-NLS-1$ //$NON-NLS-2$
                refresh(sites[index]);
            }));
        }

        void refresh(URI location)
        {
            Global.tempLog(LOG, "refresh start: " + location); //$NON-NLS-1$
            try
            {
                Object ui = Global.getField(selector, "ui"); //$NON-NLS-1$
                Object session = Global.call(ui, "getSession"); //$NON-NLS-1$
                Class<?> provUI = Platform.getBundle("org.eclipse.equinox.p2.ui") //$NON-NLS-1$
                    .loadClass("org.eclipse.equinox.internal.p2.ui.ProvUI"); //$NON-NLS-1$
                Object metadata = Global.invoke(provUI, "getMetadataRepositoryManager", session); //$NON-NLS-1$
                Object artifacts = Global.invoke(provUI, "getArtifactRepositoryManager", session); //$NON-NLS-1$
                Object tracker = Global.call(ui, "getRepositoryTracker"); //$NON-NLS-1$
                new ProgressMonitorDialog(shell).run(true, true, monitor ->
                {
                    try
                    {
                        SubMonitor progress = SubMonitor.convert(monitor, "Обновление сайта " + location, 200);
                        Global.invokeVoid(tracker, "clearRepositoryNotFound", location); //$NON-NLS-1$
                        refreshRepository(metadata, location, progress.newChild(100));
                        try
                        {
                            refreshRepository(artifacts, location, progress.newChild(100));
                        }
                        catch (OperationCanceledException e) { throw e; }
                        catch (RuntimeException e)
                        {
                            // Как у штатной кнопки: отсутствие отдельного artifact-репозитория допустимо.
                            Global.tempLog(LOG, "artifact refresh failed: " + e); //$NON-NLS-1$
                        }
                        if (monitor.isCanceled())
                            throw new OperationCanceledException();
                    }
                    catch (OperationCanceledException e) { throw new InterruptedException(); }
                    catch (Throwable e) { throw new InvocationTargetException(e); }
                });
                // В мастере p2 события пакетируются до закрытия окна: явно перечитать вход дерева.
                if (!combo.isDisposed())
                {
                    Object group = Global.getField(page, "availableIUGroup"); //$NON-NLS-1$
                    boolean updated = Global.invokeVoid(group, "updateAvailableViewState"); //$NON-NLS-1$
                    Global.tempLog(LOG, "refresh complete: " + location + ", viewerUpdated=" + updated); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            catch (InterruptedException e)
            {
                Global.tempLog(LOG, "refresh canceled: " + location); //$NON-NLS-1$
            }
            catch (Throwable e)
            {
                disable("refresh failed: " + location, e); //$NON-NLS-1$
            }
        }
    }

    private static void refreshRepository(Object manager, URI location, IProgressMonitor monitor)
    {
        Global.tempLog(LOG, "refreshRepository: manager=" + manager + ", location=" + location); //$NON-NLS-1$ //$NON-NLS-2$
        Object repository = Global.invoke(manager, "refreshRepository", location, monitor); //$NON-NLS-1$
        if (repository == null)
            throw new IllegalStateException("Обновление сайта не вернуло результат: " + location);
        Global.tempLog(LOG, "repository loaded: " + repository); //$NON-NLS-1$
    }

    /**
     * UpdateWizard получает уже рассчитанные версии. Обновляем сайты перед первым поиском
     * UpdateOperation.computeProfileChangeRequest (p2.operations 2.7.200.v20231105-1139),
     * до заполнения possibleUpdatesByIU. Повторный расчёт плана после выбора версий сеть не трогает.
     * Мост через BiConsumer и fallback-трансформер — общий паттерн хуков репозитория.
     */
    private static final class UpdateSitesRefresh implements WeavingHook, ClassFileTransformer
    {
        private static final String TARGET = "org.eclipse.equinox.p2.operations.UpdateOperation"; //$NON-NLS-1$
        private static final String CALLBACK = "tormozit.availableSoftware.beforeUpdateSearch"; //$NON-NLS-1$
        private static final String DESCRIPTOR =
            "(Lorg/eclipse/core/runtime/MultiStatus;Lorg/eclipse/core/runtime/IProgressMonitor;)V"; //$NON-NLS-1$
        private static final AtomicBoolean installed = new AtomicBoolean();
        private static final Set<Object> refreshed = Collections.newSetFromMap(new WeakHashMap<>());
        private static volatile boolean woven;

        static void install()
        {
            if (!installed.compareAndSet(false, true))
                return;
            System.getProperties().put(CALLBACK, (BiConsumer<Object, Object>)(operation, monitor) ->
                runSafely("before update search", () -> beforeSearch(operation, monitor))); //$NON-NLS-1$
            FrameworkUtil.getBundle(AvailableSoftwareDialogHook.class).getBundleContext()
                .registerService(WeavingHook.class,
                    target -> runSafely("weaving callback", () -> new UpdateSitesRefresh().weave(target)), null); //$NON-NLS-1$
            Global.tempLog(LOG, "update search weaving hook installed"); //$NON-NLS-1$
        }

        private static void beforeSearch(Object operation, Object progress)
        {
            Global.tempLog(LOG, "before update search: operation=" + System.identityHashCode(operation)); //$NON-NLS-1$
            synchronized (refreshed)
            {
                if (refreshed.contains(operation))
                {
                    Global.tempLog(LOG, "update sites already refreshed for this operation"); //$NON-NLS-1$
                    return;
                }
            }
            try
            {
                IProgressMonitor monitor = (IProgressMonitor)progress;
                Object session = Global.getField(operation, "session"); //$NON-NLS-1$
                Object context = Global.call(operation, "getProvisioningContext"); //$NON-NLS-1$
                Class<?> uiClass = Platform.getBundle("org.eclipse.equinox.p2.ui") //$NON-NLS-1$
                    .loadClass("org.eclipse.equinox.p2.ui.ProvisioningUI"); //$NON-NLS-1$
                Object ui = Global.invoke(uiClass, "getDefaultUI"); //$NON-NLS-1$
                Object tracker = Global.call(ui, "getRepositoryTracker"); //$NON-NLS-1$
                Object specified = Global.getField(context, "metadataRepositories"); //$NON-NLS-1$
                Object locations = specified instanceof URI[] ? specified
                    : Global.invoke(tracker, "getKnownRepositories", session); //$NON-NLS-1$
                if (!(locations instanceof URI[] sites))
                    throw new IllegalStateException("Не удалось получить сайты обновлений");
                Class<?> provUI = Platform.getBundle("org.eclipse.equinox.p2.ui") //$NON-NLS-1$
                    .loadClass("org.eclipse.equinox.internal.p2.ui.ProvUI"); //$NON-NLS-1$
                Object metadata = Global.invoke(provUI, "getMetadataRepositoryManager", session); //$NON-NLS-1$
                Object artifacts = Global.invoke(provUI, "getArtifactRepositoryManager", session); //$NON-NLS-1$
                Global.tempLog(LOG, "update sites: " + java.util.Arrays.toString(sites)); //$NON-NLS-1$
                for (URI site : sites)
                {
                    if (monitor.isCanceled())
                        throw new OperationCanceledException();
                    monitor.subTask("Обновление сайта " + site);
                    Global.invokeVoid(tracker, "clearRepositoryNotFound", site); //$NON-NLS-1$
                    for (Object manager : new Object[] { metadata, artifacts })
                    {
                        try
                        {
                            // Доли штатного monitor оставляем поиску версий; отмена доступна и при refresh.
                            refreshRepository(manager, site,
                                new org.eclipse.core.runtime.ProgressMonitorWrapper(monitor)
                                {
                                    @Override public void beginTask(String name, int totalWork) { subTask(name); }
                                    @Override public void worked(int work) { }
                                    @Override public void internalWorked(double work) { }
                                    @Override public void done() { }
                                });
                        }
                        catch (OperationCanceledException e) { throw e; }
                        catch (RuntimeException e)
                        {
                            // Штатный поиск затем сообщает о недоступных сайтах своим механизмом.
                            Global.tempLogException(LOG, "update refresh failed: " + site, e); //$NON-NLS-1$
                        }
                    }
                }
                if (monitor.isCanceled())
                    throw new OperationCanceledException();
                synchronized (refreshed)
                {
                    refreshed.add(operation);
                }
                Global.tempLog(LOG, "update sites refreshed; starting native update search"); //$NON-NLS-1$
            }
            catch (OperationCanceledException e)
            {
                Global.tempLog(LOG, "update sites refresh canceled"); //$NON-NLS-1$
                throw e;
            }
            catch (Throwable e)
            {
                disable("before update search failed", e); //$NON-NLS-1$
            }
        }

        private static byte[] patch(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            AtomicBoolean patched = new AtomicBoolean();
            AtomicBoolean alreadyPatched = new AtomicBoolean();
            reader.accept(new ClassVisitor(Opcodes.ASM9)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        @Override public void visitLdcInsn(Object value)
                        {
                            if (CALLBACK.equals(value))
                                alreadyPatched.set(true);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (alreadyPatched.get())
                return null;
            // Защита живёт В САМОМ классе p2: ловит также Error/LinkageError при входе в наш метод,
            // отсутствие моста и неверный тип callback. В обработчике нет вызовов кода плагина.
            // Новые кадры сохраняют исходные локальные переменные; исходные кадры не пересчитываем.
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"computeProfileChangeRequest".equals(name) || !DESCRIPTOR.equals(descriptor)) //$NON-NLS-1$
                        return mv;
                    patched.set(true);
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override public void visitCode()
                        {
                            super.visitCode();
                            Label start = new Label();
                            Label end = new Label();
                            Label failed = new Label();
                            Label nativeCode = new Label();
                            super.visitTryCatchBlock(start, end, failed, "java/lang/Throwable"); //$NON-NLS-1$
                            super.visitLabel(start);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                "()Ljava/util/Properties;", false); //$NON-NLS-1$
                            super.visitLdcInsn(CALLBACK);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiConsumer"); //$NON-NLS-1$
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 2);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiConsumer", "accept", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;Ljava/lang/Object;)V", true); //$NON-NLS-1$
                            super.visitLabel(end);
                            super.visitJumpInsn(Opcodes.GOTO, nativeCode);
                            super.visitLabel(failed);
                            super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] { "java/lang/Throwable" }); //$NON-NLS-1$
                            super.visitInsn(Opcodes.POP);
                            super.visitLabel(nativeCode);
                            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        }
                    };
                }
            }, 0);
            Global.tempLog(LOG, "patch UpdateOperation: methodFound=" + patched.get()); //$NON-NLS-1$
            return patched.get() ? writer.toByteArray() : null;
        }

        @Override
        public void weave(WovenClass target)
        {
            try
            {
                if (disabled || target.getState() != WovenClass.TRANSFORMING || !TARGET.equals(target.getClassName()))
                    return;
                byte[] bytes = patch(target.getBytes());
                if (bytes != null)
                {
                    target.setBytes(bytes);
                    woven = true;
                }
            }
            catch (Throwable e) { disable("weave UpdateOperation", e); } //$NON-NLS-1$
        }

        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
            ProtectionDomain domain, byte[] bytes)
        {
            try
            {
                if (disabled || !TARGET.replace('.', '/').equals(name))
                    return null;
                return patch(bytes);
            }
            catch (Throwable e)
            {
                disable("transform UpdateOperation", e); //$NON-NLS-1$
                return null;
            }
        }
    }
}
