package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.SyntaxErrorMessage;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.XtextSourceViewer;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.editor.model.IXtextModelListener;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Особая пометка синтаксической ошибки, которая обрывает разбор модуля, — чтобы её было видно
 * среди остальных ошибок на полосах редактора.
 *
 * <p>Такая ошибка качественно отличается от обычной: после неё дерево модуля не строится до
 * конца (см. {@link BslAstCompleteness}), поэтому методы ниже не видны ни структурному
 * сравнению, ни навигации, ни подсказкам. На полосе обзора среди десятка одинаковых красных
 * штрихов это ничем не выделено — плагин добавляет свои аннотации:
 * <ul>
 * <li>{@link #TYPE_POINT} — на строке самой ошибки: свой цвет на полосе обзора и своя иконка
 * на левой линейке;</li>
 * <li>{@link #TYPE_TAIL} — на всём неразобранном остатке модуля: на полосе обзора это сплошная
 * полоса, по которой сразу видно, какая часть модуля потеряна.</li>
 * </ul>
 * Оформление объявлено в {@code plugin.xml} ({@code org.eclipse.ui.editors.annotationTypes} и
 * {@code markerAnnotationSpecification}) — штатную аннотацию ошибки EDT не трогаем, наша идёт
 * в дополнение к ней.
 *
 * <p>Жизненный цикл — как у {@link BracketContentHintHook}: слушатель частей рабочего стола и
 * отложенный пересчёт в фоновом {@link Job} (обработчики вызываются на UI-потоке и только
 * планируют работу).
 *
 * <p>Пометка всегда считается по модели самого редактора — своего разбора модуля плагин не
 * делает, на модулях в десятки тысяч строк это было бы недопустимо дорого. Вместо этого лечится
 * причина: после ВСТАВКИ препроцессорной директивы не на своё место частичный перепарсинг Xtext
 * оставляет дерево «полным» там, где разобрать текст уже нельзя, — в этом случае (и только в
 * нём) плагин заставляет Xtext разобрать модуль заново, см. {@code EditorState.onModelChanged}
 * и {@code EditorState.forceFullReparse}. При исправлении такой ошибки дерево восстанавливается
 * само, и перестраивать ничего не нужно.
 */
public final class BslParseTruncationMarkHook implements IStartup
{
    private static final String TAG = "BslParseTruncation"; //$NON-NLS-1$
    /** Типы аннотаций — совпадают с объявленными в {@code plugin.xml}. */
    public static final String TYPE_POINT = "tormozit.bslParseTruncation"; //$NON-NLS-1$
    public static final String TYPE_TAIL = "tormozit.bslParseTruncationTail"; //$NON-NLS-1$
    /** Задержка перед пересчётом: он стоит полного разбора модуля, серию правок склеиваем в одну. */
    private static final int REBUILD_DEBOUNCE_MS = 800;
    /** Предел числа пометок хвоста: на полосе обзора высотой в сотни пикселей большего не видно. */
    private static final int MAX_TAIL_MARKS = 300;
    /** Сколько символов от начала узла ошибки смотреть в поисках первого непробельного. */
    private static final int HASH_LOOKAHEAD = 32;

    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final Map<StyledText, EditorState> attached = new WeakHashMap<>();
    private static final Map<DtGranularEditor<?>, IPageChangedListener> pageListeners = new HashMap<>();

    @Override
    public void earlyStartup()
    {
        if (!installed.compareAndSet(false, true))
            return;
        PlatformUI.getWorkbench().getDisplay().asyncExec(() ->
        {
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                registerWindow(window);
            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow window) { registerWindow(window); }
                @Override public void windowActivated(IWorkbenchWindow window) {}
                @Override public void windowDeactivated(IWorkbenchWindow window) {}
                @Override public void windowClosed(IWorkbenchWindow window) {}
            });
        });
    }

    private static void registerWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                inspect(ref);
            }

            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                inspect(ref);
            }

            @Override
            public void partClosed(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (part instanceof DtGranularEditor<?> granular)
                    unregisterGranularEditor(granular);
            }

            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) {}
            @Override public void partVisible(IWorkbenchPartReference ref) {}
        });
        for (IWorkbenchPage page : window.getPages())
            for (IEditorReference ref : page.getEditorReferences())
                inspect(ref);
    }

    private static void inspect(IWorkbenchPartReference ref)
    {
        if (!(ref instanceof IEditorReference))
            return;
        IWorkbenchPart part = ref.getPart(false);
        if (part instanceof BslXtextEditor bsl)
            attach(bsl);
        else if (part instanceof DtGranularEditor<?> granular)
            attachGranular(granular);
    }

    private static void attachGranular(DtGranularEditor<?> editor)
    {
        attachEmbeddedPage(editor.getActivePageInstance());
        if (pageListeners.containsKey(editor))
            return;
        IPageChangedListener listener = event -> attachEmbeddedPage(event.getSelectedPage());
        editor.addPageChangedListener(listener);
        pageListeners.put(editor, listener);
    }

    private static void unregisterGranularEditor(DtGranularEditor<?> editor)
    {
        IPageChangedListener listener = pageListeners.remove(editor);
        if (listener != null)
            editor.removePageChangedListener(listener);
    }

    private static void attachEmbeddedPage(Object page)
    {
        if (!(page instanceof DtGranularEditorXtextEditorPage<?> xtextPage))
            return;
        IEditorPart embedded = xtextPage.getEmbeddedEditor();
        if (embedded instanceof BslXtextEditor bsl)
            attach(bsl);
    }

    private static void attach(BslXtextEditor editor)
    {
        try
        {
            ISourceViewer sourceViewer = editor.getInternalSourceViewer();
            if (!(sourceViewer instanceof XtextSourceViewer viewer))
                return;
            StyledText widget = viewer.getTextWidget();
            if (widget == null || widget.isDisposed() || attached.containsKey(widget))
                return;
            IXtextDocument document = viewer.getXtextDocument();
            if (document == null)
                return;

            EditorState state = new EditorState(viewer, document, editor.getTitle());
            attached.put(widget, state);
            document.addModelListener(state.modelListener);
            widget.addDisposeListener(e -> detach(widget));
            // Первый пересчёт — тем же отложенным путём, разбор модуля на UI-потоке недопустим.
            state.schedule(false);
        }
        catch (Exception | LinkageError e)
        {
            log("attach: " + e); //$NON-NLS-1$
        }
    }

    private static void detach(StyledText widget)
    {
        EditorState state = attached.remove(widget);
        if (state == null)
            return;
        try
        {
            state.document.removeModelListener(state.modelListener);
            state.job.cancel();
        }
        catch (Exception | LinkageError ignored)
        {
            // редактор уже закрыт — снимать больше нечего
        }
    }

    private static void log(String message)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, message);
    }

    /** Состояние одного открытого редактора модуля. */
    private static final class EditorState
    {
        final ISourceViewer viewer;
        final IXtextDocument document;
        /** Имя модуля — для записи о полном перепарсинге в журнал «Комфорт». */
        final String title;
        final IXtextModelListener modelListener;
        /** Не {@code final}: задание перевзводит само себя, а из своей же лямбды поле не видно как final. */
        Job job;
        /** Поставленные нами аннотации — чтобы снять их при следующем пересчёте. */
        final List<Annotation> ours = new ArrayList<>(2);
        /** Признак состава синтаксических ошибок прошлой модели — пересчёт только на его изменение. */
        private volatile int knownErrorSignature;
        /** Директивы не на месте в прошлой модели — важно их количество, а не смещения. */
        private volatile List<Integer> knownOffsets = List.of();
        /** Сколько директив было при последнем перестроении — защита от петли перепарсинга. */
        private volatile int reparsedCount;
        /** Была ли уже первая модель: она приходит от разбора с нуля и в лечении не нуждается. */
        private volatile boolean initialized;
        /** Что делать очередному запуску: разобрать заново или довериться модели редактора. */
        private volatile boolean reparseRequested;
        /** Есть ли непрочитанный запрос на пересчёт — задание перевзводит себя по нему. */
        private volatile boolean pending;

        EditorState(ISourceViewer viewer, IXtextDocument document, String title)
        {
            this.viewer = viewer;
            this.document = document;
            this.title = title != null ? title : "модуль"; //$NON-NLS-1$
            this.modelListener = this::onModelChanged;
            this.job = Job.create("Поиск обрыва разбора модуля", monitor -> //$NON-NLS-1$
            {
                pending = false;
                Mark mark = document.readOnly(this::computeByModel);
                /*
                 * Перепарсинг — только когда модель ПОДОЗРИТЕЛЬНА: директива не на своём месте
                 * есть, а дерево при этом целое. Разбирается так (см. PartialParsingHelper.reparse
                 * в декомпиляции): Xtext после частичного разбора области смотрит
                 * hasSyntaxErrors() и при ошибках сам делает полный перепарсинг. Если область
                 * разобралась чисто — а препроцессорный блок законен на уровне операторов, —
                 * отката не происходит, и обрыв на уровне объемлющей конструкции остаётся
                 * незамеченным. Это и есть случай, который надо чинить; когда дерево уже
                 * обрублено, чинить нечего и 100–300 мс тратить незачем.
                 */
                if (mark == null && reparseRequested)
                {
                    // Сбрасываем ТОЛЬКО когда действительно перестраиваем: раньше запрос
                    // забирался всегда, и решение «дерево уже обрублено, чинить нечего» съедало
                    // его впустую — следующая вставка оставалась без перестроения.
                    reparseRequested = false;
                    forceFullReparse();
                    mark = document.readOnly(this::computeByModel);
                }
                else if (mark != null)
                {
                    reparseRequested = false; // дерево обрублено — перестраивать нечего
                }
                final Mark computed = mark; // в лямбду ниже — только effectively final
                PlatformUI.getWorkbench().getDisplay().asyncExec(() -> apply(computed));
                /*
                 * Пока задание работало, могла прийти новая модель. Её вызов schedule() для
                 * ВЫПОЛНЯЮЩЕГОСЯ задания ничего не даёт (Job.schedule на running-задании
                 * игнорируется), поэтому перевзводим себя сами — иначе изменение просто
                 * терялось: наблюдалось как «первая вставка директивы не срабатывает, вторая
                 * срабатывает» (вторая лишь перевзводила задание и подхватывала висящий запрос).
                 */
                if (pending)
                    job.schedule(REBUILD_DEBOUNCE_MS);
                return Status.OK_STATUS;
            });
            this.job.setSystem(true);
            this.job.setPriority(Job.DECORATE);
        }

        /**
         * Единственная точка, где решается, тратить ли работу. Обе проверки здесь бесплатны:
         * инкрементальный разбор Xtext уже отработал, остаётся обойти список его синтаксических
         * ошибок (их единицы).
         *
         * <ul>
         * <li>Изменился состав синтаксических ошибок — стоит пересчитать пометку (проверка
         * целости дерева). Пока состав прежний, правка ничего не сломала и не починила, и
         * сканировать модуль незачем.</li>
         * <li>Появилась директива {@code #…} не на своём месте («Недопустимая лексема "#…" в
         * данном контексте») — дополнительно нужно перестроить дерево: именно при ДОБАВЛЕНИИ
         * такой ошибки частичный перепарсинг оставляет дерево «полным» там, где разобрать текст
         * уже нельзя. При её исправлении дерево восстанавливается штатно, перестраивать нечего.</li>
         * </ul>
         *
         * Признак директивы берётся по символу в документе, а не по формулировке сообщения, —
         * чтобы не зависеть от языка интерфейса.
         */
        private void onModelChanged(XtextResource resource)
        {
            int signature = syntaxErrorSignature(resource);
            List<Integer> offsets = misplacedPreprocessorOffsets(resource);
            boolean errorsChanged = signature != knownErrorSignature;
            int previousCount = knownOffsets.size();
            boolean firstModel = !initialized;
            initialized = true;
            knownErrorSignature = signature;
            knownOffsets = offsets;

            /*
             * Первая модель редактора — это разбор с нуля, он заведомо верен, чинить нечего.
             * Иначе открытие модуля, где такая директива уже есть, выглядело бы как её появление
             * и тянуло за собой холостой перепарсинг.
             */
            if (firstModel)
            {
                schedule(false);
                return;
            }

            /*
             * Перестраиваем дерево, только когда директив стало БОЛЬШЕ. Именно добавление
             * оставляет дерево «полным» там, где разобрать уже нельзя; при удалении дерево
             * восстанавливается штатно, и перепарсинг там — чистая трата (замер: 229 мс на
             * модуле в 1,4 МБ, дерево 587 → 587). Сравнение по количеству, а не по составу:
             * смещения сдвигаются от любой правки выше по тексту.
             *
             * {@code reparsedCount} закрывает петлю: наш же перепарсинг даёт новую модель и
             * новый вызов сюда, и если он нашёл больше директив, чем частичный разбор, второй
             * раз перестраивать не нужно.
             */
            boolean appeared = offsets.size() > previousCount && offsets.size() > reparsedCount;
            reparsedCount = appeared ? offsets.size() : Math.min(reparsedCount, offsets.size());
            if (offsets.size() < previousCount)
                reparseRequested = false; // директиву убрали — перестраивать больше нечего

            /*
             * Главная отсечка по нагрузке: пока состав синтаксических ошибок не менялся,
             * проверять целость дерева незачем — правка ничего не сломала и не починила. Без
             * неё набор текста внутри уже сломанной строки заставлял раз в 800 мс сканировать
             * весь модуль (на 30 000 строк это мегабайт на проход).
             */
            if (!appeared && !errorsChanged)
                return;
            schedule(appeared);
        }

        /**
         * Признак состава синтаксических ошибок: их количество и формулировки. Смещения сюда не
         * входят намеренно — иначе любая правка выше по тексту сдвигала бы их и признак менялся
         * бы без причины. Всё нужное уже посчитано разбором, здесь только обход единиц элементов.
         */
        private static int syntaxErrorSignature(XtextResource resource)
        {
            IParseResult parseResult = resource != null ? resource.getParseResult() : null;
            Iterable<INode> errors = parseResult != null ? parseResult.getSyntaxErrors() : null;
            if (errors == null)
                return 0;
            int count = 0;
            int messages = 0;
            for (INode error : errors)
            {
                if (error == null)
                    continue;
                count++;
                SyntaxErrorMessage message = error.getSyntaxErrorMessage();
                String text = message != null ? message.getMessage() : null;
                messages += text != null ? text.hashCode() : 0;
            }
            return count * 31 + messages;
        }

        /**
         * Смещения директив не на своём месте — именно состав, а не «есть или нет»: модуль может
         * уже содержать такую ошибку, и тогда добавление второй по булеву признаку осталось бы
         * незамеченным (так и было — перестроение не запускалось).
         */
        private List<Integer> misplacedPreprocessorOffsets(XtextResource resource)
        {
            IParseResult parseResult = resource != null ? resource.getParseResult() : null;
            Iterable<INode> errors = parseResult != null ? parseResult.getSyntaxErrors() : null;
            if (errors == null)
                return List.of();
            List<Integer> offsets = new ArrayList<>(2);
            for (INode error : errors)
                if (error != null && mentionsPreprocessorToken(error) && startsWithHash(error.getOffset()))
                    offsets.add(Integer.valueOf(error.getOffset()));
            return offsets;
        }

        /**
         * Ошибочная лексема в сообщении — та самая директива.
         *
         * <p>Одного взгляда на первый символ узла ошибки мало: узел восстановления часто
         * начинается со строки {@code #Область}, хотя ошибка совсем в другом месте, и в модуле,
         * где областей много, любая мелкая опечатка запускала холостое перестроение (замер:
         * 255 и 211 мс, дерево 587 → 587). В сообщении разбора ошибочная лексема указана в
         * кавычках — {@code Недопустимая лексема "'#Если'" в данном контексте}, — по ней и
         * отличаем. Ключ — сама лексема, а не слова сообщения, так что перевод интерфейса
         * признак не ломает.
         *
         * <p>Апостроф перед {@code #} обязателен, и это не придирка к форматированию. В
         * апострофах разбор цитирует РЕАЛЬНО ВСТРЕЧЕННУЮ лексему — это наш случай. Без них
         * цитируется ОЖИДАЕМАЯ лексема: {@code Пропущена лексема "#КонецЕсли" у "ИначеЕсли"} —
         * сообщение о законной конструкции, после которого разбор восстанавливается. Пока я
         * принимал оба варианта, такое сообщение запускало холостое перестроение.
         *
         * <p>И лексема должна быть ОТКРЫВАЮЩЕЙ. Замер: {@code Недопустимая лексема "'#КонецЕсли'"}
         * — дерево остаётся целым (4 = 4), перестроение холостое; обрыв даёт только
         * {@code "'#Если'"}, когда парсер начинает конструкцию, которую не может закрыть.
         */
        private static boolean mentionsPreprocessorToken(INode error)
        {
            SyntaxErrorMessage message = error.getSyntaxErrorMessage();
            String token = quotedToken(message != null ? message.getMessage() : null);
            return "#Если".equalsIgnoreCase(token) || "#If".equalsIgnoreCase(token); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** Лексема из сообщения разбора: {@code …"'#Если'"…} → {@code #Если}; иначе {@code null}. */
        private static String quotedToken(String message)
        {
            if (message == null)
                return null;
            int start = message.indexOf("'#"); //$NON-NLS-1$
            if (start < 0)
                return null;
            int end = message.indexOf('\'', start + 1);
            return end > start ? message.substring(start + 1, end) : null;
        }

        /**
         * Первый непробельный символ узла ошибки — читаем из документа, а НЕ через
         * {@code INode.getText()}: узел ошибки может покрывать весь неразобранный остаток модуля,
         * и его {@code getText()} собрал бы строку в сотни килобайт — на каждую новую модель, на
         * UI-потоке. Здесь же несколько символов.
         */
        private boolean startsWithHash(int offset)
        {
            try
            {
                int limit = Math.min(offset + HASH_LOOKAHEAD, document.getLength());
                for (int i = Math.max(0, offset); i < limit; i++)
                {
                    char c = document.getChar(i);
                    if (!Character.isWhitespace(c))
                        return c == '#';
                }
            }
            catch (BadLocationException e)
            {
                log("startsWithHash: " + e); //$NON-NLS-1$
            }
            return false;
        }

        /**
         * Заставляет Xtext разобрать модуль заново целиком.
         *
         * <p>Это лечение перекоса самого редактора: после вставки препроцессорной директивы не на
         * своё место частичный перепарсинг оставляет дерево «полным», хотя разобрать текст уже
         * нельзя, — и от истории правок начинают зависеть не только наша пометка, но и структура,
         * навигация, подсказки. Случается не всегда: {@code PartialParsingHelper.reparse} при
         * ошибках в разобранной области сам откатывается к полному разбору, и тогда дерево
         * обрубается без нас (замеры: на модуле в 1,4 МБ шесть перепарсингов подряд оказались
         * холостыми, 587 → 587). Поэтому вызывается только при подозрительной модели — см. место
         * вызова.
         *
         * <p>ПРОВЕРЕНО И НЕ РАБОТАЕТ: перечитывание только хвоста, от строки ошибки до конца
         * модуля, через {@code XtextResource.update(from, length, тот же текст)}. Замер на модуле,
         * который при открытии даёт 1 метод: после такого «перечитывания» в дереве оставалось 4
         * метода, то есть разбор не выполнялся вовсе: текст области не изменился, и частичный
         * разбор закорачивается. Объемлющие конструкции он в любом случае не пересматривает, а
         * обрыв — это именно неудача разбора на верхнем уровне.
         *
         * <p>Поэтому полный {@code reparse}. Он дорог на модулях в десятки тысяч строк, но
         * выполняется не при каждой правке, а один раз на появление такой ошибки (см.
         * {@link #onModelChanged}), в фоновом {@link Job}.
         */
        private void forceFullReparse()
        {
            try
            {
                String report = document.modify(resource ->
                {
                    int before = methodCount(resource);
                    long started = System.currentTimeMillis();
                    resource.reparse(document.get());
                    return title + ": " + (System.currentTimeMillis() - started) //$NON-NLS-1$
                        + " мс, методов " + before + " → " + methodCount(resource); //$NON-NLS-1$ //$NON-NLS-2$
                });
                log("полный перепарсинг — " + report); //$NON-NLS-1$
            }
            catch (Exception | LinkageError e)
            {
                log("forceFullReparse: " + e); //$NON-NLS-1$
            }
        }

        /**
         * @param reparse {@code true} — директив стало больше, дерево может требовать
         *     перестроения ({@link #forceFullReparse}). Запрос «липкий»: он живёт, пока
         *     перестроение действительно не выполнится или пока директивы не уберут, — иначе
         *     соседние модели, приходящие почти сразу, сбрасывали бы его раньше времени.
         */
        void schedule(boolean reparse)
        {
            if (reparse)
                reparseRequested = true;
            pending = true; // если задание сейчас работает, оно перевзведёт себя само
            job.cancel();
            job.schedule(REBUILD_DEBOUNCE_MS);
        }

        /**
         * Решение принимается по модели самого редактора — своего разбора модуля у нас нет.
         *
         * <p>Это допустимо потому, что перекос модели после вставки директивы мы лечим в
         * источнике ({@link #forceFullReparse}), а не обходим сбоку: во всех остальных случаях
         * дерево редактора верно, и пометка совпадает с тем, что увидят сравнение и структура.
         */
        private Mark computeByModel(XtextResource resource)
        {
            if (resource == null || !BslAstCompleteness.isTruncated(resource))
                return null;
            return toMark(BslAstCompleteness.truncatingError(resource.getParseResult()));
        }

        /** Временная диагностика перепарсинга — снять после подтверждения. */
        private static int methodCount(XtextResource resource)
        {
            org.eclipse.emf.ecore.EObject root =
                resource.getParseResult() != null ? resource.getParseResult().getRootASTElement() : null;
            return root instanceof com._1c.g5.v8.dt.bsl.model.Module module ? module.allMethods().size() : -1;
        }

        private static Mark toMark(INode error)
        {
            if (error == null)
                return null;
            SyntaxErrorMessage message = error.getSyntaxErrorMessage();
            return new Mark(error.getOffset(), Math.max(1, error.getLength()),
                message != null ? message.getMessage() : null);
        }

        void apply(Mark mark)
        {
            IAnnotationModel model = viewer.getAnnotationModel();
            if (model == null)
                return;
            Annotation[] toRemove = ours.toArray(new Annotation[0]);
            ours.clear();

            Map<Annotation, Position> toAdd = new LinkedHashMap<>();
            if (mark != null)
            {
                int length = document.getLength();
                int offset = Math.max(0, Math.min(mark.offset, length));

                Annotation point = new Annotation(TYPE_POINT, false, describe(mark.message));
                toAdd.put(point, new Position(offset, Math.min(mark.length, length - offset)));
                ours.add(point);
                addTail(toAdd, offset);
            }

            if (toRemove.length == 0 && toAdd.isEmpty())
                return;
            if (model instanceof IAnnotationModelExtension ext)
            {
                ext.replaceAnnotations(toRemove, toAdd);
                return;
            }
            for (Annotation annotation : toRemove)
                model.removeAnnotation(annotation);
            for (Map.Entry<Annotation, Position> entry : toAdd.entrySet())
                model.addAnnotation(entry.getKey(), entry.getValue());
        }

        /**
         * Хвост помечаем построчно, а не одной длинной аннотацией на весь остаток.
         *
         * <p>Многострочная аннотация ломает подсказку линейки обзора: та для перекрытой
         * области показывает предпросмотр всего охваченного текста — на пол-модуля выходит
         * подсказка во весь экран. Построчные аннотации дают на линейке ту же сплошную полосу,
         * а подсказка остаётся короткой.
         *
         * <p>Текста у них нет ({@code null}) — сообщение должно быть одно, на строке ошибки,
         * иначе оно повторяется в подсказке столько раз, сколько строк попало под курсор.
         *
         * <p>Число аннотаций ограничено {@link #MAX_TAIL_MARKS}: на длинном хвосте помечаем
         * каждую N-ю строку — на полосе обзора высотой в несколько сотен пикселей разницы не
         * видно, а лишних объектов в модели не заводим.
         */
        private void addTail(Map<Annotation, Position> toAdd, int offset)
        {
            try
            {
                int firstLine = document.getLineOfOffset(offset);
                int lastLine = document.getNumberOfLines() - 1;
                int lines = lastLine - firstLine;
                if (lines <= 0)
                    return;
                int step = Math.max(1, (lines + MAX_TAIL_MARKS - 1) / MAX_TAIL_MARKS);
                for (int line = firstLine + 1; line <= lastLine; line += step)
                {
                    Annotation tail = new Annotation(TYPE_TAIL, false, null);
                    toAdd.put(tail, new Position(document.getLineOffset(line), 1));
                    ours.add(tail);
                }
            }
            catch (BadLocationException e)
            {
                log("addTail: " + e); //$NON-NLS-1$
            }
        }

        private static String describe(String message)
        {
            String text = "Обрыв разбора модуля: методы ниже не разобраны" //$NON-NLS-1$
                + Global.pluginSignForTooltip();
            return message != null && !message.isBlank() ? text + ". " + message : text; //$NON-NLS-1$
        }
    }

    /** Место обрыва: смещение, длина узла ошибки и текст диагностики. */
    private static final class Mark
    {
        final int offset;
        final int length;
        final String message;

        Mark(int offset, int length, String message)
        {
            this.offset = offset;
            this.length = length;
            this.message = message;
        }
    }
}
