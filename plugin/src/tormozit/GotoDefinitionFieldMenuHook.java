package tormozit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;

import com._1c.g5.v8.dt.ui.util.OpenHelper;
import com._1c.g5.v8.dt.md.ui.shared.MdUiSharedImages;

/**
 * Глобальный пункт контекстного меню «Перейти к определению» для ЛЮБОГО поля ввода
 * (Text/StyledText/Combo): если в тексте кликнутого control встречается имя типа с точкой
 * (напр. «СправочникСсылка.Валюты»), добавляет пункт меню. Для единственной ссылки
 * также доступны открытие объекта и показ в навигаторе.
 *
 * <p>Намеренно НЕ использует никакого сканирования строк/секций/canvas/геометрии — только
 * {@link PropertySheetControlInterop#controlText(Control)} на самом кликнутом control,
 * тем же способом, каким {@link GoToDefinition} берёт текст из активного редактора.
 */
public final class GotoDefinitionFieldMenuHook implements IStartup
{
    private static final String MENU_HOOK_MARKER = "tormozit.gotoDefFieldMenuHooked"; //$NON-NLS-1$
    private static final String ITEM_MARKER = "tormozit.gotoDefFieldMenuItem"; //$NON-NLS-1$

    /** Сегмент — идентификатор МД: заглавная буква, необязательно с ведущим {@code _}. */
    private static final Pattern TYPE_REF_PATTERN = Pattern.compile(
            "_?\\p{Lu}[\\p{L}\\p{N}_]*(?:\\._?\\p{Lu}[\\p{L}\\p{N}_]*)+"); //$NON-NLS-1$

    /**
     * Последний реально сфокусированный текстовый control и его текст на момент потери фокуса
     * (или последнего обновления). В LWT-панелях (напр. «Свойства») к моменту правого клика
     * поле-редактор часто уже не существует как отдельный control (canvas перерисовался) —
     * событие MenuDetect тогда приходит на общий canvas, а не на StyledText. Поэтому запоминаем
     * последний фокус ЗАРАНЕЕ (на FocusIn/FocusOut), а не пытаемся найти его в момент клика.
     */
    private static Control lastFocusedControl;
    private static String lastFocusedText = ""; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.addFilter(SWT.MenuDetect, GotoDefinitionFieldMenuHook::handleMenuDetect);
            display.addFilter(SWT.FocusIn, GotoDefinitionFieldMenuHook::handleFocusIn);
            display.addFilter(SWT.FocusOut, GotoDefinitionFieldMenuHook::handleFocusOut);
        });
    }

    private static void handleFocusIn(Event event)
    {
        if (!(event.widget instanceof Control control) || control.isDisposed())
            return;
        if (!isSupportedControl(control))
            return;
        lastFocusedControl = control;
        lastFocusedText = PropertySheetControlInterop.controlText(control);
    }

    /** Обновляем текст на потере фокуса — пользователь мог отредактировать поле перед кликом. */
    private static void handleFocusOut(Event event)
    {
        if (!(event.widget instanceof Control control) || control.isDisposed())
            return;
        if (control != lastFocusedControl)
            return;
        lastFocusedText = PropertySheetControlInterop.controlText(control);
    }

    private static void handleMenuDetect(Event event)
    {
        if (!(event.widget instanceof Control control) || control.isDisposed())
            return;

        if (isSupportedControl(control))
        {
            if (control.getMenu() != null)
            {
                hookControlMenu(control);
                return;
            }
            tryShowStandalone(event, control, PropertySheetControlInterop.controlText(control));
            return;
        }

        // Фолбэк — по белому списку, а не по чёрному: применяется только к LWT-хостящим
        // canvas-виджетам (см. {@link #isLwtHostedControl}), где отдельного текстового
        // control в состоянии покоя не существует (напр. канвас панели «Свойства»,
        // com._1c.g5.lwt.interop.SwtLightComposite). Для любых нативных SWT-виджетов со своим
        // штатным меню (дерево реквизитов объекта метаданных, таблицы, вкладки workbench и
        // т.п.) фолбэк не сработает в принципе — вести список исключений не нужно.
        if (!isLwtHostedControl(control))
            return;

        // Если у control уже есть собственное меню — дополняем его, а не заменяем отдельным
        // всплывающим меню (иначе штатные пункты этого меню пропадают у пользователя из виду).
        if (control.getMenu() != null)
        {
            hookControlMenu(control);
            return;
        }

        // event.widget — не текстовый control (напр. общий canvas LWT-панели «Свойства»,
        // где отдельного StyledText в состоянии покоя не существует). Используем текст
        // последнего сфокусированного поля, запомненный заранее через FocusIn/FocusOut —
        // тем же приёмом, каким GoToDefinition берёт текст «из активного редактора», а не
        // из точки клика.
        if (lastFocusedControl == null || lastFocusedControl.isDisposed())
            return;
        tryShowStandalone(event, control, lastFocusedText);
    }

    /** Показывает отдельное меню на месте клика, если {@code text} содержит имя типа. */
    private static void tryShowStandalone(Event event, Control clickedControl, String text)
    {
        String typeRef = findTypeRef(text);
        if (typeRef == null)
            return;
        event.doit = false;
        org.eclipse.swt.graphics.Point at = event.x != 0 || event.y != 0
                ? new org.eclipse.swt.graphics.Point(event.x, event.y)
                : clickedControl.getDisplay().getCursorLocation();
        // В GoToDefinition.jump передаём ВЕСЬ текст поля, а не только найденный фрагмент —
        // для составных типов («СправочникСсылка.А, ПеречислениеСсылка.Б») jump сам умеет
        // разобрать список через запятую и предложить выбор; typeRef здесь — только признак
        // «стоит показывать пункт» и текст подсказки.
        showStandaloneMenu(clickedControl, at, text, typeRef);
    }

    /** Отдельное всплывающее меню — для control без своего SWT Menu. */
    private static void showStandaloneMenu(Control control, org.eclipse.swt.graphics.Point at,
            String fullText, String typeRef)
    {
        Menu menu = new Menu(control.getShell(), SWT.POP_UP);
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                "Перейти к определению", "tormozit.GoToDefinition")); //$NON-NLS-1$ //$NON-NLS-2$
        ComfortSubmenuHelper.setMenuItemTooltip(item, "Перейти к определению " + typeRef); //$NON-NLS-1$
        item.addListener(SWT.Selection, e -> control.getDisplay().asyncExec(
                () -> runGotoDefinitionAction(control, fullText)));
        addSingleReferenceActions(menu, control, fullText);
        menu.setLocation(at);
        menu.setVisible(true);
        menu.addListener(SWT.Hide, e -> control.getDisplay().asyncExec(() -> {
            if (!menu.isDisposed())
                menu.dispose();
        }));
    }

    private static boolean isSupportedControl(Control control)
    {
        return control instanceof Text || control instanceof StyledText || control instanceof Combo;
    }

    /**
     * LWT-виджет (напр. {@code com._1c.g5.lwt.interop.SwtLightComposite} — канвас панели
     * «Свойства»: MouseDown/MenuDetect приходят на host, а не на дочерние поля).
     * Позитивно опознаёт поле ввода LWT-фреймворка, а не «всё, кроме известных
     * структурных виджетов».
     */
    private static boolean isLwtHostedControl(Control control)
    {
        if (control == null)
            return false;
        String cn = control.getClass().getName();
        return cn.contains(".lwt.") || cn.contains("LightLabel") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("LightText") || cn.contains("ILightControl"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void hookControlMenu(Control control)
    {
        if (control.isDisposed())
            return;
        Menu menu = control.getMenu();
        if (menu == null || menu.isDisposed())
            return;
        if (Boolean.TRUE.equals(menu.getData(MENU_HOOK_MARKER)))
            return;
        menu.setData(MENU_HOOK_MARKER, Boolean.TRUE);
        menu.addMenuListener(new MenuAdapter()
        {
            @Override
            public void menuShown(MenuEvent e)
            {
                injectIfApplicable((Menu) e.widget, control);
            }
        });
    }

    /** Удаляет наш прошлый пункт (текст мог измениться) и добавляет заново, если применимо. */
    private static void injectIfApplicable(Menu menu, Control control)
    {
        updateReferenceMenu(menu, control, PropertySheetControlInterop.controlText(control));
    }

    /** Общее меню ссылок для полей ввода и ячеек типов, текст которых получен из модели. */
    static void updateReferenceMenu(Menu menu, Control control, String text)
    {
        updateReferenceMenu(menu, control, text, false, false, null);
    }

    static void updateTypeReferenceMenu(Menu menu, Control control, String text, boolean rootMenu,
        Runnable openTypes)
    {
        updateReferenceMenu(menu, control, text, true, rootMenu, openTypes);
    }

    static boolean isReferenceMenuItem(MenuItem item)
    {
        return Boolean.TRUE.equals(item.getData(ITEM_MARKER));
    }

    private static void updateReferenceMenu(Menu menu, Control control, String text, boolean typeCell,
        boolean rootMenu, Runnable openTypes)
    {
        if (menu == null || menu.isDisposed())
            return;
        for (MenuItem item : menu.getItems())
        {
            if (Boolean.TRUE.equals(item.getData(ITEM_MARKER)) && !item.isDisposed())
                item.dispose();
        }
        // Уже существующее подменю «Комфорт» (напр. в контекстном меню BSLEditor) — если оно
        // есть, добавляем пункт туда, а не отдельной строкой в корень меню. Само подменю здесь
        // никогда не создаём — там, где его нет, наш пункт остаётся на верхнем уровне меню.
        Menu comfortSub = ComfortSubmenuHelper.findExistingComfortSubmenu(menu);
        if (comfortSub != null)
        {
            for (MenuItem item : comfortSub.getItems())
            {
                if (Boolean.TRUE.equals(item.getData(ITEM_MARKER)) && !item.isDisposed())
                    item.dispose();
            }
        }

        String typeRef = findTypeRef(text);
        if (typeRef == null && openTypes == null)
            return;

        Menu targetMenu = !rootMenu && comfortSub != null ? comfortSub : menu;
        int insertIndex = typeCell && targetMenu == menu ? ValueTypeColumnMenu.rootInsertIndex(menu) : -1;
        if (targetMenu == menu && insertIndex < 0)
        {
            MenuItem separator = new MenuItem(menu, SWT.SEPARATOR);
            separator.setData(ITEM_MARKER, Boolean.TRUE);
        }
        if (typeCell && addSingleReferenceActions(targetMenu, control, text, true, insertIndex))
            return;
        MenuItem item = createReferenceMenuItem(targetMenu,
            typeCell ? "Открыть типы" : ComfortSubmenuHelper.menuItemTextWithKeyBinding( //$NON-NLS-1$
                "Перейти к определению", "tormozit.GoToDefinition"), insertIndex); //$NON-NLS-1$ //$NON-NLS-2$
        item.setData(ITEM_MARKER, Boolean.TRUE);
        if (typeCell)
            item.setImage(MdUiSharedImages.getImage(MdUiSharedImages.OBJS_TYPE_DESCRIPTION));
        ComfortSubmenuHelper.setMenuItemTooltip(item, typeCell
            ? "Открыть просмотр описания типов с возможностью перехода к объектам метаданных" //$NON-NLS-1$
                : "Перейти к определению " + typeRef); //$NON-NLS-1$
        // Что именно передаётся дальше — см. runGotoDefinitionAction: для StyledText текст
        // поля не используется (штатная команда сама берёт выражение из каретки), для
        // остальных полей — передаётся целиком, GoToDefinition.jump сам разбирает составные
        // описания типов через запятую.
        item.addListener(SWT.Selection, e -> control.getDisplay().asyncExec(
                () -> {
                    if (openTypes != null)
                        openTypes.run();
                    else
                        runGotoDefinitionAction(control, text);
                }));
        if (!typeCell)
            addSingleReferenceActions(targetMenu, control, text, false);
    }

    /** Обе команды работают с единственной ссылкой поля, независимо от положения каретки. */
    private static void addSingleReferenceActions(Menu menu, Control control, String text)
    {
        addSingleReferenceActions(menu, control, text, false);
    }

    private static boolean addSingleReferenceActions(Menu menu, Control control, String text, boolean typeCell)
    {
        return addSingleReferenceActions(menu, control, text, typeCell, -1);
    }

    private static boolean addSingleReferenceActions(Menu menu, Control control, String text, boolean typeCell,
        int insertIndex)
    {
        String reference = findSingleTypeRef(text);
        if (reference == null)
            return false;
        MenuItem open = createReferenceMenuItem(menu,
                typeCell ? "Открыть объект типа" : "Открыть объект", insertIndex); //$NON-NLS-1$ //$NON-NLS-2$
        open.setData(ITEM_MARKER, Boolean.TRUE);
        if (typeCell)
            open.setImage(MdUiSharedImages.getImage(MdUiSharedImages.OBJS_TYPE_DESCRIPTION));
        ComfortSubmenuHelper.setMenuItemTooltip(open, "Открыть объект " + reference); //$NON-NLS-1$
        open.addListener(SWT.Selection, e -> control.getDisplay().asyncExec(
                () -> runSingleReferenceAction(control, reference, false)));
        MenuItem reveal = createReferenceMenuItem(menu,
                typeCell ? "Показать тип в навигаторе" : "Показать в навигаторе", //$NON-NLS-1$ //$NON-NLS-2$
                insertIndex >= 0 ? insertIndex + 1 : -1);
        reveal.setData(ITEM_MARKER, Boolean.TRUE);
        if (typeCell)
            reveal.setImage(MdUiSharedImages.getImage(MdUiSharedImages.OBJS_TYPE_DESCRIPTION));
        ComfortSubmenuHelper.setMenuItemTooltip(reveal, "Показать в навигаторе " + reference); //$NON-NLS-1$
        reveal.addListener(SWT.Selection, e -> control.getDisplay().asyncExec(
                () -> runSingleReferenceAction(control, reference, true)));
        return true;
    }

    private static MenuItem createReferenceMenuItem(Menu menu, String text, int insertIndex)
    {
        if (insertIndex < 0)
            return ComfortSubmenuHelper.createSortedMenuItem(menu, SWT.PUSH, text);
        MenuItem item = new MenuItem(menu, SWT.PUSH, Math.min(insertIndex, menu.getItemCount()));
        item.setText(text);
        return item;
    }

    private static void runSingleReferenceAction(Control control, String reference, boolean reveal)
    {
        if (control.isDisposed())
            return;
        String title = reveal ? "Показать в навигаторе" : "Открыть объект"; //$NON-NLS-1$ //$NON-NLS-2$
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null
                ? PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage() : null;
        IProject project = Global.getActiveProject(page, true);
        if (project == null)
            return;
        try
        {
            int dot = reference.indexOf('.');
            String base = GoToDefinition.TYPE_SUFFIX_MAP.get(reference.substring(0, dot));
            String fullName = base != null ? base + reference.substring(dot) : reference;
            EObject object = GoToDefinition.resolveEObjectForFullName(fullName, page, project);
            if (object == null)
            {
                ToastNotification.show(title,
                        "В проекте " + project.getName() + " не найдена ссылка:\n" + reference, 5000); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            if (reveal)
                NavigatorReveal.revealAndActivateIfHidden(object);
            else
                new OpenHelper().openEditor(object);
        }
        catch (Exception e)
        {
            Global.logError("GotoDefinitionFieldMenuHook", title, e); //$NON-NLS-1$
            ToastNotification.show(title, "Не удалось выполнить команду: " + e.getMessage(), 5000); //$NON-NLS-1$
        }
    }

    /**
     * Для текстового документа ({@link StyledText}, напр. BSL-редактор) текст поля целиком
     * почти всегда бесполезен как ссылка — передаём управление штатной команде
     * {@code tormozit.GoToDefinition}, которая сама берёт выражение из позиции каретки/
     * выделения в активном редакторе. Для прочих полей ввода (Text/Combo) по-прежнему
     * передаём весь текст поля напрямую в {@link GoToDefinition#jump}.
     */
    private static void runGotoDefinitionAction(Control control, String fullText)
    {
        if (control instanceof StyledText)
            runGotoDefinitionCommand();
        else
            runGotoDefinition(control, fullText);
    }

    private static void runGotoDefinitionCommand()
    {
        try
        {
            IHandlerService handlerService = PlatformUI.getWorkbench().getService(IHandlerService.class);
            if (handlerService != null)
                handlerService.executeCommand("tormozit.GoToDefinition", null); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.logError("GotoDefinitionFieldMenuHook", "runGotoDefinitionCommand", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void runGotoDefinition(Control control, String fullText)
    {
        if (control == null || control.isDisposed())
            return;
        Shell shell = control.getShell();
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null
                ? PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage() : null;
        IProject project = Global.getActiveProject(page, false);
        if (project == null)
        {
            ToastNotification.show("Перейти к определению", "Отсутствует активный проект"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        // allowIrAutoConnect=false: пункт контекстного меню поля ввода не должен запускать
        // подключение ИР. Уже подключённая сессия при этом по-прежнему используется для
        // нормализации ссылок форм/макетов.
        if (!GoToDefinition.jump(fullText, shell, page, project, false)
            && !GoToDefinition.consumeJumpCancelled())
        {
            ToastNotification.show("Перейти к определению", //$NON-NLS-1$
                    "В проекте " + project.getName() + " не найдена ссылка:\n" + fullText, 5000); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Ищет в {@code text} подстроку вида «Идентификатор.Идентификатор», у которой первый
     * сегмент — известный корневой тип МД ({@link MdTypeMapping#isKnownMdRootType}) либо
     * известный суффикс данных ({@link GoToDefinition#TYPE_SUFFIX_MAP}).
     */
    private static String findTypeRef(String text)
    {
        return findTypeRef(text, false);
    }

    private static String findSingleTypeRef(String text)
    {
        return findTypeRef(text, true);
    }

    private static String findTypeRef(String text, boolean singleOnly)
    {
        if (text == null || text.isBlank())
            return null;
        Matcher m = TYPE_REF_PATTERN.matcher(text);
        String found = null;
        while (m.find())
        {
            String candidate = m.group();
            int dot = candidate.indexOf('.');
            String firstSeg = dot > 0 ? candidate.substring(0, dot) : candidate;
            if (MdTypeMapping.isKnownMdRootType(firstSeg)
                    || MdTypeMapping.ruPluralToRu(firstSeg) != null
                    || GoToDefinition.TYPE_SUFFIX_MAP.containsKey(firstSeg))
            {
                if (!singleOnly)
                    return candidate;
                if (found != null)
                    return null;
                found = candidate;
            }
        }
        return found;
    }
}
