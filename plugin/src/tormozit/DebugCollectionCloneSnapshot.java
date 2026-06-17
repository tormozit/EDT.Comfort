package tormozit;

import org.eclipse.swt.widgets.TableItem;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;

/**
 * Снимок состояния окна «Коллекция» для клонирования без повторной загрузки данных.
 */
final class DebugCollectionCloneSnapshot
{
    private final IBslIndexedValue indexedValue;
    private final IBslStackFrame frame;
    private final BslValuePath path;
    private final DebugCollectionColumnModel columns;
    private final DebugCollectionTableModel sourceModel;
    private final DebugCollectionRowFilter sourceFilter;
    private final String filterText;
    private final int topIndex;
    private final int selectedDisplayIndex;
    private final int selectedVisibleColumn;
    private final String presentationHeader;
    private final boolean filterByPresentation;

    private DebugCollectionCloneSnapshot(
        IBslIndexedValue indexedValue,
        IBslStackFrame frame,
        BslValuePath path,
        DebugCollectionColumnModel columns,
        DebugCollectionTableModel sourceModel,
        DebugCollectionRowFilter sourceFilter,
        String filterText,
        int topIndex,
        int selectedDisplayIndex,
        int selectedVisibleColumn,
        String presentationHeader,
        boolean filterByPresentation)
    {
        this.indexedValue = indexedValue;
        this.frame = frame;
        this.path = path;
        this.columns = columns;
        this.sourceModel = sourceModel;
        this.sourceFilter = sourceFilter;
        this.filterText = filterText != null ? filterText : ""; //$NON-NLS-1$
        this.topIndex = Math.max(0, topIndex);
        this.selectedDisplayIndex = selectedDisplayIndex;
        this.selectedVisibleColumn = selectedVisibleColumn;
        this.presentationHeader = presentationHeader;
        this.filterByPresentation = filterByPresentation;
    }

    static DebugCollectionCloneSnapshot capture(DebugCollectionWindow source)
    {
        if (source == null || source.isDisposed())
            return null;
        DebugCollectionTableModel model = source.tableModelForClone();
        if (model == null)
            return null;

        String filterText = source.filterTextForClone();
        DebugCollectionRowFilter filter = source.rowFilterForClone();
        int top = source.topIndexForClone();
        int displayIndex = -1;
        int visibleColumn = -1;
        DebugCollectionTableInteraction interaction = source.activeInteractionForClone();
        if (interaction != null)
        {
            TableItem item = interaction.selectedItem();
            if (item != null && !item.isDisposed())
            {
                displayIndex = DebugCollectionTableItemKeys.displayIndex(item, item.getParent());
                visibleColumn = interaction.modelVisibleColumn();
            }
        }
        String presentation = model.columns.presentationHeader();
        if (presentation == null)
            presentation = source.currentPresentationHeaderForClone();

        DebugCollectionColumnModel columnsSnapshot = model.columns.copy();
        if (presentation != null && !presentation.isBlank())
            columnsSnapshot.applyPresentationHeader(presentation);

        boolean filterByPresentation = source.filterByPresentationForClone();

        return new DebugCollectionCloneSnapshot(
            source.indexedValueForClone(),
            source.stackFrameForClone(),
            source.valuePathForClone(),
            columnsSnapshot,
            model,
            filter,
            filterText,
            top,
            displayIndex,
            visibleColumn,
            presentation,
            filterByPresentation);
    }

    IBslIndexedValue indexedValue()
    {
        return indexedValue;
    }

    IBslStackFrame frame()
    {
        return frame;
    }

    BslValuePath path()
    {
        return path;
    }

    boolean schemaResolved()
    {
        return columns != null && columns.modelColumnCount() > 1;
    }

    DebugCollectionTableModel toModel(String typeTitle)
    {
        DebugCollectionTableModel model = new DebugCollectionTableModel(
            indexedValue, frame, path, columns.copy(), typeTitle);
        model.importCachesFrom(sourceModel);
        return model;
    }

    DebugCollectionRowFilter toRowFilter()
    {
        DebugCollectionRowFilter filter = DebugCollectionRowFilter.copyFrom(sourceFilter, filterText);
        filter.setPresentationOnly(filterByPresentation);
        return filter;
    }

    String filterText()
    {
        return filterText;
    }

    int topIndex()
    {
        return topIndex;
    }

    int selectedDisplayIndex()
    {
        return selectedDisplayIndex;
    }

    int selectedVisibleColumn()
    {
        return selectedVisibleColumn;
    }

    String presentationHeader()
    {
        return presentationHeader;
    }

    boolean filterByPresentation()
    {
        return filterByPresentation;
    }
}
