package tormozit;

import java.lang.reflect.Field;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.emf.ecore.EObject;
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

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.md.resource.MdTypeUtil;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * История переходов по ссылкам BSL-редактора (Ctrl+клик), issue 664.
 * Команды перехода к определению сохраняют штатные обработчики.
 * Дополнительно: переход по имени типа объекта метаданных в комментарии
 * (Ctrl+клик и команда «Перейти к определению»).
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
     * просмотрщика), поэтому ссылку на тип в комментарии для неё открываем здесь.
     */
    private static final class OpenDeclarationListener implements IExecutionListener
    {
        private IHyperlink pending;

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
                if (hasStockLinks(viewer, offset))
                    return;
                pending = typeLink(editor, page, viewer, offset);
            }
            catch (RuntimeException | LinkageError e)
            {
                pending = null;
            }
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue)
        {
            IHyperlink link = pending;
            pending = null;
            if (link != null && OPEN_DECLARATION_COMMAND.equals(commandId))
                link.open();
        }

        @Override
        public void notHandled(String commandId, NotHandledException exception)
        {
            pending = null;
        }

        @Override
        public void postExecuteFailure(String commandId, ExecutionException exception)
        {
            pending = null;
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
                    MdObject producer = object instanceof TypeItem type ? MdTypeUtil.getTypeProducer(type) : null;
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

    private static ILeafNode commentLeaf(XtextResource resource, int offset)
    {
        ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset);
        return leaf != null && leaf.isHidden() && leaf.getText().startsWith("//") ? leaf : null;
    }

    private static boolean isTypeNameChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    private static final class TypeHyperlink implements IHyperlink
    {
        private final IRegion region;
        private final String name;
        private final MdObject target;
        private final BslXtextEditor source;
        private final IWorkbenchPage page;

        TypeHyperlink(IRegion region, String name, MdObject target, BslXtextEditor source, IWorkbenchPage page)
        {
            this.region = region;
            this.name = name;
            this.target = target;
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
                CompareConfigOpenObjectHandler.openInEditor(target, source, source.getSite().getShell());
                scope.opened = true;
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
            IHyperlink[] links = delegate.detectHyperlinks(viewer, region, multiple);
            if (links == null || links.length == 0)
            {
                IHyperlink type = region == null ? null : typeLink(source, page, viewer, region.getOffset());
                return type == null ? links : new IHyperlink[] { type };
            }
            IHyperlink[] result = links.clone();
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
        private final XtextHyperlink delegate;
        private final BslXtextEditor source;
        private final IWorkbenchPage page;

        HistoryHyperlink(XtextHyperlink delegate, BslXtextEditor source, IWorkbenchPage page)
        {
            this.delegate = delegate;
            this.source = source;
            this.page = page;
        }

        @Override public IRegion getHyperlinkRegion() { return delegate.getHyperlinkRegion(); }
        @Override public String getTypeLabel() { return delegate.getTypeLabel(); }
        @Override public String getHyperlinkText() { return delegate.getHyperlinkText(); }

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
