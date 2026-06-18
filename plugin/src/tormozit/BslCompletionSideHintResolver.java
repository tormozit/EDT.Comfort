package tormozit;

import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.mcore.Method;
import com._1c.g5.v8.dt.mcore.Property;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;

/**
 * Имя и тип («Метод» / «Свойство») элемента автодополнения для ИР.
 */
public final class BslCompletionSideHintResolver
{
    private BslCompletionSideHintResolver() {}

    public static String resolveElementName(ICompletionProposal proposal)
    {
        ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
        if (raw == null)
            return null;
        return extractProposalName(raw.getDisplayString());
    }

    /**
     * @return {@link IrBslExpressionHtmlSupport#KIND_METHOD},
     *         {@link IrBslExpressionHtmlSupport#KIND_PROPERTY} или {@code null}
     */
    public static String resolveElementKind(ICompletionProposal proposal)
    {
        ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
        if (raw == null)
            return null;
        String fromEObject = resolveKindFromProposalEObject(raw);
        if (fromEObject != null)
            return fromEObject;
        return resolveKindFromReplacement(raw);
    }

    static String extractProposalName(String display)
    {
        if (display == null || display.isEmpty())
            return null;
        int parenIdx = display.indexOf('(');
        String withoutParams = parenIdx >= 0 ? display.substring(0, parenIdx).trim() : display.trim();
        int colonIdx = withoutParams.indexOf(':');
        String name = colonIdx >= 0 ? withoutParams.substring(0, colonIdx).trim() : withoutParams;
        return name.isEmpty() ? null : name;
    }

    private static String resolveKindFromProposalEObject(ICompletionProposal raw)
    {
        if (!(raw instanceof ConfigurableCompletionProposal configurable))
            return null;
        Object additional = Global.getField(configurable, "additionalProposalInfo"); //$NON-NLS-1$
        if (additional instanceof EObject eObject)
            return resolveKindFromFeatureEObject(eObject);
        return null;
    }

    private static String resolveKindFromFeatureEObject(EObject eObject)
    {
        if (eObject instanceof Method)
            return IrBslExpressionHtmlSupport.KIND_METHOD;
        if (eObject instanceof Property)
            return IrBslExpressionHtmlSupport.KIND_PROPERTY;
        if (eObject instanceof FeatureEntry entry)
        {
            EObject feature = entry.getFeature();
            if (feature != null)
                return resolveKindFromFeatureEObject(feature);
        }
        return null;
    }

    private static String resolveKindFromReplacement(ICompletionProposal raw)
    {
        if (!(raw instanceof ConfigurableCompletionProposal configurable))
            return null;
        String replacement = configurable.getReplacementString();
        String name = extractProposalName(raw.getDisplayString());
        if (replacement == null || name == null || name.isEmpty())
            return null;
        String trimmed = replacement.trim();
        if (trimmed.startsWith(name) && trimmed.length() > name.length())
        {
            char next = trimmed.charAt(name.length());
            if (next == '(')
                return IrBslExpressionHtmlSupport.KIND_METHOD;
        }
        return IrBslExpressionHtmlSupport.KIND_PROPERTY;
    }
}
