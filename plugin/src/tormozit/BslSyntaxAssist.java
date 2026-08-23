package tormozit;

import java.util.List;
import java.util.Locale;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.osgi.framework.Bundle;

import com.google.inject.Injector;

/**
 * Мост к панели «Синтакс-помощник» (вид {@code BslInfoView} бандла {@code com._1c.g5.v8.dt.bsl.ui}).
 *
 * <p><b>Почему рефлексия и почему не {@code Class.forName}.</b> Все нужные классы лежат во
 * внутренних пакетах {@code com._1c.g5.v8.dt.internal.bsl.ui.*}; наружу пакеты не экспортируются,
 * и {@code Class.forName} из нашего бандла падает с {@code ClassNotFoundException}. Классы грузятся
 * загрузчиком самого бандла — {@code Platform.getBundle(...).loadClass(...)}: он ограничения
 * экспорта не проверяет.
 *
 * <p><b>Устройство панели.</b> Она состоит из двух частей: сверху панель навигации
 * ({@code SyntaxAssistNavigationPanel} — оглавление, поиск, закладки), снизу панель описания
 * ({@code SyntaxAssistDescriptionPanel} со своим браузером). {@link #showSearch(String)} работает
 * только с верхней (переключает вкладку на «Поиск» и запускает поиск), {@link #openViewPage(Object)}
 * — только с нижней. Поэтому их можно вызывать вместе: они не перебивают друг друга.
 *
 * <p>Потребители: {@link SyntaxAssistOpenElementHandler} (команда редактора модуля) и
 * {@code PropertySheetActivePropertyHook} (пункт «Синтакс-помощник» панели «Свойства»).
 */
public final class BslSyntaxAssist
{
    /** Тема временного лога — общая с потребителями. */
    private static final String TEMP_TOPIC = "syntax-assist"; //$NON-NLS-1$

    private static final String BSL_UI_BUNDLE = "com._1c.g5.v8.dt.bsl.ui"; //$NON-NLS-1$
    private static final String BSL_ACTIVATOR =
        "com._1c.g5.v8.dt.bsl.ui.internal.BslActivator"; //$NON-NLS-1$
    private static final String DOC_PROVIDER =
        "com._1c.g5.v8.dt.internal.bsl.ui.documentation.BslDocumentationProvider"; //$NON-NLS-1$
    private static final String VIEW_PAGE =
        "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.IBslDocumentationViewPage"; //$NON-NLS-1$
    private static final String PAGE_DESCRIPTOR =
        "com._1c.g5.v8.dt.internal.bsl.ui.syntaxassist.description.DocumentationPageDescriptor"; //$NON-NLS-1$
    private static final String GROUP_DESCRIPTOR =
        "com._1c.g5.v8.dt.internal.bsl.ui.syntaxassist.description.DocumentationPageGroupDescriptor"; //$NON-NLS-1$
    private static final String LANGUAGE_PROVIDER =
        "com._1c.g5.v8.dt.internal.bsl.ui.syntaxassist.SyntaxAssistLanguageProvider"; //$NON-NLS-1$
    private static final String VIEW_UTIL =
        "com._1c.g5.v8.dt.internal.bsl.ui.syntaxassist.SyntaxAssistViewUtil"; //$NON-NLS-1$
    private static final String DOC_UTIL =
        "com._1c.g5.v8.dt.internal.bsl.ui.documentation.BslDocumentationUtil"; //$NON-NLS-1$

    /** Идентификатор вида панели «Синтакс-помощник». */
    public static final String VIEW_ID = "com._1c.g5.v8.dt.bsl.ui.view.BslInfoView"; //$NON-NLS-1$

    /** {@code BslDocumentationProvider} — Guice-синглтон, ищется один раз. */
    private static Object docProviderCache;

    private BslSyntaxAssist() {}

    /** Класс из внутреннего пакета {@code com._1c.g5.v8.dt.bsl.ui} — только его же загрузчиком. */
    public static Class<?> bslUiClass(String name)
    {
        return bundleClass(BSL_UI_BUNDLE, name);
    }

    /** Класс из внутреннего пакета чужого бандла — только его же загрузчиком. */
    public static Class<?> bundleClass(String bundleId, String name)
    {
        try
        {
            Bundle bundle = Platform.getBundle(bundleId);
            return bundle != null ? bundle.loadClass(name) : null;
        }
        catch (ClassNotFoundException e)
        {
            Global.tempLog(TEMP_TOPIC, "класс не найден в " + bundleId + ": " + name); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /**
     * {@code BslDocumentationProvider} из Guice-инжектора языка BSL — того же, из которого его
     * берёт сама панель «Синтакс-помощник». У {@code BslActivator.getInjector} есть параметр языка
     * (безаргументного {@code getInjector()} в этой версии EDT нет).
     *
     * <p>Сам инжектор приводится к {@link Injector} (пакет {@code com.google.inject} нашему бандлу
     * доступен), а не вызывается рефлексией: {@code InjectorImpl} лежит во внутреннем пакете Guice,
     * и рефлексивный вызов по нему может молча не пройти. Если инжектор языка почему-либо
     * недоступен, тот же экземпляр берётся из Xtext-реестра сервисов по любому BSL-URI.
     */
    public static Object documentationProvider()
    {
        Object cached = docProviderCache;
        if (cached != null)
            return cached;
        Object provider = injected(DOC_PROVIDER);
        if (provider == null)
        {
            Class<?> providerClass = bslUiClass(DOC_PROVIDER);
            provider = providerClass != null ? providerFromXtextRegistry(providerClass) : null;
        }
        docProviderCache = provider;
        return provider;
    }

    /** Язык документации, выбранный в самой панели; при неудаче — язык среды. */
    public static String language()
    {
        Object provider = injected(LANGUAGE_PROVIDER);
        Object language = provider != null ? Global.invoke(provider, "getLanguage") : null; //$NON-NLS-1$
        if (language instanceof String text && !text.isBlank())
            return text;
        return Locale.getDefault().getLanguage();
    }

    /** Экземпляр из Guice-инжектора языка BSL по имени класса. */
    private static Object injected(String className)
    {
        try
        {
            Class<?> targetClass = bslUiClass(className);
            Class<?> activatorClass = bslUiClass(BSL_ACTIVATOR);
            if (targetClass == null || activatorClass == null)
            {
                Global.tempLog(TEMP_TOPIC, "классы bsl.ui недоступны (" + className //$NON-NLS-1$
                    + "=" + targetClass + ", активатор=" + activatorClass + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return null;
            }
            Object activator = activatorClass.getMethod("getInstance").invoke(null); //$NON-NLS-1$
            Object language = activatorClass.getField("COM__1C_G5_V8_DT_BSL_BSL").get(null); //$NON-NLS-1$
            Object injector = activator != null
                ? activatorClass.getMethod("getInjector", String.class).invoke(activator, language) //$NON-NLS-1$
                : null;
            return injector instanceof Injector guice ? guice.getInstance(targetClass) : null;
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "инжектор: " + className, e); //$NON-NLS-1$
            return null;
        }
    }

    /** Запасной путь: тот же Guice-экземпляр через реестр сервисов Xtext по BSL-URI. */
    private static Object providerFromXtextRegistry(Class<?> providerClass)
    {
        IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(URI.createURI("comfort.bsl")); //$NON-NLS-1$
        Object provider = rsp != null ? rsp.get(providerClass) : null;
        Global.tempLog(TEMP_TOPIC, "провайдер через реестр Xtext: rsp=" //$NON-NLS-1$
            + (rsp == null ? "null" : rsp.getClass().getName()) //$NON-NLS-1$
            + ", провайдер=" + (provider == null ? "null" : provider.getClass().getName())); //$NON-NLS-1$ //$NON-NLS-2$
        return provider;
    }

    /**
     * Группа страниц документации для элемента модели — то, чем EDT описывает рассчитанную роль
     * слова ({@code BslDocumentationPageGroup}). Ноль страниц — роль не определилась, больше одной
     * — роль неоднозначна.
     *
     * <p>Собирается долго (читает документацию платформы) — вызывать не из потока UI.
     */
    public static Object viewDocumentationPages(EObject element)
    {
        try
        {
            Object provider = documentationProvider();
            if (provider == null || element == null)
                return null;
            return provider.getClass()
                .getMethod("getViewDocumentationPages", EObject.class, String.class) //$NON-NLS-1$
                .invoke(provider, element, language());
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "страницы для " + element, e); //$NON-NLS-1$
            return null;
        }
    }

    /** Страницы группы; пустой список — если группы нет. */
    public static List<?> pagesOf(Object group)
    {
        Object pages = group != null ? Global.invoke(group, "getPages") : null; //$NON-NLS-1$
        return pages instanceof List<?> list ? list : List.of();
    }

    /** Поисковый запрос, который EDT сопоставил группе; может быть {@code null}. */
    public static String searchQueryOf(Object group)
    {
        Object query = group != null ? Global.invoke(group, "getSearchQuery") : null; //$NON-NLS-1$
        return query instanceof String text ? text : null;
    }

    /** Показывает панель и возвращает её ({@code SyntaxAssistView}); {@code null} — не открылась. */
    public static Object showView()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window != null ? window.getActivePage() : null;
            if (page != null)
                page.showView(VIEW_ID);
            Class<?> utilClass = bslUiClass(VIEW_UTIL);
            return utilClass != null
                ? utilClass.getMethod("showOrGetShowedView").invoke(null) : null; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "открытие панели", e); //$NON-NLS-1$
            return null;
        }
    }

    /** Поиск в верхней части панели (вкладка «Поиск» панели навигации). */
    public static void showSearch(String query)
    {
        if (query == null || query.isBlank())
            return;
        try
        {
            Class<?> utilClass = bslUiClass(VIEW_UTIL);
            if (utilClass != null)
                utilClass.getMethod("showSearch", String.class).invoke(null, query); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "поиск «" + query + "»", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Загружает готовую страницу ({@code IBslDocumentationViewPage}) в нижнюю часть панели.
     *
     * @return {@code true}, если страница отдана браузеру панели описания.
     */
    public static boolean openViewPage(Object viewPage)
    {
        Object descriptor = pageDescriptor(viewPage);
        return descriptor != null && openDescriptor(descriptor);
    }

    /** Дескриптор одной страницы для браузера панели описания. */
    public static Object pageDescriptor(Object viewPage)
    {
        try
        {
            Object docProvider = documentationProvider();
            Class<?> descriptorClass = bslUiClass(PAGE_DESCRIPTOR);
            if (viewPage == null || docProvider == null || descriptorClass == null)
                return null;
            return descriptorClass.getConstructor(bslUiClass(VIEW_PAGE), bslUiClass(DOC_PROVIDER))
                .newInstance(viewPage, docProvider);
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "дескриптор страницы", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Дескриптор группы страниц — та самая страница EDT «Неоднозначность» / «Ничего не найдено»
     * со списком найденных ролей.
     */
    public static Object pageGroupDescriptor(List<?> pages, String searchQuery)
    {
        try
        {
            Object docProvider = documentationProvider();
            Class<?> descriptorClass = bslUiClass(GROUP_DESCRIPTOR);
            if (docProvider == null || descriptorClass == null)
                return null;
            return descriptorClass
                .getConstructor(List.class, bslUiClass(DOC_PROVIDER), String.class)
                .newInstance(pages, docProvider, searchQuery);
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "дескриптор группы страниц", e); //$NON-NLS-1$
            return null;
        }
    }

    /** Открывает дескриптор в браузере нижней части панели. */
    public static boolean openDescriptor(Object descriptor)
    {
        Object view = showView();
        Object panel = view != null ? Global.invoke(view, "getDescriptionPanel") : null; //$NON-NLS-1$
        Object browser = panel != null ? Global.invoke(panel, "getBrowser") : null; //$NON-NLS-1$
        if (browser == null || descriptor == null)
            return false;
        return Global.invokeVoid(browser, "openPage", descriptor); //$NON-NLS-1$
    }

    /**
     * Элемент модели по смещению в документе модуля — то же, чем EDT определяет роль слова
     * (переменная, метод объекта, тип и т.п.). Смещение — <b>модельное</b> (из
     * {@code ITextViewer.getSelectedRange()}), не виджетное.
     *
     * <p>Внутри читает документ, поэтому вызывать не из потока UI.
     */
    public static EObject elementAt(IXtextDocument xtextDocument, int offset)
    {
        try
        {
            Class<?> utilClass = bslUiClass(DOC_UTIL);
            if (utilClass == null || xtextDocument == null)
                return null;
            Object element = utilClass
                .getMethod("getEObjectByXtextDocumentAndOffset", IXtextDocument.class, int.class) //$NON-NLS-1$
                .invoke(null, xtextDocument, offset);
            return element instanceof EObject eObject ? eObject : null;
        }
        catch (Exception e)
        {
            Global.tempLogException(TEMP_TOPIC, "элемент по смещению " + offset, e); //$NON-NLS-1$
            return null;
        }
    }
}
