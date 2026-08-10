package tormozit;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.FrameworkUtil;

/**
 * Окно с полным описанием ошибки (issue 276). Дерево панелей «Отладка» и «Трассировки стеков»
 * рисует только первую строку описания, поэтому остальное («по причине: …») показываем здесь:
 * текст целиком, с прокруткой и обычным выделением/копированием.
 * <p>
 * Кнопки: «Остановка по ошибке» — штатный диалог EDT с подставленной причиной (первая строка
 * описания); «Ссылки» — переход к объектам метаданных, упомянутым в тексте, как в окне ошибки EDT
 * ({@link ErrorDialogLinksHook}).
 * <p>
 * Окно одно на приложение: повторный вызов обновляет текст и поднимает его наверх.
 */
final class ErrorDescriptionWindow
{
    private static final String TEXT_WIDGET_KEY = "tormozit.comfort.errorDescription.text"; //$NON-NLS-1$

    private static Shell window;
    private static String windowText = ""; //$NON-NLS-1$

    private ErrorDescriptionWindow()
    {
    }

    static void open(Shell parent, String text)
    {
        if (text == null || text.isBlank())
            return;
        windowText = text;
        if (window != null && !window.isDisposed())
        {
            if (window.getData(TEXT_WIDGET_KEY) instanceof Text existing && !existing.isDisposed())
                existing.setText(text);
            window.setActive();
            return;
        }

        Shell shell = new Shell(parent, SWT.SHELL_TRIM);
        shell.setText(Global.withPluginWindowTitle("Описание ошибки")); //$NON-NLS-1$
        shell.setLayout(new GridLayout(1, false));

        Text field = new Text(shell, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        field.setText(text);
        field.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        shell.setData(TEXT_WIDGET_KEY, field);

        createButtonBar(shell);

        shell.addListener(SWT.Traverse, event -> {
            if (event.detail == SWT.TRAVERSE_ESCAPE)
                shell.close();
        });
        // Размеры и положение окна переживают закрытие и перезапуск EDT.
        Rectangle[] lastBounds = { null };
        Listener geometryTracker = event -> lastBounds[0] = shell.getBounds();
        shell.addListener(SWT.Resize, geometryTracker);
        shell.addListener(SWT.Move, geometryTracker);
        shell.addListener(SWT.Dispose, event -> {
            GeometryStore.save(lastBounds[0]);
            window = null;
        });
        shell.setBounds(GeometryStore.load(shell.getDisplay(), parent));

        window = shell;
        shell.open();
        field.setFocus();
    }

    private static void createButtonBar(Shell shell)
    {
        Composite bar = new Composite(shell, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        GridLayout layout = new GridLayout(3, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        bar.setLayout(layout);

        Button stopOnError = new Button(bar, SWT.PUSH);
        stopOnError.setText("Остановка по ошибке"); //$NON-NLS-1$
        stopOnError.setToolTipText("Создать точку останова по ошибке с причиной из первой строки описания"); //$NON-NLS-1$
        stopOnError.addListener(SWT.Selection,
                event -> StacktracesViewInteractionHook.openStopOnErrorForReason(windowText));

        Button links = new Button(bar, SWT.PUSH);
        links.setText("Ссылки"); //$NON-NLS-1$
        links.setToolTipText("Показать ссылки на объекты метаданных из текста ошибки"); //$NON-NLS-1$
        links.addListener(SWT.Selection, event -> ErrorDialogLinksHook.openLinksForText(shell, windowText));

        Button close = new Button(bar, SWT.PUSH);
        close.setText("Закрыть"); //$NON-NLS-1$
        close.addListener(SWT.Selection, event -> shell.close());
        shell.setDefaultButton(close);
    }

    /**
     * Размеры и положение окна в настройках рабочей области ({@link ScopedPreferenceStore} —
     * {@code setValue} без {@code save()} не пережил бы перезапуск EDT).
     */
    private static final class GeometryStore
    {
        private static final String PREF_X = "comfort.errorDescription.window.x"; //$NON-NLS-1$
        private static final String PREF_Y = "comfort.errorDescription.window.y"; //$NON-NLS-1$
        private static final String PREF_WIDTH = "comfort.errorDescription.window.width"; //$NON-NLS-1$
        private static final String PREF_HEIGHT = "comfort.errorDescription.window.height"; //$NON-NLS-1$
        private static final int MIN_WIDTH = 400;
        private static final int MIN_HEIGHT = 250;

        private static ScopedPreferenceStore prefs;

        private GeometryStore()
        {
        }

        static Rectangle load(Display display, Shell parent)
        {
            ScopedPreferenceStore store = prefs();
            if (store != null && store.contains(PREF_WIDTH) && store.contains(PREF_HEIGHT))
            {
                Rectangle stored = new Rectangle(store.getInt(PREF_X), store.getInt(PREF_Y),
                        store.getInt(PREF_WIDTH), store.getInt(PREF_HEIGHT));
                if (stored.width >= MIN_WIDTH && stored.height >= MIN_HEIGHT)
                    return clampToMonitor(display, stored);
            }
            return defaultBounds(display, parent);
        }

        static void save(Rectangle bounds)
        {
            ScopedPreferenceStore store = prefs();
            if (store == null || bounds == null || bounds.width < MIN_WIDTH || bounds.height < MIN_HEIGHT)
                return;
            store.setValue(PREF_X, bounds.x);
            store.setValue(PREF_Y, bounds.y);
            store.setValue(PREF_WIDTH, bounds.width);
            store.setValue(PREF_HEIGHT, bounds.height);
            try
            {
                store.save();
            }
            catch (Exception ignored)
            {
                // настройки необязательны
            }
        }

        private static Rectangle defaultBounds(Display display, Shell parent)
        {
            Rectangle area = parent != null && !parent.isDisposed() ? parent.getBounds()
                    : display.getPrimaryMonitor().getClientArea();
            int width = Math.min(900, Math.max(500, area.width - 200));
            int height = Math.min(500, Math.max(300, area.height - 200));
            return new Rectangle(area.x + (area.width - width) / 2, area.y + (area.height - height) / 2,
                    width, height);
        }

        private static Rectangle clampToMonitor(Display display, Rectangle bounds)
        {
            Monitor monitor = display.getPrimaryMonitor();
            Point center = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
            for (Monitor candidate : display.getMonitors())
            {
                if (candidate.getBounds().contains(center))
                {
                    monitor = candidate;
                    break;
                }
            }
            Rectangle client = monitor.getClientArea();
            int width = Math.min(bounds.width, client.width);
            int height = Math.min(bounds.height, client.height);
            int x = Math.max(client.x, Math.min(bounds.x, client.x + client.width - width));
            int y = Math.max(client.y, Math.min(bounds.y, client.y + client.height - height));
            return new Rectangle(x, y, width, height);
        }

        private static ScopedPreferenceStore prefs()
        {
            if (prefs != null)
                return prefs;
            try
            {
                String pluginId = FrameworkUtil.getBundle(GeometryStore.class).getSymbolicName();
                prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
            }
            catch (Exception ignored)
            {
                return null;
            }
            return prefs;
        }
    }
}
