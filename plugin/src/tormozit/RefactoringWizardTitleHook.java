package tormozit;

import java.util.Set;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Полный путь к переименовываемому объекту в заголовке окна мастера рефакторинга — окна
 * «Переименование элемента» (например {@code Справочник.Валюты.Форма.ФормаСписка}).
 *
 * <p>Штатно LTK ставит в заголовок только общее «Переименование элемента»
 * ({@code RefactoringUIHelper.RenameWizardTitle}). Самого объекта в модели мастера нет: на этап
 * предпросмотра он не доносится ({@code IRefactoring.getTitle()} — это имя проекта, элементы
 * {@code IRefactoringItem} — произвольные строки операций), выбор объекта и нового имени происходит
 * раньше, в отдельном мелком диалоге/линк-редакторе.
 *
 * <p>Поэтому путь берётся в момент запуска команды переименования: {@link IExecutionListener} на
 * {@code ICommandService} в {@code preExecute} по контексту команды (выделение в навигаторе,
 * активный редактор МД/формы) вычисляет полное имя через {@link GetRef} и запоминает его вместе с
 * меткой времени. Открывшееся следом окно мастера дописывает это имя в {@code shell.setText}.
 * Резерв: если запомненного имени нет (переименование запущено не тем путём, что в списке), путь
 * берётся из активной части/выделения прямо на момент показа окна.
 *
 * <p>Для переименования локальной переменной или метода BSL «пути к объекту метаданных» нет — тогда
 * в заголовок попадает имя модуля либо ничего (заголовок остаётся штатным).
 *
 * <p>Суффикс «(Комфорт)» не ставится — это окно EDT (см. правила репозитория).
 */
public final class RefactoringWizardTitleHook
{
    /** Команды, после которых открывается мастер переименования (проверка дешёвая, в preExecute). */
    private static final Set<String> RENAME_COMMAND_IDS = Set.of(
        "com._1c.g5.v8.dt.commands.rename", //$NON-NLS-1$
        "com._1c.g5.v8.dt.refactoring.rename", //$NON-NLS-1$
        "com._1c.g5.v8.dt.bsl.ui.refactoring.RenameElement", //$NON-NLS-1$
        "com._1c.g5.v8.dt.dcs.ui.renameDataSet", //$NON-NLS-1$
        "com._1c.g5.v8.dt.erd.ui.commands.renameCommand", //$NON-NLS-1$
        "org.eclipse.xtext.ui.refactoring.RenameElement", //$NON-NLS-1$
        "org.eclipse.ui.edit.rename"); //$NON-NLS-1$

    /** Диалог мастера рефакторинга — проверка та же, что в соседних хуках этого окна. */
    private static final String DIALOG_NAME_PART_REFACTORING = "Refactoring"; //$NON-NLS-1$
    private static final String DIALOG_NAME_PART_DIALOG = "Dialog"; //$NON-NLS-1$

    private static final String SHELL_HANDLED_KEY = "tormozit.refactoringWizardTitleShell"; //$NON-NLS-1$

    /**
     * Полное имя переименовываемого элемента, положенное на {@code Shell} мастера — читает
     * {@link RefactoringPreviewHook} для колонки «Подходит» ({@code String}).
     */
    static final String SHELL_RENAME_FULL_NAME_KEY = "tormozit.refactoringWizardRenameFullName"; //$NON-NLS-1$

    /** Запомненное имя старше этого — не используем (мастер так и не открылся, запуск был другой). */
    private static final long MAX_STASH_AGE_MS = 180_000;

    /** Заголовок окно может выставить уже после SWT.Show — ждём его с повторами. */
    private static final int RETRY_DELAY_MS = 120;
    private static final int MAX_ATTEMPTS = 15;

    private static volatile String pendingFullName;
    private static volatile long pendingFullNameAt;

    private static boolean executionListenerInstalled;

    private RefactoringWizardTitleHook()
    {
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        installExecutionListener();
        display.addFilter(SWT.Show, RefactoringWizardTitleHook::handleShow);
    }

    private static void installExecutionListener()
    {
        if (executionListenerInstalled || !PlatformUI.isWorkbenchRunning())
            return;
        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        commandService.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
                if (commandId == null || !RENAME_COMMAND_IDS.contains(commandId))
                    return;
                // Любой запуск переименования перетирает прошлое имя — даже если сейчас вычислить
                // не удалось, старое (от другого объекта) подставлять нельзя.
                pendingFullName = resolveFullName(event);
                pendingFullNameAt = System.currentTimeMillis();
            }

            @Override public void postExecuteSuccess(String commandId, Object returnValue) {}
            @Override public void postExecuteFailure(String commandId, ExecutionException exception) {}
            @Override public void notHandled(String commandId, NotHandledException exception) {}
        });
        executionListenerInstalled = true;
    }

    private static void handleShow(Event event)
    {
        if (!(event.widget instanceof Shell shell) || shell.isDisposed())
            return;
        if (shell.getData(SHELL_HANDLED_KEY) != null || !isRefactoringWizardDialog(shell))
            return;
        shell.setData(SHELL_HANDLED_KEY, Boolean.TRUE);

        String fullName = freshPendingFullName();
        pendingFullName = null;
        if (fullName == null)
            fullName = resolveFullNameFromActiveContext();
        if (fullName == null || fullName.isBlank())
            return;
        shell.setData(SHELL_RENAME_FULL_NAME_KEY, fullName);

        String target = fullName;
        Display display = shell.getDisplay();
        display.asyncExec(() -> scheduleApplyTitle(shell, target, 0));
    }

    private static String freshPendingFullName()
    {
        String name = pendingFullName;
        if (name == null)
            return null;
        return System.currentTimeMillis() - pendingFullNameAt <= MAX_STASH_AGE_MS ? name : null;
    }

    private static void scheduleApplyTitle(Shell shell, String fullName, int attempt)
    {
        if (shell.isDisposed() || attempt >= MAX_ATTEMPTS)
            return;
        String current = shell.getText();
        if (current == null || current.isBlank())
        {
            shell.getDisplay().timerExec(RETRY_DELAY_MS, () -> scheduleApplyTitle(shell, fullName, attempt + 1));
            return;
        }
        if (!current.contains(fullName))
            shell.setText(current + " — " + fullName); //$NON-NLS-1$
    }

    private static boolean isRefactoringWizardDialog(Shell shell)
    {
        Object data = shell.getData();
        if (data == null)
            return false;
        String name = data.getClass().getName();
        return name.contains(DIALOG_NAME_PART_REFACTORING) && name.contains(DIALOG_NAME_PART_DIALOG);
    }

    /** Полное имя объекта по контексту команды переименования: меню → выделение → активная часть. */
    private static String resolveFullName(ExecutionEvent event)
    {
        if (event == null)
            return null;
        try
        {
            String fromMenu = fullNameFromSelection(HandlerUtil.getActiveMenuSelection(event));
            if (fromMenu != null)
                return fromMenu;
            String fromSelection = fullNameFromSelection(HandlerUtil.getCurrentSelection(event));
            if (fromSelection != null)
                return fromSelection;
            return fullNameFromPart(HandlerUtil.getActivePart(event));
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static String resolveFullNameFromActiveContext()
    {
        try
        {
            if (!PlatformUI.isWorkbenchRunning())
                return null;
            var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null || window.getActivePage() == null)
                return null;
            String fromSelection = fullNameFromSelection(window.getSelectionService().getSelection());
            if (fromSelection != null)
                return fromSelection;
            return fullNameFromPart(window.getActivePage().getActivePart());
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static String fullNameFromSelection(ISelection selection)
    {
        if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty())
            return null;
        for (Object element : structured.toList())
        {
            String name = element instanceof EObject model
                ? GetRef.eObjectToFullName(model)
                : GetRef.fullNameFromNavigatorElement(element);
            if (name != null && !name.isBlank())
                return name;
        }
        return null;
    }

    private static String fullNameFromPart(IWorkbenchPart part)
    {
        if (part == null)
            return null;
        String name = GetRef.getRefFromPart(part);
        return name != null && !name.isBlank() ? name : null;
    }
}
