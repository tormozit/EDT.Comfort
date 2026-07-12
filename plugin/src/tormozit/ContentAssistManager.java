package tormozit;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.dcs.ui.DataCompositionSchemaEditor;
import com._1c.g5.v8.dt.dcs.ui.datasets.DataSets;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorEmbeddedEditorPage;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

public final class ContentAssistManager
{
    private static ContentAssistManager instance;

    private final ContentAssistSettings  settings;
    private final WindowListener                 windowListener   = new WindowListener();
    private final SettingsChangeListener         settingsListener = new SettingsChangeListener();

    private final Map<IWorkbenchWindow, IPartListener2>          partListeners =
        new HashMap<>();
    private final Map<DtGranularEditor<?>, IPageChangedListener> pageListeners =
        new HashMap<>();

    private ContentAssistManager(ContentAssistSettings settings)
    {
        this.settings = settings;
    }

    public static synchronized ContentAssistManager init(
            ContentAssistSettings settings)
    {
        if (instance == null)
            instance = new ContentAssistManager(settings);
        return instance;
    }

    public static ContentAssistManager getInstance() { return instance; }

    public void start()
    {
        settings.loadSettings();
        settings.addPropertyChangeListener(settingsListener);

        if (!PlatformUI.isWorkbenchRunning())
            return;

        applyPatchToOpenedEditorsOnUIThread();

        PlatformUI.getWorkbench().addWindowListener(windowListener);
        for (IWorkbenchWindow window :
                PlatformUI.getWorkbench().getWorkbenchWindows())
            registerPartListener(window);
    }

    public void stop()
    {
        settings.removePropertyChangeListener(settingsListener);
        if (PlatformUI.isWorkbenchRunning())
            PlatformUI.getWorkbench().removeWindowListener(windowListener);
    }

    public void applyPatchToOpenedEditors()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;

        if (Display.getCurrent() != null)
            applyPatchToOpenedEditorsOnUIThread();
        else
            display.asyncExec(this::applyPatchToOpenedEditorsOnUIThread);
    }

    private void applyPatchToOpenedEditorsOnUIThread()
    {
        if (!settings.isEnabled())
            return;
        if (!PlatformUI.isWorkbenchRunning())
            return;

        for (IWorkbenchWindow window :
                PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorReference ref : page.getEditorReferences())
                {
                    IWorkbenchPart part = ref.getPart(false);
                    if (part instanceof DtGranularEditor<?>)
                        applyPatchToGranularEditor((DtGranularEditor<?>) part);
                    else if (part instanceof BslXtextEditor)
                        applyPatchToBslEditor((BslXtextEditor) part);
                }
            }
        }
    }

    private void applyPatchToGranularEditor(DtGranularEditor<?> editor)
    {
        if (!settings.isEnabled())
            return;

        org.eclipse.ui.forms.editor.IFormPage activePage =
            editor.getActivePageInstance();
        if (activePage instanceof DtGranularEditorXtextEditorPage<?>)
        {
            IEditorPart embedded =
                ((DtGranularEditorXtextEditorPage<?>) activePage).getEmbeddedEditor();
            if (embedded instanceof BslXtextEditor)
                applyPatchToBslEditor((BslXtextEditor) embedded);
            else if (embedded instanceof ITextEditor textEditor)
                applyPatchToGenericXtextEditor(textEditor);
        }
        if (activePage instanceof DtGranularEditorEmbeddedEditorPage<?> embeddedPage)
            applyPatchToDcsQueryInPage(embeddedPage);

        if (!pageListeners.containsKey(editor))
        {
            IPageChangedListener pl = new PageChangeListener();
            editor.addPageChangedListener(pl);
            pageListeners.put(editor, pl);
        }
    }

    private void applyPatchToBslEditor(BslXtextEditor editor)
    {
        if (!settings.isEnabled()) return;

        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (!(viewer instanceof SourceViewer)) return;
        SourceViewer sourceViewer = (SourceViewer) viewer;

        ContentAssistPatcher.applyPatch(
            sourceViewer, settings.getTimeout(), settings.getCharset(), editor);
    }

    private void applyPatchToGenericXtextEditor(ITextEditor editor)
    {
        if (!settings.isEnabled()) return;

        ISourceViewer viewer = TextEditor.getSourceViewer(editor);
        if (!(viewer instanceof SourceViewer sourceViewer))
            return;
        QueryTextEditorFacade facade =
            new QueryTextEditorFacade(editor, sourceViewer, null);
        ContentAssistPatcher.applyPatch(
            sourceViewer, settings.getTimeout(), settings.getCharset(), facade);
    }

    void applyPatchToQueryEditorShell(Shell shell)
    {
        if (!settings.isEnabled()) return;
        if (shell == null || shell.isDisposed()) return;

        ISourceViewer viewer = QueryTextEditDialogHook.resolveViewerForShell(shell);
        if (viewer == null || !(viewer instanceof SourceViewer sourceViewer))
            return;

        Object dialog = QueryTextEditDialogHook.resolveDialogForShell(shell);
        Object qlEditor = dialog != null ? Global.getField(dialog, "qlEditor") : null; //$NON-NLS-1$
        QueryTextEditorFacade facade = new QueryTextEditorFacade(qlEditor, sourceViewer, dialog);

        ContentAssistPatcher.applyPatch(
            sourceViewer, settings.getTimeout(), settings.getCharset(), facade);
    }

    private void applyPatchToDcsQueryInPage(DtGranularEditorEmbeddedEditorPage<?> page)
    {
        if (!settings.isEnabled()) return;
        if (!"editors.commontemplate.pages.dcs".equals(page.getId())) //$NON-NLS-1$
            return;
        IEditorPart embedded = page.getEmbeddedEditor();
        if (!(embedded instanceof DataCompositionSchemaEditor dcsEditor))
            return;
        if (dcsEditor.getPages().isEmpty())
            return;
        Object firstPage = dcsEditor.getPages().get(0);
        if (!(firstPage instanceof DataSets dataSets))
            return;
        Object qlEditor = dataSets.getQueryEditor();
        if (qlEditor == null)
            return;
        Object viewerObj = Global.invoke(qlEditor, "getViewer"); //$NON-NLS-1$
        if (!(viewerObj instanceof SourceViewer sourceViewer))
            return;
        QueryTextEditorFacade facade =
            new QueryTextEditorFacade(qlEditor, sourceViewer, null);
        ContentAssistPatcher.applyPatch(
            sourceViewer, settings.getTimeout(), settings.getCharset(), facade);
    }

    private void registerPartListener(IWorkbenchWindow window)
    {
        if (!partListeners.containsKey(window))
        {
            IPartListener2 pl = new PartListener();
            window.getPartService().addPartListener(pl);
            partListeners.put(window, pl);
        }
    }

    private class SettingsChangeListener implements IPropertyChangeListener
    {
        @Override
        public void propertyChange(PropertyChangeEvent event)
        {
            String prop = event.getProperty();
            if (ContentAssistSettings.PREF_ENABLED.equals(prop)
                    || ContentAssistSettings.PREF_TIMEOUT.equals(prop)
                    || ComfortSettings.PREF_REPLACE_LIST_FILTERS.equals(prop))
            {
                settings.loadSettings();
                applyPatchToOpenedEditors();
            }
        }
    }

    private class WindowListener implements IWindowListener
    {
        @Override
        public void windowOpened(IWorkbenchWindow window)
        {
            registerPartListener(window);
        }

        @Override
        public void windowClosed(IWorkbenchWindow window)
        {
            IPartListener2 pl = partListeners.remove(window);
            if (pl != null)
                window.getPartService().removePartListener(pl);
        }

        @Override public void windowActivated(IWorkbenchWindow window)   {}
        @Override public void windowDeactivated(IWorkbenchWindow window) {}
    }

    private class PartListener implements IPartListener2
    {
        @Override
        public void partOpened(IWorkbenchPartReference ref)
        {
            IWorkbenchPart part = ref.getPart(false);
            if (part instanceof DtGranularEditor<?>)
                applyPatchToGranularEditor((DtGranularEditor<?>) part);
            else if (part instanceof BslXtextEditor)
                applyPatchToBslEditor((BslXtextEditor) part);
        }

        @Override
        public void partClosed(IWorkbenchPartReference ref)
        {
            IWorkbenchPart part = ref.getPart(false);
            if (part instanceof DtGranularEditor<?>)
            {
                DtGranularEditor<?> editor = (DtGranularEditor<?>) part;
                IPageChangedListener pl = pageListeners.remove(editor);
                if (pl != null)
                    editor.removePageChangedListener(pl);
            }
        }
    }

    private class PageChangeListener implements IPageChangedListener
    {
        @Override
        public void pageChanged(PageChangedEvent event)
        {
            Object page = event.getSelectedPage();
            if (page instanceof DtGranularEditorXtextEditorPage<?>)
            {
                IEditorPart embedded =
                    ((DtGranularEditorXtextEditorPage<?>) page).getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor)
                    applyPatchToBslEditor((BslXtextEditor) embedded);
                else if (embedded instanceof ITextEditor textEditor)
                    applyPatchToGenericXtextEditor(textEditor);
            }
            if (page instanceof DtGranularEditorEmbeddedEditorPage<?> embeddedPage)
                applyPatchToDcsQueryInPage(embeddedPage);
        }
    }
}
