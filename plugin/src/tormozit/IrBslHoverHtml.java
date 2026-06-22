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

    public static String mergeHtml(String baseHtml, String irFragment)
    {
        if (irFragment == null || irFragment.isEmpty())
            return baseHtml != null ? baseHtml : ""; //$NON-NLS-1$
        String insert = extractInsertableFragment(irFragment);
        if (insert.isEmpty())
            insert = irFragment;
        String irBlock = "<hr/><div class=\"comfort-ir-hover\">" + insert + "</div>"; //$NON-NLS-1$ //$NON-NLS-2$
        if (baseHtml == null || baseHtml.isEmpty())
            return irBlock;
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