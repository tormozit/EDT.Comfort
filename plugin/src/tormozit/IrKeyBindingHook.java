package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.BindingManagerEvent;
import org.eclipse.jface.bindings.IBindingManagerListener;
import org.eclipse.jface.bindings.TriggerSequence;
import org.eclipse.jface.bindings.keys.KeyBinding;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.internal.keys.BindingService;
import org.eclipse.ui.keys.IBindingService;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Снимает привязки команд ИР, когда для активного BSL-редактора нет подключённой
 * сессии ИР, и возвращает их при подключении — чтобы штатные сочетания EDT снова работали.
 */
public final class IrKeyBindingHook implements org.eclipse.ui.IStartup
{
    private static final String TAG = "IrKeyBinding"; //$NON-NLS-1$

    /** Задержка перед пересборкой после серии событий BindingManager. */
    private static final int SYNC_DEBOUNCE_MS = 150;

    /** Команды ИР с привязками в {@code plugin.xml}. */
    private static final String[] IR_COMMAND_IDS = {
        IrMethodConstructorHandler.COMMAND_ID,
        EditEmbeddedTextCommandHandler.COMMAND_ID,
        IrFormatTextCommandHandler.COMMAND_ID
    };

    private static final Set<String> IR_COMMAND_ID_SET =
            new HashSet<>(Arrays.asList(IR_COMMAND_IDS));

    private static final List<IrBindingDescriptor> suppressedDescriptors = new ArrayList<>();

    /** Архив дескriptor'ов — для restore, если suppressedDescriptors уже очищен. */
    private static final List<IrBindingDescriptor> irBindingArchive = new ArrayList<>();

    private static BindingService bindingServiceInternal;
    private static ICommandService commandService;
    private static IBindingManagerListener bindingListener;
    private static boolean listenerInstalled;
    private static boolean windowsHookInstalled;

    /** Не реагировать на bindingManagerChanged от наших add/remove. */
    private static boolean applyingSuppress;

    private static Boolean lastPresent;
    private static String lastLoggedProject;
    private static Runnable pendingSyncTask;

    /**
     * Активны ли сейчас горячие клавиши команд ИР для активного BSL-редактора.
     */
    public static boolean isActive()
    {
        return shouldIrBindingsBePresent();
    }

    static boolean isApplyingSuppress()
    {
        return applyingSuppress;
    }

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            if (!installServices())
                return;
            installBindingListener();
            installWindowListeners();
            IRApplication.getInstance().addChangeListener(IrKeyBindingHook::onIrApplicationChanged);
            syncIrKeyBindings();
        });
    }

    private static void onIrApplicationChanged()
    {
        lastPresent = null;
        lastLoggedProject = null;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(IrKeyBindingHook::syncIrKeyBindings);
    }

    private static boolean installServices()
    {
        if (PlatformUI.getWorkbench() == null)
            return false;
        IBindingService bindingService =
                PlatformUI.getWorkbench().getService(IBindingService.class);
        commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (!(bindingService instanceof BindingService internal) || commandService == null)
            return false;
        bindingServiceInternal = internal;
        return true;
    }

    private static void installBindingListener()
    {
        if (listenerInstalled || bindingServiceInternal == null)
            return;
        bindingListener = IrKeyBindingHook::onBindingManagerChanged;
        bindingServiceInternal.addBindingManagerListener(bindingListener);
        listenerInstalled = true;
    }

    private static void installWindowListeners()
    {
        if (windowsHookInstalled)
            return;

        PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override public void windowActivated(IWorkbenchWindow window) {}
            @Override public void windowDeactivated(IWorkbenchWindow window) {}
            @Override public void windowClosed(IWorkbenchWindow window) {}
        });

        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            hookWindow(window);

        windowsHookInstalled = true;
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                scheduleSyncIrKeyBindings();
            }

            @Override
            public void partBroughtToTop(IWorkbenchPartReference ref)
            {
                scheduleSyncIrKeyBindings();
            }

            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                scheduleSyncIrKeyBindings();
            }

            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partVisible(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) {}
        });
    }

    private static void onBindingManagerChanged(BindingManagerEvent event)
    {
        if (applyingSuppress || PriorityGlobalKeyBindingHook.isApplyingOverrides())
            return;
        scheduleSyncIrKeyBindings();
    }

    private static void scheduleSyncIrKeyBindings()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (pendingSyncTask != null)
            display.timerExec(-1, pendingSyncTask);
        pendingSyncTask = () ->
        {
            pendingSyncTask = null;
            syncIrKeyBindings();
        };
        display.timerExec(SYNC_DEBOUNCE_MS, pendingSyncTask);
    }

    /** Полная пересборка: suppress/restore IR-привязок и зеркал FormatAction. */
    static synchronized void syncIrKeyBindings()
    {
        if (bindingServiceInternal == null || commandService == null)
        {
            if (!installServices())
                return;
            installBindingListener();
        }

        boolean present = shouldIrBindingsBePresent();
        int restoreCount = suppressedDescriptors.size();

        applyingSuppress = true;
        try
        {
            if (present)
                restoreSuppressedIrBindings();
            else
                suppressAllIrBindings();
        }
        finally
        {
            applyingSuppress = false;
        }

        PriorityGlobalKeyBindingHook.syncOverrides();

        IDtProject dtProject = resolveActiveDtProject();
        IProject project = dtProject != null ? dtProject.getWorkspaceProject() : null;
        String projectName = project != null ? project.getName() : "—"; //$NON-NLS-1$
        boolean presentChanged = !Boolean.valueOf(present).equals(lastPresent);
        boolean projectChanged = !projectName.equals(lastLoggedProject);
        if (presentChanged || projectChanged)
        {
            IrKeyBindingDebug.step("sync", //$NON-NLS-1$
                    "present=" + present //$NON-NLS-1$
                        + " project=" + projectName //$NON-NLS-1$
                        + " suppressed=" + suppressedDescriptors.size() //$NON-NLS-1$
                        + " archive=" + irBindingArchive.size() //$NON-NLS-1$
                        + (present
                            ? " restored=" + restoreCount //$NON-NLS-1$
                                + " activeBindings=" + countActiveIrBindings() //$NON-NLS-1$
                            : "")); //$NON-NLS-1$
            lastPresent = present;
            lastLoggedProject = projectName;
            if (present)
                IrKeyBindingDebug.logIrMirrorSummary();
        }
    }

    private static boolean shouldIrBindingsBePresent()
    {
        IDtProject dtProject = resolveActiveDtProject();
        if (dtProject != null)
            return IRApplication.hasConnectedSessionForKeys(dtProject);
        return IRApplication.hasAnyConnectedSessionForKeys();
    }

    private static void suppressAllIrBindings()
    {
        if (bindingServiceInternal == null)
            return;

        Set<String> suppressedSigs = descriptorSignatures(suppressedDescriptors);
        Set<String> archiveSigs = descriptorSignatures(irBindingArchive);
        for (Binding binding : bindingServiceInternal.getBindings())
        {
            if (!isIrCommandBinding(binding))
                continue;
            IrBindingDescriptor descriptor = IrBindingDescriptor.from(binding);
            String sig = descriptor.signature();
            if (suppressedSigs.contains(sig))
                continue;

            bindingServiceInternal.removeBinding(binding);
            suppressedDescriptors.add(descriptor);
            suppressedSigs.add(sig);
            if (!archiveSigs.contains(sig))
            {
                irBindingArchive.add(descriptor);
                archiveSigs.add(sig);
            }

            if (Global.isLogEnabled())
            {
                Global.log(TAG, "suppressed IR binding ctx=" + descriptor.contextId //$NON-NLS-1$
                        + " cmd=" + descriptor.commandId //$NON-NLS-1$
                        + " seq=" + descriptor.sequenceFormat); //$NON-NLS-1$
            }
        }
    }

    private static void restoreSuppressedIrBindings()
    {
        if (bindingServiceInternal == null || commandService == null)
            return;

        List<IrBindingDescriptor> source = !suppressedDescriptors.isEmpty()
                ? new ArrayList<>(suppressedDescriptors)
                : new ArrayList<>(irBindingArchive);
        if (source.isEmpty())
            return;

        int restored = 0;
        Set<String> activeSigs = activeIrBindingSignatures();
        for (IrBindingDescriptor descriptor : source)
        {
            String sig = descriptor.signature();
            if (activeSigs.contains(sig))
                continue;
            KeyBinding binding = descriptor.createBinding(commandService);
            if (binding == null)
                continue;
            bindingServiceInternal.addBinding(binding);
            activeSigs.add(sig);
            restored++;
        }
        suppressedDescriptors.clear();

        if (Global.isLogEnabled() && restored > 0)
            Global.log(TAG, "restored IR bindings: " + restored); //$NON-NLS-1$
    }

    private static int countActiveIrBindings()
    {
        return activeIrBindingSignatures().size();
    }

    private static Set<String> activeIrBindingSignatures()
    {
        Set<String> sigs = new HashSet<>();
        if (bindingServiceInternal == null)
            return sigs;
        for (Binding binding : bindingServiceInternal.getBindings())
        {
            if (isIrCommandBinding(binding))
                sigs.add(IrBindingDescriptor.from(binding).signature());
        }
        return sigs;
    }

    private static IDtProject resolveActiveDtProject()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return null;
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return null;

        IDtProject fromActive = resolveDtProjectFromEditorPart(page.getActiveEditor());
        if (fromActive != null)
            return fromActive;

        IDtProject fallback = null;
        for (IEditorReference ref : page.getEditorReferences())
        {
            IEditorPart editor = ref.getEditor(false);
            if (editor == null)
                continue;
            IDtProject dtProject = resolveDtProjectFromEditorPart(editor);
            if (dtProject == null)
                continue;
            if (IRApplication.hasConnectedSessionForKeys(dtProject))
                return dtProject;
            if (fallback == null)
                fallback = dtProject;
        }
        return fallback;
    }

    private static IDtProject resolveDtProjectFromEditorPart(IEditorPart editor)
    {
        if (editor == null)
            return null;

        BslXtextEditor bslEditor = GetRef.getActiveBslEditor(editor);
        if (bslEditor == null && editor instanceof BslXtextEditor)
            bslEditor = (BslXtextEditor) editor;
        if (bslEditor == null)
            return null;

        return Global.getDtProjectFromBslEditor(bslEditor);
    }

    private static boolean isIrCommandBinding(Binding binding)
    {
        if (binding == null)
            return false;
        ParameterizedCommand pc = binding.getParameterizedCommand();
        return pc != null && IR_COMMAND_ID_SET.contains(pc.getId());
    }

    private static Set<String> descriptorSignatures(List<IrBindingDescriptor> descriptors)
    {
        Set<String> sigs = new HashSet<>();
        for (IrBindingDescriptor descriptor : descriptors)
            sigs.add(descriptor.signature());
        return sigs;
    }

    private static final class IrBindingDescriptor
    {
        final String commandId;
        final String schemeId;
        final String contextId;
        final String sequenceFormat;
        final int bindingType;

        private IrBindingDescriptor(
                String commandId,
                String schemeId,
                String contextId,
                String sequenceFormat,
                int bindingType)
        {
            this.commandId = commandId;
            this.schemeId = schemeId;
            this.contextId = contextId;
            this.sequenceFormat = sequenceFormat;
            this.bindingType = bindingType;
        }

        static IrBindingDescriptor from(Binding binding)
        {
            ParameterizedCommand pc = binding.getParameterizedCommand();
            String cmdId = pc != null ? pc.getId() : ""; //$NON-NLS-1$
            TriggerSequence trigger = binding.getTriggerSequence();
            String sequence = trigger != null ? trigger.format() : ""; //$NON-NLS-1$
            return new IrBindingDescriptor(
                    cmdId,
                    binding.getSchemeId(),
                    binding.getContextId(),
                    sequence,
                    binding.getType());
        }

        String signature()
        {
            return commandId + '|' + schemeId + '|' + contextId + '|' + sequenceFormat;
        }

        KeyBinding createBinding(ICommandService commands)
        {
            if (commands == null || commandId == null || commandId.isEmpty())
                return null;
            Command command = commands.getCommand(commandId);
            if (command == null || !command.isDefined())
                return null;

            ParameterizedCommand parameterized;
            try
            {
                parameterized = ParameterizedCommand.generateCommand(command, null);
            }
            catch (Exception e)
            {
                Global.logError(TAG, "generateCommand: " + commandId, e); //$NON-NLS-1$
                return null;
            }

            KeySequence sequence;
            try
            {
                sequence = KeySequence.getInstance(sequenceFormat);
            }
            catch (Exception e)
            {
                Global.logError(TAG, "parse sequence: " + sequenceFormat, e); //$NON-NLS-1$
                return null;
            }

            return new KeyBinding(
                    sequence,
                    parameterized,
                    schemeId,
                    contextId,
                    null,
                    null,
                    null,
                    bindingType);
        }
    }
}
