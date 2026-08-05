package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Пункт «Показать в навигаторе» в подменю «Комфорт» контекстного меню редактора XML
 * (страница Source редактора {@code XMLMultiPageEditorPart} WST, а также обычный текстовый
 * редактор Workbench с открытым {@code .xml}).
 *
 * <p>Действие и сама команда (CTRL+T) — в {@link XmlEditorShowInNavigatorHandler}.
 */
public class XmlEditorShowInNavigatorHook implements IStartup
{
    private static final String HOOK_MARKER = "tormozit.xmlEditorNavigatorMenuHooked"; //$NON-NLS-1$

    private final Map<IWorkbenchWindow, IPartListener2> partListeners = new HashMap<>();

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)      { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });

            for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows())
                hookWindow(w);
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        if (window == null || partListeners.containsKey(window))
            return;

        IPartListener2 listener = new IPartListener2()
        {
            @Override
            public void partOpened(org.eclipse.ui.IWorkbenchPartReference ref)
            {
                Display.getDefault().asyncExec(() -> hookEditor(ref.getPart(false)));
            }

            @Override
            public void partActivated(org.eclipse.ui.IWorkbenchPartReference ref)
            {
                Display.getDefault().asyncExec(() -> hookEditor(ref.getPart(false)));
            }

            @Override public void partBroughtToTop(org.eclipse.ui.IWorkbenchPartReference r) {}
            @Override public void partClosed(org.eclipse.ui.IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(org.eclipse.ui.IWorkbenchPartReference r)  {}
            @Override public void partHidden(org.eclipse.ui.IWorkbenchPartReference r)       {}
            @Override public void partVisible(org.eclipse.ui.IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(org.eclipse.ui.IWorkbenchPartReference r) {}
        };

        partListeners.put(window, listener);
        window.getPartService().addPartListener(listener);

        if (window.getActivePage() != null)
        {
            for (IEditorPart ed : window.getActivePage().getEditors())
                hookEditor(ed);
        }
    }

    private void hookEditor(Object part)
    {
        if (!(part instanceof IEditorPart editor)
            || !XmlEditorShowInNavigatorHandler.isXmlEditor(editor))
            return;

        StyledText textWidget = resolveTextWidget(editor);
        if (textWidget == null || textWidget.isDisposed())
            return;

        Menu menu = textWidget.getMenu();
        if (menu == null || menu.isDisposed() || Boolean.TRUE.equals(menu.getData(HOOK_MARKER)))
            return;

        menu.setData(HOOK_MARKER, Boolean.TRUE);
        MenuAdapter listener = buildMenuListener(editor);
        menu.addMenuListener(listener);
        menu.addDisposeListener(e ->
        {
            if (!menu.isDisposed())
                menu.removeMenuListener(listener);
        });
    }

    private static StyledText resolveTextWidget(IEditorPart editor)
    {
        ITextEditor textEditor = TextEditor.resolveTextEditor(editor);
        if (textEditor == null)
            return null;
        ISourceViewer viewer = TextEditor.getSourceViewer(textEditor);
        return viewer != null ? viewer.getTextWidget() : null;
    }

    private static MenuAdapter buildMenuListener(IEditorPart editor)
    {
        return new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(1);

            @Override
            public void menuShown(MenuEvent e)
            {
                Menu menu = (Menu)e.widget;
                if (menu == null || menu.isDisposed())
                    return;

                Menu comfortSub = ComfortSubmenuHelper.findOrCreateComfortSubmenu(menu, menu.getShell());
                if (comfortSub == null)
                    return;

                EObject target = XmlEditorShowInNavigatorHandler.resolveTarget(editor);
                MenuItem item = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH,
                    ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                        XmlEditorShowInNavigatorHandler.MENU_LABEL,
                        XmlEditorShowInNavigatorHandler.COMMAND_ID,
                        XmlEditorShowInNavigatorHandler.BINDING_CONTEXT_ID));
                item.setToolTipText(
                    XmlEditorShowInNavigatorHandler.MENU_TOOLTIP + Global.pluginSignForTooltip());
                item.setEnabled(target != null);
                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        XmlEditorShowInNavigatorHandler.showInNavigator(target);
                    }
                });
                addedItems.add(item);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                Display display = ((Menu)e.widget).getDisplay();
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
}
