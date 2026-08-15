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
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
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

import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.md.ui.editor.aef.AbstractDtGranularEditorAefPage;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorEmbeddedEditorPage;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

/**
 * В заголовки вкладок-списков редактора объекта метаданных добавляет число строк
 * списка: сначала «Подсистемы (?)», затем рассчитанное число.
 *
 * <p>Обычные коллекции объекта считаются по модели сразу (сумма many-ссылок страницы,
 * объявленных на классе объекта: реквизиты+табличные части, формы, команды, макеты).
 * Это же число остаётся после открытия вкладки. Деревья с пометками — после активации.
 * Вкладки модулей: «(+)» если файл модуля не пустой, «(-)» если файла нет или размер 0.
 */
public final class MdEditorListTabCountHook implements IStartup
{
    private static final String TAG = "MdEditorListTabCountHook"; //$NON-NLS-1$

    private static final String KEY_FOLDER = "tormozit.mdListTabCount.folder"; //$NON-NLS-1$

    private static final String KEY_WATCHED = "tormozit.mdListTabCount.watched"; //$NON-NLS-1$

    private static final String KEY_RETRYING = "tormozit.mdListTabCount.retrying"; //$NON-NLS-1$

    /** Суффикс « (N)» / « (?)» / « (+)» / « (-)» в заголовке вкладки. */
    private static final Pattern COUNT_SUFFIX = Pattern.compile(" \\((\\?|\\+|\\-|\\d+)\\)$"); //$NON-NLS-1$

    private static final int HOOK_RETRY_DELAY_MS = 200;

    private static final int HOOK_MAX_ATTEMPTS = 150;

    private static final int COUNT_RETRY_DELAY_MS = 150;

    private static final int COUNT_MAX_ATTEMPTS = 20;

    private static final String DT_TREE_VIEW_CLASS =
        "com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView"; //$NON-NLS-1$

    private final Set<DtGranularEditor<?>> hookedEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    private final Set<DtGranularEditor<?>> pendingRetryEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

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
        });
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
            @Override public void partOpened(IWorkbenchPartReference ref)      { hookFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref)   { hookFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r)  { hookFromRef(r); }
            @Override public void partClosed(IWorkbenchPartReference r)        {}
            @Override public void partDeactivated(IWorkbenchPartReference r)   {}
            @Override public void partHidden(IWorkbenchPartReference r)        {}
            @Override public void partVisible(IWorkbenchPartReference r)       {}
            @Override public void partInputChanged(IWorkbenchPartReference r)  { hookFromRef(r); }

            private void hookFromRef(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference editorRef))
                    return;
                IWorkbenchPart part = editorRef.getPart(false);
                if (part instanceof DtGranularEditor<?> granular)
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

            if (hookedEditors.add(editor))
            {
                editor.addPageChangedListener(event ->
                {
                    refreshEditor(editor);
                    Display.getDefault().timerExec(200, () -> refreshEditor(editor));
                });
                installFolderWatch(editor);
                Display.getDefault().timerExec(300, () -> refreshEditor(editor));
            }
            refreshEditor(editor);
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

    private static void installFolderWatch(DtGranularEditor<?> editor)
    {
        Object container = Global.invoke(editor, "getContainer"); //$NON-NLS-1$
        if (!(container instanceof CTabFolder folder) || folder.isDisposed())
            return;
        if (folder.getData(KEY_FOLDER) != null)
            return;
        folder.setData(KEY_FOLDER, Boolean.TRUE);
        int[] lastCount = { folder.getItemCount() };
        folder.addListener(SWT.Paint, event ->
        {
            if (folder.isDisposed())
                return;
            int n = folder.getItemCount();
            if (n == lastCount[0])
                return;
            lastCount[0] = n;
            refreshEditor(editor);
        });
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

            Object pagesObj = Global.getField(editor, "pages"); //$NON-NLS-1$
            if (!(pagesObj instanceof List<?> pages))
                return;
            CTabItem[] items = folder.getItems();
            int n = Math.min(pages.size(), items.length);
            for (int i = 0; i < n; i++)
            {
                CTabItem item = items[i];
                if (item == null || item.isDisposed())
                    continue;
                Object pageObj = pages.get(i);
                IFormPage page = pageObj instanceof IFormPage formPage ? formPage : null;
                refreshTab(editor, item, page, 0);
            }
        }
        catch (Exception e)
        {
            Global.logError(TAG, "refresh editor", e); //$NON-NLS-1$
        }
    }

    private static void refreshTab(DtGranularEditor<?> editor, CTabItem item, IFormPage page, int attempt)
    {
        if (item == null || item.isDisposed())
            return;

        String baseTitle = baseTitle(item, page);
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

        Integer modelRows = countFromModel(editor, page);
        if (modelRows != null)
        {
            applyTitle(item, baseTitle, Integer.toString(modelRows.intValue()));
            if (created)
                watchList(editor, item, page, partControl);
            return;
        }

        Integer uiRows = created ? countRows(partControl) : null;
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

    private static String baseTitle(CTabItem item, IFormPage page)
    {
        if (page != null)
        {
            String title = page.getTitle();
            if (title != null && !title.isBlank())
                return stripCountSuffix(title);
        }
        String text = item.getText();
        return text == null ? null : stripCountSuffix(text);
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
        String desired = countToken == null ? baseTitle : baseTitle + " (" + countToken + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        if (!desired.equals(item.getText()))
            item.setText(desired);
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
        EObject model = editor.getModel();
        if (model == null || model.eIsProxy())
            return null;
        IContainer folder = mdFolder(model);
        if (folder == null)
            return null;

        List<String> fileNames = moduleBslFileNames(editor, page);
        if (fileNames.isEmpty())
            return null;
        try
        {
            for (String fileName : fileNames)
            {
                if (moduleFileHasContent(folder.getFile(new Path(fileName))))
                    return "+"; //$NON-NLS-1$
            }
            return "-"; //$NON-NLS-1$
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
        }

        Object feature = Global.invoke(page, "getDefaultFeature"); //$NON-NLS-1$
        if (feature instanceof EStructuralFeature structural && structural.isMany())
            return true;

        if (page instanceof AbstractDtGranularEditorAefPage)
            return true;

        String className = page.getClass().getName();
        return className.contains("EventHandlers"); //$NON-NLS-1$
    }

    /**
     * Сумма размеров many-ссылок страницы, объявленных на классе объекта.
     * Без обхода элементов и без резолва прокси. Наследованные фичи
     * (стандартные реквизиты и т.п.) не входят.
     */
    private static Integer countFromModel(DtGranularEditor<?> editor, IFormPage page)
    {
        if (page == null || modelCountUnsafe(page))
            return null;
        EObject model = editor.getModel();
        if (model == null || model.eIsProxy())
            return null;
        if (MdClassPackage.Literals.CONFIGURATION.equals(model.eClass()))
            return null;

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
                || !declaredOnClass(model, reference))
                continue;
            result.add(reference);
        }
    }

    private static boolean declaredOnClass(EObject model, EStructuralFeature feature)
    {
        for (EStructuralFeature declared : model.eClass().getEStructuralFeatures())
        {
            if (declared == feature || declared.getName().equals(feature.getName()))
                return true;
        }
        return false;
    }

    private static boolean modelCountUnsafe(IFormPage page)
    {
        String id = page.getId();
        String className = page.getClass().getName();
        String lower = ((id != null ? id : "") + " " + className).toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
        return lower.contains("subsystem") //$NON-NLS-1$
            || lower.contains("content") //$NON-NLS-1$
            || lower.contains("predefined") //$NON-NLS-1$
            || lower.contains("recorder") //$NON-NLS-1$
            || lower.contains("event") //$NON-NLS-1$
            || lower.contains("right") //$NON-NLS-1$
            || lower.contains("functionaloption") //$NON-NLS-1$
            || lower.contains("commonattribute") //$NON-NLS-1$
            || lower.contains("generation") //$NON-NLS-1$
            || lower.contains("dataexchange") //$NON-NLS-1$
            || lower.contains("standalone") //$NON-NLS-1$
            || lower.contains("characteristic"); //$NON-NLS-1$
    }

    // =========================================================================
    // Подсчёт строк списка
    // =========================================================================

    private static Integer countRows(Control root)
    {
        Control list = findPrimaryList(root);
        if (list == null)
            return null;
        if (list instanceof Table table)
            return Integer.valueOf(table.getItemCount());
        if (list instanceof Tree tree)
            return Integer.valueOf(countTreeRows(tree));
        return null;
    }

    private static Control findPrimaryList(Control root)
    {
        List<Control> lists = new ArrayList<>();
        collectLists(root, lists);
        if (lists.isEmpty())
            return null;
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

    private static int countTreeRows(Tree tree)
    {
        TreeViewer viewer = findTreeViewer(tree);
        if (viewer != null)
        {
            Integer checked = countCheckedInViewer(viewer);
            if (checked != null)
                return checked.intValue();
        }
        if ((tree.getStyle() & SWT.CHECK) != 0)
            return countSwtChecked(tree.getItems());
        return tree.getItemCount();
    }

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

        boolean hasCheckUi = false;
        for (Object root : roots)
        {
            if (checkKind(root) != CheckKind.NONE)
            {
                hasCheckUi = true;
                break;
            }
        }
        if (!hasCheckUi)
            return null;

        int[] checked = { 0 };
        walkChecked(content, roots, checked);
        return Integer.valueOf(checked[0]);
    }

    private static void walkChecked(ITreeContentProvider content, Object[] elements, int[] checked)
    {
        if (elements == null)
            return;
        for (Object element : elements)
        {
            if (checkKind(element) == CheckKind.CHECKED)
                checked[0]++;
            Object[] children;
            try
            {
                children = content.getChildren(element);
            }
            catch (RuntimeException e)
            {
                continue;
            }
            walkChecked(content, children, checked);
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

    private static void watchList(DtGranularEditor<?> editor, CTabItem item, IFormPage page, Control root)
    {
        Control list = findPrimaryList(root);
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
