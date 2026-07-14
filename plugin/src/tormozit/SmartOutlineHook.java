package tormozit;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ICheckStateProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.ui.ActiveShellExpression;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerActivation;
import org.eclipse.ui.handlers.IHandlerService;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;

import org.eclipse.jface.viewers.StyledString;

/**
 * Перехватчик окон с деревом и полем поиска: Quick Outline, «Редактирование типа данных» (SelectTypeDialog).
 */
public class SmartOutlineHook implements IStartup {

    @Override
    public void earlyStartup() {
        Display.getDefault().asyncExec(() -> {
//          Activator.getDefault().getInjector().injectMembers(this); // Слишком рано?
            install(Display.getDefault());
            BslOutlineEventsSupport.installOutlinePageListener();
        });
    }
    
    private static final String PATCHED_KEY = "tormozit.outlinePatched";
    private static final String TYPE_COPY_HOOK_KEY = "tormozit.typeCopyHook"; //$NON-NLS-1$
    private static final String TYPE_COPY_VIEWER_KEY = "tormozit.typeCopyViewer"; //$NON-NLS-1$
    private static final String TYPE_COPY_LP_KEY = "tormozit.typeCopyLabelProvider"; //$NON-NLS-1$
    private static final String CLEAR_BUTTON_KEY = "tormozit.outlineClearButton"; //$NON-NLS-1$
    private static final String HEADER_BUTTON_KEY = "tormozit.outlineHeaderButton"; //$NON-NLS-1$
    private static final String OUTLINE_COMFORT_HEADER_KEY = "tormozit.outlineComfortHeader"; //$NON-NLS-1$
    private static final String OUTLINE_MENU_LAYOUT_KEY = "tormozit.outlineMenuBarOriginalLayout"; //$NON-NLS-1$
    private static final String CLEAR_INSTALLED_KEY = "tormozit.outlineClearInstalled"; //$NON-NLS-1$
    private static final String LAST_PATTERN_KEY = "tormozit.outlineLastPattern"; //$NON-NLS-1$
    private static final String PENDING_CLEAR_SELECTION_KEY = "tormozit.outlinePendingClearSelection"; //$NON-NLS-1$
    private static final String OUTLINE_RECENT_ON_OPEN_KEY = "tormozit.outlineRecentOnOpen"; //$NON-NLS-1$
    /** Заголовок {@code SelectTypeDialog_title} / {@code TypeDescriptionDialogComponent_DialogTitle}. */
    private static final String SELECT_TYPE_DIALOG_TITLE =
            "Редактирование типа данных"; //$NON-NLS-1$

    public static void install(Display display) {
        if (display == null || display.isDisposed()) return;

        Listener listener = new Listener() {
            @Override
            public void handleEvent(Event event) {
                if (!(event.widget instanceof Shell)) return;

                Shell shell = (Shell) event.widget;
                if (shell.isDisposed())
                    return;
                if (shell.getData(PATCHED_KEY) != null) return;

                String title = shell.getText();
                if (!mightBeSmartFilterShell(shell, title))
                    return;

                schedulePatchAttempt(display, shell, 0);
            }
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static void schedulePatchAttempt(Display display, Shell shell, int attempt)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
        {
            return;
        }
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;
        int delay = attempt == 0 ? 0 : 80;
        display.timerExec(delay, () -> {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            tryInstallOutlineHeaderButtons(shell);
            if (tryPatchOutline(shell, attempt))
                return;
            if (attempt < 12)
                schedulePatchAttempt(display, shell, attempt + 1);
        });
    }

    /** @return {@code true}, если патч применён */
    private static boolean tryPatchOutline(Shell shell, int attempt) {
        if (shell.getData(PATCHED_KEY) != null)
            return true;

        Object dialog = findDialog(shell);
        String dialogName = dialog != null ? dialog.getClass().getName() : "";
        String shellTitle = shell.getText();

        Control filterControl = findFilterControl(shell, dialog);
        Tree treeWidget = findTreeWidget(shell);

        if (filterControl == null || treeWidget == null)
        {
            return false;
        }

        TreeViewer viewer = findTreeViewer(treeWidget, shell, dialog);
        if (viewer == null || viewer.getContentProvider() == null)
        {
            return false;
        }

        String lpName = viewer.getLabelProvider() != null ? viewer.getLabelProvider().getClass().getName() : "";
        String cpName = viewer.getContentProvider().getClass().getName();
        String shellName = shell.getClass().getName();

        if (!isSmartFilterTarget(shell, shellTitle, lpName, cpName, shellName, dialogName))
        {
            return false;
        }

        shell.setData(PATCHED_KEY, Boolean.TRUE);

        disableSearchBoxesInShell(shell, shell, viewer);

        applySmartSearch(viewer, filterControl, shellTitle, dialogName, dialog, shell);
        return true;
    }

    private static boolean mightBeSmartFilterShell(Shell shell, String title)
    {
        if (shell.getClass().getName().contains("WorkbenchWindow") //$NON-NLS-1$
                && (title == null || !title.contains(SELECT_TYPE_DIALOG_TITLE)))
            return false;
        if (findTreeWidget(shell) == null)
            return false;
        if (title != null && (title.contains(SELECT_TYPE_DIALOG_TITLE) || title.contains("типа данных"))) //$NON-NLS-1$
            return true;
        Object data = shell.getData();
        if (data != null) {
            String n = data.getClass().getName();
            if (n.contains("Outline") || n.contains("SelectTypeDialog") || n.contains("TypeDescriptionDialog") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    || n.contains("LwtDialogRenderer") || n.contains("aef2.lwt")) //$NON-NLS-4$ //$NON-NLS-5$
                return true;
        }
        if (title != null && title.toLowerCase().contains("outline")) //$NON-NLS-1$
            return true;
        return findFilterControl(shell, findDialog(shell)) != null;
    }

    private static boolean isSmartFilterTarget(Shell shell, String shellTitle, String lpName, String cpName,
            String shellName, String dialogName)
    {
        if (lpName.contains("Outline") || cpName.contains("Outline") //$NON-NLS-1$ //$NON-NLS-2$
                || shellName.contains("Outline") || dialogName.contains("Outline")) //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        if (dialogName.contains("SelectTypeDialog") || dialogName.contains("TypeDescriptionDialog")) //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        if (dialogName.contains("LwtDialogRenderer") || dialogName.contains("LwtDialog")) //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        if (shellTitle != null && shellTitle.contains(SELECT_TYPE_DIALOG_TITLE))
            return true;
        if (lpName.contains("TypeInfo") || cpName.contains("TypeInfo") //$NON-NLS-1$ //$NON-NLS-2$
                || lpName.contains("SelectType") || cpName.contains("SelectType")) //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        for (ViewerFilter f : findViewerFilters(shell))
        {
            if (f.getClass().getName().contains("SelectTypeDialogFilter")) //$NON-NLS-1$
                return true;
        }
        return false;
    }

    private static ViewerFilter[] findViewerFilters(Shell shell)
    {
        Tree tree = findTreeWidget(shell);
        if (tree == null)
            return new ViewerFilter[0];
        TreeViewer viewer = findTreeViewer(tree, shell, findDialog(shell));
        return viewer != null ? viewer.getFilters() : new ViewerFilter[0];
    }

    private static Object findDialog(Shell shell)
    {
        Object data = shell.getData();
        if (data != null)
            return data;
        Object win = shell.getData("org.eclipse.jface.window.Window"); //$NON-NLS-1$
        if (win != null)
            return win;
        Tree tree = findTreeWidget(shell);
        if (tree == null)
            return null;
        int[] events = { SWT.Selection, SWT.Expand, SWT.Collapse, SWT.Modify };
        for (int eventType : events) {
            for (Listener listener : tree.getListeners(eventType)) {
                Object outer = Global.getField(listener, "this$0"); //$NON-NLS-1$
                if (outer != null) {
                    String n = outer.getClass().getName();
                    if (n.contains("SelectTypeDialog") || n.contains("TypeDescriptionDialog")) //$NON-NLS-1$ //$NON-NLS-2$
                        return outer;
                }
            }
        }
        return null;
    }

    private static Object findSearchBox(Object dialog)
    {
        return dialog != null ? Global.getField(dialog, "searchBox") : null; //$NON-NLS-1$
    }

    /** Отключаем авто-поиск SearchBox; {@code DtTreeView$SearchListener} сохраняем для подсветки. */
    private static void disableSearchBoxesInShell(Composite root, Shell shell, TreeViewer viewer)
    {
        if (root == null || root.isDisposed())
            return;
        for (Control child : root.getChildren())
        {
            if (child.getClass().getName().contains("SearchBox")) //$NON-NLS-1$
            {
                Object listener = Global.getField(child, "searchListener"); //$NON-NLS-1$
                if (listener != null && listener.getClass().getName().contains("DtTreeView$SearchListener")) //$NON-NLS-1$
                    storeAefSearchListener(shell, viewer, listener);
                Global.invoke(child, "setSearchListener", (Object) null); //$NON-NLS-1$
                Global.invoke(child, "setRunSearchOnTextChange", Boolean.FALSE); //$NON-NLS-1$
            }
            if (child instanceof Composite)
                disableSearchBoxesInShell((Composite) child, shell, viewer);
        }
    }

    private static void storeAefSearchListener(Shell shell, TreeViewer viewer, Object listener)
    {
        if (shell != null)
            shell.setData(AefTreeItemHighlight.SAVED_LISTENER_KEY, listener);
        if (viewer != null)
        {
            Control control = viewer.getControl();
            if (control != null)
                control.setData(AefTreeItemHighlight.SAVED_LISTENER_KEY, listener);
        }
    }

    private static TreeViewer findTreeViewer(Tree treeWidget, Shell shell, Object dialog)
    {
        TreeViewer viewer = findTreeViewerInParents(treeWidget, shell);
        if (viewer != null)
            return viewer;
        if (dialog == null)
            dialog = findDialog(shell);
        if (dialog != null)
        {
            for (String fieldName : new String[] { "treeViewer", "viewer", "fTreeViewer", "outlineViewer" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {
                Object v = Global.getField(dialog, fieldName);
                if (v instanceof TreeViewer)
                    return (TreeViewer) v;
            }
            Object v = Global.invoke(dialog, "getTreeViewer"); //$NON-NLS-1$
            if (v instanceof TreeViewer)
                return (TreeViewer) v;
        }
        for (int eventType : new int[] { SWT.Selection, SWT.Expand, SWT.Collapse })
        {
            for (Listener listener : treeWidget.getListeners(eventType))
            {
                Object outer = Global.getField(listener, "this$0"); //$NON-NLS-1$
                if (outer instanceof TreeViewer)
                    return (TreeViewer) outer;
                Object v = Global.getField(listener, "viewer"); //$NON-NLS-1$
                if (v instanceof TreeViewer)
                    return (TreeViewer) v;
                v = Global.getField(listener, "treeViewer"); //$NON-NLS-1$
                if (v instanceof TreeViewer)
                    return (TreeViewer) v;
            }
        }
        return null;
    }

    private static TreeViewer findTreeViewerInParents(Tree treeWidget, Shell shell)
    {
        Composite parent = treeWidget.getParent();
        while (parent != null)
        {
            TreeViewer fromDt = treeViewerOnDtTreeView(parent);
            if (fromDt != null)
                return fromDt;
            Object viewer = Global.invoke(parent, "getTreeViewer"); //$NON-NLS-1$
            if (viewer instanceof TreeViewer)
                return (TreeViewer) viewer;
            viewer = Global.invoke(parent, "getViewer"); //$NON-NLS-1$
            if (viewer instanceof TreeViewer)
                return (TreeViewer) viewer;
            for (String fieldName : new String[] { "treeViewer", "viewer", "fTreeViewer", "bslTreeViewer" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {
                Object fViewer = Global.getField(parent, fieldName);
                if (fViewer instanceof TreeViewer)
                    return (TreeViewer) fViewer;
            }
            if (parent == shell)
                break;
            parent = parent.getParent();
        }
        return null;
    }

    private static TreeViewer treeViewerOnDtTreeView(Composite composite)
    {
        if (composite == null || !composite.getClass().getName().contains("DtTreeView")) //$NON-NLS-1$
            return null;
        String key = dtTreeViewerDataKey();
        if (key == null)
            return null;
        Object data = composite.getData(key);
        return data instanceof TreeViewer ? (TreeViewer) data : null;
    }

    private static String dtTreeViewerDataKey()
    {
        try
        {
            Class<?> c = Class.forName("com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView"); //$NON-NLS-1$
            java.lang.reflect.Field f = c.getDeclaredField("TREE_VIEWER_KEY"); //$NON-NLS-1$
            f.setAccessible(true);
            Object key = f.get(null);
            if (key instanceof String)
                return (String) key;
        }
        catch (Exception ignored) {}
        return null;
    }

    private static void applySmartSearch(TreeViewer viewer, Control filterControl, String shellTitle,
            String dialogName, Object dialog, Shell patchedShell)
    {
        for (ViewerFilter filter : viewer.getFilters()) {
            viewer.removeFilter(filter);
        }

        IBaseLabelProvider rawLp = resolveNativeLabelProvider(viewer, dialog, viewer.getLabelProvider());
        restoreCheckStateProvider(viewer, dialog);

        boolean bslQuickOutline = dialogName != null && dialogName.contains("BslQuickOutlinePopup"); //$NON-NLS-1$
        IBaseLabelProvider labelSourceForFilter = rawLp;
        if (bslQuickOutline)
        {
            IBaseLabelProvider bslInner = BslOutlineEventsSupport.getBslOutlineInnerLabelProvider(dialog);
            if (bslInner != null)
                labelSourceForFilter = bslInner;
        }
        ILabelProvider innerFilterLp = createLabelProviderAdapter(labelSourceForFilter);
        BslOutlineEventsSupport.FlatSubscriptionHandlerLabelProvider flatFilterLp = null;
        if (bslQuickOutline)
            flatFilterLp = BslOutlineEventsSupport.createFlatSubscriptionHandlerLabelProvider(
                    innerFilterLp, labelSourceForFilter);
        ILabelProvider baseLp = flatFilterLp != null ? flatFilterLp : innerFilterLp;

        boolean typeTree = isTypeTreeDialog(shellTitle, dialogName);
        SmartOutlineFilter smartFilter = new SmartOutlineFilter(baseLp, typeTree, typeTree);
        if (flatFilterLp != null)
            flatFilterLp.bindFilter(smartFilter);
        if (!typeTree)
            smartFilter.setFlattenWhenFiltered(true);
        smartFilter.setPattern(getFilterPattern(filterControl));

        wrapContentProviderForFlatOutline(viewer, smartFilter, baseLp, typeTree);

        BslOutlineEventsSupport.SubscriptionFlatLabels subscriptionFlatLabels = null;
        if (bslQuickOutline)
        {
            BslOutlineEventsSupport.showEventsInQuickOutline(dialog, viewer);
            subscriptionFlatLabels = BslOutlineEventsSupport.createSubscriptionFlatLabels();
            if (flatFilterLp != null)
                flatFilterLp.bindSubscriptionFlatLabels(subscriptionFlatLabels);
        }

        SmartOutlineFlatContentProvider flatContentProvider = null;
        Object outlineCp = viewer.getContentProvider();
        if (outlineCp instanceof SmartOutlineFlatContentProvider)
        {
            flatContentProvider = (SmartOutlineFlatContentProvider) outlineCp;
            if (bslQuickOutline)
            {
                flatContentProvider.bindFlatLabelContext(subscriptionFlatLabels, labelSourceForFilter,
                        innerFilterLp, viewer, dialog);
            }
            flatContentProvider.rebuildLeafIndex(viewer.getInput());
            if (bslQuickOutline && subscriptionFlatLabels != null)
            {
                BslOutlineEventsSupport.enrichSubscriptionFlatLabels(subscriptionFlatLabels, viewer, dialog,
                        flatContentProvider.getIndexedLeaves(), labelSourceForFilter);
            }
        }
        if (flatContentProvider != null && !typeTree)
            smartFilter.bindFlatContentProvider(flatContentProvider);

        IStyledLabelProvider innerStyledLp = null;
        if (rawLp instanceof DelegatingStyledCellLabelProvider) {
            innerStyledLp = ((DelegatingStyledCellLabelProvider) rawLp).getStyledStringProvider();
        } else if (rawLp instanceof IStyledLabelProvider) {
            innerStyledLp = (IStyledLabelProvider) rawLp;
        }
        if (bslQuickOutline && innerStyledLp == null)
        {
            IBaseLabelProvider bslInner = BslOutlineEventsSupport.getBslOutlineInnerLabelProvider(dialog);
            if (bslInner instanceof IStyledLabelProvider)
                innerStyledLp = (IStyledLabelProvider) bslInner;
        }

        final SmartOutlineFlatContentProvider flatContentProviderRef = flatContentProvider;
        final SmartLabelHighlight highlightControl = installHighlightProvider(viewer, rawLp, baseLp,
                innerStyledLp, getFilterPattern(filterControl), dialogName, dialog, smartFilter,
                labelSourceForFilter, subscriptionFlatLabels);
        final boolean aefTree = highlightControl instanceof AefTreeItemHighlight;
        if (flatContentProviderRef == null || typeTree)
            viewer.addFilter(smartFilter);
        if (aefTree)
        {
            AefTreeItemHighlight aef = (AefTreeItemHighlight) highlightControl;
            aef.bindContext(smartFilter);
            aef.apply(viewer, patchedShell);
        }
        if (typeTree)
            timedApplyTreeExpansion(smartFilter, viewer, "applySmartSearch/initial"); //$NON-NLS-1$

        // ПРИОРИТЕТ 1: Рейтинг Имени (от большего к меньшему)
        viewer.setComparator(new SmartOutlineComparator(smartFilter.getNamePremiumCache(), smartFilter.getParamPremiumCache(), baseLp, flatContentProvider));

        // --- Замена поля фильтра для диалогов выбора типа ---
        final Control fc;
        final FilterInputBox[] typeFilterBoxRef = new FilterInputBox[1];
        
        if (typeTree && filterControl != null) {
            // Находим штатный SearchBox (Composite) в иерархии
            Control searchBoxComposite = filterControl;
            while (searchBoxComposite != null && !searchBoxComposite.isDisposed()
                    && !(searchBoxComposite instanceof com._1c.g5.v8.dt.common.ui.controls.search.SearchBox)) {
                searchBoxComposite = searchBoxComposite.getParent();
            }
            
            if (searchBoxComposite != null && !searchBoxComposite.isDisposed()) {
                Composite parent = searchBoxComposite.getParent();
                Object layoutData = searchBoxComposite.getLayoutData();
                Control sibling = siblingBelow(searchBoxComposite);
                
                // Удаляем штатный SearchBox
                searchBoxComposite.dispose();
                
                // Создаем наш FilterInputBox БЕЗ onSearch — фильтрация через ModifyListener
                FilterInputBox newBox = FilterInputBox.forSelectType(parent, null);
                if (newBox != null) {
                    typeFilterBoxRef[0] = newBox;
                    SearchBox newSearchBox = newBox.widget();
                    if (layoutData != null)
                        newSearchBox.setLayoutData(layoutData);
                    if (sibling != null && !sibling.isDisposed())
                        newSearchBox.moveAbove(sibling);
                    parent.layout(true, true);
                    
                    fc = newBox.inputControl();
                    // Фокус в новое поле
                    newBox.scheduleFocusWhenReady();
                } else {
                    fc = filterControl;
                }
            } else {
                fc = filterControl;
            }
        } else {
            fc = filterControl;
        }

        // Контейнер для хранения ссылки на текущую отложенную задачу (дебаунс)
        final Runnable[] pendingFilterTask = new Runnable[1];
        // Отдельный, более длинный дебаунс для дорогого раскрытия дерева типов — см.
        // scheduleTreeExpansion.
        final Runnable[] pendingExpandTask = new Runnable[1];

        addFilterModifyListener(fc, new ModifyListener() {
            @Override
            public void modifyText(ModifyEvent e) {
                String pattern = getFilterPattern(fc);
//                if (typeFilterBoxRef[0] != null && !pattern.isEmpty()) {
//                    typeFilterBoxRef[0].remember(pattern);
//                }
                Display display = fc.getDisplay();

                // ОПТИМИЗАЦИЯ 2: Дебаунс. Отменяем прошлый таймер
                if (pendingFilterTask[0] != null) {
                    display.timerExec(-1, pendingFilterTask[0]);
                }

                pendingFilterTask[0] = new Runnable() {
                    @Override
                    public void run() {
                        executeSmartFilterUpdate(viewer, smartFilter, highlightControl,
                                flatContentProviderRef, bslQuickOutline, aefTree, typeTree,
                                pattern, patchedShell, dialog, fc, pendingExpandTask);
                    }
                };

                display.timerExec(150, pendingFilterTask[0]);
            }
        });

        fc.addDisposeListener(e -> {
            if (pendingFilterTask[0] != null && !fc.getDisplay().isDisposed()) {
                fc.getDisplay().timerExec(-1, pendingFilterTask[0]);
            }
            if (pendingExpandTask[0] != null && !fc.getDisplay().isDisposed()) {
                fc.getDisplay().timerExec(-1, pendingExpandTask[0]);
            }
        });

        // Навигация и Enter
        if (typeTree) {
            fc.addListener(SWT.Traverse, event -> {
                if (event.detail == SWT.TRAVERSE_RETURN) {
                    event.doit = false;
                    event.detail = SWT.TRAVERSE_NONE;
                    
                    Tree tree = viewer.getTree();
                    if (tree == null || tree.isDisposed()) return;
                    
                    // Если ничего не выделено — выделяем первый видимый элемент
                    if (tree.getSelectionCount() == 0) {
                        keepOrSelectFirstVisibleItem(viewer, smartFilter);
                    }
                    
                    // Переводим фокус в дерево
                    tree.setFocus();
                    tree.showSelection();
                }
            });
            FilterInputBoxListNavigation.installTreeNavigation(fc, viewer.getTree(), null);
            installTypeCopySupport(patchedShell, viewer, baseLp);
        } else {
            FilterInputBoxListNavigation.installTreeNavigation(fc, viewer.getTree(),
                    () -> {
                        if (tryActivateEventFromFilter(viewer, dialog))
                            return true;
                        recordOutlineOpenSelection(viewer, baseLp);
                        return false;
                    });
        }

        if (!typeTree)
        {
            installQuickOutlineHeaderButtons(fc, viewer, patchedShell, dialog, dialogName);
            installOutlineRecentPlacesOnOpen(viewer, baseLp);
        }

        if (bslQuickOutline)
            BslOutlineEventsSupport.installEventHandlerActivation(viewer, dialog, fc);

        if (!typeTree)
            BslSideHintOutlineInstall.installIfBsl(viewer, dialog, dialogName);
    }
    
    /** Временный замер: пользователь сообщил, что раскрытие дерева в «Редактировании типа данных»
     * иногда блокирует ввод — нужно понять, сколько реально занимает сам обход. */
    private static void timedApplyTreeExpansion(SmartOutlineFilter smartFilter, TreeViewer viewer, String where)
    {
        long start = System.nanoTime();
        smartFilter.applyTreeExpansion(viewer);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        Global.tempLog("typetree-expand", //$NON-NLS-1$
            where + " pattern=\"" + smartFilter.getPattern() + "\" elapsedMs=" + elapsedMs); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * По логам обход дерева ({@link #timedApplyTreeExpansion}) при широком фильтре занимает
     * сотни мс — на каждое отдельное нажатие клавиши это ощущается как подвисание ввода, даже
     * с потолком {@code SmartOutlineFilter.MAX_AUTO_EXPAND}. Раскрытие — не то, что нужно видеть
     * на КАЖДОМ промежуточном состоянии набора текста, поэтому раскрываем только когда ввод
     * реально остановился (отдельный, более длинный дебаунс, независимый от дебаунса самой
     * фильтрации — та должна оставаться отзывчивой).
     */
    private static final int TYPE_TREE_EXPAND_DEBOUNCE_MS = 400;

    private static void scheduleTreeExpansion(SmartOutlineFilter smartFilter, TreeViewer viewer,
            Runnable[] pendingExpandTask, String pattern)
    {
        Control control = viewer.getControl();
        if (control == null || control.isDisposed())
            return;
        Display display = control.getDisplay();
        if (pendingExpandTask[0] != null)
            display.timerExec(-1, pendingExpandTask[0]);
        pendingExpandTask[0] = () -> {
            if (control.isDisposed())
                return;
            timedApplyTreeExpansion(smartFilter, viewer, "debounced pattern=\"" + pattern + "\""); //$NON-NLS-1$ //$NON-NLS-2$
        };
        display.timerExec(TYPE_TREE_EXPAND_DEBOUNCE_MS, pendingExpandTask[0]);
    }

    private static void executeSmartFilterUpdate(TreeViewer viewer, SmartOutlineFilter smartFilter,
            SmartLabelHighlight highlightControl, SmartOutlineFlatContentProvider flatContentProvider,
            boolean bslQuickOutline, boolean aefTree, boolean typeTree,
            String pattern, Shell patchedShell, Object dialog, Control filterControl,
            Runnable[] pendingExpandTask) {
        
        Control control = viewer.getControl();
        if (control == null || control.isDisposed()) return;
        
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed()) return;
        
        BslSideHintOutlineInstall.cancelPendingHintUpdate(tree);
        tree.setData(BslSideHintOutlineInstall.SUPPRESS_SELECTION_KEY, Boolean.TRUE);
        tree.setRedraw(false);
        try {
            String lastPattern = tree.getData(LAST_PATTERN_KEY) instanceof String
                ? (String) tree.getData(LAST_PATTERN_KEY) : ""; //$NON-NLS-1$
            boolean clearingFilter = pattern.isEmpty() && !lastPattern.isEmpty();
            IStructuredSelection pendingSelection = takePendingClearSelection(filterControl);
            if (pendingSelection == null && clearingFilter
                && viewer.getSelection() instanceof IStructuredSelection)
                pendingSelection = (IStructuredSelection) viewer.getSelection();
            final IStructuredSelection savedSelection = pendingSelection;

            // 1. Очищаем кэши и задаем новый текст поиска
            smartFilter.refreshPattern(pattern);
            
            // 2. Паттерн подсветки (styled label — при refresh; AEF — после refresh)
            if (highlightControl != null)
                highlightControl.setHighlightPattern(pattern);

            // 3. Обновляем только видимые элементы — плоский список уже пересобран в content provider
            viewer.refresh(true);

            if (typeTree)
                scheduleTreeExpansion(smartFilter, viewer, pendingExpandTask, pattern);

            if (highlightControl instanceof AefTreeItemHighlight)
                ((AefTreeItemHighlight) highlightControl).apply(viewer, patchedShell);

            // 4. Выделение: при очистке — прежняя строка, при фильтре — первая видимая
            if (clearingFilter)
                restoreOutlineSelection(viewer, smartFilter, savedSelection);
            else if (!pattern.isEmpty() && !aefTree)
                keepOrSelectFirstVisibleItem(viewer, smartFilter);

            tree.setData(LAST_PATTERN_KEY, pattern);
        } finally {
            tree.setData(BslSideHintOutlineInstall.SUPPRESS_SELECTION_KEY, null);
            tree.setRedraw(true);
        }
        if (bslQuickOutline)
            BslSideHintOutlineInstall.refreshAfterFilter(viewer, dialog);
    }
    
    private static Control siblingBelow(Control control)
    {
        Composite parent = control.getParent();
        if (parent == null)
            return null;
        Control[] children = parent.getChildren();
        for (int i = 0; i < children.length; i++)
        {
            if (children[i] == control && i + 1 < children.length)
                return children[i + 1];
        }
        return null;
    }
    
    /** Имитирует двойной клик по текущей выделенной строке дерева. */
    private static void performTreeDoubleClick(TreeViewer viewer) {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed()) return;
        TreeItem[] selection = tree.getSelection();
        if (selection.length == 0) return;
        TreeItem item = selection[0];
        Event event = new Event();
        event.type = SWT.MouseDoubleClick;
        event.widget = tree;
        event.item = item;
        event.button = 1;
        event.count = 2;
        tree.notifyListeners(SWT.MouseDoubleClick, event);
    }

    private static void wrapContentProviderForFlatOutline(TreeViewer viewer, SmartOutlineFilter smartFilter,
                                                          ILabelProvider baseLp, boolean typeTree)
    {
        if (typeTree || viewer == null)
            return;
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider))
            return;
        if (cp instanceof SmartOutlineFlatContentProvider)
            return;
        viewer.setContentProvider(new SmartOutlineFlatContentProvider((ITreeContentProvider) cp, smartFilter, baseLp));
    }

    /** Enter / двойной клик в схеме модуля — запись в «Последние места» (не при смене выделения). */
    private static void installOutlineRecentPlacesOnOpen(TreeViewer viewer, ILabelProvider baseLp)
    {
        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(OUTLINE_RECENT_ON_OPEN_KEY)))
            return;
        tree.setData(OUTLINE_RECENT_ON_OPEN_KEY, Boolean.TRUE);
        tree.addListener(SWT.DefaultSelection, event -> recordOutlineOpenSelection(viewer, baseLp));
    }

    private static void recordOutlineOpenSelection(TreeViewer viewer, ILabelProvider baseLp)
    {
        if (viewer == null)
            return;
        if (!(viewer.getSelection() instanceof IStructuredSelection sel))
            return;
        if (sel.isEmpty())
            return;
        RecentPlacesTracker.recordOutlineSelection(viewer, sel.getFirstElement(), baseLp);
    }

    /** Иконка ✕ из {@code SearchBox} в плагине {@code com._1c.g5.v8.dt.common.ui}. */
    private static Image loadClearImage()
    {
        try
        {
            // Загружаем через ImageDescriptor из того же OSGi-bundle, что и SearchBox
            Class<?> sbCls = Class.forName(
                    "com._1c.g5.v8.dt.common.ui.controls.search.SearchBox"); //$NON-NLS-1$
            java.net.URL url = sbCls.getResource("icons/clear.png"); //$NON-NLS-1$
            if (url != null)
                return ImageDescriptor.createFromURL(url).createImage(false);
        }
        catch (Exception ignored) {}
        return null;
    }

    private static void tryInstallOutlineHeaderButtons(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        Object dialog = findDialog(shell);
        if (dialog == null)
            return;
        String dialogName = dialog.getClass().getName();
        String shellTitle = shell.getText();
        if (!mightBeSmartFilterShell(shell, shellTitle))
            return;
        if (isTypeTreeDialog(shellTitle, dialogName))
            return;
        Control filterControl = findFilterControl(shell, dialog);
        if (filterControl == null || Boolean.TRUE.equals(filterControl.getData(CLEAR_INSTALLED_KEY)))
            return;
        if (resolveOutlineToolBar(dialog, filterControl) == null)
            return;
        Tree tree = findTreeWidget(shell);
        TreeViewer viewer = tree != null ? findTreeViewer(tree, shell, dialog) : null;
        installQuickOutlineHeaderButtons(filterControl, viewer, shell, dialog, dialogName);
    }

    private static void installQuickOutlineHeaderButtons(Control filterControl, TreeViewer viewer, Shell shell,
            Object dialog, String dialogName)
    {
        if (filterControl == null || filterControl.isDisposed())
            return;
        if (Boolean.TRUE.equals(filterControl.getData(CLEAR_INSTALLED_KEY)))
            return;

        Composite parent = filterControl.getParent();
        if (parent == null || parent.isDisposed())
            return;

        ToolBar menuBar = resolveOutlineToolBar(dialog, filterControl);
        if (menuBar != null)
        {
            Composite titleArea = findOutlineTitleArea(menuBar);
            if (hasOutlineComfortToolBar(titleArea))
            {
                filterControl.setData(CLEAR_INSTALLED_KEY, Boolean.TRUE);
                return;
            }
            installQuickOutlineHeaderComfortToolBar(filterControl, viewer, shell, dialog, dialogName, menuBar);
            return;
        }

        installQuickOutlineHeaderButtonsFallback(filterControl, viewer, shell, dialog, dialogName, parent);
    }

    /** Отдельный тулбар слева от штатного ▼ ({@link DebugInspectorHook} — не в menuBar: на нём MouseDown → showDialogMenu). */
    private static void installQuickOutlineHeaderComfortToolBar(Control filterControl, TreeViewer viewer, Shell shell,
            Object dialog, String dialogName, ToolBar menuBar)
    {
        Composite titleArea = findOutlineTitleArea(menuBar);
        if (titleArea == null || titleArea.isDisposed())
            return;

        stripComfortItemsFromMenuBar(menuBar);

        boolean bslQuickOutline = dialogName != null && dialogName.contains("BslQuickOutlinePopup"); //$NON-NLS-1$
        Color titleBg = titleArea.getBackground();
        Object menuBarLayout = menuBar.getLayoutData();

        ToolBar comfortBar = new ToolBar(titleArea, SWT.FLAT | SWT.LEFT);
        markOutlineComfortHeader(comfortBar);
        comfortBar.setBackground(titleBg);
        comfortBar.setLayoutData(outlineComfortToolBarGridData());

        Image clearImg = loadClearImage();
        ToolItem clearItem = new ToolItem(comfortBar, SWT.PUSH);
        if (clearImg != null)
        {
            clearItem.setImage(clearImg);
            clearItem.setToolTipText("Очистить фильтр" + Global.pluginSignForTooltip()); //$NON-NLS-1$
            comfortBar.addDisposeListener(e -> clearImg.dispose());
        }
        else
        {
            clearItem.setText("✕"); //$NON-NLS-1$
            clearItem.setToolTipText("Очистить фильтр" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        }

        ToolItem commonItem = null;
        ToolItem detailedItem = null;
        if (bslQuickOutline)
        {
            commonItem = new ToolItem(comfortBar, SWT.PUSH);
            commonItem.setText("Общие ИР"); //$NON-NLS-1$
            commonItem.setToolTipText("Список общих методов в ИР" + Global.pluginSignForTooltip()); //$NON-NLS-1$

            detailedItem = new ToolItem(comfortBar, SWT.PUSH);
            detailedItem.setText("Подробно ИР"); //$NON-NLS-1$
            detailedItem.setToolTipText("Список методов модуля в ИР" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        }

        filterControl.setData(CLEAR_INSTALLED_KEY, Boolean.TRUE);
        comfortBar.setData(CLEAR_BUTTON_KEY, clearItem);
        clearItem.addListener(SWT.Selection, e -> {
            if (viewer != null && viewer.getSelection() instanceof IStructuredSelection)
                filterControl.setData(PENDING_CLEAR_SELECTION_KEY, viewer.getSelection());
            setFilterText(filterControl, ""); //$NON-NLS-1$
            if (!filterControl.isDisposed())
                filterControl.forceFocus();
        });

        if (commonItem != null)
        {
            commonItem.addListener(SWT.Selection, e -> {
                filterControl.getDisplay().asyncExec(() -> {
                    BslXtextEditor editor = IrMethodListHandler.resolveBslEditor(dialog);
                    IrMethodListHandler.openCommonMethods(editor, getFilterPattern(filterControl));
                    if (!filterControl.isDisposed())
                        filterControl.forceFocus();
                });
            });
        }
        if (detailedItem != null)
        {
            detailedItem.addListener(SWT.Selection, e -> {
                filterControl.getDisplay().asyncExec(() -> {
                    BslXtextEditor editor = IrMethodListHandler.resolveBslEditor(dialog);
                    IrMethodListHandler.openModuleMethods(editor, getFilterPattern(filterControl));
                    if (!filterControl.isDisposed())
                        filterControl.forceFocus();
                });
            });
        }

        if (menuBar.getParent() == titleArea)
            comfortBar.moveAbove(menuBar);

        applyOutlineMenuBarGridData(menuBar, menuBarLayout);

        if (titleArea.getLayout() instanceof GridLayout gridLayout)
            gridLayout.numColumns = Math.max(gridLayout.numColumns, bslQuickOutline ? 4 : 3);

        titleArea.layout(true, true);
    }

    private static Composite findOutlineTitleArea(ToolBar menuBar)
    {
        if (menuBar == null || menuBar.isDisposed())
            return null;
        for (Composite parent = menuBar.getParent(); parent != null; parent = parent.getParent())
        {
            if (parent.getLayout() instanceof GridLayout)
                return parent;
        }
        return menuBar.getParent();
    }

    private static boolean hasOutlineComfortToolBar(Composite titleArea)
    {
        if (titleArea == null || titleArea.isDisposed())
            return false;
        for (Control child : titleArea.getChildren())
        {
            if (isOutlineComfortHeader(child))
                return true;
        }
        return false;
    }

    private static void markOutlineComfortHeader(Control control)
    {
        control.setData(OUTLINE_COMFORT_HEADER_KEY, Boolean.TRUE);
    }

    private static boolean isOutlineComfortHeader(Control control)
    {
        return control != null && !control.isDisposed()
            && Boolean.TRUE.equals(control.getData(OUTLINE_COMFORT_HEADER_KEY));
    }

    private static GridData outlineComfortToolBarGridData()
    {
        return new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
    }

    private static void stripComfortItemsFromMenuBar(ToolBar menuBar)
    {
        if (menuBar == null || menuBar.isDisposed())
            return;
        for (ToolItem item : menuBar.getItems())
        {
            if (item.isDisposed())
                continue;
            if (Boolean.TRUE.equals(item.getData(HEADER_BUTTON_KEY)))
                item.dispose();
        }
    }

    private static void saveOutlineMenuBarLayout(ToolBar menuBar, Object menuBarLayout)
    {
        if (menuBar == null || menuBar.isDisposed() || menuBarLayout == null)
            return;
        if (menuBar.getData(OUTLINE_MENU_LAYOUT_KEY) == null)
            menuBar.setData(OUTLINE_MENU_LAYOUT_KEY, menuBarLayout);
    }

    private static void applyOutlineMenuBarGridData(ToolBar menuBar, Object originalLayout)
    {
        if (menuBar == null || menuBar.isDisposed())
            return;
        Object layoutSource = originalLayout;
        if (layoutSource == null)
            layoutSource = menuBar.getData(OUTLINE_MENU_LAYOUT_KEY);
        saveOutlineMenuBarLayout(menuBar, layoutSource);
        GridData gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        if (layoutSource instanceof GridData src)
            gd.verticalIndent = src.verticalIndent;
        else if (menuBar.getLayoutData() instanceof GridData current)
            gd.verticalIndent = current.verticalIndent;
        gd.widthHint = computeToolBarContentWidth(menuBar);
        menuBar.setLayoutData(gd);
    }

    private static void installQuickOutlineHeaderButtonsFallback(Control filterControl, TreeViewer viewer, Shell shell,
            Object dialog, String dialogName, Composite parent)
    {
        boolean bslQuickOutline = dialogName != null && dialogName.contains("BslQuickOutlinePopup"); //$NON-NLS-1$
        int extraButtons = bslQuickOutline ? 2 : 0;

        Image clearImg = loadClearImage();
        Button clear = new Button(parent, SWT.PUSH);
        clear.setData("org.eclipse.e4.ui.css.id", "tormozit-clear-button"); //$NON-NLS-1$ //$NON-NLS-2$
        if (clearImg != null)
        {
            clear.setImage(clearImg);
            clear.setToolTipText("Очистить фильтр" + Global.pluginSignForTooltip()); //$NON-NLS-1$
            clear.addDisposeListener(e -> clearImg.dispose());
        }
        else
        {
            clear.setText("✕"); //$NON-NLS-1$
            clear.setToolTipText("Очистить фильтр" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        }
        configureOutlineHeaderButton(clear);

        Button commonBtn = null;
        Button detailedBtn = null;
        if (bslQuickOutline)
        {
            commonBtn = new Button(parent, SWT.PUSH);
            commonBtn.setText("Общие ИР"); //$NON-NLS-1$
            commonBtn.setToolTipText("Список общих методов в ИР" + Global.pluginSignForTooltip()); //$NON-NLS-1$
            configureOutlineHeaderButton(commonBtn);

            detailedBtn = new Button(parent, SWT.PUSH);
            detailedBtn.setText("Подробно ИР"); //$NON-NLS-1$
            detailedBtn.setToolTipText("Список методов модуля в ИР" + Global.pluginSignForTooltip()); //$NON-NLS-1$
            configureOutlineHeaderButton(detailedBtn);
        }

        org.eclipse.swt.widgets.Layout layout = parent.getLayout();
        if (layout instanceof GridLayout)
        {
            GridLayout gl = (GridLayout) layout;
            Object ld = filterControl.getLayoutData();
            GridData filterGd = ld instanceof GridData ? (GridData) ld : null;
            reserveHeaderColumns(filterGd, gl, 1 + extraButtons);
        }
        else if (!(layout instanceof RowLayout))
        {
            parent.setLayout(new RowLayout(SWT.HORIZONTAL));
        }

        clear.pack();
        if (commonBtn != null)
            commonBtn.pack();
        if (detailedBtn != null)
            detailedBtn.pack();

        Control viewMenuControl = findViewMenuControl(parent, filterControl);
        if (viewMenuControl != null && !viewMenuControl.isDisposed())
        {
            if (detailedBtn != null && !detailedBtn.isDisposed())
                detailedBtn.moveAbove(viewMenuControl);
            if (commonBtn != null && !commonBtn.isDisposed())
                commonBtn.moveAbove(detailedBtn != null ? detailedBtn : viewMenuControl);
            clear.moveAbove(commonBtn != null ? commonBtn : viewMenuControl);
        }

        filterControl.setData(CLEAR_INSTALLED_KEY, Boolean.TRUE);
        parent.setData(CLEAR_BUTTON_KEY, clear);
        clear.addListener(SWT.Selection, e -> {
            if (viewer != null && viewer.getSelection() instanceof IStructuredSelection)
                filterControl.setData(PENDING_CLEAR_SELECTION_KEY, viewer.getSelection());
            setFilterText(filterControl, ""); //$NON-NLS-1$
            if (!filterControl.isDisposed())
                filterControl.forceFocus();
        });

        if (commonBtn != null)
        {
            commonBtn.addListener(SWT.Selection, e -> {
                filterControl.getDisplay().asyncExec(() -> {
                    BslXtextEditor editor = IrMethodListHandler.resolveBslEditor(dialog);
                    IrMethodListHandler.openCommonMethods(editor, getFilterPattern(filterControl));
                    if (!filterControl.isDisposed())
                        filterControl.forceFocus();
                });
            });
        }
        if (detailedBtn != null)
        {
            detailedBtn.addListener(SWT.Selection, e -> {
                filterControl.getDisplay().asyncExec(() -> {
                    BslXtextEditor editor = IrMethodListHandler.resolveBslEditor(dialog);
                    IrMethodListHandler.openModuleMethods(editor, getFilterPattern(filterControl));
                    if (!filterControl.isDisposed())
                        filterControl.forceFocus();
                });
            });
        }

        parent.layout(true, true);
        if (shell != null && !shell.isDisposed())
            shell.layout(true, true);
    }

    private static ToolBar resolveOutlineToolBar(Object dialog, Control filterControl)
    {
        if (dialog != null)
        {
            Object bar = Global.getField(dialog, "toolBar"); //$NON-NLS-1$
            if (bar instanceof ToolBar toolBar && !toolBar.isDisposed())
                return toolBar;
        }
        if (filterControl != null && !filterControl.isDisposed())
        {
            Composite parent = filterControl.getParent();
            if (parent != null && !parent.isDisposed())
            {
                Control found = findViewMenuControl(parent, filterControl);
                if (found instanceof ToolBar toolBar && !toolBar.isDisposed())
                    return toolBar;
            }
        }
        return null;
    }

    private static int computeToolBarContentWidth(ToolBar toolBar)
    {
        if (toolBar == null || toolBar.isDisposed())
            return SWT.DEFAULT;
        toolBar.pack();
        Point size = toolBar.getSize();
        if (size.x > 0)
            return size.x;
        int width = 0;
        for (ToolItem item : toolBar.getItems())
        {
            if (item.isDisposed())
                continue;
            Rectangle bounds = item.getBounds();
            if (bounds.width > 0)
                width += bounds.width;
        }
        return width > 0 ? width : SWT.DEFAULT;
    }

    private static void configureOutlineHeaderButton(Button button)
    {
        button.addListener(SWT.MouseDown, e -> e.doit = false);
        button.setData("__noFocus", Boolean.TRUE); //$NON-NLS-1$
        button.setData(HEADER_BUTTON_KEY, Boolean.TRUE);
        button.setLayoutData(new GridData(SWT.NONE, SWT.CENTER, false, false));
    }

    private static void reserveHeaderColumns(GridData filterGd, GridLayout gl, int columns)
    {
        for (int i = 0; i < columns; i++)
        {
            if (filterGd != null && filterGd.horizontalSpan > 1)
                filterGd.horizontalSpan--;
            gl.numColumns = Math.max(gl.numColumns + 1, 2);
        }
    }

    /** Штатная кнопка ▼ меню окна PopupDialog — sibling поля фильтра в строке заголовка. */
    private static Control findViewMenuControl(Composite parent, Control filterControl)
    {
        if (parent == null || parent.isDisposed())
            return null;
        Control fallback = null;
        for (Control child : parent.getChildren())
        {
            if (child == filterControl || child.isDisposed())
                continue;
            if (Boolean.TRUE.equals(child.getData(HEADER_BUTTON_KEY)))
                continue;
            if (isOutlineComfortHeader(child))
                continue;
            if (child instanceof ToolBar)
                return child;
            if (fallback == null)
                fallback = child;
        }
        return fallback;
    }

    private static IStructuredSelection takePendingClearSelection(Control filterControl)
    {
        if (filterControl == null)
            return null;
        Object pending = filterControl.getData(PENDING_CLEAR_SELECTION_KEY);
        filterControl.setData(PENDING_CLEAR_SELECTION_KEY, null);
        return pending instanceof IStructuredSelection ? (IStructuredSelection) pending : null;
    }

    private static void restoreOutlineSelection(TreeViewer viewer, SmartOutlineFilter smartFilter,
                                                IStructuredSelection selection)
    {
        if (viewer == null || selection == null || selection.isEmpty())
            return;
        Object element = selection.getFirstElement();
        Set<Object> toExpand = new LinkedHashSet<>();
        smartFilter.collectTopLevelExpansion(viewer, toExpand);
        addAncestorChain(viewer, element, toExpand);
        if (!toExpand.isEmpty())
            viewer.setExpandedElements(toExpand.toArray());
        viewer.setSelection(new StructuredSelection(element), true);
    }

    private static void addAncestorChain(TreeViewer viewer, Object element, Set<Object> toExpand)
    {
        if (viewer == null || element == null || toExpand == null)
            return;
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider))
            return;
        ITreeContentProvider tcp = (ITreeContentProvider) cp;
        Object parent = tcp.getParent(element);
        while (parent != null)
        {
            toExpand.add(parent);
            parent = tcp.getParent(parent);
        }
    }

    private static void setFilterText(Control filterControl, String text)
    {
        if (filterControl instanceof Text)
            ((Text) filterControl).setText(text);
        else if (filterControl instanceof StyledText)
            ((StyledText) filterControl).setText(text);
    }

    private static String getFilterPattern(Control filterControl)
    {
        if (filterControl == null || filterControl.isDisposed())
            return ""; //$NON-NLS-1$
        if (filterControl instanceof Text)
            return ((Text) filterControl).getText();
        if (filterControl instanceof StyledText)
            return ((StyledText) filterControl).getText();
        return ""; //$NON-NLS-1$
    }

    private static void addFilterModifyListener(Control filterControl, ModifyListener listener)
    {
        if (filterControl instanceof Text)
            ((Text) filterControl).addModifyListener(listener);
        else if (filterControl instanceof StyledText)
            ((StyledText) filterControl).addModifyListener(listener);
    }
    private static void keepOrSelectFirstVisibleItem(TreeViewer viewer, SmartOutlineFilter smartFilter)
    {
        if (viewer == null)
            return;
        if (isCurrentSelectionStillVisible(viewer))
        {
            Tree tree = viewer.getTree();
            if (tree != null && !tree.isDisposed())
            {
                TreeItem[] selection = tree.getSelection();
                if (selection != null && selection.length > 0 && !selection[0].isDisposed())
                    tree.showItem(selection[0]);
            }
            return;
        }
        selectFirstVisibleItem(viewer, smartFilter);
    }

    private static boolean isCurrentSelectionStillVisible(TreeViewer viewer)
    {
        if (viewer == null)
            return false;
        if (!(viewer.getSelection() instanceof IStructuredSelection sel) || sel.isEmpty())
            return false;
        Object element = sel.getFirstElement();
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return false;
        return findTreeItemForElement(tree.getItems(), element) != null;
    }

    private static TreeItem findTreeItemForElement(TreeItem[] items, Object element)
    {
        if (items == null || element == null)
            return null;
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            if (element.equals(item.getData()))
                return item;
            TreeItem nested = findTreeItemForElement(item.getItems(), element);
            if (nested != null)
                return nested;
        }
        return null;
    }

    private static void selectFirstVisibleItem(TreeViewer viewer, SmartOutlineFilter smartFilter) {
        if (viewer == null)
            return;
        Control control = viewer.getControl();
        if (control == null || control.isDisposed() || !(control instanceof Tree))
            return;
        Tree tree = (Tree) control;
        if (tree.getItemCount() <= 0)
            return;
        try
        {
            TreeItem first = tree.getItem(0);
            if (first == null || first.isDisposed())
                return;
            TreeItem terminal = first;
            if (smartFilter == null || !smartFilter.isFlattenWhenFiltered() || !smartFilter.isFiltering())
                terminal = getFirstTerminalItem(first);
            if (terminal == null || terminal.isDisposed())
                return;
            Object element = terminal.getData();
            if (element != null)
                viewer.setSelection(new StructuredSelection(element), true);
            else
                tree.setSelection(terminal);
            tree.showItem(terminal);
        }
        catch (RuntimeException ignored) {}
    }
    
    private static TreeItem getFirstTerminalItem(TreeItem item) {
        if (item == null) return null;
        TreeItem[] children = item.getItems();
        if (children.length == 0) return item;
        return getFirstTerminalItem(children[0]);
    }
    
    private static IBaseLabelProvider resolveNativeLabelProvider(TreeViewer viewer, Object dialog,
            IBaseLabelProvider viewerLp)
    {
        if (viewerLp instanceof StyledCellLabelProvider)
            return viewerLp;
        if (treeUsesAefViewModels(viewer))
            return viewerLp;

        Object[] roots = { dialog, viewer };
        for (Object root : roots)
        {
            if (root == null)
                continue;
            for (String field : new String[] { "treeComponent", "treeViewer", "fTreeViewer" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                Object comp = Global.getField(root, field);
                if (comp == null)
                    continue;
                Object lp = Global.invoke(comp, "getLabelProvider"); //$NON-NLS-1$
                if (lp instanceof StyledCellLabelProvider)
                    return (IBaseLabelProvider) lp;
            }
        }
        return viewerLp;
    }

    private static void restoreCheckStateProvider(TreeViewer viewer, Object dialog)
    {
        if (viewer == null || dialog == null)
            return;
        for (String field : new String[] { "treeComponent", "treeViewer" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Object comp = Global.getField(dialog, field);
            if (comp == null)
                continue;
            Object csp = Global.invoke(comp, "getCheckStateProvider"); //$NON-NLS-1$
            if (csp instanceof ICheckStateProvider)
            {
                ICheckStateProvider checkState = (ICheckStateProvider) csp;
                if (viewer instanceof CheckboxTreeViewer)
                    ((CheckboxTreeViewer) viewer).setCheckStateProvider(checkState);
                else
                    Global.invoke(viewer, "setCheckStateProvider", checkState); //$NON-NLS-1$
                return;
            }
        }
    }

    private static boolean isAefRenderedTree(TreeViewer viewer, String dialogName, IBaseLabelProvider rawLp)
    {
        if (dialogName != null && (dialogName.contains("LwtDialog") || dialogName.contains("aef2"))) //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        if (rawLp != null && LabelProvider.class.getName().equals(rawLp.getClass().getName()))
            return treeUsesAefViewModels(viewer);
        return false;
    }

    private static boolean treeUsesAefViewModels(TreeViewer viewer)
    {
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof org.eclipse.jface.viewers.ITreeContentProvider))
            return false;
        org.eclipse.jface.viewers.ITreeContentProvider tcp =
                (org.eclipse.jface.viewers.ITreeContentProvider) cp;
        Object[] roots = tcp.getElements(viewer.getInput());
        if (roots == null || roots.length == 0)
            return false;
        return AefTreeItemHighlight.isAefTreeItem(roots[0]);
    }

    private static boolean tryActivateEventFromFilter(TreeViewer viewer, Object dialog)
    {
        return BslOutlineEventsSupport.tryActivateSelectedEvent(viewer, dialog);
    }

    private static SmartLabelHighlight installHighlightProvider(TreeViewer viewer, IBaseLabelProvider rawLp,
            ILabelProvider baseLp, IStyledLabelProvider innerStyledLp, String initialPattern, String dialogName,
            Object dialog, SmartOutlineFilter smartFilter, IBaseLabelProvider bslLabelSourceForFlat,
            BslOutlineEventsSupport.SubscriptionFlatLabels subscriptionFlatLabels)
    {
        boolean bslQuickOutline = dialogName != null && dialogName.contains("BslQuickOutlinePopup"); //$NON-NLS-1$
        if (bslQuickOutline && innerStyledLp != null && rawLp instanceof DelegatingStyledCellLabelProvider)
        {
            IStyledLabelProvider styledForTree = new BslFlatSubscriptionStyledLabelWrapper(innerStyledLp,
                    smartFilter, bslLabelSourceForFlat, subscriptionFlatLabels);
            SmartOutlineLabelProvider smartLabelProvider = new SmartOutlineLabelProvider(styledForTree, baseLp);
            smartLabelProvider.setHighlightPattern(initialPattern);
            injectStyledStringProvider((DelegatingStyledCellLabelProvider) rawLp, smartLabelProvider);
            return smartLabelProvider;
        }
        if (bslQuickOutline && dialog != null)
        {
            StyledCellLabelProvider rebuilt =
                    BslOutlineEventsSupport.rebuildBslQuickOutlineLabelProvider(dialog);
            StyledCellLabelProvider styledBase = rebuilt;
            if (styledBase == null && rawLp instanceof StyledCellLabelProvider)
                styledBase = (StyledCellLabelProvider) rawLp;
            if (styledBase != null)
            {
                SmartStyledCellLabelWrapper wrapper = new SmartStyledCellLabelWrapper(styledBase);
                wrapper.setHighlightPattern(initialPattern);
                if (smartFilter != null && bslLabelSourceForFlat != null)
                    wrapper.setBslFlatSubscriptionLabels(smartFilter, bslLabelSourceForFlat, subscriptionFlatLabels);
                viewer.setLabelProvider(wrapper);
                return wrapper;
            }
        }

        if (isAefRenderedTree(viewer, dialogName, rawLp))
            return new AefTreeItemHighlight(initialPattern);

        if (rawLp instanceof StyledCellLabelProvider && !(rawLp instanceof SmartStyledCellLabelWrapper))
        {
            SmartStyledCellLabelWrapper wrapper = new SmartStyledCellLabelWrapper((StyledCellLabelProvider) rawLp);
            wrapper.setHighlightPattern(initialPattern);
            viewer.setLabelProvider(wrapper);
            return wrapper;
        }

        SmartOutlineLabelProvider smartLabelProvider = innerStyledLp != null
                ? new SmartOutlineLabelProvider(innerStyledLp, null)
                : new SmartOutlineLabelProvider(null, baseLp);
        smartLabelProvider.setHighlightPattern(initialPattern);

        if (innerStyledLp != null && rawLp instanceof DelegatingStyledCellLabelProvider)
            injectStyledStringProvider((DelegatingStyledCellLabelProvider) rawLp, smartLabelProvider);
        else
            viewer.setLabelProvider(new DelegatingStyledCellLabelProvider(smartLabelProvider));
        return smartLabelProvider;
    }

    private static boolean isTypeTreeDialog(String shellTitle, String dialogName)
    {
        if (shellTitle != null && shellTitle.contains(SELECT_TYPE_DIALOG_TITLE))
            return true;
        return dialogName.contains("SelectTypeDialog") || dialogName.contains("TypeDescriptionDialog") //$NON-NLS-1$ //$NON-NLS-2$
                || dialogName.contains("LwtDialog"); //$NON-NLS-1$
    }

    /** Текст выбранного типа для {@link GetRef} и Ctrl+C в диалоге выбора типа. */
    public static String getRefFromTypeDialog(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;
        Object dialog = shell.getData();
        String dialogName = dialog != null ? dialog.getClass().getName() : ""; //$NON-NLS-1$
        if (!isTypeTreeDialog(shell.getText(), dialogName))
            return null;
        Object viewerObj = shell.getData(TYPE_COPY_VIEWER_KEY);
        if (!(viewerObj instanceof TreeViewer))
            return null;
        Object lpObj = shell.getData(TYPE_COPY_LP_KEY);
        if (!(lpObj instanceof ILabelProvider))
            return null;
        IStructuredSelection sel = ((TreeViewer) viewerObj).getStructuredSelection();
        if (sel.isEmpty())
            return null;
        String text = SmartTreeElementLabels.resolve(sel.getFirstElement(), (ILabelProvider) lpObj);
        return text != null && !text.isBlank() ? text.strip() : null;
    }

    private static void installTypeCopySupport(Shell shell, TreeViewer viewer, ILabelProvider labelProvider)
    {
        if (Boolean.TRUE.equals(shell.getData(TYPE_COPY_HOOK_KEY)))
            return;
        shell.setData(TYPE_COPY_VIEWER_KEY, viewer);
        shell.setData(TYPE_COPY_LP_KEY, labelProvider);
        IHandlerService handlerService = PlatformUI.getWorkbench().getService(IHandlerService.class);
        if (handlerService == null)
            return;
        AbstractHandler handler = new AbstractHandler()
        {
            @Override
            public Object execute(ExecutionEvent event)
            {
                copySelectedTypeToClipboard(shell);
                return null;
            }
        };
        IHandlerActivation activation = handlerService.activateHandler(
                "org.eclipse.ui.edit.copy", handler, new ActiveShellExpression(shell)); //$NON-NLS-1$
        shell.setData(TYPE_COPY_HOOK_KEY, Boolean.TRUE);
        shell.addDisposeListener(e -> handlerService.deactivateHandler(activation));
    }

    private static void copySelectedTypeToClipboard(Shell shell)
    {
        String text = getRefFromTypeDialog(shell);
        if (text == null || text.isEmpty())
            return;
        Clipboard clipboard = new Clipboard(shell.getDisplay());
        try
        {
            clipboard.setContents(
                    new Object[] { text },
                    new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            clipboard.dispose();
        }
        ToastNotification.show("Скопировано", text, 4000); //$NON-NLS-1$
    }

    private static ILabelProvider createLabelProviderAdapter(IBaseLabelProvider rawLp) {
        return new ILabelProvider() {
            @Override
            public String getText(Object element) {
                return SmartTreeElementLabels.resolve(element, rawLp);
            }

            @Override
            public org.eclipse.swt.graphics.Image getImage(Object element) {
                if (rawLp instanceof ILabelProvider) {
                    return ((ILabelProvider) rawLp).getImage(element);
                }
                Object img = Global.invoke(rawLp, "getImage", element);
                if (img instanceof org.eclipse.swt.graphics.Image) return (org.eclipse.swt.graphics.Image) img;
                return null;
            }

            @Override public void addListener(org.eclipse.jface.viewers.ILabelProviderListener l) { if (rawLp != null) rawLp.addListener(l); }
            @Override public void dispose() { if (rawLp != null) rawLp.dispose(); }
            @Override public boolean isLabelProperty(Object e, String p) { return rawLp != null && rawLp.isLabelProperty(e, p); }
            @Override public void removeListener(org.eclipse.jface.viewers.ILabelProviderListener l) { if (rawLp != null) rawLp.removeListener(l); }
        };
    }
    
    private static void injectStyledStringProvider(DelegatingStyledCellLabelProvider provider, IStyledLabelProvider smartProvider) {
        Class<?> cls = provider.getClass();
        while (cls != null) {
            for (java.lang.reflect.Field field : cls.getDeclaredFields()) {
                if (IStyledLabelProvider.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        field.set(provider, smartProvider);
                        return;
                    } catch (Exception ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
    }
    
    private static Control findFilterControl(Composite parent, Object dialog)
    {
        if (dialog == null && parent instanceof Shell)
            dialog = findDialog((Shell) parent);
        if (dialog != null)
        {
            StyledText fromDialog = styledTextFromSearchBox(findSearchBox(dialog));
            if (fromDialog != null)
                return fromDialog;
        }

        StyledText styled = findStyledTextWidget(parent);
        if (styled != null)
            return styled;

        Text text = findTextWidget(parent);
        return text;
    }

    private static Text findTextWidget(Composite parent) {
        for (Control control : parent.getChildren()) {
            if (control instanceof Text) return (Text) control;
            if (control instanceof Composite) {
                Text result = findTextWidget((Composite) control);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static StyledText findStyledTextWidget(Composite parent)
    {
        for (Control control : parent.getChildren()) {
            if (control instanceof StyledText)
                return (StyledText) control;
            if (control.getClass().getName().contains("SearchBox")) //$NON-NLS-1$
            {
                StyledText text = styledTextFromSearchBox(control);
                if (text != null)
                    return text;
            }
            if (control instanceof Composite) {
                StyledText result = findStyledTextWidget((Composite) control);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static StyledText styledTextFromSearchBox(Object searchBox)
    {
        if (searchBox == null)
            return null;
        for (String field : new String[] { "text", "searchText", "styledText" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Object text = Global.getField(searchBox, field);
            if (text instanceof StyledText)
                return (StyledText) text;
        }
        return null;
    }

    private static Tree findTreeWidget(Composite parent) {
        for (Control control : parent.getChildren()) {
            if (control instanceof Tree) return (Tree) control;
            if (control instanceof Composite) {
                Tree result = findTreeWidget((Composite) control);
                if (result != null) return result;
            }
        }
        return null;
    }

    /**
     * Обёртка над штатным {@code BslOutlineLabelProvider} внутри {@code DelegatingStyledCellLabelProvider}

     * Quick Outline — здесь реально рисуется текст ячеек.
     */
    private static final class BslFlatSubscriptionStyledLabelWrapper extends LabelProvider implements IStyledLabelProvider
    {

        private final IStyledLabelProvider delegate;
        private final SmartOutlineFilter filter;
        private final IBaseLabelProvider labelSource;
        private final BslOutlineEventsSupport.SubscriptionFlatLabels flatLabels;
        BslFlatSubscriptionStyledLabelWrapper(IStyledLabelProvider delegate, SmartOutlineFilter filter,
                IBaseLabelProvider labelSource, BslOutlineEventsSupport.SubscriptionFlatLabels flatLabels)
        {

            this.delegate = delegate;
            this.filter = filter;
            this.labelSource = labelSource;
            this.flatLabels = flatLabels;
        }

        @Override
        public StyledString getStyledText(Object element)
        {

            StyledString styled = delegate != null ? delegate.getStyledText(element) : null;
            String text = styled != null ? styled.getString() : ""; //$NON-NLS-1$
            if (filter != null && filter.isFlattenWhenFiltered() && filter.isFiltering())
            {

                String flat = BslOutlineEventsSupport.resolveFlatDisplayLabel(element, labelSource, flatLabels);
                if (flat != null)
                    text = flat;
            }

            else if (flatLabels != null)
            {

                String tree = BslOutlineEventsSupport.resolveTreeSubscriptionHandlerLabel(element, labelSource, flatLabels);
                if (tree != null)
                    text = tree;
            }

            if (styled != null && text.equals(styled.getString()))
                return styled;
            return new StyledString(text != null ? text : ""); //$NON-NLS-1$
        }

        @Override
        public Image getImage(Object element)
        {

            return delegate != null ? delegate.getImage(element) : null;
        }

        @Override
        public void dispose()
        {

            if (delegate != null)
                delegate.dispose();
            super.dispose();
        }

    }

}