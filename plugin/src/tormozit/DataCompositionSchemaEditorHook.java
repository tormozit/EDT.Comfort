package tormozit;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLStreamException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.ui.IEditorInput;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.XtextSourceViewer;
import org.eclipse.xtext.ui.editor.embedded.EmbeddedEditor;
import com._1c.g5.v8.dt.common.ui.widgets.tableex.TableExTreeViewer;
import com._1c.g5.v8.dt.ql.model.QlPackage;
import com._1c.g5.v8.dt.ql.model.QuerySchema;
import com._1c.g5.v8.dt.ql.model.QuerySchemaExpression;
import com._1c.g5.v8.dt.ql.model.QuerySchemaSelectQuery;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.ViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
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
import org.osgi.framework.Bundle;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmEditingContext;
import com._1c.g5.v8.dt.common.PreferenceUtils;
import com._1c.g5.v8.dt.common.ui.widgets.tableex.TableEx;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.core.V8Commands;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetObject;
import com._1c.g5.v8.dt.dcs.model.schema.DcsPackage;
import com._1c.g5.v8.dt.dcs.ui.DataCompositionSchemaControlContext;
import com._1c.g5.v8.dt.dcs.ui.DataCompositionSchemaEditor;
import com._1c.g5.v8.dt.dcs.ui.DcsEvent;
import com._1c.g5.v8.dt.dcs.ui.DcsEvent.DcsEventType;
import com._1c.g5.v8.dt.dcs.ui.EditorPage;
import com._1c.g5.v8.dt.dcs.ui.datasets.DataSets;
import com._1c.g5.v8.dt.dcs.ui.datasets.DataSetsLoadHandler;
import com._1c.g5.v8.dt.dcs.util.DcsV8Serializer;
import com._1c.g5.v8.dt.export.ExportException;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorEmbeddedEditorPage;
import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.platform.version.Version;
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
            // Не getEditors(): он создаёт все восстановленные вкладки, а проект при старте ещё не поднят.
            for (org.eclipse.ui.IEditorReference ref : window.getActivePage().getEditorReferences())
            {
                IEditorPart ed = EDITOR_ID.equals(ref.getId()) ? ref.getEditor(false) : null;
                if (ed != null)
                    applyPatchToGranularEditor((DtGranularEditor<?>) ed);
            }

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
        TableExHeaderAccent.install(dcsEditor.getControlContext());
        ColumnWidths.install(dcsEditor.getControlContext());
        QueryOptionsLayout.install(dcsEditor.getControlContext());
        DcsComputedValueTypes.install(dcsEditor.getControlContext());
        FieldTypeAutofill.install(dcsEditor.getControlContext());
        QueryFieldNavigation.install(dcsEditor.getControlContext());
        DataSetPositionMemory.install(page, dcsEditor);
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
    
    /**
     * Автоподбор типа значения по имени поля набора данных «Объект» (таблицы полей страницы
     * «Наборы данных»; у наборов-запросов тип выводится из запроса). После правки колонки «Поле»
     * или «Путь», если у поля тип ещё не задан, подбирает его так же, как для реквизитов
     * ({@link TypeByNameAdvisor}): совпадение имени с объектом метаданных, иначе правила имён,
     * иначе Напарник. Список доступных типов — тот же, что показывает диалог выбора типа ячейки
     * («Тип значения»): {@code TypeProviderService.getTypeDescriptionInfoWithTypeInfo}.
     *
     * <p>Правку ловим обёрткой штатного {@link EditingSupport} колонки: так срабатывает только
     * ввод пользователем в таблице, а не любое изменение модели (загрузка схемы, обмен с ИР).
     * Прежний {@code EditingSupport} берётся из приватного поля {@code ViewerColumn.editingSupport}.
     */
    private static final class FieldTypeAutofill
    {
        private static final String INSTALLED_KEY = "tormozit.dcs.fieldTypeAutofill"; //$NON-NLS-1$
        private static final String[] OBJECT_FIELD_VIEWERS =
            { "objectFieldsFullViewer", "objectFieldsInUnionViewer" }; //$NON-NLS-1$ //$NON-NLS-2$
        private static final String[] NAME_COLUMNS = { "FIELD_COL_INDEX", "PATH_COL_INDEX" }; //$NON-NLS-1$ //$NON-NLS-2$
        private static final String TYPE_PROVIDER_BUNDLE = "com._1c.g5.v8.dt.platform.core"; //$NON-NLS-1$
        private static final String TYPE_PROVIDER_SERVICE =
            "com._1c.g5.v8.dt.platform.core.typeinfo.TypeProviderService"; //$NON-NLS-1$

        static void install(DataCompositionSchemaControlContext context)
        {
            if (context == null)
                return;
            for (EditorPage editorPage : context.getPages())
            {
                if (!(editorPage instanceof Composite page) || page.isDisposed()
                    || !"DataSets".equals(editorPage.getClass().getSimpleName()) //$NON-NLS-1$
                    || page.getData(INSTALLED_KEY) != null)
                    continue;
                page.setData(INSTALLED_KEY, Boolean.TRUE);
                for (String viewerField : OBJECT_FIELD_VIEWERS)
                    wrapViewer(context, Global.getField(page, viewerField));
            }
        }

        private static void wrapViewer(DataCompositionSchemaControlContext context, Object fieldsViewer)
        {
            if (fieldsViewer == null || !(Global.invoke(fieldsViewer, "getViewer") instanceof ColumnViewer viewer)) //$NON-NLS-1$
                return;
            for (String columnName : NAME_COLUMNS)
            {
                Object column = DcsComputedValueTypes.fieldsColumn(fieldsViewer, columnName);
                if (column == null
                    || !(Global.invoke(fieldsViewer, "getColumnIndex", column) instanceof Integer index) //$NON-NLS-1$
                    || !(Global.invoke(viewer, "getViewerColumn", index) instanceof ViewerColumn viewerColumn)) //$NON-NLS-1$
                    continue;
                if (Global.getField(viewerColumn, "editingSupport") instanceof EditingSupport original //$NON-NLS-1$
                    && !(original instanceof NameEditingSupport))
                {
                    viewerColumn.setEditingSupport(new NameEditingSupport(viewer, original, context));
                }
            }
        }

        private static boolean isEmpty(TypeDescription type)
        {
            return type == null || type.getTypes().isEmpty();
        }

        /** Всё делегируется прежнему {@code EditingSupport}; после записи имени запускается подбор типа. */
        private static final class NameEditingSupport extends EditingSupport
        {
            private final EditingSupport delegate;
            private final DataCompositionSchemaControlContext context;

            NameEditingSupport(ColumnViewer viewer, EditingSupport delegate, DataCompositionSchemaControlContext context)
            {
                super(viewer);
                this.delegate = delegate;
                this.context = context;
            }

            @Override
            protected CellEditor getCellEditor(Object element)
            {
                return Global.invoke(delegate, "getCellEditor", element) instanceof CellEditor editor ? editor : null; //$NON-NLS-1$
            }

            @Override
            protected boolean canEdit(Object element)
            {
                return Boolean.TRUE.equals(Global.invoke(delegate, "canEdit", element)); //$NON-NLS-1$
            }

            @Override
            protected Object getValue(Object element)
            {
                return Global.invoke(delegate, "getValue", element); //$NON-NLS-1$
            }

            @Override
            protected void setValue(Object element, Object value)
            {
                String before = text(Global.invoke(delegate, "getValue", element)); //$NON-NLS-1$
                Global.invokeVoid(delegate, "setValue", element, value); //$NON-NLS-1$
                String name = text(value);
                if (name == null || name.equals(before) || !(element instanceof DataCompositionSchemaDataSetField field)
                    || !(field.eContainer() instanceof DataCompositionSchemaDataSetObject))
                    return;
                String trimmed = name.trim();
                // Вложенные поля («Реквизит.Поле») и пустое имя — не про этот подбор; запись в
                // модель завершается вне обработчика ячейки.
                if (trimmed.isEmpty() || trimmed.indexOf('.') >= 0 || !isEmpty(field.getValueType()))
                    return;
                Display display = Display.getCurrent();
                if (display != null)
                    display.asyncExec(() -> suggestType(field, trimmed));
            }

            private static String text(Object value)
            {
                return value instanceof StringValue stringValue ? stringValue.getValue() : null;
            }

            private void suggestType(DataCompositionSchemaDataSetField field, String name)
            {
                if (field.eResource() == null || !isEmpty(field.getValueType()))
                    return;
                IV8Project v8Project = context.getV8project();
                IDtProject project = v8Project != null ? v8Project.getDtProject() : null;
                TypeByNameAdvisor.TypeCatalog catalog = availableTypes(field);
                if (project == null || catalog == null)
                {
                    return;
                }
                String source = TypeByNameAdvisor.availableSource(project, catalog, name);
                if (source == null)
                    return;
                TypeByNameAdvisor.suggest(project, catalog, name,
                    () -> field.eResource() != null && isEmpty(field.getValueType()),
                    typeName -> applyType(field, catalog, typeName, source));
            }

            private void applyType(DataCompositionSchemaDataSetField field, TypeByNameAdvisor.TypeCatalog catalog,
                String typeName, String source)
            {
                Object item = TypeByNameAdvisor.findTypeItem(catalog, typeName);
                if (!(item instanceof TypeItem typeItem))
                    return;
                TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
                description.getTypes().add(typeItem);
                V8Commands.executeSet(context.getEditingContext(), field,
                    DcsPackage.Literals.DATA_COMPOSITION_SCHEMA_DATA_SET_FIELD__VALUE_TYPE, description);
                Global.log("DcsFieldTypeAutofill", "Тип подобран через " + source + ": " + typeName); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }

            /** Типы, доступные полю: как в диалоге выбора типа ячейки «Тип значения»; {@code null} — получить не удалось. */
            private static TypeByNameAdvisor.TypeCatalog availableTypes(DataCompositionSchemaDataSetField field)
            {
                try
                {
                    Bundle bundle = Platform.getBundle(TYPE_PROVIDER_BUNDLE);
                    if (bundle == null)
                        return null;
                    Object service = bundle.loadClass(TYPE_PROVIDER_SERVICE).getField("INSTANCE").get(null); //$NON-NLS-1$
                    Object info = Global.invoke(service, "getTypeDescriptionInfoWithTypeInfo", field, //$NON-NLS-1$
                        field.eContainer(), DcsPackage.Literals.DATA_COMPOSITION_SCHEMA_DATA_SET_FIELD__VALUE_TYPE, null);
                    if (!(Global.invoke(info, "getTypeInfos") instanceof Iterable<?> typeInfos)) //$NON-NLS-1$
                        return null;
                    List<TypeItem> items = new ArrayList<>();
                    for (Object typeInfo : typeInfos)
                    {
                        if (!(Global.invoke(typeInfo, "getType") instanceof TypeItem item)) //$NON-NLS-1$
                            continue;
                        if (item.eIsProxy() && EcoreUtil.resolve(item, field) instanceof TypeItem resolved)
                            item = resolved;
                        items.add(item);
                    }
                    return items.isEmpty() ? null : new TypeByNameAdvisor.TypeCatalog(items);
                }
                catch (ReflectiveOperationException | RuntimeException e)
                {
                    return null;
                }
            }
        }
    }

    /** Ширины таблиц конструктора, общие для схем, но отдельные для каждой страницы и таблицы. */
    /**
     * Переход между псевдонимом поля в тексте последнего запроса набора данных и полем набора
     * данных (#1005). Ctrl+щелчок по псевдониму в списке выбираемых полей последнего запроса
     * выделяет одноимённое поле в таблице полей; двойной щелчок по полю набора данных выделяет
     * его псевдоним в тексте запроса. Слушатели — display-фильтры, потому что редактор запроса
     * ({@code DataSetsQuery.qlEditor}) создаётся лениво.
     */
    static final class QueryFieldNavigation
    {
        private static final String INSTALLED_KEY = "tormozit.dcs.queryFieldNavigation"; //$NON-NLS-1$
        /** Пары «редактор запроса» / «таблица полей» страницы DataSets: полный и в объединении. */
        private static final String[][] PAIRS = {
            { "queryFullViewer", "queryFieldsFullViewer" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "queryInUnionViewer", "queryFieldsInUnionViewer" } }; //$NON-NLS-1$ //$NON-NLS-2$

        static void install(DataCompositionSchemaControlContext context)
        {
            if (context == null)
                return;
            for (EditorPage editorPage : context.getPages())
            {
                if (!(editorPage instanceof Composite page) || page.isDisposed()
                    || !"DataSets".equals(editorPage.getClass().getSimpleName()) //$NON-NLS-1$
                    || page.getData(INSTALLED_KEY) != null)
                    continue;
                page.setData(INSTALLED_KEY, Boolean.TRUE);
                Display display = page.getDisplay();
                Listener filter = event -> onEvent(page, event);
                display.addFilter(SWT.MouseDown, filter);
                display.addFilter(SWT.MouseDoubleClick, filter);
                page.addListener(SWT.Dispose, event ->
                {
                    display.removeFilter(SWT.MouseDown, filter);
                    display.removeFilter(SWT.MouseDoubleClick, filter);
                });
            }
        }

        private static void onEvent(Composite page, Event event)
        {
            if (page.isDisposed() || !(event.widget instanceof Control control) || control.isDisposed())
                return;
            for (String[] pair : PAIRS)
            {
                Object queryComposite = Global.getField(page, pair[0]);
                Object fieldsViewer = Global.getField(page, pair[1]);
                Object fieldsTree = fieldsViewer == null ? null : Global.invoke(fieldsViewer, "getViewer"); //$NON-NLS-1$
                Object editorObj = queryComposite == null ? null : Global.invoke(queryComposite, "getQueryEditor"); //$NON-NLS-1$
                if (queryComposite == null || fieldsViewer == null
                    || !(fieldsTree instanceof TableExTreeViewer fields))
                    continue;
                EmbeddedEditor editor = editorObj instanceof EmbeddedEditor found ? found : null;
                if (editor == null || editor.getViewer() == null)
                    continue;
                if (event.type == SWT.MouseDown)
                {
                    if (control == editor.getViewer().getTextWidget())
                    {
                        if (event.button == 1 && (event.stateMask & SWT.MOD1) != 0
                            && selectFieldByAlias(editor, fields, event))
                            event.doit = false;
                        return;
                    }
                }
                else if (isInside(control, fields.getControl()))
                {
                    if (event.button == 1
                        && fieldNameCellAt(fieldsViewer, fields, control, event) instanceof DataCompositionSchemaDataSetField field
                        && selectAliasByField(editor, field))
                        event.doit = false;
                    return;
                }
            }
        }

        private static boolean isInside(Control control, Control root)
        {
            for (Control current = control; current != null; current = current.getParent())
                if (current == root)
                    return true;
            return false;
        }

        private static boolean selectFieldByAlias(EmbeddedEditor editor, TableExTreeViewer fields, Event event)
        {
            XtextSourceViewer viewer = editor.getViewer();
            int widgetOffset = viewer.getTextWidget().getOffsetAtPoint(new Point(event.x, event.y));
            if (widgetOffset < 0)
                return false;
            int offset = viewer instanceof ITextViewerExtension5 extension
                ? extension.widgetOffset2ModelOffset(widgetOffset) : widgetOffset;
            String alias = editor.getDocument().readOnly(resource -> aliasAt(resource, offset));
            if (alias == null)
                return false;
            DataCompositionSchemaDataSetField field = findField(fields, alias);
            if (field == null)
                return false;
            fields.setSelection(new StructuredSelection(field), true);
            fields.getControl().setFocus();
            return true;
        }

        /**
         * Элемент строки, если двойной щелчок пришёлся на колонку «Поле» и штатно эта ячейка не
         * правится (у полей набора-запроса имя задаёт запрос, {@code DataSetsFieldsEditingSupport.canEdit}
         * → {@code false}). Иначе {@code null} — двойной щелчок остаётся штатным (правка ячейки).
         *
         * <p>Запись {@link TableEx} занимает несколько строк {@code Grid} («Путь» над «Автозаголовком»),
         * поэтому индекс колонки {@code Grid} ≠ индексу колонки просмотрщика, а
         * {@code ColumnViewer.getCell} на нижних строках записи отдаёт {@code null}. Перевод —
         * штатными {@code TableEx.getColumnIndexByGridColumn} / {@code getItemByGridItem}.
         */
        private static Object fieldNameCellAt(Object fieldsViewer, TableExTreeViewer fields, Control control, Event event)
        {
            if (!(control instanceof org.eclipse.nebula.widgets.grid.Grid grid)
                || !(fields.getControl() instanceof TableEx table))
                return null;
            Point point = new Point(event.x, event.y);
            org.eclipse.nebula.widgets.grid.GridItem gridItem = grid.getItem(point);
            org.eclipse.nebula.widgets.grid.GridColumn gridColumn = grid.getColumn(point);
            if (gridItem == null || gridColumn == null)
                return null;
            int index = table.getColumnIndexByGridColumn(gridItem, grid.indexOf(gridColumn));
            Object nameColumn = DcsComputedValueTypes.fieldsColumn(fieldsViewer, "FIELD_COL_INDEX"); //$NON-NLS-1$
            Object item = table.getItemByGridItem(gridItem);
            Object element = item instanceof org.eclipse.swt.widgets.Item swtItem ? swtItem.getData() : null;
            boolean nameCell = nameColumn != null
                && Global.invoke(fieldsViewer, "getColumnIndex", nameColumn) instanceof Integer nameIndex //$NON-NLS-1$
                && nameIndex == index;
            Object canEdit = null;
            if (nameCell && Global.invoke(fields, "getViewerColumn", index) instanceof ViewerColumn column //$NON-NLS-1$
                && Global.getField(column, "editingSupport") instanceof EditingSupport support) //$NON-NLS-1$
                canEdit = Global.invoke(support, "canEdit", element); //$NON-NLS-1$
            return nameCell && !Boolean.TRUE.equals(canEdit) ? element : null;
        }

        private static boolean selectAliasByField(EmbeddedEditor editor, DataCompositionSchemaDataSetField field)
        {
            if (field.getField() == null)
                return false;
            String name = field.getField();
            int[] range = editor.getDocument().readOnly(resource -> aliasRange(resource, name));
            if (range == null)
                return false;
            XtextSourceViewer viewer = editor.getViewer();
            viewer.setSelectedRange(range[0], range[1]);
            viewer.revealRange(range[0], range[1]);
            viewer.getTextWidget().setFocus();
            return true;
        }

        /** Поля выборки последнего запроса пакета (имена результата задаёт первое объединяемое выражение). */
        private static List<QuerySchemaExpression> lastQueryFields(XtextResource resource)
        {
            if (resource == null || resource.getContents().isEmpty())
                return List.of();
            EObject root = resource.getContents().get(0);
            QuerySchema schema = root instanceof QuerySchema direct ? direct
                : EcoreUtil2.getAllContentsOfType(root, QuerySchema.class).stream().findFirst().orElse(null);
            if (schema == null || schema.getQueries().isEmpty()
                || !(schema.getQueries().get(schema.getQueries().size() - 1) instanceof QuerySchemaSelectQuery select)
                || select.getOperators().isEmpty())
                return List.of();
            return select.getOperators().get(0).getSelectFields();
        }

        private static String aliasAt(XtextResource resource, int offset)
        {
            if (resource == null || resource.getParseResult() == null)
                return null;
            ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset);
            if (leaf == null)
                return null;
            QuerySchemaExpression expression = EcoreUtil2
                .getContainerOfType(NodeModelUtils.findActualSemanticObjectFor(leaf), QuerySchemaExpression.class);
            if (expression == null || expression.getAlias() == null
                || !lastQueryFields(resource).contains(expression))
                return null;
            for (INode node : NodeModelUtils.findNodesForFeature(expression, QlPackage.Literals.QUERY_SCHEMA_EXPRESSION__ALIAS))
                if (offset >= node.getOffset() && offset <= node.getEndOffset())
                    return expression.getAlias();
            return null;
        }

        private static int[] aliasRange(XtextResource resource, String name)
        {
            for (QuerySchemaExpression expression : lastQueryFields(resource))
            {
                if (!name.equalsIgnoreCase(expression.getAlias()))
                    continue;
                List<INode> nodes = NodeModelUtils.findNodesForFeature(expression,
                    QlPackage.Literals.QUERY_SCHEMA_EXPRESSION__ALIAS);
                if (!nodes.isEmpty())
                    return new int[] { nodes.get(0).getOffset(), nodes.get(0).getLength() };
            }
            return null;
        }

        private static DataCompositionSchemaDataSetField findField(TableExTreeViewer viewer, String alias)
        {
            if (!(viewer.getContentProvider() instanceof ITreeContentProvider provider))
                return null;
            return findField(provider, provider.getElements(viewer.getInput()), alias);
        }

        private static DataCompositionSchemaDataSetField findField(ITreeContentProvider provider, Object[] elements,
            String alias)
        {
            if (elements == null)
                return null;
            for (Object element : elements)
            {
                if (element instanceof DataCompositionSchemaDataSetField field && alias.equalsIgnoreCase(field.getField()))
                    return field;
                DataCompositionSchemaDataSetField nested = findField(provider, provider.getChildren(element), alias);
                if (nested != null)
                    return nested;
            }
            return null;
        }
    }

    /**
     * Запоминает последний активный набор данных схемы и позицию каретки в тексте его запроса —
     * по аналогии с {@link BslModulePositionMemoryHook}. Хранение между сеансами EDT —
     * {@link EditorMemoryStore}, ключ — файл макета. Страница наборов данных заполняется и
     * создаёт редактор запроса лениво, поэтому восстановление идёт короткими повторами.
     */
    private static final class DataSetPositionMemory
    {
        private static final String INSTALLED_KEY = "tormozit.dcs.dataSetPositionMemory"; //$NON-NLS-1$
        private static final String HOOKED_KEY = "tormozit.dcs.dataSetPositionMemory.editor"; //$NON-NLS-1$
        private static final char SEP = '\t';
        private static final int RETRY_MS = 100;
        private static final int MAX_ATTEMPTS = 100;

        private static final EditorMemoryStore STORE =
            new EditorMemoryStore("dcsDataSetPosition.entries", 300); //$NON-NLS-1$

        static void install(DtGranularEditorEmbeddedEditorPage<?> hostPage, DataCompositionSchemaEditor dcsEditor)
        {
            if (dcsEditor.getPages().isEmpty() || !(dcsEditor.getPages().get(0) instanceof DataSets dataSets)
                || dataSets.isDisposed() || dataSets.getData(INSTALLED_KEY) != null)
                return;
            IEditorInput input = hostPage.getEditor() == null ? null : hostPage.getEditor().getEditorInput();
            IFile file = input == null ? null : input.getAdapter(IFile.class);
            if (file == null)
                return;
            dataSets.setData(INSTALLED_KEY, Boolean.TRUE);
            new Session(file.getFullPath().toString(), dataSets).start();
        }

        private static final class Session
        {
            private final String key;
            private final DataSets dataSets;
            private final Display display;
            private String savedName;
            private int savedLine;
            private int savedColumn;
            /** Пока true — события выделения не перезаписывают запомненное (идёт восстановление). */
            private boolean restoring;

            Session(String key, DataSets dataSets)
            {
                this.key = key;
                this.dataSets = dataSets;
                this.display = dataSets.getDisplay();
            }

            void start()
            {
                String raw = STORE.load(key);
                if (raw != null)
                {
                    String[] parts = raw.split(String.valueOf(SEP), -1);
                    try
                    {
                        if (parts.length == 3 && !parts[0].isEmpty())
                        {
                            savedName = parts[0];
                            savedLine = Integer.parseInt(parts[1]);
                            savedColumn = Integer.parseInt(parts[2]);
                        }
                    }
                    catch (NumberFormatException ignored)
                    {
                        savedName = null;
                    }
                }
                restoring = savedName != null;
                dataSets.getDataSetsViewer().addSelectionChangedListener(event ->
                {
                    if (restoring)
                        return;
                    Object first = event.getStructuredSelection().getFirstElement();
                    if (first instanceof DataSet dataSet && dataSet.getName() != null)
                    {
                        STORE.updateMemory(key, dataSet.getName() + SEP + 0 + SEP + 0);
                        hookEditor(0);
                    }
                });
                dataSets.addListener(SWT.Dispose, event -> STORE.flush());
                dataSets.getDataSetsViewer().getControl().addListener(SWT.FocusOut, event -> STORE.flush());
                if (restoring)
                    display.asyncExec(() -> restore(0));
                else
                    hookEditor(0);
            }

            private void restore(int attempt)
            {
                if (dataSets.isDisposed())
                    return;
                TreeViewer tree = dataSets.getDataSetsViewer();
                DataSet target = tree.getInput() == null ? null : findDataSet(tree, savedName);
                if (target == null)
                {
                    if (attempt >= MAX_ATTEMPTS)
                        restoring = false;
                    else
                        display.timerExec(RETRY_MS, () -> restore(attempt + 1));
                    return;
                }
                if (dataSets.getCurrentDataSet() != target)
                    tree.setSelection(new StructuredSelection(target), true);
                restoreCaret(target, attempt);
            }

            private void restoreCaret(DataSet target, int attempt)
            {
                if (dataSets.isDisposed())
                    return;
                if (!(target instanceof DataCompositionSchemaDataSetQuery))
                {
                    restoring = false;
                    return;
                }
                EmbeddedEditor editor = dataSets.getQueryEditor();
                XtextSourceViewer viewer = editor == null ? null : editor.getViewer();
                IDocument document = viewer == null ? null : viewer.getDocument();
                DataSet current = dataSets.getCurrentDataSet();
                if (document == null || document.getLength() == 0 || current == null
                    || !java.util.Objects.equals(current.getName(), target.getName()))
                {
                    if (attempt >= MAX_ATTEMPTS)
                        restoring = false;
                    else
                        display.timerExec(RETRY_MS, () -> restoreCaret(target, attempt + 1));
                    return;
                }
                try
                {
                    int line = Math.max(0, Math.min(savedLine, document.getNumberOfLines() - 1));
                    IRegion info = document.getLineInformation(line);
                    int offset = info.getOffset() + Math.max(0, Math.min(savedColumn, info.getLength()));
                    viewer.setSelectedRange(offset, 0);
                    viewer.revealRange(offset, 0);
                    refocusAfterStockFocusReturn(viewer);
                    display.timerExec(0, () -> focusQueryText(viewer));
                }
                catch (BadLocationException ignored)
                {
                    // Текст короче запомненной позиции — остаёмся в начале.
                }
                restoring = false;
                hookEditor(0);
            }

            /**
             * Штатное отложенное обновление таблицы полей после смены набора заканчивается
             * {@code DataSets.setFocus()} (DataSetsFieldsViewerBase.lambda$6) и возвращает фокус в дерево
             * наборов уже после нашей установки. Один раз за 3 с переносим фокус обратно в текст запроса.
             */
            private void refocusAfterStockFocusReturn(XtextSourceViewer viewer)
            {
                final long until = System.currentTimeMillis() + 3000;
                final boolean[] done = { false };
                Listener[] filter = new Listener[1];
                filter[0] = event ->
                {
                    if (done[0] || System.currentTimeMillis() > until || dataSets.isDisposed()
                        || !(event.widget instanceof Control control) || control == viewer.getTextWidget())
                        return;
                    for (Control c = control; c != null; c = c.getParent())
                        if (c == dataSets)
                        {
                            done[0] = true;
                            display.removeFilter(SWT.FocusIn, filter[0]);
                            display.asyncExec(() -> focusQueryText(viewer));
                            return;
                        }
                };
                display.addFilter(SWT.FocusIn, filter[0]);
                display.timerExec(3500, () -> display.removeFilter(SWT.FocusIn, filter[0]));
            }

            /** Фокус в текст запроса, если он пуст или остался внутри страницы наборов данных. */
            private void focusQueryText(XtextSourceViewer viewer)
            {
                StyledText text = viewer.getTextWidget();
                if (dataSets.isDisposed() || text == null || text.isDisposed() || text.isFocusControl())
                    return;
                Control focused = display.getFocusControl();
                boolean focusFree = focused == null;
                for (Control c = focused; c != null && !focusFree; c = c.getParent())
                    focusFree = c == dataSets;
                if (focusFree)
                    text.setFocus();
            }

            /** Подписка на каретку редактора запроса; он создаётся лениво, поэтому с повторами. */
            private void hookEditor(int attempt)
            {
                if (dataSets.isDisposed())
                    return;
                EmbeddedEditor editor = dataSets.getQueryEditor();
                XtextSourceViewer viewer = editor == null ? null : editor.getViewer();
                if (viewer == null || viewer.getTextWidget() == null || viewer.getTextWidget().isDisposed())
                {
                    if (attempt < 30)
                        display.timerExec(RETRY_MS, () -> hookEditor(attempt + 1));
                    return;
                }
                if (viewer.getTextWidget().getData(HOOKED_KEY) != null)
                    return;
                viewer.getTextWidget().setData(HOOKED_KEY, Boolean.TRUE);
                viewer.addSelectionChangedListener(event -> rememberCaret(editor));
            }

            private void rememberCaret(EmbeddedEditor editor)
            {
                if (restoring || dataSets.isDisposed() || dataSets.getQueryEditor() != editor)
                    return;
                DataSet current = dataSets.getCurrentDataSet();
                XtextSourceViewer viewer = editor.getViewer();
                IDocument document = viewer == null ? null : viewer.getDocument();
                if (current == null || current.getName() == null || document == null)
                    return;
                try
                {
                    int offset = viewer.getSelectedRange().x;
                    int line = document.getLineOfOffset(offset);
                    int column = offset - document.getLineOffset(line);
                    STORE.updateMemory(key, current.getName() + SEP + line + SEP + column);
                }
                catch (BadLocationException ignored)
                {
                    // Документ в момент подмены текста — пропускаем событие.
                }
            }

            private static DataSet findDataSet(TreeViewer tree, String name)
            {
                if (!(tree.getContentProvider() instanceof ITreeContentProvider provider))
                    return null;
                return findDataSet(provider, provider.getElements(tree.getInput()), name);
            }

            private static DataSet findDataSet(ITreeContentProvider provider, Object[] elements, String name)
            {
                if (elements == null)
                    return null;
                for (Object element : elements)
                {
                    if (element instanceof DataSet dataSet && name.equals(dataSet.getName()))
                        return dataSet;
                    DataSet nested = findDataSet(provider, provider.getChildren(element), name);
                    if (nested != null)
                        return nested;
                }
                return null;
            }
        }
    }

    private static final class ColumnWidths
    {
        private static final String ATTACHED_KEY = "tormozit.dcsColumnWidths"; //$NON-NLS-1$
        private static final String SETTINGS_SECTION = "dcsColumnWidths"; //$NON-NLS-1$

        static void install(DataCompositionSchemaControlContext context)
        {
            if (context == null)
                return;
            for (EditorPage editorPage : context.getPages())
            {
                if (!(editorPage instanceof Composite page) || page.isDisposed()
                    || page.getData(ATTACHED_KEY) != null)
                    continue;
                page.setData(ATTACHED_KEY, Boolean.TRUE);
                List<TableEx> tables = new ArrayList<>();
                collectTables(page, tables);
                for (int index = 0; index < tables.size(); index++)
                {
                    TableEx table = tables.get(index);
                    String key = editorPage.getClass().getName() + "." + index; //$NON-NLS-1$
                    String[][] latestWidths = { null };
                    table.getHeaderControl().addListener(SWT.Paint, event ->
                    {
                        latestWidths[0] = readWidths(table);
                    });
                    table.getHeaderControl().addListener(SWT.MouseUp, event ->
                    {
                        latestWidths[0] = readWidths(table);
                    });
                    restore(table, key);
                    // EDT явно уничтожает TableEx до Dispose страницы. Последние ширины
                    // держим отдельно от виджетов и записываем при закрытии страницы.
                    page.addListener(SWT.Dispose, event -> save(table, key, latestWidths[0]));
                }
            }
        }

        private static void collectTables(Composite root, List<TableEx> tables)
        {
            for (Control child : root.getChildren())
            {
                if (child instanceof TableEx table)
                    tables.add(table);
                else if (child instanceof Composite composite)
                    collectTables(composite, tables);
            }
        }

        private static IDialogSettings settings()
        {
            IDialogSettings root = Activator.getDefault().getDialogSettings();
            IDialogSettings section = root.getSection(SETTINGS_SECTION);
            return section != null ? section : root.addNewSection(SETTINGS_SECTION);
        }

        private static void restore(TableEx table, String key)
        {
            String[] stored = settings().getArray(key);
            org.eclipse.nebula.widgets.grid.Grid header = table.getHeaderControl();
            if (stored == null || header == null || header.isDisposed()
                || stored.length != header.getColumnCount())
                return;
            int[] widths = new int[stored.length];
            try
            {
                for (int index = 0; index < stored.length; index++)
                {
                    widths[index] = Integer.parseInt(stored[index]);
                    if (widths[index] <= 0)
                    {
                        return;
                    }
                }
            }
            catch (NumberFormatException exception)
            {
                return;
            }
            // setColumnWidth обновляет ColumnWeightData штатного TableExColumnLayout,
            // а не только текущий GridColumn, иначе следующий layout затрёт ширины.
            for (int index = 0; index < widths.length; index++)
                table.setColumnWidth(new Point(index, 0), widths[index]);
            header.getParent().layout();
            table.fillBorders();
            table.updateHeader();
        }

        private static void save(TableEx table, String key, String[] latestWidths)
        {
            String[] widths = readWidths(table);
            if (widths == null)
                widths = latestWidths;
            if (widths == null)
                return;
            settings().put(key, widths);
        }

        private static String[] readWidths(TableEx table)
        {
            if (table.isDisposed())
                return null;
            org.eclipse.nebula.widgets.grid.Grid header = table.getHeaderControl();
            if (header == null || header.isDisposed() || header.getColumnCount() == 0
                || header.getClientArea().width <= 0)
                return null;
            String[] widths = new String[header.getColumnCount()];
            for (int index = 0; index < widths.length; index++)
            {
                int width = header.getColumn(index).getWidth();
                if (width <= 0)
                    return null;
                widths[index] = Integer.toString(width);
            }
            return widths;
        }

    }

    /** Держит флажки параметров запроса рядом, независимо от ширины конструктора. */
    private static final class QueryOptionsLayout
    {
        static void install(DataCompositionSchemaControlContext context)
        {
            if (context == null)
                return;
            for (EditorPage editorPage : context.getPages())
                if (editorPage instanceof Composite page && !page.isDisposed())
                    attach(page);
        }

        private static void attach(Composite root)
        {
            // Поля и общий родитель проверены по DataSetsQuery в .tmp/bundles/dcs-ui.
            if (root.getClass().getName().equals("com._1c.g5.v8.dt.dcs.ui.datasets.query.DataSetsQuery")) //$NON-NLS-1$
            {
                Object autoFill = Global.getField(root, "autoFillCheck"); //$NON-NLS-1$
                Object grouping = Global.getField(root, "useQueryGroupIfPossibleCheck"); //$NON-NLS-1$
                if (autoFill instanceof Button first && grouping instanceof Button second
                    && !first.isDisposed() && !second.isDisposed()
                    && first.getParent() == second.getParent()
                    && first.getLayoutData() instanceof GridData data)
                {
                    if (data.grabExcessHorizontalSpace)
                    {
                        data.grabExcessHorizontalSpace = false;
                        first.getParent().layout();
                    }
                }
                return;
            }
            for (Control child : root.getChildren())
                if (child instanceof Composite composite)
                    attach(composite);
        }
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
}
