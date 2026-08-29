package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.common.localization.LocalizedEnumProvider;
import com._1c.g5.v8.dt.ui.V8UiSharedImages;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
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
    /** Зазор между значком критичности, значком аннотации и подписью флажка. */
    private static final int ICON_SPACING = 2;
    /** Прокручиваемая форма, внутри которой лежат секции отбора. */
    private static final String FORM_CLASS_NAME = "org.eclipse.ui.forms.widgets.ScrolledForm"; //$NON-NLS-1$

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
        Composite severityHost = applyModuleSeverityIcons(shell);

        shell.layout(true, true);
        // Раскладка секций внутри прокручиваемой формы к этому моменту ещё не
        // выполнена (её ширина — ноль), поэтому недостачу считаем после показа.
        shell.getDisplay().asyncExec(() -> grantMissingWidth(shell, severityHost));
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

    /**
     * Рядом со значком критичности показывается значок того же уровня в
     * редакторе модуля (issue 423).
     *
     * <p>В модуле проблема показывается не значком критичности, а значком
     * серьёзности, и по названию критичности не видно, чем именно она обернётся:
     * «Значительная» и выше — ошибкой, «Незначительная» — предупреждением,
     * «Тривиальная» — информацией.
     *
     * <p>Значки живут отдельными подписями, а не одной картинкой на флажке: у
     * каждого своя подсказка — про критичность и про аннотацию. Значки и флажок
     * складываются в одну ячейку, поэтому сама секция остаётся о двух столбцах,
     * как штатно. Левый столбец не трогается вовсе: критичности там все до одной
     * дают в модуле ошибку, и три одинаковых значка подряд ничего не добавляют.
     */
    private static Composite applyModuleSeverityIcons(Control root)
    {
        Composite host = findSeverityHost(root);
        if (host == null)
        {
            Debug.log("applyModuleSeverityIcons: severity checkboxes not found"); //$NON-NLS-1$
            return null;
        }
        List<Button> checkboxes = severityCheckboxes(host);
        if (checkboxes.size() < 2)
            return null;
        for (int i = 1; i < checkboxes.size(); i += 2)
            splitIcons(host, checkboxes.get(i));
        return host;
    }

    /**
     * Значок критичности и значок аннотации — отдельными подписями перед флажком,
     * все трое в общей ячейке на месте флажка.
     */
    private static void splitIcons(Composite host, Button button)
    {
        MarkerSeverity marker = severityByTitle(button.getText());
        IssueSeverity severity = ValidationChecksFilterHook.issueSeverityOf(marker);
        Image own = button.getImage();
        Image module = severity != null ? ValidationChecksFilterHook.moduleSeverityImage(severity) : null;
        if (own == null || module == null)
            return;

        // Ширину флажка запоминаем до снятия значка: после setImage(null) SWT
        // пересчитывает её без картинки, а нативный флажок место под неё не
        // освобождает — подпись начинает резаться многоточием.
        int width = button.computeSize(SWT.DEFAULT, SWT.DEFAULT).x;

        Composite cell = new Composite(host, SWT.NONE);
        cell.setLayoutData(button.getLayoutData());
        cell.moveAbove(button);
        GridLayout cellLayout = new GridLayout(3, false);
        cellLayout.marginWidth = 0;
        cellLayout.marginHeight = 0;
        cellLayout.horizontalSpacing = ICON_SPACING;
        cell.setLayout(cellLayout);

        iconLabel(cell, own, "Критичность: " + button.getText()); //$NON-NLS-1$
        Label moduleIcon = iconLabel(cell, module, ValidationChecksFilterHook.moduleSeverityTooltip(severity));
        ValidationChecksFilterHook.setModuleSeverityIcon(moduleIcon, severity);
        ValidationChecksFilterHook.wireModuleAnnotationSettings(moduleIcon);
        if (!button.setParent(cell))
        {
            cell.dispose();
            Debug.log("splitIcons: setParent failed"); //$NON-NLS-1$
            return;
        }
        button.setImage(null);
        GridData buttonData = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        buttonData.widthHint = width;
        button.setLayoutData(buttonData);
    }

    private static Label iconLabel(Composite host, Image image, String tooltip)
    {
        Label label = new Label(host, SWT.NONE);
        label.setImage(image);
        label.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));
        label.setToolTipText(TooltipText.wrap(label, tooltip));
        return label;
    }

    /**
     * Значки заняли место, которого в окне не было, — окну его и добавляем.
     *
     * <p>Считаем по самой секции критичности, а не по оболочке: секция лежит в
     * прокручиваемой форме, и та о нуждах своего содержимого оболочке не
     * сообщает — {@code shell.computeSize} остался бы прежним, а подпись
     * критичности так и осталась бы обрезанной. Форму заодно просим пересчитать
     * себя ({@code reflow}) — иначе новая ширина содержимого ей неизвестна.
     */
    private static void grantMissingWidth(Shell shell, Composite host)
    {
        if (host == null || host.isDisposed() || shell.isDisposed())
            return;
        reflowForm(host);
        int actual = host.getSize().x;
        if (actual <= 0)
        {
            // Ширины ещё нет — сравнивать не с чем, и «недостача» вышла бы равной
            // всей требуемой ширине: окно раздулось бы, а обрезка осталась.
            Debug.log("grantMissingWidth: severity host is not laid out yet"); //$NON-NLS-1$
            return;
        }
        int missing = host.computeSize(SWT.DEFAULT, SWT.DEFAULT).x - actual;
        if (missing > 0)
        {
            Point size = shell.getSize();
            shell.setSize(size.x + missing, size.y);
            shell.layout(true, true);
            reflowForm(host);
        }
        Debug.log("grantMissingWidth: missing=" + missing); //$NON-NLS-1$
    }

    /** Прокручиваемая форма пересчитывает размеры своего содержимого только по {@code reflow}. */
    private static void reflowForm(Control control)
    {
        Composite form = findForm(control);
        if (form != null)
            Global.invoke(form, "reflow", Boolean.TRUE); //$NON-NLS-1$
    }

    private static Composite findForm(Control control)
    {
        for (Composite parent = control.getParent(); parent != null; parent = parent.getParent())
        {
            if (FORM_CLASS_NAME.equals(parent.getClass().getName()))
                return parent;
        }
        return null;
    }

    /** @return композит, в котором лежат флажки критичности, или {@code null} */
    private static Composite findSeverityHost(Control control)
    {
        if (!(control instanceof Composite composite))
            return null;
        if (severityCheckboxes(composite).size() >= 2)
            return composite;
        for (Control child : composite.getChildren())
        {
            Composite found = findSeverityHost(child);
            if (found != null)
                return found;
        }
        return null;
    }

    private static List<Button> severityCheckboxes(Composite host)
    {
        List<Button> checkboxes = new ArrayList<>();
        for (Control child : host.getChildren())
        {
            if (child instanceof Button button && (button.getStyle() & SWT.CHECK) != 0
                && severityByTitle(button.getText()) != null)
            {
                checkboxes.add(button);
            }
        }
        return checkboxes;
    }

    /**
     * @return критичность по подписи флажка. Подписи типов проблем отвергаются:
     * названия там и там могут совпасть, а значок серьёзности в модуле относится
     * только к критичности
     */
    private static MarkerSeverity severityByTitle(String title)
    {
        if (title == null || title.isBlank())
            return null;
        for (IssueType type : IssueType.values())
        {
            if (title.equals(LocalizedEnumProvider.getLocalizedString(type)))
                return null;
        }
        for (MarkerSeverity marker : MarkerSeverity.values())
        {
            if (title.equals(LocalizedEnumProvider.getLocalizedString(marker)))
                return marker;
        }
        return null;
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
