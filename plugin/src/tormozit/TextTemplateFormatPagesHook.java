package tormozit;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.editors.text.TextSourceViewerConfiguration;
import org.eclipse.ui.forms.IManagedForm;
import org.eclipse.ui.forms.editor.FormEditor;
import org.eclipse.ui.forms.editor.FormPage;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.texteditor.ITextEditor;

import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupport;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupportProvider;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorPage;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.ui.editor.IDtGranularEditorEmbeddedEditorPage;
import com._1c.g5.v8.dt.ui.util.OpenHelper;
import com._1c.g5.wiring.ServiceAccess;

import tormozit.FormattedTextEditor.Format;

/**
 * Дорабатывает штатную вкладку макета типа «Текстовый документ» (issue #868): вкладка
 * называется «Сырой текст» ({@link MdEditorTabsHook}), над текстом — панель с кнопкой «Формат».
 *
 * <ul>
 * <li>Формат и подсветка — {@link FormattedTextEditor} на штатном просмотрщике: редактор, команды,
 * выделение и переходы остаются штатными, страницы EDT не трогаются.
 * Ручной выбор хранится в свойстве файла макета ({@link #FORMAT_PROPERTY}).</li>
 * <li>Для Markdown и HTML добавляется вкладка «Готовый текст» — оформленный документ.</li>
 * </ul>
 *
 * <p>EDT пересоздаёт страницы редактора (смена входа, обновление модели): доработка ставится на
 * каждый новый экземпляр штатной страницы — при смене вкладки, входа и активации редактора.
 */
public final class TextTemplateFormatPagesHook implements IStartup
{
    static final String RAW_PAGE_TITLE = "Сырой текст"; //$NON-NLS-1$

    /** Штатная вкладка текстового макета (TemplateEditor). */
    static final String STOCK_PAGE_ID = "editors.commontemplate.pages.text.document"; //$NON-NLS-1$

    private static final String PREVIEW_PAGE_ID = "tormozit.textTemplate.preview"; //$NON-NLS-1$

    /** Контекст клавиш вкладки «Сырой текст» (plugin.xml): Ctrl+F3 / Ctrl+Shift+F3. */
    private static final String RAW_TEXT_CONTEXT_ID = "tormozit.textTemplate.rawText.context"; //$NON-NLS-1$

    private static final String PREVIEW_PAGE_TITLE = "Готовый текст"; //$NON-NLS-1$

    /** Ручной выбор формата макета; нет свойства — «Авто». */
    private static final QualifiedName FORMAT_PROPERTY =
        new QualifiedName("tormozit.comfort", "textTemplateFormat"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final int HOOK_RETRY_DELAY_MS = 200;

    private static final int HOOK_MAX_ATTEMPTS = 150;

    /** Подпись команды навигатора ({@link NavigatorMenuHook}). */
    static final String OPEN_PREVIEW_TEXT = "Открыть готовый текст"; //$NON-NLS-1$

    /** Доработанные экземпляры штатной страницы (EDT пересоздаёт страницы — у нового своя доработка). */
    private static final Map<IFormPage, Decoration> DECORATIONS = new WeakHashMap<>();

    /** Редакторы, открытые командой «Открыть готовый текст»: показать вкладку, когда она появится. */
    private static final Set<DtGranularEditor<?>> PENDING_PREVIEW =
        Collections.newSetFromMap(new WeakHashMap<>());

    private final Set<DtGranularEditor<?>> listenedEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    private final Set<DtGranularEditor<?>> pendingRetryEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)      { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });
            for (IWorkbenchWindow w : workbench.getWorkbenchWindows())
                hookWindow(w);
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart editor = ref.getEditor(false);
                if (editor instanceof DtGranularEditor<?> granular)
                    hookEditor(granular, 0);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)      { hookFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref)   { hookFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r)  {}
            @Override public void partClosed(IWorkbenchPartReference r)        {}
            @Override public void partDeactivated(IWorkbenchPartReference r)   {}
            @Override public void partHidden(IWorkbenchPartReference r)        {}
            @Override public void partVisible(IWorkbenchPartReference r)       { hookFromRef(r); }
            @Override public void partInputChanged(IWorkbenchPartReference r)  { hookFromRef(r); }

            private void hookFromRef(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference editorRef))
                    return;
                IWorkbenchPart part = editorRef.getPart(false);
                if (part instanceof DtGranularEditor<?> granular)
                    hookEditor(granular, 0);
            }
        });
    }

    private void hookEditor(DtGranularEditor<?> editor, int attempt)
    {
        try
        {
            if (listenedEditors.add(editor))
            {
                // Сразу при открытии, до загрузки модели: вкладка создаёт элементы при первом показе
                // (смена страницы), и панель ставится синхронно — до первой отрисовки, без скачка текста.
                // EDT пересоздаёт страницы — проверяем на каждой смене вкладки.
                editor.addPageChangedListener(e -> decorate(editor));
            }
            if (editor.getModel() == null || !Boolean.TRUE.equals(Global.getField(editor, "initialized"))) //$NON-NLS-1$
            {
                scheduleRetry(editor, attempt);
                return;
            }
            if (!isTextTemplate(editor.getModel()))
                return;
            decorate(editor);
            // Запасной путь: EDT достраивает редактор асинхронно, вкладка могла ещё не создаться.
            Display.getDefault().asyncExec(() -> decorate(editor));
        }
        catch (RuntimeException | LinkageError e)
        {
            // Редактор не дорабатывается — штатная вкладка остаётся как есть.
        }
    }

    private void scheduleRetry(DtGranularEditor<?> editor, int attempt)
    {
        if (attempt >= HOOK_MAX_ATTEMPTS || editor.getSite() == null)
            return;
        Composite container = (Composite)Global.invoke(editor, "getContainer"); //$NON-NLS-1$
        if (container != null && container.isDisposed())
            return;
        if (!pendingRetryEditors.add(editor))
            return;
        Display.getDefault().timerExec(HOOK_RETRY_DELAY_MS, () ->
        {
            pendingRetryEditors.remove(editor);
            hookEditor(editor, attempt + 1);
        });
    }

    /** Доработать текущий экземпляр штатной страницы, если её элементы созданы и она ещё не доработана. */
    private static void decorate(DtGranularEditor<?> editor)
    {
        try
        {
            if (!isTextTemplate(editor.getModel()))
                return;
            Composite container = (Composite)Global.invoke(editor, "getContainer"); //$NON-NLS-1$
            if (container == null || container.isDisposed())
                return;
            IFormPage page = ((FormEditor)editor).findPage(STOCK_PAGE_ID);
            if (page instanceof FormPage stock && stock.getPartControl() == null && PENDING_PREVIEW.contains(editor))
            {
                // Элементы штатной страницы создаются при первом показе — без них нет и «Готового текста».
                ((FormEditor)editor).setActivePage(STOCK_PAGE_ID);
                return;
            }
            if (!(page instanceof FormPage formPage) || !(page instanceof IDtGranularEditorEmbeddedEditorPage<?> embedded)
                || formPage.getManagedForm() == null || formPage.getPartControl() == null
                || formPage.getPartControl().isDisposed())
                return;
            Decoration existing = DECORATIONS.get(page);
            if (existing != null)
            {
                existing.syncPreview();
                showPendingPreview(editor, existing);
                return;
            }
            if (!(embedded.getEmbeddedEditor() instanceof ITextEditor textEditor)
                || !(textEditor.getEditorInput() instanceof IFileEditorInput fileInput)
                || !(textEditor.getAdapter(ITextOperationTarget.class) instanceof SourceViewer viewer)
                || viewer.getTextWidget() == null || viewer.getDocument() == null)
                return;
            Decoration decoration = new Decoration(editor, formPage, textEditor, viewer, fileInput.getFile());
            DECORATIONS.put(page, decoration);
            decoration.install();
            showPendingPreview(editor, decoration);
        }
        catch (RuntimeException | LinkageError e)
        {
            // Вкладка не дорабатывается — остаётся штатной.
        }
    }

    // =========================================================================
    // Команда навигатора «Открыть готовый текст»
    // =========================================================================

    static boolean isTextTemplate(Object object)
    {
        return object instanceof BasicTemplate template && template.getTemplateType() == TemplateType.TEXT_DOCUMENT;
    }

    /** Формат макета без открытия редактора: ручной выбор или по содержимому; {@code null} — файл не найден. */
    static Format formatOf(BasicTemplate template)
    {
        IFile file = templateFile(template);
        if (file == null || !file.exists())
            return null;
        Format chosen = loadChoice(file);
        if (chosen != null)
            return chosen;
        try (InputStream in = file.getContents(true))
        {
            return FormattedTextEditor.detect(new String(in.readAllBytes(), file.getCharset()));
        }
        catch (CoreException | IOException e)
        {
            return null;
        }
    }

    /** Файл макета — как его находит штатная страница ({@code IProjectFileSystemSupport.getFile}). */
    private static IFile templateFile(BasicTemplate template)
    {
        try
        {
            IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
            IProjectFileSystemSupportProvider provider = Global.getOsgiService(IProjectFileSystemSupportProvider.class);
            if (provider == null)
                provider = ServiceAccess.get(IProjectFileSystemSupportProvider.class);
            IProject project = lookup != null ? lookup.getProject(template) : null;
            IProjectFileSystemSupport support = provider != null && project != null
                ? provider.getProjectFileSystemSupport(project) : null;
            return support != null ? support.getFile(template, MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE) : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** Открыть редактор макета на вкладке «Готовый текст» (она появляется после доработки штатной вкладки). */
    static void openPreview(BasicTemplate template)
    {
        IEditorPart part = new OpenHelper().openEditor(template);
        if (!(part instanceof DtGranularEditor<?> editor))
            return;
        PENDING_PREVIEW.add(editor);
        Display.getDefault().asyncExec(() -> decorate(editor));
    }

    private static void showPendingPreview(DtGranularEditor<?> editor, Decoration decoration)
    {
        if (!PENDING_PREVIEW.remove(editor))
            return;
        FormEditor formEditor = editor;
        if (formEditor.findPage(PREVIEW_PAGE_ID) != null)
        {
            formEditor.setActivePage(PREVIEW_PAGE_ID);
            return;
        }
        formEditor.setActivePage(STOCK_PAGE_ID);
        ToastNotification.show(OPEN_PREVIEW_TEXT, "У формата «" + decoration.formatted.format().title //$NON-NLS-1$
            + "» нет готового текста. Готовый текст есть у Markdown и HTML.", 5000); //$NON-NLS-1$
    }


    private static Format loadChoice(IFile file)
    {
        try
        {
            Format format = Format.parse(file.getPersistentProperty(FORMAT_PROPERTY));
            return format != null && format.isAvailable() ? format : null;
        }
        catch (CoreException e)
        {
            return null;
        }
    }

    private static void saveChoice(IFile file, Format chosen)
    {
        try
        {
            file.setPersistentProperty(FORMAT_PROPERTY, chosen != null ? chosen.name() : null);
        }
        catch (CoreException e)
        {
            // Файл недоступен — выбор действует до закрытия редактора.
        }
    }

    // =========================================================================
    // Доработка штатной страницы
    // =========================================================================

    /** Панель формата и подсветка одного экземпляра штатной страницы «Сырой текст». */
    static final class Decoration
    {
        final DtGranularEditor<?> editor;

        final FormPage page;

        final ITextEditor textEditor;

        final SourceViewer viewer;

        final IFile file;

        final FormattedTextEditor formatted;

        Decoration(DtGranularEditor<?> editor, FormPage page, ITextEditor textEditor, SourceViewer viewer, IFile file)
        {
            this.editor = editor;
            this.page = page;
            this.textEditor = textEditor;
            this.viewer = viewer;
            this.file = file;
            // Настройка просмотрщика от EDT — для формата «Обычный».
            Object configuration = Global.invoke(textEditor, "getSourceViewerConfiguration"); //$NON-NLS-1$
            SourceViewerConfiguration stockConfiguration = configuration instanceof SourceViewerConfiguration stock
                ? stock : new TextSourceViewerConfiguration(EditorsUI.getPreferenceStore());
            this.formatted = new FormattedTextEditor(viewer, stockConfiguration,
                textEditor.getDocumentProvider() != null
                    ? textEditor.getDocumentProvider().getAnnotationModel(textEditor.getEditorInput()) : null,
                this::syncPreview);
        }

        void install()
        {
            activateRawTextContext();
            // Подсветка вхождений (в т.ч. после Ctrl+F3) ищет просмотрщик поля, а вкладку
            // текстового макета (не Xtext) по редактору не находит — регистрируем явно.
            TextEditorOccurrencesSupport.registerViewer(viewer);
            installToolbar();
            formatted.start(loadChoice(file));
        }

        /**
         * Контекст клавиш вкладки (Ctrl+F3 / Ctrl+Shift+F3 — быстрый поиск плагина). Сервис контекстов
         * сайта встроенного редактора: контекст действует, только пока вкладка активна.
         */
        private void activateRawTextContext()
        {
            IContextService contexts = textEditor.getSite() != null
                ? textEditor.getSite().getService(IContextService.class) : null;
            if (contexts != null)
                contexts.activateContext(RAW_TEXT_CONTEXT_ID);
        }

        /** «Готовый текст» — у форматов с оформленным видом; EDT мог убрать вкладку при пересоздании страниц. */
        @SuppressWarnings("unchecked")
        void syncPreview()
        {
            DtGranularEditor<BasicTemplate> templateEditor = (DtGranularEditor<BasicTemplate>)editor;
            IFormPage existing = ((FormEditor)editor).findPage(PREVIEW_PAGE_ID);
            Format format = formatted.format();
            boolean wanted = format != null && format.hasPreview();
            try
            {
                if (existing instanceof PreviewPage preview && wanted)
                {
                    preview.setSource(formatted);
                    return;
                }
                if (existing instanceof DtGranularEditorPage<?> old)
                    templateEditor.removePage((DtGranularEditorPage<BasicTemplate>)old);
                if (wanted)
                {
                    PreviewPage preview = new PreviewPage(formatted);
                    preview.initialize(editor);
                    templateEditor.addPageForFeatures(preview);
                }
                MdEditorTabsHook.requestRefresh(editor);
            }
            catch (PartInitException | RuntimeException | LinkageError e)
            {
                // Без вкладки «Готовый текст» — сырой текст работает как обычно.
            }
        }

        /**
         * Строка панели над текстом у левого края; текст редактора — под ней. Подпись кнопки меняет
         * ширину, поэтому панель не в заголовке формы (там она выровнена вправо и съезжала бы).
         */
        private void installToolbar()
        {
            Composite body = page.getManagedForm().getForm().getBody();
            Control[] editorControls = body.getChildren();
            GridLayout layout = new GridLayout(1, false);
            layout.marginWidth = 0;
            layout.marginHeight = 0;
            layout.verticalSpacing = 0;
            body.setLayout(layout);
            // Полоса на всю ширину поля и линия под ней — граница панели и текста.
            ToolBar toolBar = new ToolBar(body, SWT.FLAT | SWT.HORIZONTAL);
            toolBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            Label separator = new Label(body, SWT.SEPARATOR | SWT.HORIZONTAL);
            separator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            separator.moveAbove(null);
            toolBar.moveAbove(null);
            for (Control control : editorControls)
            {
                GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
                // Предпочтительный размер редактора (весь текст) не должен раздувать форму страницы:
                // прокручивается сам редактор.
                data.widthHint = 100;
                data.heightHint = 100;
                control.setLayoutData(data);
            }
            formatted.createFormatItem(toolBar,
                "Формат сырого текста макета: подсветка и вкладка «Готовый текст».\n" //$NON-NLS-1$
                    + "«Авто» — по содержимому; ручной выбор запоминается для макета.", //$NON-NLS-1$
                Global.pluginSignForTooltip(),
                chosen -> saveChoice(file, chosen));
            body.layout(true, true);
        }
    }

    // =========================================================================
    // Вкладка «Готовый текст»
    // =========================================================================

    /** Оформленный вид сырого текста (Markdown, HTML) — {@link FormattedTextEditor.Preview} на вкладке редактора. */
    private static final class PreviewPage extends DtGranularEditorPage<BasicTemplate>
    {
        private FormattedTextEditor source;

        private FormattedTextEditor.Preview preview;

        PreviewPage(FormattedTextEditor source)
        {
            super(PREVIEW_PAGE_ID, PREVIEW_PAGE_TITLE);
            this.source = source;
        }

        void setSource(FormattedTextEditor source)
        {
            this.source = source;
            if (preview != null)
                preview.setSource(source);
        }

        @Override
        protected Layout createPageLayout()
        {
            return new FillLayout();
        }

        @Override
        protected void createPageControls(IManagedForm managedForm)
        {
            preview = new FormattedTextEditor.Preview(new Browser(managedForm.getForm().getBody(), SWT.NONE), source);
        }

        @Override
        public void setActive(boolean active)
        {
            if (!active && preview != null)
                preview.transferSelectionToRaw();
            super.setActive(active);
            if (active && preview != null)
                preview.render();
        }
    }
}
