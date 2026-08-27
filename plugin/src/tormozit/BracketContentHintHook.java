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
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocumentListener;
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
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorActionConstants;
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
 * (по аналогии с {@link BslEditorHighlightingHook}, но без рефлексии —
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
            PatchState state = new PatchState(widget, document, viewer, editor);
            patched.put(widget, state);

            widget.addPaintListener(state.paintListener);
            widget.addCaretListener(state.caretListener);
            document.addModelListener(state.modelListener);
            document.addDocumentListener(state.docListener);
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
            state.document.removeDocumentListener(state.docListener);
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

    private static int countLineDelimiters(String text)
    {
        if (text == null || text.isEmpty())
            return 0;
        int count = 0;
        for (int i = 0; i < text.length(); i++)
        {
            if (text.charAt(i) == '\n')
                count++;
        }
        return count;
    }

    private static final class PatchState
    {
        final StyledText widget;
        final IXtextDocument document;
        final ITextViewerExtension5 lineMapper;
        final ITextEditor editor;
        final PaintListener paintListener;
        final CaretListener caretListener;
        final IXtextModelListener modelListener;
        final IDocumentListener docListener;
        final Job rebuildJob;
        volatile List<BracketContentHintIndex.Entry> index = Collections.emptyList();
        volatile int pendingOldDelims;
        /** Последнее множество подсказок, отражённое на экране (обновляется в onPaint). Только UI-поток. */
        private List<BracketContentHintPresenter.VisibleHint> lastPainted = Collections.emptyList();

        PatchState(StyledText widget, IXtextDocument document, ITextViewerExtension5 lineMapper, ITextEditor editor)
        {
            this.widget = widget;
            this.document = document;
            this.lineMapper = lineMapper;
            this.editor = editor;
            this.paintListener = this::onPaint;
            // Перерисовка от каретки — только если фактически изменилось множество
            // видимых подсказок (каретка вошла в строку подсказки / вышла), а не на
            // каждое движение: во время rebuild свёрток EDT сама двигает каретку,
            // и безусловный полный redraw только продлевал вспышку. При выключенной
            // фече множество всегда пусто — перерисовка от каретки не выполняется вовсе.
            this.caretListener = e -> {
                if (!widget.isDisposed() && ComfortSettings.isBracketContentHintEnabled())
                {
                    List<BracketContentHintPresenter.VisibleHint> current =
                        BracketContentHintPresenter.computeVisibleHints(widget, lineMapper, index,
                            ComfortSettings.getBracketContentHintMinLines());
                    if (hintsDiffer(current, lastPainted))
                        widget.redraw();
                }
            };
            this.modelListener = resource -> {
                // Не тратим время на пересчёт (даже фоновый), пока фича выключена
                // в настройках (выключена по умолчанию) — это большинство
                // пользователей плагина.
                if (ComfortSettings.isBracketContentHintEnabled())
                    scheduleRebuild(this);
            };
            this.docListener = new IDocumentListener()
            {
                @Override
                public void documentAboutToBeChanged(DocumentEvent event)
                {
                    try
                    {
                        pendingOldDelims = countLineDelimiters(document.get(event.getOffset(), event.getLength()));
                    }
                    catch (Exception e)
                    {
                        pendingOldDelims = -1;
                    }
                }

                @Override
                public void documentChanged(DocumentEvent event)
                {
                    String inserted = event.getText();
                    int oldDelims = pendingOldDelims;
                    if (oldDelims >= 0)
                        applyIndexShift(event.getOffset(), event.getLength(), inserted, oldDelims);
                }
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

        /**
         * Мгновенно (до репарса и debounce-пересчёта) сдвигает записи индекса
         * на дельту правки, чтобы подсказки сразу рисовались на правильных
         * строках: без этого между правкой документа и {@code rebuild done}
         * (~0.8 c: репарс + debounce) подсказки рисовались по устаревшим
         * номерам строк документа и на секунду «съезжали» на соседние строки.
         *
         * <p>Гасятся ({@link #mapEntry} → {@code null}) только записи, у которых
         * правка задела само закрывающее слово или стёрла строку начала —
         * их геометрию надёжно пересчитать нельзя, их вернёт пересчёт.
         */
        private void applyIndexShift(int editOffset, int editOldLength, String inserted, int oldDelims)
        {
            int lineShift = countLineDelimiters(inserted) - oldDelims;
            int insLen = inserted == null ? 0 : inserted.length();
            int charShift = insLen - editOldLength;
            if (lineShift == 0 && charShift == 0)
                return;

            // documentChanged приходит после применения правки, но префикс
            // [0, editOffset) не изменился — номер строки начала правки в
            // старых координатах равен номеру этой же позиции сейчас.
            int editStartLine = safeGetLineOfOffset(editOffset);
            if (editStartLine < 0)
                return; // координаты не определились — оставляем до пересчёта
            int editOldEndLine = editOldLength > 0 ? editStartLine + oldDelims : editStartLine;

            List<BracketContentHintIndex.Entry> current = index;
            List<BracketContentHintIndex.Entry> shifted = new ArrayList<>(current.size());
            for (BracketContentHintIndex.Entry entry : current)
            {
                BracketContentHintIndex.Entry mapped =
                    mapEntry(entry, editOffset, editOldLength, editStartLine, editOldEndLine, lineShift, charShift);
                if (mapped != null)
                    shifted.add(mapped);
            }
            index = shifted;
        }

        /**
         * Переносит запись индекса через правку. Координаты записи и правки —
         * СТАРЫЕ (на момент постройки индекса); полоса строк правки —
         * [editStartLine, editOldEndLine]. Документ уже содержит правку,
         * поэтому новую строку конца берём из него точно.
         *
         * <ul>
         * <li>правка целиком ниже конструкции → запись без изменений;
         * <li>целиком выше → сдвиг строк и офсетов на дельту;
         * <li>правка после закрывающего слова (в т.ч. ENTER в конце его
         * строки) → конструкция не сдвинулась, запись без изменений;
         * <li>правка задела само закрывающее слово или строку начала →
         * {@code null} до пересчёта;
         * <li>иначе (правка выше закрывающего слова: тело, вставка/удаление
         * строк) → офсет сдвигается на дельту, строка конца — точно из
         * документа, строка начала не меняется.
         * </ul>
         */
        private BracketContentHintIndex.Entry mapEntry(BracketContentHintIndex.Entry entry, int editOffset,
            int editOldLength, int editStartLine, int editOldEndLine, int lineShift, int charShift)
        {
            if (editStartLine > entry.endLine)
                return entry; // правка целиком ниже конструкции
            if (editOldEndLine < entry.startLine)
                return new BracketContentHintIndex.Entry(entry.startLine + lineShift, entry.endLine + lineShift,
                    entry.endOffset + charShift, entry.hintText);

            // правка задела полосу строк конструкции — точный разбор по офсетам
            int closingChar = entry.endOffset - 1; // последний символ закрывающего слова
            if (editOffset > closingChar)
                return entry; // правка после закрывающего слова — конструкция не сдвинулась
            if (editOffset + editOldLength > closingChar)
                return null; // правка задела само закрывающее слово — ждём пересчёта
            if (editStartLine < entry.startLine)
                return null; // правка переписала строку начала конструкции

            int newEndOffset = entry.endOffset + charShift;
            int newEndLine;
            try
            {
                newEndLine = document.getLineOfOffset(newEndOffset - 1);
            }
            catch (BadLocationException e)
            {
                return null;
            }
            return new BracketContentHintIndex.Entry(entry.startLine, newEndLine, newEndOffset, entry.hintText);
        }

        private int safeGetLineOfOffset(int offset)
        {
            try
            {
                return document.getLineOfOffset(offset);
            }
            catch (BadLocationException e)
            {
                return -1;
            }
        }

        private void onPaint(PaintEvent e)
        {
            if (!ComfortSettings.isBracketContentHintEnabled())
            {
                lastPainted = Collections.emptyList();
                return;
            }

            List<BracketContentHintPresenter.VisibleHint> visible = BracketContentHintPresenter.computeVisibleHints(
                widget, lineMapper, index, ComfortSettings.getBracketContentHintMinLines());
            lastPainted = visible;
            BracketContentHintPresenter.paint(e, widget, lineMapper, visible, isWhitespaceCharactersShown());
        }

        private static boolean hintsDiffer(List<BracketContentHintPresenter.VisibleHint> a,
            List<BracketContentHintPresenter.VisibleHint> b)
        {
            if (a.size() != b.size())
                return true;
            for (int i = 0; i < a.size(); i++)
            {
                BracketContentHintPresenter.VisibleHint x = a.get(i);
                BracketContentHintPresenter.VisibleHint y = b.get(i);
                if (x.widgetLine != y.widgetLine || x.widgetColorOffset != y.widgetColorOffset
                    || !x.text.equals(y.text))
                    return true;
            }
            return false;
        }

        /**
         * Включён ли режим «Показывать непечатаемые символы». Состояние берём у
         * самой штатной команды редактора (её {@code isChecked} синхронизируется
         * с настройкой при каждом её изменении) — так не приходится гадать, в
         * каком из хранилищ настроек цепочки XtextEditor лежит значение.
         */
        private boolean isWhitespaceCharactersShown()
        {
            try
            {
                IAction action = editor.getAction(ITextEditorActionConstants.SHOW_WHITESPACE_CHARACTERS);
                return action != null && action.isChecked();
            }
            catch (Exception e)
            {
                return false;
            }
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
