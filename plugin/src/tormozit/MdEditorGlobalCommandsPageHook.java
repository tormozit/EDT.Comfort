package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.IManagedForm;

import com._1c.g5.v8.dt.core.platform.IConfigurationAware;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorPage;
import com._1c.g5.v8.dt.md.ui.shared.MdUiSharedImages;
import com._1c.g5.v8.dt.metadata.mdclass.CommonCommand;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.ui.util.OpenHelper;

/**
 * Встраивает страницу «Глобальные команды» в редактор определяемого типа
 * ({@link DtGranularEditor}): список параметризуемых общих команд конфигурации, в типе
 * параметра которых указан этот определяемый тип. Только просмотр; двойной клик или Enter
 * открывают команду.
 *
 * <p>Вкладка ставится после «Подписки» ({@link MdEventHandlersPageHook}), если та есть.
 */
public final class MdEditorGlobalCommandsPageHook implements IStartup
{
    private static final String TAG = "MdEditorGlobalCommandsPageHook"; //$NON-NLS-1$

    static final String PAGE_ID = "tormozit.mdGlobalCommands"; //$NON-NLS-1$

    private static final String PAGE_TITLE = "Команды"; //$NON-NLS-1$

    private static final int HOOK_RETRY_DELAY_MS = 200;

    private static final int HOOK_MAX_ATTEMPTS = 150;

    private final Set<DtGranularEditor<?>> hookedEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    private final Set<DtGranularEditor<?>> pendingRetryEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)      { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });
            for (IWorkbenchWindow w : workbench.getWorkbenchWindows())
                hookWindow(w);
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart editor = ref.getEditor(false);
                if (editor instanceof DtGranularEditor<?> granular)
                    hookEditor(granular, 0);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)      { hookFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref)   { hookFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r)  {}
            @Override public void partClosed(IWorkbenchPartReference r)        {}
            @Override public void partDeactivated(IWorkbenchPartReference r)   {}
            @Override public void partHidden(IWorkbenchPartReference r)        {}
            @Override public void partVisible(IWorkbenchPartReference r)       {}
            @Override public void partInputChanged(IWorkbenchPartReference r)  {}

            private void hookFromRef(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference editorRef))
                    return;
                IWorkbenchPart part = editorRef.getPart(false);
                if (part instanceof DtGranularEditor<?> granular)
                    hookEditor(granular, 0);
            }
        });
    }

    private void hookEditor(DtGranularEditor<?> editor, int attempt)
    {
        if (hookedEditors.contains(editor))
            return;
        try
        {
            // Пока редактор не инициализирован, вкладки основных страниц не созданы.
            if (editor.getModel() == null || !Boolean.TRUE.equals(Global.getField(editor, "initialized"))) //$NON-NLS-1$
            {
                scheduleRetry(editor, attempt);
                return;
            }
            if (!hookedEditors.add(editor))
                return;
            if (editor.getModel() instanceof DefinedType definedType)
                addPage(editor, definedType);
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "hook editor", e); //$NON-NLS-1$
        }
    }

    private void scheduleRetry(DtGranularEditor<?> editor, int attempt)
    {
        if (attempt >= HOOK_MAX_ATTEMPTS || editor.getSite() == null)
            return;
        Composite container = (Composite)Global.invoke(editor, "getContainer"); //$NON-NLS-1$
        if (container != null && container.isDisposed())
            return;
        if (!pendingRetryEditors.add(editor))
            return;
        Display.getDefault().timerExec(HOOK_RETRY_DELAY_MS, () ->
        {
            pendingRetryEditors.remove(editor);
            hookEditor(editor, attempt + 1);
        });
    }

    private static void addPage(DtGranularEditor<?> editor, DefinedType definedType)
    {
        try
        {
            GlobalCommandsPage page = new GlobalCommandsPage(definedType);
            page.initialize(editor);
            Composite container = (Composite)Global.invoke(editor, "getContainer"); //$NON-NLS-1$
            if (container == null || container.isDisposed())
                return;
            page.createPartControl(container);
            int index = insertIndex(editor);
            if (index >= 0)
                editor.addPage(index, page);
            else
                editor.addPage(page);
        }
        catch (PartInitException | RuntimeException e)
        {
            Global.logError(TAG, "add page", e); //$NON-NLS-1$
        }
    }

    /** После «Подписок», иначе — там, где эту вкладку ставит {@link MdEventHandlersPageHook}. */
    private static int insertIndex(DtGranularEditor<?> editor)
    {
        int subscriptions = MdEventHandlersPageHook.indexOfPageId(editor, MdEventHandlersPageHook.PAGE_ID);
        if (subscriptions >= 0)
            return subscriptions + 1;
        return MdEventHandlersPageHook.resolveInsertIndex(editor);
    }

    private static Configuration resolveConfiguration(DefinedType definedType)
    {
        IV8ProjectManager projectManager = (IV8ProjectManager)Global.getServiceByClass(IV8ProjectManager.class);
        IV8Project project = projectManager != null ? projectManager.getProject(definedType) : null;
        return project instanceof IConfigurationAware aware ? aware.getConfiguration() : null;
    }

    static final class GlobalCommandsPage extends DtGranularEditorPage<DefinedType>
    {
        private final DefinedType definedType;

        private Composite host;

        private TableViewer viewer;

        private Table table;

        GlobalCommandsPage(DefinedType definedType)
        {
            super(PAGE_ID, PAGE_TITLE);
            this.definedType = definedType;
        }

        @Override
        protected Layout createPageLayout()
        {
            return new FillLayout();
        }

        @Override
        protected void createPageControls(IManagedForm managedForm)
        {
            host = new Composite(managedForm.getForm().getBody(), SWT.NONE);
            TableColumnLayout tableLayout = new TableColumnLayout();
            host.setLayout(tableLayout);

            viewer = new TableViewer(host, SWT.FULL_SELECTION | SWT.SINGLE | SWT.BORDER);
            table = viewer.getTable();
            table.setHeaderVisible(true);
            ThemeAwareColors.applyGridLines(table);

            TableViewerColumn nameColumn = new TableViewerColumn(viewer, SWT.NONE);
            nameColumn.getColumn().setText("Имя"); //$NON-NLS-1$
            nameColumn.setLabelProvider(new ColumnLabelProvider()
            {
                @Override
                public String getText(Object element)
                {
                    return nameOf(element);
                }

                @Override
                public Image getImage(Object element)
                {
                    return element instanceof CommonCommand command
                        ? MdUiSharedImages.getMdClassImage(command.eClass()) : null;
                }
            });
            tableLayout.setColumnData(nameColumn.getColumn(), new ColumnPixelData(300, true, true));

            TableViewerColumn groupColumn = new TableViewerColumn(viewer, SWT.NONE);
            groupColumn.getColumn().setText("Группа"); //$NON-NLS-1$
            groupColumn.setLabelProvider(new ColumnLabelProvider()
            {
                @Override
                public String getText(Object element)
                {
                    return groupOf(element);
                }
            });
            tableLayout.setColumnData(groupColumn.getColumn(), new ColumnWeightData(1, 200, true));

            viewer.setContentProvider(ArrayContentProvider.getInstance());

            FormTableInteraction interaction = new FormTableInteraction(table, viewer,
                (item, col) -> col == 0 ? nameOf(item.getData()) : groupOf(item.getData()));
            interaction.install();

            table.addListener(SWT.DefaultSelection, e ->
            {
                if (e.item instanceof TableItem item && item.getData() instanceof CommonCommand command)
                    open(command);
            });
        }

        static String nameOf(Object element)
        {
            return element instanceof CommonCommand command && command.getName() != null ? command.getName() : ""; //$NON-NLS-1$
        }

        static String groupOf(Object element)
        {
            if (!(element instanceof CommonCommand command) || command.getGroup() == null)
                return ""; //$NON-NLS-1$
            // mcore.CommandGroup — пустой интерфейс: имя есть у группы-объекта метаданных и у стандартной группы.
            EObject group = command.getGroup();
            if (group instanceof MdObject mdObject)
                return mdObject.getName() != null ? mdObject.getName() : ""; //$NON-NLS-1$
            EStructuralFeature name = group.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
            return name != null && group.eGet(name) instanceof String text ? text : ""; //$NON-NLS-1$
        }

        static void open(CommonCommand command)
        {
            try
            {
                new OpenHelper().openEditor(command);
            }
            catch (RuntimeException | LinkageError e)
            {
                Global.logError(TAG, "open command", e); //$NON-NLS-1$
            }
        }

        /** Тип параметра команды включает определяемый тип страницы. */
        private boolean isParameterized(CommonCommand command)
        {
            TypeDescription description = command.getCommandParameterType();
            String name = definedType.getName();
            if (description == null || name == null)
                return false;
            for (TypeItem type : description.getTypes())
                if (("DefinedType." + name).equals(type.getName()) //$NON-NLS-1$
                    || ("ОпределяемыйТип." + name).equals(type.getNameRu())) //$NON-NLS-1$
                    return true;
            return false;
        }

        /** Число команд; {@code null} — список ещё не загружен. */
        Integer commandCount()
        {
            return viewer != null && viewer.getInput() instanceof List<?> rows ? Integer.valueOf(rows.size()) : null;
        }

        @Override
        public void setActive(boolean active)
        {
            if (active && host != null && !host.isDisposed())
                reload();
            super.setActive(active);
        }

        @Override
        public void setFocus()
        {
            if (table != null && !table.isDisposed())
                table.setFocus();
            else
                super.setFocus();
        }

        /** Список из модели заново: команды могли измениться в другом редакторе. */
        private void reload()
        {
            Configuration configuration = resolveConfiguration(definedType);
            List<CommonCommand> rows = new ArrayList<>();
            if (configuration != null)
                for (CommonCommand command : configuration.getCommonCommands())
                    if (isParameterized(command))
                        rows.add(command);
            rows.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(nameOf(a), nameOf(b)));
            viewer.setInput(rows);
            if (getEditor() instanceof DtGranularEditor<?> granularEditor)
                MdEditorTabsHook.requestRefresh(granularEditor);
        }
    }
}
