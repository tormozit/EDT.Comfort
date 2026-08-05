package tormozit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;

/**
 * Патч мастера EDT «Экспорт» (экспорт проекта V8 в файлы конфигурации): добавляет
 * флажок, который после завершения экспорта в каталог исправляет тот же баг
 * конвертации форм в формат конфигуратора 8.5, что и {@link DeployConfigurationFixHook} —
 * https://github.com/1C-Company/1c-edt-issues/issues/2157.
 * <p>В отличие от диалога «Обновление конфигурации в приложениях», этот мастер
 * сериализует XML прямо в Java-коде (бандл {@code com._1c.g5.v8.dt.export.xml}) и
 * сразу пишет результат в постоянный каталог, выбранный пользователем — гонки со
 * временем жизни файла нет, патч делается один раз после завершения экспорта
 * (кнопка «Готово» блокирует UI до конца операции — {@code ExportConfigurationWizardPage
 * .executeExport()} выполняется синхронно с модальным прогресс-диалогом).
 * <p>Экспорт в архив (радиокнопка «В архив») пока не поддержан — правится только
 * экспорт в каталог.
 */
public final class ExportConfigurationXmlFixHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.exportConfigFixPatched"; //$NON-NLS-1$
    private static final String DIALOG_TITLE = "Экспорт"; //$NON-NLS-1$
    private static final String RADIO_DIR_SNIPPET = "каталог"; //$NON-NLS-1$
    private static final String BTN_FINISH = "Готово"; //$NON-NLS-1$
    private static final String CHECKBOX_TEXT = "Исправлять проблемы после выгрузки в формат 8.5"; //$NON-NLS-1$
    private static final String CHECKBOX_TOOLTIP =
        "Убирает известный баг конвертации форм в формат конфигуратора 8.5: лишний ButtonImportance=Main " //$NON-NLS-1$
            + "у кнопок (issue 1C-Company/1c-edt-issues#2157)."; //$NON-NLS-1$
    private static final String FORM_XML_NAME = "Form.xml"; //$NON-NLS-1$
    private static final String TEMP_LOG_TOPIC = "ExportConfigFix"; //$NON-NLS-1$

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
            if (!(event.widget instanceof Shell))
                return;
            Shell shell = (Shell) event.widget;
            if (shell.getData(PATCHED_KEY) != null)
                return;
            if (!isExportDialogShell(shell))
                return;
            Global.tempLog(TEMP_LOG_TOPIC, "shell matched by title='" + shell.getText() + "', scheduling"); //$NON-NLS-1$ //$NON-NLS-2$
            schedulePatchAttempt(display, shell, 0);
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static boolean isExportDialogShell(Shell shell)
    {
        String title = shell.getText();
        return title != null && title.contains(DIALOG_TITLE);
    }

    private static void schedulePatchAttempt(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;

        display.timerExec(attempt == 0 ? 0 : 100, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;

            Button dirRadio = findButtonContaining(shell, RADIO_DIR_SNIPPET);
            Button finishButton = findButtonByText(shell, BTN_FINISH);
            if (dirRadio == null || finishButton == null)
            {
                if (attempt >= 20)
                {
                    Global.tempLog(TEMP_LOG_TOPIC, "giving up after " + attempt //$NON-NLS-1$
                        + " attempts, dirRadio=" + dirRadio + ", finishButton=" + finishButton //$NON-NLS-1$ //$NON-NLS-2$
                        + ", allControls=" + collectAllControls(shell)); //$NON-NLS-1$
                }
                else
                {
                    schedulePatchAttempt(display, shell, attempt + 1);
                }
                return;
            }

            shell.setData(PATCHED_KEY, Boolean.TRUE);
            Button ourCheckbox = addFixCheckbox(dirRadio);
            wrapFinishButton(shell, finishButton, ourCheckbox);
            Global.tempLog(TEMP_LOG_TOPIC, "checkbox added and finish button wrapped, allControls=" //$NON-NLS-1$
                + collectAllControls(shell)); //$NON-NLS-1$
        });
    }

    private static Button addFixCheckbox(Button anchor)
    {
        Composite parent = anchor.getParent();
        Button checkbox = new Button(parent, SWT.CHECK);
        checkbox.setText(CHECKBOX_TEXT);
        checkbox.setToolTipText(CHECKBOX_TOOLTIP + Global.pluginSignForTooltip());
        Object layoutData = anchor.getLayoutData();
        if (layoutData instanceof GridData)
        {
            GridData src = (GridData) layoutData;
            GridData copy = new GridData(src.horizontalAlignment, src.verticalAlignment,
                src.grabExcessHorizontalSpace, src.grabExcessVerticalSpace, src.horizontalSpan, src.verticalSpan);
            checkbox.setLayoutData(copy);
        }
        relayoutFromShell(checkbox);
        return checkbox;
    }

    /** Релейаут ближайшего родителя не поднимает размер выше по дереву до самого
     * Shell (у страницы визарда фиксированный расчётный размер) — новая строка
     * иначе обрезается за пределами видимой области. {@code Shell.computeSize}
     * даёт сильно завышенное значение (страница визарда рассчитана на граб
     * вертикального пространства) — растим точечно, на высоту самого контрола
     * плюс небольшой отступ, а не до "естественного" размера всей страницы. */
    private static void relayoutFromShell(Control control)
    {
        Shell shell = control.getShell();
        if (shell == null || shell.isDisposed())
            return;
        shell.layout(true, true);
        Point current = shell.getSize();
        int growBy = control.getBounds().height + 12;
        Global.tempLog(TEMP_LOG_TOPIC, "relayoutFromShell: current=" + current + ", growBy=" + growBy //$NON-NLS-1$
            + ", checkboxBounds=" + control.getBounds()); //$NON-NLS-1$
        shell.setSize(current.x, current.y + growBy);
        shell.layout(true, true);
    }

    private static void wrapFinishButton(Shell shell, Button finishButton, Button ourCheckbox)
    {
        Listener[] original = finishButton.getListeners(SWT.Selection);
        for (Listener l : original)
            finishButton.removeListener(SWT.Selection, l);

        finishButton.addListener(SWT.Selection, event ->
        {
            boolean shouldFix = false;
            String targetDir = null;
            try
            {
                // Дубли Configuration.mdo — всегда до штатной выгрузки (не от флажка Form.xml).
                ConfigurationMdoFix.fixBeforeUnload(shell);
                shouldFix = ourCheckbox != null && !ourCheckbox.isDisposed() && ourCheckbox.getSelection();
                Global.tempLog(TEMP_LOG_TOPIC, "finish clicked, shouldFix=" + shouldFix); //$NON-NLS-1$
                if (shouldFix)
                    targetDir = findTargetDirectoryPath(shell);
            }
            catch (Exception e)
            {
                Global.tempLog(TEMP_LOG_TOPIC, "pre-finish handling failed: " + e); //$NON-NLS-1$
            }

            // Оригинальные слушатели (реальный экспорт) должны отработать в любом
            // случае, даже если наша логика выше или патч ниже упадёт с исключением.
            for (Listener l : original)
                l.handleEvent(event);

            try
            {
                if (shouldFix && targetDir != null && !targetDir.isEmpty())
                    patchExportedDirectory(targetDir);
                else if (shouldFix)
                    Global.tempLog(TEMP_LOG_TOPIC, "targetDir not resolved, skip patching"); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                Global.tempLog(TEMP_LOG_TOPIC, "post-finish patching failed: " + e); //$NON-NLS-1$
            }
        });
    }

    /**
     * Ищет текстовое поле с путём каталога экспорта: все контролы диалога лежат
     * в одном плоском {@code Composite} (не по строкам), поэтому ищем
     * {@code Text}/{@code Combo} с той же координатой Y (строка), что и радиокнопка
     * «...каталог» — первый попавшийся {@code Combo} в родителе оказался полем
     * проекта, а не каталога (см. лог {@code targetDir is not a directory}).
     */
    private static String findTargetDirectoryPath(Shell shell)
    {
        Button dirRadio = findButtonContaining(shell, RADIO_DIR_SNIPPET);
        if (dirRadio == null)
            return null;
        Composite parent = dirRadio.getParent();
        if (parent == null)
            return null;
        int radioY = dirRadio.getBounds().y;
        Global.tempLog(TEMP_LOG_TOPIC, "findTargetDirectoryPath: radioY=" + radioY //$NON-NLS-1$
            + ", allControls=" + collectAllControls(parent)); //$NON-NLS-1$
        for (Control c : parent.getChildren())
        {
            if (c == dirRadio || Math.abs(c.getBounds().y - radioY) > 4)
                continue;
            if (c instanceof Combo)
                return ((Combo) c).getText();
            if (c instanceof Text)
                return ((Text) c).getText();
        }
        return null;
    }

    private static void patchExportedDirectory(String targetDir)
    {
        Path root = Path.of(targetDir);
        if (!Files.isDirectory(root))
        {
            Global.tempLog(TEMP_LOG_TOPIC, "targetDir is not a directory: " + targetDir); //$NON-NLS-1$
            return;
        }
        // [0] найдено Form.xml, [1] реально исправлено, [2] ошибок
        int[] counters = { 0, 0, 0 };
        try
        {
            Files.walk(root)
                .filter(p -> FORM_XML_NAME.equalsIgnoreCase(p.getFileName().toString()))
                .forEach(p ->
                {
                    counters[0]++;
                    try
                    {
                        if (FormXmlPatcher.patch(p))
                            counters[1]++;
                    }
                    catch (Exception e)
                    {
                        counters[2]++;
                        Global.tempLog(TEMP_LOG_TOPIC, "patch failed for " + p + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                });
        }
        catch (IOException e)
        {
            Global.tempLog(TEMP_LOG_TOPIC, "walk failed for " + root + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        Global.tempLog(TEMP_LOG_TOPIC,
            "done, formXmlFound=" + counters[0] + ", fixed=" + counters[1] + ", errors=" + counters[2]); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        FormXmlPatcher.showResultToast(counters[1], counters[2]);
    }

    private static String stripMnemonic(String text)
    {
        return text == null ? null : text.replace("&", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static List<String> collectAllControls(Control root)
    {
        List<String> result = new ArrayList<>();
        collectAllControls(root, result);
        return result;
    }

    private static void collectAllControls(Control root, List<String> out)
    {
        String bounds = root.getBounds().toString();
        if (root instanceof Button)
        {
            Button b = (Button) root;
            out.add("Button" + bounds + "[style=" + b.getStyle() + "] '" + b.getText() + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        else if (root instanceof Combo)
        {
            out.add("Combo" + bounds + " '" + ((Combo) root).getText() + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        else if (root instanceof Text)
        {
            out.add("Text" + bounds + " '" + ((Text) root).getText() + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        else if (root instanceof org.eclipse.swt.widgets.Label)
        {
            out.add("Label" + bounds + " '" + ((org.eclipse.swt.widgets.Label) root).getText() + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        else if (root instanceof Composite && !(root instanceof Shell))
        {
            out.add("Composite" + bounds + " layout=" //$NON-NLS-1$ //$NON-NLS-2$
                + (((Composite) root).getLayout() == null ? "null" : ((Composite) root).getLayout().getClass().getSimpleName())); //$NON-NLS-1$
        }
        if (root instanceof Composite)
        {
            for (Control child : ((Composite) root).getChildren())
                collectAllControls(child, out);
        }
    }

    private static Button findButtonContaining(Control root, String snippet)
    {
        if (root instanceof Button)
        {
            Button b = (Button) root;
            String text = b.getText();
            if (text != null && text.contains(snippet))
                return b;
        }
        if (root instanceof Composite)
        {
            for (Control child : ((Composite) root).getChildren())
            {
                Button found = findButtonContaining(child, snippet);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static Button findButtonByText(Control root, String text)
    {
        if (root instanceof Button)
        {
            Button b = (Button) root;
            if ((b.getStyle() & SWT.PUSH) != 0 && text.equals(stripMnemonic(b.getText())))
                return b;
        }
        if (root instanceof Composite)
        {
            for (Control child : ((Composite) root).getChildren())
            {
                Button found = findButtonByText(child, text);
                if (found != null)
                    return found;
            }
        }
        return null;
    }
}
