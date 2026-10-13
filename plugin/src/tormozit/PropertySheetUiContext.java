package tormozit;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/** Поиск SWT-корня палитры «Свойства» и буфер обмена для имени свойства. */
final class PropertySheetUiContext
{
    private PropertySheetUiContext() {}

    /** Колбэки раннего вплетения в MdPropertySheetPage (issue #783). */
    static void installSelectionCallbacks()
    {
        System.getProperties().put("tormozit.propertySheet.invalidate", //$NON-NLS-1$
            (Consumer<Object>) SelectionUpdates::invalidate);
        System.getProperties().put("tormozit.propertySheet.operation", //$NON-NLS-1$
            (BiFunction<Object, Runnable, Runnable>) SelectionUpdates::operation);
        System.getProperties().put("tormozit.propertySheet.result", //$NON-NLS-1$
            (BiFunction<Object, Runnable, Runnable>) SelectionUpdates::result);
        System.getProperties().put("tormozit.propertySheet.progress", //$NON-NLS-1$
            (BiFunction<Object, Runnable, Runnable>) (monitor, runnable) -> SelectionUpdates.progress(monitor, runnable, false));
        System.getProperties().put("tormozit.propertySheet.progressDone", //$NON-NLS-1$
            (BiFunction<Object, Runnable, Runnable>) (monitor, runnable) -> SelectionUpdates.progress(monitor, runnable, true));
    }

    /**
     * Каждая отмена штатного монитора инвалидирует и уже поставленные asyncExec.
     * Очередь ProjectOperationApi остаётся штатной: устаревший Runnable завершается
     * до получения контекста и загрузки свойств. Токен переносится из операции в UI,
     * поскольку EDT создаёт новое ISelection при адаптации к контексту модели.
     */
    private static final class SelectionUpdates
    {
        private static final Map<Object, State> STATES = new WeakHashMap<>();
        private static final ThreadLocal<Ticket> RUNNING = new ThreadLocal<>();

        private static synchronized State state(Object page)
        {
            return STATES.computeIfAbsent(page, key -> new State());
        }

        static void invalidate(Object page)
        {
            State state = state(page);
            synchronized (state)
            {
                ++state.revision;
            }
        }

        static Runnable operation(Object page, Runnable original)
        {
            State state = state(page);
            Ticket ticket;
            synchronized (state)
            {
                ticket = new Ticket(state, state.revision);
            }
            return () ->
            {
                if (!ticket.isCurrent())
                    return;
                Ticket previous = RUNNING.get();
                RUNNING.set(ticket);
                try
                {
                    original.run();
                }
                finally
                {
                    if (previous == null)
                        RUNNING.remove();
                    else
                        RUNNING.set(previous);
                }
            };
        }

        static Runnable result(Object page, Runnable original)
        {
            Ticket ticket = RUNNING.get();
            if (ticket == null || ticket.state != state(page))
                return original;
            return () ->
            {
                if (ticket.isCurrent())
                    original.run();
            };
        }

        /** Monitor.beginTask/done ставят layout панели отдельно от результата операции. */
        static Runnable progress(Object monitor, Runnable original, boolean finish)
        {
            return () ->
            {
                boolean canceled = monitor instanceof IProgressMonitor progress && progress.isCanceled();
                Object page = Global.getField(monitor, "this$0"); //$NON-NLS-1$
                Object currentMonitor = Global.getField(page, "progressMonitor"); //$NON-NLS-1$
                // После перехода к пустому/неподдерживаемому выделению новый Monitor
                // не создаётся: старый done всё ещё должен убрать начатый индикатор.
                boolean skip = canceled && (!finish || currentMonitor != null && currentMonitor != monitor);
                if (!skip)
                    original.run();
            };
        }

        private static final class State
        {
            private long revision;
        }

        private static final class Ticket
        {
            private final State state;
            private final long revision;

            private Ticket(State state, long revision)
            {
                this.state = state;
                this.revision = revision;
            }

            private boolean isCurrent()
            {
                synchronized (state)
                {
                    return revision == state.revision;
                }
            }
        }
    }

    static void copyToClipboard(Control control, String text)
    {
        if (control == null || control.isDisposed() || text == null)
            return;
        Clipboard cb = new Clipboard(control.getDisplay());
        cb.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        cb.dispose();
    }

    static Composite findPaletteRoot(Object page)
    {
        if (page == null)
            return null;
        for (String method : new String[] {
                "getNewPaletteContent", //$NON-NLS-1$
                "getNewPaletteScrolledComposite", //$NON-NLS-1$
                "getRootComposite", //$NON-NLS-1$
                "getControl" //$NON-NLS-1$
        })
        {
            Composite fromMethod = compositeFrom(Global.invoke(page, method));
            if (fromMethod != null && isPlausiblePaletteRoot(fromMethod))
                return fromMethod;
        }
        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        if (scene == null)
        {
            Object palette = Global.invoke(page, "getPaletteComponent"); //$NON-NLS-1$
            scene = palette != null ? Global.invoke(palette, "getScene") : null; //$NON-NLS-1$
        }
        if (scene != null)
        {
            Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
            Object composite = renderer != null ? Global.invoke(renderer, "getComposite") : null; //$NON-NLS-1$
            Composite fromRenderer = compositeFrom(composite);
            if (fromRenderer != null && isPlausiblePaletteRoot(fromRenderer))
                return fromRenderer;
        }
        return null;
    }

    private static Composite compositeFrom(Object value)
    {
        if (value instanceof ScrolledComposite)
        {
            Control content = ((ScrolledComposite) value).getContent();
            if (content instanceof Composite)
                return (Composite) content;
        }
        return value instanceof Composite ? (Composite) value : null;
    }

    /** Прокручиваемый content палитры (полная высота canvas). */
    static Composite findPaletteContent(Object page)
    {
        if (page == null)
            return null;
        for (String method : new String[] {
                "getNewPaletteScrolledComposite", //$NON-NLS-1$
                "getNewPaletteContent" //$NON-NLS-1$
        })
        {
            Object raw = Global.invoke(page, method);
            if (raw instanceof ScrolledComposite)
            {
                Control content = ((ScrolledComposite) raw).getContent();
                if (content instanceof Composite)
                    return (Composite) content;
            }
            if (raw instanceof Composite)
                return (Composite) raw;
        }
        return findPaletteRoot(page);
    }

    static ScrolledComposite findPaletteScrolledComposite(Object page)
    {
        if (page == null)
            return null;
        Object raw = Global.invoke(page, "getNewPaletteScrolledComposite"); //$NON-NLS-1$
        if (raw instanceof ScrolledComposite scrolled)
            return scrolled;
        Object managedForm = Global.invoke(page, "getManagedForm"); //$NON-NLS-1$
        Object form = managedForm != null ? Global.invoke(managedForm, "getForm") : null; //$NON-NLS-1$
        return form instanceof ScrolledComposite scrolled ? scrolled : null;
    }

    static boolean isPlausiblePaletteRoot(Composite composite)
    {
        if (composite == null || composite.isDisposed())
            return false;
        String cn = composite.getClass().getSimpleName();
        return !cn.contains("ToolBar") && !cn.contains("Toolbar") && !cn.contains("Menu"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
