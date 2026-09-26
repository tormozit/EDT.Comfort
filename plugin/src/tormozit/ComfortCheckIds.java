package tormozit;

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

    /** {@code tormozit.checks.BrokenFormPictureCheck} — битая ссылка на картинку в форме. */
    public static final String BROKEN_FORM_PICTURE = "comfort.check.brokenFormPicture"; //$NON-NLS-1$

    public static final String STATIC_ACCESS_PARAMETERS = "comfort-bsl-static-access-parameters"; //$NON-NLS-1$
    public static final String STATIC_ACCESS_OBSOLETE = "comfort-bsl-static-access-obsolete"; //$NON-NLS-1$
    public static final String STATIC_ACCESS_COMPATIBILITY = "comfort-bsl-static-access-compatibility"; //$NON-NLS-1$
    public static final String STATIC_ACCESS_VARIABLE = "comfort-bsl-static-access-variable"; //$NON-NLS-1$
    public static final String STATIC_ACCESS_EVENT_HANDLER = "comfort-bsl-static-access-event-handler"; //$NON-NLS-1$

    private ComfortCheckIds() {}
}
