package tormozit;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.swt.graphics.Image;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatch;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatchStatus;
import com._1c.g5.v8.dt.compare.core.ComparisonContext;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus;
import com._1c.g5.v8.dt.compare.core.ComparisonUtils;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.core.IComparisonParticipant;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.core.SerializableMergeSettings;
import com._1c.g5.v8.dt.compare.datasource.ComparisonDataSourceTransactionalContext;
import com._1c.g5.v8.dt.compare.merge.IMergeSession;
import com._1c.g5.v8.dt.compare.merge.MergeContext;
import com._1c.g5.v8.dt.compare.merge.MergeUtils;
import com._1c.g5.v8.dt.compare.merge.MergeValidationProblem;
import com._1c.g5.v8.dt.compare.model.CompareFactory;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.MergeRule;
import com._1c.g5.v8.dt.compare.model.MergeSettings;
import com._1c.g5.v8.dt.compare.model.SolidResourceComparisonNode;
import com._1c.g5.v8.dt.compare.model.TextNodeCustomMergeSettings;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonViewContext;
import com._1c.g5.v8.dt.compare.ui.partialmodel.IPartialModel;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IPartialModelNode;

/**
 * Результат табличного объединения (#1015) в плане сравнения/объединения — так же, как у модуля:
 * правило «Пользовательское объединение» ({@link MergeRule#CUSTOM_MERGE}) и XML результата в
 * {@link TextNodeCustomMergeSettings} узла макета.
 *
 * <p>Штатно EDT даёт ручное объединение только текстовому макету
 * ({@code ExternalPropertyUtils.isTemplateSupportManualMerge}), поэтому штатный участник сравнения
 * ресурсов в списке участников менеджера сравнения заменяется обёрткой, которая для узла «табличный
 * документ с обеих сторон» поддерживает пользовательское объединение и пишет результат штатной
 * отложенной операцией, а всё остальное отдаёт штатному участнику.
 *
 * <p>Логика взята из edt-agent-bridge ({@code SpreadsheetMergeBridge}, {@code SpreadsheetMergeSupport.commit},
 * бандл {@code com._1c.g5.v8.dt.compare} 28); проверено по {@code com._1c.g5.v8.dt.compare_27.0.1.v202605050943}.
 */
public final class SpreadsheetMergePlan
{
    private static final String TOPIC = "spreadsheet-merge-1015"; //$NON-NLS-1$
    private static final String SPREADSHEET = "SpreadsheetDocument"; //$NON-NLS-1$
    /** Ключ настроек узла в сохранённом плане объединения. */
    private static final String SETTINGS_KEY = "TormozitSpreadsheetCustomMergeSettings"; //$NON-NLS-1$
    private static final String PARTICIPANT_CLASS =
        "com._1c.g5.v8.dt.internal.compare.SolidResourceComparisonParticipant"; //$NON-NLS-1$
    private static final int VALIDATION_NODE_LIMIT = 100_000;

    private static volatile boolean installed;
    private static boolean listening;
    private static boolean statusListening;

    private SpreadsheetMergePlan()
    {
    }

    /**
     * Ставит обёртку штатного участника, если её ещё нет. Идемпотентна; вызывается при старте,
     * при регистрации менеджера сравнения, при открытии редактора сравнения и перед записью.
     * Узлы уже построенного сравнения набор правил не пересчитывают — их сравнение надо открыть заново.
     */
    public static synchronized void ensureInstalled(String reason)
    {
        if (installed)
            return;
        try
        {
            BundleContext context = Global.ourContext();
            if (context == null)
                return;
            ServiceReference<IComparisonManager> reference = context.getServiceReference(IComparisonManager.class);
            if (reference == null)
            {
                listenForManager(context);
                tempLog("plan.install[" + reason + "]: менеджера сравнения ещё нет"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            IComparisonManager manager = context.getService(reference);
            installInto(manager, reason);
            if (!installed && !statusListening)
            {
                // Участники загружаются позже регистрации менеджера; запуск сравнения — последний момент до построения узлов
                manager.addStatusListener(new IComparisonManager.ICompareMergeStatusListener()
                {
                    @Override
                    public void statusChanged(ComparisonProcessHandle handle, ComparisonProcessStatus status)
                    {
                        ensureInstalled("status " + status); //$NON-NLS-1$
                    }

                    @Override
                    public void statusChanged(CompareMergeProcessBatch batch, CompareMergeProcessBatchStatus status)
                    {
                        ensureInstalled("batch " + status); //$NON-NLS-1$
                    }
                });
                statusListening = true;
            }
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            tempLog("plan.install[" + reason + "]: исключение " + e); //$NON-NLS-1$ //$NON-NLS-2$
            Global.logError("SpreadsheetMerge", "не удалось включить табличное объединение в плане", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void installInto(IComparisonManager manager, String reason) throws ReflectiveOperationException
    {
        Field field = findField(manager.getClass(), "participants"); //$NON-NLS-1$
        if (field == null)
        {
            tempLog("plan.install[" + reason + "]: нет поля participants у " + manager.getClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<IComparisonParticipant> participants = (List<IComparisonParticipant>) field.get(manager);
        int replaced = 0;
        for (int i = 0; i < participants.size(); i++)
        {
            IComparisonParticipant original = participants.get(i);
            if (original == null || Proxy.isProxyClass(original.getClass())
                || !PARTICIPANT_CLASS.equals(original.getClass().getName()))
                continue;
            // Тот же индекс: менеджер кэширует участника узла по номеру в списке
            participants.set(i, wrap(original));
            replaced++;
        }
        installed = replaced > 0;
        tempLog("plan.install[" + reason + "]: участников=" + participants.size() + ", заменено=" + replaced //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + ", идёт сравнение=" + manager.hasActiveComparison()); //$NON-NLS-1$
    }

    private static void listenForManager(BundleContext context)
    {
        if (listening)
            return;
        try
        {
            context.addServiceListener(event -> {
                if (event.getType() == ServiceEvent.REGISTERED)
                    ensureInstalled("service"); //$NON-NLS-1$
            }, "(objectClass=" + IComparisonManager.class.getName() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            listening = true;
        }
        catch (Exception e)
        {
            tempLog("plan.install: слушатель службы не поставлен " + e); //$NON-NLS-1$
        }
    }

    private static IComparisonParticipant wrap(IComparisonParticipant original)
    {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        interfaces.add(IComparisonParticipant.class);
        for (Class<?> type = original.getClass(); type != null; type = type.getSuperclass())
            for (Class<?> candidate : type.getInterfaces())
                if (Modifier.isPublic(candidate.getModifiers()))
                    interfaces.add(candidate);
        return (IComparisonParticipant) Proxy.newProxyInstance(original.getClass().getClassLoader(),
            interfaces.toArray(Class<?>[]::new), (proxy, method, args) -> dispatch(original, method, args));
    }

    private static Object dispatch(IComparisonParticipant original, Method method, Object[] args) throws Throwable
    {
        String name = method.getName();
        int argc = args != null ? args.length : 0;
        if ("supportsCustomMerging".equals(name) && argc == 2 && isSpreadsheet(args[0])) //$NON-NLS-1$
            return Boolean.TRUE;
        if ("merge".equals(name) && argc == 3 && isSpreadsheet(args[0]) && args[1] instanceof MergeContext context) //$NON-NLS-1$
        {
            SolidResourceComparisonNode node = (SolidResourceComparisonNode) args[0];
            if (rule(node) == MergeRule.CUSTOM_MERGE)
            {
                String xml = customResult(node);
                if (xml == null)
                    throw new IllegalStateException("Нет результата табличного объединения: " + node.bmGetId()); //$NON-NLS-1$
                byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
                MergeUtils.addPostponedMergeOperation(context, node, node.getMainQualifyingType(),
                    (id, converter) -> session -> new ByteArrayInputStream(bytes), converter(original));
                tempLog("plan.merge: узел=" + node.bmGetId() + ", байт=" + bytes.length); //$NON-NLS-1$ //$NON-NLS-2$
                return Status.OK_STATUS;
            }
        }
        if ("validateMerge".equals(name) && argc == 4 && args[0] instanceof IMergeSession mergeSession //$NON-NLS-1$
            && args[1] instanceof ComparisonContext context && args[2] instanceof ComparisonNode top)
        {
            @SuppressWarnings("unchecked")
            List<MergeValidationProblem> problems = (List<MergeValidationProblem>) args[3];
            validateSubtree(mergeSession.getComparisonSession(), context, top, problems);
        }
        if (("saveCustomSettings".equals(name) || "restoreCustomSettings".equals(name)) && argc == 3 //$NON-NLS-1$ //$NON-NLS-2$
            && isSpreadsheet(args[0]))
        {
            if ("saveCustomSettings".equals(name)) //$NON-NLS-1$
                MergeUtils.saveCustomSettings((ComparisonNode) args[0], (SerializableMergeSettings) args[1], SETTINGS_KEY);
            else
                MergeUtils.restoreCustomSettings((ComparisonNode) args[0], (SerializableMergeSettings) args[1], SETTINGS_KEY);
            return null;
        }
        try
        {
            return method.invoke(original, args);
        }
        catch (InvocationTargetException e)
        {
            throw e.getCause();
        }
    }

    /** Отмеченный к объединению табличный узел с пользовательским правилом, но без результата — ошибка проверки. */
    private static void validateSubtree(IComparisonSession session, ComparisonContext context, ComparisonNode top,
        List<MergeValidationProblem> problems)
    {
        ArrayDeque<ComparisonNode> queue = new ArrayDeque<>();
        queue.add(top);
        int seen = 0;
        while (!queue.isEmpty() && ++seen <= VALIDATION_NODE_LIMIT)
        {
            ComparisonNode next = session.getNode(context, queue.remove().bmGetId());
            if (next == null)
                continue;
            if (isSpreadsheet(next) && next.getMergeSettings().isMustBeMerged()
                && rule(next) == MergeRule.CUSTOM_MERGE && customResult(next) == null)
                problems.add(new MergeValidationProblem(next,
                    "Нет результата табличного объединения — откройте «" + SpreadsheetMergeWindow.MENU_LABEL //$NON-NLS-1$
                        + "» и нажмите «ОК»")); //$NON-NLS-1$
            queue.addAll(next.getChildren());
        }
    }

    /**
     * Запись по «ОК» окна табличного объединения в узел плана; {@code null} — узел не найден
     * или это не табличный документ с обеих сторон.
     */
    public static Consumer<String> writer(IPartialModelNode partial, String tag)
    {
        ensureInstalled("writer"); //$NON-NLS-1$
        IComparisonSession session = partial != null ? partial.getComparisonSession() : null;
        ComparisonNode node = partial != null ? partial.retrieveComparisonNode() : null;
        boolean spreadsheet = isSpreadsheet(node);
        tempLog("plan.writer[" + tag + "]: узел=" + (partial != null ? partial.getNodeId() : null) //$NON-NLS-1$ //$NON-NLS-2$
            + ", тип=" + (node != null ? node.eClass().getName() : null) + ", табличный=" + spreadsheet //$NON-NLS-1$ //$NON-NLS-2$
            + ", сессия=" + (session != null)); //$NON-NLS-1$
        if (session == null || !spreadsheet)
            return null;
        long id = partial.getNodeId();
        return xml -> {
            commit(session, id, xml);
            refreshTree(partial, session);
        };
    }

    /**
     * Как штатное сохранение ручного объединения ({@code PartialModelController.setMergeRule}): узел дерева и его
     * родители перечитывают правило и состояние ручного объединения (значок), затем представление перерисовывает их.
     */
    private static void refreshTree(IPartialModelNode partial, IComparisonSession session)
    {
        int refreshed = 0;
        try
        {
            List<IPartialModelNode> nodes = new java.util.ArrayList<>();
            for (IPartialModelNode node = partial; node != null; node = node.getParent())
                nodes.add(node);
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                for (IWorkbenchPage page : window.getPages())
                    for (IEditorReference reference : page.getEditorReferences())
                    {
                        IEditorPart editor = reference.getEditor(false);
                        if (editor == null || CompareConfigSelectionListener.getSession(editor) != session
                            || !(Global.getField(editor, "comparisonView") instanceof DtComparisonView view) //$NON-NLS-1$
                            || !(Global.getField(view, "context") instanceof DtComparisonViewContext context)) //$NON-NLS-1$
                            continue;
                        IPartialModel model = context.getPartialModelProvider().getPartialModel(partial);
                        context.getPartialModelController().refreshNodes(model, nodes, session);
                        for (IPartialModelNode node : nodes)
                            view.refresh(node);
                        refreshed++;
                    }
        }
        catch (RuntimeException | LinkageError e)
        {
            tempLog("plan.refresh: исключение " + e); //$NON-NLS-1$
        }
        tempLog("plan.refresh: узел=" + partial.getNodeId() + ", представлений=" + refreshed //$NON-NLS-1$ //$NON-NLS-2$
            + ", состояние=" + partial.getCustomMergeSettingsStatus()); //$NON-NLS-1$
    }

    /**
     * Узел плана для окна, открытого из диалога сравнения: выделенный в активном редакторе сравнения
     * узел (или его прямой потомок), чей файл стороны «Основная» совпадает с файлом стороны диалога.
     */
    public static IPartialModelNode findSelectedNode(ITypedElement... elements)
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        IEditorPart editor = page != null ? page.getActiveEditor() : null;
        // Поставщик выделения редактора сравнения отдаёт не узел дерева, а строку — берём выделение самого дерева
        ISelectionProvider provider = editor != null ? CompareConfigMenuHook.getTreeViewerFromEditor(editor) : null;
        ISelection selection = provider != null ? provider.getSelection() : null;
        Object first = selection instanceof IStructuredSelection structured ? structured.getFirstElement() : null;
        if (!(first instanceof IPartialModelNode selected))
        {
            tempLog("plan.find: выделено " + (first != null ? first.getClass().getName() : null) //$NON-NLS-1$
                + ", редактор=" + (editor != null ? editor.getClass().getName() : null)); //$NON-NLS-1$
            return null;
        }
        Set<Path> paths = new LinkedHashSet<>();
        for (ITypedElement element : elements)
            if (Global.getField(element, "path") instanceof Path path) //$NON-NLS-1$
                paths.add(path.normalize());
        IPartialModelNode found = matches(selected, paths) ? selected : null;
        if (found == null && selected.getChildren() != null)
            for (IPartialModelNode child : selected.getChildren())
                if (matches(child, paths))
                {
                    found = child;
                    break;
                }
        tempLog("plan.find: выделен=" + selected.getNodeId() + ", пути=" + paths + ", найден=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + (found != null ? found.getNodeId() : null));
        return found;
    }

    /** Сохранённый в плане результат табличного объединения узла; {@code null} — ручного объединения нет. */
    public static String savedResult(IPartialModelNode partial)
    {
        ComparisonNode node = partial != null ? partial.retrieveComparisonNode() : null;
        return isSpreadsheet(node) && rule(node) == MergeRule.CUSTOM_MERGE ? customResult(node) : null;
    }

    /** Сторона окна с содержимым {@code xml} под именем и типом {@code like}. */
    public static ITypedElement textElement(ITypedElement like, String xml)
    {
        return new XmlTypedElement(like != null ? like.getName() : "Template.mxlx", //$NON-NLS-1$
            like != null ? like.getType() : "mxlx", xml); //$NON-NLS-1$
    }

    private static final class XmlTypedElement implements ITypedElement, IEncodedStreamContentAccessor
    {
        private final String name;
        private final String type;
        private final String xml;

        XmlTypedElement(String name, String type, String xml)
        {
            this.name = name;
            this.type = type;
            this.xml = xml;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public Image getImage()
        {
            return null;
        }

        @Override
        public String getType()
        {
            return type;
        }

        @Override
        public InputStream getContents()
        {
            return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String getCharset()
        {
            return StandardCharsets.UTF_8.name();
        }
    }

    private static boolean matches(IPartialModelNode partial, Set<Path> paths)
    {
        ComparisonNode node = partial.retrieveComparisonNode();
        if (!isSpreadsheet(node))
            return false;
        if (paths.isEmpty())
            return true;
        SolidResourceComparisonNode resource = (SolidResourceComparisonNode) node;
        IQualifiedNameFilePathConverter converter = managerConverter(partial.getComparisonSession());
        if (converter == null)
            return true;
        for (ComparisonSide side : new ComparisonSide[] { ComparisonSide.MAIN, ComparisonSide.OTHER })
        {
            String link = resource.getSymlink(side);
            Path path = link != null ? ComparisonUtils.getFilePathBySymlink(link, resource.getQualifyingType(side), converter) : null;
            if (path != null && paths.stream().anyMatch(candidate -> candidate.endsWith(path.normalize())
                || path.normalize().endsWith(candidate)))
                return true;
        }
        return false;
    }

    private static void commit(IComparisonSession session, long id, String xml)
    {
        ensureInstalled("commit"); //$NON-NLS-1$
        if (!ComparisonUtils.isComparisonModelEditable(session.getStatus()))
            throw new IllegalStateException("План объединения уже нельзя изменить (" + session.getStatus() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        Object manager = Global.getField(session, "comparisonManager"); //$NON-NLS-1$
        if (manager == null)
            throw new IllegalStateException("Нет менеджера сравнения"); //$NON-NLS-1$
        String[] failure = { null };
        AbstractBmTask<Void> task = new AbstractBmTask<>("Комфорт: результат табличного объединения") //$NON-NLS-1$
        {
            @Override
            public Void execute(IBmTransaction transaction, IProgressMonitor monitor)
            {
                ComparisonContext context = new ComparisonContext(session);
                try
                {
                    context.setComparisonTransaction(transaction);
                    context.setDataSourceContext(new ComparisonDataSourceTransactionalContext(session, null));
                    ComparisonNode node = session.getNode(context, id);
                    if (!isSpreadsheet(node))
                        failure[0] = "Узел плана больше не табличный документ"; //$NON-NLS-1$
                    else if (!node.getMergeSettings().getAvailableMergeRules().contains(MergeRule.CUSTOM_MERGE))
                        failure[0] = "Сравнение построено до включения табличного объединения — откройте сравнение заново"; //$NON-NLS-1$
                    else
                    {
                        TextNodeCustomMergeSettings custom = CompareFactory.eINSTANCE.createTextNodeCustomMergeSettings();
                        custom.setCustomMergeResult(xml);
                        session.setCustomMergeSettings(id, custom, context);
                        if (!session.setMergeRule(id, MergeRule.CUSTOM_MERGE, context))
                            failure[0] = "EDT не приняла правило «Пользовательское объединение»"; //$NON-NLS-1$
                    }
                    return null;
                }
                finally
                {
                    // Транзакцией владеет задача: close() с транзакцией зафиксировал бы её и при ошибке
                    context.setComparisonTransaction(null);
                    context.close();
                }
            }
        };
        Global.invoke(manager, "runComparisonTreeBmModelTask", session, task); //$NON-NLS-1$
        if (failure[0] != null)
        {
            tempLog("plan.commit: узел=" + id + ", отказ: " + failure[0]); //$NON-NLS-1$ //$NON-NLS-2$
            throw new IllegalStateException(failure[0]);
        }
        boolean marked = session.setMustBeMerged(id, true, false);
        tempLog("plan.commit: узел=" + id + ", длина=" + xml.length() + ", отмечен=" + marked); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static boolean isSpreadsheet(Object object)
    {
        return object instanceof SolidResourceComparisonNode node
            && SPREADSHEET.equals(node.getMainQualifyingType()) && SPREADSHEET.equals(node.getOtherQualifyingType())
            && node.getMainSymlink() != null && node.getOtherSymlink() != null;
    }

    private static MergeRule rule(ComparisonNode node)
    {
        MergeSettings settings = node.getMergeSettings();
        return settings != null ? settings.getMergeRule() : null;
    }

    private static String customResult(ComparisonNode node)
    {
        MergeSettings settings = node.getMergeSettings();
        return settings != null && settings.getCustomSettings() instanceof TextNodeCustomMergeSettings text
            ? text.getCustomMergeResult() : null;
    }

    private static IQualifiedNameFilePathConverter converter(IComparisonParticipant original)
    {
        return Objects.requireNonNull((IQualifiedNameFilePathConverter) Global.getField(original, "filePathConverter"), //$NON-NLS-1$
            "filePathConverter"); //$NON-NLS-1$
    }

    private static IQualifiedNameFilePathConverter managerConverter(IComparisonSession session)
    {
        Object manager = session != null ? Global.getField(session, "comparisonManager") : null; //$NON-NLS-1$
        return manager != null
            && Global.getField(manager, "qualifiedNameFilePathConverter") instanceof IQualifiedNameFilePathConverter c //$NON-NLS-1$
                ? c : null;
    }

    private static Field findField(Class<?> type, String name)
    {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
            try
            {
                return current.getDeclaredField(name);
            }
            catch (NoSuchFieldException ignored)
            {
                // выше по иерархии
            }
        return null;
    }

    private static void tempLog(String text)
    {
        Global.tempLog(TOPIC, text);
    }
}
