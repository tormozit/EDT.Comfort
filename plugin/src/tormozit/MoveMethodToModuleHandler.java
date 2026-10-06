package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.common.CommandException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.DialogSettings;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.window.Window;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.ltk.core.refactoring.TextEditBasedChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import org.eclipse.ltk.core.refactoring.participants.RefactoringProcessor;
import org.eclipse.ltk.ui.refactoring.RefactoringWizard;
import org.eclipse.ltk.ui.refactoring.RefactoringWizardOpenOperation;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.ResourceUtil;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.refactoring.IRefactoringUpdateAcceptor;
import org.eclipse.xtext.ui.refactoring.IRenameRefactoringProvider;
import org.eclipse.xtext.ui.refactoring.impl.AbstractRenameProcessor;
import org.eclipse.xtext.ui.refactoring.impl.IRefactoringDocument;
import org.eclipse.xtext.ui.refactoring.ui.IRenameContextFactory;
import org.eclipse.xtext.ui.refactoring.ui.IRenameElementContext;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com._1c.g5.v8.dt.bsl.contextdef.IBslModuleContextDefService;
import com._1c.g5.v8.dt.bsl.model.ExplicitVariable;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.ModuleType;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Variable;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.form.model.EventHandler;
import com._1c.g5.v8.dt.form.model.EventHandlerContainer;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormCommandHandlerContainer;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.NamedElement;
import com._1c.g5.v8.dt.mcore.util.Environment;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;

/**
 * Команда «Рефакторинг → Переместить в модуль...» редактора модуля (issue 697). Каретка — на
 * заголовке процедуры или функции. Метод переносится в выбранный общий модуль или модуль
 * менеджера, а его вызовы переписываются с новым родителем: {@code Метод()} и
 * {@code СтарыйМодуль.Метод()} → {@code НовыйМодуль.Метод()}, внутри нового модуля — без
 * родителя.
 *
 * <p>Вызовы (в том числе локальные) ищет штатное переименование EDT: его обработчик запускается
 * с пробным именем, и из получившегося набора правок берутся только места имён — сами правки
 * переименования не применяются. Свой набор изменений собирается через штатный
 * {@link IRefactoringUpdateAcceptor} (он же различает открытый редактор и файл на диске) и
 * показывается в штатном окне «Рефакторинг».
 *
 * <p>Упоминания в строковых литералах и в свойствах объектов (обработчики подписок, заданий,
 * элементов форм) не меняются: участники переименования и полнотекстовый проход не запускаются.
 */
public class MoveMethodToModuleHandler extends AbstractHandler
{
    private static final String TITLE = "Переместить в модуль";
    /** Штатная команда редактора модуля «Иерархия вызовов» (com._1c.g5.v8.dt.bsl.ui/plugin.xml). */
    private static final String CALL_HIERARCHY_COMMAND_ID =
        "com._1c.g5.v8.dt.bsl.ui.editor.callhierarchy.CallHierarchy";
    /** Пробное имя для штатного переименования: нужно только чтобы оно прошло проверку имени. */
    private static final String PROBE_SUFFIX = "КомфортПеренос";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        Shell shell = HandlerUtil.getActiveShell(event);
        IEditorPart editorPart = HandlerUtil.getActiveEditor(event);
        BslXtextEditor editor = GetRef.getActiveBslEditor(editorPart);
        if (editor == null || !(editor.getSelectionProvider().getSelection() instanceof ITextSelection selection))
            return null;
        IXtextDocument document = editor.getDocument();
        if (document == null)
            return null;

        Source source;
        try
        {
            source = document.readOnly((IUnitOfWork<Source, XtextResource>) resource ->
                findSource(resource, editor, document.get(), selection.getOffset()));
        }
        catch (RuntimeException e)
        {
            // модель модуля не разобралась — ведём себя как при каретке вне заголовка
            source = null;
        }
        if (source == null)
        {
            MessageDialog.openInformation(shell, Global.withPluginWindowTitle(TITLE),
                "Установите курсор на заголовок процедуры или функции.");
            return null;
        }

        // Всё важное — одним вопросом до выбора модуля; у мастера страницы ввода нет.
        if (!source.formBindings.isEmpty() && !MessageDialog.openQuestion(shell, Global.withPluginWindowTitle(TITLE),
            "Метод «" + source.name + "» является обработчиком — " + String.join(", ", source.formBindings)
                + ". После перемещения эти привязки перестанут работать.\n\nПродолжить?"))
            return null;

        Map<String, CommonModule> commonModules = commonModules(source.file.getProject());
        TargetModule self = source.staticContext
            ? describeModule(source.file, source.header.russian, commonModules) : null;
        // Серверный метод общего модуля с «Вызовом сервера» мог вызываться с клиента из других модулей.
        if (source.serverOnly && self != null && self.serverCall)
            source.needsServerCall = true;
        // Серверный метод, который можно вызвать с клиента (общий модуль с «Вызовом сервера», модуль
        // формы): откуда его зовут на самом деле — показывает штатная «Иерархия вызовов».
        if (source.serverOnly && (source.formModule || self != null && self.serverCall))
            showCallHierarchy(editor, source);
        if (!source.contextRefs.isEmpty() && !source.instanceContext)
        {
            if (self == null)
                source.contextUnsupported = true;
            else if (!self.global)
                source.staticQualifier = self.prefix + ".";
        }

        TargetModule target = chooseTarget(shell, source, commonModules);
        if (target == null)
            return null;
        String prefix = target.global ? "" : target.prefix;

        MoveRefactoring refactoring = new MoveRefactoring(source, target, prefix);
        try
        {
            new RefactoringWizardOpenOperation(new MoveWizard(refactoring))
                .run(shell, Global.withPluginWindowTitle(TITLE));
        }
        catch (InterruptedException e)
        {
            // отмена во время проверки начальных условий — окно не открывалось
        }
        return null;
    }

    /**
     * Штатная команда «Иерархия вызовов» для перемещаемого метода. Команда берёт метод под кареткой,
     * поэтому каретка сначала ставится на его имя.
     */
    private static void showCallHierarchy(BslXtextEditor editor, Source source)
    {
        try
        {
            editor.selectAndReveal(source.header.nameOffset, 0);
            IHandlerService handlers = editor.getSite().getService(IHandlerService.class);
            if (handlers != null)
                handlers.executeCommand(CALL_HIERARCHY_COMMAND_ID, null);
        }
        catch (CommandException | RuntimeException e)
        {
            // иерархия — справочная: без неё выбор модуля всё равно возможен
        }
    }

    // =========================================================================
    // Перемещаемый метод
    // =========================================================================

    /** Перемещаемый метод: всё, что нужно дальше вне блокировки документа. */
    private static final class Source
    {
        IResourceServiceProvider services;
        IRenameElementContext context;
        URI uri;
        IFile file;
        String name;
        /** Текст модуля на момент вызова команды — к нему относятся все смещения. */
        String text;
        Header header;
        /** Начало переносимого текста: комментарий-описание над методом, если он есть. */
        int blockStart;
        /** Конец метода (после «КонецПроцедуры»/«КонецФункции»). */
        int nodeEnd;
        /** Удаляемый диапазон исходного модуля: метод с описанием, пустой строкой перед ним и переводом строки. */
        int deleteStart;
        int deleteEnd;
        /** Привязки формы, для которых метод — обработчик: после перемещения они перестанут работать. */
        List<String> formBindings = new ArrayList<>();
        /** Контексты компиляции метода с учётом модуля, директивы и {@code #Если}; {@code null} — неизвестны. */
        Environments environments;
        /** Метод компилируется только на сервере. */
        boolean serverOnly;
        /**
         * Серверный метод вызывается из клиентского кода — целевому общему модулю нужно свойство
         * «Вызов сервера»; окно выбора включает отбор по этой колонке.
         */
        boolean needsServerCall;
        /**
         * Серверный метод вызывается из клиентского метода этого же модуля — это известно точно,
         * поэтому отбор «Вызов сервера» в окне выбора снять нельзя.
         */
        boolean calledFromClient;

        /** Модуль с экземпляром (форма, объект, набор записей): контекст передаётся параметром. */
        boolean instanceContext;
        /** Модуль без экземпляра (общий модуль, менеджер): к его членам обращаются по имени модуля. */
        boolean staticContext;
        boolean formModule;
        /** Обращения тела метода к локальному контексту модуля: смещения имён. */
        List<Integer> contextRefs = new ArrayList<>();
        /** Рекурсивные вызовы в теле метода: смещения имён. */
        List<Integer> selfCalls = new ArrayList<>();
        /**
         * «Экспорт» у использованных методов и переменных исходного модуля: начало имени → место
         * вставки слова (за скобкой параметров, у переменной — за именем).
         */
        Map<Integer, Integer> exportEdits = new TreeMap<>();
        /** Начало метода (директива компиляции или ключевое слово). */
        int nodeStart;
        /** Занятые в методе имена (параметры и переменные), в нижнем регистре. */
        Set<String> localNames = new HashSet<>();
        /** Имя добавляемого первого параметра-контекста; {@code null} — параметр не нужен. */
        String contextParam;
        /** Родитель обращений к членам исходного модуля без экземпляра, с точкой; пусто — без родителя. */
        String staticQualifier = "";
        /** Обращения к локальному контексту есть, но переписать их нечем (модуль иного вида). */
        boolean contextUnsupported;

        /** Что подставляется перед обращением к локальному контексту в перенесённом теле. */
        String contextQualifier()
        {
            return contextParam != null ? contextParam + "." : staticQualifier;
        }
    }

    /**
     * Обращения тела метода к локальному контексту модуля: другим методам и переменным модуля,
     * а также к свойствам и методам его владельца (реквизиты и элементы формы, реквизиты объекта).
     * Членов владельца называет {@link IBslModuleContextDefService}; обращение считается локальным
     * по имени, если в самом методе такого имени нет (локальное имя перекрывает глобальное).
     */
    private static void analyzeContext(Module module, Method method, IResourceServiceProvider services,
        String text, Source source)
    {
        ModuleType type = module.getModuleType();
        source.formModule = type == ModuleType.FORM_MODULE;
        source.staticContext = type == ModuleType.COMMON_MODULE || type == ModuleType.MANAGER_MODULE;
        source.instanceContext = source.formModule || type == ModuleType.OBJECT_MODULE
            || type == ModuleType.RECORDSET_MODULE || type == ModuleType.VALUE_MANAGER_MODULE;

        for (TreeIterator<EObject> it = method.eAllContents(); it.hasNext();)
        {
            if (it.next() instanceof Variable variable && variable.getName() != null)
                source.localNames.add(lower(variable.getName()));
        }
        Map<String, Method> moduleMethods = new HashMap<>();
        for (Method other : module.allMethods())
        {
            if (other != null && other != method && other.getName() != null)
                moduleMethods.put(lower(other.getName()), other);
        }
        Map<String, ExplicitVariable> moduleVariables = new HashMap<>();
        for (TreeIterator<EObject> it = module.eAllContents(); it.hasNext();)
        {
            EObject element = it.next();
            if (element instanceof Method)
                it.prune();
            else if (element instanceof ExplicitVariable variable && variable.getName() != null)
                moduleVariables.put(lower(variable.getName()), variable);
        }
        Set<String> ownerMembers = new HashSet<>();
        if (type != ModuleType.COMMON_MODULE)
        {
            try
            {
                addMemberNames(services.get(IBslModuleContextDefService.class).getContextDef(module), ownerMembers);
                // реквизиты и элементы формы
                if (Global.invoke(module.getOwner(), "getFormContext") instanceof ContextDef formContext)
                    addMemberNames(formContext, ownerMembers);
            }
            catch (RuntimeException | LinkageError e)
            {
                // члены владельца неизвестны — остаются методы и переменные самого модуля
            }
        }

        String selfName = lower(method.getName());
        Set<String> exported = new HashSet<>();
        for (TreeIterator<EObject> it = method.eAllContents(); it.hasNext();)
        {
            if (!(it.next() instanceof StaticFeatureAccess access) || access.getName() == null)
                continue;
            String name = access.getName();
            String key = lower(name);
            ICompositeNode node = NodeModelUtils.findActualNodeFor(access);
            int offset = node != null ? node.getOffset() : -1;
            if (offset < 0 || !text.regionMatches(true, offset, name, 0, name.length()))
                continue;
            if (key.equals(selfName))
            {
                source.selfCalls.add(Integer.valueOf(offset));
                continue;
            }
            if (source.localNames.contains(key))
                continue;
            Method otherMethod = moduleMethods.get(key);
            ExplicitVariable moduleVariable = moduleVariables.get(key);
            if (otherMethod == null && moduleVariable == null && !ownerMembers.contains(key))
                continue;
            source.contextRefs.add(Integer.valueOf(offset));
            if (!exported.add(key))
                continue;
            if (otherMethod != null)
            {
                ICompositeNode methodNode = NodeModelUtils.findActualNodeFor(otherMethod);
                Header header = methodNode != null
                    ? Header.parse(text, methodNode.getOffset(), methodNode.getEndOffset()) : null;
                if (header != null && !header.export)
                    source.exportEdits.put(Integer.valueOf(header.nameOffset), Integer.valueOf(header.afterParameters));
            }
            else if (moduleVariable != null && !moduleVariable.isExport())
            {
                ICompositeNode variableNode = NodeModelUtils.findActualNodeFor(moduleVariable);
                int variableOffset = variableNode != null ? variableNode.getOffset() : -1;
                if (variableOffset >= 0 && text.regionMatches(true, variableOffset, name, 0, name.length()))
                    source.exportEdits.put(Integer.valueOf(variableOffset),
                        Integer.valueOf(variableOffset + name.length()));
            }
        }

        if (source.instanceContext && !source.contextRefs.isEmpty())
        {
            String base = source.formModule ? (source.header.russian ? "Форма" : "Form")
                : source.header.russian ? "Объект" : "Object";
            String candidate = base;
            for (int index = 0; source.localNames.contains(lower(candidate)) || lower(candidate).equals(selfName); index++)
                candidate = (source.header.russian ? "Контекст" : "Context") + (index == 0 ? "" : String.valueOf(index));
            source.contextParam = candidate;
        }
    }

    /** Есть ли в модуле вызов метода из чисто клиентского метода (типично — серверный метод формы). */
    private static boolean isCalledFromClient(Module module, Method method)
    {
        String name = method.getName();
        for (Method caller : module.allMethods())
        {
            Environments environments = caller != null && caller != method ? caller.environments() : null;
            if (environments == null || !environments.containsAny(Environments.ALL_CLIENTS)
                || environments.contains(Environment.SERVER))
                continue;
            for (TreeIterator<EObject> it = caller.eAllContents(); it.hasNext();)
            {
                if (it.next() instanceof StaticFeatureAccess access && name.equalsIgnoreCase(access.getName()))
                    return true;
            }
        }
        return false;
    }

    private static void addMemberNames(ContextDef context, Set<String> result)
    {
        if (context == null)
            return;
        List<EObject> members = new ArrayList<>(context.allProperties());
        members.addAll(context.allMethods());
        for (EObject member : members)
        {
            if (member instanceof NamedElement named && named.getName() != null)
                result.add(lower(named.getName()));
            if (Global.invoke(member, "getNameRu") instanceof String nameRu && !nameRu.isEmpty())
                result.add(lower(nameRu));
        }
    }

    private static String lower(String name)
    {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Привязки формы к методу её модуля: события формы и элементов (включая {@code extInfo}),
     * действия команд. Тот же обход, что у подсказки «Обработчик для» ({@code BslEditorHoverHook}).
     */
    private static void collectFormBindings(Module module, String methodName, List<String> result)
    {
        try
        {
            // Владелец модуля формы — содержимое формы (form.model.Form), а не BasicForm.
            if (!(module.getOwner() instanceof Form form))
                return;
            collectEventBindings(form, "формы", methodName, result);
            collectItemBindings(form.getItems(), methodName, result);
            for (FormCommand command : form.getFormCommands())
            {
                if (command.getAction() instanceof FormCommandHandlerContainer action && action.getHandler() != null
                    && methodName.equalsIgnoreCase(action.getHandler().getName()))
                    result.add("действие команды формы «" + command.getName() + "»");
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            // привязки формы недоступны — предупреждения не будет, перемещению это не мешает
        }
    }

    /**
     * Предопределённый обработчик события самого модуля ({@code ОбработкаЗаполнения} модуля объекта,
     * {@code ПриСозданииНаСервере} формы и т.п.): платформа вызывает его по имени. Признак — отметка
     * модели EDT {@code Method.isEvent()} либо имя из списка событий модуля.
     */
    private static void collectModuleEventBinding(Module module, Method method, IResourceServiceProvider services,
        List<String> result)
    {
        try
        {
            boolean event = method.isEvent();
            if (!event)
            {
                for (Event moduleEvent : services.get(IBslModuleContextDefService.class).getModuleEvents(module))
                {
                    if (moduleEvent != null && (method.getName().equalsIgnoreCase(moduleEvent.getName())
                        || method.getName().equalsIgnoreCase(moduleEvent.getNameRu())))
                        event = true;
                }
            }
            if (event)
                result.add("событие «" + method.getName() + "» модуля");
        }
        catch (RuntimeException | LinkageError e)
        {
            // список событий модуля недоступен — предупреждения не будет
        }
    }

    private static void collectItemBindings(List<FormItem> items, String methodName, List<String> result)
    {
        if (items == null)
            return;
        for (FormItem item : items)
        {
            collectEventBindings(item, "элемента «" + item.getName() + "»", methodName, result);
            if (item instanceof FormItemContainer nested)
                collectItemBindings(nested.getItems(), methodName, result);
        }
    }

    private static void collectEventBindings(Object owner, String ownerText, String methodName, List<String> result)
    {
        List<EventHandler> handlers = new ArrayList<>();
        if (owner instanceof EventHandlerContainer container)
            handlers.addAll(container.getHandlers());
        if (Global.invoke(owner, "getExtInfo") instanceof EventHandlerContainer extInfo)
            handlers.addAll(extInfo.getHandlers());
        for (EventHandler handler : handlers)
        {
            if (handler.getName() == null || !methodName.equalsIgnoreCase(handler.getName()))
                continue;
            Event event = handler.getEvent();
            String eventName = event == null ? ""
                : event.getNameRu() != null && !event.getNameRu().isEmpty() ? event.getNameRu()
                : event.getName() != null ? event.getName() : "";
            result.add("событие" + (eventName.isEmpty() ? "" : " «" + eventName + "»") + " " + ownerText);
        }
    }

    private static Source findSource(XtextResource resource, XtextEditor editor, String text, int caret)
    {
        if (resource.getContents().isEmpty() || !(resource.getContents().get(0) instanceof Module module))
            return null;
        URI uri = resource.getURI();
        if (uri == null || !uri.isPlatformResource())
            return null;
        for (Method method : module.allMethods())
        {
            ICompositeNode node = method != null ? NodeModelUtils.findActualNodeFor(method) : null;
            if (node == null)
                continue;
            int nodeStart = node.getOffset();
            int nodeEnd = node.getEndOffset();
            if (caret < lineStart(text, nodeStart) || caret > nodeEnd)
                continue;
            Header header = Header.parse(text, nodeStart, nodeEnd);
            // каретка в теле метода — не на заголовке
            if (header == null || caret > lineEnd(text, header.end))
                return null;

            IResourceServiceProvider services = resource.getResourceServiceProvider();
            IRenameContextFactory contextFactory = services.get(IRenameContextFactory.class);
            Source source = new Source();
            source.services = services;
            source.context = contextFactory.createRenameElementContext(method, editor,
                new TextSelection(header.nameOffset, header.nameLength), resource);
            if (source.context == null)
                return null;
            source.uri = uri;
            source.file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(uri.toPlatformString(true)));
            source.name = text.substring(header.nameOffset, header.nameOffset + header.nameLength);
            source.text = text;
            source.header = header;
            source.nodeStart = nodeStart;
            source.nodeEnd = nodeEnd;
            source.environments = method.environments();
            source.serverOnly = source.environments != null && source.environments.contains(Environment.SERVER)
                && !source.environments.containsAny(Environments.ALL_CLIENTS);
            source.calledFromClient = source.serverOnly && isCalledFromClient(module, method);
            source.needsServerCall = source.calledFromClient;
            collectFormBindings(module, source.name, source.formBindings);
            collectModuleEventBinding(module, method, services, source.formBindings);
            analyzeContext(module, method, services, text, source);

            int blockStart = lineStart(text, nodeStart);
            while (blockStart > 0)
            {
                int previous = lineStart(text, blockStart - 1);
                if (!text.substring(previous, blockStart).strip().startsWith("//"))
                    break;
                blockStart = previous;
            }
            source.blockStart = blockStart;
            source.deleteStart = blockStart;
            if (blockStart > 0)
            {
                int previous = lineStart(text, blockStart - 1);
                if (text.substring(previous, blockStart).isBlank())
                    source.deleteStart = previous;
            }
            int restEnd = lineEnd(text, nodeEnd);
            source.deleteEnd = text.substring(nodeEnd, restEnd).isBlank() ? nextLineStart(text, nodeEnd) : nodeEnd;
            return source;
        }
        return null;
    }

    /** Заголовок метода, разобранный по тексту: от ключевого слова до закрывающей скобки и «Экспорт». */
    private static final class Header
    {
        int nameOffset;
        int nameLength;
        /** Открывающая скобка параметров. */
        int open;
        /** Позиция сразу за закрывающей скобкой параметров — сюда вставляется «Экспорт». */
        int afterParameters;
        boolean export;
        /** Конец заголовка: за «Экспорт», а без него — за скобкой. */
        int end;
        /** Русский вариант встроенного языка — по ключевому слову метода. */
        boolean russian;

        static Header parse(String text, int start, int limit)
        {
            int pos = start;
            while (pos < limit)
            {
                char c = text.charAt(pos);
                if (Character.isWhitespace(c))
                {
                    pos++;
                    continue;
                }
                // директивы компиляции и аннотации, комментарии между ними
                if (c == '&' || c == '/' && pos + 1 < limit && text.charAt(pos + 1) == '/')
                {
                    pos = lineEnd(text, pos);
                    continue;
                }
                int wordEnd = identifierEnd(text, pos);
                if (wordEnd == pos)
                    return null;
                String word = text.substring(pos, wordEnd);
                if (word.equalsIgnoreCase("Асинх") || word.equalsIgnoreCase("Async"))
                {
                    pos = wordEnd;
                    continue;
                }
                boolean russian = word.equalsIgnoreCase("Процедура") || word.equalsIgnoreCase("Функция");
                if (!russian && !word.equalsIgnoreCase("Procedure") && !word.equalsIgnoreCase("Function"))
                    return null;
                int nameOffset = skipWhitespace(text, wordEnd);
                int nameEnd = identifierEnd(text, nameOffset);
                int open = skipWhitespace(text, nameEnd);
                if (nameEnd == nameOffset || open >= limit || text.charAt(open) != '(')
                    return null;
                int close = matchingParenthesis(text, open, limit);
                if (close < 0)
                    return null;
                Header header = new Header();
                header.nameOffset = nameOffset;
                header.nameLength = nameEnd - nameOffset;
                header.open = open;
                header.afterParameters = close + 1;
                header.russian = russian;
                int exportOffset = skipWhitespace(text, close + 1);
                int exportEnd = identifierEnd(text, exportOffset);
                String export = text.substring(exportOffset, exportEnd);
                header.export = export.equalsIgnoreCase("Экспорт") || export.equalsIgnoreCase("Export");
                header.end = header.export ? exportEnd : close + 1;
                return header;
            }
            return null;
        }

        /** Парная «)» для «(» в {@code open}; строки, даты и комментарии пропускаются. */
        private static int matchingParenthesis(String text, int open, int limit)
        {
            int depth = 0;
            for (int pos = open; pos < limit; pos++)
            {
                char c = text.charAt(pos);
                if (c == '"' || c == '\'')
                {
                    int closing = text.indexOf(c, pos + 1);
                    if (closing < 0 || closing >= limit)
                        return -1;
                    pos = closing;
                }
                else if (c == '/' && pos + 1 < limit && text.charAt(pos + 1) == '/')
                    pos = lineEnd(text, pos);
                else if (c == '(')
                    depth++;
                else if (c == ')' && --depth == 0)
                    return pos;
            }
            return -1;
        }
    }

    // =========================================================================
    // Целевой модуль
    // =========================================================================

    private static final class TargetModule
    {
        final IFile file;
        /** Родитель вызова: имя общего модуля или путь к менеджеру ({@code Справочники.Валюты}). */
        final String prefix;
        /** Глобальный общий модуль: его методы вызываются без имени модуля. */
        final boolean global;
        /** Контексты, в которых модуль компилируется; {@code null} — неизвестны. */
        final Environments environments;
        /** Свойство «Вызов сервера» общего модуля: его серверные методы можно вызывать с клиента. */
        final boolean serverCall;

        TargetModule(IFile file, String prefix, boolean global, Environments environments, boolean serverCall)
        {
            this.serverCall = serverCall;
            this.file = file;
            this.prefix = prefix;
            this.global = global;
            this.environments = environments;
        }
    }

    /**
     * Контексты компиляции общего модуля по его свойствам — как их считает сама EDT
     * ({@code BslOwnerComputerService.computeEnvironments}).
     */
    private static Environments commonModuleEnvironments(CommonModule module)
    {
        Environments result = Environments.EMPTY;
        if (module.isClientManagedApplication())
            result = result.add(Environments.MNG_CLIENTS);
        if (module.isClientOrdinaryApplication())
            result = result.add(Environment.CLIENT);
        if (module.isExternalConnection())
            result = result.add(Environment.EXTERNAL_CONN);
        if (module.isServer())
            result = result.add(Environments.ALL_SERVERS);
        return result;
    }

    /** Модуль менеджера компилируется на сервере, в толстом клиенте и во внешнем соединении. */
    private static Environments managerModuleEnvironments()
    {
        return Environments.ALL_SERVERS.add(Environment.CLIENT).add(Environment.EXTERNAL_CONN);
    }

    /** Общие модули проекта по имени в нижнем регистре; модель недоступна — пусто. */
    private static Map<String, CommonModule> commonModules(IProject project)
    {
        Map<String, CommonModule> result = new HashMap<>();
        try
        {
            IV8Project v8project = Global.getServiceByClass(IV8ProjectManager.class) instanceof IV8ProjectManager manager
                ? manager.getProject(project) : null;
            if (Global.invoke(v8project, "getConfiguration") instanceof Configuration configuration)
            {
                for (CommonModule module : configuration.getCommonModules())
                {
                    if (module != null && module.getName() != null)
                        result.put(lower(module.getName()), module);
                }
            }
        }
        catch (RuntimeException e)
        {
            // модель недоступна — модули останутся в списке без сведений о контекстах
        }
        return result;
    }

    /**
     * Выбор целевого модуля. Контексты метода — сумма контекстов исходного модуля, директивы
     * компиляции и {@code #Если} (готовое {@code environments()} модели EDT).
     *
     * <p>С клиента сервер вызвать можно, с сервера клиент — нельзя. Поэтому:
     * <ul>
     * <li>метод, компилируемый на сервере, переносится только в серверный модуль — такие модули
     * отбираются жёстко, клиентских в списке нет;</li>
     * <li>метод, компилируемый в клиенте, можно перенести и в клиентский модуль, и в серверный
     * с «Вызовом сервера» — отбор по колонке «Клиент» лишь включён при открытии и снимается;</li>
     * <li>отбор по «Вызову сервера» (серверный метод зовут с клиента) — тоже снимаемый.</li>
     * </ul>
     */
    private static TargetModule chooseTarget(Shell shell, Source source, Map<String, CommonModule> commonModules)
    {
        List<TargetModule> all = new ArrayList<>();
        try
        {
            collectTargets(source.file.getProject(), source, commonModules, all);
        }
        catch (CoreException e)
        {
            // папки проекта не читаются — список останется пустым, об этом скажет сообщение ниже
        }
        boolean serverMethod = source.environments != null && source.environments.contains(Environment.SERVER);
        List<TargetModule> targets = new ArrayList<>();
        for (TargetModule module : all)
        {
            if (!serverMethod || module.environments == null || module.environments.contains(Environment.SERVER))
                targets.add(module);
        }
        if (targets.isEmpty())
        {
            MessageDialog.openInformation(shell, Global.withPluginWindowTitle(TITLE), all.isEmpty()
                ? "В проекте нет других общих модулей и модулей менеджеров."
                : "В проекте нет других серверных модулей для метода «" + source.name + "».");
            return null;
        }
        targets.sort((a, b) -> a.prefix.compareToIgnoreCase(b.prefix));
        // Последний выбранный модуль для этого набора контекстов компиляции — если он есть в списке.
        IDialogSettings settings = lastTargetSettings();
        String settingsKey = lastTargetKey(source);
        String lastPrefix = settings.get(settingsKey);
        TargetModule last = null;
        for (TargetModule module : targets)
        {
            if (module.prefix.equalsIgnoreCase(lastPrefix))
                last = module;
        }
        Environments environments = source.environments;
        boolean[] columnFilters = new boolean[TargetDialog.COLUMN_COUNT];
        columnFilters[TargetDialog.CLIENT_COLUMN] =
            environments != null && environments.containsAny(Environments.MNG_CLIENTS);
        columnFilters[TargetDialog.SERVER_CALL_COLUMN] = source.needsServerCall;
        TargetDialog dialog = new TargetDialog(shell, targets, last, columnFilters, source.calledFromClient);
        if (dialog.open() != Window.OK || dialog.chosen == null)
            return null;
        settings.put(settingsKey, dialog.chosen.prefix);
        return dialog.chosen;
    }

    /**
     * Окно выбора целевого модуля: фильтр с историей и таблица «Модуль / Клиент / Сервер».
     * Штатный {@code ElementListSelectionDialog} не подошёл: его список заполняется по 10 строк
     * раз в 100 мс, а история фильтра у таких окон общая.
     */
    private static final class TargetDialog extends Dialog
    {
        // Не «…targetDialog»: под тем именем остались ширины колонок с текстом вместо галочек.
        private static final String SETTINGS_SECTION = "MoveMethodToModule.targetModules";
        private static final String KEY_COL_ORDER = "columnOrder";
        private static final String KEY_COL_FILL_MODE = "colFillMode";
        private static final String[] KEY_COL_WIDTHS =
            { "colModuleWidth", "colClientWidth", "colServerWidth", "colServerCallWidth" };
        private static final int DEFAULT_MODULE_COL_WIDTH = 320;
        private static final int MIN_COL_WIDTH = 30;
        /** Поля текста в шапке колонки (слева и справа вместе). */
        private static final int HEADER_TEXT_INSET_PX = 24;
        /** Отметка в колонках «Клиент» и «Сервер» — как у флагов в дереве элементов формы. */
        private static final String FLAG_MARK = "✓";
        static final int CLIENT_COLUMN = 1;
        static final int SERVER_CALL_COLUMN = 3;
        static final int COLUMN_COUNT = 4;

        private final List<TargetModule> targets;
        private final TargetModule initial;
        /** Колонки-галочки, по которым список открывается с отбором (пользователь может его снять). */
        private final boolean[] columnFilters;
        /** Были ли отборы по галочке в «Клиент» и «Вызов сервера» при прошлом оповещении — чтобы поймать снятие. */
        private boolean clientFiltered;
        private boolean serverCallFiltered;
        /** Отбор «Вызов сервера» снять нельзя: при снятии он возвращается. */
        private final boolean serverCallLocked;
        TargetModule chosen;

        private FilterInputBox filterInput;
        private TableViewer viewer;
        private FormTableInteraction interaction;
        private final TableColumn[] columns = new TableColumn[COLUMN_COUNT];
        private SmartMatcher matcher = new SmartMatcher("");
        /** Строка, выбранная пользователем: переживает фильтр и возвращается, когда снова видна. */
        private TargetModule current;
        /** Выделение меняет сам диалог (после фильтра) — {@link #current} не трогать. */
        private boolean selecting;

        TargetDialog(Shell parentShell, List<TargetModule> targets, TargetModule initial, boolean[] columnFilters,
            boolean serverCallLocked)
        {
            super(parentShell);
            this.serverCallLocked = serverCallLocked;
            this.targets = targets;
            this.initial = initial;
            this.columnFilters = columnFilters;
            setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
        }

        @Override
        protected void configureShell(Shell shell)
        {
            super.configureShell(shell);
            shell.setText(Global.withPluginWindowTitle(TITLE));
        }

        @Override
        protected Point getInitialSize()
        {
            return new Point(540, 480);
        }

        @Override
        protected boolean isResizable()
        {
            return true;
        }

        @Override
        protected Control createDialogArea(Composite parent)
        {
            Composite area = (Composite) super.createDialogArea(parent);
            area.setLayout(new GridLayout(1, false));

            filterInput = FilterInputBox.forMoveMethodTarget(area, this::applyFilter);

            Composite tableStack = new Composite(area, SWT.NONE);
            tableStack.setLayout(null);
            tableStack.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            Composite columnHost = new Composite(tableStack, SWT.NONE);
            TableColumnLayout columnLayout = new TableColumnLayout(true);
            columnHost.setLayout(columnLayout);

            viewer = new TableViewer(columnHost, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
            Table table = viewer.getTable();
            table.setHeaderVisible(true);
            ThemeAwareColors.applyGridLines(table);

            IDialogSettings settings = dialogSettings();
            boolean hasSavedColumnWidths =
                FormTableColumnState.hasSavedColumnWidths(settings, KEY_COL_FILL_MODE, KEY_COL_WIDTHS);
            String[] titles = { "Модуль", "Клиент", "Сервер", "Вызов сервера" };
            for (int index = 0; index < columns.length; index++)
            {
                TableViewerColumn viewerColumn = new TableViewerColumn(viewer, index == 0 ? SWT.NONE : SWT.CENTER);
                columns[index] = viewerColumn.getColumn();
                columns[index].setText(titles[index]);
                if (index == 1)
                    columns[index].setToolTipText(TooltipText.wrap(table,
                        "Модуль компилируется в клиенте управляемого приложения."));
                if (index == 3)
                    columns[index].setToolTipText(TooltipText.wrap(table,
                        "Свойство «Вызов сервера» общего модуля. Нужно, если серверный метод вызывается из клиентских."));
                int columnIndex = index;
                if (index == 0)
                    viewerColumn.setLabelProvider(new SelectionAwareStyledCellLabelProvider(new ModuleLabelProvider()));
                else
                    viewerColumn.setLabelProvider(new ColumnLabelProvider()
                    {
                        @Override
                        public String getText(Object element)
                        {
                            return columnText(element, columnIndex);
                        }
                    });
                // «Клиент» и «Сервер» по умолчанию — ровно под текст шапки: в ячейках только галочка
                int defaultWidth = index == 0 ? DEFAULT_MODULE_COL_WIDTH : headerTextWidth(table, titles[index]);
                columnLayout.setColumnData(columns[index], new ColumnPixelData(FormTableColumnState.readWidth(settings,
                    KEY_COL_WIDTHS[index], defaultWidth, MIN_COL_WIDTH), true, true));
            }
            FormTableColumnState.loadOrder(settings, KEY_COL_ORDER, table);

            viewer.setContentProvider(ArrayContentProvider.getInstance());
            viewer.addFilter(new ViewerFilter()
            {
                @Override
                public boolean select(Viewer filtered, Object parentElement, Object element)
                {
                    return matcher.isEmpty || element instanceof TargetModule module && matcher.matches(module.prefix);
                }
            });
            viewer.setInput(targets);
            viewer.addSelectionChangedListener(event ->
            {
                if (!selecting && event.getStructuredSelection().getFirstElement() instanceof TargetModule module)
                    current = module;
            });

            interaction = new FormTableInteraction(table, viewer,
                (item, column) -> columnText(item.getData(), modelColumn(column)));
            interaction.setOwnerDrawColumns(columns[0]);
            interaction.setFilterTextResolver((element, column) -> columnText(element, modelColumn(column)));
            interaction.install(hasSavedColumnWidths);
            for (int index = 0; index < columnFilters.length; index++)
            {
                if (columnFilters[index])
                    interaction.applyColumnFilterValue(index, FLAG_MARK);
            }
            // У общего модуля сочетание «Клиент» = да и «Вызов сервера» = да запрещено платформой,
            // поэтому отборы по галочке в этих колонках взаимоисключающие: включение одного снимает
            // другой (отбор по пустому значению сюда не относится). Клиентский метод можно перенести
            // в клиентский модуль либо в серверный с «Вызовом сервера» — для него снятие одного
            // отбора по галочке включает другой.
            boolean clientMethod = columnFilters[CLIENT_COLUMN];
            clientFiltered = isMarkFiltered(CLIENT_COLUMN);
            serverCallFiltered = isMarkFiltered(SERVER_CALL_COLUMN);
            interaction.setColumnFilterChangeListener(() ->
            {
                boolean client = isMarkFiltered(CLIENT_COLUMN);
                boolean serverCall = isMarkFiltered(SERVER_CALL_COLUMN);
                // включён второй отбор — снять тот, что был раньше
                int clear = !(client && serverCall) ? -1
                    : clientFiltered || serverCallLocked ? CLIENT_COLUMN : SERVER_CALL_COLUMN;
                int enable = !clientMethod || client || serverCall ? -1
                    : clientFiltered ? SERVER_CALL_COLUMN : serverCallFiltered ? CLIENT_COLUMN : -1;
                // серверный метод зовут с клиента в этом же модуле — без «Вызова сервера» нельзя
                boolean restore = serverCallLocked && !serverCall;
                clientFiltered = client;
                serverCallFiltered = serverCall;
                if (clear < 0 && enable < 0 && !restore)
                    return;
                table.getDisplay().asyncExec(() ->
                {
                    if (table.isDisposed())
                        return;
                    boolean clientNow = isMarkFiltered(CLIENT_COLUMN);
                    boolean serverCallNow = isMarkFiltered(SERVER_CALL_COLUMN);
                    if (restore && !serverCallNow)
                        interaction.applyColumnFilterValue(SERVER_CALL_COLUMN, FLAG_MARK);
                    else if (clear >= 0 && clientNow && serverCallNow)
                        interaction.clearColumnFilter(clear);
                    else if (enable >= 0 && !clientNow && !serverCallNow)
                        interaction.applyColumnFilterValue(enable, FLAG_MARK);
                });
            });

            current = initial;
            selectCurrentOrFirst();

            table.addListener(SWT.MouseDoubleClick, event -> okPressed());
            table.addListener(SWT.KeyDown, event ->
            {
                if (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)
                    okPressed();
            });
            FilterInputBoxListNavigation.installTableOpenOnEnter(filterInput.inputControl(), table, null,
                this::okPressed);
            filterInput.scheduleFocusWhenReady();
            return area;
        }

        @Override
        protected void okPressed()
        {
            if (!(viewer.getStructuredSelection().getFirstElement() instanceof TargetModule module))
                return;
            chosen = module;
            super.okPressed();
        }

        @Override
        public boolean close()
        {
            if (viewer != null && !viewer.getTable().isDisposed())
                FormTableColumnState.saveOrderAndWidths(dialogSettings(), KEY_COL_ORDER, KEY_COL_FILL_MODE,
                    interaction != null && interaction.isColumnsExactFill(), KEY_COL_WIDTHS, columns,
                    viewer.getTable());
            return super.close();
        }

        private void applyFilter()
        {
            if (viewer == null || viewer.getTable().isDisposed())
                return;
            matcher = new SmartMatcher(filterInput.getText().trim());
            viewer.refresh();
            selectCurrentOrFirst();
        }

        /** Выбранная пользователем строка, если фильтр её не скрыл; иначе первая видимая. */
        private void selectCurrentOrFirst()
        {
            Table table = viewer.getTable();
            selecting = true;
            try
            {
                TableItem target = null;
                for (TableItem item : table.getItems())
                {
                    if (item.getData() == current)
                        target = item;
                }
                if (target == null && table.getItemCount() > 0)
                    target = table.getItem(0);
                if (target == null)
                {
                    viewer.setSelection(StructuredSelection.EMPTY);
                    return;
                }
                viewer.setSelection(new StructuredSelection(target.getData()), true);
                interaction.selectCell(target, table.indexOf(columns[0]));
            }
            finally
            {
                selecting = false;
            }
        }

        /** Наложен ли на колонку отбор именно по галочке. */
        private boolean isMarkFiltered(int column)
        {
            return FLAG_MARK.equals(interaction.columnFilterValue(column));
        }

        private static int headerTextWidth(Table table, String title)
        {
            GC gc = new GC(table);
            try
            {
                return gc.textExtent(title).x + HEADER_TEXT_INSET_PX;
            }
            finally
            {
                gc.dispose();
            }
        }

        /** Индекс колонки таблицы → номер колонки модели (порядок колонок меняется перетаскиванием). */
        private int modelColumn(int tableColumn)
        {
            Table table = viewer.getTable();
            if (tableColumn < 0 || tableColumn >= table.getColumnCount())
                return -1;
            TableColumn column = table.getColumn(tableColumn);
            for (int index = 0; index < columns.length; index++)
            {
                if (columns[index] == column)
                    return index;
            }
            return -1;
        }

        private static String columnText(Object element, int column)
        {
            if (!(element instanceof TargetModule module))
                return "";
            Environments environments = module.environments;
            return switch (column)
            {
                case 0 -> module.prefix;
                // «Клиент» — управляемый клиент; толстый клиент обычного приложения галочкой не отмечается
                case 1 -> environments != null && environments.containsAny(Environments.MNG_CLIENTS) ? FLAG_MARK : "";
                case 2 -> environments != null && environments.contains(Environment.SERVER) ? FLAG_MARK : "";
                case 3 -> module.serverCall ? FLAG_MARK : "";
                default -> "";
            };
        }

        private static IDialogSettings dialogSettings()
        {
            return DialogSettings.getOrCreateSection(Activator.getDefault().getDialogSettings(), SETTINGS_SECTION);
        }

        /** Имя модуля с подсветкой вхождений фильтра. */
        private final class ModuleLabelProvider extends LabelProvider implements IStyledLabelProvider
        {
            @Override
            public StyledString getStyledText(Object element)
            {
                String text = columnText(element, 0);
                StyledString styled = new StyledString();
                int position = 0;
                if (!matcher.isEmpty)
                {
                    for (SmartMatcher.HighlightRange range : matcher.getHighlightRanges(text))
                    {
                        if (range.offset < position || range.offset + range.length > text.length())
                            continue;
                        styled.append(text.substring(position, range.offset));
                        position = range.offset + range.length;
                        styled.append(text.substring(range.offset, position), SmartMatchHighlight.styler(viewer.getTable()));
                    }
                }
                styled.append(text.substring(position));
                return styled;
            }
        }
    }

    private static IDialogSettings lastTargetSettings()
    {
        return DialogSettings.getOrCreateSection(Activator.getDefault().getDialogSettings(),
            "MoveMethodToModule.lastTarget");
    }

    /** Ключ запоминания: проект и набор контекстов компиляции метода (имена по алфавиту). */
    private static String lastTargetKey(Source source)
    {
        List<String> names = new ArrayList<>();
        if (source.environments != null)
        {
            for (Environment environment : source.environments.toArray())
                names.add(environment.name());
        }
        names.sort(null);
        return source.file.getProject().getName() + "|" + String.join(",", names)
            + (source.needsServerCall ? "|serverCall" : "");
    }

    /**
     * Общие модули и модули менеджеров проекта, кроме исходного. Родитель вызова — по пути файла,
     * тем же преобразованием, что у ссылок на строки модулей ({@link GetRef#pathToModuleRef}).
     * В английском варианте языка имя коллекции менеджеров совпадает с именем папки типа
     * ({@code Catalogs}, {@code Documents}).
     */
    private static void collectTargets(IProject project, Source source, Map<String, CommonModule> commonModules,
        List<TargetModule> targets) throws CoreException
    {
        IFolder src = project.getFolder("src");
        if (!src.exists())
            return;
        for (IResource type : src.members())
        {
            if (!(type instanceof IFolder typeFolder))
                continue;
            boolean common = COMMON_MODULES_FOLDER.equals(typeFolder.getName());
            for (IResource object : typeFolder.members())
            {
                if (!(object instanceof IFolder objectFolder))
                    continue;
                TargetModule module = describeModule(
                    objectFolder.getFile(common ? COMMON_MODULE_FILE : MANAGER_MODULE_FILE), source.header.russian,
                    commonModules);
                if (module != null && !module.file.equals(source.file))
                    targets.add(module);
            }
        }
    }

    private static final String COMMON_MODULES_FOLDER = "CommonModules";
    private static final String COMMON_MODULE_FILE = "Module.bsl";
    private static final String MANAGER_MODULE_FILE = "ManagerModule.bsl";

    /** Общий модуль или модуль менеджера по файлу; иной модуль или несуществующий файл — {@code null}. */
    private static TargetModule describeModule(IFile file, boolean russian, Map<String, CommonModule> commonModules)
    {
        if (file == null || !file.exists() || !(file.getParent() instanceof IFolder objectFolder)
            || !(objectFolder.getParent() instanceof IFolder typeFolder))
            return null;
        boolean common = COMMON_MODULES_FOLDER.equals(typeFolder.getName());
        if (!file.getName().equals(common ? COMMON_MODULE_FILE : MANAGER_MODULE_FILE))
            return null;
        if (common)
        {
            CommonModule module = commonModules.get(lower(objectFolder.getName()));
            return new TargetModule(file, objectFolder.getName(), module != null && module.isGlobal(),
                module != null ? commonModuleEnvironments(module) : null, module != null && module.isServerCall());
        }
        GetRef.ModuleRef ref = GetRef.pathToModuleRef(file.getProjectRelativePath().toString());
        String prefix = ref != null ? MdTypeMapping.directModuleName(ref.modulePath) : "";
        // тип без известной коллекции менеджеров — вызвать такой модуль по имени нельзя
        if (prefix.isEmpty() || MdTypeMapping.ruPluralToRu(MdTypeMapping.firstSegment(prefix)) == null)
            return null;
        return new TargetModule(file, russian ? prefix : typeFolder.getName() + "." + objectFolder.getName(), false,
            managerModuleEnvironments(), false);
    }

    // =========================================================================
    // Рефакторинг
    // =========================================================================

    private static final class MoveRefactoring extends Refactoring
    {
        private final Source source;
        private final TargetModule target;
        /** Родитель вызова; пусто — вызывать без родителя (глобальный общий модуль). */
        private final String prefix;
        private IRefactoringUpdateAcceptor acceptor;

        MoveRefactoring(Source source, TargetModule target, String prefix)
        {
            this.source = source;
            this.target = target;
            this.prefix = prefix;
        }

        @Override
        public String getName()
        {
            return "Переместить «" + source.name + "» в модуль «" + target.prefix + "»";
        }

        @Override
        public RefactoringStatus checkInitialConditions(IProgressMonitor pm)
        {
            return new RefactoringStatus();
        }

        @Override
        public RefactoringStatus checkFinalConditions(IProgressMonitor pm) throws CoreException
        {
            acceptor = null;
            RefactoringStatus status = new RefactoringStatus();
            try
            {
                collectEdits(SubMonitor.convert(pm, "Поиск вызовов метода", 100), status);
            }
            catch (OperationCanceledException e)
            {
                throw e;
            }
            catch (RuntimeException e)
            {
                status.addFatalError("Не удалось подготовить перемещение: " + e);
            }
            return status;
        }

        @Override
        public Change createChange(IProgressMonitor pm) throws CoreException
        {
            return acceptor != null ? acceptor.createCompositeChange(getName(), pm) : new CompositeChange(getName());
        }

        private void collectEdits(SubMonitor monitor, RefactoringStatus status) throws CoreException
        {
            IRefactoringUpdateAcceptor updates = source.services.get(IRefactoringUpdateAcceptor.class);
            IRenameRefactoringProvider provider = source.services.get(IRenameRefactoringProvider.class);
            URI targetUri = URI.createPlatformResourceURI(target.file.getFullPath().toString(), true);
            String targetText = contents(updates, targetUri);
            if (targetText == null)
            {
                status.addFatalError("Не удалось прочитать модуль «" + target.prefix + "».");
                return;
            }
            if (GetRef.findMethodDeclarationLine(new Document(targetText), source.name) >= 0)
            {
                status.addFatalError("В модуле «" + target.prefix + "» уже есть метод «" + source.name + "».");
                return;
            }
            if (!source.text.equals(contents(updates, source.uri)))
            {
                status.addFatalError("Текст исходного модуля изменился. Повторите команду.");
                return;
            }

            // Места вызовов — из штатного переименования с пробным именем.
            ProcessorBasedRefactoring rename = provider.getRenameRefactoring(source.context);
            RefactoringProcessor processor = rename != null ? rename.getProcessor() : null;
            if (!(processor instanceof AbstractRenameProcessor renameProcessor))
            {
                status.addFatalError("Не удалось подготовить поиск вызовов метода.");
                return;
            }
            renameProcessor.setNewName(source.name + PROBE_SUFFIX);
            RefactoringStatus renameStatus = rename.checkAllConditions(monitor.split(70));
            if (renameStatus.hasFatalError())
            {
                status.merge(renameStatus);
                return;
            }
            Map<URI, TreeMap<Integer, Integer>> occurrences = new LinkedHashMap<>();
            collectOccurrences(processor.createChange(monitor.split(20)), occurrences);

            int rewritten = 0;
            for (Map.Entry<URI, TreeMap<Integer, Integer>> entry : occurrences.entrySet())
            {
                URI uri = entry.getKey();
                boolean inSource = uri.equals(source.uri);
                String text = inSource ? source.text : uri.equals(targetUri) ? targetText : contents(updates, uri);
                if (text == null)
                    continue;
                // внутри целевого модуля и для глобального модуля вызов остаётся без родителя
                String qualifier = uri.equals(targetUri) || prefix.isEmpty() ? "" : prefix + ".";
                for (Map.Entry<Integer, Integer> occurrence : entry.getValue().entrySet())
                {
                    // сам перемещаемый метод: объявление и рекурсивные вызовы уезжают как есть
                    if (inSource && occurrence.getKey() >= source.deleteStart && occurrence.getKey() < source.deleteEnd)
                        continue;
                    int offset = nameOffset(text, source.name, occurrence.getKey(), occurrence.getValue());
                    String place = uri.lastSegment() + ", строка " + lineNumber(text, occurrence.getKey());
                    if (offset < 0 || insideStringLiteral(text, offset))
                    {
                        status.addWarning("Упоминание метода не изменено (" + uri.trimSegments(1).lastSegment()
                            + "/" + place + ").");
                        continue;
                    }
                    int qualifierStart = qualifierStart(text, offset);
                    if (qualifierStart < 0)
                    {
                        status.addWarning("Вызов не изменён: перед именем метода сложное выражение ("
                            + uri.trimSegments(1).lastSegment() + "/" + place + ").");
                        continue;
                    }
                    // Одна правка-замена на вызов, от прежнего родителя до имени (а с параметром-контекстом —
                    // до открывающей скобки): у вставки нулевой длины таблица предпросмотра не считает
                    // «Метод», «Родитель» и «Текст» (RefactoringPreviewHook.PreviewRow.needsContext).
                    int nameEnd = offset + source.name.length();
                    int end = nameEnd;
                    String replacement = qualifier + text.substring(offset, nameEnd);
                    if (source.contextParam != null)
                    {
                        // контекст вызова: прежний родитель, а у локального вызова — сам модуль
                        String context = qualifierStart < offset ? text.substring(qualifierStart, offset).strip()
                            : source.header.russian ? "ЭтотОбъект." : "ThisObject.";
                        context = context.substring(0, context.length() - 1).strip();
                        int open = argumentsOpen(text, nameEnd);
                        if (open >= 0)
                        {
                            end = open + 1;
                            replacement += text.substring(nameEnd, end) + firstArgument(text, open, context);
                        }
                        else
                            status.addWarning("В вызов не добавлен параметр «" + source.contextParam + "» ("
                                + uri.trimSegments(1).lastSegment() + "/" + place + ").");
                    }
                    if (!replacement.equals(text.substring(qualifierStart, end)))
                        updates.accept(uri, new ReplaceEdit(qualifierStart, end - qualifierStart, replacement));
                    rewritten++;
                }
            }

            if (source.contextUnsupported)
                status.addWarning("Обращения метода к методам и переменным исходного модуля не переписаны: "
                    + "модуль такого вида нельзя вызвать из другого модуля.");
            else
            {
                // замена от имени до места вставки — чтобы строка таблицы относилась к этому методу
                String exportWord = source.header.russian ? " Экспорт" : " Export";
                for (Map.Entry<Integer, Integer> export : source.exportEdits.entrySet())
                {
                    int start = export.getKey().intValue();
                    int end = export.getValue().intValue();
                    updates.accept(source.uri,
                        new ReplaceEdit(start, end - start, source.text.substring(start, end) + exportWord));
                }
            }
            // Описание и пустая строка над методом удаляются отдельной правкой: строка таблицы про сам
            // метод должна начинаться в нём, иначе колонка «Метод» у неё пуста.
            int methodStart = lineStart(source.text, source.nodeStart);
            if (source.deleteStart < methodStart)
                updates.accept(source.uri, new DeleteEdit(source.deleteStart, methodStart - source.deleteStart));
            updates.accept(source.uri, new DeleteEdit(methodStart, source.deleteEnd - methodStart));
            updates.accept(targetUri, insertion(targetText));
            if (rewritten == 0)
                status.addInfo("Вызовы метода «" + source.name + "» не найдены.");
            acceptor = updates;
        }

        /** Вставка метода в целевой модуль: после последнего метода, а без методов — в конец текста. */
        private InsertEdit insertion(String targetText)
        {
            String delimiter = targetText.contains("\r\n") || !targetText.contains("\n")
                && source.text.contains("\r\n") ? "\r\n" : "\n";
            // Вставки в переносимый текст: смещение в исходном модуле → текст; применяются с конца.
            TreeMap<Integer, String> inserts = new TreeMap<>();
            if (!source.header.export)
                inserts.put(Integer.valueOf(source.header.afterParameters), source.header.russian ? " Экспорт" : " Export");
            if (source.contextParam != null)
            {
                inserts.put(Integer.valueOf(source.header.open + 1),
                    firstArgument(source.text, source.header.open, source.contextParam));
                for (Integer call : source.selfCalls)
                {
                    int open = argumentsOpen(source.text, call.intValue() + source.name.length());
                    if (open >= 0)
                        inserts.put(Integer.valueOf(open + 1), firstArgument(source.text, open, source.contextParam));
                }
            }
            String contextQualifier = source.contextUnsupported ? "" : source.contextQualifier();
            if (!contextQualifier.isEmpty())
            {
                // обращение, стоящее первым аргументом рекурсивного вызова, идёт после вставленного параметра
                for (Integer reference : source.contextRefs)
                    inserts.merge(reference, contextQualifier, String::concat);
            }
            StringBuilder builder = new StringBuilder(source.text.substring(source.blockStart, source.nodeEnd));
            for (Map.Entry<Integer, String> insert : inserts.descendingMap().entrySet())
                builder.insert(insert.getKey().intValue() - source.blockStart, insert.getValue());
            String moved = builder.toString();
            int afterLastMethod = -1;
            for (int pos = 0; pos < targetText.length();)
            {
                int end = lineEnd(targetText, pos);
                String line = targetText.substring(pos, end).strip().toLowerCase(Locale.ROOT);
                if (line.startsWith("конецпроцедуры") || line.startsWith("конецфункции")
                    || line.startsWith("endprocedure") || line.startsWith("endfunction"))
                    afterLastMethod = end;
                pos = nextLineStart(targetText, pos);
            }
            if (afterLastMethod >= 0)
                return new InsertEdit(afterLastMethod, delimiter + delimiter + moved);
            String lead = targetText.isBlank() ? ""
                : targetText.endsWith("\n") ? delimiter : delimiter + delimiter;
            return new InsertEdit(targetText.length(), lead + moved + delimiter);
        }
    }

    private static String contents(IRefactoringUpdateAcceptor updates, URI uri)
    {
        IRefactoringDocument document = updates.getDocument(uri);
        return document != null ? document.getOriginalContents() : null;
    }

    /** Места правок штатного переименования: модуль → (смещение → длина). */
    private static void collectOccurrences(Change change, Map<URI, TreeMap<Integer, Integer>> result)
    {
        if (change instanceof CompositeChange composite)
        {
            for (Change child : composite.getChildren())
                collectOccurrences(child, result);
            return;
        }
        if (!(change instanceof TextEditBasedChange))
            return;
        IFile file = fileOfChange(change);
        Object edit = change instanceof TextChange textChange ? textChange.getEdit() : Global.invoke(change, "getEdit");
        if (file == null || !(edit instanceof TextEdit root))
            return;
        URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        collectLeafEdits(root, result.computeIfAbsent(uri, key -> new TreeMap<>()));
    }

    private static void collectLeafEdits(TextEdit edit, TreeMap<Integer, Integer> result)
    {
        if (edit instanceof MultiTextEdit)
        {
            for (TextEdit child : edit.getChildren())
                collectLeafEdits(child, result);
            return;
        }
        result.put(edit.getOffset(), edit.getLength());
    }

    /** Модуль изменения: файл — у файловых изменений, редактор — у изменений открытого документа. */
    private static IFile fileOfChange(Change change)
    {
        Object modified = change.getModifiedElement();
        if (modified instanceof IFile file)
            return file;
        if (modified instanceof XtextEditor xtextEditor && xtextEditor.getResource() instanceof IFile file)
            return file;
        if (modified instanceof IEditorPart part)
            return ResourceUtil.getFile(part.getEditorInput());
        if (Global.invoke(modified, "getFile_") instanceof IFile handlyFile)
            return handlyFile;
        return change instanceof TextFileChange textFileChange ? textFileChange.getFile() : null;
    }

    // =========================================================================
    // Разбор текста
    // =========================================================================

    /**
     * Смещение имени метода в правке переименования: правка покрывает само имя либо обращение,
     * оканчивающееся им ({@code Модуль.Метод}). Иное — {@code -1}.
     */
    private static int nameOffset(String text, String name, int offset, int length)
    {
        int end = offset + length;
        if (offset < 0 || end > text.length() || length < name.length())
            return -1;
        int start = end - name.length();
        if (!text.regionMatches(true, start, name, 0, name.length()))
            return -1;
        return start == offset || text.charAt(start - 1) == '.' ? start : -1;
    }

    /**
     * Начало родителя перед именем: цепочка идентификаторов через точку ({@code Справочники.Валюты.}).
     * Родителя нет — возвращается {@code nameOffset}; перед точкой не идентификатор (вызов, индекс) —
     * {@code -1}.
     */
    private static int qualifierStart(String text, int nameOffset)
    {
        int pos = skipWhitespaceBack(text, nameOffset);
        if (pos == 0 || text.charAt(pos - 1) != '.')
            return nameOffset;
        while (true)
        {
            int end = skipWhitespaceBack(text, pos - 1);
            int start = end;
            while (start > 0 && isIdentifierPart(text.charAt(start - 1)))
                start--;
            if (start == end)
                return -1;
            pos = skipWhitespaceBack(text, start);
            if (pos == 0 || text.charAt(pos - 1) != '.')
                return start;
        }
    }

    /** Открывающая скобка аргументов за именем метода; скобки нет — {@code -1}. */
    private static int argumentsOpen(String text, int nameEnd)
    {
        int open = skipWhitespace(text, nameEnd);
        return open < text.length() && text.charAt(open) == '(' ? open : -1;
    }

    /** Текст нового первого аргумента (параметра) для списка, открытого скобкой {@code open}. */
    private static String firstArgument(String text, int open, String value)
    {
        int next = skipWhitespace(text, open + 1);
        return next < text.length() && text.charAt(next) == ')' ? value : value + ", ";
    }

    /** Смещение внутри строкового литерала: нечётное число кавычек от начала строки (с учётом «|»). */
    private static boolean insideStringLiteral(String text, int offset)
    {
        int start = lineStart(text, offset);
        boolean inside = text.substring(start, offset).stripLeading().startsWith("|");
        for (int pos = start; pos < offset; pos++)
        {
            if (text.charAt(pos) == '"')
                inside = !inside;
        }
        return inside;
    }

    private static boolean isIdentifierPart(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static int identifierEnd(String text, int offset)
    {
        int pos = offset;
        while (pos < text.length() && isIdentifierPart(text.charAt(pos)))
            pos++;
        return pos;
    }

    private static int skipWhitespace(String text, int offset)
    {
        int pos = offset;
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos)))
            pos++;
        return pos;
    }

    private static int skipWhitespaceBack(String text, int offset)
    {
        int pos = offset;
        while (pos > 0 && Character.isWhitespace(text.charAt(pos - 1)))
            pos--;
        return pos;
    }

    private static int lineStart(String text, int offset)
    {
        int pos = Math.min(offset, text.length());
        while (pos > 0 && text.charAt(pos - 1) != '\n')
            pos--;
        return pos;
    }

    /** Конец строки без разделителя. */
    private static int lineEnd(String text, int offset)
    {
        int pos = offset;
        while (pos < text.length() && text.charAt(pos) != '\n' && text.charAt(pos) != '\r')
            pos++;
        return pos;
    }

    private static int nextLineStart(String text, int offset)
    {
        int pos = lineEnd(text, offset);
        if (pos < text.length() && text.charAt(pos) == '\r')
            pos++;
        if (pos < text.length() && text.charAt(pos) == '\n')
            pos++;
        return pos;
    }

    private static int lineNumber(String text, int offset)
    {
        int line = 1;
        for (int pos = 0; pos < offset && pos < text.length(); pos++)
        {
            if (text.charAt(pos) == '\n')
                line++;
        }
        return line;
    }

    // =========================================================================
    // Окно
    // =========================================================================

    private static final class MoveWizard extends RefactoringWizard
    {
        MoveWizard(Refactoring refactoring)
        {
            super(refactoring, WIZARD_BASED_USER_INTERFACE | PREVIEW_EXPAND_FIRST_NODE);
            setWindowTitle(Global.withPluginWindowTitle(TITLE));
            setDefaultPageTitle(TITLE);
        }

        @Override
        protected void addUserInputPages()
        {
            // Страниц ввода нет: мастер сразу ищет вызовы и открывается на предпросмотре изменений.
        }
    }
}
