package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobFunction;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.ICheckStateProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.TextEditBasedChange;
import org.eclipse.ltk.core.refactoring.TextEditBasedChangeGroup;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ViewForm;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.TextEdit;

import com._1c.g5.v8.dt.core.platform.IResourceLookup;

/**
 * Табличный режим страницы «Вносимые изменения» мастера рефакторинга — окна «Рефакторинг»,
 * «Переименовать элемент» (issue 451).
 *
 * <p>Штатно изменения показаны деревом: чтобы понять, что за вхождение в строке, приходится
 * раскрывать узлы и щёлкать по каждому. Табличный режим показывает те же изменения плоским списком
 * с колонками «Файл», «Тип файла», «Модуль», «Метод», «Родитель», «Тип родителя» и «Синтаксический
 * тип», сортировкой, отбором по значению ячейки и множественным выделением — пометки ставятся
 * пачками.
 *
 * <p>Устройство — как у табличного режима страницы «Проверки» окна «Параметры»
 * ({@code ValidationChecksFilterHook.ChecksTablePane}): дерево и таблица лежат в одном
 * {@link Composite} со стеком {@link TopControlStack} на месте дерева внутри штатной
 * {@code ViewerPane}, режим переключает кнопка в её тулбаре. Состав строк — листья того же дерева
 * (тот же поставщик содержимого и те же отборы), поэтому режимы всегда показывают одно и то же.
 *
 * <p>Пометки — состояние узлов дерева. Таблица не ставит их сама, а посылает дереву тот же
 * {@link CheckStateChangedEvent}, что и клик по флажку в дереве: работу делает штатный слушатель
 * LTK (включение изменения, поддерево, серые родители) и слушатель EDT.
 *
 * <p>Колонки «Метод», «Родитель», «Тип родителя» и «Синтаксический тип» считает
 * {@link BslOccurrenceContextResolver} в фоне: тип родителя требует разбора модуля моделью BSL, на
 * сотнях вхождений это заметно. До расчёта в ячейке типа стоит «?», а в заголовке колонки виден
 * счётчик обработанных вхождений.
 *
 * <p>Отдельно от {@link RefactoringPreviewCurrentLinesHook} (панель «Текущая строка» в панели
 * сравнения того же окна) и {@link RefactoringPreviewMarksHook} (начальная расстановка пометок):
 * у тех своя привязка и свой жизненный цикл.
 */
public final class RefactoringPreviewTableHook
{
    private static final String TEMP_LOG_TOPIC = "refactoring-preview-table"; //$NON-NLS-1$

    /** Диалог мастера рефакторинга — проверка та же, что в двух соседних хуках этого окна. */
    private static final String DIALOG_NAME_PART_REFACTORING = "Refactoring"; //$NON-NLS-1$
    private static final String DIALOG_NAME_PART_DIALOG = "Dialog"; //$NON-NLS-1$
    /** Страница предпросмотра LTK — штатная {@code PreviewWizardPage} или её потомок EDT. */
    private static final String PREVIEW_PAGE_NAME_PART = "PreviewWizardPage"; //$NON-NLS-1$

    private static final String PAGE_HANDLED_KEY = "tormozit.refactoringPreviewTablePage"; //$NON-NLS-1$

    /** Страница предпросмотра может быть не первой страницей мастера — ждём её с повторами. */
    private static final int RETRY_DELAY_MS = 300;
    private static final int MAX_ATTEMPTS = 40;

    private RefactoringPreviewTableHook()
    {
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, RefactoringPreviewTableHook::handleShow);
    }

    /**
     * Показ любого контрола внутри окна мастера: страница предпросмотра становится видимой именно
     * так ({@code WizardDialog.showPage} → {@code setVisible(true)}), отдельного события у неё нет.
     */
    private static void handleShow(Event event)
    {
        if (!(event.widget instanceof Control control) || control.isDisposed())
            return;
        Shell shell = control.getShell();
        if (shell == null || shell.isDisposed() || !isRefactoringWizardDialog(shell))
            return;
        Display display = shell.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> scheduleAttach(shell, 0));
    }

    private static boolean isRefactoringWizardDialog(Shell shell)
    {
        Object data = shell.getData();
        if (data == null)
            return false;
        String name = data.getClass().getName();
        return name.contains(DIALOG_NAME_PART_REFACTORING) && name.contains(DIALOG_NAME_PART_DIALOG);
    }

    private static void scheduleAttach(Shell shell, int attempt)
    {
        if (shell.isDisposed() || attempt >= MAX_ATTEMPTS)
            return;
        if (tryAttach(shell))
            return;
        Display display = shell.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(RETRY_DELAY_MS, () -> scheduleAttach(shell, attempt + 1));
    }

    /**
     * @return {@code false}, если страницу предпросмотра ещё стоит подождать; {@code true} — режим
     *     подключён, подключать нечего или вёрстка страницы не та
     */
    private static boolean tryAttach(Shell shell)
    {
        if (shell == null || shell.isDisposed() || !(shell.getData() instanceof IWizardContainer container))
            return true;
        IWizardPage page = container.getCurrentPage();
        // Не страница предпросмотра — её показ придёт своим событием
        if (page == null || !page.getClass().getName().contains(PREVIEW_PAGE_NAME_PART))
            return true;
        if (Boolean.TRUE.equals(pageFlag(page)))
            return true;
        if (!(Global.getField(page, "fTreeViewer") instanceof CheckboxTreeViewer treeViewer)) //$NON-NLS-1$
            return false;
        Tree tree = treeViewer.getTree();
        if (tree == null || tree.isDisposed())
            return false;
        markPageHandled(page);
        try
        {
            PreviewTablePane.install(treeViewer);
        }
        catch (RuntimeException e)
        {
            Global.tempLogException(TEMP_LOG_TOPIC, "install", e); //$NON-NLS-1$
        }
        return true;
    }

    private static Object pageFlag(IWizardPage page)
    {
        Control control = page.getControl();
        return control != null && !control.isDisposed() ? control.getData(PAGE_HANDLED_KEY) : Boolean.TRUE;
    }

    private static void markPageHandled(IWizardPage page)
    {
        Control control = page.getControl();
        if (control != null && !control.isDisposed())
            control.setData(PAGE_HANDLED_KEY, Boolean.TRUE);
    }

    /**
     * То же, что {@code StackLayout}, но предпочтительный размер берётся только у текущего верхнего
     * контрола: у таблицы с {@link TableColumnLayout} он равен сумме ширин колонок и иначе задавал
     * бы ширину всей страницы мастера.
     */
    private static final class TopControlStack extends Layout
    {
        Control topControl;

        @Override
        protected Point computeSize(Composite composite, int wHint, int hHint, boolean flushCache)
        {
            Point size = topControl == null || topControl.isDisposed() ? new Point(0, 0)
                : topControl.computeSize(wHint, hHint, flushCache);
            if (wHint != SWT.DEFAULT)
                size.x = wHint;
            if (hHint != SWT.DEFAULT)
                size.y = hHint;
            return size;
        }

        @Override
        protected void layout(Composite composite, boolean flushCache)
        {
            Rectangle client = composite.getClientArea();
            for (Control child : composite.getChildren())
            {
                child.setVisible(child == topControl);
                if (child == topControl)
                    child.setBounds(client);
            }
        }
    }

    /** Строка таблицы: лист дерева изменений и всё, что о нём удалось вычислить. */
    private static final class PreviewRow
    {
        /** Узел дерева ({@code PreviewNode} из неэкспортированного пакета LTK — только рефлексия). */
        final Object node;
        final String text;
        final IFile file;
        final int offset;
        final int length;

        String fileName = ""; //$NON-NLS-1$
        String fileType = ""; //$NON-NLS-1$
        String module = ""; //$NON-NLS-1$
        String method = ""; //$NON-NLS-1$
        String parent = ""; //$NON-NLS-1$
        String syntaxKind = ""; //$NON-NLS-1$
        /** {@code null} — тип ещё не вычисляли (в ячейке «?»). */
        String parentType;

        PreviewRow(Object node, String text, IFile file, int offset, int length)
        {
            this.node = node;
            this.text = text;
            this.file = file;
            this.offset = offset;
            this.length = length;
        }

        /** Колонки «Метод», «Родитель», «Тип родителя», «Синтаксический тип» есть только у модулей. */
        boolean needsContext()
        {
            return offset >= 0 && length > 0 && BslModuleMethodResolver.isBslModule(file);
        }
    }

    /** Таблица вместо дерева изменений и всё её поведение. */
    private static final class PreviewTablePane
    {
        private static final String SETTINGS_SECTION = "tormozit.refactoringPreviewTable"; //$NON-NLS-1$
        private static final String TOGGLE_ID = SETTINGS_SECTION + ".toggle"; //$NON-NLS-1$
        private static final String KEY_COL_ORDER = "columnOrder"; //$NON-NLS-1$
        /** Ширины на момент закрытия были чистым авто-заполнением, а не ручной подгонкой. */
        private static final String KEY_COLUMNS_FILL = "columnsFill"; //$NON-NLS-1$
        private static final String[] WIDTH_KEYS = {"changeWidth", "fileWidth", "fileTypeWidth", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "moduleWidth", "methodWidth", "parentWidth", "parentTypeWidth", "syntaxWidth"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        private static final int[] DEFAULT_WIDTHS = {320, 200, 90, 260, 180, 180, 220, 140};
        private static final int MIN_COLUMN_WIDTH = 40;

        private static final String UNKNOWN_TYPE = "?"; //$NON-NLS-1$
        private static final String PARENT_TYPE_TITLE = "Тип родителя"; //$NON-NLS-1$

        /** Значения {@code PreviewNode.getActive()}. */
        private static final int INACTIVE = 0;
        private static final int PARTLY_ACTIVE = 1;

        private static ImageData tableModeIconData;

        private final CheckboxTreeViewer treeViewer;
        private final Composite stack;
        private final TopControlStack stackLayout;
        private final Composite tableHost;
        private final Table table;
        private final CheckboxTableViewer viewer;
        private final List<PreviewRow> rows = new ArrayList<>();

        private FormTableInteraction interaction;
        private TableColumn parentTypeColumn;
        private IAction toggleAction;
        private ToolBarManager toolBarManager;
        private boolean tableMode;
        private boolean syncing;
        private Job contextJob;
        /** Читается фоновым потоком — устаревшая пачка результатов в таблицу не попадает. */
        private volatile long contextGeneration;

        private PreviewTablePane(CheckboxTreeViewer treeViewer, Composite stack, TopControlStack stackLayout,
            Composite tableHost, Table table, CheckboxTableViewer viewer)
        {
            this.treeViewer = treeViewer;
            this.stack = stack;
            this.stackLayout = stackLayout;
            this.tableHost = tableHost;
            this.table = table;
            this.viewer = viewer;
        }

        /**
         * @return {@code null}, если вёрстка страницы не та, которую мы знаем — тогда страница
         *     остаётся штатной, только с деревом
         */
        static PreviewTablePane install(CheckboxTreeViewer treeViewer)
        {
            Tree tree = treeViewer.getTree();
            if (!(tree.getParent() instanceof ViewForm pane))
            {
                Global.tempLog(TEMP_LOG_TOPIC, "tree parent is not ViewForm: " //$NON-NLS-1$
                    + tree.getParent().getClass().getName());
                return null;
            }

            Composite stack = new Composite(pane, SWT.NONE);
            TopControlStack stackLayout = new TopControlStack();
            stack.setLayout(stackLayout);
            if (!tree.setParent(stack))
            {
                stack.dispose();
                Global.tempLog(TEMP_LOG_TOPIC, "setParent(tree) failed"); //$NON-NLS-1$
                return null;
            }

            // tableHost с layout(null) + columnHost с TableColumnLayout — раскладка, которую
            // ожидает FormTableInteraction для overlay заголовка колонок.
            Composite tableHost = new Composite(stack, SWT.NONE);
            tableHost.setLayout(null);
            Composite columnHost = new Composite(tableHost, SWT.NONE);
            TableColumnLayout columnLayout = new TableColumnLayout();
            columnHost.setLayout(columnLayout);

            // CheckboxTableViewer, а не TableViewer: пометки строк восстанавливает сам JFace по
            // ICheckStateProvider, иначе сортировка и отбор их теряют.
            CheckboxTableViewer viewer = new CheckboxTableViewer(
                new Table(columnHost, SWT.CHECK | SWT.FULL_SELECTION | SWT.MULTI));
            Table table = viewer.getTable();
            table.setHeaderVisible(true);
            ThemeAwareColors.applyGridLines(table);

            PreviewTablePane result =
                new PreviewTablePane(treeViewer, stack, stackLayout, tableHost, table, viewer);
            result.createColumns(columnLayout);

            stackLayout.topControl = tree;
            pane.setContent(stack);
            stack.layout();
            pane.layout(true, true);

            result.wireListeners();
            result.installToggle(pane);
            result.setTableMode(ComfortSettings.isRefactoringPreviewTableMode());
            Global.tempLog(TEMP_LOG_TOPIC, "installed"); //$NON-NLS-1$
            return result;
        }

        private void createColumns(TableColumnLayout columnLayout)
        {
            IDialogSettings settings = dialogSettings();
            ILabelProvider treeLabels = treeLabelProvider();

            TableViewerColumn changeColumn = new TableViewerColumn(viewer, SWT.NONE);
            changeColumn.getColumn().setText("Изменение"); //$NON-NLS-1$
            changeColumn.setLabelProvider(new ColumnLabelProvider()
            {
                @Override
                public String getText(Object element)
                {
                    return element instanceof PreviewRow row ? row.text : ""; //$NON-NLS-1$
                }

                @Override
                public Image getImage(Object element)
                {
                    if (treeLabels == null || !(element instanceof PreviewRow row))
                        return null;
                    return treeLabels.getImage(row.node);
                }
            });
            applyWidth(columnLayout, changeColumn.getColumn(), settings, 0);

            addTextColumn(columnLayout, settings, 1, "Файл", row -> row.fileName); //$NON-NLS-1$
            addTextColumn(columnLayout, settings, 2, "Тип файла", row -> row.fileType); //$NON-NLS-1$
            addTextColumn(columnLayout, settings, 3, "Модуль", row -> row.module); //$NON-NLS-1$
            addTextColumn(columnLayout, settings, 4, "Метод", row -> row.method); //$NON-NLS-1$
            addTextColumn(columnLayout, settings, 5, "Родитель", row -> row.parent); //$NON-NLS-1$
            parentTypeColumn = addTextColumn(columnLayout, settings, 6, PARENT_TYPE_TITLE,
                row -> row.parentType != null ? row.parentType : UNKNOWN_TYPE);
            addTextColumn(columnLayout, settings, 7, "Синтаксический тип", row -> row.syntaxKind); //$NON-NLS-1$

            viewer.setContentProvider(ArrayContentProvider.getInstance());
            viewer.setCheckStateProvider(new ICheckStateProvider()
            {
                @Override
                public boolean isChecked(Object element)
                {
                    return element instanceof PreviewRow row && activeState(row.node) != INACTIVE;
                }

                @Override
                public boolean isGrayed(Object element)
                {
                    return element instanceof PreviewRow row && activeState(row.node) == PARTLY_ACTIVE;
                }
            });
            viewer.setInput(rows);

            interaction = new FormTableInteraction(table, viewer,
                (item, col) -> cellText(item != null ? item.getData() : null, col));
            interaction.setFilterTextResolver(PreviewTablePane::cellText);
            interaction.setColumnReorderEnabled(true);
            FormTableColumnState.loadOrder(settings, KEY_COL_ORDER, table);
            // true — пользователь уже подстраивал ширины сам, режим заполнения по ширине не навязываем.
            // Ширины, оставшиеся от авто-заполнения, за «подстроенные» не считаются (KEY_COLUMNS_FILL).
            interaction.install(
                FormTableColumnState.hasSavedColumnWidths(settings, KEY_COLUMNS_FILL, WIDTH_KEYS));
            interaction.enableHeaderSort();
            installMarkMenu();
        }

        private TableColumn addTextColumn(TableColumnLayout columnLayout, IDialogSettings settings, int index,
            String title, java.util.function.Function<PreviewRow, String> text)
        {
            TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
            column.getColumn().setText(title);
            column.setLabelProvider(new ColumnLabelProvider()
            {
                @Override
                public String getText(Object element)
                {
                    if (!(element instanceof PreviewRow row))
                        return ""; //$NON-NLS-1$
                    String value = text.apply(row);
                    return value != null ? value : ""; //$NON-NLS-1$
                }
            });
            applyWidth(columnLayout, column.getColumn(), settings, index);
            return column.getColumn();
        }

        private static void applyWidth(TableColumnLayout columnLayout, TableColumn column,
            IDialogSettings settings, int index)
        {
            int width = FormTableColumnState.readWidth(settings, WIDTH_KEYS[index], DEFAULT_WIDTHS[index],
                MIN_COLUMN_WIDTH);
            columnLayout.setColumnData(column, new ColumnPixelData(width, true, true));
        }

        private ILabelProvider treeLabelProvider()
        {
            return treeViewer.getLabelProvider() instanceof ILabelProvider labels ? labels : null;
        }

        private void wireListeners()
        {
            table.addListener(SWT.Selection, event ->
            {
                if (event.detail == SWT.CHECK)
                    toggleCheck(event.item);
                else
                    syncSelectionToTree();
            });
            // Пробел переключает пометку у всех выделенных строк. Штатную обработку гасим:
            // иначе система переключит ещё и флажок строки с фокусом, отменив нашу.
            table.addListener(SWT.KeyDown, event ->
            {
                if (event.character == ' ' && event.stateMask == 0)
                {
                    event.doit = false;
                    setChecked(selectedRows(), !allChecked(selectedRows()));
                }
            });
            treeViewer.addSelectionChangedListener(event -> syncSelectionFromTree());
            treeViewer.getTree().addListener(SWT.Selection, event -> refreshRows());
            table.addDisposeListener(event ->
            {
                cancelContextResolution();
                BslOccurrenceContextResolver.clearCaches();
                saveColumnLayout();
            });
        }

        /** Пункты пометки в контекстном меню таблицы (меню создаёт {@link FormTableInteraction}). */
        private void installMarkMenu()
        {
            Menu menu = table.getMenu();
            if (menu == null || menu.isDisposed())
                return;
            addMenuItem(menu, "Пометить\tПробел", () -> setChecked(selectedRows(), true)); //$NON-NLS-1$
            addMenuItem(menu, "Снять пометку", () -> setChecked(selectedRows(), false)); //$NON-NLS-1$
            addMenuItem(menu, "Инвертировать пометки", this::invertChecked); //$NON-NLS-1$
        }

        private static void addMenuItem(Menu menu, String text, Runnable action)
        {
            MenuItem item = new MenuItem(menu, SWT.PUSH);
            item.setText(text);
            item.addListener(SWT.Selection, event -> action.run());
        }

        /**
         * Кнопка табличного режима — в тулбаре штатной панели над деревом изменений. Тулбар
         * принадлежит {@code ToolBarManager} панели, а его {@code update(true)} чужие «сырые»
         * {@code ToolItem} стирает (вызываем и мы сами в {@link #overrideMarkAllActions}, и EDT) —
         * поэтому кнопка оформлена действием в менеджере, как у переключателей панелей сравнения.
         */
        private void installToggle(ViewForm pane)
        {
            if (!(Global.invoke(pane, "getToolBarManager") instanceof ToolBarManager manager)) //$NON-NLS-1$
            {
                Global.tempLog(TEMP_LOG_TOPIC, "toggle: toolbar manager not found, topCenter=" //$NON-NLS-1$
                    + (pane.getTopCenter() == null ? "null" : pane.getTopCenter().getClass().getName()));
                return;
            }
            toolBarManager = manager;
            Action toggle = new Action("", IAction.AS_CHECK_BOX) //$NON-NLS-1$
            {
                @Override
                public void run()
                {
                    applyMode(isChecked());
                    ComfortSettings.setRefactoringPreviewTableMode(isChecked());
                }
            };
            toggle.setId(TOGGLE_ID);
            toggle.setImageDescriptor(tableModeIconDescriptor());
            toggle.setToolTipText(TooltipText.wrap(pane,
                "Табличный режим: изменения плоским списком с колонками «Файл», «Модуль», «Метод», " //$NON-NLS-1$
                    + "«Родитель», «Тип родителя» и «Синтаксический тип»" + Global.pluginSignForTooltip())); //$NON-NLS-1$
            toggle.setChecked(ComfortSettings.isRefactoringPreviewTableMode());
            manager.add(toggle);
            toggleAction = toggle;
            int items = manager.getItems().length;
            overrideMarkAllActions(manager);
            Global.tempLog(TEMP_LOG_TOPIC, "toggle: action added, manager items=" + items //$NON-NLS-1$
                + "->" + manager.getItems().length); //$NON-NLS-1$
        }

        /**
         * Кнопки EDT «пометить/снять пометку со всех полнотекстовых вхождений»: в табличном режиме
         * они работают по выделению таблицы. Подменяется вклад в {@code ToolBarManager} панели —
         * своё действие вызывает штатное, когда табличный режим выключен или выделения нет.
         */
        private void overrideMarkAllActions(ToolBarManager manager)
        {
            for (IContributionItem contribution : manager.getItems())
            {
                if (!(contribution instanceof ActionContributionItem actionItem))
                    continue;
                IAction action = actionItem.getAction();
                if (action == null || !isMarkAllAction(action))
                    continue;
                boolean check = action.getClass().getSimpleName().startsWith("Check"); //$NON-NLS-1$
                String id = action.getId() != null ? action.getId() : contribution.getId();
                ActionContributionItem replacement = new ActionContributionItem(new MarkAllDelegate(action, check));
                try
                {
                    manager.insertBefore(id, replacement);
                    manager.remove(contribution);
                }
                catch (RuntimeException e)
                {
                    // Вклад без идентификатора — оставляем штатную кнопку как есть: пометить
                    // выделенные строки можно пробелом и контекстным меню таблицы.
                    Global.tempLogException(TEMP_LOG_TOPIC, "overrideMarkAllActions", e); //$NON-NLS-1$
                }
            }
            manager.update(true);
        }

        private static boolean isMarkAllAction(IAction action)
        {
            return action.getClass().getSimpleName().contains("FullTextSearchChangesAction"); //$NON-NLS-1$
        }

        /** Штатное действие «пометить/снять все», перенаправленное на выделение таблицы. */
        private final class MarkAllDelegate extends Action
        {
            private final IAction original;
            private final boolean check;

            MarkAllDelegate(IAction original, boolean check)
            {
                super(original.getText(), original.getStyle());
                this.original = original;
                this.check = check;
                setId(original.getId());
                setImageDescriptor(original.getImageDescriptor());
                setToolTipText(original.getToolTipText());
            }

            @Override
            public void run()
            {
                List<PreviewRow> selected = selectedRows();
                if (!tableMode || selected.isEmpty())
                {
                    original.run();
                    refreshRows();
                    return;
                }
                PreviewTablePane.this.setChecked(selected, check);
            }
        }

        void setTableMode(boolean value)
        {
            applyMode(value);
            if (toggleAction != null)
                toggleAction.setChecked(value);
            // Виджет кнопки не следит за действием сам — обновляем вклад после смены пометки.
            if (toolBarManager != null && toolBarManager.find(TOGGLE_ID) instanceof ActionContributionItem item)
                item.update();
        }

        private void applyMode(boolean value)
        {
            if (stack.isDisposed())
                return;
            tableMode = value;
            if (value)
                reload();
            else
                cancelContextResolution();
            stackLayout.topControl = value ? tableHost : treeViewer.getTree();
            stack.layout();
        }

        /** Пересобирает строки по тем же правилам, по которым показывает изменения дерево. */
        private void reload()
        {
            if (table.isDisposed() || !(treeViewer.getContentProvider() instanceof ITreeContentProvider provider))
                return;
            Object input = treeViewer.getInput();
            if (input == null)
                return;
            rows.clear();
            for (Object root : filtered(provider.getElements(input), input))
                collect(provider, root, 0);
            viewer.refresh();
            syncSelectionFromTree();
            scheduleContextResolution();
            Global.tempLog(TEMP_LOG_TOPIC, "reload: rows=" + rows.size()); //$NON-NLS-1$
        }

        private void collect(ITreeContentProvider provider, Object node, int depth)
        {
            if (depth > 32)
                return;
            Object[] children = filtered(provider.getChildren(node), node);
            if (children.length == 0)
            {
                PreviewRow row = buildRow(node);
                if (row != null)
                    rows.add(row);
                return;
            }
            for (Object child : children)
                collect(provider, child, depth + 1);
        }

        private Object[] filtered(Object[] elements, Object parent)
        {
            Object[] result = elements != null ? elements : new Object[0];
            for (ViewerFilter filter : treeViewer.getFilters())
                result = filter.filter(treeViewer, parent, result);
            return result;
        }

        private PreviewRow buildRow(Object node)
        {
            ILabelProvider labels = treeLabelProvider();
            String text = labels != null ? labels.getText(node) : String.valueOf(Global.invoke(node, "getText")); //$NON-NLS-1$
            IRegion region = occurrenceRegion(node);
            IFile file = fileOf(node);
            PreviewRow row = new PreviewRow(node, text != null ? text : "", file, //$NON-NLS-1$
                region != null ? region.getOffset() : -1, region != null ? region.getLength() : 0);
            if (file != null)
            {
                row.fileName = file.getName();
                row.fileType = fileExtension(file);
                row.module = moduleLabel(file);
            }
            if (!row.needsContext())
                row.parentType = ""; //$NON-NLS-1$
            return row;
        }

        private static String fileExtension(IFile file)
        {
            String extension = file.getFileExtension();
            return extension != null ? extension : ""; //$NON-NLS-1$
        }

        /**
         * Место вхождения в тексте изменяемого файла: у листа-группы правок его отдаёт сама группа,
         * у листа-изменения (вхождение полнотекстового поиска — одна правка) берём его правку.
         */
        private static IRegion occurrenceRegion(Object node)
        {
            if (Global.invoke(node, "getChangeGroup") instanceof TextEditBasedChangeGroup group) //$NON-NLS-1$
                return group.getRegion();
            if (!(Global.invoke(node, "getChange") instanceof TextEditBasedChange change)) //$NON-NLS-1$
                return null;
            List<TextEdit> edits = new ArrayList<>();
            collectLeafEdits(Global.invoke(change, "getEdit"), edits); //$NON-NLS-1$
            if (edits.isEmpty())
                return null;
            TextEdit first = edits.get(0);
            return new org.eclipse.jface.text.Region(first.getOffset(), first.getLength());
        }

        private static void collectLeafEdits(Object edit, List<TextEdit> result)
        {
            if (!(edit instanceof TextEdit textEdit))
                return;
            if (textEdit instanceof MultiTextEdit)
            {
                for (TextEdit child : textEdit.getChildren())
                    collectLeafEdits(child, result);
                return;
            }
            result.add(textEdit);
        }

        /** Изменяемый файл — у ближайшего узла-изменения вверх по дереву. */
        private static IFile fileOf(Object node)
        {
            for (Object current = node; current != null; current = Global.invoke(current, "getParent")) //$NON-NLS-1$
            {
                if (Global.invoke(current, "getChange") instanceof Change change) //$NON-NLS-1$
                {
                    IFile file = fileOfChange(change);
                    if (file != null)
                        return file;
                }
            }
            return null;
        }

        /**
         * Файл изменения. У файловых изменений LTK ({@code TextFileChange} и его потомков EDT)
         * {@code getModifiedElement()} — сам {@link IFile}. У изменений модулей EDT
         * (полнотекстовый поиск: {@code FullTextSearchSourceFileChange} и родня) изменяемый
         * элемент — handly-файл ({@code BslFile} и прочие {@code WorkspaceSourceFile}), файл
         * даёт их {@code getFile_()}. Изменения объектов BM ({@code BmObjectTextContentChange})
         * модифицируют {@link EObject} — файл ищем через {@code IResourceLookup}.
         */
        private static IFile fileOfChange(Change change)
        {
            Object modified = change.getModifiedElement();
            if (modified instanceof IFile file)
                return file;
            if (Global.invoke(modified, "getFile_") instanceof IFile handlyFile) //$NON-NLS-1$
                return handlyFile;
            if (modified instanceof EObject model)
            {
                IFile file = modelFile(model);
                if (file != null)
                    return file;
            }
            if (change instanceof TextFileChange textFileChange)
                return textFileChange.getFile();
            Global.tempLog(TEMP_LOG_TOPIC, "fileOfChange: unresolved, change=" //$NON-NLS-1$
                + change.getClass().getName() + ", modified=" //$NON-NLS-1$
                + (modified == null ? "null" : modified.getClass().getName())); //$NON-NLS-1$
            return null;
        }

        private static IFile modelFile(EObject model)
        {
            IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
            if (lookup == null)
                return null;
            for (EObject owner = model; owner != null; owner = owner.eContainer())
            {
                IFile file = lookup.getPlatformResource(owner);
                if (file != null && file.exists())
                    return file;
                if (owner.eResource() != null)
                {
                    file = lookup.getPlatformResource(owner.eResource());
                    if (file != null && file.exists())
                        return file;
                }
            }
            return null;
        }

        /** Модуль в терминах 1С ({@code Справочник.Номенклатура.МодульОбъекта}); не модуль — пусто. */
        private static String moduleLabel(IFile file)
        {
            if (!BslModuleMethodResolver.isBslModule(file))
                return ""; //$NON-NLS-1$
            String module = GetRef.resolveSetTextModuleName(file);
            return module != null ? module : ""; //$NON-NLS-1$
        }

        private static int activeState(Object node)
        {
            return Global.invoke(node, "getActive") instanceof Integer active ? active.intValue() : INACTIVE; //$NON-NLS-1$
        }

        // ---- Пометки ----

        private void toggleCheck(Widget item)
        {
            if (!(item instanceof TableItem tableItem) || !(tableItem.getData() instanceof PreviewRow row))
                return;
            setChecked(List.of(row), tableItem.getChecked());
        }

        private List<PreviewRow> selectedRows()
        {
            List<PreviewRow> selected = new ArrayList<>();
            IStructuredSelection selection = viewer.getStructuredSelection();
            for (Object element : selection.toList())
            {
                if (element instanceof PreviewRow row)
                    selected.add(row);
            }
            return selected;
        }

        private boolean allChecked(Collection<PreviewRow> target)
        {
            for (PreviewRow row : target)
            {
                if (activeState(row.node) == INACTIVE)
                    return false;
            }
            return !target.isEmpty();
        }

        private void invertChecked()
        {
            for (PreviewRow row : selectedRows())
                fireCheck(row.node, activeState(row.node) == INACTIVE);
            afterCheckChanged();
        }

        private void setChecked(Collection<PreviewRow> target, boolean value)
        {
            if (target.isEmpty())
                return;
            for (PreviewRow row : target)
                fireCheck(row.node, value);
            afterCheckChanged();
        }

        /**
         * Дереву посылается тот же {@link CheckStateChangedEvent}, что и при клике по флажку в нём:
         * состояние изменения ставит штатный слушатель LTK, серые пометки родителей — слушатель EDT.
         * Свой {@code setEnabled} в обход них не зовём.
         */
        private void fireCheck(Object node, boolean value)
        {
            treeViewer.setChecked(node, value);
            Global.invoke(treeViewer, "fireCheckStateChanged", //$NON-NLS-1$
                new CheckStateChangedEvent(treeViewer, node, value));
        }

        private void afterCheckChanged()
        {
            // Изменение с запретом на редактирование пометку не принимает — состояние узлов
            // перечитываем, а не считаем свою правку применённой.
            treeViewer.refresh();
            refreshRows();
        }

        private void refreshRows()
        {
            if (!table.isDisposed() && tableMode)
                viewer.refresh();
        }

        // ---- Синхронизация выделения ----

        private void syncSelectionToTree()
        {
            if (syncing || !tableMode)
                return;
            List<PreviewRow> selected = selectedRows();
            if (selected.isEmpty())
                return;
            // В дерево уходит одна строка — за ней следует панель сравнения ниже.
            Object node = selected.get(selected.size() - 1).node;
            syncing = true;
            try
            {
                expandAncestors(node);
                treeViewer.setSelection(new StructuredSelection(node), true);
            }
            finally
            {
                syncing = false;
            }
        }

        private void expandAncestors(Object node)
        {
            List<Object> ancestors = new ArrayList<>();
            for (Object parent = Global.invoke(node, "getParent"); parent != null; //$NON-NLS-1$
                parent = Global.invoke(parent, "getParent")) //$NON-NLS-1$
            {
                ancestors.add(0, parent);
            }
            for (Object ancestor : ancestors)
                treeViewer.expandToLevel(ancestor, 1);
        }

        private void syncSelectionFromTree()
        {
            if (syncing || !tableMode || table.isDisposed())
                return;
            Object selected = treeViewer.getStructuredSelection().getFirstElement();
            PreviewRow target = null;
            for (PreviewRow row : rows)
            {
                if (row.node == selected)
                {
                    target = row;
                    break;
                }
            }
            if (target == null)
                return;
            syncing = true;
            try
            {
                viewer.setSelection(new StructuredSelection(target), true);
                if (interaction != null)
                    interaction.revealSelection();
            }
            finally
            {
                syncing = false;
            }
        }

        // ---- Фоновый расчёт колонок ----

        /**
         * Считает «Метод», «Родитель», «Синтаксический тип» и «Тип родителя». Первые три —
         * лексический разбор текста модуля, последний поднимает модель BSL, поэтому проход только
         * фоновый. Видимые строки идут первыми, устаревшие проходы отсекает поколение.
         */
        private void scheduleContextResolution()
        {
            cancelContextResolution();
            List<PreviewRow> pending = new ArrayList<>();
            for (PreviewRow row : rows)
            {
                if (row.needsContext() && row.parentType == null)
                    pending.add(row);
            }
            if (pending.isEmpty())
            {
                updateProgressTitle(0, 0);
                return;
            }
            long generation = ++contextGeneration;
            List<PreviewRow> ordered = visibleFirst(pending);
            updateProgressTitle(0, ordered.size());

            // Приведение обязательно: Job.create перегружен под ICoreRunnable и IJobFunction
            Job job = Job.create("Комфорт: контекст вхождений рефакторинга", (IJobFunction)(monitor -> //$NON-NLS-1$
                resolveContext(ordered, generation, monitor)));
            job.setSystem(true);
            contextJob = job;
            job.schedule();
        }

        private IStatus resolveContext(List<PreviewRow> ordered, long generation, IProgressMonitor monitor)
        {
            Map<IFile, String> texts = new IdentityHashMap<>();
            List<PreviewRow> batch = new ArrayList<>();
            int done = 0;
            for (PreviewRow row : ordered)
            {
                if (monitor.isCanceled() || generation != contextGeneration)
                    return Status.CANCEL_STATUS;
                // Текст модуля читается один раз на файл; null (файл не прочитан) тоже запоминаем,
                // иначе каждая строка такого модуля снова полезет на диск.
                if (!texts.containsKey(row.file))
                    texts.put(row.file, BslModuleMethodResolver.moduleText(row.file));
                resolveRow(row, texts.get(row.file));
                batch.add(row);
                done++;
                if (batch.size() >= 25 || done == ordered.size())
                {
                    publish(new ArrayList<>(batch), done, ordered.size(), generation);
                    batch.clear();
                }
            }
            return Status.OK_STATUS;
        }

        private void resolveRow(PreviewRow row, String content)
        {
            if (content == null)
            {
                row.parentType = ""; //$NON-NLS-1$
                return;
            }
            row.parent = BslOccurrenceContextResolver.parentText(content, row.offset);
            row.syntaxKind = BslOccurrenceContextResolver.syntaxKind(content, row.offset, row.length);
            String method = BslModuleMethodResolver.methodAtLine(row.file, lineOf(content, row.offset));
            row.method = method != null ? method : ""; //$NON-NLS-1$
            // Комментарии и строковые литералы модели не принадлежат — типа родителя у них нет,
            // и модуль ради них не поднимается.
            if (BslOccurrenceContextResolver.KIND_COMMENT.equals(row.syntaxKind)
                || BslOccurrenceContextResolver.KIND_LITERAL.equals(row.syntaxKind))
            {
                row.parentType = ""; //$NON-NLS-1$
                return;
            }
            row.parentType = BslOccurrenceContextResolver.parentType(row.file, content, row.offset);
        }

        private void publish(List<PreviewRow> batch, int done, int total, long generation)
        {
            Display display = table.getDisplay();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() ->
            {
                if (table.isDisposed() || generation != contextGeneration)
                    return;
                viewer.update(batch.toArray(), null);
                updateProgressTitle(done, total);
            });
        }

        /** Прогресс виден в заголовке колонки: окно мастера модальное, штатный индикатор в нём не показать. */
        private void updateProgressTitle(int done, int total)
        {
            if (parentTypeColumn == null || parentTypeColumn.isDisposed())
                return;
            parentTypeColumn.setText(done >= total ? PARENT_TYPE_TITLE
                : PARENT_TYPE_TITLE + " (" + done + "/" + total + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        private void cancelContextResolution()
        {
            contextGeneration++;
            if (contextJob != null)
            {
                contextJob.cancel();
                contextJob = null;
            }
        }

        /** Видимый диапазон таблицы — первым: пользователь видит заполнение сразу, а не через минуту. */
        private List<PreviewRow> visibleFirst(List<PreviewRow> pending)
        {
            int top = table.getTopIndex();
            int itemHeight = Math.max(table.getItemHeight(), 1);
            int visible = table.getClientArea().height / itemHeight + 2;
            Map<PreviewRow, Boolean> onScreen = new IdentityHashMap<>();
            for (int i = top; i < Math.min(top + visible, table.getItemCount()); i++)
            {
                if (table.getItem(i).getData() instanceof PreviewRow row)
                    onScreen.put(row, Boolean.TRUE);
            }
            List<PreviewRow> ordered = new ArrayList<>(pending.size());
            for (PreviewRow row : pending)
            {
                if (onScreen.containsKey(row))
                    ordered.add(row);
            }
            for (PreviewRow row : pending)
            {
                if (!onScreen.containsKey(row))
                    ordered.add(row);
            }
            return groupByFile(ordered);
        }

        /** Внутри порядка «видимые сначала» строки одного модуля идут подряд — текст читается один раз. */
        private static List<PreviewRow> groupByFile(List<PreviewRow> ordered)
        {
            Map<IFile, List<PreviewRow>> byFile = new LinkedHashMap<>();
            for (PreviewRow row : ordered)
                byFile.computeIfAbsent(row.file, key -> new ArrayList<>()).add(row);
            List<PreviewRow> result = new ArrayList<>(ordered.size());
            for (List<PreviewRow> group : byFile.values())
                result.addAll(group);
            return result;
        }

        private static int lineOf(String content, int offset)
        {
            int line = 1;
            int limit = Math.min(offset, content.length());
            for (int i = 0; i < limit; i++)
            {
                if (content.charAt(i) == '\n')
                    line++;
            }
            return line;
        }

        // ---- Прочее ----

        private static String cellText(Object element, int column)
        {
            if (!(element instanceof PreviewRow row))
                return ""; //$NON-NLS-1$
            return switch (column)
            {
                case 0 -> row.text;
                case 1 -> row.fileName;
                case 2 -> row.fileType;
                case 3 -> row.module;
                case 4 -> row.method;
                case 5 -> row.parent;
                case 6 -> row.parentType != null ? row.parentType : UNKNOWN_TYPE;
                case 7 -> row.syntaxKind;
                default -> ""; //$NON-NLS-1$
            };
        }

        private void saveColumnLayout()
        {
            if (table == null || table.isDisposed())
                return;
            TableColumn[] columns = table.getColumns();
            if (columns.length != WIDTH_KEYS.length)
                return;
            FormTableColumnState.saveOrderAndWidths(dialogSettings(), KEY_COL_ORDER, KEY_COLUMNS_FILL,
                interaction != null && interaction.isColumnsExactFill(), WIDTH_KEYS, columns, table);
        }

        private static IDialogSettings dialogSettings()
        {
            IDialogSettings root = Activator.getDefault().getDialogSettings();
            IDialogSettings section = root.getSection(SETTINGS_SECTION);
            return section != null ? section : root.addNewSection(SETTINGS_SECTION);
        }

        /**
         * Иконка кнопки табличного режима: контур таблицы с разлиновкой, системными цветами.
         * Кэшируется готовая {@link ImageData} — она не зависит от дисплея, в отличие от
         * {@link Image}, и переживает пересоздание тулбара менеджером.
         */
        private static synchronized ImageDescriptor tableModeIconDescriptor()
        {
            if (tableModeIconData == null)
            {
                Display display = Display.getCurrent();
                if (display == null || display.isDisposed())
                    return null;
                int size = 16;
                Image image = new Image(display, size, size);
                GC gc = new GC(image);
                try
                {
                    gc.setAdvanced(true);
                    gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
                    gc.fillRectangle(0, 0, size, size);
                    gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
                    gc.drawRectangle(2, 3, size - 6, size - 7);
                    gc.drawLine(2, 6, size - 4, 6);
                    gc.drawLine(2, 9, size - 4, 9);
                    gc.drawLine(6, 3, 6, size - 4);
                }
                finally
                {
                    gc.dispose();
                }
                tableModeIconData = image.getImageData();
                image.dispose();
            }
            return new ImageDescriptor()
            {
                @Override
                public ImageData getImageData()
                {
                    return tableModeIconData;
                }
            };
        }
    }
}
