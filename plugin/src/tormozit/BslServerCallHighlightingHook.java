package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.text.TextAttribute;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.ide.editor.syntaxcoloring.IHighlightedPositionAcceptor;
import org.eclipse.xtext.ide.editor.syntaxcoloring.ISemanticHighlightingCalculator;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.XtextSourceViewer;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.editor.syntaxcoloring.AttributedPosition;
import org.eclipse.xtext.ui.editor.syntaxcoloring.HighlightingPresenter;
import org.eclipse.xtext.ui.editor.syntaxcoloring.HighlightingReconciler;
import org.eclipse.xtext.util.CancelIndicator;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Подключает подсветку серверных вызовов к BSL-редакторам: оборачивает
 * {@code newCalculator} реконсилера и применяет полный цикл Xtext-highlighting
 * (включая {@code updatePresentation}) после открытия/активации.
 */
public final class BslServerCallHighlightingHook implements IStartup
{
    private static final String TAG = "ServerCallHL"; //$NON-NLS-1$
    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final List<Object> patchedReconcilers = new ArrayList<>();
    private static final Map<DtGranularEditor<?>, IPageChangedListener> pageListeners = new HashMap<>();
    /** Debounce Job'ов apply: partOpened+Activated+BroughtToTop иначе дают тройной залп. */
    private static final Map<Object, Job> pendingApplyJobs = new IdentityHashMap<>();
    private static final long APPLY_DEBOUNCE_MS = 50L;

    @Override
    public void earlyStartup()
    {
        if (!installed.compareAndSet(false, true))
            return;

        PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                registerWindow(window);
            PlatformUI.getWorkbench().addWindowListener(new WindowAdapter());
        });

        Activator.getDefault().getPreferenceStore()
            .addPropertyChangeListener(event -> {
                String prop = event.getProperty();
                if (ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_ENABLED.equals(prop)
                    || ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_COLOR.equals(prop)
                    || ComfortSettings.PREF_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR.equals(prop)
                    || ComfortSettings.PREF_FILTER_MATCH_COLOR.equals(prop))
                {
                    PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
                        SmartMatchHighlight.clearColorCache();
                        refreshAllEditors();
                    });
                }
            });

        // Смена e4 CSS темы (светлая↔тёмная): сброс кэша FG и пересчёт подсветки.
        IEclipsePreferences themeNode = InstanceScope.INSTANCE.getNode(
            "org.eclipse.e4.ui.css.swt.theme"); //$NON-NLS-1$
        themeNode.addPreferenceChangeListener(event -> {
            PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
                SmartMatchHighlight.clearColorCache();
                refreshAllEditors();
            });
        });
    }

    static void refreshAllEditors()
    {
        if (!installed.get())
            return;
        if (PlatformUI.getWorkbench().getDisplay() == null
            || PlatformUI.getWorkbench().getDisplay().isDisposed())
            return;
        for (Object reconciler : patchedReconcilers)
        {
            registerServerCallStyle(reconciler);
            applyHighlightingNow(reconciler);
        }
    }

    /**
     * Применяет подсветку после wrap калькулятора: полный пайплайн Xtext до
     * {@code updatePresentation}, с debounce на реконсилер. Штатный
     * {@code modelChanged} на переоткрытом standalone-модуле часто выходит рано
     * из‑за гонки с параллельными refresh Job'ами.
     */
    private static void applyHighlightingNow(Object reconciler)
    {
        try
        {
            if (!(reconciler instanceof HighlightingReconciler hr))
                return;

            XtextSourceViewer sourceViewer = resolveSourceViewer(hr);
            if (sourceViewer == null)
                return;
            IXtextDocument document = sourceViewer.getXtextDocument();
            if (document == null)
                return;

            synchronized (pendingApplyJobs)
            {
                Job previous = pendingApplyJobs.get(hr);
                if (previous != null)
                    previous.cancel();

                Job job = new Job("Comfort: server-call highlighting apply") //$NON-NLS-1$
                {
                    @Override
                    protected IStatus run(IProgressMonitor monitor)
                    {
                        try
                        {
                            if (monitor.isCanceled())
                                return Status.CANCEL_STATUS;

                            document.tryReadOnly(resource ->
                            {
                                if (monitor.isCanceled())
                                    return Boolean.FALSE;
                                runFullHighlightingApply(hr, resource, monitor);
                                return Boolean.TRUE;
                            });
                        }
                        catch (Exception e)
                        {
                            Global.log(TAG, "applyHighlightingNow job failed: " //$NON-NLS-1$
                                + e.getClass().getSimpleName() + " " + e.getMessage()); //$NON-NLS-1$
                        }
                        finally
                        {
                            synchronized (pendingApplyJobs)
                            {
                                if (pendingApplyJobs.get(hr) == this)
                                    pendingApplyJobs.remove(hr);
                            }
                        }
                        return Status.OK_STATUS;
                    }
                };
                job.setSystem(true);
                job.setPriority(Job.DECORATE);
                pendingApplyJobs.put(hr, job);
                job.schedule(APPLY_DEBOUNCE_MS);
            }
        }
        catch (Exception e)
        {
            Global.log(TAG, "applyHighlightingNow failed: " + e.getClass().getSimpleName() //$NON-NLS-1$
                + " " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static XtextSourceViewer resolveSourceViewer(HighlightingReconciler hr)
    {
        try
        {
            Field sourceViewerField = findField(HighlightingReconciler.class, "sourceViewer"); //$NON-NLS-1$
            if (sourceViewerField == null)
                return null;
            sourceViewerField.setAccessible(true);
            Object sourceViewerObj = sourceViewerField.get(hr);
            return sourceViewerObj instanceof XtextSourceViewer sourceViewer ? sourceViewer : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Полный аналог тела {@code HighlightingReconciler.modelChanged} до
     * {@code updatePresentation}. Управляет флагом {@code reconciling}, чтобы
     * параллельный native reconcile не вошёл в середину.
     */
    @SuppressWarnings("unchecked")
    private static void runFullHighlightingApply(HighlightingReconciler hr, XtextResource resource,
        IProgressMonitor monitor)
    {
        Field reconcileLockField = findField(HighlightingReconciler.class, "fReconcileLock"); //$NON-NLS-1$
        Field reconcilingField = findField(HighlightingReconciler.class, "reconciling"); //$NON-NLS-1$
        Field presenterField = findField(HighlightingReconciler.class, "presenter"); //$NON-NLS-1$
        Field addedField = findField(HighlightingReconciler.class, "addedPositions"); //$NON-NLS-1$
        Field removedField = findField(HighlightingReconciler.class, "removedPositions"); //$NON-NLS-1$
        Method startMethod = findMethod(HighlightingReconciler.class, "startReconcilingPositions"); //$NON-NLS-1$
        Method reconcileMethod = findMethod(HighlightingReconciler.class, "reconcilePositions", //$NON-NLS-1$
            XtextResource.class, CancelIndicator.class);
        Method updateMethod = findMethod(HighlightingReconciler.class, "updatePresentation", //$NON-NLS-1$
            TextPresentation.class, List.class, List.class, XtextResource.class);
        Method stopMethod = findMethod(HighlightingReconciler.class, "stopReconcilingPositions"); //$NON-NLS-1$

        if (reconcileLockField == null || reconcilingField == null || presenterField == null
            || addedField == null || removedField == null || startMethod == null
            || reconcileMethod == null || updateMethod == null || stopMethod == null)
        {
            Global.log(TAG, "runFullHighlightingApply: reflection members missing"); //$NON-NLS-1$
            return;
        }

        try
        {
            reconcileLockField.setAccessible(true);
            reconcilingField.setAccessible(true);
            presenterField.setAccessible(true);
            addedField.setAccessible(true);
            removedField.setAccessible(true);
            startMethod.setAccessible(true);
            reconcileMethod.setAccessible(true);
            updateMethod.setAccessible(true);
            stopMethod.setAccessible(true);

            Object lock = reconcileLockField.get(hr);
            if (lock == null)
                return;

            synchronized (lock)
            {
                if (reconcilingField.getBoolean(hr))
                    return;
                reconcilingField.setBoolean(hr, true);
            }

            HighlightingPresenter presenter = null;
            try
            {
                if (monitor != null && monitor.isCanceled())
                    return;

                Object presenterObj = presenterField.get(hr);
                if (!(presenterObj instanceof HighlightingPresenter p))
                    return;
                presenter = p;

                presenter.setCanceled(false);
                if (presenter.isCanceled())
                    return;

                startMethod.invoke(hr);
                if (presenter.isCanceled() || (monitor != null && monitor.isCanceled()))
                    return;

                reconcileMethod.invoke(hr, resource, CancelIndicator.NullImpl);
                if (presenter.isCanceled() || (monitor != null && monitor.isCanceled()))
                    return;

                List<AttributedPosition> added =
                    (List<AttributedPosition>)addedField.get(hr);
                List<AttributedPosition> removed =
                    (List<AttributedPosition>)removedField.get(hr);
                TextPresentation textPresentation = presenter.createPresentation(added, removed);
                if (textPresentation == null
                    || presenter.isCanceled()
                    || (monitor != null && monitor.isCanceled()))
                    return;

                updateMethod.invoke(hr, textPresentation, added, removed, resource);
            }
            finally
            {
                if (presenter != null)
                    presenter.setCanceled(false);
                try
                {
                    stopMethod.invoke(hr);
                }
                catch (Exception ignored)
                {
                }
                synchronized (lock)
                {
                    reconcilingField.setBoolean(hr, false);
                }
            }
        }
        catch (Exception e)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Global.log(TAG, "runFullHighlightingApply failed: " //$NON-NLS-1$
                + cause.getClass().getSimpleName() + " " + cause.getMessage()); //$NON-NLS-1$
        }
    }

    private static void registerWindow(IWorkbenchWindow window)
    {
        window.getPartService().addPartListener(new PartAdapter());
        for (IWorkbenchPage page : window.getPages())
        {
            for (IEditorReference ref : page.getEditorReferences())
                inspectEditor(ref);
        }
    }

    private static void inspectEditor(IEditorReference ref)
    {
        try
        {
            IWorkbenchPart part = ref.getPart(false);
            if (part instanceof BslXtextEditor)
                patchEditor((BslXtextEditor)part);
            else if (part instanceof DtGranularEditor)
                patchGranularEditor((DtGranularEditor<?>)part);
        }
        catch (Exception e)
        {
            Global.log(TAG, "inspectEditor failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /** Повторный apply при активации standalone-редактора (у форм есть pageChanged). */
    private static void refreshOnActivate(IWorkbenchPartReference ref)
    {
        try
        {
            IWorkbenchPart part = ref.getPart(false);
            if (part instanceof BslXtextEditor editor)
                patchEditor(editor);
            else if (part instanceof DtGranularEditor)
                patchGranularEditor((DtGranularEditor<?>)part);
        }
        catch (Exception e)
        {
            Global.log(TAG, "refreshOnActivate failed: " + e.getClass().getSimpleName() //$NON-NLS-1$
                + " " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void patchGranularEditor(DtGranularEditor<?> editor)
    {
        try
        {
            patchFormPageIfBsl(editor.getActivePageInstance());

            if (!pageListeners.containsKey(editor))
            {
                IPageChangedListener listener = event -> patchFormPageIfBsl(event.getSelectedPage());
                editor.addPageChangedListener(listener);
                pageListeners.put(editor, listener);
            }
        }
        catch (Exception e)
        {
            Global.log(TAG, "patchGranular failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void unregisterGranularEditor(DtGranularEditor<?> editor)
    {
        IPageChangedListener listener = pageListeners.remove(editor);
        if (listener != null)
            editor.removePageChangedListener(listener);
    }

    private static void patchFormPageIfBsl(Object page)
    {
        if (page instanceof DtGranularEditorXtextEditorPage)
        {
            DtGranularEditorXtextEditorPage<?> xtextPage = (DtGranularEditorXtextEditorPage<?>)page;
            IEditorPart embedded = xtextPage.getEmbeddedEditor();
            if (embedded instanceof BslXtextEditor)
                patchEditor((BslXtextEditor)embedded);
        }
    }

    private static void patchEditor(BslXtextEditor editor)
    {
        try
        {
            Object viewer = editor.getInternalSourceViewer();
            if (viewer == null)
                return;

            Object reconciler = findHighlightingReconciler(viewer);
            if (reconciler == null)
                return;

            boolean firstTimeWrap = wrapCalculator(reconciler);
            if (firstTimeWrap && !patchedReconcilers.contains(reconciler))
                patchedReconcilers.add(reconciler);
            registerServerCallStyle(reconciler);
            applyHighlightingNow(reconciler);
        }
        catch (Exception e)
        {
            Global.log(TAG, "patchEditor failed: " + e.getClass().getSimpleName() + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static Object getAttributeProvider(Object reconciler)
    {
        try
        {
            Field field = findField(reconciler.getClass(), "attributeProvider"); //$NON-NLS-1$
            if (field == null)
                return null;
            field.setAccessible(true);
            return field.get(reconciler);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** Регистрирует/обновляет стили серверных вызовов в attributeProvider реконсилера. */
    @SuppressWarnings("unchecked")
    private static void registerServerCallStyle(Object reconciler)
    {
        Object attributeProvider = getAttributeProvider(reconciler);
        if (attributeProvider == null)
            return;
        try
        {
            Field attrsField = findField(attributeProvider.getClass(), "attributes"); //$NON-NLS-1$
            if (attrsField == null)
                return;
            attrsField.setAccessible(true);
            Object mapObj = attrsField.get(attributeProvider);
            if (!(mapObj instanceof HashMap))
                return;
            HashMap<String, TextAttribute> attributes = (HashMap<String, TextAttribute>)mapObj;

            putServerCallStyle(attributes, BslServerCallHighlightingConfiguration.SERVER_CALL_ID,
                ComfortSettings.getServerCallHighlightingColor(),
                ComfortSettings.DEFAULT_SERVER_CALL_HIGHLIGHTING_COLOR);
            putServerCallStyle(attributes, BslServerCallHighlightingConfiguration.SERVER_CALL_CONTEXT_ID,
                ComfortSettings.getServerCallContextHighlightingColor(),
                ComfortSettings.DEFAULT_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR);
        }
        catch (Exception e)
        {
            Global.log(TAG, "registerStyle failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void putServerCallStyle(HashMap<String, TextAttribute> attributes, String id, String colorStr,
            String fallback)
    {
        TextAttribute attr = createServerCallTextAttribute(colorStr, fallback);
        if (attr == null)
            return;
        attributes.put(id, attr);

        // TextAttributeProvider кэширует merged-стили под ключом "$$$Merged:...$$$";
        // сбрасываем записи с этим id, чтобы цвет из настроек применился заново.
        Iterator<String> keys = attributes.keySet().iterator();
        while (keys.hasNext())
        {
            String key = keys.next();
            if (key.startsWith("$$$Merged:") && key.contains(id)) //$NON-NLS-1$
                keys.remove();
        }
    }

    /** Только цвет, без жирного/курсива. */
    private static TextAttribute createServerCallTextAttribute(String colorStr, String fallback)
    {
        try
        {
            RGB light = ComfortSettings.parseRgb(colorStr, fallback);
            RGB effective = SmartMatchHighlight.toEffectiveRgb(light);
            Color foreground = new Color(Display.getCurrent(), effective);
            return new TextAttribute(foreground, null, org.eclipse.swt.SWT.NONE, null);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static Object findHighlightingReconciler(Object viewer)
    {
        try
        {
            Field fTextInputListeners = findField(viewer.getClass(), "fTextInputListeners"); //$NON-NLS-1$
            if (fTextInputListeners == null)
                return null;
            fTextInputListeners.setAccessible(true);
            Object listenersObj = fTextInputListeners.get(viewer);
            if (listenersObj == null)
                return null;

            Class<?> reconcilerClass = loadClass(
                "org.eclipse.xtext.ui.editor.syntaxcoloring.HighlightingReconciler"); //$NON-NLS-1$
            if (reconcilerClass == null)
                return null;

            if (listenersObj instanceof List)
            {
                for (Object listener : (List<?>)listenersObj)
                {
                    if (reconcilerClass.isInstance(listener))
                        return listener;
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return null;
    }

    private static boolean wrapCalculator(Object reconciler)
    {
        try
        {
            Field newCalcField = findField(reconciler.getClass(), "newCalculator"); //$NON-NLS-1$
            if (newCalcField == null)
                return false;
            newCalcField.setAccessible(true);

            Object original = newCalcField.get(reconciler);
            if (original instanceof DelegatingCalculator)
                return false;

            newCalcField.set(reconciler, new DelegatingCalculator(original));
            return true;
        }
        catch (Exception e)
        {
            Global.log(TAG, "wrapCalculator failed: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    private static Field findField(Class<?> clazz, String name)
    {
        Class<?> c = clazz;
        while (c != null)
        {
            try
            {
                return c.getDeclaredField(name);
            }
            catch (NoSuchFieldException e)
            {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes)
    {
        Class<?> c = clazz;
        while (c != null)
        {
            try
            {
                return c.getDeclaredMethod(name, paramTypes);
            }
            catch (NoSuchMethodException e)
            {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static Class<?> loadClass(String name)
    {
        try
        {
            return Class.forName(name);
        }
        catch (ClassNotFoundException e)
        {
            return null;
        }
    }

    static final class DelegatingCalculator implements ISemanticHighlightingCalculator
    {
        private final Object delegate;

        DelegatingCalculator(Object delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public void provideHighlightingFor(XtextResource resource,
            IHighlightedPositionAcceptor acceptor, CancelIndicator cancelIndicator)
        {
            if (delegate instanceof ISemanticHighlightingCalculator)
            {
                ((ISemanticHighlightingCalculator)delegate)
                    .provideHighlightingFor(resource, acceptor, cancelIndicator);
            }

            new BslServerCallHighlightingCalculator()
                .provideHighlightingFor(resource, acceptor, cancelIndicator);
        }
    }

    private static final class PartAdapter implements IPartListener2
    {
        @Override
        public void partOpened(IWorkbenchPartReference ref)
        {
            if (ref instanceof IEditorReference)
                inspectEditor((IEditorReference)ref);
        }

        @Override
        public void partClosed(IWorkbenchPartReference ref)
        {
            IWorkbenchPart part = ref.getPart(false);
            if (part instanceof DtGranularEditor)
                unregisterGranularEditor((DtGranularEditor<?>)part);
        }

        @Override
        public void partActivated(IWorkbenchPartReference ref)
        {
            refreshOnActivate(ref);
        }

        @Override
        public void partBroughtToTop(IWorkbenchPartReference ref)
        {
            refreshOnActivate(ref);
        }

        @Override public void partDeactivated(IWorkbenchPartReference ref) {}
        @Override public void partHidden(IWorkbenchPartReference ref) {}
        @Override public void partInputChanged(IWorkbenchPartReference ref) {}
        @Override public void partVisible(IWorkbenchPartReference ref) {}
    }

    private static final class WindowAdapter implements org.eclipse.ui.IWindowListener
    {
        @Override public void windowActivated(IWorkbenchWindow window) {}
        @Override public void windowDeactivated(IWorkbenchWindow window) {}

        @Override
        public void windowOpened(IWorkbenchWindow window)
        {
            registerWindow(window);
        }

        @Override
        public void windowClosed(IWorkbenchWindow window) {}
    }
}
