package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.egit.ui.internal.UIText;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.resource.JFaceColors;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextInputListener;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.texteditor.FindReplaceAction;

/**
 * Методы модулей ({@code .bsl}, {@code .os}) в тексте изменений Git (unified diff) — общее для
 * области отличий панели «История Git» и панели «Изменения файла Git», по образцу обработки
 * «История Git» ИР:
 * <ul>
 *   <li>после заголовка каждого блока «@@ … @@» дорисовывается имя метода, в котором лежит
 *       изменение. Текст документа не меняется — иначе разошлись бы области раскраски и переходы
 *       штатного {@code DiffDocument};</li>
 *   <li>справа от текста — узкий список методов с изменениями по алфавиту; выбор строки
 *       прокручивает текст к первому изменению метода;</li>
 *   <li>строка, которой EGit заканчивает обрезанный текст, — ссылка на полное сравнение файла.</li>
 * </ul>
 * Метод ищется в новой версии файла по строке первого изменения блока: у самого заголовка стоит
 * строка контекста, она может относиться к предыдущему методу.
 */
final class GitDiffMethods
{
    private static final String SETTINGS_SECTION = "GitDiffMethods"; //$NON-NLS-1$
    private static final String KEY_SHOW_LIST = "showList"; //$NON-NLS-1$
    private static final String LIST_TOGGLE_KEY = "tormozit.gitDiffMethodsListToggle"; //$NON-NLS-1$
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)"); //$NON-NLS-1$
    private static final String OUTSIDE = "<вне методов>";
    private static final String TRUNCATED = "<текст обрезан>";
    private static final int DEFAULT_WIDTH_PERMILLE = 180;

    /**
     * Текст изменений одного файла в документе.
     *
     * @param path    путь файла в репозитории
     * @param newText новая версия файла целиком; вызывается в фоне, {@code null} — версии нет
     */
    record Section(int offset, int length, String path, Supplier<String> newText)
    {
    }

    /** Куда встаёт список: разделитель и его часть, справа от которой список. */
    record Host(SashForm sash, Control leftNeighbour)
    {
    }

    /** Изменённая строка («+» или «-»): её строка в документе и место в новой версии файла. */
    private record Change(int documentLine, int moduleLine)
    {
    }

    /** Блок изменений: строка заголовка и все изменённые строки по порядку. */
    private record Hunk(int headerLine, List<Change> changes, Section section, boolean severalFiles)
    {
    }

    /** @param order 0 — метод, 1 — «вне методов», 2 — обрезка текста; внутри порядка — по алфавиту */
    private record Entry(String label, int order, int documentLine)
    {
    }

    private static Pattern truncationLine;

    /**
     * Строка, которой штатный {@code DiffRegionFormatter} заканчивает текст изменений, обрезанный
     * по настройке EGit «Максимум строк». В «Истории Git» весь текст показать нельзя: область
     * отличий — один элемент на всю высоту текста и на длинном тексте упирается в предел высоты.
     */
    static boolean isTruncationLine(String line)
    {
        Pattern truncated = truncationLine;
        if (truncated == null)
        {
            // «[...] diff truncated after {0} lines.» — текст зависит от языка EGit
            String[] parts = UIText.DiffStyleRangeFormatter_diffTruncated.split("\\{0\\}", -1); //$NON-NLS-1$
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < parts.length; i++)
                regex.append(i > 0 ? "[\\d\\s.,]+" : "").append(Pattern.quote(parts[i].trim())); //$NON-NLS-1$ //$NON-NLS-2$
            truncationLine = truncated = Pattern.compile(regex.toString());
        }
        return !line.isEmpty() && truncated.matcher(line.trim()).matches();
    }

    /** Строка обрезки текста — она последняя в документе; {@code -1}, если текст не обрезан. */
    private static int truncationLine(IDocument document)
    {
        try
        {
            int last = document.getNumberOfLines() - 1;
            for (int line = last; line >= Math.max(0, last - 3); line--)
            {
                if (isTruncationLine(lineText(document, line)))
                    return line;
            }
        }
        catch (BadLocationException e)
        {
            // документ сменился во время обхода
        }
        return -1;
    }

    /** Штатный диалог «Найти/Заменить» по тексту изменений — как Ctrl+F в редакторе. */
    static void openFindReplace(SourceViewer viewer)
    {
        ResourceBundle bundle = ResourceBundle.getBundle("org.eclipse.ui.texteditor.EditorMessages"); //$NON-NLS-1$
        new FindReplaceAction(bundle, "FindReplace_", viewer.getTextWidget().getShell(), //$NON-NLS-1$
            viewer.getFindReplaceTarget()).run();
    }

    private final SourceViewer viewer;
    private final StyledText text;
    private final Function<IDocument, List<Section>> sections;
    private final Supplier<Host> host;
    private final String settingsKey;
    private final IntConsumer openFullCompare;
    private Table list;
    private List<Entry> entries = List.of();
    private IDocument namesDocument;
    private Map<Integer, String> namesByLine = Map.of();
    private Job job;
    private boolean applyingWidth;
    private boolean handCursor;
    private TableColumn listColumn;

    private GitDiffMethods(SourceViewer viewer, Function<IDocument, List<Section>> sections,
        Supplier<Host> host, String settingsKey, IntConsumer openFullCompare)
    {
        this.viewer = viewer;
        this.text = viewer.getTextWidget();
        this.sections = sections;
        this.host = host;
        this.settingsKey = settingsKey;
        this.openFullCompare = openFullCompare;
    }

    /**
     * @param sections        тексты файлов в документе изменений (вызывается в UI-потоке)
     * @param host            место списка; спрашивается при первом показе, {@code null} — списка нет
     * @param settingsKey     под каким именем помнить ширину списка
     * @param openFullCompare открыть полное сравнение файла, на котором текст обрезан; получает
     *                        смещение строки обрезки в документе
     */
    static GitDiffMethods install(SourceViewer viewer, Function<IDocument, List<Section>> sections,
        Supplier<Host> host, String settingsKey, IntConsumer openFullCompare)
    {
        GitDiffMethods methods = new GitDiffMethods(viewer, sections, host, settingsKey, openFullCompare);
        // обычный слушатель Paint выполняется после собственной отрисовки StyledText
        methods.text.addListener(SWT.Paint, methods::paint);
        methods.installTruncationLink();
        methods.installListToggle();
        viewer.addTextInputListener(new ITextInputListener()
        {
            @Override
            public void inputDocumentAboutToBeChanged(IDocument oldInput, IDocument newInput)
            {
            }

            @Override
            public void inputDocumentChanged(IDocument oldInput, IDocument newInput)
            {
                methods.update(newInput);
            }
        });
        methods.update(viewer.getDocument());
        return methods;
    }

    /** Документ сменился: методы блоков ищутся в фоне (читаются версии файлов). */
    private void update(IDocument document)
    {
        if (job != null)
            job.cancel();
        namesDocument = null;
        namesByLine = Map.of();
        List<Hunk> hunks = document != null ? collectHunks(document) : List.of();
        if (hunks.isEmpty())
        {
            showEntries(List.of());
            return;
        }
        Display display = text.getDisplay();
        int truncatedLine = truncationLine(document);
        job = new Job("Методы в изменениях Git")
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                Map<Integer, String> names = new HashMap<>();
                Map<Section, String[]> modules = new HashMap<>();
                Map<String, Entry> found = new HashMap<>();
                for (Hunk hunk : hunks)
                {
                    if (monitor.isCanceled())
                        return Status.CANCEL_STATUS;
                    String[] methods = modules.computeIfAbsent(hunk.section(), GitDiffMethods::methodsByLine);
                    // имя модуля дописывается, только когда показаны изменения нескольких модулей сразу
                    String suffix = hunk.severalFiles() ? " - " + moduleLabel(hunk.section().path()) : ""; //$NON-NLS-1$ //$NON-NLS-2$
                    boolean first = true;
                    // блок может задевать несколько методов: в список идёт метод каждой изменённой строки
                    for (Change change : hunk.changes())
                    {
                        String name = methodAt(methods, change.moduleLine());
                        // в заголовке блока — метод первого изменения
                        if (first && name != null)
                            names.put(Integer.valueOf(hunk.headerLine()), name);
                        first = false;
                        String label = (name != null ? name : OUTSIDE) + suffix;
                        // строки идут по порядку документа — остаётся первое изменение метода
                        found.putIfAbsent(label, new Entry(label, name == null ? 1 : 0, change.documentLine()));
                    }
                }
                List<Entry> sorted = new ArrayList<>(found.values());
                // изменения остальных методов в текст не попали — об этом последняя строка списка
                if (truncatedLine >= 0)
                    sorted.add(new Entry(TRUNCATED, 2, truncatedLine));
                sorted.sort((a, b) -> a.order() != b.order() ? Integer.compare(a.order(), b.order())
                    : a.label().compareToIgnoreCase(b.label()));
                if (!display.isDisposed())
                    display.asyncExec(() ->
                    {
                        if (text.isDisposed() || viewer.getDocument() != document)
                            return;
                        namesDocument = document;
                        namesByLine = names;
                        text.redraw();
                        showEntries(sorted);
                    });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    private List<Hunk> collectHunks(IDocument document)
    {
        List<Hunk> hunks = new ArrayList<>();
        List<Section> modules = new ArrayList<>();
        for (Section section : sections.apply(document))
        {
            String path = section.path() == null ? "" : section.path().toLowerCase(Locale.ROOT); //$NON-NLS-1$
            if (section.length() > 0 && (path.endsWith(".bsl") || path.endsWith(".os"))) //$NON-NLS-1$ //$NON-NLS-2$
                modules.add(section);
        }
        try
        {
            for (Section section : modules)
            {
                int first = document.getLineOfOffset(section.offset());
                int last = document.getLineOfOffset(
                    Math.min(section.offset() + section.length(), document.getLength()) - 1);
                for (int line = first; line <= last; line++)
                {
                    Matcher header = HUNK.matcher(lineText(document, line));
                    if (!header.find())
                        continue;
                    int moduleLine = Integer.parseInt(header.group(1));
                    List<Change> changes = new ArrayList<>();
                    int body = line + 1;
                    for (; body <= last; body++)
                    {
                        String content = lineText(document, body);
                        if (content.startsWith("@@")) //$NON-NLS-1$
                            break;
                        if (content.startsWith("\\")) //$NON-NLS-1$
                            continue; // «\ No newline at end of file»
                        boolean removed = content.startsWith("-"); //$NON-NLS-1$
                        if (removed || content.startsWith("+")) //$NON-NLS-1$
                            changes.add(new Change(body, moduleLine));
                        // удалённой строки в новой версии нет: её место — следующая за ней строка
                        if (!removed)
                            moduleLine++;
                    }
                    if (!changes.isEmpty())
                        hunks.add(new Hunk(line, changes, section, modules.size() > 1));
                    line = body - 1;
                }
            }
        }
        catch (BadLocationException | NumberFormatException e)
        {
            // документ сменился во время обхода
        }
        return hunks;
    }

    private static String lineText(IDocument document, int line) throws BadLocationException
    {
        IRegion region = document.getLineInformation(line);
        return document.get(region.getOffset(), region.getLength());
    }

    /**
     * Имя метода для каждой строки новой версии файла ({@code null} — строка вне методов). Строки
     * объявления и завершения метода относятся к нему.
     */
    private static String[] methodsByLine(Section section)
    {
        String module = null;
        try
        {
            module = section.newText().get();
        }
        catch (RuntimeException e)
        {
            Global.logError("GitDiffMethods", "module text failed path=" + section.path(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (module == null)
            return new String[0];
        String[] lines = module.split("\n", -1); //$NON-NLS-1$
        String[] methods = new String[lines.length];
        String current = null;
        for (int i = 0; i < lines.length; i++)
        {
            Matcher start = GetRef.METHOD_START.matcher(lines[i]);
            if (start.find())
                current = start.group(1);
            methods[i] = current;
            if (current != null && GetRef.METHOD_END.matcher(lines[i]).find())
                current = null;
        }
        return methods;
    }

    /** Имя метода строки {@code line} (с единицы); {@code null} — вне методов или за концом файла. */
    private static String methodAt(String[] methods, int line)
    {
        return line >= 1 && line <= methods.length ? methods[line - 1] : null;
    }

    /** Чем различать одноимённые методы разных модулей: полное имя объекта, иначе путь файла. */
    private static String moduleLabel(String path)
    {
        String fullName = GetRef.resolveFullNameOrNull(path);
        return fullName != null && !fullName.isBlank() ? fullName : path;
    }

    private void paint(Event event)
    {
        if (namesByLine.isEmpty() || namesDocument != viewer.getDocument())
            return;
        int lineCount = text.getLineCount();
        int first = text.getLineIndex(event.y);
        int last = text.getLineIndex(event.y + event.height);
        for (Map.Entry<Integer, String> name : namesByLine.entrySet())
        {
            int line = viewer.modelLine2WidgetLine(name.getKey().intValue());
            if (line < first || line > last || line >= lineCount)
                continue;
            Point end = text.getLocationAtOffset(text.getOffsetAtLine(line) + text.getLine(line).length());
            event.gc.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.TEXT_FONT));
            event.gc.setForeground(text.getForeground());
            event.gc.drawText("  " + name.getValue(), end.x, end.y, true); //$NON-NLS-1$
        }
    }

    // ---- строка обрезки текста — ссылка на полное сравнение ----

    private void installTruncationLink()
    {
        viewer.addTextPresentationListener(presentation ->
        {
            IDocument document = viewer.getDocument();
            IRegion extent = presentation.getExtent();
            int line = document != null && extent != null ? truncationLine(document) : -1;
            if (line < 0)
                return;
            try
            {
                IRegion region = document.getLineInformation(line);
                if (region.getLength() == 0 || region.getOffset() < extent.getOffset()
                    || region.getOffset() + region.getLength() > extent.getOffset() + extent.getLength())
                    return;
                StyleRange link = new StyleRange(region.getOffset(), region.getLength(),
                    JFaceColors.getHyperlinkText(text.getDisplay()), null);
                link.underline = true;
                presentation.mergeStyleRange(link);
            }
            catch (BadLocationException e)
            {
                // документ сменился во время раскраски
            }
        });
        text.addListener(SWT.MouseMove, event ->
        {
            boolean onLink = truncationOffsetAt(event.x, event.y) >= 0;
            // курсор возвращаем, только если меняли сами: у штатных гиперссылок EGit он свой
            if (onLink != handCursor)
            {
                handCursor = onLink;
                text.setCursor(onLink ? text.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
            }
        });
        text.addListener(SWT.MouseUp, event ->
        {
            // щелчок, а не конец выделения мышью
            int offset = event.button == 1 && text.getSelectionCount() == 0
                ? truncationOffsetAt(event.x, event.y) : -1;
            if (offset >= 0)
                openFullCompare.accept(offset);
        });
    }

    /** Смещение строки обрезки в документе, если точка виджета лежит на её тексте; иначе {@code -1}. */
    private int truncationOffsetAt(int x, int y)
    {
        IDocument document = viewer.getDocument();
        int line = document != null ? truncationLine(document) : -1;
        int widgetLine = line >= 0 ? viewer.modelLine2WidgetLine(line) : -1;
        if (widgetLine < 0 || widgetLine >= text.getLineCount())
            return -1;
        int top = text.getLinePixel(widgetLine);
        int end = text.getOffsetAtLine(widgetLine) + text.getLine(widgetLine).length();
        if (y < top || y >= top + text.getLineHeight() || x > text.getLocationAtOffset(end).x)
            return -1;
        try
        {
            return document.getLineOffset(line);
        }
        catch (BadLocationException e)
        {
            return -1;
        }
    }

    // ---- список методов ----

    /** Переключатель «Показывать список методов»: общий для обеих панелей, по умолчанию включён. */
    private static boolean isListEnabled()
    {
        return !"false".equals(settings().get(KEY_SHOW_LIST)); //$NON-NLS-1$
    }

    /**
     * Пункт-переключатель в контекстном меню текста. Меню у текста может появиться позже установки
     * и пересобирается перед каждым показом, поэтому пункт добавляется при показе, а прежний
     * убирается.
     */
    private void installListToggle()
    {
        text.addListener(SWT.MenuDetect, event ->
        {
            Menu menu = text.getMenu();
            if (menu == null || menu.isDisposed() || menu.getData(LIST_TOGGLE_KEY) != null)
                return;
            menu.setData(LIST_TOGGLE_KEY, Boolean.TRUE);
            menu.addMenuListener(new MenuAdapter()
            {
                @Override
                public void menuShown(MenuEvent shown)
                {
                    for (MenuItem item : menu.getItems())
                    {
                        if (item.getData(LIST_TOGGLE_KEY) != null)
                            item.dispose();
                    }
                    new MenuItem(menu, SWT.SEPARATOR).setData(LIST_TOGGLE_KEY, Boolean.TRUE);
                    MenuItem toggle = new MenuItem(menu, SWT.CHECK);
                    toggle.setData(LIST_TOGGLE_KEY, Boolean.TRUE);
                    toggle.setText("Показывать список методов");
                    toggle.setSelection(isListEnabled());
                    ComfortSubmenuHelper.setMenuItemTooltip(toggle,
                        "Показывать справа от текста изменений модуля список методов с изменениями");
                    toggle.addListener(SWT.Selection, selected ->
                    {
                        settings().put(KEY_SHOW_LIST, String.valueOf(toggle.getSelection()));
                        showEntries(entries);
                    });
                }
            });
        });
    }

    private void showEntries(List<Entry> computed)
    {
        entries = computed;
        List<Entry> shown = isListEnabled() ? computed : List.of();
        if (shown.isEmpty())
        {
            if (list != null && !list.isDisposed() && list.getVisible())
            {
                list.setVisible(false);
                list.getParent().layout();
            }
            return;
        }
        if (list == null || list.isDisposed())
        {
            Host place = host.get();
            if (place == null || place.sash() == null || place.sash().isDisposed())
                return;
            createList(place);
        }
        list.setRedraw(false);
        list.removeAll();
        for (Entry entry : shown)
            new TableItem(list, SWT.NONE).setText(entry.label());
        list.setRedraw(true);
        if (!list.getVisible())
        {
            list.setVisible(true);
            applyWidth();
        }
        list.getParent().layout();
        fitListColumn();
    }

    private void createList(Host place)
    {
        // таблица без колонок и заголовка, а не List: у неё штатная подсветка строки под указателем
        list = new Table(place.sash(), SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        if (place.leftNeighbour() != null && !place.leftNeighbour().isDisposed())
            list.moveBelow(place.leftNeighbour());
        list.setVisible(false);
        // Одна колонка во всю ширину без заголовка: не поместившийся текст обрезается в ячейке, и
        // Windows сама показывает его подсказкой — отдельно для каждой строки, как в остальных таблицах.
        listColumn = new TableColumn(list, SWT.LEFT);
        String listTip = TooltipText.wrap(list,
            "Методы с изменениями. Выбор строки прокручивает текст к первому изменению метода"
                + Global.pluginSignForTooltip());
        list.setToolTipText(listTip);
        // Над строкой своя подсказка снимается: непустая глушит системную подсказку обрезанной ячейки
        // (лог: текст строки ставился верно, но подсказка таблицы целиком показывается один раз за
        // вход указателя и на соседней строке уже не появляется).
        list.addListener(SWT.MouseMove, event ->
        {
            String tip = list.getItem(new Point(event.x, event.y)) != null ? null : listTip;
            if (!java.util.Objects.equals(tip, list.getToolTipText()))
                list.setToolTipText(tip);
        });
        list.addListener(SWT.Resize, event -> fitListColumn());
        CopyCommandSupport.wireCopyOverride(list);
        list.addListener(SWT.Selection, event ->
        {
            // строки списка идут в порядке entries: список виден, только когда показаны все
            int index = list.getSelectionIndex();
            if (index >= 0 && index < entries.size())
                reveal(entries.get(index).documentLine());
        });
        list.addListener(SWT.Resize, event -> rememberWidth());
    }

    /** Колонка списка — во всю видимую ширину: с появлением вертикальной полосы ширина меняется. */
    private void fitListColumn()
    {
        if (list == null || list.isDisposed() || listColumn == null || listColumn.isDisposed())
            return;
        int width = list.getClientArea().width;
        if (width > 0 && listColumn.getWidth() != width)
            listColumn.setWidth(width);
    }

    /** Показать строку документа у верхнего края текста и поставить на неё каретку. */
    private void reveal(int documentLine)
    {
        IDocument document = viewer.getDocument();
        if (document == null || text.isDisposed())
            return;
        try
        {
            int offset = document.getLineOffset(documentLine);
            viewer.setSelectedRange(offset, 0);
            int widgetOffset = viewer.modelOffset2WidgetOffset(offset);
            if (widgetOffset < 0)
                return;
            int margin = 2 * text.getLineHeight();
            ScrolledComposite outer = outerScroll();
            if (outer != null && outer.getContent() != null)
            {
                // «История Git»: текст растянут на всю высоту, прокручивает его внешняя область
                Point inContent = text.getDisplay().map(text, outer.getContent(),
                    0, text.getLocationAtOffset(widgetOffset).y);
                outer.setOrigin(outer.getOrigin().x, Math.max(0, inContent.y - margin));
            }
            else
                text.setTopIndex(Math.max(0, text.getLineAtOffset(widgetOffset) - 2));
        }
        catch (BadLocationException e)
        {
            // документ сменился после заполнения списка
        }
    }

    private ScrolledComposite outerScroll()
    {
        for (Control control = text.getParent(); control != null; control = control.getParent())
        {
            if (control instanceof ScrolledComposite scrolled)
                return scrolled;
            if (control instanceof SashForm)
                return null;
        }
        return null;
    }

    private static IDialogSettings settings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        return section != null ? section : top.addNewSection(SETTINGS_SECTION);
    }

    /** Части разделителя без его полос — в том же порядке, что веса {@link SashForm#getWeights()}. */
    private static List<Control> parts(SashForm sash)
    {
        List<Control> parts = new ArrayList<>();
        for (Control child : sash.getChildren())
        {
            if (!(child instanceof Sash))
                parts.add(child);
        }
        return parts;
    }

    private void applyWidth()
    {
        if (!(list.getParent() instanceof SashForm sash))
            return;
        int permille = DEFAULT_WIDTH_PERMILLE;
        try
        {
            permille = settings().getInt(settingsKey);
        }
        catch (NumberFormatException e)
        {
            // ширина ещё не запоминалась
        }
        permille = Math.max(50, Math.min(600, permille));
        int index = parts(sash).indexOf(list);
        int[] weights = sash.getWeights();
        if (index < 0 || weights.length != parts(sash).size())
            return;
        long others = 0;
        for (int i = 0; i < weights.length; i++)
        {
            if (i != index)
                others += weights[i];
        }
        if (others <= 0)
            return;
        weights[index] = (int) Math.max(1, others * permille / (1000 - permille));
        applyingWidth = true;
        try
        {
            sash.setWeights(weights);
        }
        finally
        {
            applyingWidth = false;
        }
    }

    private void rememberWidth()
    {
        if (applyingWidth || list.isDisposed() || !list.getVisible())
            return;
        int total = list.getParent().getClientArea().width;
        if (total > 0 && list.getSize().x > 0)
            settings().put(settingsKey, list.getSize().x * 1000 / total);
    }
}
