package tormozit;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

/**
 * Патч диалога EDT «Обновление конфигурации в приложениях»: добавляет флажок,
 * который после загрузки конфигурации в приложение исправляет известный баг
 * конвертации форм в формат конфигуратора 8.5 —
 * https://github.com/1C-Company/1c-edt-issues/issues/2157 (лишний ButtonImportance=Main
 * у кнопок).
 * <p>Промежуточные XML-файлы дампа конфигурации EDT пишет во временный каталог
 * {@code %TEMP%\1cedt\ssh-<port>\...} перед передачей спавненному процессу 1cv8.exe
 * (режим DESIGNER) и удаляет их сразу после использования — патч делается через
 * {@link WatchService} на этот каталог, активный только на время операции.
 */
public final class DeployConfigurationFixHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.deployConfigFixPatched"; //$NON-NLS-1$
    private static final String DIALOG_TITLE = "Обновление конфигурации в приложениях"; //$NON-NLS-1$
    private static final String BTN_FULL_LOAD_SNIPPET = "Загрузить конфигурацию полностью"; //$NON-NLS-1$
    private static final String BTN_FINISH = "Готово"; //$NON-NLS-1$
    private static final String CHECKBOX_TEXT = "Исправлять проблемы после выгрузки в формат 8.5"; //$NON-NLS-1$
    private static final String CHECKBOX_TOOLTIP =
        "Убирает известный баг конвертации форм в формат конфигуратора 8.5: лишний ButtonImportance=Main " //$NON-NLS-1$
            + "у кнопок (issue 1C-Company/1c-edt-issues#2157)."; //$NON-NLS-1$
    private static final String CHECKBOX_KEY = "tormozit.deployConfigFixCheckbox"; //$NON-NLS-1$

private static final String TEMP_ROOT_NAME = "1cedt"; //$NON-NLS-1$
private static final String FORM_XML_NAME = "Form.xml"; //$NON-NLS-1$

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
            boolean isDeployShell = isDeployDialogShell(shell);
            if (!isDeployShell)
                return;
            schedulePatchAttempt(display, shell, 0);
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static boolean isDeployDialogShell(Shell shell)
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

            Button fullLoadCheckbox = findButtonContaining(shell, BTN_FULL_LOAD_SNIPPET);
            Button finishButton = findButtonByText(shell, BTN_FINISH);
            if (fullLoadCheckbox == null || finishButton == null)
            {
                if (attempt >= 20)
                {
                }
                else
                {
                    schedulePatchAttempt(display, shell, attempt + 1);
                }
                return;
            }

            shell.setData(PATCHED_KEY, Boolean.TRUE);
            Button ourCheckbox = addFixCheckbox(fullLoadCheckbox);
            wrapFinishButton(finishButton, ourCheckbox);
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
        checkbox.moveBelow(anchor);
        relayoutFromShell(checkbox);
        anchor.setData(CHECKBOX_KEY, checkbox);
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
        shell.setSize(current.x, current.y + growBy);
        shell.layout(true, true);
    }

    private static void wrapFinishButton(Button finishButton, Button ourCheckbox)
    {
        Listener[] original = finishButton.getListeners(SWT.Selection);
        for (Listener l : original)
            finishButton.removeListener(SWT.Selection, l);

        finishButton.addListener(SWT.Selection, event ->
        {
            FormXmlFixWatcher watcher = null;
            try
            {
                // Дубли Configuration.mdo — всегда до штатной выгрузки (не от флажка Form.xml).
                ConfigurationMdoFix.fixBeforeUnload(finishButton.getShell());
                boolean shouldFix = ourCheckbox != null && !ourCheckbox.isDisposed() && ourCheckbox.getSelection();
                if (shouldFix)
                    watcher = FormXmlFixWatcher.startIfPossible();
            }
            catch (Exception e)
            {
            }
            final FormXmlFixWatcher startedWatcher = watcher;

            // Оригинальные слушатели (реальная загрузка конфигурации) должны
            // отработать в любом случае, даже если наша логика выше упала.
            for (Listener l : original)
                l.handleEvent(event);

            try
            {
                if (startedWatcher != null)
                    stopWatcherAfterDeployJob(startedWatcher);
            }
            catch (Exception e)
            {
            }
        });
    }

    private static void stopWatcherAfterDeployJob(FormXmlFixWatcher watcher)
    {
        AtomicBoolean stopped = new AtomicBoolean(false);
        IJobChangeListener listener = new IJobChangeListener()
        {
            @Override public void aboutToRun(IJobChangeEvent event) {}
            @Override public void awake(IJobChangeEvent event) {}
            @Override public void sleeping(IJobChangeEvent event) {}
            @Override public void running(IJobChangeEvent event) {}
            @Override public void scheduled(IJobChangeEvent event) {}

            @Override
            public void done(IJobChangeEvent event)
            {
                if (!isDeployJob(event.getJob()))
                    return;
                if (!stopped.compareAndSet(false, true))
                    return;
                Job.getJobManager().removeJobChangeListener(this);
                stopWatcherAndNotify(watcher);
            }
        };
        Job.getJobManager().addJobChangeListener(listener);

        // Запасной останов, если job-событие не пришло (например, синхронное выполнение).
        Display.getDefault().timerExec(10 * 60_000, () ->
        {
            if (stopped.compareAndSet(false, true))
            {
                Job.getJobManager().removeJobChangeListener(listener);
                stopWatcherAndNotify(watcher);
            }
        });
    }

    private static void stopWatcherAndNotify(FormXmlFixWatcher watcher)
    {
        watcher.stop();
        FormXmlPatcher.showResultToast(watcher.patchedCount(), 0);
    }

    private static boolean isDeployJob(Job job)
    {
        if (job == null)
            return false;
        String className = job.getClass().getName();
        if (className.contains("DeployWithProgressOperation") //$NON-NLS-1$
            || className.contains("UpdateApplications")) //$NON-NLS-1$
            return true;
        String name = job.getName();
        return name != null && name.contains("Обновление конфигурации"); //$NON-NLS-1$
    }

    private static String stripMnemonic(String text)
    {
        return text == null ? null : text.replace("&", ""); //$NON-NLS-1$ //$NON-NLS-2$
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

    /**
     * Следит за {@code %TEMP%\1cedt\} и патчит {@code Form.xml}, как только они там
     * появляются, до того как их успеет прочитать/удалить процесс загрузки. Гонка
     * теоретически возможна (WatchService не гарантирует мгновенную реакцию), но
     * файлы по наблюдениям живут секунды, не миллисекунды — согласовано с пользователем
     * как приемлемый компромисс (см. план чата про Guice/DI-архитектуру EDT).
     */
    private static final class FormXmlFixWatcher
    {
        private final WatchService watchService;
        private final AtomicInteger patchedCount = new AtomicInteger();
        private Thread thread;
        private volatile boolean stopped;

        private FormXmlFixWatcher(WatchService watchService)
        {
            this.watchService = watchService;
        }

        static FormXmlFixWatcher startIfPossible()
        {
            Path root = Path.of(System.getProperty("java.io.tmpdir"), TEMP_ROOT_NAME); //$NON-NLS-1$
            try
            {
                Files.createDirectories(root);
                WatchService ws = FileSystems.getDefault().newWatchService();
                registerRecursively(ws, root);
                FormXmlFixWatcher watcher = new FormXmlFixWatcher(ws);
                Thread t = new Thread(() -> watcher.loop(ws, root), "tormozit-deploy-xml-fix-watcher"); //$NON-NLS-1$
                t.setDaemon(true);
                watcher.thread = t;
                t.start();
                return watcher;
            }
            catch (IOException e)
            {
                return null;
            }
        }

        private static void registerRecursively(WatchService ws, Path dir) throws IOException
        {
            dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE);
            try (var stream = Files.newDirectoryStream(dir))
            {
                for (Path child : stream)
                {
                    if (Files.isDirectory(child))
                        registerRecursively(ws, child);
                }
            }
            catch (IOException ignored)
            {
                // каталог мог исчезнуть между листингом и обходом — не критично
            }
        }

        private void loop(WatchService ws, Path root)
        {
            while (!stopped)
            {
                WatchKey key;
                try
                {
                    key = ws.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (key == null)
                    continue;

                Path dir = (Path) key.watchable();
                for (WatchEvent<?> ev : key.pollEvents())
                {
                    if (ev.kind() != StandardWatchEventKinds.ENTRY_CREATE)
                        continue;
                    Path child = dir.resolve((Path) ev.context());
                    handleCreated(ws, child);
                }
                if (!key.reset())
                {
                    // каталог удалён/недоступен — не фатально, остальные подкаталоги следим дальше
                }
            }
            try { ws.close(); } catch (IOException ignored) {}
        }

        private void handleCreated(WatchService ws, Path path)
        {
            try
            {
                if (Files.isDirectory(path))
                {
                    registerRecursively(ws, path);
                    return;
                }
                if (!FORM_XML_NAME.equalsIgnoreCase(path.getFileName().toString()))
                    return;
                waitUntilStableThenPatch(path);
            }
            catch (IOException e)
            {
            }
        }

        private void waitUntilStableThenPatch(Path path)
        {
            try
            {
                long lastSize = -1;
                for (int i = 0; i < 20 && !stopped; i++)
                {
                    if (!Files.exists(path))
                        return;
                    long size = Files.size(path);
                    if (size > 0 && size == lastSize)
                        break;
                    lastSize = size;
                    Thread.sleep(25);
                }
                if (Files.exists(path) && FormXmlPatcher.patch(path))
                    patchedCount.incrementAndGet();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            catch (Exception e)
            {
            }
        }

        void stop()
        {
            stopped = true;
            if (thread != null)
                thread.interrupt();
        }

        int patchedCount()
        {
            return patchedCount.get();
        }
    }
}
