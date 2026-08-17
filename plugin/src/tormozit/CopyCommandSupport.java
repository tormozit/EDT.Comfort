package tormozit;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.commands.ICommandService;

/**
 * Общий перехват Ctrl+C для виджетов Комфорт. На Win32 {@code SWT.KeyDown} букву не видит:
 * акселератор Edit → Copy уходит в {@code WM_COMMAND} до SWT.
 *
 * <p>Два канала, оба нужны:
 * <ul>
 * <li>диалоги без своего Copy — {@code ICommandService} {@code notHandled}/{@code postExecute*};</li>
 * <li>редактор/View — штатный обработчик {@code IActionBars} Copy реально выполняется и
 * перезаписывает буфер (список вкладок МД, сравнение конфигураций). Слушатель команды
 * этого не отменяет: подменяем global Copy, и при фокусе на нашем виджете штатный код
 * не зовётся.</li>
 * </ul>
 *
 * <p>Подмена {@code IActionBars} живёт только пока фокус на нашем виджете. Иначе
 * всегда включённый Copy перехватывает Ctrl+C у остальных полей той же части
 * (AEF-поля редактора МД, модуль, «История Git») — Win32 тогда не отдаёт клавишу
 * штатному контролу.
 *
 * <p>Любой новый {@code Table}/{@code List}/{@code Tree} в {@code plugin/src} — сразу
 * {@link #wireCopyOverride(Control)} (или {@code FormTableInteraction.install()}, он уже
 * вызывает этот метод). Внутри редактора EDT ни {@code List}, ни {@code Table} сами
 * не копируют: Ctrl+C обрабатывает global Copy редактора.
 */
public final class CopyCommandSupport
{
    private static final String EDIT_COPY_COMMAND_ID = "org.eclipse.ui.edit.copy"; //$NON-NLS-1$

    private static final Map<Control, Runnable> targets = new ConcurrentHashMap<>();

    private static final Map<IActionBars, IAction> wrappedBars = new WeakHashMap<>();

    private static boolean listenerInstalled;

    private static boolean focusFilterInstalled;

    private CopyCommandSupport()
    {
    }

    /**
     * Копирует выделенный текст {@code Table}/{@code List}/{@code Tree}/{@code StyledText}
     * при Ctrl+C.
     */
    public static void wireCopyOverride(Control control)
    {
        wireCopyOverride(control, () -> copyDefaultSelection(control));
    }

    /**
     * Подключает {@code copyAction} при фокусе на {@code control}. Внутри редактора также
     * подменяет global Copy, иначе штатный обработчик перезапишет буфер.
     * Саму подмену ставим только пока фокус на нашем виджете — иначе Ctrl+C
     * перехватывается у остальных полей активной части.
     */
    public static void wireCopyOverride(Control control, Runnable copyAction)
    {
        if (control == null || control.isDisposed() || copyAction == null)
            return;
        targets.put(control, copyAction);
        control.addDisposeListener(e ->
        {
            targets.remove(control);
            restoreCopyWrapperIfUnused();
        });
        installFocusTracker();
        installExecutionListener();
        if (isOurTargetFocused())
            installActivePartCopyWrapper();
    }

    private static void installActivePartCopyWrapper()
    {
        IWorkbenchPart part = activePart();
        if (part == null)
            return;
        IActionBars bars = actionBarsOf(part);
        if (bars == null)
            return;
        synchronized (wrappedBars)
        {
            if (wrappedBars.containsKey(bars))
                return;
            IAction original = bars.getGlobalActionHandler(ActionFactory.COPY.getId());
            if (original instanceof PartCopyWrapper)
            {
                wrappedBars.put(bars, original);
                return;
            }
            PartCopyWrapper wrapper = new PartCopyWrapper(original);
            wrappedBars.put(bars, original);
            bars.setGlobalActionHandler(ActionFactory.COPY.getId(), wrapper);
            bars.updateActionBars();
        }
    }

    /**
     * Следит за фокусом всего Display: подмена Copy только пока фокус на нашем виджете.
     * {@code FocusIn} приходит уже после смены {@code getFocusControl}, поэтому
     * достаточно одного фильтра, без слушателей на каждом контроле.
     */
    private static void installFocusTracker()
    {
        if (focusFilterInstalled)
            return;
        Display display = Display.getDefault();
        if (display == null)
            return;
        Listener listener = event ->
        {
            if (isOurTargetFocused())
                installActivePartCopyWrapper();
            else
                restoreCopyWrapperIfUnused();
        };
        display.addFilter(SWT.FocusIn, listener);
        focusFilterInstalled = true;
    }

    private static void restoreCopyWrapperIfUnused()
    {
        if (isOurTargetFocused())
            return;
        synchronized (wrappedBars)
        {
            if (isOurTargetFocused())
                return;
            for (IActionBars bars : new ArrayList<>(wrappedBars.keySet()))
            {
                IAction original = wrappedBars.remove(bars);
                if (bars == null)
                    continue;
                IAction current = bars.getGlobalActionHandler(ActionFactory.COPY.getId());
                if (current instanceof PartCopyWrapper)
                {
                    bars.setGlobalActionHandler(ActionFactory.COPY.getId(), original);
                    bars.updateActionBars();
                }
            }
        }
    }

    private static IWorkbenchPart activePart()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        return page == null ? null : page.getActivePart();
    }

    private static IActionBars actionBarsOf(IWorkbenchPart part)
    {
        IWorkbenchPartSite site = part.getSite();
        if (site instanceof IEditorSite editorSite)
            return editorSite.getActionBars();
        if (site instanceof IViewSite viewSite)
            return viewSite.getActionBars();
        return null;
    }

    private static void installExecutionListener()
    {
        if (listenerInstalled || PlatformUI.getWorkbench() == null)
            return;
        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        commandService.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
                // Намеренно пусто: в редакторе штатный Copy после preExecute перезаписывает буфер.
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
                handlePossibleCopy(commandId);
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception)
            {
                handlePossibleCopy(commandId);
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception)
            {
                handlePossibleCopy(commandId);
            }
        });
        listenerInstalled = true;
    }

    private static void handlePossibleCopy(String commandId)
    {
        if (!EDIT_COPY_COMMAND_ID.equals(commandId))
            return;
        tryCopyFocused();
    }

    /**
     * @return {@code true}, если скопировали текст нашего виджета
     */
    private static boolean tryCopyFocused()
    {
        Control target = focusedTarget();
        if (target == null)
            return false;
        Runnable action = targets.get(target);
        if (action == null)
            return false;
        action.run();
        return true;
    }

    private static boolean isOurTargetFocused()
    {
        return focusedTarget() != null;
    }

    private static Control focusedTarget()
    {
        Display display = Display.getCurrent();
        if (display == null)
            return null;
        Control focus = display.getFocusControl();
        if (focus == null)
            return null;
        for (Control c = focus; c != null && !c.isDisposed(); c = c.getParent())
        {
            if (targets.containsKey(c))
                return c;
        }
        return null;
    }

    private static void copyDefaultSelection(Control control)
    {
        String text = defaultSelectionText(control);
        if (text == null || text.isEmpty())
            return;
        Clipboard clipboard = new Clipboard(control.getDisplay());
        try
        {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            clipboard.dispose();
        }
    }

    private static String defaultSelectionText(Control control)
    {
        if (control instanceof Table table && !table.isDisposed())
        {
            int index = table.getSelectionIndex();
            if (index < 0)
                return null;
            TableItem item = table.getItem(index);
            int columns = table.getColumnCount();
            if (columns <= 1)
                return item.getText();
            StringBuilder builder = new StringBuilder();
            for (int col = 0; col < columns; col++)
            {
                String cell = item.getText(col);
                if (cell == null || cell.isEmpty())
                    continue;
                if (builder.length() > 0)
                    builder.append('\t');
                builder.append(cell);
            }
            return builder.length() == 0 ? null : builder.toString();
        }
        if (control instanceof List list && !list.isDisposed())
        {
            String[] selection = list.getSelection();
            return selection.length == 0 ? null : String.join("\n", selection); //$NON-NLS-1$
        }
        if (control instanceof Tree tree && !tree.isDisposed())
        {
            TreeItem[] selection = tree.getSelection();
            if (selection.length == 0)
                return null;
            StringBuilder builder = new StringBuilder();
            for (TreeItem item : selection)
            {
                String line = item.getText();
                if (line == null || line.isEmpty())
                    continue;
                if (builder.length() > 0)
                    builder.append('\n');
                builder.append(line);
            }
            return builder.length() == 0 ? null : builder.toString();
        }
        if (control instanceof StyledText styledText && !styledText.isDisposed())
        {
            String selection = styledText.getSelectionText();
            if (selection != null && !selection.isEmpty())
                return selection;
            String all = styledText.getText();
            return all == null || all.isEmpty() ? null : all;
        }
        return null;
    }

    private static final class PartCopyWrapper extends Action
    {
        private final IAction original;

        private PartCopyWrapper(IAction original)
        {
            this.original = original;
            if (original != null)
            {
                setText(original.getText());
                setToolTipText(original.getToolTipText());
                setImageDescriptor(original.getImageDescriptor());
                setActionDefinitionId(original.getActionDefinitionId());
            }
            setActionDefinitionId(ActionFactory.COPY.getCommandId());
        }

        @Override
        public boolean isEnabled()
        {
            if (isOurTargetFocused())
                return true;
            return original == null || original.isEnabled();
        }

        @Override
        public void run()
        {
            if (tryCopyFocused())
                return;
            if (original != null)
                original.run();
        }
    }
}
