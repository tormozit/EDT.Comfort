package tormozit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.TransferData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.navigator.CommonViewer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.DataItem;
import com._1c.g5.v8.dt.form.model.DataPathReferredObject;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;

/**
 * Доработки деревьев ({@code DtTreeView}) в редакторах объектов метаданных.
 * <p>
 * Сейчас доработка одна — текущая строка не должна уезжать на верхнюю видимую при промахе
 * программного выделения. Двойной клик по проблеме реквизита в панели проблем даёт штатную
 * цепочку {@code BmMarkerUiHandler.showMarker} → {@code OpenHelper.openEditor} →
 * {@code DtGranularEditor.gotoSelection}, в которой в дерево уходят ДВА выделения: сперва сам
 * реквизит (строка есть — выделяется верно), затем объект маркера, например
 * {@code TypeDescription} для проверки составных типов. Строки для него в дереве нет, маппер
 * отдаёт пустую {@code TreeItemViewModel}, {@code DtTreeView} пытается выделить её в JFace,
 * элемент не находится — и выделение становится пустым. Штатный
 * {@code DtTreeView$SelectionListener} трактует это как «пользователь снял выделение» и выделяет
 * {@code tree.getTopItem()}, то есть верхнюю ВИДИМУЮ строку. Пользователь видит, как активной
 * становится посторонняя строка.
 * <p>
 * Перехват: штатный слушатель выделения снимается с {@code TreeViewer} и вызывается из нашей
 * обёртки. Пустое выделение, пришедшее из программного применения события (в стеке есть кадр
 * {@code DtTreeView}), штатному слушателю не отдаётся — вместо этого восстанавливается прежняя
 * строка. Пустое выделение от действий пользователя обрабатывается штатно.
 * <p>
 * Почему не «доводка» (выделить нужную строку после того, как штатная цепочка отработает):
 * промах приходит примерно через 170 мс после верного выделения, и повторное выделение было бы
 * видно пользователю как скачок текущей строки туда и обратно.
 */
public final class MdEditorTreeHook
    implements IStartup
{
    /** Ключ, под которым {@code DtTreeView} кладёт свой {@code TreeViewer} в данные контрола. */
    private static final String DT_TREE_VIEWER_KEY =
        "com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView.treeViewer"; //$NON-NLS-1$

    private static final String STOCK_LISTENER_CLASS =
        "com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView$SelectionListener"; //$NON-NLS-1$

    /** Кадр стека, по которому видно, что выделение применяет сам {@code DtTreeView}. */
    private static final String DT_TREE_VIEW_CLASS =
        "com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView"; //$NON-NLS-1$

    private static final String DROP_MARKER = "tormozit.mdEditorAttributesDrop"; //$NON-NLS-1$

    private static final String DRAG_MARKER = "tormozit.mdEditorAttributesDrag"; //$NON-NLS-1$

    private static final String FORM_COLUMNS_KEY = "tormozit.mdEditorFormColumns"; //$NON-NLS-1$

    private static final String FORM_COLUMNS_SIGNATURE_KEY =
        "tormozit.mdEditorFormColumnsSignature"; //$NON-NLS-1$

    private static final String FORM_COLUMN_MARKER = "tormozit.mdEditorFormColumn"; //$NON-NLS-1$

    private static final String FORM_NAME_COLUMN_MARKER =
        "tormozit.mdEditorFormNameColumn"; //$NON-NLS-1$

    private static final String FORM_COLUMNS_GUARD_MARKER =
        "tormozit.mdEditorFormColumnsGuard"; //$NON-NLS-1$

    private static final String FORM_COLUMNS_RECHECK_MARKER =
        "tormozit.mdEditorFormColumnsRecheck"; //$NON-NLS-1$

    private static final String FORM_COLUMNS_CLASSIFIED_MARKER =
        "tormozit.mdEditorFormColumnsClassified"; //$NON-NLS-1$

    private static final String FORM_COLUMN_DOUBLE_CLICK_MARKER =
        "tormozit.mdEditorFormColumnDoubleClick"; //$NON-NLS-1$

    private static final String FOCUS_MARKER = "tormozit.mdEditorSelectionFocus"; //$NON-NLS-1$

    private static final String MODEL_ADD_OBSERVER_KEY =
        "tormozit.mdEditorModelAddObserver"; //$NON-NLS-1$

    private static final String PASTE_COMMAND_ID = "org.eclipse.ui.edit.paste"; //$NON-NLS-1$

    private static final String NAVIGATOR_UI_BUNDLE = "com._1c.g5.v8.dt.navigator.ui"; //$NON-NLS-1$

    private static final String NAVIGATOR_DROP_ASSISTANT =
        "com._1c.g5.v8.dt.internal.navigator.ui.NavigatorDropAssistant"; //$NON-NLS-1$

    private static final String DT_TREE_VIEW_INTERNAL =
        "com/_1c/g5/v8/dt/ui/aef/swt/views/DtTreeView"; //$NON-NLS-1$

    private static final String MODEL_OBJECT_COPY_SUPPORT_INTERNAL =
        "com/_1c/g5/v8/dt/internal/md/copy/ModelObjectCopySupport"; //$NON-NLS-1$

    private static final String COPY_CONTAINED_DESCRIPTOR =
        "(Ljava/util/List;Lorg/eclipse/emf/ecore/EObject;Lorg/eclipse/emf/ecore/EReference;" //$NON-NLS-1$
            + "Lorg/eclipse/core/runtime/IProgressMonitor;)Ljava/util/List;"; //$NON-NLS-1$

    private static final String COPY_TOP_DESCRIPTOR =
        "(Ljava/util/List;Lorg/eclipse/core/resources/IProject;" //$NON-NLS-1$
            + "Lorg/eclipse/core/runtime/IProgressMonitor;)Ljava/util/List;"; //$NON-NLS-1$

    private static final AtomicBoolean weavingHookInstalled = new AtomicBoolean();

    private static final AtomicBoolean pasteCommandHookInstalled = new AtomicBoolean();

    /** Страницы редактора создаются не сразу — повторяем обход контролов. */
    private static final int[] RETRY_DELAYS = { 0, 150, 400, 900, 1800, 3500 };

    /** События активации приходят пачкой — обход контролов повторяем не чаще раза в секунду. */
    private static final long RESCHEDULE_PAUSE_MS = 1000;

    private static final Map<IEditorPart, Long> lastScheduled = new WeakHashMap<>();

    /** Дерево, из которого началась команда или перетаскивание; нужно после закрытия progress. */
    private static volatile TreeViewer pendingSelectionViewer;

    private static volatile PasteSnapshot pendingPaste;

    /** Регистрируется из {@link Activator#start(BundleContext)} до загрузки {@code DtTreeView}. */
    static void installWeavingHook()
    {
        if (!weavingHookInstalled.compareAndSet(false, true))
            return;
        org.osgi.framework.Bundle bundle = FrameworkUtil.getBundle(MdEditorTreeHook.class);
        BundleContext context = bundle != null ? bundle.getBundleContext() : null;
        if (context != null)
            context.registerService(WeavingHook.class, new TreeStyleWeavingHook(), null);
    }

    /** Вызывается непосредственно перед возвратом результата штатного {@code copyAndAttach}. */
    public static void afterCopied(List<?> copied)
    {
        List<EObject> objects = new ArrayList<>();
        if (copied != null)
            for (Object value : copied)
                if (value instanceof EObject object)
                    objects.add(object);
        if (objects.isEmpty())
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> selectCopiedObjects(objects, 0));
    }

    /**
     * Вызывается из инструментированного конструктора {@code TreeViewer}. Стиль меняется
     * только при создании дерева активного редактора объекта метаданных.
     */
    public static int metadataEditorTreeStyle(Composite parent, int style)
    {
        if (!PlatformUI.isWorkbenchRunning())
        {
            return style;
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        boolean metadataEditor = page != null && page.getActiveEditor() instanceof DtGranularEditor<?>;
        int result = metadataEditor ? (style & ~SWT.SINGLE) | SWT.MULTI : style;
        return result;
    }

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            Display.getDefault().addFilter(SWT.KeyDown, MdEditorTreeHook::watchPaste);
            Display.getDefault().addFilter(SWT.Selection, MdEditorTreeHook::watchPasteMenu);
            IWorkbench workbench = PlatformUI.getWorkbench();
            if (workbench == null)
                return;
            installPasteCommandObserver(workbench);
            for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
                hookWindow(window);
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow window) { hookWindow(window); }
                @Override public void windowActivated(IWorkbenchWindow window) {}
                @Override public void windowDeactivated(IWorkbenchWindow window) {}
                @Override public void windowClosed(IWorkbenchWindow window) {}
            });
        });
    }

    /** Наблюдение за настоящей командой Eclipse Paste, независимо от клавиатуры и меню SWT. */
    private static void installPasteCommandObserver(IWorkbench workbench)
    {
        if (!pasteCommandHookInstalled.compareAndSet(false, true))
            return;
        ICommandService service = workbench.getService(ICommandService.class);
        Command command = service != null ? service.getCommand(PASTE_COMMAND_ID) : null;
        if (command == null)
            return;
        command.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
                Control focus = Display.getDefault().getFocusControl();
                TreeViewer viewer = focus instanceof Tree tree ? viewerOf(tree) : null;
                pendingPaste = viewer != null
                    ? new PasteSnapshot(viewer, visibleObjects(viewer.getTree())) : null;
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
                PasteSnapshot snapshot = pendingPaste;
                pendingPaste = null;
                if (snapshot != null)
                    selectAddedObject(snapshot.viewer, snapshot.before, 0);
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception)
            {
                pendingPaste = null;
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception)
            {
                pendingPaste = null;
            }
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        for (IWorkbenchPage page : window.getPages())
            if (page != null)
                for (org.eclipse.ui.IEditorReference ref : page.getEditorReferences())
                    scheduleInstall(ref != null ? ref.getEditor(false) : null);
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { scheduleInstall(editorOf(ref)); }
            @Override public void partActivated(IWorkbenchPartReference ref) { scheduleInstall(editorOf(ref)); }
            @Override public void partVisible(IWorkbenchPartReference ref) { scheduleInstall(editorOf(ref)); }
            @Override public void partInputChanged(IWorkbenchPartReference ref) { scheduleInstall(editorOf(ref)); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
        });
    }

    private static IEditorPart editorOf(IWorkbenchPartReference ref)
    {
        IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
        return part instanceof IEditorPart editor ? editor : null;
    }

    private static void scheduleInstall(IEditorPart editor)
    {
        if (editor == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        long now = System.currentTimeMillis();
        Long previous = lastScheduled.get(editor);
        if (previous != null && now - previous.longValue() < RESCHEDULE_PAUSE_MS)
            return;
        lastScheduled.put(editor, Long.valueOf(now));
        for (int delay : RETRY_DELAYS)
            display.timerExec(delay, () -> install(editor));
    }

    private static void install(IEditorPart editor)
    {
        Object container = Global.invoke(editor, "getContainer"); //$NON-NLS-1$
        if (!(container instanceof Composite composite) || composite.isDisposed())
            return;
        installInChildren(composite, 0);
    }

    private static void installInChildren(Composite composite, int depth)
    {
        if (composite.isDisposed() || depth > 25)
            return;
        if (composite.getData(DT_TREE_VIEWER_KEY) instanceof TreeViewer viewer)
            installOnViewer(viewer);
        for (Control child : composite.getChildren())
            if (child instanceof Composite childComposite)
                installInChildren(childComposite, depth + 1);
    }

    /**
     * Ставится заново, если {@code DtTreeView} перепривязался к тому же дереву и добавил свой
     * слушатель снова: признаком служит не отметка на виджете, а наличие штатного слушателя в
     * списке.
     */
    private static void installOnViewer(TreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        if (!Boolean.TRUE.equals(tree.getData(FOCUS_MARKER)))
        {
            tree.addListener(SWT.FocusIn, event -> pendingSelectionViewer = viewer);
            tree.setData(FOCUS_MARKER, Boolean.TRUE);
        }
        if (MdEditorAttributeMenuHook.isDataPageAttributesTree(tree))
        {
            installAttributeDrag(viewer);
            installAttributeDrop(viewer);
            installFormColumns(viewer);
            installModelAddObserver(viewer);
        }
        ISelectionChangedListener stock = findListener(viewer, STOCK_LISTENER_CLASS);
        if (stock == null)
            return;
        ISelectionChangedListener previous = findListener(viewer, KeepSelectionListener.class.getName());
        if (previous != null)
            viewer.removeSelectionChangedListener(previous);
        viewer.removeSelectionChangedListener(stock);
        viewer.addSelectionChangedListener(new KeepSelectionListener(viewer, stock));
        Debug.log("перехват выделения установлен"); //$NON-NLS-1$
    }

    /** Колонки явно назначенных основных форм на вкладке «Данные». */
    private static void installFormColumns(TreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (!MdEditorAttributeMenuHook.isDataPageAttributesTree(tree))
            return;
        installFormColumnsGuard(viewer);
        if (!hasClassifiableRow(tree))
            return;
        if (isStandardAttributesTree(tree))
        {
            tree.setData(FORM_COLUMNS_CLASSIFIED_MARKER, Boolean.TRUE);
            removeFormColumns(tree);
            return;
        }
        tree.setData(FORM_COLUMNS_CLASSIFIED_MARKER, Boolean.TRUE);
        DtGranularEditor<?> editor = MdEditorAttributeMenuHook.editorOf(tree);
        EObject owner = editor != null ? editor.getModel() : null;
        if (owner == null)
            return;
        List<FormColumn> forms = mainForms(owner);
        String signature = formColumnsSignature(forms);
        if (signature.equals(tree.getData(FORM_COLUMNS_SIGNATURE_KEY)))
        {
            if (tree.getData(FORM_COLUMNS_KEY) instanceof List<?> installed)
                for (Object value : installed)
                    if (value instanceof FormColumn form)
                        form.attach(viewer);
            FormTreeInteraction.install(tree, viewer);
            ColumnAutoFit.install(tree, null, index -> index != 0);
            return;
        }

        for (TreeColumn column : tree.getColumns())
            if (Boolean.TRUE.equals(column.getData(FORM_COLUMN_MARKER)))
                column.dispose();
        if (forms.isEmpty())
        {
            tree.setData(FORM_COLUMNS_KEY, List.of());
            tree.setData(FORM_COLUMNS_SIGNATURE_KEY, signature);
            return;
        }

        if (tree.getColumnCount() == 0)
        {
            TreeColumn name = new TreeColumn(tree, SWT.LEFT);
            name.setData(FORM_NAME_COLUMN_MARKER, Boolean.TRUE);
            name.setText("Реквизит"); //$NON-NLS-1$
            name.setWidth(Math.max(220, tree.getClientArea().width / 2));
        }
        else if (tree.getColumn(0).getText().isBlank())
            tree.getColumn(0).setText("Реквизит"); //$NON-NLS-1$

        for (FormColumn form : forms)
        {
            TreeViewerColumn viewerColumn = new TreeViewerColumn(viewer, SWT.CENTER);
            TreeColumn column = viewerColumn.getColumn();
            form.column = column;
            column.setData(FORM_COLUMN_MARKER, Boolean.TRUE);
            column.setText(form.title());
            column.setToolTipText(TooltipText.wrap(tree,
                "Наличие реквизита на основной форме «" + form.title() //$NON-NLS-1$
                    + "». Двойной клик открывает элемент формы.")); //$NON-NLS-1$
            column.setMoveable(true);
            column.setWidth(100);
            viewerColumn.setLabelProvider(new ColumnLabelProvider()
            {
                @Override
                public String getText(Object element)
                {
                    EObject object = elementObject(tree, element);
                    return MdEditorAttributeMenuHook.isDataMember(object)
                        ? form.textFor(object, viewer) : ""; //$NON-NLS-1$
                }
            });
        }
        tree.setHeaderVisible(true);
        tree.setData(FORM_COLUMNS_KEY, forms);
        tree.setData(FORM_COLUMNS_SIGNATURE_KEY, signature);
        for (FormColumn form : forms)
            form.attach(viewer);
        installFormColumnDoubleClick(tree);
        FormTreeInteraction.install(tree, viewer);
        ColumnAutoFit.install(tree, null, index -> index != 0);
        Composite parent = tree.getParent();
        if (parent != null && !parent.isDisposed())
            parent.layout(true, true);
    }

    /** Отдельное дерево стандартных реквизитов на вкладке «Данные». */
    private static boolean isStandardAttributesTree(Tree tree)
    {
        for (TreeItem item : tree.getItems())
            if (containsStandardAttribute(tree, item))
                return true;
        return false;
    }

    private static boolean containsStandardAttribute(Tree tree, TreeItem item)
    {
        Object data = item.getData();
        if (data != null && data.getClass().getName().contains("StandardAttribute")) //$NON-NLS-1$
            return true;
        EObject object = elementObject(tree, data);
        if (object != null && object.eClass().getName().contains("StandardAttribute")) //$NON-NLS-1$
            return true;
        for (TreeItem child : item.getItems())
            if (containsStandardAttribute(tree, child))
                return true;
        return false;
    }

    /** Не создаём колонки до появления первой строки ленивого дерева. */
    private static boolean hasClassifiableRow(Tree tree)
    {
        for (TreeItem item : tree.getItems())
            if (hasClassifiableRow(tree, item))
                return true;
        return false;
    }

    private static boolean hasClassifiableRow(Tree tree, TreeItem item)
    {
        Object data = item.getData();
        if (data != null && (elementObject(tree, data) != null
            || data.getClass().getName().contains("Attribute"))) //$NON-NLS-1$
            return true;
        for (TreeItem child : item.getItems())
            if (hasClassifiableRow(tree, child))
                return true;
        return false;
    }

    /** Перепроверяет тип дерева, когда ленивый viewer впервые отрисовал содержательную строку. */
    private static void installFormColumnsGuard(TreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (Boolean.TRUE.equals(tree.getData(FORM_COLUMNS_GUARD_MARKER)))
            return;
        tree.setData(FORM_COLUMNS_GUARD_MARKER, Boolean.TRUE);
        tree.addListener(SWT.PaintItem, event ->
        {
            if (Boolean.TRUE.equals(tree.getData(FORM_COLUMNS_CLASSIFIED_MARKER))
                || Boolean.TRUE.equals(tree.getData(FORM_COLUMNS_RECHECK_MARKER)))
                return;
            tree.setData(FORM_COLUMNS_RECHECK_MARKER, Boolean.TRUE);
            tree.getDisplay().asyncExec(() ->
            {
                if (tree.isDisposed())
                    return;
                tree.setData(FORM_COLUMNS_RECHECK_MARKER, null);
                installFormColumns(viewer);
            });
        });
    }

    private static void removeFormColumns(Tree tree)
    {
        for (TreeColumn column : tree.getColumns())
            if (Boolean.TRUE.equals(column.getData(FORM_COLUMN_MARKER))
                || Boolean.TRUE.equals(column.getData(FORM_NAME_COLUMN_MARKER)))
                column.dispose();
        tree.setData(FORM_COLUMNS_KEY, null);
        tree.setData(FORM_COLUMNS_SIGNATURE_KEY, null);
        tree.setHeaderVisible(false);
    }

    private static List<FormColumn> mainForms(EObject owner)
    {
        List<FormColumn> result = new ArrayList<>();
        Set<String> seenForms = new LinkedHashSet<>();
        for (EStructuralFeature feature : owner.eClass().getEAllStructuralFeatures())
        {
            String name = feature.getName();
            if (!(feature instanceof EReference) || feature.isMany() || name == null
                || !name.startsWith("default") || !name.endsWith("Form")) //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            Object value = owner.eGet(feature, true);
            if (value instanceof BasicForm basicForm)
            {
                String formKey = objectKey(basicForm);
                if (formKey != null && !seenForms.add(formKey))
                {
                    continue;
                }
                FormColumn column = FormColumn.create(feature, basicForm);
                if (column != null)
                    result.add(column);
            }
        }
        return result;
    }

    private static String formColumnsSignature(List<FormColumn> forms)
    {
        StringBuilder result = new StringBuilder();
        for (FormColumn form : forms)
            result.append(form.feature.getName()).append('=').append(EcoreUtil.getURI(form.basicForm))
                .append(';');
        return result.toString();
    }

    private static void installModelAddObserver(TreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        DtGranularEditor<?> editor = MdEditorAttributeMenuHook.editorOf(tree);
        EObject owner = editor != null ? editor.getModel() : null;
        if (owner == null)
            return;
        if (tree.getData(MODEL_ADD_OBSERVER_KEY) instanceof ModelAddObserver existing)
        {
            if (MdEditorAttributeMenuHook.sameObject(existing.owner, owner))
                return;
            existing.dispose();
        }
        ModelAddObserver observer = new ModelAddObserver(owner, viewer);
        owner.eAdapters().add(observer);
        tree.setData(MODEL_ADD_OBSERVER_KEY, observer);
        tree.addDisposeListener(event -> observer.dispose());
    }

    private static void installFormColumnDoubleClick(Tree tree)
    {
        if (Boolean.TRUE.equals(tree.getData(FORM_COLUMN_DOUBLE_CLICK_MARKER)))
            return;
        tree.addListener(SWT.MouseDoubleClick, event ->
        {
            if (!(tree.getData(FORM_COLUMNS_KEY) instanceof List<?> raw))
                return;
            TreeItem row = FormTreeInteraction.rowAt(tree, event.x, event.y);
            EObject object = row != null ? elementObject(tree, row.getData()) : null;
            int clickedColumn = FormTreeInteraction.columnAtX(tree, event.x);
            if (row == null || object == null)
                return;
            for (Object value : raw)
            {
                if (!(value instanceof FormColumn form) || form.column == null
                    || form.column.isDisposed())
                    continue;
                int index = tree.indexOf(form.column);
                if (index < 0 || clickedColumn != index)
                    continue;
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                IWorkbenchPage page = window != null ? window.getActivePage() : null;
                if (page != null)
                    FormEditorHook.openFormAttributeAndGoTo(page, form.basicForm, object);
                return;
            }
        });
        tree.setData(FORM_COLUMN_DOUBLE_CLICK_MARKER, Boolean.TRUE);
    }

    /**
     * Редактор объекта использует тот же {@code DtTreeView}, что и навигатор, но не подключает
     * {@code NavigatorDropAssistant}. Берём его экземпляр из Guice-инжектора EDT: в нём уже
     * находятся штатные сервисы копирования и разрешения целевой коллекции.
     */
    private static void installAttributeDrop(TreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (Boolean.TRUE.equals(tree.getData(DROP_MARKER)))
            return;
        DropTarget target = ensureDropTarget(tree);
        if (target == null)
            return;
        ensureLocalSelectionTransfer(target);
        target.addDropListener(new DropTargetAdapter()
        {
            @Override
            public void dragEnter(DropTargetEvent event)
            {
                updateDropDetail(event, tree);
            }

            @Override
            public void dragOperationChanged(DropTargetEvent event)
            {
                updateDropDetail(event, tree);
            }

            @Override
            public void dragOver(DropTargetEvent event)
            {
                updateDropDetail(event, tree);
            }

            @Override
            public void dropAccept(DropTargetEvent event)
            {
                updateDropDetail(event, tree);
            }

            @Override
            public void drop(DropTargetEvent event)
            {
                // SWT на Windows оставляет MOVE даже при Ctrl в некоторых внутренних деревьях
                // EDT. Перед передачей штатному assistant ещё раз берём фактическое состояние
                // клавиши, чтобы handleDrop получил именно выбранную пользователем операцию.
                updateDropDetail(event, tree);
                EObject targetObject = dropTargetObject(tree, event);
                Object assistant = navigatorDropAssistant();
                if (targetObject == null || assistant == null
                    || !(event.data instanceof TreeSelection source))
                    return;
                pendingSelectionViewer = viewer;
                Set<String> before = visibleObjects(tree);
                try
                {
                    Method handleDrop = assistant.getClass().getMethod("handleDrop", //$NON-NLS-1$
                        Class.forName("org.eclipse.ui.navigator.CommonDropAdapter"), //$NON-NLS-1$
                        DropTargetEvent.class, Object.class);
                    handleDrop.invoke(assistant, null, event, targetObject);
                    selectAddedObject(viewer, before, 0);
                }
                catch (ReflectiveOperationException e)
                {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    Debug.log("перетаскивание реквизита: " + cause); //$NON-NLS-1$
                }
            }
        });
        tree.setData(DROP_MARKER, Boolean.TRUE);
    }

    /** Редактор сам не создаёт {@link DragSource}, поэтому перетаскивание из него не начинается. */
    private static void installAttributeDrag(TreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (Boolean.TRUE.equals(tree.getData(DRAG_MARKER)))
            return;
        DragSource source = ensureDragSource(tree);
        if (source == null)
            return;
        ensureLocalSelectionTransfer(source);
        source.addDragListener(new DragSourceAdapter()
        {
            @Override
            public void dragStart(DragSourceEvent event)
            {
                TreeSelection selection = metadataSelection(tree);
                event.doit = !selection.isEmpty();
                if (!event.doit)
                    return;
                pendingSelectionViewer = viewer;
                LocalSelectionTransfer.getTransfer().setSelection(selection);
            }

            @Override
            public void dragSetData(DragSourceEvent event)
            {
                if (LocalSelectionTransfer.getTransfer().isSupportedType(event.dataType))
                    event.data = LocalSelectionTransfer.getTransfer().getSelection();
            }

            @Override
            public void dragFinished(DragSourceEvent event)
            {
                LocalSelectionTransfer.getTransfer().setSelection(null);
            }
        });
        tree.setData(DRAG_MARKER, Boolean.TRUE);
    }

    private static TreeSelection metadataSelection(Tree tree)
    {
        List<TreePath> paths = new ArrayList<>();
        for (TreeItem item : tree.getSelection())
        {
            EObject object = elementObject(tree, item.getData());
            if (object != null)
                paths.add(new TreePath(new Object[] { object }));
        }
        return paths.isEmpty() ? TreeSelection.EMPTY
            : new TreeSelection(paths.toArray(new TreePath[0]));
    }

    private static void updateDropDetail(DropTargetEvent event, Tree tree)
    {
        Object source = LocalSelectionTransfer.getTransfer().getSelection();
        EObject target = dropTargetObject(tree, event);
        if (!(source instanceof TreeSelection selection) || target == null)
        {
            event.detail = DND.DROP_NONE;
            return;
        }
        preferLocalSelectionDataType(event);
        boolean canMove = canMove(selection, target);
        boolean ctrl = KeyStateProbe.isCtrlPressed();
        if (ctrl && (event.operations & DND.DROP_COPY) != 0)
            event.detail = DND.DROP_COPY;
        else if (event.detail == DND.DROP_MOVE && canMove)
            event.detail = DND.DROP_MOVE;
        else if (event.detail == DND.DROP_COPY && (event.operations & DND.DROP_COPY) != 0)
            event.detail = DND.DROP_COPY;
        else if (canMove && (event.operations & DND.DROP_MOVE) != 0)
            event.detail = DND.DROP_MOVE;
        else
            event.detail = (event.operations & DND.DROP_COPY) != 0 ? DND.DROP_COPY : DND.DROP_NONE;
    }

    private static boolean canMove(TreeSelection selection, EObject target)
    {
        if (selection.size() != 1 || selection.getFirstElement() == null)
            return false;
        Object value = selection.getFirstElement();
        if (!(value instanceof EObject source) || source.eContainer() == null
            || target.eContainer() == null)
            return false;
        return MdEditorAttributeMenuHook.sameObject(source.eContainer(), target.eContainer())
            && source.eContainingFeature() == target.eContainingFeature();
    }

    private static EObject dropTargetObject(Tree tree, DropTargetEvent event)
    {
        TreeItem item = tree.getItem(tree.toControl(event.x, event.y));
        return item == null ? null : elementObject(tree, item.getData());
    }

    private static EObject elementObject(Tree tree, Object element)
    {
        EObject mapped = MdEditorAttributeMenuHook.mapViewModelToEObject(tree, element);
        return mapped != null ? mapped : NavigatorElementModels.resolveEObject(element);
    }

    private static Object navigatorDropAssistant()
    {
        try
        {
            org.osgi.framework.Bundle bundle = Platform.getBundle(NAVIGATOR_UI_BUNDLE);
            if (bundle == null)
                return null;
            Class<?> pluginClass = bundle.loadClass("com._1c.g5.v8.dt.navigator.ui.NavigatorUiPlugin"); //$NON-NLS-1$
            Object plugin = pluginClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
            Object injector = pluginClass.getMethod("getInjector").invoke(plugin); //$NON-NLS-1$
            Class<?> assistantClass = bundle.loadClass(NAVIGATOR_DROP_ASSISTANT);
            return injector instanceof com.google.inject.Injector guice
                ? guice.getInstance(assistantClass) : null;
        }
        catch (ReflectiveOperationException e)
        {
            Debug.log("не удалось получить NavigatorDropAssistant: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static DropTarget ensureDropTarget(Tree tree)
    {
        Object existing = tree.getData(DND.DROP_TARGET_KEY);
        if (existing instanceof DropTarget target)
            return target;
        try
        {
            return new DropTarget(tree, DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_DEFAULT);
        }
        catch (RuntimeException | SWTError e)
        {
            Debug.log("не удалось создать приёмник перетаскивания: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static DragSource ensureDragSource(Tree tree)
    {
        Object existing = tree.getData(DND.DRAG_SOURCE_KEY);
        if (existing instanceof DragSource source)
            return source;
        try
        {
            return new DragSource(tree, DND.DROP_COPY | DND.DROP_MOVE);
        }
        catch (RuntimeException | SWTError e)
        {
            return null;
        }
    }

    private static void ensureLocalSelectionTransfer(DropTarget target)
    {
        Transfer local = LocalSelectionTransfer.getTransfer();
        Transfer[] current = target.getTransfer();
        if (current != null)
        {
            for (Transfer transfer : current)
                if (transfer == local)
                    return;
            Transfer[] expanded = new Transfer[current.length + 1];
            System.arraycopy(current, 0, expanded, 0, current.length);
            expanded[current.length] = local;
            target.setTransfer(expanded);
        }
        else
            target.setTransfer(new Transfer[] { local });
    }

    private static void ensureLocalSelectionTransfer(DragSource source)
    {
        Transfer local = LocalSelectionTransfer.getTransfer();
        Transfer[] current = source.getTransfer();
        if (current != null)
        {
            for (Transfer transfer : current)
                if (transfer == local)
                    return;
            Transfer[] expanded = new Transfer[current.length + 1];
            System.arraycopy(current, 0, expanded, 0, current.length);
            expanded[current.length] = local;
            source.setTransfer(expanded);
        }
        else
            source.setTransfer(new Transfer[] { local });
    }

    private static void preferLocalSelectionDataType(DropTargetEvent event)
    {
        Transfer local = LocalSelectionTransfer.getTransfer();
        if (event.currentDataType != null && local.isSupportedType(event.currentDataType))
            return;
        if (event.dataTypes == null)
            return;
        for (TransferData type : event.dataTypes)
            if (local.isSupportedType(type))
            {
                event.currentDataType = type;
                return;
            }
    }

    private static Set<String> visibleObjects(Tree tree)
    {
        Set<String> result = new java.util.HashSet<>();
        collectVisibleObjects(tree, tree.getItems(), result);
        return result;
    }

    private static void collectVisibleObjects(Tree tree, TreeItem[] items, Set<String> result)
    {
        for (TreeItem item : items)
        {
            String key = objectKey(elementObject(tree, item.getData()));
            if (key != null)
                result.add(key);
            collectVisibleObjects(tree, item.getItems(), result);
        }
    }

    private static void selectAddedObject(TreeViewer viewer, Set<String> before, int attempt)
    {
        Display display = viewer.getTree().getDisplay();
        display.timerExec(attempt == 0 ? 100 : 250, () ->
        {
            try
            {
                Tree tree = viewer.getTree();
                if (tree.isDisposed())
                    return;
                TreeItem[] added = findAddedItems(tree, tree.getItems(), before);
                if (added.length > 0)
                {
                    Object[] elements = new Object[added.length];
                    for (int i = 0; i < added.length; i++)
                        elements[i] = added[i].getData();
                    viewer.setSelection(new StructuredSelection(elements), true);
                    tree.showSelection();
                    return;
                }
                if (attempt < 8)
                    selectAddedObject(viewer, before, attempt + 1);
            }
            catch (RuntimeException e)
            {
            }
        });
    }

    /** Ctrl+V обрабатывает штатная команда после фильтра; снимок дерева берём до неё. */
    private static void watchPaste(org.eclipse.swt.widgets.Event event)
    {
        if ((event.stateMask & SWT.CTRL) == 0 || (event.keyCode != 'v' && event.keyCode != 'V'))
            return;
        watchPasteOnFocus(event.display != null ? event.display.getFocusControl() : null);
    }

    /** Контекстное меню EDT вызывает Paste напрямую, минуя ожидаемый нами {@code SWT.KeyDown}. */
    private static void watchPasteMenu(org.eclipse.swt.widgets.Event event)
    {
        if (!(event.widget instanceof MenuItem item) || !"Вставить".equals(plainMenuText(item))) //$NON-NLS-1$
            return;
        watchPasteOnFocus(event.display != null ? event.display.getFocusControl() : null);
    }

    private static String plainMenuText(MenuItem item)
    {
        String text = item.getText();
        int tab = text.indexOf('\t');
        return tab >= 0 ? text.substring(0, tab) : text;
    }

    private static void watchPasteOnFocus(Control focus)
    {
        if (!(focus instanceof Tree tree))
            return;
        TreeViewer viewer = viewerOf(tree);
        if (viewer != null)
        {
            pendingSelectionViewer = viewer;
            selectAddedObject(viewer, visibleObjects(tree), 0);
        }
    }

    private static void selectCopiedObjects(List<EObject> objects, int attempt)
    {
        TreeViewer viewer = focusedViewer();
        if (viewer == null)
            viewer = pendingSelectionViewer;
        if (viewer != null && selectObjects(viewer, objects))
        {
            pendingSelectionViewer = null;
            return;
        }
        if (attempt < 20)
            Display.getDefault().timerExec(150, () -> selectCopiedObjects(objects, attempt + 1));
    }

    private static TreeViewer focusedViewer()
    {
        Control focus = Display.getDefault().getFocusControl();
        return focus instanceof Tree tree ? viewerOf(tree) : null;
    }

    private static boolean selectObjects(TreeViewer viewer, List<EObject> objects)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return false;
        if (viewer instanceof CommonViewer)
        {
            viewer.setSelection(new StructuredSelection(objects), true);
            if (tree.getSelectionCount() == objects.size())
            {
                tree.showSelection();
                return true;
            }
        }
        List<Object> rows = new ArrayList<>();
        collectObjectRows(tree, tree.getItems(), objects, rows);
        if (rows.size() != objects.size())
            return false;
        viewer.setSelection(new StructuredSelection(rows), true);
        tree.showSelection();
        return tree.getSelectionCount() == rows.size();
    }

    private static void collectObjectRows(Tree tree, TreeItem[] items, List<EObject> objects,
        List<Object> rows)
    {
        for (TreeItem item : items)
        {
            EObject rowObject = elementObject(tree, item.getData());
            for (EObject object : objects)
                if (MdEditorAttributeMenuHook.sameObject(rowObject, object))
                {
                    rows.add(item.getData());
                    break;
                }
            collectObjectRows(tree, item.getItems(), objects, rows);
        }
    }

    private static TreeViewer viewerOf(Tree tree)
    {
        for (Control control = tree; control != null; control = control.getParent())
            if (control.getData(DT_TREE_VIEWER_KEY) instanceof TreeViewer viewer)
                return viewer;
        if (!PlatformUI.isWorkbenchRunning())
            return null;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            for (IWorkbenchPage page : window.getPages())
                for (IViewReference reference : page.getViewReferences())
                {
                    IViewPart view = reference.getView(false);
                    Object commonViewer = view != null ? Global.invoke(view, "getCommonViewer") : null; //$NON-NLS-1$
                    if (commonViewer instanceof CommonViewer viewer && viewer.getTree() == tree)
                        return viewer;
                }
        return null;
    }

    private static String objectKey(EObject object)
    {
        if (object == null)
            return null;
        try
        {
            return EcoreUtil.getURI(object).toString();
        }
        catch (RuntimeException e)
        {
            return object.eClass().getName() + '@' + System.identityHashCode(object);
        }
    }

    private static TreeItem[] findAddedItems(Tree tree, TreeItem[] items, Set<String> before)
    {
        java.util.ArrayList<TreeItem> result = new java.util.ArrayList<>();
        collectAddedItems(tree, items, before, result);
        return result.toArray(new TreeItem[0]);
    }

    private static void collectAddedItems(Tree tree, TreeItem[] items, Set<String> before,
        java.util.List<TreeItem> result)
    {
        for (TreeItem item : items)
        {
            String key = objectKey(elementObject(tree, item.getData()));
            if (key != null && !before.contains(key))
                result.add(item);
            collectAddedItems(tree, item.getItems(), before, result);
        }
    }

    private static ISelectionChangedListener findListener(TreeViewer viewer, String className)
    {
        Object listenerList = Global.getField(viewer, "selectionChangedListeners"); //$NON-NLS-1$
        Object raw = listenerList != null ? Global.invoke(listenerList, "getListeners") : null; //$NON-NLS-1$
        if (!(raw instanceof Object[] listeners))
            return null;
        for (Object listener : listeners)
            if (listener instanceof ISelectionChangedListener selectionListener
                && className.equals(listener.getClass().getName()))
                return selectionListener;
        return null;
    }

    private static final class PasteSnapshot
    {
        private final TreeViewer viewer;
        private final Set<String> before;

        private PasteSnapshot(TreeViewer viewer, Set<String> before)
        {
            this.viewer = viewer;
            this.before = before;
        }
    }

    /** Выделение добавленного объекта без зависимости от уже загруженного сервиса копирования EDT. */
    private static final class ModelAddObserver extends EContentAdapter
    {
        private final EObject owner;
        private final TreeViewer viewer;
        private final Display display;
        private final Set<EObject> pending = new LinkedHashSet<>();
        private boolean scheduled;
        private boolean disposed;

        private ModelAddObserver(EObject owner, TreeViewer viewer)
        {
            this.owner = owner;
            this.viewer = viewer;
            this.display = viewer.getTree().getDisplay();
        }

        @Override
        public void notifyChanged(Notification notification)
        {
            super.notifyChanged(notification);
            if (disposed || notification == null || notification.isTouch())
                return;
            int eventType = notification.getEventType();
            if (eventType != Notification.ADD && eventType != Notification.ADD_MANY)
                return;
            List<EObject> added = new ArrayList<>();
            Object value = notification.getNewValue();
            if (value instanceof EObject object)
                added.add(object);
            else if (value instanceof Collection<?> collection)
                for (Object item : collection)
                    if (item instanceof EObject object)
                        added.add(object);
            if (added.isEmpty())
                return;
            synchronized (this)
            {
                pending.addAll(added);
                if (scheduled)
                    return;
                scheduled = true;
            }
            display.asyncExec(() -> display.timerExec(120, this::flush));
        }

        private void flush()
        {
            List<EObject> candidates;
            synchronized (this)
            {
                candidates = new ArrayList<>(pending);
                pending.clear();
                scheduled = false;
            }
            selectVisibleAdded(candidates, 0);
        }

        private void selectVisibleAdded(List<EObject> candidates, int attempt)
        {
            Tree tree = viewer.getTree();
            if (disposed || tree == null || tree.isDisposed())
                return;
            List<Object> rows = new ArrayList<>();
            collectObjectRows(tree, tree.getItems(), candidates, rows);
            if (!rows.isEmpty())
            {
                viewer.setSelection(new StructuredSelection(rows), true);
                tree.showSelection();
                return;
            }
            if (attempt < 20)
                display.timerExec(150, () -> selectVisibleAdded(candidates, attempt + 1));
        }

        private void dispose()
        {
            disposed = true;
            if (owner.eAdapters().contains(this))
                owner.eAdapters().remove(this);
            synchronized (this)
            {
                pending.clear();
                scheduled = false;
            }
        }
    }

    /**
     * Обёртка штатного {@code DtTreeView$SelectionListener}: пустое выделение, пришедшее от
     * программного применения события, не пропускается — вместо подстановки верхней видимой
     * строки возвращается прежняя.
     */
    private static final class KeepSelectionListener
        implements ISelectionChangedListener
    {
        private final TreeViewer viewer;
        private final ISelectionChangedListener stock;
        private ISelection lastNonEmpty;
        private boolean restoring;

        KeepSelectionListener(TreeViewer viewer, ISelectionChangedListener stock)
        {
            this.viewer = viewer;
            this.stock = stock;
        }

        @Override
        public void selectionChanged(SelectionChangedEvent event)
        {
            ISelection selection = event != null ? event.getSelection() : null;
            if (selection != null && !selection.isEmpty())
            {
                lastNonEmpty = selection;
                restoring = false;
                stock.selectionChanged(event);
                return;
            }
            // Повторный промах при восстановлении (строка устарела после обновления дерева) —
            // отдаём штатному, иначе выделение зациклится.
            if (restoring || lastNonEmpty == null || !appliedByTreeView())
            {
                restoring = false;
                stock.selectionChanged(event);
                return;
            }
            restoring = true;
            try
            {
                viewer.setSelection(lastNonEmpty, false);
            }
            finally
            {
                restoring = false;
            }
            if (viewer.getSelection().isEmpty())
            {
                stock.selectionChanged(event);
                return;
            }
            Debug.log("промах программного выделения — текущая строка сохранена"); //$NON-NLS-1$
        }

        /**
         * Пустое выделение пришло из применения события дерева ({@code DtTreeView}), а не от
         * действий пользователя: у пользовательского клика в стеке только рассылка SWT/JFace.
         */
        private static boolean appliedByTreeView()
        {
            for (StackTraceElement frame : new Throwable().getStackTrace())
                if (frame.getClassName().startsWith(DT_TREE_VIEW_CLASS))
                    return true;
            return false;
        }
    }

    /** Одна основная форма и фоновый кеш видимых ячеек её колонки. */
    private static final class FormColumn
    {
        private static final int BATCH_SIZE = 50;

        private static final int SCHEDULE_DELAY_MS = 100;

        private static final CellResult ABSENT = new CellResult(null);

        private final EStructuralFeature feature;
        private final BasicForm basicForm;
        private final Map<EObject, CellResult> resolved = new WeakHashMap<>();
        private final LinkedHashSet<EObject> pending = new LinkedHashSet<>();
        private TreeColumn column;
        private TreeViewer viewer;
        private Job job;
        private boolean scheduled;
        private boolean paintHooked;

        private FormColumn(EStructuralFeature feature, BasicForm basicForm)
        {
            this.feature = feature;
            this.basicForm = basicForm;
        }

        static FormColumn create(EStructuralFeature feature, BasicForm basicForm)
        {
            return new FormColumn(feature, basicForm);
        }

        String title()
        {
            String name = basicForm.getName();
            return name == null || name.isBlank() ? feature.getName() : name;
        }

        void attach(TreeViewer newViewer)
        {
            viewer = newViewer;
            Tree tree = newViewer != null ? newViewer.getTree() : null;
            if (paintHooked || tree == null || tree.isDisposed())
                return;
            paintHooked = true;
            tree.addListener(SWT.PaintItem, event ->
            {
                if (!(event.item instanceof TreeItem row) || column == null || column.isDisposed()
                    || event.index != tree.indexOf(column))
                    return;
                EObject object = elementObject(tree, row.getData());
                if (MdEditorAttributeMenuHook.isDataMember(object))
                    request(object, newViewer);
            });
        }

        /**
         * Провайдер возвращает только готовый результат. JFace может запросить подпись
         * и для строки ниже области прокрутки, поэтому запуск расчёта идёт из {@code SWT.PaintItem}.
         */
        String textFor(EObject object, TreeViewer requestingViewer)
        {
            if (object == null)
                return ""; //$NON-NLS-1$
            CellResult result;
            synchronized (this)
            {
                viewer = requestingViewer;
                result = resolved.get(object);
            }
            if (result == null)
                return ""; //$NON-NLS-1$
            return result.item != null ? "+" : "-"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        private void request(EObject object, TreeViewer requestingViewer)
        {
            if (object == null)
                return;
            boolean start = false;
            synchronized (this)
            {
                viewer = requestingViewer;
                if (!resolved.containsKey(object) && pending.add(object))
                {
                    start = !scheduled;
                    if (start)
                        scheduled = true;
                }
            }
            if (start)
                job().schedule(SCHEDULE_DELAY_MS);
        }

        private Job job()
        {
            synchronized (this)
            {
                if (job == null)
                {
                    job = new Job("Комфорт: связи реквизитов с основной формой") //$NON-NLS-1$
                    {
                        @Override
                        protected IStatus run(IProgressMonitor monitor)
                        {
                            runBatch(monitor);
                            return Status.OK_STATUS;
                        }
                    };
                    job.setSystem(true);
                    job.setPriority(Job.DECORATE);
                }
                return job;
            }
        }

        private void runBatch(IProgressMonitor monitor)
        {
            List<EObject> batch = new ArrayList<>(BATCH_SIZE);
            synchronized (this)
            {
                for (EObject object : pending)
                {
                    batch.add(object);
                    if (batch.size() >= BATCH_SIZE)
                        break;
                }
                pending.removeAll(batch);
            }
            Map<EObject, CellResult> computed = compute(batch, monitor);
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> apply(batch, computed));
        }

        private Map<EObject, CellResult> compute(List<EObject> batch, IProgressMonitor monitor)
        {
            Map<EObject, CellResult> result = new HashMap<>();
            for (EObject object : batch)
                result.put(object, ABSENT);
            try
            {
                AbstractForm resolvedForm = basicForm.getForm();
                if (resolvedForm != null && resolvedForm.eIsProxy())
                    resolvedForm = (AbstractForm)EcoreUtil.resolve(resolvedForm, basicForm);
                if (!(resolvedForm instanceof Form form))
                    return result;
                TreeIterator<EObject> contents = form.eAllContents();
                while (contents.hasNext() && (monitor == null || !monitor.isCanceled()))
                {
                    EObject value = contents.next();
                    if (!(value instanceof DataItem item))
                        continue;
                    AbstractDataPath path = item.getDataPath();
                    if (path == null)
                        continue;
                    for (DataPathReferredObject referred : path.getObjects())
                    {
                        EObject referredObject = referred != null ? referred.getObject() : null;
                        if (referredObject == null)
                            continue;
                        EObject metadataObject = FormEditorHook.resolveMetadataFormReference(referredObject);
                        for (EObject object : batch)
                            if (result.get(object) == ABSENT
                                && MdEditorAttributeMenuHook.sameObject(metadataObject, object))
                                result.put(object, new CellResult(item));
                    }
                }
            }
            catch (RuntimeException | LinkageError ignored)
            {
            }
            return result;
        }

        private void apply(List<EObject> batch, Map<EObject, CellResult> computed)
        {
            TreeViewer currentViewer;
            boolean again;
            synchronized (this)
            {
                resolved.putAll(computed);
                scheduled = false;
                currentViewer = viewer;
                again = !pending.isEmpty();
                if (again)
                    scheduled = true;
            }
            if (currentViewer != null && currentViewer.getControl() != null
                && !currentViewer.getControl().isDisposed())
                updateRows(currentViewer, currentViewer.getTree().getItems(), batch);
            if (again)
                job().schedule(SCHEDULE_DELAY_MS);
        }

        private static void updateRows(TreeViewer viewer, TreeItem[] rows, List<EObject> objects)
        {
            for (TreeItem row : rows)
            {
                EObject rowObject = elementObject(viewer.getTree(), row.getData());
                if (rowObject != null)
                    for (EObject object : objects)
                        if (MdEditorAttributeMenuHook.sameObject(rowObject, object))
                        {
                            viewer.update(row.getData(), null);
                            break;
                        }
                if (row.getExpanded())
                    updateRows(viewer, row.getItems(), objects);
            }
        }
    }

    private static final class CellResult
    {
        private final FormItem item;

        private CellResult(FormItem item)
        {
            this.item = item;
        }
    }

    /** Подменяет стиль штатного {@code TreeViewer} на {@code SWT.MULTI} для редактора МД. */
    private static final class TreeStyleWeavingHook
        implements WeavingHook
    {
        @Override
        public void weave(WovenClass wovenClass)
        {
            if (wovenClass.getState() != WovenClass.TRANSFORMING)
                return;
            boolean treeView = DT_TREE_VIEW_INTERNAL.replace('/', '.').equals(wovenClass.getClassName());
            boolean copySupport = MODEL_OBJECT_COPY_SUPPORT_INTERNAL.replace('/', '.')
                .equals(wovenClass.getClassName());
            if (!treeView && !copySupport)
                return;
            try
            {
                byte[] transformed = treeView ? transformTreeStyle(wovenClass.getBytes())
                    : transformCopyResult(wovenClass.getBytes());
                if (transformed == null)
                    return;
                wovenClass.getDynamicImports().add("tormozit"); //$NON-NLS-1$
                wovenClass.setBytes(transformed);
            }
            catch (Throwable ignored)
            {
            }
        }
    }

    private static byte[] transformCopyResult(byte[] source)
    {
        ClassReader reader = new ClassReader(source);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        AtomicBoolean touched = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"copyAndAttach".equals(name) //$NON-NLS-1$
                    || (!COPY_CONTAINED_DESCRIPTOR.equals(descriptor)
                        && !COPY_TOP_DESCRIPTOR.equals(descriptor)))
                    return mv;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitInsn(int opcode)
                    {
                        if (opcode == Opcodes.ARETURN)
                        {
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "tormozit/MdEditorTreeHook", //$NON-NLS-1$
                                "afterCopied", "(Ljava/util/List;)V", false); //$NON-NLS-1$ //$NON-NLS-2$
                            touched.set(true);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return touched.get() ? writer.toByteArray() : null;
    }

    private static byte[] transformTreeStyle(byte[] source)
    {
        ClassReader reader = new ClassReader(source);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
        {
            @Override
            protected String getCommonSuperClass(String type1, String type2)
            {
                return "java/lang/Object"; //$NON-NLS-1$
            }
        };
        AtomicBoolean touched = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"creareTreeViewer".equals(name) //$NON-NLS-1$
                    || !"(Lorg/eclipse/swt/widgets/Composite;)Lorg/eclipse/jface/viewers/TreeViewer;".equals(descriptor)) //$NON-NLS-1$
                    return mv;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    private boolean parentOnStack;

                    @Override
                    public void visitVarInsn(int opcode, int varIndex)
                    {
                        super.visitVarInsn(opcode, varIndex);
                        if (opcode == Opcodes.ALOAD && varIndex == 1)
                        {
                            super.visitInsn(Opcodes.DUP);
                            parentOnStack = true;
                        }
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand)
                    {
                        super.visitIntInsn(opcode, operand);
                        if (parentOnStack && opcode == Opcodes.SIPUSH && operand == 2820)
                        {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "tormozit/MdEditorTreeHook", //$NON-NLS-1$
                                "metadataEditorTreeStyle", "(Lorg/eclipse/swt/widgets/Composite;I)I", false); //$NON-NLS-1$ //$NON-NLS-2$
                            touched.set(true);
                            parentOnStack = false;
                        }
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return touched.get() ? writer.toByteArray() : null;
    }

    private static final class Debug
    {
        private static final String TAG = "MdEditorTree"; //$NON-NLS-1$

        private Debug() {}

        static void log(String message)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, message);
        }
    }
}
