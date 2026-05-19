

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Работа с WMI через COM (Jacob).
 *
 * <p>Точный порт функций из RDT.os:
 * <ul>
 *   <li>{@link #connectWmi()} ← {@code ПолучитьCOMОбъектWMIЛкс()}</li>
 *   <li>{@link #findProcess}  ← {@code ПолучитьПроцессОСЛкс()}</li>
 *   <li>{@link #terminate}    ← {@code УбитьПроцесс()}</li>
 * </ul>
 */
public final class WmiProcessHelper
{
    /** UTC, формат как в {@code ПолучитьЛитералДатыДляWQLЛкс()}. */
    private static final DateTimeFormatter WQL_DATE_FMT =
        DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss").withZone(ZoneOffset.UTC); //$NON-NLS-1$

    private WmiProcessHelper() {}

    // -----------------------------------------------------------------------
    // ПолучитьCOMОбъектWMIЛкс()
    // -----------------------------------------------------------------------

    /**
     * <pre>
     *   Locator = Новый COMОбъект("WbemScripting.SWbemLocator");
     *   Возврат Locator.ConnectServer(".", "root\cimv2");
     * </pre>
     */
    public static Object connectWmi()
    {
        try
        {
            Object locator = ComJacobBridge.createComObject("WbemScripting.SWbemLocator"); //$NON-NLS-1$
            Object wmi     = ComJacobBridge.invoke(locator, "ConnectServer", ".", "root\\cimv2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            IRApplicationRegistry.log("WMI подключён: root\\cimv2"); //$NON-NLS-1$
            return wmi;
        }
        catch (Exception e)
        {
            IRApplicationRegistry.log("WMI connectWmi() ошибка: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // ПолучитьПроцессОСЛкс()
    // -----------------------------------------------------------------------

    /**
     * Порт вызова {@code ПолучитьПроцессОСЛкс(Неопределено, МоментСтарта, 2, "-Embedding")}
     * из {@code ПодключениеИР()}.
     */
    public static Object findProcess(Object wmi, long startedAfterMs, int toleranceSec)
    {
        if (wmi == null) return null;

        String dateFrom = wqlDate(startedAfterMs - toleranceSec * 1000L);
        String dateTo   = wqlDate(startedAfterMs + (toleranceSec + 1) * 1000L);

        String wql = "Select * from Win32_Process" //$NON-NLS-1$
            + " Where CommandLine LIKE '%-Embedding%'" //$NON-NLS-1$
            + " AND Name = '1cv8.exe'" //$NON-NLS-1$
            + " AND CreationDate >= '" + dateFrom + "'" //$NON-NLS-1$ //$NON-NLS-2$
            + " AND CreationDate <= '" + dateTo   + "'"; //$NON-NLS-1$ //$NON-NLS-2$

        IRApplicationRegistry.log("WQL: " + wql); //$NON-NLS-1$
        try
        {
            Object resultSet = ComJacobBridge.invoke(wmi, "ExecQuery", wql); //$NON-NLS-1$
            // Для Каждого ПроцессОС Из ВыборкаПроцессовОС Цикл
            //     Значение = ПроцессОС; Прервать;  // берём первый
            // КонецЦикла;
            for (Object process : ComJacobBridge.iterateComCollection(resultSet))
            {
                long pid = ComJacobBridge.toLong(ComJacobBridge.getProperty(process, "ProcessId")); //$NON-NLS-1$
                IRApplicationRegistry.log("Процесс ОС найден, PID=" + pid); //$NON-NLS-1$
                return process;
            }
            IRApplicationRegistry.log("Процесс ОС не найден (0 результатов)"); //$NON-NLS-1$
            return null;
        }
        catch (Exception e)
        {
            IRApplicationRegistry.log("WMI ExecQuery ошибка: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /** Находит Win32_Process по PID через WMI (для повторного получения объекта при отключении). */
    public static Object findProcessByPid(Object wmi, long pid)
    {
        if (wmi == null || pid <= 0) return null;
        try
        {
            Object resultSet = ComJacobBridge.invoke(wmi, "ExecQuery", //$NON-NLS-1$
                "Select * from Win32_Process Where ProcessID = " + pid); //$NON-NLS-1$
            for (Object p : ComJacobBridge.iterateComCollection(resultSet)) return p;
            return null;
        }
        catch (Exception e)
        {
            IRApplicationRegistry.log("findProcessByPid(" + pid + "): " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // УбитьПроцесс()
    // -----------------------------------------------------------------------

    /**
     * <pre>
     *   // Оригинал: ПроцессОС.Terminate()
     * </pre>
     * Fallback — {@code taskkill /F /PID}.
     */
    public static void terminate(Object processObj, long pid)
    {
        if (processObj != null)
        {
            try
            {
                ComJacobBridge.invoke(processObj, "Terminate"); //$NON-NLS-1$
                IRApplicationRegistry.log("Win32_Process.Terminate() OK, PID=" + pid); //$NON-NLS-1$
                return;
            }
            catch (Exception e)
            {
                IRApplicationRegistry.log("Terminate() ошибка → taskkill: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        killByPid(pid);
    }

    /** Извлекает PID из Win32_Process COM-объекта. */
    public static long getPid(Object processObj)
    {
        if (processObj == null) return 0L;
        try { return ComJacobBridge.toLong(ComJacobBridge.getProperty(processObj, "ProcessId")); } //$NON-NLS-1$
        catch (Exception e) { return 0L; }
    }

    private static String wqlDate(long epochMs)
    {
        return WQL_DATE_FMT.format(Instant.ofEpochMilli(epochMs));
    }

    static void killByPid(long pid)
    {
        if (pid <= 0) return;
        try
        {
            new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(pid)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .redirectErrorStream(true).start().waitFor();
            IRApplicationRegistry.log("taskkill /F /PID " + pid + " выполнен"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e) { IRApplicationRegistry.log("taskkill ошибка: " + e.getMessage()); } //$NON-NLS-1$
    }
}
