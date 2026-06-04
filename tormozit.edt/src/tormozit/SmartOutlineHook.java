package tormozit;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
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
 * Перехватчик открытия окна Quick Outline для замены стандартного поиска на "Умный".
 * TODO подключить к окну "Открыть объект метаданных" com._1c.g5.v8.dt.md.ui.dialogs.OpenMdObjectSelectionDialog.class
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

    public static void install(Display display) {
        if (display == null || display.isDisposed()) return;

        Listener listener = new Listener() {
            @Override
            public void handleEvent(Event event) {
                if (!(event.widget instanceof Shell)) return;
                
                Shell shell = (Shell) event.widget;
                if (shell.getData(PATCHED_KEY) != null) return;

                display.asyncExec(() -> {
                    if (!shell.isDisposed()) {
                        tryPatchOutline(shell);
                    }
                });
            }
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static void tryPatchOutline(Shell shell) {
        if (shell.getData(PATCHED_KEY) != null)
            return;

        Text filterText = findTextWidget(shell);
        Tree treeWidget = findTreeWidget(shell);

        if (filterText == null || treeWidget == null) return;

        TreeViewer viewer = findTreeViewer(treeWidget, shell);
        if (viewer == null || viewer.getContentProvider() == null) return;

        String lpName = viewer.getLabelProvider() != null ? viewer.getLabelProvider().getClass().getName() : "";
        String cpName = viewer.getContentProvider() != null ? viewer.getContentProvider().getClass().getName() : "";
        String shellName = shell.getClass().getName();
        Object dialog = shell.getData();
        String dialogName = dialog != null ? dialog.getClass().getName() : "";
        
        boolean isOutline = lpName.contains("Outline") || cpName.contains("Outline") 
                         || shellName.contains("Outline") || dialogName.contains("Outline");
        
        if (!isOutline) return;

        shell.setData(PATCHED_KEY, Boolean.TRUE);
        applySmartSearch(viewer, filterText);
    }

    private static TreeViewer findTreeViewer(Tree treeWidget, Shell shell) {
        Composite parent = treeWidget.getParent();
        while (parent != null) {
            Object viewer = Global.invoke(parent, "getTreeViewer");
            if (viewer instanceof TreeViewer) return (TreeViewer) viewer;

            viewer = Global.invoke(parent, "getViewer");
            if (viewer instanceof TreeViewer) return (TreeViewer) viewer;

            for (String fieldName : new String[]{"treeViewer", "viewer", "fTreeViewer", "bslTreeViewer"}) {
                Object fViewer = Global.getField(parent, fieldName);
                if (fViewer instanceof TreeViewer) return (TreeViewer) fViewer;
            }

            if (parent == shell) break;
            parent = parent.getParent();
        }

        Object dialog = shell.getData();
        if (dialog != null) {
            for (String fieldName : new String[]{"treeViewer", "viewer", "fTreeViewer", "outlineViewer"}) {
                Object viewer = Global.getField(dialog, fieldName);
                if (viewer instanceof TreeViewer) return (TreeViewer) viewer;
            }
            Object viewer = Global.invoke(dialog, "getTreeViewer");
            if (viewer instanceof TreeViewer) return (TreeViewer) viewer;
        }

        int[] vitalEvents = { SWT.Selection, SWT.Expand, SWT.Collapse };
        for (int eventType : vitalEvents) {
            for (Listener listener : treeWidget.getListeners(eventType)) {
                Object outer = Global.getField(listener, "this$0");
                if (outer instanceof TreeViewer) return (TreeViewer) outer;

                Object viewer = Global.getField(listener, "viewer");
                if (viewer instanceof TreeViewer) return (TreeViewer) viewer;

                viewer = Global.getField(listener, "treeViewer");
                if (viewer instanceof TreeViewer) return (TreeViewer) viewer;
            }
        }
        return null;
    }

private static void applySmartSearch(TreeViewer viewer, Text filterText) {
        for (ViewerFilter filter : viewer.getFilters()) {
            viewer.removeFilter(filter);
        }

        IBaseLabelProvider rawLp = viewer.getLabelProvider();
        ILabelProvider baseLp = createLabelProviderAdapter(rawLp);
        
        SmartOutlineFilter smartFilter = new SmartOutlineFilter(baseLp);
        smartFilter.setPattern(filterText.getText());
        
        IStyledLabelProvider innerStyledLp = null;
        if (rawLp instanceof DelegatingStyledCellLabelProvider) {
            innerStyledLp = ((DelegatingStyledCellLabelProvider) rawLp).getStyledStringProvider();
        } else if (rawLp instanceof IStyledLabelProvider) {
            innerStyledLp = (IStyledLabelProvider) rawLp;
        }

        final SmartOutlineLabelProvider finalSmartLabelProvider;
        if (innerStyledLp != null) {
            SmartOutlineLabelProvider smartLabelProvider = new SmartOutlineLabelProvider(innerStyledLp);
            smartLabelProvider.setPattern(filterText.getText());
            
            if (rawLp instanceof DelegatingStyledCellLabelProvider) {
                injectStyledStringProvider((DelegatingStyledCellLabelProvider) rawLp, smartLabelProvider);
            } else {
                viewer.setLabelProvider(new DelegatingStyledCellLabelProvider(smartLabelProvider));
            }
            finalSmartLabelProvider = smartLabelProvider;
        } else {
            finalSmartLabelProvider = null;
        }

        viewer.addFilter(smartFilter);
        
        // ОПТИМИЗАЦИЯ 1: Устанавливаем компаратор ОДИН раз при инициализации.
        // Переданные кэш-карты обновляются внутри smartFilter, компаратор увидит изменения автоматически.
        viewer.setComparator(new SmartOutlineComparator(smartFilter.getNamePremiumCache(), smartFilter.getParamPremiumCache(), baseLp));

        // Контейнер для хранения ссылки на текущую отложенную задачу (дебаунс)
        final Runnable[] pendingFilterTask = new Runnable[1];

        filterText.addModifyListener(new ModifyListener() {
            @Override
            public void modifyText(ModifyEvent e) {            
                String pattern = filterText.getText();
                Display display = filterText.getDisplay();
                
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
                            
                            // 2. Обновляем паттерн для подсветки совпадений жирным цветом
                            if (finalSmartLabelProvider != null) {
                                finalSmartLabelProvider.setPattern(pattern);
                            }
                            
                            // 3. Выполняем ровно ОДИН refresh дерева
                            viewer.refresh();
                            
                            // 4. Железно активируем первую видимую строку в уже отфильтрованном списке
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

        FilterFieldListNavigation.installTreeNavigation(filterText, viewer.getTree());

        Display display = filterText.getDisplay();
        filterText.addDisposeListener(e -> {
            if (pendingFilterTask[0] != null && !display.isDisposed()) {
                display.timerExec(-1, pendingFilterTask[0]);
            }
        });
    }
    private static void selectFirstVisibleItem(Control control) {
        if (control == null || control.isDisposed()) return;
        if (control instanceof Tree) {
            Tree tree = (Tree) control;
            if (tree.getItemCount() > 0) {
                TreeItem first = tree.getItem(0);
                TreeItem terminal = getFirstTerminalItem(first);
                if (terminal != null) {
                    tree.setSelection(terminal);
                    tree.showItem(terminal);
                    Event selectionEvent = new Event();
                    selectionEvent.widget = tree;
                    selectionEvent.item = terminal;
                    tree.notifyListeners(SWT.Selection, selectionEvent);
                }
            }
        }
    }
    
    private static TreeItem getFirstTerminalItem(TreeItem item) {
        if (item == null) return null;
        TreeItem[] children = item.getItems();
        if (children.length == 0) return item;
        return getFirstTerminalItem(children[0]);
    }
    
    private static ILabelProvider createLabelProviderAdapter(IBaseLabelProvider rawLp) {
        return new ILabelProvider() {
            @Override
            public String getText(Object element) {
                if (rawLp instanceof ILabelProvider) {
                    return ((ILabelProvider) rawLp).getText(element);
                }
                if (rawLp instanceof DelegatingStyledCellLabelProvider) {
                    IStyledLabelProvider styledProvider = ((DelegatingStyledCellLabelProvider) rawLp).getStyledStringProvider();
                    if (styledProvider != null) {
                        org.eclipse.jface.viewers.StyledString ss = styledProvider.getStyledText(element);
                        return ss != null ? ss.getString() : "";
                    }
                }
                Object text = Global.invoke(rawLp, "getText", element);
                if (text instanceof String) return (String) text;
                return element != null ? element.toString() : "";
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