package tormozit;

import org.eclipse.debug.ui.ILaunchConfigurationDialog;
import org.eclipse.debug.ui.ILaunchConfigurationTab;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;

/**
 * История последних значений всех текстовых полей вкладки «Аргументы»
 * диалога конфигураций запуска ({@code ArgumentsTab}: разделение данных,
 * параметр запуска, файл журнала, имя PWA).
 *
 * <p>UI и хранилище — как у {@link FilterInputBox}: персистентная история
 * ({@link FilterHistoryStore}) + кнопка ▾ и Ctrl+↓ ({@link FilterHistoryUi}).
 * У каждого поля свой {@code scopeId}, чтобы истории не смешивались.
 * Штатные поля остаются обычным {@link Text} (связаны с атрибутами
 * launch-конфигурации через ModifyListener самой вкладки).
 *
 * <p>Поля лежат в {@code GridLayout} вместе с подписями / кнопкой «Выбрать…»;
 * отдельная колонка ▾ в том же parent ломала бы раскладку, поэтому Text
 * переносится в узкий ряд {@code [поле][▾]}.
 */
public final class LaunchArgumentsHistoryHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.launchArgumentsHistoryPatched"; //$NON-NLS-1$
    private static final String SCHEDULED_KEY = "tormozit.launchArgumentsHistoryScheduled"; //$NON-NLS-1$
    private static final String ARGUMENTS_TAB_SUFFIX = ".ArgumentsTab"; //$NON-NLS-1$

    /** Поля {@code ArgumentsTab} → отдельный scope истории и подсказка кнопки. */
    private static final TextField[] TEXT_FIELDS = {
        new TextField("dataSeparation", "launchDataSeparation", //$NON-NLS-1$ //$NON-NLS-2$
            "История разделения данных (или Ctrl+↓ в поле)"), //$NON-NLS-1$
        // scope сохранён от первой версии хука — уже накопленная история не сбрасывается
        new TextField("startupOption", "launchStartupOption", //$NON-NLS-1$ //$NON-NLS-2$
            "История параметров запуска (или Ctrl+↓ в поле)"), //$NON-NLS-1$
        new TextField("logFile", "launchLogFile", //$NON-NLS-1$ //$NON-NLS-2$
            "История файлов журнала (или Ctrl+↓ в поле)"), //$NON-NLS-1$
        new TextField("pwaNameText", "launchPwaName", //$NON-NLS-1$ //$NON-NLS-2$
            "История имён PWA (или Ctrl+↓ в поле)"), //$NON-NLS-1$
    };

    private static final class TextField
    {
        final String fieldName;
        final String scopeId;
        final String tooltip;

        TextField(String fieldName, String scopeId, String tooltip)
        {
            this.fieldName = fieldName;
            this.scopeId = scopeId;
            this.tooltip = tooltip;
        }
    }

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    private static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        Listener listener = event ->
        {
            if (!(event.widget instanceof Control control) || control.isDisposed())
                return;
            Shell shell = control.getShell();
            if (shell == null || shell.isDisposed())
                return;
            if (!(shell.getData() instanceof ILaunchConfigurationDialog))
                return;
            scheduleTryPatch(shell);
        };
        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
    }

    private static void scheduleTryPatch(Shell shell)
    {
        if (shell.isDisposed() || Boolean.TRUE.equals(shell.getData(SCHEDULED_KEY)))
            return;
        shell.setData(SCHEDULED_KEY, Boolean.TRUE);
        shell.getDisplay().timerExec(50, () ->
        {
            if (!shell.isDisposed())
                shell.setData(SCHEDULED_KEY, null);
            tryPatch(shell);
        });
    }

    private static void tryPatch(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;
        Object data = shell.getData();
        if (!(data instanceof ILaunchConfigurationDialog dialog))
            return;
        ILaunchConfigurationTab[] tabs = dialog.getTabs();
        if (tabs == null)
            return;
        for (ILaunchConfigurationTab tab : tabs)
        {
            if (tab == null)
                continue;
            if (!tab.getClass().getName().endsWith(ARGUMENTS_TAB_SUFFIX))
                continue;
            for (TextField spec : TEXT_FIELDS)
            {
                Object field = Global.getField(tab, spec.fieldName);
                if (field instanceof Text text && !text.isDisposed())
                    wireHistory(text, spec);
            }
            return;
        }
    }

    private static void wireHistory(Text text, TextField spec)
    {
        if (Boolean.TRUE.equals(text.getData(PATCHED_KEY)))
            return;
        Composite parent = text.getParent();
        if (parent == null || parent.isDisposed())
            return;

        // Сразу, до мутаций — иначе повторный Show/Activate успеет вызвать второй wrap.
        text.setData(PATCHED_KEY, Boolean.TRUE);

        Control before = siblingBefore(text);
        Control after = siblingAfter(text);
        Object layoutData = text.getLayoutData();

        Composite row = new Composite(parent, SWT.NONE);
        GridLayout rowLayout = new GridLayout(1, false);
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 0;
        rowLayout.horizontalSpacing = 2;
        row.setLayout(rowLayout);
        if (layoutData != null)
            row.setLayoutData(layoutData);
        else
            row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        text.setParent(row);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // Важно для logFile: после setParent ряд оказывается в конце, а «Выбрать…»
        // остаётся первым — возвращаем ряд на место текста (перед бывшим соседом справа).
        if (after != null && !after.isDisposed())
            row.moveAbove(after);
        else if (before != null && !before.isDisposed())
            row.moveBelow(before);

        FilterHistoryUi.wireKeyboard(text, spec.scopeId);
        Composite buttonsRow = FilterHistoryUi.createButtonsRow(row);
        FilterHistoryUi.addHistoryButton(buttonsRow, text, spec.scopeId,
            spec.tooltip + Global.pluginSignForTooltip());
        parent.layout(true, true);
    }

    private static Control siblingBefore(Control control)
    {
        Composite parent = control.getParent();
        if (parent == null || parent.isDisposed())
            return null;
        Control[] children = parent.getChildren();
        for (int i = 0; i < children.length; i++)
        {
            if (children[i] == control)
                return i > 0 ? children[i - 1] : null;
        }
        return null;
    }

    private static Control siblingAfter(Control control)
    {
        Composite parent = control.getParent();
        if (parent == null || parent.isDisposed())
            return null;
        Control[] children = parent.getChildren();
        for (int i = 0; i < children.length; i++)
        {
            if (children[i] == control)
                return i + 1 < children.length ? children[i + 1] : null;
        }
        return null;
    }
}
