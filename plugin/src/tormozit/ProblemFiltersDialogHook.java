package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.common.localization.LocalizedEnumProvider;
import com._1c.g5.v8.dt.ui.V8UiSharedImages;
import com.e1c.g5.v8.dt.check.settings.IssueType;

/**
 * Окно «Настройки отбора» панели проблем конфигурации
 * ({@code com._1c.g5.v8.dt.internal.ui.validation.ProblemViewFiltersDialog}) —
 * приведение к терминологии issue 401.
 *
 * <p>Штатно окно устроено так: заголовок «Показывать проблемы:», под ним секции
 * «Критичность» и «Тип», а флажок «Показывать ошибки конфигурации» — в самом
 * низу, отдельно от всего. Из-за этого не видно главного: ошибка конфигурации —
 * это такой же вид проблемы, как и то, что перечислено выше, а секции
 * «Критичность»/«Тип» описывают именно предупреждения.
 *
 * <p>Поэтому: заголовок становится «Показывать предупреждения:» со значком
 * предупреждения, а штатный флажок ошибок конфигурации переносится наверх,
 * прямо под заголовок — рядом с остальными видами проблем (текст флажка
 * остаётся штатным). Заодно тип «Предупреждение» показывается как «Прочее
 * предупреждение» с нейтральным значком (см.
 * {@link ValidationChecksFilterHook#typeImage}) — штатный значок этого типа
 * совпадает с маркером критичности «Незначительная» в модуле.
 *
 * <p>Отдельный файл, а не вложенный класс: точка входа из {@code plugin.xml}.
 */
public final class ProblemFiltersDialogHook implements IStartup
{
    private static final String DIALOG_CLASS_NAME =
        "com._1c.g5.v8.dt.internal.ui.validation.ProblemViewFiltersDialog"; //$NON-NLS-1$
    private static final String PATCHED_KEY = "tormozit.problemFiltersDialogPatched"; //$NON-NLS-1$

    /** Штатные тексты EDT ({@code internal/ui/validation/messages.properties}). */
    private static final String SHOW_PROBLEMS_LABEL = "Показывать проблемы:"; //$NON-NLS-1$
    private static final String SHOW_BUILD_ERRORS_LABEL = "Показывать ошибки конфигурации"; //$NON-NLS-1$

    /** Двоеточие — как у штатного заголовка: за ним идут перечисляемые виды проблем. */
    private static final String SHOW_WARNINGS_LABEL = "Показывать предупреждения:"; //$NON-NLS-1$
    /** Треугольник с восклицательным знаком — общий знак предупреждения. */
    private static final String WARNING_ICON_PATH = "/icons/markers16/warning.gif"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> install(display));
    }

    private static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, event ->
        {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            if (Boolean.TRUE.equals(shell.getData(PATCHED_KEY)) || !isFiltersDialog(shell))
                return;
            shell.setData(PATCHED_KEY, Boolean.TRUE);
            patch(shell);
        });
        Debug.log("install: installed"); //$NON-NLS-1$
    }

    private static boolean isFiltersDialog(Shell shell)
    {
        Object dialog = shell.getData();
        return dialog != null && DIALOG_CLASS_NAME.equals(dialog.getClass().getName());
    }

    private static void patch(Shell shell)
    {
        Label header = findLabel(shell, SHOW_PROBLEMS_LABEL);
        Button configErrors = findCheckbox(shell, SHOW_BUILD_ERRORS_LABEL);
        Control replacement = header != null ? replaceHeader(header) : null;

        // Текст флажка оставляем штатным — меняется только его место: сразу под
        // заголовком, рядом с остальными видами проблем.
        if (configErrors != null && replacement != null && configErrors.getParent() == replacement.getParent())
            configErrors.moveBelow(replacement);

        renameWarningType(shell);

        shell.layout(true, true);
        Debug.log("patch: header=" + (header != null) + " configErrors=" + (configErrors != null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * SWT {@link Label} показывает либо текст, либо картинку, поэтому значок к
     * заголовку добавляем через {@link CLabel} на том же месте, а штатную метку
     * прячем (её создаёт и настраивает шрифтом сам диалог, удалять нельзя —
     * databinding и раскладка рассчитаны на неё).
     */
    private static Control replaceHeader(Label header)
    {
        Composite parent = header.getParent();
        CLabel replacement = new CLabel(parent, SWT.NONE);
        replacement.setText(SHOW_WARNINGS_LABEL);
        replacement.setFont(header.getFont());
        replacement.setBackground(header.getBackground());
        replacement.setImage(V8UiSharedImages.getImage(WARNING_ICON_PATH));
        replacement.setLayoutData(copyLayoutData(header.getLayoutData()));
        replacement.moveAbove(header);

        header.setVisible(false);
        if (header.getLayoutData() instanceof GridData data)
            data.exclude = true;
        return replacement;
    }

    private static Object copyLayoutData(Object source)
    {
        if (!(source instanceof GridData original))
            return new GridData(SWT.FILL, SWT.CENTER, true, false);
        GridData copy = new GridData(original.horizontalAlignment, original.verticalAlignment,
            original.grabExcessHorizontalSpace, original.grabExcessVerticalSpace, original.horizontalSpan,
            original.verticalSpan);
        copy.horizontalIndent = original.horizontalIndent;
        copy.verticalIndent = original.verticalIndent;
        return copy;
    }

    /** Флажок типа «Предупреждение» → «Прочее предупреждение» с нейтральным значком. */
    private static void renameWarningType(Shell shell)
    {
        Button warningType = findCheckbox(shell, LocalizedEnumProvider.getLocalizedString(IssueType.WARNING));
        if (warningType == null)
            return;
        warningType.setText(ValidationChecksFilterHook.OTHER_WARNING_TYPE_TITLE);
        warningType.setImage(ValidationChecksFilterHook.typeImage(shell.getDisplay(), IssueType.WARNING));
    }

    private static Label findLabel(Control control, String text)
    {
        if (control instanceof Label label && text.equals(label.getText()))
            return label;
        if (control instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                Label found = findLabel(child, text);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static Button findCheckbox(Control control, String text)
    {
        if (control instanceof Button button && (button.getStyle() & SWT.CHECK) != 0 && text.equals(button.getText()))
            return button;
        if (control instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                Button found = findCheckbox(child, text);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static final class Debug
    {
        private static final String TAG = "ProblemFiltersDialog"; //$NON-NLS-1$

        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
