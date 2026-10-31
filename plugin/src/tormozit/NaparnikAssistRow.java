package tormozit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.AbstractInformationControl;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.ITextListener;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.TextEvent;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.text.contentassist.ContentAssistEvent;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.contentassist.ICompletionListenerExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension4;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension6;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;

/**
 * Строка с предложением 1С:Напарника наверху списка автодополнения.
 *
 * <p>Запрос к серверу собираем сами, а не штатным {@code CompletionRequestProvider}: тот
 * приклеивает к тексту модуля префикс выбранного пункта списка. Здесь в запрос идут текст модуля
 * у каретки и слова списка целиком, без отметки, какое из них текущее.
 *
 * <p>Строка и боковая подсказка обновляются только целыми строками предложения: пока сервер не
 * прислал перевод строки (или не закончил ответ), показывается «...».
 *
 * @see <a href="https://github.com/tormozit/EDT.Comfort/issues/759">issue 759</a>
 */
public final class NaparnikAssistRow
{
    private static final String THINKING = "..."; //$NON-NLS-1$
    /** Ответ пустой, с ошибкой или не дал ни одной пригодной строки. */
    private static final String NO_SUGGESTION = "ИИ не знает"; //$NON-NLS-1$
    private static final String ERROR = "<ошибка>"; //$NON-NLS-1$
    /** Начало текста боковой подсказки, который не код: показывается без раскраски языка. */
    private static final char PLAIN_INFO_MARK = '\u0001';

    /** Сколько слов списка уходит в запрос; столько же берёт штатный запрос Напарника. */
    private static final int MAX_WORDS = 600;
    /** Пауза перед запросом: на серию нажатий — один запрос. */
    private static final int REQUEST_DELAY_MS = 300;
    private static final int WORDS_WAIT_STEP_MS = 100;
    private static final int WORDS_WAIT_ATTEMPTS = 10;

    private static final String NAPARNIK_UI_BUNDLE = "com.e1c.edt.ai.ui"; //$NON-NLS-1$
    private static final String NAPARNIK_PACKAGE = "com.e1c.edt.ai"; //$NON-NLS-1$
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    private static final Map<SourceViewer, NaparnikAssistRow> INSTANCES = new WeakHashMap<>();
    /** Сеанс автодополнения, чей список сейчас открыт. */
    private static volatile NaparnikAssistRow active;
    /** Текст ошибки последнего запроса любого редактора; {@code null} — последний запрос удался. */
    private static String lastFailure;
    private static Image rowImage;
    private static boolean rowImageResolved;

    private final SourceViewer viewer;
    private final ContentAssistant assistant;
    private final RowProposal proposal = new RowProposal();
    private final VerifyKeyListener keyListener = this::onVerifyKey;
    private final ITextListener textListener = this::onTextChanged;
    private final ICompletionListener completionListener = new SessionListener();

    private boolean sessionOpen;
    /** Список открыт командой (Ctrl+Space), а не автооткрытием при вводе. */
    private boolean openedByCommand;
    /** Политика Напарника «Выключен»: строка показывает последнюю ошибку, запросов нет. */
    private boolean requestsOff;
    /** Модель завершения кода Напарника этого редактора; {@code null} — строки нет. */
    private Object viewModel;
    /** Пользователь сам перешёл на строку Напарника: её выделение не сбрасываем. */
    private boolean rowNavigated;
    private boolean selectRowOnOpen;
    private boolean applying;

    /** Позиция модели, для которой запрошено предложение; {@code -1} — предложения нет. */
    private int baseOffset = -1;
    /** Ответ сервера с переводами строк {@code \n}; {@code null} — ответа ещё нет. */
    private String full;
    private boolean completed;
    /** Текст ошибки последнего запроса; {@code null} — ошибки не было. */
    private String error;
    private volatile int generation;
    private Object cancelSource;
    /** Непринятый остаток предложения целыми строками, переводы строк {@code \n}. */
    private String remaining = ""; //$NON-NLS-1$

    private NaparnikAssistRow(SourceViewer viewer, ContentAssistant assistant)
    {
        this.viewer = viewer;
        this.assistant = assistant;
    }

    // ---- подключение ---------------------------------------------------------

    public static void install(SourceViewer viewer, ContentAssistant assistant)
    {
        if (viewer == null || assistant == null)
            return;
        NaparnikAssistRow existing = INSTANCES.get(viewer);
        if (existing != null)
        {
            if (existing.assistant == assistant)
                return;
            existing.dispose();
        }
        NaparnikAssistRow row = new NaparnikAssistRow(viewer, assistant);
        INSTANCES.put(viewer, row);
        assistant.addCompletionListener(row.completionListener);
        viewer.addTextListener(row.textListener);
    }

    public static void uninstall(SourceViewer viewer)
    {
        NaparnikAssistRow row = INSTANCES.remove(viewer);
        if (row != null)
            row.dispose();
    }

    private void dispose()
    {
        endSession();
        assistant.removeCompletionListener(completionListener);
        viewer.removeTextListener(textListener);
        viewer.removeVerifyKeyListener(keyListener);
    }

    // ---- строка в списке -----------------------------------------------------

    static boolean isRow(ICompletionProposal proposal)
    {
        return proposal instanceof RowProposal
            || SmartContentAssistProcessor.unwrapProposal(proposal) instanceof RowProposal;
    }

    /**
     * Список со строкой Напарника на первом месте — или без неё, если сейчас она не нужна.
     * К пустому списку строка добавляется, только если его открыли командой (Ctrl+Space):
     * при автооткрытии одна она окно автодополнения не держит.
     */
    static List<ICompletionProposal> withRow(List<ICompletionProposal> list)
    {
        if (list == null)
            return null;
        // Сюда сходятся все пути списка в окно: боковая подсказка — от обёртки, при любом флажке «Фильтр».
        list = SmartContentAssistProcessor.wrapForSideHint(list);
        NaparnikAssistRow row = active;
        int rows = 0;
        int placeholders = 0;
        boolean regular = false;
        for (ICompletionProposal p : list)
        {
            if (isRow(p))
                rows++;
            else if (SmartContentAssistProcessor.isEmptyPlaceholderProposal(p))
                placeholders++;
            else
                regular = true;
        }
        boolean want = row != null && row.viewModel != null && (regular || row.openedByCommand);
        // Строка «нет вариантов» рядом со строкой Напарника не нужна.
        boolean dropPlaceholders = want && !regular && placeholders > 0;
        if (!dropPlaceholders && (want ? rows == 1 && list.get(0) == row.proposal : rows == 0))
            return list;
        List<ICompletionProposal> out = new ArrayList<>(list.size() + 1);
        if (want)
            out.add(row.proposal);
        for (ICompletionProposal p : list)
        {
            if (isRow(p))
                continue;
            if (dropPlaceholders && SmartContentAssistProcessor.isEmptyPlaceholderProposal(p))
                continue;
            out.add(p);
        }
        return out;
    }

    static ICompletionProposal[] withRow(ICompletionProposal[] proposals)
    {
        proposals = SmartContentAssistProcessor.wrapForSideHint(proposals);
        NaparnikAssistRow row = active;
        if (row == null)
            return proposals;
        if (proposals == null)
        {
            if (!row.openedByCommand)
                return null;
            proposals = new ICompletionProposal[0];
        }
        // Напарник сам зовёт процессор, собирая слова для своего запроса, и обрывает разбор
        // на первом же непонятном ему пункте — нашу строку ему отдавать нельзя.
        if (STACK_WALKER.walk(
            frames -> frames.anyMatch(frame -> frame.getClassName().startsWith(NAPARNIK_PACKAGE))))
            return proposals;
        List<ICompletionProposal> list = new ArrayList<>(proposals.length);
        for (ICompletionProposal p : proposals)
            list.add(p);
        List<ICompletionProposal> out = withRow(list);
        return out == list ? proposals : out.toArray(new ICompletionProposal[0]);
    }

    /**
     * Строка списка, выделяемая по умолчанию: строку Напарника пропускаем, пока пользователь
     * сам на неё не перешёл.
     */
    static int defaultIndex(List<ICompletionProposal> list)
    {
        if (list == null || list.size() < 2 || !isRow(list.get(0)))
            return 0;
        NaparnikAssistRow row = active;
        return row != null && row.rowNavigated ? 0 : 1;
    }

    // ---- сеанс автодополнения ------------------------------------------------

    private void startSession(boolean byCommand)
    {
        viewModel = null;
        openedByCommand = byCommand;
        if (!ComfortSettings.isNaparnikAssistRowEnabled())
            return;
        StyledText widget = viewer.getTextWidget();
        Object vm = NaparnikManualModeHook.findViewModel(widget);
        String policy = NaparnikManualModeHook.completionPolicy();
        if (vm == null)
            return;
        // Политика «Выключен» — завершение кода у Напарника отключено, запросов не шлём. Строки
        // тоже нет, кроме одного случая: последний запрос кончился ошибкой. Тогда строка остаётся
        // и объясняет, почему предложений больше нет.
        requestsOff = "off".equalsIgnoreCase(policy); //$NON-NLS-1$
        if (requestsOff && lastFailure == null)
            return;
        viewModel = vm;
        // Ответа нет и ждать его не от кого: запрос прошлого сеанса отменён при закрытии списка.
        // После ошибки новое открытие списка — новая попытка.
        if (requestsOff || full == null || error != null)
            dropSuggestion();
        sessionOpen = true;
        active = this;
        rowNavigated = selectRowOnOpen;
        selectRowOnOpen = false;
        viewer.prependVerifyKeyListener(keyListener);
        recompute(true);
    }

    private void endSession()
    {
        sessionOpen = false;
        openedByCommand = false;
        if (requestsOff)
        {
            requestsOff = false;
            dropSuggestion();
        }
        if (active == this)
            active = null;
        // Полученное предложение не сбрасываем: Enter закрывает список, вставляет строку и
        // открывает его заново — остаток должен дожить до следующего сеанса.
        if (full == null)
            dropSuggestion();
    }

    private final class SessionListener implements ICompletionListener, ICompletionListenerExtension2
    {
        @Override
        public void assistSessionStarted(ContentAssistEvent event)
        {
            try
            {
                startSession(!event.isAutoActivated);
            }
            catch (RuntimeException ignored)
            {
                // Строки в этом сеансе не будет; сам список автодополнения ломать нельзя.
            }
        }

        @Override
        public void assistSessionEnded(ContentAssistEvent event)
        {
            endSession();
        }

        @Override
        public void selectionChanged(ICompletionProposal selected, boolean smartToggle)
        {
            if (!sessionOpen)
                return;
            // Свой слушатель клавиш окно автодополнения ставит при показе, уже после начала
            // сеанса; повторная постановка возвращает наш слушатель в начало цепочки.
            viewer.prependVerifyKeyListener(keyListener);
            if (!isRow(selected) || rowNavigated)
                return;
            Display display = display();
            if (display == null)
                return;
            // Окно автодополнения при показе само выделяет первую строку, и сделает это уже
            // после этого уведомления — увести выделение можно только следующим шагом. Чтобы
            // перескок не был виден, таблица на этот шаг не перерисовывается. Таймер, а не
            // asyncExec: тот ждёт пустой очереди сообщений и может задержаться надолго.
            Table table = ContentAssistPopupSync.getProposalTable(
                ContentAssistPopupSync.getPopupObject(assistant));
            boolean frozen = table != null && !table.isDisposed();
            if (frozen)
                table.setRedraw(false);
            display.timerExec(0, () ->
            {
                try
                {
                    leaveRowIfNotNavigated();
                }
                finally
                {
                    if (frozen && !table.isDisposed())
                        table.setRedraw(true);
                }
            });
        }

        @Override
        public void applied(ICompletionProposal applied)
        {
        }
    }

    private void leaveRowIfNotNavigated()
    {
        if (!sessionOpen || rowNavigated || !isRowSelected())
            return;
        List<ICompletionProposal> list = ContentAssistPopupSync.visibleProposals(assistant);
        if (list != null && list.size() > 1)
            ContentAssistPopupSync.selectProposalIndex(assistant, 1);
    }

    private boolean isRowSelected()
    {
        if (ContentAssistPopupSync.selectedProposalIndex(assistant) != 0)
            return false;
        List<ICompletionProposal> list = ContentAssistPopupSync.visibleProposals(assistant);
        return list != null && !list.isEmpty() && isRow(list.get(0));
    }

    // ---- текст модуля и предложение ------------------------------------------

    private void onTextChanged(TextEvent event)
    {
        DocumentEvent change = event.getDocumentEvent();
        if (change == null)
            return;
        if (baseOffset >= 0 && change.getOffset() < baseOffset)
            dropSuggestion();
        if (!sessionOpen)
            return;
        if (!applying)
            rowNavigated = false;
        // Каретка в момент события ещё на старом месте.
        Display display = display();
        if (display != null)
            display.asyncExec(() -> recompute(false));
    }

    private void dropSuggestion()
    {
        cancelRequest();
        baseOffset = -1;
        full = null;
        completed = false;
        error = null;
    }

    /**
     * Сверяет предложение с тем, что набрано после точки запроса, и обновляет строку списка.
     * Не подходит или кончилось — новый запрос.
     */
    private void recompute(boolean immediateRequest)
    {
        if (!sessionOpen || viewModel == null)
            return;
        IDocument document = viewer.getDocument();
        int caret = SmartContentAssistProcessor.resolveWidgetCaret(viewer);
        if (document == null || caret < 0)
            return;
        String typed = null;
        if (baseOffset >= 0 && caret >= baseOffset)
        {
            try
            {
                typed = normalize(document.get(baseOffset, caret - baseOffset));
            }
            catch (Exception ignored)
            {
            }
        }
        boolean fits = typed != null;
        if (fits && full != null)
        {
            int common = Math.min(typed.length(), full.length());
            fits = full.regionMatches(true, 0, typed, 0, common);
            if (fits && completed && typed.length() >= full.length() && !typed.isEmpty())
                fits = false;
        }
        if (!fits)
        {
            requestSuggestion(caret, immediateRequest);
            return;
        }
        String shown = completed ? full : completeLines(full);
        setRemaining(shown != null && shown.length() > typed.length()
            ? shown.substring(typed.length()) : ""); //$NON-NLS-1$
    }

    /** Часть ответа до последнего пришедшего перевода строки; недописанная строка не показывается. */
    private static String completeLines(String text)
    {
        if (text == null)
            return null;
        int end = text.lastIndexOf('\n');
        return end < 0 ? "" : text.substring(0, end); //$NON-NLS-1$
    }

    private void setRemaining(String value)
    {
        boolean thinking = value.isBlank() && !completed;
        boolean failed = value.isBlank() && completed && error != null;
        String display = thinking ? THINKING : failed ? ERROR
            : value.isBlank() ? NO_SUGGESTION : firstLineLabel(value);
        String info = failed ? PLAIN_INFO_MARK + error : value.isBlank() ? null : stripLeadingBreaks(value);
        boolean changed = !display.equals(proposal.display)
            || (info == null ? proposal.info != null : !info.equals(proposal.info));
        remaining = value;
        proposal.display = display;
        proposal.info = info;
        if (changed && sessionOpen)
            ContentAssistPopupSync.refreshProposalRow(assistant, proposal);
    }

    private static String firstLineLabel(String text)
    {
        String label = null;
        boolean more = false;
        for (String line : text.split("\n", -1)) //$NON-NLS-1$
        {
            if (line.isBlank())
                continue;
            if (label != null)
            {
                more = true;
                break;
            }
            label = line.strip().replace('\t', ' ');
        }
        if (label == null)
            return NO_SUGGESTION;
        return more ? label + " …" : label; //$NON-NLS-1$
    }

    private static String stripLeadingBreaks(String text)
    {
        int start = 0;
        while (start < text.length() && text.charAt(start) == '\n')
            start++;
        return text.substring(start);
    }

    private static String normalize(String text)
    {
        return text.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- запрос к серверу Напарника ------------------------------------------

    private void cancelRequest()
    {
        generation++;
        Object source = cancelSource;
        cancelSource = null;
        if (source != null)
        {
            try
            {
                Global.invokeVoid(source, "cancel"); //$NON-NLS-1$
            }
            catch (RuntimeException ignored)
            {
            }
        }
    }

    private void requestSuggestion(int caret, boolean immediate)
    {
        cancelRequest();
        baseOffset = caret;
        if (requestsOff)
        {
            full = ""; //$NON-NLS-1$
            completed = true;
            error = lastFailure + "\n\nЗавершение кода 1С:Напарника сейчас выключено (политика «Выключен»)," //$NON-NLS-1$
                + " новые запросы не выполняются."; //$NON-NLS-1$
            setRemaining(""); //$NON-NLS-1$
            return;
        }
        full = null;
        completed = false;
        error = null;
        setRemaining(""); //$NON-NLS-1$
        int gen = generation;
        Display display = display();
        if (display == null)
            return;
        display.timerExec(immediate ? WORDS_WAIT_STEP_MS : REQUEST_DELAY_MS,
            () -> launchRequest(gen, 0));
    }

    private void launchRequest(int gen, int attempt)
    {
        if (gen != generation || !sessionOpen || viewModel == null)
            return;
        Display display = display();
        if (display == null)
            return;
        List<Word> words = collectWords();
        // Первый список считается в фоне: без слов запрос уйдёт только если их так и не дождались.
        if (words.isEmpty() && attempt < WORDS_WAIT_ATTEMPTS)
        {
            display.timerExec(WORDS_WAIT_STEP_MS, () -> launchRequest(gen, attempt + 1));
            return;
        }
        Object vm = viewModel;
        Object source;
        try
        {
            source = loadClass(vm, "com.e1c.edt.ai.CancellationTokenSource") //$NON-NLS-1$
                .getDeclaredConstructor().newInstance();
        }
        catch (Throwable t)
        {
            failRequest(gen, display, "Не удалось подготовить запрос к Напарнику.", t); //$NON-NLS-1$
            return;
        }
        cancelSource = source;
        Thread thread = new Thread(() -> runRequest(gen, vm, source, words, display),
            "Комфорт: предложение Напарника"); //$NON-NLS-1$
        thread.setDaemon(true);
        thread.start();
    }

    /** Слова открытого списка в порядке показа; строка Напарника и заглушка пустого списка не входят. */
    private List<Word> collectWords()
    {
        List<Word> words = new ArrayList<>();
        List<ICompletionProposal> list = ContentAssistPopupSync.computedProposals(assistant);
        if (list == null)
            return words;
        for (ICompletionProposal p : list)
        {
            if (words.size() >= MAX_WORDS)
                break;
            if (isRow(p) || SmartContentAssistProcessor.isEmptyPlaceholderProposal(p))
                continue;
            ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(p);
            if (!(raw instanceof ICompletionProposalExtension3 ext3))
                continue;
            try
            {
                CharSequence text = ext3.getPrefixCompletionText(null, 0);
                String display = raw.getDisplayString();
                if (text == null || display == null)
                    continue;
                int priority = raw instanceof ConfigurableCompletionProposal configurable
                    ? configurable.getPriority() : 0;
                words.add(new Word(display, text.toString(), priority));
            }
            catch (RuntimeException ignored)
            {
            }
        }
        return words;
    }

    /** Сколько ждать связывания модуля перед запросом контекста, потом — как раньше. */
    private static final long LINK_WAIT_CAP_MS = 20_000;
    private static final long LINK_WAIT_STEP_MS = 100;

    /**
     * Ждёт, пока модуль связан ({@code BslResource.isLinkedBatch}). Контекст Напарника
     * ({@code getAiContext} → {@code Contexts.create} → {@code resolveLazyCrossReferences}) на
     * несвязанном модуле сам запускает полную перевязку: на модуле БСП это 3,3 с лишнего
     * расчёта типов среди перевязок EDT после правки, и список членов ждал их все (18.10 10:18:03).
     * Перевязку после правки и так делает сверка редактора — дожидаемся её.
     *
     * @return {@code false} — запрос отменён, пока ждали
     */
    private boolean awaitModuleLinked(int gen)
    {
        if (!(viewer.getDocument() instanceof org.eclipse.xtext.ui.editor.model.IXtextDocument document))
            return true;
        long started = System.nanoTime();
        while (gen == generation)
        {
            // tryReadOnly не ждёт блокировку: занято (идёт перевязка) — null.
            Boolean linked = document.tryReadOnly(resource ->
                resource instanceof com._1c.g5.v8.dt.bsl.resource.BslResource bsl ? bsl.isLinkedBatch() : Boolean.TRUE);
            if (Boolean.TRUE.equals(linked) || (System.nanoTime() - started) / 1_000_000L >= LINK_WAIT_CAP_MS)
                return true;
            try
            {
                Thread.sleep(LINK_WAIT_STEP_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void runRequest(int gen, Object vm, Object source, List<Word> words, Display display)
    {
        try
        {
            if (!awaitModuleLinked(gen))
                return;
            Object context = Global.invoke(vm, "getAiContext", source); //$NON-NLS-1$
            Object aiContext = context instanceof Optional<?> optional ? optional.orElse(null) : null;
            if (aiContext == null)
            {
                failRequest(gen, display, "Напарник не дал контекст модуля для запроса.", null); //$NON-NLS-1$
                return;
            }
            Class<?> providerType = loadClass(vm, "com.e1c.edt.ai.assistent.ICompletionRequestProvider"); //$NON-NLS-1$
            Object provider = proxy(providerType, (self, method, args) ->
            {
                if ("get".equals(method.getName()) && args != null && args.length == 2) //$NON-NLS-1$
                    return buildRequest(gen, vm, aiContext, words, args[0], args[1]);
                return null;
            });
            Class<?> observerType = loadClass(vm, "com.e1c.edt.ai.IObserver"); //$NON-NLS-1$
            Object observer = proxy(observerType, (self, method, args) ->
            {
                String name = method.getName();
                if ("onNext".equals(name) && args != null && args.length == 1) //$NON-NLS-1$
                    onCompletion(gen, vm, aiContext, source, args[0], display);
                else if ("onError".equals(name)) //$NON-NLS-1$
                {
                    Throwable serverError =
                        args != null && args.length == 1 && args[0] instanceof Throwable t ? t : null;
                    failRequest(gen, display, "Сервер Напарника вернул ошибку.", serverError); //$NON-NLS-1$
                }
                else if ("onCompleted".equals(name)) //$NON-NLS-1$
                    finishRequest(gen, display);
                return null;
            });
            Object codeAssistant = Global.getField(vm, "codeAssistant"); //$NON-NLS-1$
            Object project = projectArgument(vm, aiContext);
            if (project == null)
            {
                failRequest(gen, display, "Напарник не определил проект модуля.", null); //$NON-NLS-1$
                return;
            }
            Object observable = call(vm, "com.e1c.edt.ai.assistent.ICodeAssistant", "createSource", //$NON-NLS-1$ //$NON-NLS-2$
                codeAssistant, project, provider, source);
            call(vm, "com.e1c.edt.ai.IObservable", "subscribe", observable, observer); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Throwable t)
        {
            failRequest(gen, display, "Запрос к серверу Напарника не выполнен.", t); //$NON-NLS-1$
        }
    }

    /**
     * Проект для вызова сервера. Тип аргумента у версий Напарника разный ({@code IProject} либо
     * {@code ProjectId}), а имя метода контекста меняется вместе с ним — поэтому берём метод
     * контекста, возвращающий ровно тот тип, которого ждёт {@code createSource}.
     */
    private static Object projectArgument(Object vm, Object aiContext) throws Exception
    {
        for (Method source : loadClass(vm, "com.e1c.edt.ai.assistent.ICodeAssistant").getMethods()) //$NON-NLS-1$
        {
            if (!"createSource".equals(source.getName()) || source.getParameterCount() != 3) //$NON-NLS-1$
                continue;
            Class<?> wanted = source.getParameterTypes()[0];
            for (Method getter : aiContext.getClass().getMethods())
            {
                if (getter.getParameterCount() == 0 && getter.getReturnType() == wanted)
                    return getter.invoke(aiContext);
            }
        }
        return null;
    }

    /**
     * Запрос как у штатного сборщика Напарника для открытого списка автодополнения, но без
     * префикса выбранного пункта: текст модуля как есть, слова списка — все.
     */
    private Object buildRequest(int gen, Object vm, Object aiContext, List<Word> words,
        Object statistics, Object token) throws Exception
    {
        Object localContext = call(vm, "com.e1c.edt.ai.ILocalContext", "create", //$NON-NLS-1$ //$NON-NLS-2$
            Global.getField(vm, "localContext"), aiContext, statistics, token); //$NON-NLS-1$
        if (localContext == null)
            return Optional.empty();
        Object prefix = Global.invoke(aiContext, "getPrefix"); //$NON-NLS-1$
        Object proposalsProvider = Global.getField(vm, "proposalsProvider"); //$NON-NLS-1$
        List<Object> proposals = new ArrayList<>(words.size());
        for (Word word : words)
        {
            Object converted = call(vm, "com.e1c.edt.ai.ui.IProposalsProvider", "getProposal", //$NON-NLS-1$ //$NON-NLS-2$
                proposalsProvider, word, Integer.valueOf(0), prefix);
            Object item = converted instanceof Optional<?> optional ? optional.orElse(null) : null;
            if (item == null)
                continue;
            item.getClass().getField("priority").setInt(item, word.priority); //$NON-NLS-1$
            proposals.add(item);
        }
        Class<?> type = localContext.getClass();
        type.getField("proposals").set(localContext, proposals); //$NON-NLS-1$
        type.getField("forced").setBoolean(localContext, false); //$NON-NLS-1$
        // Не contentAssist: с этим признаком сервер считает слова списка выбранными пунктами и
        // пишет продолжение для каждого («// Здесь используется Поле: …»). Без него слова —
        // справочный перечень доступных имён, как в штатном запросе Напарника при вводе.
        type.getField("contentAssist").setBoolean(localContext, false); //$NON-NLS-1$
        Object request = loadClass(vm, "com.e1c.edt.ai.assistent.model.CompletionRequest") //$NON-NLS-1$
            .getDeclaredConstructor().newInstance();
        request.getClass().getField("localContext").set(request, localContext); //$NON-NLS-1$
        return Optional.of(request);
    }

    private void onCompletion(int gen, Object vm, Object aiContext, Object source, Object completion,
        Display display)
    {
        if (completion == null)
            return;
        try
        {
            // Как штатный обработчик ответа: сервер сообщает, каких сведений о конфигурации
            // ему не хватило, и Напарник дополняет ими общий контекст следующих запросов.
            call(vm, "com.e1c.edt.ai.IGlobalContextManager", "update", //$NON-NLS-1$ //$NON-NLS-2$
                Global.getField(vm, "globalContextManager"), aiContext, completion, source); //$NON-NLS-1$
        }
        catch (Throwable ignored)
        {
        }
        Object text = Global.getField(completion, "text"); //$NON-NLS-1$
        if (!(text instanceof String piece) || piece.isEmpty() || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            if (gen != generation)
                return;
            full = normalize((full == null ? "" : full) + piece); //$NON-NLS-1$
            recompute(false);
        });
    }

    private void finishRequest(int gen, Display display)
    {
        finishRequest(gen, display, null);
    }

    /** Запрос не удался: строка остаётся в списке и показывает ошибку, её текст — в боковой подсказке. */
    private void failRequest(int gen, Display display, String what, Throwable cause)
    {
        StringBuilder text = new StringBuilder(what);
        Throwable t = cause;
        while (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
            t = t.getCause();
        for (int depth = 0; t != null && depth < 5; t = t.getCause(), depth++)
        {
            text.append('\n').append(t.getClass().getSimpleName());
            if (t.getMessage() != null && !t.getMessage().isBlank())
                text.append(": ").append(t.getMessage().strip()); //$NON-NLS-1$
        }
        finishRequest(gen, display, text.toString());
    }

    private void finishRequest(int gen, Display display, String errorText)
    {
        if (display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            if (gen != generation || completed)
                return;
            completed = true;
            lastFailure = errorText;
            if (errorText != null)
            {
                // Пришедшая до ошибки часть ответа не годится: она обрывается на полуслове.
                error = errorText;
                full = ""; //$NON-NLS-1$
            }
            if (full == null)
                full = ""; //$NON-NLS-1$
            cancelSource = null;
            if (sessionOpen)
                recompute(false);
        });
    }

    private static Object proxy(Class<?> type, InvocationHandler handler)
    {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (self, method, args) ->
        {
            if (method.getDeclaringClass() == Object.class)
            {
                return switch (method.getName())
                {
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(self)); //$NON-NLS-1$
                    case "equals" -> Boolean.valueOf(self == args[0]); //$NON-NLS-1$
                    default -> "NaparnikAssistRow." + type.getSimpleName(); //$NON-NLS-1$
                };
            }
            return handler.invoke(self, method, args);
        });
    }

    /** Класс Напарника: сначала загрузчиком бандла модели, затем бандла клиента сервера. */
    private static Class<?> loadClass(Object vm, String name) throws ClassNotFoundException
    {
        try
        {
            return vm.getClass().getClassLoader().loadClass(name);
        }
        catch (ClassNotFoundException e)
        {
            Object codeAssistant = Global.getField(vm, "codeAssistant"); //$NON-NLS-1$
            if (codeAssistant == null)
                throw e;
            return codeAssistant.getClass().getClassLoader().loadClass(name);
        }
    }

    /**
     * Вызов метода интерфейса Напарника. Через интерфейс, а не класс объекта: реализации бывают
     * лямбдами и непубличными классами.
     */
    private static Object call(Object vm, String interfaceName, String methodName, Object target,
        Object... args) throws Exception
    {
        if (target == null)
            throw new IllegalStateException("нет объекта " + interfaceName); //$NON-NLS-1$
        for (Method method : loadClass(vm, interfaceName).getMethods())
        {
            if (!method.getName().equals(methodName) || method.getParameterCount() != args.length)
                continue;
            method.setAccessible(true);
            return method.invoke(target, args);
        }
        throw new NoSuchMethodException(interfaceName + '.' + methodName);
    }

    // ---- клавиши и вставка ---------------------------------------------------

    private void onVerifyKey(VerifyEvent event)
    {
        if (!sessionOpen || viewModel == null || !event.doit
            || !ContentAssistPopupSync.isPopupVisible(assistant))
            return;
        int mods = event.stateMask & SWT.MODIFIER_MASK;
        boolean enter = event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR;
        if (mods == 0)
        {
            switch (event.keyCode)
            {
                case SWT.ARROW_UP, SWT.ARROW_DOWN, SWT.PAGE_UP, SWT.PAGE_DOWN, SWT.HOME, SWT.END ->
                    // Выделение сменится позже, в слушателе самого окна автодополнения.
                    event.display.asyncExec(() -> rowNavigated = sessionOpen && isRowSelected());
                default ->
                {
                }
            }
        }
        if (enter && mods == 0 && isRowSelected())
        {
            // Пока Напарник думает, Enter в его строке ничего не делает: штатная вставка
            // закрыла бы список, не вставив ничего.
            event.doit = false;
            if (!remaining.isBlank())
                accept(linePiece(remaining), true);
            return;
        }
        if (remaining.isBlank() || !isRowSelected() || isNaparnikHintShown())
            return;
        if (event.keyCode == SWT.TAB && mods == 0)
        {
            event.doit = false;
            String piece = wordPiece(remaining);
            String rest = remaining.substring(piece.length());
            accept(piece, false);
            if (!rest.isBlank())
                showInline(rest);
        }
        else if (enter && mods == SWT.CTRL)
        {
            event.doit = false;
            accept(remaining, false);
        }
    }

    /** Серая подсказка самого Напарника в тексте: её Tab и Ctrl+Enter не перехватываем. */
    private boolean isNaparnikHintShown()
    {
        try
        {
            Object shown = call(viewModel, "com.e1c.edt.ai.ui.IHintPainter", "getDisplayedHintText", //$NON-NLS-1$ //$NON-NLS-2$
                Global.getField(viewModel, "hintPainter")); //$NON-NLS-1$
            return shown instanceof String text && !text.isEmpty();
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * Ведущие пустые строки, первая непустая строка и, если предложение продолжается, перевод
     * строки с отступом следующей: каретка сразу встаёт туда, куда ляжет продолжение.
     */
    private static String linePiece(String text)
    {
        int end = 0;
        while (end < text.length())
        {
            int lineEnd = text.indexOf('\n', end);
            if (lineEnd < 0)
                return text;
            if (!text.substring(0, lineEnd).isBlank())
            {
                int next = lineEnd + 1;
                while (next < text.length() && (text.charAt(next) == ' ' || text.charAt(next) == '\t'))
                    next++;
                return text.substring(0, next);
            }
            end = lineEnd + 1;
        }
        return text;
    }

    /** Ведущие пробелы и очередное слово; знак препинания — отдельное слово. */
    private static String wordPiece(String text)
    {
        int end = 0;
        while (end < text.length() && Character.isWhitespace(text.charAt(end)))
            end++;
        if (end >= text.length())
            return text;
        if (!isWordChar(text.charAt(end)))
            return text.substring(0, end + 1);
        while (end < text.length() && isWordChar(text.charAt(end)))
            end++;
        return text.substring(0, end);
    }

    private static boolean isWordChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private void accept(String piece, boolean reopen)
    {
        String rest = remaining.substring(piece.length());
        ContentAssistPopupSync.hideProposalPopup(assistant);
        if (!insert(piece))
            return;
        if (!reopen || rest.isBlank())
            return;
        selectRowOnOpen = true;
        Display display = display();
        if (display != null)
        {
            display.asyncExec(() ->
            {
                if (!ContentAssistPopupSync.showPossibleCompletionsAsCommand(assistant))
                    selectRowOnOpen = false;
            });
        }
    }

    /**
     * Остаток предложения — серой подсказкой Напарника прямо в тексте. Повторяет то, что
     * Напарник делает сам, получив ответ сервера: сеанс подсказки с текстом и штатный показ.
     * Дальше остаток принимается его же клавишами.
     */
    private void showInline(String rest)
    {
        Object vm = viewModel;
        Display display = display();
        if (vm == null || display == null)
            return;
        // Свой сеанс окончен: остаток теперь ведёт Напарник.
        dropSuggestion();
        // Не в UI-потоке, как и у Напарника: контекст модуля и показ он сам переносит в UI.
        Thread thread = new Thread(() ->
        {
            try
            {
                Object source = loadClass(vm, "com.e1c.edt.ai.CancellationTokenSource") //$NON-NLS-1$
                    .getDeclaredConstructor().newInstance();
                Object context = Global.invoke(vm, "getAiContext", source); //$NON-NLS-1$
                Object aiContext = context instanceof Optional<?> optional ? optional.orElse(null) : null;
                if (aiContext == null)
                    return;
                Object completionContext = null;
                for (java.lang.reflect.Constructor<?> constructor
                    : loadClass(vm, "com.e1c.edt.ai.ui.CodeCompletionContext").getDeclaredConstructors()) //$NON-NLS-1$
                {
                    if (constructor.getParameterCount() != 4)
                        continue;
                    constructor.setAccessible(true);
                    completionContext = constructor.newInstance(Global.getField(vm, "codeCompletionContext"), //$NON-NLS-1$
                        aiContext, Global.getField(vm, "textWidget"), source); //$NON-NLS-1$
                    break;
                }
                if (completionContext == null)
                    return;
                Object context4 = completionContext;
                boolean[] singleWord = new boolean[1];
                display.syncExec(() -> singleWord[0] =
                    Boolean.TRUE.equals(Global.invoke(context4, "isSingleWordMode"))); //$NON-NLS-1$
                String sessionType = "com.e1c.edt.ai.ICodeCompletionSession"; //$NON-NLS-1$
                Object session = call(vm, "com.google.inject.Provider", "get", //$NON-NLS-1$ //$NON-NLS-2$
                    Global.getField(vm, "sessionProvider")); //$NON-NLS-1$
                // Число строк подсказки — по остатку, а не по настройке Напарника: остаток уже
                // получен и показан в списке, обрезать его незачем.
                int lines = rest.split("\n", -1).length; //$NON-NLS-1$
                session = call(vm, sessionType, "initiaize", session, completionContext, //$NON-NLS-1$
                    Global.getField(vm, "history"), Integer.valueOf(lines), Boolean.valueOf(singleWord[0])); //$NON-NLS-1$
                Object lock = Global.getField(vm, "lockObject"); //$NON-NLS-1$
                synchronized (lock != null ? lock : vm)
                {
                    Object last = Global.getField(vm, "lastSession"); //$NON-NLS-1$
                    if (last != null)
                    {
                        Object lastContext = call(vm, sessionType, "getContext", last); //$NON-NLS-1$
                        Global.invokeVoid(Global.invoke(lastContext, "getCancellationTokenSource"), "cancel"); //$NON-NLS-1$ //$NON-NLS-2$
                        call(vm, sessionType, "reset", last); //$NON-NLS-1$
                    }
                    if (!Global.setFieldForce(vm, "lastSession", session)) //$NON-NLS-1$
                        return;
                }
                // Место подсказки в тексте. Напарник задаёт его перед каждым запросом; без этого
                // шага подсказка получает текст, но рисовать её негде.
                Object painter = Global.getField(vm, "hintPainter"); //$NON-NLS-1$
                Object widget = Global.getField(vm, "textWidget"); //$NON-NLS-1$
                Object contextCaret = Global.invoke(aiContext, "getCaretOffset"); //$NON-NLS-1$
                Throwable[] pinError = new Throwable[1];
                display.syncExec(() ->
                {
                    try
                    {
                        if (!(widget instanceof StyledText styled) || styled.isDisposed())
                            throw new IllegalStateException("нет виджета редактора"); //$NON-NLS-1$
                        int pinnedAt = contextCaret instanceof Integer offset
                            ? offset.intValue() : styled.getCaretOffset();
                        String painterType = "com.e1c.edt.ai.ui.IHintPainter"; //$NON-NLS-1$
                        call(vm, painterType, "reset", painter); //$NON-NLS-1$
                        Global.invokeVoid(Global.getField(vm, "verticalRulerPainter"), "reset"); //$NON-NLS-1$ //$NON-NLS-2$
                        call(vm, painterType, "pinOffset", painter, styled, Integer.valueOf(pinnedAt), //$NON-NLS-1$
                            Boolean.TRUE, Boolean.valueOf(singleWord[0]));
                        call(vm, painterType, "setHintAt", painter, "", "", Integer.valueOf(0)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        styled.showSelection();
                        Global.invokeVoid(vm, "redraw"); //$NON-NLS-1$
                    }
                    catch (Throwable t)
                    {
                        pinError[0] = t;
                    }
                });
                if (pinError[0] != null)
                    return;
                Object text = null;
                for (java.lang.reflect.Constructor<?> constructor
                    : loadClass(vm, "com.e1c.edt.ai.Text").getConstructors()) //$NON-NLS-1$
                {
                    if (constructor.getParameterCount() == 2)
                    {
                        text = constructor.newInstance(rest, session);
                        break;
                    }
                }
                call(vm, "com.e1c.edt.ai.IHint", "append", call(vm, sessionType, "getHint", session), text); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                call(vm, sessionType, "complete", session); //$NON-NLS-1$
                java.lang.reflect.Constructor<?> statistics =
                    loadClass(vm, vm.getClass().getName() + "$ProcessingStatistics").getDeclaredConstructor(); //$NON-NLS-1$
                statistics.setAccessible(true);
                NaparnikManualModeHook.allowHintByUserAction();
                Global.invokeVoid(vm, "show", session, statistics.newInstance()); //$NON-NLS-1$
            }
            catch (Throwable ignored)
            {
                // Слово уже вставлено; без подсказки в тексте остаток просто не показан.
            }
        }, "Комфорт: подсказка Напарника в тексте"); //$NON-NLS-1$
        thread.setDaemon(true);
        thread.start();
    }

    /** Двойной щелчок по строке: окно уже закрыто штатной вставкой. */
    private void acceptLineAfterHide()
    {
        if (viewModel == null || remaining.isBlank())
            return;
        String piece = linePiece(remaining);
        String rest = remaining.substring(piece.length());
        if (!insert(piece) || rest.isBlank())
            return;
        selectRowOnOpen = true;
        Display display = display();
        if (display != null)
        {
            display.asyncExec(() ->
            {
                if (!ContentAssistPopupSync.showPossibleCompletionsAsCommand(assistant))
                    selectRowOnOpen = false;
            });
        }
    }

    private boolean insert(String piece)
    {
        IDocument document = viewer.getDocument();
        int caret = SmartContentAssistProcessor.resolveWidgetCaret(viewer);
        if (document == null || caret < 0 || piece.isEmpty())
            return false;
        String text = piece.replace("\n", TextUtilities.getDefaultLineDelimiter(document)); //$NON-NLS-1$
        applying = true;
        try
        {
            document.replace(caret, 0, text);
            viewer.setSelectedRange(caret + text.length(), 0);
            viewer.revealRange(caret + text.length(), 0);
            return true;
        }
        catch (Exception ignored)
        {
            return false;
        }
        finally
        {
            applying = false;
        }
    }

    private Display display()
    {
        StyledText widget = viewer.getTextWidget();
        return widget == null || widget.isDisposed() ? null : widget.getDisplay();
    }

    private static Image rowImage()
    {
        if (!rowImageResolved)
        {
            rowImageResolved = true;
            try
            {
                ImageDescriptor descriptor =
                    AbstractUIPlugin.imageDescriptorFromPlugin(NAPARNIK_UI_BUNDLE, "icons/obj16/ai.png"); //$NON-NLS-1$
                if (descriptor != null)
                    rowImage = descriptor.createImage();
            }
            catch (RuntimeException ignored)
            {
            }
        }
        return rowImage;
    }

    // ---- вложенные типы ------------------------------------------------------

    /**
     * Слово списка в том виде, в каком его понимает штатный преобразователь Напарника
     * ({@code IProposalsProvider.getProposal}). Сам пункт списка ему не отдаём: он запросил бы
     * у каждого полное описание, а это расчёт документации на сотни пунктов.
     */
    private static final class Word implements ICompletionProposal, ICompletionProposalExtension3
    {
        private final String display;
        private final String text;
        final int priority;

        Word(String display, String text, int priority)
        {
            this.display = display;
            this.text = text;
            this.priority = priority;
        }

        @Override
        public String getDisplayString()
        {
            return display;
        }

        @Override
        public CharSequence getPrefixCompletionText(IDocument document, int completionOffset)
        {
            return text;
        }

        @Override
        public int getPrefixCompletionStart(IDocument document, int completionOffset)
        {
            return completionOffset;
        }

        @Override
        public IInformationControlCreator getInformationControlCreator()
        {
            return null;
        }

        @Override
        public void apply(IDocument document)
        {
        }

        @Override
        public Point getSelection(IDocument document)
        {
            return null;
        }

        @Override
        public String getAdditionalProposalInfo()
        {
            return null;
        }

        @Override
        public Image getImage()
        {
            return null;
        }

        @Override
        public IContextInformation getContextInformation()
        {
            return null;
        }
    }

    /** Цвет текста строки Напарника: свой для светлой и тёмной темы. */
    private static final Styler ROW_STYLER = new Styler()
    {
        private Color light;
        private Color dark;

        @Override
        public void applyStyles(TextStyle textStyle)
        {
            Display display = Display.getCurrent();
            if (display == null)
                return;
            if (ThemeAwareColors.isDarkTheme())
            {
                if (dark == null)
                    dark = new Color(display, 210, 150, 255);
                textStyle.foreground = dark;
            }
            else
            {
                if (light == null)
                    light = new Color(display, 120, 0, 160);
                textStyle.foreground = light;
            }
        }
    };

    private final class RowProposal implements ICompletionProposal, ICompletionProposalExtension2,
        ICompletionProposalExtension3, ICompletionProposalExtension4, ICompletionProposalExtension6
    {
        String display = THINKING;
        String info;

        @Override
        public String getDisplayString()
        {
            return display;
        }

        @Override
        public StyledString getStyledDisplayString()
        {
            return new StyledString(display, ROW_STYLER);
        }

        @Override
        public String getAdditionalProposalInfo()
        {
            return info;
        }

        @Override
        public Image getImage()
        {
            return rowImage();
        }

        @Override
        public void apply(ITextViewer textViewer, char trigger, int stateMask, int offset)
        {
            acceptLineAfterHide();
        }

        @Override
        public void apply(IDocument document)
        {
        }

        /** Фильтр списка строку не убирает. */
        @Override
        public boolean validate(IDocument document, int offset, DocumentEvent event)
        {
            return true;
        }

        @Override
        public void selected(ITextViewer textViewer, boolean smartToggle)
        {
        }

        @Override
        public void unselected(ITextViewer textViewer)
        {
        }

        @Override
        public IInformationControlCreator getInformationControlCreator()
        {
            return CodeInformationControl::new;
        }

        /** Не {@code null}: слушатель списка у Напарника разыменовывает результат без проверки. */
        @Override
        public CharSequence getPrefixCompletionText(IDocument document, int completionOffset)
        {
            return ""; //$NON-NLS-1$
        }

        @Override
        public int getPrefixCompletionStart(IDocument document, int completionOffset)
        {
            return completionOffset;
        }

        @Override
        public boolean isAutoInsertable()
        {
            return false;
        }

        @Override
        public Point getSelection(IDocument document)
        {
            return null;
        }

        @Override
        public IContextInformation getContextInformation()
        {
            return null;
        }
    }

    /** Боковая подсказка: текст предложения шрифтом редактора с раскраской встроенного языка. */
    private static final class CodeInformationControl extends AbstractInformationControl
    {
        private static final Set<String> KEYWORDS = Set.of(
            "если", "тогда", "иначеесли", "иначе", "конецесли", "для", "каждого", "из", "по", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
            "цикл", "конеццикла", "пока", "процедура", "конецпроцедуры", "функция", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "конецфункции", "перем", "возврат", "продолжить", "прервать", "попытка", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "исключение", "конецпопытки", "вызватьисключение", "новый", "и", "или", "не", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "истина", "ложь", "неопределено", "null", "знач", "экспорт", "перейти", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "выполнить", "асинх", "ждать", "добавитьобработчик", "удалитьобработчик", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "if", "then", "elsif", "else", "endif", "for", "each", "in", "to", "do", "enddo", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$ //$NON-NLS-11$
            "while", "procedure", "endprocedure", "function", "endfunction", "var", "return", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "continue", "break", "try", "except", "endtry", "raise", "new", "and", "or", "not", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
            "true", "false", "undefined", "val", "export", "goto", "execute", "async", "await", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
            "addhandler", "removehandler"); //$NON-NLS-1$ //$NON-NLS-2$

        private StyledText text;
        private final List<Color> colors = new ArrayList<>();
        private Color keyword;
        private Color comment;
        private Color string;
        private Color number;
        private Color directive;

        CodeInformationControl(Shell parent)
        {
            super(parent, false);
            create();
            // create() раздаёт всем вложенным контролам шрифт диалогов.
            text.setFont(JFaceResources.getTextFont());
        }

        @Override
        protected void createContent(Composite parent)
        {
            parent.setLayout(new FillLayout());
            text = new StyledText(parent, SWT.MULTI | SWT.READ_ONLY | SWT.H_SCROLL | SWT.V_SCROLL);
            text.setFont(JFaceResources.getTextFont());
            text.setForeground(parent.getForeground());
            text.setBackground(parent.getBackground());
            text.setMargins(4, 2, 4, 2);
            keyword = color(200, 0, 0, 255, 120, 120);
            comment = color(0, 128, 0, 110, 200, 110);
            string = color(0, 90, 170, 120, 190, 255);
            number = color(120, 0, 160, 210, 150, 255);
            directive = color(150, 80, 0, 230, 170, 90);
        }

        @Override
        public void setForegroundColor(Color foreground)
        {
            super.setForegroundColor(foreground);
            text.setForeground(foreground);
        }

        @Override
        public void setBackgroundColor(Color background)
        {
            super.setBackgroundColor(background);
            text.setBackground(background);
        }

        @Override
        public void setInformation(String information)
        {
            String code = information == null ? "" : information; //$NON-NLS-1$
            boolean plain = !code.isEmpty() && code.charAt(0) == PLAIN_INFO_MARK;
            if (plain)
                code = code.substring(1);
            text.setText(code);
            text.setWordWrap(plain);
            text.setStyleRanges(plain ? new StyleRange[0] : styleRanges(code));
        }

        @Override
        public boolean hasContents()
        {
            return text != null && text.getCharCount() > 0;
        }

        @Override
        public Point computeSizeHint()
        {
            Point size = text.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
            Point limit = getSizeConstraints();
            if (limit != null)
            {
                size.x = Math.min(size.x, limit.x);
                size.y = Math.min(size.y, limit.y);
            }
            return size;
        }

        @Override
        public void dispose()
        {
            for (Color color : colors)
                color.dispose();
            colors.clear();
            super.dispose();
        }

        private Color color(int lightR, int lightG, int lightB, int darkR, int darkG, int darkB)
        {
            Color color = ThemeAwareColors.isDarkTheme()
                ? new Color(text.getDisplay(), darkR, darkG, darkB)
                : new Color(text.getDisplay(), lightR, lightG, lightB);
            colors.add(color);
            return color;
        }

        private StyleRange[] styleRanges(String code)
        {
            List<StyleRange> ranges = new ArrayList<>();
            int length = code.length();
            int i = 0;
            while (i < length)
            {
                char c = code.charAt(i);
                int start = i;
                if (c == '/' && i + 1 < length && code.charAt(i + 1) == '/')
                {
                    i = lineEnd(code, i);
                    ranges.add(new StyleRange(start, i - start, comment, null));
                }
                else if (c == '"' || (c == '|' && isLineStart(code, i)))
                {
                    i++;
                    while (i < length && code.charAt(i) != '\n')
                    {
                        if (code.charAt(i++) != '"')
                            continue;
                        if (i < length && code.charAt(i) == '"')
                            i++;
                        else
                            break;
                    }
                    ranges.add(new StyleRange(start, i - start, string, null));
                }
                else if (c == '\'')
                {
                    i++;
                    while (i < length && code.charAt(i) != '\'' && code.charAt(i) != '\n')
                        i++;
                    if (i < length && code.charAt(i) == '\'')
                        i++;
                    ranges.add(new StyleRange(start, i - start, number, null));
                }
                else if ((c == '#' || c == '&') && isLineStart(code, i))
                {
                    i = lineEnd(code, i);
                    ranges.add(new StyleRange(start, i - start, directive, null));
                }
                else if (Character.isDigit(c))
                {
                    while (i < length && (Character.isDigit(code.charAt(i)) || code.charAt(i) == '.'))
                        i++;
                    ranges.add(new StyleRange(start, i - start, number, null));
                }
                else if (isWordChar(c))
                {
                    while (i < length && isWordChar(code.charAt(i)))
                        i++;
                    // Слово после точки — имя свойства или метода, даже если совпало с ключевым.
                    boolean member = start > 0 && code.charAt(start - 1) == '.';
                    if (!member && KEYWORDS.contains(code.substring(start, i).toLowerCase(Locale.ROOT)))
                        ranges.add(new StyleRange(start, i - start, keyword, null));
                }
                else
                    i++;
            }
            return ranges.toArray(new StyleRange[0]);
        }

        private static int lineEnd(String code, int from)
        {
            int end = code.indexOf('\n', from);
            return end < 0 ? code.length() : end;
        }

        private static boolean isLineStart(String code, int offset)
        {
            for (int i = offset - 1; i >= 0; i--)
            {
                char c = code.charAt(i);
                if (c == '\n')
                    return true;
                if (!Character.isWhitespace(c))
                    return false;
            }
            return true;
        }
    }
}
