package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.form.model.ElementDataSourceInfo;
import com._1c.g5.v8.dt.form.model.ElementDataSourceInfo.ElementDataSourceInfoType;
import com._1c.g5.v8.dt.form.model.PropertyInfo;
import com._1c.g5.v8.dt.form.ui.editor.attribute.TypeColumnLabelProvider;

/**
 * Окно «Выбор реквизита» — путь к данным элемента формы ({@code DataPathDialog} EDT): многословный фильтр
 * ({@link SmartMatcher}, плоский AND) с персистентной историей и подсветкой вхождений, расчёт
 * которого вынесен в фон (#482).
 *
 * <p>Штатный {@code DataPathDialog$TreeFilter} ({@code TreeViewerFilter}) считает совпадение в
 * {@code select()} — в UI-потоке, рекурсивным обходом потомков до 4-го уровня, заново для каждого
 * узла и без запоминания. Потомки узла ({@code DataPathProvider.getChildren}) в первый раз
 * вычисляются долго (типы реквизитов), поэтому первый ввод фильтра замораживал интерфейс на
 * десятки секунд. Здесь дерево обходится один раз в фоновом задании, а {@link ViewerFilter}
 * только сверяется с готовым результатом.
 *
 * <p>{@code DataPathProvider} не потокобезопасен (обычные {@code HashMap} путей, {@code EList}
 * потомков узла), поэтому поставщик содержимого дерева подменяется на {@link LockedContentProvider}:
 * и фон, и UI ходят к штатному поставщику только под общей блокировкой, по одному узлу за раз.
 */
public final class DataPathDialogHook implements IStartup
{
    private static final String DIALOG_CLASS = "com._1c.g5.v8.dt.form.internal.ui.aef.datapath.DataPathDialog"; //$NON-NLS-1$
    private static final String STOCK_FILTER_CLASS = DIALOG_CLASS + "$TreeFilter"; //$NON-NLS-1$
    private static final String PATCHED_KEY = "tormozit.dataPathDialogPatched"; //$NON-NLS-1$
    private static final String WINDOW_KEY = "org.eclipse.jface.window.Window"; //$NON-NLS-1$
    private static final String SETTINGS_SECTION = "DataPathDialogHook"; //$NON-NLS-1$
    private static final String KEY_DIALOG_WIDTH = "dialogWidth"; //$NON-NLS-1$
    private static final String KEY_DIALOG_HEIGHT = "dialogHeight"; //$NON-NLS-1$
    private static final String KEY_TYPE_COLUMN_WIDTH = "typeColumnWidth"; //$NON-NLS-1$
    private static final int COLUMN_TYPE = 1;
    private static final int WIDTH_NAME_START = 220;
    private static final int WIDTH_TYPE_START = 160;
    private static final int MIN_COLUMN_WIDTH = 20;

    /** Глубина поиска по потомкам — как у штатного фильтра ({@code DataPathDialog.MAXIMUM_EXPAND_LEVEL}). */
    private static final int MAX_DEPTH = 4;
    private static final int FILTER_DELAY_MS = 150;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    private static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        Listener listener = event -> {
            if (event.widget instanceof Shell shell && !shell.isDisposed())
                onShellEvent(shell);
        };
        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
    }

    private static void onShellEvent(Shell shell)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        if (Boolean.TRUE.equals(shell.getData(PATCHED_KEY)))
            return;
        if (resolveDialog(shell) == null)
            return;
        schedulePatch(shell, 0);
    }

    private static void schedulePatch(Shell shell, int attempt)
    {
        Display display = shell.getDisplay();
        int delay = attempt == 0 ? 0 : 50;
        display.timerExec(delay, () -> {
            if (shell.isDisposed() || Boolean.TRUE.equals(shell.getData(PATCHED_KEY)))
                return;
            if (!tryPatch(shell) && attempt < 20)
                schedulePatch(shell, attempt + 1);
        });
    }

    private static boolean tryPatch(Shell shell)
    {
        Object dialog = resolveDialog(shell);
        if (dialog == null)
            return true; // диалог уже закрыт

        Object viewerObj = Global.getField(dialog, "viewer"); //$NON-NLS-1$
        if (!(viewerObj instanceof TreeViewer viewer)
                || viewer.getControl() == null
                || viewer.getControl().isDisposed()
                || !(viewer.getContentProvider() instanceof ITreeContentProvider stockContent))
            return false;
        Object searchObj = Global.getField(dialog, "search"); //$NON-NLS-1$
        if (!(searchObj instanceof Control searchControl) || searchControl.isDisposed())
            return false;
        IStyledLabelProvider labels = SelectionAwareStyledCellLabelProvider.unwrapOrAdapt(viewer.getLabelProvider());
        if (labels == null)
            return false;

        shell.setData(PATCHED_KEY, Boolean.TRUE);

        // Штатный слушатель ввода висит на самом поле и уходит вместе с ним.
        FilterInputBox filterInput =
            FilterInputBox.replacePatternText(searchControl, FilterInputBox.Scope.DATA_PATH_DIALOG, null);
        if (filterInput == null)
        {
            shell.setData(PATCHED_KEY, null);
            return false;
        }

        Session session = new Session(dialog, shell, viewer, new LockedContentProvider(stockContent), labels);

        for (ViewerFilter filter : viewer.getFilters())
        {
            if (filter != null && STOCK_FILTER_CLASS.equals(filter.getClass().getName()))
                viewer.removeFilter(filter);
        }
        installShellSizeMemory(shell);
        installColumns(session);
        viewer.setContentProvider(session.content);
        viewer.addFilter(new ReadyResultFilter(session));

        Control filterControl = filterInput.inputControl();
        if (filterControl == null)
            filterControl = filterInput.widget();
        final Control fc = filterControl;
        addFilterModifyListener(fc, e -> session.onFilterChanged(getFilterPattern(fc)));
        FilterInputBoxListNavigation.installTreeNavigation(fc, viewer.getTree());

        shell.addListener(SWT.Dispose, e -> session.dispose());
        filterInput.scheduleFocusWhenReady();
        return true;
    }

    /**
     * Штатное дерево — без колонок. Заводим две: «Реквизит» (подпись узла с подсветкой вхождений
     * фильтра) и «Тип» — в той же подаче, что колонка «Тип» дерева реквизитов редактора формы
     * ({@code FormEditorHook.AttributesTypePresentation}): текст от штатного
     * {@link TypeColumnLabelProvider}, значок и сокращение ссылочных типов — от
     * {@link ValueTypeColumnLabelProvider}.
     */
    private static void installColumns(Session session)
    {
        TreeViewer viewer = session.viewer;
        Tree tree = viewer.getTree();

        TreeViewerColumn nameColumn = new TreeViewerColumn(viewer, SWT.NONE);
        nameColumn.getColumn().setText("Реквизит"); //$NON-NLS-1$
        nameColumn.setLabelProvider(
            new SelectionAwareStyledCellLabelProvider(new HighlightStyledLabelProvider(session.labels, session)));

        TreeViewerColumn typeColumn = new TreeViewerColumn(viewer, SWT.NONE);
        typeColumn.getColumn().setText("Тип"); //$NON-NLS-1$
        typeColumn.setLabelProvider(new ValueTypeColumnLabelProvider(tree,
            element -> {
                PropertyInfo info = session.content.propertyInfo(element);
                return info != null ? info.getValueType() : null;
            },
            element -> {
                PropertyInfo info = session.content.propertyInfo(element);
                return info != null ? info.getForm() : null;
            },
            typeTextProvider(session.content)));

        tree.setHeaderVisible(true);
        ColumnViewerToolTipSupport.enableFor(viewer);

        // Ширины, выбор ячейки и копирование — общие механизмы плагина, как у дерева элементов
        // формы (FormEditorHook.ItemsTree). «Тип» из подгонки исключён: его ширину задаёт
        // пользователь, и она запоминается; свободный остаток забирает «Реквизит».
        nameColumn.getColumn().setWidth(WIDTH_NAME_START);
        typeColumn.getColumn().setWidth(FormTableColumnState.readWidth(dialogSettings(),
            KEY_TYPE_COLUMN_WIDTH, WIDTH_TYPE_START, MIN_COLUMN_WIDTH));
        tree.addListener(SWT.Dispose, e -> {
            int width = tree.getColumnCount() > COLUMN_TYPE ? tree.getColumn(COLUMN_TYPE).getWidth() : 0;
            if (width >= MIN_COLUMN_WIDTH)
                dialogSettings().put(KEY_TYPE_COLUMN_WIDTH, width);
        });
        ColumnAutoFit.install(tree, null, index -> index == COLUMN_TYPE);
        // Штатное дерево создано без SWT.FULL_SELECTION: клик в колонке «Тип» строку не выбирает.
        FormTreeInteraction interaction = FormTreeInteraction.install(tree, viewer);
        CopyCommandSupport.wireCopyOverride(tree, () -> copyActiveCellText(tree, interaction));
    }

    /** Ctrl+C: текст активной ячейки текущей строки. */
    private static boolean copyActiveCellText(Tree tree, FormTreeInteraction interaction)
    {
        if (tree.isDisposed())
            return false;
        int column = interaction.activeColumn();
        TreeItem[] selection = tree.getSelection();
        if (column < 0 || column >= tree.getColumnCount() || selection.length == 0 || selection[0].isDisposed())
            return false;
        String text = selection[0].getText(column);
        if (text == null || text.isEmpty())
            return false;
        Clipboard clipboard = new Clipboard(tree.getDisplay());
        try
        {
            clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
        }
        finally
        {
            clipboard.dispose();
        }
        return true;
    }

    /** Размер окна «Выбор реквизита» — восстановление при открытии и запоминание при закрытии. */
    private static void installShellSizeMemory(Shell shell)
    {
        int width = savedInt(KEY_DIALOG_WIDTH);
        int height = savedInt(KEY_DIALOG_HEIGHT);
        if (width > 0 && height > 0)
        {
            Rectangle screen = shell.getMonitor().getClientArea();
            Point minimum = shell.getMinimumSize();
            shell.setSize(Math.max(minimum.x, Math.min(width, screen.width)),
                Math.max(minimum.y, Math.min(height, screen.height)));
        }

        // Размер запоминается по событию Resize: в DisposeListener окно ОС уже уничтожено
        // и getSize() отдаёт неверные значения.
        Point[] lastSize = { null };
        shell.addListener(SWT.Resize, e -> {
            if (shell.getMaximized() || shell.getMinimized())
                return;
            Point size = shell.getSize();
            if (size.x > 0 && size.y > 0)
                lastSize[0] = size;
        });
        shell.addListener(SWT.Dispose, e -> {
            if (lastSize[0] == null)
                return;
            IDialogSettings settings = dialogSettings();
            settings.put(KEY_DIALOG_WIDTH, lastSize[0].x);
            settings.put(KEY_DIALOG_HEIGHT, lastSize[0].y);
        });
    }

    /** Сохранённое целое или 0, если значения нет. */
    private static int savedInt(String key)
    {
        IDialogSettings settings = dialogSettings();
        if (settings.get(key) == null)
            return 0;
        try
        {
            return settings.getInt(key);
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    private static IDialogSettings dialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }

    /**
     * Текст типа — как в редакторе формы, от штатного {@link TypeColumnLabelProvider}. Проект ему
     * отдаём тот же, что у штатного поставщика подписей узлов ({@code DataPathProvider.lprovider}).
     * Шрифт и значок штатного не берём: жирный шрифт ему нужен только в редакторе формы.
     */
    private static ColumnLabelProvider typeTextProvider(LockedContentProvider content)
    {
        Object project = Global.getField(Global.getField(content.provider(), "lprovider"), "v8project"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!(project instanceof IV8Project v8project))
            return null;
        TypeColumnLabelProvider stock = new TypeColumnLabelProvider(null, v8project);
        return new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                PropertyInfo info = content.propertyInfo(element);
                return info != null ? stock.getText(info) : ""; //$NON-NLS-1$
            }
        };
    }

    private static String getFilterPattern(Control filterControl)
    {
        if (filterControl == null || filterControl.isDisposed())
            return ""; //$NON-NLS-1$
        if (filterControl instanceof Text t)
            return t.getText();
        if (filterControl instanceof StyledText st)
            return st.getText();
        return ""; //$NON-NLS-1$
    }

    private static void addFilterModifyListener(Control filterControl, ModifyListener listener)
    {
        if (filterControl instanceof Text t)
            t.addModifyListener(listener);
        else if (filterControl instanceof StyledText st)
            st.addModifyListener(listener);
    }

    private static Object resolveDialog(Shell shell)
    {
        Object data = shell.getData();
        if (data != null && DIALOG_CLASS.equals(data.getClass().getName()))
            return data;
        Object window = shell.getData(WINDOW_KEY);
        return window != null && DIALOG_CLASS.equals(window.getClass().getName()) ? window : null;
    }

    private static String textOf(IStyledLabelProvider labels, Object element)
    {
        StyledString styled = labels.getStyledText(element);
        String text = styled != null ? styled.getString() : null;
        return text != null ? text : ""; //$NON-NLS-1$
    }

    /** Состояние фильтра одного открытого диалога. */
    private static final class Session
    {
        final Object dialog;
        final Shell shell;
        final TreeViewer viewer;
        final LockedContentProvider content;
        final IStyledLabelProvider labels;
        /** Результат, по которому дерево отфильтровано сейчас; меняется только в UI-потоке. */
        volatile FilterResult applied = FilterResult.EMPTY;
        private Job job;
        /** Текущая строка, скрытая фильтром; только UI-поток. */
        private Object hiddenCurrent;
        /** Текст фильтра (нормализованный), по которому идёт или завершён последний расчёт; только UI-поток. */
        private String requestedPattern = ""; //$NON-NLS-1$
        private volatile boolean disposed;

        Session(Object dialog, Shell shell, TreeViewer viewer, LockedContentProvider content,
            IStyledLabelProvider labels)
        {
            this.dialog = dialog;
            this.shell = shell;
            this.viewer = viewer;
            this.content = content;
            this.labels = labels;
        }

        /** UI-поток. Дерево остаётся с прежним результатом, пока фон не посчитает новый. */
        void onFilterChanged(String pattern)
        {
            SmartMatcher matcher = new SmartMatcher(pattern);
            // Modify без смены текста фильтра: пересчёт по тому же тексту не нужен, а повторное
            // применение пустого фильтра свернуло бы дерево, которое пользователь раскрыл руками.
            if (matcher.fullPattern.equals(requestedPattern))
                return;
            requestedPattern = matcher.fullPattern;
            if (job != null)
                job.cancel();
            job = null;
            if (matcher.isEmpty)
            {
                apply(FilterResult.EMPTY);
                return;
            }
            Object input = viewer.getInput();
            Job newJob = new Job("Фильтр пути к данным") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    return compute(this, matcher, input, monitor);
                }
            };
            newJob.setSystem(true);
            job = newJob;
            if (!shell.isDisposed())
                shell.setCursor(shell.getDisplay().getSystemCursor(SWT.CURSOR_APPSTARTING));
            newJob.schedule(FILTER_DELAY_MS);
        }

        private IStatus compute(Job self, SmartMatcher matcher, Object input, IProgressMonitor monitor)
        {
            FilterResult result = new FilterResult(matcher);
            try
            {
                List<Object> unwalkedVisible = new ArrayList<>();
                for (Object root : content.getElements(input))
                    walk(root, 1, result, unwalkedVisible, monitor);
                // У видимых узлов последнего уровня дерево спросит hasChildren (значок раскрытия) —
                // это расчёт их прямых потомков. Делаем его здесь, чтобы первый (долгий) расчёт
                // не достался UI. Узлов ссылочного/определяемого типа среди них нет: их потомков
                // не считаем вовсе, см. LockedContentProvider.hasChildren.
                for (Object element : unwalkedVisible)
                {
                    checkCanceled(monitor);
                    content.getChildren(element);
                }
            }
            catch (OperationCanceledException e)
            {
                return Status.CANCEL_STATUS;
            }
            catch (RuntimeException e)
            {
                Global.logError("DataPathDialogHook", "Расчёт фильтра пути к данным", e); //$NON-NLS-1$ //$NON-NLS-2$
                return Status.OK_STATUS;
            }
            if (disposed || shell.isDisposed())
                return Status.OK_STATUS;
            shell.getDisplay().asyncExec(() -> {
                if (job == self)
                    apply(result);
            });
            return Status.OK_STATUS;
        }

        /**
         * Узел виден, если подходит сам или подходит хотя бы один потомок в пределах
         * {@link #MAX_DEPTH}. В узлы ссылочного и определяемого типа обход не спускается.
         */
        private boolean walk(Object element, int level, FilterResult result, List<Object> unwalkedVisible,
            IProgressMonitor monitor)
        {
            checkCanceled(monitor);
            result.known.add(element);
            boolean hit = result.matcher.matches(textOf(labels, element));
            boolean forbidden = content.isDescentForbidden(element);
            boolean descend = level < MAX_DEPTH && !forbidden;
            if (descend)
            {
                boolean childHit = false;
                for (Object child : content.getChildren(element))
                {
                    if (walk(child, level + 1, result, unwalkedVisible, monitor))
                        childHit = true;
                }
                if (childHit)
                {
                    hit = true;
                    result.expanded.add(element);
                }
            }
            if (hit)
            {
                result.visible.add(element);
                if (!descend && !forbidden)
                    unwalkedVisible.add(element);
            }
            return hit;
        }

        private void checkCanceled(IProgressMonitor monitor)
        {
            if (disposed || monitor.isCanceled())
                throw new OperationCanceledException();
        }

        /** UI-поток. Порядок действий с деревом — как у штатного слушателя ввода. */
        private void apply(FilterResult result)
        {
            if (disposed || shell.isDisposed())
                return;
            shell.setCursor(null);
            Tree tree = viewer.getTree();
            if (tree.isDisposed())
                return;
            // Текущая строка переживает смену и очистку фильтра: если фильтр её скрыл, помним её
            // и возвращаем, как только она снова видна.
            Object current = viewer.getStructuredSelection().getFirstElement();
            if (current == null)
                current = hiddenCurrent;
            applied = result;
            tree.setRedraw(false);
            try
            {
                if (result.matcher.isEmpty)
                {
                    viewer.collapseAll();
                    viewer.refresh();
                    viewer.expandToLevel(1);
                }
                else
                {
                    // Не expandToLevel: он раскрыл бы и узлы, в которые обход не спускался
                    // (ссылочный/определяемый тип), — их потомки посчитались бы в UI.
                    viewer.collapseAll();
                    viewer.refresh();
                    viewer.setExpandedElements(result.expanded.toArray());
                }
                // collapseAll уводит выделение со свёрнутой строки — ставим заново, с раскрытием
                // предков и прокруткой.
                if (current != null && (result.matcher.isEmpty || result.visible.contains(current)))
                    viewer.setSelection(new StructuredSelection(current), true);
            }
            finally
            {
                tree.setRedraw(true);
            }
            if (viewer.getStructuredSelection().getFirstElement() == current)
            {
                hiddenCurrent = null;
            }
            else
            {
                hiddenCurrent = current;
                // Текущая строка скрыта фильтром — штатно сбросить выбор диалога и кнопку OK.
                viewer.setSelection(StructuredSelection.EMPTY);
                Global.invokeVoid(dialog, "clearSelection"); //$NON-NLS-1$
            }
        }

        /**
         * UI-поток, закрытие диалога. После него штатный код читает {@code DataPathProvider}
         * (путь выбранного узла) — дожидаемся, пока фон закончит текущий узел.
         */
        void dispose()
        {
            disposed = true;
            if (job != null)
                job.cancel();
            job = null;
            content.lock.lock();
            content.lock.unlock();
        }
    }

    /** Готовый результат расчёта для одного текста фильтра. */
    private static final class FilterResult
    {
        static final FilterResult EMPTY = new FilterResult(new SmartMatcher("")); //$NON-NLS-1$

        final SmartMatcher matcher;
        /** Узлы, пройденные расчётом (до {@link #MAX_DEPTH}). */
        final Set<Object> known = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Object> visible = Collections.newSetFromMap(new IdentityHashMap<>());
        /** Узлы с подходящими потомками — их дерево раскрывает. */
        final List<Object> expanded = new ArrayList<>();

        FilterResult(SmartMatcher matcher)
        {
            this.matcher = matcher;
        }
    }

    /** Только сверка с готовым результатом — без обращения к потомкам узла. */
    private static final class ReadyResultFilter extends ViewerFilter
    {
        private final Session session;

        ReadyResultFilter(Session session)
        {
            this.session = session;
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            FilterResult result = session.applied;
            if (result.matcher.isEmpty)
                return true;
            if (result.known.contains(element))
                return result.visible.contains(element);
            // Глубже MAX_DEPTH (узел раскрыт вручную) — как у штатного фильтра, только по своему тексту.
            return result.matcher.matches(textOf(session.labels, element));
        }
    }

    /** Штатный поставщик содержимого под общей блокировкой фона и UI. */
    private static final class LockedContentProvider implements ITreeContentProvider
    {
        /** Честная — UI не ждёт дольше расчёта одного узла в фоне. */
        final ReentrantLock lock = new ReentrantLock(true);
        private final ITreeContentProvider base;
        /** Тип узла не меняется, пока открыт диалог; доступ под {@link #lock}. */
        private final java.util.Map<Object, Boolean> descentForbidden = new IdentityHashMap<>();
        private static final Object NO_INFO = new Object();
        /** Источник данных узла; доступ под {@link #lock}. */
        private final java.util.Map<Object, Object> sourceInfos = new IdentityHashMap<>();

        LockedContentProvider(ITreeContentProvider base)
        {
            this.base = base;
        }

        @Override
        public Object[] getElements(Object inputElement)
        {
            lock.lock();
            try
            {
                return base.getElements(inputElement);
            }
            finally
            {
                lock.unlock();
            }
        }

        @Override
        public Object[] getChildren(Object parentElement)
        {
            lock.lock();
            try
            {
                return base.getChildren(parentElement);
            }
            finally
            {
                lock.unlock();
            }
        }

        /**
         * Штатный {@code hasChildren} — это полный расчёт потомков узла. Для узла ссылочного или
         * определяемого типа он сверхтяжёлый (реквизиты любого объекта по ссылке), а нужен только
         * для значка раскрытия — поэтому значок показываем без расчёта: реквизиты у ссылки есть
         * всегда. Потомки считаются, только когда пользователь сам раскроет узел.
         */
        @Override
        public boolean hasChildren(Object element)
        {
            if (isDescentForbidden(element))
                return true;
            lock.lock();
            try
            {
                return base.hasChildren(element);
            }
            finally
            {
                lock.unlock();
            }
        }

        /**
         * Узел ссылочного («*Ссылка») или определяемого («ОпределяемыйТип.*») типа: спуск в его
         * потомков дал бы практически бесконечное дерево — та же защита, что при поиске строки
         * дерева реквизитов формы ({@link FormEditorHook#isFormAttributeReferenceNode}).
         */
        boolean isDescentForbidden(Object element)
        {
            Object info = propertyInfo(element);
            lock.lock();
            try
            {
                Boolean known = descentForbidden.get(element);
                if (known != null)
                    return known.booleanValue();
            }
            finally
            {
                lock.unlock();
            }
            boolean forbidden = FormEditorHook.isFormAttributeReferenceNode(info)
                || FormEditorHook.isFormAttributeDefinedTypeNode(info);
            lock.lock();
            try
            {
                descentForbidden.put(element, Boolean.valueOf(forbidden));
            }
            finally
            {
                lock.unlock();
            }
            return forbidden;
        }

        /**
         * Реквизит, стоящий за узлом, — у него есть тип значения. У реквизитов формы это сам
         * источник данных узла. У строк под «Элементы» источник — {@link ElementDataSourceInfo}
         * без типа, а реквизит лежит в его {@code getSource()}: для видов {@code PROPERTY_INFO} и
         * {@code PROPERTY_INFO_TABLE} там {@code PropertyInfo} поля (проверено логом на полях
         * текущих данных динамического списка). Служебные строки («Элементы», сама таблица,
         * «Текущие данные») — {@code null}.
         */
        PropertyInfo propertyInfo(Object element)
        {
            Object info = sourceInfo(element);
            if (info instanceof PropertyInfo property)
                return property;
            if (info instanceof ElementDataSourceInfo elementInfo
                && (elementInfo.getType() == ElementDataSourceInfoType.PROPERTY_INFO
                    || elementInfo.getType() == ElementDataSourceInfoType.PROPERTY_INFO_TABLE)
                && elementInfo.getSource() instanceof PropertyInfo property)
                return property;
            return null;
        }

        /** Штатный {@code DataPathProvider} — поле {@code provider} поставщика содержимого диалога. */
        Object provider()
        {
            return Global.getField(base, "provider"); //$NON-NLS-1$
        }

        /**
         * Источник данных узла ({@code PropertyInfo} у реквизитов) или {@code null} у служебных
         * строк. Берётся у {@code DataPathProvider} тем же путём, каким тот сам считает потомков:
         * {@code getPathByItem} + {@code getDataSourceInfo}.
         */
        Object sourceInfo(Object element)
        {
            lock.lock();
            try
            {
                Object known = sourceInfos.get(element);
                if (known != null)
                    return known == NO_INFO ? null : known;
                Object provider = provider();
                Object path = Global.invoke(provider, "getPathByItem", element); //$NON-NLS-1$
                Object info = Global.invoke(provider, "getDataSourceInfo", path, element); //$NON-NLS-1$
                sourceInfos.put(element, info != null ? info : NO_INFO);
                return info;
            }
            catch (RuntimeException e)
            {
                Global.logError("DataPathDialogHook", "Источник данных узла пути к данным", e); //$NON-NLS-1$ //$NON-NLS-2$
                sourceInfos.put(element, NO_INFO);
                return null;
            }
            finally
            {
                lock.unlock();
            }
        }

        @Override
        public Object getParent(Object element)
        {
            return base.getParent(element);
        }

        @Override
        public void inputChanged(Viewer viewer, Object oldInput, Object newInput)
        {
            base.inputChanged(viewer, oldInput, newInput);
        }

        @Override
        public void dispose()
        {
            base.dispose();
        }
    }

    /** Подсветка вхождений фильтра; только в узлах, которые сами подходят под весь запрос. */
    private static final class HighlightStyledLabelProvider extends LabelProvider implements IStyledLabelProvider
    {
        private final IStyledLabelProvider base;
        private final Session session;

        HighlightStyledLabelProvider(IStyledLabelProvider base, Session session)
        {
            this.base = base;
            this.session = session;
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            StyledString styled = new StyledString(textOf(base, element));
            SmartMatcher matcher = session.applied.matcher;
            String text = styled.getString();
            if (!matcher.isEmpty && matcher.matches(text))
                SmartMatchHighlight.applyRanges(styled, matcher.getHighlightRanges(text), session.viewer.getTree());
            return styled;
        }

        @Override
        public Image getImage(Object element)
        {
            return base.getImage(element);
        }

        @Override
        public void dispose()
        {
            base.dispose();
            super.dispose();
        }
    }
}
