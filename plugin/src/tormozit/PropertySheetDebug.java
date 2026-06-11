package tormozit;

import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;

/**
 * Логи хука панели «Свойства» через {@link Global}.
 * Включение: Параметры → Комфорт → «Общее логирование».
 */
public final class PropertySheetDebug
{
    private static final String TAG = "PropertySheet"; //$NON-NLS-1$

    private PropertySheetDebug() {}

    static boolean isEnabled()
    {
        return Global.isLogEnabled();
    }

    static boolean isVerbose()
    {
        return Global.isLogEnabled();
    }

    static boolean isTrace()
    {
        return Global.isLogEnabled();
    }

    static String flags()
    {
        return "enabled=" + Global.isLogEnabled(); //$NON-NLS-1$
    }

    public static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, msg);
    }

    /** Проблема / нештатная ситуация. */
    static void problem(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
    }

    static void scanProblem(String msg)
    {
        problem("[scan] " + msg); //$NON-NLS-1$
    }

    static void uiProblem(String msg)
    {
        problem("[ui] " + msg); //$NON-NLS-1$
    }

    static void valueControl(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, "[value] " + msg); //$NON-NLS-1$
    }

    static void valueControlVerbose(String msg)
    {
        valueControl(msg);
    }

    /** Синхронизация «Новая» ↔ «Старая». */
    static void sync(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, "[sync] " + msg); //$NON-NLS-1$
    }

    static void syncVerbose(String msg)
    {
        sync(msg);
    }

    static void scan(String msg)
    {
        log("[scan] " + msg); //$NON-NLS-1$
    }

    static void scanVerbose(String msg)
    {
        log("[scan] " + msg); //$NON-NLS-1$
    }

    static void resolve(String msg)
    {
        log("[resolve] " + msg); //$NON-NLS-1$
    }

    static void resolveVerbose(String msg)
    {
        resolve(msg);
    }

    static void ui(String msg)
    {
        log("[ui] " + msg); //$NON-NLS-1$
    }

    static void uiVerbose(String msg)
    {
        log("[ui] " + msg); //$NON-NLS-1$
    }

    static void feature(String msg)
    {
        log("[feature] " + msg); //$NON-NLS-1$
    }

    public static String safe(Object o)
    {
        if (o == null)
            return "<null>"; //$NON-NLS-1$
        return o.getClass().getSimpleName();
    }

    /** Полное имя класса — для логов [value]. */
    static String typeName(Object o)
    {
        if (o == null)
            return "<null>"; //$NON-NLS-1$
        String cn = o.getClass().getName();
        int dot = cn.lastIndexOf('.');
        return dot >= 0 ? cn.substring(dot + 1) : cn;
    }

    static String quote(String s)
    {
        if (s == null)
            return "<null>"; //$NON-NLS-1$
        if (s.length() > 60)
            return "\"" + s.substring(0, 57) + "...\""; //$NON-NLS-1$ //$NON-NLS-2$
        return "\"" + s + "\""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    static String controlBrief(Control control)
    {
        if (control == null)
            return "<null>"; //$NON-NLS-1$
        if (control.isDisposed())
            return safe(control) + "(disposed)"; //$NON-NLS-1$
        Point size = control.getSize();
        Point loc = control.toDisplay(0, 0);
        String text = PropertySheetControlInterop.controlText(control);
        if (text.length() > 40)
            text = text.substring(0, 37) + "..."; //$NON-NLS-1$
        return safe(control) + " " + size.x + "x" + size.y //$NON-NLS-1$ //$NON-NLS-2$
                + " @(" + loc.x + "," + loc.y + ")" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + (text.isEmpty() ? "" : " text=" + quote(text)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Дерево SWT под root (depth) — для диагностики slots=0. */
    static String compositeTreeBrief(org.eclipse.swt.widgets.Composite root, int maxDepth)
    {
        if (root == null || root.isDisposed())
            return "<null>"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        appendCompositeTree(root, 0, maxDepth, sb);
        return sb.toString();
    }

    private static void appendCompositeTree(org.eclipse.swt.widgets.Composite composite, int depth,
            int maxDepth, StringBuilder sb)
    {
        if (composite == null || composite.isDisposed() || depth > maxDepth)
            return;
        if (sb.length() > 0)
            sb.append(" | "); //$NON-NLS-1$
        sb.append(repeat("  ", depth)).append(safe(composite)); //$NON-NLS-1$
        sb.append(" ch=").append(composite.getChildren().length); //$NON-NLS-1$
        for (org.eclipse.swt.widgets.Control child : composite.getChildren())
        {
            if (child == null || child.isDisposed())
                continue;
            sb.append(" | ").append(repeat("  ", depth + 1)).append(controlBrief(child)); //$NON-NLS-1$ //$NON-NLS-2$
            if (child instanceof org.eclipse.swt.widgets.Composite && depth + 1 < maxDepth)
                appendCompositeTree((org.eclipse.swt.widgets.Composite) child, depth + 1, maxDepth, sb);
        }
    }

    private static String repeat(String s, int n)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++)
            sb.append(s);
        return sb.toString();
    }

    /** Причина отклонения контрола как «имя свойства». */
    static String nameControlRejectReason(Control control, String expectedName)
    {
        if (control == null)
            return "control=null"; //$NON-NLS-1$
        if (control.isDisposed())
            return "disposed"; //$NON-NLS-1$
        if (PropertySheetUiContext.isFilterAreaControl(control))
            return "filterArea"; //$NON-NLS-1$
        if (PropertySheetControlInterop.isTwistieOrDecor(control))
            return "twistieOrDecor"; //$NON-NLS-1$
        Point size = control.getSize();
        if (size.x > 0 && size.x < 20)
            return "tooNarrow w=" + size.x; //$NON-NLS-1$
        if (size.y > 48)
            return "tooTall h=" + size.y; //$NON-NLS-1$
        String visible = PropertySheetControlInterop.controlText(control);
        if (!visible.isEmpty() && !expectedName.equals(visible))
            return "textMismatch visible=" + quote(visible); //$NON-NLS-1$
        return "ok"; //$NON-NLS-1$
    }
}
