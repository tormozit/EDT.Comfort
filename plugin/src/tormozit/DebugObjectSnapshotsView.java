package tormozit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;
import com._1c.g5.v8.dt.debug.util.DebugNodeModelUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class DebugObjectSnapshotsView extends ViewPart
{
    private static final String ID = "tormozit.DebugObjectSnapshotsView"; //$NON-NLS-1$
    private static final String TITLE = "Снимки объектов"; //$NON-NLS-1$
    private static final String ICON_PATH = "icons/eview16/object_snapshots.png"; //$NON-NLS-1$
    private static final String TEMP_LOG = "debug-object-snapshots"; //$NON-NLS-1$
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss") //$NON-NLS-1$
        .withZone(ZoneId.systemDefault());
    private static final Comparator<String> PROPERTY_ORDER =
        String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    private TableViewer snapshotsViewer;
    private TableViewer propertiesViewer;
    private TableViewer comparisonViewer;
    private Composite details;
    private Composite comparisonPane;
    private StackLayout detailsLayout;
    private Button hideEqual;
    private List<Snapshot> snapshots = List.of();
    private int[] propertiesColumnWidths;
    private int[] propertiesColumnOrder;

    static boolean canCapture(Object element)
    {
        return element instanceof IBslVariable
            || element instanceof IWatchExpression watch && watch.isEnabled() && !watch.isPending()
                && !watch.hasErrors() && watch.getValue() instanceof IBslValue;
    }

    static MenuItem addSaveMenuItem(Menu menu, Runnable capture)
    {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText("Сохранить снимок"); //$NON-NLS-1$
        Activator activator = Activator.getDefault();
        var images = activator.getImageRegistry();
        if (images.getDescriptor(ICON_PATH) == null)
            images.put(ICON_PATH, ImageDescriptor.createFromURL(activator.getBundle().getEntry(ICON_PATH)));
        item.setImage(images.get(ICON_PATH));
        ComfortSubmenuHelper.setMenuItemTooltip(item, "Сохранить свойства объекта отладки"); //$NON-NLS-1$
        item.addListener(SWT.Selection, event -> capture.run());
        return item;
    }

    static void capture(Object element)
    {
        capture(() ->
        {
            if (element instanceof IBslVariable variable)
                return new Target(variable.getName(), variable.getValue(), variable.getStackFrame());
            if (element instanceof IWatchExpression watch && canCapture(watch)
                && watch.getValue() instanceof IBslValue value)
                return new Target(watch.getExpressionText(), value, value.getStackFrame());
            return null;
        });
    }

    static void captureRow(DebugCollectionTableModel model, int row, IBslStackFrame frame)
    {
        capture(() ->
        {
            IBslVariable variable = model.getRowVariable(row);
            Global.tempLog(TEMP_LOG, "collection row=" + row + " resolved=" + (variable != null)); //$NON-NLS-1$
            if (variable == null)
                return null;
            String expression = BslInspectSupport.resolveExpressionTextForVariable(variable, model, row, null);
            return new Target(expression.isBlank() ? variable.getName() : expression, variable.getValue(), frame);
        });
    }

    private static void capture(TargetReader reader)
    {
        Display display = Display.getCurrent();
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        Job.create("Сохранение снимка объекта отладки", monitor -> //$NON-NLS-1$
        {
            try
            {
                Target target = reader.read();
                if (target == null || target.value() == null || target.value().isUnreadable())
                    throw new IOException("Значение объекта недоступно."); //$NON-NLS-1$
                IBslValue value = target.value();
                IBslStackFrame frame = value.getStackFrame();
                if (frame == null)
                    frame = target.frame();
                checkSuspended(frame, monitor);
                int line = frame.getLineNumber();
                var source = frame.getSource();
                String path = source != null && source.isPlatformResource()
                    ? source.trimFragment().toPlatformString(true) : ""; //$NON-NLS-1$
                IFile file = workspaceFile(path);
                IProject project = file != null ? file.getProject() : DebugIRHandler.getProjectFromStackFrame(frame);
                Global.tempLog(TEMP_LOG, "read valueClass=" + value.getClass().getSimpleName() //$NON-NLS-1$
                    + " type=" + value.getValueTypeName() + " sourcePath=" + path + " line=" + line); //$NON-NLS-1$
                GetRef.ModuleLineLocation location = sourceLocation(frame, file, line);
                List<Property> properties = readProperties(value, frame, monitor);
                checkSuspended(frame, monitor);
                String type = text(value.getValueTypeName());
                Snapshot snapshot = new Snapshot(UUID.randomUUID().toString(), System.currentTimeMillis(),
                    text(target.name()), type, location, project != null ? project.getName() : "", properties); //$NON-NLS-1$
                Snapshot previous = Store.append(snapshot);
                boolean comparable = previous != null && propertyNames(previous).equals(propertyNames(snapshot));
                Global.tempLog(TEMP_LOG, "saved properties=" + properties.size() + " comparable=" + comparable); //$NON-NLS-1$
                if (!display.isDisposed())
                    display.asyncExec(() ->
                    {
                        refreshOpenViews();
                        List<String> ids = comparable ? List.of(previous.id(), snapshot.id()) : List.of(snapshot.id());
                        ToastNotification.show("Снимок сохранён", snapshot.name(), 6000, //$NON-NLS-1$
                            () -> showSnapshots(page, ids),
                            comparable ? "Сравнить с предыдущим" : "Открыть снимок"); //$NON-NLS-1$ //$NON-NLS-2$
                    });
                return Status.OK_STATUS;
            }
            catch (OperationCanceledException e)
            {
                Global.tempLog(TEMP_LOG, "capture canceled"); //$NON-NLS-1$
                return Status.CANCEL_STATUS;
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                Global.tempLog(TEMP_LOG, "capture interrupted"); //$NON-NLS-1$
                return Status.CANCEL_STATUS;
            }
            catch (IOException | DebugException | RuntimeException e)
            {
                Global.tempLogException(TEMP_LOG, "capture failed", e); //$NON-NLS-1$
                if (!display.isDisposed())
                    display.asyncExec(() -> ToastNotification.show("Не удалось сохранить снимок", e.getMessage())); //$NON-NLS-1$
                return Status.OK_STATUS;
            }
        }).schedule();
    }

    private static IFile workspaceFile(String path)
    {
        return path == null || path.isEmpty() ? null : ResourcesPlugin.getWorkspace().getRoot()
            .getFile(org.eclipse.core.runtime.Path.fromPortableString(path));
    }

    private static GetRef.ModuleLineLocation sourceLocation(IBslStackFrame frame, IFile file, int line)
    {
        String moduleText = null;
        if (file != null && line > 0)
        {
            try
            {
                var module = frame.getModule();
                if (module != null)
                    moduleText = DebugNodeModelUtils.getModuleResourceContent(module);
            }
            catch (RuntimeException e)
            {
                Global.tempLogException(TEMP_LOG, "module content unavailable", e); //$NON-NLS-1$
            }
        }
        GetRef.ModuleLineLocation location = GetRef.moduleLineLocation(file, moduleText, line);
        Global.tempLog(TEMP_LOG, "source textAvailable=" + (moduleText != null) //$NON-NLS-1$
            + " reference=" + (location != null ? location.reference() : "")); //$NON-NLS-1$ //$NON-NLS-2$
        return location;
    }

    private static List<String> propertyNames(Snapshot snapshot)
    {
        return snapshot.properties().stream().map(Property::name).toList();
    }

    private static List<Property> readProperties(IBslValue value, IBslStackFrame frame, IProgressMonitor monitor)
        throws DebugException, IOException, InterruptedException
    {
        boolean hasVariables = value.hasVariables();
        Global.tempLog(TEMP_LOG, "properties expandable=" + hasVariables //$NON-NLS-1$
            + " indexed=" + (value instanceof IBslIndexedValue) + " pending=" + value.isPending()); //$NON-NLS-1$
        if (!hasVariables)
            return List.of();

        IBslVariable[] variables;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        if (!(value instanceof IBslIndexedValue) && !value.isPending())
            BslValueEvaluate.ensureEvaluated(value);
        while (true)
        {
            checkSuspended(frame, monitor);
            if (value instanceof IBslIndexedValue indexed)
            {
                variables = indexed.getContextVariables();
                if (variables != null)
                    break;
            }
            else
            {
                boolean evaluated = value.isEvaluated();
                variables = value.getVariables();
                if (evaluated && variables != null)
                    break;
            }
            if (System.nanoTime() >= deadline)
            {
                Global.tempLog(TEMP_LOG, "properties timed out, pending=" + value.isPending() //$NON-NLS-1$
                    + " evaluated=" + value.isEvaluated()); //$NON-NLS-1$
                throw new IOException("Отладчик не вернул свойства объекта."); //$NON-NLS-1$
            }
            Thread.sleep(50);
        }

        List<Property> properties = new ArrayList<>(variables.length);
        for (IBslVariable variable : variables)
        {
            checkSuspended(frame, monitor);
            IBslValue property = variable.getValue();
            String presentation = property == null || property.isUnreadable()
                ? DebugCollectionTableModel.UnreadablePresentation() : text(property.getValueString());
            properties.add(new Property(text(variable.getName()), presentation, text(variable.getReferenceTypeName())));
        }
        properties.sort(Comparator.comparing(Property::name, PROPERTY_ORDER));
        return List.copyOf(properties);
    }

    private static void checkSuspended(IBslStackFrame frame, IProgressMonitor monitor) throws IOException
    {
        if (monitor.isCanceled())
            throw new OperationCanceledException();
        if (frame == null || frame.isTerminated() || !frame.getThread().isSuspended())
            throw new IOException("Для сохранения снимка остановитесь в отладчике."); //$NON-NLS-1$
    }

    private static void showSnapshots(IWorkbenchPage page, List<String> ids)
    {
        try
        {
            if (page == null)
            {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                page = window != null ? window.getActivePage() : null;
            }
            if (page != null && page.showView(ID) instanceof DebugObjectSnapshotsView view)
            {
                view.reload(ids);
                long available = view.snapshots.stream().filter(snapshot -> ids.contains(snapshot.id())).count();
                if (view.selectedSnapshots().size() < available)
                {
                    FormTableInteraction.of(view.snapshotsViewer.getTable()).clearAllFilters();
                    view.reload(ids);
                }
            }
        }
        catch (PartInitException e)
        {
            showError(e);
        }
    }

    private static void refreshOpenViews()
    {
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            for (IWorkbenchPage page : window.getPages())
                if (page.findView(ID) instanceof DebugObjectSnapshotsView view && view.snapshotsViewer != null)
                    view.reload(view.selectedIds());
    }

    @Override
    public void createPartControl(Composite parent)
    {
        SashForm panes = new SashForm(parent, SWT.HORIZONTAL);
        snapshotsViewer = createTable(panes, SWT.MULTI);
        addColumn(snapshotsViewer, "Дата и время", 135, (Snapshot snapshot) -> //$NON-NLS-1$
            DATE.format(Instant.ofEpochMilli(snapshot.date())));
        addColumn(snapshotsViewer, "Имя", 150, Snapshot::name); //$NON-NLS-1$
        addColumn(snapshotsViewer, "Тип", 130, Snapshot::type); //$NON-NLS-1$
        addColumn(snapshotsViewer, "Число свойств", 105, (Snapshot snapshot) -> //$NON-NLS-1$
            Integer.toString(snapshot.properties().size()));
        addColumn(snapshotsViewer, "Размер", 80, (Snapshot snapshot) -> //$NON-NLS-1$
            Long.toString(snapshot.characterCount()));
        TableColumn moduleColumn = addColumn(snapshotsViewer, "Модуль", 225, Snapshot::moduleName); //$NON-NLS-1$
        TableColumn methodColumn = addColumn(snapshotsViewer, "Метод", 150, Snapshot::methodName); //$NON-NLS-1$
        TableColumn lineColumn = addColumn(snapshotsViewer, "Строка", 65, Snapshot::lineNumber); //$NON-NLS-1$
        addColumn(snapshotsViewer, "Проект", 110, Snapshot::project); //$NON-NLS-1$
        initSnapshotsInteraction(List.of(moduleColumn, methodColumn, lineColumn));

        details = new Composite(panes, SWT.NONE);
        detailsLayout = new StackLayout();
        details.setLayout(detailsLayout);
        comparisonPane = new Composite(details, SWT.NONE);
        GridLayout layout = new GridLayout();
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        comparisonPane.setLayout(layout);
        hideEqual = new Button(comparisonPane, SWT.CHECK);
        hideEqual.setText("Скрыть равные"); //$NON-NLS-1$
        hideEqual.setSelection(true);
        hideEqual.addListener(SWT.Selection, event -> updateDetails());
        comparisonViewer = createTable(comparisonPane, SWT.SINGLE);
        comparisonViewer.getTable().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        addColumn(comparisonViewer, "Свойство", 155, Difference::name); //$NON-NLS-1$
        addColumn(comparisonViewer, "Статус", 90, Difference::status); //$NON-NLS-1$
        addColumn(comparisonViewer, "Объект 1 · Значение", 165, (Difference row) -> valueOf(row.before())); //$NON-NLS-1$
        addColumn(comparisonViewer, "Объект 1 · Тип", 125, (Difference row) -> typeOf(row.before())); //$NON-NLS-1$
        addColumn(comparisonViewer, "Объект 2 · Значение", 165, (Difference row) -> valueOf(row.after())); //$NON-NLS-1$
        addColumn(comparisonViewer, "Объект 2 · Тип", 125, (Difference row) -> typeOf(row.after())); //$NON-NLS-1$
        new FormTableInteraction(comparisonViewer.getTable(), comparisonViewer).install();
        comparisonViewer.addDoubleClickListener(event ->
        {
            if (comparisonViewer.getStructuredSelection().getFirstElement() instanceof Difference row
                && row.isChanged())
                openPropertyComparison(row);
        });
        panes.setWeights(1, 1);
        snapshotsViewer.addSelectionChangedListener(event -> updateDetails());
        getSite().setSelectionProvider(snapshotsViewer);
        reload(List.of());
    }

    private void initSnapshotsInteraction(List<TableColumn> sourceColumns)
    {
        Table table = snapshotsViewer.getTable();
        FormTableInteraction interaction = new FormTableInteraction(table, snapshotsViewer);
        interaction.setExternalMenuPopulation(true);
        interaction.install();
        Menu menu = new Menu(table);
        table.setMenu(menu);
        table.addListener(SWT.KeyDown, event ->
        {
            if (event.keyCode == SWT.DEL && (event.stateMask & SWT.MODIFIER_MASK) == 0)
            {
                event.doit = false;
                delete(selectedSnapshots());
            }
        });
        menu.addListener(SWT.Show, event ->
        {
            for (MenuItem item : menu.getItems())
                item.dispose();
            List<Snapshot> selected = selectedSnapshots();
            Snapshot snapshot = selected.size() == 1 ? selected.get(0) : null;
            MenuItem rename = new MenuItem(menu, SWT.PUSH);
            rename.setText("Переименовать"); //$NON-NLS-1$
            rename.setEnabled(snapshot != null);
            rename.addListener(SWT.Selection, e -> rename(snapshot));
            MenuItem delete = new MenuItem(menu, SWT.PUSH);
            delete.setText("Удалить\tDelete"); //$NON-NLS-1$
            delete.setEnabled(!selected.isEmpty());
            delete.addListener(SWT.Selection, e -> delete(selected));
            MenuItem source = new MenuItem(menu, SWT.PUSH);
            source.setText("Перейти к строке модуля"); //$NON-NLS-1$
            boolean hasSource = snapshot != null && snapshot.location() != null;
            source.setEnabled(hasSource);
            source.addListener(SWT.Selection, e -> openSource(snapshot));
            MenuItem copyRef = new MenuItem(menu, SWT.PUSH);
            copyRef.setText("Копировать ссылку"); //$NON-NLS-1$
            copyRef.setEnabled(hasSource);
            copyRef.addListener(SWT.Selection, e ->
                GetRef.setClipboardText(snapshot.location().reference(), getSite().getShell()));
            new MenuItem(menu, SWT.SEPARATOR);
            interaction.populateFilterMenuItems(menu);
        });
        table.addListener(SWT.MouseDoubleClick, event ->
        {
            Point point = new Point(event.x, event.y);
            TableItem item = table.getItem(point);
            if (item != null && item.getData() instanceof Snapshot snapshot && snapshot.location() != null
                && sourceColumns.stream().anyMatch(column -> item.getBounds(table.indexOf(column)).contains(point)))
                openSource(snapshot);
        });
    }

    private void reload(List<String> ids)
    {
        Global.tempLog(TEMP_LOG, "reload ids=" + ids + " from=" + caller()); //$NON-NLS-1$
        try
        {
            snapshots = Store.read();
            List<Snapshot> newestFirst = new ArrayList<>(snapshots);
            Collections.reverse(newestFirst);
            snapshotsViewer.setInput(newestFirst);
            List<Snapshot> selected = newestFirst.stream().filter(s -> ids.contains(s.id())).toList();
            snapshotsViewer.setSelection(new StructuredSelection(selected), true);
            updateDetails();
        }
        catch (IOException e)
        {
            showError(e);
        }
    }

    private List<Snapshot> selectedSnapshots()
    {
        List<Snapshot> selected = new ArrayList<>();
        for (Object element : snapshotsViewer.getStructuredSelection().toList())
            selected.add((Snapshot) element);
        return selected;
    }

    private List<String> selectedIds()
    {
        return selectedSnapshots().stream().map(Snapshot::id).toList();
    }

    private void updateDetails()
    {
        List<Snapshot> selected = selectedSnapshots();
        if (selected.size() == 2)
        {
            List<Snapshot> chronological = selected.stream()
                .sorted(Comparator.comparingInt(snapshots::indexOf)).toList();
            comparisonViewer.setInput(compare(chronological.get(0), chronological.get(1), hideEqual.getSelection()));
            detailsLayout.topControl = comparisonPane;
            // В режиме сравнения таблицы свойств нет физически: ей нечем всплыть поверх сравнения.
            if (propertiesViewer != null)
            {
                rememberPropertiesColumns();
                propertiesViewer.getTable().dispose();
                propertiesViewer = null;
            }
        }
        else
        {
            if (propertiesViewer == null)
            {
                createPropertiesViewer();
                restorePropertiesColumns();
            }
            propertiesViewer.setInput(selected.size() == 1 ? selected.get(0).properties() : List.of());
            detailsLayout.topControl = propertiesViewer.getTable();
        }
        details.layout();
    }

    private void createPropertiesViewer()
    {
        propertiesViewer = createTable(details, SWT.SINGLE);
        addColumn(propertiesViewer, "Свойство", 170, Property::name); //$NON-NLS-1$
        addColumn(propertiesViewer, "Значение", 230, Property::value); //$NON-NLS-1$
        addColumn(propertiesViewer, "Тип", 160, Property::type); //$NON-NLS-1$
        new FormTableInteraction(propertiesViewer.getTable(), propertiesViewer).install();
    }

    /** Ширины и порядок колонок переживают уничтожение таблицы при переключении режимов. */
    private void rememberPropertiesColumns()
    {
        Table table = propertiesViewer.getTable();
        if (table.isDisposed())
            return;
        int count = table.getColumnCount();
        propertiesColumnWidths = new int[count];
        for (int i = 0; i < count; i++)
            propertiesColumnWidths[i] = table.getColumn(i).getWidth();
        propertiesColumnOrder = table.getColumnOrder();
    }

    private void restorePropertiesColumns()
    {
        if (propertiesColumnWidths == null || propertiesColumnOrder == null)
            return;
        Table table = propertiesViewer.getTable();
        for (int i = 0; i < propertiesColumnWidths.length && i < table.getColumnCount(); i++)
            table.getColumn(i).setWidth(propertiesColumnWidths[i]);
        table.setColumnOrder(propertiesColumnOrder);
    }

    private static List<Difference> compare(Snapshot first, Snapshot second, boolean hideEqual)
    {
        List<Difference> rows = new ArrayList<>();
        List<Property> before = first.properties();
        List<Property> after = second.properties();
        int left = 0;
        int right = 0;
        while (left < before.size() || right < after.size())
        {
            Property oldProperty = left < before.size() ? before.get(left) : null;
            Property newProperty = right < after.size() ? after.get(right) : null;
            int order = oldProperty == null ? 1 : newProperty == null ? -1
                : PROPERTY_ORDER.compare(oldProperty.name(), newProperty.name());
            if (order < 0)
            {
                rows.add(new Difference(oldProperty.name(), "Удалено", oldProperty, null)); //$NON-NLS-1$
                left++;
            }
            else if (order > 0)
            {
                rows.add(new Difference(newProperty.name(), "Добавлено", null, newProperty)); //$NON-NLS-1$
                right++;
            }
            else
            {
                boolean equal = oldProperty.equals(newProperty);
                if (!equal || !hideEqual)
                    rows.add(new Difference(oldProperty.name(), equal ? "Равно" : "Изменено", //$NON-NLS-1$ //$NON-NLS-2$
                        oldProperty, newProperty));
                left++;
                right++;
            }
        }
        return rows;
    }

    private void openPropertyComparison(Difference row)
    {
        Property before = row.before();
        Property after = row.after();
        try
        {
            CompareConfigMenuHook.openPlainTextComparison(before.value(), after.value(),
                comparisonSideLabel("Объект 1", before.type()), comparisonSideLabel("Объект 2", after.type()), //$NON-NLS-1$ //$NON-NLS-2$
                Global.withPluginWindowTitle("Сравнение свойства «" + row.name() + "»"), TEMP_LOG); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (RuntimeException e)
        {
            showError(e);
        }
    }

    private static String caller()
    {
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (StackTraceElement frame : new Exception().getStackTrace())
        {
            String name = frame.getClassName();
            if (name.equals(DebugObjectSnapshotsView.class.getName()) || name.startsWith("java."))
                continue;
            if (result.length() > 0)
                result.append(" <- "); //$NON-NLS-1$
            result.append(name).append('.').append(frame.getMethodName());
            if (++count >= 10)
                break;
        }
        return result.toString();
    }

    private static String comparisonSideLabel(String label, String type)
    {
        return type.isEmpty() ? label : label + " (" + type + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void rename(Snapshot snapshot)
    {
        InputDialog dialog = new InputDialog(getSite().getShell(), Global.withPluginWindowTitle("Переименовать снимок"), //$NON-NLS-1$
            "Имя снимка", snapshot.name(), name -> name.isBlank() ? "Введите имя снимка" : null); //$NON-NLS-1$ //$NON-NLS-2$
        if (dialog.open() == Window.OK)
        {
            String name = dialog.getValue().trim();
            updateStore(items -> items.replaceAll(item -> item.id().equals(snapshot.id())
                ? new Snapshot(item.id(), item.date(), name, item.type(), item.location(),
                    item.project(), item.properties())
                : item));
        }
    }

    private void delete(List<Snapshot> selected)
    {
        if (selected.isEmpty())
            return;
        if (!MessageDialog.openConfirm(getSite().getShell(), Global.withPluginWindowTitle("Удаление снимков"), //$NON-NLS-1$
            "Удалить выбранные снимки?")) //$NON-NLS-1$
            return;
        List<String> ids = selected.stream().map(Snapshot::id).toList();
        updateStore(items -> items.removeIf(item -> ids.contains(item.id())));
    }

    private static void updateStore(Consumer<List<Snapshot>> update)
    {
        try
        {
            Store.update(update);
            refreshOpenViews();
        }
        catch (IOException e)
        {
            showError(e);
        }
    }

    private void openSource(Snapshot snapshot)
    {
        String reference = snapshot.location().reference();
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(snapshot.project());
        if (!GoToDefinition.jump(reference, getSite().getShell(), getSite().getPage(), project, false)
            && !GoToDefinition.consumeJumpCancelled())
        {
            Global.tempLog(TEMP_LOG, "source jump failed project=" + snapshot.project() + " reference=" + reference); //$NON-NLS-1$ //$NON-NLS-2$
            ToastNotification.show(TITLE, "Не удалось перейти к строке модуля: " + reference); //$NON-NLS-1$
        }
    }

    @Override
    public void setFocus()
    {
        snapshotsViewer.getTable().setFocus();
    }

    private static TableViewer createTable(Composite parent, int selection)
    {
        TableViewer viewer = new TableViewer(parent, SWT.FULL_SELECTION | SWT.BORDER | selection);
        viewer.getTable().setHeaderVisible(true);
        viewer.getTable().setLinesVisible(true);
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        return viewer;
    }

    private static <T> TableColumn addColumn(TableViewer viewer, String label, int width, Function<T, String> text)
    {
        TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
        column.getColumn().setText(label);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            @SuppressWarnings("unchecked")
            public String getText(Object element)
            {
                return text.apply((T) element);
            }
        });
        return column.getColumn();
    }

    private static String valueOf(Property property)
    {
        return property != null ? property.value() : ""; //$NON-NLS-1$
    }

    private static String typeOf(Property property)
    {
        return property != null ? property.type() : ""; //$NON-NLS-1$
    }

    private static String text(String text)
    {
        return Objects.requireNonNullElse(text, ""); //$NON-NLS-1$
    }

    private static void showError(Exception error)
    {
        Global.tempLogException(TEMP_LOG, "view or storage failure", error); //$NON-NLS-1$
        ToastNotification.show(TITLE, error.getMessage());
    }

    @FunctionalInterface
    private interface TargetReader
    {
        Target read() throws DebugException;
    }

    private record Target(String name, IBslValue value, IBslStackFrame frame) {}

    private record Property(String name, String value, String type)
    {
        long characterCount()
        {
            return (long) characters(name) + characters(value) + characters(type);
        }

        private static int characters(String text)
        {
            return text.codePointCount(0, text.length());
        }
    }

    private record Difference(String name, String status, Property before, Property after)
    {
        boolean isChanged()
        {
            return before != null && after != null && !before.equals(after);
        }
    }

    private record Snapshot(String id, long date, String name, String type, GetRef.ModuleLineLocation location,
        String project, List<Property> properties)
    {
        long characterCount()
        {
            return properties.stream().mapToLong(Property::characterCount).sum();
        }

        String moduleName()
        {
            return location != null ? location.module() : ""; //$NON-NLS-1$
        }

        String methodName()
        {
            return location != null ? location.method() : ""; //$NON-NLS-1$
        }

        String lineNumber()
        {
            return location != null ? Integer.toString(location.line()) : ""; //$NON-NLS-1$
        }
    }

    private static final class Store
    {
        private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        private static List<Snapshot> entries;

        static synchronized List<Snapshot> read() throws IOException
        {
            if (entries == null)
            {
                Path file = file();
                entries = Files.notExists(file) ? List.of() : readSnapshots(file);
            }
            return entries;
        }

        private static List<Snapshot> readSnapshots(Path file) throws IOException
        {
            List<Snapshot> snapshots = new ArrayList<>();
            for (JsonNode node : JSON.readValue(file.toFile(), JsonNode[].class))
            {
                Snapshot snapshot = JSON.treeToValue(node, Snapshot.class);
                if (!node.has("location")) //$NON-NLS-1$
                {
                    IFile moduleFile = workspaceFile(node.path("path").asText()); //$NON-NLS-1$
                    GetRef.ModuleLineLocation location =
                        GetRef.moduleLineLocation(moduleFile, null, node.path("line").asInt()); //$NON-NLS-1$
                    snapshot = new Snapshot(snapshot.id(), snapshot.date(), snapshot.name(), snapshot.type(),
                        location, snapshot.project(), snapshot.properties());
                }
                snapshots.add(snapshot);
            }
            return List.copyOf(snapshots);
        }

        static synchronized Snapshot append(Snapshot snapshot) throws IOException
        {
            List<Snapshot> current = read();
            Snapshot previous = current.isEmpty() ? null : current.get(current.size() - 1);
            update(items -> items.add(snapshot));
            return previous;
        }

        static synchronized void update(Consumer<List<Snapshot>> update) throws IOException
        {
            List<Snapshot> replacement = new ArrayList<>(read());
            update.accept(replacement);
            Path file = file();
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "debug-object-snapshots-", ".json"); //$NON-NLS-1$ //$NON-NLS-2$
            try
            {
                JSON.writeValue(temporary.toFile(), replacement);
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                entries = List.copyOf(replacement);
            }
            finally
            {
                Files.deleteIfExists(temporary);
            }
        }

        private static Path file()
        {
            return Activator.getDefault().getStateLocation().append("debug-object-snapshots.json").toFile().toPath(); //$NON-NLS-1$
        }
    }
}
