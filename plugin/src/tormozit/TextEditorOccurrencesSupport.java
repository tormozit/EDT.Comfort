package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.IAnnotationModelListener;
import org.eclipse.jface.text.source.IOverviewRuler;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.xtext.ui.editor.XtextEditor;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Универсальные «маркеры вхождений» для текстовых полей EDT без собственного механизма
 * Xtext ({@code OccurrenceMarker}): XML-редакторы, панели сравнения версий Git, модальный
 * редактор запроса. Как и штатный EDT-механизм «Выделение
 * текущего идентификатора» в стратегии «по совпадению строкового представления», подсвечивает
 * все вхождения слова. Условий включения два:
 * <ul>
 * <li>идентификатор {@link StyledText} <b>выделен целиком</b> (двойной клик, Ctrl+клик) —
 * подсвечиваются вхождения на границах слова;</li>
 * <li><b>выполнен поиск</b> — выделено найденное вхождение (диалог «Найти/Заменить»,
 * F3/Ctrl+F3/Shift+F3): границы слова для включения не требуются, поиск мог найти и часть
 * идентификатора.</li>
 * </ul>
 *
 * <p>Вхождения ищутся с настройками поиска из диалога «Найти» (регистр и т.п.). Одно
 * исключение — «Только слово целиком»: при включении по выделенному слову считается
 * включённым независимо от диалога, при включении поиском берётся из настроек.
 * При обычном движении каретки лишней нагрузки нет. Подсветка применяется только там, где у поля есть
 * {@link ISourceViewer} (редактор, панель сравнения, редактор запроса); произвольные
 * {@link StyledText} без viewer (LWT-поля ввода и т.п.) не затрагиваются.
 *
 * <p>Подсвечивается окно вокруг каретки — не более {@link #MATCHES_AROUND_CARET} вхождений
 * до неё и столько же после: у частых слов подсветка всего документа не нужна и дорога.
 *
 * <p>Отрисовка — две части:
 * <ul>
 * <li>подсветка в тексте — painter поверх {@link StyledText} (полупрозрачная заливка,
 * не трогает {@code StyleRange} редактора и не конфликтует с подсветкой различий);</li>
 * <li>метки на линейке обзора — аннотации {@link #ANNOTATION_TYPE} в annotation model
 * viewer'а (модельные офсеты, переживают правки текста через {@link Position}); тип
 * регистрируется на {@link IOverviewRuler} вручную (штатно типы добавляет только
 * {@code SourceViewerDecorationSupport} из {@code markerAnnotationSpecification}).
 * Где линейки нет — остаётся только подсветка текста.</li>
 * </ul>
 *
 * <p>Единое глобальное состояние всех переключателей — Xtext-преференс BSL
 * {@code ui.editor.markOccurrences} (узел {@code com._1c.g5.v8.dt.bsl.ui} — см.
 * {@link #BSL_QUALIFIER}): им же управляют
 * штатный EDT-механизм BSL и штатная кнопка «Переключить маркеры вхождений» Xtext.
 * Запись идёт через store живого {@link BslXtextEditor}
 * ({@code preferenceStoreAccess.getWritablePreferenceStore()} — ровно тот же store, что у
 * штатной кнопки, приём из {@code TextEditorIdentifierSelectionHook}); без открытого
 * BSL-редактора — scoped store того же узла. Изменения слушаются по узлу.
 * В {@link XtextEditor} (BSL) собственный подсветщик включается только в режиме поиска:
 * по выделенному слову там работает штатный механизм EDT, а вот найденное поиском он
 * подсвечивает только целыми словами.
 */
public final class TextEditorOccurrencesSupport
{
    /**
     * Узел преференсов BSL-редактора EDT. НЕ имя грамматики {@code com._1c.g5.v8.dt.bsl.Bsl}:
     * EDT переопределяет Xtext {@code PreferenceStoreAccess} классом
     * {@code BslUiPreferenceStoreAccess}, чей {@code getQualifier()} = {@code com._1c.g5.v8.dt.bsl.ui}
     * (декомпилировано в .tmp/bundles/bsl-ui-full). Ровно в этот узел пишет штатная кнопка
     * «Переключить маркеры вхождений» через {@code editor.getPreferenceStore()} =
     * {@code FixedScopedPreferenceStore(InstanceScope, qualifier)} — поэтому и читать, и слушать
     * изменения нужно здесь.
     */
    private static final String BSL_QUALIFIER = "com._1c.g5.v8.dt.bsl.ui"; //$NON-NLS-1$
    /** Ключ штатного переключателя Xtext «Переключить маркеры вхождений». */
    private static final String KEY_MARK_OCCURRENCES = "ui.editor.markOccurrences"; //$NON-NLS-1$

    /** Тип аннотаций-вхождений на линейке обзора. */
    private static final String ANNOTATION_TYPE = "tormozit.comfort.occurrence"; //$NON-NLS-1$
    /**
     * Типы аннотаций штатного механизма вхождений Xtext/BSL (декомпиляция
     * {@code BslOccurrenceMarker$InnerMarkOccurrencesJob} — .tmp/bundles/bsl-ui). Пока в
     * BSL-редакторе работает наша подсветка после поиска, они убираются: иначе на общих
     * местах две заливки накладываются друг на друга.
     */
    private static final Set<String> STOCK_OCCURRENCE_TYPES = Set.of(
        "org.eclipse.xtext.ui.editor.defaultOccurrenceAnnotation", //$NON-NLS-1$
        "org.eclipse.xtext.ui.editor.declarationAnnotation"); //$NON-NLS-1$
    private static final String RULER_TYPE_KEY = "tormozit.occurrencesRulerType"; //$NON-NLS-1$

    /** Защита от гигантских текстов. */
    private static final int MAX_TEXT_LENGTH = 2_000_000;
    /**
     * Окно подсветки вокруг каретки: не более стольких вхождений до каретки и стольких же
     * после. Частые слова («Если», «Тогда») больше не отключают подсветку целиком —
     * подсвечивается ближайшая к каретке часть, а не весь документ.
     */
    private static final int MATCHES_AROUND_CARET = 100;

    private static ScopedPreferenceStore scopedStore;
    private static IEclipsePreferences.IPreferenceChangeListener storeListener;
    /** Если store недоступен (запуск без Xtext) — локальное состояние. */
    private static volatile boolean fallbackState = true;
    private static volatile boolean enabled = true;

    private static final List<Consumer<Boolean>> stateListeners = new CopyOnWriteArrayList<>();

    private static StyledText activeWidget;
    private static ISourceViewer activeViewer;
    private static IAnnotationModel activeModel;
    private static Annotation[] activeAnnotations;
    /** Модель, в которой подавлены штатные вхождения Xtext/BSL, и слушатель для этого. */
    private static IAnnotationModel suppressedModel;
    private static IAnnotationModelListener suppressListener;
    /** Наше собственное удаление штатных аннотаций — уведомление о нём игнорируем. */
    private static boolean removingStock;
    private static OccurrencePainter activePainter;
    private static Color rulerColor;

    /**
     * Известные пары «виджет → его {@link ISourceViewer}»: панели сравнения регистрируются
     * явно ({@link #registerViewer(ISourceViewer)} из {@link MergeViewerReflection}), прочие
     * поля попадают сюда при первом успешном разборе. Иначе viewer панели сравнения не
     * находится ни по цепочке родителей (её {@code SourceViewer} — не {@link Control}),
     * ни через активный редактор, и подсветка там не работает. Ключи — слабые: виджет
     * закрытого окна не удерживается.
     */
    private static final Map<StyledText, ISourceViewer> knownViewers =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** Строка последнего поиска «Найти/Заменить» ({@code null} — поиск не задан). */
    private static volatile String searchPattern;
    /**
     * Настройки поиска живого диалога. Пока диалог их не сообщил, берутся сохранённые
     * настройки диалога «Найти/Заменить» ({@link TextEditorFastSearchHandler}).
     */
    private static volatile Boolean searchCaseSensitive;
    private static volatile Boolean searchWholeWord;
    /**
     * Момент последней команды поиска ({@link #markSearchNavigation()}). Окно короткое:
     * выделение команда ставит сразу, а дальше это уже обычное движение каретки.
     */
    private static volatile long searchNavigationAt;
    private static final long SEARCH_NAVIGATION_WINDOW_MS = 1500;

    /** Отсечка повторных asyncExec на каждый {@link SWT#Selection}. */
    private static volatile boolean selectionScheduled;
    private static volatile StyledText selectionPendingWidget;

    private TextEditorOccurrencesSupport()
    {
    }

    /**
     * Регистрирует поле-{@link StyledText} вместе с его {@link ISourceViewer}. Нужна там,
     * где вьюер не найти по цепочке родителей виджета — прежде всего панели сравнения
     * ({@code MergeSourceViewer.getSourceViewer()}); вызывается из
     * {@link MergeViewerReflection#extractSourceViewer(Object, String)}.
     */
    public static void registerViewer(ISourceViewer viewer)
    {
        if (viewer == null)
            return;
        StyledText text = viewer.getTextWidget();
        if (text != null && !text.isDisposed())
            knownViewers.put(text, viewer);
    }

    /** Вьюер панели сравнения, зарегистрированный через {@link #registerViewer}; иначе {@code null}. */
    static ISourceViewer viewerFor(StyledText text)
    {
        if (text == null || text.isDisposed())
            return null;
        return knownViewers.get(text);
    }

    /**
     * Контекст поиска из диалога «Найти/Заменить»
     * ({@link FindReplaceLiveMatchCountHook}): пока выделение совпадает с искомой строкой,
     * включается подсветка — без требования «слово выделено целиком»: поиск может найти и
     * часть идентификатора. Сам поиск вхождений при этом обычный, как и при выделении
     * слова целиком.
     *
     * @param caseSensitive флажок «С учётом регистра»
     * @param wholeWord флажок «Только слово целиком»
     * @param regEx поиск по регулярному выражению — литеральной строки нет, режим поиска
     *            не включается (остаётся обычное «слово выделено целиком»)
     */
    public static void setSearchContext(String pattern, boolean caseSensitive, boolean wholeWord,
        boolean regEx)
    {
        searchPattern = regEx || pattern == null || pattern.isEmpty() ? null : pattern;
        searchCaseSensitive = Boolean.valueOf(caseSensitive);
        searchWholeWord = Boolean.valueOf(wholeWord);
    }

    /**
     * «С учётом регистра»: значение живого диалога, иначе сохранённая настройка диалога
     * «Найти/Заменить». Действует в обоих режимах включения — вхождения ищутся с
     * настройками поиска.
     */
    private static boolean caseSensitiveSetting()
    {
        Boolean live = searchCaseSensitive;
        return live != null ? live.booleanValue() : TextEditorFastSearchHandler.isCaseSensitiveSearch();
    }

    /**
     * «Только слово целиком»: значение живого диалога, иначе сохранённая настройка. Берётся
     * только при включении подсветки поиском; при выделении слова целиком считается
     * включённым независимо от диалога.
     */
    private static boolean wholeWordSetting()
    {
        Boolean live = searchWholeWord;
        return live != null ? live.booleanValue() : TextEditorFastSearchHandler.isWholeWordSearch();
    }

    /**
     * Отметка «только что выполнена команда поиска» (F3 / Ctrl+F3 / Shift+F3, «Найти далее»
     * и т.п.): выделение после неё — найденное вхождение, поэтому подсвечиваем все вхождения
     * выделенной строки, не проверяя границы слова. Диалог при этом мог и не открываться,
     * так что сравнить с искомой строкой ({@link #isSearchSelection}) нечего.
     */
    static void markSearchNavigation()
    {
        searchNavigationAt = System.currentTimeMillis();
    }

    /**
     * Выделение поставлено поиском программно — отмечаем режим поиска и пересчитываем
     * подсветку сами: на программную установку выделения {@link StyledText} события не
     * шлёт, и без явного пересчёта отметка просто истекает (в логе видно, что каждой
     * подсветке после поиска предшествует именно принудительный пересчёт).
     */
    static void refreshAfterSearch(StyledText text)
    {
        markSearchNavigation();
        if (text == null || text.isDisposed())
            return;
        Display display = text.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            if (!text.isDisposed())
                handleSelection(text);
        });
    }

    /** Выделение поставлено командой поиска, и подсветка ещё не пересчитана по ней. */
    private static boolean isSearchNavigation()
    {
        long at = searchNavigationAt;
        return at != 0 && System.currentTimeMillis() - at <= SEARCH_NAVIGATION_WINDOW_MS;
    }

    /** Выделение — это найденное диалогом поиска вхождение искомой строки. */
    private static boolean isSearchSelection(String selected)
    {
        String pattern = searchPattern;
        if (pattern == null || selected == null || selected.isEmpty())
            return false;
        return caseSensitiveSetting() ? pattern.equals(selected) : pattern.equalsIgnoreCase(selected);
    }

    /**
     * Поле поддерживает подсветку вхождений: у него есть {@link ISourceViewer} (редактор,
     * панель сравнения, редактор запроса). Нужна {@link OccurrencesToggleHook}, чтобы не
     * ставить переключатель в окна, где подсвечивать нечего — например в «Выбор объектов»,
     * где единственный {@link StyledText} это поле фильтра.
     */
    static boolean supportsHighlight(StyledText text)
    {
        return text != null && !text.isDisposed() && resolveViewerFor(text) != null;
    }

    /** Подписка на смену единого состояния (переключатели обновляют свой вид). */
    static void addStateListener(Consumer<Boolean> listener)
    {
        stateListeners.add(listener);
    }

    /** Инициализация при старте: читает и слушает единое состояние. */
    static void init()
    {
        if (storeListener != null)
            return;
        try
        {
            ScopedPreferenceStore store = new ScopedPreferenceStore(
                InstanceScope.INSTANCE, BSL_QUALIFIER);
            store.setDefault(KEY_MARK_OCCURRENCES, true);
            scopedStore = store;
            /*
             * Слушаем узел IEclipsePreferences, а не наш экземпляр ScopedPreferenceStore:
             * PropertyChangeEvent доставляется только тому экземпляру, через который писали,
             * а штатная кнопка Xtext пишет через СВОЙ store живого BslXtextEditor. Событие
             * узла приходит при изменении из любого экземпляра того же узла.
             */
            IEclipsePreferences node = InstanceScope.INSTANCE.getNode(BSL_QUALIFIER);
            storeListener = e -> {
                if (KEY_MARK_OCCURRENCES.equals(e.getKey()))
                    applyState(isEnabled());
            };
            node.addPreferenceChangeListener(storeListener);
        }
        catch (RuntimeException ignored)
        {
            scopedStore = null;
        }
        applyState(isEnabled());
    }

    /** Текущее единое состояние (default {@code true}, как у EDT). */
    public static boolean isEnabled()
    {
        if (scopedStore != null)
        {
            try
            {
                return scopedStore.getBoolean(KEY_MARK_OCCURRENCES);
            }
            catch (RuntimeException ignored)
            {
                // ниже — fallback
            }
        }
        return fallbackState;
    }

    /**
     * Переключение из любой точки: пишет через store живого BSL-редактора (гарантированная
     * доставка событию штатной кнопке Xtext и EDT-механизму), иначе — в scoped store того
     * же узла. Слушатели и локальное применение — через событие узла.
     */
    public static void setEnabled(boolean value)
    {
        boolean written = false;
        IPreferenceStore xtextStore = xtextWritableStore();
        if (xtextStore != null)
        {
            try
            {
                xtextStore.setValue(KEY_MARK_OCCURRENCES, value);
                written = true;
            }
            catch (RuntimeException ignored)
            {
                // ниже — scoped store
            }
        }
        if (!written && scopedStore != null)
        {
            try
            {
                scopedStore.setValue(KEY_MARK_OCCURRENCES, value);
                return; // событие узла применит состояние
            }
            catch (RuntimeException ignored)
            {
                // ниже — fallback
            }
        }
        fallbackState = value;
        applyState(value);
    }

    /**
     * Store живого {@link BslXtextEditor}: {@code preferenceStoreAccess.getWritablePreferenceStore()}
     * — тот же store, в который пишет штатная кнопка «Переключить маркеры вхождений».
     */
    private static IPreferenceStore xtextWritableStore()
    {
        if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing())
            return null;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return null;
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return null;
        BslXtextEditor bsl = findBslEditor(page);
        if (bsl == null)
            return null;
        Object access = Global.getField(bsl, "preferenceStoreAccess"); //$NON-NLS-1$
        if (access == null)
            return null;
        Object store = Global.invoke(access, "getWritablePreferenceStore"); //$NON-NLS-1$
        return store instanceof IPreferenceStore preferenceStore ? preferenceStore : null;
    }

    private static BslXtextEditor findBslEditor(IWorkbenchPage page)
    {
        IEditorPart active = page.getActiveEditor();
        if (active instanceof BslXtextEditor bslEditor)
            return bslEditor;
        BslXtextEditor fromActive = GetRef.getActiveBslEditor(active);
        if (fromActive != null)
            return fromActive;
        for (IEditorReference ref : page.getEditorReferences())
        {
            IEditorPart editor = ref.getEditor(false);
            if (editor instanceof BslXtextEditor bslEditor)
                return bslEditor;
            BslXtextEditor found = GetRef.getActiveBslEditor(editor);
            if (found != null)
                return found;
        }
        return null;
    }

    private static void applyState(boolean value)
    {
        enabled = value;
        if (!value)
            deactivate();
        else
            refreshFromFocus();
        for (Consumer<Boolean> listener : stateListeners)
            listener.accept(value);
    }

    /** Coalesce-планирование проверки выделения (на каждый Selection каретки дёшево). */
    static void scheduleSelection(StyledText text)
    {
        if (text == null || text.isDisposed())
            return;
        selectionPendingWidget = text;
        if (selectionScheduled)
            return;
        /*
         * Display берём у самого виджета: Display.getCurrent() возвращает null при вызове
         * не из UI-потока, и прежний ранний return оставлял selectionScheduled = true
         * навсегда — после такого случая подсветка молча переставала работать до перезапуска.
         */
        Display display = text.getDisplay();
        if (display == null || display.isDisposed())
            return;
        selectionScheduled = true;
        display.asyncExec(() -> {
            selectionScheduled = false;
            StyledText pending = selectionPendingWidget;
            if (pending != null && !pending.isDisposed())
                handleSelection(pending);
        });
    }

    /** Проверка «идентификатор выделен целиком» и обновление подсветки. */
    static void handleSelection(StyledText text)
    {
        if (text == null || text.isDisposed())
            return;
        if (!enabled)
        {
            deactivateIfActive(text);
            return;
        }

        Point selection = text.getSelectionRange();
        if (selection.y <= 0)
        {
            deactivateIfActive(text);
            return;
        }

        /*
         * Два условия включения: выполнен поиск (выделено найденное вхождение искомой
         * строки — границы слова не важны) либо идентификатор выделен целиком.
         */
        String selectedText = selectionText(text);
        boolean searchMode = isSearchSelection(selectedText) || isSearchNavigation();
        /*
         * В Xtext-редакторах (BSL) по выделенному слову работает штатный механизм EDT — свой
         * там не включаем, чтобы не дублировать. А вот после поиска штатный подсвечивает
         * только целые слова, поэтому режим поиска работает и в BSL.
         */
        if (!searchMode && isXtextEditorWidget(text))
        {
            deactivateIfActive(text);
            return;
        }
        String word = searchMode ? selectedText : wholeSelectedWord(text, selection);
        if (word == null || word.isEmpty()
            || (!searchMode && !IdentifierSelectionSupport.isIdentifierChar(word.charAt(0))))
        {
            deactivateIfActive(text);
            return;
        }

        /*
         * Якорь — виджетный офсет выделения: то же слово, выделенное в другом месте, даёт
         * другое окно подсветки (см. MATCHES_AROUND_CARET), поэтому сравнения одного слова
         * мало, иначе после перехода к следующему вхождению окно осталось бы прежним.
         */
        int anchor = selection.x;
        if (activePainter != null && activeWidget == text && word.equals(activePainter.word)
            && anchor == activePainter.anchor)
            return;

        /*
         * Вхождения ищем с настройками поиска из диалога «Найти». Исключение — «слово
         * целиком»: при включении по выделенному слову оно считается включённым независимо
         * от диалога, а при включении поиском берётся из настроек.
         */
        WordMatches matches = findMatches(text, word, anchor,
            !searchMode || wholeWordSetting(), caseSensitiveSetting());
        if (matches == null)
        {
            deactivateIfActive(text);
            return;
        }

        deactivate();
        if (matches.widgetRanges.isEmpty())
            return;
        activeWidget = text;
        activeViewer = matches.viewer;
        if (matches.viewer != null)
        {
            attachAnnotations(matches.viewer, matches.modelRanges);
            if (searchMode)
                suppressStockOccurrences(matches.viewer.getAnnotationModel());
        }
        activePainter = OccurrencePainter.install(text, word, anchor, matches.widgetRanges);
        if (searchMode)
            searchNavigationAt = 0; // отметка команды поиска использована
    }

    /** Текст выделения виджета ({@code null} при устаревших офсетах). */
    private static String selectionText(StyledText text)
    {
        try
        {
            return text.getSelectionText();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Слово текущей строки виджета, если выделение совпадает с его границами точно
     * (от края до края); иначе {@code null}. Границы — прямой проход по классу символа
     * ({@link IdentifierSelectionSupport#isIdentifierChar(char)}), не «соседнее слово»
     * как у навигации {@code previousBoundary}/{@code nextBoundary}.
     */
    private static String wholeSelectedWord(StyledText text, Point selection)
    {
        try
        {
            int line = text.getLineAtOffset(selection.x);
            int lineOffset = text.getOffsetAtLine(line);
            String lineText = text.getLine(line);
            if (lineText == null || lineText.isEmpty())
                return null;
            int posInLine = selection.x - lineOffset;
            int selEnd = posInLine + selection.y;
            if (posInLine < 0 || selEnd > lineText.length())
                return null;
            int wordStart = posInLine;
            while (wordStart > 0
                && IdentifierSelectionSupport.isIdentifierChar(lineText.charAt(wordStart - 1)))
                wordStart--;
            int wordEnd = selEnd;
            while (wordEnd < lineText.length()
                && IdentifierSelectionSupport.isIdentifierChar(lineText.charAt(wordEnd)))
                wordEnd++;
            if (wordStart != posInLine || wordEnd != selEnd)
                return null;
            return lineText.substring(wordStart, wordEnd);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** Вхождения слова: модельные офсеты (аннотации) и виджетные (painter). */
    private static final class WordMatches
    {
        final ISourceViewer viewer;
        final List<int[]> modelRanges;
        final List<int[]> widgetRanges;

        WordMatches(ISourceViewer viewer, List<int[]> modelRanges, List<int[]> widgetRanges)
        {
            this.viewer = viewer;
            this.modelRanges = modelRanges;
            this.widgetRanges = widgetRanges;
        }
    }

    /**
     * Все вхождения {@code word} (whole word, точное совпадение). По документу viewer'а
     * (полный текст со свёртками; свёрнутые вхождения исключаются из виджетных диапазонов).
     * Только там, где есть {@link ISourceViewer} (редактор, панель сравнения, редактор
     * запроса) — в произвольных {@link StyledText} без viewer (LWT-поля ввода и т.п.)
     * подсветка вхождений не применяется.
     *
     * <p>Правила поиска вхождений одни и те же в обоих режимах включения (слово целиком /
     * после поиска) — режим влияет только на решение «включать ли подсветку».
     *
     * <p>Подсвечивается не весь документ, а окно вокруг каретки: не более
     * {@link #MATCHES_AROUND_CARET} вхождений до неё и столько же после.
     *
     * @param widgetAnchor виджетный офсет выделения (каретка) — центр окна
     * @param wholeWord только вхождения на границах слова (см.
     *            {@link #findAllWordRanges(String, String, boolean, boolean)})
     * @param caseSensitive с учётом регистра (там же)
     * @return {@code null} — нет viewer/документа либо текст слишком велик.
     */
    private static WordMatches findMatches(StyledText text, String word, int widgetAnchor,
        boolean wholeWord, boolean caseSensitive)
    {
        ISourceViewer viewer = resolveViewerFor(text);
        IDocument document = viewer != null ? viewer.getDocument() : null;
        if (document == null)
            return null;
        String fullText = document.get();
        if (fullText == null || fullText.length() > MAX_TEXT_LENGTH)
            return null;
        List<int[]> modelRanges = limitAroundCaret(
            findAllWordRanges(fullText, word, wholeWord, caseSensitive),
            modelAnchor(viewer, widgetAnchor));
        List<int[]> widgetRanges = new ArrayList<>(modelRanges.size());
        for (int[] range : modelRanges)
        {
            int widgetOffset = range[0];
            if (viewer instanceof ITextViewerExtension5 ext5)
                widgetOffset = ext5.modelOffset2WidgetOffset(range[0]);
            if (widgetOffset >= 0)
                widgetRanges.add(new int[] { widgetOffset, range[1] });
        }
        return new WordMatches(viewer, modelRanges, widgetRanges);
    }

    /**
     * Модельный офсет каретки: {@link StyledText} даёт виджетные координаты, а вхождения
     * ищутся по документу (со свёртками они расходятся — см. правила «Каретка редактора:
     * виджет ≠ модель»).
     */
    private static int modelAnchor(ISourceViewer viewer, int widgetAnchor)
    {
        if (viewer instanceof ITextViewerExtension5 ext5)
        {
            int model = ext5.widgetOffset2ModelOffset(widgetAnchor);
            if (model >= 0)
                return model;
        }
        return widgetAnchor;
    }

    /**
     * Оставляет не более {@link #MATCHES_AROUND_CARET} вхождений до каретки и столько же
     * после неё (диапазоны уже отсортированы по возрастанию офсета).
     */
    private static List<int[]> limitAroundCaret(List<int[]> ranges, int caret)
    {
        if (ranges.size() <= 2 * MATCHES_AROUND_CARET)
            return ranges;
        int first = 0;
        while (first < ranges.size() && ranges.get(first)[0] < caret)
            first++;
        int from = Math.max(0, first - MATCHES_AROUND_CARET);
        int to = Math.min(ranges.size(), first + MATCHES_AROUND_CARET);
        return new ArrayList<>(ranges.subList(from, to));
    }

    /**
     * Viewer поля: сначала известные пары (панели сравнения регистрируются явно, прочие
     * поля кэшируются при первом разборе), затем фокус-цепочка родителей, модальные
     * диалоги (редактор запроса) и, наконец, перебор открытых текстовых редакторов.
     *
     * <p>Перебор редакторов — вместо «только активная часть окна»: {@code getActivePart()}
     * в момент {@code SWT.Selection} может ещё указывать на прежнюю часть (панель,
     * навигатор), и подсветка в обычном текстовом редакторе тогда пропадала через раз.
     */
    private static ISourceViewer resolveViewerFor(StyledText text)
    {
        ISourceViewer known = knownViewers.get(text);
        if (known != null && known.getTextWidget() == text)
            return known;

        ISourceViewer viewer = TextEditor.resolveViewerFromFocus(text);
        if (viewer == null || viewer.getTextWidget() != text)
            viewer = TextEditor.resolveViewerFromDialog(text);
        if (viewer == null || viewer.getTextWidget() != text)
            viewer = resolveViewerFromEditors(text);
        if (viewer != null && viewer.getTextWidget() == text)
        {
            knownViewers.put(text, viewer);
            return viewer;
        }
        return null;
    }

    /** Открытые (созданные) текстовые редакторы всех окон — чей viewer владеет виджетом. */
    private static ISourceViewer resolveViewerFromEditors(StyledText text)
    {
        if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing())
            return null;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorReference ref : page.getEditorReferences())
                {
                    ISourceViewer viewer = viewerOfEditor(ref.getEditor(false), text);
                    if (viewer != null)
                        return viewer;
                }
            }
        }
        return null;
    }

    private static ISourceViewer viewerOfEditor(IWorkbenchPart part, StyledText text)
    {
        // Xtext-редакторы тоже: их поле нужно в режиме поиска (решение «подсвечивать ли»
        // принимает handleSelection, а не разбор вьюера)
        ITextEditor editor = TextEditor.resolveTextEditor(part);
        if (editor == null)
            return null;
        ISourceViewer viewer = TextEditor.getSourceViewer(editor);
        return viewer != null && viewer.getTextWidget() == text ? viewer : null;
    }

    /**
     * Вхождения {@code word} в {@code text}: {@code {offset, length}}.
     *
     * @param wholeWord только вхождения на границах слова. При включении по выделенному
     *            слову — да, как и было. В режиме поиска — нет: поиск находит и часть
     *            идентификатора («ByString» внутри «inputByString»), и по границам слова
     *            не нашлось бы ни одного вхождения, включая само найденное.
     * @param caseSensitive с учётом регистра. При включении по выделенному слову — да, как
     *            и было. В режиме поиска — как в диалоге поиска: иначе при поиске без учёта
     *            регистра найденные вхождения с другим регистром («Server» рядом с
     *            «server») оставались бы неподсвеченными.
     */
    private static List<int[]> findAllWordRanges(String text, String word, boolean wholeWord,
        boolean caseSensitive)
    {
        String haystack = text;
        String needle = word;
        if (!caseSensitive)
        {
            String lowerText = text.toLowerCase(Locale.ROOT);
            String lowerWord = word.toLowerCase(Locale.ROOT);
            // регистронезависимо — только если офсеты не поехали (есть символы, меняющие
            // длину при смене регистра)
            if (lowerText.length() == text.length() && lowerWord.length() == word.length())
            {
                haystack = lowerText;
                needle = lowerWord;
            }
        }
        List<int[]> ranges = new ArrayList<>();
        int index = 0;
        while (index <= haystack.length() - needle.length())
        {
            index = haystack.indexOf(needle, index);
            if (index < 0)
                break;
            if (!wholeWord || isWordBoundary(text, index, index + needle.length()))
                ranges.add(new int[] { index, needle.length() });
            index += needle.length();
        }
        return ranges;
    }

    private static boolean isWordBoundary(String text, int matchStart, int matchEnd)
    {
        if (matchStart > 0 && IdentifierSelectionSupport.isIdentifierChar(text.charAt(matchStart - 1)))
            return false;
        if (matchEnd < text.length() && IdentifierSelectionSupport.isIdentifierChar(text.charAt(matchEnd)))
            return false;
        return true;
    }

    // ========= Аннотации на линейке обзора =========

    /** Кладёт аннотации вхождений (модельные офсеты) и регистрирует тип на линейке. */
    private static void attachAnnotations(ISourceViewer viewer, List<int[]> modelRanges)
    {
        IAnnotationModel model = viewer.getAnnotationModel();
        if (model == null)
            return;
        registerOverviewType(viewer);

        Map<Annotation, Position> toAdd = new LinkedHashMap<>();
        for (int[] range : modelRanges)
            toAdd.put(new Annotation(ANNOTATION_TYPE, false, null),
                new Position(range[0], range[1]));
        replaceAnnotations(model, activeModel == model ? activeAnnotations : null, toAdd);
        activeModel = model;
        activeAnnotations = toAdd.keySet().toArray(new Annotation[0]);
    }

    /**
     * Пока активна наша подсветка после поиска, штатные аннотации вхождений Xtext/BSL
     * убираются из модели: заливки накладывались на совпадающих вхождениях. Штатный маркер
     * считает вхождения фоновым заданием и дописывает их позже нашей установки, поэтому мало
     * снять один раз — слушаем модель и снимаем каждый раз. Ничего восстанавливать не нужно:
     * штатный механизм пересчитает вхождения на следующем изменении выделения.
     *
     * <p>Снятие — синхронно, прямо в уведомлении модели: с отложенным ({@code asyncExec})
     * штатная подсветка успевала отрисоваться, и после каждого перехода было видно мигание.
     * Перерисовка в SWT идёт после обработки события, поэтому убранное в том же цикле на
     * экран не попадает. От повторного входа защищает {@link #removingStock}.
     */
    private static void suppressStockOccurrences(IAnnotationModel model)
    {
        if (model == null || suppressedModel == model)
            return;
        releaseStockSuppression();
        suppressedModel = model;
        suppressListener = TextEditorOccurrencesSupport::removeStockOccurrences;
        model.addAnnotationModelListener(suppressListener);
        removeStockOccurrences(model);
    }

    private static void removeStockOccurrences(IAnnotationModel model)
    {
        if (removingStock || model == null || model != suppressedModel)
            return;
        List<Annotation> stock = new ArrayList<>();
        try
        {
            Iterator<Annotation> iterator = model.getAnnotationIterator();
            while (iterator.hasNext())
            {
                Annotation annotation = iterator.next();
                if (annotation != null && STOCK_OCCURRENCE_TYPES.contains(annotation.getType()))
                    stock.add(annotation);
            }
        }
        catch (RuntimeException e)
        {
            return; // модель перестроилась во время обхода — снимем на следующем уведомлении
        }
        if (stock.isEmpty())
            return;
        removingStock = true;
        try
        {
            replaceAnnotations(model, stock.toArray(new Annotation[0]), Collections.emptyMap());
        }
        finally
        {
            removingStock = false;
        }
    }

    private static void releaseStockSuppression()
    {
        if (suppressedModel != null && suppressListener != null)
            suppressedModel.removeAnnotationModelListener(suppressListener);
        suppressedModel = null;
        suppressListener = null;
    }

    private static void clearAnnotations()
    {
        if (activeModel != null)
            replaceAnnotations(activeModel, activeAnnotations, Collections.emptyMap());
        activeModel = null;
        activeAnnotations = null;
    }

    private static void replaceAnnotations(IAnnotationModel model, Annotation[] old,
        Map<Annotation, Position> add)
    {
        if ((old == null || old.length == 0) && add.isEmpty())
            return;
        if (model instanceof IAnnotationModelExtension extension)
        {
            extension.replaceAnnotations(
                old != null ? old : new Annotation[0], add);
            return;
        }
        if (old != null)
        {
            for (Annotation annotation : old)
                model.removeAnnotation(annotation);
        }
        for (Map.Entry<Annotation, Position> entry : add.entrySet())
            model.addAnnotation(entry.getKey(), entry.getValue());
    }

    /**
     * Регистрирует {@link #ANNOTATION_TYPE} на линейке обзора viewer'а — штатно это делает
     * {@code SourceViewerDecorationSupport} для типов из {@code markerAnnotationSpecification};
     * наш тип добавляем сами (цвет/слой/обновление), один раз на линейку.
     */
    private static void registerOverviewType(ISourceViewer viewer)
    {
        IOverviewRuler ruler = overviewRulerOf(viewer);
        if (ruler == null)
            return;
        Control control = ruler.getControl();
        if (control == null || Boolean.TRUE.equals(control.getData(RULER_TYPE_KEY)))
            return;
        ruler.addAnnotationType(ANNOTATION_TYPE);
        ruler.setAnnotationTypeColor(ANNOTATION_TYPE, rulerColor(control.getDisplay()));
        ruler.setAnnotationTypeLayer(ANNOTATION_TYPE, 3);
        control.setData(RULER_TYPE_KEY, Boolean.TRUE);
        ruler.update();
    }

    private static Color rulerColor(Display display)
    {
        if (rulerColor != null && !rulerColor.isDisposed())
            return rulerColor;
        rulerColor = new Color(display, 216, 136, 0);
        return rulerColor;
    }

    private static IOverviewRuler overviewRulerOf(ISourceViewer viewer)
    {
        Object ruler = Global.invoke(viewer, "getOverviewRuler"); //$NON-NLS-1$
        if (ruler instanceof IOverviewRuler overviewRuler)
            return overviewRuler;
        ruler = Global.getField(viewer, "fOverviewRuler"); //$NON-NLS-1$
        return ruler instanceof IOverviewRuler overviewRuler ? overviewRuler : null;
    }

    /**
     * Виджет принадлежит {@link XtextEditor} — по выделенному слову там работает штатный
     * механизм EDT/Xtext (в режиме поиска это не мешает: штатный ищет целыми словами).
     */
    private static boolean isXtextEditorWidget(StyledText text)
    {
        if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing())
            return false;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return false;
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return false;
        IWorkbenchPart part = page.getActivePart();
        ITextEditor editor = TextEditor.resolveTextEditor(part);
        if (!(editor instanceof XtextEditor xtextEditor))
            return false;
        ISourceViewer viewer = TextEditor.getSourceViewer(xtextEditor);
        return viewer != null && viewer.getTextWidget() == text;
    }

    /** Пересчёт по текущему фокусу (после включения переключателем). */
    static void refreshFromFocus()
    {
        Display display = Display.getCurrent();
        if (display == null)
            return;
        Control focus = display.getFocusControl();
        if (focus instanceof StyledText st && !st.isDisposed())
        {
            handleSelection(st);
            return;
        }
        // Фокус на кнопке переключателя и т.п. — пересчитываем по активному редактору
        refreshFromActiveEditor();
    }

    /**
     * Пересчёт по активному редактору окна: активация редактора не меняет выделение
     * его {@code StyledText} — события {@code Selection}/{@code FocusIn} не гарантированы,
     * поэтому при возврате в редактор с уже выделенным словом подсветку восстанавливаем
     * сами ({@code OccurrencesToggleHook.partActivated}).
     */
    static void refreshFromActiveEditor()
    {
        if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing())
            return;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        if (page == null)
            return;
        IEditorPart editor = page.getActiveEditor();
        if (editor == null)
            return;
        ITextEditor textEditor = TextEditor.resolveTextEditor(editor);
        if (textEditor == null)
            return;
        ISourceViewer viewer = TextEditor.getSourceViewer(textEditor);
        if (viewer == null)
            return;
        StyledText text = viewer.getTextWidget();
        if (text != null && !text.isDisposed())
            handleSelection(text);
    }

    static void deactivate()
    {
        if (activePainter != null)
            activePainter.uninstall();
        activePainter = null;
        activeViewer = null;
        activeWidget = null;
        clearAnnotations();
        releaseStockSuppression();
    }

    private static void deactivateIfActive(StyledText text)
    {
        if (activeWidget == text)
            deactivate();
    }

    /**
     * Подсветка вхождений поверх текста (по образцу {@code AnnotationPainter}):
     * полупрозрачная заливка прямоугольников в {@link SWT#Paint} — не трогает
     * {@code StyleRange} редактора и не конфликтует с подсветкой различий.
     */
    private static final class OccurrencePainter implements PaintListener, DisposeListener
    {
        private static final int FILL_ALPHA = 64;

        final String word;
        /** Виджетный офсет выделения, вокруг которого построено окно подсветки. */
        final int anchor;
        private final StyledText text;
        private final List<int[]> ranges;
        private final Color fill;

        private OccurrencePainter(StyledText text, String word, int anchor, List<int[]> ranges)
        {
            this.text = text;
            this.word = word;
            this.anchor = anchor;
            this.ranges = ranges;
            this.fill = new Color(text.getDisplay(), 255, 220, 0);
        }

        static OccurrencePainter install(StyledText text, String word, int anchor, List<int[]> ranges)
        {
            OccurrencePainter painter = new OccurrencePainter(text, word, anchor, ranges);
            text.addPaintListener(painter);
            text.addDisposeListener(painter);
            painter.redrawAll();
            return painter;
        }

        void uninstall()
        {
            if (text.isDisposed())
                return;
            text.removePaintListener(this);
            text.removeDisposeListener(this);
            fill.dispose();
            redrawAll();
        }

        private void redrawAll()
        {
            if (text.isDisposed())
                return;
            for (int[] range : ranges)
                text.redrawRange(range[0], range[1], false);
        }

        @Override
        public void paintControl(PaintEvent e)
        {
            if (ranges.isEmpty() || text.isDisposed())
                return;
            GC gc = e.gc;
            gc.setAlpha(FILL_ALPHA);
            gc.setBackground(fill);
            Rectangle clip = new Rectangle(e.x, e.y, e.width, e.height);
            for (int[] range : ranges)
                drawRange(gc, range[0], range[0] + range[1], clip);
        }

        /** Заливка вхождения построчно (перенос строк, длинные совпадения). */
        private void drawRange(GC gc, int start, int end, Rectangle clip)
        {
            try
            {
                int charCount = text.getCharCount();
                start = Math.max(0, Math.min(start, charCount));
                end = Math.max(start, Math.min(end, charCount));
                int lineHeight = text.getLineHeight();
                int firstLine = text.getLineAtOffset(start);
                int lastLine = text.getLineAtOffset(Math.max(start, end - 1));
                for (int line = firstLine; line <= lastLine; line++)
                {
                    int lineOffset = text.getOffsetAtLine(line);
                    int lineEndOffset = lineOffset + text.getLine(line).length();
                    int from = Math.max(start, lineOffset);
                    int to = Math.min(end, Math.max(lineOffset, lineEndOffset));
                    if (to <= from)
                        continue;
                    Point p1 = text.getLocationAtOffset(from);
                    Point p2 = text.getLocationAtOffset(to);
                    int width = Math.max(p2.x - p1.x, 2);
                    if (p1.y + lineHeight < clip.y || p1.y > clip.y + clip.height)
                        continue;
                    gc.fillRectangle(p1.x, p1.y, width, lineHeight);
                }
            }
            catch (RuntimeException ignored)
            {
                // офсеты устарели при параллельном изменении текста — следующий
                // Selection-триггер пересчитает подсветку
            }
        }

        @Override
        public void widgetDisposed(DisposeEvent e)
        {
            if (activePainter == this)
            {
                activePainter = null;
                activeWidget = null;
                activeViewer = null;
            }
            fill.dispose();
        }
    }
}
