package tormozit.checks;

import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com.e1c.g5.v8.dt.check.BslDirectLocationIssue;
import com.e1c.g5.v8.dt.check.CheckComplexity;
import com.e1c.g5.v8.dt.check.DirectLocation;
import com.e1c.g5.v8.dt.check.ICheckParameters;
import com.e1c.g5.v8.dt.check.components.BasicCheck;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

import tormozit.ComfortCheckIds;
import tormozit.bslparser.BslParserHook;

/**
 * Проверка «Директива препроцессора разрывает оператор»: блок {@code #Если} или {@code #Удаление},
 * ветки которого содержат часть оператора (например, {@code ИначеЕсли} или только {@code Если} без
 * {@code КонецЕсли}).
 *
 * <p>Платформа такой код принимает, грамматика EDT — нет. {@link BslParserHook} скрывает от парсера
 * директивы такого блока, и код веток разбирается как обычный. Проблема ставится на открывающую
 * инструкцию: на {@code #Если … Тогда} — условие препроцессора EDT для этого кода не учитывает, и
 * замечания о доступности на клиенте/сервере внутри блока могут быть ложными; на {@code #Удаление} —
 * удалённый код EDT разбирает как действующий. Скрытая {@code #Вставка} проблемы не даёт: её код и
 * так действующий.
 *
 * <p>Скрытые директивы находятся в дереве узлов ({@link BslParserHook#findReportedHiddenDirectives}),
 * своего разбора проверка не делает.
 */
public class BslPreprocessorStraddleCheck
    extends BasicCheck<Object>
{
    public static final String CHECK_ID = ComfortCheckIds.BSL_PREPROCESSOR_STRADDLE;

    private static final String TITLE = "Директива препроцессора разрывает оператор"; //$NON-NLS-1$

    private static final String DESCRIPTION =
        "Блок #Если … #КонецЕсли или #Удаление … #КонецУдаления, ветки которого содержат часть оператора:" //$NON-NLS-1$
            + " например, ИначеЕсли или Если без КонецЕсли. Платформа такой код принимает, а грамматика EDT" //$NON-NLS-1$
            + " считает его синтаксической ошибкой. Комфорт скрывает от разбора директивы этого блока, и код" //$NON-NLS-1$
            + " веток проверяется как обычный: для #Если — без учёта условия препроцессора, для #Удаление —" //$NON-NLS-1$
            + " как действующий, хотя в расширении он удалён."; //$NON-NLS-1$

    private static final String MESSAGE_IF =
        "Директива препроцессора разрывает оператор: EDT разбирает код блока без учёта её условия"; //$NON-NLS-1$

    private static final String MESSAGE_DELETE =
        "Директива препроцессора разрывает оператор: EDT разбирает удалённый код блока как действующий"; //$NON-NLS-1$

    @Override
    public String getCheckId()
    {
        return CHECK_ID;
    }

    @Override
    protected void configureCheck(CheckConfigurer builder)
    {
        builder.title(TITLE)
            .description(DESCRIPTION)
            .complexity(CheckComplexity.NORMAL)
            .severity(IssueSeverity.MINOR)
            .issueType(IssueType.WARNING)
            .module()
            .checkedObjectType(BslPackage.Literals.MODULE);
    }

    @Override
    protected void check(Object object, ResultAcceptor resultAceptor, ICheckParameters parameters,
        IProgressMonitor monitor)
    {
        if (monitor.isCanceled() || !(object instanceof Module module))
            return;
        Resource resource = module.eResource();
        if (!(resource instanceof XtextResource xtextResource))
            return;
        IParseResult parseResult = xtextResource.getParseResult();
        ICompositeNode root = parseResult != null ? parseResult.getRootNode() : null;
        List<int[]> instructions;
        String uri = String.valueOf(xtextResource.getURI());
        try
        {
            instructions = BslParserHook.findReportedHiddenDirectives(root);
        }
        catch (NoClassDefFoundError e)
        {
            // Бандл хука не установлен (зависимость необязательная) — скрытых директив и быть не может.
            tormozit.Global.tempLog("bsl-preproc-straddle", "проверка: нет класса хука, " + uri + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return;
        }
        // Временная диагностика: вызов проверки и сколько инструкций найдено.
        tormozit.Global.tempLog("bsl-preproc-straddle", "проверка: " + uri + ", корень " //$NON-NLS-1$ //$NON-NLS-2$
            + (root != null) + ", найдено " + instructions.size()); //$NON-NLS-1$
        for (int[] instr : instructions)
        {
            DirectLocation location = new DirectLocation(Integer.valueOf(instr[0]), Integer.valueOf(instr[1]),
                Integer.valueOf(instr[2]), causer(root, instr[0], module));
            String message = instr[3] == BslParserHook.KIND_DELETE ? MESSAGE_DELETE : MESSAGE_IF;
            resultAceptor.addIssue(new BslDirectLocationIssue(message, location));
        }
    }

    /**
     * Объект-виновник — внутри модуля, не сам модуль: проблемы на объекте из BM
     * {@code CheckExecutor} отбрасывает (см. {@code BslAstTruncationCheck.causer}).
     */
    private static EObject causer(ICompositeNode root, int offset, Module module)
    {
        ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(root, offset);
        EObject semantic = leaf != null ? NodeModelUtils.findActualSemanticObjectFor(leaf) : null;
        if (semantic != null && semantic != module)
            return semantic;
        List<Method> methods = module.allMethods();
        for (Method method : methods)
        {
            ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
            if (node != null && node.getTotalOffset() <= offset && offset < node.getTotalEndOffset())
                return method;
        }
        if (!methods.isEmpty())
            return methods.get(0);
        return module.eContents().isEmpty() ? null : module.eContents().get(0);
    }
}
