// IrBslHoverHtml.java
package tormozit;

import org.eclipse.jface.text.IInformationControl;
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
    private static final String BSL_DOC_UTIL_CLASS =
        "com._1c.g5.v8.dt.internal.bsl.ui.documentation.BslDocumentationUtil"; //$NON-NLS-1$

    private static volatile java.lang.reflect.Method addStyleToHtmlMethod;
    private static volatile java.lang.reflect.Method defaultLanguageMethod;

    private IrBslHoverHtml() {}

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
            // #region agent log
            ContentAssistDebug.debugSessionLog("H12", "wrapIrOnly", "styled", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"inLen\":" + insert.length() //$NON-NLS-1$
                    + ",\"outLen\":" + wrapped.length() //$NON-NLS-1$
                    + ",\"fullDoc\":" + isFullHtmlDocument(wrapped) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
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

    /**
     * Полный HTML-документ assist/hover EDT ({@code addStyleToHtml}) для фрагмента без base.
     * Без обёртки SWT Browser показывает сырой HTML и обрезает содержимое.
     */
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
                // #region agent log
                ContentAssistDebug.debugSessionLog("H12", "wrapForAssistBrowser", "noMethod", "{}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                // #endregion
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
            // #region agent log
            ContentAssistDebug.debugSessionLog("H12", "wrapForAssistBrowser", "fail", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"err\":" + ContentAssistDebug.jsonStr(e.getClass().getSimpleName()) //$NON-NLS-1$
                    + ",\"msg\":" + ContentAssistDebug.jsonStr(String.valueOf(e.getMessage())) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return trimmed;
        }
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