package tormozit.edt.applications;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

    /**
     * Хук панели «Приложения» EDT.
     *
     * <p>Добавляет в панель:
     * <ul>
     *   <li>Многоколоночное дерево: колонка 0 делегирует к оригинальному
     *       {@code DecoratingStyledCellLabelProvider} EDT, колонка 1 показывает
     *       «Начало сеанса конфигуратора» (временно отключено). Создание {@code TreeViewerColumn}
     *       вызывает {@code clearLegacyRenderer()}, который уничтожает
     *       {@code OwnerDrawLabelProvider} EDT; мы сохраняем ссылку на старый
     *       провайдер и продолжаем делегировать к его {@code update(cell)}.</li>
     *   <li>Кнопку «Отключить конфигуратор» в тулбар панели.</li>
     *   <li>Пункт «Отключить конфигуратор» в контекстное меню списка.</li>
     * </ul>
     *
     * <p>Временная модификация: обращения к ComConnectionRegistry удалены.
     * Подключение конфигуратора выполняется EDT через createClientAndConnect() в
     * com._1c.g5.v8.dt.internal.platform.services.core.runtimes.execution.DesignerSessionPool.
     * Хук должен подписаться на эти события и запоминать дату факта подключения
     * в колонке списка (в будущей реализации).
     * Команда "Отключить конфигуратор" должна вызывать release() в том же классе
     * для выбранных баз (в будущей реализации).
     */
public class ApplicationsViewHook implements IStartup
{
    // ---- Константы ----

    private static final String APPLICATIONS_VIEW_CLASS =
        "com.e1c.g5.dt.internal.applications.ui.view.ApplicationsView"; //$NON-NLS-1$

    private static final String CMD_DISCONNECT_TITLE = "Освободить конфигуратор"; 
    private static final String CMD_DISCONNECT_TOOLTIP = "Освободить конфигураторы выбранных инфобаз"; 
    private static final String COL_CONFIG_TOOLTIP = "Начало сеанса конфигуратора"; 
    private static final String COL_CONFIG_TITLE = "Конфигуратор"; 
    private static final int    COL_CONFIG_WIDTH = 165;

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"); //$NON-NLS-1$

    // -----------------------------------------------------------------------
    // IStartup
    // -----------------------------------------------------------------------

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench wb = PlatformUI.getWorkbench();

            for (IWorkbenchWindow w : wb.getWorkbenchWindows())
                hookWindow(w);

            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });
        });
    }

    // -----------------------------------------------------------------------
    // Подключение к окну / панели
    // -----------------------------------------------------------------------

    private void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (isApplicationsView(view))
                    hookView(view);
            }
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref.getPart(false);
                if (isApplicationsView(part))
                    // asyncExec: даём partControl завершить возможные отложенные init
                    Display.getDefault().asyncExec(() -> hookView(part));
            }

            @Override public void partActivated(IWorkbenchPartReference r)    {}
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private static boolean isApplicationsView(Object part)
    {
        return part != null &&
               APPLICATIONS_VIEW_CLASS.equals(part.getClass().getName());
    }

    // -----------------------------------------------------------------------
    // Основная логика хука
    // -----------------------------------------------------------------------

    private void hookView(Object part)
    {
        if (!(part instanceof IViewPart)) return;
        IViewPart view = (IViewPart) part;

        ColumnViewer viewer = findViewer(view);
        if (viewer == null) return;

        Control control = viewer.getControl();
        if (control == null || control.isDisposed()) return;

        if (control instanceof Tree)
        {
            setupMultiColumnTree(viewer, (Tree) control);
        }

         addToolbarButton(view, viewer, control);
         addContextMenuItem(viewer, control);
     }

    // -----------------------------------------------------------------------
    // 1. Многоколоночное дерево с сохранением оригинального рендерера
    // -----------------------------------------------------------------------

    /**
     * Настраивает TreeViewer на две колонки.
     *
     * <p>Перед созданием {@link TreeViewerColumn} сохраняем оригинальный
     * label provider EDT. Конструктор {@code ViewerColumn} вызывает
     * {@code viewer.clearLegacyRenderer()}, который делает
     * {@code disposeOwnerDrawLabelProvider()}, но сам объект-провайдер
     * остаётся жив и его {@code update(ViewerCell)} продолжает отдавать
     * текст и иконку. Мы делегируем к нему из провайдера колонки&nbsp;0.
     */
    private void setupMultiColumnTree(ColumnViewer viewer, Tree tree)
    {
        // Защита от повторного применения
        if (tree.getColumnCount() > 0 || tree.getHeaderVisible())
            return;

        // Сохраняем старый label provider ДО создания TreeViewerColumn
        final IBaseLabelProvider oldProvider = viewer.getLabelProvider();

        // Колонка 0 — делегируем к оригинальному рендереру EDT
        TreeViewerColumn col0 = new TreeViewerColumn((TreeViewer)viewer, SWT.NONE);
        col0.getColumn().setText("Инфобаза");
        col0.getColumn().setWidth(200);
        col0.getColumn().setResizable(true);
        col0.setLabelProvider(new CellLabelProvider()
        {
            @Override
            public void update(ViewerCell cell)
            {
                if (oldProvider instanceof CellLabelProvider)
                {
                    ((CellLabelProvider) oldProvider).update(cell);
                }
                else if (oldProvider instanceof ILabelProvider)
                {
                    ILabelProvider lp = (ILabelProvider) oldProvider;
                    Object element = cell.getElement();
                    cell.setText(lp.getText(element));
                    cell.setImage(lp.getImage(element));
                }
            }
        });

        // Колонка 1 — дата начала сеанса конфигуратора
         TreeViewerColumn col1 = new TreeViewerColumn((TreeViewer)viewer, SWT.NONE);
         col1.getColumn().setText(COL_CONFIG_TITLE);
         col1.getColumn().setToolTipText(COL_CONFIG_TOOLTIP);
         col1.getColumn().setWidth(COL_CONFIG_WIDTH);
         col1.getColumn().setResizable(true);
         col1.setLabelProvider(new CellLabelProvider()
         {
             @Override
             public void update(ViewerCell cell)
             {
                 String key = extractKey(cell.getElement());
                 LocalDateTime dt = null;
                 String text = (dt != null) ? DATE_FMT.format(dt) : "не подключено";
                 cell.setText(text);
             }
         });

        tree.setHeaderVisible(true);
        tree.setLinesVisible(true);
    }

    // -----------------------------------------------------------------------
    // 2. Кнопка в тулбаре панели
    // -----------------------------------------------------------------------

    private void addToolbarButton(IViewPart view, ColumnViewer viewer, Control control)
    {
        IActionBars actionBars = view.getViewSite().getActionBars();
        IToolBarManager toolbar = actionBars.getToolBarManager();

        Action action = new Action(CMD_DISCONNECT_TITLE)
        {
            @Override
            public void run()
            {
                disconnectSelected(viewer);
                // Перерисовка инициируется слушателем реестра в registerRedrawOnRegistryChange
            }
        };
        action.setToolTipText(CMD_DISCONNECT_TOOLTIP);
        toolbar.add(new Separator());
        toolbar.add(action);
        actionBars.updateActionBars();
    }

    // -----------------------------------------------------------------------
    // 3. Пункт в контекстном меню
    // -----------------------------------------------------------------------

    private void addContextMenuItem(ColumnViewer viewer, Control control)
    {
        Menu menu = control.getMenu();
        if (menu == null)
        {
            menu = new Menu(control);
            control.setMenu(menu);
        }

        final Menu finalMenu = menu;

        MenuAdapter adapter = new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(2);

            @Override
            public void menuShown(MenuEvent e)
            {
                IStructuredSelection sel =
                    (IStructuredSelection) viewer.getSelection();
                if (sel.isEmpty()) return;

                // Показываем пункт только если среди выбранных есть активные сеансы
                 boolean anyConnected = false;
                if (!anyConnected) return;

                addedItems.add(new MenuItem(finalMenu, SWT.SEPARATOR));

                MenuItem item = new MenuItem(finalMenu, SWT.PUSH);
                item.setText(CMD_DISCONNECT_TITLE);

                final IStructuredSelection capturedSel = sel;
                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        disconnectItems(capturedSel.toList());
                    }
                });
                addedItems.add(item);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                // asyncExec: сначала доставляем widgetSelected, потом dispose
                Display display = ((Menu) e.widget).getDisplay();
                List<MenuItem> toDispose = new ArrayList<>(addedItems);
                addedItems.clear();
                display.asyncExec(() ->
                {
                    for (MenuItem mi : toDispose)
                        if (!mi.isDisposed()) mi.dispose();
                });
            }
        };

        menu.addMenuListener(adapter);
        control.addDisposeListener(
            e -> { if (!finalMenu.isDisposed()) finalMenu.removeMenuListener(adapter); });
    }

    // -----------------------------------------------------------------------
    // 4. Перерисовка при изменении реестра
    // -----------------------------------------------------------------------



    // -----------------------------------------------------------------------
    // Действия "Отключить конфигуратор"
    // -----------------------------------------------------------------------

    private void disconnectSelected(ColumnViewer viewer)
    {
        IStructuredSelection sel = (IStructuredSelection) viewer.getSelection();
        if (!sel.isEmpty())
            disconnectItems(sel.toList());
    }

     private void disconnectItems(List<?> items)
     {
         for (Object item : items)
         {
             String key = extractKey(item);
             // Временная модификация: отключение через ComConnectionRegistry отключено
             // TODO: Реализовать отключение через DesignerSessionPool.release()
             // if (ComConnectionRegistry.isConnected(key))
             //     ComConnectionRegistry.disconnect(key);
                 // disconnect() уведомляет слушателей → registerRedrawOnRegistryChange → refresh
         }
     }

    // -----------------------------------------------------------------------
    // Поиск ColumnViewer через рефлексию
    // -----------------------------------------------------------------------

    /**
     * Перебирает все поля класса вида и его суперклассов в поисках
     * первого {@link ColumnViewer}. Не зависит от имени поля в EDT.
     */
    private static ColumnViewer findViewer(Object view)
    {
        Class<?> cls = view.getClass();
        while (cls != null)
        {
            for (Field f : cls.getDeclaredFields())
            {
                if (!ColumnViewer.class.isAssignableFrom(f.getType())) continue;
                try
                {
                    f.setAccessible(true);
                    Object val = f.get(view);
                    if (val instanceof ColumnViewer)
                        return (ColumnViewer) val;
                }
                catch (Exception ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Извлечение ключа из элемента строки
    // -----------------------------------------------------------------------

    /**
     * Возвращает строковый ключ для элемента строки вьюера.
     * Пробует: {@code getName()} → {@code getTitle()} → {@code toString()}.
     */
    static String extractKey(Object item)
    {
        if (item == null) return ""; //$NON-NLS-1$

        String name = invokeString(item, "getName"); //$NON-NLS-1$
        if (name != null && !name.isEmpty()) return name;

        String title = invokeString(item, "getTitle"); //$NON-NLS-1$
        if (title != null && !title.isEmpty()) return title;

        return item.toString();
    }

    private static String invokeString(Object obj, String method)
    {
        try
        {
            Object r = obj.getClass().getMethod(method).invoke(obj);
            return r instanceof String ? (String) r : null;
        }
        catch (Exception ignored) { return null; }
    }
}
