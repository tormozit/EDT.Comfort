package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Команда «Инспектировать» в окне «Коллекция» (F2 по умолчанию).
 */
public final class ComfortCollectionInspectHandler extends AbstractHandler
{
    public static final String COMMAND_ID = "tormozit.collectionInspect"; //$NON-NLS-1$

    public static final String BINDING_CONTEXT_ID = "tormozit.collection.context"; //$NON-NLS-1$

    @Override
    public void setEnabled(Object evaluationContext)
    {
        setBaseEnabled(activeCollectionWindow() != null);
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        ComfortCollectionWindow window = windowFromEvent(event);
        if (window != null)
            window.inspectSelection();
        return null;
    }

    static ComfortCollectionWindow activeCollectionWindow()
    {
        ComfortCollectionWindow window = windowFromShell(activeShell());
        if (window != null && window.canInspectSelection())
            return window;
        return null;
    }

    private static ComfortCollectionWindow windowFromEvent(ExecutionEvent event)
    {
        ComfortCollectionWindow window = windowFromShell(HandlerUtil.getActiveShell(event));
        if (window != null)
            return window;
        return activeCollectionWindow();
    }

    private static ComfortCollectionWindow windowFromShell(Shell shell)
    {
        return CollectionWindowRegistry.windowForShell(shell);
    }

    private static Shell activeShell()
    {
        Display display = Display.getCurrent();
        if (display == null)
            display = Display.getDefault();
        return display != null ? display.getActiveShell() : null;
    }
}
