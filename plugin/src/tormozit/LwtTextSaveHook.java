package tormozit;

import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListenerWithChecks;
import org.eclipse.core.commands.IHandler;
import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IPropertyListener;
import org.eclipse.ui.ISaveablePart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.aef2.models.IModel;
import com._1c.g5.aef2.models.ModelListener;

/**
 * Save из LWT-поля с отложенным вводом: применить поле, дождаться dirty и сохранить
 * редактор ровно одним вызовом doSave (issue 743). Готовность поля подтверждается
 * modelCommitted его модели, а не ранее установленной модифицированностью редактора.
 *
 * <p>LightText.sendTextChangedEvent — штатное уведомление по Enter/потере фокуса,
 * проверено в .tmp/bundles/lwt/LightText-listeners.javap-c.txt и LightText.javap-c.txt.
 * Оно синхронно проходит привязку StandardComponent$2.modelChanged, но уведомление
 * редактора о модифицированности может прийти позже. Ждём PROP_DIRTY, не блокируя UI.
 *
 * <p>Command.executeWithChecks читает handler ПОСЛЕ firePreExecute (проверено в
 * .tmp/bundles/eclipse-commands/Command.javap-c.txt). Подменяем обработчик только
 * текущего вызова: штатный Save не выполняется, затем исходный handler возвращается.
 */
public final class LwtTextSaveHook implements IStartup
{
    private static final String SAVE_COMMAND = "org.eclipse.ui.file.save"; //$NON-NLS-1$
    private static final String LWT_OVERLAY_DATA_KEY = "com._1c.g5.lwt.lwtOverlay"; //$NON-NLS-1$
    /** Только UI-поток; повторный Ctrl+S объединяется с уже ожидающим сохранением. */
    private static final Map<IEditorPart, PendingSave> PENDING = new IdentityHashMap<>();
    private static boolean installed;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            if (installed || display.isDisposed())
                return;
            ICommandService service = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (service == null)
                return;
            service.addExecutionListener(new SaveListener());
            installed = true;
        });
    }

    private static final class SaveListener implements IExecutionListenerWithChecks
    {
        @Override
        public void preExecute(String commandId, ExecutionEvent event)
        {
            if (!SAVE_COMMAND.equals(commandId))
                return;
            Display display = Display.getCurrent();
            if (display == null)
            {
                return;
            }
            IEditorPart editor = HandlerUtil.getActiveEditor(event);
            Control focus = display.getFocusControl();
            Object lightText = focus != null && !focus.isDisposed()
                ? focus.getData(LWT_OVERLAY_DATA_KEY) : null;
            Object text = Global.invoke(lightText, "getText"); //$NON-NLS-1$
            Object lastText = Global.invoke(lightText, "getLastNotifiedText"); //$NON-NLS-1$
            boolean changed = text instanceof String && lastText instanceof String
                && !Objects.equals(text, lastText);
            IModel fieldModel = changed ? modelForLightText(focus, lightText) : null;
            boolean pending = editor != null && PENDING.containsKey(editor);

            if (editor == null || (!changed && !pending))
                return;
            Command command = event.getCommand();
            if (command.getHandler() instanceof FieldSaveHandler)
                return;
            FieldSaveHandler replacement = new FieldSaveHandler(command, editor,
                changed ? lightText : null, fieldModel, display);
            command.setHandler(replacement);
        }

        @Override public void postExecuteSuccess(String commandId, Object returnValue)
        {
            outcome(commandId);
        }

        @Override public void postExecuteFailure(String commandId, ExecutionException exception)
        {
            outcome(commandId);
        }

        @Override public void notHandled(String commandId, NotHandledException exception)
        {
            outcome(commandId);
        }

        @Override public void notEnabled(String commandId, NotEnabledException exception)
        {
            outcome(commandId);
        }

        @Override public void notDefined(String commandId, NotDefinedException exception)
        {
            outcome(commandId);
        }

        private static void outcome(String commandId)
        {
            if (!SAVE_COMMAND.equals(commandId))
                return;
            Command command = PlatformUI.getWorkbench().getService(ICommandService.class).getCommand(commandId);
            if (command.getHandler() instanceof FieldSaveHandler replacement)
                replacement.restore();
        }
    }

    /** Обработчик одного вызова команды; штатному handler этот вызов не передаёт. */
    private static final class FieldSaveHandler extends AbstractHandler
    {
        private final Command command;
        private final IHandler original;
        private final IEditorPart editor;
        private final Object lightText;
        private final IModel fieldModel;
        private final Display display;
        private boolean executed;

        FieldSaveHandler(Command command, IEditorPart editor, Object lightText, IModel fieldModel, Display display)
        {
            this.command = command;
            this.original = command.getHandler();
            this.editor = editor;
            this.lightText = lightText;
            this.fieldModel = fieldModel;
            this.display = display;
        }

        @Override
        public Object execute(ExecutionEvent event) throws ExecutionException
        {
            if (executed)
            {
                return null;
            }
            executed = true;
            try
            {
                PendingSave pending = PENDING.get(editor);
                if (pending == null)
                {
                    pending = new PendingSave(editor, display);
                    PENDING.put(editor, pending);
                    pending.attach();
                }
                pending.commitField(lightText, fieldModel);
                return null;
            }
            finally
            {
                restore();
            }
        }

        void restore()
        {
            if (command.getHandler() == this)
            {
                command.setHandler(original);
            }
        }
    }

    private static final class PendingSave implements IPropertyListener, IPartListener2
    {
        private final IEditorPart editor;
        private final Display display;
        private final IWorkbenchPartReference editorReference;
        private final List<FieldCommitListener> fieldCommits = new ArrayList<>();
        private boolean committing;
        private boolean finished;

        PendingSave(IEditorPart editor, Display display)
        {
            this.editor = editor;
            this.display = display;
            this.editorReference = editor.getSite().getPage().getReference(editor);
        }

        void attach()
        {
            editor.addPropertyListener(this);
            editor.getSite().getPage().addPartListener(this);
        }

        void commitField(Object lightText, IModel fieldModel) throws ExecutionException
        {
            if (committing || finished)
            {
                return;
            }
            committing = true;
            try
            {
                if (lightText != null)
                {
                    if (fieldModel == null)
                        throw new IllegalStateException("Model for active LightText not found"); //$NON-NLS-1$
                    FieldCommitListener listener = new FieldCommitListener(this, fieldModel);
                    fieldCommits.add(listener);
                    fieldModel.addModelListener(listener);

                    if (!Global.invokeVoid(lightText, "sendTextChangedEvent")) //$NON-NLS-1$
                        throw new IllegalStateException("LightText.sendTextChangedEvent unavailable"); //$NON-NLS-1$
                }
            }
            catch (RuntimeException e)
            {
                finish();
                throw new ExecutionException("Не удалось применить значение поля перед сохранением", e);
            }
            finally
            {
                committing = false;
            }
            saveWhenDirty();
        }

        @Override
        public void propertyChanged(Object source, int propId)
        {
            if (propId != ISaveablePart.PROP_DIRTY || display.isDisposed())
                return;
            // Уведомление может прийти из потока движка и внутри commitField.
            display.asyncExec(this::saveWhenDirty);
        }

        private void saveWhenDirty()
        {
            if (finished || committing)
                return;
            boolean fieldsCommitted = !fieldCommits.isEmpty()
                && fieldCommits.stream().allMatch(listener -> listener.committed);
            boolean dirty = editor.isDirty();

            if (!fieldsCommitted || !dirty)
                return;
            // Снимаем ожидание ДО doSave: повторные события/команды не сохранят второй раз.
            finish();
            editor.doSave(new NullProgressMonitor());
        }

        private void finish()
        {
            if (finished)
                return;
            finished = true;
            PENDING.remove(editor);
            editor.removePropertyListener(this);
            for (FieldCommitListener listener : fieldCommits)
                listener.model.removeModelListener(listener);
            fieldCommits.clear();
            editor.getSite().getPage().removePartListener(this);
        }

        @Override public void partClosed(IWorkbenchPartReference ref)
        {
            if (ref == editorReference || ref.getPart(false) == editor)
            {
                finish();
            }
        }

        @Override public void partActivated(IWorkbenchPartReference ref) {}
        @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
        @Override public void partDeactivated(IWorkbenchPartReference ref) {}
        @Override public void partOpened(IWorkbenchPartReference ref) {}
        @Override public void partHidden(IWorkbenchPartReference ref) {}
        @Override public void partVisible(IWorkbenchPartReference ref) {}
        @Override public void partInputChanged(IWorkbenchPartReference ref) {}
    }

    /** Сигнал только от модели конкретного поля; устанавливается после применения IChange. */
    private static final class FieldCommitListener extends ModelListener
    {
        private final PendingSave pending;
        private final IModel model;
        private volatile boolean committed;

        FieldCommitListener(PendingSave pending, IModel model)
        {
            this.pending = pending;
            this.model = model;
        }

        @Override
        public void modelCommitted(IModel source)
        {
            if (source != model)
                return;
            committed = true;
            if (!pending.display.isDisposed())
                pending.display.asyncExec(pending::saveWhenDirty);
        }

        @Override
        public void modelDisposed(IModel source)
        {
            if (!committed && !pending.display.isDisposed())
                pending.display.asyncExec(pending::finish);
        }
    }

    /** Находим именно компонент редактора поля, а не родительский FieldComponent-контейнер. */
    private static IModel modelForLightText(Control focus, Object lightText)
    {
        Object page = PropertySheetActivePropertyHook.resolvePageFromControl(focus);
        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
        Object mapObject = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$

        if (!(mapObject instanceof Map<?, ?> views))
            return null;
        Object root = PropertySheetControlInterop.sceneRootComponent(scene);

        for (Map.Entry<?, ?> entry : views.entrySet())
        {
            Object nativeControl = Global.invoke(entry.getValue(), "getNativeControl"); //$NON-NLS-1$
            Object lightControl = PropertySheetControlInterop.lightControlFromView(entry.getValue());
            Object nativeContent = Global.invoke(nativeControl, "getContent"); //$NON-NLS-1$
            Object lightContent = Global.invoke(lightControl, "getContent"); //$NON-NLS-1$
            boolean matches = nativeControl == lightText || lightControl == lightText
                || nativeContent == lightText || lightContent == lightText;

            if (!matches)
                continue;
            IModel model = modelForViewModel(root, entry.getKey());
            if (model == null)
                model = modelForViewModel(Global.invoke(root, "getDefinitionComponent"), entry.getKey()); //$NON-NLS-1$

            if (model != null)
                return model;
        }

        return null;
    }

    private static IModel modelForViewModel(Object component, Object viewModel)
    {
        if (component == null)
            return null;
        for (Object ownViewModel : AefFieldFocus.existingViewModels(component))
        {
            if (ownViewModel == viewModel)
            {
                Object model = Global.invoke(component, "getModel"); //$NON-NLS-1$

                return model instanceof IModel fieldModel ? fieldModel : null;
            }
        }
        for (Object child : AefFieldFocus.existingComponents(component))
        {
            IModel model = modelForViewModel(child, viewModel);
            if (model != null)
                return model;
        }
        return null;
    }
}
