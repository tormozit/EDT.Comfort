import com.google.inject.Inject;

import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamException;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.IManagedForm;
import org.eclipse.ui.forms.ManagedForm;
import org.eclipse.ui.forms.editor.FormPage;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.eclipse.ui.forms.widgets.ScrolledForm;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.dcs.ui.DataCompositionSchemaEditor;
import com._1c.g5.v8.dt.dcs.ui.EditorPageBase;
import com._1c.g5.v8.dt.dcs.ui.datasets.DataSets;
import com._1c.g5.v8.dt.dcs.ui.datasets.DataSetsLoadHandler;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorEmbeddedEditorPage;
import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.util.DcsV8Serializer;
import com._1c.g5.v8.dt.export.ExportException;
import com._1c.g5.v8.dt.xml.ChangeAnyRefTypeOutputStream;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.common.PreferenceUtils;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmEditingContext;
import com._1c.g5.v8.dt.dcs.ui.DcsEvent;
import com._1c.g5.v8.dt.dcs.ui.DcsEvent.DcsEventType;
import org.eclipse.core.runtime.IProgressMonitor;

public class DataCompositionSchemaEditorHook implements IStartup
{
    @Inject
    static private IResourceLookup resourceLookup;
    @Inject
    static private IRuntimeVersionSupport runtimeVersionSupport;
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
            item.setToolTipText("Редактировать в консоли компоновки данных ИР (Tormozit)");
            item.addListener(SWT.Selection, event -> {
                Object editor = Global.getField(page, "editor");
                Object BmModel = Global.getField(editor, "bmModel");
                IDtProject project = (IDtProject)Global.getField(BmModel, "project");
                IRApplicationRegistry.IrSession irSession = IRApplicationRegistry.getSession(project);
                if (irSession == null || irSession.executor == null) {
                    return;
                }
                irSession.executor.submit(() -> {
                    try 
                    {
                        // Здесь мы находимся в родном потоке для этого COM-объекта. 
                        String file = exportToFile(page);
                        ComBridge.setProperty(irSession.root, "Visible", true);
                        Object irClient = irSession.getModule("ирКлиент");
                        URI fileUri = page.getModel().eResource().getURI();
                        String fullObjectName = fileUri.path().substring(1);
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

    public static String exportToFile(DtGranularEditorEmbeddedEditorPage<?> page)
        throws IOException, FileNotFoundException, XMLStreamException, ExportException
    {
        // com._1c.g5.v8.dt.dcs.ui.datasets.DataSetsSaveHandler.DataSetsSaveHandler()
        DataCompositionSchemaEditor dcsEditor = (DataCompositionSchemaEditor) page.getEmbeddedEditor();
        Object editor = Global.getField(page, "editor");
        Object BmModel = Global.getField(editor, "bmModel");
        IDtProject project = (IDtProject)Global.getField(BmModel, "project");
        String file = File.createTempFile("tormozit.edt", ".xml").getPath();
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
            DcsV8Serializer serializer = new DcsV8Serializer(project, version, resourceLookup);
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
        DcsV8Serializer serializer = new DcsV8Serializer(project, version, resourceLookup);
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
            e.printStackTrace();
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
}
