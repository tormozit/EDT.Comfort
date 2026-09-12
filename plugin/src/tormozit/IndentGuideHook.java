/******************************************************************************
 * Copyright (c) 2006-2023 The IndentGuide Authors.
 * Copyright (c) 2026 EDT Comfort contributors.
 *
 * Adapted from net.certiv.tools.indentguide Starter (MIT License):
 * https://opensource.org/licenses/MIT
 *****************************************************************************/
package tormozit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringTokenizer;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.FormEditor;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.ui.progress.UIJob;
import org.eclipse.ui.texteditor.AbstractTextEditor;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.IDocumentProviderExtension4;
import org.eclipse.ui.themes.IThemeManager;

import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Устанавливает {@link IndentGuidePainter} на текстовые редакторы EDT.
 */
public final class IndentGuideHook implements IStartup
{
    private static final String TAG = "IndentGuide"; //$NON-NLS-1$

    private IPreferenceStore store;
    private Set<String> excludedTypeIds = Set.of();

    private final LinkedHashSet<Data> datas = new LinkedHashSet<>();
    private final Map<Object, IPageChangedListener> pageListeners = new HashMap<>();

    private final PartWatcher partWatcher = new PartWatcher();
    private final PropWatcher propWatcher = new PropWatcher();

    @Override
    public void earlyStartup()
    {
        UIJob job = new UIJob("Indent Guide Startup") //$NON-NLS-1$
        {
            @Override
            public IStatus runInUIThread(IProgressMonitor monitor)
            {
                IWorkbench wb = PlatformUI.getWorkbench();
                wb.getThemeManager().addPropertyChangeListener(propWatcher);
                ComfortSettings settings = ComfortSettings.getInstance();
                store = settings != null
                    ? settings.getPreferenceStore()
                    : Activator.getDefault().getPreferenceStore();
                store.addPropertyChangeListener(propWatcher);

                updateContentTypes();
                initWorkbenchWindows();

                wb.addWindowListener(new org.eclipse.ui.IWindowListener()
                {
                    @Override
                    public void windowOpened(IWorkbenchWindow window)
                    {
                        initWorkbenchWindow(window);
                        window.getPartService().addPartListener(partWatcher);
                    }

                    @Override
                    public void windowClosed(IWorkbenchWindow window)
                    {
                    }

                    @Override
                    public void windowActivated(IWorkbenchWindow window)
                    {
                    }

                    @Override
                    public void windowDeactivated(IWorkbenchWindow window)
                    {
                    }
                });
                return Status.OK_STATUS;
            }
        };
        job.setPriority(Job.SHORT);
        job.setSystem(true);
        job.schedule(200);
    }

    private void initWorkbenchWindows()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
        {
            initWorkbenchWindow(window);
            window.getPartService().addPartListener(partWatcher);
        }
    }

    private void initWorkbenchWindow(IWorkbenchWindow window)
    {
        for (IWorkbenchPage page : window.getPages())
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                IWorkbenchPart part = ref.getPart(false);
                if (part != null)
                    installPainter(part);
            }
        }
    }

    private void installPainter(IWorkbenchPart part)
    {
        if (store == null || !store.getBoolean(ComfortSettings.PREF_INDENT_GUIDE_ENABLED))
            return;

        if (part instanceof DtGranularEditor<?> granular)
        {
            ensurePageListener(granular);
            patchGranularPage(granular, granular.getActivePageInstance());
            return;
        }

        if (part instanceof FormEditor || part instanceof MultiPageEditorPart)
            ensurePageListener(part);

        AbstractTextEditor editor = activeEditor(part);
        if (editor == null)
            return;

        installOnEditor(part, editor);
    }

    private void ensurePageListener(Object part)
    {
        if (pageListeners.containsKey(part))
            return;
        if (!(part instanceof org.eclipse.jface.dialogs.IPageChangeProvider provider))
            return;
        IPageChangedListener listener = event -> {
            Object selected = event.getSelectedPage();
            if (part instanceof DtGranularEditor<?> granular)
                patchGranularPage(granular, selected);
            else if (part instanceof IWorkbenchPart workbenchPart)
                installPainter(workbenchPart);
        };
        provider.addPageChangedListener(listener);
        pageListeners.put(part, listener);
    }

    private void patchGranularPage(DtGranularEditor<?> part, Object page)
    {
        if (page instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
        {
            IEditorPart embedded = xtextPage.getEmbeddedEditor();
            if (embedded instanceof AbstractTextEditor editor)
                installOnEditor(part, editor);
        }
    }

    private void installOnEditor(IWorkbenchPart part, AbstractTextEditor editor)
    {
        IContentType type = typeOf(editor);
        if (!valid(type))
            return;

        try
        {
            ISourceViewer viewer = TextEditor.getSourceViewer(editor);
            if (!(viewer instanceof ITextViewerExtension2 ext))
                return;

            Data data = findRecord(part, editor);
            if (data == null)
            {
                data = new Data(part, editor, type, viewer);
                datas.add(data);
            }
            if (data.painter == null)
            {
                data.painter = new IndentGuidePainter(viewer);
                ext.addPainter(data.painter);
                Global.log(TAG, "painter installed"); //$NON-NLS-1$
            }
        }
        catch (Throwable e)
        {
            Global.log(TAG, "install failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private AbstractTextEditor activeEditor(IWorkbenchPart part)
    {
        IEditorPart editor = null;

        if (part instanceof FormEditor formEditor)
            editor = formEditor.getActiveEditor();
        else if (part instanceof MultiPageEditorPart)
        {
            Object active = Global.invoke(part, "getActiveEditor"); //$NON-NLS-1$
            if (active instanceof IEditorPart activePart)
                editor = activePart;
        }
        else if (part instanceof IEditorPart editorPart)
            editor = editorPart;

        return (editor instanceof AbstractTextEditor ate) ? ate : null;
    }

    private Data findRecord(IWorkbenchPart part, AbstractTextEditor editor)
    {
        return datas.stream()
            .filter(d -> d.part.equals(part) && d.editor.equals(editor))
            .findFirst()
            .orElse(null);
    }

    private boolean valid(IContentType type)
    {
        if (type == null)
            return true;
        return !excludedTypeIds.contains(type.getId());
    }

    private IContentType typeOf(AbstractTextEditor editor)
    {
        IDocumentProvider provider = editor.getDocumentProvider();
        if (provider instanceof IDocumentProviderExtension4 ext4)
        {
            try
            {
                IContentType type = ext4.getContentType(editor.getEditorInput());
                if (type != null)
                    return type;
            }
            catch (CoreException e)
            {
                Global.log(TAG, "content type: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        IEditorInput input = editor.getEditorInput();
        if (input != null)
        {
            IContentTypeManager mgr = Platform.getContentTypeManager();
            try
            {
                return mgr.findContentTypeFor(input.getName());
            }
            catch (Exception ignored)
            {
            }
        }
        return null;
    }

    private void updateContentTypes()
    {
        if (store == null)
        {
            excludedTypeIds = Set.of();
            return;
        }
        excludedTypeIds = undelimit(store.getString(ComfortSettings.PREF_INDENT_GUIDE_CONTENT_TYPES));
    }

    static Set<String> undelimit(String delimited)
    {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        if (delimited == null || delimited.isEmpty())
            return types;
        StringTokenizer tokens = new StringTokenizer(delimited, "|"); //$NON-NLS-1$
        while (tokens.hasMoreTokens())
            types.add(tokens.nextToken());
        return types;
    }

    static String delimit(Set<IContentType> types)
    {
        if (types == null || types.isEmpty())
            return ""; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        for (IContentType type : types)
        {
            if (sb.length() > 0)
                sb.append('|');
            sb.append(type.getId());
        }
        return sb.toString();
    }

    private void refreshAll()
    {
        for (Data d : datas)
        {
            if (d.painter != null)
            {
                d.painter.loadPrefs();
                d.painter.redrawAll();
            }
        }
    }

    private void deactivate(IWorkbenchPart part)
    {
        IPageChangedListener listener = pageListeners.remove(part);
        if (listener != null && part instanceof org.eclipse.jface.dialogs.IPageChangeProvider provider)
            provider.removePageChangedListener(listener);

        AbstractTextEditor editor = activeEditor(part);
        Data d = editor != null ? findRecord(part, editor) : null;
        if (d != null && d.painter != null)
        {
            ((ITextViewerExtension2) d.viewer).removePainter(d.painter);
            d.painter.dispose();
            d.painter = null;
        }
        datas.removeIf(rec -> rec.part.equals(part));
    }

    private void deactivateTypes(Set<String> typeIds)
    {
        for (Data d : datas)
        {
            if (d.type != null && typeIds.contains(d.type.getId()) && d.painter != null)
            {
                ((ITextViewerExtension2) d.viewer).removePainter(d.painter);
                d.painter.dispose();
                d.painter = null;
            }
        }
    }

    private void deactivateAll()
    {
        for (Data d : datas)
        {
            if (d.painter != null)
            {
                ((ITextViewerExtension2) d.viewer).removePainter(d.painter);
                d.painter.dispose();
                d.painter = null;
            }
        }
    }

    private final class PartWatcher implements IPartListener2
    {
        @Override
        public void partOpened(IWorkbenchPartReference ref)
        {
            IWorkbenchPart part = ref.getPart(false);
            if (part != null)
                installPainter(part);
        }

        @Override
        public void partClosed(IWorkbenchPartReference ref)
        {
            IWorkbenchPart part = ref.getPart(false);
            if (part != null)
                deactivate(part);
        }
    }

    private final class PropWatcher implements IPropertyChangeListener
    {
        @Override
        public void propertyChange(PropertyChangeEvent evt)
        {
            String prop = evt.getProperty();
            Object now = evt.getNewValue();

            if (IThemeManager.CHANGE_CURRENT_THEME.equals(prop))
            {
                refreshAll();
                return;
            }

            if (!ComfortSettings.isIndentGuideProperty(prop))
                return;

            if (ComfortSettings.PREF_INDENT_GUIDE_ENABLED.equals(prop))
            {
                if (Boolean.TRUE.equals(now) || Boolean.parseBoolean(String.valueOf(now)))
                    initWorkbenchWindows();
                else
                    deactivateAll();
            }
            else if (ComfortSettings.PREF_INDENT_GUIDE_CONTENT_TYPES.equals(prop))
            {
                Set<String> oldExcluded = excludedTypeIds;
                updateContentTypes();
                Set<String> newlyExcluded = new HashSet<>(excludedTypeIds);
                newlyExcluded.removeAll(oldExcluded);
                if (!newlyExcluded.isEmpty())
                    deactivateTypes(newlyExcluded);
                Set<String> newlyIncluded = new HashSet<>(oldExcluded);
                newlyIncluded.removeAll(excludedTypeIds);
                if (!newlyIncluded.isEmpty())
                    initWorkbenchWindows();
            }

            refreshAll();
        }
    }

    private static final class Data
    {
        final IWorkbenchPart part;
        final AbstractTextEditor editor;
        final IContentType type;
        final ISourceViewer viewer;
        IndentGuidePainter painter;

        Data(IWorkbenchPart part, AbstractTextEditor editor, IContentType type, ISourceViewer viewer)
        {
            this.part = part;
            this.editor = editor;
            this.type = type;
            this.viewer = viewer;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(part, editor);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (!(obj instanceof Data d))
                return false;
            return Objects.equals(part, d.part) && Objects.equals(editor, d.editor);
        }
    }
}
