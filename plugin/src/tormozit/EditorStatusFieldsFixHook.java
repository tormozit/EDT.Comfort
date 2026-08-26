package tormozit;

import java.util.Map;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.MultiPageEditorActionBarContributor;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;

/**
 * Восстанавливает штатный индикатор позиции в строке состояния («2979 : 16 : 110732», «Запись»,
 * «Insert») у редакторов модулей, встроенных в {@link DtGranularEditor} (модуль формы и т.п.).
 *
 * Дефект EDT: все три секции — это поля {@code AbstractTextEditor.fStatusFields}, которые ставит
 * {@code DtGranularEditorActionBarContributor.setActivePage(embeddedEditor)}, вызываемый из
 * {@code MultiPageEditorPart.pageChange}. Если редактор восстановлен при старте EDT, страница
 * модуля становится активной без {@code pageChange} — контрибьютор о ней не узнаёт, карта полей
 * остаётся без {@code InputPosition}/{@code InputMode}/{@code ElementState}, и
 * {@code updateStatusField} молча выходит: обновлять нечего. Индикатор залипает до тех пор, пока
 * пользователь не переключится на другую часть и обратно (тогда {@code pageChange} срабатывает).
 *
 * Лечение: при активации редактора проверяем наличие {@code InputPosition} у встроенного
 * BSL-редактора и, если его нет, сами вызываем {@code setActivePage} — тот же вызов, что EDT
 * делает при каждой смене страницы, поэтому он идемпотентен.
 */
public class EditorStatusFieldsFixHook implements IStartup
{
    /** Категория штатного поля позиции ({@code AbstractTextEditor.CATEGORY_INPUT_POSITION}). */
    private static final String POSITION_CATEGORY = "InputPosition"; //$NON-NLS-1$
    private static final int MAX_ATTEMPTS = 5;
    private static final int RETRY_DELAY_MS = 300;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(EditorStatusFieldsFixHook::install);
    }

    private static void install()
    {
        if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench() == null)
            return;

        PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow w)      { hookWindow(w); }
            @Override public void windowActivated(IWorkbenchWindow w)   {}
            @Override public void windowDeactivated(IWorkbenchWindow w) {}
            @Override public void windowClosed(IWorkbenchWindow w)      {}
        });

        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            hookWindow(window);
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        // Редактор, восстановленный при старте, активируется до установки слушателя.
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
            schedule(page.getActiveEditor(), 0);

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partActivated(IWorkbenchPartReference ref)     { fromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref)  { fromRef(ref); }
            @Override public void partOpened(IWorkbenchPartReference ref)        {}
            @Override public void partClosed(IWorkbenchPartReference ref)        {}
            @Override public void partDeactivated(IWorkbenchPartReference ref)   {}
            @Override public void partHidden(IWorkbenchPartReference ref)        {}
            @Override public void partVisible(IWorkbenchPartReference ref)       {}
            @Override public void partInputChanged(IWorkbenchPartReference ref)  {}
        });
    }

    private static void fromRef(IWorkbenchPartReference ref)
    {
        if (ref instanceof IEditorReference editorRef)
            schedule(editorRef.getEditor(false), 0);
    }

    /**
     * Проверяет редактор после того, как EDT достроит страницу; при неудаче — ограниченное число
     * повторов (страница granular-редактора создаётся асинхронно).
     */
    private static void schedule(IEditorPart editor, int attempt)
    {
        if (editor == null || attempt >= MAX_ATTEMPTS)
            return;

        Display display = Display.getDefault();
        display.asyncExec(() -> {
            if (restoreStatusFields(editor))
                return;
            display.timerExec(RETRY_DELAY_MS, () -> schedule(editor, attempt + 1));
        });
    }

    /**
     * @return {@code true}, если проверять больше нечего (поля на месте, восстановлены, или
     *         редактор не относится к делу)
     */
    private static boolean restoreStatusFields(IEditorPart editor)
    {
        if (editor == null || editor.getEditorSite() == null)
            return true;
        if (!(editor instanceof DtGranularEditor<?>))
            return true;

        BslXtextEditor embedded = GetRef.getActiveBslEditor(editor);
        if (embedded == null)
            return false; // страница ещё не создана или открыта не страница модуля — повторим
        if (hasPositionField(embedded))
            return true;

        if (!(editor.getEditorSite()
            .getActionBarContributor() instanceof MultiPageEditorActionBarContributor contributor))
            return true;

        contributor.setActivePage(embedded);

        Global.tempLog("status-line", "{\"event\":\"restoreStatusFields\",\"editor\":\"" //$NON-NLS-1$ //$NON-NLS-2$
            + editor.getTitle() + "\",\"restored\":" + hasPositionField(embedded) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        return true;
    }

    private static boolean hasPositionField(BslXtextEditor editor)
    {
        Object statusFields = Global.getField(editor, "fStatusFields"); //$NON-NLS-1$
        return statusFields instanceof Map<?, ?> map && map.containsKey(POSITION_CATEGORY);
    }
}
