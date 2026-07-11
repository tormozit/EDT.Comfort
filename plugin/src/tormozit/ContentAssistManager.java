package tormozit;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.text.contentassist.ContentAssistEvent;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContentAssistantExtension2;
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
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.IContentAssistantExtension2;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.contentassist.ContentAssistEvent;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.dcs.ui.DataCompositionSchemaEditor;
import com._1c.g5.v8.dt.dcs.ui.datasets.DataSets;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorEmbeddedEditorPage;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Управляет подпиской на открытие/закрытие BSL-редакторов и применяет
 * к ним патч автооткрытия подсказки.
 *
 * <p><b>Порядок инициализации:</b>
 * <ol>
 *   <li>{@code ContentAssistManager.init(settings)} — вызывается
 *       из {@code Activator.start()} (безопасно, без UI);</li>
 *   <li>{@code start()} — вызывается из {@code earlyStartup()} уже на
 *       UI-потоке, когда Workbench гарантированно инициализирован.</li>
 * </ol>
 *
 * <p>{@code start()} не использует {@code syncExec}/{@code asyncExec} —
 * он уже выполняется на UI-потоке (через {@code earlyStartup()}).
 * {@link #applyPatchToOpenedEditors()} безопасен из любого потока.
 */
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

    // ---- Синглтон ----

    public static synchronized ContentAssistManager init(
            ContentAssistSettings settings)
    {
        if (instance == null)
            instance = new ContentAssistManager(settings);
        return instance;
    }

    public static ContentAssistManager getInstance() { return instance; }

    // ---- Lifecycle ----

    /**
     * Запускает менеджер.
     *
     * <p><b>Вызывается с UI-потока</b> из {@code earlyStartup() → asyncExec},
     * когда Workbench готов. Не использует {@code syncExec}/{@code asyncExec},
     * чтобы не замедлять старт.
     */
    public void start()
    {
        settings.loadSettings();
        settings.addPropertyChangeListener(settingsListener);

        if (!PlatformUI.isWorkbenchRunning())
            return;

        // Применяем патч к уже открытым редакторам (восстановленная сессия)
        applyPatchToOpenedEditorsOnUIThread();

        // Регистрируем слушатели окон и частей
        PlatformUI.getWorkbench().addWindowListener(windowListener);
        for (IWorkbenchWindow window :
                PlatformUI.getWorkbench().getWorkbenchWindows())
            registerPartListener(window);
    }

    /** Останавливает менеджер. */
    public void stop()
    {
        settings.removePropertyChangeListener(settingsListener);
        if (PlatformUI.isWorkbenchRunning())
            PlatformUI.getWorkbench().removeWindowListener(windowListener);
    }

    // ---- Патч ----

    /**
     * Применяет патч ко всем открытым BSL-редакторам.
     * Безопасен из любого потока: если вызван не с UI-потока,
     * планирует {@code asyncExec}.
     */
    public void applyPatchToOpenedEditors()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;

        if (Display.getCurrent() != null)           // уже на UI-потоке
            applyPatchToOpenedEditorsOnUIThread();
        else
            display.asyncExec(this::applyPatchToOpenedEditorsOnUIThread);
    }

    /**
     * Применяет патч ко всем открытым редакторам.
     * <b>Вызывать только с UI-потока.</b>
     */
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
                // Перебираем ВСЕ открытые редакторы, а не только активный
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

        // --- автооткрытие и символы-триггеры ---
        boolean ok = ContentAssistPatcher.applyPatch(
            sourceViewer, settings.getTimeout(), settings.getCharset(), editor);
        ContentAssistDebug.log("BslEditor patch " + (ok ? "OK" : "FAIL") //$NON-NLS-1$
            + " editor=" + editor.getTitle()); //$NON-NLS-1$
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

        boolean ok = ContentAssistPatcher.applyPatch(
            sourceViewer, settings.getTimeout(), settings.getCharset(), facade);
        ContentAssistDebug.log("QueryEditor patch " + (ok ? "OK" : "FAIL") //$NON-NLS-1$
            + " shell=" + shell.getText()); //$NON-NLS-1$
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
        boolean ok = ContentAssistPatcher.applyPatch(
            sourceViewer, settings.getTimeout(), settings.getCharset(), facade);
        ContentAssistDebug.log("DcsQuery patch " + (ok ? "OK" : "FAIL")); //$NON-NLS-1$ //$NON-NLS-2$
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

    // ---- Внутренние слушатели ----

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
                applyPatchToOpenedEditors(); // безопасен из любого потока
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
            }
            if (page instanceof DtGranularEditorEmbeddedEditorPage<?> embeddedPage)
                applyPatchToDcsQueryInPage(embeddedPage);
        }
    }
}
