package tormozit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

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
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.layout.TreeColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.window.Window;
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
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
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
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.navigator.CommonViewer;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmEditingContext;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.shared.MdUiSharedImages;
import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.DataItem;
import com._1c.g5.v8.dt.form.model.DataPathReferredObject;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.FunctionalOption;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.ui.dialog.ListItemSelectionDialog;
import com._1c.g5.v8.dt.ui.util.OpenHelper;

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

    private static final String FORM_FILLER_COLUMN_MARKER =
        "tormozit.mdEditorFormFillerColumn"; //$NON-NLS-1$

    private static final String NATIVE_STRETCH_REMOVED_MARKER =
        "tormozit.mdEditorNativeStretchRemoved"; //$NON-NLS-1$

    private static final String NATIVE_STRETCH_LISTENER_CLASS =
        "com._1c.g5.v8.dt.common.ui.ControlWithColumnUtils$1"; //$NON-NLS-1$

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

    private static final String FO_PANEL_GUARD_MARKER = "tormozit.mdEditorFoPanelGuard"; //$NON-NLS-1$

    private static final String FO_PANEL_RECHECK_MARKER = "tormozit.mdEditorFoPanelRecheck"; //$NON-NLS-1$

    private static final String FO_COUNT_COLUMN_MARKER = "tormozit.mdEditorFoCountColumn"; //$NON-NLS-1$

    private static final String FO_COUNT_INDEX_KEY = "tormozit.mdEditorFoCountIndex"; //$NON-NLS-1$

    private static final String FO_COUNT_JOB_KEY = "tormozit.mdEditorFoCountJob"; //$NON-NLS-1$

    private static final String FO_PICKER_SETTINGS_SECTION = "MdEditorTreeHook.foPickerDialog"; //$NON-NLS-1$

    private static final String PROPERTY_SHEET_VIEW_ID = "org.eclipse.ui.views.PropertySheet"; //$NON-NLS-1$

    private static final String FO_PANEL_MARKER = "tormozit.mdEditorFoPanelInstalled"; //$NON-NLS-1$

    private static final String FO_PANEL_TABLE_KEY = "tormozit.mdEditorFoPanelTable"; //$NON-NLS-1$

    private static final String FO_PANEL_TITLE_KEY = "tormozit.mdEditorFoPanelTitle"; //$NON-NLS-1$

    private static final String FO_PANEL_LOG_TOPIC = "fo-data-panel"; //$NON-NLS-1$

    private static final int FO_PANEL_HEIGHT = 110;

    private static final int FO_PANEL_MIN_HEIGHT = 60;

    private static final int FO_PANEL_MAX_HEIGHT = 400;

    private static final String FO_PANEL_SETTINGS_SECTION = "MdEditorTreeHook.foPanel"; //$NON-NLS-1$

    private static final String FO_PANEL_HEIGHT_KEY = "height"; //$NON-NLS-1$

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
            installFunctionalOptionsPanel(viewer);
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
        {
            Global.tempLog("form-columns-width", "installFormColumns: нет классифицируемой строки, выход"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (isStandardAttributesTree(tree))
        {
            Global.tempLog("form-columns-width", "installFormColumns: дерево стандартных реквизитов, колонки не создаём"); //$NON-NLS-1$ //$NON-NLS-2$
            tree.setData(FORM_COLUMNS_CLASSIFIED_MARKER, Boolean.TRUE);
            removeFormColumns(tree);
            return;
        }
        tree.setData(FORM_COLUMNS_CLASSIFIED_MARKER, Boolean.TRUE);
        DtGranularEditor<?> editor = MdEditorAttributeMenuHook.editorOf(tree);
        EObject owner = editor != null ? editor.getModel() : null;
        if (owner == null)
        {
            Global.tempLog("form-columns-width", "installFormColumns: owner == null, выход"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        List<FormColumn> forms = mainForms(owner);
        String signature = formColumnsSignature(forms);
        Global.tempLog("form-columns-width", "installFormColumns: forms.size()=" + forms.size() //$NON-NLS-1$ //$NON-NLS-2$
            + ", signature=" + signature + ", cached=" + tree.getData(FORM_COLUMNS_SIGNATURE_KEY) //$NON-NLS-1$ //$NON-NLS-2$
            + ", columnCount=" + tree.getColumnCount()); //$NON-NLS-1$
        if (signature.equals(tree.getData(FORM_COLUMNS_SIGNATURE_KEY)))
        {
            if (tree.getData(FORM_COLUMNS_KEY) instanceof List<?> installed)
                for (Object value : installed)
                    if (value instanceof FormColumn form)
                        form.attach(viewer);
            FormTreeInteraction.install(tree, viewer);
            disableNativeColumnStretch(tree);
            if (tree.getColumnCount() > 0)
                installNameColumnWidthPersistence(tree.getColumn(0));
            Global.tempLog("form-columns-width", "installFormColumns: сигнатура совпала, колонки переиспользованы"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        for (TreeColumn column : tree.getColumns())
            if (Boolean.TRUE.equals(column.getData(FORM_COLUMN_MARKER)))
                column.dispose();
        if (forms.isEmpty())
        {
            Global.tempLog("form-columns-width", "installFormColumns: forms.isEmpty(), колонки форм не создаём"); //$NON-NLS-1$ //$NON-NLS-2$
            tree.setData(FORM_COLUMNS_KEY, List.of());
            tree.setData(FORM_COLUMNS_SIGNATURE_KEY, signature);
            return;
        }

        TreeColumn name;
        if (tree.getColumnCount() == 0)
        {
            name = new TreeColumn(tree, SWT.LEFT);
            name.setData(FORM_NAME_COLUMN_MARKER, Boolean.TRUE);
        }
        else
        {
            // Штатный DtTreeView создаёт единственную стартовую колонку сам, ДО нас, и регистрирует
            // для неё в TreeColumnLayout родителя ColumnWeightData (растягивать на всю ширину) — без
            // явной перерегистрации на ColumnPixelData любой наш setWidth() был бы переписан обратно
            // при первом же layout() (issue: колонка «Реквизиты» всегда 100% ширины панели).
            name = tree.getColumn(0);
        }
        setFixedColumnWidth(tree, name, NameColumnWidthStore.load(tree));
        installNameColumnWidthPersistence(name);

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
            setFixedColumnWidth(tree, column, 90);
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
        // Свободное место справа остаётся пустым местом панели, а не растягивает видимые колонки:
        // штатный com._1c.g5.v8.dt.common.ui.ControlWithColumnUtils.addStretchLastColumn (слушатель
        // SWT.Resize, вешается на дерево ещё в DtTreeView.createColumns) иначе сам находит «последнюю
        // resizable колонку с шириной больше 0» и растягивает её — без затычки на эту роль попадала бы
        // видимая колонка формы; сам штатный слушатель снимается отдельно (disableNativeColumnStretch).
        // Ширина 0 (не 1): слушатель уже снят, а лишний 1px давал вторую линию сетки сразу после
        // границы последней видимой колонки — визуально «двойная» разделительная линия.
        TreeViewerColumn fillerViewerColumn = new TreeViewerColumn(viewer, SWT.LEFT);
        TreeColumn filler = fillerViewerColumn.getColumn();
        filler.setData(FORM_COLUMN_MARKER, Boolean.TRUE);
        filler.setData(FORM_FILLER_COLUMN_MARKER, Boolean.TRUE);
        filler.setMoveable(false);
        setFixedColumnWidth(tree, filler, 0);
        // Голая TreeColumn без своего label provider'а показала бы в ячейках toString() элемента
        // JFace-модели (issue: "com._1c.g5.aef2...TreeItemViewModel..." в каждой строке) — у затычки
        // должен быть пустой provider, как у остальных наших колонок.
        fillerViewerColumn.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return ""; //$NON-NLS-1$
            }
        });
        tree.setHeaderVisible(true);
        ThemeAwareColors.applyGridLines(tree);
        tree.setData(FORM_COLUMNS_KEY, forms);
        tree.setData(FORM_COLUMNS_SIGNATURE_KEY, signature);
        for (FormColumn form : forms)
            form.attach(viewer);
        installFormColumnDoubleClick(tree);
        FormTreeInteraction.install(tree, viewer);
        disableNativeColumnStretch(tree);
        Global.tempLog("form-columns-width", "installFormColumns: колонки созданы, columnCount=" //$NON-NLS-1$ //$NON-NLS-2$
            + tree.getColumnCount() + ", headerVisible=" + tree.getHeaderVisible()); //$NON-NLS-1$
        Composite parent = tree.getParent();
        if (parent != null && !parent.isDisposed())
            parent.layout(true, true);
        tree.getDisplay().timerExec(2000, () -> logSettledWidths(tree));
    }

    /** Снимок ширин колонок через 2с после установки — видно, к чему пришла подгонка на самом деле. */
    private static void logSettledWidths(Tree tree)
    {
        if (tree.isDisposed())
            return;
        StringBuilder widths = new StringBuilder();
        for (TreeColumn column : tree.getColumns())
            widths.append(column.getWidth()).append(','); //$NON-NLS-1$
        Global.tempLog("form-columns-width", "logSettledWidths: clientWidth=" + tree.getClientArea().width //$NON-NLS-1$ //$NON-NLS-2$
            + ", widths=" + widths); //$NON-NLS-1$
    }

    /**
     * Задать колонке фиксированную ширину в пикселях, переживающую {@code layout()} родителя. Голого
     * {@link TreeColumn#setWidth} недостаточно для колонки, которой уже управляет {@link TreeColumnLayout}
     * родительской панели (штатная стартовая колонка {@code DtTreeView} зарегистрирована в нём с
     * {@code ColumnWeightData} — растягивать на всю ширину клиентской области; без перерегистрации на
     * {@link ColumnPixelData} эта ширина восстанавливается при первом же {@code layout()}).
     */
    private static void setFixedColumnWidth(Tree tree, TreeColumn column, int width)
    {
        column.setWidth(width);
        Composite host = tree.getParent();
        if (host != null && !host.isDisposed() && host.getLayout() instanceof TreeColumnLayout layout)
            layout.setColumnData(column, new ColumnPixelData(width, true, false));
    }

    /** Метка «слушатель ресайза для сохранения ширины уже стоит» — на самой колонке, идемпотентно. */
    private static final String NAME_COLUMN_WIDTH_LISTENER_MARKER =
        "tormozit.mdEditorNameColumnWidthListener"; //$NON-NLS-1$

    /** Запоминает ширину колонки-дерева («Реквизиты»), которую пользователь потянул мышью. */
    private static void installNameColumnWidthPersistence(TreeColumn name)
    {
        if (name == null || name.isDisposed()
            || Boolean.TRUE.equals(name.getData(NAME_COLUMN_WIDTH_LISTENER_MARKER)))
            return;
        name.setData(NAME_COLUMN_WIDTH_LISTENER_MARKER, Boolean.TRUE);
        name.addListener(SWT.Resize, event -> NameColumnWidthStore.save(name.getWidth()));
    }

    /** Ширина колонки-дерева («Реквизиты») на вкладке «Данные» — между сеансами EDT. */
    private static final class NameColumnWidthStore
    {
        private static final String PREF_WIDTH = "tormozit.mdEditor.attributesNameColumn.width"; //$NON-NLS-1$
        /** Ширина по умолчанию (нет сохранённого значения) — 30 символов текущего шрифта дерева. */
        private static final int DEFAULT_WIDTH_CHARS = 30;
        private static final int FALLBACK_WIDTH_PX = 300;
        private static final int MIN_WIDTH = 100;
        private static final int MAX_WIDTH = 1000;

        private static ScopedPreferenceStore prefs;

        private NameColumnWidthStore()
        {
        }

        /** @param control контрол, чьим шрифтом мерить 50 символов по умолчанию (обычно само дерево). */
        static int load(Control control)
        {
            ScopedPreferenceStore store = prefs();
            if (store == null || !store.contains(PREF_WIDTH))
                return clamp(defaultWidth(control));
            return clamp(store.getInt(PREF_WIDTH));
        }

        private static int defaultWidth(Control control)
        {
            if (control == null || control.isDisposed())
                return FALLBACK_WIDTH_PX;
            GC gc = new GC(control);
            try
            {
                int charWidth = gc.textExtent("00").x / 2; //$NON-NLS-1$
                return DEFAULT_WIDTH_CHARS * charWidth;
            }
            finally
            {
                gc.dispose();
            }
        }

        static void save(int width)
        {
            ScopedPreferenceStore store = prefs();
            if (store == null)
                return;
            store.setValue(PREF_WIDTH, clamp(width));
            try
            {
                store.save();
            }
            catch (Exception ignored)
            {
                // настройки необязательны
            }
        }

        private static int clamp(int width)
        {
            if (width < MIN_WIDTH)
                return MIN_WIDTH;
            if (width > MAX_WIDTH)
                return MAX_WIDTH;
            return width;
        }

        private static ScopedPreferenceStore prefs()
        {
            if (prefs != null)
                return prefs;
            try
            {
                String pluginId = FrameworkUtil.getBundle(NameColumnWidthStore.class).getSymbolicName();
                prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
            }
            catch (RuntimeException ignored)
            {
                return null;
            }
            return prefs;
        }
    }

    /**
     * Снимает штатный {@code SWT.Resize}-слушатель {@code ControlWithColumnUtils.addStretchLastColumn}
     * (вешается на дерево ещё в {@code DtTreeView.createColumns}, до наших колонок): он растягивает
     * «последнюю resizable колонку с шириной больше 0» на весь остаток при каждом ресайзе. Ширины у
     * плагина статичные (см. {@link #setFixedColumnWidth}), лишнее место после затычки остаётся
     * пустым местом панели — без него нативный слушатель забирал этот остаток себе, дёргая то одну,
     * то другую видимую колонку формы (issue: перетаскивание границы колонки роняло их ширины).
     */
    private static void disableNativeColumnStretch(Tree tree)
    {
        if (Boolean.TRUE.equals(tree.getData(NATIVE_STRETCH_REMOVED_MARKER)))
            return;
        tree.setData(NATIVE_STRETCH_REMOVED_MARKER, Boolean.TRUE);
        for (Listener listener : tree.getListeners(SWT.Resize))
            if (NATIVE_STRETCH_LISTENER_CLASS.equals(listener.getClass().getName()))
                tree.removeListener(SWT.Resize, listener);
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

    /**
     * Колонка «ФО» в дереве реквизитов вкладки «Данные» — число функциональных опций,
     * в состав которых входит реквизит (как на вкладке «Функц. опции», см.
     * {@code MdEditorFunctionalOptionsCountHook}). В дерево «Стандартные реквизиты» не
     * встраивается.
     */
    private static void installFunctionalOptionsPanel(TreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (!MdEditorAttributeMenuHook.isDataPageAttributesTree(tree))
            return;
        installFoColumnGuard(viewer);
        if (!hasClassifiableRow(tree) || isStandardAttributesTree(tree))
            return;
        DtGranularEditor<?> editor = MdEditorAttributeMenuHook.editorOf(tree);
        if (editor == null)
            return;
        installFunctionalOptionsCountColumn(viewer, tree, editor);
        installIncludedOptionsList(viewer, tree, editor);
    }

    /**
     * Под деревом реквизитов — список функциональных опций, в состав которых УЖЕ включён
     * выделенный элемент (или сам объект метаданных, если выделение не подходит — тот же
     * запасной вариант, что и у колонки «ФО», см. {@link #functionalOptionsTargetFor}). Без
     * фильтра (в оригинале на вкладке «Функц. опции» он есть, здесь не нужен). «Изменить»
     * открывает выбранную опцию; «Удалить» исключает текущий элемент из её состава (не удаляет
     * саму опцию). Контекстное меню и горячие клавиши (Свойства, Сфокусировать в Навигаторе,
     * Найти ссылки на объект и т.п.) — штатные объектные команды EDT, полученные регистрацией
     * списка как поставщика выделения страницы, а не переписанные вручную.
     */
    private static void installIncludedOptionsList(TreeViewer viewer, Tree tree, DtGranularEditor<?> editor)
    {
        if (Boolean.TRUE.equals(tree.getData(FO_PANEL_MARKER)))
            return;
        Composite parent = tree.getParent();
        if (parent == null || parent.isDisposed())
            return;
        Layout layout = parent.getLayout();
        if (!(layout instanceof GridLayout gridLayout))
        {
            Global.tempLog(FO_PANEL_LOG_TOPIC, "родитель дерева реквизитов не GridLayout: " //$NON-NLS-1$
                + (layout == null ? "null" : layout.getClass().getName())); //$NON-NLS-1$
            tree.setData(FO_PANEL_MARKER, Boolean.TRUE);
            return;
        }
        tree.setData(FO_PANEL_MARKER, Boolean.TRUE);

        Sash sash = new Sash(parent, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, gridLayout.numColumns, 1));
        sash.setToolTipText(TooltipText.wrap(sash,
            "Перетащите, чтобы изменить высоту панели функциональных опций." //$NON-NLS-1$
                + Global.pluginSignForTooltip()));

        Composite panel = new Composite(parent, SWT.NONE);
        GridData panelData = new GridData(SWT.FILL, SWT.FILL, true, false, gridLayout.numColumns, 1);
        panelData.heightHint = loadFoPanelHeight();
        panel.setLayoutData(panelData);
        sash.addListener(SWT.Selection, event ->
        {
            if (event.detail == SWT.DRAG)
                return;
            int diff = event.y - sash.getBounds().y;
            int newHeight = Math.max(FO_PANEL_MIN_HEIGHT, Math.min(FO_PANEL_MAX_HEIGHT, panelData.heightHint - diff));
            if (newHeight == panelData.heightHint)
                return;
            panelData.heightHint = newHeight;
            saveFoPanelHeight(newHeight);
            parent.layout(true, true);
        });
        GridLayout panelLayout = new GridLayout(1, false);
        panelLayout.marginWidth = 0;
        panelLayout.marginHeight = 1;
        panelLayout.verticalSpacing = 2;
        panel.setLayout(panelLayout);

        Table table = new Table(panel, SWT.BORDER | SWT.V_SCROLL | SWT.MULTI);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        table.setHeaderVisible(false);
        ThemeAwareColors.applyGridLines(table);
        CopyCommandSupport.wireCopyOverride(table);
        table.addListener(SWT.MouseDoubleClick, event -> openSelectedFunctionalOption(editor, table, viewer));
        table.addListener(SWT.KeyDown, event ->
        {
            if (event.keyCode == SWT.DEL)
                unassignSelectedOption(editor, table, tree, viewer);
        });

        Composite header = new Composite(panel, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout headerLayout = new GridLayout(4, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 0;
        header.setLayout(headerLayout);
        header.moveAbove(table);

        Label icon = new Label(header, SWT.NONE);
        icon.setImage(foSectionImage());

        Label title = new Label(header, SWT.NONE);
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        title.setToolTipText(TooltipText.wrap(title,
            "Функциональные опции, в состав которых уже включён выделенный в дереве элемент" //$NON-NLS-1$
                + " (или сам объект метаданных, если выделение не подходит)." //$NON-NLS-1$
                + Global.pluginSignForTooltip()));

        Button editButton = new Button(header, SWT.PUSH);
        editButton.setText("Изменить"); //$NON-NLS-1$
        editButton.setToolTipText(TooltipText.wrap(editButton,
            "Выбрать функциональные опции для текущего элемента." //$NON-NLS-1$
                + Global.pluginSignForTooltip()));
        editButton.addListener(SWT.Selection, event -> openFunctionalOptionsPicker(editor, tree, viewer, table));

        Button deleteButton = new Button(header, SWT.PUSH);
        deleteButton.setText("Удалить"); //$NON-NLS-1$
        deleteButton.setToolTipText(TooltipText.wrap(deleteButton,
            "Исключить выделенный в дереве элемент из состава выбранных функциональных опций" //$NON-NLS-1$
                + ". Клавиша Delete делает то же самое." //$NON-NLS-1$
                + Global.pluginSignForTooltip()));
        deleteButton.addListener(SWT.Selection, event -> unassignSelectedOption(editor, table, tree, viewer));

        installFoPanelObjectCommands(table, editor, tree, viewer);

        tree.setData(FO_PANEL_TABLE_KEY, table);
        tree.setData(FO_PANEL_TITLE_KEY, title);
        viewer.addSelectionChangedListener(event -> updateIncludedOptionsList(tree, viewer, editor));
        tree.addListener(SWT.FocusIn, event -> updateIncludedOptionsList(tree, viewer, editor));
        tree.addDisposeListener(event ->
        {
            if (!sash.isDisposed())
                sash.dispose();
            if (!panel.isDisposed())
                panel.dispose();
        });

        parent.layout(true, true);
        updateIncludedOptionsList(tree, viewer, editor);
    }

    /** Та же картинка, что у пункта меню «Функциональные опции» на этой же вкладке. */
    private static Image foSectionImage()
    {
        try
        {
            Image image = MdUiSharedImages.getImage(MdUiSharedImages.OBJS_FUNCTIONAL_OPTION);
            return image != null && !image.isDisposed() ? image : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Попытка получить контекстное меню «бесплатно» через {@code registerContextMenu} провалилась:
     * подтягивается меню общего охвата {@code popup:org.eclipse.ui.popup.any} (Групповая
     * разработка / Сравнить / Заменить на / Вывести список — команды для объектов конфигурации
     * вообще), а не то более узкое меню списка, что видно на скриншоте оригинала. Поэтому пункты
     * меню — вручную, но только те, для которых нашлось подтверждённое действие:
     * <ul>
     *   <li>«Перейти в редактор объекта» — открыть саму функциональную опцию;</li>
     *   <li>«Удалить» — исключить текущий элемент из её состава (саму опцию не удаляет);</li>
     *   <li>«Сфокусировать в Навигаторе» — {@link NavigatorReveal#revealAndActivateIfHidden},
     *       тот же путь, что и у штатной команды {@code com._1c.g5.v8.dt.ui.commands.focusNavigator}
     *       (см. {@code ProjectStructureViewHook});</li>
     *   <li>«Свойства» — панель «Свойства» по текущему выделению списка.</li>
     * </ul>
     * «Добавить в расширение» и «Найти ссылки на объект» из оригинала не перенесены: не нашёл
     * подтверждённого id этих команд в разобранных бандлах, гадать не стал.
     */
    private static void installFoPanelObjectCommands(Table table, DtGranularEditor<?> editor, Tree tree,
        TreeViewer viewer)
    {
        if (editor.getSite() != null)
        {
            TableRowSelectionProvider provider = new TableRowSelectionProvider(table);
            ISelectionProvider[] previous = new ISelectionProvider[1];
            table.addListener(SWT.FocusIn, event ->
            {
                previous[0] = editor.getSite().getSelectionProvider();
                editor.getSite().setSelectionProvider(provider);
            });
            table.addListener(SWT.FocusOut, event ->
            {
                if (previous[0] != null)
                    editor.getSite().setSelectionProvider(previous[0]);
            });
        }

        Menu menu = new Menu(table);
        table.setMenu(menu);
        menu.addMenuListener(new MenuAdapter()
        {
            @Override
            public void menuShown(MenuEvent event)
            {
                for (MenuItem item : menu.getItems())
                    item.dispose();
                fillFoPanelMenu(menu, table, editor, tree, viewer);
            }
        });
    }

    private static void fillFoPanelMenu(Menu menu, Table table, DtGranularEditor<?> editor, Tree tree,
        TreeViewer viewer)
    {
        boolean hasSelection = table.getSelectionCount() > 0;

        MenuItem open = new MenuItem(menu, SWT.PUSH);
        open.setText("Перейти в редактор объекта"); //$NON-NLS-1$
        open.setEnabled(hasSelection);
        open.addListener(SWT.Selection, event -> openSelectedFunctionalOption(editor, table, viewer));

        MenuItem delete = new MenuItem(menu, SWT.PUSH);
        delete.setText("Удалить"); //$NON-NLS-1$
        delete.setEnabled(hasSelection);
        delete.addListener(SWT.Selection, event -> unassignSelectedOption(editor, table, tree, viewer));

        new MenuItem(menu, SWT.SEPARATOR);

        MenuItem reveal = new MenuItem(menu, SWT.PUSH);
        reveal.setText("Сфокусировать в Навигаторе"); //$NON-NLS-1$
        reveal.setEnabled(hasSelection);
        reveal.addListener(SWT.Selection, event -> revealSelectedOption(table));

        MenuItem properties = new MenuItem(menu, SWT.PUSH);
        properties.setText("Свойства"); //$NON-NLS-1$
        properties.setEnabled(hasSelection);
        properties.addListener(SWT.Selection, event -> bringFoPanelPropertiesToFront(editor));
    }

    private static void revealSelectedOption(Table table)
    {
        TableItem[] selection = table.getSelection();
        if (selection.length > 0 && selection[0].getData() instanceof FunctionalOption option)
            NavigatorReveal.revealAndActivateIfHidden(option);
    }

    private static void bringFoPanelPropertiesToFront(DtGranularEditor<?> editor)
    {
        if (editor.getSite() == null)
            return;
        IWorkbenchPage page = editor.getSite().getPage();
        if (page == null)
            return;
        IViewPart view = page.findView(PROPERTY_SHEET_VIEW_ID);
        if (view == null)
        {
            try
            {
                view = page.showView(PROPERTY_SHEET_VIEW_ID, null, IWorkbenchPage.VIEW_VISIBLE);
            }
            catch (PartInitException e)
            {
                Global.logError("MdEditorTreeFo", "showView PropertySheet", e); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
        }
        page.bringToTop(view);
    }

    private static void updateIncludedOptionsList(Tree tree, TreeViewer viewer, DtGranularEditor<?> editor)
    {
        if (tree.isDisposed())
            return;
        Label title = tree.getData(FO_PANEL_TITLE_KEY) instanceof Label l ? l : null;
        Table table = tree.getData(FO_PANEL_TABLE_KEY) instanceof Table t ? t : null;
        if (title == null || table == null || title.isDisposed() || table.isDisposed())
            return;
        MdObject target = resolveFunctionalOptionsTarget(viewer, editor);
        table.removeAll();
        if (target == null)
        {
            title.setText("Функциональные опции"); //$NON-NLS-1$
            return;
        }
        title.setText("Функциональные опции: " + foDisplayName(target)); //$NON-NLS-1$
        Configuration configuration = foConfigurationOf(editor);
        if (configuration == null)
            return;
        for (FunctionalOption option : configuration.getFunctionalOptions())
        {
            if (option == null || !option.getContent().contains(target))
                continue;
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(foDisplayName(option));
            item.setData(option);
        }
    }

    /**
     * Цель для колонки «ФО» и для списка внизу — общая: реквизит/табличная часть и т.п., если
     * выделен именно он, иначе сам объект метаданных. Одна и та же функция для обоих мест —
     * иначе число в колонке и состав списка разойдутся на строках-«не реквизитах».
     */
    private static MdObject functionalOptionsTargetFor(EObject object, DtGranularEditor<?> editor)
    {
        if (object instanceof MdObject member && MdEditorAttributeMenuHook.isDataMember(object))
            return member;
        return editor.getModel() instanceof MdObject owner ? owner : null;
    }

    /**
     * По выделению JFace-вьювера, не по сырому {@code Tree.getSelection()}: у этого дерева
     * (обёртка {@code DtTreeView}) нативное SWT-выделение при клике по строке не всегда успевает
     * обновиться к моменту срабатывания {@code SWT.Selection} — вьювер синхронизирован надёжнее
     * (тот же канал использует {@code KeepSelectionListener} рядом).
     */
    private static MdObject resolveFunctionalOptionsTarget(TreeViewer viewer, DtGranularEditor<?> editor)
    {
        Object selected = viewer.getStructuredSelection().getFirstElement();
        EObject resolved = selected != null ? elementObject(viewer.getTree(), selected) : null;
        return functionalOptionsTargetFor(resolved, editor);
    }

    /**
     * Открывает выбранную функциональную опцию и сразу выделяет в её «Составе» текущий элемент —
     * ту же строку, что активна в дереве реквизитов родительского окна.
     */
    private static void openSelectedFunctionalOption(DtGranularEditor<?> editor, Table table, TreeViewer viewer)
    {
        TableItem[] selection = table.getSelection();
        if (selection.length == 0 || !(selection[0].getData() instanceof FunctionalOption option))
            return;
        IWorkbenchPage page = editor.getSite() != null ? editor.getSite().getPage() : null;
        if (page == null)
            return;
        MdObject target = resolveFunctionalOptionsTarget(viewer, editor);
        IEditorPart opened;
        try
        {
            opened = new OpenHelper(page).openEditor(option);
        }
        catch (RuntimeException e)
        {
            Global.logError("MdEditorTreeFo", "openSelectedFunctionalOption", e); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (target != null && opened instanceof DtGranularEditor<?> foEditor)
            scheduleRevealInFunctionalOptionEditor(foEditor, target, 0);
    }

    private static void scheduleRevealInFunctionalOptionEditor(DtGranularEditor<?> foEditor, MdObject target,
        int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || attempt >= 40)
            return;
        display.timerExec(attempt == 0 ? 0 : 100, () ->
        {
            if (!revealInFunctionalOptionEditor(foEditor, target))
                scheduleRevealInFunctionalOptionEditor(foEditor, target, attempt + 1);
        });
    }

    /** «Состав» ({@code editors.functionalOption.pages.content}) — то же дерево, что у левой части вкладки «Функц. опции» объекта, только у самой опции. */
    private static boolean revealInFunctionalOptionEditor(DtGranularEditor<?> foEditor, MdObject target)
    {
        IFormPage page = findFormPage(foEditor, MdEditorTreeHook::isFunctionalOptionContentPage);
        if (page == null)
            return false;
        foEditor.setActivePage(page.getId());
        IFormPage active = foEditor.getActivePageInstance();
        if (!isFunctionalOptionContentPage(active))
            return false;
        Object root = Global.getField(active, "pageComponent"); //$NON-NLS-1$
        Object component = findFunctionalOptionContentComponent(root, 0);
        if (component == null)
            return false;
        try
        {
            return Global.invokeVoid(component, "setSelection", List.of(target)); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    private static boolean isFunctionalOptionContentPage(IFormPage page)
    {
        if (page == null)
            return false;
        if ("editors.functionalOption.pages.content".equals(page.getId())) //$NON-NLS-1$
            return true;
        return page.getClass().getName().contains("FunctionalOptionEditorContentPage"); //$NON-NLS-1$
    }

    private static Object findFunctionalOptionContentComponent(Object component, int depth)
    {
        if (component == null || depth > 20)
            return null;
        if (component.getClass().getName().contains("FunctionalOptionEditorContentPageComponent")) //$NON-NLS-1$
            return component;
        for (Object child : AefFieldFocus.childComponents(component))
        {
            Object found = findFunctionalOptionContentComponent(child, depth + 1);
            if (found != null)
                return found;
        }
        return null;
    }

    private static IFormPage findFormPage(DtGranularEditor<?> editor, Predicate<IFormPage> match)
    {
        Object pagesObj = Global.getField(editor, "pages"); //$NON-NLS-1$
        if (!(pagesObj instanceof List<?> pages))
            return null;
        for (Object pageObj : pages)
            if (pageObj instanceof IFormPage page && match.test(page))
                return page;
        return null;
    }

    /** «Удалить» в панели — исключает текущий элемент из состава опции, саму опцию не трогает. */
    /** «Удалить» и клавиша Delete — исключают ВСЕ выделенные опции из состава текущего элемента. */
    private static void unassignSelectedOption(DtGranularEditor<?> editor, Table table, Tree tree,
        TreeViewer viewer)
    {
        TableItem[] selection = table.getSelection();
        if (selection.length == 0)
            return;
        List<FunctionalOption> toRemove = new ArrayList<>();
        for (TableItem item : selection)
            if (item.getData() instanceof FunctionalOption option)
                toRemove.add(option);
        if (toRemove.isEmpty())
            return;
        MdObject target = resolveFunctionalOptionsTarget(viewer, editor);
        if (target == null)
            return;
        IBmEditingContext editingContext = editor.getEditingContext();
        if (editingContext == null)
            return;
        try
        {
            editingContext.execute(new AbstractBmTask<Void>("Комфорт: функциональная опция") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction transaction, IProgressMonitor monitor)
                {
                    if (!(transaction.toTransactionObject(target) instanceof MdObject txTarget))
                        return null;
                    for (FunctionalOption option : toRemove)
                    {
                        if (transaction.toTransactionObject(option) instanceof FunctionalOption txOption)
                            txOption.getContent().remove(txTarget);
                    }
                    return null;
                }
            });
        }
        catch (RuntimeException e)
        {
            Global.logError("MdEditorTreeFo", "unassignSelectedOption", e); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        updateIncludedOptionsList(tree, viewer, editor);
        refreshFunctionalOptionsCount(tree, viewer, editor);
    }

    /**
     * «Изменить» в панели — тот же штатный диалог EDT «Выбор объектов»
     * ({@link ListItemSelectionDialog}), что и в панели «Свойства» для функциональных опций (см.
     * javadoc {@link ListItemSelectionDialogFilterHook}): фильтр с историей, подсветка совпадений
     * и тулбар «отметить все / снять все» достаются бесплатно — тот хук патчит любой показанный
     * экземпляр этого класса, отдельно ничего строить не нужно. Пометки — функциональные опции,
     * в состав которых уже входит текущий элемент; изменения после «ОК» — одной BM-транзакцией.
     */
    private static void openFunctionalOptionsPicker(DtGranularEditor<?> editor, Tree tree, TreeViewer viewer,
        Table panelTable)
    {
        MdObject target = resolveFunctionalOptionsTarget(viewer, editor);
        if (target == null)
            return;
        Configuration configuration = foConfigurationOf(editor);
        if (configuration == null)
            return;
        List<FunctionalOption> options = new ArrayList<>(configuration.getFunctionalOptions());
        options.sort(Comparator.comparing(MdEditorTreeHook::foDisplayName, String.CASE_INSENSITIVE_ORDER));
        List<FunctionalOption> initiallyChecked = new ArrayList<>();
        for (FunctionalOption option : options)
            if (option.getContent().contains(target))
                initiallyChecked.add(option);
        TableItem[] panelSelection = panelTable.getSelection();
        FunctionalOption activeInParent = panelSelection.length > 0
            && panelSelection[0].getData() instanceof FunctionalOption selected ? selected : null;

        ILabelProvider labelProvider = new LabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return element instanceof FunctionalOption option ? foDisplayName(option) : super.getText(element);
            }
        };
        ListItemSelectionDialog dialog = new ListItemSelectionDialog(tree.getShell(), options,
            new StructuredSelection(initiallyChecked), labelProvider, ArrayContentProvider.getInstance(),
            "Выбор объектов", true, true) //$NON-NLS-1$
        {
            @Override
            protected IDialogSettings getDialogBoundsSettings()
            {
                return foPickerDialogSettings();
            }

            @Override
            protected int getDialogBoundsStrategy()
            {
                return DIALOG_PERSISTSIZE | DIALOG_PERSISTLOCATION;
            }

            @Override
            protected Control createDialogArea(Composite parent)
            {
                Control area = super.createDialogArea(parent);
                if (activeInParent != null && elementsTableViewer != null)
                    elementsTableViewer.setSelection(new StructuredSelection(activeInParent), false);
                return area;
            }
        };
        if (dialog.open() != Window.OK)
            return;
        Set<FunctionalOption> checked = new HashSet<>();
        for (Object element : dialog.getResult())
            if (element instanceof FunctionalOption option)
                checked.add(option);

        List<FunctionalOption> toAdd = new ArrayList<>();
        List<FunctionalOption> toRemove = new ArrayList<>();
        for (FunctionalOption option : options)
        {
            boolean was = initiallyChecked.contains(option);
            boolean now = checked.contains(option);
            if (now && !was)
                toAdd.add(option);
            else if (!now && was)
                toRemove.add(option);
        }
        if (toAdd.isEmpty() && toRemove.isEmpty())
            return;

        IBmEditingContext editingContext = editor.getEditingContext();
        if (editingContext == null)
            return;
        try
        {
            editingContext.execute(new AbstractBmTask<Void>("Комфорт: функциональные опции") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction transaction, IProgressMonitor monitor)
                {
                    if (!(transaction.toTransactionObject(target) instanceof MdObject txTarget))
                        return null;
                    for (FunctionalOption option : toAdd)
                    {
                        if (transaction.toTransactionObject(option) instanceof FunctionalOption txOption
                            && !txOption.getContent().contains(txTarget))
                            txOption.getContent().add(txTarget);
                    }
                    for (FunctionalOption option : toRemove)
                    {
                        if (transaction.toTransactionObject(option) instanceof FunctionalOption txOption)
                            txOption.getContent().remove(txTarget);
                    }
                    return null;
                }
            });
        }
        catch (RuntimeException e)
        {
            Global.logError("MdEditorTreeFo", "openFunctionalOptionsPicker", e); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        updateIncludedOptionsList(tree, viewer, editor);
        refreshFunctionalOptionsCount(tree, viewer, editor);
    }

    /**
     * {@link ISelectionProvider} по выделению строки списка ФО — подставляется в
     * {@code editor.getSite()} на время фокуса, чтобы панель «Свойства» показывала выбранную
     * функциональную опцию.
     */
    private static final class TableRowSelectionProvider implements ISelectionProvider
    {
        private final Table table;

        private final List<ISelectionChangedListener> listeners = new ArrayList<>();

        TableRowSelectionProvider(Table table)
        {
            this.table = table;
            table.addListener(SWT.Selection, event -> fireSelectionChanged());
        }

        private void fireSelectionChanged()
        {
            SelectionChangedEvent event = new SelectionChangedEvent(this, getSelection());
            for (ISelectionChangedListener listener : new ArrayList<>(listeners))
                listener.selectionChanged(event);
        }

        @Override
        public void addSelectionChangedListener(ISelectionChangedListener listener)
        {
            listeners.add(listener);
        }

        @Override
        public void removeSelectionChangedListener(ISelectionChangedListener listener)
        {
            listeners.remove(listener);
        }

        @Override
        public ISelection getSelection()
        {
            if (table.isDisposed())
                return StructuredSelection.EMPTY;
            List<FunctionalOption> options = new ArrayList<>();
            for (TableItem item : table.getSelection())
                if (item.getData() instanceof FunctionalOption option)
                    options.add(option);
            return options.isEmpty() ? StructuredSelection.EMPTY : new StructuredSelection(options);
        }

        @Override
        public void setSelection(ISelection selection)
        {
            // Выделение задаётся кликом пользователя по строке; программно не требуется.
        }
    }

    private static String foDisplayName(EObject object)
    {
        Object nameRu = Global.invoke(object, "getNameRu"); //$NON-NLS-1$
        if (nameRu instanceof String ru && !ru.isBlank())
            return ru;
        Object name = Global.invoke(object, "getName"); //$NON-NLS-1$
        return name instanceof String text && !text.isBlank() ? text : object.eClass().getName();
    }

    /** Перепроверяет тип дерева, когда ленивый viewer впервые отрисовал содержательную строку. */
    private static void installFoColumnGuard(TreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (Boolean.TRUE.equals(tree.getData(FO_PANEL_GUARD_MARKER)))
            return;
        tree.setData(FO_PANEL_GUARD_MARKER, Boolean.TRUE);
        tree.addListener(SWT.PaintItem, event ->
        {
            if (Boolean.TRUE.equals(tree.getData(FO_COUNT_COLUMN_MARKER))
                || Boolean.TRUE.equals(tree.getData(FO_PANEL_RECHECK_MARKER)))
                return;
            tree.setData(FO_PANEL_RECHECK_MARKER, Boolean.TRUE);
            tree.getDisplay().asyncExec(() ->
            {
                if (tree.isDisposed())
                    return;
                tree.setData(FO_PANEL_RECHECK_MARKER, null);
                installFunctionalOptionsPanel(viewer);
            });
        });
    }

    /** Колонка «ФО» — число функциональных опций, в состав которых входит реквизит. */
    private static void installFunctionalOptionsCountColumn(TreeViewer viewer, Tree tree,
        DtGranularEditor<?> editor)
    {
        if (Boolean.TRUE.equals(tree.getData(FO_COUNT_COLUMN_MARKER)))
            return;
        tree.setData(FO_COUNT_COLUMN_MARKER, Boolean.TRUE);
        TreeColumn name = tree.getColumnCount() == 0 ? new TreeColumn(tree, SWT.LEFT) : tree.getColumn(0);
        setFixedColumnWidth(tree, name, NameColumnWidthStore.load(tree));
        installNameColumnWidthPersistence(name);

        TreeViewerColumn column = new TreeViewerColumn(viewer, SWT.RIGHT, 1);
        TreeColumn swtColumn = column.getColumn();
//        swtColumn.setText("ФО"); //$NON-NLS-1$
        swtColumn.setImage(foSectionImage());
        swtColumn.setToolTipText(TooltipText.wrap(tree,
            "Число функциональных опций, в состав которых входит свойство данных, либо сам объект." //$NON-NLS-1$
                + " Опции редактируются в таблице снизу." //$NON-NLS-1$
                + Global.pluginSignForTooltip()));
        setFixedColumnWidth(tree, swtColumn, 30);
        swtColumn.setResizable(false);
        swtColumn.setMoveable(false);
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                Integer count = functionalOptionsCountFor(tree, editor, element);
                return count == null ? "" : count.toString(); //$NON-NLS-1$
            }

            @Override
            public Color getForeground(Object element)
            {
                Integer count = functionalOptionsCountFor(tree, editor, element);
                return count != null && count.intValue() == 0
                    ? tree.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY) : null;
            }
        });
        tree.setHeaderVisible(true);
        ThemeAwareColors.applyGridLines(tree);
        disableNativeColumnStretch(tree);
        tree.addListener(SWT.FocusIn, event -> refreshFunctionalOptionsCount(tree, viewer, editor));
        refreshFunctionalOptionsCount(tree, viewer, editor);
    }

    /** Как {@code MdEditorFunctionalOptionsCountHook.countForElement} — та же цель, что у панели. */
    private static Integer functionalOptionsCountFor(Tree tree, DtGranularEditor<?> editor, Object element)
    {
        EObject object = elementObject(tree, element);
        MdObject target = functionalOptionsTargetFor(object, editor);
        return target == null ? null : functionalOptionsCount(tree, target);
    }

    /** Как в {@code MdEditorFunctionalOptionsCountHook}: счёт в фоне, применение — через asyncExec. */
    private static void refreshFunctionalOptionsCount(Tree tree, TreeViewer viewer, DtGranularEditor<?> editor)
    {
        if (tree.isDisposed())
            return;
        if (tree.getData(FO_COUNT_JOB_KEY) instanceof Job previous)
            previous.cancel();
        Configuration configuration = foConfigurationOf(editor);
        Job job = new Job("Комфорт: число функциональных опций") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                if (monitor.isCanceled())
                    return Status.CANCEL_STATUS;
                Map<EObject, Integer> counts = computeFunctionalOptionsCounts(configuration);
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return Status.CANCEL_STATUS;
                display.asyncExec(() ->
                {
                    if (monitor.isCanceled() || tree.isDisposed())
                        return;
                    tree.setData(FO_COUNT_INDEX_KEY, counts);
                    if (!viewer.getTree().isDisposed())
                        viewer.refresh();
                });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.setPriority(Job.DECORATE);
        tree.setData(FO_COUNT_JOB_KEY, job);
        job.schedule();
    }

    private static Map<EObject, Integer> computeFunctionalOptionsCounts(Configuration configuration)
    {
        Map<EObject, Integer> counts = new HashMap<>();
        if (configuration != null)
        {
            for (FunctionalOption option : configuration.getFunctionalOptions())
            {
                if (option == null)
                    continue;
                for (MdObject item : option.getContent())
                    if (item != null)
                        counts.merge(item, Integer.valueOf(1), Integer::sum);
            }
        }
        return counts;
    }

    private static Integer functionalOptionsCount(Tree tree, EObject object)
    {
        if (!(tree.getData(FO_COUNT_INDEX_KEY) instanceof Map<?, ?> map))
            return null;
        Object value = map.get(object);
        return value instanceof Integer count ? count : Integer.valueOf(0);
    }

    private static Configuration foConfigurationOf(DtGranularEditor<?> editor)
    {
        EObject model = editor != null ? editor.getModel() : null;
        for (EObject current = model; current != null; current = current.eContainer())
        {
            if (current instanceof Configuration configuration)
                return configuration;
        }
        if (!(model instanceof MdObject mdObject))
            return null;
        IV8ProjectManager projectManager =
            (IV8ProjectManager)Global.getServiceByClass(IV8ProjectManager.class);
        if (projectManager == null)
            return null;
        IV8Project project = projectManager.getProject(mdObject);
        if (project instanceof IConfigurationProject configurationProject)
            return configurationProject.getConfiguration();
        Object configuration = Global.invoke(project, "getConfiguration"); //$NON-NLS-1$
        return configuration instanceof Configuration conf ? conf : null;
    }

    private static IDialogSettings foPickerDialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(FO_PICKER_SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(FO_PICKER_SETTINGS_SECTION);
        return section;
    }

    private static IDialogSettings foPanelSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(FO_PANEL_SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(FO_PANEL_SETTINGS_SECTION);
        return section;
    }

    private static int loadFoPanelHeight()
    {
        String stored = foPanelSettings().get(FO_PANEL_HEIGHT_KEY);
        if (stored == null)
            return FO_PANEL_HEIGHT;
        try
        {
            return Math.max(FO_PANEL_MIN_HEIGHT, Math.min(FO_PANEL_MAX_HEIGHT, Integer.parseInt(stored)));
        }
        catch (NumberFormatException e)
        {
            return FO_PANEL_HEIGHT;
        }
    }

    private static void saveFoPanelHeight(int height)
    {
        foPanelSettings().put(FO_PANEL_HEIGHT_KEY, height);
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
