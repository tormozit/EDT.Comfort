package tormozit;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.emf.ecore.InternalEObject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Логи Content Assist в общем журнале {@link GlobalLogView} (при включённом «Вести журнал»).
 */
public final class ContentAssistDebug
{
    private static final AtomicInteger validateCalls = new AtomicInteger();
    private static final AtomicInteger agentValidateLogs = new AtomicInteger();

    /** Маркер сборки для literal assist. */
    public static final String LITERAL_ASSIST_BUILD = "20260708-sync-selection"; //$NON-NLS-1$

    private ContentAssistDebug() {}

    // Временная диагностика рекурсии EDT. Исключение содержит только последние 1024
    // кадра, поэтому сохраняем исходный вызов ДО восстановления ссылок блока.
    private static final String MODEL_TRACE_PROPERTY = "tormozit.assist.modelResolutionTrace";
    private static final String MODEL_TRACE_CLASS =
        "com._1c.g5.v8.dt.bsl.resource.BslDerivedStateComputer";
    private static final String MODEL_TRACE_DESCRIPTOR =
        "(Lcom/_1c/g5/v8/dt/bsl/model/Block;)V";
    private static final String MODEL_UNLOAD_PROPERTY = "tormozit.assist.modelUnloadTrace";
    private static final String MODEL_UNLOAD_CLASS =
        "com._1c.g5.v8.dt.bsl.parser.antlr.BslUnloader";
    private static final String MODEL_UNLOAD_DESCRIPTOR = "(Lorg/eclipse/emf/ecore/EObject;)V";
    private static final java.util.concurrent.atomic.AtomicBoolean modelTraceInstalled =
        new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean modelTraceWeavingInstalled =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** Раньше загрузки BSL-классов: работает и при запрещённом self-attach агента. */
    static void installModelResolutionWeaving(org.osgi.framework.BundleContext context)
    {
        if (!modelTraceWeavingInstalled.compareAndSet(false, true))
            return;
        try
        {
            installModelTraceConsumers();
            if (context == null)
                throw new IllegalStateException("No BundleContext for model diagnostics");
            context.registerService(org.osgi.framework.hooks.weaving.WeavingHook.class,
                new ModelResolutionWeavingHook(), null);
            Global.tempLog("assist-model", "weaving registered=true");
        }
        catch (Throwable error)
        {
            modelTraceWeavingInstalled.set(false);
            Global.tempLogException("assist-model", "weaving registration failed", error);
        }
    }

    static void installModelResolutionTrace()
    {
        if (!modelTraceInstalled.compareAndSet(false, true))
            return;
        installModelTraceConsumers();
        boolean registered = BslDocCommentDescriptionFix.registerExtraTransformer(
            new ModelResolutionTransformer(), MODEL_TRACE_CLASS, MODEL_UNLOAD_CLASS);
        Global.tempLog("assist-model", "install registered=" + registered
            + " weavingRegistered=" + modelTraceWeavingInstalled.get());
    }

    private static void installModelTraceConsumers()
    {
        System.getProperties().put(MODEL_TRACE_PROPERTY,
            (java.util.function.Consumer<Object>) block ->
                traceModelResolution("installProxiesForBlock", block));
        System.getProperties().put(MODEL_UNLOAD_PROPERTY,
            (java.util.function.Consumer<Object>) block -> traceModelResolution("unloadRoot", block));
    }

    /** Только basic-доступ: eContainer/eResource здесь сами могли бы запустить рекурсию. */
    private static void traceModelResolution(String phase, Object block)
    {
        try
        {
            StringBuilder detail = new StringBuilder(phase).append(" thread=")
                .append(pipelineText(Thread.currentThread().getName()));
            PipelineSpan active = pipelineSpan.get();
            detail.append(" span=").append(active == null ? 0 : active.id);
            java.util.Set<InternalEObject> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
            InternalEObject object = block instanceof InternalEObject e ? e : null;
            while (object != null && seen.size() < 64 && seen.add(object))
            {
                detail.append(" ancestor=").append(object.eClass().getName())
                    .append('@').append(pipelineIdentity(object))
                    .append(" proxy=").append(object.eIsProxy())
                    .append(" uri=").append(object.eProxyURI());
                org.eclipse.emf.ecore.resource.Resource direct = object.eDirectResource();
                if (direct != null)
                {
                    detail.append(" directResource=").append(pipelineIdentity(direct))
                        .append(" resourceUri=").append(direct.getURI());
                    if (direct instanceof org.eclipse.xtext.resource.XtextResource xtext
                        && xtext.getParseResult() != null)
                        detail.append(" parseRoot=").append(
                            pipelineIdentity(xtext.getParseResult().getRootASTElement()));
                }
                object = object.eInternalContainer();
            }
            detail.append(" chainRemaining=").append(pipelineIdentity(object));
            boolean recursive = isRecursiveModelTrace(phase);
            detail.append(" recursive=").append(recursive);
            if (recursive)
                Global.tempLog("assist-model", detail.toString());
            else
                Global.tempLogException("assist-model", detail.toString(),
                    new Throwable("model resolution entry"));
        }
        catch (Throwable diagnosticFailure)
        {
            // Диагностика не должна менять результат или исключение штатного метода.
            try
            {
                Global.tempLogException("assist-model", "diagnostics failed", diagnosticFailure);
            }
            catch (Throwable ignored)
            {
            }
        }
    }

    /** Каждый вход пишется; стек сохраняем у внешнего вызова, а не у каждого витка. */
    private static boolean isRecursiveModelTrace(String phase)
    {
        String owner = "unloadRoot".equals(phase) ? MODEL_UNLOAD_CLASS : MODEL_TRACE_CLASS;
        return StackWalker.getInstance().walk(frames -> frames
            .filter(frame -> owner.equals(frame.getClassName()) && phase.equals(frame.getMethodName()))
            .limit(2).count() > 1);
    }

    private static final class ModelResolutionWeavingHook
        implements org.osgi.framework.hooks.weaving.WeavingHook
    {
        @Override
        public void weave(org.osgi.framework.hooks.weaving.WovenClass woven)
        {
            if (!(MODEL_TRACE_CLASS.equals(woven.getClassName())
                    || MODEL_UNLOAD_CLASS.equals(woven.getClassName()))
                || woven.getState() != org.osgi.framework.hooks.weaving.WovenClass.TRANSFORMING)
                return;
            byte[] bytes = new ModelResolutionTransformer().transform(null,
                woven.getClassName().replace('.', '/'), null, null, woven.getBytes());
            if (bytes != null)
                woven.setBytes(bytes);
            Global.tempLog("assist-model", "weaving class=" + woven.getClassName()
                + " patched=" + (bytes != null));
        }
    }

    /** Наблюдение проверенного в JAR метода; ни возврат, ни исключения EDT не подменяются. */
    private static final class ModelResolutionTransformer
        implements java.lang.instrument.ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
            java.security.ProtectionDomain domain, byte[] bytes)
        {
            boolean unload = MODEL_UNLOAD_CLASS.replace('.', '/').equals(name);
            if (!unload && !MODEL_TRACE_CLASS.replace('.', '/').equals(name))
                return null;
            String targetMethod = unload ? "unloadRoot" : "installProxiesForBlock";
            String targetDescriptor = unload ? MODEL_UNLOAD_DESCRIPTOR : MODEL_TRACE_DESCRIPTOR;
            String targetProperty = unload ? MODEL_UNLOAD_PROPERTY : MODEL_TRACE_PROPERTY;
            try
            {
                ClassReader reader = new ClassReader(bytes);
                // WeavingHook и Instrumentation могут наблюдать одну загрузку класса.
                // Второй раз тот же callback не вставляем.
                java.util.concurrent.atomic.AtomicBoolean alreadyTraced =
                    new java.util.concurrent.atomic.AtomicBoolean();
                reader.accept(new ClassVisitor(Opcodes.ASM9)
                {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String descriptor,
                        String signature, String[] exceptions)
                    {
                        if (!targetMethod.equals(method) || !targetDescriptor.equals(descriptor))
                            return null;
                        return new MethodVisitor(Opcodes.ASM9)
                        {
                            @Override
                            public void visitLdcInsn(Object value)
                            {
                                if (targetProperty.equals(value))
                                    alreadyTraced.set(true);
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                if (alreadyTraced.get())
                {
                    Global.tempLog("assist-model", "transform alreadyTraced=true");
                    return null;
                }
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                java.util.concurrent.atomic.AtomicBoolean touched =
                    new java.util.concurrent.atomic.AtomicBoolean();
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
                {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String descriptor,
                        String signature, String[] exceptions)
                    {
                        MethodVisitor visitor = super.visitMethod(
                            access, method, descriptor, signature, exceptions);
                        if (visitor == null || !targetMethod.equals(method)
                            || !targetDescriptor.equals(descriptor))
                            return visitor;
                        touched.set(true);
                        return new MethodVisitor(Opcodes.ASM9, visitor)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System",
                                    "getProperties", "()Ljava/util/Properties;", false);
                                visitLdcInsn(targetProperty);
                                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties",
                                    "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                visitInsn(Opcodes.DUP);
                                visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Consumer");
                                Label noConsumer = new Label();
                                Label original = new Label();
                                visitJumpInsn(Opcodes.IFEQ, noConsumer);
                                visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
                                visitVarInsn(Opcodes.ALOAD, 1);
                                visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer",
                                    "accept", "(Ljava/lang/Object;)V", true);
                                visitJumpInsn(Opcodes.GOTO, original);
                                visitLabel(noConsumer);
                                visitFrame(Opcodes.F_SAME1, 0, null, 1,
                                    new Object[] { "java/lang/Object" });
                                visitInsn(Opcodes.POP);
                                visitLabel(original);
                                visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }
                        };
                    }
                }, 0);
                Global.tempLog("assist-model", "transform method=" + targetMethod
                    + " matched=" + touched.get()
                    + " redefined=" + (redefined != null));
                return touched.get() ? writer.toByteArray() : null;
            }
            catch (Throwable error)
            {
                Global.tempLogException("assist-model", "transform failed", error);
                return null;
            }
        }
    }

    // Временная диагностика текущей сессии. Не зависит от «Вести журнал».
    private static final java.util.concurrent.atomic.AtomicLong pipelineIds =
        new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong pipelineEvents =
        new java.util.concurrent.atomic.AtomicLong();
    private static final ThreadLocal<PipelineSpan> pipelineSpan = new ThreadLocal<>();
    /** Счётчики потока: сравнения, вызовы/время оформления, время записи диагностики. */
    private static final ThreadLocal<long[]> pipelineCounters =
        ThreadLocal.withInitial(() -> new long[4]);

    static long pipelineNextId()
    {
        return pipelineIds.incrementAndGet();
    }

    static String pipelineIdentity(Object value)
    {
        return value == null ? "null" : Integer.toHexString(System.identityHashCode(value));
    }

    static String pipelineText(String value)
    {
        if (value == null)
            return "null";
        String clipped = value.length() > 120 ? value.substring(0, 120) + "…" : value;
        return "\"" + clipped.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    /** По просьбе пользователя оставлены итоги этапов, без покадровой детализации. */
    static boolean pipelineEventEnabled(String event)
    {
        return switch (event)
        {
            case "compute.end", "filter.end", "filter.sort.end", "cache.filter.end",
                "popup.stockFilter.end", "popup.stockFilter.work", "popup.recompute.end",
                "word.exit", "member.exit", "gate.action.end", "paramHint.open.finish",
                "showPossibleCompletions.finish", "assist.documentChanged.finish" -> true;
            default -> false;
        };
    }

    static void pipeline(String event, String detail)
    {
        if (!pipelineEventEnabled(event))
            return;
        long logStartNs = System.nanoTime();
        PipelineSpan active = pipelineSpan.get();
        long[] counters = pipelineCounters.get();
        Global.tempLog("assist-pipeline", "seq=" + pipelineEvents.incrementAndGet()
            + " ns=" + System.nanoTime() + " event=" + event
            + " span=" + (active == null ? 0 : active.id)
            + " trace=" + (active == null ? 0 : active.trace)
            + " ui=" + (org.eclipse.swt.widgets.Display.getCurrent() != null)
            + " comparesTotal=" + counters[0] + " stylesTotal=" + counters[1]
            + " thread=" + pipelineText(Thread.currentThread().getName())
            + (detail == null || detail.isEmpty() ? "" : " " + detail.replace('\r', ' ').replace('\n', ' ')));
        counters[3] += System.nanoTime() - logStartNs;
    }

    static PipelineSpan pipelineStart(String event, String detail)
    {
        return new PipelineSpan(event, detail);
    }

    static void pipelineCompare()
    {
        pipelineCounters.get()[0]++;
    }

    static void pipelineStyled(long startNs, Object proposal)
    {
        long elapsedNs = System.nanoTime() - startNs;
        long[] counters = pipelineCounters.get();
        counters[1]++;
        counters[2] += elapsedNs;
        pipeline("row.style", "proposal=" + pipelineIdentity(proposal)
            + " filter=" + pipelineText(SmartFilterTracker.getCurrentFilter())
            + " elapsedNs=" + elapsedNs);
    }

    static String pipelineList(java.util.List<ICompletionProposal> list)
    {
        long digest = 1;
        if (list != null)
            for (ICompletionProposal proposal : list)
                digest = digest * 31 + System.identityHashCode(
                    SmartContentAssistProcessor.unwrapProposal(proposal));
        return "list=" + pipelineIdentity(list) + " n=" + (list == null ? -1 : list.size())
            + " order=" + Long.toHexString(digest)
            + " first=" + pipelineIdentity(list == null || list.isEmpty() ? null
                : SmartContentAssistProcessor.unwrapProposal(list.get(0)));
    }

    /** Идентификаторы предложений, без чтения текста/модели/иконок. */
    static String pipelineList(ICompletionProposal[] list)
    {
        long digest = 1;
        if (list != null)
            for (ICompletionProposal proposal : list)
                digest = digest * 31 + System.identityHashCode(
                    SmartContentAssistProcessor.unwrapProposal(proposal));
        return "list=" + pipelineIdentity(list) + " n=" + (list == null ? -1 : list.length)
            + " order=" + Long.toHexString(digest)
            + " first=" + pipelineIdentity(list == null || list.length == 0 ? null
                : SmartContentAssistProcessor.unwrapProposal(list[0]));
    }

    /** Один синхронный этап; вложенность и счётчики принадлежат текущему потоку. */
    static final class PipelineSpan implements AutoCloseable
    {
        private final long id = pipelineNextId();
        private final PipelineSpan parent = pipelineSpan.get();
        private final long trace = parent == null ? id : parent.trace;
        private final String event;
        private final long startNs = System.nanoTime();
        private final long compares = pipelineCounters.get()[0];
        private final long styles = pipelineCounters.get()[1];
        private final long styleNs = pipelineCounters.get()[2];
        private final long logNs = pipelineCounters.get()[3];

        private PipelineSpan(String event, String detail)
        {
            this.event = event;
            pipelineSpan.set(this);
            pipeline(event + ".begin", "parent=" + (parent == null ? 0 : parent.id)
                + " " + (detail == null ? "" : detail));
        }

        @Override
        public void close()
        {
            long elapsedNs = System.nanoTime() - startNs;
            long[] counters = pipelineCounters.get();
            pipeline(event + ".end", "elapsedNs=" + elapsedNs
                + " compares=" + (counters[0] - compares)
                + " styles=" + (counters[1] - styles)
                + " styleNs=" + (counters[2] - styleNs)
                + " logNs=" + (counters[3] - logNs));
            if (parent == null)
                pipelineSpan.remove();
            else
                pipelineSpan.set(parent);
        }
    }

    // Временная детализация применения списка. Отдельный потоковый стек нужен и для
    // вызовов JFace, и для вложенных callback предложений. Не читает текст/модель объекта.
    private static final ThreadLocal<FillSpan> fillSpan = new ThreadLocal<>();
    private static final ThreadLocal<long[]> fillLogNanos =
        ThreadLocal.withInitial(() -> new long[1]);
    private static final java.lang.management.ThreadMXBean FILL_THREAD_MX =
        java.lang.management.ManagementFactory.getThreadMXBean();

    static FillSpan fillStart(String event, Object object)
    {
        return fillStart(event, object, "");
    }

    static FillSpan fillStart(String event, Object object, String detail)
    {
        // Подробности каждого геттера/выбора строки больше не нужны. Для оставленных
        // операций пишем каждый вход/выход, без порога времени и флажка журнала.
        if (!event.equals("setProposals") && !event.equals("table.forceFill")
            && !event.startsWith("paramHint."))
            return null;
        return new FillSpan(event, object, detail);
    }

    /** Каждый вход/выход пишется без флажков и порогов, без вызова методов proposal. */
    private static void fillWrite(FillSpan span, String state, String detail)
    {
        long start = System.nanoTime();
        Global.tempLog("assist-fill", "ns=" + start + " state=" + state
            + " id=" + span.id + " parent=" + (span.parent == null ? 0 : span.parent.id)
            + " root=" + span.root + " pipeline=" + span.pipelineId
            + " event=" + span.event + " object=" + span.objectId + " class=" + span.objectClass
            + " thread=" + pipelineText(Thread.currentThread().getName())
            + (detail.isEmpty() ? "" : " " + detail.replace('\r', ' ').replace('\n', ' ')));
        fillLogNanos.get()[0] += System.nanoTime() - start;
    }

    /** Синхронный вызов и его вложенные callbacks; счётчики не суммировать повторно. */
    static final class FillSpan implements AutoCloseable
    {
        private final long id = pipelineNextId();
        private final FillSpan parent = fillSpan.get();
        private final long root = parent == null ? id : parent.root;
        private final long pipelineId = pipelineSpan.get() == null ? 0 : pipelineSpan.get().id;
        private final long startNs = System.nanoTime();
        private final long logStartNs = fillLogNanos.get()[0];
        private final String event;
        private final String objectId;
        private final String objectClass;
        private final boolean measureThread;
        private final long cpuBefore;
        private final java.lang.management.ThreadInfo threadBefore;
        private long childNs;
        private boolean closed;

        private FillSpan(String event, Object object, String detail)
        {
            this.event = event;
            objectId = pipelineIdentity(object);
            objectClass = object == null ? "null" : object.getClass().getName();
            // JMX только на границах редких UI операций, не на каждом сравнении/геттере.
            measureThread = event.equals("setProposals") || event.equals("table.forceFill")
                || event.equals("popup.select") || event.equals("popup.configure");
            cpuBefore = measureThread && FILL_THREAD_MX.isCurrentThreadCpuTimeSupported()
                ? FILL_THREAD_MX.getCurrentThreadCpuTime() : -1L;
            threadBefore = measureThread
                ? FILL_THREAD_MX.getThreadInfo(Thread.currentThread().getId()) : null;
            fillSpan.set(this);
            fillWrite(this, "begin", detail);
        }

        @Override
        public void close()
        {
            if (closed)
                return;
            closed = true;
            long elapsedNs = System.nanoTime() - startNs;
            long cpuAfter = measureThread && FILL_THREAD_MX.isCurrentThreadCpuTimeSupported()
                ? FILL_THREAD_MX.getCurrentThreadCpuTime() : -1L;
            java.lang.management.ThreadInfo after = measureThread
                ? FILL_THREAD_MX.getThreadInfo(Thread.currentThread().getId()) : null;
            boolean contention = threadBefore != null && after != null
                && threadBefore.getBlockedTime() >= 0 && after.getBlockedTime() >= 0;
            try
            {
                fillWrite(this, "end", "elapsedNs=" + elapsedNs
                    + " childNs=" + childNs + " selfNs=" + Math.max(0, elapsedNs - childNs)
                    + " logNs=" + (fillLogNanos.get()[0] - logStartNs)
                    + " cpuNs=" + (cpuBefore >= 0 && cpuAfter >= 0 ? cpuAfter - cpuBefore : -1L)
                    + " blockedMs=" + (contention
                        ? after.getBlockedTime() - threadBefore.getBlockedTime() : -1L)
                    + " waitedMs=" + (contention
                        ? after.getWaitedTime() - threadBefore.getWaitedTime() : -1L));
            }
            finally
            {
                if (parent == null)
                    fillSpan.remove();
                else
                {
                    parent.childNs += System.nanoTime() - startNs;
                    fillSpan.set(parent);
                }
            }
        }
    }

    /** No-op: раньше NDJSON на диск; вызовы оставлены в коде без I/O. */
    public static void debugModeLog(String hypothesisId, String location, String message, String dataJson)
    {
    }

    /** No-op: раньше session NDJSON. */
    public static void sessionLog(String hypothesisId, String location, String message, String dataJson)
    {
    }

    public static void logLiteralAssistBuildStamp()
    {
    }

    public static void logAutoOpen(int autoOpenSeq, String hypothesisId, String location,
        String message, String dataJson)
    {
        agentLog(hypothesisId, location, message, dataJson);
    }

    public static void traceAssist(String hypothesisId, String location, String message,
        String dataJson)
    {
        agentLog(hypothesisId, location, message, dataJson);
    }

    public static void logAutoOpenDecision(String decision, int caret, int irN)
    {
        agentLog("H78", "onWordsTablePrepared", "autoOpenDecision", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"decision\":\"" + jsonEscape(decision) + "\",\"caret\":" + caret //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"irN\":" + irN + "}"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static String jsonEscapeForLog(String value)
    {
        return jsonEscape(value);
    }

    private static String jsonEscape(String value)
    {
        if (value == null)
            return ""; //$NON-NLS-1$
        return value.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** Диагностика assist — только в «Журнал Комфорт» при включённом «Вести журнал». */
    public static void agentLog(String hypothesisId, String location, String message, String dataJson)
    {
        if (!Global.isLogEnabled())
            return;
        String data = dataJson != null && !dataJson.isEmpty() ? dataJson : "{}"; //$NON-NLS-1$
        Global.log("contentAssist", //$NON-NLS-1$
            "[" + hypothesisId + "] " + location + " " + message + " " + data); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** Временный замер: thresholdMs — данные, никогда не условие записи. */
    public static void perfLog(String location, long elapsedMs, int thresholdMs, String extraJson)
    {
        pipeline(location, "elapsedMs=" + elapsedMs + " thresholdMs=" + thresholdMs
            + " data=" + extraJson);
    }

    /** Начало временного замера; парный perfEnd пишет и короткие вызовы. */
    public static long perfStart(String location)
    {
        long start = System.nanoTime();
        pipeline(location + ".start", "startNs=" + start);
        return start;
    }

    /** Безусловный конец временного замера. */
    public static void perfEnd(String location, long startNanos, String extraJson)
    {
        pipeline(location + ".finish", "startNs=" + startNanos
            + " elapsedNs=" + (System.nanoTime() - startNanos) + " data=" + extraJson);
    }

    /** Безусловная отметка временной диагностики. */
    public static void perfMark(String location, String extraJson)
    {
        pipeline(location, "data=" + extraJson);
    }

    public static boolean shouldLogValidateLiteral()
    {
        if (!Global.isLogEnabled())
            return false;
        return agentValidateLogs.incrementAndGet() <= 5;
    }

    public static boolean isEnabled()
    {
        return Global.isLogEnabled();
    }

    public static void log(String msg)
    {
        pipeline("message", msg);
        Global.log("contentAssist", msg); //$NON-NLS-1$
    }

    public static void logValidate(boolean accepted, String filter, ICompletionProposal proposal, int offset)
    {
        validateCalls.incrementAndGet();
    }

    public static String sampleTypes(ICompletionProposal[] arr, int max)
    {
        if (arr == null || arr.length == 0)
            return " types=[]"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder(" types=["); //$NON-NLS-1$
        int n = Math.min(max, arr.length);
        for (int i = 0; i < n; i++)
        {
            if (i > 0)
                sb.append(',');
            sb.append(arr[i] == null ? "null" : arr[i].getClass().getSimpleName()); //$NON-NLS-1$
        }
        if (arr.length > n)
            sb.append("…"); //$NON-NLS-1$
        return sb.append(']').toString();
    }

    public static void resetValidateStats()
    {
        validateCalls.set(0);
    }

    public static String proposalLabel(ICompletionProposal p)
    {
        if (p == null)
            return "null"; //$NON-NLS-1$
        String d = p.getDisplayString();
        String type = p.getClass().getSimpleName();
        if (p instanceof SmartCompletionProposal)
            type += "→" + ((SmartCompletionProposal) p).getDelegate().getClass().getSimpleName(); //$NON-NLS-1$
        String text = d == null ? "" : d; //$NON-NLS-1$
        if (text.length() > 48)
            text = text.substring(0, 48) + "…"; //$NON-NLS-1$
        return type + ":" + text; //$NON-NLS-1$
    }

    public static String eventSummary(DocumentEvent e)
    {
        if (e == null)
            return "null"; //$NON-NLS-1$
        String t = e.getText();
        return "off=" + e.getOffset() + " len=" + (t == null ? 0 : t.length()) //$NON-NLS-1$ //$NON-NLS-2$
            + " text=\"" + (t == null ? "" : t.replace('\n', ' ')) + "\""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
