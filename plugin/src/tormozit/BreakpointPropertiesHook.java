package tormozit;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.debug.ui.AbstractDebugView;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.forms.widgets.ExpandableComposite;
import org.eclipse.ui.texteditor.ITextEditor;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.debug.core.IDebugConsoleWriter;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeEventProcessor;
import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslLineBreakpoint;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetId;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetTypeUtil;
import org.eclipse.core.resources.IMarkerDelta;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugElement;

/**
 * Доработки диалога «Свойства для …» (точка останова):
 * гиперссылка «Выводить ИР» (порт RDT {@code КнопкаВставитьВыражениеИзМодуля});
 * поля условия и значения выражения всегда доступны, флажок включается при вводе;
 * прокрутка диалога колесом мыши.
 */
public final class BreakpointPropertiesHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.breakpointPropertiesPatched"; //$NON-NLS-1$
    private static final String LINK_ROW_KEY = "tormozit.breakpointIrLinkRow"; //$NON-NLS-1$
    private static final String RESIZE_KEY = "tormozit.breakpointPaneResize"; //$NON-NLS-1$
    private static final String RESIZE_PANES_KEY = "tormozit.breakpointResizePanes"; //$NON-NLS-1$
    private static final String RESIZE_LISTENER_KEY = "tormozit.breakpointResizeListener"; //$NON-NLS-1$
    private static final String SIZE_MEMORY_KEY = "tormozit.breakpointSizeMemory"; //$NON-NLS-1$
    private static final String CONTENT_ASSIST_KEY = "tormozit.breakpointContentAssistPatched"; //$NON-NLS-1$
    private static final String ALWAYS_ENABLED_KEY = "tormozit.breakpointAlwaysEnabled"; //$NON-NLS-1$
    private static final String AUTO_CONTINUE_KEY = "tormozit.breakpointAutoContinue"; //$NON-NLS-1$
    private static final String WHEEL_SCROLL_KEY = "tormozit.breakpointWheelScroll"; //$NON-NLS-1$
    private static final String PERSIST_KEY = "tormozit.breakpointPersistUnchecked"; //$NON-NLS-1$
    /**
     * Текст условия при снятом флажке «Условный». Штатный {@code doSave} пишет в этом
     * случае пустую строку, а признака «условие выключено» у точки останова нет
     * (условной её делает сама непустая строка) — поэтому текст храним отдельно.
     */
    private static final String MARKER_SAVED_CONDITION = "tormozit.savedCondition"; //$NON-NLS-1$
    /** Текст выражения при снятом флажке «Значение выражения» — см. {@link #MARKER_SAVED_CONDITION}. */
    private static final String MARKER_SAVED_EXPRESSION = "tormozit.savedExpression"; //$NON-NLS-1$
    /** Текст описания при снятом флажке «Описание точки останова» — см. {@link #MARKER_SAVED_CONDITION}. */
    private static final String MARKER_SAVED_DESCRIPTION = "tormozit.savedDescription"; //$NON-NLS-1$
    /**
     * Пункты вывода в консоль, включённые до снятия общего флажка, — имена полей
     * {@code BslBreakpointActionsEditor} через запятую. Включение флажка их возвращает.
     */
    private static final String MARKER_SAVED_CONSOLE_OUTPUT = "tormozit.savedConsoleOutput"; //$NON-NLS-1$
    private static final String CONSOLE_MASTER_KEY = "tormozit.breakpointConsoleMaster"; //$NON-NLS-1$
    private static final String BREAKPOINTS_VIEW_HOOKED_KEY = "tormozit.breakpointsViewConsoleMaster"; //$NON-NLS-1$
    private static final String BSL_LINE_BREAKPOINT_DETAIL_PANE =
            "com._1c.g5.v8.dt.internal.debug.ui.breakpoints.BslLineBreakpointDetailPane"; //$NON-NLS-1$
    private static final String CONSOLE_MASTER_FALLBACK_TEXT = "Вывести в консоль процесса отладки при попадании"; //$NON-NLS-1$
    private static final String CONSOLE_MASTER_TOOLTIP =
            "При попадании выводить строку «> время: модуль:строка, (предмет отладки: сеанс, сервер), метод: текст строки» и продолжать выполнение. Отмеченные ниже пункты выводятся после неё. Снятие выключает и запоминает пункты, включение возвращает."; //$NON-NLS-1$
    /** Общий флажок вывода включён — флажок самостоятельный, пунктами вывода не определяется. */
    private static final String MARKER_CONSOLE_OUTPUT = "tormozit.consoleOutput"; //$NON-NLS-1$
    /**
     * Описание точки останова подставлено плагином: без пунктов вывода сервер отладки сообщения
     * не присылает и строке «Выполнено» не к чему привязаться ({@code BslLineBreakpointInformation.hasMessage}).
     */
    private static final String MARKER_CONSOLE_HIDDEN_DESCRIPTION = "tormozit.consoleHiddenDescription"; //$NON-NLS-1$
    private static final String CONSOLE_HIDDEN_DESCRIPTION = "Выполнено"; //$NON-NLS-1$
    private static final String CONTINUE_EXECUTION_FIELD = "continueExecutionButton"; //$NON-NLS-1$
    private static final String[] CONSOLE_OUTPUT_FIELDS = {
        "enablementDescriptionButton", //$NON-NLS-1$
        "evaluateExpressionButton", //$NON-NLS-1$
        "putStackButton", //$NON-NLS-1$
        "putCurrentHitCountButton" //$NON-NLS-1$
    };
    private static final String BP_FIELD_ROLE_KEY = "tormozit.bpFieldRole"; //$NON-NLS-1$
    private static final String SETTINGS_SECTION = "tormozit.breakpointPropertiesDialog"; //$NON-NLS-1$
    private static final String KEY_DIALOG_WIDTH = "DIALOG_WIDTH"; //$NON-NLS-1$
    private static final String KEY_DIALOG_HEIGHT = "DIALOG_HEIGHT"; //$NON-NLS-1$
    private static final int EDITOR_PANE_MIN_HEIGHT = 48;
    private static final int EDITOR_PANE_HEIGHT_HINT = 64;
    private static final int COMBO_FIELD_MAX_WIDTH = 200;
    private static final String DIALOG_TITLE_PREFIX = "Свойства для"; //$NON-NLS-1$
    private static final String BUTTON_TEXT = "Выводить ИР"; //$NON-NLS-1$
    private static final String BUTTON_TOOLTIP =
            "Вставить вывод значений выражений из выделенного фрагмента или текущей строки модуля."; //$NON-NLS-1$
    private static final String BSL_BREAKPOINT_PAGE =
            "com._1c.g5.v8.dt.internal.debug.ui.breakpoints.BslBreakpointPage"; //$NON-NLS-1$
    private static final String ACTIONS_EDITOR =
            "com._1c.g5.v8.dt.internal.debug.ui.breakpoints.BslBreakpointActionsEditor"; //$NON-NLS-1$
    private static final String CONDITION_EDITOR =
            "com._1c.g5.v8.dt.internal.debug.ui.breakpoints.BslBreakpointConditionEditor"; //$NON-NLS-1$
    private static final String EXPRESSION_PANE =
            "com._1c.g5.v8.dt.internal.debug.ui.breakpoints.BslBreakpointTextAndHistoryEditorPane"; //$NON-NLS-1$
    /** Тема временного лога поля кода BSL этого диалога ({@code .tmp/temp-logs}). */
    private static final String FIELD_LOG_TOPIC = "bp-field"; //$NON-NLS-1$
    private static final String JOIN_SEPARATOR = ", " + "\n"; //$NON-NLS-1$ //$NON-NLS-2$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            install(Display.getDefault());
            installBreakpointsView();
            ConsoleExecutedLine.installDoubleClick(Display.getDefault());
        });
        ConsoleExecutedLine.install();
    }

    /**
     * Панель «Точки останова»: её панель деталей ({@code BslLineBreakpointDetailPane}) строит тот же
     * редактор «Действия», что и диалог, — общий флажок вывода ставим и туда. Редактор один на
     * панель, при выборе другой точки EDT зовёт его {@code setInput}, поэтому сверяемся после
     * каждой смены выделения.
     */
    private static void installBreakpointsView()
    {
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return;
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            hookBreakpointsViewWindow(window);
        workbench.addWindowListener(new IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                hookBreakpointsViewWindow(window);
            }

            @Override
            public void windowClosed(IWorkbenchWindow window) { }

            @Override
            public void windowActivated(IWorkbenchWindow window) { }

            @Override
            public void windowDeactivated(IWorkbenchWindow window) { }
        });
    }

    private static void hookBreakpointsViewWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                hookBreakpointsView(ref.getPart(false));
            }

            @Override
            public void partVisible(IWorkbenchPartReference ref)
            {
                hookBreakpointsView(ref.getPart(false));
            }
        });
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return;
        for (IViewReference ref : page.getViewReferences())
        {
            if (ref != null)
                hookBreakpointsView(ref.getPart(false));
        }
    }

    private static void hookBreakpointsView(IWorkbenchPart part)
    {
        if (!(part instanceof AbstractDebugView view) || view.getViewSite() == null
                || !IDebugUIConstants.ID_BREAKPOINT_VIEW.equals(view.getViewSite().getId()))
            return;
        Viewer viewer = view.getViewer();
        if (viewer == null || viewer.getControl() == null || viewer.getControl().isDisposed())
            return;
        Control control = viewer.getControl();
        if (!Boolean.TRUE.equals(control.getData(BREAKPOINTS_VIEW_HOOKED_KEY)))
        {
            control.setData(BREAKPOINTS_VIEW_HOOKED_KEY, Boolean.TRUE);
            // Панель деталей обновляется слушателем выделения самой панели — сверяемся после него.
            viewer.addSelectionChangedListener(e -> control.getDisplay().asyncExec(() ->
            {
                if (!control.isDisposed())
                    patchBreakpointsViewDetailPane(view);
            }));
        }
        patchBreakpointsViewDetailPane(view);
    }

    /** {@code VariablesView.fDetailPane} → {@code DetailPaneProxy.fCurrentPane} → редактор «Действия». */
    private static void patchBreakpointsViewDetailPane(AbstractDebugView view)
    {
        Object proxy = Global.getField(view, "fDetailPane"); //$NON-NLS-1$
        Object pane = proxy != null ? Global.getField(proxy, "fCurrentPane") : null; //$NON-NLS-1$
        if (pane == null || !BSL_LINE_BREAKPOINT_DETAIL_PANE.equals(pane.getClass().getName()))
            return;
        Object actionsEditor = findSubEditor(Global.getField(pane, "breakpointEditor"), ACTIONS_EDITOR); //$NON-NLS-1$
        if (actionsEditor != null)
            installConsoleMasterCheckbox(actionsEditor, true);
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell))
                return;
            if (shell.isDisposed())
                return;
            if (!isBreakpointPropertiesShell(shell))
                return;
            installShellSizeMemory(shell);
            if (shell.getData(PATCHED_KEY) == null)
                startPatch(display, shell);
            if (shell.getData(CONTENT_ASSIST_KEY) == null)
                scheduleContentAssistAttempt(display, shell, 0);
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static boolean isBreakpointPropertiesShell(Shell shell)
    {
        String title = shell.getText();
        if (title == null || !title.startsWith(DIALOG_TITLE_PREFIX))
            return false;
        PreferenceDialog dialog = findPreferenceDialog(shell);
        if (dialog == null)
            return false;
        Object page = dialog.getSelectedPage();
        return page != null && BSL_BREAKPOINT_PAGE.equals(page.getClass().getName());
    }

    /**
     * Первая попытка — синхронно в обработчике {@link SWT#Show}: это событие SWT шлёт
     * до показа окна средствами ОС ({@code Shell.setVisible} → {@code sendEvent(SWT.Show)}
     * → {@code ShowWindow}), поэтому перестроение страницы попадает уже в первый кадр
     * и пользователь не видит двухшаговости «сначала штатный вид, через миг наш».
     * Отложенные попытки остаются запасным путём, если поля страницы ещё не готовы.
     */
    private static void startPatch(Display display, Shell shell)
    {
        if (!tryPatch(shell))
            schedulePatchAttempt(display, shell, 1);
    }

    private static void schedulePatchAttempt(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;
        int delay = attempt == 0 ? 0 : 80;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (tryPatch(shell))
                return;
            if (attempt < 12)
                schedulePatchAttempt(display, shell, attempt + 1);
        });
    }

    /** Автооткрытие подсказки в полях условия и значения выражения. */
    private static void scheduleContentAssistAttempt(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(CONTENT_ASSIST_KEY) != null)
            return;
        int delay = attempt == 0 ? 0 : 80;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed() || shell.getData(CONTENT_ASSIST_KEY) != null)
                return;
            if (tryPatchContentAssist(shell))
            {
                shell.setData(CONTENT_ASSIST_KEY, Boolean.TRUE);
                BreakpointPropertiesDebug.log("contentAssist patched"); //$NON-NLS-1$
                return;
            }
            if (attempt < 12)
                scheduleContentAssistAttempt(display, shell, attempt + 1);
            else
            {
                shell.setData(CONTENT_ASSIST_KEY, Boolean.TRUE);
                BreakpointPropertiesDebug.problem("contentAssist patch failed"); //$NON-NLS-1$
            }
        });
    }

    private static boolean tryPatchContentAssist(Shell shell)
    {
        ContentAssistManager mgr = ContentAssistManager.getInstance();
        if (mgr == null)
            return true;
        ContentAssistSettings cas = ContentAssistSettings.getInstance();
        if (cas == null || !cas.isEnabled())
            return true;

        Object conditionEditor = resolveBreakpointSubEditor(shell, CONDITION_EDITOR);
        Object actionsEditor = resolveActionsEditor(shell);
        SourceViewer conditionViewer = resolveEmbeddedViewer(
            conditionEditor == null ? null : Global.getField(conditionEditor, "conditionPane")); //$NON-NLS-1$
        SourceViewer expressionViewer = resolveEmbeddedViewer(
            actionsEditor == null ? null : Global.getField(actionsEditor, "expressionPane")); //$NON-NLS-1$
        if (conditionViewer == null && expressionViewer == null)
            return false;

        boolean ok = true;
        if (conditionViewer != null && !mgr.applyPatchToEmbeddedBslViewer(conditionViewer))
            ok = false;
        if (expressionViewer != null && !mgr.applyPatchToEmbeddedBslViewer(expressionViewer))
            ok = false;
        // Общее поведение поля кода BSL — то же, что в поле «Выражение» инспектора:
        // вставка по Enter из списка, Ctrl+Shift+Space, жизнь LinkedMode при всплывающих
        // окнах, возврат фокуса в поле.
        if (conditionViewer != null)
        {
            markBreakpointField(conditionViewer, "condition"); //$NON-NLS-1$
            BslExpressionField.attach(conditionViewer, FIELD_LOG_TOPIC);
        }
        if (expressionViewer != null)
        {
            markBreakpointField(expressionViewer, "expression"); //$NON-NLS-1$
            BslExpressionField.attach(expressionViewer, FIELD_LOG_TOPIC);
        }
        if (conditionViewer == null || expressionViewer == null)
            ok = false;
        return ok;
    }

    private static void markBreakpointField(SourceViewer viewer, String role)
    {
        if (viewer == null)
            return;
        StyledText text = viewer.getTextWidget();
        if (text == null || text.isDisposed())
            return;
        text.setData(BP_FIELD_ROLE_KEY, role);
    }

    private static SourceViewer resolveEmbeddedViewer(Object pane)
    {
        if (pane == null)
            return null;
        Object textEditor = Global.getField(pane, "textEditor"); //$NON-NLS-1$
        if (textEditor == null)
            return null;
        Object viewer = Global.invoke(textEditor, "getViewer"); //$NON-NLS-1$
        return viewer instanceof SourceViewer sourceViewer ? sourceViewer : null;
    }

    /** Размер окна «Свойства для …» — восстановление при открытии и запоминание при закрытии. */
    private static void installShellSizeMemory(Shell shell)
    {
        if (Boolean.TRUE.equals(shell.getData(SIZE_MEMORY_KEY)))
            return;
        shell.setData(SIZE_MEMORY_KEY, Boolean.TRUE);

        restoreShellSize(shell);

        // Размер запоминается по событию Resize: в DisposeListener окно ОС уже уничтожено
        // и getSize() отдаёт неверные значения.
        Point[] lastSize = { null };
        shell.addListener(SWT.Resize, e ->
        {
            if (shell.getMaximized() || shell.getMinimized())
                return;
            Point size = shell.getSize();
            if (size.x > 0 && size.y > 0)
                lastSize[0] = size;
        });
        shell.addDisposeListener(e -> saveShellSize(lastSize[0]));
    }

    private static void restoreShellSize(Shell shell)
    {
        IDialogSettings settings = dialogSettings();
        if (settings.get(KEY_DIALOG_WIDTH) == null || settings.get(KEY_DIALOG_HEIGHT) == null)
            return;

        int width;
        int height;
        try
        {
            width = settings.getInt(KEY_DIALOG_WIDTH);
            height = settings.getInt(KEY_DIALOG_HEIGHT);
        }
        catch (NumberFormatException e)
        {
            return;
        }
        if (width <= 0 || height <= 0)
            return;

        Rectangle screen = shell.getMonitor().getClientArea();
        Point minimum = shell.getMinimumSize();
        width = Math.max(minimum.x, Math.min(width, screen.width));
        height = Math.max(minimum.y, Math.min(height, screen.height));
        shell.setSize(width, height);
    }

    private static void saveShellSize(Point size)
    {
        if (size == null || size.x <= 0 || size.y <= 0)
            return;
        IDialogSettings settings = dialogSettings();
        settings.put(KEY_DIALOG_WIDTH, size.x);
        settings.put(KEY_DIALOG_HEIGHT, size.y);
    }

    private static IDialogSettings dialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }

    private static boolean tryPatch(Shell shell)
    {
        Object actionsEditor = resolveActionsEditor(shell);
        if (actionsEditor == null)
            return false;

        Button evaluateButton = (Button) Global.getField(actionsEditor, "evaluateExpressionButton"); //$NON-NLS-1$
        if (evaluateButton == null || evaluateButton.isDisposed())
            return false;

        if (Boolean.TRUE.equals(evaluateButton.getData(LINK_ROW_KEY)))
        {
            installEditorPanesVerticalStretch(shell, actionsEditor);
            installAlwaysEnabledPanes(shell, actionsEditor);
            installConsoleMasterCheckbox(actionsEditor);
            installMouseWheelScroll(shell);
            installUncheckedTextPersistence(shell);
            shell.setData(PATCHED_KEY, Boolean.TRUE);
            return true;
        }

        installEditorPanesVerticalStretch(shell, actionsEditor);
        // Только после installAlwaysEnabledPanes: там в поля подставляется текст,
        // запомненный при снятом флажке, и он тоже должен раскрыть свою группу.
        installAlwaysEnabledPanes(shell, actionsEditor);
        installConsoleMasterCheckbox(actionsEditor);
        expandGroupsWithContent(shell, actionsEditor);
        installMouseWheelScroll(shell);
        installUncheckedTextPersistence(shell);
        if (!installIrOutputLink(actionsEditor, evaluateButton))
            return false;

        shell.setData(PATCHED_KEY, Boolean.TRUE);
        return true;
    }

    /**
     * Запоминание текста условия и выражения при снятом флажке.
     * <p>
     * Штатный {@code doSave} обоих редакторов при снятом флажке пишет в точку останова
     * пустую строку, и набранный текст пропадает. Пишем его в свой атрибут маркера
     * <b>до</b> штатного обработчика: фильтр {@link Display} получает {@link SWT#Selection}
     * раньше слушателей кнопки, поэтому окно ещё не закрыто и поля живы. «Отмена» кнопку
     * OK не нажимает — там ничего не запоминается, как и ожидается.
     */
    private static void installUncheckedTextPersistence(Shell shell)
    {
        if (Boolean.TRUE.equals(shell.getData(PERSIST_KEY)))
            return;
        PreferenceDialog dialog = findPreferenceDialog(shell);
        if (dialog == null)
            return;
        Object okObject = Global.invoke(dialog, "getButton", Integer.valueOf(IDialogConstants.OK_ID)); //$NON-NLS-1$
        if (!(okObject instanceof Button okButton) || okButton.isDisposed())
            return;
        shell.setData(PERSIST_KEY, Boolean.TRUE);

        Display display = shell.getDisplay();
        Listener beforeOk = event ->
        {
            if (event.widget != okButton || shell.isDisposed())
                return;
            saveUncheckedFieldText(resolveBreakpointSubEditor(shell, CONDITION_EDITOR),
                "conditionButton", "conditionPane", true); //$NON-NLS-1$ //$NON-NLS-2$
            Object actionsEditor = resolveActionsEditor(shell);
            saveUncheckedFieldText(actionsEditor,
                "evaluateExpressionButton", "expressionPane", false); //$NON-NLS-1$ //$NON-NLS-2$
            saveConsoleOutputState(actionsEditor);
        };
        display.addFilter(SWT.Selection, beforeOk);
        shell.addDisposeListener(e ->
        {
            if (!display.isDisposed())
                display.removeFilter(SWT.Selection, beforeOk);
        });
    }

    /**
     * При снятом флажке — текст в свой атрибут маркера, при включённом — атрибут убираем
     * (текст сохранит сама EDT в штатном свойстве точки останова).
     */
    private static void saveUncheckedFieldText(Object editor, String checkboxField, String paneField,
            boolean conditionField)
    {
        if (editor == null)
            return;
        Button checkbox = (Button)Global.getField(editor, checkboxField);
        Object pane = Global.getField(editor, paneField);
        Object bpObject = Global.getField(editor, "breakpoint"); //$NON-NLS-1$
        if (checkbox == null || checkbox.isDisposed() || pane == null
                || !(bpObject instanceof IBslLineBreakpoint breakpoint))
            return;
        IMarker marker = breakpoint.getMarker();
        if (marker == null || !marker.exists())
            return;

        String value = null;
        if (!checkbox.getSelection())
        {
            Object text = Global.invoke(pane, "getText"); //$NON-NLS-1$
            if (text instanceof String s && !s.isBlank())
                value = s;
        }
        try
        {
            marker.setAttribute(savedTextAttribute(conditionField), value);
        }
        catch (CoreException e)
        {
            BreakpointPropertiesDebug.problem("сохранение текста при снятом флажке: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static String savedTextAttribute(boolean conditionField)
    {
        return conditionField ? MARKER_SAVED_CONDITION : MARKER_SAVED_EXPRESSION;
    }

    /** Текст, запомненный при снятом флажке, или {@code null}. */
    private static String readSavedText(IBslLineBreakpoint breakpoint, boolean conditionField)
    {
        IMarker marker = breakpoint.getMarker();
        if (marker == null || !marker.exists())
            return null;
        try
        {
            Object value = marker.getAttribute(savedTextAttribute(conditionField));
            return value instanceof String s && !s.isBlank() ? s : null;
        }
        catch (CoreException e)
        {
            return null;
        }
    }

    /**
     * Поля условия и значения выражения всегда доступны для ввода (не зависят от флажков).
     * При появлении непустого текста связанный флажок включается сразу.
     */
    /**
     * Штатная обёртка поля: {@code if (true and …) then endif;} вокруг выражения
     * ({@code BslBreakpointTextAndHistoryEditorPane.EDITOR_PREFIX/SUFFIX}). Без неё
     * документ — тело модуля, и {@code строка() + x} даёт «ожидается ';'».
     */
    private static final String BP_EDITOR_WRAP_PREFIX = " if (true and "; //$NON-NLS-1$
    private static final String BP_EDITOR_WRAP_SUFFIX = ") then endif;"; //$NON-NLS-1$

    /**
     * Идёт подстановка текста, запомненного при снятом флажке: изменение документа в это
     * время не должно включать флажок — пользователь снял его намеренно. UI-поток, поэтому
     * обычного поля достаточно.
     */
    private static boolean restoringSavedText;

    private static void installAlwaysEnabledPanes(Shell shell, Object actionsEditor)
    {
        Object conditionEditor = resolveBreakpointSubEditor(shell, CONDITION_EDITOR);
        if (conditionEditor != null)
        {
            Button conditionButton = (Button) Global.getField(conditionEditor, "conditionButton"); //$NON-NLS-1$
            Object conditionPane = Global.getField(conditionEditor, "conditionPane"); //$NON-NLS-1$
            wireAlwaysEnabledPane(conditionButton, conditionPane, conditionEditor, true);
        }

        Button evaluateButton = (Button) Global.getField(actionsEditor, "evaluateExpressionButton"); //$NON-NLS-1$
        Object expressionPane = Global.getField(actionsEditor, "expressionPane"); //$NON-NLS-1$
        wireAlwaysEnabledPane(evaluateButton, expressionPane, actionsEditor, false);
        wireAutoContinueOnConsoleActions(actionsEditor);
    }

    /**
     * Группа «Вывести в консоль»: при включении любого флажка включается
     * «Продолжить выполнение». Для «Значение выражения» — также при автофлажке
     * по вводу и при вставке «Выводить ИР».
     */
    private static void wireAutoContinueOnConsoleActions(Object actionsEditor)
    {
        for (String fieldName : CONSOLE_OUTPUT_FIELDS)
        {
            Button button = (Button) Global.getField(actionsEditor, fieldName);
            if (button == null || button.isDisposed())
                continue;
            if (Boolean.TRUE.equals(button.getData(AUTO_CONTINUE_KEY)))
                continue;
            button.setData(AUTO_CONTINUE_KEY, Boolean.TRUE);
            button.addListener(SWT.Selection, e ->
            {
                if (button.getSelection())
                    ensureContinueExecutionChecked(actionsEditor);
                else
                    uncheckContinueExecutionIfDisabled(actionsEditor);
            });
        }
        uncheckContinueExecutionIfDisabled(actionsEditor);
    }

    /**
     * «Продолжить выполнение» недоступно, пока в консоль ничего не выводится. Включённая
     * пометка в этом состоянии ни на что не влияет и только вводит в заблуждение (серая
     * галочка) — снимаем её. Уведомление слушателей нужно, чтобы редактор счёл себя
     * изменённым и снятое состояние ушло в точку останова.
     */
    private static void uncheckContinueExecutionIfDisabled(Object actionsEditor)
    {
        if (actionsEditor == null)
            return;
        Button continueButton = (Button) Global.getField(actionsEditor, "continueExecutionButton"); //$NON-NLS-1$
        if (continueButton == null || continueButton.isDisposed())
            return;
        if (continueButton.isEnabled() || !continueButton.getSelection())
            return;
        // Общий флажок включён — «Продолжить выполнение» к нему привязано, сообщение подставит плагин.
        if (isConsoleMasterOn(actionsEditor))
        {
            continueButton.setEnabled(true);
            return;
        }

        continueButton.setSelection(false);
        Event event = new Event();
        event.type = SWT.Selection;
        event.widget = continueButton;
        continueButton.notifyListeners(SWT.Selection, event);
    }

    private static void ensureContinueExecutionChecked(Object actionsEditor)
    {
        if (actionsEditor == null)
            return;
        // Сюда приходят все включения пунктов вывода, и автовключения «Значение выражения»
        // без Selection тоже: включённый пункт означает «выводить» — общий флажок тоже.
        setConsoleMasterState(actionsEditor, true);
        Button continueButton = (Button) Global.getField(actionsEditor, "continueExecutionButton"); //$NON-NLS-1$
        if (continueButton == null || continueButton.isDisposed())
            return;
        if (!continueButton.isEnabled())
            continueButton.setEnabled(true);
        if (continueButton.getSelection())
            return;
        continueButton.setSelection(true);
        Event event = new Event();
        event.type = SWT.Selection;
        event.widget = continueButton;
        continueButton.notifyListeners(SWT.Selection, event);
    }

    /** Состояние общего флажка вывода для текущей точки останова редактора «Действия». */
    private static final class ConsoleMasterState
    {
        /** Панель «Точки останова»: кнопки OK нет, запоминаем в маркер сразу. */
        final boolean persistImmediately;
        final List<String> remembered = new ArrayList<>();
        Object breakpoint;
        /** Общий флажок включён (атрибут {@link #MARKER_CONSOLE_OUTPUT}), независимо от пунктов. */
        boolean on;

        ConsoleMasterState(boolean persistImmediately)
        {
            this.persistImmediately = persistImmediately;
        }
    }

    /**
     * Общий флажок вывода в консоль — как «Выводить в окно служебных сообщений» конфигуратора.
     * В EDT вместо него рамка с заголовком; заголовок рамки становится текстом флажка, а сам
     * флажок встаёт первым в блоке «Действия».
     * Флажок самостоятельный: включён один он — в консоль идёт строка «Выполнено: …»
     * ({@link ConsoleExecutedLine}). Он привязан к «Продолжить выполнение» в обе стороны.
     * Снятие запоминает включённые пункты и выключает их, включение возвращает запомненные.
     * Включение любого пункта включает и флажок.
     */
    private static void installConsoleMasterCheckbox(Object actionsEditor)
    {
        installConsoleMasterCheckbox(actionsEditor, false);
    }

    private static void installConsoleMasterCheckbox(Object actionsEditor, boolean persistImmediately)
    {
        Button descriptionButton = (Button) Global.getField(actionsEditor, "enablementDescriptionButton"); //$NON-NLS-1$
        if (descriptionButton == null || descriptionButton.isDisposed())
            return;
        Composite inner = descriptionButton.getParent();
        if (inner == null || !(inner.getParent() instanceof Group group) || group.isDisposed())
            return;
        if (group.getData(CONSOLE_MASTER_KEY) != null)
        {
            refreshConsoleMaster(actionsEditor);
            return;
        }
        Composite host = group.getParent();
        if (host == null || !(host.getLayout() instanceof GridLayout))
            return;

        String title = group.getText() == null ? "" : group.getText().strip(); //$NON-NLS-1$
        if (title.endsWith(":")) //$NON-NLS-1$
            title = title.substring(0, title.length() - 1).strip();
        if (title.isEmpty())
            title = CONSOLE_MASTER_FALLBACK_TEXT;

        Button master = new Button(host, SWT.CHECK);
        master.setText(title);
        master.setToolTipText(TooltipText.wrap(master, CONSOLE_MASTER_TOOLTIP + Global.pluginSignForTooltip()));
        master.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button continueButton = (Button) Global.getField(actionsEditor, CONTINUE_EXECUTION_FIELD);
        if (continueButton != null && !continueButton.isDisposed() && continueButton.getParent() == host)
            master.moveAbove(continueButton);
        else
            master.moveAbove(group);
        group.setText(""); //$NON-NLS-1$
        group.setData(CONSOLE_MASTER_KEY, master);

        ConsoleMasterState state = new ConsoleMasterState(persistImmediately);
        master.setData(CONSOLE_MASTER_KEY, state);
        refreshConsoleMaster(actionsEditor);

        master.addListener(SWT.Selection, e ->
        {
            boolean on = master.getSelection();
            state.on = on;
            if (on)
            {
                restoreConsoleOutput(actionsEditor, state.remembered);
            }
            else
            {
                state.remembered.clear();
                state.remembered.addAll(checkedConsoleOutput(actionsEditor));
                for (String fieldName : CONSOLE_OUTPUT_FIELDS)
                    setCheckedWithNotify((Button) Global.getField(actionsEditor, fieldName), false);
            }
            setContinueExecutionChecked(actionsEditor, on);
            if (state.persistImmediately)
                saveConsoleOutputState(actionsEditor);
        });
        Display display = master.getDisplay();
        Runnable keepContinueEnabledLater = () -> display.asyncExec(() -> keepContinueEnabled(actionsEditor));
        for (String fieldName : CONSOLE_OUTPUT_FIELDS)
        {
            Button button = (Button) Global.getField(actionsEditor, fieldName);
            if (button != null && !button.isDisposed())
                button.addListener(SWT.Selection, e ->
                {
                    keepContinueEnabledLater.run();
                    if (state.persistImmediately)
                        saveConsoleOutputState(actionsEditor);
                });
        }
        // Штатный checkContinueExecutionAvailability гасит пометку и при правке описания/выражения.
        Text descriptionText = (Text) Global.getField(actionsEditor, "descriptionText"); //$NON-NLS-1$
        if (descriptionText != null && !descriptionText.isDisposed())
            descriptionText.addListener(SWT.Modify, e -> keepContinueEnabledLater.run());
        Object pane = Global.getField(actionsEditor, "expressionPane"); //$NON-NLS-1$
        if (pane != null && Global.getField(pane, "document") instanceof IDocument document) //$NON-NLS-1$
            document.addDocumentListener(new IDocumentListener()
            {
                @Override
                public void documentAboutToBeChanged(DocumentEvent event)
                {
                }

                @Override
                public void documentChanged(DocumentEvent event)
                {
                    if (!master.isDisposed())
                        keepContinueEnabledLater.run();
                }
            });
        if (continueButton != null && !continueButton.isDisposed())
            continueButton.addListener(SWT.Selection, e ->
            {
                if (state.on != continueButton.getSelection())
                    setConsoleMasterState(actionsEditor, continueButton.getSelection());
            });
        host.layout(true, true);
    }

    /** Общий флажок включён: «Продолжить выполнение» доступно, даже когда EDT нечего выводить. */
    private static void keepContinueEnabled(Object actionsEditor)
    {
        if (!isConsoleMasterOn(actionsEditor))
            return;
        Button continueButton = (Button) Global.getField(actionsEditor, CONTINUE_EXECUTION_FIELD);
        if (continueButton != null && !continueButton.isDisposed() && !continueButton.isEnabled())
            continueButton.setEnabled(true);
    }

    private static void setContinueExecutionChecked(Object actionsEditor, boolean checked)
    {
        Button continueButton = (Button) Global.getField(actionsEditor, CONTINUE_EXECUTION_FIELD);
        if (continueButton == null || continueButton.isDisposed())
            return;
        if (checked && !continueButton.isEnabled())
            continueButton.setEnabled(true);
        setCheckedWithNotify(continueButton, checked);
    }

    private static ConsoleMasterState consoleMasterState(Object actionsEditor)
    {
        Button master = consoleMaster(actionsEditor);
        return master != null && master.getData(CONSOLE_MASTER_KEY) instanceof ConsoleMasterState state
                ? state
                : null;
    }

    private static boolean isConsoleMasterOn(Object actionsEditor)
    {
        ConsoleMasterState state = consoleMasterState(actionsEditor);
        return state != null && state.on;
    }

    private static void setConsoleMasterState(Object actionsEditor, boolean on)
    {
        Button master = consoleMaster(actionsEditor);
        ConsoleMasterState state = consoleMasterState(actionsEditor);
        if (master == null || state == null || state.on == on)
            return;
        state.on = on;
        master.setSelection(on);
        if (on)
            keepContinueEnabled(actionsEditor);
        if (state.persistImmediately)
            saveConsoleOutputState(actionsEditor);
    }

    /**
     * Сменилась точка останова редактора (панель «Точки останова» переиспользует его для
     * каждой выбранной): запомненное — из её маркера, описание при снятом флажке — в поле.
     * {@code setInput} ставит флажки без {@link SWT#Selection} — общий флажок сверяем здесь.
     */
    private static void refreshConsoleMaster(Object actionsEditor)
    {
        Button master = consoleMaster(actionsEditor);
        if (master == null || !(master.getData(CONSOLE_MASTER_KEY) instanceof ConsoleMasterState state))
            return;
        Object breakpoint = Global.getField(actionsEditor, "breakpoint"); //$NON-NLS-1$
        if (breakpoint == state.breakpoint)
            return;
        state.breakpoint = breakpoint;
        state.remembered.clear();
        hideConsoleExecutedDescription(actionsEditor);
        restoreSavedDescription(actionsEditor);
        state.on = Boolean.TRUE.equals(readMarkerAttribute(actionsEditor, MARKER_CONSOLE_OUTPUT));
        if (!state.on)
            state.remembered.addAll(readSavedConsoleOutput(actionsEditor));
        master.setSelection(state.on);
        keepContinueEnabled(actionsEditor);
    }

    /**
     * Описание, подставленное плагином ради строки «Выполнено» ({@link ConsoleExecutedLine}), в поле
     * не показываем: для пользователя описания нет. Подстановка — не правка, редактор не «грязный».
     */
    private static void hideConsoleExecutedDescription(Object actionsEditor)
    {
        if (!Boolean.TRUE.equals(readMarkerAttribute(actionsEditor, MARKER_CONSOLE_HIDDEN_DESCRIPTION)))
            return;
        Button descriptionButton = (Button) Global.getField(actionsEditor, "enablementDescriptionButton"); //$NON-NLS-1$
        Text descriptionText = (Text) Global.getField(actionsEditor, "descriptionText"); //$NON-NLS-1$
        if (descriptionButton == null || descriptionButton.isDisposed() || descriptionText == null
                || descriptionText.isDisposed() || !CONSOLE_HIDDEN_DESCRIPTION.equals(descriptionText.getText().strip()))
            return;
        boolean wasDirty = Boolean.TRUE.equals(Global.invoke(actionsEditor, "isDirty")); //$NON-NLS-1$
        descriptionButton.setSelection(false);
        descriptionText.setText(""); //$NON-NLS-1$
        descriptionText.setEnabled(false);
        if (!wasDirty)
            Global.invoke(actionsEditor, "setDirty", false); //$NON-NLS-1$
    }

    private static Button consoleMaster(Object actionsEditor)
    {
        Button descriptionButton = (Button) Global.getField(actionsEditor, "enablementDescriptionButton"); //$NON-NLS-1$
        if (descriptionButton == null || descriptionButton.isDisposed())
            return null;
        Composite inner = descriptionButton.getParent();
        if (inner == null || !(inner.getParent() instanceof Group group)
                || !(group.getData(CONSOLE_MASTER_KEY) instanceof Button master) || master.isDisposed())
            return null;
        return master;
    }

    /** Имена полей включённых пунктов вывода; «Продолжить выполнение» — только вместе с ними. */
    private static List<String> checkedConsoleOutput(Object actionsEditor)
    {
        List<String> result = new ArrayList<>();
        for (String fieldName : CONSOLE_OUTPUT_FIELDS)
        {
            Button button = (Button) Global.getField(actionsEditor, fieldName);
            if (button != null && !button.isDisposed() && button.getSelection())
                result.add(fieldName);
        }
        Button continueButton = (Button) Global.getField(actionsEditor, CONTINUE_EXECUTION_FIELD);
        if (!result.isEmpty() && continueButton != null && !continueButton.isDisposed()
                && continueButton.getSelection())
            result.add(CONTINUE_EXECUTION_FIELD);
        return result;
    }

    /** Запомненные при снятии общего флажка пункты — обратно; без них ничего не включаем. */
    private static void restoreConsoleOutput(Object actionsEditor, List<String> remembered)
    {
        for (String fieldName : remembered)
        {
            if (!CONTINUE_EXECUTION_FIELD.equals(fieldName))
                setCheckedWithNotify((Button) Global.getField(actionsEditor, fieldName), true);
        }
    }

    private static void setCheckedWithNotify(Button button, boolean checked)
    {
        if (button == null || button.isDisposed() || button.getSelection() == checked)
            return;
        button.setSelection(checked);
        Event event = new Event();
        event.type = SWT.Selection;
        event.widget = button;
        button.notifyListeners(SWT.Selection, event);
    }

    /**
     * Запомненные пункты вывода — в атрибут маркера, пока общий флажок снят; при включённом
     * атрибут убираем. Описание при снятом флажке — тоже: штатный {@code doSave} пишет пустую строку.
     */
    private static void saveConsoleOutputState(Object actionsEditor)
    {
        if (actionsEditor == null)
            return;
        Object bpObject = Global.getField(actionsEditor, "breakpoint"); //$NON-NLS-1$
        if (!(bpObject instanceof IBslLineBreakpoint breakpoint))
            return;
        IMarker marker = breakpoint.getMarker();
        if (marker == null || !marker.exists())
            return;

        String savedDescription = null;
        Button descriptionButton = (Button) Global.getField(actionsEditor, "enablementDescriptionButton"); //$NON-NLS-1$
        Text descriptionText = (Text) Global.getField(actionsEditor, "descriptionText"); //$NON-NLS-1$
        if (descriptionButton != null && !descriptionButton.isDisposed() && !descriptionButton.getSelection()
                && descriptionText != null && !descriptionText.isDisposed() && !descriptionText.getText().isBlank())
            savedDescription = descriptionText.getText().strip();

        String savedOutput = null;
        ConsoleMasterState state = consoleMasterState(actionsEditor);
        if (state != null && !state.on && !state.remembered.isEmpty())
            savedOutput = String.join(",", state.remembered); //$NON-NLS-1$
        try
        {
            marker.setAttribute(MARKER_SAVED_DESCRIPTION, savedDescription);
            marker.setAttribute(MARKER_SAVED_CONSOLE_OUTPUT, savedOutput);
            if (state != null)
                marker.setAttribute(MARKER_CONSOLE_OUTPUT, state.on ? Boolean.TRUE : null);
        }
        catch (CoreException e)
        {
            BreakpointPropertiesDebug.problem("сохранение пунктов вывода в консоль: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static List<String> readSavedConsoleOutput(Object actionsEditor)
    {
        List<String> result = new ArrayList<>();
        Object value = readMarkerAttribute(actionsEditor, MARKER_SAVED_CONSOLE_OUTPUT);
        if (!(value instanceof String s) || s.isBlank())
            return result;
        for (String name : s.split(",")) //$NON-NLS-1$
        {
            String fieldName = name.strip();
            if (CONTINUE_EXECUTION_FIELD.equals(fieldName) || List.of(CONSOLE_OUTPUT_FIELDS).contains(fieldName))
                result.add(fieldName);
        }
        return result;
    }

    /** Описание, запомненное при снятом флажке, — в поле (флажок остаётся снятым). */
    private static void restoreSavedDescription(Object actionsEditor)
    {
        Button descriptionButton = (Button) Global.getField(actionsEditor, "enablementDescriptionButton"); //$NON-NLS-1$
        Text descriptionText = (Text) Global.getField(actionsEditor, "descriptionText"); //$NON-NLS-1$
        if (descriptionButton == null || descriptionButton.isDisposed() || descriptionButton.getSelection()
                || descriptionText == null || descriptionText.isDisposed() || !descriptionText.getText().isEmpty())
            return;
        Object value = readMarkerAttribute(actionsEditor, MARKER_SAVED_DESCRIPTION);
        if (!(value instanceof String s) || s.isBlank())
            return;
        // Штатный ModifyListener поля сочтёт подстановку правкой: в панели «Точки останова»
        // это предложение сохранить при каждом выборе точки. Подстановка — не правка.
        boolean wasDirty = Boolean.TRUE.equals(Global.invoke(actionsEditor, "isDirty")); //$NON-NLS-1$
        descriptionText.setText(s);
        if (!wasDirty)
            Global.invoke(actionsEditor, "setDirty", false); //$NON-NLS-1$
    }

    private static Object readMarkerAttribute(Object actionsEditor, String attribute)
    {
        Object bpObject = Global.getField(actionsEditor, "breakpoint"); //$NON-NLS-1$
        if (!(bpObject instanceof IBslLineBreakpoint breakpoint))
            return null;
        IMarker marker = breakpoint.getMarker();
        if (marker == null || !marker.exists())
            return null;
        try
        {
            return marker.getAttribute(attribute);
        }
        catch (CoreException e)
        {
            return null;
        }
    }

    private static void wireAlwaysEnabledPane(Button checkbox, Object pane, Object breakpointHolder,
            boolean conditionField)
    {
        if (checkbox == null || checkbox.isDisposed() || pane == null)
            return;
        if (Boolean.TRUE.equals(checkbox.getData(ALWAYS_ENABLED_KEY)))
            return;
        checkbox.setData(ALWAYS_ENABLED_KEY, Boolean.TRUE);

        forcePaneEnabled(pane);
        // Пустое условие/выражение: EDT делает initializeEmptyModel без обёртки.
        // Поле уже доступно — нужна ensureInitialized до ввода, иначе синтаксис как у инструкций.
        ensureBreakpointExpressionWrap(pane, breakpointHolder, conditionField, null);
        Display display = checkbox.getDisplay();
        if (display == null || display.isDisposed())
            return;
        // setInput иногда после нашего патча: повторить, когда breakpoint уже на месте.
        // Текст мог появиться только сейчас — тогда и группу раскрывать здесь.
        Runnable retry = () ->
        {
            ensureBreakpointExpressionWrap(pane, breakpointHolder, conditionField, null);
            expandGroupIfHasContent(pane);
        };
        display.timerExec(0, retry);
        display.timerExec(120, retry);

        // Штатный Selection: setEnabled(selection) и при включении ensureInitialized(…,
        // breakpoint.getXxx()), что при initialized=false подставляет сохранённое
        // (часто пустое) значение поверх уже набранного текста. Фильтр Display идёт
        // до слушателей виджета — успеваем запомнить текст и вернуть после EDT.
        String[] textBeforeCheck = { null };
        Listener captureBeforeEdt = event ->
        {
            if (event.widget != checkbox)
                return;
            if (!checkbox.getSelection())
            {
                textBeforeCheck[0] = null;
                return;
            }
            Object text = Global.invoke(pane, "getText"); //$NON-NLS-1$
            textBeforeCheck[0] = text instanceof String s ? s : null;
        };
        display.addFilter(SWT.Selection, captureBeforeEdt);
        checkbox.addDisposeListener(e ->
        {
            if (!display.isDisposed())
                display.removeFilter(SWT.Selection, captureBeforeEdt);
        });
        checkbox.addListener(SWT.Selection, e ->
        {
            forcePaneEnabled(pane);
            restorePaneTextIfWiped(pane, textBeforeCheck[0]);
            textBeforeCheck[0] = null;
        });

        Object documentObj = Global.getField(pane, "document"); //$NON-NLS-1$
        if (!(documentObj instanceof IDocument document))
            return;
        // Не вызывать pane.addDocumentListener — там только один слот, затрём слушатель EDT.
        document.addDocumentListener(new IDocumentListener()
        {
            @Override
            public void documentAboutToBeChanged(DocumentEvent event)
            {
            }

            @Override
            public void documentChanged(DocumentEvent event)
            {
                if (checkbox.isDisposed() || restoringSavedText)
                    return;
                Object text = Global.invoke(pane, "getText"); //$NON-NLS-1$
                if (!(text instanceof String s) || s.isBlank())
                    return;
                // setSelection не шлёт Selection — штатный ensureInitialized не вызывается.
                // Обёртку — только async: updateModel внутри documentChanged реентрантен.
                if (!documentHasBreakpointExpressionWrap(pane))
                {
                    String typed = s;
                    display.asyncExec(() -> ensureBreakpointExpressionWrap(
                            pane, breakpointHolder, conditionField, typed));
                }
                if (!checkbox.getSelection())
                {
                    checkbox.setSelection(true);
                    if (!conditionField)
                        ensureContinueExecutionChecked(breakpointHolder);
                }
            }
        });
    }

    /**
     * Вставляет штатную обёртку выражения вокруг редактируемой части, если её ещё нет.
     * {@code preferEditable} — уже набранный текст (не затирать сохранённым пустым).
     */
    private static void ensureBreakpointExpressionWrap(Object pane, Object breakpointHolder,
            boolean conditionField, String preferEditable)
    {
        if (pane == null || breakpointHolder == null)
            return;
        Object bpObj = Global.getField(breakpointHolder, "breakpoint"); //$NON-NLS-1$
        if (!(bpObj instanceof IBslLineBreakpoint breakpoint))
            return;
        if (documentHasBreakpointExpressionWrap(pane))
            return;

        String editable = preferEditable;
        if (editable == null || editable.isBlank())
        {
            Object text = Global.invoke(pane, "getText"); //$NON-NLS-1$
            if (text instanceof String s && !s.isBlank())
                editable = s;
        }
        if (editable == null || editable.isBlank())
        {
            try
            {
                String stored = conditionField
                        ? breakpoint.getCondition()
                        : breakpoint.getExpressionForEvaluation();
                editable = stored != null ? stored : ""; //$NON-NLS-1$
            }
            catch (CoreException e)
            {
                editable = ""; //$NON-NLS-1$
            }
        }

        // Штатное свойство пустое — значит флажок был снят; возвращаем текст, запомненный
        // при закрытии окна, и следим, чтобы он не включил флажок обратно.
        boolean fromSaved = false;
        if (editable.isBlank())
        {
            String saved = readSavedText(breakpoint, conditionField);
            if (saved != null)
            {
                editable = saved;
                fromSaved = true;
            }
        }

        // initializeEmptyModel мог оставить initialized=true без обёртки — сбросить флаг.
        Object initialized = Global.getField(pane, "initialized"); //$NON-NLS-1$
        if (Boolean.TRUE.equals(initialized))
            Global.invoke(pane, "setNotInitialized"); //$NON-NLS-1$

        if (!fromSaved)
        {
            Global.invoke(pane, "ensureInitialized", breakpoint, editable); //$NON-NLS-1$
            return;
        }
        restoringSavedText = true;
        try
        {
            Global.invoke(pane, "ensureInitialized", breakpoint, editable); //$NON-NLS-1$
        }
        finally
        {
            restoringSavedText = false;
        }
    }

    private static boolean documentHasBreakpointExpressionWrap(Object pane)
    {
        Object documentObj = Global.getField(pane, "document"); //$NON-NLS-1$
        if (!(documentObj instanceof IDocument document))
            return false;
        try
        {
            String full = document.get();
            return full != null
                    && full.contains(BP_EDITOR_WRAP_PREFIX)
                    && full.contains(BP_EDITOR_WRAP_SUFFIX);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** Вернуть текст поля, если штатный ensureInitialized подставил пустое значение. */
    private static void restorePaneTextIfWiped(Object pane, String preserved)
    {
        if (preserved == null || preserved.isBlank())
            return;
        Object text = Global.invoke(pane, "getText"); //$NON-NLS-1$
        String current = text instanceof String s ? s : ""; //$NON-NLS-1$
        if (preserved.equals(current))
            return;
        Object modelAccess = Global.invoke(pane, "getModelAccess"); //$NON-NLS-1$
        if (modelAccess == null)
            return;
        Global.invoke(modelAccess, "updateEditablePart", preserved); //$NON-NLS-1$
    }

    private static void forcePaneEnabled(Object pane)
    {
        if (pane == null)
            return;
        SourceViewer viewer = resolveEmbeddedViewer(pane);
        if (viewer != null)
        {
            Control control = viewer.getControl();
            Object comboObj = Global.getField(pane, "historyCombo"); //$NON-NLS-1$
            boolean comboOk = !(comboObj instanceof Combo combo) || combo.isDisposed()
                    || combo.isEnabled();
            if (control != null && !control.isDisposed() && control.isEnabled()
                    && viewer.isEditable() && comboOk)
                return;
        }
        Global.invoke(pane, "setEnabled", Boolean.TRUE); //$NON-NLS-1$
    }

    /**
     * Колесо мыши прокручивает {@link ScrolledComposite} диалога, когда виджет под курсором
     * (в т.ч. {@link StyledText} полей условия/выражения) сам прокручиваться не может.
     */
    private static void installMouseWheelScroll(Shell shell)
    {
        if (Boolean.TRUE.equals(shell.getData(WHEEL_SCROLL_KEY)))
            return;
        shell.setData(WHEEL_SCROLL_KEY, Boolean.TRUE);

        Display display = shell.getDisplay();
        Listener filter = event ->
        {
            if (shell.isDisposed())
                return;
            if (!(event.widget instanceof Control control) || control.isDisposed())
                return;
            if (control.getShell() != shell)
                return;
            if (!shouldForwardWheelToDialog(control))
                return;
            ScrolledComposite scrolled = enclosingScrolled(control);
            if (scrolled == null || scrolled.isDisposed())
                return;
            if (!scrollScrolledComposite(scrolled, control, event.count))
                return;
            event.doit = false;
        };
        display.addFilter(SWT.MouseWheel, filter);
        shell.addDisposeListener(e ->
        {
            if (!display.isDisposed())
                display.removeFilter(SWT.MouseWheel, filter);
        });
    }

    private static boolean shouldForwardWheelToDialog(Control control)
    {
        if (control instanceof StyledText styled)
            return !canScrollStyledTextItself(styled);
        // Combo — штатная смена пунктов истории колесом.
        if (control instanceof Combo)
            return false;
        return true;
    }

    private static boolean canScrollStyledTextItself(StyledText styled)
    {
        ScrollBar bar = styled.getVerticalBar();
        return bar != null && bar.getMaximum() - bar.getMinimum() > bar.getThumb();
    }

    private static boolean scrollScrolledComposite(ScrolledComposite scrolled, Control source, int count)
    {
        ScrollBar bar = scrolled.getVerticalBar();
        if (bar == null)
            return false;
        int max = bar.getMaximum() - bar.getThumb();
        if (max <= bar.getMinimum())
            return false;
        int step = 16;
        if (source instanceof StyledText styled && styled.getLineHeight() > 0)
            step = styled.getLineHeight();
        Point origin = scrolled.getOrigin();
        int y = Math.max(bar.getMinimum(), Math.min(max, origin.y - count * step));
        if (y == origin.y)
            return false;
        scrolled.setOrigin(origin.x, y);
        return true;
    }

    private static ScrolledComposite enclosingScrolled(Control control)
    {
        for (Control current = control.getParent(); current != null; current = current.getParent())
        {
            if (current instanceof ScrolledComposite scrolled)
                return scrolled;
        }
        return null;
    }

    /** Гиперссылка «Выводить ИР» справа от флажка «Значение выражения» (вторая колонка строки EDT). */
    private static boolean installIrOutputLink(Object actionsEditor, Button evaluateButton)
    {
        Composite parent = evaluateButton.getParent();
        if (parent == null || parent.isDisposed())
            return false;

        Control placeholder = findEvaluateExpressionRowPlaceholder(evaluateButton);
        Object layoutData = placeholder != null && !placeholder.isDisposed()
                ? placeholder.getLayoutData()
                : null;
        Control belowAnchor = findControlBelowPlaceholder(parent, evaluateButton, placeholder);

        if (placeholder != null && !placeholder.isDisposed())
            placeholder.dispose();

        Link link = new Link(parent, SWT.NONE);
        link.setText("<a>" + BUTTON_TEXT + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$
        link.setToolTipText(BUTTON_TOOLTIP);
        GridData linkData;
        if (layoutData instanceof GridData existing)
            linkData = existing;
        else
            linkData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        linkData.horizontalAlignment = SWT.END;
        linkData.grabExcessHorizontalSpace = true;
        link.setLayoutData(linkData);
        link.addListener(SWT.Selection, e ->
        {
            if (BUTTON_TEXT.equals(e.text))
                onInsertFromIr(actionsEditor);
        });

        if (belowAnchor != null && !belowAnchor.isDisposed())
            link.moveAbove(belowAnchor);
        else
            link.moveBelow(evaluateButton);

        evaluateButton.setData(LINK_ROW_KEY, Boolean.TRUE);
        parent.layout(true, true);
        return true;
    }

    /** Пустой {@link Label} во 2-й колонке строки «Значение выражения». */
    private static Control findEvaluateExpressionRowPlaceholder(Button evaluateButton)
    {
        Composite parent = evaluateButton.getParent();
        if (parent == null || parent.isDisposed())
            return null;
        Control[] children = parent.getChildren();
        for (int i = 0; i < children.length - 1; i++)
        {
            if (children[i] == evaluateButton && children[i + 1] instanceof Label label)
                return label;
        }
        return null;
    }

    private static Control findControlBelowPlaceholder(Composite parent, Button evaluateButton, Control placeholder)
    {
        Control[] children = parent.getChildren();
        boolean afterPlaceholder = false;
        for (Control child : children)
        {
            if (child == placeholder)
            {
                afterPlaceholder = true;
                continue;
            }
            if (afterPlaceholder && child != evaluateButton)
                return child;
        }
        return null;
    }

    /** Поля «Условный» / «Значение выражения» — растягивание по высоте. */
    private static void installEditorPanesVerticalStretch(Shell shell, Object actionsEditor)
    {
        Object conditionEditor = resolveBreakpointSubEditor(shell, CONDITION_EDITOR);
        if (conditionEditor != null)
        {
            Control conditionPane = (Control) Global.getField(conditionEditor, "conditionPane"); //$NON-NLS-1$
            installPaneVerticalStretch(shell, conditionPane);
        }

        Control expressionPane = (Control) Global.getField(actionsEditor, "expressionPane"); //$NON-NLS-1$
        installPaneVerticalStretch(shell, expressionPane);
    }

    /**
     * Группы «Условия» и «Действия» — автоматическое разворачивание при открытии диалога,
     * если внутри группы включена хотя бы одна пометка.
     */
    private static void expandGroupsWithContent(Shell shell, Object actionsEditor)
    {
        Object conditionEditor = resolveBreakpointSubEditor(shell, CONDITION_EDITOR);
        if (conditionEditor != null)
            expandGroupIfHasContent(Global.getField(conditionEditor, "conditionPane")); //$NON-NLS-1$
        expandGroupIfHasContent(Global.getField(actionsEditor, "expressionPane")); //$NON-NLS-1$
    }

    /**
     * Группа раскрывается, если в ней есть включённый флажок <b>или</b> непустой текст поля:
     * при снятом флажке текст всё равно восстанавливается (см. {@link #MARKER_SAVED_CONDITION}),
     * и прятать его в свёрнутой группе нельзя — пользователь не увидит, что там что-то есть.
     */
    private static void expandGroupIfHasContent(Object pane)
    {
        if (!(pane instanceof Control insideGroup) || insideGroup.isDisposed())
            return;
        ExpandableComposite group = findExpandableAncestor(insideGroup);
        if (group == null || group.isDisposed() || group.isExpanded())
            return;
        if (!hasCheckedOption(group.getClient()) && !hasPaneText(pane))
            return;

        group.setExpanded(true);
        relayoutPaneHierarchy(group);
    }

    private static boolean hasPaneText(Object pane)
    {
        Object text = Global.invoke(pane, "getText"); //$NON-NLS-1$
        return text instanceof String s && !s.isBlank();
    }

    private static ExpandableComposite findExpandableAncestor(Control control)
    {
        for (Control current = control; current != null && !(current instanceof Shell);
                current = current.getParent())
        {
            if (current instanceof ExpandableComposite expandable)
                return expandable;
        }
        return null;
    }

    private static boolean hasCheckedOption(Control control)
    {
        if (control == null || control.isDisposed())
            return false;
        if (control instanceof Button button)
            return (button.getStyle() & SWT.CHECK) != 0 && button.getSelection();
        if (control instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                if (hasCheckedOption(child))
                    return true;
            }
        }
        return false;
    }

    private static void installPaneVerticalStretch(Shell shell, Control pane)
    {
        if (pane == null || pane.isDisposed())
            return;
        if (Boolean.TRUE.equals(pane.getData(RESIZE_KEY)))
            return;
        pane.setData(RESIZE_KEY, Boolean.TRUE);

        applyVerticalStretchGridData(pane, EDITOR_PANE_MIN_HEIGHT, EDITOR_PANE_HEIGHT_HINT);
        stretchTextAndHistoryPaneInternals(pane);
        enableVerticalGrabOnAncestors(pane);
        registerResizePane(shell, pane);
        relayoutPaneHierarchy(pane);
    }

    @SuppressWarnings("unchecked")
    private static void registerResizePane(Shell shell, Control pane)
    {
        List<Control> panes = (List<Control>) shell.getData(RESIZE_PANES_KEY);
        if (panes == null)
        {
            panes = new ArrayList<>();
            shell.setData(RESIZE_PANES_KEY, panes);
        }
        if (!panes.contains(pane))
            panes.add(pane);

        if (shell.getData(RESIZE_LISTENER_KEY) != null)
            return;

        Listener resizeListener = e ->
        {
            List<Control> registered = (List<Control>) shell.getData(RESIZE_PANES_KEY);
            if (registered == null)
                return;
            for (Control registeredPane : registered)
                relayoutPaneHierarchy(registeredPane);
        };
        shell.addListener(SWT.Resize, resizeListener);
        shell.addDisposeListener(e ->
        {
            if (!shell.isDisposed())
                shell.removeListener(SWT.Resize, resizeListener);
        });
        shell.setData(RESIZE_LISTENER_KEY, Boolean.TRUE);
    }

    private static void applyVerticalStretchGridData(Control control, int minHeight, int heightHint)
    {
        applyStretchGridData(control, minHeight, heightHint, true);
    }

    private static void applyStretchGridData(Control control, int minHeight, int heightHint, boolean grabVertical)
    {
        if (control == null || control.isDisposed())
            return;
        Object layoutData = control.getLayoutData();
        GridData gd;
        if (layoutData instanceof GridData existing)
            gd = existing;
        else
        {
            gd = new GridData(SWT.FILL, grabVertical ? SWT.FILL : SWT.CENTER, true, grabVertical);
            control.setLayoutData(gd);
        }
        gd.grabExcessHorizontalSpace = true;
        gd.grabExcessVerticalSpace = grabVertical;
        gd.horizontalAlignment = SWT.FILL;
        gd.verticalAlignment = grabVertical ? SWT.FILL : SWT.CENTER;
        if (minHeight > 0)
            gd.minimumHeight = Math.max(gd.minimumHeight, minHeight);
        if (heightHint > 0)
            gd.heightHint = Math.max(gd.heightHint, heightHint);
    }

    private static void ensureHistoryComboDoesNotGrabVertical(Control combo)
    {
        if (combo == null || combo.isDisposed())
            return;
        Object layoutData = combo.getLayoutData();
        GridData gd;
        if (layoutData instanceof GridData existing)
            gd = existing;
        else
        {
            gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
            combo.setLayoutData(gd);
        }
        gd.grabExcessHorizontalSpace = true;
        gd.grabExcessVerticalSpace = false;
        gd.horizontalAlignment = SWT.FILL;
        gd.verticalAlignment = SWT.CENTER;
    }

    private static void replaceWithNarrowComboLayout(Composite composite)
    {
        if (!(composite.getLayout() instanceof GridLayout gl))
            return;
        int marginLeft = gl.marginLeft;
        int marginTop = gl.marginTop;
        int marginRight = gl.marginRight;
        int marginBottom = gl.marginBottom;
        int spacing = gl.verticalSpacing;
        composite.setLayout(new Layout()
        {
            @Override
            protected Point computeSize(Composite parent, int wHint, int hHint, boolean flushCache)
            {
                Control[] children = parent.getChildren();
                int maxWidth = 0;
                int totalHeight = 0;
                boolean first = true;
                for (Control child : children)
                {
                    if (child.isDisposed() || !child.getVisible())
                        continue;
                    Point cs = child.computeSize(SWT.DEFAULT, SWT.DEFAULT, flushCache);
                    int cw = cs.x;
                    if (child instanceof Combo)
                        cw = Math.min(cw, COMBO_FIELD_MAX_WIDTH);
                    maxWidth = Math.max(maxWidth, cw);
                    if (!first)
                        totalHeight += spacing;
                    totalHeight += cs.y;
                    first = false;
                }
                int width = wHint != SWT.DEFAULT ? wHint : marginLeft + maxWidth + marginRight;
                int height = hHint != SWT.DEFAULT ? hHint : marginTop + totalHeight + marginBottom;
                return new Point(width, height);
            }

            @Override
            protected void layout(Composite parent, boolean flushCache)
            {
                Control[] children = parent.getChildren();
                Rectangle area = parent.getClientArea();
                int innerWidth = area.width - marginLeft - marginRight;
                int totalNatural = 0;
                int childCount = 0;
                for (Control child : children)
                {
                    if (child.isDisposed() || !child.getVisible())
                        continue;
                    Point cs = child.computeSize(SWT.DEFAULT, SWT.DEFAULT, flushCache);
                    totalNatural += cs.y;
                    childCount++;
                    if (childCount > 1)
                        totalNatural += spacing;
                }
                int extra = Math.max(0, area.height - marginTop - marginBottom - totalNatural);
                int y = area.y + marginTop;
                for (Control child : children)
                {
                    if (child.isDisposed() || !child.getVisible())
                        continue;
                    Point cs = child.computeSize(SWT.DEFAULT, SWT.DEFAULT, flushCache);
                    int cw;
                    int ch = cs.y;
                    if (child instanceof Combo)
                        cw = Math.min(cs.x, COMBO_FIELD_MAX_WIDTH);
                    else
                    {
                        cw = innerWidth;
                        if (extra > 0)
                        {
                            ch += extra;
                            extra = 0;
                        }
                    }
                    child.setBounds(area.x + marginLeft, y, Math.max(cw, 1), ch);
                    y += ch + spacing;
                }
            }
        });
    }

    private static void stretchTextAndHistoryPaneInternals(Control pane)
    {
        if (!(pane instanceof Composite composite))
            return;
        replaceWithNarrowComboLayout(composite);
        for (Control child : composite.getChildren())
        {
            if (child instanceof Combo)
            {
                ensureHistoryComboDoesNotGrabVertical(child);
                continue;
            }
            applyVerticalStretchGridData(child, EDITOR_PANE_MIN_HEIGHT, EDITOR_PANE_HEIGHT_HINT);
            if (child instanceof Composite inner)
            {
                for (Control nested : inner.getChildren())
                    applyVerticalStretchGridData(nested, EDITOR_PANE_MIN_HEIGHT, 0);
            }
        }
    }

    private static void enableVerticalGrabOnAncestors(Control start)
    {
        for (Control parent = start.getParent(); parent != null && !(parent instanceof Shell);
                parent = parent.getParent())
        {
            if (parent instanceof ScrolledComposite scrolled)
            {
                relayoutScrolledComposite(scrolled);
                continue;
            }
            applyVerticalStretchGridData(parent, 0, 0);
        }
    }

    private static void relayoutPaneHierarchy(Control pane)
    {
        if (pane == null || pane.isDisposed())
            return;
        for (Control parent = pane.getParent(); parent != null && !(parent instanceof Shell);
                parent = parent.getParent())
        {
            if (parent instanceof ScrolledComposite scrolled)
                relayoutScrolledComposite(scrolled);
        }
        Control immediateParent = pane.getParent();
        if (immediateParent instanceof Composite parentComposite && !parentComposite.isDisposed())
            parentComposite.layout(true, true);
    }

    private static void relayoutScrolledComposite(ScrolledComposite scrolled)
    {
        if (scrolled == null || scrolled.isDisposed())
            return;
        Control content = scrolled.getContent();
        if (content == null || content.isDisposed())
            return;
        if (content instanceof Composite contentComposite)
        {
            replacePageLayoutIfInflated(contentComposite);
            contentComposite.layout(true, true);
        }
        Point size = content.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        scrolled.setMinSize(size.x, size.y);
        scrolled.layout(true, true);
    }

    private static void replacePageLayoutIfInflated(Composite contentComposite)
    {
        if (contentComposite.isDisposed())
            return;
        Object layout = contentComposite.getLayout();
        if (layout == null)
            return;
        String name = layout.getClass().getSimpleName();
        if (!"PageLayout".equals(name)) //$NON-NLS-1$
            return;
        Control[] kids = contentComposite.getChildren();
        if (kids.length != 1)
            return;
        Point kidCs = kids[0].computeSize(SWT.DEFAULT, SWT.DEFAULT);
        Point myCs = contentComposite.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        if (myCs.x <= kidCs.x + 20)
            return;
        GridLayout replacement = new GridLayout();
        replacement.marginWidth = 0;
        replacement.marginHeight = 0;
        contentComposite.setLayout(replacement);
    }

    private static Object resolveBreakpointSubEditor(Shell shell, String editorClassName)
    {
        PreferenceDialog dialog = findPreferenceDialog(shell);
        if (dialog == null)
            return null;
        Object page = dialog.getSelectedPage();
        if (page == null || !BSL_BREAKPOINT_PAGE.equals(page.getClass().getName()))
            return null;

        return findSubEditor(Global.getField(page, "editor"), editorClassName); //$NON-NLS-1$
    }

    /** Редактор класса {@code editorClassName} внутри {@code CompositeBreakpointEditor}. */
    private static Object findSubEditor(Object compositeEditor, String editorClassName)
    {
        if (compositeEditor == null)
            return null;
        Object editorsObj = Global.getField(compositeEditor, "editors"); //$NON-NLS-1$
        if (!(editorsObj instanceof List<?> editors))
            return null;
        for (Object editor : editors)
        {
            if (editor != null && editorClassName.equals(editor.getClass().getName()))
                return editor;
        }
        return null;
    }

    private static Object resolveActionsEditor(Shell shell)
    {
        return resolveBreakpointSubEditor(shell, ACTIONS_EDITOR);
    }

    private static PreferenceDialog findPreferenceDialog(Shell shell)
    {
        Shell current = shell;
        while (current != null && !current.isDisposed())
        {
            Object data = current.getData();
            if (data instanceof PreferenceDialog dialog)
                return dialog;
            current = current.getParent() instanceof Shell parent ? parent : null;
        }
        return null;
    }

    private static void onInsertFromIr(Object actionsEditor)
    {
        Object breakpointObj = Global.getField(actionsEditor, "breakpoint"); //$NON-NLS-1$
        if (!(breakpointObj instanceof IBslLineBreakpoint breakpoint))
        {
            BreakpointPropertiesDebug.problem("breakpoint missing"); //$NON-NLS-1$
            return;
        }

        Object pane = Global.getField(actionsEditor, "expressionPane"); //$NON-NLS-1$
        if (pane == null || !EXPRESSION_PANE.equals(pane.getClass().getName()))
        {
            BreakpointPropertiesDebug.problem("expressionPane missing"); //$NON-NLS-1$
            return;
        }

        BslXtextEditor editor = resolveModuleEditor(breakpoint);
        if (editor == null)
        {
            ToastNotification.show(BUTTON_TEXT, "Не удалось открыть модуль точки останова", 4_000); //$NON-NLS-1$
            return;
        }

        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        if (dtProject == null)
            return;

        IRSession irSession = IRApplication.getSession(dtProject, true);
        if (irSession == null || irSession.executor == null)
            return;

        String oldExpressionText = readCurrentExpressionText(actionsEditor, pane, breakpoint);
        SelectionRange syncRange = resolveSyncRange(editor, breakpoint);

        irSession.syncCodeEditorToIR(editor, syncRange.offset, syncRange.endOffset);
        irSession.executor.submit(() ->
        {
            try
            {
                ensureCodeEditor(irSession);
                irSession.invokeCodeEditor("РазобратьТекущийКонтекст"); //$NON-NLS-1$
                String sourceText = resolveSourceText(irSession, editor, syncRange);
                List<String> objectExpressions = findObjectExpressions(irSession, sourceText);
                String fragment = ExpressionFragmentBuilder.build(irSession, oldExpressionText, objectExpressions);
                if (fragment == null || fragment.isEmpty()
                        || (!oldExpressionText.isEmpty() && oldExpressionText.contains(fragment)))
                {
                    BreakpointPropertiesDebug.log("skip insert fragmentEmpty=" + fragment.isEmpty()); //$NON-NLS-1$
                    return;
                }
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return;
                display.asyncExec(() -> insertExpressionFragment(actionsEditor, pane, breakpoint,
                        oldExpressionText, fragment));
            }
            catch (Exception e)
            {
                BreakpointPropertiesDebug.problem("insert: " + e.getMessage()); //$NON-NLS-1$
                Display display = Display.getDefault();
                if (display != null && !display.isDisposed())
                    display.asyncExec(() -> ToastNotification.show(BUTTON_TEXT,
                            "Ошибка вызова ИР: " + e.getMessage(), 5_000)); //$NON-NLS-1$
            }
        });
    }

    private static String readCurrentExpressionText(Object actionsEditor, Object pane, IBslLineBreakpoint breakpoint)
    {
        Button evaluateButton = (Button) Global.getField(actionsEditor, "evaluateExpressionButton"); //$NON-NLS-1$
        if (evaluateButton != null && evaluateButton.getSelection())
        {
            Object text = Global.invoke(pane, "getText"); //$NON-NLS-1$
            if (text instanceof String s)
                return s.trim();
        }
        try
        {
            String stored = breakpoint.getExpressionForEvaluation();
            return stored != null ? stored.trim() : ""; //$NON-NLS-1$
        }
        catch (CoreException e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    private static void insertExpressionFragment(Object actionsEditor, Object pane, IBslLineBreakpoint breakpoint,
            String oldExpressionText, String fragment)
    {
        setEvaluateExpressionEnabled(actionsEditor, pane, breakpoint, true);

        String current = ""; //$NON-NLS-1$
        Object textObj = Global.invoke(pane, "getText"); //$NON-NLS-1$
        if (textObj instanceof String s)
            current = s;

        if (!current.isEmpty() && current.contains(fragment))
            return;

        String merged = current.isEmpty() ? fragment : current + fragment;
        Object modelAccess = Global.invoke(pane, "getModelAccess"); //$NON-NLS-1$
        if (modelAccess == null)
        {
            BreakpointPropertiesDebug.problem("modelAccess missing"); //$NON-NLS-1$
            return;
        }
        Global.invoke(modelAccess, "updateEditablePart", merged); //$NON-NLS-1$
        Global.invoke(pane, "setFocusOnEditor"); //$NON-NLS-1$
        BreakpointPropertiesDebug.log("inserted len=" + merged.length() + " exprCount fragment"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void setEvaluateExpressionEnabled(Object actionsEditor, Object pane,
            IBslLineBreakpoint breakpoint, boolean enabled)
    {
        Button evaluateButton = (Button) Global.getField(actionsEditor, "evaluateExpressionButton"); //$NON-NLS-1$
        if (evaluateButton == null || evaluateButton.isDisposed())
            return;
        evaluateButton.setSelection(enabled);
        Global.invoke(pane, "setEnabled", enabled); //$NON-NLS-1$
        if (!enabled)
            return;

        ensureContinueExecutionChecked(actionsEditor);

        String existing = ""; //$NON-NLS-1$
        try
        {
            String stored = breakpoint.getExpressionForEvaluation();
            if (stored != null)
                existing = stored;
        }
        catch (CoreException ignored) {}

        Global.invoke(pane, "ensureInitialized", breakpoint, existing); //$NON-NLS-1$
        if (pane instanceof Control control)
            relayoutPaneHierarchy(control);
    }

    private static BslXtextEditor resolveModuleEditor(IBslLineBreakpoint breakpoint)
    {
        try
        {
            IMarker marker = breakpoint.getMarker();
            if (marker == null)
                return null;
            IResource resource = marker.getResource();
            if (!(resource instanceof IFile file))
                return null;

            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;

            IEditorPart active = page.getActiveEditor();
            BslXtextEditor activeBsl = GetRef.getActiveBslEditor(active);
            if (activeBsl != null)
            {
                IFile activeFile = activeBsl.getEditorInput().getAdapter(IFile.class);
                if (file.equals(activeFile))
                {
                    revealLine(activeBsl, breakpoint.getLineNumber());
                    return activeBsl;
                }
            }

            IEditorPart part = page.findEditor(new FileEditorInput(file));
            if (part == null)
            {
                // Модуль формы уже открыт внутри редактора формы, но его вход —
                // не FileEditorInput, поэтому findEditor его не находит.
                // IDE.openEditor по .bsl открывал ВТОРОЙ редактор на тот же файл: оба
                // становились изменёнными, и при закрытии EDT спрашивал «изменён, но
                // всё ещё открыт в другом месте с теми же изменениями». Штатный
                // OpenHelper сам находит нужный редактор объекта и активирует в нём
                // страницу модуля (тот же вызов, что в openObjectEditorForFile).
                part = openModuleEditorViaOpenHelper(page, file);
            }
            if (part == null)
                return null;

            if (part instanceof BslXtextEditor bsl)
            {
                revealLine(bsl, breakpoint.getLineNumber());
                return bsl;
            }

            BslXtextEditor embedded = GetRef.getActiveBslEditor(part);
            if (embedded != null)
            {
                revealLine(embedded, breakpoint.getLineNumber());
                return embedded;
            }
        }
        catch (Exception e)
        {
            BreakpointPropertiesDebug.problem("resolveModuleEditor: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Открывает модуль штатным {@code OpenHelper.openEditor(IFile, ISelection)} — так же,
     * как это делает поиск по файлам ({@code FileSearchResultsHook.openObjectEditorForFile}).
     * Он открывает именно редактор объекта, а не второй редактор на тот же файл.
     */
    private static IEditorPart openModuleEditorViaOpenHelper(IWorkbenchPage page, IFile file)
    {
        try
        {
            Class<?> cls = Class.forName("com._1c.g5.v8.dt.ui.util.OpenHelper"); //$NON-NLS-1$
            Object helper = cls.getConstructor(IWorkbenchPage.class).newInstance(page);
            for (java.lang.reflect.Method m : cls.getMethods())
            {
                if (!"openEditor".equals(m.getName()) || m.getParameterCount() != 2) //$NON-NLS-1$
                    continue;
                if (m.getParameterTypes()[0].equals(IFile.class)
                    && m.getParameterTypes()[1].equals(ISelection.class))
                {
                    // Пустое выделение вместо null: строку показываем сами (revealLine).
                    Object opened = m.invoke(helper, file, TextSelection.emptySelection());
                    BreakpointPropertiesDebug.log("openModuleEditor: OpenHelper → " //$NON-NLS-1$
                        + (opened == null ? "null" : opened.getClass().getSimpleName())); //$NON-NLS-1$
                    return opened instanceof IEditorPart editor ? editor : null;
                }
            }
        }
        catch (Exception e)
        {
            BreakpointPropertiesDebug.problem("openModuleEditor: " + e); //$NON-NLS-1$
        }
        return null;
    }

    private static void revealLine(BslXtextEditor editor, int lineNumber)
    {
        if (lineNumber <= 0)
            return;
        try
        {
            ISourceViewer viewer = editor.getInternalSourceViewer();
            if (viewer == null)
                return;
            IDocument doc = viewer.getDocument();
            if (doc == null)
                return;
            int lineIndex = Math.max(0, lineNumber - 1);
            if (lineIndex >= doc.getNumberOfLines())
                return;
            int offset = doc.getLineOffset(lineIndex);
            editor.selectAndReveal(offset, 0);
        }
        catch (Exception e)
        {
            BreakpointPropertiesDebug.problem("revealLine: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static SelectionRange resolveSyncRange(BslXtextEditor editor, IBslLineBreakpoint breakpoint)
    {
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer != null)
        {
            Object sel = viewer.getSelectionProvider().getSelection();
            if (sel instanceof ITextSelection textSelection && textSelection.getLength() > 0)
            {
                return new SelectionRange(textSelection.getOffset(),
                        textSelection.getOffset() + textSelection.getLength());
            }
        }
        try
        {
            IDocument doc = viewer != null ? viewer.getDocument() : null;
            if (doc != null)
            {
                int lineIndex = Math.max(0, breakpoint.getLineNumber() - 1);
                if (lineIndex < doc.getNumberOfLines())
                {
                    int offset = doc.getLineOffset(lineIndex);
                    return new SelectionRange(offset, offset);
                }
            }
        }
        catch (Exception ignored) {}
        return new SelectionRange(0, 0);
    }

    private static String resolveSourceText(IRSession irSession, BslXtextEditor editor, SelectionRange syncRange)
    {
        if (syncRange.endOffset > syncRange.offset)
        {
            try
            {
                ISourceViewer viewer = editor.getInternalSourceViewer();
                IDocument doc = viewer != null ? viewer.getDocument() : null;
                if (doc != null)
                    return doc.get(syncRange.offset, syncRange.endOffset - syncRange.offset);
            }
            catch (Exception ignored) {}
        }

        Object fieldText = ComBridge.getProperty(irSession.codeEditor, "ПолеТекста"); //$NON-NLS-1$
        Object startLine = ComBridge.getProperty(irSession.codeEditor, "мНачальнаяСтрока"); //$NON-NLS-1$
        if (!ComBridge.isVariantUndefined(fieldText) && !ComBridge.isVariantUndefined(startLine))
            return ComBridge.toString(ComBridge.invoke(fieldText, "ПолучитьСтроку", startLine)); //$NON-NLS-1$
        return ""; //$NON-NLS-1$
    }

    private static List<String> findObjectExpressions(IRSession irSession, String sourceText)
    {
        List<String> result = new ArrayList<>();
        if (sourceText == null || sourceText.isBlank())
            return result;
        Object raw = ComBridge.invoke(irSession.codeEditor, "НайтиОбъектныеВыражения", sourceText); //$NON-NLS-1$
        if (raw == null)
            return result;
        for (Object item : ComBridge.iterateComCollection(raw))
        {
            String text = ComBridge.toString(item);
            if (text != null && !text.isBlank())
                result.add(text.trim());
        }
        return result;
    }

    private static void ensureCodeEditor(IRSession irSession)
    {
        if (irSession.codeEditor != null)
            return;
        Object irCache = irSession.getModule("ирКэш"); //$NON-NLS-1$
        irSession.codeEditor = ComBridge.invoke(irCache, "ПолеТекстаПрограммы", 0); //$NON-NLS-1$
    }

    private static final class SelectionRange
    {
        final int offset;
        final int endOffset;

        SelectionRange(int offset, int endOffset)
        {
            this.offset = offset;
            this.endOffset = endOffset;
        }
    }

    /** Порт сборки фрагмента из RDT {@code КнопкаВставитьВыражениеИзМодуля}. */
    private static final class ExpressionFragmentBuilder
    {
        private ExpressionFragmentBuilder() {}

        static String build(IRSession session, String oldExpressionText, List<String> objectExpressions)
        {
            if (objectExpressions.isEmpty())
                return ""; //$NON-NLS-1$

            Object irCommon = session.getModule("ирОбщий"); //$NON-NLS-1$
            List<String> parts = new ArrayList<>();

            // Время и метод не вставляем: их выводит строка общего флажка вывода (ConsoleExecutedLine).
            // Пустая первая часть — разделитель перед добавлением к уже набранному выражению.
            if (oldExpressionText != null && !oldExpressionText.isBlank())
                parts.add(""); //$NON-NLS-1$

            for (String expression : objectExpressions)
            {
                String label = truncate(irCommon, expression, 50);
                String prepared = prepareExpressionForOutput(session, irCommon, expression);
                parts.add("\"" + label + " = \"+" + prepared); //$NON-NLS-1$ //$NON-NLS-2$
            }

            return String.join(JOIN_SEPARATOR, parts);
        }

        private static String prepareExpressionForOutput(IRSession session, Object irCommon, String expression)
        {
            if (ComBridge.toBoolean(ComBridge.invoke(session.codeEditor,
                    "ЛиТекущееВыражениеИмеетСлово", "Количество", "Метод", ""))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                return expression + ".Количество()"; //$NON-NLS-1$
            return "ирОбщий.ПредставлениеСОбрезкойДлиныЛкс(" + expression + ", 50)"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static String truncate(Object irCommon, String value, int maxLen)
        {
            if (value == null)
                value = ""; //$NON-NLS-1$
            return ComBridge.toString(ComBridge.invoke(irCommon, "ПредставлениеСОбрезкойДлиныЛкс", value, maxLen)); //$NON-NLS-1$
        }
    }

    /**
     * Строка «Выполнено: …» в консоли отладки для точек с общим флажком вывода
     * ({@link #MARKER_CONSOLE_OUTPUT}) — как «Сработала точка останова» конфигуратора.
     * <p>
     * EDT ({@code RuntimeDebugClientTarget.proceedStackEventDataAndSuspentionFlag}, debug.core
     * 18.0.0.v202605050943) печатает через {@link IDebugConsoleWriter} заголовок «метод, строка»,
     * затем сообщение сервера и два перевода строки — и только если сервер сообщение прислал, то
     * есть у точки задан хоть один пункт вывода. Поэтому: без пунктов точке подставляется скрытое
     * описание ({@link #enforce}); {@code consoleWriter} цели заменяется обёрткой, которая вместо
     * заголовка и скрытого описания печатает свою строку. Поток (предмет отладки, сеанс) в
     * {@code print} не передаётся — его даёт команда {@code handleCallStackFormed}, перехваченная
     * в {@code RuntimeEventDispatchJob.eventProcessor} на том же потоке Java.
     * Номера соединения в модели EDT нет ({@link DebugTargetId}) — в строке его нет.
     */
    private static final class ConsoleExecutedLine
    {
        private static final String CLIENT_TARGET_CLASS =
                "com._1c.g5.v8.dt.internal.debug.core.model.RuntimeDebugClientTarget"; //$NON-NLS-1$
        private static final String DISPATCH_JOB_CLASS =
                "com._1c.g5.v8.dt.internal.debug.core.model.RuntimeEventDispatchJob"; //$NON-NLS-1$
        private static final String MESSAGES_CLASS = "com._1c.g5.v8.dt.internal.debug.core.model.Messages"; //$NON-NLS-1$
        private static final String HEADER_FIELD = "RuntimeDebugTargetThread_Breakpoint_data"; //$NON-NLS-1$
        private static final String HEADER_FALLBACK = "Данные в точке останова: метод {0}, строка {1}"; //$NON-NLS-1$
        private static final int LINE_CACHE_LIMIT = 16;
        private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS"); //$NON-NLS-1$

        /** Команда «стек сформирован», которую сейчас обрабатывает цель на этом потоке. */
        private static final ThreadLocal<Object> CURRENT_COMMAND = new ThreadLocal<>();
        private static final Map<String, IDocument> LINE_CACHE = new LinkedHashMap<>(16, 0.75f, true)
        {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, IDocument> eldest)
            {
                return size() > LINE_CACHE_LIMIT;
            }
        };
        private static volatile Pattern headerPattern;
        private static volatile boolean installed;
        private static final int PRINTED_LIMIT = 5000;
        /** Напечатанная строка (без перевода строки) → место в модуле, породившее её. */
        private static final Map<String, PrintedLocation> PRINTED = new LinkedHashMap<>(256, 0.75f, false)
        {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PrintedLocation> eldest)
            {
                return size() > PRINTED_LIMIT;
            }
        };

        /** Место строки лога: ссылка на строку с позицией относительно метода ({@link #moduleLineRef}). */
        private record PrintedLocation(IProject project, String ref)
        {
        }

        private ConsoleExecutedLine() {}

        static void install()
        {
            if (installed)
                return;
            DebugPlugin plugin = DebugPlugin.getDefault();
            if (plugin == null)
                return;
            installed = true;
            plugin.getBreakpointManager().addBreakpointListener(new IBreakpointListener()
            {
                @Override
                public void breakpointAdded(IBreakpoint breakpoint)
                {
                    scheduleEnforce(breakpoint);
                }

                @Override
                public void breakpointRemoved(IBreakpoint breakpoint, IMarkerDelta delta)
                {
                }

                @Override
                public void breakpointChanged(IBreakpoint breakpoint, IMarkerDelta delta)
                {
                    scheduleEnforce(breakpoint);
                }
            });
            plugin.addDebugEventListener(events ->
            {
                for (DebugEvent event : events)
                {
                    if (event.getSource() instanceof IDebugElement element)
                        hookTarget(element.getDebugTarget());
                }
            });
        }

        private static void rememberPrinted(String line, PrintedLocation location)
        {
            synchronized (PRINTED)
            {
                PRINTED.put(line.strip(), location);
            }
        }

        /**
         * Двойной клик по нашей строке в консоли отладки открывает модуль на строке, которая её
         * породила. Строку узнаём по тексту — консоль EDT ({@code DebugMessageConsole}) хранит только текст.
         */
        static void installDoubleClick(Display display)
        {
            if (display == null || display.isDisposed())
                return;
            display.addFilter(SWT.MouseDoubleClick, event ->
            {
                if (!(event.widget instanceof StyledText text) || text.isDisposed() || event.button != 1)
                    return;
                PrintedLocation location;
                String clicked;
                try
                {
                    int lineIndex = text.getLineIndex(event.y);
                    clicked = text.getLine(lineIndex).strip();
                }
                catch (IllegalArgumentException e)
                {
                    return;
                }
                synchronized (PRINTED)
                {
                    location = PRINTED.get(clicked);
                }
                if (location == null)
                    return;
                // После штатной обработки двойного клика (выделение слова) — иначе фокус вернётся в консоль.
                display.asyncExec(() -> openModuleLine(location));
            });
        }

        private static void openModuleLine(PrintedLocation location)
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window != null ? window.getActivePage() : null;
            if (page == null)
                return;
            GoToDefinition.jump(location.ref(), window.getShell(), page, location.project(), false);
        }

        /** Слушатель точек останова идёт под блокировкой дерева ресурсов — маркер правим заданием. */
        private static void scheduleEnforce(IBreakpoint breakpoint)
        {
            if (!(breakpoint instanceof IBslLineBreakpoint lineBreakpoint))
                return;
            Job job = Job.create("Комфорт: вывод точки останова", (IProgressMonitor monitor) -> //$NON-NLS-1$
            {
                enforce(lineBreakpoint);
                return Status.OK_STATUS;
            });
            job.setSystem(true);
            job.schedule();
        }

        /**
         * Общий флажок включён — продолжать выполнение и, если пунктов вывода нет, подставить скрытое
         * описание (без сообщения сервер ничего не пришлёт). Выключен — скрытое описание убрать.
         */
        private static void enforce(IBslLineBreakpoint breakpoint)
        {
            IMarker marker = breakpoint.getMarker();
            if (marker == null || !marker.exists())
                return;
            try
            {
                boolean on = Boolean.TRUE.equals(marker.getAttribute(MARKER_CONSOLE_OUTPUT));
                boolean hidden = Boolean.TRUE.equals(marker.getAttribute(MARKER_CONSOLE_HIDDEN_DESCRIPTION));
                String description = Objects.toString(breakpoint.getDescription(), ""); //$NON-NLS-1$
                boolean hiddenDescription = hidden && CONSOLE_HIDDEN_DESCRIPTION.equals(description.strip());
                boolean otherOutput = !Objects.toString(breakpoint.getExpressionForEvaluation(), "").isEmpty() //$NON-NLS-1$
                        || breakpoint.isPutStackTrace() || breakpoint.isPutHitCount();
                if (on)
                {
                    if (!breakpoint.getContinueExecution())
                        breakpoint.setContinueExecution(true);
                    if (description.isEmpty() && !otherOutput)
                    {
                        marker.setAttribute(MARKER_CONSOLE_HIDDEN_DESCRIPTION, Boolean.TRUE);
                        breakpoint.setDescription(CONSOLE_HIDDEN_DESCRIPTION);
                    }
                    else if (hiddenDescription && otherOutput)
                    {
                        breakpoint.setDescription(""); //$NON-NLS-1$
                        marker.setAttribute(MARKER_CONSOLE_HIDDEN_DESCRIPTION, null);
                    }
                    else if (hidden && !hiddenDescription)
                    {
                        marker.setAttribute(MARKER_CONSOLE_HIDDEN_DESCRIPTION, null);
                    }
                }
                else if (hidden)
                {
                    if (hiddenDescription)
                        breakpoint.setDescription(""); //$NON-NLS-1$
                    marker.setAttribute(MARKER_CONSOLE_HIDDEN_DESCRIPTION, null);
                }
            }
            catch (CoreException e)
            {
                BreakpointPropertiesDebug.problem("вывод в консоль, правка точки останова: " + e.getMessage()); //$NON-NLS-1$
            }
        }

        /** Обёртки на цель отладки EDT: запись в консоль и обработчик событий. Повторный вызов — no-op. */
        private static void hookTarget(Object target)
        {
            // Реальный класс — наследник (в EDT с профилированием ProfilingRuntimeDebugClientTarget).
            if (target == null || !isInstanceOf(target, CLIENT_TARGET_CLASS))
                return;
            synchronized (ConsoleExecutedLine.class)
            {
                Object writer = Global.getField(target, "consoleWriter"); //$NON-NLS-1$
                if (writer instanceof IDebugConsoleWriter original && !(writer instanceof Writer))
                    Global.setField(target, "consoleWriter", new Writer(target, original)); //$NON-NLS-1$
                Object dispatcher = Global.getField(target, "eventDispatcher"); //$NON-NLS-1$
                if (dispatcher == null || !isInstanceOf(dispatcher, DISPATCH_JOB_CLASS))
                    return;
                Object processor = Global.getField(dispatcher, "eventProcessor"); //$NON-NLS-1$
                if (!(processor instanceof IRuntimeEventProcessor original) || isOurProcessor(processor))
                    return;
                InvocationHandler handler = new CommandCapture(original);
                Object proxy = Proxy.newProxyInstance(IRuntimeEventProcessor.class.getClassLoader(),
                        new Class<?>[] { IRuntimeEventProcessor.class }, handler);
                Global.setField(dispatcher, "eventProcessor", proxy); //$NON-NLS-1$
            }
        }

        private static boolean isInstanceOf(Object object, String className)
        {
            for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass())
            {
                if (className.equals(type.getName()))
                    return true;
            }
            return false;
        }

        private static boolean isOurProcessor(Object processor)
        {
            return Proxy.isProxyClass(processor.getClass())
                    && Proxy.getInvocationHandler(processor) instanceof CommandCapture;
        }

        /** Запоминает команду {@code handleCallStackFormed} на время её обработки целью. */
        private static final class CommandCapture implements InvocationHandler
        {
            private final IRuntimeEventProcessor delegate;

            CommandCapture(IRuntimeEventProcessor delegate)
            {
                this.delegate = delegate;
            }

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable
            {
                boolean capture = "handleCallStackFormed".equals(method.getName()) && args != null && args.length == 1; //$NON-NLS-1$
                Object previous = CURRENT_COMMAND.get();
                if (capture)
                    CURRENT_COMMAND.set(args[0]);
                try
                {
                    return method.invoke(delegate, args);
                }
                catch (InvocationTargetException e)
                {
                    throw e.getCause() != null ? e.getCause() : e;
                }
                finally
                {
                    if (capture)
                    {
                        if (previous == null)
                            CURRENT_COMMAND.remove();
                        else
                            CURRENT_COMMAND.set(previous);
                    }
                }
            }
        }

        /** Ожидаемая после нашего заголовка часть вывода EDT: сообщение, затем два перевода строки. */
        private static final class Pending
        {
            final IBslLineBreakpoint breakpoint;
            final String methodSignature;
            final Object command;
            boolean messageDone;
            int separators;

            Pending(IBslLineBreakpoint breakpoint, String methodSignature, Object command)
            {
                this.breakpoint = breakpoint;
                this.methodSignature = methodSignature;
                this.command = command;
            }
        }

        private static final class Writer implements IDebugConsoleWriter
        {
            private final Object target;
            private final IDebugConsoleWriter delegate;
            private final ThreadLocal<Pending> pending = new ThreadLocal<>();

            Writer(Object target, IDebugConsoleWriter delegate)
            {
                this.target = target;
                this.delegate = delegate;
            }

            @Override
            public void print(ILaunch launch, String text)
            {
                try
                {
                    if (handle(launch, text))
                        return;
                }
                catch (RuntimeException e)
                {
                    pending.remove();
                    BreakpointPropertiesDebug.problem("вывод в консоль: " + e); //$NON-NLS-1$
                }
                delegate.print(launch, text);
            }

            /** {@code true} — текст обработан здесь, штатно печатать не нужно. */
            private boolean handle(ILaunch launch, String text)
            {
                Pending current = pending.get();
                if (current != null)
                {
                    if (!current.messageDone)
                    {
                        current.messageDone = true;
                        delegate.print(launch, composeLine(current, text));
                        return true;
                    }
                    if (text != null && text.isBlank() && current.separators < 2)
                    {
                        if (++current.separators >= 2)
                            pending.remove();
                        return true;
                    }
                    pending.remove();
                }

                Matcher matcher = headerPattern(target).matcher(text == null ? "" : text); //$NON-NLS-1$
                if (!matcher.matches())
                    return false;
                String signature = matcher.group(1);
                int lineNumber = parseLineNumber(matcher.group(2));
                IBslLineBreakpoint breakpoint = findConsoleOutputBreakpoint(signature, lineNumber);
                if (breakpoint == null)
                    return false;
                pending.set(new Pending(breakpoint, signature, CURRENT_COMMAND.get()));
                return true;
            }

            /**
             * {@code > <Время.мс>: <Модуль>:<Строка>, (<Предмет>: <Сеанс>, <Сервер>:<Порт>), <Метод>: <Текст строки>}
             * и следом сообщение сервера без скрытого описания.
             */
            private String composeLine(Pending pending, String message)
            {
                IBslLineBreakpoint breakpoint = pending.breakpoint;
                String rest = stripHiddenDescription(message);
                StringBuilder line = new StringBuilder("> ").append(LocalTime.now().format(TIME_FORMAT)).append(": "); //$NON-NLS-1$ //$NON-NLS-2$
                String moduleName = MarkersListViewsHook.ModuleField.moduleName(breakpoint.getMarker());
                line.append(moduleName);
                int lineNumber = 0;
                try
                {
                    lineNumber = breakpoint.getLineNumber();
                }
                catch (CoreException ignored) {}
                line.append(':').append(lineNumber).append(", (").append(targetPresentation(pending.command)) //$NON-NLS-1$
                        .append(", ").append(serverAddress()).append("), ") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(methodName(pending.methodSignature))
                        .append(": "); //$NON-NLS-1$
                IDocument module = moduleDocument(breakpoint);
                line.append(codeLine(module, lineNumber));
                IMarker marker = breakpoint.getMarker();
                if (marker != null && marker.getResource() instanceof IFile file)
                    rememberPrinted(line.toString(), new PrintedLocation(file.getProject(),
                            moduleLineRef(module, moduleName, lineNumber, methodName(pending.methodSignature))));
                line.append(System.lineSeparator());
                if (!rest.isEmpty())
                    line.append(rest).append(System.lineSeparator());
                return line.toString();
            }

            private String serverAddress()
            {
                Object url = Global.invoke(target, "getDebugServerUrl"); //$NON-NLS-1$
                if (!(url instanceof String s) || s.isBlank())
                    return ""; //$NON-NLS-1$
                try
                {
                    URI uri = URI.create(s.strip());
                    if (uri.getHost() != null)
                        return uri.getPort() >= 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost(); //$NON-NLS-1$
                }
                catch (IllegalArgumentException ignored) {}
                return s.strip();
            }
        }

        /** {@code <Предмет отладки>: <Номер сеанса>} из {@link DebugTargetId} команды. */
        private static String targetPresentation(Object command)
        {
            Object id = command != null ? Global.invoke(command, "getTargetID") : null; //$NON-NLS-1$
            if (!(id instanceof DebugTargetId targetId))
                return "?"; //$NON-NLS-1$
            String type = targetId.getTargetType() != null
                    ? DebugTargetTypeUtil.getPresentation(targetId.getTargetType())
                    : ""; //$NON-NLS-1$
            BigDecimal seance = targetId.getSeanceNo();
            return type + ": " + (seance != null ? seance.toPlainString() : "?"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** Скрытое описание — строкой целиком; остальное сообщение сервера как есть. */
        private static String stripHiddenDescription(String message)
        {
            if (message == null)
                return ""; //$NON-NLS-1$
            List<String> kept = new ArrayList<>();
            for (String line : message.split("\\R", -1)) //$NON-NLS-1$
            {
                if (!CONSOLE_HIDDEN_DESCRIPTION.equals(line.strip()))
                    kept.add(line);
            }
            return String.join(System.lineSeparator(), kept).strip();
        }

        /** Имя метода из сигнатуры EDT: до скобки, последнее слово. */
        private static String methodName(String signature)
        {
            if (signature == null)
                return ""; //$NON-NLS-1$
            String s = signature.strip();
            int paren = s.indexOf('(');
            if (paren >= 0)
                s = s.substring(0, paren).strip();
            int cut = Math.max(s.lastIndexOf(' '), s.lastIndexOf('.'));
            return cut >= 0 ? s.substring(cut + 1) : s;
        }

        private static String codeLine(IDocument module, int lineNumber)
        {
            if (module == null || lineNumber <= 0 || lineNumber > module.getNumberOfLines())
                return ""; //$NON-NLS-1$
            try
            {
                IRegion region = module.getLineInformation(lineNumber - 1);
                return module.get(region.getOffset(), region.getLength()).strip();
            }
            catch (BadLocationException e)
            {
                return ""; //$NON-NLS-1$
            }
        }

        /**
         * Ссылка на строку с позицией относительно метода — как «Копировать ссылку на строку»
         * ({@link GetRef}): {@code {Модуль(строка,0:Метод,смещение)}}. Переход по ней
         * ({@link GoToDefinition#jump}) ищет метод заново и переживает правки модуля.
         */
        private static String moduleLineRef(IDocument module, String moduleName, int lineNumber, String methodName)
        {
            int declaration0 = module != null && !methodName.isEmpty()
                    ? GetRef.findMethodDeclarationLine(module, methodName)
                    : -1;
            if (declaration0 < 0)
                return "{" + moduleName + "(" + lineNumber + ",0)}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            int offset = lineNumber - (declaration0 + 1);
            return "{" + moduleName + "(" + lineNumber + ",0:" + methodName + "," + offset + ")}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }

        /** Текст модуля точки останова с диска (кэш по отметке изменения файла). */
        private static IDocument moduleDocument(IBslLineBreakpoint breakpoint)
        {
            IMarker marker = breakpoint.getMarker();
            if (marker == null || !(marker.getResource() instanceof IFile file) || !file.exists())
                return null;
            String key = file.getFullPath() + "#" + file.getModificationStamp(); //$NON-NLS-1$
            IDocument cached;
            synchronized (LINE_CACHE)
            {
                cached = LINE_CACHE.get(key);
            }
            if (cached != null)
                return cached;
            try (InputStream in = file.getContents(true))
            {
                Charset charset;
                try
                {
                    charset = Charset.forName(file.getCharset());
                }
                catch (RuntimeException e)
                {
                    charset = StandardCharsets.UTF_8;
                }
                String content = new String(in.readAllBytes(), charset);
                if (!content.isEmpty() && content.charAt(0) == '﻿')
                    content = content.substring(1);
                cached = new Document(content);
            }
            catch (CoreException | IOException e)
            {
                return null;
            }
            synchronized (LINE_CACHE)
            {
                LINE_CACHE.put(key, cached);
            }
            return cached;
        }

        /** Точка с общим флажком вывода по сигнатуре метода и строке из заголовка EDT. */
        private static IBslLineBreakpoint findConsoleOutputBreakpoint(String signature, int lineNumber)
        {
            DebugPlugin plugin = DebugPlugin.getDefault();
            if (plugin == null || lineNumber <= 0)
                return null;
            for (IBreakpoint breakpoint : plugin.getBreakpointManager().getBreakpoints())
            {
                if (!(breakpoint instanceof IBslLineBreakpoint line))
                    continue;
                try
                {
                    IMarker marker = line.getMarker();
                    if (marker != null && marker.exists() && line.getLineNumber() == lineNumber
                            && Objects.equals(line.getMethodSignature(), signature)
                            && Boolean.TRUE.equals(marker.getAttribute(MARKER_CONSOLE_OUTPUT)))
                        return line;
                }
                catch (CoreException ignored) {}
            }
            return null;
        }

        private static int parseLineNumber(String text)
        {
            String digits = text == null ? "" : text.replaceAll("\\D", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            try
            {
                return digits.isEmpty() ? 0 : Integer.parseInt(digits);
            }
            catch (NumberFormatException e)
            {
                return 0;
            }
        }

        /** Шаблон заголовка «метод {0}, строка {1}» из {@code Messages} EDT (с учётом локализации). */
        private static Pattern headerPattern(Object target)
        {
            Pattern pattern = headerPattern;
            if (pattern != null)
                return pattern;
            String template = HEADER_FALLBACK;
            try
            {
                Class<?> messages = target.getClass().getClassLoader().loadClass(MESSAGES_CLASS);
                Object value = messages.getField(HEADER_FIELD).get(null);
                if (value instanceof String s && s.contains("{0}") && s.contains("{1}")) //$NON-NLS-1$ //$NON-NLS-2$
                    template = s;
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                BreakpointPropertiesDebug.problem("шаблон заголовка точки останова в консоли: " + e); //$NON-NLS-1$
            }
            int first = template.indexOf("{0}"); //$NON-NLS-1$
            int second = template.indexOf("{1}"); //$NON-NLS-1$
            String regex;
            if (first >= 0 && second > first)
                regex = Pattern.quote(template.substring(0, first)) + "(.*)" //$NON-NLS-1$
                        + Pattern.quote(template.substring(first + 3, second)) + "([\\d\\s\\u00A0\\u202F]+?)" //$NON-NLS-1$
                        + Pattern.quote(template.substring(second + 3)) + "\\s*"; //$NON-NLS-1$
            else
                regex = "(?!)"; //$NON-NLS-1$
            pattern = Pattern.compile(regex, Pattern.DOTALL);
            headerPattern = pattern;
            return pattern;
        }
    }
}
