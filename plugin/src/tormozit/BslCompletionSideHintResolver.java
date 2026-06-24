package tormozit;

import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.ui.hover.BslDispatchingEObjectTextHover;
import com._1c.g5.v8.dt.mcore.Method;
import com._1c.g5.v8.dt.mcore.Property;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.xtext.ui.editor.hover.IEObjectHover;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;
import org.eclipse.xtext.resource.IResourceServiceProvider;

import org.eclipse.emf.common.util.URI;

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
        if (raw instanceof IrCompletionProposal ir)
            return ir.getWordValue();
        return extractProposalName(raw.getDisplayString());
    }

    /** Стабильный ключ кэша HTML/активации (не меняется при дорасчёте типа). */
    public static String resolveIrCacheKey(ICompletionProposal proposal)
    {
        ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
        if (raw instanceof IrCompletionProposal ir)
            return ir.getStableCacheKey();
        return raw != null ? raw.getDisplayString() : null;
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
        if (raw instanceof IrCompletionProposal ir)
            return ir.isMethod()
                ? IrBslExpressionHtmlSupport.KIND_METHOD
                : IrBslExpressionHtmlSupport.KIND_PROPERTY;
        String fromEObject = resolveKindFromProposalEObject(raw);
        if (fromEObject != null)
            return fromEObject;
        return resolveKindFromReplacement(raw);
    }

    /** {@link IInformationControlCreator} браузера BSL — как у штатного assist EDT. */
    public static IInformationControlCreator resolveAssistBrowserCreator()
    {
        SourceViewer viewer = ContentAssistSessionReloader.getActiveViewer();
        if (viewer == null || !(viewer.getDocument() instanceof IXtextDocument xtextDoc))
            return null;
        URI resourceUri = xtextDoc.getResourceURI();
        if (resourceUri == null)
            return null;
        IResourceServiceProvider rsp =
            IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(resourceUri);
        if (rsp == null)
            return null;
        IEObjectHover hover = rsp.get(IEObjectHover.class);
        if (!(hover instanceof BslDispatchingEObjectTextHover bslHover))
            return null;
        return bslHover.getHoverControlCreator();
    }

    /**
     * Browser creator assist: EDT-proposal → кэш сессии → список popup → hover.
     * ИР-строки без своего creator иначе получают текстовый DefaultInformationControl.
     */
    public static IInformationControlCreator resolveAssistBrowserCreatorForProposal(
        ContentAssistant assistant, ICompletionProposal proposal)
    {
        IInformationControlCreator creator = creatorFromProposal(proposal);
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (creator == null && reloader != null)
            creator = reloader.getAssistBrowserCreator();
        if (creator == null && assistant != null)
            creator = ContentAssistPopupSync.findAssistBrowserCreatorInPopup(assistant);
        if (creator == null)
            creator = resolveAssistBrowserCreator();
        if (creator != null && reloader != null)
            reloader.rememberAssistBrowserCreator(creator);
        return creator;
    }

    private static IInformationControlCreator creatorFromProposal(ICompletionProposal proposal)
    {
        ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
        if (raw instanceof IrCompletionProposal)
            return null;
        if (raw instanceof ICompletionProposalExtension3 ext3raw)
            return ext3raw.getInformationControlCreator();
        return null;
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
