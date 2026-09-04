package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ViewerColumn;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.shared.MdUiSharedImages;
import com._1c.g5.v8.dt.ui.validation.ProblemsDecorationHelper;
import com._1c.g5.v8.dt.validation.ValidationUtil;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerUpdateListener;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com._1c.g5.v8.dt.validation.marker.MarkersChangedEvent;
import com._1c.g5.v8.dt.validation.marker.v2.IMarkerManagerV2;

/**
 * Иконки критичности проблем у строк списка форм на вкладке «Формы» редактора объекта
 * (issue 388).
 * <p>
 * Индикатор наложенный, как в навигаторе: фон — значок самой формы, поверх него уменьшенный
 * значок критичности ({@link ProblemsDecorationHelper#decorateImage}). Критичность —
 * максимальная среди маркеров формы вместе с вложенными, как у иконки вкладки
 * ({@code DtGranularEditorMarkerSupport}). Считается в фоне, в отрисовке ячейки только
 * чтение готовой таблицы.
 * <p>
 * Список форм — {@code navigatorTable} в определении {@code DtGranularEditorFormsPage},
 * то есть {@code TableViewer} из {@code DtTableView} с единственной колонкой; иконку даёт
 * обёртка над её {@code CellLabelProvider}.
 */
public final class MdEditorFormsPageHook implements IStartup
{
    private static final String TAG = "MdEditorFormsPage"; //$NON-NLS-1$

    private static final String FORMS_PAGE_ID = "editors.pages.forms"; //$NON-NLS-1$

    private static final String NAVIGATOR_TABLE_COMPONENT_CLASS =
        "com._1c.g5.v8.dt.ui.aef.component.NavigatorTableComponent"; //$NON-NLS-1$

    /** {@code DtTableView} кладёт свой {@code TableViewer} в данные контрола под этим ключом. */
    private static final String DT_TABLE_VIEWER_KEY =
        "com._1c.g5.v8.dt.ui.aef.swt.views.DtTableView.tableViewer"; //$NON-NLS-1$

    /** {@code ViewerColumn.COLUMN_VIEWER_KEY}: колонка JFace в данных {@link TableColumn}. */
    private static final String COLUMN_VIEWER_KEY = "org.eclipse.jface.columnViewer"; //$NON-NLS-1$

    private static final String HOOK_MARKER = "tormozit.formsMarkerIcons"; //$NON-NLS-1$

    private static final int MAX_ATTEMPTS = 40;

    private static final int RETRY_MS = 100;

    /** События коммиттера маркеров идут пачками — считаем один раз в конце пачки. */
    private static final int RECOMPUTE_DELAY_MS = 300;

    private final Set<DtGranularEditor<?>> hookedEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
            for (IWorkbenchWindow w : workbench.getWorkbenchWindows())
                hookWindow(w);
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                if (ref.getEditor(false) instanceof DtGranularEditor<?> granular)
                    hookEditor(granular);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { hookFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { hookFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) { hookFromRef(ref); }
        });
    }

    private void hookFromRef(IWorkbenchPartReference ref)
    {
        if (ref != null && ref.getPart(false) instanceof DtGranularEditor<?> granular)
            hookEditor(granular);
    }

    private void hookEditor(DtGranularEditor<?> editor)
    {
        if (editor == null)
            return;
        if (hookedEditors.add(editor))
        {
            editor.addPageChangedListener((PageChangedEvent event) ->
            {
                IFormPage hinted = event.getSelectedPage() instanceof IFormPage form ? form : null;
                scheduleInstall(editor, 0, hinted);
            });
        }
        scheduleInstall(editor, 0, null);
    }

    private static void scheduleInstall(DtGranularEditor<?> editor, int attempt, IFormPage hintedPage)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || attempt >= MAX_ATTEMPTS)
            return;
        display.timerExec(attempt == 0 ? 0 : RETRY_MS, () ->
        {
            try
            {
                if (install(editor, hintedPage))
                    return;
            }
            catch (RuntimeException e)
            {
                Global.logError(TAG, "install", e); //$NON-NLS-1$
            }
            scheduleInstall(editor, attempt + 1, hintedPage);
        });
    }

    /**
     * @return {@code true}, если делать больше нечего (вкладка не наша или иконки уже
     *     подключены); {@code false} — повторить попытку позже
     */
    private static boolean install(DtGranularEditor<?> editor, IFormPage hintedPage)
    {
        if (editor == null)
            return true;
        IFormPage page = isFormsPage(hintedPage) ? hintedPage : editor.getActivePageInstance();
        if (!isFormsPage(page))
            return true;

        Object root = Global.getField(page, "pageComponent"); //$NON-NLS-1$
        Object component = findComponentByClass(root, NAVIGATOR_TABLE_COMPONENT_CLASS, 0);
        TableViewer viewer = findTableViewer(page, component);
        Table table = viewer != null ? viewer.getTable() : null;
        if (component == null || table == null || table.isDisposed())
            return false;

        if (table.getData(HOOK_MARKER) instanceof FormMarkerIcons installed)
        {
            installed.scheduleRecompute();
            return true;
        }

        IProject project = projectOf(editor.getModel());
        if (project == null)
            return false;

        FormMarkerIcons icons = new FormMarkerIcons(viewer, Global.invoke(component, "getMapper"), project); //$NON-NLS-1$
        if (!icons.install())
            return false;
        table.setData(HOOK_MARKER, icons);
        return true;
    }

    private static boolean isFormsPage(IFormPage page)
    {
        if (page == null)
            return false;
        if (FORMS_PAGE_ID.equals(page.getId()))
            return true;
        return page.getClass().getName().contains("FormsPage"); //$NON-NLS-1$
    }

    private static IProject projectOf(EObject model)
    {
        if (model == null)
            return null;
        IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
        return lookup == null ? null : lookup.getProject(model);
    }

    private static Object findComponentByClass(Object component, String className, int depth)
    {
        if (component == null || depth > 20)
            return null;
        if (className.equals(component.getClass().getName()))
            return component;
        for (Object child : AefFieldFocus.childComponents(component))
        {
            Object found = findComponentByClass(child, className, depth + 1);
            if (found != null)
                return found;
        }
        return null;
    }

    /**
     * На вкладке одна таблица (список форм), но справа есть поля выбора основных форм —
     * поэтому при нескольких кандидатах берём тот viewer, чей вход совпадает с view-модель
     * компонента списка.
     */
    private static TableViewer findTableViewer(IFormPage page, Object component)
    {
        Control root = page != null ? page.getPartControl() : null;
        if (!(root instanceof Composite composite) || composite.isDisposed())
            return null;
        List<TableViewer> viewers = new ArrayList<>();
        collectTableViewers(composite, viewers, 0);
        if (viewers.isEmpty())
            return null;
        for (Object viewModel : viewModels(component))
        {
            for (TableViewer viewer : viewers)
            {
                Object input = viewer.getInput();
                if (input == viewModel || input == Global.invoke(viewModel, "getInput")) //$NON-NLS-1$
                    return viewer;
            }
        }
        return viewers.get(0);
    }

    private static List<Object> viewModels(Object component)
    {
        Object viewModels = Global.invoke(component, "getViewModels"); //$NON-NLS-1$
        List<Object> out = new ArrayList<>();
        if (viewModels instanceof Iterable<?> iterable)
        {
            for (Object viewModel : iterable)
            {
                if (viewModel != null)
                    out.add(viewModel);
            }
        }
        return out;
    }

    private static void collectTableViewers(Control control, List<TableViewer> out, int depth)
    {
        if (control == null || control.isDisposed() || depth > 24)
            return;
        if (control.getData(DT_TABLE_VIEWER_KEY) instanceof TableViewer viewer && !out.contains(viewer))
            out.add(viewer);
        if (control instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
                collectTableViewers(child, out, depth + 1);
        }
    }

    /**
     * Индекс «форма → максимальная критичность» и подмена label provider колонки.
     * Пересчёт — при подключении, при смене вкладки и при изменении маркеров проекта.
     */
    private static final class FormMarkerIcons implements IMarkerUpdateListener
    {
        private final TableViewer viewer;

        private final Object mapper;

        private final IProject project;

        private final Job recomputeJob;

        /** {@code bmGetId} формы → критичность. Заменяется целиком, читается из отрисовки. */
        private volatile Map<Long, MarkerSeverity> severities = Map.of();

        /** В таблице появилась строка, которой нет в индексе (добавили форму). */
        private volatile boolean unknownSeen;

        private IMarkerManagerV2 markerManager;

        private FormMarkerIcons(TableViewer viewer, Object mapper, IProject project)
        {
            this.viewer = viewer;
            this.mapper = mapper;
            this.project = project;
            // Приведение обязательно: Job.create перегружен под ICoreRunnable и IJobFunction
            this.recomputeJob = Job.create("Комфорт: критичность проблем форм", //$NON-NLS-1$
                (ICoreRunnable)monitor -> recompute());
            this.recomputeJob.setSystem(true);
        }

        private boolean install()
        {
            Table table = viewer.getTable();
            if (table == null || table.isDisposed() || table.getColumnCount() == 0)
                return false;
            boolean wrapped = false;
            for (TableColumn column : table.getColumns())
            {
                if (!(column.getData(COLUMN_VIEWER_KEY) instanceof ViewerColumn viewerColumn))
                    continue;
                Object base = Global.getField(viewerColumn, "labelProvider"); //$NON-NLS-1$
                if (base instanceof MarkerIconLabelProvider)
                {
                    wrapped = true;
                    continue;
                }
                if (!(base instanceof CellLabelProvider cellProvider))
                    continue;
                // Не setLabelProvider: он зовёт dispose у прежнего провайдера, а это
                // DtTableViewProvider — он же content provider таблицы, без него список пуст.
                MarkerIconLabelProvider wrapper = new MarkerIconLabelProvider(cellProvider, this);
                Global.setField(viewerColumn, "labelProvider", wrapper); //$NON-NLS-1$
                wrapper.initialize(viewer, viewerColumn);
                wrapped = true;
            }
            if (!wrapped)
                return false;

            markerManager = Global.getOsgiService(IMarkerManagerV2.class);
            if (markerManager != null)
                markerManager.addListener(this);
            table.addDisposeListener(event -> dispose());
            scheduleRecompute();
            return true;
        }

        private void dispose()
        {
            recomputeJob.cancel();
            if (markerManager != null)
                markerManager.removeListener(this);
            markerManager = null;
        }

        @Override
        public void handleMarkersChanged(MarkersChangedEvent event)
        {
            // Считать прямо здесь нельзя: это поток коммиттера маркеров, он держит хранилище
            Collection<IProject> changed = event == null ? null : event.getChangedProjects();
            if (changed != null && !changed.isEmpty() && !changed.contains(project))
                return;
            scheduleRecompute();
        }

        void scheduleRecompute()
        {
            recomputeJob.cancel();
            recomputeJob.schedule(RECOMPUTE_DELAY_MS);
        }

        /** Максимальная критичность строки; {@code null} — пока не посчитано или проблем нет. */
        private MarkerSeverity severityOf(Object element)
        {
            EObject form = modelOf(element);
            if (!(form instanceof IBmObject bmObject))
                return null;
            MarkerSeverity severity = severities.get(Long.valueOf(bmObject.bmGetId()));
            if (severity == null)
            {
                // Строки нет в индексе — форму добавили после последнего расчёта.
                // В индексе у формы без проблем стоит NONE, поэтому цикла пересчётов не будет.
                if (!unknownSeen)
                {
                    unknownSeen = true;
                    scheduleRecompute();
                }
                return null;
            }
            return severity == MarkerSeverity.NONE ? null : severity;
        }

        /**
         * Значок самой формы: он и служит фоном для значка критичности. Штатный провайдер
         * списка иконок не рисует, поэтому берём ту же картинку, что показывает навигатор.
         */
        private Image objectImage(Object element)
        {
            EObject form = modelOf(element);
            if (form == null || form.eIsProxy())
                return null;
            try
            {
                return MdUiSharedImages.getMdClassImage(form.eClass());
            }
            catch (RuntimeException e)
            {
                Global.logError(TAG, "objectImage", e); //$NON-NLS-1$
                return null;
            }
        }

        /** Строка списка — {@code TableItemViewModel}; форму даёт маппер компонента. */
        private EObject modelOf(Object element)
        {
            if (element == null)
                return null;
            if (mapper != null)
            {
                EObject mapped = NavigatorElementModels.resolveEObject(
                    Global.invoke(mapper, "mapViewToModel", element)); //$NON-NLS-1$
                if (mapped != null)
                    return mapped;
            }
            return NavigatorElementModels.resolveEObject(element);
        }

        private void recompute()
        {
            List<EObject> forms = new ArrayList<>();
            runInUi(() ->
            {
                Table table = viewer.getTable();
                if (table == null || table.isDisposed())
                    return;
                for (TableItem item : table.getItems())
                {
                    EObject form = modelOf(item.getData());
                    if (form != null)
                        forms.add(form);
                }
            });
            if (forms.isEmpty())
            {
                unknownSeen = false;
                return;
            }

            IMarkerManager markers = Global.getOsgiService(IMarkerManager.class);
            if (markers == null)
                return;
            Map<Long, MarkerSeverity> computed = new HashMap<>();
            for (EObject form : forms)
            {
                if (!(form instanceof IBmObject bmObject))
                    continue;
                Long id = Long.valueOf(bmObject.bmGetId());
                computed.put(id, maxSeverity(markers, id));
            }
            severities = computed;
            unknownSeen = false;

            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() ->
            {
                Table table = viewer.getTable();
                if (table == null || table.isDisposed())
                    return;
                // update, а не refresh: перечитывать содержимое таблицы незачем, меняются только подписи
                viewer.update(elementsOf(table), null);
            });
        }

        private static Object[] elementsOf(Table table)
        {
            List<Object> elements = new ArrayList<>();
            for (TableItem item : table.getItems())
            {
                if (item.getData() != null)
                    elements.add(item.getData());
            }
            return elements.toArray();
        }

        /**
         * Максимум по маркерам самой формы и вложенных в неё объектов — как штатная иконка
         * вкладки, которая берёт вложенные маркеры редактируемого объекта.
         *
         * @return {@link MarkerSeverity#NONE}, если проблем нет: {@code null} в индексе
         *     означал бы «ещё не посчитано»
         */
        private MarkerSeverity maxSeverity(IMarkerManager markers, Long objectId)
        {
            List<Marker> found = new ArrayList<>();
            for (Marker[] batch : new Marker[][] {
                markers.getNestedMarkers(project, objectId),
                markers.getMarkers(project, objectId) })
            {
                if (batch == null)
                    continue;
                for (Marker marker : batch)
                {
                    if (marker != null)
                        found.add(marker);
                }
            }
            if (found.isEmpty())
                return MarkerSeverity.NONE;
            MarkerSeverity severity = ValidationUtil.getMaxMarkerSeverity(found);
            return severity == null ? MarkerSeverity.NONE : severity;
        }

        private static void runInUi(Runnable action)
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.syncExec(() ->
            {
                try
                {
                    action.run();
                }
                catch (RuntimeException e)
                {
                    Global.logError(TAG, "ui", e); //$NON-NLS-1$
                }
            });
        }
    }

    /**
     * Обёртка штатного {@code DtTableViewProvider}: текст и стили рисует он, иконку слева
     * ставим мы. У строк без проблем иконки нет.
     */
    private static final class MarkerIconLabelProvider extends StyledCellLabelProvider
    {
        private final CellLabelProvider base;

        private final FormMarkerIcons icons;

        private MarkerIconLabelProvider(CellLabelProvider base, FormMarkerIcons icons)
        {
            this.base = base;
            this.icons = icons;
        }

        @Override
        public void update(ViewerCell cell)
        {
            if (base != null)
                base.update(cell);
            if (cell == null)
                return;
            Object element = cell.getElement();
            Image objectImage = cell.getImage() != null ? cell.getImage() : icons.objectImage(element);
            if (objectImage == null)
                return;
            MarkerSeverity severity = icons.severityOf(element);
            cell.setImage(severity == null ? objectImage : decorated(objectImage, severity));
        }

        /** Значок формы с наложенным уменьшенным значком критичности — как в навигаторе. */
        private static Image decorated(Image objectImage, MarkerSeverity severity)
        {
            try
            {
                Image image = ProblemsDecorationHelper.decorateImage(objectImage, severity);
                return image != null ? image : objectImage;
            }
            catch (RuntimeException e)
            {
                Global.logError(TAG, "decorateImage", e); //$NON-NLS-1$
                return objectImage;
            }
        }

        @Override
        public String getToolTipText(Object element)
        {
            return base != null ? base.getToolTipText(element) : null;
        }

        /**
         * Раньше при удалении колонки JFace освобождал штатный провайдер — мы встали на его
         * место, значит и освобождаем его сами (иначе утекут шрифт и content provider AEF).
         */
        @Override
        public void dispose(ColumnViewer viewer, ViewerColumn column)
        {
            super.dispose(viewer, column);
            if (base != null)
                base.dispose(viewer, column);
        }
    }
}
