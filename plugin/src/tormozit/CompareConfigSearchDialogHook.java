package tormozit;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.IntSupplier;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.viewers.IContentProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.search.ui.NewSearchUI;
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
    private static final String SESSION_KEY = "tormozit.compareSearchSession"; //$NON-NLS-1$
    private static final String DIALOG_CLASS = "ComparisonTreeSearchDialog"; //$NON-NLS-1$
    private static final int PROGRESS_INTERVAL_MS = 1000;
    private static final int COLLECT_CANCEL_CHECK_INTERVAL = 256;

    // Ключи настроек для хранения в dialog_settings.xml
    private static final String SETTINGS_SECTION = "TormozitCompareConfigSearchSettings"; //$NON-NLS-1$
    private static final String KEY_SEARCH_All_rows = "searchAllRows"; //$NON-NLS-1$
    private static final String KEY_SEARCH_All_columns = "searchAllColumns"; //$NON-NLS-1$

    // Кэш узлов между открытиями диалога, пока жив редактор сравнения
    private static final Map<IEditorPart, SearchCache> searchCacheByEditor = new WeakHashMap<>();

    private static final class SearchCache
    {
        final List<Object> items;
        final Object input;
        final int filterHash;

        SearchCache(List<Object> items, Object input, int filterHash)
        {
            this.items = items;
            this.input = input;
            this.filterHash = filterHash;
        }
    }

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
        Composite buttonBar = btnNext.getParent();

        IDialogSettings settings = getDialogSettings();

        Button cbSearchAllRows = new Button(parent, SWT.CHECK);
        cbSearchAllRows.setText("По всем строкам");
        cbSearchAllRows.setToolTipText("Стандартный поиск EDT ищет только по строкам имен объектов. Этот флажок включает просмотр всех строк" + Global.pluginSignForTooltip());
        cbSearchAllRows.setLayoutData(new GridData(GridData.BEGINNING, GridData.CENTER, false, false));

        boolean loadDetailed = settings.get(KEY_SEARCH_All_rows) == null ? true : settings.getBoolean(KEY_SEARCH_All_rows);
        cbSearchAllRows.setSelection(loadDetailed);

        cbSearchAllRows.addListener(SWT.Selection, event -> {
            settings.put(KEY_SEARCH_All_rows, cbSearchAllRows.getSelection());
        });

        if (btnCase != null)
            cbSearchAllRows.moveBelow(btnCase);

        Button cbSearchAllColumns = new Button(parent, SWT.CHECK);
        cbSearchAllColumns.setText("По всем колонкам");
        cbSearchAllColumns.setToolTipText("Дополнительно к поиску в колонках значений еще искать в колонке \"Объект\"" + Global.pluginSignForTooltip());
        cbSearchAllColumns.setLayoutData(new GridData(GridData.BEGINNING, GridData.CENTER, false, false));

        boolean loadObjectCol = settings.get(KEY_SEARCH_All_columns) == null ? true : settings.getBoolean(KEY_SEARCH_All_columns);
        cbSearchAllColumns.setSelection(loadObjectCol);

        cbSearchAllColumns.addListener(SWT.Selection, event -> {
            settings.put(KEY_SEARCH_All_columns, cbSearchAllColumns.getSelection());
        });

        if (btnCase != null)
            cbSearchAllColumns.moveBelow(btnCase);

        Button btnFindAll = new Button(buttonBar, SWT.PUSH);
        btnFindAll.setText("\u041D\u0430\u0439\u0442\u0438 \u0432\u0441\u0435"); //$NON-NLS-1$
        btnFindAll.setToolTipText("\u041F\u043E\u043A\u0430\u0437\u0430\u0442\u044C \u0432\u0441\u0435 \u043D\u0430\u0439\u0434\u0435\u043D\u043D\u044B\u0435 \u0443\u0437\u043B\u044B \u0432 \u043F\u0430\u043D\u0435\u043B\u0438 \u0440\u0435\u0437\u0443\u043B\u044C\u0442\u0430\u0442\u043E\u0432 \u043F\u043E\u0438\u0441\u043A\u0430" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        btnFindAll.setLayoutData(new org.eclipse.swt.layout.RowData());

        btnFindAll.moveAbove(btnPrev);

        Text textFilter = (Text)getField(dialog, "textFilter"); //$NON-NLS-1$

        CompareConfigSearchSession session = new CompareConfigSearchSession(shell, dialog, btnNext, btnPrev, btnFindAll, textFilter, getEditorFromDialog(dialog));

        disableTreeDeactivationClearing(dialog);

        btnFindAll.addListener(SWT.Selection, event ->
        {
            if (session.isRunning())
            {
                session.cancel();
                return;
            }
            session.findAndShowAll(cbSearchAllColumns.getSelection());
            session.focusComparisonTree();
        });
        shell.setData(SESSION_KEY, session);
        shell.addListener(SWT.Close, event -> session.onShellClose());

        interceptButton(btnNext, dialog, cbSearchAllRows, session, false, cbSearchAllColumns);
        interceptButton(btnPrev, dialog, cbSearchAllRows, session, true, cbSearchAllColumns);

        installSearchDialogButtonKeepAlive(shell, dialog, session, textFilter, cbSearchAllRows, cbSearchAllColumns, btnCase);

        buttonBar.layout(true, true);
        parent.layout(true, true);
        shell.pack();
    }

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

    private static void interceptButton(Button button, Object dialog, Button cbSearchAllRows, CompareConfigSearchSession session, boolean backward, Button cbSearchAllColumns)
    {
        Listener[] original = button.getListeners(SWT.Selection);
        for (Listener l : original)
            button.removeListener(SWT.Selection, l);

        button.addListener(SWT.Selection, event ->
        {
            if (session.isRunning())
            {
                session.cancel();
                return;
            }
            if (!cbSearchAllRows.getSelection())
            {
                reattachSearchEngineMediator(dialog);
                for (Listener l : original)
                    l.handleEvent(event);
            }
            else
            {
                session.startSearch(backward, cbSearchAllColumns.getSelection());
            }
            session.focusComparisonTree();
            session.refreshSearchButtons();
        });
    }

    /**
     * EDT снимает searchEngineMediator при деактивации дерева (фокус в поле ввода диалога).
     * Отключаем DeactivationListener — PartListener по-прежнему обрабатывает смену редактора.
     */
    private static void disableTreeDeactivationClearing(Object dialog)
    {
        Object controller = getField(dialog, "controller"); //$NON-NLS-1$
        if (controller == null)
            return;

        try
        {
            Field listenersField = controller.getClass().getDeclaredField("comparisonTreeSearchUpdateListeners"); //$NON-NLS-1$
            listenersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> registered = (List<Object>)listenersField.get(controller);
            if (registered == null || registered.isEmpty())
                return;

            List<Object> toRemove = new ArrayList<>();
            List<Object> toKeep = new ArrayList<>();
            for (Object listener : registered)
            {
                if (listener != null && "DeactivationListener".equals(listener.getClass().getSimpleName())) //$NON-NLS-1$
                    toRemove.add(listener);
                else
                    toKeep.add(listener);
            }
            if (toRemove.isEmpty())
                return;

            listenersField.set(controller, toKeep);

            Object mediator = getField(controller, "searchEngineMediator"); //$NON-NLS-1$
            if (mediator != null)
            {
                Class<?> listenerType = Class.forName("com._1c.g5.v8.dt.md.compare.search.IComparisonTreeSearchUpdateListener"); //$NON-NLS-1$
                Method remove = mediator.getClass().getMethod("removeComparisonTreeSearchUpdateListener", listenerType); //$NON-NLS-1$
                for (Object listener : toRemove)
                    remove.invoke(mediator, listener);
            }
        }
        catch (Exception ignored) {}
    }

    private static void reattachSearchEngineMediator(Object dialog)
    {
        Object controller = getField(dialog, "controller"); //$NON-NLS-1$
        if (controller == null)
            return;

        if (getField(controller, "searchEngineMediator") != null) //$NON-NLS-1$
            return;

        IEditorPart editor = getEditorFromDialog(dialog);
        Object mediator = captureSearchEngineMediator(editor);
        if (mediator == null)
            return;

        try
        {
            Class<?> mediatorType = Class.forName("com._1c.g5.v8.dt.internal.compare.ui.search.ComparisonTreeSearchEngineMediator"); //$NON-NLS-1$
            Method setMediator = controller.getClass().getDeclaredMethod("setSearchEngineMediator", mediatorType, boolean.class); //$NON-NLS-1$
            setMediator.setAccessible(true);
            setMediator.invoke(controller, mediator, Boolean.FALSE);
        }
        catch (Exception ignored) {}
    }

    private static Object captureSearchEngineMediator(IEditorPart editor)
    {
        if (editor == null)
            return null;
        try
        {
            Class<?> mediatorType = Class.forName("com._1c.g5.v8.dt.internal.compare.ui.search.ComparisonTreeSearchEngineMediator"); //$NON-NLS-1$
            return editor.getAdapter(mediatorType);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static void installSearchDialogButtonKeepAlive(Shell shell, Object dialog, CompareConfigSearchSession session,
        Text textFilter, Button cbSearchAllRows, Button cbSearchAllColumns, Button btnCase)
    {
        Runnable keepAlive = () ->
        {
            if (shell.isDisposed())
                return;
            reattachSearchEngineMediator(dialog);
            session.refreshSearchButtons();
        };

        shell.addListener(SWT.Activate, event -> shell.getDisplay().asyncExec(keepAlive));

        FocusAdapter focusIn = new FocusAdapter()
        {
            @Override
            public void focusGained(FocusEvent e)
            {
                keepAlive.run();
            }
        };

        if (textFilter != null)
        {
            textFilter.addFocusListener(focusIn);
            textFilter.addModifyListener((ModifyEvent e) -> session.refreshSearchButtons());
        }
        if (cbSearchAllRows != null)
            cbSearchAllRows.addFocusListener(focusIn);
        if (cbSearchAllColumns != null)
            cbSearchAllColumns.addFocusListener(focusIn);
        if (btnCase != null)
            btnCase.addFocusListener(focusIn);

        shell.getDisplay().asyncExec(keepAlive);
    }

    private static int computeTreeCacheKey(AbstractTreeViewer viewer, Object input)
    {
        return Objects.hash(Arrays.hashCode(viewer.getFilters()), System.identityHashCode(input));
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

    private static boolean collectModelItems(
        Object parent,
        ITreeContentProvider provider,
        List<Object> result,
        AbstractTreeViewer viewer,
        CollectProgress progress)
    {
        Object[] children = provider.getChildren(parent);
        if (children != null)
        {
            for (Object child : children)
            {
                if (progress != null && !progress.tick())
                    return false;
                if (isNodeMatchFilters(child, viewer))
                {
                    result.add(child);
                    if (progress != null)
                        progress.nodeAdded();
                }
                if (!collectModelItems(child, provider, result, viewer, progress))
                    return false;
            }
        }
        return true;
    }

    private static final class CollectProgress
    {
        private final int generation;
        private final IntSupplier activeGeneration;
        private final IProgressMonitor monitor;
        private final Runnable onNodeAdded;
        private final Runnable onNodeProcessed;
        private int nodesSinceCheck;

        CollectProgress(int generation, IntSupplier activeGeneration, IProgressMonitor monitor, Runnable onNodeAdded, Runnable onNodeProcessed)
        {
            this.generation = generation;
            this.activeGeneration = activeGeneration;
            this.monitor = monitor;
            this.onNodeAdded = onNodeAdded;
            this.onNodeProcessed = onNodeProcessed;
        }

        boolean tick()
        {
            if (onNodeProcessed != null)
                onNodeProcessed.run();
            if (++nodesSinceCheck < COLLECT_CANCEL_CHECK_INTERVAL)
                return true;
            nodesSinceCheck = 0;
            return !monitor.isCanceled() && generation == activeGeneration.getAsInt();
        }

        void nodeAdded()
        {
            if (onNodeAdded != null)
                onNodeAdded.run();
        }
    }

    private static boolean isMatched(Object element, String query, boolean caseSensitive, IBaseLabelProvider baseLabelProvider, boolean cbSearchAllColumns)
    {
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

    private static String extractNodeLabel(Object node)
    {
        try
        {
            Object styled = Global.invoke(node, "getStyledText"); //$NON-NLS-1$
            if (styled instanceof StyledString ss)
            {
                String text = ss.getString();
                if (text != null && !text.isEmpty())
                    return text;
            }
        }
        catch (Exception ignored) {}
        try
        {
            Object label = Global.invoke(node, "getLabel"); //$NON-NLS-1$
            if (label instanceof String s && !s.isEmpty())
                return s;
        }
        catch (Exception ignored) {}
        return ""; //$NON-NLS-1$
    }

    private static String buildPathForNode(Object element)
    {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (Object cur = element; cur != null; cur = Global.invoke(cur, "getParent")) //$NON-NLS-1$
        {
            String label = extractNodeLabel(cur);
            if (label == null || label.isEmpty())
                continue;
            parts.add(0, label);
        }
        return String.join(".", parts); //$NON-NLS-1$
    }

    private static String getCachedObjectPath(Object parent, Map<Object, String> cache)
    {
        if (parent == null)
            return ""; //$NON-NLS-1$
        return cache.computeIfAbsent(parent, CompareConfigSearchDialogHook::buildPathForNode);
    }

    private static boolean textMatches(String text, String effectiveQuery, boolean caseSensitive)
    {
        return text != null && (caseSensitive ? text.contains(effectiveQuery) : text.toLowerCase().contains(effectiveQuery));
    }

    private static final class ComparisonStatusInfo
    {
        final String status;
        final boolean checkable;

        ComparisonStatusInfo(String status, boolean checkable)
        {
            this.status = status;
            this.checkable = checkable;
        }
    }

    private static ComparisonStatusInfo computeComparisonStatus(Object element)
    {
        String comparisonStatus = ""; //$NON-NLS-1$
        boolean checkable = true;

        if (element instanceof AbstractNodeWithLabels node)
        {
            if (node.hasOnlyOnOneSide(ComparisonSide.OTHER, ComparisonSide.MAIN))
                comparisonStatus = "Добавлено"; //$NON-NLS-1$
            else if (node.hasOnlyOnOneSide(ComparisonSide.MAIN, ComparisonSide.OTHER))
                comparisonStatus = "Удалено"; //$NON-NLS-1$
            else if (node.hasChanged(ComparisonSide.MAIN, ComparisonSide.OTHER))
                comparisonStatus = "Изменено"; //$NON-NLS-1$
            checkable = node.isCheckable();
        }
        else if (element instanceof AbstractDirectPartialModelNode node)
        {
            if (node.hasOnlyOnOneSide(ComparisonSide.OTHER, ComparisonSide.MAIN))
                comparisonStatus = "Добавлено"; //$NON-NLS-1$
            else if (node.hasOnlyOnOneSide(ComparisonSide.MAIN, ComparisonSide.OTHER))
                comparisonStatus = "Удалено"; //$NON-NLS-1$
            else if (node.hasDifferences(ComparisonSide.MAIN, ComparisonSide.OTHER))
                comparisonStatus = "Изменено"; //$NON-NLS-1$
        }

        return new ComparisonStatusInfo(comparisonStatus, checkable);
    }

    private static final class ColumnHit
    {
        final String columnSide;
        final String matchText;

        ColumnHit(String columnSide, String matchText)
        {
            this.columnSide = columnSide;
            this.matchText = matchText;
        }
    }

    private static void collectColumnHits(Object element, String effectiveQuery, boolean caseSensitive,
        boolean searchAllColumns, String headerMain, String headerOther, String headerAncestor,
        String headerObject, List<ColumnHit> hits)
    {
        if (element instanceof AbstractNodeWithLabels node)
        {
            String text = node.getSideLabel(ComparisonSide.MAIN);
            if (textMatches(text, effectiveQuery, caseSensitive))
                hits.add(new ColumnHit(headerMain != null ? headerMain : "MAIN", text)); //$NON-NLS-1$

            text = node.getSideLabel(ComparisonSide.OTHER);
            if (textMatches(text, effectiveQuery, caseSensitive))
                hits.add(new ColumnHit(headerOther != null ? headerOther : "OTHER", text)); //$NON-NLS-1$

            text = node.getSideLabel(ComparisonSide.COMMON_ANCESTOR);
            if (textMatches(text, effectiveQuery, caseSensitive))
                hits.add(new ColumnHit(headerAncestor != null ? headerAncestor : "ОбщийПредок", text)); //$NON-NLS-1$
        }
        if (searchAllColumns && element instanceof AbstractDirectPartialModelNode node)
        {
            String text = node.getLabel();
            if (textMatches(text, effectiveQuery, caseSensitive))
                hits.add(new ColumnHit(headerObject, text));
        }
    }

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

    private static void updateDialog(Object dialog)
    {
        try
        {
            dialog.getClass().getMethod("update").invoke(dialog); //$NON-NLS-1$
        }
        catch (Exception ignored) {}
    }

    private static IEditorPart getEditorFromDialog(Object dialog)
    {
        Object controller = getField(dialog, "controller"); //$NON-NLS-1$
        if (controller == null) return null;

        IPartService ps = (IPartService)getField(controller, "partService"); //$NON-NLS-1$
        if (ps == null) return null;

        IWorkbenchPart part = ps.getActivePart();
        return (part instanceof IEditorPart) ? (IEditorPart)part : null;
    }

    private static AbstractTreeViewer getTreeViewerFromDialog(Object dialog)
    {
        IEditorPart editor = getEditorFromDialog(dialog);
        return editor != null ? getTreeViewerFromEditor(editor) : null;
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

    private static void focusComparisonTree(IEditorPart editor)
    {
        AbstractTreeViewer viewer = getTreeViewerFromEditor(editor);
        if (viewer == null)
            return;
        Control control = viewer.getControl();
        if (control == null || control.isDisposed())
            return;
        Display display = control.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            if (control.isDisposed())
                return;
            try
            {
                editor.setFocus();
            }
            catch (Exception ignored) {}
            if (!control.isDisposed())
                control.setFocus();
        });
    }

    private static String getColumnHeader(IEditorPart editor, ComparisonSide side)
    {
        AbstractTreeViewer viewer = getTreeViewerFromEditor(editor);
        if (viewer == null) return null;
        Tree tree = (Tree) viewer.getControl();
        if (tree == null || tree.isDisposed()) return null;
        TreeColumn[] columns = tree.getColumns();
        int idx;
        switch (side)
        {
            case MAIN:
                idx = columns.length >= 2 ? 1 : -1;
                break;
            case OTHER:
                idx = columns.length >= 3 ? 2 : -1;
                break;
            case COMMON_ANCESTOR:
                idx = columns.length >= 4 ? 3 : -1;
                break;
            default:
                return null;
        }
        if (idx >= 0 && idx < columns.length)
        {
            String text = columns[idx].getText();
            if (text != null && !text.isEmpty()) return text;
        }
        return null;
    }

    private static String getObjectColumnHeader(IEditorPart editor)
    {
        AbstractTreeViewer viewer = getTreeViewerFromEditor(editor);
        if (viewer == null) return "\u041E\u0431\u044A\u0435\u043A\u0442";
        Tree tree = (Tree) viewer.getControl();
        if (tree == null || tree.isDisposed()) return "\u041E\u0431\u044A\u0435\u043A\u0442";
        TreeColumn[] columns = tree.getColumns();
        if (columns.length > 0)
        {
            String text = columns[0].getText();
            if (text != null && !text.isEmpty()) return text;
        }
        return "\u041E\u0431\u044A\u0435\u043A\u0442";
    }

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

    /**
     * Фоновый прерываемый поиск по всему дереву сравнения (флажок «По всем строкам»).
     */
    private static final class CompareConfigSearchSession
    {
        private final Shell shell;
        private final Object dialog;
        private final Button btnNext;
        private final Button btnPrev;
        private final Button btnFindAll;
        private final Text textFilter;

        private volatile Job activeJob;
        private volatile Job findAllJob;
        private volatile int scanned;
        private volatile int total;
        private volatile int collected;
        private volatile int totalProcessed;
        private volatile boolean collecting;
        private volatile boolean running;
        private volatile boolean searchError;
        private int activeGeneration;
        private Runnable progressTick;

        private IEditorPart editorPart;

        private final String btnNextOriginalText;
        private final String btnPrevOriginalText;

        private String btnNextOriginal2;
        private String btnPrevOriginal2;
        private String btnFindAllOriginal2;

        private List<Object> cachedItems;
        private Object cachedInput;
        private int cachedFilterHash;

        CompareConfigSearchSession(Shell shell, Object dialog, Button btnNext, Button btnPrev, Button btnFindAll, Text textFilter, IEditorPart editor)
        {
            this.shell = shell;
            this.dialog = dialog;
            this.btnNext = btnNext;
            this.btnPrev = btnPrev;
            this.btnFindAll = btnFindAll;
            this.textFilter = textFilter;
            this.editorPart = editor;
            this.btnNextOriginalText = btnNext.getText();
            this.btnPrevOriginalText = btnPrev.getText();
        }

        void refreshSearchButtons()
        {
            if (running)
                return;
            boolean canSearch = editorPart != null && getTreeViewerFromEditor(editorPart) != null;
            boolean hasQuery = textFilter != null && !textFilter.isDisposed() && !textFilter.getText().isEmpty();

            if (btnNext != null && !btnNext.isDisposed())
                btnNext.setEnabled(canSearch);
            if (btnPrev != null && !btnPrev.isDisposed())
                btnPrev.setEnabled(canSearch);
            if (btnFindAll != null && !btnFindAll.isDisposed())
                btnFindAll.setEnabled(canSearch && hasQuery);
        }

        void focusComparisonTree()
        {
            CompareConfigSearchDialogHook.focusComparisonTree(editorPart);
        }

        boolean isRunning()
        {
            return running;
        }

        void onShellClose()
        {
            cancel();
        }

        void cancel()
        {
            stopProgressTimer();
            Job job = activeJob;
            activeJob = null;
            if (job != null)
                job.cancel();
            Job jfa = findAllJob;
            findAllJob = null;
            if (jfa != null)
                jfa.cancel();
            if (running)
            {
                running = false;
                activeGeneration++;
            }
            restoreSearchButtons();
        }

        private void invalidateCache()
        {
            cachedItems = null;
            cachedInput = null;
            cachedFilterHash = 0;
        }

        private boolean isCacheValid(Object input, int filterHash)
        {
            return cachedItems != null && cachedInput == input && cachedFilterHash == filterHash;
        }

        private void saveCache(Object input, int filterHash, List<Object> items)
        {
            cachedInput = input;
            cachedFilterHash = filterHash;
            cachedItems = items;
            if (editorPart != null)
                searchCacheByEditor.put(editorPart, new SearchCache(items, input, filterHash));
        }

        void startSearch(boolean backward, boolean searchAllColumns)
        {
            cancel();
            activeGeneration++;

            Text textFilter = (Text)getField(dialog, "textFilter"); //$NON-NLS-1$
            if (textFilter == null || textFilter.isDisposed())
                return;

            String query = textFilter.getText();
            if (query.isEmpty())
                return;

            Button cbCase = (Button)getField(dialog, "buttonCaseSensitive"); //$NON-NLS-1$
            boolean caseSensitive = cbCase != null && cbCase.getSelection();
            String effectiveQuery = caseSensitive ? query : query.toLowerCase();

            AbstractTreeViewer viewer = getTreeViewerFromEditor(editorPart);
            if (viewer == null)
                return;

            IContentProvider cp = viewer.getContentProvider();
            if (!(cp instanceof ITreeContentProvider))
                return;
            ITreeContentProvider provider = (ITreeContentProvider) cp;

            IBaseLabelProvider labelProvider = viewer.getLabelProvider();
            ISelection sel = viewer.getSelection();
            Object input = viewer.getInput();
            int filterHash = computeTreeCacheKey(viewer, input);
            boolean cacheHit = isCacheValid(input, filterHash);

            if (!cacheHit && editorPart != null)
            {
                SearchCache cached = searchCacheByEditor.get(editorPart);
                if (cached != null && cached.input == input && cached.filterHash == filterHash)
                {
                    cachedInput = cached.input;
                    cachedFilterHash = cached.filterHash;
                    cachedItems = cached.items;
                    cacheHit = true;
                }
            }

            final boolean hasCachedItems = cacheHit;

            final int generation = activeGeneration;
            running = true;
            collecting = !hasCachedItems;
            searchError = false;
            scanned = 0;
            total = hasCachedItems ? cachedItems.size() : 0;
            collected = 0;
            totalProcessed = 0;

            setSearchButtonsToCancelMode();
            startProgressTimer(generation);

            if (Global.isLogEnabled())
                Global.log("CompareSearch", "start query=\"" + query + "\" backward=" + backward + " allColumns=" + searchAllColumns + " cacheHit=" + cacheHit); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            Job job = new Job("Поиск по дереву сравнения...") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    try
                    {
                        if (generation != activeGeneration)
                            return Status.CANCEL_STATUS;

                        List<Object> items;
                        if (hasCachedItems)
                        {
                            items = cachedItems;
                        }
                        else
                        {
                            long t0 = System.currentTimeMillis();
                            items = new ArrayList<>();
                            CollectProgress progress = new CollectProgress(
                                generation,
                                () -> activeGeneration,
                                monitor,
                                () -> collected++,
                                () -> totalProcessed++);

                            Object[] roots = provider.getElements(input);
                            if (roots != null)
                            {
                                for (Object root : roots)
                                {
                                    if (!progress.tick())
                                        return finishCancelled(generation);
                                    items.add(root);
                                    progress.nodeAdded();
                                    if (!collectModelItems(root, provider, items, viewer, progress))
                                        return finishCancelled(generation);
                                }
                            }
                            long collectMs = System.currentTimeMillis() - t0;

                            if (Global.isLogEnabled())
                                Global.log("CompareSearch", "collected " + items.size() + " items in " + collectMs + "ms"); //$NON-NLS-1$ //$NON-NLS-2$

                            saveCache(input, filterHash, items);
                            collecting = false;
                        }

                        int n = items.size();
                        total = n;
                        scanned = 0;

                        if (n <= 0)
                        {
                            finishOnUi(generation, false, false);
                            return Status.OK_STATUS;
                        }

                        int startIdx = (sel instanceof IStructuredSelection && !sel.isEmpty())
                            ? items.indexOf(((IStructuredSelection) sel).getFirstElement())
                            : -1;

                        monitor.beginTask("Поиск по дереву сравнения...", n); //$NON-NLS-1$

                        for (int i = 1; i <= n; i++)
                        {
                            if (monitor.isCanceled() || generation != activeGeneration)
                                return finishCancelled(generation);

                            int idx = backward
                                ? Math.floorMod(startIdx - i, n)
                                : (startIdx + i) % n;
                            Object candidate = items.get(idx);

                            if (isMatched(candidate, effectiveQuery, caseSensitive, labelProvider, searchAllColumns))
                            {
                                Object found = candidate;
                                Display.getDefault().asyncExec(() ->
                                {
                                    if (generation != activeGeneration || textFilter.isDisposed())
                                        return;
                                    viewer.setSelection(new StructuredSelection(found), true);
                                    finishOnUi(generation, false, true);
                                });

                                if (Global.isLogEnabled())
                                    Global.log("CompareSearch", "found at i=" + i + " idx=" + idx); //$NON-NLS-1$ //$NON-NLS-2$

                                monitor.done();
                                return Status.OK_STATUS;
                            }

                            scanned = i;

                            if (Global.isLogEnabled() && i % 500 == 0)
                                Global.log("CompareSearch", "progress " + i + "/" + n); //$NON-NLS-1$ //$NON-NLS-2$

                            monitor.worked(1);
                        }

                        if (Global.isLogEnabled())
                            Global.log("CompareSearch", "no match after " + n + " items"); //$NON-NLS-1$

                        finishOnUi(generation, false, false);
                        monitor.done();
                        return Status.OK_STATUS;
                    }
                    catch (Throwable t)
                    {
                        searchError = true;
                        if (Global.isLogEnabled())
                            Global.log("CompareSearch", "error: " + t.getMessage()); //$NON-NLS-1$
                        return finishCancelled(generation);
                    }
                }
            };
            activeJob = job;
            job.setSystem(true);
            job.schedule();
        }

        void findAndShowAll(boolean searchAllColumns)
        {
            cancel();

            Text textFilter = (Text)getField(dialog, "textFilter"); //$NON-NLS-1$
            if (textFilter == null || textFilter.isDisposed())
                return;
            String query = textFilter.getText();
            if (query.isEmpty())
                return;

            Button cbCase = (Button)getField(dialog, "buttonCaseSensitive"); //$NON-NLS-1$
            boolean caseSensitive = cbCase != null && cbCase.getSelection();

            if (editorPart == null)
                return;

            AbstractTreeViewer viewer = getTreeViewerFromEditor(editorPart);
            if (viewer == null)
                return;

            IContentProvider cp = viewer.getContentProvider();
            if (!(cp instanceof ITreeContentProvider))
                return;
            ITreeContentProvider provider = (ITreeContentProvider)cp;

            Object input = viewer.getInput();
            int filterHash = computeTreeCacheKey(viewer, input);

            String headerMain = getColumnHeader(editorPart, ComparisonSide.MAIN);
            String headerOther = getColumnHeader(editorPart, ComparisonSide.OTHER);
            String headerAncestor = getColumnHeader(editorPart, ComparisonSide.COMMON_ANCESTOR);
            String headerObject = getObjectColumnHeader(editorPart);

            boolean cacheHit = isCacheValid(input, filterHash);

            if (!cacheHit && editorPart != null)
            {
                SearchCache cached = searchCacheByEditor.get(editorPart);
                if (cached != null && cached.input == input && cached.filterHash == filterHash)
                {
                    cachedInput = cached.input;
                    cachedFilterHash = cached.filterHash;
                    cachedItems = cached.items;
                    cacheHit = true;
                }
            }

            final boolean hasCachedItems = cacheHit;
            final String effectiveQuery = caseSensitive ? query : query.toLowerCase();

            activeGeneration++;
            final int generation = activeGeneration;
            running = true;
            collecting = !hasCachedItems;
            searchError = false;
            scanned = 0;
            total = hasCachedItems ? cachedItems.size() : 0;
            collected = 0;
            totalProcessed = 0;
            setSearchButtonsToCancelMode();
            startProgressTimer(generation);

            Job job = new Job("Поиск по дереву сравнения...") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    try
                    {
                        if (generation != activeGeneration)
                            return Status.CANCEL_STATUS;

                        List<Object> items;
                        if (hasCachedItems)
                        {
                            items = cachedItems;
                        }
                        else
                        {
                            items = new ArrayList<>();
                            collecting = true;
                            collected = 0;
                            totalProcessed = 0;
                            CollectProgress progress = new CollectProgress(generation,
                                () -> activeGeneration, monitor,
                                () -> collected++,
                                () -> totalProcessed++);
                            Object[] roots = provider.getElements(input);
                            if (roots != null)
                            {
                                for (Object root : roots)
                                {
                                    if (!progress.tick())
                                        return Status.CANCEL_STATUS;
                                    items.add(root);
                                    progress.nodeAdded();
                                    if (!collectModelItems(root, provider, items, viewer, progress))
                                        return Status.CANCEL_STATUS;
                                }
                            }
                            saveCache(input, filterHash, items);
                            collecting = false;
                        }

                        int n = items.size();
                        total = n;
                        scanned = 0;
                        monitor.beginTask("Фильтрация...", n); //$NON-NLS-1$

                        List<CompareSearchMatch> matches = buildFindAllMatches(items, effectiveQuery,
                            caseSensitive, searchAllColumns, headerMain, headerOther,
                            headerAncestor, headerObject, generation, monitor);

                        if (generation != activeGeneration)
                            return Status.CANCEL_STATUS;

                        Display.getDefault().asyncExec(() ->
                        {
                            if (generation != activeGeneration)
                                return;
                            showFindAllResultsUI(matches, query);
                        });

                        monitor.done();
                        return Status.OK_STATUS;
                    }
                    catch (Throwable t)
                    {
                        if (Global.isLogEnabled())
                            Global.log("CompareSearch", "findAll error: " + t.getMessage());
                        searchError = true;
                        Display.getDefault().asyncExec(() ->
                        {
                            if (generation != activeGeneration)
                                return;
                            stopProgressTimer();
                            findAllJob = null;
                            running = false;
                            restoreSearchButtons();
                            setStatus(dialog, "Ошибка: " + t.getMessage()); //$NON-NLS-1$
                        });
                        return Status.CANCEL_STATUS;
                    }
                }
            };
            findAllJob = job;
            job.setSystem(true);
            job.schedule();
        }

        private List<CompareSearchMatch> buildFindAllMatches(List<Object> items, String effectiveQuery,
            boolean caseSensitive, boolean searchAllColumns,
            String headerMain, String headerOther, String headerAncestor, String headerObject,
            int generation, IProgressMonitor monitor)
        {
            List<CompareSearchMatch> matches = new ArrayList<>();
            Map<Object, String> pathCache = new IdentityHashMap<>();
            List<ColumnHit> columnHits = new ArrayList<>(4);
            int n = items.size();
            for (int i = 0; i < n; i++)
            {
                if ((monitor != null && monitor.isCanceled()) || generation != activeGeneration)
                    return matches;

                scanned = i + 1;
                Object element = items.get(i);

                columnHits.clear();
                collectColumnHits(element, effectiveQuery, caseSensitive, searchAllColumns,
                    headerMain, headerOther, headerAncestor, headerObject, columnHits);

                if (!columnHits.isEmpty())
                {
                    String propertyName = extractNodeLabel(element);
                    Object parent = Global.invoke(element, "getParent"); //$NON-NLS-1$
                    String objectPath = getCachedObjectPath(parent, pathCache);
                    ComparisonStatusInfo statusInfo = computeComparisonStatus(element);

                    for (ColumnHit hit : columnHits)
                    {
                        matches.add(new CompareSearchMatch(element, objectPath, propertyName,
                            hit.columnSide, hit.matchText, statusInfo.status, statusInfo.checkable));
                    }
                }

                if (monitor != null)
                    monitor.worked(1);
            }
            return matches;
        }

        private void showFindAllResultsUI(List<CompareSearchMatch> matches, String query)
        {
            stopProgressTimer();
            findAllJob = null;
            running = false;
            restoreSearchButtons();
            if (!matches.isEmpty())
            {
                setStatus(dialog, "Найдено: " + matches.size()); //$NON-NLS-1$
                doShowFindAllResults(matches, query);
            }
            else
                setStatus(dialog, "Не найдено"); //$NON-NLS-1$
        }

        private void doShowFindAllResults(List<CompareSearchMatch> matches, String query)
        {
            CompareSearchResult result = new CompareSearchResult(matches, editorPart);
            result.setQueryText(query);
            CompareSearchQuery searchQuery = new CompareSearchQuery();
            result.setQuery(searchQuery);
            searchQuery.setSearchResult(result);
            NewSearchUI.runQueryInBackground(searchQuery);
        }

        private List<Object> collectAllItems(ITreeContentProvider provider, Object input, AbstractTreeViewer viewer)
        {
            List<Object> items = new ArrayList<>();
            Object[] roots = provider.getElements(input);
            if (roots != null)
            {
                for (Object root : roots)
                {
                    items.add(root);
                    collectModelItems(root, provider, items, viewer, null);
                }
            }
            return items;
        }

        private IStatus finishCancelled(int generation)
        {
            if (Global.isLogEnabled())
                Global.log("CompareSearch", "cancelled"); //$NON-NLS-1$
            finishOnUi(generation, true, false);
            return Status.CANCEL_STATUS;
        }

        private void finishOnUi(int generation, boolean cancelled, boolean found)
        {
            Display display = shell != null && !shell.isDisposed() ? shell.getDisplay() : null;
            if (display == null || display.isDisposed())
            {
                running = false;
                activeJob = null;
                stopProgressTimer();
                return;
            }
            display.asyncExec(() ->
            {
                if (generation != activeGeneration)
                    return;
                stopProgressTimer();
                activeJob = null;
                running = false;
                if (shell.isDisposed())
                    return;
                if (cancelled)
                {
                    if (searchError)
                        setStatus(dialog, "Остановлено по ошибке (смотри журнал Комфорт)"); //$NON-NLS-1$
                    else
                        clearStatus(dialog);
                }
                else if (found)
                    setStatus(dialog, "Найдено"); //$NON-NLS-1$
                else
                    setStatus(dialog, "Не найдено"); //$NON-NLS-1$
                restoreSearchButtons();
                updateDialog(dialog);
                refreshSearchButtons();
            });
        }

        private void setSearchButtonsToCancelMode()
        {
            if (btnNext != null && !btnNext.isDisposed())
            {
                btnNextOriginal2 = btnNext.getText();
                btnNext.setText("\u041E\u0442\u043C\u0435\u043D\u0430");
                btnNext.setEnabled(true);
            }
            if (btnPrev != null && !btnPrev.isDisposed())
            {
                btnPrevOriginal2 = btnPrev.getText();
                btnPrev.setText("\u041E\u0442\u043C\u0435\u043D\u0430");
                btnPrev.setEnabled(true);
            }
            if (btnFindAll != null && !btnFindAll.isDisposed())
            {
                btnFindAllOriginal2 = btnFindAll.getText();
                btnFindAll.setText("\u041E\u0442\u043C\u0435\u043D\u0430");
                btnFindAll.setEnabled(true);
            }
        }

        private void restoreSearchButtons()
        {
            if (btnNext != null && !btnNext.isDisposed())
                btnNext.setText(btnNextOriginal2 != null ? btnNextOriginal2 : btnNextOriginalText);
            if (btnPrev != null && !btnPrev.isDisposed())
                btnPrev.setText(btnPrevOriginal2 != null ? btnPrevOriginal2 : btnPrevOriginalText);
            if (btnFindAll != null && !btnFindAll.isDisposed())
                btnFindAll.setText(btnFindAllOriginal2 != null ? btnFindAllOriginal2 : "Найти все"); //$NON-NLS-1$
            refreshSearchButtons();
        }

        private void startProgressTimer(int generation)
        {
            stopProgressTimer();
            Display display = shell.getDisplay();
            progressTick = () ->
            {
                if (generation != activeGeneration || !running || shell.isDisposed())
                    return;
                if (collecting)
                    setStatus(dialog, "Сбор узлов… " + collected + " (обработано " + totalProcessed + ")"); //$NON-NLS-1$
                else
                    setStatus(dialog, total > 0
                        ? "Поиск… " + scanned + "/" + total //$NON-NLS-1$
                        : "Поиск…"); //$NON-NLS-1$
                if (running && generation == activeGeneration && !shell.isDisposed())
                    display.timerExec(PROGRESS_INTERVAL_MS, progressTick);
            };
            display.timerExec(PROGRESS_INTERVAL_MS, progressTick);
        }

        private void stopProgressTimer()
        {
            if (progressTick == null)
                return;
            Display display = shell != null && !shell.isDisposed() ? shell.getDisplay() : null;
            if (display != null && !display.isDisposed())
                display.timerExec(-1, progressTick);
            progressTick = null;
        }
    }
}
