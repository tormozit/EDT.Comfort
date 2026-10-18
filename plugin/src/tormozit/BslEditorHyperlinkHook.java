package tormozit;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

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
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.INavigationHistory;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.xtext.naming.IQualifiedNameConverter;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.IScopeProvider;
import org.eclipse.xtext.ui.editor.hyperlinking.XtextHyperlink;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.md.resource.MdTypeUtil;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.EditorPageDescriptor;
import com._1c.g5.v8.dt.md.ui.editor.base.IEditorPagesRegistry;
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
                if (target == null || resource.getParseResult() == null)
                    return null;
                ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset);
                return leaf == null || leaf.isHidden() ? null : new PropertyHyperlink(
                    new Region(leaf.getOffset(), leaf.getLength()), target, editor, page);
            });
        }
        catch (RuntimeException | LinkageError failure)
        {
            return null;
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
            link = literalLink(editor, page, viewer, offset);
        if (link == null)
        {
            // Поля, псевдонимы, виртуальные таблицы в литерале-запросе — как в «Редакторе запроса».
            IHyperlink query = BslQueryLiteralSupport.queryLink(editor, viewer, offset);
            link = query == null ? null : new HistoryHyperlink(query, editor, page);
        }
        return link;
    }

    /**
     * Ссылка на объект метаданных по полному имени «Тип.Имя» под {@code offset} в строковом
     * литерале (текст запроса, аргумент НайтиПоПолномуИмени и т.п.). Дальние сегменты
     * («…КурсыВалют.СрезПоследних») не мешают — ссылка ведёт на объект верхнего уровня.
     */
    private static IHyperlink literalLink(BslXtextEditor editor, IWorkbenchPage page, ITextViewer viewer, int offset)
    {
        if (!(viewer.getDocument() instanceof IXtextDocument document) || document.getResourceURI() == null)
            return null;
        try
        {
            Object[] found = document.readOnly(resource ->
            {
                if (resource == null || resource.getParseResult() == null)
                    return null;
                ILeafNode leaf = literalLeaf(resource, offset);
                // Каретка сразу за именем перед закрывающей кавычкой или концом строки.
                if (leaf == null && offset > 0)
                    leaf = literalLeaf(resource, offset - 1);
                if (leaf == null)
                    return null;
                String text = leaf.getText();
                int position = Math.max(0, Math.min(text.length(), offset - leaf.getOffset()));
                int start = position;
                while (start > 0 && isTypeNameChar(text.charAt(start - 1)))
                    start--;
                while (start < position && text.charAt(start) == '.')
                    start++;
                int typeEnd = text.indexOf('.', start);
                if (typeEnd < 0 || !MdTypeMapping.isKnownMdRootType(text.substring(start, typeEnd)))
                    return null;
                int end = typeEnd + 1;
                while (end < text.length() && isTypeNameChar(text.charAt(end)) && text.charAt(end) != '.')
                    end++;
                // Под указателем — дальний сегмент, а не само «Тип.Имя».
                if (end == typeEnd + 1 || position > end)
                    return null;
                return new Object[] { text.substring(start, end), new Region(leaf.getOffset() + start, end - start) };
            });
            if (found == null)
                return null;
            IV8ProjectManager manager = (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
            IV8Project project = manager == null ? null : manager.getProject(document.getResourceURI());
            if (project == null)
                return null;
            // Поиск в BM — вне блокировки документа.
            String name = (String) found[0];
            return GoToDefinition.resolveEObjectByQualifiedName(name, project) instanceof MdObject target
                ? new TypeHyperlink((IRegion) found[1], name, target, editor, page)
                : null;
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }
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
                    EStructuralFeature objectModule = target instanceof MdObject
                        ? target.eClass().getEStructuralFeature("objectModule") : null; //$NON-NLS-1$
                    EStructuralFeature managerModule = target instanceof MdObject
                        ? target.eClass().getEStructuralFeature("managerModule") : null; //$NON-NLS-1$
                    String dataPage = target instanceof MdObject ? dataPageId(target, page) : null;
                    // Имена и типы свойств подтверждены в .tmp/bundles/mdclass/model/MdClass.xcore.
                    if (objectModule == null && managerModule == null && dataPage == null)
                    {
                        result.add(link);
                        continue;
                    }
                    if (objectModule != null)
                        result.add(new TypeHyperlink(link.getHyperlinkRegion(), "Модуль объекта",
                            (MdObject) target, objectModule, source, page));
                    if (managerModule != null)
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
            IHyperlink[] links = delegate.detectHyperlinks(viewer, region, multiple);
            if (links == null || links.length == 0)
            {
                IHyperlink type = region == null ? null : textLink(source, page, viewer, region.getOffset());
                if (type == null)
                    return links;
                links = new IHyperlink[] { type };
            }
            IHyperlink[] result = multiple ? metadataLinks(viewer, links, source, page) : links.clone();
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
