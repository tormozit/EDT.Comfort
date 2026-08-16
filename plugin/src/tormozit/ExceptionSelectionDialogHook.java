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
 * <li>штатный флажок «Останавливаться по ошибке» переименовывается в «Без фильтра» — штатная
 * подпись не передаёт его поведение (он означает точку «Все исключения» и очищает причину);</li>
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
    private static final String SHADOW_PATTERN_KEY = "tormozit.exceptionSelectionShadowPattern"; //$NON-NLS-1$
    /**
     * ОПАСНО удлинять: контейнер флажка в штатном диалоге узкий (клиентская область ~189 px при
     * отступе 5 px), и подпись длиннее ~180 px просто обрезается. Растягивание флажка
     * ({@code GridData} FILL + grab) и расширение окна ({@code shell.setSize}) уже пробовались и
     * дают либо обрезку, либо пустое поле справа от контролов. Держать подпись короткой.
     */
    private static final String CATCH_ALL_LABEL = "Без фильтра"; //$NON-NLS-1$
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
        // Штатный okPressed() делает ((Text) getPatternControl()).getText() — SearchBox/
        // StyledText туда ставить нельзя (ClassCastException → OK «ничего не делает», #255).
        // Оставляем скрытый Text как pattern и синхронизируем его с полем фильтра.
        Text shadowPattern = installShadowPatternText(shell, filterInput.getText());
        updatePatternControlReference(dialog, shadowPattern);

        Button pasteButton = installPasteButton(buttonBarComposite, filterInput, shadowPattern);
        if (pasteButton == null)
            return false;

        installFilterModifyListener(filterControl, dialog, smartFilter, shadowPattern);
        FilterInputBoxListNavigation.installTableNavigation(filterControl, table, null);

        renameCatchAllCheckbox(shell);

        shell.setText(DIALOG_TITLE + TITLE_SUFFIX);
        shell.setData(PATCHED_KEY, Boolean.TRUE);

        applySmartFilter(dialog, smartFilter, filterInput.getText());
        filterInput.scheduleFocusWhenReady();
        fillOnOpen(filterInput, shell, shadowPattern);
        return true;
    }

    /**
     * Штатная подпись флажка «Останавливаться по ошибке» ({@code Messages.BslExceptionSelectionDialog_Catch_all})
     * читается как «сделать остановку по этой ошибке», хотя флажок означает остановку на
     * <em>любых</em> ошибках (штатный {@code catchAllExceptions}: очищает поле причины и создаёт
     * точку «Все исключения»). Подпись «Без фильтра» короткая — умещается в штатный узкий
     * контейнер, поэтому ширины окна и флажка не трогаем.
     */
    private static void renameCatchAllCheckbox(Shell shell)
    {
        Button checkbox = findCatchAllCheckbox(shell);
        if (checkbox == null)
            return;
        // Подпись короче штатной — помещается в узкий контейнер как есть, без подгонки ширин.
        checkbox.setText(CATCH_ALL_LABEL);
        Composite parent = checkbox.getParent();
        if (parent != null && !parent.isDisposed())
            parent.layout(true);
    }

    private static Button findCatchAllCheckbox(Composite parent)
    {
        for (Control child : parent.getChildren())
        {
            if (child instanceof Button button && (button.getStyle() & SWT.CHECK) != 0)
                return button;
            if (child instanceof Composite composite)
            {
                Button found = findCatchAllCheckbox(composite);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /**
     * Скрытый {@link Text}, который остаётся в {@code dialog.pattern}: штатный
     * {@code BslExceptionSelectionDialog.okPressed} читает текст только через каст к Text.
     */
    private static Text installShadowPatternText(Shell shell, String initial)
    {
        Text shadow = new Text(shell, SWT.NONE);
        shadow.setVisible(false);
        shadow.setBounds(0, 0, 0, 0);
        shadow.setText(initial != null ? initial : ""); //$NON-NLS-1$
        shell.setData(SHADOW_PATTERN_KEY, shadow);
        return shadow;
    }

    /** Доверенная причина ({@link #setPendingReason}), иначе — как обычная кнопка, из буфера. */
    private static void fillOnOpen(FilterInputBox filterInput, Shell shell, Text shadowPattern)
    {
        String trusted = pendingReason;
        pendingReason = null;
        if (trusted != null && !trusted.isEmpty())
        {
            filterInput.setText(trusted);
            syncShadowPattern(shadowPattern, trusted);
            return;
        }
        pasteFromClipboard(filterInput, shell, shadowPattern);
    }

    // -----------------------------------------------------------------------
    // Кнопка «Вставить из буфера»
    // -----------------------------------------------------------------------

    private static Button installPasteButton(Composite buttonBarComposite, FilterInputBox filterInput,
            Text shadowPattern)
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

        button.addListener(SWT.Selection, e -> pasteFromClipboard(filterInput, buttonBarComposite.getShell(),
                shadowPattern));
        buttonBarComposite.layout(true, true);
        return button;
    }

    private static void pasteFromClipboard(FilterInputBox filterInput, Shell shell, Text shadowPattern)
    {
        if (filterInput.isDisposed())
            return;
        String clipboardText = TextEditor.readClipboardText(shell);
        if (clipboardText == null || clipboardText.isBlank())
            return;
        String reason = extractErrorReasonViaParser(clipboardText);
        if (reason.isEmpty())
            return;
        filterInput.setText(reason);
        syncShadowPattern(shadowPattern, reason);
    }

    private static void syncShadowPattern(Text shadowPattern, String text)
    {
        if (shadowPattern == null || shadowPattern.isDisposed())
            return;
        String value = text != null ? text : ""; //$NON-NLS-1$
        if (!value.equals(shadowPattern.getText()))
            shadowPattern.setText(value);
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
            return StacktraceCompileFormatHook.wrapParser(injector.getInstance(IStacktraceParser.class));
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
            BslExceptionSmartFilter smartFilter, Text shadowPattern)
    {
        Runnable[] pending = { null };
        ModifyListener listener = e ->
        {
            // Синхрон сразу: OK может нажать до срабатывания debounce фильтра.
            syncShadowPattern(shadowPattern, getFilterPattern(filterControl));
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
