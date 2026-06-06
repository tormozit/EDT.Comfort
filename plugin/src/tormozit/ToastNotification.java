package tormozit;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

public final class ToastNotification
{
    // =======================================================================
    // НАСТРОЙКИ АНИМАЦИИ И ВНЕШНЕГО ВИДА
    // =======================================================================
    private static final int SLIDE_IN_DURATION_MS = 500;
    private static final int FADE_OUT_DURATION_MS = 2000;
    private static final int ANIMATION_STEP_MS    = 30;

    private static final int WIDTH          = 400;
    private static final int PADDING        = 10;
    private static final int GAP_BETWEEN    = 2;  // зазор между тостами
    private static final int EDGE_GAP       = 2;  // отступ от правого края и от таскбара
    private static final int MIN_TOP_MARGIN = 10; // запас от верха экрана до начала нового слоя

    /** Единая дельта размера шрифта относительно системного (pt). 0 = без изменений. */
    private static final int FONT_SIZE_DELTA = 1;

    // =======================================================================
    // РЕЕСТР АКТИВНЫХ ТОСТОВ (читается/пишется только из display-потока)
    // =======================================================================
    private static final List<ToastEntry> activeToasts = new CopyOnWriteArrayList<>();

    private static final class ToastEntry
    {
        final Shell shell;
        final int   y;      // целевая Y верхней границы (конечная позиция после анимации)
        final int   height;

        ToastEntry(Shell s, int y, int h) { shell = s; this.y = y; this.height = h; }
    }

    private ToastNotification() {}

    // =======================================================================
    // ПУБЛИЧНЫЙ API
    // =======================================================================

    public static Shell show(String title, String message)
    {
        return show(title, message, 4000, null, null);
    }

    public static Shell show(String title, String message, int durationMs)
    {
        return show(title, message, durationMs, null, null);
    }
    
    public static Shell show(String title, String message, int durationMs, Runnable action)
    {
        return show(title, message, durationMs, action, null);
    }

    /**
     * Показывает всплывающее уведомление.
     * <p>Тосты стекуются снизу вверх (от верхней границы таскбара).
     * Новый тост выезжает снизу вверх в свою ячейку, не перекрывая таскбар
     * и тосты текущего слоя. При нехватке места начинается новый слой —
     * тосты снова идут от таскбара, перекрывая тосты предыдущего слоя.
     *
     * @param title       заголовок (null = не отображать)
     * @param message     основной текст
     * @param durationMs  время показа в мс до начала затухания
     * @param actionLabel текст гиперссылки «Выполнить» (null = не отображать)
     * @param action      действие при клике на гиперссылку; произвольные параметры
     *                    передаются через замыкание лямбды (null = не отображать)
     */
    public static Shell show(String title, String message, int durationMs, Runnable action, String actionLabel)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) return null;
        Shell[] holder = new Shell[1];
        Global.log(title + ": "+ message);

        display.syncExec(() ->
        {
            Shell shell = new Shell(display, SWT.NO_TRIM | SWT.ON_TOP);
            holder[0] = shell;

            Color bgColor     = display.getSystemColor(SWT.COLOR_INFO_BACKGROUND);
            Color fgColor     = display.getSystemColor(SWT.COLOR_INFO_FOREGROUND);
            Color borderColor = new Color(display, 192, 192, 192);
            shell.addDisposeListener(e -> borderColor.dispose());

            GridLayout layout      = new GridLayout(1, false);
            layout.marginWidth     = PADDING;
            layout.marginHeight    = PADDING;
            layout.verticalSpacing = 0;
            shell.setLayout(layout);
            shell.setBackground(bgColor);

            // --- Клик по фону → копируемый диалог ---
            // Определяем заранее — нужен при создании виджетов
            Listener clickListener = e ->
            {
                if (!shell.isDisposed())
                {
                    shell.dispose();
                    display.asyncExec(() -> openCopyableDialog(title, message));
                }
            };

            // --- Заголовок + кнопка × (всегда одна строка) ---
            Composite header = new Composite(shell, SWT.NONE);
            {
                GridLayout hl    = new GridLayout(2, false);
                hl.marginWidth   = 0;
                hl.marginHeight  = 0;
                hl.verticalSpacing = 0;
                hl.horizontalSpacing = 4;
                header.setLayout(hl);
                header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
                header.setBackground(bgColor);
                header.addListener(SWT.MouseDown, clickListener);

                // Заголовок (левая ячейка — всё доступное пространство)
                if (title != null && !title.isEmpty())
                {
                    Label titleLbl = new Label(header, SWT.WRAP);
                    titleLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                    titleLbl.setText(title);
                    titleLbl.setBackground(bgColor);
                    titleLbl.setForeground(fgColor);
                    titleLbl.setFont(makeBoldFont(titleLbl, display));
                    titleLbl.addListener(SWT.MouseDown, clickListener);
                }
                else
                {
                    // Пустой разделитель, чтобы × остался в правом углу
                    new Label(header, SWT.NONE).setLayoutData(
                        new GridData(SWT.FILL, SWT.CENTER, true, false));
                }

                // Кнопка × (правая ячейка, фиксированная ширина)
                ToolBar toolBar = new ToolBar(header, SWT.FLAT | SWT.RIGHT);
                toolBar.setBackground(shell.getBackground());
                GridData tbData = new GridData(SWT.RIGHT, SWT.TOP, false, false);
                toolBar.setLayoutData(tbData);

                ToolItem closeItem = new ToolItem(toolBar, SWT.PUSH);
                closeItem.setText("✕");
                closeItem.addListener(SWT.Selection, e ->
                { if (!shell.isDisposed()) shell.dispose(); });
            }

            // --- Сообщение ---
            if (message != null && !message.isEmpty())
            {
                Label msgLbl = new Label(shell, SWT.WRAP);
                GridData gd  = new GridData(SWT.FILL, SWT.TOP, true, false);
                gd.widthHint = WIDTH - 2 * PADDING;
                msgLbl.setLayoutData(gd);
                msgLbl.setText(message);
                msgLbl.setBackground(bgColor);
                msgLbl.setForeground(fgColor);
                msgLbl.setFont(makeRegularFont(msgLbl, display));
                msgLbl.addListener(SWT.MouseDown, clickListener);
            }

            // --- Гиперссылка «Выполнить» (опционально) ---
            final Link actionLink;
            if (actionLabel != null && !actionLabel.isEmpty() && action != null)
            {
                actionLink = new Link(shell, SWT.NONE);
                actionLink.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, true, false));
                actionLink.setText("<a>" + actionLabel + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$
                actionLink.setBackground(bgColor);
                actionLink.addListener(SWT.Selection, e ->
                {
                    if (!shell.isDisposed()) shell.dispose();
                    display.asyncExec(action::run);
                });
            }
            else
            {
                actionLink = null;
            }

            // --- Рамка (рисуется поверх содержимого) ---
            shell.addPaintListener(e ->
            {
                Point sz = shell.getSize();
                e.gc.setForeground(borderColor);
                e.gc.setLineWidth(1);
                e.gc.drawRectangle(0, 0, sz.x - 1, sz.y - 1);
            });

            shell.pack();
            Point size = shell.getSize();
            if (size.x < WIDTH) shell.setSize(WIDTH, size.y);
            final Point finalSize = shell.getSize();

            // --- Вычисляем позицию с учётом уже активных тостов ---
            Rectangle ca = display.getPrimaryMonitor().getClientArea();
            int targetX  = ca.x + ca.width  - finalSize.x - EDGE_GAP;
            int targetY  = findSlotTopY(ca, finalSize.y);

            // Регистрируем ДО показа, чтобы следующие тосты учитывали нашу позицию
            ToastEntry entry = new ToastEntry(shell, targetY, finalSize.y);
            activeToasts.add(entry);
            shell.addDisposeListener(e -> activeToasts.remove(entry));

            // Тост выезжает СНИЗУ ВВЕРХ
            int startY = targetY + finalSize.y;
            shell.setLocation(targetX, startY);
            shell.setAlpha(0);
            shell.setVisible(true);

            // --- 1. Анимация появления (снизу вверх) ---
            int slideSteps = Math.max(1, SLIDE_IN_DURATION_MS / ANIMATION_STEP_MS);
            for (int i = 0; i <= slideSteps; i++)
            {
                final int step = i;
                display.timerExec(step * ANIMATION_STEP_MS, () ->
                {
                    if (!shell.isDisposed())
                    {
                        shell.setLocation(targetX,
                            startY + (targetY - startY) * step / slideSteps);
                        shell.setAlpha(255 * step / slideSteps);
                    }
                });
            }

            // --- 2. Ховер ---
            final boolean[] isHovered    = { false };
            final boolean[] isFading     = { false };
            final int[]     currentAlpha = { 255 };
            final int[]     remainingMs  = { durationMs };

            Listener hoverListener = e ->
            {
                if      (e.type == SWT.MouseEnter) { isHovered[0] = true; }
                else if (e.type == SWT.MouseExit
                         && !shell.getBounds().contains(display.getCursorLocation()))
                         { isHovered[0] = false; }
            };
            shell.addListener(SWT.MouseEnter, hoverListener);
            shell.addListener(SWT.MouseExit,  hoverListener);
            for (Control child : shell.getChildren())
            {
                child.addListener(SWT.MouseEnter, hoverListener);
                child.addListener(SWT.MouseExit,  hoverListener);
            }
            if (actionLink != null)
            {
                actionLink.addListener(SWT.MouseEnter, hoverListener);
                actionLink.addListener(SWT.MouseExit,  hoverListener);
            }

            // --- 3. Таймер удержания + затухание ---
            Runnable[] loop = new Runnable[1];
            loop[0] = () ->
            {
                if (shell.isDisposed()) return;

                if (isHovered[0])
                {
                    if (isFading[0] || currentAlpha[0] < 255)
                    {
                        shell.setAlpha(255);
                        currentAlpha[0] = 255;
                        isFading[0]     = false;
                    }
                    remainingMs[0] = durationMs;
                    display.timerExec(100, loop[0]);
                    return;
                }

                if (remainingMs[0] > 0)
                {
                    remainingMs[0] -= 100;
                    display.timerExec(100, loop[0]);
                }
                else
                {
                    isFading[0]     = true;
                    int decrement   = Math.max(1, 255 * ANIMATION_STEP_MS / FADE_OUT_DURATION_MS);
                    currentAlpha[0] = Math.max(0, currentAlpha[0] - decrement);
                    shell.setAlpha(currentAlpha[0]);
                    if (currentAlpha[0] > 0)
                        display.timerExec(ANIMATION_STEP_MS, loop[0]);
                    else
                        shell.dispose();
                }
            };
            display.timerExec(SLIDE_IN_DURATION_MS + 50, loop[0]);
        });

        return holder[0];
    }

    public static void close(Shell shell)
    {
        if (shell == null) return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) return;
        if (display.getThread() == Thread.currentThread())
        {
            if (!shell.isDisposed()) shell.dispose();
        }
        else
        {
            display.asyncExec(() -> { if (!shell.isDisposed()) shell.dispose(); });
        }
    }

    // =======================================================================
    // АЛГОРИТМ РАЗМЕЩЕНИЯ: стек снизу вверх, при переполнении — новый слой
    // =======================================================================

    private static int findSlotTopY(Rectangle clientArea, int toastHeight)
    {
        int clientBottom = clientArea.y + clientArea.height - EDGE_GAP;
        int minTopY      = clientArea.y + MIN_TOP_MARGIN;

        List<int[]> slots = activeToasts.stream()
            .filter(e -> !e.shell.isDisposed())
            .map(e -> new int[]{ e.y, e.y + e.height })
            .sorted(Comparator.<int[], Integer>comparing(s -> s[1]).reversed())
            .collect(Collectors.toList());

        int candidateBottom = clientBottom;

        for (int[] slot : slots)
        {
            if (candidateBottom - toastHeight < slot[1] + GAP_BETWEEN)
                candidateBottom = slot[0] - GAP_BETWEEN;
        }

        int candidateTop = candidateBottom - toastHeight;

        if (candidateTop < minTopY)
            candidateTop = clientBottom - toastHeight;

        return candidateTop;
    }

    // =======================================================================
    // ШРИФТЫ
    // =======================================================================

    private static Font makeBoldFont(Control control, Display display)
    {
        FontData[] fd = control.getFont().getFontData();
        fd[0].setStyle(SWT.BOLD);
        fd[0].setHeight(fd[0].getHeight() + FONT_SIZE_DELTA);
        Font f = new Font(display, fd);
        control.addDisposeListener(e -> f.dispose());
        return f;
    }

    private static Font makeRegularFont(Control control, Display display)
    {
        FontData[] fd = control.getFont().getFontData();
        fd[0].setHeight(fd[0].getHeight() + FONT_SIZE_DELTA);
        Font f = new Font(display, fd);
        control.addDisposeListener(e -> f.dispose());
        return f;
    }

    // =======================================================================
    // ДИАЛОГ ПРОСМОТРА ТЕКСТА УВЕДОМЛЕНИЯ (открывается по клику на тост)
    // =======================================================================

    private static void openCopyableDialog(String title, String message)
    {
        Display display = Display.getDefault();
        Shell dialog = new Shell(display, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
        dialog.setText(title != null && !title.isEmpty() ? title : "Текст уведомления"); //$NON-NLS-1$
        dialog.setLayout(new GridLayout(1, false));

        // SWT.WRAP вместо SWT.H_SCROLL — текст переносится по словам,
        // горизонтальная полоса прокрутки не нужна.
        Text textWidget = new Text(dialog,
            SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP);
        textWidget.setText(message != null ? message : ""); //$NON-NLS-1$
        GridData gd   = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.widthHint  = 480;
        gd.heightHint = 220;
        textWidget.setLayoutData(gd);

        // SWT.READ_ONLY блокирует стандартные горячие клавиши на Windows,
        // поэтому Ctrl+C обрабатываем вручную.
        textWidget.addListener(SWT.KeyDown, e ->
        {
            if (e.stateMask == SWT.CTRL && e.keyCode == 'c')
            {
                String sel = textWidget.getSelectionText();
                if (sel != null && !sel.isEmpty())
                {
                    Clipboard cb = new Clipboard(display);
                    cb.setContents(
                        new Object[]{ sel },
                        new Transfer[]{ TextTransfer.getInstance() }
                    );
                    cb.dispose();
                }
            }
        });

        Button btnClose = new Button(dialog, SWT.PUSH);
        btnClose.setText("Закрыть"); //$NON-NLS-1$
        btnClose.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        btnClose.addListener(SWT.Selection, e -> dialog.dispose());

        dialog.pack();
        Rectangle screen = display.getPrimaryMonitor().getBounds();
        Rectangle db     = dialog.getBounds();
        dialog.setLocation(screen.x + (screen.width  - db.width)  / 2,
                           screen.y + (screen.height - db.height) / 2);
        dialog.open();
    }
}
