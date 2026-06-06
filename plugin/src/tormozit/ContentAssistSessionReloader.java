package tormozit;

import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ContentAssistEvent;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Widget;

/**
 * Сессия assist: подмена {@code fFilterRunnable}, Ctrl+Space → переключатель фильтра.
 */
public final class ContentAssistSessionReloader
{
    private static final String DATA_KEY = "ContentAssistSessionReloader.installed"; //$NON-NLS-1$

    private static final ThreadLocal<ContentAssistant> ACTIVE_ASSISTANT = new ThreadLocal<>();
    private static final ThreadLocal<SourceViewer> ACTIVE_VIEWER = new ThreadLocal<>();
    private static final ThreadLocal<SmartContentAssistProcessor> ACTIVE_PROCESSOR =
        new ThreadLocal<>();

    private final SourceViewer viewer;
    private final ContentAssistant assistant;
    private final SmartContentAssistProcessor processor;
    private final CtrlSpaceFilter ctrlSpaceFilter;
    private final String displayFilterKey;

    public static void install(SourceViewer viewer, ContentAssistant assistant,
                               SmartContentAssistProcessor processor)
    {
        Widget w = viewer.getTextWidget();
        if (!(w instanceof Control)) return;
        Control control = (Control) w;
        if (control.getData(DATA_KEY) != null) return;

        new ContentAssistSessionReloader(viewer, assistant, processor);
        control.setData(DATA_KEY, Boolean.TRUE);
        ContentAssistDebug.log("install completionListener (filterRunnable hijack)"); //$NON-NLS-1$
    }

    private ContentAssistSessionReloader(SourceViewer viewer, ContentAssistant assistant,
                                         SmartContentAssistProcessor processor)
    {
        this.viewer = viewer;
        this.assistant = assistant;
        this.processor = processor;
        this.ctrlSpaceFilter = new CtrlSpaceFilter(assistant);
        this.displayFilterKey = "tormozit.ctrlSpaceFilter." + System.identityHashCode(viewer); //$NON-NLS-1$

        assistant.addCompletionListener(new ICompletionListener() {
            @Override
            public void assistSessionStarted(ContentAssistEvent event)
            {
                ContentAssistDebug.resetValidateStats();
                SmartAssistFilterState.reset();
                ContentAssistDebug.log("assistSessionStarted auto=" + event.isAutoActivated //$NON-NLS-1$
                    + " processor=" + processorName(event.processor)); //$NON-NLS-1$
                ACTIVE_ASSISTANT.set(assistant);
                ACTIVE_VIEWER.set(viewer);
                ACTIVE_PROCESSOR.set(processor);
                int caret = ContentAssistPopupSync.syncSessionOffsets(assistant, viewer);
                SmartContentAssistProcessor.primeAssistContext(viewer, caret);
                processor.warmFullListCache(viewer);
                ContentAssistPopupSync.installFilterOverride(assistant, viewer, processor);
                installCtrlSpaceFilter();
                Control c = (Control) viewer.getTextWidget();
                if (c != null && !c.isDisposed())
                    c.getDisplay().asyncExec(() -> {
                        if (ContentAssistPopupSync.isPopupVisible(assistant))
                            ContentAssistPopupSync.applyFilteredList(assistant, viewer, processor);
                        ContentAssistPopupSync.refreshAdditionalInfo(assistant);
                        ContentAssistPopupUi.ensureFilterToggle(assistant, viewer, processor);
                    });
            }

            @Override
            public void assistSessionEnded(ContentAssistEvent event)
            {
                uninstallCtrlSpaceFilter();
                ACTIVE_ASSISTANT.remove();
                ACTIVE_VIEWER.remove();
                ACTIVE_PROCESSOR.remove();
                ContentAssistPopupSync.uninstallFilterOverride(assistant);
                ContentAssistPopupSync.clearPendingFilterToggleSelection();
                ContentAssistDebug.log("assistSessionEnded processor=" + processorName(event.processor)); //$NON-NLS-1$
                processor.invalidateCache();
                SmartFilterTracker.setCurrentFilter("");
                SmartAssistFilterState.reset();
                SmartContentAssistProcessor.clearLastComputeCaret();
            }

            @Override
            public void selectionChanged(
                org.eclipse.jface.text.contentassist.ICompletionProposal proposal,
                boolean smartToggle) {}
        });
    }

    private void installCtrlSpaceFilter()
    {
        Control c = (Control) viewer.getTextWidget();
        if (c == null || c.isDisposed())
            return;
        Display display = c.getDisplay();
        if (display == null || display.isDisposed())
            return;
        if (display.getData(displayFilterKey) != null)
            return;
        display.addFilter(SWT.KeyDown, ctrlSpaceFilter);
        display.setData(displayFilterKey, ctrlSpaceFilter);
    }

    private void uninstallCtrlSpaceFilter()
    {
        Control c = (Control) viewer.getTextWidget();
        if (c == null || c.isDisposed())
            return;
        Display display = c.getDisplay();
        if (display == null || display.isDisposed())
            return;
        Listener installed = (Listener) display.getData(displayFilterKey);
        if (installed != null)
            display.removeFilter(SWT.KeyDown, installed);
        display.setData(displayFilterKey, null);
    }

    public static ContentAssistant getActiveAssistant()
    {
        return ACTIVE_ASSISTANT.get();
    }

    public static SourceViewer getActiveViewer()
    {
        return ACTIVE_VIEWER.get();
    }

    public static SmartContentAssistProcessor getActiveProcessor()
    {
        return ACTIVE_PROCESSOR.get();
    }

    /** Повторная загрузка списка после reconcile (member-access после точки). */
    public static void refreshPopupIfOpen()
    {
        ContentAssistant ca = ACTIVE_ASSISTANT.get();
        SourceViewer viewer = ACTIVE_VIEWER.get();
        SmartContentAssistProcessor processor = ACTIVE_PROCESSOR.get();
        if (ca == null || viewer == null || processor == null)
            return;
        if (!ContentAssistPopupSync.isPopupVisible(ca))
            return;
        ContentAssistPopupSync.applyFilteredList(ca, viewer, processor);
    }

    public static void scheduleFilterToggleUiSync()
    {
        ContentAssistant ca = ACTIVE_ASSISTANT.get();
        SourceViewer viewer = ACTIVE_VIEWER.get();
        if (ca == null)
            return;
        Control c = null;
        try
        {
            Object popup = ContentAssistPopupSync.getPopupObject(ca);
            if (popup != null)
            {
                org.eclipse.swt.widgets.Shell shell =
                    ContentAssistPopupSync.getProposalShell(popup);
                if (shell != null && !shell.isDisposed())
                    c = shell;
            }
        }
        catch (Exception ignored) {}

        if (c == null)
            c = org.eclipse.ui.PlatformUI.getWorkbench().getDisplay().getActiveShell();
        if (c == null || c.isDisposed())
            return;
        SmartContentAssistProcessor processor = ACTIVE_PROCESSOR.get();
        c.getDisplay().asyncExec(() -> {
            if (ca != null && viewer != null && processor != null
                && ContentAssistPopupSync.isPopupVisible(ca))
                ContentAssistPopupSync.applyFilteredList(ca, viewer, processor);
            else
                ContentAssistPopupUi.syncFilterToggle(ca, viewer);
        });
    }

    static CtrlSpaceFilter ctrlSpaceFilter(ContentAssistant assistant)
    {
        return new CtrlSpaceFilter(assistant);
    }

    private static String processorName(IContentAssistProcessor p)
    {
        if (p == null)
            return "null";
        if (p instanceof SmartContentAssistProcessor)
            return "Smart→" + ((SmartContentAssistProcessor) p).getDelegate().getClass().getSimpleName();
        return p.getClass().getSimpleName();
    }

    /** Ctrl+Space при открытом popup — переключение фильтра в {@link SmartContentAssistProcessor}. */
    static final class CtrlSpaceFilter implements Listener
    {
        private final ContentAssistant assistant;

        CtrlSpaceFilter(ContentAssistant assistant)
        {
            this.assistant = assistant;
        }

        @Override
        public void handleEvent(Event event)
        {
            if (event.type != SWT.KeyDown)
                return;
            if (!isCtrlSpace(event))
                return;
            if (!ContentAssistPopupSync.isPopupVisible(assistant))
                return;
            event.doit = false;
        }

        private static boolean isCtrlSpace(Event event)
        {
            int sm = event.stateMask;
            if ((sm & SWT.CTRL) == 0 && (sm & SWT.MOD1) == 0)
                return false;
            return event.keyCode == SWT.SPACE
                || event.character == ' '
                || event.character == '\u0000' && event.keyCode == 32;
        }
    }
}
