package tormozit;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.xml.stream.XMLStreamException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.core.event.BmChangeEvent;
import com._1c.g5.v8.bm.core.event.BmEvent;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmEditingContext;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.event.BmEventFilter;
import com._1c.g5.v8.bm.integration.event.IBmAsyncEventListener;
import com._1c.g5.v8.dt.common.PreferenceUtils;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetUnion;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DataSetField;
import com._1c.g5.v8.dt.dcs.path.DcsPath;
import com._1c.g5.v8.dt.dcs.settings.DcsAvailableFieldInfo;
import com._1c.g5.v8.dt.dcs.settings.DcsAvailableSettingsSourceForSchema;
import com._1c.g5.v8.dt.dcs.settings.SettingsContext;
import com._1c.g5.v8.dt.dcs.ui.DataCompositionSchemaControlContext;
import com._1c.g5.v8.dt.dcs.ui.DataCompositionSchemaEditor;
import com._1c.g5.v8.dt.dcs.ui.DcsEvent;
import com._1c.g5.v8.dt.dcs.ui.DcsEvent.DcsEventType;
import com._1c.g5.v8.dt.dcs.ui.EditorPage;
import com._1c.g5.v8.dt.dcs.ui.datasets.DataSets;
import com._1c.g5.v8.dt.dcs.ui.datasets.DataSetsLoadHandler;
import com._1c.g5.v8.dt.dcs.util.DcsV8Serializer;
import com._1c.g5.v8.dt.export.ExportException;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorEmbeddedEditorPage;
import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.ql.typesystem.TypeDescriptionSource;
import com._1c.g5.v8.dt.xml.ChangeAnyRefTypeOutputStream;

public class DataCompositionSchemaEditorHook implements IStartup
{
    static private IResourceLookup resourceLookup() {
        return Global.getOsgiService(IResourceLookup.class);
    }
    private static final String EDITOR_ID  = "com._1c.g5.v8.dt.md.ui.editor.commonTemplate"; //$NON-NLS-1$
    private final Map<IWorkbenchWindow, IPartListener2>          partListeners =
        new HashMap<>();
    private final Map<DtGranularEditor<?>, IPageChangedListener> pageListeners =
        new HashMap<>();

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
//          Activator.getDefault().getInjector().injectMembers(this); // Слишком рано?
            for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows())
                hookWindow(w);

            PlatformUI.getWorkbench().addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });
        });
    }

    // =======================================================================
    // Подключение к окну
    // =======================================================================

    private void hookWindow(IWorkbenchWindow window)
    {
        if (window.getActivePage() != null)
            for (IEditorPart ed : window.getActivePage().getEditors())
                if (EDITOR_ID.equals(ed.getSite().getId()))
                    applyPatchToGranularEditor((DtGranularEditor<?>) ed);

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                if (!EDITOR_ID.equals(ref.getId())) return;
                Display.getDefault().asyncExec(() ->
                {
                    IEditorPart part = (IEditorPart) ref.getPart(false);
                    if (part instanceof DtGranularEditor<?>)
                        applyPatchToGranularEditor((DtGranularEditor<?>) part);
                });
            }
            @Override public void partActivated(IWorkbenchPartReference r)    {}
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private void applyPatchToGranularEditor(DtGranularEditor<?> editor)
    {
        org.eclipse.ui.forms.editor.IFormPage activePage = editor.getActivePageInstance();
        if (activePage instanceof DtGranularEditorEmbeddedEditorPage)
            applyPatchToEditorPage((DtGranularEditorEmbeddedEditorPage<?>) activePage);

        if (!pageListeners.containsKey(editor))
        {
            IPageChangedListener pl = new PageChangeListener();
            editor.addPageChangedListener(pl);
            pageListeners.put(editor, pl);
        }
    }

    // =======================================================================
    // Патч страницы «Макет» (DCS-редактор)
    // =======================================================================

    /**
     * @param page страница «Макет» (TemplateEditorDcsPage), содержащая DataCompositionSchemaEditor
     */
    private void applyPatchToEditorPage(DtGranularEditorEmbeddedEditorPage<?> page)
    {
        if ("editors.commontemplate.pages.dcs" != page.getId())
            return;
        // page: com._1c.g5.v8.dt.internal.md.ui.editors.template.TemplateEditorDcsPage
        DataCompositionSchemaEditor dcsEditor = (DataCompositionSchemaEditor) page.getEmbeddedEditor();
        installResourcesSortMenu(dcsEditor);
        installOutputListCommand(dcsEditor);
        ComputedValueTypes.install(dcsEditor);
        DataSets firstPage = (DataSets) dcsEditor.getPages().get(0);
        ToolBar toolbar = findToolbar((Composite) firstPage);
        if (toolbar == null || toolbar.isDisposed()) {
            return; 
        }
        // 2. ЗАЩИТА: Добавляем кнопку через asyncExec
        // Это гарантирует, что мы не лезем в UI в момент его отрисовки
        Display.getDefault().asyncExec(() -> {
            if (toolbar.isDisposed()|| toolbar.getItems().length > 2) 
                return; // Проверяем еще раз перед самой вставкой
            ToolItem item = new ToolItem(toolbar, SWT.PUSH);
            item.setText("Редактор ИР"); 
            item.setToolTipText("Редактировать в консоли компоновки данных ИР" + Global.pluginSignForTooltip());
            item.addListener(SWT.Selection, event -> {
                Object editor = Global.getField(page, "editor");
                Object BmModel = Global.getField(editor, "bmModel");
                IDtProject project = (IDtProject)Global.getField(BmModel, "project");
                IRSession irSession = IRApplication.getSession(project, true);
                String fullObjectName = GetRef.getRefFromEditor(editor);
                if (irSession == null || irSession.executor == null) {
                    return;
                }
                irSession.executor.submit(() -> {
                    try 
                    {
                        // Здесь мы находимся в родном потоке для этого COM-объекта. 
                        String file = exportToFile(page);
                        Object irClient = irSession.getModule("ирКлиент");
                        irSession.showWindow();
                        // Мультиметка260525_210353
                        ComBridge.invoke(irClient, "РедактироватьСхемуКомпоновкиИзФайлаЛкс", file, false, fullObjectName);
                        new File(file).delete();
                        ToastNotification.show("Редактор ИР", "Измененная схема вернется в EDT, если не будет изменяться там во время редактирования в приложении ИР!");
                    } 
                    catch (Exception e) 
                    {
                        Global.log("Ошибка вызова ИР: " + e.getMessage());
                    }
                });
                
            });
            toolbar.pack();
            toolbar.getParent().layout(true);
        });
    }

    /**
     * Вешает на таблицу ресурсов пункт «Сортировать по полю» ({@link DcsFieldsSortHandler}).
     *
     * <p>Своего контекстного меню у этой таблицы EDT не создаёт: в
     * {@code DcsUiUtil.addContextMenuToViewer} идентификатор меню передан как {@code null},
     * поэтому и меню не наполняется, и точки для декларативного вклада нет — меню заводим сами.
     * Пакет {@code ...dcs.ui.resources} бандл не экспортирует, поэтому страница и её просмотрщик
     * доступны только по имени класса и рефлексией.
     */
    private void installResourcesSortMenu(DataCompositionSchemaEditor dcsEditor)
    {
        for (EditorPage editorPage : dcsEditor.getPages())
        {
            if (!"com._1c.g5.v8.dt.dcs.ui.resources.Resources".equals(editorPage.getClass().getName())) //$NON-NLS-1$
                continue;
            Object viewer = Global.invoke(editorPage, "getViewer"); //$NON-NLS-1$
            if (!(viewer instanceof ColumnViewer resourcesViewer))
                return;
            // getControl() отдаёт TableEx — композит-обёртку. Правый щелчок приходит во
            // вложенный контрол данных, на него же вешает меню и сама EDT
            // (DcsUiUtil.addContextMenuToViewer: table.getDataControl().setMenu(...)).
            Object dataControl = Global.invoke(resourcesViewer.getControl(), "getDataControl"); //$NON-NLS-1$
            Control control =
                dataControl instanceof Control child ? child : resourcesViewer.getControl();
            if (control == null || control.isDisposed() || control.getMenu() != null)
                return;
            Menu menu = new Menu(control);
            MenuItem item = new MenuItem(menu, SWT.PUSH);
            item.setText("Сортировать по полю"); //$NON-NLS-1$
            item.addListener(SWT.Selection, event -> DcsFieldsSortHandler
                .sortResourcesByField(dcsEditor.getControlContext(), resourcesViewer));
            control.setMenu(menu);
            return;
        }
    }

    /** Простые имена страниц-композитов конструктора СКД, в списки которых добавляем «Вывести список». */
    private static final Set<String> OUTPUT_LIST_PAGES =
        Set.of("DataSets", "Links", "CalculatedFields", "Parameters", "Resources"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    /**
     * Добавляет пункт «Вывести список» ({@link OutputListCommand}) в контекстные меню списков
     * основных страниц схемы: поля наборов данных, связи наборов, вычисляемые поля, ресурсы,
     * параметры. Эти списки — виджеты Nebula {@code Grid}, единой точки подключения у них нет,
     * поэтому обходим дерево контролов каждой страницы. Вкладку «Настройки» не трогаем.
     */
    private void installOutputListCommand(DataCompositionSchemaEditor dcsEditor)
    {
        for (EditorPage editorPage : dcsEditor.getPages())
        {
            if (!(editorPage instanceof Composite pageComposite) || pageComposite.isDisposed())
                continue;
            String name = editorPage.getClass().getSimpleName();
            if (OUTPUT_LIST_PAGES.contains(name))
                OutputListCommand.attachDescendants(pageComposite);
        }
    }

    public static String exportToFile(DtGranularEditorEmbeddedEditorPage<?> page)
        throws IOException, FileNotFoundException, XMLStreamException, ExportException
    {
        // com._1c.g5.v8.dt.dcs.ui.datasets.DataSetsSaveHandler.DataSetsSaveHandler()
        DataCompositionSchemaEditor dcsEditor = (DataCompositionSchemaEditor) page.getEmbeddedEditor();
        Object editor = Global.getField(page, "editor");
        Object BmModel = Global.getField(editor, "bmModel");
        IDtProject project = (IDtProject)Global.getField(BmModel, "project");
        String file = File.createTempFile("tormozit", ".xml").getPath();
        DataCompositionSchema schema = (DataCompositionSchema) dcsEditor.getModel();
        IV8ProjectManager projectManager = (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
        IV8Project v8Project = projectManager.getProject(project);
        int convertMode = v8Project.getCompatibilityMode().compareTo(CompatibilityMode.VERSION8_323) <= 0 ? 0 : 1;
        try (FileOutputStream fileStream = new FileOutputStream(file)) 
        {
            OutputStream outputStream = new ChangeAnyRefTypeOutputStream(fileStream, convertMode);
//                        outputStream.write(BOM); // new byte[]{-17, -69, -65};
//                        Version version = runtimeVersionSupport.getRuntimeVersion(schema);
            Version version = v8Project.getVersion();
            DcsV8Serializer serializer = new DcsV8Serializer(project, version, resourceLookup());
            serializer.serializeXML(schema, outputStream, PreferenceUtils.getLineSeparator(project.getWorkspaceProject()), project);
            return file;
        }
    }

    public static boolean importFromFile(DtGranularEditorEmbeddedEditorPage<?> page, File file)
    {
        // com._1c.g5.v8.dt.dcs.ui.datasets.DataSetsLoadHandler.DataSetsLoadHandler()
        DataCompositionSchemaEditor dcsEditor = (DataCompositionSchemaEditor) page.getEmbeddedEditor();
        Object editor = Global.getField(page, "editor");
        Object BmModel = Global.getField(editor, "bmModel");
        IDtProject project = (IDtProject)Global.getField(BmModel, "project");
        IV8ProjectManager projectManager = (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
        IV8Project v8Project = projectManager.getProject(project);
        Version version = v8Project.getVersion();
        DcsV8Serializer serializer = new DcsV8Serializer(project, version, resourceLookup());
        try (FileInputStream fis = new FileInputStream(file))
        {
            final DataCompositionSchema schemaNew = serializer.deserializeXML(fis);
            final DataCompositionSchema schemaOld = (DataCompositionSchema) dcsEditor.getModel();
            IBmEditingContext editingContext = dcsEditor.getEditingContext();
            editingContext.execute(new AbstractBmTask<Object>("DataSetsLoadHandler merge task via reflection") {
                @Override
                public Void execute(IBmTransaction transaction, IProgressMonitor progressMonitor) {
                    try {
                        DataCompositionSchema schemaOldTransactional = (DataCompositionSchema) transaction.toTransactionObject(schemaOld);
                        Global.invoke(DataSetsLoadHandler.class, "replaceContents", schemaOldTransactional, schemaNew);
                    } catch (Exception e) {
                        throw new RuntimeException("Ошибка при рефлексивном вызове replaceContents", e);
                    }
                    return null;
                }
            });
            dcsEditor.notify(new DcsEvent(DcsEventType.EDITOR_SCHEMA_LOADED, schemaOld));
            return false;
        }
        catch (Exception e)
        {
            Global.logError("DataCompositionSchema", "reload schema", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return true;
    }

    private ToolBar findToolbar(Composite container) {
        for (Control child : container.getChildren()) {
            ToolBar toolbar = null;
            if (child instanceof ToolBar) {
                toolbar = (ToolBar) child;
            }
            else if (child instanceof Composite) {
                toolbar = findToolbar((Composite) child);
            }
            if (toolbar != null)
            {
                ToolItem[] items = toolbar.getItems();
                for (ToolItem item : items)
                {
                    String text = item.getToolTipText();
                    if (text != null && text.startsWith("Загрузить"))
                    {
                        return toolbar;
                    }
                } 
            }
        }
        return null;        
    }
    
    private class PageChangeListener implements IPageChangedListener
    {
        @Override
        public void pageChanged(PageChangedEvent event)
        {
            Object page = event.getSelectedPage();
            if (page instanceof DtGranularEditorEmbeddedEditorPage)
                applyPatchToEditorPage((DtGranularEditorEmbeddedEditorPage<?>) page);
        }
    }

    // =======================================================================
    // Вычисленный тип в колонке «Тип значения»
    // =======================================================================

    /**
     * Показывает тусклым цветом вычисленный тип поля в колонке «Тип значения», если явный тип
     * не задан: поля наборов данных (все шесть просмотрщиков страницы «Наборы данных») и
     * вычисляемые поля.
     *
     * <p>Тип берётся тем же расчётом, что и дерево доступных полей на вкладке «Настройки» —
     * {@link DcsAvailableSettingsSourceForSchema}. Готовый источник вкладки «Настройки» не
     * годится: он пересчитывается только при её активации, поэтому строим свой, в фоновой
     * задаче (как и сама EDT), по изменениям модели схемы и переключению вкладок.
     *
     * <p>Текст ставится в ячейку провайдером надписей колонки, поэтому штатное Ctrl+C
     * ({@code DefaultCopyTextCommandHandler} берёт текст ячейки Grid) его копирует, а редактор
     * ячейки показывает значение модели (пустое) — в режиме редактирования подсказка пропадает.
     */
    private static final class ComputedValueTypes
    {
        private static final String INSTALLED_KEY = "tormozit.dcs.computedValueTypes"; //$NON-NLS-1$
        private static final int RECOMPUTE_DELAY_MS = 400;
        private static final int EDITOR_ACTIVE_RETRY_MS = 500;

        /** Поля страницы {@code DataSets} с просмотрщиками полей (пакет {@code ...datasets.fields} не экспортирован). */
        private static final String[] DATASET_FIELD_VIEWERS = { "queryFieldsFullViewer", //$NON-NLS-1$
            "queryFieldsInUnionViewer", "objectFieldsFullViewer", "objectFieldsInUnionViewer", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "unionFieldsFullViewer", "unionFieldsInUnionViewer" }; //$NON-NLS-1$ //$NON-NLS-2$
        private static final String FIELDS_COLUMN_ENUM =
            "com._1c.g5.v8.dt.dcs.ui.datasets.fields.DataSetsFieldsViewerBase$FieldsColumn"; //$NON-NLS-1$
        /** {@code CalculatedFields.VALUE_TYPE_COL_INDEX}. */
        private static final int CALCULATED_VALUE_TYPE_COLUMN = 9;

        /**
         * Назначения полей, в которых ищем поле. Первое — выбор; остальные — для полей, которым
         * ограничение использования закрывает выбор.
         */
        private static final DcsAvailableSettingsSourceForSchema.FieldUse[] FIELD_USES = {
            DcsAvailableSettingsSourceForSchema.FieldUse.eSelect,
            DcsAvailableSettingsSourceForSchema.FieldUse.eFilter,
            DcsAvailableSettingsSourceForSchema.FieldUse.eOrder,
            DcsAvailableSettingsSourceForSchema.FieldUse.eGroupping,
            DcsAvailableSettingsSourceForSchema.FieldUse.eCalculatedFieldExpression };

        private final DataCompositionSchemaControlContext context;
        private final Display display;
        private final List<ColumnViewer> viewers = new ArrayList<>();
        private final Job job;
        private IBmAsyncEventListener bmListener;
        private URI schemaUri;
        /** Ключ — {@link #pathKey} пути данных поля, значение — представление типа. */
        private volatile Map<String, String> types = Map.of();
        private boolean refreshPending;

        private ComputedValueTypes(DataCompositionSchemaControlContext context, Display display)
        {
            this.context = context;
            this.display = display;
            this.job = new Job("Типы полей схемы компоновки") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    recompute();
                    return Status.OK_STATUS;
                }
            };
            job.setSystem(true);
        }

        static void install(DataCompositionSchemaEditor dcsEditor)
        {
            Composite dataSetsPage = null;
            Composite calculatedPage = null;
            for (EditorPage editorPage : dcsEditor.getPages())
            {
                if (!(editorPage instanceof Composite composite) || composite.isDisposed())
                    continue;
                String name = editorPage.getClass().getSimpleName();
                if ("DataSets".equals(name)) //$NON-NLS-1$
                    dataSetsPage = composite;
                else if ("CalculatedFields".equals(name)) //$NON-NLS-1$
                    calculatedPage = composite;
            }
            if (dataSetsPage == null || dataSetsPage.getData(INSTALLED_KEY) != null)
                return;
            DataCompositionSchemaControlContext context = dcsEditor.getControlContext();
            if (context == null)
                return;
            ComputedValueTypes instance = new ComputedValueTypes(context, dataSetsPage.getDisplay());
            dataSetsPage.setData(INSTALLED_KEY, instance);
            for (String fieldName : DATASET_FIELD_VIEWERS)
                instance.wrapDataSetFieldsViewer(Global.getField(dataSetsPage, fieldName));
            if (calculatedPage != null
                && Global.invoke(calculatedPage, "getViewer") instanceof ColumnViewer viewer) //$NON-NLS-1$
                instance.wrapColumn(viewer, CALCULATED_VALUE_TYPE_COLUMN);
            if (dataSetsPage.getParent() instanceof CTabFolder tabs)
                tabs.addListener(SWT.Selection, e -> instance.schedule());
            instance.hookBmChanges();
            dataSetsPage.addDisposeListener(e -> instance.dispose());
            instance.schedule();
        }

        private void wrapDataSetFieldsViewer(Object fieldsViewer)
        {
            if (fieldsViewer == null
                || !(Global.invoke(fieldsViewer, "getViewer") instanceof ColumnViewer viewer)) //$NON-NLS-1$
                return;
            Object column = fieldsColumn(fieldsViewer, "VALUE_TYPE_COL_INDEX"); //$NON-NLS-1$
            if (column != null && Global.invoke(fieldsViewer, "getColumnIndex", column) instanceof Integer index) //$NON-NLS-1$
                wrapColumn(viewer, index);
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private static Object fieldsColumn(Object fieldsViewer, String name)
        {
            try
            {
                Class<?> enumClass =
                    Class.forName(FIELDS_COLUMN_ENUM, false, fieldsViewer.getClass().getClassLoader());
                return Enum.valueOf((Class<Enum>) enumClass, name);
            }
            catch (Exception | LinkageError e)
            {
                return null;
            }
        }

        /**
         * Заменяет провайдер надписей колонки обёрткой. {@code ColumnViewer.getViewerColumn} и
         * {@code ViewerColumn.getLabelProvider} в JFace пакетные — через {@link Global#invoke}.
         *
         * <p>{@code ViewerColumn.setLabelProvider} вызывает {@code dispose} у прежнего провайдера,
         * а он у EDT общий для всех колонок: {@code BaseLabelProvider.dispose} только снимает его
         * слушателей изменения надписей. Провайдеры полей наборов и вычисляемых полей таких
         * событий не шлют, так что остальные колонки ничего не теряют.
         */
        private void wrapColumn(ColumnViewer viewer, int columnIndex)
        {
            if (viewer.getControl() == null || viewer.getControl().isDisposed())
                return;
            if (!(Global.invoke(viewer, "getViewerColumn", columnIndex) instanceof ViewerColumn viewerColumn)) //$NON-NLS-1$
                return;
            Object current = Global.invoke(viewerColumn, "getLabelProvider"); //$NON-NLS-1$
            if (!(current instanceof CellLabelProvider delegate) || current instanceof HintLabelProvider)
                return;
            viewerColumn.setLabelProvider(new HintLabelProvider(delegate, this));
            viewers.add(viewer);
        }

        private void hookBmChanges()
        {
            IBmModel bmModel = context.getBmModel();
            DataCompositionSchema schema = context.getDataCompositionSchema();
            Resource resource = schema != null ? schema.eResource() : null;
            if (bmModel == null || resource == null)
                return;
            schemaUri = resource.getURI();
            bmListener = this::onBmEvent;
            bmModel.addAsyncEventListener(bmListener, BmEventFilter.changeFilter());
        }

        /** Как {@code DataCompositionSchemaEditor.handleAsyncEvent}: изменения ресурса схемы. */
        private void onBmEvent(BmEvent event)
        {
            if (event.getChangeEvents() == null)
                return;
            for (BmChangeEvent change : event.getChangeEvents().values())
            {
                EObject object = change.getObject();
                Resource resource = object != null ? object.eResource() : null;
                if (resource != null && Objects.equals(resource.getURI(), schemaUri))
                {
                    schedule();
                    return;
                }
            }
        }

        private void schedule()
        {
            job.schedule(RECOMPUTE_DELAY_MS);
        }

        private void dispose()
        {
            job.cancel();
            IBmModel bmModel = context.getBmModel();
            if (bmListener != null && bmModel != null)
                bmModel.removeAsyncEventListener(bmListener);
            bmListener = null;
        }

        String typeFor(String dataPath)
        {
            return dataPath == null ? null : types.get(pathKey(dataPath));
        }

        /** Фоновый расчёт — как {@code Settings.refreshAvailableFieldsSource}. */
        private void recompute()
        {
            Map<String, String> result = new HashMap<>();
            Set<String> paths = new LinkedHashSet<>();
            try
            {
                DataCompositionSchema schema = context.getDataCompositionSchema();
                IV8Project v8project = context.getV8project();
                if (schema == null || v8project == null)
                    return;
                collectPaths(schema, paths);
                if (!paths.isEmpty())
                {
                    int alias = v8project.getScriptVariant().getValue();
                    SettingsContext settingsContext = new SettingsContext(v8project.getDtProject(),
                        context.getBmModel(), context.getMdTypeIndex(), context.getEmfIndexManager());
                    DcsAvailableSettingsSourceForSchema source =
                        new DcsAvailableSettingsSourceForSchema(settingsContext);
                    source.init(v8project, schema, context.getCurrentLanguageCode(), alias,
                        context.getVersion());
                    Map<String, DcsAvailableFieldInfo> infos = findFieldInfos(source, paths);
                    for (String path : paths)
                    {
                        String text = typeText(infos.get(pathKey(path)), alias);
                        if (text != null)
                            result.put(pathKey(path), text);
                    }
                }
            }
            catch (Exception | LinkageError e)
            {
                // Схема в промежуточном состоянии — прежние типы остаются до следующего пересчёта.
                return;
            }
            if (result.equals(types))
                return;
            types = Map.copyOf(result);
            if (!display.isDisposed())
                display.asyncExec(this::refreshViewers);
        }

        /** Пути данных полей без явного типа: поля всех наборов (с вложенными в объединения) и вычисляемые поля. */
        private static void collectPaths(DataCompositionSchema schema, Set<String> paths)
        {
            for (DataSet dataSet : schema.getDataSets())
                collectDataSetPaths(dataSet, paths);
            for (DataCompositionSchemaCalculatedField field : schema.getCalculatedFields())
                if (isEmpty(field.getValueType()) && field.getDataPath() != null)
                    paths.add(field.getDataPath());
        }

        private static void collectDataSetPaths(DataSet dataSet, Set<String> paths)
        {
            for (DataSetField field : dataSet.getFields())
                if (field instanceof DataCompositionSchemaDataSetField dataSetField
                    && isEmpty(dataSetField.getValueType()) && dataSetField.getDataPath() != null)
                    paths.add(dataSetField.getDataPath());
            if (dataSet instanceof DataCompositionSchemaDataSetUnion union)
                for (DataSet item : union.getItems())
                    collectDataSetPaths(item, paths);
        }

        private static boolean isEmpty(TypeDescription type)
        {
            return type == null || type.getTypes().isEmpty();
        }

        /**
         * Как {@code DcsAvailableFields.findField}: доступные поля берутся списком потомков
         * родительского пути, по одному запросу на родителя и назначение.
         */
        private static Map<String, DcsAvailableFieldInfo> findFieldInfos(
            DcsAvailableSettingsSourceForSchema source, Set<String> paths)
        {
            Map<String, DcsAvailableFieldInfo> infos = new HashMap<>();
            Set<String> loadedParents = new HashSet<>();
            for (DcsAvailableSettingsSourceForSchema.FieldUse use : FIELD_USES)
            {
                for (String path : paths)
                {
                    if (infos.containsKey(pathKey(path)))
                        continue;
                    try
                    {
                        DcsPath parent = new DcsPath(path).nearParentPath();
                        if (!loadedParents.add(use + "|" + parent)) //$NON-NLS-1$
                            continue;
                        List<DcsAvailableFieldInfo> children = new ArrayList<>();
                        source.getChildFields(new DcsPath(), use, parent, children, 1,
                            DcsAvailableSettingsSourceForSchema.OrderType.eOBTitle, false);
                        for (DcsAvailableFieldInfo info : children)
                            if (info.dataPath != null)
                                for (DcsPath dataPath : info.dataPath)
                                    if (dataPath != null)
                                        infos.putIfAbsent(pathKey(dataPath.toString()), info);
                    }
                    catch (Exception e)
                    {
                        // Путь, который источник не разбирает, — просто без подсказки.
                    }
                }
            }
            return infos;
        }

        /** Представление как у штатной колонки: имена типов через запятую, без квалификаторов. */
        private static String typeText(DcsAvailableFieldInfo info, int alias)
        {
            if (info == null || info.isFolder)
                return null;
            TypeDescriptionSource valueType = info.valueType;
            if (valueType == null || valueType.isEmpty())
                return null;
            StringBuilder text = new StringBuilder();
            for (TypeItem type : valueType.getTypes())
            {
                String name = alias == 0 ? McoreUtil.getTypeName(type) : McoreUtil.getTypeNameRu(type);
                if (name == null || name.isEmpty())
                    continue;
                if (text.length() > 0)
                    text.append(", "); //$NON-NLS-1$
                text.append(name);
            }
            return text.length() > 0 ? text.toString() : null;
        }

        private static String pathKey(String dataPath)
        {
            String normalized = dataPath;
            try
            {
                normalized = new DcsPath(dataPath).toString();
            }
            catch (Exception e)
            {
                // Остаётся исходная строка.
            }
            return normalized.toLowerCase(Locale.ROOT);
        }

        /** Идущее редактирование ячейки обновление просмотрщика оборвало бы — откладываем. */
        private void refreshViewers()
        {
            refreshPending = false;
            for (ColumnViewer viewer : viewers)
            {
                if (viewer.getControl() == null || viewer.getControl().isDisposed())
                    continue;
                if (viewer.isCellEditorActive())
                {
                    refreshPending = true;
                    continue;
                }
                viewer.refresh();
            }
            if (refreshPending && !display.isDisposed())
                display.timerExec(EDITOR_ACTIVE_RETRY_MS, this::refreshViewers);
        }
    }

    /**
     * Провайдер надписей колонки «Тип значения»: штатный текст, а при пустом — вычисленный тип
     * тусклым цветом. {@code dispose} штатному не передаёт — он общий для всех колонок и
     * освобождается их собственными {@code ViewerColumn}.
     */
    private static final class HintLabelProvider extends CellLabelProvider
    {
        private final CellLabelProvider delegate;
        private final ComputedValueTypes types;

        HintLabelProvider(CellLabelProvider delegate, ComputedValueTypes types)
        {
            this.delegate = delegate;
            this.types = types;
        }

        @Override
        public void update(ViewerCell cell)
        {
            delegate.update(cell);
            String text = cell.getText();
            if (text != null && !text.isEmpty())
                return;
            Object element = cell.getElement();
            String dataPath = element instanceof DataCompositionSchemaDataSetField dataSetField
                ? dataSetField.getDataPath()
                : element instanceof DataCompositionSchemaCalculatedField calculatedField
                    ? calculatedField.getDataPath() : null;
            String hint = types.typeFor(dataPath);
            if (hint == null)
                return;
            cell.setText(hint);
            cell.setForeground(ThemeAwareColors.effectiveSystemColor(
                cell.getControl().getDisplay(), SWT.COLOR_DARK_GRAY));
        }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            return delegate.isLabelProperty(element, property);
        }

        @Override
        public String getToolTipText(Object element)
        {
            return delegate.getToolTipText(element);
        }

        @Override
        public Image getToolTipImage(Object element)
        {
            return delegate.getToolTipImage(element);
        }

        @Override
        public Color getToolTipBackgroundColor(Object element)
        {
            return delegate.getToolTipBackgroundColor(element);
        }

        @Override
        public Color getToolTipForegroundColor(Object element)
        {
            return delegate.getToolTipForegroundColor(element);
        }

        @Override
        public Font getToolTipFont(Object element)
        {
            return delegate.getToolTipFont(element);
        }

        @Override
        public Point getToolTipShift(Object element)
        {
            return delegate.getToolTipShift(element);
        }

        @Override
        public boolean useNativeToolTip(Object element)
        {
            return delegate.useNativeToolTip(element);
        }

        @Override
        public int getToolTipTimeDisplayed(Object element)
        {
            return delegate.getToolTipTimeDisplayed(element);
        }

        @Override
        public int getToolTipDisplayDelayTime(Object element)
        {
            return delegate.getToolTipDisplayDelayTime(element);
        }

        @Override
        public int getToolTipStyle(Object element)
        {
            return delegate.getToolTipStyle(element);
        }
    }
}
