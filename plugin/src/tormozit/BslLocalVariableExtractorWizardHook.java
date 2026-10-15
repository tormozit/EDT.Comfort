package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.ui.refactoring.RefactoringWizard;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.XtextEditor;

import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.Parameter;

/**
 * Окно «Извлечь локальную переменную»: предлагает имя формального параметра для
 * выделенного аргумента вызова (issue 809).
 *
 * <p>Поля editor/selection в AbstractBslExtractRefactoring и textField в
 * TextInputWizardPage проверены через javap бандла bsl-ui 22.0.0. Изменение Text
 * вызывает штатный textModified: он обновляет рефакторинг и проверяет имя.
 * Номер аргумента считается по узлам запятых, как в ParamHintHtmlModifier,
 * с учётом пустых слотов. Неоднозначное или занятое имя оставляет штатное предложение.
 */
public final class BslLocalVariableExtractorWizardHook implements IStartup
{
    private static final String WIZARD_CLASS =
        "com._1c.g5.v8.dt.internal.bsl.ui.refactoring.BslLocalVariableExtractorWizard"; //$NON-NLS-1$
    private static final String HANDLED_KEY = "tormozit.extractLocalVariableName"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            Display display = Display.getDefault();
            if (!display.isDisposed())
                display.addFilter(SWT.Show, event ->
                {
                    if (event.widget instanceof Shell shell && !shell.isDisposed())
                        apply(shell);
                });
        });
    }

    private static void apply(Shell shell)
    {
        if (shell.getData(HANDLED_KEY) != null
            || !(shell.getData() instanceof IWizardContainer container))
            return;
        IWizardPage page = container.getCurrentPage();
        if (page == null || !(page.getWizard() instanceof RefactoringWizard wizard)
            || !WIZARD_CLASS.equals(wizard.getClass().getName()))
            return;
        shell.setData(HANDLED_KEY, Boolean.TRUE);
        Refactoring refactoring = wizard.getRefactoring();
        if (!(Global.getField(refactoring, "editor") instanceof XtextEditor editor) //$NON-NLS-1$
            || !(Global.getField(refactoring, "selection") instanceof ITextSelection selection) //$NON-NLS-1$
            || !(Global.getField(page, "textField") instanceof Text text) || text.isDisposed()) //$NON-NLS-1$
        {
            return;
        }
        try
        {
            String proposal = editor.getDocument().readOnly(resource ->
                parameterName(resource, selection));
            if (proposal == null || proposal.isBlank())
                return;
            String previous = text.getText();
            // Проверяем ровно тем же валидатором, что поле мастера, не меняя документ.
            Object status;
            try
            {
                Global.invoke(refactoring, "setVariableName", proposal); //$NON-NLS-1$
                status = Global.invoke(refactoring, "validateVariableName"); //$NON-NLS-1$
            }
            finally
            {
                Global.invoke(refactoring, "setVariableName", previous); //$NON-NLS-1$
            }
            if (!(status instanceof RefactoringStatus validation) || validation.hasError())
            {
                return;
            }
            text.setText(proposal);
            text.selectAll();
        }
        catch (Exception ignored)
        {
        }
    }

    private static String parameterName(XtextResource resource, ITextSelection selection)
    {
        if (resource == null || resource.getParseResult() == null || selection.getLength() == 0)
            return null;
        String selected = selection.getText();
        if (selected == null)
            return null;
        int start = selection.getOffset();
        int end = start + selection.getLength();
        int leading = 0;
        while (leading < selected.length() && Character.isWhitespace(selected.charAt(leading)))
            leading++;
        start += leading;
        int trailing = selected.length();
        while (trailing > leading && Character.isWhitespace(selected.charAt(trailing - 1)))
            trailing--;
        end -= selected.length() - trailing;
        if (start >= end)
            return null;
        ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), start);
        EObject object = leaf != null ? NodeModelUtils.findActualSemanticObjectFor(leaf) : null;
        for (EObject current = object; current != null; current = current.eContainer())
        {
            if (!(current instanceof Expression argument)
                || !(argument.eContainer() instanceof Invocation invocation)
                || !invocation.getParams().contains(argument))
                continue;
            ICompositeNode node = NodeModelUtils.findActualNodeFor(argument);
            // Часть аргумента сама по себе не соответствует формальному параметру.
            if (node == null || node.getOffset() != start || node.getEndOffset() != end)
                continue;
            int index = argumentIndex(invocation, start);
            return index >= 0 ? resolvedParameterName(invocation, index) : null;
        }
        return null;
    }

    private static int argumentIndex(Invocation invocation, int offset)
    {
        ICompositeNode call = NodeModelUtils.findActualNodeFor(invocation);
        if (call == null)
            return -1;
        List<INode> parts = new ArrayList<>();
        ICompositeNode method = NodeModelUtils.findActualNodeFor(invocation.getMethodAccess());
        if (method != null)
            parts.add(method);
        for (Expression argument : invocation.getParams())
        {
            ICompositeNode node = NodeModelUtils.findActualNodeFor(argument);
            if (node != null)
                parts.add(node);
        }
        int index = 0;
        for (ILeafNode leaf : call.getLeafNodes())
        {
            if (leaf.getOffset() >= offset)
                break;
            if (leaf.isHidden() || !",".equals(leaf.getText())) //$NON-NLS-1$
                continue;
            boolean nested = false;
            for (INode part : parts)
                if (leaf.getOffset() >= part.getOffset() && leaf.getOffset() < part.getEndOffset())
                {
                    nested = true;
                    break;
                }
            if (!nested)
                index++;
        }
        return index;
    }

    private static String resolvedParameterName(Invocation invocation, int index)
    {
        FeatureAccess access = invocation.getMethodAccess();
        List<FeatureEntry> entries;
        if (access instanceof StaticFeatureAccess stat)
            entries = stat.getFeatureEntries();
        else if (access instanceof DynamicFeatureAccess dynamic)
            entries = dynamic.getFeatureEntries();
        else
            return null;
        String result = null;
        for (FeatureEntry entry : entries)
        {
            EObject feature = entry.getFeature();
            String name;
            if (feature instanceof com._1c.g5.v8.dt.bsl.model.Method method)
                name = index < method.getFormalParams().size()
                    ? method.getFormalParams().get(index).getName() : null;
            else if (feature instanceof com._1c.g5.v8.dt.mcore.Method method)
                name = platformParameterName(method, access, index);
            else
                return null;
            if (name == null || name.isBlank() || result != null && !result.equalsIgnoreCase(name))
                return null;
            result = name;
        }
        return result;
    }

    private static String platformParameterName(com._1c.g5.v8.dt.mcore.Method method,
        FeatureAccess access, int index)
    {
        String calledName = access.getName();
        boolean russian = calledName != null && calledName.equalsIgnoreCase(method.getNameRu());
        if (!russian && (calledName == null || !calledName.equalsIgnoreCase(method.getName())))
            return null;
        String result = null;
        for (ParamSet signature : method.getParamSet())
        {
            if (index >= signature.getParams().size())
                return null;
            Parameter parameter = signature.getParams().get(index);
            String name = russian ? parameter.getNameRu() : parameter.getName();
            if (name == null || name.isBlank() || result != null && !result.equalsIgnoreCase(name))
                return null;
            result = name;
        }
        return result;
    }
}

