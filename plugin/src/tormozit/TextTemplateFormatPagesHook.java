package tormozit;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentPartitioner;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITypedRegion;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextAttribute;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.reconciler.IReconciler;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.ui.texteditor.spelling.SpellingAnnotation;
import org.eclipse.jface.text.presentation.IPresentationReconciler;
import org.eclipse.jface.text.presentation.PresentationReconciler;
import org.eclipse.jface.text.rules.DefaultDamagerRepairer;
import org.eclipse.jface.text.rules.IToken;
import org.eclipse.jface.text.rules.ITokenScanner;
import org.eclipse.jface.text.rules.Token;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.mylyn.internal.wikitext.ui.editor.syntax.FastMarkupPartitioner;
import org.eclipse.mylyn.wikitext.markdown.MarkdownLanguage;
import org.eclipse.mylyn.wikitext.parser.MarkupParser;
import org.eclipse.mylyn.wikitext.parser.outline.OutlineItem;
import org.eclipse.mylyn.wikitext.parser.outline.OutlineParser;
import org.eclipse.mylyn.wikitext.ui.editor.MarkupSourceViewerConfiguration;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
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
import org.osgi.framework.Bundle;

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

/**
 * Дорабатывает штатную вкладку макета типа «Текстовый документ» (issue #868): вкладка
 * называется «Сырой текст» ({@link MdEditorTabsHook}), над текстом — панель с кнопкой «Формат».
 *
 * <ul>
 * <li>Формат: «Авто» (по содержимому, {@link #detect}), «Текст», «Markdown», «JSON», «XML», «HTML».
 * Ручной выбор хранится в свойстве файла макета ({@link #FORMAT_PROPERTY}).</li>
 * <li>Формат меняет только настройку подсветки штатного просмотрщика ({@code SourceViewer.configure}):
 * редактор, команды, выделение и переходы остаются штатными, страницы EDT не трогаются.</li>
 * <li>Для Markdown и HTML добавляется вкладка «Готовый текст» — оформленный документ.</li>
 * </ul>
 *
 * <p>EDT пересоздаёт страницы редактора (смена входа, обновление модели): доработка ставится на
 * каждый новый экземпляр штатной страницы — при смене вкладки, входа и активации редактора.
 * Markdown — через Mylyn WikiText; без его бандлов (зависимости {@code resolution:=optional})
 * формата Markdown в списке нет.
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

    private static final String WIKITEXT_UI_BUNDLE = "org.eclipse.mylyn.wikitext.ui"; //$NON-NLS-1$

    private static final String WIKITEXT_MARKDOWN_BUNDLE = "org.eclipse.mylyn.wikitext.markdown"; //$NON-NLS-1$

    private static final int HOOK_RETRY_DELAY_MS = 200;

    private static final int HOOK_MAX_ATTEMPTS = 150;

    /** Сколько первых строк текста смотрим при определении Markdown. */
    private static final int DETECT_MAX_LINES = 400;

    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}\\s+\\S"); //$NON-NLS-1$

    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)"); //$NON-NLS-1$

    private static final Pattern LINK = Pattern.compile("\\[[^\\]\\n]+\\]\\([^)\\n]+\\)"); //$NON-NLS-1$

    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*([-*+]|\\d+\\.)\\s+\\S"); //$NON-NLS-1$

    private static final Pattern BOLD = Pattern.compile("\\*\\*[^*\\n]+\\*\\*"); //$NON-NLS-1$

    private static final Pattern HTML_START =
        Pattern.compile("^(<!doctype\\s+html|<html[\\s>]|<head[\\s>]|<body[\\s>])", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    /** Формат сырого текста макета. */
    enum Format
    {
        TEXT("Текст"), //$NON-NLS-1$
        MARKDOWN("Markdown"), //$NON-NLS-1$
        JSON("JSON"), //$NON-NLS-1$
        XML("XML"), //$NON-NLS-1$
        HTML("HTML"); //$NON-NLS-1$

        final String title;

        Format(String title)
        {
            this.title = title;
        }

        boolean hasPreview()
        {
            return this == MARKDOWN || this == HTML;
        }

        boolean isAvailable()
        {
            return this != MARKDOWN || isWikiTextAvailable();
        }

        static Format parse(String value)
        {
            for (Format format : values())
                if (format.name().equals(value))
                    return format;
            return null;
        }
    }

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

    /** Бандлы WikiText есть в установке (классы из них трогаем только после этой проверки). */
    static boolean isWikiTextAvailable()
    {
        return isResolved(Platform.getBundle(WIKITEXT_UI_BUNDLE))
            && isResolved(Platform.getBundle(WIKITEXT_MARKDOWN_BUNDLE));
    }

    private static boolean isResolved(Bundle bundle)
    {
        return bundle != null && (bundle.getState() & (Bundle.RESOLVED | Bundle.STARTING | Bundle.ACTIVE)) != 0;
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
            return detect(new String(in.readAllBytes(), file.getCharset()));
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
        ToastNotification.show(OPEN_PREVIEW_TEXT, "У формата «" + decoration.format.title //$NON-NLS-1$
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

    /**
     * Текст фрагмента без разметки — как он выглядит в «Готовом тексте»: без тегов, маркеров
     * заголовков, списков, цитат, выделения и ссылок; первая непустая строка, не длиннее 120 символов.
     */
    static String plainText(String source)
    {
        if (source == null)
            return null;
        for (String line : source.split("\\R")) //$NON-NLS-1$
        {
            String text = line.replaceAll("<[^>]*>", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("^\\s*(#{1,6}\\s+|>\\s?|[-*+]\\s+|\\d+\\.\\s+)", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("[*_`~]", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("\\s+", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .strip();
            if (!text.isEmpty())
                return text.length() > 120 ? text.substring(0, 120).strip() : text;
        }
        return null;
    }

    // =========================================================================
    // Определение формата
    // =========================================================================

    static Format detect(String text)
    {
        if (text == null)
            return Format.TEXT;
        String trimmed = text.strip();
        if (trimmed.isEmpty())
            return Format.TEXT;
        char first = trimmed.charAt(0);
        char last = trimmed.charAt(trimmed.length() - 1);
        if ((first == '{' && last == '}') || (first == '[' && last == ']'))
            return Format.JSON;
        if (first == '<')
            return HTML_START.matcher(trimmed).find() ? Format.HTML : Format.XML;
        return isWikiTextAvailable() && looksLikeMarkdown(trimmed) ? Format.MARKDOWN : Format.TEXT;
    }

    /** Похож ли текст на Markdown: заголовки {@code #}, блоки кода, ссылки {@code [текст](адрес)}, списки, {@code **}. */
    static boolean looksLikeMarkdown(String text)
    {
        int headings = 0;
        int fences = 0;
        int links = 0;
        int lists = 0;
        int bold = 0;
        String[] lines = text.split("\\R", DETECT_MAX_LINES + 1); //$NON-NLS-1$
        for (int i = 0; i < lines.length && i < DETECT_MAX_LINES; i++)
        {
            String line = lines[i];
            if (HEADING.matcher(line).find())
                headings++;
            if (FENCE.matcher(line).find())
                fences++;
            if (LINK.matcher(line).find())
                links++;
            if (LIST_ITEM.matcher(line).find())
                lists++;
            if (BOLD.matcher(line).find())
                bold++;
        }
        if (headings + fences + links == 0)
            return false;
        return headings * 3 + fences * 3 + links * 2 + bold + (lists >= 2 ? 1 : 0) >= 3;
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

        /** Настройка просмотрщика от EDT — для формата «Текст». */
        private final SourceViewerConfiguration stockConfiguration;

        private IDocumentPartitioner stockPartitioner;

        private IDocumentPartitioner markdownPartitioner;

        private Format detected;

        private Format chosen;

        private Format format;

        private ToolItem formatItem;

        private Menu formatMenu;

        Decoration(DtGranularEditor<?> editor, FormPage page, ITextEditor textEditor, SourceViewer viewer, IFile file)
        {
            this.editor = editor;
            this.page = page;
            this.textEditor = textEditor;
            this.viewer = viewer;
            this.file = file;
            Object configuration = Global.invoke(textEditor, "getSourceViewerConfiguration"); //$NON-NLS-1$
            this.stockConfiguration = configuration instanceof SourceViewerConfiguration stock
                ? stock : new TextSourceViewerConfiguration(EditorsUI.getPreferenceStore());
        }

        void install()
        {
            detected = detect(documentText());
            chosen = loadChoice(file);
            activateRawTextContext();
            // Подсветка вхождений (в т.ч. после Ctrl+F3) ищет просмотрщик поля, а вкладку
            // текстового макета (не Xtext) по редактору не находит — регистрируем явно.
            TextEditorOccurrencesSupport.registerViewer(viewer);
            installToolbar();
            apply(chosen != null ? chosen : detected);
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

        String documentText()
        {
            IDocument document = viewer.getDocument();
            return document != null ? document.get() : null;
        }

        int caretOffset()
        {
            ITextSelection selection = selection();
            return selection != null ? selection.getOffset() : 0;
        }

        ITextSelection selection()
        {
            ISelectionProvider provider = textEditor.getSelectionProvider();
            return provider != null && provider.getSelection() instanceof ITextSelection selection ? selection : null;
        }

        /** Что искать в «Готовом тексте»: выделенный фрагмент, без выделения — строка под кареткой. */
        String selectionProbe()
        {
            ITextSelection selection = selection();
            IDocument document = viewer.getDocument();
            if (selection == null || document == null)
                return null;
            try
            {
                if (selection.getLength() > 0)
                    return plainText(selection.getText());
                int line = document.getLineOfOffset(Math.min(selection.getOffset(), document.getLength()));
                IRegion region = document.getLineInformation(line);
                return plainText(document.get(region.getOffset(), region.getLength()));
            }
            catch (BadLocationException e)
            {
                return null;
            }
        }

        /**
         * Выделить в сыром тексте фрагмент, выделенный в «Готовом тексте»: ближайшее к каретке
         * вхождение; между словами допускаются пробелы и символы разметки ({@code ** _ ` # >} и т.п.).
         */
        void selectInRaw(String rendered)
        {
            String text = documentText();
            if (text == null || rendered == null)
                return;
            String[] words = rendered.strip().split("\\s+"); //$NON-NLS-1$
            if (words.length == 0 || words[0].isEmpty())
                return;
            StringBuilder regex = new StringBuilder();
            for (String word : words)
            {
                if (regex.length() > 0)
                    regex.append("(?:\\s|<[^>]*>|[*_`~#>\\[\\]()])+"); //$NON-NLS-1$
                regex.append(Pattern.quote(word));
            }
            java.util.regex.Matcher matcher = Pattern.compile(regex.toString()).matcher(text);
            int caret = caretOffset();
            int bestStart = -1;
            int bestEnd = -1;
            while (matcher.find())
            {
                if (bestStart < 0 || Math.abs(matcher.start() - caret) < Math.abs(bestStart - caret))
                {
                    bestStart = matcher.start();
                    bestEnd = matcher.end();
                }
            }
            if (bestStart >= 0)
                textEditor.selectAndReveal(bestStart, bestEnd - bestStart);
        }

        /** Выбор в меню «Формат»: {@code null} — «Авто». */
        void choose(Format value)
        {
            chosen = value;
            saveChoice(file, value);
            detected = detect(documentText());
            apply(value != null ? value : detected);
        }

        /** Подсветка формата на штатном просмотрщике; выделение, каретка и редактор не меняются. */
        private void apply(Format value)
        {
            IDocument document = viewer.getDocument();
            if (document == null || viewer.getTextWidget() == null || viewer.getTextWidget().isDisposed())
                return;
            format = value;
            viewer.unconfigure();
            restorePartitioner(document);
            SourceViewerConfiguration configuration = switch (value)
            {
                case MARKDOWN ->
                {
                    stockPartitioner = document.getDocumentPartitioner();
                    markdownPartitioner = MarkdownSupport.installPartitioner(document);
                    yield MarkdownSupport.createConfiguration();
                }
                case JSON -> new ScannerConfiguration(new JsonScanner(), false);
                case XML, HTML -> new ScannerConfiguration(new MarkupTagScanner(), true);
                default -> stockConfiguration;
            };
            viewer.configure(configuration);
            if (value != Format.TEXT)
                removeSpellingAnnotations();
            viewer.invalidateTextPresentation();
            updateFormatItem();
            syncPreview();
        }

        /** Пометки орфографии прежней настройки: её проверка снята вместе с ней, а пометки остались бы. */
        private void removeSpellingAnnotations()
        {
            IAnnotationModel model = textEditor.getDocumentProvider() != null
                ? textEditor.getDocumentProvider().getAnnotationModel(textEditor.getEditorInput()) : null;
            if (model == null)
                return;
            java.util.List<Annotation> spelling = new java.util.ArrayList<>();
            for (java.util.Iterator<Annotation> it = model.getAnnotationIterator(); it.hasNext();)
            {
                Annotation annotation = it.next();
                if (annotation instanceof SpellingAnnotation)
                    spelling.add(annotation);
            }
            if (model instanceof IAnnotationModelExtension extension)
                extension.replaceAnnotations(spelling.toArray(new Annotation[0]), null);
            else
                spelling.forEach(model::removeAnnotation);
        }

        private void restorePartitioner(IDocument document)
        {
            if (markdownPartitioner == null)
                return;
            if (document.getDocumentPartitioner() == markdownPartitioner)
            {
                markdownPartitioner.disconnect();
                if (stockPartitioner != null)
                    stockPartitioner.connect(document);
                document.setDocumentPartitioner(stockPartitioner);
            }
            markdownPartitioner = null;
            stockPartitioner = null;
        }

        /** «Готовый текст» — у форматов с оформленным видом; EDT мог убрать вкладку при пересоздании страниц. */
        @SuppressWarnings("unchecked")
        void syncPreview()
        {
            DtGranularEditor<BasicTemplate> templateEditor = (DtGranularEditor<BasicTemplate>)editor;
            IFormPage existing = ((FormEditor)editor).findPage(PREVIEW_PAGE_ID);
            boolean wanted = format != null && format.hasPreview();
            try
            {
                if (existing instanceof PreviewPage preview && wanted)
                {
                    preview.setSource(this);
                    return;
                }
                if (existing instanceof DtGranularEditorPage<?> old)
                    templateEditor.removePage((DtGranularEditorPage<BasicTemplate>)old);
                if (wanted)
                {
                    PreviewPage preview = new PreviewPage(this);
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

            formatItem = new ToolItem(toolBar, SWT.DROP_DOWN);
            formatItem.setToolTipText(TooltipText.wrap(toolBar,
                "Формат сырого текста макета: подсветка и вкладка «Готовый текст».\n" //$NON-NLS-1$
                    + "«Авто» — по содержимому; ручной выбор запоминается для макета." //$NON-NLS-1$
                    + Global.pluginSignForTooltip()));
            formatMenu = new Menu(toolBar);
            // Меню — по щелчку и на стрелке, и на самой кнопке.
            formatItem.addListener(SWT.Selection, e ->
            {
                fillFormatMenu();
                Rectangle bounds = formatItem.getBounds();
                formatMenu.setLocation(toolBar.toDisplay(bounds.x, bounds.y + bounds.height));
                formatMenu.setVisible(true);
            });
            body.layout(true, true);
        }

        private void updateFormatItem()
        {
            if (formatItem == null || formatItem.isDisposed())
                return;
            // Ручной выбор видно сразу: иначе его легко принять за ошибку определения формата.
            String suffix = chosen == null ? " (авто)" //$NON-NLS-1$
                : chosen == detected ? " (выбран)" : " (выбран; авто — " + detected.title + ')'; //$NON-NLS-1$ //$NON-NLS-2$
            formatItem.setText("Формат: " + format.title + suffix); //$NON-NLS-1$
            formatItem.getParent().getParent().layout(true, true);
        }

        private void fillFormatMenu()
        {
            for (MenuItem item : formatMenu.getItems())
                item.dispose();
            addFormatItem("Авто (" + detect(documentText()).title + ")", null); //$NON-NLS-1$ //$NON-NLS-2$
            new MenuItem(formatMenu, SWT.SEPARATOR);
            for (Format value : Format.values())
                if (value.isAvailable())
                    addFormatItem(value.title, value);
        }

        private void addFormatItem(String text, Format value)
        {
            MenuItem item = new MenuItem(formatMenu, SWT.RADIO);
            item.setText(text);
            item.setSelection(chosen == value);
            item.addListener(SWT.Selection, e ->
            {
                if (item.getSelection() && chosen != value)
                    item.getDisplay().asyncExec(() -> choose(value));
            });
        }
    }

    // =========================================================================
    // Подсветка
    // =========================================================================

    /** Настройка просмотрщика: штатные возможности текстового редактора и подсветка своим сканером. */
    private static final class ScannerConfiguration extends TextSourceViewerConfiguration
    {
        private final ITokenScanner scanner;

        /** Перекрашивать весь документ: у разметки многострочные теги и комментарии. */
        private final boolean wholeDocument;

        ScannerConfiguration(ITokenScanner scanner, boolean wholeDocument)
        {
            super(EditorsUI.getPreferenceStore());
            this.scanner = scanner;
            this.wholeDocument = wholeDocument;
        }

        /** Без проверки орфографии: в данных и разметке она находит «ошибки» в именах, ключах и значениях. */
        @Override
        public IReconciler getReconciler(ISourceViewer sourceViewer)
        {
            return null;
        }

        @Override
        public IPresentationReconciler getPresentationReconciler(ISourceViewer sourceViewer)
        {
            PresentationReconciler reconciler = new PresentationReconciler();
            DefaultDamagerRepairer damagerRepairer = wholeDocument ? new WholeDocumentDamagerRepairer(scanner)
                : new DefaultDamagerRepairer(scanner);
            reconciler.setDamager(damagerRepairer, IDocument.DEFAULT_CONTENT_TYPE);
            reconciler.setRepairer(damagerRepairer, IDocument.DEFAULT_CONTENT_TYPE);
            return reconciler;
        }
    }

    /** Повреждённая область — весь документ: сканер разметки начинает с начала и знает, внутри ли тега. */
    private static final class WholeDocumentDamagerRepairer extends DefaultDamagerRepairer
    {
        WholeDocumentDamagerRepairer(ITokenScanner scanner)
        {
            super(scanner);
        }

        @Override
        public IRegion getDamageRegion(ITypedRegion partition, DocumentEvent event, boolean documentPartitioningChanged)
        {
            return new Region(0, event.getDocument().getLength());
        }
    }

    /** Цвета подсветки: пары для светлой и тёмной темы. */
    private static final class Styles
    {
        static IToken token(int light, int dark, int style)
        {
            int rgb = ThemeAwareColors.isDarkTheme() ? dark : light;
            Color color = new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            return new Token(new TextAttribute(color, null, style));
        }

        static IToken key()       { return token(0x0451A5, 0x9CDCFE, SWT.NORMAL); }
        static IToken string()    { return token(0xA31515, 0xCE9178, SWT.NORMAL); }
        static IToken number()    { return token(0x098658, 0xB5CEA8, SWT.NORMAL); }
        static IToken literal()   { return token(0x0000FF, 0x569CD6, SWT.BOLD); }
        static IToken punct()     { return token(0x5A5A5A, 0xB0B0B0, SWT.NORMAL); }
        static IToken tag()       { return token(0x800000, 0x569CD6, SWT.NORMAL); }
        static IToken attribute() { return token(0xE50000, 0x9CDCFE, SWT.NORMAL); }
        static IToken comment()   { return token(0x008000, 0x6A9955, SWT.ITALIC); }
        static IToken special()   { return token(0x800080, 0xC586C0, SWT.NORMAL); }
    }

    /** Базовый посимвольный сканер области документа. */
    private abstract static class CharScanner implements ITokenScanner
    {
        protected IDocument document;

        protected int position;

        protected int end;

        protected int tokenStart;

        @Override
        public void setRange(IDocument document, int offset, int length)
        {
            this.document = document;
            this.position = offset;
            this.end = offset + length;
            this.tokenStart = offset;
        }

        @Override
        public int getTokenOffset()
        {
            return tokenStart;
        }

        @Override
        public int getTokenLength()
        {
            return position - tokenStart;
        }

        protected char charAt(int offset)
        {
            try
            {
                return offset < document.getLength() ? document.getChar(offset) : '\0';
            }
            catch (BadLocationException e)
            {
                return '\0';
            }
        }

        protected boolean startsWith(String text)
        {
            for (int i = 0; i < text.length(); i++)
                if (position + i >= end || charAt(position + i) != text.charAt(i))
                    return false;
            return true;
        }

        /** Пропустить до {@code terminator} включительно или до конца области. */
        protected void skipPast(String terminator)
        {
            while (position < end && !startsWith(terminator))
                position++;
            position = Math.min(end, position + terminator.length());
        }

        protected IToken whitespace()
        {
            while (position < end && Character.isWhitespace(charAt(position)))
                position++;
            return Token.WHITESPACE;
        }
    }

    /** JSON: ключи, строки, числа, {@code true/false/null}, скобки и разделители. */
    private static final class JsonScanner extends CharScanner
    {
        private final IToken key = Styles.key();

        private final IToken string = Styles.string();

        private final IToken number = Styles.number();

        private final IToken literal = Styles.literal();

        private final IToken punct = Styles.punct();

        @Override
        public IToken nextToken()
        {
            tokenStart = position;
            if (position >= end)
                return Token.EOF;
            char c = charAt(position);
            if (Character.isWhitespace(c))
                return whitespace();
            if (c == '"')
            {
                position++;
                while (position < end)
                {
                    char ch = charAt(position++);
                    if (ch == '\\')
                        position++;
                    else if (ch == '"' || ch == '\n' || ch == '\r')
                        break;
                }
                position = Math.min(position, end);
                return isFollowedByColon(position) ? key : string;
            }
            if (c == '-' || Character.isDigit(c))
            {
                position++;
                while (position < end && "0123456789.eE+-".indexOf(charAt(position)) >= 0) //$NON-NLS-1$
                    position++;
                return number;
            }
            if (Character.isLetter(c))
            {
                while (position < end && Character.isLetter(charAt(position)))
                    position++;
                String word = text(tokenStart, position);
                return "true".equals(word) || "false".equals(word) || "null".equals(word) ? literal : Token.UNDEFINED; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            position++;
            return "{}[]:,".indexOf(c) >= 0 ? punct : Token.UNDEFINED; //$NON-NLS-1$
        }

        private boolean isFollowedByColon(int offset)
        {
            int length = document.getLength();
            for (int i = offset; i < length; i++)
            {
                char c = charAt(i);
                if (!Character.isWhitespace(c))
                    return c == ':';
            }
            return false;
        }

        private String text(int from, int to)
        {
            try
            {
                return document.get(from, to - from);
            }
            catch (BadLocationException e)
            {
                return ""; //$NON-NLS-1$
            }
        }
    }

    /** XML и HTML: теги, атрибуты, значения, комментарии, CDATA, {@code <?…?>}, {@code <!…>}, сущности. */
    private static final class MarkupTagScanner extends CharScanner
    {
        private final IToken tag = Styles.tag();

        private final IToken attribute = Styles.attribute();

        private final IToken value = Styles.string();

        private final IToken comment = Styles.comment();

        private final IToken special = Styles.special();

        private final IToken punct = Styles.punct();

        /** Позиция внутри открытого тега (после имени, до {@code >}). */
        private boolean inTag;

        @Override
        public void setRange(IDocument document, int offset, int length)
        {
            super.setRange(document, offset, length);
            inTag = false;
        }

        @Override
        public IToken nextToken()
        {
            tokenStart = position;
            if (position >= end)
                return Token.EOF;
            char c = charAt(position);
            if (inTag)
                return insideTag(c);
            if (startsWith("<!--")) //$NON-NLS-1$
            {
                skipPast("-->"); //$NON-NLS-1$
                return comment;
            }
            if (startsWith("<![CDATA[")) //$NON-NLS-1$
            {
                skipPast("]]>"); //$NON-NLS-1$
                return special;
            }
            if (startsWith("<?")) //$NON-NLS-1$
            {
                skipPast("?>"); //$NON-NLS-1$
                return special;
            }
            if (startsWith("<!")) //$NON-NLS-1$
            {
                skipPast(">"); //$NON-NLS-1$
                return special;
            }
            if (c == '<')
            {
                position++;
                if (position < end && charAt(position) == '/')
                    position++;
                while (position < end && isNameChar(charAt(position)))
                    position++;
                inTag = true;
                return tag;
            }
            if (c == '&')
            {
                position++;
                while (position < end && charAt(position) != ';' && !Character.isWhitespace(charAt(position))
                    && charAt(position) != '<')
                    position++;
                if (position < end && charAt(position) == ';')
                    position++;
                return special;
            }
            while (position < end && charAt(position) != '<' && charAt(position) != '&')
                position++;
            return Token.UNDEFINED;
        }

        private IToken insideTag(char c)
        {
            if (Character.isWhitespace(c))
                return whitespace();
            if (c == '>' || (c == '/' && position + 1 < end && charAt(position + 1) == '>'))
            {
                position += c == '>' ? 1 : 2;
                inTag = false;
                return tag;
            }
            if (c == '"' || c == '\'')
            {
                position++;
                while (position < end && charAt(position) != c)
                    position++;
                position = Math.min(end, position + 1);
                return value;
            }
            if (c == '=')
            {
                position++;
                return punct;
            }
            if (isNameChar(c))
            {
                while (position < end && isNameChar(charAt(position)))
                    position++;
                return attribute;
            }
            position++;
            return Token.UNDEFINED;
        }

        private static boolean isNameChar(char c)
        {
            return Character.isLetterOrDigit(c) || c == ':' || c == '-' || c == '_' || c == '.';
        }
    }

    // =========================================================================
    // Markdown (классы WikiText — только здесь, после проверки бандлов)
    // =========================================================================

    private static final class MarkdownSupport
    {
        static SourceViewerConfiguration createConfiguration()
        {
            MarkupSourceViewerConfiguration configuration = new MarkupSourceViewerConfiguration(EditorsUI.getPreferenceStore())
            {
                /** Без проверки орфографии: в макетах в Markdown — код, имена и адреса, она их «исправляет». */
                @Override
                public IReconciler getReconciler(ISourceViewer sourceViewer)
                {
                    return null;
                }
            };
            configuration.setMarkupLanguage(new MarkdownLanguage());
            return configuration;
        }

        /** Разметка WikiText читает разбор из своего разбивщика документа (как MarkupDocumentProvider). */
        static IDocumentPartitioner installPartitioner(IDocument document)
        {
            FastMarkupPartitioner partitioner = new FastMarkupPartitioner();
            partitioner.setMarkupLanguage(new MarkdownLanguage());
            IDocumentPartitioner previous = document.getDocumentPartitioner();
            if (previous != null)
                previous.disconnect();
            partitioner.connect(document);
            document.setDocumentPartitioner(partitioner);
            return partitioner;
        }

        static String toHtml(String text)
        {
            return new MarkupParser(new MarkdownLanguage()).parseToHtml(text);
        }

        /** Id заголовка раздела, в котором стоит каретка; {@code null} — каретка до первого заголовка. */
        static String headingIdAt(String text, int offset)
        {
            OutlineItem root = new OutlineParser(new MarkdownLanguage()).parse(text);
            OutlineItem item = root != null ? root.findNearestMatchingOffset(offset) : null;
            return item != null && item != root ? item.getId() : null;
        }
    }

    // =========================================================================
    // Вкладка «Готовый текст»
    // =========================================================================

    /** Оформленный вид сырого текста (Markdown, HTML); при показе — к разделу под кареткой. */
    private static final class PreviewPage extends DtGranularEditorPage<BasicTemplate>
    {
        private Decoration source;

        private Browser browser;

        private String pendingAnchor;

        /** Что найти после загрузки и выделять ли найденное (в сыром тексте был выделен фрагмент). */
        private String pendingProbe;

        private boolean pendingKeepSelection;

        /** Выделение, поставленное при показе: при уходе со вкладки его в сырой текст не переносим. */
        private String placedSelection;

        PreviewPage(Decoration source)
        {
            super(PREVIEW_PAGE_ID, PREVIEW_PAGE_TITLE);
            this.source = source;
        }

        void setSource(Decoration source)
        {
            this.source = source;
        }

        @Override
        protected Layout createPageLayout()
        {
            return new FillLayout();
        }

        @Override
        protected void createPageControls(IManagedForm managedForm)
        {
            browser = new Browser(managedForm.getForm().getBody(), SWT.NONE);
            browser.addProgressListener(ProgressListener.completedAdapter(e -> scrollToAnchor()));
        }

        @Override
        public void setActive(boolean active)
        {
            if (!active)
                transferSelectionToRaw();
            super.setActive(active);
            if (active)
                render();
        }

        private void render()
        {
            if (browser == null || browser.isDisposed() || source == null)
                return;
            String text = source.documentText();
            if (text == null)
                text = ""; //$NON-NLS-1$
            pendingAnchor = null;
            ITextSelection selection = source.selection();
            pendingProbe = source.selectionProbe();
            pendingKeepSelection = selection != null && selection.getLength() > 0;
            placedSelection = null;
            if (source.format == Format.MARKDOWN)
            {
                pendingAnchor = MarkdownSupport.headingIdAt(text, source.caretOffset());
                browser.setText(withStyle(MarkdownSupport.toHtml(text)));
            }
            else
                browser.setText(text);
        }

        /**
         * После загрузки — к месту сырого текста: выделенный там фрагмент выделяется и здесь;
         * без выделения — прокрутка к строке под кареткой (для Markdown — иначе к её разделу).
         */
        private void scrollToAnchor()
        {
            if (browser == null || browser.isDisposed())
                return;
            if (pendingProbe != null && !pendingProbe.isEmpty())
            {
                Object found = browser.evaluate("return " + findScript(pendingProbe, pendingKeepSelection) + ';'); //$NON-NLS-1$
                if (Boolean.TRUE.equals(found))
                {
                    placedSelection = pendingKeepSelection ? selectedText() : null;
                    return;
                }
            }
            if (pendingAnchor == null)
                return;
            browser.execute("var e=document.getElementById(" + jsString(pendingAnchor) + ");if(e){e.scrollIntoView();}"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** Выделенное здесь — в сырой текст; выделение, поставленное при показе, назад не переносим. */
        private void transferSelectionToRaw()
        {
            if (browser == null || browser.isDisposed() || source == null)
                return;
            String selected = selectedText();
            if (selected == null || selected.isBlank() || selected.equals(placedSelection))
                return;
            source.selectInRaw(selected);
        }

        private String selectedText()
        {
            try
            {
                Object value = browser.evaluate("return window.getSelection ? String(window.getSelection())" //$NON-NLS-1$
                    + " : (document.selection && document.selection.type != 'None' ? document.selection.createRange().text : '');"); //$NON-NLS-1$
                return value instanceof String text ? text : null;
            }
            catch (RuntimeException e)
            {
                return null;
            }
        }

        /** Найти текст на странице: IE ({@code createTextRange}) и Chromium/Edge ({@code window.find}). */
        private static String findScript(String text, boolean keepSelection)
        {
            return "(function(t,k){try{" //$NON-NLS-1$
                + "if(document.body&&document.body.createTextRange){var r=document.body.createTextRange();" //$NON-NLS-1$
                + "if(r.findText(t)){r.scrollIntoView();if(!k){r.collapse(true);}r.select();return true;}return false;}" //$NON-NLS-1$
                + "if(window.find){var s=window.getSelection();s.removeAllRanges();" //$NON-NLS-1$
                + "if(window.find(t,false,false,true,false,false,false)){var n=s.anchorNode;" //$NON-NLS-1$
                + "var e=n&&(n.nodeType==1?n:n.parentNode);if(e&&e.scrollIntoView){e.scrollIntoView();}" //$NON-NLS-1$
                + "if(!k){s.collapseToStart();}return true;}}" //$NON-NLS-1$
                + "}catch(x){}return false;})(" + jsString(text) + ',' + keepSelection + ')'; //$NON-NLS-1$
        }

        private static String jsString(String text)
        {
            return '\'' + text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
                .replace("</", "<\\/") + '\''; //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static String withStyle(String html)
        {
            String style = "<style>body{font-family:sans-serif;line-height:1.4;margin:12px 16px;}" //$NON-NLS-1$
                + "pre,code{font-family:monospace;}pre{padding:8px;border:1px solid #ccc;}" //$NON-NLS-1$
                + "table{border-collapse:collapse;}td,th{border:1px solid #ccc;padding:2px 6px;}</style>"; //$NON-NLS-1$
            int head = html.indexOf("</head>"); //$NON-NLS-1$
            return head >= 0 ? html.substring(0, head) + style + html.substring(head) : style + html;
        }
    }
}
