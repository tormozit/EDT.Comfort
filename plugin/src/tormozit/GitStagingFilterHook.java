package tormozit;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Text;
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
 * Многословный фильтр ({@link SmartMatcher}, AND по словам) в панели «Индексирование Git»
 * ({@code com._1c.g5.v8.dt.internal.team.ui.views.DtStagingView}, наследник EGit
 * {@code org.eclipse.egit.ui.internal.staging.StagingView}).
 *
 * <p>Разведка декомпиляцией + рефлексией показала: списки «Индексированные»/«Неиндексированные
 * изменения» — {@code TreeViewer} (поля {@code stagedViewer}/{@code unstagedViewer}), общее поле
 * поиска {@code filterText} ({@code Text}, обычный wildcard EGit) на весь вид. У {@code DtStagingView}
 * своего {@code SearchBox}/{@code ViewerFilter} нет.
 *
 * <p>Штатный {@code StagingViewContentProvider} матчит только {@code entry.getPath()} — для
 * фильтрации по «ПолноеИмя + ИмяФайла» (полное имя объекта метаданных не хранится в пути) этого
 * недостаточно, поэтому вместо подмены штатного {@code Pattern} используем свой
 * {@link GitStagingSearchFilter} поверх дерева (с текстом матчинга — {@link #matchText}), а штатное
 * поле {@code filterPattern} оставляем всегда {@code null} (штатный контент-провайдер отдаёт все
 * элементы без своей фильтрации — реальную фильтрацию делает уже наш {@code ViewerFilter}).
 *
 * <p><b>Не {@link SmartOutlineFilter}:</b> тот у листьев матчит через {@code matcher.matchesTree()} —
 * при однословном (без точек) запросе это фактически {@code matches()} только по ПОСЛЕДНЕМУ
 * dot-сегменту текста узла (рассчитан на «имя типа»/«имя узла дерева» без внутренних точек). Наш
 * {@link #matchText} сам содержит точки («ОбщийМодуль.малыйМодуль.Модуль Module.bsl») — с
 * {@code SmartOutlineFilter} матчилось бы только «bsl» после последней точки, теряя всё остальное
 * (обнаружено на «малы мо»: не находило «малыйМодуль», хотя оба фрагмента в тексте есть). Поэтому
 * {@link GitStagingSearchFilter} матчит листья простым {@code matcher.matches(text)} (плоский AND
 * по фрагментам, без dot-иерархии), а «показать папку, если внутри есть совпадение» — своей
 * рекурсией по {@link ITreeContentProvider}.
 *
 * <p>Дополнительно к подсветке — {@link #GitStagingLabelWrapper} дописывает к штатному тексту
 * строки полное имя объекта метаданных через « - » (см. {@link GetRef#resolveFullNameOrNull}),
 * как в панели «Результаты поиска» ({@link FileSearchResultsHook}), но не отдельной колонкой —
 * прямо в тексте единственной колонки дерева (по просьбе пользователя).
 *
 * <p>Логирование: Параметры → Комфорт → «Общее логирование».
 */
public final class GitStagingFilterHook implements IStartup
{
    private static final String VIEW_ID = "com._1c.g5.v8.dt.internal.team.ui.views.DtStagingView"; //$NON-NLS-1$
    private static final String PATCHED_KEY = "tormozit.gitStagingFilterPatched"; //$NON-NLS-1$
    private static final String HISTORY_SCOPE_ID = "gitStagingFilter"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
                return;
            for (IWorkbenchWindow window : wb.getWorkbenchWindows())
                hookWindow(window);
            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
            Debug.log("earlyStartup: installed"); //$NON-NLS-1$
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        for (IWorkbenchPage page : window.getPages())
        {
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (isGitStagingView(view))
                    schedulePatch(view, 0);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r) {}
            @Override public void partDeactivated(IWorkbenchPartReference r) {}
            @Override public void partHidden(IWorkbenchPartReference r) {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}

            private void tryFromRef(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (isGitStagingView(part))
                    schedulePatch((IViewPart) part, 0);
            }
        });
    }

    private static boolean isGitStagingView(Object part)
    {
        if (!(part instanceof IViewPart view))
            return false;
        return VIEW_ID.equals(view.getSite().getId());
    }

    private static void schedulePatch(IViewPart view, int attempt)
    {
        Display display = Display.getDefault();
        int delay = attempt == 0 ? 0 : 100;
        display.timerExec(delay, () ->
        {
            if (!tryPatch(view) && attempt < 20)
                schedulePatch(view, attempt + 1);
            else if (attempt >= 20)
                Debug.log("tryPatch GIVE UP after 20 attempts"); //$NON-NLS-1$
        });
    }

    private static boolean tryPatch(IViewPart view)
    {
        try
        {
            Object filterTextObj = Global.getField(view, "filterText"); //$NON-NLS-1$
            if (!(filterTextObj instanceof Text filterText) || filterText.isDisposed())
            {
                Debug.log("tryPatch WAIT: filterText=" //$NON-NLS-1$
                    + (filterTextObj == null ? "null" : filterTextObj.getClass().getName())); //$NON-NLS-1$
                return false;
            }
            if (Boolean.TRUE.equals(filterText.getData(PATCHED_KEY)))
                return true;

            GitStagingSearchFilter stagedFilter = installViewer(view, "stagedViewer"); //$NON-NLS-1$
            GitStagingSearchFilter unstagedFilter = installViewer(view, "unstagedViewer"); //$NON-NLS-1$
            if (stagedFilter == null && unstagedFilter == null)
            {
                Debug.log("tryPatch WAIT: no viewers ready"); //$NON-NLS-1$
                return false;
            }

            // Штатный Pattern больше не используется нашим фильтром — держим поле пустым,
            // чтобы StagingViewContentProvider отдавал все элементы без своей фильтрации.
            Global.setField(view, "filterPattern", null); //$NON-NLS-1$

            stripModifyListeners(filterText);
            filterText.setData(PATCHED_KEY, Boolean.TRUE);
            filterText.addListener(SWT.Modify, e ->
                applyFilter(view, filterText, stagedFilter, unstagedFilter));
            filterText.setToolTipText(
                FilterInputBox.FLAT_FILTER_TOOLTIP + "\nCtrl+↓ — история запросов."); //$NON-NLS-1$
            FilterHistoryUi.wireKeyboard(filterText, HISTORY_SCOPE_ID);
            addHistoryButton(filterText);

            applyFilter(view, filterText, stagedFilter, unstagedFilter);

            Debug.log("tryPatch PATCH OK"); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            Debug.log("tryPatch EXCEPTION: " + e); //$NON-NLS-1$
            return false;
        }
    }

    private static void stripModifyListeners(Text filterText)
    {
        for (Listener l : filterText.getListeners(SWT.Modify))
            filterText.removeListener(SWT.Modify, l);
    }

    /**
     * Видимая кнопка-стрелка (▾) справа от {@code filterText}. Родитель поля в {@code DtStagingView} —
     * {@code GridLayout} с {@code filterText} как единственным дочерним элементом; сам
     * {@code filterText} держал широкий (нет {@code GridData}/натуральный) размер, поэтому после
     * добавления второй колонки ему нужен явный {@code grab+fill}, иначе колонка с кнопкой
     * вылезает за пределы родителя (подтверждено диагностикой, снята после фикса).
     */
    private static void addHistoryButton(Text filterText)
    {
        try
        {
            Composite parent = filterText.getParent();
            if (parent == null || parent.isDisposed())
                return;
            Composite row = FilterHistoryUi.createButtonsRow(parent);
            FilterHistoryUi.addHistoryButton(row, filterText, HISTORY_SCOPE_ID);
            filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            parent.layout(true, true);
        }
        catch (Exception e)
        {
            Debug.log("addHistoryButton EXCEPTION: " + e); //$NON-NLS-1$
        }
    }

    /** Оборачивает label provider и вешает {@link GitStagingSearchFilter}; {@code null}, если вьювер не готов. */
    private static GitStagingSearchFilter installViewer(IViewPart view, String viewerField)
    {
        Object viewerObj = Global.getField(view, viewerField);
        if (!(viewerObj instanceof TreeViewer viewer))
            return null;

        IBaseLabelProvider current = viewer.getLabelProvider();
        if (current instanceof GitStagingLabelWrapper)
        {
            GitStagingSearchFilter filter = findExistingFilter(viewer);
            return filter != null ? filter : attachFilter(viewer);
        }
        if (!(current instanceof CellLabelProvider cellLp))
        {
            Debug.log("installViewer " + viewerField + ": lp=" //$NON-NLS-1$ //$NON-NLS-2$
                + (current != null ? current.getClass().getName() : "null")); //$NON-NLS-1$
            return null;
        }

        viewer.setLabelProvider(new GitStagingLabelWrapper(cellLp));
        return attachFilter(viewer);
    }

    private static GitStagingSearchFilter findExistingFilter(TreeViewer viewer)
    {
        for (ViewerFilter f : viewer.getFilters())
            if (f instanceof GitStagingSearchFilter gsf)
                return gsf;
        return null;
    }

    private static GitStagingSearchFilter attachFilter(TreeViewer viewer)
    {
        GitStagingSearchFilter filter = new GitStagingSearchFilter();
        filter.captureInitialExpandedElements(viewer);
        viewer.addFilter(filter);
        return filter;
    }

    // -----------------------------------------------------------------------
    // Фильтрация
    // -----------------------------------------------------------------------

    private static void applyFilter(IViewPart view, Text filterText,
        GitStagingSearchFilter stagedFilter, GitStagingSearchFilter unstagedFilter)
    {
        if (filterText.isDisposed())
            return;
        String text = filterText.getText();

        refreshOneViewer(view, "stagedViewer", stagedFilter, text); //$NON-NLS-1$
        refreshOneViewer(view, "unstagedViewer", unstagedFilter, text); //$NON-NLS-1$

        Debug.log("applyFilter text=\"" + text + "\""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void refreshOneViewer(IViewPart view, String viewerField,
        GitStagingSearchFilter filter, String text)
    {
        if (filter == null)
            return;
        Object viewerObj = Global.getField(view, viewerField);
        if (!(viewerObj instanceof TreeViewer viewer) || viewer.getControl().isDisposed())
            return;

        filter.setPattern(text);
        IBaseLabelProvider lp = viewer.getLabelProvider();
        if (lp instanceof GitStagingLabelWrapper wrapper)
            wrapper.setHighlightPattern(text);

        viewer.getTree().setRedraw(false);
        try
        {
            viewer.refresh();
            // Раскрытие веток к совпадениям — только пока реально фильтруем; при очистке поля
            // возвращаемся к снимку раскрытости на момент патча, а не трогаем текущее состояние
            // ещё раз (см. javadoc класса), иначе схлопывали бы то, что пользователь успел
            // раскрыть руками в промежутке.
            if (filter.isFiltering())
                viewer.expandAll();
            else
                filter.restoreInitialExpandedElements(viewer);
        }
        finally
        {
            viewer.getTree().setRedraw(true);
        }
    }

    /** «ПолноеИмя ИмяФайла» для листа ({@code getPath()} есть); "" для остальных узлов (папки и т.п.). */
    private static String matchText(Object element)
    {
        Object pathObj = Global.invoke(element, "getPath"); //$NON-NLS-1$
        if (!(pathObj instanceof String path) || path.isEmpty())
            return ""; //$NON-NLS-1$
        String fileName = fileNameOf(path);
        String fullName = GetRef.resolveFullNameOrNull(path);
        return fullName != null && !fullName.isEmpty() ? fullName + " " + fileName : fileName; //$NON-NLS-1$
    }

    private static String fileNameOf(String path)
    {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /**
     * Плоский AND по фрагментам {@link SmartMatcher} для листьев ({@link #matchText}, без
     * dot-иерархии — см. javadoc класса, почему не {@link SmartOutlineFilter}); для узлов без
     * своего пути (папки) — рекурсивная проверка «есть ли совпадение среди потомков».
     */
    private static final class GitStagingSearchFilter extends ViewerFilter
    {
        private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$
        private final Map<Object, Boolean> subtreeMemo = new IdentityHashMap<>();
        private Object[] initialExpandedElements = new Object[0];

        void setPattern(String pattern)
        {
            matcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
            subtreeMemo.clear();
        }

        boolean isFiltering()
        {
            return !matcher.isEmpty;
        }

        void captureInitialExpandedElements(TreeViewer viewer)
        {
            Object[] current = viewer.getExpandedElements();
            initialExpandedElements = current != null ? current : new Object[0];
        }

        void restoreInitialExpandedElements(TreeViewer viewer)
        {
            viewer.setExpandedElements(initialExpandedElements);
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            if (matcher.isEmpty)
                return true;
            String text = matchText(element);
            if (!text.isEmpty())
                return matcher.matches(text);

            // Узел без своего пути (папка и т.п.) — виден, только если внутри есть совпадение.
            if (!(viewer instanceof TreeViewer treeViewer))
                return true;
            Object cp = treeViewer.getContentProvider();
            if (!(cp instanceof ITreeContentProvider tcp))
                return true;
            return hasMatchInSubtree(tcp, element);
        }

        private boolean hasMatchInSubtree(ITreeContentProvider tcp, Object element)
        {
            Boolean memo = subtreeMemo.get(element);
            if (memo != null)
                return memo.booleanValue();

            boolean result = false;
            for (Object child : tcp.getChildren(element))
            {
                String childText = matchText(child);
                boolean childMatches = !childText.isEmpty()
                    ? matcher.matches(childText) : hasMatchInSubtree(tcp, child);
                if (childMatches)
                {
                    result = true;
                    break;
                }
            }
            subtreeMemo.put(element, result);
            return result;
        }
    }

    // -----------------------------------------------------------------------
    // Подсветка + дописывание полного имени в текст строки
    // -----------------------------------------------------------------------

    /**
     * Дописывает « - <ПолноеИмя>» к штатному тексту строки (для листьев дерева — {@code getPath()}
     * есть) и подсвечивает совпавшие фрагменты {@link SmartMatcher} во всём получившемся тексте.
     */
    private static final class GitStagingLabelWrapper extends StyledCellLabelProvider
        implements ILabelProvider
    {
        private final CellLabelProvider base;
        private SmartMatcher highlightMatcher = new SmartMatcher(""); //$NON-NLS-1$

        GitStagingLabelWrapper(CellLabelProvider base)
        {
            this.base = base;
        }

        void setHighlightPattern(String pattern)
        {
            highlightMatcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
        }

        private String appendFullName(String baseText, Object element)
        {
            Object pathObj = Global.invoke(element, "getPath"); //$NON-NLS-1$
            if (!(pathObj instanceof String path) || path.isEmpty())
                return baseText;
            String fullName = GetRef.resolveFullNameOrNull(path);
            return fullName != null && !fullName.isEmpty() ? baseText + " - " + fullName : baseText; //$NON-NLS-1$
        }

        @Override
        public void update(ViewerCell cell)
        {
            if (base != null)
                base.update(cell);
            if (cell == null)
                return;
            String text = appendFullName(cell.getText() != null ? cell.getText() : "", cell.getElement()); //$NON-NLS-1$
            if (!text.equals(cell.getText()))
                cell.setText(text);
            // Вызываем всегда, а не только при непустом фильтре — иначе при очистке поля старые
            // StyleRange (SWT переиспользует TreeItem между refresh-ами) остаются висеть.
            // appendMatchRanges сам корректно очищает ячейку при пустом списке диапазонов.
            List<SmartMatcher.HighlightRange> ranges = !highlightMatcher.isEmpty && !text.isEmpty()
                ? highlightMatcher.getHighlightRanges(text) : List.of();
            SmartMatchHighlight.appendMatchRanges(cell, ranges);
        }

        @Override
        public String getText(Object element)
        {
            String baseText;
            if (base instanceof ILabelProvider bl)
                baseText = bl.getText(element);
            else
            {
                Object text = Global.invoke(base, "getText", element); //$NON-NLS-1$
                baseText = text instanceof String s ? s : ""; //$NON-NLS-1$
            }
            return appendFullName(baseText != null ? baseText : "", element); //$NON-NLS-1$
        }

        @Override
        public Image getImage(Object element)
        {
            if (base instanceof ILabelProvider bl)
                return bl.getImage(element);
            Object img = Global.invoke(base, "getImage", element); //$NON-NLS-1$
            return img instanceof Image ? (Image) img : null;
        }

        @Override
        public void addListener(ILabelProviderListener listener)
        {
            base.addListener(listener);
        }

        @Override
        public void removeListener(ILabelProviderListener listener)
        {
            base.removeListener(listener);
        }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            return base.isLabelProperty(element, property);
        }

        @Override
        public void dispose()
        {
            base.dispose();
        }
    }

    // -----------------------------------------------------------------------
    // Логи
    // -----------------------------------------------------------------------

    private static final class Debug
    {
        private static final String TAG = "GitStagingFilter"; //$NON-NLS-1$

        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
