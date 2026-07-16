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

import com._1c.g5.v8.dt.dcs.ui.DataCompositionSchemaEditor;
import com._1c.g5.v8.dt.dcs.ui.datasets.DataSets;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorEmbeddedEditorPage;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;
import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.List;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import com._1c.g5.v8.dt.core.platform.IDtProject;

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
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
            {
                display.addFilter(SWT.MenuDetect, TextEditorMenuHook::handleMenuDetect);
                display.addFilter(SWT.KeyDown, TextEditorMenuHook::handleDisplayKeyDown);
            }

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

    /** Display-фильтр для {@code StyledText} без ITextEditor (DCS, DynamicListQueryDialog и т.п.). */
    private static void handleMenuDetect(Event event)
    {
        if (!(event.widget instanceof StyledText text) || text.isDisposed())
        {
            Global.log("MenuHook", "handleMenuDetect: not StyledText or disposed");
            return;
        }
        if (Boolean.TRUE.equals(text.getData(HOOK_MARKER)))
        {
            Global.log("MenuHook", "handleMenuDetect: already hooked (HOOK_MARKER)");
            return;
        }

        Menu existingMenu = text.getMenu();
        if (existingMenu != null && !existingMenu.isDisposed()
            && Boolean.TRUE.equals(existingMenu.getData(QueryTextEditDialogHook.HOOK_MARKER_QUERY)))
        {
            Global.log("MenuHook", "handleMenuDetect: skipped (QueryTextEditDialog)");
            return;
        }

        Global.log("MenuHook", "handleMenuDetect: resolving viewer for class="
            + text.getClass().getName());

        ISourceViewer viewer = TextEditor.resolveViewerFromFocus(text);
        String ext = null;

        if (viewer == null)
        {
            Global.log("MenuHook", "handleMenuDetect: resolveViewerFromFocus=null, trying activeEditor");
            viewer = resolveViewerFromActiveEditor(text);
            if (viewer != null)
                ext = TextEditor.QL_COMPARE_EXTENSION;
        }

        if (viewer == null)
        {
            Global.log("MenuHook", "handleMenuDetect: resolveViewerFromFocus+activeEditor=null, trying dialog");
            viewer = TextEditor.resolveViewerFromDialog(text);
            if (viewer != null)
                ext = TextEditor.QL_COMPARE_EXTENSION;
        }

        if (viewer == null)
        {
            Global.log("MenuHook", "handleMenuDetect: viewer not found");
            return;
        }

        Global.log("MenuHook", "handleMenuDetect: viewer found=" + viewer.getClass().getName()
            + " ext=" + ext);
        boolean editable = text.getEditable();
        TextEditorComfortMenu.attachContextWithViewer(text, HOOK_MARKER, viewer, ext, editable);
    }

    /** Display-фильтр Ctrl+Alt+V / Alt+Shift+F для модальных диалогов (куда Key Binding Service не доставляет события). */
    private static void handleDisplayKeyDown(Event event)
    {
        // Ctrl+Alt+V ── вставка со сравнением
        if (event.keyCode == 'v'
            && (event.stateMask & SWT.MOD1) != 0
            && (event.stateMask & SWT.MOD3) != 0)
        {
            if (!(event.widget instanceof StyledText text) || text.isDisposed())
                return;
            Shell shell = text.getShell();
            if (shell == null || shell.isDisposed())
                return;
            TextEditor.Context ctx = TextEditor.resolveContextFromFocus();
            if (ctx == null)
                return;
            event.doit = false;
            PasteWithCompareActions.run(shell, ctx);
            return;
        }

        // Alt+Shift+F ── форматирование текста ИР (только для диалогов с QL-редактором, при подключённом ИР)
        if (event.keyCode == 'f'
            && (event.stateMask & SWT.MOD2) != 0
            && (event.stateMask & SWT.MOD3) != 0)
        {
            if (!(event.widget instanceof StyledText text) || text.isDisposed())
                return;
            ISourceViewer viewer = TextEditor.resolveViewerFromDialog(text);
            if (viewer == null || !IrFormatTextHandler.isApplicableQuery(viewer))
                return;
            IDtProject dtProject = IrFormatTextHandler.resolveDtProjectForQuery(null);
            if (dtProject == null)
                return;
            IRSession irSession = IRApplication.getSession(dtProject, true);
            if (irSession == null || irSession.executor == null)
                return;
            event.doit = false;
            IrFormatTextHandler.formatQuery(viewer, null);
        }
    }

    /** Разрешает {@link ISourceViewer} через активный редактор Workbench для DCS и подобных. */
    private static ISourceViewer resolveViewerFromActiveEditor(StyledText text)
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) { Global.log("MenuHook", "resolveAE: window=null"); return null; }
        IWorkbenchPage page = window.getActivePage();
        if (page == null) { Global.log("MenuHook", "resolveAE: page=null"); return null; }
        IEditorPart activeEditor = page.getActiveEditor();
        if (activeEditor == null) { Global.log("MenuHook", "resolveAE: activeEditor=null"); return null; }

        Global.log("MenuHook", "resolveAE: activeEditor=" + activeEditor.getClass().getName()
            + " siteId=" + activeEditor.getSite().getId());

        if (activeEditor instanceof DtGranularEditor<?> granular)
        {
            IFormPage activePage = granular.getActivePageInstance();
            if (activePage instanceof DtGranularEditorEmbeddedEditorPage<?> embeddedPage)
            {
                Global.log("MenuHook", "resolveAE: embeddedPage class=" + activePage.getClass().getName());

                IEditorPart embedded = embeddedPage.getEmbeddedEditor();
                if (embedded instanceof DataCompositionSchemaEditor dcsEditor)
                {
                    Global.log("MenuHook", "resolveAE: dcsEditor pages=" + dcsEditor.getPages().size());

                    if (!dcsEditor.getPages().isEmpty())
                    {
                        Object firstPage = dcsEditor.getPages().get(0);
                        Global.log("MenuHook", "resolveAE: firstPage class=" + firstPage.getClass().getName());

                        if (firstPage instanceof DataSets dataSets)
                        {
                            var qlEditor = dataSets.getQueryEditor();
                            Global.log("MenuHook", "resolveAE: qlEditor=" + (qlEditor != null ? qlEditor.getClass().getName() : "null"));

                            if (qlEditor != null)
                            {
                                var viewer = qlEditor.getViewer();
                                Global.log("MenuHook", "resolveAE: viewer=" + (viewer != null ? viewer.getClass().getName() : "null"));

                                if (viewer != null)
                                {
                                    StyledText wt = viewer.getTextWidget();
                                    boolean match = wt == text;
                                    Global.log("MenuHook", "resolveAE: textWidget match=" + match);
                                    if (match)
                                        return viewer;
                                }
                            }
                        }
                        else
                        {
                            Global.log("MenuHook", "resolveAE: firstPage not DataSets");
                        }
                    }
                }
                else
                {
                    Global.log("MenuHook", "resolveAE: embedded not DataCompositionSchemaEditor, class="
                        + (embedded != null ? embedded.getClass().getName() : "null"));
                }
            }
            else
            {
                Global.log("MenuHook", "resolveAE: activePage class="
                    + (activePage != null ? activePage.getClass().getName() : "null"));
            }
        }
        else
        {
            Global.log("MenuHook", "resolveAE: activeEditor not DtGranularEditor, class="
                + activeEditor.getClass().getName());
        }

        return null;
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

        static void attachContextWithViewer(
            StyledText textWidget,
            String hookMarker,
            ISourceViewer viewer,
            String ext,
            boolean editable)
        {
            if (textWidget == null || textWidget.isDisposed())
                return;
            if (Boolean.TRUE.equals(textWidget.getData(hookMarker)))
                return;

            Menu menu = textWidget.getMenu();
            if (menu == null || menu.isDisposed())
                return;

            textWidget.setData(hookMarker, Boolean.TRUE);
            String useExt = ext;
            attachToMenu(menu, hookMarker, viewer, () ->
            {
                Shell shell = textWidget.getShell();
                TextEditor.Context ctx = TextEditor.buildContext(viewer, useExt, editable);
                if (ctx == null)
                    return null;
                return List.of(
                    new MenuBinding(shell,
                        PasteWithCompareActions.MENU_LABEL,
                        "Сравнить выделение с буфером обмена и вставить результат",
                        PasteWithCompareHandler.COMMAND_ID,
                        PasteWithCompareHandler.BINDING_CONTEXT_ID,
                        () -> PasteWithCompareActions.isAvailable(shell, ctx),
                        () -> PasteWithCompareActions.run(shell, ctx)),
                    new MenuBinding(shell,
                        SortTextLinesActions.MENU_LABEL,
                        "Отсортировать выделенные строки текста по алфавиту",
                        SortTextLinesHandler.COMMAND_ID,
                        SortTextLinesHandler.BINDING_CONTEXT_ID,
                        () -> SortTextLinesActions.isAvailable(shell, ctx),
                        () -> SortTextLinesActions.run(shell, ctx)));
            });
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
                return List.of(
                    new MenuBinding(shell,
                        PasteWithCompareActions.MENU_LABEL,
                        "Сравнить выделение с буфером обмена и вставить результат",
                        PasteWithCompareHandler.COMMAND_ID,
                        PasteWithCompareHandler.BINDING_CONTEXT_ID,
                        () -> PasteWithCompareActions.isAvailable(shell, part, editorPart),
                        () -> PasteWithCompareActions.run(shell, part, editorPart)),
                    new MenuBinding(shell,
                        SortTextLinesActions.MENU_LABEL,
                        "Отсортировать выделенные строки текста по алфавиту",
                        SortTextLinesHandler.COMMAND_ID,
                        SortTextLinesHandler.BINDING_CONTEXT_ID,
                        () -> SortTextLinesActions.isAvailable(shell, part, editorPart),
                        () -> SortTextLinesActions.run(shell, part, editorPart)));
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
                return List.of(
                    new MenuBinding(shell,
                        PasteWithCompareActions.MENU_LABEL,
                        "Сравнить выделение с буфером обмена и вставить результат",
                        PasteWithCompareHandler.COMMAND_ID,
                        PasteWithCompareHandler.BINDING_CONTEXT_ID,
                        () -> PasteWithCompareActions.isAvailable(shell, contextSupplier.get()),
                        () -> PasteWithCompareActions.run(shell, contextSupplier.get())),
                    new MenuBinding(shell,
                        SortTextLinesActions.MENU_LABEL,
                        "Отсортировать выделенные строки текста по алфавиту",
                        SortTextLinesHandler.COMMAND_ID,
                        SortTextLinesHandler.BINDING_CONTEXT_ID,
                        () -> SortTextLinesActions.isAvailable(shell, contextSupplier.get()),
                        () -> SortTextLinesActions.run(shell, contextSupplier.get())));
            });
        }

        /**
         * Прикрепляет подменю к уже существующему {@link Menu} (ленивое меню embedded-редакторов).
         * Маркер хранится на объекте меню.
         */
        static void attachToMenu(
            Menu menu,
            String hookMarker,
            Supplier<List<MenuBinding>> bindingsSupplier)
        {
            attachToMenu(menu, hookMarker, null, bindingsSupplier);
        }

        static void attachToMenu(
            Menu menu,
            String hookMarker,
            ISourceViewer viewer,
            Supplier<List<MenuBinding>> bindingsSupplier)
        {
            if (menu == null || menu.isDisposed())
                return;
            if (Boolean.TRUE.equals(menu.getData(hookMarker)))
                return;

            menu.setData(hookMarker, Boolean.TRUE);

            MenuAdapter listener = buildMenuListener(bindingsSupplier, viewer);
            menu.addMenuListener(listener);

            menu.addDisposeListener(e ->
            {
                if (!menu.isDisposed())
                    menu.removeMenuListener(listener);
            });
        }

        private static MenuAdapter buildMenuListener(Supplier<List<MenuBinding>> bindingsSupplier)
        {
            return buildMenuListener(bindingsSupplier, null);
        }

        private static MenuAdapter buildMenuListener(Supplier<List<MenuBinding>> bindingsSupplier, ISourceViewer viewer)
        {
            return new MenuAdapter()
            {
                private final List<MenuItem> addedItems = new ArrayList<>(4);

                @Override
                public void menuShown(MenuEvent e)
                {
                    List<MenuBinding> bindings = bindingsSupplier.get();
                    if (bindings == null || bindings.isEmpty())
                        return;

                    Menu menu = (Menu) e.widget;

                    Menu comfortSub = ComfortSubmenuHelper.findOrCreateComfortSubmenu(menu, menu.getShell());
                    if (comfortSub == null)
                        return;

                    for (MenuBinding binding : bindings)
                    {
                        MenuItem item = new MenuItem(comfortSub, SWT.PUSH);
                        item.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                            binding.label, binding.commandId, binding.bindingContextId));
                        item.setToolTipText(binding.tooltip + Global.pluginSignForTooltip());
                        item.setEnabled(binding.isAvailable());
                        item.addSelectionListener(new SelectionAdapter()
                        {
                            @Override
                            public void widgetSelected(SelectionEvent ev)
                            {
                                binding.run();
                            }
                        });
                        addedItems.add(item);
                    }

                    if (viewer != null && IrFormatTextHandler.isApplicableQuery(viewer))
                    {
                        MenuItem formatItem = new MenuItem(comfortSub, SWT.PUSH);
                        formatItem.setText(IrFormatTextHandler.MENU_LABEL);
                        formatItem.setToolTipText(
                            "Форматировать текст через приложение ИР"
                                + Global.pluginSignForTooltip());
                        formatItem.setEnabled(true);
                        formatItem.addSelectionListener(new SelectionAdapter()
                        {
                            @Override
                            public void widgetSelected(SelectionEvent ev)
                            {
                                IrFormatTextHandler.formatQuery(viewer, null);
                            }
                        });
                        addedItems.add(formatItem);

                        MenuItem irEditorItem = new MenuItem(comfortSub, SWT.PUSH);
                        irEditorItem.setText(IrQueryTextEditorHandler.MENU_LABEL);
                        irEditorItem.setToolTipText(
                            "Открыть весь текст запроса в текстовом редакторе ИР"
                                + Global.pluginSignForTooltip());
                        irEditorItem.setEnabled(true);
                        irEditorItem.addSelectionListener(new SelectionAdapter()
                        {
                            @Override
                            public void widgetSelected(SelectionEvent ev)
                            {
                                IrQueryTextEditorHandler.openQueryTextInIrEditor(
                                    viewer, null, menu.getShell());
                            }
                        });
                        addedItems.add(irEditorItem);
                    }
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
            final String label;
            final String tooltip;
            final String commandId;
            final String bindingContextId;
            private final java.util.function.BooleanSupplier isAvailableSupplier;
            private final Runnable runAction;

            MenuBinding(Shell shell, String label, String tooltip, String commandId, String bindingContextId,
                java.util.function.BooleanSupplier isAvailableSupplier, Runnable runAction)
            {
                this.shell = shell;
                this.label = label;
                this.tooltip = tooltip;
                this.commandId = commandId;
                this.bindingContextId = bindingContextId;
                this.isAvailableSupplier = isAvailableSupplier;
                this.runAction = runAction;
            }

            boolean isAvailable()
            {
                return shell != null && !shell.isDisposed() && isAvailableSupplier.getAsBoolean();
            }

            void run()
            {
                if (shell != null && !shell.isDisposed())
                    runAction.run();
            }
        }
    }

}
