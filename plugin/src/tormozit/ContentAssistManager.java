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
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;
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
        // Живые маркеры ошибок запросов в литералах (issue 837).
        BslQueryLiteralSupport.attachMarkers(editor);
        // Типы результатов запросов — в модели после каждой правки, а не только перед списком.
        BslQueryResultTypes.watchEditor(editor);
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
        if (!settings.isEnabled())
            return;
        if (shell == null || shell.isDisposed()) return;

        ISourceViewer viewer = QueryTextEditDialogHook.resolveViewerForShell(shell);
        if (viewer == null || !(viewer instanceof SourceViewer sourceViewer))
            return;

        Object dialog = QueryTextEditDialogHook.resolveDialogForShell(shell);
        Object qlEditor = dialog != null ? Global.getField(dialog, "qlEditor") : null; //$NON-NLS-1$
        QueryTextEditorFacade facade = new QueryTextEditorFacade(qlEditor, sourceViewer, dialog);
        QueryEditorHover.install(sourceViewer, facade);

        ContentAssistPatcher.applyPatch(
            sourceViewer, settings.getTimeout(), settings.getCharset(), facade);
    }

    /**
     * Встроенный BSL-редактор без {@link BslXtextEditor} (условие / выражение точки останова).
     * @return {@code false}, если viewer ещё не готов (нет ContentAssistant) — повторить позже
     */
    boolean applyPatchToEmbeddedBslViewer(SourceViewer sourceViewer)
    {
        if (!settings.isEnabled())
            return true;
        if (sourceViewer == null)
            return false;
        return ContentAssistPatcher.applyPatch(
            sourceViewer, settings.getTimeout(), settings.getCharset(),
            new EmbeddedBslEditorFacade(sourceViewer));
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
        // Редактор запроса набора данных создаётся лениво — при выборе набора, уже после
        // активации страницы (issue 1003). Поэтому подключаемся ещё и на фокус поля запроса.
        installDcsQueryFocusPatch(dataSets);
        Object qlEditor = dataSets.getQueryEditor();
        if (qlEditor == null)
            return;
        patchDcsQueryEditor(qlEditor, null);
    }

    private static final String DCS_FOCUS_PATCH_KEY = "tormozit.dcs.queryAssistFocusPatch"; //$NON-NLS-1$
    private static final String DCS_PATCHED_TEXT_KEY = "tormozit.dcs.queryAssistPatched"; //$NON-NLS-1$
    /** Поля страницы DataSets с редакторами запроса: полный и в объединении (см. dcs-ui). */
    private static final String[] DCS_QUERY_COMPOSITES = { "queryFullViewer", "queryInUnionViewer" }; //$NON-NLS-1$ //$NON-NLS-2$

    /** Display-фильтр фокуса: подключить автодополнение к лениво созданному редактору запроса. */
    private void installDcsQueryFocusPatch(DataSets dataSets)
    {
        if (!(dataSets instanceof org.eclipse.swt.widgets.Composite page) || page.isDisposed()
            || page.getData(DCS_FOCUS_PATCH_KEY) != null)
            return;
        page.setData(DCS_FOCUS_PATCH_KEY, Boolean.TRUE);
        Display display = page.getDisplay();
        org.eclipse.swt.widgets.Listener filter = event ->
        {
            if (page.isDisposed() || !(event.widget instanceof org.eclipse.swt.custom.StyledText text)
                || text.isDisposed() || text.getData(DCS_PATCHED_TEXT_KEY) != null
                || !isInside(text, page))
                return;
            for (String field : DCS_QUERY_COMPOSITES)
            {
                Object composite = Global.getField(page, field);
                Object ql = composite != null ? Global.invoke(composite, "getQueryEditor") : null; //$NON-NLS-1$
                if (patchDcsQueryEditor(ql, text))
                    return;
            }
            patchDcsQueryEditor(dataSets.getQueryEditor(), text);
        };
        display.addFilter(org.eclipse.swt.SWT.FocusIn, filter);
        page.addListener(org.eclipse.swt.SWT.Dispose,
            event -> display.removeFilter(org.eclipse.swt.SWT.FocusIn, filter));
    }

    private static boolean isInside(org.eclipse.swt.widgets.Control control,
                                    org.eclipse.swt.widgets.Control root)
    {
        for (org.eclipse.swt.widgets.Control c = control; c != null; c = c.getParent())
            if (c == root)
                return true;
        return false;
    }

    /**
     * @param focusText поле, получившее фокус; {@code null} — подключить без сверки с полем
     * @return {@code true}, если редактор {@code qlEditor} — тот, что принадлежит {@code focusText}
     */
    private boolean patchDcsQueryEditor(Object qlEditor, org.eclipse.swt.custom.StyledText focusText)
    {
        if (qlEditor == null || !settings.isEnabled())
            return false;
        Object viewerObj = Global.invoke(qlEditor, "getViewer"); //$NON-NLS-1$
        if (!(viewerObj instanceof SourceViewer sourceViewer))
            return false;
        org.eclipse.swt.custom.StyledText text = sourceViewer.getTextWidget();
        if (focusText != null && text != focusText)
            return false;
        // Повторное подключение при активации страницы/смене настроек не пропускаем: оно
        // переключает фильтры списка; от повторов по фокусу защищает сам фильтр.
        if (text == null || text.isDisposed())
            return true;
        QueryTextEditorFacade facade =
            new QueryTextEditorFacade(qlEditor, sourceViewer, null);
        QueryEditorHover.install(sourceViewer, facade);
        // Ctrl+клик по полю таблицы — переход к реквизиту, как в окнах запросов (issue 837).
        BslQueryLiteralSupport.wrapQueryEditorHyperlinks(sourceViewer);
        boolean patched = ContentAssistPatcher.applyPatch(
            sourceViewer, settings.getTimeout(), settings.getCharset(), facade);
        if (patched)
            text.setData(DCS_PATCHED_TEXT_KEY, Boolean.TRUE);
        return true;
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

    /**
     * Подсказка при наведении в окнах языка запросов (issue 1003). Штатный QL описание поля не
     * даёт ({@code QlEObjectHoverProvider} не знает {@code DbView}); описание — то же, что у
     * запроса в литерале модуля ({@link BslQueryLiteralSupport#queryEditorHover}). Нет описания —
     * штатная подсказка.
     */
    private static final class QueryEditorHover
        implements org.eclipse.jface.text.ITextHover, org.eclipse.jface.text.ITextHoverExtension,
        org.eclipse.jface.text.ITextHoverExtension2
    {
        private static final String MARKER = "tormozit.queryEditorHover"; //$NON-NLS-1$
        private final org.eclipse.jface.text.ITextHover delegate;
        private final QueryTextEditorFacade facade;
        private volatile org.eclipse.jface.text.IInformationControlCreator lastCreator;

        private QueryEditorHover(org.eclipse.jface.text.ITextHover delegate, QueryTextEditorFacade facade)
        {
            this.delegate = delegate;
            this.facade = facade;
        }

        static void install(SourceViewer viewer, QueryTextEditorFacade facade)
        {
            org.eclipse.swt.custom.StyledText text = viewer == null ? null : viewer.getTextWidget();
            if (text == null || text.isDisposed() || text.getData(MARKER) != null)
                return;
            text.setData(MARKER, Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<Object, org.eclipse.jface.text.ITextHover> hovers =
                (Map<Object, org.eclipse.jface.text.ITextHover>) Global.getField(viewer, "fTextHovers"); //$NON-NLS-1$
            if (hovers == null || hovers.isEmpty())
                viewer.setTextHover(new QueryEditorHover(null, facade),
                    org.eclipse.jface.text.IDocument.DEFAULT_CONTENT_TYPE);
            else
                for (Map.Entry<Object, org.eclipse.jface.text.ITextHover> entry : hovers.entrySet())
                    if (!(entry.getValue() instanceof QueryEditorHover))
                        entry.setValue(new QueryEditorHover(entry.getValue(), facade));
            // Переключатель «Подсказки при наведении без Ctrl» — как в редакторе модуля; после
            // обёртки: копия подсказки на Ctrl берётся уже с нашей. Пункт в меню добавляют хуки
            // меню окон запросов.
            BslHoverHintState.installOnViewer(viewer, false);
            QuerySyntaxAssistKey.install(viewer, facade);
        }

        @Override
        public org.eclipse.jface.text.IRegion getHoverRegion(org.eclipse.jface.text.ITextViewer viewer, int offset)
        {
            org.eclipse.jface.text.IRegion region =
                BslQueryLiteralSupport.queryEditorHoverRegion(viewer.getDocument(), offset);
            if (region != null)
                return region;
            return delegate != null ? delegate.getHoverRegion(viewer, offset) : null;
        }

        @Override
        public Object getHoverInfo2(org.eclipse.jface.text.ITextViewer viewer, org.eclipse.jface.text.IRegion region)
        {
            lastCreator = null;
            if (region != null)
            {
                var hover = BslQueryLiteralSupport.queryEditorHover(() ->
                {
                    IDtProject dtProject = facade.getDtProject();
                    return dtProject != null ? dtProject.getWorkspaceProject() : null;
                }, viewer.getDocument(), region.getOffset());
                if (hover != null && hover.info() != null)
                {
                    lastCreator = hover.creator();
                    return hover.info();
                }
            }
            if (delegate instanceof org.eclipse.jface.text.ITextHoverExtension2 ext2)
                return ext2.getHoverInfo2(viewer, region);
            return delegate != null ? delegate.getHoverInfo(viewer, region) : null;
        }

        @Override
        @Deprecated
        public String getHoverInfo(org.eclipse.jface.text.ITextViewer viewer, org.eclipse.jface.text.IRegion region)
        {
            Object info = getHoverInfo2(viewer, region);
            return info == null ? null : info instanceof String text ? text : info.toString();
        }

        @Override
        public org.eclipse.jface.text.IInformationControlCreator getHoverControlCreator()
        {
            org.eclipse.jface.text.IInformationControlCreator creator = lastCreator;
            if (creator != null)
                return creator;
            return delegate instanceof org.eclipse.jface.text.ITextHoverExtension ext
                ? ext.getHoverControlCreator() : null;
        }
    }

    /**
     * Ctrl+F1 в окнах языка запросов — как «Открыть синтакс-помощник» редактора модуля: поиск слова
     * в верхней части панели, для ключевого слова или функции QL — статья раздела языка запросов
     * в нижней. Команда EDT здесь не активна (её контекст — редактор модуля), поэтому клавиша
     * перехватывается на поле; у {@code StyledText} — только {@code addVerifyKeyListener}.
     */
    private static final class QuerySyntaxAssistKey
    {
        static void install(SourceViewer viewer, QueryTextEditorFacade facade)
        {
            org.eclipse.swt.custom.StyledText text = viewer.getTextWidget();
            text.addVerifyKeyListener(event ->
            {
                if (event.keyCode != org.eclipse.swt.SWT.F1
                    || (event.stateMask & org.eclipse.swt.SWT.MODIFIER_MASK) != org.eclipse.swt.SWT.MOD1)
                    return;
                event.doit = false;
                // Windows шлёт за F1 запрос справки (SWT.Help) в фокус. Панель, открытая сразу,
                // забирает фокус в редакторе среды (схема компоновки) — и справка Eclipse
                // открывается по ней. Поэтому запрос справки этого нажатия поглощаем на поле,
                // а панель открываем после него.
                org.eclipse.swt.widgets.Listener swallowHelp = new org.eclipse.swt.widgets.Listener()
                {
                    @Override
                    public void handleEvent(org.eclipse.swt.widgets.Event help)
                    {
                        text.removeListener(org.eclipse.swt.SWT.Help, this);
                    }
                };
                text.addListener(org.eclipse.swt.SWT.Help, swallowHelp);
                Display display = text.getDisplay();
                display.asyncExec(() ->
                {
                    if (!text.isDisposed())
                        open(viewer, facade);
                });
                display.timerExec(500, () ->
                {
                    if (!text.isDisposed())
                        text.removeListener(org.eclipse.swt.SWT.Help, swallowHelp);
                });
            });
        }

        private static void open(SourceViewer viewer, QueryTextEditorFacade facade)
        {
            org.eclipse.jface.text.IDocument document = viewer.getDocument();
            if (document == null)
                return;
            // Модельное смещение, не виджетное: со свёртками они расходятся.
            org.eclipse.swt.graphics.Point range = viewer.getSelectedRange();
            IDtProject dtProject = facade.getDtProject();
            org.eclipse.core.resources.IProject project = dtProject != null ? dtProject.getWorkspaceProject() : null;
            String selected = null;
            try
            {
                if (range.y > 0)
                    selected = document.get(range.x, range.y).replaceAll("\\R", ""); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (org.eclipse.jface.text.BadLocationException e)
            {
                // выделения нет — ищем слово под кареткой
            }
            String keyword = BslQueryLiteralSupport.queryEditorKeyword(project, document, range.x);
            String word = selected != null && !selected.isBlank() ? selected
                : keyword != null ? keyword : wordAt(document, range.x);
            if (keyword == null)
            {
                SyntaxAssistOpenElementHandler.show(java.util.List.of(), word);
                return;
            }
            // Статьи документации платформы читаются долго — вне потока UI, как в редакторе модуля.
            Display display = viewer.getTextWidget().getDisplay();
            java.util.concurrent.CompletableFuture.runAsync(() ->
            {
                java.util.List<?> pages = BslSyntaxAssist.queryKeywordPages(project, keyword);
                if (!display.isDisposed())
                    display.asyncExec(() -> SyntaxAssistOpenElementHandler.show(pages, word));
            });
        }

        private static String wordAt(org.eclipse.jface.text.IDocument document, int offset)
        {
            org.eclipse.jface.text.IRegion region = BslQueryLiteralSupport.queryEditorHoverRegion(document, offset);
            try
            {
                return region == null ? null : document.get(region.getOffset(), region.getLength());
            }
            catch (org.eclipse.jface.text.BadLocationException e)
            {
                return null;
            }
        }
    }

    /**
     * Facade для встроенного BSL без {@link BslXtextEditor}.
     * {@link #getDtProject()} всегда {@code null}: иначе ИР пошёл бы по query-sync.
     */
    private static final class EmbeddedBslEditorFacade implements TextEditorFacade
    {
        private final SourceViewer viewer;

        EmbeddedBslEditorFacade(SourceViewer viewer)
        {
            this.viewer = viewer;
        }

        @Override
        public SourceViewer getSourceViewer()
        {
            return viewer;
        }

        @Override
        public IDtProject getDtProject()
        {
            return null;
        }

        @Override
        public IWorkbenchPartSite getSite()
        {
            return null;
        }

        @Override
        public boolean isEditable()
        {
            return viewer != null && viewer.isEditable();
        }

        @Override
        public String getDisplayName()
        {
            return "Редактор точки останова"; //$NON-NLS-1$
        }

        @Override
        public boolean isQueryMode()
        {
            return false;
        }

        @Override
        public Object getRaw()
        {
            return viewer;
        }
    }
}
