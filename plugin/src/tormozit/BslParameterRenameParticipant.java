package tormozit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.URI;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext;
import org.eclipse.ltk.core.refactoring.participants.RenameParticipant;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.ui.refactoring.BslRenameElementContext;

/** Обновляет имя параметра в документирующем комментарии штатной операцией переименования. */
public final class BslParameterRenameParticipant extends RenameParticipant
{
    private IFile file;
    private String oldName;
    private int offset = -1;

    @Override
    protected boolean initialize(Object element)
    {
        if (!(element instanceof BslRenameElementContext context)
            || !(context.getTargetObject() instanceof FormalParam parameter)
            || !(parameter.eContainer() instanceof Method method)
            || !(method.eContainer() instanceof Module module))
            return false;
        oldName = parameter.getName();
        URI uri = context.getTargetElementURI();
        if (uri == null || !uri.isPlatformResource())
            return false;
        file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(uri.toPlatformString(true)));
        var comment = BslStructureInsertCommentTypes.parseMethodComment(method, module);
        var section = comment == null ? null : comment.getParametersSection();
        var definition = section == null ? null : section.getParameterByName(oldName);
        var node = NodeModelUtils.findActualNodeFor(method);
        if (definition == null || node == null)
        {
            return false;
        }
        int[] lines = comment.getLineGlobalOffsets();
        int line = definition.getLineNumber();
        if (lines != null && line >= 0 && line < lines.length)
            offset = node.getTotalOffset() + lines[line] + definition.getNameOffset();
        String text = node.getRootNode().getText();
        boolean matches = offset >= 0 && text.regionMatches(true, offset, oldName, 0, oldName.length());
        return matches;
    }

    @Override
    public String getName()
    {
        return "Переименование параметра в комментарии метода";
    }

    @Override
    public RefactoringStatus checkConditions(IProgressMonitor monitor, CheckConditionsContext context)
    {
        return new RefactoringStatus();
    }

    @Override
    public Change createChange(IProgressMonitor monitor) throws CoreException
    {
        String newName = getArguments().getNewName();
        TextChange existing = getTextChange(file);
        ReplaceEdit edit = new ReplaceEdit(offset, oldName.length(), newName);
        if (existing != null)
        {
            existing.addEdit(edit);
            return null;
        }
        TextFileChange change = new TextFileChange(getName(), file);
        change.setEdit(edit);
        return change;
    }
}



