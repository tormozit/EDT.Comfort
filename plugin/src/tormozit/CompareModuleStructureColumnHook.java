package tormozit;

import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ViewerColumn;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;

import com._1c.g5.v8.dt.bsl.compare.BslModuleComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IPartialModelNode;

/**
 * Если включена «Сравнивать модули с учётом структуры», но AST одной из версий модуля не
 * строится ({@code BslModuleComparisonNode.isParseModuleStructure() == false} — EDT сама
 * откатывается к текстовому сравнению, см. декомпилированный
 * {@code BslModuleComparisonParticipant.compareBslModuleNode}), в ячейках сторон дерева
 * сравнения для этого модуля показываем «Структура недоступна» вместо обычной подписи.
 *
 * <p>{@code isParseModuleStructure()} — единый флаг на весь узел, EDT не хранит (и сама не
 * знает — см. лог-сообщение в {@code compareBslModuleNode}, берущее {@code mainSymlink ?:
 * otherSymlink} без разбора, чья сторона реально сломалась) какая именно сторона не
 * распарсилась — поэтому текст показывается в ячейках обеих сторон.
 *
 * <p>Колонки сторон дерева ({@code DtComparisonView$ComparisonSideColumnLabelProvider},
 * package-private — недоступен для прямой ссылки, определяем по имени класса) находим через
 * {@code TreeColumn.getData("org.eclipse.jface.columnViewer")} → {@link ViewerColumn}
 * (публичный JFace API, ключ данных подтверждён декомпиляцией) и оборачиваем их
 * {@code LabelProvider} — тот класс расширяет {@code ColumnLabelProvider}, поэтому оборачиваем
 * тем же типом, делегируя текст/цвета оригиналу через рефлексию (тип оригинала недоступен для
 * прямой компиляции).
 */
public final class CompareModuleStructureColumnHook
{
    private static final String TAG = "CompareModuleStructureColumn"; //$NON-NLS-1$
    private static final String INSTALLED_KEY = "tormozit.compareModuleStructureColumnInstalled"; //$NON-NLS-1$
    private static final String COLUMN_VIEWER_DATA_KEY = "org.eclipse.jface.columnViewer"; //$NON-NLS-1$
    private static final String COMPARISON_SIDE_PROVIDER_CLASS = "ComparisonSideColumnLabelProvider"; //$NON-NLS-1$
    private static final String TEXT_STRUCTURE_UNAVAILABLE = "Структура недоступна"; //$NON-NLS-1$

    private CompareModuleStructureColumnHook()
    {
    }

    public static void install(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(INSTALLED_KEY)))
            return;
        tree.setData(INSTALLED_KEY, Boolean.TRUE);

        int wrapped = 0;
        for (TreeColumn column : tree.getColumns())
            if (wrapColumnIfComparisonSide(column))
                wrapped++;
        if (wrapped == 0)
            log("install: колонка стороны сравнения не найдена"); //$NON-NLS-1$
    }

    private static boolean wrapColumnIfComparisonSide(TreeColumn column)
    {
        Object viewerColumnObj = column.getData(COLUMN_VIEWER_DATA_KEY);
        if (!(viewerColumnObj instanceof ViewerColumn viewerColumn))
            return false;

        // getLabelProvider() у ViewerColumn package-private (не public) — только через рефлексию;
        // setLabelProvider(CellLabelProvider) ниже уже public, вызывается напрямую.
        Object currentProvider = Global.invoke(viewerColumn, "getLabelProvider"); //$NON-NLS-1$
        if (currentProvider == null
            || !COMPARISON_SIDE_PROVIDER_CLASS.equals(currentProvider.getClass().getSimpleName()))
            return false;

        viewerColumn.setLabelProvider(new ModuleStructureLabelProvider(currentProvider));
        return true;
    }

    private static boolean isStructureUnavailable(Object element)
    {
        if (!(element instanceof IPartialModelNode node))
            return false;
        ComparisonNode comparisonNode = node.retrieveComparisonNode();
        return comparisonNode instanceof BslModuleComparisonNode module && !module.isParseModuleStructure();
    }

    private static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, msg);
    }

    /** Оборачивает оригинальный {@code ComparisonSideColumnLabelProvider} (недоступен по типу — только через рефлексию). */
    private static final class ModuleStructureLabelProvider extends ColumnLabelProvider
    {
        private final Object original;

        ModuleStructureLabelProvider(Object original)
        {
            this.original = original;
        }

        @Override
        public String getText(Object element)
        {
            if (isStructureUnavailable(element))
                return TEXT_STRUCTURE_UNAVAILABLE;
            Object text = Global.invoke(original, "getText", element); //$NON-NLS-1$
            return text instanceof String ? (String) text : null;
        }

        @Override
        public Color getForeground(Object element)
        {
            Object color = Global.invoke(original, "getForeground", element); //$NON-NLS-1$
            return color instanceof Color ? (Color) color : null;
        }

        @Override
        public Color getBackground(Object element)
        {
            Object color = Global.invoke(original, "getBackground", element); //$NON-NLS-1$
            return color instanceof Color ? (Color) color : null;
        }
    }
}
