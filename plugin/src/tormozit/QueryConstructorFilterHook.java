package tormozit;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;

public final class QueryConstructorFilterHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.queryConstructorFilterPatched"; //$NON-NLS-1$
    private static final String QUERY_WIZARD_CLASS = "QueryWizard"; //$NON-NLS-1$

    private static final Set<Shell> patchedShells =
        Collections.newSetFromMap(new WeakHashMap<>());

    /** Персистентно (переживает и переоткрытие окна, и перезапуск EDT) — через уже принятое
     * в проекте {@link FilterHistoryStore} (см. {@code PreferenceSearchFilterAugmenter},
     * {@code ComfortKeysPreferences}), а не штатный {@code InMemorySearchHistory}. */
    private static final SearchHistoryAdapter searchHistory = new SearchHistoryAdapter();

    private static final class SearchHistoryAdapter
        implements com._1c.g5.v8.dt.common.ui.controls.search.ISearchHistory
    {
        private static final String SCOPE_ID = "queryConstructorTables"; //$NON-NLS-1$

        @Override
        public void savePattern(String pattern)
        {
            FilterHistoryStore.remember(SCOPE_ID, pattern);
        }

        @Override
        public void replacePattern(String pattern)
        {
            savePattern(pattern);
        }

        @Override
        public String getActivePattern()
        {
            // Всегда "" — SearchBox.setHistory() сам подставляет непустой activePattern в поле
            // при каждом открытии окна, а это не нужно (см. просьбу пользователя).
            return ""; //$NON-NLS-1$
        }

        @Override
        public java.util.List<String> getRecentPatterns(int max)
        {
            java.util.List<String> items = FilterHistoryStore.load(SCOPE_ID);
            return max >= 0 && items.size() > max ? items.subList(0, max) : items;
        }
    }

    @Override
    public void earlyStartup()
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, QueryConstructorFilterHook::handleShow);
    }

    private static void handleShow(Event event)
    {
        if (!(event.widget instanceof Shell shell))
            return;
        if (shell.isDisposed())
            return;
        if (suppressShowKeysPopupIfRecentCtrlF(shell))
            return;
        if (shell.getData(PATCHED_KEY) != null)
            return;
        if (patchedShells.contains(shell))
            return;
        if (!isQueryWizardShell(shell))
            return;
        scheduleTryPatch(shell, 0);
    }

    /** Момент последнего перехваченного Ctrl+F в нашем дереве (см. {@link #wireCtrlF}) — используется
     * ниже, чтобы отличить попап "подсказка по клавишам" Eclipse, вызванный именно этим нажатием. */
    private static volatile long lastCtrlFTimeMs;

    /**
     * Сервис биндингов Eclipse обрабатывает Ctrl+F раньше нас (см. {@link #wireCtrlF} —
     * {@code KeyDown}-фильтр опаздывает выставить {@code doit=false}) и сам решает показать
     * {@code ShowKeysUI$ShowKeysPopup} ("подсказка по клавишам") — предотвратить его появление
     * нельзя, но можно сразу скрыть, пока пользователь не успел его заметить (в пределах короткого
     * окна после нашего Ctrl+F — не трогаем этот попап, если он появился по другой причине).
     */
    private static boolean suppressShowKeysPopupIfRecentCtrlF(Shell shell)
    {
        Object data = shell.getData();
        if (data == null || !data.getClass().getName().contains("ShowKeysUI")) //$NON-NLS-1$
            return false;
        if (System.currentTimeMillis() - lastCtrlFTimeMs > 500)
            return false;
        shell.setVisible(false);
        return true;
    }

    private static boolean isQueryWizardShell(Shell shell)
    {
        Object data = shell.getData();
        if (data == null)
            return false;
        String name = data.getClass().getName();
        return name.contains(QUERY_WIZARD_CLASS) && !name.contains("Control"); //$NON-NLS-1$
    }

    private static void scheduleTryPatch(Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;
        int delay = attempt == 0 ? 0 : 60;
        shell.getDisplay().timerExec(delay, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (tryPatch(shell, attempt))
                return;
            if (attempt < 40)
                scheduleTryPatch(shell, attempt + 1);
        });
    }

    private static boolean tryPatch(Shell shell, int attempt)
    {
        Object dialog = shell.getData();
        if (dialog == null)
        {
            shell.setData(PATCHED_KEY, Boolean.TRUE);
            return true;
        }

        Object qwc = getField(dialog, "queryWizardControl"); //$NON-NLS-1$
        if (qwc == null)
            return false;

        Object tabsObj = getField(qwc, "tablesAndFieldsTab"); //$NON-NLS-1$
        if (tabsObj == null)
            return false;

        TreeViewer tree = getFieldAs(tabsObj, "availableTablesTree", TreeViewer.class); //$NON-NLS-1$
        if (tree == null)
            return false;

        Composite parent = safeCast(tree.getControl().getParent(), Composite.class);
        if (parent == null)
            return false;

        Composite qwcComp = safeCast(qwc, Composite.class);
        ToolBar searchToolBar = qwcComp != null ? findSearchToolBar(qwcComp) : null;

        SearchBox searchBox = findSearchBox(parent);
        if (searchBox != null)
        {
            stripFocusListeners(searchBox);
        }
        else
        {
            if (searchToolBar == null)
                return false;
            searchBox = createSearchBoxViaAction(searchToolBar);
            if (searchBox == null)
                return false;
            stripFocusListeners(searchBox);
        }

        shell.setData(PATCHED_KEY, Boolean.TRUE);
        patchedShells.add(shell);

        try
        {
            installFilter(tree, searchBox, searchToolBar);
            Debug.log("PATCH OK tree=" + tree.getClass().getSimpleName() //$NON-NLS-1$
                + " searchBox=" + searchBox.getClass().getSimpleName()); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Debug.problem("tryPatch exception: " + e); //$NON-NLS-1$
            Global.logError(Debug.TAG, "tryPatch", e); //$NON-NLS-1$
        }

        shell.addDisposeListener(e -> patchedShells.remove(shell));
        return true;
    }

    private static Object getField(Object obj, String name)
    {
        if (obj == null)
            return null;
        try
        {
            Field f = findField(obj.getClass(), name);
            if (f == null)
                return null;
            f.setAccessible(true);
            return f.get(obj);
        }
        catch (Exception e)
        {
            Debug.problem("getField " + name + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getFieldAs(Object obj, String name, Class<T> type)
    {
        Object val = getField(obj, name);
        return type.isInstance(val) ? (T) val : null;
    }

    private static Field findField(Class<?> clazz, String name)
    {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass())
        {
            try
            {
                return c.getDeclaredField(name);
            }
            catch (NoSuchFieldException ignored)
            {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static SearchBox findSearchBox(Control root)
    {
        if (root == null || root.isDisposed())
            return null;
        if (root instanceof SearchBox sb)
            return sb;
        if (root instanceof Composite comp)
        {
            for (Control child : comp.getChildren())
            {
                if (child.isDisposed())
                    continue;
                if (child instanceof SearchBox sb)
                    return sb;
                SearchBox found = findSearchBox(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T safeCast(Object obj, Class<T> type)
    {
        return type.isInstance(obj) ? (T) obj : null;
    }

    private static SearchBox createSearchBoxViaAction(ToolBar toolBar)
    {
        ToolItem searchItem = findSearchToolItem(toolBar);
        if (searchItem == null)
            return null;
        Object action = searchItem.getData();
        if (action == null)
            return null;
        if (action instanceof org.eclipse.jface.action.ActionContributionItem aci)
        {
            action = aci.getAction();
        }
        if (action == null)
            return null;

        Composite parent = safeCast(toolBar.getParent(), Composite.class);
        try
        {
            if (action instanceof org.eclipse.jface.action.IAction ia)
                ia.run();
            else
            {
                java.lang.reflect.Method runMethod = action.getClass().getMethod("run"); //$NON-NLS-1$
                runMethod.invoke(action);
            }
        }
        catch (Exception e)
        {
            Debug.problem("createSearchBoxViaAction invoke failed: " + e); //$NON-NLS-1$
            return null;
        }

        SearchBox sb = parent != null ? findSearchBox(parent) : null;

        // TablesAndFieldsTab$6.run() (эта action) не идемпотентен — безусловно создаёт НОВЫЙ
        // SearchBox через addSearchBox() при каждом вызове, не проверяя, есть ли уже один. Отключение
        // самой action (setEnabled(false)) визуально красит её недоступной, но НЕ убирает исходный
        // ToolItem "Поиск" из тулбара — он остаётся, просто "сжатым" разовым layout-хаком нативного
        // кода при создании SearchBox; любой следующий relayout (потеря/восстановление фокуса,
        // Ctrl+F) может снова показать его (уже недоступным, но занимающим место — отсюда прыжок).
        // Реально убираем — dispose() самого ToolItem, а не только его action.
        if (sb != null)
        {
            if (action instanceof org.eclipse.jface.action.IAction ia)
                ia.setEnabled(false);
            if (!searchItem.isDisposed())
                searchItem.dispose();
        }
        return sb;
    }

    private static ToolBar findToolBar(Composite parent)
    {
        for (Control child : parent.getChildren())
        {
            if (child instanceof ToolBar tb)
                return tb;
        }
        return null;
    }

    private static ToolBar findSearchToolBar(Composite root)
    {
        for (Control child : root.getChildren())
        {
            if (child instanceof ToolBar tb && findSearchToolItem(tb) != null)
                return tb;
            if (child instanceof Composite comp)
            {
                ToolBar found = findSearchToolBar(comp);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static ToolItem findSearchToolItem(ToolBar toolBar)
    {
        for (ToolItem item : toolBar.getItems())
        {
            String tip = item.getToolTipText();
            if (tip != null && (tip.contains("Поиск") || tip.toLowerCase().contains("search"))) //$NON-NLS-1$ //$NON-NLS-2$
                return item;
        }
        return null;
    }

    /**
     * Снимаем только чужой {@code TablesAndFieldsTab$7} (его {@code focusLost} делает
     * {@code searchBox.dispose()} — схлопывает наш поиск обратно в кнопку при потере фокуса).
     * Внутренний {@code SearchBox$5} (штатная подсказка при пустом поле — display/hideMessage)
     * не трогаем, иначе {@code setMessage} перестаёт что-либо показывать.
     */
    private static void stripFocusListeners(SearchBox searchBox)
    {
        try
        {
            Listener[] lost = searchBox.getListeners(SWT.FocusOut);
            for (Listener l : lost)
            {
                Object real = l instanceof org.eclipse.swt.widgets.TypedListener typed
                    ? typed.getEventListener() : l;
                String className = real.getClass().getName();
                if (className.startsWith("com._1c.g5.v8.dt.common.ui.controls.search.SearchBox$")) //$NON-NLS-1$
                    continue;
                searchBox.removeListener(SWT.FocusOut, l);
            }
        }
        catch (Exception e)
        {
            Debug.problem("stripFocusListeners: " + e); //$NON-NLS-1$
        }
    }

    private static void installFilter(TreeViewer tree, SearchBox searchBox, ToolBar searchToolBar)
    {
        org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider baseStyled =
            resolveStyledLabelProvider(tree);
        if (baseStyled == null)
        {
            Debug.problem("installFilter: baseStyled=null"); //$NON-NLS-1$
            return;
        }

        // skipHighlight = null: категория тоже может законно подсветиться в иерархическом режиме
        // ("справ.таблица" — фрагмент "справ" матчится в самой категории), не только "Таблица".
        SmartOutlineLabelProvider highlighter = new SmartOutlineLabelProvider(baseStyled, null, null, baseStyled);
        tree.setLabelProvider(new org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider(highlighter));

        AvailableTableSearchFilter filter = new AvailableTableSearchFilter(baseStyled);
        tree.addFilter(filter);

        searchBox.setMessage("Поиск по таблицам"); //$NON-NLS-1$
        searchBox.setMinimumSearchTextLength(3);
        searchBox.setHistory(searchHistory);
        installPopupOutsideClickCloser(searchBox);

        setSearchListener(searchBox,
            (SearchBox.ISearchListener) (text, monitor) -> onSearch(text, tree, filter, highlighter));

        installChangeTablesResetHook(tree, searchToolBar, filter);

        if (tree.getControl() instanceof Tree swtTree)
        {
            wireTreeCopy(swtTree);
            wireCtrlF(searchBox, swtTree);
        }

        Debug.log("installFilter filters=" + tree.getFilters().length); //$NON-NLS-1$
    }

    /** Текущие {@code SearchBox}/дерево, для которых нужно перехватывать Ctrl+F (см. {@link #wireCtrlF}). */
    private static volatile SearchBox ctrlFTargetSearchBox;
    private static volatile Tree ctrlFTargetTree;
    private static boolean ctrlFExecutionListenerInstalled;

    /**
     * Ctrl+F должен фокусировать наш {@code SearchBox} (штатная кнопка так и подписана —
     * "Поиск (Ctrl+f)"). Тот же архитектурный потолок, что и у Ctrl+C (см. {@link #wireTreeCopy}):
     * обычный {@code SWT.KeyDown} не долетает в модальном диалоге. Перехват — через
     * {@code ICommandService} на {@code org.eclipse.ui.edit.findReplace} (штатный ID команды
     * "Найти"), один слушатель на всё время работы плагина (не на каждое открытие окна).
     */
    private static void wireCtrlF(SearchBox searchBox, Tree tree)
    {
        ctrlFTargetSearchBox = searchBox;
        ctrlFTargetTree = tree;
        searchBox.addDisposeListener(e ->
        {
            if (ctrlFTargetSearchBox == searchBox)
            {
                ctrlFTargetSearchBox = null;
                ctrlFTargetTree = null;
            }
        });
        if (ctrlFExecutionListenerInstalled || PlatformUI.getWorkbench() == null)
            return;
        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        commandService.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
                handlePossibleCtrlF(commandId);
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception)
            {
                handlePossibleCtrlF(commandId);
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception)
            {
            }
        });
        ctrlFExecutionListenerInstalled = true;

        // Дополнительно — сырой SWT.KeyDown фильтр раньше сервиса биндингов Eclipse: тот на
        // Ctrl+F показывает свой попап "подсказка по клавишам" (ShowKeysUI$ShowKeysPopup) —
        // не всегда действенно (порядок регистрации фильтров не гарантирован), но Ctrl+C оказался
        // съеден нативным акселератором раньше SWT, а Ctrl+F может и не быть.
        searchBox.getDisplay().addFilter(SWT.KeyDown, event ->
        {
            if ((event.stateMask & SWT.CTRL) == 0 || Character.toLowerCase((char) event.keyCode) != 'f') //$NON-NLS-1$
                return;
            SearchBox sb = ctrlFTargetSearchBox;
            Tree t = ctrlFTargetTree;
            if (sb == null || sb.isDisposed() || t == null || t.isDisposed())
                return;
            Control focus = sb.getDisplay().getFocusControl();
            if (focus != t && focus != sb)
                return;
            lastCtrlFTimeMs = System.currentTimeMillis();
            event.doit = false;
            if (focus != sb)
                sb.forceFocus();
        });
    }

    private static void handlePossibleCtrlF(String commandId)
    {
        SearchBox searchBox = ctrlFTargetSearchBox;
        Tree tree = ctrlFTargetTree;
        if (searchBox == null || searchBox.isDisposed() || tree == null || tree.isDisposed())
            return;
        if (!"org.eclipse.ui.edit.findReplace".equals(commandId)) //$NON-NLS-1$
            return;
        Control focus = searchBox.getDisplay().getFocusControl();
        // Фокус уже там — не дёргаем forceFocus() повторно: preExecute и notHandled оба
        // срабатывают на одно нажатие (см. wireTreeCopy), второй вызов на уже сфокусированном
        // поле вызывал видимый "прыжок" (SearchBox что-то пересчитывает в layout при повторном
        // получении фокуса).
        if (focus == searchBox)
            return;
        if (focus != tree)
            return;
        lastCtrlFTimeMs = System.currentTimeMillis();
        searchBox.forceFocus();
    }

    /** Дерево, для которого сейчас нужно перехватывать команду Copy (см. {@link #wireTreeCopy}). */
    private static volatile Tree copyTargetTree;
    private static boolean copyExecutionListenerInstalled;

    /**
     * Ctrl+C при фокусе на дереве копирует текст выделенной строки. Тот же архитектурный потолок,
     * что и в {@code PreferenceSearchFilterAugmenter.wireTreeCopy}/{@code KeyBindingToastHook}:
     * буква C при зажатом Ctrl не порождает {@code SWT.KeyDown} в модальном диалоге — нативная
     * Win32-трансляция акселератора съедает её раньше. Перехват — через
     * {@code ICommandService.addExecutionListener} на {@code org.eclipse.ui.edit.copy}, а не
     * KeyDown — срабатывание команды долетает независимо от пути.
     */
    private static void wireTreeCopy(Tree tree)
    {
        copyTargetTree = tree;
        tree.addDisposeListener(e ->
        {
            if (copyTargetTree == tree)
                copyTargetTree = null;
        });
        installCopyExecutionListener();
    }

    private static void installCopyExecutionListener()
    {
        if (copyExecutionListenerInstalled || PlatformUI.getWorkbench() == null)
            return;
        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        commandService.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
                handlePossibleTreeCopy(commandId);
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception)
            {
                handlePossibleTreeCopy(commandId);
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception)
            {
            }
        });
        copyExecutionListenerInstalled = true;
    }

    private static void handlePossibleTreeCopy(String commandId)
    {
        Tree tree = copyTargetTree;
        if (tree == null || tree.isDisposed() || tree.getDisplay().getFocusControl() != tree)
            return;
        if (!"org.eclipse.ui.edit.copy".equals(commandId)) //$NON-NLS-1$
            return;
        TreeItem[] selection = tree.getSelection();
        if (selection.length == 0)
            return;
        String text = selection[0].getText();
        if (text == null || text.isBlank())
            return;
        Clipboard clipboard = new Clipboard(tree.getDisplay());
        try
        {
            clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
        }
        finally
        {
            clipboard.dispose();
        }
    }

    /**
     * «Отображать таблицы изменений» (тумблер) сам делает только {@code tree.refresh()} (см.
     * декомпиль {@code TablesAndFieldsTab$4.runWithEvent}) — новые узлы {@code *.Изменения}
     * добавляются в модель, но ни разу не проходят через наш filter/label provider (тот же кэш
     * JFace, что уже чинили для непустого поиска). После клика по кнопке досбрасываем input сами.
     */
    private static void installChangeTablesResetHook(
        TreeViewer tree, ToolBar searchToolBar, AvailableTableSearchFilter filter)
    {
        if (searchToolBar == null)
            return;
        ToolItem changeTablesItem = findToolItemByTip(searchToolBar, "Отображать таблицы изменений"); //$NON-NLS-1$
        if (changeTablesItem == null)
            return;
        changeTablesItem.addListener(SWT.Selection, e ->
        {
            Object input = tree.getInput();
            tree.setInput(null);
            tree.setInput(input);
            if (filter.isFiltering())
            {
                tree.expandToLevel(2);
                for (org.eclipse.jface.viewers.TreePath target : filter.drainExpandTargets())
                    tree.setExpandedState(target, true);
            }
        });
    }

    private static ToolItem findToolItemByTip(ToolBar toolBar, String tipFragment)
    {
        for (ToolItem item : toolBar.getItems())
        {
            String tip = item.getToolTipText();
            if (tip != null && tip.contains(tipFragment))
                return item;
        }
        return null;
    }

    /**
     * {@code SearchBox} сам регистрирует {@code Display.addFilter(SWT.MouseDown, ...)} для
     * закрытия попапа истории по клику вне поля/попапа (см. {@code displayPopup}/{@code SearchBox$2}
     * в декомпиле) — но на практике попап не закрывается. Дублируем ту же логику своим
     * независимым фильтром через рефлексию (не мешает штатному, если он всё же сработает —
     * {@code hidePopup()} у него идемпотентен, второй вызов — no-op).
     */
    private static void installPopupOutsideClickCloser(SearchBox searchBox)
    {
        Display display = searchBox.getDisplay();
        if (display == null || display.isDisposed())
            return;
        Listener closer = event ->
        {
            if (searchBox.isDisposed())
                return;
            try
            {
                Field displayingPopupField = SearchBox.class.getDeclaredField("displayingPopup"); //$NON-NLS-1$
                displayingPopupField.setAccessible(true);
                if (!Boolean.TRUE.equals(displayingPopupField.get(searchBox)))
                    return;
                java.lang.reflect.Method getPopup = SearchBox.class.getDeclaredMethod("getPopup"); //$NON-NLS-1$
                getPopup.setAccessible(true);
                Object popup = getPopup.invoke(searchBox);
                if (event.widget == popup || event.widget == searchBox)
                    return;
                java.lang.reflect.Method hidePopup = SearchBox.class.getDeclaredMethod("hidePopup"); //$NON-NLS-1$
                hidePopup.setAccessible(true);
                hidePopup.invoke(searchBox);
            }
            catch (Exception e)
            {
                Debug.problem("installPopupOutsideClickCloser: " + e); //$NON-NLS-1$
            }
        };
        display.addFilter(SWT.MouseDown, closer);
        searchBox.addDisposeListener(e -> display.removeFilter(SWT.MouseDown, closer));
    }

    /**
     * "Таблица" (в т.ч. табличная часть на 3-м уровне) — это НЕ конкретный класс
     * ({@code DbViewTableDefImpl}/{@code DbViewSelectDefImpl} — таблицы 2-го уровня,
     * {@code DbViewFieldTableDefImpl} — табличная часть 3-го уровня), а маркерный интерфейс
     * {@code com._1c.g5.v8.dt.metadata.dbview.Table} ({@code getFields()}): через него проходят
     * {@code DbViewDef} (→ {@code DbViewTableDef}/{@code DbViewSelectDef}) и напрямую
     * {@code DbViewFieldTableDef} (табличная часть — "поле, которое само таблица"). Обычные поля
     * ({@code DbViewFieldFieldDef}/{@code DbViewVirtualFieldDef}) этот интерфейс не реализуют —
     * отсекаются автоматически, на любой глубине, без привязки к уровню/родителю.
     */
    private static boolean isTableLike(Object element)
    {
        return element instanceof com._1c.g5.v8.dt.metadata.dbview.Table;
    }

    /**
     * Только штатный обход дерева (JFace сам вызывает {@code select}/{@code getStyledText} для уже
     * раскрытых узлов) — без своего рекурсивного прохода по поддереву и без автораскрытия путей к
     * совпадениям: узлы "Таблица" на выбор пользователя разворачиваются штатно, а получение их
     * полей (дорогой BM/DB-запрос) не форсируется фильтром сверх того, что уже делает
     * {@code expandToLevel(2)} (тот, как выяснилось, и так материализует и таблицы, и их поля).
     */
    private static final class AvailableTableSearchFilter extends ViewerFilter
    {
        private final org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider styled;
        private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$
        /** Таблицы, показанные ТОЛЬКО из-за совпавшей табличной части внутри (не по своему имени)
         * — их и только их нужно точечно развернуть, чтобы совпадение было видно. Остальные
         * видимые таблицы разворачивать не нужно — иначе разворачивается вообще всё подряд.
         * Полный {@code TreePath} (не голый элемент) — {@code AvailableTablesContentProvider} не
         * реализует {@code getParent()}, поэтому {@code setExpandedState(Object,boolean)} не может
         * сам построить цепочку родителей для ещё не связанного с виджетом элемента; путь строим
         * явно из уже известного {@code parentElement}. */
        private final java.util.Set<org.eclipse.jface.viewers.TreePath> expandTargets =
            new java.util.LinkedHashSet<>();
        /** Узлы, совпавшие САМИ ПО СЕБЕ (не через проверку потомков) — все их потомки видимы без
         * дальнейшей фильтрации (иначе внутри найденной табличной части отрезались бы её же
         * несовпавшие поля — то, что и должно быть видно целиком, раз сама часть — и есть матч).
         * Заполняется по ходу {@link #select}, родитель проверяется раньше ребёнка (JFace всегда
         * так обходит), так что к моменту проверки ребёнка родитель уже отмечен. */
        private final java.util.Set<Object> fullyMatchedContainers =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        AvailableTableSearchFilter(
            org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider styled)
        {
            this.styled = styled;
        }

        void setPattern(String pattern)
        {
            matcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
            expandTargets.clear();
            fullyMatchedContainers.clear();
        }

        boolean isFiltering()
        {
            return !matcher.isEmpty;
        }

        /** Забрать и сбросить накопленный за последний проход {@code select()} список путей,
         * которые нужно точечно развернуть (см. {@link #expandTargets}). */
        java.util.Set<org.eclipse.jface.viewers.TreePath> drainExpandTargets()
        {
            java.util.Set<org.eclipse.jface.viewers.TreePath> result = new java.util.LinkedHashSet<>(expandTargets);
            expandTargets.clear();
            return result;
        }

        @Override
        public boolean select(org.eclipse.jface.viewers.Viewer viewer, Object parentElement, Object element)
        {
            if (matcher.isEmpty)
                return true;
            if (!(viewer instanceof TreeViewer treeViewer))
                return true;
            Object cp = treeViewer.getContentProvider();
            if (!(cp instanceof org.eclipse.jface.viewers.ITreeContentProvider tcp))
                return true;

            String elemText = text(element);
            // Текст родителя заходит в матчинг ТОЛЬКО через иерархический режим
            // SmartMatcher.matchesTree ("родитель.имя" по секциям — откат к обычному matches()
            // при однословном фильтре без точки), а не отдельной независимой проверкой родителя.
            String parentText = parentElement != null ? text(parentElement) : ""; //$NON-NLS-1$
            String fullName = parentText.isEmpty() ? elemText : parentText + "." + elemText; //$NON-NLS-1$

            // Родитель уже совпал сам по себе — этот узел (и весь его поддерево) виден целиком,
            // без дальнейшей фильтрации по имени.
            if (parentElement != null && fullyMatchedContainers.contains(parentElement))
            {
                if (isTableLike(element))
                    fullyMatchedContainers.add(element);
                return true;
            }

            if (isTableLike(element))
            {
                if (matcher.matchesTree(fullName))
                {
                    fullyMatchedContainers.add(element);
                    return true;
                }
                // Собственное имя не совпало — таблица всё равно видима, если внутри неё есть
                // совпавшая табличная часть (3-й уровень); тогда саму таблицу нужно развернуть
                // точечно (markContainerOnMatch=true), чтобы совпадение было видно.
                return hasMatchingDescendant(tcp, element, elemText, 1, true, parentElement);
            }
            // Категория верхнего уровня (не Table, см. isTableLike) — скрываем, если внутри нет ни
            // одной совпавшей таблицы ИЛИ табличной части глубже (глубина 2: категория→таблица→
            // табличная часть) — иначе прячем всю ветку, даже если совпадение есть, просто на
            // 3-м уровне, а мы проверяли только 1-й (это и была причина «не находит ветку»).
            // Саму категорию разворачивать не нужно (markContainerOnMatch=false) — она и так
            // видна как верхний уровень.
            if (element instanceof com._1c.g5.v8.dt.qw.ui.utils.AvailableTable)
                return hasMatchingDescendant(tcp, element, elemText, 2, false, parentElement);
            // Обычное поле (не "Таблица", не категория) — видимо только при собственном совпадении.
            return matcher.matchesTree(fullName);
        }

        /**
         * Есть ли совпадение среди потомков (только "Таблица"/табличные части — обычные поля не
         * интересуют, у них своя видимость через {@link #select}), вглубь максимум
         * {@code remainingDepth} уровней. При найденном совпадении — если {@code markContainerOnMatch}
         * и {@code container} сам "Таблица" (не категория) — кладёт {@code TreePath(containerParent,
         * container)} в {@link #expandTargets}, чтобы потом развернуть точечно именно этот
         * контейнер, а не всё дерево.
         */
        private boolean hasMatchingDescendant(org.eclipse.jface.viewers.ITreeContentProvider tcp,
            Object container, String containerText, int remainingDepth, boolean markContainerOnMatch,
            Object containerParent)
        {
            if (remainingDepth <= 0)
                return false;
            boolean found = false;
            for (Object child : tcp.getChildren(container))
            {
                if (!isTableLike(child) && !(child instanceof com._1c.g5.v8.dt.qw.ui.utils.AvailableTable))
                    continue;
                String childText = text(child);
                String fullName = containerText.isEmpty() ? childText : containerText + "." + childText; //$NON-NLS-1$
                if (matcher.matchesTree(fullName) || (remainingDepth > 1
                    && hasMatchingDescendant(tcp, child, fullName, remainingDepth - 1, true, container)))
                {
                    found = true;
                    break;
                }
            }
            if (found && markContainerOnMatch && isTableLike(container))
            {
                Object[] segments = containerParent != null
                    ? new Object[] { containerParent, container } : new Object[] { container };
                expandTargets.add(new org.eclipse.jface.viewers.TreePath(segments));
            }
            return found;
        }

        private String text(Object element)
        {
            org.eclipse.jface.viewers.StyledString text = styled.getStyledText(element);
            return text != null ? text.getString() : ""; //$NON-NLS-1$
        }
    }

    private static org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider
        resolveStyledLabelProvider(TreeViewer tree)
    {
        Object lp = tree.getLabelProvider();
        if (lp instanceof org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider delegating)
            return delegating.getStyledStringProvider();
        return null;
    }

    private static void setSearchListener(SearchBox searchBox, Object listener)
    {
        try
        {
            Field f = SearchBox.class.getDeclaredField("searchListener"); //$NON-NLS-1$
            f.setAccessible(true);
            f.set(searchBox, listener);
        }
        catch (Exception e)
        {
            Debug.problem("setSearchListener: " + e); //$NON-NLS-1$
        }
    }

    /**
     * Непустой фильтр — полный сброс input (см. ниже, почему) + {@code expandToLevel(2)}
     * (категории → таблицы; {@code (1)} на этом дереве оказался no-op) + точечное раскрытие
     * КОНКРЕТНЫХ таблиц, у которых совпадение только в табличной части (см.
     * {@link AvailableTableSearchFilter#drainExpandTargets}) — не блочный
     * {@code expandToLevel(3)}, который разворачивал вообще все видимые таблицы без разбора.
     * Очистка — сворачиваем всё ({@code collapseAll}), но восстанавливаем текущую строку:
     * запоминаем выделение ДО сворачивания и ставим обратно через {@code setSelection(..., true)}
     * — {@code reveal=true} штатно разворачивает только путь к ней, а не всё дерево.
     */
    private static void onSearch(String text, TreeViewer tree, AvailableTableSearchFilter filter,
        SmartOutlineLabelProvider highlighter)
    {
        filter.setPattern(text);
        highlighter.setHighlightPattern(text);
        if (text == null || text.isEmpty())
        {
            org.eclipse.jface.viewers.ISelection selection = tree.getSelection();
            tree.collapseAll();
            tree.refresh();
            if (!selection.isEmpty())
                tree.setSelection(selection, true);
            return;
        }
        // collapseAll()+expandToLevel()+refresh() не заставляли JFace переспросить select() для
        // уже материализованных узлов "Таблица" — их select() не вызывался вообще, в отличие от их
        // полей. Полный сброс input — гарантированно сбрасывает все ассоциации на всех уровнях
        // дерева, без гадания о конкретном месте кэширования в JFace.
        Object input = tree.getInput();
        tree.setInput(null);
        tree.setInput(input);
        tree.expandToLevel(2);
        for (org.eclipse.jface.viewers.TreePath target : filter.drainExpandTargets())
            tree.expandToLevel(target, 1);
    }

    private static final class Debug
    {
        static final String TAG = "QConstructorFilter"; //$NON-NLS-1$

        private Debug()
        {
        }

        static boolean isEnabled()
        {
            return Global.isLogEnabled();
        }

        static void log(String msg)
        {
            if (isEnabled())
                Global.log(TAG, msg);
        }

        static void problem(String msg)
        {
            if (isEnabled())
                Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
        }
    }
}
