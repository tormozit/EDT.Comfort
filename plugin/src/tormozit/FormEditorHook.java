package tormozit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.CompoundContributionItem;

import com._1c.g5.v8.dt.form.ui.editor.FormEditor;
import com._1c.g5.v8.dt.form.ui.editor.FormEditorPage;
import com._1c.g5.v8.dt.form.ui.editor.item.FormItemActionsGroup;
import com._1c.g5.v8.dt.ui.commands.ShowPropertiesHandler;

/**
 * Объединяет три поведения редактора форм EDT:
 *
 * <ol>
 *   <li><b>Правый клик в области предпросмотра (WYSIWYG).</b>
 *       На {@link SWT#MouseDown} посылает синтетический левый клик,
 *       чтобы EDT выбрал элемент формы под курсором до открытия контекстного меню.
 *       Область предпросмотра реализована классом
 *       {@code WysiwygNativeComposite} (наследник {@link Composite}).
 *
 *   <li><b>Двойной клик в области предпросмотра (WYSIWYG).</b>
 *       Активирует панель «Свойства» и выполняет «Изменить» для выбранного
 *       элемента — как двойной клик по дереву элементов формы в EDT.
 *
 *   <li><b>Сортировка подменю «События».</b>
 *       Упорядочивает пункты подменю «События» контекстного меню дерева элементов
 *       формы в соответствии с порядком конфигуратора 1С.
 *       Механизм: глобальный display-фильтр на {@link SWT#MenuDetect};
 *       на SWT-меню дерева вешается {@link MenuAdapter}, после построения
 *       корневого меню находим каскад «События» и подключаем {@link IMenuListener}
 *       к его JFace {@link MenuManager}.
 * </ol>
 */
public class FormEditorHook implements IStartup
{
    // -----------------------------------------------------------------------
    // Константы — правый клик (RightClickSelect)
    // -----------------------------------------------------------------------

    /** Простое имя класса wysiwyg-области предпросмотра форм. */
    private static final String WYSIWYG_CLASS = "WysiwygNativeComposite"; //$NON-NLS-1$

    // -----------------------------------------------------------------------
    // Константы — порядок событий (EventOrder)
    // -----------------------------------------------------------------------

    /** Текст подменю событий (FormItemActionsGroup_Events_group_name). */
    private static final String EVENTS_SUBMENU_TEXT = "События"; //$NON-NLS-1$

    /** Параметр команды EDT: имя события формы. */
    private static final String EDT_EVENT_NAME_PARAM =
            "com._1c.g5.v8.dt.form.ui.commandParameters.eventName"; //$NON-NLS-1$

    /**
     * Ключ которым JFace {@link MenuManager} регистрирует себя в SWT {@link Menu}.
     * Значение константы {@code MenuManager.MANAGER_KEY}.
     */
    private static final String JFACE_MANAGER_KEY =
            "org.eclipse.jface.action.MenuManager.managerKey"; //$NON-NLS-1$

    /** Маркер: SWT MenuAdapter уже навешен на меню дерева. */
    private static final String HOOK_MARKER = "tormozit.formEventOrderHooked"; //$NON-NLS-1$

    /** Подменю «События», на которое уже навешен listener сортировки. */
    private static final Set<IMenuManager> hookedEventsMenus =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Эталонный порядок событий формы как в конфигураторе 1С.
     * Ключ — имя события; значение — порядковый номер (меньше = выше в меню).
     */
    private static final Map<String, Integer> FORM_EVENT_ORDER = buildOrderMap(
        "ПриСозданииНаСервере",                                            //  1
        "ПриОткрытии",                                                     //  2
        "ПриПовторномОткрытии",                                            //  3
        "ПередЗакрытием",                                                  //  4
        "ПриЗакрытии",                                                     //  5
        "ОбработкаВыбора",                                                 //  6
        "ОбработкаОповещения",                                             //  7
        "ОбработкаАктивизации",                                            //  8
        "ОбработкаЗаписиНового",                                           //  9
        "ПриЧтенииНаСервере",                                              // 10
        "ПередЗаписью",                                                    // 11
        "ПередЗаписьюНаСервере",                                           // 12
        "ПриЗаписиНаСервере",                                              // 13
        "ПослеЗаписиНаСервере",                                            // 14
        "ПослеЗаписи",                                                     // 15
        "ОбработкаПроверкиЗаполненияНаСервере",                            // 16
        "ВнешнееСобытие",                                                  // 17
        "ОтключениеВнешнейКомпонентыПриОшибке",                            // 18
        "ПриСохраненииДанныхВНастройкахНаСервере",                         // 19
        "ПередЗагрузкойДанныхИзНастроекНаСервере",                         // 20
        "ПриЗагрузкеДанныхИзНастроекНаСервере",                            // 21
        "ОбработкаНавигационнойСсылки",                                    // 22
        "ОбработкаПолученияНавигационнойСсылки",                           // 23
        "ОбработкаПолученияСпискаНавигационныхСсылок",                     // 24
        "ОбработкаПерехода",                                               // 25
        "ВыборЗначения",                                                   // 26
        "ПриИзменениипараметровЭкрана",                                    // 27
        "АвтоПодборПользователейСистемыВзаимодействия",                    // 28
        "ОбработкаПолученияФормыВыбораПользователейСистемыВзаимодействия", // 29
        "ПриИзмененииДоступностиОсновногоСервера",                         // 30
        "ПередПереоткрытиемСДругогоСервера",                               // 31
        "ПриПереоткрытииСДругогоСервера",                                  // 32
        "ПриЗасыпанииКлиентскогоПриложения",                               // 33
        "ПриПробужденииКлиентскогоПриложения",                             // 34
        "ПриВставкеИзБуфераОбмена"                                         // 35
    );

    // -----------------------------------------------------------------------
    // IStartup
    // -----------------------------------------------------------------------

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.MouseDown, FormEditorHook::handleMouseDown);
        display.addFilter(SWT.MouseDoubleClick, FormEditorHook::handleMouseDoubleClick);
        display.addFilter(SWT.MenuDetect, FormEditorHook::handleMenuDetect);
    }

    // -----------------------------------------------------------------------
    // Правый клик — выбор элемента формы
    // -----------------------------------------------------------------------

    private static void handleMouseDown(Event e)
    {
        if (e.button != 3)
            return;
        if (!(e.widget instanceof Composite))
            return;
        if (!WYSIWYG_CLASS.equals(e.widget.getClass().getSimpleName()))
            return;

        simulateLeftClick((Composite) e.widget, e.x, e.y);
    }

    /**
     * Посылает синтетическую пару MouseDown/MouseUp левой кнопки на виджет,
     * чтобы EDT выбрал элемент формы под курсором до открытия контекстного меню.
     */
    private static void simulateLeftClick(Composite widget, int x, int y)
    {
        if (widget.isDisposed())
            return;

        Event down = new Event();
        down.type   = SWT.MouseDown;
        down.button = 1;
        down.x      = x;
        down.y      = y;
        down.count  = 1;
        widget.notifyListeners(SWT.MouseDown, down);

        Event up = new Event();
        up.type   = SWT.MouseUp;
        up.button = 1;
        up.x      = x;
        up.y      = y;
        up.count  = 1;
        widget.notifyListeners(SWT.MouseUp, up);
    }

    // -----------------------------------------------------------------------
    // Двойной клик — «Свойства» + «Изменить» (как в дереве элементов)
    // -----------------------------------------------------------------------

    private static void handleMouseDoubleClick(Event e)
    {
        if (e.button != 1)
            return;
        if (!(e.widget instanceof Composite))
            return;
        if (!WYSIWYG_CLASS.equals(e.widget.getClass().getSimpleName()))
            return;

        Display display = e.display;
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(FormEditorHook::runWysiwygDoubleClickActions);
    }

    /** Повторяет {@code FormEditorPage.lambda$9}: ShowProperties + Edit. */
    private static void runWysiwygDoubleClickActions()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;

        FormEditorPage page = FormEditor.getActiveFormEditorPage();
        if (page == null)
            return;

        Object groupObj = Global.getField(page, "itemsActionsGroup"); //$NON-NLS-1$
        if (!(groupObj instanceof FormItemActionsGroup group))
            return;

        var provider = page.getSite().getSelectionProvider();
        ISelection selection = provider != null ? provider.getSelection() : null;
        if (selection == null || selection.isEmpty())
            return;

        ShowPropertiesHandler.run(page.getSite());

        IAction edit = group.getEditAction();
        if (edit != null)
            edit.run();
    }

    // -----------------------------------------------------------------------
    // Сортировка подменю «События»
    // -----------------------------------------------------------------------

    private static void handleMenuDetect(Event e)
    {
        if (!(e.widget instanceof Tree))
            return;
        Tree tree = (Tree) e.widget;

        Menu swtMenu = tree.getMenu();
        if (swtMenu == null || swtMenu.isDisposed())
            return;

        if (Boolean.TRUE.equals(swtMenu.getData(HOOK_MARKER)))
            return;
        swtMenu.setData(HOOK_MARKER, Boolean.TRUE);

        swtMenu.addMenuListener(new MenuAdapter()
        {
            @Override
            public void menuShown(MenuEvent me)
            {
                onFormTreeMenuShown((Menu) me.widget);
            }
        });
    }

    /**
     * Корневое меню уже построено — ищем каскад «События» и подключаем сортировку
     * к его JFace MenuManager.
     */
    private static void onFormTreeMenuShown(Menu menu)
    {
        if (menu == null || menu.isDisposed())
            return;

        MenuManager eventsMenu = findEventsMenuManager(menu);
        if (eventsMenu != null)
            hookEventsMenuManager(eventsMenu);
    }

    /**
     * Ищет MenuManager подменю «События»: через каскад SWT (если уже создан)
     * или через items корневого JFace MenuManager (submenu часто ленивый).
     */
    private static MenuManager findEventsMenuManager(Menu menu)
    {
        for (MenuItem item : menu.getItems())
        {
            if (item.isDisposed() || (item.getStyle() & SWT.CASCADE) == 0)
                continue;
            if (!isEventsMenuText(item.getText()))
                continue;

            Menu subMenu = item.getMenu();
            if (subMenu != null && !subMenu.isDisposed())
            {
                Object data = subMenu.getData(JFACE_MANAGER_KEY);
                if (data instanceof MenuManager)
                    return (MenuManager) data;
            }
            break;
        }

        Object rootData = menu.getData(JFACE_MANAGER_KEY);
        if (rootData instanceof MenuManager)
            return findEventsSubMenuInManager((MenuManager) rootData);
        return null;
    }

    private static MenuManager findEventsSubMenuInManager(MenuManager root)
    {
        for (IContributionItem item : root.getItems())
        {
            if (!(item instanceof MenuManager))
                continue;
            MenuManager sub = (MenuManager) item;
            if (isEventsMenuText(sub.getMenuText()))
                return sub;
        }
        return null;
    }

    private static void hookEventsMenuManager(MenuManager eventsMenu)
    {
        if (!hookedEventsMenus.add(eventsMenu))
            return;
        eventsMenu.addMenuListener(FormEditorHook::onEventsMenuAboutToShow);
    }

    /** Синхронно, до заполнения SWT-меню: разворачиваем compound и сортируем. */
    private static void onEventsMenuAboutToShow(IMenuManager eventsMenu)
    {
        sortEventItems(eventsMenu);
    }

    // -----------------------------------------------------------------------
    // Переупорядочивание подменю
    // -----------------------------------------------------------------------

    private static void sortEventItems(IMenuManager eventsMenu)
    {
        List<IContributionItem> items = collectExpandedItems(eventsMenu);
        if (items.size() <= 1)
            return;

        List<IContributionItem> sorted = new ArrayList<>(items);
        sorted.sort((a, b) ->
        {
            int orderA = getEventOrder(a);
            int orderB = getEventOrder(b);
            if (orderA != orderB)
                return Integer.compare(orderA, orderB);
            return getItemLabel(a).compareToIgnoreCase(getItemLabel(b));
        });

        if (items.equals(sorted))
            return;

        eventsMenu.removeAll();
        for (IContributionItem item : sorted)
            eventsMenu.add(item);
    }

    /** Разворачивает {@link CompoundContributionItem} EDT в плоский список пунктов. */
    private static List<IContributionItem> collectExpandedItems(IMenuManager menu)
    {
        List<IContributionItem> result = new ArrayList<>();
        for (IContributionItem item : menu.getItems())
        {
            if (item == null || !item.isVisible())
                continue;
            if (item instanceof CompoundContributionItem)
            {
                IContributionItem[] sub = invokeGetContributionItems((CompoundContributionItem) item);
                if (sub == null)
                    continue;
                for (IContributionItem child : sub)
                {
                    if (child != null && child.isVisible())
                        result.add(child);
                }
            }
            else
            {
                result.add(item);
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Утилиты
    // -----------------------------------------------------------------------

    /** {@link CompoundContributionItem#getContributionItems()} — protected. */
    private static IContributionItem[] invokeGetContributionItems(CompoundContributionItem item)
    {
        try
        {
            Method m = CompoundContributionItem.class.getDeclaredMethod("getContributionItems"); //$NON-NLS-1$
            m.setAccessible(true);
            return (IContributionItem[]) m.invoke(item);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static boolean isEventsMenuText(String text)
    {
        return EVENTS_SUBMENU_TEXT.equals(stripMnemonics(text));
    }

    private static String stripMnemonics(String text)
    {
        if (text == null)
            return ""; //$NON-NLS-1$
        return text.replace("&", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int getEventOrder(IContributionItem item)
    {
        String label = getItemLabel(item);
        if (label.isEmpty())
            return Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : FORM_EVENT_ORDER.entrySet())
            if (label.contains(entry.getKey()))
                return entry.getValue();
        return Integer.MAX_VALUE;
    }

    private static String getItemLabel(IContributionItem item)
    {
        if (item == null)
            return ""; //$NON-NLS-1$

        String eventName = getCommandParameter(item, EDT_EVENT_NAME_PARAM);
        if (!eventName.isEmpty())
            return eventName;

        try
        {
            Class<?> cls = item.getClass();
            while (cls != null && cls != Object.class)
            {
                for (java.lang.reflect.Field f : cls.getDeclaredFields())
                {
                    String name = f.getName();
                    if (!"label".equals(name) && !"text".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
                        continue;
                    f.setAccessible(true);
                    Object v = f.get(item);
                    if (v instanceof String && !((String) v).isEmpty())
                        return (String) v;
                }
                cls = cls.getSuperclass();
            }
        }
        catch (Exception ignored) {}

        String id = item.getId();
        return id != null ? id : ""; //$NON-NLS-1$
    }

    private static String getCommandParameter(IContributionItem item, String key)
    {
        try
        {
            Class<?> cls = item.getClass();
            while (cls != null && cls != Object.class)
            {
                for (java.lang.reflect.Field f : cls.getDeclaredFields())
                {
                    if (!"parameters".equals(f.getName()) //$NON-NLS-1$
                            && !"commandParameterMap".equals(f.getName())) //$NON-NLS-1$
                        continue;
                    f.setAccessible(true);
                    Object v = f.get(item);
                    if (!(v instanceof Map))
                        continue;
                    Object value = ((Map<?, ?>) v).get(key);
                    if (value instanceof String && !((String) value).isEmpty())
                        return (String) value;
                }
                cls = cls.getSuperclass();
            }
        }
        catch (Exception ignored) {}
        return ""; //$NON-NLS-1$
    }

    private static Map<String, Integer> buildOrderMap(String... names)
    {
        Map<String, Integer> map = new HashMap<>(names.length * 2);
        for (int i = 0; i < names.length; i++)
            map.put(names[i], i);
        return map;
    }
}
