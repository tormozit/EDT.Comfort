package tormozit;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.xtext.ui.editor.XtextEditor;

/**
 * Переключатели «Переключить маркеры вхождений» для
 * {@link UniversalOccurrencesSupport}, все с единым глобальным состоянием:
 * <ul>
 * <li>немодальные Xtext/BSL-редакторы — штатная кнопка Xtext (скрытая
 * {@code setVisible(false)} в {@code MarkOccurrenceActionContributor}) становится видимой
 * в тулбаре редактора;</li>
 * <li>прочие немодальные текстовые редакторы (XML и др.) — своя кнопка-аналог в тулбаре
 * редактора (штатной у них нет) с той же иконкой, что у штатной кнопки Xtext;</li>
 * <li>модальные окна с текстовым полем — кнопка-аналог в тулбаре окна.</li>
 * </ul>
 * Свои кнопки обновляют вид при смене состояния откуда угодно (включая штатную кнопку
 * BSL-редактора) через {@link UniversalOccurrencesSupport#addStateListener}.
 */
public final class OccurrencesToggleHook implements IStartup
{
    private static final String TOGGLE_ID = "tormozit.universalOccurrencesToggle"; //$NON-NLS-1$
    /** Идентификатор кнопки в тулбаре панели сравнения ({@link #createToggleAction()}). */
    private static final String TOGGLE_COMPARE_ID = TOGGLE_ID + ".compareDialog"; //$NON-NLS-1$
    private static final String TOGGLE_TOOLTIP = "Переключить маркеры вхождений"; //$NON-NLS-1$

    /** Иконка штатной кнопки Xtext — из бандла {@code org.eclipse.xtext.ui}. */
    private static final String XTEXT_UI_PLUGIN = "org.eclipse.xtext.ui"; //$NON-NLS-1$
    private static final String ICON_ENABLED_PATH = "icons/elcl16/mark_occurrences.gif"; //$NON-NLS-1$
    /** Запасная, если бандл Xtext недоступен. */
    private static final String FALLBACK_ICON_PATH = "icons/etool16/occurrences_toggle.png"; //$NON-NLS-1$

    private static final String SHELL_HOOKED_KEY = "tormozit.occurrencesToggleShellHooked"; //$NON-NLS-1$
    private static final String TOOLBAR_ITEM_KEY = "tormozit.occurrencesToggleItem"; //$NON-NLS-1$

    /** Попытки дождаться контента диалогового шелла и его тулбара. */
    private static final int MAX_DIALOG_ATTEMPTS = 25;
    /** Попытки найти contribution штатной кнопки Xtext после открытия редактора. */
    private static final int MAX_REVEAL_ATTEMPTS = 10;

    /** Созданные кнопки — обновляются при смене состояния извне. */
    private static final CopyOnWriteArrayList<Action> toggleActions = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<ToolItem> toggleItems = new CopyOnWriteArrayList<>();

    private static ImageDescriptor toggleIconDescriptor;
    private static Image toggleImage;

    /** Редакторы, для которых штатная кнопка уже показана (защита от повторного поиска). */
    private final Set<IEditorPart> revealedEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Кнопка-переключатель конкретного редактора (своя или штатная Xtext) — чтобы показывать
     * её только у активного редактора.
     *
     * <p>Тулбары редакторов живут в общей командной панели окна, и элемент неактивного
     * редактора из неё сам не исчезает: рядом с рабочей кнопкой активного редактора
     * оставался её неактивный (серый) дубль от другого открытого редактора. Штатный
     * {@code MarkOccurrenceActionContributor} держит свою кнопку скрытой всегда, поэтому
     * показанную нами тоже прячем, когда её редактор не активен.
     */
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

    private static final Map<ITextEditor, ToggleBinding> managedToggles =
        Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            UniversalOccurrencesSupport.addStateListener(OccurrencesToggleHook::syncToggles);
            hookDialogShells(Display.getDefault());
            hookWorkbench();
        });
    }

    /** Синхронизация вида всех своих кнопок с единым состоянием. */
    private static void syncToggles(boolean enabled)
    {
        for (Action action : toggleActions)
        {
            if (action.isChecked() != enabled)
                action.setChecked(enabled);
        }
        for (ToolItem item : toggleItems)
        {
            if (!item.isDisposed() && item.getSelection() != enabled)
                item.setSelection(enabled);
        }
    }

    private void hookWorkbench()
    {
        if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench() == null)
            return;

        PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
            @Override public void windowActivated(IWorkbenchWindow w)   {}
            @Override public void windowDeactivated(IWorkbenchWindow w) {}
            @Override public void windowClosed(IWorkbenchWindow w)      {}
        });

        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            hookWindow(window);
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart editor = ref.getEditor(false);
                if (editor != null)
                    hookEditor(editor, 0);
            }
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                hookFromPartRef(ref, 0);
            }

            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                hookFromPartRef(ref, 0);
                // Активация редактора не меняет выделение — событие пересчёта не
                // придёт; восстанавливаем подсветку выделенного слова сами
                Display.getDefault().asyncExec(() -> {
                    UniversalOccurrencesSupport.refreshFromActiveEditor();
                    // после смены активного редактора командная панель окна уже перестроена
                    updateToggleVisibility();
                });
            }

            @Override
            public void partBroughtToTop(IWorkbenchPartReference ref)
            {
                Display.getDefault().asyncExec(OccurrencesToggleHook::updateToggleVisibility);
            }

            @Override
            public void partClosed(IWorkbenchPartReference ref)
            {
                Display.getDefault().asyncExec(OccurrencesToggleHook::updateToggleVisibility);
            }

            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private void hookFromPartRef(IWorkbenchPartReference ref, int attempt)
    {
        if (!(ref instanceof IEditorReference))
            return;
        IEditorPart editor = ((IEditorReference) ref).getEditor(false);
        if (editor != null)
            hookEditor(editor, attempt);
    }

    private void hookEditor(IEditorPart editor, int attempt)
    {
        ITextEditor textEditor = TextEditor.resolveTextEditor(editor);
        Global.tempLog("occtoggle", "hookEditor attempt=" + attempt + " part=" + className(editor)
            + " -> textEditor=" + className(textEditor)
            + " isXtext=" + (textEditor instanceof XtextEditor));
        if (textEditor == null || textEditor.getEditorSite() == null)
            return;
        if (textEditor instanceof XtextEditor)
        {
            if (revealedEditors.contains(textEditor))
                return;
            if (revealNativeToggle((XtextEditor) textEditor))
                revealedEditors.add(textEditor);
            else if (attempt < MAX_REVEAL_ATTEMPTS)
            {
                Display.getDefault().asyncExec(() -> {
                    if (PlatformUI.isWorkbenchRunning() && !PlatformUI.getWorkbench().isClosing())
                        hookEditor(editor, attempt + 1);
                });
            }
            return;
        }
        addOwnToggle(textEditor);
    }

    private static String className(Object o)
    {
        return o == null ? "null" : o.getClass().getName();
    }

    private static void logToolbar(IToolBarManager toolbar, String where)
    {
        try
        {
            StringBuilder sb = new StringBuilder();
            sb.append("toolbar ").append(where).append(" manager=").append(toolbar.getClass().getName())
                .append(" items=[");
            for (IContributionItem item : toolbar.getItems())
            {
                String kind = item.getClass().getSimpleName();
                String id = item.getId();
                String text = null;
                String tip = null;
                boolean visible = item.isVisible();
                if (item instanceof ActionContributionItem contribution && contribution.getAction() != null)
                {
                    text = contribution.getAction().getText();
                    tip = contribution.getAction().getToolTipText();
                }
                sb.append("{").append(kind).append(" id=").append(id)
                    .append(" vis=").append(visible)
                    .append(" text=").append(text)
                    .append(" tip=").append(tip).append("}");
            }
            sb.append("]");
            if (toolbar instanceof org.eclipse.jface.action.ToolBarManager manager)
            {
                sb.append(" widget=[");
                try
                {
                    org.eclipse.swt.widgets.ToolBar widget = manager.getControl();
                    if (widget == null || widget.isDisposed())
                        sb.append("null");
                    else
                    {
                        for (org.eclipse.swt.widgets.ToolItem ti : widget.getItems())
                        {
                            sb.append("{text=").append(ti.getText())
                                .append(" tip=").append(ti.getToolTipText())
                                .append(" enabled=").append(ti.getEnabled())
                                .append(" data=").append(ti.getData()).append("}");
                        }
                    }
                }
                catch (RuntimeException e)
                {
                    sb.append("<err:").append(e.getMessage()).append(">");
                }
                sb.append("]");
            }
            Global.tempLog("occtoggle", sb.toString());
        }
        catch (RuntimeException e)
        {
            Global.tempLogException("occtoggle", "logToolbar failed in " + where, e);
        }
    }

    /**
     * Показывает штатную скрытую кнопку Xtext «Переключить маркеры вхождений» в тулбаре
     * редактора (её текст/тултип стабилен в ru/en локали Xtext).
     *
     * @return {@code true} — кнопка найдена (и показана).
     */
    private static boolean revealNativeToggle(XtextEditor editor)
    {
        IToolBarManager toolbar = editor.getEditorSite().getActionBars().getToolBarManager();
        logToolbar(toolbar, "revealNativeToggle BEFORE");
        if (toolbar == null)
            return false;
        for (IContributionItem item : toolbar.getItems())
        {
            if (!(item instanceof ActionContributionItem contribution))
                continue;
            IAction action = contribution.getAction();
            if (action == null)
                continue;
            if (isMarkOccurrencesLabel(action.getText()) || isMarkOccurrencesLabel(action.getToolTipText()))
            {
                managedToggles.put(editor, new ToggleBinding(toolbar, contribution));
                logToolbar(toolbar, "revealNativeToggle AFTER found " + idOf(item));
                updateToggleVisibility();
                return true;
            }
        }
        logToolbar(toolbar, "revealNativeToggle AFTER not-found");
        return false;
    }

    private static String idOf(IContributionItem item)
    {
        try
        {
            return item.getId();
        }
        catch (RuntimeException e)
        {
            return "<err:" + e.getMessage() + ">";
        }
    }

    private static boolean isMarkOccurrencesLabel(String label)
    {
        if (label == null || label.isEmpty())
            return false;
        String lower = label.toLowerCase();
        return lower.contains("маркеры вхождений") //$NON-NLS-1$
            || lower.contains("mark occurrences"); //$NON-NLS-1$
    }

    /** Своя кнопка-аналог для немодальных редакторов без штатной (XML и др.). */
    private static void addOwnToggle(ITextEditor textEditor)
    {
        IToolBarManager toolbar = textEditor.getEditorSite().getActionBars().getToolBarManager();
        logToolbar(toolbar, "addOwnToggle editor=" + className(textEditor) + " BEFORE");
        if (toolbar == null)
            return;

        IContributionItem existing = toolbar.find(TOGGLE_ID);
        if (existing instanceof ActionContributionItem contribution
            && contribution.getAction() instanceof Action action)
        {
            action.setChecked(UniversalOccurrencesSupport.isEnabled());
            managedToggles.put(textEditor, new ToggleBinding(toolbar, contribution));
            updateToggleVisibility();
            return;
        }
        if (existing != null)
            return;

        Action action = new Action("", IAction.AS_CHECK_BOX) //$NON-NLS-1$
        {
            @Override
            public void run()
            {
                UniversalOccurrencesSupport.setEnabled(isChecked());
            }
        };
        action.setId(TOGGLE_ID);
        action.setImageDescriptor(toggleIconDescriptor());
        action.setToolTipText(TOGGLE_TOOLTIP + Global.pluginSignForTooltip());
        action.setChecked(UniversalOccurrencesSupport.isEnabled());
        toggleActions.addIfAbsent(action);
        toolbar.add(action);
        toolbar.update(true);
        IContributionItem added = toolbar.find(TOGGLE_ID);
        if (added != null)
            managedToggles.put(textEditor, new ToggleBinding(toolbar, added));
        logToolbar(toolbar, "addOwnToggle AFTER");
        updateToggleVisibility();
    }

    /**
     * Показывает кнопку вхождений только у активного редактора, у остальных — прячет.
     * Иначе в общей командной панели окна рядом с рабочей кнопкой висит её неактивный
     * дубль от другого открытого редактора (в т.ч. штатная кнопка Xtext, которую мы
     * показали в BSL-редакторе).
     */
    private static void updateToggleVisibility()
    {
        ITextEditor active = activeTextEditor();
        List<Map.Entry<ITextEditor, ToggleBinding>> entries;
        synchronized (managedToggles)
        {
            entries = new ArrayList<>(managedToggles.entrySet());
        }
        for (Map.Entry<ITextEditor, ToggleBinding> entry : entries)
        {
            ToggleBinding binding = entry.getValue();
            boolean visible = entry.getKey() == active;
            try
            {
                if (binding.item.isVisible() == visible)
                    continue;
                binding.item.setVisible(visible);
                binding.toolbar.update(true);
                Global.tempLog("occtoggle", "visibility=" + visible //$NON-NLS-1$ //$NON-NLS-2$
                    + " editor=" + className(entry.getKey())); //$NON-NLS-1$
            }
            catch (RuntimeException e)
            {
                // тулбар редактора уже разобран — забываем о кнопке
                managedToggles.remove(entry.getKey());
                Global.tempLogException("occtoggle", "updateToggleVisibility", e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /** Текстовый редактор активной части окна (внутренний — для многостраничных). */
    private static ITextEditor activeTextEditor()
    {
        if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing())
            return null;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        IEditorPart editor = page != null ? page.getActiveEditor() : null;
        return editor != null ? TextEditor.resolveTextEditor(editor) : null;
    }

    /**
     * Кнопка-переключатель для {@link IToolBarManager} — для модальных редакторов
     * сравнения текста, где она кладётся рядом с «Текущие строки» их хуками
     * ({@code CompareDialogCurrentLinesHook}/{@code TextMergeEditorHook}); регистрируется
     * в общем реестре синхронизации состояния. Иконка/тултип — как у всех наших кнопок.
     */
    public static IAction createToggleAction()
    {
        Action action = new Action("", IAction.AS_CHECK_BOX) //$NON-NLS-1$
        {
            @Override
            public void run()
            {
                UniversalOccurrencesSupport.setEnabled(isChecked());
            }
        };
        action.setId(TOGGLE_COMPARE_ID);
        action.setImageDescriptor(toggleIconDescriptor());
        action.setToolTipText(TOGGLE_TOOLTIP + Global.pluginSignForTooltip());
        action.setChecked(UniversalOccurrencesSupport.isEnabled());
        toggleActions.addIfAbsent(action);
        return action;
    }

    /**
     * Кнопка-переключатель, оставшаяся в тулбаре от прошлой сборки панели сравнения.
     * Хуки сравнения пересобирают тулбар («наши кнопки, затем то, что было»), и без такой
     * проверки прежняя кнопка возвращалась вместе со штатными — в панели появлялся второй,
     * уже неработающий переключатель.
     */
    public static boolean isStaleToggleItem(IContributionItem item)
    {
        if (item == null)
            return false;
        String id = idOf(item);
        boolean stale = TOGGLE_COMPARE_ID.equals(id) || TOGGLE_ID.equals(id);
        if (stale)
            Global.tempLog("occtoggle", "drop stale toggle item id=" + id); //$NON-NLS-1$ //$NON-NLS-2$
        return stale;
    }

    /**
     * Помечает шелл модального окна как обслуженный: универсальный скан
     * {@link #hookDialogShell} не добавит в него свою кнопку — она уже положена
     * хуком сравнения рядом с «Текущие строки».
     */
    public static void markShellHandled(Shell shell)
    {
        if (shell != null && !shell.isDisposed())
            shell.setData(SHELL_HOOKED_KEY, Boolean.TRUE);
    }

    /**
     * Убирает кнопку, добавленную универсальным сканом в этот шелл раньше вызова
     * {@link #markShellHandled(Shell)} (скан и хуки сравнения работают асинхронно).
     */
    public static void removeDialogItems(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;
        removeDialogItemsIn(shell);
    }

    private static void removeDialogItemsIn(Composite composite)
    {
        for (Control child : composite.getChildren())
        {
            if (child instanceof ToolBar toolBar && !toolBar.isDisposed())
            {
                for (ToolItem item : toolBar.getItems())
                {
                    if (Boolean.TRUE.equals(item.getData(TOOLBAR_ITEM_KEY)))
                    {
                        toggleItems.remove(item);
                        item.dispose();
                        toolBar.pack();
                    }
                }
            }
            else if (child instanceof Composite childComposite)
                removeDialogItemsIn(childComposite);
        }
    }

    // ========= Модальные окна: кнопка-аналог в тулбаре окна =========

    private static void hookDialogShells(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, OccurrencesToggleHook::handleShellShow);
    }

    private static void handleShellShow(Event e)
    {
        if (!(e.widget instanceof Shell shell) || shell.isDisposed())
            return;
        if (Boolean.TRUE.equals(shell.getData(SHELL_HOOKED_KEY)))
            return;
        if (isWorkbenchShell(shell))
        {
            shell.setData(SHELL_HOOKED_KEY, Boolean.TRUE);
            Global.tempLog("occtoggle", "handleShellShow WORKBENCH skip " + shell.getClass().getName());
            return;
        }
        Global.tempLog("occtoggle", "handleShellShow SCHEDULE " + shell.getClass().getName());
        scheduleDialogShellHook(shell, 0);
    }

    private static void scheduleDialogShellHook(Shell shell, int attempt)
    {
        if (shell.isDisposed() || Boolean.TRUE.equals(shell.getData(SHELL_HOOKED_KEY)))
            return;
        Display display = shell.getDisplay();
        display.asyncExec(() -> hookDialogShell(shell, attempt));
    }

    private static void hookDialogShell(Shell shell, int attempt)
    {
        if (shell.isDisposed() || Boolean.TRUE.equals(shell.getData(SHELL_HOOKED_KEY)))
            return;

        StyledText text = findStyledText(shell);
        if (text == null)
        {
            if (attempt < MAX_DIALOG_ATTEMPTS)
            {
                scheduleDialogShellHook(shell, attempt + 1);
                return;
            }
            shell.setData(SHELL_HOOKED_KEY, Boolean.TRUE);
            return;
        }

        ToolBar toolbar = findToolBar(shell);
        Global.tempLog("occtoggle", "hookDialogShell attempt=" + attempt
            + " shell=" + shell.getClass().getName() + " styledTextFound=true toolbar="
            + (toolbar == null ? "null" : "found items=" + toolbar.getItemCount()));
        if (toolbar != null && toolbar.getData(TOOLBAR_ITEM_KEY) == null)
        {
            ToolItem item = new ToolItem(toolbar, SWT.CHECK);
            item.setImage(toggleImage());
            item.setToolTipText(TOGGLE_TOOLTIP + Global.pluginSignForTooltip());
            item.setSelection(UniversalOccurrencesSupport.isEnabled());
            item.addSelectionListener(new SelectionAdapter()
            {
                @Override
                public void widgetSelected(SelectionEvent e)
                {
                    UniversalOccurrencesSupport.setEnabled(item.getSelection());
                }
            });
            toolbar.setData(TOOLBAR_ITEM_KEY, item);
            item.setData(TOOLBAR_ITEM_KEY, Boolean.TRUE);
            toggleItems.addIfAbsent(item);
            toolbar.pack();
            Global.tempLog("occtoggle", "hookDialogShell ADDED item to shell="
                + shell.getClass().getName());
        }
        shell.setData(SHELL_HOOKED_KEY, Boolean.TRUE);
    }

    private static boolean isWorkbenchShell(Shell shell)
    {
        if (!PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench() == null)
            return false;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            if (window.getShell() == shell)
                return true;
        }
        return false;
    }

    private static StyledText findStyledText(Composite composite)
    {
        for (Control child : composite.getChildren())
        {
            if (child instanceof StyledText styledText)
                return styledText;
            if (child instanceof Composite childComposite)
            {
                StyledText found = findStyledText(childComposite);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static ToolBar findToolBar(Composite composite)
    {
        for (Control child : composite.getChildren())
        {
            if (child instanceof ToolBar toolBar && !toolBar.isDisposed())
                return toolBar;
            if (child instanceof Composite childComposite)
            {
                ToolBar found = findToolBar(childComposite);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    // ========= Иконка — та же, что у штатной кнопки Xtext =========

    private static ImageDescriptor toggleIconDescriptor()
    {
        if (toggleIconDescriptor != null)
            return toggleIconDescriptor;
        ImageDescriptor descriptor = iconFromBundle(XTEXT_UI_PLUGIN, ICON_ENABLED_PATH);
        if (descriptor == null)
            descriptor = iconFromOwnBundle(FALLBACK_ICON_PATH);
        toggleIconDescriptor = descriptor != null
            ? descriptor : ImageDescriptor.getMissingImageDescriptor();
        return toggleIconDescriptor;
    }

    private static ImageDescriptor iconFromBundle(String bundleId, String path)
    {
        try
        {
            org.osgi.framework.Bundle bundle = Platform.getBundle(bundleId);
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

    private static ImageDescriptor iconFromOwnBundle(String path)
    {
        Activator activator = Activator.getDefault();
        if (activator == null || activator.getBundle() == null)
            return null;
        URL url = activator.getBundle().getEntry(path);
        return url != null ? ImageDescriptor.createFromURL(url) : null;
    }

    private static Image toggleImage()
    {
        if (toggleImage != null && !toggleImage.isDisposed())
            return toggleImage;
        toggleImage = toggleIconDescriptor().createImage();
        return toggleImage;
    }
}
