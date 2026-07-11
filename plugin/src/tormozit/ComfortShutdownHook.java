package tormozit;

import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Подготовка к закрытию EDT: снять отложенные UI-задачи плагина до {@code Display.release()}.
 */
public final class ComfortShutdownHook implements IStartup
{
    private static volatile boolean listenerInstalled;

    @Override
    public void earlyStartup()
    {
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return;
        if (listenerInstalled)
            return;
        listenerInstalled = true;
        workbench.addWorkbenchListener(new ShutdownListener());
    }

    private static final class ShutdownListener implements IWorkbenchListener
    {
        @Override
        public boolean preShutdown(IWorkbench workbench, boolean forced)
        {
            ComfortShutdownDebug.begin(forced ? "forced" : "normal"); //$NON-NLS-1$ //$NON-NLS-2$
            boolean ok = true;
            try
            {
                runStep("toasts", ToastNotification::closeAllActive); //$NON-NLS-1$
                runStep("paramHint", ParamHintHtmlModifier::dismissAllVisible); //$NON-NLS-1$
                runStep("contentAssist", ComfortShutdownHook::prepareContentAssist); //$NON-NLS-1$
                runStep("propertySheet", PropertySheetUiCoordinator::cancelAll); //$NON-NLS-1$
                runStep("collection", DebugCollectionWindowRegistry::disposeAll); //$NON-NLS-1$
                runStep("collectionSize", DebugCollectionSizeResolver::cancelAll); //$NON-NLS-1$
                runStep("designerPool", () -> DesignerSessionPoolAccessor.getInstance().stopPolling()); //$NON-NLS-1$
                runStep("listTheme", ListSelectionThemeHook::uninstall); //$NON-NLS-1$
                runStep("irDisconnect", IRApplication::disconnectAll); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                ok = false;
                ComfortShutdownDebug.problem("preShutdown", e.toString()); //$NON-NLS-1$
            }
            finally
            {
                ComfortShutdownDebug.end(ok);
            }
            return true;
        }

        @Override
        public void postShutdown(IWorkbench workbench)
        {
            listenerInstalled = false;
        }
    }

    private static void runStep(String name, Runnable action)
    {
        try
        {
            action.run();
            ComfortShutdownDebug.step(name);
        }
        catch (Exception e)
        {
            ComfortShutdownDebug.problem(name, e.toString());
        }
    }

    private static void prepareContentAssist()
    {
        ContentAssistSessionReloader.prepareShutdown();
        if (!PlatformUI.isWorkbenchRunning())
            return;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorReference ref : page.getEditorReferences())
                {
                    IWorkbenchPart part = ref.getPart(false);
                    if (part instanceof DtGranularEditor<?> granular)
                        prepareGranularEditor(granular);
                    else if (part instanceof BslXtextEditor bsl)
                        prepareBslEditor(bsl);
                }
            }
        }
    }

    private static void prepareGranularEditor(DtGranularEditor<?> editor)
    {
        org.eclipse.ui.forms.editor.IFormPage activePage = editor.getActivePageInstance();
        if (!(activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage))
            return;
        IEditorPart embedded = xtextPage.getEmbeddedEditor();
        if (embedded instanceof BslXtextEditor bsl)
            prepareBslEditor(bsl);
    }

    private static void prepareBslEditor(BslXtextEditor editor)
    {
        if (editor == null)
            return;
        ISourceViewer viewer = TextEditor.getSourceViewer(editor);
        if (!(viewer instanceof SourceViewer sourceViewer))
            return;
        ContentAssistPopupSync.cancelSessionPopupSync(sourceViewer);
        ContentAssistPopupSync.cancelFilterBarSetup(sourceViewer);
        ContentAssistPopupSync.cancelCaretRecompute(sourceViewer);
        ContentAssistant contentAssist = ContentAssistPatcher.getContentAssistant(sourceViewer);
        if (contentAssist != null)
            ContentAssistPopupSync.hideProposalPopup(contentAssist);
    }
}
