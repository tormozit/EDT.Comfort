// IrBslHoverHtml.java
package tormozit;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.jface.internal.text.html.BrowserInformationControl;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IInformationControlCreatorExtension;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/**
 * Слияние HTML doc-hover / боковой подсказки с блоком из ИР
 * без ссылок на internal API {@code BslBrowserInformationControlInput}.
 */
public final class IrBslHoverHtml
{
    private static final String BSL_BROWSER_INPUT_CLASS =
        "com._1c.g5.v8.dt.internal.bsl.ui.browserscommon.BslBrowserInformationControlInput"; //$NON-NLS-1$
    private static final String BSL_ASSIST_BROWSER_INPUT_CLASS =
        "com._1c.g5.v8.dt.bsl.ui.contentassist.BslContentAssistBrowserInput"; //$NON-NLS-1$
    private static final String BSL_HOVER_CONTROL_CREATOR_CLASS =
        "com._1c.g5.v8.dt.bsl.ui.hover.BslEObjectHoverProvider$BslHoverControlCreator"; //$NON-NLS-1$
    private static final String BSL_DOC_UTIL_CLASS =
        "com._1c.g5.v8.dt.internal.bsl.ui.documentation.BslDocumentationUtil"; //$NON-NLS-1$

    private static volatile java.lang.reflect.Method addStyleToHtmlMethod;
    private static volatile java.lang.reflect.Method defaultLanguageMethod;

    /** Представление отдельно от объекта EDT: ссылки и история используют исходный input. */
    private static final Map<Object, HtmlDecoration> HTML_DECORATIONS =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final String DECORATION_LISTENER_MARK = "tormozit.bslHtmlDecorationListener"; //$NON-NLS-1$

    private IrBslHoverHtml() {}

    /** Возвращает тот же объект EDT; для чистого HTML из ИР сохраняет строковый путь. */
    public static Object withHtml(Object input, String html)
    {
        if (!isBslBrowserInput(input))
            return html;
        HTML_DECORATIONS.put(input, new HtmlDecoration(Global.invoke(input, "getIndex"), html)); //$NON-NLS-1$
        return input;
    }

    /** HTML предыдущего оформителя, когда несколько дополнений строятся последовательно. */
    public static String readDisplayHtml(Object input)
    {
        String html = decoratedHtml(input);
        return html != null ? html : readHtml(input);
    }

    private static String decoratedHtml(Object input)
    {
        HtmlDecoration decoration = HTML_DECORATIONS.get(input);
        return decoration != null
            && java.util.Objects.equals(decoration.index, Global.invoke(input, "getIndex")) //$NON-NLS-1$
                ? decoration.html : null;
    }

    /** setInput уже сохранил исходный объект и уведомляет слушателей после Browser.setText. */
    public static void installHtmlDecoration(IInformationControl control)
    {
        if (!(control instanceof BrowserInformationControl information))
            return;
        Browser browser = findControlBrowser(control);
        if (browser == null || browser.isDisposed())
            return;
        MetadataTypeMenu.installBrowser(browser);
        if (browser.getData(DECORATION_LISTENER_MARK) == null)
        {
            browser.setData(DECORATION_LISTENER_MARK, Boolean.TRUE);
            information.addInputChangeListener(input -> applyHtmlDecoration(information, browser, input));
            if (hasBslHeightFitListener(browser))
                information.addInputChangeListener(input -> keepVisibleHeightUntilLoaded(information, browser));
            applyHtmlDecoration(information, browser, information.getInput());
        }
    }

    /**
     * Смена строки списка при видимой подсказке: jface после {@code setInput} растягивает окно —
     * боковая подсказка автодополнения не меньше списка ({@code CompletionProposalPopup}:
     * {@code setSizeConstraints(50, 10, true, …)}), да и {@code computeSizeHint} для HTML EDT
     * упирается в максимум. До подгонки по высоте страницы (обработчик EDT на загрузку) видна
     * неперерисованная чёрная область (#878). Ограничения jface ставит до {@code setInput},
     * а размер считает после — здесь, между ними, держим текущую высоту на один показ.
     * Нужную высоту выставит EDT.
     */
    private static void keepVisibleHeightUntilLoaded(BrowserInformationControl control, Browser browser)
    {
        if (browser.isDisposed())
            return;
        org.eclipse.swt.widgets.Shell shell = browser.getShell();
        if (shell == null || shell.isDisposed() || !shell.isVisible())
            return;
        Object constraints = Global.getField(control, "fSizeConstraints"); //$NON-NLS-1$
        int width = constraints instanceof org.eclipse.swt.graphics.Point point
            ? point.x : org.eclipse.swt.SWT.DEFAULT;
        control.setSizeConstraints(width, shell.getSize().y);
        liftAssistMinimalSizeOnce(control, shell.getDisplay());
    }

    /**
     * Снять «не меньше списка» у {@code AdditionalInfoController} на текущий показ, если control —
     * его боковая подсказка. Флаг возвращается сразу после показа: штатный jface читает его
     * синхронно в том же {@code internalShowInformationControl}, что вызвал {@code setInput}.
     */
    private static boolean liftAssistMinimalSizeOnce(BrowserInformationControl control,
        org.eclipse.swt.widgets.Display display)
    {
        Object controller = ContentAssistPopupSync.resolveAdditionalInfoController(
            ContentAssistSessionReloader.getActiveAssistant());
        if (controller == null
            || Global.invoke(controller, "getCurrentInformationControl2") != control //$NON-NLS-1$
            || !Boolean.TRUE.equals(Global.getField(controller, "fEnforceAsMinimalSize"))) //$NON-NLS-1$
            return false;
        if (!Global.setFieldForce(controller, "fEnforceAsMinimalSize", Boolean.FALSE)) //$NON-NLS-1$
            return false;
        display.asyncExec(() -> {
            if (Boolean.FALSE.equals(Global.getField(controller, "fEnforceAsMinimalSize"))) //$NON-NLS-1$
                Global.setFieldForce(controller, "fEnforceAsMinimalSize", Boolean.TRUE); //$NON-NLS-1$
        });
        return true;
    }

    /** Обработчик EDT, подгоняющий высоту окна под страницу после загрузки. */
    private static boolean hasBslHeightFitListener(Browser browser)
    {
        Object webBrowser = Global.getField(browser, "webBrowser"); //$NON-NLS-1$
        Object listeners = Global.getField(webBrowser, "progressListeners"); //$NON-NLS-1$
        if (listeners instanceof org.eclipse.swt.browser.ProgressListener[] array)
            for (org.eclipse.swt.browser.ProgressListener listener : array)
                if (listener != null
                    && listener.getClass().getName().startsWith(BSL_HOVER_CONTROL_CREATOR_CLASS))
                    return true;
        return false;
    }

    private static void applyHtmlDecoration(BrowserInformationControl control, Browser browser, Object input)
    {
        if (browser.isDisposed() || control.getInput() != input)
            return;
        String html = decoratedHtml(input);
        if (html != null)
        {
            browser.setText(html);
        }
    }

    /** Также охватывает штатный фокусируемый control, созданный самим JFace. */
    static void installHtmlDecorationForBrowser(Browser browser)
    {
        if (HTML_DECORATIONS.isEmpty() || browser == null || browser.isDisposed())
            return;
        Object webBrowser = Global.getField(browser, "webBrowser"); //$NON-NLS-1$
        Object listeners = Global.getField(webBrowser, "locationListeners"); //$NON-NLS-1$
        if (listeners instanceof org.eclipse.swt.browser.LocationListener[] array)
            for (org.eclipse.swt.browser.LocationListener listener : array)
            {
                Object bsl = listener;
                if (bsl != null && !bsl.getClass().getName().endsWith("BslLocationListener")) //$NON-NLS-1$
                    bsl = Global.getField(bsl, "val$listener"); //$NON-NLS-1$
                if (bsl == null || !bsl.getClass().getName().endsWith("BslLocationListener")) //$NON-NLS-1$
                    continue;
                Object control = Global.getField(bsl, "control"); //$NON-NLS-1$
                if (control instanceof IInformationControl information)
                    installHtmlDecoration(information);
            }
    }

    public static IInformationControlCreator wrapCreator(IInformationControlCreator creator)
    {
        // При переходе мышью в подсказку JFace берёт не внешний creator,
        // а этот presenter из HoverControlCreator (проверено в Xtext JAR).
        // Слушатель нужен до первого setInput: на SWT.Show HTML уже загружен.
        if (creator instanceof org.eclipse.xtext.ui.editor.hover.html.DefaultEObjectHoverProvider.HoverControlCreator)
        {
            Object presenter = Global.getField(creator, "fInformationPresenterControlCreator"); //$NON-NLS-1$
            if (presenter instanceof IInformationControlCreator presenterCreator
                && !(presenterCreator instanceof HtmlDecorationCreator))
            {
                Global.setFieldForce(creator, "fInformationPresenterControlCreator", //$NON-NLS-1$
                    wrapCreator(presenterCreator));
            }
        }
        return creator == null || creator instanceof HtmlDecorationCreator
            ? creator : new HtmlDecorationCreator(creator);
    }

    private static final class HtmlDecoration
    {
        final Object index;
        final String html;

        HtmlDecoration(Object index, String html)
        {
            this.index = index;
            this.html = html;
        }
    }

    private static final class HtmlDecorationCreator
        implements IInformationControlCreator, IInformationControlCreatorExtension
    {
        private final IInformationControlCreator delegate;

        HtmlDecorationCreator(IInformationControlCreator delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public IInformationControl createInformationControl(org.eclipse.swt.widgets.Shell parent)
        {
            IInformationControl control = delegate.createInformationControl(parent);
            installHtmlDecoration(control);
            return control;
        }

        @Override
        public boolean canReuse(IInformationControl control)
        {
            boolean reuse = delegate instanceof IInformationControlCreatorExtension extension
                && extension.canReuse(control);
            if (reuse)
                installHtmlDecoration(control);
            return reuse;
        }

        @Override
        public boolean canReplace(IInformationControlCreator creator)
        {
            IInformationControlCreator other = creator instanceof HtmlDecorationCreator decorated
                ? decorated.delegate : creator;
            return delegate instanceof IInformationControlCreatorExtension extension
                && extension.canReplace(other);
        }
    }

    public static boolean isBslBrowserInput(Object info)
    {
        if (info == null)
            return false;
        for (Class<?> type = info.getClass(); type != null; type = type.getSuperclass())
        {
            if (BSL_BROWSER_INPUT_CLASS.equals(type.getName()))
                return true;
        }
        return false;
    }

    public static String readHtml(Object browserInput)
    {
        if (browserInput == null)
            return ""; //$NON-NLS-1$
        if (browserInput instanceof String text)
            return text;
        Object html = Global.invoke(browserInput, "getHtml"); //$NON-NLS-1$
        return html != null ? html.toString() : ""; //$NON-NLS-1$
    }

    public static boolean isFullHtmlDocument(String html)
    {
        if (html == null || html.isBlank())
            return false;
        String lower = html.trim().toLowerCase();
        return lower.startsWith("<!doctype html") || lower.startsWith("<html"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public static String mergeHtml(String baseHtml, String irFragment)
    {
        if (irFragment == null || irFragment.isEmpty())
            return baseHtml != null ? baseHtml : ""; //$NON-NLS-1$
        String trimmedIr = irFragment.trim();
        if (baseHtml == null || baseHtml.isEmpty())
        {
            String insert = extractInsertableFragment(trimmedIr);
            if (insert.isEmpty())
                insert = trimmedIr;
            insert = stripIrEmbeddedChrome(insert);
            String wrapped = wrapForAssistBrowser(insert);
            return wrapped;
        }
        String insert = extractInsertableFragment(trimmedIr);
        if (insert.isEmpty())
            insert = trimmedIr;
        String irBlock = "<hr/><div class=\"comfort-ir-hover\">" + insert + "</div>"; //$NON-NLS-1$ //$NON-NLS-2$
        String lower = baseHtml.toLowerCase();
        int bodyEnd = lower.lastIndexOf("</body>"); //$NON-NLS-1$
        if (bodyEnd >= 0)
            return baseHtml.substring(0, bodyEnd) + irBlock + baseHtml.substring(bodyEnd);
        return baseHtml + irBlock;
    }

    /**
     * Вставляет блок директивы компиляции ({@code &НаСервере} и т.д.) в HTML
     * перед первым {@code <div} или {@code <span}. Идемпотентно: повторный вызов
     * с тем же HTML не добавит директиву повторно.
     *
     * @return HTML с директивой или исходный {@code html} если директива пуста
     */
    public static String injectDirectiveIntoHtml(String html, String directive)
    {
        if (html == null || html.isEmpty() || directive == null || directive.isBlank())
            return html;
        String marker = "comfort-directive"; //$NON-NLS-1$
        if (html.contains(marker))
            return html;
        String directiveBlock = "<div class=\"" + marker + "\">" + directive.trim() + "</div> "; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int firstDiv = html.indexOf("<div"); //$NON-NLS-1$
        int firstSpan = html.indexOf("<span"); //$NON-NLS-1$
        int insertAt = -1;
        if (firstDiv >= 0 && firstSpan >= 0)
            insertAt = Math.min(firstDiv, firstSpan);
        else if (firstDiv >= 0)
            insertAt = firstDiv;
        else if (firstSpan >= 0)
            insertAt = firstSpan;
        if (insertAt < 0)
            return html;
        return html.substring(0, insertAt) + directiveBlock + html.substring(insertAt);
    }

    /**
     * Вставляет «Новая» в начало заголовка штатной подсказки при наведении
     * ({@code infocontrol-heading}). Идемпотентно: повторный вызов не дублирует префикс.
     *
     * @return HTML с префиксом или исходный {@code html}, если заголовок не найден
     */
    public static String injectNewVariablePrefix(String html)
    {
        if (html == null || html.isEmpty())
            return html;
        String marker = "comfort-new-variable"; //$NON-NLS-1$
        if (html.contains(marker))
            return html;
        int insertAt = findHeadingTextInsertOffset(html);
        if (insertAt < 0)
            return html;
        String prefix = "<span class=\"" + marker + "\">Новая</span> "; //$NON-NLS-1$ //$NON-NLS-2$
        return html.substring(0, insertAt) + prefix + html.substring(insertAt);
    }

    private static int findHeadingTextInsertOffset(String html)
    {
        int classPos = html.indexOf("infocontrol-heading"); //$NON-NLS-1$
        if (classPos < 0)
            classPos = html.indexOf("contentassist-heading-content"); //$NON-NLS-1$
        if (classPos < 0)
            return -1;
        int openEnd = html.indexOf('>', classPos);
        if (openEnd < 0)
            return -1;
        int insertAt = openEnd + 1;
        int headingClose = html.indexOf("</div>", insertAt); //$NON-NLS-1$
        int counterClass = html.indexOf("infocontrol-counter", insertAt); //$NON-NLS-1$
        if (counterClass >= 0 && (headingClose < 0 || counterClass < headingClose))
        {
            int counterEnd = html.indexOf("</span>", counterClass); //$NON-NLS-1$
            if (counterEnd >= 0 && (headingClose < 0 || counterEnd < headingClose))
                insertAt = counterEnd + "</span>".length(); //$NON-NLS-1$
        }
        return insertAt;
    }

    /**
     * Находит виджет {@link Browser} внутри information control, обходя дерево SWT-виджетов.
     * Аналог внутреннего {@code BslInfoBrowserUtils.getInfoBrowser()} из EDT.
     */
    public static Browser findControlBrowser(IInformationControl control)
    {
        if (control == null)
            return null;
        Object shell = Global.invoke(control, "getShell"); //$NON-NLS-1$
        if (!(shell instanceof Composite comp))
            return null;
        return findDescendantBrowser(comp);
    }

    /** Находит виджет {@link Browser} внутри произвольного составного SWT-виджета. */
    public static Browser findControlBrowser(Composite composite)
    {
        if (composite == null || composite.isDisposed())
            return null;
        return findDescendantBrowser(composite);
    }

    private static Browser findDescendantBrowser(Composite composite)
    {
        for (Control child : composite.getChildren())
        {
            if (child instanceof Browser browser && !browser.isDisposed())
                return browser;
            if (child instanceof Composite c)
            {
                Browser b = findDescendantBrowser(c);
                if (b != null)
                    return b;
            }
        }
        return null;
    }

    /**
     * Применяет HTML напрямую в браузер information control, минуя {@code setInput()}.
     * Используется когда стандартный {@code setInput(String)} бросает ассерт в Xtext-контроле.
     *
     * @return {@code true} если браузер найден и HTML применён
     */
    public static boolean applyHtmlToControl(IInformationControl control, String html)
    {
        if (control == null || html == null)
            return false;
        Browser browser = findControlBrowser(control);
        if (browser == null || browser.isDisposed())
            return false;
        browser.setText(html);
        return true;
    }

    /**
     * {@code true}, если текущий HTML в браузере — сброс EDT на исходную base-страницу,
     * а не навигация по гиперссылке на другую doc-страницу.
     *
     * @param mergedHtml полный merged (base + ИР), для отличия сброса от страницы без блока ИР
     */
    static boolean looksLikeBaseHtmlReset(String current, String base, String mergedHtml)
    {
        if (current == null || base == null || base.isEmpty())
            return false;
        String normCurrent = normalizeHtmlForCompare(current);
        String normBase = normalizeHtmlForCompare(base);
        if (normCurrent.equals(normBase))
            return true;
        int lenC = normCurrent.length();
        int lenB = normBase.length();
        if (lenB < 100)
            return false;
        int lenTolerance = Math.max(250, lenB / 25);
        int diffToBase = Math.abs(lenC - lenB);
        if (mergedHtml != null && !mergedHtml.isEmpty())
        {
            int diffToMerged = Math.abs(lenC - normalizeHtmlForCompare(mergedHtml).length());
            if (diffToBase <= lenTolerance && diffToMerged > lenTolerance)
                return true;
        }
        else if (diffToBase <= lenTolerance)
            return true;
        int prefixLen = Math.min(lenB, lenC) - 50;
        if (prefixLen <= 0)
            return false;
        return lenB > 200
            && lenC > lenB * 0.85
            && normBase.regionMatches(0, normCurrent, 0, prefixLen);
    }

    private static String normalizeHtmlForCompare(String html)
    {
        if (html == null || html.isEmpty())
            return ""; //$NON-NLS-1$
        return html.toLowerCase().replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public static String wrapForAssistBrowser(String content)
    {
        if (content == null || content.isBlank())
            return content;
        String trimmed = content.trim();
        if (isFullHtmlDocument(trimmed))
            return trimmed;
        try
        {
            ensureAssistBrowserStyleMethods();
            if (addStyleToHtmlMethod == null)
            {
                return trimmed;
            }
            String lang = "ru"; //$NON-NLS-1$
            if (defaultLanguageMethod != null)
            {
                Object rawLang = defaultLanguageMethod.invoke(null);
                if (rawLang instanceof String s && !s.isEmpty())
                    lang = s;
            }
            Object wrapped = addStyleToHtmlMethod.invoke(null, trimmed, lang);
            return wrapped != null ? wrapped.toString() : trimmed;
        }
        catch (Exception e)
        {
            return trimmed;
        }
    }

    /** Payload для {@code AdditionalInfoController.showInformation} — wrapped HTML для browser. */
    public static Object toAssistSideHintPayload(Object html)
    {
        if (html == null)
            return null;
        if (isBslBrowserInput(html))
            return html;
        String text = readHtml(html);
        if (text == null || text.isEmpty())
            return html;
        if (isFullHtmlDocument(text))
            return text;
        return wrapForAssistBrowser(text);
    }

    private static void ensureAssistBrowserStyleMethods()
        throws ClassNotFoundException, NoSuchMethodException
    {
        if (addStyleToHtmlMethod == null)
        {
            Class<?> browserCls = resolveBslBrowserInputClass();
            addStyleToHtmlMethod = browserCls.getMethod(
                "addStyleToHtml", String.class, String.class); //$NON-NLS-1$
        }
        if (defaultLanguageMethod == null)
        {
            try
            {
                Class<?> util = Class.forName(BSL_DOC_UTIL_CLASS);
                defaultLanguageMethod = util.getMethod("getDefaultLanguage"); //$NON-NLS-1$
            }
            catch (ClassNotFoundException | NoSuchMethodException ignored)
            {
                // lang остаётся ru
            }
        }
    }

    /** Internal-класс EDT недоступен из OSGi — берём через экспортированный assist-input. */
    private static Class<?> resolveBslBrowserInputClass() throws ClassNotFoundException
    {
        Class<?> assist = Class.forName(BSL_ASSIST_BROWSER_INPUT_CLASS);
        for (Class<?> type = assist; type != null; type = type.getSuperclass())
        {
            if (BSL_BROWSER_INPUT_CLASS.equals(type.getName()))
                return type;
        }
        throw new ClassNotFoundException(BSL_BROWSER_INPUT_CLASS);
    }

    /** Убирает встроенные {@code style}/{@code script} ИР — иначе перебивают EDT {@code addStyleToHtml}. */
    static String stripIrEmbeddedChrome(String html)
    {
        if (html == null || html.isEmpty())
            return html;
        String stripped = html
            .replaceAll("(?is)<style[^>]*>.*?</style>", "") //$NON-NLS-1$
            .replaceAll("(?is)<script[^>]*>.*?</script>", ""); //$NON-NLS-1$
        return stripped.trim();
    }

    /** ИР часто возвращает полный {@code <html>…</html>}; в base вставляем только содержимое body. */
    static String extractInsertableFragment(String html)
    {
        if (html == null || html.isEmpty())
            return ""; //$NON-NLS-1$
        String trimmed = html.trim();
        String lower = trimmed.toLowerCase();
        int bodyStart = lower.indexOf("<body"); //$NON-NLS-1$
        if (bodyStart < 0)
            return trimmed;
        int bodyOpenEnd = lower.indexOf('>', bodyStart);
        if (bodyOpenEnd < 0)
            return trimmed;
        int bodyEnd = lower.lastIndexOf("</body>"); //$NON-NLS-1$
        if (bodyEnd > bodyOpenEnd)
            return trimmed.substring(bodyOpenEnd + 1, bodyEnd).trim();
        return trimmed.substring(bodyOpenEnd + 1).trim();
    }
}
