package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.shared.MdUiSharedImages;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.ui.util.OpenHelper;

/**
 * Вкладка «Данные» редактора объекта метаданных:
 * <ul>
 *   <li>контекстное меню дерева реквизитов — переход на «Функц. опции», «Права»
 *       и открытие редактора «Все роли»;</li>
 *   <li>двойной клик в «Общие реквизиты» — {@code bringToTop} панели «Свойства».
 *       Дерево «Стандартные реквизиты» не перехватывать: Grok 4.6 (2026-08-16) за ~40 попыток не смог правильно загрузить реквизит в панель «Свойства».</li>
 * </ul>
 */
public final class MdEditorAttributeMenuHook implements IStartup
{
    private static final String TAG = "MdEditorAttributeMenu"; //$NON-NLS-1$

    private static final String HOOK_MARKER = "tormozit.mdEditorAttributeMenuHooked"; //$NON-NLS-1$

    private static final String HOOK_MENU_KEY = "tormozit.mdEditorAttributeMenuInstance"; //$NON-NLS-1$

    private static final String MENU_GEN_KEY = "tormozit.mdEditorAttributeMenuGen"; //$NON-NLS-1$

    private static final String DT_TREE_VIEWER_KEY =
        "com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView.treeViewer"; //$NON-NLS-1$

    private static final String FO_PAGE_ID = "editors.pages.functionalOptions"; //$NON-NLS-1$

    private static final String FO_CONTENT_COMPONENT_CLASS =
        "com._1c.g5.v8.dt.internal.md.ui.editors.pages.functionaloptions.DtGranularEditorFunctionalOptionsMdObjectContentComponent"; //$NON-NLS-1$

    private static final String PROPERTIES_MENU_TEXT = "Свойства"; //$NON-NLS-1$

    private static final String ITEM_FO = "Функциональные опции"; //$NON-NLS-1$

    private static final String ITEM_RIGHTS = "Права"; //$NON-NLS-1$

    private static final String ITEM_ALL_ROLES = "Все роли"; //$NON-NLS-1$

    private static final int MAX_ATTEMPTS = 40;

    private static final int RETRY_MS = 100;

    private static final String PROPERTY_SHEET_VIEW_ID = "org.eclipse.ui.views.PropertySheet"; //$NON-NLS-1$

    private static Widget lastDownWidget;

    private static Object lastDownItem;

    private static int sameItemDowns;

    private static long lastDownMs;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.addFilter(SWT.MenuDetect, MdEditorAttributeMenuHook::handleMenuDetect);
            display.addFilter(SWT.Show, MdEditorAttributeMenuHook::handleMenuShow);
            display.addFilter(SWT.MouseDown, MdEditorAttributeMenuHook::handleMouseDown);
            display.addFilter(SWT.MouseDoubleClick, MdEditorAttributeMenuHook::handleMouseDoubleClick);
        });
    }

    /**
     * Счётчик кликов по одному и тому же элементу: display-level {@code MouseDoubleClick}
     * срабатывает и при втором клике в другом виджете в пределах {@link Display#getDoubleClickTime}.
     */
    private static void handleMouseDown(Event event)
    {
        if (event.button != 1)
            return;
        Control control = dataPageTableOrTree(event);
        if (control == null)
            return;
        Object item = itemDataOf(event);
        long now = System.currentTimeMillis();
        int dblTime = event.display != null ? event.display.getDoubleClickTime() : 500;
        boolean same = event.widget == lastDownWidget
            && Objects.equals(item, lastDownItem)
            && (now - lastDownMs) <= dblTime;
        sameItemDowns = same ? sameItemDowns + 1 : 1;
        lastDownWidget = event.widget;
        lastDownItem = item;
        lastDownMs = now;
    }

    /**
     * «Общие реквизиты»: {@code bringToTop}.
     * Деревья вкладки не перехватываем. В «Стандартные реквизиты» не лезть:
     * Grok 4.6 (2026-08-16) за ~40 попыток не смог правильно загрузить реквизит в панель «Свойства».
     */
    private static void handleMouseDoubleClick(Event event)
    {
        if (event.button != 1)
            return;
        Control control = dataPageTableOrTree(event);
        if (!(control instanceof Table table))
            return;
        Object item = itemDataOf(event);
        boolean genuine = event.widget == lastDownWidget
            && sameItemDowns >= 2
            && Objects.equals(item, lastDownItem);
        DtGranularEditor<?> editor = editorOf(table);
        if (selectedCommonAttribute(table) == null)
            return;
        if (!genuine || item == null || editor == null || editor.getSite() == null)
            return;
        event.doit = false;
        bringPropertiesViewToFront(editor);
    }

    private static void bringPropertiesViewToFront(DtGranularEditor<?> editor)
    {
        IViewPart view = propertiesView(editor);
        if (view == null)
            return;
        editor.getSite().getPage().bringToTop(view);
    }

    private static IViewPart propertiesView(DtGranularEditor<?> editor)
    {
        IWorkbenchPage page = editor.getSite().getPage();
        if (page == null)
            return null;
        IViewPart view = page.findView(PROPERTY_SHEET_VIEW_ID);
        if (view == null)
        {
            try
            {
                view = page.showView(PROPERTY_SHEET_VIEW_ID, null, IWorkbenchPage.VIEW_VISIBLE);
            }
            catch (PartInitException e)
            {
                Global.logError(TAG, "showView PropertySheet", e); //$NON-NLS-1$
                return null;
            }
        }
        return view;
    }

    private static Control dataPageTableOrTree(Event event)
    {
        if (event.widget instanceof Table table && !table.isDisposed())
        {
            DtGranularEditor<?> editor = editorOf(table);
            return isDataPageControl(editor, table) ? table : null;
        }
        if (event.widget instanceof Tree tree && !tree.isDisposed())
        {
            DtGranularEditor<?> editor = editorOf(tree);
            return isDataPageControl(editor, tree) ? tree : null;
        }
        return null;
    }

    private static Object itemDataOf(Event event)
    {
        Point point = new Point(event.x, event.y);
        if (event.widget instanceof Table table && !table.isDisposed())
        {
            TableItem item = table.getItem(point);
            return item == null ? null : item.getData();
        }
        if (event.widget instanceof Tree tree && !tree.isDisposed())
        {
            TreeItem item = tree.getItem(point);
            return item == null ? null : item.getData();
        }
        return null;
    }

    private static boolean isDataPageControl(DtGranularEditor<?> editor, Control control)
    {
        if (editor == null || control == null || control.isDisposed())
            return false;
        IFormPage page = editor.getActivePageInstance();
        if (!isDataPage(page))
            return false;
        Control root = page.getPartControl();
        return root != null && isUnder(root, control);
    }

    private static EObject selectedCommonAttribute(Table table)
    {
        TableItem[] selection = table.getSelection();
        if (selection == null || selection.length == 0)
            return null;
        Object data = selection[0].getData();
        if (data == null)
            return null;
        String typeName = data.getClass().getName();
        if (typeName == null || !typeName.contains("CommonAttributesDataItemViewModel")) //$NON-NLS-1$
            return null;
        return mapViewModelToEObject(table, data);
    }

    private static void handleMenuDetect(Event event)
    {
        if (!(event.widget instanceof Control control) || control.isDisposed())
            return;
        Tree tree = treeOf(control);
        if (tree == null || tree.isDisposed() || !isDataPageAttributesTree(tree))
            return;
        Menu menu = menuOf(tree);
        if (menu == null || menu.isDisposed())
            return;
        hookMenu(tree, menu);
        scheduleEnsureMenuItems(tree, menu);
    }

    /**
     * MenuManager пересобирает popup на {@code SWT.Show} и может стереть чужие пункты.
     * Повторно цепляемся к новому экземпляру меню и вставляем пункты после его наполнения.
     */
    private static void handleMenuShow(Event event)
    {
        if (!(event.widget instanceof Menu menu) || menu.isDisposed())
            return;
        Control parent = menu.getParent();
        if (parent == null || parent.isDisposed())
            return;
        Tree tree = treeOf(parent);
        if (tree == null || tree.isDisposed() || !isDataPageAttributesTree(tree))
            return;
        Menu treeMenu = menuOf(tree);
        if (treeMenu != menu)
            return;
        hookMenu(tree, menu);
        scheduleEnsureMenuItems(tree, menu);
    }

    private static void hookMenu(Tree tree, Menu menu)
    {
        Object hooked = tree.getData(HOOK_MENU_KEY);
        if (hooked == menu && Boolean.TRUE.equals(menu.getData(HOOK_MARKER)))
            return;
        tree.setData(HOOK_MENU_KEY, menu);
        menu.setData(HOOK_MARKER, Boolean.TRUE);

        MenuAdapter listener = new MenuAdapter()
        {
            @Override
            public void menuShown(MenuEvent me)
            {
                Menu swtMenu = (Menu) me.widget;
                bumpMenuGen(swtMenu);
                fillMenuItems(swtMenu, tree);
                scheduleEnsureMenuItems(tree, swtMenu);
            }

            @Override
            public void menuHidden(MenuEvent me)
            {
                Menu swtMenu = (Menu) me.widget;
                int gen = menuGen(swtMenu);
                Display display = swtMenu.getDisplay();
                display.asyncExec(() ->
                {
                    if (!swtMenu.isDisposed() && menuGen(swtMenu) == gen)
                        removeOurItems(swtMenu);
                });
            }
        };

        menu.addMenuListener(listener);
        tree.addDisposeListener(ev ->
        {
            if (!menu.isDisposed())
                menu.removeMenuListener(listener);
        });
    }

    private static void scheduleEnsureMenuItems(Tree tree, Menu menu)
    {
        Display display = tree.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            if (tree.isDisposed())
                return;
            Menu current = menuOf(tree);
            if (current == null || current.isDisposed())
                return;
            if (current != menu)
                hookMenu(tree, current);
            if (!hasOurItems(current))
                fillMenuItems(current, tree);
        });
    }

    private static void fillMenuItems(Menu swtMenu, Tree tree)
    {
        if (swtMenu == null || swtMenu.isDisposed() || tree == null || tree.isDisposed())
            return;
        if (hasOurItems(swtMenu))
            return;
        EObject member = selectedDataMember(tree);
        if (member == null)
            return;

        DtGranularEditor<?> editor = editorOf(tree);
        if (editor == null)
            return;

        IFormPage foPage = findPage(editor, MdEditorAttributeMenuHook::isFunctionalOptionsPage);
        IFormPage rightsPage = findPage(editor, MdEditorAttributeMenuHook::isRightsPage);
        Configuration configuration = configurationOf(editor);
        String relativeName = relativeName(member, editor.getModel());
        int insertIndex = findInsertIndex(swtMenu);

        new MenuItem(swtMenu, SWT.SEPARATOR, insertIndex);

        MenuItem foItem = new MenuItem(swtMenu, SWT.PUSH, insertIndex + 1);
        foItem.setText(ITEM_FO);
        Image foImage = mdImage(MdUiSharedImages.OBJS_FUNCTIONAL_OPTION);
        if (foImage != null)
            foItem.setImage(foImage);
        foItem.setToolTipText("Открыть вкладку «Функц. опции» и выделить этот реквизит" //$NON-NLS-1$
            + Global.pluginSignForTooltip());
        foItem.setEnabled(foPage != null);
        foItem.addListener(SWT.Selection, ev -> revealOnFunctionalOptions(editor, member));

        MenuItem rightsItem = new MenuItem(swtMenu, SWT.PUSH, insertIndex + 2);
        rightsItem.setText(ITEM_RIGHTS);
        Image rightsImage = mdImage(MdUiSharedImages.OBJS_ROLE);
        if (rightsImage != null)
            rightsItem.setImage(rightsImage);
        rightsItem.setToolTipText("Открыть вкладку «Права» и отфильтровать по относительному имени" //$NON-NLS-1$
            + Global.pluginSignForTooltip());
        rightsItem.setEnabled(rightsPage != null && relativeName != null && !relativeName.isBlank());
        final String filterText = relativeName;
        rightsItem.addListener(SWT.Selection, ev -> revealOnRights(editor, filterText));

        MenuItem allRolesItem = new MenuItem(swtMenu, SWT.PUSH, insertIndex + 3);
        allRolesItem.setText(ITEM_ALL_ROLES);
        Image allRolesImage = mdImage(MdUiSharedImages.OBJS_ROLE);
        if (allRolesImage != null)
            allRolesItem.setImage(allRolesImage);
        allRolesItem.setToolTipText("Открыть редактор «Все роли» и отфильтровать по относительному имени" //$NON-NLS-1$
            + Global.pluginSignForTooltip());
        allRolesItem.setEnabled(configuration != null);
        allRolesItem.addListener(SWT.Selection, ev -> revealOnAllRoles(editor, member, filterText));
    }

    private static boolean hasOurItems(Menu menu)
    {
        if (menu == null || menu.isDisposed())
            return false;
        for (MenuItem item : menu.getItems())
        {
            if (ITEM_FO.equals(plainMenuText(item)))
                return true;
        }
        return false;
    }

    private static void removeOurItems(Menu menu)
    {
        if (menu == null || menu.isDisposed())
            return;
        MenuItem[] items = menu.getItems();
        int foIndex = -1;
        for (int i = 0; i < items.length; i++)
        {
            if (ITEM_FO.equals(plainMenuText(items[i])))
            {
                foIndex = i;
                break;
            }
        }
        if (foIndex < 0)
            return;
        int start = foIndex > 0 && (items[foIndex - 1].getStyle() & SWT.SEPARATOR) != 0
            ? foIndex - 1 : foIndex;
        for (int i = Math.min(start + 4, items.length) - 1; i >= start; i--)
        {
            if (items[i] == null || items[i].isDisposed())
                continue;
            String text = plainMenuText(items[i]);
            boolean ours = ITEM_FO.equals(text) || ITEM_RIGHTS.equals(text) || ITEM_ALL_ROLES.equals(text)
                || (i == start && (items[i].getStyle() & SWT.SEPARATOR) != 0);
            if (ours)
                items[i].dispose();
        }
    }

    private static int bumpMenuGen(Menu menu)
    {
        int gen = menuGen(menu) + 1;
        menu.setData(MENU_GEN_KEY, Integer.valueOf(gen));
        return gen;
    }

    private static int menuGen(Menu menu)
    {
        return menu.getData(MENU_GEN_KEY) instanceof Integer value ? value.intValue() : 0;
    }

    private static Menu menuOf(Tree tree)
    {
        Menu menu = tree.getMenu();
        if (menu != null && !menu.isDisposed())
            return menu;
        return resolveContextMenu(tree);
    }

    private static boolean isDataPageAttributesTree(Tree tree)
    {
        DtGranularEditor<?> editor = editorOf(tree);
        if (editor == null)
            return false;
        IFormPage page = editor.getActivePageInstance();
        if (!isDataPage(page))
            return false;
        Control root = page.getPartControl();
        return root != null && isUnder(root, tree);
    }

    private static DtGranularEditor<?> editorOf(Control control)
    {
        if (!PlatformUI.isWorkbenchRunning())
            return null;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage workbenchPage = window == null ? null : window.getActivePage();
        IEditorPart editor = workbenchPage == null ? null : workbenchPage.getActiveEditor();
        return editor instanceof DtGranularEditor<?> granular ? granular : null;
    }

    private static boolean isDataPage(IFormPage page)
    {
        if (page == null)
            return false;
        String id = page.getId();
        if (id != null && id.endsWith(".pages.data")) //$NON-NLS-1$
            return true;
        return page.getClass().getName().endsWith("EditorDataPage"); //$NON-NLS-1$
    }

    private static boolean isFunctionalOptionsPage(IFormPage page)
    {
        if (page == null)
            return false;
        String id = page.getId();
        if (FO_PAGE_ID.equals(id))
            return true;
        return page.getClass().getName().contains("FunctionalOptionsPage"); //$NON-NLS-1$
    }

    private static boolean isRightsPage(IFormPage page)
    {
        if (page == null)
            return false;
        String id = page.getId();
        if (id != null && id.endsWith(".editors.page.rights")) //$NON-NLS-1$
            return true;
        return page.getClass().getName().endsWith("RightsEditorRightsPage"); //$NON-NLS-1$
    }

    private static EObject selectedDataMember(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return null;
        TreeItem[] selection = tree.getSelection();
        if (selection == null || selection.length == 0)
            return null;
        Object element = selection[0].getData();
        EObject direct = NavigatorElementModels.resolveEObject(element);
        if (isDataMember(direct))
            return direct;
        EObject mapped = mapViewModelToEObject(tree, element);
        return isDataMember(mapped) ? mapped : null;
    }

    private static EObject mapViewModelToEObject(Control control, Object viewModel)
    {
        if (viewModel == null)
            return null;
        Object mapper = mapperOwningViewModel(control, viewModel);
        if (mapper == null)
            return null;
        Object model;
        try
        {
            model = Global.invoke(mapper, "mapViewToModel", viewModel); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "mapViewToModel", e); //$NON-NLS-1$
            return null;
        }
        return NavigatorElementModels.resolveEObject(model);
    }

    private static Object mapperOwningViewModel(Control control, Object viewModel)
    {
        DtGranularEditor<?> editor = editorOf(control);
        IFormPage page = editor != null ? editor.getActivePageInstance() : null;
        Object root = page != null ? Global.getField(page, "pageComponent") : null; //$NON-NLS-1$
        return findMapperOwning(root, viewModel, 0);
    }

    private static Object findMapperOwning(Object component, Object viewModel, int depth)
    {
        if (component == null || viewModel == null || depth > 20)
            return null;
        Object mapper = Global.invoke(component, "getMapper"); //$NON-NLS-1$
        if (ownsViewModel(mapper, viewModel) || mapsViewModel(mapper, viewModel))
            return mapper;
        for (Object child : AefFieldFocus.childComponents(component))
        {
            Object found = findMapperOwning(child, viewModel, depth + 1);
            if (found != null)
                return found;
        }
        return null;
    }

    private static boolean mapsViewModel(Object mapper, Object viewModel)
    {
        if (mapper == null)
            return false;
        try
        {
            Object model = Global.invoke(mapper, "mapViewToModel", viewModel); //$NON-NLS-1$
            return NavigatorElementModels.resolveEObject(model) != null;
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    private static boolean ownsViewModel(Object mapper, Object viewModel)
    {
        if (mapper == null)
            return false;
        Object mappedViews = Global.invoke(mapper, "getMappedViewModels"); //$NON-NLS-1$
        if (mappedViews instanceof Collection<?> views && views.contains(viewModel))
            return true;
        Object mappedModels = Global.invoke(mapper, "getMappedModels"); //$NON-NLS-1$
        return mappedModels instanceof Collection<?> models && models.contains(viewModel);
    }

    private static boolean isDataMember(EObject object)
    {
        if (object == null)
            return false;
        String typeName = object.eClass().getName();
        if (typeName != null && typeName.contains("CommonAttribute")) //$NON-NLS-1$
            return false;
        // RegisterDimension / RegisterResource / CatalogAttribute и т.п. — BasicFeature,
        // а не голые имена «Dimension»/«Resource».
        if (object instanceof BasicFeature)
            return true;
        if (typeName == null)
            return false;
        if ("StandardAttribute".equals(typeName)) //$NON-NLS-1$
            return true;
        if (typeName.endsWith("TabularSection")) //$NON-NLS-1$
            return true;
        if (typeName.endsWith("Dimension") && !typeName.contains("DimensionTable")) //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        return typeName.endsWith("Resource") //$NON-NLS-1$
            || typeName.endsWith("AccountingFlag") //$NON-NLS-1$
            || typeName.endsWith("Recalculation"); //$NON-NLS-1$
    }

    private static String relativeName(EObject member, EObject owner)
    {
        String fromFullName = relativeFromFullName(member, owner);
        if (fromFullName != null && !fromFullName.isBlank())
            return fromFullName;
        return relativeFromContainment(member, owner);
    }

    private static String relativeFromFullName(EObject member, EObject owner)
    {
        String full = GetRef.eObjectToFullName(member);
        if (full == null || full.isBlank())
            return null;
        String ownerFull = owner != null ? GetRef.eObjectToFullName(owner) : null;
        String relative = stripOwnerPrefix(full, ownerFull);
        if (relative != null)
            return relative;
        return stripOwnerPrefix(full, MdTypeMapping.toOwnerMdObjectRef(full));
    }

    private static String stripOwnerPrefix(String full, String ownerFull)
    {
        if (full == null || ownerFull == null || ownerFull.isBlank())
            return null;
        String prefix = ownerFull + "."; //$NON-NLS-1$
        if (!full.startsWith(prefix))
            return null;
        String relative = full.substring(prefix.length());
        return relative.isBlank() ? null : relative;
    }

    private static String relativeFromContainment(EObject member, EObject owner)
    {
        List<String> parts = new ArrayList<>();
        for (EObject current = member; current != null && current != owner; current = current.eContainer())
        {
            if (owner != null && sameObject(current, owner))
                break;
            String type = typeRu(current);
            String name = objectName(current);
            if (type == null || type.isBlank() || name == null || name.isBlank())
                return null;
            parts.add(type + "." + name); //$NON-NLS-1$
            if (parts.size() > 8)
                return null;
        }
        if (parts.isEmpty())
            return null;
        StringBuilder result = new StringBuilder();
        for (int i = parts.size() - 1; i >= 0; i--)
        {
            if (result.length() > 0)
                result.append('.');
            result.append(parts.get(i));
        }
        return result.toString();
    }

    private static String typeRu(EObject object)
    {
        String typeName = object.eClass().getName();
        if ("StandardAttribute".equals(typeName)) //$NON-NLS-1$
            return "СтандартныйРеквизит"; //$NON-NLS-1$
        String ru = MdTypeMapping.anyToRu(typeName);
        if (ru != null)
            return ru;
        if (typeName.endsWith("Attribute")) //$NON-NLS-1$
            return "Реквизит"; //$NON-NLS-1$
        if (typeName.endsWith("TabularSection")) //$NON-NLS-1$
            return "ТабличнаяЧасть"; //$NON-NLS-1$
        if (typeName.endsWith("Dimension") && !typeName.contains("DimensionTable")) //$NON-NLS-1$ //$NON-NLS-2$
            return "Измерение"; //$NON-NLS-1$
        if (typeName.endsWith("Resource")) //$NON-NLS-1$
            return "Ресурс"; //$NON-NLS-1$
        if (typeName.endsWith("AccountingFlag")) //$NON-NLS-1$
            return "ПризнакУчета"; //$NON-NLS-1$
        if (typeName.endsWith("Recalculation")) //$NON-NLS-1$
            return "Перерасчет"; //$NON-NLS-1$
        return typeName;
    }

    private static String objectName(EObject object)
    {
        Object nameRu = Global.invoke(object, "getNameRu"); //$NON-NLS-1$
        if (nameRu instanceof String ru && !ru.isBlank())
            return ru;
        Object name = Global.invoke(object, "getName"); //$NON-NLS-1$
        return name instanceof String text && !text.isBlank() ? text : null;
    }

    private static void revealOnFunctionalOptions(DtGranularEditor<?> editor, EObject member)
    {
        if (editor == null || member == null)
            return;
        IFormPage page = findPage(editor, MdEditorAttributeMenuHook::isFunctionalOptionsPage);
        if (page == null)
            return;
        editor.setActivePage(page.getId());
        scheduleSelectOnFunctionalOptions(editor, member, 0);
    }

    private static void scheduleSelectOnFunctionalOptions(DtGranularEditor<?> editor, EObject member, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || attempt >= MAX_ATTEMPTS)
            return;
        display.timerExec(attempt == 0 ? 0 : RETRY_MS, () ->
        {
            if (selectOnFunctionalOptions(editor, member, attempt))
            {
                for (int delay : new int[] { 300, 800, 1500 })
                    display.timerExec(delay, () -> selectOnFunctionalOptions(editor, member, -1));
                return;
            }
            scheduleSelectOnFunctionalOptions(editor, member, attempt + 1);
        });
    }

    /**
     * Выделение в «Составе объекта» — штатный {@code setSelection} компонента. Успех только если
     * {@code TreeViewer} реально показывает этот реквизит: страница по умолчанию выбирает сам
     * объект, а {@code mapModelToView == null} очищает выделение.
     */
    private static boolean selectOnFunctionalOptions(DtGranularEditor<?> editor, EObject member, int attempt)
    {
        IFormPage page = editor.getActivePageInstance();
        if (!isFunctionalOptionsPage(page))
            return false;
        Object root = Global.getField(page, "pageComponent"); //$NON-NLS-1$
        Object component = findComponentByClass(root, FO_CONTENT_COMPONENT_CLASS, 0);
        if (component == null)
            return false;

        TreeViewer viewer = findContentTreeViewer(component);
        if (viewer != null && viewer.getTree() != null && !viewer.getTree().isDisposed())
            viewer.expandToLevel(8);

        Object mapper = Global.invoke(component, "getMapper"); //$NON-NLS-1$
        Object model = mappedModelFor(component, member);
        if (model == null)
            model = modelIfMapperKnows(mapper, member);
        if (model == null)
            return false;
        boolean invoked;
        try
        {
            invoked = Global.invokeVoid(component, "setSelection", List.of(model)); //$NON-NLS-1$
        }
        catch (RuntimeException ignored)
        {
            return false;
        }
        boolean selected = selectionIsMember(viewer, mapper, member);
        if (!invoked || !selected)
            return false;
        Tree tree = viewer.getTree();
        if (tree != null && !tree.isDisposed() && tree.getSelectionCount() > 0)
            tree.showSelection();
        return true;
    }

    private static Object mappedModelFor(Object component, EObject member)
    {
        Object mapper = Global.invoke(component, "getMapper"); //$NON-NLS-1$
        Object mappedModels = mapper != null ? Global.invoke(mapper, "getMappedModels") : null; //$NON-NLS-1$
        if (!(mappedModels instanceof Iterable<?> iterable))
            return null;
        for (Object candidate : iterable)
        {
            if (candidate == member)
                return candidate;
            EObject candidateObject = NavigatorElementModels.resolveEObject(candidate);
            if (sameObject(candidateObject, member))
                return candidate;
        }
        return null;
    }

    private static Object modelIfMapperKnows(Object mapper, EObject member)
    {
        if (mapper == null || member == null)
            return null;
        try
        {
            Object view = Global.invoke(mapper, "mapModelToView", member); //$NON-NLS-1$
            return view != null ? member : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static boolean selectionIsMember(TreeViewer viewer, Object mapper, EObject member)
    {
        if (viewer == null || member == null)
            return false;
        IStructuredSelection selection = viewer.getStructuredSelection();
        if (selection == null || selection.isEmpty())
            return false;
        Object element = selection.getFirstElement();
        if (sameObject(NavigatorElementModels.resolveEObject(element), member))
            return true;
        if (mapper == null)
            return false;
        try
        {
            Object model = Global.invoke(mapper, "mapViewToModel", element); //$NON-NLS-1$
            return sameObject(NavigatorElementModels.resolveEObject(model), member);
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    /** TreeViewer дерева «Состав объекта», не списка функциональных опций. */
    private static TreeViewer findContentTreeViewer(Object component)
    {
        Object scene = component != null ? Global.invoke(component, "getScene") : null; //$NON-NLS-1$
        for (Object nativeControl : AefFieldFocus.editorNativeControls(scene, component))
        {
            TreeViewer viewer = treeViewerOfNative(nativeControl);
            if (viewer != null)
                return viewer;
        }
        return null;
    }

    private static TreeViewer treeViewerOfNative(Object nativeControl)
    {
        Control control = nativeControl instanceof Control swt ? swt : null;
        if (control == null)
            control = Global.invoke(nativeControl, "getNativeControl") instanceof Control swt ? swt : null; //$NON-NLS-1$
        if (control == null || control.isDisposed())
            return null;
        for (Control current = control; current != null; current = current.getParent())
        {
            if (current.getData(DT_TREE_VIEWER_KEY) instanceof TreeViewer viewer)
                return viewer;
        }
        return control instanceof Composite composite ? findTreeViewerInData(composite, 0) : null;
    }

    private static TreeViewer findTreeViewerInData(Composite composite, int depth)
    {
        if (composite == null || composite.isDisposed() || depth > 20)
            return null;
        if (composite.getData(DT_TREE_VIEWER_KEY) instanceof TreeViewer viewer)
            return viewer;
        for (Control child : composite.getChildren())
        {
            if (child instanceof Composite childComposite)
            {
                TreeViewer found = findTreeViewerInData(childComposite, depth + 1);
                if (found != null)
                    return found;
            }
        }
        return null;
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

    private static void revealOnRights(DtGranularEditor<?> editor, String relativeName)
    {
        if (editor == null || relativeName == null || relativeName.isBlank())
            return;
        IFormPage page = findPage(editor, MdEditorAttributeMenuHook::isRightsPage);
        if (page == null)
            return;
        editor.setActivePage(page.getId());
        scheduleRightsFilter(editor, relativeName, 0);
    }

    private static void scheduleRightsFilter(DtGranularEditor<?> editor, String relativeName, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || attempt >= MAX_ATTEMPTS)
            return;
        display.timerExec(attempt == 0 ? 0 : RETRY_MS, () ->
        {
            IFormPage page = editor.getActivePageInstance();
            if (isRightsPage(page) && RightsEditorFilterHook.applyFilterText(page, relativeName))
                return;
            scheduleRightsFilter(editor, relativeName, attempt + 1);
        });
    }

    private static void revealOnAllRoles(DtGranularEditor<?> editor, EObject member, String relativeName)
    {
        Configuration configuration = configurationOf(editor);
        if (configuration == null || member == null)
            return;
        IWorkbenchPage workbenchPage = editor.getSite() != null ? editor.getSite().getPage() : null;
        if (workbenchPage == null)
            return;
        IEditorPart allRoles;
        try
        {
            allRoles = new OpenHelper(workbenchPage).openEditor(configuration,
                MdClassPackage.Literals.CONFIGURATION__ROLES);
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "open AllRoles", e); //$NON-NLS-1$
            return;
        }
        if (allRoles == null)
            return;
        scheduleSelectOnAllRoles(allRoles, editor.getModel(), member, relativeName, 0);
    }

    private static Configuration configurationOf(DtGranularEditor<?> editor)
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
            (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
        if (projectManager == null)
            return null;
        IV8Project project = projectManager.getProject(mdObject);
        if (project instanceof IConfigurationProject configurationProject)
            return configurationProject.getConfiguration();
        Object configuration = Global.invoke(project, "getConfiguration"); //$NON-NLS-1$
        return configuration instanceof Configuration conf ? conf : null;
    }

    private static void scheduleSelectOnAllRoles(IEditorPart editor, EObject owner, EObject member,
        String relativeName, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || attempt >= MAX_ATTEMPTS)
            return;
        display.timerExec(attempt == 0 ? 0 : RETRY_MS, () ->
        {
            if (!selectOnAllRoles(editor, owner, member))
            {
                scheduleSelectOnAllRoles(editor, owner, member, relativeName, attempt + 1);
                return;
            }
            if (relativeName != null && !relativeName.isBlank()
                && !RightsEditorFilterHook.applyFilterText(editor, relativeName))
            {
                scheduleSelectOnAllRoles(editor, owner, member, relativeName, attempt + 1);
                return;
            }
        });
    }

    private static boolean selectOnAllRoles(IEditorPart editor, EObject owner, EObject member)
    {
        if (editor == null || member == null)
            return false;
        Object section = Global.getField(editor, "objectsSection"); //$NON-NLS-1$
        if (section == null || !(Global.getField(section, "viewer") instanceof TreeViewer viewer) //$NON-NLS-1$
            || !(viewer.getContentProvider() instanceof ITreeContentProvider provider))
            return false;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return false;
        Object[] roots = provider.getElements(viewer.getInput());
        Object ownerRow = owner != null ? findAllRolesRow(provider, roots, owner, 0, 4, true) : null;
        Object row = ownerRow == null
            ? findAllRolesRow(provider, roots, member, 0, 8, true)
            : sameObject(rowObject(ownerRow), member) ? ownerRow
                : findAllRolesRow(provider, provider.getChildren(ownerRow), member, 0, 8, false);
        if (row == null)
            return false;
        viewer.setSelection(new StructuredSelection(row), true);
        if (tree.getSelectionCount() > 0)
            tree.showSelection();
        Global.invoke(section, "setFocus"); //$NON-NLS-1$
        return tree.getSelectionCount() > 0;
    }

    private static EObject rowObject(Object row)
    {
        return Global.invoke(row, "getEObject") instanceof EObject object ? object : null; //$NON-NLS-1$
    }

    private static Object findAllRolesRow(ITreeContentProvider provider, Object[] rows, EObject target,
        int depth, int maxDepth, boolean skipOtherObjects)
    {
        if (rows == null || target == null || depth >= maxDepth)
            return null;
        for (Object row : rows)
        {
            if (row == null)
                continue;
            EObject rowObject = rowObject(row);
            if (sameObject(rowObject, target))
                return row;
            if (skipOtherObjects && rowObject != null && rowObject.eContainer() instanceof Configuration)
                continue;
            Object found = findAllRolesRow(provider, provider.getChildren(row), target, depth + 1,
                maxDepth, skipOtherObjects);
            if (found != null)
                return found;
        }
        return null;
    }

    private static IFormPage findPage(DtGranularEditor<?> editor, Predicate<IFormPage> match)
    {
        if (editor == null || match == null)
            return null;
        Object pagesObj = Global.getField(editor, "pages"); //$NON-NLS-1$
        if (!(pagesObj instanceof List<?> pages))
            return null;
        for (Object pageObj : pages)
        {
            if (pageObj instanceof IFormPage page && match.test(page))
                return page;
        }
        return null;
    }

    private static boolean sameObject(EObject first, EObject second)
    {
        if (first == null || second == null)
            return false;
        if (first == second)
            return true;
        if (first instanceof IBmObject firstBm && second instanceof IBmObject secondBm
            && firstBm.bmGetId() == secondBm.bmGetId())
            return true;
        try
        {
            return EcoreUtil.getURI(first).equals(EcoreUtil.getURI(second));
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "sameObject", e); //$NON-NLS-1$
            return false;
        }
    }

    private static boolean isUnder(Control ancestor, Control control)
    {
        for (Control current = control; current != null; current = current.getParent())
        {
            if (current == ancestor)
                return true;
        }
        return false;
    }

    private static int findInsertIndex(Menu menu)
    {
        MenuItem[] items = menu.getItems();
        for (int i = 0; i < items.length; i++)
        {
            if (PROPERTIES_MENU_TEXT.equals(plainMenuText(items[i])))
                return i;
        }
        return items.length;
    }

    private static String plainMenuText(MenuItem item)
    {
        String text = item.getText();
        if (text == null || text.isBlank())
            return ""; //$NON-NLS-1$
        int tab = text.indexOf('\t');
        if (tab >= 0)
            text = text.substring(0, tab);
        return text.replace("&", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Меню на контроле или ближайшем предке (как ищет SWT при MenuDetect). */
    private static Menu resolveContextMenu(Control control)
    {
        for (Control current = control; current != null; current = current.getParent())
        {
            Menu menu = current.getMenu();
            if (menu != null && !menu.isDisposed())
                return menu;
        }
        return null;
    }

    private static Tree treeOf(Control control)
    {
        if (control instanceof Tree tree)
            return tree;
        Tree nested = findTreeDescendant(control, 0);
        if (nested != null)
            return nested;
        for (Control parent = control.getParent(); parent != null; parent = parent.getParent())
        {
            if (parent instanceof Tree tree)
                return tree;
            if (parent.getClass().getName().contains("DtTreeView")) //$NON-NLS-1$
                return findTreeDescendant(parent, 0);
        }
        return null;
    }

    private static Tree findTreeDescendant(Control control, int depth)
    {
        if (!(control instanceof Composite composite) || composite.isDisposed() || depth > 8)
            return null;
        for (Control child : composite.getChildren())
        {
            if (child instanceof Tree tree)
                return tree;
            Tree nested = findTreeDescendant(child, depth + 1);
            if (nested != null)
                return nested;
        }
        return null;
    }

    /** Те же картинки, что у вкладок «Функц. опции» / «Права» в редакторе объекта. */
    private static Image mdImage(String key)
    {
        if (key == null)
            return null;
        try
        {
            Image image = MdUiSharedImages.getImage(key);
            return image != null && !image.isDisposed() ? image : null;
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
    }
}
