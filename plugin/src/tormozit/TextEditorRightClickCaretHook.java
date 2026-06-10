package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPartSite;

/**
 * Классическое поведение правого клика в текстовых редакторах Workbench:
 * на {@link SWT#MouseDown} сначала перемещает каретку в точку клика,
 * затем платформа показывает контекстное меню.
 *
 * <p>Если клик попадает внутрь текущего выделения, выделение сохраняется
 * (как в {@code org.eclipse.ui.texteditor}).
 */
public class TextEditorRightClickCaretHook implements IStartup
{
    /**
     * Ключ SWT-данных сайта части Workbench
     * (см. {@code org.eclipse.ui.internal.PartPane}, поле {@code SITE_KEY}).
     */
    private static final String PART_SITE_DATA_KEY = "org.eclipse.ui.part.Site"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        ComfortEarlyStartup.defer(() -> install(Display.getDefault()));
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.MouseDown, TextEditorRightClickCaretHook::handleMouseDown);
    }

    private static void handleMouseDown(Event e)
    {
        if (e.button != 3)
            return;
        if (!(e.widget instanceof StyledText))
            return;
        StyledText text = (StyledText) e.widget;
        moveCaretToClick(text, e.x, e.y);
    }

    private static void moveCaretToClick(StyledText text, int x, int y)
    {
        try
        {
            int offset = text.getOffsetAtPoint(new Point(x, y));
            if (offset < 0)
                return;

            Point sel = text.getSelection();
            if (sel.x <= offset && offset <= sel.y)
                return;

            text.setSelection(offset, offset);
        }
        catch (IllegalArgumentException ex)
        {
            // клик вне области текста (поля, отступы)
        }
    }
}
