package tormozit;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

import com._1c.g5.v8.dt.common.ui.controls.ImageViewer;

/**
 * Единое поведение поля картинки во всех окнах (редактор, мастер «Новая общая
 * картинка», «Выбор картинки»): Ctrl+колёсико, контекстное меню масштаба,
 * подпись формат·размер·глубина.
 *
 * <p>ImageViewer — {@code WebView.setZoom} на FX-потоке.
 * SWT {@link Label} — масштаб от вписанного в родителя размера ({@code fit × zoom}).
 */
public final class PictureFieldEnhance
{
    private static final String INSTALLED_KEY = "tormozit.pictureFieldEnhance"; //$NON-NLS-1$
    private static final String SESSION_KEY = INSTALLED_KEY + ".session"; //$NON-NLS-1$
    private static final String META_LABEL_KEY = "tormozit.pictureFieldMetaLabel"; //$NON-NLS-1$
    private static final String LOAD_HOOK_KEY = INSTALLED_KEY + ".loadHook"; //$NON-NLS-1$
    private static final String FX_INPUT_KEY = INSTALLED_KEY + ".fxInput"; //$NON-NLS-1$
    private static final String LOG_TAG = "PictureFieldEnhance"; //$NON-NLS-1$
    private static final double ZOOM_STEP = 1.25;
    private static final double ZOOM_MIN = 0.25;
    private static final double ZOOM_MAX = 4.0;
    private static boolean wheelFilterInstalled;

    private PictureFieldEnhance() {}

    /** Ctrl+колёсико. Берём контрол под курсором — e.widget часто фокус (таблица), не превью. */
    public static void ensureGlobalFilters(Display display)
    {
        if (wheelFilterInstalled || display == null || display.isDisposed())
            return;
        wheelFilterInstalled = true;
        display.addFilter(SWT.MouseWheel, e -> handleSwtWheel(display, e));
        display.addFilter(SWT.MouseHorizontalWheel, e -> handleSwtWheel(display, e));
    }

    private static void handleSwtWheel(Display display, org.eclipse.swt.widgets.Event e)
    {
        // MOD1 = Ctrl на Windows; CTRL иногда не выставлен в stateMask у колеса.
        if ((e.stateMask & (SWT.MOD1 | SWT.CTRL)) == 0)
            return;
        Control under = display.getCursorControl();
        if (under == null || under.isDisposed())
        {
            if (e.widget instanceof Control c && !c.isDisposed())
                under = c;
            else
                return;
        }
        Session session = findSession(under);
        if (session == null)
            return;
        // ImageViewer: зум только через FX ScrollEvent — иначе SWT+FX удваивают шаг.
        if (session.control instanceof ImageViewer)
        {
            e.doit = false;
            return;
        }
        try
        {
            if (e.count > 0)
                session.zoomIn();
            else if (e.count < 0)
                session.zoomOut();
        }
        catch (Throwable t)
        {
            Global.logError(LOG_TAG, "wheel", t); //$NON-NLS-1$
        }
        e.doit = false;
    }

    private static Session findSession(Control start)
    {
        for (Control c = start; c != null && !c.isDisposed(); c = c.getParent())
        {
            Object raw = c.getData(SESSION_KEY);
            if (raw instanceof Session session)
                return session;
        }
        return null;
    }

    public static void install(ImageViewer viewer)
    {
        if (viewer == null || viewer.isDisposed())
            return;
        ensureGlobalFilters(viewer.getDisplay());

        // Повторный вход: AEF/loadImage не всегда шлёт Show, а FX-слушатели/дети
        // могут обнулиться — переподключаем, не создавая вторую сессию.
        if (viewer.getData(INSTALLED_KEY) != null)
        {
            Object raw = viewer.getData(SESSION_KEY);
            if (raw instanceof Session session)
            {
                bindSession(viewer, session);
                ensureLoadReapply(viewer, session);
                applyWebViewZoom(session);
                scheduleMetaFromViewer(session);
            }
            return;
        }
        viewer.setData(INSTALLED_KEY, Boolean.TRUE);
        Global.tempLog(DIAG, "install new ImageViewer bounds=" + viewer.getBounds() //$NON-NLS-1$
            + " from=" + StackWalker.getInstance().walk(s -> s.skip(1).limit(3) // agent log
                .map(f -> f.getClassName().replaceFirst(".*\\.", "") + "." + f.getMethodName()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .collect(java.util.stream.Collectors.joining(" < ")))); //$NON-NLS-1$

        // Мета — рядом с viewer, если родитель на GridLayout. В редакторе и мастере общей
        // картинки родитель — AEF-контейнер со своим макетом (SwtLightLayout), чужой Label он
        // не размещает; тогда — внутрь viewer (GridLayout в одну колонку) строкой под FXCanvas.
        Label meta = ensureMetaLabel(viewer.getParent());
        if (meta == null)
            meta = ensureMetaLabel(viewer);
        Session session = new Session(viewer, meta, null);
        bindSession(viewer, session);
        ensureLoadReapply(viewer, session);
        scheduleMetaFromViewer(session);
    }

    /**
     * SWT-превью («Выбор картинки»): {@code sourceImage} — оригинал без вписывания
     * (например {@code PictureDialog.currentImage}). {@code sourceBytes} — исходные байты этой
     * картинки: формат и глубину у {@link Image} уже не узнать.
     */
    public static void installSwtPreview(Label pictureLabel, Label sizeLabel,
        java.util.function.Supplier<Image> sourceImage,
        java.util.function.Supplier<byte[]> sourceBytes)
    {
        if (pictureLabel == null || pictureLabel.isDisposed())
            return;
        ensureGlobalFilters(pictureLabel.getDisplay());
        if (pictureLabel.getData(INSTALLED_KEY) != null)
            return;
        pictureLabel.setData(INSTALLED_KEY, Boolean.TRUE);

        Label meta = sizeLabel != null && !sizeLabel.isDisposed() ? sizeLabel : null;
        Session session = new Session(pictureLabel, meta, sourceImage);
        session.sourceBytesSupplier = sourceBytes;
        bindSession(pictureLabel, session);
        // Родитель: Ctrl+колёсико рядом с картинкой и после штатного resize→uploadImage.
        Composite parent = pictureLabel.getParent();
        if (parent != null && !parent.isDisposed())
        {
            parent.setData(SESSION_KEY, session);
            attachZoomMenu(parent, session);
            parent.addListener(SWT.Resize, e ->
                pictureLabel.getDisplay().asyncExec(() ->
                {
                    if (pictureLabel.isDisposed())
                        return;
                    if (session.zoom != 1.0)
                        applySwtZoom(session);
                    else
                        refreshMetaFromSwt(session);
                }));
        }
        refreshMetaFromSwt(session);
    }

    /** Поле картинки уже подключено ({@link #install(ImageViewer)} хотя бы раз). */
    public static boolean isInstalled(Control control)
    {
        return control != null && !control.isDisposed() && control.getData(INSTALLED_KEY) != null;
    }

    public static void notifyImageChanged(ImageViewer viewer)
    {
        if (viewer == null || viewer.isDisposed())
            return;
        Object raw = viewer.getData(SESSION_KEY);
        if (!(raw instanceof Session session))
            return;
        session.zoom = 1.0;
        applyWebViewZoom(session);
        scheduleMetaFromViewer(session);
    }

    /** Сброс зума/меты после смены картинки в SWT-превью. */
    public static void notifySwtImageChanged(Label pictureLabel)
    {
        if (pictureLabel == null || pictureLabel.isDisposed())
            return;
        Object raw = pictureLabel.getData(SESSION_KEY);
        if (!(raw instanceof Session session))
            return;
        session.zoom = 1.0;
        disposeScaled(session);
        expandPictureHost(pictureLabel.getParent(), session, 0);
        refreshMetaFromSwt(session);
    }

    public static void installRecursive(Control root)
    {
        if (root == null || root.isDisposed())
            return;
        if (root instanceof ImageViewer iv)
            install(iv);
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
                installRecursive(child);
        }
    }

    public static String formatMeta(ImageData data, String formatHint)
    {
        if (data == null)
            return ""; //$NON-NLS-1$
        String format = formatHint != null && !formatHint.isBlank() ? formatHint : formatFromType(data);
        return formatMeta(data.width, data.height, format, data.depth);
    }

    public static String formatMeta(byte[] bytes, String fileNameOrExt)
    {
        return formatMeta(bytes, fileNameOrExt, null);
    }

    /**
     * Мета по исходным байтам: формат — по сигнатуре, размер и глубина — из первого кадра.
     * {@code fallback} — уже построенный образ на случай, когда SWT байты не декодирует (SVG):
     * тогда размер берётся у него, а глубину он не хранит честно (на Windows всегда 32 бита).
     */
    private static String formatMeta(byte[] bytes, String fileNameOrExt, ImageData fallback)
    {
        if (bytes == null || bytes.length == 0)
            return fallback != null ? formatMeta(fallback, null) : ""; //$NON-NLS-1$
        String sniffed = sniffFormat(bytes);
        String format = sniffed != null ? sniffed : extensionOf(fileNameOrExt);
        ImageData frame = null;
        try
        {
            ImageData[] frames = new ImageLoader().load(new ByteArrayInputStream(bytes));
            if (frames != null && frames.length > 0)
                frame = frames[0];
        }
        catch (Exception e)
        {
            // SVG и прочее, что SWT не декодирует.
        }
        // #region agent log
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(12, bytes.length); i++)
            hex.append(String.format("%02X ", bytes[i] & 0xFF)); //$NON-NLS-1$
        Global.tempLog(DIAG, "formatMeta len=" + bytes.length + " head=" + hex + "-> " + sniffed //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " frame=" + (frame == null ? "null" : frame.width + "x" + frame.height + "/" + frame.depth) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + " fallback=" + (fallback != null)); //$NON-NLS-1$
        // #endregion
        if (frame != null)
            return formatMeta(frame, format);
        if (fallback != null)
            return formatMeta(fallback.width, fallback.height, format, 0);
        return format != null ? format.toUpperCase(Locale.ROOT) : ""; //$NON-NLS-1$
    }

    private static String formatMeta(int width, int height, String format, int depth)
    {
        String depthText = depth > 0 ? depth + " бит" : "?"; //$NON-NLS-1$ //$NON-NLS-2$
        String formatText = format != null ? format.toUpperCase(Locale.ROOT) : "?"; //$NON-NLS-1$
        return formatText + " · " + width + " × " + height + " · " + depthText; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Сколько байт от начала хватает для распознавания сигнатуры. */
    private static final int SNIFF_BYTES = 256;

    /** Формат по сигнатуре в начале данных; {@code null} — не распознан. */
    private static String sniffFormat(byte[] b)
    {
        if (b == null)
            return null;
        int len = Math.min(SNIFF_BYTES, b.length);
        if (startsWith(b, len, 0x89, 'P', 'N', 'G'))
            return "PNG"; //$NON-NLS-1$
        if (startsWith(b, len, 'G', 'I', 'F', '8'))
            return "GIF"; //$NON-NLS-1$
        if (startsWith(b, len, 0xFF, 0xD8, 0xFF))
            return "JPEG"; //$NON-NLS-1$
        if (startsWith(b, len, 'B', 'M'))
            return "BMP"; //$NON-NLS-1$
        if (startsWith(b, len, 0, 0, 1, 0))
            return "ICO"; //$NON-NLS-1$
        if (startsWith(b, len, 'I', 'I', 42, 0) || startsWith(b, len, 'M', 'M', 0, 42))
            return "TIFF"; //$NON-NLS-1$
        if (startsWith(b, len, 'R', 'I', 'F', 'F') && len >= 12
            && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P')
            return "WEBP"; //$NON-NLS-1$
        if (startsWith(b, len, 'P', 'K', 3, 4))
            return "ZIP"; //$NON-NLS-1$
        String text = new String(b, 0, len, java.nio.charset.StandardCharsets.ISO_8859_1)
            .toLowerCase(Locale.ROOT);
        if (text.contains("<svg")) //$NON-NLS-1$
            return "SVG"; //$NON-NLS-1$
        return null;
    }

    private static boolean startsWith(byte[] b, int len, int... sig)
    {
        if (len < sig.length)
            return false;
        for (int i = 0; i < sig.length; i++)
        {
            if ((b[i] & 0xFF) != sig[i])
                return false;
        }
        return true;
    }

    private static void bindSession(Control control, Session session)
    {
        control.setData(SESSION_KEY, session);
        if (control instanceof ImageViewer viewer)
        {
            // Без setMenu на FXCanvas: меню показываем сами (нативное SWT — с подсветкой).
            clearSwtMenu(viewer);
            for (Control child : viewer.getChildren())
            {
                if (child instanceof Label)
                    continue;
                child.setData(SESSION_KEY, session);
                clearSwtMenu(child);
            }
            wireFxInput(viewer, session);
            return;
        }
        attachZoomMenu(control, session);
        if (control instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                if (child instanceof Label)
                    continue;
                child.setData(SESSION_KEY, session);
                attachZoomMenu(child, session);
            }
        }
    }

    private static void clearSwtMenu(Control control)
    {
        if (control == null || control.isDisposed())
            return;
        Menu old = control.getMenu();
        if (old != null && !old.isDisposed())
            old.dispose();
        control.setMenu(null);
    }

    /**
     * Постоянное {@link Control#setMenu}. Не popup из MenuDetect с dispose в Hide —
     * на Windows Hide приходит до Selection и съедает клик по пункту.
     */
    private static void attachZoomMenu(Control control, Session session)
    {
        Menu old = control.getMenu();
        if (old != null && !old.isDisposed())
            old.dispose();
        Menu menu = new Menu(control);
        addZoomItems(menu, session);
        control.setMenu(menu);
    }

    /**
     * FXCanvas: Ctrl+scroll и ПКМ через JavaFX-события.
     * Меню — нативное SWT (подсветка пункта), показ с задержкой после отпускания ПКМ.
     * ЛКМ/ZoomEvent глотаем — иначе WebKit smart-zoom по клику в центре картинки.
     */
    private static void wireFxInput(ImageViewer viewer, Session session)
    {
        if (Boolean.TRUE.equals(viewer.getData(FX_INPUT_KEY)))
            return;
        try
        {
            Object webView = Global.getField(viewer, "webView"); //$NON-NLS-1$
            if (webView == null)
                return;
            Display display = viewer.getDisplay();
            Class<?> eventHandler = Class.forName("javafx.event.EventHandler"); //$NON-NLS-1$
            Class<?> eventType = Class.forName("javafx.event.EventType"); //$NON-NLS-1$

            Object menuHandler = fxHandler(eventHandler, (event) ->
            {
                double sx = ((Number) event.getClass().getMethod("getScreenX") //$NON-NLS-1$
                    .invoke(event)).doubleValue();
                double sy = ((Number) event.getClass().getMethod("getScreenY") //$NON-NLS-1$
                    .invoke(event)).doubleValue();
                event.getClass().getMethod("consume").invoke(event); //$NON-NLS-1$
                int x = (int) Math.round(sx);
                int y = (int) Math.round(sy);
                // После отпускания ПКМ, иначе меню без трекинга мыши / без подсветки.
                display.asyncExec(() -> display.timerExec(100,
                    () -> showZoomMenuSafe(session, x, y)));
            });
            webView.getClass()
                .getMethod("setOnContextMenuRequested", eventHandler) //$NON-NLS-1$
                .invoke(webView, menuHandler);

            Object scrollHandler = fxHandler(eventHandler, (event) ->
            {
                boolean ctrl = Boolean.TRUE.equals(event.getClass()
                    .getMethod("isControlDown").invoke(event)); //$NON-NLS-1$
                if (!ctrl)
                    return;
                double dy = ((Number) event.getClass().getMethod("getDeltaY") //$NON-NLS-1$
                    .invoke(event)).doubleValue();
                event.getClass().getMethod("consume").invoke(event); //$NON-NLS-1$
                display.asyncExec(() ->
                {
                    if (dy > 0)
                        session.zoomIn();
                    else if (dy < 0)
                        session.zoomOut();
                });
            });
            Class<?> scrollEvent = Class.forName("javafx.scene.input.ScrollEvent"); //$NON-NLS-1$
            Object scrollType = scrollEvent.getField("SCROLL").get(null); //$NON-NLS-1$
            webView.getClass()
                .getMethod("addEventFilter", eventType, eventHandler) //$NON-NLS-1$
                .invoke(webView, scrollType, scrollHandler);

            suppressWebKitClickZoom(webView, eventHandler, eventType);
            viewer.setData(FX_INPUT_KEY, Boolean.TRUE);
        }
        catch (Throwable t)
        {
            Global.logError(LOG_TAG, "wireFxInput", t); //$NON-NLS-1$
        }
    }

    /**
     * WebKit в WebView по ЛКМ/жесту меняет масштаб страницы (выглядит как
     * «клик — зум туда, клик — обратно»). Глотаем ZoomEvent и primary mouse.
     */
    private static void suppressWebKitClickZoom(Object webView, Class<?> eventHandler,
        Class<?> eventType) throws Exception
    {
        Class<?> zoomEvent = Class.forName("javafx.scene.input.ZoomEvent"); //$NON-NLS-1$
        Object zoomAny = zoomEvent.getField("ANY").get(null); //$NON-NLS-1$
        Object zoomConsume = fxHandler(eventHandler,
            (event) -> event.getClass().getMethod("consume").invoke(event)); //$NON-NLS-1$
        webView.getClass()
            .getMethod("addEventFilter", eventType, eventHandler) //$NON-NLS-1$
            .invoke(webView, zoomAny, zoomConsume);

        Class<?> mouseEvent = Class.forName("javafx.scene.input.MouseEvent"); //$NON-NLS-1$
        Class<?> mouseButton = Class.forName("javafx.scene.input.MouseButton"); //$NON-NLS-1$
        Object primary = mouseButton.getField("PRIMARY").get(null); //$NON-NLS-1$
        Object[] mouseTypes = {
            mouseEvent.getField("MOUSE_PRESSED").get(null), //$NON-NLS-1$
            mouseEvent.getField("MOUSE_RELEASED").get(null), //$NON-NLS-1$
            mouseEvent.getField("MOUSE_CLICKED").get(null) //$NON-NLS-1$
        };
        Object mouseConsume = fxHandler(eventHandler, (event) ->
        {
            Object button = event.getClass().getMethod("getButton").invoke(event); //$NON-NLS-1$
            if (primary.equals(button))
                event.getClass().getMethod("consume").invoke(event); //$NON-NLS-1$
        });
        for (Object type : mouseTypes)
        {
            webView.getClass()
                .getMethod("addEventFilter", eventType, eventHandler) //$NON-NLS-1$
                .invoke(webView, type, mouseConsume);
        }
    }

    @FunctionalInterface
    private interface FxEventAction
    {
        void run(Object event) throws Exception;
    }

    private static Object fxHandler(Class<?> eventHandler, FxEventAction action)
        throws Exception
    {
        return java.lang.reflect.Proxy.newProxyInstance(
            eventHandler.getClassLoader(),
            new Class<?>[] { eventHandler },
            (proxy, method, args) ->
            {
                if ("handle".equals(method.getName()) && args != null && args.length == 1 //$NON-NLS-1$
                    && args[0] != null)
                {
                    try
                    {
                        action.run(args[0]);
                    }
                    catch (Throwable t)
                    {
                        Global.logError(LOG_TAG, "fx handler", t); //$NON-NLS-1$
                    }
                }
                return null;
            });
    }

    /**
     * Нативное SWT popup для ImageViewer. Dispose — asyncExec после Hide
     * (иначе Selection пункта не доезжает). Показ с timerExec после ПКМ —
     * иначе меню без трекинга мыши. Без лёгкого сдвига курсора в модальном
     * цикле {@code TrackPopupMenu} Windows не шлёт первый {@code WM_MOUSEMOVE} —
     * пункт под курсором без подсветки, пока мышь не сдвинут вручную.
     */
    private static void showZoomMenuSafe(Session session, int x, int y)
    {
        if (session.control == null || session.control.isDisposed())
            return;
        Shell shell = session.control.getShell();
        if (shell == null || shell.isDisposed())
            return;
        Menu menu = new Menu(session.control);
        addZoomItems(menu, session);
        Display display = shell.getDisplay();
        menu.addListener(SWT.Hide, ev -> display.asyncExec(() ->
        {
            if (!menu.isDisposed())
                menu.dispose();
        }));
        menu.setLocation(x, y);
        // setVisible блокирует до закрытия меню; timerExec отрабатывает внутри цикла.
        display.timerExec(1, () -> nudgeCursorForMenuHilite(display));
        menu.setVisible(true);
    }

    /** Сдвиг курсора на 1px и обратно — чтобы Windows подсветила пункт под мышью. */
    private static void nudgeCursorForMenuHilite(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        Point p = display.getCursorLocation();
        display.setCursorLocation(p.x + 1, p.y);
        display.setCursorLocation(p.x, p.y);
    }

    private static Label ensureMetaLabel(Composite host)
    {
        if (host == null || host.isDisposed())
            return null;
        Object existing = host.getData(META_LABEL_KEY);
        if (existing instanceof Label label && !label.isDisposed())
            return label;
        if (!(host.getLayout() instanceof GridLayout))
            return null;
        Label meta = new Label(host, SWT.NONE);
        meta.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        host.setData(META_LABEL_KEY, meta);
        host.layout(true, true);
        return meta;
    }

    private static void addZoomItems(Menu menu, Session session)
    {
        MenuItem zoomIn = new MenuItem(menu, SWT.PUSH);
        zoomIn.setText("Увеличить"); //$NON-NLS-1$
        ComfortSubmenuHelper.setMenuItemTooltip(zoomIn, "Увеличить масштаб картинки"); //$NON-NLS-1$
        zoomIn.addListener(SWT.Selection, e -> session.zoomIn());

        MenuItem zoomOut = new MenuItem(menu, SWT.PUSH);
        zoomOut.setText("Уменьшить"); //$NON-NLS-1$
        ComfortSubmenuHelper.setMenuItemTooltip(zoomOut, "Уменьшить масштаб картинки"); //$NON-NLS-1$
        zoomOut.addListener(SWT.Selection, e -> session.zoomOut());

        MenuItem original = new MenuItem(menu, SWT.PUSH);
        original.setText("Оригинальный размер"); //$NON-NLS-1$
        ComfortSubmenuHelper.setMenuItemTooltip(original, "Сбросить масштаб картинки"); //$NON-NLS-1$
        original.addListener(SWT.Selection, e -> session.resetZoom());
    }

    private static void scheduleMetaFromViewer(Session session)
    {
        if (!(session.control instanceof ImageViewer viewer) || viewer.isDisposed())
            return;
        Display display = viewer.getDisplay();
        for (int delay : new int[] { 150, 600, 1500 })
            display.timerExec(delay, () -> refreshMetaFromViewer(session, viewer));
    }

    private static void refreshMetaFromViewer(Session session, ImageViewer viewer)
    {
        if (viewer.isDisposed())
            return;
        try
        {
            URL url = (URL) Global.getField(viewer, "currentUrl"); //$NON-NLS-1$
            if (url == null)
            {
                setMetaText(session, ""); //$NON-NLS-1$
                return;
            }
            setMetaText(session, formatMeta(readUrlBytes(url), url.getPath()));
        }
        catch (Exception ex)
        {
            Global.logError(LOG_TAG, "meta viewer", ex); //$NON-NLS-1$
        }
    }

    private static void refreshMetaFromSwt(Session session)
    {
        if (!(session.control instanceof Label label) || label.isDisposed())
            return;
        Image image = resolveSource(session, label);
        if (image == null)
        {
            setMetaText(session, ""); //$NON-NLS-1$
            return;
        }
        try
        {
            setMetaText(session, sourceMeta(session, image));
        }
        catch (Exception ex)
        {
            setMetaText(session, ""); //$NON-NLS-1$
        }
    }

    /**
     * Мета SWT-превью по исходным байтам: у {@link Image} формат уже потерян, а глубина —
     * глубина экранного образа, не файла. Кеш по экземпляру {@link Image}: диалог создаёт
     * новый образ на каждую смену картинки, а мета пересчитывается ещё и на каждый Resize.
     */
    private static String sourceMeta(Session session, Image image)
    {
        if (session.metaImage == image && session.meta != null)
        {
            Global.tempLog(DIAG, "sourceMeta cached=" + session.meta); //$NON-NLS-1$ // agent log
            return session.meta;
        }
        byte[] bytes = null;
        if (session.sourceBytesSupplier != null)
        {
            try
            {
                bytes = session.sourceBytesSupplier.get();
            }
            catch (RuntimeException e)
            {
                Global.tempLogException(DIAG, "sourceMeta", e); //$NON-NLS-1$ // agent log
                Global.logError(LOG_TAG, "sourceMeta", e); //$NON-NLS-1$
            }
        }
        String meta = formatMeta(bytes, null, image.getImageData());
        session.meta = meta;
        session.metaImage = image;
        Global.tempLog(DIAG, "sourceMeta computed=" + meta + " bytes=" //$NON-NLS-1$ //$NON-NLS-2$
            + (bytes == null ? "null" : bytes.length)); //$NON-NLS-1$ // agent log
        return meta;
    }

    private static void setMetaText(Session session, String text)
    {
        // #region agent log — временная диагностика формата (не удалять без подтверждения фикса)
        Global.tempLog(DIAG, "setMetaText control=" + session.control.getClass().getSimpleName() //$NON-NLS-1$
            + " text='" + text + "' meta=" + (session.metaLabel == null ? "null" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : session.metaLabel.isDisposed() ? "disposed" : "ok visible=" + session.metaLabel.isVisible())); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        if (session.metaLabel == null || session.metaLabel.isDisposed())
            return;
        session.metaLabel.setText(text != null ? text : ""); //$NON-NLS-1$
    }

    /** agent log: тема временной диагностики формата. */
    static final String DIAG = "picture-format"; //$NON-NLS-1$

    private static void applyWebViewZoom(Session session)
    {
        if (!(session.control instanceof ImageViewer viewer) || viewer.isDisposed())
            return;
        try
        {
            Object webView = Global.getField(viewer, "webView"); //$NON-NLS-1$
            if (webView == null)
                return;
            double zoom = session.zoom;
            Class<?> platform = Class.forName("javafx.application.Platform"); //$NON-NLS-1$
            Method runLater = platform.getMethod("runLater", Runnable.class); //$NON-NLS-1$
            runLater.invoke(null, (Runnable) () ->
            {
                try
                {
                    webView.getClass().getMethod("setZoom", double.class) //$NON-NLS-1$
                        .invoke(webView, Double.valueOf(zoom));
                }
                catch (Throwable t)
                {
                    Global.logError(LOG_TAG, "setZoom", t); //$NON-NLS-1$
                }
            });
        }
        catch (Throwable ex)
        {
            Global.logError(LOG_TAG, "webview zoom", ex); //$NON-NLS-1$
        }
    }

    /**
     * Зум в «Выбрать картинку».
     * pictureComposite.heightHint фиксирован — увеличенный Label обрезается.
     * Без ScrolledComposite: поднимаем heightHint превью под размер картинки.
     */
    private static void applySwtZoom(Session session)
    {
        if (!(session.control instanceof Label label) || label.isDisposed())
            return;
        Image source = resolveSource(session, label);
        if (source == null)
            return;

        ImageData data = source.getImageData();
        Composite host = label.getParent();
        Rectangle area = host != null && !host.isDisposed()
            ? host.getClientArea()
            : new Rectangle(0, 0, Math.max(1, data.width), Math.max(1, data.height));

        double scale;
        if (session.zoom == 1.0)
        {
            scale = 1.0;
            if (area.width > 0 && area.height > 0 && data.width > 0 && data.height > 0)
            {
                scale = Math.min(area.width / (double) data.width,
                    area.height / (double) data.height);
                if (scale > 1.0)
                    scale = 1.0;
            }
        }
        else
            scale = session.zoom;

        int w = Math.max(1, (int) Math.round(data.width * scale));
        int h = Math.max(1, (int) Math.round(data.height * scale));
        final int maxPx = 2048;
        if (w > maxPx || h > maxPx)
        {
            double clamp = Math.min(maxPx / (double) data.width, maxPx / (double) data.height);
            if (session.zoom != 1.0)
                session.zoom = clamp;
            w = Math.max(1, (int) Math.round(data.width * clamp));
            h = Math.max(1, (int) Math.round(data.height * clamp));
        }

        Image scaled = new Image(label.getDisplay(), data.scaledTo(w, h));
        Image old = session.scaledImage;
        session.scaledImage = scaled;
        label.setImage(scaled);
        if (old != null && !old.isDisposed() && old != source)
            old.dispose();

        expandPictureHost(host, session, h);
        refreshMetaFromSwt(session);
    }

    /** {@code host} — Composite превью с heightHint (pictureComposite*), родитель Label. */
    private static void expandPictureHost(Composite host, Session session, int imageH)
    {
        if (host == null || host.isDisposed())
            return;
        Object ld = host.getLayoutData();
        if (!(ld instanceof GridData gd))
            return;
        if (session.baseHeightHint < 0)
            session.baseHeightHint = gd.heightHint > 0 ? gd.heightHint : host.getSize().y;

        int metaH = 0;
        if (session.metaLabel != null && !session.metaLabel.isDisposed())
            metaH = session.metaLabel.computeSize(SWT.DEFAULT, SWT.DEFAULT).y + 4;

        if (session.zoom == 1.0)
            gd.heightHint = session.baseHeightHint;
        else
            gd.heightHint = Math.max(session.baseHeightHint, imageH + metaH);

        Composite outer = host.getParent();
        if (outer != null && !outer.isDisposed())
            outer.layout(true, true);
        else
            host.layout(true, true);
    }

    private static void ensureLoadReapply(ImageViewer viewer, Session session)
    {
        if (viewer == null || viewer.isDisposed() || session == null)
            return;
        if (Boolean.TRUE.equals(viewer.getData(LOAD_HOOK_KEY)))
            return;
        try
        {
            Object webView = Global.getField(viewer, "webView"); //$NON-NLS-1$
            if (webView == null)
                return;
            Object engine = webView.getClass().getMethod("getEngine").invoke(webView); //$NON-NLS-1$
            Object worker = engine.getClass().getMethod("getLoadWorker").invoke(engine); //$NON-NLS-1$
            Object stateProperty = worker.getClass().getMethod("stateProperty").invoke(worker); //$NON-NLS-1$

            Class<?> changeListener = Class.forName("javafx.beans.value.ChangeListener"); //$NON-NLS-1$
            Display display = viewer.getDisplay();
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                changeListener.getClassLoader(),
                new Class<?>[] { changeListener },
                (proxy, method, args) ->
                {
                    if (!"changed".equals(method.getName()) || args == null || args.length < 3) //$NON-NLS-1$
                        return null;
                    Object newState = args[2];
                    String name = newState != null ? newState.toString() : ""; //$NON-NLS-1$
                    if (!"SUCCEEDED".equals(name)) //$NON-NLS-1$
                        return null;
                    display.asyncExec(() ->
                    {
                        if (viewer.isDisposed())
                            return;
                        bindSession(viewer, session);
                        applyWebViewZoom(session);
                        scheduleMetaFromViewer(session);
                    });
                    return null;
                });
            stateProperty.getClass()
                .getMethod("addListener", changeListener) //$NON-NLS-1$
                .invoke(stateProperty, listener);
            viewer.setData(LOAD_HOOK_KEY, Boolean.TRUE);
        }
        catch (Throwable t)
        {
            Global.logError(LOG_TAG, "ensureLoadReapply", t); //$NON-NLS-1$
        }
    }

    /** Оригинал не владеем (PictureDialog.currentImage) — не dispose. */
    private static Image resolveSource(Session session, Label label)
    {
        if (session.sourceImageSupplier != null)
        {
            Image source = session.sourceImageSupplier.get();
            if (source != null && !source.isDisposed())
                return source;
        }
        Image current = label.getImage();
        if (current == null || current.isDisposed())
            return null;
        if (session.scaledImage != null && current == session.scaledImage)
            return null;
        return current;
    }

    private static void disposeScaled(Session session)
    {
        if (session.scaledImage == null)
            return;
        if (session.control instanceof Label label && !label.isDisposed()
            && label.getImage() == session.scaledImage)
            label.setImage(null);
        if (!session.scaledImage.isDisposed())
            session.scaledImage.dispose();
        session.scaledImage = null;
    }

    private static byte[] readUrlBytes(URL url)
    {
        try (InputStream in = url.openStream())
        {
            return in.readAllBytes();
        }
        catch (Exception e)
        {
            try
            {
                return Files.readAllBytes(Path.of(url.toURI()));
            }
            catch (Exception e2)
            {
                return null;
            }
        }
    }

    private static String extensionOf(String path)
    {
        if (path == null || path.isBlank())
            return null;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1)
            return null;
        return name.substring(dot + 1);
    }

    /** Формат из {@code ImageData.type} (заполняет ImageLoader; у данных из Image не задан). */
    private static String formatFromType(ImageData data)
    {
        if (data == null)
            return "?"; //$NON-NLS-1$
        switch (data.type)
        {
            case SWT.IMAGE_PNG:
                return "PNG"; //$NON-NLS-1$
            case SWT.IMAGE_GIF:
                return "GIF"; //$NON-NLS-1$
            case SWT.IMAGE_JPEG:
                return "JPEG"; //$NON-NLS-1$
            case SWT.IMAGE_BMP:
            case SWT.IMAGE_BMP_RLE:
            case SWT.IMAGE_OS2_BMP:
                return "BMP"; //$NON-NLS-1$
            case SWT.IMAGE_ICO:
                return "ICO"; //$NON-NLS-1$
            case SWT.IMAGE_TIFF:
                return "TIFF"; //$NON-NLS-1$
            default:
                return "?"; //$NON-NLS-1$
        }
    }

    private static final class Session
    {
        final Control control;
        final Label metaLabel;
        final java.util.function.Supplier<Image> sourceImageSupplier;
        java.util.function.Supplier<byte[]> sourceBytesSupplier;
        /** Образ, для которого посчитана {@link #meta}. */
        Image metaImage;
        String meta;
        double zoom = 1.0;
        int baseHeightHint = -1;
        Image scaledImage;

        Session(Control control, Label metaLabel,
            java.util.function.Supplier<Image> sourceImageSupplier)
        {
            this.control = control;
            this.metaLabel = metaLabel;
            this.sourceImageSupplier = sourceImageSupplier;
            control.addDisposeListener(e -> disposeScaled(this));
        }

        void zoomIn()
        {
            zoom = Math.min(ZOOM_MAX, zoom * ZOOM_STEP);
            apply();
        }

        void zoomOut()
        {
            zoom = Math.max(ZOOM_MIN, zoom / ZOOM_STEP);
            apply();
        }

        void resetZoom()
        {
            zoom = 1.0;
            apply();
        }

        void apply()
        {
            if (control.isDisposed())
                return;
            if (control instanceof ImageViewer)
                applyWebViewZoom(this);
            else if (control instanceof Label label)
            {
                if (zoom == 1.0)
                {
                    disposeScaled(this);
                    // Вернуть штатное вписывание: если есть оригинал — пересчитать fit×1.
                    if (sourceImageSupplier != null && sourceImageSupplier.get() != null)
                        applySwtZoom(this);
                    else
                        refreshMetaFromSwt(this);
                }
                else
                    applySwtZoom(this);
            }
        }
    }
}
