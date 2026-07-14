package tormozit;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

/**
 * Подменю «Комфорт» в модальном «Редактор запроса» ({@code QueryTextEditDialog}).
 */
public final class QueryTextEditDialogHook implements IStartup
{
    private static final String SHELL_REGISTERED_KEY =
        "tormozit.queryTextEditShellRegistered"; //$NON-NLS-1$
    static final String HOOK_MARKER_QUERY = "tormozit.queryEditorComfortMenuHooked"; //$NON-NLS-1$
    private static final String COMFORT_SUBMENU_MARKER =
        "tormozit.queryEditorComfortSubmenu"; //$NON-NLS-1$
    private static final String DIALOG_CLASS_SUFFIX = "QueryTextEditDialog"; //$NON-NLS-1$
    private static final String DYNAMIC_LIST_DIALOG_SUFFIX = "DynamicListQueryDialog"; //$NON-NLS-1$
    private static final String DIALOG_TITLE = "Редактор запроса"; //$NON-NLS-1$
    private static final String DYNAMIC_LIST_TITLE = "Динамический список"; //$NON-NLS-1$
    private static final String SUBMENU_TEXT = ComfortSubmenuHelper.SUBMENU_TEXT;

    /** Открытые окна «Редактор запроса» (shell диалога). */
    private static final Set<Shell> activeQueryEditorShells =
        Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Control> menuDetectHookedControls =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        display.addFilter(SWT.MenuDetect, QueryTextEditDialogHook::handleMenuDetect);
        display.addFilter(SWT.Show, QueryTextEditDialogHook::handleShow);

        QueryTextEditDialogDebug.log("install MenuDetect + Show filters"); //$NON-NLS-1$
    }

    private static void handleShow(Event event)
    {
        if (event.widget instanceof Menu menu)
        {
            handleMenuWidgetShow(menu);
            return;
        }

        if (!(event.widget instanceof Shell shell))
            return;
        if (shell.isDisposed())
            return;
        if (Boolean.TRUE.equals(shell.getData(SHELL_REGISTERED_KEY)))
            return;
        if (!isQueryTextEditShell(shell))
            return;

        registerQueryEditorShell(shell);
    }

    private static void registerQueryEditorShell(Shell shell)
    {
        shell.setData(SHELL_REGISTERED_KEY, Boolean.TRUE);
        activeQueryEditorShells.add(shell);

        Object dialog = resolveDialog(shell);
        QueryTextEditDialogDebug.log("register shell title=\"" + shell.getText() //$NON-NLS-1$
            + "\" dialog=" + describeDialog(dialog) //$NON-NLS-1$
            + " qlEditor=" + (dialog != null && Global.getField(dialog, "qlEditor") != null)); //$NON-NLS-1$ //$NON-NLS-2$

        shell.addDisposeListener(e -> activeQueryEditorShells.remove(shell));

        scheduleHookStyledTextMenus(shell.getDisplay(), shell, 0);
        scheduleMenuDetectOnShell(shell.getDisplay(), shell, 0);
        scheduleContentAssistPatch(shell);
    }

    private static void handleMenuWidgetShow(Menu menu)
    {
        Shell dialogShell = resolveQueryEditorShellForMenu(menu);
        if (dialogShell == null)
            return;

        Control focus = menu.getDisplay().getFocusControl();
        if (focus == null || focus.isDisposed())
            focus = findQueryEditorStyledText(dialogShell);
        if (focus == null)
        {
            QueryTextEditDialogDebug.problem("MenuShow: no focus StyledText menuItems=" //$NON-NLS-1$
                + menu.getItemCount());
            return;
        }

        if (isRootEditorContextMenu(menu, focus))
            ensureMenuHook(dialogShell, focus, menu);
    }

    private static void handleMenuDetect(Event event)
    {
        if (!(event.widget instanceof Control control))
            return;

        Shell dialogShell = resolveQueryEditorShellForControl(control);
        if (dialogShell == null)
            return;

        QueryTextEditDialogDebug.log("MenuDetect control=" + control.getClass().getSimpleName() //$NON-NLS-1$
            + " menu=" + (control.getMenu() != null)); //$NON-NLS-1$

        Runnable hook = () -> hookControlMenu(dialogShell, control);
        hook.run();
        if (control.getMenu() == null)
            control.getDisplay().asyncExec(hook);
    }

    private static void hookControlMenu(Shell dialogShell, Control control)
    {
        if (control instanceof StyledText styledText)
            TextEditorIdentifierSelectionHook.installOnStyledText(styledText);

        Menu menu = control.getMenu();
        if (menu == null || menu.isDisposed())
            return;
        if (isRootEditorContextMenu(menu, control))
            ensureMenuHook(dialogShell, control, menu);
    }

    /**
     * JFace наполняет меню после {@link SWT#Show} — вставляем пункты в {@link MenuAdapter#menuShown}.
     */
    private static void ensureMenuHook(Shell dialogShell, Control control, Menu menu)
    {
        if (!isRootEditorContextMenu(menu, control))
            return;
        if (menu.isDisposed() || Boolean.TRUE.equals(menu.getData(HOOK_MARKER_QUERY)))
            return;

        menu.setData(HOOK_MARKER_QUERY, Boolean.TRUE);
        menu.addMenuListener(new MenuAdapter()
        {
            @Override
            public void menuShown(MenuEvent e)
            {
                Menu m = (Menu) e.widget;
                if (m == null || m.isDisposed() || hasComfortItem(m))
                    return;
                tryInjectIntoMenu(m, dialogShell, control);
            }
        });
    }

    private static void tryInjectIntoMenu(Menu menu, Shell dialogShell, Control control)
    {
        if (!isRootEditorContextMenu(menu, control))
            return;
        if (menu == null || menu.isDisposed() || hasComfortItem(menu))
            return;

        QlEditorContext qlContext = resolveQlEditorContext(dialogShell, control);
        if (qlContext == null)
        {
            QueryTextEditDialogDebug.problem("inject: qlContext=null dialog=" //$NON-NLS-1$
                + describeDialog(resolveDialog(dialogShell, control))
                + " control=" + control.getClass().getName()); //$NON-NLS-1$
            return;
        }

        injectComfortItems(menu, dialogShell, qlContext);
        QueryTextEditDialogDebug.log("inject OK menuItems=" + menu.getItemCount()); //$NON-NLS-1$
    }

    private static void injectComfortItems(Menu menu, Shell shell, QlEditorContext qlContext)
    {
        if (menu.getItemCount() > 0)
            new MenuItem(menu, SWT.SEPARATOR);

        Menu comfortSub = ComfortSubmenuHelper.findOrCreateComfortSubmenu(menu, shell);
        if (comfortSub == null)
            return;
        comfortSub.setData(COMFORT_SUBMENU_MARKER, Boolean.TRUE);

        MenuItem pasteItem = new MenuItem(comfortSub, SWT.PUSH);
        pasteItem.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
            PasteWithCompareActions.MENU_LABEL,
            PasteWithCompareHandler.COMMAND_ID,
            PasteWithCompareHandler.BINDING_CONTEXT_ID));
        pasteItem.setToolTipText(
            "Сравнить выделение с буфером обмена и вставить результат"
                + Global.pluginSignForTooltip());

        final Object qlEditor = qlContext.qlEditor;
        final ISourceViewer viewer = qlContext.viewer;
        final Object queryDialog = resolveDialog(shell);
        pasteItem.setEnabled(PasteWithCompareActions.isAvailable(shell,
            TextEditor.buildContext(viewer, TextEditor.QL_COMPARE_EXTENSION,
                isQlEditorEditable(qlEditor))));
        pasteItem.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent ev)
            {
                PasteWithCompareActions.run(shell,
                    TextEditor.buildContext(viewer, TextEditor.QL_COMPARE_EXTENSION,
                        isQlEditorEditable(qlEditor)));
            }
        });

        MenuItem formatItem = new MenuItem(comfortSub, SWT.PUSH);
        formatItem.setText(IrFormatTextHandler.MENU_LABEL);
        formatItem.setToolTipText(
            "Форматировать текст запроса через приложение ИР" + Global.pluginSignForTooltip());
        formatItem.setEnabled(IrFormatTextHandler.isApplicableQuery(viewer));
        formatItem.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent ev)
            {
                IrFormatTextHandler.formatQuery(viewer, queryDialog);
            }
        });

        MenuItem irEditorItem = new MenuItem(comfortSub, SWT.PUSH);
        irEditorItem.setText(IrQueryTextEditorHandler.MENU_LABEL);
        irEditorItem.setToolTipText(
            "Открыть весь текст запроса в текстовом редакторе ИР"
                + Global.pluginSignForTooltip());
        irEditorItem.setEnabled(IrFormatTextHandler.isApplicableQuery(viewer));
        irEditorItem.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent ev)
            {
                IrQueryTextEditorHandler.openQueryTextInIrEditor(viewer, queryDialog, shell);
            }
        });
    }

    /** Только корневое контекстное меню {@link StyledText}, не каскады вроде нашего подменю. */
    private static boolean isRootEditorContextMenu(Menu menu, Control control)
    {
        if (menu == null || menu.isDisposed() || control == null || control.isDisposed())
            return false;
        if (Boolean.TRUE.equals(menu.getData(COMFORT_SUBMENU_MARKER)))
            return false;
        if (menu.getParentMenu() != null)
            return false;
        Menu rootMenu = control.getMenu();
        return rootMenu != null && !rootMenu.isDisposed() && rootMenu == menu;
    }

    private static boolean hasComfortItem(Menu menu)
    {
        for (MenuItem item : menu.getItems())
        {
            if (item.isDisposed())
                continue;
            String text = item.getText();
            if (text == null)
                continue;
            if (SUBMENU_TEXT.equals(text))
                return true;
            if (text.startsWith(PasteWithCompareActions.MENU_LABEL)) //$NON-NLS-1$
                return true;
        }
        return false;
    }

    private static Shell resolveQueryEditorShellForMenu(Menu menu)
    {
        if (menu == null || menu.isDisposed())
            return null;

        Shell menuShell = menu.getShell();
        if (menuShell != null && activeQueryEditorShells.contains(menuShell))
            return menuShell;

        Control focus = menu.getDisplay().getFocusControl();
        Shell fromFocus = resolveQueryEditorShellForControl(focus);
        if (fromFocus != null)
            return fromFocus;

        if (menuShell != null && isQueryTextEditShell(menuShell))
            return menuShell;

        return null;
    }

    private static Shell resolveQueryEditorShellForControl(Control control)
    {
        if (control == null || control.isDisposed())
            return null;

        Shell shell = control.getShell();
        if (shell != null && activeQueryEditorShells.contains(shell))
            return shell;

        if (shell != null && isQueryTextEditShell(shell))
            return shell;

        return null;
    }

    private static void scheduleHookStyledTextMenus(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 50;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed())
                return;
            hookAllStyledTextMenus(shell);
            if (attempt < 12)
                scheduleHookStyledTextMenus(display, shell, attempt + 1);
        });
    }

    private static void hookAllStyledTextMenus(Composite root)
    {
        if (root == null || root.isDisposed())
            return;

        Shell dialogShell = root instanceof Shell shell ? shell : root.getShell();
        if (dialogShell == null || !activeQueryEditorShells.contains(dialogShell))
            return;

        if (root instanceof StyledText styledText)
            hookControlMenu(dialogShell, styledText);

        for (Control child : root.getChildren())
        {
            if (child.isDisposed())
                continue;
            if (child instanceof StyledText styledText)
                hookControlMenu(dialogShell, styledText);
            if (child instanceof Composite composite)
                hookAllStyledTextMenus(composite);
        }
    }

    private static StyledText findQueryEditorStyledText(Shell dialogShell)
    {
        return Global.findControl(dialogShell, StyledText.class, st -> !st.isDisposed());
    }

    private static void scheduleMenuDetectOnShell(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 50;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed())
                return;
            attachMenuDetectRecursively(shell);
            if (attempt < 8)
                scheduleMenuDetectOnShell(display, shell, attempt + 1);
        });
    }

    private static void attachMenuDetectRecursively(Composite container)
    {
        if (container == null || container.isDisposed())
            return;

        hookMenuDetectOnControl(container);
        for (Control child : container.getChildren())
        {
            hookMenuDetectOnControl(child);
            if (child instanceof Composite composite)
                attachMenuDetectRecursively(composite);
        }
    }

    private static void hookMenuDetectOnControl(Control control)
    {
        if (control == null || control.isDisposed())
            return;
        if (!menuDetectHookedControls.add(control))
            return;

        Listener listener = QueryTextEditDialogHook::handleMenuDetect;
        control.addListener(SWT.MenuDetect, listener);
        control.addDisposeListener(e ->
        {
            menuDetectHookedControls.remove(control);
            try
            {
                control.removeListener(SWT.MenuDetect, listener);
            }
            catch (Exception ignored)
            {
            }
        });
    }

    private static boolean isQueryTextEditShell(Shell shell)
    {
        if (resolveDialog(shell) != null)
            return true;
        String title = shell.getText();
        if (title != null && title.startsWith(DIALOG_TITLE))
            return true;
        if (title != null && title.startsWith(DYNAMIC_LIST_TITLE))
            return true;
        Object data = shell.getData();
        return data != null && isQueryTextEditDialog(data);
    }

    private static Object resolveDialog(Shell shell)
    {
        return resolveDialog(shell, null);
    }

    private static Object resolveDialog(Shell shell, Control fromControl)
    {
        if (shell == null || shell.isDisposed())
            return null;

        Object fromShell = resolveDialogOnWidget(shell);
        if (fromShell != null)
            return fromShell;

        if (fromControl != null && !fromControl.isDisposed())
        {
            for (Control c = fromControl; c != null && !c.isDisposed(); c = c.getParent())
            {
                Object onControl = resolveDialogOnWidget(c);
                if (onControl != null)
                    return onControl;
            }
        }

        return resolveDialogInComposite(shell);
    }

    private static Object resolveDialogInComposite(Composite root)
    {
        if (root == null || root.isDisposed())
            return null;

        Object onRoot = resolveDialogOnWidget(root);
        if (onRoot != null)
            return onRoot;

        for (Control child : root.getChildren())
        {
            if (child.isDisposed())
                continue;
            Object onChild = resolveDialogOnWidget(child);
            if (onChild != null)
                return onChild;
            if (child instanceof Composite composite)
            {
                Object nested = resolveDialogInComposite(composite);
                if (nested != null)
                    return nested;
            }
        }
        return null;
    }

    private static Object resolveDialogOnWidget(org.eclipse.swt.widgets.Widget widget)
    {
        if (widget == null || widget.isDisposed())
            return null;

        for (String key : new String[] { null, "org.eclipse.jface.window.Window", //$NON-NLS-1$ //$NON-NLS-2$
            "org.eclipse.jface.dialogs.Dialog.dialog" }) //$NON-NLS-1$
        {
            Object data = key == null ? widget.getData() : widget.getData(key);
            if (isQueryTextEditDialog(data))
                return data;
        }
        return null;
    }

    private static boolean isQueryTextEditDialog(Object data)
    {
        if (data == null)
            return false;
        String name = data.getClass().getName();
        return name.contains(DIALOG_CLASS_SUFFIX)
            || name.contains(DYNAMIC_LIST_DIALOG_SUFFIX);
    }

    private static QlEditorContext resolveQlEditorContext(Shell shell, Control control)
    {
        if (shell == null || control == null || control.isDisposed())
            return null;

        Object dialog = resolveDialog(shell, control);
        if (dialog != null)
        {
            QlEditorContext fromDialog = contextFromDialog(dialog, control);
            if (fromDialog != null)
                return fromDialog;
        }

        ISourceViewer viewer = resolveViewerFromControl(shell, control);
        if (viewer == null)
            return null;

        Object qlEditor = dialog != null ? Global.getField(dialog, "qlEditor") : null; //$NON-NLS-1$
        return new QlEditorContext(qlEditor, viewer);
    }

    private static QlEditorContext contextFromDialog(Object dialog, Control control)
    {
        Object qlEditor = Global.getField(dialog, "qlEditor"); //$NON-NLS-1$
        ISourceViewer viewer = viewerFromQlEditor(qlEditor);
        if (viewer == null)
            viewer = viewerFromDialog(dialog);
        if (viewer == null && qlEditor != null)
        {
            Object textEditor = Global.getField(qlEditor, "textEditor"); //$NON-NLS-1$
            if (textEditor instanceof StyledText)
                viewer = viewerFromQlEditor(qlEditor);
        }
        if (viewer == null)
        {
            QueryTextEditDialogDebug.problem("contextFromDialog: viewer=null qlEditor=" //$NON-NLS-1$
                + (qlEditor != null ? qlEditor.getClass().getSimpleName() : "null")); //$NON-NLS-1$
            return null;
        }
        return new QlEditorContext(qlEditor, viewer);
    }

    private static ISourceViewer viewerFromDialog(Object dialog)
    {
        Object embedded = Global.invoke(dialog, "getEmbeddedQlEditor"); //$NON-NLS-1$
        if (embedded != null)
        {
            Object viewer = Global.invoke(embedded, "getViewer"); //$NON-NLS-1$
            if (viewer instanceof ISourceViewer sourceViewer)
                return sourceViewer;
        }
        Object tplEditor = Global.getField(dialog, "embeddedTemplateEditor"); //$NON-NLS-1$
        if (tplEditor != null)
        {
            Object viewer = Global.invoke(tplEditor, "getViewer"); //$NON-NLS-1$
            if (viewer instanceof ISourceViewer sourceViewer)
                return sourceViewer;
        }
        return null;
    }

    private static ISourceViewer resolveViewerFromControl(Shell shell, Control control)
    {
        for (Control c = control; c != null && !c.isDisposed(); c = c.getParent())
        {
            ISourceViewer adapted = Adapters.adapt(c, ISourceViewer.class);
            if (adapted != null)
                return adapted;

            for (String method : new String[] { "getViewer", "getSourceViewer", "getTextViewer" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                Object viewerObj = Global.invoke(c, method);
                if (viewerObj instanceof ISourceViewer sourceViewer)
                    return sourceViewer;
            }

            Object embedded = Global.invoke(c, "getEmbeddedQlEditor"); //$NON-NLS-1$
            if (embedded != null)
            {
                Object embeddedViewer = Global.invoke(embedded, "getViewer"); //$NON-NLS-1$
                if (embeddedViewer instanceof ISourceViewer sourceViewer)
                    return sourceViewer;
            }
        }

        Object dialog = resolveDialog(shell, control);
        if (dialog != null)
        {
            ISourceViewer fromDialog = viewerFromDialog(dialog);
            if (fromDialog != null)
                return fromDialog;
            ISourceViewer fromQl = viewerFromQlEditor(Global.getField(dialog, "qlEditor")); //$NON-NLS-1$
            if (fromQl != null)
                return fromQl;
        }

        StyledText inShell = findQueryEditorStyledText(shell);
        if (inShell != null && inShell != control)
            return resolveViewerFromControl(shell, inShell);

        return null;
    }

    private static ISourceViewer viewerFromQlEditor(Object qlEditor)
    {
        if (qlEditor == null)
            return null;

        Object viewerObj = Global.invoke(qlEditor, "getViewer"); //$NON-NLS-1$
        if (viewerObj instanceof ISourceViewer sourceViewer)
            return sourceViewer;

        Object embedded = Global.getField(qlEditor, "embeddedQlEditor"); //$NON-NLS-1$
        if (embedded != null)
        {
            Object embeddedViewer = Global.invoke(embedded, "getViewer"); //$NON-NLS-1$
            if (embeddedViewer instanceof ISourceViewer sourceViewer)
                return sourceViewer;
        }
        return null;
    }

    private static boolean isQlEditorEditable(Object qlEditor)
    {
        if (qlEditor == null)
            return true;
        Object editable = Global.invoke(qlEditor, "isEditable"); //$NON-NLS-1$
        return !(editable instanceof Boolean b) || b;
    }

    private static String describeDialog(Object dialog)
    {
        if (dialog == null)
            return "null"; //$NON-NLS-1$
        return dialog.getClass().getName();
    }

    private static void scheduleContentAssistPatch(Shell shell)
    {
        // Временный безусловный маркер трассировки (debug-perf-query-lag.log). Снять после фикса.
        ContentAssistDebug.perfLog("QueryTextEditDialogHook.scheduleContentAssistPatch", 0, 0, //$NON-NLS-1$
            "{\"shellNull\":" + (shell == null) //$NON-NLS-1$
                + ",\"shellDisposed\":" + (shell != null && shell.isDisposed()) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        if (shell == null || shell.isDisposed())
            return;
        shell.getDisplay().asyncExec(() ->
        {
            if (shell.isDisposed())
                return;
            ContentAssistManager mgr = ContentAssistManager.getInstance();
            ContentAssistDebug.perfLog("QueryTextEditDialogHook.scheduleContentAssistPatch.asyncExec", 0, 0, //$NON-NLS-1$
                "{\"mgrNull\":" + (mgr == null) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            if (mgr != null)
                mgr.applyPatchToQueryEditorShell(shell);
        });
    }

    static ISourceViewer resolveViewerForShell(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;
        Object dialog = resolveDialog(shell);
        if (dialog != null)
        {
            Object qlEditor = Global.getField(dialog, "qlEditor"); //$NON-NLS-1$
            ISourceViewer fromQl = viewerFromQlEditor(qlEditor);
            if (fromQl != null)
                return fromQl;
            ISourceViewer fromDialog = viewerFromDialog(dialog);
            if (fromDialog != null)
                return fromDialog;
        }
        return null;
    }

    static Object resolveDialogForShell(Shell shell)
    {
        return resolveDialog(shell);
    }

    /**
     * Контекст «Вставить со сравнением» для модального «Редактора запроса» по фокусу.
     */
    static TextEditor.Context tryBuildPasteContext(Control focus)
    {
        if (focus == null || focus.isDisposed())
            return null;

        Shell shell = focus.getShell();
        if (shell == null || shell.isDisposed())
            return null;
        if (!activeQueryEditorShells.contains(shell) && !isQueryTextEditShell(shell))
            return null;

        QlEditorContext qlContext = resolveQlEditorContext(shell, focus);
        if (qlContext == null || qlContext.viewer == null)
            return null;

        return TextEditor.buildContext(
            qlContext.viewer,
            TextEditor.QL_COMPARE_EXTENSION,
            isQlEditorEditable(qlContext.qlEditor));
    }

    private static final class QlEditorContext
    {
        final Object qlEditor;
        final ISourceViewer viewer;

        QlEditorContext(Object qlEditor, ISourceViewer viewer)
        {
            this.qlEditor = qlEditor;
            this.viewer = viewer;
        }
    }

    /**
     * Диагностика подменю «Комфорт» в «Редакторе запроса».
     *
     * <p>Включение: Параметры → Комфорт → «Общее логирование».
     */
    private static final class QueryTextEditDialogDebug
    {
        private static final String TAG = "QueryEditor"; //$NON-NLS-1$

        private QueryTextEditDialogDebug()
        {
        }

        public static boolean isEnabled()
        {
            return Global.isLogEnabled();
        }

        public static void log(String msg)
        {
            if (isEnabled())
                Global.log(TAG, msg);
        }

        /** Сбой ветки — в журнал при включённом {@link #isEnabled()}. */
        public static void problem(String msg)
        {
            if (isEnabled())
                Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
        }
    }

}
