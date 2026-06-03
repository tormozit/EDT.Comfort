package tormozit;



import org.eclipse.jface.text.contentassist.ContentAssistant;

import org.eclipse.jface.text.contentassist.ContentAssistEvent;

import org.eclipse.jface.text.contentassist.ICompletionListener;

import org.eclipse.jface.text.contentassist.IContentAssistProcessor;

import org.eclipse.jface.text.source.SourceViewer;

import org.eclipse.swt.widgets.Control;

import org.eclipse.swt.widgets.Widget;



/**

 * Сессия assist: подмена {@code fFilterRunnable} в popup и пересчёт списка при вводе.

 */

public final class ContentAssistSessionReloader

{

    private static final String DATA_KEY = "ContentAssistSessionReloader.installed"; //$NON-NLS-1$



    private final SourceViewer viewer;

    private final ContentAssistant assistant;

    private final SmartContentAssistProcessor processor;

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



        assistant.addCompletionListener(new ICompletionListener() {

            @Override

            public void assistSessionStarted(ContentAssistEvent event)

            {

                ContentAssistDebug.resetValidateStats();

                ContentAssistDebug.log("assistSessionStarted auto=" + event.isAutoActivated //$NON-NLS-1$

                    + " processor=" + processorName(event.processor)); //$NON-NLS-1$

                processor.warmFullListCache(viewer);

                ContentAssistPopupSync.installFilterOverride(assistant, viewer, processor);

                Control c = (Control) viewer.getTextWidget();

                if (c != null && !c.isDisposed())

                    c.getDisplay().asyncExec(() ->

                        ContentAssistPopupSync.refreshAdditionalInfo(assistant));

            }



            @Override

            public void assistSessionEnded(ContentAssistEvent event)

            {

                ContentAssistPopupSync.uninstallFilterOverride(assistant);

                ContentAssistDebug.log("assistSessionEnded processor=" + processorName(event.processor)); //$NON-NLS-1$

                processor.invalidateCache();

                SmartFilterTracker.setCurrentFilter("");

            }



            @Override

            public void selectionChanged(

                org.eclipse.jface.text.contentassist.ICompletionProposal proposal,

                boolean smartToggle) {}

        });

    }



    private static String processorName(IContentAssistProcessor p)

    {

        if (p == null)

            return "null";

        if (p instanceof SmartContentAssistProcessor)

            return "Smart→" + ((SmartContentAssistProcessor) p).getDelegate().getClass().getSimpleName();

        return p.getClass().getSimpleName();

    }

}

