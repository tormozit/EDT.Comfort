package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPart;
import org.osgi.framework.Bundle;

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
     * Делегат без состояния между вызовами: часть и выделение он каждый раз берёт заново
     * (активная часть workbench), а кадр стека — из контекста отладки. Поэтому один экземпляр
     * переиспользуется, а не создаётся на каждое нажатие (создание идёт через Guice).
     */
    private static Object delegate;

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
        Object inspectDelegate = resolveDelegate();
        if (frame == null || inspectDelegate == null)
            return null;

        // Разбор выражения — тот же метод делегата, что и у пункта меню: выделение, а при его
        // отсутствии слово под кареткой через подсказку отладки.
        Object watch = Global.invoke(inspectDelegate, "geSelectedExpression", frame); //$NON-NLS-1$
        IWatchExpression expression = watch instanceof IWatchExpression typed ? typed : null;
        if (expression == null)
        {
            // Каретка на пустом месте: слова нет, разбирать нечего. Штатное поведение — молча
            // ничего не делать; вместо этого открываем тот же попап с пустым выражением — у него
            // есть поле ввода с историей, то есть получается «вычислить выражение» с нуля.
            // Не через BslInspectSupport.newWatchExpression — он пустую строку отбрасывает.
            expression = DebugPlugin.getDefault().getExpressionManager()
                .newWatchExpression(EMPTY_EXPRESSION);
            if (expression == null)
                return null;
        }
        openPopup(inspectDelegate, expression, frame);
        return null;
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
     */
    private static synchronized Object resolveDelegate()
    {
        if (delegate != null)
            return delegate;
        try
        {
            Bundle bundle = Platform.getBundle(BUNDLE_DEBUG_UI);
            if (bundle == null)
                return null;
            Object plugin = Global.invoke(bundle.loadClass(CLASS_PLUGIN), "getDefault"); //$NON-NLS-1$
            Object injectorObj = Global.invoke(plugin, "getInjector"); //$NON-NLS-1$
            if (!(injectorObj instanceof com.google.inject.Injector injector))
                return null;
            // getInstance() имеет две одноаргументные перегрузки (Class и Key) — Global.invoke их
            // не различает, поэтому вызов типизированный (как в CreateDebuggerBreakpoints).
            delegate = injector.getInstance(bundle.loadClass(CLASS_DELEGATE));
            return delegate;
        }
        catch (Exception e)
        {
            DebugInspectorDebug.problem("inspect: resolveDelegate: " + e); //$NON-NLS-1$
            return null;
        }
    }
}
