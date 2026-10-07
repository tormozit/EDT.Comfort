package tormozit;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.e4.ui.model.application.ui.MElementContainer;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.workbench.IPresentationEngine;
import org.eclipse.e4.ui.workbench.renderers.swt.StackRenderer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.DefaultToolTip;
import org.eclipse.jface.window.ToolTip;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPartConstants;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ShowInContext;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.ui.editor.input.IDtEditorInput;
import com._1c.g5.v8.dt.metadata.mdclass.BasicCommand;
import com._1c.g5.v8.dt.metadata.mdclass.CommonCommand;

/** Заголовки вкладок (728), подсказки списка (729), постоянная кнопка списка (730) и диагностика (726). */
public final class WorkbenchTabsHook implements IStartup
{
    private static final String TOPIC = "workbench-tabs"; //$NON-NLS-1$

    private final Set<BslXtextEditor> titleEditors = Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            Display display = Display.getDefault();
            if (display.isDisposed())
                return;
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                hookWindow(window);
            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow window) { hookWindow(window); }
                @Override public void windowActivated(IWorkbenchWindow window) {}
                @Override public void windowDeactivated(IWorkbenchWindow window) {}
                @Override public void windowClosed(IWorkbenchWindow window) {}
            });
            Global.tempLog(TOPIC, "installed"); //$NON-NLS-1$
            display.addFilter(SWT.Paint, event -> {
                if (event.widget instanceof CTabFolder folder)
                    PartListButton.scheduleInstall(folder);
            });
            display.addFilter(SWT.Show, event -> {
                if (!(event.widget instanceof Shell shell))
                    return;
                try
                {
                    if (!dumpPartTables(shell))
                        return;
                    for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                        dumpFolders(window.getShell());
                    Global.tempLog(TOPIC, "end popup=" + identity(shell)); //$NON-NLS-1$
                }
                catch (RuntimeException ex)
                {
                    Global.tempLogException(TOPIC, "popup scan", ex); //$NON-NLS-1$
                }
            });
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        PartListButton.scan(window.getShell());
        for (var page : window.getPages())
            for (IEditorReference ref : page.getEditorReferences())
                hookEditor(ref);
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)
            {
                logEditorInputs(ref, "opened"); //$NON-NLS-1$
                hookEditor(ref);
            }
            @Override public void partActivated(IWorkbenchPartReference ref) { hookEditor(ref); }
            @Override public void partInputChanged(IWorkbenchPartReference ref)
            {
                logEditorInputs(ref, "inputChanged"); //$NON-NLS-1$
                hookEditor(ref);
            }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partVisible(IWorkbenchPartReference ref) {}
        });
    }

    /** Наблюдаем входные данные без восстановления ещё не созданных редакторов. */
    private static void logEditorInputs(IWorkbenchPartReference trigger, String reason)
    {
        if (!(trigger instanceof IEditorReference))
            return;
        final String topic = "editor-opening"; //$NON-NLS-1$
        Global.tempLog(topic, "event=" + reason + " ref=" + identity(trigger) + " id=" + trigger.getId()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Global.tempLogException(topic, "opening call stack", new Exception("diagnostic stack")); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            IEditorPart opened = ((IEditorReference)trigger).getEditor(false);
            if (opened == null)
            {
                Global.tempLog(topic, "editor not instantiated"); //$NON-NLS-1$
                return;
            }
            IEditorInput input = opened.getEditorInput();
            Global.tempLog(topic, "trigger editor=" + identity(opened) + " input=" + describeInput(input)); //$NON-NLS-1$ //$NON-NLS-2$
            for (IEditorReference ref : trigger.getPage().getEditorReferences())
            {
                IEditorPart editor = ref.getEditor(false);
                if (editor == null)
                {
                    Global.tempLog(topic, "candidate ref=" + identity(ref) + " id=" + ref.getId() //$NON-NLS-1$ //$NON-NLS-2$
                        + " name=" + ref.getName() + " editor not instantiated"); //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                }
                try
                {
                    IEditorInput candidate = editor.getEditorInput();
                    Global.tempLog(topic, "candidate ref=" + identity(ref) + " editor=" + identity(editor) //$NON-NLS-1$ //$NON-NLS-2$
                        + " id=" + ref.getId() + " title=" + editor.getTitle() //$NON-NLS-1$ //$NON-NLS-2$
                        + " input=" + describeInput(candidate) //$NON-NLS-1$
                        + " existingEqualsNew=" + (candidate != null && candidate.equals(input)) //$NON-NLS-1$
                        + " newEqualsExisting=" + (input != null && input.equals(candidate))); //$NON-NLS-1$
                }
                catch (RuntimeException ex)
                {
                    Global.tempLogException(topic, "candidate ref=" + identity(ref), ex); //$NON-NLS-1$
                }
            }
        }
        catch (RuntimeException ex)
        {
            Global.tempLogException(topic, "editor input scan", ex); //$NON-NLS-1$
        }
    }

    private static String describeInput(IEditorInput input)
    {
        if (input == null)
            return "null"; //$NON-NLS-1$
        String text = input.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(input)) //$NON-NLS-1$
            + " name=" + input.getName() + " tooltip=" + input.getToolTipText() + " value=" + input; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (input instanceof org.eclipse.ui.IFileEditorInput fileInput)
            text += " file=" + fileInput.getFile().getFullPath(); //$NON-NLS-1$
        if (input instanceof IDtEditorInput dtInput)
        {
            var model = dtInput.getModel();
            var feature = dtInput.getFeature();
            text += " model=" + identity(model) + " feature=" + (feature == null ? null : feature.getName()); //$NON-NLS-1$ //$NON-NLS-2$
            if (model != null && model.eResource() != null)
                text += " resource=" + model.eResource().getURI(); //$NON-NLS-1$
        }
        return text;
    }

    private void hookEditor(IWorkbenchPartReference ref)
    {
        if (!(ref instanceof IEditorReference) || !(ref.getPart(false) instanceof BslXtextEditor editor))
            return;
        if (titleEditors.add(editor))
            editor.addPropertyListener((part, property) -> {
                if (property == IWorkbenchPartConstants.PROP_TITLE)
                    Display.getDefault().asyncExec(() -> shortenCommandTitle(editor));
            });
        shortenCommandTitle(editor);
    }

    /** Штатный BSL ShowInContext возвращает владельца модуля, как и расчёт заголовка EDT. */
    private static void shortenCommandTitle(BslXtextEditor editor)
    {
        if (PlatformUI.getWorkbench().isClosing() || editor.getSite() == null
            || editor.getInternalSourceViewer() == null
            || editor.getInternalSourceViewer().getTextWidget() == null
            || editor.getInternalSourceViewer().getTextWidget().isDisposed())
            return;
        try
        {
            ShowInContext context = editor.getShowInContext();
            if (context == null || !(context.getSelection() instanceof IStructuredSelection selection)
                || !(selection.getFirstElement() instanceof BasicCommand command)
                || command instanceof CommonCommand)
                return;
            String name = command.getName();
            if (name != null && !name.isBlank() && !name.equals(editor.getPartName()))
            {
                if (!Global.invokeVoid(editor, "setPartName", name)) //$NON-NLS-1$
                    Global.tempLog("command-tab-title", "setPartName failed: " + name); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        catch (RuntimeException ex)
        {
            Global.tempLogException("command-tab-title", "shorten title", ex); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static boolean dumpPartTables(Composite parent)
    {
        boolean found = false;
        for (Control child : parent.getChildren())
        {
            if (child instanceof Table table)
            {
                TableItem[] rows = table.getItems();
                boolean partTable = false;
                for (TableItem row : rows)
                    partTable |= row.getData() instanceof MPart;
                if (!partTable)
                    continue;
                found = true;
                PartListToolTip.install(table);
                Global.tempLog(TOPIC, "popup=" + identity(table.getShell()) //$NON-NLS-1$
                    + " table=" + identity(table) + " rows=" + rows.length); //$NON-NLS-1$ //$NON-NLS-2$
                for (int i = 0; i < rows.length; i++)
                    Global.tempLog(TOPIC, "row=" + i + " text=" + rows[i].getText() //$NON-NLS-1$ //$NON-NLS-2$
                        + " element=" + describe(rows[i].getData())); //$NON-NLS-1$
            }
            else if (child instanceof Composite composite)
                found |= dumpPartTables(composite);
        }
        return found;
    }

    /** BasicPartList хранит MPart в строках; источник текста совпадает с SWTPartRenderer.getToolTip. */
    private static final class PartListToolTip extends DefaultToolTip
    {
        private static final String KEY = "tormozit.workbench.partListToolTip"; //$NON-NLS-1$

        private final Table table;

        static void install(Table table)
        {
            if (table.getData(KEY) != null)
                return;
            table.setData(KEY, new PartListToolTip(table));
            Global.tempLog(TOPIC, "tooltip installed table=" + identity(table)); //$NON-NLS-1$
        }

        private PartListToolTip(Table table)
        {
            super(table, ToolTip.NO_RECREATE, false);
            this.table = table;
            setShift(new Point(10, 20));
            table.addListener(SWT.Hide, event -> hide());
            table.getShell().addListener(SWT.Hide, event -> hide());
        }

        @Override
        protected Object getToolTipArea(Event event)
        {
            return table.getItem(new Point(event.x, event.y));
        }

        @Override
        protected boolean shouldCreateToolTip(Event event)
        {
            // Штатный ColumnViewerToolTipSupport перед этим событием включает native tooltip.
            // JFace-подсказка использует Shell с NO_FOCUS и не забирает фокус у списка вкладок.
            table.setToolTipText(""); //$NON-NLS-1$
            TableItem row = (TableItem)getToolTipArea(event);
            String text = null;
            if (row != null && row.getData() instanceof MPart part)
            {
                Object override = part.getTransientData().get(IPresentationEngine.OVERRIDE_TITLE_TOOL_TIP_KEY);
                text = override instanceof String tip ? tip : part.getLocalizedTooltip();
                if (text == null || text.isBlank())
                    text = part.getLocalizedLabel();
            }
            Global.tempLog(TOPIC, "tooltip hover row=" + identity(row) + " text=" + text); //$NON-NLS-1$ //$NON-NLS-2$
            if (text == null || text.isBlank())
                return false;
            setText(TooltipText.wrap(table, text + Global.pluginSignForTooltip()));
            return super.shouldCreateToolTip(event);
        }
    }

    /** SWT addTabControl/setChevronVisible и StackRenderer.showAvailableItems проверены в бандлах. */
    private static final class PartListButton
    {
        private static final String KEY = "tormozit.workbench.partListButton"; //$NON-NLS-1$

        static void scan(Composite parent)
        {
            if (parent == null || parent.isDisposed())
                return;
            if (parent instanceof CTabFolder folder)
                scheduleInstall(folder);
            for (Control child : parent.getChildren())
                if (child instanceof Composite composite)
                    scan(composite);
        }

        static void scheduleInstall(CTabFolder folder)
        {
            if (folder.isDisposed() || folder.getData(KEY) != null
                || !(folder.getData("modelElement") instanceof MPartStack stack) //$NON-NLS-1$
                || !(stack.getRenderer() instanceof StackRenderer))
                return;
            folder.setData(KEY, Boolean.TRUE);
            folder.getDisplay().asyncExec(() -> install(folder));
        }

        private static void install(CTabFolder folder)
        {
            if (folder.isDisposed())
                return;
            if (!(folder.getData("modelElement") instanceof MPartStack stack) //$NON-NLS-1$
                || !(stack.getRenderer() instanceof StackRenderer))
            {
                folder.setData(KEY, null);
                return;
            }
            ToolBar toolbar = new ToolBar(folder, SWT.FLAT);
            ToolItem button = new ToolItem(toolbar, SWT.PUSH);
            button.setText("»"); //$NON-NLS-1$
            button.setToolTipText(TooltipText.wrap(toolbar,
                "Список вкладок" + Global.pluginSignForTooltip()));
            button.addListener(SWT.Selection, event -> {
                if (folder.getData("modelElement") instanceof MPartStack currentStack //$NON-NLS-1$
                    && currentStack.getRenderer() instanceof StackRenderer renderer)
                {
                    Global.tempLog(TOPIC, "list button clicked folder=" + identity(folder) //$NON-NLS-1$
                        + " tabs=" + folder.getItemCount()); //$NON-NLS-1$
                    renderer.showAvailableItems(currentStack, folder);
                }
            });
            boolean added = false;
            try
            {
                // Отдельный tab control не заменяет штатный topRight (панель действий части).
                added = Global.invokeVoid(folder, "addTabControl", toolbar, SWT.TRAIL); //$NON-NLS-1$
                if (!added || !Global.invokeVoid(folder, "setChevronVisible", false)) //$NON-NLS-1$
                    throw new IllegalStateException("Cannot install permanent tab list button"); //$NON-NLS-1$
                folder.setData(KEY, toolbar);
                Global.tempLog(TOPIC, "list button installed folder=" + identity(folder)); //$NON-NLS-1$
            }
            catch (RuntimeException ex)
            {
                Global.tempLogException(TOPIC, "list button install", ex); //$NON-NLS-1$
                if (added)
                    Global.invokeVoid(folder, "removeTabControl", toolbar); //$NON-NLS-1$
                toolbar.dispose();
                Global.invokeVoid(folder, "setChevronVisible", true); //$NON-NLS-1$
                // Не повторять неудачную установку на каждом Paint.
            }
        }
    }

    private static void dumpFolders(Composite parent)
    {
        if (parent == null || parent.isDisposed())
            return;
        if (parent instanceof CTabFolder folder)
        {
            Object model = folder.getData("modelElement"); //$NON-NLS-1$
            if (model instanceof MElementContainer<?> stack)
            {
                Global.tempLog(TOPIC, "folder=" + identity(folder) + " visible=" + folder.isVisible() //$NON-NLS-1$ //$NON-NLS-2$
                    + " bounds=" + folder.getBounds() + " stack=" + describe(stack)); //$NON-NLS-1$ //$NON-NLS-2$
                for (MUIElement element : stack.getChildren())
                    Global.tempLog(TOPIC, "child=" + describe(element)); //$NON-NLS-1$
                for (CTabItem tab : folder.getItems())
                    Global.tempLog(TOPIC, "tab=" + identity(tab) + " text=" + tab.getText() //$NON-NLS-1$ //$NON-NLS-2$
                        + " showing=" + tab.isShowing() + " bounds=" + tab.getBounds() //$NON-NLS-1$ //$NON-NLS-2$
                        + " element=" + describe(tab.getData("modelElement"))); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        for (Control child : parent.getChildren())
            if (child instanceof Composite composite)
                dumpFolders(composite);
    }

    private static String describe(Object object)
    {
        String text = identity(object);
        if (object instanceof MUIElement element)
            text += " id=" + element.getElementId() + " rendered=" + element.isToBeRendered() //$NON-NLS-1$ //$NON-NLS-2$
                + " visible=" + element.isVisible() + " parent=" + identity(element.getParent()) //$NON-NLS-1$ //$NON-NLS-2$
                + " widget=" + identity(element.getWidget()); //$NON-NLS-1$
        if (object instanceof MPart part)
            text += " label=" + part.getLabel() + " uri=" + part.getContributionURI(); //$NON-NLS-1$ //$NON-NLS-2$
        if (object instanceof MPlaceholder placeholder)
            text += " ref=" + describe(placeholder.getRef()); //$NON-NLS-1$
        return text;
    }

    private static String identity(Object object)
    {
        return object == null ? "null" : object.getClass().getSimpleName() //$NON-NLS-1$
            + "@" + Integer.toHexString(System.identityHashCode(object)); //$NON-NLS-1$
    }
}
