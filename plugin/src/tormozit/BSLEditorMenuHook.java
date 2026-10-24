package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.e4.ui.model.application.ui.menu.MHandledItem;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IVerticalRulerInfo;
import org.eclipse.ui.texteditor.MarkerAnnotation;
import org.eclipse.e4.ui.workbench.renderers.swt.HandledContributionItem;
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
import org.eclipse.ui.menus.CommandContributionItem;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslBreakpointFactory;
import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslLineBreakpoint;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Доработки контекстных меню BSL-редактора EDT: команды работы с текстом
 * и копирование/вставка настроек остановок в меню левой полосы.
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
    private static final String ITEM_TEXT_QueryConstructor = IrQueryConstructorHandler.MENU_LABEL;
    private static final String ITEM_TEXT_FormatText = IrFormatTextHandler.MENU_LABEL;
    private static final String ITEM_TEXT_FindReferences = IrFindReferencesHandler.MENU_LABEL;
    private static final String ITEM_TEXT_GitFragmentSearch = IrGitFragmentSearchHandler.MENU_LABEL;
    private static final String ITEM_TEXT_ModuleCheck =IrModuleCheckHandler.MENU_LABEL;
    private static final String ITEM_TEXT_ModuleCheckClear = IrModuleCheckClearHandler.MENU_LABEL;
    private static final String ITEM_TEXT_DeclareExpressionType = DeclareExpressionTypeHandler.MENU_LABEL;
    private static final String GENERATE_METHOD_COMMENT_COMMAND_ID = "com._1c.g5.v8.dt.bsl.ui.BslGenerateMethodComment"; //$NON-NLS-1$
    private static final String GENERATE_METHOD_COMMENT_HOOKED = "tormozit.generateMethodCommentTooltipHooked"; //$NON-NLS-1$
    private static final String ITEM_TEXT_ResetFolding = ResetFoldingHandler.MENU_LABEL;

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

    /** Число повторов ожидания viewer/меню через asyncExec, прежде чем сдаться (защита от вечного цикла). */
    private static final int MAX_ATTACH_ATTEMPTS = 100;

    private void hookBslEditor(BslXtextEditor editor)
    {
        // Попытка немедленно, если виджет ещё не готов — повтор через asyncExec
        Display.getDefault().asyncExec(() -> attachMenuToBslEditor(editor, 0));
    }

    /**
     * Прикрепляет {@link MenuAdapter} к SWT-меню StyledText виджета редактора.
     * Если виджет ещё не создан или меню не установлено — планирует повтор.
     * Дублирование предотвращается флагом {@link #HOOK_MARKER} на виджете.
     */
    private void attachMenuToBslEditor(BslXtextEditor editor, int attempt)
    {
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer instanceof SourceViewer sourceViewer)
        {
            StyledText textWidget = sourceViewer.getTextWidget();
            if (textWidget != null && !textWidget.isDisposed())
                ensureEmbeddedHooks(editor, textWidget);
        }

        if (editor.getSite() == null || isWorkbenchClosing())
            return; // редактор ещё не инициализирован (или EDT закрывается) — меню позже

        if (!(viewer instanceof SourceViewer))
        {
            // Viewer ещё не создан — попробуем чуть позже, но не бесконечно
            // (иначе asyncExec крутится вечно и не даёт Display.release() опустошить очередь, issue #130)
            if (attempt >= MAX_ATTACH_ATTEMPTS)
                return;
            Display.getDefault().asyncExec(() -> attachMenuToBslEditor(editor, attempt + 1));
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
            if (attempt >= MAX_ATTACH_ATTEMPTS)
                return;
            Display.getDefault().asyncExec(() -> attachMenuToBslEditor(editor, attempt + 1));
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

    private static boolean isWorkbenchClosing()
    {
        return !PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing();
    }

    /** Контекст Xtext и диагностика клавиш — без ожидания меню. */
    static void ensureEmbeddedHooks(BslXtextEditor editor, StyledText textWidget)
    {
        if (editor == null || textWidget == null || textWidget.isDisposed())
            return;
        attachKeyDiagnostic(editor, textWidget);
        EmbeddedBslXtextContextHook.attach(editor, textWidget);
        TextEditorIdentifierSelectionHook.attachToTextEditor(editor, textWidget);
        BreakpointRulerMenu.attach(editor, textWidget);
    }

    /** Меню левой полосы: снимок настроек общий для всех модулей до закрытия EDT. */
    private static final class BreakpointRulerMenu
    {
        private static final String HOOKED = "tormozit.breakpointRulerMenu"; //$NON-NLS-1$
        private static final String LOG_TOPIC = "bp-copy-paste"; //$NON-NLS-1$
        // Только настройки: расположение, сигнатура метода, регистрация и текущий
        // счётчик попаданий принадлежат целевой остановке и сюда не входят.
        private static final String[] SETTINGS = {
            IBreakpoint.ENABLED,
            IBslLineBreakpoint.CONDITION,
            IBslLineBreakpoint.HIT_COUNT,
            IBslLineBreakpoint.HIT_CONDITION,
            IBslLineBreakpoint.PARENT_METHOD_TO_BREAK_ON,
            IBslLineBreakpoint.PUT_DESCRIPTION,
            IBslLineBreakpoint.PUT_EXPRESSION_TO_EVALUATE,
            IBslLineBreakpoint.PUT_STACK_TRACE,
            IBslLineBreakpoint.PUT_HIT_COUNT,
            IBslLineBreakpoint.CONTINUE_EXECUTION,
            // Сохранённые значения и общий флажок из BreakpointPropertiesHook.
            "tormozit.savedCondition", //$NON-NLS-1$
            "tormozit.savedExpression", //$NON-NLS-1$
            "tormozit.savedDescription", //$NON-NLS-1$
            "tormozit.savedConsoleOutput", //$NON-NLS-1$
            "tormozit.consoleOutput", //$NON-NLS-1$
            "tormozit.consoleHiddenDescription" //$NON-NLS-1$
        };
        private static Map<String, Object> copiedSettings;

        static void attach(BslXtextEditor editor, StyledText text)
        {
            if (Boolean.TRUE.equals(text.getData(HOOKED)))
                return;
            text.setData(HOOKED, Boolean.TRUE);
            IMenuListener listener = menu -> populate(editor, menu);
            editor.addRulerContextMenuListener(listener);
            text.addDisposeListener(e -> editor.removeRulerContextMenuListener(listener));
            Global.tempLog(LOG_TOPIC, "attach editor=" + editor.getEditorInput()); //$NON-NLS-1$
        }

        private static void populate(BslXtextEditor editor, IMenuManager menu)
        {
            ISourceViewer viewer = editor.getInternalSourceViewer();
            IVerticalRulerInfo ruler = editor.getAdapter(IVerticalRulerInfo.class);
            IFile file = editor.getEditorInput().getAdapter(IFile.class);
            if (viewer == null || viewer.getDocument() == null || ruler == null || file == null
                    || !"bsl".equalsIgnoreCase(file.getFileExtension())) //$NON-NLS-1$
                return;
            int line = ruler.getLineOfLastMouseButtonActivity();
            if (line < 0 || line >= viewer.getDocument().getNumberOfLines())
                return;
            try
            {
                IBslLineBreakpoint breakpoint = findBreakpoint(viewer, line);
                Global.tempLog(LOG_TOPIC, "menu file=" + file.getFullPath() + " line=" + (line + 1) //$NON-NLS-1$ //$NON-NLS-2$
                    + " breakpoint=" + (breakpoint != null) + " copied=" + (copiedSettings != null)); //$NON-NLS-1$ //$NON-NLS-2$
                addCommand(menu, "copy", "Скопировать точку остановка", //$NON-NLS-1$
                    "Запомнить настройки остановки для вставки в другую строку модуля.",
                    breakpoint != null, () -> copy(breakpoint));
                addCommand(menu, "paste", "Вставить точку остановка", //$NON-NLS-1$
                    "Создать остановку с запомненными настройками. Если остановка уже есть, заменить её настройки.",
                    copiedSettings != null, () -> paste(file, line + 1, breakpoint));
            }
            catch (BadLocationException e)
            {
                reportFailure(e);
            }
        }

        private static void addCommand(IMenuManager menu, String id, String label, String tooltip,
                boolean enabled, Runnable command)
        {
            String itemId = "tormozit.breakpointRuler." + id; //$NON-NLS-1$
            menu.remove(itemId);
            ContributionItem contribution = new ContributionItem(itemId)
            {
                @Override
                public void fill(Menu parent, int index)
                {
                    MenuItem item = index < 0 ? new MenuItem(parent, SWT.PUSH)
                        : new MenuItem(parent, SWT.PUSH, index);
                    item.setText(label);
                    item.setEnabled(enabled);
                    ComfortSubmenuHelper.setMenuItemTooltip(item, tooltip);
                    item.addListener(SWT.Selection, event -> command.run());
                }
            };
            if (menu.find("additions") != null) //$NON-NLS-1$
                menu.appendToGroup("additions", contribution); //$NON-NLS-1$
            else
                menu.add(contribution);
        }

        /** Позиция аннотации учитывает несохранённые правки и свёртки выше строки. */
        private static IBslLineBreakpoint findBreakpoint(ISourceViewer viewer, int line)
                throws BadLocationException
        {
            IAnnotationModel model = viewer.getAnnotationModel();
            if (model == null)
                return null;
            var annotations = model.getAnnotationIterator();
            while (annotations.hasNext())
            {
                var annotation = annotations.next();
                if (!(annotation instanceof MarkerAnnotation markerAnnotation))
                    continue;
                Position position = model.getPosition(annotation);
                if (position == null || position.isDeleted()
                        || viewer.getDocument().getLineOfOffset(position.getOffset()) != line)
                    continue;
                IBreakpoint breakpoint = DebugPlugin.getDefault().getBreakpointManager()
                    .getBreakpoint(markerAnnotation.getMarker());
                if (breakpoint instanceof IBslLineBreakpoint bslBreakpoint)
                    return bslBreakpoint;
            }
            return null;
        }

        private static void copy(IBslLineBreakpoint breakpoint)
        {
            try
            {
                Map<String, Object> attributes = breakpoint.getMarker().getAttributes();
                Map<String, Object> snapshot = new LinkedHashMap<>();
                for (String key : SETTINGS)
                    snapshot.put(key, attributes.get(key));
                copiedSettings = Collections.unmodifiableMap(snapshot);
                Global.tempLog(LOG_TOPIC, "copy marker=" + breakpoint.getMarker().getId()); //$NON-NLS-1$
            }
            catch (CoreException e)
            {
                reportFailure(e);
            }
        }

        private static void paste(IFile file, int line, IBslLineBreakpoint existing)
        {
            Map<String, Object> snapshot = copiedSettings;
            if (snapshot == null)
                return;
            IBslLineBreakpoint created = null;
            try
            {
                IBslLineBreakpoint target = existing;
                if (target == null)
                {
                    IBslBreakpointFactory factory = CreateDebuggerBreakpoints.resolveFactory();
                    if (factory == null)
                    {
                        Global.tempLog(LOG_TOPIC, "paste: breakpoint factory unavailable"); //$NON-NLS-1$
                        ToastNotification.show("Точки останова", "Не удалось получить службу создания остановок EDT.");
                        return;
                    }
                    target = factory.createLineBreakpoint(file, line);
                    created = target;
                }
                // null удаляет отсутствовавшие у источника настройки существующей точки.
                // Все настройки меняются одним событием маркера, включая enabled.
                IMarker marker = target.getMarker();
                marker.setAttributes(SETTINGS, snapshot.values().toArray());
                if (created != null)
                    DebugPlugin.getDefault().getBreakpointManager().addBreakpoint(created);
                Global.tempLog(LOG_TOPIC, "paste file=" + file.getFullPath() + " line=" + line //$NON-NLS-1$ //$NON-NLS-2$
                    + " created=" + (created != null)); //$NON-NLS-1$
            }
            catch (CoreException e)
            {
                if (created != null)
                {
                    try
                    {
                        created.delete();
                    }
                    catch (CoreException cleanupError)
                    {
                        e.addSuppressed(cleanupError);
                    }
                }
                reportFailure(e);
            }
        }

        private static void reportFailure(Exception error)
        {
            Global.tempLog(LOG_TOPIC, "failure: " + error); //$NON-NLS-1$
            ToastNotification.show("Точки останова", "Не удалось скопировать или вставить остановку: "
                + error.getMessage());
        }
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
    // Подсказка штатного «Генерировать комментарии к методу» (Источник)
    // =========================================================================

    private static String declareExpressionTypeTooltip()
    {
        return "Объявить тип выражения, параметра или результата метода. При подключённом ИР команда выполняется через ИР.\n"
            + "Без ИР у метода без комментария создаётся комментарий. С флажком «"
            + ComfortVisibleSettings.label(ComfortSettings.PREF_ECONOMICAL_METHOD_COMMENT)
            + "» (Параметры → Комфорт → Редактор кода) добавляется минимально необходимый комментарий "
            + "для указания типа функции или параметра";
    }

    /**
     * Штатные подменю заполняются при их показе, поэтому слушатель ставим на каждое подменю один раз:
     * он срабатывает после заполнения и находит пункт команды по идентификатору, а не по надписи.
     */
    private static void hookStockGenerateMethodCommentTooltip(Menu menu)
    {
        for (MenuItem item : menu.getItems())
        {
            if (item.isDisposed() || (item.getStyle() & SWT.CASCADE) == 0)
                continue;
            Menu sub = item.getMenu();
            if (sub == null || sub.isDisposed() || Boolean.TRUE.equals(sub.getData(GENERATE_METHOD_COMMENT_HOOKED)))
                continue;
            sub.setData(GENERATE_METHOD_COMMENT_HOOKED, Boolean.TRUE);
            sub.addMenuListener(new MenuAdapter()
            {
                @Override
                public void menuShown(MenuEvent e)
                {
                    for (MenuItem stock : sub.getItems())
                        if (!stock.isDisposed() && isGenerateMethodCommentCommand(stock.getData()))
                            ComfortSubmenuHelper.setMenuItemTooltip(stock, declareExpressionTypeTooltip());
                }
            });
        }
    }

    /** Оба штатных представления команды: JFace и E4. */
    private static boolean isGenerateMethodCommentCommand(Object data)
    {
        if (data instanceof CommandContributionItem contribution)
            return contribution.getCommand() != null
                && GENERATE_METHOD_COMMENT_COMMAND_ID.equals(contribution.getCommand().getId());
        if (data instanceof HandledContributionItem contribution)
        {
            MHandledItem model = contribution.getModel();
            if (model == null)
                return false;
            if (model.getWbCommand() != null)
                return GENERATE_METHOD_COMMENT_COMMAND_ID.equals(model.getWbCommand().getId());
            return model.getCommand() != null
                && GENERATE_METHOD_COMMENT_COMMAND_ID.equals(model.getCommand().getElementId());
        }
        return false;
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
                    ComfortSubmenuHelper.setMenuItemTooltip(constructorItem,
                        "Открыть конструктор метода приложения ИР");
                    constructorItem.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent ev)
                        {
                            runMethodConstructorCommand(editor);
                        }
                    });
                    addedItems.add(constructorItem);

                    MenuItem queryConstructorItem = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH,
                        ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                            ITEM_TEXT_QueryConstructor,
                            IrQueryConstructorHandler.COMMAND_ID,
                            EditEmbeddedTextCommandHandler.BINDING_CONTEXT_ID));
                    ComfortSubmenuHelper.setMenuItemTooltip(queryConstructorItem,
                        "Открыть конструктор запроса приложения ИР для текста запроса под кареткой");
                    queryConstructorItem.setEnabled(IrQueryConstructorHandler.isApplicable(editor));
                    queryConstructorItem.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent ev)
                        {
                            runQueryConstructorCommand(editor);
                        }
                    });
                    addedItems.add(queryConstructorItem);

                    MenuItem findRefsItem =
                        ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH, ITEM_TEXT_FindReferences);
                    ComfortSubmenuHelper.setMenuItemTooltip(findRefsItem,
                        "Найти ссылки на слово или объект метаданных через приложение ИР");
                    findRefsItem.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent ev)
                        {
                            runFindReferencesCommand(editor);
                        }
                    });
                    addedItems.add(findRefsItem);

                    if (IrGitFragmentSearchHandler.isGitProject(editor))
                    {
                        MenuItem gitFragmentItem = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH,
                            ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                                ITEM_TEXT_GitFragmentSearch,
                                IrGitFragmentSearchHandler.COMMAND_ID,
                                IrGitFragmentSearchHandler.BINDING_CONTEXT_ID));
                        ComfortSubmenuHelper.setMenuItemTooltip(gitFragmentItem,
                            "Найти выделенный фрагмент в истории Git модуля через приложение ИР."
                                + " Без выделения открывается история Git модуля на текущей строке");
                        gitFragmentItem.addSelectionListener(new SelectionAdapter()
                        {
                            @Override
                            public void widgetSelected(SelectionEvent ev)
                            {
                                runGitFragmentSearchCommand(editor);
                            }
                        });
                        addedItems.add(gitFragmentItem);
                    }

                    MenuItem moduleCheckItem = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH,
                        ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                            ITEM_TEXT_ModuleCheck,
                            IrModuleCheckHandler.COMMAND_ID,
                            IrModuleCheckCommandHandler.BINDING_CONTEXT_ID));
                    ComfortSubmenuHelper.setMenuItemTooltip(moduleCheckItem,
                        "Открыть проверку модуля через приложение ИР");
                    moduleCheckItem.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent ev)
                        {
                            runModuleCheckCommand(editor);
                        }
                    });
                    addedItems.add(moduleCheckItem);

                    MenuItem moduleCheckClearItem = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH,
                        ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                            ITEM_TEXT_ModuleCheckClear,
                            IrModuleCheckClearHandler.COMMAND_ID,
                            IrModuleCheckClearCommandHandler.BINDING_CONTEXT_ID));
                    ComfortSubmenuHelper.setMenuItemTooltip(moduleCheckClearItem,
                        "Удалить все маркеры, оставленные проверкой модуля ИР, для текущего проекта");
                    moduleCheckClearItem.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent ev)
                        {
                            runModuleCheckClearCommand(editor);
                        }
                    });
                    addedItems.add(moduleCheckClearItem);

                    MenuItem formatItem = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH,
                        ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                            ITEM_TEXT_FormatText,
                            IrFormatTextCommandHandler.COMMAND_ID,
                            IrFormatTextCommandHandler.BINDING_CONTEXT_ID));
                    ComfortSubmenuHelper.setMenuItemTooltip(formatItem,
                        "Форматировать выделенный текст через приложение ИР");
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
                            GENERATE_METHOD_COMMENT_COMMAND_ID,
                            IrDeclareExpressionTypeCommandHandler.BINDING_CONTEXT_ID));
                    ComfortSubmenuHelper.setMenuItemTooltip(declareTypeItem, declareExpressionTypeTooltip());
                    declareTypeItem.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent ev)
                        {
                            runDeclareExpressionTypeCommand(editor);
                        }
                    });
                    addedItems.add(declareTypeItem);

                    MenuItem resetFoldingItem = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH,
                        ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                            ITEM_TEXT_ResetFolding,
                            ResetFoldingHandler.COMMAND_ID,
                            ResetFoldingHandler.BINDING_CONTEXT_ID));
                    ComfortSubmenuHelper.setMenuItemTooltip(resetFoldingItem,
                        "Вернуть сворачиваемые группы к состоянию как при открытии модуля");
                    resetFoldingItem.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent ev)
                        {
                            runResetFoldingCommand(editor);
                        }
                    });
                    addedItems.add(resetFoldingItem);

                    addedItems.add(BslHoverHintState.addMenuItem(comfortSub));
                } 

                hookStockGenerateMethodCommentTooltip(menu);

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
                    ComfortSubmenuHelper.setMenuItemTooltip(item,
                        "Редактировать строковый литерал в отдельном окне: подсветка Markdown/JSON/XML/HTML и готовый текст. "
                            + "При подключённом приложении ИР — в редакторе текста ИР");
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
                    ComfortSubmenuHelper.setMenuItemTooltip(item,
                        "Текущее выражение передается в отладочный инструмент в окно приложения ИР или предмета отладки");
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

    private static void runQueryConstructorCommand(BslXtextEditor editor)
    {
        try
        {
            IHandlerService handlerService = editor.getSite().getService(IHandlerService.class);
            if (handlerService != null)
            {
                handlerService.executeCommand(IrQueryConstructorHandler.COMMAND_ID, null);
                return;
            }
        }
        catch (Exception ignored)
        {
            // fallback ниже
        }
        IrQueryConstructorHandler.openQueryConstructor(editor);
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

    private static void runGitFragmentSearchCommand(BslXtextEditor editor)
    {
        try
        {
            IHandlerService handlerService = editor.getSite().getService(IHandlerService.class);
            if (handlerService != null)
            {
                handlerService.executeCommand(IrGitFragmentSearchHandler.COMMAND_ID, null);
                return;
            }
        }
        catch (Exception ignored)
        {
            // fallback ниже
        }
        IrGitFragmentSearchHandler.findFragment(editor);
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

    private static void runModuleCheckClearCommand(BslXtextEditor editor)
    {
        try
        {
            IHandlerService handlerService = editor.getSite().getService(IHandlerService.class);
            if (handlerService != null)
            {
                handlerService.executeCommand(IrModuleCheckClearHandler.COMMAND_ID, null);
                return;
            }
        }
        catch (Exception ignored)
        {
            // fallback ниже
        }
        IrModuleCheckClearHandler.clearMarkers(editor);
    }

    private static void runDeclareExpressionTypeCommand(BslXtextEditor editor)
    {
        try
        {
            IHandlerService handlerService = editor.getSite().getService(IHandlerService.class);
            if (handlerService != null)
            {
                handlerService.executeCommand(DeclareExpressionTypeHandler.COMMAND_ID, null);
                return;
            }
        }
        catch (Exception ignored)
        {
            // fallback ниже
        }
        DeclareExpressionTypeHandler.declareExpressionType(editor);
    }

    private static void runResetFoldingCommand(BslXtextEditor editor)
    {
        try
        {
            IHandlerService handlerService = editor.getSite().getService(IHandlerService.class);
            if (handlerService != null)
            {
                handlerService.executeCommand(ResetFoldingHandler.COMMAND_ID, null);
                return;
            }
        }
        catch (Exception ignored)
        {
            // fallback ниже
        }
        ResetFoldingHandler.resetFolding(editor);
    }
}
