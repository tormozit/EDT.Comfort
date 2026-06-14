package tormozit;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.texteditor.ITextEditor;

import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Подменю «Комфорт» в контекстном меню текстовых редакторов Workbench.
 */
public class TextEditorMenuHook implements IStartup
{
    private static final String HOOK_MARKER = "tormozit.textEditorComfortMenuHooked"; //$NON-NLS-1$

    private final Set<DtGranularEditor<?>> hookedGranularEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });

            for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows())
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
                IEditorPart ed = ref.getEditor(false);
                if (ed != null)
                    hookEditorIfNeeded(ed);
            }
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference))
                    return;
                IEditorPart ed = ((IEditorReference) ref).getEditor(false);
                if (ed != null)
                    hookEditorIfNeeded(ed);
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

    private void hookEditorIfNeeded(IEditorPart editor)
    {
        if (editor instanceof ITextEditor textEditor)
            hookTextEditor(textEditor, editor, editor);
        else if (editor instanceof DtGranularEditor<?> granular)
            hookGranularEditor(granular);
    }

    private void hookTextEditor(ITextEditor textEditor, IEditorPart editorPart, IWorkbenchPart part)
    {
        Display.getDefault().asyncExec(() -> attachComfortMenu(textEditor, editorPart, part));
    }

    private void hookGranularEditor(DtGranularEditor<?> granular)
    {
        hookActiveTextPage(granular);

        if (!hookedGranularEditors.contains(granular))
        {
            IPageChangedListener listener = event -> hookActiveTextPage(granular);
            granular.addPageChangedListener(listener);
            hookedGranularEditors.add(granular);
        }
    }

    private void hookActiveTextPage(DtGranularEditor<?> granular)
    {
        IFormPage activePage = granular.getActivePageInstance();
        if (!(activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage))
            return;
        IEditorPart embedded = xtextPage.getEmbeddedEditor();
        if (embedded instanceof ITextEditor textEditor)
            hookTextEditor(textEditor, granular, granular);
    }

    private void attachComfortMenu(ITextEditor textEditor, IEditorPart editorPart, IWorkbenchPart part)
    {
        if (textEditor.getSite() == null)
            return;

        ISourceViewer viewer = TextEditorSupport.getSourceViewer(textEditor);
        if (!(viewer instanceof SourceViewer sourceViewer))
        {
            Display.getDefault().asyncExec(() -> attachComfortMenu(textEditor, editorPart, part));
            return;
        }

        StyledText textWidget = sourceViewer.getTextWidget();
        if (textWidget == null || textWidget.isDisposed())
            return;

        if (Boolean.TRUE.equals(textWidget.getData(HOOK_MARKER)))
            return;

        if (textWidget.getMenu() == null)
        {
            Display.getDefault().asyncExec(() -> attachComfortMenu(textEditor, editorPart, part));
            return;
        }

        TextEditorComfortMenu.attachWorkbench(textWidget, HOOK_MARKER, editorPart, part);
    }
}
