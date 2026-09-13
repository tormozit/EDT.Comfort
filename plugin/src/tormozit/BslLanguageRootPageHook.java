package tormozit;

import java.util.WeakHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

/**
 * Страница свойств проекта «Встроенный язык»
 * ({@code com._1c.g5.v8.dt.bsl.ui.editor.BslLanguageRootPreferencePage}).
 *
 * <p><b>Флажок «Перезаписывать типы документирующим комментарием».</b> Пока в параметрах
 * проекта («Комфорт») установлено «Объединять рассчитанный тип с документирующим», плагин
 * держит этот штатный флажок включённым: объединение строится поверх него. Менять его при этом
 * бессмысленно — значение вернётся, — поэтому флажок делается недоступным, а причина выводится
 * меткой под ним. Подсказка при наведении тут не годится: недоступный виджет в Windows мышиных
 * событий не получает. Состояние пересчитывается при каждом заходе на страницу, иначе после
 * снятия нашей пометки флажок так и остался бы серым.
 *
 * <p>Флажок у страницы не отдельным полем, а создаётся внутри {@code addBslOptions} и
 * связывается через databinding, поэтому ищется обходом дерева виджетов по надписи. Надпись
 * берётся из класса сообщений самой EDT, а не задаётся строкой, — иначе при смене языка
 * интерфейса поиск перестал бы находить флажок.
 */
public final class BslLanguageRootPageHook
    implements IStartup
{
    private static final String PAGE_CLASS_NAME =
        "com._1c.g5.v8.dt.bsl.ui.editor.BslLanguageRootPreferencePage"; //$NON-NLS-1$

    private static final String MESSAGES_CLASS_NAME =
        "com._1c.g5.v8.dt.bsl.ui.editor.Messages"; //$NON-NLS-1$

    private static final String LABEL_FIELD =
        "BslLanguageRootPreferencePage_Replace_types_by_documentation_comment"; //$NON-NLS-1$

    private static final String PATCHED_KEY = "tormozit.bslLanguageRootPagePatched"; //$NON-NLS-1$

    private static final String REASON_FORCED =
        "Удерживается включённым, пока в параметрах «Комфорт» проекта установлено «Объединять"
            + " рассчитанный тип с документирующим»: объединение строится поверх этого режима."
            + " Снимите ту пометку, и флажок снова станет доступен.";

    private static final int MAX_ATTEMPTS = 30;

    private static final int RETRY_MS = 100;

    private static final WeakHashMap<Shell, Boolean> pendingWiring = new WeakHashMap<>();

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

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            PreferenceDialog dialog = findPreferenceDialog(shell);
            if (dialog == null)
                return;
            scheduleWireOnce(display, shell, dialog);
        };

        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
        trace("хук установлен"); //$NON-NLS-1$
    }

    /** Окно свойств проекта — тоже {@link PreferenceDialog}, лежит в {@code getData()} оболочки. */
    private static PreferenceDialog findPreferenceDialog(Shell shell)
    {
        Shell current = shell;
        while (current != null && !current.isDisposed())
        {
            if (current.getData() instanceof PreferenceDialog dialog)
                return dialog;
            current = current.getParent() instanceof Shell parent ? parent : null;
        }
        return null;
    }

    private static void scheduleWireOnce(Display display, Shell shell, PreferenceDialog dialog)
    {
        synchronized (pendingWiring)
        {
            if (Boolean.TRUE.equals(pendingWiring.get(shell)))
                return;
            pendingWiring.put(shell, Boolean.TRUE);
        }

        dialog.addPageChangedListener(event -> tryPatchSelected(dialog.getSelectedPage()));
        scheduleRetry(display, shell, dialog, 0);
    }

    /**
     * Страница создаётся не мгновенно: при открытии окна свойств выбранная страница может ещё
     * не иметь виджетов, поэтому попытка повторяется, пока флажок не найден.
     */
    private static void scheduleRetry(Display display, Shell shell, PreferenceDialog dialog,
        int attempt)
    {
        if (shell.isDisposed())
            return;
        if (tryPatchSelected(dialog.getSelectedPage()) || attempt >= MAX_ATTEMPTS)
            return;
        display.timerExec(RETRY_MS, () -> scheduleRetry(display, shell, dialog, attempt + 1));
    }

    /** @return {@code true}, если страница не наша, уже обработана или обработана сейчас. */
    private static boolean tryPatchSelected(Object selected)
    {
        if (!(selected instanceof IPreferencePage page))
        {
            trace("страница не IPreferencePage: " //$NON-NLS-1$
                + (selected == null ? "null" : selected.getClass().getName())); //$NON-NLS-1$
            return true;
        }
        if (!PAGE_CLASS_NAME.equals(page.getClass().getName()))
        {
            trace("другая страница: " + page.getClass().getName()); //$NON-NLS-1$
            return true;
        }

        Control control = page.getControl();
        if (!(control instanceof Composite composite) || composite.isDisposed())
        {
            trace("виджеты страницы ещё не созданы"); //$NON-NLS-1$
            return false;
        }

        String label = stockLabel(page.getClass().getClassLoader());
        if (label == null || label.isBlank())
        {
            StringBuilder found = new StringBuilder();
            collectChecks(composite, found);
            trace("надпись флажка не получена, пометки на странице: " + found); //$NON-NLS-1$
            return true;
        }

        Button check = findCheck(composite, label);
        if (check == null)
        {
            trace("флажок не найден по надписи «" + label + "»"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        Object project = Global.invoke(page, "getProject"); //$NON-NLS-1$
        boolean forced = project instanceof IProject iProject
            && BslDocCommentComputedTypes.isMergeEnabled(iProject);
        if (Boolean.valueOf(forced).equals(check.getData(PATCHED_KEY)))
            return true;
        check.setData(PATCHED_KEY, Boolean.valueOf(forced));
        trace("флажок найден, проект=" //$NON-NLS-1$
            + (project instanceof IProject iProject ? iProject.getName() : String.valueOf(project))
            + " нашФлажок=" + forced); //$NON-NLS-1$

        // Состояние пересчитывается при каждом заходе на страницу: нашу пометку могли снять,
        // и тогда штатный флажок обязан снова стать доступным, а пояснение — исчезнуть.
        check.setEnabled(!forced);
        if (forced)
            showReason(check);
        else
            hideReason(check);
        return true;
    }

    /**
     * Причина недоступности — видимой меткой под флажком, а не подсказкой при наведении:
     * недоступный виджет в Windows мышиных событий не получает, и подсказка у него не
     * показывается вовсе. Пользователь видел бы серый флажок без объяснения.
     */
    private static void showReason(Button check)
    {
        Composite parent = check.getParent();
        if (parent == null || parent.isDisposed())
            return;
        if (findReason(parent) != null)
            return;
        Label reason = new Label(parent, SWT.WRAP);
        reason.setText(REASON_FORCED);
        reason.setForeground(
            ThemeAwareColors.effectiveSystemColor(parent.getDisplay(), SWT.COLOR_DARK_GRAY));
        reason.moveBelow(check);
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        gd.widthHint = 420;
        gd.horizontalIndent = 16;
        if (parent.getLayout() instanceof GridLayout grid && grid.numColumns > 1)
            gd.horizontalSpan = grid.numColumns;
        reason.setLayoutData(gd);
        // Раскладку пересчитываем от корня страницы, а не от родителя метки: layout() меняет
        // расположение детей, но не размер самого контейнера, и добавленная строка осталась бы
        // за пределами группы — метка есть, но её не видно.
        pageRoot(parent).layout(true, true);
        growShell(check.getShell());
    }

    /** Пометку сняли — пояснение убираем вместе с недоступностью. */
    private static void hideReason(Button check)
    {
        Composite parent = check.getParent();
        if (parent == null || parent.isDisposed())
            return;
        Label reason = findReason(parent);
        if (reason == null)
            return;
        reason.dispose();
        Composite root = pageRoot(parent);
        root.layout(true, true);
    }

    private static Label findReason(Composite parent)
    {
        for (Control child : parent.getChildren())
        {
            if (child instanceof Label label && !label.isDisposed()
                && REASON_FORCED.equals(label.getText()))
                return label;
        }
        return null;
    }

    private static Composite pageRoot(Composite from)
    {
        Composite root = from;
        while (root.getParent() != null && !(root.getParent() instanceof Shell))
            root = root.getParent();
        return root;
    }

    /**
     * Окно свойств считает свой размер один раз при открытии, поэтому появившаяся позже строка
     * в него не влезает и обрезается. Высоту наращиваем, ширину и уменьшение не трогаем, чтобы
     * не ломать ручной размер окна.
     */
    private static void growShell(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;
        Point size = shell.getSize();
        Point wanted = shell.computeSize(size.x, SWT.DEFAULT, true);
        if (wanted.y > size.y)
            shell.setSize(size.x, wanted.y);
    }

    /** Временная диагностика (issue 509): какие пометки вообще есть на странице. */
    private static void collectChecks(Composite parent, StringBuilder out)
    {
        for (Control child : parent.getChildren())
        {
            if (child instanceof Button button && (button.getStyle() & SWT.CHECK) != 0)
                out.append('«').append(button.getText()).append("» "); //$NON-NLS-1$
            if (child instanceof Composite nested)
                collectChecks(nested, out);
        }
    }

    /** Временная диагностика (issue 509): снять после подтверждения. */
    private static void trace(String text)
    {
        Global.tempLog("issue509", "страница ВстроенныйЯзык: " + text); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Надпись флажка берём у самой EDT: при другом языке интерфейса она другая. */
    private static String stockLabel(ClassLoader loader)
    {
        try
        {
            // Поле открытое, а сам класс сообщений — пакетный, поэтому без setAccessible
            // получаем IllegalAccessException.
            java.lang.reflect.Field field =
                Class.forName(MESSAGES_CLASS_NAME, true, loader).getField(LABEL_FIELD);
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof String text ? text : null;
        }
        catch (Throwable t)
        {
            trace("надпись: " + t); //$NON-NLS-1$
            return null;
        }
    }

    private static Button findCheck(Composite parent, String label)
    {
        for (Control child : parent.getChildren())
        {
            if (child instanceof Button button && (button.getStyle() & SWT.CHECK) != 0
                && label.equals(button.getText()))
                return button;
            if (child instanceof Composite nested)
            {
                Button found = findCheck(nested, label);
                if (found != null)
                    return found;
            }
        }
        return null;
    }
}
