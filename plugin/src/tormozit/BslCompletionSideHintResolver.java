package tormozit;

import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.bsl.ui.hover.BslDispatchingEObjectTextHover;
import com._1c.g5.v8.dt.mcore.Method;
import com._1c.g5.v8.dt.mcore.Property;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextHoverExtension;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.xtext.ui.editor.hover.IEObjectHover;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;
import org.eclipse.xtext.resource.IResourceServiceProvider;

import org.eclipse.emf.common.util.URI;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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

    /** H20: пошаговый разбор источника browser creator. */
    public static final class AssistBrowserCreatorTrace
    {
        public IInformationControlCreator creator;
        public String winner = "none"; //$NON-NLS-1$
        public boolean fromCache;
        public boolean fromHoverViewer;
        public boolean fromHoverEditor;
        public boolean fromPopup;
        public boolean fromProbeDelegate;
        public boolean fromActiveViewer;
        public int probeOffset = -1;
        public String probeSource = "none"; //$NON-NLS-1$
        public int popupTotal;
        public int popupIr;
        public int popupEdt;
        public int popupExt3WithCreator;
        public int viewerHash = -1;
        public int reloaderHash = -1;

        public String toDebugJson()
        {
            return "{\"viewerHash\":" + viewerHash //$NON-NLS-1$
                + ",\"reloaderHash\":" + reloaderHash //$NON-NLS-1$
                + ",\"fromCache\":" + fromCache //$NON-NLS-1$
                + ",\"fromHoverViewer\":" + fromHoverViewer //$NON-NLS-1$
                + ",\"fromHoverEditor\":" + fromHoverEditor //$NON-NLS-1$
                + ",\"fromPopup\":" + fromPopup //$NON-NLS-1$
                + ",\"fromProbeDelegate\":" + fromProbeDelegate //$NON-NLS-1$
                + ",\"fromActiveViewer\":" + fromActiveViewer //$NON-NLS-1$
                + ",\"probeOffset\":" + probeOffset //$NON-NLS-1$
                + ",\"probeSource\":\"" + probeSource //$NON-NLS-1$
                + "\",\"popupTotal\":" + popupTotal //$NON-NLS-1$
                + ",\"popupIr\":" + popupIr //$NON-NLS-1$
                + ",\"popupEdt\":" + popupEdt //$NON-NLS-1$
                + ",\"popupExt3WithCreator\":" + popupExt3WithCreator //$NON-NLS-1$
                + ",\"winner\":\"" + winner //$NON-NLS-1$
                + "\",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * H20: все шаги resolve creator без записи в кэш reloader.
     */
    public static AssistBrowserCreatorTrace traceAssistBrowserCreatorResolution(
        ContentAssistant assistant, SourceViewer viewer, ContentAssistSessionReloader reloader)
    {
        AssistBrowserCreatorTrace trace = new AssistBrowserCreatorTrace();
        trace.viewerHash = viewer != null ? System.identityHashCode(viewer) : -1;
        trace.reloaderHash = reloader != null ? System.identityHashCode(reloader) : -1;
        try
        {
            ContentAssistPopupSync.PopupCreatorScan scan =
                ContentAssistPopupSync.scanPopupCreators(assistant);
            trace.popupTotal = scan.total;
            trace.popupIr = scan.ir;
            trace.popupEdt = scan.edt;
            trace.popupExt3WithCreator = scan.ext3WithCreator;
            IInformationControlCreator creator = null;
            if (reloader != null)
            {
                creator = reloader.getAssistBrowserCreator();
                if (creator != null)
                {
                    trace.fromCache = true;
                    trace.winner = "cache"; //$NON-NLS-1$
                }
            }
            if (creator == null && viewer != null)
            {
                creator = resolveAssistBrowserCreator(viewer);
                if (creator != null)
                {
                    trace.fromHoverViewer = true;
                    trace.winner = "hoverViewer"; //$NON-NLS-1$
                }
            }
            if (creator == null && reloader != null)
            {
                creator = resolveAssistBrowserCreatorFromEditor(reloader.getBslEditor());
                if (creator != null)
                {
                    trace.fromHoverEditor = true;
                    trace.winner = "hoverEditor"; //$NON-NLS-1$
                }
            }
            if (creator == null)
            {
                SmartContentAssistProcessor activeProcessor =
                    ContentAssistSessionReloader.getActiveProcessor();
                if (activeProcessor == null && reloader != null)
                    activeProcessor = reloader.getProcessor();
                int literalCaret = resolveLiteralCaretForProbe(viewer);
                ProbeDelegateBrowserResult probe = probeDelegateBrowserCreatorDetailed(
                    activeProcessor, viewer, literalCaret);
                trace.probeOffset = probe.probeOffset;
                trace.probeSource = probe.source;
                if (probe.creator != null)
                {
                    creator = probe.creator;
                    trace.fromProbeDelegate = true;
                    trace.winner = "probeDelegate"; //$NON-NLS-1$
                }
            }
            if (creator == null && scan.firstCreator != null)
            {
                creator = scan.firstCreator;
                trace.fromPopup = true;
                trace.winner = "popup"; //$NON-NLS-1$
            }
            if (creator == null)
            {
                creator = resolveAssistBrowserCreator();
                if (creator != null)
                {
                    trace.fromActiveViewer = true;
                    trace.winner = "activeViewer"; //$NON-NLS-1$
                }
            }
            trace.creator = creator;
        }
        catch (Exception e)
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H26", "traceAssistBrowserCreatorResolution", "error", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"msg\":\"" + ContentAssistDebug.jsonEscapeForLog(e.getMessage()) //$NON-NLS-1$
                    + "\",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            trace.creator = resolveAssistBrowserCreatorChain(assistant, viewer, reloader);
            if (trace.creator != null)
                trace.winner = "fallback"; //$NON-NLS-1$
        }
        return trace;
    }

    /** Цепочка resolve без H20 trace — fallback при сбое диагностики. */
    public static IInformationControlCreator resolveAssistBrowserCreatorChain(
        ContentAssistant assistant, SourceViewer viewer, ContentAssistSessionReloader reloader)
    {
        IInformationControlCreator creator = null;
        if (reloader != null)
            creator = reloader.getAssistBrowserCreator();
        if (creator == null && viewer != null)
            creator = resolveAssistBrowserCreator(viewer);
        if (creator == null && reloader != null)
            creator = resolveAssistBrowserCreatorFromEditor(reloader.getBslEditor());
        if (creator == null)
        {
            SmartContentAssistProcessor activeProcessor =
                ContentAssistSessionReloader.getActiveProcessor();
            if (activeProcessor == null && reloader != null)
                activeProcessor = reloader.getProcessor();
            creator = probeDelegateBrowserCreator(activeProcessor, viewer,
                resolveLiteralCaretForProbe(viewer));
        }
        if (creator == null && assistant != null)
            creator = ContentAssistPopupSync.findAssistBrowserCreatorInPopup(assistant);
        if (creator == null)
            creator = resolveAssistBrowserCreator();
        return creator;
    }

    /** {@link IInformationControlCreator} браузера BSL — как у штатного assist EDT. */
    public static IInformationControlCreator resolveAssistBrowserCreator()
    {
        return resolveAssistBrowserCreator(ContentAssistSessionReloader.getActiveViewer());
    }

    public static IInformationControlCreator resolveAssistBrowserCreator(SourceViewer viewer)
    {
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

    /** Hover creator через редактор — fallback, если RSP ещё не отдал IEObjectHover. */
    public static IInformationControlCreator resolveAssistBrowserCreatorFromEditor(
        BslXtextEditor editor)
    {
        if (editor == null)
            return null;
        try
        {
            org.eclipse.jface.text.source.ISourceViewer viewer = editor.getInternalSourceViewer();
            if (viewer instanceof SourceViewer sv)
            {
                IInformationControlCreator creator = resolveAssistBrowserCreator(sv);
                if (creator != null)
                    return creator;
                return resolveHoverCreatorFromViewerMap(sv);
            }
        }
        catch (Exception ignored)
        {
            // null ниже
        }
        return null;
    }

    /** Результат тихого probe delegate/hover вне literal. */
    public static final class ProbeDelegateBrowserResult
    {
        public IInformationControlCreator creator;
        public int probeOffset = -1;
        public String source = "none"; //$NON-NLS-1$
    }

    /**
     * Browser creator без UI: тихий delegate.compute вне literal + hover map.
     */
    public static IInformationControlCreator probeDelegateBrowserCreator(
        SmartContentAssistProcessor processor, SourceViewer viewer, int literalCaret)
    {
        return probeDelegateBrowserCreatorDetailed(processor, viewer, literalCaret).creator;
    }

    public static ProbeDelegateBrowserResult probeDelegateBrowserCreatorDetailed(
        SmartContentAssistProcessor processor, SourceViewer viewer, int literalCaret)
    {
        ProbeDelegateBrowserResult result = new ProbeDelegateBrowserResult();
        if (viewer == null)
            return result;
        if (literalCaret < 0)
            literalCaret = resolveLiteralCaretForProbe(viewer);
        IInformationControlCreator fromDelegate =
            probeDelegateCreatorFromOffset(processor, viewer, literalCaret, result);
        if (fromDelegate != null)
        {
            result.creator = fromDelegate;
            result.source = "delegate"; //$NON-NLS-1$
            return result;
        }
        IInformationControlCreator fromHover = resolveHoverCreatorFromViewerMap(viewer);
        if (fromHover != null)
        {
            result.creator = fromHover;
            result.source = "hover"; //$NON-NLS-1$
        }
        return result;
    }

    private static int resolveLiteralCaretForProbe(SourceViewer viewer)
    {
        if (viewer != null && viewer.getTextWidget() instanceof StyledText st
            && !st.isDisposed())
            return st.getCaretOffset();
        return -1;
    }

    private static IInformationControlCreator probeDelegateCreatorFromOffset(
        SmartContentAssistProcessor processor, SourceViewer viewer, int literalCaret,
        ProbeDelegateBrowserResult result)
    {
        if (processor == null || viewer == null)
            return null;
        IDocument doc = viewer.getDocument();
        int probeOffset = resolveProbeOffsetOutsideLiteral(doc, literalCaret);
        result.probeOffset = probeOffset;
        if (probeOffset < 0)
            return null;
        try
        {
            ICompletionProposal[] proposals =
                processor.getDelegate().computeCompletionProposals(viewer, probeOffset);
            if (proposals == null)
                return null;
            for (ICompletionProposal proposal : proposals)
            {
                if (proposal == null)
                    continue;
                ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
                if (raw instanceof IrCompletionProposal)
                    continue;
                if (raw instanceof ICompletionProposalExtension3 ext3)
                {
                    IInformationControlCreator creator = ext3.getInformationControlCreator();
                    if (creator != null)
                        return creator;
                }
            }
        }
        catch (Exception ignored)
        {
            // null ниже
        }
        return null;
    }

    /** Probe-offset вне строкового литерала для delegate.computeCompletionProposals. */
    static int resolveProbeOffsetOutsideLiteral(IDocument doc, int literalCaret)
    {
        if (doc == null || literalCaret < 0)
            return -1;
        Set<Integer> candidates = new LinkedHashSet<>();
        int dot = SmartContentAssistProcessor.ReceiverTypeLabel.findMemberAccessDot(doc, literalCaret);
        if (dot >= 0)
        {
            addProbeOffsetIfOutsideLiteral(candidates, doc, dot + 1);
            addProbeOffsetIfOutsideLiteral(candidates, doc, dot);
        }
        try
        {
            int line = doc.getLineOfOffset(literalCaret);
            int lineStart = doc.getLineOffset(line);
            addProbeOffsetIfOutsideLiteral(candidates, doc, lineStart);
        }
        catch (BadLocationException ignored)
        {
            // candidates ниже
        }
        for (int off = literalCaret; off >= 0 && off > literalCaret - 500; off--)
        {
            if (isProbeOffsetOutsideLiteral(doc, off))
            {
                candidates.add(off);
                break;
            }
        }
        for (Integer off : candidates)
        {
            if (off != null && off >= 0 && isProbeOffsetOutsideLiteral(doc, off))
                return off;
        }
        return -1;
    }

    private static boolean isProbeOffsetOutsideLiteral(IDocument doc, int offset)
    {
        if (doc == null || offset < 0)
            return false;
        try
        {
            if (offset > doc.getLength())
                return false;
            return !SmartContentAssistProcessor.isStringLiteralAssistContext(doc, offset);
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private static void addProbeOffsetIfOutsideLiteral(Set<Integer> candidates, IDocument doc,
        int offset)
    {
        if (isProbeOffsetOutsideLiteral(doc, offset))
            candidates.add(offset);
    }

    /** Hover creator из fTextHovers редактора (unwrap htmlHover). */
    static IInformationControlCreator resolveHoverCreatorFromViewerMap(SourceViewer viewer)
    {
        if (viewer == null)
            return null;
        try
        {
            @SuppressWarnings("unchecked")
            Map<Object, ITextHover> hovers =
                (Map<Object, ITextHover>) Global.getField(viewer, "fTextHovers"); //$NON-NLS-1$
            if (hovers == null || hovers.isEmpty())
                return null;
            for (ITextHover hover : hovers.values())
            {
                IInformationControlCreator creator = hoverCreatorFromTextHover(hover);
                if (creator != null)
                    return creator;
            }
        }
        catch (Exception ignored)
        {
            // null ниже
        }
        return null;
    }

    private static IInformationControlCreator hoverCreatorFromTextHover(ITextHover hover)
    {
        if (hover == null)
            return null;
        if (hover instanceof ITextHoverExtension ext)
        {
            IInformationControlCreator creator = ext.getHoverControlCreator();
            if (creator != null)
                return creator;
        }
        Object htmlHover = Global.getField(hover, "htmlHover"); //$NON-NLS-1$
        if (htmlHover instanceof BslDispatchingEObjectTextHover bslHover)
            return bslHover.getHoverControlCreator();
        if (htmlHover instanceof ITextHoverExtension htmlExt)
            return htmlExt.getHoverControlCreator();
        return null;
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
        if (creator == null && reloader != null)
            creator = resolveAssistBrowserCreatorFromEditor(reloader.getBslEditor());
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
