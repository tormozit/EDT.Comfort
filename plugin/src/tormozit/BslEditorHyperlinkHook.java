package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListenerWithChecks;
import org.eclipse.core.commands.IHandler;
import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetectorExtension;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetectorExtension2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.xtext.naming.IQualifiedNameConverter;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.IScopeProvider;
import org.eclipse.xtext.ui.editor.hyperlinking.XtextHyperlink;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Statement;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.DerivedProperty;
import com._1c.g5.v8.dt.mcore.FieldSource;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.SourceType;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.md.resource.MdTypeUtil;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.EditorPageDescriptor;
import com._1c.g5.v8.dt.md.ui.editor.base.IEditorPagesRegistry;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicRegister;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.ui.util.OpenHelper;

/**
 * История переходов по ссылкам BSL-редактора (Ctrl+клик), issue 664.
 * Для свойств структуры и колонок команда ведёт туда же, куда ссылка в подсказке.
 * Дополнительно: переход по имени типа объекта метаданных в комментарии
 * (Ctrl+клик и команда «Перейти к определению»).
 * По Ctrl+наведению на объект метаданных предлагает его модули, issue 708.
 * Полное имя объекта метаданных в строковом литерале («РегистрСведений.КурсыВалют»
 * в тексте запроса) — такая же ссылка, issue 825.
 */
public final class BslEditorHyperlinkHook implements IStartup
{
    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            Display display = Display.getDefault();
            display.addFilter(SWT.MouseMove, BslEditorHyperlinkHook::attach);
            display.addFilter(SWT.MouseDown, BslEditorHyperlinkHook::attach);
            display.addFilter(SWT.Show, BslEditorHyperlinkHook::hidePrimaryLink);
            display.addFilter(SWT.Show, BslEditorHyperlinkHook::showVariableLinks);
            ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commands != null)
                commands.addExecutionListener(new OpenDeclarationListener());
        });
    }

    private static final String OPEN_DECLARATION_COMMAND =
        "org.eclipse.xtext.ui.editor.hyperlinking.OpenDeclaration"; //$NON-NLS-1$

    /**
     * {@link #fullNameLink} увидел путь через форму к реквизиту: штатную ссылку на форму
     * для этого места не берём (иначе Ctrl+наведение предлагает «Модуль формы»/«В навигаторе»).
     */
    private static final ThreadLocal<Boolean> FORM_MEMBER_PATH = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Команда «Перейти к определению» берёт свой экземпляр детектора (не из массива
     * просмотрщика). Для свойства подменяем обработчик одного вызова, как в LwtTextSaveHook;
     * для ссылки на тип в комментарии сохраняем дополнение после штатного обработчика.
     */
    private static final class OpenDeclarationListener implements IExecutionListenerWithChecks
    {
        private IHyperlink pending;
        private PropertyDefinitionHandler propertyHandler;

        @Override
        public void preExecute(String commandId, ExecutionEvent event)
        {
            pending = null;
            if (!OPEN_DECLARATION_COMMAND.equals(commandId))
                return;
            try
            {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                IWorkbenchPage page = window == null ? null : window.getActivePage();
                BslXtextEditor editor = page == null ? null : GetRef.getActiveBslEditor(page.getActivePart());
                ITextViewer viewer = editor == null ? null : editor.getInternalSourceViewer();
                if (viewer == null)
                    return;
                // getSelectedRange — координаты модели (не виджета), см. правило про folding.
                int offset = viewer.getSelectedRange().x;
                IHyperlink property = propertyLink(editor, page, viewer, offset);
                if (property != null)
                {
                    Command command = event.getCommand();
                    if (command.getHandler() instanceof PropertyDefinitionHandler)
                        return;
                    propertyHandler = new PropertyDefinitionHandler(command, property);
                    command.setHandler(propertyHandler);
                    return;
                }
                if (hasStockLinks(viewer, offset))
                    return;
                pending = textLink(editor, page, viewer, offset);
            }
            catch (RuntimeException | LinkageError e)
            {
                pending = null;
                restorePropertyHandler(commandId);
            }
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue)
        {
            restorePropertyHandler(commandId);
            IHyperlink link = pending;
            pending = null;
            if (link != null && OPEN_DECLARATION_COMMAND.equals(commandId))
                link.open();
        }

        @Override
        public void notHandled(String commandId, NotHandledException exception)
        {
            restorePropertyHandler(commandId);
            pending = null;
        }

        @Override
        public void postExecuteFailure(String commandId, ExecutionException exception)
        {
            restorePropertyHandler(commandId);
            pending = null;
        }

        @Override
        public void notEnabled(String commandId, NotEnabledException exception)
        {
            restorePropertyHandler(commandId);
            pending = null;
        }

        @Override
        public void notDefined(String commandId, NotDefinedException exception)
        {
            restorePropertyHandler(commandId);
            pending = null;
        }

        private void restorePropertyHandler(String commandId)
        {
            if (OPEN_DECLARATION_COMMAND.equals(commandId) && propertyHandler != null)
            {
                propertyHandler.restore();
                propertyHandler = null;
            }
        }

        private static boolean hasStockLinks(ITextViewer viewer, int offset)
        {
            if (!(Global.getField(viewer, "fHyperlinkDetectors") instanceof IHyperlinkDetector[] detectors))
                return false;
            for (IHyperlinkDetector detector : detectors)
            {
                IHyperlinkDetector stock = detector instanceof HistoryDetector history ? history.delegate : detector;
                if (stock == null)
                    continue;
                IHyperlink[] links = stock.detectHyperlinks(viewer, new Region(offset, 0), false);
                if (links != null && links.length > 0)
                    return true;
            }
            return false;
        }
    }

    /** Один вызов команды: тот же адрес и открытие, что у обеих подсказок. */
    private static final class PropertyDefinitionHandler extends AbstractHandler
    {
        private final Command command;
        private final IHandler original;
        private final IHyperlink link;

        PropertyDefinitionHandler(Command command, IHyperlink link)
        {
            this.command = command;
            this.original = command.getHandler();
            this.link = link;
        }

        @Override
        public Object execute(ExecutionEvent event)
        {
            try
            {
                link.open();
                return null;
            }
            finally
            {
                restore();
            }
        }

        void restore()
        {
            if (command.getHandler() == this)
                command.setHandler(original);
        }
    }

    /** Один и тот же переход для команды и Ctrl+клика на имени свойства. */
    private static IHyperlink propertyLink(BslXtextEditor editor, IWorkbenchPage page,
        ITextViewer viewer, int offset)
    {
        if (!(viewer.getDocument() instanceof IXtextDocument document))
            return null;
        try
        {
            return readModel(document, false, resource ->
            {
                Property property = BslStructureInsertCommentTypes.propertyAt(resource, offset);
                var target = BslStructureInsertCommentTypes.propertySourceUriFor(property, resource);
                if (resource.getParseResult() == null)
                    return null;
                ILeafNode leaf = BslNodeLookup.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset);
                if (leaf == null || leaf.isHidden())
                    return null;
                IRegion region = new Region(leaf.getOffset(), leaf.getLength());
                if (target != null)
                    return new PropertyHyperlink(region, target, editor, page);
                IHyperlink member = tableMemberLink(property, resource, region, editor, page);
                if (member != null)
                    return member;
                return BslQueryResultTypes.isQueryFieldAt(resource, offset)
                    ? new DeferredQueryPropertyHyperlink(region, document, offset, editor, page) : null;
            });
        }
        catch (RuntimeException | LinkageError failure)
        {
            return null;
        }
    }

    /**
     * Общий или стандартный реквизит объекта ({@code Объект.Комментарий}, {@code Объект.Код}): штатная
     * ссылка общего реквизита открывает его собственный редактор, у стандартного ссылки нет вовсе.
     * Ведём в редактор объекта метаданных таблицы на строку реквизита — как из окон запросов
     * ({@link BslQueryLiteralSupport#openTableMember}). Таблица — владелец типа, которому принадлежит свойство.
     */
    private static IHyperlink tableMemberLink(Property property, XtextResource resource, IRegion region,
        BslXtextEditor editor, IWorkbenchPage page)
    {
        if (property == null || property.getName() == null)
            return null;
        EObject origin = property instanceof DerivedProperty derived ? derived.getSource() : null;
        if (origin != null && origin.eIsProxy())
            origin = EcoreUtil.resolve(origin, resource);
        TypeItem ownerType = org.eclipse.xtext.EcoreUtil2.getContainerOfType(property, TypeItem.class);
        MdObject table = ownerType == null ? null : typeProducer(ownerType);
        boolean common = origin != null && "CommonAttribute".equals(origin.eClass().getName()); //$NON-NLS-1$
        boolean standard = false;
        String fieldName = property.getName();
        // Стандартные поля таблицы — поля FieldSource с SourceType.STANDARD_FIELDS (тот же признак, что у
        // MdStandardObjectsInferrer); StandardAttributeUtil.getStandardAttributes хранит только изменённые.
        if (!common && table instanceof FieldSource fields && (origin == null || origin == table
            || "StandardAttribute".equals(origin.eClass().getName()))) //$NON-NLS-1$
            for (com._1c.g5.v8.dt.mcore.Field field : fields.getFields())
                if (field != null && field.getSourceTypes().contains(SourceType.STANDARD_FIELDS)
                    && (property.getName().equalsIgnoreCase(field.getName())
                        || property.getNameRu() != null && property.getNameRu().equalsIgnoreCase(field.getNameRu())))
                {
                    standard = true;
                    fieldName = field.getName();
                    break;
                }
        if (table == null || !common && !standard)
            return null;
        return new TableMemberHyperlink(region, table, common ? origin : null, fieldName, editor, page);
    }

    private static final class TableMemberHyperlink implements IHyperlink
    {
        private final IRegion region;
        private final MdObject table;
        private final EObject common;
        private final String fieldName;
        private final BslXtextEditor source;
        private final IWorkbenchPage page;

        TableMemberHyperlink(IRegion region, MdObject table, EObject common, String fieldName,
            BslXtextEditor source, IWorkbenchPage page)
        {
            this.region = region;
            this.table = table;
            this.common = common;
            this.fieldName = fieldName;
            this.source = source;
            this.page = page;
        }

        @Override public IRegion getHyperlinkRegion() { return region; }
        @Override public String getTypeLabel() { return null; }
        @Override public String getHyperlinkText() { return "Перейти к определению реквизита"; }

        @Override
        public void open()
        {
            try (HistoryScope scope = new HistoryScope(page, source))
            {
                BslQueryLiteralSupport.openTableMember(table, common, fieldName, source, source.getSite().getShell());
                scope.markOpened();
            }
        }
    }

    /** Описание формального параметра доступно и из объявления, и из обращения в теле метода. */
    private static IHyperlink parameterDescriptionLink(BslXtextEditor editor, IWorkbenchPage page,
        ITextViewer viewer, int offset)
    {
        if (!(viewer.getDocument() instanceof IXtextDocument document))
            return null;
        Global.tempLog("parameter-description-link", "detect offset=" + offset);
        try
        {
            return ContentAssistSessionReloader.readOnlyPeekAst(document, resource ->
            {
                EObject target = new org.eclipse.xtext.resource.EObjectAtOffsetHelper()
                    .resolveElementAt(resource, offset);
                if (target instanceof StaticFeatureAccess access)
                {
                    target = null;
                    for (var entry : access.getFeatureEntries())
                        if (entry != null && entry.getFeature() instanceof com._1c.g5.v8.dt.bsl.model.FormalParam)
                        {
                            target = entry.getFeature();
                            break;
                        }
                }
                if (!(target instanceof com._1c.g5.v8.dt.bsl.model.FormalParam parameter)
                    || !(parameter.eContainer() instanceof com._1c.g5.v8.dt.bsl.model.Method method)
                    || !(method.eContainer() instanceof com._1c.g5.v8.dt.bsl.model.Module module)
                    || resource.getParseResult() == null)
                    return null;
                ILeafNode leaf = BslNodeLookup.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset);
                if (leaf == null || leaf.isHidden() || !parameter.getName().equalsIgnoreCase(leaf.getText()))
                    return null;
                var comment = BslStructureInsertCommentTypes.parseMethodComment(method, module);
                var definition = comment == null || comment.getParametersSection() == null ? null
                    : comment.getParametersSection().getParameterByName(parameter.getName());
                INode node = NodeModelUtils.findActualNodeFor(method);
                int[] lines = comment == null ? null : comment.getLineGlobalOffsets();
                int line = definition == null ? -1 : definition.getLineNumber();
                if (node == null || lines == null || line < 0 || line >= lines.length)
                    return null;
                int start = node.getTotalOffset() + lines[line] + definition.getNameOffset();
                String text = node.getRootNode().getText();
                boolean matches = text.regionMatches(true, start, parameter.getName(), 0, parameter.getName().length());
                Global.tempLog("parameter-description-link", "parameter=" + parameter.getName()
                    + " method=" + method.getName() + " offset=" + start + " matches=" + matches);
                if (!matches)
                    return null;
                var place = new BslStructureInsertCommentTypes.PropertySourceTarget(EcoreUtil.getURI(method),
                    start, parameter.getName().length());
                return new PropertyHyperlink(new Region(leaf.getOffset(), leaf.getLength()), place, editor, page,
                    "Описание");
            });
        }
        catch (RuntimeException | LinkageError failure)
        {
            Global.tempLog("parameter-description-link", "failed offset=" + offset + " error=" + failure);
            return null;
        }
    }

    /** Первый переход также запускает общий досчёт; UI-поток не разбирает запрос. */
    private static final class DeferredQueryPropertyHyperlink implements IHyperlink
    {
        private final IRegion region;
        private final IXtextDocument document;
        private final int offset;
        private final BslXtextEditor editor;
        private final IWorkbenchPage page;

        DeferredQueryPropertyHyperlink(IRegion region, IXtextDocument document, int offset,
            BslXtextEditor editor, IWorkbenchPage page)
        {
            this.region = region;
            this.document = document;
            this.offset = offset;
            this.editor = editor;
            this.page = page;
        }

        @Override public IRegion getHyperlinkRegion() { return region; }
        @Override public String getTypeLabel() { return null; }
        @Override public String getHyperlinkText() { return "Перейти к определению поля запроса"; }

        @Override
        public void open()
        {
            long stamp = ((org.eclipse.jface.text.IDocumentExtension4)document).getModificationStamp();
            org.eclipse.core.runtime.jobs.Job job = org.eclipse.core.runtime.jobs.Job.create(
                "Переход к определению поля запроса", monitor -> //$NON-NLS-1$
                {
                    try
                    {
                        var target = document.readOnly(resource ->
                        {
                            if (monitor.isCanceled() || !unchanged(stamp))
                                return null;
                            BslStructureInsertCommentTypes.refreshTypesForAssist(resource, offset, status -> {});
                            return BslStructureInsertCommentTypes.propertySourceUriAt(resource, offset);
                        });
                        if (target != null && !monitor.isCanceled())
                            Display.getDefault().asyncExec(() ->
                            {
                                if (unchanged(stamp) && editor.getInternalSourceViewer() != null
                                    && editor.getInternalSourceViewer().getTextWidget() != null
                                    && !editor.getInternalSourceViewer().getTextWidget().isDisposed())
                                    new PropertyHyperlink(region, target, editor, page).open();
                            });
                    }
                    catch (RuntimeException | LinkageError failure)
                    {
                    }
                });
            job.setSystem(true);
            job.schedule();
        }

        private boolean unchanged(long stamp)
        {
            return ((org.eclipse.jface.text.IDocumentExtension4)document).getModificationStamp() == stamp;
        }
    }

    private static final class PropertyHyperlink implements IHyperlink
    {
        private final IRegion region;
        private final BslStructureInsertCommentTypes.PropertySourceTarget target;
        private final BslXtextEditor source;
        private final IWorkbenchPage page;
        private final String text;

        PropertyHyperlink(IRegion region, BslStructureInsertCommentTypes.PropertySourceTarget target,
            BslXtextEditor source, IWorkbenchPage page)
        {
            this(region, target, source, page, "Перейти к определению свойства");
        }

        PropertyHyperlink(IRegion region, BslStructureInsertCommentTypes.PropertySourceTarget target,
            BslXtextEditor source, IWorkbenchPage page, String text)
        {
            this.region = region;
            this.target = target;
            this.source = source;
            this.page = page;
            this.text = text;
        }

        @Override public IRegion getHyperlinkRegion() { return region; }
        @Override public String getTypeLabel() { return null; }
        @Override public String getHyperlinkText() { return text; }

        @Override
        public void open()
        {
            try (HistoryScope scope = new HistoryScope(page, source))
            {
                BslStructureInsertCommentTypes.openPropertySource(target);
                scope.markOpened();
            }
        }
    }

    private static IHyperlink textLink(BslXtextEditor editor, IWorkbenchPage page, ITextViewer viewer, int offset)
    {
        IHyperlink link = typeLink(editor, page, viewer, offset);
        if (link == null)
            link = fullNameLink(editor, page, viewer, offset, false, false);
        if (link == null)
            link = fullNameLink(editor, page, viewer, offset, true, false);
        if (link == null)
            link = commonModuleMethodLink(editor, page, viewer, offset);
        if (link == null)
            link = tempTableLink(editor, page, viewer, offset);
        if (link == null)
        {
            // Поля, псевдонимы, виртуальные таблицы в литерале-запросе — как в «Редакторе запроса».
            IHyperlink query = BslQueryLiteralSupport.queryLink(editor, viewer, offset);
            link = query == null ? null : new HistoryHyperlink(query, editor, page);
        }
        return link;
    }

    /**
     * Имя временной таблицы или её колонки в литерале {@code М.Таблицы["Вт"]}, {@code М.Таблицы.Найти("Вт")},
     * {@code ….Колонки.Найти("Поле")} — переход к {@code ПОМЕСТИТЬ} или полю пакета-источника (ИР: переходы
     * {@code ВременныеТаблицыЗапроса}, issue 889).
     */
    private static IHyperlink tempTableLink(BslXtextEditor editor, IWorkbenchPage page, ITextViewer viewer, int offset)
    {
        if (!(viewer.getDocument() instanceof IXtextDocument document))
            return null;
        try
        {
            return readModel(document, false, resource ->
            {
                if (resource == null || resource.getParseResult() == null)
                    return null;
                EObject object = BslNodeLookup.resolveContainedElementAt(resource, offset);
                StringLiteral literal = org.eclipse.xtext.EcoreUtil2.getContainerOfType(object, StringLiteral.class);
                var target = literal == null ? null
                    : BslTempTableSessions.literalNameTarget(literal, org.eclipse.xtext.util.CancelIndicator.NullImpl);
                var node = target == null ? null : NodeModelUtils.findActualNodeFor(literal);
                return node == null ? null
                    : new PropertyHyperlink(new Region(node.getOffset(), node.getLength()), target, editor, page);
            });
        }
        catch (RuntimeException | LinkageError failure)
        {
            return null;
        }
    }

    /** Полное имя объекта метаданных в литерале или комментарии — ссылка на фрагмент под указателем. */
    private static IHyperlink segmentLink(BslXtextEditor editor, IWorkbenchPage page, ITextViewer viewer, int offset)
    {
        IHyperlink link = fullNameLink(editor, page, viewer, offset, false, true);
        return link != null ? link : fullNameLink(editor, page, viewer, offset, true, true);
    }

    /**
     * Ссылка на объект метаданных по полному имени «Тип.Имя» под {@code offset} в строковом
     * литерале (текст запроса, аргумент НайтиПоПолномуИмени и т.п.). Дальние сегменты
     * («…КурсыВалют.СрезПоследних») не мешают — ссылка ведёт на объект верхнего уровня.
     * Первый сегмент может быть и во множественном числе («Справочники.Имя.Метод»).
     * {@code inComment} — то же для комментария {@code //}.
     * Ссылка ведёт на объект того фрагмента, что под указателем: на «Обработка1» в
     * «Обработка.Обработка1.Форма.Форма1» — на обработку, на «Форма1» — на форму.
     * {@code segmentOnly} — только когда фрагмент под указателем найден в конфигурации
     * (без хвоста). tryReadOnly, как и readOnly, может ждать синхронизации модели EDT.
     */
    private static IHyperlink fullNameLink(BslXtextEditor editor, IWorkbenchPage page, ITextViewer viewer,
        int offset, boolean inComment, boolean segmentOnly)
    {
        if (!(viewer.getDocument() instanceof IXtextDocument document) || document.getResourceURI() == null)
            return null;
        try
        {
            IUnitOfWork<Object[], XtextResource> work = resource ->
            {
                if (resource == null || resource.getParseResult() == null)
                    return null;
                ILeafNode leaf = inComment ? commentLeaf(resource, offset) : literalLeaf(resource, offset);
                // Каретка сразу за именем перед закрывающей кавычкой или концом строки.
                if (leaf == null && offset > 0)
                    leaf = inComment ? commentLeaf(resource, offset - 1) : literalLeaf(resource, offset - 1);
                if (leaf == null)
                    return null;
                String text = leaf.getText();
                int position = Math.max(0, Math.min(text.length(), offset - leaf.getOffset()));
                int start = position;
                while (start > 0 && isTypeNameChar(text.charAt(start - 1)))
                    start--;
                while (start < position && text.charAt(start) == '.')
                    start++;
                // Глобальное свойство «Метаданные.Справочники.Имя» — имя объекта идёт после него.
                int skip = text.regionMatches(true, start, METADATA_PREFIX, 0, METADATA_PREFIX.length())
                    ? METADATA_PREFIX.length() : 0;
                int typeEnd = text.indexOf('.', start + skip);
                if (typeEnd < 0)
                    return null;
                String typeWord = text.substring(start + skip, typeEnd);
                if (!MdTypeMapping.isKnownMdRootType(typeWord) && MdTypeMapping.ruPluralToRu(typeWord) == null
                    && !GoToDefinition.TYPE_SUFFIX_MAP.containsKey(typeWord))
                    return null;
                int end = start;
                while (end < text.length() && isTypeNameChar(text.charAt(end)))
                    end++;
                while (end > start && text.charAt(end - 1) == '.')
                    end--;
                // Под указателем — само имя, а не пробел или знак после него.
                if (end <= typeEnd + 1 || position > end)
                    return null;
                int hovered = 0;
                for (int i = start + skip; i < position; i++)
                    if (text.charAt(i) == '.')
                        hovered++;
                String path = text.substring(start + skip, end);
                int memberStart = formMemberStart(path.split("\\.", -1)); //$NON-NLS-1$
                if (memberStart >= 0 && hovered >= memberStart)
                    FORM_MEMBER_PATH.set(Boolean.TRUE);
                return new Object[] { path, Integer.valueOf(leaf.getOffset() + start),
                    Integer.valueOf(skip), Integer.valueOf(hovered) };
            };
            Object[] found = readModel(document, segmentOnly, work);
            if (found == null)
                return null;
            IV8ProjectManager manager = (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
            IV8Project project = manager == null ? null : manager.getProject(document.getResourceURI());
            if (project == null)
                return null;
            // Поиск в BM — вне блокировки документа. Берём самую длинную начальную часть имени,
            // которая есть в конфигурации: хвост («.Метод», опечатка) ссылку не отменяет.
            String[] segments = ((String) found[0]).split("\\.", -1); //$NON-NLS-1$
            int regionStart = (Integer) found[1];
            int skip = (Integer) found[2];
            int fullLength = skip + ((String) found[0]).length();
            // Дочерние объекты правее фрагмента под указателем не рассматриваем:
            // «Тип.Имя» — сегменты 0–1, далее пары «Вид.Имя».
            int hovered = (Integer) found[3];
            // Путь через форму к реквизиту: цель — сам реквизит (формы или метаданных),
            // а не форма. Если не разрешился — ссылки нет, на форму не откатываемся.
            int memberStart = formMemberStart(segments);
            if (memberStart >= 0 && hovered >= memberStart)
                return formPathMemberLink(editor, page, project, segments, memberStart, hovered,
                    regionStart, skip);
            int limit = hovered < 2 ? 2 : hovered + 2 - hovered % 2;
            for (int count = Math.min(segments.length, limit); count >= 2; count--)
            {
                if (count > 2 && count % 2 != 0)
                    continue; // после «Тип.Имя» идут пары «Вид.Имя»
                String raw = String.join(".", java.util.Arrays.copyOf(segments, count)); //$NON-NLS-1$
                if (GoToDefinition.resolveEObjectByQualifiedName(normalizeFullName(segments, count), project)
                    instanceof MdObject target)
                {
                    boolean onTail = offset - regionStart > skip + raw.length();
                    // «Обработка.Имя.Форма» с указателем на роли формы — переходы к основной форме,
                    // а не к объекту.
                    if (onTail && count == 2 && segments.length == 3 && hovered == 2)
                    {
                        BasicForm roleForm = BslLiteralValues.roleForm(target, segments[2]);
                        if (roleForm != null)
                            return new TypeHyperlink(new Region(regionStart, fullLength), "Форма", roleForm, editor,
                                page);
                    }
                    if (segmentOnly && onTail)
                        return null;
                    // «Справочники.Имя.Метод» с указателем на методе — в метод модуля менеджера,
                    // а не в меню объекта.
                    if (onTail && count == 2 && segments.length == 3 && MdTypeMapping.ruPluralToRu(segments[0]) != null)
                        return new ManagerMethodHyperlink(new Region(regionStart, fullLength),
                            String.join(".", segments), project.getProject(), editor, page); //$NON-NLS-1$
                    // Иной хвост — ссылкой подсвечиваем имя целиком.
                    int length = onTail ? fullLength : skip + raw.length();
                    return new TypeHyperlink(new Region(regionStart, length), raw, target, editor, page);
                }
            }
            return null;
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }
    }

    /** Начало сегментов-реквизитов после «…Форма.<Имя>.» / «ОбщаяФорма.<Имя>.»; иначе -1. */
    private static int formMemberStart(String[] segments)
    {
        if (segments == null || segments.length < 3)
            return -1;
        if (isRuOrEn(segments[0], "ОбщаяФорма", "CommonForm")) //$NON-NLS-1$ //$NON-NLS-2$
            return 2;
        for (int i = 0; i + 1 < segments.length; i += 2)
            if (isRuOrEn(segments[i], "Форма", "Form")) //$NON-NLS-1$ //$NON-NLS-2$
                return i + 2;
        return -1;
    }

    private static boolean isRuOrEn(String value, String ru, String en)
    {
        return ru.equalsIgnoreCase(value) || en.equalsIgnoreCase(value);
    }

    /**
     * Реквизит формы или вложенный реквизит в пути «…Форма.<Имя>.<Реквизит>…»: переход на сам
     * реквизит, а не на форму. {@code null} — цель не найдена; форму в этом случае не подставляем.
     */
    private static IHyperlink formPathMemberLink(BslXtextEditor editor, IWorkbenchPage page,
        IV8Project project, String[] segments, int memberStart, int hovered, int regionStart, int skip)
    {
        if (project == null || memberStart <= 0 || hovered < memberStart)
            return null;
        if (!(GoToDefinition.resolveEObjectByQualifiedName(
            normalizeFullName(segments, memberStart), project) instanceof BasicForm basicForm))
            return null;
        com._1c.g5.v8.dt.metadata.mdclass.AbstractForm abstractForm = basicForm.getForm();
        if (abstractForm != null && abstractForm.eIsProxy())
            abstractForm = (com._1c.g5.v8.dt.metadata.mdclass.AbstractForm)EcoreUtil.resolve(abstractForm,
                basicForm);
        if (!(abstractForm instanceof com._1c.g5.v8.dt.form.model.Form form) || form.eIsProxy())
            return null;
        com._1c.g5.v8.dt.form.model.AbstractFormAttribute attribute =
            formAttribute(form, segments[memberStart]);
        if (attribute == null)
            return null;
        IRegion region = memberRegion(regionStart + skip, segments, hovered);
        if (hovered == memberStart)
            return new FormElementHyperlink(region, attribute, editor, page);
        java.util.List<String> dataPath = new java.util.ArrayList<>();
        for (int i = memberStart; i <= hovered; i++)
            dataPath.add(segments[i]);
        // Вложенный реквизит: родные типы формы (с дополнительными реквизитами), затем источник
        // найденного свойства — реквизит/колонка формы или строка данных формы.
        EObject contextProperty = formContextProperty(form, attribute);
        IHyperlink link = contextProperty == null ? null
            : nestedMemberLink(contextProperty, segments, memberStart, hovered, region, basicForm, dataPath,
                editor, page);
        // Тип данных формы может быть без источника — тогда цель ищем по объявленному типу.
        return link != null ? link
            : nestedMemberLink(attribute, segments, memberStart, hovered, region, basicForm, dataPath,
                editor, page);
    }

    /** Проход по вложенным сегментам от {@code start} до {@code hovered} и ссылка на цель. */
    private static IHyperlink nestedMemberLink(EObject start, String[] segments, int memberStart,
        int hovered, IRegion region, BasicForm basicForm, java.util.List<String> dataPath,
        BslXtextEditor editor, IWorkbenchPage page)
    {
        EObject current = start;
        java.util.List<TypeItem> types = Activator.docCommentMemberTypes(current);
        for (int i = memberStart + 1; i <= hovered; i++)
        {
            Property found = Activator.docCommentMember(types, segments[i]);
            if (found == null)
                return null;
            current = found;
            types = Activator.docCommentMemberTypes(found);
        }
        return memberHyperlink(current, region, basicForm, dataPath, editor, page);
    }

    private static com._1c.g5.v8.dt.form.model.AbstractFormAttribute formAttribute(
        com._1c.g5.v8.dt.form.model.Form form, String name)
    {
        if (name == null || form.getAttributes() == null)
            return null;
        for (com._1c.g5.v8.dt.form.model.FormAttribute attribute : form.getAttributes())
            if (attribute != null && attribute.getName() != null
                && name.equalsIgnoreCase(attribute.getName()))
                return attribute;
        return null;
    }

    /** Свойство контекста формы для реквизита — носитель родного типа данных формы. */
    private static EObject formContextProperty(com._1c.g5.v8.dt.form.model.Form form,
        com._1c.g5.v8.dt.form.model.AbstractFormAttribute attribute)
    {
        if (form.getFormContext() == null)
            return null;
        for (Property property : form.getFormContext().getProperties())
            if (property instanceof DerivedProperty derived && derived.getSource() == attribute)
                return property;
        String uri = EcoreUtil.getURI(attribute).toString();
        for (Property property : form.getFormContext().getProperties())
            if (property instanceof DerivedProperty derived && derived.getSource() != null
                && uri.equals(EcoreUtil.getURI(derived.getSource()).toString()))
                return property;
        return null;
    }

    private static IHyperlink memberHyperlink(EObject member, IRegion region, BasicForm basicForm,
        java.util.List<String> dataPath, BslXtextEditor editor, IWorkbenchPage page)
    {
        if (member instanceof com._1c.g5.v8.dt.form.model.AbstractFormAttribute
            || member instanceof com._1c.g5.v8.dt.form.model.FormItem)
            return new FormElementHyperlink(region, member, editor, page);
        if (member instanceof Property property)
        {
            EObject source = property instanceof DerivedProperty derived ? derived.getSource() : null;
            if (source instanceof com._1c.g5.v8.dt.form.model.AbstractFormAttribute
                || source instanceof com._1c.g5.v8.dt.form.model.FormItem)
                return new FormElementHyperlink(region, source, editor, page);
            return new FormDataMemberHyperlink(region, basicForm, dataPath, page);
        }
        return null;
    }

    private static IRegion memberRegion(int pathStart, String[] segments, int hovered)
    {
        int offset = pathStart;
        for (int i = 0; i < hovered; i++)
            offset += segments[i].length() + 1;
        return new Region(offset, segments[hovered].length());
    }

    /**
     * Готовая ссылка на сегмент пути через форму по тексту документа: нужна в UI-потоке,
     * где читать модель нельзя. Иначе штатная ссылка EDT открывает объект метаданных раньше,
     * чем фоновая проверка заменит её.
     */
    private static DeferredFormMemberHyperlink deferredFormMemberLink(BslXtextEditor editor,
        IWorkbenchPage page, ITextViewer viewer, IRegion region)
    {
        if (editor == null || page == null || region == null
            || !(viewer.getDocument() instanceof IXtextDocument document))
            return null;
        Object[] found = formMemberPathAtDocument(document, region.getOffset());
        if (found == null)
            return null;
        String[] segments = (String[])found[0];
        int memberStart = ((Integer)found[1]).intValue();
        int hovered = ((Integer)found[2]).intValue();
        int pathStart = ((Integer)found[3]).intValue();
        IV8ProjectManager manager = (IV8ProjectManager)Global.getServiceByClass(IV8ProjectManager.class);
        IV8Project project = manager == null ? null : manager.getProject(document.getResourceURI());
        if (project == null)
            return null;
        return new DeferredFormMemberHyperlink(memberRegion(pathStart, segments, hovered), segments,
            memberStart, hovered, pathStart, project, editor, page);
    }

    /** Путь к реквизиту через форму в строке документа; {@code null} — это не такой путь. */
    private static Object[] formMemberPathAtDocument(org.eclipse.jface.text.IDocument document, int offset)
    {
        try
        {
            int line = document.getLineOfOffset(offset);
            int lineStart = document.getLineOffset(line);
            String text = document.get(lineStart, document.getLineLength(line));
            int position = offset - lineStart;
            int left = position;
            int right = position;
            while (left > 0 && isTypeNameChar(text.charAt(left - 1)))
                left--;
            while (right < text.length() && isTypeNameChar(text.charAt(right)))
                right++;
            while (left < right && text.charAt(left) == '.')
                left++;
            while (right > left && text.charAt(right - 1) == '.')
                right--;
            if (left >= right)
                return null;
            String path = text.substring(left, right);
            String[] segments = path.split("\\.", -1); //$NON-NLS-1$
            if (segments.length < 4)
                return null;
            String first = segments[0];
            if (!MdTypeMapping.isKnownMdRootType(first) && MdTypeMapping.ruPluralToRu(first) == null
                && !GoToDefinition.TYPE_SUFFIX_MAP.containsKey(first))
                return null;
            int memberStart = formMemberStart(segments);
            if (memberStart < 0 || segments.length <= memberStart)
                return null;
            int local = Math.max(0, Math.min(path.length(), position - left));
            int hovered = 0;
            for (int i = 0; i < local; i++)
                if (path.charAt(i) == '.')
                    hovered++;
            if (hovered < memberStart || hovered >= segments.length)
                return null;
            return new Object[] { segments, Integer.valueOf(memberStart), Integer.valueOf(hovered),
                Integer.valueOf(lineStart + left), Integer.valueOf(segments[hovered].length()) };
        }
        catch (org.eclipse.jface.text.BadLocationException e)
        {
            return null;
        }
    }

    /** Отложенная ссылка: путь уже разобран, форма и строка разрешаются при открытии. */
    private static final class DeferredFormMemberHyperlink implements IHyperlink
    {
        private final IRegion region;
        private final String[] segments;
        private final int memberStart;
        private final int hovered;
        private final int pathStart;
        private final IV8Project project;
        private final BslXtextEditor source;
        private final IWorkbenchPage page;

        DeferredFormMemberHyperlink(IRegion region, String[] segments, int memberStart, int hovered,
            int pathStart, IV8Project project, BslXtextEditor source, IWorkbenchPage page)
        {
            this.region = region;
            this.segments = segments;
            this.memberStart = memberStart;
            this.hovered = hovered;
            this.pathStart = pathStart;
            this.project = project;
            this.source = source;
            this.page = page;
        }

        @Override public IRegion getHyperlinkRegion() { return region; }
        @Override public String getTypeLabel() { return null; }
        @Override public String getHyperlinkText() { return segments[hovered]; }

        @Override
        public void open()
        {
            IHyperlink link = formPathMemberLink(source, page, project, segments, memberStart, hovered,
                pathStart, 0);
            if (link != null)
                link.open();
        }
    }

    private static final class FormElementHyperlink implements IHyperlink
    {
        private final IRegion region;
        private final EObject target;
        private final BslXtextEditor source;
        private final IWorkbenchPage page;

        FormElementHyperlink(IRegion region, EObject target, BslXtextEditor source, IWorkbenchPage page)
        {
            this.region = region;
            this.target = target;
            this.source = source;
            this.page = page;
        }

        @Override public IRegion getHyperlinkRegion() { return region; }
        @Override public String getTypeLabel() { return null; }
        @Override public String getHyperlinkText() { return "Перейти к реквизиту формы"; } //$NON-NLS-1$

        @Override
        public void open()
        {
            try (HistoryScope scope = new HistoryScope(page, source))
            {
                if (FormEditorHook.openFormElement(page, target) != null)
                    scope.markOpened();
            }
        }
    }

    /**
     * Строка данных формы для вложенного реквизита в ссылке комментария. Открывается редактор
     * формы и выделяется путь в дереве реквизитов формы ({@code Объект → Команды}) — той же
     * механикой, что и остальные переходы к реквизитам формы, без редактора метаданных.
     */
    private static final class FormDataMemberHyperlink implements IHyperlink
    {
        private final IRegion region;
        private final BasicForm form;
        private final java.util.List<String> dataPath;
        private final IWorkbenchPage page;

        FormDataMemberHyperlink(IRegion region, BasicForm form, java.util.List<String> dataPath,
            IWorkbenchPage page)
        {
            this.region = region;
            this.form = form;
            this.dataPath = dataPath;
            this.page = page;
        }

        @Override public IRegion getHyperlinkRegion() { return region; }
        @Override public String getTypeLabel() { return null; }
        @Override public String getHyperlinkText()
        {
            return dataPath == null || dataPath.isEmpty() ? "Перейти к реквизиту формы" //$NON-NLS-1$
                : dataPath.get(dataPath.size() - 1);
        }

        @Override
        public void open()
        {
            FormEditorHook.openFormDataPath(page, form, dataPath);
        }
    }

    /**
     * Ссылка на метод общего модуля в литерале «ОбщийМодуль.Метод» (обработчик команды,
     * описание оповещения и т.п.) — как в обычном вызове: на имени модуля — меню объекта
     * метаданных, на имени метода — переход к методу.
     */
    private static IHyperlink commonModuleMethodLink(BslXtextEditor editor, IWorkbenchPage page, ITextViewer viewer,
        int offset)
    {
        if (!(viewer.getDocument() instanceof IXtextDocument document) || document.getResourceURI() == null)
            return null;
        try
        {
            int[] found = readModel(document, false, resource ->
            {
                if (resource == null || resource.getParseResult() == null)
                    return null;
                ILeafNode leaf = literalLeaf(resource, offset);
                if (leaf == null && offset > 0)
                    leaf = literalLeaf(resource, offset - 1);
                if (leaf == null)
                    return null;
                String text = leaf.getText();
                int position = Math.max(0, Math.min(text.length(), offset - leaf.getOffset()));
                int start = position;
                while (start > 0 && isTypeNameChar(text.charAt(start - 1)))
                    start--;
                int end = position;
                while (end < text.length() && isTypeNameChar(text.charAt(end)))
                    end++;
                int dot = text.indexOf('.', start);
                int secondDot = dot < 0 ? -1 : text.indexOf('.', dot + 1);
                // Ровно «Модуль.Метод»: оба имени непустые, вторая точка не допускается.
                if (dot <= start || dot >= end - 1 || secondDot >= 0 && secondDot < end)
                    return null;
                return new int[] { leaf.getOffset() + start, dot - start, end - dot - 1 };
            });
            // #region agent log
            Global.tempLog("literal-common-module", "cm offset=" + offset + " found=" //$NON-NLS-1$ //$NON-NLS-2$
                + (found == null ? "null" : java.util.Arrays.toString(found))); //$NON-NLS-1$
            // #endregion
            if (found == null)
                return null;
            String name = document.get(found[0], found[1] + 1 + found[2]);
            String moduleName = name.substring(0, found[1]);
            String methodName = name.substring(found[1] + 1);
            IV8ProjectManager manager = (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
            IV8Project project = manager == null ? null : manager.getProject(document.getResourceURI());
            // #region agent log
            EObject resolvedLog = project == null ? null
                : GoToDefinition.resolveEObjectByQualifiedName("ОбщийМодуль." + moduleName, project); //$NON-NLS-1$
            Global.tempLog("literal-common-module", "cm name=" + name + " project=" + project //$NON-NLS-1$ //$NON-NLS-2$
                + " resolved=" + (resolvedLog == null ? "null" : resolvedLog.eClass().getName())); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            if (project == null
                || !(GoToDefinition.resolveEObjectByQualifiedName("ОбщийМодуль." + moduleName, project) //$NON-NLS-1$
                    instanceof CommonModule module))
                return null;
            if (offset <= found[0] + found[1])
                return new TypeHyperlink(new Region(found[0], found[1]), moduleName, module, editor, page);
            // Метода в модуле нет — ссылки нет (как у обычного вызова).
            IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
            IFile file = lookup == null || module.getModule() == null ? null
                : lookup.getPlatformResource(module.getModule());
            String moduleText = file == null ? null : BslModuleMethodResolver.moduleText(file);
            // #region agent log
            Global.tempLog("literal-common-module", "cm lookup=" + (lookup != null) + " moduleRef=" //$NON-NLS-1$ //$NON-NLS-2$
                + module.getModule() + " file=" + (file == null ? "null" : file.getFullPath()) //$NON-NLS-1$ //$NON-NLS-2$
                + " textLength=" + (moduleText == null ? -1 : moduleText.length()) //$NON-NLS-1$
                + " declLine=" + (moduleText == null ? -2 //$NON-NLS-1$
                    : GetRef.findMethodDeclarationLine(new org.eclipse.jface.text.Document(moduleText), methodName)));
            // #endregion
            if (moduleText == null
                || GetRef.findMethodDeclarationLine(new org.eclipse.jface.text.Document(moduleText), methodName) < 0)
                return null;
            // Полное имя метода — «Модуль.Метод»: подчёркиваем его целиком, как полное имя метаданного.
            return new ManagerMethodHyperlink(new Region(found[0], name.length()), name,
                project.getProject(), editor, page);
        }
        catch (RuntimeException | LinkageError | org.eclipse.jface.text.BadLocationException e)
        {
            // #region agent log
            Global.tempLog("literal-common-module", "cm failure " + e); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return null;
        }
    }

    private static final String METADATA_PREFIX = "Метаданные."; //$NON-NLS-1$

    /** «Справочники.Имя.Формы.Ф» /«СправочникСсылка.Имя» → «Справочник.Имя.Форма.Ф» (первые {@code count} сегментов). */
    private static String normalizeFullName(String[] segments, int count)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++)
        {
            String segment = segments[i];
            if (i == 0)
            {
                String base = MdTypeMapping.ruPluralToRu(segment);
                if (base == null)
                    base = GoToDefinition.TYPE_SUFFIX_MAP.get(segment);
                if (base != null)
                    segment = base;
            }
            else if (i >= 2 && i % 2 == 0)
            {
                String singular = MdTypeMapping.systemLabelToSingular(segment);
                if (singular != null)
                    segment = singular;
            }
            if (i > 0)
                sb.append('.');
            sb.append(segment);
        }
        return sb.toString();
    }

    private static ILeafNode literalLeaf(XtextResource resource, int offset)
    {
        ILeafNode leaf = BslNodeLookup.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset);
        return leaf != null && !leaf.isHidden()
            && NodeModelUtils.findActualSemanticObjectFor(leaf) instanceof StringLiteral ? leaf : null;
    }

    /**
     * Ссылка на объект метаданных по имени типа под {@code offset} в комментарии
     * (EDT делает ссылками только «см. …»). Вызывается лишь по Ctrl+наведению и по
     * команде перехода — при отрисовке модуля не участвует.
     */
    private static IHyperlink typeLink(BslXtextEditor editor, IWorkbenchPage page, ITextViewer viewer, int offset)
    {
        if (!(viewer.getDocument() instanceof IXtextDocument document))
            return null;
        try
        {
            return readModel(document, false, new IUnitOfWork<IHyperlink, XtextResource>()
            {
                @Override
                public IHyperlink exec(XtextResource resource) throws Exception
                {
                    if (resource == null || resource.getParseResult() == null || resource.getContents().isEmpty())
                        return null;
                    ILeafNode leaf = commentLeaf(resource, offset);
                    // Каретка сразу за именем в конце строки стоит уже на переводе строки.
                    if (leaf == null && offset > 0)
                        leaf = commentLeaf(resource, offset - 1);
                    if (leaf == null)
                        return null;
                    String text = leaf.getText();
                    int position = Math.max(0, Math.min(text.length(), offset - leaf.getOffset()));
                    int start = position;
                    while (start > 0 && isTypeNameChar(text.charAt(start - 1)))
                        start--;
                    int end = position;
                    while (end < text.length() && isTypeNameChar(text.charAt(end)))
                        end++;
                    while (start < end && text.charAt(start) == '.')
                        start++;
                    while (end > start && text.charAt(end - 1) == '.')
                        end--;
                    if (start >= end)
                        return null;
                    String name = text.substring(start, end);
                    IResourceServiceProvider services = resource.getResourceServiceProvider();
                    IScopeProvider scopes = services.get(IScopeProvider.class);
                    IQualifiedNameConverter converter = services.get(IQualifiedNameConverter.class);
                    if (scopes == null || converter == null)
                        return null;
                    // Тот же поиск типа по имени, что в BslDocumentationComment.computeType.
                    IScope scope = scopes.getScope(resource.getContents().get(0),
                        McorePackage.Literals.TYPE_DESCRIPTION__TYPES);
                    IEObjectDescription description = scope == null ? null
                        : scope.getSingleElement(converter.toQualifiedName(name));
                    EObject object = description == null ? null : description.getEObjectOrProxy();
                    if (object != null && object.eIsProxy())
                        object = EcoreUtil.resolve(object, resource);
                    MdObject producer = object instanceof TypeItem type ? typeProducer(type) : null;
                    return producer == null ? null
                        : new TypeHyperlink(new Region(leaf.getOffset() + start, end - start), name, producer,
                            editor, page);
                }
            });
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }
    }

    /** Для TypeSet (в том числе определяемого типа) EDT хранит владельца через MdTypeSet. */
    static MdObject typeProducer(TypeItem type)
    {
        MdObject producer = MdTypeUtil.getTypeProducer(type);
        if (producer == null && type instanceof TypeSet)
            producer = org.eclipse.xtext.EcoreUtil2.getContainerOfType(type, MdObject.class);
        return producer;
    }

    /** Те же переходы, что у Ctrl-ссылки, для меню типа внутри подсказки. */
    static IHyperlink[] metadataTypeLinks(TypeItem type, BslXtextEditor source)
    {
        return metadataObjectLinks(typeProducer(type), source);
    }

    /** Имя таблицы запроса ведёт к тому же объекту метаданных и его модулям. */
    static IHyperlink[] metadataObjectLinks(MdObject target, BslXtextEditor source)
    {
        if (source == null || source.getSite() == null || source.getInternalSourceViewer() == null)
            return new IHyperlink[0];
        if (target == null)
            return new IHyperlink[0];
        IWorkbenchPage page = source.getSite().getPage();
        return metadataLinks(source.getInternalSourceViewer(), new IHyperlink[] {
            new TypeHyperlink(new Region(0, 0), "Объект", target, source, page) }, source, page);
    }

    private static ILeafNode commentLeaf(XtextResource resource, int offset)
    {
        ILeafNode leaf = BslNodeLookup.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset);
        return leaf != null && leaf.isHidden() && leaf.getText().startsWith("//") ? leaf : null;
    }

    private static boolean isTypeNameChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    /**
     * Расширяем только список выбора ссылок: одиночный переход и штатные ссылки
     * форм/методов сохраняются. URI объекта разрешаем в ResourceSet документа,
     * как в BslSideHintOutlineInstall.resolveHandlerTargetInResourceSet.
     */
    private static <T> T readModel(IXtextDocument document, boolean tryOnly,
        IUnitOfWork<T, XtextResource> work)
    {
        IUnitOfWork<T, XtextResource> indexed = resource -> BslNodeLookup.read(work, resource);
        return tryOnly ? document.tryReadOnly(indexed) : document.readOnly(indexed);
    }

    private static IHyperlink[] metadataLinks(ITextViewer viewer, IHyperlink[] links,
        BslXtextEditor source, IWorkbenchPage page)
    {
        if (!(viewer.getDocument() instanceof IXtextDocument document))
            return links;
        try
        {
            // tryReadOnly делегирует в readOnly (IReadAccess, Xtext 2.33): это не tryLock.
            // Синхронизация BSL-документа может ждать задания разрешения ссылок модуля.
            IHyperlink[] extended = readModel(document, true, resource ->
            {
                if (resource == null)
                    return links;
                List<IHyperlink> result = new ArrayList<>();
                for (IHyperlink link : links)
                {
                    EObject target = null;
                    if (link instanceof TypeHyperlink type)
                        target = type.target;
                    else if (link instanceof XtextHyperlink xtext && xtext.getURI() != null
                        && !"bsl".equalsIgnoreCase(xtext.getURI().fileExtension())) //$NON-NLS-1$
                        target = resource.getResourceSet().getEObject(xtext.getURI(), true);
                    // EDT ссылается на producedTypes/managerType/type, а не на корень МД.
                    // Как для имени типа в комментарии, берём объект — производитель типа.
                    boolean typeTarget = target instanceof TypeItem;
                    if (target instanceof TypeItem type)
                        target = typeProducer(type);
                    if (!(target instanceof MdObject object))
                    {
                        result.add(link);
                        continue;
                    }
                    String title = object instanceof BasicForm ? "Форма" : "Объект";
                    // Основной переход (Ctrl+клик, клик в подсказке) открывает редактор объекта;
                    // в списке выбора его строки нет. У штатной ссылки сохраняем её обработчик.
                    result.add(new PrimaryHyperlink(!typeTarget && link instanceof XtextHyperlink xtext
                        ? new HistoryHyperlink(xtext, source, page, title)
                        : new TypeHyperlink(link.getHyperlinkRegion(), title, object, source, page)));
                    if (object instanceof BasicForm form)
                    {
                        // Страница «Данные» редактора формы — это страница владельца, её не предлагаем.
                        IFile module = formModuleFile(form);
                        if (module != null && fileHasContent(module))
                            result.add(new FileHyperlink(link.getHyperlinkRegion(), "Модуль формы", module,
                                source, page));
                    }
                    else if (object instanceof BasicTemplate)
                    {
                        result.add(new TypeHyperlink(link.getHyperlinkRegion(), "Макет", object, source, page));
                    }
                    else
                    {
                        boolean register = object instanceof BasicRegister;
                        // Имена и типы свойств подтверждены в .tmp/bundles/mdclass/model/MdClass.xcore.
                        EStructuralFeature instanceModule =
                            object.eClass().getEStructuralFeature(register ? "recordSetModule" : "objectModule"); //$NON-NLS-1$ //$NON-NLS-2$
                        EStructuralFeature managerModule = object.eClass().getEStructuralFeature("managerModule"); //$NON-NLS-1$
                        String dataPage = dataPageId(object, page);
                        if (instanceModule != null && moduleHasContent(object, instanceModule))
                            result.add(new TypeHyperlink(link.getHyperlinkRegion(),
                                register ? "Модуль набора записей" : "Модуль объекта",
                                object, instanceModule, source, page));
                        if (managerModule != null && moduleHasContent(object, managerModule))
                            result.add(new TypeHyperlink(link.getHyperlinkRegion(), "Модуль менеджера",
                                object, managerModule, source, page));
                        if (dataPage != null)
                            result.add(new DataHyperlink(link.getHyperlinkRegion(), object, dataPage, source, page));
                    }
                    if (object instanceof DefinedType definedType && definedType.getType() != null
                        && !definedType.getType().getTypes().isEmpty())
                        result.add(new CompositionHyperlink(link.getHyperlinkRegion(), definedType, source));
                    result.add(new NavigatorHyperlink(link.getHyperlinkRegion(), object));
                }
                return result.toArray(IHyperlink[]::new);
            });
            return extended != null ? extended : links;
        }
        catch (RuntimeException | LinkageError e)
        {
            return links;
        }
    }

    /** Не разрешаем ссылку на Module: для меню достаточно файла и открытого буфера. */
    private static boolean moduleHasContent(EObject target, EStructuralFeature feature)
    {
        Object value = target.eGet(feature, false);
        if (!(value instanceof EObject module))
            return false;
        IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
        if (lookup == null)
            return true;
        IFile file = lookup.getPlatformResource(module);
        return file == null || fileHasContent(file);
    }

    private static boolean fileHasContent(IFile file)
    {
        var buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
        if (buffer != null && buffer.getDocument() != null)
            return !buffer.getDocument().get().isBlank();
        if (!file.exists())
            return false;
        String text = BslModuleMethodResolver.moduleText(file);
        // При ошибке чтения не считаем модуль пустым.
        return text == null || !text.isBlank();
    }

    /** Модуль формы лежит в папке формы (у общей формы — в папке объекта). */
    private static IFile formModuleFile(BasicForm form)
    {
        IResource folder = NavigatorResourceResolver.resolve(form);
        return folder instanceof IContainer container ? container.getFile(new Path("Module.bsl")) : null; //$NON-NLS-1$
    }

    private static String dataPageId(EObject target, IWorkbenchPage page)
    {
        EditorPageDescriptor descriptor = dataPageDescriptor(target, page);
        return descriptor == null ? null : descriptor.getPageId();
    }

    private static EditorPageDescriptor dataPageDescriptor(EObject target, IWorkbenchPage page)
    {
        OpenHelper helper = new OpenHelper(page);
        var input = helper.getEditorInput(target, null, null);
        if (input == null)
            return null;
        String editorId = helper.getEditorId(input);
        if (editorId == null)
            return null;
        // Тот же реестр, которым DtGranularEditor.addPages наполняет редактор.
        for (EditorPageDescriptor descriptor : IEditorPagesRegistry.INSTANCE.getEditorPages(editorId, target))
            if (descriptor.getPageId().endsWith(".pages.data")) //$NON-NLS-1$
                return descriptor;
        return null;
    }

    private static final class DataHyperlink implements IHyperlink
    {
        private final IRegion region;
        private final MdObject target;
        private final String pageId;
        private final BslXtextEditor source;
        private final IWorkbenchPage page;

        DataHyperlink(IRegion region, MdObject target, String pageId, BslXtextEditor source, IWorkbenchPage page)
        {
            this.region = region;
            this.target = target;
            this.pageId = pageId;
            this.source = source;
            this.page = page;
        }

        @Override public IRegion getHyperlinkRegion() { return region; }
        @Override public String getTypeLabel() { return null; }
        @Override public String getHyperlinkText() { return "Данные"; }

        @Override
        public void open()
        {
            try (HistoryScope scope = new HistoryScope(page, source))
            {
                // Редактор выбирает начальную страницу по свойству во входе
                // (DtGranularEditor.getPageForFeatureOrDefault), поэтому передаём свойство
                // страницы «Данные» — тогда первая страница не мелькает.
                EditorPageDescriptor descriptor = dataPageDescriptor(target, page);
                List<EStructuralFeature> features = descriptor == null ? null : descriptor.getFeatures();
                EStructuralFeature feature = features == null || features.isEmpty() ? null : features.get(0);
                IEditorPart editor = feature != null ? new OpenHelper(page).openEditor(target, feature)
                    : new OpenHelper(page).openEditor(target);
                if (editor instanceof DtGranularEditor<?> granular)
                {
                    scope.markOpened();
                    // Только что открытый редактор сначала показывает страницу загрузки и
                    // добавляет свои страницы позже — до этого setActivePage возвращает null.
                    if (granular.setActivePage(pageId) == null)
                        retryActivatePage(granular, pageId, 1);
                }
            }
        }

        private static void retryActivatePage(DtGranularEditor<?> editor, String pageId, int attempt)
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed() || attempt > 40)
                return;
            display.timerExec(100, () ->
            {
                if (editor.getSite() == null || editor.getSite().getPage() == null
                    || editor.getSite().getPage().findEditor(editor.getEditorInput()) != editor)
                    return;
                if (editor.setActivePage(pageId) == null)
                    retryActivatePage(editor, pageId, attempt + 1);
            });
        }
    }

    /** Переход по клику на саму ссылку; в списке выбора ссылок не показывается. */
    static boolean isPrimary(IHyperlink link)
    {
        return link instanceof PrimaryHyperlink;
    }

    private static final class PrimaryHyperlink implements IHyperlink
    {
        private final IHyperlink delegate;

        PrimaryHyperlink(IHyperlink delegate)
        {
            this.delegate = delegate;
        }

        @Override public IRegion getHyperlinkRegion() { return delegate.getHyperlinkRegion(); }
        @Override public String getTypeLabel() { return delegate.getTypeLabel(); }
        @Override public String getHyperlinkText() { return delegate.getHyperlinkText(); }
        @Override public void open() { delegate.open(); }
    }

    private static final class NavigatorHyperlink implements IHyperlink
    {
        private final IRegion region;
        private final MdObject target;

        NavigatorHyperlink(IRegion region, MdObject target)
        {
            this.region = region;
            this.target = target;
        }

        @Override public IRegion getHyperlinkRegion() { return region; }
        @Override public String getTypeLabel() { return null; }
        @Override public String getHyperlinkText() { return "В навигаторе"; }
        @Override public void open() { NavigatorReveal.revealAndActivateIfHidden(target); }
    }

    /** Состав определяемого типа — тот же просмотр, что «Открыть типы» в меню ячейки типа. */
    private static final class CompositionHyperlink implements IHyperlink
    {
        private final IRegion region;
        private final DefinedType target;
        private final BslXtextEditor source;

        CompositionHyperlink(IRegion region, DefinedType target, BslXtextEditor source)
        {
            this.region = region;
            this.target = target;
            this.source = source;
        }

        @Override public IRegion getHyperlinkRegion() { return region; }
        @Override public String getTypeLabel() { return null; }
        @Override public String getHyperlinkText() { return "Состав"; }
        @Override public void open() { SelectTypeDialogHook.openReadOnly(source.getSite().getShell(), target.getType(), target); }
    }

    private static final class FileHyperlink implements IHyperlink
    {
        private final IRegion region;
        private final String name;
        private final IFile file;
        private final BslXtextEditor source;
        private final IWorkbenchPage page;

        FileHyperlink(IRegion region, String name, IFile file, BslXtextEditor source, IWorkbenchPage page)
        {
            this.region = region;
            this.name = name;
            this.file = file;
            this.source = source;
            this.page = page;
        }

        @Override public IRegion getHyperlinkRegion() { return region; }
        @Override public String getTypeLabel() { return null; }
        @Override public String getHyperlinkText() { return name; }

        @Override
        public void open()
        {
            try (HistoryScope scope = new HistoryScope(page, source))
            {
                if (GoToDefinition.openBslModuleGranularEditor(file) != null)
                    scope.markOpened();
            }
        }
    }

    private static IHyperlink[] callingModuleLinks(ITextViewer viewer, IHyperlink[] links,
        BslXtextEditor source, IWorkbenchPage page, CallingModuleContext context)
    {
        if (context == null)
            return links;
        IFile caller = source.getEditorInput().getAdapter(IFile.class);
        String name = BslCallingModules.moduleName(caller);
        if (name == null || !(viewer.getDocument() instanceof IXtextDocument document))
            return links;
        List<CallingModuleCandidate> candidates = readModel(document, true, resource -> {
            if (resource == null || resource.getResourceSet() == null)
                return List.of();
            List<CallingModuleCandidate> found = new ArrayList<>();
            for (IHyperlink link : links)
            {
                IHyperlink original = link instanceof HistoryHyperlink history ? history.delegate : link;
                if (!(original instanceof XtextHyperlink xtext) || xtext.getURI() == null
                    || xtext.getURI().trimFragment().equals(resource.getURI().trimFragment()))
                    continue;
                // Команда предлагается только на самом вызове, а не на имени в комментарии.
                EObject at = BslNodeLookup.resolveContainedElementAt(resource, link.getHyperlinkRegion().getOffset());
                var call = org.eclipse.xtext.EcoreUtil2.getContainerOfType(at,
                    com._1c.g5.v8.dt.bsl.model.Invocation.class);
                if (call == null || call.getParams().stream().noneMatch(java.util.Objects::nonNull))
                    continue;
                EObject target = resource.getResourceSet().getEObject(xtext.getURI(), true);
                var method = ParamHintHtmlModifier.ParameterSeeLinks.sourceMethod(target);
                if (method == null || method.eIsProxy() || method.eResource() == null)
                    continue;
                found.add(new CallingModuleCandidate(link, xtext, method));
            }
            return found;
        });
        // Модель исходного модуля уже отпущена: чтение цели не задерживает штатный переход.
        if (candidates == null || candidates.isEmpty())
            return links;
        List<IHyperlink> result = new ArrayList<>(List.of(links));
        java.util.Set<String> added = new java.util.HashSet<>();
        for (CallingModuleCandidate candidate : candidates)
        {
            BslNodeLookup.checkCanceled();
            var method = candidate.method();
            IHyperlink link = candidate.link();
            result.set(result.indexOf(link), new HistoryHyperlink(candidate.xtext(), source, page, "Перейти"));
            IFile targetFile = ForeignModuleModel.fileOf(method.eResource().getURI());
            IXtextDocument targetDocument = context.document(targetFile);
            CallingModuleAvailability availability;
            if (targetDocument != null)
                availability = ContentAssistSessionReloader.readOnlyPeekAst(targetDocument, targetResource -> {
                    if (!(BslNodeLookup.rootAst(targetResource) instanceof com._1c.g5.v8.dt.bsl.model.Module module))
                        return null;
                    var parse = targetResource.getParseResult();
                    if (parse == null || !parse.getRootNode().getText().equals(targetDocument.get()))
                        return null;
                    for (var iterator = module.eAllContents(); iterator.hasNext();)
                        if (iterator.next() instanceof com._1c.g5.v8.dt.bsl.model.Method current
                            && method.getName().equalsIgnoreCase(current.getName()))
                            return new CallingModuleAvailability(BslCallingModules.canAdd(current, targetDocument),
                                BslCallingModules.names(current).stream().anyMatch(name::equalsIgnoreCase));
                    return null;
                });
            else
                availability = new CallingModuleAvailability(BslCallingModules.canAdd(method, null),
                    BslCallingModules.names(method).stream().anyMatch(name::equalsIgnoreCase));
            if (availability == null)
            {
                context.incomplete = true;
            }
            boolean eligible = availability != null && availability.eligible();
            boolean exists = availability != null && availability.exists();
            if (eligible && !exists && added.add(EcoreUtil.getURI(method).toString()))
                result.add(new AddCallingModuleHyperlink(link.getHyperlinkRegion(),
                    EcoreUtil.getURI(method), method.getName(), name, source, page));
        }
        return result.toArray(IHyperlink[]::new);
    }

    private record CallingModuleCandidate(IHyperlink link, XtextHyperlink xtext,
        com._1c.g5.v8.dt.bsl.model.Method method) {}

    private record CallingModuleAvailability(boolean eligible, boolean exists) {}

    /** Документы захватываются в UI, фон читает готовую модель настоящего редактора. */
    private static final class CallingModuleContext
    {
        private record Dependency(IFile file, IXtextDocument document, String stamp) {}
        private final java.util.Map<IFile, Dependency> open;
        private final java.util.Map<IFile, Dependency> used = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile boolean incomplete;

        private CallingModuleContext(java.util.Map<IFile, Dependency> open)
        {
            this.open = open;
        }

        static CallingModuleContext capture()
        {
            java.util.Map<IFile, Dependency> open = new java.util.HashMap<>();
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                for (IWorkbenchPage page : window.getPages())
                    for (var reference : page.getEditorReferences())
                    {
                        IEditorPart part = reference.getEditor(false);
                        BslXtextEditor editor = part == null ? null : GetRef.getActiveBslEditor(part);
                        if (editor == null || editor.getEditorInput() == null)
                            continue;
                        IFile file = editor.getEditorInput().getAdapter(IFile.class);
                        IXtextDocument document = editor.getDocument();
                        if (file != null && document != null)
                            open.put(file, new Dependency(file, document, ForeignModuleModel.stamp(file, document)));
                    }
            return new CallingModuleContext(open);
        }

        IXtextDocument document(IFile file)
        {
            if (file == null)
                return null;
            Dependency dependency = open.get(file);
            if (dependency == null)
                dependency = new Dependency(file, null, ForeignModuleModel.stamp(file, null));
            used.put(file, dependency);
            return dependency.document();
        }

        /** Только UI. Проверяется также открытие/закрытие редактора цели. */
        boolean isCurrent()
        {
            if (incomplete)
                return false;
            for (Dependency dependency : used.values())
            {
                IXtextDocument document = ForeignModuleModel.openDocument(dependency.file());
                String stamp = ForeignModuleModel.stamp(dependency.file(), document);
                if (document != dependency.document() || !stamp.equals(dependency.stamp()))
                {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class AddCallingModuleHyperlink implements IHyperlink
    {
        private final IRegion region;
        private final org.eclipse.emf.common.util.URI target;
        private final String methodName;
        private final String caller;
        private final BslXtextEditor source;
        private final IWorkbenchPage page;

        AddCallingModuleHyperlink(IRegion region, org.eclipse.emf.common.util.URI target, String methodName,
            String caller, BslXtextEditor source, IWorkbenchPage page)
        {
            this.region = region;
            this.target = target;
            this.methodName = methodName;
            this.caller = caller;
            this.source = source;
            this.page = page;
        }

        @Override public IRegion getHyperlinkRegion() { return region; }
        @Override public String getTypeLabel() { return null; }
        @Override public String getHyperlinkText() { return "Добавить в вызывающие модули"; }

        @Override
        public void open()
        {
            try (HistoryScope history = new HistoryScope(page, source))
            {
                IFile file = ForeignModuleModel.fileOf(target.trimFragment());
                if (file == null)
                    return;
                IEditorPart part = org.eclipse.ui.ide.IDE.openEditor(page, file);
                if (part != null)
                    history.markOpened();
                BslXtextEditor editor = part != null ? GetRef.getActiveBslEditor(part) : null;
                if (editor == null || !editor.isEditable())
                    return;
                IXtextDocument document = editor.getDocument();
                BslCallingModules.Edit edit = document.readOnly(resource -> {
                    if (resource == null || resource.getContents().isEmpty()
                        || !(resource.getContents().get(0) instanceof com._1c.g5.v8.dt.bsl.model.Module module))
                        return null;
                    for (var method : module.allMethods())
                        if (methodName.equalsIgnoreCase(method.getName()))
                            return BslCallingModules.addition(method, document, caller);
                    return null;
                });
                if (edit != null)
                {
                    document.replace(edit.offset(), 0, edit.text());
                    editor.selectAndReveal(edit.offset(), edit.text().length());
                }
            }
            catch (Exception error)
            {
                ToastNotification.show("Вызывающие модули", "Не удалось изменить комментарий: " + error.getMessage(), 5000);
            }
        }
    }

    /**
     * Метод модуля менеджера по тексту «Справочники.Имя.Метод» или общего модуля «Модуль.Метод» —
     * тот же переход, что Ctrl+F12.
     */
    private static final class ManagerMethodHyperlink implements IHyperlink
    {
        private final IRegion region;
        private final String name;
        private final IProject project;
        private final BslXtextEditor source;
        private final IWorkbenchPage page;

        ManagerMethodHyperlink(IRegion region, String name, IProject project, BslXtextEditor source,
            IWorkbenchPage page)
        {
            this.region = region;
            this.name = name;
            this.project = project;
            this.source = source;
            this.page = page;
        }

        @Override public IRegion getHyperlinkRegion() { return region; }
        @Override public String getTypeLabel() { return null; }
        @Override public String getHyperlinkText() { return name; }

        @Override
        public void open()
        {
            try (HistoryScope scope = new HistoryScope(page, source))
            {
                if (GoToDefinition.jump(name, source.getSite().getShell(), page, project, false))
                    scope.markOpened();
            }
        }
    }

    private static final class TypeHyperlink implements IHyperlink
    {
        private final IRegion region;
        private final String name;
        private final MdObject target;
        private final EStructuralFeature moduleFeature;
        private final BslXtextEditor source;
        private final IWorkbenchPage page;

        TypeHyperlink(IRegion region, String name, MdObject target, BslXtextEditor source, IWorkbenchPage page)
        {
            this(region, name, target, null, source, page);
        }

        TypeHyperlink(IRegion region, String name, MdObject target, EStructuralFeature moduleFeature,
            BslXtextEditor source, IWorkbenchPage page)
        {
            this.region = region;
            this.name = name;
            this.target = target;
            this.moduleFeature = moduleFeature;
            this.source = source;
            this.page = page;
        }

        @Override public IRegion getHyperlinkRegion() { return region; }
        @Override public String getTypeLabel() { return null; }
        @Override public String getHyperlinkText() { return name; }

        @Override
        public void open()
        {
            try (HistoryScope scope = new HistoryScope(page, source))
            {
                // OpenHelper.getFile использует feature именно для открытия BSL-модуля
                // (подтверждено в .tmp/bundles/dt-ui/OpenHelper.bytecode.javap.txt).
                if (moduleFeature != null)
                {
                    if (new OpenHelper(page).openEditor(target, moduleFeature) != null)
                        scope.markOpened();
                }
                else
                {
                    CompareConfigOpenObjectHandler.openInEditor(target, source, source.getSite().getShell());
                    scope.markOpened();
                }
            }
        }
    }

    private static void attach(Event event)
    {
        if (!(event.widget instanceof StyledText text) || text.isDisposed()
            || (event.stateMask & SWT.MOD1) == 0 || !PlatformUI.isWorkbenchRunning())
            return;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        BslXtextEditor editor = page == null ? null : GetRef.getActiveBslEditor(page.getActivePart());
        if (editor == null)
            return;
        ITextViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null || viewer.getTextWidget() != text)
            return;
        Object value = Global.getField(viewer, "fHyperlinkDetectors");
        if (!(value instanceof IHyperlinkDetector[] detectors))
            return;
        // TextViewer и HyperlinkManager используют один массив (проверено в
        // .tmp/bundles/jface-text/). setHyperlinkDetectors вызвал бы dispose
        // действующих детекторов. Меняем только элементы общего массива.
        synchronized (detectors)
        {
            for (int i = 0; i < detectors.length; i++)
            {
                IHyperlinkDetector detector = detectors[i];
                if (detector == null || detector instanceof HistoryDetector
                    || !"com._1c.g5.v8.dt.bsl.ui.editor.CustomHyperlinkDetector"
                        .equals(detector.getClass().getName()))
                    continue;
                detectors[i] = detector instanceof IHyperlinkDetectorExtension2
                    ? new ModifiedHistoryDetector(detector, editor, page)
                    : new HistoryDetector(detector, editor, page);
            }
        }
    }

    private static class HistoryDetector implements IHyperlinkDetector, IHyperlinkDetectorExtension
    {
        final IHyperlinkDetector delegate;
        private final BslXtextEditor source;
        private final IWorkbenchPage page;

        HistoryDetector(IHyperlinkDetector delegate, BslXtextEditor source, IWorkbenchPage page)
        {
            this.delegate = delegate;
            this.source = source;
            this.page = page;
        }

        /** Ссылки, досчитанные в фоне для последнего положения указателя. */
        private volatile DetectedLinks detected;
        /** Только UI-поток. */
        private Job pendingJob;
        private long pendingStamp;
        private int pendingOffset;
        private boolean pendingMultiple;
        private CallingModuleContext pendingContext;
        private volatile long generation;
        private volatile boolean disposed;
        private final ISchedulingRule detectionRule = new ISchedulingRule()
        {
            @Override public boolean contains(ISchedulingRule rule) { return rule == this; }
            @Override public boolean isConflicting(ISchedulingRule rule) { return rule == this; }
        };

        @Override
        public IHyperlink[] detectHyperlinks(ITextViewer viewer, IRegion region, boolean multiple)
        {
            // Наведение с Ctrl идёт в UI-потоке, а блокировку документа секундами держат
            // проверки и раскраска (и tryReadOnly её ждёт). Свои ссылки читают модель модуля —
            // в UI берём только готовый результат, промах досчитываем в фоне.
            if (region == null || Display.getCurrent() == null
                || !(viewer.getDocument() instanceof IDocumentExtension4 document))
                return detect(viewer, region, multiple,
                    () -> coveringOnly(delegate.detectHyperlinks(viewer, region, multiple), region));
            long stamp = document.getModificationStamp();
            DetectedLinks ready = detected;
            if (ready != null && ready.stamp() == stamp && ready.multiple() == multiple
                && ready.covers(region.getOffset()) && (ready.context() == null || ready.context().isCurrent()))
                return ready.links() == null ? null : ready.links().clone();
            IHyperlink[] stock = coveringOnly(delegate.detectHyperlinks(viewer, region, multiple), region);
            DeferredFormMemberHyperlink deferred = deferredFormMemberLink(source, page, viewer, region);
            IHyperlink[] result = deferred != null ? new IHyperlink[] { deferred }
                : stock == null ? null : stock.clone();
            if (deferred == null && result != null)
                for (int i = 0; i < result.length; i++)
                    if (result[i] instanceof XtextHyperlink link)
                        result[i] = new HistoryHyperlink(link, source, page);
            scheduleDetect(viewer, region, multiple, stock, result, document, stamp);
            return result;
        }

        /**
         * Штатная ссылка литерала «Обработка.Имя.Форма» на «Форма» ведёт на объект, но её
         * область — только «Обработка.Имя»: подчёркнуто не то, что под указателем, и список
         * выбора закрывается при движении к нему. Такие ссылки отбрасываем — тогда работает
         * {@link #textLink} с подсветкой имени целиком.
         */
        private static IHyperlink[] coveringOnly(IHyperlink[] links, IRegion region)
        {
            if (links == null || region == null)
                return links;
            List<IHyperlink> result = new ArrayList<>(links.length);
            int offset = region.getOffset();
            for (IHyperlink link : links)
            {
                IRegion area = link == null ? null : link.getHyperlinkRegion();
                if (link != null && (area == null
                    || offset >= area.getOffset() && offset <= area.getOffset() + area.getLength()))
                    result.add(link);
            }
            return result.size() == links.length ? links : result.toArray(IHyperlink[]::new);
        }

        private void scheduleDetect(ITextViewer viewer, IRegion region, boolean multiple, IHyperlink[] stock,
            IHyperlink[] shown, IDocumentExtension4 document, long stamp)
        {
            if (disposed)
                return;
            if (pendingJob != null && pendingJob.getState() != Job.NONE && pendingStamp == stamp
                && pendingOffset == region.getOffset() && pendingMultiple == multiple
                && (pendingContext == null || pendingContext.isCurrent()))
            {
                return;
            }
            if (pendingJob != null)
                pendingJob.cancel();
            long requestedGeneration = ++generation;
            CallingModuleContext context = multiple ? CallingModuleContext.capture() : null;
            Job job = Job.create("Ссылки BSL-редактора", monitor -> //$NON-NLS-1$
            {
                if (monitor.isCanceled() || document.getModificationStamp() != stamp)
                    return;
                IHyperlink[] links;
                try
                {
                    links = BslNodeLookup.compute(monitor,
                        () -> detect(viewer, region, multiple, () -> stock));
                    if (monitor.isCanceled() || document.getModificationStamp() != stamp)
                        return;
                    if (context != null && links != null && links.length > 0)
                    {
                        queuePublish(viewer, document,
                            new DetectedLinks(stamp, region.getOffset(), multiple, links, context),
                            shown, requestedGeneration, true);
                        IHyperlink[] primary = links;
                        links = BslNodeLookup.compute(monitor,
                            () -> callingModuleLinks(viewer, primary, source, page, context));
                    }
                }
                catch (OperationCanceledException canceled)
                {
                    return;
                }
                catch (RuntimeException | LinkageError failure)
                {
                    return;
                }
                finally
                {
                    if (context != null)
                        context.open.clear();
                }
                if (monitor.isCanceled() || document.getModificationStamp() != stamp)
                    return;
                DetectedLinks result = new DetectedLinks(stamp, region.getOffset(), multiple, links, context);
                queuePublish(viewer, document, result, shown, requestedGeneration, false);
            });
            job.setSystem(true);
            job.setRule(detectionRule);
            pendingJob = job;
            pendingStamp = stamp;
            pendingOffset = region.getOffset();
            pendingMultiple = multiple;
            pendingContext = context;
            job.schedule();
        }

        private void queuePublish(ITextViewer viewer, IDocumentExtension4 document, DetectedLinks result,
            IHyperlink[] shown, long requestedGeneration, boolean primary)
        {
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> {
                    if (!disposed && generation == requestedGeneration)
                        publish(viewer, document, result, shown, primary);
                });
        }

        /**
         * Готовый результат — в кэш и синтетическое движение указателя, чтобы менеджер его показал.
         * Ctrl+клик без наведения с Ctrl ищет ссылки прямо в {@code mouseDown} и сразу открывает
         * список из штатных {@code shown}; при открытом списке менеджер движение указателя
         * не обрабатывает — подписи меняем в самом списке.
         */
        private void publish(ITextViewer viewer, IDocumentExtension4 document, DetectedLinks result,
            IHyperlink[] shown, boolean primary)
        {
            if (document.getModificationStamp() != result.stamp()
                || !primary && result.context() != null && !result.context().isCurrent())
                return;
            StyledText text = viewer.getTextWidget();
            if (text == null || text.isDisposed())
                return;
            IHyperlink[] links = result.links();
            if (links == null || links.length == 0)
            {
                // Разбор литерала-запроса идёт в скрытом редакторе запроса — только в UI-потоке
                // (в фоне queryLink возвращает null), как и до досчёта в фоне.
                IHyperlink query = BslQueryLiteralSupport.queryLink(source, viewer, result.offset());
                if (query != null)
                    result = new DetectedLinks(result.stamp(), result.offset(), result.multiple(),
                        new IHyperlink[] { new HistoryHyperlink(query, source, page) }, result.context());
            }
            detected = result;
            if (!text.isFocusControl())
            {
                return;
            }
            relabelShownLinks(text, shown, result.links());
            TextEditorCtrlClickSelectWordHook.pokeHyperlinkManager(text);
        }

        private IHyperlink[] detect(ITextViewer viewer, IRegion region, boolean multiple,
            Supplier<IHyperlink[]> stock)
        {
            BslNodeLookup.checkCanceled();
            IHyperlink property = region == null ? null : propertyLink(source, page, viewer, region.getOffset());
            if (property != null)
                return new IHyperlink[] { property };
            BslNodeLookup.checkCanceled();
            // Штатные ссылки литерала («ПолучитьФорму») ведут на имя целиком: на «Обработка1»
            // в имени формы предложили бы форму.
            FORM_MEMBER_PATH.set(Boolean.FALSE);
            IHyperlink segment = region == null ? null : segmentLink(source, page, viewer, region.getOffset());
            boolean formMember = Boolean.TRUE.equals(FORM_MEMBER_PATH.get());
            FORM_MEMBER_PATH.remove();
            // Путь «…Форма.<Имя>.<Реквизит>…»: штатная ссылка ведёт на форму — её не берём,
            // даже если наш переход не разрешился.
            IHyperlink[] links = segment != null ? new IHyperlink[] { segment }
                : formMember ? null : stock.get();
            // #region agent log
            StringBuilder linksLog = new StringBuilder();
            if (links != null)
                for (IHyperlink link : links)
                    linksLog.append(link == null ? "null" : link.getClass().getName() + "'" + link.getHyperlinkText() //$NON-NLS-1$ //$NON-NLS-2$
                        + "'@" + link.getHyperlinkRegion()).append(' '); //$NON-NLS-1$
            Global.tempLog("literal-common-module", "detect offset=" + (region == null ? -1 : region.getOffset()) //$NON-NLS-1$ //$NON-NLS-2$
                + " multiple=" + multiple + " segment=" + (segment != null) + " links=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + (links == null ? "null" : "[" + linksLog + "]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // #endregion
            BslNodeLookup.checkCanceled();
            IHyperlink description = multiple && region != null
                ? parameterDescriptionLink(source, page, viewer, region.getOffset()) : null;
            if (links == null || links.length == 0)
            {
                IHyperlink type = region == null ? null : textLink(source, page, viewer, region.getOffset());
                if (type == null)
                    return description == null ? links : new IHyperlink[] { description };
                links = new IHyperlink[] { type };
            }
            IHyperlink[] result = multiple ? metadataLinks(viewer, links, source, page) : links.clone();
            if (description != null)
            {
                IHyperlink[] extended = java.util.Arrays.copyOf(result, result.length + 1);
                extended[result.length] = description;
                result = extended;
            }
            BslNodeLookup.checkCanceled();
            if (multiple)
                compactVariableLinks(viewer, result);
            for (int i = 0; i < result.length; i++)
            {
                // Исходную позицию сохраняем и для BSL: при открытии другого модуля
                // selectAndReveal отмечает уже его, а не редактор, из которого ушли.
                if (result[i] instanceof XtextHyperlink link)
                    result[i] = new HistoryHyperlink(link, source, page);
                else if (result[i] instanceof VariableHyperlink variable)
                    result[i] = new VariableHyperlink(
                        new HistoryHyperlink(variable.delegate, source, page), variable.text);
            }
            return result;
        }

        @Override
        public void dispose()
        {
            disposed = true;
            cancelPending();
            if (delegate instanceof IHyperlinkDetectorExtension extension)
                extension.dispose();
        }

        private void cancelPending()
        {
            generation++;
            if (pendingJob != null)
                pendingJob.cancel();
            detected = null;
        }
    }

    private record DetectedLinks(long stamp, int offset, boolean multiple, IHyperlink[] links,
        CallingModuleContext context)
    {
        /**
         * Ссылки покрывают всё слово под указателем, а не одно смещение: указатель на другой
         * букве того же слова — тот же результат. Без ссылок области нет — только то же смещение.
         */
        boolean covers(int at)
        {
            if (at == offset)
                return true;
            if (links == null)
                return false;
            for (IHyperlink link : links)
            {
                IRegion area = link == null ? null : link.getHyperlinkRegion();
                if (area == null || at < area.getOffset() || at > area.getOffset() + area.getLength())
                    return false;
            }
            return links.length > 0;
        }
    }

    /** Подписи EDT обрезаны до 25 символов: восстанавливаем выражение из AST, issue 848. */
    private static void compactVariableLinks(ITextViewer viewer, IHyperlink[] links)
    {
        if (!(viewer.getDocument() instanceof IXtextDocument document))
            return;
        try
        {
            document.tryReadOnly(resource ->
            {
                if (resource == null)
                    return null;
                for (int i = 0; i < links.length; i++)
                {
                    if (!(links[i] instanceof XtextHyperlink link) || link.getURI() == null
                        || !resource.getURI().equals(link.getURI().trimFragment()))
                        continue;
                    EObject target = resource.getEObject(link.getURI().fragment());
                    if (target instanceof com._1c.g5.v8.dt.bsl.model.FormalParam)
                    {
                        links[i] = new VariableHyperlink(link, "Объявление");
                        continue;
                    }
                    if (!(target instanceof StaticFeatureAccess access))
                        continue;
                    Statement statement = org.eclipse.xtext.EcoreUtil2.getContainerOfType(access, Statement.class);
                    if (!(statement instanceof SimpleStatement assignment) || assignment.getLeft() != access
                        || assignment.getRight() == null)
                        continue;
                    INode expression = NodeModelUtils.findActualNodeFor(assignment.getRight());
                    INode statementNode = NodeModelUtils.findActualNodeFor(statement);
                    if (expression == null || statementNode == null)
                        continue;
                    String text = expression.getText().strip().replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
                    String terminator = assignmentTerminator(statementNode);
                    links[i] = new VariableHyperlink(link, "= " + text + terminator
                        + " (№" + statementNode.getStartLine() + ")");
                }
                return null;
            });
        }
        catch (RuntimeException e)
        {
            // При изменении модели во время наведения сохраняем штатные подписи.
        }
    }

    private static String assignmentTerminator(INode statementNode)
    {
        // Bsl.xtext: statements += Statement SEMICOLON; SimpleStatement не включает «;».
        for (INode node = statementNode; node != null;)
        {
            if (!node.hasNextSibling())
            {
                node = node.getParent();
                continue;
            }
            node = node.getNextSibling();
            for (ILeafNode leaf : node.getLeafNodes())
            {
                if (leaf.isHidden())
                    continue;
                return ";".equals(leaf.getText()) ? ";" : ""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * LinkListInformationControl заполняет таблицу до Shell.setVisible(true)
     * (проверено в .tmp/bundles/jface-text/). Измеряем штатные подписи до показа.
     * Полосы прокрутки остаются под управлением штатного контрола JFace.
     */
    private static void showVariableLinks(Event event)
    {
        if (!(event.widget instanceof Shell shell) || shell.isDisposed() || (shell.getStyle() & SWT.TOOL) == 0)
            return;
        Table table = variableLinksTable(shell);
        if (table == null)
            return;
        int stockTextWidth = 0;
        int contentTextWidth = 0;
        GC gc = new GC(table);
        try
        {
            gc.setFont(table.getFont());
            for (TableItem item : table.getItems())
            {
                String original = item.getData() instanceof VariableHyperlink link
                    ? link.delegate.getHyperlinkText() : item.getText();
                stockTextWidth = Math.max(stockTextWidth,
                    gc.textExtent(original == null ? "" : original, SWT.DRAW_TRANSPARENT).x); //$NON-NLS-1$
                contentTextWidth = Math.max(contentTextWidth,
                    gc.textExtent(item.getText(), SWT.DRAW_TRANSPARENT).x);
            }
        }
        finally
        {
            gc.dispose();
        }
        int contentWidth = shell.computeSize(SWT.DEFAULT, SWT.DEFAULT, true).x;
        // Рамка и отступы одинаковы для исходных и новых подписей. Не меняем текст
        // TableItem ради замера: нативная таблица сохраняет прежнюю ширину элементов.
        int stockWidth = Math.max(1, contentWidth - contentTextWidth + stockTextWidth);
        placeVariableLinks(shell, table, Math.min(contentWidth, stockWidth * 2));
    }

    /**
     * Список, уже открытый из штатных ссылок {@code shown} (Ctrl+клик до досчёта в фоне):
     * строки, чьи ссылки досчитаны, получают подписи {@link VariableHyperlink}. Данные строк
     * не меняем — ими владеет TableViewer списка, а открытие у ссылок одно и то же.
     */
    private static void relabelShownLinks(StyledText text, IHyperlink[] shown, IHyperlink[] links)
    {
        if (shown == null || shown.length == 0 || links == null)
            return;
        java.util.Map<IHyperlink, String> labels = new java.util.IdentityHashMap<>();
        for (IHyperlink link : links)
            if (link instanceof VariableHyperlink variable && variable.delegate instanceof HistoryHyperlink history)
                labels.put(history.delegate, variable.text);
        if (labels.isEmpty())
            return;
        for (Shell shell : text.getShell().getShells())
        {
            if (shell.isDisposed() || !shell.isVisible() || (shell.getStyle() & SWT.TOOL) == 0)
                continue;
            Table table = shownLinksTable(shell, shown);
            if (table == null)
                continue;
            int stockTextWidth = 0;
            int contentTextWidth = 0;
            int changed = 0;
            GC gc = new GC(table);
            try
            {
                gc.setFont(table.getFont());
                for (TableItem item : table.getItems())
                {
                    String stockText = item.getText();
                    String label = item.getData() instanceof HistoryHyperlink history
                        ? labels.get(history.delegate) : null;
                    if (label != null && !label.equals(stockText))
                    {
                        item.setText(label);
                        changed++;
                    }
                    stockTextWidth = Math.max(stockTextWidth, gc.textExtent(stockText, SWT.DRAW_TRANSPARENT).x);
                    contentTextWidth = Math.max(contentTextWidth,
                        gc.textExtent(item.getText(), SWT.DRAW_TRANSPARENT).x);
                }
            }
            finally
            {
                gc.dispose();
            }
            if (changed == 0)
                return;
            // Окно уже подогнано под штатные подписи. Не сужаем: нативная таблица
            // не уменьшает ширину строк после setText — сужение дало бы прокрутку.
            int width = shell.getBounds().width;
            placeVariableLinks(shell, table,
                Math.max(width, Math.min(width - stockTextWidth + contentTextWidth, width * 2)));
            return;
        }
    }

    /** Таблица открытого списка, все строки которого — ссылки {@code shown} (по экземпляру). */
    private static Table shownLinksTable(Composite parent, IHyperlink[] shown)
    {
        for (Control child : parent.getChildren())
        {
            if (child instanceof Table table)
            {
                if (table.getItemCount() == 0)
                    continue;
                boolean all = true;
                for (TableItem item : table.getItems())
                {
                    boolean found = false;
                    for (IHyperlink link : shown)
                        found |= item.getData() == link;
                    all &= found;
                }
                if (all)
                    return table;
            }
            else if (child instanceof Composite composite)
            {
                Table found = shownLinksTable(composite, shown);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static void placeVariableLinks(Shell shell, Table table, int desiredWidth)
    {
        if (table.getLayoutData() instanceof GridData data)
            data.horizontalAlignment = SWT.FILL;
        Rectangle bounds = shell.getBounds();
        Rectangle screen = shell.getMonitor().getClientArea();
        int viewportHeight = table.getClientArea().height;
        int width = Math.min(desiredWidth, screen.width);
        int x = Math.max(screen.x, Math.min(bounds.x, screen.x + screen.width - width));
        shell.setBounds(x, bounds.y, width, bounds.height);
        shell.layout(true, true);
        // Сужение добавляет горизонтальную полосу уже после штатного расчёта высоты.
        // Сохраняем прежнюю область строк, компенсируя только занятое полосой место.
        int lostViewportHeight = Math.max(0, viewportHeight - table.getClientArea().height);
        int height = Math.min(screen.height, bounds.height + lostViewportHeight);
        if (height != bounds.height)
        {
            int y = Math.max(screen.y, Math.min(bounds.y, screen.y + screen.height - height));
            shell.setBounds(x, y, width, height);
            shell.layout(true, true);
        }
    }

    /**
     * Ctrl+клик открывает первую ссылку (HyperlinkManager), а список показывает все
     * (MultipleHyperlinkPresenter, .tmp/bundles/jface-text/). Строку основного перехода
     * убираем из уже заполненной таблицы до показа и укорачиваем окно на эту строку.
     */
    private static void hidePrimaryLink(Event event)
    {
        if (!(event.widget instanceof Shell shell) || shell.isDisposed() || (shell.getStyle() & SWT.TOOL) == 0)
            return;
        Table table = primaryLinksTable(shell);
        if (table == null)
            return;
        int itemHeight = table.getItemHeight();
        // Без прокрутки все строки видны — окно короче на одну строку; с прокруткой высота прежняя.
        boolean fits = table.getItemCount() * itemHeight <= table.getClientArea().height;
        table.getItem(0).dispose();
        table.setSelection(0);
        if (fits)
        {
            Rectangle bounds = shell.getBounds();
            shell.setSize(bounds.width, bounds.height - itemHeight);
            shell.layout(true, true);
        }
    }

    private static Table primaryLinksTable(Composite parent)
    {
        for (Control child : parent.getChildren())
        {
            if (child instanceof Table table)
            {
                if (table.getItemCount() > 1 && table.getItem(0).getData() instanceof PrimaryHyperlink)
                    return table;
            }
            else if (child instanceof Composite composite)
            {
                Table found = primaryLinksTable(composite);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static Table variableLinksTable(Composite parent)
    {
        for (Control child : parent.getChildren())
        {
            if (child instanceof Table table)
            {
                if (table.getItemCount() > 0 && table.getItem(0).getData() instanceof IHyperlink)
                    for (TableItem item : table.getItems())
                        if (item.getData() instanceof VariableHyperlink)
                            return table;
            }
            else if (child instanceof Composite composite)
            {
                Table found = variableLinksTable(composite);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static final class VariableHyperlink implements IHyperlink
    {
        private final IHyperlink delegate;
        private final String text;

        VariableHyperlink(IHyperlink delegate, String text)
        {
            this.delegate = delegate;
            this.text = text;
        }

        @Override public IRegion getHyperlinkRegion() { return delegate.getHyperlinkRegion(); }
        @Override public String getTypeLabel() { return delegate.getTypeLabel(); }
        @Override public String getHyperlinkText() { return text; }
        @Override public void open() { delegate.open(); }
    }

    private static final class ModifiedHistoryDetector extends HistoryDetector
        implements IHyperlinkDetectorExtension2
    {
        ModifiedHistoryDetector(IHyperlinkDetector delegate, BslXtextEditor source, IWorkbenchPage page)
        {
            super(delegate, source, page);
        }

        @Override
        public int getStateMask()
        {
            return ((IHyperlinkDetectorExtension2) delegate).getStateMask();
        }
    }

    private static final class HistoryHyperlink implements IHyperlink
    {
        private final IHyperlink delegate;
        private final String text;
        private final BslXtextEditor source;
        private final IWorkbenchPage page;

        HistoryHyperlink(IHyperlink delegate, BslXtextEditor source, IWorkbenchPage page)
        {
            this(delegate, source, page, delegate.getHyperlinkText());
        }

        HistoryHyperlink(IHyperlink delegate, BslXtextEditor source, IWorkbenchPage page, String text)
        {
            this.delegate = delegate;
            this.text = text;
            this.source = source;
            this.page = page;
        }

        @Override public IRegion getHyperlinkRegion() { return delegate.getHyperlinkRegion(); }
        @Override public String getTypeLabel() { return delegate.getTypeLabel(); }
        @Override public String getHyperlinkText() { return text; }

        @Override
        public void open()
        {
            Global.tempLog("bsl-hyperlink-history", "open delegate=" + delegate.getClass().getName()
                + " target=" + (delegate instanceof XtextHyperlink link ? link.getURI() : delegate.getHyperlinkText()));
            boolean formTarget = isFormTargetLink(delegate);
            try (HistoryScope scope = new HistoryScope(page, source))
            {
                delegate.open();
                scope.markOpened();
                // Как двойной клик по реквизиту в навигаторе: EDT уже выделил строку в списке
                // формы, остаётся только активировать панель «Свойства» после события UI.
                if (formTarget)
                    OpenHelperAttributePropertiesHook.scheduleActivateProperties();
            }
        }

        /**
         * URI перехода внутри формы: {@code bm://…Form#/parameters:Имя},
         * {@code #/formCommands:Имя}, {@code #/items:Группа/items:Элемент} или
         * {@code #/attributes:Реквизит} (набор фрагментов EDT — BslBmReferenceFinder).
         * EDT сам выделяет строку, остаётся активировать панель «Свойства».
         */
        private static boolean isFormTargetLink(IHyperlink link)
        {
            if (!(link instanceof XtextHyperlink xtext) || xtext.getURI() == null)
                return false;
            String fragment = xtext.getURI().fragment();
            return fragment != null && (fragment.startsWith("/parameters:") //$NON-NLS-1$
                || fragment.startsWith("/formCommands:") //$NON-NLS-1$
                || fragment.startsWith("/items:") //$NON-NLS-1$
                || fragment.startsWith("/attributes:")); //$NON-NLS-1$
        }
    }

    /** История переходов плюс гашение popup и фоновой детекции ссылок BSL-редактора. */
    private static final class HistoryScope extends NavigationHistoryScope
    {
        HistoryScope(IWorkbenchPage page, IEditorPart source)
        {
            super(page, source);
            deactivateHyperlinks(source);
        }
    }

    private static void deactivateHyperlinks(IEditorPart source)
    {
        BslXtextEditor editor = GetRef.getActiveBslEditor(source);
        ITextViewer viewer = editor == null ? null : editor.getInternalSourceViewer();
        if (viewer == null)
            return;
        if (Global.getField(viewer, "fHyperlinkDetectors") instanceof IHyperlinkDetector[] detectors)
            for (IHyperlinkDetector detector : detectors)
                if (detector instanceof HistoryDetector pending)
                    pending.cancelPending();
        // TextViewer.fHyperlinkManager и HyperlinkManager.deactivate() проверены
        // в .tmp/bundles/jface-text/: скрывает presenter и сбрасывает fActive.
        Object manager = Global.getField(viewer, "fHyperlinkManager");
        if (manager != null)
            Global.invoke(manager, "deactivate");
    }
}
