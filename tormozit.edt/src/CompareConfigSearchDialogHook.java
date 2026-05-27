import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.IContentProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartService;
import org.eclipse.ui.IWorkbenchPart;

import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.AbstractDirectPartialModelNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.AbstractNodeWithLabels;

/**
 * Патч диалога поиска EDT (CTRL+F в дереве сравнения).
 *
 * Добавляет флажок "Только имена объектов" (включён по умолчанию).
 * Когда флажок снят — кнопки "Далее"/"Назад" используют наш поиск:
 * обходят всю модель дерева (включая свернутые узлы) и ищут текст во всех ячейках.
 */
public class CompareConfigSearchDialogHook
{
    private static final String PATCHED_KEY = "tormozit.searchPatched"; //$NON-NLS-1$
    private static final String DIALOG_CLASS = "ComparisonTreeSearchDialog"; //$NON-NLS-1$

    public static void install(Display display)
    {
        display.addFilter(SWT.Show, event ->
        {
            if (!(event.widget instanceof Shell))
                return;
            Shell shell = (Shell)event.widget;
            if (shell.getData(PATCHED_KEY) != null)
                return; 

            Object dialog = shell.getData();
            if (dialog == null)
                return;
            if (!dialog.getClass().getName().contains(DIALOG_CLASS))
                return;

            shell.setData(PATCHED_KEY, Boolean.TRUE);
            patchDialog(shell, dialog);
        });
    }

    private static void patchDialog(Shell shell, Object dialog)
    {
        Button btnCase = (Button)getField(dialog, "buttonCaseSensitive"); //$NON-NLS-1$
        Button btnNext = (Button)getField(dialog, "buttonSearch"); //$NON-NLS-1$
        Button btnPrev = (Button)getField(dialog, "buttonSearchBack"); //$NON-NLS-1$

        if (btnNext == null || btnPrev == null)
            return;

        Composite parent = btnCase != null ? btnCase.getParent() : btnNext.getParent();

        Button cbDetailedSearch = new Button(parent, SWT.CHECK);
        cbDetailedSearch.setText("По всем строкам");
        cbDetailedSearch.setToolTipText("Стандартный поиск EDT ищет только по строкам имен объектов. Этот флажок включает просмотр всех строк (Tormozit)");
        cbDetailedSearch.setSelection(true);
        cbDetailedSearch.setLayoutData(new GridData(GridData.BEGINNING, GridData.CENTER, false, false));

        if (btnCase != null)
            cbDetailedSearch.moveBelow(btnCase);
        
        Button cbSearchObjectColumn = new Button(parent, SWT.CHECK);
        cbSearchObjectColumn.setText("По всем колонкам");
        cbSearchObjectColumn.setToolTipText("Дополнительно к поиску в колонках значений еще искать в колонке \"Объект\" (Tormozit)");
        cbSearchObjectColumn.setSelection(true); 
        cbSearchObjectColumn.setLayoutData(new GridData(GridData.BEGINNING, GridData.CENTER, false, false));

        if (btnCase != null)
            cbSearchObjectColumn.moveBelow(btnCase);
        
        interceptButton(btnNext, cbDetailedSearch, dialog, false, cbSearchObjectColumn);
        interceptButton(btnPrev, cbDetailedSearch, dialog, true, cbSearchObjectColumn);

        parent.layout(true, true);
        shell.pack();
    }

    private static void interceptButton(Button button, Button cbDetailedSearch, Object dialog, boolean backward, Button cbSearchObjectColumn)
    {
        Listener[] original = button.getListeners(SWT.Selection);
        for (Listener l : original)
            button.removeListener(SWT.Selection, l);

        button.addListener(SWT.Selection, event ->
        {
            if (!cbDetailedSearch.getSelection())
            {
                for (Listener l : original)
                    l.handleEvent(event);
            }
            else
            {
                performFullTreeSearch(dialog, backward, cbSearchObjectColumn.getSelection());
            }
        });
    }

    // ---- Поиск по всей модели дерева ----

    private static void performFullTreeSearch(Object dialog, boolean backward, boolean searchObjectColumn)
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
            
        IContentProvider cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider))
            return;
        ITreeContentProvider provider = (ITreeContentProvider) cp;

        // Собираем ВСЕ элементы модели (включая нераскрытые)
        List<Object> items = new ArrayList<>();
        Object[] roots = provider.getElements(viewer.getInput());
        if (roots != null)
        {
            for (Object root : roots)
            {
                items.add(root);
                collectModelItems(root, provider, items);
            }
        }
        
        if (items.isEmpty())
            return;

        // Определяем текущую позицию по модели
        ISelection sel = viewer.getSelection();
        int current = -1;
        if (sel instanceof IStructuredSelection && !sel.isEmpty())
        {
            current = items.indexOf(((IStructuredSelection) sel).getFirstElement());
        }

        int n = items.size();
        int step = backward ? n - 1 : 1;

        for (int i = 1; i <= n; i++)
        {
            int idx = (current + i * step) % n;
            Object candidate = items.get(idx);
            
            if (isMatched(candidate, effectiveQuery, caseSensitive, viewer, searchObjectColumn))
            {
                // setSelection с флагом reveal=true заставит дерево само
                // раскрыть все нужные родительские ветки и прокрутить к элементу!
                viewer.setSelection(new StructuredSelection(candidate), true);
                if (!viewer.getSelection().isEmpty())
                {
                    clearStatus(dialog);
                    return;
                }
            }
        }

        setStatus(dialog, "Совпадений не найдено"); //$NON-NLS-1$
    }

    /** Рекурсивно собирает все элементы модели, используя провайдер контента. */
    private static void collectModelItems(Object parent, ITreeContentProvider provider, List<Object> result)
    {
        Object[] children = provider.getChildren(parent);
        if (children != null)
        {
            for (Object child : children)
            {
                result.add(child);
                collectModelItems(child, provider, result);
            }
        }
    }

    /** Проверяет совпадение текста в ячейках модели через LabelProvider. */
    private static boolean isMatched(Object element, String query, boolean caseSensitive, AbstractTreeViewer viewer, boolean searchObjectColumn)
    {
        IBaseLabelProvider baseLabelProvider = viewer.getLabelProvider();
        if (baseLabelProvider instanceof ILabelProvider) {
            String text;
            if (element instanceof AbstractNodeWithLabels) {
                AbstractNodeWithLabels node = (AbstractNodeWithLabels) element; 
                text = node.getSideLabel(ComparisonSide.MAIN);
                if (text != null && (caseSensitive ? text.contains(query) : text.toLowerCase().contains(query)))
                {
                    return true;
                }
                text = node.getSideLabel(ComparisonSide.OTHER);
                if (text != null && (caseSensitive ? text.contains(query) : text.toLowerCase().contains(query)))
                {
                    return true;
                }
                text = node.getSideLabel(ComparisonSide.COMMON_ANCESTOR);
                if (text != null && (caseSensitive ? text.contains(query) : text.toLowerCase().contains(query)))
                {
                    return true;
                }
            }
            if (searchObjectColumn && element instanceof AbstractDirectPartialModelNode) {
                AbstractDirectPartialModelNode node = (AbstractDirectPartialModelNode) element; 
                text = node.getLabel();
                if (text != null && (caseSensitive ? text.contains(query) : text.toLowerCase().contains(query)))
                {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- Статус-строка диалога ----

    private static void setStatus(Object dialog, String message)
    {
        try
        {
            dialog.getClass().getMethod("setMessage", String.class).invoke(dialog, message); //$NON-NLS-1$
        }
        catch (Exception ignored) {}
    }

    private static void clearStatus(Object dialog)
    {
        setStatus(dialog, ""); //$NON-NLS-1$
    }

    // ---- Получение TreeViewer из диалога ----

    private static AbstractTreeViewer getTreeViewerFromDialog(Object dialog)
    {
        Object controller = getField(dialog, "controller"); //$NON-NLS-1$
        if (controller == null) return null;

        IPartService ps = (IPartService)getField(controller, "partService"); //$NON-NLS-1$
        if (ps == null) return null;

        IWorkbenchPart part = ps.getActivePart();
        if (!(part instanceof IEditorPart)) return null;

        return getTreeViewerFromEditor((IEditorPart)part);
    }

    private static AbstractTreeViewer getTreeViewerFromEditor(IEditorPart editor)
    {
        Object view = getField(editor, "comparisonView"); //$NON-NLS-1$
        if (!(view instanceof DtComparisonView)) return null;

        Object treeControl = ((DtComparisonView)view).getTreeControl();
        if (treeControl == null) return null;

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
            catch (NoSuchFieldException ignored) { cls = cls.getSuperclass(); }
            catch (Exception ignored) { return null; }
        }
        return null;
    }

    private static Object invokeNoArg(Object o, String name)
    {
        if (o == null) return null;
        try { return o.getClass().getMethod(name).invoke(o); }
        catch (Exception ignored) { return null; }
    }
}