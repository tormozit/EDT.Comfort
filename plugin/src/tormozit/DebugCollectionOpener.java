package tormozit;

import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;

/**
 * Открытие и фокус окон «Коллекция» с dedup и клонированием.
 */
public final class DebugCollectionOpener
{
    public enum OpenMode
    {
        NORMAL, CLONE
    }

    private DebugCollectionOpener() {}

    public static DebugCollectionWindow open(
        IBslIndexedValue indexedValue,
        IBslStackFrame frame,
        BslValuePath path,
        OpenMode mode)
    {
        return open(indexedValue, frame, path, mode, null);
    }

    public static DebugCollectionWindow openClone(DebugCollectionWindow source)
    {
        if (source == null || source.isDisposed())
            return null;
        DebugCollectionCloneSnapshot snapshot = DebugCollectionCloneSnapshot.capture(source);
        if (snapshot == null)
            return null;
        return open(snapshot.indexedValue(), snapshot.frame(), snapshot.path(), OpenMode.CLONE, snapshot);
    }

    static DebugCollectionWindow open(
        IBslIndexedValue indexedValue,
        IBslStackFrame frame,
        BslValuePath path,
        OpenMode mode,
        DebugCollectionCloneSnapshot cloneSnapshot)
    {
        if (indexedValue == null)
            return null;

        String pathKey = pathKey(path);
        String baseRegistryKey = DebugCollectionWindowRegistry.registryKey(pathKey, frame);

        if (mode == OpenMode.NORMAL)
        {
            DebugCollectionWindow existing = DebugCollectionWindowRegistry.findExisting(baseRegistryKey);
            if (existing != null)
            {
                existing.activate();
                return existing;
            }
        }

        int cloneIndex = 1;
        String registryKey = baseRegistryKey;
        if (mode == OpenMode.CLONE)
        {
            while (DebugCollectionWindowRegistry.findExisting(
                DebugCollectionWindowRegistry.cloneKey(baseRegistryKey, cloneIndex)) != null)
                cloneIndex++;
            registryKey = DebugCollectionWindowRegistry.cloneKey(baseRegistryKey, cloneIndex);
        }

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return null;

        final String finalRegistryKey = registryKey;
        final int finalCloneIndex = cloneIndex;
        final DebugCollectionWindow[] created = new DebugCollectionWindow[1];

        display.syncExec(() -> {
            DebugCollectionWindow window = new DebugCollectionWindow(
                indexedValue,
                frame,
                path,
                mode == OpenMode.CLONE ? finalCloneIndex : 0,
                finalRegistryKey,
                cloneSnapshot);
            window.open();
            DebugCollectionWindowRegistry.register(finalRegistryKey, window);
            created[0] = window;
        });

        return created[0];
    }

    private static String pathKey(BslValuePath path)
    {
        if (path == null)
            return ""; //$NON-NLS-1$
        String expr = path.getExpression();
        if (expr != null && !expr.isBlank())
            return expr.trim();
        String text = path.toString();
        return text != null ? text.trim() : ""; //$NON-NLS-1$
    }
}
