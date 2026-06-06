package tormozit;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.IDialogSettings; // Добавлен импорт
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.ViewerFilter;
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
 * Патч диалога поиска EDT (CTRL+F в дереве сравнения конфигураций).
 */
public class CompareConfigSearchDialogHook
{
    private static final String PATCHED_KEY = "tormozit.searchPatched"; //$NON-NLS-1$
    private static final String DIALOG_CLASS = "ComparisonTreeSearchDialog"; //$NON-NLS-1$
    
    // Ключи настроек для хранения в dialog_settings.xml
    private static final String SETTINGS_SECTION = "TormozitCompareConfigSearchSettings"; //$NON-NLS-1$
    private static final String KEY_SEARCH_All_rows = "searchAllRows"; //$NON-NLS-1$
    private static final String KEY_SEARCH_All_columns = "searchAllColumns"; //$NON-NLS-1$

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

        // Получаем сохраненные настройки диалога
        IDialogSettings settings = getDialogSettings();

        Button cbSearchAllRows = new Button(parent, SWT.CHECK);
        cbSearchAllRows.setText("По всем строкам");
        cbSearchAllRows.setToolTipText("Стандартный поиск EDT ищет только по строкам имен объектов. Этот флажок включает просмотр всех строк (Comfort)");
        cbSearchAllRows.setLayoutData(new GridData(GridData.BEGINNING, GridData.CENTER, false, false));
        
        // Восстановление состояния при открытии (по умолчанию true, если настройки еще не создавались)
        boolean loadDetailed = settings.get(KEY_SEARCH_All_rows) == null ? true : settings.getBoolean(KEY_SEARCH_All_rows);
        cbSearchAllRows.setSelection(loadDetailed);

        // Сохранение при изменении состояния флажка строк
        cbSearchAllRows.addListener(SWT.Selection, event -> {
            settings.put(KEY_SEARCH_All_rows, cbSearchAllRows.getSelection());
        });

        if (btnCase != null)
            cbSearchAllRows.moveBelow(btnCase);
        
        Button cbSearchAllColumns = new Button(parent, SWT.CHECK);
        cbSearchAllColumns.setText("По всем колонкам");
        cbSearchAllColumns.setToolTipText("Дополнительно к поиску в колонках значений еще искать в колонке \"Объект\" (Comfort)");
        cbSearchAllColumns.setLayoutData(new GridData(GridData.BEGINNING, GridData.CENTER, false, false));

        // Восстановление состояния при открытии (по умолчанию true, если настройки еще не создавались)
        boolean loadObjectCol = settings.get(KEY_SEARCH_All_columns) == null ? true : settings.getBoolean(KEY_SEARCH_All_columns);
        cbSearchAllColumns.setSelection(loadObjectCol);

        // Сохранение при изменении состояния флажка колонок
        cbSearchAllColumns.addListener(SWT.Selection, event -> {
            settings.put(KEY_SEARCH_All_columns, cbSearchAllColumns.getSelection());
        });
        
        if (btnCase != null)
            cbSearchAllColumns.moveBelow(btnCase);
        
        interceptButton(btnNext, cbSearchAllRows, dialog, false, cbSearchAllColumns);
        interceptButton(btnPrev, cbSearchAllRows, dialog, true, cbSearchAllColumns);

        parent.layout(true, true);
        shell.pack();
    }
    
    /**
     * Вспомогательный метод для получения секции настроек плагина.
     */
    private static IDialogSettings getDialogSettings()
    {
        IDialogSettings topSettings = Activator.getDefault().getDialogSettings();
        IDialogSettings section = topSettings.getSection(SETTINGS_SECTION);
        if (section == null)
        {
            section = topSettings.addNewSection(SETTINGS_SECTION);
        }
        return section;
    }

    private static void interceptButton(Button button, Button cbSearchAllRows, Object dialog, boolean backward, Button cbSearchAllColumns)
    {
        Listener[] original = button.getListeners(SWT.Selection);
        for (Listener l : original)
            button.removeListener(SWT.Selection, l);

        button.addListener(SWT.Selection, event ->
        {
            if (!cbSearchAllRows.getSelection())
            {
                for (Listener l : original)
                    l.handleEvent(event);
            }
            else
            {
                performFullTreeSearch(dialog, backward, cbSearchAllColumns.getSelection());
            }
        });
    }

    // ---- Поиск по всей модели дерева ----

    private static void performFullTreeSearch(Object dialog, boolean backward, boolean cbSearchAllColumns)
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
                collectModelItems(root, provider, items, viewer);
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
            
            if (isMatched(candidate, effectiveQuery, caseSensitive, viewer, cbSearchAllColumns))
            {
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
    
    public static boolean isNodeMatchFilters(
        Object element,
        AbstractTreeViewer viewer)
    {
        for (ViewerFilter filter : viewer.getFilters())
        {
            if (!filter.select(viewer, null, element))
            {
                return false;
            }
        }

        return true;
    }
    
    /** Рекурсивно собирает все элементы модели, используя провайдер контента и фильтры. */
    private static void collectModelItems(Object parent, ITreeContentProvider provider, List<Object> result, AbstractTreeViewer viewer)
    {
        Object[] children = provider.getChildren(parent);
        if (children != null)
        {
            for (Object child : children)
            {
                if (isNodeMatchFilters(child, viewer))
                {
                    result.add(child);
                }
                collectModelItems(child, provider, result, viewer);
            }
        }
    }

    /** Проверяет совпадение текста в ячейках модели через LabelProvider. */
    private static boolean isMatched(Object element, String query, boolean caseSensitive, AbstractTreeViewer viewer, boolean cbSearchAllColumns)
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
            if (cbSearchAllColumns && element instanceof AbstractDirectPartialModelNode) {
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