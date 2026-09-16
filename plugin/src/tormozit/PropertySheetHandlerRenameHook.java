package tormozit;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ltk.core.refactoring.CheckConditionsOperation;
import org.eclipse.ltk.core.refactoring.PerformRefactoringOperation;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import org.eclipse.ltk.core.refactoring.participants.RefactoringProcessor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.refactoring.IRenameRefactoringProvider;
import org.eclipse.xtext.ui.refactoring.impl.AbstractRenameProcessor;

import com._1c.g5.aef2.models.ChangeOrigin;
import com._1c.g5.aef2.models.IModel;
import com._1c.g5.aef2.models.IModelListener;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.ui.refactoring.BslRenameElementContext;
import com._1c.g5.v8.dt.form.model.CommandHandler;
import com._1c.g5.v8.dt.form.model.EventHandler;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.ui.properties.models.CommandActionModel;
import com._1c.g5.v8.dt.form.ui.properties.models.EventHandlerModel;

/**
 * Смена имени обработчика в панели «Свойства» формы (поле «Действие» команды, поля событий
 * элемента/формы) — вопрос «Изменить имя процедуры "X" на "Y"?», как в конфигураторе.
 * https://github.com/tormozit/EDT.Comfort/issues/528
 *
 * <p><b>Штатно</b> ({@code EventHandlerModel}/{@code CommandActionModel.getChange}, декомпилировано:
 * {@code .tmp/bundles/form-ui}) ввод нового имени меняет только привязку в форме; процедура
 * со старым именем остаётся в модуле, а новая создаётся позже — при переходе к обработчику.
 *
 * <p><b>Перехват.</b> При получении фокуса полем палитры берём его модель
 * ({@link PropertySheetControlInterop#modelForFocusedField}) и подписываемся на
 * {@link IModelListener#modelCommitted} (зовётся из {@code Model.commit()} после
 * {@code getChange().apply()}), запоминая прежнее имя. Вопрос задаётся асинхронно, если:
 * имена непустые и различаются; в модуле формы есть процедура со старым именем и нет с новым;
 * в форме не осталось других привязок к старому имени.
 *
 * <p><b>«Да»</b> — штатный рефакторинг переименования метода BSL без мастера
 * ({@code BslBmRenameRefactoringProvider}, контекст {@link BslRenameElementContext} без редактора —
 * контекстный URI обязан быть platform-URI, иначе {@code BslBmRenameElementProcessor.initialize}
 * падает на {@code null}). Вызовы в модуле переименовываются; участник формы
 * {@code FormElementHandlerRenameParticipant} привязок со старым именем уже не находит.
 * <b>«Нет»</b> — штатное поведение.
 */
public final class PropertySheetHandlerRenameHook implements IStartup
{
    private static final String LOG_TOPIC = "handler-rename-528"; //$NON-NLS-1$

    private static final Set<IModel> ATTACHED = Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> display.addFilter(SWT.FocusIn, PropertySheetHandlerRenameHook::onFocusIn));
    }

    private static void onFocusIn(Event event)
    {
        if (!(event.widget instanceof Control control))
            return;
        Display display = control.getDisplay();
        display.asyncExec(() ->
        {
            if (control.isDisposed())
                return;
            Object page = PropertySheetActivePropertyHook.resolvePageFromControl(control);
            if (page == null)
                return;
            Object model = PropertySheetControlInterop.modelForFocusedField(page);
            if (model instanceof EventHandlerModel || model instanceof CommandActionModel)
                attach((IModel) model);
        });
    }

    private static void attach(IModel model)
    {
        if (!ATTACHED.add(model))
            return;
        String initial = currentName(model);
        Global.tempLog(LOG_TOPIC, "attach " + model.getClass().getSimpleName() + " value=" + initial); //$NON-NLS-1$ //$NON-NLS-2$
        model.addModelListener(new CommitListener(initial));
    }

    private static String currentName(IModel model)
    {
        if (model instanceof EventHandlerModel m)
            return m.get();
        if (model instanceof CommandActionModel m)
            return m.get();
        return null;
    }

    private static final class CommitListener implements IModelListener
    {
        private String lastName;

        CommitListener(String initial)
        {
            lastName = initial;
        }

        @Override
        public void modelCommitted(IModel model)
        {
            String oldName = lastName;
            String newName = currentName(model);
            lastName = newName;
            Global.tempLog(LOG_TOPIC, "committed old=" + oldName + " new=" + newName); //$NON-NLS-1$ //$NON-NLS-2$
            if (isBlank(oldName) || isBlank(newName) || oldName.equalsIgnoreCase(newName))
                return;
            Display display = Display.getCurrent();
            if (display == null)
                display = Display.getDefault();
            display.asyncExec(() -> offerRename(model, oldName, newName));
        }

        @Override
        public void modelChanged(IModel model, ChangeOrigin origin)
        {
        }

        @Override
        public void modelOnline(IModel model)
        {
        }

        @Override
        public void modelOffline(IModel model)
        {
        }

        @Override
        public void modelDisposed(IModel model)
        {
            model.removeModelListener(this);
            ATTACHED.remove(model);
        }
    }

    private static void offerRename(IModel model, String oldName, String newName)
    {
        try
        {
            Form form;
            EObject selection;
            String eventName = null;
            if (model instanceof EventHandlerModel m)
            {
                form = m.getForm();
                selection = m.getSelection();
                eventName = m.getEvent() != null ? m.getEvent().getName() : null;
            }
            else if (model instanceof CommandActionModel m)
            {
                form = m.getForm();
                selection = m.getSelection();
            }
            else
                return;
            if (form == null)
            {
                Global.tempLog(LOG_TOPIC, "skip: no form"); //$NON-NLS-1$
                return;
            }
            Module module = resolveModule(form);
            if (module == null)
            {
                Global.tempLog(LOG_TOPIC, "skip: module not resolved"); //$NON-NLS-1$
                return;
            }
            Method oldMethod = findMethod(module, oldName);
            Method newMethod = findMethod(module, newName);
            int otherBindings = countOtherBindings(form, selection, eventName, oldName);
            Global.tempLog(LOG_TOPIC, "check oldMethod=" + (oldMethod != null) + " newMethod=" + (newMethod != null) //$NON-NLS-1$ //$NON-NLS-2$
                + " otherBindings=" + otherBindings + " moduleUri=" + uriOf(module)); //$NON-NLS-1$ //$NON-NLS-2$
            if (oldMethod == null || newMethod != null || otherBindings > 0)
                return;
            Shell shell = activeShell();
            String message = "Изменить имя процедуры \"" + oldMethod.getName() + "\" на \"" + newName //$NON-NLS-1$ //$NON-NLS-2$
                + "\"?\nИначе при переходе к обработчику будет создана процедура с именем \"" + newName + "\"."; //$NON-NLS-1$ //$NON-NLS-2$
            if (!MessageDialog.openQuestion(shell, Global.withPluginWindowTitle("Имя обработчика"), message)) //$NON-NLS-1$
                return;
            renameMethod(shell, oldMethod, newName);
        }
        catch (RuntimeException e)
        {
            Global.tempLog(LOG_TOPIC, "offerRename failed: " + e); //$NON-NLS-1$
        }
    }

    private static Module resolveModule(Form form)
    {
        Module module = form.getModule();
        if (module != null && module.eIsProxy())
        {
            EObject resolved = EcoreUtil.resolve(module, form);
            module = resolved instanceof Module m && !m.eIsProxy() ? m : null;
        }
        return module;
    }

    private static Method findMethod(Module module, String name)
    {
        for (Method method : module.allMethods())
        {
            if (method != null && name.equalsIgnoreCase(method.getName()))
                return method;
        }
        return null;
    }

    /**
     * Привязки формы к {@code name}, кроме редактируемой: у команды — её обработчик, у элемента —
     * обработчик редактируемого события (к моменту проверки он уже может носить новое имя,
     * а может ещё и нет). Прочие события того же элемента считаются.
     */
    private static int countOtherBindings(Form form, EObject selection, String eventName, String name)
    {
        int count = 0;
        for (Iterator<EObject> it = form.eAllContents(); it.hasNext();)
        {
            EObject object = it.next();
            if (object instanceof EventHandler handler)
            {
                if (!name.equalsIgnoreCase(handler.getName()))
                    continue;
                if (eventName != null && handler.eContainer() == selection && handler.getEvent() != null
                    && eventName.equals(handler.getEvent().getName()))
                    continue;
            }
            else if (object instanceof CommandHandler handler)
            {
                if (!name.equalsIgnoreCase(handler.getName()))
                    continue;
                if (eventName == null && selection instanceof FormCommand && EcoreUtil.isAncestor(selection, handler))
                    continue;
            }
            else
                continue;
            count++;
        }
        return count;
    }

    private static void renameMethod(Shell shell, Method method, String newName)
    {
        String problem = null;
        try
        {
            URI targetUri = EcoreUtil.getURI(method);
            Resource resource = method.eResource();
            URI contextUri = resource != null ? resource.getURI() : null;
            Global.tempLog(LOG_TOPIC, "rename target=" + targetUri + " context=" + contextUri); //$NON-NLS-1$ //$NON-NLS-2$
            if (contextUri == null || !contextUri.isPlatform())
                problem = "Модуль формы не найден в рабочей области."; //$NON-NLS-1$
            IResourceServiceProvider rsp = problem == null
                ? IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(contextUri) : null;
            IRenameRefactoringProvider provider = rsp != null ? rsp.get(IRenameRefactoringProvider.class) : null;
            if (problem == null && provider == null)
                problem = "Не найден механизм переименования встроенного языка."; //$NON-NLS-1$
            if (problem == null)
            {
                BslRenameElementContext context = new BslRenameElementContext(targetUri, method.eClass(), null, null,
                    contextUri, method, method);
                ProcessorBasedRefactoring refactoring = provider.getRenameRefactoring(context);
                RefactoringProcessor processor = refactoring != null ? refactoring.getProcessor() : null;
                if (!(processor instanceof AbstractRenameProcessor renameProcessor))
                    problem = "Не удалось подготовить переименование процедуры."; //$NON-NLS-1$
                else
                {
                    renameProcessor.setNewName(newName);
                    problem = perform(refactoring);
                }
            }
        }
        catch (Exception e)
        {
            Global.tempLog(LOG_TOPIC, "rename failed: " + e); //$NON-NLS-1$
            problem = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        if (problem != null)
        {
            Global.tempLog(LOG_TOPIC, "rename problem: " + problem); //$NON-NLS-1$
            MessageDialog.openError(shell, Global.withPluginWindowTitle("Имя обработчика"), //$NON-NLS-1$
                "Процедура не переименована.\n" + problem); //$NON-NLS-1$
        }
    }

    /** @return текст ошибки или {@code null} при успехе. */
    private static String perform(ProcessorBasedRefactoring refactoring) throws Exception
    {
        PerformRefactoringOperation operation =
            new PerformRefactoringOperation(refactoring, CheckConditionsOperation.ALL_CONDITIONS);
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        PlatformUI.getWorkbench().getProgressService().busyCursorWhile(monitor ->
        {
            try
            {
                workspace.run(operation, monitor);
            }
            catch (CoreException e)
            {
                throw new java.lang.reflect.InvocationTargetException(e);
            }
        });
        RefactoringStatus status = operation.getConditionStatus();
        Global.tempLog(LOG_TOPIC, "conditions=" + status + " validation=" + operation.getValidationStatus()); //$NON-NLS-1$ //$NON-NLS-2$
        if (status != null && status.hasFatalError())
            return status.getMessageMatchingSeverity(RefactoringStatus.FATAL);
        RefactoringStatus validation = operation.getValidationStatus();
        if (validation != null && validation.hasFatalError())
            return validation.getMessageMatchingSeverity(RefactoringStatus.FATAL);
        return null;
    }

    private static Shell activeShell()
    {
        Display display = Display.getCurrent();
        Shell shell = display != null ? display.getActiveShell() : null;
        if (shell == null && PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null)
            shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
        return shell;
    }

    private static String uriOf(EObject object)
    {
        Resource resource = object.eResource();
        return resource != null ? String.valueOf(resource.getURI()) : "null"; //$NON-NLS-1$
    }

    private static boolean isBlank(String s)
    {
        return s == null || s.isBlank();
    }
}
