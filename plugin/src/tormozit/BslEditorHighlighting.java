package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.Keyword;
import org.eclipse.xtext.ide.editor.syntaxcoloring.IHighlightedPositionAcceptor;
import org.eclipse.xtext.ide.editor.syntaxcoloring.ISemanticHighlightingCalculator;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.util.CancelIndicator;
import org.osgi.framework.Bundle;

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
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
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
 * <li>слова-ключи в роли имён членов после точки ({@code Запрос.Выполнить()}):
 * лексер красит их как ключевые слова всегда — перекрашиваются обычным текстом
 * {@link BslServerCallHighlightingConfiguration#KEYWORD_MEMBER_NAME_ID};
 * места, раскрашенные как серверные вызовы, не затрагиваются.</li>
 * </ul>
 */
public final class BslEditorHighlighting
    implements ISemanticHighlightingCalculator
{
    /** Штатный калькулятор EDT: из него берётся лексер запроса в BSL-строке. */
    private final Object nativeCalculator;

    BslEditorHighlighting(Object nativeCalculator)
    {
        this.nativeCalculator = nativeCalculator;
    }

    @Override
    public void provideHighlightingFor(XtextResource resource, IHighlightedPositionAcceptor acceptor,
        CancelIndicator cancelIndicator)
    {
        if (resource == null || acceptor == null || isCanceled(cancelIndicator) || resource.getParseResult() == null)
            return;

        EObject root = resource.getParseResult().getRootASTElement();
        if (root == null)
            return;

        highlightKeywordMemberNames(root, acceptor, cancelIndicator);

        boolean serverCalls = ComfortSettings.isServerCallHighlightingEnabled();
        boolean implicitVars = ComfortSettings.isImplicitVariableHighlightingEnabled();
        if (serverCalls)
            highlightServerCalls(root, acceptor, cancelIndicator);
        if (implicitVars && !isCanceled(cancelIndicator))
            highlightImplicitVariableCreations(root, acceptor, cancelIndicator);
        if (ComfortSettings.isQueryLiteralHighlightingEnabled() && !isCanceled(cancelIndicator))
            QueryLiterals.highlight(nativeCalculator, root, acceptor, cancelIndicator);
    }

    /**
     * Снимает ключевую окраску со слов-ключей в роли имён членов после точки
     * ({@code Запрос.Выполнить()}). Стиль без цвета — обычный текст; места
     * серверных вызовов пропускаются: их окраска важнее.
     */
    private void highlightKeywordMemberNames(EObject root, IHighlightedPositionAcceptor acceptor,
        CancelIndicator cancelIndicator)
    {
        boolean serverCalls = ComfortSettings.isServerCallHighlightingEnabled();
        TreeIterator<EObject> iterator = root.eAllContents();
        while (iterator.hasNext())
        {
            if (isCanceled(cancelIndicator))
                return;

            EObject element = iterator.next();
            if (!(element instanceof DynamicFeatureAccess access))
                continue;
            List<INode> nodes = NodeModelUtils.findNodesForFeature(access,
                BslPackage.Literals.FEATURE_ACCESS__NAME);
            if (!hasKeywordName(nodes))
                continue;
            if (serverCalls && isServerCallPainted(access))
                continue;
            for (INode node : nodes)
            {
                if (node.getLength() > 0)
                    acceptor.addPosition(node.getOffset(), node.getLength(),
                        BslServerCallHighlightingConfiguration.KEYWORD_MEMBER_NAME_ID);
            }
        }
    }

    /** Имя члена задано токеном-ключевым словом языка (Выполнить/Execute и т.п.). */
    private static boolean hasKeywordName(List<INode> nodes)
    {
        for (INode node : nodes)
        {
            if (isKeywordNode(node))
                return true;
        }
        return false;
    }

    /**
     * Узел имени члена — композит с {@code RuleCall}; ключевым слово делает
     * видимый лист-токен внутри узла.
     */
    private static boolean isKeywordNode(INode node)
    {
        if (node instanceof ICompositeNode composite)
        {
            for (ILeafNode leaf : composite.getLeafNodes())
            {
                if (!leaf.isHidden() && leaf.getGrammarElement() instanceof Keyword)
                    return true;
            }
            return false;
        }
        return node.getGrammarElement() instanceof Keyword;
    }

    /**
     * Имя принадлежит цепочке вызова, раскрашиваемой как серверный вызов,
     * см. {@link #highlightInvocation}: сам methodAccess или источник в его цепочке.
     */
    private static boolean isServerCallPainted(FeatureAccess access)
    {
        EObject cur = access;
        while (cur instanceof DynamicFeatureAccess)
        {
            EObject parent = cur.eContainer();
            if (parent instanceof Invocation invocation
                && invocation.getMethodAccess() == cur && invocation.isIsServerCall())
            {
                return true;
            }
            cur = parent;
        }
        return false;
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

    private static boolean isCanceled(CancelIndicator cancelIndicator)
    {
        return cancelIndicator != null && cancelIndicator.isCanceled();
    }

    /**
     * Раскраска текстов запросов в строковых литералах (issue #817): многострочный
     * литерал, первое слово которого {@code ВЫБРАТЬ}/{@code SELECT} или
     * {@code УНИЧТОЖИТЬ}/{@code DROP}, разбирается
     * штатным лексером EDT «запрос в BSL-строке» ({@code QlInBslTokenHighlighting}).
     * Его экземпляр внедрён в поле {@code qlInBslTokenHighlighting} штатного
     * {@code BslSemanticHighlightingCalculator}, но штатный вызов
     * ({@code computeStringLiteralHighlighting}) в EDT не используется. Позиции не
     * пересекаются: токены — {@link BslServerCallHighlightingConfiguration#queryTokenId},
     * промежутки (кавычки, «|», пробелы) —
     * {@link BslServerCallHighlightingConfiguration#QUERY_TEXT_ID}; у всех стилей общий фон.
     */
    private static final class QueryLiterals
    {
        private static final String TAG = "QueryLiteralHL"; //$NON-NLS-1$
        private static final String LEXER_FIELD = "qlInBslTokenHighlighting"; //$NON-NLS-1$
        private static final String QL_BSL_UI_BUNDLE = "com._1c.g5.v8.dt.ql.bsl.ui"; //$NON-NLS-1$
        private static final String LEXER_CLASS =
            "com._1c.g5.v8.dt.ql.bsl.ui.token.highlighting.impl.QlInBslTokenHighlighting"; //$NON-NLS-1$
        /** Id, который лексер отдаёт токенам без своего цвета (ключевые слова, знаки). */
        private static final String UNMAPPED_ID = Activator.PLUGIN_ID + ".query.unmapped"; //$NON-NLS-1$
        private static final String OTHERS_ID = "Ql_Others"; //$NON-NLS-1$

        private static volatile Object fallbackLexer;
        private static volatile boolean failureLogged;

        static void highlight(Object nativeCalculator, EObject root, IHighlightedPositionAcceptor acceptor,
            CancelIndicator cancelIndicator)
        {
            Object lexer = null;
            java.lang.reflect.Method update = null;
            java.lang.reflect.Method start = null;
            TreeIterator<EObject> iterator = root.eAllContents();
            while (iterator.hasNext())
            {
                if (isCanceled(cancelIndicator))
                    return;
                if (!(iterator.next() instanceof StringLiteral literal))
                    continue;
                INode node = NodeModelUtils.findActualNodeFor(literal);
                if (node == null)
                    continue;
                int offset = node.getOffset();
                int end = node.getEndOffset();
                String text = node.getRootNode().getText();
                if (end > text.length() || end - offset < 3 || text.charAt(offset) != '"')
                    continue;
                String content = text.substring(offset + 1,
                    text.charAt(end - 1) == '"' ? end - 1 : end);
                if (content.indexOf('\n') < 0 || !startsWithQueryWord(content))
                    continue;
                if (lexer == null)
                {
                    lexer = resolveLexer(nativeCalculator);
                    if (lexer == null)
                        return;
                    try
                    {
                        update = lexer.getClass().getMethod("updateSourceData", String.class, int.class); //$NON-NLS-1$
                        start = lexer.getClass().getMethod("startHighlighting", //$NON-NLS-1$
                            IHighlightedPositionAcceptor.class, String.class);
                    }
                    catch (ReflectiveOperationException e)
                    {
                        logFailure("methods missing", e); //$NON-NLS-1$
                        return;
                    }
                }
                List<int[]> tokens = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                try
                {
                    synchronized (lexer)
                    {
                        update.invoke(lexer, content, Integer.valueOf(offset + 1));
                        start.invoke(lexer, (IHighlightedPositionAcceptor)(tokenOffset, length, tokenIds) -> {
                            tokens.add(new int[] { tokenOffset, length });
                            ids.add(tokenIds.length > 0 ? tokenIds[0] : UNMAPPED_ID);
                        }, UNMAPPED_ID);
                    }
                }
                catch (ReflectiveOperationException | RuntimeException e)
                {
                    logFailure("lexer failed", e); //$NON-NLS-1$
                    return;
                }
                acceptLiteral(text, offset, end, tokens, ids, acceptor);
            }
        }

        /** Первое слово текста (после пробелов, «|» и комментариев) — ВЫБРАТЬ/SELECT или УНИЧТОЖИТЬ/DROP. */
        private static boolean startsWithQueryWord(String content)
        {
            int i = 0;
            int length = content.length();
            while (i < length)
            {
                char c = content.charAt(i);
                if (Character.isWhitespace(c) || c == '|')
                    i++;
                else if (c == '/' && i + 1 < length && content.charAt(i + 1) == '/')
                {
                    int lineEnd = content.indexOf('\n', i);
                    if (lineEnd < 0)
                        return false;
                    i = lineEnd + 1;
                }
                else
                    break;
            }
            return startsWithWord(content, i, "ВЫБРАТЬ") || startsWithWord(content, i, "SELECT") //$NON-NLS-1$ //$NON-NLS-2$
                || startsWithWord(content, i, "УНИЧТОЖИТЬ") || startsWithWord(content, i, "DROP"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static boolean startsWithWord(String content, int from, String word)
        {
            int to = from + word.length();
            return to <= content.length() && content.regionMatches(true, from, word, 0, word.length())
                && (to == content.length() || !Character.isLetterOrDigit(content.charAt(to))
                    && content.charAt(to) != '_');
        }

        /** Токены — стилями запроса, промежутки между ними — стилем текста запроса. */
        private static void acceptLiteral(String text, int offset, int end, List<int[]> tokens, List<String> ids,
            IHighlightedPositionAcceptor acceptor)
        {
            int position = offset;
            for (int i = 0; i < tokens.size(); i++)
            {
                int tokenOffset = tokens.get(i)[0];
                int tokenEnd = Math.min(end, tokenOffset + tokens.get(i)[1]);
                if (tokenOffset < position || tokenEnd <= tokenOffset)
                    continue;
                String qlId = qlId(text, tokenOffset, tokenEnd, ids.get(i));
                if (qlId == null)
                    continue;
                if (tokenOffset > position)
                    acceptor.addPosition(position, tokenOffset - position,
                        BslServerCallHighlightingConfiguration.QUERY_TEXT_ID);
                acceptor.addPosition(tokenOffset, tokenEnd - tokenOffset,
                    BslServerCallHighlightingConfiguration.queryTokenId(qlId));
                position = tokenEnd;
            }
            if (end > position)
                acceptor.addPosition(position, end - position, BslServerCallHighlightingConfiguration.QUERY_TEXT_ID);
        }

        /**
         * Стиль токена. Лексер даёт свой цвет литералам, числам, параметрам,
         * комментариям и «;», знакам и именам — {@code Ql_Others}, ключевым словам и
         * прочим знакам — переданный id без цвета. Пробелы и «|» — текст литерала ({@code null}).
         */
        private static String qlId(String text, int from, int to, String id)
        {
            char first = text.charAt(from);
            if (Character.isWhitespace(first) || first == '|' && to - from == 1)
                return null;
            if (!OTHERS_ID.equals(id) && !UNMAPPED_ID.equals(id))
                return id;
            if (Character.isLetterOrDigit(first) || first == '_')
                return UNMAPPED_ID.equals(id) ? "Ql_Keywords" : OTHERS_ID; //$NON-NLS-1$
            return first == '(' || first == ')' ? "Ql_Brackets" : "Ql_Operators"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        /**
         * Экземпляр из поля штатного калькулятора; если поля нет (другая версия EDT) —
         * собственный экземпляр того же класса из бандла {@code ql.bsl.ui}.
         */
        private static Object resolveLexer(Object nativeCalculator)
        {
            Object lexer = Global.getField(nativeCalculator, LEXER_FIELD);
            if (lexer != null)
                return lexer;
            Object fallback = fallbackLexer;
            if (fallback != null)
                return fallback;
            try
            {
                Bundle bundle = Platform.getBundle(QL_BSL_UI_BUNDLE);
                if (bundle == null)
                {
                    logFailure("bundle " + QL_BSL_UI_BUNDLE + " missing", null); //$NON-NLS-1$ //$NON-NLS-2$
                    return null;
                }
                fallback = bundle.loadClass(LEXER_CLASS).getDeclaredConstructor().newInstance();
                fallbackLexer = fallback;
                return fallback;
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                logFailure("lexer instance failed", e); //$NON-NLS-1$
                return null;
            }
        }

        private static void logFailure(String message, Throwable e)
        {
            if (failureLogged)
                return;
            failureLogged = true;
            Throwable cause = e != null && e.getCause() != null ? e.getCause() : e;
            Global.log(TAG, "[!] " + message //$NON-NLS-1$
                + (cause == null ? "" : ": " + cause.getClass().getSimpleName() + " " + cause.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }
}
