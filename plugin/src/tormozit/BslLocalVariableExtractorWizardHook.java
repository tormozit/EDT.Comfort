package tormozit;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.ui.refactoring.RefactoringWizard;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.util.TextRegion;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Окно «Извлечь локальную переменную»: подбор имени из «СоздатьПеременную» ИР
 * по ожидаемому типу, типу выделения и выражению.
 *
 * <p>Поля editor/selection в AbstractBslExtractRefactoring и textField в
 * TextInputWizardPage проверены через javap бандла bsl-ui 22.0.0. Изменение Text
 * вызывает штатный textModified: он обновляет рефакторинг и проверяет имя.
 * Имя проходит штатную проверку; при ошибке остаётся предложение EDT.
 */
public final class BslLocalVariableExtractorWizardHook implements IStartup
{
    private static final String WIZARD_CLASS =
        "com._1c.g5.v8.dt.internal.bsl.ui.refactoring.BslLocalVariableExtractorWizard"; //$NON-NLS-1$
    private static final String HANDLED_KEY = "tormozit.extractLocalVariableName"; //$NON-NLS-1$
    private static final String COMMAND_ID = "com._1c.g5.v8.dt.bsl.ui.BslLocalVariableExtractor"; //$NON-NLS-1$
    private static final String NAME_HISTORY_SCOPE = "extractLocalVariable.name"; //$NON-NLS-1$

    /** После закрытия автодополнения запускает тот же обработчик, что меню «Рефакторинг». */
    static void extract(ITextViewer viewer, IDocument document, int offset, String selected)
    {
        // CompletionProposalPopup закрывает сеанс до apply: активного reloader здесь уже нет.
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        var page = window != null ? window.getActivePage() : null;
        var activeEditor = page != null ? page.getActiveEditor() : null;
        BslXtextEditor editor = GetRef.getActiveBslEditor(activeEditor);
        ITextViewer sourceViewer = editor != null ? editor.getInternalSourceViewer() : null;
        Global.tempLog("create-variable", "command requested offset=" + offset + " selected=" + selected //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " editor=" + (activeEditor == null ? null : activeEditor.getClass().getName()) //$NON-NLS-1$
            + " bslEditor=" + (editor == null ? null : editor.getClass().getName()) //$NON-NLS-1$
            + " suppliedViewer=" + (viewer != null) + " sourceViewer=" + (sourceViewer != null) //$NON-NLS-1$ //$NON-NLS-2$
            + " sameDocument=" + (editor != null && editor.getDocument() == document)); //$NON-NLS-1$
        if (editor == null || editor.getDocument() != document || sourceViewer == null
            || sourceViewer.getTextWidget() == null || sourceViewer.getTextWidget().isDisposed())
        {
            Global.tempLog("create-variable", "command skipped: no matching live editor/viewer"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        sourceViewer.getTextWidget().getDisplay().asyncExec(() ->
        {
            try
            {
                if (sourceViewer.getTextWidget() == null || sourceViewer.getTextWidget().isDisposed()
                    || sourceViewer.getDocument() != document || editor.getDocument() != document
                    || GetRef.getActiveBslEditor(editor.getSite().getPage().getActiveEditor()) != editor
                    || !document.get(offset, selected.length()).equals(selected))
                {
                    Global.tempLog("create-variable", "command skipped: editor or selection changed"); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                sourceViewer.setSelectedRange(offset, selected.length());
                sourceViewer.getTextWidget().setFocus();
                editor.getSelectionProvider().setSelection(new TextSelection(document, offset, selected.length()));
                IHandlerService handlers = editor.getSite().getService(IHandlerService.class);
                Global.tempLog("create-variable", "execute command service=" + (handlers != null)); //$NON-NLS-1$ //$NON-NLS-2$
                if (handlers != null)
                    handlers.executeCommand(COMMAND_ID, null);
            }
            catch (Exception e)
            {
                Global.tempLog("create-variable", "command error=" + e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
    }

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
        configureReplaceAll(page, refactoring, selection);
        installNameHistory(text);
        try
        {
            String proposal = editor.getDocument().readOnly(resource ->
                BslExpectedTypePremiums.suggestExtractedVariableName(resource, editor.getDocument(), selection));
            Global.tempLog("create-variable", "wizard selected=" + selection.getText() + " proposal=" + proposal); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
                Global.tempLog("create-variable", "wizard name rejected=" + proposal + " status=" + status); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return;
            }
            text.setText(proposal);
            text.selectAll();
        }
        catch (Exception e)
        {
            Global.tempLog("create-variable", "wizard error=" + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Оставляем штатный Text и его ModifyListener, как у полей LaunchConfigurationHook. */
    private static void installNameHistory(Text text)
    {
        Composite parent = text.getParent();
        Composite row = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.horizontalSpacing = 2;
        row.setLayout(layout);
        row.setLayoutData(text.getLayoutData());
        row.moveAbove(text);
        text.setParent(row);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        FilterHistoryUi.wireKeyboard(text, NAME_HISTORY_SCOPE);
        // Закрытие окна может уничтожить поле без отдельного FocusOut.
        text.addDisposeListener(event -> FilterHistoryStore.remember(NAME_HISTORY_SCOPE, text.getText()));
        Composite buttons = FilterHistoryUi.createButtonsRow(row);
        FilterHistoryUi.addHistoryButton(buttons, text, NAME_HISTORY_SCOPE,
            TooltipText.wrap(buttons, "История имён переменной (Ctrl+↓)" + Global.pluginSignForTooltip())); //$NON-NLS-1$
        parent.layout(true, true);
        Global.tempLog("create-variable", "name-history installed count=" + FilterHistoryStore.load(NAME_HISTORY_SCOPE).size()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * checkInitialConditions уже заполнил additionalReplaceRegions штатным NodeOccurencesFinder
     * (проверено в bsl-ui 22.0.0). Массив включает выделение: считаем только остальные места.
     */
    private static void configureReplaceAll(IWizardPage page, Refactoring refactoring, ITextSelection selection)
    {
        if (!(page.getControl() instanceof Composite parent))
            return;
        for (var control : parent.getChildren())
        {
            if (!(control instanceof Button button) || (button.getStyle() & SWT.CHECK) == 0)
                continue;
            // На этой странице единственный флажок — штатная замена всех вхождений.
            button.setSelection(false);
            Global.invoke(refactoring, "setReplaceAllOccurrences", false); //$NON-NLS-1$
            Object found = Global.getField(refactoring, "additionalReplaceRegions"); //$NON-NLS-1$
            if (!(found instanceof TextRegion[] regions))
            {
                Global.tempLog("create-variable", "replace-all regions unavailable=" + found); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            int count = 0;
            int start = selection.getOffset();
            int end = start + selection.getLength();
            for (TextRegion region : regions)
                if (region.getOffset() + region.getLength() <= start || region.getOffset() >= end)
                    count++;
            boolean allowed = button.getEnabled();
            button.setText("Заменить остальные вхождения (" + count + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            button.setEnabled(allowed && count > 0);
            parent.layout(true, true);
            Global.tempLog("create-variable", "replace-all total=" + regions.length + " other=" + count //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " allowed=" + allowed + " enabled=" + button.getEnabled()); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        Global.tempLog("create-variable", "replace-all control not found"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
