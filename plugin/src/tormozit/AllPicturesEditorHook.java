package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.DecoratingLabelProvider;
import org.eclipse.jface.viewers.ILabelDecorator;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
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

import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.md.ui.aef.providers.PictureColumnLabelProvider;
import com._1c.g5.v8.dt.platform.pictures.IPictureManifestQueryComputer;

/**
 * Редактор «Все картинки» (общие картинки конфигурации): то же, что в диалоге «Выбрать картинку»
 * ({@link PictureDialogHook}) — эскизы в списке и многословный фильтр с подсветкой вхождений.
 *
 * <p><b>Эскизы.</b> Штатный провайдер списка — {@link DecoratingLabelProvider}; сам он не
 * заменяется (иначе теряются его текст, слежение за переименованием и декорации), подменяется
 * только его декоратор ({@link ThumbnailDecorator}): вместо штатной иконки отдаёт эскиз, когда тот
 * готов. Эскиз строит штатный {@link PictureColumnLabelProvider} (тот же, что в диалоге) в фоне —
 * синхронно это ~1,6 с на 534 картинки.
 *
 * <p><b>Почему не {@code StyledCellLabelProvider}.</b> У таблицы редактора нет колонок. При
 * {@code refresh} JFace выключает перерисовку, и каждый {@code setText}/{@code setImage} строки
 * откладывает пересчёт ширины прокрутки ({@code Table.fixScrollWidth}); owner-draw провайдер затем
 * зовёт {@code ViewerCell.getBounds()}, а {@code TableItem.getBounds} при взведённом флаге
 * перемеряет ВСЕ строки. Итог — O(n²): 4,7 с блокировки ввода на 534 строках (замер #681).
 * Поэтому подсветка вхождений — оверлеем в {@code SWT.PaintItem}, как в диалоге.
 *
 * <p><b>Фильтр</b> ({@link SmartMatcherPictureFilter}) включается настройкой замены фильтров
 * списков; история запросов персистентна ({@link FilterInputBox.Scope#ALL_PICTURES}), текущая
 * строка переживает смену и очистку фильтра.
 */
public final class AllPicturesEditorHook implements IStartup
{
    private static final String TAG = "AllPicturesEditorHook"; //$NON-NLS-1$

    private static final String EDITOR_CLASS =
        "com._1c.g5.v8.dt.md.ui.pictures.editor.AllPicturesEditor"; //$NON-NLS-1$

    private static final String PATCHED_KEY = "tormozit.allPicturesEditorPatched"; //$NON-NLS-1$

    private static final String STOCK_FILTER_CLASS = "SearchViewerFilter"; //$NON-NLS-1$

    private static final int MAX_ATTEMPTS = 40;

    private static final int RETRY_MS = 100;

    private static final int APPLY_DELAY_MS = 150;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
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

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
                patchIfAllPictures(ref.getEditor(false));
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)     { patchFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref)  { patchFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}

            private void patchFromRef(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference))
                    return;
                IWorkbenchPart part = ((IEditorReference)ref).getPart(false);
                if (part instanceof IEditorPart editorPart)
                    patchIfAllPictures(editorPart);
            }
        });
    }

    private static void patchIfAllPictures(Object editor)
    {
        if (editor != null && EDITOR_CLASS.equals(editor.getClass().getName()))
            scheduleTryPatch(editor, 0);
    }

    private static void scheduleTryPatch(Object editor, int attempt)
    {
        if (attempt >= MAX_ATTEMPTS)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Runnable task = () ->
        {
            if (!tryPatch((IEditorPart)editor))
                scheduleTryPatch(editor, attempt + 1);
        };
        if (attempt == 0)
            display.asyncExec(task);
        else
            display.timerExec(RETRY_MS, task);
    }

    /** @return {@code true}, если делать больше нечего (пропатчено или недоступно). */
    private static boolean tryPatch(IEditorPart editor)
    {
        try
        {
            Control root = editor.getAdapter(Control.class);
            if (root == null && Global.invoke(editor, "getPartControl") instanceof Control c) //$NON-NLS-1$
                root = c;
            if (root == null || root.isDisposed())
                return false;

            // TableViewer штатно нигде не хранится: он — делегат провайдера выделения редактора.
            Object provider = editor.getSite().getSelectionProvider();
            Object delegate = provider != null ? Global.getField(provider, "delegate") : null; //$NON-NLS-1$
            if (!(delegate instanceof TableViewer viewer))
                return false;
            Table table = viewer.getTable();
            SearchBox searchBox = findSearchBox(root);
            if (table == null || table.isDisposed() || searchBox == null || searchBox.isDisposed())
                return false;
            if (Boolean.TRUE.equals(table.getData(PATCHED_KEY)))
                return true;

            Object model = Global.getField(editor, "editorModel"); //$NON-NLS-1$
            Object project = model != null ? Global.getField(model, "v8Project") : null; //$NON-NLS-1$
            Object queryComputer = model != null ? Global.getField(model, "lightThemeQueryComputer") : null; //$NON-NLS-1$
            if (!(project instanceof IV8Project v8Project)
                || !(queryComputer instanceof IPictureManifestQueryComputer computer))
                return false;

            table.setData(PATCHED_KEY, Boolean.TRUE);

            if (viewer.getLabelProvider() instanceof DecoratingLabelProvider labels)
            {
                // Освобождение — через штатный провайдер: его dispose зовёт dispose декоратора.
                // setLabelDecorator сам обновляет все строки (LabelProviderChangedEvent).
                labels.setLabelDecorator(new ThumbnailDecorator(viewer, labels.getLabelDecorator(),
                    new PictureColumnLabelProvider(v8Project.getScriptVariant(), computer)));
            }

            // В редакторе Ctrl+C забирает global Copy части — копируем текст выделенной строки сами.
            CopyCommandSupport.wireCopyOverride(table);
            if (ComfortSettings.isReplaceListFiltersEnabled())
                installFilter(viewer, searchBox);
            return true;
        }
        catch (Exception e)
        {
            Global.logError(TAG, "tryPatch", e); //$NON-NLS-1$
            return true;
        }
    }

    private static SearchBox findSearchBox(Control control)
    {
        if (control instanceof SearchBox box)
            return box;
        if (control instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                SearchBox found = findSearchBox(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static void installFilter(TableViewer viewer, SearchBox searchBox)
    {
        Table table = viewer.getTable();
        SmartMatcherPictureFilter filter = new SmartMatcherPictureFilter();

        // Подсветка вхождений — как в диалоге «Выбрать картинку» (PictureDialogHook.addHighlightToTable).
        table.addListener(SWT.PaintItem, e ->
        {
            SmartMatcher matcher = filter.getMatcher();
            if (matcher == null || matcher.isEmpty)
                return;
            SmartMatchHighlight.paintTableCellMatchOverlay(e, table, (TableItem)e.item, matcher, false, 0, false);
        });

        searchBox.setToolTipText(FilterInputBox.FLAT_FILTER_TOOLTIP + "\nCtrl+↓ — история запросов."); //$NON-NLS-1$
        searchBox.setMinimumSearchTextLength(0);
        searchBox.setJobScheduleDelay(0);
        FilterInputBox.attachHistory(searchBox, FilterInputBox.Scope.ALL_PICTURES);
        // ↑/↓/PgUp/PgDn из поля фильтра — в список (как в диалоге «Выбрать картинку»).
        FilterInputBoxListNavigation.installTableNavigation(searchBox, table, null);

        final int[] generation = { 0 };
        // [0] — текущая строка до фильтра, [1] — уже применённый текст запроса.
        final Object[] remembered = { null, null };
        Runnable schedule = () ->
        {
            if (searchBox.isDisposed())
                return;
            int mine = ++generation[0];
            searchBox.getDisplay().timerExec(APPLY_DELAY_MS, () ->
            {
                if (searchBox.isDisposed() || table.isDisposed() || mine != generation[0])
                    return;
                apply(viewer, searchBox, filter, remembered);
            });
        };
        // Enter / выбор из истории; живой ввод — SWT.Modify (Job SearchBox глотает последний символ).
        searchBox.setSearchListener((text, monitor) -> schedule.run());
        searchBox.setRunSearchOnTextChange(false);
        searchBox.addModifyListener(e -> schedule.run());

        String initial = searchBox.getText();
        if (initial != null && !initial.isEmpty())
            apply(viewer, searchBox, filter, remembered);
    }

    private static void apply(TableViewer viewer, SearchBox searchBox, SmartMatcherPictureFilter filter,
        Object[] remembered)
    {
        String pattern = searchBox.getText() != null ? searchBox.getText().trim() : ""; //$NON-NLS-1$
        // Поле зовёт performSearch и при потере фокуса с тем же текстом — зря пересобирать список нельзя.
        if (pattern.equals(remembered[1]))
            return;
        remembered[1] = pattern;
        filter.setPattern(pattern);

        // Строка, которую скрыл фильтр, возвращается, когда снова видна.
        Object current = viewer.getStructuredSelection().getFirstElement();
        if (current != null)
            remembered[0] = current;

        List<ViewerFilter> keep = new ArrayList<>();
        for (ViewerFilter existing : viewer.getFilters())
            if (!existing.getClass().getSimpleName().contains(STOCK_FILTER_CLASS) && existing != filter)
                keep.add(existing);
        keep.add(filter);
        viewer.setFilters(keep.toArray(new ViewerFilter[0]));

        // Выделение после refresh может остаться, но список прокручен в начало — показываем строку всегда.
        if (remembered[0] != null && filter.select(viewer, null, remembered[0]))
        {
            viewer.setSelection(new StructuredSelection(remembered[0]), true);
            viewer.getTable().showSelection();
        }
        FilterInputBoxListNavigation.selectFirstRowIfSelectionLost(viewer.getTable());
        // Набор строк мог не измениться, а подсветка вхождений — да.
        viewer.getTable().redraw();
    }

    /**
     * Декоратор штатного {@link DecoratingLabelProvider}: вместо штатной иконки отдаёт эскиз
     * картинки, остальное (текст, слушатели, декорации рабочего места) — прежнему декоратору.
     *
     * <p>Эскизы считаются по очереди в фоновом {@link Job}; готовые применяются к строкам пачкой
     * одним {@code asyncExec}. Пока эскиза нет, в строке штатная иконка того же размера 16×16 —
     * высота строк не меняется.
     */
    private static final class ThumbnailDecorator implements ILabelDecorator
    {
        private final TableViewer viewer;
        private final ILabelDecorator stock;
        /** Штатный построитель эскизов; владеет изображениями, не потокобезопасен — под {@code synchronized}. */
        private final PictureColumnLabelProvider source;
        /** UI-поток. */
        private final Map<Object, Image> ready = new HashMap<>();
        /** UI-поток. */
        private final Set<Object> requested = new HashSet<>();
        private final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Object[]> loaded = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean flushPending = new AtomicBoolean();
        private final Job worker;
        private volatile boolean disposed;

        ThumbnailDecorator(TableViewer viewer, ILabelDecorator stock, PictureColumnLabelProvider source)
        {
            this.viewer = viewer;
            this.stock = stock;
            this.source = source;
            worker = new Job("Эскизы общих картинок") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    Object element;
                    while (!disposed && !monitor.isCanceled() && (element = queue.poll()) != null)
                        load(element);
                    return Status.OK_STATUS;
                }
            };
            worker.setSystem(true);
        }

        @Override
        public Image decorateImage(Image image, Object element)
        {
            Image thumbnail = ready.get(element);
            if (thumbnail != null && !thumbnail.isDisposed())
                return thumbnail;
            if (!disposed && requested.add(element))
            {
                queue.add(element);
                worker.schedule();
            }
            return stock != null ? stock.decorateImage(image, element) : null;
        }

        @Override
        public String decorateText(String text, Object element)
        {
            return stock != null ? stock.decorateText(text, element) : null;
        }

        /** Фоновый поток. */
        private void load(Object element)
        {
            Image image = null;
            try
            {
                synchronized (source)
                {
                    if (disposed)
                        return;
                    image = source.getImage(element);
                }
            }
            catch (RuntimeException e)
            {
                Global.logError(TAG, "thumbnail " + element, e); //$NON-NLS-1$
            }
            if (image == null)
                return;
            loaded.add(new Object[] { element, image });
            if (!flushPending.compareAndSet(false, true))
                return;
            Display display = viewer.getControl().getDisplay();
            if (!display.isDisposed())
                display.asyncExec(this::flush);
        }

        /** UI-поток: применить все готовые к этому моменту эскизы одной пачкой. */
        private void flush()
        {
            flushPending.set(false);
            if (disposed || viewer.getControl().isDisposed())
                return;
            List<Object> elements = new ArrayList<>();
            Object[] pair;
            while ((pair = loaded.poll()) != null)
            {
                ready.put(pair[0], (Image)pair[1]);
                elements.add(pair[0]);
            }
            if (elements.isEmpty())
                return;
            viewer.update(elements.toArray(), null);
        }

        @Override
        public void addListener(ILabelProviderListener listener)
        {
            if (stock != null)
                stock.addListener(listener);
        }

        @Override
        public void removeListener(ILabelProviderListener listener)
        {
            if (stock != null)
                stock.removeListener(listener);
        }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            return stock != null && stock.isLabelProperty(element, property);
        }

        /** Зовётся штатным провайдером при его освобождении (закрытие редактора). */
        @Override
        public void dispose()
        {
            if (disposed)
                return;
            disposed = true;
            queue.clear();
            loaded.clear();
            worker.cancel();
            synchronized (source)
            {
                source.dispose();
            }
            // Свой кэш штатный построитель освободил сам; иконку «не прочиталось» он не кэширует.
            for (Image image : ready.values())
                if (!image.isDisposed())
                    image.dispose();
            ready.clear();
            // setLabelDecorator прежний декоратор не освобождает — он остался за нами.
            if (stock != null)
                stock.dispose();
        }
    }
}
