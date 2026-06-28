package tormozit;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.swt.widgets.Display;

/**
 * COM-цепочка ИР для обогащения списка автодополнения (порт RDT {@code ПриПолученииДанныхТ9}).
 */
public final class IrBslCompletionSupport
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONTEXT_VARIANT_SEPARATOR = "$"; //$NON-NLS-1$

    /** Результат {@code ОписаниеТекущегоСловаАвтодополнения} (RDT {@code ПриАктивизацииСтрокиТ9}). */
    public static final class ActivationDescription
    {
        public final String type;
        public final String description;
        public final boolean rawHtml;

        ActivationDescription(String type, String description, boolean rawHtml)
        {
            this.type = type != null ? type : ""; //$NON-NLS-1$
            this.description = description != null ? description : ""; //$NON-NLS-1$
            this.rawHtml = rawHtml;
        }
    }

    static String buildDisplayString(String word, String type)
    {
        if (type == null || type.isEmpty())
            return word != null ? word : ""; //$NON-NLS-1$
        return (word != null ? word : "") + " : " + type; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Начальная подпись ИР-строки в списке (тип из JSON, часто с {@code ??}). */
    static String buildInitialListDisplay(
        String word, boolean method, String listTypeLabel, String parentContextType)
    {
        String name = formatListEntryName(word, method);
        if (listTypeLabel != null && !listTypeLabel.isEmpty())
            return appendParentContext(name + " : " + listTypeLabel, parentContextType); //$NON-NLS-1$
        if (parentContextType != null && !parentContextType.isEmpty())
            return name + " ~ " + parentContextType; //$NON-NLS-1$
        return name;
    }

    /**
     * Подпись после {@code ОписаниеТекущегоСловаАвтодополнения}: весь тип из JSON заменяется
     * рассчитанным {@code <Тип>} (как EDT {@code Имя : <Тип> ~ Родитель}).
     */
    static String buildActivatedListDisplay(
        String word, boolean method, String listTypeLabel, String calculatedType,
        String parentContextType)
    {
        if (calculatedType == null)
            return buildInitialListDisplay(word, method, listTypeLabel, parentContextType);
        String name = formatListEntryName(word, method);
        String calc = wrapAngleType(calculatedType);
        return appendParentContext(name + " : " + calc, parentContextType); //$NON-NLS-1$
    }

    static String formatListEntryName(String word, boolean method)
    {
        if (word == null || word.isEmpty())
            return ""; //$NON-NLS-1$
        if (method && !word.endsWith("()")) //$NON-NLS-1$
            return word + "()"; //$NON-NLS-1$
        return word;
    }

    static String wrapAngleType(String type)
    {
        if (type == null || type.isEmpty())
            return ""; //$NON-NLS-1$
        String trimmed = type.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">")) //$NON-NLS-1$ //$NON-NLS-2$
            return trimmed;
        return "<" + trimmed + ">"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String appendParentContext(String base, String parentContextType)
    {
        if (base == null)
            return ""; //$NON-NLS-1$
        if (parentContextType == null || parentContextType.isEmpty() || base.contains(" ~ ")) //$NON-NLS-1$
            return base;
        return base + " ~ " + parentContextType; //$NON-NLS-1$
    }

    /** HTML боковой подсказки из {@code Описание} активации строки assist. */
    static String formatActivationHtml(String description, boolean rawHtml)
    {
        if (description == null || description.isBlank())
            return null;
        if (rawHtml || description.charAt(0) == '<')
            return IrBslHoverHtml.mergeHtml("", description); //$NON-NLS-1$
        return "<pre>" + escapeActivationHtml(description) + "</pre>"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String escapeActivationHtml(String text)
    {
        return text
            .replace("&", "&amp;") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("<", "&lt;") //$NON-NLS-1$ //$NON-NLS-2$
            .replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Порт RDT {@code ПриАктивизацииСтрокиТ9}: динамический тип и описание слова.
     * Только COM-поток {@link IRSession#executor}.
     */
    static ActivationDescription fetchWordActivationDescription(
        IRSession session, String wordValue, boolean isMethod, String dictionaryKey)
    {
        if (session == null || wordValue == null || wordValue.isEmpty())
            return null;
        long started = System.currentTimeMillis();
        IrBslExpressionHtmlSupport.ensureCodeEditor(session);
        Object codeEditor = session.codeEditor;
        Path cancelFile = null;
        try
        {
            cancelFile = IRSession.setEvaluationCancellationFile(session, codeEditor);
            String dict = dictionaryKey != null ? dictionaryKey : ""; //$NON-NLS-1$
            // Функция ОписаниеТекущегоСловаАвтодополнения(Знач Слово, Знач ЛиМетод = Неопределено, Знач КлючНабораСлов = "", Знач ЛиОтдельноеОписаниеАктивно = Истина, Знач ТолькоДляАвтооткрытия = Ложь, Знач ЛиФорматХТМЛ = Ложь) Экспорт
            Object raw = session.invokeCodeEditorQuiet(
                "ОписаниеТекущегоСловаАвтодополнения", //$NON-NLS-1$
                wordValue, isMethod, dict, true, false, true);
            Object comResult = ComBridge.unwrapComResult(raw);
            if (comResult == null)
            {
                return null;
            }
            String type = readActivationComField(comResult, "Тип"); //$NON-NLS-1$
            String description = readActivationComField(comResult, "Описание"); //$NON-NLS-1$
            if ((type == null) && (description == null))
                return null;
            boolean rawHtml = description != null && !description.isEmpty()
                && description.charAt(0) == '<';
            IrCompletionDebug.timing(
                "ПриАктивизацииСтрокиТ9." + wordValue, started); //$NON-NLS-1$
            return new ActivationDescription(type, description, rawHtml);
        }
        catch (Exception e)
        {
            String msg = e.getMessage() != null ? e.getMessage() : ""; //$NON-NLS-1$
            if (msg.contains("отмены") || msg.contains("cancel")) //$NON-NLS-1$ //$NON-NLS-2$
                IrCompletionDebug.step("activation", "cancelled word=" + wordValue); //$NON-NLS-1$ //$NON-NLS-2$
            else
                IrCompletionDebug.problem("activation word=" + wordValue + ": " + msg); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        finally
        {
            IRSession.clearEvaluationCancellationFile(session, codeEditor, cancelFile);
        }
    }

    /**
     * RDT: {@code ОписаниеКом.Тип} / {@code ОписаниеКом.Описание} — свойства dispatch,
     * не {@code Свойство()} структуры.
     */
    private static String readActivationComField(Object comObject, String fieldName)
    {
        if (comObject == null)
            return ""; //$NON-NLS-1$
        try
        {
            String direct = ComBridge.comFieldAsString(ComBridge.getProperty(comObject, fieldName));
            if (direct != null && !direct.isEmpty())
                return direct;
        }
        catch (RuntimeException ignored)
        {
            //
        }
        return ""; //$NON-NLS-1$
    }

    private IrBslCompletionSupport() {}

    /** Результат подготовки контекста assist / side-hint. */
    public static final class Snapshot
    {
        public final int caret;
        public final String contextType;
        public final ICompletionProposal[] proposals;
        public final int wordsTransferred;
        public final int wordsDisplayed;
        /** {@code ЗаполнитьТаблицуСлов} вернул {@code Истина} (порт gate автооткрытия ИР). */
        public final boolean autoOpenSuggested;

        Snapshot(int caret, String contextType, ICompletionProposal[] proposals,
            int wordsTransferred, int wordsDisplayed)
        {
            this(caret, contextType, proposals, wordsTransferred, wordsDisplayed, false);
        }

        Snapshot(int caret, String contextType, ICompletionProposal[] proposals,
            int wordsTransferred, int wordsDisplayed, boolean autoOpenSuggested)
        {
            this.caret = caret;
            this.contextType = contextType != null ? contextType : ""; //$NON-NLS-1$
            this.proposals = proposals != null ? proposals : new ICompletionProposal[0];
            this.wordsTransferred = wordsTransferred;
            this.wordsDisplayed = wordsDisplayed;
            this.autoOpenSuggested = autoOpenSuggested;
        }
    }

    /** Строка таблицы слов ИР до конвертации в proposal. */
    static final class WordEntry
    {
        final String word;
        final String type;
        final boolean method;
        final boolean returnsValue;
        final boolean exact;
        final int priority;
        final String templateText;
        final String filterName;
        final boolean replaceParentOnInsert;
        final String dictionaryKey;

        WordEntry(String word, String type, boolean method, boolean returnsValue, boolean exact,
            int priority, String templateText, String filterName, boolean replaceParentOnInsert,
            String dictionaryKey)
        {
            this.word = word;
            this.type = type;
            this.method = method;
            this.returnsValue = returnsValue;
            this.exact = exact;
            this.priority = priority;
            this.templateText = templateText;
            this.filterName = filterName;
            this.replaceParentOnInsert = replaceParentOnInsert;
            this.dictionaryKey = dictionaryKey != null ? dictionaryKey : ""; //$NON-NLS-1$
        }

        IrCompletionProposal toProposal(String parentContextType)
        {
            String display = IrBslCompletionSupport.buildInitialListDisplay(
                word, method, type, parentContextType);
            return new IrCompletionProposal(display, filterName, templateText, method, priority,
                word, dictionaryKey, type, parentContextType, returnsValue);
        }
    }

    /**
     * Sync на UI; COM на {@code session.executor}; callback на UI после цепочки RDT.
     */
    public static void prepareAssistContextAsync(
        IRSession session, BslXtextEditor editor, int caretOffset, boolean autoInvoke,
        Consumer<Snapshot> onReady)
    {
        prepareAssistContextAsync(session, editor, caretOffset, autoInvoke, false, onReady);
    }

    public static void prepareAssistContextAsync(
        IRSession session, BslXtextEditor editor, int caretOffset, boolean autoInvoke,
        boolean closePreviousCommand, Consumer<Snapshot> onReady)
    {
        if (session == null || editor == null || session.executor == null
            || session.executor.isShutdown())
            return;
        IRSession.CodeEditorSyncPayload payload;
        try
        {
            payload = session.prepareCodeEditorSyncForAssist(editor, caretOffset);
        }
        catch (Exception e)
        {
            IrCompletionDebug.problem("sync: " + e.getMessage()); //$NON-NLS-1$
            return;
        }
        if (payload == null)
            return;
        final IRSession.CodeEditorSyncPayload syncPayload = payload;
        session.executor.submit(() -> {
            Snapshot snapshot = null;
            try
            {
                if (closePreviousCommand)
                    finishAssistCommandProcessing(session);
                snapshot = fetchCompletionSnapshot(session, syncPayload, autoInvoke);
            }
            catch (Exception e)
            {
                IrCompletionDebug.problem("fetch: " + e.getMessage()); //$NON-NLS-1$
            }
            final Snapshot ready = snapshot;
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed() && onReady != null)
                display.asyncExec(() -> onReady.accept(ready));
        });
    }

    /**
     * Закрывает контекст assist в ИР (RDT: КончитьОбработкуКоманды после сессии или перед новым sync).
     * Только COM-поток {@link IRSession#executor}.
     */
    static void finishAssistCommandProcessing(IRSession session)
    {
        if (session == null)
            return;
        try
        {
            IrBslExpressionHtmlSupport.ensureCodeEditor(session);
            session.invokeCodeEditorQuiet("КончитьОбработкуКоманды"); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            // старые версии ИР
        }
    }

    static Snapshot fetchCompletionSnapshot(
        IRSession session, IRSession.CodeEditorSyncPayload payload, boolean autoInvoke)
    {
        session.applyPreparedCodeEditorSync(payload);
        long started = System.currentTimeMillis();
//         Процедура РазобратьТекущийКонтекст(Знач ВзятьЛевоеОтРавенства = Ложь, выхЕстьТочкаСправа = Ложь, Знач КакВызовМетода = Неопределено, Знач НомерСтроки = 0, Знач НомерКолонки = 0,
//          Знач ПереходитьВоВложенныйКонтекст = Ложь, Знач ПозицияВТексте = 0, Знач ТребоватьРазбор = Ложь) Экспорт 
        Object typesTable = session.invokeCodeEditorQuiet(
            "ТаблицаТиповТекущегоВыражения", false, false, true, true); //$NON-NLS-1$
        IrCompletionDebug.timing("Расчет типов контекста", started); //$NON-NLS-1$

        started = System.currentTimeMillis();
//        Функция ЗаполнитьТаблицуСлов(ТаблицаТиповКонтекста = Неопределено, Знач ПрименитьОжидаемыйТип = Истина, выхЕстьЛучшееСлово = Неопределено, Знач РазрешитьОткрытиеОкон = Истина,
//            Знач Сортировать = Истина, Знач ДобавлятьНизкоВероятные = Ложь, Знач ОтделятьБольшиеНаборыСлов = Ложь, Знач СловоФильтр = Неопределено, Знач ЗапретГлобальногоКонтекста = Ложь,
//            Знач ТипСловаФильтр = Неопределено, ИнлайнРежимДоступен = Ложь) Экспорт
        boolean autoOpenSuggested = ComBridge.toBoolean(session.invokeCodeEditorQuiet(
            "ЗаполнитьТаблицуСлов", typesTable, true, false, false, false, !autoInvoke, true)); //$NON-NLS-1$
        IrCompletionDebug.timing("Заполнение слов контекста", started); //$NON-NLS-1$
        // #region agent log
        ContentAssistDebug.debugModeLog("H77", "fetchCompletionSnapshot", "irFill", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"fillOk\":" + autoOpenSuggested + ",\"autoInvoke\":" + autoInvoke //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        if (!autoOpenSuggested)
            return null;

        String contextType = ComBridge.toString(
            session.invokeCodeEditorQuiet("ИмяТипаКонтекста")); //$NON-NLS-1$

        started = System.currentTimeMillis();
        int transferred = 0;
        int displayed = 0;
        List<ICompletionProposal> merged = new ArrayList<>();
        Set<String> seenFilters = new LinkedHashSet<>();

        String contextSeparator = resolveContextSeparator(session);
        for (String setName : collectWordSetNames(session))
        {
            List<WordEntry> cached = session.getCachedWordSet(setName);
            if (cached == null)
            {
                List<WordEntry> fetched = fetchWordSet(session, setName, contextSeparator, setName);
                session.putCachedWordSet(setName, fetched);
                transferred += fetched.size();
                appendUniqueProposals(merged, seenFilters, fetched, contextType);
                displayed += fetched.size();
            }
            else
            {
                IrCompletionDebug.step("cache", "hit set=\"" + setName + "\" size=" + cached.size()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                appendUniqueProposals(merged, seenFilters, cached, contextType);
                displayed += cached.size();
            }
        }

        List<WordEntry> mainTable = fetchWordSet(session, "", contextSeparator, ""); //$NON-NLS-1$
        transferred += mainTable.size();
        displayed += mainTable.size();
        appendUniqueProposals(merged, seenFilters, mainTable, contextType);

        IrCompletionDebug.timing("Передача слов", started); //$NON-NLS-1$
        IrCompletionDebug.log("Выражение contextType=" + contextType //$NON-NLS-1$
            + " слов передано " + transferred + ", выведено " + displayed); //$NON-NLS-1$ //$NON-NLS-2$

        // RDT ПриПолученииДанныхТ9: для документа КончитьОбработкуКоманды не вызывают —
        // контекст остаётся открытым для ОписаниеТекущегоСловаАвтодополнения при активации строки.
        session.pumpUserMessagesToUi();

        return new Snapshot(-1, contextType,
            merged.toArray(new ICompletionProposal[merged.size()]), transferred, displayed,
            autoOpenSuggested);
    }

    private static void appendUniqueProposals(
        List<ICompletionProposal> target, Set<String> seenFilters, List<WordEntry> words,
        String parentContextType)
    {
        for (WordEntry entry : words)
        {
            String key = normalizeFilterKey(entry.filterName);
            if (key.isEmpty() || seenFilters.contains(key))
                continue;
            seenFilters.add(key);
            target.add(entry.toProposal(parentContextType));
        }
    }

    static String normalizeFilterKey(String filter)
    {
        if (filter == null)
            return ""; //$NON-NLS-1$
        int paren = filter.indexOf('(');
        String base = paren >= 0 ? filter.substring(0, paren).trim() : filter.trim();
        int colon = base.indexOf(':');
        return colon >= 0 ? base.substring(0, colon).trim() : base;
    }

    private static String resolveContextSeparator(IRSession session)
    {
        try
        {
            String sep = ComBridge.toString(
                session.invokeCodeEditorQuiet("РазделительВариантаКонтекста")); //$NON-NLS-1$
            if (sep != null && !sep.isEmpty())
                return sep;
        }
        catch (Exception ignored) {}
        return CONTEXT_VARIANT_SEPARATOR;
    }

    private static List<String> collectWordSetNames(IRSession session)
    {
        List<String> names = new ArrayList<>();
        try
        {
            Object wordSets = ComBridge.getProperty(session.codeEditor, "мНаборыСлов"); //$NON-NLS-1$
            if (wordSets == null)
                return names;
            for (Object item : ComBridge.iterateComCollection(wordSets))
            {
                String key = ComBridge.structureField(item, "Ключ"); //$NON-NLS-1$
                if (key == null || key.isEmpty())
                    key = ComBridge.toString(ComBridge.getProperty(item, "Ключ")).strip(); //$NON-NLS-1$
                if (!key.isEmpty())
                    names.add(key);
            }
        }
        catch (Exception e)
        {
            IrCompletionDebug.problem("мНаборыСлов: " + e.getMessage()); //$NON-NLS-1$
        }
        return names;
    }

    private static List<WordEntry> fetchWordSet(
        IRSession session, String setName, String contextSeparator, String dictionaryKey)
    {
        long started = System.currentTimeMillis();
        String json = ComBridge.toString(
            session.invokeCodeEditorQuiet("ТаблицаСловВJSON", setName)); //$NON-NLS-1$
        IrCompletionDebug.timing("- Передача набора слов \"" + setName + "\"", started); //$NON-NLS-1$ //$NON-NLS-2$

        started = System.currentTimeMillis();
        List<WordEntry> result = parseWordsJson(json, contextSeparator, dictionaryKey);
        IrCompletionDebug.timing("- Конвертация набора слов \"" + setName + "\"", started); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    static List<WordEntry> parseWordsJson(
        String json, String contextSeparator, String dictionaryKey)
    {
        List<WordEntry> result = new ArrayList<>();
        if (json == null || json.isBlank())
            return result;
        try
        {
            JsonNode root = JSON.readTree(json);
            if (!root.isArray())
                return result;
            boolean hasPriority = root.size() > 0 && root.get(0).has("Приоритет"); //$NON-NLS-1$
            // #region agent log
            ContentAssistDebug.sessionLog("H4", "parseWordsJson", "hasPriority", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"rows\":" + root.size() + ",\"hasPriority\":" + hasPriority //$NON-NLS-1$
                    + ",\"dict\":\"" + (dictionaryKey != null ? dictionaryKey : "") + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // #endregion
            String dictKey = dictionaryKey != null ? dictionaryKey : ""; //$NON-NLS-1$
            for (JsonNode row : root)
            {
                WordEntry entry = parseWordRow(row, contextSeparator, hasPriority, dictKey);
                if (entry != null)
                    result.add(entry);
            }
        }
        catch (Exception e)
        {
            IrCompletionDebug.problem("parse JSON: " + e.getMessage()); //$NON-NLS-1$
        }
        return result;
    }

    private static WordEntry parseWordRow(
        JsonNode row, String contextSeparator, boolean hasPriority, String dictionaryKey)
    {
        if (row == null || !row.has("Слово")) //$NON-NLS-1$
            return null;
        String word = row.get("Слово").asText(""); //$NON-NLS-1$ //$NON-NLS-2$
        if (word.isEmpty())
            return null;
        boolean method = row.path("ЛиМетод").asBoolean(false); //$NON-NLS-1$
        boolean returnsValue = row.path("ЛиРез").asBoolean(false); //$NON-NLS-1$
        boolean exact = row.path("ЛиТочный").asBoolean(true); //$NON-NLS-1$
        String type = row.path("Тип").asText(""); //$NON-NLS-1$ //$NON-NLS-2$
        int priority = hasPriority ? row.path("Приоритет").asInt(0) : 0;

        boolean replaceParent = contextSeparator != null
            && !contextSeparator.isEmpty()
            && word.contains(contextSeparator);
        String filter = word;
        String template = null;
        if (replaceParent)
        {
            int sep = word.indexOf(contextSeparator);
            String left = word.substring(0, sep);
            String right = word.substring(sep + contextSeparator.length());
            filter = left;
            template = right + "." + left; //$NON-NLS-1$
            if (method)
                template = template + "(<!>)"; //$NON-NLS-1$
        }
        else
        {
            template = buildTemplate(word, method);
        }
        return new WordEntry(word, type, method, returnsValue, exact, priority,
            template, filter, replaceParent, dictionaryKey);
    }

    private static String buildTemplate(String word, boolean method)
    {
        if ("Новый".equals(word)) //$NON-NLS-1$
            return word + " <?>"; //$NON-NLS-1$
        if ("Перейти".equals(word)) //$NON-NLS-1$
            return word + " ~<?>"; //$NON-NLS-1$
        if ("КонецЕсли".equals(word) || "КонецЦикла".equals(word) //$NON-NLS-1$ //$NON-NLS-2$
            || "КонецПопытки".equals(word)) //$NON-NLS-1$
            return word + ";"; //$NON-NLS-1$
        if (method)
        {
            if ("Тип".equals(word)) //$NON-NLS-1$
                return word + "(\"<?>\")"; //$NON-NLS-1$
            if ("НСтр".equals(word)) //$NON-NLS-1$
                return word + "(\"ru='<!>'\")"; //$NON-NLS-1$
        }
        return null;
    }
}
