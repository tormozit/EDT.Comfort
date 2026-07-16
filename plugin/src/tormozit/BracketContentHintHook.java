package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
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
import org.eclipse.xtext.ui.editor.XtextSourceViewer;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.editor.model.IXtextModelListener;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Показывает содержимое начала блочной BSL-конструкции (Процедура/Функция,
 * Если, Пока/Для, Попытка, #Область, #Если) полупрозрачным текстом рядом с
 * её закрывающим ключевым словом, когда конструкция занимает много строк —
 * см. issue #1513. Устанавливает {@link PaintListener}/{@link CaretListener}
 * напрямую на {@link StyledText} каждого открытого BSL-редактора, без Guice
 * (по аналогии с {@link BslServerCallHighlightingHook}, но без рефлексии —
 * {@code getInternalSourceViewer()}/{@code getXtextDocument()} публичны).
 */
public final class BracketContentHintHook implements IStartup
{
    private static final String TAG = "BracketHint"; //$NON-NLS-1$
    /**
     * Задержка перед пересчётом индекса после {@code modelChanged} — коалесцирует
     * серию быстрых правок (например, ввод текста) в один пересчёт вместо одного
     * на каждое изменение.
     */
    private static final int REBUILD_DEBOUNCE_MS = 300;
    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final Map<StyledText, PatchState> patched = new WeakHashMap<>();
    private static final Map<DtGranularEditor<?>, IPageChangedListener> pageListeners = new HashMap<>();

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
                if (ComfortSettings.PREF_BRACKET_CONTENT_HINT_ENABLED.equals(prop)
                    || ComfortSettings.PREF_BRACKET_CONTENT_HINT_MIN_LINES.equals(prop))
                {
                    Global.log(TAG, "property changed: " + prop); //$NON-NLS-1$
                    PlatformUI.getWorkbench().getDisplay().asyncExec(BracketContentHintHook::refreshAllEditors);
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
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display == null || display.isDisposed())
        {
            Global.log(TAG, "refreshAllEditors: display null/disposed"); //$NON-NLS-1$
            return;
        }
        Global.log(TAG, "refreshAllEditors: redrawing " + patched.size() + " widgets"); //$NON-NLS-1$ //$NON-NLS-2$
        for (StyledText widget : new ArrayList<>(patched.keySet()))
        {
            if (!widget.isDisposed())
                widget.redraw();
        }
    }

    private static void registerWindow(IWorkbenchWindow window)
    {
        window.getPartService().addPartListener(new PartAdapter());
        for (IWorkbenchPage page : window.getPages())
        {
            IEditorReference[] refs = page.getEditorReferences();
            for (IEditorReference ref : refs)
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
            ISourceViewer viewerObj = editor.getInternalSourceViewer();
            if (!(viewerObj instanceof XtextSourceViewer viewer))
                return;

            StyledText widget = viewer.getTextWidget();
            if (widget == null || widget.isDisposed() || patched.containsKey(widget))
                return;

            IXtextDocument document = viewer.getXtextDocument();
            if (document == null)
                return;

            // XtextSourceViewer extends ProjectionViewer implements ITextViewerExtension5 —
            // нужен для перевода номеров строк документа в номера строк виджета
            // с учётом свёрнутых (folding) блоков, см. BracketContentHintPresenter.
            PatchState state = new PatchState(widget, document, viewer);
            patched.put(widget, state);

            widget.addPaintListener(state.paintListener);
            widget.addCaretListener(state.caretListener);
            document.addModelListener(state.modelListener);
            widget.addDisposeListener(e -> unpatch(widget));

            // ВАЖНО: здесь, в самом patchEditor (вызывается прямо из обработчика
            // partOpened, пока редактор ещё инициализируется), НЕЛЬЗЯ синхронно
            // дёргать document.readOnly() — блокирующее ожидание read-lock'а на
            // UI-потоке в этот момент приводит к deadlock'у с фоновой задачей
            // первичного парсинга/связывания (та сама ждёт UI-поток). Здесь мы
            // только регистрируем слушатели — первый пересчёт индекса произойдёт
            // позже, реактивно, когда придёт modelChanged (см. PatchState.rebuildJob:
            // там document.readOnly() уже безопасен, так как вызывается из
            // отдельного фонового Job, а не с UI-потока внутри partOpened).
            Global.log(TAG, "patchEditor: OK, patched=" + patched.size()); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.log(TAG, "patchEditor failed: " + e.getClass().getSimpleName() + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void unpatch(StyledText widget)
    {
        PatchState state = patched.remove(widget);
        if (state == null)
            return;
        try
        {
            if (!widget.isDisposed())
            {
                widget.removePaintListener(state.paintListener);
                widget.removeCaretListener(state.caretListener);
            }
            state.document.removeModelListener(state.modelListener);
            state.rebuildJob.cancel();
        }
        catch (Exception ignored)
        {
            // редактор/документ уже закрыт — снимать больше нечего
        }
    }

    /**
     * Xtext вызывает {@link IXtextModelListener#modelChanged} НА UI-ПОТОКЕ по
     * дизайну (см. {@code XtextDocument.notifyModelListenersOnUiThread()} —
     * "we run the IXtextModelListeners on the UI thread"). Поэтому сам обход
     * AST ({@link BracketContentHintIndex#build}) здесь делать нельзя — на
     * файле в 50 000 строк это заметная блокировка UI (~30-50 мс) на каждое
     * реальное изменение документа. Вместо этого только планируем пересчёт в
     * фоновом {@link Job} — сам {@code modelChanged} остаётся мгновенным.
     */
    private static void scheduleRebuild(PatchState state)
    {
        state.rebuildJob.cancel();
        state.rebuildJob.schedule(REBUILD_DEBOUNCE_MS);
    }

    private static final class PatchState
    {
        final StyledText widget;
        final IXtextDocument document;
        final ITextViewerExtension5 lineMapper;
        final PaintListener paintListener;
        final CaretListener caretListener;
        final IXtextModelListener modelListener;
        final Job rebuildJob;
        volatile List<BracketContentHintIndex.Entry> index = Collections.emptyList();

        PatchState(StyledText widget, IXtextDocument document, ITextViewerExtension5 lineMapper)
        {
            this.widget = widget;
            this.document = document;
            this.lineMapper = lineMapper;
            this.paintListener = this::onPaint;
            this.caretListener = e -> {
                if (!widget.isDisposed())
                    widget.redraw();
            };
            this.modelListener = resource -> {
                // Не тратим время на пересчёт (даже фоновый), пока фича выключена
                // в настройках (выключена по умолчанию) — это большинство
                // пользователей плагина.
                if (ComfortSettings.isBracketContentHintEnabled())
                    scheduleRebuild(this);
            };
            this.rebuildJob = new Job("Comfort: индекс подсказок BSL-конструкций") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    List<BracketContentHintIndex.Entry> result;
                    try
                    {
                        // document.readOnly(...) — штатный безопасный доступ к
                        // XtextResource из ФОНОВОГО потока (в отличие от вызова
                        // из patchEditor/partOpened на UI-потоке, см. комментарий
                        // там — здесь это не приводит к deadlock'у).
                        result = document.readOnly(res -> BracketContentHintIndex.build(res, document));
                    }
                    catch (Exception e)
                    {
                        Global.log(TAG, "rebuildJob failed: " + e.getMessage()); //$NON-NLS-1$
                        return Status.CANCEL_STATUS;
                    }
                    if (monitor.isCanceled())
                        return Status.CANCEL_STATUS;

                    index = result;
                    Display display = PlatformUI.getWorkbench().getDisplay();
                    if (display != null && !display.isDisposed())
                    {
                        display.asyncExec(() -> {
                            if (!widget.isDisposed())
                                widget.redraw();
                        });
                    }
                    return Status.OK_STATUS;
                }
            };
            this.rebuildJob.setSystem(true); // не показывать в Progress View — внутренняя декоративная фича
            this.rebuildJob.setPriority(Job.DECORATE);
        }

        private void onPaint(PaintEvent e)
        {
            if (!ComfortSettings.isBracketContentHintEnabled())
                return;

            List<BracketContentHintPresenter.VisibleHint> visible = BracketContentHintPresenter.computeVisibleHints(
                widget, lineMapper, index, ComfortSettings.getBracketContentHintMinLines());
            BracketContentHintPresenter.paint(e, widget, visible);
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

        @Override public void partActivated(IWorkbenchPartReference ref) {}
        @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
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

        @Override public void windowClosed(IWorkbenchWindow window) {}
    }
}
