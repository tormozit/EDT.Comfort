package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.platform.services.core.runtimes.IRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.IRuntimeInstallationValidator;
import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.wiring.ServiceProperties;

/**
 * Настройка {@link ComfortSettings#PREF_SUPPRESS_MIN_PLATFORM_BUILD} — подавление
 * требования ЕДТ по минимальной сборке платформы в рамках уже поддерживаемого релиза.
 *
 * <p>Требование считает валидатор {@code EnterprisePlatformInstallationValidator}
 * (внутренний класс бандла {@code com._1c.g5.v8.dt.platform.services.core}, карта
 * минимальных сборок — {@code RuntimeVersionMinimalBuildSupport}). Он используется в
 * двух местах через один и тот же {@link IRuntimeInstallationManager#getRuntimeInstallationValidator()}:
 * панель «Параметры → Версии платформы» (только статус) и
 * {@code ExecutionEnvironmentInstallationResolver.isValid} — уже реальный подбор среды
 * выполнения при запуске клиента/сервера, где сборка ниже минимальной исключается из
 * списка пригодных.
 *
 * <p>Подмена — рефлексией: приватное поле {@code runtimeInstallationValidator} менеджера
 * (недокументированный внутренний класс, доступа к типу нет) заменяется на
 * {@link Proxy}, который придерживает именно эту ошибку, определяя её не по жёстко
 * зашитому тексту, а по фактическому шаблону {@code MessageFormat} из ресурсов ЕДТ
 * (не зависит от локали). Остальные проверки валидатора (тип рантайма, поддерживаемые
 * релизы, битность, standalone-сервер) проходят как есть.
 */
public class PlatformMinBuildRequirementHook implements IStartup
{
    /** typeId менеджера инсталляций «1С:Предприятие» (в отличие от Server/WebClient и т.п.). */
    private static final String ENTERPRISE_PLATFORM_TYPE_ID =
        "com._1c.g5.v8.dt.platform.services.core.runtimeType.EnterprisePlatform"; //$NON-NLS-1$

    private static final String VALIDATOR_FIELD_NAME = "runtimeInstallationValidator"; //$NON-NLS-1$

    private static final String MESSAGES_CLASS =
        "com._1c.g5.v8.dt.internal.platform.services.core.runtimes.restrictions.Messages"; //$NON-NLS-1$

    private static final String MIN_BUILD_MESSAGE_FIELD =
        "Restrictions_Version__0__1__is_not_supported_DT_supports_installation_of_version__0__with_build_greated_than__2"; //$NON-NLS-1$

    private static final String LOG_TOPIC = "platformMinBuild"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        try
        {
            patch();
        }
        catch (Exception e)
        {
            Global.tempLogException(LOG_TOPIC,
                "не удалось подключиться к валидатору минимальной сборки платформы", e); //$NON-NLS-1$
        }
    }

    private static void patch() throws ReflectiveOperationException
    {
        IRuntimeInstallationManager manager = ServiceAccess.get(
            IRuntimeInstallationManager.class, ServiceProperties.named(ENTERPRISE_PLATFORM_TYPE_ID));
        if (manager == null)
        {
            Global.tempLog(LOG_TOPIC, "менеджер инсталляций EnterprisePlatform не найден"); //$NON-NLS-1$
            return;
        }

        IRuntimeInstallationValidator original = manager.getRuntimeInstallationValidator();
        if (original == null)
        {
            Global.tempLog(LOG_TOPIC, "у менеджера EnterprisePlatform нет валидатора"); //$NON-NLS-1$
            return;
        }
        if (Proxy.isProxyClass(original.getClass()))
        {
            Global.tempLog(LOG_TOPIC, "валидатор уже подменён (повторный earlyStartup?)"); //$NON-NLS-1$
            return;
        }

        Field field = findField(manager.getClass(), VALIDATOR_FIELD_NAME);
        if (field == null)
        {
            Global.tempLog(LOG_TOPIC,
                "поле " + VALIDATOR_FIELD_NAME + " не найдено в " + manager.getClass()); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        Pattern minBuildPattern = buildMinBuildPattern(original);

        IRuntimeInstallationValidator proxy = (IRuntimeInstallationValidator)Proxy.newProxyInstance(
            IRuntimeInstallationValidator.class.getClassLoader(),
            new Class<?>[] { IRuntimeInstallationValidator.class },
            new SuppressingHandler(original, minBuildPattern));

        field.setAccessible(true);
        field.set(manager, proxy);
        Global.tempLog(LOG_TOPIC,
            "валидатор подменён, шаблон минимальной сборки распознан: " + (minBuildPattern != null)); //$NON-NLS-1$
    }

    /**
     * Строит регэксп из фактического (уже переведённого) шаблона {@code MessageFormat},
     * которым ЕДТ формирует сообщение об ошибке минимальной сборки — без жёстко
     * зашитого текста и без привязки к локали.
     */
    private static Pattern buildMinBuildPattern(IRuntimeInstallationValidator original)
    {
        try
        {
            Class<?> messagesClass = original.getClass().getClassLoader().loadClass(MESSAGES_CLASS);
            Field field = messagesClass.getDeclaredField(MIN_BUILD_MESSAGE_FIELD);
            field.setAccessible(true);
            String template = (String)field.get(null);
            if (template == null || template.isBlank())
                return null;

            String[] parts = template.split("\\{\\d+\\}", -1); //$NON-NLS-1$
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < parts.length; i++)
            {
                regex.append(Pattern.quote(parts[i]));
                if (i < parts.length - 1)
                    regex.append(".*?"); //$NON-NLS-1$
            }
            return Pattern.compile(regex.toString(), Pattern.DOTALL);
        }
        catch (Exception e)
        {
            Global.tempLogException(LOG_TOPIC,
                "не удалось получить шаблон сообщения минимальной сборки", e); //$NON-NLS-1$
            return null;
        }
    }

    private static Field findField(Class<?> type, String name)
    {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
        {
            try
            {
                return c.getDeclaredField(name);
            }
            catch (NoSuchFieldException ignored)
            {
                // ищем в предке
            }
        }
        return null;
    }

    /** Делегирует исходному валидатору; заменяет статус min-build ошибки на OK при включённой настройке. */
    private static final class SuppressingHandler implements InvocationHandler
    {
        private final IRuntimeInstallationValidator original;
        private final Pattern minBuildPattern;

        SuppressingHandler(IRuntimeInstallationValidator original, Pattern minBuildPattern)
        {
            this.original = original;
            this.minBuildPattern = minBuildPattern;
        }

        @Override
        public Object invoke(Object proxyInstance, Method method, Object[] args) throws Throwable
        {
            Object result;
            try
            {
                result = method.invoke(original, args);
            }
            catch (InvocationTargetException e)
            {
                throw e.getCause();
            }
            if (!(result instanceof IStatus status) || status.isOK())
                return result;
            if (!ComfortSettings.isSuppressMinPlatformBuildEnabled())
                return result;
            if (minBuildPattern == null)
                return result;
            String message = status.getMessage();
            if (message == null || !minBuildPattern.matcher(message).matches())
                return result;
            return Status.OK_STATUS;
        }
    }
}
