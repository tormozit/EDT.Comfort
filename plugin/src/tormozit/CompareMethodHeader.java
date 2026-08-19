package tormozit;

import org.eclipse.jface.text.Document;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/**
 * Имя текущего метода в надписи над полем текста окна сравнения BSL:
 * {@code <подпись стороны> — <ИмяМетода>}. Вне метода суффикс не ставится.
 * В {@link org.eclipse.compare.CompareConfiguration} не пишется — только в CLabel/Label шапки.
 */
final class CompareMethodHeader
{
    private static final String LAST_METHOD_KEY = "tormozit.compareMethodHeader.lastMethod"; //$NON-NLS-1$
    private static final String INSTALLED_KEY = "tormozit.compareMethodHeader.installed"; //$NON-NLS-1$
    private static final String PENDING_KEY = "tormozit.compareMethodHeader.pending"; //$NON-NLS-1$
    private static final String METHOD_SEPARATOR = " — "; //$NON-NLS-1$

    private CompareMethodHeader()
    {
    }

    /** Имя процедуры/функции, в которой стоит каретка; {@code null} вне метода. */
    static String methodNameAt(StyledText text)
    {
        if (text == null || text.isDisposed() || text.getCharCount() <= 0)
            return null;
        try
        {
            int line1 = CompareLineRangeMatcher.lineAtCaret(text) + 1;
            String name = GetRef.findEnclosingMethodName(new Document(text.getText()), line1);
            return name != null && !name.isBlank() ? name : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Двусторонний {@code TextMergeViewer}: {@code fLeftLabel}/{@code fRightLabel} —
     * визуальные подписи (уже с учётом зеркала), к каждой дописывается метод своей панели.
     */
    static void applyTwoWay(Object viewer, String visualLeft, String visualRight)
    {
        if (viewer == null)
            return;
        setField(viewer, "fLeftLabel", visualLeft, //$NON-NLS-1$
            MergeViewerReflection.extractStyledText(viewer, "fLeft")); //$NON-NLS-1$
        setField(viewer, "fRightLabel", visualRight, //$NON-NLS-1$
            MergeViewerReflection.extractStyledText(viewer, "fRight")); //$NON-NLS-1$
    }

    static void setField(Object owner, String fieldName, String baseLabel, StyledText text)
    {
        if (owner == null || fieldName == null || baseLabel == null)
            return;
        String method = methodNameAt(text);
        String shown = method == null ? baseLabel : baseLabel + METHOD_SEPARATOR + method;
        MergeViewerReflection.setLabelText(owner, fieldName, shown);
        rememberMethod(owner, fieldName, method);
    }

    /**
     * Следит за кареткой и обновляет суффикс метода, не трогая базовую подпись стороны.
     * Идемпотентно по {@code lifetime}. {@code result}/{@code resultField} — только у 3-way.
     */
    static void install(Control lifetime, Object owner, StyledText left, String leftField,
        StyledText right, String rightField, StyledText result, String resultField)
    {
        if (lifetime == null || lifetime.isDisposed() || owner == null)
            return;
        if (Boolean.TRUE.equals(lifetime.getData(INSTALLED_KEY)))
            return;
        lifetime.setData(INSTALLED_KEY, Boolean.TRUE);
        Runnable refreshAll = () ->
        {
            refreshField(owner, leftField, left);
            refreshField(owner, rightField, right);
            refreshField(owner, resultField, result);
        };
        wire(lifetime, left, refreshAll);
        wire(lifetime, right, refreshAll);
        wire(lifetime, result, refreshAll);
        refreshAll.run();
    }

    private static void wire(Control lifetime, StyledText text, Runnable refreshAll)
    {
        if (text == null || text.isDisposed())
            return;
        text.addCaretListener(e -> scheduleRefresh(lifetime, refreshAll));
        text.addListener(SWT.Modify, e -> scheduleRefresh(lifetime, refreshAll));
    }

    private static void scheduleRefresh(Control lifetime, Runnable refresh)
    {
        if (lifetime == null || lifetime.isDisposed())
            return;
        if (Boolean.TRUE.equals(lifetime.getData(PENDING_KEY)))
            return;
        Display display = lifetime.getDisplay();
        if (display == null || display.isDisposed())
            return;
        lifetime.setData(PENDING_KEY, Boolean.TRUE);
        display.asyncExec(() ->
        {
            if (lifetime.isDisposed())
                return;
            lifetime.setData(PENDING_KEY, Boolean.FALSE);
            refresh.run();
        });
    }

    private static void refreshField(Object owner, String fieldName, StyledText text)
    {
        if (owner == null || fieldName == null)
            return;
        String current = MergeViewerReflection.extractLabelText(owner, fieldName);
        if (current == null)
            return;
        String last = rememberedMethod(owner, fieldName);
        String base = stripMethodSuffix(current, last);
        setField(owner, fieldName, base, text);
    }

    private static String stripMethodSuffix(String label, String lastMethod)
    {
        if (label == null || lastMethod == null || lastMethod.isBlank())
            return label;
        String suffix = METHOD_SEPARATOR + lastMethod;
        return label.endsWith(suffix) ? label.substring(0, label.length() - suffix.length()) : label;
    }

    private static void rememberMethod(Object owner, String fieldName, String method)
    {
        Object field = Global.getField(owner, fieldName);
        if (field instanceof Control control && !control.isDisposed())
            control.setData(LAST_METHOD_KEY, method);
    }

    private static String rememberedMethod(Object owner, String fieldName)
    {
        Object field = Global.getField(owner, fieldName);
        if (field instanceof Control control && !control.isDisposed())
        {
            Object value = control.getData(LAST_METHOD_KEY);
            return value instanceof String s ? s : null;
        }
        return null;
    }
}
