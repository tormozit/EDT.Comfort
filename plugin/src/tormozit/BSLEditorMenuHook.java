package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Добавляет пункт «Редактировать вложенный текст» в контекстное меню
 * BSL-редактора EDT.
 *
 * <p>Поддерживает оба варианта открытия BSL-редактора:
 * <ul>
 *   <li><b>Автономный</b> — {@link BslXtextEditor} открыт напрямую
 *       (файл *.bsl / ресурс, открытый через «Открыть как текст»).</li>
 *   <li><b>Встроенный</b> — {@link BslXtextEditor} является страницей
 *       многостраничного {@link DtGranularEditor} (стандартный редактор
 *       модуля EDT: «Текст модуля», «Форма», «Права» и т. д.).</li>
 * </ul>
 *
 * <h3>Механизм внедрения меню</h3>
 * <p>В отличие от подхода через {@code org.eclipse.ui.popupMenus} /
 * {@code org.eclipse.ui.menus}, данный хук использует SWT {@link MenuAdapter},
 * что позволяет динамически добавлять/удалять пункты и не требует регистрации
 * дополнительных extension point'ов.</p>
 *
 * <h3>Жизненный цикл</h3>
 * <ol>
 *   <li>{@code earlyStartup()} → подписывается на все текущие и будущие окна.</li>
 *   <li>Для каждого окна — слушает открытие редакторов ({@link IPartListener2}).</li>
 *   <li>При открытии редактора подходящего типа — прикрепляет меню через
 *       {@link #attachMenuToBslEditor}.</li>
 * </ol>
 */
public class BSLEditorMenuHook implements IStartup
{
    private static final String ITEM_TEXT_DebugIR = "Отладить объект ИР";
    private static final String ITEM_TEXT_MethodConstructor = "Конструктор метода ИР";
    private static final String ITEM_TEXT_FormatText = IrFormatTextHandler.MENU_LABEL;
    private static final String ITEM_TEXT_FindReferences = IrFindReferencesHandler.MENU_LABEL;
    private static final String ITEM_TEXT_ModuleCheck = IrModuleCheckHandler.MENU_LABEL;
    private static final String ITEM_TEXT_DeclareExpressionType = IrDeclareExpressionTypeHandler.MENU_LABEL;

    private static final String SURROUND_HINT =
        "\nЭто же можно сделать для выделенного блока через CTRL+Space при подключенном ИР.";

    /**
     * Ключ SWT-данных для маркировки «меню уже прикреплено».
     * Храним на виджете {@link StyledText}, чтобы не дублировать при повторном вызове.
     */
    private static final String HOOK_MARKER = "tormozit.bslMenuHooked"; //$NON-NLS-1$

    static final String BSL_MENU_HOOK_MARKER = HOOK_MARKER;

    /** Пассивная диагностика Alt+Shift+F (без перехвата). */
    private static final String KEY_DIAG_MARKER = "tormozit.irFormatTextKeyDiag"; //$NON-NLS-1$

    private static final String KEY_DIAG_THROTTLE = "tormozit.irFormatTextKeyDiagAt"; //$NON-NLS-1$

    private static final long KEY_DIAG_THROTTLE_MS = 500L;

    /**
     * Набор DtGranularEditor-ов, к которым уже добавлен IPageChangedListener.
     * WeakHashMap без значений используется как WeakHashSet: не удерживает редакторы в памяти.
     */
    private final Set<DtGranularEditor<?>> hookedGranularEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    // =========================================================================
    // IStartup
    // =========================================================================

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
//          Activator.getDefault().getInjector().injectMembers(this); // Слишком рано?

            // Подписка на будущие окна — сразу, без задержки.
            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });

            // Для уже открытых редакторов (восстановлённая сессия) — даём EDT время
            // завершить инициализацию маркеров (DtGranularEditorMarkerSupport),
            // иначе race condition при старте приводит к NPE в BuildMarkersJob.
//            Display.getDefault().timerExec(3000, () ->
//            {
                for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows())
                    hookWindow(w);
//            });
        });
    }

    // =========================================================================
    // Подключение к окну / редактору
    // =========================================================================

    private void hookWindow(IWorkbenchWindow window)
    {
        // Уже открытые редакторы (восстановленная сессия)
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart ed = ref.getEditor(false);
                if (ed != null) hookEditorIfNeeded(ed);
            }
        }

        // Будущие редакторы
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference)) return;
                IEditorPart ed = ((IEditorReference) ref).getEditor(false);
                if (ed != null) hookEditorIfNeeded(ed);
            }

            @Override public void partActivated(IWorkbenchPartReference r)
            {
                if (!(r instanceof IEditorReference))
                    return;
                IEditorPart ed = ((IEditorReference) r).getEditor(false);
                if (ed != null)
                    hookEditorIfNeeded(ed);
            }
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
        if (editor instanceof BslXtextEditor)
            hookBslEditor((BslXtextEditor) editor);
        else if (editor instanceof DtGranularEditor<?>)
            hookGranularEditor((DtGranularEditor<?>) editor);
    }

    // =========================================================================
    // Автономный BslXtextEditor
    // =========================================================================

    private void hookBslEditor(BslXtextEditor editor)
    {
        // Попытка немедленно, если виджет ещё не готов — повтор через asyncExec
        Display.getDefault().asyncExec(() -> attachMenuToBslEditor(editor));
    }

    /**
     * Прикрепляет {@link MenuAdapter} к SWT-меню StyledText виджета редактора.
     * Если виджет ещё не создан или меню не установлено — планирует повтор.
     * Дублирование предотвращается флагом {@link #HOOK_MARKER} на виджете.
     */
    private void attachMenuToBslEditor(BslXtextEditor editor)
    {
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer instanceof SourceViewer sourceViewer)
        {
            StyledText textWidget = sourceViewer.getTextWidget();
            if (textWidget != null && !textWidget.isDisposed())
                ensureEmbeddedHooks(editor, textWidget);
        }

        if (editor.getSite() == null)
            return; // редактор ещё не инициализирован — меню позже

        if (!(viewer instanceof SourceViewer))
        {
            // Viewer ещё не создан — попробуем чуть позже
            Display.getDefault().asyncExec(() -> attachMenuToBslEditor(editor));
            return;
        }

        StyledText textWidget = ((SourceViewer) viewer).getTextWidget();
        if (textWidget == null || textWidget.isDisposed())
            return;

        // Защита от повторного прикрепления меню
        if (Boolean.TRUE.equals(textWidget.getData(HOOK_MARKER)))
            return;

        Menu menu = textWidget.getMenu();
        if (menu == null || menu.isDisposed())
        {
            // Меню ещё не создано (XtextEditor регистрирует его позже)
            Display.getDefault().asyncExec(() -> attachMenuToBslEditor(editor));
            return;
        }

        textWidget.setData(HOOK_MARKER, Boolean.TRUE);

        if (IrFormatTextDebug.isKeyDiagnosticEnabled())
        {
            IrFormatTextDebug.log("bsl hook attached editor=" //$NON-NLS-1$
                + IrFormatTextDebug.formatEditorBrief(editor)
                + " widget=StyledText@0x" + Integer.toHexString(System.identityHashCode(textWidget))); //$NON-NLS-1$
        }

        MenuAdapter listener = buildMenuListener(editor);
        menu.addMenuListener(listener);

        // Очистка при уничтожении виджета
        textWidget.addDisposeListener(e ->
        {
            if (!menu.isDisposed())
                menu.removeMenuListener(listener);
        });
    }

    /** Контекст Xtext и диагностика клавиш — без ожидания меню. */
    static void ensureEmbeddedHooks(BslXtextEditor editor, StyledText textWidget)
    {
        if (editor == null || textWidget == null || textWidget.isDisposed())
            return;
        attachKeyDiagnostic(editor, textWidget);
        EmbeddedBslXtextContextHook.attach(editor, textWidget);
        TextEditorIdentifierSelectionHook.attachToTextEditor(editor, textWidget);
    }

    private static void attachKeyDiagnostic(BslXtextEditor editor, StyledText textWidget)
    {
        if (!IrFormatTextDebug.isKeyDiagnosticEnabled())
            return;
        if (Boolean.TRUE.equals(textWidget.getData(KEY_DIAG_MARKER)))
            return;

        Listener keyListener = event ->
        {
            if (!IrFormatTextDebug.isKeyDiagnosticEnabled())
                return;
            if (IrFormatTextDebug.looksLikeAltShiftF(event))
            {
                long now = System.currentTimeMillis();
                Object lastAt = textWidget.getData(KEY_DIAG_THROTTLE);
                if (lastAt instanceof Long last && now - last < KEY_DIAG_THROTTLE_MS)
                    return;
                textWidget.setData(KEY_DIAG_THROTTLE, now);
                IrFormatTextDebug.logKeyDiagnostics(editor, event);
                return;
            }
            if (IrFormatTextDebug.isFKey(event))
                IrFormatTextDebug.step("keyProbe widget F-key", IrFormatTextDebug.formatKeyEvent(event)); //$NON-NLS-1$
            if (IrFormatTextDebug.hasAltShiftModifiers(event))
                IrFormatTextDebug.step("keyProbe widget alt+shift", IrFormatTextDebug.formatKeyEvent(event)); //$NON-NLS-1$
        };

        textWidget.addListener(SWT.KeyDown, keyListener);
        textWidget.setData(KEY_DIAG_MARKER, Boolean.TRUE);
        textWidget.addDisposeListener(e -> textWidget.removeListener(SWT.KeyDown, keyListener));
    }

    // =========================================================================
    // Многостраничный DtGranularEditor (редактор модуля EDT)
    // =========================================================================

    private void hookGranularEditor(DtGranularEditor<?> editor)
    {
        // Текущая активная страница (если уже открыта нужная)
        hookGranularEditorActivePage(editor);

        // Подписываемся на смену страниц (одна подписка на редактор)
        if (hookedGranularEditors.add(editor))
        {
            IPageChangedListener pageListener = new IPageChangedListener()
            {
                @Override
                public void pageChanged(PageChangedEvent event)
                {
                    Object selectedPage = event.getSelectedPage();
                    if (selectedPage instanceof DtGranularEditorXtextEditorPage<?>)
                    {
                        IEditorPart embedded =
                            ((DtGranularEditorXtextEditorPage<?>) selectedPage)
                                .getEmbeddedEditor();
                        if (embedded instanceof BslXtextEditor)
                            hookBslEditor((BslXtextEditor) embedded);
                    }
                }
            };
            editor.addPageChangedListener(pageListener);
        }
    }

    private void hookGranularEditorActivePage(DtGranularEditor<?> editor)
    {
        IFormPage activePage = editor.getActivePageInstance();
        if (!(activePage instanceof DtGranularEditorXtextEditorPage<?>)) return;
        IEditorPart embedded =
            ((DtGranularEditorXtextEditorPage<?>) activePage).getEmbeddedEditor();
        if (embedded instanceof BslXtextEditor)
            hookBslEditor((BslXtextEditor) embedded);
    }

    // =========================================================================
    // Поиск штатного подменю «Окружить»
    // =========================================================================

    private static Menu findExistingSurroundSubmenu(Menu menu)
    {
        if (menu == null || menu.isDisposed())
            return null;
        for (MenuItem item : menu.getItems())
        {
            if (item.isDisposed())
                continue;
            String text = item.getText();
            boolean isCascade = (item.getStyle() & SWT.CASCADE) != 0;
            if (!isCascade)
                continue;
            if (text != null && text.replace("&", "").trim().equals("Окружить"))
            {
                Menu sub = item.getMenu();
                if (sub != null && !sub.isDisposed())
                    return sub;
            }
        }
        return null;
    }

    // =========================================================================
    // Построение MenuAdapter
    // =========================================================================

    /**
     * Создаёт MenuAdapter, который при показе меню добавляет пункт
     * «Редактировать вложенный текст», а при скрытии — удаляет его.
     *
     * <p>Динамическое добавление/удаление (в отличие от статической регистрации)
     * позволяет проверить перед показом, применима ли команда в текущем контексте.
     */
    private static MenuAdapter buildMenuListener(BslXtextEditor editor)
    {
        return new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(5);

            @Override
            public void menuShown(MenuEvent e)
            {
                Menu menu = (Menu) e.widget;

                Menu comfortSub = ComfortSubmenuHelper.findOrCreateComfortSubmenu(menu, menu.getShell());
                if (comfortSub != null)
                {
                    MenuItem constructorItem = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH,
                        ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                            ITEM_TEXT_MethodConstructor,
                            IrMethodConstructorHandler.COMMAND_ID,
                            EditEmbeddedTextCommandHandler.BINDING_CONTEXT_ID));
                    constructorItem.setToolTipText(
                        "Открыть конструктор метода приложения ИР" + Global.pluginSignForTooltip());
                    constructorItem.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent ev)
                        {
                            runMethodConstructorCommand(editor);
                        }
                    });
                    addedItems.add(constructorItem);

                    MenuItem findRefsItem =
                        ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH, ITEM_TEXT_FindReferences);
                    findRefsItem.setToolTipText(
                        "Найти ссылки на слово или объект метаданных через приложение ИР"
                            + Global.pluginSignForTooltip());
                    findRefsItem.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent ev)
                        {
                            runFindReferencesCommand(editor);
                        }
                    });
                    addedItems.add(findRefsItem);

                    MenuItem moduleCheckItem = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH,
                        ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                            ITEM_TEXT_ModuleCheck,
                            IrModuleCheckHandler.COMMAND_ID,
                            IrModuleCheckCommandHandler.BINDING_CONTEXT_ID));
                    moduleCheckItem.setToolTipText(
                        "Открыть проверку модуля через приложение ИР" + Global.pluginSignForTooltip());
                    moduleCheckItem.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent ev)
                        {
                            runModuleCheckCommand(editor);
                        }
                    });
                    addedItems.add(moduleCheckItem);

                    MenuItem formatItem = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH,
                        ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                            ITEM_TEXT_FormatText,
                            IrFormatTextCommandHandler.COMMAND_ID,
                            IrFormatTextCommandHandler.BINDING_CONTEXT_ID));
                    formatItem.setToolTipText(
                        "Форматировать выделенный текст через приложение ИР" + Global.pluginSignForTooltip());
                    formatItem.setEnabled(IrFormatTextHandler.isApplicableBsl(editor));
                    formatItem.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent ev)
                        {
                            runFormatTextCommand(editor);
                        }
                    });
                    addedItems.add(formatItem);

                    MenuItem declareTypeItem = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH,
                        ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                            ITEM_TEXT_DeclareExpressionType,
                            IrDeclareExpressionTypeHandler.COMMAND_ID,
                            IrDeclareExpressionTypeCommandHandler.BINDING_CONTEXT_ID));
                    declareTypeItem.setToolTipText(
                        "Явно объявить тип выражения под кареткой через приложение ИР" + Global.pluginSignForTooltip());
                    declareTypeItem.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent ev)
                        {
                            runDeclareExpressionTypeCommand(editor);
                        }
                    });
                    addedItems.add(declareTypeItem);
                }

                Menu surroundSub = findExistingSurroundSubmenu(menu);
                if (surroundSub != null && !Boolean.TRUE.equals(surroundSub.getData("tormozit.surroundHooked")))
                {
                    surroundSub.setData("tormozit.surroundHooked", Boolean.TRUE);
                    surroundSub.addMenuListener(new MenuAdapter()
                    {
                        @Override
                        public void menuShown(MenuEvent ev)
                        {
                            for (BslSurroundHandler.SurroundKind kind : BslSurroundHandler.SurroundKind.values())
                            {
                                MenuItem item = new MenuItem(surroundSub, SWT.PUSH);
                                item.setText(kind.label);
                                item.setToolTipText(kind.label + Global.pluginSignForTooltip() + SURROUND_HINT);
                                item.addSelectionListener(new SelectionAdapter()
                                {
                                    @Override
                                    public void widgetSelected(SelectionEvent e)
                                    {
                                        BslSurroundHandler.surroundWith(editor, kind);
                                    }
                                });
                            }
                        }

                        @Override
                        public void menuHidden(MenuEvent ev)
                        {
                            Display display = ((Menu) ev.widget).getDisplay();
                            display.asyncExec(() ->
                            {
                                for (MenuItem mi : ((Menu) ev.widget).getItems())
                                    if (!mi.isDisposed()) mi.dispose();
                            });
                        }
                    });
                }

                if (EditEmbeddedTextHandler.isApplicable(editor))
                {
                    MenuItem item = new MenuItem(menu, SWT.PUSH);
                    item.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                        EditEmbeddedTextCommandHandler.MENU_LABEL,
                        EditEmbeddedTextCommandHandler.COMMAND_ID,
                        EditEmbeddedTextCommandHandler.BINDING_CONTEXT_ID));
                    item.setToolTipText(
                        "Открыть вложенный текст в редакторе текста приложения ИР" + Global.pluginSignForTooltip());
                    item.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent e)
                        {
                            runEmbeddedTextCommand(editor);
                        }
                    });
                    addedItems.add(item);
                }

                if (DebugIRHandler.isApplicable(editor))
                {
                    MenuItem item = new MenuItem(menu, SWT.PUSH);
                    item.setText(ITEM_TEXT_DebugIR);
                    item.setToolTipText(
                        "Текущее выражение передается в отладочный инструмент в окно приложения ИР или предмета отладки" + Global.pluginSignForTooltip());
                    item.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent e)
                        {
                            DebugIRHandler.debugObject(editor);
                        }
                    });
                    addedItems.add(item);
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
                    for (MenuItem mi : toDispose)
                        if (!mi.isDisposed()) mi.dispose();
                });
            }
        };
    }

    private static void runMethodConstructorCommand(BslXtextEditor editor)
    {
        try
        {
            IHandlerService handlerService = editor.getSite().getService(IHandlerService.class);
            if (handlerService != null)
            {
                handlerService.executeCommand(IrMethodConstructorHandler.COMMAND_ID, null);
                return;
            }
        }
        catch (Exception ignored)
        {
            // fallback ниже
        }
        IrMethodConstructorHandler.openMethodConstructor(editor);
    }

    private static void runEmbeddedTextCommand(BslXtextEditor editor)
    {
        try
        {
            IHandlerService handlerService = editor.getSite().getService(IHandlerService.class);
            if (handlerService != null)
            {
                handlerService.executeCommand(EditEmbeddedTextCommandHandler.COMMAND_ID, null);
                return;
            }
        }
        catch (Exception ignored)
        {
            // fallback ниже
        }
        EditEmbeddedTextHandler.editEmbeddedText(editor);
    }

    private static void runFormatTextCommand(BslXtextEditor editor)
    {
        try
        {
            IHandlerService handlerService = editor.getSite().getService(IHandlerService.class);
            if (handlerService != null)
            {
                handlerService.executeCommand(IrFormatTextCommandHandler.COMMAND_ID, null);
                IrFormatTextDebug.log("menu executeCommand OK"); //$NON-NLS-1$
                return;
            }
            IrFormatTextDebug.log("menu handlerService=null → fallback"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            IrFormatTextDebug.log("menu executeCommand failed: " + e.getMessage() + " → fallback"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IrFormatTextHandler.formatBslModule(editor);
    }

    private static void runFindReferencesCommand(BslXtextEditor editor)
    {
        try
        {
            IHandlerService handlerService = editor.getSite().getService(IHandlerService.class);
            if (handlerService != null)
            {
                handlerService.executeCommand(IrFindReferencesHandler.COMMAND_ID, null);
                return;
            }
        }
        catch (Exception ignored)
        {
            // fallback ниже
        }
        IrFindReferencesHandler.findInModules(editor);
    }

    private static void runModuleCheckCommand(BslXtextEditor editor)
    {
        try
        {
            IHandlerService handlerService = editor.getSite().getService(IHandlerService.class);
            if (handlerService != null)
            {
                handlerService.executeCommand(IrModuleCheckHandler.COMMAND_ID, null);
                return;
            }
        }
        catch (Exception ignored)
        {
            // fallback ниже
        }
        IrModuleCheckHandler.checkModule(editor);
    }

    private static void runDeclareExpressionTypeCommand(BslXtextEditor editor)
    {
        try
        {
            IHandlerService handlerService = editor.getSite().getService(IHandlerService.class);
            if (handlerService != null)
            {
                handlerService.executeCommand(IrDeclareExpressionTypeHandler.COMMAND_ID, null);
                return;
            }
        }
        catch (Exception ignored)
        {
            // fallback ниже
        }
        IrDeclareExpressionTypeHandler.declareExpressionType(editor);
    }
}
