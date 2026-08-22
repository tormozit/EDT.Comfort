package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabFolderRenderer;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
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

import com._1c.g5.v8.dt.bsl.ui.BslSharedImages;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.md.ui.editor.aef.AbstractDtGranularEditorAefPage;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorEmbeddedEditorPage;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;
import com._1c.g5.v8.dt.md.ui.shared.MdUiSharedImages;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Predefined;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;

/**
 * В заголовки вкладок-списков редактора объекта метаданных добавляет число строк
 * списка: сначала «Подсистемы ?», затем рассчитанное число.
 *
 * <p>Обычные коллекции объекта считаются по модели сразу (сумма many-ссылок страницы:
 * реквизиты+табличные части, формы, команды, макеты, характеристики;
 * предопределённые — {@code predefined.items}).
 * Это же число остаётся после открытия вкладки. Деревья с пометками и доп. индексы —
 * после активации вкладки. Первичный подсчёт не создаёт вкладку: many-ссылки через
 * {@code eGet(false)}, модули — размер файла на диске.
 * «Ввод на основании»: сразу число левого списка со знаком вопроса ({@code 3?}),
 * после открытия вкладки — сумма обоих списков.
 * «Права»: при открытии — «?», после перехода на вкладку — число верхних строк дерева.
 * «Подсистемы»: при открытии — «?», после перехода — число подсистем в составе объекта.
 * Вкладки модулей: если модуль один — «Модуль»; если несколько — короткое имя
 * без «Модуль» («Объект», «Менеджер», …). «+» если файл не пустой, «-» если файла нет или размер 0.
 * У вкладок — картинка EDT (как в навигаторе); значок ошибки/предупреждения штатной
 * вкладки не затирается. Сброс картинки EDT восстанавливается без повторного
 * {@code setImage}. В меню «>>» скрытые вкладки в порядке полосы, между левыми и
 * правыми — пометка {@code <Видимые вкладки>}; выбор прокручивает полосу.
 * Вертикальный список слева — флажок {@link ComfortSettings#PREF_MD_EDITOR_VERTICAL_TABS}
 * и больше 10 вкладок.
 */
public final class MdEditorListTabCountHook implements IStartup
{
    private static final String TAG = "MdEditorListTabCountHook"; //$NON-NLS-1$

    private static final String KEY_FOLDER = "tormozit.mdListTabCount.folder"; //$NON-NLS-1$

    private static final String KEY_WATCHED = "tormozit.mdListTabCount.watched"; //$NON-NLS-1$

    private static final String KEY_RETRYING = "tormozit.mdListTabCount.retrying"; //$NON-NLS-1$

    private static final String KEY_LEFT_NAV = "tormozit.mdListTabCount.leftNav"; //$NON-NLS-1$

    private static final String KEY_SASH = "tormozit.mdListTabCount.sash"; //$NON-NLS-1$

    private static final String KEY_LEFT_PENDING = "tormozit.mdListTabCount.leftPending"; //$NON-NLS-1$

    private static final String KEY_TAB_HEIGHT = "tormozit.mdListTabCount.tabHeight"; //$NON-NLS-1$

    private static final String KEY_LEFT_WIDTH_OK = "tormozit.mdListTabCount.leftWidthOk"; //$NON-NLS-1$

    private static final String KEY_REFRESH_PENDING = "tormozit.mdListTabCount.refreshPending"; //$NON-NLS-1$

    private static final String KEY_COMFORT_IMAGE = "tormozit.mdListTabCount.comfortImage"; //$NON-NLS-1$

    private static final String KEY_CONTENT_APPLIED = "tormozit.mdListTabCount.contentApplied"; //$NON-NLS-1$

    private static final String KEY_OVERFLOW_MENU = "tormozit.mdListTabCount.overflowMenu"; //$NON-NLS-1$

    private static final String OVERFLOW_VISIBLE_MARKER = "<Видимые вкладки>"; //$NON-NLS-1$

    /** Суффикс « N» / « N?» / « ?» / « +» / « -», а также старый вид в скобках. */
    private static final Pattern COUNT_SUFFIX =
        Pattern.compile(" (?:\\((\\?|\\+|\\-|\\d+)\\)|(\\d+\\?|\\?|\\+|\\-|\\d+))$"); //$NON-NLS-1$

    private static final String MODULE_PREFIX_RU = "Модуль "; //$NON-NLS-1$

    private static final String MODULE_SUFFIX_EN = " module"; //$NON-NLS-1$

    /** Штатные заголовки вкладок модулей → короткое имя в именительном падеже. */
    private static final Map<String, String> MODULE_TAB_SHORT_TITLES = Map.ofEntries(
        Map.entry("Модуль объекта", "Объект"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Модуль менеджера", "Менеджер"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Модуль набора записей", "Набор записей"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Модуль команды", "Команда"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Модуль приложения", "Приложение"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Модуль сеанса", "Сеанс"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Модуль внешнего соединения", "Внешнее соединение"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Модуль обычного приложения", "Обычное приложение"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Модуль менеджера значения", "Менеджер значения"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Object module", "Object"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Manager module", "Manager"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Manager Module", "Manager"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Record set module", "Record set"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Command module", "Command"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Application module", "Application"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Session module", "Session"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("External connection module", "External connection"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Ordinary application module", "Ordinary application"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Value manager module", "Value manager")); //$NON-NLS-1$ //$NON-NLS-2$

    private static final int LEFT_TABS_AFTER = 10;

    private static final int HOOK_RETRY_DELAY_MS = 200;

    private static final int HOOK_MAX_ATTEMPTS = 150;

    private static final int COUNT_RETRY_DELAY_MS = 150;

    private static final int COUNT_MAX_ATTEMPTS = 20;

    /** Вкладка «Права» заполняет дерево асинхронно (до ~20 с ожидания индекса). */
    private static final int RIGHTS_COUNT_MAX_ATTEMPTS = 140;

    private static final String DT_TREE_VIEW_CLASS =
        "com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView"; //$NON-NLS-1$

    private final Set<DtGranularEditor<?>> hookedEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    private final Set<DtGranularEditor<?>> pendingRetryEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    private static boolean paintFilterInstalled;

    // =========================================================================
    // IStartup
    // =========================================================================

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

            ContentAssistSettings cas = ContentAssistSettings.getInstance();
            if (cas != null)
            {
                cas.getPreferenceStore().addPropertyChangeListener(event ->
                {
                    if (!ComfortSettings.PREF_MD_EDITOR_VERTICAL_TABS.equals(event.getProperty()))
                        return;
                    Display.getDefault().asyncExec(this::refreshAllHookedEditors);
                });
            }
        });
    }

    private void refreshAllHookedEditors()
    {
        for (DtGranularEditor<?> editor : new ArrayList<>(hookedEditors))
            refreshEditor(editor);
    }

    // =========================================================================
    // Подключение к окну / редактору
    // =========================================================================

    private void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart editor = ref.getEditor(false);
                if (editor instanceof DtGranularEditor<?> granular)
                    hookEditor(granular);
            }
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)      { hookFromRef(ref, false); }
            @Override public void partActivated(IWorkbenchPartReference ref)   { hookFromRef(ref, true); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r)  { hookFromRef(r, true); }
            @Override public void partClosed(IWorkbenchPartReference r)        {}
            @Override public void partDeactivated(IWorkbenchPartReference r)   {}
            @Override public void partHidden(IWorkbenchPartReference r)        {}
            @Override public void partVisible(IWorkbenchPartReference r)       { hookFromRef(r, true); }
            @Override public void partInputChanged(IWorkbenchPartReference r)  { hookFromRef(r, false); }

            private void hookFromRef(IWorkbenchPartReference ref, boolean activationOnly)
            {
                if (!(ref instanceof IEditorReference editorRef))
                    return;
                IWorkbenchPart part = editorRef.getPart(false);
                if (!(part instanceof DtGranularEditor<?> granular))
                    return;
                if (activationOnly && hookedEditors.contains(granular))
                {
                    onEditorBecameVisible(granular);
                    return;
                }
                hookEditor(granular);
            }
        });
    }

    private void hookEditor(DtGranularEditor<?> editor)
    {
        hookEditor(editor, 0);
    }

    private void hookEditor(DtGranularEditor<?> editor, int attempt)
    {
        try
        {
            if (editor.getModel() == null || !isEditorInitialized(editor))
            {
                scheduleHookRetry(editor, attempt);
                return;
            }

            boolean first = hookedEditors.add(editor);
            if (first)
            {
                editor.addPageChangedListener(event ->
                {
                    scheduleRefreshEditor(editor);
                    Display.getDefault().timerExec(200,
                        () -> scheduleRefreshEditor(editor));
                });
            }
            installFolderWatch(editor);
            refreshEditor(editor);
            if (first)
            {
                Display.getDefault().timerExec(300,
                    () -> scheduleRefreshEditor(editor));
            }
            if (!pagesMatchTabs(editor))
                scheduleHookRetry(editor, attempt);
        }
        catch (Exception e)
        {
            Global.logError(TAG, "hook editor", e); //$NON-NLS-1$
        }
    }

    private void scheduleHookRetry(DtGranularEditor<?> editor, int attempt)
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

    private static boolean isEditorInitialized(DtGranularEditor<?> editor)
    {
        Object initialized = Global.getField(editor, "initialized"); //$NON-NLS-1$
        return Boolean.TRUE.equals(initialized);
    }

    /**
     * Вкладки уже созданы и список страниц редактора с ними совпал.
     * У скрытого восстановленного редактора CTabItem'ы могут быть раньше {@code pages}.
     */
    private static boolean pagesMatchTabs(DtGranularEditor<?> editor)
    {
        Object container = Global.invoke(editor, "getContainer"); //$NON-NLS-1$
        if (!(container instanceof CTabFolder folder) || folder.isDisposed())
            return false;
        int tabs = folder.getItemCount();
        if (tabs <= 0)
            return false;
        Object pagesObj = Global.getField(editor, "pages"); //$NON-NLS-1$
        if (!(pagesObj instanceof List<?> pages) || pages.isEmpty())
            return false;
        return pages.size() >= tabs;
    }

    private static void installFolderWatch(DtGranularEditor<?> editor)
    {
        Object container = Global.invoke(editor, "getContainer"); //$NON-NLS-1$
        if (!(container instanceof CTabFolder folder) || folder.isDisposed())
            return;
        if (folder.getData(KEY_FOLDER) != null)
            return;
        folder.setData(KEY_FOLDER, Boolean.TRUE);
        if (!(folder.getRenderer() instanceof ComfortTabRenderer))
            folder.setRenderer(new ComfortTabRenderer(folder));
        installImageRestorePaintFilter();
        folder.setUnselectedImageVisible(true);
        folder.setMRUVisible(false);
        folder.addCTabFolder2Listener(new CTabFolder2Adapter()
        {
            @Override
            public void showList(CTabFolderEvent event)
            {
                event.doit = false;
                showOverflowMenu(folder, event);
            }
        });
        int[] lastCount = { folder.getItemCount() };
        folder.addListener(SWT.Paint, event ->
        {
            if (folder.isDisposed())
                return;
            int n = folder.getItemCount();
            if (n != lastCount[0])
            {
                lastCount[0] = n;
                scheduleRefreshEditor(editor);
                return;
            }
            if (folder.getData(KEY_CONTENT_APPLIED) == null && isTabFolderReady(editor, folder))
                scheduleRefreshEditor(editor);
        });
    }

    private static void applyTabPlacement(DtGranularEditor<?> editor, CTabFolder folder)
    {
        if (folder == null || folder.isDisposed())
            return;
        boolean wantLeft = wantLeftNav(folder);
        Table nav = leftNavOf(folder);
        boolean hasLeft = nav != null;
        if (wantLeft == hasLeft)
        {
            if (hasLeft)
            {
                syncLeftNav(folder, nav);
                applyTabImages(editor, folder);
                ensureLeftNavPixelWidth(folder);
            }
            else
                applyTabImages(editor, folder);
            return;
        }
        // Пустой CTabFolder на время переключения страниц — не разбирать уже показанный список
        if (hasLeft && folder.getItemCount() == 0)
            return;
        if (Boolean.TRUE.equals(folder.getData(KEY_LEFT_PENDING)))
            return;
        folder.setData(KEY_LEFT_PENDING, Boolean.TRUE);
        folder.getDisplay().asyncExec(() ->
        {
            if (folder.isDisposed())
                return;
            folder.setData(KEY_LEFT_PENDING, null);
            boolean want = wantLeftNav(folder);
            if (want)
                showLeftNav(editor, folder);
            else
            {
                hideLeftNav(folder);
                applyTabImages(editor, folder);
            }
        });
    }

    private static boolean wantLeftNav(CTabFolder folder)
    {
        return ComfortSettings.isMdEditorVerticalTabsEnabled()
            && folder.getItemCount() > LEFT_TABS_AFTER;
    }

    private static void showLeftNav(DtGranularEditor<?> editor, CTabFolder folder)
    {
        if (folder.isDisposed())
            return;
        Table existing = leftNavOf(folder);
        if (existing != null)
        {
            syncLeftNav(folder, existing);
            applyTabImages(editor, folder);
            ensureLeftNavPixelWidth(folder);
            return;
        }
        Composite parent = folder.getParent();
        if (parent == null || parent.isDisposed())
            return;

        folder.setData(KEY_TAB_HEIGHT, Integer.valueOf(folder.getTabHeight()));
        parent.setRedraw(false);
        try
        {
            folder.setTabHeight(0);
            SashForm sash = new SashForm(parent, SWT.HORIZONTAL);
            sash.setSashWidth(3);
            folder.setParent(sash);

            Table nav = new Table(sash, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.BORDER
                | SWT.DOUBLE_BUFFERED);
            nav.setHeaderVisible(false);
            nav.setLinesVisible(false);
            TableColumn column = new TableColumn(nav, SWT.NONE);
            nav.addListener(SWT.Resize, event ->
            {
                if (nav.isDisposed() || column.isDisposed())
                    return;
                int width = nav.getClientArea().width;
                if (width > 0 && column.getWidth() != width)
                    column.setWidth(width);
            });
            nav.moveAbove(folder);
            sash.setData(KEY_LEFT_NAV, nav);
            folder.setData(KEY_LEFT_NAV, nav);
            folder.setData(KEY_SASH, sash);
            sash.addListener(SWT.Resize, event -> ensureLeftNavPixelWidth(folder));

            nav.addListener(SWT.Selection, event ->
            {
                if (folder.isDisposed() || nav.isDisposed())
                    return;
                int index = nav.getSelectionIndex();
                if (index < 0 || index == folder.getSelectionIndex())
                    return;
                folder.setSelection(index);
                org.eclipse.swt.widgets.Event swtEvent = new org.eclipse.swt.widgets.Event();
                swtEvent.item = folder.getItem(index);
                folder.notifyListeners(SWT.Selection, swtEvent);
                nav.getDisplay().asyncExec(() ->
                {
                    if (!nav.isDisposed())
                        nav.setFocus();
                });
            });
            CopyCommandSupport.wireCopyOverride(nav);
            syncLeftNav(folder, nav);
            applyTabImages(editor, folder);
            parent.setLayout(new FillLayout());
            parent.layout(true, true);
            ensureLeftNavPixelWidth(folder);
        }
        finally
        {
            parent.setRedraw(true);
        }
    }

    private static Table leftNavOf(CTabFolder folder)
    {
        if (folder == null || folder.isDisposed())
            return null;
        Object navObj = folder.getData(KEY_LEFT_NAV);
        return navObj instanceof Table table && !table.isDisposed() ? table : null;
    }

    private static int leftNavWidthForTitles(Table nav)
    {
        GC gc = new GC(nav);
        try
        {
            int max = 0;
            int imageExtra = 0;
            int lineHeight = gc.stringExtent("А").y; //$NON-NLS-1$
            for (TableItem item : nav.getItems())
            {
                String text = item.getText();
                if (text != null && !text.isEmpty())
                    max = Math.max(max, gc.stringExtent(text).x);
                Image image = item.getImage();
                if (image != null && !image.isDisposed())
                    imageExtra = Math.max(imageExtra, image.getBounds().width + 6);
            }
            if (max <= 0)
                max = gc.stringExtent("Формы 0").x; //$NON-NLS-1$
            return nav.computeTrim(0, 0, max + imageExtra, lineHeight).width;
        }
        finally
        {
            gc.dispose();
        }
    }

    /** @return {@code true}, если ширина уже выставлена в пикселях */
    private static boolean applyLeftNavPixelWidth(SashForm sash, int leftWidth)
    {
        if (sash == null || sash.isDisposed() || leftWidth <= 0)
            return false;
        int total = sash.getClientArea().width;
        int minRight = 48;
        if (total < leftWidth + minRight)
            return false;
        sash.setWeights(new int[] { leftWidth, total - leftWidth });
        return true;
    }

    /**
     * Пока клиент SashForm ещё нулевой (восстановление редакторов при старте),
     * {@link SashForm#setWeights} из 1:1 даёт половину окна. Ждём реальный размер.
     */
    private static void ensureLeftNavPixelWidth(CTabFolder folder)
    {
        if (folder == null || folder.isDisposed())
            return;
        Object sashObj = folder.getData(KEY_SASH);
        if (!(sashObj instanceof SashForm sash) || sash.isDisposed())
            return;
        if (Boolean.TRUE.equals(sash.getData(KEY_LEFT_WIDTH_OK)))
            return;
        Table nav = leftNavOf(folder);
        if (nav == null || nav.isDisposed())
            return;
        if (applyLeftNavPixelWidth(sash, leftNavWidthForTitles(nav)))
            sash.setData(KEY_LEFT_WIDTH_OK, Boolean.TRUE);
    }

    private static void hideLeftNav(CTabFolder folder)
    {
        if (folder.isDisposed())
            return;
        Object navObj = folder.getData(KEY_LEFT_NAV);
        folder.setData(KEY_LEFT_NAV, null);
        folder.setData(KEY_SASH, null);

        Composite parent = folder.getParent();
        Composite editorParent = parent instanceof SashForm ? parent.getParent() : parent;
        if (parent instanceof SashForm)
        {
            if (editorParent != null && !editorParent.isDisposed())
                folder.setParent(editorParent);
            parent.dispose();
        }
        else if (navObj instanceof Table table && !table.isDisposed())
            table.dispose();

        Object heightObj = folder.getData(KEY_TAB_HEIGHT);
        folder.setData(KEY_TAB_HEIGHT, null);
        if (heightObj instanceof Integer height && height.intValue() > 0)
            folder.setTabHeight(height.intValue());
        else
            folder.setTabHeight(-1);

        Composite host = folder.getParent();
        if (host == null || host.isDisposed())
            return;
        folder.setLayoutData(null);
        host.setLayout(new FillLayout());
        host.layout(true, true);
    }

    private static void syncLeftNav(CTabFolder folder, Table nav)
    {
        if (folder.isDisposed() || nav.isDisposed())
            return;
        CTabItem[] items = folder.getItems();
        boolean countChanged = nav.getItemCount() != items.length;
        if (countChanged)
        {
            nav.setRedraw(false);
            nav.removeAll();
            for (CTabItem item : items)
            {
                TableItem row = new TableItem(nav, SWT.NONE);
                row.setText(item.getText());
            }
            nav.setRedraw(true);
        }
        else
        {
            for (int i = 0; i < items.length; i++)
            {
                CTabItem item = items[i];
                TableItem row = nav.getItem(i);
                String text = item.getText();
                if (!text.equals(row.getText()))
                    row.setText(text);
            }
        }
        int sel = folder.getSelectionIndex();
        if (sel >= 0 && nav.getSelectionIndex() != sel)
            nav.setSelection(sel);
    }

    // =========================================================================
    // Обновление заголовков
    // =========================================================================

    private static void refreshEditor(DtGranularEditor<?> editor)
    {
        try
        {
            Object container = Global.invoke(editor, "getContainer"); //$NON-NLS-1$
            if (!(container instanceof CTabFolder folder) || folder.isDisposed())
                return;

            if (!isTabFolderReady(editor, folder))
                return;
            folder.setRedraw(false);
            try
            {
                Object pagesObj = Global.getField(editor, "pages"); //$NON-NLS-1$
                List<?> pages = pagesObj instanceof List<?> list ? list : List.of();
                CTabItem[] items = folder.getItems();
                for (int i = 0; i < items.length; i++)
                {
                    CTabItem item = items[i];
                    if (item == null || item.isDisposed())
                        continue;
                    Object pageObj = i < pages.size() ? pages.get(i) : null;
                    IFormPage page = pageObj instanceof IFormPage formPage ? formPage : null;
                    refreshTab(editor, item, page, 0);
                }
                applyTabImages(editor, folder);
                applyTabPlacement(editor, folder);
            }
            finally
            {
                folder.setRedraw(true);
            }
            folder.setData(KEY_CONTENT_APPLIED, Boolean.TRUE);
        }
        catch (Exception e)
        {
            Global.logError(TAG, "refresh editor", e); //$NON-NLS-1$
        }
    }

    private static void scheduleRefreshEditor(DtGranularEditor<?> editor)
    {
        Object container = Global.invoke(editor, "getContainer"); //$NON-NLS-1$
        if (!(container instanceof CTabFolder folder) || folder.isDisposed())
            return;
        if (Boolean.TRUE.equals(folder.getData(KEY_REFRESH_PENDING)))
            return;
        folder.setData(KEY_REFRESH_PENDING, Boolean.TRUE);
        folder.getDisplay().asyncExec(() ->
        {
            if (folder.isDisposed())
                return;
            folder.setData(KEY_REFRESH_PENDING, null);
            refreshEditor(editor);
        });
    }

    static void requestRefresh(DtGranularEditor<?> editor)
    {
        scheduleRefreshEditor(editor);
    }

    static void refreshFunctionalOptionsTitle(DtGranularEditor<?> editor)
    {
        if (editor == null)
            return;
        Object container = Global.invoke(editor, "getContainer"); //$NON-NLS-1$
        if (!(container instanceof CTabFolder folder) || folder.isDisposed())
            return;
        Object pagesObj = Global.getField(editor, "pages"); //$NON-NLS-1$
        List<?> pages = pagesObj instanceof List<?> list ? list : List.of();
        CTabItem[] items = folder.getItems();
        for (int i = 0; i < items.length; i++)
        {
            CTabItem item = items[i];
            if (item == null || item.isDisposed())
                continue;
            Object pageObj = i < pages.size() ? pages.get(i) : null;
            IFormPage page = pageObj instanceof IFormPage formPage ? formPage : null;
            if (!isFunctionalOptionsPage(page, null))
                continue;
            refreshTab(editor, item, page, COUNT_MAX_ATTEMPTS);
            return;
        }
    }

    private static void onEditorBecameVisible(DtGranularEditor<?> editor)
    {
        Object container = Global.invoke(editor, "getContainer"); //$NON-NLS-1$
        if (!(container instanceof CTabFolder folder) || folder.isDisposed())
            return;
        if (!isTabFolderReady(editor, folder))
            return;
        if (folder.getData(KEY_CONTENT_APPLIED) == null)
            refreshEditor(editor);
        else
            restoreTabImagesQuiet(folder);
    }

    private static boolean isEditorPartVisible(DtGranularEditor<?> editor)
    {
        try
        {
            var site = editor.getSite();
            return site != null && site.getPage() != null && site.getPage().isPartVisible(editor);
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    /**
     * Не трогаем CTabFolder, пока редактор скрыт или вкладки ещё не отрисованы:
     * setText/setImage на нулевой полосе сдвигают firstIndex (первые вкладки уезжают в «>>»).
     */
    private static boolean isTabFolderReady(DtGranularEditor<?> editor, CTabFolder folder)
    {
        if (folder == null || folder.isDisposed() || folder.getItemCount() <= 0)
            return false;
        if (folder.getClientArea().width <= 0)
            return false;
        if (!isEditorPartVisible(editor))
            return false;
        for (CTabItem item : folder.getItems())
        {
            if (item != null && !item.isDisposed() && item.isShowing())
                return true;
        }
        return false;
    }

    /**
     * {@code CTabFolder.updateItems(selectedIndex)} при нехватке места сдвигает
     * {@code firstIndex}, чтобы выбранная вкладка осталась на полосе. Это штатно:
     * первые уезжают в «>>», выбор из меню прокручивает окно. Меню помечает
     * видимый промежуток {@link #OVERFLOW_VISIBLE_MARKER}.
     */
    private static void showOverflowMenu(CTabFolder folder, CTabFolderEvent event)
    {
        if (folder == null || folder.isDisposed())
            return;
        Object oldObj = folder.getData(KEY_OVERFLOW_MENU);
        if (oldObj instanceof Menu old && !old.isDisposed())
            old.dispose();
        Menu menu = new Menu(folder.getShell(), SWT.POP_UP);
        folder.setData(KEY_OVERFLOW_MENU, menu);
        menu.addListener(SWT.Hide, e -> e.display.asyncExec(() ->
        {
            if (!menu.isDisposed())
                menu.dispose();
        }));
        menu.addListener(SWT.Dispose, e ->
        {
            if (!folder.isDisposed() && folder.getData(KEY_OVERFLOW_MENU) == menu)
                folder.setData(KEY_OVERFLOW_MENU, null);
        });

        boolean markerAdded = false;
        for (CTabItem tab : folder.getItems())
        {
            if (tab == null || tab.isDisposed())
                continue;
            if (tab.isShowing())
            {
                if (!markerAdded)
                {
                    MenuItem marker = new MenuItem(menu, SWT.NONE);
                    marker.setText(OVERFLOW_VISIBLE_MARKER);
                    marker.setEnabled(false);
                    markerAdded = true;
                }
                continue;
            }
            addOverflowMenuItem(menu, folder, tab);
        }
        if (menu.getItemCount() <= 0)
        {
            menu.dispose();
            return;
        }
        int x = event.x;
        int y = event.y + event.height;
        Point location = folder.getDisplay().map(folder, null, x, y);
        menu.setLocation(location.x, location.y);
        menu.setVisible(true);
    }

    private static void addOverflowMenuItem(Menu menu, CTabFolder folder, CTabItem tab)
    {
        MenuItem item = new MenuItem(menu, SWT.NONE);
        String text = tab.getText();
        item.setText(text == null ? "" : text.replace("\n", " ")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Image image = tab.getImage();
        if (usableImage(image))
            item.setImage(image);
        item.addListener(SWT.Selection, event ->
        {
            if (tab.isDisposed() || folder.isDisposed())
                return;
            int index = folder.indexOf(tab);
            if (index < 0)
                return;
            int old = folder.getSelectionIndex();
            folder.setSelection(index);
            if (index != old)
            {
                Event swt = new Event();
                swt.item = tab;
                folder.notifyListeners(SWT.Selection, swt);
            }
        });
    }

    /**
     * EDT обнуляет image и ставит в очередь {@code updateFolder}. Если ответить
     * {@code setImage}, будет второй пересчёт полосы — мигание. Возвращаем ссылку
     * в поле до {@code computeSize}/{@code draw}, без {@code updateFolder}.
     */
    private static void installImageRestorePaintFilter()
    {
        if (paintFilterInstalled)
            return;
        paintFilterInstalled = true;
        Display.getDefault().addFilter(SWT.Paint, event ->
        {
            if (!(event.widget instanceof CTabFolder folder))
                return;
            if (folder.isDisposed() || folder.getData(KEY_FOLDER) == null)
                return;
            if (tabImageNeedsRestore(folder))
                restoreTabImagesQuiet(folder);
        });
    }

    private static void restoreTabImagesQuiet(CTabFolder folder)
    {
        if (folder == null || folder.isDisposed())
            return;
        for (CTabItem item : folder.getItems())
        {
            if (item == null || item.isDisposed())
                continue;
            if (usableImage(item.getImage()))
                continue;
            Image comfort = dataImage(item, KEY_COMFORT_IMAGE);
            if (comfort == null)
                continue;
            Global.setField(item, "image", comfort); //$NON-NLS-1$
        }
    }

    /**
     * Штатный рендерер считает ширину и рисует по {@code item.getImage()}.
     * Подставляем картинку Комфорта до этих вызовов, чтобы {@code runUpdate}
     * после сброса EDT не сжимал вкладки.
     */
    private static final class ComfortTabRenderer extends CTabFolderRenderer
    {
        ComfortTabRenderer(CTabFolder parent)
        {
            super(parent);
        }

        @Override
        protected Point computeSize(int part, int state, GC gc, int wHint, int hHint)
        {
            restoreTabImagesQuiet(parent);
            return super.computeSize(part, state, gc, wHint, hHint);
        }

        @Override
        protected void draw(int part, int state, Rectangle bounds, GC gc)
        {
            restoreTabImagesQuiet(parent);
            super.draw(part, state, bounds, gc);
        }
    }

    private static void refreshTab(DtGranularEditor<?> editor, CTabItem item, IFormPage page, int attempt)
    {
        if (item == null || item.isDisposed())
            return;

        String baseTitle = baseTitle(editor, item, page);
        if (baseTitle == null || baseTitle.isBlank())
            return;

        if (isModulePage(page))
        {
            String mark = modulePresenceMark(editor, page);
            if (mark != null)
                applyTitle(item, baseTitle, mark);
            else if (COUNT_SUFFIX.matcher(item.getText()).find())
                applyTitle(item, baseTitle, null);
            return;
        }

        if (!looksLikeListPage(page))
        {
            if (COUNT_SUFFIX.matcher(item.getText()).find())
                applyTitle(item, baseTitle, null);
            return;
        }

        Control partControl = page != null ? page.getPartControl() : item.getControl();
        boolean created = partControl != null && !partControl.isDisposed();

        if (isGenerationPage(page))
        {
            refreshGenerationTab(editor, item, page, partControl, created, baseTitle, attempt);
            return;
        }

        if (isMdEventHandlersPage(page, baseTitle))
        {
            Integer uiRows = countEventHandlerSubscriptions(page);
            if (uiRows != null)
            {
                applyTitle(item, baseTitle, Integer.toString(uiRows.intValue()));
                if (created)
                    watchList(editor, item, page, partControl);
                return;
            }
            applyTitle(item, baseTitle, "?"); //$NON-NLS-1$
            return;
        }

        if (isFunctionalOptionsPage(page, baseTitle))
        {
            Integer count = MdEditorFunctionalOptionsCountHook.nonZeroRowCount(page);
            if (count != null)
            {
                applyTitle(item, baseTitle, Integer.toString(count.intValue()));
                return;
            }
            applyTitle(item, baseTitle, "?"); //$NON-NLS-1$
            if (created && attempt < COUNT_MAX_ATTEMPTS)
                scheduleCountRetry(editor, item, page, attempt);
            return;
        }

        if (isSubsystemsPage(page, baseTitle))
        {
            Integer marked = created ? countMarkedSubsystems(editor) : null;
            if (marked != null)
            {
                applyTitle(item, baseTitle, Integer.toString(marked.intValue()));
                watchList(editor, item, page, partControl);
                return;
            }
            applyTitle(item, baseTitle, "?"); //$NON-NLS-1$
            if (created && attempt < COUNT_MAX_ATTEMPTS)
                scheduleCountRetry(editor, item, page, attempt);
            return;
        }

        if (isRightsPage(page))
        {
            Integer topRows = created ? countRightsTopRows(page) : null;
            if (topRows != null && (topRows.intValue() > 0 || attempt >= RIGHTS_COUNT_MAX_ATTEMPTS))
            {
                applyTitle(item, baseTitle, Integer.toString(topRows.intValue()));
                watchRightsTree(editor, item, page);
                return;
            }
            applyTitle(item, baseTitle, "?"); //$NON-NLS-1$
            if (created)
                watchRightsTree(editor, item, page);
            if (created && attempt < RIGHTS_COUNT_MAX_ATTEMPTS)
                scheduleCountRetry(editor, item, page, attempt);
            return;
        }

        Integer modelRows = countFromModel(editor, page, created);
        if (modelRows != null)
        {
            applyTitle(item, baseTitle, Integer.toString(modelRows.intValue()));
            if (created)
                watchList(editor, item, page, partControl);
            return;
        }

        if (countsFromModelOnly(page))
        {
            if (created && attempt >= COUNT_MAX_ATTEMPTS)
                applyTitle(item, baseTitle, "0"); //$NON-NLS-1$
            else
                applyTitle(item, baseTitle, "?"); //$NON-NLS-1$
            if (created && attempt < COUNT_MAX_ATTEMPTS)
                scheduleCountRetry(editor, item, page, attempt);
            return;
        }

        Integer uiRows = created ? countRows(partControl, page) : null;
        if (uiRows != null)
        {
            applyTitle(item, baseTitle, Integer.toString(uiRows.intValue()));
            watchList(editor, item, page, partControl);
            return;
        }

        if (created && attempt >= COUNT_MAX_ATTEMPTS)
            applyTitle(item, baseTitle, null);
        else
            applyTitle(item, baseTitle, "?"); //$NON-NLS-1$

        if (created && attempt < COUNT_MAX_ATTEMPTS)
            scheduleCountRetry(editor, item, page, attempt);
    }

    private static void scheduleCountRetry(DtGranularEditor<?> editor, CTabItem item, IFormPage page,
        int attempt)
    {
        if (item.isDisposed() || Boolean.TRUE.equals(item.getData(KEY_RETRYING)))
            return;
        item.setData(KEY_RETRYING, Boolean.TRUE);
        Display display = item.getDisplay();
        display.timerExec(COUNT_RETRY_DELAY_MS, () ->
        {
            if (item.isDisposed())
                return;
            item.setData(KEY_RETRYING, null);
            refreshTab(editor, item, page, attempt + 1);
        });
    }

    /**
     * Левое дерево («Вводится на основании») — {@code basedOn} объекта, сразу.
     * Правое («Является основанием для») появляется при создании страницы.
     * До активации: {@code 3?}; после — сумма листьев обоих деревьев.
     */
    private static void refreshGenerationTab(DtGranularEditor<?> editor, CTabItem item, IFormPage page,
        Control partControl, boolean created, String baseTitle, int attempt)
    {
        int basedOn = countBasedOn(editor);
        String partial = Integer.toString(basedOn) + "?"; //$NON-NLS-1$
        if (!created)
        {
            applyTitle(item, baseTitle, partial);
            return;
        }
        Integer total = countGenerationTrees(partControl, Integer.valueOf(basedOn));
        if (total == null && attempt >= COUNT_MAX_ATTEMPTS)
            total = countGenerationTrees(partControl, null);
        if (total != null)
        {
            applyTitle(item, baseTitle, Integer.toString(total.intValue()));
            watchGenerationTrees(editor, item, page, partControl);
            return;
        }
        applyTitle(item, baseTitle, partial);
        if (attempt < COUNT_MAX_ATTEMPTS)
            scheduleCountRetry(editor, item, page, attempt);
    }

    private static int countBasedOn(DtGranularEditor<?> editor)
    {
        EObject model = editor.getModel();
        if (model == null || model.eIsProxy())
            return 0;
        EStructuralFeature feature = model.eClass().getEStructuralFeature("basedOn"); //$NON-NLS-1$
        if (!(feature instanceof EReference reference) || !reference.isMany())
            return 0;
        try
        {
            Object value = model.eGet(reference, false);
            return value instanceof Collection<?> collection ? collection.size() : 0;
        }
        catch (RuntimeException ignored)
        {
            return 0;
        }
    }

    /**
     * Сумма листьев обоих деревьев. Если {@code requiredLeft} задан — ждём, пока
     * одно из деревьев даст столько же листьев (левый список уже совпал с {@code basedOn}).
     * Папки типов (Справочники, Документы) не считаются.
     */
    private static Integer countGenerationTrees(Control root, Integer requiredLeft)
    {
        List<Control> lists = new ArrayList<>();
        collectLists(root, lists);
        List<Integer> leafCounts = new ArrayList<>();
        for (Control control : lists)
        {
            if (!(control instanceof Tree tree))
                continue;
            Integer leaves = countContentLeaves(tree);
            if (leaves == null)
                return null;
            leafCounts.add(leaves);
        }
        if (leafCounts.size() < 2)
            return null;
        boolean leftReady = requiredLeft == null || requiredLeft.intValue() == 0;
        int sum = 0;
        for (Integer count : leafCounts)
        {
            int n = count.intValue();
            if (requiredLeft != null && n == requiredLeft.intValue())
                leftReady = true;
            sum += n;
        }
        return leftReady ? Integer.valueOf(sum) : null;
    }

    private static Integer countContentLeaves(Tree tree)
    {
        TreeViewer viewer = findTreeViewer(tree);
        return viewer == null ? null : countViewerLeaves(viewer);
    }

    /**
     * Подписки, видимые после фильтров вкладки (отбор по объекту, поиск),
     * без групп событий. Обход content provider без фильтров даёт все подписки конфигурации.
     */
    private static Integer countEventHandlerSubscriptions(IFormPage page)
    {
        if (page == null || !Boolean.TRUE.equals(Global.getField(page, "filled"))) //$NON-NLS-1$
            return null;
        Object editor = Global.getField(page, "embeddedEditor"); //$NON-NLS-1$
        Object mainSection = editor != null ? Global.invoke(editor, "getMainSection") : null; //$NON-NLS-1$
        Object viewerObj = mainSection != null
            ? Global.invoke(mainSection, "getEventHandlersTreeViewer") : null; //$NON-NLS-1$
        if (!(viewerObj instanceof TreeViewer viewer))
            return null;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return null;
        Object provider = viewer.getContentProvider();
        if (!(provider instanceof ITreeContentProvider content))
            return Integer.valueOf(countVisibleEventSubscriptions(tree.getItems()));
        Object[] roots;
        try
        {
            roots = content.getElements(viewer.getInput());
        }
        catch (RuntimeException e)
        {
            return Integer.valueOf(countVisibleEventSubscriptions(tree.getItems()));
        }
        int[] count = { 0 };
        walkFilteredEventSubscriptions(viewer, content, viewer.getFilters(), viewer.getInput(), roots,
            count);
        return Integer.valueOf(count[0]);
    }

    private static void walkFilteredEventSubscriptions(TreeViewer viewer, ITreeContentProvider content,
        ViewerFilter[] filters, Object parent, Object[] elements, int[] count)
    {
        if (elements == null)
            return;
        for (Object element : elements)
        {
            if (!passesViewerFilters(viewer, filters, parent, element))
                continue;
            if (element instanceof EventSubscription)
                count[0]++;
            Object[] children;
            try
            {
                children = content.getChildren(element);
            }
            catch (RuntimeException e)
            {
                continue;
            }
            walkFilteredEventSubscriptions(viewer, content, filters, element, children, count);
        }
    }

    private static boolean passesViewerFilters(TreeViewer viewer, ViewerFilter[] filters, Object parent,
        Object element)
    {
        if (filters == null)
            return true;
        for (ViewerFilter filter : filters)
        {
            if (filter != null && !filter.select(viewer, parent, element))
                return false;
        }
        return true;
    }

    private static int countVisibleEventSubscriptions(TreeItem[] items)
    {
        int n = 0;
        for (TreeItem item : items)
        {
            if (item.getData() instanceof EventSubscription)
                n++;
            n += countVisibleEventSubscriptions(item.getItems());
        }
        return n;
    }

    private static Integer countViewerLeaves(TreeViewer viewer)
    {
        if (viewer == null)
            return null;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return null;
        Object provider = viewer.getContentProvider();
        if (!(provider instanceof ITreeContentProvider content))
            return null;
        Object[] roots;
        try
        {
            roots = content.getElements(viewer.getInput());
        }
        catch (RuntimeException e)
        {
            return null;
        }
        if (roots == null)
            return Integer.valueOf(0);
        int[] leaves = { 0 };
        walkContentLeaves(content, roots, leaves);
        return Integer.valueOf(leaves[0]);
    }

    private static void walkContentLeaves(ITreeContentProvider content, Object[] elements, int[] leaves)
    {
        if (elements == null)
            return;
        for (Object element : elements)
        {
            Object[] children;
            try
            {
                children = content.getChildren(element);
            }
            catch (RuntimeException e)
            {
                continue;
            }
            if (children == null || children.length == 0)
                leaves[0]++;
            else
                walkContentLeaves(content, children, leaves);
        }
    }

    private static void watchGenerationTrees(DtGranularEditor<?> editor, CTabItem item, IFormPage page,
        Control root)
    {
        List<Control> lists = new ArrayList<>();
        collectLists(root, lists);
        for (Control control : lists)
        {
            if (!(control instanceof Tree tree) || tree.isDisposed() || tree.getData(KEY_WATCHED) != null)
                continue;
            tree.setData(KEY_WATCHED, Boolean.TRUE);
            tree.addListener(SWT.Paint, event ->
            {
                if (item.isDisposed() || tree.isDisposed())
                    return;
                refreshTab(editor, item, page, COUNT_MAX_ATTEMPTS);
            });
            tree.addListener(SWT.Selection, event ->
            {
                if (item.isDisposed())
                    return;
                refreshTab(editor, item, page, COUNT_MAX_ATTEMPTS);
            });
        }
    }

    private static String baseTitle(DtGranularEditor<?> editor, CTabItem item, IFormPage page)
    {
        if (page != null)
        {
            String title = page.getTitle();
            if (title != null && !title.isBlank())
                return displayBaseTitle(editor, page, stripCountSuffix(title));
        }
        String text = item.getText();
        return text == null ? null : displayBaseTitle(editor, page, stripCountSuffix(text));
    }

    private static String displayBaseTitle(DtGranularEditor<?> editor, IFormPage page, String base)
    {
        if (base == null)
            return null;
        if (isAdditionalIndexesPage(page, base))
            return "Доп. индексы"; //$NON-NLS-1$
        if (isFunctionalOptionsPage(page, base))
            return "Функц. опции"; //$NON-NLS-1$
        if (isMdEventHandlersPage(page, base))
            return "Подписки"; //$NON-NLS-1$
        if (isModulePage(page) || looksLikeModuleTabTitle(base))
            return shortenModuleTabTitle(editor, base);
        return shortenPredefinedBase(page, base);
    }

    private static boolean looksLikeModuleTabTitle(String base)
    {
        if (base == null || base.isBlank())
            return false;
        if (MODULE_TAB_SHORT_TITLES.containsKey(base))
            return true;
        if (base.startsWith(MODULE_PREFIX_RU) || "Модуль".equals(base)) //$NON-NLS-1$
            return true;
        String lower = base.toLowerCase(Locale.ENGLISH);
        return lower.endsWith(MODULE_SUFFIX_EN) || "module".equals(lower); //$NON-NLS-1$
    }

    private static String shortenModuleTabTitle(DtGranularEditor<?> editor, String base)
    {
        if (countModulePages(editor) <= 1)
            return soloModuleTabTitle(base);
        String mapped = MODULE_TAB_SHORT_TITLES.get(base);
        if (mapped != null)
            return mapped;
        if (base.startsWith(MODULE_PREFIX_RU))
        {
            String rest = base.substring(MODULE_PREFIX_RU.length()).strip();
            return rest.isEmpty() ? base : capitalizeFirst(rest);
        }
        if (base.length() > MODULE_SUFFIX_EN.length()
            && base.toLowerCase(Locale.ENGLISH).endsWith(MODULE_SUFFIX_EN))
        {
            String rest = base.substring(0, base.length() - MODULE_SUFFIX_EN.length()).strip();
            return rest.isEmpty() ? base : rest;
        }
        return base;
    }

    private static String soloModuleTabTitle(String base)
    {
        if (base != null && base.toLowerCase(Locale.ENGLISH).contains("module")) //$NON-NLS-1$
            return "Module"; //$NON-NLS-1$
        return "Модуль"; //$NON-NLS-1$
    }

    private static int countModulePages(DtGranularEditor<?> editor)
    {
        if (editor == null)
            return 0;
        Object pagesObj = Global.getField(editor, "pages"); //$NON-NLS-1$
        if (!(pagesObj instanceof List<?> pages))
            return 0;
        int n = 0;
        for (Object pageObj : pages)
        {
            if (pageObj instanceof IFormPage page && isModulePage(page))
                n++;
        }
        return n;
    }

    private static String capitalizeFirst(String text)
    {
        int cp = text.codePointAt(0);
        int upper = Character.toUpperCase(cp);
        if (upper == cp)
            return text;
        return new String(Character.toChars(upper)) + text.substring(Character.charCount(cp));
    }

    private static boolean isMdEventHandlersPage(IFormPage page, String base)
    {
        if (page != null)
        {
            String id = page.getId();
            if (id != null && "tormozit.mdEventHandlers".equals(id)) //$NON-NLS-1$
                return true;
            if (page.getClass().getName().contains("MdEventHandlersPageHook")) //$NON-NLS-1$
                return true;
        }
        return "Подписки на события".equals(base) //$NON-NLS-1$
            || "Event subscriptions".equals(base); //$NON-NLS-1$
    }

    private static boolean isDataExchangePage(IFormPage page, String base)
    {
        if (page != null)
        {
            String id = page.getId();
            if (id != null && id.toLowerCase(Locale.ROOT).contains("dataexchange")) //$NON-NLS-1$
                return true;
            if (page.getClass().getName().toLowerCase(Locale.ROOT).contains("dataexchange")) //$NON-NLS-1$
                return true;
        }
        return "Обмен данными".equals(base) //$NON-NLS-1$
            || "Data exchange".equals(base); //$NON-NLS-1$
    }

    private static boolean isFunctionalOptionsPage(IFormPage page, String base)
    {
        if (page != null)
        {
            String id = page.getId();
            if (id != null && id.toLowerCase(Locale.ROOT).contains("functionaloption")) //$NON-NLS-1$
                return true;
            if (page.getClass().getName().toLowerCase(Locale.ROOT).contains("functionaloption")) //$NON-NLS-1$
                return true;
        }
        return "Функциональные опции".equals(base) //$NON-NLS-1$
            || "Functional options".equals(base); //$NON-NLS-1$
    }

    private static boolean isSubsystemsPage(IFormPage page, String base)
    {
        if (page != null)
        {
            String id = page.getId();
            if (id != null && id.toLowerCase(Locale.ROOT).contains("subsystems")) //$NON-NLS-1$
                return true;
            if (page.getClass().getName().toLowerCase(Locale.ROOT).contains("subsystemspage")) //$NON-NLS-1$
                return true;
        }
        return "Подсистемы".equals(base) //$NON-NLS-1$
            || "Subsystems".equals(base); //$NON-NLS-1$
    }

    private static boolean isAdditionalIndexesPage(IFormPage page, String base)
    {
        if (page != null)
        {
            String id = page.getId();
            if (id != null && id.toLowerCase(Locale.ROOT).contains("aindex")) //$NON-NLS-1$
                return true;
            if (page.getClass().getName().contains("AdditionalIndexes")) //$NON-NLS-1$
                return true;
        }
        return "Дополнительные индексы".equals(base) //$NON-NLS-1$
            || "Additional indexes".equals(base); //$NON-NLS-1$
    }

    private static String shortenPredefinedBase(IFormPage page, String base)
    {
        if (base == null || !isPredefinedPage(page, base))
            return base;
        if (base.endsWith(" данные")) //$NON-NLS-1$
            return base.substring(0, base.length() - " данные".length()).strip(); //$NON-NLS-1$
        if (base.endsWith(" Data")) //$NON-NLS-1$
            return base.substring(0, base.length() - " Data".length()).strip(); //$NON-NLS-1$
        return base;
    }

    private static boolean isPredefinedPage(IFormPage page, String base)
    {
        if (page != null)
        {
            String id = page.getId();
            if (id != null && id.toLowerCase(Locale.ROOT).contains("predefined")) //$NON-NLS-1$
                return true;
            if (page.getClass().getName().contains("PredefinedData")) //$NON-NLS-1$
                return true;
        }
        return "Предопределенные данные".equals(base) //$NON-NLS-1$
            || "Predefined Data".equals(base); //$NON-NLS-1$
    }

    private static boolean isGenerationPage(IFormPage page)
    {
        if (page == null)
            return false;
        String id = page.getId();
        if (id != null && id.toLowerCase(Locale.ROOT).contains("generation")) //$NON-NLS-1$
            return true;
        return page.getClass().getName().contains("GenerationPage"); //$NON-NLS-1$
    }

    private static boolean isRightsPage(IFormPage page)
    {
        if (page == null)
            return false;
        if (isRightsPageId(page.getId()))
            return true;
        return page.getClass().getName().endsWith("RightsEditorRightsPage"); //$NON-NLS-1$
    }

    private static boolean isRightsPageId(String id)
    {
        return id != null && id.endsWith(".editors.page.rights"); //$NON-NLS-1$
    }

    /**
     * Верхние строки дерева объектов/ролей на вкладке «Права», без вложенных узлов.
     * Пока дерево ещё заполняется (асинхронно), возвращает 0.
     */
    private static Integer countRightsTopRows(IFormPage page)
    {
        Tree tree = rightsObjectsTree(page);
        if (tree == null || tree.isDisposed())
            return null;
        return Integer.valueOf(tree.getItemCount());
    }

    private static Tree rightsObjectsTree(IFormPage page)
    {
        if (page == null)
            return null;
        Object section = Global.getField(page, "objectsSection"); //$NON-NLS-1$
        Object viewerObj = section != null ? Global.getField(section, "viewer") : null; //$NON-NLS-1$
        if (viewerObj instanceof TreeViewer viewer)
        {
            Tree tree = viewer.getTree();
            if (tree != null && !tree.isDisposed())
                return tree;
        }
        return null;
    }

    private static String stripCountSuffix(String title)
    {
        Matcher matcher = COUNT_SUFFIX.matcher(title);
        return matcher.find() ? title.substring(0, matcher.start()) : title;
    }

    private static void applyTitle(CTabItem item, String baseTitle, String countToken)
    {
        if (item.isDisposed())
            return;
        String desired = countToken == null ? baseTitle : baseTitle + " " + countToken; //$NON-NLS-1$
        if (desired.equals(item.getText()))
            return;
        CTabFolder folder = item.getParent();
        item.setText(desired);
        Table nav = leftNavOf(folder);
        if (nav == null)
            return;
        int index = folder.indexOf(item);
        if (index >= 0 && index < nav.getItemCount())
            nav.getItem(index).setText(desired);
    }

    /**
     * Картинки на самих вкладках и, если есть, в левом списке.
     * Штатный значок ошибки/предупреждения не затираем; свою картинку
     * ставим, если вкладка пустая. Сброс EDT восстанавливаем.
     */
    private static void applyTabImages(DtGranularEditor<?> editor, CTabFolder folder)
    {
        if (folder == null || folder.isDisposed())
            return;
        if (!tabImagesNeedApply(editor, folder))
            return;
        folder.setRedraw(false);
        try
        {
            Object pagesObj = editor == null ? null : Global.getField(editor, "pages"); //$NON-NLS-1$
            List<?> pages = pagesObj instanceof List<?> list ? list : List.of();
            CTabItem[] items = folder.getItems();
            for (int i = 0; i < items.length; i++)
            {
                CTabItem item = items[i];
                if (item == null || item.isDisposed())
                    continue;
                Object pageObj = i < pages.size() ? pages.get(i) : null;
                IFormPage page = pageObj instanceof IFormPage formPage ? formPage : null;
                applyTabImage(editor, item, page);
            }
        }
        finally
        {
            folder.setRedraw(true);
        }
    }

    private static boolean tabImagesNeedApply(DtGranularEditor<?> editor, CTabFolder folder)
    {
        Object pagesObj = editor == null ? null : Global.getField(editor, "pages"); //$NON-NLS-1$
        List<?> pages = pagesObj instanceof List<?> list ? list : List.of();
        CTabItem[] items = folder.getItems();
        Table nav = leftNavOf(folder);
        boolean navOk = nav != null && !nav.isDisposed();
        for (int i = 0; i < items.length; i++)
        {
            CTabItem item = items[i];
            if (item == null || item.isDisposed())
                continue;
            Object pageObj = i < pages.size() ? pages.get(i) : null;
            IFormPage page = pageObj instanceof IFormPage formPage ? formPage : null;
            Image ours = resolveTabImage(editor, page);
            Image comfort = usableImage(ours) ? ours : dataImage(item, KEY_COMFORT_IMAGE);
            Image current = usableImage(item.getImage()) ? item.getImage() : null;
            if (current == null && usableImage(comfort))
                return true;
            if (navOk && i < nav.getItemCount())
            {
                Image shown = current != null ? current : comfort;
                if (nav.getItem(i).getImage() != shown)
                    return true;
            }
        }
        return false;
    }

    private static void applyTabImage(DtGranularEditor<?> editor, CTabItem item, IFormPage page)
    {
        if (item == null || item.isDisposed())
            return;
        Image ours = resolveTabImage(editor, page);
        if (usableImage(ours))
            item.setData(KEY_COMFORT_IMAGE, ours);
        Image comfort = dataImage(item, KEY_COMFORT_IMAGE);
        Image current = usableImage(item.getImage()) ? item.getImage() : null;
        if (current == null && comfort != null)
            item.setImage(comfort);

        CTabFolder folder = item.getParent();
        Table nav = leftNavOf(folder);
        if (nav == null || nav.isDisposed())
            return;
        int index = folder.indexOf(item);
        if (index < 0 || index >= nav.getItemCount())
            return;
        TableItem row = nav.getItem(index);
        Image shown = usableImage(item.getImage()) ? item.getImage() : comfort;
        if (row.getImage() != shown)
            row.setImage(shown);
    }

    private static Image dataImage(CTabItem item, String key)
    {
        Object stored = item.getData(key);
        return stored instanceof Image image && usableImage(image) ? image : null;
    }

    private static boolean tabImageNeedsRestore(CTabFolder folder)
    {
        if (folder == null || folder.isDisposed())
            return false;
        for (CTabItem item : folder.getItems())
        {
            if (item == null || item.isDisposed())
                continue;
            if (!usableImage(item.getImage()) && dataImage(item, KEY_COMFORT_IMAGE) != null)
                return true;
        }
        return false;
    }

    private static Image resolveTabImage(DtGranularEditor<?> editor, IFormPage page)
    {
        if (page != null)
        {
            Image title = page.getTitleImage();
            if (usableImage(title))
                return title;
        }
        Image fromFeature = imageFromPageFeatures(editor, page);
        if (fromFeature != null)
            return fromFeature;
        return imageFromPageKind(editor, page);
    }

    private static Image imageFromPageFeatures(DtGranularEditor<?> editor, IFormPage page)
    {
        if (page == null)
            return null;
        Image image = imageFromFeature(Global.invoke(page, "getDefaultFeature")); //$NON-NLS-1$
        if (image != null)
            return image;
        if (editor == null)
            return null;
        for (Object feature : featuresMappedToPage(editor, page))
        {
            image = imageFromFeature(feature);
            if (image != null)
                return image;
        }
        return null;
    }

    private static Image imageFromFeature(Object featureObj)
    {
        if (!(featureObj instanceof EReference reference))
            return null;
        EClass type = reference.getEReferenceType();
        if (type == null)
            return null;
        String name = type.getName();
        if ("MdObject".equals(name) || "BasicMdObject".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        try
        {
            return MdUiSharedImages.getMdClassImage(type);
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
    }

    private static Image imageFromPageKind(DtGranularEditor<?> editor, IFormPage page)
    {
        if (isModulePage(page))
            return BslSharedImages.getImage(BslSharedImages.IMG_MODULE);
        if (isMdEventHandlersPage(page, null))
            return mdImage(MdUiSharedImages.OBJS_EVENT_SUBSCRIPTION);
        if (isDataExchangePage(page, null))
            return mdImage(MdUiSharedImages.OBJS_EXCHANGE_PLAN);
        if (isFunctionalOptionsPage(page, null))
            return mdImage(MdUiSharedImages.OBJS_FUNCTIONAL_OPTION);
        if (isPredefinedPage(page, null))
            return mdImage(MdUiSharedImages.OBJS_PREDEFINED_ELEMENT);
        if (isAdditionalIndexesPage(page, null))
            return mdImage(MdUiSharedImages.OBJS_TABLE);
        if (isGenerationPage(page))
            return mdImage(MdUiSharedImages.OBJS_DOCUMENT);

        String id = page != null ? page.getId() : null;
        String lower = id == null ? "" : id.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        if (lower.contains("subsystem")) //$NON-NLS-1$
            return mdImage(MdUiSharedImages.OBJS_SUBSYSTEM);
        if (lower.contains("characteristic")) //$NON-NLS-1$
            return mdImage(MdUiSharedImages.OBJS_CHARACTERISTICS);
        if (lower.contains("right")) //$NON-NLS-1$
            return mdImage(MdUiSharedImages.OBJS_ROLE);
        if (lower.contains(".dcs") || lower.contains("diagram")) //$NON-NLS-1$ //$NON-NLS-2$
            return mdImage(MdUiSharedImages.OBJS_DATA_COMPOSITION_SCHEMA);
        if (lower.contains("owner")) //$NON-NLS-1$
            return mdImage(MdUiSharedImages.OBJS_CATALOG);
        if (lower.endsWith(".main") || lower.contains(".main")) //$NON-NLS-1$ //$NON-NLS-2$
            return modelClassImage(editor);
        return modelClassImage(editor);
    }

    private static Image modelClassImage(DtGranularEditor<?> editor)
    {
        if (editor == null)
            return null;
        EObject model = editor.getModel();
        if (model == null || model.eIsProxy())
            return null;
        try
        {
            return MdUiSharedImages.getMdClassImage(model.eClass());
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
    }

    private static Image mdImage(String key)
    {
        if (key == null)
            return null;
        try
        {
            return MdUiSharedImages.getImage(key);
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
    }

    private static boolean usableImage(Image image)
    {
        return image != null && !image.isDisposed();
    }

    // =========================================================================
    // Вкладки модулей: (+) / (-)
    // =========================================================================

    private static boolean isModulePage(IFormPage page)
    {
        if (page == null)
            return false;
        if (page instanceof DtGranularEditorXtextEditorPage)
            return true;
        String id = page.getId();
        if (id == null)
            return false;
        int dot = id.lastIndexOf('.');
        String last = (dot >= 0 ? id.substring(dot + 1) : id).toLowerCase(Locale.ROOT);
        return last.contains("module"); //$NON-NLS-1$
    }

    /**
     * «+» если файл модуля есть и его размер больше нуля, «-» иначе.
     * Размер читается с диска, без загрузки текста модуля.
     */
    private static String modulePresenceMark(DtGranularEditor<?> editor, IFormPage page)
    {
        List<IContainer> folders = moduleFolders(editor, page);
        if (folders.isEmpty())
            return null;

        List<String> fileNames = moduleBslFileNames(editor, page);
        if (fileNames.isEmpty())
            return null;
        try
        {
            for (IContainer folder : folders)
            {
                for (String fileName : fileNames)
                {
                    if (moduleFileHasContent(folder.getFile(new Path(fileName))))
                        return "+"; //$NON-NLS-1$
                }
            }
            return "-"; //$NON-NLS-1$
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
    }

    /**
     * Папки, где может лежать файл модуля этой вкладки.
     *
     * <p>Для редактора объекта метаданных это папка его {@code .mdo} — там же лежат
     * {@code ObjectModule.bsl} и прочие модули объекта. Для редактора формы модель редактора —
     * подчинённый объект {@code BasicForm}, который хранится не в своём файле, а внутри
     * {@code .mdo} владельца (см. {@code <forms>} в {@code Справочник1.mdo}), поэтому его папка —
     * папка справочника, а не формы, и {@code Module.bsl} там не найти. Реальная папка формы
     * берётся от модели самой страницы ({@code form.model.Form} — файл {@code Form.form}).
     */
    private static List<IContainer> moduleFolders(DtGranularEditor<?> editor, IFormPage page)
    {
        List<IContainer> folders = new ArrayList<>();
        addModuleFolder(folders, pageModel(page));
        addModuleFolder(folders, editor.getModel());
        return folders;
    }

    private static void addModuleFolder(List<IContainer> folders, EObject model)
    {
        if (model == null || model.eIsProxy())
            return;
        IContainer folder = mdFolder(model);
        if (folder != null && !folders.contains(folder))
            folders.add(folder);
    }

    private static EObject pageModel(IFormPage page)
    {
        if (page == null)
            return null;
        try
        {
            Object model = Global.invoke(page, "getModel"); //$NON-NLS-1$
            return model instanceof EObject eObject ? eObject : null;
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
    }

    private static boolean moduleFileHasContent(IFile file)
    {
        if (file == null || !file.exists())
            return false;
        IPath location = file.getLocation();
        if (location == null)
            return false;
        return location.toFile().length() > 0;
    }

    private static IContainer mdFolder(EObject model)
    {
        IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
        if (lookup == null)
            return null;
        try
        {
            IFile file = lookup.getPlatformResource(model);
            if (file != null)
                return file.getParent();
            if (model.eResource() != null)
            {
                file = lookup.getPlatformResource(model.eResource());
                if (file != null)
                    return file.getParent();
            }
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
        return null;
    }

    private static List<String> moduleBslFileNames(DtGranularEditor<?> editor, IFormPage page)
    {
        List<String> names = new ArrayList<>();
        for (Object featureObj : featuresMappedToPage(editor, page))
        {
            if (featureObj instanceof EStructuralFeature feature)
                addBslFileName(names, feature.getName());
        }
        if (names.isEmpty())
        {
            try
            {
                Object feature = Global.invoke(page, "getDefaultFeature"); //$NON-NLS-1$
                if (feature instanceof EStructuralFeature structural)
                    addBslFileName(names, structural.getName());
            }
            catch (RuntimeException ignored)
            {
                // нет default feature
            }
        }
        if (names.isEmpty())
            addBslFileNameFromPageId(names, page.getId());
        return names;
    }

    private static void addBslFileNameFromPageId(List<String> names, String pageId)
    {
        if (pageId == null || pageId.isBlank())
            return;
        String lower = pageId.toLowerCase(Locale.ROOT);
        if (lower.endsWith("pages.module") && !lower.contains("objectmodule")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            names.add("ManagerModule.bsl"); //$NON-NLS-1$
            return;
        }
        int dot = pageId.lastIndexOf('.');
        String last = dot >= 0 ? pageId.substring(dot + 1) : pageId;
        addBslFileName(names, last);
    }

    private static void addBslFileName(List<String> names, String featureName)
    {
        if (featureName == null || featureName.isEmpty())
            return;
        String lower = featureName.toLowerCase(Locale.ROOT);
        if (!"module".equals(lower) && !lower.endsWith("module")) //$NON-NLS-1$ //$NON-NLS-2$
            return;
        String fileName = Character.toUpperCase(featureName.charAt(0)) + featureName.substring(1) + ".bsl"; //$NON-NLS-1$
        if (!names.contains(fileName))
            names.add(fileName);
    }

    // =========================================================================
    // Какие вкладки умеют считать строки
    // =========================================================================

    private static boolean looksLikeListPage(IFormPage page)
    {
        if (page == null)
            return false;
        if (page instanceof DtGranularEditorXtextEditorPage
            || page instanceof DtGranularEditorEmbeddedEditorPage)
            return false;
        if (page.getClass().getName().endsWith("DtGranularEditorProgressPage")) //$NON-NLS-1$
            return false;

        String id = page.getId();
        if (id != null)
        {
            String lower = id.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".main") //$NON-NLS-1$
                || lower.contains("module") //$NON-NLS-1$
                || lower.contains(".dcs") //$NON-NLS-1$
                || lower.contains("diagram") //$NON-NLS-1$
                || lower.contains("loading") //$NON-NLS-1$
                || lower.contains("progress")) //$NON-NLS-1$
                return false;
            if (lower.contains(".aindex")) //$NON-NLS-1$
                return true;
            if ("tormozit.mdEventHandlers".equals(id)) //$NON-NLS-1$
                return true;
            if (isRightsPageId(id))
                return true;
        }

        Object feature = Global.invoke(page, "getDefaultFeature"); //$NON-NLS-1$
        if (feature instanceof EStructuralFeature structural && structural.isMany())
            return true;

        if (page instanceof AbstractDtGranularEditorAefPage)
            return true;

        String className = page.getClass().getName();
        return className.contains("EventHandlers") //$NON-NLS-1$
            || className.contains("AdditionalIndexes") //$NON-NLS-1$
            || className.endsWith("RightsEditorRightsPage"); //$NON-NLS-1$
    }

    /**
     * Сумма размеров many-ссылок страницы, применимых к объекту.
     * Без обхода элементов. Стандартные реквизиты не входят.
     * Доп. индексы — только если страница уже создана (при открытии редактора не считаем).
     */
    private static Integer countFromModel(DtGranularEditor<?> editor, IFormPage page,
        boolean pageCreated)
    {
        if (page == null || modelCountUnsafe(page))
            return null;
        EObject model = editor.getModel();
        if (model == null || model.eIsProxy())
            return null;
        if (MdClassPackage.Literals.CONFIGURATION.equals(model.eClass()))
            return null;

        if (isAdditionalIndexesPage(page, null))
        {
            if (!pageCreated)
                return null;
            return countHolderListByName(model, "additionalIndexes", "indexes"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (isPredefinedPage(page, null))
            return countHolderListByName(model, "predefined", "items"); //$NON-NLS-1$ //$NON-NLS-2$

        List<EReference> references = countableReferences(editor, page, model);
        if (references.isEmpty())
            return null;
        int sum = 0;
        try
        {
            for (EReference reference : references)
            {
                Object value = model.eGet(reference, false);
                if (!(value instanceof Collection<?> collection))
                    return null;
                sum += collection.size();
            }
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
        return Integer.valueOf(sum);
    }

    /**
     * Список внутри одиночного объекта ({@code additionalIndexes.indexes},
     * {@code predefined.items}). Признак — имя фичи на {@code eClass()} модели,
     * не карта страницы и не виджеты. Нет фичи или нет holder — 0.
     */
    private static Integer countHolderListByName(EObject model, String holderName, String listName)
    {
        EStructuralFeature holderFeat = model.eClass().getEStructuralFeature(holderName);
        if (!(holderFeat instanceof EReference holderRef) || holderRef.isMany())
            return Integer.valueOf(0);
        try
        {
            Object value = model.eGet(holderRef, true);
            if (value == null)
                return Integer.valueOf(0);
            if (value instanceof Predefined predefined && "items".equals(listName)) //$NON-NLS-1$
                return Integer.valueOf(predefined.predefinedItems().size());
            if (!(value instanceof EObject holder))
                return Integer.valueOf(0);
            EStructuralFeature items = holder.eClass().getEStructuralFeature(listName);
            if (!(items instanceof EReference itemsRef) || !itemsRef.isMany())
                return Integer.valueOf(0);
            Object list = holder.eGet(itemsRef, false);
            if (!(list instanceof Collection<?> collection))
                return Integer.valueOf(0);
            return Integer.valueOf(collection.size());
        }
        catch (RuntimeException ignored)
        {
            return Integer.valueOf(0);
        }
    }

    private static boolean countsFromModelOnly(IFormPage page)
    {
        return isAdditionalIndexesPage(page, null) || isPredefinedPage(page, null);
    }

    private static List<EReference> countableReferences(DtGranularEditor<?> editor, IFormPage page,
        EObject model)
    {
        List<EReference> result = new ArrayList<>();
        collectCountable(result, featuresMappedToPage(editor, page), model);
        if (result.isEmpty())
        {
            try
            {
                Object features = Global.invoke(page, "getPageFeatures"); //$NON-NLS-1$
                if (features instanceof Collection<?> collection)
                    collectCountable(result, collection, model);
            }
            catch (RuntimeException ignored)
            {
                // страница ещё без definition
            }
        }
        if (result.isEmpty())
        {
            try
            {
                Object feature = Global.invoke(page, "getDefaultFeature"); //$NON-NLS-1$
                if (feature != null)
                    collectCountable(result, List.of(feature), model);
            }
            catch (RuntimeException ignored)
            {
                return result;
            }
        }
        return result;
    }

    private static Collection<?> featuresMappedToPage(DtGranularEditor<?> editor, IFormPage page)
    {
        Object mapObj = Global.getField(editor, "featureToPageMap"); //$NON-NLS-1$
        if (!(mapObj instanceof Map<?, ?> map) || map.isEmpty())
            return List.of();
        List<Object> features = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            if (entry.getValue() == page)
                features.add(entry.getKey());
        }
        return features;
    }

    private static void collectCountable(List<EReference> result, Collection<?> features, EObject model)
    {
        if (features == null)
            return;
        for (Object featureObj : features)
        {
            if (!(featureObj instanceof EReference reference)
                || !reference.isMany()
                || result.contains(reference)
                || !countableOnModel(model, reference))
                continue;
            result.add(reference);
        }
    }

    private static boolean countableOnModel(EObject model, EReference reference)
    {
        if ("standardAttributes".equals(reference.getName())) //$NON-NLS-1$
            return false;
        return reference.getEContainingClass().isSuperTypeOf(model.eClass());
    }

    private static boolean modelCountUnsafe(IFormPage page)
    {
        String id = page.getId();
        String className = page.getClass().getName();
        String lower = ((id != null ? id : "") + " " + className).toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
        return lower.contains("subsystem") //$NON-NLS-1$
            || lower.contains("content") //$NON-NLS-1$
            || lower.contains("recorder") //$NON-NLS-1$
            || lower.contains("event") //$NON-NLS-1$
            || lower.contains("right") //$NON-NLS-1$
            || lower.contains("functionaloption") //$NON-NLS-1$
            || lower.contains("commonattribute") //$NON-NLS-1$
            || lower.contains("generation") //$NON-NLS-1$
            || lower.contains("dataexchange") //$NON-NLS-1$
            || lower.contains("standalone"); //$NON-NLS-1$
    }

    /**
     * Число подсистем, в состав которых входит объект — как пометки в дереве.
     * Не {@code MdObject.getSubsystems()}: это обход дерева конфигурации,
     * поэтому только после активации вкладки.
     */
    private static Integer countMarkedSubsystems(DtGranularEditor<?> editor)
    {
        EObject model = editor != null ? editor.getModel() : null;
        if (!(model instanceof MdObject mdObject) || mdObject.eIsProxy())
            return null;
        Configuration configuration = configurationOf(model);
        if (configuration == null)
            return null;
        int[] count = { 0 };
        walkMarkedSubsystems(configuration.getSubsystems(), mdObject, count, 0);
        Global.tempLog("subsys-count", mdObject.getName() + " marked=" + count[0]); //$NON-NLS-1$ //$NON-NLS-2$
        return Integer.valueOf(count[0]);
    }

    private static Configuration configurationOf(EObject model)
    {
        for (EObject current = model; current != null; current = current.eContainer())
        {
            if (current instanceof Configuration configuration)
                return configuration;
        }
        if (!(model instanceof MdObject mdObject))
            return null;
        IV8ProjectManager projectManager =
            (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
        if (projectManager == null)
            return null;
        IV8Project project = projectManager.getProject(mdObject);
        if (project instanceof IConfigurationProject configurationProject)
            return configurationProject.getConfiguration();
        Object configuration = Global.invoke(project, "getConfiguration"); //$NON-NLS-1$
        return configuration instanceof Configuration conf ? conf : null;
    }

    private static void walkMarkedSubsystems(List<? extends Subsystem> subsystems, MdObject mdObject,
        int[] count, int depth)
    {
        if (subsystems == null || depth > 24)
            return;
        for (Subsystem subsystem : subsystems)
        {
            if (subsystem == null)
                continue;
            try
            {
                if (subsystem.getContent().contains(mdObject))
                    count[0]++;
            }
            catch (RuntimeException ignored)
            {
            }
            walkMarkedSubsystems(subsystem.getSubsystems(), mdObject, count, depth + 1);
        }
    }

    // =========================================================================
    // Подсчёт строк списка
    // =========================================================================

    private static Integer countRows(Control root, IFormPage page)
    {
        Control list = findPrimaryList(root, page);
        if (list == null)
            return null;
        if (list instanceof Table table)
            return Integer.valueOf(countTableRows(table, page));
        if (list instanceof Tree tree)
            return Integer.valueOf(countTreeRows(tree));
        return null;
    }

    private static Control findPrimaryList(Control root, IFormPage page)
    {
        List<Control> lists = new ArrayList<>();
        collectLists(root, lists);
        if (lists.isEmpty())
            return null;
        if (isAdditionalIndexesPage(page, null))
        {
            for (Control candidate : lists)
            {
                if (candidate instanceof Table)
                    return candidate;
            }
            return null;
        }
        Control best = lists.get(0);
        long bestArea = areaOf(best);
        for (int i = 1; i < lists.size(); i++)
        {
            Control candidate = lists.get(i);
            long area = areaOf(candidate);
            if (area > bestArea)
            {
                best = candidate;
                bestArea = area;
            }
        }
        return best;
    }

    private static void collectLists(Control control, List<Control> lists)
    {
        if (control == null || control.isDisposed())
            return;
        if (control instanceof Table || control instanceof Tree)
        {
            lists.add(control);
            return;
        }
        if (control instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
                collectLists(child, lists);
        }
    }

    private static long areaOf(Control control)
    {
        Rectangle bounds = control.getBounds();
        return (long)Math.max(0, bounds.width) * Math.max(0, bounds.height);
    }

    /**
     * Таблица с пометками — число включённых флажков, иначе число строк.
     * «Обмен данными» всегда с флажками (не {@code SWT.CHECK}, а {@code getCheckState}).
     */
    private static int countTableRows(Table table, IFormPage page)
    {
        TableItem[] items = table.getItems();
        boolean dataExchange = isDataExchangePage(page, null);
        if (dataExchange || hasTableCheckUi(table, items))
            return countCheckedTableItems(table, items);
        return table.getItemCount();
    }

    private static boolean hasTableCheckUi(Table table, TableItem[] items)
    {
        if ((table.getStyle() & SWT.CHECK) != 0)
            return true;
        for (TableItem item : items)
        {
            if (checkKind(item.getData()) != CheckKind.NONE)
                return true;
        }
        return false;
    }

    private static int countCheckedTableItems(Table table, TableItem[] items)
    {
        boolean swtCheck = (table.getStyle() & SWT.CHECK) != 0;
        int n = 0;
        for (TableItem item : items)
        {
            CheckKind kind = checkKind(item.getData());
            if (kind == CheckKind.CHECKED)
                n++;
            else if (kind == CheckKind.NONE && swtCheck && item.getChecked() && !item.getGrayed())
                n++;
        }
        return n;
    }

    private static int countTreeRows(Tree tree)
    {
        TreeViewer viewer = findTreeViewer(tree);
        if (viewer != null)
        {
            Integer checked = countCheckedInViewer(viewer);
            if (checked != null)
                return checked.intValue();
        }
        Integer itemChecked = countCheckedInTreeItems(tree.getItems());
        if (itemChecked != null)
            return itemChecked.intValue();
        if ((tree.getStyle() & SWT.CHECK) != 0)
            return countSwtChecked(tree.getItems());
        return tree.getItemCount();
    }

    /**
     * Число помеченных узлов. Пометки часто только у вложенных строк
     * (вкладка «Подсистемы»: у корней {@code NO_CHECK}), поэтому обходим всё дерево.
     */
    private static Integer countCheckedInViewer(TreeViewer viewer)
    {
        Object provider = viewer.getContentProvider();
        if (!(provider instanceof ITreeContentProvider content))
            return null;
        Object input = viewer.getInput();
        Object[] roots;
        try
        {
            roots = content.getElements(input);
        }
        catch (RuntimeException e)
        {
            return null;
        }
        if (roots == null)
            return null;

        int[] acc = { 0, 0 }; // [0] помеченные, [1] есть ли пометки в дереве
        walkChecked(content, roots, acc, 0);
        if (acc[1] == 0)
            return null;
        return Integer.valueOf(acc[0]);
    }

    private static void walkChecked(ITreeContentProvider content, Object[] elements, int[] acc, int depth)
    {
        if (elements == null || depth > 24)
            return;
        for (Object element : elements)
        {
            CheckKind kind = checkKind(element);
            if (kind != CheckKind.NONE)
                acc[1] = 1;
            if (kind == CheckKind.CHECKED)
                acc[0]++;
            Object[] children;
            try
            {
                children = content.getChildren(element);
            }
            catch (RuntimeException e)
            {
                continue;
            }
            walkChecked(content, children, acc, depth + 1);
        }
    }

    private static Integer countCheckedInTreeItems(TreeItem[] items)
    {
        if (items == null || items.length == 0)
            return null;
        int[] acc = { 0, 0 };
        walkCheckedTreeItems(items, acc, 0);
        return acc[1] == 0 ? null : Integer.valueOf(acc[0]);
    }

    private static void walkCheckedTreeItems(TreeItem[] items, int[] acc, int depth)
    {
        if (items == null || depth > 24)
            return;
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            CheckKind kind = checkKind(item.getData());
            if (kind != CheckKind.NONE)
                acc[1] = 1;
            if (kind == CheckKind.CHECKED)
                acc[0]++;
            walkCheckedTreeItems(item.getItems(), acc, depth + 1);
        }
    }

    private enum CheckKind
    {
        NONE, CHECKED, OTHER
    }

    private static CheckKind checkKind(Object element)
    {
        Object state = Global.invoke(element, "getCheckState"); //$NON-NLS-1$
        if (state == null)
            return CheckKind.NONE;
        String name = state instanceof Enum<?> en ? en.name() : String.valueOf(state);
        if ("NO_CHECK".equals(name)) //$NON-NLS-1$
            return CheckKind.NONE;
        if ("CHECKED".equals(name)) //$NON-NLS-1$
            return CheckKind.CHECKED;
        return CheckKind.OTHER;
    }

    private static int countSwtChecked(TreeItem[] items)
    {
        int n = 0;
        for (TreeItem item : items)
        {
            if (item.getChecked() && !item.getGrayed())
                n++;
            n += countSwtChecked(item.getItems());
        }
        return n;
    }

    private static TreeViewer findTreeViewer(Control start)
    {
        String key = dtTreeViewerKey();
        Control current = start;
        while (current != null && !current.isDisposed())
        {
            if (key != null && current instanceof Composite composite)
            {
                Object data = composite.getData(key);
                if (data instanceof TreeViewer viewer)
                    return viewer;
            }
            current = current.getParent();
        }
        return null;
    }

    private static String dtTreeViewerKey()
    {
        try
        {
            Class<?> type = Class.forName(DT_TREE_VIEW_CLASS);
            java.lang.reflect.Field field = type.getDeclaredField("TREE_VIEWER_KEY"); //$NON-NLS-1$
            field.setAccessible(true);
            Object key = field.get(null);
            return key instanceof String s ? s : null;
        }
        catch (Exception ignored)
        {
            return DT_TREE_VIEW_CLASS + ".treeViewer"; //$NON-NLS-1$
        }
    }

    // =========================================================================
    // Слежение за изменением списка
    // =========================================================================

    private static void watchRightsTree(DtGranularEditor<?> editor, CTabItem item, IFormPage page)
    {
        Tree tree = rightsObjectsTree(page);
        if (tree == null || tree.isDisposed() || tree.getData(KEY_WATCHED) != null)
            return;
        tree.setData(KEY_WATCHED, Boolean.TRUE);
        tree.addListener(SWT.Paint, event ->
        {
            if (item.isDisposed() || tree.isDisposed() || tree.getItemCount() <= 0)
                return;
            refreshTab(editor, item, page, RIGHTS_COUNT_MAX_ATTEMPTS);
        });
    }

    private static void watchList(DtGranularEditor<?> editor, CTabItem item, IFormPage page, Control root)
    {
        Control list = findPrimaryList(root, page);
        if (list == null || list.isDisposed() || list.getData(KEY_WATCHED) != null)
            return;
        list.setData(KEY_WATCHED, Boolean.TRUE);
        list.addListener(SWT.Paint, event ->
        {
            if (item.isDisposed() || list.isDisposed())
                return;
            refreshTab(editor, item, page, COUNT_MAX_ATTEMPTS);
        });
        list.addListener(SWT.Selection, event ->
        {
            if (item.isDisposed())
                return;
            refreshTab(editor, item, page, COUNT_MAX_ATTEMPTS);
        });
    }
}
