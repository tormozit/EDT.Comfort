package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HRGN;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WORD;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Активация главного окна процесса Windows (User32): восстановление из свёрнутого состояния и вывод на передний план.
 */
public final class WinWindowActivator
{
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win"); //$NON-NLS-1$ //$NON-NLS-2$

    /** Win32: {@code (HWND)-1} */
    private static final HWND HWND_TOPMOST = new HWND(Pointer.createConstant(-1));
    /** Win32: {@code (HWND)-2} */
    private static final HWND HWND_NOTOPMOST = new HWND(Pointer.createConstant(-2));
    private static final int WS_EX_TOPMOST = 0x00000008;
    /** Win32 {@code WS_EX_TOOLWINDOW} — в JNA 5.13 не объявлен в {@link WinUser}. */
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    /** Win32 {@code VK_RETURN} — в JNA 5.13 не объявлен в {@link WinUser}. */
    private static final int VK_RETURN = 0x0D;
    /** Win32 {@code KEYEVENTF_KEYUP} — в JNA 5.13 не объявлен в {@link WinUser}. */
    private static final int KEYEVENTF_KEYUP = 0x0002;

    /** Win32-API, отсутствующие в {@link User32} JNA 5.13. */
    private interface User32Extra extends StdCallLibrary
    {
        User32Extra INSTANCE = Native.load("user32", User32Extra.class, W32APIOptions.DEFAULT_OPTIONS); //$NON-NLS-1$

        boolean EnableWindow(HWND hWnd, boolean bEnable);

        boolean SystemParametersInfo(int uiAction, int uiParam, RECT pvParam, int fWinIni);

        boolean ClientToScreen(HWND hWnd, POINT lpPoint);

        int SetWindowRgn(HWND hWnd, HRGN hRgn, boolean bRedraw);
    }

    /** {@code CreateRectRgn} — gdi32 не в classpath PDE target JNA 5.13. */
    private interface Gdi32Extra extends StdCallLibrary
    {
        Gdi32Extra INSTANCE = Native.load("gdi32", Gdi32Extra.class, W32APIOptions.DEFAULT_OPTIONS); //$NON-NLS-1$

        HRGN CreateRectRgn(int nLeftRect, int nTopRect, int nRightRect, int nBottomRect);
    }

    private static final int SPI_GETWORKAREA = 0x0030;
    /** Высота клиентской полосы с логотипом 1С и заголовком ИР (под обрезку region). */
    private static final int IR_TOP_CLIENT_BAND = 24;
    /** Максимальная ширина видимой полоски главного окна ИР. */
    private static final int IR_STRIP_MAX_WIDTH = 180;

    /** Сохранённое состояние top-level окна для восстановления после сжатия до заголовка. */
    public static final class WindowState
    {
        final HWND hwnd;
        final WinUser.WINDOWPLACEMENT placement;
        final RECT rect;

        WindowState(HWND hwnd, WinUser.WINDOWPLACEMENT placement, RECT rect)
        {
            this.hwnd = hwnd;
            this.placement = placement;
            this.rect = rect;
        }
    }

    /** Top-level окно процесса Windows. */
    public static final class ProcessWindowInfo
    {
        public final HWND hwnd;
        public final String title;
        public final int area;
        public final boolean visible;

        ProcessWindowInfo(HWND hwnd, String title, int area, boolean visible)
        {
            this.hwnd = hwnd;
            this.title = title;
            this.area = area;
            this.visible = visible;
        }
    }

    private WinWindowActivator() {}

    public static boolean isWindows()
    {
        return WINDOWS;
    }

    public static boolean isProcessWindow(HWND hwnd, int pid)
    {
        if (!WINDOWS || hwnd == null || pid <= 0)
            return false;
        IntByReference windowPid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, windowPid);
        return windowPid.getValue() == pid;
    }

    public static void setWindowEnabled(HWND hwnd, boolean enabled)
    {
        if (!WINDOWS || hwnd == null)
            return;
        User32Extra.INSTANCE.EnableWindow(hwnd, enabled);
    }

    /**
     * Все top-level окна процесса с заголовком (видимые и невидимые).
     */
    public static List<ProcessWindowInfo> enumProcessWindows(int pid)
    {
        if (!WINDOWS || pid <= 0)
            return Collections.emptyList();

        List<ProcessWindowInfo> result = new ArrayList<>();
        User32.INSTANCE.EnumWindows((hwnd, data) ->
        {
            char[] title = new char[512];
            if (User32.INSTANCE.GetWindowText(hwnd, title, title.length) == 0)
                return true;

            IntByReference windowPid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, windowPid);
            if (windowPid.getValue() != pid)
                return true;

            String titleText = new String(title).trim();
            if (titleText.isEmpty())
                return true;

            RECT rect = new RECT();
            if (!User32.INSTANCE.GetWindowRect(hwnd, rect))
                return true;

            int area = Math.max(0, rect.right - rect.left) * Math.max(0, rect.bottom - rect.top);
            boolean visible = User32.INSTANCE.IsWindowVisible(hwnd);
            result.add(new ProcessWindowInfo(hwnd, titleText, area, visible));
            return true;
        }, null);
        return result;
    }

    public static HWND findMainWindowHandle(int pid)
    {
        return findProcessMainWindowHandle(pid);
    }

    /** {@code true}, если {@link HWND} отсутствует или нулевой (JNA: {@code Pointer.NULL} может быть {@code null}). */
    public static boolean isNullHwnd(HWND hwnd)
    {
        if (hwnd == null)
            return true;
        Pointer p = hwnd.getPointer();
        return p == null || Pointer.nativeValue(p) == 0L;
    }

    public static boolean hwndEquals(HWND a, HWND b)
    {
        if (a == b)
            return true;
        if (isNullHwnd(a) || isNullHwnd(b))
            return false;
        return Pointer.nativeValue(a.getPointer()) == Pointer.nativeValue(b.getPointer());
    }

    /**
     * Главное окно процесса — как {@code System.Diagnostics.Process.MainWindowHandle}:
     * top-level окно без владельца ({@code GW_OWNER}), с непустым заголовком,
     * не {@code WS_EX_TOOLWINDOW}. При {@code includeHidden=true} допускается {@code SW_HIDE}.
     */
    public static HWND findProcessMainWindowHandle(int pid)
    {
        return findProcessMainWindowHandle(pid, false);
    }

    public static HWND findProcessMainWindowHandle(int pid, boolean includeHidden)
    {
        if (!WINDOWS || pid <= 0)
            return null;

        final HWND[] found = { null };
        User32.INSTANCE.EnumWindows((hwnd, data) ->
        {
            if (!isProcessWindow(hwnd, pid) || !isProcessMainWindowCandidate(hwnd, !includeHidden))
                return true;
            found[0] = hwnd;
            return true;
        }, null);
        return found[0];
    }

    /**
     * Крупнейшее top-level окно процесса с заголовком (fallback, если MainWindowHandle не найден).
     */
    public static HWND findLargestProcessMainWindow(int pid)
    {
        if (!WINDOWS || pid <= 0)
            return null;

        HWND best = null;
        int bestArea = 0;
        for (ProcessWindowInfo info : enumProcessWindows(pid))
        {
            if (!isProcessMainWindowCandidate(info.hwnd, false))
                continue;
            if (info.area > bestArea)
            {
                bestArea = info.area;
                best = info.hwnd;
            }
        }
        return best;
    }

    /**
     * Поиск главного окна ИР: кэш сессии → visible → hidden → крупнейшее top-level.
     */
    public static HWND resolveIrMainWindow(IRSession session)
    {
        if (session == null)
            return null;

        long pid = session.pid;
        if (pid <= 0 && session.processObj != null)
            pid = WmiProcessHelper.getPid(session.processObj);
        if (pid <= 0)
            return null;

        com.sun.jna.platform.win32.WinDef.HWND cached = session.getCachedIrMainHwnd();
        if (cached != null)
            return cached;

        int pidInt = (int) pid;
        HWND hwnd = findProcessMainWindowHandle(pidInt, false);
        if (hwnd == null)
            hwnd = findProcessMainWindowHandle(pidInt, true);
        if (hwnd == null)
            hwnd = findLargestProcessMainWindow(pidInt);
        return hwnd;
    }

    /** Главное окно ИР-сессии по PID из {@link IRSession#processObj} / {@link IRSession#pid}. */
    public static HWND findIrMainWindow(IRSession session)
    {
        return resolveIrMainWindow(session);
    }

    private static boolean isProcessMainWindowCandidate(HWND hwnd)
    {
        return isProcessMainWindowCandidate(hwnd, true);
    }

    private static boolean isProcessMainWindowCandidate(HWND hwnd, boolean requireVisible)
    {
        if (isNullHwnd(hwnd) || !User32.INSTANCE.IsWindow(hwnd))
            return false;

        if (requireVisible && !User32.INSTANCE.IsWindowVisible(hwnd))
            return false;

        HWND owner = User32.INSTANCE.GetWindow(hwnd, new DWORD(WinUser.GW_OWNER));
        if (!isNullHwnd(owner))
            return false;

        char[] title = new char[512];
        if (User32.INSTANCE.GetWindowText(hwnd, title, title.length) == 0)
            return false;
        if (new String(title).trim().isEmpty())
            return false;

        int exStyle = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
        return (exStyle & WS_EX_TOOLWINDOW) == 0;
    }

    /** Скрывает top-level окно ({@code SW_HIDE}) — без изменения сохранённой геометрии. */
    public static void hideWindow(HWND hwnd)
    {
        if (!WINDOWS || isNullHwnd(hwnd) || !User32.INSTANCE.IsWindow(hwnd))
            return;

        User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_HIDE);
    }

    public static boolean isWindowVisible(HWND hwnd)
    {
        return !isNullHwnd(hwnd) && User32.INSTANCE.IsWindowVisible(hwnd);
    }

    /**
     * Показывает в левом нижнем углу рабочей области полоску левого верхнего угла окна
     * (логотип 1С + заголовок). Outer-size не уменьшается — обрезка через {@code SetWindowRgn}.
     *
     * @return сохранённое состояние или {@code null}
     */
    public static WindowState shrinkToTitleBar(HWND hwnd)
    {
        if (!WINDOWS || hwnd == null)
            return null;

        if (!User32.INSTANCE.IsWindow(hwnd))
            return null;

        if (isMinimized(hwnd))
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE);

        WinUser.WINDOWPLACEMENT placement = new WinUser.WINDOWPLACEMENT();
        placement.length = placement.size();
        if (!User32.INSTANCE.GetWindowPlacement(hwnd, placement).booleanValue())
            return null;

        RECT rect = new RECT();
        User32.INSTANCE.GetWindowRect(hwnd, rect);

        POINT clientOrigin = new POINT();
        User32Extra.INSTANCE.ClientToScreen(hwnd, clientOrigin);

        int windowWidth = rect.right - rect.left;
        int windowHeight = rect.bottom - rect.top;
        int ncTop = clientOrigin.y - rect.top;
        int stripHeight = ncTop + IR_TOP_CLIENT_BAND;
        int stripWidth = Math.min(IR_STRIP_MAX_WIDTH, windowWidth);

        RECT workArea = getPrimaryWorkArea();
        int x = workArea.left;
        int y = workArea.bottom - stripHeight;

        boolean wasVisible = User32.INSTANCE.IsWindowVisible(hwnd);

        User32.INSTANCE.SetWindowPos(hwnd, null,
            x, y, windowWidth, windowHeight,
            WinUser.SWP_NOZORDER | WinUser.SWP_NOACTIVATE);

        if (!wasVisible)
        {
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOWNOACTIVATE);
            User32.INSTANCE.SetWindowPos(hwnd, null,
                x, y, windowWidth, windowHeight,
                WinUser.SWP_NOZORDER | WinUser.SWP_NOACTIVATE);
        }

        // Region после ShowWindow: на скрытом hwnd обрезка часто не применяется — окно «уезжает» целиком.
        HRGN rgn = Gdi32Extra.INSTANCE.CreateRectRgn(0, 0, stripWidth, stripHeight);
        User32Extra.INSTANCE.SetWindowRgn(hwnd, rgn, true);

        WindowState state = new WindowState(hwnd, copyPlacement(placement), copyRect(rect));
        IrModalWindowDebug.step("shrink", "mode=region hwnd=" + hwnd.getPointer() //$NON-NLS-1$ //$NON-NLS-2$
            + " saved=" + formatRect(rect) + " strip=" + stripWidth + "x" + stripHeight //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " ncTop=" + ncTop + " at " + x + "," + y); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return state;
    }

    private static RECT getPrimaryWorkArea()
    {
        RECT workArea = new RECT();
        User32Extra.INSTANCE.SystemParametersInfo(SPI_GETWORKAREA, 0, workArea, 0);
        return workArea;
    }

    private static String formatRect(RECT rect)
    {
        if (rect == null)
            return "?"; //$NON-NLS-1$
        return rect.left + "," + rect.top + "-" + rect.right + "," + rect.bottom; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static WinUser.WINDOWPLACEMENT copyPlacement(WinUser.WINDOWPLACEMENT src)
    {
        WinUser.WINDOWPLACEMENT copy = new WinUser.WINDOWPLACEMENT();
        copy.length = copy.size();
        copy.flags = src.flags;
        copy.showCmd = src.showCmd;
        if (src.ptMinPosition != null)
        {
            copy.ptMinPosition.x = src.ptMinPosition.x;
            copy.ptMinPosition.y = src.ptMinPosition.y;
        }
        if (src.ptMaxPosition != null)
        {
            copy.ptMaxPosition.x = src.ptMaxPosition.x;
            copy.ptMaxPosition.y = src.ptMaxPosition.y;
        }
        if (src.rcNormalPosition != null)
        {
            copy.rcNormalPosition.left = src.rcNormalPosition.left;
            copy.rcNormalPosition.top = src.rcNormalPosition.top;
            copy.rcNormalPosition.right = src.rcNormalPosition.right;
            copy.rcNormalPosition.bottom = src.rcNormalPosition.bottom;
        }
        return copy;
    }

    private static RECT copyRect(RECT src)
    {
        RECT copy = new RECT();
        copy.left = src.left;
        copy.top = src.top;
        copy.right = src.right;
        copy.bottom = src.bottom;
        return copy;
    }

    /**
     * Восстанавливает позицию, размер и режим отображения (в т.ч. развёрнуто/свёрнуто).
     */
    public static void restoreWindow(WindowState state)
    {
        if (!WINDOWS || state == null || state.hwnd == null)
            return;

        if (!User32.INSTANCE.IsWindow(state.hwnd))
        {
            IrModalWindowDebug.problem("restore: окно уже не существует"); //$NON-NLS-1$
            return;
        }

        User32Extra.INSTANCE.SetWindowRgn(state.hwnd, null, true);
        User32.INSTANCE.ShowWindow(state.hwnd, WinUser.SW_SHOWNORMAL);

        boolean restored = false;
        if (state.placement != null)
        {
            WinUser.WINDOWPLACEMENT placement = copyPlacement(state.placement);
            placement.length = placement.size();
            restored = User32.INSTANCE.SetWindowPlacement(state.hwnd, placement).booleanValue();
            IrModalWindowDebug.step("restore", "SetWindowPlacement=" + restored //$NON-NLS-1$ //$NON-NLS-2$
                + " showCmd=" + placement.showCmd //$NON-NLS-1$
                + " normal=" + formatRect(placement.rcNormalPosition)); //$NON-NLS-1$
        }

        if (!restored && state.rect != null)
        {
            User32.INSTANCE.SetWindowPos(state.hwnd, null,
                state.rect.left, state.rect.top,
                state.rect.right - state.rect.left,
                state.rect.bottom - state.rect.top,
                WinUser.SWP_NOZORDER | WinUser.SWP_NOACTIVATE);
            IrModalWindowDebug.step("restore", "fallback SetWindowPos " + formatRect(state.rect)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Восстанавливает геометрию главного окна без показа (modal end → {@code Visible=false}).
     * <p>
     * Сначала {@code SW_HIDE}, затем снятие region и {@code SetWindowPlacement} с исходным
     * {@code showCmd} (на скрытом hwnd placement применяется без отображения). Не писать
     * {@code showCmd=SW_HIDE} в placement — иначе при следующем {@code Visible=true} COM ИР
     * не сможет открыть диалог.
     */
    public static void restoreWindowHidden(WindowState state)
    {
        if (!WINDOWS || state == null || isNullHwnd(state.hwnd))
            return;

        if (!User32.INSTANCE.IsWindow(state.hwnd))
        {
            IrModalWindowDebug.problem("restore: окно уже не существует"); //$NON-NLS-1$
            return;
        }

        User32.INSTANCE.ShowWindow(state.hwnd, WinUser.SW_HIDE);
        User32Extra.INSTANCE.SetWindowRgn(state.hwnd, null, false);

        boolean restored = false;
        if (state.placement != null)
        {
            WinUser.WINDOWPLACEMENT placement = copyPlacement(state.placement);
            placement.length = placement.size();
            restored = User32.INSTANCE.SetWindowPlacement(state.hwnd, placement).booleanValue();
            IrModalWindowDebug.step("restore", "hidden SetWindowPlacement=" + restored //$NON-NLS-1$ //$NON-NLS-2$
                + " showCmd=" + placement.showCmd //$NON-NLS-1$
                + " normal=" + formatRect(placement.rcNormalPosition)); //$NON-NLS-1$
        }

        if (!restored && state.rect != null)
        {
            User32.INSTANCE.SetWindowPos(state.hwnd, null,
                state.rect.left, state.rect.top,
                state.rect.right - state.rect.left,
                state.rect.bottom - state.rect.top,
                WinUser.SWP_NOZORDER | WinUser.SWP_NOACTIVATE);
            IrModalWindowDebug.step("restore", "hidden fallback SetWindowPos " + formatRect(state.rect)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Сбрасывает Win32-состояние главного окна перед modal-сессией: снимает region и
     * {@code showCmd=SW_HIDE} в placement (остаток старых restore), не показывая окно.
     */
    public static void prepareIrMainForModal(HWND hwnd)
    {
        if (!WINDOWS || isNullHwnd(hwnd) || !User32.INSTANCE.IsWindow(hwnd))
            return;

        User32Extra.INSTANCE.SetWindowRgn(hwnd, null, false);

        WinUser.WINDOWPLACEMENT placement = new WinUser.WINDOWPLACEMENT();
        placement.length = placement.size();
        if (!User32.INSTANCE.GetWindowPlacement(hwnd, placement).booleanValue())
            return;

        if (placement.showCmd != WinUser.SW_HIDE)
            return;

        placement.showCmd = WinUser.SW_SHOWNORMAL;
        User32.INSTANCE.SetWindowPlacement(hwnd, placement);
        User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_HIDE);
    }

    /**
     * Ожидает появления главного окна процесса (до {@code timeoutMs}).
     */
    public static HWND waitIrMainWindow(IRSession session, long timeoutMs)
    {
        if (!WINDOWS || session == null || timeoutMs <= 0)
            return resolveIrMainWindow(session);

        long deadline = System.currentTimeMillis() + timeoutMs;
        HWND hwnd;
        while ((hwnd = resolveIrMainWindow(session)) == null && System.currentTimeMillis() < deadline)
        {
            try
            {
                Thread.sleep(50);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return hwnd;
    }

    /**
     * HWND всех workbench-shell EDT (для монитора модального режима ИР).
     */
    public static Set<HWND> collectWorkbenchShells()
    {
        if (!WINDOWS)
            return Collections.emptySet();

        Set<HWND> result = new HashSet<>();
        try
        {
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            {
                Shell shell = window.getShell();
                if (shell == null || shell.isDisposed())
                    continue;
                HWND hwnd = hwndFromShell(shell);
                if (hwnd != null)
                    result.add(hwnd);
            }
        }
        catch (Exception ignored)
        {
        }
        return result;
    }

    public static boolean isWorkbenchShell(HWND hwnd)
    {
        if (hwnd == null)
            return false;
        for (HWND wb : collectWorkbenchShells())
        {
            if (wb.equals(hwnd))
                return true;
        }
        return false;
    }

    /**
     * {@code true}, если {@code hwnd} — shell workbench EDT или его дочерний элемент
     * (клик в редактор, навигатор и т.п.).
     */
    public static boolean isEdtForeground(HWND hwnd)
    {
        if (!WINDOWS || hwnd == null)
            return false;

        if (isWorkbenchShell(hwnd))
            return true;

        Set<HWND> workbenchShells = collectWorkbenchShells();
        for (HWND current = hwnd; current != null; current = parentWindow(current))
        {
            for (HWND wb : workbenchShells)
            {
                if (current.equals(wb))
                    return true;
            }
        }
        return false;
    }

    /**
     * {@code true}, если {@code hwnd} — одно из модальных окон ИР или его дочерний элемент.
     */
    public static boolean isIrDialogForeground(HWND hwnd, List<HWND> dialogHwnds)
    {
        if (!WINDOWS || hwnd == null || dialogHwnds == null || dialogHwnds.isEmpty())
            return false;

        for (HWND dialog : dialogHwnds)
        {
            for (HWND current = hwnd; current != null; current = parentWindow(current))
            {
                if (current.equals(dialog))
                    return true;
            }
        }
        return false;
    }

    private static HWND parentWindow(HWND hwnd)
    {
        HWND parent = User32.INSTANCE.GetParent(hwnd);
        if (isNullHwnd(parent))
            return null;
        return parent;
    }

    /**
     * Выводит окно на передний план. В псевдомодальном режиме — при переходе фокуса
     * на EDT: активировать только окно ИР, без повторного {@link #activateWorkbench()}.
     */
    public static void activateWindow(HWND hwnd)
    {
        if (hwnd != null)
            showAndActivate(hwnd);
    }

    /**
     * Активирует главное окно EDT (workbench). Нужно перед выводом окон ИР поверх EDT
     * (аналог {@code БлокируемоеОкно} в RDT {@code НачатьВызовВнешнегоОкнаАсинх}).
     *
     * @return {@code true}, если shell workbench найден и активирован
     */
    public static boolean activateWorkbench()
    {
        if (!WINDOWS)
            return false;

        HWND hwnd = null;
        try
        {
            IWorkbenchWindow active = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (active != null)
                hwnd = hwndFromShell(active.getShell());
            if (hwnd == null)
            {
                for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                {
                    hwnd = hwndFromShell(window.getShell());
                    if (hwnd != null)
                        break;
                }
            }
        }
        catch (Exception ignored)
        {
        }

        if (hwnd == null)
        {
            for (HWND wb : collectWorkbenchShells())
            {
                hwnd = wb;
                break;
            }
        }

        if (hwnd == null)
            return false;

        activateWindow(hwnd);
        return true;
    }

    /**
     * Выводит EDT на задний план относительно диалога ИР без {@link #activateWorkbench()}.
     * <p>
     * Используется при открытии диалога, когда 1С уже активировала его — EDT виден сзади,
     * клавиатурный фокус не переключается на EDT (нет мигания).
     */
    public static void showEdtBehindIrDialog(HWND irHwnd)
    {
        if (!WINDOWS || isNullHwnd(irHwnd))
            return;

        for (HWND wb : collectWorkbenchShells())
        {
            User32.INSTANCE.ShowWindow(wb, WinUser.SW_SHOWNA);
            User32.INSTANCE.SetWindowPos(wb, irHwnd, 0, 0, 0, 0,
                WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOACTIVATE);
        }
    }

    /**
     * Активирует EDT, затем окно ИР — чтобы за модальным окном ИР на заднем плане
     * было видно окно EDT (аналог {@code БлокируемоеОкно} в RDT).
     * <p>
     * В псевдомодальном режиме — только при переходе фокуса <em>на окно ИР</em>.
     * При клике в EDT не вызывать: там достаточно {@link #activateWindow(HWND)}.
     */
    public static void activateEdtThenWindow(HWND irHwnd)
    {
        activateWorkbench();
        activateWindow(irHwnd);
    }

    /**
     * {@link #activateEdtThenWindow(HWND)} с UI-потока SWT.
     */
    public static void activateEdtThenWindowOnUiThread(HWND irHwnd)
    {
        if (!WINDOWS || irHwnd == null)
            return;

        try
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
            {
                activateEdtThenWindow(irHwnd);
                return;
            }
            if (Display.getCurrent() == display)
                activateEdtThenWindow(irHwnd);
            else
                display.syncExec(() -> activateEdtThenWindow(irHwnd));
        }
        catch (Exception ignored)
        {
            activateEdtThenWindow(irHwnd);
        }
    }

    /**
     * {@link #activateWindow(HWND)} с UI-потока SWT — {@code SetForegroundWindow} надёжнее,
     * чем из фонового потока монитора.
     */
    public static void activateWindowOnUiThread(HWND hwnd)
    {
        if (!WINDOWS || hwnd == null)
            return;

        try
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
            {
                activateWindow(hwnd);
                return;
            }
            if (Display.getCurrent() == display)
                activateWindow(hwnd);
            else
                display.syncExec(() -> activateWindow(hwnd));
        }
        catch (Exception ignored)
        {
            activateWindow(hwnd);
        }
    }

    /**
     * Закрепляет SWT-shell поверх всех окон ОС (HWND_TOPMOST) или снимает закрепление.
     * На не-Windows платформах не выполняет действий.
     */
    public static void setShellAlwaysOnTop(Shell shell, boolean alwaysOnTop)
    {
        if (!WINDOWS || shell == null || shell.isDisposed())
            return;

        HWND hwnd = hwndFromShell(shell);
        if (hwnd == null)
            return;

        HWND insertAfter = alwaysOnTop ? HWND_TOPMOST : HWND_NOTOPMOST;
        User32.INSTANCE.SetWindowPos(hwnd, insertAfter, 0, 0, 0, 0,
            WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOACTIVATE);
    }

    /** Win32 EnableWindow(TRUE) для тоста, если модальный цикл отключил sibling-окно. */
    public static void ensureToastClickable(Shell shell)
    {
        if (!WINDOWS || shell == null || shell.isDisposed())
            return;
        shell.setEnabled(true);
        HWND hwnd = hwndFromShell(shell);
        if (hwnd == null)
            return;
        setWindowEnabled(hwnd, true);
    }

    public static boolean isShellWindowEnabled(Shell shell)
    {
        if (!WINDOWS || shell == null || shell.isDisposed())
            return true;
        HWND hwnd = hwndFromShell(shell);
        return hwnd == null || User32.INSTANCE.IsWindowEnabled(hwnd);
    }

    /** Экранные координаты top-level shell (Windows). На не-Windows — {@link Shell#setLocation}. */
    public static void setShellScreenPosition(Shell shell, int screenX, int screenY, int width, int height)
    {
        if (shell == null || shell.isDisposed())
            return;
        if (!WINDOWS)
        {
            shell.setLocation(screenX, screenY);
            if (width > 0 && height > 0)
                shell.setSize(width, height);
            return;
        }
        HWND hwnd = hwndFromShell(shell);
        if (hwnd == null)
            return;
        int flags = WinUser.SWP_NOACTIVATE | WinUser.SWP_SHOWWINDOW;
        int w = width > 0 ? width : 0;
        int h = height > 0 ? height : 0;
        if (w <= 0 || h <= 0)
            flags |= WinUser.SWP_NOSIZE;
        flags |= WinUser.SWP_NOZORDER;
        User32.INSTANCE.SetWindowPos(hwnd, null, screenX, screenY, w, h, flags);
    }

    /**
     * Снимает WS_EX_TOPMOST / HWND_TOPMOST у shell (hover с SWT.ON_TOP).
     * На не-Windows не выполняет действий.
     */
    public static void clearShellTopmost(Shell shell)
    {
        if (!WINDOWS || shell == null || shell.isDisposed())
            return;
        HWND hwnd = hwndFromShell(shell);
        if (hwnd != null)
            clearTopmostStyle(hwnd);
    }

    /**
     * Держит popup поверх окна-владельца внутри приложения (Win32 owner), без HWND_TOPMOST.
     * Другие программы могут перекрыть инспектор; окна EDT над выбранным workbench-shell — нет.
     */
    public static void setShellAboveOwner(Shell shell, Shell ownerShell, boolean aboveOwner)
    {
        if (!WINDOWS || shell == null || shell.isDisposed())
            return;

        HWND hwnd = hwndFromShell(shell);
        if (hwnd == null)
            return;

        clearTopmostStyle(hwnd);

        HWND ownerHwnd = null;
        if (aboveOwner && ownerShell != null && !ownerShell.isDisposed())
            ownerHwnd = hwndFromShell(ownerShell);

        Pointer ownerPtr = ownerHwnd != null ? ownerHwnd.getPointer() : Pointer.NULL;
        User32.INSTANCE.SetWindowLongPtr(hwnd, WinUser.GWL_HWNDPARENT, ownerPtr);

        if (aboveOwner && ownerHwnd != null)
        {
            User32.INSTANCE.SetWindowPos(hwnd, new HWND(), 0, 0, 0, 0,
                WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOACTIVATE);
        }
    }

    private static void clearTopmostStyle(HWND hwnd)
    {
        int exStyle = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
        if ((exStyle & WS_EX_TOPMOST) != 0)
        {
            User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, exStyle & ~WS_EX_TOPMOST);
        }
        User32.INSTANCE.SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0,
            WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOACTIVATE);
    }

    static HWND hwndFromShell(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;
        Object handle = Global.getField(shell, "handle"); //$NON-NLS-1$
        if (!(handle instanceof Number number))
            return null;
        return hwndFromNative(number.longValue());
    }

    /** HWND из native-значения; {@code null}, если окно не существует. */
    public static HWND hwndFromNative(long hwndVal)
    {
        if (!WINDOWS || hwndVal == 0)
            return null;
        HWND hwnd = new HWND(Pointer.createConstant(hwndVal));
        if (!User32.INSTANCE.IsWindow(hwnd))
            return null;
        return hwnd;
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

        HWND hwnd = findProcessMainWindowHandle((int) pid);
        if (hwnd == null)
        {
            Global.log("WinWindowActivator: окно не найдено для PID=" + pid); //$NON-NLS-1$
            return false;
        }

        showAndActivate(hwnd);
        Global.log("WinWindowActivator: активировано окно PID=" + pid); //$NON-NLS-1$
        return true;
    }

    /**
     * Ожидает появления top-level окна процесса с заголовком, подходящим под {@code titlePattern}.
     *
     * @return {@code true}, если окно найдено и активировано
     */
    public static boolean waitForWindowTitle(long pid, Pattern titlePattern, long timeoutMs)
    {
        if (!WINDOWS || pid <= 0 || titlePattern == null || timeoutMs <= 0)
            return false;

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline)
        {
            HWND hwnd = findWindowByTitle((int) pid, titlePattern);
            if (hwnd != null)
            {
                showAndActivate(hwnd);
                return true;
            }
            try
            {
                Thread.sleep(50);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static HWND findWindowByTitle(int pid, Pattern titlePattern)
    {
        final HWND[] found = { null };

        User32.INSTANCE.EnumWindows((hwnd, data) ->
        {
            char[] title = new char[512];
            if (User32.INSTANCE.GetWindowText(hwnd, title, title.length) == 0)
                return true;

            IntByReference windowPid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, windowPid);
            if (windowPid.getValue() != pid)
                return true;

            String titleText = new String(title).trim();
            if (!titleText.isEmpty() && titlePattern.matcher(titleText).find())
            {
                found[0] = hwnd;
                return false;
            }
            return true;
        }, null);

        return found[0];
    }

    private static boolean isMinimized(HWND hwnd)
    {
        WinUser.WINDOWPLACEMENT placement = new WinUser.WINDOWPLACEMENT();
        if (!User32.INSTANCE.GetWindowPlacement(hwnd, placement).booleanValue())
            return false;
        return placement.showCmd == WinUser.SW_SHOWMINIMIZED;
    }

    /**
     * Пауза и Ctrl+Enter в окно ИР — автозапуск формы после COM-вызова {@code codeEditor}.
     * Вызывать из COM-потока {@link IRSession#executor}.
     */
    public static void sendCtrlEnterToIrAfterDelay(IRSession session, long delayMs)
    {
        if (!WINDOWS || session == null || session.pid <= 0)
            return;

        if (delayMs > 0)
        {
            try
            {
                Thread.sleep(delayMs);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
        }

        int pid = (int) session.pid;
        HWND hwnd = User32.INSTANCE.GetForegroundWindow();
        if (!isProcessWindow(hwnd, pid))
            hwnd = resolveIrMainWindow(session);
        if (hwnd == null)
            return;

        activateWindow(hwnd);
        sendCtrlEnter();
    }

    private static void sendCtrlEnter()
    {
        WinUser.INPUT[] inputs = (WinUser.INPUT[]) new WinUser.INPUT().toArray(4);

        inputs[0].type = new DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        inputs[0].input.setType("ki"); //$NON-NLS-1$
        inputs[0].input.ki.wVk = new WORD(WinUser.VK_CONTROL);

        inputs[1].type = new DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        inputs[1].input.setType("ki"); //$NON-NLS-1$
        inputs[1].input.ki.wVk = new WORD(VK_RETURN);

        inputs[2].type = new DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        inputs[2].input.setType("ki"); //$NON-NLS-1$
        inputs[2].input.ki.wVk = new WORD(VK_RETURN);
        inputs[2].input.ki.dwFlags = new DWORD(KEYEVENTF_KEYUP);

        inputs[3].type = new DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        inputs[3].input.setType("ki"); //$NON-NLS-1$
        inputs[3].input.ki.wVk = new WORD(WinUser.VK_CONTROL);
        inputs[3].input.ki.dwFlags = new DWORD(KEYEVENTF_KEYUP);

        User32.INSTANCE.SendInput(new DWORD(4), inputs, inputs[0].size());
    }

    private static void showAndActivate(HWND hwnd)
    {
        if (isMinimized(hwnd))
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE);
        else
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOW);

        HWND fg = User32.INSTANCE.GetForegroundWindow();
        int fgThreadId = fg != null ? User32.INSTANCE.GetWindowThreadProcessId(fg, null) : 0;
        int targetThreadId = User32.INSTANCE.GetWindowThreadProcessId(hwnd, null);

        boolean attached = fgThreadId != 0 && targetThreadId != 0 && fgThreadId != targetThreadId;
        if (attached)
            User32.INSTANCE.AttachThreadInput(new DWORD(fgThreadId), new DWORD(targetThreadId), true);

        try
        {
            User32.INSTANCE.BringWindowToTop(hwnd);
            User32.INSTANCE.SetForegroundWindow(hwnd);
        }
        finally
        {
            if (attached)
                User32.INSTANCE.AttachThreadInput(new DWORD(fgThreadId), new DWORD(targetThreadId), false);
        }
    }
}
