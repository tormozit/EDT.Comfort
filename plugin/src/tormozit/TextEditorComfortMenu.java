package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;

/**
 * Подменю «Комфорт» в контекстном меню текстовых редакторов.
 */
final class TextEditorComfortMenu
{
    private static final String SUBMENU_TEXT = ComfortSubmenuHelper.SUBMENU_TEXT;

    private TextEditorComfortMenu()
    {
    }

    static void attachWorkbench(
        StyledText textWidget,
        String hookMarker,
        IEditorPart editorPart,
        IWorkbenchPart part)
    {
        if (textWidget == null || textWidget.isDisposed())
            return;
        if (Boolean.TRUE.equals(textWidget.getData(hookMarker)))
            return;

        Menu menu = textWidget.getMenu();
        if (menu == null || menu.isDisposed())
            return;

        textWidget.setData(hookMarker, Boolean.TRUE);
        attachToMenu(menu, hookMarker, () ->
        {
            Shell shell = textWidget.getShell();
            return new MenuBinding(
                shell,
                () -> PasteWithCompareActions.isAvailable(shell, part, editorPart),
                () -> PasteWithCompareActions.run(shell, part, editorPart));
        });
    }

    static void attachContext(
        StyledText textWidget,
        String hookMarker,
        Supplier<TextEditorSupport.Context> contextSupplier)
    {
        if (textWidget == null || textWidget.isDisposed())
            return;
        if (Boolean.TRUE.equals(textWidget.getData(hookMarker)))
            return;

        Menu menu = textWidget.getMenu();
        if (menu == null || menu.isDisposed())
            return;

        textWidget.setData(hookMarker, Boolean.TRUE);
        attachToMenu(menu, hookMarker, () ->
        {
            Shell shell = textWidget.getShell();
            return new MenuBinding(
                shell,
                () -> PasteWithCompareActions.isAvailable(shell, contextSupplier.get()),
                () -> PasteWithCompareActions.run(shell, contextSupplier.get()));
        });
    }

    /**
     * Прикрепляет подменю к уже существующему {@link Menu} (ленивое меню embedded-редакторов).
     * Маркер хранится на объекте меню.
     */
    static void attachToMenu(
        Menu menu,
        String hookMarker,
        Supplier<MenuBinding> bindingSupplier)
    {
        if (menu == null || menu.isDisposed())
            return;
        if (Boolean.TRUE.equals(menu.getData(hookMarker)))
            return;

        menu.setData(hookMarker, Boolean.TRUE);

        MenuAdapter listener = buildMenuListener(bindingSupplier);
        menu.addMenuListener(listener);

        menu.addDisposeListener(e ->
        {
            if (!menu.isDisposed())
                menu.removeMenuListener(listener);
        });
    }

    private static MenuAdapter buildMenuListener(Supplier<MenuBinding> bindingSupplier)
    {
        return new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(4);

            @Override
            public void menuShown(MenuEvent e)
            {
                MenuBinding binding = bindingSupplier.get();
                if (binding == null)
                    return;

                Menu menu = (Menu) e.widget;

                Menu comfortSub = ComfortSubmenuHelper.findOrCreateComfortSubmenu(menu, menu.getShell());
                if (comfortSub == null)
                    return;

                MenuItem pasteItem = new MenuItem(comfortSub, SWT.PUSH);
                pasteItem.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                    PasteWithCompareActions.MENU_LABEL,
                    PasteWithCompareHandler.COMMAND_ID,
                    PasteWithCompareHandler.BINDING_CONTEXT_ID));
                pasteItem.setToolTipText(
                    "Сравнить выделение с буфером обмена и вставить результат"
                        + Global.pluginSignForTooltip());
                pasteItem.setEnabled(binding.isAvailable());
                pasteItem.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        binding.run();
                    }
                });
                addedItems.add(pasteItem);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                Display display = ((Menu) e.widget).getDisplay();
                List<MenuItem> toDispose = new ArrayList<>(addedItems);
                addedItems.clear();
                display.asyncExec(() ->
                {
                    for (MenuItem item : toDispose)
                    {
                        if (!item.isDisposed())
                            item.dispose();
                    }
                });
            }
        };
    }

    static final class MenuBinding
    {
        private final Shell shell;
        private final java.util.function.BooleanSupplier isAvailable;
        private final Runnable run;

        MenuBinding(Shell shell, java.util.function.BooleanSupplier isAvailable, Runnable run)
        {
            this.shell = shell;
            this.isAvailable = isAvailable;
            this.run = run;
        }

        boolean isAvailable()
        {
            return shell != null && !shell.isDisposed() && isAvailable.getAsBoolean();
        }

        void run()
        {
            if (shell != null && !shell.isDisposed())
                run.run();
        }
    }
}
