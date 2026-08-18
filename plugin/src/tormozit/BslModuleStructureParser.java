package tormozit;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.SyntaxErrorMessage;
import org.eclipse.xtext.parser.IParseResult;

import com._1c.g5.v8.dt.bsl.compare.BslCompareUtils;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com.google.inject.Injector;

/**
 * Разбор текста BSL-модуля в дерево секций (методы/процедуры, препроцессорные регионы,
 * объявления) для панели «Структура» попарного {@code CompareDialog}
 * (см. {@link CompareDialogCurrentLinesHook}). Переиспользует штатный разбор EDT —
 * {@link BslCompareUtils#parseBslModuleContent(InputStream)} (публичный) и internal
 * {@code com._1c.g5.v8.dt.internal.bsl.compare.contributor.BslModuleSectionCompareUtils}
 * (тот же метод, что строит дерево для «Сравнивать модули с учётом структуры» в 3-way).
 *
 * <p>Возвращаемый тип {@code IParseResult} (org.eclipse.xtext.parser, не экспортирован
 * бандлом Xtext) и сам {@code BslModuleSectionCompareUtils} (internal-пакет) —
 * недоступны на этапе компиляции, вызываются исключительно через рефлексию.
 * {@link IComparisonSession} нужен методу только как источник опционального
 * {@code getIntegrationContext()} (обогащение подписи метода) и разыменовывается
 * без null-проверки — передаём безопасную {@link Proxy}-заглушку (тот же приём,
 * что {@code Global.installLightControlListener}).
 */
final class BslModuleStructureParser
{
    private static final String SECTION_COMPARE_UTILS =
        "com._1c.g5.v8.dt.internal.bsl.compare.contributor.BslModuleSectionCompareUtils"; //$NON-NLS-1$
    private static final String BSL_ACTIVATOR = "com._1c.g5.v8.dt.bsl.ui.internal.BslActivator"; //$NON-NLS-1$
    private static final String BSL_LANGUAGE_ID = "com._1c.g5.v8.dt.bsl.Bsl"; //$NON-NLS-1$
    private static final String GRAMMAR_ACCESS_CLASS = "com._1c.g5.v8.dt.bsl.services.BslGrammarAccess"; //$NON-NLS-1$
    private static final String LOCATION_PROVIDER_CLASS = "org.eclipse.xtext.resource.ILocationInFileProvider"; //$NON-NLS-1$
    private static final String MODULE_CLASS = "com._1c.g5.v8.dt.bsl.model.Module"; //$NON-NLS-1$
    private static final String DOCUMENT_CLASS = "org.eclipse.jface.text.IDocument"; //$NON-NLS-1$
    private static final String DOCUMENT_IMPL_CLASS = "org.eclipse.jface.text.Document"; //$NON-NLS-1$

    private BslModuleStructureParser()
    {
    }

    /** Узел дерева структуры модуля — собственная упрощённая модель, без зависимости от internal-типов 1С. */
    static final class SectionNode
    {
        final String label;
        final int offset;
        final int length;
        final List<SectionNode> children = new ArrayList<>();

        SectionNode(String label, int offset, int length)
        {
            this.label = label;
            this.offset = offset;
            this.length = length;
        }
    }

    /** Позиция и текст первой синтаксической ошибки (документ может содержать несколько — берём первую). */
    private static final class SyntaxErrorInfo
    {
        final String message;
        final int offset;

        SyntaxErrorInfo(String message, int offset)
        {
            this.message = message;
            this.offset = offset;
        }
    }

    /**
     * Результат разбора одной стороны. При синтаксической ошибке {@code root} — НЕ {@code null}:
     * это та часть структуры, которую всё же удалось построить (у Xtext/ANTLR есть
     * error-recovery — частичный AST обычно доступен), плюс {@code syntaxError*} с местом
     * ошибки — {@link BslModuleStructureDiff} добавляет по ним отдельный узел-ошибку в дерево.
     * {@code syntaxError*} заполняются только для ошибок, оборвавших разбор
     * ({@link BslAstCompleteness}): при сработавшем восстановлении структура полная, и
     * сообщать пользователю не о чем.
     * {@code root == null} — только при полном фиаско (см. {@link #fatalError}), тогда дерево
     * вообще не строится (см. {@code BslModuleStructureDiff.diff}).
     */
    static final class ParseOutcome
    {
        final SectionNode root;
        final String fatalError;
        final String syntaxErrorMessage;
        final int syntaxErrorOffset;

        private ParseOutcome(SectionNode root, String fatalError, String syntaxErrorMessage, int syntaxErrorOffset)
        {
            this.root = root;
            this.fatalError = fatalError;
            this.syntaxErrorMessage = syntaxErrorMessage;
            this.syntaxErrorOffset = syntaxErrorOffset;
        }

        static ParseOutcome ok(SectionNode root, SyntaxErrorInfo syntaxError)
        {
            return new ParseOutcome(root, null,
                syntaxError != null ? syntaxError.message : null, syntaxError != null ? syntaxError.offset : -1);
        }

        static ParseOutcome fatal(String message)
        {
            return new ParseOutcome(null, message != null && !message.isBlank() ? message : "Ошибка разбора", null, -1); //$NON-NLS-1$
        }
    }

    static ParseOutcome parse(String text)
    {
        /*
         * Пустая строка — валидный пустой BSL-модуль (файл только появился/удалён в этой
         * ревизии), а не ошибка синтаксиса — не отсекаем её сюда, парсер штатно обработает
         * (ноль секций), а diff (см. BslModuleStructureDiff) корректно покажет все секции
         * другой стороны как добавленные/удалённые.
         */
        if (text == null)
            return ParseOutcome.fatal("Нет текста"); //$NON-NLS-1$
        try
        {
            Object parseResult = parseBslModuleContent(text);
            if (parseResult == null)
                return ParseOutcome.fatal("Не удалось разобрать модуль"); //$NON-NLS-1$

            /*
             * Показываем только те ошибки, после которых восстановление не сработало и дерево
             * оборвалось (см. BslAstCompleteness). Остальные на структуру не влияют: разбивка
             * на методы по восстановленному AST полная, узел «Неполная структура» был бы
             * ложной тревогой — тем более что EDT такие модули теперь тоже сравнивает
             * структурно (см. BslCompareParseErrorSuppressor).
             */
            SyntaxErrorInfo syntaxError = parseResult instanceof IParseResult result
                && BslAstCompleteness.isTruncated(result) ? extractSyntaxError(parseResult) : null;

            Object module = Global.call(parseResult, "getRootASTElement"); //$NON-NLS-1$
            Class<?> moduleClass = Class.forName(MODULE_CLASS);
            if (module == null || !moduleClass.isInstance(module))
            {
                // AST недоступен вовсе — структуру показывать не из чего, но ошибку — можно.
                if (syntaxError != null)
                    return ParseOutcome.ok(new SectionNode("Модуль", 0, text.length()), syntaxError); //$NON-NLS-1$
                return ParseOutcome.fatal("Пустой модуль"); //$NON-NLS-1$
            }

            SectionNode root = new SectionNode("Модуль", 0, text.length()); //$NON-NLS-1$
            /*
             * getBslModuleSectionDescriptions на частичном (error-recovery) AST может повести
             * себя непредсказуемо (не рассчитан на "битый" код 1С) — не даём этому уронить
             * весь результат: при исключении просто остаёмся с пустым деревом + узел ошибки.
             */
            try
            {
                List<Object> descriptions = getSectionDescriptions(moduleClass, module, text);
                if (descriptions != null)
                    for (Object description : descriptions)
                        root.children.add(toSectionNode(description));
            }
            catch (Exception | LinkageError e)
            {
                // остаёмся с пустым деревом + узел ошибки (если есть)
            }
            return ParseOutcome.ok(root, syntaxError);
        }
        catch (Exception | LinkageError e)
        {
            return ParseOutcome.fatal(e.toString());
        }
    }

    private static Object parseBslModuleContent(String text) throws Exception
    {
        Method m = BslCompareUtils.class.getMethod("parseBslModuleContent", InputStream.class); //$NON-NLS-1$
        try (InputStream stream = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))
        {
            return m.invoke(null, stream);
        }
    }

    private static final String[] BLOCK_END_KEYWORDS =
        { "КонецПроцедуры", "КонецФункции", "КонецЕсли", "КонецЦикла", "КонецПопытки", "КонецОбласти" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    /**
     * {@code BslCompareUtils.parseBslModuleContent} строже интерактивного редактора BSL: перед
     * закрывающим ключевым словом конструкции (КонецПроцедуры/КонецФункции/...) точку с запятой
     * разрешается не ставить — это НЕ ошибка (подтверждено на реальном примере из редактора
     * EDT — тот же код там не подсвечивается). Наш разбор этот случай ошибочно считает
     * синтаксической ошибкой — фильтруем именно этот конкретный паттерн ложного срабатывания,
     * не синтаксические ошибки вообще (другие формулировки/места по-прежнему считаются реальными).
     */
    private static boolean isBenignMissingSemicolonBeforeBlockEnd(String message)
    {
        if (message == null || !message.contains("Пропущена лексема \";\"")) //$NON-NLS-1$
            return false;
        for (String keyword : BLOCK_END_KEYWORDS)
            if (message.contains(keyword))
                return true;
        return false;
    }

    /**
     * Берём ту ошибку, что оборвала дерево ({@link BslAstCompleteness#truncatingError}), а не
     * первую по тексту: в модуле их обычно несколько, и первая обычно безобидная — разбор
     * после неё восстановился. Показав её, мы отправили бы пользователя править не то место
     * (а то и другой метод).
     *
     * <p>Доброкачественный паттерн «пропущена {@code ;} перед КонецПроцедуры» (см.
     * {@link #isBenignMissingSemicolonBeforeBlockEnd}) в подпись не пускаем и здесь: если
     * обрыв «назначен» именно на него, берём ближайшую содержательную формулировку.
     */
    private static SyntaxErrorInfo extractSyntaxError(Object parseResult)
    {
        if (!(parseResult instanceof IParseResult result) || !result.hasSyntaxErrors())
            return null;

        INode truncating = BslAstCompleteness.truncatingError(result);
        SyntaxErrorInfo info = toSyntaxErrorInfo(truncating);
        if (info != null && !isBenignMissingSemicolonBeforeBlockEnd(info.message))
            return info;

        for (INode node : result.getSyntaxErrors())
        {
            SyntaxErrorInfo candidate = toSyntaxErrorInfo(node);
            if (candidate != null && !isBenignMissingSemicolonBeforeBlockEnd(candidate.message))
                return candidate;
        }
        return info; // все формулировки доброкачественные — показываем оборвавшую как есть
    }

    private static SyntaxErrorInfo toSyntaxErrorInfo(INode node)
    {
        if (node == null)
            return null;
        SyntaxErrorMessage errorMessage = node.getSyntaxErrorMessage();
        String message = errorMessage != null ? errorMessage.getMessage() : null;
        String text = message != null && !message.isBlank() ? message : "Синтаксическая ошибка"; //$NON-NLS-1$
        return new SyntaxErrorInfo(text, Math.max(0, node.getOffset()));
    }

    /**
     * {@code BslModuleSectionCompareUtils.getBslModuleSectionDescriptions(Module, IDocument,
     * String, IComparisonSession, ILocationInFileProvider, BslGrammarAccess)} — internal,
     * весь вызов через рефлексию. {@code IComparisonSession} — заглушка через {@link Proxy}
     * (см. класс-комментарий).
     */
    private static List<Object> getSectionDescriptions(Class<?> moduleClass, Object module, String text)
        throws Exception
    {
        ClassLoader bslCompareLoader = BslCompareUtils.class.getClassLoader();
        Class<?> utilsClass = Class.forName(SECTION_COMPARE_UTILS, true, bslCompareLoader);

        Class<?> documentClass = Class.forName(DOCUMENT_CLASS);
        Class<?> documentImplClass = Class.forName(DOCUMENT_IMPL_CLASS);
        Object document = documentImplClass.getConstructor(String.class).newInstance(text);

        /*
         * injector.getClass() резолвится в com.google.inject.internal.InjectorImpl (не public,
         * другой classloader) — Method с НЕГО кидает IllegalAccessException даже для формально
         * public-метода. Берём Method с публичного интерфейса com.google.inject.Injector
         * (уже используется в Activator.java этого же плагина — доступен на этапе компиляции).
         */
        Object activator = Class.forName(BSL_ACTIVATOR).getMethod("getInstance").invoke(null); //$NON-NLS-1$
        Injector injector = (Injector)activator.getClass()
            .getMethod("getInjector", String.class).invoke(activator, BSL_LANGUAGE_ID); //$NON-NLS-1$

        Class<?> grammarAccessClass = Class.forName(GRAMMAR_ACCESS_CLASS);
        Class<?> locationProviderClass = Class.forName(LOCATION_PROVIDER_CLASS, true, grammarAccessClass.getClassLoader());
        Object grammarAccess = injector.getInstance(grammarAccessClass);
        Object locationProvider = injector.getInstance(locationProviderClass);

        IComparisonSession dummySession = noOpComparisonSession();

        Method m = utilsClass.getMethod("getBslModuleSectionDescriptions", //$NON-NLS-1$
            moduleClass, documentClass, String.class, IComparisonSession.class, locationProviderClass,
            grammarAccessClass);
        Object result = m.invoke(null, module, document, null, dummySession, locationProvider, grammarAccess);
        @SuppressWarnings("unchecked")
        List<Object> descriptions = (List<Object>)result;
        return descriptions;
    }

    /** Все методы — безопасные значения по умолчанию (null/false/0), никогда не бросает исключение. */
    private static IComparisonSession noOpComparisonSession()
    {
        InvocationHandler handler = (proxy, method, args) ->
        {
            String name = method.getName();
            if ("hashCode".equals(name)) //$NON-NLS-1$
                return System.identityHashCode(proxy);
            if ("equals".equals(name)) //$NON-NLS-1$
                return proxy == (args != null && args.length > 0 ? args[0] : null);
            if ("toString".equals(name)) //$NON-NLS-1$
                return "BslModuleStructureParser$NoOpComparisonSession"; //$NON-NLS-1$
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class)
                return Boolean.FALSE;
            if (returnType == int.class || returnType == long.class || returnType == short.class
                || returnType == byte.class)
                return 0;
            if (returnType == char.class)
                return '\0';
            if (returnType == float.class || returnType == double.class)
                return 0.0;
            return null;
        };
        return (IComparisonSession)Proxy.newProxyInstance(
            IComparisonSession.class.getClassLoader(), new Class<?>[] { IComparisonSession.class }, handler);
    }

    /** {@code BslModuleSectionDescription}: {@code getName()}, {@code getType()}, {@code getRegions()}, {@code getChildren()}. */
    private static SectionNode toSectionNode(Object description)
    {
        Object nameObj = Global.call(description, "getName"); //$NON-NLS-1$
        Object typeObj = Global.call(description, "getType"); //$NON-NLS-1$
        String typeName = typeObj != null ? String.valueOf(Global.call(typeObj, "getName")) : null; //$NON-NLS-1$

        int[] range = regionRange(Global.call(description, "getRegions")); //$NON-NLS-1$
        String label = labelFor(typeName, nameObj instanceof String s ? s : null, range[2]);
        SectionNode node = new SectionNode(label, range[0], range[1]);

        Object childrenObj = Global.call(description, "getChildDescriptions"); //$NON-NLS-1$
        if (childrenObj instanceof Iterable<?> children)
            for (Object child : children)
                node.children.add(toSectionNode(child));
        return node;
    }

    private static String labelFor(String typeName, String name, int regionCount)
    {
        /*
         * "Main" — внутреннее имя типа секции в модели 1C (операторы верхнего уровня вне
         * процедур/функций). Может состоять из НЕСКОЛЬКИХ разрозненных фрагментов текста
         * в разных местах модуля (см. regionRange — офсет/длина сейчас охватывают диапазон от
         * первого фрагмента до конца последнего целиком, включая процедуры между ними, по
         * решению пользователя это оставлено как есть) — название честно отражает это,
         * не выдавая себя за один непрерывный «метод».
         *
         * ВАЖНО: проверка типа — ПЕРЕД проверкой имени, не после. У описания секции этого типа
         * getName() тоже возвращает "Main" (не пусто/null, как ожидалось изначально) — отладчик
         * подтвердил, что при проверке "по имени первым" эта ветка вообще не вызывается.
         */
        if ("Main".equals(typeName)) //$NON-NLS-1$
            return "<Вне методов " + regionCount + " " + placesWord(regionCount) + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (name != null && !name.isBlank())
            return name;
        return typeName != null ? typeName : "?"; //$NON-NLS-1$
    }

    /** Русское склонение «место» по числу: 1 место / 2-4 места / 0,5+ мест (искл. 11-14 → мест). */
    private static String placesWord(int count)
    {
        int mod100 = count % 100;
        int mod10 = count % 10;
        if (mod100 >= 11 && mod100 <= 14)
            return "мест"; //$NON-NLS-1$
        if (mod10 == 1)
            return "место"; //$NON-NLS-1$
        if (mod10 >= 2 && mod10 <= 4)
            return "места"; //$NON-NLS-1$
        return "мест"; //$NON-NLS-1$
    }

    /**
     * {@code List<TextRegion>} → [минимальный offset, суммарная длина от первого до конца
     * последнего, число фрагментов]. По решению пользователя офсет/длина намеренно оставлены
     * как один сплошной диапазон (первый…последний) даже когда фрагментов несколько и они
     * разрозненны по модулю — только счётчик фрагментов используется отдельно, для честного
     * названия узла «Main» (см. {@link #labelFor}), не для навигации/сравнения.
     */
    private static int[] regionRange(Object regionsObj)
    {
        if (!(regionsObj instanceof Iterable<?> regions))
            return new int[] { 0, 0, 0 };
        int minOffset = -1;
        int maxEnd = -1;
        int count = 0;
        for (Object region : regions)
        {
            Object offsetObj = Global.call(region, "getOffset"); //$NON-NLS-1$
            Object lengthObj = Global.call(region, "getLength"); //$NON-NLS-1$
            if (!(offsetObj instanceof Integer offset) || !(lengthObj instanceof Integer length))
                continue;
            count++;
            if (minOffset < 0 || offset < minOffset)
                minOffset = offset;
            int end = offset + length;
            if (end > maxEnd)
                maxEnd = end;
        }
        if (minOffset < 0)
            return new int[] { 0, 0, 0 };
        return new int[] { minOffset, Math.max(0, maxEnd - minOffset), count };
    }
}
