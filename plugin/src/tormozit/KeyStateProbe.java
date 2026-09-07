// KeyStateProbe.java
package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Состояние клавиш-модификаторов «прямо сейчас», а не {@code stateMask} прошлого события.
 *
 * <p>На Windows это по-прежнему {@code OS.GetKeyState} — единственный способ узнать
 * состояние клавиши вне события. На прочих платформах (Linux GTK, macOS) класса
 * {@code org.eclipse.swt.internal.win32.OS} в сборке SWT нет вовсе, и обращение к нему
 * завершается {@link NoClassDefFoundError} — issue #466: ошибка вылетала из
 * {@code getHoverInfo2} и гасила в редакторе BSL вообще все подсказки при наведении,
 * а из глобального Display-фильтра — на каждое движение мыши. Поэтому там состояние
 * накапливается слушателями {@link Display#addFilter}: {@code KeyDown}/{@code KeyUp} по
 * самим модификаторам плюс {@code stateMask} любого другого события (он же лечит
 * рассинхрон после Alt+Tab с зажатым Ctrl).
 *
 * <p>Событие, у которого мы сами снимаем бит модификатора (см.
 * {@code TextEditorCtrlClickSelectWordHook}), надо провести через {@link #note(Event)}
 * <b>до</b> правки {@code stateMask} — иначе трекер запомнит уже искажённое состояние.
 */
final class KeyStateProbe
{
    private static final int MODIFIERS = SWT.CTRL | SWT.SHIFT | SWT.ALT;

    private static final boolean WIN32 = "win32".equals(SWT.getPlatform()); //$NON-NLS-1$

    /** Накопленное состояние модификаторов для платформ без Win32-API. */
    private static volatile int modifiers;

    private static boolean filterInstalled;

    private KeyStateProbe() {}

    /** Зажат ли Ctrl (без учёта прочих модификаторов). */
    static boolean isCtrlPressed()
    {
        return (currentModifiers() & SWT.CTRL) != 0;
    }

    /** Зажат ли Ctrl без Shift и Alt — модификатор гиперссылки EDT. */
    static boolean isCtrlOnlyPressed()
    {
        return (currentModifiers() & MODIFIERS) == SWT.CTRL;
    }

    /**
     * Запомнить состояние модификаторов события до того, как мы сами изменим его
     * {@code stateMask}. На Windows не делает ничего: там состояние берётся у системы.
     */
    static void note(Event event)
    {
        if (WIN32 || event == null)
            return;
        modifiers = modifiersOf(event);
    }

    private static int currentModifiers()
    {
        if (WIN32)
            return Win32KeyState.modifiers();
        ensureFilterInstalled();
        return modifiers;
    }

    private static void ensureFilterInstalled()
    {
        if (filterInstalled)
            return;
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed())
            return; // фильтр ставится только из UI-потока; до этого работаем по последнему известному
        filterInstalled = true;
        Listener listener = (Event event) -> modifiers = modifiersOf(event);
        display.addFilter(SWT.KeyDown, listener);
        display.addFilter(SWT.KeyUp, listener);
        display.addFilter(SWT.MouseMove, listener);
        display.addFilter(SWT.MouseDown, listener);
        display.addFilter(SWT.MouseUp, listener);
        display.addFilter(SWT.MouseEnter, listener);
    }

    /**
     * Модификаторы по событию. В самом нажатии (отпускании) модификатора его бита
     * в {@code stateMask} ещё (уже) нет, поэтому клавиша события учитывается отдельно.
     */
    private static int modifiersOf(Event event)
    {
        int mask = event.stateMask & MODIFIERS;
        int key = event.keyCode & MODIFIERS;
        if (key == 0)
            return mask;
        if (event.type == SWT.KeyDown)
            return mask | key;
        if (event.type == SWT.KeyUp)
            return mask & ~key;
        return mask;
    }

    /**
     * Обращение к Win32-API вынесено во вложенный класс: на платформах без него
     * класс просто не загружается, и {@link NoClassDefFoundError} не возникает.
     */
    private static final class Win32KeyState
    {
        private Win32KeyState() {}

        static int modifiers()
        {
            int mask = 0;
            if (pressed(org.eclipse.swt.internal.win32.OS.VK_CONTROL))
                mask |= SWT.CTRL;
            if (pressed(org.eclipse.swt.internal.win32.OS.VK_SHIFT))
                mask |= SWT.SHIFT;
            if (pressed(org.eclipse.swt.internal.win32.OS.VK_MENU))
                mask |= SWT.ALT;
            return mask;
        }

        private static boolean pressed(int virtualKey)
        {
            return (org.eclipse.swt.internal.win32.OS.GetKeyState(virtualKey) & 0x8000) != 0;
        }
    }
}
