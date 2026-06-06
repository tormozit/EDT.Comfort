package tormozit;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ICheckStateProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IStartup;

/**
 * Перехватчик окон с деревом и полем поиска: Quick Outline, «Редактирование типа данных» (SelectTypeDialog).
 */
public class SmartOutlineHook implements IStartup {

    @Override
    public void earlyStartup() {
        Display.getDefault().asyncExec(() -> {
//          Activator.getDefault().getInjector().injectMembers(this); // Слишком рано?
            install(Display.getDefault());
        });
    }
    
    private static final String PATCHED_KEY = "tormozit.outlinePatched";
    /** Заголовок {@code SelectTypeDialog_title} / {@code TypeDescriptionDialogComponent_DialogTitle}. */
    private static final String SELECT_TYPE_DIALOG_TITLE =
            "\u0420\u0435\u0434\u0430\u043a\u0442\u0438\u0440\u043e\u0432\u0430\u043d\u0438\u0435 \u0442\u0438\u043f\u0430 \u0434\u0430\u043d\u043d\u044b\u0445"; //$NON-NLS-1$

    public static void install(Display display) {
        if (display == null || display.isDisposed()) return;

        Listener listener = new Listener() {
            @Override
            public void handleEvent(Event event) {
                if (!(event.widget instanceof Shell)) return;

                Shell shell = (Shell) event.widget;
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
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;
        int delay = attempt == 0 ? 0 : 80;
        display.timerExec(delay, () -> {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
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
            return false;

        TreeViewer viewer = findTreeViewer(treeWidget, shell, dialog);
        if (viewer == null || viewer.getContentProvider() == null)
            return false;

        String lpName = viewer.getLabelProvider() != null ? viewer.getLabelProvider().getClass().getName() : "";
        String cpName = viewer.getContentProvider().getClass().getName();
        String shellName = shell.getClass().getName();

        if (!isSmartFilterTarget(shell, shellTitle, lpName, cpName, shellName, dialogName))
            return false;

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
        if (title != null && (title.contains(SELECT_TYPE_DIALOG_TITLE) || title.contains("\u0442\u0438\u043f\u0430 \u0434\u0430\u043d\u043d\u044b\u0445"))) //$NON-NLS-1$
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

        ILabelProvider baseLp = createLabelProviderAdapter(rawLp);

        boolean typeTree = isTypeTreeDialog(shellTitle, dialogName);
        SmartOutlineFilter smartFilter = new SmartOutlineFilter(baseLp, typeTree, typeTree);
        smartFilter.setPattern(getFilterPattern(filterControl));

        IStyledLabelProvider innerStyledLp = null;
        if (rawLp instanceof DelegatingStyledCellLabelProvider) {
            innerStyledLp = ((DelegatingStyledCellLabelProvider) rawLp).getStyledStringProvider();
        } else if (rawLp instanceof IStyledLabelProvider) {
            innerStyledLp = (IStyledLabelProvider) rawLp;
        }

        final SmartLabelHighlight highlightControl = installHighlightProvider(viewer, rawLp, baseLp,
                innerStyledLp, getFilterPattern(filterControl), dialogName);
        final boolean aefTree = highlightControl instanceof AefTreeItemHighlight;
        viewer.addFilter(smartFilter);
        if (aefTree)
        {
            AefTreeItemHighlight aef = (AefTreeItemHighlight) highlightControl;
            aef.bindContext(smartFilter);
            aef.apply(viewer, patchedShell);
        }

        // ОПТИМИЗАЦИЯ 1: Устанавливаем компаратор ОДИН раз при инициализации.
        // Переданные кэш-карты обновляются внутри smartFilter, компаратор увидит изменения автоматически.
        viewer.setComparator(new SmartOutlineComparator(smartFilter.getNamePremiumCache(), smartFilter.getParamPremiumCache(), baseLp));

        // Контейнер для хранения ссылки на текущую отложенную задачу (дебаунс)
        final Runnable[] pendingFilterTask = new Runnable[1];

        addFilterModifyListener(filterControl, new ModifyListener() {
            @Override
            public void modifyText(ModifyEvent e) {            
                String pattern = getFilterPattern(filterControl);
                Display display = filterControl.getDisplay();
                
                // ОПТИМИЗАЦИЯ 2: Дебаунс. Отменяем прошлый таймер, если пользователь продолжает быстро печатать
                if (pendingFilterTask[0] != null) {
                    display.timerExec(-1, pendingFilterTask[0]);
                }
                
                pendingFilterTask[0] = new Runnable() {
                    @Override
                    public void run() {
                        Control control = viewer.getControl();
                        if (control == null || control.isDisposed()) return;
                        
                        Tree tree = viewer.getTree();
                        if (tree == null || tree.isDisposed()) return;
                        
                        // ОПТИМИЗАЦИЯ 3: Полностью блокируем перерисовку дерева на уровне ОС.
                        // Никаких промежуточных прыжков скроллбара и старых выделений пользователь не увидит.
                        tree.setRedraw(false);
                        try {
                            // 1. Очищаем кэши и задаем новый текст поиска
                            smartFilter.refreshPattern(pattern);
                            
                            // 2. Паттерн подсветки (styled label — при refresh; AEF — после refresh)
                            if (highlightControl != null)
                                highlightControl.setHighlightPattern(pattern);

                            // 3. Выполняем ровно ОДИН refresh дерева
                            viewer.refresh();

                            if (highlightControl instanceof AefTreeItemHighlight)
                                ((AefTreeItemHighlight) highlightControl).apply(viewer, patchedShell);

                            // 4. Выделение первой строки — только для обычного SWT-дерева (AEF/LWT ломает TreeItem)
                            if (!aefTree)
                                selectFirstVisibleItem(tree);
                        } finally {
                            // Включаем отрисовку обратно. ОС мгновенно отобразит финальный готовый результат
                            tree.setRedraw(true);
                        }
                    }
                };
                
                // Запуск фильтрации с микрозадержкой в 150 мс для плавности ввода
                display.timerExec(150, pendingFilterTask[0]);
            }
        });

        FilterFieldListNavigation.installTreeNavigation(filterControl, viewer.getTree());

        Display display = filterControl.getDisplay();
        filterControl.addDisposeListener(e -> {
            if (pendingFilterTask[0] != null && !display.isDisposed()) {
                display.timerExec(-1, pendingFilterTask[0]);
            }
        });
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
    private static void selectFirstVisibleItem(Control control) {
        if (control == null || control.isDisposed()) return;
        if (!(control instanceof Tree))
            return;
        Tree tree = (Tree) control;
        if (tree.getItemCount() <= 0)
            return;
        try
        {
            TreeItem first = tree.getItem(0);
            if (first == null || first.isDisposed())
                return;
            TreeItem terminal = getFirstTerminalItem(first);
            if (terminal == null || terminal.isDisposed())
                return;
            tree.setSelection(terminal);
            tree.showItem(terminal);
            Event selectionEvent = new Event();
            selectionEvent.widget = tree;
            selectionEvent.item = terminal;
            tree.notifyListeners(SWT.Selection, selectionEvent);
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

    private static SmartLabelHighlight installHighlightProvider(TreeViewer viewer, IBaseLabelProvider rawLp,
            ILabelProvider baseLp, IStyledLabelProvider innerStyledLp, String initialPattern, String dialogName)
    {
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
}