package tormozit;

import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * Нижняя панель popup: переключатель фильтра и тип контекста списка.
 */
public final class ContentAssistPopupUi
{
    private static final String BAR_DATA_KEY = "tormozit.filterBar"; //$NON-NLS-1$
    private static final String TOGGLE_DATA_KEY = "tormozit.filterToggleButton"; //$NON-NLS-1$
    private static final String CONTEXT_LABEL_DATA_KEY = "tormozit.contextTypeLabel"; //$NON-NLS-1$

    private ContentAssistPopupUi() {}

    public static void ensureFilterToggle(ContentAssistant assistant, SourceViewer viewer,
                                          SmartContentAssistProcessor processor)
    {
        try
        {
            Object popup = ContentAssistPopupSync.getPopupObject(assistant);
            if (popup == null)
                return;

            Shell shell = ContentAssistPopupSync.getProposalShell(popup);
            if (shell == null || shell.isDisposed())
                return;

            ContentAssistPopupSync.hideStatusLine(assistant, popup);

            Composite bar = (Composite) shell.getData(BAR_DATA_KEY);
            Button toggle;
            Label contextLabel;
            if (bar == null || bar.isDisposed())
            {
                bar = createFilterBar(shell, assistant, viewer, processor);
                toggle = (Button) bar.getData(TOGGLE_DATA_KEY);
                contextLabel = (Label) bar.getData(CONTEXT_LABEL_DATA_KEY);
            }
            else
            {
                toggle = (Button) bar.getData(TOGGLE_DATA_KEY);
                contextLabel = (Label) bar.getData(CONTEXT_LABEL_DATA_KEY);
            }

            if (toggle != null && !toggle.isDisposed())
                toggle.setSelection(SmartAssistFilterState.isSmartFilterEnabled());
            if (contextLabel != null && !contextLabel.isDisposed())
                contextLabel.setText(resolveContextTypeLabel(viewer));
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("filterToggle UI ERROR: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    public static void updateContextTypeLabel(SourceViewer viewer)
    {
        try
        {
            ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
            if (assistant == null || viewer == null)
                return;
            Object popup = ContentAssistPopupSync.getPopupObject(assistant);
            if (popup == null)
                return;
            Shell shell = ContentAssistPopupSync.getProposalShell(popup);
            if (shell == null || shell.isDisposed())
                return;
            Composite bar = (Composite) shell.getData(BAR_DATA_KEY);
            if (bar == null || bar.isDisposed())
                return;
            Label contextLabel = (Label) bar.getData(CONTEXT_LABEL_DATA_KEY);
            if (contextLabel != null && !contextLabel.isDisposed())
                contextLabel.setText(resolveContextTypeLabel(viewer));
        }
        catch (Exception ignored) {}
    }

    public static void syncFilterToggle(ContentAssistant assistant, SourceViewer viewer)
    {
        try
        {
            Object popup = ContentAssistPopupSync.getPopupObject(assistant);
            if (popup == null)
                return;
            Shell shell = ContentAssistPopupSync.getProposalShell(popup);
            if (shell == null || shell.isDisposed())
                return;
            Composite bar = (Composite) shell.getData(BAR_DATA_KEY);
            if (bar == null || bar.isDisposed())
                return;
            Button toggle = (Button) bar.getData(TOGGLE_DATA_KEY);
            if (toggle != null && !toggle.isDisposed())
                toggle.setSelection(SmartAssistFilterState.isSmartFilterEnabled());
            Label contextLabel = (Label) bar.getData(CONTEXT_LABEL_DATA_KEY);
            if (contextLabel != null && !contextLabel.isDisposed())
                contextLabel.setText(resolveContextTypeLabel(viewer));
        }
        catch (Exception ignored) {}
    }

    private static String resolveContextTypeLabel(SourceViewer viewer)
    {
        if (viewer == null || viewer.getDocument() == null)
            return ""; //$NON-NLS-1$
        int caret = viewer.getTextWidget() != null
            ? viewer.getTextWidget().getCaretOffset()
            : viewer.getSelectedRange().x;
        if (SmartContentAssistProcessor.ReceiverTypeLabel.findMemberAccessDot(
                viewer.getDocument(), caret) < 0)
            return ""; //$NON-NLS-1$

        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        if (processor != null)
        {
            String label = processor.resolveReceiverTypeLabel();
            if (label != null && !label.isEmpty())
                return "Родитель: " + label;
        }
        return "Родитель: —"; //$NON-NLS-1$
    }

    private static Composite createFilterBar(Shell shell, ContentAssistant assistant,
                                             SourceViewer viewer,
                                             SmartContentAssistProcessor processor)
    {
        Composite bar = new Composite(shell, SWT.NONE);
        RowLayout row = new RowLayout(SWT.HORIZONTAL);
        row.marginLeft = 4;
        row.marginRight = 4;
        row.marginTop = 2;
        row.marginBottom = 2;
        row.spacing = 10;
        bar.setLayout(row);
        GridData barData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        bar.setLayoutData(barData);

        Button toggle = new Button(bar, SWT.CHECK);
        toggle.setText("Фильтр включен"); //$NON-NLS-1$
        toggle.setSelection(SmartAssistFilterState.isSmartFilterEnabled());
        toggle.addListener(SWT.Selection, e -> {
            SmartAssistFilterState.setSmartFilterEnabled(toggle.getSelection());
            ContentAssistPopupSync.applyFilteredList(assistant, viewer, processor);
        });

        Label contextLabel = new Label(bar, SWT.NONE);
        contextLabel.setText(resolveContextTypeLabel(viewer));

        bar.setData(TOGGLE_DATA_KEY, toggle);
        bar.setData(CONTEXT_LABEL_DATA_KEY, contextLabel);
        shell.setData(BAR_DATA_KEY, bar);

        shell.addListener(SWT.KeyDown,
            e -> ContentAssistSessionReloader.ctrlSpaceFilter(assistant).handleEvent(e));
        shell.layout(true, true);
        ContentAssistDebug.log("filterBar UI created"); //$NON-NLS-1$
        return bar;
    }
}
