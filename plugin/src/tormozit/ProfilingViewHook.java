package tormozit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.descriptor.basic.MPartDescriptor;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.profiling.core.ILineProfilingResult;
import com._1c.g5.v8.dt.profiling.core.IProfilingResult;
import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;
import com._1c.g5.v8.dt.debug.core.model.BslModuleReference;
import com._1c.g5.v8.dt.debug.core.model.IBslModuleLocator;
import com._1c.g5.v8.dt.bsl.model.Module;

/** Доработки штатной панели «Замер производительности» (issue 585). */
public final class ProfilingViewHook implements IStartup
{
    private static final String VIEW_ID =
            "com._1c.g5.v8.dt.profiling.ui.view.ProfilingView"; //$NON-NLS-1$
    private static final String VIEW_CLASS =
            "com._1c.g5.v8.dt.internal.profiling.ui.view.ProfilingView"; //$NON-NLS-1$
    private static final String TITLE = "Замеры производительности"; //$NON-NLS-1$
    private static final String INSTALLED_KEY = "tormozit.comfort.profiling.installed"; //$NON-NLS-1$
    private static volatile boolean installed;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(ProfilingViewHook::install);
    }

    private static void install()
    {
        if (installed)
            return;
        installed = true;
        IWorkbench workbench = PlatformUI.getWorkbench();
        renameDescriptor(workbench);
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            hookWindow(window);
        workbench.addWindowListener(new IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override
            public void windowActivated(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override
            public void windowDeactivated(IWorkbenchWindow window)
            {
            }

            @Override
            public void windowClosed(IWorkbenchWindow window)
            {
            }
        });
    }

    private static void renameDescriptor(IWorkbench workbench)
    {
        Object application = workbench.getService(MApplication.class);
        if (!(application instanceof MApplication model))
            return;
        for (MPartDescriptor descriptor : model.getDescriptors())
        {
            if (VIEW_ID.equals(descriptor.getElementId()))
                descriptor.setLabel(TITLE);
        }
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null || window.getShell() == null || window.getShell().isDisposed())
            return;
        for (IWorkbenchPage page : window.getPages())
        {
            for (IViewReference ref : page.getViewReferences())
                enhance(ref.getView(false));
        }
        if (Boolean.TRUE.equals(window.getShell().getData(ProfilingViewHook.class.getName())))
            return;
        window.getShell().setData(ProfilingViewHook.class.getName(), Boolean.TRUE);
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                enhance(ref.getPart(false));
            }

            @Override
            public void partVisible(IWorkbenchPartReference ref)
            {
                enhance(ref.getPart(false));
            }
        });
    }

    private static void enhance(Object part)
    {
        if (!(part instanceof IViewPart view) || !VIEW_CLASS.equals(view.getClass().getName()))
            return;
        Object model = view.getSite().getService(MPart.class);
        if (model instanceof MPart mpart && !TITLE.equals(mpart.getLabel()))
            mpart.setLabel(TITLE);
        try
        {
            List<?> parts = (List<?>) view.getClass().getMethod("getParts").invoke(view); //$NON-NLS-1$
            if (parts.isEmpty())
                return;
            Object tablePart = parts.get(0);
            TableViewer viewer = (TableViewer) field(tablePart, "resultsTable"); //$NON-NLS-1$
            Table table = viewer.getTable();
            if (Boolean.TRUE.equals(table.getData(INSTALLED_KEY)))
                return;
            Object comparator = field(tablePart, "tableComparator"); //$NON-NLS-1$
            Method setColumn = comparator.getClass().getMethod("setColumn", int.class); //$NON-NLS-1$
            setColumn.invoke(comparator, 5);
            table.setSortColumn(table.getColumn(5));
            table.setSortDirection(SWT.DOWN);
            addCalculatedColumns((org.eclipse.swt.widgets.Composite) tablePart, viewer, comparator);
            installColumnWidths(view, (Composite) tablePart, viewer);
            installMethodCalculation(view, tablePart, viewer);
            installResultState(view, tablePart, viewer, comparator);
            installSmartFilter(view, tablePart, viewer);
            installSelectionFooter(view, tablePart, viewer);
            installDoubleClickMessage(viewer);
            viewer.refresh();
            table.setData(INSTALLED_KEY, Boolean.TRUE);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            Global.tempLog("profiling-view", "Установка доработок панели: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void addCalculatedColumns(org.eclipse.swt.widgets.Composite tablePart,
            TableViewer viewer, Object stockComparator) throws ReflectiveOperationException
    {
        TableColumnLayout layout = (TableColumnLayout) tablePart.getLayout();
        TableViewerColumn method = new TableViewerColumn(viewer, SWT.NONE);
        TableColumn methodColumn = method.getColumn();
        methodColumn.setText("Метод"); //$NON-NLS-1$
        methodColumn.setToolTipText(TooltipText.wrap(tablePart,
                "Метод строки модуля" + Global.pluginSignForTooltip())); //$NON-NLS-1$
        layout.setColumnData(methodColumn, new ColumnWeightData(80, 80, true));
        method.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                @SuppressWarnings("unchecked")
                Map<Object, String> names = (Map<Object, String>) viewer.getTable().getData("profiling.methodNames"); //$NON-NLS-1$
                return names != null ? names.getOrDefault(element, "") : ""; //$NON-NLS-1$ //$NON-NLS-2$
            }
        });

        TableViewerColumn average = new TableViewerColumn(viewer, SWT.RIGHT);
        TableColumn averageColumn = average.getColumn();
        averageColumn.setText("Среднее время"); //$NON-NLS-1$
        averageColumn.setToolTipText(TooltipText.wrap(tablePart,
                "Чистое время строки, делённое на количество вызовов" //$NON-NLS-1$
                        + Global.pluginSignForTooltip()));
        layout.setColumnData(averageColumn, new ColumnWeightData(55, 70, true));
        average.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                @SuppressWarnings("unchecked")
                Map<Object, String> values = (Map<Object, String>) viewer.getTable()
                        .getData("profiling.averageTimes"); //$NON-NLS-1$
                return values != null ? values.getOrDefault(element, "") : ""; //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
        TableViewerColumn actuality = new TableViewerColumn(viewer, SWT.NONE);
        TableColumn actualityColumn = actuality.getColumn();
        actualityColumn.setText(""); //$NON-NLS-1$
        actualityColumn.setToolTipText(TooltipText.wrap(tablePart,
                "Актуальность текста строки модуля" + Global.pluginSignForTooltip())); //$NON-NLS-1$
        layout.setColumnData(actualityColumn, new ColumnPixelData(24, false, false));
        actuality.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return ""; //$NON-NLS-1$
            }

            @Override
            public String getToolTipText(Object element)
            {
                Actuality state = actuality(viewer, element);
                String text = state == Actuality.MISSING ? "Модуль не найден в проекте замера" //$NON-NLS-1$
                        : state == Actuality.CHANGED ? "Текст строки модуля изменился после замера" //$NON-NLS-1$
                                : state == Actuality.CURRENT ? "Текст строки соответствует замеру" : ""; //$NON-NLS-1$ //$NON-NLS-2$
                return text.isEmpty() ? null : TooltipText.wrap(viewer.getTable(),
                        text + Global.pluginSignForTooltip());
            }
        });
        Table table = viewer.getTable();
        table.addListener(SWT.PaintItem, event ->
        {
            if (event.index != 12 || !(event.item instanceof TableItem item))
                return;
            Image icon = actualityImage(actuality(viewer, item.getData()));
            if (icon == null)
                return;
            org.eclipse.swt.graphics.Rectangle bounds = icon.getBounds();
            event.gc.drawImage(icon, event.x + Math.max(0, (event.width - bounds.width) / 2),
                    event.y + Math.max(0, (event.height - bounds.height) / 2));
        });
        Global.tempLog("profiling-view", "Значки статуса рисуются только в своей колонке"); //$NON-NLS-1$ //$NON-NLS-2$
        int[] moved = new int[table.getColumnCount()];
        moved[0] = 12;
        moved[1] = 0;
        moved[2] = 10;
        for (int i = 1; i < 10; i++)
            moved[i + 2] = i;
        moved[12] = 11;
        table.setColumnOrder(moved);
        Field sortField = fieldHandle(stockComparator, "propertyIndex"); //$NON-NLS-1$
        Field directionField = fieldHandle(stockComparator, "descending"); //$NON-NLS-1$
        ViewerComparator extended = new ViewerComparator()
        {
            @Override
            public int compare(Viewer source, Object left, Object right)
            {
                try
                {
                    int index = sortField.getInt(stockComparator);
                    if (index == 10)
                    {
                        String a = lines(left).length > 0 ? methodName(lines(left)[0]) : ""; //$NON-NLS-1$
                        String b = lines(right).length > 0 ? methodName(lines(right)[0]) : ""; //$NON-NLS-1$
                        int result = String.CASE_INSENSITIVE_ORDER.compare(a, b);
                        return directionField.getBoolean(stockComparator) ? -result : result;
                    }
                    if (index == 11)
                    {
                        int result = Double.compare(averageNanos(lines(left)),
                                averageNanos(lines(right)));
                        return directionField.getBoolean(stockComparator) ? -result : result;
                    }
                    return ((ViewerComparator) stockComparator).compare(source, left, right);
                }
                catch (ReflectiveOperationException e)
                {
                    Global.tempLog("profiling-view", "Сортировка замера: " + e); //$NON-NLS-1$ //$NON-NLS-2$
                    return 0;
                }
            }
        };
        viewer.setComparator(extended);
        for (int index : new int[] { 10, 11 })
        {
            TableColumn column = table.getColumn(index);
            column.addListener(SWT.Selection, e ->
            {
                try
                {
                    stockComparator.getClass().getMethod("setColumn", int.class) //$NON-NLS-1$
                            .invoke(stockComparator, index);
                    table.setSortColumn(column);
                    table.setSortDirection(directionField.getBoolean(stockComparator)
                            ? SWT.DOWN : SWT.UP);
                    viewer.refresh();
                }
                catch (ReflectiveOperationException ex)
                {
                    Global.tempLog("profiling-view", "Сортировка колонки: " + ex); //$NON-NLS-1$ //$NON-NLS-2$
                }
            });
        }
        tablePart.layout(true, true);
    }

    private static double averageNanos(ILineProfilingResult[] values)
    {
        long calls = 0;
        long nanos = 0;
        for (ILineProfilingResult line : values)
        {
            calls += line.getFrequency();
            nanos += line.getPureTimeForDebugTarget(line.getDebugTargetType()).toNanos();
        }
        return calls > 0 ? (double) nanos / calls : 0;
    }

    private static String methodName(ILineProfilingResult line)
    {
        String signature = line.getMethodSignature();
        if (signature == null)
            return ""; //$NON-NLS-1$
        int bracket = signature.indexOf('(');
        return (bracket >= 0 ? signature.substring(0, bracket) : signature).trim();
    }

    private static void installColumnWidths(IViewPart view, Composite tablePart, TableViewer viewer)
            throws ReflectiveOperationException
    {
        Table table = viewer.getTable();
        IDialogSettings root = Activator.getDefault().getDialogSettings();
        IDialogSettings settings = root.getSection("profiling-view-columns"); //$NON-NLS-1$
        if (settings == null)
            settings = root.addNewSection("profiling-view-columns"); //$NON-NLS-1$
        IDialogSettings saved = settings;
        boolean[] ready = { false };
        Runnable apply = () ->
        {
            if (table.isDisposed())
                return;
            ready[0] = false;
            TableColumnLayout layout = (TableColumnLayout) tablePart.getLayout();
            for (int i = 0; i < table.getColumnCount(); i++)
            {
                // EDT скрывает колонки типов целей нулевой шириной для одиночной цели.
                if (i == 12 || (i >= 6 && i <= 9 && table.getColumn(i).getWidth() == 0))
                    continue;
                String value = saved.get("width." + i); //$NON-NLS-1$
                if (value == null)
                    continue;
                try
                {
                    int width = Integer.parseInt(value);
                    if (width > 0)
                        layout.setColumnData(table.getColumn(i),
                                new ColumnPixelData(width, true, false));
                }
                catch (NumberFormatException e)
                {
                    Global.tempLog("profiling-view", "Ширина колонки: " + e); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            tablePart.layout(true, true);
            ready[0] = true;
        };
        for (int i = 0; i < table.getColumnCount(); i++)
        {
            int index = i;
            table.getColumn(i).addListener(SWT.Resize, e ->
            {
                if (ready[0] && !table.isDisposed() && index != 12)
                {
                    int width = table.getColumn(index).getWidth();
                    if (width > 0)
                        saved.put("width." + index, width); //$NON-NLS-1$
                }
            });
        }
        apply.run();
        table.setData("profiling.restoreWidths", apply); //$NON-NLS-1$
        Object controls = view.getClass().getMethod("getControlsPanel").invoke(view); //$NON-NLS-1$
        Object dateSelector = field(controls, "dateSelector"); //$NON-NLS-1$
        if (dateSelector instanceof org.eclipse.jface.viewers.ComboViewer combo)
        {
            org.eclipse.swt.widgets.Listener beforeSelection = e ->
            {
                if (e.widget == combo.getCombo())
                    ready[0] = false;
            };
            table.getDisplay().addFilter(SWT.Selection, beforeSelection);
            table.addListener(SWT.Dispose,
                    e -> table.getDisplay().removeFilter(SWT.Selection, beforeSelection));
        }
    }

    private static Actuality actuality(TableViewer viewer, Object row)
    {
        @SuppressWarnings("unchecked")
        Map<Object, Actuality> states = (Map<Object, Actuality>) viewer.getTable()
                .getData("profiling.actuality"); //$NON-NLS-1$
        return states != null ? states.get(row) : null;
    }

    private static Image actualityImage(Actuality state)
    {
        if (state == Actuality.MISSING)
            return PlatformUI.getWorkbench().getSharedImages()
                    .getImage(ISharedImages.IMG_OBJS_ERROR_TSK);
        if (state == Actuality.CHANGED)
            return PlatformUI.getWorkbench().getSharedImages()
                    .getImage(ISharedImages.IMG_OBJS_WARN_TSK);
        if (state == Actuality.CURRENT)
            return PlatformUI.getWorkbench().getSharedImages()
                    .getImage(ISharedImages.IMG_OBJS_INFO_TSK);
        return null;
    }

    private static ILineProfilingResult[] lines(Object element)
    {
        return element instanceof ILineProfilingResult[] lines ? lines : new ILineProfilingResult[0];
    }

    private static void installMethodCalculation(IViewPart view, Object tablePart, TableViewer viewer)
    {
        Table table = viewer.getTable();
        IBslModuleLocator locator;
        try
        {
            locator = (IBslModuleLocator) field(tablePart, "locator"); //$NON-NLS-1$
        }
        catch (ReflectiveOperationException | ClassCastException e)
        {
            Global.tempLog("profiling-view", "Поиск модулей замера: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            locator = null;
        }
        IBslModuleLocator moduleLocator = locator;
        Runnable update = () -> calculateMethods(view, viewer, moduleLocator);
        table.setData("profiling.recalculate", update); //$NON-NLS-1$
        table.addListener(SWT.Show, e -> table.getDisplay().asyncExec(update));
        boolean[] visibleUpdatePending = { false };
        table.addListener(SWT.Paint, e ->
        {
            if (visibleUpdatePending[0])
                return;
            visibleUpdatePending[0] = true;
            table.getDisplay().asyncExec(() ->
            {
                visibleUpdatePending[0] = false;
                updateVisibleRows(viewer);
            });
        });
        calculateMethods(view, viewer, moduleLocator);
    }

    private static void calculateMethods(IViewPart view, TableViewer viewer,
            IBslModuleLocator locator)
    {
        Table table = viewer.getTable();
        Object sourceInput = table.getData("profiling.sourceInput"); //$NON-NLS-1$
        if (sourceInput == null)
            sourceInput = viewer.getInput();
        if (table.isDisposed() || !(sourceInput instanceof Collection<?> source))
            return;
        IProfilingResult result = selectedResult(view);
        Object input = sourceInput;
        List<Object> rows = new ArrayList<>(source);
        Object generation = new Object();
        table.setData("profiling.methodGeneration", generation); //$NON-NLS-1$
        Job job = new Job("Методы замера производительности") //$NON-NLS-1$
        {
            @Override
            protected org.eclipse.core.runtime.IStatus run(IProgressMonitor monitor)
            {
                Map<Object, String> names = new IdentityHashMap<>();
                Map<Object, Actuality> actuality = new IdentityHashMap<>();
                Map<BslModuleReference, IFile> moduleFiles = new HashMap<>();
                Map<IFile, List<String>> fileLines = new HashMap<>();
                for (Object row : rows)
                {
                    if (monitor.isCanceled())
                        return org.eclipse.core.runtime.Status.CANCEL_STATUS;
                    ILineProfilingResult[] values = lines(row);
                    if (values.length > 0)
                    {
                        names.put(row, methodName(values[0]));
                        actuality.put(row, checkActuality(locator, values[0], moduleFiles,
                                fileLines));
                    }
                }
                if (result != null && result.getTotalDurability() > 0
                        && result.getTotalPureDurability() > 0)
                {
                    double full = result.getTotalDurability();
                    double clean = result.getTotalPureDurability();
                    for (ILineProfilingResult line : result.getProfilingResults())
                        line.setTotalDurabilities(full, clean);
                }
                table.getDisplay().asyncExec(() ->
                {
                    if (!table.isDisposed() && table.getData("profiling.sourceInput") == input //$NON-NLS-1$
                            && table.getData("profiling.methodGeneration") == generation) //$NON-NLS-1$
                    {
                        table.setData("profiling.methodNames", names); //$NON-NLS-1$
                        table.setData("profiling.actuality", actuality); //$NON-NLS-1$
                        table.setData("profiling.labelsGeneration", generation); //$NON-NLS-1$
                        updateVisibleRows(viewer);
                        restoreSelectedRow(table);
                    }
                });
                return org.eclipse.core.runtime.Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    private static void updateVisibleRows(TableViewer viewer)
    {
        Table table = viewer.getTable();
        if (table.isDisposed())
            return;
        Object generation = table.getData("profiling.labelsGeneration"); //$NON-NLS-1$
        if (generation == null)
            return;
        int top = table.getTopIndex();
        int visible = table.getClientArea().height / Math.max(1, table.getItemHeight()) + 2;
        int end = Math.min(table.getItemCount(), top + visible);
        for (int i = top; i < end; i++)
        {
            TableItem item = table.getItem(i);
            if (item.getData("profiling.labelsGeneration") == generation) //$NON-NLS-1$
                continue;
            item.setData("profiling.labelsGeneration", generation); //$NON-NLS-1$
            if (item.getData() != null)
                viewer.update(item.getData(), null);
        }
    }

    private static Actuality checkActuality(IBslModuleLocator locator, ILineProfilingResult line,
            Map<BslModuleReference, IFile> moduleFiles, Map<IFile, List<String>> fileLines)
    {
        if (locator == null || line.getProject() == null || line.getModuleID() == null)
            return Actuality.MISSING;
        try
        {
            BslModuleReference reference = new BslModuleReference(
                    line.getModuleID().getObjectID(), line.getModuleID().getPropertyID(),
                    line.getProject());
            IFile file;
            if (moduleFiles.containsKey(reference))
                file = moduleFiles.get(reference);
            else
            {
                Module module = locator.getModule(reference, false);
                file = moduleToFile(module);
                moduleFiles.put(reference, file);
            }
            if (file == null || !file.exists())
                return Actuality.MISSING;
            List<String> source = fileLines.get(file);
            if (source == null)
            {
                source = readLines(file);
                fileLines.put(file, source);
            }
            int index = line.getLineNo() - 1;
            String actual = index >= 0 && index < source.size() ? source.get(index) : null;
            if (actual == null)
                return Actuality.CHANGED;
            return actual.equals(line.getLine().strip()) ? Actuality.CURRENT : Actuality.CHANGED;
        }
        catch (Exception e)
        {
            Global.tempLog("profiling-view", "Проверка строки замера: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return Actuality.MISSING;
        }
    }

    private static IFile moduleToFile(Module module)
    {
        if (module == null)
            return null;
        URI uri = EcoreUtil.getURI(module);
        if (uri == null || !uri.trimFragment().isPlatformResource())
            return null;
        String path = uri.trimFragment().toPlatformString(true);
        return path != null ? ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(path)) : null;
    }

    private static List<String> readLines(IFile file) throws Exception
    {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getContents(true), StandardCharsets.UTF_8)))
        {
            String text;
            while ((text = reader.readLine()) != null)
                lines.add(text.strip());
        }
        return lines;
    }

    private static void installDoubleClickMessage(TableViewer viewer)
    {
        viewer.addDoubleClickListener(e ->
        {
            if (!(e.getSelection() instanceof IStructuredSelection selection))
                return;
            Actuality state = actuality(viewer, selection.getFirstElement());
            if (state == Actuality.MISSING)
                ToastNotification.show("Замеры производительности", //$NON-NLS-1$
                        "Модуль не найден в проекте замера", 5_000); //$NON-NLS-1$
            else if (state == Actuality.CHANGED)
                ToastNotification.show("Замеры производительности", //$NON-NLS-1$
                        "Текст строки модуля изменился после замера", 5_000); //$NON-NLS-1$
        });
    }

    private enum Actuality
    {
        CURRENT,
        CHANGED,
        MISSING
    }

    private static IProfilingResult selectedResult(IViewPart view)
    {
        try
        {
            Object result = view.getClass().getMethod("getSelectedResult").invoke(view); //$NON-NLS-1$
            return result instanceof IProfilingResult profiling ? profiling : null;
        }
        catch (ReflectiveOperationException e)
        {
            Global.tempLog("profiling-view", "Текущий замер: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    private static void installResultState(IViewPart view, Object tablePart, TableViewer viewer,
            Object comparator) throws ReflectiveOperationException
    {
        Table table = viewer.getTable();
        ResultState state = new ResultState();
        IProfilingResult selected = selectedResult(view);
        state.currentId = selected != null ? selected.getUuid() : null;
        if (state.currentId != null)
            state.byResult.put(state.currentId, RowState.defaults());
        table.addListener(SWT.Selection, e -> remember(view, viewer, state));
        for (int i = 0; i < 12; i++)
        {
            TableColumn column = table.getColumn(i);
            column.addListener(SWT.Selection,
                    e -> table.getDisplay().asyncExec(() -> remember(view, viewer, state)));
        }
        Object[] observedInput = { viewer.getInput() };
        table.addListener(SWT.Paint, e ->
        {
            if (table.isDisposed() || viewer.getInput() == observedInput[0])
                return;
            observedInput[0] = viewer.getInput();
            if (table.getData("profiling.filterInput") == observedInput[0]) //$NON-NLS-1$
                return;
            table.setData("profiling.sourceInput", observedInput[0]); //$NON-NLS-1$
            table.getDisplay().asyncExec(() ->
            {
                if (table.isDisposed())
                    return;
                Object restoreWidths = table.getData("profiling.restoreWidths"); //$NON-NLS-1$
                if (restoreWidths instanceof Runnable widths)
                    widths.run();
                restoreResultState(view, viewer, comparator, state);
                Object recalculate = table.getData("profiling.recalculate"); //$NON-NLS-1$
                if (recalculate instanceof Runnable calculation)
                    calculation.run();
                Object refilter = table.getData("profiling.refilter"); //$NON-NLS-1$
                if (refilter instanceof Runnable filtering)
                    filtering.run();
            });
        });
        table.setData("profiling.restoreSelection", (Runnable) () -> //$NON-NLS-1$
        {
            IProfilingResult current = selectedResult(view);
            if (current == null || state.currentId == null
                    || !state.currentId.equals(current.getUuid()))
                return;
            RowState row = state.byResult.get(state.currentId);
            if (row != null && row.rowKey != null)
                Global.tempLog("profiling-filter", "Восстановление строки: ключ=" //$NON-NLS-1$ //$NON-NLS-2$
                        + row.rowKey.hashCode() + ", найдена=" //$NON-NLS-1$
                        + selectRow(viewer, row.rowKey));
            else
                Global.tempLog("profiling-filter", "Восстановление строки: ключ отсутствует"); //$NON-NLS-1$ //$NON-NLS-2$
        });
        for (String name : new String[] { "groupModules", "showClientAction", //$NON-NLS-1$ //$NON-NLS-2$
                "showServerAction", "showMobileManagedServerAction" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Object action = field(view, name);
            if (action instanceof IAction checked)
                checked.addPropertyChangeListener(e -> rememberModes(view, state));
        }
    }

    private static void remember(IViewPart view, TableViewer viewer, ResultState state)
    {
        Table table = viewer.getTable();
        IProfilingResult selected = selectedResult(view);
        if (table.isDisposed() || state.restoring || state.currentId == null || selected == null
                || !state.currentId.equals(selected.getUuid()))
            return;
        RowState row = state.byResult.computeIfAbsent(state.currentId, id -> RowState.defaults());
        TableColumn sort = table.getSortColumn();
        if (sort != null)
            row.sortIndex = table.indexOf(sort);
        row.descending = table.getSortDirection() == SWT.DOWN;
        if (viewer.getSelection() instanceof IStructuredSelection selection && !selection.isEmpty())
        {
            row.rowKey = key(selection.getFirstElement());
            Global.tempLog("profiling-filter", "Запомнена строка: ключ=" //$NON-NLS-1$ //$NON-NLS-2$
                    + (row.rowKey != null ? row.rowKey.hashCode() : 0));
            TableItem[] selectedItems = table.getSelection();
            if (selectedItems.length > 0)
                Global.tempLog("profiling-view", "Границы выбранной строки: чистое=" //$NON-NLS-1$ //$NON-NLS-2$
                        + selectedItems[0].getBounds(5) + ", среднее=" //$NON-NLS-1$
                        + selectedItems[0].getBounds(11) + ", ширины=" //$NON-NLS-1$
                        + table.getColumn(5).getWidth() + "/" //$NON-NLS-1$
                        + table.getColumn(11).getWidth());
        }
    }

    private static void rememberModes(IViewPart view, ResultState state)
    {
        if (state.restoring || state.currentId == null)
            return;
        RowState row = state.byResult.computeIfAbsent(state.currentId, id -> RowState.defaults());
        row.modes = modes(view);
    }

    private static void restoreResultState(IViewPart view, TableViewer viewer, Object comparator,
            ResultState state)
    {
        Table table = viewer.getTable();
        if (table.isDisposed())
            return;
        IProfilingResult result = selectedResult(view);
        state.currentId = result != null ? result.getUuid() : null;
        if (state.currentId == null)
            return;
        RowState saved = state.byResult.computeIfAbsent(state.currentId, id -> RowState.defaults());
        state.restoring = true;
        try
        {
            int sortIndex = Math.max(0, Math.min(11, saved.sortIndex));
            setField(comparator, "propertyIndex", sortIndex); //$NON-NLS-1$
            setField(comparator, "descending", saved.descending); //$NON-NLS-1$
            table.setSortColumn(table.getColumn(sortIndex));
            table.setSortDirection(saved.descending ? SWT.DOWN : SWT.UP);
            applyModes(view, saved.modes);
            viewer.refresh();
            if (saved.rowKey != null)
                selectRow(viewer, saved.rowKey);
        }
        catch (ReflectiveOperationException e)
        {
            Global.tempLog("profiling-view", "Восстановление состояния замера: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            state.restoring = false;
        }
    }

    private static String key(Object row)
    {
        ILineProfilingResult[] values = lines(row);
        if (values.length == 0)
            return null;
        ILineProfilingResult first = values[0];
        return first.getModuleName() + '\n' + first.getLineNo() + '\n' + first.getLine();
    }

    private static boolean selectRow(TableViewer viewer, String rowKey)
    {
        for (TableItem item : viewer.getTable().getItems())
        {
            if (rowKey.equals(key(item.getData())))
            {
                viewer.setSelection(new StructuredSelection(item.getData()), true);
                return true;
            }
        }
        return false;
    }

    private static void restoreSelectedRow(Table table)
    {
        Object action = table.getData("profiling.restoreSelection"); //$NON-NLS-1$
        if (action instanceof Runnable restore)
            restore.run();
    }

    private static boolean[] modes(IViewPart view)
    {
        String[] names = { "groupModules", "showClientAction", "showServerAction", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "showMobileManagedServerAction" }; //$NON-NLS-1$
        boolean[] checked = new boolean[names.length];
        for (int i = 0; i < names.length; i++)
        {
            try
            {
                checked[i] = ((IAction) field(view, names[i])).isChecked();
            }
            catch (ReflectiveOperationException | ClassCastException e)
            {
                Global.tempLog("profiling-view", "Режим замера: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return checked;
    }

    private static void applyModes(IViewPart view, boolean[] checked) throws ReflectiveOperationException
    {
        if (checked == null)
            return;
        String[] names = { "groupModules", "showClientAction", "showServerAction", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "showMobileManagedServerAction" }; //$NON-NLS-1$
        for (int i = 0; i < names.length; i++)
        {
            IAction action = (IAction) field(view, names[i]);
            if (action.isChecked() != checked[i])
            {
                action.setChecked(checked[i]);
                action.run();
            }
        }
    }

    private static void setField(Object owner, String name, Object value)
            throws ReflectiveOperationException
    {
        Class<?> type = owner.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(owner, value);
                return;
            }
            catch (NoSuchFieldException e)
            {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static final class ResultState
    {
        private final Map<UUID, RowState> byResult = new java.util.HashMap<>();
        private UUID currentId;
        private boolean restoring;
    }

    private static final class RowState
    {
        private int sortIndex = 5;
        private boolean descending = true;
        private String rowKey;
        private boolean[] modes;

        private static RowState defaults()
        {
            return new RowState();
        }
    }

    private static void installSmartFilter(IViewPart view, Object tablePart, TableViewer viewer)
            throws ReflectiveOperationException
    {
        Object controls = view.getClass().getMethod("getControlsPanel").invoke(view); //$NON-NLS-1$
        if (!(field(controls, "filterField") instanceof SearchBox input)) //$NON-NLS-1$
            return;
        FilterInputBox.attachHistoryKeepLayout(input, FilterInputBox.Scope.PROFILING_RESULTS);
        String standard = FilterInputBox.FLAT_FILTER_TOOLTIP;
        input.setToolTipText(TooltipText.wrap(input,
                standard.substring(0, standard.lastIndexOf('\n'))
                        + "\n• ищет только по исходному коду в колонке «Строка»" //$NON-NLS-1$
                        + Global.pluginSignForTooltip()));
        Object stock = field(tablePart, "filter"); //$NON-NLS-1$
        if (!(stock instanceof ViewerFilter nativeFilter))
            return;
        viewer.removeFilter(nativeFilter);
        Table table = viewer.getTable();
        table.setData("profiling.sourceInput", viewer.getInput()); //$NON-NLS-1$
        ViewerComparator comparator = viewer.getComparator();
        viewer.setComparator(null);
        Method targetMatch = stock.getClass().getMethod("isTargetMatch", DebugTargetType.class); //$NON-NLS-1$

        List<CellLabelHighlightWrapper> highlighted = new ArrayList<>();
        for (int index : new int[] { 2 })
        {
            CellLabelProvider provider = viewer.getLabelProvider(index);
            if (provider == null)
                continue;
            CellLabelHighlightWrapper wrapper = new CellLabelHighlightWrapper(provider);
            wrapper.setHighlightPattern(input.getText());
            new TableViewerColumn(viewer, table.getColumn(index)).setLabelProvider(wrapper);
            highlighted.add(wrapper);
        }
        input.setRunSearchOnUiThread(true);
        input.setMinimumSearchTextLength(0);
        input.setRunSearchOnTextChange(true);
        java.util.function.Consumer<String> request = text ->
        {
            for (CellLabelHighlightWrapper wrapper : highlighted)
                wrapper.setHighlightPattern(text);
            Object inputIdentity = table.getData("profiling.sourceInput"); //$NON-NLS-1$
            if (!(inputIdentity instanceof Collection<?> source))
                return;
            List<Object> rows = new ArrayList<>(source);
            Object generation = new Object();
            table.setData("profiling.filterGeneration", generation); //$NON-NLS-1$
            Object oldJob = table.getData("profiling.filterJob"); //$NON-NLS-1$
            if (oldJob instanceof Job previous)
                previous.cancel();
            SmartMatcher requested = new SmartMatcher(text);
            long started = System.nanoTime();
            Global.tempLog("profiling-filter", "Запрос: строк=" + rows.size() //$NON-NLS-1$ //$NON-NLS-2$
                    + ", длина=" + text.length()); //$NON-NLS-1$
            Job job = new Job("Фильтр замеров производительности") //$NON-NLS-1$
            {
                @Override
                protected org.eclipse.core.runtime.IStatus run(IProgressMonitor progress)
                {
                    List<Object> found = new ArrayList<>();
                    Map<Object, String> averageTexts = new IdentityHashMap<>();
                    DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.getDefault());
                    DecimalFormat averageFormat = new DecimalFormat("0.000000", symbols); //$NON-NLS-1$
                    for (Object row : rows)
                    {
                        if (progress.isCanceled())
                            return org.eclipse.core.runtime.Status.CANCEL_STATUS;
                        ILineProfilingResult[] values = lines(row);
                        if (values.length == 0)
                            continue;
                        boolean visible = false;
                        long cleanNanos = 0;
                        long calls = 0;
                        try
                        {
                            for (ILineProfilingResult line : values)
                            {
                                if (Boolean.TRUE.equals(targetMatch.invoke(stock,
                                        line.getDebugTargetType())))
                                {
                                    visible = true;
                                    cleanNanos += line.getPureTimeForDebugTarget(
                                            line.getDebugTargetType()).toNanos();
                                    calls += line.getFrequency();
                                }
                            }
                        }
                        catch (ReflectiveOperationException ex)
                        {
                            Global.tempLog("profiling-view", "Отбор по режиму: " + ex); //$NON-NLS-1$ //$NON-NLS-2$
                            return org.eclipse.core.runtime.Status.CANCEL_STATUS;
                        }
                        if (!visible)
                            continue;
                        if (requested.matches(values[0].getLine()))
                        {
                            found.add(row);
                            double seconds = cleanNanos / 1_000_000_000d;
                            if (calls > 0)
                                averageTexts.put(row, averageFormat.format(seconds / calls));
                        }
                    }
                    if (comparator != null)
                        found.sort((left, right) -> comparator.compare(null, left, right));
                    long scanned = System.nanoTime();
                    Global.tempLog("profiling-filter", "Найдено=" + found.size() //$NON-NLS-1$ //$NON-NLS-2$
                            + ", расчёт мс=" + (scanned - started) / 1_000_000); //$NON-NLS-1$
                    table.getDisplay().asyncExec(() ->
                    {
                        if (table.isDisposed()
                                || table.getData("profiling.sourceInput") != inputIdentity //$NON-NLS-1$
                                || table.getData("profiling.filterGeneration") != generation) //$NON-NLS-1$
                            return;
                        List<Object> displayed = new ArrayList<>(found.size());
                        table.setData("profiling.averageTimes", averageTexts); //$NON-NLS-1$
                        table.setData("profiling.filterInput", displayed); //$NON-NLS-1$
                        long inputStarted = System.nanoTime();
                        viewer.setInput(displayed);
                        Global.tempLog("profiling-filter", "Очистка таблицы мс=" //$NON-NLS-1$ //$NON-NLS-2$
                                + (System.nanoTime() - inputStarted) / 1_000_000);
                        Runnable append = new Runnable()
                        {
                            private int offset;
                            private long previousFinished = System.nanoTime();

                            @Override
                            public void run()
                            {
                                if (table.isDisposed() || viewer.getInput() != displayed
                                        || table.getData("profiling.filterGeneration") != generation //$NON-NLS-1$
                                        || table.getData("profiling.sourceInput") != inputIdentity) //$NON-NLS-1$
                                    return;
                                int end = Math.min(offset + 100, found.size());
                                long queueWait = System.nanoTime() - previousFinished;
                                Object[] batch = found.subList(offset, end).toArray();
                                displayed.addAll(found.subList(offset, end));
                                long batchStarted = System.nanoTime();
                                long addFinished = batchStarted;
                                if (batch.length > 0)
                                {
                                    table.setRedraw(false);
                                    try
                                    {
                                        viewer.add(batch);
                                        addFinished = System.nanoTime();
                                    }
                                    catch (RuntimeException ex)
                                    {
                                        Global.tempLog("profiling-filter", "Ошибка порции " //$NON-NLS-1$ //$NON-NLS-2$
                                                + offset + ".." + end + ": " + ex); //$NON-NLS-1$ //$NON-NLS-2$
                                        throw ex;
                                    }
                                    finally
                                    {
                                        table.setRedraw(true);
                                    }
                                }
                                long redrawFinished = System.nanoTime();
                                Global.tempLog("profiling-filter", "Порция: " + offset + ".." + end //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                        + ", добавление мс=" //$NON-NLS-1$
                                        + (addFinished - batchStarted) / 1_000_000
                                        + ", перерисовка мс=" //$NON-NLS-1$
                                        + (redrawFinished - addFinished) / 1_000_000
                                        + ", ожидание мс=" + queueWait / 1_000_000); //$NON-NLS-1$
                                offset = end;
                                previousFinished = System.nanoTime();
                                if (offset < found.size())
                                    table.getDisplay().asyncExec(this);
                                else
                                {
                                    restoreSelectedRow(table);
                                    Global.tempLog("profiling-filter", "Отображено=" + found.size() //$NON-NLS-1$ //$NON-NLS-2$
                                            + ", всего мс=" //$NON-NLS-1$
                                            + (System.nanoTime() - started) / 1_000_000);
                                }
                            }
                        };
                        append.run();
                    });
                    return org.eclipse.core.runtime.Status.OK_STATUS;
                }
            };
            job.setSystem(true);
            table.setData("profiling.filterJob", job); //$NON-NLS-1$
            job.schedule();
        };
        table.setData("profiling.refilter", (Runnable) () -> request.accept(input.getText())); //$NON-NLS-1$
        input.setSearchListener((text, monitor) -> request.accept(text));
        for (String name : new String[] { "showClientAction", "showServerAction", //$NON-NLS-1$ //$NON-NLS-2$
                "showMobileManagedServerAction" }) //$NON-NLS-1$
        {
            Object action = field(view, name);
            if (action instanceof IAction checked)
                checked.addPropertyChangeListener(e -> table.getDisplay().asyncExec(
                        () -> request.accept(input.getText())));
        }
        for (TableColumn column : table.getColumns())
            column.addListener(SWT.Selection, e -> table.getDisplay().asyncExec(
                    () -> request.accept(input.getText())));
        request.accept(input.getText());
    }

    private static void installSelectionFooter(IViewPart view, Object tablePart, TableViewer viewer)
            throws ReflectiveOperationException
    {
        Table table = viewer.getTable();
        Object stockFilter = field(tablePart, "filter"); //$NON-NLS-1$
        Method targetMatch = stockFilter.getClass().getMethod("isTargetMatch", //$NON-NLS-1$
                DebugTargetType.class);
        Composite content = (Composite) field(view, "content"); //$NON-NLS-1$
        Canvas footer = new Canvas(content, SWT.DOUBLE_BUFFERED);
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.heightHint = 23;
        data.exclude = true;
        footer.setLayoutData(data);
        footer.setVisible(false);
        footer.addPaintListener(e -> paintFooter(table, footer, e.gc, stockFilter, targetMatch));
        Runnable update = () ->
        {
            if (table.isDisposed() || footer.isDisposed())
                return;
            boolean show = table.getSelectionCount() > 1;
            if (data.exclude == show)
            {
                data.exclude = !show;
                footer.setVisible(show);
                content.layout(true, true);
            }
            footer.redraw();
        };
        table.addListener(SWT.Selection, e -> update.run());
        table.addListener(SWT.Resize, e -> footer.redraw());
        if (table.getHorizontalBar() != null)
            table.getHorizontalBar().addListener(SWT.Selection, e -> footer.redraw());
        for (TableColumn column : table.getColumns())
            column.addListener(SWT.Resize, e -> footer.redraw());
        table.addListener(SWT.Dispose, e ->
        {
            if (!footer.isDisposed())
                footer.dispose();
        });
        update.run();
    }

    private static void paintFooter(Table table, Canvas footer, GC gc, Object stockFilter,
            Method targetMatch)
    {
        if (table.isDisposed() || table.getSelectionCount() <= 1)
            return;
        long calls = 0;
        long full = 0;
        long clean = 0;
        long serverCalls = 0;
        double cleanPercent = 0;
        for (TableItem item : table.getSelection())
        {
            ILineProfilingResult[] lines = lines(item.getData());
            for (ILineProfilingResult line : lines)
            {
                try
                {
                    if (!Boolean.TRUE.equals(targetMatch.invoke(stockFilter,
                            line.getDebugTargetType())))
                        continue;
                }
                catch (ReflectiveOperationException e)
                {
                    Global.tempLog("profiling-footer", "Отбор строки: " + e); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                calls += line.getFrequency();
                clean += line.getPureTimeForDebugTarget(line.getDebugTargetType()).toNanos();
                full += line.getTimeForDebugTarget(line.getDebugTargetType()).toNanos();
                cleanPercent += line.getPurePercentage();
                if (line.getServerCallSignal() != null)
                    serverCalls += line.getServerCallSignal().getValue();
            }
        }
        Global.tempLog("profiling-footer", "Выделено=" + table.getSelectionCount() //$NON-NLS-1$ //$NON-NLS-2$
                + ", вызовов=" + calls + ", полное нс=" + full //$NON-NLS-1$ //$NON-NLS-2$
                + ", чистое нс=" + clean + ", процент=" + cleanPercent); //$NON-NLS-1$ //$NON-NLS-2$
        gc.setBackground(footer.getBackground());
        gc.fillRectangle(footer.getClientArea());
        gc.setForeground(footer.getDisplay().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
        gc.drawLine(0, 0, footer.getClientArea().width, 0);
        gc.setForeground(footer.getForeground());
        DecimalFormat format = new DecimalFormat("0.000000", //$NON-NLS-1$
                DecimalFormatSymbols.getInstance(Locale.getDefault()));
        DecimalFormat percentFormat = new DecimalFormat("0.00", //$NON-NLS-1$
                DecimalFormatSymbols.getInstance(Locale.getDefault()));
        String[] cells = new String[table.getColumnCount()];
        cells[0] = "Σ " + table.getSelectionCount(); //$NON-NLS-1$
        cells[3] = Long.toString(calls);
        cells[4] = format.format(full / 1_000_000_000d);
        cells[5] = format.format(clean / 1_000_000_000d) + "  " //$NON-NLS-1$
                + percentFormat.format(cleanPercent) + " %"; //$NON-NLS-1$
        cells[9] = Long.toString(serverCalls);
        cells[11] = format.format(calls > 0 ? (double) clean / calls / 1_000_000_000d : 0);
        int x = table.getHorizontalBar() != null ? -table.getHorizontalBar().getSelection() : 0;
        for (int index : table.getColumnOrder())
        {
            int width = table.getColumn(index).getWidth();
            String value = cells[index];
            if (value != null && width > 6)
            {
                int textWidth = gc.textExtent(value).x;
                int offset = index == 0 ? 4 : Math.max(4, width - textWidth - 4);
                gc.setClipping(x + 2, 1, Math.max(0, width - 4), footer.getClientArea().height - 1);
                gc.drawText(value, x + offset, 4, true);
            }
            x += width;
        }
        gc.setClipping((org.eclipse.swt.graphics.Rectangle) null);
    }

    private static Field fieldHandle(Object owner, String name) throws ReflectiveOperationException
    {
        Class<?> type = owner.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            }
            catch (NoSuchFieldException e)
            {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException
    {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
