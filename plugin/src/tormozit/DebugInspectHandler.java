package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IDebugMonitoringManager;

/**
 * Обработчик команды EDT «Инспектировать» ({@code com._1c.g5.v8.dt.debug.ui.commands.Inspect},
 * по умолчанию Shift+F9) для контекста редактора встроенного языка.
 *
 * <p>Штатное сочетание привязано к действию набора {@code com._1c.g5.v8.dt.debug.ui.actionSet},
 * у которого объявлено {@code enablesFor="+"} с {@code <objectClass ITextSelection>}. Доступность
 * такого действия считает {@code SelectionEnabler} по текущему выделению, поэтому при каретке
 * внутри слова (выделения нет) команда остаётся запрещённой: платформа даже не доходит до
 * делегата и завершает вызов как {@code notEnabled}. Пункт «Инспектировать» контекстного меню
 * редактора — отдельный экземпляр того же делегата, и он слово под кареткой берёт: при пустом
 * выделении {@code InspectActionDelegate.geSelectedExpression} переходит на подсказку отладки
 * ({@code BslDebugDispatchingEObjectTextHover}) в позиции каретки.
 *
 * <p>Этот обработчик объявлен на ту же команду с выражением {@code activeWhen} более высокого
 * приоритета (см. комментарий в {@code plugin.xml}) и переиспользует делегат EDT: разбор
 * выражения — его же {@code geSelectedExpression}, попап открывается теми же окном, привязкой и
 * менеджером мониторинга, что и в штатном {@code run()}. То есть клавиатурный путь работает
 * ровно как пункт меню — и с выделением, и по слову под кареткой. Своей логики разбора
 * выражения здесь нет намеренно: она должна оставаться одной и той же с меню.
 *
 * <p>Отличие одно: на пустом месте, где разбирать нечего и штатная команда молчит, попап всё
 * равно открывается — с пустым выражением. У него есть поле ввода с историей, так что это
 * рабочий сценарий «вычислить произвольное выражение», а не пустое окно.
 */
public final class DebugInspectHandler extends AbstractHandler
{
    private static final String BUNDLE_DEBUG_UI = "com._1c.g5.v8.dt.debug.ui"; //$NON-NLS-1$

    private static final String CLASS_PLUGIN =
        "com._1c.g5.v8.dt.internal.debug.ui.DebugUiPlugin"; //$NON-NLS-1$

    private static final String CLASS_DELEGATE =
        "com._1c.g5.v8.dt.internal.debug.ui.actions.InspectActionDelegate"; //$NON-NLS-1$

    /** Пустое выражение попапа: поле ввода открывается пустым, вычислять нечего. */
    private static final String EMPTY_EXPRESSION = ""; //$NON-NLS-1$

    /**
     * Инжектор бандла {@code debug.ui}: сам по себе он не меняется, поэтому кэшируется —
     * в отличие от делегата, см. {@link #createDelegate()}.
     */
    private static Object injector;

    /**
     * Доступность считаем в {@link #isEnabled()}, а не только в {@code setEnabled}: обёртка
     * {@code HandlerProxy} из {@code plugin.xml} спрашивает именно {@code isEnabled()}, а
     * {@code setEnabled(Object)} может не вызываться вовсе — тогда осталось бы значение по
     * умолчанию (включено), и Shift+F9 без остановки отладчика молча ничего не делал бы.
     */
    @Override
    public boolean isEnabled()
    {
        return activeBslStackFrame() != null;
    }

    @Override
    public void setEnabled(Object evaluationContext)
    {
        setBaseEnabled(activeBslStackFrame() != null);
    }

    @Override
    public Object execute(ExecutionEvent event)
    {
        IBslStackFrame frame = activeBslStackFrame();
        Object inspectDelegate = createDelegate();
        if (frame == null || inspectDelegate == null)
            return null;

        // Часть задаём явно, как это делает пункт контекстного меню (он приходит к делегату
        // через setActivePart). Без этого делегат опирается на DebugUiPlugin.getActivePart(),
        // то есть на состояние момента показа попапа, а не момента нажатия клавиш.
        IWorkbenchPart activePart = HandlerUtil.getActivePart(event);
        if (activePart != null)
            Global.invoke(inspectDelegate, "setActivePart", null, activePart); //$NON-NLS-1$

        String text = CaretExpressionResolver.resolve(activePart, inspectDelegate);

        IWatchExpression expression = text == null || text.isBlank()
            // Под кареткой пусто — открываем попап с пустым выражением: у него есть поле ввода
            // с историей, то есть получается «вычислить выражение» с нуля. Не через
            // BslInspectSupport.newWatchExpression — он пустую строку отбрасывает.
            ? DebugPlugin.getDefault().getExpressionManager().newWatchExpression(EMPTY_EXPRESSION)
            : BslInspectSupport.newWatchExpression(text);
        if (expression == null)
            return null;
        openPopup(inspectDelegate, expression, frame);
        return null;
    }

    /**
     * Разбор выражения под кареткой по модели BSL (Xtext), а не через подсказку отладки EDT.
     *
     * <p>Штатный {@code InspectActionDelegate} при пустом выделении спрашивает
     * {@code BslDebugDispatchingEObjectTextHover}. Логи показали, что регион она определяет верно
     * (слово под кареткой), а вот информацию по нему отдаёт то пустую, то от прошлого разбора —
     * в попап попадало постороннее выражение либо ничего. Поэтому берём выражение сами.
     *
     * <p>Правило — <b>минимальное целостное выражение</b>: узел AST под кареткой поднимается до
     * ближайшего {@link Expression}, и если это обращение к методу, берётся весь вызов со
     * скобками и аргументами. Цепочка {@code а.б.в} в модели вложенная, поэтому каретка на
     * {@code б} даёт {@code а.б}, а не всю цепочку.
     *
     * <p>Если под кареткой пусто (пробел, перевод строки, комментарий) — {@code null}: попап
     * открывается с пустым полем ввода.
     */
    private static final class CaretExpressionResolver
    {
        private CaretExpressionResolver()
        {
        }

        static String resolve(IWorkbenchPart part, Object inspectDelegate)
        {
            try
            {
                ITextSelection selection = textSelection(inspectDelegate);
                if (selection == null)
                    return null;
                if (selection.getLength() > 0)
                    return selection.getText();

                ITextViewer viewer = textViewer(part != null ? part : delegatePart(inspectDelegate));
                IDocument document = viewer == null ? null : viewer.getDocument();
                if (!(document instanceof IXtextDocument xtextDocument))
                    return null;

                int offset = caretOffset(document, selection.getOffset());
                int[] range = xtextDocument.readOnly(
                    (IUnitOfWork<int[], XtextResource>)resource -> expressionRange(resource, offset));
                if (range == null)
                    return null;
                return document.get(range[0], range[1]).trim();
            }
            catch (Exception e)
            {
                DebugInspectorDebug.problem("inspect: resolve: " + e); //$NON-NLS-1$
                return null;
            }
        }

        /**
         * Каретка сразу за словом ({@code ТекущаяДатаСеанса|}) — это всё ещё «внутри слова»:
         * узел под самим смещением там уже следующий токен. Сдвигаемся на символ влево только
         * если слева буква, цифра или подчёркивание, иначе после {@code ;} или пробела мы бы
         * подхватывали предыдущий оператор вместо пустого выражения.
         */
        private static int caretOffset(IDocument document, int offset) throws Exception
        {
            if (offset <= 0)
                return offset;
            char before = document.getChar(offset - 1);
            return Character.isLetterOrDigit(before) || before == '_' ? offset - 1 : offset;
        }

        private static int[] expressionRange(XtextResource resource, int offset)
        {
            IParseResult parseResult = resource == null ? null : resource.getParseResult();
            if (parseResult == null || parseResult.getRootNode() == null)
                return null;
            ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(parseResult.getRootNode(), offset);
            if (leaf == null || leaf.isHidden())
                return null;

            EObject element = leaf.getSemanticElement();
            while (element != null && !(element instanceof Expression))
                element = element.eContainer();
            if (element == null)
                return null;

            // Каретка в имени метода — берём весь вызов, а не одно имя.
            if (element.eContainer() instanceof Invocation invocation
                && invocation.getMethodAccess() == element)
            {
                element = invocation;
            }

            INode node = NodeModelUtils.findActualNodeFor(element);
            return node == null ? null : new int[] { node.getOffset(), node.getLength() };
        }

        private static ITextSelection textSelection(Object inspectDelegate)
        {
            Object selection = Global.invoke(inspectDelegate, "getTargetSelection"); //$NON-NLS-1$
            return selection instanceof ITextSelection text ? text : null;
        }

        private static IWorkbenchPart delegatePart(Object inspectDelegate)
        {
            Object part = Global.invoke(inspectDelegate, "getPart"); //$NON-NLS-1$
            return part instanceof IWorkbenchPart workbenchPart ? workbenchPart : null;
        }

        private static ITextViewer textViewer(IWorkbenchPart part)
        {
            Object viewer = part == null ? null : part.getAdapter(ITextViewer.class);
            return viewer instanceof ITextViewer text ? text : null;
        }
    }

    /**
     * Открывает штатный попап ровно так же, как это делает сам делегат в своём
     * {@code Display.asyncExec}: окно берётся у части делегата, точка привязки — у него же
     * ({@code getPopupAnchor(getStyledText(part))}), менеджер мониторинга — из его поля.
     */
    private static void openPopup(Object inspectDelegate, IWatchExpression expression,
        IBslStackFrame frame)
    {
        Display.getDefault().asyncExec(() ->
        {
            try
            {
                Object part = Global.invoke(inspectDelegate, "getPart"); //$NON-NLS-1$
                if (!(part instanceof IWorkbenchPart workbenchPart) || workbenchPart.getSite() == null)
                    return;
                Shell shell = workbenchPart.getSite().getShell();
                Object styledText = Global.invoke(inspectDelegate, "getStyledText", part); //$NON-NLS-1$
                Object anchor = Global.invoke(inspectDelegate, "getPopupAnchor", styledText); //$NON-NLS-1$
                Object manager = Global.getField(inspectDelegate, "monitoringManager"); //$NON-NLS-1$
                BslInspectSupport.openInspectPopup(shell, anchor instanceof Point point ? point : null,
                    expression, frame,
                    manager instanceof IDebugMonitoringManager typed ? typed : null);
            }
            catch (Exception e)
            {
                DebugInspectorDebug.problem("inspect: openPopup: " + e); //$NON-NLS-1$
            }
        });
    }

    /** Кадр стека BSL текущего контекста отладки — так же, как {@code InspectActionDelegate.getFrame()}. */
    private static IBslStackFrame activeBslStackFrame()
    {
        try
        {
            IAdaptable context = DebugUITools.getDebugContext();
            if (context == null)
                return null;
            Object frame = context.getAdapter(org.eclipse.debug.core.model.IStackFrame.class);
            return frame instanceof IBslStackFrame bslFrame ? bslFrame : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Делегат EDT создаётся через Guice-инжектор бандла {@code debug.ui}: у него инжектятся
     * подсказка отладки и менеджер мониторинга, без которых слово под кареткой не разобрать.
     *
     * <p><b>Каждый вызов — новый экземпляр.</b> Внутри делегата живёт подсказка отладки
     * {@code BslDebugDispatchingEObjectTextHover}, а она (как все {@code DispatchingEObjectTextHover}
     * Xtext) держит состояние предыдущего разбора между {@code getHoverRegion} и
     * {@code getHoverInfo2}. Переиспользованный экземпляр на новом месте отдавал выражение
     * прошлого вызова — в попап попадало постороннее выражение и его вычисление не запускалось.
     * Контекстное меню этой беды не знает именно потому, что получает свой экземпляр делегата.
     */
    private static Object createDelegate()
    {
        try
        {
            Bundle bundle = Platform.getBundle(BUNDLE_DEBUG_UI);
            if (bundle == null)
                return null;
            com.google.inject.Injector guice = resolveInjector(bundle);
            if (guice == null)
                return null;
            // getInstance() имеет две одноаргументные перегрузки (Class и Key) — Global.invoke их
            // не различает, поэтому вызов типизированный (как в CreateDebuggerBreakpoints).
            return guice.getInstance(bundle.loadClass(CLASS_DELEGATE));
        }
        catch (Exception e)
        {
            DebugInspectorDebug.problem("inspect: createDelegate: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static synchronized com.google.inject.Injector resolveInjector(Bundle bundle)
        throws ClassNotFoundException
    {
        if (injector instanceof com.google.inject.Injector cached)
            return cached;
        Object plugin = Global.invoke(bundle.loadClass(CLASS_PLUGIN), "getDefault"); //$NON-NLS-1$
        Object resolved = Global.invoke(plugin, "getInjector"); //$NON-NLS-1$
        injector = resolved;
        return resolved instanceof com.google.inject.Injector guice ? guice : null;
    }
}
