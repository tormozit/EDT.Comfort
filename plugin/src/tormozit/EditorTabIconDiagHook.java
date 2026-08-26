package tormozit;

import java.net.URL;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.ui.model.application.ui.MElementContainer;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IPropertyListener;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartConstants;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;

/**
 * Обход штатной пропажи иконок вкладок панели редакторов
 * (<a href="https://github.com/tormozit/EDT.Comfort/issues/400">issue 400</a>).
 * {@code DtGranularEditor.dispose()} уничтожает {@code titleImage} после декоратора;
 * кэш {@code DecoratorManager} отдаёт тот же {@code Image} другим вкладкам того же типа —
 * после закрытия одной вкладки у соседних остаётся disposed-картинка.
 * Восстанавливает живую иконку (title image или {@code iconURI}). Лог: только пропажа/возврат.
 */
public final class EditorTabIconDiagHook implements IStartup
{
    private static final String TOPIC = "editor-tab-icon"; //$NON-NLS-1$
    private static final String KEY_WATCHED = "tormozit.editorTabIconDiag.watched"; //$NON-NLS-1$
    private static final String KEY_SNAP = "tormozit.editorTabIconDiag.snap"; //$NON-NLS-1$
    private static final String OVERRIDE_ICON_KEY = "e4_override_icon_image_key"; //$NON-NLS-1$
    /** Однократная проверка соседей после закрытия вкладки: dispose titleImage идёт после partClosed. */
    private static final int CLOSE_SCAN_MS = 100;

    private static final Map<IWorkbenchPart, IPropertyListener> TITLE_LISTENERS = new IdentityHashMap<>();
    private static final List<CTabFolder> WATCHED = new ArrayList<>();

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(EditorTabIconDiagHook::install);
    }

    private static void install()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Point dpi = display.getDPI();
        log("start dpi=" + dpi.x + "," + dpi.y + " zoom=" + zoomOf(display)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        IWorkbench workbench = PlatformUI.getWorkbench();
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            hookWindow(window);
        workbench.addWindowListener(new IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
            @Override public void windowActivated(IWorkbenchWindow w) {}
            @Override public void windowDeactivated(IWorkbenchWindow w) {}
            @Override public void windowClosed(IWorkbenchWindow w) {}
        });
        scanAll("install"); //$NON-NLS-1$
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null)
                continue;
            IEditorPart active = page.getActiveEditor();
            if (active != null)
                hookEditor(active);
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart editor = ref.getEditor(false);
                if (editor != null && editor != active)
                    hookEditor(editor);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                hookFromRef(ref);
            }

            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                hookFromRef(ref);
            }

            @Override
            public void partVisible(IWorkbenchPartReference ref)
            {
                hookFromRef(ref);
            }

            @Override
            public void partBroughtToTop(IWorkbenchPartReference ref)
            {
                hookFromRef(ref);
            }

            @Override
            public void partClosed(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                unhookPart(part);
                Display display = Display.getCurrent();
                if (display == null || display.isDisposed())
                    return;
                scanAll("closed"); //$NON-NLS-1$
                display.timerExec(CLOSE_SCAN_MS, () ->
                {
                    if (!display.isDisposed())
                        scanAll("closed-delayed"); //$NON-NLS-1$
                });
            }

            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) {}
        });
    }

    private static void hookFromRef(IWorkbenchPartReference ref)
    {
        if (!(ref instanceof IEditorReference editorRef))
            return;
        IEditorPart editor = editorRef.getEditor(false);
        if (editor != null)
            hookEditor(editor);
    }

    private static void hookEditor(IEditorPart editor)
    {
        if (editor == null)
            return;
        try
        {
            watchFolder(folderOf(editor));
            if (!TITLE_LISTENERS.containsKey(editor))
            {
                IPropertyListener listener = (source, propId) ->
                {
                    if (propId != IWorkbenchPartConstants.PROP_TITLE)
                        return;
                    MPart mpart = mpartOf(editor);
                    clearDisposedOverride(mpart);
                    Display display = Display.getCurrent();
                    if (display != null)
                        display.asyncExec(() -> restoreAfterTitleChange(editor));
                };
                TITLE_LISTENERS.put(editor, listener);
                editor.addPropertyListener(listener);
            }
        }
        catch (RuntimeException ex)
        {
            log("hook fail editor=" + className(editor) + " " + ex); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void unhookPart(IWorkbenchPart part)
    {
        if (part == null)
            return;
        IPropertyListener listener = TITLE_LISTENERS.remove(part);
        if (listener != null)
        {
            try
            {
                part.removePropertyListener(listener);
            }
            catch (RuntimeException ignored)
            {
            }
        }
    }

    private static void scanAll(String reason)
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            if (window == null)
                continue;
            for (IWorkbenchPage page : window.getPages())
            {
                if (page == null)
                    continue;
                IEditorPart active = page.getActiveEditor();
                if (active != null)
                    watchFolder(folderOf(active));
                for (IEditorReference ref : page.getEditorReferences())
                {
                    IEditorPart editor = ref.getEditor(false);
                    if (editor != null)
                        watchFolder(folderOf(editor));
                }
            }
        }
        for (int i = WATCHED.size() - 1; i >= 0; i--)
        {
            CTabFolder folder = WATCHED.get(i);
            if (folder.isDisposed())
            {
                WATCHED.remove(i);
                continue;
            }
            scanFolder(folder, reason);
        }
    }

    private static void watchFolder(CTabFolder folder)
    {
        if (folder == null || folder.isDisposed())
            return;
        if (folder.getData(KEY_WATCHED) == Boolean.TRUE)
            return;
        folder.setData(KEY_WATCHED, Boolean.TRUE);
        WATCHED.add(folder);
        Object renderer = folder.getRenderer();
        log("watch folder#" + Integer.toHexString(System.identityHashCode(folder)) //$NON-NLS-1$
            + " items=" + folder.getItemCount() //$NON-NLS-1$
            + " sel=" + folder.getSelectionIndex() //$NON-NLS-1$
            + " style=" + folder.getStyle() //$NON-NLS-1$
            + " zoom=" + zoomOf(folder) //$NON-NLS-1$
            + " renderer=" + (renderer != null ? renderer.getClass().getName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
        scanFolder(folder, "watch"); //$NON-NLS-1$
    }

    private static void scanFolder(CTabFolder folder, String reason)
    {
        if (folder == null || folder.isDisposed())
            return;
        CTabItem[] items;
        try
        {
            items = folder.getItems();
        }
        catch (RuntimeException ex)
        {
            log("scan fail reason=" + reason + " " + ex); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        int selected = folder.getSelectionIndex();
        boolean anyLoss = false;
        StringBuilder dump = new StringBuilder();
        for (int i = 0; i < items.length; i++)
        {
            CTabItem item = items[i];
            if (item == null || item.isDisposed())
                continue;
            String now = itemState(item);
            String prev = (String) item.getData(KEY_SNAP);
            if (prev != null && prev.equals(now))
                continue;
            item.setData(KEY_SNAP, now);
            boolean nowOk = "ok".equals(now); //$NON-NLS-1$
            if (prev == null)
            {
                if (!nowOk)
                {
                    logMissing(reason, "init-missing", i, item, selected, now, prev); //$NON-NLS-1$
                    tryRestore(reason, i, item, i == selected);
                }
                continue;
            }
            if (prev.equals(now))
                continue;
            boolean wasOk = "ok".equals(prev); //$NON-NLS-1$
            if (wasOk && !nowOk)
            {
                anyLoss = true;
                String line = logMissing(reason, "loss", i, item, selected, now, prev); //$NON-NLS-1$
                dump.append(" [").append(i).append("] ").append(line); //$NON-NLS-1$ //$NON-NLS-2$
                tryRestore(reason, i, item, i == selected);
            }
            else if (!wasOk && nowOk)
                logMissing(reason, "restore", i, item, selected, now, prev); //$NON-NLS-1$
        }
        if (anyLoss)
        {
            log(reason + " folderDump folder#" + Integer.toHexString(System.identityHashCode(folder)) //$NON-NLS-1$
                + " items=" + items.length + " sel=" + selected //$NON-NLS-1$ //$NON-NLS-2$
                + " zoom=" + zoomOf(folder) + dump); //$NON-NLS-1$
            log(reason + " stack" + stack()); //$NON-NLS-1$
        }
    }

    private static String logMissing(String reason, String kind, int index, CTabItem item, int selected,
            String now, String prev)
    {
        String line = describeItem(item, index == selected);
        log(reason + " " + kind + " [" + index + "] state=" + now + " prev=" + prev + " " + line); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        return line;
    }

    private static void tryRestore(String reason, int index, CTabItem item, boolean selected)
    {
        if (restoreItem(item))
        {
            String now = itemState(item);
            item.setData(KEY_SNAP, now);
            log(reason + " restore [" + index + "] state=" + now + " " + describeItem(item, selected)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        else
            log(reason + " restore-fail [" + index + "] " + describeItem(item, selected)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * {@code CTabItem.setImage} бросает на уже disposed-картинке, поэтому подставляем
     * живую: title image редактора или иконка из {@code MPart.iconURI}.
     * Из {@code e4_override_icon_image_key} убираем disposed, иначе StackRenderer вернёт её снова.
     */
    private static boolean restoreItem(CTabItem item)
    {
        if (item == null || item.isDisposed())
            return false;
        IEditorPart editor = editorOf(item);
        MPart mpart = mpartOfItem(item);
        clearDisposedOverride(mpart);
        Image live = liveImage(editor, mpart);
        if (!usableImage(live))
            return false;
        try
        {
            NaparnikManualModeHook.logFlickerCause("tabIcon.setImage"); //$NON-NLS-1$
            item.setImage(live);
        }
        catch (RuntimeException ex)
        {
            log("setImage fail tab='" + safeText(item.getText()) + "' " + ex); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        return "ok".equals(itemState(item)); //$NON-NLS-1$
    }

    private static Image liveImage(IEditorPart editor, MPart mpart)
    {
        Image part = editor != null ? titleImageOf(editor) : null;
        if (usableImage(part))
            return part;
        return imageFromIconURI(mpart != null ? mpart.getIconURI() : null);
    }

    private static void clearDisposedOverride(MPart mpart)
    {
        if (mpart == null)
            return;
        Object override = mpart.getTransientData().get(OVERRIDE_ICON_KEY);
        if (override instanceof Image image && !usableImage(image))
            mpart.getTransientData().remove(OVERRIDE_ICON_KEY);
    }

    private static Image imageFromIconURI(String uri)
    {
        if (uri == null || uri.isEmpty())
            return null;
        String prefix = "platform:/plugin/"; //$NON-NLS-1$
        if (!uri.startsWith(prefix))
            return null;
        String rest = uri.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0)
            return null;
        Bundle bundle = Platform.getBundle(rest.substring(0, slash));
        if (bundle == null)
            return null;
        URL url = FileLocator.find(bundle, new Path(rest.substring(slash)), null);
        if (url == null)
            return null;
        try
        {
            ImageDescriptor descriptor = ImageDescriptor.createFromURL(url);
            return JFaceResources.getResources().createImage(descriptor);
        }
        catch (RuntimeException ex)
        {
            log("iconURI fail " + uri + " " + ex); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    private static boolean usableImage(Image image)
    {
        if (image == null)
            return false;
        try
        {
            if (image.isDisposed())
                return false;
            Rectangle bounds = image.getBounds();
            return bounds.width > 0 && bounds.height > 0;
        }
        catch (RuntimeException ex)
        {
            return false;
        }
    }

    private static void logEditor(IEditorPart editor, String reason)
    {
        CTabFolder folder = folderOf(editor);
        watchFolder(folder);
        CTabItem item = itemOf(editor, folder);
        String line = item != null && !item.isDisposed()
            ? describeItem(item, folder != null && folder.getSelection() == item)
            : describeEditorOnly(editor);
        log(reason + " " + line); //$NON-NLS-1$
        log(reason + " stack" + stack()); //$NON-NLS-1$
        if (item != null && !item.isDisposed())
            tryRestore(reason, folder != null ? folder.indexOf(item) : -1, item,
                folder != null && folder.getSelection() == item);
    }

    private static void restoreAfterTitleChange(IEditorPart editor)
    {
        if (editor == null)
            return;
        CTabFolder folder = folderOf(editor);
        CTabItem item = itemOf(editor, folder);
        clearDisposedOverride(mpartOf(editor));
        if (item == null || item.isDisposed())
            return;
        if ("ok".equals(itemState(item)) && usableImage(titleImageOf(editor))) //$NON-NLS-1$
            return;
        if (!"ok".equals(itemState(item))) //$NON-NLS-1$
            logEditor(editor, "propTitle"); //$NON-NLS-1$
        else
            restoreItem(item);
    }

    private static String itemState(CTabItem item)
    {
        Image image = safeImage(item::getImage);
        if (image == null)
            return "null"; //$NON-NLS-1$
        try
        {
            if (image.isDisposed())
                return "disposed"; //$NON-NLS-1$
            Rectangle bounds = image.getBounds();
            if (bounds.width <= 0 || bounds.height <= 0)
                return "empty"; //$NON-NLS-1$
            return "ok"; //$NON-NLS-1$
        }
        catch (RuntimeException ex)
        {
            return "err"; //$NON-NLS-1$
        }
    }

    private static String describeItem(CTabItem item, boolean selected)
    {
        IEditorPart editor = editorOf(item);
        MPart mpart = mpartOfItem(item);
        Image itemImage = safeImage(item::getImage);
        Image partImage = editor != null ? titleImageOf(editor) : null;
        Object override = mpart != null ? mpart.getTransientData().get(OVERRIDE_ICON_KEY) : null;
        Image overrideImage = override instanceof Image img ? img : null;
        StringBuilder sb = new StringBuilder();
        sb.append("tab='").append(safeText(item.getText())).append('\''); //$NON-NLS-1$
        sb.append(" sel=").append(selected); //$NON-NLS-1$
        sb.append(" item=").append(describeImage(itemImage)); //$NON-NLS-1$
        sb.append(" part=").append(describeImage(partImage)); //$NON-NLS-1$
        sb.append(" override=").append(describeImage(overrideImage)); //$NON-NLS-1$
        sb.append(" sameItemPart=").append(itemImage != null && itemImage == partImage); //$NON-NLS-1$
        sb.append(" sameItemOverride=").append(itemImage != null && itemImage == overrideImage); //$NON-NLS-1$
        if (mpart != null)
            sb.append(" iconURI=").append(mpart.getIconURI()); //$NON-NLS-1$
        sb.append(" editor=").append(editor != null ? className(editor) : "?"); //$NON-NLS-1$ //$NON-NLS-2$
        if (editor != null)
            sb.append(" dirty=").append(editor.isDirty()); //$NON-NLS-1$
        return sb.toString();
    }

    private static String describeEditorOnly(IEditorPart editor)
    {
        MPart mpart = mpartOf(editor);
        Object override = mpart != null ? mpart.getTransientData().get(OVERRIDE_ICON_KEY) : null;
        Image overrideImage = override instanceof Image img ? img : null;
        return "tab='?' title='" + safeTitle(editor) + "' item=? part=" //$NON-NLS-1$ //$NON-NLS-2$
            + describeImage(titleImageOf(editor))
            + " override=" + describeImage(overrideImage) //$NON-NLS-1$
            + " iconURI=" + (mpart != null ? mpart.getIconURI() : "null") //$NON-NLS-1$ //$NON-NLS-2$
            + " editor=" + className(editor) //$NON-NLS-1$
            + " dirty=" + editor.isDirty(); //$NON-NLS-1$
    }

    private static String describeImage(Image image)
    {
        if (image == null)
            return "null"; //$NON-NLS-1$
        String id = Integer.toHexString(System.identityHashCode(image));
        try
        {
            if (image.isDisposed())
                return "disposed#" + id; //$NON-NLS-1$
            Rectangle b = image.getBounds();
            String size = b.width + "x" + b.height; //$NON-NLS-1$
            if (b.width <= 0 || b.height <= 0)
                return "empty#" + id + "(" + size + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "ok#" + id + "(" + size + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        catch (RuntimeException ex)
        {
            return "err#" + id + "(" + ex.getClass().getSimpleName() + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    private static Image titleImageOf(IEditorPart editor)
    {
        try
        {
            return editor.getTitleImage();
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    private static Image safeImage(Supplier<Image> getter)
    {
        try
        {
            return getter.get();
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    private static CTabFolder folderOf(IEditorPart editor)
    {
        MPart mpart = mpartOf(editor);
        if (mpart == null)
            return null;
        MElementContainer<MUIElement> parent = mpart.getParent();
        if (parent == null)
            return null;
        Object widget = parent.getWidget();
        return widget instanceof CTabFolder folder && !folder.isDisposed() ? folder : null;
    }

    private static CTabItem itemOf(IEditorPart editor, CTabFolder folder)
    {
        if (folder == null || folder.isDisposed() || editor == null)
            return null;
        MPart mpart = mpartOf(editor);
        Object partWidget = mpart != null ? mpart.getWidget() : null;
        for (CTabItem item : folder.getItems())
        {
            if (item == null || item.isDisposed())
                continue;
            if (mpart != null && mpartOfItem(item) == mpart)
                return item;
            Control control = item.getControl();
            if (partWidget instanceof Control pw && (control == pw || isChild(control, pw) || isChild(pw, control)))
                return item;
        }
        return null;
    }

    private static IEditorPart editorOf(CTabItem item)
    {
        MPart itemPart = mpartOfItem(item);
        Control control = item.getControl();
        if (!PlatformUI.isWorkbenchRunning())
            return null;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            if (window == null)
                continue;
            for (IWorkbenchPage page : window.getPages())
            {
                if (page == null)
                    continue;
                for (IEditorReference ref : page.getEditorReferences())
                {
                    IEditorPart editor = ref.getEditor(false);
                    if (editor == null)
                        continue;
                    MPart mpart = mpartOf(editor);
                    if (itemPart != null && mpart == itemPart)
                        return editor;
                    if (mpart != null && mpart.getWidget() instanceof Control pw
                        && (control == pw || isChild(control, pw) || isChild(pw, control)))
                        return editor;
                }
            }
        }
        return null;
    }

    private static MPart mpartOf(IEditorPart editor)
    {
        try
        {
            if (editor.getSite() == null)
                return null;
            Object raw = editor.getSite().getService(MPart.class);
            return raw instanceof MPart mpart ? mpart : null;
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    private static MPart mpartOfItem(CTabItem item)
    {
        Object data = item.getData("modelElement"); //$NON-NLS-1$
        if (data instanceof MPart mpart)
            return mpart;
        data = item.getData();
        return data instanceof MPart mpart ? mpart : null;
    }

    private static boolean isChild(Control child, Control ancestor)
    {
        if (child == null || ancestor == null || child.isDisposed() || ancestor.isDisposed())
            return false;
        for (Control current = child; current != null && !current.isDisposed(); current = current.getParent())
        {
            if (current == ancestor)
                return true;
        }
        return false;
    }

    private static String zoomOf(Object widget)
    {
        Object zoom = Global.invoke(widget, "getZoom"); //$NON-NLS-1$
        if (zoom == null)
            zoom = Global.invoke(widget, "getDeviceZoom"); //$NON-NLS-1$
        return zoom != null ? String.valueOf(zoom) : "?"; //$NON-NLS-1$
    }

    private static String className(Object obj)
    {
        return obj != null ? obj.getClass().getSimpleName() : "null"; //$NON-NLS-1$
    }

    private static String safeTitle(IWorkbenchPart part)
    {
        try
        {
            return safeText(part.getTitle());
        }
        catch (RuntimeException ex)
        {
            return "?"; //$NON-NLS-1$
        }
    }

    private static String safeText(String text)
    {
        if (text == null)
            return ""; //$NON-NLS-1$
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    private static String stack()
    {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        int n = 0;
        for (int i = 0; i < frames.length && n < 30; i++)
        {
            String frame = frames[i].toString();
            if (frame.contains("EditorTabIconDiagHook") || frame.contains("Thread.getStackTrace")) //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            sb.append(" <- ").append(frame); //$NON-NLS-1$
            n++;
        }
        return sb.toString();
    }

    private static void log(String text)
    {
        Global.tempLog(TOPIC, text);
    }
}
