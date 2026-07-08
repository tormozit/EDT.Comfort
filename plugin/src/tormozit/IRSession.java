package tormozit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.ui.IEditorInput;
import org.eclipse.text.undo.DocumentUndoManagerRegistry;
import org.eclipse.text.undo.IDocumentUndoManager;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.TextRegion;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.sun.jna.platform.win32.WinDef.HWND;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

public final class IRSession
    {
        public final IRApplication.State state;
        public final LocalDateTime startTime;
        public final long pid;
        public final String platformVersion;
        final Object root; // V8X.Application
        final Object processObj; // WMIProcess
        public String appTitle;
        public org.eclipse.core.resources.IProject project;
        public final ExecutorService executor; // Выделенный поток для всех операций с этой COM-сессией
        /** Не null, если ИР подключён портативно (ирПортативный.epf), а не через расширение.
         *  В этом случае getModule() использует эту форму вместо root (COM-приложения). */
        public Object moduleRoot = null;
        public InfobaseReference infobase;
        public IInfobaseApplication application;
        public Object codeEditor = null; // ирКлсПолеТекстаПрограммы
        public TextRegion changedTextRange = null;
        public String newTextOfRange = "";

        /** project-relative путь .bsl → hash содержимого на момент последнего setText в ИР. */
        private final Map<String, byte[]> pushedSignatures = new ConcurrentHashMap<>();

        /** Порт RDT {@code КэшНаборовСлов}: имя набора → таблица слов на время COM-сессии. */
        private final ConcurrentHashMap<String, List<IrBslCompletionSupport.WordEntry>> irWordSetCache =
            new ConcurrentHashMap<>();

        /** Последний модуль и текст, ушедший в ИР через {@link #setText} (для обратного remap диапазона). */
        private String lastSyncedModuleName = ""; //$NON-NLS-1$
        /** {@code doc.get()} на момент sync — координаты для {@link #syncCodeEditorFromIR}. */
        String lastSyncedRawText = ""; //$NON-NLS-1$
        private String lastSyncedLfText = ""; //$NON-NLS-1$

        /** Кэш HWND главного окна ИР (native value) для повторных modal-сессий. */
        private volatile long cachedIrMainHwnd;

        IRSession(IRApplication.State state, LocalDateTime startTime, long pid, String platformVersion,
                  Object root, Object processObj, String appTitle, org.eclipse.core.resources.IProject project,
                  ExecutorService executor, InfobaseReference infobase)
        {
            this.state = state;
            this.startTime = startTime;
            this.pid = pid;
            this.platformVersion = platformVersion;
            this.root = root;
            this.processObj = processObj;
            this.appTitle = appTitle;
            this.project = project;
            this.executor = executor;
            this.infobase = infobase;
        }

        boolean isAlreadyPushed(String bslPath, byte[] hash)
        {
            if (bslPath == null || hash == null)
                return false;
            byte[] prev = pushedSignatures.get(bslPath);
            return prev != null && Arrays.equals(prev, hash);
        }

        void markPushed(String bslPath, byte[] hash)
        {
            if (bslPath == null || hash == null)
                return;
            pushedSignatures.put(bslPath, hash.clone());
        }

        void resetPushedSignatures()
        {
            pushedSignatures.clear();
        }

        List<IrBslCompletionSupport.WordEntry> getCachedWordSet(String setName)
        {
            if (setName == null)
                return null;
            return irWordSetCache.get(setName);
        }

        void putCachedWordSet(String setName, List<IrBslCompletionSupport.WordEntry> words)
        {
            if (setName == null || words == null)
                return;
            irWordSetCache.put(setName, List.copyOf(words));
        }

        /** Кэшированный HWND главного окна ИР (для повторных modal-сессий). */
        com.sun.jna.platform.win32.WinDef.HWND getCachedIrMainHwnd()
        {
            if (cachedIrMainHwnd == 0 || pid <= 0)
                return null;
            com.sun.jna.platform.win32.WinDef.HWND hwnd =
                WinWindowActivator.hwndFromNative(cachedIrMainHwnd);
            if (hwnd == null || !WinWindowActivator.isProcessWindow(hwnd, (int) pid))
            {
                cachedIrMainHwnd = 0;
                return null;
            }
            return hwnd;
        }

        void cacheIrMainHwnd(com.sun.jna.platform.win32.WinDef.HWND hwnd)
        {
            if (WinWindowActivator.isNullHwnd(hwnd))
                return;
            cachedIrMainHwnd = com.sun.jna.Pointer.nativeValue(hwnd.getPointer());
        }

        public Object getModule(String name)
        {
            return ComBridge.getProperty(moduleRoot != null ? moduleRoot : root, name);
        }
        public <T> T executeOnComThread(Callable<T> task) {
            if (state != IRApplication.State.CONNECTED)
            {
                IRApplication.notifyWaitForIrConnection();
                throw new IllegalStateException();
            }
            if (executor == null || executor.isShutdown()) {
                throw new IllegalStateException("COM-executor не инициализирован или остановлен");
            }
            try {
                return executor.submit(task).get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                RuntimeException toThrow = new RuntimeException("Прервано ожидание COM-потока", e); //$NON-NLS-1$
                notifyComThreadError(toThrow);
                throw toThrow;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                RuntimeException toThrow;
                if (cause instanceof RuntimeException)
                    toThrow = (RuntimeException) cause;
                else
                    toThrow = new RuntimeException("Ошибка в COM-потоке", cause); //$NON-NLS-1$
                notifyComThreadError(toThrow);
                throw toThrow;
            } catch (TimeoutException e) {
                RuntimeException toThrow = new RuntimeException("Таймаут ожидания COM-потока (10 сек)", e); //$NON-NLS-1$
                notifyComThreadError(toThrow);
                throw toThrow;
            }
        }

        private static void notifyComThreadError(Throwable cause)
        {
            String detail = cause != null ? cause.getMessage() : null;
            if (detail == null || detail.isEmpty())
                detail = cause != null ? cause.toString() : ""; //$NON-NLS-1$
            detail = ComBridge.formatErrorForNotification(detail);
            final String message = "Ошибка вызова ИР: " + detail; //$NON-NLS-1$
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> ToastNotification.show(IRApplication.toastTitle(), message, 5_000));
        }

        public boolean isProcessAlive() {
            if (pid <= 0) return true; // pid неизвестен — не блокируем
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        }

        public void syncCodeEditorToIR(BslXtextEditor editor)
        {
            ISourceViewer viewer = editor.getInternalSourceViewer();
            Object sel = viewer.getSelectionProvider().getSelection();
            ITextSelection textSelection = (ITextSelection) sel;
            int offset = textSelection.getOffset();
            int endOffset = offset + textSelection.getLength();
            syncCodeEditorToIR(editor, offset, endOffset);
        }

        /** Синхронизация модуля в codeEditor с кареткой в точке hover (свёрнутое выделение). */
        public void syncCodeEditorToIR(BslXtextEditor editor, int offset)
        {
            syncCodeEditorToIR(editor, offset, offset);
        }

        public void syncCodeEditorToIR(BslXtextEditor editor, int offset, int endOffset)
        {
            CodeEditorSyncPayload payload = prepareCodeEditorSync(editor, offset, endOffset);
            if (payload == null)
                return;
            executeOnComThread(() -> {
                applyCodeEditorSync(payload);
                return null;
            });
        }

        /** Подготовка sync для команд ИР на UI; применение — {@link #applyPreparedCodeEditorSync} на {@link #executor}. */
        CodeEditorSyncPayload prepareCodeEditorSyncFromEditor(BslXtextEditor editor)
        {
            if (editor == null)
                return null;
            ISourceViewer viewer = editor.getInternalSourceViewer();
            if (viewer == null)
                return null;
            Object sel = viewer.getSelectionProvider().getSelection();
            if (!(sel instanceof ITextSelection textSelection))
                return null;
            int offset = textSelection.getOffset();
            return prepareCodeEditorSync(editor, offset, offset + textSelection.getLength());
        }

        /** Подготовка данных sync на потоке вызывающего (hover job / UI). Без collect pending. */
        CodeEditorSyncPayload prepareCodeEditorSyncForHover(BslXtextEditor editor, int offset)
        {
            return prepareCodeEditorSync(editor, offset, offset, false);
        }

        /** Assist: caret-точка на UI, {@code collectPending=true}. */
        CodeEditorSyncPayload prepareCodeEditorSyncForAssist(BslXtextEditor editor, int offset, int endOffset)
        {
            return prepareCodeEditorSync(editor, offset, endOffset, true);
        }

        /** Применить подготовленный sync на потоке {@link #executor}. */
        void applyPreparedCodeEditorSync(CodeEditorSyncPayload payload)
        {
            if (payload != null)
                applyCodeEditorSync(payload);
        }

        private CodeEditorSyncPayload prepareCodeEditorSync(
            BslXtextEditor editor, int offset, int endOffset)
        {
            return prepareCodeEditorSync(editor, offset, endOffset, true);
        }

        private CodeEditorSyncPayload prepareCodeEditorSync(
            BslXtextEditor editor, int offset, int endOffset, boolean collectPending)
        {
            if (editor == null)
                return null;
            ISourceViewer viewer = editor.getInternalSourceViewer();
            if (viewer == null || !(viewer.getDocument() instanceof IXtextDocument doc))
                return null;

            String currentModuleName = GetRef.resolveSetTextModuleName(editor);
            String text = doc.get();
            String currentBslPath = ""; //$NON-NLS-1$
            IEditorInput input = editor.getEditorInput();
            if (input != null)
            {
                IFile file = input.getAdapter(IFile.class);
                if (file != null)
                    currentBslPath = file.getProjectRelativePath().toString().replace('\\', '/');
            }

            byte[] currentHash = collectPending
                ? IRModuleChangeCollector.contentFingerprint(text)
                : null;

            List<IRModuleChangeCollector.ModuleSyncEntry> pending = collectPending
                ? IRModuleChangeCollector.collectPendingModules(
                    this, project, infobase, currentModuleName)
                : List.of();

            return new CodeEditorSyncPayload(
                text, currentModuleName, offset, endOffset, currentBslPath, currentHash, pending);
        }

        private void applyCodeEditorSync(CodeEditorSyncPayload payload)
        {
            ensureCodeEditor();
            discardPendingUserMessages();
            for (IRModuleChangeCollector.ModuleSyncEntry e : payload.pending)
            {
                setTextQuiet(e.text, e.moduleName, 0, 0);
                markPushed(e.bslPath, e.hash);
                IRModuleSyncDebug.logPushed(e.moduleName, e.bslPath);
            }
            setTextQuiet(payload.text, payload.moduleName, payload.offset, payload.endOffset);
            if (!payload.bslPath.isEmpty() && payload.hash != null)
                markPushed(payload.bslPath, payload.hash);
            pumpUserMessagesToUi();
        }

        static final class CodeEditorSyncPayload
        {
            final String text;
            final String moduleName;
            final int offset;
            final int endOffset;
            final String bslPath;
            final byte[] hash;
            final List<IRModuleChangeCollector.ModuleSyncEntry> pending;
            CodeEditorSyncPayload(
                String text, String moduleName, int offset, int endOffset,
                String bslPath, byte[] hash, List<IRModuleChangeCollector.ModuleSyncEntry> pending)
            {
                this.text = text;
                this.moduleName = moduleName;
                this.offset = offset;
                this.endOffset = endOffset;
                this.bslPath = bslPath;
                this.hash = hash;
                this.pending = pending;
            }
        }

        private void ensureCodeEditor()
        {
            if (codeEditor == null)
            {
                Object irCache = getModule("ирКэш"); //$NON-NLS-1$
                codeEditor = ComBridge.invoke(irCache, "ПолеТекстаПрограммы", 0); //$NON-NLS-1$
            }
        }

        /** Сброс очереди сообщений ИР без показа (порт RDT перед {@code УстановитьТекст}). COM-поток. */
        void discardPendingUserMessages()
        {
            if (!canPumpUserMessages())
                return;
            ensureCodeEditor();
            ComBridge.drainUserMessages(codeEditor);
            IrUserMessagesDebug.step("discard", ""); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** Прочитать очередь сообщений ИР и показать тост на UI. COM-поток. */
        public void pumpUserMessagesToUi()
        {
            if (!canPumpUserMessages())
                return;
            ensureCodeEditor();
            ComBridge.UserMessageDrainResult drained = ComBridge.drainUserMessages(codeEditor);
            if (drained.text.isEmpty())
            {
                IrUserMessagesDebug.step("pump", "empty"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            IrUserMessagesDebug.log("show len=" + drained.text.length() //$NON-NLS-1$
                + (drained.moduleRef.isEmpty() ? "" : " ref=" + drained.moduleRef)); //$NON-NLS-1$ //$NON-NLS-2$
            showUserMessageOnUi(drained.text, drained.moduleRef);
        }

        /** COM {@code codeEditor} + прокачка сообщений. Только COM-поток {@link #executor}. */
        public Object invokeCodeEditor(String method, Object... args)
        {
            Object result = invokeCodeEditorQuiet(method, args);
            pumpUserMessagesToUi();
            return result;
        }

        /** COM {@code codeEditor} без прокачки (пакетный sync). COM-поток. */
        Object invokeCodeEditorQuiet(String method, Object... args)
        {
            ensureCodeEditor();
            return ComBridge.invoke(codeEditor, method, args);
        }

        private boolean canPumpUserMessages()
        {
            return state == IRApplication.State.CONNECTED;
        }

        private void showUserMessageOnUi(String text, String moduleRef)
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() ->
            {
                Runnable action = null;
                String actionLabel = null;
                if (moduleRef != null && !moduleRef.isBlank())
                {
                    final String ref = moduleRef.strip();
                    action = () ->
                    {
                        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                        if (window == null)
                            return;
                        IProject p = project;
                        GoToDefinition.jump(ref, window.getShell(), window.getActivePage(), p);
                    };
                    actionLabel = "Открыть модуль"; //$NON-NLS-1$
                }
                ToastNotification.show(
                    IRApplication.toastTitle(), text, 5_000, action, actionLabel);
            });
        }

        /** Синхронизация текста «Редактора запроса» в поле ИР (язык запросов). */
        public void syncQueryEditorToIR(ISourceViewer viewer)
        {
            if (viewer == null)
                return;

            IDocument doc = viewer.getDocument();
            if (doc == null)
                return;

            Object sel = viewer.getSelectionProvider().getSelection();
            ITextSelection textSelection = sel instanceof ITextSelection ts ? ts : new TextSelection(0, 0);
            final String text = doc.get();
            final int offset = textSelection.getOffset();
            final int endOffset = offset + textSelection.getLength();

            executeOnComThread(() -> {
                ensureCodeEditor();
                ComBridge.setProperty(codeEditor, "ЯзыкПрограммы", 1); //$NON-NLS-1$
                setText(text, "", offset, endOffset); //$NON-NLS-1$
                pumpUserMessagesToUi();
                return null;
            });
            // setText с "" moduleName не сохраняет lastSyncedRawText — сохраняем явно
            lastSyncedRawText = text;
            lastSyncedLfText = Global.normalizeLineSeparators(text);
        }

        // порт ПередатьИзмененияИзПоляТекстаВОкноМодуля + ПередатьГраницыВыделенияИзПолеТекстаПрограммы
        public void syncCodeEditorFromIR(BslXtextEditor editor)
        {
            if (editor == null)
                return;
            syncTextEditorFromIR(editor.getInternalSourceViewer(), 0);
            TextEditor.focusBslEditor(editor);
        }

        /** Возврат правок из поля ИР в произвольный {@link ISourceViewer} (BSL, запрос и т.п.). */
        public void syncTextEditorFromIR(ISourceViewer viewer, int endOffsetAdjustment)
        {
            if (viewer == null)
                return;

            boolean hasReplace = Boolean.TRUE.equals(executeOnComThread(this::hasReplaceableRange));
            if (hasReplace)
            {
                executeOnComThread(() -> {
                    readChangedTextRange();
                    return null;
                });
                IDocument doc = viewer.getDocument();
                if (doc == null)
                    return;

                int offsetAdjust = TextEditor.saveSelectionBoundsForUndo(viewer);
                final int replaceOffset = changedTextRange.getOffset() + offsetAdjust;
                final int replaceLength = changedTextRange.getLength();
                final String insertText = newTextOfRange;

                if (doc instanceof IXtextDocument xtextDoc)
                {
                    xtextDoc.modify(resource -> {
                        try
                        {
                            xtextDoc.replace(replaceOffset, replaceLength, insertText);
                        }
                        catch (BadLocationException e)
                        {
                            throw new RuntimeException("Ошибка позиционирования при вставке текста из ИР", e); //$NON-NLS-1$
                        }
                        return null;
                    });
                }
                else
                {
                    try
                    {
                        doc.replace(replaceOffset, replaceLength, insertText);
                    }
                    catch (BadLocationException e)
                    {
                        throw new RuntimeException("Ошибка позиционирования при вставке текста из ИР", e); //$NON-NLS-1$
                    }
                }

                IDocumentUndoManager undoManager = DocumentUndoManagerRegistry.getDocumentUndoManager(doc);
                if (undoManager != null)
                    undoManager.commit();
                changedTextRange = null;
                newTextOfRange = ""; //$NON-NLS-1$
            }
            syncSelectionFromIR(viewer, endOffsetAdjustment);
        }

        public void syncQueryEditorFromIR(ISourceViewer viewer)
        {
            syncTextEditorFromIR(viewer, 0);
            TextEditor.focusSourceViewer(viewer);
        }

        /** Порт ПередатьГраницыВыделенияИзПолеТекстаПрограммы. */
        public void syncSelectionFromIR(BslXtextEditor editor)
        {
            syncSelectionFromIR(editor, 0);
        }

        public void syncSelectionFromIR(BslXtextEditor editor, int endOffsetAdjustment)
        {
            if (editor == null)
                return;
            syncSelectionFromIR(editor.getInternalSourceViewer(), endOffsetAdjustment);
        }

        public void syncSelectionFromIR(ISourceViewer viewer, int endOffsetAdjustment)
        {
            if (viewer == null)
                return;
            IDocument doc = viewer.getDocument();
            if (doc == null)
                return;

            final int adj = endOffsetAdjustment;
            int[] lfSel = executeOnComThread(() -> readIrSelectionLf(adj));
            if (lfSel == null)
                return;

            String raw = doc.get();
            int docStart = Global.remapOffsetFromLf(raw, lfSel[0]);
            int docEnd = Global.remapOffsetFromLf(raw, lfSel[1]);
            IRModuleSyncDebug.logSelectionFromIr(lfSel[0], lfSel[1], docStart, docEnd);
            viewer.setSelectedRange(docStart, Math.max(0, docEnd - docStart));
        }
        /**
         * 
         */
        public void openTextEditor(String text, String sourceRef)
        {
            Object irClient = getModule("ирКлиент"); //$NON-NLS-1$
            String lfText = Global.normalizeLineSeparators(text != null ? text : ""); //$NON-NLS-1$
            // (Текст, Знач Заголовок = "", ВариантПросмотра = "Компактный", ТолькоПросмотр = Ложь, Знач КлючУникальности = Неопределено, ВладелецФормы = Неопределено, ВыделитьВсе = Ложь,
            // Знач Модально = Ложь, ВыделениеДвумерное = Неопределено, Знач ИскомаяСтрока = "", Знач КлючИсточника = "")
            ComBridge.invoke(irClient, "ОткрытьТекстЛкс", lfText, sourceRef, null, false, sourceRef, null, false, false, null, "", sourceRef); //$NON-NLS-1$                
        }
        
        public void setText(String text
            , String moduleName
            , int startOffset // from 0
            , int endOffset // from 0
        )
        {
            discardPendingUserMessages();
            setTextQuiet(text, moduleName, startOffset, endOffset);
        }

        private void setTextQuiet(String text
            , String moduleName
            , int startOffset
            , int endOffset
        )
        {
            Global.LfTextSlice slice = Global.toLfWithSelection(text, startOffset, endOffset);
            if (moduleName != null && !moduleName.isEmpty() && slice.text() != null)
            {
                lastSyncedModuleName = moduleName;
                lastSyncedRawText = text != null ? text : ""; //$NON-NLS-1$
                lastSyncedLfText = slice.text();
            }
            IRModuleSyncDebug.logSetTextSelection(
                startOffset, endOffset, slice.start(), slice.end(), Global.countCrlf(text));
            invokeCodeEditorQuiet("УстановитьТекст", slice.text(), false, null, false, null, moduleName, slice.start() + 1, //$NON-NLS-1$
                slice.end() + 1);
        }

        public Object replaceSelectedText(String text)
        {
            String lfText = Global.normalizeLineSeparators(text != null ? text : ""); //$NON-NLS-1$
            return ComBridge.toString(invokeCodeEditor("ВставитьИзмененныйТекстовыйЛитерал", lfText)); //$NON-NLS-1$
       }

        public void readChangedTextRange()
        {
            newTextOfRange = Global.normalizeLineSeparators(
                ComBridge.toString(ComBridge.getProperty(codeEditor, "мЗамещающийФрагмент"))); //$NON-NLS-1$
            Object comRange = ComBridge.getProperty(codeEditor, "мЗаменяемыйДиапазон"); //$NON-NLS-1$
            int irLfStart = (int) ComBridge.toLong(ComBridge.getProperty(comRange, "Начало")) - 1; //$NON-NLS-1$
            int irLfEnd = (int) ComBridge.toLong(ComBridge.getProperty(comRange, "Конец")) - 1; //$NON-NLS-1$
            String raw = lastSyncedRawText != null ? lastSyncedRawText : ""; //$NON-NLS-1$
            int rangeStart = Global.remapOffsetFromLf(raw, irLfStart);
            int rangeEnd = Global.remapOffsetFromLf(raw, irLfEnd);
            IRModuleSyncDebug.logRangeFromIr(irLfStart, irLfEnd, rangeStart, rangeEnd);
            changedTextRange = new TextRegion(rangeStart, rangeEnd - rangeStart);
        }

        /** {@code мЗаменяемыйДиапазон} задан. Вызывать только из COM-потока. */
        private boolean hasReplaceableRange()
        {
            ensureCodeEditor();
            Object comRange = ComBridge.getProperty(codeEditor, "мЗаменяемыйДиапазон"); //$NON-NLS-1$
            return comRange != null;
        }

        /** [lfStart, lfEndExclusive] из {@code ПолеТекста.ВыделениеОдномерное()} или {@code null}. COM-поток. */
        private int[] readIrSelectionLf(int endOffsetAdjustment)
        {
            ensureCodeEditor();
            Object fieldText = ComBridge.getProperty(codeEditor, "ПолеТекста"); //$NON-NLS-1$
            if (fieldText == null)
                return null;
            Object sel1d = ComBridge.invoke(fieldText, "ВыделениеОдномерное"); //$NON-NLS-1$
            if (sel1d == null)
                return null;
            int irLfStart = (int) ComBridge.toLong(ComBridge.getProperty(sel1d, "Начало")) - 1; //$NON-NLS-1$
            int irLfEnd = (int) ComBridge.toLong(ComBridge.getProperty(sel1d, "Конец")) - 1 + endOffsetAdjustment; //$NON-NLS-1$
            return new int[] { irLfStart, irLfEnd };
        }

        public String selectTextLiteral()
        {
//            Функция ВыделитьТекстовыйЛитерал(Знач ПолеТекстаЛ = Неопределено, выхНачальнаяПозиция0 = 0, выхКонечнаяПозиция0 = 0, Знач РазбиратьКонтекст = Истина, выхВыражение = "",
//                Знач РазрешитьПотерюКомментариев = Истина) Экспорт 
            return ComBridge.toString(invokeCodeEditor("ВыделитьТекстовыйЛитерал", null, null, null, true, null, false)); //$NON-NLS-1$
        }

        // Модальный
        public boolean openTextLiteralEditor()
        {
            return ComBridge.toBoolean(invokeCodeEditor("ОткрытьРедакторТекстовогоЛитерала", null, null, null, true, null, false)); //$NON-NLS-1$
        }

        public void showWindow()
        {
           ComBridge.setProperty(root, "Visible", true);
           if (pid > 0)
               WinWindowActivator.activateMainWindow(pid);
         }

        public void openJobConsole()
        {
            executeOnComThread(() -> {
                showWindow();
                ComBridge.invoke(getModule("ирКлиент"), "ОткрытьКонсольЗаданийЛкс", true); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            });
        }

        /**
         * Выполняет блокирующий COM-вызов ИР в псевдомодальном режиме EDT↔ИР.
         * Вызывать только из {@link #executor}.
         */
        public <T> T runIrModalDialog(Pattern titlePattern, long waitForDialogMs, Callable<T> action)
            throws Exception
        {
            return runIrModalDialog(titlePattern, waitForDialogMs, action, null);
        }

        /**
         * @param keepIrOpenOnResult если предикат вернёт {@code true} для результата {@code action},
         *     псевдомодальность снимается, но окно ИР остаётся видимым (аналог
         *     {@code ВосстановитьОкноПриложения} в RDT)
         */
        public <T> T runIrModalDialog(Pattern titlePattern, long waitForDialogMs, Callable<T> action,
            Predicate<T> keepIrOpenOnResult) throws Exception
        {
            if (state != IRApplication.State.CONNECTED)
                throw new IllegalStateException("ИР не подключён"); //$NON-NLS-1$

            if (!WinWindowActivator.isWindows() || pid <= 0)
            {
                IrModalWindowDebug.problem("runIrModalDialog без Win32 — прямой вызов"); //$NON-NLS-1$
                return action.call();
            }

            IrModalWindowSession modal = IrModalWindowSession.begin(this, titlePattern, waitForDialogMs);
            T result;
            try
            {
                result = action.call();
            }
            catch (Exception e)
            {
                modal.end();
                throw e;
            }
            if (keepIrOpenOnResult != null && keepIrOpenOnResult.test(result))
                modal.endLeaveIrVisible();
            else
                modal.end();
            return result;
        }


    // -----------------------------------------------------------------------
    // Адаптер вставки автодополнения (порт RDT ПриВыбореЗначенияТΟ)
    // -----------------------------------------------------------------------

    /** Результат {@code Адаптер_ПриВыбореСтрокиАвтодополнения} (порт RDT). */
    public static final class CompletionAdapterResult
    {
        /** {@code null} — адаптер не вернул шаблон (НовыйШаблон = Неопределено). */
        public final String newTemplate;
        /** Порт выходного Параметра ФорматироватьТекст. */
        public final boolean formatText;
        /** Порт выходного Параметра ЛиГенераторСПоглощениемНачалаСтроки. */
        public final boolean isGeneratorWithLineStart;
        /** 0-based LF-смещение начала удаляемого диапазона, {@code -1} если не задан. */
        public final int deleteFromLf;
        /** 0-based LF-смещение конца удаляемого диапазона, {@code -1} если не задан. */
        public final int deleteToLf;

        CompletionAdapterResult(String newTemplate, boolean formatText,
            boolean isGeneratorWithLineStart, int deleteFromLf, int deleteToLf)
        {
            this.newTemplate = newTemplate;
            this.formatText = formatText;
            this.isGeneratorWithLineStart = isGeneratorWithLineStart;
            this.deleteFromLf = deleteFromLf;
            this.deleteToLf = deleteToLf;
        }
    }

    /**
     * Порт RDT {@code Адаптер_ПриВыбореСтрокиАвтодополнения}.
     * Вызывать только из COM-потока {@link #executor}.
     *
     * @return {@code null} если {@code codeEditor} недоступен
     */
    public CompletionAdapterResult invokeCompletionAdapter(
        String wordValue, boolean isMethod, String dictionaryKey, String currentTemplate)
    {
        ensureCodeEditor();
        if (codeEditor == null)
            return null;

        // Byref-параметры (выходные, без Знач)
        Object templateVar   = ComBridge.createByrefString(currentTemplate != null ? currentTemplate : ""); //$NON-NLS-1$
        Object formatTextVar = ComBridge.createByrefBool(false);
        Object isGeneratorVar = ComBridge.createByrefBool(false);

        // Функция Адаптер_ПриВыбореСтрокиАвтодополнения(
        //   Знач Значение, Знач ЭтоМетод, Знач КлючСловаря,
        //   ШаблонДляВставки, ФорматироватьТекст, ЛиГенераторСПоглощениемНачалаСтроки
        // ) Экспорт
        Object raw = invokeCodeEditorQuiet(
            "Адаптер_ПриВыбореСтрокиАвтодополнения", //$NON-NLS-1$
            wordValue,
            isMethod,
            dictionaryKey != null ? dictionaryKey : "", //$NON-NLS-1$
            templateVar,
            formatTextVar,
            isGeneratorVar);

        // Читаем выходные byref-параметры
        boolean formatText   = ComBridge.readByrefBool(formatTextVar);
        boolean isGenerator  = ComBridge.readByrefBool(isGeneratorVar);

        // Функция возвращает НовыйШаблон или Неопределено
        String newTemplate = ComBridge.isVariantUndefined(raw) ? null : ComBridge.toString(raw);

        // Если генератор с поглощением — читаем мЗаменяемыйДиапазон
        int deleteFromLf = -1;
        int deleteToLf   = -1;
        if (isGenerator)
        {
            try
            {
                Object comRange = ComBridge.getProperty(codeEditor, "мЗаменяемыйДиапазон"); //$NON-NLS-1$
                if (comRange != null)
                {
                    deleteFromLf = (int) ComBridge.toLong(
                        ComBridge.getProperty(comRange, "Начало")) - 1; //$NON-NLS-1$
                    deleteToLf = (int) ComBridge.toLong(
                        ComBridge.getProperty(comRange, "Конец")) - 1; //$NON-NLS-1$
                }
            }
            catch (Exception e)
            {
                IrCompletionDebug.problem("адаптер range: " + e.getMessage()); //$NON-NLS-1$
            }
        }

        return new CompletionAdapterResult(newTemplate, formatText, isGenerator, deleteFromLf, deleteToLf);
    }

    /**
         * Проактивная отмена: удаление cancel-файла (ИР прерывает {@code ОписаниеХТМЛВыражения}).
         * COM {@code УстановитьФайлОтменыВычислений(null)} вызывается в {@code finally} на executor.
         */
        public static void cancelActiveEvaluation(IRSession session)
        {
            if (session == null)
                return;
            AtomicReference<Path> ref = IrBslExpressionHtmlSupport.activeCancelFiles.get(session);
            if (ref == null)
                return;
            Path path = ref.get();
            if (path == null)
                return;
            try
            {
                if (Files.deleteIfExists(path))
                    BslSideHintDebug.step("ir cancel", "deleted " + path.getFileName()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (Exception e)
            {
                BslSideHintDebug.problem("ir cancel: " + e.getMessage()); //$NON-NLS-1$
            }
        }

    public static Path setEvaluationCancellationFile(IRSession session, Object codeEditor) throws IOException
        {
            Path cancelFile;
            cancelFile = Files.createTempFile(IrBslExpressionHtmlSupport.CANCEL_FILE_PREFIX, IrBslExpressionHtmlSupport.CANCEL_FILE_SUFFIX);
            IrBslExpressionHtmlSupport.activeCancelFiles.computeIfAbsent(session, s -> new AtomicReference<>()).set(cancelFile);
            ComBridge.invoke(codeEditor, "УстановитьФайлОтменыВычислений", //$NON-NLS-1$
                cancelFile.toAbsolutePath().toString());
            return cancelFile;
        }

    static void clearEvaluationCancellationFile(
            IRSession session, Object codeEditor, Path cancelFile)
        {
            if (session == null || cancelFile == null)
                return;
            AtomicReference<Path> ref = IrBslExpressionHtmlSupport.activeCancelFiles.computeIfAbsent(session, s -> new AtomicReference<>());
            if (ref.compareAndSet(cancelFile, null))
            {
                try
                {
                    ComBridge.invoke(codeEditor, "УстановитьФайлОтменыВычислений", (Object) null); //$NON-NLS-1$
                }
                catch (Exception e)
                {
                    BslSideHintDebug.problem("ir cancel clear: " + e.getMessage()); //$NON-NLS-1$
                }
            }
            try
            {
                Files.deleteIfExists(cancelFile);
            }
            catch (Exception e)
            {
                BslSideHintDebug.problem("ir cancel delete: " + e.getMessage()); //$NON-NLS-1$
            }
        }

    /**
     * Псевдомодальный режим открытия окон ИР из EDT (аналог TurboConf
     * {@code Начать/ЗавершитьВызовВнешнегоОкнаАсинх} в режиме «Диалог»).
     * <p>
     * <b>Правила активации в псевдомодальном режиме</b> (монитор, edge-trigger + focus-guard):
     * <ol>
     * <li>Активировали окно ИР — сначала EDT, затем снова ИР ({@link WinWindowActivator#activateEdtThenWindowOnUiThread}),
     *     чтобы за модальным окном на заднем плане было видно EDT.</li>
     * <li>Активировали окно EDT — только ИР ({@link WinWindowActivator#activateWindowOnUiThread}),
     *     без повторной активации EDT. Focus-guard: пока {@code GetForegroundWindow} — EDT, redirect с throttle ~400 ms.</li>
     * </ol>
     */
    private static final class IrModalWindowSession
    {
        private static final long MONITOR_INTERVAL_MS = 50;
        /** Минимальный интервал redirect EDT→IR при смене дочернего HWND (набор текста). */
        private static final long EDT_TO_IR_REDIRECT_MIN_MS = 200;
        /** Минимальный интервал focus-guard, когда HWND EDT не меняется, а клавиатура в EDT. */
        private static final long FOCUS_GUARD_MIN_MS = 400;

        /**
         * Жёсткая блокировка EDT через {@code EnableWindow} и overlay-shell.
         * <p>
         * Отключено намеренно: реализация слишком агрессивна — на практике перестают
         * проходить клики не только в EDT, но и в окнах других приложений. Не включать,
         * пока не будет переработана (только HWND workbench, без глобальных побочных эффектов).
         * <p>
         * Псевдомодальность — «мягкий» режим: см. правила активации в javadoc класса.
         */
        private static final boolean BLOCK_EDT_INPUT = false;

        private final IRSession session;
        private final Pattern dialogTitlePattern;
        private final long waitForDialogMs;

        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean ended = new AtomicBoolean(false);
        private final AtomicBoolean dialogSeen = new AtomicBoolean(false);
        private final AtomicBoolean closeWaitLogged = new AtomicBoolean(false);

        private HWND mainHwnd;
        private HWND lastForegroundHwnd;
        private long lastEdtToIrRedirectMs;
        private WinWindowActivator.WindowState mainWindowState;
        private Thread monitorThread;

        private final List<Shell> overlayShells = new ArrayList<>();
        private final List<HWND> blockedWorkbenchHwnds = new ArrayList<>();

        private IrModalWindowSession(IRSession session, Pattern dialogTitlePattern, long waitForDialogMs)
        {
            this.session = session;
            this.dialogTitlePattern = dialogTitlePattern;
            this.waitForDialogMs = waitForDialogMs;
        }

        static IrModalWindowSession begin(IRSession session, Pattern dialogTitlePattern, long waitForDialogMs)
        {
            IrModalWindowSession modal = new IrModalWindowSession(session, dialogTitlePattern, waitForDialogMs);
            modal.doBegin();
            return modal;
        }

        private void doBegin()
        {
            if (!WinWindowActivator.isWindows() || session.pid <= 0)
            {
                IrModalWindowDebug.problem("модальный режим недоступен (не Windows или pid неизвестен)"); //$NON-NLS-1$
                return;
            }

            IrModalWindowDebug.step("begin", "pid=" + session.pid); //$NON-NLS-1$ //$NON-NLS-2$

            try
            {
                ComBridge.setProperty(session.root, "Visible", true); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                IrModalWindowDebug.problem("Visible=true: " + e.getMessage()); //$NON-NLS-1$
            }

            mainHwnd = WinWindowActivator.resolveIrMainWindow(session);
            if (mainHwnd == null)
                IrModalWindowDebug.problem("главное окно ИР не найдено (MainWindowHandle)"); //$NON-NLS-1$
            else
            {
                session.cacheIrMainHwnd(mainHwnd);
                WinWindowActivator.prepareIrMainForModal(mainHwnd);
                WinWindowActivator.hideWindow(mainHwnd);
                IrModalWindowDebug.step("mainHidden", "hwnd=" + mainHwnd.getPointer()); //$NON-NLS-1$ //$NON-NLS-2$
            }

            startMonitor();
        }

        void end()
        {
            if (!ended.compareAndSet(false, true))
                return;

            stopMonitorAndUnblock();
            IrModalWindowDebug.step("end", "pid=" + session.pid); //$NON-NLS-1$ //$NON-NLS-2$

            if (mainWindowState != null)
            {
                WinWindowActivator.restoreWindowHidden(mainWindowState);
                mainWindowState = null;
            }
            else
            {
                IrModalWindowDebug.problem("end: нет сохранённого состояния главного окна"); //$NON-NLS-1$
            }

            try
            {
                ComBridge.setProperty(session.root, "Visible", false); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                IrModalWindowDebug.problem("Visible=false: " + e.getMessage()); //$NON-NLS-1$
            }
        }

        /** Снять псевдомодальность и оставить окно ИР видимым (неуспех конструктора метода и т.п.). */
        void endLeaveIrVisible()
        {
            if (!ended.compareAndSet(false, true))
                return;

            stopMonitorAndUnblock();
            IrModalWindowDebug.step("endLeaveIrVisible", "pid=" + session.pid); //$NON-NLS-1$ //$NON-NLS-2$

            if (mainWindowState != null)
            {
                WinWindowActivator.restoreWindow(mainWindowState);
                mainWindowState = null;
            }

            session.showWindow();
        }

        private void stopMonitorAndUnblock()
        {
            active.set(false);

            if (monitorThread != null)
            {
                try
                {
                    monitorThread.interrupt();
                    monitorThread.join(500);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                monitorThread = null;
            }

            if (BLOCK_EDT_INPUT)
                unblockEdtOnUiThread();
        }

        private void startMonitor()
        {
            if (!WinWindowActivator.isWindows() || session.pid <= 0)
                return;

            monitorThread = new Thread(this::monitorLoop, "IrModalMonitor-" + session.pid); //$NON-NLS-1$
            monitorThread.setDaemon(true);
            monitorThread.start();
        }

        private void monitorLoop()
        {
            long waitDeadline = waitForDialogMs > 0 ? System.currentTimeMillis() + waitForDialogMs : 0;

            while (active.get() && !Thread.currentThread().isInterrupted())
            {
                try
                {
                    int pid = (int) session.pid;
                    List<WinWindowActivator.ProcessWindowInfo> windows =
                        WinWindowActivator.enumProcessWindows(pid);

                    HWND currentMain = mainHwnd;
                    if (currentMain == null)
                    {
                        currentMain = WinWindowActivator.resolveIrMainWindow(session);
                        mainHwnd = currentMain;
                    }

                    List<HWND> dialogs = new ArrayList<>();
                    for (WinWindowActivator.ProcessWindowInfo info : windows)
                    {
                        if (!info.visible)
                            continue;
                        if (currentMain != null && WinWindowActivator.hwndEquals(info.hwnd, currentMain))
                            continue;
                        if (info.title.isEmpty())
                            continue;
                        if (dialogTitlePattern != null && !dialogTitlePattern.matcher(info.title).find())
                            continue;
                        dialogs.add(info.hwnd);
                    }

                    if (!dialogs.isEmpty())
                    {
                        boolean firstSeen = !dialogSeen.get();
                        dialogSeen.set(true);
                        if (waitDeadline > 0)
                            waitDeadline = 0;

                        HWND topDialog = dialogs.get(dialogs.size() - 1);
                        HWND fg = com.sun.jna.platform.win32.User32.INSTANCE.GetForegroundWindow();

                        if (firstSeen)
                        {
                            IrModalWindowDebug.step("dialogSeen", "диалог появился"); //$NON-NLS-1$ //$NON-NLS-2$
                            if (BLOCK_EDT_INPUT)
                                blockEdtOnUiThread();
                            if (currentMain == null)
                            {
                                currentMain = WinWindowActivator.resolveIrMainWindow(session);
                                mainHwnd = currentMain;
                                if (currentMain != null)
                                    IrModalWindowDebug.step("mainResolved", "hwnd=" + currentMain.getPointer()); //$NON-NLS-1$ //$NON-NLS-2$
                            }
                            if (currentMain != null)
                            {
                                session.cacheIrMainHwnd(currentMain);
                                WinWindowActivator.prepareIrMainForModal(currentMain);
                                if (WinWindowActivator.isWindowVisible(currentMain))
                                    WinWindowActivator.hideWindow(currentMain);
                                mainWindowState = WinWindowActivator.shrinkToTitleBar(currentMain);
                                if (mainWindowState == null)
                                    IrModalWindowDebug.problem("не удалось сжать главное окно ИР"); //$NON-NLS-1$
                                else
                                    IrModalWindowDebug.step("mainShrunk", "hwnd=" + currentMain.getPointer()); //$NON-NLS-1$ //$NON-NLS-2$
                            }
                            applyOpenRedirect(fg, topDialog, dialogs);
                            lastForegroundHwnd =
                                com.sun.jna.platform.win32.User32.INSTANCE.GetForegroundWindow();
                        }
                        else
                        {
                            applyFocusRedirect(fg, topDialog, dialogs);
                            lastForegroundHwnd = fg;
                        }
                    }
                    else if (dialogSeen.get() && closeWaitLogged.compareAndSet(false, true))
                    {
                        IrModalWindowDebug.step("monitor", "диалоги закрыты — ждём возврата COM"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    else if (waitDeadline > 0 && System.currentTimeMillis() > waitDeadline)
                    {
                        IrModalWindowDebug.step("monitor", "таймаут ожидания диалога"); //$NON-NLS-1$ //$NON-NLS-2$
                        waitDeadline = 0;
                    }

                    Thread.sleep(MONITOR_INTERVAL_MS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
                catch (Exception e)
                {
                    IrModalWindowDebug.problem("monitor: " + e.getMessage()); //$NON-NLS-1$
                }
            }
        }

        /**
         * Первое появление диалога: без лишнего цикла EDT→IR, если 1С уже активировала окно.
         */
        private void applyOpenRedirect(HWND fg, HWND topDialog, List<HWND> dialogs)
        {
            if (topDialog == null)
                return;

            if (WinWindowActivator.isIrDialogForeground(fg, dialogs))
            {
                IrModalWindowDebug.step("redirectFocus", "open → skip (dialogFg)"); //$NON-NLS-1$ //$NON-NLS-2$
                WinWindowActivator.showEdtBehindIrDialog(topDialog);
            }
            else if (WinWindowActivator.isEdtForeground(fg))
            {
                IrModalWindowDebug.step("redirectFocus", "open → IR (edtWasFg)"); //$NON-NLS-1$ //$NON-NLS-2$
                WinWindowActivator.showEdtBehindIrDialog(topDialog);
                WinWindowActivator.activateWindowOnUiThread(topDialog);
            }
            else
            {
                IrModalWindowDebug.step("redirectFocus", "open → EDT → IR"); //$NON-NLS-1$ //$NON-NLS-2$
                WinWindowActivator.activateEdtThenWindowOnUiThread(topDialog);
            }
        }

        /**
         * Перенаправление клавиатурного фокуса (см. javadoc класса).
         * IR→EDT→IR — по переходу на ИР; EDT→IR — edge-trigger + focus-guard (только IR).
         */
        private void applyFocusRedirect(HWND fg, HWND topDialog, List<HWND> dialogs)
        {
            if (fg == null || topDialog == null)
                return;

            if (WinWindowActivator.isEdtForeground(fg))
            {
                if (WinWindowActivator.isIrDialogForeground(fg, dialogs))
                    return;

                boolean lastEdt = WinWindowActivator.isEdtForeground(lastForegroundHwnd);
                boolean enteredEdt = !lastEdt;
                boolean edtTargetChanged = !hwndEquals(fg, lastForegroundHwnd);

                if (enteredEdt || edtTargetChanged)
                {
                    String reason = enteredEdt ? "entered" : "edtHwnd"; //$NON-NLS-1$ //$NON-NLS-2$
                    tryReturnFocusToIr(topDialog, "EDT → IR (" + reason + ")", EDT_TO_IR_REDIRECT_MIN_MS); //$NON-NLS-1$ //$NON-NLS-2$
                }
                else
                    tryReturnFocusToIr(topDialog, "focusGuard → IR", FOCUS_GUARD_MIN_MS); //$NON-NLS-1$
                return;
            }

            boolean fgIr = WinWindowActivator.isIrDialogForeground(fg, dialogs);
            boolean lastIr = WinWindowActivator.isIrDialogForeground(lastForegroundHwnd, dialogs);

            // Прямой переход на ИР (не сразу после EDT→IR — там только activateWindowOnUiThread)
            if (fgIr && !lastIr && !hwndEquals(fg, lastForegroundHwnd))
            {
                long now = System.currentTimeMillis();
                if (now - lastEdtToIrRedirectMs < EDT_TO_IR_REDIRECT_MIN_MS)
                    return;

                IrModalWindowDebug.step("redirectFocus", "IR → EDT → IR"); //$NON-NLS-1$ //$NON-NLS-2$
                WinWindowActivator.activateEdtThenWindowOnUiThread(topDialog);
            }
        }

        private void tryReturnFocusToIr(HWND topDialog, String logMessage, long minIntervalMs)
        {
            long now = System.currentTimeMillis();
            if (now - lastEdtToIrRedirectMs < minIntervalMs)
                return;

            IrModalWindowDebug.step("redirectFocus", logMessage); //$NON-NLS-1$
            WinWindowActivator.activateWindowOnUiThread(topDialog);
            lastEdtToIrRedirectMs = now;
        }

        private static boolean hwndEquals(HWND a, HWND b)
        {
            return WinWindowActivator.hwndEquals(a, b);
        }

        private void blockEdtOnUiThread()
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;

            Runnable block = () ->
            {
                overlayShells.clear();
                blockedWorkbenchHwnds.clear();

                Set<HWND> workbenchHwnds = WinWindowActivator.collectWorkbenchShells();
                for (HWND hwnd : workbenchHwnds)
                {
                    WinWindowActivator.setWindowEnabled(hwnd, false);
                    blockedWorkbenchHwnds.add(hwnd);
                }

                try
                {
                    for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                    {
                        Shell shell = window.getShell();
                        if (shell == null || shell.isDisposed())
                            continue;

                        Rectangle bounds = shell.getBounds();
                        Shell overlay = new Shell(shell, SWT.NO_TRIM | SWT.ON_TOP);
                        overlay.setBounds(bounds);
                        overlay.setAlpha(1);
                        overlay.setVisible(true);
                        overlay.setEnabled(true);
                        overlayShells.add(overlay);
                    }
                }
                catch (Exception e)
                {
                    IrModalWindowDebug.problem("overlay: " + e.getMessage()); //$NON-NLS-1$
                }
            };

            if (Display.getCurrent() == display)
                block.run();
            else
                display.syncExec(block);
        }

        private void unblockEdtOnUiThread()
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;

            Runnable unblock = () ->
            {
                for (Shell overlay : overlayShells)
                {
                    if (overlay != null && !overlay.isDisposed())
                        overlay.dispose();
                }
                overlayShells.clear();

                for (HWND hwnd : blockedWorkbenchHwnds)
                    WinWindowActivator.setWindowEnabled(hwnd, true);
                blockedWorkbenchHwnds.clear();
            };

            if (Display.getCurrent() == display)
                unblock.run();
            else
                display.syncExec(unblock);
        }
    }

    }
