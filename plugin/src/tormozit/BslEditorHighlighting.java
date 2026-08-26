package tormozit;

import java.util.List;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.ide.editor.syntaxcoloring.IHighlightedPositionAcceptor;
import org.eclipse.xtext.ide.editor.syntaxcoloring.ISemanticHighlightingCalculator;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.util.CancelIndicator;

import com._1c.g5.v8.dt.bsl.common.Symbols;
import com._1c.g5.v8.dt.bsl.model.Block;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.ForStatement;
import com._1c.g5.v8.dt.bsl.model.ImplicitVariable;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.util.BslUtil;

/**
 * Дополнительная семантическая подсветка BSL-редактора. Оборачивается вокруг
 * штатного калькулятора реконсилера через {@link BslEditorHighlightingHook}
 * (делегирование к оригинальному калькулятору делает сам хук).
 * <ul>
 * <li>серверные вызовы: {@link Invocation} с {@code isIsServerCall() == true} —
 * {@link BslServerCallHighlightingConfiguration#SERVER_CALL_CONTEXT_ID} для
 * вызовов «с контекстом» (&НаСервере) и
 * {@link BslServerCallHighlightingConfiguration#SERVER_CALL_ID} для вызовов
 * «без контекста» (&НаСервереБезКонтекста) либо когда метод не резолвится;</li>
 * <li>создаваемые переменные: имя
 * {@link StaticFeatureAccess} в левой части присваивания с непустой правой
 * частью либо переменная цикла, и только если
 * {@code getImplicitVariable() != null} —
 * {@link BslServerCallHighlightingConfiguration#IMPLICIT_VARIABLE_ID}.</li>
 * </ul>
 */
public final class BslEditorHighlighting
    implements ISemanticHighlightingCalculator
{
    @Override
    public void provideHighlightingFor(XtextResource resource, IHighlightedPositionAcceptor acceptor,
        CancelIndicator cancelIndicator)
    {
        if (resource == null || acceptor == null || isCanceled(cancelIndicator) || resource.getParseResult() == null)
            return;

        boolean serverCalls = ComfortSettings.isServerCallHighlightingEnabled();
        boolean implicitVars = ComfortSettings.isImplicitVariableHighlightingEnabled();
        if (!serverCalls && !implicitVars)
            return;

        EObject root = resource.getParseResult().getRootASTElement();
        if (root == null)
            return;

        if (serverCalls)
            highlightServerCalls(root, acceptor, cancelIndicator);
        if (implicitVars && !isCanceled(cancelIndicator))
            highlightImplicitVariableCreations(root, acceptor, cancelIndicator);
    }

    private void highlightServerCalls(EObject root, IHighlightedPositionAcceptor acceptor,
        CancelIndicator cancelIndicator)
    {
        TreeIterator<EObject> iterator = root.eAllContents();
        while (iterator.hasNext())
        {
            if (isCanceled(cancelIndicator))
                return;

            EObject element = iterator.next();
            if (element instanceof Invocation inv && inv.isIsServerCall())
                highlightInvocation(inv, acceptor);
        }
    }

    /**
     * Красит имя в месте создания неявной переменной: левая часть первого
     * присваивания и переменная цикла {@code Для}/{@code Для Каждого}.
     * Берёт готовый список {@link Block#getImplicitVariables()}, без линковки.
     */
    private void highlightImplicitVariableCreations(EObject root,
        IHighlightedPositionAcceptor acceptor, CancelIndicator cancelIndicator)
    {
        if (root instanceof Module module)
        {
            highlightImplicitVariablesInBlock(module, acceptor, cancelIndicator);
            for (Method method : module.allMethods())
            {
                if (isCanceled(cancelIndicator))
                    return;
                highlightImplicitVariablesInBlock(method, acceptor, cancelIndicator);
            }
            return;
        }
        highlightImplicitVariablesByTree(root, acceptor, cancelIndicator);
    }

    private void highlightImplicitVariablesInBlock(Block block, IHighlightedPositionAcceptor acceptor,
        CancelIndicator cancelIndicator)
    {
        if (block == null)
            return;
        EList<ImplicitVariable> variables = block.getImplicitVariables();
        if (variables.isEmpty())
            return;
        ImplicitVariable first = variables.get(0);
        if (first == null || !(first.eContainer() instanceof StaticFeatureAccess))
        {
            highlightImplicitVariablesByTree(block, acceptor, cancelIndicator);
            return;
        }
        for (ImplicitVariable variable : variables)
        {
            if (isCanceled(cancelIndicator))
                return;
            if (variable == null)
                continue;
            EObject container = variable.eContainer();
            if (container instanceof StaticFeatureAccess access
                && isImplicitVariableCreationSite(access))
                highlightFeatureName(access, acceptor,
                    BslServerCallHighlightingConfiguration.IMPLICIT_VARIABLE_ID);
        }
    }

    private void highlightImplicitVariablesByTree(EObject root, IHighlightedPositionAcceptor acceptor,
        CancelIndicator cancelIndicator)
    {
        TreeIterator<EObject> iterator = root.eAllContents();
        while (iterator.hasNext())
        {
            if (isCanceled(cancelIndicator))
                return;
            EObject element = iterator.next();
            if (element instanceof StaticFeatureAccess access && isImplicitVariableCreationSite(access))
            {
                highlightFeatureName(access, acceptor,
                    BslServerCallHighlightingConfiguration.IMPLICIT_VARIABLE_ID);
            }
        }
    }

    /**
     * Место создания неявной переменной: левая часть {@link SimpleStatement} с
     * непустой правой (есть присваивание, а не одиночный идентификатор) либо
     * переменная {@link ForStatement}. EDT вешает {@code ImplicitVariable} и на
     * {@code SimpleStatement} без {@code =} (вызов процедуры без скобок / набор
     * текста) — такие узлы не считаются созданием.
     */
    static boolean isImplicitVariableCreationSite(StaticFeatureAccess access)
    {
        if (access == null || access.getImplicitVariable() == null)
            return false;
        EObject parent = access.eContainer();
        if (parent instanceof SimpleStatement statement)
            return statement.getLeft() == access && statement.getRight() != null;
        if (parent instanceof ForStatement loop)
            return loop.getVariableAccess() == access;
        return false;
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
     * базовый стиль.
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
                acceptor.addPosition(node.getOffset(), node.getLength(), highlightingId);
        }
    }

    private boolean isCanceled(CancelIndicator cancelIndicator)
    {
        return cancelIndicator != null && cancelIndicator.isCanceled();
    }
}
