package tormozit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.compare.CompareViewerPane;
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.Scheme;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.bindings.keys.KeyBinding;
import org.eclipse.jface.bindings.keys.ParseException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.keys.IBindingService;

import com._1c.g5.v8.dt.compare.ui.mergeviewer.IThreeSideTextMergeViewerProvider;
import com._1c.g5.v8.dt.compare.ui.mergeviewer.ThreeSideTextMergeViewerPanel;

/**
 * По умолчанию (Ctrl+F3 / Shift+F3) на {@code M1+F3}/{@code M2+F3} в контексте
 * {@code org.eclipse.xtext.ui.XtextEditorScope} уже претендует штатная команда EDT
 * ({@code ...ForwardBslFastSearchHandler}/{@code Back...}), из-за чего одновременный
 * системный (plugin.xml) биндинг {@link TextEditorFastSearchHandler} на то же сочетание приводит
 * к диалогу выбора команды вместо тихого выполнения.
 * <p>
 * Решение: один раз при старте программно создаём {@code USER}-привязку (тот же механизм,
 * которым страница настроек «Клавиши» сохраняет выбор пользователя) на наши команды
 * ({@code tormozit.TextEditorFastSearchForward}/{@code Back}) для тех же сочетаний
 * в контексте {@code XtextEditorScope} (редакторы BSL). USER-привязка однозначно побеждает
 * SYSTEM-привязку EDT без диалога, при этом остаётся обычной записью в «Клавиши» —
 * пользователь может там же переназначить или отключить её.
 * <p>
 * Для модального «Редактора запроса» (куда Eclipse Key Binding Service не доставляет события)
 * установлен глобальный {@link Display#addFilter SWT.KeyDown-фильтр} на Ctrl+F3.
 * <p>
 * В текстовых панелях окон сравнения (2-way {@link CompareViewerPane}, 3-way
 * {@link ThreeSideTextMergeViewerPanel}) штатная «Найти следующее» ({@code F3}) срабатывает
 * как команда, но не ищет: {@code FindNextAction} берёт {@code IFindReplaceTarget} у активной
 * workbench-части, а не у сфокусированной панели merge-вьюера. После срабатывания команды
 * (или по KeyDown, если команда не дошла) выполняется переход по буферу диалога «Найти/Заменить».
 */
public final class TextEditorFastSearchKeyBindingHook implements IStartup
{
    private static final String TAG = "FastSearch"; //$NON-NLS-1$
    private static final String CONTEXT_ID = "org.eclipse.xtext.ui.XtextEditorScope"; //$NON-NLS-1$
    private static final String SEQ_FORWARD = "M1+F3"; //$NON-NLS-1$
    private static final String SEQ_BACK = "M1+M2+F3"; //$NON-NLS-1$
    private static final String CMD_FIND_NEXT = "org.eclipse.ui.edit.findNext"; //$NON-NLS-1$
    private static final String CMD_FIND_PREVIOUS = "org.eclipse.ui.edit.findPrevious"; //$NON-NLS-1$

    private static StyledText pendingCompareFindWidget;
    private static Point pendingCompareFindSelection;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(TextEditorFastSearchKeyBindingHook::ensureBindings);
    }

    private static void ensureBindings()
    {
        try
        {
            IBindingService bindingService = PlatformUI.getWorkbench().getAdapter(IBindingService.class);
            ICommandService commandService = PlatformUI.getWorkbench().getAdapter(ICommandService.class);
            if (bindingService == null || commandService == null)
                return;

            Scheme activeScheme = bindingService.getActiveScheme();
            if (activeScheme == null)
                return;
            String schemeId = activeScheme.getId();

            Binding[] existing = bindingService.getBindings();
            if (existing == null)
                return;

            KeySequence forwardSeq = KeySequence.getInstance(SEQ_FORWARD);
            KeySequence backSeq = KeySequence.getInstance(SEQ_BACK);

            Command forwardCmd = commandService.getCommand("tormozit.TextEditorFastSearchForward"); //$NON-NLS-1$
            Command backCmd = commandService.getCommand("tormozit.TextEditorFastSearchBack"); //$NON-NLS-1$

            List<Binding> newBindings = new ArrayList<>(Arrays.asList(existing));
            ensureContextBindings(newBindings, forwardSeq, backSeq,
                forwardCmd, backCmd, schemeId, existing);
            bindingService.savePreferences(activeScheme, newBindings.toArray(new Binding[0]));
        }
        catch (ParseException e)
        {
            Global.log("TextEditorFastSearchKeyBindingHook: " + e); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            Global.log("TextEditorFastSearchKeyBindingHook: " + e); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            Global.log("TextEditorFastSearchKeyBindingHook: " + e); //$NON-NLS-1$
        }
        installDisplayFilter();
        installFindNextListener();
    }

    private static void ensureContextBindings(List<Binding> newBindings,
        KeySequence forwardSeq, KeySequence backSeq,
        Command forwardCmd, Command backCmd, String schemeId, Binding[] existing)
    {
        boolean forwardCustomized = hasUserBinding(existing, forwardSeq, schemeId);
        boolean backCustomized = hasUserBinding(existing, backSeq, schemeId);

        if (!forwardCustomized)
        {
            newBindings.add(new KeyBinding(
                forwardSeq, new ParameterizedCommand(forwardCmd, null),
                schemeId, CONTEXT_ID, null, null, null, Binding.USER));
        }
        if (!backCustomized)
        {
            newBindings.add(new KeyBinding(
                backSeq, new ParameterizedCommand(backCmd, null),
                schemeId, CONTEXT_ID, null, null, null, Binding.USER));
        }
    }

    /** Display-фильтр для модальных диалогов, куда не доходит Key Binding Service. */
    private static void installDisplayFilter()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.KeyDown, TextEditorFastSearchKeyBindingHook::handleDisplayKeyDown);
        Global.log(TAG, "installDisplayFilter"); //$NON-NLS-1$
    }

    private static void installFindNextListener()
    {
        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        commandService.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
                rememberCompareFind(commandId);
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
                completeCompareFind(commandId);
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception)
            {
                completeCompareFind(commandId);
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception)
            {
                completeCompareFind(commandId);
            }
        });
    }

    private static void handleDisplayKeyDown(Event event)
    {
        if (event.keyCode != SWT.F3)
            return;
        if ((event.stateMask & SWT.MOD1) == 0)
            return;

        boolean forward = (event.stateMask & SWT.MOD2) == 0;
        if (!(event.widget instanceof StyledText textWidget) || textWidget.isDisposed())
            return;

        TextEditorFastSearchHandler.executeSearch(textWidget, forward);
        event.doit = false;
    }

    /**
     * Текстовая панель 2-way ({@link CompareViewerPane}) или 3-way
     * ({@link ThreeSideTextMergeViewerPanel} / {@link IThreeSideTextMergeViewerProvider}).
     */
    private static boolean isTextCompareWidget(Control focus)
    {
        for (Control c = focus; c != null && !c.isDisposed(); c = c.getParent())
        {
            if (c instanceof CompareViewerPane || c instanceof ThreeSideTextMergeViewerPanel)
                return true;
        }
        return focus.getShell() != null && !focus.getShell().isDisposed()
            && focus.getShell().getData() instanceof IThreeSideTextMergeViewerProvider;
    }

    private static Boolean findNextForward(String commandId)
    {
        if (CMD_FIND_NEXT.equals(commandId))
            return Boolean.TRUE;
        if (CMD_FIND_PREVIOUS.equals(commandId))
            return Boolean.FALSE;
        return null;
    }

    private static void rememberCompareFind(String commandId)
    {
        Boolean forward = findNextForward(commandId);
        pendingCompareFindWidget = null;
        pendingCompareFindSelection = null;
        if (forward == null)
            return;
        Display display = Display.getCurrent();
        if (display == null)
            return;
        Control focus = display.getFocusControl();
        if (!(focus instanceof StyledText textWidget) || textWidget.isDisposed()
            || !isTextCompareWidget(textWidget))
            return;
        pendingCompareFindWidget = textWidget;
        pendingCompareFindSelection = textWidget.getSelectionRange();
    }

    /**
     * Штатная {@code FindNextAction} уже отработала (или нет обработчика). Если выделение
     * в панели сравнения не сдвинулось — ищем сами по буферу «Найти».
     */
    private static void completeCompareFind(String commandId)
    {
        Boolean forward = findNextForward(commandId);
        if (forward == null)
            return;
        StyledText textWidget = pendingCompareFindWidget;
        Point before = pendingCompareFindSelection;
        pendingCompareFindWidget = null;
        pendingCompareFindSelection = null;
        if (textWidget == null || textWidget.isDisposed())
        {
            Display display = Display.getCurrent();
            Control focus = display != null ? display.getFocusControl() : null;
            if (focus instanceof StyledText st && !st.isDisposed() && isTextCompareWidget(st))
                TextEditorFastSearchHandler.executeFindNextFromBuffer(st, forward.booleanValue());
            return;
        }
        Point after = textWidget.getSelectionRange();
        if (before != null && after != null && (after.x != before.x || after.y != before.y))
            return;
        TextEditorFastSearchHandler.executeFindNextFromBuffer(textWidget, forward.booleanValue());
    }

    /** Есть ли уже пользовательская (в т.ч. «не привязано») привязка на это сочетание в этом контексте. */
    private static boolean hasUserBinding(Binding[] bindings, KeySequence sequence, String schemeId)
    {
        for (Binding b : bindings)
        {
            if (b.getType() != Binding.USER)
                continue;
            if (!schemeId.equals(b.getSchemeId()))
                continue;
            if (!CONTEXT_ID.equals(b.getContextId()))
                continue;
            if (sequence.equals(b.getTriggerSequence()))
                return true;
        }
        return false;
    }
}
