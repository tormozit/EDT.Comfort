package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Table;
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
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;

import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.shared.MdUiSharedImages;
import com._1c.g5.v8.dt.metadata.mdclass.CommonCommand;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Дополняет штатную вкладку «Команды» редактора объекта ({@code DtGranularEditorCommandsPage}):
 * под таблицей команд объекта показывает внешние параметризуемые команды конфигурации, в типе
 * параметра которых указан ссылочный тип объекта — прямо или через определяемый тип, в состав
 * которого он входит. Только просмотр; двойной клик или Enter открывает команду.
 *
 * <p>Штатная таблица лежит во внутреннем {@code DtLayoutComposite} с {@link GridLayout}; свой
 * блок добавляется в тот же контейнер вторым потомком. Если AEF пересоздаст контейнер, блок
 * восстанавливается при следующей активации вкладки.
 */
public final class MdEditorObjectCommandsPageHook implements IStartup
{
    private static final String TAG = "MdEditorObjectCommandsPageHook"; //$NON-NLS-1$

    /** Ключ {@link Table#setData}: таблица принадлежит этому хуку. */
    static final String TABLE_MARKER = "tormozit.objectGlobalCommandsTable"; //$NON-NLS-1$

    private static final String COMMANDS_PAGE_SUFFIX = "EditorCommandsPage"; //$NON-NLS-1$

    private static final int HOOK_RETRY_DELAY_MS = 200;

    private static final int HOOK_MAX_ATTEMPTS = 150;

    private static final int BLOCK_HEIGHT_HINT = 200;

    private static final int SASH_HEIGHT = 5;

    /** Минимальная высота каждого из двух списков при перетаскивании разделителя. */
    private static final int MIN_LIST_HEIGHT = 60;

    private final Set<DtGranularEditor<?>> hookedEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    private final Set<DtGranularEditor<?>> pendingRetryEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    private final WeakHashMap<DtGranularEditor<?>, Block> blocks = new WeakHashMap<>();

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
            if (editor.getModel() == null || !Boolean.TRUE.equals(Global.getField(editor, "initialized"))) //$NON-NLS-1$
            {
                scheduleRetry(editor, attempt);
                return;
            }
            if (!hookedEditors.add(editor))
                return;
            boolean hasRefType = editor.getModel() instanceof MdObject md
                && MdEditorDefinedTypesPageHook.refTypeOf(md) != null;
            if (!hasRefType)
                return;
            MdObject mdObject = (MdObject)editor.getModel();
            IPageChangedListener listener = event ->
            {
                if (isCommandsPage(event.getSelectedPage()))
                    scheduleAttach(editor, mdObject, 0);
            };
            editor.addPageChangedListener(listener);
            if (isCommandsPage(editor.getActivePageInstance()))
                scheduleAttach(editor, mdObject, 0);
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "hook editor", e); //$NON-NLS-1$
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

    private static boolean isCommandsPage(Object page)
    {
        return page != null && page.getClass().getName().endsWith(COMMANDS_PAGE_SUFFIX);
    }

    /** Ждёт, пока штатная страница построит свою таблицу, затем ставит/обновляет блок. */
    private void scheduleAttach(DtGranularEditor<?> editor, MdObject mdObject, int attempt)
    {
        Display.getDefault().timerExec(attempt == 0 ? 0 : HOOK_RETRY_DELAY_MS, () ->
        {
            try
            {
                IFormPage page = editor.getActivePageInstance();
                if (!isCommandsPage(page))
                    return;
                Control root = page.getPartControl();
                Table stock = root != null && !root.isDisposed() ? findStockTable(root) : null;
                if (stock == null)
                {
                    if (attempt < 20)
                        scheduleAttach(editor, mdObject, attempt + 1);
                    return;
                }
                Composite parent = stock.getParent();
                if (!(parent.getLayout() instanceof GridLayout))
                    return;
                Block block = blocks.get(editor);
                boolean created = block == null || block.isDisposed() || block.parent() != parent;
                if (created)
                {
                    block = new Block(parent, mdObject);
                    blocks.put(editor, block);
                }
                block.reload();
                moveStandardCommandsCheckboxToTop(parent.getParent());
            }
            catch (RuntimeException e)
            {
                Global.logError(TAG, "attach block", e); //$NON-NLS-1$
            }
        });
    }

    /**
     * Флажок «Использовать стандартные команды» — «лёгкий» элемент LWT в раскладке секции, стоящий
     * после таблицы. Ставим его первым потомком. {@code removeChild} уничтожает только SWT-обёртки
     * ({@code SwtLightControl}), поэтому переставляем лишь чисто «лёгкий» элемент.
     *
     * <p>Бандл LWT в манифесте плагина не подключён (как и в {@link AefFieldFocus}) — всё через
     * рефлексию; загрузчик классов берём у раскладки секции.
     */
    private static void moveStandardCommandsCheckboxToTop(Composite section)
    {
        if (section == null || section.isDisposed() || section.getLayout() == null)
            return;
        try
        {
            ClassLoader loader = section.getLayout().getClass().getClassLoader();
            Class<?> compositeClass = Class.forName("com._1c.g5.lwt.interop.SwtLightComposite", false, loader); //$NON-NLS-1$
            Class<?> wrapperClass = Class.forName("com._1c.g5.lwt.interop.SwtLightControl", false, loader); //$NON-NLS-1$
            Object light = Global.invoke(compositeClass, "getSwtLightComposite", section); //$NON-NLS-1$
            if (light == null || !(Global.invoke(light, "getChildren") instanceof Iterable<?> iterable)) //$NON-NLS-1$
                return;
            List<Object> children = new ArrayList<>();
            for (Object child : iterable)
                children.add(child);
            Object checkbox = null;
            for (Object child : children)
                if (!wrapperClass.isInstance(child))
                    checkbox = child;
            if (checkbox == null || children.get(0) == checkbox)
                return;
            Global.invoke(light, "addChild", checkbox, Integer.valueOf(0)); //$NON-NLS-1$
            Global.invoke(light, "invalidate"); //$NON-NLS-1$
            section.layout(true);
        }
        catch (ClassNotFoundException | RuntimeException e)
        {
            Global.logError(TAG, "move checkbox", e); //$NON-NLS-1$
        }
    }

    /** Штатная таблица команд объекта: первая таблица страницы, не наша. */
    private static Table findStockTable(Control control)
    {
        if (control instanceof Table table)
            return table.getData(TABLE_MARKER) == null ? table : null;
        if (control instanceof Composite composite)
            for (Control child : composite.getChildren())
            {
                Table found = findStockTable(child);
                if (found != null)
                    return found;
            }
        return null;
    }

    // =========================================================================
    // Блок под штатной таблицей
    // =========================================================================

    private static final class Block
    {
        private final Composite parent;

        private final MdObject mdObject;

        private final Sash sash;

        private final Composite block;

        private final GridData blockData;

        private final Label title;

        private final TableViewer viewer;

        Block(Composite parent, MdObject mdObject)
        {
            this.parent = parent;
            this.mdObject = mdObject;

            // Рамку рисует сам контейнер штатной страницы (BorderPainter по его границам) — она общая
            // для обоих списков; между списками — подвижный разделитель, без зазора.
            GridLayout grid = (GridLayout)parent.getLayout();
            grid.verticalSpacing = 0;

            sash = new Sash(parent, SWT.HORIZONTAL);
            GridData sashData = new GridData(SWT.FILL, SWT.CENTER, true, false);
            sashData.heightHint = SASH_HEIGHT;
            sash.setLayoutData(sashData);

            block = new Composite(parent, SWT.NONE);
            GridLayout layout = new GridLayout(1, false);
            layout.marginWidth = 0;
            layout.marginHeight = 0;
            layout.verticalSpacing = 0;
            block.setLayout(layout);
            blockData = new GridData(SWT.FILL, SWT.FILL, true, false);
            // По умолчанию нижний список занимает половину страницы.
            blockData.heightHint = Math.max(BLOCK_HEIGHT_HINT, parent.getClientArea().height / 2);
            block.setLayoutData(blockData);

            sash.addListener(SWT.Selection, e -> resizeBlock(e.y));

            title = new Label(block, SWT.NONE);
            title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            // Верхняя граница списка: у штатной таблицы своей рамки нет, рамку рисует контейнер.
            Label topBorder = new Label(block, SWT.SEPARATOR | SWT.HORIZONTAL);
            topBorder.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            Composite host = new Composite(block, SWT.NONE);
            host.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            TableColumnLayout tableLayout = new TableColumnLayout();
            host.setLayout(tableLayout);

            viewer = new TableViewer(host, SWT.FULL_SELECTION | SWT.SINGLE);
            Table table = viewer.getTable();
            table.setData(TABLE_MARKER, Boolean.TRUE);
            table.setHeaderVisible(true);
            ThemeAwareColors.applyGridLines(table);

            TableViewerColumn nameColumn = new TableViewerColumn(viewer, SWT.NONE);
            nameColumn.getColumn().setText("Имя"); //$NON-NLS-1$
            nameColumn.setLabelProvider(new ColumnLabelProvider()
            {
                @Override
                public String getText(Object element)
                {
                    return MdEditorGlobalCommandsPageHook.GlobalCommandsPage.nameOf(element);
                }

                @Override
                public Image getImage(Object element)
                {
                    return element instanceof CommonCommand command
                        ? MdUiSharedImages.getMdClassImage(command.eClass()) : null;
                }
            });
            tableLayout.setColumnData(nameColumn.getColumn(), new ColumnPixelData(300, true, true));

            TableViewerColumn groupColumn = new TableViewerColumn(viewer, SWT.NONE);
            groupColumn.getColumn().setText("Группа"); //$NON-NLS-1$
            groupColumn.setLabelProvider(new ColumnLabelProvider()
            {
                @Override
                public String getText(Object element)
                {
                    return MdEditorGlobalCommandsPageHook.GlobalCommandsPage.groupOf(element);
                }
            });
            tableLayout.setColumnData(groupColumn.getColumn(), new ColumnWeightData(1, 200, true));

            viewer.setContentProvider(ArrayContentProvider.getInstance());

            FormTableInteraction interaction = new FormTableInteraction(table, viewer,
                (item, col) -> col == 0
                    ? MdEditorGlobalCommandsPageHook.GlobalCommandsPage.nameOf(item.getData())
                    : MdEditorGlobalCommandsPageHook.GlobalCommandsPage.groupOf(item.getData()));
            interaction.install();

            table.addListener(SWT.DefaultSelection, e ->
            {
                if (e.item instanceof TableItem item && item.getData() instanceof CommonCommand command)
                    MdEditorGlobalCommandsPageHook.GlobalCommandsPage.open(command);
            });
        }

        Composite parent()
        {
            return parent;
        }

        /** Разделитель перетащили на {@code sashY} (координата в контейнере): блок получает остаток. */
        private void resizeBlock(int sashY)
        {
            GridLayout grid = (GridLayout)parent.getLayout();
            int total = parent.getClientArea().height - grid.marginHeight;
            int minTop = grid.marginHeight + MIN_LIST_HEIGHT;
            int maxTop = total - SASH_HEIGHT - MIN_LIST_HEIGHT;
            int top = Math.max(minTop, Math.min(sashY, Math.max(minTop, maxTop)));
            blockData.heightHint = total - top - SASH_HEIGHT;
            parent.layout(true, true);
        }

        boolean isDisposed()
        {
            return block.isDisposed();
        }

        /** Список из модели заново: команды могли измениться в другом редакторе. */
        void reload()
        {
            TypeItem refType = MdEditorDefinedTypesPageHook.refTypeOf(mdObject);
            Configuration configuration = MdEditorDefinedTypesPageHook.resolveConfiguration(mdObject);
            List<CommonCommand> rows = new ArrayList<>();
            if (refType != null && configuration != null)
            {
                Set<String> definedTypeNames = new HashSet<>();
                for (DefinedType definedType : configuration.getDefinedTypes())
                    if (definedType.getName() != null && containsType(definedType.getTypeDescription(), refType))
                        definedTypeNames.add(definedType.getName());
                for (CommonCommand command : configuration.getCommonCommands())
                    if (isParameterized(command, refType, definedTypeNames))
                        rows.add(command);
            }
            rows.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                MdEditorGlobalCommandsPageHook.GlobalCommandsPage.nameOf(a),
                MdEditorGlobalCommandsPageHook.GlobalCommandsPage.nameOf(b)));
            viewer.setInput(rows);
            title.setText("Внешние параметризуемые команды: " + rows.size()); //$NON-NLS-1$
            parent.layout(true, true);
        }

        private static boolean containsType(TypeDescription description, TypeItem type)
        {
            if (description == null)
                return false;
            for (TypeItem item : description.getTypes())
                if (MdEditorDefinedTypesPageHook.sameType(item, type))
                    return true;
            return false;
        }

        /** Тип параметра включает ссылочный тип объекта или определяемый тип с ним в составе. */
        private static boolean isParameterized(CommonCommand command, TypeItem refType, Set<String> definedTypeNames)
        {
            TypeDescription description = command.getCommandParameterType();
            if (description == null)
                return false;
            for (TypeItem type : description.getTypes())
            {
                if (MdEditorDefinedTypesPageHook.sameType(type, refType))
                    return true;
                for (String name : definedTypeNames)
                    if (("DefinedType." + name).equals(type.getName()) //$NON-NLS-1$
                        || ("ОпределяемыйТип." + name).equals(type.getNameRu())) //$NON-NLS-1$
                        return true;
            }
            return false;
        }
    }
}
