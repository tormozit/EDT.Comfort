package tormozit;

import org.eclipse.compare.CompareUI;
import org.eclipse.compare.CompareViewerSwitchingPane;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Control;

/**
 * Локализация заголовка режима сравнения в шапке {@link CompareViewerSwitchingPane}
 * (там же, где выпадающий список «Сравнение по умолчанию» / «Сравнение встроенного языка» /
 * «Сравнение с учётом семантики» / «Сравнение текста»).
 *
 * <p>Xtext-просмотрщик BSL кладёт в {@code CompareUI.COMPARE_VIEWER_TITLE} своего control'а
 * нелокализованный заголовок «Bsl Compare» (имя грамматики + «Compare»), и
 * {@code CompareViewerSwitchingPane.setInput} показывает именно его — хотя пункт того же
 * режима в выпадающем списке уже русский (подпись descriptor'а из {@code plugin_ru.properties}
 * бандла {@code com._1c.g5.v8.dt.bsl.ui}). Подменяем на ту же русскую подпись.
 *
 * <p>Два входа — по моменту вызова относительно {@code setInput} панели:
 * <ul>
 *   <li>{@link #localizeViewerData(Viewer)} — в нашем {@code findContentViewer}, до того как
 *   панель прочитала данные control'а (окно «Вставить со сравнением»);</li>
 *   <li>{@link #localizePaneTitle(CompareViewerSwitchingPane, Control)} — когда мы приходим уже
 *   к готовой панели чужого входа сравнения (EGit, локальная история): заголовок уже прочитан
 *   и показан, поэтому правим ещё и {@code fTitle} с перерисовкой шапки.</li>
 * </ul>
 */
public final class CompareViewerTitle
{
    /** Подпись пункта «Сравнение встроенного языка» из {@code plugin_ru.properties} бандла BSL UI. */
    private static final String BSL_TITLE = "Сравнение встроенного языка"; //$NON-NLS-1$

    private CompareViewerTitle()
    {
    }

    /**
     * Подмена в данных control'а вьюера — до того, как панель сравнения прочитает
     * {@code COMPARE_VIEWER_TITLE} в своём {@code setInput}.
     */
    public static void localizeViewerData(Viewer viewer)
    {
        if (viewer == null)
            return;
        localizeControlData(viewer.getControl());
    }

    /**
     * Панель уже показала нелокализованный заголовок (её {@code setInput} прошёл раньше, чем мы
     * добрались до вьюера) — переписываем и данные control'а (на будущие {@code setInput}), и
     * приватное {@code fTitle} панели с вызовом её {@code updateTitle}: публичного способа
     * переустановить заголовок нет — {@code setText} затрётся ближайшим {@code updateTitle}.
     */
    public static void localizePaneTitle(CompareViewerSwitchingPane pane, Control viewerControl)
    {
        if (pane == null || pane.isDisposed())
            return;
        localizeControlData(viewerControl);
        Object shown = Global.getField(pane, "fTitle"); //$NON-NLS-1$
        if (!(shown instanceof String s) || !isRawTitle(s))
            return;
        Global.setField(pane, "fTitle", BSL_TITLE); //$NON-NLS-1$
        Global.invokeVoid(pane, "updateTitle"); //$NON-NLS-1$
    }

    private static void localizeControlData(Control control)
    {
        if (control == null || control.isDisposed())
            return;
        Object title = control.getData(CompareUI.COMPARE_VIEWER_TITLE);
        if (title instanceof String s && isRawTitle(s))
            control.setData(CompareUI.COMPARE_VIEWER_TITLE, BSL_TITLE);
    }

    private static boolean isRawTitle(String title)
    {
        return "Bsl Compare".equals(title) || "Xtext Compare".equals(title); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
