package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.core.platform.IDtProject;

public class HistoryViewHandler extends AbstractHandler
{
    public static final String COMMAND_ID = "tormozit.history.openInIr";

    public static final String EGIT_HISTORY_VIEW_ID = "org.eclipse.egit.ui.HistoryView";
    public static final String TEAM_HISTORY_VIEW_ID = "org.eclipse.team.ui.GenericHistoryView";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        if (!(part instanceof IViewPart view))
            return null;

        String viewId = view.getSite().getId();
        if (!EGIT_HISTORY_VIEW_ID.equals(viewId) && !TEAM_HISTORY_VIEW_ID.equals(viewId))
            return null;

        executeIrHistory(view);
        return null;
    }

    public static void executeIrHistoryWithFile(IFile file, String commitSha)
    {
        if (file == null || !file.exists())
        {
            ToastNotification.show("История ИР",
                "Не удалось определить модуль. Откройте историю для конкретного файла.");
            return;
        }

        IProject project = file.getProject();
        IDtProject dtProject = Global.getDtProjectFromWorkspaceProject(project);
        if (dtProject == null)
        {
            ToastNotification.show("История ИР",
                "Не удалось определить проект EDT для файла " + file.getName());
            return;
        }

        String moduleName = resolveModuleProjectionName(file, project);

        IRSession irSession = IRApplication.getSession(dtProject, true);
        if (irSession == null || irSession.executor == null)
            return;

        String finalModuleName = moduleName != null ? moduleName : "";
        String finalCommitSha = commitSha != null ? commitSha : "";

        irSession.executor.submit(() ->
        {
            try
            {
                Object irClient = irSession.getModule("ирКлиент");
                irSession.showWindow();
                ComBridge.invoke(irClient, "ОткрытьИсториюГитаЛкс",
                    "", finalModuleName, finalCommitSha, 0);
            }
            catch (Exception e)
            {
                Global.log("Ошибка вызова ОткрытьИсториюГитаЛкс: " + e.getMessage());
            }
        });
    }

    static void executeIrHistory(IViewPart view)
    {
        IFile file = getFileFromView(view);
        String commitSha = getCommitShaFromSelection(view);
        executeIrHistoryWithFile(file, commitSha);
    }

    private static IFile getFileFromView(IViewPart view)
    {
        IFile file = view.getAdapter(IFile.class);
        if (file != null && file.exists())
            return file;

        try
        {
            Object input = Global.call(view, "getInput");
            if (input instanceof IFile f)
                return f;
            if (input instanceof IResource res)
            {
                IFile f = res.getAdapter(IFile.class);
                if (f != null && f.exists())
                    return f;
            }
        }
        catch (Exception ignored)
        {
        }

        if (view.getSite() != null)
        {
            var sp = view.getSite().getSelectionProvider();
            if (sp != null)
            {
                ISelection sel = sp.getSelection();
                if (sel instanceof IStructuredSelection ss && ss.size() == 1)
                {
                    IFile f = GitChangedFileMenuHook.resolveFile(view, ss.getFirstElement());
                    if (f != null && f.exists())
                        return f;
                }
            }
        }

        ISelection focusSel = GitChangedFileMenuHook.selectionFromFocusControl();
        if (focusSel instanceof IStructuredSelection ss && ss.size() == 1)
        {
            IFile f = GitChangedFileMenuHook.resolveFile(view, ss.getFirstElement());
            if (f != null && f.exists())
                return f;
        }

        return null;
    }

    private static String resolveModuleProjectionName(IFile file, IProject project)
    {
        return GetRef.resolveSetTextModuleName(file);
    }

    private static String getCommitShaFromSelection(IViewPart view)
    {
        if (view.getSite() == null)
            return null;
        var sp = view.getSite().getSelectionProvider();
        if (sp == null)
            return null;
        ISelection sel = sp.getSelection();
        if (!(sel instanceof IStructuredSelection ss) || ss.size() != 1)
            return null;
        Object element = ss.getFirstElement();
        if (element == null)
            return null;

        String sha = extractCommitSha(element);
        if (sha != null)
            return sha;

        String str = element.toString();
        if (str != null && str.length() >= 7 && str.length() <= 64 && str.matches("[0-9a-f]+"))
            return str;

        return null;
    }

    static String extractCommitSha(Object element)
    {
        try
        {
            Object id = Global.call(element, "getId");
            if (id != null)
            {
                Object name = Global.call(id, "getName");
                if (name instanceof String s && s.matches("[0-9a-f]{7,64}"))
                    return s;
            }
        }
        catch (Exception ignored)
        {
        }

        try
        {
            Object commit = Global.call(element, "getCommit");
            if (commit != null)
            {
                Object id = Global.call(commit, "getId");
                if (id != null)
                {
                    Object name = Global.call(id, "getName");
                    if (name instanceof String s && s.matches("[0-9a-f]{7,64}"))
                        return s;
                }
            }
        }
        catch (Exception ignored)
        {
        }

        try
        {
            Object name = Global.call(element, "getName");
            if (name instanceof String s && s.matches("[0-9a-f]{7,64}"))
                return s;
        }
        catch (Exception ignored)
        {
        }

        try
        {
            Object id = Global.call(element, "getObjectId");
            if (id != null)
            {
                Object name = Global.call(id, "getName");
                if (name instanceof String s && s.matches("[0-9a-f]{7,64}"))
                    return s;
            }
        }
        catch (Exception ignored)
        {
        }

        return null;
    }

    public static IViewPart findActiveHistoryView()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (view == null || view.getSite() == null)
                    continue;
                String id = view.getSite().getId();
                if (EGIT_HISTORY_VIEW_ID.equals(id) || TEAM_HISTORY_VIEW_ID.equals(id))
                    return view;
            }
        }
        catch (Exception ignored)
        {
        }
        return null;
    }
}
