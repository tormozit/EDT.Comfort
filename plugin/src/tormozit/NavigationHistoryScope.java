package tormozit;

import java.lang.reflect.Field;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.INavigationHistory;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Сохранение позиции в истории навигации EDT («Назад/Вперёд по истории») для перехода,
 * который платформа не видит сама. Отметка «откуда» ставится до открытия, все промежуточные
 * записи подавляются через {@code NavigationHistory.ignoreEntries}, отметка «куда» — после.
 * Обе отметки обязательны: перед переходом «назад» платформа перезаписывает текущую запись
 * фактической кареткой ({@code NavigationHistory.shiftEntry}).
 *
 * <p>Вынесено из {@code BslEditorHyperlinkHook.HistoryScope} (issue 664), потому что тот же
 * переход нужен ссылкам в быстрых исправлениях и подсказках (issue 1080). Проверка полей
 * платформы — в {@code .tmp/bundles/ui-workbench/NavigationHistory-c.javap.txt}.
 *
 * @see BslBracketJumpHistoryHook
 */
public class NavigationHistoryScope implements AutoCloseable
{
    private static final String LOG = "bsl-hyperlink-history"; //$NON-NLS-1$

    private final IWorkbenchPage page;
    private final INavigationHistory history;
    private Field ignoreEntries;
    private boolean opened;

    public NavigationHistoryScope(IWorkbenchPage page, IEditorPart source)
    {
        this.page = page;
        history = page == null ? null : page.getNavigationHistory();
        Global.tempLog(LOG, "begin source=" + describeEditor(source)); //$NON-NLS-1$
        if (history == null)
            return;
        try
        {
            if (!"org.eclipse.ui.internal.NavigationHistory".equals(history.getClass().getName())) //$NON-NLS-1$
                return;
            Field field = history.getClass().getDeclaredField("ignoreEntries"); //$NON-NLS-1$
            field.setAccessible(true);
            Global.tempLog(LOG, "mark source ignoreEntries=" + field.getInt(history)); //$NON-NLS-1$
            history.markLocation(source);
            int previous = field.getInt(history);
            field.setInt(history, previous + 1);
            ignoreEntries = field;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            Global.tempLog(LOG, "begin failed: " + e); //$NON-NLS-1$
            // При несовместимости платформы оставляем штатную историю переходов.
        }
    }

    /** Переход действительно открыл редактор — иначе новая запись не создаётся. */
    public void markOpened()
    {
        opened = true;
    }

    @Override
    public void close()
    {
        if (ignoreEntries == null)
            return;
        try
        {
            ignoreEntries.setInt(history, ignoreEntries.getInt(history) - 1);
            IEditorPart destination = page.getActiveEditor();
            // У редактора объекта отметка хранит страницу, у вложенного BSL — каретку.
            BslXtextEditor bsl = GetRef.getActiveBslEditor(destination);
            if (bsl != null)
                destination = bsl;
            Global.tempLog(LOG, "finish opened=" + opened //$NON-NLS-1$
                + " ignoreEntries=" + ignoreEntries.getInt(history) //$NON-NLS-1$
                + " destination=" + describeEditor(destination)); //$NON-NLS-1$
            if (opened && destination != null)
                history.markLocation(destination);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            Global.tempLog(LOG, "finish failed: " + e); //$NON-NLS-1$
            // Не прерываем переход из-за ошибки истории.
        }
    }

    /**
     * Выполнить действие с отметкой активного BSL-редактора как исходной позиции.
     * Используется мостами из HTML-подсказок, где переход узнаётся только в момент клика.
     */
    public static void runFromActiveEditor(Runnable action)
    {
        IWorkbenchPage page = Global.getActivePage();
        IWorkbenchPart part = page == null ? null : page.getActivePart();
        IEditorPart source = part == null ? null : GetRef.getActiveBslEditor(part);
        if (page == null || source == null)
        {
            action.run();
            return;
        }
        try (NavigationHistoryScope history = new NavigationHistoryScope(page, source))
        {
            action.run();
            history.markOpened();
        }
    }

    private static String describeEditor(IEditorPart editor)
    {
        if (editor == null)
            return "null"; //$NON-NLS-1$
        try
        {
            String description = editor.getClass().getName() + " input=" + editor.getEditorInput().getName(); //$NON-NLS-1$
            if (editor instanceof BslXtextEditor bsl && bsl.getInternalSourceViewer() != null)
                description += " modelSelection=" + bsl.getInternalSourceViewer().getSelectedRange(); //$NON-NLS-1$
            return description;
        }
        catch (RuntimeException e)
        {
            return editor.getClass().getName() + " unavailable: " + e; //$NON-NLS-1$
        }
    }
}
