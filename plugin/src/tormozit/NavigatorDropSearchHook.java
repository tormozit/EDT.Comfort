package tormozit;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.TransferData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.navigator.CommonViewer;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmEditingContext;
import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.md.api.ExchangePlanContentTask;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.metadata.mdclass.AutoRegistrationChanges;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlan;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.ui.util.ContentUtil;

/**
 * DnD «навигатор → список», если состав списка нельзя менять перетаскиванием
 * (на списке нет своего обработчика drop на добавление).
 *
 * <p>Если список допускает дубли объектов метаданных (вкладка «Права», окно «Все роли») —
 * имя подставляется в поле {@link SearchBox}, связанное с этим списком напрямую,
 * и запускается поиск. 
 *
 * <p>Иначе выделяется строка перетаскиваемого объекта в том списке, куда сбросили.
 *
 * <p>Исключение — «Состав» подсистемы, функциональной опции и плана обмена: сброс включает объекты в состав
 * (см. {@link ContentPageDrop}).
 *
 * <p>Навигатор, наборы объектов и дерево сравнения сюда не входят: у них свой drop.
 */
public final class NavigatorDropSearchHook implements IStartup
{
    private static final String INSTALLED_KEY = "tormozit.navigatorDropSearch"; //$NON-NLS-1$
    private static final int PARENT_WALK = 8;
    private static final int CHILD_WALK = 6;

    private static final Set<DtGranularEditor<?>> HOOKED_EDITORS =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            log("earlyStartup"); //$NON-NLS-1$
            installDisplayFilter(display);
            IWorkbench workbench = PlatformUI.getWorkbench();
            for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
                hookWindow(window);
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
            for (Shell shell : display.getShells())
                scanControl(shell);
        });
    }

    private static void installDisplayFilter(Display display)
    {
        display.addFilter(SWT.Show, event ->
        {
            if (!(event.widget instanceof Control control) || control.isDisposed())
                return;
            display.asyncExec(() -> consider(control));
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
                scanPart(ref.getPart(false));
            for (IViewReference ref : page.getViewReferences())
                scanPart(ref.getPart(false));
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { scanPart(ref.getPart(false)); }
            @Override public void partActivated(IWorkbenchPartReference ref) { scanPart(ref.getPart(false)); }
            @Override public void partVisible(IWorkbenchPartReference ref) { scanPart(ref.getPart(false)); }
            @Override public void partClosed(IWorkbenchPartReference r) {}
            @Override public void partDeactivated(IWorkbenchPartReference r) {}
            @Override public void partHidden(IWorkbenchPartReference r) {}
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private static void scanPart(IWorkbenchPart part)
    {
        if (part == null || Global.isNavigatorPart(part))
            return;
        if (part instanceof DtGranularEditor<?> editor)
            hookMdEditor(editor);
        else if (RightsEditorFilterHook.isAllRolesEditor(part))
            hookAllRolesEditor(part);
        Object parent = Global.getField(part, "parent"); //$NON-NLS-1$
        if (parent instanceof Control control)
            scanControl(control);
    }

    private static void hookMdEditor(DtGranularEditor<?> editor)
    {
        if (!HOOKED_EDITORS.add(editor))
        {
            installOnMdEditor(editor);
            return;
        }
        editor.addPageChangedListener(event -> installOnMdEditor(editor));
        installOnMdEditor(editor);
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.timerExec(300, () -> installOnMdEditor(editor));
    }

    private static void hookAllRolesEditor(Object editor)
    {
        installOnRightsSection(editor);
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.timerExec(300, () -> installOnRightsSection(editor));
    }

    private static void installOnMdEditor(DtGranularEditor<?> editor)
    {
        if (editor == null)
            return;
        Object page = Global.invoke(editor, "getActivePageInstance"); //$NON-NLS-1$
        if (page == null && editor.getSelectedPage() instanceof IFormPage formPage)
            page = formPage;
        installOnRightsSection(page);
        if (page instanceof IFormPage formPage)
        {
            Control partControl = formPage.getPartControl();
            if (partControl != null)
                scanControl(partControl);
        }
    }

    private static void installOnRightsSection(Object page)
    {
        if (page == null)
            return;
        Object section = Global.getField(page, "objectsSection"); //$NON-NLS-1$
        if (section == null)
            return;
        Object viewerObj = Global.getField(section, "viewer"); //$NON-NLS-1$
        Object searchObj = Global.getField(section, "searchBox"); //$NON-NLS-1$
        if (!(viewerObj instanceof TreeViewer viewer) || !(searchObj instanceof SearchBox searchBox))
        {
            log("rights skip page=" + page.getClass().getName() //$NON-NLS-1$
                + " viewer=" + typeName(viewerObj) + " searchBox=" + typeName(searchObj)); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed() || searchBox.isDisposed())
            return;
        log("rights install tree=" + tree.hashCode() + " searchBox=" + searchBox.hashCode()); //$NON-NLS-1$ //$NON-NLS-2$
        installFilterPair(searchBox, tree);
    }

    private static void scanControl(Control root)
    {
        if (root == null || root.isDisposed())
            return;
        consider(root);
        if (!(root instanceof Composite composite))
            return;
        for (Control child : composite.getChildren())
            scanControl(child);
    }

    private static void consider(Control control)
    {
        if (control == null || control.isDisposed())
            return;
        if (control instanceof SearchBox || control instanceof Tree || control instanceof Table)
            tryInstall(control);
    }

    private static void tryInstall(Control origin)
    {
        if (origin == null || origin.isDisposed())
            return;
        if (Boolean.TRUE.equals(origin.getData(INSTALLED_KEY)))
            return;

        Control list;
        SearchBox searchBox;
        if (origin instanceof SearchBox box)
        {
            searchBox = box;
            list = findDirectList(box);
        }
        else if (isList(origin))
        {
            list = origin;
            searchBox = findDirectSearchBox(origin);
        }
        else
            return;
        if (list instanceof Tree tree && !tree.isDisposed() && ContentPageDrop.install(tree))
            return;
        if (list == null || list.isDisposed() || isNavigatorList(list) || hasForeignDropTarget(list))
        {
            log("tryInstall skip origin=" + origin.getClass().getSimpleName() //$NON-NLS-1$
                + " list=" + typeName(list)); //$NON-NLS-1$
            return;
        }

        if (allowsDuplicateMdObjects(list) && searchBox != null && !searchBox.isDisposed()
                && findDirectSearchBox(list) == searchBox)
        {
            log("tryInstall filter origin=" + origin.getClass().getSimpleName()); //$NON-NLS-1$
            installFilterPair(searchBox, list);
            return;
        }
        if (origin instanceof SearchBox)
            return;
        log("tryInstall select origin=" + origin.getClass().getSimpleName()); //$NON-NLS-1$
        installOn(list, list);
    }

    private static void installFilterPair(SearchBox searchBox, Control list)
    {
        installOn(list, list);
        installOn(searchBox, list);
        Control text = searchTextControl(searchBox);
        if (text != null)
            installOn(text, list);
    }

    private static void installOn(Control control, Control list)
    {
        if (control == null || control.isDisposed())
            return;
        if (Boolean.TRUE.equals(control.getData(INSTALLED_KEY)))
            return;
        if (control == list && hasForeignDropTarget(list))
            return;
        DropTarget target = ensureDropTarget(control);
        if (target == null)
        {
            log("DropTarget fail " + control.getClass().getSimpleName()); //$NON-NLS-1$
            return;
        }
        ensureLocalSelectionTransfer(target);
        target.addDropListener(new SearchDropListener(list));
        control.setData(INSTALLED_KEY, Boolean.TRUE);
        log("DropTarget on " + control.getClass().getSimpleName() + "#" + control.hashCode() //$NON-NLS-1$ //$NON-NLS-2$
            + " transfers=" + transfersOf(target)); //$NON-NLS-1$
    }

    private static final class SearchDropListener extends DropTargetAdapter
    {
        private final Control list;
        private boolean acceptDrop;

        SearchDropListener(Control list)
        {
            this.list = list;
        }

        @Override
        public void dragEnter(DropTargetEvent event)
        {
            updateDetail(event, "enter"); //$NON-NLS-1$
        }

        @Override
        public void dragOperationChanged(DropTargetEvent event)
        {
            updateDetail(event, "op"); //$NON-NLS-1$
        }

        @Override
        public void dragOver(DropTargetEvent event)
        {
            updateDetail(event, null);
        }

        @Override
        public void dropAccept(DropTargetEvent event)
        {
            updateDetail(event, "accept"); //$NON-NLS-1$
        }

        @Override
        public void dragLeave(DropTargetEvent event)
        {
            acceptDrop = false;
        }

        @Override
        public void drop(DropTargetEvent event)
        {
            String fullName = resolveDraggedFullName();
            log("drop accept=" + acceptDrop + " full=" + fullName //$NON-NLS-1$ //$NON-NLS-2$
                + " detail=" + event.detail); //$NON-NLS-1$
            if (!acceptDrop || fullName == null)
                return;
            SearchBox direct = findDirectSearchBox(list);
            if (direct != null && allowsDuplicateMdObjects(list))
            {
                String search = hierarchicalSearchName(fullName);
                if (search != null && !search.isBlank())
                    applySearch(direct, list, search);
                return;
            }
            selectMatchingRow(list, fullName);
        }

        private void updateDetail(DropTargetEvent event, String logKind)
        {
            String fullName = resolveDraggedFullName();
            if (fullName == null)
            {
                acceptDrop = false;
                if (logKind != null)
                    log("drag " + logKind + " noText sel=" + describeSelection() //$NON-NLS-1$ //$NON-NLS-2$
                        + " detail=" + event.detail); //$NON-NLS-1$
                return;
            }
            preferLocalSelectionDataType(event);
            event.detail = DND.DROP_MOVE;
            event.feedback = DND.FEEDBACK_NONE;
            acceptDrop = true;
            if (logKind != null)
                log("drag " + logKind + " MOVE full=" + fullName); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * «Состав» подсистемы, функциональной опции и плана обмена ({@code editors.*.pages.content}): сброс объектов
     * из навигатора включает их в состав в транзакции редактора и выделяет их в дереве.
     * Допустимые виды объектов — те же, что у штатного выбора состава (см. {@link Kind}).
     */
    private static final class ContentPageDrop extends DropTargetAdapter
    {
        private enum Kind
        {
            /** Виды — публичный {@code SubsystemEditorContentPageModel.CONTAINED_CLASSES}. */
            SUBSYSTEM("editors.subsystem.pages.content", "SubsystemEditorContentPageComponent", //$NON-NLS-1$ //$NON-NLS-2$
                MdClassPackage.Literals.SUBSYSTEM__CONTENT, "Комфорт: состав подсистемы", null), //$NON-NLS-1$
            FUNCTIONAL_OPTION("editors.functionalOption.pages.content", //$NON-NLS-1$
                "FunctionalOptionEditorContentPageComponent", MdClassPackage.Literals.FUNCTIONAL_OPTION__CONTENT, //$NON-NLS-1$
                "Комфорт: состав функциональной опции", "FUNCTIONAL_OPTION_CONTENT__CONTAINED_ECLASSES"), //$NON-NLS-1$ //$NON-NLS-2$
            /** Состав — записи {@code ExchangePlanContentItem}, добавляются штатной {@code ExchangePlanContentTask}. */
            EXCHANGE_PLAN("editors.exchangeplan.pages.content", "ExchangePlanContentComponent", //$NON-NLS-1$ //$NON-NLS-2$
                MdClassPackage.Literals.EXCHANGE_PLAN__CONTENT, "Комфорт: состав плана обмена", //$NON-NLS-1$
                "EXCHANGE_PLAN_CONTENT__CONTAINED_ECLASSES"); //$NON-NLS-1$

            final String pageId;
            final String componentClass;
            final EReference feature;
            final String taskName;
            /** Приватное поле {@code NavigatorTreeDialogModelFactory} с видами штатного выбора состава. */
            final String eClassesField;

            Kind(String pageId, String componentClass, EReference feature, String taskName, String eClassesField)
            {
                this.pageId = pageId;
                this.componentClass = componentClass;
                this.feature = feature;
                this.taskName = taskName;
                this.eClassesField = eClassesField;
            }

            static Kind of(String pageId)
            {
                for (Kind kind : values())
                {
                    if (kind.pageId.equals(pageId))
                        return kind;
                }
                return null;
            }
        }

        private static final String KEY = "tormozit.navigatorDropContentPage"; //$NON-NLS-1$
        private static final String SUBSYSTEM_MODEL_CLASS =
            "com._1c.g5.v8.dt.internal.md.ui.editors.subsystem.SubsystemEditorContentPageModel"; //$NON-NLS-1$
        private static final String DIALOG_MODEL_FACTORY_CLASS =
            "com._1c.g5.v8.dt.md.ui.aef.models.NavigatorTreeDialogModelFactory"; //$NON-NLS-1$
        /** Ключ, под которым {@code DtTreeView} кладёт свой {@code TreeViewer} в данные своего {@code Composite} — родителя дерева. */
        private static final String DT_TREE_VIEWER_KEY =
            "com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView.treeViewer"; //$NON-NLS-1$
        /** Раскрытие пути к каждому объекту занимает свою попытку. */
        private static final int SELECT_ATTEMPTS = 50;
        /** Сколько проверок подряд выделение всех должно продержаться (по {@link #SELECT_DELAY_MS}). */
        private static final int SETTLE_CHECKS = 10;
        private static final int SELECT_DELAY_MS = 100;

        private final DtGranularEditor<?> editor;
        private final IFormPage page;
        private final Kind kind;
        private final Tree tree;
        private final Predicate<EObject> allowed;
        private final IProject project;

        private ContentPageDrop(DtGranularEditor<?> editor, IFormPage page, Kind kind, Tree tree,
            Predicate<EObject> allowed, IProject project)
        {
            this.editor = editor;
            this.page = page;
            this.kind = kind;
            this.tree = tree;
            this.allowed = allowed;
            this.project = project;
        }

        /** {@code true}, если дерево — «Состав» подсистемы или опции и слушатель на нём стоит. */
        static boolean install(Tree tree)
        {
            if (Boolean.TRUE.equals(tree.getData(KEY)))
                return true;
            DtGranularEditor<?> editor = MdEditorAttributeMenuHook.editorOf(tree);
            IFormPage page = editor != null ? contentPageOf(editor, tree) : null;
            Kind kind = page != null ? Kind.of(page.getId()) : null;
            EObject owner = kind != null ? editor.getModel() : null;
            if (owner == null || !kind.feature.getEContainingClass().isInstance(owner))
                return false;
            Predicate<EObject> allowed = allowedTypes(kind, page);
            IProject project = projectOf(owner);
            DropTarget target = allowed != null && project != null ? ensureDropTarget(tree) : null;
            if (target == null)
                return false;
            ensureLocalSelectionTransfer(target);
            target.addDropListener(new ContentPageDrop(editor, page, kind, tree, allowed, project));
            tree.setData(KEY, Boolean.TRUE);
            tree.setData(INSTALLED_KEY, Boolean.TRUE);
            return true;
        }

        @Override
        public void dragEnter(DropTargetEvent event)
        {
            updateDetail(event);
        }

        @Override
        public void dragOperationChanged(DropTargetEvent event)
        {
            updateDetail(event);
        }

        @Override
        public void dragOver(DropTargetEvent event)
        {
            updateDetail(event);
        }

        @Override
        public void dropAccept(DropTargetEvent event)
        {
            updateDetail(event);
        }

        @Override
        public void drop(DropTargetEvent event)
        {
            List<MdObject> objects = draggedObjects();
            EObject owner = editor.getModel();
            if (objects.isEmpty() || owner == null)
                return;
            IBmEditingContext context = editor.getEditingContext();
            if (context == null)
                return;
            try
            {
                context.execute(new AbstractBmTask<Void>(kind.taskName)
                {
                    @Override
                    public Void execute(IBmTransaction transaction, IProgressMonitor monitor)
                    {
                        EObject txOwner = transaction.toTransactionObject(owner);
                        if (txOwner instanceof ExchangePlan plan)
                        {
                            addToExchangePlan(transaction, plan, objects, monitor);
                            return null;
                        }
                        if (txOwner == null || !(txOwner.eGet(kind.feature) instanceof List<?> raw))
                            return null;
                        @SuppressWarnings("unchecked")
                        List<Object> content = (List<Object>) raw;
                        for (MdObject object : objects)
                        {
                            if (transaction.toTransactionObject(object) instanceof MdObject txObject
                                && !content.contains(txObject))
                                content.add(txObject);
                        }
                        return null;
                    }
                });
            }
            catch (RuntimeException e)
            {
                Global.logError("NavigatorDropSearchHook", "contentPageDrop", e); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            scheduleSelect(objects, 0, 0);
        }

        /**
         * Как штатный выбор состава: новая запись с авторегистрацией «Разрешить» и штатной
         * сортировкой. Уже входящий объект пропускаем — задача с {@code ALLOW} переписала бы
         * его авторегистрацию.
         */
        private static void addToExchangePlan(IBmTransaction transaction, ExchangePlan plan, List<MdObject> objects,
            IProgressMonitor monitor)
        {
            for (MdObject object : objects)
            {
                if (!(transaction.toTransactionObject(object) instanceof MdObject txObject)
                    || plan.getContent().stream().anyMatch(item -> item.getMdObject() == txObject))
                    continue;
                new ExchangePlanContentTask(plan, txObject, AutoRegistrationChanges.ALLOW).execute(plan, monitor);
            }
        }

        private void updateDetail(DropTargetEvent event)
        {
            if (draggedObjects().isEmpty())
            {
                event.detail = DND.DROP_NONE;
                return;
            }
            preferLocalSelectionDataType(event);
            event.detail = DND.DROP_MOVE;
            event.feedback = DND.FEEDBACK_SCROLL;
        }

        /** Объекты допустимых видов из того же проекта, что и владелец состава. */
        private List<MdObject> draggedObjects()
        {
            Object selection = LocalSelectionTransfer.getTransfer().getSelection();
            if (!(selection instanceof IStructuredSelection structured))
                return List.of();
            List<MdObject> result = new ArrayList<>();
            for (Object element : structured.toList())
            {
                if (element == null || NavigatorTreeElementLabels.isGroupNode(element))
                    continue;
                EObject model = NavigatorElementModels.resolveEObject(element);
                EObject actual = model != null ? ContentUtil.getActualObject(model) : null;
                if (actual instanceof MdObject object && allowed.test(object) && !result.contains(object)
                    && project.equals(projectOf(object)))
                    result.add(object);
            }
            return result;
        }

        /**
         * Дерево перестраивается после транзакции не сразу — выделяем, когда строки появятся.
         * После выделения всех строк ещё {@link #SETTLE_CHECKS} проверок следим за ним: события
         * раскрытия, поставленные в очередь компонента AEF, доходят позже и выделяют в
         * {@code DtTreeView} только первый элемент. Сузили до части наших — выделяем снова;
         * пользователь выделил другое — не вмешиваемся.
         * @param stable число проверок подряд, на которых были выделены все
         */
        private void scheduleSelect(List<MdObject> objects, int attempt, int stable)
        {
            if (tree.isDisposed())
                return;
            tree.getDisplay().timerExec(attempt == 0 ? 0 : SELECT_DELAY_MS, () ->
            {
                if (tree.isDisposed())
                    return;
                // Однострочное дерево всех сброшенных выделить не может — там хватает первого.
                boolean multi = (tree.getStyle() & SWT.MULTI) != 0;
                List<MdObject> wanted = multi ? objects : objects.subList(0, 1);
                // Компонент AEF из переданного списка выделяет и раскрывает только первый объект,
                // а строк в свёрнутых ветках ещё нет. Поэтому раскрываем путь к объектам по одному,
                // а когда строки есть у всех — выделяем их разом через штатный TreeViewer.
                List<Object> rows = new ArrayList<>();
                MdObject missing = collectRows(wanted, rows);
                if (missing != null)
                    revealThroughComponent(missing);
                else if (countSelected(wanted) < wanted.size())
                {
                    // Пользователь уже выделил другое — его выделение не трогаем.
                    if (stable > 0 && !selectionWithin(wanted))
                        return;
                    TreeViewer viewer = viewerOf(tree);
                    if (viewer != null)
                        viewer.setSelection(new StructuredSelection(rows), true);
                }
                int selected = countSelected(wanted);
                int nextStable = selected == wanted.size() ? stable + 1 : stable;
                if (selected == wanted.size() && stable == 0)
                    tree.setFocus();
                if (nextStable < SETTLE_CHECKS && attempt + 1 < SELECT_ATTEMPTS)
                    scheduleSelect(objects, attempt + 1, nextStable);
            });
        }

        /** Выделены только строки сброшенных объектов (или ничего) — значит, выделение сузили не руками. */
        private boolean selectionWithin(List<MdObject> objects)
        {
            for (TreeItem item : tree.getSelection())
            {
                EObject selected = objectOf(item);
                if (objects.stream().noneMatch(object -> MdEditorAttributeMenuHook.sameObject(selected, object)))
                    return false;
            }
            return true;
        }

        /** {@code TreeViewer} лежит не на самом {@link Tree}, а на {@code Composite} {@code DtTreeView} выше. */
        private static TreeViewer viewerOf(Tree tree)
        {
            for (Control control = tree; control != null; control = control.getParent())
            {
                if (control.getData(DT_TREE_VIEWER_KEY) instanceof TreeViewer viewer && viewer.getTree() == tree)
                    return viewer;
            }
            return null;
        }

        /** Штатный {@code setSelection} компонента раскрывает путь к объекту и создаёт его строку. */
        private boolean revealThroughComponent(MdObject object)
        {
            Object component = findComponent(Global.getField(page, "pageComponent"), 0); //$NON-NLS-1$
            if (component == null)
                return false;
            try
            {
                return Global.invokeVoid(component, "setSelection", List.of(object)); //$NON-NLS-1$
            }
            catch (RuntimeException e)
            {
                Global.logError("NavigatorDropSearchHook", "revealThroughComponent", e); //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
        }

        private int countSelected(List<MdObject> objects)
        {
            int count = 0;
            for (MdObject object : objects)
            {
                for (TreeItem item : tree.getSelection())
                {
                    if (MdEditorAttributeMenuHook.sameObject(objectOf(item), object))
                    {
                        count++;
                        break;
                    }
                }
            }
            return count;
        }

        /**
         * Собирает элементы созданных строк для объектов (выделение идёт через штатный
         * {@code TreeViewer}, чтобы о нём узнали его слушатели и «Свойства»).
         * @return первый объект, у которого строки ещё нет; {@code null} — строки есть у всех
         */
        private MdObject collectRows(List<MdObject> objects, List<Object> rows)
        {
            MdObject missing = null;
            for (MdObject object : objects)
            {
                Object row = findRow(tree.getItems(), object);
                if (row != null)
                    rows.add(row);
                else if (missing == null)
                    missing = object;
            }
            return missing;
        }

        private Object findRow(TreeItem[] items, MdObject object)
        {
            for (TreeItem item : items)
            {
                if (item.isDisposed())
                    continue;
                if (item.getData() != null && MdEditorAttributeMenuHook.sameObject(objectOf(item), object))
                    return item.getData();
                Object nested = findRow(item.getItems(), object);
                if (nested != null)
                    return nested;
            }
            return null;
        }

        private EObject objectOf(TreeItem item)
        {
            return MdEditorAttributeMenuHook.mapViewModelToEObject(tree, item.getData());
        }

        private Object findComponent(Object component, int depth)
        {
            if (component == null || depth > 20)
                return null;
            if (component.getClass().getName().endsWith(kind.componentClass))
                return component;
            for (Object child : AefFieldFocus.childComponents(component))
            {
                Object found = findComponent(child, depth + 1);
                if (found != null)
                    return found;
            }
            return null;
        }

        /** Не активная страница: {@code SWT.Show} дерева приходит, пока страница ещё переключается. */
        private static IFormPage contentPageOf(DtGranularEditor<?> editor, Tree tree)
        {
            if (!(Global.getField(editor, "pages") instanceof List<?> pages)) //$NON-NLS-1$
                return null;
            for (Object value : pages)
            {
                if (value instanceof IFormPage page && Kind.of(page.getId()) != null
                    && page.getPartControl() != null && isUnder(page.getPartControl(), tree))
                    return page;
            }
            return null;
        }

        /** Нет списка видов (поле переименовано в другой версии EDT) — {@code null}, сброс только выделяет строку. */
        private static Predicate<EObject> allowedTypes(Kind kind, IFormPage page)
        {
            ClassLoader loader = page.getClass().getClassLoader();
            try
            {
                if (kind == Kind.SUBSYSTEM)
                {
                    Class<?> model = Class.forName(SUBSYSTEM_MODEL_CLASS, true, loader);
                    if (!(model.getField("CONTAINED_CLASSES").get(null) instanceof Class<?>[] classes)) //$NON-NLS-1$
                        return null;
                    return object -> Arrays.stream(classes).anyMatch(type -> type.isInstance(object));
                }
                Class<?> factory = Class.forName(DIALOG_MODEL_FACTORY_CLASS, true, loader);
                Field field = factory.getDeclaredField(kind.eClassesField);
                field.setAccessible(true);
                if (!(field.get(null) instanceof EClass[] classes))
                    return null;
                return object -> Arrays.stream(classes).anyMatch(type -> type.isInstance(object));
            }
            catch (ReflectiveOperationException | LinkageError | RuntimeException e)
            {
                Global.logError("NavigatorDropSearchHook", "allowedTypes " + kind, e); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }
        }

        private static IProject projectOf(EObject object)
        {
            IV8ProjectManager manager = Global.getOsgiService(IV8ProjectManager.class);
            IV8Project v8Project = manager != null ? manager.getProject(object) : null;
            return v8Project != null ? v8Project.getProject() : null;
        }
    }

    private static String resolveDraggedFullName()
    {
        Object selObj = LocalSelectionTransfer.getTransfer().getSelection();
        if (!(selObj instanceof IStructuredSelection sel) || sel.isEmpty())
            return null;
        Object element = sel.getFirstElement();
        if (element == null || NavigatorTreeElementLabels.isGroupNode(element))
            return null;
        String fullName = GetRef.fullNameFromNavigatorElement(element);
        return fullName == null || fullName.isBlank() ? null : fullName;
    }

    /**
     * Хвост после владельца МД: {@code Справочник.Орг.Реквизит.Бик} → {@code Реквизит.Бик}.
     * Для самого объекта — {@code Справочник.Орг}.
     */
    private static String hierarchicalSearchName(String fullName)
    {
        String owner = MdTypeMapping.toOwnerMdObjectRef(fullName);
        if (owner == null || owner.isBlank())
            return fullName;
        String prefix = owner + "."; //$NON-NLS-1$
        if (fullName.startsWith(prefix))
        {
            String relative = fullName.substring(prefix.length());
            if (!relative.isBlank())
                return relative;
        }
        return owner;
    }

    private static String describeSelection()
    {
        Object selObj = LocalSelectionTransfer.getTransfer().getSelection();
        if (selObj == null)
            return "null"; //$NON-NLS-1$
        if (!(selObj instanceof IStructuredSelection sel) || sel.isEmpty())
            return selObj.getClass().getName();
        Object el = sel.getFirstElement();
        return el == null ? "empty" : el.getClass().getName(); //$NON-NLS-1$
    }

    private static void applySearch(SearchBox searchBox, Control list, String text)
    {
        if (searchBox == null || searchBox.isDisposed())
            return;
        log("applySearch " + text); //$NON-NLS-1$
        RightsEditorFilterHook.applyExactReference(searchBox);
        searchBox.setText(text);
        Global.invoke(searchBox, "performSearch"); //$NON-NLS-1$
        Display display = searchBox.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            if (searchBox.isDisposed())
                return;
            int end = searchBox.getCharCount();
            searchBox.setCaretOffset(end);
            searchBox.setSelection(end, end);
        });
        display.timerExec(120, () -> selectFirstVisible(list));
        if (list != null && !list.isDisposed())
            list.setFocus();
    }

    private static void selectMatchingRow(Control list, String fullName)
    {
        if (list == null || list.isDisposed() || fullName == null || fullName.isBlank())
            return;
        if (list instanceof Table table)
        {
            for (TableItem item : table.getItems())
            {
                if (matchesFullName(item.getData(), fullName))
                {
                    table.setSelection(item);
                    table.showSelection();
                    table.setFocus();
                    return;
                }
            }
            return;
        }
        if (!(list instanceof Tree tree) || tree.getItemCount() <= 0)
            return;
        TreeItem found = findTreeItem(tree.getItems(), fullName);
        if (found == null)
            return;
        tree.setSelection(found);
        tree.showItem(found);
        tree.setFocus();
    }

    private static TreeItem findTreeItem(TreeItem[] items, String fullName)
    {
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            if (matchesFullName(item.getData(), fullName))
                return item;
            TreeItem nested = findTreeItem(item.getItems(), fullName);
            if (nested != null)
                return nested;
        }
        return null;
    }

    private static boolean matchesFullName(Object data, String fullName)
    {
        if (data == null)
            return false;
        String have = GetRef.fullNameFromNavigatorElement(data);
        return fullName.equals(have);
    }

    private static void selectFirstVisible(Control list)
    {
        if (list instanceof Table table)
        {
            if (table.isDisposed() || table.getItemCount() <= 0)
                return;
            table.setSelection(table.getItem(0));
            table.showSelection();
            return;
        }
        if (!(list instanceof Tree tree) || tree.isDisposed() || tree.getItemCount() <= 0)
            return;
        TreeItem first = tree.getItem(0);
        tree.setSelection(first);
        tree.showItem(first);
    }

    /**
     * Поле фильтра, связанное со списком напрямую: пара {@code objectsSection.viewer/searchBox}
     * либо SearchBox в той же секции, без общего предка с другим {@link Tree}/{@link Table}.
     */
    private static SearchBox findDirectSearchBox(Control list)
    {
        if (list == null || list.isDisposed())
            return null;
        SearchBox fromSection = searchBoxOfRightsList(list);
        if (fromSection != null)
            return fromSection;
        Composite parent = list.getParent();
        for (int depth = 0; depth < PARENT_WALK && parent != null && !parent.isDisposed(); depth++)
        {
            if (parent instanceof CTabFolder || parent instanceof Shell)
                break;
            Control scope = countLists(parent) > 1 ? childContaining(parent, list) : parent;
            SearchBox found = findSearchBoxExclusive(scope, list, 0);
            if (found != null)
                return found;
            parent = parent.getParent();
        }
        return null;
    }

    /**
     * Список, для которого {@code searchBox} — прямой фильтр. Если в предке несколько
     * списков и поле не лежит в секции ровно одного из них — {@code null}.
     */
    private static Control findDirectList(SearchBox searchBox)
    {
        if (searchBox == null || searchBox.isDisposed())
            return null;
        Composite parent = searchBox.getParent();
        for (int depth = 0; depth < PARENT_WALK && parent != null && !parent.isDisposed(); depth++)
        {
            if (parent instanceof CTabFolder || parent instanceof Shell)
                break;
            Control[] lists = collectLists(parent);
            if (lists.length == 1)
                return lists[0];
            if (lists.length > 1)
            {
                Control pane = childContaining(parent, searchBox);
                Control[] inPane = pane == null ? new Control[0] : collectLists(pane);
                return inPane.length == 1 ? inPane[0] : null;
            }
            parent = parent.getParent();
        }
        return null;
    }

    private static SearchBox findSearchBoxExclusive(Control root, Control list, int depth)
    {
        if (root == null || root.isDisposed() || depth > CHILD_WALK)
            return null;
        if (root instanceof SearchBox box)
            return box;
        if (!(root instanceof Composite composite))
            return null;
        for (Control child : composite.getChildren())
        {
            if (child == null || child.isDisposed() || child == list)
                continue;
            if (isList(child) || containsOtherList(child, list))
                continue;
            SearchBox found = findSearchBoxExclusive(child, list, depth + 1);
            if (found != null)
                return found;
        }
        return null;
    }

    private static boolean containsOtherList(Control root, Control list)
    {
        if (root == null || root.isDisposed())
            return false;
        if (isList(root))
            return root != list;
        if (!(root instanceof Composite composite))
            return false;
        for (Control child : composite.getChildren())
        {
            if (containsOtherList(child, list))
                return true;
        }
        return false;
    }

    private static Control childContaining(Composite parent, Control descendant)
    {
        for (Control current = descendant; current != null; current = current.getParent())
        {
            if (current.getParent() == parent)
                return current;
        }
        return null;
    }

    private static int countLists(Control root)
    {
        return collectLists(root).length;
    }

    private static Control[] collectLists(Control root)
    {
        ArrayList<Control> lists = new ArrayList<>();
        collectListsInto(root, lists);
        return lists.toArray(new Control[0]);
    }

    private static void collectListsInto(Control root, ArrayList<Control> lists)
    {
        if (root == null || root.isDisposed())
            return;
        if (isList(root))
        {
            lists.add(root);
            return;
        }
        if (!(root instanceof Composite composite))
            return;
        for (Control child : composite.getChildren())
            collectListsInto(child, lists);
    }

    private static boolean isList(Control control)
    {
        return control instanceof Tree || control instanceof Table;
    }

    private static boolean hasForeignDropTarget(Control list)
    {
        if (list == null || list.isDisposed())
            return false;
        if (Boolean.TRUE.equals(list.getData(INSTALLED_KEY)))
            return false;
        return list.getData(DND.DROP_TARGET_KEY) instanceof DropTarget;
    }

    private static boolean allowsDuplicateMdObjects(Control list)
    {
        return objectsSectionForList(list) != null;
    }

    private static SearchBox searchBoxOfRightsList(Control list)
    {
        Object section = objectsSectionForList(list);
        if (section == null)
            return null;
        Object searchObj = Global.getField(section, "searchBox"); //$NON-NLS-1$
        return searchObj instanceof SearchBox box && !box.isDisposed() ? box : null;
    }

    private static Object objectsSectionForList(Control list)
    {
        if (!(list instanceof Tree tree) || tree.isDisposed())
            return null;
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return null;
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorReference ref : page.getEditorReferences())
                {
                    IWorkbenchPart part = ref.getPart(false);
                    if (part == null)
                        continue;
                    Object section = sectionIfTree(part, tree);
                    if (section != null)
                        return section;
                    if (part instanceof DtGranularEditor<?> editor)
                    {
                        section = sectionIfTree(Global.invoke(editor, "getActivePageInstance"), tree); //$NON-NLS-1$
                        if (section != null)
                            return section;
                        section = sectionIfTree(editor.getSelectedPage(), tree);
                        if (section != null)
                            return section;
                    }
                }
            }
        }
        return null;
    }

    private static Object sectionIfTree(Object owner, Tree tree)
    {
        if (owner == null)
            return null;
        Object section = Global.getField(owner, "objectsSection"); //$NON-NLS-1$
        if (section == null)
            return null;
        Object viewerObj = Global.getField(section, "viewer"); //$NON-NLS-1$
        if (!(viewerObj instanceof TreeViewer viewer))
            return null;
        Tree sectionTree = viewer.getTree();
        return sectionTree == tree ? section : null;
    }

    private static boolean isNavigatorList(Control list)
    {
        if (isNavigatorTree(list))
            return true;
        return isUnderView(list, Global.NAVIGATOR_VIEW_ID)
            || isUnderView(list, "org.eclipse.ui.navigator.ProjectExplorer"); //$NON-NLS-1$
    }

    private static boolean isNavigatorTree(Control list)
    {
        if (!(list instanceof Tree tree))
            return false;
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return false;
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                IWorkbenchPart view = createdView(page, Global.NAVIGATOR_VIEW_ID);
                if (view == null)
                    continue;
                Object raw = Global.invoke(view, "getCommonViewer"); //$NON-NLS-1$
                if (raw instanceof CommonViewer viewer && viewer.getTree() == tree)
                    return true;
            }
        }
        return false;
    }

    private static boolean isUnderView(Control list, String viewId)
    {
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return false;
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                IWorkbenchPart view = createdView(page, viewId);
                if (view == null)
                    continue;
                Object parent = Global.getField(view, "parent"); //$NON-NLS-1$
                if (parent instanceof Control root && isUnder(root, list))
                    return true;
            }
        }
        return false;
    }

    /**
     * Уже созданное представление, без восстановления. {@code IWorkbenchPage.findView}
     * создаёт отложенное представление прямо из сканирования контролов: на старте так
     * создавалась «Структура проекта», и Workbench записывал ей в держатели модель
     * активного в тот момент редактора. Эта лишняя ссылка не снималась, и при закрытии
     * изменённой формы EDT спрашивал «изменён, но всё ещё открыт в другом месте».
     * Несозданному представлению проверяемый контрол принадлежать и не может.
     */
    private static IWorkbenchPart createdView(IWorkbenchPage page, String viewId)
    {
        IViewReference reference = page.findViewReference(viewId);
        return reference != null ? reference.getView(false) : null;
    }

    private static boolean isUnder(Control root, Control child)
    {
        for (Control current = child; current != null; current = current.getParent())
        {
            if (current == root)
                return true;
        }
        return false;
    }

    private static Control searchTextControl(SearchBox searchBox)
    {
        if (searchBox == null || searchBox.isDisposed())
            return null;
        for (String field : new String[] { "text", "searchText", "styledText" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Object value = Global.getField(searchBox, field);
            if (value instanceof StyledText || value instanceof Text)
                return (Control) value;
        }
        for (Control child : searchBox.getChildren())
        {
            if (child instanceof StyledText || child instanceof Text)
                return child;
        }
        return null;
    }

    private static DropTarget ensureDropTarget(Control control)
    {
        Object existing = control.getData(DND.DROP_TARGET_KEY);
        if (existing instanceof DropTarget target)
            return target;
        try
        {
            return new DropTarget(control, DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_DEFAULT);
        }
        catch (RuntimeException | SWTError e)
        {
            log("DropTarget init error: " + e); //$NON-NLS-1$
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
            {
                if (transfer == local)
                    return;
            }
            Transfer[] expanded = new Transfer[current.length + 1];
            System.arraycopy(current, 0, expanded, 0, current.length);
            expanded[current.length] = local;
            target.setTransfer(expanded);
        }
        else
            target.setTransfer(new Transfer[] { local });
    }

    private static void preferLocalSelectionDataType(DropTargetEvent event)
    {
        Transfer local = LocalSelectionTransfer.getTransfer();
        if (event.currentDataType != null && local.isSupportedType(event.currentDataType))
            return;
        TransferData[] types = event.dataTypes;
        if (types == null)
            return;
        for (TransferData type : types)
        {
            if (local.isSupportedType(type))
            {
                event.currentDataType = type;
                return;
            }
        }
    }

    private static String transfersOf(DropTarget target)
    {
        Transfer[] transfers = target.getTransfer();
        if (transfers == null)
            return "none"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < transfers.length; i++)
        {
            if (i > 0)
                sb.append(',');
            sb.append(transfers[i].getClass().getSimpleName());
        }
        return sb.toString();
    }

    private static String typeName(Object value)
    {
        return value == null ? "null" : value.getClass().getName(); //$NON-NLS-1$
    }

    private static void log(String text)
    {
    }
}
