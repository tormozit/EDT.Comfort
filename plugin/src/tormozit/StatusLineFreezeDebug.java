package tormozit;

import java.lang.reflect.Method;
import java.util.Map;

import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * ВРЕМЕННАЯ диагностика: иногда перестаёт обновляться штатный индикатор позиции в строке
 * состояния («2979 : 16 : 110732», «Запись», «Insert»); лечится переактивацией редактора.
 *
 * Все три секции — это поля {@code AbstractTextEditor.fStatusFields}, которые ставит
 * контрибьютор редактора при активации ({@code BasicTextEditorActionContributor.setActiveEditor}),
 * а показывает {@code SubStatusLineManager} панели действий редактора. Залипание возможно, если
 * (а) карта {@code fStatusFields} у видимого редактора очищена (контрибьютор считает активным
 * другой редактор), (б) панель действий редактора деактивирована
 * ({@code SubActionBars.active == false} / {@code SubStatusLineManager.visible == false}),
 * (в) текст в элементе актуален, но не доехал до {@code CLabel}.
 *
 * Пишет по строке на нажатие клавиши/мыши в {@code .tmp/temp-logs/status-line.log} безусловно
 * (без флажка «Общее логирование»). Удалить после исправления дефекта.
 */
public class StatusLineFreezeDebug implements IStartup
{
    private static final String TOPIC = "status-line"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.asyncExec(() -> {
            Listener listener = event -> display
                .asyncExec(() -> sample(event.type == SWT.KeyDown ? "key" : "mouse")); //$NON-NLS-1$ //$NON-NLS-2$
            display.addFilter(SWT.KeyDown, listener);
            display.addFilter(SWT.MouseUp, listener);
            Global.tempLog(TOPIC, "{\"event\":\"installed\"}"); //$NON-NLS-1$
        });
    }

    private static void sample(String trigger)
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window != null ? window.getActivePage() : null;
            if (page == null)
                return;

            IWorkbenchPart activePart = page.getActivePart();
            IEditorPart activeEditor = page.getActiveEditor();
            BslXtextEditor bsl = GetRef.getActiveBslEditor(activePart);
            String source = "activePart"; //$NON-NLS-1$
            if (bsl == null)
            {
                bsl = GetRef.getActiveBslEditor(activeEditor);
                source = "activeEditor"; //$NON-NLS-1$
            }
            if (bsl == null)
                return;

            Control focus = window.getShell().getDisplay().getFocusControl();
            boolean focusInEditor = focus != null && !focus.isDisposed()
                && bsl.getInternalSourceViewer() != null
                && focus == bsl.getInternalSourceViewer().getTextWidget();

            Object statusFields = Global.getField(bsl, "fStatusFields"); //$NON-NLS-1$
            String keys = statusFields instanceof Map
                ? String.valueOf(((Map<?, ?>)statusFields).keySet())
                : String.valueOf(statusFields);
            Object positionField = statusFields instanceof Map
                ? ((Map<?, ?>)statusFields).get("InputPosition") //$NON-NLS-1$
                : null;
            String itemText =
                positionField != null ? String.valueOf(Global.call(positionField, "getText")) : null; //$NON-NLS-1$
            Object label = Global.getField(positionField, "label"); //$NON-NLS-1$
            String labelText = label != null ? String.valueOf(Global.call(label, "getText")) : null; //$NON-NLS-1$
            String expected = String.valueOf(callDeclared(bsl, "getCursorPosition")); //$NON-NLS-1$

            IActionBars bars = bsl.getEditorSite() != null ? bsl.getEditorSite().getActionBars() : null;
            Object barsActive = Global.getField(bars, "active"); //$NON-NLS-1$
            IStatusLineManager statusLine = bars != null ? bars.getStatusLineManager() : null;
            Object statusLineVisible = Global.getField(statusLine, "visible"); //$NON-NLS-1$

            Object contributor = Global.call(bsl.getEditorSite(), "getActionBarContributor"); //$NON-NLS-1$
            Object contributorEditor = Global.getField(contributor, "fActiveEditorPart"); //$NON-NLS-1$

            boolean stale = itemText != null && expected != null && !expected.equals(itemText);
            boolean labelStale = labelText != null && itemText != null && !itemText.equals(labelText);

            Global.tempLog(TOPIC, "{\"trigger\":\"" + trigger //$NON-NLS-1$
                + "\",\"source\":\"" + source //$NON-NLS-1$
                + "\",\"activePart\":\"" + describe(activePart) //$NON-NLS-1$
                + "\",\"activeEditor\":\"" + describe(activeEditor) //$NON-NLS-1$
                + "\",\"editor\":\"" + identity(bsl) //$NON-NLS-1$
                + "\",\"focusInEditor\":" + focusInEditor //$NON-NLS-1$
                + ",\"focus\":\"" + (focus == null ? "null" : focus.getClass().getSimpleName()) //$NON-NLS-1$ //$NON-NLS-2$
                + "\",\"statusFieldKeys\":\"" + keys //$NON-NLS-1$
                + "\",\"expected\":\"" + expected //$NON-NLS-1$
                + "\",\"itemText\":\"" + itemText //$NON-NLS-1$
                + "\",\"labelText\":\"" + labelText //$NON-NLS-1$
                + "\",\"stale\":" + stale //$NON-NLS-1$
                + ",\"labelStale\":" + labelStale //$NON-NLS-1$
                + ",\"bars\":\"" + (bars == null ? "null" : bars.getClass().getSimpleName()) //$NON-NLS-1$ //$NON-NLS-2$
                + "\",\"barsActive\":" + barsActive //$NON-NLS-1$
                + ",\"statusLineVisible\":" + statusLineVisible //$NON-NLS-1$
                + ",\"contributor\":\"" //$NON-NLS-1$
                + (contributor == null ? "null" : contributor.getClass().getSimpleName()) //$NON-NLS-1$
                + "\",\"contributorEditor\":\"" + identity(contributorEditor) + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Global.tempLogException(TOPIC, "sample", e); //$NON-NLS-1$
        }
    }

    private static Object callDeclared(Object target, String methodName)
    {
        if (target == null)
            return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass())
        {
            try
            {
                Method m = c.getDeclaredMethod(methodName);
                m.setAccessible(true);
                return m.invoke(target);
            }
            catch (NoSuchMethodException ignored)
            {
                // ищем выше по иерархии
            }
            catch (Exception e)
            {
                return "<" + e + ">"; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return null;
    }

    private static String describe(Object part)
    {
        if (part == null)
            return "null"; //$NON-NLS-1$
        String title = part instanceof IWorkbenchPart ? ((IWorkbenchPart)part).getTitle() : ""; //$NON-NLS-1$
        return part.getClass().getSimpleName() + "@" //$NON-NLS-1$
            + Integer.toHexString(System.identityHashCode(part)) + " " + title; //$NON-NLS-1$
    }

    private static String identity(Object o)
    {
        return o == null ? "null" //$NON-NLS-1$
            : o.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(o)); //$NON-NLS-1$
    }
}
