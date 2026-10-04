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
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.ViewerColumn;
import org.eclipse.swt.SWT;
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
        TableExHeaderAccent.install(dcsEditor.getControlContext());
        ColumnWidths.install(dcsEditor.getControlContext());
        QueryOptionsLayout.install(dcsEditor.getControlContext());
        DcsComputedValueTypes.install(dcsEditor.getControlContext());
        FieldTypeAutofill.install(dcsEditor.getControlContext());
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
     * ({@link TypeByNameAdvisor}): совпадение имени с объектом метаданных, иначе ИР, иначе
     * Напарник. Список доступных типов — тот же, что показывает диалог выбора типа ячейки
     * («Тип значения»): {@code TypeProviderService.getTypeDescriptionInfoWithTypeInfo}.
     *
     * <p>Правку ловим обёрткой штатного {@link EditingSupport} колонки: так срабатывает только
     * ввод пользователем в таблице, а не любое изменение модели (загрузка схемы, обмен с ИР).
     * Прежний {@code EditingSupport} берётся из приватного поля {@code ViewerColumn.editingSupport}.
     */
    private static final class FieldTypeAutofill
    {
        private static final String INSTALLED_KEY = "tormozit.dcs.fieldTypeAutofill"; //$NON-NLS-1$
        /** Временная диагностика — снять после подтверждения работы. */
        private static final String LOG = "dcs-field-type"; //$NON-NLS-1$
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
                    Global.tempLog(LOG, "обёртка колонки " + columnName + " установлена: " //$NON-NLS-1$ //$NON-NLS-2$
                        + fieldsViewer.getClass().getSimpleName());
                }
                else
                    Global.tempLog(LOG, "[!] колонка " + columnName + ": EditingSupport не найден"); //$NON-NLS-1$ //$NON-NLS-2$
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
                Global.tempLog(LOG, "правка имени поля: «" + before + "» → «" + trimmed + "» тип пуст=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + isEmpty(field.getValueType()));
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
                    Global.tempLog(LOG, "подбор пропущен: проект=" + (project != null) + " типы=" + (catalog != null)); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                String source = TypeByNameAdvisor.availableSource(project, catalog, name);
                Global.tempLog(LOG, "подбор: имя=" + name + " типовДоступно=" + catalog.items().size() //$NON-NLS-1$ //$NON-NLS-2$
                    + " источник=" + source); //$NON-NLS-1$
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
                Global.tempLog(LOG, "подстановка «" + typeName + "»: сопоставлен=" + (item != null)); //$NON-NLS-1$ //$NON-NLS-2$
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
                    Global.tempLogException(LOG, "[!] список типов поля", e); //$NON-NLS-1$
                    return null;
                }
            }
        }
    }

    /** Ширины таблиц конструктора, общие для схем, но отдельные для каждой страницы и таблицы. */
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
