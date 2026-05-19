

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartService;
import org.eclipse.ui.IWorkbenchPart;

import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;

/**
 * Патч диалога поиска EDT (CTRL+F в дереве сравнения).
 *
 * Добавляет флажок "Только имена объектов" (включён по умолчанию).
 * Когда флажок снят — кнопки "Далее"/"Назад" используют наш поиск:
 * обходят все видимые строки дерева и ищут текст во всех ячейках.
 *
 * Подключается один раз через Display.addFilter(SWT.Show) в earlyStartup(),
 * что гарантирует перехват каждого нового открытия диалога.
 */
public class CompareConfigSearchDialogHook
{
    /** Ключ-маркер: диалог уже был обработан нами. */
    private static final String PATCHED_KEY = "tormozit.searchPatched"; //$NON-NLS-1$

    /** Имя целевого класса EDT-диалога поиска. */
    private static final String DIALOG_CLASS = "ComparisonTreeSearchDialog"; //$NON-NLS-1$

    // ---- Публичный API ----

    /**
     * Регистрирует Display-фильтр, который перехватывает открытие
     * диалога поиска EDT и добавляет в него наш флажок.
     * Вызывать один раз при старте плагина.
     */
    public static void install(Display display)
    {
        display.addFilter(SWT.Show, event ->
        {
            if (!(event.widget instanceof Shell))
                return;
            Shell shell = (Shell)event.widget;
            if (shell.getData(PATCHED_KEY) != null)
                return; // уже патчили этот экземпляр

            // JFace хранит экземпляр Window/Dialog в shell.getData()
            Object dialog = shell.getData();
            if (dialog == null)
                return;
            if (!dialog.getClass().getName().contains(DIALOG_CLASS))
                return;

            shell.setData(PATCHED_KEY, Boolean.TRUE);
            patchDialog(shell, dialog);
        });
    }

    // ---- Патч диалога ----

    private static void patchDialog(Shell shell, Object dialog)
    {
        Button btnCase = (Button)getField(dialog, "buttonCaseSensitive"); //$NON-NLS-1$
        Button btnNext = (Button)getField(dialog, "buttonSearch"); //$NON-NLS-1$
        Button btnPrev = (Button)getField(dialog, "buttonSearchBack"); //$NON-NLS-1$

        if (btnNext == null || btnPrev == null)
            return;

        // Родительский Composite диалога (GridLayout, 1 колонка)
        Composite parent = btnCase != null ? btnCase.getParent() : btnNext.getParent();

        // Создаём флажок в том же Composite
        Button cbNamesOnly = new Button(parent, SWT.CHECK);
        cbNamesOnly.setText("Только имена объектов"); //$NON-NLS-1$
        cbNamesOnly.setSelection(true); // включён по умолчанию
        cbNamesOnly.setLayoutData(new GridData(
            GridData.BEGINNING, GridData.CENTER, false, false));

        // Помещаем после "Is case sensitive", перед строкой с кнопками
        if (btnCase != null)
            cbNamesOnly.moveBelow(btnCase);

        // Перехватываем кнопки "Далее" и "Назад"
        interceptButton(btnNext, cbNamesOnly, dialog, false);
        interceptButton(btnPrev, cbNamesOnly, dialog, true);

        // Обновляем размер диалога
        parent.layout(true, true);
        shell.pack();
    }

    /**
     * Снимает оригинальные SWT-слушатели с кнопки и добавляет наш:
     * если флажок включён — отдаём управление EDT, иначе — наш поиск.
     */
    private static void interceptButton(Button button, Button cbNamesOnly,
        Object dialog, boolean backward)
    {
        Listener[] original = button.getListeners(SWT.Selection);
        for (Listener l : original)
            button.removeListener(SWT.Selection, l);

        button.addListener(SWT.Selection, event ->
        {
            if (cbNamesOnly.isDisposed() || cbNamesOnly.getSelection())
            {
                // Флажок включён — EDT ищет сам (только имена объектов)
                for (Listener l : original)
                    l.handleEvent(event);
            }
            else
            {
                // Флажок выключен — наш поиск по всем ячейкам всех строк
                performFullTreeSearch(dialog, backward);
            }
        });
    }

    // ---- Поиск по всем ячейкам ----

    private static void performFullTreeSearch(Object dialog, boolean backward)
    {
        Text textFilter = (Text)getField(dialog, "textFilter"); //$NON-NLS-1$
        if (textFilter == null || textFilter.isDisposed())
            return;

        String query = textFilter.getText();
        if (query.isEmpty())
            return;

        Button cbCase = (Button)getField(dialog, "buttonCaseSensitive"); //$NON-NLS-1$
        boolean caseSensitive = cbCase != null && cbCase.getSelection();
        String effectiveQuery = caseSensitive ? query : query.toLowerCase();

        AbstractTreeViewer viewer = getTreeViewerFromDialog(dialog);
        if (viewer == null)
            return;
        Tree tree = (Tree)viewer.getControl();
        List<TreeItem> items = new ArrayList<>();
        collectItems(tree.getItems(), items);
        if (items.isEmpty())
            return;

        // Определяем текущую позицию
        TreeItem[] sel = tree.getSelection();
        int current = sel.length > 0 ? items.indexOf(sel[0]) : -1;

        int n = items.size();
        // backward: шагаем назад (n-1 шаг вперёд по кольцу)
        int step = backward ? n - 1 : 1;

        for (int i = 1; i <= n; i++)
        {
            int idx = (current + i * step) % n;
            TreeItem candidate = items.get(idx);
            if (matches(candidate, effectiveQuery, caseSensitive))
            {
                tree.setSelection(candidate);
                tree.showItem(candidate);
                // Уведомляем JFace-слой об изменении выделения
                viewer.setSelection(new StructuredSelection(candidate.getData()), true);
                clearStatus(dialog);
                return;
            }
        }

        // Ничего не найдено — показываем статус как EDT
        setStatus(dialog, "Совпадений не найдено"); //$NON-NLS-1$
    }

    /** Рекурсивно собирает все видимые строки (только раскрытые ветки). */
    private static void collectItems(TreeItem[] items, List<TreeItem> result)
    {
        for (TreeItem item : items)
        {
            result.add(item);
            collectItems(item.getItems(), result);
        }
    }

    /** Проверяет, содержит ли хотя бы одна ячейка строки искомый текст. */
    private static boolean matches(TreeItem item, String query, boolean caseSensitive)
    {
        int cols = Math.max(1, item.getParent().getColumnCount());
        for (int col = 0; col < cols; col++)
        {
            String text = item.getText(col);
            if (caseSensitive ? text.contains(query)
                              : text.toLowerCase().contains(query))
                return true;
        }
        return false;
    }

    // ---- Статус-строка диалога ----

    private static void setStatus(Object dialog, String message)
    {
        try
        {
            dialog.getClass()
                  .getMethod("setMessage", String.class) //$NON-NLS-1$
                  .invoke(dialog, message);
        }
        catch (Exception ignored)
        {
        }
    }

    private static void clearStatus(Object dialog)
    {
        setStatus(dialog, ""); //$NON-NLS-1$
    }

    // ---- Получение TreeViewer из диалога ----

    private static AbstractTreeViewer getTreeViewerFromDialog(Object dialog)
    {
        // ComparisonTreeSearchDialog → controller → partService → активный редактор
        Object controller = getField(dialog, "controller"); //$NON-NLS-1$
        if (controller == null)
            return null;

        IPartService ps = (IPartService)getField(controller, "partService"); //$NON-NLS-1$
        if (ps == null)
            return null;

        IWorkbenchPart part = ps.getActivePart();
        if (!(part instanceof IEditorPart))
            return null;

        return getTreeViewerFromEditor((IEditorPart)part);
    }

    private static AbstractTreeViewer getTreeViewerFromEditor(IEditorPart editor)
    {
        Object view = getField(editor, "comparisonView"); //$NON-NLS-1$
        if (!(view instanceof DtComparisonView))
            return null;

        Object treeControl = ((DtComparisonView)view).getTreeControl();
        if (treeControl == null)
            return null;

        Object viewer = invokeNoArg(treeControl, "getTreeViewer"); //$NON-NLS-1$
        return (viewer instanceof AbstractTreeViewer) ? (AbstractTreeViewer)viewer : null;
    }

    // ---- Утилиты рефлексии ----

    private static Object getField(Object obj, String name)
    {
        Class<?> cls = obj.getClass();
        while (cls != null)
        {
            try
            {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            }
            catch (NoSuchFieldException ignored)
            {
                cls = cls.getSuperclass();
            }
            catch (Exception ignored)
            {
                return null;
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object o, String name)
    {
        if (o == null)
            return null;
        try
        {
            return o.getClass().getMethod(name).invoke(o);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }
}
