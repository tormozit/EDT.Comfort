package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import org.eclipse.compare.rangedifferencer.IRangeComparator;
import org.eclipse.compare.rangedifferencer.RangeDifference;
import org.eclipse.compare.rangedifferencer.RangeDifferencer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IPositionUpdater;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.BslModuleReference;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.bm.core.IBmObject;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

/**
 * Позиция исполнения в изменённом BSL-модуле (#909).
 *
 * <p>Сохраняет штатную аннотацию и её жизненный цикл InstructionPointerManager.
 * Поле DynamicInstructionPointerAnnotation.fStackFrame проверено в исходниках
 * org.eclipse.debug.ui 3.18.200.v20231106-1112. Не меняет номера строк кадров,
 * точки останова, команды шага и вычисление выражений.
 *
 * <p>Выгрузка через существующий агент, как в ProfilingViewHook. Текст принимается
 * только при совпадении поколения до и после выгрузки с поколением предмета.
 * Частичная выгрузка по listFile содержит только владельца нужного модуля.
 * Снимок кэшируется на поколение, проект и владельца; операции агента и diff — в фоне.
 */
public final class BslDebugSourceHook implements IStartup
{
    private static final String CURRENT = "org.eclipse.debug.ui.currentIP";
    private static final String SECONDARY = "org.eclipse.debug.ui.secondaryIP";
    private static final String UNCERTAIN = "tormozit.comfort.debug.uncertainIP";
    private static final String KEY = "tormozit.comfort.debug.source";
    private static final String STALE_REASON =
        "Метаданные сеанса устарели относительно базы; текст исполняемой версии недоступен";
    private static final Map<IRuntimeDebugClientTarget, Map<SourceKey, ExportedSource>> SOURCES =
        new WeakHashMap<>();
    // Один файл может быть открыт в нескольких редакторах с общей моделью аннотаций.
    private static final Map<Annotation, Appearance> APPEARANCES = new WeakHashMap<>();
    private static final Set<Session> SESSIONS = Collections.newSetFromMap(new WeakHashMap<>());
    private static Field stackFrameField;

    @Override
    public void earlyStartup()
    {
        try
        {
            Class<?> type = Platform.getBundle("org.eclipse.debug.ui").loadClass(
                "org.eclipse.debug.internal.ui.DynamicInstructionPointerAnnotation");
            stackFrameField = type.getDeclaredField("fStackFrame");
            stackFrameField.setAccessible(true);
        }
        catch (Exception e)
        {
            Global.tempLogException("debug-source", "Подключение указателя исполнения", e);
            return;
        }
        Display.getDefault().asyncExec(() ->
        {
            DebugPlugin.getDefault().addDebugEventListener(events ->
            {
                for (DebugEvent event : events)
                    if (event.getKind() == DebugEvent.CHANGE && event.getDetail() == DebugEvent.CONTENT
                        && event.getSource() instanceof IRuntimeDebugClientTarget target)
                    {
                        // DebugViewHook посылает CHANGE/CONTENT при обновлении базы
                        // и по завершении общей проверки поколений метаданных сеанса.
                        Display display = Display.getDefault();
                        if (!display.isDisposed())
                            display.asyncExec(() ->
                            {
                                for (Session session : SESSIONS)
                                    session.invalidate(target);
                            });
                    }
            });
            // Paint охватывает и лениво создаваемые страницы редакторов форм.
            // После подключения фильтр больше не обходит workbench для этого виджета.
            Display.getDefault().addFilter(SWT.Paint, event ->
            {
                if (!(event.widget instanceof StyledText text))
                    return;
                if (text.getData(KEY) instanceof Session previous)
                {
                    if (!previous.closed && previous.viewer.getDocument() == previous.document)
                        return;
                    previous.close();
                    text.setData(KEY, null);
                }
                var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                var page = window != null ? window.getActivePage() : null;
                if (page != null)
                    attach(GetRef.getActiveBslEditor(page.getActiveEditor()), text);
            });
            for (var window : PlatformUI.getWorkbench().getWorkbenchWindows())
                for (var page : window.getPages())
                    for (var reference : page.getEditorReferences())
                    {
                        IEditorPart part = reference.getEditor(false);
                        if (part != null)
                            attach(GetRef.getActiveBslEditor(part), null);
                    }
        });
    }

    private static void attach(BslXtextEditor editor, StyledText painted)
    {
        if (editor == null)
            return;
        ISourceViewer viewer = editor.getInternalSourceViewer();
        StyledText text = viewer != null ? viewer.getTextWidget() : null;
        if (text == null || text.isDisposed() || (painted != null && painted != text)
            || text.getData(KEY) != null || viewer.getDocument() == null)
            return;
        var provider = editor.getDocumentProvider();
        IAnnotationModel model = provider != null
            ? provider.getAnnotationModel(editor.getEditorInput()) : null;
        IFile file = editor.getEditorInput().getAdapter(IFile.class);
        if (model == null || file == null)
            return;
        Session session = new Session(editor, viewer, model, file);
        text.setData(KEY, session);
        session.install();
    }

    private static final class Session implements IDocumentListener
    {
        final BslXtextEditor editor;
        final ISourceViewer viewer;
        final IAnnotationModel model;
        final IDocument document;
        final IFile file;
        final Display display;
        final Map<Annotation, Pointer> pointers = new IdentityHashMap<>();
        final org.eclipse.jface.text.source.IAnnotationModelListener modelListener = ignored -> modelChanged();
        final IPositionUpdater positionUpdater = this::protectPositions;
        long revision;
        boolean queued;
        boolean scanning;
        boolean changingDocument;
        CachedMapping cachedMapping;
        volatile boolean modifying;
        volatile boolean closed;

        Session(BslXtextEditor editor, ISourceViewer viewer, IAnnotationModel model, IFile file)
        {
            this.editor = editor;
            this.viewer = viewer;
            this.model = model;
            this.document = viewer.getDocument();
            this.file = file;
            this.display = viewer.getTextWidget().getDisplay();
        }

        void install()
        {
            SESSIONS.add(this);
            model.addAnnotationModelListener(modelListener);
            document.addDocumentListener(this);
            document.insertPositionUpdater(positionUpdater, 0);
            viewer.getTextWidget().addDisposeListener(event -> close());
            queue();
        }

        void close()
        {
            closed = true;
            SESSIONS.remove(this);
            revision++;
            model.removeAnnotationModelListener(modelListener);
            document.removeDocumentListener(this);
            document.removePositionUpdater(positionUpdater);
            pointers.clear();
        }

        void invalidate(IRuntimeDebugClientTarget target)
        {
            boolean changed = false;
            for (Pointer pointer : pointers.values())
                if (!pointer.stale && pointer.frame.getDebugTarget() == target)
                {
                    pointer.requestedText = null;
                    pointer.pending = false;
                    pointer.revision++;
                    changed = true;
                }
            if (changed)
                queue();
        }

        void protectPositions(DocumentEvent event)
        {
            // DefaultPositionUpdater удаляет целиком поглощённую позицию, а AnnotationModel
            // затем удаляет её аннотацию. Оставляем нулевой якорь для красного указателя.
            // Удаление аннотации самим отладчиком этот updater не перехватывает.
            for (var iterator = model.getAnnotationIterator(); iterator.hasNext();)
            {
                Annotation annotation = iterator.next();
                String type = annotation.getType();
                if ((CURRENT.equals(type) || SECONDARY.equals(type) || UNCERTAIN.equals(type))
                    && stackFrameField.getDeclaringClass().isInstance(annotation))
                    protectPosition(model.getPosition(annotation), event);
            }
        }

        void modelChanged()
        {
            if (modifying || closed)
                return;
            // BslSourceDisplay сначала позиционирует редактор, затем синхронно добавляет
            // аннотацию. Готовое соответствие применяем в этом же UI-вызове, до Paint.
            if (Display.getCurrent() == display && !changingDocument && !scanning && cachedMapping != null)
                scan();
            else
                queue();
        }

        void queue()
        {
            if (modifying || closed || display.isDisposed())
                return;
            // Модель может уведомить из фонового потока. Состояние Session — только UI.
            display.asyncExec(() ->
            {
                if (closed || queued)
                    return;
                queued = true;
                display.timerExec(75, () ->
                {
                    queued = false;
                    if (!closed)
                        scan();
                });
            });
        }

        void scan()
        {
            if (scanning)
                return;
            scanning = true;
            try
            {
                scanNow();
            }
            finally
            {
                scanning = false;
            }
        }

        void scanNow()
        {
            long request = ++revision;
            String current = null;
            pointers.keySet().removeIf(annotation -> model.getPosition(annotation) == null);
            List<PointerRequest> requests = new ArrayList<>();
            List<PointerRequest> ready = new ArrayList<>();
            List<Pointer> stalePointers = new ArrayList<>();
            for (var iterator = model.getAnnotationIterator(); iterator.hasNext();)
            {
                Annotation annotation = iterator.next();
                String type = annotation.getType();
                if (!CURRENT.equals(type) && !SECONDARY.equals(type) && !UNCERTAIN.equals(type))
                    continue;
                try
                {
                    if (!stackFrameField.getDeclaringClass().isInstance(annotation)
                        || !(stackFrameField.get(annotation) instanceof IBslStackFrame frame)
                        || frame.isTerminated() || !frame.getThread().isSuspended())
                        continue;
                    Pointer pointer = pointers.computeIfAbsent(annotation,
                        ignored -> new Pointer(annotation, frame, type, annotation.getText()));
                    if (DebugViewHook.isSessionMetadataStale(frame))
                    {
                        if (!pointer.stale)
                            stalePointers.add(pointer);
                        continue;
                    }
                    int line = frame.getLineNumber() - 1;
                    if (line < 0)
                        continue;
                    Position old = model.getPosition(annotation);
                    boolean cached = cachedMapping != null && cachedMapping.isValidFor(frame, document);
                    if (current == null)
                        current = cached ? cachedMapping.current : document.get();
                    if (line == pointer.requestedLine && current.equals(pointer.requestedText)
                        && (pointer.pending || (old != null && old.offset == pointer.offset
                            && type.equals(pointer.resultType))))
                        continue;
                    boolean reveal = !pointer.applied && old != null
                        && viewer.getSelectedRange().x == old.offset;
                    pointer.requestedLine = line;
                    pointer.requestedText = current;
                    pointer.pending = true;
                    PointerRequest item = new PointerRequest(pointer, line, reveal, viewer.getSelectedRange().x,
                        ++pointer.revision);
                    if (cached)
                        ready.add(item);
                    else
                        requests.add(item);
                }
                catch (Exception e)
                {
                    Global.tempLogException("debug-source", "Чтение кадра", e);
                }
            }
            for (Pointer pointer : stalePointers)
                markStale(pointer);
            for (PointerRequest item : ready)
                apply(request, current, item, cachedMapping.matching.at(item.line), cachedMapping, true);
            if (requests.isEmpty())
                return;
            String snapshot = current;
            long snapshotStamp = documentStamp(document);
            Global.tempLog("debug-source", "request file=" + file.getFullPath()
                + " revision=" + request + " pointers=" + requests.size());
            Job job = Job.create("Комфорт: сопоставление исполняемой строки", monitor ->
            {
                for (PointerRequest item : requests)
                {
                    if (closed || item.revision != item.pointer.revision || monitor.isCanceled())
                        continue;
                    Mapping mapping;
                    CachedMapping computed = null;
                    try
                    {
                        String original = source(item.pointer.frame, file);
                        if (closed || item.revision != item.pointer.revision || monitor.isCanceled())
                            continue;
                        LineMapping matching = new LineMapping(original, snapshot);
                        computed = new CachedMapping(item.pointer.frame.getDebugTarget(),
                            item.pointer.frame.getReference(),
                            item.pointer.frame.getThread().getRuntimeDebugTarget().getConfigVersion(),
                            snapshot, snapshotStamp, matching);
                        mapping = matching.at(item.line);
                        Global.tempLog("debug-source", "mapping built file=" + file.getFullPath()
                            + " sourceLines=" + matching.sourceCount + " editorLines=" + matching.targetCount);
                    }
                    catch (StaleSessionMetadataException e)
                    {
                        DebugViewHook.markSessionMetadataStale(item.pointer.frame);
                        Global.tempLog("debug-source", "stale session file=" + file.getFullPath());
                        if (!display.isDisposed())
                            display.asyncExec(() ->
                            {
                                if (!closed && model.getPosition(item.pointer.annotation) != null)
                                    markStale(item.pointer);
                            });
                        continue;
                    }
                    catch (Exception e)
                    {
                        Global.tempLogException("debug-source", "Получение исходного модуля", e);
                        mapping = new Mapping(clamp(item.line, lines(snapshot).length), false,
                            "Не удалось получить текст исполняемого модуля из базы");
                    }
                    Mapping result = mapping;
                    CachedMapping calculated = computed;
                    if (!display.isDisposed())
                        display.asyncExec(() -> apply(request, snapshot, item, result, calculated, false));
                }
                return Status.OK_STATUS;
            });
            job.setSystem(true);
            job.schedule();
        }

        void markStale(Pointer pointer)
        {
            Position position = model.getPosition(pointer.annotation);
            if (pointer.stale || position == null)
                return;
            pointer.stale = true;
            DebugViewHook.clearCorrectedFrameLine(pointer.frame);
            pointer.pending = false;
            pointer.revision++;
            modifying = true;
            try
            {
                model.removeAnnotation(pointer.annotation);
                pointer.annotation.setType(UNCERTAIN);
                pointer.annotation.setText(STALE_REASON);
                model.addAnnotation(pointer.annotation, new Position(position.offset, position.length));
            }
            finally
            {
                modifying = false;
            }
            Global.tempLog("debug-source", "stale pointer, no source lookup or diff file=" + file.getFullPath());
        }

        void apply(long request, String current, PointerRequest item, Mapping mapping,
            CachedMapping computed, boolean synchronous)
        {
            Pointer pointer = item.pointer;
            try
            {
                if (closed || item.revision != pointer.revision || viewer.getDocument() != document
                    || !current.equals(document.get()) || model.getPosition(pointer.annotation) == null
                    || pointer.frame.isTerminated() || !pointer.frame.getThread().isSuspended()
                    || pointer.frame.getLineNumber() - 1 != item.line)
                    return;
                // Общая проверка могла завершиться между получением текста и этим UI-вызовом.
                if (DebugViewHook.isSessionMetadataStale(pointer.frame))
                {
                    markStale(pointer);
                    return;
                }
                if (computed != null)
                    cachedMapping = computed;
                IRegion region = document.getLineInformation(mapping.line);
                String type = mapping.exact ? pointer.type : UNCERTAIN;
                String text = mapping.exact ? pointer.text
                    : "Позиция исполнения приблизительная: " + mapping.reason;
                Position previous = model.getPosition(pointer.annotation);
                if (previous.offset != region.getOffset() || previous.length != region.getLength()
                    || !type.equals(pointer.annotation.getType())
                    || !Objects.equals(text, pointer.annotation.getText()))
                {
                    modifying = true;
                    try
                    {
                        // Объект аннотации сохраняется: InstructionPointerManager владеет именно им.
                        model.removeAnnotation(pointer.annotation);
                        pointer.annotation.setType(type);
                        pointer.annotation.setText(text);
                        model.addAnnotation(pointer.annotation, new Position(region.getOffset(), region.getLength()));
                    }
                    finally
                    {
                        modifying = false;
                    }
                }
                pointer.applied = true;
                pointer.pending = false;
                pointer.offset = region.getOffset();
                pointer.resultType = type;
                DebugViewHook.setCorrectedFrameLine(pointer.frame, item.line + 1, mapping.line + 1,
                    computed != null && computed.matching.changed);
                // После набора текста или ручного перемещения каретки её не переставляем.
                if (item.reveal && viewer.getSelectedRange().x == item.caret
                    && item.caret != region.getOffset()
                    && editor.getSite().getPage().getActiveEditor() != null
                    && GetRef.getActiveBslEditor(editor.getSite().getPage().getActiveEditor()) == editor)
                    editor.selectAndReveal(region.getOffset(), 0);
                Global.tempLog("debug-source", "applied file=" + file.getFullPath()
                    + " sourceLine=" + (item.line + 1) + " editorLine=" + (mapping.line + 1)
                    + " exact=" + mapping.exact + " synchronous=" + synchronous
                    + " annotation=" + System.identityHashCode(pointer.annotation)
                    + " reveal=" + item.reveal + " reason=" + mapping.reason);
            }
            catch (Exception e)
            {
                Global.tempLogException("debug-source", "Установка указателя", e);
            }
        }

        @Override
        public void documentAboutToBeChanged(DocumentEvent event)
        {
            changingDocument = true;
        }

        @Override
        public void documentChanged(DocumentEvent event)
        {
            changingDocument = false;
            queue();
        }
    }

    private static final class Pointer
    {
        final Annotation annotation;
        final IBslStackFrame frame;
        final String type;
        final String text;
        boolean applied;
        boolean pending;
        boolean stale;
        int requestedLine = -1;
        int offset = -1;
        volatile long revision;
        String requestedText;
        String resultType;

        Pointer(Annotation annotation, IBslStackFrame frame, String type, String text)
        {
            this.annotation = annotation;
            this.frame = frame;
            Appearance original = APPEARANCES.computeIfAbsent(annotation, ignored -> new Appearance(type, text));
            this.type = original.type;
            this.text = original.text;
        }
    }

    private record Appearance(String type, String text) {}
    private record PointerRequest(Pointer pointer, int line, boolean reveal, int caret, long revision) {}
    private record Mapping(int line, boolean exact, String reason) {}
    private record SourceKey(String generation, String project, UUID owner) {}

    private static long documentStamp(IDocument document)
    {
        return document instanceof IDocumentExtension4 extension
            ? extension.getModificationStamp() : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
    }

    private record CachedMapping(IRuntimeDebugClientTarget target, BslModuleReference module,
        String generation, String current, long stamp, LineMapping matching)
    {
        boolean isValidFor(IBslStackFrame frame, IDocument document)
        {
            return frame.getDebugTarget() == target && Objects.equals(module, frame.getReference())
                && Objects.equals(generation, frame.getThread().getRuntimeDebugTarget().getConfigVersion())
                && (stamp != IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP
                    ? stamp == documentStamp(document) : current.equals(document.get()));
        }
    }

    private static final class StaleSessionMetadataException extends Exception
    {
        private static final long serialVersionUID = 1L;
    }

    private static void protectPosition(Position position, DocumentEvent event)
    {
        if (position != null && event.getOffset() < position.offset
            && event.getOffset() + event.getLength() > position.offset + position.length)
        {
            position.offset = event.getOffset();
            position.length = 0;
        }
    }

    private static String[] lines(String text)
    {
        if (text.startsWith("\uFEFF"))
            text = text.substring(1);
        return text.split("\\r\\n|\\r|\\n", -1);
    }

    private static int clamp(int line, int count)
    {
        return Math.max(0, Math.min(line, count - 1));
    }

    /** Два направления diff считаются в фоне один раз; at() только читает готовые диапазоны. */
    private static final class LineMapping
    {
        final int sourceCount;
        final int targetCount;
        final boolean changed;
        final RangeDifference[] forward;
        final RangeDifference[] backward;

        LineMapping(String original, String current)
        {
            String[] left = lines(original);
            String[] right = lines(current);
            sourceCount = left.length;
            targetCount = right.length;
            changed = !java.util.Arrays.equals(left, right);
            forward = RangeDifferencer.findRanges(new Lines(left, false), new Lines(right, false));
            backward = RangeDifferencer.findRanges(new Lines(left, true), new Lines(right, true));
        }

        Mapping at(int line)
        {
            if (line < 0 || line >= sourceCount)
                return new Mapping(clamp(line, targetCount), false, "Строка отсутствует в исходном модуле");
            Mapping first = directionalMatch(forward, sourceCount, targetCount, line, false);
            Mapping last = directionalMatch(backward, sourceCount, targetCount, line, true);
            if (first.exact && last.exact && first.line == last.line)
                return first;
            return new Mapping(first.line, false, first.exact
                ? "Несколько соответствий в повторяющемся коде" : "Исполняемая строка изменена или удалена");
        }
    }

    private static Mapping directionalMatch(RangeDifference[] ranges, int sourceCount, int targetCount,
        int line, boolean reversed)
    {
        int sourceLine = reversed ? sourceCount - 1 - line : line;
        for (RangeDifference range : ranges)
        {
            if (sourceLine < range.leftStart() || sourceLine >= range.leftEnd())
                continue;
            int offset = sourceLine - range.leftStart();
            int target = clamp(range.rightStart() + Math.min(offset, Math.max(0, range.rightLength() - 1)),
                targetCount);
            if (reversed)
                target = targetCount - 1 - target;
            return new Mapping(target, range.kind() == RangeDifference.NOCHANGE, "");
        }
        return new Mapping(clamp(line, targetCount), false, "Нет соответствия");
    }

    private static final class Lines implements IRangeComparator
    {
        final String[] values;
        final boolean reversed;

        Lines(String[] values, boolean reversed)
        {
            this.values = values;
            this.reversed = reversed;
        }

        @Override public int getRangeCount() { return values.length; }

        @Override
        public boolean rangesEqual(int own, IRangeComparator other, int theirs)
        {
            Lines that = (Lines)other;
            return values[reversed ? values.length - 1 - own : own]
                .equals(that.values[that.reversed ? that.values.length - 1 - theirs : theirs]);
        }

        @Override public boolean skipRangeComparison(int length, int maxLength, IRangeComparator other)
        {
            return false;
        }
    }

    private static String source(IBslStackFrame frame, IFile file) throws Exception
    {
        // Проверяем весь сеанс перед обращением к кэшу: старый снимок не даёт права
        // показывать зелёный указатель после обновления метаданных базы.
        if (DebugViewHook.isSessionMetadataStale(frame))
            throw new StaleSessionMetadataException();
        IRuntimeDebugClientTarget target = frame.getDebugTarget();
        if (target == null
            || !(target.getApplication().orElse(null) instanceof IInfobaseApplication application)
            || frame.getReference() == null || frame.getReference().getProject() == null)
            throw new IllegalStateException("Не определена информационная база кадра");
        String generation = frame.getThread().getRuntimeDebugTarget().getConfigVersion();
        if (generation == null || generation.isBlank())
            throw new IllegalStateException("Нет поколения метаданных предмета отладки");
        SourceKey key = new SourceKey(generation, frame.getReference().getProject().getName(),
            frame.getReference().getParentUuid());
        synchronized (SOURCES)
        {
            if (DebugViewHook.isSessionMetadataStale(frame))
                throw new StaleSessionMetadataException();
            Map<SourceKey, ExportedSource> cache = SOURCES.computeIfAbsent(target, ignored -> new HashMap<>());
            ExportedSource exported = cache.get(key);
            if (exported == null)
            {
                var module = frame.getModule();
                if (module == null || !(module.getOwner() instanceof IBmObject owner))
                    throw new IllegalStateException("Не определён владелец исполняемого модуля");
                String ownerName = owner.bmGetFqn();
                if (ownerName == null || ownerName.isBlank())
                    throw new IllegalStateException("Не определено полное имя владельца исполняемого модуля");
                List<Object> active = new ArrayList<>();
                for (Object connection : DesignerSessionPoolAccessor.getInstance()
                    .findExistingConnections(application.getInfobase()))
                    if (Boolean.TRUE.equals(invoke(connection, "isAlive")))
                        active.add(connection);
                if (active.size() != 1)
                    throw new IllegalStateException("Требуется единственное подключение агента конфигуратора");
                String extension = "";
                if (Global.getServiceByClass(IV8ProjectManager.class) instanceof IV8ProjectManager manager)
                {
                    var project = manager.getProject(frame.getReference().getProject());
                    if (project == null)
                        throw new IllegalStateException("Не определён проект исполняемого модуля");
                    if (project instanceof IExtensionProject extensionProject)
                        extension = extensionProject.getConfiguration().getName();
                }
                else
                    throw new IllegalStateException("Недоступен менеджер проектов EDT");
                Path directory = Path.of("C:\\VC\\EDT.Comfort", ".tmp", "debug-source",
                    UUID.randomUUID().toString());
                Files.createDirectories(directory);
                export(active.get(0), generation, extension, ownerName, directory);
                exported = new ExportedSource(directory, key.owner);
                cache.put(key, exported);
            }
            return exported.module(frame.getReference().getParentUuid(), file.getName());
        }
    }

    /** Известный протокол DesignerAgentConnection.use из DebugViewHook/ProfilingViewHook. */
    private static void export(Object connection, String expected, String extension, String ownerName, Path directory)
        throws Exception
    {
        if (ownerName == null || ownerName.isBlank() || ownerName.indexOf('\n') >= 0 || ownerName.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Требуется единственный объект для частичной выгрузки");
        // Тот же формат, что у DesignerSessionThickClientLauncher.exportIncrementalXmlFromInfobase:
        // UTF-8, полное имя объекта, отдельная строка; обязательный IDumpFilesStep.listFile.
        Path selection = directory.resolve("objects.lst");
        Files.write(selection, List.of(ownerName), StandardCharsets.UTF_8);
        Method use = null;
        for (Method method : connection.getClass().getMethods())
            if (method.getName().equals("use") && method.getParameterCount() == 1)
            {
                use = method;
                break;
            }
        if (use == null)
            throw new NoSuchMethodException("DesignerAgentConnection.use");
        Class<?> actionType = use.getParameterTypes()[0];
        Object action = Proxy.newProxyInstance(actionType.getClassLoader(), new Class<?>[] { actionType },
            (proxy, method, arguments) ->
            {
                if (method.getDeclaringClass() == Object.class)
                    return switch (method.getName())
                    {
                        case "toString" -> "Debug source export";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> null;
                    };
                Object designer = invoke(connection, "getSession");
                long started = System.nanoTime();
                requireGeneration(designer, expected);
                Object step = invoke(invoke(designer, "configure"), "exportXmlFromInfobase",
                    new Class<?>[] { Path.class }, directory);
                step = invoke(step, "listFile", new Class<?>[] { Path.class }, selection);
                if (!extension.isEmpty())
                    step = invoke(step, "extension", new Class<?>[] { String.class }, extension);
                Global.tempLog("debug-source", "export partial start generation=" + expected
                    + " extension=" + extension + " object=" + ownerName + " listFile=" + selection);
                invoke(step, "exec", new Class<?>[] { Duration.class }, Duration.ofMinutes(5));
                requireGeneration(designer, expected);
                Global.tempLog("debug-source", "export partial complete object=" + ownerName
                    + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000 + " directory=" + directory);
                return null;
            });
        try
        {
            use.invoke(connection, action);
        }
        catch (InvocationTargetException e)
        {
            if (e.getCause() instanceof Exception cause)
                throw cause;
            throw e;
        }
    }

    private static void requireGeneration(Object designer, String expected) throws Exception
    {
        Object step = invoke(invoke(designer, "configure"), "retrieveGenerationId");
        Object actual = invoke(step, "exec", new Class<?>[] { Duration.class }, Duration.ofSeconds(15));
        Global.tempLog("debug-source", "generation expected=" + expected + " actual=" + actual);
        if (!Objects.equals(expected, actual))
            throw new StaleSessionMetadataException();
    }

    private static Object invoke(Object target, String name) throws Exception
    {
        return invoke(target, name, new Class<?>[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception
    {
        try
        {
            return target.getClass().getMethod(name, types).invoke(target, args);
        }
        catch (InvocationTargetException e)
        {
            if (e.getCause() instanceof Exception cause)
                throw cause;
            throw e;
        }
    }

    private static final class ExportedSource
    {
        final Path directory;
        final UUID owner;
        final Map<Path, String> texts = new HashMap<>();

        ExportedSource(Path directory, UUID owner)
        {
            this.directory = directory;
            this.owner = owner;
        }

        String module(UUID owner, String filename) throws Exception
        {
            if (!Objects.equals(this.owner, owner))
                throw new IllegalStateException("Частичная выгрузка принадлежит другому объекту " + owner);
            List<Path> candidates;
            try (var paths = Files.walk(directory))
            {
                // listFile ограничен одним владельцем. Для внешнего свойства формы
                // платформа возвращает Ext/Form.xml и Ext/Form/Module.bsl без XML владельца.
                candidates = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(filename))
                    .toList();
            }
            if (candidates.size() != 1)
                throw new IllegalStateException("Не найден единственный модуль " + owner + "/" + filename);
            Path path = candidates.get(0);
            String text = texts.get(path);
            if (text == null)
            {
                text = Files.readString(path, StandardCharsets.UTF_8);
                texts.put(path, text);
                Global.tempLog("debug-source", "partial module read owner=" + owner
                    + " file=" + path + " chars=" + text.length());
            }
            return text;
        }
    }
}
