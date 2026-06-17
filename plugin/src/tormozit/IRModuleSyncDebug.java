package tormozit;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/** Логи синхронизации модулей EDT → ИР (журнал «Комфорт»). */
public final class IRModuleSyncDebug
{
    private static final String TAG = "IRModuleSync"; //$NON-NLS-1$
    private IRModuleSyncDebug() {}

    static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, msg);
    }

    static void problem(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
    }

    static void logDirty(InfobaseReference infobase)
    {
        log("dirty infobase=" + infobaseLabel(infobase)); //$NON-NLS-1$
    }

    static void logGitSync(long changedCount)
    {
        log("начальная git-синхронизация: модулей=" + changedCount); //$NON-NLS-1$
    }

    static void logCollect(int count, boolean dirty)
    {
        log("collect pending=" + count + " dirty=" + dirty); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static void logPushed(String moduleName, String bslPath)
    {
        log("setText " + moduleName + " (" + bslPath + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    static void logSetTextSelection(int rawStart, int rawEnd, int lfStart, int lfEnd, int crlfCount)
    {
        if (rawStart != rawEnd || rawStart != lfStart || rawEnd != lfEnd || crlfCount > 0)
            log("sel raw→lf: " + rawStart + ".." + rawEnd + " → " + lfStart + ".." + lfEnd //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + ", crlf=" + crlfCount); //$NON-NLS-1$
    }

    static void logRangeFromIr(int irLfStart, int irLfEnd, int docStart, int docEnd)
    {
        if (irLfStart != docStart || irLfEnd != docEnd)
            log("range ir LF " + irLfStart + ".." + irLfEnd + " → doc raw " + docStart + ".." + docEnd); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    static void logSelectionFromIr(int irLfStart, int irLfEnd, int docStart, int docEnd)
    {
        if (irLfStart != docStart || irLfEnd != docEnd)
            log("selection ir LF " + irLfStart + ".." + irLfEnd + " → doc raw " + docStart + ".." + docEnd); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    static void logSaveSelectionForUndo(int offset, int length, int offsetAdjust, long durationMs)
    {
        log("saveSelectionForUndo doc.replace+commit " + offset + ".." + (offset + length) //$NON-NLS-1$ //$NON-NLS-2$
            + " adjust=" + offsetAdjust + " " + durationMs + "ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String infobaseLabel(InfobaseReference infobase)
    {
        if (infobase == null)
            return "?"; //$NON-NLS-1$
        String uuid = IRApplication.extractInfobaseUuid(infobase);
        return uuid.isEmpty() ? infobase.toString() : uuid;
    }
}
