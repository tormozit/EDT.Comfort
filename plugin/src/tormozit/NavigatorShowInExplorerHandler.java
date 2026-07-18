package tormozit;

import java.io.File;
import java.io.IOException;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.navigator.CommonNavigator;

/**
 * Открывает проводник ОС на файле или папке, ближайших к выбранному элементу навигатора EDT.
 */
public class NavigatorShowInExplorerHandler extends AbstractHandler
{
    @Override
    public void setEnabled(Object evaluationContext)
    {
        super.setEnabled(evaluationContext);
        if (!isHandled())
            return;
        setBaseEnabled(NavigatorResourceResolver.resolveFirst(navigatorSelection()) != null);
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        showInExplorer(menuSelection(event), HandlerUtil.getActiveShell(event));
        return null;
    }

    public static void showInExplorer(IStructuredSelection selection, Shell shell)
    {
        IResource resource = NavigatorResourceResolver.resolveFirst(selection);
        if (resource == null)
        {
            MessageDialog.openInformation(shell, "Комфорт", //$NON-NLS-1$
                    "Не удалось определить файл или папку для выбранного элемента."); //$NON-NLS-1$
            return;
        }
        File location = resource.getLocation() != null ? resource.getLocation().toFile() : null;
        if (location == null)
        {
            MessageDialog.openInformation(shell, "Комфорт", //$NON-NLS-1$
                    "Не удалось определить файл или папку для выбранного элемента."); //$NON-NLS-1$
            return;
        }
        showInExplorer(location, resource.getType() == IResource.FILE, shell);
    }

    /** Открыть проводник ОС на файле (с выделением) или папке. */
    public static void showInExplorer(File file, Shell shell)
    {
        if (file == null)
        {
            MessageDialog.openInformation(shell, "Комфорт", //$NON-NLS-1$
                    "Не удалось определить файл или папку."); //$NON-NLS-1$
            return;
        }
        showInExplorer(file, file.isFile() || !file.isDirectory(), shell);
    }

    private static void showInExplorer(File file, boolean selectAsFile, Shell shell)
    {
        try
        {
            File target = file;
            if (selectAsFile && !target.exists())
            {
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory())
                    parent.mkdirs();
                if (!target.exists())
                    target.createNewFile();
            }
            launchSystemExplorer(target, selectAsFile && target.isFile());
        }
        catch (IOException e)
        {
            Global.log("NavigatorShowInExplorerHandler: " + e); //$NON-NLS-1$
            MessageDialog.openInformation(shell, "Комфорт", //$NON-NLS-1$
                    "Не удалось открыть проводник ОС для выбранного элемента."); //$NON-NLS-1$
        }
    }

    private static void launchSystemExplorer(File file, boolean selectFile) throws IOException
    {
        String path = file.getCanonicalPath();
        String os = Platform.getOS();

        if (Platform.OS_WIN32.equals(os))
        {
            if (selectFile)
                new ProcessBuilder("explorer.exe", "/select," + quoteWindowsPath(path)).start(); //$NON-NLS-1$ //$NON-NLS-2$
            else
                new ProcessBuilder("explorer.exe", path).start(); //$NON-NLS-1$
            return;
        }
        if (Platform.OS_MACOSX.equals(os))
        {
            new ProcessBuilder("open", "-R", path).start(); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        String openPath = selectFile ? file.getParent() : path;
        if (openPath != null)
            new ProcessBuilder("xdg-open", openPath).start(); //$NON-NLS-1$
    }

    /** Как в {@code ShowInSystemExplorerHandler}: путь с пробелами — в кавычках после {@code /select,}. */
    private static String quoteWindowsPath(String path)
    {
        if (path == null || path.indexOf(' ') < 0) //$NON-NLS-1$
            return path;
        return '"' + path + '"';
    }

    private static IStructuredSelection menuSelection(ExecutionEvent event)
    {
        ISelection selection = HandlerUtil.getActiveMenuSelection(event);
        if (selection instanceof IStructuredSelection structured && !structured.isEmpty())
            return structured;
        return HandlerUtil.getCurrentStructuredSelection(event);
    }

    private static IStructuredSelection navigatorSelection()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return null;
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return null;
        IViewPart view = page.findView(Global.NAVIGATOR_VIEW_ID);
        if (!(view instanceof CommonNavigator))
            return null;
        ISelection selection = view.getSite().getSelectionProvider().getSelection();
        return selection instanceof IStructuredSelection structured ? structured : null;
    }
}
