package tormozit;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.eclipse.ui.internal.keys.model.BindingElement;

/**
 * Строка пересечения на вкладках конфликтов страницы «Клавиши».
 */
final class ComfortKeysLocalConflictRow
{
    private static final Collator SORT_COLLATOR = Collator.getInstance(Locale.getDefault());

    enum Kind
    {
        /** Назначение конкурента можно изменить в Keys. */
        RESOLVABLE,
        /** Назначение конкурента в Keys изменить нельзя. */
        UNRESOLVABLE
    }

    final Kind kind;
    final String commandId;
    final String commandName;
    final String contextId;
    final String contextName;
    final String sequenceFormatted;
    final int bindingType;
    final BindingElement bindingElement;

    ComfortKeysLocalConflictRow(
            Kind kind,
            String commandId,
            String commandName,
            String contextId,
            String contextName,
            String sequenceFormatted,
            int bindingType,
            BindingElement bindingElement)
    {
        this.kind = kind;
        this.commandId = commandId;
        this.commandName = commandName;
        this.contextId = contextId;
        this.contextName = contextName;
        this.sequenceFormatted = sequenceFormatted;
        this.bindingType = bindingType;
        this.bindingElement = bindingElement;
    }

    String commandColumnText()
    {
        String name = commandName != null && !commandName.isBlank()
                ? commandName
                : commandId;
        String typeMark = bindingType == org.eclipse.jface.bindings.Binding.USER
                ? " U" //$NON-NLS-1$
                : "  "; //$NON-NLS-1$
        return typeMark + " " + name; //$NON-NLS-1$
    }

    String contextColumnText()
    {
        String ctx = contextName != null && !contextName.isBlank()
                ? contextName
                : contextId;
        if (sequenceFormatted != null && !sequenceFormatted.isBlank())
            return ctx + "  " + sequenceFormatted; //$NON-NLS-1$
        return ctx;
    }

    String copyText()
    {
        return commandColumnText() + '\t' + contextColumnText();
    }

    static void sortByCommandThenContext(List<ComfortKeysLocalConflictRow> rows)
    {
        rows.sort(Comparator
                .comparing(ComfortKeysLocalConflictRow::sortKeyCommand, SORT_COLLATOR)
                .thenComparing(ComfortKeysLocalConflictRow::sortKeyContext, SORT_COLLATOR));
    }

    private String sortKeyCommand()
    {
        if (commandName != null && !commandName.isBlank())
            return commandName;
        return commandId != null ? commandId : ""; //$NON-NLS-1$
    }

    private String sortKeyContext()
    {
        if (contextName != null && !contextName.isBlank())
            return contextName;
        return contextId != null ? contextId : ""; //$NON-NLS-1$
    }
}
