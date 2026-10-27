package tormozit;

import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * Идентификаторы проверок, которые плагин добавляет в EDT.
 *
 * <p>Сами классы проверок живут в отдельном бандле {@code tormozit.comfort.checks} (см. его
 * {@code MANIFEST.MF} — там же объяснено, зачем он отделён). Основной бандл зависеть от него не
 * может: получилось бы кольцо зависимостей. Поэтому строковые идентификаторы, нужные хукам
 * ({@link GitBaselineFilterHook}, {@link ProblemViewOpenTargetHook}), лежат здесь, а классы
 * проверок берут их отсюда.
 *
 * <p>Значения менять нельзя: по ним EDT хранит настройки проверок, скрытия и маркеры уже
 * посчитанных проблем. Новое значение — это для EDT другая проверка.
 */
public final class ComfortCheckIds
{
    /** {@code tormozit.checks.BslAstTruncationCheck} — обрыв разбора модуля. */
    public static final String BSL_AST_TRUNCATION = "comfort-bsl-ast-truncation"; //$NON-NLS-1$

    /** Битые ссылки метаданных; прежний идентификатор сохраняет настройки проверки. */
    public static final String BROKEN_MD_REFERENCE = "comfort-md-broken-reference"; //$NON-NLS-1$

    /** {@code tormozit.checks.UnreachableMdObjectCheck} — объекта нет в составе конфигурации. */
    public static final String UNREACHABLE_MD_OBJECT = "comfort-md-unreachable-object"; //$NON-NLS-1$

    public static final String STATIC_ACCESS_PARAMETERS = "comfort-bsl-static-access-parameters"; //$NON-NLS-1$
    public static final String STATIC_ACCESS_OBSOLETE = "comfort-bsl-static-access-obsolete"; //$NON-NLS-1$
    public static final String STATIC_ACCESS_COMPATIBILITY = "comfort-bsl-static-access-compatibility"; //$NON-NLS-1$
    public static final String STATIC_ACCESS_VARIABLE = "comfort-bsl-static-access-variable"; //$NON-NLS-1$
    public static final String STATIC_ACCESS_EVENT_HANDLER = "comfort-bsl-static-access-event-handler"; //$NON-NLS-1$

    /** {@code tormozit.checks.BslPreprocessorStraddleCheck} — {@code #Если}, разрывающая оператор. */
    public static final String BSL_PREPROCESSOR_STRADDLE = "comfort-bsl-preprocessor-straddle"; //$NON-NLS-1$

    /** {@code tormozit.checks.BslForEachCollectionCheck} — «Для каждого» по выражению, не являющемуся коллекцией. */
    public static final String BSL_FOR_EACH_COLLECTION = "comfort-bsl-for-each-collection"; //$NON-NLS-1$

    /** Неизвестное поле выборки с достоверно рассчитанной схемой запроса. */
    public static final String BSL_QUERY_RESULT_FIELD = "comfort-bsl-query-result-field"; //$NON-NLS-1$

    /** Несовместимость типов выражений, аргументов и значений свойств платформы. */
    public static final String BSL_VALUE_TYPE = "comfort-bsl-value-type"; //$NON-NLS-1$

    /** Обращение по числовому индексу к типу, который его не поддерживает. */
    public static final String BSL_NUMERIC_INDEX = "comfort-bsl-numeric-index"; //$NON-NLS-1$

    /** Литерал вне полного списка допустимых значений; прежний id сохраняет настройки проверки. */
    public static final String BSL_UNKNOWN_LITERAL = "comfort-bsl-unknown-column"; //$NON-NLS-1$

    /** Штатные проверки и их замены; настройки штатных проверок не изменяются. */
    public static final Map<String, List<String>> BUILTIN_REPLACEMENTS = Map.of(
        "bsl-legacy-check-static-feature-access", List.of( //$NON-NLS-1$
            STATIC_ACCESS_PARAMETERS, STATIC_ACCESS_OBSOLETE, STATIC_ACCESS_COMPATIBILITY,
            STATIC_ACCESS_VARIABLE, STATIC_ACCESS_EVENT_HANDLER),
        "bsl-legacy-check-for-each-statetement-collection", List.of(BSL_FOR_EACH_COLLECTION), //$NON-NLS-1$
        "bsl-legacy-check-expression-type", List.of(BSL_VALUE_TYPE)); //$NON-NLS-1$

    /** Выполнение этих проверок динамически замещается независимо от включённости замен. */
    public static final Set<String> REPLACED_BUILTIN_CHECKS = BUILTIN_REPLACEMENTS.keySet();

    private ComfortCheckIds() {}
}
