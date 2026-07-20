package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import java.util.Set;
import java.util.Collections;
import java.util.WeakHashMap;

/**
 * Глобально: заметное выделение строки Table в тёмной теме EDT.
 * Tree (навигатор) — штатная подсветка только на ширину надписи, без полной строки.
 */
public final class ListSelectionThemeHook implements IStartup
{
    private static volatile boolean filtersInstalled;
    private static Listener showFilterListener;
    private static final Set<IWorkbenchPage> HOOKED_PAGES =
        Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Guard от реентрантности + debounce (issue #165): {@code SWT.Show} — глобальный фильтр,
     * срабатывает на показ ЛЮБОГО виджета в приложении. Открытие тяжёлого редактора создаёт много
     * новых виджетов — каждый даёт свой {@code Show}, и раньше КАЖДЫЙ синхронно запускал полный
     * рекурсивный обход всего дерева шелла ({@code walkControl}). Десятки вложенных полных обходов
     * подряд синхронно на UI-потоке, ровно в момент раскладки нового редактора — подтверждённая
     * причина порчи GUI соседних частей (см. память/issue165.log). Не меняем саму фичу (подсветка
     * выделения таблиц в тёмной теме) — только защищаем её включение от каскада:
     * {@code scanInProgress} не даёт стартовать новый обход, пока не закончился текущий (в т.ч.
     * вложенный, вызванный Show дочернего виджета ВО ВРЕМЯ обхода), а debounce схлопывает пачку
     * Show-событий одного всплеска в один обход после того, как поток событий утихнет.
     */
    private static volatile boolean scanInProgress;
    private static final int SHOW_DEBOUNCE_MS = 150;
    private static volatile Shell pendingScanShell;
    private static Runnable pendingScanRunnable;

    /**
     * ФИКС v3 (issue #165) — v2 (исключить редакторы из {@code partOpened}/{@code partVisible})
     * оказался недостаточным: лог показал, что ПОПУТНО с открытием редактора формы становится
     * видимым/открывается ДРУГОЙ, не-редакторский part (например, {@code applications.ui.view}) —
     * и он по-прежнему не исключён, снова синхронно запускает {@code scanWorkbenchShells()} ровно
     * в момент чужой раскладки. Сами сканы быстрые (0-3мс по логу) — дело не в длительности,
     * а в самой СИНХРОННОСТИ с чужим layout-проходом. Решение — тот же debounce, что уже есть для
     * {@code SWT.Show}, но теперь и для part-триггеров: вместо немедленного вызова —
     * откладываем на {@link #SHOW_DEBOUNCE_MS} и схлопываем повторные события в один скан.
     */
    private static Runnable pendingWorkbenchScanRunnable;

    private static void scheduleDebouncedWorkbenchScan(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        if (pendingWorkbenchScanRunnable != null)
            display.timerExec(-1, pendingWorkbenchScanRunnable);
        pendingWorkbenchScanRunnable = () -> {
            pendingWorkbenchScanRunnable = null;
            scanWorkbenchShells();
        };
        display.timerExec(SHOW_DEBOUNCE_MS, pendingWorkbenchScanRunnable);
    }

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (display.getThread() == Thread.currentThread())
            install(display);
        else
            display.asyncExec(() -> install(display));
    }

    private static synchronized void install(Display display)
    {
        if (display == null || display.isDisposed() || filtersInstalled)
            return;
        filtersInstalled = true;
        showFilterListener = event -> {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            scheduleDebouncedScan(display, shell);
        };
        display.addFilter(SWT.Show, showFilterListener);
        hookWorkbenchWindows();
        guardedScan(() -> {
            for (Shell shell : display.getShells())
            {
                if (shell != null && !shell.isDisposed())
                    Enhancement.scanShell(shell);
            }
        });
        display.timerExec(2000, () -> scanWorkbenchShells());
        display.timerExec(8000, () -> scanWorkbenchShells());
    }

    private static void hookWorkbenchWindows()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
            hookWindow(window);
        wb.addWindowListener(new org.eclipse.ui.IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override public void windowActivated(IWorkbenchWindow window) {}
            @Override public void windowDeactivated(IWorkbenchWindow window) {}
            @Override public void windowClosed(IWorkbenchWindow window) {}
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        Shell shell = window.getShell();
        if (shell != null && !shell.isDisposed())
            guardedScan(() -> Enhancement.scanShell(shell));
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
            hookPage(page);
        window.addPageListener(new org.eclipse.ui.IPageListener()
        {
            @Override
            public void pageActivated(IWorkbenchPage activePage)
            {
                hookPage(activePage);
            }

            @Override public void pageClosed(IWorkbenchPage closedPage) {}
            @Override public void pageOpened(IWorkbenchPage openedPage)
            {
                hookPage(openedPage);
            }
        });
    }

    private static void hookPage(IWorkbenchPage page)
    {
        if (page == null || !HOOKED_PAGES.add(page))
            return;
        page.addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference partRef)
            {
                if (isEditorRef(partRef))
                    return;
                scheduleDebouncedWorkbenchScan(Display.getDefault());
            }

            @Override public void partActivated(IWorkbenchPartReference partRef) {}
            @Override public void partBroughtToTop(IWorkbenchPartReference partRef) {}
            @Override public void partClosed(IWorkbenchPartReference partRef) {}
            @Override public void partDeactivated(IWorkbenchPartReference partRef) {}
            @Override public void partHidden(IWorkbenchPartReference partRef) {}
            @Override public void partVisible(IWorkbenchPartReference partRef)
            {
                if (isEditorRef(partRef))
                    return;
                scheduleDebouncedWorkbenchScan(Display.getDefault());
            }

            @Override public void partInputChanged(IWorkbenchPartReference partRef) {}
        });
        scanWorkbenchShells();
    }

    /**
     * ФИКС v2 (issue #165) — предыдущий (guard+debounce на {@code SWT.Show}) не остановил баг:
     * перепроверка показала, что {@code showFilterListener} и так фильтрует по
     * {@code event.widget instanceof Shell} — то есть срабатывает только на показ ЦЕЛОГО шелла
     * (новый диалог/окно), а НЕ на показ произвольного дочернего виджета. Открытие редактора внутри
     * уже показанного главного окна новый Shell не создаёт — значит "каскад Show" из первой версии
     * фикса был неверной гипотезой, реального каскада там не было, потому guard+debounce почти
     * ничего не менял.
     *
     * <p>Реальный триггер, связанный с редакторами, — {@code partOpened}/{@code partVisible} в
     * {@link #hookPage}: они и раньше не фильтровались по типу part'а, включая {@code IEditorPart},
     * и вызывали {@code scanWorkbenchShells()} — полный рекурсивный обход ВСЕХ шеллов workbench —
     * при каждом открытии/показе РЕДАКТОРА. Сама фича (подсветка выделения) предназначена для таблиц
     * во ВИДАХ/диалогах (навигатор и т.п.), не для внутренних таблиц редакторов — для них есть
     * отдельный явный {@link #ensureControl}. Поэтому новый фикс не троттлит частоту, а сужает
     * область: редакторы (в т.ч. открытие/закрытие формы/BSL-модуля) больше не запускают
     * {@code scanWorkbenchShells()} вообще — только показ view/диалога. Guard+debounce из v1
     * оставлены (не мешают, дополнительная защита для оставшихся триггеров).
     */
    private static boolean isEditorRef(IWorkbenchPartReference ref)
    {
        return ref instanceof org.eclipse.ui.IEditorReference;
    }

    /** Снять глобальный SWT.Show filter (preShutdown). */
    public static void uninstall()
    {
        if (!filtersInstalled)
            return;
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed() && showFilterListener != null)
            display.removeFilter(SWT.Show, showFilterListener);
        showFilterListener = null;
        filtersInstalled = false;
        HOOKED_PAGES.clear();
    }

    /** Откладывает скан ОДНОГО шелла: пачка Show-событий одного всплеска схлопывается в один обход. */
    private static void scheduleDebouncedScan(Display display, Shell shell)
    {
        pendingScanShell = shell;
        if (pendingScanRunnable != null)
            display.timerExec(-1, pendingScanRunnable); // отменить предыдущий отложенный скан
        pendingScanRunnable = () -> {
            pendingScanRunnable = null;
            Shell target = pendingScanShell;
            if (target == null || target.isDisposed())
                return;
            guardedScan(() -> Enhancement.scanShell(target));
        };
        display.timerExec(SHOW_DEBOUNCE_MS, pendingScanRunnable);
    }

    /**
     * Не даёт стартовать новый обход, пока не закончился текущий (в т.ч. вложенный).
     *
     * <p>ФИКС v5 (issue #165): по логу конкретного повтора крах (каскад
     * {@code CTabFolder.destroyItem}/{@code CTabItem.dispose}/{@code StackRenderer.hideChild})
     * вызывается ИЗНУТРИ {@code PartRenderingEngine.safeRemoveGui}, которая сама выполняется во
     * ВЛОЖЕННОМ {@code Display.readAndDispatch()} (см. {@code PartRenderingEngine$5.run} в
     * стектрейсе). Если наш обход (вызванный из {@code SWT.Show}-фильтра или {@code timerExec}, оба
     * могут быть реентерабельно вызваны платформой изнутри такого вложенного цикла) тронет виджет,
     * который штатный код в этот же момент разбирает/меняет, и бросит необработанное исключение —
     * оно улетит вверх и прервёт ЧУЖОЙ вложенный цикл диспетчеризации на середине, оставляя часть
     * дерева недоразобранной — ровно та картина, что мы наблюдаем (desync). Раньше {@code finally}
     * сбрасывал {@code scanInProgress}, но исключение из {@code scanAction.run()} всё равно летело
     * дальше наружу. Теперь ловим здесь — наш код не должен быть тем, что прерывает чужой обход.
     */
    private static void guardedScan(Runnable scanAction)
    {
        if (scanInProgress)
            return;
        scanInProgress = true;
        try
        {
            scanAction.run();
        }
        catch (RuntimeException ignored) {}
        finally
        {
            scanInProgress = false;
        }
    }

    private static void scanWorkbenchShells()
    {
        guardedScan(ListSelectionThemeHook::scanWorkbenchShellsImpl);
    }

    private static void scanWorkbenchShellsImpl()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
        {
            Shell shell = window.getShell();
            if (shell != null && !shell.isDisposed())
                Enhancement.scanShell(shell);
        }
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
        {
            for (Shell shell : display.getShells())
            {
                if (shell != null && !shell.isDisposed())
                    Enhancement.scanShell(shell);
            }
        }
    }

    /** Явная установка на таблицу (popup автодополнения создаётся после Show shell). */
    public static void ensureControl(Control list)
    {
        if (list == null || list.isDisposed())
            return;
        Enhancement.installIfNeeded(list);
    }

        private static final class Enhancement
        {
            private static final String INSTALLED_KEY = "tormozit.listSelectionThemeInstalled"; //$NON-NLS-1$
            private static final String LISTENER_KEY = "tormozit.listSelectionThemeListeners"; //$NON-NLS-1$

        private Enhancement() {}

        static void scanShell(Shell shell)
        {
            if (shell == null || shell.isDisposed())
                return;
            walkControl(shell);
        }

        private static void walkControl(Control control)
        {
            if (control == null || control.isDisposed())
                return;
            if (control instanceof Table table)
                installIfNeeded(table);
            else if (control instanceof Tree tree)
                uninstallTreeThemeIfInstalled(tree);
            if (control instanceof Composite composite)
            {
                for (Control child : composite.getChildren())
                    walkControl(child);
            }
        }

        static void uninstallTreeThemeIfInstalled(Tree tree)
        {
            if (tree == null || tree.isDisposed())
                return;
            if (!Boolean.TRUE.equals(tree.getData(INSTALLED_KEY)))
                return;
            uninstall(tree);
        }

        static void installIfNeeded(Control list)
        {
            if (!(list instanceof Table table))
                return;
            if (list == null || list.isDisposed() || ListSelectionThemeColors.isOptOut(list))
                return;
            if (Boolean.TRUE.equals(list.getData(INSTALLED_KEY)))
                return;
            // Фича только для тёмной темы (см. ListSelectionThemeColors.isDarkList) — раньше
            // слушатели вешались на ЛЮБУЮ таблицу независимо от темы, и только покраска внутри
            // EraseItem проверяла isDarkList; FocusIn/FocusOut/Selection всё равно синхронно
            // дёргали redraw() в светлой теме без всякого видимого эффекта — лишний риск (в т.ч.
            // для гипотезы v4 реентрантного redraw) без пользы. Если тема переключится на тёмную
            // во время работы без перезапуска — таблица получит слушатели при следующем скане
            // (Show/partOpened/partVisible), а не мгновенно.
            if (!ListSelectionThemeColors.isDarkList(table))
                return;

            Listener erase = Enhancement::onEraseItem;
            // v4-гипотеза (реентрантный redraw из FocusOut) проверена логом конкретного повтора и
            // НЕ подтвердилась — LSTH не проявлял активности рядом с моментом краха. Оставлен
            // простой синхронный вариант; см. v5 (guardedScan) — текущая проверяемая гипотеза.
            Listener focus = e -> syncTableSelection(table);
            Listener selection = e -> syncTableSelection(table);

            list.addListener(SWT.EraseItem, erase);
            list.addListener(SWT.FocusIn, focus);
            list.addListener(SWT.FocusOut, focus);
            list.addListener(SWT.Selection, selection);
            list.addDisposeListener(e -> uninstall(list));

            Listener[] bundle = new Listener[] { erase, focus, selection };
            list.setData(LISTENER_KEY, bundle);
            list.setData(INSTALLED_KEY, Boolean.TRUE);
            syncTableSelection(table);
            ListSelectionThemeColors.installSelectionPrePaintFilter(table, (t, item, col) ->
            {
                if (!isSelected(t, item))
                    return null;
                return ListSelectionThemeColors.listSelectionBackground(t, t.isFocusControl());
            }, "table"); //$NON-NLS-1$
        }

        static void uninstall(Control list)
        {
            if (list == null || list.isDisposed())
                return;
            Object stored = list.getData(LISTENER_KEY);
            if (stored instanceof Listener[] bundle && bundle.length >= 3)
            {
                list.removeListener(SWT.EraseItem, bundle[0]);
                list.removeListener(SWT.FocusIn, bundle[1]);
                list.removeListener(SWT.FocusOut, bundle[1]);
                list.removeListener(SWT.Selection, bundle[2]);
            }
            list.setData(LISTENER_KEY, null);
            list.setData(INSTALLED_KEY, null);
        }

        private static void onEraseItem(Event e)
        {
            if (e.gc == null)
                return;
            if (e.item instanceof TableItem tableItem)
                paintTableErase(e, tableItem);
        }

        private static void paintTableErase(Event e, TableItem item)
        {
            if (!(e.widget instanceof Table table) || table.isDisposed() || item.isDisposed())
                return;
            if (!ListSelectionThemeColors.isDarkList(table))
                return;
            if (!isSelected(table, item))
                return;
            Color bg = ListSelectionThemeColors.listSelectionBackground(table, table.isFocusControl());
            if (bg == null)
                return;
            ListSelectionThemeColors.fillSelectionBackground(e, table, item, bg);
            e.detail &= ~SWT.BACKGROUND;
            if (!bg.isDisposed())
                bg.dispose();
        }

        private static boolean isSelected(Table table, TableItem item)
        {
            for (TableItem selected : table.getSelection())
            {
                if (selected == item)
                    return true;
            }
            return false;
        }

        private static void syncTableSelection(Table table)
        {
            if (table == null || table.isDisposed())
                return;
            for (TableItem item : table.getSelection())
                redrawTableItem(table, item);
        }

        private static void redrawTableItem(Table table, TableItem item)
        {
            if (item == null || item.isDisposed())
                return;
            int cols = Math.max(1, table.getColumnCount());
            for (int c = 0; c < cols; c++)
            {
                Rectangle bounds = item.getBounds(c);
                if (bounds != null && !bounds.isEmpty())
                    table.redraw(bounds.x, bounds.y, bounds.width, bounds.height, false);
            }
        }
    }
}
