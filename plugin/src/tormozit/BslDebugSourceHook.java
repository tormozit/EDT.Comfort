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
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.eclipse.compare.rangedifferencer.IRangeComparator;
import org.eclipse.compare.rangedifferencer.RangeDifference;
import org.eclipse.compare.rangedifferencer.RangeDifferencer;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.CoreException;
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
import org.eclipse.ui.texteditor.AbstractMarkerAnnotationModel;
import org.eclipse.ui.texteditor.SimpleMarkerAnnotation;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.BslModuleReference;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread;
import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslLineBreakpoint;
import com._1c.g5.v8.bm.core.IBmObject;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

/**
 * Позиция исполнения в изменённом BSL-модуле (#909).
 *
 * <p>Сохраняет штатную аннотацию и её жизненный цикл InstructionPointerManager.
 * Поле DynamicInstructionPointerAnnotation.fStackFrame проверено в исходниках
 * org.eclipse.debug.ui 3.18.200.v20231106-1112. Не меняет номера строк кадров,
 * команды шага и вычисление выражений. Номера точек останова пересчитываются
 * только для команд отладчику; маркеры остаются в координатах редактора (#1040).
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
    // Все созданные каталоги выгрузок (SOURCES слабая — по ней при закрытии не всё найти).
    private static final List<Path> EXPORT_DIRECTORIES = new ArrayList<>();

    /** Удалить каталоги выгрузок исполняемых модулей; вызывается при закрытии EDT. */
    static void deleteExportDirectories()
    {
        List<Path> directories;
        synchronized (EXPORT_DIRECTORIES)
        {
            directories = new ArrayList<>(EXPORT_DIRECTORIES);
            EXPORT_DIRECTORIES.clear();
        }
        for (Path directory : directories)
        {
            try (var paths = Files.walk(directory))
            {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path ->
                {
                    try
                    {
                        Files.deleteIfExists(path);
                    }
                    catch (java.io.IOException ignored)
                    {
                        // Остаток уберёт очистка временной папки ОС.
                    }
                });
            }
            catch (java.io.IOException ignored)
            {
                // Каталог уже удалён или недоступен.
            }
        }
    }

    @Override
    public void earlyStartup()
    {
        BreakpointMapping.install();
        try
        {
            Class<?> type = Platform.getBundle("org.eclipse.debug.ui").loadClass(
                "org.eclipse.debug.internal.ui.DynamicInstructionPointerAnnotation");
            stackFrameField = type.getDeclaredField("fStackFrame");
            stackFrameField.setAccessible(true);
        }
        catch (Exception e)
        {
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

    /** Только готовое состояние сопоставления; отрисовка списка ничего не запускает и не ждёт. */
    static boolean isBreakpointSourceUncertain(IBslLineBreakpoint breakpoint)
    {
        return BreakpointMapping.isUncertain(breakpoint);
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
            BreakpointMapping.open(file, document, model);
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
            BreakpointMapping.close(file, document, model);
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
                    }
                    catch (StaleSessionMetadataException e)
                    {
                        DebugViewHook.markSessionMetadataStale(item.pointer.frame);
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
            }
            catch (Exception e)
            {
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
            BreakpointMapping.refresh(file);
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
        return source(frame.getDebugTarget(), frame.getReference(),
            frame.getThread().getRuntimeDebugTarget().getConfigVersion(), file,
            frame::getModule, () -> DebugViewHook.isSessionMetadataStale(frame));
    }

    private static String source(IRuntimeDebugClientTarget target, BslModuleReference reference,
        String generation, IFile file, Supplier<com._1c.g5.v8.dt.bsl.model.Module> moduleSupplier,
        BooleanSupplier stale) throws Exception
    {
        // Проверяем весь сеанс перед обращением к кэшу: старый снимок не даёт права
        // показывать зелёный указатель после обновления метаданных базы.
        if (stale.getAsBoolean())
            throw new StaleSessionMetadataException();
        if (target == null
            || !(target.getApplication().orElse(null) instanceof IInfobaseApplication application)
            || reference == null || reference.getProject() == null)
            throw new IllegalStateException("Не определена информационная база кадра");
        if (generation == null || generation.isBlank())
            throw new IllegalStateException("Нет поколения метаданных предмета отладки");
        SourceKey key = new SourceKey(generation, reference.getProject().getName(), reference.getParentUuid());
        synchronized (SOURCES)
        {
            if (stale.getAsBoolean())
                throw new StaleSessionMetadataException();
            Map<SourceKey, ExportedSource> cache = SOURCES.computeIfAbsent(target, ignored -> new HashMap<>());
            ExportedSource exported = cache.get(key);
            if (exported == null)
            {
                var module = moduleSupplier.get();
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
                    var project = manager.getProject(reference.getProject());
                    if (project == null)
                        throw new IllegalStateException("Не определён проект исполняемого модуля");
                    if (project instanceof IExtensionProject extensionProject)
                        extension = extensionProject.getConfiguration().getName();
                }
                else
                    throw new IllegalStateException("Недоступен менеджер проектов EDT");
                Path directory = Files.createTempDirectory("comfort-debug-source-");
                synchronized (EXPORT_DIRECTORIES)
                {
                    EXPORT_DIRECTORIES.add(directory);
                }
                export(active.get(0), generation, extension, ownerName, directory);
                exported = new ExportedSource(directory, key.owner);
                cache.put(key, exported);
            }
            return exported.module(reference.getParentUuid(), file.getName());
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
                requireGeneration(designer, expected);
                Object step = invoke(invoke(designer, "configure"), "exportXmlFromInfobase",
                    new Class<?>[] { Path.class }, directory);
                step = invoke(step, "listFile", new Class<?>[] { Path.class }, selection);
                if (!extension.isEmpty())
                    step = invoke(step, "extension", new Class<?>[] { String.class }, extension);
                invoke(step, "exec", new Class<?>[] { Duration.class }, Duration.ofMinutes(5));
                requireGeneration(designer, expected);
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

    /** Координаты точек принадлежат редактору; отладчику передаётся отдельное представление. */
    private static final class BreakpointMapping
    {
        private static final String TOPIC = "breakpoint-lines-1040";
        private static final Map<IRuntimeDebugClientTarget, Map<IBslLineBreakpoint, State>> STATES =
            new WeakHashMap<>();
        // XtextDocumentProvider наследует FileDocumentProvider, а не TextFileDocumentProvider:
        // его несохранённого документа может вообще не быть в FileBuffers.
        private static final Map<IFile, List<EditorDocument>> OPEN = new HashMap<>();
        private static final Map<IRuntimeDebugClientTarget, Map<IFile, ReverseMapping>> MATCHINGS = new WeakHashMap<>();

        static void open(IFile file, IDocument document, IAnnotationModel model)
        {
            synchronized (OPEN)
            {
                OPEN.computeIfAbsent(file, ignored -> new ArrayList<>()).add(new EditorDocument(document, model));
            }
            refresh(file);
        }

        static void close(IFile file, IDocument document, IAnnotationModel model)
        {
            synchronized (OPEN)
            {
                List<EditorDocument> editors = OPEN.get(file);
                if (editors != null)
                {
                    editors.remove(new EditorDocument(document, model));
                    if (editors.isEmpty())
                        OPEN.remove(file);
                }
            }
            refresh(file);
        }

        static void install()
        {
            System.getProperties().put("tormozit.debug.breakpoints.outgoing",
                (BiFunction<Object, Object, Object>) (target, breakpoint) -> wrap(target, breakpoint, false));
            System.getProperties().put("tormozit.debug.breakpoints.lookup",
                (BiFunction<Object, Object, Object>) (target, breakpoint) -> wrap(target, breakpoint, true));
            Global.tempLog(TOPIC, "install weaving="
                + System.getProperty("tormozit.debug.breakpoints.outgoing.status", "not loaded"));
            DebugPlugin.getDefault().addDebugEventListener(events ->
            {
                for (DebugEvent event : events)
                    if (event.getSource() instanceof org.eclipse.debug.core.model.IDebugElement element
                        && element.getDebugTarget() instanceof IRuntimeDebugClientTarget target)
                    {
                        if (event.getKind() == DebugEvent.TERMINATE || event.getKind() == DebugEvent.CHANGE)
                            BreakpointListHook.sourceMappingChanged();
                        if (event.getKind() != DebugEvent.CREATE && event.getKind() != DebugEvent.CHANGE
                            && event.getKind() != DebugEvent.SUSPEND)
                            continue;
                        Global.tempLog(TOPIC, "event kind=" + event.getKind() + " weaving="
                            + System.getProperty("tormozit.debug.breakpoints.outgoing.status", "not loaded"));
                        refresh(target, null);
                    }
            });
        }

        static void refresh(IFile file)
        {
            refresh(null, file);
        }

        static boolean isUncertain(IBslLineBreakpoint breakpoint)
        {
            synchronized (STATES)
            {
                for (var entry : STATES.entrySet())
                {
                    IRuntimeDebugClientTarget target = entry.getKey();
                    if (target.isTerminated() || target.isDisconnected())
                        continue;
                    List<IRuntimeDebugTargetThread> subjects = connectedSubjects(target);
                    if (subjects.isEmpty())
                        continue;
                    State state = entry.getValue().get(breakpoint);
                    if (state != null)
                        synchronized (state)
                        {
                            if (state.version != null && state.line < 1)
                                for (var subject : subjects)
                                    if (Objects.equals(state.version.generation,
                                        subject.getRuntimeDebugTarget().getConfigVersion()))
                                        return true;
                        }
                }
            }
            return false;
        }

        /** Соединение с сервером отладки само по себе ещё не означает подключение к предмету. */
        private static List<IRuntimeDebugTargetThread> connectedSubjects(IRuntimeDebugClientTarget target)
        {
            List<IRuntimeDebugTargetThread> subjects = new ArrayList<>();
            for (var thread : target.getThreads())
                if (thread.isConnected() && !thread.isTerminated())
                    subjects.add(thread);
            return subjects;
        }

        private static void refresh(IRuntimeDebugClientTarget onlyTarget, IFile onlyFile)
        {
            synchronized (STATES)
            {
                if (STATES.isEmpty())
                    return;
            }
            // Нельзя отправлять команды из уведомления документа/отладчика и под монитором STATES.
            // Задача проверяет версии повторно; неизменившиеся события не порождают команды.
            Job job = Job.create("Комфорт: обновление строк точек останова", monitor ->
            {
                Map<IRuntimeDebugClientTarget, Map<IBslLineBreakpoint, State>> copy = new IdentityHashMap<>();
                synchronized (STATES)
                {
                    STATES.forEach((target, states) ->
                    {
                        if (onlyTarget == null || target == onlyTarget)
                            copy.put(target, new HashMap<>(states));
                    });
                }
                copy.forEach((target, states) -> states.forEach((breakpoint, state) ->
                {
                    try
                    {
                        IMarker marker = breakpoint.getMarker();
                        if (marker == null || !marker.exists() || !(marker.getResource() instanceof IFile file)
                            || (onlyFile != null && !onlyFile.equals(file)))
                            return;
                        Version version = version(target, breakpoint, file);
                        synchronized (state)
                        {
                            if (version.equals(state.version))
                                return;
                        }
                        resend(target, breakpoint);
                    }
                    catch (Exception e)
                    {
                        Global.tempLog(TOPIC, "refresh failed: " + e);
                    }
                }));
                return Status.OK_STATUS;
            });
            job.setSystem(true);
            job.schedule();
        }

        private static Object wrap(Object candidate, Object value, boolean lookup)
        {
            if (!(candidate instanceof IRuntimeDebugClientTarget target)
                || !(value instanceof IBslLineBreakpoint breakpoint))
                return value;
            int line = -1;
            try
            {
                IMarker marker = breakpoint.getMarker();
                if (marker == null || !marker.exists() || !(marker.getResource() instanceof IFile file) || !file.exists())
                    return value;
                State state;
                synchronized (STATES)
                {
                    state = STATES.computeIfAbsent(target, ignored -> new WeakHashMap<>())
                        .computeIfAbsent(breakpoint, ignored -> new State());
                }
                if (lookup)
                {
                    synchronized (state)
                    {
                        // Именно отправленная строка, даже если редактор с тех пор изменился.
                        line = state.version == null ? breakpoint.getLineNumber() : state.sentLine;
                    }
                }
                else
                {
                    Version version = version(target, breakpoint, file);
                    boolean schedule = false;
                    synchronized (state)
                    {
                        if (!version.equals(state.version))
                        {
                            state.version = version;
                            state.line = -1;
                            state.reason = "Соответствие строки ещё рассчитывается";
                            schedule = true;
                        }
                        line = state.line;
                        state.sentLine = line;
                    }
                    if (schedule)
                    {
                        BreakpointListHook.sourceMappingChanged();
                        prepare(target, breakpoint, file, state, version);
                    }
                    Global.tempLog(TOPIC, "outgoing file=" + file.getFullPath() + " marker="
                        + version.markerLine + " runtime=" + line + " generation=" + version.generation
                        + " reason=" + state.reason);
                }
                if (lookup)
                    Global.tempLog(TOPIC, "lookup file=" + file.getFullPath() + " runtime=" + line);
            }
            catch (Exception e)
            {
                Global.tempLog(TOPIC, "wrap lookup=" + lookup + " failed: " + e);
            }
            int mapped = line;
            return Proxy.newProxyInstance(IBslLineBreakpoint.class.getClassLoader(),
                new Class<?>[] { IBslLineBreakpoint.class }, (proxy, method, arguments) ->
                {
                    if (method.getName().equals("getLineNumber"))
                        return mapped;
                    if (!lookup && method.getName().equals("isEnabled") && mapped < 1)
                        return false;
                    try
                    {
                        return method.invoke(breakpoint, arguments);
                    }
                    catch (InvocationTargetException e)
                    {
                        throw e.getCause();
                    }
                });
        }

        private static Version version(IRuntimeDebugClientTarget target, IBslLineBreakpoint breakpoint, IFile file)
            throws CoreException
        {
            EditorDocument editor = null;
            String reason = "";
            synchronized (OPEN)
            {
                List<EditorDocument> editors = OPEN.get(file);
                if (editors != null)
                    for (EditorDocument next : editors)
                    {
                        if (editor != null && editor.document != next.document)
                            reason = "Для модуля открыто несколько разных документов редактора";
                        editor = next;
                    }
            }
            if (editor == null)
            {
                var buffer = FileBuffers.getTextFileBufferManager()
                    .getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
                if (buffer != null)
                    editor = new EditorDocument(buffer.getDocument(), buffer.getAnnotationModel());
            }
            IDocument document = editor != null ? editor.document : null;
            long stamp = document != null ? documentStamp(document) : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
            String generation = null;
            List<IRuntimeDebugTargetThread> subjects = connectedSubjects(target);
            for (var thread : subjects)
            {
                if (DebugViewHook.isSessionMetadataStale(thread))
                    reason = STALE_REASON;
                String next = thread.getRuntimeDebugTarget().getConfigVersion();
                if (next == null || next.isBlank())
                    continue;
                if (generation != null && !generation.equals(next))
                    reason = "Предметы отладки используют разные поколения метаданных";
                generation = next;
            }
            if (generation == null && reason.isEmpty())
                reason = "Не определено поколение метаданных предмета отладки";
            Global.tempLog(TOPIC, "subjects target=" + System.identityHashCode(target)
                + " connected=" + subjects.size() + " generation=" + generation + " reason=" + reason);
            return new Version(document, editor != null ? editor.model : null,
                stamp, file.getModificationStamp(), breakpoint.getLineNumber(),
                generation, reason, document != null && stamp == IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP
                    ? document.get() : null);
        }

        private static void prepare(IRuntimeDebugClientTarget target, IBslLineBreakpoint breakpoint,
            IFile file, State state, Version expected)
        {
            Display.getDefault().asyncExec(() ->
            {
                String text = null;
                int line = expected.markerLine;
                String failure = expected.reason;
                try
                {
                    if (!expected.equals(version(target, breakpoint, file)))
                    {
                        resend(target, breakpoint);
                        return;
                    }
                    if (expected.document != null)
                    {
                        text = expected.document.get();
                        Position position = markerPosition(expected.model, breakpoint.getMarker());
                        if (position != null && !position.isDeleted())
                            line = expected.document.getLineOfOffset(position.offset) + 1;
                        else
                            failure = "Не найдена текущая позиция точки в редакторе модуля";
                        Global.tempLog(TOPIC, "capture file=" + file.getFullPath() + " document="
                            + expected.document.getClass().getName() + " stamp=" + expected.stamp
                            + " model=" + (expected.model != null ? expected.model.getClass().getName() : "null")
                            + " position=" + position + " marker=" + expected.markerLine + " editor=" + line);
                    }
                }
                catch (Exception e)
                {
                    failure = "Не удалось прочитать текущую строку точки: " + e.getMessage();
                }
                String captured = text;
                int editorLine = line;
                String captureFailure = failure;
                Job job = Job.create("Комфорт: сопоставление строки точки останова", monitor ->
                {
                    int runtimeLine = -1;
                    String reason = captureFailure;
                    try
                    {
                        if (reason.isEmpty())
                        {
                            String current = captured;
                            if (current == null)
                                try (var contents = file.getContents())
                                {
                                    current = new String(contents.readAllBytes(),
                                        java.nio.charset.Charset.forName(file.getCharset()));
                                }
                            BslModuleReference reference = target.getModuleLocator().getReference(file);
                            String original = source(target, reference, expected.generation, file,
                                () -> target.getModuleLocator().getModule(reference, true),
                                () -> !expected.equals(uncheckedVersion(target, breakpoint, file)));
                            Mapping mapping = matching(target, file, expected.generation, current, original)
                                .at(editorLine - 1);
                            if (mapping.exact)
                                runtimeLine = mapping.line + 1;
                            else
                                reason = "У строки нет однозначного соответствия в модуле базы";
                        }
                    }
                    catch (Exception e)
                    {
                        reason = "Не удалось сопоставить строку с модулем базы: " + e.getMessage();
                        Global.tempLog(TOPIC, "prepare failed file=" + file.getFullPath() + ": " + e);
                    }
                    Global.tempLog(TOPIC, "prepared file=" + file.getFullPath() + " marker="
                        + expected.markerLine + " editor=" + editorLine + " runtime=" + runtimeLine
                        + " reason=" + reason);
                    synchronized (state)
                    {
                        if (!expected.equals(state.version))
                            return Status.OK_STATUS;
                        state.line = runtimeLine;
                        state.reason = reason;
                    }
                    BreakpointListHook.sourceMappingChanged();
                    resend(target, breakpoint);
                    return Status.OK_STATUS;
                });
                job.setSystem(true);
                job.schedule();
            });
        }

        /** Один diff на модуль и снимок текста, а не на каждую точку в модуле. Только фон. */
        private static LineMapping matching(IRuntimeDebugClientTarget target, IFile file,
            String generation, String current, String original)
        {
            synchronized (MATCHINGS)
            {
                Map<IFile, ReverseMapping> files = MATCHINGS.computeIfAbsent(target, ignored -> new HashMap<>());
                ReverseMapping cached = files.get(file);
                if (cached == null || !Objects.equals(cached.generation, generation)
                    || !cached.current.equals(current) || !cached.original.equals(original))
                {
                    cached = new ReverseMapping(generation, current, original, new LineMapping(current, original));
                    files.put(file, cached);
                }
                return cached.matching;
            }
        }

        private static Position markerPosition(IAnnotationModel model, IMarker marker)
        {
            if (model instanceof AbstractMarkerAnnotationModel markers)
            {
                Position position = markers.getMarkerPosition(marker);
                if (position != null)
                    return position;
            }
            if (model != null)
                for (var iterator = model.getAnnotationIterator(); iterator.hasNext();)
                {
                    Annotation annotation = iterator.next();
                    if (annotation instanceof SimpleMarkerAnnotation marked && marker.equals(marked.getMarker()))
                        return model.getPosition(annotation);
                }
            return null;
        }

        private static Version uncheckedVersion(IRuntimeDebugClientTarget target, IBslLineBreakpoint breakpoint, IFile file)
        {
            try
            {
                return version(target, breakpoint, file);
            }
            catch (Exception e)
            {
                Global.tempLog(TOPIC, "version failed file=" + file.getFullPath() + ": " + e);
                return null;
            }
        }

        private static void resend(IRuntimeDebugClientTarget target, IBslLineBreakpoint breakpoint)
        {
            DebugPlugin.getDefault().asyncExec(() ->
            {
                try
                {
                    IMarker marker = breakpoint.getMarker();
                    if (!target.isTerminated() && !target.isDisconnected() && marker != null
                        && marker.exists() && breakpoint.isRegistered())
                    {
                        Global.tempLog(TOPIC, "resend marker=" + marker.getId());
                        target.breakpointChanged(breakpoint, null);
                    }
                }
                catch (Exception e)
                {
                    Global.tempLog(TOPIC, "resend failed: " + e);
                }
            });
        }

        private record EditorDocument(IDocument document, IAnnotationModel model) {}

        private record ReverseMapping(String generation, String current, String original, LineMapping matching) {}

        private record Version(IDocument document, IAnnotationModel model, long stamp, long fileStamp, int markerLine,
            String generation, String reason, String unknownText) {}

        private static final class State
        {
            Version version;
            int line = -1;
            int sentLine = -1;
            volatile String reason = "";
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
            }
            return text;
        }
    }
}
