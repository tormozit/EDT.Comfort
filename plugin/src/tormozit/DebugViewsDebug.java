package tormozit;

import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IViewPart;

/**
 * Диагностика панелей отладчика «Переменные» / «Выражения» и команды «Отладить объект ИР».
 *
 * <p>Включение: Параметры → Комфорт → «Вести журнал».
 */
public final class DebugViewsDebug
{
    private static final String TAG = "DebugViews"; //$NON-NLS-1$

    private DebugViewsDebug() {}

    public static boolean isEnabled()
    {
        return Global.isLogEnabled();
    }

    public static void log(String msg)
    {
        if (isEnabled())
            Global.log(TAG, msg);
    }

    public static void problem(String msg)
    {
        if (isEnabled())
            Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
    }

    public static void logError(String msg, Throwable error)
    {
        if (isEnabled())
            Global.logError(TAG, msg, error);
    }

    public static String partBrief(IWorkbenchPart part)
    {
        if (part == null)
            return "part=null"; //$NON-NLS-1$
        String id = part instanceof IViewPart view
            ? view.getViewSite().getId()
            : part.getSite().getId();
        return id + " (" + part.getClass().getSimpleName() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    public static String selectionBrief(ISelection selection)
    {
        if (selection == null)
            return "selection=null"; //$NON-NLS-1$
        if (!(selection instanceof IStructuredSelection structured))
            return selection.getClass().getSimpleName();
        if (structured.isEmpty())
            return "selection=empty"; //$NON-NLS-1$
        Object first = structured.getFirstElement();
        String type = first != null ? first.getClass().getSimpleName() : "null"; //$NON-NLS-1$
        return "selection size=" + structured.size() + " first=" + type; //$NON-NLS-1$ //$NON-NLS-2$
    }

    public static String threadBrief()
    {
        return Thread.currentThread().getName();
    }
}
