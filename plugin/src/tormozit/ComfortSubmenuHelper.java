package tormozit;

import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.TriggerSequence;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.internal.keys.BindingService;
import org.eclipse.ui.keys.IBindingService;

/**
 * Общее подменю «Комфорт» в контекстных menю (find-or-create, без дубликатов).
 */
final class ComfortSubmenuHelper
{
    static final String SUBMENU_TEXT = "Комфорт"; //$NON-NLS-1$
    static final String SUBMENU_MARKER = "tormozit.comfortSubmenu"; //$NON-NLS-1$

    private static final String DEFAULT_SCHEME_ID =
            "org.eclipse.ui.defaultAcceleratorConfiguration"; //$NON-NLS-1$

    private ComfortSubmenuHelper() {}

    /**
     * Текст пункта SWT-меню с колонкой сочетания клавиш ({@code label\tCtrl+…}).
     * Берёт effective-привязку из {@link IBindingService} для активного контекста.
     */
    static String menuItemTextWithKeyBinding(String label, String commandId)
    {
        return menuItemTextWithKeyBinding(label, commandId, null);
    }

    /**
     * То же с fallback по {@code contextId}, если active-bindings пусты
     * (например модальный «Редактор запроса»).
     */
    static String menuItemTextWithKeyBinding(String label, String commandId, String contextId)
    {
        if (label == null || commandId == null || commandId.isEmpty())
            return label;

        for (TriggerSequence sequence : resolveKeySequences(commandId, contextId))
        {
            if (sequence == null)
                continue;
            String formatted = sequence.format();
            if (formatted != null && !formatted.isEmpty())
                return label + '\t' + formatted;
        }
        return label;
    }

    /**
     * Текст {@link org.eclipse.swt.widgets.ToolItem} с сочетанием клавиш через пробел
     * ({@code label F2}) — таб в toolbar на Windows не даёт колонку.
     */
    static String toolbarItemTextWithKeyBinding(String label, String commandId)
    {
        return toolbarItemTextWithKeyBinding(label, commandId, null);
    }

    static String toolbarItemTextWithKeyBinding(String label, String commandId, String contextId)
    {
        if (label == null || commandId == null || commandId.isEmpty())
            return label;

        for (TriggerSequence sequence : resolveKeySequences(commandId, contextId))
        {
            if (sequence == null)
                continue;
            String formatted = sequence.format();
            if (formatted != null && !formatted.isEmpty())
                return label + ' ' + formatted;
        }
        return label;
    }

    static TriggerSequence[] resolveKeySequences(String commandId, String contextId)
    {
        Set<TriggerSequence> result = new LinkedHashSet<>();
        try
        {
            IBindingService bindingService =
                PlatformUI.getWorkbench().getService(IBindingService.class);
            if (bindingService != null)
            {
                TriggerSequence[] active = bindingService.getActiveBindingsFor(commandId);
                if (active != null)
                {
                    for (TriggerSequence sequence : active)
                    {
                        if (sequence != null)
                            result.add(sequence);
                    }
                }
            }

            if (contextId != null && !contextId.isBlank())
            {
                BindingService internal = resolveBindingServiceInternal(bindingService);
                if (internal != null)
                    collectConfiguredSequences(internal, commandId, contextId, result);
            }
        }
        catch (Exception ignored)
        {
        }
        return result.toArray(new TriggerSequence[0]);
    }

    private static BindingService resolveBindingServiceInternal(IBindingService bindingService)
    {
        if (bindingService instanceof BindingService internal)
            return internal;
        try
        {
            IBindingService service =
                PlatformUI.getWorkbench().getService(IBindingService.class);
            if (service instanceof BindingService internal)
                return internal;
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    private static void collectConfiguredSequences(
            BindingService bindingService,
            String commandId,
            String contextId,
            Set<TriggerSequence> result)
    {
        String activeScheme = bindingService.getActiveScheme() != null
            ? bindingService.getActiveScheme().getId()
            : DEFAULT_SCHEME_ID;
        String platform = bindingService.getPlatform();
        String locale = bindingService.getLocale();

        for (Binding binding : bindingService.getBindings())
        {
            if (binding == null)
                continue;
            String schemeId = binding.getSchemeId();
            if (!activeScheme.equals(schemeId) && !DEFAULT_SCHEME_ID.equals(schemeId))
                continue;
            if (!contextId.equals(binding.getContextId()))
                continue;

            ParameterizedCommand pc = binding.getParameterizedCommand();
            if (pc == null || !commandId.equals(pc.getId()))
                continue;
            if (!matchesPlatformLocale(binding, platform, locale))
                continue;

            TriggerSequence trigger = binding.getTriggerSequence();
            if (trigger != null)
                result.add(trigger);
        }
    }

    private static boolean matchesPlatformLocale(Binding binding, String platform, String locale)
    {
        String bindingPlatform = binding.getPlatform();
        if (bindingPlatform != null && !bindingPlatform.isBlank() && !bindingPlatform.equals(platform))
            return false;
        String bindingLocale = binding.getLocale();
        return bindingLocale == null || bindingLocale.isBlank() || bindingLocale.equals(locale);
    }

    /**
     * Ищет пункт «Open With» / «Open with» / «Открыть с помощью» (CASCADE) в меню навигатора.
     */
    static MenuItem findOpenWithCascade(Menu menu)
    {
        if (menu == null || menu.isDisposed())
            return null;
        for (MenuItem item : menu.getItems())
        {
            if (item.isDisposed() || (item.getStyle() & SWT.CASCADE) == 0)
                continue;
            String text = item.getText();
            if (text == null)
                continue;
            String normalized = text.replace("&", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
            if (normalized.equalsIgnoreCase("Open With") //$NON-NLS-1$
                    || normalized.equalsIgnoreCase("Open with") //$NON-NLS-1$
                    || normalized.equalsIgnoreCase("Открыть с помощью")) //$NON-NLS-1$
                return item;
        }
        return null;
    }

    /**
     * Возвращает anchor-элемент, после которого следует вставлять подменю «Комфорт» —
     * последний пункт группы edit (перед вторым разделителем после «Open With»).
     * Если определить позицию не удалось, возвращает {@code null} (вставка в конец).
     */
    static MenuItem findAnchorAfterEditGroup(Menu menu)
    {
        MenuItem openWith = findOpenWithCascade(menu);
        if (openWith == null || openWith.isDisposed())
            return null;
        MenuItem[] items = menu.getItems();
        int startIndex = menu.indexOf(openWith);
        if (startIndex < 0)
            return null;
        int separatorCount = 0;
        for (int i = startIndex + 1; i < items.length; i++)
        {
            if (items[i].isDisposed())
                continue;
            if ((items[i].getStyle() & SWT.SEPARATOR) != 0)
            {
                separatorCount++;
                if (separatorCount == 2)
                    return i > 0 ? items[i - 1] : null;
            }
        }
        return null;
    }

    static Menu findOrCreateComfortSubmenu(Menu parentMenu, Shell shell)
    {
        return findOrCreateComfortSubmenu(parentMenu, shell, null);
    }

    /**
     * Находит существующее подменю «Комфорт» в {@code parentMenu}, ничего не создавая.
     * Используется там, где новый пункт должен попадать в уже имеющееся подменю «Комфорт»
     * (например, в контекстном меню {@code BSLEditor}), но не должен провоцировать его
     * создание в меню, где подменю не предусмотрено (обычные текстовые поля и т.п.).
     */
    static Menu findExistingComfortSubmenu(Menu parentMenu)
    {
        if (parentMenu == null || parentMenu.isDisposed())
            return null;
        for (MenuItem item : parentMenu.getItems())
        {
            if (item.isDisposed())
                continue;
            if (SUBMENU_TEXT.equals(item.getText()) && (item.getStyle() & SWT.CASCADE) != 0)
            {
                Menu sub = item.getMenu();
                if (sub != null && !sub.isDisposed())
                    return sub;
            }
            if (Boolean.TRUE.equals(item.getData(SUBMENU_MARKER)))
            {
                Menu sub = item.getMenu();
                if (sub != null && !sub.isDisposed())
                    return sub;
            }
        }
        return null;
    }

    /**
     * Находит существующее подменю «Комфорт» в {@code parentMenu} либо создаёт новое.
     * Если {@code anchor} не {@code null} и подменю не найдено, новый пункт вставляется
     * сразу после {@code anchor} (в противном случае — в конец).
     */
    static Menu findOrCreateComfortSubmenu(Menu parentMenu, Shell shell, MenuItem anchor)
    {
        if (parentMenu == null || parentMenu.isDisposed())
            return null;

        Menu existing = findExistingComfortSubmenu(parentMenu);
        if (existing != null)
            return existing;

        int index = anchor != null && !anchor.isDisposed() && anchor.getParent() == parentMenu
            ? parentMenu.indexOf(anchor) + 1
            : -1;
        MenuItem comfortRoot = index >= 0
            ? new MenuItem(parentMenu, SWT.CASCADE, index)
            : new MenuItem(parentMenu, SWT.CASCADE);
        comfortRoot.setText(SUBMENU_TEXT);
        comfortRoot.setData(SUBMENU_MARKER, Boolean.TRUE);
        Menu comfortSub = new Menu(shell != null && !shell.isDisposed() ? shell : parentMenu.getShell(), SWT.DROP_DOWN);
        comfortRoot.setMenu(comfortSub);
        return comfortSub;
    }
}

