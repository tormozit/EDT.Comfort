

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

public final class EclipseToastNotification 
{
    // =======================================================================
    // НАСТРОЙКИ АНИМАЦИИ И ВНЕШНЕГО ВИДА (Легко менять здесь)
    // =======================================================================
    private static final int SLIDE_IN_DURATION_MS = 500;  // Время выплывания снизу вверх
    private static final int FADE_OUT_DURATION_MS = 1500; // Время плавного исчезновения (прозрачность)
    private static final int ANIMATION_STEP_MS    = 40;   // Частота обновления анимации (меньше = плавнее)
    
    private static final int WIDTH   = 380;
    private static final int PADDING = 12;

    private EclipseToastNotification() {}

    public static Shell show(String title, String message, int durationMs) 
    {
        Display display = Display.getDefault();
        final Shell[] shellHolder = new Shell[1];

        display.syncExec(() -> {
            Shell shell = new Shell(display, SWT.NO_TRIM | SWT.ON_TOP);
            shellHolder[0] = shell;
            
            shell.setLayout(new GridLayout(1, false));
            shell.setBackground(display.getSystemColor(SWT.COLOR_INFO_BACKGROUND));

            // Заголовок
            if (title != null && !title.isEmpty()) {
                Label lbl = new Label(shell, SWT.WRAP);
                lbl.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
                lbl.setText(title);
                lbl.setBackground(display.getSystemColor(SWT.COLOR_INFO_BACKGROUND));
                lbl.setForeground(display.getSystemColor(SWT.COLOR_INFO_FOREGROUND));
                FontData[] fd = lbl.getFont().getFontData();
                fd[0].setStyle(SWT.BOLD);
                Font bold = new Font(display, fd);
                lbl.setFont(bold);
                lbl.addDisposeListener(e -> bold.dispose());
            }

            // Сообщение
            if (message != null && !message.isEmpty()) {
                Label lbl = new Label(shell, SWT.WRAP);
                GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
                gd.widthHint = WIDTH - 2 * PADDING;
                lbl.setLayoutData(gd);
                lbl.setText(message);
                lbl.setBackground(display.getSystemColor(SWT.COLOR_INFO_BACKGROUND));
                lbl.setForeground(display.getSystemColor(SWT.COLOR_INFO_FOREGROUND));
            }

            shell.pack();
            Point size = shell.getSize();
            if (size.x < WIDTH) shell.setSize(WIDTH, size.y);
            final Point finalSize = shell.getSize();

            Rectangle screen = display.getPrimaryMonitor().getBounds();
            int targetX = screen.x + screen.width - finalSize.x - 16;
            int targetY = screen.y + screen.height - finalSize.y - 48;

            int startY = targetY + 100;
            shell.setLocation(targetX, startY);
            shell.setAlpha(0);
            shell.setVisible(true);

            // ----------------------------------------------------------------
            // 1. Анимация появления (Рассчитывается из SLIDE_IN_DURATION_MS)
            // ----------------------------------------------------------------
            int slideSteps = Math.max(1, SLIDE_IN_DURATION_MS / ANIMATION_STEP_MS);
            for (int i = 0; i <= slideSteps; i++) {
                final int step = i;
                display.timerExec(step * ANIMATION_STEP_MS, () -> {
                    if (!shell.isDisposed()) {
                        int curY = startY - ((startY - targetY) * step / slideSteps);
                        shell.setLocation(targetX, curY);
                        shell.setAlpha(255 * step / slideSteps);
                    }
                });
            }

            final int[] remainingTime = new int[]{ durationMs };
            final boolean[] isHovered = new boolean[]{ false };
            final boolean[] isFading = new boolean[]{ false };
            final int[] currentAlpha = new int[]{ 255 };

            // ----------------------------------------------------------------
            // 2. Логика наведения мыши (Ховер)
            // ----------------------------------------------------------------
            Listener hoverListener = e -> {
                if (e.type == SWT.MouseEnter) {
                    isHovered[0] = true;
                } else if (e.type == SWT.MouseExit) {
                    Point mouseLoc = display.getCursorLocation();
                    if (!shell.getBounds().contains(mouseLoc)) {
                        isHovered[0] = false;
                    }
                }
            };

            shell.addListener(SWT.MouseEnter, hoverListener);
            shell.addListener(SWT.MouseExit, hoverListener);
            for (Control child : shell.getChildren()) {
                child.addListener(SWT.MouseEnter, hoverListener);
                child.addListener(SWT.MouseExit, hoverListener);
            }

            // ----------------------------------------------------------------
            // 3. Обработка клика (Открытие копируемого окна)
            // ----------------------------------------------------------------
            Listener clickListener = e -> {
                if (!shell.isDisposed()) {
                    shell.dispose();
                    display.asyncExec(() -> openCopyableDialog(title, message));
                }
            };
            shell.addListener(SWT.MouseDown, clickListener);
            for (Control child : shell.getChildren()) {
                child.addListener(SWT.MouseDown, clickListener);
            }

            // ----------------------------------------------------------------
            // 4. Таймер удержания и динамическая анимация затухания
            // ----------------------------------------------------------------
            Runnable[] timerLoop = new Runnable[1];
            timerLoop[0] = new Runnable() {
                @Override
                public void run() {
                    if (shell.isDisposed()) return;

                    // Если мышь наведена — сбрасываем прозрачность и таймер ожидания
                    if (isHovered[0]) {
                        if (currentAlpha[0] < 255 || isFading[0]) {
                            shell.setAlpha(255);
                            currentAlpha[0] = 255;
                            isFading[0] = false;
                        }
                        remainingTime[0] = durationMs; 
                        display.timerExec(100, this);
                        return;
                    }

                    if (remainingTime[0] > 0) {
                        // Обычный режим ожидания перед скрытием
                        remainingTime[0] -= 100;
                        display.timerExec(100, this);
                    } else {
                        // Режим затухания
                        isFading[0] = true;
                        
                        // Автоматический расчет дельты альфы для текущего шага времени
                        int alphaDecrement = (255 * ANIMATION_STEP_MS) / FADE_OUT_DURATION_MS;
                        if (alphaDecrement < 1) alphaDecrement = 1; // Защита от деления на 0

                        if (currentAlpha[0] > 0) {
                            currentAlpha[0] -= alphaDecrement;
                            if (currentAlpha[0] < 0) currentAlpha[0] = 0;
                            
                            shell.setAlpha(currentAlpha[0]);
                            display.timerExec(ANIMATION_STEP_MS, this);
                        } else {
                            shell.dispose();
                        }
                    }
                }
            };
            
            // Запускаем основной цикл контроля времени сразу после окончания выплывания
            display.timerExec(SLIDE_IN_DURATION_MS + 50, timerLoop[0]);
        });

        return shellHolder[0];
    }
    
    public static Shell show(String title, String message) 
    {
        return show(title, message, 4000);
    }
    public static void close(final Shell shell) 
    {
        if (shell == null) return;
        Display display = Display.getDefault();
        if (display.isDisposed()) return;

        if (display.getThread() == Thread.currentThread()) {
            if (!shell.isDisposed()) shell.dispose();
        } else {
            display.asyncExec(() -> {
                if (!shell.isDisposed()) shell.dispose();
            });
        }
    }

    private static void openCopyableDialog(String title, String message) 
    {
        Display display = Display.getDefault();
        Shell dialog = new Shell(display, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
        dialog.setText(title != null && !title.isEmpty() ? title : "Текст уведомления");
        dialog.setLayout(new GridLayout(1, false));

        Text textWidget = new Text(dialog, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        textWidget.setText(message != null ? message : "");
        
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.widthHint = 480;
        gd.heightHint = 220;
        textWidget.setLayoutData(gd);

        Button btnClose = new Button(dialog, SWT.PUSH);
        btnClose.setText("Закрыть");
        btnClose.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        btnClose.addListener(SWT.Selection, e -> dialog.dispose());

        dialog.pack();
        
        Rectangle screen = display.getPrimaryMonitor().getBounds();
        Rectangle dialogBounds = dialog.getBounds();
        dialog.setLocation(screen.x + (screen.width - dialogBounds.width) / 2,
                           screen.y + (screen.height - dialogBounds.height) / 2);
        
        dialog.open();
    }
}