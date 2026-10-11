package tormozit;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffCache;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffChangedListener;
import org.eclipse.egit.ui.internal.ActionUtils;
import org.eclipse.egit.ui.internal.CompareUtils;
import org.eclipse.egit.ui.internal.commit.DiffDocument;
import org.eclipse.egit.ui.internal.commit.DiffRegionFormatter;
import org.eclipse.egit.ui.internal.commit.DiffViewer;
import org.eclipse.egit.ui.internal.staging.StagingEntry;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Панель «Изменения файла Git»: компактные изменения (unified diff) файлов, выделенных в списках
 * панели «Индексирование Git». Повторяет область изменений панели «История Git»: тот же штатный
 * {@link DiffViewer} EGit с {@link DiffDocument} и {@link DiffRegionFormatter}, те же настройки
 * EGit (предел числа строк, игнорирование пробелов).
 * <p>
 * «Неиндексированные изменения» сравнивают индекс с рабочим каталогом, «Индексированные изменения» —
 * HEAD с индексом. Стороны читает сам {@code DiffFormatter.scan}: у файла рабочего каталога нет
 * blob в Git, поэтому штатный {@code DiffRegionFormatter.write(FileDiff)} (только коммиты) не
 * подходит. Без {@code FileDiff} в документе нет областей файлов, и гиперссылки EGit на версии
 * файла коммита не создаются ({@code DiffViewer$HyperlinkDetector} возвращает {@code null}).
 * Вместо них команда контекстного меню «Показать в редакторе» открывает файл рабочего каталога
 * на строке, соответствующей строке блока изменений.
 * <p>
 * Поля {@code stagedViewer}/{@code unstagedViewer} подтверждены в {@code StagingView} EGit.
 */
public final class GitFileDiffView extends ViewPart
{
    public static final String ID = "tormozit.GitFileDiffView"; //$NON-NLS-1$
    /** Тот же значок, что у панели в {@code plugin.xml}, — для кнопки и пункта меню, открывающих её. */
    private static final String ICON_PATH = "icons/eview16/git_file_diff.png"; //$NON-NLS-1$
    private static final String TAG = "GitFileDiffView"; //$NON-NLS-1$
    private static final String DT_STAGING_VIEW_ID = "com._1c.g5.v8.dt.internal.team.ui.views.DtStagingView"; //$NON-NLS-1$
    private static final String EGIT_STAGING_VIEW_ID = "org.eclipse.egit.ui.StagingView"; //$NON-NLS-1$
    private static final String PREF_MAX_DIFF_LINES = "HistoryView_MaxDiffLines"; //$NON-NLS-1$
    private static final String PREF_IGNORE_WHITESPACE = "resourcehistory_ignore_whitespace"; //$NON-NLS-1$
    private static final String NO_FILE = "Выберите файл в панели «Индексирование Git»";

    /** Что показано: файлы одного списка одного репозитория. */
    private static record Request(Repository repository, List<String> paths, boolean staged) {}

    /** Строка файла рабочего каталога, нумерация с единицы. */
    private static record Target(File file, int line) {}

    /** Документ изменений, помнящий, где начинается текст каждого файла. */
    private static final class FileDocument extends DiffDocument
    {
        private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@"); //$NON-NLS-1$
        private static final int MAX_MODULE_BYTES = 20 * 1024 * 1024;
        private final Repository repository;
        /** Новая сторона — индекс («Индексированные изменения»), иначе рабочий каталог. */
        private final boolean staged;
        private final List<Integer> offsets = new ArrayList<>();
        /** {@code null} — файла в новой стороне нет (удалён). */
        private final List<String> paths = new ArrayList<>();

        FileDocument(Repository repository, boolean staged)
        {
            this.repository = repository;
            this.staged = staged;
        }

        /** Тексты файлов документа — для имён и списка методов ({@link GitDiffMethods}). */
        List<GitDiffMethods.Section> sections()
        {
            List<GitDiffMethods.Section> sections = new ArrayList<>();
            for (int i = 0; i < offsets.size(); i++)
            {
                String path = paths.get(i);
                if (path == null)
                    continue;
                int start = offsets.get(i).intValue();
                int end = i + 1 < offsets.size() ? offsets.get(i + 1).intValue() : getLength();
                sections.add(new GitDiffMethods.Section(start, end - start, path, () -> newText(path)));
            }
            return sections;
        }

        /**
         * Полное сравнение файла, на котором текст обрезан (он выводился последним), — как двойной
         * щелчок по файлу в панели «Индексирование Git»: {@code CompareIndexWithHeadActionHandler}
         * для индексированных изменений, {@code CompareWithIndexActionHandler} для остальных.
         */
        void openFullCompare(int truncationOffset, IWorkbenchPage page)
        {
            String path = null;
            for (int i = 0; i < offsets.size() && offsets.get(i).intValue() <= truncationOffset; i++)
                path = paths.get(i) != null ? paths.get(i) : path;
            if (path == null)
                return;
            Path location = new Path(new File(repository.getWorkTree(), path).getAbsolutePath());
            if (staged)
                CompareUtils.compare(location, repository, "Index", "HEAD", false, page); //$NON-NLS-1$ //$NON-NLS-2$
            else
                CompareUtils.compare(location, repository, "HEAD", "Index", true, page); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** Новая версия файла; {@code null}, если её не прочитать или она слишком велика. */
        private String newText(String path)
        {
            try
            {
                if (staged)
                {
                    DirCacheEntry entry = repository.readDirCache().getEntry(path);
                    if (entry == null)
                        return null;
                    ObjectLoader loader = repository.open(entry.getObjectId(), Constants.OBJ_BLOB);
                    return loader.getSize() > MAX_MODULE_BYTES ? null
                        : new String(loader.getCachedBytes(MAX_MODULE_BYTES), StandardCharsets.UTF_8);
                }
                File file = new File(repository.getWorkTree(), path);
                return !file.isFile() || file.length() > MAX_MODULE_BYTES ? null
                    : new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
            catch (IOException error)
            {
                return null;
            }
        }

        void startFile(String path)
        {
            offsets.add(Integer.valueOf(getLength()));
            paths.add(path);
        }

        /**
         * Строка файла, которой соответствует строка документа. Номер считается от заголовка блока
         * «@@ -a,b +c,d @@»: {@code c} плюс число строк новой стороны до искомой. Удалённой строке
         * соответствует место, где она стояла. Строки заголовка файла ведут на его начало.
         */
        Target target(int line) throws BadLocationException
        {
            int offset = getLineOffset(line);
            int index = -1;
            for (int i = 0; i < offsets.size() && offsets.get(i).intValue() <= offset; i++)
                index = i;
            if (index < 0 || paths.get(index) == null)
                return null;
            File file = new File(repository.getWorkTree(), paths.get(index));
            if (!file.isFile())
                return null;
            int first = getLineOfOffset(offsets.get(index).intValue());
            int passed = 0;
            for (int i = line; i >= first; i--)
            {
                IRegion info = getLineInformation(i);
                String text = get(info.getOffset(), info.getLength());
                Matcher header = HUNK.matcher(text);
                if (header.find())
                    return new Target(file, Math.max(1, Integer.parseInt(header.group(1)) + passed));
                if (i < line && !text.startsWith("-") && !text.startsWith("\\")) //$NON-NLS-1$ //$NON-NLS-2$
                    passed++;
            }
            return new Target(file, 1);
        }
    }

    /** «Открыть с помощью → Редактор XML» (org.eclipse.wst.xml.ui), как в {@code FileSearchResultsHook}. */
    private static final String XML_EDITOR_ID =
        "org.eclipse.wst.xml.ui.internal.tabletree.XMLMultiPageEditorPart"; //$NON-NLS-1$
    /** Как в {@code org.eclipse.ui.actions.OpenWithMenu}: не переиспользовать чужой редактор по тому же input. */
    private static final int OPEN_WITH_MATCH =
        IWorkbenchPage.MATCH_INPUT | IWorkbenchPage.MATCH_ID | IWorkbenchPage.MATCH_IGNORE_SIZE;

    /**
     * Модуль открывается редактором модуля EDT. Остальные файлы — текстовым редактором исходника
     * (XML или простым), а не редактором по умолчанию: у редакторов объектов EDT (схема компоновки,
     * форма, mdo) строк файла нет, и показать в них строку нельзя.
     */
    private static void openTarget(Target target)
    {
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        Shell shell = page.getWorkbenchWindow().getShell();
        IFile file = ResourcesPlugin.getWorkspace().getRoot()
            .getFileForLocation(Path.fromOSString(target.file().getAbsolutePath()));
        boolean opened = false;
        if (file != null && file.exists())
            opened = "bsl".equalsIgnoreCase(file.getFileExtension()) //$NON-NLS-1$
                ? GoToDefinition.openBslModuleAtLine(file, target.line(), page, shell)
                : openSourceAtLine(file, target, page);
        // Файл вне рабочей области: штатное открытие EGit, строки у него нумеруются с нуля.
        if (!opened)
            DiffViewer.openFileInEditor(target.file(), target.line() - 1);
    }

    private static boolean openSourceAtLine(IFile file, Target target, IWorkbenchPage page)
    {
        try
        {
            IEditorPart editor = page.openEditor(new FileEditorInput(file),
                isXml(target.file()) ? XML_EDITOR_ID : EditorsUI.DEFAULT_TEXT_EDITOR_ID, true, OPEN_WITH_MATCH);
            ITextEditor text = editor != null ? ProblemViewOpenInTextEditorHandler.findTextEditor(editor) : null;
            if (text != null)
            {
                revealLine(text, target.line());
                // Свежеоткрытый редактор может выставить своё начальное положение уже после открытия.
                Display.getDefault().asyncExec(() -> revealLine(text, target.line()));
            }
            return editor != null;
        }
        catch (Exception error)
        {
            Global.logError(TAG, "open failed file=" + file.getFullPath(), error);
            return false;
        }
    }

    private static void revealLine(ITextEditor text, int line)
    {
        try
        {
            IDocumentProvider provider = text.getDocumentProvider();
            IDocument document = provider != null ? provider.getDocument(text.getEditorInput()) : null;
            if (document == null)
                document = text.getAdapter(IDocument.class);
            if (document == null)
                return;
            int index = Math.min(Math.max(0, line - 1), Math.max(0, document.getNumberOfLines() - 1));
            text.selectAndReveal(document.getLineOffset(index), 0);
        }
        catch (Exception error) { Global.logError(TAG, "reveal failed line=" + line, error); }
    }

    /** Первый значащий символ файла — «<» (после BOM и пробелов). */
    private static boolean isXml(File file)
    {
        try (InputStream stream = new FileInputStream(file))
        {
            for (int value : stream.readNBytes(64))
            {
                int symbol = value & 0xFF;
                if (symbol == 0xEF || symbol == 0xBB || symbol == 0xBF || Character.isWhitespace(symbol))
                    continue;
                return symbol == '<';
            }
        }
        catch (IOException error) { /* Не XML: простой текстовый редактор. */ }
        return false;
    }

    private DiffViewer viewer;
    /** Подключённые списки панели «Индексирование Git» и снятие наших слушателей с них. */
    private final Map<TreeViewer, Runnable> hooked = new IdentityHashMap<>();
    private volatile Request current;
    private volatile int generation;
    private Job job;

    private final IndexDiffChangedListener indexListener = (repository, data) ->
    {
        Request request = current;
        if (request != null && request.repository() == repository)
            schedule(request, true);
    };

    private final IPartListener2 partListener = new IPartListener2()
    {
        @Override public void partOpened(IWorkbenchPartReference ref) { hook(ref); }
        @Override public void partVisible(IWorkbenchPartReference ref) { hook(ref); }
        @Override public void partActivated(IWorkbenchPartReference ref) {}
        @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
        @Override public void partClosed(IWorkbenchPartReference ref) {}
        @Override public void partDeactivated(IWorkbenchPartReference ref) {}
        @Override public void partHidden(IWorkbenchPartReference ref) {}
        @Override public void partInputChanged(IWorkbenchPartReference ref) {}

        private void hook(IWorkbenchPartReference ref)
        {
            if (ref.getPart(false) instanceof IViewPart view && isStagingView(view))
                hookStagingView(view);
        }
    };

    public static ImageDescriptor iconDescriptor()
    {
        Bundle bundle = FrameworkUtil.getBundle(GitFileDiffView.class);
        URL url = bundle != null ? bundle.getEntry(ICON_PATH) : null;
        return url != null ? ImageDescriptor.createFromURL(url) : ImageDescriptor.getMissingImageDescriptor();
    }

    /** Значок из реестра плагина: освобождать не нужно. */
    public static Image icon()
    {
        ImageRegistry registry = Activator.getDefault().getImageRegistry();
        if (registry.getDescriptor(ICON_PATH) == null)
            registry.put(ICON_PATH, iconDescriptor());
        return registry.get(ICON_PATH);
    }

    /** Открывает панель, не забирая фокус у списка файлов. */
    public static void open(IWorkbenchPage page)
    {
        if (page == null)
            return;
        try { page.showView(ID, null, IWorkbenchPage.VIEW_VISIBLE); }
        catch (PartInitException error) { Global.logError(TAG, "open failed", error); }
    }

    private static boolean isStagingView(IViewPart view)
    {
        String id = view.getSite() != null ? view.getSite().getId() : null;
        return DT_STAGING_VIEW_ID.equals(id) || EGIT_STAGING_VIEW_ID.equals(id);
    }

    @Override
    public void createPartControl(Composite parent)
    {
        // разделитель — ради списка методов справа от текста (GitDiffMethods); скрытый список места не занимает
        SashForm sash = new SashForm(parent, SWT.HORIZONTAL);
        viewer = new DiffViewer(sash, null, SWT.H_SCROLL | SWT.V_SCROLL | SWT.MULTI);
        viewer.configure(new DiffViewer.Configuration(EditorsUI.getPreferenceStore()));
        viewer.setEditable(false);
        viewer.setDocument(new Document());
        GitDiffMethods.install(viewer,
            document -> document instanceof FileDocument files ? files.sections() : List.of(),
            () -> new GitDiffMethods.Host(sash, viewer.getControl()), "fileDiff", //$NON-NLS-1$
            offset ->
            {
                if (viewer.getDocument() instanceof FileDocument files)
                    files.openFullCompare(offset, getSite().getPage());
            });
        installTextActions();
        setContentDescription(NO_FILE);

        IWorkbenchPage page = getSite().getPage();
        page.addPartListener(partListener);
        IndexDiffCache.INSTANCE.addIndexDiffChangedListener(indexListener);
        for (IViewReference ref : page.getViewReferences())
        {
            IViewPart view = ref.getView(false);
            if (view != null && isStagingView(view))
                hookStagingView(view);
        }
    }

    /**
     * Строка файла для строки документа, над которой вызвано контекстное меню: под указателем мыши,
     * а при вызове с клавиатуры — строка каретки. Правый щелчок каретку {@code StyledText} не двигает.
     */
    private Target menuTarget(Point menuLocation)
    {
        if (!(viewer.getDocument() instanceof FileDocument document))
            return null;
        try
        {
            int offset = viewer.getSelectedRange().x;
            if (menuLocation != null)
            {
                StyledText text = viewer.getTextWidget();
                int widgetOffset = text.getOffsetAtPoint(text.toControl(menuLocation));
                if (widgetOffset < 0)
                    return null;
                offset = viewer.widgetOffset2ModelOffset(widgetOffset);
            }
            return offset < 0 ? null : document.target(document.getLineOfOffset(offset));
        }
        catch (BadLocationException error) { return null; }
    }

    /** Копирование и выделение всего — как у области изменений панели «История Git». */
    private void installTextActions()
    {
        Target[] target = new Target[1];
        Action show = new Action("Показать в редакторе")
        {
            @Override
            public void run()
            {
                if (target[0] != null)
                    openTarget(target[0]);
            }
        };
        ActionUtils.UpdateableAction selectAll = ActionUtils.createGlobalAction(ActionFactory.SELECT_ALL,
            () -> viewer.doOperation(ITextOperationTarget.SELECT_ALL),
            () -> viewer.canDoOperation(ITextOperationTarget.SELECT_ALL));
        ActionUtils.UpdateableAction copy = ActionUtils.createGlobalAction(ActionFactory.COPY,
            () -> viewer.doOperation(ITextOperationTarget.COPY),
            () -> viewer.canDoOperation(ITextOperationTarget.COPY));
        // Ctrl+F до клавиши в тексте не доходит — только командой, пока фокус в тексте
        ActionUtils.UpdateableAction find = ActionUtils.createGlobalAction(ActionFactory.FIND,
            () -> GitDiffMethods.openFindReplace(viewer), () -> true);
        ActionUtils.setGlobalActions(viewer.getControl(), copy, selectAll, find);
        viewer.addSelectionChangedListener(event -> copy.update());
        // F3 / Shift+F3 — по строке диалога «Найти/Заменить», как в области отличий «Истории Git»;
        // StyledText обрабатывает клавиши во внутреннем слушателе KeyDown — перехват до него
        viewer.getTextWidget().addVerifyKeyListener(event ->
        {
            if (event.keyCode != SWT.F3 || (event.stateMask & SWT.MOD1) != 0)
                return;
            TextEditorFastSearchHandler.executeFindNextFromBuffer(viewer.getTextWidget(),
                (event.stateMask & SWT.MOD2) == 0);
            event.doit = false;
        });
        MenuManager menu = new MenuManager();
        menu.setRemoveAllWhenShown(true);
        menu.addMenuListener(manager ->
        {
            copy.update();
            selectAll.update();
            show.setEnabled(target[0] != null);
            manager.add(show);
            manager.add(new Separator());
            manager.add(copy);
            manager.add(selectAll);
        });
        StyledText text = viewer.getTextWidget();
        text.setMenu(menu.createContextMenu(text));
        // MenuDetect приходит раньше показа меню; с клавиатуры строку задаёт каретка.
        text.addListener(SWT.MenuDetect, event ->
            target[0] = menuTarget(event.detail == SWT.MENU_KEYBOARD ? null : new Point(event.x, event.y)));
        text.addDisposeListener(event -> menu.dispose());
    }

    private void hookStagingView(IViewPart view)
    {
        TreeViewer focused = null;
        TreeViewer filled = null;
        for (String field : new String[] { "unstagedViewer", "stagedViewer" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            if (!(Global.getField(view, field) instanceof TreeViewer tree) || tree.getControl().isDisposed())
                continue;
            boolean staged = "stagedViewer".equals(field); //$NON-NLS-1$
            if (tree.getControl().isFocusControl())
                focused = tree;
            if (filled == null && !entries(tree.getStructuredSelection()).isEmpty())
                filled = tree;
            if (hooked.containsKey(tree))
                continue;
            Control control = tree.getControl();
            ISelectionChangedListener selection = event -> selected(tree.getStructuredSelection(), staged);
            Listener focus = event -> selected(tree.getStructuredSelection(), staged);
            tree.addPostSelectionChangedListener(selection);
            control.addListener(SWT.FocusIn, focus);
            control.addDisposeListener(event -> hooked.remove(tree));
            hooked.put(tree, () ->
            {
                if (control.isDisposed())
                    return;
                tree.removePostSelectionChangedListener(selection);
                control.removeListener(SWT.FocusIn, focus);
            });
        }
        // Панель открыли при уже выбранном файле: показываем его сразу.
        TreeViewer initial = focused != null ? focused : filled;
        if (current == null && initial != null)
            selected(initial.getStructuredSelection(), initial == Global.getField(view, "stagedViewer")); //$NON-NLS-1$
    }

    private static List<StagingEntry> entries(IStructuredSelection selection)
    {
        List<StagingEntry> result = new ArrayList<>();
        for (Object element : selection.toList())
            if (element instanceof StagingEntry entry)
                result.add(entry);
        return result;
    }

    /** Выделение без файлов (папка, пусто) показанное не меняет. */
    private void selected(IStructuredSelection selection, boolean staged)
    {
        List<StagingEntry> entries = entries(selection);
        if (entries.isEmpty())
            return;
        Repository repository = entries.get(0).getRepository();
        List<String> paths = new ArrayList<>();
        for (StagingEntry entry : entries)
            if (entry.getRepository() == repository)
                paths.add(entry.getPath());
        Request request = new Request(repository, paths, staged);
        if (request.equals(current))
            return;
        schedule(request, false);
    }

    /** @param refresh то же самое показано заново: положение прокрутки сохраняется */
    private void schedule(Request request, boolean refresh)
    {
        int stamp;
        synchronized (this)
        {
            current = request;
            stamp = ++generation;
            if (job != null)
                job.cancel();
            job = new Job("Изменения файла Git")
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    IDocument document;
                    try { document = format(request, monitor); }
                    catch (Throwable error)
                    {
                        Global.logError(TAG, "format failed paths=" + request.paths(), error);
                        document = new Document("Не удалось получить изменения: " + error);
                    }
                    if (monitor.isCanceled())
                        return Status.CANCEL_STATUS;
                    IDocument result = document;
                    Display.getDefault().asyncExec(() -> show(request, result, stamp, refresh));
                    return Status.OK_STATUS;
                }
            };
            job.setSystem(true);
            job.schedule();
        }
    }

    private static IDocument format(Request request, IProgressMonitor monitor) throws Exception
    {
        Repository repository = request.repository();
        IPreferenceStore store = org.eclipse.egit.ui.Activator.getDefault().getPreferenceStore();
        FileDocument document = new FileDocument(repository, request.staged());
        try (DiffRegionFormatter formatter = new DiffRegionFormatter(document, document.getLength(),
            store.getInt(PREF_MAX_DIFF_LINES)))
        {
            formatter.setRepository(repository);
            formatter.setQuotePaths(false);
            if (store.getBoolean(PREF_IGNORE_WHITESPACE))
                formatter.setDiffComparator(RawTextComparator.WS_IGNORE_ALL);
            formatter.setPathFilter(PathFilterGroup.createFromStrings(request.paths()));
            AbstractTreeIterator before = request.staged() ? headTree(repository)
                : new DirCacheIterator(repository.readDirCache());
            AbstractTreeIterator after = request.staged() ? new DirCacheIterator(repository.readDirCache())
                : new FileTreeIterator(repository);
            for (DiffEntry entry : formatter.scan(before, after))
            {
                if (monitor.isCanceled())
                    break;
                formatter.flush();
                document.startFile(entry.getChangeType() == DiffEntry.ChangeType.DELETE ? null : entry.getNewPath());
                formatter.format(entry);
            }
            formatter.flush();
            document.connect(formatter);
        }
        return document;
    }

    private static AbstractTreeIterator headTree(Repository repository) throws Exception
    {
        ObjectId tree = repository.resolve("HEAD^{tree}"); //$NON-NLS-1$
        if (tree == null)
            return new EmptyTreeIterator();
        CanonicalTreeParser parser = new CanonicalTreeParser();
        try (ObjectReader reader = repository.newObjectReader())
        {
            parser.reset(reader, tree);
        }
        return parser;
    }

    private void show(Request request, IDocument document, int stamp, boolean refresh)
    {
        if (stamp != generation || viewer == null || viewer.getControl() == null || viewer.getControl().isDisposed())
            return;
        int top = refresh ? viewer.getTopIndex() : 0;
        viewer.setDocument(document);
        if (top > 0)
            viewer.setTopIndex(Math.min(top, Math.max(0, document.getNumberOfLines() - 1)));
        List<String> paths = request.paths();
        setContentDescription((request.staged() ? "Индексированные изменения: " : "Неиндексированные изменения: ")
            + (paths.size() == 1 ? paths.get(0) : "файлов — " + paths.size()));
    }

    @Override
    public void setFocus()
    {
        if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed())
            viewer.getControl().setFocus();
    }

    @Override
    public void dispose()
    {
        IndexDiffCache.INSTANCE.removeIndexDiffChangedListener(indexListener);
        getSite().getPage().removePartListener(partListener);
        for (Runnable unhook : new ArrayList<>(hooked.values()))
            unhook.run();
        hooked.clear();
        synchronized (this)
        {
            generation++;
            if (job != null)
                job.cancel();
        }
        super.dispose();
    }
}
