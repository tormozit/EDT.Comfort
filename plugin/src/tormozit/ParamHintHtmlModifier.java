// ParamHintHtmlModifier.java
package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Переносит открывающую скобку {@code (} на новую строку в заголовке
 * подсказки параметров метода (CTRL+SHIFT+Space).
 * <p>
 * Перехват универсальный — глобальный SWT-фильтр {@link SWT#Show},
 * не привязан к конкретному пути открытия подсказки. Повторно
 * обрабатывает HTML при каждом обновлении содержимого (перемещение
 * каретки), так как проверка {@code <br>} перед скобкой идёт по
 * текущему HTML, а не по флагу.
 */
public final class ParamHintHtmlModifier
{
    private static final String HEADING_CLASS = "contentassist-heading-content";

    private static volatile boolean installed;

    private ParamHintHtmlModifier() {}

    public static boolean isInstalled()
    {
        return installed;
    }

    /** Установить глобальный перехватчик HTML подсказки параметров. */
    public static void install(Display display)
    {
        if (installed)
            return;
        installed = true;

        ContentAssistDebug.log("ParamHintHtmlModifier: install SWT.Show filter");

        display.addFilter(SWT.Show, event ->
        {
            if (!(event.widget instanceof Shell shell))
                return;
            if (shell.isDisposed())
                return;

            Browser browser = IrBslHoverHtml.findControlBrowser(shell);
            if (browser == null || browser.isDisposed())
                return;

            ContentAssistDebug.debugModeLog("ParamHintHtml", "browserFound",
                shell.getClass().getSimpleName(),
                "{\"browser\":\"" + browser.getClass().getName() + "\"}");

            browser.addProgressListener(new ProgressListener()
            {
                @Override
                public void completed(ProgressEvent event)
                {
                    tryModifyBrowserHtml(browser);
                }

                @Override
                public void changed(ProgressEvent event)
                {
                    // не используется
                }
            });

            // На случай если HTML уже загружен
            tryModifyBrowserHtml(browser);
        });

        ContentAssistDebug.log("ParamHintHtmlModifier: SWT.Show filter installed");
    }

    /** Модифицировать HTML в браузере, если в нём ещё нет переноса. */
    private static void tryModifyBrowserHtml(Browser browser)
    {
        if (browser == null || browser.isDisposed())
            return;

        String html = browser.getText();
        if (html == null || html.isBlank())
            return;

        String modified = modifyHeadingHtml(html);
        if (modified == null || modified.equals(html))
            return;

        browser.setText(modified);

        ContentAssistDebug.debugModeLog("ParamHintHtml", "modified",
            "ok",
            "{\"lenBefore\":" + html.length()
                + ",\"lenAfter\":" + modified.length()
                + ",\"modified\":\"" + ContentAssistDebug.jsonEscapeForLog(modified) + "\"}");
    }

    /**
     * Внутри {@code <span class="contentassist-heading-content">} меняет
     * первую {@code '('} на {@code '<br>('}. Если {@code <br>} уже есть
     * перед скобкой (предыдущая модификация) — возвращает {@code null}.
     *
     * @return модифицированный html, или {@code null} если паттерн не найден
     *         или HTML уже модифицирован
     */
    static String modifyHeadingHtml(String html)
    {
        if (html == null || html.isEmpty())
            return null;

        String marker = "<span class=\"" + HEADING_CLASS + "\">";
        int spanStart = html.indexOf(marker);
        if (spanStart < 0)
            return null;

        int contentStart = spanStart + marker.length();

        // Уже модифицировано: <br> найден раньше '('
        int brPos = html.indexOf("<br>", contentStart);
        int firstParen = html.indexOf('(', contentStart);
        if (firstParen < 0)
            return null;
        if (brPos >= 0 && brPos < firstParen)
            return null;

        // Проверить что '(' внутри того же span (не вышли за </span>)
        int spanEnd = html.indexOf("</span>", contentStart);
        if (spanEnd >= 0 && firstParen > spanEnd)
            return null;

        return html.substring(0, firstParen) + "<br>(" + html.substring(firstParen + 1);
    }
}
