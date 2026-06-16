package tormozit;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.TriggerSequence;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.internal.keys.BindingService;
import org.eclipse.ui.internal.keys.model.BindingElement;
import org.eclipse.ui.internal.keys.model.KeyController;
import org.eclipse.ui.internal.keys.model.ModelElement;
import org.eclipse.ui.keys.IBindingService;

/**
 * Анализ пересечений клавиш на странице «Клавиши».
 * <p>
 * Источник — каталог {@link KeyController} / BindingManager, не runtime
 * {@link BindingService#getBindings()} (там хуки могут снимать привязки).
 * <p>
 * «Конфликты устранимые»: назначение конкурента пользователь может изменить в Keys
 * (снять U или задать другую клавишу, в т.ч. вместо S).
 * «Конфликты неустранимые»: назначение конкурента в Keys изменить нельзя.
 */
final class ComfortKeysLocalConflictAnalyzer
{
    private static final String TAG = "ComfortKeysLocalConflict"; //$NON-NLS-1$

    private static final String DEFAULT_SCHEME_ID =
            "org.eclipse.ui.defaultAcceleratorConfiguration"; //$NON-NLS-1$

    private static final String DESIGNER_SCHEME_ID =
            "com._1c.g5.v8.designer.scheme"; //$NON-NLS-1$

    private ComfortKeysLocalConflictAnalyzer()
    {
    }

    static boolean hasAssignedKey(BindingElement selected)
    {
        if (selected == null)
            return false;
        TriggerSequence trigger = selected.getTrigger();
        return trigger != null && !trigger.isEmpty();
    }

    static ComfortKeysAnalysisResult analyze(
            KeyController keyController,
            BindingElement selected,
            IProgressMonitor monitor)
    {
        if (keyController == null || selected == null)
            return ComfortKeysAnalysisResult.EMPTY;

        if (!hasAssignedKey(selected))
            return ComfortKeysAnalysisResult.EMPTY;

        TriggerSequence trigger = selected.getTrigger();
        if (!(trigger instanceof KeySequence targetSequence))
            return ComfortKeysAnalysisResult.EMPTY;

        BindingService bindingService = resolveBindingService(keyController);
        String activeScheme = resolveActiveScheme(keyController, bindingService);
        String platform = bindingService != null ? bindingService.getPlatform() : null;
        String locale = bindingService != null ? bindingService.getLocale() : null;
        ICommandService commandService =
                PlatformUI.getWorkbench().getService(ICommandService.class);

        Binding[] bindings = resolveManagerBindings(keyController);
        ScanContext ctx = new ScanContext(
                keyController,
                selected,
                targetSequence,
                activeScheme,
                platform,
                locale,
                commandService,
                bindings,
                monitor);

        List<ComfortKeysLocalConflictRow> globalRows = scanResolvableSameKey(ctx);
        List<ComfortKeysLocalConflictRow> localRows = scanUnresolvableSameKey(ctx);

        if (Global.isLogEnabled())
        {
            Global.log(TAG, "analyze seq=" + targetSequence.format() //$NON-NLS-1$
                    + " scheme=" + activeScheme //$NON-NLS-1$
                    + " catalog=" + bindings.length //$NON-NLS-1$
                    + " resolvable=" + globalRows.size() //$NON-NLS-1$
                    + " unresolvable=" + localRows.size()); //$NON-NLS-1$
        }

        return new ComfortKeysAnalysisResult(globalRows, localRows);
    }

    private static List<ComfortKeysLocalConflictRow> scanResolvableSameKey(ScanContext ctx)
    {
        Map<String, ComfortKeysLocalConflictRow> byCommandContext = new LinkedHashMap<>();
        for (int i = 0; i < ctx.bindings.length; i++)
        {
            if (ctx.monitor != null && ctx.monitor.isCanceled())
                break;
            if (ctx.monitor != null && (i % 50 == 0))
                ctx.monitor.worked(1);

            Binding binding = ctx.bindings[i];
            if (!matchesCatalogBinding(ctx, binding))
                continue;

            if (!isCompetitorAssignmentUserChangeable(
                    ctx.keyController, binding, ctx.commandService))
                continue;

            addRow(
                    byCommandContext,
                    ctx,
                    binding,
                    ComfortKeysLocalConflictRow.Kind.RESOLVABLE,
                    true);
        }

        return sortRows(byCommandContext);
    }

    private static List<ComfortKeysLocalConflictRow> scanUnresolvableSameKey(ScanContext ctx)
    {
        Map<String, ComfortKeysLocalConflictRow> byCommandContext = new LinkedHashMap<>();
        for (int i = 0; i < ctx.bindings.length; i++)
        {
            if (ctx.monitor != null && ctx.monitor.isCanceled())
                break;
            if (ctx.monitor != null && (i % 50 == 0))
                ctx.monitor.worked(1);

            Binding binding = ctx.bindings[i];
            if (!matchesCatalogBinding(ctx, binding))
                continue;

            if (isCompetitorAssignmentUserChangeable(
                    ctx.keyController, binding, ctx.commandService))
                continue;

            addRow(
                    byCommandContext,
                    ctx,
                    binding,
                    ComfortKeysLocalConflictRow.Kind.UNRESOLVABLE,
                    false);
        }

        return sortRows(byCommandContext);
    }

    private static void addRow(
            Map<String, ComfortKeysLocalConflictRow> byCommandContext,
            ScanContext ctx,
            Binding binding,
            ComfortKeysLocalConflictRow.Kind kind,
            boolean preferUserOnDuplicate)
    {
        ParameterizedCommand pc = binding.getParameterizedCommand();
        String commandId = pc != null ? pc.getId() : null;
        if (commandId == null || commandId.isBlank())
            return;

        String contextId = binding.getContextId();
        int bindingType = binding.getType();
        if (isSameCommandAsSelected(ctx.selected, commandId))
            return;

        String signature = commandId + '|' + contextId;
        String commandName = resolveCommandName(ctx.commandService, commandId);
        String contextName = resolveContextName(ctx.keyController, contextId);
        BindingElement bindingElement =
                resolveBindingElementForCatalogBinding(ctx.keyController, binding);
        String sequenceFormatted = formatEffectiveSequence(bindingElement, binding);

        ComfortKeysLocalConflictRow candidate = new ComfortKeysLocalConflictRow(
                kind,
                commandId,
                commandName,
                contextId,
                contextName,
                sequenceFormatted,
                bindingType,
                bindingElement);

        ComfortKeysLocalConflictRow existing = byCommandContext.get(signature);
        if (existing == null)
        {
            byCommandContext.put(signature, candidate);
            return;
        }
        if (preferUserOnDuplicate
                && existing.bindingType != Binding.USER
                && candidate.bindingType == Binding.USER)
            byCommandContext.put(signature, candidate);
    }

    private static List<ComfortKeysLocalConflictRow> sortRows(
            Map<String, ComfortKeysLocalConflictRow> byCommandContext)
    {
        List<ComfortKeysLocalConflictRow> result = new ArrayList<>(byCommandContext.values());
        ComfortKeysLocalConflictRow.sortByCommandThenContext(result);
        return result;
    }

    private static boolean matchesCatalogBinding(ScanContext ctx, Binding binding)
    {
        if (binding == null)
            return false;

        if (!schemeMatches(binding.getSchemeId(), ctx.activeScheme))
            return false;
        if (!matchesPlatformLocale(binding, ctx.platform, ctx.locale))
            return false;

        KeySequence effectiveKey = resolveEffectiveKeyForMatch(ctx.keyController, binding);
        if (effectiveKey == null)
            return false;

        return matchesSequenceKey(effectiveKey, ctx.targetSequence);
    }

    /** Эффективная клавиша из строки Keys (U вместо устаревшей S в каталоге). */
    private static KeySequence resolveEffectiveKeyForMatch(
            KeyController keyController,
            Binding binding)
    {
        ParameterizedCommand pc = binding.getParameterizedCommand();
        String commandId = pc != null ? pc.getId() : null;
        if (commandId == null || commandId.isBlank() || binding == null)
            return null;

        String contextId = binding.getContextId();
        int bindingType = binding.getType();

        BindingElement keysRow = findBindingElementInModel(
                keyController, commandId, contextId, bindingType);
        if (keysRow == null && bindingType == Binding.SYSTEM)
            keysRow = findBindingElementInModel(
                    keyController, commandId, contextId, Binding.USER);

        if (keysRow != null)
        {
            TriggerSequence effective = keysRow.getTrigger();
            if (effective instanceof KeySequence keySequence && !effective.isEmpty())
                return keySequence;
        }

        TriggerSequence catalogTrigger = binding.getTriggerSequence();
        if (catalogTrigger instanceof KeySequence keySequence && !catalogTrigger.isEmpty())
            return keySequence;

        return null;
    }

    private static String formatEffectiveSequence(BindingElement bindingElement, Binding binding)
    {
        if (bindingElement != null)
        {
            TriggerSequence effective = bindingElement.getTrigger();
            if (effective != null && !effective.isEmpty())
                return effective.format();
        }
        TriggerSequence catalogTrigger = binding.getTriggerSequence();
        if (catalogTrigger != null && !catalogTrigger.isEmpty())
            return catalogTrigger.format();
        return ""; //$NON-NLS-1$
    }

    /**
     * Может ли пользователь изменить назначение конкурента на странице Keys
     * (тип S/U сейчас не важен).
     */
    private static boolean isCompetitorAssignmentUserChangeable(
            KeyController keyController,
            Binding binding,
            ICommandService commandService)
    {
        if (binding == null)
            return false;

        ParameterizedCommand pc = binding.getParameterizedCommand();
        String commandId = pc != null ? pc.getId() : null;
        if (commandId == null || commandId.isBlank())
            return false;

        if (commandService == null)
            return false;

        Command command = commandService.getCommand(commandId);
        if (command == null)
            return false;

        try
        {
            if (!command.isDefined())
                return false;
        }
        catch (Exception ignored)
        {
            return false;
        }

        return resolveBindingElementForCatalogBinding(keyController, binding) != null;
    }

    /** Та же команда — не конкурент (зеркала в других контекстах, S/U-двойник). */
    private static boolean isSameCommandAsSelected(
            BindingElement selected,
            String commandId)
    {
        if (commandId == null || commandId.isBlank())
            return false;
        return commandId.equals(resolveSelectedCommandId(selected));
    }

    private static String resolveSelectedCommandId(BindingElement selected)
    {
        String id = selected.getId();
        if (id != null && !id.isBlank())
            return id;

        try
        {
            Object modelObject = selected.getModelObject();
            if (modelObject instanceof Binding binding)
            {
                ParameterizedCommand pc = binding.getParameterizedCommand();
                if (pc != null)
                {
                    String commandId = pc.getId();
                    if (commandId != null && !commandId.isBlank())
                        return commandId;
                }
            }
        }
        catch (Exception ignored)
        {
            // fallback — getId()
        }

        try
        {
            Object pcObj = Global.invoke(selected, "getParameterizedCommand"); //$NON-NLS-1$
            if (pcObj instanceof ParameterizedCommand parameterizedCommand)
            {
                String commandId = parameterizedCommand.getId();
                if (commandId != null && !commandId.isBlank())
                    return commandId;
            }
        }
        catch (Exception ignored)
        {
            // package-private getParameterizedCommand
        }

        return id;
    }

    private static BindingElement resolveBindingElementForBinding(
            KeyController keyController,
            Binding binding)
    {
        if (keyController == null || binding == null)
            return null;

        try
        {
            Object element = keyController.getBindingModel()
                    .getBindingToElement()
                    .get(binding);
            if (element instanceof BindingElement bindingElement)
                return bindingElement;
        }
        catch (Exception ignored)
        {
            // внутренний API изменился
        }

        return null;
    }

    private static BindingElement resolveBindingElementForCatalogBinding(
            KeyController keyController,
            Binding binding)
    {
        BindingElement element = resolveBindingElementForBinding(keyController, binding);
        if (element != null)
            return element;

        ParameterizedCommand pc = binding.getParameterizedCommand();
        String commandId = pc != null ? pc.getId() : null;
        if (commandId == null || commandId.isBlank())
            return null;

        return findBindingElementInModel(
                keyController,
                commandId,
                binding.getContextId(),
                binding.getType());
    }

    private static BindingElement findBindingElementInModel(
            KeyController keyController,
            String commandId,
            String contextId,
            int bindingType)
    {
        if (keyController == null || commandId == null || commandId.isBlank())
            return null;

        try
        {
            for (Object element : keyController.getBindingModel().getBindings())
            {
                if (!(element instanceof BindingElement bindingElement))
                    continue;
                if (!commandId.equals(bindingElement.getId()))
                    continue;
                String modelContextId = resolveBindingElementContextId(bindingElement);
                if (contextId == null
                        ? modelContextId != null
                        : !contextId.equals(modelContextId))
                    continue;
                if (!matchesBindingElementType(bindingElement, bindingType))
                    continue;
                return bindingElement;
            }
        }
        catch (Exception ignored)
        {
            // внутренний API изменился
        }

        return null;
    }

    private static boolean matchesBindingElementType(
            BindingElement bindingElement,
            int bindingType)
    {
        Integer userDelta = bindingElement.getUserDelta();
        if (userDelta != null)
            return userDelta.intValue() == bindingType;
        return bindingType == Binding.SYSTEM;
    }

    private static BindingService resolveBindingService(KeyController keyController)
    {
        try
        {
            Object service = Global.invoke(keyController, "getService"); //$NON-NLS-1$
            if (service instanceof BindingService bindingService)
                return bindingService;
        }
        catch (Exception ignored)
        {
            // поле bindingService
        }

        try
        {
            Field field = KeyController.class.getDeclaredField("bindingService"); //$NON-NLS-1$
            field.setAccessible(true);
            Object service = field.get(keyController);
            if (service instanceof BindingService bindingService)
                return bindingService;
        }
        catch (Exception ignored)
        {
            // fallback — workbench
        }

        IBindingService service =
                PlatformUI.getWorkbench().getService(IBindingService.class);
        if (service instanceof BindingService bindingService)
            return bindingService;

        return null;
    }

    private static Binding[] resolveManagerBindings(KeyController keyController)
    {
        Object manager = resolveBindingManager(keyController);
        if (manager == null)
            return new Binding[0];

        try
        {
            Object bindings = Global.invoke(manager, "getBindings"); //$NON-NLS-1$
            if (bindings instanceof Binding[] arr)
                return arr;
        }
        catch (Exception ignored)
        {
            // внутренний API изменился
        }

        return new Binding[0];
    }

    private static Object resolveBindingManager(KeyController keyController)
    {
        try
        {
            Object manager = Global.invoke(keyController, "getManager"); //$NON-NLS-1$
            if (manager != null)
                return manager;
        }
        catch (Exception ignored)
        {
            // fBindingManager
        }

        try
        {
            Field field = KeyController.class.getDeclaredField("fBindingManager"); //$NON-NLS-1$
            field.setAccessible(true);
            return field.get(keyController);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static String resolveActiveScheme(
            KeyController keyController,
            BindingService bindingService)
    {
        try
        {
            Object schemeModel = keyController.getSchemeModel();
            if (schemeModel != null)
            {
                Object selected = Global.invoke(schemeModel, "getSelectedElement"); //$NON-NLS-1$
                if (selected instanceof ModelElement element)
                {
                    String id = element.getId();
                    if (id != null && !id.isBlank())
                        return id;
                }
            }
        }
        catch (Exception ignored)
        {
            // activeSchemeId
        }

        try
        {
            Field field = KeyController.class.getDeclaredField("activeSchemeId"); //$NON-NLS-1$
            field.setAccessible(true);
            Object id = field.get(keyController);
            if (id instanceof String schemeId && !schemeId.isBlank())
                return schemeId;
        }
        catch (Exception ignored)
        {
            // BindingService
        }

        if (bindingService != null && bindingService.getActiveScheme() != null)
            return bindingService.getActiveScheme().getId();

        return DEFAULT_SCHEME_ID;
    }

    private static String resolveBindingElementContextId(BindingElement selected)
    {
        ModelElement context = selected.getContext();
        if (context != null)
        {
            String id = context.getId();
            if (id != null && !id.isBlank())
                return id;
        }

        try
        {
            Object id = Global.invoke(selected, "getContextId"); //$NON-NLS-1$
            if (id instanceof String contextId && !contextId.isBlank())
                return contextId;
        }
        catch (Exception ignored)
        {
            // package-private getContextId
        }

        return null;
    }

    private static boolean matchesSequenceKey(KeySequence keySequence, KeySequence target)
    {
        if (target.equals(keySequence))
            return true;

        return target.getTriggers().length > 0
                && keySequence.getTriggers().length > 0
                && target.getTriggers()[0].equals(keySequence.getTriggers()[0]);
    }

    private static boolean schemeMatches(String schemeId, String activeScheme)
    {
        if (schemeId == null)
            return false;

        return activeScheme.equals(schemeId)
                || DEFAULT_SCHEME_ID.equals(schemeId)
                || DESIGNER_SCHEME_ID.equals(schemeId);
    }

    private static boolean matchesPlatformLocale(Binding binding, String platform, String locale)
    {
        String bindingPlatform = binding.getPlatform();
        if (bindingPlatform != null && !bindingPlatform.isBlank()
                && platform != null && !bindingPlatform.equals(platform))
            return false;

        String bindingLocale = binding.getLocale();
        return bindingLocale == null || bindingLocale.isBlank()
                || locale == null || bindingLocale.equals(locale);
    }

    private static String resolveCommandName(ICommandService commandService, String commandId)
    {
        if (commandService == null)
            return commandId;

        Command command = commandService.getCommand(commandId);
        if (command == null)
            return commandId;

        try
        {
            String name = command.getName();
            if (name != null && !name.isBlank())
                return name;
        }
        catch (Exception ignored)
        {
            // команда ещё не определена
        }

        return commandId;
    }

    private static String resolveContextName(KeyController keyController, String contextId)
    {
        try
        {
            Object contextModel = keyController.getContextModel();
            if (contextModel == null)
                return fallbackContextLabel(contextId);

            Object contexts = Global.invoke(contextModel, "getElements"); //$NON-NLS-1$
            if (!(contexts instanceof Iterable<?>))
                contexts = Global.invoke(contextModel, "getContexts"); //$NON-NLS-1$

            if (!(contexts instanceof Iterable<?> iterable))
                return fallbackContextLabel(contextId);

            for (Object ctx : iterable)
            {
                Object id = Global.invoke(ctx, "getId"); //$NON-NLS-1$
                if (!contextId.equals(id))
                    continue;

                Object name = Global.invoke(ctx, "getName"); //$NON-NLS-1$
                if (name instanceof String s && !s.isBlank())
                    return s;
            }
        }
        catch (Exception ignored)
        {
            // fallback — id контекста
        }

        return fallbackContextLabel(contextId);
    }

    private static String fallbackContextLabel(String contextId)
    {
        String known = knownContextLabel(contextId);
        return known != null ? known : contextId;
    }

    private static String knownContextLabel(String contextId)
    {
        if ("com._1c.g5.v8.dt.form.ui.ordinaryFormEditor".equals(contextId)) //$NON-NLS-1$
            return "Редактор формы"; //$NON-NLS-1$
        if ("com._1c.g5.v8.dt.form.ui.formEditor".equals(contextId)) //$NON-NLS-1$
            return "Редактор формы"; //$NON-NLS-1$
        if ("org.eclipse.xtext.ui.XtextEditorScope".equals(contextId)) //$NON-NLS-1$
            return "Редактирование текста"; //$NON-NLS-1$
        if ("org.eclipse.xtext.ui.embeddedTextEditorScope".equals(contextId)) //$NON-NLS-1$
            return "Вложенный текст"; //$NON-NLS-1$
        if ("tormozit.compareConf.context".equals(contextId)) //$NON-NLS-1$
            return "Редактор сравнения EDT"; //$NON-NLS-1$
        if ("tormozit.collection.context".equals(contextId)) //$NON-NLS-1$
            return "Окно коллекции"; //$NON-NLS-1$
        return null;
    }

    private static final class ScanContext
    {
        final KeyController keyController;
        final BindingElement selected;
        final KeySequence targetSequence;
        final String activeScheme;
        final String platform;
        final String locale;
        final ICommandService commandService;
        final Binding[] bindings;
        final IProgressMonitor monitor;

        ScanContext(
                KeyController keyController,
                BindingElement selected,
                KeySequence targetSequence,
                String activeScheme,
                String platform,
                String locale,
                ICommandService commandService,
                Binding[] bindings,
                IProgressMonitor monitor)
        {
            this.keyController = keyController;
            this.selected = selected;
            this.targetSequence = targetSequence;
            this.activeScheme = activeScheme;
            this.platform = platform;
            this.locale = locale;
            this.commandService = commandService;
            this.bindings = bindings;
            this.monitor = monitor;
        }
    }
}
