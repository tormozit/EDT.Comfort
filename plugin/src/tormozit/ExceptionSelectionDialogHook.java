package tormozit;

import java.lang.reflect.Field;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.dialogs.BslExceptionSmartFilter;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog;
import org.osgi.framework.Bundle;

import com.google.inject.Injector;

import com._1c.g5.v8.dt.stacktraces.model.IStacktrace;
import com._1c.g5.v8.dt.stacktraces.model.IStacktraceElement;
import com._1c.g5.v8.dt.stacktraces.model.IStacktraceError;
import com._1c.g5.v8.dt.stacktraces.model.IStacktraceParser;

/**
 * Штатный диалог «Остановка по ошибке» EDT ({@code BslExceptionSelectionDialog} — открывается
 * кнопкой «Добавить точку останова по исключению» панели «Точки останова», а также
 * {@link StacktracesViewInteractionHook} из панели «Трассировки стека»):
 * <ul>
 * <li>штатное поле фильтра заменяется на общий {@link FilterInputBox} (как обычно в этом плагине)
 * с многословным AND-фильтром {@link BslExceptionSmartFilter} вместо штатного подстрочного;</li>
 * <li>в заголовок дописывается « (Новая)» — отличать от редактора существующей точки
 * ({@link BreakpointListHook}, окно «Сообщение исключения»);</li>
 * <li>добавляется кнопка «Вставить из буфера» — читает буфер обмена и разбирает его штатным
 * {@code IStacktraceParser} EDT (тем же, что использует сама панель «Трассировки стека»), берёт
 * причину из получившегося {@code IStacktraceError} — парсер сам решает, похож ли текст на дамп
 * ошибки (нужен хотя бы один уровень стека), никакой своей эвристики;</li>
 * <li>это же действие выполняется один раз сразу при открытии диалога (без клика);</li>
 * <li>{@link StacktracesViewInteractionHook} передаёт уже готовую (доверенную) причину напрямую,
 * через {@link #setPendingReason}, в обход буфера обмена — она и так уже разобрана EDT (панель
 * «Трассировки стека» строит {@code IStacktraceError} при добавлении, парсер повторно не нужен).</li>
 * </ul>
 */
public final class ExceptionSelectionDialogHook implements IStartup
{
    /** {@code Messages.BslExceptionSelectionDialog_Exception_selection_dialog_title} (ru). */
    private static final String DIALOG_TITLE = "Остановка по ошибке"; //$NON-NLS-1$
    private static final String TITLE_SUFFIX = " (Новая)"; //$NON-NLS-1$
    private static final String PATCHED_KEY = "tormozit.exceptionSelectionDialogPatched"; //$NON-NLS-1$
    private static final String PASTE_BUTTON_LABEL = "Вставить из буфера"; //$NON-NLS-1$
    private static final String PASTE_BUTTON_TOOLTIP =
            "Извлечь причину ошибки из буфера обмена и подставить в поле фильтра"; //$NON-NLS-1$

    private static volatile String pendingReason;

    /**
     * Доверенная причина от {@link StacktracesViewInteractionHook} — уже прошла разбор EDT
     * (узел {@code IStacktraceError}), подставляется в поле фильтра при следующем открытии этого
     * диалога напрямую, без повторной проверки «похоже ли на текст ошибки».
     */
    static void setPendingReason(String reason)
    {
        pendingReason = reason;
    }

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            if (shell.getData(PATCHED_KEY) != null)
                return;
            if (!DIALOG_TITLE.equals(shell.getText()))
                return;
            schedulePatchAttempt(display, shell, 0);
        };

        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
    }

    private static void schedulePatchAttempt(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;
        int delay = attempt == 0 ? 0 : 60;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (tryPatch(shell))
                return;
            if (attempt < 15)
                schedulePatchAttempt(display, shell, attempt + 1);
        });
    }

    private static boolean tryPatch(Shell shell)
    {
        if (!(shell.getData() instanceof FilteredItemsSelectionDialog dialog))
            return false;

        Control patternControl = dialog.getPatternControl();
        if (!(patternControl instanceof Text patternText) || patternText.isDisposed())
            return false;

        Control buttonBar = ((Dialog) dialog).buttonBar;
        if (!(buttonBar instanceof Composite buttonBarComposite) || buttonBarComposite.isDisposed())
            return false;

        Table table = resolveTable(dialog);
        if (table == null)
            return false;

        BslExceptionSmartFilter smartFilter = new BslExceptionSmartFilter(dialog, patternText.getText());

        FilterInputBox filterInput = FilterInputBox.replacePatternText(
                patternText, FilterInputBox.Scope.FILTERED_LIST_DIALOG, null);
        if (filterInput == null)
            return false;

        Control filterControl = filterInput.inputControl();
        if (filterControl == null)
            filterControl = filterInput.widget();
        updatePatternControlReference(dialog, filterControl);

        Button pasteButton = installPasteButton(buttonBarComposite, filterInput);
        if (pasteButton == null)
            return false;

        installFilterModifyListener(filterControl, dialog, smartFilter);
        FilterInputBoxListNavigation.installTableNavigation(filterControl, table, null);

        shell.setText(DIALOG_TITLE + TITLE_SUFFIX);
        shell.setData(PATCHED_KEY, Boolean.TRUE);

        applySmartFilter(dialog, smartFilter, filterInput.getText());
        filterInput.scheduleFocusWhenReady();
        fillOnOpen(filterInput, shell);
        return true;
    }

    /** Доверенная причина ({@link #setPendingReason}), иначе — как обычная кнопка, из буфера. */
    private static void fillOnOpen(FilterInputBox filterInput, Shell shell)
    {
        String trusted = pendingReason;
        pendingReason = null;
        Global.tempLog("ExceptionSelectionDialog", "fillOnOpen pendingReason=[" + trusted + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (trusted != null && !trusted.isEmpty())
        {
            filterInput.setText(trusted);
            return;
        }
        pasteFromClipboard(filterInput, shell);
    }

    // -----------------------------------------------------------------------
    // Кнопка «Вставить из буфера»
    // -----------------------------------------------------------------------

    private static Button installPasteButton(Composite buttonBarComposite, FilterInputBox filterInput)
    {
        if (!(buttonBarComposite.getLayout() instanceof GridLayout layout))
            return null;

        Button button = new Button(buttonBarComposite, SWT.PUSH);
        button.setText(PASTE_BUTTON_LABEL);
        button.setToolTipText(PASTE_BUTTON_TOOLTIP + Global.pluginSignForTooltip());
        GridDataFactory.swtDefaults().applyTo(button);
        layout.numColumns++;

        Control[] siblings = buttonBarComposite.getChildren();
        if (siblings.length > 1)
            button.moveAbove(siblings[0]);

        button.addListener(SWT.Selection, e -> pasteFromClipboard(filterInput, buttonBarComposite.getShell()));
        buttonBarComposite.layout(true, true);
        return button;
    }

    private static void pasteFromClipboard(FilterInputBox filterInput, Shell shell)
    {
        if (filterInput.isDisposed())
            return;
        String clipboardText = TextEditor.readClipboardText(shell);
        Global.tempLog("ExceptionSelectionDialog", "pasteFromClipboard clipboardText=[" + clipboardText + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (clipboardText == null || clipboardText.isBlank())
            return;
        String reason = extractErrorReasonViaParser(clipboardText);
        Global.tempLog("ExceptionSelectionDialog", "pasteFromClipboard reason=[" + reason + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (reason.isEmpty())
            return;
        filterInput.setText(reason);
    }

    // -----------------------------------------------------------------------
    // Разбор буфера обмена штатным IStacktraceParser EDT
    // -----------------------------------------------------------------------

    private static final String STACKTRACES_BUNDLE = "com._1c.g5.v8.dt.stacktraces"; //$NON-NLS-1$
    private static final String STACKTRACES_PLUGIN_CLASS =
            "com._1c.g5.v8.dt.internal.stacktraces.StacktracesPlugin"; //$NON-NLS-1$

    /**
     * Разбирает {@code rawText} тем же парсером, что панель «Трассировки стека» — если EDT находит
     * в тексте узел {@code IStacktraceError} (т.е. считает, что это похоже на дамп ошибки с хотя бы
     * одним уровнем стека), берёт из него причину, иначе — "".
     */
    private static String extractErrorReasonViaParser(String rawText)
    {
        IStacktraceParser parser = resolveParser();
        if (parser == null)
            return ""; //$NON-NLS-1$
        try
        {
            IStacktrace trace = parser.parse(rawText, null, null);
            if (trace == null)
                return ""; //$NON-NLS-1$
            for (IStacktraceElement child : trace.getChilden())
            {
                if (child instanceof IStacktraceError errorNode)
                    return BreakpointListHook.firstLine(errorNode.getName());
            }
        }
        catch (Exception e)
        {
            Global.log("ExceptionSelectionDialog", "extractErrorReasonViaParser: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ""; //$NON-NLS-1$
    }

    /** {@code IStacktraceParser} не привязан к команде/действию — берётся из Guice-инжектора бандла. */
    private static IStacktraceParser resolveParser()
    {
        try
        {
            Bundle bundle = Platform.getBundle(STACKTRACES_BUNDLE);
            if (bundle == null)
                return null;
            Class<?> pluginClass = bundle.loadClass(STACKTRACES_PLUGIN_CLASS);
            Object plugin = Global.invoke(pluginClass, "getDefault"); //$NON-NLS-1$
            Object injectorObj = Global.invoke(plugin, "getInjector"); //$NON-NLS-1$
            if (!(injectorObj instanceof Injector injector))
                return null;
            return injector.getInstance(IStacktraceParser.class);
        }
        catch (Exception e)
        {
            Global.log("ExceptionSelectionDialog", "resolveParser: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Замена поля фильтра + подключение BslExceptionSmartFilter
    // -----------------------------------------------------------------------

    private static Table resolveTable(Object dialog)
    {
        Object viewerObj = Global.getField(dialog, "tableViewer"); //$NON-NLS-1$
        if (!(viewerObj instanceof TableViewer viewer) || viewer.getControl().isDisposed())
            return null;
        return viewer.getTable();
    }

    private static void updatePatternControlReference(Object dialog, Control control)
    {
        Global.setField(dialog, "pattern", control); //$NON-NLS-1$
    }

    private static void installFilterModifyListener(Control filterControl, FilteredItemsSelectionDialog dialog,
            BslExceptionSmartFilter smartFilter)
    {
        Runnable[] pending = { null };
        ModifyListener listener = e ->
        {
            Display display = filterControl.getDisplay();
            if (pending[0] != null)
                display.timerExec(-1, pending[0]);
            pending[0] = () -> applySmartFilter(dialog, smartFilter, getFilterPattern(filterControl));
            display.timerExec(150, pending[0]);
        };
        if (filterControl instanceof Text text)
            text.addModifyListener(listener);
        else if (filterControl instanceof StyledText styled)
            styled.addModifyListener(listener);

        filterControl.addDisposeListener(e ->
        {
            if (pending[0] != null && !filterControl.getDisplay().isDisposed())
                filterControl.getDisplay().timerExec(-1, pending[0]);
        });
    }

    private static String getFilterPattern(Control filterControl)
    {
        if (filterControl == null || filterControl.isDisposed())
            return ""; //$NON-NLS-1$
        if (filterControl instanceof Text text)
            return text.getText();
        if (filterControl instanceof StyledText styled)
            return styled.getText();
        return ""; //$NON-NLS-1$
    }

    /** Порт схемы {@code OpenMdObjectHook.applySmartFilter}, упрощённый под плоский список строк. */
    private static void applySmartFilter(Object dialog, BslExceptionSmartFilter smartFilter, String pattern)
    {
        Object currentFilter = Global.getField(dialog, "filter"); //$NON-NLS-1$
        boolean skip = smartFilter.shouldSkipSchedule(pattern, currentFilter);
        boolean filterHandoff = currentFilter != smartFilter;

        smartFilter.setPattern(pattern);
        if (skip && !filterHandoff)
            return;

        Job filterHistoryJob = getJobField(dialog, "filterHistoryJob"); //$NON-NLS-1$
        Job filterJob = getJobField(dialog, "filterJob"); //$NON-NLS-1$
        if (filterHistoryJob != null)
            filterHistoryJob.cancel();
        if (filterJob != null)
            filterJob.cancel();

        setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "filter", smartFilter); //$NON-NLS-1$
        setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "lastCompletedFilter", null); //$NON-NLS-1$
        setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "lastCompletedResult", null); //$NON-NLS-1$

        if (filterHistoryJob != null)
            filterHistoryJob.schedule();
    }

    private static Job getJobField(Object dialog, String fieldName)
    {
        Object job = Global.getField(dialog, fieldName);
        return job instanceof Job ? (Job) job : null;
    }

    private static void setFieldExactClass(Object obj, Class<?> exactClass, String fieldName, Object value)
    {
        try
        {
            Field f = exactClass.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(obj, value);
        }
        catch (Exception ignored)
        {
        }
    }
}
