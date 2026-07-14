package tormozit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

/**
 * Глобальный пункт контекстного меню «Перейти к определению» для ЛЮБОГО поля ввода
 * (Text/StyledText/Combo): если в тексте кликнутого control встречается имя типа с точкой
 * (напр. «СправочникСсылка.Валюты»), добавляет пункт меню.
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

        // Фолбэк предназначен только для LWT-канвасов панели «Свойства» (см. javadoc класса),
        // где отдельного текстового control в состоянии покоя не существует. Структурные виджеты
        // со своим штатным контекстным меню (дерево реквизитов объекта метаданных, таблицы и т.п.)
        // исключаем — иначе наш пункт подменяет собой их меню вместо того чтобы его дополнять.
        if (control instanceof Tree || control instanceof Table || control instanceof List)
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

    /** Отдельное всплывающее меню с единственным пунктом — для control без своего SWT Menu. */
    private static void showStandaloneMenu(Control control, org.eclipse.swt.graphics.Point at,
            String fullText, String typeRef)
    {
        Menu menu = new Menu(control.getShell(), SWT.POP_UP);
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                "Перейти к определению", "tormozit.GoToDefinition")); //$NON-NLS-1$ //$NON-NLS-2$
        item.setToolTipText("Перейти к определению " + typeRef + Global.pluginSignForTooltip()); //$NON-NLS-1$
        item.addListener(SWT.Selection, e -> control.getDisplay().asyncExec(
                () -> runGotoDefinition(control, fullText)));
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
        if (menu == null || menu.isDisposed())
            return;
        for (MenuItem item : menu.getItems())
        {
            if (Boolean.TRUE.equals(item.getData(ITEM_MARKER)) && !item.isDisposed())
                item.dispose();
        }

        String text = PropertySheetControlInterop.controlText(control);
        String typeRef = findTypeRef(text);
        if (typeRef == null)
            return;

        MenuItem separator = new MenuItem(menu, SWT.SEPARATOR);
        separator.setData(ITEM_MARKER, Boolean.TRUE);
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setData(ITEM_MARKER, Boolean.TRUE);
        item.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                "Перейти к определению", "tormozit.GoToDefinition")); //$NON-NLS-1$ //$NON-NLS-2$
        item.setToolTipText("Перейти к определению " + typeRef + Global.pluginSignForTooltip()); //$NON-NLS-1$
        // В команду передаём ВЕСЬ текст поля (см. tryShowStandalone) — GoToDefinition.jump сам
        // разбирает составные описания типов через запятую.
        item.addListener(SWT.Selection, e -> control.getDisplay().asyncExec(
                () -> runGotoDefinition(control, text)));
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
        if (!GoToDefinition.jump(fullText, shell, page, project) && !GoToDefinition.consumeJumpCancelled())
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
        if (text == null || text.isBlank())
            return null;
        Matcher m = TYPE_REF_PATTERN.matcher(text);
        while (m.find())
        {
            String candidate = m.group();
            int dot = candidate.indexOf('.');
            String firstSeg = dot > 0 ? candidate.substring(0, dot) : candidate;
            if (MdTypeMapping.isKnownMdRootType(firstSeg)
                    || GoToDefinition.TYPE_SUFFIX_MAP.containsKey(firstSeg))
                return candidate;
        }
        return null;
    }
}
