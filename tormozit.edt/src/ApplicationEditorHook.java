

import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.events.HyperlinkAdapter;
import org.eclipse.ui.forms.events.HyperlinkEvent;
import org.eclipse.ui.forms.widgets.Form;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.eclipse.ui.forms.widgets.Hyperlink;
import org.eclipse.ui.forms.widgets.ScrolledForm;

import com._1c.g5.v8.dt.ui.editor.input.DtEditorInputFactory;
import com._1c.g5.v8.dt.ui.editor.input.IDtEditorInput;

/**
 * Хук редактора приложения ({@code applicationEditor}).
 *
 * <p>Добавляет гиперссылку «Редактировать инфобазу» в область заголовка формы.
 * По клику открывает {@code com._1c.g5.v8.dt.platform.services.ui.InfobaseEditor}
 * для инфобазы текущего приложения.
 *
 * <h3>Технический путь к InfobaseEditor</h3>
 * <pre>
 *   editor.getEditorInput().getApplication().getInfobase()  →  InfobaseReference (EObject)
 *   OpenHelper.openEditor(EObject)                          →  InfobaseEditor
 * </pre>
 *
 * <h3>Получение OpenHelper (Guice-инжектируемый класс)</h3>
 * <p>EDT использует Google Guice (не e4 DI). Правильные пути по документации EDT:
 * <ol>
 *   <li>{@code ServiceAccess.get(Class)} — если OpenHelper зарегистрирован как OSGi-сервис.</li>
 *   <li>{@code BundleActivator.getDefault().getInjector().getInstance(Class)} — через
 *       Guice-инжектор бандла, имя активатора берём из {@code Bundle-Activator} в MANIFEST.MF.</li>
 * </ol>
 */
public class ApplicationEditorHook implements IStartup
{
    private static final String APPLICATION_EDITOR_ID =
        "com.e1c.g5.dt.applications.ui.editor.applicationEditor"; //$NON-NLS-1$

    private static final String OPEN_HELPER_CLASS =
        "com._1c.g5.v8.dt.ui.util.OpenHelper"; //$NON-NLS-1$

    // =======================================================================
    // IStartup
    // =======================================================================

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

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window.getActivePage() != null)
            for (IEditorPart ed : window.getActivePage().getEditors())
                if (APPLICATION_EDITOR_ID.equals(ed.getSite().getId()))
                    hookEditor(ed);

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                if (!APPLICATION_EDITOR_ID.equals(ref.getId())) return;
                Display.getDefault().asyncExec(() ->
                {
                    IEditorPart ed = (IEditorPart) ref.getPart(false);
                    if (ed != null) hookEditor(ed);
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

    // =======================================================================
    // Хук редактора
    // =======================================================================

    private static void hookEditor(IEditorPart editor)
    {
        try
        {
            Object managedForm = Reflect.getField(editor, "managedForm"); //$NON-NLS-1$
            if (managedForm == null) return;

            FormToolkit toolkit       = (FormToolkit)  Reflect.call(managedForm, "getToolkit"); //$NON-NLS-1$
            ScrolledForm scrolledForm = (ScrolledForm) Reflect.call(managedForm, "getForm");    //$NON-NLS-1$
            if (toolkit == null || scrolledForm == null) return;

            addHyperlinkToHead(scrolledForm.getForm(), toolkit, editor);
        }
        catch (Exception e)
        {
            Reflect.log("ApplicationEditorHook.hookEditor: " + e); //$NON-NLS-1$
        }
    }

    private static void addHyperlinkToHead(Form form, FormToolkit toolkit, IEditorPart editor)
    {
        Composite existing = (Composite) Reflect.call(form, "getHeadClient"); //$NON-NLS-1$
        if (existing != null)
        {
            for (var child : existing.getChildren())
                if (child instanceof Hyperlink
                    && LINK_TEXT.equals(((Hyperlink) child).getText()))
                    return; // уже добавлено
            createHyperlink(existing, toolkit, editor);
            existing.layout(true, true);
            return;
        }

        Composite headClient = toolkit.createComposite(form.getHead());
        headClient.setLayout(new org.eclipse.swt.layout.RowLayout(SWT.HORIZONTAL));
        createHyperlink(headClient, toolkit, editor);
        form.setHeadClient(headClient);
        form.getHead().layout(true, true);
    }

    private static final String LINK_TEXT = "Инфобаза"; //$NON-NLS-1$
    private static void createHyperlink(Composite parent, FormToolkit toolkit, IEditorPart editor)
    {
        Hyperlink link = toolkit.createHyperlink(parent, LINK_TEXT, SWT.NONE);
        link.setToolTipText("Открыть редактор инфобазы"); //$NON-NLS-1$
        link.addHyperlinkListener(new HyperlinkAdapter()
        {
            @Override public void linkActivated(HyperlinkEvent e) { openInfobaseEditor(editor); }
        });
    }

    private static void openInfobaseEditor(IEditorPart editor)
    {
        // 1. Извлекаем InfobaseReference из входных данных редактора
        Object input = editor.getEditorInput();
        Object application = Reflect.call(input, "getApplication"); //$NON-NLS-1$
        Object infobase = Reflect.call(application, "getInfobase"); //$NON-NLS-1$
        IWorkbenchPage workbenchPage = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        IDtEditorInput<?> input2 = DtEditorInputFactory.create((EObject)infobase);
        try
        {
            workbenchPage.openEditor(input2, "com._1c.g5.v8.dt.platform.services.ui.InfobaseEditor");
        }
        catch (PartInitException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
}
