package tormozit;

import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.ui.IWorkbenchPartSite;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * {@link TextEditorFacade} для BSL-редактора ({@link BslXtextEditor}).
 */
final class BslTextEditorFacade implements TextEditorFacade
{
    private final BslXtextEditor editor;

    BslTextEditorFacade(BslXtextEditor editor)
    {
        this.editor = editor;
    }

    @Override
    public SourceViewer getSourceViewer()
    {
        ISourceViewer viewer = editor.getInternalSourceViewer();
        return viewer instanceof SourceViewer sv ? sv : null;
    }

    @Override
    public IDtProject getDtProject()
    {
        return Global.getDtProjectFromBslEditor(editor);
    }

    @Override
    public IWorkbenchPartSite getSite()
    {
        return editor.getSite();
    }

    @Override
    public boolean isEditable()
    {
        return editor.isEditable();
    }

    @Override
    public String getDisplayName()
    {
        return editor.getTitle();
    }

    @Override
    public boolean isQueryMode()
    {
        return false;
    }

    @Override
    public Object getRaw()
    {
        return editor;
    }

    BslXtextEditor getBslEditor()
    {
        return editor;
    }
}
