package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.ILabelDecorator;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.IManagedForm;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationAware;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorPage;
import com._1c.g5.v8.dt.md.ui.shared.MdUiSharedImages;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdtype.MdType;
import com._1c.g5.v8.dt.ui.util.OpenHelper;

/**
 * Встраивает страницу «Определяемые типы» в редактор объекта метаданных
 * ({@link DtGranularEditor}): список определяемых типов конфигурации с пометками —
 * входит ли ссылочный тип объекта в состав определяемого типа. Клик по пометке
 * добавляет тип объекта в состав определяемого типа или убирает из него.
 *
 * <p>Вкладка добавляется только объектам, у которых есть ссылочный тип
 * ({@code producedTypes.refType}): справочники, документы, планы видов характеристик и т.п.
 * Переключатель «Только помеченные» (включён по умолчанию) скрывает определяемые типы,
 * в состав которых объект не входит. Фильтр — многословный ({@link SmartMatcher}) по имени
 * и синониму.
 *
 * <p>Вкладка ставится после вкладки «Подписки на события» ({@link MdEventHandlersPageHook}),
 * если та есть, иначе перед «Обмен данными».
 */
public final class MdEditorDefinedTypesPageHook implements IStartup
{
    static final String PAGE_ID = "tormozit.mdDefinedTypes"; //$NON-NLS-1$

    private static final String PAGE_TITLE = "Опред. типы"; //$NON-NLS-1$

    private static final int HOOK_RETRY_DELAY_MS = 200;

    private static final int HOOK_MAX_ATTEMPTS = 150;

    private final Set<DtGranularEditor<?>> hookedEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    private final Set<DtGranularEditor<?>> pendingRetryEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)      { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });
            for (IWorkbenchWindow w : workbench.getWorkbenchWindows())
                hookWindow(w);
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart editor = ref.getEditor(false);
                if (editor instanceof DtGranularEditor<?> granular)
                    hookEditor(granular, 0);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)      { hookFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref)   { hookFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r)  {}
            @Override public void partClosed(IWorkbenchPartReference r)        {}
            @Override public void partDeactivated(IWorkbenchPartReference r)   {}
            @Override public void partHidden(IWorkbenchPartReference r)        {}
            @Override public void partVisible(IWorkbenchPartReference r)       {}
            @Override public void partInputChanged(IWorkbenchPartReference r)  {}

            private void hookFromRef(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference editorRef))
                    return;
                IWorkbenchPart part = editorRef.getPart(false);
                if (part instanceof DtGranularEditor<?> granular)
                    hookEditor(granular, 0);
            }
        });
    }

    private void hookEditor(DtGranularEditor<?> editor, int attempt)
    {
        if (hookedEditors.contains(editor))
            return;
        try
        {
            // Пока редактор не инициализирован, вкладки основных страниц не созданы —
            // повторяем по таймеру (в т.ч. для редакторов, восстановленных при старте).
            if (editor.getModel() == null || !Boolean.TRUE.equals(Global.getField(editor, "initialized"))) //$NON-NLS-1$
            {
                scheduleRetry(editor, attempt);
                return;
            }
            if (!hookedEditors.add(editor))
                return;
            Object model = editor.getModel();
            TypeItem refType = model instanceof MdObject mdObject ? refTypeOf(mdObject) : null;
            if (refType != null)
                addPage(editor, (MdObject)model);
        }
        catch (RuntimeException e)
        {
        }
    }

    private void scheduleRetry(DtGranularEditor<?> editor, int attempt)
    {
        if (attempt >= HOOK_MAX_ATTEMPTS || editor.getSite() == null)
            return;
        Composite container = (Composite)Global.invoke(editor, "getContainer"); //$NON-NLS-1$
        if (container != null && container.isDisposed())
            return;
        if (!pendingRetryEditors.add(editor))
            return;
        Display.getDefault().timerExec(HOOK_RETRY_DELAY_MS, () ->
        {
            pendingRetryEditors.remove(editor);
            hookEditor(editor, attempt + 1);
        });
    }

    private static void addPage(DtGranularEditor<?> editor, MdObject mdObject)
    {
        try
        {
            DefinedTypesPage page = new DefinedTypesPage(mdObject);
            page.initialize(editor);
            Composite container = (Composite)Global.invoke(editor, "getContainer"); //$NON-NLS-1$
            if (container == null || container.isDisposed())
                return;
            page.createPartControl(container);
            int index = insertIndex(editor);
            if (index >= 0)
                editor.addPage(index, page);
            else
                editor.addPage(page);
        }
        catch (PartInitException | RuntimeException e)
        {
        }
    }

    /** После «Подписок на события», иначе — там же, где эту вкладку ставит {@link MdEventHandlersPageHook}. */
    private static int insertIndex(DtGranularEditor<?> editor)
    {
        int subscriptions = MdEventHandlersPageHook.indexOfPageId(editor, MdEventHandlersPageHook.PAGE_ID);
        if (subscriptions >= 0)
            return subscriptions + 1;
        return MdEventHandlersPageHook.resolveInsertIndex(editor);
    }

    // =========================================================================
    // Ссылочный тип объекта и конфигурация
    // =========================================================================

    /** Ссылочный тип объекта ({@code producedTypes.refType}); {@code null} — у объекта его нет. */
    static TypeItem refTypeOf(MdObject mdObject)
    {
        EStructuralFeature produced = mdObject.eClass().getEStructuralFeature("producedTypes"); //$NON-NLS-1$
        Object producedValue = produced != null ? mdObject.eGet(produced) : null;
        if (!(producedValue instanceof EObject producedTypes))
        {
            return null;
        }
        EStructuralFeature refType = producedTypes.eClass().getEStructuralFeature("refType"); //$NON-NLS-1$
        Object refValue = refType != null ? producedTypes.eGet(refType) : null;
        // refType — обёртка MdType с идентификатором; сам TypeItem даёт getType() (как ProdusedTypesUtil EDT).
        return refValue instanceof MdType mdType && mdType.getType() instanceof TypeItem type ? type : null;
    }

    /** Свежая конфигурация проекта объекта (у расширения — его собственная). */
    static Configuration resolveConfiguration(MdObject mdObject)
    {
        IV8ProjectManager projectManager = (IV8ProjectManager)Global.getServiceByClass(IV8ProjectManager.class);
        IV8Project project = projectManager != null ? projectManager.getProject(mdObject) : null;
        return project instanceof IConfigurationAware aware ? aware.getConfiguration() : null;
    }

    static boolean sameType(Object a, Object b)
    {
        if (a == b || (a != null && a.equals(b)))
            return true;
        if (a instanceof EObject ea && b instanceof EObject eb)
            return EcoreUtil.getURI(ea).equals(EcoreUtil.getURI(eb));
        return false;
    }

    // =========================================================================
    // Страница
    // =========================================================================

    static final class DefinedTypesPage extends DtGranularEditorPage<MdObject>
    {
        private final MdObject mdObject;

        private final TypeItem refType;

        private Composite host;

        private FilterInputBox filterInput;

        private Button onlyMarkedButton;

        private TableViewer viewer;

        private Table table;

        private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$

        private boolean onlyMarked = true;

        /** Определяемые типы, пометку которых сняли: остаются в списке, пока фильтр не изменят. */
        private final Set<String> pinnedNames = new HashSet<>();

        DefinedTypesPage(MdObject mdObject)
        {
            super(PAGE_ID, PAGE_TITLE);
            this.mdObject = mdObject;
            this.refType = refTypeOf(mdObject);
        }

        @Override
        protected Layout createPageLayout()
        {
            return new FillLayout();
        }

        @Override
        protected void createPageControls(IManagedForm managedForm)
        {
            host = new Composite(managedForm.getForm().getBody(), SWT.NONE);
            GridLayout layout = new GridLayout(2, false);
            layout.marginWidth = 0;
            layout.marginHeight = 0;
            host.setLayout(layout);

            filterInput = FilterInputBox.forDefinedTypes(host, this::onFilterChanged);

            onlyMarkedButton = new Button(host, SWT.CHECK);
            onlyMarkedButton.setText("Только помеченные"); //$NON-NLS-1$
            onlyMarkedButton.setSelection(true);
            onlyMarkedButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
            onlyMarkedButton.setToolTipText(TooltipText.wrap(onlyMarkedButton,
                "Только помеченные.\nВыключено — показать все определяемые типы конфигурации." //$NON-NLS-1$
                    + Global.pluginSignForTooltip()));
            onlyMarkedButton.addListener(SWT.Selection, e ->
            {
                onlyMarked = onlyMarkedButton.getSelection();
                pinnedNames.clear();
                applyFilter();
            });

            Composite tableHost = new Composite(host, SWT.NONE);
            GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
            // Предпочтительный размер таблицы (все строки списка) не должен раздувать страницу:
            // форма редактора прокручивается по размеру содержимого, а таблица прокручивается сама.
            tableData.heightHint = 100;
            tableData.widthHint = 100;
            tableHost.setLayoutData(tableData);
            TableColumnLayout tableLayout = new TableColumnLayout();
            tableHost.setLayout(tableLayout);

            viewer = new TableViewer(tableHost, SWT.CHECK | SWT.FULL_SELECTION | SWT.SINGLE | SWT.BORDER);
            table = viewer.getTable();
            table.setHeaderVisible(true);
            ThemeAwareColors.applyGridLines(table);

            TableViewerColumn nameColumn = new TableViewerColumn(viewer, SWT.NONE);
            nameColumn.getColumn().setText("Имя"); //$NON-NLS-1$
            // SelectionAwareStyledCellLabelProvider, не Delegating: иначе подсветка вхождений
            // пропадает на выделенной строке (см. класс-javadoc провайдера).
            nameColumn.setLabelProvider(new SelectionAwareStyledCellLabelProvider(new NameLabels()));
            tableLayout.setColumnData(nameColumn.getColumn(), new ColumnPixelData(400, true, true));
            installDecoratorRefresh();

            viewer.setContentProvider(ArrayContentProvider.getInstance());
            viewer.addFilter(new ViewerFilter()
            {
                @Override
                public boolean select(Viewer v, Object parentElement, Object element)
                {
                    return element instanceof DefinedType definedType && isVisible(definedType);
                }
            });

            FormTableInteraction interaction = new FormTableInteraction(table, viewer,
                (item, col) -> item.getData() instanceof DefinedType definedType ? nameOf(definedType) : ""); //$NON-NLS-1$
            interaction.setOwnerDrawColumns(nameColumn.getColumn());
            interaction.install();

            table.addListener(SWT.Selection, e ->
            {
                if (e.detail == SWT.CHECK && e.item instanceof TableItem item)
                    onCheck(item);
            });
            // Двойной клик / Enter: редактор определяемого типа, в нём — диалог состава с текущим типом.
            table.addListener(SWT.DefaultSelection, e ->
            {
                if (e.item instanceof TableItem item && item.getData() instanceof DefinedType definedType)
                    openDefinedType(definedType);
            });
        }

        /** Штатные декораторы EDT (значки проблем, суффикс вида типа и т.п.), как в навигаторе. */
        private final ILabelDecorator decorator = PlatformUI.getWorkbench().getDecoratorManager().getLabelDecorator();

        /**
         * Лёгкие декораторы считаются асинхронно и сообщают о готовности событием — строки
         * обновляем по нему, иначе декорация не появится до следующей перерисовки.
         */
        private void installDecoratorRefresh()
        {
            ILabelProviderListener listener = event -> Display.getDefault().asyncExec(() ->
            {
                if (table == null || table.isDisposed())
                    return;
                Object[] elements = event.getElements();
                if (elements != null)
                    viewer.update(elements, null);
                else
                {
                    viewer.refresh();
                    syncChecks();
                }
            });
            decorator.addListener(listener);
            table.addDisposeListener(e -> decorator.removeListener(listener));
        }

        private final class NameLabels extends LabelProvider
            implements DelegatingStyledCellLabelProvider.IStyledLabelProvider
        {
            @Override
            public Image getImage(Object element)
            {
                if (!(element instanceof DefinedType definedType))
                    return null;
                Image image = MdUiSharedImages.getMdClassImage(definedType.eClass());
                Image decorated = image != null ? decorator.decorateImage(image, element) : null;
                return decorated != null ? decorated : image;
            }

            @Override
            public StyledString getStyledText(Object element)
            {
                String name = element instanceof DefinedType definedType ? nameOf(definedType) : ""; //$NON-NLS-1$
                StyledString styled = new StyledString(name);
                if (!matcher.isEmpty && !name.isEmpty())
                    SmartMatchHighlight.applyRanges(styled, matcher.getHighlightRanges(name), table);
                String decorated = decorator.decorateText(name, element);
                if (decorated == null || decorated.equals(name))
                    return styled;
                return StyledCellLabelProvider.styleDecoratedString(decorated, StyledString.DECORATIONS_STYLER, styled);
            }
        }

        private static String nameOf(DefinedType definedType)
        {
            return definedType.getName() != null ? definedType.getName() : ""; //$NON-NLS-1$
        }

        // ---------------------------------------------------------------------
        // Открытие
        // ---------------------------------------------------------------------

        /**
         * Редактор определяемого типа и в нём диалог «Редактирование типа данных» со строкой
         * ссылочного типа объекта — тот же механизм, что у «Открыть связь» в подписках.
         */
        private void openDefinedType(DefinedType definedType)
        {
            try
            {
                IEditorPart part = new OpenHelper().openEditor(definedType);
                if (part == null)
                    return;
                List<String> targets = new ArrayList<>();
                for (String name : new String[] { refType.getName(), refType.getNameRu() })
                    if (name != null && !name.isBlank() && !targets.contains(name))
                        targets.add(name);
                TypeDescriptionDialogFlow.openInEditor(part, targets, null);
            }
            catch (RuntimeException | LinkageError e)
            {
            }
        }

        // ---------------------------------------------------------------------
        // Состояние
        // ---------------------------------------------------------------------

        /** Ссылочный тип объекта входит в состав определяемого типа. */
        private boolean isMarked(DefinedType definedType)
        {
            TypeDescription description = definedType.getTypeDescription();
            if (description == null)
                return false;
            for (TypeItem type : description.getTypes())
                if (sameType(type, refType))
                    return true;
            return false;
        }

        private boolean isVisible(DefinedType definedType)
        {
            if (onlyMarked && !isMarked(definedType) && !pinnedNames.contains(definedType.getName()))
                return false;
            return matcher.isEmpty || matcher.matches(nameOf(definedType));
        }

        /** Число определяемых типов, в состав которых входит объект; {@code null} — список ещё не загружен. */
        Integer markedCount()
        {
            if (!(viewer.getInput() instanceof List<?> rows))
                return null;
            int count = 0;
            for (Object row : rows)
                if (row instanceof DefinedType definedType && isMarked(definedType))
                    count++;
            return Integer.valueOf(count);
        }

        @Override
        public void setActive(boolean active)
        {
            if (active && host != null && !host.isDisposed())
                reload();
            super.setActive(active);
        }

        @Override
        public void setFocus()
        {
            if (filterInput != null && !filterInput.isDisposed())
                filterInput.widget().setFocus();
            else
                super.setFocus();
        }

        /** Список из модели заново: состав определяемых типов мог измениться в другом редакторе. */
        private void reload()
        {
            Configuration configuration = resolveConfiguration(mdObject);
            List<DefinedType> rows = new ArrayList<>();
            if (configuration != null)
                rows.addAll(configuration.getDefinedTypes());
            rows.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(nameOf(a), nameOf(b)));
            if (!firstLoadDone)
            {
                // Первый заход: «Только помеченные» имеет смысл, только если есть что показать.
                firstLoadDone = true;
                onlyMarked = rows.stream().anyMatch(this::isMarked);
                onlyMarkedButton.setSelection(onlyMarked);
            }
            viewer.setInput(rows);
            syncChecks();
            if (getEditor() instanceof DtGranularEditor<?> granularEditor)
                MdEditorTabsHook.requestRefresh(granularEditor);
        }

        private boolean firstLoadDone;

        private void onFilterChanged()
        {
            pinnedNames.clear();
            applyFilter();
        }

        private void applyFilter()
        {
            matcher = new SmartMatcher(filterInput != null ? filterInput.getText() : null);
            if (viewer == null || table.isDisposed())
                return;
            viewer.refresh();
            syncChecks();
            FilterInputBoxListNavigation.selectFirstRowIfSelectionLost(table);
        }

        private void syncChecks()
        {
            for (TableItem item : table.getItems())
            {
                boolean marked = item.getData() instanceof DefinedType definedType && isMarked(definedType);
                if (item.getChecked() != marked)
                    item.setChecked(marked);
            }
        }

        // ---------------------------------------------------------------------
        // Запись
        // ---------------------------------------------------------------------

        private void onCheck(TableItem item)
        {
            if (!(item.getData() instanceof DefinedType definedType))
                return;
            boolean marked = isMarked(definedType);
            // Пользователь переключил флажок сам — возвращаем расчётное состояние, дальше его
            // выставит перерисовка после записи в модель.
            syncChecks();
            if (definedType.getTypeDescription() == null)
            {
                ToastNotification.show(PAGE_TITLE, "У определяемого типа не задан состав", 5_000); //$NON-NLS-1$
                return;
            }
            if (marked)
                pinnedNames.add(definedType.getName());
            Job job = new Job("Комфорт: изменение определяемого типа") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    applyChange(definedType, !marked);
                    return Status.OK_STATUS;
                }
            };
            job.setSystem(true);
            job.schedule();
        }

        private void applyChange(DefinedType definedType, boolean add)
        {
            try
            {
                IBmModelManager models = (IBmModelManager)Global.getServiceByClass(IBmModelManager.class);
                IBmModel model = models != null ? models.getModel(definedType) : null;
                if (model == null)
                    throw new IllegalStateException("BM-модель определяемого типа не найдена"); //$NON-NLS-1$
                model.execute(new AbstractBmTask<Void>("comfort.definedTypesPage") //$NON-NLS-1$
                {
                    @Override
                    public Void execute(IBmTransaction transaction, IProgressMonitor monitor)
                    {
                        DefinedType target = transaction.toTransactionObject(definedType);
                        TypeDescription description = target.getTypeDescription();
                        if (description == null)
                            return null;
                        if (add)
                        {
                            TypeItem type = refType instanceof IBmObject
                                ? transaction.toTransactionObject(refType) : refType;
                            if (description.getTypes().stream().noneMatch(t -> sameType(t, type)))
                                description.getTypes().add(type);
                        }
                        else
                            description.getTypes().removeIf(t -> sameType(t, refType));
                        return null;
                    }
                });
            }
            catch (RuntimeException e)
            {
                Display display = Display.getDefault();
                if (!display.isDisposed())
                    display.asyncExec(() -> ToastNotification.show(PAGE_TITLE,
                        "Не удалось изменить определяемый тип: " + e.getMessage(), 6_000)); //$NON-NLS-1$
                return;
            }
            Display display = Display.getDefault();
            if (!display.isDisposed())
                display.asyncExec(() ->
                {
                    if (host != null && !host.isDisposed())
                        reload();
                });
        }
    }
}
