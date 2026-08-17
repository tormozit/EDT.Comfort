package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.Document;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ST;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Фиксация в «Последних местах» ({@link RecentPlaces}) места из окна сравнения
 * с кнопкой «Показать в модуле» ({@link ShowInModuleHandler}): окно длительно
 * активно — фиксируем не просто модуль, а текущий метод по позиции каретки
 * (аналог {@link RecentPlacesTracker} для обычных редакторов).
 *
 * <p>Отслеживание запускается в момент добавления кнопки «Показать в модуле»
 * в тулбар окна ({@link CompareEditorCurrentLinesHook}, {@link TextMergeEditorHook},
 * {@link PasteWithCompareActions}) и живёт, пока жив шелл окна. Правило то же,
 * что у {@link RecentPlacesTracker}: окно держит фокус {@link #DWELL_MS} мс —
 * место фиксируется; движение каретки перезапускает отсчёт, чтобы зафиксировать
 * именно текущий метод, а не метод на момент открытия.
 *
 * <p>Ключ и ссылка — как у {@code RecentPlacesTracker.recordBslPlace}: каретка
 * внутри метода → {@code "МодульПолный: ИмяМетода"} + расширенная ссылка с номером
 * строки; вне метода — объект-владелец модуля без суффикса типа. Строка и сторона —
 * те же, куда ведёт кнопка «Показать в модуле» этого окна, поэтому переход из
 * «Последних мест» попадает ровно туда же.
 */
public final class CompareRecentPlacesTracker
{
    /** Экземпляр трекера на шелле (один на окно, список поставщиков мест внутри). */
    private static final String TRACKER_KEY = "tormozit.compareRecentPlacesTracker"; //$NON-NLS-1$

    /** Метка на StyledText: каретка этого виджета уже перезапускает отсчёт. */
    private static final String CARET_WIRED_KEY = "tormozit.compareRecentPlacesCaretWired"; //$NON-NLS-1$

    /** Как {@code RecentPlacesTracker.DWELL_MS}. */
    private static final int DWELL_MS = 3000;

    private final Shell shell;
    private final Display display;

    /**
     * Поставщики мест; свежие первыми — при переприсоединении после смены варианта
     * сравнения старые поставщики ссылаются на уничтоженные виджеты и отсеиваются
     * проверками в самих поставщиках.
     */
    private final List<Supplier<Place>> suppliers = new ArrayList<>();

    private Runnable pending;

    private CompareRecentPlacesTracker(Shell shell)
    {
        this.shell = shell;
        this.display = shell.getDisplay();
    }

    /**
     * Начинает/продолжает отслеживание окна. Повторные вызовы для одного шелла
     * (переприсоединение после смены варианта сравнения, несколько редакторов
     * сравнения в одном окне) добавляют поставщика; срабатывает первый валидный.
     */
    public static void track(Shell shell, Supplier<Place> placeSupplier)
    {
        if (shell == null || shell.isDisposed() || placeSupplier == null)
            return;
        CompareRecentPlacesTracker tracker = forShell(shell);
        tracker.suppliers.add(0, placeSupplier);
        tracker.wireCaretHosts(resolveQuietly(placeSupplier));
        tracker.arm();
    }

    private static Place resolveQuietly(Supplier<Place> placeSupplier)
    {
        try
        {
            return placeSupplier.get();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static CompareRecentPlacesTracker forShell(Shell shell)
    {
        if (shell.getData(TRACKER_KEY) instanceof CompareRecentPlacesTracker existing)
            return existing;
        CompareRecentPlacesTracker tracker = new CompareRecentPlacesTracker(shell);
        shell.setData(TRACKER_KEY, tracker);
        shell.addListener(SWT.Activate, e -> tracker.arm());
        shell.addListener(SWT.Deactivate, e -> tracker.cancel());
        shell.addDisposeListener(e -> tracker.cancel());
        return tracker;
    }

    // =========================================================================
    // Отсчёт фиксации
    // =========================================================================

    private void arm()
    {
        if (shell.isDisposed())
            return;
        cancel();
        pending = this::fire;
        display.timerExec(DWELL_MS, pending);
    }

    private void cancel()
    {
        if (pending != null)
        {
            display.timerExec(-1, pending);
            pending = null;
        }
    }

    private void fire()
    {
        pending = null;
        if (shell.isDisposed() || display.isDisposed())
            return;
        // Не активное окно (модальный диалог поверх, переключились в другое приложение) — не фиксируем
        if (display.getActiveShell() != shell)
            return;
        for (Supplier<Place> supplier : suppliers)
        {
            Place place = resolveQuietly(supplier);
            if (place == null)
                continue;
            wireCaretHosts(place);
            record(place);
            return;
        }
    }

    /** Движение каретки в любой панели окна перезапускает отсчёт (фиксация текущего метода). */
    private void wireCaretHosts(Place place)
    {
        if (place == null)
            return;
        for (StyledText host : place.caretHosts)
        {
            if (host == null || host.isDisposed() || Boolean.TRUE.equals(host.getData(CARET_WIRED_KEY)))
                continue;
            host.setData(CARET_WIRED_KEY, Boolean.TRUE);
            host.addListener(ST.CaretMoved, e -> armFrom(host));
        }
    }

    private void armFrom(StyledText host)
    {
        // Каретка чужого (уничтоженного вместе со старым вьюером) виджета не должна перезапускать отсчёт
        if (!host.isDisposed() && host.getShell() == shell)
            arm();
    }

    // =========================================================================
    // Фабрики мест
    // =========================================================================

    /**
     * 2-way окно (Git-сравнение, «Вставить со сравнением»): {@code moduleText} — панель
     * с реальным файлом {@code file}. Метод — по каретке фокусной панели; если фокус в
     * панели без файла ({@code otherText}), её строка мапится в {@code moduleText}
     * (каретка панели-партнёра не двигается и всегда стоит на 0).
     */
    static Place forTwoSides(IFile file, StyledText moduleText, StyledText otherText)
    {
        if (moduleText == null || moduleText.isDisposed())
            return null;
        StyledText focused = otherText != null && !otherText.isDisposed() && otherText.isFocusControl()
            ? otherText
            : moduleText.isFocusControl() ? moduleText : null;
        if (focused == null || focused == moduleText)
            return new Place(file, moduleText, null, moduleText, otherText);
        int moduleLine = CompareLineRangeMatcher.findMatchedLine(focused,
            CompareLineRangeMatcher.lineAtCaret(focused), moduleText);
        String method = moduleLine >= 0
            ? GetRef.findEnclosingMethodName(new Document(moduleText.getText()), moduleLine + 1)
            : null;
        return new Place(file, null, method, moduleText, otherText);
    }

    /**
     * Место по имени метода (секция дерева «Настройка объединения модулей»): имя
     * подтверждается объявлением в самом файле модуля — это даёт точную строку navRef
     * и отсекает не-методы (области и пр.). {@code null} — имя не метод / файл не читается.
     */
    static Place forMethodName(IFile file, String methodName, StyledText... caretHosts)
    {
        if (file == null || !file.exists() || methodName == null || methodName.isBlank())
            return null;
        String content = readModuleContent(file);
        if (content == null)
            return null;
        int line0 = GetRef.findMethodDeclarationLine(new Document(content), methodName);
        if (line0 < 0)
            return null;
        return new Place(file, null, methodName, line0 + 1, caretHosts);
    }

    /** Содержимое BSL-файла из workspace (UTF-8, BOM срезается); {@code null} при ошибке. */
    private static String readModuleContent(IFile file)
    {
        try (java.io.InputStream in = file.getContents())
        {
            byte[] bytes = in.readAllBytes();
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    // =========================================================================
    // Запись места
    // =========================================================================

    private static void record(Place place)
    {
        IFile file = place.file;
        StyledText text = place.text;
        boolean textAlive = text != null && !text.isDisposed();
        if (file == null || !file.exists() || (!textAlive && place.method == null && place.navLine <= 0))
            return;

        GetRef.ModuleRef moduleRef = GetRef.pathToModuleRef(file.getProjectRelativePath().toString());
        if (moduleRef == null)
            return;

        int line1 = 0;
        String method = place.method;
        if (textAlive)
        {
            line1 = CompareLineRangeMatcher.lineAtCaret(text) + 1;
            if (method == null)
                method = GetRef.findEnclosingMethodName(new Document(text.getText()), line1);
        }
        else if (place.navLine > 0)
            line1 = place.navLine;

        String key;
        String navRef;
        String displayName;
        String ownName;
        if (method != null)
        {
            key = moduleRef.modulePath + ": " + method; //$NON-NLS-1$
            navRef = "{" + moduleRef.toRefPrefix() + "(" + (line1 > 0 ? line1 : 1) + ",0:" + method + ",0)}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            displayName = key;
            ownName = method;
        }
        else
        {
            String ownerRef = RecentPlacesKeys.stripModuleTypeSuffix(moduleRef.modulePath);
            key = ownerRef;
            navRef = moduleRef.toRefPrefix();
            displayName = ownerRef;
            ownName = lastSegment(ownerRef);
        }

        String projectName = file.getProject() != null ? file.getProject().getName() : ""; //$NON-NLS-1$
        if (RecentPlaces.getInstance().add(key, navRef, displayName, ownName, projectName))
            Global.log("RecentPlaces add (compare): " + displayName); //$NON-NLS-1$
    }

    private static String lastSegment(String path)
    {
        if (path == null || path.isBlank())
            return path;
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    // =========================================================================

    /**
     * Место для фиксации: {@code file} — реальный файл модуля; {@code text} — сторона
     * сравнения с этим файлом (строка каретки → текущий метод); {@code method} —
     * явное имя метода (секция дерева, маппинг из панели без файла), {@code null} —
     * искать по каретке {@code text}; {@code navLine} — строка 1-based для navRef,
     * когда {@code text} нет; {@code caretHosts} — все панели окна, движение каретки
     * в любой из них перезапускает отсчёт.
     */
    public static final class Place
    {
        final IFile file;
        final StyledText text;
        final String method;
        final int navLine;
        final StyledText[] caretHosts;

        public Place(IFile file, StyledText text, String method, StyledText... caretHosts)
        {
            this(file, text, method, 0, caretHosts);
        }

        Place(IFile file, StyledText text, String method, int navLine, StyledText... caretHosts)
        {
            this.file = file;
            this.text = text;
            this.method = method;
            this.navLine = navLine;
            this.caretHosts = caretHosts;
        }
    }
}
