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
    /** Data-key нижней панели popup (для определения таблицы автодополнения). */
    public static final String FILTER_BAR_DATA_KEY = "tormozit.filterBar"; //$NON-NLS-1$
    private static final String BAR_DATA_KEY = FILTER_BAR_DATA_KEY;
    private static final String TOGGLE_DATA_KEY = "tormozit.filterToggleButton"; //$NON-NLS-1$
    private static final String CONTEXT_LABEL_DATA_KEY = "tormozit.contextTypeLabel"; //$NON-NLS-1$
    /** Не реагировать на {@code setSelection} при программной синхронизации с Ctrl+Space. */
    private static boolean suppressToggleSelection;
    private static final String PARENT_PLACEHOLDER = "Родитель: —"; //$NON-NLS-1$
    private static int cachedContextLabelDot = -1;
    private static String cachedContextLabelReceiver = ""; //$NON-NLS-1$
    private static String cachedContextLabelText = ""; //$NON-NLS-1$

    private ContentAssistPopupUi() {}

    public static void ensureFilterToggle(ContentAssistant assistant, SourceViewer viewer,
                                          SmartContentAssistProcessor processor)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
        {
            removeFilterToggle(assistant);
            return;
        }
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
            {
                setToggleSelectionQuiet(toggle, SmartAssistFilterState.isSmartFilterEnabled());
                applyFilterToggleAvailability(toggle, viewer, processor);
            }
            if (contextLabel != null && !contextLabel.isDisposed())
                applyContextTypeLabel(contextLabel, viewer);
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("filterToggle UI ERROR: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void applyContextTypeLabel(Label contextLabel, SourceViewer viewer)
    {
        String old = contextLabel.getText();
        String text = resolveContextTypeLabel(viewer);
        if (text == null)
            text = ""; //$NON-NLS-1$
        contextLabel.setText(text);
        contextLabel.setToolTipText(text);
        if (text.equals(old))
            return;
        Composite bar = contextLabel.getParent();
        if (bar == null || bar.isDisposed())
            return;
        bar.layout(true, true);
        Shell shell = bar.getShell();
        if (shell != null && !shell.isDisposed())
            shell.layout(true, true);
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
                applyContextTypeLabel(contextLabel, viewer);
        }
        catch (Exception ignored) {}
    }

    public static boolean isFilterBarCreated(ContentAssistant assistant)
    {
        try
        {
            Object popup = ContentAssistPopupSync.getPopupObject(assistant);
            if (popup == null)
                return false;
            Shell shell = ContentAssistPopupSync.getProposalShell(popup);
            if (shell == null || shell.isDisposed())
                return false;
            Composite bar = (Composite) shell.getData(BAR_DATA_KEY);
            return bar != null && !bar.isDisposed();
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    /** Текст «Родитель: …» без записи в label (для H17). */
    public static String peekContextTypeLabel(SourceViewer viewer)
    {
        return resolveContextTypeLabel(viewer);
    }

    public static void removeFilterToggle(ContentAssistant assistant)
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
            if (bar != null && !bar.isDisposed())
            {
                bar.dispose();
                shell.setData(BAR_DATA_KEY, null);
                shell.layout(true, true);
            }
        }
        catch (Exception ignored) {}
    }

    public static void syncFilterToggle(ContentAssistant assistant, SourceViewer viewer)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
        {
            removeFilterToggle(assistant);
            return;
        }
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
            {
                setToggleSelectionQuiet(toggle, SmartAssistFilterState.isSmartFilterEnabled());
                applyFilterToggleAvailability(toggle, viewer,
                    ContentAssistSessionReloader.getActiveProcessor());
            }
            Label contextLabel = (Label) bar.getData(CONTEXT_LABEL_DATA_KEY);
            if (contextLabel != null && !contextLabel.isDisposed())
                applyContextTypeLabel(contextLabel, viewer);
        }
        catch (Exception ignored) {}
    }

    private static String resolveContextTypeLabel(SourceViewer viewer)
    {
        if (viewer == null || viewer.getDocument() == null)
        {
            return ""; //$NON-NLS-1$
        }
        int modelCaret = resolveViewerCaret(viewer);
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        int filterOff = assistant != null ? ContentAssistPopupSync.getFilterOffset(assistant) : -1;
        int invOff = assistant != null ? ContentAssistPopupSync.getInvocationOffset(assistant) : -1;
        int lastOff = assistant != null ? ContentAssistPopupSync.getLastCompletionOffset(assistant) : -1;
        int caret;
        if (filterOff >= 0)
        {
            caret = filterOff;
        }
        else if (invOff >= 0)
        {
            caret = invOff;
        }
        else if (lastOff >= 0)
        {
            caret = lastOff;
        }
        else
        {
            caret = modelCaret;
        }
        boolean inLiteral = SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret);
        if (inLiteral)
        {
            ContentAssistSessionReloader reloader =
                ContentAssistSessionReloader.getActiveReloader();
            String ctx = reloader != null ? reloader.getIrAssistContextTypeLabel() : null;
            cachedContextLabelDot = -1;
            cachedContextLabelReceiver = ""; //$NON-NLS-1$
            cachedContextLabelText = ctx != null && !ctx.isEmpty()
                ? "Родитель: " + ctx : PARENT_PLACEHOLDER;
            return cachedContextLabelText;
        }
        int dot = SmartContentAssistProcessor.ReceiverTypeLabel.findMemberAccessDot(
            viewer.getDocument(), caret);
        if (dot < 0)
        {
            cachedContextLabelDot = -1;
            cachedContextLabelReceiver = ""; //$NON-NLS-1$
            cachedContextLabelText = ""; //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        }
        String receiver = SmartContentAssistProcessor.memberAccessReceiver(
            viewer.getDocument(), caret);
        if (dot == cachedContextLabelDot
            && receiver.equals(cachedContextLabelReceiver)
            && !cachedContextLabelText.isEmpty()
            && !PARENT_PLACEHOLDER.equals(cachedContextLabelText))
        {
            return cachedContextLabelText;
        }

        String fromList = parentFromCurrentProposals();
        if (!fromList.isEmpty())
        {
            cachedContextLabelDot = dot;
            cachedContextLabelReceiver = receiver;
            cachedContextLabelText = fromList;
            return cachedContextLabelText;
        }
        String ast = SmartContentAssistProcessor.ReceiverTypeLabel.resolve(viewer);
        if (ast != null && !ast.isEmpty())
        {
            cachedContextLabelDot = dot;
            cachedContextLabelReceiver = receiver;
            cachedContextLabelText = "Родитель: " + ast;
            return cachedContextLabelText;
        }
        cachedContextLabelDot = dot;
        cachedContextLabelReceiver = receiver;
        cachedContextLabelText = PARENT_PLACEHOLDER;
        return cachedContextLabelText;
    }

    private static String parentFromCurrentProposals()
    {
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        if (processor == null)
            return ""; //$NON-NLS-1$
        String label = processor.resolveReceiverTypeLabel();
        if (label == null || label.isEmpty())
            return ""; //$NON-NLS-1$
        return "Родитель: " + label;
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
        toggle.setText("Фильтр"); //$NON-NLS-1$
        setToggleSelectionQuiet(toggle, SmartAssistFilterState.isSmartFilterEnabled());
        toggle.addListener(SWT.Selection, e -> {
            if (suppressToggleSelection)
                return;
            ContentAssistPopupSync.captureSelectionBeforeFilterToggle(assistant);
            SmartAssistFilterState.setSmartFilterEnabled(toggle.getSelection());
            ContentAssistPopupSync.recomputePopupList(assistant, viewer, processor);
        });

        Label contextLabel = new Label(bar, SWT.NONE);
        applyContextTypeLabel(contextLabel, viewer);

        bar.setData(TOGGLE_DATA_KEY, toggle);
        bar.setData(CONTEXT_LABEL_DATA_KEY, contextLabel);
        shell.setData(BAR_DATA_KEY, bar);

        shell.addListener(SWT.KeyDown, e -> {
            ContentAssistSessionReloader.CtrlSpaceFilter filter =
                ContentAssistSessionReloader.ctrlSpaceFilter(assistant);
            if (filter != null)
                filter.handleEvent(e);
        });
        shell.layout(true, true);
        ContentAssistDebug.log("filterBar UI created"); //$NON-NLS-1$
        return bar;
    }

    private static void setToggleSelectionQuiet(Button toggle, boolean selected)
    {
        suppressToggleSelection = true;
        try
        {
            toggle.setSelection(selected);
        }
        finally
        {
            suppressToggleSelection = false;
        }
    }

    private static void applyFilterToggleAvailability(Button toggle, SourceViewer viewer,
                                                      SmartContentAssistProcessor processor)
    {
        int caret = resolveViewerCaret(viewer);
        boolean inLiteral = SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret);
        boolean irConnected = false;
        if (inLiteral)
        {
            ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
            TextEditorFacade facade = reloader != null ? reloader.getFacade() : null;
            irConnected = IrBslExpressionHtmlSupport.resolveIrSessionForAssist(facade, viewer)
                != null;
        }
        toggle.setEnabled(true);
        toggle.setToolTipText(null);
    }

    private static int resolveViewerCaret(SourceViewer viewer)
    {
        return SmartContentAssistProcessor.resolveWidgetCaret(viewer);
    }
}
