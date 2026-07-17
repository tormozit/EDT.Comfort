package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.BindingManagerEvent;
import org.eclipse.jface.bindings.IBindingManagerListener;
import org.eclipse.jface.bindings.Scheme;
import org.eclipse.jface.bindings.TriggerSequence;
import org.eclipse.jface.bindings.keys.KeyBinding;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.internal.keys.BindingService;
import org.eclipse.ui.keys.IBindingService;

/**
 * Поддерживает приоритет глобальных горячих клавиш {@link #GLOBAL_COMMAND_IDS}
 * над локальными SYSTEM-привязками EDT (сейчас — редактор форм, {@code formEditor}).
 *
 * <p>Для каждого сочетания, которое пользователь назначил команде в контексте
 * «В окне», добавляется runtime {@link Binding#USER}-привязка в локальных контекстах.
 * При переназначении в Keys override пересобирается автоматически.
 *
 * <p>Привязки добавляются через {@link BindingService#addBinding} (E4 + Keys),
 * а не только через {@link org.eclipse.jface.bindings.BindingManager#addBinding}.
 */
public class PriorityGlobalKeyBindingHook implements IStartup
{
    private static final String TAG = "PriorityGlobalKeyBinding"; //$NON-NLS-1$

    /** Задержка перед пересборкой после серии событий BindingManager. */
    private static final int SYNC_DEBOUNCE_MS = 150;

    /** Глобальные команды с приоритетной привязкой. */
    private static final String[] GLOBAL_COMMAND_IDS = {
        "tormozit.GoToDefinition", //$NON-NLS-1$
        "tormozit.CopyRef"         //$NON-NLS-1$
    };

    private static final Set<String> GLOBAL_COMMAND_ID_SET =
            new HashSet<>(Arrays.asList(GLOBAL_COMMAND_IDS));

    /** Фиксированное сочетание каждой команды из {@link #GLOBAL_COMMAND_IDS} (как в {@code plugin.xml}). */
    private static final java.util.Map<String, String> GLOBAL_COMMAND_DEFAULT_SEQUENCES =
            java.util.Map.of(
                "tormozit.GoToDefinition", "M1+F12", //$NON-NLS-1$ //$NON-NLS-2$
                "tormozit.CopyRef", "M1+F11"); //$NON-NLS-1$ //$NON-NLS-2$


    /**
     * Команды ИР → USER в embedded/formEditor (XtextEditorScope — restore из IrKeyBindingHook);
     * SYSTEM FormatAction в Xtext-контекстах снимается (E4 не отдаёт приоритет USER над designer.scheme).
     * Источник сочетаний — effective USER в XtextEditorScope; SYSTEM из plugin.xml не зеркалируется,
     * если пользователь уже переназначил команду в Keys.
     */
    private static final String[] IR_MIRROR_COMMAND_IDS = {
        IrMethodConstructorHandler.COMMAND_ID,
        EditEmbeddedTextCommandHandler.COMMAND_ID,
        IrFormatTextCommandHandler.COMMAND_ID,
        IrModuleCheckHandler.COMMAND_ID
    };

    private static final String XTEXT_EDITOR_CONTEXT_ID =
            "org.eclipse.xtext.ui.XtextEditorScope"; //$NON-NLS-1$

    private static final String XTEXT_EMBEDDED_EDITOR_CONTEXT_ID =
            "org.eclipse.xtext.ui.embeddedTextEditorScope"; //$NON-NLS-1$

    private static final String GLOBAL_CONTEXT_ID = "org.eclipse.ui.contexts.window"; //$NON-NLS-1$

    private static final String FORM_EDITOR_CONTEXT_ID =
            "com._1c.g5.v8.dt.form.ui.formEditor"; //$NON-NLS-1$

    private static final String ORDINARY_FORM_EDITOR_CONTEXT_ID =
            "com._1c.g5.v8.dt.form.ui.ordinaryFormEditor"; //$NON-NLS-1$

    /** Локальные контексты EDT, где SYSTEM-привязки перебивают window. */
    private static final String[] LOCAL_OVERRIDE_CONTEXT_IDS = {
        FORM_EDITOR_CONTEXT_ID,
        ORDINARY_FORM_EDITOR_CONTEXT_ID
    };

    /** Контексты USER-зеркала команд ИР при подключённой сессии (без Xtext/window — там restore). */
    private static final String[] IR_MIRROR_CONTEXT_IDS = {
        XTEXT_EMBEDDED_EDITOR_CONTEXT_ID,
        FORM_EDITOR_CONTEXT_ID,
        ORDINARY_FORM_EDITOR_CONTEXT_ID
    };

    /** Базовая схема Comfort в plugin.xml; родитель «Конфигуратор 1C». */
    private static final String DEFAULT_SCHEME_ID =
            "org.eclipse.ui.defaultAcceleratorConfiguration"; //$NON-NLS-1$

    private static final Set<String> LOCAL_CONTEXTS =
            new HashSet<>(Arrays.asList(LOCAL_OVERRIDE_CONTEXT_IDS));

    private static final String XTEXT_FORMAT_ACTION_ID =
            "org.eclipse.xtext.ui.FormatAction"; //$NON-NLS-1$

    /** Контексты, где снимаем/восстанавливаем SYSTEM FormatAction (E4 не отдаёт приоритет USER). */
    private static final String[] FORMAT_ACTION_SUPPRESSION_CONTEXT_IDS = {
        XTEXT_EDITOR_CONTEXT_ID,
        XTEXT_EMBEDDED_EDITOR_CONTEXT_ID,
        FORM_EDITOR_CONTEXT_ID,
        ORDINARY_FORM_EDITOR_CONTEXT_ID
    };

    /** Схема 1С designer: штатный «Формат» = Alt+Shift+F (com._1c.g5.v8.designer). */
    private static final String DESIGNER_SCHEME_ID = "com._1c.g5.v8.designer.scheme"; //$NON-NLS-1$

    /** Formal M2+M3+F — designer FormatAction в Xtext/embedded. */
    private static final String FORMAT_ACTION_DESIGNER_SEQUENCE = "M2+M3+F"; //$NON-NLS-1$

    private static final List<KeyBinding> appliedOverrides = new ArrayList<>();

    /** SYSTEM-привязки, временно снятые ради IrFormatText (восстанавливаются при resync). */
    private static final List<Binding> suppressedCompetingBindings = new ArrayList<>();

    private static BindingService bindingServiceInternal;
    private static ICommandService commandService;
    private static IBindingManagerListener bindingListener;
    private static boolean listenerInstalled;

    /** Не реагировать на bindingManagerChanged от наших add/remove. */
    private static boolean applyingOverrides;

    private static Runnable pendingSyncTask;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            if (!installServices())
                return;
            ensurePersistedGlobalOverrides(); // до installBindingListener() — без гонки с диалогом «Клавиши»
            installBindingListener();
        });
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
        bindingListener = PriorityGlobalKeyBindingHook::onBindingManagerChanged;
        bindingServiceInternal.addBindingManagerListener(bindingListener);
        listenerInstalled = true;
    }

    private static void onBindingManagerChanged(BindingManagerEvent event)
    {
        if (commandService != null)
            commandService.refreshElements(GetRef.COMMAND_ID, null);
        if (applyingOverrides || IrKeyBindingHook.isApplyingSuppress())
            return;
        scheduleSyncOverrides();
    }

    private static void scheduleSyncOverrides()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (pendingSyncTask != null)
            display.timerExec(-1, pendingSyncTask);
        pendingSyncTask = () ->
        {
            pendingSyncTask = null;
            syncOverrides();
        };
        display.timerExec(SYNC_DEBOUNCE_MS, pendingSyncTask);
    }

    /** Полная пересборка runtime USER-override по текущим глобальным привязкам. */
    static synchronized void syncOverrides()
    {
        if (bindingServiceInternal == null || commandService == null)
        {
            if (!installServices())
                return;
        }

        List<KeyBinding> desired = buildDesiredOverrides();
        boolean irActive = IrKeyBindingHook.isActive();
        Set<KeySequence> irFormatSequences = irActive
                ? collectAllIrFormatTextSequences()
                : Set.of();
        boolean overridesNeedUpdate = !syncStateUpToDate(desired, irFormatSequences);

        applyingOverrides = true;
        try
        {
            if (!irActive)
                ensureFormatActionDefaults(Set.of());

            Set<KeySequence> globalSequences = collectAllGlobalCommandSequences();
            tempDumpGlobalCommandBindings(globalSequences);
            // реактивно — переустановит persisted USER-оверрайд, если «Восстановить команду» её сняла;
            // readRegistryAndPreferences() внутри пересобирает живую модель и сбрасывает наши
            // непersist-нутые LOCAL_OVERRIDE_CONTEXT_IDS-привязки — форсируем их пересборку ниже.
            if (ensurePersistedGlobalOverrides())
                overridesNeedUpdate = true;

            if (overridesNeedUpdate)
            {
                restoreSuppressedBindings();
                removeAppliedOverrides();
            }

            if (irActive && !irFormatSequences.isEmpty())
                reconcileFormatActionSuppression(irFormatSequences);

            if (!overridesNeedUpdate)
                return;

            String schemeId = activeSchemeId();
            for (KeyBinding binding : desired)
            {
                bindingServiceInternal.addBinding(binding);
                appliedOverrides.add(binding);
            }
            if (Global.isLogEnabled())
            {
                Global.log(TAG, "applied overrides: " + appliedOverrides.size() //$NON-NLS-1$
                        + ", scheme=" + schemeId //$NON-NLS-1$
                        + ", suppressed=" + suppressedCompetingBindings.size()); //$NON-NLS-1$
                logIrMirrorSummary(desired);
            }
        }
        finally
        {
            applyingOverrides = false;
        }
    }

    private static List<KeyBinding> buildDesiredOverrides()
    {
        List<KeyBinding> result = new ArrayList<>();
        String schemeId = activeSchemeId();
        for (String commandId : GLOBAL_COMMAND_IDS)
        {
            Command command = commandService.getCommand(commandId);
            if (command == null || !command.isDefined())
                continue;

            ParameterizedCommand parameterized;
            try
            {
                parameterized = ParameterizedCommand.generateCommand(command, null);
            }
            catch (Exception e)
            {
                Global.logError(TAG, "generateCommand: " + commandId, e); //$NON-NLS-1$
                continue;
            }

            Set<KeySequence> sequences = collectGlobalKeySequences(commandId);
            for (KeySequence sequence : sequences)
            {
                for (String localContextId : LOCAL_OVERRIDE_CONTEXT_IDS)
                    result.add(createOverride(sequence, parameterized, schemeId, localContextId));
            }
        }

        if (IrKeyBindingHook.isActive())
        {
            for (String commandId : IR_MIRROR_COMMAND_IDS)
            {
                Command command = commandService.getCommand(commandId);
                if (command == null || !command.isDefined())
                    continue;

                ParameterizedCommand parameterized;
                try
                {
                    parameterized = ParameterizedCommand.generateCommand(command, null);
                }
                catch (Exception e)
                {
                    Global.logError(TAG, "generateCommand: " + commandId, e); //$NON-NLS-1$
                    continue;
                }

                Set<KeySequence> sequences = collectEffectiveContextKeySequences(
                        commandId, XTEXT_EDITOR_CONTEXT_ID);
                for (KeySequence sequence : sequences)
                {
                    for (String mirrorContextId : IR_MIRROR_CONTEXT_IDS)
                        result.add(createOverride(sequence, parameterized, schemeId, mirrorContextId));
                }
            }
        }
        return result;
    }

    static boolean isApplyingOverrides()
    {
        return applyingOverrides;
    }

    /**
     * Отформатированное сочетание клавиш команды из {@link #GLOBAL_COMMAND_IDS} (для тултипов).
     * В отличие от {@code IBindingService.getBestActiveBindingFormattedFor}, не возвращает
     * {@code null} при конфликте с чужой командой на том же сочетании (например, штатный
     * Eclipse «Run Last Launched» на Ctrl+F11).
     */
    static String formatShortcutFor(String commandId)
    {
        if (bindingServiceInternal == null && !installServices())
            return null;
        Set<KeySequence> sequences = collectGlobalKeySequences(commandId);
        return sequences.isEmpty() ? null : sequences.iterator().next().format();
    }

    private static boolean syncStateUpToDate(List<KeyBinding> desired, Set<KeySequence> irFormatSequences)
    {
        if (!overrideSignaturesEqual(appliedOverrides, desired))
            return false;
        Set<String> desiredSuppressions = computeRequiredSuppressionSignatures(irFormatSequences);
        return desiredSuppressions.equals(bindingSignatures(suppressedCompetingBindings));
    }

    /** Все effective-сочетания IrFormatText в менеджере привязок (SYSTEM + USER). */
    private static Set<KeySequence> collectAllIrFormatTextSequences()
    {
        Set<KeySequence> sequences = new LinkedHashSet<>();
        if (bindingServiceInternal == null)
            return sequences;

        String activeScheme = activeSchemeId();
        String platform = bindingServiceInternal.getPlatform();
        String locale = bindingServiceInternal.getLocale();

        for (Binding binding : bindingServiceInternal.getBindings())
        {
            if (binding == null)
                continue;
            ParameterizedCommand pc = binding.getParameterizedCommand();
            if (pc == null || !IrFormatTextCommandHandler.COMMAND_ID.equals(pc.getId()))
                continue;

            String schemeId = binding.getSchemeId();
            if (!activeScheme.equals(schemeId) && !DEFAULT_SCHEME_ID.equals(schemeId)
                    && !DESIGNER_SCHEME_ID.equals(schemeId))
                continue;
            if (!matchesPlatformLocale(binding, platform, locale))
                continue;

            TriggerSequence trigger = binding.getTriggerSequence();
            if (trigger instanceof KeySequence keySequence)
                sequences.add(keySequence);
        }
        return sequences;
    }

    private static void reconcileFormatActionSuppression(Set<KeySequence> sequences)
    {
        Set<String> required = computeRequiredSuppressionSignatures(sequences);
        if (required.equals(bindingSignatures(suppressedCompetingBindings)))
            return;

        restoreSuppressedBindings();
        suppressCompetingFormatActionBindings(sequences);
    }

    private static Set<String> computeRequiredSuppressionSignatures(Set<KeySequence> sequences)
    {
        Set<String> sigs = findCompetingFormatActionSignatures(sequences);
        if (bindingServiceInternal == null || sequences.isEmpty())
            return sigs;

        String activeScheme = activeSchemeId();
        String platform = bindingServiceInternal.getPlatform();
        String locale = bindingServiceInternal.getLocale();

        for (Binding binding : suppressedCompetingBindings)
        {
            if (isSuppressibleFormatActionBinding(binding, sequences, activeScheme, platform, locale))
                sigs.add(bindingSignature(binding));
        }
        return sigs;
    }

    private static Set<KeySequence> extractIrFormatTextSequences(List<KeyBinding> desired)
    {
        Set<KeySequence> sequences = new LinkedHashSet<>();
        for (KeyBinding binding : desired)
        {
            ParameterizedCommand pc = binding.getParameterizedCommand();
            if (pc == null || !"tormozit.IrFormatText".equals(pc.getId())) //$NON-NLS-1$
                continue;
            TriggerSequence trigger = binding.getTriggerSequence();
            if (trigger instanceof KeySequence keySequence)
                sequences.add(keySequence);
        }
        return sequences;
    }

    private static Set<String> findCompetingFormatActionSignatures(Set<KeySequence> sequences)
    {
        Set<String> sigs = new HashSet<>();
        if (bindingServiceInternal == null || sequences.isEmpty())
            return sigs;

        String activeScheme = activeSchemeId();
        String platform = bindingServiceInternal.getPlatform();
        String locale = bindingServiceInternal.getLocale();

        for (Binding binding : bindingServiceInternal.getBindings())
        {
            if (!isSuppressibleFormatActionBinding(binding, sequences, activeScheme, platform, locale))
                continue;
            sigs.add(bindingSignature(binding));
        }
        return sigs;
    }

    private static void suppressCompetingFormatActionBindings(Set<KeySequence> sequences)
    {
        if (bindingServiceInternal == null || sequences.isEmpty())
            return;

        String activeScheme = activeSchemeId();
        String platform = bindingServiceInternal.getPlatform();
        String locale = bindingServiceInternal.getLocale();

        Binding[] snapshot = bindingServiceInternal.getBindings();
        for (Binding binding : snapshot)
        {
            if (!isSuppressibleFormatActionBinding(binding, sequences, activeScheme, platform, locale))
                continue;

            bindingServiceInternal.removeBinding(binding);
            suppressedCompetingBindings.add(binding);

            if (Global.isLogEnabled())
            {
                Global.log(TAG, "suppressed FormatAction ctx=" + binding.getContextId() //$NON-NLS-1$
                        + " scheme=" + binding.getSchemeId() //$NON-NLS-1$
                        + " seq=" + binding.getTriggerSequence().format()); //$NON-NLS-1$
            }
        }
    }

    private static boolean isSuppressibleFormatActionBinding(
            Binding binding,
            Set<KeySequence> sequences,
            String activeScheme,
            String platform,
            String locale)
    {
        if (binding == null || binding.getType() != Binding.SYSTEM)
            return false;

        ParameterizedCommand pc = binding.getParameterizedCommand();
        if (pc == null || !XTEXT_FORMAT_ACTION_ID.equals(pc.getId()))
            return false;

        if (!isFormatActionSuppressionContext(binding.getContextId()))
            return false;

        String schemeId = binding.getSchemeId();
        if (!activeScheme.equals(schemeId) && !DEFAULT_SCHEME_ID.equals(schemeId)
                && !DESIGNER_SCHEME_ID.equals(schemeId))
            return false;

        if (!matchesPlatformLocale(binding, platform, locale))
            return false;

        TriggerSequence trigger = binding.getTriggerSequence();
        return trigger instanceof KeySequence keySequence && sequences.contains(keySequence);
    }

    private static boolean isFormatActionSuppressionContext(String contextId)
    {
        if (contextId == null)
            return false;
        for (String allowed : FORMAT_ACTION_SUPPRESSION_CONTEXT_IDS)
        {
            if (allowed.equals(contextId))
                return true;
        }
        return false;
    }

    private static void restoreSuppressedBindings()
    {
        if (bindingServiceInternal == null)
            return;
        for (Binding binding : suppressedCompetingBindings)
            bindingServiceInternal.addBinding(binding);
        suppressedCompetingBindings.clear();
    }

    // =========================================================================
    // Снятие чужих SYSTEM-привязок на сочетаниях GLOBAL_COMMAND_IDS в GLOBAL_CONTEXT_ID
    // =========================================================================

    /**
     * ВРЕМЕННАЯ диагностика (снять после подтверждения фикса): безусловный дамп ВСЕХ
     * привязок на сочетания {@link #GLOBAL_COMMAND_IDS} — без фильтров по схеме/контексту,
     * чтобы увидеть реальный (не предполагаемый) contextId/schemeId/type конкурирующих
     * команд, например {@code org.eclipse.debug.ui.commands.RunLast} на Ctrl+F11.
     * Пишет в {@code .tmp/temp-logs/PriorityGlobalKeyBinding.log} через {@link Global#tempLog}.
     */
    private static void tempDumpGlobalCommandBindings(Set<KeySequence> sequences)
    {
        if (bindingServiceInternal == null)
        {
            Global.tempLog(TAG, "dump: bindingServiceInternal=null"); //$NON-NLS-1$
            return;
        }
        Global.tempLog(TAG, "dump BEGIN activeScheme=" + activeSchemeId() //$NON-NLS-1$
                + " ourSequences=" + sequences); //$NON-NLS-1$
        for (Binding binding : bindingServiceInternal.getBindings())
        {
            if (binding == null)
                continue;
            TriggerSequence trigger = binding.getTriggerSequence();
            if (!(trigger instanceof KeySequence keySequence) || !sequences.contains(keySequence))
                continue;
            ParameterizedCommand pc = binding.getParameterizedCommand();
            Global.tempLog(TAG, "dump   cmd=" + (pc != null ? pc.getId() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                    + " ctx=" + binding.getContextId() //$NON-NLS-1$
                    + " scheme=" + binding.getSchemeId() //$NON-NLS-1$
                    + " type=" + (binding.getType() == Binding.USER ? "USER" : "SYSTEM") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " platform=" + binding.getPlatform() //$NON-NLS-1$
                    + " locale=" + binding.getLocale() //$NON-NLS-1$
                    + " seq=" + keySequence.format()); //$NON-NLS-1$
        }
        Global.tempLog(TAG, "dump END"); //$NON-NLS-1$
    }

    /** Все effective-сочетания {@link #GLOBAL_COMMAND_IDS} (объединённо, для поиска конфликтов). */
    private static Set<KeySequence> collectAllGlobalCommandSequences()
    {
        Set<KeySequence> result = new LinkedHashSet<>();
        for (String commandId : GLOBAL_COMMAND_IDS)
            result.addAll(collectGlobalKeySequences(commandId));
        return result;
    }

    /**
     * Сохраняет USER-привязку {@link #GLOBAL_COMMAND_IDS} в {@link #GLOBAL_CONTEXT_ID} через
     * {@link IBindingService#savePreferences} (+ tombstone на SYSTEM-схеме, см. ниже) и сразу
     * пересобирает живую модель через {@link IBindingService#readRegistryAndPreferences}.
     * Вызывается из {@link #earlyStartup()} (холодный старт) и реактивно из {@link #syncOverrides()}
     * (например, когда пользователь жмёт «Восстановить команду» и наша привязка пропадает —
     * тут же переустанавливается заново, а не только при следующем перезапуске EDT).
     *
     * <p>Идемпотентна (сверяет желаемое состояние с текущим и выходит без записи, если совпадает),
     * поэтому многократные вызовы безопасны. Ранние попытки вызывать {@code savePreferences} реактивно
     * БЕЗ пары {@code readRegistryAndPreferences} и БЕЗ tombstone ломали «Восстановить команду» для
     * всех команд Комфорта — с обоими элементами вместе (плюс единый идемпотентный путь что при
     * старте, что реактивно) 2026-07-17 подтверждено пользователем как рабочее, без регрессий.
     *
     * @return {@code true}, если реально записали и вызвали {@code readRegistryAndPreferences}
     *         (это сбрасывает и наши непersist-нутые эфемерные оверрайды {@link #LOCAL_OVERRIDE_CONTEXT_IDS} —
     *         вызывающий код обязан в этом случае форсировать их пересборку в этом же проходе).
     */
    private static boolean ensurePersistedGlobalOverrides()
    {
        if (bindingServiceInternal == null || commandService == null)
            return false;

        Scheme activeScheme = bindingServiceInternal.getActiveScheme();
        if (activeScheme == null)
            return false;
        String schemeId = activeScheme.getId();

        List<Binding> desiredUserBindings = new ArrayList<>();
        for (String commandId : GLOBAL_COMMAND_IDS)
        {
            Command command = commandService.getCommand(commandId);
            if (command == null || !command.isDefined())
                continue;
            ParameterizedCommand parameterized;
            try
            {
                parameterized = ParameterizedCommand.generateCommand(command, null);
            }
            catch (Exception e)
            {
                Global.logError(TAG, "generateCommand (persist): " + commandId, e); //$NON-NLS-1$
                continue;
            }
            String defaultSequenceFormal = GLOBAL_COMMAND_DEFAULT_SEQUENCES.get(commandId);
            if (defaultSequenceFormal == null)
                continue;
            KeySequence sequence;
            try
            {
                sequence = KeySequence.getInstance(defaultSequenceFormal);
            }
            catch (Exception e)
            {
                Global.logError(TAG, "parse default sequence: " + defaultSequenceFormal, e); //$NON-NLS-1$
                continue;
            }
            desiredUserBindings.add(createOverride(sequence, parameterized, schemeId, GLOBAL_CONTEXT_ID));
            // Tombstone (USER-привязка с пустой командой) на той же связке в родительской
            // схеме — гасит там SYSTEM-декларации (нашу же из plugin.xml и чужие, например
            // org.eclipse.debug.ui.commands.RunLast на Ctrl+F11), чтобы не было второй строки
            // в Keys и самого конфликта. Тот же приём, что и у ручного назначения в диалоге.
            desiredUserBindings.add(createOverride(sequence, null, DEFAULT_SCHEME_ID, GLOBAL_CONTEXT_ID));
        }
        if (desiredUserBindings.isEmpty())
            return false;

        Set<String> desiredTriggerKeys = new HashSet<>();
        for (Binding desired : desiredUserBindings)
            desiredTriggerKeys.add(triggerKey(desired));

        List<Binding> originalUserBindings = new ArrayList<>();
        List<Binding> combined = new ArrayList<>();
        for (Binding binding : bindingServiceInternal.getBindings())
        {
            if (binding.getType() != Binding.USER)
                continue;
            if (isOwnEphemeralOverride(binding))
                continue; // наша же рантайм-привязка для LOCAL_OVERRIDE_CONTEXT_IDS — не сохраняем
            originalUserBindings.add(binding);
            if (!desiredTriggerKeys.contains(triggerKey(binding)))
                combined.add(binding); // не наш триггер — сохраняем как есть
        }
        combined.addAll(desiredUserBindings);

        if (bindingSignatures(combined).equals(bindingSignatures(originalUserBindings)))
            return false; // уже в желаемом состоянии

        try
        {
            bindingServiceInternal.savePreferences(activeScheme, combined.toArray(new Binding[0]));
            Global.tempLog(TAG, "persisted USER override via savePreferences: scheme=" + schemeId //$NON-NLS-1$
                    + " total=" + combined.size() //$NON-NLS-1$
                    + " added=" + desiredUserBindings.size()); //$NON-NLS-1$

            // Пересобрать живую модель из реестра+preferences заново — иначе то, чем пользуется
            // диалог «Клавиши»/«Восстановить команду», может остаться в устаревшем состоянии
            // относительно того, что мы только что записали через savePreferences.
            bindingServiceInternal.readRegistryAndPreferences(commandService);
            Global.tempLog(TAG, "readRegistryAndPreferences done"); //$NON-NLS-1$
            return true;
        }
        catch (java.io.IOException e)
        {
            Global.logError(TAG, "savePreferences", e); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * {@code true}, если это в точности тот вид рантайм-привязки, который сам плагин пересоздаёт
     * при каждом старте через {@link #buildDesiredOverrides()} (GLOBAL_COMMAND_IDS × LOCAL_OVERRIDE_CONTEXT_IDS).
     * Такие записи никогда нельзя сохранять в preferences — они не «настройка пользователя».
     */
    private static boolean isOwnEphemeralOverride(Binding binding)
    {
        ParameterizedCommand pc = binding.getParameterizedCommand();
        String commandId = pc != null ? pc.getId() : null;
        if (commandId == null || !GLOBAL_COMMAND_ID_SET.contains(commandId))
            return false;
        String contextId = binding.getContextId();
        return FORM_EDITOR_CONTEXT_ID.equals(contextId) || ORDINARY_FORM_EDITOR_CONTEXT_ID.equals(contextId);
    }

    private static String triggerKey(Binding binding)
    {
        return binding.getSchemeId() + '|' + binding.getContextId()
                + '|' + binding.getTriggerSequence().format();
    }

    /**
     * Восстанавливает designer SYSTEM FormatAction Alt+Shift+F, если IrFormatText
     * не занимает это сочетание. Нужно после removeBinding, не пережившего перезапуск EDT.
     */
    private static void ensureFormatActionDefaults(Set<KeySequence> irFormatSequences)
    {
        if (bindingServiceInternal == null || commandService == null)
            return;

        KeySequence designerFormatSequence;
        try
        {
            designerFormatSequence = KeySequence.getInstance(FORMAT_ACTION_DESIGNER_SEQUENCE);
        }
        catch (Exception e)
        {
            Global.logError(TAG, "parse FormatAction sequence: " + FORMAT_ACTION_DESIGNER_SEQUENCE, e); //$NON-NLS-1$
            return;
        }
        if (irFormatSequences.contains(designerFormatSequence))
            return;

        if (isFormatActionSequenceActive(designerFormatSequence))
            return;

        Command command = commandService.getCommand(XTEXT_FORMAT_ACTION_ID);
        if (command == null || !command.isDefined())
            return;

        ParameterizedCommand parameterized;
        try
        {
            parameterized = ParameterizedCommand.generateCommand(command, null);
        }
        catch (Exception e)
        {
            Global.logError(TAG, "generateCommand: " + XTEXT_FORMAT_ACTION_ID, e); //$NON-NLS-1$
            return;
        }

        for (String contextId : FORMAT_ACTION_SUPPRESSION_CONTEXT_IDS)
        {
            Binding existing = findFormatActionSystemBinding(
                    DESIGNER_SCHEME_ID, designerFormatSequence, contextId);
            if (existing != null)
            {
                bindingServiceInternal.addBinding(existing);
                continue;
            }

            KeyBinding binding = new KeyBinding(
                    designerFormatSequence,
                    parameterized,
                    DESIGNER_SCHEME_ID,
                    contextId,
                    null,
                    null,
                    null,
                    Binding.SYSTEM);
            bindingServiceInternal.addBinding(binding);
        }
    }

    /** Effective: Alt+Shift+F среди active-bindings FormatAction (не только запись в getBindings). */
    private static boolean isFormatActionSequenceActive(KeySequence sequence)
    {
        if (bindingServiceInternal == null || sequence == null)
            return false;

        TriggerSequence[] active = bindingServiceInternal.getActiveBindingsFor(XTEXT_FORMAT_ACTION_ID);
        if (active == null)
            return false;

        for (TriggerSequence trigger : active)
        {
            if (sequence.equals(trigger))
                return true;
        }
        return false;
    }

    private static Binding findFormatActionSystemBinding(
            String schemeId,
            KeySequence sequence,
            String contextId)
    {
        if (bindingServiceInternal == null || schemeId == null || contextId == null || sequence == null)
            return null;

        String platform = bindingServiceInternal.getPlatform();
        String locale = bindingServiceInternal.getLocale();

        for (Binding binding : bindingServiceInternal.getBindings())
        {
            if (binding == null || binding.getType() != Binding.SYSTEM)
                continue;

            ParameterizedCommand pc = binding.getParameterizedCommand();
            if (pc == null || !XTEXT_FORMAT_ACTION_ID.equals(pc.getId()))
                continue;
            if (!contextId.equals(binding.getContextId()))
                continue;
            if (!schemeId.equals(binding.getSchemeId()))
                continue;
            if (!matchesPlatformLocale(binding, platform, locale))
                continue;

            TriggerSequence trigger = binding.getTriggerSequence();
            if (trigger instanceof KeySequence keySequence && keySequence.equals(sequence))
                return binding;
        }
        return null;
    }

    private static Set<String> bindingSignatures(List<? extends Binding> bindings)
    {
        Set<String> sigs = new HashSet<>();
        for (Binding binding : bindings)
            sigs.add(bindingSignature(binding));
        return sigs;
    }

    private static String bindingSignature(Binding binding)
    {
        ParameterizedCommand pc = binding.getParameterizedCommand();
        String commandId = pc != null ? pc.getId() : ""; //$NON-NLS-1$
        return commandId + '|' + binding.getSchemeId() + '|' + binding.getContextId()
                + '|' + binding.getTriggerSequence().format();
    }

    private static void logIrMirrorSummary(List<KeyBinding> desired)
    {
        for (String commandId : IR_MIRROR_COMMAND_IDS)
        {
            int mirrorBindings = 0;
            Set<String> seenSequences = new LinkedHashSet<>();
            for (KeyBinding binding : desired)
            {
                ParameterizedCommand pc = binding.getParameterizedCommand();
                if (pc == null || !commandId.equals(pc.getId()))
                    continue;
                mirrorBindings++;
                TriggerSequence trigger = binding.getTriggerSequence();
                if (trigger != null)
                    seenSequences.add(trigger.format());
            }
            if (mirrorBindings == 0)
                continue;

            String seqText = seenSequences.isEmpty()
                    ? "—" //$NON-NLS-1$
                    : String.join(", ", seenSequences); //$NON-NLS-1$
            Global.log(TAG, "IR mirror: " + commandId //$NON-NLS-1$
                    + " seq=" + seqText //$NON-NLS-1$
                    + " bindings=" + mirrorBindings); //$NON-NLS-1$
        }
    }

    /** Число runtime USER-override для команды ИР (для {@link IrKeyBindingDebug}). */
    static int countMirrorOverridesForCommand(String commandId)
    {
        if (commandId == null || commandId.isEmpty())
            return 0;

        int count = 0;
        for (KeyBinding binding : appliedOverrides)
        {
            ParameterizedCommand pc = binding.getParameterizedCommand();
            if (pc != null && commandId.equals(pc.getId()))
                count++;
        }
        return count;
    }

    private static KeyBinding createOverride(
            KeySequence sequence,
            ParameterizedCommand parameterized,
            String schemeId,
            String localContextId)
    {
        return new KeyBinding(
                sequence,
                parameterized,
                schemeId,
                localContextId,
                null,
                null,
                null,
                Binding.USER);
    }

    private static String activeSchemeId()
    {
        Scheme scheme = bindingServiceInternal.getActiveScheme();
        if (scheme != null && scheme.getId() != null && !scheme.getId().isBlank())
            return scheme.getId();
        return DEFAULT_SCHEME_ID;
    }

    private static boolean overrideSignaturesEqual(List<KeyBinding> current, List<KeyBinding> desired)
    {
        return overrideSignatures(current).equals(overrideSignatures(desired));
    }

    private static Set<String> overrideSignatures(List<KeyBinding> bindings)
    {
        Set<String> sigs = new HashSet<>();
        for (KeyBinding binding : bindings)
        {
            ParameterizedCommand pc = binding.getParameterizedCommand();
            String commandId = pc != null ? pc.getId() : ""; //$NON-NLS-1$
            sigs.add(commandId + '|' + binding.getSchemeId() + '|' + binding.getContextId()
                    + '|' + binding.getTriggerSequence().format());
        }
        return sigs;
    }

    /**
     * Effective-сочетания команды в глобальном контексте: сначала через IBindingService,
     * иначе — явный поиск в активной и базовой схемах.
     */
    private static Set<KeySequence> collectGlobalKeySequences(String commandId)
    {
        Set<KeySequence> result = new LinkedHashSet<>();

        TriggerSequence[] active = bindingServiceInternal.getActiveBindingsFor(commandId);
        if (active != null)
        {
            for (TriggerSequence ts : active)
            {
                if (ts instanceof KeySequence ks)
                    result.add(ks);
            }
        }

        String activeScheme = activeSchemeId();
        String platform = bindingServiceInternal.getPlatform();
        String locale = bindingServiceInternal.getLocale();

        for (Binding binding : bindingServiceInternal.getBindings())
        {
            if (binding == null)
                continue;
            String schemeId = binding.getSchemeId();
            if (!activeScheme.equals(schemeId) && !DEFAULT_SCHEME_ID.equals(schemeId))
                continue;
            if (!GLOBAL_CONTEXT_ID.equals(binding.getContextId()))
                continue;
            if (LOCAL_CONTEXTS.contains(binding.getContextId()))
                continue;

            ParameterizedCommand pc = binding.getParameterizedCommand();
            if (pc == null || !commandId.equals(pc.getId()))
                continue;
            if (!matchesPlatformLocale(binding, platform, locale))
                continue;

            TriggerSequence trigger = binding.getTriggerSequence();
            if (trigger instanceof KeySequence keySequence)
                result.add(keySequence);
        }
        return result;
    }

    /**
     * Effective-сочетания команды в контексте: если пользователь задал USER-привязки в Keys,
     * SYSTEM-дефолт из plugin.xml не включается (иначе зеркало «воскрешает» старое сочетание).
     */
    private static Set<KeySequence> collectEffectiveContextKeySequences(
            String commandId, String contextId)
    {
        Set<KeySequence> userSequences = collectContextKeySequencesByType(
                commandId, contextId, Binding.USER);
        if (!userSequences.isEmpty())
            return userSequences;
        return collectContextKeySequencesByType(commandId, contextId, Binding.SYSTEM);
    }

    /** Сочетания команды в заданном контексте и типе привязки. */
    private static Set<KeySequence> collectContextKeySequencesByType(
            String commandId, String contextId, int bindingType)
    {
        Set<KeySequence> result = new LinkedHashSet<>();
        if (bindingServiceInternal == null || contextId == null)
            return result;

        String activeScheme = activeSchemeId();
        String platform = bindingServiceInternal.getPlatform();
        String locale = bindingServiceInternal.getLocale();

        for (Binding binding : bindingServiceInternal.getBindings())
        {
            if (binding == null || binding.getType() != bindingType)
                continue;
            String schemeId = binding.getSchemeId();
            if (!activeScheme.equals(schemeId) && !DEFAULT_SCHEME_ID.equals(schemeId))
                continue;
            if (!contextId.equals(binding.getContextId()))
                continue;

            ParameterizedCommand pc = binding.getParameterizedCommand();
            if (pc == null || !commandId.equals(pc.getId()))
                continue;
            if (!matchesPlatformLocale(binding, platform, locale))
                continue;

            TriggerSequence trigger = binding.getTriggerSequence();
            if (trigger instanceof KeySequence keySequence)
                result.add(keySequence);
        }
        return result;
    }

    private static boolean matchesPlatformLocale(Binding binding, String platform, String locale)
    {
        String bindingPlatform = binding.getPlatform();
        if (bindingPlatform != null && platform != null && !bindingPlatform.equals(platform))
            return false;
        String bindingLocale = binding.getLocale();
        if (bindingLocale != null && locale != null && !bindingLocale.equals(locale))
            return false;
        return true;
    }

    private static void removeAppliedOverrides()
    {
        if (bindingServiceInternal == null)
            return;
        for (KeyBinding binding : appliedOverrides)
            bindingServiceInternal.removeBinding(binding);
        appliedOverrides.clear();
    }
}
