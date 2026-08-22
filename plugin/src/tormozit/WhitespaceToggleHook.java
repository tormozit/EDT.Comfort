package tormozit;

import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.texteditor.ITextEditorActionConstants;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Кнопка «Показывать непечатаемые символы» (¶) в панели инструментов редактора
 * модуля BSL.
 *
 * <p>Штатный {@code ShowWhitespaceCharactersActionContributor} Xtext в конфигурации
 * BSL не подключён — команда в меню «Правка» есть, но переключателя на панели,
 * как в окне сравнения, нет. Здесь добавляем в тулбар ту же
 * {@link org.eclipse.ui.texteditor.ShowWhitespaceCharactersAction} редактора.
 *
 * <p>Правый клик по кнопке — «Параметры...» открывает штатный диалог
 * «Показывать непечатаемые символы» (гиперссылка «настройки видимости» на странице
 * «Текстовые редакторы»), без открытия окна «Параметры».
 */
public final class WhitespaceToggleHook implements IStartup
{
    /** Как у команды {@code org.eclipse.ui.edit.text.toggleShowWhitespaceCharacters}. */
    private static final String COMMAND_ID = "org.eclipse.ui.edit.text.toggleShowWhitespaceCharacters"; //$NON-NLS-1$
    private static final String TEXTEDITOR_PLUGIN = "org.eclipse.ui.workbench.texteditor"; //$NON-NLS-1$
    private static final String EDITORS_PLUGIN = "org.eclipse.ui.editors"; //$NON-NLS-1$
    private static final String ICON_PATH = "icons/full/etool16/show_whitespace_chars.png"; //$NON-NLS-1$
    private static final String ICON_DISABLED_PATH = "icons/full/dtool16/show_whitespace_chars.png"; //$NON-NLS-1$

    private static final String TOOL_ITEM_KEY = "tormozit.whitespaceToggle"; //$NON-NLS-1$
    private static final String TOOLBAR_MENU_LISTENER_KEY = "tormozit.whitespaceToggle.menuListener"; //$NON-NLS-1$

    private static final String WHITESPACE_OPTIONS_DIALOG =
        "org.eclipse.ui.internal.editors.text.TextEditorDefaultsPreferencePage$WhitespaceCharacterPainterOptionsDialog"; //$NON-NLS-1$

    private static final int MAX_ATTACH_ATTEMPTS = 40;
    private static final int RETRY_DELAY_MS = 50;

    private static ImageDescriptor iconDescriptor;
    private static ImageDescriptor disabledIconDescriptor;

    /** Редакторы, для которых кнопка уже добавлена в тулбар. */
    private static final Set<BslXtextEditor> attachedEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    private static final Set<DtGranularEditor<?>> hookedGranularEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    private static final class ToggleBinding
    {
        final IToolBarManager toolbar;
        final IContributionItem item;

        ToggleBinding(IToolBarManager toolbar, IContributionItem item)
        {
            this.toolbar = toolbar;
            this.item = item;
        }
    }

    private static final Map<BslXtextEditor, ToggleBinding> managedToggles =
        Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(WhitespaceToggleHook::hookWorkbench);
    }

    private static void hookWorkbench()
    {
        if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench() == null)
            return;

        PlatformUI.getWorkbench().addWindowListener(new org.eclipse.ui.IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
            @Override public void windowActivated(IWorkbenchWindow w)   {}
            @Override public void windowDeactivated(IWorkbenchWindow w) {}
            @Override public void windowClosed(IWorkbenchWindow w)      {}
        });

        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            hookWindow(window);
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart editor = ref.getEditor(false);
                if (editor != null)
                    hookEditorIfNeeded(editor);
            }
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                hookFromPartRef(ref);
            }

            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                hookFromPartRef(ref);
                Display.getDefault().asyncExec(WhitespaceToggleHook::updateToggleVisibility);
            }

            @Override
            public void partBroughtToTop(IWorkbenchPartReference ref)
            {
                Display.getDefault().asyncExec(WhitespaceToggleHook::updateToggleVisibility);
            }

            @Override
            public void partClosed(IWorkbenchPartReference ref)
            {
                Display.getDefault().asyncExec(WhitespaceToggleHook::updateToggleVisibility);
            }

            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private static void hookFromPartRef(IWorkbenchPartReference ref)
    {
        if (!(ref instanceof IEditorReference))
            return;
        IEditorPart editor = ((IEditorReference) ref).getEditor(false);
        if (editor != null)
            hookEditorIfNeeded(editor);
    }

    private static void hookEditorIfNeeded(IEditorPart editor)
    {
        if (editor instanceof BslXtextEditor bsl)
            scheduleAttach(bsl, 0);
        else if (editor instanceof DtGranularEditor<?> granular)
            hookGranularEditor(granular);
    }

    private static void hookGranularEditor(DtGranularEditor<?> editor)
    {
        hookGranularEditorActivePage(editor);
        if (!hookedGranularEditors.add(editor))
            return;
        editor.addPageChangedListener(event ->
        {
            Object page = event.getSelectedPage();
            if (page instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
            {
                IEditorPart embedded = xtextPage.getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor bsl)
                    scheduleAttach(bsl, 0);
            }
            Display.getDefault().asyncExec(WhitespaceToggleHook::updateToggleVisibility);
        });
    }

    private static void hookGranularEditorActivePage(DtGranularEditor<?> editor)
    {
        IFormPage activePage = editor.getActivePageInstance();
        if (!(activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage))
            return;
        IEditorPart embedded = xtextPage.getEmbeddedEditor();
        if (embedded instanceof BslXtextEditor bsl)
            scheduleAttach(bsl, 0);
    }

    private static void scheduleAttach(BslXtextEditor editor, int attempt)
    {
        if (editor == null || attachedEditors.contains(editor))
            return;
        Display.getDefault().asyncExec(() -> tryAttach(editor, attempt));
    }

    private static void tryAttach(BslXtextEditor editor, int attempt)
    {
        if (editor == null || attachedEditors.contains(editor))
            return;
        if (PlatformUI.isWorkbenchRunning() && PlatformUI.getWorkbench().isClosing())
            return;
        if (editor.getEditorSite() == null)
        {
            retryLater(editor, attempt);
            return;
        }

        IAction action = editor.getAction(ITextEditorActionConstants.SHOW_WHITESPACE_CHARACTERS);
        if (action == null)
        {
            retryLater(editor, attempt);
            return;
        }

        IToolBarManager toolbar = editor.getEditorSite().getActionBars().getToolBarManager();
        if (toolbar == null)
        {
            retryLater(editor, attempt);
            return;
        }

        applyStandardPresentation(action);

        IContributionItem existing = findExistingItem(toolbar);
        if (existing != null)
        {
            attachedEditors.add(editor);
            managedToggles.put(editor, new ToggleBinding(toolbar, existing));
            schedulePreferencesContextMenu(existing);
            updateToggleVisibility();
            return;
        }

        ActionContributionItem item = new ActionContributionItem(action)
        {
            @Override
            public void fill(ToolBar parent, int index)
            {
                super.fill(parent, index);
                hookPreferencesContextMenu(this);
            }
        };
        item.setVisible(false);
        insertItem(toolbar, item);
        toolbar.update(true);
        attachedEditors.add(editor);
        managedToggles.put(editor, new ToggleBinding(toolbar, item));
        schedulePreferencesContextMenu(item);
        updateToggleVisibility();
    }

    private static void schedulePreferencesContextMenu(IContributionItem item)
    {
        Display display = Display.getCurrent();
        if (display == null)
            return;
        display.asyncExec(() -> hookPreferencesContextMenu(item));
    }

    private static void hookPreferencesContextMenu(IContributionItem item)
    {
        ToolItem toolItem = findToolItem(item);
        if (toolItem == null || toolItem.isDisposed())
            return;
        if (Boolean.TRUE.equals(toolItem.getData(TOOL_ITEM_KEY)))
            return;
        toolItem.setData(TOOL_ITEM_KEY, Boolean.TRUE);

        ToolBar bar = toolItem.getParent();
        if (bar == null || bar.isDisposed())
            return;
        if (bar.getData(TOOLBAR_MENU_LISTENER_KEY) != null)
            return;

        Listener listener = WhitespaceToggleHook::onToolbarMenuDetect;
        bar.addListener(SWT.MenuDetect, listener);
        bar.setData(TOOLBAR_MENU_LISTENER_KEY, listener);
    }

    private static void onToolbarMenuDetect(Event event)
    {
        if (!(event.widget instanceof ToolBar bar) || bar.isDisposed())
            return;
        Point local = bar.toControl(event.x, event.y);
        ToolItem hit = bar.getItem(local);
        if (hit == null || hit.isDisposed() || !Boolean.TRUE.equals(hit.getData(TOOL_ITEM_KEY)))
            return;

        event.doit = false;
        Shell shell = bar.getShell();
        Menu menu = new Menu(shell, SWT.POP_UP);
        MenuItem preferences = new MenuItem(menu, SWT.PUSH);
        preferences.setText("Параметры..."); //$NON-NLS-1$
        preferences.setToolTipText(TooltipText.wrap(bar,
            "Настройки видимости непечатаемых символов" + Global.pluginSignForTooltip())); //$NON-NLS-1$
        preferences.addListener(SWT.Selection, e -> openWhitespaceVisibilityDialog(shell));
        menu.setLocation(event.x, event.y);
        menu.setVisible(true);
    }

    /**
     * Штатный диалог гиперссылки «(настройки видимости)» со страницы «Текстовые редакторы».
     * Пишет напрямую в {@link EditorsUI#getPreferenceStore()}, окно «Параметры» не открывает.
     */
    private static void openWhitespaceVisibilityDialog(Shell parent)
    {
        try
        {
            Bundle bundle = Platform.getBundle(EDITORS_PLUGIN);
            if (bundle == null)
                return;
            Class<?> dialogClass = bundle.loadClass(WHITESPACE_OPTIONS_DIALOG);
            Constructor<?> ctor = dialogClass.getDeclaredConstructor(Shell.class, IPreferenceStore.class);
            ctor.setAccessible(true);
            IPreferenceStore store = EditorsUI.getPreferenceStore();
            if (store == null)
                return;
            Shell shell = parent != null && !parent.isDisposed()
                ? parent
                : Display.getDefault().getActiveShell();
            Dialog dialog = (Dialog) ctor.newInstance(shell, store);
            dialog.open();
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            Global.log("WhitespaceToggleHook", //$NON-NLS-1$
                "Не удалось открыть диалог настроек видимости непечатаемых символов: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static ToolItem findToolItem(IContributionItem item)
    {
        if (item == null)
            return null;
        Widget widget = null;
        if (item instanceof ActionContributionItem contribution)
        {
            try
            {
                widget = contribution.getWidget();
            }
            catch (RuntimeException ignored)
            {
                widget = null;
            }
        }
        if (widget == null)
            widget = (Widget) Global.getField(item, "widget"); //$NON-NLS-1$
        return widget instanceof ToolItem toolItem ? toolItem : null;
    }

    private static IContributionItem findExistingItem(IToolBarManager toolbar)
    {
        IContributionItem byCommand = toolbar.find(COMMAND_ID);
        if (byCommand != null)
            return byCommand;
        IContributionItem byEditorAction = toolbar.find(ITextEditorActionConstants.SHOW_WHITESPACE_CHARACTERS);
        if (byEditorAction != null)
            return byEditorAction;
        for (IContributionItem item : toolbar.getItems())
        {
            if (!(item instanceof ActionContributionItem contribution))
                continue;
            IAction action = contribution.getAction();
            if (action == null)
                continue;
            if (COMMAND_ID.equals(action.getActionDefinitionId())
                || isWhitespaceLabel(action.getToolTipText())
                || isWhitespaceLabel(action.getText()))
                return contribution;
        }
        return null;
    }

    private static void insertItem(IToolBarManager toolbar, ActionContributionItem item)
    {
        int index = indexAfterMarkOccurrences(toolbar);
        if (toolbar instanceof ToolBarManager manager && index >= 0)
            manager.insert(index, item);
        else
            toolbar.add(item);
    }

    /** Сразу после «Переключить маркеры вхождений» — как у штатной группы редактора. */
    private static int indexAfterMarkOccurrences(IToolBarManager toolbar)
    {
        IContributionItem[] items = toolbar.getItems();
        for (int i = 0; i < items.length; i++)
        {
            IAction action = actionOf(items[i]);
            if (action != null && isMarkOccurrencesLabel(action.getText(), action.getToolTipText()))
                return i + 1;
        }
        return -1;
    }

    private static IAction actionOf(IContributionItem item)
    {
        if (!(item instanceof ActionContributionItem contribution))
            return null;
        return contribution.getAction();
    }

    private static boolean isMarkOccurrencesLabel(String... labels)
    {
        for (String label : labels)
        {
            if (label == null || label.isEmpty())
                continue;
            String lower = label.toLowerCase();
            if (lower.contains("маркеры вхождений") //$NON-NLS-1$
                || lower.contains("mark occurrences")) //$NON-NLS-1$
                return true;
        }
        return false;
    }

    private static boolean isWhitespaceLabel(String label)
    {
        if (label == null || label.isEmpty())
            return false;
        String lower = label.toLowerCase();
        return lower.contains("непечат") //$NON-NLS-1$
            || lower.contains("whitespace character"); //$NON-NLS-1$
    }

    /** Иконка и подпись как у штатной команды сравнения/редактора; тултип — с суффиксом плагина. */
    private static void applyStandardPresentation(IAction action)
    {
        action.setImageDescriptor(standardIcon());
        action.setDisabledImageDescriptor(standardDisabledIcon());
        action.setText(""); //$NON-NLS-1$
        if (action.getActionDefinitionId() == null || action.getActionDefinitionId().isEmpty())
            action.setActionDefinitionId(COMMAND_ID);

        String base = action.getToolTipText();
        if (base == null || base.isBlank())
            base = action.getText();
        if (base == null || base.isBlank())
            base = "Показывать непечатаемые символы"; //$NON-NLS-1$
        String suffix = Global.pluginSignForTooltip();
        if (!base.contains("(Комфорт)")) //$NON-NLS-1$
            action.setToolTipText(base + suffix);
    }

    private static void retryLater(BslXtextEditor editor, int attempt)
    {
        if (attempt >= MAX_ATTACH_ATTEMPTS)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(RETRY_DELAY_MS, () -> tryAttach(editor, attempt + 1));
    }

    /**
     * Показывает кнопку у активного редактора модуля и прячет у остальных — та же
     * проблема общей командной панели окна, что и у {@link OccurrencesToggleHook}.
     */
    private static void updateToggleVisibility()
    {
        BslXtextEditor active = activeBslEditor();
        if (active == null)
            return;
        ToggleBinding activeBinding = managedToggles.get(active);
        List<Map.Entry<BslXtextEditor, ToggleBinding>> entries;
        synchronized (managedToggles)
        {
            entries = new ArrayList<>(managedToggles.entrySet());
        }
        for (Map.Entry<BslXtextEditor, ToggleBinding> entry : entries)
        {
            ToggleBinding binding = entry.getValue();
            boolean visible = activeBinding != null && binding.item == activeBinding.item;
            try
            {
                if (binding.item.isVisible() == visible)
                {
                    if (visible)
                        schedulePreferencesContextMenu(binding.item);
                    continue;
                }
                binding.item.setVisible(visible);
                binding.toolbar.update(true);
                if (visible)
                    schedulePreferencesContextMenu(binding.item);
            }
            catch (RuntimeException e)
            {
                managedToggles.remove(entry.getKey());
            }
        }
    }

    private static BslXtextEditor activeBslEditor()
    {
        if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing())
            return null;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        IEditorPart editor = page != null ? page.getActiveEditor() : null;
        if (editor == null)
            return null;
        if (editor instanceof BslXtextEditor bsl)
            return bsl;
        return GetRef.getActiveBslEditor(editor);
    }

    private static ImageDescriptor standardIcon()
    {
        if (iconDescriptor == null)
            iconDescriptor = loadIcon(ICON_PATH);
        return iconDescriptor != null ? iconDescriptor : ImageDescriptor.getMissingImageDescriptor();
    }

    private static ImageDescriptor standardDisabledIcon()
    {
        if (disabledIconDescriptor == null)
            disabledIconDescriptor = loadIcon(ICON_DISABLED_PATH);
        return disabledIconDescriptor != null ? disabledIconDescriptor : standardIcon();
    }

    private static ImageDescriptor loadIcon(String path)
    {
        try
        {
            org.osgi.framework.Bundle bundle =
                org.eclipse.core.runtime.Platform.getBundle(TEXTEDITOR_PLUGIN);
            if (bundle == null)
                return null;
            URL url = bundle.getEntry(path);
            return url != null ? ImageDescriptor.createFromURL(url) : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }
}
