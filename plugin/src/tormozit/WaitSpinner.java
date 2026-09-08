package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;

/**
 * Крутящийся индикатор слева от подписи статуса: {@code Canvas} в той же ячейке
 * раскладки, что и {@code Label}, чтобы не ломать соседние контролы.
 * <p>
 * Потребители: панель проблем ({@link ProblemViewHook}) и диалог поиска по дереву
 * сравнения ({@link CompareConfigSearchDialogHook}).
 */
final class WaitSpinner
{
    private static final int SIZE = 16;

    private static final int TICK_MS = 80;

    private static final int STEP_DEG = 30;

    private static final String HOST_KEY = "tormozit.comfort.waitSpinnerHost"; //$NON-NLS-1$

    private final Canvas canvas;

    private int angle;

    private boolean running;

    static WaitSpinner attach(Label status, String tooltip)
    {
        if (status == null || status.isDisposed())
            return null;
        Composite parent = status.getParent();
        if (parent == null || parent.isDisposed())
            return null;
        Object data = parent.getData(HOST_KEY);
        if (data instanceof WaitSpinner existing && !existing.isDisposed())
            return existing;
        return create(status, tooltip);
    }

    private static WaitSpinner create(Label status, String tooltip)
    {
        Composite header = status.getParent();
        Object layoutData = status.getLayoutData();
        Composite row = new Composite(header, SWT.NONE);
        row.moveAbove(status);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.horizontalSpacing = 6;
        layout.verticalSpacing = 0;
        row.setLayout(layout);
        if (layoutData != null)
            row.setLayoutData(layoutData);
        row.setBackgroundMode(SWT.INHERIT_DEFAULT);

        Canvas canvas = new Canvas(row, SWT.DOUBLE_BUFFERED | SWT.NO_FOCUS);
        GridData spinnerData = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        int size = Math.max(SIZE, status.computeSize(SWT.DEFAULT, SWT.DEFAULT).y);
        spinnerData.widthHint = size;
        spinnerData.heightHint = size;
        spinnerData.exclude = true;
        canvas.setLayoutData(spinnerData);
        canvas.setVisible(false);
        canvas.setBackgroundMode(SWT.INHERIT_DEFAULT);
        if (tooltip != null && !tooltip.isEmpty())
        {
            canvas.setToolTipText(TooltipText.wrap(canvas,
                tooltip + Global.pluginSignForTooltip()));
        }

        try
        {
            status.setParent(row);
        }
        catch (RuntimeException ignored)
        {
            row.dispose();
            return null;
        }
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        WaitSpinner spinner = new WaitSpinner(canvas);
        row.setData(HOST_KEY, spinner);
        header.layout(true, true);
        return spinner;
    }

    private WaitSpinner(Canvas canvas)
    {
        this.canvas = canvas;
        canvas.addPaintListener(this::paint);
        canvas.addDisposeListener(e -> running = false);
    }

    boolean isDisposed()
    {
        return canvas.isDisposed();
    }

    boolean isVisible()
    {
        return !canvas.isDisposed() && canvas.getVisible();
    }

    void setActive(boolean on)
    {
        if (canvas.isDisposed())
            return;
        GridData data = canvas.getLayoutData() instanceof GridData grid ? grid : null;
        boolean shown = canvas.getVisible() && (data == null || !data.exclude);
        if (on == shown && running == on)
            return;
        if (data != null)
            data.exclude = !on;
        canvas.setVisible(on);
        Composite host = canvas.getParent();
        if (host != null && !host.isDisposed())
        {
            Composite header = host.getParent();
            if (header != null && !header.isDisposed())
                header.layout(true, true);
            else
                host.layout(true, true);
        }
        if (on)
            start();
        else
            running = false;
    }

    private void start()
    {
        if (running)
            return;
        running = true;
        Display display = canvas.getDisplay();
        display.timerExec(0, new Runnable()
        {
            @Override
            public void run()
            {
                if (!running || canvas.isDisposed())
                    return;
                angle = (angle + STEP_DEG) % 360;
                canvas.redraw();
                display.timerExec(TICK_MS, this);
            }
        });
    }

    private void paint(PaintEvent e)
    {
        Point size = canvas.getSize();
        GC gc = e.gc;
        Composite host = canvas.getParent();
        if (host != null && !host.isDisposed() && host.getBackground() != null)
            gc.setBackground(host.getBackground());
        else
            gc.setBackground(canvas.getBackground());
        gc.fillRectangle(0, 0, size.x, size.y);
        gc.setAntialias(SWT.ON);
        gc.setLineWidth(2);
        gc.setLineCap(SWT.CAP_ROUND);
        gc.setForeground(ThemeAwareColors.effectiveSystemColor(
            canvas.getDisplay(), SWT.COLOR_WIDGET_FOREGROUND));
        int pad = 2;
        int d = Math.min(size.x, size.y) - pad * 2;
        if (d < 4)
            return;
        gc.drawArc(pad, pad, d, d, -angle, 270);
    }
}
