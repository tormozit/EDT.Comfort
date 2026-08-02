package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

import tormozit.BslModuleStructureDiff.DiffNode;
import tormozit.BslModuleStructureDiff.Kind;
import tormozit.BslModuleStructureDiff.Result;

/**
 * Панель «Структура» попарного {@code CompareDialog} (см. {@link CompareDialogCurrentLinesHook}) —
 * дерево различающихся секций модуля сверху над текстовым сравнением.
 *
 * <p>Собственный простой {@link TreeViewer} — НЕ штатный {@code DtComparisonView}/
 * {@code ComparisonTreeControl} из 3-way merge: та интеграция (internal Guice/checkNotNull-тяжёлая
 * инфраструктура {@code DtComparisonViewContext}, множество проверенных вслепую реализаций
 * {@code IPartialModelNode}/{@code IComparedElement}) стабильно давала пустое дерево при валидных
 * данных — судя по всему, у неё собственный, не до конца выявленный механизм рендеринга (колонки/
 * label provider), который нельзя достоверно повторить через рефлексию за разумное время. Полное
 * визуальное сходство со штатной панелью 3-way принесено в жертву надёжности — дерево здесь
 * полностью в нашем контроле, но по возможности приближено к штатному внешнему виду: поле
 * многословного фильтра сверху и компактный фильтр по типу отличий снизу, как у штатной панели
 * структуры «Сравнивать модули с учётом структуры».
 *
 * <p>Данные — {@link BslModuleStructureDiff.DiffNode} (только узлы с различиями, плюс узлы
 * синтаксической ошибки, см. {@link BslModuleStructureDiff}). Если ни с одной стороны не удалось
 * получить вообще никакой AST (см. {@link BslModuleStructureDiff.Result#leftError}) — вместо
 * дерева показывается текст ошибки (выделяемый/копируемый {@link Text}, не {@link LabelProvider}).
 */
final class CompareDialogStructurePanel
{
    private final Composite control;
    private final Tree tree; // null, если показана область с текстом ошибки вместо дерева

    private CompareDialogStructurePanel(Composite control, Tree tree)
    {
        this.control = control;
        this.tree = tree;
    }

    Composite getControl()
    {
        return control;
    }

    /**
     * Высота на {@code rows} строк дерева (плюс строка фильтра сверху и строка фильтра типа
     * снизу — примерно такой же высоты) — для расчёта высоты по умолчанию в
     * {@link StructureToggleController}. {@code -1}, если дерева сейчас нет (область ошибки).
     */
    int computeHeightForRows(int rows)
    {
        if (tree == null || tree.isDisposed())
            return -1;
        int rowHeight = Math.max(1, tree.getItemHeight());
        return tree.getHeaderHeight() + rowHeight * rows + 2 * tree.getBorderWidth() + rowHeight * 2 + 8;
    }

    /**
     * @param onSelect вызывается с {@code null} при выборе корня (штатное поведение — без
     *                 подсветки диапазона) либо с выбранным {@link DiffNode} для подсветки
     * @param onDoubleClick двойной клик по узлу — переход к первому отличию ВНУТРИ него
     *                 (не к заголовку); с {@code null} не вызывается (для корня штатно
     *                 срабатывает только {@code onSelect})
     */
    static CompareDialogStructurePanel create(Composite parent, String leftText, String rightText,
        String leftLabel, String rightLabel, Consumer<DiffNode> onSelect, Consumer<DiffNode> onDoubleClick)
    {
        try
        {
            Result result = BslModuleStructureDiff.diff(leftText, rightText, leftLabel, rightLabel);
            if (result.root == null)
            {
                return new CompareDialogStructurePanel(
                    createErrorArea(parent, result, leftLabel, rightLabel), null);
            }

            Tree[] treeHolder = new Tree[1];
            Composite container =
                createTreeContainer(parent, result.root, leftLabel, rightLabel, onSelect, onDoubleClick, treeHolder);
            return new CompareDialogStructurePanel(container, treeHolder[0]);
        }
        catch (RuntimeException e)
        {
            return new CompareDialogStructurePanel(createMessageArea(parent,
                "Не удалось построить панель структуры: " + e), null); //$NON-NLS-1$
        }
    }

    /**
     * [поле многословного фильтра (штатный {@link FilterInputBox}/{@code SearchBox} — история
     * встроена в сам виджет, лупа/крестик/история — как в окне «Значения» EDT, НЕ отдельная
     * кнопка рядом) + компактный фильтр по типу отличий, одной строкой] / [дерево].
     */
    private static Composite createTreeContainer(Composite parent, DiffNode root, String leftLabel,
        String rightLabel, Consumer<DiffNode> onSelect, Consumer<DiffNode> onDoubleClick, Tree[] treeHolder)
    {
        Composite container = new Composite(parent, SWT.NONE);
        GridLayout containerLayout = new GridLayout(1, false);
        containerLayout.marginWidth = 0;
        containerLayout.marginHeight = 0;
        container.setLayout(containerLayout);

        // Структурные заготовки строки фильтра — заполняются ПОСЛЕ дерева/viewer'а ниже
        // (кросс-ссылки: обработчик фильтра должен видеть уже готовые textFilter/viewer).
        Composite filterRow = new Composite(container, SWT.NONE);
        GridLayout filterRowLayout = new GridLayout(1, false);
        filterRowLayout.marginWidth = 0;
        filterRowLayout.marginHeight = 0;
        filterRow.setLayout(filterRowLayout);
        filterRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Tree tree = new Tree(container, SWT.SINGLE | SWT.BORDER | SWT.FULL_SELECTION);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        treeHolder[0] = tree;

        TreeViewer viewer = new TreeViewer(tree);
        viewer.setContentProvider(new DiffTreeContentProvider());
        DiffTreeLabelProvider labelProvider = new DiffTreeLabelProvider();
        viewer.setLabelProvider(labelProvider);
        // Синтаксические ошибки — важнее прочего, всегда первыми; внутри каждой группы — по алфавиту.
        viewer.setComparator(new ViewerComparator()
        {
            @Override
            public int compare(Viewer v, Object e1, Object e2)
            {
                boolean err1 = e1 instanceof DiffNode n && n.kind == Kind.SYNTAX_ERROR;
                boolean err2 = e2 instanceof DiffNode n && n.kind == Kind.SYNTAX_ERROR;
                if (err1 != err2)
                    return err1 ? -1 : 1;
                String l1 = e1 instanceof DiffNode n ? n.label : String.valueOf(e1);
                String l2 = e2 instanceof DiffNode n ? n.label : String.valueOf(e2);
                return l1.compareToIgnoreCase(l2);
            }
        });

        TextFilter textFilter = new TextFilter();
        KindFilter kindFilter = new KindFilter();
        viewer.setFilters(textFilter, kindFilter);

        /*
         * root (kind=ROOT, label="Модуль") сам НЕ показываем — он служебный контейнер (выбор
         * которого означает "штатное поведение, без подсветки", см. onSelect ниже), а не
         * реальный элемент структуры. Раньше он был единственной видимой строкой верхнего
         * уровня, и без ручного разворачивания реальные различия (его дети) не были видны.
         * Показываем сразу его детей как элементы верхнего уровня.
         */
        viewer.setInput(root);
        viewer.expandToLevel(3);
        viewer.addPostSelectionChangedListener(event ->
        {
            Object selected = event.getStructuredSelection().getFirstElement();
            DiffNode node = selected instanceof DiffNode d ? d : null;
            onSelect.accept(node != null && node.kind == Kind.ROOT ? null : node);
        });
        if (onDoubleClick != null)
        {
            viewer.addDoubleClickListener(event ->
            {
                Object selection = event.getSelection();
                Object selected = selection instanceof org.eclipse.jface.viewers.IStructuredSelection s
                    ? s.getFirstElement() : null;
                if (selected instanceof DiffNode node && node.kind != Kind.ROOT)
                    onDoubleClick.accept(node);
            });
        }
        wireTreeCopy(tree);

        // Штатный SearchBox (история встроена в сам виджет — лупа/крестик/история, как в
        // окне «Значения» EDT), не самодельный Text с отдельной кнопкой рядом.
        FilterInputBox[] filterBoxHolder = new FilterInputBox[1];
        FilterInputBox filterBox = FilterInputBox.forCompareStructure(filterRow, () ->
        {
            String pattern = filterBoxHolder[0].getText();
            textFilter.setPattern(pattern);
            labelProvider.setHighlightPattern(pattern);
            viewer.refresh();
        });
        filterBoxHolder[0] = filterBox;

        // Комбо само подгоняет ширину под самый длинный пункт (без grab) — тоже компактно.
        Combo kindCombo = new Combo(filterRow, SWT.READ_ONLY);
        if (filterRow.getLayout() instanceof GridLayout grid)
            grid.numColumns = grid.numColumns + 1;
        kindCombo.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        List<Kind> comboKinds = populateKindCombo(kindCombo, root, leftLabel, rightLabel);
        kindCombo.addListener(SWT.Selection, e ->
        {
            int i = kindCombo.getSelectionIndex();
            kindFilter.onlyKind = i >= 0 && i < comboKinds.size() ? comboKinds.get(i) : null;
            viewer.refresh();
        });

        return container;
    }

    /**
     * «Показывать отличия» / «Показывать только <leftLabel> (N)» / «Показывать только <rightLabel> (N)» /
     * «Показывать изменённые (N)» / «Показывать ошибки (N)» — только для типов, реально
     * присутствующих в дереве (N &gt; 0), аналогично штатному «Фильтр:» в 3-way/сравнении
     * конфигураций.
     *
     * @return соответствие «индекс combo → {@link Kind}» ({@code null} на позиции 0 — «Показывать отличия»,
     *         т.к. совпадающие элементы мы и так не показываем — в отличие от штатного сравнения структуры)
     */
    private static List<Kind> populateKindCombo(Combo combo, DiffNode root, String leftLabel, String rightLabel)
    {
        int[] counts = new int[Kind.values().length];
        countKinds(root, counts);

        int totalDiffs = counts[Kind.REMOVED.ordinal()] + counts[Kind.ADDED.ordinal()]
            + counts[Kind.CHANGED.ordinal()] + counts[Kind.SYNTAX_ERROR.ordinal()];

        List<Kind> kinds = new ArrayList<>();
        combo.add("Показывать отличия (" + totalDiffs + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.add(null);

        if (counts[Kind.REMOVED.ordinal()] > 0)
        {
            combo.add("Показывать только " + labelOrDefault(leftLabel, "левую сторону") //$NON-NLS-1$ //$NON-NLS-2$
                + " (" + counts[Kind.REMOVED.ordinal()] + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            kinds.add(Kind.REMOVED);
        }
        if (counts[Kind.ADDED.ordinal()] > 0)
        {
            combo.add("Показывать только " + labelOrDefault(rightLabel, "правую сторону") //$NON-NLS-1$ //$NON-NLS-2$
                + " (" + counts[Kind.ADDED.ordinal()] + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            kinds.add(Kind.ADDED);
        }
        if (counts[Kind.CHANGED.ordinal()] > 0)
        {
            combo.add("Показывать изменённые (" + counts[Kind.CHANGED.ordinal()] + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            kinds.add(Kind.CHANGED);
        }
        if (counts[Kind.SYNTAX_ERROR.ordinal()] > 0)
        {
            combo.add("Показывать ошибки (" + counts[Kind.SYNTAX_ERROR.ordinal()] + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            kinds.add(Kind.SYNTAX_ERROR);
        }
        combo.select(0);
        return kinds;
    }

    private static void countKinds(DiffNode node, int[] counts)
    {
        for (DiffNode child : node.children)
        {
            counts[child.kind.ordinal()]++;
            countKinds(child, counts);
        }
    }

    /** Многословный фильтр (флэт-AND, {@link SmartMatcher}) — совпадение по себе или по любому потомку. */
    private static final class TextFilter extends ViewerFilter
    {
        private SmartMatcher matcher;

        void setPattern(String pattern)
        {
            matcher = pattern == null || pattern.isBlank() ? null : new SmartMatcher(pattern);
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            if (matcher == null || !(element instanceof DiffNode node))
                return true;
            return matches(node);
        }

        private boolean matches(DiffNode node)
        {
            if (matcher.matches(node.label))
                return true;
            for (DiffNode child : node.children)
                if (matches(child))
                    return true;
            return false;
        }
    }

    /** Фильтр по типу отличия (см. {@link #populateKindCombo}) — совпадение по себе или по любому потомку. */
    private static final class KindFilter extends ViewerFilter
    {
        private Kind onlyKind;

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            if (onlyKind == null || !(element instanceof DiffNode node))
                return true;
            return matches(node);
        }

        private boolean matches(DiffNode node)
        {
            if (node.kind == onlyKind)
                return true;
            for (DiffNode child : node.children)
                if (matches(child))
                    return true;
            return false;
        }
    }

    /**
     * Копирование текста строки по Ctrl+C — как и везде в таблицах/деревьях плагина
     * (см. AGENTS.md, «Таблицы в формах»). {@code Tree.addListener(SWT.KeyDown, ...)} тут не
     * сработает: нативная Win32-трансляция Ctrl+C в акселератор диалога съедает событие раньше,
     * чем SWT создаст {@code KeyDown} (тот же архитектурный потолок, что и
     * {@code PreferenceSearchFilterAugmenter.wireTreeCopy}, у которой этот приём подсмотрен) —
     * перехватываем команду {@code org.eclipse.ui.edit.copy} через {@code ICommandService},
     * а не клавишу. В отличие от прообраза — поддержка НЕСКОЛЬКИХ одновременно открытых деревьев
     * структуры (разные окна сравнения), не только одного статического экземпляра.
     */
    private static final List<Tree> copyTargetTrees = new CopyOnWriteArrayList<>();
    private static volatile boolean copyExecutionListenerInstalled;

    private static void wireTreeCopy(Tree tree)
    {
        copyTargetTrees.add(tree);
        tree.addDisposeListener(e -> copyTargetTrees.remove(tree));
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
                handlePossibleTreeCopy("preExecute:" + commandId); //$NON-NLS-1$
            }

            /*
             * Если у команды org.eclipse.ui.edit.copy НАШЁЛСЯ реальный обработчик (например,
             * глобальный Copy активной части workbench позади модального 3-way диалога) — он
             * выполняется ПОСЛЕ preExecute и может перезаписать буфер обмена своим (возможно,
             * пустым) содержимым. Пишем сюда же ЕЩЁ РАЗ, чтобы наша запись была последней.
             */
            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
                handlePossibleTreeCopy("postExecuteSuccess:" + commandId); //$NON-NLS-1$
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception)
            {
                handlePossibleTreeCopy("notHandled:" + commandId); //$NON-NLS-1$
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception)
            {
            }
        });
        copyExecutionListenerInstalled = true;
    }

    private static void handlePossibleTreeCopy(String taggedCommandId)
    {
        int colon = taggedCommandId.indexOf(':');
        String commandId = colon >= 0 ? taggedCommandId.substring(colon + 1) : taggedCommandId;
        if (!"org.eclipse.ui.edit.copy".equals(commandId)) //$NON-NLS-1$
            return;
        for (Tree tree : copyTargetTrees)
        {
            boolean focused = !tree.isDisposed() && tree.getDisplay().getFocusControl() == tree;
            if (!focused)
                continue;
            TreeItem[] selection = tree.getSelection();
            if (selection.length == 0)
                return;
            // Не selection[0].getText() — там наш декоративный префикс (+/−/⚠/~), копируем чистое имя.
            Object data = selection[0].getData();
            String text = data instanceof DiffNode node ? node.label : selection[0].getText();
            if (text == null || text.isBlank())
                return;
            Clipboard clipboard = new Clipboard(tree.getDisplay());
            try
            {
                clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
            }
            finally
            {
                clipboard.dispose();
            }
            return;
        }
    }

    private static final class DiffTreeContentProvider implements ITreeContentProvider
    {
        /** Сам служебный корень (label="Модуль") не показываем — сразу его дети как верхний уровень. */
        @Override
        public Object[] getElements(Object inputElement)
        {
            return inputElement instanceof DiffNode root ? root.children.toArray() : new Object[0];
        }

        @Override
        public Object[] getChildren(Object parentElement)
        {
            return parentElement instanceof DiffNode node ? node.children.toArray() : new Object[0];
        }

        /**
         * ВАЖНО: AbstractTreeViewer полагается на реальный getParent (иначе дерево рисуется
         * неверно — см. DiffNode.parent / DiffNode.addChild). Для элементов верхнего уровня
         * (их родитель — служебный корень, которого в дереве нет) возвращаем {@code null},
         * иначе TreeViewer будет пытаться найти в дереве несуществующий элемент-родитель.
         */
        @Override
        public Object getParent(Object element)
        {
            if (!(element instanceof DiffNode node) || node.parent == null)
                return null;
            return node.parent.kind == Kind.ROOT ? null : node.parent;
        }

        @Override
        public boolean hasChildren(Object element)
        {
            return element instanceof DiffNode node && !node.children.isEmpty();
        }
    }

    /**
     * Текст-маркер вида различия, цвет ФОНА строки (без цвета текста — приближение к раскраске
     * штатной панели: оранжевый - только слева, зелёный - только справа, красный - ошибка) и
     * подсветка совпадений текстового фильтра — {@link StyledCellLabelProvider}, не
     * {@link ColumnLabelProvider} (нужны {@code StyleRange} для
     * {@link SmartMatchHighlight#appendMatchRanges}, тот же готовый помощник, что и в
     * {@code GitStagingFilterHook}).
     */
    private static final class DiffTreeLabelProvider extends StyledCellLabelProvider
    {
        private Color addedBackground;
        private Color removedBackground;
        private Color errorBackground;
        private SmartMatcher highlightMatcher = new SmartMatcher(""); //$NON-NLS-1$

        void setHighlightPattern(String pattern)
        {
            highlightMatcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
        }

        private static String prefixFor(Kind kind)
        {
            return switch (kind)
            {
                case ADDED -> "+ "; //$NON-NLS-1$
                case REMOVED -> "− "; //$NON-NLS-1$
                case SYNTAX_ERROR -> "⚠ "; //$NON-NLS-1$
                case CHANGED -> "~ "; //$NON-NLS-1$
                case ROOT -> ""; //$NON-NLS-1$
            };
        }

        @Override
        public void update(ViewerCell cell)
        {
            Object element = cell.getElement();
            if (!(element instanceof DiffNode node))
            {
                cell.setText(String.valueOf(element));
                super.update(cell);
                return;
            }
            String prefix = prefixFor(node.kind);
            cell.setText(prefix + node.label);
            cell.setBackground(backgroundFor(node.kind));

            List<SmartMatcher.HighlightRange> ranges = !highlightMatcher.isEmpty
                ? offsetRanges(highlightMatcher.getHighlightRanges(node.label), prefix.length())
                : List.of();
            SmartMatchHighlight.appendMatchRanges(cell, ranges);
            super.update(cell);
        }

        private static List<SmartMatcher.HighlightRange> offsetRanges(
            List<SmartMatcher.HighlightRange> ranges, int offset)
        {
            if (offset == 0 || ranges.isEmpty())
                return ranges;
            List<SmartMatcher.HighlightRange> shifted = new ArrayList<>(ranges.size());
            for (SmartMatcher.HighlightRange r : ranges)
                shifted.add(new SmartMatcher.HighlightRange(r.offset + offset, r.length));
            return shifted;
        }

        private Color backgroundFor(Kind kind)
        {
            Display display = Display.getCurrent();
            return switch (kind)
            {
                case ADDED -> lightGreen(display);
                case REMOVED -> lightOrange(display);
                case SYNTAX_ERROR -> lightRed(display);
                case CHANGED, ROOT -> null;
            };
        }

        private Color lightGreen(Display display)
        {
            if (addedBackground == null || addedBackground.isDisposed())
                addedBackground = new Color(display, 226, 245, 226);
            return addedBackground;
        }

        private Color lightOrange(Display display)
        {
            if (removedBackground == null || removedBackground.isDisposed())
                removedBackground = new Color(display, 250, 235, 215);
            return removedBackground;
        }

        private Color lightRed(Display display)
        {
            if (errorBackground == null || errorBackground.isDisposed())
                errorBackground = new Color(display, 250, 220, 220);
            return errorBackground;
        }

        @Override
        public void dispose()
        {
            if (addedBackground != null && !addedBackground.isDisposed())
                addedBackground.dispose();
            if (removedBackground != null && !removedBackground.isDisposed())
                removedBackground.dispose();
            if (errorBackground != null && !errorBackground.isDisposed())
                errorBackground.dispose();
            super.dispose();
        }
    }

    private static Composite createErrorArea(Composite parent, Result result, String leftLabel, String rightLabel)
    {
        StringBuilder text = new StringBuilder();
        if (result.leftError != null)
            text.append(labelOrDefault(leftLabel, "Слева")).append(": ").append(result.leftError); //$NON-NLS-1$ //$NON-NLS-2$
        if (result.rightError != null)
        {
            if (text.length() > 0)
                text.append(System.lineSeparator());
            text.append(labelOrDefault(rightLabel, "Справа")).append(": ").append(result.rightError); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return createMessageArea(parent, text.toString());
    }

    /** {@link Text}, не {@link LabelProvider}-виджет — текст ошибки должен быть выделяемым и копируемым (Ctrl+C). */
    private static Composite createMessageArea(Composite parent, String message)
    {
        Composite area = new Composite(parent, SWT.NONE);
        area.setLayout(new GridLayout(1, false));
        Text text = new Text(area, SWT.WRAP | SWT.MULTI | SWT.READ_ONLY);
        text.setText(message);
        text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        return area;
    }

    private static String labelOrDefault(String text, String fallback)
    {
        return text != null && !text.isBlank() ? text.replaceAll(":$", "") : fallback; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
