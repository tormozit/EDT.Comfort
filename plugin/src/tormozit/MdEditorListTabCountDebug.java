package tormozit;

import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.ui.forms.editor.IFormPage;

/**
 * Диагностика счётчиков во вкладках-списках редактора объекта метаданных
 * ({@link MdEditorListTabCountHook}).
 *
 * <p>Причина: у нового объекта без подписок и оснований счётчики «Подписки» и
 * «Ввод на основании» периодически показывают очень большое число, которое
 * пропадает после перехода на вкладку
 * (<a href="https://github.com/tormozit/EDT.Comfort/issues/439">issue 439</a>).
 *
 * <p>Включение: Параметры → Комфорт → «Общее логирование». Эталон: {@link DebugInspectorDebug}.
 */
public final class MdEditorListTabCountDebug
{
    private static final String TAG = "MdEditorListTabCount"; //$NON-NLS-1$

    private MdEditorListTabCountDebug() {}

    public static boolean isEnabled()
    {
        return Global.isLogEnabled();
    }

    /**
     * Одна строка на решение о токене счётчика вкладки.
     *
     * @param base заголовок вкладки без суффикса
     * @param page страница редактора (может быть {@code null})
     * @param created создан ли уже элемент управления вкладки
     * @param attempt номер повторной попытки подсчёта
     * @param branch ветка {@code refreshTab}, принявшая решение
     * @param token что вписано в заголовок ({@code null} — суффикс снят)
     * @param detail дополнительные данные ветки (сырые/отфильтрованные числа и т.п.)
     */
    public static void count(String base, IFormPage page, boolean created, int attempt, String branch,
        String token, String detail)
    {
        if (!isEnabled())
            return;
        StringBuilder sb = new StringBuilder(160);
        sb.append("tab='").append(base).append('\''); //$NON-NLS-1$
        sb.append(" page=").append(pageRef(page)); //$NON-NLS-1$
        sb.append(" created=").append(created); //$NON-NLS-1$
        sb.append(" attempt=").append(attempt); //$NON-NLS-1$
        sb.append(" branch=").append(branch); //$NON-NLS-1$
        sb.append(" token=").append(token == null ? "<none>" : token); //$NON-NLS-1$ //$NON-NLS-2$
        if (detail != null && !detail.isEmpty())
            sb.append(' ').append(detail);
        Global.log(TAG, sb.toString());
    }

    public static void problem(String msg)
    {
        if (isEnabled())
            Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
    }

    static String pageRef(IFormPage page)
    {
        if (page == null)
            return "null"; //$NON-NLS-1$
        String id = page.getId();
        return (id == null ? "?" : id) + '/' + page.getClass().getSimpleName(); //$NON-NLS-1$
    }

    static String filtersRef(ViewerFilter[] filters)
    {
        if (filters == null)
            return "null"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        sb.append(filters.length).append('['); //$NON-NLS-1$
        for (int i = 0; i < filters.length; i++)
        {
            if (i > 0)
                sb.append(',');
            sb.append(filters[i] == null ? "null" : filters[i].getClass().getSimpleName()); //$NON-NLS-1$
        }
        return sb.append(']').toString();
    }
}
