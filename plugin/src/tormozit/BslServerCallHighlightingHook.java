package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.text.TextAttribute;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.swt.custom.StyledText;
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

public final class BslServerCallHighlightingHook implements IStartup
{
    private static final String TAG = "ServerCallHL"; //$NON-NLS-1$
    /** Временная тема диагностики: работает ли применение подсветки mutation-free путём. */
    private static final String MUTFREE_DIAG_TOPIC = "ServerCallHL-mutfree"; //$NON-NLS-1$
    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final List<Object> patchedReconcilers = new ArrayList<>();
    private static final Map<DtGranularEditor<?>, IPageChangedListener> pageListeners = new HashMap<>();
    private static final Map<Object, BslXtextEditor> reconcilerEditors = new WeakHashMap<>();
    /** Обратная карта — для повторного триггера пересчёта при активации standalone-редактора. */
    private static final Map<BslXtextEditor, Object> editorReconcilers = new WeakHashMap<>();
    /** Debounce Job'ов apply на реконсилер (partOpened+Activated+BroughtToTop иначе дают тройной залп). */
    private static final Map<Object, Job> pendingApplyJobs = new IdentityHashMap<>();
    /** Задержка debounce, чтобы Opened/Activated/BroughtToTop схлопнулись в один apply. */
    private static final long APPLY_DEBOUNCE_MS = 50L;

    @Override
    public void earlyStartup()
    {
        if (!installed.compareAndSet(false, true))
        {
            Global.log(TAG, "earlyStartup already installed, skipping"); //$NON-NLS-1$
            return;
        }

        Global.log(TAG, "earlyStartup called"); //$NON-NLS-1$
        PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
            IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
            Global.log(TAG, "workbench windows: " + windows.length); //$NON-NLS-1$
            for (IWorkbenchWindow window : windows)
                registerWindow(window);
            PlatformUI.getWorkbench().addWindowListener(new WindowAdapter());
            Global.log(TAG, "window listener registered"); //$NON-NLS-1$
        });

        Activator.getDefault().getPreferenceStore()
            .addPropertyChangeListener(event -> {
                String prop = event.getProperty();
                if (ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_ENABLED.equals(prop)
                    || ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_COLOR.equals(prop)
                    || ComfortSettings.PREF_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR.equals(prop))
                {
                    Global.log(TAG, "property changed: " + prop); //$NON-NLS-1$
                    PlatformUI.getWorkbench().getDisplay().asyncExec(BslServerCallHighlightingHook::refreshAllEditors);
                }
            });
        Global.log(TAG, "property listener registered"); //$NON-NLS-1$
    }

    static void refreshAllEditors()
    {
        if (!installed.get())
        {
            Global.log(TAG, "refreshAllEditors: not installed"); //$NON-NLS-1$
            return;
        }
        if (PlatformUI.getWorkbench().getDisplay() == null
            || PlatformUI.getWorkbench().getDisplay().isDisposed())
        {
            Global.log(TAG, "refreshAllEditors: display null/disposed"); //$NON-NLS-1$
            return;
        }
        Global.log(TAG, "refreshAllEditors: refreshing " + patchedReconcilers.size() + " reconcilers"); //$NON-NLS-1$ //$NON-NLS-2$
        for (Object reconciler : patchedReconcilers)
        {
            // Обновляем TextAttribute (цвет/стиль) перед пересчётом — иначе refresh
            // просто заново нарисует старым, закэшированным значением. Раньше это
            // делал отдельный updateServerCallStyleInProvider(), вызываемый только
            // из property-change слушателя на Activator.getDefault().getPreferenceStore(),
            // но ComfortPreferencePage.performOk() зовёт только refreshAllEditors()
            // напрямую (без слушателя) — цвет из «Параметров» из-за этого не долетал.
            registerServerCallStyle(reconciler);
            forceRefresh(reconciler);
        }
    }

    /**
     * Mutation-free применение подсветки после wrap калькулятора. Штатный
     * {@code HighlightingReconciler.modelChanged} на переоткрытом standalone-модуле
     * часто выходит рано (~10µs) из‑за гонки {@code presenter.isCanceled} с параллельными
     * refresh Job'ами; диагностический путь, который только вызывал
     * {@code reconcilePositions}, считал позиции, но не вызывал {@code updatePresentation}.
     * Здесь — полный пайплайн Xtext до применения презентации, с debounce на реконсилер.
     */
    private static void applyHighlightingNow(Object reconciler)
    {
        try
        {
            if (!(reconciler instanceof HighlightingReconciler hr))
                return;

            XtextSourceViewer sourceViewer = resolveSourceViewer(hr);
            if (sourceViewer == null)
            {
                Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: sourceViewer null"); //$NON-NLS-1$
                return;
            }
            IXtextDocument document = sourceViewer.getXtextDocument();
            if (document == null)
            {
                Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: document null"); //$NON-NLS-1$
                return;
            }

            synchronized (pendingApplyJobs)
            {
                Job previous = pendingApplyJobs.get(hr);
                if (previous != null)
                {
                    previous.cancel();
                    Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: debounced hr=" //$NON-NLS-1$
                        + System.identityHashCode(hr));
                }

                Job job = new Job("Comfort: server-call highlighting apply") //$NON-NLS-1$
                {
                    @Override
                    protected IStatus run(IProgressMonitor monitor)
                    {
                        try
                        {
                            if (monitor.isCanceled())
                                return Status.CANCEL_STATUS;

                            Boolean got = document.tryReadOnly(resource ->
                            {
                                if (monitor.isCanceled())
                                    return Boolean.FALSE;
                                Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: LOCK ACQUIRED resource=" //$NON-NLS-1$
                                    + resource + " hr=" + System.identityHashCode(hr)); //$NON-NLS-1$
                                runFullHighlightingApply(hr, resource, monitor);
                                return Boolean.TRUE;
                            });
                            if (got == null)
                                Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: LOCK FAILED (null result)"); //$NON-NLS-1$
                        }
                        catch (Exception e)
                        {
                            Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: job failed: " //$NON-NLS-1$
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
            Global.log(TAG, "resolveSourceViewer failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Полный аналог тела {@code HighlightingReconciler.modelChanged} до
     * {@code updatePresentation}, без раннего выхода штатного {@code modelChanged}
     * (который на reopen standalone часто no-op). Управляет флагом {@code reconciling},
     * чтобы параллельный native reconcile не вошёл в середину.
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
            Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: reflection members missing"); //$NON-NLS-1$
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
            {
                Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: fReconcileLock null"); //$NON-NLS-1$
                return;
            }

            synchronized (lock)
            {
                if (reconcilingField.getBoolean(hr))
                {
                    Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: skipped already reconciling"); //$NON-NLS-1$
                    return;
                }
                reconcilingField.setBoolean(hr, true);
            }

            HighlightingPresenter presenter = null;
            try
            {
                if (monitor != null && monitor.isCanceled())
                {
                    Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: skipped canceled (monitor)"); //$NON-NLS-1$
                    return;
                }

                Object presenterObj = presenterField.get(hr);
                if (!(presenterObj instanceof HighlightingPresenter p))
                {
                    Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: presenter missing"); //$NON-NLS-1$
                    return;
                }
                presenter = p;

                presenter.setCanceled(false);
                if (presenter.isCanceled())
                {
                    Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: skipped canceled"); //$NON-NLS-1$
                    return;
                }

                startMethod.invoke(hr);
                if (presenter.isCanceled() || (monitor != null && monitor.isCanceled()))
                {
                    Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: skipped canceled (after start)"); //$NON-NLS-1$
                    return;
                }

                reconcileMethod.invoke(hr, resource, CancelIndicator.NullImpl);
                if (presenter.isCanceled() || (monitor != null && monitor.isCanceled()))
                {
                    Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: skipped canceled (after reconcile)"); //$NON-NLS-1$
                    return;
                }

                List<AttributedPosition> added =
                    (List<AttributedPosition>)addedField.get(hr);
                List<AttributedPosition> removed =
                    (List<AttributedPosition>)removedField.get(hr);
                TextPresentation textPresentation = presenter.createPresentation(added, removed);
                if (textPresentation == null
                    || presenter.isCanceled()
                    || (monitor != null && monitor.isCanceled()))
                {
                    Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: skipped canceled (after createPresentation)"); //$NON-NLS-1$
                    return;
                }

                updateMethod.invoke(hr, textPresentation, added, removed, resource);
                Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: presentation scheduled added=" //$NON-NLS-1$
                    + (added == null ? -1 : added.size()) + " removed=" //$NON-NLS-1$
                    + (removed == null ? -1 : removed.size())
                    + " hr=" + System.identityHashCode(hr)); //$NON-NLS-1$
            }
            finally
            {
                if (presenter != null)
                    presenter.setCanceled(false);
                try
                {
                    stopMethod.invoke(hr);
                }
                catch (Exception stopEx)
                {
                    Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: stopReconcilingPositions failed: " //$NON-NLS-1$
                        + stopEx.getMessage());
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
            Global.tempLog(MUTFREE_DIAG_TOPIC, "apply: runFullHighlightingApply failed: " //$NON-NLS-1$
                + cause.getClass().getSimpleName() + " " + cause.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * {@link HighlightingReconciler#refresh()} (публичный, тот же путь, что
     * {@code install()} при первом открытии редактора) НЕ годится: внутри он берёт
     * {@code document.tryReadOnly(...)} — не блокирующий вариант, который тихо
     * ничего не делает (без единой ошибки), если read-lock сразу не достался —
     * подтверждено экспериментом: после перехода на {@code refresh()} раскраска
     * серверных вызовов переставала появляться. Прямой вызов
     * {@code HighlightingReconciler.modelChanged(resource)} — та же проблема.
     * Единственный надёжный способ — настоящее изменение документа (идёт через
     * блокирующий путь), поэтому делаем тривиальный самовосстанавливающийся edit:
     * заменяем первый символ документа на него же самого, это порождает
     * {@code DocumentEvent} и запускает штатный конвейер пересвязывания.
     * <p>
     * Побочный эффект: этот {@code DocumentEvent} — даже самовосстанавливающийся —
     * взводит модифицированность редактора; {@code DirtyStateEditorSupport.markEditorClean()}
     * тут не помогает (это внутреннее состояние Xtext, а не {@code ITextEditor.isDirty()}).
     * Второй самозаменяющий edit с восстановленным modification stamp тоже не годится:
     * он порождает ещё один {@code DocumentEvent}, и {@code HighlightingReconciler
     * .updatePresentation()} (сверяет {@code resourceSet.getModificationStamp()} перед
     * применением) отбрасывает результат ПЕРВОГО пересчёта как устаревший — раскраска
     * пропадает (подтверждено экспериментом). Поэтому модифицированность снимаем без
     * единой лишней записи в документ — напрямую через {@code fCanBeSaved} в
     * {@code ElementInfo} документ-провайдера (см. {@link #resetDirtyFlagIfClean}),
     * тем же приёмом, что использует сам {@code XtextDocumentProvider
     * .UnchangedElementListener}.
     * <p>
     * При открытии редактора не используется (визуальный артефакт); остаётся для
     * {@link #refreshAllEditors()} при смене настроек цвета.
     */
    private static void forceRefresh(Object reconciler)
    {
        try
        {
            if (!(reconciler instanceof HighlightingReconciler hr))
            {
                Global.log(TAG, "forceRefresh: not a HighlightingReconciler, class=" //$NON-NLS-1$
                    + reconciler.getClass().getName());
                return;
            }

            Field sourceViewerField = findField(HighlightingReconciler.class, "sourceViewer"); //$NON-NLS-1$
            if (sourceViewerField == null)
            {
                Global.log(TAG, "forceRefresh: 'sourceViewer' field not found"); //$NON-NLS-1$
                return;
            }
            sourceViewerField.setAccessible(true);
            Object sourceViewerObj = sourceViewerField.get(hr);
            if (!(sourceViewerObj instanceof XtextSourceViewer sourceViewer))
            {
                Global.log(TAG, "forceRefresh: sourceViewer=" //$NON-NLS-1$
                    + (sourceViewerObj == null ? "null" : sourceViewerObj.getClass().getName())); //$NON-NLS-1$
                return;
            }

            IXtextDocument document = sourceViewer.getXtextDocument();
            if (document == null)
            {
                Global.log(TAG, "forceRefresh: xtextDocument null"); //$NON-NLS-1$
                return;
            }

            if (document.getLength() == 0)
            {
                Global.log(TAG, "forceRefresh: document empty, nothing to trigger on"); //$NON-NLS-1$
                return;
            }

            BslXtextEditor editorForCleanup = reconcilerEditors.get(reconciler);
            boolean wasDirty = editorForCleanup != null && editorForCleanup.isDirty();

            /*
             * Сама правка (self-replace) остаётся — единственный проверенно надёжный способ
             * реально прогнать пересчёт (см. класс-комментарий выше про refresh()/modelChanged()
             * с тихим no-op). Но её визуальный эффект (кратковременное задвоение строк, пока
             * презентация пересчитывается и переприменяется к StyledText) подавляем через
             * setRedraw — правка и обработка вызванного ею DocumentEvent идут БЕЗ перерисовки,
             * возвращаем перерисовку отложенно (следующий цикл событий), когда пересчитанная
             * подсветка уже применена и виджет можно перерисовать один раз чисто.
             */
            StyledText textWidget = sourceViewer.getTextWidget();
            if (textWidget != null && !textWidget.isDisposed())
                textWidget.setRedraw(false);
            try
            {
                char firstChar = document.getChar(0);
                document.replace(0, 1, String.valueOf(firstChar));
                Global.log(TAG, "forceRefresh: self-replace edit triggered at offset 0"); //$NON-NLS-1$
            }
            finally
            {
                if (textWidget != null && !textWidget.isDisposed())
                {
                    Display display = textWidget.getDisplay();
                    if (display != null && !display.isDisposed())
                        display.asyncExec(() ->
                        {
                            if (!textWidget.isDisposed())
                                textWidget.setRedraw(true);
                        });
                    else
                        textWidget.setRedraw(true);
                }
            }

            if (editorForCleanup != null && !wasDirty)
                resetDirtyFlagIfClean(editorForCleanup);
        }
        catch (Exception e)
        {
            Global.log(TAG, "forceRefresh failed: " + e.getClass().getSimpleName() + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void registerWindow(IWorkbenchWindow window)
    {
        window.getPartService().addPartListener(new PartAdapter());
        for (IWorkbenchPage page : window.getPages())
        {
            IEditorReference[] refs = page.getEditorReferences();
            Global.log(TAG, "registerWindow: " + refs.length + " editors in page"); //$NON-NLS-1$ //$NON-NLS-2$
            for (IEditorReference ref : refs)
                inspectEditor(ref);
        }
    }

    private static void inspectEditor(IEditorReference ref)
    {
        try
        {
            IWorkbenchPart part = ref.getPart(false);
            Global.log(TAG, "  editor: id=" + ref.getId() + " part=" //$NON-NLS-1$ //$NON-NLS-2$
                + (part == null ? "null" : part.getClass().getName()));

            if (part instanceof BslXtextEditor)
            {
                patchEditor((BslXtextEditor)part);
            }
            else if (part instanceof DtGranularEditor)
            {
                patchGranularEditor((DtGranularEditor<?>)part);
            }
        }
        catch (Exception e)
        {
            Global.log(TAG, "  inspectEditor failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Повторный триггер пересчёта при активации/выносе-наверх уже пропатченного standalone
     * BSL-редактора — форма получает такое переигрывание бесплатно через IPageChangedListener
     * на каждое переключение вкладки внутри неё, у standalone-модуля такого источника нет,
     * поэтому даём его явно. ВАЖНО: заново резолвим реконсилер через patchEditor(editor), а не
     * берём закэшированную ссылку из editorReconcilers — если Xtext успел пересоздать/переустановить
     * реконсилер после реального связывания, старая ссылка мертва и ничего не драйвит (та же
     * логика, что и у patchFormPageIfBsl для форм — он тоже резолвит заново на каждой странице).
     */
    private static void refreshOnActivate(IWorkbenchPartReference ref)
    {
        try
        {
            IWorkbenchPart part = ref.getPart(false);
            if (part instanceof BslXtextEditor editor)
            {
                patchEditor(editor);
            }
            else if (part instanceof DtGranularEditor)
            {
                patchGranularEditor((DtGranularEditor<?>)part);
            }
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
            {
                Global.log(TAG, "patchEditor: reconciler not found"); //$NON-NLS-1$
                return;
            }

            boolean firstTimeWrap = wrapCalculator(reconciler);
            if (firstTimeWrap && !patchedReconcilers.contains(reconciler))
                patchedReconcilers.add(reconciler);
            reconcilerEditors.put(reconciler, editor);
            editorReconcilers.put(editor, reconciler);
            registerServerCallStyle(reconciler);
            if (firstTimeWrap)
                Global.log(TAG, "patchEditor: OK, patched=" + patchedReconcilers.size()); //$NON-NLS-1$

            /*
             * forceRefresh() (self-replace) при открытии редактора ОТКЛЮЧЁН — визуальный
             * артефакт. Вместо него — applyHighlightingNow(): полный mutation-free пайплайн
             * до updatePresentation, с debounce. Вызывается КАЖДЫЙ раз при patchEditor
             * (не только при первом wrap) — формы получают повтор через IPageChangedListener,
             * standalone — через PartAdapter.partActivated/partBroughtToTop.
             */
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
            {
                Global.log(TAG, "getAttrProvider: 'attributeProvider' not found"); //$NON-NLS-1$
                return null;
            }
            field.setAccessible(true);
            return field.get(reconciler);
        }
        catch (Exception e)
        {
            Global.log(TAG, "getAttrProvider failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /** Регистрирует/обновляет стили {@code SERVER_CALL_ID}/{@code SERVER_CALL_CONTEXT_ID}
     *  в собственном {@code attributeProvider} конкретного реконсилера — этот
     *  провайдер не является общим синглтоном для всех редакторов, поэтому
     *  регистрировать стиль нужно у каждого патченного реконсилера отдельно. */
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
            {
                Global.log(TAG, "registerStyle: 'attributes' not found on " + attributeProvider.getClass().getName()); //$NON-NLS-1$
                return;
            }
            attrsField.setAccessible(true);
            Object mapObj = attrsField.get(attributeProvider);
            if (!(mapObj instanceof HashMap))
            {
                Global.log(TAG, "registerStyle: attributes is not HashMap"); //$NON-NLS-1$
                return;
            }
            HashMap<String, TextAttribute> attributes = (HashMap<String, TextAttribute>)mapObj;

            putServerCallStyle(attributes, BslServerCallHighlightingConfiguration.SERVER_CALL_ID,
                ComfortSettings.getServerCallHighlightingColor(),
                ComfortSettings.DEFAULT_SERVER_CALL_HIGHLIGHTING_COLOR);
            putServerCallStyle(attributes, BslServerCallHighlightingConfiguration.SERVER_CALL_CONTEXT_ID,
                ComfortSettings.getServerCallContextHighlightingColor(),
                ComfortSettings.DEFAULT_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR);

            Global.log(TAG, "registerStyle: OK"); //$NON-NLS-1$
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
        {
            Global.log(TAG, "putServerCallStyle: failed to create TextAttribute for " + id); //$NON-NLS-1$
            return;
        }
        attributes.put(id, attr);

        // TextAttributeProvider.getMergedAttributes(ids) кэширует результат
        // объединения нескольких id (напр. когда серверный вызов совпадает по
        // диапазону с нативным "Methods") под отдельным ключом
        // "$$$Merged:.../id/...$$$" и пересчитывает его ТОЛЬКО если такого
        // ключа ещё нет в attributes — иначе рендер вечно берёт объединённый
        // стиль с тем цветом, что был при первом вычислении. Точный merged-ключ
        // не собрать (порядок id заранее не известен), поэтому просто
        // выбрасываем все "$$$Merged:...$$$"-записи, где встречается этот id —
        // они лениво пересчитаются заново.
        Iterator<String> keys = attributes.keySet().iterator();
        while (keys.hasNext())
        {
            String key = keys.next();
            if (key.startsWith("$$$Merged:") && key.contains(id)) //$NON-NLS-1$
                keys.remove();
        }
    }

    /** Стиль всегда {@code SWT.NONE} — только цвет, без модификации шрифта
     *  (жирный/курсив), чтобы текст оставался штатным. */
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
            Global.log(TAG, "createTextAttr failed: " + e.getMessage()); //$NON-NLS-1$
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
            Global.tempLog(MUTFREE_DIAG_TOPIC, "wrapCalculator: OK, reconciler=" //$NON-NLS-1$
                + System.identityHashCode(reconciler));
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

    /**
     * Снимает модифицированность редактора без единой записи в документ — напрямую
     * через {@code fCanBeSaved} в {@code AbstractDocumentProvider.ElementInfo} (тот
     * же публичный флаг, что читает/пишет {@code XtextDocumentProvider
     * .UnchangedElementListener.documentChanged()}), и штатно оповещает об этом
     * оповещателем {@code fireElementDirtyStateChanged}, чтобы у {@code ITextEditor}
     * (и заголовка вкладки) сразу обновился признак "*". Оба метода/поля —
     * {@code protected}/пакетные в {@code AbstractDocumentProvider}, отсюда рефлексия.
     */
    private static void resetDirtyFlagIfClean(BslXtextEditor editor)
    {
        try
        {
            Object provider = editor.getDocumentProvider();
            Object input = editor.getEditorInput();
            if (provider == null || input == null)
                return;

            Method getElementInfo = findMethod(provider.getClass(), "getElementInfo", Object.class); //$NON-NLS-1$
            if (getElementInfo == null)
            {
                Global.log(TAG, "resetDirtyFlagIfClean: 'getElementInfo' not found"); //$NON-NLS-1$
                return;
            }
            getElementInfo.setAccessible(true);
            Object elementInfo = getElementInfo.invoke(provider, input);
            if (elementInfo == null)
            {
                Global.log(TAG, "resetDirtyFlagIfClean: elementInfo null"); //$NON-NLS-1$
                return;
            }

            Field canBeSavedField = findField(elementInfo.getClass(), "fCanBeSaved"); //$NON-NLS-1$
            if (canBeSavedField == null)
            {
                Global.log(TAG, "resetDirtyFlagIfClean: 'fCanBeSaved' not found"); //$NON-NLS-1$
                return;
            }
            canBeSavedField.setAccessible(true);
            canBeSavedField.set(elementInfo, false);

            Method fireDirty = findMethod(provider.getClass(), "fireElementDirtyStateChanged", //$NON-NLS-1$
                Object.class, boolean.class);
            if (fireDirty == null)
            {
                Global.log(TAG, "resetDirtyFlagIfClean: 'fireElementDirtyStateChanged' not found"); //$NON-NLS-1$
                return;
            }
            fireDirty.setAccessible(true);
            fireDirty.invoke(provider, input, false);

            Global.log(TAG, "resetDirtyFlagIfClean: OK"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.log(TAG, "resetDirtyFlagIfClean failed: " + e.getClass().getSimpleName() + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
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
