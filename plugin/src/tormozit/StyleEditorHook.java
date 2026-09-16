package tormozit;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.core.commands.operations.IUndoContext;
import org.eclipse.core.commands.operations.IUndoableOperation;
import org.eclipse.core.commands.operations.OperationHistoryEvent;
import org.eclipse.core.commands.operations.OperationHistoryFactory;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IPropertyListener;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.operations.model.IEditingContext;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;

/**
 * Редактор объекта «Стиль» (issue 530: признак «изменён» сразу после открытия).
 *
 * <p>Пока — только временная диагностика: кто и когда меняет контекст редактирования.
 */
public final class StyleEditorHook implements IStartup
{
    /** Редактор объекта метаданных «Стиль» (md.ui); страница «Стиль» в нём — из style.ui. */
    private static final String MD_EDITOR_ID = "com._1c.g5.v8.dt.md.ui.editor.style"; //$NON-NLS-1$
    /** Отдельный редактор файла стиля (style.ui). */
    private static final String STYLE_EDITOR_ID = "com._1c.g5.v8.dt.style.ui.editor"; //$NON-NLS-1$
    // #region agent log
    private static final String TEMP_LOG = "issue530-style-dirty"; //$NON-NLS-1$
    // #endregion

    private final Map<IWorkbenchWindow, Boolean> hookedWindows = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<IEditorPart, Boolean> hookedEditors = new WeakHashMap<>();

    @Override
    public void earlyStartup()
    {
        // #region agent log
        OperationHistoryFactory.getOperationHistory().addOperationHistoryListener(StyleEditorHook::onHistoryEvent);
        // #endregion
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
                hookWindow(window);
            workbench.addWindowListener(new IWindowListener()
            {
                @Override
                public void windowOpened(IWorkbenchWindow window) { hookWindow(window); }

                @Override
                public void windowActivated(IWorkbenchWindow window) { hookWindow(window); }

                @Override
                public void windowDeactivated(IWorkbenchWindow window) {}

                @Override
                public void windowClosed(IWorkbenchWindow window) { hookedWindows.remove(window); }
            });
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        if (window == null || hookedWindows.put(window, Boolean.TRUE) != null)
            return;
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref) { onPart(ref, "partOpened"); } //$NON-NLS-1$

            @Override
            public void partActivated(IWorkbenchPartReference ref) { onPart(ref, "partActivated"); } //$NON-NLS-1$
        });
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
            for (var ref : page.getEditorReferences())
                onPart(ref, "existing"); //$NON-NLS-1$
    }

    private void onPart(IWorkbenchPartReference ref, String phase)
    {
        if (ref == null || !(MD_EDITOR_ID.equals(ref.getId()) || STYLE_EDITOR_ID.equals(ref.getId())))
            return;
        IWorkbenchPart part = ref.getPart(false);
        if (!(part instanceof IEditorPart editor))
            return;
        // #region agent log
        tempLog(phase + " dirty=" + editor.isDirty() + " title=" + editor.getTitle()); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        if (hookedEditors.put(editor, Boolean.TRUE) != null)
            return;
        installDiagnostics(editor);
    }

    // #region agent log
    private static void onHistoryEvent(OperationHistoryEvent event)
    {
        IUndoableOperation op = event.getOperation();
        StringBuilder contexts = new StringBuilder();
        if (op != null)
            for (IUndoContext c : op.getContexts())
                contexts.append(c).append("; "); //$NON-NLS-1$
        tempLog("history type=" + event.getEventType() //$NON-NLS-1$
            + " label=" + (op == null ? null : op.getLabel()) //$NON-NLS-1$
            + " op=" + (op == null ? null : op.getClass().getName()) //$NON-NLS-1$
            + " contexts=[" + contexts + "]" //$NON-NLS-1$ //$NON-NLS-2$
            + " thread=" + Thread.currentThread().getName() //$NON-NLS-1$
            + "\n" + stack()); //$NON-NLS-1$
    }

    private static void installDiagnostics(IEditorPart editor)
    {
        String title = editor.getTitle();
        editor.addPropertyListener(new IPropertyListener()
        {
            @Override
            public void propertyChanged(Object source, int propId)
            {
                if (propId == IEditorPart.PROP_DIRTY)
                    tempLog("PROP_DIRTY dirty=" + editor.isDirty() + " title=" + title + "\n" + stack()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        });
        IEditingContext context = editor instanceof DtGranularEditor<?> granular ? granular.getApiEditingContext() : null;
        tempLog("installDiagnostics title=" + title + " context=" + context //$NON-NLS-1$ //$NON-NLS-2$
            + " contextDirty=" + (context == null ? "?" : String.valueOf(context.isDirty()))); //$NON-NLS-1$ //$NON-NLS-2$
        Display display = Display.getCurrent();
        for (int delay : new int[] { 0, 50, 200, 500, 1000, 3000 })
            display.timerExec(delay, () -> tempLog("timer+" + delay + " dirty=" + editor.isDirty() //$NON-NLS-1$ //$NON-NLS-2$
                + " contextDirty=" + (context == null ? "?" : String.valueOf(context.isDirty())) //$NON-NLS-1$ //$NON-NLS-2$
                + " pages=" + describePages(editor))); //$NON-NLS-1$
    }

    private static String describePages(IEditorPart editor)
    {
        Object pages = Global.getField(editor, "pages"); //$NON-NLS-1$
        if (!(pages instanceof Iterable<?> list))
            return String.valueOf(pages);
        StringBuilder sb = new StringBuilder("["); //$NON-NLS-1$
        for (Object page : list)
        {
            if (page == null)
                continue;
            sb.append(page.getClass().getSimpleName());
            Object dirty = Global.invoke(page, "isDirty"); //$NON-NLS-1$
            if (dirty != null)
                sb.append(" dirty=").append(dirty); //$NON-NLS-1$
            Object pageContext = Global.invoke(page, "getApiEditingContext"); //$NON-NLS-1$
            if (pageContext instanceof IEditingContext pc && pc != Global.invoke(editor, "getApiEditingContext")) //$NON-NLS-1$
                sb.append(" ownContextDirty=").append(pc.isDirty()); //$NON-NLS-1$
            sb.append("; "); //$NON-NLS-1$
        }
        return sb.append(']').toString();
    }

    private static String stack()
    {
        StringWriter sw = new StringWriter();
        new Throwable("stack").printStackTrace(new PrintWriter(sw)); //$NON-NLS-1$
        return sw.toString();
    }

    private static void tempLog(String text)
    {
        Global.tempLog(TEMP_LOG, text);
    }
    // #endregion
}
