package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * «Показать в навигаторе» для выбранного поля «Объект.*» в дереве реквизитов формы (Ctrl+T).
 */
public final class FormAttributesShowInNavigatorHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        FormEditorHook.showSelectedMetadataAttributeInNavigator(editor);
        return null;
    }

    @Override
    public boolean isEnabled()
    {
        return FormEditorHook.shouldConsumeAttributesShowInNavigatorKey();
    }

    @Override
    public void setEnabled(Object evaluationContext)
    {
        setBaseEnabled(isEnabled());
    }
}
