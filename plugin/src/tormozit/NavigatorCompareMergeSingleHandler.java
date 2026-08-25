package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISources;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.handlers.IHandlerService;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * «Сравнить/объединить» при выделении одного объекта навигатора: подбирает
 * одноимённый объект в другом проекте и вызывает штатную команду EDT.
 */
public final class NavigatorCompareMergeSingleHandler extends AbstractHandler
{
    private static final String NATIVE_COMMAND_ID = "com._1c.g5.v8.dt.compare.ui.openCompareWizard"; //$NON-NLS-1$

    private static final String CHOOSE_PROJECT_TITLE = "Выберите проект объекта сравнения"; //$NON-NLS-1$
    private static final String CHOOSE_PROJECT_MESSAGE =
            "Выберите проект с одноимённым объектом:"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        MdObject source = selectedMdObject(menuSelection(event));
        if (source == null)
            return null;
        List<Peer> peers = findPeers(source);
        if (peers.isEmpty())
            return null;
        Peer peer = peers.size() == 1
                ? peers.get(0)
                : choosePeer(HandlerUtil.getActiveShell(event), peers);
        if (peer == null)
            return null;
        openNativeCompare(source, peer.object, HandlerUtil.getActiveShell(event));
        return null;
    }

    private static List<Peer> findPeers(MdObject source)
    {
        List<Peer> peers = new ArrayList<>();
        if (source == null)
            return peers;
        String fullName = fullNameOf(source);
        if (fullName == null)
            return peers;
        IV8ProjectManager manager = Global.getOsgiService(IV8ProjectManager.class);
        if (manager == null)
            return peers;
        IV8Project sourceProject = manager.getProject(source);
        IProject sourceWs = sourceProject != null ? sourceProject.getProject() : null;
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (project == null || !project.isOpen() || project.equals(sourceWs))
                continue;
            IV8Project v8 = manager.getProject(project);
            if (v8 == null)
                continue;
            EObject resolved = GoToDefinition.resolveEObjectByQualifiedName(fullName, v8);
            if (resolved instanceof MdObject md && resolved != source)
                peers.add(new Peer(md, project));
        }
        peers.sort((a, b) -> a.project.getName().compareToIgnoreCase(b.project.getName()));
        return peers;
    }

    private static String fullNameOf(MdObject object)
    {
        String fromRef = GetRef.eObjectToFullName(object);
        if (fromRef != null && !fromRef.isBlank())
            return fromRef;
        if (object instanceof IBmObject bm && !(object instanceof BasicForm))
        {
            String fqn = bm.bmGetFqn();
            if (fqn != null && !fqn.isBlank())
                return fqn;
        }
        return null;
    }

    private static MdObject selectedMdObject(IStructuredSelection selection)
    {
        if (selection == null || selection.size() != 1)
            return null;
        Object first = selection.getFirstElement();
        if (first instanceof MdObject md)
            return md;
        EObject resolved = NavigatorElementModels.resolveEObject(first);
        return resolved instanceof MdObject md ? md : null;
    }

    private static IStructuredSelection menuSelection(ExecutionEvent event)
    {
        ISelection selection = HandlerUtil.getActiveMenuSelection(event);
        if (selection instanceof IStructuredSelection structured && !structured.isEmpty())
            return structured;
        return HandlerUtil.getCurrentStructuredSelection(event);
    }

    private static Peer choosePeer(Shell shell, List<Peer> peers)
    {
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell, new LabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return element instanceof Peer peer ? peer.project.getName() : String.valueOf(element);
            }
        });
        dialog.setTitle(Global.withPluginWindowTitle(CHOOSE_PROJECT_TITLE));
        dialog.setMessage(CHOOSE_PROJECT_MESSAGE);
        dialog.setElements(peers.toArray());
        dialog.setInitialSelections(new Object[] { peers.get(0) });
        if (dialog.open() != Window.OK)
            return null;
        Object[] selected = dialog.getResult();
        return selected != null && selected.length > 0 && selected[0] instanceof Peer peer
                ? peer : null;
    }

    private static void openNativeCompare(MdObject source, MdObject peer, Shell shell)
    {
        IHandlerService handlers = PlatformUI.getWorkbench().getService(IHandlerService.class);
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (handlers == null || commands == null)
            return;
        Command command = commands.getCommand(NATIVE_COMMAND_ID);
        if (command == null || !command.isDefined())
            return;
        IStructuredSelection pair = new StructuredSelection(new Object[] { source, peer });
        IEvaluationContext context = new EvaluationContext(handlers.getCurrentState(), pair);
        context.addVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME, pair);
        if (shell != null)
            context.addVariable(ISources.ACTIVE_SHELL_NAME, shell);
        try
        {
            handlers.executeCommandInContext(new ParameterizedCommand(command, null), null, context);
        }
        catch (Exception e)
        {
            Global.log("NavigatorCompareMergeSingle: " + e); //$NON-NLS-1$
        }
    }

    /**
     * Видимость пункта меню: в другом проекте есть объект с тем же полным именем.
     */
    public static final class HasPeerInOtherProjectTester extends PropertyTester
    {
        @Override
        public boolean test(Object receiver, String property, Object[] args, Object expectedValue)
        {
            MdObject source = receiver instanceof MdObject md
                    ? md
                    : (NavigatorElementModels.resolveEObject(receiver) instanceof MdObject md
                            ? md : null);
            return source != null && !findPeers(source).isEmpty();
        }
    }

    private static final class Peer
    {
        private final MdObject object;
        private final IProject project;

        private Peer(MdObject object, IProject project)
        {
            this.object = object;
            this.project = project;
        }
    }
}
