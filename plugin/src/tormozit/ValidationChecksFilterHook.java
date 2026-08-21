package tormozit;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;
import com._1c.g5.v8.dt.ui.validation.ChecksViewerControl;
import com._1c.g5.v8.dt.ui.validation.IChecksTreeNode;
import com.e1c.g5.v8.dt.check.settings.INamedElement;

/**
 * Многословный фильтр ({@link SmartMatcher}, AND по словам) на странице «Валидация»
 * (Свойства проекта / Параметры → V8 → Валидация,
 * {@code com._1c.g5.v8.dt.internal.ui.validation.ValidationPreferencePage}).
 *
 * <p>Разведка декомпиляцией показала: поле поиска там уже штатный
 * {@link SearchBox} (не голый {@code Text}), но матчинг по тексту делает не
 * {@code ViewerFilter}, а отдельный {@code CheckFilter} (predicate,
 * {@code com._1c.g5.v8.dt.common.StringUtils.fuzzyMatch} — нечёткая подстрока)
 * прямо внутри content-провайдера дерева ({@code ChecksTreeProvider}). Пакет
 * {@code internal.ui.validation} не экспортирован — {@code CheckFilter}/
 * {@code ChecksTreeViewer}/страница недоступны для прямого импорта, только
 * рефлексия; {@code ChecksViewerControl}/{@code IChecksTreeNode}
 * (пакет {@code com._1c.g5.v8.dt.ui.validation}) экспортированы и используются
 * напрямую.
 *
 * <p>Согласовано с пользователем: поверх штатного дерева добавляется
 * собственный {@link ViewerFilter} на {@link SmartMatcher}, а штатный
 * {@code CheckFilter} не трогается вовсе — вместо него {@code SearchBox}
 * получает свою персистентную историю ({@link FilterInputBox#attachHistoryKeepLayout}
 * / {@link FilterInputBox.Scope#VALIDATION_CHECKS} — обязательно, см. правило
 * «Подключение фильтра» в AGENTS.md: голая {@code InMemorySearchHistory} теряет
 * историю при закрытии диалога; compact-ширину не ставим — штатное поле
 * тянется в строке с тулбаром), поэтому {@code CheckFilter.getActivePattern()}
 * остаётся пустым навсегда и его текстовый матчинг (fuzzy) становится
 * безусловно {@code true} ({@code testSearchWithoutId} — байткод подтверждает
 * short-circuit на пустом паттерне); фильтры по важности/типу/умолчанию в
 * {@code CheckFilter} продолжают работать как раньше. Это исключает риск
 * порчи истории поиска (которую до правки хранил тот же {@code CheckFilter}
 * как {@code ISearchHistory}) — вариант с принудительным сбросом его паттерна
 * после каждого поиска отвергнут именно из-за этого риска.
 *
 * <p>Узел дерева виден, если под {@link SmartMatcher#matches(String)}
 * попадает его собственный заголовок ({@link IChecksTreeNode#getValue()}.
 * {@code getTitle()}) или заголовок любого потомка — правило одинаково для
 * категорий и отдельных проверок.
 *
 * <p>Подсветка совпадений: у страницы уже есть штатная подсветка
 * ({@code SearchStyledLabelProviderDelegate} на единственной колонке дерева),
 * но она тоже читает паттерн из {@code ISearchHistory}, переданного в
 * конструктор {@code ChecksTreeViewer} (тот же {@code CheckFilter}) —
 * захваченного ОДИН РАЗ при создании дерева, до нашего патча. Подмена
 * {@code searchBox.setHistory(...)} на неё не влияет (другой объект), поэтому
 * своя подсветка через {@link CellLabelHighlightWrapper} на колонке 0
 * {@code checksViewer} — отдельно от {@link ValidationSearchFilter}.
 *
 * <p>Ctrl+C на дереве проверок и полях страницы (идентификатор {@code StyledText},
 * параметры проверки, …) — через {@link CopyCommandSupport}: в диалоге свойств
 * Win32-акселератор Copy съедает клавишу до SWT. Поля параметров пересоздаются
 * при смене строки — донастраиваем копирование лениво по {@code FocusIn}.
 */
public final class ValidationChecksFilterHook implements IStartup
{
    private static final String PAGE_CLASS_NAME =
        "com._1c.g5.v8.dt.internal.ui.validation.ValidationPreferencePage"; //$NON-NLS-1$
    private static final String PATCHED_KEY = "tormozit.validationChecksFilterPatched"; //$NON-NLS-1$
    private static final String PAGE_COPY_ROOT_KEY = "tormozit.validationPageCopyRoot"; //$NON-NLS-1$
    private static final String COPY_WIRED_KEY = "tormozit.validationCopyWired"; //$NON-NLS-1$
    private static final int MAX_ATTEMPTS = 30;
    private static final int RETRY_MS = 100;

    private static final WeakHashMap<Shell, Boolean> pendingWiring = new WeakHashMap<>();
    private static boolean lazyCopyFilterInstalled;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> install(display));
    }

    private static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = event ->
        {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            PreferenceDialog dialog = findPreferenceDialog(shell);
            if (dialog == null)
                return;
            scheduleWireOnce(display, shell, dialog);
        };

        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
        Debug.log("install: installed"); //$NON-NLS-1$
    }

    private static PreferenceDialog findPreferenceDialog(Shell shell)
    {
        Shell current = shell;
        while (current != null && !current.isDisposed())
        {
            if (current.getData() instanceof PreferenceDialog dialog)
                return dialog;
            current = current.getParent() instanceof Shell parent ? parent : null;
        }
        return null;
    }

    private static void scheduleWireOnce(Display display, Shell shell, PreferenceDialog dialog)
    {
        synchronized (pendingWiring)
        {
            if (Boolean.TRUE.equals(pendingWiring.get(shell)))
                return;
            pendingWiring.put(shell, Boolean.TRUE);
        }

        IPageChangedListener pageListener = event -> tryPatchSelected(event.getSelectedPage());
        dialog.addPageChangedListener(pageListener);
        scheduleRetry(display, shell, dialog, 0);
    }

    private static void scheduleRetry(Display display, Shell shell, PreferenceDialog dialog, int attempt)
    {
        if (shell.isDisposed())
            return;
        if (tryPatchSelected(dialog.getSelectedPage()) || attempt >= MAX_ATTEMPTS)
            return;
        display.timerExec(RETRY_MS, () -> scheduleRetry(display, shell, dialog, attempt + 1));
    }

    /** @return {@code true}, если для текущей выбранной страницы больше нечего делать (не наша страница или уже пропатчена). */
    private static boolean tryPatchSelected(Object selected)
    {
        if (!(selected instanceof IPreferencePage page) || !PAGE_CLASS_NAME.equals(page.getClass().getName()))
            return true;
        return tryPatch(page);
    }

    private static boolean tryPatch(IPreferencePage page)
    {
        try
        {
            Object controlObj = Global.getField(page, "checksViewerControl"); //$NON-NLS-1$
            if (!(controlObj instanceof ChecksViewerControl control))
            {
                Debug.log("tryPatch WAIT: checksViewerControl=" //$NON-NLS-1$
                    + (controlObj == null ? "null" : controlObj.getClass().getName())); //$NON-NLS-1$
                return false;
            }

            Object treeViewerObj = Global.getField(control, "checksViewer"); //$NON-NLS-1$
            if (!(treeViewerObj instanceof TreeViewer treeViewer) || treeViewer.getControl().isDisposed())
            {
                Debug.log("tryPatch WAIT: checksViewer not ready"); //$NON-NLS-1$
                return false;
            }
            if (Boolean.TRUE.equals(treeViewer.getControl().getData(PATCHED_KEY)))
                return true;

            Control pageControl = page.getControl();
            SearchBox searchBox = pageControl != null ? findSearchBox(pageControl) : null;
            if (searchBox == null)
            {
                Debug.log("tryPatch WAIT: searchBox not found"); //$NON-NLS-1$
                return false;
            }

            ValidationSearchFilter filter = new ValidationSearchFilter();
            filter.captureInitialExpanded(treeViewer);
            treeViewer.addFilter(filter);
            CellLabelHighlightWrapper highlight = installHighlight(treeViewer);

            // Персистентная история (см. правило «Подключение фильтра» в AGENTS.md) —
            // штатный CheckFilter (см. javadoc класса) больше не получает savePattern()
            // и его activePattern остаётся пустым.
            // Compact 300 px здесь слишком узкий: штатно SearchBox тянется
            // в строке с тулбаром (fillDefaults + minSize 100 + grab).
            FilterInputBox.attachHistoryKeepLayout(searchBox, FilterInputBox.Scope.VALIDATION_CHECKS);
            searchBox.setSearchListener((text, monitor) -> applySearch(treeViewer, filter, highlight, text));
            searchBox.setToolTipText(FilterInputBox.FLAT_FILTER_TOOLTIP);

            if (pageControl != null)
            {
                pageControl.setData(PAGE_COPY_ROOT_KEY, Boolean.TRUE);
                wirePageCopy(pageControl);
                installLazyCopyFilter(treeViewer.getControl().getDisplay());
            }

            treeViewer.getControl().setData(PATCHED_KEY, Boolean.TRUE);
            Debug.log("tryPatch PATCH OK"); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            Debug.log("tryPatch EXCEPTION: " + e); //$NON-NLS-1$
            return false;
        }
    }

    /** {@code performSearch} у {@link SearchBox} может прийти не с UI-потока — как и в штатном коде, оборачиваем в {@code BusyIndicator.showWhile}. */
    private static void applySearch(TreeViewer treeViewer, ValidationSearchFilter filter,
        CellLabelHighlightWrapper highlight, String text)
    {
        Display display = treeViewer.getControl().getDisplay();
        if (display == null || display.isDisposed())
            return;
        BusyIndicator.showWhile(display, () ->
        {
            if (treeViewer.getControl().isDisposed())
                return;
            boolean wasFiltering = filter.isFiltering();
            // При очистке фильтра refresh + restoreInitialExpanded теряют/прячут
            // текущую строку — сохраняем выделение до refresh (как InfobasesViewHook /
            // SmartOutlineHook) и восстанавливаем с reveal после свёртки.
            IStructuredSelection savedSelection = null;
            if (wasFiltering && treeViewer.getSelection() instanceof IStructuredSelection ss
                && !ss.isEmpty())
                savedSelection = ss;
            filter.setPattern(text);
            if (highlight != null)
                highlight.setHighlightPattern(text);
            treeViewer.getControl().setRedraw(false);
            try
            {
                treeViewer.refresh();
                if (filter.isFiltering())
                    treeViewer.expandAll();
                else if (wasFiltering)
                {
                    filter.restoreInitialExpanded(treeViewer);
                    if (savedSelection != null)
                    {
                        // После свёртки виджеты потомков уничтожены — без
                        // раскрытия предков setSelection не найдёт строку.
                        expandAncestors(treeViewer, savedSelection.getFirstElement());
                        treeViewer.setSelection(savedSelection, true);
                    }
                }
            }
            finally
            {
                treeViewer.getControl().setRedraw(true);
            }
        });
    }

    /** Раскрывает цепочку родителей элемента через {@link ITreeContentProvider#getParent}. */
    private static void expandAncestors(TreeViewer viewer, Object element)
    {
        if (element == null
            || !(viewer.getContentProvider() instanceof ITreeContentProvider tcp))
            return;
        Object parent = tcp.getParent(element);
        while (parent != null)
        {
            viewer.setExpandedState(parent, true);
            parent = tcp.getParent(parent);
        }
    }

    private static SearchBox findSearchBox(Control control)
    {
        if (control instanceof SearchBox searchBox)
            return searchBox;
        if (control instanceof Composite composite)
            for (Control child : composite.getChildren())
            {
                SearchBox found = findSearchBox(child);
                if (found != null)
                    return found;
            }
        return null;
    }

    /**
     * Ctrl+C на дереве и текстовых полях страницы (см. javadoc класса).
     * Обход {@code page.getControl()} покрывает дерево и постоянные поля
     * ({@code idTxt} и т.п.); поля параметров проверки пересоздаются при смене
     * строки — их донастраивает {@link #installLazyCopyFilter}.
     */
    private static void wirePageCopy(Control control)
    {
        if (control == null || control.isDisposed())
            return;
        if (isCopyableControl(control) && !Boolean.TRUE.equals(control.getData(COPY_WIRED_KEY)))
        {
            control.setData(COPY_WIRED_KEY, Boolean.TRUE);
            CopyCommandSupport.wireCopyOverride(control);
        }
        if (control instanceof Composite composite)
            for (Control child : composite.getChildren())
                wirePageCopy(child);
    }

    private static void installLazyCopyFilter(Display display)
    {
        if (lazyCopyFilterInstalled || display == null || display.isDisposed())
            return;
        display.addFilter(SWT.FocusIn, event ->
        {
            if (!(event.widget instanceof Control focus) || focus.isDisposed())
                return;
            if (!isCopyableControl(focus) || Boolean.TRUE.equals(focus.getData(COPY_WIRED_KEY)))
                return;
            if (!isUnderValidationCopyRoot(focus))
                return;
            focus.setData(COPY_WIRED_KEY, Boolean.TRUE);
            CopyCommandSupport.wireCopyOverride(focus);
        });
        lazyCopyFilterInstalled = true;
    }

    private static boolean isCopyableControl(Control control)
    {
        return control instanceof Tree
            || control instanceof Text
            || control instanceof StyledText
            || control instanceof Combo;
    }

    private static boolean isUnderValidationCopyRoot(Control control)
    {
        for (Control c = control; c != null && !c.isDisposed(); c = c.getParent())
        {
            if (Boolean.TRUE.equals(c.getData(PAGE_COPY_ROOT_KEY)))
                return true;
        }
        return false;
    }

    /** Оборачивает label provider колонки 0 {@code checksViewer} в {@link CellLabelHighlightWrapper} (если ещё не обёрнут). */
    private static CellLabelHighlightWrapper installHighlight(TreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed() || tree.getColumnCount() == 0)
            return null;

        Object columnObj = Global.invoke(viewer, "getViewerColumn", Integer.valueOf(0)); //$NON-NLS-1$
        if (!(columnObj instanceof TreeViewerColumn column))
            return null;

        Object lpObj = Global.invoke(column, "getLabelProvider"); //$NON-NLS-1$
        if (lpObj instanceof CellLabelHighlightWrapper existing)
            return existing;
        if (!(lpObj instanceof CellLabelProvider lp))
            return null;

        CellLabelHighlightWrapper wrapper = new CellLabelHighlightWrapper(lp);
        column.setLabelProvider(wrapper);
        return wrapper;
    }

    /**
     * Узел виден, если под {@link SmartMatcher#matches(String)} попадает его
     * заголовок или заголовок любого потомка (правило одинаково для категорий
     * и отдельных проверок — {@link IChecksTreeNode#getValue()} есть у обоих).
     */
    private static final class ValidationSearchFilter extends ViewerFilter
    {
        private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$
        private final Map<Object, Boolean> subtreeMemo = new IdentityHashMap<>();
        private Object[] initialExpanded = new Object[0];

        void setPattern(String pattern)
        {
            matcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
            subtreeMemo.clear();
        }

        boolean isFiltering()
        {
            return !matcher.isEmpty;
        }

        void captureInitialExpanded(TreeViewer viewer)
        {
            Object[] current = viewer.getExpandedElements();
            initialExpanded = current != null ? current : new Object[0];
        }

        void restoreInitialExpanded(TreeViewer viewer)
        {
            viewer.setExpandedElements(initialExpanded);
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            if (matcher.isEmpty)
                return true;
            return matchesOwnOrDescendant(viewer, element);
        }

        private boolean matchesOwnOrDescendant(Viewer viewer, Object element)
        {
            Boolean memo = subtreeMemo.get(element);
            if (memo != null)
                return memo.booleanValue();

            String text = titleOf(element);
            boolean result = text != null && matcher.matches(text);
            if (!result && viewer instanceof TreeViewer treeViewer
                && treeViewer.getContentProvider() instanceof ITreeContentProvider tcp)
            {
                for (Object child : tcp.getChildren(element))
                {
                    if (matchesOwnOrDescendant(viewer, child))
                    {
                        result = true;
                        break;
                    }
                }
            }
            subtreeMemo.put(element, result);
            return result;
        }

        private static String titleOf(Object element)
        {
            if (!(element instanceof IChecksTreeNode node))
                return null;
            INamedElement value = node.getValue();
            String title = value != null ? value.getTitle() : null;
            return title != null && !title.isEmpty() ? title : null;
        }
    }

    private static final class Debug
    {
        private static final String TAG = "ValidationChecksFilter"; //$NON-NLS-1$

        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
