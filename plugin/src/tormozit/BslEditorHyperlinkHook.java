package tormozit;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Path;
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
import org.eclipse.ui.INavigationHistory;
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
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.md.resource.MdTypeUtil;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.EditorPageDescriptor;
import com._1c.g5.v8.dt.md.ui.editor.base.IEditorPagesRegistry;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicRegister;
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
            display.addFilter(SWT.Show, BslEditorHyperlinkHook::showVariableLinks);
            ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commands != null)
                commands.addExecutionListener(new OpenDeclarationListener());
        });
    }

    private static final String OPEN_DECLARATION_COMMAND =
        "org.eclipse.xtext.ui.editor.hyperlinking.OpenDeclaration"; //$NON-NLS-1$

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
            return document.readOnly(resource ->
            {
                var target = BslStructureInsertCommentTypes.propertySourceUriAt(resource, offset);
                if (resource.getParseResult() == null)
                    return null;
                ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset);
                if (leaf == null || leaf.isHidden())
                    return null;
                IRegion region = new Region(leaf.getOffset(), leaf.getLength());
                if (target != null)
                    return new PropertyHyperlink(region, target, editor, page);
                return BslQueryResultTypes.isQueryFieldAt(resource, offset)
                    ? new DeferredQueryPropertyHyperlink(region, document, offset, editor, page) : null;
            });
        }
        catch (RuntimeException | LinkageError failure)
        {
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

        PropertyHyperlink(IRegion region, BslStructureInsertCommentTypes.PropertySourceTarget target,
            BslXtextEditor source, IWorkbenchPage page)
        {
            this.region = region;
            this.target = target;
            this.source = source;
            this.page = page;
        }

        @Override public IRegion getHyperlinkRegion() { return region; }
        @Override public String getTypeLabel() { return null; }
        @Override public String getHyperlinkText() { return "Перейти к определению свойства"; }

        @Override
        public void open()
        {
            try (HistoryScope scope = new HistoryScope(page, source))
            {
                BslStructureInsertCommentTypes.openPropertySource(target);
                scope.opened = true;
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
        {
            // Поля, псевдонимы, виртуальные таблицы в литерале-запросе — как в «Редакторе запроса».
            IHyperlink query = BslQueryLiteralSupport.queryLink(editor, viewer, offset);
            link = query == null ? null : new HistoryHyperlink(query, editor, page);
        }
        return link;
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
     * (без хвоста), и без ожидания блокировки документа: вызов идёт до штатного детектора.
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
                return new Object[] { text.substring(start + skip, end), Integer.valueOf(leaf.getOffset() + start),
                    Integer.valueOf(skip), Integer.valueOf(hovered) };
            };
            Object[] found = segmentOnly ? document.tryReadOnly(work) : document.readOnly(work);
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
        ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset);
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
            return document.readOnly(new IUnitOfWork<IHyperlink, XtextResource>()
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
        ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset);
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
    private static IHyperlink[] metadataLinks(ITextViewer viewer, IHyperlink[] links,
        BslXtextEditor source, IWorkbenchPage page)
    {
        if (!(viewer.getDocument() instanceof IXtextDocument document))
            return links;
        try
        {
            // Вызов идёт в UI-потоке при наведении. Штатный readOnly BSL-документа ждёт все
            // задания разрешения ссылок модуля (Job.join) и вешает UI на время долгого расчёта.
            // tryReadOnly такого ожидания не делает: занято — остаются штатные ссылки.
            IHyperlink[] extended = document.tryReadOnly(resource ->
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
                    if (target instanceof BasicForm form)
                    {
                        // Страница «Данные» редактора формы — это страница владельца, её не предлагаем.
                        IFile module = formModuleFile(form);
                        if (module != null && fileHasContent(module))
                            result.add(new FileHyperlink(link.getHyperlinkRegion(), "Модуль формы", module,
                                source, page));
                        result.add(link instanceof XtextHyperlink xtext
                            ? new HistoryHyperlink(xtext, source, page, "Форма")
                            : new TypeHyperlink(link.getHyperlinkRegion(), "Форма", form, source, page));
                        continue;
                    }
                    boolean register = target instanceof BasicRegister;
                    EStructuralFeature instanceModule = target instanceof MdObject
                        ? target.eClass().getEStructuralFeature(register ? "recordSetModule" : "objectModule") : null; //$NON-NLS-1$ //$NON-NLS-2$
                    EStructuralFeature managerModule = target instanceof MdObject
                        ? target.eClass().getEStructuralFeature("managerModule") : null; //$NON-NLS-1$
                    String dataPage = target instanceof MdObject ? dataPageId(target, page) : null;
                    // Имена и типы свойств подтверждены в .tmp/bundles/mdclass/model/MdClass.xcore.
                    if (instanceModule == null && managerModule == null && dataPage == null)
                    {
                        result.add(link);
                        continue;
                    }
                    if (instanceModule != null && moduleHasContent(target, instanceModule))
                        result.add(new TypeHyperlink(link.getHyperlinkRegion(),
                            register ? "Модуль набора записей" : "Модуль объекта",
                            (MdObject) target, instanceModule, source, page));
                    if (managerModule != null && moduleHasContent(target, managerModule))
                        result.add(new TypeHyperlink(link.getHyperlinkRegion(), "Модуль менеджера",
                            (MdObject) target, managerModule, source, page));
                    if (dataPage != null)
                        result.add(new DataHyperlink(link.getHyperlinkRegion(), (MdObject) target,
                            dataPage, source, page));
                    // У штатной ссылки сохраняем обработчик открытия объекта.
                    result.add(!typeTarget && link instanceof XtextHyperlink xtext
                        ? new HistoryHyperlink(xtext, source, page, "Объект")
                        : new TypeHyperlink(link.getHyperlinkRegion(), "Объект", (MdObject) target,
                            source, page));
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
                    scope.opened = true;
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
                scope.opened = GoToDefinition.openBslModuleGranularEditor(file) != null;
            }
        }
    }

    /** Метод модуля менеджера по тексту «Справочники.Имя.Метод» — тот же переход, что Ctrl+F12. */
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
                scope.opened = GoToDefinition.jump(name, source.getSite().getShell(), page, project, false);
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
                    scope.opened = new OpenHelper(page).openEditor(target, moduleFeature) != null;
                else
                {
                    CompareConfigOpenObjectHandler.openInEditor(target, source, source.getSite().getShell());
                    scope.opened = true;
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

        @Override
        public IHyperlink[] detectHyperlinks(ITextViewer viewer, IRegion region, boolean multiple)
        {
            IHyperlink property = region == null ? null : propertyLink(source, page, viewer, region.getOffset());
            if (property != null)
                return new IHyperlink[] { property };
            // Штатные ссылки литерала («ПолучитьФорму») ведут на имя целиком: на «Обработка1»
            // в имени формы предложили бы форму.
            IHyperlink segment = region == null ? null : segmentLink(source, page, viewer, region.getOffset());
            IHyperlink[] links = segment != null ? new IHyperlink[] { segment }
                : delegate.detectHyperlinks(viewer, region, multiple);
            if (links == null || links.length == 0)
            {
                IHyperlink type = region == null ? null : textLink(source, page, viewer, region.getOffset());
                if (type == null)
                    return links;
                links = new IHyperlink[] { type };
            }
            IHyperlink[] result = multiple ? metadataLinks(viewer, links, source, page) : links.clone();
            if (multiple)
                compactVariableLinks(viewer, result);
            for (int i = 0; i < result.length; i++)
            {
                // Ссылки на строки BSL оставляем штатными. Для реквизита
                // BslHyperlinkHelper создаёт XtextHyperlink с URI объекта модели.
                if (result[i] instanceof XtextHyperlink link && link.getURI() != null
                    && !"bsl".equalsIgnoreCase(link.getURI().fileExtension()))
                    result[i] = new HistoryHyperlink(link, source, page);
            }
            return result;
        }

        @Override
        public void dispose()
        {
            if (delegate instanceof IHyperlinkDetectorExtension extension)
                extension.dispose();
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
        if (table.getLayoutData() instanceof GridData data)
            data.horizontalAlignment = SWT.FILL;
        Rectangle bounds = shell.getBounds();
        Rectangle screen = shell.getMonitor().getClientArea();
        int viewportHeight = table.getClientArea().height;
        int contentWidth = shell.computeSize(SWT.DEFAULT, SWT.DEFAULT, true).x;
        // Рамка и отступы одинаковы для исходных и новых подписей. Не меняем текст
        // TableItem ради замера: нативная таблица сохраняет прежнюю ширину элементов.
        int stockWidth = Math.max(1, contentWidth - contentTextWidth + stockTextWidth);
        int width = Math.min(contentWidth, Math.min(screen.width, stockWidth * 2));
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
            try (HistoryScope scope = new HistoryScope(page, source))
            {
                delegate.open();
                scope.opened = true;
            }
        }
    }

    private static final class HistoryScope implements AutoCloseable
    {
        private final IWorkbenchPage page;
        private final INavigationHistory history;
        private Field ignoreEntries;
        private boolean opened;

        HistoryScope(IWorkbenchPage page, IEditorPart source)
        {
            this.page = page;
            history = page.getNavigationHistory();
            if (history == null)
                return;
            try
            {
                // NavigationHistory.addEntry/markEditor пропускают записи при
                // ignoreEntries > 0; gotoEntry использует этот же счётчик.
                // Проверено в .tmp/bundles/ui-workbench/NavigationHistory-c.javap.txt.
                if (!"org.eclipse.ui.internal.NavigationHistory".equals(history.getClass().getName()))
                    return;
                Field field = history.getClass().getDeclaredField("ignoreEntries");
                field.setAccessible(true);
                history.markLocation(source);
                int previous = field.getInt(history);
                field.setInt(history, previous + 1);
                ignoreEntries = field;
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                // При несовместимости платформы оставляем штатную историю переходов.
            }
        }

        @Override
        public void close()
        {
            if (ignoreEntries != null)
            {
                try
                {
                    ignoreEntries.setInt(history, ignoreEntries.getInt(history) - 1);
                    IEditorPart destination = page.getActiveEditor();
                    if (opened && destination != null)
                        history.markLocation(destination);
                }
                catch (ReflectiveOperationException | RuntimeException e)
                {
                    // Не прерываем штатное открытие ссылки из-за ошибки истории.
                }
            }
        }
    }
}
