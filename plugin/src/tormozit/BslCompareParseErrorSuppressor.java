package tormozit;

import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.parser.IEncodingProvider;
import org.eclipse.xtext.resource.IContainer;
import org.eclipse.xtext.resource.IResourceDescription;
import org.eclipse.xtext.resource.IResourceFactory;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.validation.IResourceValidator;

/**
 * Снимает отказ EDT от режима «Сравнивать модули с учётом структуры» при синтаксической
 * ошибке в модуле.
 *
 * <p>Причина отказа — {@code BslModuleComparisonParticipant.compareBslModuleWithParsingModuleStructure}
 * (бандл {@code com._1c.g5.v8.dt.bsl.compare}): для каждой стороны он разбирает модуль и
 * сразу выходит, если {@code parseResult.getRootASTElement().eResource().getErrors()} непуст.
 * Вызывающий {@code compareBslModuleNode} на этот выход пишет в лог
 * «Failed to parse module content…», ставит {@code setParseModuleStructure(false)} и
 * сравнивает модуль как текст. Проверяется именно список ошибок ресурса, а не наличие AST:
 * Xtext после ошибки восстанавливается и дерево модуля со всеми методами строит полностью
 * (наш {@link BslModuleStructureParser} на тех же модулях разбивку на методы делает
 * корректно, и конфигуратор 1С в этом случае тоже работает по восстановленному AST).
 *
 * <p>Перехват — на создании ресурса, а не на байткоде участника. {@code BslCompareUtils
 * .parseBslModuleContent(InputStream)} создаёт ресурс так:
 *
 * <pre>
 * IResourceServiceProvider p = Registry.INSTANCE.getResourceServiceProvider(URI.createURI("foo1.bsl"));
 * XtextResource r = (XtextResource)p.get(IResourceFactory.class).createResource(URI.createURI("foo2.bsl"));
 * r.load(stream, Collections.emptyMap());
 * </pre>
 *
 * {@code foo2.bsl} — константа, встречающаяся только в этом методе, поэтому подмена
 * затрагивает ровно разбор модулей для сравнения/слияния и не касается редактора, билдера
 * и прочих потребителей BSL-ресурсов. Мы подменяем в {@code Registry.getExtensionToFactoryMap()}
 * запись для расширения {@code bsl} на делегирующую обёртку, и у ресурса с этим URI заранее
 * кладём в поле {@code ResourceImpl.errors} список {@link HiddenErrorList}, который принимает
 * добавляемые диагностики, но снаружи остаётся пустым. EDT видит «ошибок нет» и сравнивает
 * структурно; сами диагностики при этом никуда не деваются — {@code IParseResult
 * .getSyntaxErrors()} их по-прежнему отдаёт (на этом построены индикаторы ошибок в дереве
 * сравнения и панели «Структура»).
 *
 * <p>Подавляются не любые ошибки: если восстановление не сработало и дерево модуля оборвано
 * (см. {@link BslAstCompleteness}), список ошибок отдаётся как есть — пусть EDT штатно
 * сравнит модуль как текст. Структура по огрызку дерева хуже отсутствия структуры.
 *
 * <p>{@link #withoutSuppression(Supplier)} возвращает штатное поведение для одного вызова
 * в текущем потоке — нужно и для команды «Сравнить без учёта структуры» (пусть EDT сама
 * откатится к тексту), и для чтения реального списка ошибок в UI.
 */
public final class BslCompareParseErrorSuppressor
{
    private static final String TAG = "BslCompareParseErrors"; //$NON-NLS-1$
    /** URI ресурса, создаваемого {@code BslCompareUtils.parseBslModuleContent(InputStream)}. */
    private static final String COMPARE_RESOURCE_URI = "foo2.bsl"; //$NON-NLS-1$
    /** URI, по которому тот же метод получает провайдер сервисов языка. */
    private static final String COMPARE_PROVIDER_URI = "foo1.bsl"; //$NON-NLS-1$
    private static final String BSL_EXTENSION = "bsl"; //$NON-NLS-1$
    private static final String ERRORS_FIELD = "errors"; //$NON-NLS-1$
    /** Класс, чьё поле {@code errors} читает {@code Resource.getErrors()} (BslResource его затеняет). */
    private static final String RESOURCE_IMPL_CLASS = "org.eclipse.emf.ecore.resource.impl.ResourceImpl"; //$NON-NLS-1$

    private static final ThreadLocal<Boolean> SUPPRESSION_DISABLED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private BslCompareParseErrorSuppressor()
    {
    }

    /**
     * Ставит обёртку, если её ещё нет. Идемпотентна и дёшева — вызывается и при старте, и
     * при открытии сравнения (запись в реестре могла быть перезаписана поздней регистрацией
     * языка Xtext).
     */
    public static void ensureInstalled()
    {
        try
        {
            Map<String, Object> map = IResourceServiceProvider.Registry.INSTANCE.getExtensionToFactoryMap();
            if (map == null)
                return;
            Object current = map.get(BSL_EXTENSION);
            if (current instanceof SuppressingProvider)
                return;
            map.put(BSL_EXTENSION, new SuppressingProvider(current));
            log("установлена обёртка провайдера bsl-ресурсов (была: " //$NON-NLS-1$
                + (current != null ? current.getClass().getName() : "нет записи") + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception | LinkageError e)
        {
            Global.logError(TAG, "не удалось установить обёртку провайдера bsl-ресурсов", e); //$NON-NLS-1$
        }
    }

    /**
     * Выполняет {@code action} со штатным поведением EDT — ошибки разбора в текущем потоке
     * не скрываются.
     */
    public static <T> T withoutSuppression(Supplier<T> action)
    {
        boolean previous = Boolean.TRUE.equals(SUPPRESSION_DISABLED.get());
        SUPPRESSION_DISABLED.set(Boolean.TRUE);
        try
        {
            return action.get();
        }
        finally
        {
            SUPPRESSION_DISABLED.set(previous);
        }
    }

    private static boolean isSuppressionActive()
    {
        return !Boolean.TRUE.equals(SUPPRESSION_DISABLED.get());
    }

    private static void log(String message)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, message);
    }

    /**
     * Значение реестра для расширения {@code bsl}. Реализует {@code IResourceServiceProvider
     * .Provider}, а не сам {@code IResourceServiceProvider}, чтобы не резолвить провайдер
     * языка (и Guice-инжектор BSL) на старте плагина: реестр вызывает {@link #get} только
     * когда ресурс действительно понадобился.
     */
    private static final class SuppressingProvider implements IResourceServiceProvider.Provider
    {
        /** Прежнее значение реестра: сам провайдер либо один из двух видов Provider. */
        private final Object original;

        SuppressingProvider(Object original)
        {
            this.original = original;
        }

        /**
         * Реестр спрашивает провайдер на каждый bsl-ресурс (редактор, билдер, поиск) — обёртку
         * на делегат кэшируем, чтобы не плодить объекты тысячами. Делегат для языка один и тот же.
         */
        private volatile IResourceServiceProvider cachedDelegate;
        private volatile SuppressingResourceServiceProvider cachedWrapper;

        @Override
        public IResourceServiceProvider get(URI uri, String contentType)
        {
            IResourceServiceProvider delegate = resolveDelegate(uri, contentType);
            if (delegate == null)
                return null;
            SuppressingResourceServiceProvider wrapper = cachedWrapper;
            if (delegate != cachedDelegate || wrapper == null)
            {
                wrapper = new SuppressingResourceServiceProvider(delegate);
                cachedDelegate = delegate;
                cachedWrapper = wrapper;
            }
            return wrapper;
        }

        private IResourceServiceProvider resolveDelegate(URI uri, String contentType)
        {
            if (original instanceof IResourceServiceProvider.Provider provider)
                return provider.get(uri, contentType);
            if (original instanceof com.google.inject.Provider<?> provider)
            {
                Object value = provider.get();
                return value instanceof IResourceServiceProvider p ? p : null;
            }
            if (original instanceof IResourceServiceProvider provider)
                return provider;
            /*
             * Записи для bsl в карте расширений не было — язык резолвился по типу содержимого
             * либо протоколу. Спрашиваем реестр напрямую тем же URI, каким пользуется
             * BslCompareUtils; наша запись в карте расширений при этом уже стоит, поэтому
             * временно снимаем её, чтобы не уйти в рекурсию.
             */
            return resolveFromRegistryBypassingUs();
        }

        private IResourceServiceProvider resolveFromRegistryBypassingUs()
        {
            Map<String, Object> map = IResourceServiceProvider.Registry.INSTANCE.getExtensionToFactoryMap();
            Object saved = map.remove(BSL_EXTENSION);
            try
            {
                return IResourceServiceProvider.Registry.INSTANCE
                    .getResourceServiceProvider(URI.createURI(COMPARE_PROVIDER_URI));
            }
            finally
            {
                if (saved != null)
                    map.put(BSL_EXTENSION, saved);
            }
        }
    }

    /** Делегирует всё, кроме {@link IResourceFactory} — его подменяет {@link SuppressingResourceFactory}. */
    private static final class SuppressingResourceServiceProvider implements IResourceServiceProvider
    {
        private final IResourceServiceProvider delegate;

        SuppressingResourceServiceProvider(IResourceServiceProvider delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public IResourceValidator getResourceValidator()
        {
            return delegate.getResourceValidator();
        }

        @Override
        public IResourceDescription.Manager getResourceDescriptionManager()
        {
            return delegate.getResourceDescriptionManager();
        }

        @Override
        public IContainer.Manager getContainerManager()
        {
            return delegate.getContainerManager();
        }

        @Override
        public boolean canHandle(URI uri)
        {
            return delegate.canHandle(uri);
        }

        @Override
        public IEncodingProvider getEncodingProvider()
        {
            return delegate.getEncodingProvider();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T get(Class<T> type)
        {
            T service = delegate.get(type);
            if (type == IResourceFactory.class && service instanceof IResourceFactory factory)
                return (T)new SuppressingResourceFactory(factory);
            return service;
        }
    }

    /** Ресурсу сравнения ({@code foo2.bsl}) подставляет список ошибок, невидимый снаружи. */
    private static final class SuppressingResourceFactory implements IResourceFactory
    {
        private final IResourceFactory delegate;

        SuppressingResourceFactory(IResourceFactory delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public Resource createResource(URI uri)
        {
            Resource resource = delegate.createResource(uri);
            if (resource != null && isCompareResource(uri) && isSuppressionActive())
                hideErrors(resource);
            return resource;
        }

        private static boolean isCompareResource(URI uri)
        {
            return uri != null && COMPARE_RESOURCE_URI.equals(uri.toString());
        }

        private static void hideErrors(Resource resource)
        {
            /*
             * ResourceImpl.getErrors() создаёт список лениво и запоминает в поле errors —
             * положенный туда заранее наш экземпляр так и останется в игре после load().
             *
             * Поле берём именно у ResourceImpl, а не первое попавшееся с этим именем:
             * BslResource объявляет СВОЁ приватное errors (java.util.List, свой кэш
             * диагностик), которое затеняет поле EMF. Запись в него проходит успешно и
             * ничего не меняет — getErrors() продолжает отдавать список EMF, по которому
             * и судит участник сравнения.
             */
            boolean replaced = setResourceImplErrors(resource, new HiddenErrorList(resource));
            if (!replaced)
                log("не удалось подменить список ошибок ресурса " + resource.getClass().getName()); //$NON-NLS-1$
        }

        private static boolean setResourceImplErrors(Resource resource, Object value)
        {
            for (Class<?> c = resource.getClass(); c != null; c = c.getSuperclass())
            {
                if (!RESOURCE_IMPL_CLASS.equals(c.getName()))
                    continue;
                try
                {
                    java.lang.reflect.Field field = c.getDeclaredField(ERRORS_FIELD);
                    field.setAccessible(true);
                    field.set(resource, value);
                    return field.get(resource) == value;
                }
                catch (Exception | LinkageError e)
                {
                    log("не удалось записать поле errors: " + e); //$NON-NLS-1$
                    return false;
                }
            }
            log("ResourceImpl не найден в иерархии " + resource.getClass().getName()); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Список ошибок ресурса, который прикидывается пустым — но только пока восстановление
     * после ошибки отработало и модуль разобран целиком.
     *
     * <p>Диагностики хранит честно (обычным списком): участник сравнения смотрит только
     * {@code isEmpty()} (см. декомпиляцию {@code compareBslModuleWithParsingModuleStructure}),
     * а всем остальным потребителям врать незачем.
     *
     * <p>Решение принимается лениво, на первом же {@code isEmpty()}: к этому моменту
     * {@code load()} закончен и AST можно осмотреть. Оно кэшируется — запрос идёт на каждую
     * сторону и не должен каждый раз сканировать текст.
     */
    private static final class HiddenErrorList extends BasicEList<Resource.Diagnostic>
    {
        private static final long serialVersionUID = 1L;

        private final transient Resource resource;
        private transient Boolean truncated;

        HiddenErrorList(Resource resource)
        {
            this.resource = resource;
        }

        /**
         * {@code true} (пусто) — ошибки прячем, EDT сравнивает структурно. При оборванном AST
         * отдаём список как есть: пусть EDT штатно откатится к сравнению модуля как текста —
         * структура, построенная по огрызку дерева, хуже отсутствия структуры.
         */
        @Override
        public boolean isEmpty()
        {
            if (super.isEmpty())
                return true;
            return !isTruncated();
        }

        private boolean isTruncated()
        {
            Boolean known = truncated;
            if (known != null)
                return known.booleanValue();
            boolean result = BslAstCompleteness.isTruncated(resource);
            truncated = Boolean.valueOf(result);
            log("диагностик=" + size() + ", AST оборван=" + result //$NON-NLS-1$ //$NON-NLS-2$
                + (result ? " — подавление отменено, EDT сравнит модуль как текст" : " — ошибки скрыты")); //$NON-NLS-1$ //$NON-NLS-2$
            return result;
        }
    }
}
