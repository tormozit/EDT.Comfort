package tormozit;

import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorInput;
import org.eclipse.core.resources.IFile;

import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * {@link TextEditorFacade} для модального «Редактора запроса» (QueryTextEditDialog)
 * и встроенных QL-редакторов (DCS, DynamicList).
 *
 * <p>Объект редактора ({@code qlEditor}) и viewer доступны через рефлексию;
 * {@link org.eclipse.ui.IWorkbenchPartSite} недоступен для модальных диалогов.</p>
 */
final class QueryTextEditorFacade implements TextEditorFacade
{
    private final Object qlEditor;
    private final SourceViewer viewer;
    private final Object dialog;

    QueryTextEditorFacade(Object qlEditor, SourceViewer viewer, Object dialog)
    {
        this.qlEditor = qlEditor;
        this.viewer = viewer;
        this.dialog = dialog;
    }

    @Override
    public SourceViewer getSourceViewer()
    {
        return viewer;
    }

    @Override
    public IDtProject getDtProject()
    {
        return resolveDtProject();
    }

    @Override
    public IWorkbenchPartSite getSite()
    {
        return null;
    }

    @Override
    public boolean isEditable()
    {
        if (qlEditor == null)
            return true;
        Object editable = Global.invoke(qlEditor, "isEditable"); //$NON-NLS-1$
        return !(editable instanceof Boolean b) || b;
    }

    @Override
    public String getDisplayName()
    {
        return "Редактор запроса"; //$NON-NLS-1$
    }

    @Override
    public boolean isQueryMode()
    {
        return true;
    }

    @Override
    public Object getRaw()
    {
        return dialog != null ? dialog : qlEditor;
    }

    Object getQlEditor()
    {
        return qlEditor;
    }

    Object getDialog()
    {
        return dialog;
    }

    private IDtProject resolveDtProject()
    {
        if (dialog != null)
        {
            IDtProject fromDialog = Global.getDtProjectFromQueryDialog(dialog);
            if (fromDialog != null)
                return fromDialog;
        }
        if (qlEditor != null)
        {
            IDtProject fromQlEditor = Global.getProjectFromEditor(qlEditor);
            if (fromQlEditor != null)
                return fromQlEditor;
        }
        return resolveDtProjectFromActiveEditor();
    }

    private static IDtProject resolveDtProjectFromActiveEditor()
    {
        try
        {
            IWorkbenchWindow window =
                PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;
            IEditorPart editor = page.getActiveEditor();
            if (editor == null)
                return null;

            IEditorInput input = editor.getEditorInput();
            if (input != null)
            {
                IFile file = input.getAdapter(IFile.class);
                if (file != null)
                    return Global.getDtProjectFromWorkspaceProject(file.getProject());
            }
        }
        catch (Exception ignored)
        {
        }
        return null;
    }
}
