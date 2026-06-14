package tormozit;

import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;

/**
 * Открытие и фокус окон «Коллекция» с dedup и клонированием.
 */
public final class ComfortCollectionOpener
{
    public enum OpenMode
    {
        NORMAL, CLONE
    }

    private ComfortCollectionOpener() {}

    public static ComfortCollectionWindow open(
        IBslIndexedValue indexedValue,
        IBslStackFrame frame,
        BslValuePath path,
        OpenMode mode)
    {
        if (indexedValue == null)
            return null;

        String pathKey = pathKey(path);
        String baseRegistryKey = CollectionWindowRegistry.registryKey(pathKey, frame);

        if (mode == OpenMode.NORMAL)
        {
            ComfortCollectionWindow existing = CollectionWindowRegistry.findExisting(baseRegistryKey);
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
            while (CollectionWindowRegistry.findExisting(
                CollectionWindowRegistry.cloneKey(baseRegistryKey, cloneIndex)) != null)
                cloneIndex++;
            registryKey = CollectionWindowRegistry.cloneKey(baseRegistryKey, cloneIndex);
        }

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return null;

        final String finalRegistryKey = registryKey;
        final int finalCloneIndex = cloneIndex;
        final ComfortCollectionWindow[] created = new ComfortCollectionWindow[1];

        display.syncExec(() -> {
            ComfortCollectionWindow window = new ComfortCollectionWindow(
                indexedValue, frame, path, mode == OpenMode.CLONE ? finalCloneIndex : 0, finalRegistryKey);
            window.open();
            CollectionWindowRegistry.register(finalRegistryKey, window);
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
