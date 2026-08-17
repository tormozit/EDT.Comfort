package tormozit;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.IManagedForm;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.forms.events.HyperlinkAdapter;
import org.eclipse.ui.forms.events.HyperlinkEvent;
import org.eclipse.ui.forms.widgets.Form;
import org.eclipse.ui.forms.widgets.ScrolledForm;

import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;

/**
 * Штатная надпись шапки редактора объекта («Обнаружено N предупреждений / ошибок»)
 * становится ссылкой: клик открывает панель «Ошибки конфигурации» с отбором
 * «Текущий объект».
 * <p>
 * {@code DtGranularEditorMarkerSupport} пишет сводку через
 * {@code ScrolledForm.setMessage}. Eclipse Forms рисует её гиперссылкой только
 * если у формы есть {@code IHyperlinkListener} — штатно его нет, поэтому надпись
 * обычный {@code CLabel}. Хук вешает слушатель через публичный
 * {@link Form#addMessageHyperlinkListener}.
 */
public final class MdEditorMarkerMessageHook implements IStartup
{
    private static final String TAG = "MdEditorMarkerMessage"; //$NON-NLS-1$

    /** Ключ пометки формы: слушатель уже подключён. */
    private static final String KEY_INSTALLED = "tormozit.mdEditorMarkerMessage"; //$NON-NLS-1$

    private final Set<DtGranularEditor<?>> hookedEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
            for (IWorkbenchWindow w : workbench.getWorkbenchWindows())
                hookWindow(w);
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                if (ref.getEditor(false) instanceof DtGranularEditor<?> granular)
                    hookEditor(granular);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { hookFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { hookFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) { hookFromRef(ref); }
        });
    }

    private void hookFromRef(IWorkbenchPartReference ref)
    {
        if (ref != null && ref.getPart(false) instanceof DtGranularEditor<?> granular)
            hookEditor(granular);
    }

    private void hookEditor(DtGranularEditor<?> editor)
    {
        try
        {
            if (hookedEditors.add(editor))
                editor.addPageChangedListener(event -> installOnActivePage(editor));
            installOnActivePage(editor);
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "hook editor", e); //$NON-NLS-1$
        }
    }

    private static void installOnActivePage(DtGranularEditor<?> editor)
    {
        try
        {
            IFormPage page = editor.getActivePageInstance();
            if (page == null)
                return;
            IManagedForm managedForm = page.getManagedForm();
            if (managedForm == null)
                return;
            ScrolledForm scrolledForm = managedForm.getForm();
            if (scrolledForm == null || scrolledForm.isDisposed())
                return;
            Form form = scrolledForm.getForm();
            if (form == null || form.isDisposed() || form.getData(KEY_INSTALLED) != null)
                return;
            form.setData(KEY_INSTALLED, Boolean.TRUE);
            form.addMessageHyperlinkListener(new HyperlinkAdapter()
            {
                @Override
                public void linkActivated(HyperlinkEvent e)
                {
                    ProblemViewMarkers.showForCurrentObject();
                }
            });
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "install on active page", e); //$NON-NLS-1$
        }
    }
}
