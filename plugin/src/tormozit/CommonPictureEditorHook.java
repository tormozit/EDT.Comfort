package tormozit;

import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.jface.wizard.IWizard;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

import com._1c.g5.v8.dt.common.ui.controls.ImageViewer;

/**
 * Редактор и мастер общей картинки: единый {@link PictureFieldEnhance} на каждом
 * {@link ImageViewer}, refresh превью после «Загрузить из файла».
 */
public final class CommonPictureEditorHook implements IStartup
{
    private static final String UPLOAD_COMMAND =
        "com._1c.g5.v8.dt.md.ui.commands.commonPictureFileUpload"; //$NON-NLS-1$
    private static final String CLEAR_COMMAND =
        "com._1c.g5.v8.dt.md.ui.commands.commonPictureClean"; //$NON-NLS-1$
    private static final String EDITOR_CLASS =
        "com._1c.g5.v8.dt.internal.md.ui.editors.commonpicture.CommonPictureEditor"; //$NON-NLS-1$
    private static final String WIZARD_CLASS =
        "com._1c.g5.v8.dt.md.ui.wizards.CommonPictureWizard"; //$NON-NLS-1$
    private static final String WIZARD_SHELL_KEY = "tormozit.commonPictureWizardEnhance"; //$NON-NLS-1$
    private static final String LOG_TAG = "CommonPictureEditorHook"; //$NON-NLS-1$

    private final Map<IWorkbenchWindow, IPartListener2> partListeners = new WeakHashMap<>();

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            PictureFieldEnhance.ensureGlobalFilters(Display.getDefault());
            installCommandListener();
            installImageViewerFilter(Display.getDefault());
            installPartListeners();
        });
    }

    private void installCommandListener()
    {
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commands == null)
            return;
        commands.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event) {}

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
                if (!UPLOAD_COMMAND.equals(commandId) && !CLEAR_COMMAND.equals(commandId))
                    return;
                Display.getDefault().asyncExec(() -> refreshActivePictureEditor(commandId));
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception) {}

            @Override
            public void notHandled(String commandId, NotHandledException exception) {}
        });
    }

    private static void refreshActivePictureEditor(String commandId)
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return;
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return;
        IEditorPart editor = page.getActiveEditor();
        if (editor == null || !EDITOR_CLASS.equals(editor.getClass().getName()))
            return;

        Display display = Display.getDefault();
        for (int delay : new int[] { 50, 200, 500, 1000 })
            display.timerExec(delay, () -> forceRefreshViewers(editor));
    }

    private static void forceRefreshViewers(IEditorPart editor)
    {
        if (editor == null)
            return;
        Control root = editor.getAdapter(Control.class);
        if (root == null)
        {
            Object partControl = Global.invoke(editor, "getPartControl"); //$NON-NLS-1$
            if (partControl instanceof Control c)
                root = c;
        }
        if (root == null && editor.getSite() != null)
            root = editor.getSite().getShell();
        if (root != null)
            refreshImageViewersUnder(root);
    }

    private static void refreshImageViewersUnder(Control root)
    {
        if (root == null || root.isDisposed())
            return;
        if (root instanceof ImageViewer viewer)
        {
            try
            {
                Object url = Global.getField(viewer, "currentUrl"); //$NON-NLS-1$
                if (url instanceof java.net.URL u)
                    viewer.loadImage(u);
                viewer.refresh();
                PictureFieldEnhance.install(viewer);
                PictureFieldEnhance.notifyImageChanged(viewer);
            }
            catch (Exception ex)
            {
                Global.logError(LOG_TAG, "refresh viewer", ex); //$NON-NLS-1$
            }
        }
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
                refreshImageViewersUnder(child);
        }
    }

    /**
     * ImageViewer.Show + мастер «Новая общая картинка» (не workbench-part):
     * shell IWizardContainer; после выбора файла AEF может пересобрать превью —
     * install() идемпотентно переподключает FX.
     */
    private static void installImageViewerFilter(Display display)
    {
        // Show приходит только при смене видимости, не при создании: в редакторе общей
        // картинки AEF строит поле уже после partOpened/partActivated, и без Resize
        // подключение ждало клика в поле. Первый Resize получает любой контрол при раскладке.
        display.addFilter(SWT.Resize, event ->
        {
            if (event.widget instanceof ImageViewer viewer && !PictureFieldEnhance.isInstalled(viewer))
                PictureFieldEnhance.install(viewer);
        });
        display.addFilter(SWT.Show, event ->
        {
            if (event.widget instanceof ImageViewer viewer && !viewer.isDisposed())
                PictureFieldEnhance.install(viewer);
            else if (event.widget instanceof Control child && !child.isDisposed()
                && child.getParent() instanceof ImageViewer viewer)
                PictureFieldEnhance.install(viewer);
            else if (event.widget instanceof Shell shell)
                scheduleWizardEnhance(display, shell, 0);
        });
        display.addFilter(SWT.Activate, event ->
        {
            if (event.widget instanceof Shell shell)
                scheduleWizardEnhance(display, shell, 0);
        });
    }

    private static void scheduleWizardEnhance(Display display, Shell shell, int attempt)
    {
        if (shell == null || shell.isDisposed())
            return;
        // Ключ: null — мастер ещё не опознан, FALSE — чужой (больше не проверяем),
        // TRUE — наш. Для нашего installRecursive зовётся на каждый Show/Activate:
        // после выбора файла AEF пересобирает превью, а install() идемпотентен.
        Object marked = shell.getData(WIZARD_SHELL_KEY);
        if (Boolean.FALSE.equals(marked))
            return;
        if (attempt == 0 && !(shell.getData() instanceof IWizardContainer))
            return;

        int delay = attempt == 0 ? 40 : 120;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed() || Boolean.FALSE.equals(shell.getData(WIZARD_SHELL_KEY)))
                return;

            IWizard wizard = resolveWizard(shell);
            if (wizard == null)
            {
                if (shell.getData() instanceof IWizardContainer && attempt < 20)
                    scheduleWizardEnhance(display, shell, attempt + 1);
                return;
            }
            if (!isCommonPictureWizard(wizard))
            {
                shell.setData(WIZARD_SHELL_KEY, Boolean.FALSE);
                return;
            }

            shell.setData(WIZARD_SHELL_KEY, Boolean.TRUE);
            PictureFieldEnhance.installRecursive(shell);
            if (countImageViewers(shell) == 0 && attempt < 25)
                scheduleWizardEnhance(display, shell, attempt + 1);
        });
    }

    private static IWizard resolveWizard(Shell shell)
    {
        Object data = shell.getData();
        if (!(data instanceof IWizardContainer container))
            return null;
        IWizardPage page = container.getCurrentPage();
        return page != null ? page.getWizard() : null;
    }

    private static boolean isCommonPictureWizard(IWizard wizard)
    {
        if (wizard == null)
            return false;
        for (Class<?> c = wizard.getClass(); c != null; c = c.getSuperclass())
        {
            if (WIZARD_CLASS.equals(c.getName()))
                return true;
        }
        return false;
    }

    private static int countImageViewers(Control root)
    {
        if (root == null || root.isDisposed())
            return 0;
        int n = root instanceof ImageViewer ? 1 : 0;
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
                n += countImageViewers(child);
        }
        return n;
    }

    private void installPartListeners()
    {
        IWorkbench workbench = PlatformUI.getWorkbench();
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            hookWindow(window);
        workbench.addWindowListener(new org.eclipse.ui.IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window) { hookWindow(window); }

            @Override
            public void windowClosed(IWorkbenchWindow window)
            {
                IPartListener2 pl = partListeners.remove(window);
                if (pl != null)
                    window.getPartService().removePartListener(pl);
            }

            @Override
            public void windowActivated(IWorkbenchWindow window) {}

            @Override
            public void windowDeactivated(IWorkbenchWindow window) {}
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        if (window == null || partListeners.containsKey(window))
            return;
        IPartListener2 listener = new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference partRef)
            {
                scheduleEnhance(partRef);
            }

            @Override
            public void partActivated(IWorkbenchPartReference partRef)
            {
                scheduleEnhance(partRef);
            }
        };
        partListeners.put(window, listener);
        window.getPartService().addPartListener(listener);
    }

    private static void scheduleEnhance(IWorkbenchPartReference partRef)
    {
        if (partRef == null)
            return;
        String id = partRef.getId();
        if (id == null || !id.toLowerCase().contains("commonpicture")) //$NON-NLS-1$
            return;
        Display.getDefault().asyncExec(() ->
        {
            try
            {
                Object part = partRef.getPart(false);
                if (!(part instanceof IEditorPart editor))
                    return;
                forceRefreshViewers(editor);
                Control control = editor.getAdapter(Control.class);
                if (control != null)
                    PictureFieldEnhance.installRecursive(control);
                else if (editor.getSite() != null)
                    PictureFieldEnhance.installRecursive(editor.getSite().getShell());
            }
            catch (Exception ex)
            {
                Global.logError(LOG_TAG, "part enhance", ex); //$NON-NLS-1$
            }
        });
    }
}
