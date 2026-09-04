package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.WeakHashMap;

import org.eclipse.jface.text.AbstractInformationControlManager;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.texteditor.ITextEditor;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Состояние глобального переключателя «Подсказки при наведении без Ctrl»
 * (пункт подменю «Комфорт» в контекстном меню BSL- и XML-редактора, а также полей
 * текста окон сравнения — см. {@link #installOnCompareViewer}).
 *
 * <p>Два режима:
 * <ul>
 * <li>тумблер включен — обычный hover при удержании указателя мыши (без Ctrl);
 * <li>тумблер выключен — hover только при зажатом Ctrl.
 * </ul>
 *
 * <p>Механизм тот же, что у {@code BslModuleSpellCheckHook}: у {@link ISourceViewer}
 * находится поле {@code fTextHoverManager} — наследник {@link AbstractInformationControlManager} —
 * и у него выставляется {@code setEnabled(...)}. Так гасятся и штатный doc-hover,
 * и ИР-обогащение ({@code IrBslTextHoverWrapper} живёт внутри того же менеджера).
 * Состояние Ctrl отслеживается глобальным Display-фильтром. На {@code MouseMove}
 * нельзя полагаться на {@code event.stateMask}: {@code TextEditorCtrlClickSelectWordHook}
 * снимает бит Ctrl, чтобы не рисовать гиперссылку до выделения слова.
 * Пока указатель на попапе инспектора, нажатие и отпускание Ctrl его не закрывают
 * (и штатный closer редактора, и наш Ctrl-гейт) — независимо от тумблера.
 *
 * <p>В XML (WST) hover зарегистрирован на маску «без модификаторов», а не на
 * {@code DEFAULT_HOVER_STATE_MASK}: при зажатом Ctrl штатный lookup его не находит.
 * Поэтому при выключенном тумблере тот же hover дополнительно вешается на {@link SWT#MOD1}.
 *
 * <p>{@code fInformationPresenter} (Ctrl+hover / Ctrl+F2) сознательно не трогаем:
 * это не «удержание указателя мыши», а осознанное действие с клавиатуры.
 */
final class BslHoverHintState
{
    private static final String[] MANAGER_FIELDS = { "fTextHoverManager" }; //$NON-NLS-1$

    private static final String ITEM_TEXT = "Подсказки при наведении без Ctrl"; //$NON-NLS-1$

    private static final String ITEM_TOOLTIP =
        "Включено — подсказки при наведении указателя. Выключено — требуется нажатый Ctrl"; //$NON-NLS-1$

    /** Метка «в контекстное меню этого виджета пункт уже добавляется». */
    private static final String MENU_HOOK_MARKER = "tormozit.hoverHintMenuHooked"; //$NON-NLS-1$

    /** Метка «слушатель MenuDetect на этом виджете уже поставлен». */
    private static final String MENU_DETECT_MARKER = "tormozit.hoverHintMenuDetect"; //$NON-NLS-1$

    /** Меню поля сравнения создаётся не сразу — ограниченное число повторов, см. issue #130. */
    private static final int MAX_MENU_ATTEMPTS = 40;

    private static final int MENU_RETRY_DELAY_MS = 50;

    /** Кэш состояния Ctrl, обновляется глобальным Display-фильтром. */
    private static volatile boolean ctrlHeld;

    private static boolean filterInstalled;

    /**
     * Вьюеры вне редакторов (панели окон сравнения текстов) — их не найти обходом
     * {@code getEditorReferences}, поэтому держим слабые ссылки и применяем к ним
     * состояние вместе с редакторами.
     */
    private static final Set<ISourceViewer> extraViewers =
        Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private BslHoverHintState() {}

    static boolean isEnabled()
    {
        return ComfortSettings.isHoverHintsEnabled();
    }

    /** Сохранить настройку и применить ко всем открытым редакторам и полям окон сравнения. */
    static void setEnabled(boolean enabled)
    {
        ComfortSettings.setHoverHintsEnabled(enabled);
        applyToAllEditors();
    }

    /** Пункт-переключатель в подменю «Комфорт». */
    static MenuItem addMenuItem(Menu comfortSub)
    {
        MenuItem item = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.CHECK, ITEM_TEXT);
        item.setToolTipText(ITEM_TOOLTIP + Global.pluginSignForTooltip());
        item.setSelection(isEnabled());
        item.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent ev)
            {
                setEnabled(item.getSelection());
            }
        });
        return item;
    }

    /**
     * Текстовое поле не редактора, а окна сравнения (панели merge-вьюера): применить
     * текущее состояние и добавить пункт-переключатель в его контекстное меню.
     * Вызывается из единственной точки, где панель сравнения отдаёт свой вьюер —
     * {@link MergeViewerReflection#extractSourceViewer}. Повторные вызовы безопасны.
     */
    static void installOnCompareViewer(ISourceViewer viewer)
    {
        if (viewer == null)
            return;
        StyledText text = viewer.getTextWidget();
        if (text == null || text.isDisposed())
            return;
        extraViewers.add(viewer);
        Display display = text.getDisplay();
        if (display.getThread() == Thread.currentThread())
        {
            applyToViewer(viewer);
            hookContextMenu(text, 0);
            /*
             * Если меню создаётся только к первому правому клику (позже наших повторов) —
             * подключаемся на MenuDetect: он приходит до показа меню, поэтому наш
             * menuShown успевает сработать уже на этом же вызове.
             */
            if (!Boolean.TRUE.equals(text.getData(MENU_DETECT_MARKER)))
            {
                text.setData(MENU_DETECT_MARKER, Boolean.TRUE);
                text.addListener(SWT.MenuDetect, e -> hookContextMenu(text, MAX_MENU_ATTEMPTS));
            }
        }
        else
        {
            // timerExec/меню — только из потока UI (иначе SWTException и молчащий пункт)
            display.asyncExec(() -> installOnCompareViewer(viewer));
        }
    }

    /**
     * Контекстное меню поля сравнения создаётся позже самого виджета (штатный
     * {@code MenuManager} панели) — ждём его ограниченное число попыток.
     */
    private static void hookContextMenu(StyledText text, int attempt)
    {
        if (text.isDisposed())
            return;

        Menu menu = text.getMenu();
        if (menu == null || menu.isDisposed())
        {
            if (attempt >= MAX_MENU_ATTEMPTS)
                return;
            text.getDisplay().timerExec(MENU_RETRY_DELAY_MS, () -> hookContextMenu(text, attempt + 1));
            return;
        }
        // Помним само меню, а не факт подключения: панель может заменить меню целиком
        if (text.getData(MENU_HOOK_MARKER) == menu)
            return;

        text.setData(MENU_HOOK_MARKER, menu);
        /*
         * Пункты и само подменю «Комфорт» создаются на каждый показ и снимаются при
         * скрытии: штатный MenuManager панели перезаполняет меню перед каждым показом
         * (removeAll), поэтому долгоживущие пункты всё равно не пережили бы показ.
         */
        MenuAdapter listener = new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(1);

            @Override
            public void menuShown(MenuEvent e)
            {
                Menu shown = (Menu)e.widget;
                if (shown == null || shown.isDisposed())
                    return;
                Menu comfortSub = ComfortSubmenuHelper.findOrCreateComfortSubmenu(shown, shown.getShell());
                if (comfortSub == null)
                    return;
                addedItems.add(addMenuItem(comfortSub));
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
        menu.addMenuListener(listener);
        text.addDisposeListener(e ->
        {
            if (!menu.isDisposed())
                menu.removeMenuListener(listener);
        });
    }

    /** Итоговая доступность hover: тумблер вкл → всегда; тумблер выкл → только при Ctrl. */
    static boolean isHoverHintsCurrentlyEnabled()
    {
        return ComfortSettings.isHoverHintsEnabled() || ctrlHeld;
    }

    /**
     * Включить/выключить hover-менеджеры конкретного viewer согласно текущему
     * состоянию (тумблер + Ctrl-гейт). При выключении закрывает открытую подсказку,
     * если указатель не на попапе инспектора и не внутри этой подсказки.
     */
    static void applyToViewer(ISourceViewer viewer)
    {
        if (viewer == null)
            return;
        ensureCtrlFilterInstalled();
        boolean enabled = isHoverHintsCurrentlyEnabled();
        boolean keepInspector = isPointerOnInspectorPopup();
        for (String field : MANAGER_FIELDS)
        {
            Object mgr = Global.getField(viewer, field);
            if (!(mgr instanceof AbstractInformationControlManager aim))
                continue;
            try
            {
                if (!enabled && !keepInspector && !isPointerInsideInformationControl(aim))
                    aim.disposeInformationControl();
                aim.setEnabled(enabled);
            }
            catch (Exception ignored)
            {
            }
        }
        syncCtrlStateMaskHovers(viewer);
    }

    /**
     * Указатель уже в открытой подсказке этого менеджера (включая вложенные shell).
     */
    private static boolean isPointerInsideInformationControl(AbstractInformationControlManager aim)
    {
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed())
            return false;
        Point cursor = display.getCursorLocation();
        Control under = display.getCursorControl();
        if (isPointerInsideControl(Global.getField(aim, "fInformationControl"), cursor, under)) //$NON-NLS-1$
            return true;
        Object replacer = Global.getField(aim, "fInformationControlReplacer"); //$NON-NLS-1$
        if (replacer == null)
            return false;
        return isPointerInsideControl(Global.getField(replacer, "fInformationControl"), cursor, under); //$NON-NLS-1$
    }

    private static boolean isPointerInsideControl(Object control, Point cursor, Control under)
    {
        Shell shell = informationControlShell(control);
        if (shell == null || shell.isDisposed() || !shell.isVisible())
            return false;
        if (under != null && !under.isDisposed())
        {
            Shell underShell = under.getShell();
            if (isSameOrNestedShell(underShell, shell))
                return true;
        }
        return cursor != null && shell.getBounds().contains(cursor);
    }

    private static boolean isSameOrNestedShell(Shell candidate, Shell root)
    {
        for (Shell walk = candidate; walk != null && !walk.isDisposed(); walk = parentShellOf(walk))
        {
            if (walk == root)
                return true;
        }
        return false;
    }

    private static Shell parentShellOf(Shell shell)
    {
        Composite parent = shell.getParent();
        while (parent != null && !(parent instanceof Shell))
            parent = parent.getParent();
        return parent instanceof Shell s ? s : null;
    }

    private static Shell informationControlShell(Object control)
    {
        if (control == null)
            return null;
        try
        {
            Object shell = Global.invoke(control, "getShell"); //$NON-NLS-1$
            if (shell instanceof Shell s)
                return s;
        }
        catch (Exception ignored)
        {
        }
        Object field = Global.getField(control, "fShell"); //$NON-NLS-1$
        return field instanceof Shell s ? s : null;
    }

    static void applyToAllEditors()
    {
        try
        {
            if (!PlatformUI.isWorkbenchRunning())
                return;
            ensureCtrlFilterInstalled();
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            {
                for (IWorkbenchPage page : window.getPages())
                {
                    for (IEditorReference ref : page.getEditorReferences())
                        applyToEditor(ref.getEditor(false));
                }
            }
            applyToCompareViewers();
        }
        catch (Exception ignored)
        {
        }
    }

    /** Панели окон сравнения (см. {@link #installOnCompareViewer}); уничтоженные — забываем. */
    private static void applyToCompareViewers()
    {
        List<ISourceViewer> snapshot;
        synchronized (extraViewers)
        {
            snapshot = new ArrayList<>(extraViewers);
        }
        for (ISourceViewer viewer : snapshot)
        {
            StyledText text = viewer.getTextWidget();
            if (text == null || text.isDisposed())
                extraViewers.remove(viewer);
            else
                applyToViewer(viewer);
        }
    }

    private static void applyToEditor(IEditorPart editor)
    {
        if (editor instanceof BslXtextEditor bsl)
        {
            applyToViewer(bsl.getInternalSourceViewer());
        }
        else if (editor instanceof DtGranularEditor<?> granular)
        {
            applyToFormPage(granular.getActivePageInstance());
            // Все страницы, включая неактивные вкладки: FormEditor не публикует
            // перечисление страниц — читаем защищённое поле Vector pages.
            Object pagesField = Global.getField(granular, "pages"); //$NON-NLS-1$
            if (pagesField instanceof Vector<?> pages)
            {
                for (Object page : pages)
                    if (page instanceof IFormPage formPage)
                        applyToFormPage(formPage);
            }
        }
        else if (XmlEditorShowInNavigatorHandler.isXmlEditor(editor))
        {
            ITextEditor textEditor = TextEditor.resolveTextEditor(editor);
            if (textEditor != null)
                applyToViewer(TextEditor.getSourceViewer(textEditor));
        }
    }

    private static void applyToFormPage(IFormPage page)
    {
        if (!(page instanceof DtGranularEditorXtextEditorPage<?> xtextPage))
            return;
        IEditorPart embedded = xtextPage.getEmbeddedEditor();
        if (embedded instanceof BslXtextEditor bsl)
            applyToViewer(bsl.getInternalSourceViewer());
    }

    /**
     * WST регистрирует hover на маску 0 (без модификаторов). При зажатом Ctrl
     * lookup идёт по {@link SWT#MOD1} и без копии туда штатная подсказка не находится.
     * Копия ставится только если на Ctrl ещё нет своего hover; снимается, если это
     * та же самая копия.
     */
    private static void syncCtrlStateMaskHovers(ISourceViewer viewer)
    {
        if (!(viewer instanceof ITextViewerExtension2 ext))
            return;
        Object raw = Global.getField(viewer, "fTextHovers"); //$NON-NLS-1$
        if (!(raw instanceof Map<?, ?> hovers) || hovers.isEmpty())
            return;

        Map<String, ITextHover> atNone = new HashMap<>();
        Map<String, ITextHover> atCtrl = new HashMap<>();
        for (Map.Entry<?, ?> entry : hovers.entrySet())
        {
            if (!(entry.getValue() instanceof ITextHover hover))
                continue;
            Object typeObj = Global.getField(entry.getKey(), "fContentType"); //$NON-NLS-1$
            Object maskObj = Global.getField(entry.getKey(), "fStateMask"); //$NON-NLS-1$
            if (!(typeObj instanceof String type) || !(maskObj instanceof Integer mask))
                continue;
            if (mask == 0)
                atNone.put(type, hover);
            else if (mask == SWT.MOD1)
                atCtrl.put(type, hover);
        }
        if (atNone.isEmpty())
            return;

        boolean hoverWithoutCtrl = ComfortSettings.isHoverHintsEnabled();
        for (Map.Entry<String, ITextHover> entry : atNone.entrySet())
        {
            String type = entry.getKey();
            ITextHover noneHover = entry.getValue();
            ITextHover ctrlHover = atCtrl.get(type);
            if (!hoverWithoutCtrl)
            {
                if (ctrlHover == null)
                    ext.setTextHover(noneHover, type, SWT.MOD1);
            }
            else if (ctrlHover != null && ctrlHover == noneHover)
            {
                ext.setTextHover(null, type, SWT.MOD1);
            }
        }
    }

    /**
     * Установить (один раз) глобальный Display-фильтр слежения за Ctrl.
     * При смене состояния применяет гейт ко всем открытым BSL- и XML-редакторам.
     */
    private static void ensureCtrlFilterInstalled()
    {
        if (filterInstalled)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        filterInstalled = true;
        Listener listener = (Event event) ->
        {
            keepInspectorOpenOnCtrl(event);
            updateCtrlState(isCtrlHeld(event));
        };
        display.addFilter(SWT.KeyDown, listener);
        display.addFilter(SWT.KeyUp, listener);
        display.addFilter(SWT.MouseMove, listener);
    }

    /**
     * Пока указатель на попапе инспектора, Ctrl не должен доходить до closer
     * редактора ({@code keyPressed} закрывает hover на любую клавишу).
     * Клавиши, уже идущие в сам инспектор (Ctrl+C и т.п.), не глотаем.
     */
    private static void keepInspectorOpenOnCtrl(Event event)
    {
        if (event.keyCode != SWT.CTRL)
            return;
        if (event.type != SWT.KeyDown && event.type != SWT.KeyUp)
            return;
        Display display = event.display != null ? event.display : Display.getCurrent();
        if (display == null || display.isDisposed())
            return;
        Control under = display.getCursorControl();
        if (under == null || under.isDisposed())
            return;
        Shell inspector = inspectorShellOf(under);
        if (inspector == null)
            return;
        if (isWidgetOnShell(event.widget, inspector))
            return;
        event.doit = false;
    }

    private static boolean isPointerOnInspectorPopup()
    {
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed())
            return false;
        Control under = display.getCursorControl();
        if (under == null || under.isDisposed())
            return false;
        return inspectorShellOf(under) != null;
    }

    private static Shell inspectorShellOf(Control control)
    {
        if (control == null || control.isDisposed())
            return null;
        for (Shell walk = control.getShell(); walk != null && !walk.isDisposed(); walk = parentShellOf(walk))
        {
            if (DebugInspectorHook.isInspectorShell(walk))
                return walk;
        }
        return null;
    }

    private static boolean isWidgetOnShell(Object widget, Shell root)
    {
        if (!(widget instanceof Control control) || control.isDisposed() || root == null)
            return false;
        return isSameOrNestedShell(control.getShell(), root);
    }

    /**
     * Реальное удержание Ctrl. {@code KeyDown}/{@code KeyUp} надёжны сами по себе;
     * на {@code MouseMove} {@code stateMask} может быть уже без Ctrl — другой Display-фильтр
     * снимает {@link SWT#MOD1}, чтобы {@code HyperlinkManager} не активировался.
     */
    private static boolean isCtrlHeld(Event event)
    {
        if (event.type == SWT.KeyDown && event.keyCode == SWT.CTRL)
            return true;
        if (event.type == SWT.KeyUp && event.keyCode == SWT.CTRL)
            return false;
        return isCtrlPhysicallyHeld();
    }

    /**
     * Диагностика: {@code OS.GetKeyState} — Win32-специфичный внутренний API SWT
     * ({@code org.eclipse.swt.internal.win32.OS}). На платформах, где SWT собран не
     * под win32 (Linux GTK и т.п.), вызов может завершиться {@link LinkageError}
     * (класс/метод недоступен в фрагменте SWT этой платформы) прямо внутри
     * глобального Display-фильтра, отслеживающего Ctrl для переключателя «Подсказки
     * при наведении без Ctrl». Временно логируем такой сбой в журнал «Комфорт» и
     * пробрасываем исключение дальше без изменения поведения — чтобы подтвердить
     * гипотезу по логу пользователя, прежде чем менять логику фильтра.
     */
    private static boolean isCtrlPhysicallyHeld()
    {
        try
        {
            return (OS.GetKeyState(OS.VK_CONTROL) & 0x8000) != 0;
        }
        catch (LinkageError | RuntimeException e)
        {
            IrBslHoverDebug.problem("OS.GetKeyState failed: " + e.getClass().getName() //$NON-NLS-1$
                + ": " + e.getMessage() + "; os.name=" + System.getProperty("os.name")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            throw e;
        }
    }

    private static void updateCtrlState(boolean now)
    {
        if (ctrlHeld == now)
            return;
        ctrlHeld = now;
        if (!ComfortSettings.isHoverHintsEnabled())
            applyToAllEditors();
    }
}
