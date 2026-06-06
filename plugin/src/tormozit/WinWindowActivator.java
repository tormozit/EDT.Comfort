package tormozit;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

/**
 * Активация главного окна процесса Windows (User32): восстановление из свёрнутого состояния и вывод на передний план.
 */
public final class WinWindowActivator
{
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win"); //$NON-NLS-1$ //$NON-NLS-2$

    private WinWindowActivator() {}

    public static boolean isWindows()
    {
        return WINDOWS;
    }

    /**
     * Находит крупнейшее видимое top-level окно процесса и активирует его.
     *
     * @return {@code true}, если окно найдено и обработано
     */
    public static boolean activateMainWindow(long pid)
    {
        if (!WINDOWS || pid <= 0)
            return false;

        HWND hwnd = findMainWindow((int) pid);
        if (hwnd == null)
        {
            Global.log("WinWindowActivator: окно не найдено для PID=" + pid); //$NON-NLS-1$
            return false;
        }

        showAndActivate(hwnd);
        Global.log("WinWindowActivator: активировано окно PID=" + pid); //$NON-NLS-1$
        return true;
    }

    private static HWND findMainWindow(int pid)
    {
        final HWND[] best = { null };
        final int[] bestArea = { 0 };

        User32.INSTANCE.EnumWindows((hwnd, data) ->
        {
            if (!User32.INSTANCE.IsWindowVisible(hwnd))
                return true;

            HWND owner = User32.INSTANCE.GetWindow(hwnd, new DWORD(WinUser.GW_OWNER));
            if (owner != null && owner.getPointer() != null && !Pointer.NULL.equals(owner.getPointer()))
                return true;

            char[] title = new char[512];
            if (User32.INSTANCE.GetWindowText(hwnd, title, title.length) == 0)
                return true;

            IntByReference windowPid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, windowPid);
            if (windowPid.getValue() != pid)
                return true;

            RECT rect = new RECT();
            if (!User32.INSTANCE.GetWindowRect(hwnd, rect))
                return true;

            int area = Math.max(0, rect.right - rect.left) * Math.max(0, rect.bottom - rect.top);
            if (area > bestArea[0])
            {
                bestArea[0] = area;
                best[0] = hwnd;
            }
            return true;
        }, null);

        return best[0];
    }

    private static boolean isMinimized(HWND hwnd)
    {
        WinUser.WINDOWPLACEMENT placement = new WinUser.WINDOWPLACEMENT();
        if (!User32.INSTANCE.GetWindowPlacement(hwnd, placement).booleanValue())
            return false;
        return placement.showCmd == WinUser.SW_SHOWMINIMIZED;
    }

    private static void showAndActivate(HWND hwnd)
    {
        if (isMinimized(hwnd))
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE);
        else
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOW);

        HWND fg = User32.INSTANCE.GetForegroundWindow();
//        int fgThread = fg != null ? User32.INSTANCE.GetWindowThreadProcessId(fg, null) : 0;
//        int targetThread = User32.INSTANCE.GetWindowThreadProcessId(hwnd, null);
//
//        boolean attached = fgThread != 0 && targetThread != 0 && fgThread != targetThread;
//        if (attached)
//            User32.INSTANCE.AttachThreadInput(fgThread, targetThread, true);

        try
        {
            User32.INSTANCE.BringWindowToTop(hwnd);
            User32.INSTANCE.SetForegroundWindow(hwnd);
        }
        finally
        {
//            if (attached)
//                User32.INSTANCE.AttachThreadInput(fgThread, targetThread, false);
        }
    }
}
