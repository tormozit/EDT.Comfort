package tormozit;

import java.util.List;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.ide.editor.syntaxcoloring.IHighlightedPositionAcceptor;
import org.eclipse.xtext.ide.editor.syntaxcoloring.ISemanticHighlightingCalculator;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.util.CancelIndicator;

import com._1c.g5.v8.dt.bsl.common.Symbols;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.util.BslUtil;

/**
 * Подсвечивает серверные вызовы в BSL-редакторе. Оборачивается вокруг
 * штатного калькулятора реконсилера через {@link BslServerCallHighlightingHook}
 * (делегирование к оригинальному калькулятору делает сам хук), поэтому здесь
 * считается только дополнительный стиль для {@link Invocation} с
 * {@code isIsServerCall() == true} — {@link BslServerCallHighlightingConfiguration#SERVER_CALL_CONTEXT_ID}
 * для вызовов "с контекстом" (&НаСервере) и {@link BslServerCallHighlightingConfiguration#SERVER_CALL_ID}
 * для вызовов "без контекста" (&НаСервереБезКонтекста) либо когда метод не резолвится.
 */
public final class BslServerCallHighlightingCalculator
    implements ISemanticHighlightingCalculator
{
    private static final String TAG = "ServerCallCalc"; //$NON-NLS-1$

    @Override
    public void provideHighlightingFor(XtextResource resource, IHighlightedPositionAcceptor acceptor,
        CancelIndicator cancelIndicator)
    {
        if (resource == null || acceptor == null || isCanceled(cancelIndicator) || resource.getParseResult() == null)
            return;

        if (!ComfortSettings.isServerCallHighlightingEnabled())
            return;

        EObject root = resource.getParseResult().getRootASTElement();
        if (root == null)
            return;

        int invocations = 0;
        int serverCalls = 0;
        TreeIterator<EObject> iterator = root.eAllContents();
        while (iterator.hasNext())
        {
            if (isCanceled(cancelIndicator))
                return;

            EObject element = iterator.next();
            if (element instanceof Invocation)
            {
                invocations++;
                Invocation inv = (Invocation)element;
                if (inv.isIsServerCall())
                {
                    serverCalls++;
                    highlightInvocation(inv, acceptor);
                }
            }
        }
        if (serverCalls > 0)
            Global.log(TAG, "provideHighlightingFor: invocations=" + invocations + " serverCalls=" + serverCalls); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void highlightInvocation(Invocation invocation, IHighlightedPositionAcceptor acceptor)
    {
        FeatureAccess methodAccess = invocation.getMethodAccess();
        if (methodAccess == null)
            return;

        String highlightingId = resolveHighlightingId(methodAccess);

        highlightFeatureName(methodAccess, acceptor, highlightingId);
        if (methodAccess instanceof DynamicFeatureAccess)
            highlightSourceFeatureNames(((DynamicFeatureAccess)methodAccess).getSource(), acceptor, highlightingId);
    }

    /**
     * Серверный вызов "с контекстом" (&НаСервере) красится отдельным стилем от
     * вызова "без контекста" (&НаСервереБезКонтекста) — различие определяется
     * прагмой резолвленного метода. Если метод резолвить не удалось, используется
     * базовый стиль (прежнее поведение).
     */
    private String resolveHighlightingId(FeatureAccess methodAccess)
    {
        Method method = resolveMethod(methodAccess);
        if (method == null)
            return BslServerCallHighlightingConfiguration.SERVER_CALL_ID;

        boolean noContext = BslUtil.hasPragma(method,
            Symbols.AT_SERVER_NO_CONTEXT_INTNL, Symbols.AT_SERVER_NO_CONTEXT_RUS);
        return noContext
            ? BslServerCallHighlightingConfiguration.SERVER_CALL_ID
            : BslServerCallHighlightingConfiguration.SERVER_CALL_CONTEXT_ID;
    }

    private Method resolveMethod(FeatureAccess methodAccess)
    {
        List<FeatureEntry> entries;
        if (methodAccess instanceof StaticFeatureAccess staticAccess)
            entries = staticAccess.getFeatureEntries();
        else if (methodAccess instanceof DynamicFeatureAccess dynamicAccess)
            entries = dynamicAccess.getFeatureEntries();
        else
            return null;

        if (entries == null)
            return null;
        for (FeatureEntry entry : entries)
        {
            if (entry.getFeature() instanceof Method method)
                return method;
        }
        return null;
    }

    private void highlightSourceFeatureNames(Expression source, IHighlightedPositionAcceptor acceptor,
        String highlightingId)
    {
        if (source instanceof DynamicFeatureAccess)
        {
            DynamicFeatureAccess dynamicSource = (DynamicFeatureAccess)source;
            highlightSourceFeatureNames(dynamicSource.getSource(), acceptor, highlightingId);
            highlightFeatureName(dynamicSource, acceptor, highlightingId);
        }
        else if (source instanceof FeatureAccess)
        {
            highlightFeatureName((FeatureAccess)source, acceptor, highlightingId);
        }
    }

    private void highlightFeatureName(FeatureAccess featureAccess, IHighlightedPositionAcceptor acceptor,
        String highlightingId)
    {
        List<INode> nodes = NodeModelUtils.findNodesForFeature(featureAccess, BslPackage.Literals.FEATURE_ACCESS__NAME);
        for (INode node : nodes)
        {
            if (node.getLength() > 0)
            {
                acceptor.addPosition(node.getOffset(), node.getLength(), highlightingId);
            }
        }
    }

    private boolean isCanceled(CancelIndicator cancelIndicator)
    {
        return cancelIndicator != null && cancelIndicator.isCanceled();
    }
}
