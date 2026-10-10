package tormozit;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.IPreferencePageContainer;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.preferences.IWorkbenchPreferenceContainer;

/**
 * Страница «Общие → Внешний вид → Оформление меток»
 * ({@code org.eclipse.ui.internal.dialogs.DecoratorsPreferencePage}, пакет internal —
 * доступ только рефлексией): над списком декораторов добавляется поле фильтра.
 *
 * <p><b>Почему состояние пометок хранится отдельно.</b> Штатный {@code performOk} опрашивает
 * {@code CheckboxTableViewer.getChecked} по <i>всем</i> декораторам, а для скрытого фильтром
 * элемента виджета нет и ответ всегда «не помечен» — после «Применить» все скрытые декораторы
 * оказались бы выключены. Поэтому пометки запоминаются в {@link Session#states}, а перед
 * нажатием «Применить» / «Восстановить значения по умолчанию» / кнопки по умолчанию окна фильтр
 * на время снимается (перехват {@code SWT.Selection} на уровне {@link Display}, до штатного
 * обработчика) и тут же возвращается.
 *
 * <p>Из страницы «Комфорт» сюда ведёт ссылка «Декораторы» ({@link #open}): страница открывается
 * сразу с фильтром «Комфорт».
 */
public final class DecoratorsPageHook
    implements IStartup
{
    private static final String PAGE_CLASS_NAME = "org.eclipse.ui.internal.dialogs.DecoratorsPreferencePage"; //$NON-NLS-1$

    /** Идентификатор страницы (объявлена в {@code org.eclipse.ui.ide/plugin.xml}). */
    static final String PAGE_ID = "org.eclipse.ui.preferencePages.Decorators"; //$NON-NLS-1$

    private static final String VIEWER_FIELD = "checkboxViewer"; //$NON-NLS-1$

    private static final String SESSION_KEY = "tormozit.decoratorsPageSession"; //$NON-NLS-1$

    private static final int MAX_ATTEMPTS = 30;

    private static final int RETRY_MS = 100;

    private static final WeakHashMap<Shell, Boolean> pendingWiring = new WeakHashMap<>();

    /** Фильтр, который нужно поставить, как только страница откроется (ссылка из «Комфорт»). */
    private static volatile String pendingFilter;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> install(display));
    }

    /** Открывает страницу «Оформление меток» с заданным фильтром. */
    static void open(IPreferencePageContainer container, String filter)
    {
        if (!(container instanceof IWorkbenchPreferenceContainer wb))
            return;
        pendingFilter = filter;
        wb.openPage(PAGE_ID, null);
        Display display = Display.getDefault();
        if (container instanceof PreferenceDialog dialog && display != null && !display.isDisposed())
            display.asyncExec(() -> applyPendingWhenReady(display, dialog, 0));
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
    }

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
        dialog.addPageChangedListener(event -> tryPatch(dialog.getSelectedPage()));
        scheduleRetry(display, shell, dialog, 0);
    }

    /** Страница создаётся не мгновенно — попытка повторяется, пока виджеты не готовы. */
    private static void scheduleRetry(Display display, Shell shell, PreferenceDialog dialog, int attempt)
    {
        if (shell.isDisposed())
            return;
        if (tryPatch(dialog.getSelectedPage()) || attempt >= MAX_ATTEMPTS)
            return;
        display.timerExec(RETRY_MS, () -> scheduleRetry(display, shell, dialog, attempt + 1));
    }

    private static void applyPendingWhenReady(Display display, PreferenceDialog dialog, int attempt)
    {
        Shell shell = dialog.getShell();
        if (shell == null || shell.isDisposed())
        {
            pendingFilter = null;
            return;
        }
        if (tryPatch(dialog.getSelectedPage()) && pendingFilter == null)
            return;
        if (attempt >= MAX_ATTEMPTS)
        {
            pendingFilter = null;
            return;
        }
        display.timerExec(RETRY_MS, () -> applyPendingWhenReady(display, dialog, attempt + 1));
    }

    /** @return {@code true}, если страница не наша или уже пропатчена; {@code false} — повторить позже. */
    private static boolean tryPatch(Object selected)
    {
        if (!(selected instanceof IPreferencePage page) || !PAGE_CLASS_NAME.equals(page.getClass().getName()))
            return true;
        if (!(Global.getField(page, VIEWER_FIELD) instanceof CheckboxTableViewer viewer))
            return false;
        Table table = viewer.getTable();
        if (table == null || table.isDisposed())
            return false;

        Session session = (Session) table.getData(SESSION_KEY);
        if (session == null)
        {
            session = Session.create(page, viewer);
            if (session == null)
                return false;
            table.setData(SESSION_KEY, session);
        }
        String filter = pendingFilter;
        if (filter != null)
        {
            pendingFilter = null;
            session.box.setText(filter);
            session.apply(filter);
        }
        return true;
    }

    /** Фильтр и запомненные пометки одной открытой страницы. */
    private static final class Session
    {
        final CheckboxTableViewer viewer;
        final Object[] all;
        final Map<Object, Boolean> states = new HashMap<>();
        FilterInputBox box;
        SmartMatcher matcher;

        private Session(CheckboxTableViewer viewer, Object[] all)
        {
            this.viewer = viewer;
            this.all = all;
        }

        static Session create(IPreferencePage page, CheckboxTableViewer viewer)
        {
            if (!(viewer.getInput() instanceof Object[] elements)
                || !(viewer.getLabelProvider() instanceof ILabelProvider))
                return null;
            Table table = viewer.getTable();
            Composite parent = table.getParent();
            Control pageControl = page.getControl();
            if (pageControl == null)
                return null;

            Session session = new Session(viewer, elements);
            session.box = FilterInputBox.forDecorators(parent, () -> session.onSearch());
            session.box.widget().moveAbove(table);
            viewer.addFilter(new ViewerFilter()
            {
                @Override
                public boolean select(Viewer v, Object parentElement, Object element)
                {
                    return session.matcher == null || session.matcher.matches(session.labelOf(element));
                }
            });
            parent.layout(true, true);
            CopyCommandSupport.wireCopyOverride(table);
            session.installButtonGuard(table, pageControl);
            return session;
        }

        String labelOf(Object element)
        {
            String text = ((ILabelProvider) viewer.getLabelProvider()).getText(element);
            return text != null ? text : ""; //$NON-NLS-1$
        }

        void onSearch()
        {
            Display display = viewer.getTable().getDisplay();
            Runnable task = () -> {
                if (!box.isDisposed() && !viewer.getTable().isDisposed())
                    apply(box.getText());
            };
            if (Display.getCurrent() != null)
                task.run();
            else
                display.asyncExec(task);
        }

        boolean isVisible(Object element)
        {
            return viewer.testFindItem(element) != null;
        }

        /** Запоминает пометки видимых элементов — у скрытых виджета нет, их состояние уже в карте. */
        void snapshot()
        {
            for (Object element : all)
                if (isVisible(element))
                    states.put(element, Boolean.valueOf(viewer.getChecked(element)));
        }

        void restore()
        {
            for (Object element : all)
                if (isVisible(element))
                    viewer.setChecked(element, states.getOrDefault(element, Boolean.FALSE).booleanValue());
        }

        void apply(String text)
        {
            if (viewer.getTable().isDisposed())
                return;
            snapshot();
            Object current = viewer.getStructuredSelection().getFirstElement();
            matcher = text == null || text.isBlank() ? null : new SmartMatcher(text);
            viewer.refresh();
            restore();
            if (current != null && isVisible(current))
                viewer.setSelection(new StructuredSelection(current), true);
        }

        /**
         * Штатные «Применить», «Восстановить значения по умолчанию» и кнопка по умолчанию окна
         * ({@code performOk} для всех страниц) читают пометки всех декораторов — на время их
         * обработки фильтр снимается, потом возвращается.
         */
        void installButtonGuard(Table table, Control pageControl)
        {
            Display display = table.getDisplay();
            Listener guard = event ->
            {
                if (matcher == null || table.isDisposed() || !(event.widget instanceof Button button))
                    return;
                Shell shell = button.getShell();
                if (shell != table.getShell())
                    return;
                boolean defaultButton = shell.getDefaultButton() == button;
                boolean pageButton = (button.getStyle() & SWT.PUSH) != 0 && isInside(button, pageControl);
                if (!defaultButton && !pageButton)
                    return;
                snapshot();
                matcher = null;
                viewer.refresh();
                restore();
                display.asyncExec(() -> {
                    if (!table.isDisposed() && !box.isDisposed())
                        apply(box.getText());
                });
            };
            display.addFilter(SWT.Selection, guard);
            table.addListener(SWT.Dispose, e -> display.removeFilter(SWT.Selection, guard));
        }

        private static boolean isInside(Control control, Control ancestor)
        {
            for (Control c = control; c != null; c = c.getParent())
                if (c == ancestor)
                    return true;
            return false;
        }
    }
}
