package tormozit;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.texteditor.ITextEditor;

import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;
import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.List;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * Подменю «Комфорт» в контекстном меню текстовых редакторов Workbench.
 */
public class TextEditorMenuHook implements IStartup
{
    private static final String HOOK_MARKER = "tormozit.textEditorComfortMenuHooked"; //$NON-NLS-1$

    private final Set<DtGranularEditor<?>> hookedGranularEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
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
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart ed = ref.getEditor(false);
                if (ed != null)
                    hookEditorIfNeeded(ed);
            }
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference))
                    return;
                IEditorPart ed = ((IEditorReference) ref).getEditor(false);
                if (ed != null)
                    hookEditorIfNeeded(ed);
            }

            @Override public void partActivated(IWorkbenchPartReference r)    {}
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private void hookEditorIfNeeded(IEditorPart editor)
    {
        if (editor instanceof ITextEditor textEditor)
            hookTextEditor(textEditor, editor, editor);
        else if (editor instanceof DtGranularEditor<?> granular)
            hookGranularEditor(granular);
    }

    private void hookTextEditor(ITextEditor textEditor, IEditorPart editorPart, IWorkbenchPart part)
    {
        Display.getDefault().asyncExec(() -> attachComfortMenu(textEditor, editorPart, part));
    }

    private void hookGranularEditor(DtGranularEditor<?> granular)
    {
        hookActiveTextPage(granular);

        if (!hookedGranularEditors.contains(granular))
        {
            IPageChangedListener listener = event -> hookActiveTextPage(granular);
            granular.addPageChangedListener(listener);
            hookedGranularEditors.add(granular);
        }
    }

    private void hookActiveTextPage(DtGranularEditor<?> granular)
    {
        IFormPage activePage = granular.getActivePageInstance();
        if (!(activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage))
            return;
        IEditorPart embedded = xtextPage.getEmbeddedEditor();
        if (embedded instanceof ITextEditor textEditor)
            hookTextEditor(textEditor, granular, granular);
    }

    private void attachComfortMenu(ITextEditor textEditor, IEditorPart editorPart, IWorkbenchPart part)
    {
        if (textEditor.getSite() == null)
            return;

        ISourceViewer viewer = TextEditor.getSourceViewer(textEditor);
        if (!(viewer instanceof SourceViewer sourceViewer))
        {
            Display.getDefault().asyncExec(() -> attachComfortMenu(textEditor, editorPart, part));
            return;
        }

        StyledText textWidget = sourceViewer.getTextWidget();
        if (textWidget == null || textWidget.isDisposed())
            return;

        if (Boolean.TRUE.equals(textWidget.getData(HOOK_MARKER)))
            return;

        if (textWidget.getMenu() == null)
        {
            Display.getDefault().asyncExec(() -> attachComfortMenu(textEditor, editorPart, part));
            return;
        }

        TextEditorComfortMenu.attachWorkbench(textWidget, HOOK_MARKER, editorPart, part);
        TextEditorIdentifierSelectionHook.attachToTextEditor(textEditor);
    }

    /**
     * Подменю «Комфорт» в контекстном меню текстовых редакторов.
     */
    private static final class TextEditorComfortMenu
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
            Supplier<TextEditor.Context> contextSupplier)
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

}
